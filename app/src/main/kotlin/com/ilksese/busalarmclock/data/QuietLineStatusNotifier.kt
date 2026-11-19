package com.ilksese.busalarmclock.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 生产环境的普通安静文字通知：只在通知授权可用时投递，不设置声音、震动或全屏意图，
 * 也从不启动出门提醒的强输出；任何异常都吞掉，避免影响关注项数据。
 */
class QuietLineStatusNotifier(private val context: Context) : LineStatusNotifier {
  override fun notify(event: LineStatusEvent) {
    runCatching {
      if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
      val manager = context.getSystemService(NotificationManager::class.java) ?: return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(
          NotificationChannel(CHANNEL_ID, "线路状态", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
          },
        )
      }
      val notification = android.app.Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle(event.title)
        .setContentText(event.text)
        .setStyle(android.app.Notification.BigTextStyle().bigText(event.text))
        .setGroupAlertBehavior(android.app.Notification.GROUP_ALERT_SUMMARY)
        .setAutoCancel(true)
        .build()
      manager.notify(event.text.hashCode(), notification)
    }
  }

  private companion object {
    const val CHANNEL_ID = "line-status"
  }
}
