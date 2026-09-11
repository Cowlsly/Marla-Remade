package com.vayunmathur.auto.protocol

import java.nio.ByteBuffer

/**
 * Flags in byte 1 of a GAL frame header.
 *
 * A message that fits in one frame carries `FIRST or LAST`. Anything else is a fragment,
 * and only the first fragment of a fragmented message carries the 4-byte total length.
 */
object FrameFlags {
    const val FIRST = 0x01
    const val LAST = 0x02

    /** The message belongs to the control channel rather than a service channel. */
    const val CONTROL = 0x04

    /** The payload is TLS-wrapped. Set on everything after the handshake completes. */
    const val ENCRYPTED = 0x08
}

/**
 * The header of one GAL frame.
 *
 * ```
 * byte 0      channel id
 * byte 1      flags
 * bytes 2-3   payload length, big-endian uint16, excluding this header
 * bytes 4-7   total message length, big-endian int32, only when FIRST && !LAST
 * ```
 *
 * [payloadLength] is the on-wire length, so for an encrypted frame it is the size of the
 * TLS record and not of the plaintext inside it.
 */
data class FrameHeader(
    val channelId: Int,
    val flags: Int,
    val payloadLength: Int,
    val totalLength: Int? = null,
) {
    val isFirst: Boolean get() = flags and FrameFlags.FIRST != 0
    val isLast: Boolean get() = flags and FrameFlags.LAST != 0
    val isControl: Boolean get() = flags and FrameFlags.CONTROL != 0
    val isEncrypted: Boolean get() = flags and FrameFlags.ENCRYPTED != 0

    /** 8 bytes on the first fragment of a fragmented message, otherwise 4. */
    val size: Int get() = if (isFirst && !isLast) LONG_HEADER_SIZE else SHORT_HEADER_SIZE

    init {
        require(channelId in 0..0xFF) { "channel id $channelId does not fit in a byte" }
        require(flags in 0..0xFF) { "flags $flags do not fit in a byte" }
        require(payloadLength in 0..0xFFFF) {
            "payload length $payloadLength does not fit in a uint16"
        }
        require((totalLength != null) == (isFirst && !isLast)) {
            "totalLength is present exactly on the first fragment of a fragmented message"
        }
    }

    /** Writes this header at the buffer's current position. */
    fun writeTo(buffer: ByteBuffer) {
        buffer.put(channelId.toByte())
        buffer.put(flags.toByte())
        buffer.putShort(payloadLength.toShort())
        if (totalLength != null) buffer.putInt(totalLength)
    }

    companion object {
        const val SHORT_HEADER_SIZE = 4
        const val LONG_HEADER_SIZE = 8

        /**
         * The default maximum frame size, header included (`rto.a()` in gearhead).
         * Head units may negotiate something smaller.
         */
        const val DEFAULT_MAX_FRAME_SIZE = 16128

        /**
         * Reads a header from the buffer's current position, advancing it past the header.
         *
         * The caller must already have enough bytes: [SHORT_HEADER_SIZE] to learn the
         * flags, and [LONG_HEADER_SIZE] if those flags turn out to mean a long header.
         */
        fun readFrom(buffer: ByteBuffer): FrameHeader {
            require(buffer.remaining() >= SHORT_HEADER_SIZE) {
                "need at least $SHORT_HEADER_SIZE bytes for a frame header, " +
                    "have ${buffer.remaining()}"
            }
            val channelId = buffer.get().toInt() and 0xFF
            val flags = buffer.get().toInt() and 0xFF
            val payloadLength = buffer.short.toInt() and 0xFFFF
            val long = flags and FrameFlags.FIRST != 0 && flags and FrameFlags.LAST == 0
            val totalLength = if (long) {
                require(buffer.remaining() >= 4) {
                    "first fragment of a fragmented message needs a 4-byte total length"
                }
                buffer.int
            } else {
                null
            }
            return FrameHeader(channelId, flags, payloadLength, totalLength)
        }
    }
}
