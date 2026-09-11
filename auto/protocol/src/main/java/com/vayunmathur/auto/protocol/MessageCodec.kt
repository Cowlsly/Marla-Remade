package com.vayunmathur.auto.protocol

import java.nio.ByteBuffer

/**
 * One message on a channel: a 2-byte big-endian type followed by a protobuf payload.
 *
 * This sits above [FrameHeader]. A frame carries a slice of one of these; a fragmented
 * message is reassembled before it gets here, so [payload] is always complete.
 */
class ChannelMessage(
    val channelId: Int,
    val type: Int,
    val payload: ByteArray,
) {
    override fun toString(): String =
        "ChannelMessage(channel=$channelId, type=0x${type.toString(16)}, ${payload.size} bytes)"
}

/**
 * Encodes and decodes the message-type prefix.
 *
 * The type is read as an **unsigned** 16-bit value. Gearhead does the same
 * (`(char) byteBuffer.getShort()`), and it matters: the framing-error type is `0xFFFF`,
 * which a signed read turns into `-1` and no dispatch table will match.
 */
object MessageCodec {

    const val TYPE_PREFIX_SIZE = 2

    fun encode(type: Int, payload: ByteArray): ByteArray {
        require(type in 0..0xFFFF) { "message type $type does not fit in a uint16" }
        return ByteBuffer.allocate(TYPE_PREFIX_SIZE + payload.size)
            .putShort(type.toShort())
            .put(payload)
            .array()
    }

    /** Encodes a message with no payload, e.g. a framing error. */
    fun encode(type: Int): ByteArray = encode(type, EMPTY)

    fun decode(channelId: Int, bytes: ByteArray): ChannelMessage {
        require(bytes.size >= TYPE_PREFIX_SIZE) {
            "message of ${bytes.size} bytes is too short to carry a type"
        }
        val buffer = ByteBuffer.wrap(bytes)
        val type = buffer.short.toInt() and 0xFFFF
        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)
        return ChannelMessage(channelId, type, payload)
    }

    private val EMPTY = ByteArray(0)
}
