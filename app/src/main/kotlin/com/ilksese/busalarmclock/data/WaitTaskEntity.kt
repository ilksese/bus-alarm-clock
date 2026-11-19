package com.ilksese.busalarmclock.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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
  /** 已触发事实与提醒快照（JSON）；同一轮最多一条，写入后不再被新预测覆盖。 */
  @ColumnInfo(defaultValue = "NULL") val alertJson: String? = null,
  @ColumnInfo(defaultValue = "0") val alertConfirmed: Boolean = false,
) {
  companion object { const val SINGLE_SLOT = 1 }
}

/** 快照独立于当前任务槽位；换一轮等车不会覆盖旧通知所指向的记录。 */
@Entity(tableName = "outing_alerts")
data class OutingAlertEntity(
  @PrimaryKey val taskId: Long,
  val snapshotJson: String,
  val confirmed: Boolean,
  @ColumnInfo(defaultValue = "1") val submitted: Boolean,
)

@Dao
interface WaitTaskDao {
  @Query("SELECT * FROM wait_tasks WHERE slot = 1")
  suspend fun load(): WaitTaskEntity?

  @Upsert
  suspend fun write(task: WaitTaskEntity)

  @Query("DELETE FROM wait_tasks WHERE id = :id")
  suspend fun clear(id: Long)

  @Query("SELECT * FROM outing_alerts ORDER BY taskId DESC")
  suspend fun alerts(): List<OutingAlertEntity>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAlert(alert: OutingAlertEntity): Long

  @Query("UPDATE outing_alerts SET confirmed = 1 WHERE taskId = :id AND (submitted = 1 OR :hasReceipt = 1)")
  suspend fun markAlertConfirmed(id: Long, hasReceipt: Boolean): Int

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

  /**
   * 已触发事实与提醒快照在同一事务内写入。
   * 已有快照时不覆盖：一次等车最多一条提醒，后续预测变化不重置次数。
   */
  @Transaction
  suspend fun recordAlert(id: Long, alertJson: String): Boolean {
    val current = load() ?: return false
    if (current.id != id || current.endedReason != null || current.alertJson != null) return false
    if (insertAlert(OutingAlertEntity(id, alertJson, confirmed = false, submitted = false)) == -1L) return false
    write(current.copy(alertJson = alertJson))
    return true
  }

  @Query("UPDATE outing_alerts SET submitted = 1 WHERE taskId = :id AND submitted = 0")
  suspend fun markAlertSubmitted(id: Long): Int

  @Query("SELECT EXISTS(SELECT 1 FROM outing_alerts WHERE taskId = :id AND submitted = 1)")
  suspend fun wasAlertSubmitted(id: Long): Boolean

  @Query("DELETE FROM outing_alerts WHERE taskId = :id AND submitted = 0 AND confirmed = 0")
  suspend fun deletePendingAlert(id: Long): Int

  /** 先结束的轮次不能留下未交付的快照，晚到的 recordAlert 也会拒绝写入。 */
  @Transaction
  suspend fun finishBeforeAlertDelivery(id: Long, reason: String, endedAt: Long) {
    deletePendingAlert(id)
    val current = load() ?: return
    if (current.id != id) return
    write(current.copy(alertJson = current.alertJson.takeIf { wasAlertSubmitted(id) }, endedReason = reason, endedAt = endedAt))
  }

  /** 确认只作用于同一轮；旧提醒的确认动作不能重置新任务。 */
  @Transaction
  suspend fun confirmAlert(id: Long, hasReceipt: Boolean): Boolean {
    if (markAlertConfirmed(id, hasReceipt) == 0) return false
    val current = load()
    if (current?.id == id) write(current.copy(alertConfirmed = true))
    return true
  }

  @Transaction
  suspend fun finish(id: Long, reason: String, endedAt: Long) {
    val current = load() ?: return
    if (current.id != id) return
    write(current.copy(endedReason = reason, endedAt = endedAt))
  }
}
