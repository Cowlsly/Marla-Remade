package com.vayunmathur.cast.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The message that gives the television something to show.
 *
 * Round-trips first, because `encodeDefaults` and `explicitNulls = false` interact with an all-but-one
 * defaulted message in ways that are cheap to prove and expensive to discover on a panel. Then the
 * frame bound, which is the failure that would take the whole session with it rather than just the
 * lyrics: a control frame over [ControlFraming.MAX_FRAME_BYTES] is refused rather than sent.
 */
class NowPlayingTest {

    private val codec = ControlCodec()

    @Test
    fun `a full snapshot round-trips`() {
        val message = NowPlaying(
            resourceId = "1234",
            title = "Blue Monday",
            author = "New Order",
            album = "Substance",
            artworkResourceId = "art-1234",
            lyrics = listOf(
                LyricLine(0, "how does it feel"),
                LyricLine(4_120, "to treat me like you do"),
            ),
        )
        assertEquals(message, codec.decode(codec.encode(message)))
    }

    @Test
    fun `a mirrored session's snapshot round-trips with no resource id`() {
        // What an app drawing into a `Surface` sends: two strings and nothing else. Every other field
        // defaulted at once, which is the case `encodeDefaults` could quietly eat - and an absent
        // `resourceId` has to survive as an empty string rather than becoming a missing key, because
        // the receiver reads it as "render this now".
        val message = NowPlaying(title = "Blue Monday (Official Video)", author = "New Order")
        val decoded = assertNotNull(codec.decode(codec.encode(message))) as NowPlaying
        assertEquals(message, decoded)
        assertEquals("", decoded.resourceId)
        assertEquals("", decoded.album)
        assertEquals("", decoded.artworkResourceId)
    }

    @Test
    fun `a snapshot with nothing but a resource id round-trips`() {
        // The case a phone sends the instant it knows what it is playing, before it has read a cover
        // or a lyric tag - and the case every default at once, which `encodeDefaults` could eat.
        val message = NowPlaying(resourceId = "1234")
        val decoded = assertNotNull(codec.decode(codec.encode(message))) as NowPlaying
        assertEquals(message, decoded)
        assertTrue(decoded.lyrics.isEmpty())
        assertEquals("", decoded.plainLyrics)
    }

    @Test
    fun `untimed lyrics travel in their own field`() {
        // Two fields rather than a list of `atMs = -1` sentinels, which would pin the highlight to the
        // last line for the whole song. Proven here because it is the shape of the wire, not a UI
        // detail: nothing downstream can recover the distinction if it is lost.
        val message = NowPlaying(resourceId = "9", plainLyrics = "line one\nline two")
        val decoded = assertNotNull(codec.decode(codec.encode(message))) as NowPlaying
        assertTrue(decoded.lyrics.isEmpty())
        assertEquals("line one\nline two", decoded.plainLyrics)
    }

    @Test
    fun `a field this build does not know is ignored rather than fatal`() {
        // `ignoreUnknownKeys`, which is the only forward compatibility a refused-on-mismatch protocol
        // has - and the reason a newer phone adding a field does not hang up an older television.
        val json = """{"type":"NOW_PLAYING","resourceId":"7","title":"Ceremony","composer":"nobody"}"""
        val decoded = assertNotNull(
            ControlJson.decodeFromString<ControlMessage>(json),
        ) as NowPlaying
        assertEquals("7", decoded.resourceId)
        assertEquals("Ceremony", decoded.title)
    }

    @Test
    fun `a worst-case clamped lyric set fits in one control frame`() {
        // The numbers `:cast` clamps to, at their limit and then some: 400 lines whose total length is
        // the 16,000-character bound. If this ever exceeds the frame limit the session dies rather
        // than the lyrics, so the two constants have to be checked against each other somewhere.
        val perLine = 16_000 / 400
        val lines = List(400) { LyricLine(it * 3_000L, "x".repeat(perLine)) }
        val message = NowPlaying(
            resourceId = "1234",
            title = "x".repeat(200),
            author = "x".repeat(200),
            album = "x".repeat(200),
            artworkResourceId = "art-1234",
            lyrics = lines,
        )
        val encoded = codec.encode(message)
        assertTrue(
            encoded.size < ControlFraming.MAX_FRAME_BYTES,
            "a clamped lyric set encoded to ${encoded.size} bytes",
        )
        assertEquals(message, codec.decode(encoded))
    }

    @Test
    fun `the messages a served session already sent still round-trip`() {
        // The version bump reshaped nothing, and this is what says so: `NOW_PLAYING` is additive on
        // the wire even though the handshake refuses a peer that does not know it.
        val messages = listOf<ControlMessage>(
            Ping,
            PlayMedia(resourceId = "1234", mimeType = "audio/ogg", durationMs = 214_000),
            PlaybackState(positionMs = 1_000, durationMs = 214_000, playing = true, buffering = false),
        )
        for (message in messages) {
            assertEquals(message, codec.decode(codec.encode(message)), "$message did not round-trip")
        }
    }
}
