package com.vayunmathur.cast.platform.mirror

import com.vayunmathur.cast.network.CastUdpTransport
import com.vayunmathur.cast.protocol.Crypto
import com.vayunmathur.cast.protocol.EncryptedFrame
import com.vayunmathur.cast.protocol.FrameId
import com.vayunmathur.cast.protocol.NegotiatedStream
import com.vayunmathur.cast.protocol.ReceiverFeedback
import com.vayunmathur.cast.protocol.Recovery
import com.vayunmathur.cast.protocol.Retransmission
import com.vayunmathur.cast.protocol.RtpPacketizer
import com.vayunmathur.cast.protocol.StreamingSession

/**
 * One stream's send path: encrypt, packetize, count.
 *
 * Audio and video are independent sequences with their own keys, frame ids and RTP timestamps, so
 * there is one of these per stream rather than one shared pipeline.
 */
class StreamSender(
    private val stream: NegotiatedStream,
    private val udp: CastUdpTransport,
    /**
     * This stream's own retransmit buffer.
     *
     * **One per stream, never shared.** Audio and video are independent frame-id sequences that both
     * start at [FrameId.First], so a shared buffer would alias them - a NACK for video frame 5 would be
     * answered with audio frame 5's bytes.
     */
    private val session: StreamingSession,
) {

    private val crypto = Crypto(stream.keys.key, stream.keys.ivMask)
    private val packetizer = RtpPacketizer(stream.payloadType, stream.senderSsrc)

    /**
     * One send at a time.
     *
     * [send] runs on the encoder loop and [retransmit] on the RTCP loop, and both drive the same
     * packetizer - whose RTP sequence number is a plain counter. Interleaving them would emit two
     * packets with the same sequence number, which a receiver reads as a duplicate and drops.
     */
    private val lock = Any()

    private var nextFrameId = FrameId.First
    private var firstPresentationTimeUs = -1L

    /**
     * The highest frame id sent, which is what an 8-bit checkpoint is expanded against.
     *
     * Volatile because the RTCP loop reads it while the encoder loop writes it.
     */
    @Volatile
    var lastFrameId: FrameId = FrameId.First
        private set

    @Volatile
    var stats: SenderStats = SenderStats()
        private set

    fun send(chunk: EncodedChunk) = synchronized(lock) {
        // Timestamps are relative to the first frame, so a receiver does not have to know when the
        // encoder happened to start.
        if (firstPresentationTimeUs < 0) firstPresentationTimeUs = chunk.presentationTimeUs
        val elapsedUs = chunk.presentationTimeUs - firstPresentationTimeUs
        val rtpTimestamp = elapsedUs * stream.timebase / 1_000_000L
        val frameId = nextFrameId
        // A key frame references **itself**, and a delta frame its immediate predecessor. Both are
        // load-bearing and neither is a free choice - and now that the receiver is ours as well, the
        // second half of that pairing is enforced by [FrameAssembler] rather than hoped for:
        //
        //  - A frame that needs nothing else to decode must set referencedFrameId == frameId.
        //    Pointing a key frame at FrameId.Leader instead makes the receiver wait for frame 255
        //    forever and decode nothing at all - a black screen with no error anywhere. That cost this
        //    project an entire hardware session.
        //  - MediaCodec emits IPPP, where each P frame really does depend on the one before it, so
        //    naming any other frame misdescribes the bitstream.
        val referenced = if (chunk.isKeyFrame) frameId else FrameId(frameId.value - 1)
        val frame = EncryptedFrame(
            frameId = frameId,
            referencedFrameId = referenced,
            rtpTimestamp = rtpTimestamp,
            isKeyFrame = chunk.isKeyFrame,
            payload = crypto.crypt(frameId, chunk.data),
        )
        nextFrameId += 1
        lastFrameId = frameId
        session.record(frame)
        emit(frame, packetIds = null)
        // The wall clock is captured *here*, alongside the RTP timestamp it corresponds to. A sender
        // report has to pair the two for the same instant; pairing "now" with an older frame's
        // timestamp is what makes a receiver's clock estimate drift.
        stats = stats.copy(
            lastRtpTimestamp = rtpTimestamp,
            lastSentAtMillis = System.currentTimeMillis(),
        )
    }

    fun retransmit(items: List<Retransmission>) = synchronized(lock) {
        for (item in items) emit(item.frame, item.packetIds)
    }

    /** What this stream's own buffer says to do about one feedback packet. */
    fun onFeedback(feedback: ReceiverFeedback): Recovery = session.onFeedback(feedback)

    /** [packetIds] null means every packet of the frame; otherwise only those listed. */
    private fun emit(frame: EncryptedFrame, packetIds: List<Int>?) {
        val packets = packetizer.packetize(frame)
        var sent = 0L
        var octets = 0L
        packets.forEachIndexed { index, packet ->
            if (packetIds != null && index !in packetIds) return@forEachIndexed
            // Only a datagram the kernel actually accepted is counted: a sender report that
            // over-reports what was sent makes the receiver's loss estimate wrong.
            if (udp.send(packet)) {
                sent++
                octets += packet.size
            }
        }
        stats = stats.copy(packets = stats.packets + sent, octets = stats.octets + octets)
    }
}

/** What a sender report reports. */
data class SenderStats(
    val packets: Long = 0,
    val octets: Long = 0,
    val lastRtpTimestamp: Long = 0,
    /** When [lastRtpTimestamp] was sent, so a report can pair the two honestly. */
    val lastSentAtMillis: Long = 0,
)
