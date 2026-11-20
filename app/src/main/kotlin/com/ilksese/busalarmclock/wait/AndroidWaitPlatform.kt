package com.ilksese.busalarmclock.wait

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ilksese.busalarmclock.MainActivity
import com.ilksese.busalarmclock.R
import com.ilksese.busalarmclock.data.AlertSnapshot
import com.ilksese.busalarmclock.data.ReminderPreview
import com.ilksese.busalarmclock.data.WaitEndReason
import com.ilksese.busalarmclock.data.WaitPlatform
import com.ilksese.busalarmclock.data.WaitState
import com.ilksese.busalarmclock.data.formatMinutes
import com.ilksese.busalarmclock.data.formatAlertEta
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 应用级通知开关与出门提醒类别：任一关闭都不能保证提醒。 */
private const val PREFS = "bus_alarm_clock"
private const val KEY_GATE_OFF = "notificationGateOff"

/** Android 通知与提醒输出适配器；业务会话决定何时开始和停止。 */
class AndroidWaitPlatform(private val context: Context) : WaitPlatform {
  private var wakeLock: PowerManager.WakeLock? = null
  private val postingLock = Any()
  @Volatile private var slotOwner: Long? = null
  private val output = AtomicReference<AlertOutput?>(null)
  private val vibrationLock = Any()
  private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Only local handles are synchronized: Android service calls must not hold the cleanup gate. */
  private class AlertOutput(val owner: Long) {
    val player = AtomicReference<MediaPlayer?>(null)
    val speech = AtomicReference<ChineseSpeech?>(null)
    val chimeEnded = CompletableDeferred<Unit>()
    val toneOwner = AtomicBoolean(true)
    @Volatile var vibrator: Vibrator? = null
    @Volatile var vibrationUntil: Long = 0
    val focus = AtomicReference<AudioFocusRequest?>(null)
    @Volatile var listener: AudioManager.OnModeChangedListener? = null
  }
  private val audio get() = context.getSystemService(AudioManager::class.java)
  private val channel: WaitChannel by lazy { SystemWaitChannel(context) }

  override fun notificationsAllowed(): Boolean {
    val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val gateOff = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GATE_OFF, false)
    val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!enabled || !permission || gateOff) return false
    // 从系统授权页首次返回时渠道还不存在；创建后才读取用户对该渠道的实际设置。
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return runCatching {
      if (manager.getNotificationChannel(WaitNotifications.LEGACY_ALERT_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
      WaitNotifications.ensureChannel(channel)
      manager.getNotificationChannel(WaitNotifications.ALERT_CHANNEL_ID)
        ?.let { it.importance != NotificationManager.IMPORTANCE_NONE } == true
    }.getOrDefault(false)
  }

  override fun present(state: WaitState) {
    if (!notificationsAllowed()) return
    val text = state.phase?.label ?: "等待本次新预测"
    val detail = buildString {
      append(state.stationName.orEmpty())
      val walk = state.walkMinutes
      val buffer = state.bufferMinutes
      if (walk != null && buffer != null) {
        append(" · 预计进入 ")
        append(formatMinutes(walk))
        append("–")
        append(formatMinutes(walk + buffer))
        append(" 分钟时提醒你")
      }
    }
    val stop = state.taskId?.let { WaitNotifications.stopAction(context, it) }
    val open = PendingIntent.getActivity(
      context,
      1,
      Intent(context, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification: Notification = WaitNotifications.builder(context)
      .setContentTitle(if (state.running) "正在监测 ${text}" else (state.endedReason?.message ?: text))
      .setContentText(detail)
      .setOngoing(state.running)
      .setContentIntent(open)
      .apply { if (state.running && stop != null) addAction(0, "结束等车", stop) }
      .build()
    runCatching { NotificationManagerCompat.from(context).notify(WaitNotifications.NOTIFICATION_ID, notification) }
  }

  /** 非主动结束才提示，且只一次短提示音与短震动。 */
  override fun announceEnd(reason: WaitEndReason) {
    if (reason == WaitEndReason.CANCELLED || ringerMode() != AudioManager.RINGER_MODE_NORMAL || !outsideDnd()) return
    val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    runCatching {
      RingtoneManager.getRingtone(context, sound)?.play()
    }
    runCatching {
      val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
      } else {
        @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
      }
      vibrator?.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
    }
  }

  override fun dismiss() {
    WaitService.clearNotification(context)
  }

  override fun submitAlert(alert: AlertSnapshot, remainingMillis: () -> Long): Boolean {
    if (!notificationsAllowed()) return false
    // 同步提交并不保证 SystemUI 展示；任何系统通知本身均静默。
    return runCatching {
      // 一个迟到的 Binder 调用不能在本进程的新任务通知之后覆盖槽位。
      synchronized(postingLock) { postAlert(alert, remainingMillis = remainingMillis) }
    }.getOrDefault(false)
  }

  override suspend fun canStartAlertOutput(taskId: Long, remainingMillis: () -> Long): Boolean {
    // activeNotifications 也可能含排队项。给系统处理时间后连续检查同一个任务，缺条时只保留快照。
    // 即便通过，也不能视为用户可见回执。
    repeat(2) {
      if (remainingMillis() <= 125L) return false
      delay(100)
      if (!notificationsAllowed() || !slotContains(taskId)) return false
    }
    return remainingMillis() > 25L
  }

  override fun postedAlertIds(): Set<Long> = runCatching {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return emptySet()
    manager.activeNotifications.asSequence()
      .filter { it.tag == null && it.id == WaitNotifications.ALERT_NOTIFICATION_ID && it.notification.channelId == WaitNotifications.ALERT_CHANNEL_ID }
      .map { it.notification.extras.getLong(EXTRA_ALERT_OWNER, Long.MIN_VALUE) }
      .filter { it != Long.MIN_VALUE }
      .toSet()
  }.getOrDefault(emptySet())

  private fun slotContains(taskId: Long): Boolean = taskId in postedAlertIds()

  override fun clearAlert(taskId: Long) { withdrawAlert(taskId) }

  override fun withdrawAlert(taskId: Long): Boolean = runCatching {
    val manager = NotificationManagerCompat.from(context)
    manager.cancel(WaitNotifications.alertTag(taskId), WaitNotifications.ALERT_NOTIFICATION_ID)
    synchronized(postingLock) {
      // 同一进程以最新提交者为准；进程重开才从系统恢复槽位身份。
      if (slotOwner == taskId || (slotOwner == null && slotContains(taskId))) {
        manager.cancel(WaitNotifications.ALERT_NOTIFICATION_ID)
        slotOwner = null
      }
    }
  }.isSuccess

  private fun action(action: String, taskId: Long): PendingIntent = PendingIntent.getBroadcast(
    context, 0, WaitNotifications.taskIntent(context, WaitNotificationReceiver::class.java, action, taskId),
    PendingIntent.FLAG_IMMUTABLE,
  )

  internal fun postAlert(alert: AlertSnapshot, remainingMillis: () -> Long = { Long.MAX_VALUE }): Boolean {
    WaitNotifications.ensureChannel(channel)
    val open = PendingIntent.getActivity(
      context, 0, WaitNotifications.taskIntent(context, MainActivity::class.java, WaitNotifications.OPEN_ALERT, alert.taskId)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val list = PendingIntent.getActivity(
      context, 0, Intent(context, MainActivity::class.java).setAction(WaitNotifications.OPEN_ALERT_LIST)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val fetched = DateFormat.getDateTimeInstance().format(Date(alert.fetchedAt))
    val text = "${alert.stationName} · 站台 ${alert.stopCode} · 触发时预计 ${formatAlertEta(alert.main.etaSeconds)} · 获取时间 $fetched"
    val others = alert.others.joinToString("、") { "${it.lineName} 约 ${formatAlertEta(it.etaSeconds)}" }
    val details = buildString {
      append(text)
      if (others.isNotEmpty()) append("\n同在窗口：$others")
      append("\n更早的未确认提醒可在应用的当前等车页查看。")
    }
    val notification = WaitNotifications.alertBuilder(context)
      .addExtras(android.os.Bundle().apply { putLong(EXTRA_ALERT_OWNER, alert.taskId) })
      .setContentTitle("${alert.main.lineName} 开往${alert.main.endStationName} 该出门了")
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(details))
      .setContentIntent(open)
      .setDeleteIntent(action(WaitNotifications.DISMISS_ALERT, alert.taskId))
      .addAction(0, "知道了，停止提醒", action(WaitNotifications.CONFIRM_ALERT, alert.taskId))
      .addAction(0, "查看未确认提醒", list)
      .build()
    if (remainingMillis() <= 0) return false
    val manager = NotificationManagerCompat.from(context)
    // 只撤旧版通知的显示项，数据库快照与旧动作的任务身份都保留。
    runCatching {
      context.getSystemService(NotificationManager::class.java)?.activeNotifications?.forEach {
        if (it.id == WaitNotifications.ALERT_NOTIFICATION_ID && it.tag?.startsWith("outing-alert:") == true) manager.cancel(it.tag, it.id)
      }
    }
    if (remainingMillis() <= 0 || !notificationsAllowed()) return false
    slotOwner = alert.taskId
    manager.notify(WaitNotifications.ALERT_NOTIFICATION_ID, notification)
    // Binder 已接受提交；是否迟到由会话判定，并在撤回成功前保留快照。
    return true
  }

  private fun ringerMode(): Int? = audio?.ringerMode

  private fun outsideDnd(): Boolean = context.getSystemService(NotificationManager::class.java)
    ?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL

  override suspend fun chineseVoiceAvailable(): Boolean = withContext(Dispatchers.IO) {
    checkVoice {
      val speech = ChineseSpeech(context)
      try { speech.prepare(2_000) } finally { releaseSpeech(speech, stopFirst = false) }
    }
  }

  override suspend fun startAlertOutput(
    alert: AlertSnapshot,
    voiceEnabled: Boolean,
    remainingMillis: () -> Long,
    onInterrupted: () -> Unit,
  ): Long {
    val first = startAlertOutput(alert.taskId, remainingMillis, onInterrupted)
    if (first == 0L) return 0
    val carrier = output.get()?.takeIf { it.owner == alert.taskId && it.player.get() != null } ?: return first
    val manager = audio ?: return first
    fun ready() = output.get() === carrier && carrier.toneOwner.get() && remainingMillis() > 25L &&
      audioAllowed(manager) && notificationsAllowed() && slotContains(alert.taskId) && channelAllowsSound()

    // 在音频焦点丢失时，独立等已发出的短震自然结束，不重新启动任何声音。
    return try {
      if (!awaitWhileAllowed(1_000, ::ready) { carrier.chimeEnded.await() }) return 0
      if (voiceEnabled && ready()) {
        val text = "${alert.main.lineName}，开往${alert.main.endStationName}，${alert.stationName}，站台编号${alert.stopCode.toList().joinToString(" ")}，预计${formatAlertEta(alert.main.etaSeconds)}到站，该出门了。"
        val speech = speakChinese(carrier.speech, text, remainingMillis, ::ready)
        if (!speech.spoken && ready()) Log.w("BusAlarmClock", "本次中文语音未能播报")
        if (!speech.safeToContinue) return 0
      }
      if (!ready()) return 0
      val ring = createRingtone() ?: return 0
      val chime = carrier.player.get()
      if (chime == null || !carrier.player.compareAndSet(chime, ring)) {
        ring.release()
        return 0
      }
      releasePlayer(chime)
      ring.isLooping = true
      if (!ready()) return 0
      ring.start()
      while (ready()) delay(minOf(50L, remainingMillis()).coerceAtLeast(1))
      0
    } finally {
      val vibrationLeft = carrier.vibrationUntil - SystemClock.elapsedRealtime()
      if (vibrationLeft > 0) delay(minOf(vibrationLeft, remainingMillis()))
    }
  }

  private fun createRingtone(): MediaPlayer? {
    val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) ?: return null
    val player = MediaPlayer()
    return try {
      player.setAudioAttributes(alertAudioAttributes)
      player.setDataSource(context, uri)
      player.prepare()
      player
    } catch (_: Exception) {
      player.release()
      null
    }
  }

  private fun releasePlayer(player: MediaPlayer) { cleanupScope.launch { runCatching { player.release() } } }

  private suspend fun awaitWhileAllowed(timeout: Long, allowed: () -> Boolean, await: suspend () -> Unit): Boolean =
    coroutineScope {
      val completion = async { await() }
      val completed = withTimeoutOrNull(timeout) {
        while (!completion.isCompleted && allowed()) delay(25)
        completion.isCompleted && allowed()
      } ?: false
      completion.cancel()
      completed
    }

  private data class SpeechOutcome(val spoken: Boolean, val safeToContinue: Boolean)

  private suspend fun speakChinese(
    holder: AtomicReference<ChineseSpeech?>,
    text: String,
    remainingMillis: () -> Long,
    allowed: () -> Boolean,
  ): SpeechOutcome {
    if (!allowed()) return SpeechOutcome(false, false)
    val speech = try { ChineseSpeech(context) } catch (_: Exception) { return SpeechOutcome(false, true) }
    holder.set(speech)
    var result = ChineseSpeech.Result.FAILED
    var cleanup: kotlinx.coroutines.Deferred<Boolean>? = null
    try {
      // 探测失败没有发起播报；已进入 speak 却没有终态时，必须按可能仍在发声处理。
      if (allowed() && checkVoice { speech.prepare(minOf(2_000, remainingMillis())) }) {
        result = ChineseSpeech.Result.UNKNOWN
        val reported = AtomicReference(ChineseSpeech.Result.UNKNOWN)
        val ended = awaitWhileAllowed(minOf(10_000, remainingMillis()), allowed) {
          reported.set(speech.speak(text, alertAudioAttributes, minOf(10_000, remainingMillis()), allowed))
        }
        if (ended) result = reported.get()
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      // 语音调用异常不等于引擎已停止，沿同一条保守收尾路径处理。
    } finally {
      if (holder.compareAndSet(speech, null)) {
        cleanup = releaseSpeech(speech, stopFirst = result == ChineseSpeech.Result.UNKNOWN)
      }
    }
    val safe = if (result != ChineseSpeech.Result.UNKNOWN) true else {
      withTimeoutOrNull(minOf(250L, remainingMillis())) { cleanup?.await() } == true
    }
    return SpeechOutcome(result == ChineseSpeech.Result.SPOKEN, safe)
  }

  /** 仅等待停止结果，shutdown 慢时不能挡住已获准的续铃。 */
  private fun releaseSpeech(speech: ChineseSpeech, stopFirst: Boolean): kotlinx.coroutines.Deferred<Boolean> {
    val stopped = CompletableDeferred<Boolean>()
    cleanupScope.launch {
      try {
        stopped.complete(!stopFirst || withTimeoutOrNull(250) { speech.stopAndAwait() } == true)
      } catch (_: Exception) {
        stopped.complete(false)
      } finally {
        runCatching { speech.shutdown() }
      }
    }
    return stopped
  }

  override suspend fun previewReminder(voiceEnabled: Boolean): ReminderPreview = withContext(Dispatchers.IO) {
    val manager = audio ?: return@withContext ReminderPreview()
    val notifications = context.getSystemService(NotificationManager::class.java) ?: return@withContext ReminderPreview()
    fun settings() = notifications.getNotificationChannel(WaitNotifications.ALERT_CHANNEL_ID)
    fun ready() = outsideDnd() && output.get() == null && settings()?.importance != NotificationManager.IMPORTANCE_NONE
    fun canVibrate() = ready() && manager.ringerMode != AudioManager.RINGER_MODE_SILENT && settings()?.shouldVibrate() != false
    if (!ready()) return@withContext ReminderPreview()
    coroutineScope {
      val vibration = async {
        if (!canVibrate()) return@async false
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return@async false
        synchronized(vibrationLock) {
          if (!canVibrate() || !vibrator.hasVibrator()) return@async false
          vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
        }
        try {
          awaitWhileAllowed(250, ::canVibrate) { delay(200) }
          true
        } finally {
          synchronized(vibrationLock) { if (output.get()?.vibrator == null) vibrator.cancel() }
        }
      }
      val interrupted = AtomicBoolean(false)
      fun canPlay(): Boolean {
        val channel = settings()
        return !interrupted.get() && ready() && audioAllowed(manager) && (channel == null || channel.sound != null)
      }
      var result = ReminderPreview()
      if (canPlay()) {
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
          .setAudioAttributes(alertAudioAttributes)
          .setWillPauseWhenDucked(true)
          .setOnAudioFocusChangeListener { change -> if (change < 0) interrupted.set(true) }
          .build()
        val listener = AudioManager.OnModeChangedListener { mode ->
          if (mode != AudioManager.MODE_NORMAL) interrupted.set(true)
        }
        if (manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
          try {
            manager.addOnModeChangedListener(ContextCompat.getMainExecutor(context), listener)
            val player = MediaPlayer.create(context, R.raw.outing_chime, alertAudioAttributes, AudioManager.AUDIO_SESSION_ID_GENERATE)
            var chimeEnded = false
            if (player != null) try {
              val ended = CompletableDeferred<Unit>()
              player.setOnCompletionListener { ended.complete(Unit) }
              if (canPlay()) {
                player.start()
                result = result.copy(chime = true)
                chimeEnded = awaitWhileAllowed(1_000, ::canPlay) { ended.await() }
              }
            } finally { releasePlayer(player) }
            if (chimeEnded && canPlay()) {
              val deadline = SystemClock.elapsedRealtime() + 5_000L
              val voice = if (voiceEnabled) speakChinese(AtomicReference(null), "这是一条出门提醒试听。",
                { (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L) }, ::canPlay)
              else SpeechOutcome(false, true)
              result = result.copy(voice = voice.spoken)
              if (voice.safeToContinue && canPlay()) createRingtone()?.let { ring ->
                try {
                  if (canPlay()) {
                    ring.isLooping = true
                    ring.start()
                    result = result.copy(ringtone = true)
                    awaitWhileAllowed(1_000, ::canPlay) { delay(750) }
                  }
                } finally { releasePlayer(ring) }
              }
            }
            result = result.copy(interrupted = !canPlay())
          } finally {
            manager.removeOnModeChangedListener(listener)
            manager.abandonAudioFocusRequest(focus)
          }
        }
      }
      result.copy(vibration = vibration.await())
    }
  }

  private suspend fun checkVoice(block: suspend () -> Boolean): Boolean = try {
    block()
  } catch (error: CancellationException) {
    throw error
  } catch (_: Exception) {
    false
  }

  private val alertAudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()

  private fun audioAllowed(manager: AudioManager): Boolean = outsideDnd() &&
    manager.mode == AudioManager.MODE_NORMAL && manager.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
    manager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0

  private fun channelAllowsSound() = context.getSystemService(NotificationManager::class.java)
    ?.getNotificationChannel(WaitNotifications.ALERT_CHANNEL_ID)?.sound != null

  override fun startAlertOutput(taskId: Long, remainingMillis: () -> Long, onInterrupted: () -> Unit): Long {
    if (!notificationsAllowed() || !slotContains(taskId) || !outsideDnd() || remainingMillis() <= 0L) return 0
    val manager = audio ?: return 0
    if (manager.mode != AudioManager.MODE_NORMAL) return 0
    val settings = context.getSystemService(NotificationManager::class.java)
      ?.getNotificationChannel(WaitNotifications.ALERT_CHANNEL_ID) ?: return 0
    val carrier = AlertOutput(taskId)
    if (!output.compareAndSet(null, carrier)) return 0
    fun active() = output.get() === carrier && remainingMillis() > 0
    if (!active()) { stopAlertOutput(taskId); return 0 }
    val listener = AudioManager.OnModeChangedListener { mode ->
      if (mode != AudioManager.MODE_NORMAL) stopSound(carrier)
    }
    manager.addOnModeChangedListener(ContextCompat.getMainExecutor(context), listener)
    carrier.listener = listener
    if (!active()) {
      manager.removeOnModeChangedListener(listener)
      stopAlertOutput(taskId)
      return 0
    }
    val toneDuration = if (settings.sound != null && ringerMode() == AudioManager.RINGER_MODE_NORMAL) {
      runCatching { playAlertTone(carrier, manager, remainingMillis, onInterrupted) }.getOrDefault(0L)
    } else 0L
    if (toneDuration == 0L) stopSound(carrier)
    val vibrationDuration = if (active() && settings.shouldVibrate() && outsideDnd() &&
      (ringerMode() == AudioManager.RINGER_MODE_NORMAL || ringerMode() == AudioManager.RINGER_MODE_VIBRATE)) {
      runCatching { vibrateAlert(carrier, remainingMillis) }.getOrDefault(0L)
    } else 0L
    return maxOf(toneDuration, vibrationDuration)
  }

  private fun playAlertTone(carrier: AlertOutput, manager: AudioManager, remainingMillis: () -> Long, onInterrupted: () -> Unit): Long {
    if (manager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0) return 0
    val owner = carrier.owner
    val service = WaitRuntime.service?.takeIf { it.owns(owner) } ?: return 0
    val attributes = alertAudioAttributes
    // 使用播放器回调衔接语音；短音自身不循环。
    val player = MediaPlayer.create(context, R.raw.outing_chime, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE) ?: return 0
    carrier.player.set(player)
    player.setOnCompletionListener { carrier.chimeEnded.complete(Unit) }
    val duration = player.duration.toLong()
    if (duration !in 1L..250L) return 0
    fun ready() = output.get() === carrier && carrier.toneOwner.get() && remainingMillis() >= duration + 25L && audioAllowed(manager)
    if (!ready() || manager.mode != AudioManager.MODE_NORMAL || !notificationsAllowed() || !slotContains(owner)) return 0
    val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
      .setAudioAttributes(attributes)
      .setWillPauseWhenDucked(true)
      .setOnAudioFocusChangeListener { change ->
        if (change < 0) stopSound(carrier)
      }.build()
    if (!ready() || manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return 0
    carrier.focus.set(focus)
    if (manager.mode != AudioManager.MODE_NORMAL || !notificationsAllowed() || !slotContains(owner) || !ready()) return 0
    // player 已准备完毕且焦点已获准；提升在 IO 上，独立看门狗不等它返回。
    if (!service.promoteForPlayback(owner, ::ready)) {
      if (ready()) onInterrupted()
      return 0
    }
    if (manager.mode != AudioManager.MODE_NORMAL || !notificationsAllowed() || !slotContains(owner) || !ready()) return 0
    val endsAt = SystemClock.elapsedRealtime() + duration
    player.start()
    return (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
  }

  private fun stopSound(carrier: AlertOutput) {
    carrier.toneOwner.set(false)
    carrier.chimeEnded.complete(Unit)
    carrier.speech.getAndSet(null)?.let { releaseSpeech(it, stopFirst = true) }
    carrier.player.getAndSet(null)?.let(::releasePlayer)
    carrier.focus.getAndSet(null)?.let { focus ->
      cleanupScope.launch { runCatching { audio?.abandonAudioFocusRequest(focus) } }
    }
  }

  private fun vibrateAlert(carrier: AlertOutput, remainingMillis: () -> Long): Long {
    val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return 0
    if (!vibrator.hasVibrator() || !notificationsAllowed() || !slotContains(carrier.owner)) return 0
    return synchronized(vibrationLock) {
      val duration = minOf(200L, remainingMillis() - 25L)
      if (duration <= 0 || output.get() !== carrier) return@synchronized 0L
      carrier.vibrator = vibrator
      carrier.vibrationUntil = SystemClock.elapsedRealtime() + duration
      vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE),
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
      duration
    }
  }

  override fun stopAlertOutput(taskId: Long) {
    val carrier = output.get()?.takeIf { it.owner == taskId } ?: return
    if (!output.compareAndSet(carrier, null)) return
    // 分别释放音频和震动；语音引擎的 IPC 不能拖住其它资源收尾。
    stopSound(carrier)
    carrier.listener?.let { listener -> cleanupScope.launch { runCatching { audio?.removeOnModeChangedListener(listener) } } }
    cleanupScope.launch {
      synchronized(vibrationLock) {
        // 取消是进程级操作；旧任务不能取消新一轮已发出的震动。
        if (output.get()?.vibrator == null) runCatching { carrier.vibrator?.cancel() }
      }
    }
  }

  override fun acquireWakeLock(timeoutMillis: Long) {
    releaseWakeLock()
    val manager = context.getSystemService(PowerManager::class.java) ?: return
    // 有界唤醒锁：最长不超过本次剩余监测时间，只保证 CPU，不点亮屏幕。
    val lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bus-alarm-clock:waiting")
    lock.setReferenceCounted(false)
    runCatching { lock.acquire(timeoutMillis.coerceIn(1, 30 * 60_000L)) }
    wakeLock = lock
  }

  override fun releaseWakeLock() {
    wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
    wakeLock = null
  }

  override suspend fun startForegroundService(taskId: Long): Boolean {
    if (!WaitRuntime.requestStart(taskId)) return false
    return runCatching { ContextCompat.startForegroundService(context, WaitService.start(context, taskId)) }.isSuccess
  }

  override fun stopForegroundService(taskId: Long) {
    runCatching { WaitRuntime.stopService(taskId) }
  }

}

private const val EXTRA_ALERT_OWNER = "com.ilksese.busalarmclock.extra.ALERT_OWNER"
