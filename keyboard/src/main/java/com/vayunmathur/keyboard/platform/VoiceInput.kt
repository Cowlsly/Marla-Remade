package com.vayunmathur.keyboard.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

private const val TAG = "VoiceInput"

/**
 * Why a dictation session ended without text. The recognizer's own error codes are not
 * user-facing; [com.vayunmathur.keyboard.ui.VoiceStrip] turns these into a message.
 */
enum class VoiceFailure {
    /** No recognition service installed at all — the normal state on a de-Googled ROM. */
    UNAVAILABLE,
    PERMISSION_DENIED,

    /** Denied with "don't ask again", so only the system settings screen can undo it. */
    PERMISSION_BLOCKED,
    NETWORK,
    NO_SPEECH,
    BUSY,
    AUDIO,
    OTHER,
}

/**
 * Dictation driven straight from the IME with [SpeechRecognizer].
 *
 * An `InputMethodService` is not an Activity, so `ACTION_RECOGNIZE_SPEECH` — which hands its
 * transcript back as an activity result — is not reachable from here. [SpeechRecognizer] is
 * the same engine without that requirement: it binds the recognition service directly and
 * delivers the transcript to a listener. It also streams partial results, which is what lets
 * the strip show words while they are still being spoken.
 *
 * Main thread only, like the framework class it wraps.
 */
class VoiceInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Listen for one utterance in [languageTag], or in whatever the recognizer picks when it
     * is blank. Exactly one of [onFinal] and [onFailure] ends the session, so the caller can
     * always reset on either.
     */
    fun start(
        languageTag: String,
        onPartial: (String) -> Unit,
        onTranscribing: () -> Unit,
        onFinal: (String) -> Unit,
        onFailure: (VoiceFailure) -> Unit,
    ) {
        if (!isAvailable()) {
            onFailure(VoiceFailure.UNAVAILABLE)
            return
        }
        // One recognizer, cancelled rather than destroyed between sessions: rebinding the
        // service before the previous session has cleared reports ERROR_RECOGNIZER_BUSY.
        val sr = recognizer
            ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        runCatching { sr.cancel() }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            if (languageTag.isNotEmpty()) {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    Locale.forLanguageTag(languageTag).toLanguageTag(),
                )
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            // Capture has ended; an on-device model still has to transcribe it.
            override fun onEndOfSpeech() {
                onTranscribing()
            }

            override fun onError(error: Int) {
                onFailure(error.toFailure())
            }

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                val text = firstResult(results)
                if (text == null) onFailure(VoiceFailure.NO_SPEECH) else onFinal(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            sr.startListening(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "startListening failed", t)
            onFailure(VoiceFailure.OTHER)
        }
    }

    /** Stop capturing but keep the session: the recognizer still reports what it heard. */
    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    /** Abandon the session outright, with no result. */
    fun cancel() {
        runCatching { recognizer?.cancel() }
    }

    fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
}

private fun Int.toFailure(): VoiceFailure = when (this) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceFailure.PERMISSION_DENIED
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceFailure.NETWORK
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceFailure.NO_SPEECH
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceFailure.BUSY
    SpeechRecognizer.ERROR_AUDIO -> VoiceFailure.AUDIO
    else -> VoiceFailure.OTHER
}
