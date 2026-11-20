package com.ilksese.busalarmclock.wait

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioAttributes
import androidx.core.app.NotificationCompat
import com.ilksese.busalarmclock.R

/** 创建（或确认已存在）前台服务用的通知渠道；测试注入它来断言调用顺序。 */
fun interface WaitChannel {
  fun ensure()
}

/**
 * 等车通知的唯一身份：渠道、通知 id、小图标与前台提升顺序。
 * 前台服务与页面状态更新共用这里，避免"一处建渠道、另一处直接发通知"。
 */
object WaitNotifications {
  const val CHANNEL_ID = "waiting"

  internal const val LEGACY_ALERT_CHANNEL_ID = "outing-alert"
  const val ALERT_CHANNEL_ID = "outing-alert-v2"

  /** 前台服务与状态更新同一条通知；结束等车后按同一 id 取消，不留幽灵通知。 */
  const val NOTIFICATION_ID = 1501

  internal const val ALERT_NOTIFICATION_ID = NOTIFICATION_ID + 1
  internal const val OPEN_ALERT_LIST = "com.ilksese.busalarmclock.OPEN_ALERT_LIST"
  internal const val OPEN_ALERT = "com.ilksese.busalarmclock.OPEN_ALERT"
  internal const val CONFIRM_ALERT = "com.ilksese.busalarmclock.CONFIRM_ALERT"
  internal const val DISMISS_ALERT = "com.ilksese.busalarmclock.DISMISS_ALERT"
  internal const val STOP_WAIT = "com.ilksese.busalarmclock.STOP_WAIT"

  // Android 的通知以 (tag, id) 区分；PendingIntent 以 action + data 区分，不比较 extras。
  internal fun alertTag(taskId: Long) = "outing-alert:$taskId"

  internal fun taskIntent(context: Context, target: Class<*>, action: String, taskId: Long): Intent =
    Intent(context, target).setAction(action).setData(taskUri(action, taskId))

  internal fun stopAction(context: Context, taskId: Long): PendingIntent = PendingIntent.getBroadcast(
    context, 0, taskIntent(context, WaitNotificationReceiver::class.java, STOP_WAIT, taskId), PendingIntent.FLAG_IMMUTABLE,
  )

  internal fun taskOf(intent: Intent, action: String): Long? {
    if (intent.action != action) return null
    val data = intent.data ?: return null
    val taskId = data.lastPathSegment?.toLongOrNull() ?: return null
    return taskId.takeIf { data == taskUri(action, it) }
  }

  private fun taskUri(action: String, taskId: Long): Uri =
    Uri.Builder().scheme("bus-alarm-clock").authority("tasks").appendPath(action).appendPath(taskId.toString()).build()

  /**
   * 带渠道 id 与纯 alpha 小图标的通知构造器。
   * 自适应启动图标会被部分 ROM 判为无效通知，这里只用单色矢量图。
   */
  fun builder(context: Context): NotificationCompat.Builder =
    NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_wait)
      .setSilent(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)

  /** 通知仅保留操作入口，系统不得另行播放延迟声音。 */
  fun alertBuilder(context: Context): NotificationCompat.Builder =
    NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_wait)
      .setSilent(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setAutoCancel(false)
      // 锁屏只显示摘要，具体线路与站台不泄露到锁屏详情。
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

  /** 幂等：渠道已存在时不动它，用户对渠道的调整不会被冲掉。 */
  fun ensureChannel(channel: WaitChannel) = channel.ensure()

  /**
   * 前台提升的唯一入口：渠道一定先于通知构造与 startForeground 存在。
   * 顺序颠倒会让系统以 "Bad notification for startForeground" 直接杀死进程。
   */
  fun startForeground(
    channel: WaitChannel,
    build: () -> Notification,
    start: (Notification) -> Unit,
  ) {
    channel.ensure()
    start(build())
  }
}

/** 系统渠道实现：只在缺失时创建，重复调用是安全空操作。 */
class SystemWaitChannel(private val context: Context) : WaitChannel {
  override fun ensure() {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    ensureMonitoring(manager)
    ensureAlert(manager)
  }

  private fun ensureMonitoring(manager: NotificationManager) {
    if (manager.getNotificationChannel(WaitNotifications.CHANNEL_ID) != null) return
    val channel = NotificationChannel(
      WaitNotifications.CHANNEL_ID,
      "等车监测",
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = "等车期间保持监测的运行状态"
      setSound(null, null)
      enableVibration(false)
      setShowBadge(false)
    }
    manager.createNotificationChannel(channel)
  }

  private fun ensureAlert(manager: NotificationManager) {
    if (manager.getNotificationChannel(WaitNotifications.ALERT_CHANNEL_ID) != null) return
    val channel = NotificationChannel(
      WaitNotifications.ALERT_CHANNEL_ID,
      "出门提醒",
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = "预计到站时间进入提醒窗口时通知你出门"
      // 稳定资源 URI 用于渠道设置；系统通知静默，由应用按权限和渠道设置输出短音。
      setSound(Uri.parse("android.resource://${context.packageName}/raw/outing_chime"), AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build())
      vibrationPattern = longArrayOf(0, 200)
      enableVibration(true)
      setShowBadge(true)
    }
    manager.createNotificationChannel(channel)
  }
}
