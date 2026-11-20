package com.ilksese.busalarmclock.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 同一来源请求共享在途结果，成功与失败都计入间隔；缓存保留实际接收时间。 */
class StopRequestGate(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
  private val mutex = Mutex()
  // 请求由 gate 持有，Activity 取消 await 不会取消其他观察者仍在等待的 HTTP。
  private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  // 只在锁外读取下次允许时间的调度方需要并发安全；写入仍在 mutex 内完成。
  private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

  /** 该请求最早可再次发起的时间；从未请求返回 Long.MIN_VALUE。 */
  fun nextAllowedAt(key: String): Long = entries[key]?.nextAllowedAt ?: Long.MIN_VALUE

  suspend fun request(key: String, loader: suspend () -> HttpPayload): SourceSnapshot<String> {
    val pending = mutex.withLock {
      val entry = entries.getOrPut(key) { Entry() }
      entry.pending?.let { return@withLock it }
      val now = clock()
      if (now < entry.nextAllowedAt) {
        entry.cached?.let { return it.copy(nextAllowedAt = entry.nextAllowedAt, fromCache = true) }
        throw SourceFailure("请求过于频繁，请稍后重试", entry.nextAllowedAt)
      }
      entry.nextAllowedAt = now.plusDelay(REFRESH_INTERVAL)
      val response = CompletableDeferred<Result<SourceSnapshot<String>>>()
      entry.pending = response
      requests.launch {
        val result = runCatching {
          val loaded = loader()
          val receivedAt = clock()
          val nextAllowedAt = maxOf(now.plusDelay(REFRESH_INTERVAL), receivedAt.plusDelay(loaded.retryAfterMillis))
          SourceSnapshot(loaded.body, receivedAt, nextAllowedAt)
        }
        mutex.withLock {
          val failure = result.exceptionOrNull()
          if (failure is SourceFailure) entry.nextAllowedAt = maxOf(entry.nextAllowedAt, failure.retryAt)
          entry.cached = result.getOrNull()
          entry.cached?.let { entry.nextAllowedAt = maxOf(entry.nextAllowedAt, it.nextAllowedAt) }
          entry.pending = null
          response.complete(result)
        }
      }
      response
    }
    return pending.await().getOrThrow()
  }

  private class Entry {
    var nextAllowedAt: Long = Long.MIN_VALUE
    var cached: SourceSnapshot<String>? = null
    var pending: CompletableDeferred<Result<SourceSnapshot<String>>>? = null
  }
}

data class SourceSnapshot<T>(
  val value: T,
  val fetchedAt: Long,
  val nextAllowedAt: Long,
  /** true 表示这是复用本轮之前的结果；提醒判定只接受本轮新取得的预测。 */
  val fromCache: Boolean = false,
)
data class HttpPayload(val body: String, val retryAfterMillis: Long = 0)

/** 正的截止间隔溢出时保留最晚可表示时刻，而不是绕回过去。 */
internal fun Long.plusDelay(delay: Long): Long {
  val positive = delay.coerceAtLeast(0)
  return if (this > Long.MAX_VALUE - positive) Long.MAX_VALUE else this + positive
}

internal fun Long.delayUntil(deadline: Long): Long = when {
  deadline <= this -> 0L
  this < 0L && deadline > Long.MAX_VALUE + this -> Long.MAX_VALUE
  else -> deadline - this
}

/** 失败类别决定等车是「按间隔重试」还是直接结束本次监测。 */
enum class SourceFailureKind { TRANSIENT, AUTH, DENIED, TERMINATED }

class SourceFailure(
  message: String,
  val retryAt: Long = 0,
  val kind: SourceFailureKind = SourceFailureKind.TRANSIENT,
) : Exception(message)
