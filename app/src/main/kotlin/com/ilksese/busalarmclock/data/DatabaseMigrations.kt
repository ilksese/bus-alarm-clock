package com.ilksese.busalarmclock.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json

/**
 * 建最少任务状态表的主体，供迁移复用。
 * 这里固定是 v4 时的表结构（不含 #20 的提醒列）：v3→v4 与 v4→v5 串起来时，
 * 提醒列只会在 v4→v5 里 ALTER 一次，重复加列会让升级直接失败。
 */
internal fun createWaitTasksTable(database: SupportSQLiteDatabase) {
  database.execSQL(
    """
    CREATE TABLE IF NOT EXISTS `wait_tasks` (
      `slot` INTEGER NOT NULL,
      `id` INTEGER NOT NULL,
      `stopCode` TEXT NOT NULL,
      `stationName` TEXT NOT NULL,
      `linesJson` TEXT NOT NULL,
      `walkMinutes` REAL NOT NULL,
      `bufferMinutes` REAL NOT NULL,
      `startedAt` INTEGER NOT NULL,
      `deadlineAt` INTEGER NOT NULL,
      `lastSuccessAt` INTEGER,
      `phase` TEXT DEFAULT NULL,
      `endedReason` TEXT DEFAULT NULL,
      `endedAt` INTEGER DEFAULT 0,
      PRIMARY KEY(`slot`)
    )
    """.trimIndent(),
  )
}

/**
 * 新增最少任务状态表；只建表，不动已有的关注项数据。
 * v3 已被 #18 的 `lastCheckedAt` 占用，所以这里落在 v3→v4。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
  override fun migrate(database: SupportSQLiteDatabase) = createWaitTasksTable(database)
}

/**
 * #20 在等车记录上补两列：已触发事实与提醒快照、是否已确认。
 * 非破坏性：旧记录保留，新列对旧数据为空/未确认。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
  override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL("ALTER TABLE wait_tasks ADD COLUMN alertJson TEXT DEFAULT NULL")
    database.execSQL("ALTER TABLE wait_tasks ADD COLUMN alertConfirmed INTEGER NOT NULL DEFAULT 0")
  }
}

/** 保留 v5 当前任务上的提醒，并让后续各轮的快照按任务身份独立保存。 */
val MIGRATION_5_6 = object : Migration(5, 6) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE outing_alerts (taskId INTEGER NOT NULL PRIMARY KEY, snapshotJson TEXT NOT NULL, confirmed INTEGER NOT NULL)")
    db.execSQL("INSERT INTO outing_alerts SELECT id, alertJson, alertConfirmed FROM wait_tasks WHERE alertJson IS NOT NULL")
  }
}

/** 既有提醒仍可查看；新快照只有记录提交后才进入提醒列表。 */
val MIGRATION_6_7 = object : Migration(6, 7) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE outing_alerts ADD COLUMN submitted INTEGER NOT NULL DEFAULT 1")
  }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
  override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL("ALTER TABLE watch_items ADD COLUMN selectedLinesJson TEXT NOT NULL DEFAULT ''")
    database.execSQL("ALTER TABLE watch_items ADD COLUMN walkMinutes REAL")
    database.execSQL("ALTER TABLE watch_items ADD COLUMN bufferMinutes REAL NOT NULL DEFAULT 2.0")
    database.query("SELECT stopCode, lineNo, upDown, endStationName, targetSequence FROM watch_items").use { cursor ->
      while (cursor.moveToNext()) {
        val lines = listOf(SavedLine(cursor.getString(1), cursor.getInt(2), cursor.getString(3), cursor.getInt(4)))
        database.execSQL("UPDATE watch_items SET selectedLinesJson = ? WHERE stopCode = ?", arrayOf(Json.encodeToString(lines), cursor.getString(0)))
      }
    }
  }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS reminder_settings (slot INTEGER NOT NULL PRIMARY KEY, voiceEnabled INTEGER NOT NULL)")
  }
}

/** 记录最近一次成功核对的获取时间；失败时保持原值，界面据此区分尚未联网核对。 */
val MIGRATION_2_3 = object : Migration(2, 3) {
  override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL("ALTER TABLE watch_items ADD COLUMN lastCheckedAt INTEGER")
  }
}
