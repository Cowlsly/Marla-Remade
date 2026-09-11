package com.vayunmathur.auto.protocol

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FrameHeaderTest {

    @Test
    fun `unfragmented message uses the short header`() {
        val header = FrameHeader(
            channelId = 2,
            flags = FrameFlags.FIRST or FrameFlags.LAST or FrameFlags.ENCRYPTED,
            payloadLength = 100,
        )
        assertEquals(FrameHeader.SHORT_HEADER_SIZE, header.size)

        val buffer = ByteBuffer.allocate(header.size)
        header.writeTo(buffer)
        assertContentEquals(byteArrayOf(2, 0x0B, 0, 100), buffer.array())
    }

    @Test
    fun `first fragment of a fragmented message carries the total length`() {
        val header = FrameHeader(
            channelId = 5,
            flags = FrameFlags.FIRST,
            payloadLength = 16120,
            totalLength = 40000,
        )
        assertEquals(FrameHeader.LONG_HEADER_SIZE, header.size)

        val buffer = ByteBuffer.allocate(header.size)
        header.writeTo(buffer)
        buffer.flip()
        assertEquals(header, FrameHeader.readFrom(buffer))
    }

    @Test
    fun `payload length is unsigned`() {
        // 40000 exceeds Short.MAX_VALUE; a signed read would come back negative.
        val header = FrameHeader(0, FrameFlags.FIRST or FrameFlags.LAST, 40000)
        val buffer = ByteBuffer.allocate(header.size)
        header.writeTo(buffer)
        buffer.flip()
        assertEquals(40000, FrameHeader.readFrom(buffer).payloadLength)
    }

    @Test
    fun `channel id is unsigned`() {
        val header = FrameHeader(200, FrameFlags.FIRST or FrameFlags.LAST, 1)
        val buffer = ByteBuffer.allocate(header.size)
        header.writeTo(buffer)
        buffer.flip()
        assertEquals(200, FrameHeader.readFrom(buffer).channelId)
    }

    @Test
    fun `middle and last fragments use the short header and carry no total`() {
        for (flags in listOf(0, FrameFlags.LAST)) {
            val header = FrameHeader(3, flags, 8)
            assertEquals(FrameHeader.SHORT_HEADER_SIZE, header.size)
            assertNull(header.totalLength)
        }
    }

    @Test
    fun `total length is rejected when the flags do not call for one`() {
        assertFailsWith<IllegalArgumentException> {
            FrameHeader(1, FrameFlags.FIRST or FrameFlags.LAST, 4, totalLength = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            FrameHeader(1, FrameFlags.FIRST, 4, totalLength = null)
        }
    }

    @Test
    fun `flag accessors read the right bits`() {
        val header = FrameHeader(
            channelId = 0,
            flags = FrameFlags.LAST or FrameFlags.CONTROL,
            payloadLength = 0,
        )
        assertEquals(false, header.isFirst)
        assertEquals(true, header.isLast)
        assertEquals(true, header.isControl)
        assertEquals(false, header.isEncrypted)
    }
}
