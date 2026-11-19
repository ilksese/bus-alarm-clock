package com.ilksese.busalarmclock.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.first

/**
 * 创建单进程使用的本机数据库；扫码照片和公交响应不进入持久化模型。
 */
object DatabaseProvider {
  fun create(context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "bus-alarm-clock.db")
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
      .build()
}

/** UI 与重开行为测试共同使用的真实本机持久化实现。 */
class RoomWatchItemStore(private val database: AppDatabase) : WatchItemStore {
  override suspend fun find(stopCode: String) = database.watchItemDao().find(stopCode)
  override suspend fun save(item: WatchItemEntity) = database.watchItemDao().save(item)
  override suspend fun delete(stopCode: String) = database.watchItemDao().delete(stopCode)
  override suspend fun all() = database.watchItemDao().observeAll().first()
}

/** 最少任务状态落在同一数据库的另一张表，不另建设置存储。 */
class RoomWaitTaskStore(private val database: AppDatabase) : WaitTaskStore {
  private val dao get() = database.waitTaskDao()

  override suspend fun load(): WaitTask? = dao.load()?.toTask()

  override suspend fun save(task: WaitTask) = dao.write(task.toEntity())

  override suspend fun progress(id: Long, phase: WaitPhase, lastSuccessAt: Long?) =
    dao.progress(id, phase.name, lastSuccessAt)

  override suspend fun finish(id: Long, reason: WaitEndReason, endedAt: Long) =
    dao.finish(id, reason.name, endedAt)

  override suspend fun clear() = dao.clear()

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
  )
}
