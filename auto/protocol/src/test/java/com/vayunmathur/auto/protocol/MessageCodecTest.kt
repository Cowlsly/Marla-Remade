package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.ChannelOpenRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessageCodecTest {

    @Test
    fun `the type is a big-endian prefix ahead of the payload`() {
        val encoded = MessageCodec.encode(
            GalMessage.Control.CHANNEL_OPEN_REQUEST,
            byteArrayOf(0x0A, 0x0B),
        )
        assertContentEquals(byteArrayOf(0x00, 0x07, 0x0A, 0x0B), encoded)
    }

    @Test
    fun `the type is unsigned so the framing error round-trips`() {
        // 0xFFFF read as a signed short is -1 and matches nothing.
        val encoded = MessageCodec.encode(GalMessage.Control.FRAMING_ERROR)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), encoded)

        val decoded = MessageCodec.decode(channelId = 0, bytes = encoded)
        assertEquals(GalMessage.Control.FRAMING_ERROR, decoded.type)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `0x8000-based service types survive the round trip`() {
        for (type in listOf(
            GalMessage.Media.SETUP_REQUEST,
            GalMessage.Media.ACK,
            GalMessage.Video.FOCUS_INDICATION,
            GalMessage.Sensor.SENSOR_BATCH,
        )) {
            val decoded = MessageCodec.decode(2, MessageCodec.encode(type, byteArrayOf(1)))
            assertEquals(type, decoded.type, "type 0x${type.toString(16)} did not round-trip")
        }
    }

    @Test
    fun `a real protobuf survives the round trip intact`() {
        val request = ChannelOpenRequest.newBuilder()
            .setPriority(-128)
            .setServiceId(GalService.VIDEO_SINK.id)
            .build()

        val decoded = MessageCodec.decode(
            channelId = 0,
            bytes = MessageCodec.encode(
                GalMessage.Control.CHANNEL_OPEN_REQUEST,
                request.toByteArray(),
            ),
        )

        assertEquals(GalMessage.Control.CHANNEL_OPEN_REQUEST, decoded.type)
        val parsed = ChannelOpenRequest.parseFrom(decoded.payload)
        assertEquals(-128, parsed.priority)
        assertEquals(GalService.VIDEO_SINK.id, parsed.serviceId)
    }

    @Test
    fun `a message too short to hold a type is rejected`() {
        assertFailsWith<IllegalArgumentException> { MessageCodec.decode(0, byteArrayOf(0x01)) }
    }

    @Test
    fun `a type outside uint16 is rejected`() {
        assertFailsWith<IllegalArgumentException> { MessageCodec.encode(0x10000) }
    }
}
