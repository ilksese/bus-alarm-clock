package com.ilksese.busalarmclock.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json

/**
 * 建最少任务状态表的主体，供迁移复用。
 * 单独抽出是因为 #18 也会占用 v3；合并时只需新增 `MIGRATION_3_4` 调用本函数，
 * 不必复制建表语句，也不用改动这里的列定义。
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

/** 记录最近一次成功核对的获取时间；失败时保持原值，界面据此区分尚未联网核对。 */
val MIGRATION_2_3 = object : Migration(2, 3) {
  override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL("ALTER TABLE watch_items ADD COLUMN lastCheckedAt INTEGER")
  }
}
