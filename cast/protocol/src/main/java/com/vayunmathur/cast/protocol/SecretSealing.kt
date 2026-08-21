package com.vayunmathur.cast.protocol

import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.e2ee.PqcIdentity

/**
 * Getting the session secret to the TV, and nothing else.
 *
 * Isolated in its own file because it is the only thing in `:cast:protocol` that reaches native
 * code: `Pqc` is backed by a Rust `.so` (fips203 ML-KEM-768), so anything that touches it cannot run
 * in a host unit test. Every rule that *can* be tested on the JVM - the key schedule, the pairing
 * proofs, the framing, the whole media round trip - is therefore kept out of here.
 *
 * `Pqc.encryptTo` is used exactly as written, which is the point: it already does ML-KEM
 * encapsulation followed by AES-256-GCM under the encapsulated secret, so there is no raw KEM
 * plumbing to get wrong here. The TV's half is `PqcIdentity.decrypt`.
 */
object SecretSealing {

    /** Seal [secret] to a TV's public bundle. Only that TV's ML-KEM private key can open it. */
    fun seal(publicBundle: ByteArray, secret: ByteArray): ByteArray =
        Pqc.encryptTo(publicBundle, secret)

    /**
     * Open a sealed secret with this TV's identity, or null when it was not sealed to us.
     *
     * Null rather than an exception because a phone that sealed to a stale bundle - one from before
     * a factory reset - is a routine outcome, and the answer is to close the session and let it
     * re-read the identity.
     */
    fun open(identity: PqcIdentity, sealed: ByteArray): ByteArray? =
        runCatching { identity.decrypt(sealed) }
            .getOrNull()
            ?.takeIf { it.size == SessionKeys.SECRET_BYTES }
}
