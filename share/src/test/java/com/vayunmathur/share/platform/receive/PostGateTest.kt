package com.vayunmathur.share.platform.receive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostGateTest {

    /** The four notification kinds, as the notifier's private enum orders them. */
    private val request = 1
    private val progress = 2
    private val done = 3
    private val failed = 4

    @Test
    fun `every kind transition posts immediately`() {
        val gate = PostGate()
        // Same clock throughout: a kind change must not be rate-limited, or the user would
        // watch a finished transfer still claim to be in progress.
        assertTrue(gate.admit(request, 0, 1_000))
        assertTrue(gate.admit(progress, 0, 1_000))
        assertTrue(gate.admit(done, 100, 1_000))
        assertTrue(gate.admit(failed, 100, 1_000))
    }

    @Test
    fun `a repeated kind and percentage does not post`() {
        val gate = PostGate()
        assertTrue(gate.admit(progress, 40, 0))
        assertFalse(gate.admit(progress, 40, 10_000))
    }

    @Test
    fun `a new percentage inside the interval does not post`() {
        val gate = PostGate()
        assertTrue(gate.admit(progress, 40, 0))
        assertFalse(gate.admit(progress, 41, PROGRESS_MIN_INTERVAL_MS - 1))
        assertTrue(gate.admit(progress, 41, PROGRESS_MIN_INTERVAL_MS))
    }

    @Test
    fun `ten thousand byte-count emissions yield at most a hundred posts`() {
        // The reason the gate exists: bytesReceived advances once per socket read, so a few
        // megabytes is thousands of emissions and notify() would become the pump's bottleneck.
        val gate = PostGate()
        val total = 3_041_557L
        var posts = 0
        repeat(10_000) { i ->
            val bytes = total * (i + 1) / 10_000
            val percent = ((bytes * 100) / total).toInt()
            // 1 ms apart: fast enough that the interval floor, not the percentage, could bind.
            if (gate.admit(progress, percent, i.toLong())) posts++
        }
        assertTrue("posted $posts times", posts <= 100)
    }

    @Test
    fun `a slow transfer still reports every percentage`() {
        val gate = PostGate()
        var posts = 0
        // One percent per second: the interval floor must not swallow any of these.
        repeat(101) { p -> if (gate.admit(progress, p, p * 1_000L)) posts++ }
        assertEquals(101, posts)
    }
}
