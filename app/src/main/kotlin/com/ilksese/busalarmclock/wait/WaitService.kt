package com.ilksese.busalarmclock.wait

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.ilksese.busalarmclock.MainActivity
import com.ilksese.busalarmclock.data.WaitEndReason

/**
 * 等车期间的同一非 sticky dataSync 前台服务。
 * 只承载运行状态与停止入口；进程重启后不自动恢复，也不补发提醒。
 */
class WaitService : Service() {
  private val channel: WaitChannel by lazy { SystemWaitChannel(this) }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      WaitRuntime.requestStop()
      stopSelf()
      return START_NOT_STICKY
    }
    try {
      // 渠道必须先于 startForeground 存在，否则系统直接杀进程而不是返回错误。
      WaitNotifications.startForeground(
        channel = channel,
        build = ::notification,
        start = { startForeground(WaitNotifications.NOTIFICATION_ID, it, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) },
      )
    } catch (error: Exception) {
      // 系统在进程外杀进程时这里不会执行，那是不可控的系统行为；
      // 能在进程内接住的失败（ROM 拒绝、类型不允许）必须结束本轮，不能假装仍在前台。
      handoff(succeeded = false) { WaitRuntime.finish(it) }
      stopSelf()
      return START_NOT_STICKY
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    // 结束后立即撤下前台通知，不留给系统在状态栏留下幽灵通知。
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
    // 服务被系统终止时同样结束本次监测，不装作仍在运行。
    WaitRuntime.onServiceDestroyed?.invoke()
  }

  private fun notification(): Notification {
    val open = PendingIntent.getActivity(
      this,
      1,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return WaitNotifications.builder(this)
      .setContentTitle("正在监测等车")
      .setContentText("每 60 秒刷新一次本次站台数据")
      .setOngoing(true)
      .setContentIntent(open)
      .build()
  }

  companion object {
    const val ACTION_STOP = "com.ilksese.busalarmclock.action.STOP_WAIT"

    fun start(context: Context): Intent = Intent(context, WaitService::class.java)

    /** 前台提升的结果必须交给调用方：失败即结束本轮，成功则继续。 */
    fun handoff(succeeded: Boolean, finish: (WaitEndReason) -> Unit) {
      if (!succeeded) finish(WaitEndReason.SERVICE_TERMINATED)
    }

    /** 结束等车后按同一 id 取消通知，避免状态栏残留。 */
    fun clearNotification(context: Context) {
      runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(WaitNotifications.NOTIFICATION_ID) }
    }
  }
}

/** 前台服务与可见页面之间的最小绑定：停止入口必须能真正结束同一轮等车。 */
object WaitRuntime {
  var onStop: (() -> Unit)? = null
  var onServiceDestroyed: (() -> Unit)? = null

  /** 服务自己判定的一轮结束（例如前台提升失败）：页面结束同一轮，而不是另开一份。 */
  var onFinish: ((WaitEndReason) -> Unit)? = null
  private var stopping = false
  private var finishing = false

  fun requestStop() {
    if (stopping) return
    stopping = true
    try {
      onStop?.invoke()
    } finally {
      stopping = false
    }
  }

  fun finish(reason: WaitEndReason) {
    if (finishing) return
    finishing = true
    try {
      onFinish?.invoke(reason)
    } finally {
      finishing = false
    }
  }
}
