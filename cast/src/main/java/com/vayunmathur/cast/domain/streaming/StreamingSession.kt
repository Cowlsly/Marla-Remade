package com.vayunmathur.cast.domain.streaming

/** Which half of the stream a packet belongs to. */
enum class StreamKind { Audio, Video }

/**
 * One negotiated stream: where to send, and what to encrypt with.
 *
 * [receiverSsrc] comes out of the ANSWER rather than being derived. The receiver happened to answer
 * with our SSRC plus one on every observed session, but that is its choice to make and nothing here
 * may assume it.
 */
data class NegotiatedStream(
    val kind: StreamKind,
    val index: Int,
    val senderSsrc: Long,
    val receiverSsrc: Long,
    val payloadType: Int,
    val timebase: Int,
    val keys: StreamKeys,
)

/** Everything the ANSWER settled. */
data class Negotiation(
    val udpPort: Int,
    val streams: List<NegotiatedStream>,
) {
    val audio: NegotiatedStream? get() = streams.firstOrNull { it.kind == StreamKind.Audio }
    val video: NegotiatedStream? get() = streams.firstOrNull { it.kind == StreamKind.Video }
    val hasVideo: Boolean get() = video != null
}

/** Why an ANSWER could not be used. */
sealed interface NegotiationFailure {
    /** The receiver said `"result":"error"`. */
    data class Refused(val code: Int, val description: String) : NegotiationFailure

    /** The receiver accepted nothing we offered, so there is nothing to send. */
    data object NoStreams : NegotiationFailure

    /** The ANSWER was not shaped the way the protocol says. */
    data class Malformed(val detail: String) : NegotiationFailure
}

/**
 * `CastSession`'s sibling: the OFFER/ANSWER and loss-recovery state machine, with no I/O.
 *
 * Kept free of sockets and encoders for the same reason `CastSession` is - loss recovery is the
 * part with the subtle rules, and this way every one of them is a JVM unit test.
 */
class StreamingSession(private val plan: StreamPlan) {

    var negotiation: Negotiation? = null
        private set

    /**
     * Frames we may still be asked to resend, newest last.
     *
     * Bounded, because a receiver that stops sending feedback must not be able to make the sender
     * grow without limit. Dropping the oldest is right: a NACK for a frame we no longer hold is
     * answered by the next key frame anyway.
     */
    private val retransmitBuffer = LinkedHashMap<Long, EncryptedFrame>()

    /**
     * Turn an ANSWER into routes.
     *
     * [Answer.sendIndexes] is authoritative, not what we offered: a speaker answers `[0]` even
     * though the audio-only OFFER also contained only index 0, and a TV could in principle accept
     * a subset. Anything we offered and it did not accept is simply not sent.
     */
    fun onAnswer(message: AnswerMessage): NegotiationFailure? {
        if (message.result != "ok") {
            val error = message.error
            return NegotiationFailure.Refused(error?.code ?: 0, error?.description ?: "")
        }
        val answer = message.answer ?: return NegotiationFailure.Malformed("no answer body")
        if (answer.sendIndexes.size != answer.ssrcs.size) {
            return NegotiationFailure.Malformed(
                "sendIndexes and ssrcs disagree: " +
                    "${answer.sendIndexes.size} vs ${answer.ssrcs.size}",
            )
        }
        if (answer.udpPort !in 1..65535) {
            return NegotiationFailure.Malformed("udpPort ${answer.udpPort}")
        }
        val streams = answer.sendIndexes.mapIndexedNotNull { position, index ->
            val receiverSsrc = answer.ssrcs[position]
            when (index) {
                StreamSelection.AUDIO_INDEX -> NegotiatedStream(
                    kind = StreamKind.Audio,
                    index = index,
                    senderSsrc = plan.audioSsrc,
                    receiverSsrc = receiverSsrc,
                    payloadType = StreamSelection.AUDIO_PAYLOAD_TYPE,
                    timebase = StreamSelection.AUDIO_TIMEBASE,
                    keys = plan.audioKeys,
                )
                StreamSelection.VIDEO_INDEX -> {
                    val keys = plan.videoKeys
                    val ssrc = plan.videoSsrc
                    // A receiver accepting an index we never offered is its bug, not a reason to
                    // abandon the session - drop the stream and carry on with the rest.
                    if (keys == null || ssrc == null) {
                        null
                    } else {
                        NegotiatedStream(
                            kind = StreamKind.Video,
                            index = index,
                            senderSsrc = ssrc,
                            receiverSsrc = receiverSsrc,
                            payloadType = StreamSelection.VIDEO_PAYLOAD_TYPE,
                            timebase = StreamSelection.VIDEO_TIMEBASE,
                            keys = keys,
                        )
                    }
                }
                else -> null
            }
        }
        if (streams.isEmpty()) return NegotiationFailure.NoStreams
        negotiation = Negotiation(answer.udpPort, streams)
        return null
    }

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
         * Two seconds of video at 30 fps. Comfortably more than the 400 ms target playout delay a
         * NACK could reasonably refer to, and bounded so a silent receiver cannot grow the heap.
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
