package com.ilksese.busalarmclock.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * 创建单进程使用的本机数据库；扫码照片和公交响应不进入持久化模型。
 */
object DatabaseProvider {
  fun create(context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "bus-alarm-clock.db")
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
      .build()
}

/** UI 与重开行为测试共同使用的真实本机持久化实现。 */
class RoomWatchItemStore(private val database: AppDatabase) : WatchItemStore {
  override suspend fun find(stopCode: String) = database.watchItemDao().find(stopCode)
  override suspend fun save(item: WatchItemEntity) = database.watchItemDao().save(item)
  override suspend fun delete(stopCode: String) = database.watchItemDao().delete(stopCode)
  override suspend fun all() = database.watchItemDao().observeAll().first()
}

/** 提醒快照仍在 Room；独立提交凭据用于 Room 提交标记写失败后的恢复。 */
class RoomWaitTaskStore(
  private val database: AppDatabase,
  private val receipts: SharedPreferences? = null,
) : WaitTaskStore {
  private val dao get() = database.waitTaskDao()
  private fun hasReceipt(id: Long) = receipts?.getBoolean(id.toString(), false) == true

  override suspend fun load(): WaitTask? = dao.load()?.let { row ->
    if (row.alertJson != null && !hasReceipt(row.id) && !dao.wasAlertSubmitted(row.id)) {
      row.copy(alertJson = null, alertConfirmed = false).toTask()
    } else row.toTask()
  }

  override suspend fun save(task: WaitTask) = dao.write(task.toEntity())

  override suspend fun progress(id: Long, phase: WaitPhase, lastSuccessAt: Long?) =
    dao.progress(id, phase.name, lastSuccessAt)

  override suspend fun finish(id: Long, reason: WaitEndReason, endedAt: Long) =
    dao.finish(id, reason.name, endedAt)

  override suspend fun recordAlert(id: Long, snapshot: AlertSnapshot) =
    dao.recordAlert(id, Json.encodeToString(snapshot))

  override fun recordSubmissionReceipt(id: Long) {
    // Room 与通知系统无法共用事务；提交收据先于 Room 标记保存，标记成功后才移除。
    val journal = receipts ?: error("未配置提醒提交收据存储")
    val key = id.toString()
    try {
      check(journal.edit().putBoolean(key, true).commit()) { "提醒提交收据未能写入本机" }
    } catch (error: Exception) {
      // commit=false 也可能已改变内存副本，不能让撤回后的 pending 被误当成已提交。
      runCatching { journal.edit().remove(key).apply() }
      throw error
    }
  }

  override suspend fun markAlertSubmitted(id: Long): Boolean {
    val saved = dao.markAlertSubmitted(id) == 1 || dao.wasAlertSubmitted(id)
    if (saved) receipts?.edit()?.remove(id.toString())?.apply()
    return saved
  }

  override suspend fun confirmAlert(id: Long) = dao.confirmAlert(id, hasReceipt(id))

  override suspend fun confirmPostedAlert(id: Long) = dao.confirmAlert(id, true)

  override suspend fun finishBeforeAlertDelivery(id: Long, reason: WaitEndReason, endedAt: Long) =
    if (hasReceipt(id)) dao.finish(id, reason.name, endedAt)
    else dao.finishBeforeAlertDelivery(id, reason.name, endedAt)

  override suspend fun alerts(): List<OutingAlert> = alertsWithPosted(emptySet())

  override suspend fun alertsWithPosted(posted: Set<Long>): List<OutingAlert> {
    val pendingReceipts = receipts?.all.orEmpty()
    return dao.alerts().mapNotNull { row ->
      if (!row.submitted && !row.confirmed && row.taskId !in posted && pendingReceipts[row.taskId.toString()] != true && !hasReceipt(row.taskId)) return@mapNotNull null
      runCatching { Json.decodeFromString<AlertSnapshot>(row.snapshotJson) }.getOrNull()
        ?.takeIf { it.taskId == row.taskId }
        ?.let { OutingAlert(it, confirmed = row.confirmed, outputEnded = true, submissionPending = !row.submitted) }
    }
  }

  override suspend fun clear(id: Long) = dao.clear(id)

  private fun WaitTaskEntity.toTask() = WaitTask(
    id = id,
    stopCode = stopCode,
    stationName = stationName,
    startedAt = startedAt,
    deadlineAt = deadlineAt,
    lastSuccessAt = lastSuccessAt,
    walkMinutes = walkMinutes,
    bufferMinutes = bufferMinutes,
    linesJson = linesJson,
    phase = phase?.let { runCatching { WaitPhase.valueOf(it) }.getOrNull() },
    endedReason = endedReason?.let { runCatching { WaitEndReason.valueOf(it) }.getOrNull() },
    endedAt = endedAt,
    alert = alertJson?.let { runCatching { Json.decodeFromString<AlertSnapshot>(it) }.getOrNull() },
    alertConfirmed = alertConfirmed,
  )

  private fun WaitTask.toEntity() = WaitTaskEntity(
    id = id,
    stopCode = stopCode,
    stationName = stationName,
    linesJson = linesJson,
    walkMinutes = walkMinutes,
    bufferMinutes = bufferMinutes,
    startedAt = startedAt,
    deadlineAt = deadlineAt,
    lastSuccessAt = lastSuccessAt,
    phase = phase?.name,
    endedReason = endedReason?.name,
    endedAt = endedAt,
    alertJson = alert?.let { Json.encodeToString(it) },
    alertConfirmed = alertConfirmed,
  )
}
