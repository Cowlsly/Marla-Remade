package com.vayunmathur.passwords.platform.cable

import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.domain.Cbor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * End-to-end test of the caBLE getAssertion path (Phases 0/1/8): a real P-256 passkey is stored,
 * a CTAP getAssertion is processed, and the returned assertion signature is verified against the
 * stored public key. Proves the cross-device signer matches WebAuthn assertion semantics.
 */
@OptIn(ExperimentalEncodingApi::class)
class CtapProcessorTest {

    private val urlEncoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private class FakePasskeyDao(initial: List<Passkey>) : PasskeyDao() {
        val store = initial.toMutableList()
        override fun getAllFlow(): Flow<List<Passkey>> = flowOf(store.toList())
        override suspend fun getAll(): List<Passkey> = store.toList()
        override suspend fun getByRpId(rpId: String): List<Passkey> = store.filter { it.rpId == rpId }
        override suspend fun getByCredentialId(credentialId: String): Passkey? =
            store.firstOrNull { it.credentialId == credentialId }
        override suspend fun upsertRaw(passkey: Passkey): Long {
            store.removeAll { it.id == passkey.id }
            store.add(passkey)
            return passkey.id
        }
        override suspend fun delete(passkey: Passkey): Int {
            return if (store.removeAll { it.id == passkey.id }) 1 else 0
        }
    }

    @Test fun getAssertionProducesVerifiableSignature() = runBlocking {
        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val credIdBytes = ByteArray(16) { it.toByte() }
        val userIdBytes = byteArrayOf(9, 8, 7, 6)
        val passkey = Passkey(
            id = 1,
            rpId = "example.com",
            credentialId = urlEncoder.encode(credIdBytes),
            userId = urlEncoder.encode(userIdBytes),
            privateKeyBytes = kp.private.encoded,
            signCount = 5,
        )
        val dao = FakePasskeyDao(listOf(passkey))
        val processor = CtapProcessor(dao, userVerified = true)

        val clientDataHash = MessageDigest.getInstance("SHA-256").digest("hello".toByteArray())
        val request = Cbor.encode(linkedMapOf<Any, Any>(
            1L to "example.com",
            2L to clientDataHash,
            5L to linkedMapOf<Any, Any>("up" to true, "uv" to true),
        ))
        val response = processor.process(byteArrayOf(Ctap.CMD_GET_ASSERTION.toByte()) + request)

        assertEquals(Ctap.OK.toByte(), response[0])
        val map = CborReader.decode(response.copyOfRange(1, response.size)) as Map<*, *>

        val cred = map[1L] as Map<*, *>
        assertContentEquals(credIdBytes, cred["id"] as ByteArray)
        val authData = map[2L] as ByteArray
        val signature = map[3L] as ByteArray
        val user = map[4L] as Map<*, *>
        assertContentEquals(userIdBytes, user["id"] as ByteArray)

        // The signature must verify over authData || clientDataHash against the stored public key.
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(kp.public)
            update(authData)
            update(clientDataHash)
        }
        assertTrue(verifier.verify(signature), "assertion signature must verify")

        // signCount was bumped and persisted.
        assertEquals(6, dao.getByCredentialId(passkey.credentialId)!!.signCount)
    }

    @Test fun getAssertionNoCredentialReturnsError() = runBlocking {
        val dao = FakePasskeyDao(emptyList())
        val processor = CtapProcessor(dao, userVerified = true)
        val request = Cbor.encode(linkedMapOf<Any, Any>(1L to "nobody.example", 2L to ByteArray(32)))
        val response = processor.process(byteArrayOf(Ctap.CMD_GET_ASSERTION.toByte()) + request)
        assertEquals(Ctap.ERR_NO_CREDENTIALS.toByte(), response[0])
    }

    @Test fun getInfoReturnsZeroAaguidAndTransports() = runBlocking {
        val dao = FakePasskeyDao(emptyList())
        val processor = CtapProcessor(dao, userVerified = false)
        val response = processor.process(byteArrayOf(Ctap.CMD_GET_INFO.toByte()))
        assertEquals(Ctap.OK.toByte(), response[0])
        val map = CborReader.decode(response.copyOfRange(1, response.size)) as Map<*, *>
        assertContentEquals(ByteArray(16), map[3L] as ByteArray)          // all-zero AAGUID
        assertEquals(listOf("cable", "hybrid", "internal"), map[9L])    // transports at key 9
        val options = map[4L] as Map<*, *>
        assertEquals(true, options["uv"])
    }
}
