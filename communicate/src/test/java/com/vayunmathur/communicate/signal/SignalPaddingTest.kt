package com.vayunmathur.communicate.signal

import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.signal.SignalProtocol
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Vectors for Signal's transport padding (trailing `0x80` terminator, 80-byte blocks) and the
 * `PLAINTEXT_CONTENT` restriction. Pure, no Android — so the malformed-padding branch, which logs,
 * is deliberately not exercised here.
 */
class SignalPaddingTest {
    @Test
    fun roundTrip_preservesBodyAcrossBlockBoundaries() {
        for (size in intArrayOf(0, 1, 2, 77, 78, 79, 80, 81, 158, 159, 160, 161, 1000)) {
            val body = ByteArray(size) { (it % 251).toByte() }
            val stripped = SignalProtocol.stripMessagePadding(SignalProtocol.padMessageBody(body))
            assertTrue(body.contentEquals(stripped), "round-trip failed for size=$size")
        }
    }

    @Test
    fun paddedLength_isOneShyOfABlockMultiple() {
        // The +1/-1 in the official scheme leaves the cipher room for its own padding byte, so the
        // padded length is always blocks*80 - 1 rather than a clean multiple.
        assertEquals(79, SignalProtocol.padMessageBody(ByteArray(0)).size)
        assertEquals(79, SignalProtocol.padMessageBody(ByteArray(78)).size)
        assertEquals(159, SignalProtocol.padMessageBody(ByteArray(79)).size)
        assertEquals(159, SignalProtocol.padMessageBody(ByteArray(158)).size)
        assertEquals(239, SignalProtocol.padMessageBody(ByteArray(159)).size)
    }

    @Test
    fun terminatorFollowsBodyAndRestIsZero() {
        val body = ByteArray(10) { 0x41 }
        val padded = SignalProtocol.padMessageBody(body)
        assertEquals(0x80.toByte(), padded[10])
        assertTrue(padded.drop(11).all { it == 0x00.toByte() })
    }

    /**
     * Regression: a `Content` whose first field is a `DataMessage` serializes with leading tag byte
     * `0x0A`. The previous implementation stripped any leading byte in `0x00..0x0F`, which silently
     * ate that tag and broke every text message, reaction and delete.
     */
    @Test
    fun leadingDataMessageTagByteSurvives() {
        val body = byteArrayOf(0x0A, 0x05, 0x68, 0x65, 0x6C, 0x6C, 0x6F)
        val stripped = SignalProtocol.stripMessagePadding(SignalProtocol.padMessageBody(body))
        assertEquals(0x0A.toByte(), stripped.first())
        assertTrue(body.contentEquals(stripped))
    }

    @Test
    fun plaintextContent_acceptsOnlyADecryptionErrorMessage() {
        val decryptionErrorOnly = SignalServiceProtos.Content.newBuilder()
            .setDecryptionErrorMessage(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .build()
        assertTrue(SignalProtocol.isValidPlaintextContent(decryptionErrorOnly))
    }

    @Test
    fun plaintextContent_rejectsADataMessage() {
        // The spoofing case: an unencrypted DataMessage must never be treated as authentic.
        val dataMessage = SignalServiceProtos.Content.newBuilder()
            .setDataMessage(SignalServiceProtos.DataMessage.newBuilder().setBody("spoofed"))
            .build()
        assertFalse(SignalProtocol.isValidPlaintextContent(dataMessage))
    }

    @Test
    fun plaintextContent_rejectsADecryptionErrorSmuggledAlongsideOtherFields() {
        val smuggled = SignalServiceProtos.Content.newBuilder()
            .setDecryptionErrorMessage(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .setDataMessage(SignalServiceProtos.DataMessage.newBuilder().setBody("spoofed"))
            .build()
        assertFalse(SignalProtocol.isValidPlaintextContent(smuggled))
    }
}
