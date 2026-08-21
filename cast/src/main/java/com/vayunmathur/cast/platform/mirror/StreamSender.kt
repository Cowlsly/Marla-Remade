package com.vayunmathur.cast.platform.mirror

import com.vayunmathur.cast.domain.streaming.CastCrypto
import com.vayunmathur.cast.domain.streaming.CastRtpPacketizer
import com.vayunmathur.cast.domain.streaming.EncryptedFrame
import com.vayunmathur.cast.domain.streaming.FrameId
import com.vayunmathur.cast.domain.streaming.NegotiatedStream
import com.vayunmathur.cast.domain.streaming.Retransmission
import com.vayunmathur.cast.domain.streaming.StreamingSession
import com.vayunmathur.cast.network.CastUdpTransport

/**
 * One stream's send path: encrypt, packetize, count.
 *
 * Audio and video are independent sequences with their own keys, frame ids and RTP timestamps, so
 * there is one of these per stream rather than one shared pipeline.
 */
class StreamSender(
    private val stream: NegotiatedStream,
    private val udp: CastUdpTransport,
    private val session: StreamingSession,
) {

    private val crypto = CastCrypto(stream.keys.key, stream.keys.ivMask)
    private val packetizer = CastRtpPacketizer(stream.payloadType, stream.senderSsrc)

    private var nextFrameId = FrameId.First
    private var lastKeyFrameId = FrameId.Leader
    private var firstPresentationTimeUs = -1L

    /** The highest frame id sent, which is what an 8-bit checkpoint is expanded against. */
    var lastFrameId: FrameId = FrameId.First
        private set

    var stats: SenderStats = SenderStats()
        private set

    fun send(chunk: EncodedChunk) {
        // Timestamps are relative to the first frame, so a receiver does not have to know when the
        // encoder happened to start.
        if (firstPresentationTimeUs < 0) firstPresentationTimeUs = chunk.presentationTimeUs
        val elapsedUs = chunk.presentationTimeUs - firstPresentationTimeUs
        val rtpTimestamp = elapsedUs * stream.timebase / 1_000_000L
        val frameId = nextFrameId
        // A key frame references the leader, having no predecessor. A delta frame references the
        // last key frame rather than the frame just before it, so one lost delta does not invalidate
        // every frame that follows.
        val referenced = if (chunk.isKeyFrame) FrameId.Leader else lastKeyFrameId
        val frame = EncryptedFrame(
            frameId = frameId,
            referencedFrameId = referenced,
            rtpTimestamp = rtpTimestamp,
            isKeyFrame = chunk.isKeyFrame,
            payload = crypto.crypt(frameId, chunk.data),
        )
        if (chunk.isKeyFrame) lastKeyFrameId = frameId
        nextFrameId += 1
        lastFrameId = frameId
        session.record(frame)
        emit(frame, packetIds = null)
        stats = stats.copy(lastRtpTimestamp = rtpTimestamp)
    }

    fun retransmit(items: List<Retransmission>) {
        for (item in items) emit(item.frame, item.packetIds)
    }

    /** [packetIds] null means every packet of the frame; otherwise only those listed. */
    private fun emit(frame: EncryptedFrame, packetIds: List<Int>?) {
        val packets = packetizer.packetize(frame)
        var sent = 0L
        var octets = 0L
        packets.forEachIndexed { index, packet ->
            if (packetIds != null && index !in packetIds) return@forEachIndexed
            if (udp.send(packet)) {
                sent++
                octets += packet.size
            }
        }
        stats = SenderStats(stats.packets + sent, stats.octets + octets, stats.lastRtpTimestamp)
    }
}

/** What a sender report reports. */
data class SenderStats(
    val packets: Long = 0,
    val octets: Long = 0,
    val lastRtpTimestamp: Long = 0,
)
