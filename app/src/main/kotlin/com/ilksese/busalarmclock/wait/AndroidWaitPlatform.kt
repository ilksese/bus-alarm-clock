package com.ilksese.busalarmclock.wait

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ilksese.busalarmclock.MainActivity
import com.ilksese.busalarmclock.data.WaitEndReason
import com.ilksese.busalarmclock.data.WaitPlatform
import com.ilksese.busalarmclock.data.WaitState
import com.ilksese.busalarmclock.data.formatMinutes
import java.text.DateFormat
import java.util.Date

/** 应用级通知开关与出门提醒类别：任一关闭都不能保证提醒。 */
private const val PREFS = "bus_alarm_clock"
private const val KEY_GATE_OFF = "notificationGateOff"

/**
 * 真实平台实现：安静常驻通知 + 有界唤醒锁 + 一次短提示。
 * 本票只做监测与状态，不播放铃声或语音；那是后续出门提醒工单的范围。
 */
class AndroidWaitPlatform(private val context: Context) : WaitPlatform {
  private var wakeLock: PowerManager.WakeLock? = null
  private val channel: WaitChannel by lazy { SystemWaitChannel(context) }

  override fun notificationsAllowed(): Boolean {
    val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val gateOff = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GATE_OFF, false)
    val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return enabled && permission && !gateOff
  }

  override fun present(state: WaitState) {
    if (!notificationsAllowed()) return
    // 与前台服务共用同一渠道：授权前就开始监测、或渠道被清理时，这里同样要有渠道才能发。
    WaitNotifications.ensureChannel(channel)
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
    val stop = PendingIntent.getService(
      context,
      0,
      Intent(context, WaitService::class.java).setAction(WaitService.ACTION_STOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
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
      .apply { if (state.running) addAction(0, "结束等车", stop) }
      .build()
    runCatching { NotificationManagerCompat.from(context).notify(WaitNotifications.NOTIFICATION_ID, notification) }
  }

  /** 非主动结束才提示，且只一次短提示音与短震动。 */
  override fun announceEnd(reason: WaitEndReason) {
    if (reason == WaitEndReason.CANCELLED) return
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

  override suspend fun startForegroundService(): Boolean =
    runCatching { ContextCompat.startForegroundService(context, WaitService.start(context)) }.isSuccess

  override fun stopForegroundService() {
    runCatching { context.stopService(Intent(context, WaitService::class.java)) }
  }

  /** 系统提示音与本机提示统一使用通知声道，避免影响其他音量设置。 */
  @Suppress("unused")
  private val soundAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()

  @Suppress("unused")
  private fun formatClock(at: Long): String = DateFormat.getTimeInstance().format(Date(at))
}
