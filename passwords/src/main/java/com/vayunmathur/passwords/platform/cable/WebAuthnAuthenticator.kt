package com.vayunmathur.passwords.platform.cable

import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.platform.PasskeyUtils
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Transport-neutral WebAuthn assertion signer, shared by the Credential Manager
 * ([com.vayunmathur.passwords.ui.PasskeyAuthActivity]) and the caBLE hybrid-transport
 * authenticator. Operates on a raw `clientDataHash` (CTAP style) rather than a
 * `clientDataJSON`, so both transports funnel through one signing implementation.
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
     * Builds authenticator data, signs `authenticatorData || clientDataHash` with the passkey's
     * P-256 private key using ES256 (SHA256withECDSA), bumps and persists the signature counter,
     * and returns the pieces needed to assemble a WebAuthn/CTAP assertion response.
     */
    suspend fun signAssertion(
        passkey: Passkey,
        clientDataHash: ByteArray,
        repository: PasswordRepository,
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

        repository.upsertPasskey(
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
}
