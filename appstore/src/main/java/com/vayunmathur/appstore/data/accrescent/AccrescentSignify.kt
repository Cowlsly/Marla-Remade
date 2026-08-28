package com.vayunmathur.appstore.data.accrescent

import android.util.Base64

/**
 * ed25519 verification of an OpenBSD-signify signature, replicating Accrescent's
 * `util/RepoDataVerifier.verifySignature`.
 *
 * signify wire format (what this understands):
 * - The **public key** ([AccrescentRepo.REPODATA_PUBKEY]) is a raw base64 blob of
 *   `2-byte algo id || 8-byte key id || 32-byte ed25519 public key`; the key is bytes
 *   `[10, 42)`.
 * - A **`.sig` file** is two lines: an `untrusted comment:` line and a base64 blob of
 *   `2-byte algo id || 8-byte key id || 64-byte ed25519 signature`; the signature is bytes
 *   `[10, 74)`.
 * - The signature is over the **raw bytes of the signed file** (here, `repodata.N.json`).
 *
 * This is the whole trust anchor for the Accrescent source, so it fails closed: any parsing
 * or verification problem returns `false` rather than throwing or assuming success.
 *
 * The framing above is parsed here; the raw ed25519 check itself is [SignifyNative].
 */
object AccrescentSignify {

    private const val PUBKEY_HEADER = 10
    private const val PUBKEY_END = 42
    private const val SIG_HEADER = 10
    private const val SIG_END = 74

    /**
     * @param message the exact bytes that were signed (the repodata JSON as downloaded).
     * @param sigFileContent the full text of the `.sig` file.
     * @param base64PublicKey the signify public key, as a raw base64 string (no comment line).
     * @return true only if [sigFileContent] is a valid ed25519 signature over [message] by
     *   [base64PublicKey].
     */
    fun verify(
        message: ByteArray,
        sigFileContent: String,
        base64PublicKey: String = AccrescentRepo.REPODATA_PUBKEY,
    ): Boolean {
        return try {
            val pubKeyBytes = decodeRange(base64PublicKey, PUBKEY_HEADER, PUBKEY_END) ?: return false

            // A .sig file is a comment line followed by the base64 blob. Take the last
            // non-blank, non-comment line so a stray trailing newline can't select "".
            val sigBase64 = sigFileContent
                .lineSequence()
                .map { it.trim() }
                .lastOrNull { it.isNotEmpty() && !it.startsWith("untrusted comment:") }
                ?: return false
            val sigBytes = decodeRange(sigBase64, SIG_HEADER, SIG_END) ?: return false

            SignifyNative.nativeVerify(pubKeyBytes, message, sigBytes)
        } catch (_: Exception) {
            false
        }
    }

    /** base64-decode [value] and return bytes `[start, end)`, or null if it is too short. */
    private fun decodeRange(value: String, start: Int, end: Int): ByteArray? {
        val decoded = try {
            Base64.decode(value.trim(), Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        if (decoded.size < end) return null
        return decoded.copyOfRange(start, end)
    }
}
