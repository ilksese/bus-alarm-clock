package com.ilksese.busalarmclock.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** UI 与测试共用的应用行为入口；草稿、迟到响应、请求与保存结果均由此处决定。 */
class WatchItemApplication(private val source: BusSourceClient, private val store: WatchItemStore) {
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
    mutableState.update { it.copy(adding = true, cameraActive = false, confirmation = null, codes = emptyList(), message = null, loading = false) }
  }

  fun cancel() {
    if (state.value.saving) return
    revision.incrementAndGet()
    mutableState.update { it.copy(adding = false, cameraActive = false, confirmation = null, codes = emptyList(), loading = false, message = null) }
  }

  /**
   * 进入相机扫码。相机只在添加流程内、且用户主动点扫码后开启；
   * 这里不产生任何请求，退出添加流程即关闭，不存在候车期间的相机前台服务。
   */
  fun openCamera() {
    if (state.value.saving) return
    mutableState.update { it.copy(adding = true, cameraActive = true, message = null) }
  }

  fun closeCamera() {
    mutableState.update { it.copy(cameraActive = false) }
  }

  fun cameraPermissionDenied() {
    mutableState.update {
      it.copy(cameraActive = false, adding = true, message = "相机未授权，仍可从相册选择；不会保存照片")
    }
  }

  /**
   * 相机取景识别到一条二维码：先经与相册图片完全相同的严格来源校验，
   * 通过才复用同一导入入口；不受支持或无效码保留取景以便继续扫。
   */
  suspend fun cameraDecoded(rawValue: String) {
    if (state.value.saving) return
    val code = parseCameraCode(rawValue)
    if (code == null) {
      mutableState.update { it.copy(message = "这不是受支持的公交站台码，不会打开陌生网页", cameraActive = true) }
      return
    }
    revision.incrementAndGet()
    mutableState.update { it.copy(cameraActive = false, message = null) }
    importCode(code)
  }

  /**
   * 相机码是否会被这个入口接管，供取景层判断本次识别要不要占用“一码只交付一次”的机会。
   * 与 [cameraDecoded] 共用同一校验，不在取景层重复站台规则。
   */
  fun isCameraCodeAccepted(rawValue: String): Boolean = !state.value.saving && parseCameraCode(rawValue) != null

  private fun parseCameraCode(rawValue: String): String? = QrPayloadParser.parseGpsNumber(rawValue)

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
    if (state.value.saving || current.station.stops.none { it.stopCode == stopCode }) return
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
    val availableIds = snapshot.value.map { it.identity }.toSet()
    val previous = state.value.confirmation?.takeIf { it.stopCode == stopCode && it.fetchedAt != null }
    val chosen = when {
      previous != null -> previous.selected.filter { it in availableIds }.toSet()
      restoreOriginal -> original?.selectedLines.orEmpty().map { it.identity }.filter { it in availableIds }.toSet()
      else -> emptySet()
    }
    mutableState.update {
      it.copy(
        loading = false,
        confirmation = WatchItemImportSession(station, stopCode, snapshot.value, chosen, original, snapshot.fetchedAt, snapshot.nextAllowedAt),
      )
    }
  }

  fun selectLine(identity: String, checked: Boolean) {
    if (state.value.loading || state.value.saving) return
    mutableState.update { state ->
      val draft = state.confirmation ?: return@update state
      if (draft.availableLines.none { it.identity == identity }) return@update state
      state.copy(confirmation = draft.copy(selected = if (checked) draft.selected + identity else draft.selected - identity), message = null)
    }
  }

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
      val first = selected.first()
      val original = draft.original
      val item = WatchItemEntity(
        stopCode = draft.stopCode,
        stationName = draft.station.stationName,
        endStationName = first.endStationName,
        lineNo = first.lineNo,
        upDown = first.upDown,
        targetSequence = first.targetSequence,
        selectedLinesJson = Json.encodeToString(selected.map { SavedLine(it.lineNo, it.upDown, it.endStationName, it.targetSequence, lineName = it.lineName) }),
        walkMinutes = original?.walkMinutes,
        bufferMinutes = original?.bufferMinutes ?: 2.0,
      )
      withContext(Dispatchers.IO) { store.save(item) }
      revision.incrementAndGet()
      mutableState.update {
        it.copy(items = (it.items.filterNot { saved -> saved.stopCode == item.stopCode } + item).sortedBy { saved -> saved.stationName }, saving = false, adding = false, confirmation = null, message = "关注项已保存，没有开始等车")
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
  val currentPage: Boolean = false,
  val codes: List<String> = emptyList(),
  val confirmation: WatchItemImportSession? = null,
  val loading: Boolean = false,
  val saving: Boolean = false,
  val message: String? = null,
  val cameraActive: Boolean = false,
)

/** 数据替换仅在统一应用边界进行；生产实现始终访问同一 Room 数据库。 */
interface WatchItemStore {
  suspend fun find(stopCode: String): WatchItemEntity?
  suspend fun save(item: WatchItemEntity)
  suspend fun all(): List<WatchItemEntity>
}
