package com.vayunmathur.cast.service

import com.vayunmathur.sdk.cast.CastContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trust boundary between an app's now-playing metadata and the control channel.
 *
 * A first-party app is trusted, and that is exactly why this exists: a bug in one must not be able to
 * end a session by overflowing `ControlFraming.MAX_FRAME_BYTES`, because a frame over the bound is
 * refused rather than sent and the connection goes with it. The mapping is a `Bundle`-free function so
 * that every decision it makes is provable here rather than on a television.
 */
class NowPlayingMappingTest {

    private fun map(
        resourceId: String = "1234",
        title: String = "",
        author: String = "",
        album: String = "",
        artworkResourceId: String = "",
        times: LongArray? = null,
        texts: Array<String>? = null,
        plainLyrics: String = "",
    ) = nowPlayingOf(
        resourceId = resourceId,
        title = title,
        author = author,
        album = album,
        artworkResourceId = artworkResourceId,
        lyricTimesMs = times,
        lyricTexts = texts,
        plainLyrics = plainLyrics,
    )

    @Test
    fun `a well-formed snapshot passes through`() {
        val mapped = assertNotNull(
            map(
                title = "Blue Monday",
                author = "New Order",
                album = "Substance",
                artworkResourceId = "art-1234",
                times = longArrayOf(0, 4_120),
                texts = arrayOf("how does it feel", "to treat me like you do"),
            ),
        )
        assertEquals("1234", mapped.resourceId)
        assertEquals("New Order", mapped.author)
        assertEquals("art-1234", mapped.artworkResourceId)
        assertEquals(listOf(0L, 4_120L), mapped.lyrics.map { it.atMs })
    }

    @Test
    fun `a mirrored session's title and author pass through with no resource id`() {
        // The `Surface` case: no resource is being served, so there is no id to name - which used to be
        // refused here, back when a served session was the only one that could send metadata at all.
        val mapped = assertNotNull(
            map(resourceId = "", title = "Blue Monday (Official Video)", author = "New Order"),
        )
        assertEquals("", mapped.resourceId)
        assertEquals("Blue Monday (Official Video)", mapped.title)
        assertEquals("New Order", mapped.author)
    }

    @Test
    fun `a snapshot with nothing in it is refused`() {
        // Not a snapshot at all - forwarding it would blank the television's headline and replace a
        // title with nothing. Distinct from the case above, which has no *resource* but plenty to show.
        assertNull(map())
        assertNull(map(resourceId = ""))
    }

    @Test
    fun `an artwork id alone is enough to be worth sending`() {
        // The second of music's two snapshots per track can be nothing but a cover, if the file had no
        // readable tags beyond what the first one already carried.
        assertNotNull(map(artworkResourceId = "art-1234"))
    }

    @Test
    fun `mismatched array lengths drop the lyrics whole`() {
        // Refused rather than truncated to the shorter: the halves disagreeing means the sender is
        // confused about its own lyrics, and guessing would put the wrong words at the wrong times.
        val mapped = assertNotNull(
            map(title = "Blue Monday", times = longArrayOf(0, 1_000, 2_000), texts = arrayOf("one", "two")),
        )
        assertTrue(mapped.lyrics.isEmpty())
    }

    @Test
    fun `one array without the other is not lyrics`() {
        assertTrue(assertNotNull(map(title = "a", times = longArrayOf(0, 1_000))).lyrics.isEmpty())
        assertTrue(assertNotNull(map(title = "a", texts = arrayOf("one", "two"))).lyrics.isEmpty())
    }

    @Test
    fun `plain lyrics are dropped when there are timed ones`() {
        // So the two can never both be on screen, and the television needs no precedence rule of its
        // own to get wrong.
        val mapped = assertNotNull(
            map(
                times = longArrayOf(0),
                texts = arrayOf("how does it feel"),
                plainLyrics = "the whole song, untimed",
            ),
        )
        assertEquals(1, mapped.lyrics.size)
        assertEquals("", mapped.plainLyrics)
    }

    @Test
    fun `plain lyrics survive when there are no timed ones`() {
        val mapped = assertNotNull(map(plainLyrics = "the whole song, untimed"))
        assertEquals("the whole song, untimed", mapped.plainLyrics)
    }

    @Test
    fun `too many lines are clamped`() {
        val count = CastContract.MAX_LYRIC_LINES * 3
        val mapped = assertNotNull(
            map(times = LongArray(count) { it * 100L }, texts = Array(count) { "a" }),
        )
        assertEquals(CastContract.MAX_LYRIC_LINES, mapped.lyrics.size)
    }

    @Test
    fun `too many characters are clamped even in few lines`() {
        // The bound the line count cannot catch: ten lines of a megabyte each is well inside
        // MAX_LYRIC_LINES and well outside a control frame.
        val mapped = assertNotNull(
            map(times = LongArray(10), texts = Array(10) { "x".repeat(1_000_000) }),
        )
        assertTrue(
            mapped.lyrics.sumOf { it.text.length } <= CastContract.MAX_LYRIC_CHARS,
            "an oversize lyric set got through",
        )
    }

    @Test
    fun `an oversize title is clamped rather than refused`() {
        // Refusing would lose the whole snapshot, including the cover, over a field nothing can render
        // past a couple of lines anyway.
        val mapped = assertNotNull(map(title = "x".repeat(CastContract.MAX_LYRIC_CHARS * 4)))
        assertEquals(CastContract.MAX_LYRIC_CHARS, mapped.title.length)
    }
}
