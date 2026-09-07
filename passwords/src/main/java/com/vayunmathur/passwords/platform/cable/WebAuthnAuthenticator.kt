package com.vayunmathur.passwords.platform.cable

import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasskeyStore
import com.vayunmathur.passwords.domain.Cbor
import com.vayunmathur.passwords.platform.PasskeyUtils
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Transport-neutral WebAuthn credential creation and assertion signing, shared by the Credential
 * Manager ([com.vayunmathur.passwords.ui.PasskeyAuthActivity]) and the caBLE hybrid-transport
 * authenticator. Operates on a raw `clientDataHash` (CTAP style) rather than a `clientDataJSON`,
 * so both transports funnel through one implementation.
 */
object WebAuthnAuthenticator {

    /**
     * Result of building and signing an assertion.
     *
     * @property authenticatorData the raw authenticator data that was signed (returned to the RP).
     * @property signature DER-encoded ECDSA signature over `authenticatorData || clientDataHash`.
     * @property newSignCount the incremented signature counter that was persisted.
     */
    data class AssertionResult(
        val authenticatorData: ByteArray,
        val signature: ByteArray,
        val newSignCount: Int,
    )

    /**
     * A freshly created and persisted credential, in the pieces each transport needs.
     *
     * Deliberately stops short of an envelope: CTAP wants `{1: fmt, 2: authData, 3: attStmt}` while
     * WebAuthn wants a nested `attestationObject`, so the caller builds its own from
     * [authenticatorData].
     *
     * @property authenticatorData 37-byte header followed by the attested credential data
     *   (`aaguid || credIdLen || credentialId || coseKey`), with the AT flag set.
     * @property publicKeySpki X.509/SPKI encoding, which only the Credential Manager path reports.
     */
    data class CreatedCredential(
        val passkey: Passkey,
        val credentialId: ByteArray,
        val coseKey: ByteArray,
        val authenticatorData: ByteArray,
        val publicKeySpki: ByteArray,
    )

    /**
     * Generates a P-256 credential for [rpId], persists it, and returns the pieces needed to build
     * a registration response.
     *
     * [userId] is the raw user handle; it is stored base64url-encoded, which is the form
     * `getAssertion` decodes back. Callers holding it as a base64url string (the Credential Manager
     * request JSON does) must decode it first, or the handle round-trips wrong and the RP rejects
     * later assertions.
     *
     * Re-registering the same [rpId] and [userId] replaces the existing credential in place,
     * keeping its row id and syncId, per CTAP 2.1. Otherwise repeat registrations pile up rows that
     * an empty-allowList assertion would then choose between arbitrarily.
     */
    suspend fun createCredential(
        rpId: String,
        rpName: String,
        userId: ByteArray,
        userName: String,
        userDisplayName: String,
        aaguid: ByteArray,
        store: PasskeyStore,
        userPresent: Boolean = true,
        userVerified: Boolean = true,
    ): CreatedCredential {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val ecPublicKey = keyPair.public as ECPublicKey

        val credentialId = PasskeyUtils.generateCredentialId()
        val userIdB64 = PasskeyUtils.encodeB64Url(userId)

        // COSE_Key: kty=EC2, alg=ES256, crv=P-256, x, y.
        val coseKey = Cbor.encode(
            linkedMapOf<Any, Any>(
                1L to 2L,
                3L to -7L,
                -1L to 1L,
                -2L to toFixedBytes(ecPublicKey.w.affineX, 32),
                -3L to toFixedBytes(ecPublicKey.w.affineY, 32),
            )
        )

        val authenticatorData = PasskeyUtils.buildAuthenticatorData(
            rpId = rpId,
            userPresent = userPresent,
            userVerified = userVerified,
            attestedCredentialData = true,
            signCount = 0,
        ) + aaguid +
            byteArrayOf((credentialId.size shr 8).toByte(), credentialId.size.toByte()) +
            credentialId +
            coseKey

        val existing = store.getPasskeysByRpId(rpId).firstOrNull { it.userId == userIdB64 }
        val fresh = Passkey(
            rpId = rpId,
            rpName = rpName,
            credentialId = PasskeyUtils.encodeB64Url(credentialId),
            userId = userIdB64,
            userName = userName,
            userDisplayName = userDisplayName,
            privateKeyBytes = keyPair.private.encoded,
            creationTime = System.currentTimeMillis(),
            lastUsedTime = System.currentTimeMillis(),
            signCount = 0,
        )
        // Replacing keeps the row identity and the user's manual password link; a brand new
        // credential leaves linkedPasswordSyncId null, meaning "match to a password entry
        // automatically".
        val passkey = if (existing == null) fresh else fresh.copy(
            id = existing.id,
            syncId = existing.syncId,
            creationTime = existing.creationTime,
            linkedPasswordSyncId = existing.linkedPasswordSyncId,
        )

        val rowId = store.upsertPasskey(passkey)

        return CreatedCredential(
            passkey = if (passkey.id == 0L) passkey.copy(id = rowId) else passkey,
            credentialId = credentialId,
            coseKey = coseKey,
            authenticatorData = authenticatorData,
            publicKeySpki = keyPair.public.encoded,
        )
    }

    /**
     * Builds authenticator data, signs `authenticatorData || clientDataHash` with the passkey's
     * P-256 private key using ES256 (SHA256withECDSA), bumps and persists the signature counter,
     * and returns the pieces needed to assemble a WebAuthn/CTAP assertion response.
     */
    suspend fun signAssertion(
        passkey: Passkey,
        clientDataHash: ByteArray,
        store: PasskeyStore,
        userPresent: Boolean = true,
        userVerified: Boolean = true,
    ): AssertionResult {
        val newSignCount = passkey.signCount + 1
        val authenticatorData = PasskeyUtils.buildAuthenticatorData(
            rpId = passkey.rpId,
            userPresent = userPresent,
            userVerified = userVerified,
            signCount = newSignCount,
        )

        val signature = signWithPasskey(passkey, authenticatorData + clientDataHash)

        store.upsertPasskey(
            passkey.copy(
                signCount = newSignCount,
                lastUsedTime = System.currentTimeMillis(),
            )
        )

        return AssertionResult(authenticatorData, signature, newSignCount)
    }

    /** Signs [data] with the passkey's PKCS#8 P-256 private key using SHA256withECDSA. */
    fun signWithPasskey(passkey: Passkey, data: ByteArray): ByteArray {
        val privateKey = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(passkey.privateKeyBytes))
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
    }

    /** Left-pads or trims a coordinate to the fixed width COSE requires. */
    private fun toFixedBytes(value: BigInteger, length: Int): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == length -> bytes
            bytes.size > length -> bytes.copyOfRange(bytes.size - length, bytes.size)
            else -> ByteArray(length - bytes.size) + bytes
        }
    }
}
