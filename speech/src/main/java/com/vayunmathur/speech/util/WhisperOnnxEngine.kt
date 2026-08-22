package com.vayunmathur.speech.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer
import org.json.JSONObject

/**
 * Offline **whisper-tiny** speech recognition on ONNX Runtime, reading the int8 models straight
 * out of the APK's assets (see `speech/src/main/assets/whisper-tiny/`, refreshed by
 * `scripts/speech/fetch_whisper_onnx.sh`).
 *
 * Replaces the old ncnn `Whisper` AAR path, which needed a 117 MB runtime download. `onnx2ncnn`
 * cannot consume this model — it has no `DynamicQuantizeLinear`/`MatMulInteger`/`ConvInteger`
 * support — and the AAR expects a six-net decomposition (`fbank`, `embed_token`, `proj_out`, .)
 * that HuggingFace does not export, so the feature extraction ([WhisperFeatures]) and the decode
 * loop below live in Kotlin instead.
 *
 * Not thread-safe; [transcribe] is called from a single worker thread by
 * [com.vayunmathur.speech.service.WhisperRecognitionService].
 */
class WhisperOnnxEngine(private val context: Context) {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var tokenizer: WhisperTokenizer? = null
    private var config: GenerationConfig? = null
    private var loadFailed = false

    /** Ids and token maps read from `generation_config.json` rather than hardcoded. */
    private class GenerationConfig(json: JSONObject) {
        val startOfTranscript = json.optInt("decoder_start_token_id", 50258)
        val endOfText = json.optInt("eos_token_id", 50257)
        val noTimestamps = json.optInt("no_timestamps_token_id", 50363)
        val maxLength = json.optInt("max_length", 448)

        /** ISO-639-1 code to its `<|xx|>` token id, e.g. `en` to 50259. */
        val langToId: Map<String, Int> = buildMap {
            val obj = json.optJSONObject("lang_to_id") ?: return@buildMap
            for (key in obj.keys()) {
                // Keys arrive as "<|en|>"; store the bare code.
                put(key.removePrefix("<|").removeSuffix("|>"), obj.optInt(key))
            }
        }

        /** Never-emit ids (music notes, formatting artefacts) that upstream masks out. */
        val suppress: Set<Int> = buildSet {
            json.optJSONArray("suppress_tokens")?.let { a ->
                for (i in 0 until a.length()) add(a.optInt(i))
            }
        }

        /** Additionally suppressed at the very first generated position (leading space, EOT). */
        val suppressAtBegin: Set<Int> = buildSet {
            json.optJSONArray("begin_suppress_tokens")?.let { a ->
                for (i in 0 until a.length()) add(a.optInt(i))
            }
        }

        /** `<|transcribe|>`; `<|translate|>` is the id one lower and unused here. */
        val transcribe: Int = json.optJSONObject("task_to_id")?.optInt("transcribe", 50359) ?: 50359
    }

    /** Assets are always present, so this only reports whether loading succeeded. */
    fun isAvailable(): Boolean = ensure()

    @Synchronized
    private fun ensure(): Boolean {
        if (encoder != null && decoder != null) return true
        if (loadFailed) return false
        return try {
            val opts = OrtSession.SessionOptions().apply {
                // Whisper-tiny is small; two threads is a reasonable latency/battery balance.
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
            }
            // createSession(bytes) rather than createSession(path): the models stay mapped in
            // the APK instead of being copied into filesDir.
            encoder = env.createSession(asset(ENCODER), opts)
            decoder = env.createSession(asset(DECODER), opts)
            tokenizer = context.assets.open("$DIR/$VOCAB").use { WhisperTokenizer.load(it) }
            config = GenerationConfig(
                JSONObject(context.assets.open("$DIR/$GEN_CONFIG").use { it.bufferedReader().readText() }),
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Whisper ONNX load failed", t)
            loadFailed = true
            closeInternal()
            false
        }
    }

    private fun asset(name: String): ByteArray =
        context.assets.open("$DIR/$name").use { it.readBytes() }

    /**
     * Transcribe [pcm16k] (16 kHz mono). [language] is ISO-639-1, or null/"auto" to detect.
     * Returns the text, or null if the model could not be loaded or inference failed.
     */
    fun transcribe(pcm16k: ShortArray, language: String?): String? {
        if (!ensure()) return null
        val dec = decoder ?: return null
        val cfg = config ?: return null
        val tok = tokenizer ?: return null

        return try {
            val mel = WhisperFeatures.logMel(pcm16k)
            encode(mel).use { hidden ->
                val lang = resolveLanguage(dec, cfg, hidden, language)
                val prompt = intArrayOf(cfg.startOfTranscript, lang, cfg.transcribe, cfg.noTimestamps)
                val ids = greedyDecode(dec, cfg, hidden, prompt)
                tok.decode(ids)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "transcribe failed", t)
            null
        }
    }

    /** Run the audio encoder once per utterance; its output is reused by every decode step. */
    private fun encode(mel: FloatArray): OnnxTensor {
        val enc = encoder ?: error("encoder not loaded")
        val shape = longArrayOf(1, WhisperFeatures.N_MELS.toLong(), WhisperFeatures.N_FRAMES.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), shape).use { input ->
            enc.run(mapOf("input_features" to input)).use { result ->
                val out = result.get(0) as OnnxTensor
                // Copy into a tensor we own; the result closes its own values.
                val buf = FloatArray(out.info.shape.fold(1L) { a, b -> a * b }.toInt())
                out.floatBuffer.get(buf)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), out.info.shape)
            }
        }
    }

    /**
     * Pick the language token. An explicit [requested] code wins; otherwise one decoder step on
     * the bare `<|startoftranscript|>` prompt is scored over just the language tokens, which is
     * how Whisper's own detection works.
     */
    private fun resolveLanguage(
        dec: OrtSession,
        cfg: GenerationConfig,
        hidden: OnnxTensor,
        requested: String?,
    ): Int {
        val code = requested?.substringBefore('-')?.lowercase()
        if (code != null && code != "auto") {
            cfg.langToId[code]?.let { return it }
            Log.w(TAG, "no Whisper language token for '$requested', detecting instead")
        }
        val fallback = cfg.langToId["en"] ?: 50259
        if (cfg.langToId.isEmpty()) return fallback

        return try {
            emptyPast().use { past ->
                val ids = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longArrayOf(cfg.startOfTranscript.toLong())), longArrayOf(1, 1),
                )
                ids.use {
                    dec.run(feeds(hidden, past, ids, useCache = false)).use { result ->
                        val logits = result.get(0) as OnnxTensor
                        val vocab = logits.info.shape.last().toInt()
                        val row = FloatArray(vocab)
                        logits.floatBuffer.position(0)
                        logits.floatBuffer.get(row)
                        var best = fallback
                        var bestScore = -Float.MAX_VALUE
                        for (id in cfg.langToId.values) {
                            if (id < vocab && row[id] > bestScore) {
                                bestScore = row[id]
                                best = id
                            }
                        }
                        best
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "language detection failed, assuming en", t)
            fallback
        }
    }

    /**
     * Greedy argmax decode threading the KV cache.
     *
     * `decoder_model_merged` requires all 16 `past_key_values` inputs on every call. On the
     * first call they are zero-length and `use_cache_branch` is false, which makes the graph
     * compute cross-attention K/V from `encoder_hidden_states`; those come back as
     * `present.*.encoder.*` and stay constant, while `present.*.decoder.*` grow by one per step.
     */
    private fun greedyDecode(
        dec: OrtSession,
        cfg: GenerationConfig,
        hidden: OnnxTensor,
        prompt: IntArray,
    ): List<Int> {
        val out = ArrayList<Int>()
        val limit = (cfg.maxLength - prompt.size).coerceAtMost(MAX_NEW_TOKENS)

        var past = emptyPast()
        var step = 0
        var next = -1
        try {
            while (step < limit) {
                val ids = if (step == 0) prompt else intArrayOf(next)
                val longIds = LongArray(ids.size) { ids[it].toLong() }
                val idTensor = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longIds), longArrayOf(1, ids.size.toLong()),
                )
                val result = idTensor.use { dec.run(feeds(hidden, past, it, useCache = step > 0)) }
                // ORT has consumed the inputs; the previous cache can go now.
                past.close()

                next = argmax(
                    result.get(0) as OnnxTensor,
                    suppress = cfg.suppress,
                    alsoSuppress = if (step == 0) cfg.suppressAtBegin else emptySet(),
                )
                past = Past(result)
                step++

                if (next == cfg.endOfText) break
                out.add(next)
            }
        } finally {
            past.close()
        }
        return out
    }

    /** Argmax over the final position's logits, with masked ids excluded. */
    private fun argmax(logits: OnnxTensor, suppress: Set<Int>, alsoSuppress: Set<Int>): Int {
        val shape = logits.info.shape
        val seqLen = shape[1].toInt()
        val vocab = shape[2].toInt()
        val row = FloatArray(vocab)
        val buf = logits.floatBuffer
        buf.position((seqLen - 1) * vocab)
        buf.get(row)
        var best = 0
        var bestScore = -Float.MAX_VALUE
        for (i in 0 until vocab) {
            if (i in suppress || i in alsoSuppress) continue
            if (row[i] > bestScore) {
                bestScore = row[i]
                best = i
            }
        }
        return best
    }

    /** Build the full 19-input feed map for one decoder call. */
    private fun feeds(
        hidden: OnnxTensor,
        past: Past,
        ids: OnnxTensor,
        useCache: Boolean,
    ): Map<String, OnnxTensor> {
        val map = LinkedHashMap<String, OnnxTensor>(20)
        map["input_ids"] = ids
        map["encoder_hidden_states"] = hidden
        for ((name, tensor) in past.tensors()) map[name] = tensor
        map["use_cache_branch"] = if (useCache) past.cacheTrue else past.cacheFalse
        return map
    }

    private fun emptyPast(): Past = Past(null)

    /**
     * One generation's worth of KV cache. Wraps either the zero-length starting state or the
     * `present.*` outputs of the previous step, which are handed straight back as inputs rather
     * than copied — the encoder halves alone are 4 x 2 x 6 x 1500 x 64 floats.
     */
    private inner class Past(private val result: OrtSession.Result?) {
        val cacheFalse: OnnxTensor = OnnxTensor.createTensor(env, booleanArrayOf(false))
        val cacheTrue: OnnxTensor = OnnxTensor.createTensor(env, booleanArrayOf(true))

        private val empties = ArrayList<OnnxTensor>()

        fun tensors(): List<Pair<String, OnnxTensor>> = PAST_NAMES.map { name ->
            val tensor = if (result == null) {
                OnnxTensor.createTensor(env, FloatBuffer.allocate(0), EMPTY_SHAPE)
                    .also { empties.add(it) }
            } else {
                result.get(name.replace("past_key_values", "present")).get() as OnnxTensor
            }
            name to tensor
        }

        inline fun <T> use(block: (Past) -> T): T = try {
            block(this)
        } finally {
            close()
        }

        fun close() {
            empties.forEach { runCatching { it.close() } }
            empties.clear()
            runCatching { cacheFalse.close() }
            runCatching { cacheTrue.close() }
            runCatching { result?.close() }
        }
    }

    @Synchronized
    fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        encoder = null
        decoder = null
        tokenizer = null
        config = null
    }

    private companion object {
        const val TAG = "WhisperOnnxEngine"

        const val DIR = "whisper-tiny"
        const val ENCODER = "encoder_model_int8.onnx"
        const val DECODER = "decoder_model_merged_int8.onnx"
        const val VOCAB = "vocab.json"
        const val GEN_CONFIG = "generation_config.json"

        const val N_LAYERS = 4
        const val N_HEADS = 6L
        const val HEAD_DIM = 64L

        /** One 30 s window cannot say more than this; guards against a runaway loop. */
        const val MAX_NEW_TOKENS = 224

        val EMPTY_SHAPE = longArrayOf(1, N_HEADS, 0, HEAD_DIM)

        val PAST_NAMES: List<String> = buildList {
            for (i in 0 until N_LAYERS) {
                for (kind in listOf("decoder", "encoder")) {
                    for (kv in listOf("key", "value")) add("past_key_values.$i.$kind.$kv")
                }
            }
        }
    }
}
