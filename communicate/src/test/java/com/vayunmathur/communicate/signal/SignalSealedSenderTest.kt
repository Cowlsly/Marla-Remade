package com.vayunmathur.communicate.signal

import com.vayunmathur.communicate.data.signal.SignalSealedSender
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vectors for sealed sender. The access-key derivation is deliberately not an HKDF, and the truncation
 * takes the GCM *ciphertext* rather than the tag — both are easy to get subtly wrong.
 */
class SignalSealedSenderTest {
    private val profileKey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun accessKeyIsSixteenBytesAndDeterministic() {
        val a = SignalSealedSender.deriveAccessKey(profileKey)!!
        val b = SignalSealedSender.deriveAccessKey(profileKey)!!
        assertEquals(16, a.size)
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun accessKeyIsTheCiphertextNotTheTag() {
        // AES-256-GCM over 16 zero bytes returns 32: 16 ciphertext then 16 tag. Taking the tag instead
        // would still be 16 bytes and would still look plausible, so pin the half that is used.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(profileKey, "AES"),
            GCMParameterSpec(128, ByteArray(12)),
        )
        val full = cipher.doFinal(ByteArray(16))
        assertEquals(32, full.size)

        val derived = SignalSealedSender.deriveAccessKey(profileKey)!!
        assertTrue(derived.contentEquals(full.copyOfRange(0, 16)))
        assertFalse(derived.contentEquals(full.copyOfRange(16, 32)))
    }

    @Test
    fun differentProfileKeysGiveDifferentAccessKeys() {
        val other = ByteArray(32) { (it + 2).toByte() }
        val a = SignalSealedSender.deriveAccessKey(profileKey)!!
        val b = SignalSealedSender.deriveAccessKey(other)!!
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun rejectsProfileKeysOfTheWrongLength() {
        assertNull(SignalSealedSender.deriveAccessKey(ByteArray(0)))
        assertNull(SignalSealedSender.deriveAccessKey(ByteArray(16)))
        assertNull(SignalSealedSender.deriveAccessKey(ByteArray(31)))
        assertNull(SignalSealedSender.deriveAccessKey(ByteArray(33)))
    }

    @Test
    fun accessKeyHeaderUsesTheOfficialNameAndPaddedBase64() {
        val header = SignalSealedSender.accessKeyHeader(ByteArray(16))
        assertEquals("Unidentified-Access-Key:AAAAAAAAAAAAAAAAAAAAAA==", header)
    }

    @Test
    fun certificateResponseWithoutTheFieldIsRejected() {
        assertNull(SignalSealedSender.parseCertificate("""{}""", warn = {}))
        assertNull(SignalSealedSender.parseCertificate("""{"certificate":""}""", warn = {}))
        assertNull(SignalSealedSender.parseCertificate("not json", warn = {}))
    }

    @Test
    fun malformedCertificateBytesAreRejected() {
        // A well-formed response carrying garbage must not yield a certificate.
        assertNull(SignalSealedSender.parseCertificate("""{"certificate":"AQIDBA=="}""", warn = {}))
    }

    @Test
    fun sealedSenderMapsOntoItsOwnEnvelopeType() {
        // Envelope.UNIDENTIFIED_SENDER = 6, distinct from the inner ciphertext types.
        assertEquals(6, SignalPayload.envelopeTypeFor(SignalE2E.SEALED_SENDER_TYPE))
    }
}
