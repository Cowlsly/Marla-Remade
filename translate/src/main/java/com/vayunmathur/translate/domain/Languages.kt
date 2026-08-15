package com.vayunmathur.translate.util

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

    /** ~20 common languages, curated. Codes are ISO-639-1 for TTS Locale support. */
    val ALL: List<Language> = listOf(
        Language("en", "English", "English"),
        Language("es", "Spanish", "Español"),
        Language("fr", "French", "Français"),
        Language("de", "German", "Deutsch"),
        Language("it", "Italian", "Italiano"),
        Language("pt", "Portuguese", "Português"),
        Language("nl", "Dutch", "Nederlands"),
        Language("ru", "Russian", "Русский"),
        Language("pl", "Polish", "Polski"),
        Language("tr", "Turkish", "Türkçe"),
        Language("ar", "Arabic", "العربية"),
        Language("hi", "Hindi", "हिन्दी"),
        Language("zh", "Chinese", "中文"),
        Language("ja", "Japanese", "日本語"),
        Language("ko", "Korean", "한국어"),
        Language("vi", "Vietnamese", "Tiếng Việt"),
        Language("th", "Thai", "ไทย"),
        Language("id", "Indonesian", "Bahasa Indonesia"),
        Language("uk", "Ukrainian", "Українська"),
        Language("sv", "Swedish", "Svenska"),
    )

    /** Sources include the [AUTO] sentinel; targets are the concrete languages. */
    val SOURCES: List<Language> = listOf(AUTO) + ALL
    val TARGETS: List<Language> = ALL

    /** Look up a language by [code], falling back to [AUTO] then [en]. */
    fun byCode(code: String): Language =
        if (code == AUTO.code) AUTO else ALL.firstOrNull { it.code == code } ?: ALL.first()

    fun displayName(code: String): String = byCode(code).nativeName
}
