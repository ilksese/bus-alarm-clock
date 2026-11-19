package com.ilksese.busalarmclock.data

/** 具体站台确认草稿；不同站台从不共用选线，也不把已有关注项换绑。 */
data class WatchItemImportSession(
  val station: StationChoice,
  val stopCode: String,
  val availableLines: List<SelectedLine> = emptyList(),
  val selected: Set<String> = emptySet(),
  val original: WatchItemEntity? = null,
  val fetchedAt: Long? = null,
  val nextAllowedAt: Long? = null,
  val restoreOriginalOnLoad: Boolean = true,
  /** 编辑已有关注项：站台固定，只允许改选线路。 */
  val editing: Boolean = false,
  /** 本机保存的线路记录；带过时标记的记录只允许删除，不参与选择。 */
  val records: List<SavedLine> = emptyList(),
) {
  val isUpdate: Boolean get() = original != null
  val obsoleteRecords: List<SavedLine> get() = records.filter { it.obsolete }
}
