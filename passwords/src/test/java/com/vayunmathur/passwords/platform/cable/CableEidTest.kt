package com.vayunmathur.passwords.platform.cable

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Tests for the caBLE EID construction + encryption ([CableEid]). */
class CableEidTest {

    @Test fun plaintextLayout() {
        val nonce = ByteArray(10) { (it + 1).toByte() }
        val routing = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val eid = CableEid.buildPlaintext(nonce, routing, domainId = 0x0102)

        assertEquals(CableEid.EID_SIZE, eid.size)
        assertEquals(0, eid[0].toInt())
        assertContentEquals(nonce, eid.copyOfRange(1, 11))
        assertContentEquals(routing, eid.copyOfRange(11, 14))
        assertEquals(0x02.toByte(), eid[14]) // low byte of domain, little-endian
        assertEquals(0x01.toByte(), eid[15]) // high byte
    }

    @Test fun encryptIsCorrectSizeAndDecryptable() {
        val eidKey = ByteArray(CableKeys.EID_KEY_SIZE) { it.toByte() }
        val plaintext = CableEid.buildPlaintext(
            nonce = ByteArray(10) { (it * 3).toByte() },
            routingId = byteArrayOf(1, 2, 3),
            domainId = 0,
        )
        val advert = CableEid.encrypt(plaintext, eidKey)
        assertEquals(CableEid.ADVERT_SIZE, advert.size)

        val ciphertext = advert.copyOfRange(0, CableEid.EID_SIZE)
        val tag = advert.copyOfRange(CableEid.EID_SIZE, CableEid.ADVERT_SIZE)

        // AES-256 single block decrypts back to the plaintext EID.
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(eidKey.copyOfRange(0, 32), "AES"))
        assertContentEquals(plaintext, cipher.doFinal(ciphertext))

        // HMAC tag verifies.
        val expectedTag = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(eidKey.copyOfRange(32, 64), "HmacSHA256"))
            doFinal(ciphertext)
        }.copyOfRange(0, CableEid.TAG_SIZE)
        assertContentEquals(expectedTag, tag)
    }

    @Test fun uuid16Expansion() {
        assertEquals(
            "0000fde2-0000-1000-8000-00805f9b34fb",
            CableAdvertiser.uuid16(0xFDE2).toString(),
        )
    }
}
