package com.ilksese.busalarmclock.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SavedLine(
  val lineNo: String,
  val upDown: Int,
  val endStationName: String,
  val targetSequence: Int,
  val obsolete: Boolean = false,
  val lineName: String = lineNo,
) {
  val identity: String get() = "$lineNo|$upDown|$targetSequence"
}

/** 与最新清单比对用的方向身份；站序变化不属于线路消失。 */
val SavedLine.direction: String get() = "$lineNo|$upDown"

@Entity(tableName = "watch_items")
data class WatchItemEntity(
  @PrimaryKey val stopCode: String,
  val stationName: String,
  val endStationName: String,
  val lineNo: String,
  val upDown: Int,
  val targetSequence: Int,
  @ColumnInfo(defaultValue = "''") val selectedLinesJson: String = Json.encodeToString(listOf(SavedLine(lineNo, upDown, endStationName, targetSequence))),
  val walkMinutes: Double? = null,
  @ColumnInfo(defaultValue = "2.0") val bufferMinutes: Double = 2.0,
  /** 最近一次成功核对的来源获取时间；为空表示本机保存后尚未联网核对。 */
  val lastCheckedAt: Long? = null,
) {
  val selectedLines: List<SavedLine>
    get() = runCatching { Json.decodeFromString<List<SavedLine>>(selectedLinesJson) }
      .getOrElse { listOf(SavedLine(lineNo, upDown, endStationName, targetSequence)) }
}

@Dao
interface WatchItemDao {
  @Query("SELECT * FROM watch_items ORDER BY stationName, lineNo")
  fun observeAll(): Flow<List<WatchItemEntity>>

  @Query("SELECT * FROM watch_items WHERE stopCode = :stopCode LIMIT 1")
  suspend fun find(stopCode: String): WatchItemEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun save(item: WatchItemEntity)

  @Query("DELETE FROM watch_items WHERE stopCode = :stopCode")
  suspend fun delete(stopCode: String)
}

@Database(entities = [WatchItemEntity::class, WaitTaskEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
  abstract fun watchItemDao(): WatchItemDao
  abstract fun waitTaskDao(): WaitTaskDao
}
