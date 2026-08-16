package org.schabi.newpipe.extractor.services.youtube.sabrng.protocol

import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * Reader for YouTube's UMP envelope. UMP uses its own compact integer format, not protobuf varints.
 */
object UmpReader {

    /** Receives one UMP part at a time (used by [readStreaming]). */
    fun interface PartConsumer {
        @Throws(SabrProtocolException::class, IOException::class)
        fun accept(type: Int, payload: ByteArray)
    }

    /** Receives one UMP part and returns false when the caller has enough data. */
    fun interface StoppablePartConsumer {
        @Throws(SabrProtocolException::class, IOException::class)
        fun accept(type: Int, payload: ByteArray): Boolean
    }

    /** Receives one UMP part payload as a bounded stream. The consumer may stop at part boundary. */
    fun interface StoppablePayloadConsumer {
        @Throws(SabrProtocolException::class, IOException::class)
        fun accept(type: Int, size: Int, payload: InputStream): Boolean
    }

    /**
     * Stream the UMP envelope: read one part (type, size, payload) at a time from [in] and hand it
     * to [consumer], so the whole response body is never held in memory at once. The stream is
     * consumed but NOT closed (caller owns it).
     */
    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun readStreaming(input: InputStream, consumer: PartConsumer) {
        readStreamingUntil(input) { type, payload ->
            consumer.accept(type, payload)
            true
        }
    }

    /**
     * Like [readStreaming], but stops at a part boundary when [consumer] returns false. The caller
     * owns and closes the stream.
     */
    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun readStreamingUntil(input: InputStream, consumer: StoppablePartConsumer) {
        readPayloadsUntil(input) { type, size, payload ->
            consumer.accept(type, readExactly(payload, size))
        }
    }

    /**
     * Stream the UMP envelope while exposing each payload as a bounded stream. This lets callers
     * consume large MEDIA parts without allocating one byte[] for the whole part.
     */
    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun readPayloadsUntil(input: InputStream, consumer: StoppablePayloadConsumer) {
        while (true) {
            throwIfInterrupted()
            val first = input.read()
            if (first < 0) {
                return // clean EOF at a part boundary -> done
            }
            val type = readUmpInt(input, first)
            val size = readUmpInt(input, readByteOrThrow(input))
            if (type < 0 || size < 0) {
                throw SabrProtocolException("Invalid UMP part header")
            }
            val payload = BoundedInputStream(input, size)
            val keepGoing = consumer.accept(type, size, payload)
            payload.drain()
            if (!keepGoing) {
                return
            }
        }
    }

    // UMP compact int, given the already-read first byte. Mirrors Cursor.readUmpInt.
    @Throws(SabrProtocolException::class, IOException::class)
    private fun readUmpInt(input: InputStream, first: Int): Int {
        if (first < 0) {
            throw EOFException("Unexpected EOF in UMP integer")
        }
        if (first < 128) {
            return first
        }
        if (first < 192) {
            return (first and 0x3f) + 64 * readByteOrThrow(input)
        }
        if (first < 224) {
            return (first and 0x1f) + 32 * (readByteOrThrow(input) + 256 * readByteOrThrow(input))
        }
        if (first < 240) {
            return (first and 0x0f) + 16 * (readByteOrThrow(input) +
                256 * (readByteOrThrow(input) + 256 * readByteOrThrow(input)))
        }
        return readByteOrThrow(input) + 256 * (readByteOrThrow(input) +
            256 * (readByteOrThrow(input) + 256 * readByteOrThrow(input)))
    }

    @Throws(SabrProtocolException::class, IOException::class)
    private fun readByteOrThrow(input: InputStream): Int {
        throwIfInterrupted()
        val b = input.read()
        if (b < 0) {
            throw EOFException("Unexpected EOF in UMP integer")
        }
        return b
    }

    @Throws(SabrProtocolException::class, IOException::class)
    private fun readExactly(input: InputStream, length: Int): ByteArray {
        if (length < 0) {
            throw SabrProtocolException("Invalid UMP part length")
        }
        throwIfInterrupted()
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            throwIfInterrupted()
            val read = input.read(result, offset, length - offset)
            if (read < 0) {
                throw EOFException("Unexpected EOF while reading UMP part data")
            }
            offset += read
        }
        return result
    }

    @Throws(IOException::class)
    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Interrupted while reading UMP stream")
        }
    }

    @JvmStatic
    @Throws(SabrProtocolException::class)
    fun readAll(data: ByteArray): List<UmpPart> {
        val cursor = Cursor(data)
        val parts = ArrayList<UmpPart>()
        while (!cursor.isDone()) {
            val type = cursor.readUmpInt()
            val size = cursor.readUmpInt()
            if (type < 0 || size < 0) {
                throw SabrProtocolException("Invalid UMP part header")
            }
            parts.add(UmpPart(type, size, cursor.readBytes(size)))
        }
        return parts
    }

    class UmpPart(private val type: Int, private val size: Int, private val data: ByteArray) {
        fun getType(): Int = type
        fun getSize(): Int = size
        fun getData(): ByteArray = data.clone()
        fun getRawData(): ByteArray = data
    }

    private class BoundedInputStream(
        private val source: InputStream,
        size: Int
    ) : InputStream() {
        private var remaining: Int = size

        @Throws(IOException::class)
        override fun read(): Int {
            throwIfInterrupted()
            if (remaining <= 0) {
                return -1
            }
            val value = source.read()
            if (value < 0) {
                throw EOFException("Unexpected EOF while reading UMP part data")
            }
            remaining--
            return value
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            throwIfInterrupted()
            if (remaining <= 0) {
                return -1
            }
            val read = source.read(buffer, offset, Math.min(length, remaining))
            if (read < 0) {
                throw EOFException("Unexpected EOF while reading UMP part data")
            }
            remaining -= read
            return read
        }

        @Throws(IOException::class)
        fun drain() {
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                read(buffer, 0, Math.min(buffer.size, remaining))
            }
        }
    }

    private class Cursor(private val data: ByteArray) {
        private var offset = 0

        fun isDone(): Boolean = offset >= data.size

        @Throws(SabrProtocolException::class)
        fun readUmpInt(): Int {
            val first = readUnsignedByte()
            if (first < 128) {
                return first
            }
            if (first < 192) {
                return (first and 0x3f) + 64 * readUnsignedByte()
            }
            if (first < 224) {
                return (first and 0x1f) + 32 * (readUnsignedByte() + 256 * readUnsignedByte())
            }
            if (first < 240) {
                return (first and 0x0f) + 16 * (readUnsignedByte() +
                    256 * (readUnsignedByte() + 256 * readUnsignedByte()))
            }
            return readUnsignedByte() + 256 * (readUnsignedByte() +
                256 * (readUnsignedByte() + 256 * readUnsignedByte()))
        }

        @Throws(SabrProtocolException::class)
        fun readBytes(length: Int): ByteArray {
            if (length < 0 || offset + length > data.size) {
                throw SabrProtocolException("Unexpected EOF while reading UMP part data")
            }
            val result = ByteArray(length)
            System.arraycopy(data, offset, result, 0, length)
            offset += length
            return result
        }

        @Throws(SabrProtocolException::class)
        private fun readUnsignedByte(): Int {
            if (offset >= data.size) {
                throw SabrProtocolException("Unexpected EOF in UMP integer")
            }
            return data[offset++].toInt() and 0xff
        }
    }
}
