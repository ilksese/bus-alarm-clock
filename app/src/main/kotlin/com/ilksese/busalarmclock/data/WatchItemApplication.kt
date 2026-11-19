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
  /** 线路过时/恢复的安静通知；默认不通知，测试与生产各自注入。 */
  private val notifier: LineStatusNotifier = LineStatusNotifier { },
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
      // 上次被系统杀死时进程内来不及清理，状态栏可能残留那条常驻通知；重开时按同一 id 撤下。
      // 只在本进程没有任何在跑的一轮时才撤，避免重建页面把正在监测的通知误删。
      else if (last != null && session == null) platform.dismiss()
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      mutableState.update { it.copy(message = "本机关注项读取失败，请重试；不会清除数据") }
    }
  }

  fun showAdd() {
    if (state.value.saving) return
    revision.incrementAndGet()
    mutableState.update { it.copy(adding = true, cameraActive = false, confirmation = null, codes = emptyList(), message = null, loading = false, deleting = null) }
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
    mutableState.update { it.copy(adding = false, cameraActive = false, editing = false, confirmation = null, codes = emptyList(), loading = false, message = null) }
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
    // 结束即撤下常驻通知：状态栏不能留一条点不动的"正在监测"。
    platform.dismiss()
  }

  fun imageFailure(message: String) {
    mutableState.update { it.copy(message = message, adding = true) }
  }

  /**
   * 前台服务在进程内判定失败（例如系统拒绝前台提升）时结束同一轮等车。
   * 只结束仍在运行的那一轮；已结束的轮次不被旧回调改写。
   */
  suspend fun failWait(reason: WaitEndReason) {
    val running = session
    session = null
    if (running != null) running.terminate()
    else mutableState.update { it.copy(wait = it.wait.copy(running = false, endedReason = reason, message = reason.message)) }
    platform.dismiss()
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

  private fun handleFailure(token: Long, error: Exception) {    if (error is CancellationException) throw error
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
  val cameraActive: Boolean = false,
  val preflight: WaitPreflight? = null,
  val wait: WaitState = WaitState(),
)

/** 数据替换仅在统一应用边界进行；生产实现始终访问同一 Room 数据库。 */
interface WatchItemStore {
  suspend fun find(stopCode: String): WatchItemEntity?
  suspend fun save(item: WatchItemEntity)
  suspend fun delete(stopCode: String)
  suspend fun all(): List<WatchItemEntity>
}
