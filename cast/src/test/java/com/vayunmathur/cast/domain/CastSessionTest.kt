package com.vayunmathur.cast.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TRANSPORT_ID = "web-5"

private val RECEIVER_STATUS_RUNNING = """
    {"type":"RECEIVER_STATUS","requestId":1,"status":{
      "applications":[{"appId":"CC1AD845","displayName":"Default Media Receiver",
        "sessionId":"S1","statusText":"Ready To Cast","transportId":"$TRANSPORT_ID"}],
      "volume":{"level":0.4,"muted":false}}}
""".trimIndent()

private val RECEIVER_STATUS_IDLE = """
    {"type":"RECEIVER_STATUS","requestId":2,"status":{
      "applications":[{"appId":"E8C28D3C","displayName":"Backdrop","sessionId":"S9",
        "transportId":"web-9","isIdleScreen":true}],
      "volume":{"level":0.4,"muted":false}}}
""".trimIndent()

private val MEDIA_STATUS_PLAYING = """
    {"type":"MEDIA_STATUS","requestId":0,"status":[{"mediaSessionId":7,
      "playerState":"PLAYING","currentTime":12.5,
      "media":{"contentId":"http://192.168.1.9:41234/x","contentType":"video/mp4",
        "streamType":"BUFFERED","duration":300.0,
        "metadata":{"metadataType":0,"title":"Clip"}}}]}
""".trimIndent()

/** The periodic status the receiver sends after the first one: no `media` object. */
private val MEDIA_STATUS_TICK = """
    {"type":"MEDIA_STATUS","status":[{"mediaSessionId":7,"playerState":"PLAYING",
      "currentTime":19.0}]}
""".trimIndent()

private fun media(url: String, title: String? = null) = CastMediaInformation(
    contentId = url,
    contentType = "video/mp4",
    metadata = title?.let { CastMediaMetadata(title = it) },
)

private fun CastFrame.type(): String =
    CastJson.decodeFromString<CastEnvelope>(payload).type

class CastSessionTest {

    /** Drives a session to the point where media commands are legal. */
    private fun readySession(): CastSession = CastSession().apply {
        open()
        onMessage(CastNamespaces.RECEIVER, RECEIVER_STATUS_RUNNING)
        onMessage(CastNamespaces.MEDIA, MEDIA_STATUS_PLAYING)
    }

    @Test
    fun `open connects to the platform receiver and launches the default media receiver`() {
        val frames = CastSession().open()
        assertEquals(2, frames.size)
        assertEquals(CastNamespaces.CONNECTION, frames[0].namespace)
        assertEquals(RECEIVER_ID, frames[0].destinationId)
        assertEquals("CONNECT", frames[0].type())
        assertEquals(CastNamespaces.RECEIVER, frames[1].namespace)
        assertEquals(RECEIVER_ID, frames[1].destinationId)
        assertEquals("LAUNCH", frames[1].type())
        assertTrue(frames[1].payload.contains(DEFAULT_MEDIA_RECEIVER_APP_ID))
    }

    @Test
    fun `request ids start at one and never repeat`() {
        // Zero means "no response expected", so it must never be handed out, and a repeat
        // would make two responses indistinguishable.
        val session = CastSession()
        val ids = listOf(
            session.allocateRequestId(),
            session.allocateRequestId(),
            session.allocateRequestId(),
        )
        assertEquals(listOf(1, 2, 3), ids)
    }

    @Test
    fun `receiver status names the transport and the session joins it`() {
        val session = CastSession()
        session.open()
        val frames = session.onMessage(CastNamespaces.RECEIVER, RECEIVER_STATUS_RUNNING)
        assertEquals(CastPhase.Ready, session.state.phase)
        assertEquals("S1", session.state.sessionId)
        assertEquals(TRANSPORT_ID, session.state.transportId)
        assertEquals(0.4, session.state.volumeLevel)
        // The second CONNECT, to the app rather than to receiver-0. Without it every media
        // command is dropped with no error.
        assertEquals(1, frames.size)
        assertEquals(CastNamespaces.CONNECTION, frames[0].namespace)
        assertEquals(TRANSPORT_ID, frames[0].destinationId)
        assertEquals("CONNECT", frames[0].type())
    }

    @Test
    fun `a repeated receiver status does not rejoin`() {
        val session = CastSession()
        session.open()
        session.onMessage(CastNamespaces.RECEIVER, RECEIVER_STATUS_RUNNING)
        assertEquals(
            emptyList(),
            session.onMessage(CastNamespaces.RECEIVER, RECEIVER_STATUS_RUNNING),
        )
    }

    @Test
    fun `a load before the app is ready is held and replayed on join`() {
        val session = CastSession()
        session.open()
        // The user picks a file while LAUNCH is still in flight. Rejecting this would make
        // "pick a device" and "pick a file" order-dependent for no reason.
        assertEquals(emptyList(), session.load(media("http://h/v.mp4", "Held")))
        val frames = session.onMessage(CastNamespaces.RECEIVER, RECEIVER_STATUS_RUNNING)
        assertEquals(listOf("CONNECT", "LOAD"), frames.map { it.type() })
        val load = frames[1]
        assertEquals(CastNamespaces.MEDIA, load.namespace)
        assertEquals(TRANSPORT_ID, load.destinationId)
        assertTrue(load.payload.contains("http://h/v.mp4"))
        assertTrue(load.payload.contains("\"sessionId\":\"S1\""))
    }

    @Test
    fun `a load while ready goes straight out`() {
        val session = CastSession()
        session.open()
        session.onMessage(CastNamespaces.RECEIVER, RECEIVER_STATUS_RUNNING)
        val frames = session.load(media("http://h/v.mp4"))
        assertEquals(listOf("LOAD"), frames.map { it.type() })
        assertEquals(CastPlayerState.Buffering, session.state.playerState)
    }

    @Test
    fun `ping is answered with pong`() {
        val frames = CastSession().onMessage(CastNamespaces.HEARTBEAT, """{"type":"PING"}""")
        assertEquals(1, frames.size)
        assertEquals("PONG", frames[0].type())
        assertEquals(CastNamespaces.HEARTBEAT, frames[0].namespace)
    }

    @Test
    fun `media status supplies the media session id that commands need`() {
        val session = readySession()
        assertEquals(7, session.state.mediaSessionId)
        assertEquals(CastPlayerState.Playing, session.state.playerState)
        assertEquals(12.5, session.state.currentTimeSec)
        assertEquals(300.0, session.state.durationSec)
        assertEquals("Clip", session.state.title)
        val pause = session.pause().single()
        assertEquals(CastNamespaces.MEDIA, pause.namespace)
        assertEquals(TRANSPORT_ID, pause.destinationId)
        assertTrue(pause.payload.contains("\"mediaSessionId\":7"))
    }

    @Test
    fun `a periodic status without a media object keeps the title and duration`() {
        val session = readySession()
        session.onMessage(CastNamespaces.MEDIA, MEDIA_STATUS_TICK)
        assertEquals(19.0, session.state.currentTimeSec)
        assertEquals("Clip", session.state.title)
        assertEquals(300.0, session.state.durationSec)
    }

    @Test
    fun `an empty media status clears what is playing`() {
        val session = readySession()
        session.onMessage(CastNamespaces.MEDIA, """{"type":"MEDIA_STATUS","status":[]}""")
        assertNull(session.state.mediaSessionId)
        assertNull(session.state.title)
        assertEquals(CastPlayerState.Idle, session.state.playerState)
    }

    @Test
    fun `media commands are dropped when there is nothing to command`() {
        val session = CastSession()
        assertEquals(emptyList(), session.play())
        assertEquals(emptyList(), session.seek(10.0))
    }

    @Test
    fun `seek moves the reported position without waiting for the receiver`() {
        val session = readySession()
        val seek = session.seek(42.0).single()
        assertEquals("SEEK", seek.type())
        assertTrue(seek.payload.contains("\"currentTime\":42.0"))
        assertEquals(42.0, session.state.currentTimeSec)
    }

    @Test
    fun `volume goes to the platform receiver not the app`() {
        val session = readySession()
        val frame = session.setVolume(0.75).single()
        assertEquals(CastNamespaces.RECEIVER, frame.namespace)
        assertEquals(RECEIVER_ID, frame.destinationId)
        assertEquals("SET_VOLUME", frame.type())
        assertEquals(0.75, session.state.volumeLevel)
    }

    @Test
    fun `volume is clamped and mute is sent on its own`() {
        val session = readySession()
        session.setVolume(1.9)
        assertEquals(1.0, session.state.volumeLevel)
        val mute = session.setMuted(true).single()
        assertTrue(mute.payload.contains("\"muted\":true"))
        // explicitNulls is off, so an absent level must not become "level":null - the
        // receiver rejects that.
        assertFalse(mute.payload.contains("level"))
    }

    @Test
    fun `losing the app drops back to idle rather than failing`() {
        val session = readySession()
        session.onMessage(CastNamespaces.RECEIVER, RECEIVER_STATUS_IDLE)
        assertEquals(CastPhase.Idle, session.state.phase)
        assertNull(session.state.transportId)
        assertNull(session.state.sessionId)
    }

    @Test
    fun `a launch error reports the receiver's reason`() {
        val session = CastSession()
        session.open()
        session.onMessage(
            CastNamespaces.RECEIVER,
            """{"type":"LAUNCH_ERROR","requestId":1,"reason":"CANCELLED"}""",
        )
        assertEquals(CastPhase.Failed, session.state.phase)
        assertEquals("CANCELLED", session.state.failure)
    }

    @Test
    fun `close stops the app and closes both connections`() {
        val session = readySession()
        val frames = session.close()
        assertEquals(listOf("STOP", "CLOSE", "CLOSE"), frames.map { it.type() })
        assertEquals(CastNamespaces.RECEIVER, frames[0].namespace)
        assertEquals(TRANSPORT_ID, frames[1].destinationId)
        assertEquals(RECEIVER_ID, frames[2].destinationId)
        assertEquals(CastPhase.Idle, session.state.phase)
    }

    @Test
    fun `a close from the receiver ends the session`() {
        val session = readySession()
        session.onMessage(CastNamespaces.CONNECTION, """{"type":"CLOSE"}""")
        assertEquals(CastPhase.Idle, session.state.phase)
        assertNull(session.state.transportId)
    }

    @Test
    fun `an unparseable payload is ignored`() {
        val session = readySession()
        assertEquals(emptyList(), session.onMessage(CastNamespaces.MEDIA, "not json"))
        assertEquals(CastPlayerState.Playing, session.state.playerState)
    }
}
