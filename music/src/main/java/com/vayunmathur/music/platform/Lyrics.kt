package com.vayunmathur.music.platform

import com.vayunmathur.sdk.cast.CastContract

/**
 * One lyric line, and the position it belongs at.
 *
 * Lived in `ui/LyricsView.kt` while the only reader was a composable. It moved because [CastPlayback]
 * needs it too, and platform code must not import from `ui` - the dependency runs the other way.
 */
data class LyricLine(val timestamp: Long, val text: String)

/**
 * What a track's lyrics turned out to be.
 *
 * **Three cases rather than a list that is empty when it fails**, which is what the old
 * [parseLyrics] gave its only caller: a file with unsynced lyrics parsed to nothing, so the phone
 * said "no lyrics available" about a track that plainly had them. Naming the third case fixes that
 * and is what lets the two wire fields of `NowPlaying` be filled unambiguously - see
 * [CastContract.KEY_PLAIN_LYRICS] for why a sentinel timestamp is not an option.
 */
sealed interface Lyrics {

    /** No lyrics in the file, or nothing readable in what was there. */
    data object None : Lyrics

    /** LRC with timestamps, sorted, so a highlight can follow the clock. */
    data class Timed(val lines: List<LyricLine>) : Lyrics

    /** Text with no timings. Shown as a block, with nothing highlighted. */
    data class Plain(val text: String) : Lyrics
}

/**
 * Classifies whatever `EmbeddedLyrics.read` came back with.
 *
 * Timed wins whenever a single timestamp was found: a file with a few `[00:12.00]` lines and a lot
 * of loose text is a synced file with untimed headers in it, not a plain one.
 */
fun classifyLyrics(raw: String?): Lyrics {
    if (raw.isNullOrBlank()) return Lyrics.None
    val timed = parseLyrics(raw)
    if (timed.isNotEmpty()) return Lyrics.Timed(timed)
    // LRC metadata tags - `[ar:...]`, `[length:...]` - are not lyrics, and a file that is nothing but
    // those would otherwise be shown as a block of them.
    val plain = raw.lines()
        .filterNot { it.isBlank() || METADATA_TAG.matches(it.trim()) }
        .joinToString("\n") { it.trim() }
    return if (plain.isBlank()) Lyrics.None else Lyrics.Plain(plain)
}

/**
 * Which line is current at [positionMs], or -1 before the first one.
 *
 * Lifted out of the composable it was written in so the phone and the television share one tested
 * function rather than two copies of an `indexOfLast`. The boundary is inclusive: a line whose
 * timestamp is exactly the position is the line being sung.
 */
fun currentLyricIndex(lines: List<LyricLine>, positionMs: Long): Int =
    lines.indexOfLast { it.timestamp <= positionMs }

/**
 * Parses LRC lyric content into timed lines, or an empty list when there are none.
 *
 * Truncated to [CastContract.MAX_LYRIC_LINES] and [CastContract.MAX_LYRIC_CHARS] because these
 * lines cross a control frame when the track is cast, and a pathological file must not be able to
 * overflow one. The bound applies on the phone too rather than only on the way out: a lyrics view
 * with ten thousand lines in it is nobody's intent either.
 *
 * Known limits, deliberately unchanged: a line with several timestamps yields one entry rather than
 * one per timestamp, and enhanced-LRC word tags are left inline. Both change what real files render
 * as and belong in their own change.
 */
fun parseLyrics(lrcContent: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    var chars = 0
    for (line in lrcContent.lines()) {
        if (lines.size >= CastContract.MAX_LYRIC_LINES) break
        val match = LYRIC_PATTERN.find(line) ?: continue
        val fraction = match.groupValues[3]
        val text = match.groupValues[4].trim()
        if (text.isEmpty()) continue
        if (chars + text.length > CastContract.MAX_LYRIC_CHARS) break
        val minutes = match.groupValues[1].toLongOrNull() ?: continue
        val seconds = match.groupValues[2].toLongOrNull() ?: continue
        val fractionValue = fraction.toLongOrNull() ?: continue
        // Two digits are centiseconds and three are milliseconds, which is the one thing the LRC
        // format leaves to be inferred from the field's width.
        val timestamp = minutes * 60_000 + seconds * 1_000 +
            if (fraction.length == 2) fractionValue * 10 else fractionValue
        lines.add(LyricLine(timestamp, text))
        chars += text.length
    }
    return lines.sortedBy { it.timestamp }
}

/** `[mm:ss.xx] text`, with a two- or three-digit fraction. */
private val LYRIC_PATTERN = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})](.*)")

/** An LRC header such as `[ti:Title]`: a colon-separated tag rather than a timestamp. */
private val METADATA_TAG = Regex("\\[[a-zA-Z#]+:.*]")
