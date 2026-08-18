package com.vayunmathur.keyboard.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A named group of emoji shown as one tab on the emoji page. */
data class EmojiCategory(val label: String, val emojis: List<String>)

/**
 * One emoji plus the text search matches against: the CLDR short name ("grinning face")
 * and its keywords ("happy", "smile", ...). Both are lowercase.
 */
data class EmojiEntry(val char: String, val name: String, val keywords: List<String>) {
    /**
     * The words of [name] after the first. Search runs over every entry on every keystroke,
     * so splitting the name there allocated a list per emoji per keypress; this does it once
     * when the palette is read instead.
     *
     * The first word is left out because it cannot add a match: a query that starts it also
     * starts the whole name, which search has already ranked higher.
     */
    val laterNameWords: List<String> = name.split(' ').drop(1)
}

/**
 * The emoji the user picked most recently, newest first. Kept out of [KeyboardSettings]
 * because these change on every tap and are not something the user configures.
 */
object RecentEmoji {
    /** Four rows of eight, which is exactly what the emoji grid shows without scrolling. */
    const val MAX = 32

    /** Clock face, the tab this list lives under. */
    const val TAB = "🕘"

    /** Put [emoji] at the front, removing any earlier use of it, and trim to [MAX]. */
    fun add(recents: List<String>, emoji: String): List<String> =
        (listOf(emoji) + recents.filterNot { it == emoji }).take(MAX)

    // Emoji sequences never contain a comma, so this needs no escaping.
    fun encode(recents: List<String>): String = recents.joinToString(",")

    fun decode(stored: String?): List<String> =
        stored?.split(',')?.filter { it.isNotBlank() }?.take(MAX).orEmpty()
}

/**
 * The emoji palette and its search index, loaded from `assets/emoji.txt` (see
 * `scripts/keyboard/generate_emoji.py`). Until that asset is read the keyboard uses
 * [BUILTIN], a small curated set with no search data, so the page is never empty.
 */
class EmojiData(
    val categories: List<EmojiCategory>,
    private val entries: List<EmojiEntry>,
) {
    /**
     * Up to [limit] emoji matching [query], best first. A query that starts the name
     * ("gri" -> grinning face) ranks above one that starts a later word in it ("face"),
     * which ranks above a keyword-only match — otherwise the literal thing the user typed
     * gets buried under the hundreds of emoji that merely list it as a keyword.
     */
    fun search(query: String, limit: Int = 24): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty() || limit <= 0) return emptyList()
        val name = ArrayList<String>()
        val word = ArrayList<String>()
        val keyword = ArrayList<String>()
        for (entry in entries) {
            val bucket = when {
                entry.name.startsWith(q) -> name
                entry.laterNameWords.any { it.startsWith(q) } -> word
                entry.keywords.any { it.startsWith(q) } -> keyword
                else -> continue
            }
            bucket.add(entry.char)
            // The best bucket alone can fill the results; nothing after this can beat it.
            if (name.size >= limit) break
        }
        return (name + word + keyword).take(limit)
    }

    companion object {
        /**
         * The curated fallback set: what the keyboard showed before the generated asset
         * existed. Still what the screenshot previews render, since they have no service
         * behind them to load anything.
         */
        val BUILTIN = EmojiData(
            categories = listOf(
                EmojiCategory(
                    "😀",
                    listOf(
                        "😀", "😁", "😂", "😃", "😄",
                        "😅", "😆", "😉", "😊", "😋",
                        "😎", "😍", "😘", "😗", "😜",
                        "😝", "🤑", "🤗", "🤔", "😐",
                        "🙄", "😏", "😒", "😞", "😢",
                        "😭", "😫", "😠", "😡", "🥳",
                        "😇", "🥰", "😴", "🤤", "😱",
                    ),
                ),
                EmojiCategory(
                    "👍",
                    listOf(
                        "👍", "👎", "👌", "✌️", "🤞",
                        "👏", "🙌", "🙏", "💪", "👋",
                        "✋", "🤝", "✍️", "🧠", "👀",
                        "❤️", "🖤", "💔", "💕", "✨",
                        "🔥", "🎉", "🎊", "⭐", "🌟",
                    ),
                ),
                EmojiCategory(
                    "🐶",
                    listOf(
                        "🐶", "🐱", "🐭", "🐹", "🐰",
                        "🦊", "🐻", "🐼", "🐨", "🐯",
                        "🦁", "🐷", "🐸", "🐵", "🐔",
                        "🐧", "🐦", "🦆", "🦉", "🐝",
                        "🦋", "🐞", "🐢", "🐟", "🐳",
                    ),
                ),
                EmojiCategory(
                    "🍔",
                    listOf(
                        "🍎", "🍌", "🍉", "🍇", "🍓",
                        "🍒", "🍑", "🍊", "🍋", "🍅",
                        "🍔", "🍟", "🍕", "🌭", "🌮",
                        "🍜", "🍣", "🍩", "🍰", "☕",
                        "🍺", "🍷", "🍹", "🥤", "🍦",
                    ),
                ),
                EmojiCategory(
                    "⚙️",
                    listOf(
                        "⚙️", "📱", "💻", "⌚", "📷",
                        "🔋", "💡", "🔦", "🔑", "🔒",
                        "📅", "✉️", "📎", "✂️", "📌",
                        "🔍", "✅", "❌", "➕", "➖",
                        "❗", "❓", "❤️", "💰", "🎯",
                    ),
                ),
            ),
            entries = emptyList(),
        )

        /**
         * Tab for each Unicode emoji group. Smileys and People share one, as they do on
         * every other keyboard — split apart they are two tabs of faces and hands.
         */
        private val TABS = mapOf(
            "Smileys & Emotion" to "😀",
            "People & Body" to "😀",
            "Animals & Nature" to "🐶",
            "Food & Drink" to "🍔",
            "Travel & Places" to "🚗",
            "Activities" to "⚽",
            "Objects" to "💡",
            "Symbols" to "❤️",
            "Flags" to "🏁",
        )

        /** Read the generated palette off the main thread, falling back to [BUILTIN]. */
        suspend fun load(context: Context): EmojiData = withContext(Dispatchers.IO) {
            val entries = runCatching { readAsset(context) }.getOrNull()
            if (entries.isNullOrEmpty()) BUILTIN else build(entries)
        }

        /** Parse `emoji<TAB>group<TAB>name<TAB>keyword|keyword|...` lines. */
        internal fun parse(lines: Sequence<String>): List<Pair<String, EmojiEntry>> {
            val parsed = ArrayList<Pair<String, EmojiEntry>>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split('\t')
                if (parts.size < 3) continue
                val keywords = parts.getOrNull(3)
                    ?.split('|')
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                parsed.add(parts[1] to EmojiEntry(parts[0], parts[2].lowercase(), keywords))
            }
            return parsed
        }

        internal fun build(parsed: List<Pair<String, EmojiEntry>>): EmojiData {
            // Grouped by tab rather than by Unicode group, so the two people groups land
            // in the same category. LinkedHashMap keeps the palette's own order.
            val byTab = LinkedHashMap<String, MutableList<String>>()
            for ((group, entry) in parsed) {
                val tab = TABS[group] ?: continue
                byTab.getOrPut(tab) { ArrayList() }.add(entry.char)
            }
            val categories = byTab.map { (tab, emojis) -> EmojiCategory(tab, emojis) }
            return if (categories.isEmpty()) {
                BUILTIN
            } else {
                EmojiData(categories, parsed.map { it.second })
            }
        }

        private fun readAsset(context: Context): List<Pair<String, EmojiEntry>> =
            context.assets.open("emoji.txt").bufferedReader().useLines { parse(it) }
    }
}
