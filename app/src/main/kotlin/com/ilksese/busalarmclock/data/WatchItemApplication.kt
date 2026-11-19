package com.ilksese.busalarmclock.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** UI 与测试共用的应用行为入口；草稿、迟到响应、请求与保存结果均由此处决定。 */
class WatchItemApplication(
  private val source: BusSourceClient,
  private val store: WatchItemStore,
  private val notifier: LineStatusNotifier = LineStatusNotifier { },
) {
  private val mutableState = MutableStateFlow(WatchItemsState())
  val state = mutableState.asStateFlow()
  private val revision = java.util.concurrent.atomic.AtomicLong(0)

  suspend fun open() {
    try {
      val items = withContext(Dispatchers.IO) { store.all() }
      mutableState.update { it.copy(items = items) }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      mutableState.update { it.copy(message = "本机关注项读取失败，请重试；不会清除数据") }
    }
  }

  fun showAdd() {
    if (state.value.saving) return
    revision.incrementAndGet()
    mutableState.update { it.copy(adding = true, confirmation = null, codes = emptyList(), message = null, loading = false, deleting = null) }
  }

  /** 编辑已有关注项：站台固定不换绑，只改选线路；离线也先展示本机记录。 */
  suspend fun edit(stopCode: String) {
    if (state.value.saving) return
    val item = runCatching { withContext(Dispatchers.IO) { store.find(stopCode) } }.getOrNull() ?: return
    val station = StationChoice(item.stationName, item.stopCode, listOf(StationStop(item.stopCode, item.stationName)), item.lastCheckedAt ?: 0)
    val token = revision.incrementAndGet()
    mutableState.update {
      it.copy(
        adding = true, currentPage = false, loading = true, message = null, codes = emptyList(), deleting = null,
        confirmation = WatchItemImportSession(station, item.stopCode, emptyList(), emptySet(), item, editing = true, records = item.selectedLines),
      )
    }
    try {
      loadStop(station, item.stopCode, token, restoreOriginal = true)
    } catch (error: Exception) {
      handleFailure(token, error)
    }
  }

  fun cancel() {
    if (state.value.saving) return
    revision.incrementAndGet()
    mutableState.update { it.copy(adding = false, editing = false, confirmation = null, codes = emptyList(), loading = false, message = null) }
  }

  fun showCurrent(current: Boolean) {
    cancel()
    mutableState.update { it.copy(currentPage = current) }
  }

  fun dismissMessage() { mutableState.update { it.copy(message = null) } }

  fun imageFailure(message: String) {
    mutableState.update { it.copy(message = message, adding = true) }
  }

  suspend fun decoded(rawValues: List<String>) {
    val codes = rawValues.mapNotNull(QrPayloadParser::parseGpsNumber).distinct()
    when {
      rawValues.isEmpty() -> imageFailure("图片中未发现二维码，可以换张图片重试")
      codes.isEmpty() -> imageFailure("这不是受支持的公交站台码，不会打开陌生网页")
      codes.size > 1 -> mutableState.update { it.copy(codes = codes, adding = true) }
      else -> importCode(codes.single())
    }
  }

  suspend fun importCode(gpsNumber: String) {
    if (state.value.saving) return
    val token = revision.incrementAndGet()
    mutableState.update { it.copy(loading = true, adding = true, codes = emptyList(), confirmation = null, message = null) }
    try {
      val station = source.resolveStation(gpsNumber)
      if (token != revision.get()) return
      mutableState.update { it.copy(confirmation = WatchItemImportSession(station, station.defaultStopCode)) }
      loadStop(station, station.defaultStopCode, token, restoreOriginal = true)
    } catch (error: Exception) {
      handleFailure(token, error)
    }
  }

  suspend fun chooseStop(stopCode: String) {
    val current = state.value.confirmation ?: return
    if (state.value.saving || current.editing || current.station.stops.none { it.stopCode == stopCode }) return
    if (stopCode == current.stopCode && current.fetchedAt != null) return
    val token = revision.incrementAndGet()
    // 换站台立即清空本次选择，旧请求即使迟到也不能恢复另一站台的线路。
    mutableState.update { it.copy(confirmation = WatchItemImportSession(current.station, stopCode, restoreOriginalOnLoad = false), loading = true, message = null) }
    try {
      loadStop(current.station, stopCode, token, restoreOriginal = false)
    } catch (error: Exception) {
      handleFailure(token, error)
    }
  }

  suspend fun retry() {
    val current = state.value.confirmation
    if (current == null) {
      imageFailure("请重新选择二维码图片")
      return
    }
    if (state.value.loading || state.value.saving) return
    val token = revision.incrementAndGet()
    mutableState.update { it.copy(loading = true, message = null) }
    try { loadStop(current.station, current.stopCode, token, restoreOriginal = current.fetchedAt == null && current.restoreOriginalOnLoad) }
    catch (error: Exception) { handleFailure(token, error) }
  }

  private suspend fun loadStop(station: StationChoice, stopCode: String, token: Long, restoreOriginal: Boolean) {
    val snapshot = source.resolveLines(station.stationName, stopCode)
    val original = withContext(Dispatchers.IO) { store.find(stopCode) }
    if (token != revision.get()) return
    val latestIds = snapshot.value.map { it.identity }.toSet()
    val latestDirections = snapshot.value.associateBy { it.direction }
    val base = state.value.confirmation?.takeIf { it.stopCode == stopCode }
    val editing = base?.editing == true
    val records = when {
      editing -> reconcileRecords(base.records.ifEmpty { original?.selectedLines.orEmpty() }, latestDirections)
      restoreOriginal -> original?.selectedLines.orEmpty()
      else -> emptyList()
    }
    val previous = base?.takeIf { it.fetchedAt != null }
    val chosen = when {
      previous != null -> previous.selected.filter { it in latestIds }.toSet()
      records.isNotEmpty() -> records.filter { !it.obsolete }.mapNotNull { saved -> latestDirections[saved.direction]?.identity }.toSet()
      else -> emptySet()
    }
    val effective = if (editing) original else null
    mutableState.update {
      it.copy(
        loading = false,
        confirmation = WatchItemImportSession(
          station = if (effective != null) StationChoice(effective.stationName, effective.stopCode, listOf(StationStop(effective.stopCode, effective.stationName)), snapshot.fetchedAt) else station,
          stopCode = stopCode,
          availableLines = snapshot.value,
          selected = chosen,
          original = original,
          fetchedAt = snapshot.fetchedAt,
          nextAllowedAt = snapshot.nextAllowedAt,
          editing = editing,
          records = records,
        ),
      )
    }
  }

  /** 最新成功清单中原方向仍在则更新显示名，消失才标过时；两类转换各产生一次安静文字通知。 */
  private fun reconcileRecords(records: List<SavedLine>, latest: Map<String, SelectedLine>): List<SavedLine> = records.map { saved ->
    val fresh = latest[saved.direction]
    if (fresh == null) saved.copy(obsolete = true)
    else SavedLine(fresh.lineNo, fresh.upDown, fresh.endStationName, fresh.targetSequence, obsolete = false, lineName = fresh.lineName)
  }

  fun selectLine(identity: String, checked: Boolean) {
    if (state.value.loading || state.value.saving) return
    mutableState.update { state ->
      val draft = state.confirmation ?: return@update state
      if (draft.availableLines.none { it.identity == identity }) return@update state
      state.copy(confirmation = draft.copy(selected = if (checked) draft.selected + identity else draft.selected - identity), message = null)
    }
  }

  /** 过时或本机未核对记录只允许删除；删除后即使没有有效线路也保留关注项。 */
  suspend fun removeRecord(identity: String) {
    if (state.value.saving) return
    val draft = state.value.confirmation ?: return
    val record = draft.records.firstOrNull { it.identity == identity } ?: return
    val remaining = draft.records - record
    val original = draft.original
    val nextSelected = draft.selected - identity
    if (original == null) {
      mutableState.update { state -> state.copy(confirmation = state.confirmation?.let { it.copy(records = remaining, selected = nextSelected) }, message = null) }
      return
    }
    val keep = original.selectedLines.filterNot { it.identity == identity }
    val item = original.copy(selectedLinesJson = Json.encodeToString(keep))
    try {
      withContext(Dispatchers.IO) { store.save(item) }
      mutableState.update { state ->
        state.copy(
          items = state.items.map { existing -> if (existing.stopCode == item.stopCode) item else existing },
          confirmation = state.confirmation?.takeIf { it.stopCode == item.stopCode }?.copy(original = item, records = remaining, selected = nextSelected),
          message = if (keep.isEmpty()) "过时记录已删除；关注项仍保留，可稍后重新选择线路" else "记录已删除，关注项仍保留",
        )
      }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      mutableState.update { it.copy(message = "删除失败，记录仍保留，请重试") }
    }
  }

  /** 列表里直接删除某条记录（通常是过时记录）；不需要联网，也不改变站台绑定。 */
  suspend fun removeStoredRecord(stopCode: String, identity: String) {
    if (state.value.saving) return
    val item = state.value.items.firstOrNull { it.stopCode == stopCode }
      ?: runCatching { withContext(Dispatchers.IO) { store.find(stopCode) } }.getOrNull() ?: return
    val keep = item.selectedLines.filterNot { it.identity == identity }
    if (keep.size == item.selectedLines.size) return
    val updated = item.copy(
      selectedLinesJson = Json.encodeToString(keep),
      endStationName = keep.firstOrNull()?.endStationName ?: item.endStationName,
      lineNo = keep.firstOrNull()?.lineNo ?: item.lineNo,
      upDown = keep.firstOrNull()?.upDown ?: item.upDown,
      targetSequence = keep.firstOrNull()?.targetSequence ?: item.targetSequence,
    )
    try {
      withContext(Dispatchers.IO) { store.save(updated) }
      mutableState.update { state ->
        state.copy(
          items = state.items.map { existing -> if (existing.stopCode == stopCode) updated else existing },
          confirmation = state.confirmation?.takeIf { it.stopCode == stopCode }?.let { draft ->
            draft.copy(original = updated, records = draft.records.filterNot { it.identity == identity }, selected = draft.selected - identity)
          },
          message = if (keep.isEmpty()) "过时记录已删除；关注项仍保留，可稍后重新选择线路" else "记录已删除，关注项仍保留",
        )
      }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      mutableState.update { it.copy(message = "删除失败，记录仍保留，请重试") }
    }
  }

  /** 调整本地步行时间与预留余量：不需要联网，也不改变线路记录。 */
  suspend fun updateSettings(stopCode: String, walkMinutes: Double?, bufferMinutes: Double) {
    val item = state.value.items.firstOrNull { it.stopCode == stopCode }
      ?: runCatching { withContext(Dispatchers.IO) { store.find(stopCode) } }.getOrNull() ?: return
    val updated = item.copy(walkMinutes = walkMinutes, bufferMinutes = bufferMinutes)
    try {
      withContext(Dispatchers.IO) { store.save(updated) }
      mutableState.update { state ->
        state.copy(items = state.items.map { existing -> if (existing.stopCode == stopCode) updated else existing }, message = "本地设置已保存")
      }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      mutableState.update { it.copy(message = "本地设置保存失败，原设置未改变") }
    }
  }

  fun requestDelete(stopCode: String) {
    if (state.value.saving) return
    mutableState.update { it.copy(deleting = stopCode, message = null) }
  }

  fun cancelDelete() { mutableState.update { it.copy(deleting = null) } }

  suspend fun confirmDelete() {
    val stopCode = state.value.deleting ?: return
    try {
      withContext(Dispatchers.IO) { store.delete(stopCode) }
      revision.incrementAndGet()
      mutableState.update {
        it.copy(items = it.items.filterNot { item -> item.stopCode == stopCode }.sortedBy { item -> item.stationName }, deleting = null, confirmation = null, adding = false, message = "关注项已删除")
      }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      mutableState.update { it.copy(deleting = null, message = "删除失败，关注项仍保留，请重试") }
    }
  }

  /**
   * 关注项列表与手动刷新共用的联网核对，与页面和编辑共享同一 60 秒节流与在途合并。
   * 失败项保留原数据及上次成功获取时间，绝不据此标过时或发通知。
   */
  suspend fun refresh() {
    val current = state.value
    if (current.adding || current.saving || current.loading) return
    val items = runCatching { withContext(Dispatchers.IO) { store.all() } }.getOrElse { error ->
      if (error is CancellationException) throw error
      mutableState.update { it.copy(message = "本机关注项读取失败，请重试；不会清除数据") }
      return
    }
    if (items.isEmpty()) {
      mutableState.update { it.copy(items = items) }
      return
    }
    var failed = 0
    val verified = items.map { item ->
      val snapshot = try {
        source.resolveLines(item.stationName, item.stopCode)
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        failed++
        return@map item
      }
      val (checked, events) = reconcile(item, snapshot.value, snapshot.fetchedAt)
      try {
        withContext(Dispatchers.IO) { store.save(checked) }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        failed++
        return@map item
      }
      // 通知是旁路：未授权或发送失败都不能影响已核对的数据。
      events.forEach { event -> runCatching { notifier.notify(event) } }
      checked
    }
    mutableState.update {
      it.copy(
        items = verified.sortedBy { item -> item.stationName },
        message = when {
          failed == 0 -> "已核对 ${items.size} 个关注项的线路"
          failed == items.size -> "线路核对失败：已保留上次成功数据与获取时间"
          else -> "部分线路核对失败（$failed/${items.size}）：失败项已保留上次成功数据"
        },
      )
    }
  }

  /** 只有最新成功清单才算核对；转换次数决定通知次数，持续同状态不产生事件。 */
  private fun reconcile(item: WatchItemEntity, latest: List<SelectedLine>, fetchedAt: Long): Pair<WatchItemEntity, List<LineStatusEvent>> {
    val byDirection = latest.associateBy { it.direction }
    val events = mutableListOf<LineStatusEvent>()
    val lines = item.selectedLines.map { saved ->
      val fresh = byDirection[saved.direction]
      if (fresh == null) {
        if (!saved.obsolete) events += event(item, saved, restored = false)
        saved.copy(obsolete = true)
      } else {
        if (saved.obsolete) events += event(item, saved, restored = true)
        SavedLine(fresh.lineNo, fresh.upDown, fresh.endStationName, fresh.targetSequence, obsolete = false, lineName = fresh.lineName)
      }
    }
    val first = lines.firstOrNull()
    return item.copy(
      endStationName = first?.endStationName ?: item.endStationName,
      lineNo = first?.lineNo ?: item.lineNo,
      upDown = first?.upDown ?: item.upDown,
      targetSequence = first?.targetSequence ?: item.targetSequence,
      selectedLinesJson = Json.encodeToString(lines),
      lastCheckedAt = fetchedAt,
    ) to events
  }

  private fun event(item: WatchItemEntity, saved: SavedLine, restored: Boolean) = LineStatusEvent(
    stopCode = item.stopCode,
    stationName = item.stationName,
    lineName = saved.lineName,
    upDown = saved.upDown,
    endStationName = saved.endStationName,
    restored = restored,
  )

  suspend fun save() {
    val current = state.value
    val draft = current.confirmation ?: return
    if (current.saving || current.loading) return
    val selected = draft.availableLines.filter { it.identity in draft.selected }
    if (selected.isEmpty()) {
      mutableState.update { it.copy(message = "至少选择一条有效线路才能保存") }
      return
    }
    mutableState.update { it.copy(saving = true, message = null) }
    try {
      // 编辑时一并保留未核对的过时记录，避免保存把记录悄悄丢掉。
      val lines = selected.map { SavedLine(it.lineNo, it.upDown, it.endStationName, it.targetSequence, lineName = it.lineName) } + draft.records.filter { it.obsolete }
      val first = lines.first()
      val original = draft.original
      val item = WatchItemEntity(
        stopCode = draft.stopCode,
        stationName = draft.station.stationName,
        endStationName = first.endStationName,
        lineNo = first.lineNo,
        upDown = first.upDown,
        targetSequence = first.targetSequence,
        selectedLinesJson = Json.encodeToString(lines),
        walkMinutes = original?.walkMinutes,
        bufferMinutes = original?.bufferMinutes ?: 2.0,
        lastCheckedAt = draft.fetchedAt ?: original?.lastCheckedAt,
      )
      withContext(Dispatchers.IO) { store.save(item) }
      revision.incrementAndGet()
      mutableState.update {
        it.copy(items = (it.items.filterNot { saved -> saved.stopCode == item.stopCode } + item).sortedBy { saved -> saved.stationName }, saving = false, adding = false, editing = false, confirmation = null, message = "关注项已保存，没有开始等车")
      }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      mutableState.update { it.copy(saving = false, message = "保存失败，选线草稿仍保留，请重试；已有关注项未改变") }
    }
  }

  private fun handleFailure(token: Long, error: Exception) {
    if (error is CancellationException) throw error
    if (token != revision.get()) return
    mutableState.update { it.copy(loading = false, message = "站台或线路获取失败：${error.message ?: "请稍后重试"}；已有关注项未改变") }
  }
}

data class WatchItemsState(
  val items: List<WatchItemEntity> = emptyList(),
  val adding: Boolean = false,
  val editing: Boolean = false,
  val currentPage: Boolean = false,
  val codes: List<String> = emptyList(),
  val confirmation: WatchItemImportSession? = null,
  val loading: Boolean = false,
  val saving: Boolean = false,
  val deleting: String? = null,
  val message: String? = null,
)

/** 数据替换仅在统一应用边界进行；生产实现始终访问同一 Room 数据库。 */
interface WatchItemStore {
  suspend fun find(stopCode: String): WatchItemEntity?
  suspend fun save(item: WatchItemEntity)
  suspend fun delete(stopCode: String)
  suspend fun all(): List<WatchItemEntity>
}
