package com.ilksese.busalarmclock.wait

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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

  /** 前台服务与状态更新同一条通知；结束等车后按同一 id 取消，不留幽灵通知。 */
  const val NOTIFICATION_ID = 1501

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
}
