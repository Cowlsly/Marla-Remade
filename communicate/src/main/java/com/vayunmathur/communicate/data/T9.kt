package com.vayunmathur.communicate.data

import java.text.Normalizer

/**
 * T9 matching for the dialpad: typing 5673 finds "José", typing 76 finds "Smith".
 *
 * Names are folded before mapping (NFD, combining marks stripped, uppercased) so accented
 * letters reach the key they are printed on — the same normalisation the contacts app uses
 * to pick a name's section header, for the same reason.
 *
 * Deliberately free of `android.*` so it can be unit-tested on the JVM.
 */
object T9 {

    private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")

    /** Split on what `initialsFor` splits on, so a token here is a token there. */
    private val DELIMITERS = charArrayOf(' ', '+', '-', '_')

    /**
     * The keypad marking for [c], or null when the character has none. Scripts that were never
     * printed on a keypad (CJK, Cyrillic, Arabic, Hebrew) fall in the null case; such contacts
     * are reachable through their phone number instead of being mapped to an invented key.
     */
    fun digitFor(c: Char): Char? = when (c) {
        in '0'..'9' -> c
        'A', 'B', 'C' -> '2'
        'D', 'E', 'F' -> '3'
        'G', 'H', 'I' -> '4'
        'J', 'K', 'L' -> '5'
        'M', 'N', 'O' -> '6'
        'P', 'Q', 'R', 'S' -> '7'
        'T', 'U', 'V' -> '8'
        'W', 'X', 'Y', 'Z' -> '9'
        else -> null
    }

    /** The keys that spell [text], with unmappable characters dropped. */
    fun keysFor(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .uppercase()
        return buildString(folded.length) {
            folded.forEach { c -> digitFor(c)?.let { append(it) } }
        }
    }

    /** Keeps only what can be matched against: `*`, `#` and `+` dial but don't spell. */
    fun normalizeQuery(raw: String): String = raw.filter { it.isDigit() }

    /**
     * One contact's precomputed keys. Built once per contact list so filtering costs one pass
     * over the contacts per keystroke rather than re-folding every name.
     */
    class Entry(
        val nameKeys: List<String>,
        val initialsKey: String,
        val phoneDigits: String,
    )

    fun entryFor(name: String, phoneNumber: String): Entry {
        val tokens = name.split(*DELIMITERS).filter { it.isNotBlank() }
        return Entry(
            nameKeys = tokens.map { keysFor(it) }.filter { it.isNotEmpty() },
            initialsKey = keysFor(tokens.mapNotNull { it.firstOrNull() }.joinToString("")),
            phoneDigits = phoneNumber.filter { it.isDigit() },
        )
    }

    /**
     * True when [query] (already through [normalizeQuery]) prefixes any word of the name or the
     * run of its initials, or appears anywhere in the phone number. The phone clause mirrors the
     * recipient picker's filter so both as-you-type searches behave the same.
     */
    fun matches(entry: Entry, query: String): Boolean {
        if (query.isEmpty()) return true
        return entry.nameKeys.any { it.startsWith(query) } ||
            entry.initialsKey.startsWith(query) ||
            entry.phoneDigits.contains(query)
    }
}
