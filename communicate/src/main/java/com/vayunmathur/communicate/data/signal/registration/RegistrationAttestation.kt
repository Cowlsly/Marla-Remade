package com.vayunmathur.communicate.data.signal.registration

/**
 * Signal registration does not use WhatsApp-style HMAC/ECDH attestation.
 *
 * Real Signal: no token-asset flow to chat.signal.org; PIN/registrationLock is handled via
 * SVR2/SVRB enclave (AccountApi.setRegistrationLock(SvrKey(masterKey.serialize()))) — live-only enclave attestation via libsignal-net (rust/attest, rust/net/enclave.rs).
 * Keep wire-correct (no attestation headers to /v1/verification/session or /v1/registration); if reglock/SVR is needed it is a live-only step.
 */
object RegistrationAttestation {
    /** No-op for Signal — do not send attestation to chat.signal.org. Returns null so callers skip the header. */
    fun encryptQueryString(queryString: String, serverPubHex: String): String? = null

    /** No-op for Signal — registration bodies are not HMAC-signed via WhatsApp attestation. */
    fun signWithAttestation(body: String, key: ByteArray): String = ""
}
