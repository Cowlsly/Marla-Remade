package com.vayunmathur.passwords.data

/**
 * The slice of passkey persistence the WebAuthn/CTAP layer needs.
 *
 * [PasswordRepository] is the only production implementation. It exists as an interface because
 * the repository is a singleton wrapping an encrypted Room database, which cannot be constructed
 * in a JVM unit test — the authenticator code is pure logic and should be testable without one.
 */
interface PasskeyStore {
    suspend fun getPasskeysByRpId(rpId: String): List<Passkey>
    suspend fun getPasskeyByCredentialId(credentialId: String): Passkey?
    suspend fun upsertPasskey(passkey: Passkey): Long
}
