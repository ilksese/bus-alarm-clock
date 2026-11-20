package com.ilksese.busalarmclock.data

import kotlinx.serialization.Serializable

@Serializable
data class SelectedLine(
  val lineNo: String,
  val upDown: Int,
  val endStationName: String,
  val targetSequence: Int,
  val lineName: String = lineNo,
) {
  val identity: String get() = "$lineNo|$upDown|$targetSequence"
}

/** 线路方向身份：同线路同方向即同一乘车选项，站序变化只更新显示，不算过时。 */
val SelectedLine.direction: String get() = "$lineNo|$upDown"

/** 用户确认保存的具体站台及一条或多条可替代线路方向。 */
data class WatchItemDraft(
  val stopCode: String,
  val stationName: String,
  val lineNo: String,
  val upDown: Int,
  val endStationName: String,
  val targetSequence: Int,
  val lines: List<SelectedLine> = listOf(SelectedLine(lineNo, upDown, endStationName, targetSequence)),
) {
  companion object {
    fun create(
      stopCode: String,
      stationName: String,
      lines: List<SelectedLine>,
    ): WatchItemDraft? = lines.takeIf { it.isNotEmpty() }?.let { selected ->
      val first = selected.first()
      WatchItemDraft(
        stopCode = stopCode,
        stationName = stationName,
        lineNo = first.lineNo,
        upDown = first.upDown,
        endStationName = first.endStationName,
        targetSequence = first.targetSequence,
        lines = selected,
      )
    }
  }
}
