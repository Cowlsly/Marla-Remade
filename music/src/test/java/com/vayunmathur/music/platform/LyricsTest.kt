package com.vayunmathur.music.platform

import com.vayunmathur.sdk.cast.CastContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The lyrics parser, now that two things read it.
 *
 * It moved out of `ui/NowPlayingScreen.kt` because `CastPlayback` needs it, and this is the first test
 * it has ever had. The existing behaviour is pinned as it is: the classification and the bound are
 * new, but nothing here is meant to change what a real LRC file renders as on the phone.
 */
class LyricsTest {

    private val synced = """
        [ti:Blue Monday]
        [ar:New Order]
        [00:12.00]how does it feel
        [00:16.12]to treat me like you do
        [01:04.5]
        [00:20.500]when you've laid your hands upon me
    """.trimIndent()

    @Test
    fun `a synced file classifies as timed, sorted, with empty lines dropped`() {
        val lyrics = assertIs<Lyrics.Timed>(classifyLyrics(synced))
        assertEquals(
            listOf(12_000L, 16_120L, 20_500L),
            lyrics.lines.map { it.timestamp },
        )
        assertEquals("how does it feel", lyrics.lines.first().text)
    }

    @Test
    fun `two fraction digits are centiseconds and three are milliseconds`() {
        // The one thing the LRC format leaves to be inferred from a field's width, and the one place a
        // mistake would be a uniform 900 ms of drift that reads as a bad file rather than a bug.
        val lines = parseLyrics("[00:01.05]a\n[00:01.050]b")
        assertEquals(listOf(1_050L, 1_050L), lines.map { it.timestamp })
    }

    @Test
    fun `lyrics with no timestamps classify as plain`() {
        // Previously the empty list from these was indistinguishable from a file with no lyrics at all,
        // so the phone said "no lyrics available" about a track that plainly had them.
        val lyrics = assertIs<Lyrics.Plain>(classifyLyrics("how does it feel\n\nto treat me like you do"))
        assertEquals("how does it feel\nto treat me like you do", lyrics.text)
    }

    @Test
    fun `metadata tags alone are not lyrics`() {
        assertIs<Lyrics.None>(classifyLyrics("[ti:Blue Monday]\n[length:07:29]"))
    }

    @Test
    fun `nothing, blank and whitespace all classify as none`() {
        assertIs<Lyrics.None>(classifyLyrics(null))
        assertIs<Lyrics.None>(classifyLyrics(""))
        assertIs<Lyrics.None>(classifyLyrics("   \n\n  "))
    }

    @Test
    fun `a file with one timestamp among loose text is timed rather than plain`() {
        // A synced file with untimed headers in it, which is common - and the reason the classification
        // prefers timed whenever a single timestamp was found.
        val lyrics = assertIs<Lyrics.Timed>(classifyLyrics("written by somebody\n[00:03.00]the only timed line"))
        assertEquals(1, lyrics.lines.size)
    }

    @Test
    fun `the current index is exclusive before the first line and inclusive on a boundary`() {
        val lines = listOf(
            LyricLine(1_000, "one"),
            LyricLine(2_000, "two"),
            LyricLine(3_000, "three"),
        )
        // Before the first: -1 rather than 0, so nothing is highlighted during an intro.
        assertEquals(-1, currentLyricIndex(lines, 0))
        assertEquals(-1, currentLyricIndex(lines, 999))
        // Exactly on a timestamp is the line being sung, not the one before it.
        assertEquals(0, currentLyricIndex(lines, 1_000))
        assertEquals(1, currentLyricIndex(lines, 2_500))
        // Past the last it stays on the last, for the outro.
        assertEquals(2, currentLyricIndex(lines, 3_000))
        assertEquals(2, currentLyricIndex(lines, 9_999_999))
        assertEquals(-1, currentLyricIndex(emptyList(), 5_000))
    }

    @Test
    fun `the line count is bounded`() {
        val many = (0 until CastContract.MAX_LYRIC_LINES * 2)
            .joinToString("\n") { "[00:%02d.00]line".format(it % 60) }
        assertEquals(CastContract.MAX_LYRIC_LINES, parseLyrics(many).size)
    }

    @Test
    fun `the total character count is bounded`() {
        // A pathological file: few lines, each enormous. The line bound alone would let this through,
        // and it ends up in a control frame when the track is cast.
        val fat = (0 until 40).joinToString("\n") { "[00:%02d.00]".format(it) + "x".repeat(1_000) }
        val total = parseLyrics(fat).sumOf { it.text.length }
        assertTrue(total <= CastContract.MAX_LYRIC_CHARS, "$total characters got through")
    }
}
