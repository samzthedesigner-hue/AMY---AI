package com.amy.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * AmyTTS - Text-to-speech wrapper using Android's built-in TextToSpeech engine.
 * No cloud dependency required; works fully offline once the OS TTS voice is installed.
 */
object AmyTTS {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var onReadyCallback: (() -> Unit)? = null

    fun init(context: Context, onReady: (() -> Unit)? = null) {
        onReadyCallback = onReady
        if (tts != null) {
            onReady?.invoke()
            return
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isReady = true
                AmyLogger.i("AmyTTS", "TTS engine ready")
                onReadyCallback?.invoke()
            } else {
                AmyLogger.e("AmyTTS", "TTS init failed with status $status")
            }
        }
    }

    fun speak(text: String, utteranceId: String = "amy_utterance") {
        if (!isReady) {
            AmyLogger.w("AmyTTS", "speak() called before TTS ready, queuing anyway")
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    suspend fun speakAndWait(text: String): Boolean = suspendCoroutine { continuation ->
        val utteranceId = "amy_wait_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                continuation.resume(true)
            }
            @Deprecated("Deprecated in API but required for older SDKs")
            override fun onError(utteranceId: String?) {
                continuation.resume(false)
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking ?: false
}
