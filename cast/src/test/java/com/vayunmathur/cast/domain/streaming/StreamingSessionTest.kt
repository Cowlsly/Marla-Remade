package com.vayunmathur.cast.domain.streaming

import com.vayunmathur.cast.domain.CastDeviceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingSessionTest {

    private fun tvSession() = StreamingSession(StreamSelection.offer(CastDeviceKind.Tv))

    private fun speakerSession() = StreamingSession(StreamSelection.offer(CastDeviceKind.Speaker))

    private fun answer(
        udpPort: Int = 47505,
        sendIndexes: List<Int> = listOf(0, 1),
        ssrcs: List<Long> = listOf(20002, 50002),
    ) = AnswerMessage(
        type = "ANSWER",
        seqNum = 2,
        result = "ok",
        answer = Answer(udpPort = udpPort, sendIndexes = sendIndexes, ssrcs = ssrcs),
    )

    private fun frame(id: Long, size: Int = 4) = EncryptedFrame(
        frameId = FrameId(id),
        referencedFrameId = FrameId(id - 1),
        rtpTimestamp = id * 3000,
        isKeyFrame = id == 0L,
        payload = ByteArray(size),
    )

    @Test
    fun `a two-stream answer produces audio and video routes`() {
        val session = tvSession()
        assertNull(session.onAnswer(answer()))
        val negotiation = assertNotNull(session.negotiation)
        assertEquals(47505, negotiation.udpPort)
        assertEquals(2, negotiation.streams.size)
        assertTrue(negotiation.hasVideo)
        assertEquals(20002L, negotiation.audio?.receiverSsrc)
        assertEquals(50002L, negotiation.video?.receiverSsrc)
        assertEquals(StreamSelection.AUDIO_TIMEBASE, negotiation.audio?.timebase)
        assertEquals(StreamSelection.VIDEO_TIMEBASE, negotiation.video?.timebase)
    }

    @Test
    fun `the answer decides the stream set, not the offer`() {
        // A TV that accepts audio only is entitled to; sending video anyway would be sending to an
        // SSRC nobody is listening on.
        val session = tvSession()
        assertNull(session.onAnswer(answer(sendIndexes = listOf(0), ssrcs = listOf(20002))))
        val negotiation = assertNotNull(session.negotiation)
        assertFalse(negotiation.hasVideo)
        assertEquals(1, negotiation.streams.size)
    }

    @Test
    fun `receiver ssrcs are taken from the answer rather than derived`() {
        // They happened to be ours-plus-one on every observed session. That is the receiver's
        // choice and must not be assumed.
        val session = tvSession()
        session.onAnswer(answer(ssrcs = listOf(9001, 9002)))
        assertEquals(9001L, session.negotiation?.audio?.receiverSsrc)
        assertEquals(9002L, session.negotiation?.video?.receiverSsrc)
    }

    @Test
    fun `an error answer is reported and leaves no negotiation`() {
        val session = tvSession()
        val failure = session.onAnswer(
            AnswerMessage(type = "ANSWER", result = "error", error = AnswerError(3, "nope")),
        )
        val refused = assertIs<NegotiationFailure.Refused>(failure)
        assertEquals(3, refused.code)
        assertEquals("nope", refused.description)
        assertNull(session.negotiation)
    }

    @Test
    fun `an answer accepting nothing we can send fails rather than half-starting`() {
        val session = speakerSession()
        // Index 1 was never offered to a speaker, so there are no keys for it.
        val failure = session.onAnswer(answer(sendIndexes = listOf(1), ssrcs = listOf(50002)))
        assertIs<NegotiationFailure.NoStreams>(failure)
        assertNull(session.negotiation)
    }

    @Test
    fun `a malformed answer is refused`() {
        val session = tvSession()
        assertIs<NegotiationFailure.Malformed>(
            session.onAnswer(answer(sendIndexes = listOf(0, 1), ssrcs = listOf(20002))),
        )
        assertIs<NegotiationFailure.Malformed>(session.onAnswer(answer(udpPort = 0)))
        assertIs<NegotiationFailure.Malformed>(
            session.onAnswer(AnswerMessage(type = "ANSWER", result = "ok", answer = null)),
        )
    }

    @Test
    fun `a nacked packet is resent from the buffer`() {
        val session = tvSession()
        repeat(5) { session.record(frame(it.toLong())) }
        val recovery = session.onFeedback(
            ReceiverFeedback(
                checkpoint = FrameId(1),
                playoutDelayMs = 400,
                nacks = listOf(PacketNack(FrameId(3), 0)),
                ackedFrames = emptyList(),
            ),
        )
        assertEquals(1, recovery.retransmissions.size)
        assertEquals(FrameId(3), recovery.retransmissions.single().frame.frameId)
        assertEquals(listOf(0), recovery.retransmissions.single().packetIds)
        assertFalse(recovery.needsKeyFrame)
    }

    @Test
    fun `the checkpoint drops everything up to and including itself`() {
        val session = tvSession()
        repeat(5) { session.record(frame(it.toLong())) }
        // Frames 0..2 are acknowledged, so a later NACK for frame 2 cannot be answered.
        session.onFeedback(ReceiverFeedback(FrameId(2), 400, emptyList(), emptyList()))
        val recovery = session.onFeedback(
            ReceiverFeedback(FrameId(2), 400, listOf(PacketNack(FrameId(2), 0)), emptyList()),
        )
        assertTrue(recovery.retransmissions.isEmpty())
        // Frame 2 is at or below the checkpoint, so it is history rather than a gap - no key frame.
        assertFalse(recovery.needsKeyFrame)
    }

    @Test
    fun `a nack for a frame that has fallen out of the buffer asks for a key frame`() {
        val session = tvSession()
        // More than the buffer holds, so the earliest frames are gone.
        repeat(80) { session.record(frame(it.toLong())) }
        val recovery = session.onFeedback(
            ReceiverFeedback(FrameId(0), 400, listOf(PacketNack(FrameId(5), 0)), emptyList()),
        )
        assertTrue(recovery.retransmissions.isEmpty())
        assertTrue(recovery.needsKeyFrame)
    }

    @Test
    fun `a whole-frame nack subsumes individual packet nacks for that frame`() {
        val session = tvSession()
        session.record(frame(1, size = 4000))
        val recovery = session.onFeedback(
            ReceiverFeedback(
                checkpoint = FrameId(0),
                playoutDelayMs = 400,
                nacks = listOf(
                    PacketNack(FrameId(1), 1),
                    PacketNack(FrameId(1), CastRtcp.ALL_PACKETS_LOST),
                ),
                ackedFrames = emptyList(),
            ),
        )
        assertEquals(1, recovery.retransmissions.size)
        // null means "every packet", so the individual id must not survive alongside it.
        assertNull(recovery.retransmissions.single().packetIds)
    }

    @Test
    fun `several nacks for one frame are coalesced into a single retransmission`() {
        val session = tvSession()
        session.record(frame(1, size = 4000))
        val recovery = session.onFeedback(
            ReceiverFeedback(
                checkpoint = FrameId(0),
                playoutDelayMs = 400,
                nacks = listOf(PacketNack(FrameId(1), 2), PacketNack(FrameId(1), 0)),
                ackedFrames = emptyList(),
            ),
        )
        assertEquals(1, recovery.retransmissions.size)
        assertEquals(listOf(0, 2), recovery.retransmissions.single().packetIds)
    }

    @Test
    fun `the retransmit buffer is bounded`() {
        // A receiver that stops sending feedback must not be able to grow the heap without limit.
        val session = tvSession()
        repeat(500) { session.record(frame(it.toLong(), size = 1000)) }
        val recovery = session.onFeedback(
            ReceiverFeedback(
                checkpoint = FrameId(0),
                playoutDelayMs = 400,
                nacks = (440L until 500L).map { PacketNack(FrameId(it), 0) },
                ackedFrames = emptyList(),
            ),
        )
        // The most recent frames survive; the oldest are gone.
        assertTrue(recovery.retransmissions.isNotEmpty())
        assertEquals(FrameId(499), recovery.retransmissions.last().frame.frameId)
        assertTrue(
            recovery.retransmissions.none { it.frame.frameId < FrameId(440) },
            "the buffer kept frames it should have dropped",
        )
    }
}
