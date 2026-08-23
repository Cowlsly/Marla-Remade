package com.vayunmathur.cast.protocol

/** What [CodecNegotiation.choose] settled on, or what each end offered when nothing fitted. */
sealed interface CodecSelection {

    /**
     * Agreed.
     *
     * [receiverLimits] is the TV's envelope for [codec], and it is what the frame is fitted into and
     * what the frame rate is capped by - not the sender's, which only decides whether the fitted frame
     * is encodable at all.
     *
     * [bitRateCeiling] is the **lower** of the two ends' ceilings, because neither may be exceeded: a
     * phone whose encoder advertises less than the TV's decoder would otherwise be configured above its
     * own stated maximum.
     */
    data class Chosen(
        val codec: VideoCodec,
        val receiverLimits: CodecLimits,
        val bitRateCeiling: Int,
    ) : CodecSelection

    /**
     * Nothing fitted, with both offers named so the failure can say *which* end was short.
     *
     * An empty [senderOffered] is a phone with no hardware encoder for either codec; an empty
     * [receiverOffered] is a TV with no hardware decoder for either. Two non-empty lists with nothing
     * in common is a third thing again, and all three read differently to a user - so the lists are
     * carried rather than collapsed into one reason here.
     *
     * [demoted] is carried for the same reason: a codec both ends offer but that has already failed on
     * this pairing is skipped silently, and a message built from the offers alone would then claim two
     * ends had nothing in common when in fact they had, which is a lie the user cannot act on.
     */
    data class None(
        val senderOffered: List<VideoCodec>,
        val receiverOffered: List<VideoCodec>,
        val demoted: Set<VideoCodec>,
    ) : CodecSelection
}

/**
 * Which codec a session will use.
 *
 * **A pure function on purpose.** Every unknown in this feature is a device fact - whether a phone
 * has hardware AV1 at all, whether a TV's AV1 decoder is the software one the platform lists first -
 * and none of them can be answered without hardware. What *can* be settled without a device is the
 * rule applied to the answers, so it lives here where a JVM test can pin it down, and the platform
 * work on each side is reduced to filling in two lists.
 */
object CodecNegotiation {

    /**
     * AV1 first.
     *
     * It reaches the same quality at roughly half H.264's bitrate against H.265's ~0.6, and on a link
     * that answered 24 Mbit/s with 8.5% packet loss the bitrate is the constraint that matters. It is
     * also the codec with the unknowns, which is the argument for preferring it as soon as both ends
     * claim it: the first session is the experiment, and there is a persisted demotion behind it.
     */
    val PREFERENCE: List<VideoCodec> = listOf(VideoCodec.Av1, VideoCodec.Hevc)

    /**
     * The best codec both ends can do at [width] x [height] and [frameRate].
     *
     * [senderCodecs] is what this phone can *hardware*-encode, one entry per codec, and [receiver] is
     * what the TV advertised in `TV_IDENTITY`. A codec is viable when both ends offer it and the
     * sender's own envelope takes the frame after it has been fitted into the receiver's - which is
     * the real asymmetry between the two sides: the receiver's limits scale the frame down, while the
     * sender's encoder either takes it or does not.
     *
     * **[frameRate] is a floor against the sender's envelope only.** A phone whose encoder cannot
     * sustain it is excluded rather than accepted at a lower rate, because an encoder that silently
     * under-delivers - measured at 11 fps against a negotiated 30 - is what this ordering exists to
     * prevent. A *receiver* that caps out lower is a different matter: it genuinely cannot go faster,
     * and there is nothing to be gained by refusing it, so its cap lowers the session's rate instead
     * (see `MirrorGeometry.frameRateFor`).
     *
     * [demoted] is remembered per TV from a previous failure, so a codec that has already proved
     * itself broken on this pairing is skipped rather than retried every session.
     */
    fun choose(
        senderCodecs: List<CodecLimits>,
        receiver: DecoderLimits,
        width: Int,
        height: Int,
        frameRate: Int,
        demoted: Set<VideoCodec> = emptySet(),
    ): CodecSelection {
        for (codec in PREFERENCE) {
            if (codec in demoted) continue
            val mine = senderCodecs.firstOrNull { it.codec == codec } ?: continue
            val theirs = receiver.forCodec(codec) ?: continue
            val (fittedWidth, fittedHeight) = theirs.fit(width, height)
            if (!mine.admits(fittedWidth, fittedHeight, frameRate)) continue
            return CodecSelection.Chosen(
                codec = codec,
                receiverLimits = theirs,
                bitRateCeiling = tighterCeiling(mine.maxBitRate, theirs.maxBitRate),
            )
        }
        return CodecSelection.None(
            senderOffered = senderCodecs.map { it.codec },
            receiverOffered = receiver.codecs,
            demoted = demoted,
        )
    }

    /** The lower of two ceilings, treating a non-positive one as "did not say". */
    private fun tighterCeiling(mine: Int, theirs: Int): Int = when {
        mine <= 0 -> theirs
        theirs <= 0 -> mine
        else -> minOf(mine, theirs)
    }

    /**
     * Whether the TV can decode the one audio codec this protocol carries.
     *
     * Not part of [choose], because it is not a choice: there is one audio codec, so the only
     * question is whether the receiver has it. It matters on its own because an audio-only session
     * stands or falls on the answer - and a TV that cannot decode Opus has to be refused by name
     * rather than sent a stream it will silently drop.
     */
    fun canPlayAudio(receiver: DecoderLimits): Boolean = AudioCodec.Opus in receiver.audioCodecs
}
