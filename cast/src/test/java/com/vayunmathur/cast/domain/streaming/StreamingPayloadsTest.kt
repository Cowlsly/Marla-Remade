package com.vayunmathur.cast.domain.streaming

import com.vayunmathur.cast.domain.CastDeviceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A real ANSWER from a Google TV, captured on hardware: both streams, and a display block. */
private val ANSWER_TV = """
    {"answer":{"display":{"dimensions":{"frameRate":"60","height":2160,"width":3840},
      "scaling":"sender"},"sendIndexes":[0,1],"ssrcs":[20002,50002],"udpPort":47505},
      "result":"ok","seqNum":2,"type":"ANSWER"}
""".trimIndent()

/**
 * A real ANSWER from a speaker: the audio index alone, no display block, and three optional fields
 * the TV omitted - including `receiverRtcpEventLog` as an int array where the OFFER's is a boolean.
 */
private val ANSWER_SPEAKER = """
    {"answer":{"castMode":"mirroring","receiverRtcpEventLog":[0],"rtpExtensions":[[]],
      "sendIndexes":[0],"ssrcs":[20002],"udpPort":10008},
      "result":"ok","seqNum":2,"type":"ANSWER"}
""".trimIndent()

class StreamingPayloadsTest {

    @Test
    fun `a tv offer carries audio at index zero and video at index one`() {
        // The order is load-bearing: CreateMirroringOffer puts audio first and the ANSWER's
        // sendIndexes refers back to these numbers.
        val plan = StreamSelection.offer(CastDeviceKind.Tv)
        assertEquals(2, plan.streams.size)
        assertEquals(listOf(0, 1), plan.streams.map { it.index })
        assertEquals(listOf("audio_source", "video_source"), plan.streams.map { it.type })
        assertEquals(listOf("opus", "h264"), plan.streams.map { it.codecName })
        assertTrue(plan.hasVideo)
    }

    @Test
    fun `a speaker and a group are offered audio only`() {
        for (kind in listOf(CastDeviceKind.Speaker, CastDeviceKind.Group)) {
            val plan = StreamSelection.offer(kind)
            assertEquals(listOf("audio_source"), plan.streams.map { it.type }, "$kind")
            assertNull(plan.videoKeys, "$kind")
            assertTrue(StreamSelection.isAudioOnly(kind), "$kind")
        }
    }

    @Test
    fun `payload types are the android hack values, not the obvious ones`() {
        // 96/127 rather than kAudioOpus/kVideoH264: use_android_rtp_hack defaults to true and
        // Chrome never overrides it, so this is what receivers expect.
        val plan = StreamSelection.offer(CastDeviceKind.Tv)
        assertEquals(127, plan.streams[0].rtpPayloadType)
        assertEquals(96, plan.streams[1].rtpPayloadType)
    }

    @Test
    fun `ssrcs come from the documented priority ranges`() {
        // Audio must sort below video so it wins under contention; ComparePriority is numeric.
        repeat(20) {
            val plan = StreamSelection.offer(CastDeviceKind.Tv)
            assertTrue(plan.audioSsrc in 1..50_000, "audio ssrc ${plan.audioSsrc}")
            assertTrue(plan.videoSsrc!! in 50_001..100_000, "video ssrc ${plan.videoSsrc}")
        }
    }

    @Test
    fun `keys are per-stream, sixteen bytes, and hex-encoded on the wire`() {
        val plan = StreamSelection.offer(CastDeviceKind.Tv)
        assertEquals(16, plan.audioKeys.key.size)
        assertEquals(16, plan.audioKeys.ivMask.size)
        // Sharing a key between streams would reuse a keystream across two frame-id sequences.
        assertTrue(plan.audioKeys != plan.videoKeys)
        for (stream in plan.streams) {
            assertEquals(32, stream.aesKey.length)
            assertEquals(32, stream.aesIvMask.length)
            assertTrue(stream.aesKey.all { it in "0123456789abcdef" }, stream.aesKey)
        }
    }

    @Test
    fun `the offer serialises with the field forms the receiver expects`() {
        val json = StreamSelection.offer(CastDeviceKind.Tv).message(seqNum = 2).encode()
        assertTrue(json.contains("\"type\":\"OFFER\""), json)
        assertTrue(json.contains("\"seqNum\":2"), json)
        assertTrue(json.contains("\"castMode\":\"mirroring\""), json)
        assertTrue(json.contains("\"rtpProfile\":\"cast\""), json)
        // Both timebases and the frame rate are rational *strings*, not numbers.
        assertTrue(json.contains("\"timeBase\":\"1/48000\""), json)
        assertTrue(json.contains("\"timeBase\":\"1/90000\""), json)
        assertTrue(json.contains("\"maxFrameRate\":\"30000/1000\""), json)
        // explicitNulls is off, so audio-only fields must be absent from the video stream and
        // vice versa rather than present as null.
        assertTrue(!json.contains("null"), json)
    }

    @Test
    fun `an audio-only offer omits every video field`() {
        val json = StreamSelection.offer(CastDeviceKind.Speaker).message(seqNum = 1).encode()
        assertTrue(!json.contains("maxFrameRate"), json)
        assertTrue(!json.contains("resolutions"), json)
        assertTrue(!json.contains("video_source"), json)
        assertTrue(json.contains("\"bitRate\":128000"), json)
    }

    @Test
    fun `the tv answer parses, including the display block`() {
        val message = assertNotNull(parseAnswer(ANSWER_TV))
        assertEquals("ok", message.result)
        val answer = assertNotNull(message.answer)
        assertEquals(47505, answer.udpPort)
        assertEquals(listOf(0, 1), answer.sendIndexes)
        assertEquals(listOf(20002L, 50002L), answer.ssrcs)
        assertEquals(3840, answer.display?.dimensions?.width)
        // A string, not a number - parsing it as Int would fail the whole ANSWER.
        assertEquals("60", answer.display?.dimensions?.frameRate)
        assertEquals("sender", answer.display?.scaling)
    }

    @Test
    fun `the speaker answer parses despite disagreeing with the tv about optional fields`() {
        val answer = assertNotNull(assertNotNull(parseAnswer(ANSWER_SPEAKER)).answer)
        assertEquals(10008, answer.udpPort)
        assertEquals(listOf(0), answer.sendIndexes)
        // No display block at all: that absence is how "this device has no screen" arrives.
        assertNull(answer.display)
        assertEquals(listOf(0), answer.receiverRtcpEventLog)
        assertEquals(listOf(emptyList<String>()), answer.rtpExtensions)
    }

    @Test
    fun `an error answer is parsed rather than discarded`() {
        val message = assertNotNull(
            parseAnswer(
                """{"type":"ANSWER","seqNum":2,"result":"error",
                   |"error":{"code":3,"description":"bad offer"}}""".trimMargin(),
            ),
        )
        assertEquals("error", message.result)
        assertEquals(3, message.error?.code)
        assertNull(message.answer)
    }

    @Test
    fun `something that is not an answer is not mistaken for one`() {
        assertNull(parseAnswer("""{"type":"STATUS_RESPONSE","seqNum":1}"""))
        assertNull(parseAnswer("not json"))
        // An unknown field must not break parsing; firmware adds them.
        assertIs<AnswerMessage>(
            parseAnswer(
                """{"type":"ANSWER","seqNum":1,"result":"ok","somethingNew":true,
                   |"answer":{"udpPort":1,"sendIndexes":[0],"ssrcs":[2]}}""".trimMargin(),
            ),
        )
    }
}
