package com.vayunmathur.translate.domain

/**
 * One selectable language. [code] is the BCP-47 / ISO-639-1 code used both as the
 * translation-engine language id and (via [java.util.Locale]) for text-to-speech.
 * [AUTO] is a sentinel meaning "detect the source language automatically" and is
 * only offered as a *source*, never a target.
 */
data class Language(
    val code: String,
    val englishName: String,
    val nativeName: String,
)

object Languages {
    /** Sentinel source option: let the engine detect the language. */
    val AUTO = Language("auto", "Auto (detect)", "Auto")

    /**
     * All 100 languages SMaLL-100 was trained on, ordered by English name for the picker.
     * Codes must stay in sync with
     * [com.vayunmathur.translate.platform.Small100Model.LANG_ID] - a language listed here
     * without a target-language token would silently fail to translate.
     */
    val ALL: List<Language> = listOf(
        Language("af", "Afrikaans", "Afrikaans"),
        Language("sq", "Albanian", "Shqip"),
        Language("am", "Amharic", "አማርኛ"),
        Language("ar", "Arabic", "العربية"),
        Language("hy", "Armenian", "Հայերեն"),
        Language("ast", "Asturian", "Asturianu"),
        Language("az", "Azerbaijani", "Azərbaycan"),
        Language("ba", "Bashkir", "Башҡортса"),
        Language("be", "Belarusian", "Беларуская"),
        Language("bn", "Bengali", "বাংলা"),
        Language("bs", "Bosnian", "Bosanski"),
        Language("br", "Breton", "Brezhoneg"),
        Language("bg", "Bulgarian", "Български"),
        Language("my", "Burmese", "မြန်မာ"),
        Language("ca", "Catalan", "Català"),
        Language("ceb", "Cebuano", "Cebuano"),
        Language("zh", "Chinese", "中文"),
        Language("hr", "Croatian", "Hrvatski"),
        Language("cs", "Czech", "Čeština"),
        Language("da", "Danish", "Dansk"),
        Language("nl", "Dutch", "Nederlands"),
        Language("en", "English", "English"),
        Language("et", "Estonian", "Eesti"),
        Language("fi", "Finnish", "Suomi"),
        Language("fr", "French", "Français"),
        Language("fy", "Frisian", "Frysk"),
        Language("ff", "Fulah", "Fulfulde"),
        Language("gd", "Gaelic", "Gàidhlig"),
        Language("gl", "Galician", "Galego"),
        Language("lg", "Ganda", "Luganda"),
        Language("ka", "Georgian", "ქართული"),
        Language("de", "German", "Deutsch"),
        Language("el", "Greek", "Ελληνικά"),
        Language("gu", "Gujarati", "ગુજરાતી"),
        Language("ht", "Haitian Creole", "Kreyòl ayisyen"),
        Language("ha", "Hausa", "Hausa"),
        Language("he", "Hebrew", "עברית"),
        Language("hi", "Hindi", "हिन्दी"),
        Language("hu", "Hungarian", "Magyar"),
        Language("is", "Icelandic", "Íslenska"),
        Language("ig", "Igbo", "Igbo"),
        Language("ilo", "Ilocano", "Ilokano"),
        Language("id", "Indonesian", "Bahasa Indonesia"),
        Language("ga", "Irish", "Gaeilge"),
        Language("it", "Italian", "Italiano"),
        Language("ja", "Japanese", "日本語"),
        Language("jv", "Javanese", "Basa Jawa"),
        Language("kn", "Kannada", "ಕನ್ನಡ"),
        Language("kk", "Kazakh", "Қазақша"),
        Language("km", "Khmer", "ខ្មែរ"),
        Language("ko", "Korean", "한국어"),
        Language("lo", "Lao", "ລາວ"),
        Language("lv", "Latvian", "Latviešu"),
        Language("ln", "Lingala", "Lingála"),
        Language("lt", "Lithuanian", "Lietuvių"),
        Language("lb", "Luxembourgish", "Lëtzebuergesch"),
        Language("mk", "Macedonian", "Македонски"),
        Language("mg", "Malagasy", "Malagasy"),
        Language("ms", "Malay", "Bahasa Melayu"),
        Language("ml", "Malayalam", "മലയാളം"),
        Language("mr", "Marathi", "मराठी"),
        Language("mn", "Mongolian", "Монгол"),
        Language("ne", "Nepali", "नेपाली"),
        Language("ns", "Northern Sotho", "Sesotho sa Leboa"),
        Language("no", "Norwegian", "Norsk"),
        Language("oc", "Occitan", "Occitan"),
        Language("or", "Odia", "ଓଡ଼ିଆ"),
        Language("ps", "Pashto", "پښتو"),
        Language("fa", "Persian", "فارسی"),
        Language("pl", "Polish", "Polski"),
        Language("pt", "Portuguese", "Português"),
        Language("pa", "Punjabi", "ਪੰਜਾਬੀ"),
        Language("ro", "Romanian", "Română"),
        Language("ru", "Russian", "Русский"),
        Language("sr", "Serbian", "Српски"),
        Language("sd", "Sindhi", "سنڌي"),
        Language("si", "Sinhala", "සිංහල"),
        Language("sk", "Slovak", "Slovenčina"),
        Language("sl", "Slovenian", "Slovenščina"),
        Language("so", "Somali", "Soomaali"),
        Language("es", "Spanish", "Español"),
        Language("su", "Sundanese", "Basa Sunda"),
        Language("sw", "Swahili", "Kiswahili"),
        Language("ss", "Swati", "SiSwati"),
        Language("sv", "Swedish", "Svenska"),
        Language("tl", "Tagalog", "Tagalog"),
        Language("ta", "Tamil", "தமிழ்"),
        Language("th", "Thai", "ไทย"),
        Language("tn", "Tswana", "Setswana"),
        Language("tr", "Turkish", "Türkçe"),
        Language("uk", "Ukrainian", "Українська"),
        Language("ur", "Urdu", "اردو"),
        Language("uz", "Uzbek", "Oʻzbekcha"),
        Language("vi", "Vietnamese", "Tiếng Việt"),
        Language("cy", "Welsh", "Cymraeg"),
        Language("wo", "Wolof", "Wolof"),
        Language("xh", "Xhosa", "isiXhosa"),
        Language("yi", "Yiddish", "ייִדיש"),
        Language("yo", "Yoruba", "Yorùbá"),
        Language("zu", "Zulu", "isiZulu"),
    )

    /** Sources include the [AUTO] sentinel; targets are the concrete languages. */
    val SOURCES: List<Language> = listOf(AUTO) + ALL
    val TARGETS: List<Language> = ALL

    private val index: Map<String, Language> = ALL.associateBy { it.code }

    /** Look up a language by [code], falling back to [AUTO] then English. */
    fun byCode(code: String): Language =
        if (code == AUTO.code) AUTO else index[code] ?: index.getValue("en")

    fun displayName(code: String): String = byCode(code).nativeName
}
