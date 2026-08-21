package com.vayunmathur.cast.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TRANSPORT_ID = "691b2c09-797c-4699-adf3-3f7f4d781448"

/**
 * A real RECEIVER_STATUS from a Google TV, captured during the Phase 0 spike.
 *
 * Note `transportId` equals `sessionId` and both are UUIDs - the media receiver used `web-N`, so
 * this shape is what the join logic actually has to cope with.
 */
private val RECEIVER_STATUS_RUNNING = """
    {"type":"RECEIVER_STATUS","requestId":1,"status":{
      "applications":[{"appId":"674A0243","displayName":"Android Mirroring",
        "sessionId":"$TRANSPORT_ID","statusText":"Mirroring","transportId":"$TRANSPORT_ID"}],
      "volume":{"level":0.4,"muted":false}}}
""".trimIndent()

private val RECEIVER_STATUS_IDLE = """
    {"type":"RECEIVER_STATUS","requestId":2,"status":{
      "applications":[{"appId":"E8C28D3C","displayName":"Backdrop","sessionId":"S9",
        "transportId":"web-9","isIdleScreen":true}],
      "volume":{"level":0.4,"muted":false}}}
""".trimIndent()

/** A real ANSWER from a TV, kept as the shape Phase 2 has to parse. */
private val ANSWER_TV = """
    {"answer":{"display":{"dimensions":{"frameRate":"60","height":2160,"width":3840},
      "scaling":"sender"},"sendIndexes":[0,1],"ssrcs":[20002,50002],"udpPort":47505},
      "result":"ok","seqNum":2,"type":"ANSWER"}
""".trimIndent()

private fun CastFrame.type(): String =
    CastJson.decodeFromString<CastEnvelope>(payload).type

class CastSessionTest {

    private fun session(appId: String = MirroringAppIds.AUDIO_VIDEO) = CastSession(appId)

    /** Drives a session to the point where the app is joined and addressable. */
    private fun readySession(): CastSession = session().apply {
        open()
        onMessage(CastNamespaces.RECEIVER, RECEIVER_STATUS_RUNNING)
    }

    @Test
    fun `a device with a screen gets the audio-video app id and a speaker the audio-only one`() {
        // Not a preference: Phase 0 established that a receiver refuses the wrong one at LAUNCH,
        // with NOT_FOUND for audio-only against a TV and SYSTEM_ERROR for A/V against a speaker.
        assertEquals(MirroringAppIds.AUDIO_VIDEO, MirroringAppIds.forKind(CastDeviceKind.Tv))
        assertEquals(MirroringAppIds.AUDIO_ONLY, MirroringAppIds.forKind(CastDeviceKind.Speaker))
        assertEquals(MirroringAppIds.AUDIO_ONLY, MirroringAppIds.forKind(CastDeviceKind.Group))
    }

    @Test
    fun `open connects to the platform receiver and launches the app it was given`() {
        val frames = session(MirroringAppIds.AUDIO_ONLY).open()
        assertEquals(2, frames.size)
        assertEquals(CastNamespaces.CONNECTION, frames[0].namespace)
        assertEquals(RECEIVER_ID, frames[0].destinationId)
        assertEquals("CONNECT", frames[0].type())
        assertEquals(CastNamespaces.RECEIVER, frames[1].namespace)
        assertEquals(RECEIVER_ID, frames[1].destinationId)
        assertEquals("LAUNCH", frames[1].type())
        assertTrue(frames[1].payload.contains(MirroringAppIds.AUDIO_ONLY))
    }

    @Test
    fun `a receiver status for a different app id is not joined`() {
        // The audio-only receiver running on a device we asked for A/V is somebody else's
        // session, and joining it would address frames at an app that cannot use them.
        val session = session(MirroringAppIds.AUDIO_VIDEO)
        session.open()
        assertEquals(
            emptyList(),
            session.onMessage(CastNamespaces.RECEIVER, RECEIVER_STATUS_IDLE),
        )
        assertNull(session.state.transportId)
    }

    @Test
    fun `request ids start at one and never repeat`() {
        // Zero means "no response expected", so it must never be handed out, and a repeat
        // would make two responses indistinguishable.
        val session = session()
        val ids = listOf(
            session.allocateRequestId(),
            session.allocateRequestId(),
            session.allocateRequestId(),
        )
        assertEquals(listOf(1, 2, 3), ids)
    }

    @Test
    fun `receiver status names the transport and the session joins it`() {
        val session = session()
        session.open()
        val frames = session.onMessage(CastNamespaces.RECEIVER, RECEIVER_STATUS_RUNNING)
        assertEquals(CastPhase.Ready, session.state.phase)
        assertEquals(TRANSPORT_ID, session.state.sessionId)
        assertEquals(TRANSPORT_ID, session.state.transportId)
        assertEquals(0.4, session.state.volumeLevel)
        // The second CONNECT, to the app rather than to receiver-0. Without it every frame on
        // the webrtc namespace is dropped with no error.
        assertEquals(1, frames.size)
        assertEquals(CastNamespaces.CONNECTION, frames[0].namespace)
        assertEquals(TRANSPORT_ID, frames[0].destinationId)
        assertEquals("CONNECT", frames[0].type())
    }

    @Test
    fun `a repeated receiver status does not rejoin`() {
        val session = readySession()
        assertEquals(
            emptyList(),
            session.onMessage(CastNamespaces.RECEIVER, RECEIVER_STATUS_RUNNING),
        )
    }

    @Test
    fun `ping is answered with pong`() {
        val frames = session().onMessage(CastNamespaces.HEARTBEAT, """{"type":"PING"}""")
        assertEquals(1, frames.size)
        assertEquals("PONG", frames[0].type())
        assertEquals(CastNamespaces.HEARTBEAT, frames[0].namespace)
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
        // NOT_FOUND is what a TV answers when asked for the audio-only receiver, and
        // SYSTEM_ERROR is what a speaker answers when asked for the A/V one.
        val session = session()
        session.open()
        session.onMessage(
            CastNamespaces.RECEIVER,
            """{"type":"LAUNCH_ERROR","requestId":1,"reason":"NOT_FOUND"}""",
        )
        assertEquals(CastPhase.Failed, session.state.phase)
        assertEquals("NOT_FOUND", session.state.failure)
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
        assertEquals(emptyList(), session.onMessage(CastNamespaces.RECEIVER, "not json"))
        assertEquals(CastPhase.Ready, session.state.phase)
    }

    @Test
    fun `a namespace with no branch is ignored`() {
        // A speaker emits com.google.cast.multizone continuously, and the webrtc namespace has
        // no handler until there is a streaming session to hand it to. Neither may disturb the
        // control plane.
        val session = readySession()
        assertEquals(
            emptyList(),
            session.onMessage("urn:x-cast:com.google.cast.multizone", """{"type":"WHATEVER"}"""),
        )
        assertEquals(emptyList(), session.onMessage(CastNamespaces.WEBRTC, ANSWER_TV))
        assertEquals(CastPhase.Ready, session.state.phase)
    }
}
