package com.ilksese.busalarmclock.data

/** 一次等车的结束原因；用户主动停止不属于异常，不另发提示。 */
enum class WaitEndReason(val message: String) {
  CANCELLED("已主动取消，未触发出门提醒；不另发异常通知。"),
  MAX_DURATION("已到未触发上限，本次等车结束；需要时请重新开始。"),
  NO_SUCCESS("连续 5 分钟没有成功响应，本次等车结束。"),
  AUTH_FAILED("来源要求重新认证，本次等车结束。"),
  DENIED("来源拒绝访问，本次等车结束。"),
  SERVICE_TERMINATED("监测服务已被终止，本次等车结束。"),
  INTERRUPTED("上次等车被系统中断，没有继续监测，也没有补发提醒。"),
}

/** 当前等车页展示的状态类别；失败重试与"暂无预测"是不同语义。 */
enum class WaitPhase(val label: String) {
  WAITING_FIRST("等待本次新预测"),
  FRESH("有效预测"),
  NO_PREDICTION("暂无预测"),
  MIGHT_BE_LATE("按当前预计可能来不及"),
  RETRYING("获取失败，正在重试"),
}

/** 一条已选线路的当前预测；minutes 为 null 表示来源本次没有给出可用预测。 */
data class WaitingLine(
  val lineName: String,
  val endStationName: String,
  val minutes: Int?,
  val stops: Int?,
  val arriving: Boolean,
)

data class WaitState(
  val running: Boolean = false,
  val phase: WaitPhase? = null,
  val stopCode: String? = null,
  val stationName: String? = null,
  val walkMinutes: Double? = null,
  val bufferMinutes: Double? = null,
  val lines: List<WaitingLine> = emptyList(),
  val startedAt: Long? = null,
  val deadlineAt: Long? = null,
  val lastSuccessAt: Long? = null,
  val nextAttemptAt: Long? = null,
  val endedReason: WaitEndReason? = null,
  /** 结束发生的业务时间；用于重开时解释上次，而不是补发。 */
  val endedAt: Long? = null,
  val message: String? = null,
)

/**
 * 平台能力出口：常驻通知、结束提示与有界唤醒锁。
 * 自动化测试传入替身断言调用，绝不真实发声或震动。
 */
interface WaitPlatform {
  /** 通知总开关与出门提醒类别任一关闭即不能保证提醒，不能假启动。 */
  fun notificationsAllowed(): Boolean
  fun present(state: WaitState)
  /** 非主动结束时的一次短提示音与短震动；通知能力不允许时静默。 */
  fun announceEnd(reason: WaitEndReason)
  fun dismiss()
  /** 有界唤醒锁，最长不超过本次剩余监测时间。 */
  fun acquireWakeLock(timeoutMillis: Long)
  fun releaseWakeLock()
  /** 由可见页面启动同一非 sticky dataSync 前台服务；返回是否真的启动。 */
  suspend fun startForegroundService(): Boolean
  fun stopForegroundService()
}

/** 无平台实现时的安全默认：不做输出，也不占用资源。 */
object HeadlessWaitPlatform : WaitPlatform {
  override fun notificationsAllowed() = true
  override fun present(state: WaitState) = Unit
  override fun announceEnd(reason: WaitEndReason) = Unit
  override fun dismiss() = Unit
  override fun acquireWakeLock(timeoutMillis: Long) = Unit
  override fun releaseWakeLock() = Unit
  override suspend fun startForegroundService() = true
  override fun stopForegroundService() = Unit
}

/** 开始前同屏核对：站台、已选线路、步行时间 W 与预留余量 B。 */
data class WaitPreflight(
  val stopCode: String,
  val stationName: String,
  val lines: List<SavedLine>,
  val walkText: String = "",
  val bufferText: String = "2",
  val verifiedAt: Long? = null,
  val verifying: Boolean = false,
  val starting: Boolean = false,
  val error: String? = null,
) {
  /** 首次必填且大于 0；不自动估算，也不接受空值。 */
  val walkMinutes: Double? get() = walkText.trim().toDoubleOrNull()?.takeIf { it > 0 }
  /** 默认 2 分钟，允许 0。 */
  val bufferMinutes: Double? get() = bufferText.trim().toDoubleOrNull()?.takeIf { it >= 0 }
}

/** 一次等车的最少持久状态；用于下次打开时解释中断，不做自动恢复。 */
data class WaitTask(
  val id: Long,
  val stopCode: String,
  val stationName: String,
  val startedAt: Long,
  val deadlineAt: Long,
  val lastSuccessAt: Long?,
  val walkMinutes: Double,
  val bufferMinutes: Double,
  val linesJson: String,
  val phase: WaitPhase? = null,
  val endedReason: WaitEndReason? = null,
  val endedAt: Long? = null,
)

/**
 * 最少任务状态的本机存储。
 * [load] 读到的可能是仍在运行（上次被系统中断）或已结束的记录，由调用方解释；
 * 进度与结束写入都必须带任务身份，旧任务的回调不会改写新记录。
 */
interface WaitTaskStore {
  suspend fun load(): WaitTask?
  /** 关键状态保存失败必须抛错，调用方据此拒绝启动。 */
  suspend fun save(task: WaitTask)
  suspend fun progress(id: Long, phase: WaitPhase, lastSuccessAt: Long?)
  suspend fun finish(id: Long, reason: WaitEndReason, endedAt: Long)
  suspend fun clear()
}

internal fun formatMinutes(value: Double): String =
  if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
