package com.vayunmathur.cast.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CastMessageCodecTest {

    @Test
    fun `encodes the golden bytes for a minimal CONNECT frame`() {
        // Field-by-field, from cast_channel.proto:
        //   08 00                protocol_version = CASTV2_1_0
        //   12 08 "sender-0"     source_id
        //   1a 0a "receiver-0"   destination_id
        //   22 01 "n"            namespace (one byte so the expectation stays readable)
        //   28 00                payload_type = STRING
        //   32 02 "{}"           payload_utf8
        val encoded = CastMessageCodec.encode(
            CastMessage(
                sourceId = "sender-0",
                destinationId = "receiver-0",
                namespace = "n",
                payloadUtf8 = "{}",
            ),
        )
        val expected = byteArrayOf(
            0x08, 0x00,
            0x12, 0x08, 's'.code.toByte(), 'e'.code.toByte(), 'n'.code.toByte(),
            'd'.code.toByte(), 'e'.code.toByte(), 'r'.code.toByte(), '-'.code.toByte(),
            '0'.code.toByte(),
            0x1a, 0x0a, 'r'.code.toByte(), 'e'.code.toByte(), 'c'.code.toByte(),
            'e'.code.toByte(), 'i'.code.toByte(), 'v'.code.toByte(), 'e'.code.toByte(),
            'r'.code.toByte(), '-'.code.toByte(), '0'.code.toByte(),
            0x22, 0x01, 'n'.code.toByte(),
            0x28, 0x00,
            0x32, 0x02, '{'.code.toByte(), '}'.code.toByte(),
        )
        assertEquals(expected.toList(), encoded.toList())
    }

    @Test
    fun `round trips a realistic frame`() {
        val message = CastMessage(
            sourceId = "sender-0",
            destinationId = "web-5",
            namespace = "urn:x-cast:com.google.cast.media",
            payloadUtf8 = """{"type":"LOAD","requestId":3,"media":{"contentId":"http://a/b.mp4"}}""",
        )
        assertEquals(message, CastMessageCodec.decode(CastMessageCodec.encode(message)))
    }

    @Test
    fun `round trips a payload with multi byte characters`() {
        // The length prefix is a byte count, not a character count; getting that wrong
        // truncates every non-ASCII title.
        val message = CastMessage("sender-0", "receiver-0", "n", """{"title":"Amélie 日本"}""")
        assertEquals(message, CastMessageCodec.decode(CastMessageCodec.encode(message)))
    }

    @Test
    fun `decode skips fields it does not model`() {
        // A binary payload (field 7) and a field this app has never heard of must both be
        // stepped over rather than aborting the parse.
        val base = CastMessageCodec.encode(CastMessage("sender-0", "receiver-0", "n", "{}"))
        val withExtras = base +
            byteArrayOf(0x3a, 0x03, 1, 2, 3) + // field 7, bytes: payload_binary
            byteArrayOf(0x50.toByte(), 0x2a) + // field 10, varint
            byteArrayOf(0x5d.toByte(), 0, 0, 0, 0) // field 11, fixed32
        val decoded = CastMessageCodec.decode(withExtras)
        assertEquals("n", decoded?.namespace)
        assertEquals("{}", decoded?.payloadUtf8)
    }

    @Test
    fun `decode rejects a truncated frame`() {
        val encoded = CastMessageCodec.encode(CastMessage("sender-0", "receiver-0", "n", "{}"))
        assertNull(CastMessageCodec.decode(encoded.copyOf(encoded.size - 1)))
    }

    @Test
    fun `decode rejects a frame with no namespace`() {
        // Namespace is what routes the payload, so a frame without one is unusable even
        // though protobuf itself would accept the bytes.
        assertNull(CastMessageCodec.decode(byteArrayOf(0x08, 0x00, 0x28, 0x00)))
    }
}
