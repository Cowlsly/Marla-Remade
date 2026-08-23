package com.vayunmathur.cast.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Codec selection, which is the part of this feature that can be settled without a device.
 *
 * Every unknown behind H.265 and AV1 mirroring is a device fact - whether a phone has hardware AV1,
 * whether a TV's AV1 decoder is the software one the platform lists first - and none of them can be
 * answered here. What can be pinned down is the rule applied to the answers, and that rule is the
 * thing that decides whether a session runs at 6 Mbit/s or refuses outright.
 */
class CodecNegotiationTest {

    private fun limits(
        codec: VideoCodec,
        maxWidth: Int = 3840,
        maxHeight: Int = 2160,
        maxFrameRate: Int = 60,
        maxBitRate: Int = 40_000_000,
    ) = CodecLimits(codec, maxWidth, maxHeight, maxFrameRate, maxBitRate)

    private val bothCodecs = listOf(limits(VideoCodec.Av1), limits(VideoCodec.Hevc))

    private fun choose(
        sender: List<CodecLimits>,
        receiver: List<CodecLimits>,
        width: Int = 1344,
        height: Int = 2992,
        frameRate: Int = 30,
        demoted: Set<VideoCodec> = emptySet(),
    ) = CodecNegotiation.choose(
        senderCodecs = sender,
        receiver = DecoderLimits(receiver),
        width = width,
        height = height,
        frameRate = frameRate,
        demoted = demoted,
    )

    @Test
    fun `AV1 wins when both ends can do both`() {
        // The preference, and the reason it is AV1: roughly half H.264's bitrate for the same picture,
        // on a link whose problem was bitrate.
        val chosen = assertIs<CodecSelection.Chosen>(choose(bothCodecs, bothCodecs))
        assertEquals(VideoCodec.Av1, chosen.codec)
    }

    @Test
    fun `H265 is used when only one end has AV1`() {
        val phoneOnly = assertIs<CodecSelection.Chosen>(
            choose(bothCodecs, listOf(limits(VideoCodec.Hevc))),
        )
        assertEquals(VideoCodec.Hevc, phoneOnly.codec)

        val tvOnly = assertIs<CodecSelection.Chosen>(
            choose(listOf(limits(VideoCodec.Hevc)), bothCodecs),
        )
        assertEquals(VideoCodec.Hevc, tvOnly.codec)
    }

    @Test
    fun `the receiver's envelope is what comes back, and the ceiling is the tighter of the two`() {
        // Load-bearing: the frame size and the frame rate are both taken from the receiver's envelope,
        // and taking the phone's instead would send a TV more than it said it could decode. The bitrate
        // is the one number where *neither* end may be exceeded.
        val chosen = assertIs<CodecSelection.Chosen>(
            choose(
                sender = listOf(limits(VideoCodec.Av1, maxBitRate = 40_000_000)),
                receiver = listOf(limits(VideoCodec.Av1, maxBitRate = 8_000_000)),
            ),
        )
        assertEquals(8_000_000, chosen.receiverLimits.maxBitRate)
        assertEquals(8_000_000, chosen.bitRateCeiling)

        // And the other way round: a phone whose encoder is the tighter one must not be configured
        // above what it said it would take.
        val phoneTighter = assertIs<CodecSelection.Chosen>(
            choose(
                sender = listOf(limits(VideoCodec.Av1, maxBitRate = 5_000_000)),
                receiver = listOf(limits(VideoCodec.Av1, maxBitRate = 40_000_000)),
            ),
        )
        assertEquals(5_000_000, phoneTighter.bitRateCeiling)
    }

    @Test
    fun `an end that stated no bitrate ceiling does not clamp the other to zero`() {
        val noCeiling = assertIs<CodecSelection.Chosen>(
            choose(
                sender = listOf(limits(VideoCodec.Av1, maxBitRate = 0)),
                receiver = listOf(limits(VideoCodec.Av1, maxBitRate = 9_000_000)),
            ),
        )
        assertEquals(9_000_000, noCeiling.bitRateCeiling)
    }

    @Test
    fun `a phone with no hardware encoder refuses, and says so was the phone`() {
        // No H.264 to fall back to, so this is a terminal answer - which is why the two lists are
        // carried out: an empty sender list is a different sentence from an empty receiver one.
        val none = assertIs<CodecSelection.None>(choose(emptyList(), bothCodecs))
        assertEquals(emptyList<VideoCodec>(), none.senderOffered)
        assertEquals(listOf(VideoCodec.Av1, VideoCodec.Hevc), none.receiverOffered)
    }

    @Test
    fun `a TV with no hardware decoder refuses, and says so was the TV`() {
        val none = assertIs<CodecSelection.None>(choose(bothCodecs, emptyList()))
        assertEquals(listOf(VideoCodec.Av1, VideoCodec.Hevc), none.senderOffered)
        assertEquals(emptyList<VideoCodec>(), none.receiverOffered)
    }

    @Test
    fun `two ends with nothing in common refuse, with both offers named`() {
        val none = assertIs<CodecSelection.None>(
            choose(listOf(limits(VideoCodec.Av1)), listOf(limits(VideoCodec.Hevc))),
        )
        assertEquals(listOf(VideoCodec.Av1), none.senderOffered)
        assertEquals(listOf(VideoCodec.Hevc), none.receiverOffered)
    }

    @Test
    fun `a codec whose encoder cannot hold the frame rate is skipped`() {
        // The rate is the floor and resolution is what yields to it - an *encoder* that tops out at
        // 24 fps is excluded rather than quietly accepted, because a stream delivering 11 fps against a
        // negotiated 30 is the failure this ordering exists to prevent.
        val chosen = assertIs<CodecSelection.Chosen>(
            choose(
                sender = listOf(limits(VideoCodec.Av1, maxFrameRate = 24), limits(VideoCodec.Hevc)),
                receiver = bothCodecs,
                frameRate = 30,
            ),
        )
        assertEquals(VideoCodec.Hevc, chosen.codec)
    }

    @Test
    fun `a TV that caps out below the floor still gets the codec, at its own rate`() {
        // The deliberate asymmetry: a decoder that cannot go faster genuinely cannot, and refusing to
        // mirror to it would gain nothing. Its cap lowers the session instead, which is what
        // MirrorGeometry.frameRateFor does with the envelope handed back here.
        val chosen = assertIs<CodecSelection.Chosen>(
            choose(
                sender = bothCodecs,
                receiver = listOf(limits(VideoCodec.Av1, maxFrameRate = 24)),
                frameRate = 30,
            ),
        )
        assertEquals(VideoCodec.Av1, chosen.codec)
        assertEquals(24, chosen.receiverLimits.maxFrameRate)
    }

    @Test
    fun `a codec whose encoder cannot hold the frame is skipped, after the TV has scaled it`() {
        // The asymmetry between the two ends: the TV's envelope *scales* the frame, and the phone's
        // encoder then either takes the result or does not. A 1080p-only encoder is excluded at
        // 1344x2992 even though the 4K TV would have taken it.
        val chosen = assertIs<CodecSelection.Chosen>(
            choose(
                sender = listOf(
                    limits(VideoCodec.Av1, maxWidth = 1920, maxHeight = 1080),
                    limits(VideoCodec.Hevc),
                ),
                receiver = bothCodecs,
            ),
        )
        assertEquals(VideoCodec.Hevc, chosen.codec)

        // And the same encoder is fine once the TV's own limit has brought the frame inside it. A
        // quarter of 1344x2992 is 336x748, which a 1080p encoder takes.
        val scaled = assertIs<CodecSelection.Chosen>(
            choose(
                sender = listOf(limits(VideoCodec.Av1, maxWidth = 1920, maxHeight = 1080)),
                receiver = listOf(limits(VideoCodec.Av1, maxWidth = 336, maxHeight = 748)),
            ),
        )
        assertEquals(VideoCodec.Av1, scaled.codec)
    }

    @Test
    fun `a codec demoted against this TV is not tried again`() {
        // The recovery path in place of a mid-session downgrade: an AV1 failure is remembered per TV,
        // so the next session starts at H.265 rather than repeating the same black screen.
        val chosen = assertIs<CodecSelection.Chosen>(
            choose(bothCodecs, bothCodecs, demoted = setOf(VideoCodec.Av1)),
        )
        assertEquals(VideoCodec.Hevc, chosen.codec)

        // Both demoted is a refusal, not a fallback to the codec that already failed.
        assertIs<CodecSelection.None>(
            choose(bothCodecs, bothCodecs, demoted = setOf(VideoCodec.Av1, VideoCodec.Hevc)),
        )
    }

    @Test
    fun `a refusal caused by a demotion says so rather than denying the codec was shared`() {
        // Otherwise the message built from the two offers would claim a phone that can send AV1 and a TV
        // that can decode AV1 have nothing in common, which is untrue and leaves the user no way to find
        // the remembered demotion that is the real cause.
        val none = assertIs<CodecSelection.None>(
            choose(
                sender = listOf(limits(VideoCodec.Av1)),
                receiver = bothCodecs,
                demoted = setOf(VideoCodec.Av1),
            ),
        )
        assertEquals(setOf(VideoCodec.Av1), none.demoted)
        assertEquals(listOf(VideoCodec.Av1), none.senderOffered)
        // Both ends offered it, so the caller can tell this apart from a genuine mismatch.
        assertTrue(VideoCodec.Av1 in none.senderOffered && VideoCodec.Av1 in none.receiverOffered)
    }

    @Test
    fun `fit keeps the aspect ratio and scales by the tighter of the two ratios`() {
        // A 4K decoder still cannot take a tall portrait frame unrotated, and scaling by width alone
        // would overflow the height. Halving is used rather than a real phone shape so the assertion is
        // about the rule and not about which way a double rounded.
        val tv = limits(VideoCodec.Av1, maxWidth = 3840, maxHeight = 2000)
        assertEquals(500 to 2000, tv.fit(1000, 4000))

        // Already inside is left exactly alone, so no frame is resampled for nothing.
        assertEquals(1920 to 1080, tv.fit(1920, 1080))
    }

    @Test
    fun `a degenerate envelope is treated as unknown rather than as zero`() {
        // A decoder that answered 0 would otherwise scale every frame to nothing.
        val unknown = limits(VideoCodec.Av1, maxWidth = 0, maxHeight = 0)
        assertEquals(1344 to 2992, unknown.fit(1344, 2992))
    }
}
