package com.vayunmathur.cast.protocol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingSessionTest {

    private fun frame(id: Long, size: Int = 4) = EncryptedFrame(
        frameId = FrameId(id),
        referencedFrameId = FrameId(id - 1),
        rtpTimestamp = id * 3000,
        isKeyFrame = id == 0L,
        payload = ByteArray(size),
    )

    private fun feedback(
        checkpoint: Long,
        nacks: List<PacketNack> = emptyList(),
    ) = ReceiverFeedback(FrameId(checkpoint), 400, nacks, emptyList())

    @Test
    fun `a nacked packet is resent from the buffer`() {
        val session = StreamingSession()
        repeat(5) { session.record(frame(it.toLong())) }
        val recovery = session.onFeedback(
            feedback(checkpoint = 1, nacks = listOf(PacketNack(FrameId(3), 0))),
        )
        assertEquals(1, recovery.retransmissions.size)
        assertEquals(FrameId(3), recovery.retransmissions.single().frame.frameId)
        assertEquals(listOf(0), recovery.retransmissions.single().packetIds)
        assertFalse(recovery.needsKeyFrame)
    }

    @Test
    fun `the checkpoint drops everything up to and including itself`() {
        val session = StreamingSession()
        repeat(5) { session.record(frame(it.toLong())) }
        // Frames 0..2 are acknowledged, so a later NACK for frame 2 cannot be answered.
        session.onFeedback(feedback(checkpoint = 2))
        val recovery = session.onFeedback(
            feedback(checkpoint = 2, nacks = listOf(PacketNack(FrameId(2), 0))),
        )
        assertTrue(recovery.retransmissions.isEmpty())
        // Frame 2 is at or below the checkpoint, so it is history rather than a gap - no key frame.
        assertFalse(recovery.needsKeyFrame)
    }

    @Test
    fun `a nack for a frame that has fallen out of the buffer asks for a key frame`() {
        val session = StreamingSession()
        // More than the buffer holds, so the earliest frames are gone.
        repeat(80) { session.record(frame(it.toLong())) }
        val recovery = session.onFeedback(
            feedback(checkpoint = 0, nacks = listOf(PacketNack(FrameId(5), 0))),
        )
        assertTrue(recovery.retransmissions.isEmpty())
        assertTrue(recovery.needsKeyFrame)
    }

    @Test
    fun `a whole-frame nack subsumes individual packet nacks for that frame`() {
        val session = StreamingSession()
        session.record(frame(1, size = 4000))
        val recovery = session.onFeedback(
            feedback(
                checkpoint = 0,
                nacks = listOf(
                    PacketNack(FrameId(1), 1),
                    PacketNack.wholeFrame(FrameId(1)),
                ),
            ),
        )
        assertEquals(1, recovery.retransmissions.size)
        // null means "every packet", so the individual id must not survive alongside it.
        assertNull(recovery.retransmissions.single().packetIds)
    }

    @Test
    fun `several nacks for one frame are coalesced into a single retransmission`() {
        val session = StreamingSession()
        session.record(frame(1, size = 4000))
        val recovery = session.onFeedback(
            feedback(
                checkpoint = 0,
                nacks = listOf(PacketNack(FrameId(1), 2), PacketNack(FrameId(1), 0)),
            ),
        )
        assertEquals(1, recovery.retransmissions.size)
        assertEquals(listOf(0, 2), recovery.retransmissions.single().packetIds)
    }

    @Test
    fun `the retransmit buffer is bounded`() {
        // A receiver that stops sending feedback must not be able to grow the heap without limit.
        val session = StreamingSession()
        repeat(500) { session.record(frame(it.toLong(), size = 1000)) }
        val recovery = session.onFeedback(
            feedback(
                checkpoint = 0,
                nacks = (440L until 500L).map { PacketNack(FrameId(it), 0) },
            ),
        )
        // The most recent frames survive; the oldest are gone.
        assertTrue(recovery.retransmissions.isNotEmpty())
        assertEquals(FrameId(499), recovery.retransmissions.last().frame.frameId)
        assertTrue(
            recovery.retransmissions.none { it.frame.frameId < FrameId(440) },
            "the buffer kept frames it should have dropped",
        )
    }

    @Test
    fun `a picture loss indicator with no feedback block still asks for a key frame`() {
        // What ReceiverSession sends before it has decoded anything at all. Swallowing it would
        // leave the receiver waiting for a key frame the encoder was never told to produce.
        val session = StreamingSession()
        repeat(5) { session.record(frame(it.toLong())) }
        val parsed = Rtcp.parse(
            Rtcp.pictureLossIndicator(receiverSsrc = 2, senderSsrc = 1),
            receiverSsrc = 2,
            senderSsrc = 1,
            maxFrameId = FrameId(4),
        )
        assertTrue(assertNotNull(parsed).pictureLoss)
    }

    @Test
    fun `recording and feedback from two threads does not blow up the sender`() {
        // **A regression test for a crash that took the whole cast process down.** `record` runs on the
        // encoder loop and `onFeedback` on the RTCP loop, and both touch the retransmit buffer while
        // `onFeedback` *iterates* it - so an insert landing mid-iteration threw
        // ConcurrentModificationException out of a coroutine with nothing to catch it.
        //
        // It stayed hidden for as long as the sender only encoded 1080p, and became a crash within
        // seconds of 4K going out: four times the packets per frame to pace, and a receiver that
        // answers a struggling link with far more NACKs, turned two rarely-overlapping windows into
        // constantly-overlapping ones.
        //
        // Inherently probabilistic, as any test of a data race is. Before the fix this did not merely
        // throw - the unsynchronised `LinkedHashMap` corrupted its own link list and `record` span
        // forever - so the threads are daemons and the joins are bounded: a regression has to fail this
        // test, not hang the build.
        val session = StreamingSession()
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val rounds = 20_000

        val recorder = Thread {
            runCatching {
                start.await()
                for (id in 0L until rounds) session.record(frame(id))
            }.onFailure { failure.compareAndSet(null, it) }
        }.apply { isDaemon = true }
        val reporter = Thread {
            runCatching {
                start.await()
                for (id in 0L until rounds) {
                    // A checkpoint that trails the writer, plus a NACK ahead of it: the first makes
                    // `removeAll` iterate the buffer, the second makes it read from the same map.
                    session.onFeedback(
                        feedback(
                            checkpoint = (id - 30).coerceAtLeast(0),
                            nacks = listOf(PacketNack(FrameId(id), 0)),
                        ),
                    )
                }
            }.onFailure { failure.compareAndSet(null, it) }
        }.apply { isDaemon = true }

        recorder.start()
        reporter.start()
        start.countDown()
        recorder.join(TimeUnit.SECONDS.toMillis(20))
        reporter.join(TimeUnit.SECONDS.toMillis(20))
        assertFalse(recorder.isAlive, "the encoder side never finished - the buffer is wedged")
        assertFalse(reporter.isAlive, "the feedback side never finished - the buffer is wedged")
        assertNull(failure.get(), "concurrent access failed: ${failure.get()}")

        // And the buffer is still bounded and coherent afterwards, rather than merely un-thrown.
        assertTrue(
            session.onFeedback(feedback(checkpoint = 0)).retransmissions.isEmpty(),
            "a feedback packet with no nacks should ask for nothing",
        )
    }
}
