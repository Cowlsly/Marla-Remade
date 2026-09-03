package com.vayunmathur.translate.domain

/**
 * How many recently used languages are kept per direction (source/target).
 * Small enough to stay a glanceable section above the full list.
 */
const val MAX_RECENT_LANGUAGES = 6

/**
 * Returns [recents] with [code] moved to the front (most-recent first),
 * de-duplicated and capped at [MAX_RECENT_LANGUAGES].
 *
 * The [Languages.AUTO] sentinel is never stored: it is a mode, not a language,
 * and keeping it as "recent" would just duplicate the pinned row above the list.
 */
fun pushRecentLanguage(recents: List<String>, code: String): List<String> {
    if (code == Languages.AUTO.code) return recents
    return (listOf(code) + recents.filterNot { it == code }).take(MAX_RECENT_LANGUAGES)
}

/**
 * Parses the comma-joined recents string from DataStore back into codes.
 * A plain string is used instead of a string set because sets don't keep order.
 */
fun parseRecentLanguages(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
