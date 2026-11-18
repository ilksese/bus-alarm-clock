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
) {
  val isUpdate: Boolean get() = original != null
}
