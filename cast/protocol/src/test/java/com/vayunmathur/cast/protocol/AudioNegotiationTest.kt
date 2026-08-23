package com.vayunmathur.cast.protocol

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for negotiating audio, which until now was never negotiated at all.
 *
 * Audio needed no agreement while every session had a picture: a TV with no Opus decoder played a
 * silent video, which is a poor outcome but a legible one. An audio-only session has no picture to
 * fall back to, so the same TV would sit in silence with nothing to explain it and no failure state
 * to report - and that is the failure these tests exist to keep out.
 *
 * The other half is a session with no video codec, which the wire format could not previously
 * express. A codec field that is absent when there is no video and present when there is has to
 * round-trip both ways, because a receiver that read a default would be agreeing to decode something
 * nobody named.
 */
class AudioNegotiationTest {

    private val secret = ByteArray(SessionKeys.SECRET_BYTES) { (it * 7 + 3).toByte() }

    // ------------------------------------------------------------------
    // Advertisement
    // ------------------------------------------------------------------

    @Test
    fun `a TV that was never asked advertises no audio codec`() {
        // The default is what a receiver built against the older contract sends, and reading it as
        // "no Opus" is the honest interpretation: it did not say it had one.
        assertEquals(emptyList(), DecoderLimits().audioCodecs)
        assertFalse(CodecNegotiation.canPlayAudio(DecoderLimits()))
    }

    @Test
    fun `a TV advertising Opus can be sent audio`() {
        val limits = DecoderLimits(audioCodecs = listOf(AudioCodec.Opus))
        assertTrue(CodecNegotiation.canPlayAudio(limits))
    }

    @Test
    fun `advertising a video decoder says nothing about audio`() {
        // The two are independent hardware questions, and a TV with an AV1 decoder and no Opus
        // decoder is exactly the device this failure has to be reportable on.
        val videoOnly = DecoderLimits(
            videoCodecs = listOf(CodecLimits(VideoCodec.Av1, 3840, 2160, 60, 20_000_000)),
        )
        assertFalse(CodecNegotiation.canPlayAudio(videoOnly))
        assertEquals(listOf(VideoCodec.Av1), videoOnly.codecs)
    }

    @Test
    fun `the audio codec's wire name is pinned`() {
        // Both ends read this string. A rename that looked like a refactor would make every TV
        // report no audio decoder at all, which is indistinguishable from a TV that has none.
        val json = ControlJson.encodeToString(
            TvIdentity(
                version = PROTOCOL_VERSION,
                receiverName = "Living room",
                receiverId = "tv",
                publicBundle = "",
                limits = DecoderLimits(audioCodecs = listOf(AudioCodec.Opus)),
            ) as ControlMessage,
        )
        assertTrue(json.contains("\"OPUS\""), "got $json")
    }

    @Test
    fun `audio limits survive a round trip alongside video limits`() {
        val limits = DecoderLimits(
            videoCodecs = listOf(CodecLimits(VideoCodec.Hevc, 4096, 4096, 60, 40_000_000)),
            audioCodecs = listOf(AudioCodec.Opus),
        )
        val message = TvIdentity(
            version = PROTOCOL_VERSION,
            receiverName = "Living room",
            receiverId = "tv",
            publicBundle = "bundle",
            limits = limits,
        )
        val back = assertIs<TvIdentity>(
            ControlJson.decodeFromString<ControlMessage>(
                ControlJson.encodeToString(message as ControlMessage),
            ),
        )
        assertEquals(limits, back.limits)
    }

    @Test
    fun `a TV that omits the audio field entirely still parses`() {
        // Forwards compatibility in the direction that matters: an older TV's TV_IDENTITY has no
        // audioCodecs at all, and a phone that refused to parse it would refuse to cast anything.
        val json = """
            {"type":"TV_IDENTITY","version":$PROTOCOL_VERSION,"receiverName":"Old TV",
             "receiverId":"tv","publicBundle":"b","limits":{"videoCodecs":[]}}
        """.trimIndent()
        val back = assertIs<TvIdentity>(ControlJson.decodeFromString<ControlMessage>(json))
        assertEquals(emptyList(), back.limits.audioCodecs)
    }

    // ------------------------------------------------------------------
    // A session with no video
    // ------------------------------------------------------------------

    @Test
    fun `an audio-only stream config carries no video codec`() {
        val config = audioOnlyConfig()
        assertNull(config.videoCodec)
        val back = assertIs<StreamConfig>(
            ControlJson.decodeFromString<ControlMessage>(
                ControlJson.encodeToString(config as ControlMessage),
            ),
        )
        assertNull(back.videoCodec, "a defaulted codec would be a TV agreeing to decode a guess")
        assertFalse(back.video)
        assertTrue(back.audio)
    }

    @Test
    fun `a video stream config still requires its codec to survive the trip`() {
        val config = audioOnlyConfig().copy(video = true, videoCodec = VideoCodec.Av1)
        val back = assertIs<StreamConfig>(
            ControlJson.decodeFromString<ControlMessage>(
                ControlJson.encodeToString(config as ControlMessage),
            ),
        )
        assertEquals(VideoCodec.Av1, back.videoCodec)
    }

    @Test
    fun `an audio-only session negotiates one stream and reports no video`() {
        // `ReceiverController.pump` gates its whole decoder path on hasVideo, so this is the flag
        // that keeps an audio-only session from waiting for a picture that was never coming.
        val negotiation = Negotiation.of(
            config = audioOnlyConfig(),
            ready = StreamReady(udpPort = 4711, audioSsrc = 2, videoSsrc = 50_002),
            keys = SessionKeys.of(secret),
        )
        assertFalse(negotiation.hasVideo)
        assertNull(negotiation.video)
        val audio = assertNotNull(negotiation.audio)
        assertEquals(StreamKind.Audio, audio.kind)
        assertEquals(StreamConstants.AUDIO_TIMEBASE, audio.timebase)
        assertEquals(1, negotiation.streams.size)
    }

    // ------------------------------------------------------------------
    // The format is stated once
    // ------------------------------------------------------------------

    @Test
    fun `the audio format has one definition on this side of the broker`() {
        // The phone's encoder and the TV's decoder used to state the sample rate, the channel count
        // and the MIME type independently. Two of those are the RTP timebase and the Opus header's
        // own fields, so a drift would have been a stream neither end could explain.
        assertEquals(48_000, StreamConstants.AUDIO_TIMEBASE)
        assertEquals(2, StreamConstants.AUDIO_CHANNELS)
        assertEquals("audio/opus", AudioCodec.Opus.mimeType)
    }

    private fun audioOnlyConfig() = StreamConfig(
        width = 0,
        height = 0,
        frameRate = 0,
        bitRate = 0,
        audio = true,
        video = false,
        audioSsrc = 1,
        videoSsrc = 50_001,
        videoCodec = null,
    )
}
