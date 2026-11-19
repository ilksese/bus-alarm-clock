package com.ilksese.busalarmclock.data

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal const val REFRESH_INTERVAL = 60_000L
internal const val MAX_WAIT = 30 * 60_000L
internal const val MAX_SILENCE = 5 * 60_000L

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
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
  private val onState: (WaitState) -> Unit = {},
) {
  private val identity = AtomicLong(task.id)
  private var job: Job? = null
  private var state = WaitState(
    running = true,
    phase = WaitPhase.WAITING_FIRST,
    stopCode = task.stopCode,
    stationName = task.stationName,
    walkMinutes = task.walkMinutes,
    bufferMinutes = task.bufferMinutes,
    startedAt = task.startedAt,
    deadlineAt = task.deadlineAt,
    lastSuccessAt = task.lastSuccessAt,
  )
  private val lines: List<SavedLine> =
    runCatching { Json.decodeFromString<List<SavedLine>>(task.linesJson) }.getOrDefault(emptyList())

  fun start() {
    publish(state)
    platform.acquireWakeLock((task.deadlineAt - clock()).coerceIn(0, MAX_WAIT))
    job = scope.launch { loop() }
  }

  /** 主动取消：立即停止取数、释放资源，且不另发异常提示。 */
  suspend fun cancel() = finish(WaitEndReason.CANCELLED, announce = false)

  /** 服务被终止时同样结束，但不能假装还活着。 */
  suspend fun terminate() = finish(WaitEndReason.SERVICE_TERMINATED, announce = true)

  fun current(): WaitState = state

  private suspend fun loop() {
    while (true) {
      deadlineOf(clock())?.let { return finishOnDeadline(it) }
      try {
        val snapshot = source.observeStop(task.stopCode)
        // 到限优先于迟到响应：即便响应在期限内发出，也按结束处理，不再展示新预测。
        val receivedAt = clock()
        if (!alive()) return
        deadlineOf(receivedAt)?.let { return finishOnDeadline(it) }
        val arrivals = snapshot.value.arrivals
        state = state.copy(
          phase = phaseOf(arrivals),
          lines = lines.map { saved ->
            val arrival = arrivals[saved.identity]
            WaitingLine(saved.lineName, saved.endStationName, arrival?.minutes, arrival?.stops, arrival?.arriving == true)
          },
          // 缓存带有原始获取时间，因此复用旧结果不会把期限往后推。
          lastSuccessAt = maxOf(state.lastSuccessAt ?: task.startedAt, snapshot.fetchedAt),
          nextAttemptAt = snapshot.nextAllowedAt,
          message = null,
        )
        publish(state)
        sleepUntil(snapshot.nextAllowedAt)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        if (!alive()) return
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
        sleepUntil(maxOf(clock() + REFRESH_INTERVAL, failure?.retryAt ?: 0))
      }
    }
  }

  /** 期限判断：未触发上限优先于连续无成功；返回 null 表示仍可继续。 */
  private fun deadlineOf(now: Long): WaitEndReason? = when {
    now >= task.deadlineAt -> WaitEndReason.MAX_DURATION
    now >= (state.lastSuccessAt ?: task.startedAt) + MAX_SILENCE -> WaitEndReason.NO_SUCCESS
    else -> null
  }

  /**
   * 恢复时不补轮次：只按最早可再次请求的时刻等待一次。
   * 期限必须在等待中途也能判定，所以睡眠按上限切分，醒来后回到循环重新检查。
   */
  private suspend fun sleepUntil(at: Long) {
    sleeper((at - clock()).coerceAtLeast(0))
  }
  private fun phaseOf(arrivals: Map<String, LineArrival>): WaitPhase {
    val known = lines.mapNotNull { arrivals[it.identity] }
    if (known.isEmpty()) return WaitPhase.NO_PREDICTION
    // 余量 B 不改变"来不及"的判定；E < W 才提示可能来不及。
    return if (known.minOf { it.minutes.toLong() } < task.walkMinutes) WaitPhase.MIGHT_BE_LATE else WaitPhase.FRESH
  }

  private fun alive() = identity.get() == task.id

  private fun publish(next: WaitState) {
    if (!alive()) return
    state = next
    platform.present(next)
    onState(next)
  }

  private suspend fun finishOnDeadline(reason: WaitEndReason) = finish(reason, announce = reason != WaitEndReason.CANCELLED)

  private suspend fun finish(reason: WaitEndReason, announce: Boolean) = withContext(NonCancellable) {
    if (!identity.compareAndSet(task.id, Long.MIN_VALUE)) return@withContext
    state = state.copy(running = false, endedReason = reason, endedAt = clock(), nextAttemptAt = null, message = reason.message)
    platform.releaseWakeLock()
    platform.present(state)
    if (announce && platform.notificationsAllowed()) platform.announceEnd(reason)
    platform.stopForegroundService()
    onState(state)
    // 收尾完成后再取消调度协程，避免把自己这一轮的状态写回到旧值。
    job?.cancel()
    job = null
  }
}
