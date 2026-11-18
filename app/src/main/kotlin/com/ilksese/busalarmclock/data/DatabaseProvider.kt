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
      .addMigrations(MIGRATION_1_2)
      .build()
}

/** UI 与重开行为测试共同使用的真实本机持久化实现。 */
class RoomWatchItemStore(private val database: AppDatabase) : WatchItemStore {
  override suspend fun find(stopCode: String) = database.watchItemDao().find(stopCode)
  override suspend fun save(item: WatchItemEntity) = database.watchItemDao().save(item)
  override suspend fun all() = database.watchItemDao().observeAll().first()
}
