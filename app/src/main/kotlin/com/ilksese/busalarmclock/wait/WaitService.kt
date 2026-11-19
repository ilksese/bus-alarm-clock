package com.ilksese.busalarmclock.wait

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ilksese.busalarmclock.MainActivity
import com.ilksese.busalarmclock.R

/**
 * 等车期间的同一非 sticky dataSync 前台服务。
 * 只承载运行状态与停止入口；进程重启后不自动恢复，也不补发提醒。
 */
class WaitService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      WaitRuntime.requestStop()
      stopSelf()
      return START_NOT_STICKY
    }
    startForeground(
      NOTIFICATION_ID,
      notification(),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    super.onDestroy()
    // 服务被系统终止时同样结束本次监测，不装作仍在运行。
    WaitRuntime.onServiceDestroyed?.invoke()
  }

  private fun notification(): Notification {
    val open = android.app.PendingIntent.getActivity(
      this,
      1,
      Intent(this, MainActivity::class.java),
      android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, "waiting")
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("正在监测等车")
      .setContentText("每 60 秒刷新一次本次站台数据")
      .setOngoing(true)
      .setSilent(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setContentIntent(open)
      .build()
  }

  companion object {
    const val ACTION_STOP = "com.ilksese.busalarmclock.action.STOP_WAIT"
    private const val NOTIFICATION_ID = 1501
    fun start(context: Context): Intent = Intent(context, WaitService::class.java)
  }
}

/** 前台服务与可见页面之间的最小绑定：停止入口必须能真正结束同一轮等车。 */
object WaitRuntime {
  var onStop: (() -> Unit)? = null
  var onServiceDestroyed: (() -> Unit)? = null
  private var stopping = false

  fun requestStop() {
    if (stopping) return
    stopping = true
    try {
      onStop?.invoke()
    } finally {
      stopping = false
    }
  }
}
