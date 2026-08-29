package com.vayunmathur.translate.platform

import android.content.Context
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.ml.Small100Handle
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File

/**
 * Runtime-download config for the on-device **SMaLL-100** translation model.
 *
 * Two files, 320 MB: `small100.maml` and `tokenizer.bin`. Rebuild both byte for byte with
 * `python scripts/ml/fetch_small100.py`, which pins `alirezamsh/small100` by revision and per-file
 * SHA-256 and prints the digests below.
 *
 * # It used to be 1.14 GB across seven files
 *
 * The previous build was ncnn, and fp16, because ncnn could not quantise the 131M-parameter
 * embedding — 262 MB of fp16 per net, twice over. An int4 weight-block attempt to fix that crashed:
 * `forward_weight_block_quantize` triggered a tagged-memory fault, `SEGV_MTESERR` in
 * `libncnn_android.so` at `multiheadattention.cpp:793`, on a Pixel 9 Pro XL. So the size and the
 * only two MTE faults this tree has seen had the same cause, and both are gone with the dependency:
 * the model now runs on `:library:ml`'s own Vulkan runtime, quantised int8 per output channel.
 *
 * Files are fetched mirror-only from `data.vayunmathur.com/models/small100/` with SHA-256 pinning
 * via [downloadModels]. Auto-install via `InitialModelDownloadChecker` in MainActivity.
 */
object Small100Model {
    private const val BASE = "https://data.vayunmathur.com/models/small100/"
    const val DIR = "small100"

    /** The 2 runtime files. Names and order come from [Small100Handle.FILES]. */
    val FILES: List<ModelDownloadItem> = listOf(
        item(
            Small100Handle.GRAPH,
            "0c7f64de141874d062d25043f9391c669d6fef62cd2763220f2c6eb50e68fa47",
        ),
        item(
            Small100Handle.TOKENIZER,
            "b6556bd9f5db3b08977e4db430d3d5fc8301891f74e58018da637ba6068a4b16",
        ),
    )

    /**
     * The seven ncnn files, deleted from an existing install the first time this runs.
     *
     * Without this an upgrade leaves 1.14 GB of unreachable weights in the app's external files
     * directory, which nothing else will ever remove. Kept as names rather than a wildcard so a
     * future file of ours cannot be caught by it.
     */
    private val RETIRED = listOf(
        "encoder.ncnn.param",
        "encoder.ncnn.bin",
        "decoder.ncnn.param",
        "decoder.ncnn.bin",
        "sentencepiece.bpe.model",
        "vocab.txt",
        "pos_weights.f32.bin",
    )

    private fun item(name: String, sha256: String) =
        ModelDownloadItem("$BASE$name", "$DIR/$name", "SMaLL-100 $name", sha256)

    /** Directory [Small100Handle.inDirectory] loads from. */
    fun modelDir(context: Context): File = File(context.getExternalFilesDir(null), DIR)

    /** True once both model files are present on disk. */
    fun isDownloaded(context: Context): Boolean {
        val root = context.getExternalFilesDir(null) ?: return false
        return FILES.all { File(root, it.fileName).exists() }
    }

    /** Download any missing files (skips present ones); suspends until complete. */
    suspend fun download(context: Context, ds: DataStoreUtils) = downloadModels(context, ds, FILES)

    /** Averaged 0..1 download progress across the files, read from DataStore. */
    fun progress(ds: DataStoreUtils): Float =
        (FILES.map { ds.getDouble("progress_${it.fileName}") ?: 0.0 }.average()).toFloat()

    /**
     * Delete the ncnn model an earlier version downloaded, returning the bytes reclaimed.
     *
     * Safe to call at any time and cheap when there is nothing to do, which is every run after the
     * first. See [RETIRED].
     */
    fun deleteRetired(context: Context): Long {
        val directory = modelDir(context)
        var reclaimed = 0L
        for (name in RETIRED) {
            val file = File(directory, name)
            if (!file.isFile) continue
            val size = file.length()
            if (file.delete()) reclaimed += size
        }
        return reclaimed
    }

    /**
     * The 100 languages SMaLL-100 was trained on, in fairseq dictionary order (which is
     * plain lexicographic order of these codes). `ast`, `ceb`, `ilo` and `ns` are
     * ISO-639-3 rather than -1, so [com.vayunmathur.translate.platform.TtsSpeaker] will
     * report a missing voice for them.
     */
    private val LANG_ORDER = listOf(
        "af", "am", "ar", "ast", "az", "ba", "be", "bg", "bn", "br",
        "bs", "ca", "ceb", "cs", "cy", "da", "de", "el", "en", "es",
        "et", "fa", "ff", "fi", "fr", "fy", "ga", "gd", "gl", "gu",
        "ha", "he", "hi", "hr", "ht", "hu", "hy", "id", "ig", "ilo",
        "is", "it", "ja", "jv", "ka", "kk", "km", "kn", "ko", "lb",
        "lg", "ln", "lo", "lt", "lv", "mg", "mk", "ml", "mn", "mr",
        "ms", "my", "ne", "nl", "no", "ns", "oc", "or", "pa", "pl",
        "ps", "pt", "ro", "ru", "sd", "si", "sk", "sl", "so", "sq",
        "sr", "ss", "su", "sv", "sw", "ta", "th", "tl", "tn", "tr",
        "uk", "ur", "uz", "vi", "wo", "xh", "yi", "yo", "zh", "zu",
    )

    /** Token id of the first language in [LANG_ORDER]; the rest follow contiguously. */
    private const val FIRST_LANG_ID = 128004

    /**
     * SMaLL-100 target-language token ids (`128004 + fairseq index`). SMaLL-100 needs
     * only the target language — it is prepended to the **source**, not forced on the
     * decoder — so no source id is required. Derived from [LANG_ORDER] rather than
     * transcribed; the values match `lang_tokens.json` in the upstream export, and
     * `post::translate::FIRST_LANG_TOKEN` asserts the same base natively.
     */
    val LANG_ID: Map<String, Int> =
        LANG_ORDER.withIndex().associate { (index, code) -> code to FIRST_LANG_ID + index }
}
