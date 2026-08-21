package com.vayunmathur.cast.protocol

/**
 * Packets in, whole frames out - the exact inverse of [RtpPacketizer].
 *
 * Three things make this the hard half of the receiver, and all three are here rather than spread
 * around:
 *
 *  1. **Frame ids arrive truncated to 8 bits.** They are expanded against the last frame completed,
 *     nearest-first, because an arriving frame is usually just ahead of it while a *retransmission*
 *     is behind - and neither one-sided expansion can produce both.
 *  2. **Packets arrive out of order, duplicated, or not at all.** A frame is only emitted once every
 *     one of its packets is in hand, and a duplicate is dropped rather than counted twice.
 *  3. **It has to be bounded.** A sender that keeps producing while one frame stays permanently
 *     incomplete must not be able to grow the heap, so the oldest partial goes when the buffer is
 *     full.
 *
 * Emits [EncryptedFrame] - the same type the packetizer consumes - so a round-trip assertion is one
 * `==` rather than a field-by-field comparison. The payload is still encrypted at this point;
 * decryption belongs to [ReceiverSession], which is the thing that knows the keys.
 */
class FrameAssembler(private val maxPending: Int = MAX_PENDING_FRAMES) {

    private val pending = LinkedHashMap<Long, Partial>()

    /**
     * What truncated ids are expanded against.
     *
     * Starts null, so the very first packet's 8-bit id is taken literally: a sender always begins a
     * session at [FrameId.First], but a receiver that came up a few frames late would otherwise
     * expand 250 to -6.
     */
    private var reference: FrameId? = null

    /** The highest frame id seen, complete or not. The top of the range worth NACKing. */
    var highest: FrameId = FrameId.First
        private set

    /**
     * Feed one packet in; get a frame back when that packet completed one.
     *
     * Packets belonging to a frame at or below [floor] are dropped: those are retransmissions of
     * frames already delivered, and re-emitting one would hand the decoder a frame it has decoded.
     */
    fun add(packet: RtpPacket, floor: FrameId = FrameId.Leader): EncryptedFrame? {
        val frameId = expand(packet.frameId8)
        if (frameId <= floor) return null
        if (frameId > highest) highest = frameId

        val partial = pending.getOrPut(frameId.value) {
            Partial(
                frameId = frameId,
                maxPacketId = packet.maxPacketId,
                rtpTimestamp = packet.rtpTimestamp,
                isKeyFrame = packet.isKeyFrame,
                // A frame references itself or something earlier, never a later one, so the
                // one-sided expansion is the right one here.
                referencedFrameId = Rtcp.expandLessThanOrEqual(frameId, packet.referenceFrameId8),
            )
        }
        evictOldest()
        // Already emitted. Its packets keep arriving - the sender answers a NACK it may already have
        // answered - and re-emitting would hand the decoder a frame it has decoded.
        if (partial.isEmitted) return null
        // A packet that disagrees with the rest of its frame about how many packets there are is
        // corrupt or from a different frame that happened to truncate to the same id. Either way the
        // partial cannot be trusted, so it is restarted rather than mixed.
        if (partial.maxPacketId != packet.maxPacketId) {
            pending.remove(frameId.value)
            return null
        }
        if (!partial.put(packet.packetId, packet.payload)) return null
        if (!partial.isComplete) return null
        // Kept rather than removed, so the duplicates that follow are absorbed by the check above.
        // discardUpTo and the eviction bound are what free it.
        return partial.emit()
    }

    /** Forget everything at or below [frameId]; it has been delivered and cannot be needed again. */
    fun discardUpTo(frameId: FrameId) {
        pending.keys.removeAll { it <= frameId.value }
    }

    /**
     * What to ask for, for the frames in `(after, highest]`.
     *
     * A frame with a partial contributes its missing packet ids; a frame with nothing at all
     * contributes a whole-frame NACK, which is the only thing that can be said about a frame whose
     * packet count is not yet known. Capped, because a NACK list long enough to fragment its own
     * datagram makes the loss worse.
     */
    fun missingPackets(after: FrameId): List<PacketNack> {
        val out = mutableListOf<PacketNack>()
        var id = after + 1
        while (id <= highest && out.size < Rtcp.MAX_LOSS_FIELDS) {
            val partial = pending[id.value]
            if (partial == null) {
                out += PacketNack.wholeFrame(id)
            } else {
                for (packetId in partial.missing()) out += PacketNack(id, packetId)
            }
            id += 1
        }
        return out
    }

    fun reset() {
        pending.clear()
        reference = null
        highest = FrameId.First
    }

    private fun expand(truncated: Int): FrameId {
        val current = reference
        val expanded = if (current == null) FrameId(truncated.toLong()) else {
            Rtcp.expandNearest(current, truncated)
        }
        // Tracked forward only: expanding against a retransmission's older id would then make the
        // *next* live frame look like a lap ahead.
        if (current == null || expanded > current) reference = expanded
        return expanded
    }

    /** Dropping the oldest is right: a frame nobody completed is a frame nobody can decode. */
    private fun evictOldest() {
        while (pending.size > maxPending) {
            pending.remove(pending.keys.first())
        }
    }

    private class Partial(
        val frameId: FrameId,
        val maxPacketId: Int,
        val rtpTimestamp: Long,
        val isKeyFrame: Boolean,
        val referencedFrameId: FrameId,
    ) {
        private val chunks = arrayOfNulls<ByteArray>(maxPacketId + 1)
        private var received = 0

        val isComplete: Boolean get() = received == chunks.size

        /** True once [emit] has handed this frame over, which is what makes duplicates cheap. */
        var isEmitted: Boolean = false
            private set

        /** False when [packetId] was already held, i.e. this is a duplicate or a retransmission. */
        fun put(packetId: Int, payload: ByteArray): Boolean {
            if (packetId !in chunks.indices) return false
            if (chunks[packetId] != null) return false
            chunks[packetId] = payload
            received++
            return true
        }

        /** Empty once emitted: NACKing packets of a frame already delivered would ask for nothing. */
        fun missing(): List<Int> =
            if (isEmitted) emptyList() else chunks.indices.filter { chunks[it] == null }

        /**
         * The assembled frame, once. The chunks are released afterwards: a completed frame's bytes
         * are the caller's now, and holding a second copy of two seconds of video is the difference
         * between a bounded buffer and a large one.
         */
        fun emit(): EncryptedFrame {
            val payload = ByteArray(chunks.sumOf { it?.size ?: 0 })
            var offset = 0
            for (chunk in chunks) {
                val bytes = chunk ?: continue
                bytes.copyInto(payload, offset)
                offset += bytes.size
            }
            chunks.fill(null)
            isEmitted = true
            return EncryptedFrame(
                frameId = frameId,
                referencedFrameId = referencedFrameId,
                rtpTimestamp = rtpTimestamp,
                isKeyFrame = isKeyFrame,
                payload = payload,
            )
        }
    }

    private companion object {
        /**
         * Two seconds of video at 30 fps, matching the sender's retransmit buffer: holding a partial
         * for longer than the sender can still repair it is holding it for nothing.
         */
        const val MAX_PENDING_FRAMES = 60
    }
}
