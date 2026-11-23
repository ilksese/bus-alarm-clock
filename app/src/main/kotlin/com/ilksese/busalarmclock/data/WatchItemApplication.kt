package com.ilksese.busalarmclock.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.withLock
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
  private val submissionSleeper: suspend (Long) -> Unit = { delay(it) },
  private val elapsedClock: () -> Long = { System.nanoTime() / 1_000_000L },
  /** 线路过时/恢复的安静通知；默认不通知，测试与生产各自注入。 */
  private val notifier: LineStatusNotifier = LineStatusNotifier { },
  private val requestDeadlineSleeper: suspend (Long) -> Unit = { delay(it) },
) {
  private val mutableState = MutableStateFlow(WatchItemsState())
  val state = mutableState.asStateFlow()
  private val revision = java.util.concurrent.atomic.AtomicLong(0)
  @Volatile private var session: WaitSession? = null
  private val taskChanges = kotlinx.coroutines.sync.Mutex()
  private val notificationHandoff = kotlinx.coroutines.sync.Mutex()

  private fun activeTask(stopCode: String? = null): Long? = session?.current()?.takeIf {
    it.running && (stopCode == null || it.stopCode == stopCode)
  }?.taskId
  private val taskIds = java.util.concurrent.atomic.AtomicLong(clock())
  private val settingsLock = kotlinx.coroutines.sync.Mutex()

  suspend fun setVoiceEnabled(enabled: Boolean) = settingsLock.withLock {
    try {
      withContext(Dispatchers.IO) { store.setVoiceEnabled(enabled) }
      mutableState.update { it.copy(voiceEnabled = enabled, message = null) }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      mutableState.update { it.copy(message = "语音设置保存失败，仍使用原设置。") }
    }
  }

  suspend fun open() {
    settingsLock.withLock {
      try {
        val enabled = withContext(Dispatchers.IO) { store.voiceEnabled() }
        mutableState.update { it.copy(voiceEnabled = enabled) }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        mutableState.update { it.copy(message = "语音设置读取失败，请重试。") }
      }
    }
    try {
      restoreLocalState()
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      mutableState.update { it.copy(message = "本机关注项读取失败，请重试；不会清除数据") }
    }
  }

  private suspend fun restoreLocalState() {
    val openingItems = state.value.items
    val liveTaskId = session?.current()?.takeIf { it.running }?.taskId
    val openingWait = state.value.wait
    val (items, last, savedAlerts) = withContext(Dispatchers.IO) {
      val items = store.all()
      val last = if (liveTaskId == null) tasks.load() ?: tasks.latestResult() else tasks.latestResult()
      val posted = platform.postedAlertIds() - setOfNotNull(liveTaskId)
      val saved = if (posted.isEmpty()) tasks.alerts() else tasks.alertsWithPosted(posted)
      Triple(items, last, saved)
    }
    val reason = last?.let { task ->
      val snapshot = savedAlerts.firstOrNull { it.snapshot.taskId == task.id }
      when {
        task.endedReason != null && task.endedReason !in setOf(
          WaitEndReason.CANCELLED, WaitEndReason.CANCELLED_DURING_DELIVERY, WaitEndReason.ALERT_DELIVERY_FAILED,
          WaitEndReason.ALERTED, WaitEndReason.ALERT_CONFIRMED,
        ) -> task.endedReason
        task.alertConfirmed || snapshot?.confirmed == true -> WaitEndReason.ALERT_CONFIRMED
        task.alert != null || snapshot != null -> WaitEndReason.ALERTED
        task.endedReason != null -> task.endedReason
        clock() >= task.deadlineAt -> WaitEndReason.MAX_DURATION
        clock() >= (task.lastSuccessAt ?: task.startedAt).plusDelay(MAX_SILENCE) -> WaitEndReason.NO_SUCCESS
        else -> WaitEndReason.INTERRUPTED
      }
    }
    if (liveTaskId == null && last != null && reason != null && last.endedReason == null) {
      taskChanges.withLock {
        // 读取本机记录期间另一轮可能已启动，重开只能收尾它读取到的旧任务。
        if (session == null && state.value.wait === openingWait) withContext(Dispatchers.IO) {
          if (tasks.load()?.id == last.id) {
            platform.dismiss()
            if (last.id !in platform.postedAlertIds()) tasks.discardPendingAlert(last.id)
            tasks.finish(last.id, reason, clock())
          }
        }
      }
    }
    taskIds.accumulateAndGet(maxOf(last?.id ?: 0L, savedAlerts.maxOfOrNull { it.snapshot.taskId } ?: 0L), { id, saved -> maxOf(id, saved) })
    mutableState.update { current ->
      val result = if (last != null && reason != null) WaitResult(last.id, last.stationName, reason) else null
      val wait = if (liveTaskId != null || session != null || current.wait !== openingWait || last == null || reason == null) current.wait else {
        WaitState(taskId = last.id, stopCode = last.stopCode, stationName = last.stationName,
          endedReason = reason, endedAt = last.endedAt, message = reason.message)
      }
      val liveId = session?.current()?.takeIf { it.running }?.taskId
      val restored = savedAlerts.filterNot { it.snapshot.taskId == liveTaskId || it.snapshot.taskId == liveId }
      val known = mergeAlerts(restored, current.wait.alerts).map { alert ->
        val existing = current.wait.alerts.firstOrNull { it.snapshot.taskId == alert.snapshot.taskId }
        if (existing?.confirmed == true || savedAlerts.any { it.snapshot.taskId == alert.snapshot.taskId && it.confirmed }) {
          alert.copy(confirmed = true, outputEnded = true)
        } else if (existing?.confirmationError == true) {
          alert.copy(confirmationError = true)
        } else alert
      }
      current.copy(
        items = if (current.saving || current.items !== openingItems) current.items else items,
        wait = wait.copy(alerts = known),
        latestResult = result?.takeIf { it.taskId >= (current.latestResult?.taskId ?: Long.MIN_VALUE) } ?: current.latestResult,
      )
    }
  }

  fun showAdd() {
    if (state.value.saving) return
    revision.incrementAndGet()
    mutableState.update { it.copy(adding = true, cameraActive = false, confirmation = null, codes = emptyList(), message = null, loading = false, deleting = null) }
  }

  /** 编辑已有关注项：站台固定不换绑，只改选线路；离线也先展示本机记录。 */
  suspend fun edit(stopCode: String) = edit(stopCode, null)

  private suspend fun edit(stopCode: String, approval: WaitChange?) {
    val setup = taskChanges.withLock {
      val item = readWatchItem(stopCode) ?: return
      if (!allowItemChange(stopCode, WaitChangeAction.EDIT, approval)) return
      val station = StationChoice(item.stationName, item.stopCode, listOf(StationStop(item.stopCode, item.stationName)), item.lastCheckedAt ?: 0)
      val token = revision.incrementAndGet()
      mutableState.update {
        it.copy(
          adding = true, currentPage = false, loading = true, message = null, codes = emptyList(), deleting = null,
          waitChange = null, preflight = null,
          confirmation = WatchItemImportSession(station, item.stopCode, emptyList(), emptySet(), item, editing = true, records = item.selectedLines),
        )
      }
      station to token
    }
    val (station, token) = setup
    try {
      loadStop(station, stopCode, token, restoreOriginal = true)
    } catch (error: Exception) {
      handleFailure(token, error)
    } finally {
      finishLoading(token)
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
    mutableState.update { state ->
      if (state.saving || state.preflight?.starting == true || state.waitChange != null || state.deleting != null) return@update state
      val sameWait = state.wait.running && state.wait.stopCode == stopCode
      state.copy(
        currentPage = true,
        adding = false,
        confirmation = null,
        preflight = if (sameWait) null else WaitPreflight(
          stopCode = item.stopCode,
          stationName = item.stationName,
          lines = item.selectedLines,
          walkText = item.walkMinutes?.let { value -> formatMinutes(value) } ?: "",
          bufferText = formatMinutes(item.bufferMinutes),
        ),
        message = if (sameWait) "已有一轮等车在运行" else null,
      )
    }
  }

  fun updateWalk(text: String) = mutableState.update { state ->
    state.preflight?.takeUnless { it.starting || it.checkingVoice }?.let {
      state.copy(preflight = it.copy(walkText = text, error = null))
    } ?: state
  }

  fun updateBuffer(text: String) = mutableState.update { state ->
    state.preflight?.takeUnless { it.starting || it.checkingVoice }?.let {
      state.copy(preflight = it.copy(bufferText = text, error = null))
    } ?: state
  }

  fun dismissPreflight() {
    mutableState.update {
      if (it.preflight?.starting == true || it.waitChangeCommitting) it
      else it.copy(preflight = null, waitChange = it.waitChange?.takeUnless { change -> change.action == WaitChangeAction.REPLACE })
    }
  }

  /** 等平台授权期间 Activity 可以重建；结果只归发起时的草稿。 */
  internal fun beginNotificationPermission(draft: WaitPreflight): Boolean {
    val current = state.value
    if (current.preflight !== draft || current.notificationPermissionDraft != null) return false
    return mutableState.compareAndSet(current, current.copy(notificationPermissionDraft = draft))
  }

  internal fun takeNotificationPermissionDraft(): WaitPreflight? {
    while (true) {
      val current = state.value
      val draft = current.notificationPermissionDraft ?: return null
      if (mutableState.compareAndSet(current, current.copy(notificationPermissionDraft = null))) return draft
    }
  }

  fun notificationPermissionDenied(expected: WaitPreflight? = state.value.preflight) {
    mutableState.update { state ->
      if (expected == null || state.preflight !== expected) state
      else state.copy(preflight = expected.copy(notificationBlocked = true, error = "通知未获授权，未开始监测。请在系统设置允许通知后重试。"))
    }
  }

  /** 开始前的联网核对：允许复用 60 秒内成功结果，但始终展示原始获取时间。 */
  suspend fun verifyWait() { verifyWait(state.value.preflight ?: return) }

  private fun isVerificationFresh(fetchedAt: Long?): Boolean {
    val now = clock()
    return fetchedAt != null && fetchedAt <= now && fetchedAt.delayUntil(now) < REFRESH_INTERVAL
  }

  private suspend fun verifyWait(draft: WaitPreflight): WaitPreflight? {
    if (draft.verifying || draft.starting || draft.checkingVoice) return null
    val verification = Any()
    val checking = state.value
    if (checking.preflight !== draft || !mutableState.compareAndSet(checking,
        checking.copy(preflight = draft.copy(verifying = true, error = null, verifiedAt = null, verification = verification)))) return null
    try {
      val snapshot = source.observeStop(draft.stopCode)
      val available = snapshot.value.lines.map { it.direction }.toSet()
      val verified = mutableState.updateAndGet { state ->
        val current = state.preflight?.takeIf { it.verification === verification } ?: return@updateAndGet state
        val error = when {
          !isVerificationFresh(snapshot.fetchedAt) -> "线路核对结果已过期，请稍后重试。"
          current.lines.none { !it.obsolete && it.direction in available } -> "该站台已没有你选择的线路，请先回到关注项重新选线"
          else -> null
        }
        state.copy(preflight = current.copy(verifying = false,
          verifiedAt = snapshot.fetchedAt.takeIf { error == null }, error = error))
      }.preflight
      return verified?.takeIf { it.verification === verification && it.verifiedAt != null }
    } catch (error: Exception) {
      mutableState.update { state ->
        val current = state.preflight?.takeIf { it.verification === verification } ?: return@update state
        val message = if (error is CancellationException) null else "站台线路无法联网核对：${error.message ?: "请稍后重试"}"
        state.copy(preflight = current.copy(verifying = false, verifiedAt = null, verification = null, error = message))
      }
      if (error is CancellationException) throw error
      return null
    }
  }

  /** 由可见页面手动开始一次有限等车；任一前置条件不满足都不假启动。 */
  suspend fun startWait(expected: WaitPreflight? = state.value.preflight) {
    if (expected != null) startWait(expected, false)
  }

  /** 重查只更新当前核对页，不代表用户同意开始监测。 */
  suspend fun recheckChineseVoice() {
    val current = state.value
    val draft = current.preflight?.takeIf { current.voiceEnabled && it.voiceUnavailable && !it.starting && !it.verifying } ?: return
    val checking = draft.copy(checkingVoice = true)
    if (draft.checkingVoice || !mutableState.compareAndSet(current, current.copy(preflight = checking))) return
    var available = false
    try {
      available = platform.chineseVoiceAvailable()
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      // 引擎连接失败也按不可用处理；下次仍可重查。
    } finally {
      mutableState.update { state ->
        if (state.preflight !== checking) state
        else state.copy(preflight = draft.copy(voiceUnavailable = !available && state.voiceEnabled))
      }
    }
  }

  suspend fun previewReminder() {
    val current = state.value
    val draft = current.preflight?.takeUnless { current.wait.running || it.starting || it.verifying || it.checkingVoice } ?: return
    val message = try {
      val includeVoice = current.voiceEnabled && !draft.voiceUnavailable
      val result = platform.previewReminder(includeVoice)
      buildList {
        add(if (result.interrupted) "试听已中断" else "试听试震结束")
        add(if (result.chime) "已启动提示音" else "未播放提示音")
        add(if (result.ringtone) "已启动铃声" else "未播放铃声")
        add(if (result.vibration) "已启动试震" else "未试震")
        if (current.voiceEnabled) add(if (result.voice) "中文语音已播报" else "中文语音未能播报")
      }.joinToString("；", postfix = "。")
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      "试听试震未能完成，请检查系统设置。"
    }
    mutableState.update { if (it.preflight === draft) it.copy(preflight = draft.copy(previewMessage = message)) else it }
  }

  suspend fun startWaitWithoutVoice() {
    val draft = state.value.preflight?.takeIf { it.voiceUnavailable } ?: return
    startWait(draft, withoutVoice = true)
  }

  private suspend fun startWait(draft: WaitPreflight, withoutVoice: Boolean = false, replacement: WaitChange? = null) {
    val current = state.value
    if (current.preflight !== draft || draft.starting || draft.verifying || draft.checkingVoice ||
      current.waitChange !== replacement || current.saving) return
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
    if (draft.lines.none { !it.obsolete }) {
      mutableState.update { it.copy(preflight = draft.copy(error = "没有有效选线，请先回到关注项选择线路")) }
      return
    }
    if (!platform.notificationsAllowed()) {
      mutableState.update { it.copy(preflight = draft.copy(notificationBlocked = true, error = "通知总开关或出门提醒类别已关闭，无法保证提醒，未开始监测")) }
      return
    }
    if (!isVerificationFresh(draft.verifiedAt)) {
      // 降级确认可能停留很久；继续走同一来源的共享请求门控。
      val verified = verifyWait(draft) ?: return
      // 核对成功仅能继续同一份草稿，不能把随后打开的站台自动启动。
      return startWait(verified, withoutVoice, replacement)
    }
    val starting = draft.copy(starting = true, notificationBlocked = false, voiceUnavailable = false, error = null)
    if (!mutableState.compareAndSet(current, current.copy(preflight = starting))) return
    try {
      continueStart(draft, starting, walk, buffer, current.voiceEnabled && !withoutVoice, withoutVoice, replacement)
    } finally {
      mutableState.update { state ->
        if (state.waitChange !== replacement) state
        else state.copy(waitChange = null, preflight = if (state.preflight === starting) draft else state.preflight)
      }
    }
  }

  private suspend fun continueStart(
    draft: WaitPreflight,
    starting: WaitPreflight,
    walk: Double,
    buffer: Double,
    voiceEnabled: Boolean,
    withoutVoice: Boolean,
    replacement: WaitChange?,
  ) {
    try {
      if (voiceEnabled && !platform.chineseVoiceAvailable()) {
        mutableState.update { if (it.preflight === starting) it.copy(preflight = draft.copy(voiceUnavailable = true, error = null)) else it }
        return
      }
    } catch (error: CancellationException) {
      mutableState.update { if (it.preflight === starting) it.copy(preflight = draft) else it }
      throw error
    }
    if (state.value.preflight !== starting) return
    if (!platform.notificationsAllowed()) {
      mutableState.update { if (it.preflight === starting) it.copy(preflight = draft.copy(notificationBlocked = true,
        error = "通知已关闭，未开始监测。")) else it }
      return
    }
    taskChanges.withLock {
      val current = state.value
      if (current.preflight !== starting || current.waitChange !== replacement || current.deleting != null) return
      if (!isVerificationFresh(draft.verifiedAt)) {
        mutableState.update { it.copy(preflight = draft.copy(verifiedAt = null, error = "线路核对结果已过期，请重试。")) }
        return
      }
      val currentId = activeTask()
      if (replacement == null && currentId != null) {
        mutableState.update { latest ->
          if (latest.preflight !== starting || latest.deleting != null || latest.waitChange != null) latest
          else latest.copy(preflight = draft,
            waitChange = WaitChange(WaitChangeAction.REPLACE, draft.stopCode, currentId, draft, withoutVoice))
        }
        return
      }
      if (currentId != null && currentId != replacement?.taskId) return
      val candidate = try {
        withContext(Dispatchers.IO) { store.find(draft.stopCode) }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        mutableState.update { it.copy(preflight = draft.copy(error = "本机关注项读取失败，未开始监测。")) }
        return
      }
      if (candidate == null || candidate.selectedLines != draft.lines) {
        mutableState.update { it.copy(preflight = draft.copy(verifiedAt = null, error = "关注项已变化，请返回关注项重新开始。")) }
        return
      }
      // W/B 保存到关注项；保存失败不结束旧任务。
      try {
        if (candidate.walkMinutes != walk || candidate.bufferMinutes != buffer) {
          withContext(Dispatchers.IO) { store.save(candidate.copy(walkMinutes = walk, bufferMinutes = buffer)) }
          val refreshed = withContext(Dispatchers.IO) { store.all() }
          mutableState.update { it.copy(items = refreshed) }
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        mutableState.update { it.copy(preflight = draft.copy(starting = false, error = "本机保存失败，未开始监测：${error.message ?: "请重试"}")) }
        return
      }
      if (state.value.preflight !== starting) return
      if (!isVerificationFresh(draft.verifiedAt)) {
        mutableState.update { if (it.preflight === starting) it.copy(preflight = draft.copy(verifiedAt = null,
          error = "线路核对结果已过期，请重试。")) else it }
        return
      }
      if (!stopForChange(currentId)) {
        mutableState.update { it.copy(preflight = draft.copy(error = "上一轮等车的输出尚未停止，未开始新一轮。请稍后重试。")) }
        return
      }
      if (!platform.notificationsAllowed() || !isVerificationFresh(draft.verifiedAt)) {
        mutableState.update { it.copy(preflight = draft.copy(verifiedAt = null, error = "开始条件已变化，请重新核对。")) }
        return
      }
      val endedBeforeStart = state.value.wait
      val now = clock()
      val task = WaitTask(
        id = taskIds.incrementAndGet(),
        stopCode = draft.stopCode,
        stationName = draft.stationName,
        startedAt = now,
        deadlineAt = now.plusDelay(MAX_WAIT),
        lastSuccessAt = null,
        walkMinutes = walk,
        bufferMinutes = buffer,
        linesJson = Json.encodeToString(draft.lines),
      )
      // 服务回调按身份收尾；在服务启动之前先落盘，再安装它要控制的会话。
      // 新任务未能启动时恢复上一轮的最少记录，而不是把两轮一起从磁盘删掉。
      val previousTask = try {
        withContext(Dispatchers.IO) {
          val endedId = endedBeforeStart.taskId
          val reason = endedBeforeStart.endedReason
          if (endedId != null && reason != null) tasks.finish(endedId, reason, endedBeforeStart.endedAt ?: now)
          tasks.load().also { tasks.save(task) }
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        mutableState.update { it.copy(preflight = draft.copy(starting = false, error = "本机任务状态保存失败，未开始监测")) }
        return
      }
      val running = WaitSession(
        source = source,
        platform = platform,
        task = task,
        clock = clock,
        sleeper = sleeper,
        elapsedClock = elapsedClock,
        submissionSleeper = submissionSleeper,
        requestDeadlineSleeper = requestDeadlineSleeper,
        scope = scope,
        onState = { next ->
          mutableState.update { state ->
            if (state.wait.taskId != task.id || (!state.wait.running && next.running)) state
            else state.copy(
              wait = next.copy(alerts = mergeAlerts(state.wait.alerts, next.alerts)),
              latestResult = next.endedReason?.let { WaitResult(task.id, task.stationName, it) } ?: state.latestResult,
            )
          }
          scope.launch(Dispatchers.IO) {
            runCatching {
              if (next.running) tasks.progress(task.id, next.phase ?: WaitPhase.WAITING_FIRST, next.lastSuccessAt)
              else tasks.finish(task.id, next.endedReason ?: WaitEndReason.INTERRUPTED, next.endedAt ?: clock())
            }
          }
        },
        recordAlert = { snapshot -> withContext(Dispatchers.IO) { tasks.recordAlert(task.id, snapshot) } },
        recordSubmissionReceipt = { tasks.recordSubmissionReceipt(task.id) },
        markAlertSubmitted = { withContext(Dispatchers.IO) { tasks.markAlertSubmitted(task.id) } },
        finishBeforeAlertDelivery = { reason, endedAt ->
          withContext(Dispatchers.IO) { tasks.finishBeforeAlertDelivery(task.id, reason, endedAt) }
        },
        voiceEnabled = voiceEnabled,
      )
      session = running
      mutableState.update { it.copy(wait = running.current().copy(alerts = it.wait.alerts)) }
      if (!platform.startForegroundService(task.id)) {
        session = null
        mutableState.update {
          it.copy(wait = endedBeforeStart.copy(alerts = it.wait.alerts), preflight = draft.copy(starting = false, error = "前台服务启动失败，未开始监测"))
        }
        try {
          withContext(Dispatchers.IO) {
            if (previousTask == null) tasks.clear(task.id)
            else tasks.save(previousTask)
          }
        } catch (error: Exception) {
          if (error is CancellationException) throw error
          mutableState.update { it.copy(message = "前台服务未启动，上一轮结果未能恢复到本机记录。") }
        }
        return
      }
      mutableState.update { it.copy(preflight = null, adding = false, confirmation = null, currentPage = true) }
      if (session === running) running.start()
    }
  }

  fun cancelWaitChange(change: WaitChange) {
    mutableState.update {
      if (it.waitChange !== change || it.waitChangeCommitting || it.saving || it.preflight?.starting == true || it.preflight?.verifying == true) it
      else it.copy(waitChange = null, preflight = if (change.action == WaitChangeAction.REPLACE) null else it.preflight)
    }
  }

  suspend fun confirmWaitChange(change: WaitChange) {
    while (true) {
      val current = state.value
      if (current.waitChange !== change || current.waitChangeCommitting || current.saving ||
        current.preflight?.starting == true || current.preflight?.verifying == true) return
      if (mutableState.compareAndSet(current, current.copy(waitChangeCommitting = true))) break
    }
    try {
      when (change.action) {
        WaitChangeAction.REPLACE -> state.value.preflight?.takeIf { it === change.draft }?.let {
          startWait(it, change.withoutVoice, change)
        }
        WaitChangeAction.EDIT -> edit(change.stopCode, change)
        WaitChangeAction.SAVE -> save(change)
        WaitChangeAction.REMOVE_RECORD -> removeStoredRecord(change.stopCode, requireNotNull(change.lineIdentity), change)
      }
    } finally {
      mutableState.update {
        it.copy(waitChange = it.waitChange?.takeUnless { pending -> pending === change }, waitChangeCommitting = false)
      }
    }
  }

  private suspend fun readWatchItem(stopCode: String): WatchItemEntity? = try {
    withContext(Dispatchers.IO) { store.find(stopCode) }
  } catch (error: Exception) {
    if (error is CancellationException) throw error
    mutableState.update { it.copy(message = "读取关注项失败，请重试。") }
    null
  }

  /** 在写入锁中重新匹配任务 ID；旧确认不是停止新任务的授权。 */
  private suspend fun allowItemChange(stopCode: String, action: WaitChangeAction, approval: WaitChange?,
    draft: WatchItemImportSession? = null, lineIdentity: String? = null): Boolean {
    val current = state.value
    if (current.saving || current.deleting != null || current.waitChange !== approval || (draft != null && current.confirmation !== draft)) return false
    val id = activeTask(stopCode)
    if (approval == null && id != null) {
      mutableState.update { it.copy(waitChange = WaitChange(action, stopCode, id, draft, lineIdentity = lineIdentity)) }
      return false
    }
    if (approval != null && (approval.draft !== draft || (id != null && id != approval.taskId))) return false
    if (approval != null && id == null && approval.taskId != 0L) return false
    if (id != null && !stopForChange(id)) {
      mutableState.update { it.copy(message = "上一轮的输出尚未停止，关注项未修改。") }
      return false
    }
    return approval == null || state.value.waitChange === approval
  }

  private suspend fun stopForChange(taskId: Long?): Boolean {
    if (taskId != null) stopWait(taskId)
    return kotlinx.coroutines.withTimeoutOrNull(1_000) {
      notificationHandoff.withLock { }
      platform.awaitPreviousOutput()
    } ?: false
  }

  /** 主动结束：立即停取数，退出前台服务，不发异常提示。 */
  suspend fun stopWait(taskId: Long? = null) {
    val running = session
    if (running == null || !running.current().running || (taskId != null && running.current().taskId != taskId)) return
    running.cancel()
    // 结束即撤下常驻通知：状态栏不能留一条点不动的"正在监测"。
    if (session === running) session = null
  }

  /**
   * 查看提醒正文：只打开查看，不确认、不停止输出、不改变服务状态。
   * 返回该提醒是否属于某个已知任务，供页面决定是否切到提醒视图。
   */
  fun viewAlert(taskId: Long): Boolean {
    val known = state.value.wait.alerts.any { it.snapshot.taskId == taskId }
    if (known) mutableState.update { it.copy(currentPage = true, adding = false, cameraActive = false) }
    return known
  }

  /**
   * 「知道了，停止提醒」：停止全部输出并标记确认。
   * 只认属于该提醒的那一轮；旧提醒的确认不会停止新任务或重置它的状态。
   */
  suspend fun confirmAlert(taskId: Long) = confirmAlert(taskId, fromNotification = false)

  internal suspend fun confirmAlert(taskId: Long, fromNotification: Boolean) = withContext(NonCancellable) {
    val live = session?.takeIf { it.current().taskId == taskId && it.current().running }
    val stopped = live?.confirm(taskId, fromNotification) == true
    if (!stopped && live?.current()?.running == true) return@withContext
    val posted = fromNotification || state.value.wait.alerts.any { it.snapshot.taskId == taskId && it.submissionPending }
    // 点旧通知或冷启动时，不依赖页面内存；事务用完整身份确认存在的快照。
    val confirmed = try {
      withContext(Dispatchers.IO) {
        val stillPosted = taskId in platform.postedAlertIds()
        val hasEvidence = posted || stillPosted
        val saved = runCatching {
          if (hasEvidence) {
            tasks.confirmPostedAlert(taskId)
          } else tasks.confirmAlert(taskId)
        }
        val confirmedByStore = saved.getOrDefault(false)
        if (!confirmedByStore && hasEvidence && !fromNotification) {
          val fallback = runCatching {
            tasks.markAlertSubmitted(taskId)
            tasks.confirmAlert(taskId)
          }
          if (fallback.getOrDefault(false)) platform.clearAlert(taskId)
          fallback.getOrThrow()
        } else {
          if (confirmedByStore) platform.clearAlert(taskId)
          else if (saved.isFailure && fromNotification && !stillPosted) runCatching { tasks.recordSubmissionReceipt(taskId) }
          saved.getOrThrow()
        }
      }
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      false
    }
    mutableState.update { state ->
      val wait = state.wait
      val alerts = wait.alerts.map {
        if (it.snapshot.taskId == taskId && !it.confirmed) {
          it.copy(confirmed = confirmed, outputEnded = true, confirmationError = !confirmed)
        } else it
      }
      val latest = state.latestResult?.let {
        if (confirmed && it.taskId == taskId) it.copy(reason = WaitEndReason.ALERT_CONFIRMED) else it
      }
      state.copy(latestResult = latest, wait = if (confirmed && wait.taskId == taskId && !wait.running && wait.endedReason == WaitEndReason.ALERTED) {
        wait.copy(alerts = alerts, endedReason = WaitEndReason.ALERT_CONFIRMED, message = WaitEndReason.ALERT_CONFIRMED.message)
      } else wait.copy(alerts = alerts))
    }
  }

  /** 从磁盘恢复时不能把仍在本进程输出的提醒改成已结束。 */
  private fun mergeAlerts(current: List<OutingAlert>, incoming: List<OutingAlert>): List<OutingAlert> =
    (incoming + current).distinctBy { it.snapshot.taskId }.sortedByDescending { it.snapshot.taskId }

  fun imageFailure(message: String) {
    mutableState.update { it.copy(message = message, adding = true) }
  }

  /**
   * 前台服务在进程内判定失败（例如系统拒绝前台提升）时结束同一轮等车。
   * 只结束仍在运行的那一轮；已结束的轮次不被旧回调改写。
   */
  suspend fun failWait(reason: WaitEndReason, taskId: Long? = null) {
    val running = session ?: return
    val wait = running.current()
    if (!wait.running || (taskId != null && wait.taskId != taskId)) return
    running.terminate(reason)
    if (session === running) session = null
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
    } finally {
      finishLoading(token)
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
    } finally {
      finishLoading(token)
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
    finally { finishLoading(token) }
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
    removeStoredRecord(original.stopCode, identity)
  }

  /** 列表里直接删除某条记录（通常是过时记录）；不需要联网，也不改变站台绑定。 */
  suspend fun removeStoredRecord(stopCode: String, identity: String) = removeStoredRecord(stopCode, identity, null)

  private suspend fun removeStoredRecord(stopCode: String, identity: String, approval: WaitChange?) {
    taskChanges.withLock {
      val item = readWatchItem(stopCode) ?: return
      val keep = item.selectedLines.filterNot { it.identity == identity }
      if (keep.size == item.selectedLines.size ||
        !allowItemChange(stopCode, WaitChangeAction.REMOVE_RECORD, approval, lineIdentity = identity)) return
      val updated = item.copy(
        selectedLinesJson = Json.encodeToString(keep),
        endStationName = keep.firstOrNull()?.endStationName ?: item.endStationName,
        lineNo = keep.firstOrNull()?.lineNo ?: item.lineNo,
        upDown = keep.firstOrNull()?.upDown ?: item.upDown,
        targetSequence = keep.firstOrNull()?.targetSequence ?: item.targetSequence,
      )
      try {
        withContext(NonCancellable) {
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
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        mutableState.update { it.copy(message = "删除失败，记录仍保留，请重试") }
      }
    }
  }

  /** 调整本地步行时间与预留余量：不需要联网，也不改变线路记录。 */
  suspend fun updateSettings(stopCode: String, walkMinutes: Double?, bufferMinutes: Double) {
    taskChanges.withLock {
      val item = readWatchItem(stopCode) ?: return
      val updated = item.copy(walkMinutes = walkMinutes, bufferMinutes = bufferMinutes)
      try {
        withContext(Dispatchers.IO) { store.save(updated) }
        mutableState.update { state ->
          state.copy(items = state.items.map { existing -> if (existing.stopCode == stopCode) updated else existing }, message = "本地设置已保存，仅影响下一次等车")
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        mutableState.update { it.copy(message = "本地设置保存失败，原设置未改变") }
      }
    }
  }

  fun requestDelete(stopCode: String) {
    if (state.value.saving) return
    val request = WatchItemDeletion(stopCode, activeTask(stopCode))
    mutableState.update {
      if (it.waitChange != null || it.deleting != null || it.preflight?.starting == true || it.saving) it
      else it.copy(deleting = request, message = null)
    }
  }

  fun cancelDelete(request: WatchItemDeletion? = state.value.deleting) {
    mutableState.update { if (!it.saving && it.deleting === request) it.copy(deleting = null) else it }
  }

  suspend fun confirmDelete(request: WatchItemDeletion? = state.value.deleting) {
    if (request == null) return
    taskChanges.withLock {
      val current = state.value
      if (current.deleting !== request || current.saving) return
      val stopCode = request.stopCode
      val id = activeTask(stopCode)
      if (id != null && id != request.taskId) {
        mutableState.update { it.copy(deleting = null, message = "当前等车已变化，请重新确认删除。") }
        return
      }
      if (!mutableState.compareAndSet(current, current.copy(saving = true))) return
      try {
        if (id != null && !stopForChange(id)) {
          mutableState.update { it.copy(message = "上一轮的输出尚未停止，关注项未删除。") }
          return
        }
        withContext(NonCancellable) {
          withContext(Dispatchers.IO) { store.delete(stopCode) }
          revision.incrementAndGet()
          mutableState.update {
            it.copy(items = it.items.filterNot { item -> item.stopCode == stopCode }, confirmation = null, adding = false,
              preflight = it.preflight?.takeUnless { draft -> draft.stopCode == stopCode }, message = "关注项已删除")
          }
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        mutableState.update { it.copy(message = "删除失败，关注项仍保留，请重试") }
      } finally {
        mutableState.update { it.copy(deleting = null, saving = false) }
      }
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
    for (item in items) {
      val snapshot = try {
        source.resolveLines(item.stationName, item.stopCode)
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        failed++
        continue
      }
      val events = try {
        taskChanges.withLock {
          val saved = withContext(Dispatchers.IO) { store.find(item.stopCode) } ?: return@withLock emptyList()
          if (saved.lastCheckedAt?.let { it > snapshot.fetchedAt } == true) return@withLock emptyList()
          val (checked, events) = reconcile(saved, snapshot.value, snapshot.fetchedAt)
          withContext(Dispatchers.IO) { store.save(checked) }
          mutableState.update { state -> state.copy(items = state.items.map { if (it.stopCode == checked.stopCode) checked else it }) }
          events
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        failed++
        continue
      }
      // 通知是旁路：未授权或发送失败都不能影响已核对的数据。
      events.forEach { event -> runCatching { notifier.notify(event) } }
    }
    mutableState.update {
      it.copy(
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

  suspend fun save() = save(null)

  private suspend fun save(approval: WaitChange?) {
    taskChanges.withLock {
      val current = state.value
      val draft = current.confirmation ?: return
      if (current.saving || current.loading) return
      val selected = draft.availableLines.filter { it.identity in draft.selected }
      if (selected.isEmpty()) {
        mutableState.update { it.copy(message = "至少选择一条有效线路才能保存") }
        return
      }
      if (!allowItemChange(draft.stopCode, WaitChangeAction.SAVE, approval, draft)) return
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
        // Room 写入和进程级 UI 状态必须一起完成，即使发起保存的 Activity 已销毁。
        withContext(NonCancellable) {
          withContext(Dispatchers.IO) { store.save(item) }
          revision.incrementAndGet()
          mutableState.update {
            it.copy(items = (it.items.filterNot { saved -> saved.stopCode == item.stopCode } + item).sortedBy { saved -> saved.stationName }, saving = false, adding = false, editing = false, confirmation = null, message = "关注项已保存，没有开始等车")
          }
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        mutableState.update { it.copy(saving = false, message = "保存失败，选线草稿仍保留，请重试；已有关注项未改变") }
      } finally {
        mutableState.update { it.copy(saving = false) }
      }
    }
  }

  private fun handleFailure(token: Long, error: Exception) {
    if (error is CancellationException) throw error
    mutableState.update { current ->
      if (token != revision.get()) current
      else current.copy(message = "站台或线路获取失败：${error.message ?: "请稍后重试"}；已有关注项未改变")
    }
  }

  /** Activity 取消请求时也恢复按钮；旧请求不能清除新草稿的加载状态。 */
  private fun finishLoading(token: Long) {
    mutableState.update { if (token == revision.get()) it.copy(loading = false) else it }
  }

}

enum class WaitChangeAction { REPLACE, EDIT, SAVE, REMOVE_RECORD }

/** 每次对话框都有独立身份，迟到的确认不能复用至另一轮。 */
class WaitChange internal constructor(
  val action: WaitChangeAction,
  val stopCode: String,
  val taskId: Long,
  internal val draft: Any? = null,
  internal val withoutVoice: Boolean = false,
  internal val lineIdentity: String? = null,
)

/** 删除确认也绑定打开时的任务身份（包括当时没有运行任务）。 */
class WatchItemDeletion internal constructor(val stopCode: String, val taskId: Long?)

data class WatchItemsState(
  val items: List<WatchItemEntity> = emptyList(),
  val adding: Boolean = false,
  val editing: Boolean = false,
  val currentPage: Boolean = false,
  val codes: List<String> = emptyList(),
  val confirmation: WatchItemImportSession? = null,
  val loading: Boolean = false,
  val saving: Boolean = false,
  val deleting: WatchItemDeletion? = null,
  val waitChange: WaitChange? = null,
  val waitChangeCommitting: Boolean = false,
  val message: String? = null,
  val cameraActive: Boolean = false,
  val preflight: WaitPreflight? = null,
  internal val notificationPermissionDraft: WaitPreflight? = null,
  val wait: WaitState = WaitState(),
  val latestResult: WaitResult? = null,
  val voiceEnabled: Boolean = true,
)

/** 数据替换仅在统一应用边界进行；生产实现始终访问同一 Room 数据库。 */
interface WatchItemStore {
  suspend fun find(stopCode: String): WatchItemEntity?
  suspend fun save(item: WatchItemEntity)
  suspend fun delete(stopCode: String)
  suspend fun all(): List<WatchItemEntity>
  suspend fun voiceEnabled(): Boolean = true
  suspend fun setVoiceEnabled(enabled: Boolean) = Unit
}
