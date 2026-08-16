package org.schabi.newpipe.extractor.services.youtube.sabrng.protocol

import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Minimal protobuf wire reader/writer used by the session-based YouTube SABR stack. */
object SabrProto {
    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LENGTH_DELIMITED = 2
    const val WIRE_FIXED32 = 5

    @JvmStatic
    @Throws(SabrProtocolException::class)
    fun readFields(data: ByteArray): List<Field> {
        val cursor = Cursor(data)
        val fields = ArrayList<Field>()
        while (!cursor.isDone()) {
            val tag = cursor.readVarint()
            val number = (tag shr 3).toInt()
            val wireType = (tag and 0x07L).toInt()
            if (number <= 0) {
                throw SabrProtocolException("Invalid protobuf field number: $number")
            }
            when (wireType) {
                WIRE_VARINT -> fields.add(Field.varint(number, cursor.readVarint()))
                WIRE_FIXED64 -> fields.add(Field.bytes(number, wireType, cursor.readBytes(8)))
                WIRE_LENGTH_DELIMITED -> fields.add(
                    Field.bytes(number, wireType, cursor.readBytes(cursor.readVarint().toInt()))
                )
                WIRE_FIXED32 -> fields.add(Field.bytes(number, wireType, cursor.readBytes(4)))
                else -> throw SabrProtocolException("Unsupported protobuf wire type: $wireType")
            }
        }
        return fields
    }

    @JvmStatic
    fun formatId(format: YoutubeSabrInfo.Format): ByteArray {
        val writer = Writer()
        writer.writeInt32(1, format.getItag())
        if (format.getLastModified() > 0) {
            writer.writeUInt64(2, format.getLastModified())
        }
        writer.writeStringIfNotEmpty(3, format.getXtags())
        return writer.toByteArray()
    }

    @JvmStatic
    fun asString(data: ByteArray): String = String(data, StandardCharsets.UTF_8)

    @JvmStatic
    @Throws(SabrProtocolException::class)
    fun readPackedVarints(data: ByteArray): List<Long> {
        val cursor = Cursor(data)
        val values = ArrayList<Long>()
        while (!cursor.isDone()) {
            values.add(cursor.readVarint())
        }
        return values
    }

    @JvmStatic
    @Throws(SabrProtocolException::class)
    fun asFixed32LittleEndian(data: ByteArray): Int {
        if (data.size != 4) {
            throw SabrProtocolException("Expected fixed32 length 4, got " + data.size)
        }
        return (data[0].toInt() and 0xff) or
            ((data[1].toInt() and 0xff) shl 8) or
            ((data[2].toInt() and 0xff) shl 16) or
            ((data[3].toInt() and 0xff) shl 24)
    }

    @JvmStatic
    @Throws(SabrProtocolException::class)
    fun summarizeFields(data: ByteArray): String = summarizeFields(data, IntArray(0))

    @JvmStatic
    @Throws(SabrProtocolException::class)
    fun summarizeUnknownFields(data: ByteArray, vararg knownFieldNumbers: Int): String =
        summarizeFields(data, knownFieldNumbers)

    @Throws(SabrProtocolException::class)
    private fun summarizeFields(data: ByteArray, skippedFieldNumbers: IntArray): String {
        val fields = LinkedHashMap<String, Int>()
        for (field in readFields(data)) {
            if (contains(skippedFieldNumbers, field.getNumber())) {
                continue
            }
            val key = summarizeField(field)
            val count = fields[key]
            fields[key] = if (count == null) 1 else count + 1
        }
        if (fields.isEmpty()) {
            return "none"
        }
        val builder = StringBuilder()
        for ((key, value) in fields) {
            if (builder.isNotEmpty()) {
                builder.append(", ")
            }
            builder.append(key)
            if (value > 1) {
                builder.append('x').append(value)
            }
        }
        return builder.toString()
    }

    @Throws(SabrProtocolException::class)
    private fun summarizeField(field: Field): String {
        val builder = StringBuilder()
        builder.append(field.getNumber()).append('=')
        if (field.getWireType() == WIRE_VARINT) {
            builder.append(field.getVarint())
        } else {
            builder.append("bytes(").append(field.getBytes().size).append(')')
        }
        return builder.toString()
    }

    private fun contains(values: IntArray, value: Int): Boolean {
        for (current in values) {
            if (current == value) {
                return true
            }
        }
        return false
    }

    class Field private constructor(
        private val number: Int,
        private val wireType: Int,
        private val varint: Long,
        private val bytes: ByteArray?
    ) {
        fun getNumber(): Int = number
        fun getWireType(): Int = wireType
        fun getVarint(): Long = varint

        @Throws(SabrProtocolException::class)
        fun getBytes(): ByteArray {
            return bytes ?: throw SabrProtocolException("Field $number is not length-delimited")
        }

        @Throws(SabrProtocolException::class)
        fun getString(): String = asString(getBytes())

        companion object {
            fun varint(number: Int, value: Long): Field = Field(number, WIRE_VARINT, value, null)
            fun bytes(number: Int, wireType: Int, value: ByteArray): Field =
                Field(number, wireType, 0, value)
        }
    }

    class Writer {
        private val output = ByteArrayOutputStream()

        fun writeInt32(fieldNumber: Int, value: Int) = writeUInt64(fieldNumber, value.toLong())

        fun writeUInt64(fieldNumber: Int, value: Long) {
            writeTag(fieldNumber, WIRE_VARINT)
            writeVarint(value)
        }

        fun writeBool(fieldNumber: Int, value: Boolean) =
            writeUInt64(fieldNumber, if (value) 1 else 0)

        fun writeFloat(fieldNumber: Int, value: Float) {
            writeTag(fieldNumber, WIRE_FIXED32)
            writeFixed32(java.lang.Float.floatToIntBits(value))
        }

        fun writeFixed64(fieldNumber: Int, value: Long) {
            writeTag(fieldNumber, WIRE_FIXED64)
            var shift = 0
            while (shift < java.lang.Long.SIZE) {
                output.write(((value shr shift).toInt()) and 0xff)
                shift += java.lang.Byte.SIZE
            }
        }

        fun writeBytes(fieldNumber: Int, bytes: ByteArray) {
            writeTag(fieldNumber, WIRE_LENGTH_DELIMITED)
            writeVarint(bytes.size.toLong())
            writeRaw(bytes)
        }

        fun writeStringIfNotEmpty(fieldNumber: Int, value: String?) {
            if (value != null && value.isNotEmpty()) {
                writeBytes(fieldNumber, value.toByteArray(StandardCharsets.UTF_8))
            }
        }

        fun writeMessage(fieldNumber: Int, bytes: ByteArray) = writeBytes(fieldNumber, bytes)

        fun toByteArray(): ByteArray = output.toByteArray()

        fun size(): Int = output.size()

        private fun writeTag(fieldNumber: Int, wireType: Int) =
            writeVarint((fieldNumber.toLong() shl 3) or wireType.toLong())

        private fun writeVarint(value: Long) {
            var remaining = value
            while ((remaining and 0x7fL.inv()) != 0L) {
                output.write(((remaining and 0x7f) or 0x80).toInt())
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }

        private fun writeFixed32(value: Int) {
            output.write(value and 0xff)
            output.write((value shr 8) and 0xff)
            output.write((value shr 16) and 0xff)
            output.write((value shr 24) and 0xff)
        }

        private fun writeRaw(bytes: ByteArray) = output.write(bytes)
    }

    private class Cursor(private val data: ByteArray) {
        private var offset = 0

        fun isDone(): Boolean = offset >= data.size

        @Throws(SabrProtocolException::class)
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (offset >= data.size) {
                    throw SabrProtocolException("Unexpected EOF in protobuf varint")
                }
                val current = data[offset++].toInt() and 0xff
                result = result or ((current and 0x7f).toLong() shl shift)
                if ((current and 0x80) == 0) {
                    return result
                }
                shift += 7
            }
            throw SabrProtocolException("Protobuf varint is too long")
        }

        @Throws(SabrProtocolException::class)
        fun readBytes(length: Int): ByteArray {
            if (length < 0 || offset + length > data.size) {
                throw SabrProtocolException("Unexpected EOF while reading $length bytes")
            }
            val result = ByteArray(length)
            System.arraycopy(data, offset, result, 0, length)
            offset += length
            return result
        }
    }
}
