package com.vayunmathur.cast.network

/**
 * One CastV2 frame.
 *
 * `CastMessage` from Chrome's `cast_channel.proto` is the only message on the wire; every
 * namespace's payload rides inside it as a UTF-8 JSON string. The two enum fields have a
 * single value each in practice - protocol version `CASTV2_1_0` and payload type `STRING` -
 * so they are not modelled here: [CastMessageCodec] writes them as the constants they are
 * and a frame that arrives with a binary payload decodes to an empty [payloadUtf8], which
 * is indistinguishable from a payload we cannot parse and is handled the same way.
 */
data class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String,
)

/**
 * Hand-rolled `CastMessage` protobuf codec.
 *
 * Seven scalar fields, so a codegen dependency would cost more than it saves - `:share`
 * hand-rolls its Nearby Connections protobuf for the same reason and there are no `.proto`
 * files anywhere in the repo. Field numbers are taken from Chromium's
 * `components/cast_channel/proto/cast_channel.proto` and cited per field below; getting one
 * wrong is the kind of mistake that shows up as a device silently ignoring us, which is why
 * the round trip is asserted by a unit test against golden bytes.
 */
object CastMessageCodec {

    /** `protocol_version = 1`, the only value being `CASTV2_1_0 = 0`. */
    private const val TAG_PROTOCOL_VERSION = 1
    private const val TAG_SOURCE_ID = 2
    private const val TAG_DESTINATION_ID = 3
    private const val TAG_NAMESPACE = 4

    /** `payload_type = 5`; `STRING = 0`, `BINARY = 1`. Only STRING is ever sent. */
    private const val TAG_PAYLOAD_TYPE = 5
    private const val TAG_PAYLOAD_UTF8 = 6
    private const val TAG_PAYLOAD_BINARY = 7

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    private const val PROTOCOL_VERSION_CASTV2_1_0 = 0L
    private const val PAYLOAD_TYPE_STRING = 0L

    fun encode(message: CastMessage): ByteArray {
        val out = ArrayList<Byte>(64 + message.payloadUtf8.length)
        // Both enums are `required`, so they are written even at their default value: a
        // receiver that validates presence rejects the frame otherwise.
        out.writeVarintField(TAG_PROTOCOL_VERSION, PROTOCOL_VERSION_CASTV2_1_0)
        out.writeStringField(TAG_SOURCE_ID, message.sourceId)
        out.writeStringField(TAG_DESTINATION_ID, message.destinationId)
        out.writeStringField(TAG_NAMESPACE, message.namespace)
        out.writeVarintField(TAG_PAYLOAD_TYPE, PAYLOAD_TYPE_STRING)
        out.writeStringField(TAG_PAYLOAD_UTF8, message.payloadUtf8)
        return out.toByteArray()
    }

    /** Returns null when [bytes] is not a decodable `CastMessage`. */
    fun decode(bytes: ByteArray): CastMessage? {
        var sourceId = ""
        var destinationId = ""
        var namespace = ""
        var payload = ""
        var offset = 0
        while (offset < bytes.size) {
            val tag = bytes.readVarint(offset) ?: return null
            offset = tag.next
            val fieldNumber = (tag.value ushr 3).toInt()
            when ((tag.value and 0x7L).toInt()) {
                WIRE_VARINT -> {
                    val v = bytes.readVarint(offset) ?: return null
                    offset = v.next
                }
                WIRE_LENGTH_DELIMITED -> {
                    val len = bytes.readVarint(offset) ?: return null
                    val start = len.next
                    if (len.value < 0 || len.value > (bytes.size - start).toLong()) return null
                    val end = start + len.value.toInt()
                    when (fieldNumber) {
                        TAG_SOURCE_ID -> sourceId = String(bytes, start, end - start, Charsets.UTF_8)
                        TAG_DESTINATION_ID -> destinationId = String(bytes, start, end - start, Charsets.UTF_8)
                        TAG_NAMESPACE -> namespace = String(bytes, start, end - start, Charsets.UTF_8)
                        TAG_PAYLOAD_UTF8 -> payload = String(bytes, start, end - start, Charsets.UTF_8)
                        // A binary payload is skipped rather than surfaced: no namespace this
                        // app speaks uses one, and a caller could not do anything with it.
                        TAG_PAYLOAD_BINARY -> Unit
                    }
                    offset = end
                }
                WIRE_FIXED64 -> offset += 8
                WIRE_FIXED32 -> offset += 4
                else -> return null // groups (3/4) were removed from proto3 and never appear
            }
            if (offset > bytes.size) return null
        }
        if (namespace.isEmpty()) return null
        return CastMessage(sourceId, destinationId, namespace, payload)
    }

    private fun MutableList<Byte>.writeVarint(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                add(b.toByte())
                return
            }
            add((b or 0x80).toByte())
        }
    }

    private fun MutableList<Byte>.writeVarintField(fieldNumber: Int, value: Long) {
        writeVarint((fieldNumber.toLong() shl 3) or WIRE_VARINT.toLong())
        writeVarint(value)
    }

    private fun MutableList<Byte>.writeStringField(fieldNumber: Int, value: String) {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        writeVarint((fieldNumber.toLong() shl 3) or WIRE_LENGTH_DELIMITED.toLong())
        writeVarint(utf8.size.toLong())
        utf8.forEach { add(it) }
    }

    private class Varint(val value: Long, val next: Int)

    private fun ByteArray.readVarint(from: Int): Varint? {
        var result = 0L
        var shift = 0
        var i = from
        while (i < size) {
            val b = this[i].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return Varint(result, i)
            shift += 7
            if (shift > 63) return null
        }
        return null
    }
}
