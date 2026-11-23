package com.ilksese.busalarmclock.wait

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** 每次只使用一个本机语音实例，取消时不会停止另一轮的播报。 */
internal class ChineseSpeech(context: Context) {
  private val initialized = CompletableDeferred<Int>()
  private val terminal = CompletableDeferred<Result>()
  private val engine = TextToSpeech(context) { initialized.complete(it) }

  suspend fun prepare(timeout: Long): Boolean = withTimeoutOrNull(timeout) {
    if (initialized.await() != TextToSpeech.SUCCESS) return@withTimeoutOrNull false
    val voice = engine.voices.orEmpty().firstOrNull {
      it.locale.language == Locale.CHINESE.language && !it.isNetworkConnectionRequired &&
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
    } ?: return@withTimeoutOrNull false
    engine.setVoice(voice) == TextToSpeech.SUCCESS
  } ?: false

  enum class Result { SPOKEN, FAILED, UNKNOWN }

  suspend fun speak(text: String, attributes: AudioAttributes, timeout: Long, ready: () -> Boolean): Result {
    var submitted = false
    return withTimeoutOrNull(timeout) {
      val id = "outing"
      if (engine.setAudioAttributes(attributes) != TextToSpeech.SUCCESS) return@withTimeoutOrNull Result.FAILED
      if (engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) { if (utteranceId == id) terminal.complete(Result.SPOKEN) }
        @Deprecated("Android 兼容回调")
        override fun onError(utteranceId: String?) { if (utteranceId == id) terminal.complete(Result.FAILED) }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id) terminal.complete(Result.FAILED) }
      }) != TextToSpeech.SUCCESS || !ready()) return@withTimeoutOrNull Result.FAILED
      submitted = true
      if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) Result.FAILED
      else terminal.await()
    } ?: if (submitted) Result.UNKNOWN else Result.FAILED
  }

  suspend fun stopAndAwait(): Boolean {
    if (!terminal.isCompleted && engine.stop() != TextToSpeech.SUCCESS) return false
    terminal.await()
    return true
  }

  fun shutdown() = engine.shutdown()
}
