package com.vayunmathur.cast.protocol

/**
 * One parsed RTP packet: the header fields, and this packet's slice of the frame.
 *
 * [frameId8] and [referenceFrameId8] are the **truncated** ids straight off the wire. Expanding
 * them needs a reference point the packet itself does not carry, so that is [FrameAssembler]'s job
 * rather than this one's - a depacketizer that guessed would be the same class of bug as reading
 * the frame counter as a byte.
 */
data class RtpPacket(
    val payloadType: Int,
    val sequenceNumber: Int,
    /** Set on the last packet of a frame. Redundant with [packetId] == [maxPacketId], and checked. */
    val marker: Boolean,
    val rtpTimestamp: Long,
    val ssrc: Long,
    val isKeyFrame: Boolean,
    val frameId8: Int,
    val packetId: Int,
    val maxPacketId: Int,
    val referenceFrameId8: Int,
    val payload: ByteArray,
) {
    val packetCount: Int get() = maxPacketId + 1

    override fun equals(other: Any?): Boolean =
        other is RtpPacket &&
            payloadType == other.payloadType &&
            sequenceNumber == other.sequenceNumber &&
            marker == other.marker &&
            rtpTimestamp == other.rtpTimestamp &&
            ssrc == other.ssrc &&
            isKeyFrame == other.isKeyFrame &&
            frameId8 == other.frameId8 &&
            packetId == other.packetId &&
            maxPacketId == other.maxPacketId &&
            referenceFrameId8 == other.referenceFrameId8 &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = payloadType
        result = 31 * result + sequenceNumber
        result = 31 * result + marker.hashCode()
        result = 31 * result + rtpTimestamp.hashCode()
        result = 31 * result + ssrc.hashCode()
        result = 31 * result + isKeyFrame.hashCode()
        result = 31 * result + frameId8
        result = 31 * result + packetId
        result = 31 * result + maxPacketId
        result = 31 * result + referenceFrameId8
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * The exact inverse of [RtpPacketizer]: bytes off the socket back into header fields and a payload
 * slice.
 *
 * Every rejection below is a shape [RtpPacketizer] cannot produce, which is the whole reason this
 * can afford to be strict where a Cast receiver could not. A malformed datagram is dropped by
 * returning null rather than throwing: UDP is open to anything on the LAN, and a stray packet must
 * cost one branch rather than an exception per datagram.
 */
object RtpDepacketizer {

    fun parse(packet: ByteArray): RtpPacket? {
        if (packet.size < BASE_HEADER_SIZE) return null

        val byte0 = packet[0].toInt() and 0xff
        // Version 2, no padding, no extension, no CSRCs - `kRtpRequiredFirstByte`. Padding and
        // header extensions would change where the payload starts, and we never write either.
        if (byte0 != 0b1000_0000) return null

        val byte1 = packet[1].toInt() and 0xff
        val marker = byte1 and RTP_MARKER_BIT != 0
        val payloadType = byte1 and 0b0111_1111

        val castByte = packet[12].toInt() and 0xff
        val isKeyFrame = castByte and CAST_KEY_FRAME_BIT != 0
        // The reference frame id is what makes the header 19 bytes rather than 18. Our packetizer
        // always writes it, so a packet without it did not come from us.
        if (castByte and CAST_HAS_REFERENCE_FRAME_ID_BIT == 0) return null
        // Extension count. We never emit the adaptive-latency extension, so anything here would
        // shift the payload by an amount this parser has no rule for.
        if (castByte and 0b0011_1111 != 0) return null

        val packetId = packet.getShort(14)
        val maxPacketId = packet.getShort(16)
        if (packetId > maxPacketId) return null
        // The marker bit and the packet ids have to agree; disagreeing means corruption, and
        // trusting one over the other is how a frame ends up assembled from the wrong pieces.
        if (marker != (packetId == maxPacketId)) return null

        return RtpPacket(
            payloadType = payloadType,
            sequenceNumber = packet.getShort(2),
            marker = marker,
            rtpTimestamp = packet.getInt(4),
            ssrc = packet.getInt(8),
            isKeyFrame = isKeyFrame,
            frameId8 = packet[13].toInt() and 0xff,
            packetId = packetId,
            maxPacketId = maxPacketId,
            referenceFrameId8 = packet[18].toInt() and 0xff,
            payload = packet.copyOfRange(BASE_HEADER_SIZE, packet.size),
        )
    }
}
