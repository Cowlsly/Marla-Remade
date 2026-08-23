package com.vayunmathur.translate.platform

import android.content.Context
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File

/**
 * Runtime-download config for the on-device **SMaLL-100** translation model
 * (converted to ncnn). Quantization attempt:
 * - int4 weight-only block quant via ncnnllm (mseclip b64) produced 680 MB vs
 *   1.14 GB fp16 (36 enc + 13 dec Gemm+MHA layers, term 401), Embed stays fp16
 *   (262 MB ×2 floor).
 * - **CRASH**: int4 MHA `forward_weight_block_quantize` triggered MTE
 *   SEGV_MTESERR in `libncnn_android.so` at `multiheadattention.cpp:793`
 *   (Pixel 9 Pro XL, Android 17, OpenMP worker). Tombstone 28:
 *   `pc 0x490224 -> forward_weight_block_quantize (.omp_outlined_debug__.33)`.
 *   Root cause: ncnn int4 block-quant MHA not MTE-clean (packed int4 unpack may
 *   read out-of-tag). Roll back to fp16 stable; alternative path: Gemm-only
 *   int4 or classic Embed int8 via ncnn2int8.
 *
 * Files are fetched mirror-only from `data.vayunmathur.com/models/small100/`
 * with SHA-256 pinning via [downloadModels]. Auto-install via
 * `InitialModelDownloadChecker` in MainActivity (like OpenAssistant).
 */
object Small100Model {
    private const val BASE = "https://data.vayunmathur.com/models/small100/"
    const val DIR = "small100"

    /** The 7 runtime files — fp16 stable (int4 MHA crashes on Pixel 9 MTE, see above). */
    val FILES: List<ModelDownloadItem> = listOf(
        item("encoder.ncnn.param", "4c86bc19318933169474ddab957c7031cbce48d95c06d3159d487e6a941959c8"),
        item("encoder.ncnn.bin", "06d34fe528960b8f5246592d99b4bfaf27b164fc4a49bd5a13d95eadb87d13a2"),
        item("decoder.ncnn.param", "e3fd9b9be770d93a022d98c8b29f5ed603e08b04be424da8480cacfce00467bf"),
        item("decoder.ncnn.bin", "2bedd93dc073cb1840c563acfa5e70816893c6fbf678a538fdad68f90325ec70"),
        item("sentencepiece.bpe.model", "d8f7c76ed2a5e0822be39f0a4f95a55eb19c78f4593ce609e2edbc2aea4d380a"),
        item("vocab.txt", "84733eadc2b3f2a21c55687336ca538e55650233bcc729650cd53c2d2fc77319"),
        item("pos_weights.f32.bin", "254e2cf622a8e498df8600c5052ec492129b5ca8932ada1e514a091c26f9dd80"),
    )

    private fun item(name: String, sha256: String) =
        ModelDownloadItem("$BASE$name", "$DIR/$name", "SMaLL-100 $name", sha256)

    /** Directory the ncnn `Small100` loads from. */
    fun modelDir(context: Context): File = File(context.getExternalFilesDir(null), DIR)

    /** True once every model file is present on disk. */
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
     * only the target language (it's prepended to the source), so no source id is
     * required. Derived from [LANG_ORDER] rather than transcribed; the values match
     * `lang_tokens` in the model's upstream `mobile_manifest.json`.
     */
    val LANG_ID: Map<String, Int> =
        LANG_ORDER.withIndex().associate { (index, code) -> code to FIRST_LANG_ID + index }
}
