package com.vayunmathur.auto.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FragmenterTest {

    private val max = FrameHeader.DEFAULT_MAX_FRAME_SIZE
    private val singleMax = max - FrameHeader.SHORT_HEADER_SIZE

    @Test
    fun `a message that fits is one frame flagged first and last`() {
        val fragments = Fragmenter.split(payloadSize = 10)
        assertEquals(1, fragments.size)
        assertEquals(Fragment(0, 10, isFirst = true, isLast = true), fragments.single())
        assertEquals(FrameFlags.FIRST or FrameFlags.LAST, fragments.single().positionFlags)
    }

    @Test
    fun `an empty message is still one frame`() {
        assertEquals(1, Fragmenter.split(payloadSize = 0).size)
    }

    @Test
    fun `the largest single-frame payload does not fragment`() {
        assertEquals(1, Fragmenter.split(singleMax).size)
        assertEquals(2, Fragmenter.split(singleMax + 1).size)
    }

    @Test
    fun `the first fragment is shorter because it carries the total length`() {
        val fragments = Fragmenter.split(50_000)
        assertEquals(max - FrameHeader.LONG_HEADER_SIZE, fragments.first().length)
        assertEquals(singleMax, fragments[1].length)
    }

    @Test
    fun `fragments are contiguous and cover the payload exactly`() {
        for (size in listOf(singleMax + 1, 20_000, 50_000, 1_000_000)) {
            val fragments = Fragmenter.split(size)
            var expectedOffset = 0
            for (fragment in fragments) {
                assertEquals(expectedOffset, fragment.offset, "gap at size $size")
                expectedOffset += fragment.length
            }
            assertEquals(size, expectedOffset, "coverage at size $size")
        }
    }

    @Test
    fun `exactly one first and one last across a fragmented message`() {
        val fragments = Fragmenter.split(50_000)
        assertTrue(fragments.size > 1)
        assertEquals(1, fragments.count { it.isFirst })
        assertEquals(1, fragments.count { it.isLast })
        assertTrue(fragments.first().isFirst)
        assertTrue(fragments.last().isLast)
        // A fragmented message never has a frame that is both, which is what selects the
        // long header on the first frame.
        assertTrue(fragments.none { it.isFirst && it.isLast })
    }

    @Test
    fun `no fragment exceeds what its own header leaves room for`() {
        for (fragment in Fragmenter.split(50_000)) {
            val headerSize = if (fragment.isFirst && !fragment.isLast) {
                FrameHeader.LONG_HEADER_SIZE
            } else {
                FrameHeader.SHORT_HEADER_SIZE
            }
            assertTrue(fragment.length + headerSize <= max, "fragment $fragment overflows")
        }
    }

    @Test
    fun `a payload landing exactly on a boundary does not emit an empty final fragment`() {
        // First fragment takes max-8, then whole max-4 frames after it.
        val size = (max - FrameHeader.LONG_HEADER_SIZE) + singleMax * 2
        val fragments = Fragmenter.split(size)
        assertEquals(3, fragments.size)
        assertTrue(fragments.none { it.length == 0 })
        assertTrue(fragments.last().isLast)
    }

    @Test
    fun `control messages refuse to fragment`() {
        Fragmenter.split(singleMax, isControl = true)
        assertFailsWith<IllegalArgumentException> {
            Fragmenter.split(singleMax + 1, isControl = true)
        }
    }
}
