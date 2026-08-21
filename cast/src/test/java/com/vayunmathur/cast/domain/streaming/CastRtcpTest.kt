package com.vayunmathur.cast.domain.streaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RECEIVER_SSRC = 20_002L
private const val SENDER_SSRC = 20_001L

class CastRtcpTest {

    /**
     * Builds a feedback packet the way a receiver does, so the parser is exercised against the
     * documented layout rather than against itself.
     */
    private fun feedback(
        checkpoint: Int,
        playoutDelayMs: Int = 400,
        lossFields: List<Triple<Int, Int, Int>> = emptyList(),
        ackBitVector: List<Int>? = null,
    ): ByteArray {
        val body = mutableListOf<Byte>()
        fun int32(v: Long) {
            body += (v ushr 24).toByte(); body += (v ushr 16).toByte()
            body += (v ushr 8).toByte(); body += v.toByte()
        }
        fun int16(v: Int) {
            body += (v ushr 8).toByte(); body += v.toByte()
        }
        int32(RECEIVER_SSRC)
        int32(SENDER_SSRC)
        int32(0x43_41_53_54) // 'CAST'
        body += checkpoint.toByte()
        body += lossFields.size.toByte()
        int16(playoutDelayMs)
        for ((frameId, packetId, bits) in lossFields) {
            body += frameId.toByte()
            int16(packetId)
            body += bits.toByte()
        }
        if (ackBitVector != null) {
            int32(0x43_53_54_32) // 'CST2'
            body += 0 // feedback count, unused
            body += ackBitVector.size.toByte()
            ackBitVector.forEach { body += it.toByte() }
        }
        val header = byteArrayOf(
            ((0b100 shl 5) or 15).toByte(), // version 2, subtype kFeedback
            204.toByte(), // kApplicationDefined
            0, 0, // length, unchecked by the parser
        )
        return header + body.toByteArray()
    }

    @Test
    fun `a sender report has the documented layout`() {
        val packet = CastRtcp.senderReport(
            senderSsrc = SENDER_SSRC,
            ntpTimestamp = 0x0192a3b4c5d6e7f8,
            rtpTimestamp = 0xAABBCCDD,
            packetCount = 7,
            octetCount = 4096,
        )
        assertEquals(28, packet.size)
        assertEquals(0x80, packet[0].toInt() and 0xff) // no report blocks
        assertEquals(200, packet[1].toInt() and 0xff)
        // The length field counts 32-bit words after the first, so 24/4 rather than 28/4.
        assertEquals(6, packet.getShort(2))
        assertEquals(SENDER_SSRC, packet.getInt(4))
        assertEquals(0x0192a3b4L, packet.getInt(8))
        assertEquals(0xc5d6e7f8L, packet.getInt(12))
        assertEquals(0xAABBCCDDL, packet.getInt(16))
        assertEquals(7L, packet.getInt(20))
        assertEquals(4096L, packet.getInt(24))
    }

    @Test
    fun `a bare checkpoint parses`() {
        val parsed = assertNotNull(
            CastRtcp.parseFeedback(feedback(checkpoint = 5), RECEIVER_SSRC, SENDER_SSRC, FrameId(5)),
        )
        assertEquals(FrameId(5), parsed.checkpoint)
        assertEquals(400, parsed.playoutDelayMs)
        assertTrue(parsed.nacks.isEmpty())
        assertTrue(parsed.ackedFrames.isEmpty())
    }

    @Test
    fun `a loss field's bit vector expands into further packet ids`() {
        // Each set bit is the next packet id up from the one named, so 0b101 after packet 3 means
        // 4 and 6 are also missing.
        val parsed = assertNotNull(
            CastRtcp.parseFeedback(
                feedback(checkpoint = 10, lossFields = listOf(Triple(11, 3, 0b101))),
                RECEIVER_SSRC,
                SENDER_SSRC,
                FrameId(12),
            ),
        )
        assertEquals(
            listOf(
                PacketNack(FrameId(11), 3),
                PacketNack(FrameId(11), 4),
                PacketNack(FrameId(11), 6),
            ),
            parsed.nacks,
        )
    }

    @Test
    fun `the all-packets-lost id means the whole frame and suppresses the bit vector`() {
        val parsed = assertNotNull(
            CastRtcp.parseFeedback(
                feedback(checkpoint = 1, lossFields = listOf(Triple(2, 0xffff, 0xff))),
                RECEIVER_SSRC,
                SENDER_SSRC,
                FrameId(3),
            ),
        )
        assertEquals(1, parsed.nacks.size)
        assertTrue(parsed.nacks.single().isWholeFrame)
        assertEquals(FrameId(2), parsed.nacks.single().frameId)
    }

    @Test
    fun `CST2 acks start two frames after the checkpoint`() {
        // The "plus two" is openscreen's, documented in rtp_defines.h: the checkpoint frame is
        // implicitly acked and the vector begins beyond the frame following it.
        val parsed = assertNotNull(
            CastRtcp.parseFeedback(
                feedback(checkpoint = 4, ackBitVector = listOf(0b0000_0101)),
                RECEIVER_SSRC,
                SENDER_SSRC,
                FrameId(10),
            ),
        )
        assertEquals(listOf(FrameId(6), FrameId(8)), parsed.ackedFrames)
    }

    @Test
    fun `a second ack octet continues eight frames further on`() {
        val parsed = assertNotNull(
            CastRtcp.parseFeedback(
                feedback(checkpoint = 0, ackBitVector = listOf(0b0000_0001, 0b0000_0001)),
                RECEIVER_SSRC,
                SENDER_SSRC,
                FrameId(20),
            ),
        )
        assertEquals(listOf(FrameId(2), FrameId(10)), parsed.ackedFrames)
    }

    @Test
    fun `trailing bytes that are not CST2 are tolerated rather than treated as corrupt`() {
        // openscreen is explicit about this for backwards compatibility.
        val packet = feedback(checkpoint = 3) + byteArrayOf(1, 2, 3, 4, 5, 6)
        val parsed = assertNotNull(
            CastRtcp.parseFeedback(packet, RECEIVER_SSRC, SENDER_SSRC, FrameId(3)),
        )
        assertEquals(FrameId(3), parsed.checkpoint)
        assertTrue(parsed.ackedFrames.isEmpty())
    }

    @Test
    fun `packets for another session or of another type are rejected`() {
        val packet = feedback(checkpoint = 1)
        assertNull(CastRtcp.parseFeedback(packet, 999L, SENDER_SSRC, FrameId(1)))
        assertNull(CastRtcp.parseFeedback(packet, RECEIVER_SSRC, 999L, FrameId(1)))
        // A sender report is not feedback.
        assertNull(
            CastRtcp.parseFeedback(
                CastRtcp.senderReport(SENDER_SSRC, 0, 0, 0, 0),
                RECEIVER_SSRC,
                SENDER_SSRC,
                FrameId(1),
            ),
        )
        // Truncated.
        assertNull(
            CastRtcp.parseFeedback(packet.copyOfRange(0, 8), RECEIVER_SSRC, SENDER_SSRC, FrameId(1)),
        )
    }

    @Test
    fun `a loss field count that overruns the packet is refused`() {
        // Claiming four loss fields while carrying none must not read past the end.
        val packet = feedback(checkpoint = 1).copyOf()
        packet[4 + 12 + 1] = 4
        assertNull(CastRtcp.parseFeedback(packet, RECEIVER_SSRC, SENDER_SSRC, FrameId(1)))
    }

    @Test
    fun `the checkpoint expands across an eight-bit wrap`() {
        // Frame 260 truncates to 4. Read naively the checkpoint would look like frame 4 and the
        // retransmit buffer would never be drained again.
        assertEquals(
            FrameId(260),
            CastRtcp.expandLessThanOrEqual(FrameId(262), 260 and 0xff),
        )
        assertEquals(FrameId(255), CastRtcp.expandLessThanOrEqual(FrameId(256), 255))
        val parsed = assertNotNull(
            CastRtcp.parseFeedback(
                feedback(checkpoint = 260 and 0xff),
                RECEIVER_SSRC,
                SENDER_SSRC,
                FrameId(262),
            ),
        )
        assertEquals(FrameId(260), parsed.checkpoint)
    }

    @Test
    fun `a nacked frame id above the checkpoint expands upwards`() {
        assertEquals(FrameId(257), CastRtcp.expandGreaterThan(FrameId(255), 1))
        // Equal truncations mean a full lap, not the same frame.
        assertEquals(FrameId(511), CastRtcp.expandGreaterThan(FrameId(255), 255))
    }
}
