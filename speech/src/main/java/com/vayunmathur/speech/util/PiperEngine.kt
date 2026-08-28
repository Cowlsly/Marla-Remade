package com.vayunmathur.speech.util

import android.content.Context
import android.util.Log
import com.vayunmathur.library.ml.PiperConfig
import com.vayunmathur.library.ml.PiperSynthesizer
import org.json.JSONObject
import java.io.File

/**
 * Wrapper around [PiperSynthesizer], which runs offline **Piper (VITS)** voices on the Vulkan
 *
 * compute runtime in `:library:ml`. The original implementation held a single ncnn `Vits`
 * 22050 Hz) with a `loadFailed` latch. After the multilingual expansion we keep a
 * bounded LRU cache (max 2 voices, ~50-100 MB per instance on arm64-v8a) keyed by
 * [PiperVoiceDef.id] so switching languages doesn't pay a cold-load each time and
 * RAM stays bounded.
 *
 * Multi-quality migration: old installs have dirs like `en_US-lessac-low` or
 * `de_DE-thorsten-medium` while new registry uses generic `en_US-high`. [isExtracted]
 * tolerates old ids via [PiperVoiceRegistry.findAnyValidDirForBcp47], so this wrapper
 * must also be tolerant when resolving the actual dir for Vits loading – otherwise
 * in-app play works but Settings play (which goes through
 * [com.vayunmathur.speech.service.PiperTtsService]) fails.
 *
 * Not thread-safe beyond the `@Synchronized` methods; the TTS framework calls
 * [com.vayunmathur.speech.service.PiperTtsService.onSynthesizeText] serially, so
 * single-thread contract holds.
 */
class PiperEngine(private val context: Context) {

    /** LRU cache: voice id -> loaded synthesiser, access-order for eviction. */
    private val cache = LinkedHashMap<String, PiperSynthesizer>(2, 0.75f, true)
    private val failed = mutableMapOf<String, Boolean>()

    fun preload(): Boolean = ensure()

    /** Load a specific language/code/id, e.g. "de" or "de-DE" or "en_US-high". */
    fun preload(code: String): Boolean = ensure(code)

    fun preloadAllInstalled(): Boolean = ensure(PiperVoiceRegistry.DEFAULT.id)

    /** Native sample rate of the default loaded voice (Hz); 0 if not loaded. */
    fun sampleRate(): Int {
        val defId = PiperVoiceRegistry.DEFAULT.id
        return cache[defId]?.sampleRate ?: cache.values.firstOrNull()?.sampleRate ?: 0
    }

    /** Sample rate of a specific loaded voice, 0 if not in cache. */
    fun sampleRate(voiceIdOrCode: String): Int {
        val def = resolveDef(voiceIdOrCode) ?: return 0
        return cache[def.id]?.sampleRate ?: 0
    }

    @Synchronized
    fun synthesize(text: String, speed: Float, onChunk: (FloatArray) -> Boolean): Boolean =
        synthesize(text, PiperVoiceRegistry.DEFAULT.id, speed, onChunk)

    @Synchronized
    fun synthesize(text: String, voiceIdOrCode: String, speed: Float, onChunk: (FloatArray) -> Boolean): Boolean {
        if (!ensure(voiceIdOrCode)) return false
        val def = resolveDef(voiceIdOrCode) ?: return false
        val engine = cache[def.id] ?: return false
        return try {
            val samples = engine.synthesize(text, speed)
            if (samples.isEmpty()) return true
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + 4096, samples.size)
                val chunk = samples.copyOfRange(offset, end)
                if (!onChunk(chunk)) return false
                offset = end
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "synthesize failed for ${def.id}", t)
            false
        }
    }

    @Synchronized
    fun close() {
        closeAll()
    }

    @Synchronized
    fun close(voiceIdOrCode: String) {
        val def = resolveDef(voiceIdOrCode) ?: run {
            cache[voiceIdOrCode]?.let { v ->
                try { v.close() } catch (_: Throwable) {}
                cache.remove(voiceIdOrCode)
            }
            return
        }
        try {
            cache[def.id]?.close()
        } catch (_: Throwable) {
        }
        cache.remove(def.id)
    }

    @Synchronized
    fun closeAll() {
        for ((_, v) in cache) {
            try { v.close() } catch (_: Throwable) {}
        }
        cache.clear()
    }

    @Synchronized
    private fun ensure(): Boolean = ensure(PiperVoiceRegistry.DEFAULT.id)

    @Synchronized
    private fun ensure(voiceIdOrCode: String): Boolean {
        val def = resolveDef(voiceIdOrCode) ?: run {
            Log.d(TAG, "resolve failed for $voiceIdOrCode")
            return false
        }
        val id = def.id
        // Don't hard-fail on previously-failed cache entry – retry once after migration,
        // because registry IDs changed (en_US-lessac-low -> en_US-high) and old failed latch
        // would block Settings Play forever.
        if (failed[id] == true) {
            failed.remove(id)
        }

        if (cache.containsKey(id)) {
            cache[id]?.let { v -> cache[id] = v }
            return true
        }

        val extracted = PiperVoiceRegistry.isExtracted(context, def) ||
            (def.code == "en" && PiperModel.isExtracted(context))
        if (!extracted) return false

        return try {
            PiperVoiceRegistry.migrateLegacyIfNeeded(context)
            val dir = voiceDirForDef(def)
            if (!dir.isDirectory) {
                Log.e(TAG, "voice dir missing for $id resolved to $dir, code=$voiceIdOrCode")
                failed[id] = true
                return false
            }
            val engine = open(dir) ?: run {
                failed[id] = true
                return false
            }
            if (!engine.isAvailable) {
                Log.e(TAG, "the runtime could not load the voice in $dir for $id")
                try { engine.close() } catch (_: Throwable) {}
                failed[id] = true
                return false
            }
            if (cache.size >= MAX_CACHED) {
                val eldestKey = cache.keys.firstOrNull()
                eldestKey?.let { key ->
                    try { cache[key]?.close() } catch (_: Throwable) {}
                    cache.remove(key)
                    Log.d(TAG, "evicted $key (LRU)")
                }
            }
            cache[id] = engine
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Piper load failed for $id (code=$voiceIdOrCode)", t)
            failed[id] = true
            false
        }
    }

    /**
     * Build a synthesiser from a voice directory.
     *
     * The bundle names its four networks `<voice>_{enc_p,dp,flow,dec}.maml`, so the prefix is
     * recovered from whichever encoder is present rather than reconstructed from the registry
     * id - the two have diverged before, which is what the `voiceDirForDef` fallbacks below
     * exist to survive.
     *
     * Returns null, having logged, when the directory is not a voice.
     */
    private fun open(dir: File): PiperSynthesizer? {
        val encoder = dir.listFiles()?.firstOrNull { it.name.endsWith(ENCODER_SUFFIX) }
        if (encoder == null) {
            Log.e(TAG, "no *$ENCODER_SUFFIX in $dir")
            return null
        }
        val voice = encoder.name.removeSuffix(ENCODER_SUFFIX)
        val config = readConfig(dir) ?: return null
        if (config.second > 1) {
            // A multi-speaker voice also ships an `emb_g` network, which is not converted:
            // every voice in the registry is single-speaker. Loading one anyway would speak
            // in whichever voice the embedding defaulted to.
            Log.e(TAG, "$voice has ${config.second} speakers; only single-speaker voices run")
            return null
        }
        return PiperSynthesizer(dir, voice, config.first)
    }

    /**
     * The voice's sample rate, scales and speaker count from its `config.json`.
     *
     * The rate is not defaulted: a voice played at the wrong rate is intelligible but
     * wrongly pitched, which is worse than refusing to load it.
     */
    private fun readConfig(dir: File): Pair<PiperConfig, Int>? = try {
        val json = JSONObject(File(dir, "config.json").readText())
        val audio = json.optJSONObject("audio")
        val rate = audio?.optInt("sample_rate", 0) ?: 0
        if (rate <= 0) {
            Log.e(TAG, "config.json in $dir has no audio.sample_rate")
            return null
        }
        val inference = json.optJSONObject("inference")
        val config = PiperConfig(
            sampleRate = rate,
            noise = inference?.optDouble("noise_scale", 0.667)?.toFloat() ?: 0.667f,
            length = inference?.optDouble("length_scale", 1.0)?.toFloat() ?: 1.0f,
            durationNoise = inference?.optDouble("noise_w", 0.8)?.toFloat() ?: 0.8f,
        )
        config to json.optInt("num_speakers", 1)
    } catch (t: Throwable) {
        Log.e(TAG, "cannot read config.json in $dir", t)
        null
    }

    private fun resolveDef(voiceIdOrCode: String): PiperVoiceDef? {
        if (voiceIdOrCode.isBlank()) return PiperVoiceRegistry.DEFAULT
        PiperVoiceRegistry.resolve(
            lang = voiceIdOrCode,
            voiceName = voiceIdOrCode,
        )?.let { return it }
        PiperVoiceRegistry.byId(voiceIdOrCode)?.let { return it }
        PiperVoiceRegistry.byCode(voiceIdOrCode)?.let { return it }
        PiperVoiceRegistry.byBcp47(voiceIdOrCode)?.let { return it }
        PiperVoiceRegistry.byBcp47(voiceIdOrCode.replace('_', '-'))?.let { return it }
        PiperVoiceRegistry.byIso3(voiceIdOrCode)?.let { return it }
        if (voiceIdOrCode.length >= 2) {
            PiperVoiceRegistry.byCode(voiceIdOrCode.take(2))?.let { return it }
        }
        return null
    }

    private fun voiceDirForDef(def: PiperVoiceDef): File {
        val direct = PiperVoiceRegistry.voiceDir(context, def)
        if (direct.isDirectory) return direct
        // Tolerate old speaker-specific ids after rename to generic (e.g. en_US-lessac-low
        // dir present but registry now expects en_US-high). This makes Settings Play work
        // on upgraded installs.
        PiperVoiceRegistry.findAnyValidDirForBcp47(context, def.bcp47)?.let { return it }
        try {
            val vRoot = PiperVoiceRegistry.voicesDir(context)
            if (vRoot.isDirectory) {
                // Search same BCP-47 folder for any dir with encoder – last resort
                val bcpRoot = File(vRoot, def.bcp47)
                if (bcpRoot.isDirectory) {
                    bcpRoot.listFiles()?.firstOrNull { child ->
                        child.isDirectory && child.listFiles()?.any { it.name.endsWith("_enc_p.maml") } == true
                    }?.let { return it }
                }
                // Broader search: any BCP-47 starting with code
                vRoot.listFiles()?.forEach { bcpDir ->
                    if (!bcpDir.isDirectory) return@forEach
                    if (!bcpDir.name.equals(def.bcp47, ignoreCase = true) &&
                        !bcpDir.name.lowercase().startsWith(def.code.lowercase())) return@forEach
                    bcpDir.listFiles()?.firstOrNull { child ->
                        child.isDirectory && child.listFiles()?.any { it.name.endsWith("_enc_p.maml") } == true
                    }?.let { return it }
                }
            }
        } catch (_: Throwable) {}
        if (def.code == "en") {
            val legacy = PiperVoiceRegistry.legacyVoiceDir(context)
            if (legacy.isDirectory) return legacy
            val shim = PiperModel.voiceDir(context)
            if (shim.isDirectory) return shim
        }
        return direct
    }

    companion object {
        private const val TAG = "PiperEngine"
        private const val MAX_CACHED = 2

        /** How the bundle names the text encoder, and so how the voice prefix is found. */
        private const val ENCODER_SUFFIX = "_enc_p.maml"
    }
}
