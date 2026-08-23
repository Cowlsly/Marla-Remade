package com.vayunmathur.cast.protocol

/** One frame, decrypted and in order, ready for a decoder. */
data class DecodableFrame(
    val frameId: FrameId,
    val rtpTimestamp: Long,
    val isKeyFrame: Boolean,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is DecodableFrame &&
            frameId == other.frameId &&
            rtpTimestamp == other.rtpTimestamp &&
            isKeyFrame == other.isKeyFrame &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = frameId.hashCode()
        result = 31 * result + rtpTimestamp.hashCode()
        result = 31 * result + isKeyFrame.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * [StreamingSession]'s mirror image: datagrams in, decodable frames out, feedback back.
 *
 * Free of sockets and codecs for the same reason the sender's half is - the ordering and recovery
 * rules are the part that is easy to get wrong, and this way every one of them is a JVM unit test.
 * The receiver app wires this to a `DatagramChannel` on one side and `MediaCodec` on the other.
 *
 * Two rules carry the whole design:
 *
 *  1. **Nothing is released before the first key frame.** A delta frame references a predecessor, so
 *     feeding one to a decoder that never had it produces a green smear rather than an error. Until
 *     a key frame arrives the session asks for one and drops everything else.
 *  2. **Frames are released strictly in order.** [checkpoint] is the highest frame delivered, and a
 *     gap blocks everything behind it - which is exactly what makes retransmission worth doing. A
 *     gap that outlives the sender's ability to repair it is escaped by asking for a key frame and
 *     resynchronising on it.
 */
class ReceiverSession(
    private val stream: NegotiatedStream,
    private val playoutDelayMs: Int = StreamConstants.TARGET_DELAY_MS,
) {

    private val crypto = Crypto(stream.keys.key, stream.keys.ivMask)
    private val assembler = FrameAssembler()

    /** Complete but not yet releasable, because something before them has not arrived. */
    private val ready = sortedMapOf<Long, EncryptedFrame>()

    /**
     * The highest frame delivered to the decoder.
     *
     * [FrameId.Leader] - "the frame before the first" - until something is, so that
     * `checkpoint + 1` is [FrameId.First] from the start.
     */
    var checkpoint: FrameId = FrameId.Leader
        private set

    /** False until a key frame has been delivered, i.e. until the decoder has any reference at all. */
    private var synchronised = false

    /** Feedback rounds where a gap blocked delivery while frames beyond it kept arriving. */
    private var stalledRounds = 0

    /** Feedback rounds where packets kept arriving and nothing at all became decodable. */
    private var starvedRounds = 0
    private var packetsAtLastFeedback = 0L
    private var framesAtLastFeedback = 0L

    /** Counters, so the receiver's log can be read against the sender's. */
    var packetsReceived: Long = 0
        private set
    var packetsIgnored: Long = 0
        private set
    var framesDelivered: Long = 0
        private set

    /**
     * One datagram in; whatever it made deliverable out.
     *
     * Usually empty - most packets are a fragment of a frame that is not finished - and occasionally
     * several frames at once, when the packet that arrived was the one plugging a gap.
     *
     * A datagram that is not RTP for *this* stream is counted and dropped. That is the normal case
     * for a shared socket carrying both halves of the mirror, so it must not be logged per packet.
     */
    fun onPacket(datagram: ByteArray): List<DecodableFrame> {
        val packet = RtpDepacketizer.parse(datagram)
        if (packet == null || packet.ssrc != stream.senderSsrc) {
            packetsIgnored++
            return emptyList()
        }
        packetsReceived++
        val frame = assembler.add(packet, floor = checkpoint) ?: return emptyList()
        // **Before the first key frame, a delta frame is dropped rather than held.** It cannot be
        // decoded - its reference never arrived - so holding it would grow the buffer for the whole
        // pre-sync period and put frames in the ACK vector that are never going to be played.
        if (!synchronised && !frame.isKeyFrame) return emptyList()
        ready[frame.frameId.value] = frame
        return drain()
    }

    /**
     * The RTCP to send back: what has been received, what is missing, and whether a key frame is
     * needed.
     *
     * Called on a timer rather than per packet, because feedback is only useful at about the rate the
     * sender can act on it. A PLI rides in the same datagram as the feedback block - RTCP is compound
     * by design, and sending them separately doubles the chance of losing the one that mattered.
     */
    fun feedback(): ByteArray {
        val nacks = assembler.missingPackets(after = checkpoint)
        val needsKeyFrame = !synchronised || isStalled() || isStarved()
        if (needsKeyFrame && synchronised) {
            // The gap has outlived the sender's retransmit buffer, or nothing is completing at all.
            // Nothing else will unblock either, so give up: resynchronise on the next key frame
            // rather than stalling for ever.
            requestKeyFrame()
        }
        val block = Rtcp.feedback(
            receiverSsrc = stream.receiverSsrc,
            senderSsrc = stream.senderSsrc,
            checkpoint = checkpoint,
            playoutDelayMs = playoutDelayMs,
            nacks = nacks,
            ackedFrames = ready.keys.map { FrameId(it) },
        )
        return if (needsKeyFrame) {
            Rtcp.compound(
                Rtcp.pictureLossIndicator(stream.receiverSsrc, stream.senderSsrc),
                block,
            )
        } else {
            block
        }
    }

    /** The sender's clock, when a datagram carries a report for this stream. */
    fun onSenderReport(datagram: ByteArray): SenderReport? =
        Rtcp.parseSenderReport(datagram, stream.senderSsrc)

    /**
     * Give up on what we hold and resynchronise on the next key frame.
     *
     * Used by [feedback] when a gap has outlived the sender's retransmit buffer, and callable from
     * outside for the case this session cannot see: a frame it counted as delivered that the decoder
     * then refused. The checkpoint has already advanced past it, so every delta frame behind it
     * references something the decoder does not have - and without this the session stays
     * "synchronised", no PLI ever goes out, and the corruption lasts until the sender's next
     * scheduled IDR.
     */
    fun requestKeyFrame() {
        synchronised = false
        ready.clear()
        assembler.reset()
        stalledRounds = 0
        starvedRounds = 0
    }

    /**
     * A gap is blocking delivery while frames beyond it keep arriving.
     *
     * Counted in feedback rounds rather than milliseconds: the count only advances when feedback goes
     * out, so it measures "the sender has been told this three times and nothing came back", which is
     * the condition a key frame actually answers.
     */
    private fun isStalled(): Boolean {
        if (ready.isEmpty()) {
            stalledRounds = 0
            return false
        }
        stalledRounds++
        return stalledRounds > STALL_ROUNDS
    }

    /**
     * Packets are arriving and *nothing* is completing.
     *
     * [isStalled] cannot see this case, because it asks whether frames are queued behind a gap - and
     * under heavy loss no frame ever completes, so [ready] stays empty, `stalledRounds` keeps
     * resetting, and the session concludes it is healthy while the picture is frozen. Measured: 18
     * seconds of a frozen mirror at 8.5% packet loss during which not one key frame was asked for,
     * because by this class's own definition nothing was wrong.
     *
     * Counted in feedback rounds like [isStalled], and only while packets are actually arriving: a
     * sender that has genuinely stopped is the control channel's problem, not something a key frame
     * would fix.
     */
    private fun isStarved(): Boolean {
        if (framesDelivered != framesAtLastFeedback) {
            framesAtLastFeedback = framesDelivered
            packetsAtLastFeedback = packetsReceived
            starvedRounds = 0
            return false
        }
        starvedRounds++
        // Packets since the **last delivered frame**, not since the last round. Asking whether a
        // packet arrived in this particular 50 ms window meant the counter reset on every quiet
        // round, so at the ~25 packets/s a struggling sender produces it could never reach the
        // threshold: measured, the picture stayed frozen and this never fired once.
        return packetsReceived != packetsAtLastFeedback && starvedRounds > STARVED_ROUNDS
    }

    /**
     * Release everything now deliverable, oldest first.
     *
     * Decryption happens here, once per frame, keyed by the frame id - which is why a frame can be
     * reassembled from packets that arrived in any order and still decrypt: the counter block comes
     * from the id, not from arrival order.
     */
    private fun drain(): List<DecodableFrame> {
        val out = mutableListOf<DecodableFrame>()
        while (true) {
            if (!synchronised) {
                // Wait for a key frame, then treat everything before it as never having existed.
                val key = ready.values.firstOrNull { it.isKeyFrame } ?: return out
                ready.keys.removeAll { it < key.frameId.value }
                checkpoint = key.frameId - 1
                assembler.discardUpTo(checkpoint)
                synchronised = true
                stalledRounds = 0
            }
            val next = checkpoint + 1
            val frame = ready.remove(next.value) ?: return out
            checkpoint = next
            framesDelivered++
            stalledRounds = 0
            assembler.discardUpTo(next)
            out += DecodableFrame(
                frameId = next,
                rtpTimestamp = frame.rtpTimestamp,
                isKeyFrame = frame.isKeyFrame,
                payload = crypto.crypt(next, frame.payload),
            )
        }
    }

    private companion object {
        /**
         * Three rounds at the sender's feedback cadence is comfortably longer than one round trip on
         * a LAN, so a NACK has genuinely had its chance before a key frame is demanded - and short
         * enough that a picture never freezes for a noticeable time.
         */
        const val STALL_ROUNDS = 3

        /**
         * Longer than [STALL_ROUNDS], because this is the blunter instrument.
         *
         * A gap with frames piled behind it is unambiguous after three rounds. "Nothing completed"
         * only becomes conclusive once retransmission has had a fair chance, and the answer - throwing
         * away every partial frame the assembler holds - discards work that NACKs might still have
         * repaired. Ten rounds is half a second of frozen picture, against the 18 seconds this
         * replaces.
         */
        const val STARVED_ROUNDS = 10
    }
}
