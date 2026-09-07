package com.vayunmathur.passwords.platform.cable

import com.vayunmathur.passwords.domain.Cbor
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for cross-device passkey registration (`authenticatorMakeCredential`), including a
 * registration-then-sign-in round trip that proves both halves agree on how credential ids and
 * user handles are stored.
 */
@OptIn(ExperimentalEncodingApi::class)
class CtapMakeCredentialTest {

    private val urlDecoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    private val rpId = "example.com"
    private val userIdBytes = byteArrayOf(9, 8, 7, 6, 5)
    private val clientDataHash = MessageDigest.getInstance("SHA-256").digest("create".toByteArray())

    private fun request(
        algorithms: List<Long> = listOf(Ctap.ALG_ES256),
        excludeList: List<ByteArray> = emptyList(),
        options: Map<String, Boolean> = mapOf("rk" to true),
        rp: Any? = linkedMapOf<Any, Any>("id" to rpId, "name" to "Example"),
        user: Any? = linkedMapOf<Any, Any>(
            "id" to userIdBytes,
            "name" to "alice@example.com",
            "displayName" to "Alice",
        ),
        hash: Any? = clientDataHash,
    ): ByteArray {
        val map = linkedMapOf<Any, Any>()
        hash?.let { map[1L] = it }
        rp?.let { map[2L] = it }
        user?.let { map[3L] = it }
        map[4L] = algorithms.map { linkedMapOf<Any, Any>("alg" to it, "type" to "public-key") }
        if (excludeList.isNotEmpty()) {
            map[5L] = excludeList.map { linkedMapOf<Any, Any>("id" to it, "type" to "public-key") }
        }
        map[7L] = LinkedHashMap<Any, Any>(options)
        return byteArrayOf(Ctap.CMD_MAKE_CREDENTIAL.toByte()) + Cbor.encode(map)
    }

    private suspend fun makeCredential(
        store: FakePasskeyStore,
        payload: ByteArray = request(),
        userVerified: Boolean = true,
    ): ByteArray = CtapProcessor(store, userVerified).process(payload)

    /** authData = rpIdHash(32) ‖ flags(1) ‖ signCount(4) ‖ aaguid(16) ‖ credIdLen(2) ‖ credId ‖ cose */
    private class AuthData(val bytes: ByteArray) {
        val rpIdHash: ByteArray get() = bytes.copyOfRange(0, 32)
        val flags: Int get() = bytes[32].toInt() and 0xFF
        val signCount: Int get() = bytes.copyOfRange(33, 37).fold(0) { a, b -> (a shl 8) or (b.toInt() and 0xFF) }
        val aaguid: ByteArray get() = bytes.copyOfRange(37, 53)
        val credIdLen: Int get() = ((bytes[53].toInt() and 0xFF) shl 8) or (bytes[54].toInt() and 0xFF)
        val credentialId: ByteArray get() = bytes.copyOfRange(55, 55 + credIdLen)
        val coseKey: Map<*, *> get() = CborReader.decode(bytes.copyOfRange(55 + credIdLen, bytes.size)) as Map<*, *>
    }

    private fun authDataOf(response: ByteArray): AuthData {
        assertEquals(Ctap.OK.toByte(), response[0], "expected OK status")
        val map = CborReader.decode(response.copyOfRange(1, response.size)) as Map<*, *>
        return AuthData(map[2L] as ByteArray)
    }

    /** Rebuilds a verifying key from the COSE coordinates the RP would receive. */
    private fun publicKeyFrom(cose: Map<*, *>): PublicKey {
        val x = BigInteger(1, cose[-2L] as ByteArray)
        val y = BigInteger(1, cose[-3L] as ByteArray)
        val params = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    // -- Response shape ---------------------------------------------------

    /**
     * The CTAP response is a flat integer-keyed map, not a WebAuthn attestationObject. Getting this
     * wrong produces something that looks plausible and fails on every real relying party.
     */
    @Test fun responseIsCtapShapedNotAWebAuthnAttestationObject() = runBlocking {
        val response = makeCredential(FakePasskeyStore())
        assertEquals(Ctap.OK.toByte(), response[0])
        val map = CborReader.decode(response.copyOfRange(1, response.size)) as Map<*, *>

        assertEquals("none", map[1L])
        assertTrue(map[2L] is ByteArray)
        assertEquals(emptyMap<Any, Any>(), map[3L])
        assertFalse("fmt" in map.keys, "must not be a nested WebAuthn attestationObject")
        assertFalse("authData" in map.keys)
    }

    @Test fun authDataDescribesTheNewCredential() = runBlocking {
        val authData = authDataOf(makeCredential(FakePasskeyStore()))

        assertContentEquals(
            MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray()),
            authData.rpIdHash,
        )
        assertEquals(0x01, authData.flags and 0x01, "UP")
        assertEquals(0x04, authData.flags and 0x04, "UV")
        assertEquals(0x40, authData.flags and 0x40, "AT (attested credential data)")
        assertEquals(0, authData.flags and 0x80, "ED must be clear; no extensions are output")
        assertEquals(0, authData.signCount)
        assertEquals(16, authData.credIdLen)

        assertEquals(2L, authData.coseKey[1L], "kty EC2")
        assertEquals(Ctap.ALG_ES256, authData.coseKey[3L], "alg ES256")
        assertEquals(1L, authData.coseKey[-1L], "crv P-256")
        assertEquals(32, (authData.coseKey[-2L] as ByteArray).size)
        assertEquals(32, (authData.coseKey[-3L] as ByteArray).size)
    }

    /** The AAGUID we attest with has to be the one we told the browser about in getInfo. */
    @Test fun aaguidMatchesTheGetInfoResponse() = runBlocking {
        val authData = authDataOf(makeCredential(FakePasskeyStore()))
        assertContentEquals(ByteArray(16), authData.aaguid)
        assertContentEquals(CtapGetInfoResponse().aaguid, authData.aaguid)
    }

    // -- Round trip -------------------------------------------------------

    /**
     * Register then sign in with the same credential, verifying the assertion against the public
     * key the relying party would have extracted at registration. This is what proves the two
     * halves agree on the base64url storage of credential ids and user handles.
     */
    @Test fun registeredCredentialCanImmediatelySignIn() = runBlocking {
        val store = FakePasskeyStore()
        val created = authDataOf(makeCredential(store))
        val publicKey = publicKeyFrom(created.coseKey)

        val assertionHash = MessageDigest.getInstance("SHA-256").digest("get".toByteArray())
        val getAssertion = byteArrayOf(Ctap.CMD_GET_ASSERTION.toByte()) + Cbor.encode(
            linkedMapOf<Any, Any>(
                1L to rpId,
                2L to assertionHash,
                3L to listOf(
                    linkedMapOf<Any, Any>("id" to created.credentialId, "type" to "public-key")
                ),
            )
        )
        val response = CtapProcessor(store, userVerified = true).process(getAssertion)

        assertEquals(Ctap.OK.toByte(), response[0], "assertion must find the credential just made")
        val map = CborReader.decode(response.copyOfRange(1, response.size)) as Map<*, *>
        val authData = map[2L] as ByteArray
        val signature = map[3L] as ByteArray

        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(publicKey)
            update(authData)
            update(assertionHash)
        }
        assertTrue(verifier.verify(signature), "assertion must verify against the registered key")

        val user = map[4L] as Map<*, *>
        assertContentEquals(userIdBytes, user["id"] as ByteArray, "user handle must round-trip")
    }

    @Test fun credentialIdAndUserHandleAreStoredAsBase64Url() = runBlocking {
        val store = FakePasskeyStore()
        val created = authDataOf(makeCredential(store))
        val stored = store.store.single()

        assertFalse(stored.credentialId.contains('='), "no padding")
        assertFalse(stored.userId.contains('='), "no padding")
        assertContentEquals(created.credentialId, urlDecoder.decode(stored.credentialId))
        assertContentEquals(userIdBytes, urlDecoder.decode(stored.userId))
    }

    /** CTAP 2.1: re-registering the same rp + user replaces the credential rather than adding one. */
    @Test fun reRegisteringTheSameUserReplacesInPlace() = runBlocking {
        val store = FakePasskeyStore()
        makeCredential(store)
        val first = store.store.single()

        makeCredential(store)
        val second = store.store.single()

        assertEquals(first.id, second.id)
        assertEquals(first.syncId, second.syncId)
        assertTrue(first.credentialId != second.credentialId, "a new key pair was generated")
    }

    @Test fun aDifferentUserOnTheSameRpAddsACredential() = runBlocking {
        val store = FakePasskeyStore()
        makeCredential(store)
        makeCredential(store, request(user = linkedMapOf<Any, Any>(
            "id" to byteArrayOf(1, 1, 1),
            "name" to "bob@example.com",
        )))
        assertEquals(2, store.store.size)
    }

    // -- Errors -----------------------------------------------------------

    @Test fun unsupportedAlgorithmIsRejected() = runBlocking {
        // RS256 only; we implement ES256 exclusively.
        val response = makeCredential(FakePasskeyStore(), request(algorithms = listOf(-257L)))
        assertEquals(Ctap.ERR_UNSUPPORTED_ALGORITHM.toByte(), response[0])
    }

    @Test fun excludeListMatchingAStoredCredentialIsRejected() = runBlocking {
        val store = FakePasskeyStore()
        val existing = authDataOf(makeCredential(store))

        val response = makeCredential(store, request(excludeList = listOf(existing.credentialId)))
        assertEquals(Ctap.ERR_CREDENTIAL_EXCLUDED.toByte(), response[0])
    }

    /** An excluded id belonging to a different rp must not block registration here. */
    @Test fun excludeListForAnotherRelyingPartyIsIgnored() = runBlocking {
        val store = FakePasskeyStore()
        val other = authDataOf(
            makeCredential(store, request(rp = linkedMapOf<Any, Any>("id" to "other.test")))
        )

        val response = makeCredential(store, request(excludeList = listOf(other.credentialId)))
        assertEquals(Ctap.OK.toByte(), response[0])
    }

    @Test fun missingParametersAreRejected() = runBlocking {
        val store = FakePasskeyStore()
        assertEquals(Ctap.ERR_MISSING_PARAMETER.toByte(), makeCredential(store, request(rp = null))[0])
        assertEquals(Ctap.ERR_MISSING_PARAMETER.toByte(), makeCredential(store, request(user = null))[0])
        assertEquals(Ctap.ERR_MISSING_PARAMETER.toByte(), makeCredential(store, request(hash = null))[0])
        assertTrue(store.store.isEmpty(), "a rejected request must not persist anything")
    }

    /** Unlike getAssertion, makeCredential cannot waive user presence. */
    @Test fun userPresenceFalseIsRejected() = runBlocking {
        val response = makeCredential(FakePasskeyStore(), request(options = mapOf("up" to false)))
        assertEquals(Ctap.ERR_INVALID_OPTION.toByte(), response[0])
    }

    @Test fun userVerificationRequiredButNotVerifiedIsDenied() = runBlocking {
        val response = makeCredential(
            FakePasskeyStore(),
            request(options = mapOf("uv" to true)),
            userVerified = false,
        )
        assertEquals(Ctap.ERR_OPERATION_DENIED.toByte(), response[0])
    }

    @Test fun unknownCommandsStillReturnNotAllowed() = runBlocking {
        val response = CtapProcessor(FakePasskeyStore(), userVerified = true)
            .process(byteArrayOf(Ctap.CMD_GET_NEXT_ASSERTION.toByte()))
        assertEquals(Ctap.ERR_NOT_ALLOWED.toByte(), response[0])
    }

    @Test fun rpNameAndUserNameAreStored() = runBlocking {
        val store = FakePasskeyStore()
        makeCredential(store)
        val stored = store.store.single()
        assertNotNull(stored)
        assertEquals("Example", stored.rpName)
        assertEquals("alice@example.com", stored.userName)
        assertEquals("Alice", stored.userDisplayName)
        assertEquals(rpId, stored.rpId)
    }

    /** A new credential must be left free to auto-match a password entry. */
    @Test fun newCredentialHasNoPasswordLink() = runBlocking {
        val store = FakePasskeyStore()
        makeCredential(store)
        assertEquals(null, store.store.single().linkedPasswordSyncId)
    }
}
