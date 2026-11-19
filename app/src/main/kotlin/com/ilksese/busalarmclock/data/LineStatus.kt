package com.ilksese.busalarmclock.data

/** 线路状态转换事件；只用于普通安静文字通知，不参与出门提醒的强输出。 */
data class LineStatusEvent(
  val stopCode: String,
  val stationName: String,
  val lineName: String,
  val upDown: Int,
  val endStationName: String,
  val restored: Boolean,
) {
  val title: String get() = if (restored) "线路已恢复" else "线路已过时"
  val text: String
    get() = "$stationName · ${lineName}开往$endStationName，方向 $upDown · " +
      if (restored) "重新出现在最新线路清单，可以再次选择" else "不在最新线路清单，只保留记录"
}

/** 通知只是尽力而为的旁路；未授权或发送失败都不能影响关注项数据。 */
fun interface LineStatusNotifier {
  fun notify(event: LineStatusEvent)
}
