package com.vayunmathur.cast.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RECEIVER_SSRC = 20_002L
private const val SENDER_SSRC = 20_001L

private const val TYPE_PAYLOAD_SPECIFIC = 206
private const val SUBTYPE_PLI = 1
private const val SUBTYPE_FEEDBACK = 15

class RtcpTest {

    /**
     * Builds a compound RTCP datagram the way a receiver does: a Receiver Report first, then the
     * Cast feedback as a `kPayloadSpecific` (206) sub-packet.
     *
     * The leading report is not decoration - a parser that only reads the first sub-packet finds no
     * feedback at all, which is the bug this shape exists to catch.
     */
    private fun feedback(
        checkpoint: Int,
        playoutDelayMs: Int = 400,
        lossFields: List<Triple<Int, Int, Int>> = emptyList(),
        ackBitVector: List<Int>? = null,
        withLeadingReport: Boolean = true,
        pictureLoss: Boolean = false,
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
        // Padded to a word boundary, because the length field counts 32-bit words.
        while (body.size % 4 != 0) body += 0
        val out = mutableListOf<Byte>()
        if (withLeadingReport) out += receiverReport()
        if (pictureLoss) out += subPacket(SUBTYPE_PLI, TYPE_PAYLOAD_SPECIFIC, pliBody())
        out += subPacket(SUBTYPE_FEEDBACK, TYPE_PAYLOAD_SPECIFIC, body)
        return out.toByteArray()
    }

    /** A Receiver Report (201) with no report blocks, which is what leads a real datagram. */
    private fun receiverReport(): List<Byte> =
        subPacket(0, 201, mutableListOf<Byte>().also { b ->
            for (shift in intArrayOf(24, 16, 8, 0)) b += (RECEIVER_SSRC ushr shift).toByte()
        })

    private fun pliBody(): MutableList<Byte> = mutableListOf<Byte>().also { b ->
        for (shift in intArrayOf(24, 16, 8, 0)) b += (RECEIVER_SSRC ushr shift).toByte()
        for (shift in intArrayOf(24, 16, 8, 0)) b += (SENDER_SSRC ushr shift).toByte()
    }

    private fun subPacket(subtype: Int, type: Int, payload: List<Byte>): List<Byte> {
        val header = listOf(
            (((0b100 shl 5)) or subtype).toByte(),
            type.toByte(),
            ((payload.size / 4) ushr 8).toByte(),
            (payload.size / 4).toByte(),
        )
        return header + payload
    }

    private fun parse(packet: ByteArray, maxFrameId: FrameId) =
        Rtcp.parse(packet, RECEIVER_SSRC, SENDER_SSRC, maxFrameId)

    @Test
    fun `a sender report has the documented layout`() {
        val packet = Rtcp.senderReport(
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
            parse(feedback(checkpoint = 5), FrameId(5)),
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
            parse(feedback(checkpoint = 10, lossFields = listOf(Triple(11, 3, 0b101))), FrameId(12)),
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
            parse(feedback(checkpoint = 1, lossFields = listOf(Triple(2, 0xffff, 0xff))), FrameId(3)),
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
            parse(feedback(checkpoint = 4, ackBitVector = listOf(0b0000_0101)), FrameId(10)),
        )
        assertEquals(listOf(FrameId(6), FrameId(8)), parsed.ackedFrames)
    }

    @Test
    fun `a second ack octet continues eight frames further on`() {
        val parsed = assertNotNull(
            parse(feedback(checkpoint = 0, ackBitVector = listOf(0b0000_0001, 0b0000_0001)), FrameId(20)),
        )
        assertEquals(listOf(FrameId(2), FrameId(10)), parsed.ackedFrames)
    }

    @Test
    fun `trailing bytes that are not CST2 are tolerated rather than treated as corrupt`() {
        // openscreen is explicit about this for backwards compatibility. The bytes have to sit
        // inside the feedback sub-packet's own length to be "trailing" rather than a new sub-packet.
        val parsed = assertNotNull(
            parse(feedback(checkpoint = 3, ackBitVector = null), FrameId(3)),
        )
        assertEquals(FrameId(3), parsed.checkpoint)
        assertTrue(parsed.ackedFrames.isEmpty())
    }

    @Test
    fun `feedback is found after a leading receiver report`() {
        // The bug this whole test file was reshaped for. A real receiver leads with a Receiver
        // Report, so a parser that reads only the first sub-packet reports no feedback ever - which
        // is indistinguishable from a receiver that is not answering at all.
        val compound = feedback(checkpoint = 7, withLeadingReport = true)
        val parsed = assertNotNull(parse(compound, FrameId(7)))
        assertEquals(FrameId(7), parsed.checkpoint)
        // And it still works when the feedback happens to come first.
        assertNotNull(parse(feedback(checkpoint = 7, withLeadingReport = false), FrameId(7)))
    }

    @Test
    fun `a picture loss indicator is surfaced even with no feedback block`() {
        // PLI means "I cannot decode anything I have" - the only answer is a key frame, so it must
        // not be swallowed.
        val parsed = assertNotNull(
            parse(feedback(checkpoint = 3, pictureLoss = true), FrameId(3)),
        )
        assertTrue(parsed.pictureLoss)
        assertFalse(assertNotNull(parse(feedback(checkpoint = 3), FrameId(3))).pictureLoss)
    }

    @Test
    fun `packets for another session or of another type are rejected`() {
        val packet = feedback(checkpoint = 1)
        assertNull(Rtcp.parse(packet, 999L, SENDER_SSRC, FrameId(1)))
        assertNull(Rtcp.parse(packet, RECEIVER_SSRC, 999L, FrameId(1)))
        // A sender report is not feedback.
        assertNull(
            Rtcp.parse(
                Rtcp.senderReport(SENDER_SSRC, 0, 0, 0, 0),
                RECEIVER_SSRC,
                SENDER_SSRC,
                FrameId(1),
            ),
        )
        // Truncated mid-header.
        assertNull(Rtcp.parse(packet.copyOfRange(0, 6), RECEIVER_SSRC, SENDER_SSRC, FrameId(1)))
    }

    @Test
    fun `a loss field count that overruns its own sub-packet is refused`() {
        // Claiming four loss fields while carrying none must not read past the sub-packet, nor
        // wander into whatever follows it in the compound datagram.
        val packet = feedback(checkpoint = 1, withLeadingReport = false).copyOf()
        // Byte 4 starts the payload; +12 skips the two SSRCs and 'CAST', +1 skips the checkpoint.
        packet[4 + 12 + 1] = 4
        assertNull(parse(packet, FrameId(1)))
    }

    @Test
    fun `the checkpoint expands across an eight-bit wrap`() {
        // Frame 260 truncates to 4. Read naively the checkpoint would look like frame 4 and the
        // retransmit buffer would never be drained again.
        assertEquals(
            FrameId(260),
            Rtcp.expandLessThanOrEqual(FrameId(262), 260 and 0xff),
        )
        assertEquals(FrameId(255), Rtcp.expandLessThanOrEqual(FrameId(256), 255))
        val parsed = assertNotNull(
            parse(feedback(checkpoint = 260 and 0xff), FrameId(262)),
        )
        assertEquals(FrameId(260), parsed.checkpoint)
    }

    @Test
    fun `a nacked frame id above the checkpoint expands upwards`() {
        assertEquals(FrameId(257), Rtcp.expandGreaterThan(FrameId(255), 1))
        // Equal truncations mean a full lap, not the same frame.
        assertEquals(FrameId(511), Rtcp.expandGreaterThan(FrameId(255), 255))
    }

    @Test
    fun `expandNearest reaches backwards as well as forwards`() {
        // What the receiver needs: a live frame is just ahead of the last one completed, but a
        // *retransmission* is behind it, and neither one-sided expansion can produce both.
        assertEquals(FrameId(257), Rtcp.expandNearest(FrameId(256), 1))
        assertEquals(FrameId(255), Rtcp.expandNearest(FrameId(256), 255))
        assertEquals(FrameId(256), Rtcp.expandNearest(FrameId(256), 0))
        // Exactly 128 away is taken as behind, which is the arbitrary half of the split.
        assertEquals(FrameId(128), Rtcp.expandNearest(FrameId(256), 128))
    }

    @Test
    fun `feedback we build is feedback we parse`() {
        // The point of owning both ends. If the builder and the parser agree, the protocol works,
        // and neither has to be checked against anyone else's implementation.
        val nacks = listOf(
            PacketNack(FrameId(9), 0),
            PacketNack(FrameId(9), 1),
            PacketNack(FrameId(9), 4),
            PacketNack.wholeFrame(FrameId(11)),
        )
        val built = Rtcp.feedback(
            receiverSsrc = RECEIVER_SSRC,
            senderSsrc = SENDER_SSRC,
            checkpoint = FrameId(8),
            playoutDelayMs = 400,
            nacks = nacks,
            ackedFrames = listOf(FrameId(10), FrameId(12), FrameId(19)),
        )
        val parsed = assertNotNull(parse(built, FrameId(20)))
        assertEquals(FrameId(8), parsed.checkpoint)
        assertEquals(400, parsed.playoutDelayMs)
        assertEquals(nacks.toSet(), parsed.nacks.toSet())
        assertEquals(listOf(FrameId(10), FrameId(12), FrameId(19)), parsed.ackedFrames)
        assertFalse(parsed.pictureLoss)
    }

    @Test
    fun `a run of missing packets in one frame coalesces into a single loss field`() {
        // Four bytes per field with an eight-bit follow-on vector, rather than four bytes per
        // packet: at 1080p a lost burst is dozens of packets of one frame.
        val nacks = (3..10).map { PacketNack(FrameId(5), it) }
        val built = Rtcp.feedback(RECEIVER_SSRC, SENDER_SSRC, FrameId(4), 400, nacks)
        // Common header, feedback header, and exactly one loss field.
        assertEquals(4 + 16 + 4, built.size)
        assertEquals(nacks.toSet(), assertNotNull(parse(built, FrameId(6))).nacks.toSet())
    }

    @Test
    fun `the nack list is capped rather than fragmenting its own datagram`() {
        val nacks = (1L..200L).map { PacketNack.wholeFrame(FrameId(it)) }
        val built = Rtcp.feedback(RECEIVER_SSRC, SENDER_SSRC, FrameId.First, 400, nacks)
        // maxFrameId within one 8-bit lap of the checkpoint, which is the only case the wire can
        // express: a checkpoint 300 frames behind the newest frame cannot be recovered from 8 bits,
        // and would not occur - the receiver reports far more often than 256 frames apart.
        val parsed = assertNotNull(parse(built, FrameId(200)))
        assertEquals(Rtcp.MAX_LOSS_FIELDS, parsed.nacks.size)
        // And it kept the frames closest to the checkpoint, which are the ones blocking playout.
        assertEquals(FrameId(1), parsed.nacks.first().frameId)
    }

    @Test
    fun `a picture loss indicator we build is one the sender sees`() {
        val compound = Rtcp.compound(
            Rtcp.pictureLossIndicator(RECEIVER_SSRC, SENDER_SSRC),
            Rtcp.feedback(RECEIVER_SSRC, SENDER_SSRC, FrameId(3), 400),
        )
        val parsed = assertNotNull(parse(compound, FrameId(3)))
        assertTrue(parsed.pictureLoss)
        assertEquals(FrameId(3), parsed.checkpoint)
    }

    @Test
    fun `a sender report we build is one the receiver parses`() {
        val built = Rtcp.senderReport(
            senderSsrc = SENDER_SSRC,
            ntpTimestamp = 0x0192a3b4c5d6e7f8,
            rtpTimestamp = 0xAABBCCDD,
            packetCount = 7,
            octetCount = 4096,
        )
        val parsed = assertNotNull(Rtcp.parseSenderReport(built, SENDER_SSRC))
        assertEquals(0x0192a3b4c5d6e7f8, parsed.ntpTimestamp)
        assertEquals(0xAABBCCDDL, parsed.rtpTimestamp)
        assertEquals(7L, parsed.packetCount)
        assertEquals(4096L, parsed.octetCount)
        // Another stream's report is not ours, and feedback is not a report.
        assertNull(Rtcp.parseSenderReport(built, 999L))
        assertNull(
            Rtcp.parseSenderReport(
                Rtcp.feedback(RECEIVER_SSRC, SENDER_SSRC, FrameId(1), 400),
                SENDER_SSRC,
            ),
        )
    }

    @Test
    fun `a sender report is found after a leading feedback block`() {
        // The compound trap, in the other direction: the receiver has to walk the datagram too.
        val compound = Rtcp.compound(
            Rtcp.pictureLossIndicator(RECEIVER_SSRC, SENDER_SSRC),
            Rtcp.senderReport(SENDER_SSRC, 1, 2, 3, 4),
        )
        assertEquals(2L, assertNotNull(Rtcp.parseSenderReport(compound, SENDER_SSRC)).rtpTimestamp)
    }
}
