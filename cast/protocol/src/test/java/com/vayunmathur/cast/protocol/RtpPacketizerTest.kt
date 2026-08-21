package com.vayunmathur.cast.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PAYLOAD_TYPE = 96
private const val SSRC = 50_001L

class RtpPacketizerTest {

    private fun packetizer(maxPacketSize: Int = MAX_RTP_PACKET_SIZE) =
        RtpPacketizer(PAYLOAD_TYPE, SSRC, maxPacketSize, sequenceNumberStart = 0x1234)

    private fun frame(
        size: Int,
        frameId: FrameId = FrameId(1),
        referenced: FrameId = FrameId.Leader,
        isKeyFrame: Boolean = true,
        rtpTimestamp: Long = 0xAABBCCDD,
    ) = EncryptedFrame(
        frameId = frameId,
        referencedFrameId = referenced,
        rtpTimestamp = rtpTimestamp,
        isKeyFrame = isKeyFrame,
        payload = ByteArray(size) { (it and 0xff).toByte() },
    )

    @Test
    fun `a single-packet key frame has the expected header, byte for byte`() {
        val packets = packetizer().packetize(frame(size = 4))
        assertEquals(1, packets.size)
        val p = packets.single()
        assertEquals(19 + 4, p.size)
        // RTP: V=2 with no padding/extension/CSRC; marker set because this is the last packet.
        assertEquals(0x80, p[0].toInt() and 0xff)
        assertEquals(0x80 or PAYLOAD_TYPE, p[1].toInt() and 0xff)
        assertEquals(0x1234, p.getShort(2))
        assertEquals(0xAABBCCDDL, p.getInt(4))
        assertEquals(SSRC, p.getInt(8))
        // Cast: key-frame bit and the always-present reference-frame-id bit, no extensions.
        assertEquals(0b1100_0000, p[12].toInt() and 0xff)
        assertEquals(1, p[13].toInt() and 0xff)
        assertEquals(0, p.getShort(14)) // packet id
        assertEquals(0, p.getShort(16)) // max packet id, i.e. count - 1
        assertEquals(0xff, p[18].toInt() and 0xff) // FrameId.Leader truncates to 0xff
        assertContentEquals(byteArrayOf(0, 1, 2, 3), p.copyOfRange(19, 23))
    }

    @Test
    fun `a delta frame clears the key-frame bit and keeps the reference bit`() {
        val p = packetizer()
            .packetize(frame(size = 1, frameId = FrameId(5), referenced = FrameId(4), isKeyFrame = false))
            .single()
        assertEquals(0b0100_0000, p[12].toInt() and 0xff)
        assertEquals(5, p[13].toInt() and 0xff)
        assertEquals(4, p[18].toInt() and 0xff)
    }

    @Test
    fun `the marker bit is set only on the last packet, and ids run in order`() {
        // 23 bytes of header reserved per packet even though only 19 are written, so with a 39-byte
        // limit each packet carries 16 payload bytes.
        val p = packetizer(maxPacketSize = 39)
        assertEquals(16, p.maxPayloadSize)
        val packets = p.packetize(frame(size = 40))
        assertEquals(3, packets.size)
        packets.forEachIndexed { index, packet ->
            val isLast = index == packets.size - 1
            assertEquals(if (isLast) 0x80 or PAYLOAD_TYPE else PAYLOAD_TYPE, packet[1].toInt() and 0xff)
            assertEquals(index, packet.getShort(14))
            assertEquals(2, packet.getShort(16))
            assertEquals(0x1234 + index, packet.getShort(2))
        }
        // 16 + 16 + 8, and the payload reassembles to the original.
        assertEquals(listOf(16, 16, 8), packets.map { it.size - 19 })
        val reassembled = packets.fold(ByteArray(0)) { acc, packet ->
            acc + packet.copyOfRange(19, packet.size)
        }
        assertContentEquals(ByteArray(40) { (it and 0xff).toByte() }, reassembled)
    }

    @Test
    fun `an empty payload still produces one packet`() {
        // A silent audio frame is zero bytes; producing no packets would stall the receiver's
        // frame assembler waiting for a frame that never arrives.
        val packets = packetizer().packetize(frame(size = 0))
        assertEquals(1, packets.size)
        assertEquals(19, packets.single().size)
        assertEquals(0, packets.single().getShort(16))
    }

    @Test
    fun `an exact multiple of the payload size does not produce a trailing empty packet`() {
        val p = packetizer(maxPacketSize = 39)
        assertEquals(2, p.packetCount(32))
        assertEquals(listOf(16, 16), p.packetize(frame(size = 32)).map { it.size - 19 })
    }

    @Test
    fun `the sequence number wraps at sixteen bits`() {
        val p = RtpPacketizer(PAYLOAD_TYPE, SSRC, sequenceNumberStart = 0xffff)
        val first = p.packetize(frame(size = 1)).single()
        val second = p.packetize(frame(size = 1)).single()
        assertEquals(0xffff, first.getShort(2))
        assertEquals(0, second.getShort(2))
    }

    @Test
    fun `a key frame references itself and a delta frame its predecessor`() {
        // The invariant that made a real TV show nothing. encoded_frame.h: "If this frame does not
        // require any other frame in order to become decodable (e.g., key frames),
        // referenced_frame_id must equal frame_id." Pointing a key frame at FrameId.Leader makes the
        // receiver wait for frame 255 forever, with no error anywhere.
        val keyFrame = EncryptedFrame(
            frameId = FrameId.First,
            referencedFrameId = FrameId.First,
            rtpTimestamp = 0,
            isKeyFrame = true,
            payload = ByteArray(4),
        )
        val packet = RtpPacketizer(96, 50_001).packetize(keyFrame).single()
        // Frame id and reference frame id are the same byte value.
        assertEquals(packet[13], packet[18])
        assertEquals(0, packet[18].toInt() and 0xff)

        val delta = keyFrame.copy(
            frameId = FrameId(1),
            referencedFrameId = FrameId.First,
            isKeyFrame = false,
        )
        val deltaPacket = RtpPacketizer(96, 50_001).packetize(delta).single()
        assertEquals(1, deltaPacket[13].toInt() and 0xff)
        assertEquals(0, deltaPacket[18].toInt() and 0xff)
    }

    @Test
    fun `frame id and rtp timestamp are truncated to the wire width`() {
        // The counter is 64-bit; only the low 8 bits go in the header and the low 32 in the
        // timestamp. Anything wider must not corrupt neighbouring fields.
        val p = packetizer()
            .packetize(frame(size = 1, frameId = FrameId(0x1_0002), rtpTimestamp = 0x1_2233_4455))
            .single()
        assertEquals(0x02, p[13].toInt() and 0xff)
        assertEquals(0x2233_4455L, p.getInt(4))
        assertTrue(p.getInt(8) == SSRC)
    }
}
