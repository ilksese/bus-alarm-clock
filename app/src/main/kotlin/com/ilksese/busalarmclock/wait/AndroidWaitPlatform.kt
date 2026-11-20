package com.ilksese.busalarmclock.wait

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ilksese.busalarmclock.MainActivity
import com.ilksese.busalarmclock.R
import com.ilksese.busalarmclock.data.AlertSnapshot
import com.ilksese.busalarmclock.data.WaitEndReason
import com.ilksese.busalarmclock.data.WaitPlatform
import com.ilksese.busalarmclock.data.WaitState
import com.ilksese.busalarmclock.data.formatMinutes
import com.ilksese.busalarmclock.data.formatAlertEta
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 应用级通知开关与出门提醒类别：任一关闭都不能保证提醒。 */
private const val PREFS = "bus_alarm_clock"
private const val KEY_GATE_OFF = "notificationGateOff"

/** Android 通知与提醒输出适配器；业务会话决定何时开始和停止。 */
class AndroidWaitPlatform(private val context: Context) : WaitPlatform {
  private var wakeLock: PowerManager.WakeLock? = null
  private val postingLock = Any()
  @Volatile private var slotOwner: Long? = null
  private val output = AtomicReference<AlertOutput?>(null)
  private val vibrationLock = Any()
  private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Only local handles are synchronized: Android service calls must not hold the cleanup gate. */
  private class AlertOutput(val owner: Long) {
    val player = AtomicReference<MediaPlayer?>(null)
    val toneOwner = AtomicBoolean(true)
    @Volatile var vibrator: Vibrator? = null
    val focus = AtomicReference<AudioFocusRequest?>(null)
    @Volatile var listener: AudioManager.OnModeChangedListener? = null
  }
  private val audio get() = context.getSystemService(AudioManager::class.java)
  private val channel: WaitChannel by lazy { SystemWaitChannel(context) }

  override fun notificationsAllowed(): Boolean {
    val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val gateOff = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GATE_OFF, false)
    val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!enabled || !permission || gateOff) return false
    // 从系统授权页首次返回时渠道还不存在；创建后才读取用户对该渠道的实际设置。
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return runCatching {
      if (manager.getNotificationChannel(WaitNotifications.LEGACY_ALERT_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
      WaitNotifications.ensureChannel(channel)
      manager.getNotificationChannel(WaitNotifications.ALERT_CHANNEL_ID)
        ?.let { it.importance != NotificationManager.IMPORTANCE_NONE } == true
    }.getOrDefault(false)
  }

  override fun present(state: WaitState) {
    if (!notificationsAllowed()) return
    val text = state.phase?.label ?: "等待本次新预测"
    val detail = buildString {
      append(state.stationName.orEmpty())
      val walk = state.walkMinutes
      val buffer = state.bufferMinutes
      if (walk != null && buffer != null) {
        append(" · 预计进入 ")
        append(formatMinutes(walk))
        append("–")
        append(formatMinutes(walk + buffer))
        append(" 分钟时提醒你")
      }
    }
    val stop = state.taskId?.let { WaitNotifications.stopAction(context, it) }
    val open = PendingIntent.getActivity(
      context,
      1,
      Intent(context, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification: Notification = WaitNotifications.builder(context)
      .setContentTitle(if (state.running) "正在监测 ${text}" else (state.endedReason?.message ?: text))
      .setContentText(detail)
      .setOngoing(state.running)
      .setContentIntent(open)
      .apply { if (state.running && stop != null) addAction(0, "结束等车", stop) }
      .build()
    runCatching { NotificationManagerCompat.from(context).notify(WaitNotifications.NOTIFICATION_ID, notification) }
  }

  /** 非主动结束才提示，且只一次短提示音与短震动。 */
  override fun announceEnd(reason: WaitEndReason) {
    if (reason == WaitEndReason.CANCELLED || ringerMode() != AudioManager.RINGER_MODE_NORMAL || !outsideDnd()) return
    val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    runCatching {
      RingtoneManager.getRingtone(context, sound)?.play()
    }
    runCatching {
      val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
      } else {
        @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
      }
      vibrator?.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
    }
  }

  override fun dismiss() {
    WaitService.clearNotification(context)
  }

  override fun submitAlert(alert: AlertSnapshot, remainingMillis: () -> Long): Boolean {
    if (!notificationsAllowed()) return false
    // 同步提交并不保证 SystemUI 展示；任何系统通知本身均静默。
    return runCatching {
      // 一个迟到的 Binder 调用不能在本进程的新任务通知之后覆盖槽位。
      synchronized(postingLock) { postAlert(alert, remainingMillis = remainingMillis) }
    }.getOrDefault(false)
  }

  override suspend fun canStartAlertOutput(taskId: Long, remainingMillis: () -> Long): Boolean {
    // activeNotifications 也可能含排队项。给系统处理时间后连续检查同一个任务，缺条时只保留快照。
    // 即便通过，也不能视为用户可见回执。
    repeat(2) {
      if (remainingMillis() <= 125L) return false
      delay(100)
      if (!notificationsAllowed() || !slotContains(taskId)) return false
    }
    return remainingMillis() > 25L
  }

  override fun postedAlertIds(): Set<Long> = runCatching {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return emptySet()
    manager.activeNotifications.asSequence()
      .filter { it.tag == null && it.id == WaitNotifications.ALERT_NOTIFICATION_ID && it.notification.channelId == WaitNotifications.ALERT_CHANNEL_ID }
      .map { it.notification.extras.getLong(EXTRA_ALERT_OWNER, Long.MIN_VALUE) }
      .filter { it != Long.MIN_VALUE }
      .toSet()
  }.getOrDefault(emptySet())

  private fun slotContains(taskId: Long): Boolean = taskId in postedAlertIds()

  override fun clearAlert(taskId: Long) { withdrawAlert(taskId) }

  override fun withdrawAlert(taskId: Long): Boolean = runCatching {
    val manager = NotificationManagerCompat.from(context)
    manager.cancel(WaitNotifications.alertTag(taskId), WaitNotifications.ALERT_NOTIFICATION_ID)
    synchronized(postingLock) {
      // 同一进程以最新提交者为准；进程重开才从系统恢复槽位身份。
      if (slotOwner == taskId || (slotOwner == null && slotContains(taskId))) {
        manager.cancel(WaitNotifications.ALERT_NOTIFICATION_ID)
        slotOwner = null
      }
    }
  }.isSuccess

  private fun action(action: String, taskId: Long): PendingIntent = PendingIntent.getBroadcast(
    context, 0, WaitNotifications.taskIntent(context, WaitNotificationReceiver::class.java, action, taskId),
    PendingIntent.FLAG_IMMUTABLE,
  )

  internal fun postAlert(alert: AlertSnapshot, remainingMillis: () -> Long = { Long.MAX_VALUE }): Boolean {
    WaitNotifications.ensureChannel(channel)
    val open = PendingIntent.getActivity(
      context, 0, WaitNotifications.taskIntent(context, MainActivity::class.java, WaitNotifications.OPEN_ALERT, alert.taskId)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val list = PendingIntent.getActivity(
      context, 0, Intent(context, MainActivity::class.java).setAction(WaitNotifications.OPEN_ALERT_LIST)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val fetched = DateFormat.getDateTimeInstance().format(Date(alert.fetchedAt))
    val text = "${alert.stationName} · 站台 ${alert.stopCode} · 触发时预计 ${formatAlertEta(alert.main.etaSeconds)} · 获取时间 $fetched"
    val others = alert.others.joinToString("、") { "${it.lineName} 约 ${formatAlertEta(it.etaSeconds)}" }
    val details = buildString {
      append(text)
      if (others.isNotEmpty()) append("\n同在窗口：$others")
      append("\n更早的未确认提醒可在应用的当前等车页查看。")
    }
    val notification = WaitNotifications.alertBuilder(context)
      .addExtras(android.os.Bundle().apply { putLong(EXTRA_ALERT_OWNER, alert.taskId) })
      .setContentTitle("${alert.main.lineName} 开往${alert.main.endStationName} 该出门了")
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(details))
      .setContentIntent(open)
      .setDeleteIntent(action(WaitNotifications.DISMISS_ALERT, alert.taskId))
      .addAction(0, "知道了，停止提醒", action(WaitNotifications.CONFIRM_ALERT, alert.taskId))
      .addAction(0, "查看未确认提醒", list)
      .build()
    if (remainingMillis() <= 0) return false
    val manager = NotificationManagerCompat.from(context)
    // 只撤旧版通知的显示项，数据库快照与旧动作的任务身份都保留。
    runCatching {
      context.getSystemService(NotificationManager::class.java)?.activeNotifications?.forEach {
        if (it.id == WaitNotifications.ALERT_NOTIFICATION_ID && it.tag?.startsWith("outing-alert:") == true) manager.cancel(it.tag, it.id)
      }
    }
    if (remainingMillis() <= 0 || !notificationsAllowed()) return false
    slotOwner = alert.taskId
    manager.notify(WaitNotifications.ALERT_NOTIFICATION_ID, notification)
    // Binder 已接受提交；是否迟到由会话判定，并在撤回成功前保留快照。
    return true
  }

  private fun ringerMode(): Int? = audio?.ringerMode

  private fun outsideDnd(): Boolean = context.getSystemService(NotificationManager::class.java)
    ?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL

  override fun startAlertOutput(taskId: Long, remainingMillis: () -> Long, onInterrupted: () -> Unit): Long {
    if (!notificationsAllowed() || !slotContains(taskId) || !outsideDnd() || remainingMillis() <= 0L) return 0
    val manager = audio ?: return 0
    if (manager.mode != AudioManager.MODE_NORMAL) return 0
    val settings = context.getSystemService(NotificationManager::class.java)
      ?.getNotificationChannel(WaitNotifications.ALERT_CHANNEL_ID) ?: return 0
    val carrier = AlertOutput(taskId)
    if (!output.compareAndSet(null, carrier)) return 0
    fun active() = output.get() === carrier && remainingMillis() > 0
    fun interrupt() {
      if (output.get() !== carrier) return
      stopAlertOutput(taskId)
      onInterrupted()
    }
    if (!active()) { stopAlertOutput(taskId); return 0 }
    val listener = AudioManager.OnModeChangedListener { mode ->
      if (mode != AudioManager.MODE_NORMAL) interrupt()
    }
    manager.addOnModeChangedListener(ContextCompat.getMainExecutor(context), listener)
    carrier.listener = listener
    if (!active()) {
      manager.removeOnModeChangedListener(listener)
      stopAlertOutput(taskId)
      return 0
    }
    val toneDuration = if (settings.sound != null && ringerMode() == AudioManager.RINGER_MODE_NORMAL) {
      runCatching { playAlertTone(carrier, manager, remainingMillis, ::interrupt) }.getOrDefault(0L)
    } else 0L
    if (toneDuration == 0L) stopAlertTone(carrier)
    val vibrationDuration = if (active() && settings.shouldVibrate() && manager.mode == AudioManager.MODE_NORMAL &&
      (ringerMode() == AudioManager.RINGER_MODE_NORMAL || ringerMode() == AudioManager.RINGER_MODE_VIBRATE)) {
      runCatching { vibrateAlert(carrier, remainingMillis) }.getOrDefault(0L)
    } else 0L
    return maxOf(toneDuration, vibrationDuration)
  }

  private fun playAlertTone(carrier: AlertOutput, manager: AudioManager, remainingMillis: () -> Long, onInterrupted: () -> Unit): Long {
    if (manager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0) return 0
    val owner = carrier.owner
    val service = WaitRuntime.service?.takeIf { it.owns(owner) } ?: return 0
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
    // 短音不循环，原生播放器也有一份独立的时长上限。
    val player = MediaPlayer.create(context, R.raw.outing_chime, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE) ?: return 0
    carrier.player.set(player)
    val duration = player.duration.toLong()
    if (duration !in 1L..250L) return 0
    fun ready() = output.get() === carrier && remainingMillis() >= duration + 25L
    if (!ready() || manager.mode != AudioManager.MODE_NORMAL || !notificationsAllowed() || !slotContains(owner)) return 0
    val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
      .setAudioAttributes(attributes)
      .setWillPauseWhenDucked(true)
      .setOnAudioFocusChangeListener { change ->
        if (change < 0 && carrier.toneOwner.get()) onInterrupted()
      }.build()
    if (!ready() || manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return 0
    carrier.focus.set(focus)
    if (manager.mode != AudioManager.MODE_NORMAL || !notificationsAllowed() || !slotContains(owner) || !ready()) return 0
    // player 已准备完毕且焦点已获准；提升在 IO 上，独立看门狗不等它返回。
    if (!service.promoteForPlayback(owner, ::ready) || !ready()) {
      onInterrupted()
      return 0
    }
    if (manager.mode != AudioManager.MODE_NORMAL || !notificationsAllowed() || !slotContains(owner) || !ready()) return 0
    val endsAt = SystemClock.elapsedRealtime() + duration
    player.start()
    return (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
  }

  private fun stopAlertTone(carrier: AlertOutput) {
    carrier.toneOwner.set(false)
    val player = carrier.player.getAndSet(null)
    runCatching { player?.release() }
    carrier.focus.getAndSet(null)?.let { focus ->
      cleanupScope.launch { runCatching { audio?.abandonAudioFocusRequest(focus) } }
    }
  }

  private fun vibrateAlert(carrier: AlertOutput, remainingMillis: () -> Long): Long {
    val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return 0
    if (!vibrator.hasVibrator() || !notificationsAllowed() || !slotContains(carrier.owner)) return 0
    return synchronized(vibrationLock) {
      val duration = minOf(200L, remainingMillis() - 25L)
      if (duration <= 0 || output.get() !== carrier) return@synchronized 0L
      carrier.vibrator = vibrator
      vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE),
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
      duration
    }
  }

  override fun stopAlertOutput(taskId: Long) {
    val carrier = output.get()?.takeIf { it.owner == taskId } ?: return
    if (!output.compareAndSet(carrier, null)) return
    // Independent cleanup paths: an audio or vibrator Binder must not delay service and lock release.
    cleanupScope.launch { stopAlertTone(carrier) }
    carrier.listener?.let { listener -> cleanupScope.launch { runCatching { audio?.removeOnModeChangedListener(listener) } } }
    cleanupScope.launch {
      synchronized(vibrationLock) {
        // 取消是进程级操作；旧任务不能取消新一轮已发出的震动。
        if (output.get()?.vibrator == null) runCatching { carrier.vibrator?.cancel() }
      }
    }
  }

  override fun acquireWakeLock(timeoutMillis: Long) {
    releaseWakeLock()
    val manager = context.getSystemService(PowerManager::class.java) ?: return
    // 有界唤醒锁：最长不超过本次剩余监测时间，只保证 CPU，不点亮屏幕。
    val lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bus-alarm-clock:waiting")
    lock.setReferenceCounted(false)
    runCatching { lock.acquire(timeoutMillis.coerceIn(1, 30 * 60_000L)) }
    wakeLock = lock
  }

  override fun releaseWakeLock() {
    wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
    wakeLock = null
  }

  override suspend fun startForegroundService(taskId: Long): Boolean {
    if (!WaitRuntime.requestStart(taskId)) return false
    return runCatching { ContextCompat.startForegroundService(context, WaitService.start(context, taskId)) }.isSuccess
  }

  override fun stopForegroundService(taskId: Long) {
    runCatching { WaitRuntime.stopService(taskId) }
  }

}

private const val EXTRA_ALERT_OWNER = "com.ilksese.busalarmclock.extra.ALERT_OWNER"
