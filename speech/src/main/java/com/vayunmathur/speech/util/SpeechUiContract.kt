package com.vayunmathur.speech.util

/**
 * The UI contract for the setup screen.
 *
 * The screen takes a state value plus an actions interface rather than reading the device
 * directly, so it can be rendered by a `@Preview` — which is what the store listing images
 * are generated from. It lives in `util` so the dependency runs one way: the UI depends on
 * `util`, never the reverse.
 *
 * There is no ViewModel here. Every field below is a fact about the device (a granted
 * permission, a Settings.Secure entry, a downloaded model) that the binder re-reads when
 * the user returns from a settings screen, so the state is assembled at the call site.
 *
 * After multilingual expansion (20 TTS voices) the TTS section is a list of per-language
 * download rows with progress, size badge, delete, plus storage total and current test lang.
 */

/** Per-voice UI row in the voices section. */
data class TtsVoiceUiState(
    val code: String,
    val bcp47: String,
    val iso3: String,
    val nativeName: String,
    val englishName: String,
    val isInstalled: Boolean,
    val progress: Float = 0f,
    val sizeEstimateMb: Int = 80,
    val quality: String = "high",
    val sampleRate: Int = 22050,
)

/** Which of the five setup steps are already done + voices list. */
data class SpeechSetupUiState(
    /** Whisper recognition model readable from the APK's assets. */
    val modelReady: Boolean = false,
    val hasMic: Boolean = false,
    /** This app is the device's `voice_recognition_service`. */
    val isRecognizerDefault: Boolean = false,
    /** Any Piper TTS voice present on disk (overall flag, legacy name). */
    val ttsModelReady: Boolean = false,
    /** This app is the device's `tts_default_synth`. */
    val isTtsDefault: Boolean = false,

    /** All 20 voices with install/progress status (for expanded UI). */
    val ttsVoices: List<TtsVoiceUiState> = emptyList(),
    /** Total installed bytes across all voices. */
    val installedBytes: Long = 0L,
    /** Currently selected language code for TTS test (must be installed code). */
    val currentTestLang: String = "en",
)

/**
 * Setup screen callbacks. Every method has a no-op default so a preview can render the
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 *
 * The voice downloads are `suspend` because the button drives them directly and polls
 * [voiceProgress] while they run. Speech recognition has no download: the model ships in the
 * APK, so only TTS voices beyond the bundled English one are fetched at runtime.
 */
interface SpeechSetupActions {
    fun requestMicPermission() {}
    fun openVoiceInputSettings() {}
    fun openTtsSettings() {}

    /** Re-read the device state after a step completes. */
    fun refresh() {}

    /** Overall voice progress (averaged) for legacy single-voice UI. */
    fun voiceProgress(): Float = 0f
    suspend fun downloadVoice() {}

    /** Per-code progress (for expanded multi-voice UI). */
    fun voiceProgress(code: String): Float = 0f
    suspend fun downloadVoice(code: String) {}
    fun deleteVoice(code: String) {}
    fun installedCodes(): List<String> = emptyList()

    companion object {
        val Noop: SpeechSetupActions = object : SpeechSetupActions {}
    }
}
