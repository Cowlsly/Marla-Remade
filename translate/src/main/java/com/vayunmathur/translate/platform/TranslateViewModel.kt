package com.vayunmathur.translate.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.ocr.OcrEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the shared engines (translation, OCR, TTS) and the persisted language
 * selection. The SMaLL-100 model (~1.2 GB) is now gated via
 * [com.vayunmathur.library.downloadservice.InitialModelDownloadChecker] in
 * MainActivity — just like OpenAssistant — so it starts installing automatically
 * as soon as the page is opened. By the time this screen is shown,
 * [Small100Model] files are already present, and we just load the ncnn engine.
 */
class TranslateViewModel(app: Application) : AndroidViewModel(app) {

    val translator: TranslationEngine = Small100Translator(app)
    val ocr = OcrEngine(app)
    private val tts = TtsSpeaker(app)
    private val settings = TranslateSettings(app)

    private val _sourceLang = MutableStateFlow(Languages.AUTO.code)
    val sourceLang: StateFlow<String> = _sourceLang.asStateFlow()

    private val _targetLang = MutableStateFlow("es")
    val targetLang: StateFlow<String> = _targetLang.asStateFlow()

    /** True once the ncnn engine is loaded (model already downloaded by the checker). */
    private val _translationAvailable = MutableStateFlow(false)
    val translationAvailable: StateFlow<Boolean> = _translationAvailable.asStateFlow()

    init {
        viewModelScope.launch {
            _sourceLang.value = settings.source()
            _targetLang.value = settings.target()
            _translationAvailable.value = translator.isAvailable()
        }
    }

    fun setSource(code: String) {
        _sourceLang.value = code
        viewModelScope.launch { settings.setSource(code) }
    }

    fun setTarget(code: String) {
        _targetLang.value = code
        viewModelScope.launch { settings.setTarget(code) }
    }

    /** Swap source and target. No-op while source is Auto (nothing to swap into). */
    fun swap() {
        val src = _sourceLang.value
        if (src == Languages.AUTO.code) return
        val tgt = _targetLang.value
        setSource(tgt)
        setTarget(src)
    }

    /** Translate using the current selection; [from] null means auto-detect. */
    suspend fun translate(text: String): String? {
        val from = _sourceLang.value.takeIf { it != Languages.AUTO.code }
        return translator.translate(text, from, _targetLang.value)
    }

    /**
     * Speak [text] in [languageCode] (defaults to the current target). Nothing is spoken
     * when the TTS engine has no voice for that language — [onMissingVoice] fires instead,
     * so the caller can say so rather than have it read out in the device's language.
     */
    fun speak(
        text: String,
        languageCode: String = _targetLang.value,
        onMissingVoice: (() -> Unit)? = null,
    ) = tts.speak(text, languageCode, onMissingVoice)

    fun stopSpeaking() = tts.stop()

    override fun onCleared() {
        ocr.close()
        (translator as? Small100Translator)?.close()
        tts.shutdown()
    }
}
