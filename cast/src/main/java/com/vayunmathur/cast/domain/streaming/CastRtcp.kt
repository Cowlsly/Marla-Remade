package com.vayunmathur.cast.domain.streaming

/**
 * RTCP: sender reports out, receiver feedback in.
 *
 * From openscreen `cast/streaming/impl/rtcp_common.cc` (the common header and report block),
 * `impl/sender_report_builder.cc` (the sender report) and `impl/compound_rtcp_parser.cc`
 * (`ParseFeedback`, the Cast-specific ACK/NACK). The wire diagrams are in `impl/rtp_defines.h`.
 *
 * Getting this wrong does not look like an error: the picture renders and then freezes, because
 * without sender reports the receiver has no clock to schedule against, and without parsing
 * feedback nothing is ever retransmitted.
 */
object CastRtcp {

    /** Version 2, no padding, in the top three bits of byte 0. */
    private const val VERSION_AND_PADDING = 0b100 shl 5

    /** `RtcpPacketType` values. */
    private const val TYPE_SENDER_REPORT = 200
    private const val TYPE_APPLICATION_DEFINED = 204

    /** `RtcpSubtype::kFeedback`, carried in the report-count field. */
    private const val SUBTYPE_FEEDBACK = 15

    /** `kRtcpSenderReportSize`, excluding the 4-byte common header. */
    private const val SENDER_REPORT_SIZE = 24

    /** `'CAST'` and `'CST2'` as big-endian ASCII words. */
    private const val CAST_WORD = 0x43_41_53_54L
    private const val CST2_WORD = 0x43_53_54_32L

    private const val FEEDBACK_HEADER_SIZE = 16
    private const val FEEDBACK_ACK_HEADER_SIZE = 6
    private const val LOSS_FIELD_SIZE = 4

    /** `kAllPacketsLost` - a NACK for a whole frame rather than for one packet. */
    const val ALL_PACKETS_LOST = 0xffff

    /**
     * A sender report: our clock, and how much we have sent.
     *
     * [ntpTimestamp] is the 64-bit NTP form of the moment [rtpTimestamp] was captured; the pair is
     * what lets the receiver relate our media clock to wall time.
     */
    fun senderReport(
        senderSsrc: Long,
        ntpTimestamp: Long,
        rtpTimestamp: Long,
        packetCount: Long,
        octetCount: Long,
    ): ByteArray {
        val out = ByteArray(4 + SENDER_REPORT_SIZE)
        var i = 0
        out[i++] = VERSION_AND_PADDING.toByte() // report_count = 0: we send no report blocks
        out[i++] = TYPE_SENDER_REPORT.toByte()
        // The length field counts 32-bit words *after* the first, which is what dividing by 4
        // without adding one expresses.
        i = out.putShort(i, SENDER_REPORT_SIZE / 4)
        i = out.putInt(i, senderSsrc and 0xffff_ffffL)
        i = out.putInt(i, (ntpTimestamp ushr 32) and 0xffff_ffffL)
        i = out.putInt(i, ntpTimestamp and 0xffff_ffffL)
        i = out.putInt(i, rtpTimestamp and 0xffff_ffffL)
        i = out.putInt(i, packetCount and 0xffff_ffffL)
        out.putInt(i, octetCount and 0xffff_ffffL)
        return out
    }

    /**
     * Parse a receiver feedback packet, or return null if this is not one.
     *
     * [maxFrameId] is the highest frame we have sent; the checkpoint arrives truncated to 8 bits
     * and is expanded against it, exactly as `ExpandLessThanOrEqual` does. Without that the
     * checkpoint would appear to jump backwards every 256 frames.
     */
    fun parseFeedback(
        packet: ByteArray,
        receiverSsrc: Long,
        senderSsrc: Long,
        maxFrameId: FrameId,
    ): ReceiverFeedback? {
        if (packet.size < 4 + FEEDBACK_HEADER_SIZE) return null
        val byte0 = packet[0].toInt() and 0xff
        if (byte0 and 0b1110_0000 != VERSION_AND_PADDING) return null
        if (byte0 and 0b0001_1111 != SUBTYPE_FEEDBACK) return null
        if (packet[1].toInt() and 0xff != TYPE_APPLICATION_DEFINED) return null
        var i = 4
        if (packet.getInt(i) != receiverSsrc and 0xffff_ffffL) return null
        i += 4
        if (packet.getInt(i) != senderSsrc and 0xffff_ffffL) return null
        i += 4
        if (packet.getInt(i) != CAST_WORD) return null
        i += 4

        val checkpoint = expandLessThanOrEqual(maxFrameId, packet[i].toInt() and 0xff)
        i++
        val lossFieldCount = packet[i].toInt() and 0xff
        i++
        val playoutDelayMs = packet.getShort(i)
        i += 2

        if (packet.size - i < LOSS_FIELD_SIZE * lossFieldCount) return null
        val nacks = mutableListOf<PacketNack>()
        repeat(lossFieldCount) {
            val frameId = expandGreaterThan(checkpoint, packet[i].toInt() and 0xff)
            i++
            var packetId = packet.getShort(i)
            i += 2
            var bits = packet[i].toInt() and 0xff
            i++
            nacks += PacketNack(frameId, packetId)
            if (packetId != ALL_PACKETS_LOST) {
                // Each set bit is another missing packet, counting up from packetId.
                while (bits != 0) {
                    packetId++
                    if (bits and 1 != 0) nacks += PacketNack(frameId, packetId)
                    bits = bits ushr 1
                }
            }
        }

        // CST2 frame-level ACKs are optional, and openscreen deliberately tolerates trailing bytes
        // that are not 'CST2' rather than calling the packet corrupt.
        val acked = mutableListOf<FrameId>()
        if (packet.size - i >= FEEDBACK_ACK_HEADER_SIZE && packet.getInt(i) == CST2_WORD) {
            i += 4
            i++ // Feedback Count, unused.
            val octets = packet[i].toInt() and 0xff
            i++
            if (packet.size - i >= octets) {
                // "Plus two" because the checkpoint frame is implicitly acked and the bit vector
                // starts at the frame after the one following it - see rtp_defines.h.
                var frameId = checkpoint + 2
                repeat(octets) {
                    var bits = packet[i].toInt() and 0xff
                    var id = frameId
                    while (bits != 0) {
                        if (bits and 1 != 0) acked += id
                        bits = bits ushr 1
                        id += 1
                    }
                    i++
                    frameId += 8
                }
            }
        }
        return ReceiverFeedback(checkpoint, playoutDelayMs, nacks, acked)
    }

    /**
     * Re-expand a truncated 8-bit id, knowing the result is at most [reference].
     *
     * openscreen `impl/expanded_value_base.h`, `ExpandLessThanOrEqual`.
     */
    internal fun expandLessThanOrEqual(reference: FrameId, truncated: Int): FrameId {
        val delta = (reference.lower8 - truncated) and 0xff
        return FrameId(reference.value - delta)
    }

    /** The mirror of the above, for an id known to be greater than [reference]. */
    internal fun expandGreaterThan(reference: FrameId, truncated: Int): FrameId {
        val delta = (truncated - reference.lower8) and 0xff
        return FrameId(reference.value + if (delta == 0) 256 else delta)
    }
}

/** One packet the receiver says it is missing. */
data class PacketNack(val frameId: FrameId, val packetId: Int) {
    val isWholeFrame: Boolean get() = packetId == CastRtcp.ALL_PACKETS_LOST
}

/**
 * What the receiver told us.
 *
 * [checkpoint] is the highest frame it has completely received, so everything up to and including
 * it can be dropped from the retransmit buffer. [nacks] is what to resend.
 */
data class ReceiverFeedback(
    val checkpoint: FrameId,
    val playoutDelayMs: Int,
    val nacks: List<PacketNack>,
    val ackedFrames: List<FrameId>,
)

internal fun ByteArray.getShort(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

internal fun ByteArray.getInt(offset: Int): Long =
    ((this[offset].toLong() and 0xff) shl 24) or
        ((this[offset + 1].toLong() and 0xff) shl 16) or
        ((this[offset + 2].toLong() and 0xff) shl 8) or
        (this[offset + 3].toLong() and 0xff)
