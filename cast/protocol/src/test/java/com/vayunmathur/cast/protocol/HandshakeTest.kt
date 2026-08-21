package com.vayunmathur.cast.protocol

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The control channel, from framing up to a paired session.
 *
 * Everything here runs on the JVM because the one part that cannot - sealing the secret to the TV's
 * ML-KEM bundle - is isolated in [SecretSealing] and takes no part in these rules. The secret is
 * therefore handed over directly, which is exactly what the real handshake produces anyway.
 */
class HandshakeTest {

    private val secret = ByteArray(SessionKeys.SECRET_BYTES) { (it * 5 + 1).toByte() }

    private val limits = DecoderLimits(
        maxWidth = 3840,
        maxHeight = 2160,
        maxFrameRate = 60,
        maxBitRate = 40_000_000,
    )

    // ---- framing ----

    @Test
    fun `a framed body reads back as the same bytes`() {
        val bodies = listOf(
            "hello".toByteArray(),
            ByteArray(1),
            ByteArray(70_000) { it.toByte() },
        )
        val stream = bodies.fold(ByteArray(0)) { acc, body -> acc + ControlFraming.encode(body) }
        val input = DataInputStream(ByteArrayInputStream(stream))
        for (expected in bodies) {
            assertContentEquals(expected, ControlFraming.read(input))
        }
        // Clean end of stream, not an exception.
        assertNull(ControlFraming.read(input))
    }

    @Test
    fun `an absurd length prefix is refused rather than allocated`() {
        // A stream cannot be resynchronised after one, so carrying on would read payload as length
        // for ever - and allocating what it asked for is a one-packet denial of service.
        val hostile = byteArrayOf(0x7f, -1, -1, -1)
        assertFailsWith<IllegalArgumentException> {
            ControlFraming.read(DataInputStream(ByteArrayInputStream(hostile)))
        }
        assertFailsWith<IllegalArgumentException> {
            ControlFraming.read(DataInputStream(ByteArrayInputStream(ByteArray(4))))
        }
    }

    // ---- message round trip ----

    @Test
    fun `every handshake message round-trips through the codec`() {
        val messages = listOf<ControlMessage>(
            Hello(senderName = "Pixel 9", senderId = "phone-1", paired = true),
            TvIdentity(
                receiverName = "Living Room TV",
                receiverId = "tv-1",
                publicBundle = ProtocolBase64.encode(ByteArray(2_400) { it.toByte() }),
                limits = limits,
            ),
            SealedSecret(sealed = ProtocolBase64.encode(ByteArray(1_200) { (-it).toByte() })),
            PairRequired(code = true, attemptsLeft = 3),
            PairRequired(code = false),
            PairProof(proof = ProtocolBase64.encode(ByteArray(32))),
            PairOk(deviceKey = ProtocolBase64.encode(ByteArray(32) { 9 })),
            PairOk(deviceKey = null),
            PairFailed(attemptsLeft = 2),
            StreamConfig(
                width = 1440,
                height = 3120,
                frameRate = 30,
                bitRate = 12_000_000,
                audio = true,
                video = true,
                audioSsrc = 1_234,
                videoSsrc = 50_002,
            ),
            StreamReady(udpPort = 47_505, audioSsrc = 4_321, videoSsrc = 50_009),
            Bye(reason = "user stopped"),
        )
        val codec = ControlCodec()
        for (message in messages) {
            assertEquals(message, codec.decode(codec.encode(message)), "$message did not round-trip")
        }
    }

    @Test
    fun `the discriminator is the type field, so the wire is readable`() {
        // Not cosmetic: a wire format nobody can read in a log is the reason five rounds of
        // debugging never found the packet that was wrong.
        val body = ControlCodec().encode(Bye("done")).toString(Charsets.UTF_8)
        assertTrue(body.contains("\"type\":\"BYE\""), body)
    }

    @Test
    fun `an absent device key stays absent rather than becoming null`() {
        val body = ControlCodec().encode(PairOk(deviceKey = null)).toString(Charsets.UTF_8)
        assertFalse(body.contains("deviceKey"), body)
    }

    @Test
    fun `a message from a newer build with extra fields still parses`() {
        val codec = ControlCodec()
        val withExtra = """{"type":"STREAM_READY","udpPort":9,"audioSsrc":1,"videoSsrc":2,"hdr":true}"""
        assertEquals(
            StreamReady(udpPort = 9, audioSsrc = 1, videoSsrc = 2),
            codec.decode(withExtra.toByteArray()),
        )
    }

    @Test
    fun `a type this build does not know decodes to null rather than throwing`() {
        assertNull(ControlCodec().decode("""{"type":"TELEPORT"}""".toByteArray()))
        assertNull(ControlCodec().decode("not json at all".toByteArray()))
    }

    // ---- encryption ----

    @Test
    fun `messages after the secret is established are encrypted and still round-trip`() {
        val keys = SessionKeys.of(secret)
        val sender = ControlCodec()
        val receiver = ControlCodec()
        assertFalse(sender.isEncrypting)
        sender.useSessionKey(keys.control)
        receiver.useSessionKey(keys.control)
        assertTrue(sender.isEncrypting)

        val message = StreamReady(udpPort = 47_505, audioSsrc = 1, videoSsrc = 2)
        val body = sender.encode(message)
        // Genuinely encrypted: the plaintext would have said so.
        assertFalse(body.toString(Charsets.UTF_8).contains("STREAM_READY"))
        assertEquals(message, receiver.decode(body))
    }

    @Test
    fun `a tampered control frame fails its tag rather than being accepted`() {
        // This is what stops unauthenticated media from being usable to hijack a session: the
        // control channel is AES-256-GCM, so a flipped bit closes the session.
        val keys = SessionKeys.of(secret)
        val codec = ControlCodec().apply { useSessionKey(keys.control) }
        val body = codec.encode(Bye("bye"))
        body[body.size - 1] = (body[body.size - 1].toInt() xor 1).toByte()
        assertNull(codec.decode(body))
    }

    @Test
    fun `a frame encrypted under another secret is refused`() {
        val mine = ControlCodec().apply { useSessionKey(SessionKeys.of(secret).control) }
        val theirs = ControlCodec().apply {
            useSessionKey(SessionKeys.of(ByteArray(SessionKeys.SECRET_BYTES) { 42 }).control)
        }
        assertNull(mine.decode(theirs.encode(Bye("hi"))))
    }

    // ---- key schedule ----

    @Test
    fun `both ends derive the same schedule from the same secret`() {
        // Keys are derived, never sent, so this is the only thing that makes the media decryptable
        // at all. A mismatch would show as a picture of pure noise with nothing logged anywhere.
        val phone = SessionKeys.of(secret)
        val tv = SessionKeys.of(secret.copyOf())
        assertContentEquals(phone.control, tv.control)
        assertEquals(phone.audio, tv.audio)
        assertEquals(phone.video, tv.video)
    }

    @Test
    fun `every derived key is distinct and correctly sized`() {
        val keys = SessionKeys.of(secret)
        assertEquals(32, keys.control.size)
        assertEquals(16, keys.audio.key.size)
        assertEquals(16, keys.audio.ivMask.size)
        // Audio and video are separate frame-id sequences, so a shared key would reuse a counter
        // block across two different plaintexts.
        assertFalse(keys.audio.key.contentEquals(keys.video.key))
        assertFalse(keys.audio.key.contentEquals(keys.audio.ivMask))
        assertFalse(keys.control.contentEquals(keys.audio.key + keys.audio.ivMask))
    }

    @Test
    fun `a secret of the wrong size is refused`() {
        assertFailsWith<IllegalArgumentException> { SessionKeys.of(ByteArray(16)) }
    }

    @Test
    fun `newSecret produces the documented length`() {
        assertEquals(SessionKeys.SECRET_BYTES, SessionKeys.newSecret().size)
    }

    // ---- pairing ----

    @Test
    fun `the right code pairs and yields a device key`() {
        val keys = SessionKeys.of(secret)
        val transcript = transcript()
        val gate = PairingGate { "123456" }
        val result = gate.verifyCode(keys, transcript, keys.pairProof("123456", transcript))
        val ok = assertIs<PairResult.Ok>(result)
        assertEquals(PairingGate.DEVICE_KEY_BYTES, assertNotNull(ok.deviceKey).size)
    }

    @Test
    fun `a wrong code fails without spending the whole allowance`() {
        val keys = SessionKeys.of(secret)
        val transcript = transcript()
        val gate = PairingGate { "123456" }
        val wrong = assertIs<PairResult.Wrong>(
            gate.verifyCode(keys, transcript, keys.pairProof("000000", transcript)),
        )
        assertEquals(PairCode.MAX_ATTEMPTS - 1, wrong.attemptsLeft)
        assertFalse(wrong.codeChanged)
        // The code is still the one on screen, so the user can simply try again.
        assertEquals("123456", gate.code)
        // And the right one still works afterwards.
        assertIs<PairResult.Ok>(gate.verifyCode(keys, transcript, keys.pairProof("123456", transcript)))
    }

    @Test
    fun `three wrong codes throw the code away and put a new one on screen`() {
        // The attempt limit is what makes six digits defensible, so it is a correctness boundary
        // rather than a nicety.
        val keys = SessionKeys.of(secret)
        val transcript = transcript()
        val codes = listOf("111111", "222222", "333333").iterator()
        val gate = PairingGate { codes.next() }
        assertEquals("111111", gate.code)
        val bad = keys.pairProof("999999", transcript)
        assertEquals(2, assertIs<PairResult.Wrong>(gate.verifyCode(keys, transcript, bad)).attemptsLeft)
        assertEquals(1, assertIs<PairResult.Wrong>(gate.verifyCode(keys, transcript, bad)).attemptsLeft)
        val third = assertIs<PairResult.Wrong>(gate.verifyCode(keys, transcript, bad))
        assertTrue(third.codeChanged)
        assertEquals(PairCode.MAX_ATTEMPTS, third.attemptsLeft)
        assertEquals("222222", gate.code)
        // The old code is worth nothing now, whatever the attacker learned about it.
        assertIs<PairResult.Wrong>(gate.verifyCode(keys, transcript, keys.pairProof("111111", transcript)))
        assertIs<PairResult.Ok>(gate.verifyCode(keys, transcript, keys.pairProof("222222", transcript)))
    }

    @Test
    fun `a proof for another transcript is refused, which is what defeats a man in the middle`() {
        // The whole reason six digits suffice. An attacker relaying the session substituted its own
        // public bundle, so its transcript differs from the one the user's code was proved against -
        // and it cannot produce a proof for a transcript it did not create.
        val keys = SessionKeys.of(secret)
        val gate = PairingGate { "424242" }
        val proofForOther = keys.pairProof("424242", transcript(bundle = "an attacker's bundle"))
        assertIs<PairResult.Wrong>(gate.verifyCode(keys, transcript(), proofForOther))
    }

    @Test
    fun `a remembered device pairs silently and with no attempt limit`() {
        val keys = SessionKeys.of(secret)
        val transcript = transcript()
        val deviceKey = PairingGate.newDeviceKey()
        val gate = PairingGate { "123456" }
        val ok = assertIs<PairResult.Ok>(
            gate.verifyDevice(keys, transcript, deviceKey, keys.deviceProof(deviceKey, transcript)),
        )
        // Nothing new to persist: the phone already holds the key it just proved.
        assertNull(ok.deviceKey)

        // A wrong device proof does not lock anything out - otherwise anyone on the LAN could deny
        // a paired phone access by sending rubbish.
        val wrongKey = PairingGate.newDeviceKey()
        assertIs<PairResult.Wrong>(
            gate.verifyDevice(keys, transcript, deviceKey, keys.deviceProof(wrongKey, transcript)),
        )
        assertEquals(PairCode.MAX_ATTEMPTS, gate.attemptsLeft)
        assertEquals("123456", gate.code)
    }

    @Test
    fun `every reply to a proof is one the sender knows how to read`() {
        // The desynchronisation this guards against: the receiver has three possible answers to a
        // PAIR_PROOF - accepted, wrong, or "I have forgotten you, here is a code" - and it must send
        // exactly one. A second message would sit in the phone's buffer and be read as the answer to
        // whatever it sent next, turning a recoverable reinstall into a dead session.
        val codec = ControlCodec()
        val replies = listOf<ControlMessage>(
            PairOk(deviceKey = ProtocolBase64.encode(ByteArray(32))),
            PairOk(deviceKey = null),
            PairFailed(attemptsLeft = 2, codeChanged = false),
            PairFailed(attemptsLeft = PairCode.MAX_ATTEMPTS, codeChanged = true),
            PairRequired(code = true, attemptsLeft = PairCode.MAX_ATTEMPTS),
        )
        for (reply in replies) {
            assertEquals(reply, codec.decode(codec.encode(reply)), "$reply did not round-trip")
        }
        // codeChanged is carried rather than inferred from a full allowance coming back, so the two
        // cases the phone renders differently are distinguishable even when the count matches.
        assertEquals(
            PairFailed(attemptsLeft = PairCode.MAX_ATTEMPTS, codeChanged = true),
            codec.decode(
                codec.encode(PairFailed(attemptsLeft = PairCode.MAX_ATTEMPTS, codeChanged = true)),
            ),
        )
    }

    @Test
    fun `a device proof is not a code proof`() {
        // Distinct HKDF labels, so a proof captured from one exchange cannot be replayed into the
        // other.
        val keys = SessionKeys.of(secret)
        val transcript = transcript()
        val deviceKey = ByteArray(32) { 3 }
        assertFalse(
            keys.deviceProof(deviceKey, transcript)
                .contentEquals(keys.pairProof(deviceKey.toString(Charsets.UTF_8), transcript)),
        )
    }

    @Test
    fun `a pair code is six digits, zero-padded`() {
        repeat(200) {
            val code = PairCode.random()
            assertEquals(PairCode.DIGITS, code.length)
            assertTrue(PairCode.isWellFormed(code), code)
        }
        assertTrue(PairCode.isWellFormed("000000"))
        assertFalse(PairCode.isWellFormed("12345"))
        assertFalse(PairCode.isWellFormed("1234567"))
        assertFalse(PairCode.isWellFormed("12345a"))
    }

    // ---- negotiation ----

    @Test
    fun `both ends build the same routes from the same handshake`() {
        val keys = SessionKeys.of(secret)
        val config = StreamConfig(
            width = 1440,
            height = 3120,
            frameRate = 30,
            bitRate = 12_000_000,
            audio = true,
            video = true,
            audioSsrc = 1_234,
            videoSsrc = 50_002,
        )
        val ready = StreamReady(udpPort = 47_505, audioSsrc = 4_321, videoSsrc = 50_009)
        val negotiation = Negotiation.of(config, ready, keys)
        assertEquals(47_505, negotiation.udpPort)
        assertEquals(2, negotiation.streams.size)
        assertTrue(negotiation.hasVideo)
        // Each end keeps its own SSRC and learns the other's; neither derives it.
        assertEquals(1_234L, assertNotNull(negotiation.audio).senderSsrc)
        assertEquals(4_321L, assertNotNull(negotiation.audio).receiverSsrc)
        assertEquals(50_002L, assertNotNull(negotiation.video).senderSsrc)
        assertEquals(50_009L, assertNotNull(negotiation.video).receiverSsrc)
        assertEquals(keys.video, assertNotNull(negotiation.video).keys)
        assertEquals(StreamConstants.AUDIO_TIMEBASE, assertNotNull(negotiation.audio).timebase)
        assertEquals(StreamConstants.VIDEO_TIMEBASE, assertNotNull(negotiation.video).timebase)

        // Audio-only, which is what a phone with no usable video encoder sends.
        val audioOnly = Negotiation.of(config.copy(video = false), ready, keys)
        assertFalse(audioOnly.hasVideo)
        assertEquals(1, audioOnly.streams.size)
    }

    // ---- transcript ----

    @Test
    fun `the transcript covers the bytes that crossed the wire, in order`() {
        assertContentEquals(transcript(), transcript())
        assertFalse(transcript().contentEquals(transcript(bundle = "other")))
        // Order matters: two ends that fed the same bodies in different orders have not agreed.
        val forward = Transcript().apply { add("a".toByteArray()); add("b".toByteArray()) }
        val backward = Transcript().apply { add("b".toByteArray()); add("a".toByteArray()) }
        assertFalse(forward.value().contentEquals(backward.value()))
    }

    private fun transcript(bundle: String = "the tv's real bundle"): ByteArray {
        val codec = ControlCodec()
        return Transcript().apply {
            add(codec.encode(Hello(senderName = "Pixel 9", senderId = "phone-1")))
            add(
                codec.encode(
                    TvIdentity(
                        receiverName = "Living Room TV",
                        receiverId = "tv-1",
                        publicBundle = ProtocolBase64.encode(bundle.toByteArray()),
                        limits = limits,
                    ),
                ),
            )
            add(codec.encode(SealedSecret(sealed = ProtocolBase64.encode(ByteArray(16)))))
        }.value()
    }
}
