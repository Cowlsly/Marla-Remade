package com.vayunmathur.speech.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.vayunmathur.speech.util.PiperEngine
import com.vayunmathur.speech.util.PiperModel
import com.vayunmathur.speech.util.PiperVoiceDef
import com.vayunmathur.speech.util.PiperVoiceRegistry
import java.util.Locale
import kotlin.math.roundToInt

/**
 * System text-to-speech engine backed by offline **Piper (VITS)** running on the Vulkan
 * compute runtime in `:library:ml`. Once selected as the device's TTS engine, any app
 * that uses [android.speech.tts.TextToSpeech] (Translate's read-aloud, TalkBack,
 * ebook readers, ...) synthesizes fully on-device.
 *
 * Originally English-only (`en-US` Amy medium, 22050 Hz). After the multilingual
 * expansion we advertise only the voices that are actually extracted on disk under
 * `getExternalFilesDir()/piper/voices/<bcp47>/<id>/`, e.g. `de-DE`, `fr-FR`, etc.
 * Each voice's sample rate comes from its `config.json` — all high quality 22.05 kHz
 * now (previously 16 kHz low) — so [onSynthesizeText] starts the callback at the correct hz.
 *
 * The Settings Play button is enabled only if:
 *   isLanguageAvailable >= LANG_AVAILABLE AND getDefaultVoiceNameFor returns a
 *   non-empty name that exists in getVoices().
 */
class PiperTtsService : TextToSpeechService() {

    private val engine by lazy { PiperEngine(applicationContext) }

    @Volatile private var stopped = false
    @Volatile private var currentVoiceId: String = PiperVoiceRegistry.DEFAULT.id

    override fun onCreate() {
        super.onCreate()
        // Warm default voice off main thread so first utterance doesn't stall.
        Thread {
            try {
                PiperVoiceRegistry.migrateLegacyIfNeeded(applicationContext)
            } catch (_: Throwable) {}
            engine.preload()
        }.start()
    }

    override fun onDestroy() {
        engine.closeAll()
        super.onDestroy()
    }

    // --- Language availability: dynamic over installed voices ---

    private fun resolveDef(
        lang: String?,
        country: String? = null,
        variant: String? = null,
        voiceName: String? = null,
    ): PiperVoiceDef? {
        return PiperVoiceRegistry.resolve(lang, country, variant, voiceName)
    }

    private fun isInstalled(def: PiperVoiceDef): Boolean {
        return try {
            PiperVoiceRegistry.isExtracted(applicationContext, def) ||
                (def.code == "en" && PiperModel.isExtracted(applicationContext))
        } catch (_: Throwable) {
            false
        }
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val def = resolveDef(lang, country, variant) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        // Only advertise installed voices — uninstalled langs would show a disabled
        // Play button in Settings. Returning LANG_NOT_SUPPORTED hides them.
        if (!isInstalled(def)) return TextToSpeech.LANG_NOT_SUPPORTED

        val hasCountry = !country.isNullOrEmpty()
        val hasVariant = !variant.isNullOrEmpty()
        return when {
            hasCountry && hasVariant -> TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            hasCountry -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val code = onIsLanguageAvailable(lang, country, variant)
        if (code != TextToSpeech.LANG_NOT_SUPPORTED) {
            resolveDef(lang, country, variant)?.let { def ->
                currentVoiceId = def.id
            }
        }
        return code
    }

    override fun onGetLanguage(): Array<String> {
        // Return current voice's ISO3 triple, fallback eng-USA for legacy callers.
        return try {
            val def = PiperVoiceRegistry.byId(currentVoiceId) ?: PiperVoiceRegistry.DEFAULT
            if (isInstalled(def)) {
                arrayOf(def.iso3, def.iso3Country, "")
            } else {
                // Try first installed
                val installed = PiperVoiceRegistry.installedDefs(applicationContext)
                if (installed.isNotEmpty()) {
                    arrayOf(installed[0].iso3, installed[0].iso3Country, "")
                } else {
                    arrayOf("eng", "USA", "")
                }
            }
        } catch (_: Throwable) {
            arrayOf("eng", "USA", "")
        }
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        if (lang == null && country == null && variant == null) {
            // Framework probe without args — return first installed as BCP47.
            val installed = try {
                PiperVoiceRegistry.installedDefs(applicationContext)
            } catch (_: Throwable) {
                emptyList()
            }
            if (installed.isNotEmpty()) return installed[0].bcp47
            if (PiperModel.isExtracted(applicationContext)) return "en-US"
            return null
        }
        val avail = onIsLanguageAvailable(lang, country, variant)
        if (avail == TextToSpeech.LANG_NOT_SUPPORTED) return null
        val def = resolveDef(lang, country, variant) ?: return null
        if (!isInstalled(def)) return null
        // Must match a Voice name from onGetVoices() — return BCP47 tag.
        return def.bcp47
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR
        // Accept if any installed def matches via smart resolver.
        val def = resolveDef(voiceName = voiceName, lang = voiceName)
        if (def != null && isInstalled(def)) return TextToSpeech.SUCCESS

        // Legacy accept: eng-USA, en-US, any installed BCP47 case-insensitive, en-us-x-*
        val installed = try {
            PiperVoiceRegistry.installedDefs(applicationContext)
        } catch (_: Throwable) {
            emptyList()
        }
        for (d in installed) {
            if (voiceName.equals(d.bcp47, ignoreCase = true)) return TextToSpeech.SUCCESS
            if (voiceName.equals("${d.iso3}-${d.iso3Country}", ignoreCase = true)) return TextToSpeech.SUCCESS
            if (voiceName.equals(d.id, ignoreCase = true)) return TextToSpeech.SUCCESS
            if (voiceName.lowercase().startsWith(d.code.lowercase() + "-") ||
                voiceName.lowercase().startsWith(d.code.lowercase() + "_")
            ) return TextToSpeech.SUCCESS
        }
        // Also accept legacy English tags even if only legacy dir present
        if (voiceName.equals("en-US", ignoreCase = true) ||
            voiceName.equals("eng-USA", ignoreCase = true) ||
            voiceName.lowercase().startsWith("en-") ||
            voiceName.lowercase().startsWith("en_") ||
            voiceName.equals("en", ignoreCase = true)
        ) {
            if (PiperModel.isExtracted(applicationContext) ||
                PiperVoiceRegistry.isExtracted(applicationContext, PiperVoiceRegistry.DEFAULT)
            ) {
                return TextToSpeech.SUCCESS
            }
        }

        return try {
            super.onIsValidVoiceName(voiceName)
        } catch (_: Throwable) {
            TextToSpeech.ERROR
        }
    }

    override fun onLoadVoice(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR
        val def = resolveDef(voiceName = voiceName, lang = voiceName)
        if (def != null && isInstalled(def)) {
            currentVoiceId = def.id
            return TextToSpeech.SUCCESS
        }
        // Check legacy english
        if (voiceName.equals("en-US", ignoreCase = true) ||
            voiceName.equals("eng-USA", ignoreCase = true) ||
            voiceName.lowercase().startsWith("en")
        ) {
            if (PiperModel.isExtracted(applicationContext) ||
                PiperVoiceRegistry.isExtracted(applicationContext, PiperVoiceRegistry.DEFAULT)
            ) {
                currentVoiceId = PiperVoiceRegistry.DEFAULT.id
                return TextToSpeech.SUCCESS
            }
        }
        return onIsValidVoiceName(voiceName)
    }

    override fun onGetVoices(): MutableList<Voice> {
        val ctx = applicationContext
        val installed = try {
            PiperVoiceRegistry.installedDefs(ctx).let { defs ->
                if (defs.isEmpty() && PiperModel.isExtracted(ctx)) {
                    listOf(PiperVoiceRegistry.DEFAULT)
                } else {
                    defs
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
        if (installed.isEmpty()) return mutableListOf()

        val voices = mutableListOf<Voice>()

        for (def in installed) {
            val locale = try {
                Locale.forLanguageTag(def.bcp47)
            } catch (_: Throwable) {
                Locale(def.code, def.iso3Country.take(2))
            }

            // Primary: BCP-47 tag with extension carrying voice id so clients can
            // distinguish multiple voices for same language if ever needed.
            // Name format "<bcp47>-x-<id>" e.g. "en-US-x-en_US-amy-medium"
            val primaryName = "${def.bcp47}-x-${def.id}"
            voices += Voice(
                primaryName,
                locale,
                Voice.QUALITY_VERY_HIGH,
                Voice.LATENCY_NORMAL,
                false,
                emptySet(),
            )
            // Also publish plain BCP-47 for easy selection ("de-DE")
            if (def.bcp47 != primaryName) {
                voices += Voice(
                    def.bcp47,
                    locale,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_NORMAL,
                    false,
                    emptySet(),
                )
            }
            // ISO3 variant for CHECK_TTS_DATA compat ("deu-DEU")
            val iso3Name = "${def.iso3}-${def.iso3Country}"
            voices += Voice(
                iso3Name,
                Locale(def.code, def.iso3Country.take(2)),
                Voice.QUALITY_VERY_HIGH,
                Voice.LATENCY_NORMAL,
                false,
                emptySet(),
            )
        }

        // Backward compat: for EN also publish "en-US" and "eng-USA" without x- extension
        // so older framework probes that expect exactly those names still enable Play.
        if (installed.any { it.code == "en" }) {
            val enLocale = Locale.US
            val enLegacy = Locale.US
            if (voices.none { it.name == "en-US" }) {
                voices += Voice(
                    "en-US",
                    enLocale,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_NORMAL,
                    false,
                    emptySet(),
                )
            }
            if (voices.none { it.name == "eng-USA" }) {
                voices += Voice(
                    "eng-USA",
                    enLegacy,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_NORMAL,
                    false,
                    emptySet(),
                )
            }
        }

        return voices
    }

    override fun onStop() {
        stopped = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        val text = request.charSequenceText?.toString().orEmpty()

        // Resolve voice from request params: voiceName has priority, then language.
        val voiceName = request.voiceName

        val resolvedDef = resolveDef(
            lang = request.language,
            country = request.country,
            variant = request.variant,
            voiceName = voiceName,
        ) ?: run {
            // Fallback to current voice id
            PiperVoiceRegistry.byId(currentVoiceId) ?: PiperVoiceRegistry.DEFAULT
        }

        // Update current voice id so subsequent calls without explicit voice keep using it.
        if (isInstalled(resolvedDef)) {
            currentVoiceId = resolvedDef.id
        }

        if (!engine.preload(currentVoiceId)) {
            callback.error()
            return
        }
        val sampleRate = engine.sampleRate(currentVoiceId).let { sr ->
            if (sr <= 0) resolvedDef.sampleRate else sr
        }
        if (sampleRate <= 0) {
            callback.error()
            return
        }
        if (text.isBlank()) {
            callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        val speed = (request.speechRate / 100f).coerceIn(0.3f, 3.0f)

        if (callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
            return
        }

        val maxBytes = callback.maxBufferSize
        val ok = engine.synthesize(text, currentVoiceId, speed) { samples ->
            if (stopped) return@synthesize false
            val pcm = floatsToPcm16(samples)
            var offset = 0
            while (offset < pcm.size) {
                if (stopped) return@synthesize false
                val n = minOf(maxBytes, pcm.size - offset)
                if (callback.audioAvailable(pcm, offset, n) != TextToSpeech.SUCCESS) {
                    return@synthesize false
                }
                offset += n
            }
            true
        }

        if (ok || stopped) callback.done() else callback.error()
    }

    /** Convert Piper's float samples ([-1, 1]) to little-endian 16-bit PCM bytes. */
    private fun floatsToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).roundToInt()
            out[i++] = (v and 0xFF).toByte()
            out[i++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
