package com.vayunmathur.translate.platform

import android.content.Context
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.ml.NllbHandle
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File

/**
 * Runtime-download config for the on-device **NLLB-200-distilled-600M** translation model.
 *
 * Two files: `nllb600.maml` and `tokenizer.bin`, produced by model-eng's conversion
 * pipeline (`scripts/ml/fetch_nllb600.py`, pinning `facebook/nllb-200-distilled-600M`).
 * NLLB distilled to 600M parameters: 12 encoder layers, 12 decoder layers, `d_model`
 * 1024, 16 heads, a 4096-wide ReLU feed-forward, and a 256,206-entry vocabulary shared
 * between the input embedding and the output projection.
 *
 * The model runs on `:library:ml`'s own Vulkan runtime. Native checks the maml's graph
 * id (18, `graph::NLLB`), so a wrong file fails at load.
 *
 * Files are fetched mirror-only from `data.vayunmathur.com/models/nllb600/` via
 * [downloadModels]. Auto-install via `InitialModelDownloadChecker` in MainActivity.
 */
object NllbModel {
    private const val BASE = "https://data.vayunmathur.com/models/nllb600/"
    const val DIR = "nllb600"

    /** The 2 runtime files, SHA-256 pinned. Names and order come from [NllbHandle.FILES]. */
    val FILES: List<ModelDownloadItem> = listOf(
        item(
            NllbHandle.GRAPH,
            // 617,059,520 bytes, verified against build/nllb600/nllb600.maml.
            "1f08cebe3cb6e629fc40fbc93c71c82c4e23cd379fe0da798ebc817cc769553c",
        ),
        item(
            NllbHandle.TOKENIZER,
            // 3,849,114 bytes, verified against build/nllb600/tokenizer.bin.
            "36a6bed003d4a66cb9a513d1056355fe4bc1c73518cff82477671811fd482b57",
        ),
    )

    /**
     * The retired SMaLL-100 files, deleted from an existing install the first time this
     * runs: the two maml-era files plus the seven ncnn files an earlier version
     * downloaded.
     *
     * Without this an upgrade leaves ~320 MB of unreachable weights in the app's
     * external files directory, which nothing else will ever remove. Kept as names
     * rather than a wildcard so a future file of ours cannot be caught by it.
     */
    private val RETIRED_DIR = "small100"
    private val RETIRED = listOf(
        "small100.maml",
        "tokenizer.bin",
        "encoder.ncnn.param",
        "encoder.ncnn.bin",
        "decoder.ncnn.param",
        "decoder.ncnn.bin",
        "sentencepiece.bpe.model",
        "vocab.txt",
        "pos_weights.f32.bin",
    )

    private fun item(name: String, sha256: String?) =
        ModelDownloadItem("$BASE$name", "$DIR/$name", "NLLB-200 $name", sha256)

    /** Directory [NllbHandle.inDirectory] loads from. */
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
     * Delete the SMaLL-100 model an earlier version downloaded, returning the bytes
     * reclaimed. Removes the directory itself when it ends up empty.
     *
     * Safe to call at any time and cheap when there is nothing to do, which is every run
     * after the first. See [RETIRED].
     */
    fun deleteRetired(context: Context): Long {
        val root = context.getExternalFilesDir(null) ?: return 0L
        val directory = File(root, RETIRED_DIR)
        var reclaimed = 0L
        for (name in RETIRED) {
            val file = File(directory, name)
            if (!file.isFile) continue
            val size = file.length()
            if (file.delete()) reclaimed += size
        }
        if (directory.isDirectory && (directory.list()?.isEmpty() == true)) {
            directory.delete()
        }
        return reclaimed
    }

    /**
     * The 202 flores200 codes in token-id order: `FLORES_ORDER[0]` is
     * [FIRST_TOKEN_ID], the rest follow contiguously. Order and ids come from the
     * tokenizer's added tokens (`tokenizer.json`), verified against the cached
     * `facebook/nllb-200-distilled-600M` snapshot: 202 language tokens at exactly
     * 256001..256202, then `<mask>` at 256203. Native asserts the same range in
     * `post::translate::is_nllb_lang_token`.
     */
    private val FLORES_ORDER = listOf(
        "ace_Arab",
        "ace_Latn",
        "acm_Arab",
        "acq_Arab",
        "aeb_Arab",
        "afr_Latn",
        "ajp_Arab",
        "aka_Latn",
        "amh_Ethi",
        "apc_Arab",
        "arb_Arab",
        "ars_Arab",
        "ary_Arab",
        "arz_Arab",
        "asm_Beng",
        "ast_Latn",
        "awa_Deva",
        "ayr_Latn",
        "azb_Arab",
        "azj_Latn",
        "bak_Cyrl",
        "bam_Latn",
        "ban_Latn",
        "bel_Cyrl",
        "bem_Latn",
        "ben_Beng",
        "bho_Deva",
        "bjn_Arab",
        "bjn_Latn",
        "bod_Tibt",
        "bos_Latn",
        "bug_Latn",
        "bul_Cyrl",
        "cat_Latn",
        "ceb_Latn",
        "ces_Latn",
        "cjk_Latn",
        "ckb_Arab",
        "crh_Latn",
        "cym_Latn",
        "dan_Latn",
        "deu_Latn",
        "dik_Latn",
        "dyu_Latn",
        "dzo_Tibt",
        "ell_Grek",
        "eng_Latn",
        "epo_Latn",
        "est_Latn",
        "eus_Latn",
        "ewe_Latn",
        "fao_Latn",
        "pes_Arab",
        "fij_Latn",
        "fin_Latn",
        "fon_Latn",
        "fra_Latn",
        "fur_Latn",
        "fuv_Latn",
        "gla_Latn",
        "gle_Latn",
        "glg_Latn",
        "grn_Latn",
        "guj_Gujr",
        "hat_Latn",
        "hau_Latn",
        "heb_Hebr",
        "hin_Deva",
        "hne_Deva",
        "hrv_Latn",
        "hun_Latn",
        "hye_Armn",
        "ibo_Latn",
        "ilo_Latn",
        "ind_Latn",
        "isl_Latn",
        "ita_Latn",
        "jav_Latn",
        "jpn_Jpan",
        "kab_Latn",
        "kac_Latn",
        "kam_Latn",
        "kan_Knda",
        "kas_Arab",
        "kas_Deva",
        "kat_Geor",
        "knc_Arab",
        "knc_Latn",
        "kaz_Cyrl",
        "kbp_Latn",
        "kea_Latn",
        "khm_Khmr",
        "kik_Latn",
        "kin_Latn",
        "kir_Cyrl",
        "kmb_Latn",
        "kon_Latn",
        "kor_Hang",
        "kmr_Latn",
        "lao_Laoo",
        "lvs_Latn",
        "lij_Latn",
        "lim_Latn",
        "lin_Latn",
        "lit_Latn",
        "lmo_Latn",
        "ltg_Latn",
        "ltz_Latn",
        "lua_Latn",
        "lug_Latn",
        "luo_Latn",
        "lus_Latn",
        "mag_Deva",
        "mai_Deva",
        "mal_Mlym",
        "mar_Deva",
        "min_Latn",
        "mkd_Cyrl",
        "plt_Latn",
        "mlt_Latn",
        "mni_Beng",
        "khk_Cyrl",
        "mos_Latn",
        "mri_Latn",
        "zsm_Latn",
        "mya_Mymr",
        "nld_Latn",
        "nno_Latn",
        "nob_Latn",
        "npi_Deva",
        "nso_Latn",
        "nus_Latn",
        "nya_Latn",
        "oci_Latn",
        "gaz_Latn",
        "ory_Orya",
        "pag_Latn",
        "pan_Guru",
        "pap_Latn",
        "pol_Latn",
        "por_Latn",
        "prs_Arab",
        "pbt_Arab",
        "quy_Latn",
        "ron_Latn",
        "run_Latn",
        "rus_Cyrl",
        "sag_Latn",
        "san_Deva",
        "sat_Beng",
        "scn_Latn",
        "shn_Mymr",
        "sin_Sinh",
        "slk_Latn",
        "slv_Latn",
        "smo_Latn",
        "sna_Latn",
        "snd_Arab",
        "som_Latn",
        "sot_Latn",
        "spa_Latn",
        "als_Latn",
        "srd_Latn",
        "srp_Cyrl",
        "ssw_Latn",
        "sun_Latn",
        "swe_Latn",
        "swh_Latn",
        "szl_Latn",
        "tam_Taml",
        "tat_Cyrl",
        "tel_Telu",
        "tgk_Cyrl",
        "tgl_Latn",
        "tha_Thai",
        "tir_Ethi",
        "taq_Latn",
        "taq_Tfng",
        "tpi_Latn",
        "tsn_Latn",
        "tso_Latn",
        "tuk_Latn",
        "tum_Latn",
        "tur_Latn",
        "twi_Latn",
        "tzm_Tfng",
        "uig_Arab",
        "ukr_Cyrl",
        "umb_Latn",
        "urd_Arab",
        "uzn_Latn",
        "vec_Latn",
        "vie_Latn",
        "war_Latn",
        "wol_Latn",
        "xho_Latn",
        "ydd_Hebr",
        "yor_Latn",
        "yue_Hant",
        "zho_Hans",
        "zho_Hant",
        "zul_Latn",
    )

    /** Token id of the first language in [FLORES_ORDER]; the rest follow contiguously. */
    private const val FIRST_TOKEN_ID = 256001

    /**
     * NLLB language token ids by flores200 code (`256001 + flores index`). NLLB needs
     * BOTH the source and the target token - the source token leads the encoder input
     * and the target token forced-BOSes the decoder - so unlike SMaLL-100 there is no
     * target-only table. Derived from [FLORES_ORDER] rather than transcribed.
     */
    private val TOKEN_ID: Map<String, Int> =
        FLORES_ORDER.withIndex().associate { (index, code) -> code to FIRST_TOKEN_ID + index }

    /** The NLLB language token for [flores], or null for a code the model has no token for. */
    fun tokenId(flores: String): Int? = TOKEN_ID[flores]
}
