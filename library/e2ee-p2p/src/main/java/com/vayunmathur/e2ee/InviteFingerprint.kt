package com.vayunmathur.e2ee

import java.security.MessageDigest

/**
 * Short fingerprint of a public bundle, small enough to ride along in an invite link.
 *
 * A [Pqc] bundle is ~3.1 kB (ML-KEM-768 + ML-DSA-65 SPKIs), so the key itself cannot
 * travel in a URL. An invite instead carries this truncated digest; the recipient still
 * fetches the full bundle from the relay and checks that it hashes to what the link said.
 * That removes the relay's ability to substitute a key of its own, since matching a given
 * digest means a second-preimage search over [LENGTH] bytes rather than a birthday
 * collision — 2^128 work, versus the 2^64 a same-length collision bound would give.
 *
 * The user id is hashed in so a fingerprint lifted from one invite cannot be presented as
 * some other user's, and the domain string keeps these digests from colliding with any
 * other use of SHA-256 over the same bundles.
 */
object InviteFingerprint {
    /** Truncated digest length. 16 bytes encodes to 22 unpadded base64url characters. */
    const val LENGTH = 16

    private const val DOMAIN = "ff-invite-v1"

    /** `SHA-256(DOMAIN || u64be(userId) || publicBundle)`, truncated to [LENGTH]. */
    fun compute(userId: Long, publicBundle: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(DOMAIN.encodeToByteArray())
        md.update(u64be(userId))
        md.update(publicBundle)
        return md.digest().copyOf(LENGTH)
    }

    private fun u64be(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (v ushr (56 - i * 8)).toByte()
        return out
    }
}
