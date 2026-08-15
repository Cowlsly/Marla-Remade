package com.vayunmathur.musicbrainz.domain.library

import java.text.Normalizer

/**
 * Turns titles and artist names into comparison keys.
 *
 * Only files tagged by Picard carry MusicBrainz IDs, so most of a real library has to be
 * matched on text. That means deciding which differences are the same recording and which
 * are not: reissue noise like "Remastered" is dropped, while "Live", "Remix" and
 * "Acoustic" are kept, because those genuinely are different recordings and folding them
 * together would mark tracks as owned that the user does not have.
 */
object MatchKeys {

    private val NOISE_QUALIFIERS = listOf(
        "remaster", "remastered", "remastered version", "deluxe", "deluxe edition",
        "bonus track", "bonus", "explicit", "clean", "album version", "single version",
        "original mix", "mono", "stereo", "digital remaster", "anniversary edition",
    )

    private val BRACKETED = Regex("[\\[(]([^\\[\\]()]*)[\\])]")
    /** Dropped outright rather than turned into a space, so "Don't" matches "Dont". */
    private val APOSTROPHES = Regex("['\u2019\u02BC\u055A`]")
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{Nd} ]")
    private val WHITESPACE = Regex("\\s+")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** Splits off collaborators so "A feat. B" and "A" compare equal. */
    private val ARTIST_SEPARATORS = Regex(
        "\\s+(?:feat\\.?|featuring|ft\\.?|with|vs\\.?|&|x|and)\\s+|,\\s*",
        RegexOption.IGNORE_CASE,
    )

    fun normalize(value: String?): String? {
        if (value.isNullOrBlank()) return null
        var text = value.lowercase()
        text = BRACKETED.replace(text) { match ->
            val inner = match.groupValues[1].trim()
            if (NOISE_QUALIFIERS.any { inner.contains(it) }) "" else " $inner "
        }
        text = Normalizer.normalize(text, Normalizer.Form.NFD)
        text = COMBINING_MARKS.replace(text, "")
        text = APOSTROPHES.replace(text, "")
        text = NON_ALPHANUMERIC.replace(text, " ")
        text = WHITESPACE.replace(text, " ").trim()
        for (qualifier in NOISE_QUALIFIERS) {
            if (text.endsWith(" $qualifier")) text = text.removeSuffix(" $qualifier").trim()
        }
        return text.ifEmpty { null }
    }

    /** The first credited artist, which is the part two taggings of a track agree on. */
    fun primaryArtist(artist: String?): String? {
        if (artist.isNullOrBlank()) return null
        return ARTIST_SEPARATORS.split(artist).firstOrNull { it.isNotBlank() }?.trim()
    }

    fun trackKey(artist: String?, title: String?): String? {
        val normalizedArtist = normalize(primaryArtist(artist)) ?: return null
        val normalizedTitle = normalize(title) ?: return null
        return "$normalizedArtist\u0000$normalizedTitle"
    }

    fun albumKey(album: String?, title: String?): String? {
        val normalizedAlbum = normalize(album) ?: return null
        val normalizedTitle = normalize(title) ?: return null
        return "$normalizedAlbum\u0000$normalizedTitle"
    }
}
