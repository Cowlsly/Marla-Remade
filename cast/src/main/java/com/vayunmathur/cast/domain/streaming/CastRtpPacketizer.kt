package com.vayunmathur.cast.domain.streaming

/**
 * The largest UDP payload that fits an Ethernet frame without fragmenting:
 * `kMaxRtpPacketSizeForIpv4UdpOnEthernet` in openscreen `cast/streaming/impl/rtp_defines.h`.
 */
const val MAX_RTP_PACKET_SIZE = 1500 - 20 - 8

/**
 * RTP header (12) + Cast header (7). `kBaseRtpHeaderSize`, which openscreen writes as
 * `kRtpPacketMinValidSize + 1` because this implementation always includes the reference frame id.
 */
private const val BASE_HEADER_SIZE = 19

/** `kAdaptiveLatencyHeaderSize`. Reserved even when unused, as openscreen reserves it. */
private const val ADAPTIVE_LATENCY_HEADER_SIZE = 4

private const val MAX_HEADER_SIZE = BASE_HEADER_SIZE + ADAPTIVE_LATENCY_HEADER_SIZE

/** `kRtpRequiredFirstByte` - version 2, no padding, no extension, no CSRCs. */
private const val RTP_FIRST_BYTE = 0b1000_0000

private const val RTP_MARKER_BIT = 0b1000_0000
private const val CAST_KEY_FRAME_BIT = 0b1000_0000
private const val CAST_HAS_REFERENCE_FRAME_ID_BIT = 0b0100_0000

/** One encoded, already-encrypted frame, ready to be split into packets. */
data class EncryptedFrame(
    val frameId: FrameId,
    val referencedFrameId: FrameId,
    val rtpTimestamp: Long,
    val isKeyFrame: Boolean,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is EncryptedFrame &&
            frameId == other.frameId &&
            referencedFrameId == other.referencedFrameId &&
            rtpTimestamp == other.rtpTimestamp &&
            isKeyFrame == other.isKeyFrame &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = frameId.hashCode()
        result = 31 * result + referencedFrameId.hashCode()
        result = 31 * result + rtpTimestamp.hashCode()
        result = 31 * result + isKeyFrame.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Splits an encrypted frame into Cast RTP packets.
 *
 * Wire format from openscreen `cast/streaming/impl/rtp_packetizer.cc` (`GeneratePacket`), with the
 * field diagram in `impl/rtp_defines.h`:
 *
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * |V=2|P|X|  CC   |M|     PT      |       sequence number         |
 * |                           RTP timestamp                       |
 * |                             sender SSRC                       |
 * |K|R|ext count  |   frame id    |          packet id            |
 * |      max packet id            | reference frame id            |
 * ```
 *
 * The sequence number starts at a random value: openscreen says the reason is unclear but that
 * every implementation has done it for years, so it is copied rather than rationalised.
 *
 * The adaptive-latency extension is not emitted - it only carries a mid-stream playout-delay
 * change, which nothing here asks for - but its 4 bytes stay reserved so the payload size matches
 * openscreen's and a frame never has to be re-split.
 */
class CastRtpPacketizer(
    private val payloadType: Int,
    private val senderSsrc: Long,
    private val maxPacketSize: Int = MAX_RTP_PACKET_SIZE,
    sequenceNumberStart: Int = 0,
) {

    private var sequenceNumber = sequenceNumberStart and 0xffff

    val maxPayloadSize: Int get() = maxPacketSize - MAX_HEADER_SIZE

    /**
     * How many packets [payloadSize] bytes will take.
     *
     * Always at least one: some audio codecs encode a silent period as zero bytes, and a frame
     * that produced no packets would stall the receiver's frame assembler waiting for it.
     */
    fun packetCount(payloadSize: Int): Int =
        maxOf(1, (payloadSize + maxPayloadSize - 1) / maxPayloadSize)

    /** Every packet for [frame], in order. */
    fun packetize(frame: EncryptedFrame): List<ByteArray> {
        val total = packetCount(frame.payload.size)
        return (0 until total).map { packet(frame, it, total) }
    }

    private fun packet(frame: EncryptedFrame, packetId: Int, total: Int): ByteArray {
        val start = maxPayloadSize * packetId
        val chunkSize = if (packetId == total - 1) frame.payload.size - start else maxPayloadSize
        val out = ByteArray(BASE_HEADER_SIZE + chunkSize)
        var i = 0

        // RTP header.
        out[i++] = RTP_FIRST_BYTE.toByte()
        val marker = if (packetId == total - 1) RTP_MARKER_BIT else 0
        out[i++] = (marker or payloadType).toByte()
        i = out.putShort(i, sequenceNumber)
        sequenceNumber = (sequenceNumber + 1) and 0xffff
        i = out.putInt(i, frame.rtpTimestamp and 0xffff_ffffL)
        i = out.putInt(i, senderSsrc and 0xffff_ffffL)

        // Cast header. The reference-frame-id bit is always set, which is what makes the header 19
        // bytes rather than 18.
        val keyBit = if (frame.isKeyFrame) CAST_KEY_FRAME_BIT else 0
        out[i++] = (keyBit or CAST_HAS_REFERENCE_FRAME_ID_BIT).toByte()
        out[i++] = frame.frameId.lower8.toByte()
        i = out.putShort(i, packetId)
        i = out.putShort(i, total - 1)
        out[i++] = frame.referencedFrameId.lower8.toByte()

        frame.payload.copyInto(out, i, start, start + chunkSize)
        return out
    }
}

internal fun ByteArray.putShort(offset: Int, value: Int): Int {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
    return offset + 2
}

internal fun ByteArray.putInt(offset: Int, value: Long): Int {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
    return offset + 4
}
