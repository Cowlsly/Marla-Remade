package com.vayunmathur.cast.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The suite the whole project rests on while there is no TV.**
 *
 * Every hardware bug that cost this project a session was the same shape: we inferred a format from
 * someone else's source, and a receiver we could not instrument silently refused the stream. Both
 * ends are ours now, so the equivalent question - "do the packetizer and the depacketizer agree?" -
 * is answerable here, in milliseconds, with no device involved.
 *
 * So the assertions are deliberately end-to-end rather than per-field: encrypt, packetize,
 * depacketize, reassemble, decrypt, and require the original bytes back. A wrong-but-symmetric
 * format would pass that, which is exactly the point - the format only has to be self-consistent
 * now, and [RtpPacketizerTest] still pins the bytes themselves.
 */
class MediaRoundTripTest {

    private val keys = StreamKeys(
        key = ByteArray(16) { (it * 7).toByte() },
        ivMask = ByteArray(16) { (it * 11 + 3).toByte() },
    )

    private val crypto = Crypto(keys.key, keys.ivMask)

    private fun packetizer(maxPacketSize: Int = MAX_RTP_PACKET_SIZE) =
        RtpPacketizer(
            StreamConstants.VIDEO_PAYLOAD_TYPE,
            SENDER_SSRC,
            maxPacketSize,
            sequenceNumberStart = 0x5f2a,
        )

    /** A frame as the sender would build it: encrypted payload, key frame referencing itself. */
    private fun frame(id: Long, size: Int, isKeyFrame: Boolean = id == 0L): EncryptedFrame {
        val plaintext = ByteArray(size) { ((it * 31 + id) and 0xff).toByte() }
        return EncryptedFrame(
            frameId = FrameId(id),
            referencedFrameId = if (isKeyFrame) FrameId(id) else FrameId(id - 1),
            rtpTimestamp = id * 3000,
            isKeyFrame = isKeyFrame,
            payload = crypto.crypt(FrameId(id), plaintext),
        )
    }

    private fun plaintextOf(id: Long, size: Int) = ByteArray(size) { ((it * 31 + id) and 0xff).toByte() }

    @Test
    fun `a single-packet frame survives the round trip byte for byte`() {
        val original = frame(id = 0, size = 40)
        val packets = packetizer().packetize(original)
        assertEquals(1, packets.size)
        val assembler = FrameAssembler()
        val assembled = assertNotNull(assembler.add(assertNotNull(RtpDepacketizer.parse(packets[0]))))
        assertEquals(original, assembled)
        assertContentEquals(plaintextOf(0, 40), crypto.crypt(assembled.frameId, assembled.payload))
    }

    @Test
    fun `a multi-packet frame reassembles in the right order`() {
        // 1080p key frames run to tens of packets; a frame that reassembles its chunks in the wrong
        // order decodes to garbage with no error anywhere, which is the failure this catches.
        val original = frame(id = 0, size = 120_000)
        val packets = packetizer().packetize(original)
        assertTrue(packets.size > 80, "expected a genuinely fragmented frame, got ${packets.size}")
        assertEquals(original, assemble(packets))
    }

    @Test
    fun `packets arriving in reverse order still reassemble`() {
        val original = frame(id = 0, size = 9_000)
        assertEquals(original, assemble(packetizer().packetize(original).reversed()))
    }

    @Test
    fun `packets arriving shuffled still reassemble`() {
        val original = frame(id = 0, size = 60_000)
        val shuffled = packetizer().packetize(original).shuffled(Random(20260821))
        assertEquals(original, assemble(shuffled))
    }

    @Test
    fun `duplicated packets are absorbed rather than corrupting the frame`() {
        // A retransmission of a packet that already arrived is the normal case, not an edge case:
        // the sender resends on a NACK it may already have answered.
        val original = frame(id = 0, size = 9_000)
        val packets = packetizer().packetize(original)
        val withDuplicates = packets + packets + packets.first()
        val assembler = FrameAssembler()
        var completed: EncryptedFrame? = null
        var completions = 0
        for (bytes in withDuplicates) {
            val frame = assembler.add(assertNotNull(RtpDepacketizer.parse(bytes)))
            if (frame != null) {
                completed = frame
                completions++
            }
        }
        assertEquals(1, completions, "a frame must only ever be emitted once")
        assertEquals(original, completed)
    }

    @Test
    fun `a lost packet leaves the frame incomplete until it is retransmitted`() {
        val original = frame(id = 0, size = 9_000)
        val packets = packetizer().packetize(original)
        val assembler = FrameAssembler()
        val dropped = packets.size / 2
        for ((index, bytes) in packets.withIndex()) {
            if (index == dropped) continue
            assertNull(
                assembler.add(assertNotNull(RtpDepacketizer.parse(bytes))),
                "the frame completed while a packet was still missing",
            )
        }
        // And the receiver can say exactly which packet it wants.
        assertEquals(
            listOf(PacketNack(FrameId(0), dropped)),
            assembler.missingPackets(after = FrameId.Leader),
        )
        val recovered = assembler.add(assertNotNull(RtpDepacketizer.parse(packets[dropped])))
        assertEquals(original, recovered)
    }

    @Test
    fun `frame ids round-trip across the eight-bit wrap`() {
        // The bug that would otherwise appear only after eight and a half seconds of mirroring: the
        // wire carries eight bits of a 64-bit counter, and an id expanded wrongly decrypts to noise
        // because the IV comes from the full value.
        val assembler = FrameAssembler()
        for (id in 0L..600L) {
            val original = frame(id = id, size = 500, isKeyFrame = id % 30 == 0L)
            val packets = packetizer().packetize(original)
            var assembled: EncryptedFrame? = null
            for (bytes in packets) {
                assembler.add(assertNotNull(RtpDepacketizer.parse(bytes)))?.let { assembled = it }
            }
            assertEquals(original, assembled, "frame $id did not survive")
            assertContentEquals(
                plaintextOf(id, 500),
                crypto.crypt(assertNotNull(assembled).frameId, assertNotNull(assembled).payload),
                "frame $id decrypted wrongly, which means its id was expanded wrongly",
            )
            assembler.discardUpTo(FrameId(id))
        }
    }

    @Test
    fun `an empty frame round-trips as an empty frame`() {
        // Opus encodes a silent period as zero bytes and the packetizer still emits one packet; a
        // receiver that dropped it would stall waiting for a frame that had in fact arrived.
        val original = frame(id = 0, size = 0)
        assertEquals(original, assemble(packetizer().packetize(original)))
    }

    @Test
    fun `a whole stream survives loss, reordering and retransmission`() {
        // The closest thing to a live session that runs without hardware: 200 frames over a lossy,
        // reordering link, with the sender answering NACKs from its retransmit buffer, and every
        // delivered frame required to be byte-identical to what was captured.
        val random = Random(4242)
        val stream = NegotiatedStream(
            kind = StreamKind.Video,
            senderSsrc = SENDER_SSRC,
            receiverSsrc = RECEIVER_SSRC,
            payloadType = StreamConstants.VIDEO_PAYLOAD_TYPE,
            timebase = StreamConstants.VIDEO_TIMEBASE,
            keys = keys,
        )
        val receiver = ReceiverSession(stream)
        val sender = StreamingSession()
        val packetizer = packetizer()
        val delivered = mutableMapOf<Long, ByteArray>()
        val inFlight = mutableListOf<ByteArray>()

        for (id in 0L until 200L) {
            val isKeyFrame = id == 0L
            val original = frame(id, size = 3_000, isKeyFrame = isKeyFrame)
            sender.record(original)
            for (packet in packetizer.packetize(original)) {
                // 10% loss, which is far worse than a real LAN and therefore the point.
                if (random.nextInt(10) == 0) continue
                inFlight += packet
            }
            // Reordering: the link delivers what it has in an arbitrary order.
            inFlight.shuffle(random)
            for (packet in inFlight) {
                for (frame in receiver.onPacket(packet)) delivered[frame.frameId.value] = frame.payload
            }
            inFlight.clear()

            // The receiver reports, and the sender answers from its buffer - the loop that makes
            // loss survivable.
            val feedback = assertNotNull(
                Rtcp.parse(receiver.feedback(), RECEIVER_SSRC, SENDER_SSRC, FrameId(id)),
            )
            for (item in sender.onFeedback(feedback).retransmissions) {
                val packets = packetizer.packetize(item.frame)
                val ids = item.packetIds ?: packets.indices.toList()
                for (packetId in ids) inFlight += packets[packetId]
            }
        }
        // Flush whatever the last round put in flight.
        for (packet in inFlight) {
            for (frame in receiver.onPacket(packet)) delivered[frame.frameId.value] = frame.payload
        }

        assertTrue(
            delivered.size > 150,
            "only ${delivered.size} of 200 frames were recovered; retransmission is not working",
        )
        for ((id, payload) in delivered) {
            assertContentEquals(plaintextOf(id, 3_000), payload, "frame $id was corrupted")
        }
        // Delivery is contiguous from the first key frame: a gap would mean a frame was released
        // before the one it references.
        val ids = delivered.keys.sorted()
        assertEquals((ids.first()..ids.last()).toList(), ids, "frames were delivered out of order")
    }

    @Test
    fun `nothing is released before a key frame arrives`() {
        // A delta frame handed to a decoder that never had its reference is a green smear, not an
        // error, so it must not be released at all.
        val stream = videoStream()
        val receiver = ReceiverSession(stream)
        val packetizer = packetizer()
        for (id in 1L..5L) {
            for (packet in packetizer.packetize(frame(id, size = 400, isKeyFrame = false))) {
                assertTrue(receiver.onPacket(packet).isEmpty())
            }
        }
        // And it says so: a PLI rides with the feedback until there is something decodable.
        val parsed = assertNotNull(
            Rtcp.parse(receiver.feedback(), RECEIVER_SSRC, SENDER_SSRC, FrameId(5)),
        )
        assertTrue(parsed.pictureLoss)

        // The key frame resynchronises, and the undecodable frames before it are dropped rather
        // than delivered late.
        val key = frame(6, size = 400, isKeyFrame = true)
        val released = packetizer.packetize(key).flatMap { receiver.onPacket(it) }
        assertEquals(listOf(FrameId(6)), released.map { it.frameId })
        assertEquals(FrameId(6), receiver.checkpoint)
    }

    @Test
    fun `a datagram for another stream is ignored rather than misparsed`() {
        // Audio and video share one socket, so most of what arrives on it is not ours.
        val receiver = ReceiverSession(videoStream())
        val other = RtpPacketizer(StreamConstants.AUDIO_PAYLOAD_TYPE, 12_345L)
        assertTrue(receiver.onPacket(other.packetize(frame(0, size = 100)).single()).isEmpty())
        assertEquals(1, receiver.packetsIgnored)
        assertEquals(0, receiver.packetsReceived)
    }

    @Test
    fun `a truncated or malformed datagram is dropped without throwing`() {
        val receiver = ReceiverSession(videoStream())
        val good = packetizer().packetize(frame(0, size = 100)).single()
        assertTrue(receiver.onPacket(ByteArray(0)).isEmpty())
        assertTrue(receiver.onPacket(good.copyOfRange(0, 10)).isEmpty())
        // Version bits other than 2.
        assertTrue(receiver.onPacket(good.copyOf().also { it[0] = 0 }).isEmpty())
        // A declared header extension, which our packetizer never emits.
        assertTrue(receiver.onPacket(good.copyOf().also { it[12] = 0b0100_0001 }).isEmpty())
        // The marker bit disagreeing with the packet ids.
        assertTrue(receiver.onPacket(good.copyOf().also { it[1] = 96 }).isEmpty())
    }

    private fun videoStream() = NegotiatedStream(
        kind = StreamKind.Video,
        senderSsrc = SENDER_SSRC,
        receiverSsrc = RECEIVER_SSRC,
        payloadType = StreamConstants.VIDEO_PAYLOAD_TYPE,
        timebase = StreamConstants.VIDEO_TIMEBASE,
        keys = keys,
    )

    private fun assemble(packets: List<ByteArray>): EncryptedFrame {
        val assembler = FrameAssembler()
        var completed: EncryptedFrame? = null
        for (bytes in packets) {
            assembler.add(assertNotNull(RtpDepacketizer.parse(bytes)))?.let { completed = it }
        }
        return assertNotNull(completed, "the frame never completed")
    }

    private companion object {
        const val SENDER_SSRC = 50_001L
        const val RECEIVER_SSRC = 50_002L
    }
}
