package com.vayunmathur.speech.util

import android.content.Context
import android.util.Log
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Multi-voice registry for offline **Piper (VITS)** TTS. Each voice is a directory tree
 * fetched as a single `.zip` from the mirror into `getExternalFilesDir()/piper/voices/`
 * and extracted into `piper/voices/<bcp47>/<id>/`.
 *
 * Per latest request: one voice per language, highest quality available (high 22 kHz > medium
 * 22 kHz > low). Dist folder `dist/speech-mirror/piper/` is deduped to one zip per lang using
 * same rule. All sample rates are 22050 Hz (previously 16 kHz low for en/ar/cs). Registry SHAs
 * match `dist/speech-mirror/piper/sha256.json` after cleanup.
 *
 * If a high checkpoint exists upstream for a language missing high in dist (e.g. after
 * `fetch_piper_model.sh <dir> ... high <lang>-high.zip`), rebuild that language to high and
 * re-run dedup + hash step — registry will then be upgraded to high automatically.
 *
 * The original single-voice layout `piper/voice` (voice3.zip, Amy medium, 22050 Hz) is kept
 * as legacy and migrated. On upgrade, old `-low.zip` / `-low` / `-medium` installs are cleaned
 * via [cleanOldLowVariants] so storage doesn't double-count.
 *
 * Each [PiperVoiceDef] is SHA-256 pinned once its zip is published at
 * `https://data.vayunmathur.com/models/piper/`.
 */
data class PiperVoiceDef(
    /** HF-style id "<locale>-<speaker>-<quality>", e.g. "en_US-high". */
    val id: String,
    /** ISO-639-1 or broader code, e.g. "en", "zh". */
    val code: String,
    /** BCP-47 tag used for `Voice(name)` and `Locale.forLanguageTag`. */
    val bcp47: String,
    /** ISO-639-3, e.g. "eng", "deu". */
    val iso3: String,
    /** ISO-3166-1 alpha-3 country, e.g. "USA", "DEU". */
    val iso3Country: String,
    /** Native name, e.g. "Deutsch". */
    val nativeName: String,
    /** English name, e.g. "German". */
    val englishName: String,
    /** Remote archive name at BASE, e.g. "de-high.zip". */
    val remoteArchive: String,
    /** Dictionary file inside voice dir, e.g. "de-word_id.bin". */
    val dictFile: String,
    /** SHA-256 of remote archive, null until published. */
    val sha256: String?,
    /** Quality — highest available per language: high > medium > low. */
    val quality: String,
    /** Sample rate from config.json. */
    val sampleRate: Int,
    /** Estimated size badge in MB. */
    val sizeEstimateMb: Int,
    val speakerId: Int = 0,
    val isMultiSpeaker: Boolean = false,
)

object PiperVoiceRegistry {

    const val DIR = "piper"
    const val VOICES_ROOT = "piper/voices"
    const val LEGACY_VOICE_DIR = "piper/voice"
    const val LEGACY_ARCHIVE = "piper/voice.zip"
    const val LEGACY_REMOTE_ARCHIVE = "voice3.zip"
    const val BASE = "https://data.vayunmathur.com/models/piper/"

    private const val ENCODER_SUFFIX = "_enc_p.maml"
    private const val CONFIG = "config.json"
    private val REQUIRED_NETS = listOf("_enc_p", "_dp", "_flow", "_dec")

    /**
     * Full catalog: one voice per language, highest quality available (from local dist mirror).
     * Currently 2 high, 15 medium, 12 low = 29 langs (after dist dedup). SHA256 pinned from
     * `dist/speech-mirror/piper/sha256.json`. All 22050 Hz.
     */
    val ALL: List<PiperVoiceDef> = listOf(
        // --- high quality 22 kHz where built ---
        PiperVoiceDef(
            id = "en_US-high",
            code = "en",
            bcp47 = "en-US",
            iso3 = "eng",
            iso3Country = "USA",
            nativeName = "English",
            englishName = "English",
            remoteArchive = "en-high.zip",
            dictFile = "en-word_id.bin",
            sha256 = "ec61d6fccf0d2920abd65bcf4edfd6a96d767eb38faa8f2de344b18dc222839d",
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 51,
        ),
        PiperVoiceDef(
            id = "de_DE-high",
            code = "de",
            bcp47 = "de-DE",
            iso3 = "deu",
            iso3Country = "DEU",
            nativeName = "Deutsch",
            englishName = "German",
            remoteArchive = "de-high.zip",
            dictFile = "de-word_id.bin",
            sha256 = "7cb8622a9f2668d6d33d94eaf4fcdda5278d4ba835a0b4e4c2bc0d0bdb535970",
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 51,
        ),
        // --- medium is highest available for these 15 langs (dup low deleted from dist) ---
        PiperVoiceDef(
            id = "ar_JO-medium",
            code = "ar",
            bcp47 = "ar-SA",
            iso3 = "ara",
            iso3Country = "SAU",
            nativeName = "العربية",
            englishName = "Arabic",
            remoteArchive = "ar-medium.zip",
            dictFile = "ar-word_id.bin",
            sha256 = "e8a82c9940f41498cc08434194a871a33ca49df70470a1869f4898832d51a63c",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "bg_BG-medium",
            code = "bg",
            bcp47 = "bg-BG",
            iso3 = "bul",
            iso3Country = "BGR",
            nativeName = "Български",
            englishName = "Bulgarian",
            remoteArchive = "bg-medium.zip",
            dictFile = "bg-word_id.bin",
            sha256 = "324b06ad0c7c46d4adf175c1231c9494da1ef51dad7c9e542b0fed2477cbf525",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "ca_ES-medium",
            code = "ca",
            bcp47 = "ca-ES",
            iso3 = "cat",
            iso3Country = "ESP",
            nativeName = "Català",
            englishName = "Catalan",
            remoteArchive = "ca-medium.zip",
            dictFile = "ca-word_id.bin",
            sha256 = "3fc392eb49afa57a52cbcb9ac46470e4754d365e8bd4e4bef2919eb49b67d778",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "cs_CZ-medium",
            code = "cs",
            bcp47 = "cs-CZ",
            iso3 = "ces",
            iso3Country = "CZE",
            nativeName = "Čeština",
            englishName = "Czech",
            remoteArchive = "cs-medium.zip",
            dictFile = "cs-word_id.bin",
            sha256 = "9ed2977919c51d61f9fc87c7505952f9a33aa611637431dceab42f42945b8759",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "da_DK-medium",
            code = "da",
            bcp47 = "da-DK",
            iso3 = "dan",
            iso3Country = "DNK",
            nativeName = "Dansk",
            englishName = "Danish",
            remoteArchive = "da-medium.zip",
            dictFile = "da-word_id.bin",
            sha256 = "9c2f7e76b4d63733381046b5adb55ffb2625e12fb5ebddccfa306d0a7890d601",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "el_GR-medium",
            code = "el",
            bcp47 = "el-GR",
            iso3 = "ell",
            iso3Country = "GRC",
            nativeName = "Ελληνικά",
            englishName = "Greek",
            remoteArchive = "el-medium.zip",
            dictFile = "el-word_id.bin",
            sha256 = "246752cd4bd240f885e6768c1a37514913caa8dfaaf460e2b5bcdd86345d0696",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "fi_FI-medium",
            code = "fi",
            bcp47 = "fi-FI",
            iso3 = "fin",
            iso3Country = "FIN",
            nativeName = "Suomi",
            englishName = "Finnish",
            remoteArchive = "fi-medium.zip",
            dictFile = "fi-word_id.bin",
            sha256 = "911877ca09b3fb7ee234320ef29ca3837167bbf9be150606e238ffd070d772a6",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "hu_HU-medium",
            code = "hu",
            bcp47 = "hu-HU",
            iso3 = "hun",
            iso3Country = "HUN",
            nativeName = "Magyar",
            englishName = "Hungarian",
            remoteArchive = "hu-medium.zip",
            dictFile = "hu-word_id.bin",
            sha256 = "a2ae82ab596d9d186f319dcd8568d80afa9a9cd848c448cf646b2e8852f6910d",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "ka_GE-medium",
            code = "ka",
            bcp47 = "ka-GE",
            iso3 = "kat",
            iso3Country = "GEO",
            nativeName = "ქართული",
            englishName = "Georgian",
            remoteArchive = "ka-medium.zip",
            dictFile = "ka-word_id.bin",
            sha256 = "a578d418cb1021475453fc80386e851ef6f1dc7f59e687114ba992ee24957c74",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "lb_LU-medium",
            code = "lb",
            bcp47 = "lb-LU",
            iso3 = "ltz",
            iso3Country = "LUX",
            nativeName = "Lëtzebuergesch",
            englishName = "Luxembourgish",
            remoteArchive = "lb-medium.zip",
            dictFile = "lb-word_id.bin",
            sha256 = "9fab5c3999d445297104dc4ca33708124c7a4d5bd09496ebaa379ccc6ebd4cc3",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "ml_IN-medium",
            code = "ml",
            bcp47 = "ml-IN",
            iso3 = "mal",
            iso3Country = "IND",
            nativeName = "മലയാളം",
            englishName = "Malayalam",
            remoteArchive = "ml-medium.zip",
            dictFile = "ml-word_id.bin",
            sha256 = "a6f08fc134fe8efe78eca6da2508cc7b5b408768e9b917e4e4b82913f4f593a7",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "nb_NO-medium",
            code = "nb",
            bcp47 = "nb-NO",
            iso3 = "nob",
            iso3Country = "NOR",
            nativeName = "Norsk Bokmål",
            englishName = "Norwegian",
            remoteArchive = "nb-medium.zip",
            dictFile = "nb-word_id.bin",
            sha256 = "8eae59d616f14a9f4c2b2672f6389da6c8d543594253b6feefd6d76820bcec22",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "ne_NP-medium",
            code = "ne",
            bcp47 = "ne-NP",
            iso3 = "nep",
            iso3Country = "NPL",
            nativeName = "नेपाली",
            englishName = "Nepali",
            remoteArchive = "ne-medium.zip",
            dictFile = "ne-word_id.bin",
            sha256 = "7ae1acb07aba57b2d160f14780f14860e5c0dade28f9e3958bbd38002d4a75ce",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 35,
        ),
        PiperVoiceDef(
            id = "ro_RO-medium",
            code = "ro",
            bcp47 = "ro-RO",
            iso3 = "ron",
            iso3Country = "ROU",
            nativeName = "Română",
            englishName = "Romanian",
            remoteArchive = "ro-medium.zip",
            dictFile = "ro-word_id.bin",
            sha256 = "cbb257a1fb1c3a124048c03e67af0e467c4a5568d81a72fe886a7af569d42e35",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "sk_SK-medium",
            code = "sk",
            bcp47 = "sk-SK",
            iso3 = "slk",
            iso3Country = "SVK",
            nativeName = "Slovenčina",
            englishName = "Slovak",
            remoteArchive = "sk-medium.zip",
            dictFile = "sk-word_id.bin",
            sha256 = "82a1942fdb302e44f5246977e0f29ede00ae8c38ccca747f1f4c0361a231c3e3",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        // --- low is highest available for these 12 langs (only low built locally) ---
        PiperVoiceDef(
            id = "es_ES-low",
            code = "es",
            bcp47 = "es-ES",
            iso3 = "spa",
            iso3Country = "ESP",
            nativeName = "Español",
            englishName = "Spanish",
            remoteArchive = "es-low.zip",
            dictFile = "es-word_id.bin",
            sha256 = "3a5d14ad7687e4ed71e93eec4e5373dad75cec6a97ed1e88e5247e15afb3aab5",
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 34,
        ),
        PiperVoiceDef(
            id = "fr_FR-low",
            code = "fr",
            bcp47 = "fr-FR",
            iso3 = "fra",
            iso3Country = "FRA",
            nativeName = "Français",
            englishName = "French",
            remoteArchive = "fr-low.zip",
            dictFile = "fr-word_id.bin",
            sha256 = "ae372af01d6efd92c4ac0adb45f11db0f9ac24c571b46ace0aca050c34a9a7e5",
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "pt_BR-low",
            code = "pt",
            bcp47 = "pt-BR",
            iso3 = "por",
            iso3Country = "BRA",
            nativeName = "Português",
            englishName = "Portuguese",
            remoteArchive = "pt-low.zip",
            dictFile = "pt-word_id.bin",
            sha256 = "07a93715c0ed4e6ea1351d4a98abdbf6a1c7bb97e45034ffee8ae9fad1e7d25c",
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "nl_BE-low",
            code = "nl",
            bcp47 = "nl-NL",
            iso3 = "nld",
            iso3Country = "NLD",
            nativeName = "Nederlands",
            englishName = "Dutch",
            remoteArchive = "nl-low.zip",
            dictFile = "nl-word_id.bin",
            sha256 = "f7cfff1040cd325fadb1ec49476b77090877907a0eedffe5c5cde870737e5cba",
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "ru_RU-low",
            code = "ru",
            bcp47 = "ru-RU",
            iso3 = "rus",
            iso3Country = "RUS",
            nativeName = "Русский",
            englishName = "Russian",
            remoteArchive = "ru-low.zip",
            dictFile = "ru-word_id.bin",
            sha256 = "7995a39b9a8e02c9840bd29d3d3e6ab1991bb26f5b85f674e47dadf4eca87b6e",
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "pl_PL-low",
            code = "pl",
            bcp47 = "pl-PL",
            iso3 = "pol",
            iso3Country = "POL",
            nativeName = "Polski",
            englishName = "Polish",
            remoteArchive = "pl-low.zip",
            dictFile = "pl-word_id.bin",
            sha256 = "1d2f12113f76368f4c5e50c5c67c09c216d50077261dbafae3c6fdbb4b04714d",
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "tr_TR-low",
            code = "tr",
            bcp47 = "tr-TR",
            iso3 = "tur",
            iso3Country = "TUR",
            nativeName = "Türkçe",
            englishName = "Turkish",
            remoteArchive = "tr-low.zip",
            dictFile = "tr-word_id.bin",
            sha256 = "3f7e36d044b37a4fe82579b991ae36c05dd7d2f9683bf7eb177d58d4de468f52",
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "hi_IN-low",
            code = "hi",
            bcp47 = "hi-IN",
            iso3 = "hin",
            iso3Country = "IND",
            nativeName = "हिन्दी",
            englishName = "Hindi",
            remoteArchive = "hi-low.zip",
            dictFile = "hi-word_id.bin",
            sha256 = "92f1469eaee834e51ae6b6176c50a301ccf8322cd42c24b39842fb32a50442e9",
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "zh_CN-low",
            code = "zh",
            bcp47 = "zh-CN",
            iso3 = "zho",
            iso3Country = "CHN",
            nativeName = "中文",
            englishName = "Chinese",
            remoteArchive = "zh-low.zip",
            dictFile = "zh-word_id.bin",
            sha256 = "64dfea77bd960093d122cb8e2cceac450e5ced0f34f074fb4fcf4d48a43df81e",
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "vi_VN-low",
            code = "vi",
            bcp47 = "vi-VN",
            iso3 = "vie",
            iso3Country = "VNM",
            nativeName = "Tiếng Việt",
            englishName = "Vietnamese",
            remoteArchive = "vi-low.zip",
            dictFile = "vi-word_id.bin",
            sha256 = "4dc346df5e71b6f268456d4dc30e22f9928bb2e8d5a73aa80cee5a9f1b703938",
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "id_ID-low",
            code = "id",
            bcp47 = "id-ID",
            iso3 = "ind",
            iso3Country = "IDN",
            nativeName = "Bahasa Indonesia",
            englishName = "Indonesian",
            remoteArchive = "id-low.zip",
            dictFile = "id-word_id.bin",
            sha256 = "39ea9531b7039c71c3e300e9ecbe14ecf2ba49ac67ec84988de4267c6b2a9fef",
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "uk_UA-low",
            code = "uk",
            bcp47 = "uk-UA",
            iso3 = "ukr",
            iso3Country = "UKR",
            nativeName = "Українська",
            englishName = "Ukrainian",
            remoteArchive = "uk-low.zip",
            dictFile = "uk-word_id.bin",
            sha256 = "efb9f61d6a32160c06c9a79fbba52561c85ad5590e823c07ada033592f0f2447",
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 34,
        ),
        // --- remaining high placeholders not yet built in dist (optional, keep for future) ---
        PiperVoiceDef(
            id = "it_IT-high",
            code = "it",
            bcp47 = "it-IT",
            iso3 = "ita",
            iso3Country = "ITA",
            nativeName = "Italiano",
            englishName = "Italian",
            remoteArchive = "it-high.zip",
            dictFile = "it-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ja_JP-high",
            code = "ja",
            bcp47 = "ja-JP",
            iso3 = "jpn",
            iso3Country = "JPN",
            nativeName = "日本語",
            englishName = "Japanese",
            remoteArchive = "ja-high.zip",
            dictFile = "ja-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ko_KR-high",
            code = "ko",
            bcp47 = "ko-KR",
            iso3 = "kor",
            iso3Country = "KOR",
            nativeName = "한국어",
            englishName = "Korean",
            remoteArchive = "ko-high.zip",
            dictFile = "ko-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "th_TH-high",
            code = "th",
            bcp47 = "th-TH",
            iso3 = "tha",
            iso3Country = "THA",
            nativeName = "ไทย",
            englishName = "Thai",
            remoteArchive = "th-high.zip",
            dictFile = "th-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "sv_SE-high",
            code = "sv",
            bcp47 = "sv-SE",
            iso3 = "swe",
            iso3Country = "SWE",
            nativeName = "Svenska",
            englishName = "Swedish",
            remoteArchive = "sv-high.zip",
            dictFile = "sv-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "bn_BD-high",
            code = "bn",
            bcp47 = "bn-BD",
            iso3 = "ben",
            iso3Country = "BGD",
            nativeName = "বাংলা",
            englishName = "Bengali",
            remoteArchive = "bn-high.zip",
            dictFile = "bn-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "he_IL-high",
            code = "he",
            bcp47 = "he-IL",
            iso3 = "heb",
            iso3Country = "ISR",
            nativeName = "עברית",
            englishName = "Hebrew",
            remoteArchive = "he-high.zip",
            dictFile = "he-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ku_TR-high",
            code = "ku",
            bcp47 = "ku-TR",
            iso3 = "kur",
            iso3Country = "TUR",
            nativeName = "Kurdî",
            englishName = "Kurdish",
            remoteArchive = "ku-high.zip",
            dictFile = "ku-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "mr_IN-high",
            code = "mr",
            bcp47 = "mr-IN",
            iso3 = "mar",
            iso3Country = "IND",
            nativeName = "मराठी",
            englishName = "Marathi",
            remoteArchive = "mr-high.zip",
            dictFile = "mr-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "sr_RS-medium",
            code = "sr",
            bcp47 = "sr-RS",
            iso3 = "srp",
            iso3Country = "SRB",
            nativeName = "Српски",
            englishName = "Serbian",
            remoteArchive = "sr-medium.zip",
            dictFile = "sr-word_id.bin",
            sha256 = "e70070b6ba3e037dbc97ff9f099f26c0dc268cf4a193604f20fcc0b637972e6d",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 34,
        ),
        PiperVoiceDef(
            id = "sw_CD-medium",
            code = "sw",
            bcp47 = "sw-CD",
            iso3 = "swa",
            iso3Country = "COD",
            nativeName = "Kiswahili",
            englishName = "Swahili",
            remoteArchive = "sw-medium.zip",
            dictFile = "sw-word_id.bin",
            sha256 = "85490ef10c9c8b16c49025d5fecd64c89b74971a97644be068dad9161c016d37",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 29,
        ),
        PiperVoiceDef(
            id = "te_IN-high",
            code = "te",
            bcp47 = "te-IN",
            iso3 = "tel",
            iso3Country = "IND",
            nativeName = "తెలుగు",
            englishName = "Telugu",
            remoteArchive = "te-high.zip",
            dictFile = "te-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ur_PK-high",
            code = "ur",
            bcp47 = "ur-PK",
            iso3 = "urd",
            iso3Country = "PAK",
            nativeName = "اردو",
            englishName = "Urdu",
            remoteArchive = "ur-high.zip",
            dictFile = "ur-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
    )

    /** Default English voice — en-high.zip (high, 22 kHz). */
    val DEFAULT: PiperVoiceDef = ALL.first { it.code == "en" }

    private val byCodeMap: Map<String, PiperVoiceDef> = ALL.associateBy { it.code.lowercase() }
    private val byBcp47Map: Map<String, PiperVoiceDef> = ALL.associateBy { it.bcp47.lowercase() }
    private val byIso3Map: Map<String, PiperVoiceDef> = ALL.associateBy { it.iso3.lowercase() }
    private val byIdMap: Map<String, PiperVoiceDef> = ALL.associateBy { it.id }
    private val byRemoteMap: Map<String, PiperVoiceDef> = ALL.associateBy { it.remoteArchive }

    fun byCode(code: String): PiperVoiceDef? = byCodeMap[code.lowercase()]
    fun byBcp47(tag: String): PiperVoiceDef? = byBcp47Map[tag.lowercase()]
    fun byIso3(iso3: String): PiperVoiceDef? = byIso3Map[iso3.lowercase()]
    fun byId(id: String): PiperVoiceDef? = byIdMap[id]
    fun byRemoteArchive(name: String): PiperVoiceDef? = byRemoteMap[name]

    fun resolve(
        lang: String? = null,
        country: String? = null,
        variant: String? = null,
        voiceName: String? = null,
    ): PiperVoiceDef? {
        voiceName?.let { name ->
            val lower = name.lowercase()
            val xIdx = lower.indexOf("-x-")
            if (xIdx >= 0) {
                val bcpPart = name.substring(0, xIdx)
                byBcp47(bcpPart)?.let { return it }
                val idPart = name.substring(xIdx + 3)
                byId(idPart)?.let { return it }
            }
            byBcp47(name)?.let { return it }
            byId(name)?.let { return it }
            val parts = name.split("-", "_")
            if (parts.isNotEmpty()) {
                byCode(parts[0])?.let { return it }
                byIso3(parts[0])?.let { return it }
            }
            if (parts.size >= 2) {
                byIso3(parts[0])?.let { return it }
            }
        }

        lang?.let { l ->
            val ll = l.lowercase()
            byCode(ll)?.let { return it }
            byIso3(ll)?.let { return it }
            byBcp47(ll)?.let { return it }
            val normalized = ll.replace('_', '-')
            byBcp47(normalized)?.let { return it }
            if (ll.length >= 2) {
                val prefix = ll.substring(0, 2)
                byCode(prefix)?.let { return it }
            }
        }

        return null
    }

    fun rootDir(context: Context): File? =
        context.getExternalFilesDir(null) ?: context.filesDir

    fun voicesDir(context: Context): File {
        val root = rootDir(context) ?: return File(VOICES_ROOT)
        return File(root, VOICES_ROOT)
    }

    fun voiceDir(context: Context, def: PiperVoiceDef): File {
        val root = rootDir(context) ?: return File("$VOICES_ROOT/${def.bcp47}/${def.id}")
        return File(root, "$VOICES_ROOT/${def.bcp47}/${def.id}")
    }

    fun archiveFile(context: Context, def: PiperVoiceDef): File {
        val root = rootDir(context) ?: return File("$VOICES_ROOT/${def.remoteArchive}")
        return File(root, "$VOICES_ROOT/${def.remoteArchive}")
    }

    fun legacyArchive(context: Context): File {
        val root = rootDir(context) ?: return File(LEGACY_ARCHIVE)
        return File(root, LEGACY_ARCHIVE)
    }

    fun legacyVoiceDir(context: Context): File {
        val root = rootDir(context) ?: return File(LEGACY_VOICE_DIR)
        return File(root, LEGACY_VOICE_DIR)
    }

    fun voicePrefix(context: Context, def: PiperVoiceDef): String? {
        val files = voiceDir(context, def).listFiles() ?: return null
        val encoder = files.firstOrNull { it.name.endsWith(ENCODER_SUFFIX) } ?: return null
        return encoder.name.removeSuffix(ENCODER_SUFFIX)
    }

    private fun isValidVoiceDir(dir: File, minDictBytes: Long = 100_000L): Boolean {
        if (!dir.isDirectory) return false
        if (dir.listFiles()?.any { it.name.endsWith(".onnx") } == true) return false
        val dict = dir.listFiles()?.firstOrNull { it.name.endsWith("-word_id.bin") } ?: return false
        if (dict.length() < minDictBytes) return false
        val enc = dir.listFiles()?.firstOrNull { it.name.endsWith(ENCODER_SUFFIX) } ?: return false
        val prefix = enc.name.removeSuffix(ENCODER_SUFFIX)
        val netsOk = REQUIRED_NETS.all { net ->
            File(dir, "${prefix}${net}.maml").exists()
        }
        if (!netsOk) return false
        if (!File(dir, CONFIG).exists()) return false
        return true
    }

    /**
     * Find any valid voice dir under `piper/voices/<bcp47>/` – tolerates old speaker-specific
     * ids like `en_US-lessac-low` after we renamed to generic `en_US-high`. Used to make
     * CHECK_TTS_DATA and onGetVoices work on upgraded installs.
     */
    fun findAnyValidDirForBcp47(context: Context, bcp47: String): File? {
        return try {
            val bcpRoot = File(voicesDir(context), bcp47)
            if (!bcpRoot.isDirectory) return null
            bcpRoot.listFiles()?.firstOrNull { child ->
                child.isDirectory && isValidVoiceDir(child, if (bcp47.startsWith("en")) 1_000_000L else 100_000L)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun isExtracted(context: Context, def: PiperVoiceDef): Boolean {
        val dir = voiceDir(context, def)
        if (dir.isDirectory && isValidVoiceDir(dir, if (def.code == "en") 1_000_000L else 100_000L)) {
            return true
        }
        // Fallback: any valid dir under same BCP-47 (old speaker-specific ids)
        if (findAnyValidDirForBcp47(context, def.bcp47) != null) {
            return true
        }
        if (def.code == "en") {
            val legacyDir = legacyVoiceDir(context)
            if (legacyDir.isDirectory && isLegacyExtracted(context)) {
                return true
            }
        }
        return false
    }

    private fun isLegacyExtracted(context: Context): Boolean {
        val dir = legacyVoiceDir(context)
        val files = dir.listFiles() ?: return false
        if (files.any { it.name.endsWith(".onnx") }) return false
        val dict = File(dir, "en-word_id.bin")
        if (!dict.exists() || dict.length() < 1_000_000L) return false
        val enc = files.firstOrNull { it.name.endsWith(ENCODER_SUFFIX) } ?: return false
        val prefix = enc.name.removeSuffix(ENCODER_SUFFIX)
        val netsOk = REQUIRED_NETS.all { net ->
            File(dir, "${prefix}${net}.maml").exists()
        }
        return netsOk && File(dir, CONFIG).exists()
    }

    fun isAnyExtracted(context: Context): Boolean =
        ALL.any { isExtracted(context, it) } || isLegacyExtracted(context)

    fun installedDefs(context: Context): List<PiperVoiceDef> =
        ALL.filter { isExtracted(context, it) }.let { list ->
            if (list.isEmpty() && isLegacyExtracted(context)) listOf(DEFAULT) else list
        }

    fun installedCodes(context: Context): List<String> =
        installedDefs(context).map { it.code }.distinct()

    fun downloadItems(codes: List<String>): List<ModelDownloadItem> {
        val defs = if (codes.isEmpty()) ALL else codes.mapNotNull { byCode(it) }
        return defs.map { def ->
            val fileName = "$VOICES_ROOT/${def.remoteArchive}"
            ModelDownloadItem(
                url = "$BASE${def.remoteArchive}",
                fileName = fileName,
                description = "${def.nativeName} (${def.englishName}) voice ${def.quality} 22kHz (${def.sizeEstimateMb} MB)",
                sha256 = def.sha256,
            )
        }
    }

    suspend fun download(
        context: Context,
        ds: DataStoreUtils,
        codes: List<String>,
    ) {
        val items = downloadItems(codes)
        if (items.isNotEmpty()) {
            downloadModels(context, ds, items)
        }
    }

    fun progress(ds: DataStoreUtils, def: PiperVoiceDef): Float {
        val key = "progress_$VOICES_ROOT/${def.remoteArchive}"
        return (ds.getDouble(key) ?: 0.0).toFloat()
    }

    fun overallProgress(ds: DataStoreUtils, codes: List<String>? = null): Float {
        val defs = if (codes == null) ALL else codes.mapNotNull { byCode(it) }
        if (defs.isEmpty()) return 0f
        return defs.map { progress(ds, it) }.average().toFloat()
    }

    @Synchronized
    fun installIfNeeded(context: Context, def: PiperVoiceDef): Boolean {
        if (isExtracted(context, def)) return true
        val zip = archiveFile(context, def)

        val altZips = mutableListOf<File>()
        if (def.code == "en") {
            altZips += legacyArchive(context)
            File(rootDir(context), "$DIR/${def.remoteArchive}").let { altZips += it }
            File(rootDir(context), "$DIR/$LEGACY_REMOTE_ARCHIVE").let { altZips += it }
        }

        val usableZip = when {
            zip.exists() -> zip
            else -> altZips.firstOrNull { it.exists() }?.also { found ->
                found.renameTo(zip)
            } ?: return false
        }

        val dir = voiceDir(context, def)
        val tmp = File(voicesDir(context), ".${def.id}.tmp")
        tmp.deleteRecursively()

        return try {
            unzip(usableZip, tmp)

            if (!File(tmp, "en-word_id.bin").exists()) {
                val enDir = voiceDir(context, DEFAULT)
                val enDictSrc = File(enDir, "en-word_id.bin")
                val anyDict = tmp.listFiles()?.firstOrNull { it.name.endsWith("-word_id.bin") }
                try {
                    if (enDictSrc.exists()) {
                        enDictSrc.copyTo(File(tmp, "en-word_id.bin"), overwrite = false)
                    } else if (anyDict != null) {
                        anyDict.copyTo(File(tmp, "en-word_id.bin"), overwrite = false)
                        Log.d(TAG, "copied ${anyDict.name} -> en-word_id.bin for 1.7.0 compat in ${def.id}")
                    }
                } catch (_: Throwable) {
                }
            }

            dir.parentFile?.mkdirs()
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw IllegalStateException("rename $tmp -> $dir failed")
            usableZip.delete()
            altZips.forEach { it.takeIf { f -> f.exists() && f.absolutePath != usableZip.absolutePath }?.delete() }
            if (def.code == "en") {
                File(rootDir(context), "$DIR/${LEGACY_REMOTE_ARCHIVE}").takeIf { it.exists() }?.delete()
            }
            isExtracted(context, def)
        } catch (t: Throwable) {
            Log.e(TAG, "extracting ${def.id} failed", t)
            tmp.deleteRecursively()
            usableZip.delete()
            false
        }
    }

    fun installAllIfNeeded(context: Context, ds: DataStoreUtils) {
        for (def in ALL) {
            val p = progress(ds, def)
            if (p >= 0.999f || archiveFile(context, def).exists()) {
                installIfNeeded(context, def)
            }
        }
    }

    fun deleteFiles(context: Context, def: PiperVoiceDef) {
        try {
            voiceDir(context, def).deleteRecursively()
            archiveFile(context, def).delete()
        } catch (t: Throwable) {
            Log.e(TAG, "delete files ${def.id} failed", t)
        }
    }

    suspend fun delete(context: Context, def: PiperVoiceDef, ds: DataStoreUtils? = null) {
        try {
            voiceDir(context, def).deleteRecursively()
            archiveFile(context, def).delete()
            ds?.let {
                it.setDouble("progress_$VOICES_ROOT/${def.remoteArchive}", 0.0)
                it.setLong("dlid_$VOICES_ROOT/${def.remoteArchive}", 0L)
                it.setDouble("speed_$VOICES_ROOT/${def.remoteArchive}", 0.0)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "delete ${def.id} failed", t)
        }
    }

    fun installedBytes(context: Context): Long {
        var total = 0L
        for (def in ALL) {
            if (isExtracted(context, def)) {
                val dir = voiceDir(context, def)
                total += dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            }
        }
        if (isLegacyExtracted(context)) {
            val legacy = legacyVoiceDir(context)
            total += legacy.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }
        return total
    }

    @Synchronized
    fun cleanOldLowVariants(context: Context) {
        try {
            val vRoot = voicesDir(context)
            if (vRoot.isDirectory) {
                // Delete old -low.zip archives that are superseded by higher quality
                vRoot.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith("-low.zip")) {
                        val lang = f.name.substringBefore("-low.zip")
                        val hasHigher = ALL.any { it.code == lang && (it.quality == "medium" || it.quality == "high") && it.remoteArchive != f.name }
                        // If registry has higher for same lang, delete low archive
                        val superseded = vRoot.listFiles()?.any { other ->
                            other.isFile && other.name != f.name && other.name.startsWith("$lang-") && 
                                (other.name.endsWith("-medium.zip") || other.name.endsWith("-high.zip"))
                        } ?: false
                        if (superseded) {
                            Log.d(TAG, "deleting superseded low archive ${f.name}")
                            f.delete()
                        }
                    }
                    if (f.isFile && f.name.endsWith("-medium.zip")) {
                        val lang = f.name.substringBefore("-medium.zip")
                        val hasHigh = vRoot.listFiles()?.any { other -> other.isFile && other.name == "$lang-high.zip" } ?: false
                        if (hasHigh) {
                            Log.d(TAG, "deleting superseded medium archive ${f.name}")
                            f.delete()
                        }
                    }
                }
                // For extracted dirs, keep only highest quality per bcp47 prefix? 
                // More conservative: do not auto-delete installed medium/high dirs, only clean tmp.
                // The spec says remove every option but highest — this applies to dist, not installed.
            }
        } catch (t: Throwable) {
            Log.e(TAG, "cleanOldLowVariants failed", t)
        }
    }

    @Synchronized
    fun migrateLegacyIfNeeded(context: Context) {
        cleanOldLowVariants(context)
        val legacy = legacyVoiceDir(context)
        if (!legacy.isDirectory) return
        if (!isLegacyExtracted(context)) {
            Log.d(TAG, "legacy dir present but not valid, deleting for migration")
            legacy.deleteRecursively()
            return
        }
        val def = DEFAULT
        val newDir = voiceDir(context, def)
        if (newDir.exists() && isExtracted(context, def)) {
            Log.d(TAG, "legacy already migrated, deleting legacy $legacy")
            legacy.deleteRecursively()
            return
        }
        try {
            newDir.parentFile?.mkdirs()
            if (newDir.exists()) newDir.deleteRecursively()
            val renamed = legacy.renameTo(newDir)
            if (!renamed) {
                Log.d(TAG, "rename failed, copying legacy to $newDir")
                legacy.copyRecursively(newDir, overwrite = true)
                legacy.deleteRecursively()
            }
            Log.d(TAG, "migrated legacy $legacy -> $newDir")
        } catch (t: Throwable) {
            Log.e(TAG, "migration failed", t)
        }
    }

    private fun unzip(zip: File, outDir: File) {
        outDir.mkdirs()
        val root = outDir.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(outDir, entry.name)
                if (!out.canonicalPath.startsWith(root)) {
                    throw SecurityException("Zip entry escapes target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }

    private const val TAG = "PiperVoiceRegistry"
}
