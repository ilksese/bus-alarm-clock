package com.ilksese.busalarmclock.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json

/** 保留旧单线路记录和站台身份，不使用破坏性迁移或清库恢复。 */
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
