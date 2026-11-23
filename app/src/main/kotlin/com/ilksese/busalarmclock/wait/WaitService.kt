package com.ilksese.busalarmclock.wait

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import com.ilksese.busalarmclock.MainActivity
import com.ilksese.busalarmclock.data.BusSourceClient
import com.ilksese.busalarmclock.data.DatabaseProvider
import com.ilksese.busalarmclock.data.QuietLineStatusNotifier
import com.ilksese.busalarmclock.data.RoomWaitTaskStore
import com.ilksese.busalarmclock.data.RoomWatchItemStore
import com.ilksese.busalarmclock.data.WaitEndReason
import com.ilksese.busalarmclock.data.WatchItemApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 同一轮等车使用非 sticky 服务；取数用 dataSync，播放器就绪并取得焦点后切到 mediaPlayback，再立即播放。 */
class WaitService : Service() {
  private val channel: WaitChannel by lazy { SystemWaitChannel(this) }
  @Volatile private var owner: Owner? = null
  private data class Owner(val taskId: Long?, val startId: Int)

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val owner = intent?.getLongExtra(EXTRA_WAIT_TASK, Long.MIN_VALUE)?.takeIf { it != Long.MIN_VALUE }
    val obsolete = owner == null || !WaitRuntime.acceptsStart(owner)
    val previous = this.owner
    if (obsolete && previous?.taskId != null) {
      // 忽略身份，保留系统最新 startId，否则当前轮随后无法 stopSelfResult。
      this.owner = previous.copy(startId = startId)
      return START_NOT_STICKY
    }
    this.owner = Owner(owner, startId)
    try {
      WaitNotifications.startForeground(
        channel = channel,
        build = { notification(playing = false) },
        start = { startForeground(WaitNotifications.NOTIFICATION_ID, it, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) },
      )
      WaitRuntime.service = this
      if (obsolete || owner == null || !WaitRuntime.acceptsStart(owner)) {
        // 无本轮启动授权的 Intent 只完成 Android 要求的前台握手，不能留下服务。
        if (owner != null) stopOwned(owner)
        else if (stopSelfResult(startId)) {
          this.owner = null
          if (WaitRuntime.service === this) WaitRuntime.service = null
          stopForeground(STOP_FOREGROUND_REMOVE)
        }
      }
    } catch (error: Exception) {
      handoff(succeeded = false) { finishTask(it) }
      stopSelfResult(startId)
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    val current = WaitRuntime.service === this
    val taskId = owner?.taskId
    owner = null
    if (current) {
      WaitRuntime.service = null
      stopForeground(STOP_FOREGROUND_REMOVE)
    }
    super.onDestroy()
    if (current && taskId != null) finishTask(WaitEndReason.SERVICE_TERMINATED, taskId)
  }

  internal fun stopOwned(owner: Long) {
    val current = this.owner?.takeIf { it.taskId == owner } ?: return
    // 用捕获的 startId 停旧实例，不误停随后启动的新一轮。
    if (!stopSelfResult(current.startId) || this.owner !== current) return
    this.owner = null
    if (WaitRuntime.service === this) {
      WaitRuntime.service = null
      stopForeground(STOP_FOREGROUND_REMOVE)
    }
  }

  private fun finishTask(reason: WaitEndReason, taskId: Long? = owner?.taskId) {
    if (taskId == null) return
    WaitRuntime.scope.launch { WaitRuntime.application(this@WaitService).failWait(reason, taskId) }
  }

  internal fun owns(owner: Long) = owner == this.owner?.taskId

  internal fun promoteForPlayback(owner: Long, withinBudget: () -> Boolean): Boolean = runCatching {
    if (!owns(owner)) return false
    // dataSync 已建好渠道；调用者先取得焦点并准备短音，切换成功后才播放。
    val notification = notification(playing = true)
    if (!WaitRuntime.beginPlaybackPromotion(owner)) return false
    try {
      if (!owns(owner) || !WaitRuntime.acceptsStart(owner) || !withinBudget()) return false
      startForeground(WaitNotifications.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
      owns(owner) && WaitRuntime.acceptsStart(owner) && withinBudget()
    } finally {
      WaitRuntime.endPlaybackPromotion(owner)
    }
  }.getOrDefault(false)

  private fun notification(playing: Boolean): Notification {
    val open = PendingIntent.getActivity(
      this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
    return WaitNotifications.builder(this)
      .setContentTitle(if (playing) "出门提醒" else "正在监测等车")
      .setContentText(if (playing) "已停止取数" else "每 60 秒刷新一次本次站台数据")
      .setOngoing(true)
      .setContentIntent(open)
      .apply { owner?.taskId?.let { addAction(0, "结束等车", WaitNotifications.stopAction(this@WaitService, it)) } }
      .build()
  }

  companion object {
    private const val EXTRA_WAIT_TASK = "com.ilksese.busalarmclock.extra.WAIT_TASK"

    fun start(context: Context, taskId: Long): Intent =
      Intent(context, WaitService::class.java).putExtra(EXTRA_WAIT_TASK, taskId)

    fun handoff(succeeded: Boolean, finish: (WaitEndReason) -> Unit) {
      if (!succeeded) finish(WaitEndReason.SERVICE_TERMINATED)
    }

    fun clearNotification(context: Context) {
      runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(WaitNotifications.NOTIFICATION_ID) }
    }
  }
}

/** 进程内复用同一等车入口，通知动作不依赖 Activity 或 Compose 的生命周期。 */
internal object WaitRuntime {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  @Volatile var service: WaitService? = null
  @Volatile internal var requestedTask: Long? = null
  @Volatile private var stoppedRequest = false

  private var promotingTask: Long? = null

  @Synchronized fun outputReleased(): Boolean {
    val task = requestedTask
    return promotingTask == null && (task == null || (stoppedRequest && service?.owns(task) != true))
  }

  @Synchronized fun requestStart(taskId: Long): Boolean {
    // shortcut: Android 无法取消已发出的 Binder；返回前拒绝新一轮，避免迟到调用覆盖它。
    if (promotingTask != null) return false
    requestedTask = taskId
    stoppedRequest = false
    return true
  }

  @Synchronized fun beginPlaybackPromotion(owner: Long): Boolean {
    if (promotingTask != null || !acceptsStart(owner)) return false
    promotingTask = owner
    return true
  }

  @Synchronized fun endPlaybackPromotion(owner: Long) {
    if (promotingTask == owner) promotingTask = null
  }

  fun acceptsStart(taskId: Long): Boolean = requestedTask == taskId && !stoppedRequest

  fun stopService(taskId: Long) {
    val current = synchronized(this) {
      if (requestedTask == taskId) stoppedRequest = true
      service
    }
    current?.stopOwned(taskId)
  }
  internal var behavior: WatchItemApplication? = null

  fun application(context: Context): WatchItemApplication = behavior ?: run {
    val app = context.applicationContext
    val database = DatabaseProvider.create(app)
    WatchItemApplication(
      BusSourceClient(), RoomWatchItemStore(database), AndroidWaitPlatform(app),
      tasks = RoomWaitTaskStore(database, app.getSharedPreferences("alert_submission_receipts", Context.MODE_PRIVATE)),
      scope = scope, elapsedClock = SystemClock::elapsedRealtime,
      notifier = QuietLineStatusNotifier(app),
    ).also { behavior = it }
  }
}

/** 通知按钮和划除共用确认入口；这两种动作都不启动前台服务。 */
class WaitNotificationReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    val taskId = WaitNotifications.taskOf(intent, action) ?: return
    if (action != WaitNotifications.STOP_WAIT && action != WaitNotifications.CONFIRM_ALERT && action != WaitNotifications.DISMISS_ALERT) return
    val pending = goAsync()
    WaitRuntime.scope.launch {
      try {
        dispatch(WaitRuntime.application(context), action, taskId)
      } finally {
        pending?.finish()
      }
    }
  }

  companion object {
    internal suspend fun dispatch(application: WatchItemApplication, action: String, taskId: Long) {
      when (action) {
        WaitNotifications.STOP_WAIT -> application.stopWait(taskId)
        WaitNotifications.CONFIRM_ALERT, WaitNotifications.DISMISS_ALERT -> application.confirmAlert(taskId, fromNotification = true)
      }
    }
  }
}
