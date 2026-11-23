package com.ilksese.busalarmclock.data

import java.math.BigDecimal

/** 一次等车的结束原因；用户主动停止不属于异常，不另发提示。 */
enum class WaitEndReason(val message: String) {
  CANCELLED("已主动取消，未触发出门提醒；不另发异常通知。"),
  CANCELLED_DURING_DELIVERY("已主动结束等车；结束时提醒仍在提交，无法确认是否展示。"),
  MAX_DURATION("已到未触发上限，本次等车结束；需要时请重新开始。"),
  NO_SUCCESS("连续 5 分钟没有成功响应，本次等车结束。"),
  AUTH_FAILED("来源要求重新认证，本次等车结束。"),
  DENIED("来源拒绝访问，本次等车结束。"),
  SERVICE_TERMINATED("监测服务已被终止，本次等车结束。"),
  ALERT_DELIVERY_FAILED("出门提醒未能投递，未播放铃声或震动，本次等车结束。"),
  NOTIFICATIONS_UNAVAILABLE("通知权限或出门提醒类别已关闭，本次等车结束。请开启后重新开始。"),
  INTERRUPTED("上次等车被系统中断，没有继续监测，也没有补发提醒。"),
  ALERTED("出门提醒已触发；尚未确认，不会自动重响。"),
  ALERT_OUTPUT_INTERRUPTED("出门提醒已提交，但输出可能在结束前中断；不会自动重响。"),
  ALERT_CONFIRMED("出门提醒已确认，本次输出已停止。"),
}

/** 当前等车页展示的状态类别；失败重试与"暂无预测"是不同语义。 */
enum class WaitPhase(val label: String) {
  WAITING_FIRST("等待本次新预测"),
  FRESH("有效预测"),
  NO_PREDICTION("暂无预测"),
  MIGHT_BE_LATE("按当前预计可能来不及"),
  RETRYING("获取失败，正在重试"),
  ALERTED("正在提醒"),
}

/**
 * 触发时的一条线路快照：线路、方向与来源给出的目标站预计到站分钟数。
 * 触发之后不再随新预测变化，也不继续倒计时。
 */
@kotlinx.serialization.Serializable
data class AlertLine(
  val lineNo: String,
  val lineName: String,
  val upDown: Int,
  val endStationName: String,
  val etaMinutes: Int,
  val etaSeconds: Long = etaMinutes * 60L,
)

/**
 * 出门提醒的已触发事实与提醒快照；写入本机之后本次等车不再重新判定。
 * [taskId] 是触发它的那一轮等车身份，通知动作据此校验归属。
 */
@kotlinx.serialization.Serializable
data class AlertSnapshot(
  val taskId: Long,
  val stopCode: String,
  val stationName: String,
  val main: AlertLine,
  val others: List<AlertLine> = emptyList(),
  /** 触发时主要线路的预计到站分钟数；与 [fetchedAt] 一起说明依据。 */
  val etaMinutes: Int,
  val fetchedAt: Long,
  val triggeredAt: Long,
)

/** 提醒的运行态：快照本身不可变，确认与输出结束是它的两个独立事实。 */
data class OutingAlert(
  val snapshot: AlertSnapshot,
  val confirmed: Boolean = false,
  val outputEnded: Boolean = false,
  val confirmationError: Boolean = false,
  val submissionPending: Boolean = false,
)

/** 一条已选线路的当前预测；minutes 为 null 表示来源本次没有给出可用预测。 */
data class WaitingLine(
  val lineName: String,
  val endStationName: String,
  val minutes: Int?,
  val stops: Int?,
  val arriving: Boolean,
  val obsolete: Boolean = false,
)

data class WaitState(
  val running: Boolean = false,
  val taskId: Long? = null,
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
  /** 已触发的出门提醒（含更早未确认的），按触发时间倒序；未确认的不会被新任务静默清除。 */
  val alerts: List<OutingAlert> = emptyList(),
) {
  /** 当前要展示的那条提醒：最新一条。 */
  val alert: OutingAlert? get() = alerts.firstOrNull()
}

/**
 * 平台能力出口：常驻通知、结束提示、出门提醒输出与有界唤醒锁。
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
  suspend fun startForegroundService(taskId: Long): Boolean
  fun stopForegroundService(taskId: Long)

  /** true 仅表示同步提交成功，不代表系统已展示、播放或用户已确认。 */
  fun submitAlert(alert: AlertSnapshot, remainingMillis: () -> Long): Boolean
  /** 额外的保守门槛，不代表 SystemUI 已展示。等待期间允许用户停止。 */
  suspend fun canStartAlertOutput(taskId: Long, remainingMillis: () -> Long): Boolean = true
  suspend fun chineseVoiceAvailable(): Boolean = true
  suspend fun previewReminder(voiceEnabled: Boolean): ReminderPreview = ReminderPreview()
  suspend fun startAlertOutput(alert: AlertSnapshot, voiceEnabled: Boolean, remainingMillis: () -> Long, onInterrupted: () -> Unit): Long =
    startAlertOutput(alert.taskId, remainingMillis, onInterrupted)
  fun startAlertOutput(taskId: Long, remainingMillis: () -> Long, onInterrupted: () -> Unit): Long = 0L
  fun stopAlertOutput(taskId: Long) = Unit
  /** 新任务不接手尚未完成的旧输出清理。 */
  suspend fun awaitPreviousOutput(): Boolean = true
  /** 用户确认后撤下这条提醒的通知；按任务身份定位，不误撤新一轮的通知。 */
  fun clearAlert(taskId: Long)
  /** 未能持久记录投递时，确认系统撤回后才可清理快照。 */
  fun withdrawAlert(taskId: Long): Boolean = runCatching { clearAlert(taskId) }.isSuccess
  /** 系统当前仍持有的任务身份，仅用于重开后恢复快照，不授权播放。 */
  fun postedAlertIds(): Set<Long> = emptySet()
}

/** 无平台实现时的安全默认：不做输出，也不占用资源。 */
object HeadlessWaitPlatform : WaitPlatform {
  override fun notificationsAllowed() = true
  override fun present(state: WaitState) = Unit
  override fun announceEnd(reason: WaitEndReason) = Unit
  override fun dismiss() = Unit
  override fun acquireWakeLock(timeoutMillis: Long) = Unit
  override fun releaseWakeLock() = Unit
  override suspend fun startForegroundService(taskId: Long) = true
  override fun stopForegroundService(taskId: Long) = Unit
  override fun submitAlert(alert: AlertSnapshot, remainingMillis: () -> Long) = false
  override fun clearAlert(taskId: Long) = Unit
}

/** 试听结果只记录平台已启动的输出，不代表用户已听见或感到震动。 */
data class ReminderPreview(
  val chime: Boolean = false,
  val ringtone: Boolean = false,
  val vibration: Boolean = false,
  val voice: Boolean = false,
  val interrupted: Boolean = false,
)

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
  val notificationBlocked: Boolean = false,
  val error: String? = null,
  internal val verification: Any? = null,
  val voiceUnavailable: Boolean = false,
  val checkingVoice: Boolean = false,
  val previewMessage: String? = null,
) {
  /** 首次必填且大于 0；不自动估算，也不接受空值。 */
  val walkMinutes: Double? get() = walkText.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
  /** 默认 2 分钟，允许 0。 */
  val bufferMinutes: Double? get() = bufferText.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
}

data class WaitResult(val taskId: Long, val stationName: String, val reason: WaitEndReason)

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
  /** 已触发事实与提醒快照；同一轮最多一条，写入后不再随新预测变化。 */
  val alert: AlertSnapshot? = null,
  val alertConfirmed: Boolean = false,
)

/**
 * 最少任务状态的本机存储。
 * [load] 读到的可能是仍在运行（上次被系统中断）或已结束的记录，由调用方解释；
 * 进度与结束写入都必须带任务身份，旧任务的回调不会改写新记录。
 */
interface WaitTaskStore {
  suspend fun load(): WaitTask?
  suspend fun latestResult(): WaitTask? = load()?.takeIf { it.endedReason != null }
  /** 关键状态保存失败必须抛错，调用方据此拒绝启动。 */
  suspend fun save(task: WaitTask)
  suspend fun progress(id: Long, phase: WaitPhase, lastSuccessAt: Long?)
  suspend fun finish(id: Long, reason: WaitEndReason, endedAt: Long)
  /**
   * 事务写入已触发事实与提醒快照：同一轮最多一条，重复写入不覆盖首次触发。
   * 与进度/结束共用同一任务身份校验，旧任务写不进来。
   */
  suspend fun recordAlert(id: Long, snapshot: AlertSnapshot): Boolean
  /** Room 提交标记写入前，独立保留同步通知提交的凭据。 */
  fun recordSubmissionReceipt(id: Long) = Unit
  suspend fun markAlertSubmitted(id: Long): Boolean
  suspend fun discardPendingAlert(id: Long) = Unit
  suspend fun finishBeforeAlertDelivery(id: Long, reason: WaitEndReason, endedAt: Long)
  /** 提醒确认：只确认属于这一轮的那条提醒，旧提醒动作不影响新任务。 */
  suspend fun confirmAlert(id: Long): Boolean
  /** 由未导出的通知接收器或应用内已验证的系统槽位提供提交证据。 */
  suspend fun confirmPostedAlert(id: Long): Boolean = confirmAlert(id)
  suspend fun alerts(): List<OutingAlert>
  suspend fun alertsWithPosted(posted: Set<Long>): List<OutingAlert> = alerts()
  suspend fun clear(id: Long)
}

/**
 * 提醒触发判定：只看本轮开始后新取得的、原选方向正确、带有效目标站 ETA 的线路。
 * 规则是纯函数，便于逐条覆盖边界；不猜同线第二班，也不把站数或空值换算成分钟。
 */
internal fun alertCandidates(
  lines: List<SavedLine>,
  arrivals: Map<String, LineArrival>,
  walkMinutes: Double,
  bufferMinutes: Double,
): List<AlertLine> {
  if (!walkMinutes.isFinite() || !bufferMinutes.isFinite()) return emptyList()
  // 在十进制秒上比较，不舍入步行时间或余量，也不因二进制相加丢失边界。
  val minute = BigDecimal.valueOf(60)
  val lower = BigDecimal.valueOf(walkMinutes).multiply(minute)
  val upper = lower.add(BigDecimal.valueOf(bufferMinutes).multiply(minute))
  return lines.asSequence()
    .filter { !it.obsolete }
    .mapNotNull { saved ->
      val arrival = arrivals[saved.identity] ?: return@mapNotNull null
      val eta = BigDecimal.valueOf(arrival.seconds)
      if (eta < lower || eta > upper) return@mapNotNull null
      AlertLine(saved.lineNo, saved.lineName, saved.upDown, saved.endStationName, arrival.minutes, arrival.seconds)
    }
    // 同一条线路方向只取最近一班：来源本次已按站台聚合，这里再按方向去重防止重复对象。
    .distinctBy { "${it.lineNo}|${it.upDown}" }
    .sortedWith(compareBy({ it.etaSeconds }, { it.lineName }))
    .toList()
}

internal fun formatMinutes(value: Double): String =
  if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** 分秒展示保留来源精度，避免触发时的 6 分 30 秒显示成 6 分钟。 */
internal fun formatAlertEta(seconds: Long): String =
  if (seconds % 60L == 0L) "${seconds / 60L} 分钟" else "${seconds / 60L} 分 ${seconds % 60L} 秒"
