package com.vayunmathur.cast.protocol

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the messages that set up a served content session.
 *
 * These three carry the whole of the new arrangement, so what matters is that every field survives
 * the trip. A dropped `certificateFingerprint` is a TV that trusts nothing and plays nothing; a
 * dropped `token` is a `403` from the phone's own proxy; a dropped `port` is a connection refused.
 * All three look identical from the sofa, so they are pinned here where they can be told apart.
 */
class ContentSessionTest {

    private val session = ContentSession(
        host = "192.168.0.14",
        port = 43_117,
        certificateFingerprint = ProtocolBase64.encode(ByteArray(32) { (it * 3).toByte() }),
        token = "Zm9vYmFyLXRva2VuLXRoYXQtaXMtdXJsLXNhZmU",
        video = false,
        appLabel = "Music",
    )

    @Test
    fun `a content session survives the trip with every field intact`() {
        val back = assertIs<ContentSession>(roundTrip(session))
        assertEquals(session, back)
        // Spelled out as well as compared, because an equals on a data class would pass just as
        // happily if two fields had swapped places in a hand-written encoder.
        assertEquals("192.168.0.14", back.host)
        assertEquals(43_117, back.port)
        assertEquals(session.certificateFingerprint, back.certificateFingerprint)
        assertEquals(session.token, back.token)
        assertFalse(back.video)
        assertEquals("Music", back.appLabel)
    }

    @Test
    fun `the fingerprint round-trips as the 32 bytes the TV has to pin`() {
        val raw = ByteArray(32) { (it * 3).toByte() }
        val back = assertIs<ContentSession>(roundTrip(session))
        val decoded = ProtocolBase64.decode(back.certificateFingerprint)
        assertTrue(raw.contentEquals(decoded), "the TV would pin the wrong certificate")
    }

    @Test
    fun `a video session says so`() {
        val back = assertIs<ContentSession>(roundTrip(session.copy(video = true)))
        assertTrue(back.video, "the TV decides whether to add a surface at all from this")
    }

    @Test
    fun `a refusal carries its reason`() {
        val back = assertIs<ContentReady>(
            roundTrip(ContentReady(accepted = false, detail = "no Opus decoder")),
        )
        assertFalse(back.accepted)
        assertEquals("no Opus decoder", back.detail)
    }

    @Test
    fun `an acceptance needs no reason`() {
        val back = assertIs<ContentReady>(roundTrip(ContentReady(accepted = true)))
        assertTrue(back.accepted)
        assertEquals("", back.detail)
    }

    @Test
    fun `a play request carries what to fetch and what is in it`() {
        val play = PlayMedia(resourceId = "251/17", mimeType = "audio/ogg", durationMs = 214_000)
        val back = assertIs<PlayMedia>(roundTrip(play))
        assertEquals(play, back)
        // Slashes are meaningful: everything after the token in the URL is the resource id, so a
        // segment addressed by itag and sequence number has to survive as one string.
        assertEquals("251/17", back.resourceId)
    }

    @Test
    fun `an unknown duration is zero rather than absent`() {
        val back = assertIs<PlayMedia>(roundTrip(PlayMedia("track-1", "audio/ogg")))
        assertEquals(0L, back.durationMs, "the TV draws no seek bar for a length it does not know")
    }

    @Test
    fun `the wire names are pinned`() {
        // Both ends switch on these strings, and a rename would be a message silently dropped by a
        // build that otherwise looks identical.
        assertTrue(ControlJson.encodeToString(session as ControlMessage).contains("\"CONTENT_SESSION\""))
        assertTrue(
            ControlJson.encodeToString(ContentReady(true) as ControlMessage)
                .contains("\"CONTENT_READY\""),
        )
        assertTrue(
            ControlJson.encodeToString(PlayMedia("a", "audio/ogg") as ControlMessage)
                .contains("\"PLAY_MEDIA\""),
        )
        assertTrue(ControlJson.encodeToString(Ping as ControlMessage).contains("\"PING\""))
    }

    @Test
    fun `a ping survives the trip and carries nothing`() {
        // The keep-alive has to decode on both ends or it is worse than useless: an unknown `type`
        // is treated exactly like a dead socket, so a ping a build did not recognise would end the
        // very session it was sent to preserve.
        assertIs<Ping>(roundTrip(Ping))
    }

    @Test
    fun `a ping goes out often enough to beat the read timeout`() {
        // 60 s is what both ends give a read. At a third of that, two consecutive pings can be lost
        // before either end concludes the other has gone.
        assertTrue(
            PING_INTERVAL_MS * 2 < 60_000L,
            "a single lost ping should not be able to end a healthy session",
        )
    }

    private fun roundTrip(message: ControlMessage): ControlMessage =
        ControlJson.decodeFromString(ControlJson.encodeToString(message))
}
