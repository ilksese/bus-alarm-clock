package com.ilksese.busalarmclock.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** UI 与测试共用的应用行为入口；草稿、迟到响应、请求与保存结果均由此处决定。 */
class WatchItemApplication(
  private val source: BusSourceClient,
  private val store: WatchItemStore,
  private val platform: WaitPlatform = HeadlessWaitPlatform,
  private val clock: () -> Long = System::currentTimeMillis,
  private val tasks: WaitTaskStore,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
  private val mutableState = MutableStateFlow(WatchItemsState())
  val state = mutableState.asStateFlow()
  private val revision = java.util.concurrent.atomic.AtomicLong(0)
  private var session: WaitSession? = null
  private val taskIds = java.util.concurrent.atomic.AtomicLong(clock())

  suspend fun open() {
    try {
      val items = withContext(Dispatchers.IO) { store.all() }
      val last = withContext(Dispatchers.IO) { tasks.load() }
      mutableState.update {
        it.copy(
          items = items,
          // 重开只解释上次发生了什么；不自动恢复、不补发提醒。
          wait = when {
            last == null -> it.wait
            last.endedReason == WaitEndReason.CANCELLED -> WaitState(stopCode = last.stopCode, endedReason = WaitEndReason.CANCELLED, message = "上次等车已主动取消。")
            last.endedReason != null -> WaitState(stopCode = last.stopCode, endedReason = last.endedReason, message = last.endedReason.message)
            else -> WaitState(stopCode = last.stopCode, endedReason = WaitEndReason.INTERRUPTED, message = WaitEndReason.INTERRUPTED.message)
          },
        )
      }
      // 已经解释过的结束记录不再保留；仍在运行的记录保留到下次，直到有新任务覆盖。
      if (last?.endedReason != null) withContext(Dispatchers.IO) { tasks.clear() }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      mutableState.update { it.copy(message = "本机关注项读取失败，请重试；不会清除数据") }
    }
  }

  fun showAdd() {
    if (state.value.saving) return
    revision.incrementAndGet()
    mutableState.update { it.copy(adding = true, confirmation = null, codes = emptyList(), message = null, loading = false) }
  }

  fun cancel() {
    if (state.value.saving) return
    revision.incrementAndGet()
    mutableState.update { it.copy(adding = false, confirmation = null, codes = emptyList(), loading = false, message = null) }
  }

  fun showCurrent(current: Boolean) {
    cancel()
    mutableState.update { it.copy(currentPage = current) }
  }

  fun dismissMessage() { mutableState.update { it.copy(message = null) } }

  /** 从关注项打开开始核对；W/B 已保存过的沿用，不在此处自动估算。 */
  fun prepareWait(stopCode: String) {
    val item = state.value.items.firstOrNull { it.stopCode == stopCode } ?: return
    // 运行中的任务不被静默覆盖：明确提示，交由用户先结束当前这轮。
    if (state.value.wait.running) {
      mutableState.update { it.copy(currentPage = true, preflight = null, message = "已有一轮等车在运行，先结束它再开始。") }
      return
    }
    mutableState.update {
      it.copy(
        currentPage = true,
        adding = false,
        confirmation = null,
        wait = WaitState(stopCode = item.stopCode, stationName = item.stationName, walkMinutes = item.walkMinutes, bufferMinutes = item.bufferMinutes),
        preflight = WaitPreflight(
          stopCode = item.stopCode,
          stationName = item.stationName,
          lines = item.selectedLines,
          walkText = item.walkMinutes?.let { value -> formatMinutes(value) } ?: "",
          bufferText = formatMinutes(item.bufferMinutes),
        ),
        message = null,
      )
    }
  }

  fun updateWalk(text: String) = mutableState.update { state ->
    state.preflight?.let { state.copy(preflight = it.copy(walkText = text, error = null)) } ?: state
  }

  fun updateBuffer(text: String) = mutableState.update { state ->
    state.preflight?.let { state.copy(preflight = it.copy(bufferText = text, error = null)) } ?: state
  }

  fun dismissPreflight() {
    if (state.value.preflight?.starting == true || state.value.wait.running) return
    mutableState.update { it.copy(preflight = null) }
  }

  /** 开始前的联网核对：允许复用 60 秒内成功结果，但始终展示原始获取时间。 */
  suspend fun verifyWait() {
    val draft = state.value.preflight ?: return
    if (draft.verifying || draft.starting) return
    mutableState.update { it.copy(preflight = draft.copy(verifying = true, error = null)) }
    try {
      val snapshot = source.observeStop(draft.stopCode)
      val available = snapshot.value.lines.map { it.identity }.toSet()
      mutableState.update { state ->
        val current = state.preflight ?: return@update state
        val missing = current.lines.none { line -> line.identity in available }
        state.copy(
          preflight = current.copy(
            verifying = false,
            verifiedAt = snapshot.fetchedAt,
            error = if (missing) "该站台已没有你选择的线路，请先回到关注项重新选线" else null,
          ),
        )
      }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      mutableState.update { state ->
        val current = state.preflight ?: return@update state
        // 核对失败不能离线启动，也不能把关注项里的旧数据当成刚核对过。
        state.copy(preflight = current.copy(verifying = false, verifiedAt = null, error = "站台线路无法联网核对：${error.message ?: "请稍后重试"}"))
      }
    }
  }

  /** 由可见页面手动开始一次有限等车；任一前置条件不满足都不假启动。 */
  suspend fun startWait() {
    val current = state.value
    val draft = current.preflight ?: return
    if (draft.starting || draft.verifying) return
    if (current.wait.running) {
      mutableState.update { it.copy(preflight = draft.copy(error = "已有一轮等车在运行，先结束它再开始。")) }
      return
    }
    val walk = draft.walkMinutes
    val buffer = draft.bufferMinutes
    if (walk == null) {
      mutableState.update { it.copy(preflight = draft.copy(error = "请填写步行时间，且必须大于 0 分钟")) }
      return
    }
    if (buffer == null) {
      mutableState.update { it.copy(preflight = draft.copy(error = "预留余量不能为负，可以填 0")) }
      return
    }
    if (draft.lines.isEmpty()) {
      mutableState.update { it.copy(preflight = draft.copy(error = "没有有效选线，请先回到关注项选择线路")) }
      return
    }
    if (!platform.notificationsAllowed()) {
      mutableState.update { it.copy(preflight = draft.copy(error = "通知总开关或出门提醒类别已关闭，无法保证提醒，未开始监测")) }
      return
    }
    if (draft.verifiedAt == null) {
      // 未核对过就必须先联网核对；核对失败不会进入启动分支。
      verifyWait()
      if (state.value.preflight?.verifiedAt == null) return
      // 核对成功后直接开始，不要求用户再点一次同一个按钮。
      return startWait()
    }
    mutableState.update { it.copy(preflight = draft.copy(starting = true, error = null)) }
    // W/B 保存到关注项；保存失败不假启动。
    try {
      val item = withContext(Dispatchers.IO) { store.find(draft.stopCode) }
      if (item != null && (item.walkMinutes != walk || item.bufferMinutes != buffer)) {
        withContext(Dispatchers.IO) { store.save(item.copy(walkMinutes = walk, bufferMinutes = buffer)) }
        val refreshed = withContext(Dispatchers.IO) { store.all() }
        mutableState.update { it.copy(items = refreshed) }
      }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      mutableState.update { it.copy(preflight = draft.copy(starting = false, error = "本机保存失败，未开始监测：${error.message ?: "请重试"}")) }
      return
    }
    val now = clock()
    val task = WaitTask(
      id = taskIds.incrementAndGet(),
      stopCode = draft.stopCode,
      stationName = draft.stationName,
      startedAt = now,
      deadlineAt = now + MAX_WAIT,
      lastSuccessAt = null,
      walkMinutes = walk,
      bufferMinutes = buffer,
      linesJson = Json.encodeToString(draft.lines),
    )
    val started = platform.startForegroundService()
    if (!started) {
      mutableState.update { it.copy(preflight = draft.copy(starting = false, error = "前台服务启动失败，未开始监测")) }
      return
    }
    // 最少任务状态先落盘，用于下次打开时解释中断；保存失败即拒绝启动，不假启动。
    try {
      withContext(Dispatchers.IO) { tasks.save(task) }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      platform.stopForegroundService()
      mutableState.update { it.copy(preflight = draft.copy(starting = false, error = "本机任务状态保存失败，未开始监测")) }
      return
    }
    val running = WaitSession(
      source = source,
      platform = platform,
      task = task,
      clock = clock,
      sleeper = sleeper,
      scope = scope,
      onState = { next ->
        mutableState.update { state -> state.copy(wait = next) }
        // 每次状态变化都带任务身份写回：迟到回调改写不了新任务，也改不了已结束的记录。
        scope.launch {
          withContext(Dispatchers.IO) {
            runCatching {
              if (next.running) tasks.progress(task.id, next.phase ?: WaitPhase.WAITING_FIRST, next.lastSuccessAt)
              else tasks.finish(task.id, next.endedReason ?: WaitEndReason.INTERRUPTED, next.endedAt ?: clock())
            }
          }
        }
      },
    )
    session = running
    mutableState.update { it.copy(preflight = null, adding = false, confirmation = null, currentPage = true, wait = running.current()) }
    running.start()
  }

  /** 主动结束：立即停取数，退出前台服务，不发异常提示。 */
  suspend fun stopWait() {
    val running = session
    session = null
    if (running != null) running.cancel()
    else mutableState.update { it.copy(wait = it.wait.copy(running = false, endedReason = WaitEndReason.CANCELLED, message = WaitEndReason.CANCELLED.message)) }
  }

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

  private fun handleFailure(token: Long, error: Exception) {    if (error is CancellationException) throw error
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
  val preflight: WaitPreflight? = null,
  val wait: WaitState = WaitState(),
)

/** 数据替换仅在统一应用边界进行；生产实现始终访问同一 Room 数据库。 */
interface WatchItemStore {
  suspend fun find(stopCode: String): WatchItemEntity?
  suspend fun save(item: WatchItemEntity)
  suspend fun all(): List<WatchItemEntity>
}
