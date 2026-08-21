package com.vayunmathur.cast.protocol

/**
 * The sender's loss-recovery state machine, with no I/O.
 *
 * Kept free of sockets and encoders because recovery is the part with the subtle rules, and this way
 * every one of them is a JVM unit test. [ReceiverSession] is its mirror image on the TV.
 *
 * Smaller than it used to be: OFFER/ANSWER negotiation lived here when the peer was a Cast receiver
 * and had to be asked what it would accept. The handshake settles that now, so what is left is the
 * one thing feedback is actually for - deciding what to resend.
 */
class StreamingSession {

    /**
     * Frames we may still be asked to resend, newest last.
     *
     * Bounded, because a receiver that stops sending feedback must not be able to make the sender
     * grow without limit. Dropping the oldest is right: a NACK for a frame we no longer hold is
     * answered by the next key frame anyway.
     */
    private val retransmitBuffer = LinkedHashMap<Long, EncryptedFrame>()

    /** Remember a frame in case it has to be resent. */
    fun record(frame: EncryptedFrame) {
        retransmitBuffer[frame.frameId.value] = frame
        while (retransmitBuffer.size > RETRANSMIT_BUFFER_FRAMES) {
            val oldest = retransmitBuffer.keys.first()
            retransmitBuffer.remove(oldest)
        }
    }

    /**
     * What to do about one feedback packet.
     *
     * Everything up to the checkpoint is forgotten, then the NACKs are turned into frames to
     * resend. A NACK naming a frame that has already been dropped cannot be answered, so it asks
     * for a key frame instead - which is the only thing that lets a receiver recover without the
     * exact bytes it missed.
     */
    fun onFeedback(feedback: ReceiverFeedback): Recovery {
        retransmitBuffer.keys.removeAll { it <= feedback.checkpoint.value }
        val resend = mutableListOf<Retransmission>()
        var needKeyFrame = false
        for (nack in feedback.nacks) {
            val frame = retransmitBuffer[nack.frameId.value]
            if (frame == null) {
                // Either already acknowledged and dropped, or older than the buffer. Only the
                // second case needs a key frame, and telling them apart is not worth the state:
                // asking for one when it was not needed costs a single extra key frame.
                if (nack.frameId > feedback.checkpoint) needKeyFrame = true
                continue
            }
            resend += if (nack.isWholeFrame) {
                Retransmission(frame, packetIds = null)
            } else {
                Retransmission(frame, packetIds = listOf(nack.packetId))
            }
        }
        return Recovery(mergePacketIds(resend), needKeyFrame)
    }

    /** A single frame's worth of NACKs, coalesced. */
    private fun mergePacketIds(items: List<Retransmission>): List<Retransmission> {
        if (items.size <= 1) return items
        val byFrame = LinkedHashMap<Long, Retransmission>()
        for (item in items) {
            val key = item.frame.frameId.value
            val existing = byFrame[key]
            byFrame[key] = when {
                existing == null -> item
                // A whole-frame NACK subsumes any individual packet ids for that frame.
                existing.packetIds == null || item.packetIds == null ->
                    Retransmission(item.frame, packetIds = null)
                else -> Retransmission(
                    item.frame,
                    (existing.packetIds + item.packetIds).distinct().sorted(),
                )
            }
        }
        return byFrame.values.toList()
    }

    private companion object {
        /**
         * Two seconds of video at 30 fps. Comfortably more than the target playout delay a NACK
         * could reasonably refer to, and bounded so a silent receiver cannot grow the heap.
         */
        const val RETRANSMIT_BUFFER_FRAMES = 60
    }
}

/** One frame to resend; [packetIds] null means every packet of it. */
data class Retransmission(val frame: EncryptedFrame, val packetIds: List<Int>?)

/** What the sender should do in response to feedback. */
data class Recovery(
    val retransmissions: List<Retransmission>,
    /** True when a receiver has fallen behind further than the buffer can repair. */
    val needsKeyFrame: Boolean,
)
