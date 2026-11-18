package com.ilksese.busalarmclock.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 同一来源请求共享在途结果，成功与失败都计入间隔；缓存保留实际接收时间。 */
class StopRequestGate(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
  private val mutex = Mutex()
  private val entries = mutableMapOf<String, Entry>()

  suspend fun request(key: String, loader: suspend () -> HttpPayload): SourceSnapshot<String> {
    var owner = false
    val now = clock()
    val pending = mutex.withLock {
      val entry = entries.getOrPut(key) { Entry() }
      entry.pending?.let { return@withLock it }
      if (now < entry.nextAllowedAt) {
        entry.cached?.let { return it.copy(nextAllowedAt = entry.nextAllowedAt) }
        throw SourceFailure("请求过于频繁，请稍后重试", entry.nextAllowedAt)
      }
      owner = true
      entry.nextAllowedAt = now + 60_000
      CompletableDeferred<Result<SourceSnapshot<String>>>().also { entry.pending = it }
    }
    if (!owner) return pending.await().getOrThrow()

    val result = try {
      val response = loader()
      val receivedAt = clock()
      val nextAllowedAt = maxOf(now + 60_000, receivedAt + response.retryAfterMillis)
      Result.success(SourceSnapshot(response.body, receivedAt, nextAllowedAt))
    } catch (error: Exception) {
      Result.failure(error)
    }
    withContext(NonCancellable) {
      mutex.withLock {
        val entry = entries.getValue(key)
        val failure = result.exceptionOrNull()
        if (failure is SourceFailure) entry.nextAllowedAt = maxOf(entry.nextAllowedAt, failure.retryAt)
        result.getOrNull()?.let {
          entry.cached = it
          entry.nextAllowedAt = maxOf(entry.nextAllowedAt, it.nextAllowedAt)
        }
        // 新请求失败后不能把上一份成功缓存伪装成本轮成功；取消也须唤醒共享等待者。
        if (result.isFailure) entry.cached = null
        entry.pending = null
        pending.complete(result)
      }
    }
    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    return result.getOrThrow()
  }

  private class Entry {
    var nextAllowedAt: Long = Long.MIN_VALUE
    var cached: SourceSnapshot<String>? = null
    var pending: CompletableDeferred<Result<SourceSnapshot<String>>>? = null
  }
}

data class SourceSnapshot<T>(val value: T, val fetchedAt: Long, val nextAllowedAt: Long)
data class HttpPayload(val body: String, val retryAfterMillis: Long = 0)
class SourceFailure(message: String, val retryAt: Long = 0) : Exception(message)
