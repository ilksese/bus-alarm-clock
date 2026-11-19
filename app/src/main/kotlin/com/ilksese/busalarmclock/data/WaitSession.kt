package com.ilksese.busalarmclock.data

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

internal const val REFRESH_INTERVAL = 60_000L
internal const val MAX_WAIT = 30 * 60_000L
internal const val MAX_SILENCE = 5 * 60_000L

/** 出门提醒输出总时长上限：从提醒开始计，不分别计算，也不因初始化或故障延长。 */
internal const val ALERT_OUTPUT_LIMIT = 60_000L

/**
 * 一次有限等车的调度、期限与资源边界。
 * 到限、取消以及迟到响应都经同一任务身份判定，旧响应无法复活已结束的任务。
 */
class WaitSession(
  private val source: BusSourceClient,
  private val platform: WaitPlatform,
  private val task: WaitTask,
  private val clock: () -> Long = System::currentTimeMillis,
  private val sleeper: suspend (Long) -> Unit = { delay(it) },
  private val submissionSleeper: suspend (Long) -> Unit = { delay(it) },
  private val elapsedClock: () -> Long = { System.nanoTime() / 1_000_000L },
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
  private val onState: (WaitState) -> Unit = {},
  /** 已触发事实与快照的落地入口；必须带任务身份，写入完成后才停止取数。 */
  private val recordAlert: suspend (AlertSnapshot) -> Boolean = { false },
  private val recordSubmissionReceipt: () -> Unit = {},
  private val markAlertSubmitted: suspend () -> Boolean = { true },
  private val finishBeforeAlertDelivery: suspend (WaitEndReason, Long) -> Unit = { _, _ -> },
  private val requestDeadlineSleeper: suspend (Long) -> Unit = { delay(it) },
) {
  private val identity = AtomicLong(task.id)
  private val handoff = Mutex()
  private var pendingSnapshot: AlertSnapshot? = null
  private enum class Submission(val confirmable: Boolean = false) {
    PENDING, POSTING(true), POSTED(true), SUBMITTED(true), WITHDRAWN, CONFIRMING, CLOSED,
  }
  private val submission = AtomicReference(Submission.PENDING)
  private var outputAvailabilityJob: Job? = null
  private var job: Job? = null
  /** 输出期计时任务；到限或确认时取消，旧计时不会改写后来的状态。 */
  private var outputJob: Job? = null
  private var submissionJob: Job? = null
  /** 本轮是否已经触发过出门提醒：一次等车最多一次，后续预测变化不重置。 */
  private var alert: AlertSnapshot? = task.alert
  private val confirmed: Boolean = task.alertConfirmed
  @Volatile private var state = WaitState(
    running = true,
    taskId = task.id,
    phase = WaitPhase.WAITING_FIRST,
    stopCode = task.stopCode,
    stationName = task.stationName,
    walkMinutes = task.walkMinutes,
    bufferMinutes = task.bufferMinutes,
    startedAt = task.startedAt,
    deadlineAt = task.deadlineAt,
    lastSuccessAt = task.lastSuccessAt,
  )
  private val elapsedAtCreation = elapsedClock()
  private val wallAtCreation = clock()
  private val elapsedStartedAt = elapsedAtCreation - task.startedAt.delayUntil(wallAtCreation).coerceAtMost(MAX_WAIT)
  private val elapsedMonitorDeadline = elapsedStartedAt.plusDelay(task.startedAt.delayUntil(task.deadlineAt))
  private var elapsedLastSuccessAt = elapsedStartedAt.plusDelay(task.startedAt.delayUntil(task.lastSuccessAt ?: task.startedAt))
  private var lines: List<SavedLine> =
    runCatching { Json.decodeFromString<List<SavedLine>>(task.linesJson) }.getOrDefault(emptyList())

  fun start() {
    if (!alive()) return
    publish(state)
    platform.acquireWakeLock(clock().delayUntil(task.deadlineAt).coerceAtMost(MAX_WAIT))
    val monitoring = scope.launch(start = CoroutineStart.LAZY) {
      try {
        loop()
      } catch (error: CancellationException) {
        finish(WaitEndReason.INTERRUPTED, announce = false)
        throw error
      }
    }
    job = monitoring
    monitoring.start()
  }

  /** 主动取消：立即停止取数、释放资源，且不另发异常提示。 */
  suspend fun cancel() = finish(WaitEndReason.CANCELLED, announce = false)

  /** 服务被终止时同样结束，但不能假装还活着。 */
  suspend fun terminate(reason: WaitEndReason = WaitEndReason.SERVICE_TERMINATED) = finish(reason, announce = true)

  fun current(): WaitState = state

  private suspend fun loop() {
    while (alive()) {
      deadlineOf(clock())?.let { return finishOnDeadline(it) }
      if (!platform.notificationsAllowed()) return finishWithoutNotifications()
      try {
        val snapshot = observeBeforeDeadline() ?: return
        // 到限优先于迟到响应：即便响应在期限内发出，也按结束处理，不再展示新预测。
        val receivedAt = clock()
        if (!alive()) return
        deadlineOf(receivedAt)?.let { return finishOnDeadline(it) }
        if (!platform.notificationsAllowed()) return finishWithoutNotifications()
        val candidates: List<AlertLine>
        // 在锁内合并结果，避免取消已结束会话后覆盖结束状态。
        handoff.withLock {
          if (!alive()) return
          val arrivals = snapshot.value.arrivals
          if (!snapshot.fromCache) {
            val directions = snapshot.value.lines.associateBy { it.direction }
            lines = lines.map { saved ->
              val fresh = directions[saved.direction]
              if (fresh == null) saved.copy(obsolete = true)
              else saved.copy(obsolete = false, targetSequence = fresh.targetSequence,
                lineName = fresh.lineName, endStationName = fresh.endStationName)
            }
            elapsedLastSuccessAt = elapsedClock()
          }
          // 复用的结果只用于展示；旧缓存不参与触发，也不延长无成功期限。
          candidates = if (snapshot.fromCache) emptyList() else alertCandidates(lines, arrivals, task.walkMinutes, task.bufferMinutes)
          val next = state.copy(
            phase = if (candidates.isNotEmpty()) WaitPhase.ALERTED else phaseOf(arrivals),
            lines = lines.map { saved ->
              val arrival = arrivals[saved.identity].takeUnless { saved.obsolete }
              WaitingLine(saved.lineName, saved.endStationName, arrival?.minutes, arrival?.stops, arrival?.arriving == true)
            },
            lastSuccessAt = maxOf(state.lastSuccessAt ?: task.startedAt, snapshot.fetchedAt),
            nextAttemptAt = snapshot.nextAllowedAt,
            message = null,
          )
          state = next
        }
        if (candidates.isNotEmpty() && trigger(candidates, snapshot)) return
        publish(state)
        sleepUntil(snapshot.nextAllowedAt)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        if (!alive()) return
        deadlineOf(clock())?.let { return finishOnDeadline(it) }
        val failure = error as? SourceFailure
        when (failure?.kind) {
          SourceFailureKind.AUTH -> return finishOnDeadline(WaitEndReason.AUTH_FAILED)
          SourceFailureKind.DENIED -> return finishOnDeadline(WaitEndReason.DENIED)
          SourceFailureKind.TERMINATED -> return finishOnDeadline(WaitEndReason.SERVICE_TERMINATED)
          else -> Unit
        }
        // 临时失败保持安静，按既有间隔重试，不连发。
        state = state.copy(phase = WaitPhase.RETRYING, message = failure?.message ?: "获取失败，请稍后重试")
        publish(state)
        sleepUntil(maxOf(clock().plusDelay(REFRESH_INTERVAL), failure?.retryAt ?: 0))
      }
    }
  }

  private suspend fun observeBeforeDeadline(): SourceSnapshot<StopLineSnapshot>? {
    val inFlight = AtomicBoolean(true)
    val watchdog = handoff.withLock {
      if (!alive()) return null
      scope.launch(Dispatchers.Default) {
        while (inFlight.get() && alive()) {
          val now = clock()
          val elapsed = elapsedClock()
          val reason = deadlineOf(now, elapsed)
          if (reason != null) {
            if (inFlight.compareAndSet(true, false)) finishOnDeadline(reason)
            break
          }
          requestDeadlineSleeper(monitoringBudget(now, elapsed))
        }
      }
    }
    // 网络阻塞或共享请求不会阻塞同级看门狗；后者无需等待取消后的 HTTP 调用返回。
    try {
      val result = runCatching { source.observeStop(task.stopCode) }
      return if (inFlight.compareAndSet(true, false)) {
        watchdog.cancel()
        result.getOrThrow()
      } else null
    } finally {
      // 看门狗取得结束权后，不能由迟到请求把它取消在收尾前。
      if (inFlight.getAndSet(false)) watchdog.cancel()
    }
  }

  /**
   * 触发顺序不可交换：先把已触发事实与快照事务保存，再停止取数、失效旧响应并开始输出。
   * 保存失败时本次不算触发，继续按原间隔等待，绝不在没有事实记录的情况下响铃。
   */
  private suspend fun trigger(candidates: List<AlertLine>, snapshot: SourceSnapshot<StopLineSnapshot>): Boolean {
    if (!platform.notificationsAllowed()) {
      finishWithoutNotifications()
      return true
    }
    val triggeredAt = clock()
    val outputDeadline = elapsedClock().plusDelay(ALERT_OUTPUT_LIMIT)
    fun remaining() = elapsedClock().delayUntil(outputDeadline).takeIf { alive() } ?: 0L
    suspend fun awaitDeadline(sleep: suspend (Long) -> Unit) {
      while (true) {
        val left = remaining()
        if (left <= 0L) return
        sleep(left)
      }
    }
    val main = candidates.first()
    val alertSnapshot = AlertSnapshot(
      taskId = task.id,
      stopCode = task.stopCode,
      stationName = task.stationName,
      main = main,
      others = candidates.drop(1),
      etaMinutes = main.etaMinutes,
      fetchedAt = snapshot.fetchedAt,
      triggeredAt = triggeredAt,
    )
    handoff.withLock {
      if (!alive()) return true
      pendingSnapshot = alertSnapshot
      submission.set(Submission.PENDING)
      // 系统调用可能阻塞。先安排本轮截止，不能等 Room 或通知系统返回才开始计时。
      val budget = remaining()
      if (budget > 0) platform.acquireWakeLock(budget)
      submissionJob = scope.launch(Dispatchers.Default) {
        awaitDeadline(submissionSleeper)
        finish(WaitEndReason.ALERT_DELIVERY_FAILED, announce = false, pendingOnly = true)
      }
    }
    val recorded = try {
      recordAlert(alertSnapshot)
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      false
    }
    if (!alive()) return true
    if (!recorded) {
      handoff.withLock {
        if (!alive()) return true
        pendingSnapshot = null
        submissionJob?.cancel()
        submissionJob = null
        val monitoringBudget = clock().delayUntil(task.deadlineAt)
        if (monitoringBudget > 0) platform.acquireWakeLock(monitoringBudget)
        state = state.copy(phase = WaitPhase.RETRYING, message = "提醒状态保存失败，暂未触发；将继续等待下一次预测。")
      }
      return false
    }
    val receiptSaved = withContext(Dispatchers.IO + NonCancellable) {
      // 不持会话锁，也不占主线程；即使 Binder 无法中止，截止仍能停服务。
      if (remaining() <= 0 || !platform.notificationsAllowed() || !submission.compareAndSet(Submission.PENDING, Submission.POSTING)) return@withContext null
      if (!runCatching { platform.submitAlert(alertSnapshot, ::remaining) }.getOrDefault(false)) {
        if (!submission.compareAndSet(Submission.POSTING, Submission.PENDING) &&
          submission.compareAndSet(Submission.CLOSED, Submission.WITHDRAWN)) {
          runCatching { finishBeforeAlertDelivery(WaitEndReason.ALERT_DELIVERY_FAILED, clock()) }
        }
        return@withContext null
      }
      // 截止可独立停服，但不能在 Binder 返回前删其可能引用的快照。
      if (remaining() <= 0 || !submission.compareAndSet(Submission.POSTING, Submission.POSTED)) {
        // 确认和撤回竞争同一状态；确认已接管时，迟到提交不得撤通知或删快照。
        val mayWithdraw = submission.compareAndSet(Submission.POSTING, Submission.WITHDRAWN) ||
          submission.compareAndSet(Submission.CLOSED, Submission.WITHDRAWN)
        if (!mayWithdraw) return@withContext null
        if (platform.withdrawAlert(task.id)) {
          runCatching { finishBeforeAlertDelivery(WaitEndReason.ALERT_DELIVERY_FAILED, clock()) }
        } else if (runCatching { recordSubmissionReceipt() }.isSuccess || runCatching { markAlertSubmitted() }.getOrDefault(false)) {
          submission.compareAndSet(Submission.WITHDRAWN, Submission.SUBMITTED)
        }
        return@withContext null
      }
      // 从系统接受提交起观察撤权，不等收据或 Room 标记；检测到关闭会永久封口。
      observeOutputAvailability()
      if (runCatching { recordSubmissionReceipt() }.isSuccess) {
        submission.compareAndSet(Submission.POSTED, Submission.SUBMITTED)
        return@withContext true
      }
      // 先撤同任务通知，再删 pending；撤回失败时仍尝试 Room 恢复凭据，绝不播放。
      if (!(submission.compareAndSet(Submission.POSTED, Submission.WITHDRAWN) ||
          submission.compareAndSet(Submission.CLOSED, Submission.WITHDRAWN))) return@withContext null
      if (platform.withdrawAlert(task.id)) {
        runCatching { finishBeforeAlertDelivery(WaitEndReason.ALERT_DELIVERY_FAILED, clock()) }
        return@withContext null
      }
      if (!runCatching { markAlertSubmitted() }.getOrDefault(false)) return@withContext null
      submission.compareAndSet(Submission.WITHDRAWN, Submission.SUBMITTED)
      false
    }
    if (!alive()) return true
    if (receiptSaved == null || remaining() <= 0) {
      val reason = withContext(Dispatchers.IO) {
        if (platform.notificationsAllowed()) WaitEndReason.ALERT_DELIVERY_FAILED else WaitEndReason.NOTIFICATIONS_UNAVAILABLE
      }
      finish(reason, announce = false)
      return true
    }
    handoff.withLock {
      if (!alive()) return true
      // 先开放查看与确认；标记补写不掌握输出或停机的身份。
      publish(state.copy(phase = WaitPhase.ALERTED, alerts = listOf(OutingAlert(alertSnapshot, submissionPending = receiptSaved)), nextAttemptAt = null))
    }
    val saved = try {
      !receiptSaved || withContext(NonCancellable) { markAlertSubmitted() }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      false
    }
    if (!saved) {
      // 通知已引用该快照，停止输出但保留查看与确认入口。
      finish(WaitEndReason.ALERTED, announce = false)
      return true
    }
    val accepted = handoff.withLock {
      if (!alive()) return true
      val budget = remaining()
      if (budget <= 0) return@withLock false
      acceptDelivery(alertSnapshot)
      platform.acquireWakeLock(budget)
      submissionJob?.cancel()
      submissionJob = null
      publish(state)
      outputJob = scope.launch(Dispatchers.Default) {
        awaitDeadline(sleeper)
        finishOutput()
      }
      true
    }
    if (!accepted) {
      finish(WaitEndReason.ALERT_DELIVERY_FAILED, announce = false)
      return true
    }
    if (!receiptSaved) {
      finishOutput()
      return true
    }
    // 通知渠道、音频和前台提升的 IPC 不占看门狗调度线程，也不持会话锁。
    val completion = withContext(Dispatchers.IO) {
      if (!platform.notificationsAllowed()) return@withContext WaitEndReason.NOTIFICATIONS_UNAVAILABLE
      val duration = try {
        if (platform.canStartAlertOutput(task.id, ::remaining) && remaining() > 0) {
          platform.startAlertOutput(task.id, ::remaining) { scope.launch(Dispatchers.Default) { finishOutput() } }
        } else 0L
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        0L
      }
      val until = minOf(duration, remaining())
      if (until > 0) delay(until)
      if (platform.notificationsAllowed()) WaitEndReason.ALERTED else WaitEndReason.NOTIFICATIONS_UNAVAILABLE
    }
    finishOutput(completion)
    return true
  }

  private suspend fun observeOutputAvailability() = handoff.withLock {
    if (!alive()) return@withLock
    outputAvailabilityJob = scope.launch(Dispatchers.IO) {
      while (alive()) {
        if (!platform.notificationsAllowed()) {
          finishWithoutNotifications()
          break
        }
        delay(100)
      }
    }
  }

  private fun acceptDelivery(snapshot: AlertSnapshot, submissionPending: Boolean = false) {
    pendingSnapshot = null
    alert = snapshot
    state = state.copy(phase = WaitPhase.ALERTED, alerts = listOf(OutingAlert(snapshot, submissionPending = submissionPending)), nextAttemptAt = null, message = null)
  }

  /** 到限或通知不可用时停止输出，未确认的触发事实仍保留。 */
  private suspend fun finishOutput(reason: WaitEndReason = WaitEndReason.ALERTED) = withContext(NonCancellable) {
    handoff.withLock {
      if (!identity.compareAndSet(task.id, Long.MIN_VALUE)) return@withLock
      endOutputCarrier()
      completeAlert(reason)
    }
  }

  /** 先结束输出和服务，再落库；重试确认不复播也不触碰下一轮。 */
  suspend fun confirm(taskId: Long, fromNotification: Boolean): Boolean = withContext(NonCancellable) {
    if (task.id != taskId) return@withContext false
    handoff.withLock {
      val pending = pendingSnapshot
      while (true) {
        val delivery = submission.get()
        if (alert?.taskId != taskId && (pending?.taskId != taskId || !delivery.confirmable ||
            (delivery == Submission.POSTING && !fromNotification))) return@withLock false
        if (submission.compareAndSet(delivery, Submission.CONFIRMING)) break
      }
      if (!identity.compareAndSet(task.id, Long.MIN_VALUE)) return@withLock false
      if (pending != null) acceptDelivery(pending, submissionPending = true)
      endOutputCarrier()
      completeAlert(WaitEndReason.ALERTED)
      true
    }
  }

  private fun completeAlert(reason: WaitEndReason) {
    state = state.copy(
      running = false,
      endedReason = reason,
      endedAt = clock(),
      nextAttemptAt = null,
      message = reason.message,
      alerts = state.alerts.map { it.copy(confirmed = confirmed, outputEnded = true) },
    )
    platform.stopForegroundService(task.id)
    onState(state)
    job?.cancel()
    job = null
  }

  private fun endOutputCarrier() {
    submissionJob?.cancel()
    submissionJob = null
    outputJob?.cancel()
    outputJob = null
    outputAvailabilityJob?.cancel()
    outputAvailabilityJob = null
    platform.stopAlertOutput(task.id)
    platform.releaseWakeLock()
  }

  /** 期限判断：未触发上限优先于连续无成功；返回 null 表示仍可继续。 */
  private fun deadlineOf(now: Long, elapsed: Long = elapsedClock()): WaitEndReason? = when {
    now >= task.deadlineAt || elapsed >= elapsedMonitorDeadline -> WaitEndReason.MAX_DURATION
    now >= (state.lastSuccessAt ?: task.startedAt).plusDelay(MAX_SILENCE) || elapsed >= elapsedLastSuccessAt.plusDelay(MAX_SILENCE) -> WaitEndReason.NO_SUCCESS
    else -> null
  }

  private fun monitoringBudget(now: Long, elapsed: Long): Long = minOf(
    elapsed.delayUntil(elapsedMonitorDeadline), elapsed.delayUntil(elapsedLastSuccessAt.plusDelay(MAX_SILENCE)),
    now.delayUntil(task.deadlineAt), now.delayUntil((state.lastSuccessAt ?: task.startedAt).plusDelay(MAX_SILENCE)),
  )

  /**
   * 恢复时不补轮次：只按最早可再次请求的时刻等待一次。
   * 期限必须在等待中途也能判定，所以睡眠按上限切分，醒来后回到循环重新检查。
   */
  private suspend fun sleepUntil(at: Long) {
    val elapsed = elapsedClock()
    val wall = clock()
    val retryAt = elapsed.plusDelay(wall.delayUntil(at).coerceAtMost(MAX_WAIT))
    while (alive()) {
      val now = clock()
      val currentElapsed = elapsedClock()
      deadlineOf(now, currentElapsed)?.let { return finishOnDeadline(it) }
      if (!platform.notificationsAllowed()) return finishWithoutNotifications()
      if (now >= at || currentElapsed >= retryAt) return
      val remaining = minOf(currentElapsed.delayUntil(retryAt), now.delayUntil(at), currentElapsed.delayUntil(elapsedMonitorDeadline),
        currentElapsed.delayUntil(elapsedLastSuccessAt.plusDelay(MAX_SILENCE)), now.delayUntil(task.deadlineAt),
        now.delayUntil((state.lastSuccessAt ?: task.startedAt).plusDelay(MAX_SILENCE)))
      // 不因检查权限而提前请求来源；长 Retry-After 也至少每分钟检查一次。
      sleeper(minOf(remaining, REFRESH_INTERVAL))
    }
  }
  private fun phaseOf(arrivals: Map<String, LineArrival>): WaitPhase {
    val known = lines.filterNot { it.obsolete }.mapNotNull { arrivals[it.identity] }
    if (known.isEmpty()) return WaitPhase.NO_PREDICTION
    // 余量 B 不改变"来不及"的判定；E < W 才提示可能来不及。
    return if (known.minOf { it.seconds } / 60.0 < task.walkMinutes) WaitPhase.MIGHT_BE_LATE else WaitPhase.FRESH
  }

  private fun alive() = identity.get() == task.id

  private fun publish(next: WaitState) {
    if (!alive()) return
    state = next
    if (next.phase != WaitPhase.ALERTED) platform.present(next)
    onState(next)
  }

  private suspend fun finishWithoutNotifications() = finish(WaitEndReason.NOTIFICATIONS_UNAVAILABLE, announce = false)

  private suspend fun finishOnDeadline(reason: WaitEndReason) = finish(reason, announce = reason != WaitEndReason.CANCELLED)

  private suspend fun finish(reason: WaitEndReason, announce: Boolean, pendingOnly: Boolean = false) = withContext(NonCancellable) {
    handoff.withLock {
      if (pendingOnly && pendingSnapshot == null) return@withLock
      if (!identity.compareAndSet(task.id, Long.MIN_VALUE)) return@withLock
      val delivery = submission.getAndSet(Submission.CLOSED)
      val completedSubmission = delivery == Submission.SUBMITTED
      val pending = pendingSnapshot
      val endReason = if (completedSubmission && reason == WaitEndReason.ALERT_DELIVERY_FAILED) WaitEndReason.ALERTED else reason
      if (completedSubmission && pending != null) acceptDelivery(pending, submissionPending = true)
      val endedAt = clock()
      var cleanupFailed = false
      endOutputCarrier()
      job?.cancel()
      job = null
      platform.stopForegroundService(task.id)
      try {
        if (pending != null && delivery == Submission.PENDING) finishBeforeAlertDelivery(endReason, endedAt)
      } catch (_: Exception) {
        cleanupFailed = true
      } finally {
        pendingSnapshot = null
        state = state.copy(
          running = false,
          endedReason = endReason,
          endedAt = endedAt,
          nextAttemptAt = null,
          message = if (cleanupFailed) "${endReason.message} 本机结束状态未能保存。"
            else if (alert != null && !confirmed && endReason == WaitEndReason.CANCELLED) WaitEndReason.ALERTED.message
            else endReason.message,
          alerts = state.alerts.map { it.copy(outputEnded = true) },
        )
        onState(state)
      }
      if (announce && alert == null && platform.notificationsAllowed()) platform.announceEnd(reason)
    }
  }
}
