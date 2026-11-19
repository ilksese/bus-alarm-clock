package com.ilksese.busalarmclock.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * 一次等车的最少持久状态：只有进程重启后解释上次发生了什么所需字段，
 * 不保存公交响应、不保存历史轮次，也不用于自动恢复或补发提醒。
 */
@Entity(tableName = "wait_tasks")
data class WaitTaskEntity(
  /** 同一时刻只允许一轮等车，因此固定占一行。 */
  @PrimaryKey val slot: Int = SINGLE_SLOT,
  val id: Long,
  val stopCode: String,
  val stationName: String,
  val linesJson: String,
  val walkMinutes: Double,
  val bufferMinutes: Double,
  val startedAt: Long,
  val deadlineAt: Long,
  val lastSuccessAt: Long? = null,
  @ColumnInfo(defaultValue = "NULL") val phase: String? = null,
  @ColumnInfo(defaultValue = "NULL") val endedReason: String? = null,
  @ColumnInfo(defaultValue = "0") val endedAt: Long? = null,
) {
  companion object { const val SINGLE_SLOT = 1 }
}

@Dao
interface WaitTaskDao {
  @Query("SELECT * FROM wait_tasks WHERE slot = 1")
  suspend fun load(): WaitTaskEntity?

  @Upsert
  suspend fun write(task: WaitTaskEntity)

  @Query("DELETE FROM wait_tasks")
  suspend fun clear()

  /**
   * 读改写放在同一事务里：阶段与最后成功时间只可能来自当前任务，
   * 已被替换或已结束的记录不会被迟到的进度写回覆盖。
   */
  @Transaction
  suspend fun progress(id: Long, phase: String, lastSuccessAt: Long?) {
    val current = load() ?: return
    if (current.id != id || current.endedReason != null) return
    write(current.copy(phase = phase, lastSuccessAt = lastSuccessAt ?: current.lastSuccessAt))
  }

  @Transaction
  suspend fun finish(id: Long, reason: String, endedAt: Long) {
    val current = load() ?: return
    if (current.id != id) return
    write(current.copy(endedReason = reason, endedAt = endedAt))
  }
}
