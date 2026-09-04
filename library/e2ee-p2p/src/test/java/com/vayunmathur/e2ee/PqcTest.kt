package com.vayunmathur.e2ee

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These exercise the real native PQC (libe2ee_pqc.so), so they only run on-device
 * / when the native lib is loadable. Byte-level interop with the previously-
 * deployed Bouncy Castle encoding is covered by the Rust crate's own tests
 * (library/e2ee-p2p/src/main/rust/src/tests.rs) against captured BC vectors.
 */
class PqcTest {
    private var nativeAvailable = false

    @BeforeTest
    fun checkNative() {
        nativeAvailable = runCatching { Pqc.generateKem() }.isSuccess
        // Skipped when native lib not loadable on this host — was Assume.assumeTrue before.
        if (!nativeAvailable) return
    }

    @Test
    fun ml_kem_encrypt_decrypt_roundtrip() {
        if (!nativeAvailable) return
        val (kemPub, kemPriv) = Pqc.generateKem()
        val (dsaPub, _) = Pqc.generateDsa()
        val bundle = Pqc.bundle(kemPub, dsaPub)
        val msg = "post-quantum hello — a payload comfortably longer than one KEM ciphertext".encodeToByteArray()
        val ct = Pqc.encryptTo(bundle, msg)
        assertContentEquals(msg, Pqc.decrypt(kemPriv, ct))
    }

    @Test
    fun ml_dsa_sign_verify() {
        if (!nativeAvailable) return
        val (kemPub, _) = Pqc.generateKem()
        val (dsaPub, dsaPriv) = Pqc.generateDsa()
        val bundle = Pqc.bundle(kemPub, dsaPub)
        val data = "authenticate me".encodeToByteArray()
        val sig = Pqc.signWith(dsaPriv, data)
        assertTrue(Pqc.verify(bundle, data, sig))
        assertFalse(Pqc.verify(bundle, "tampered".encodeToByteArray(), sig))

        val (ok, od) = Pqc.generateKem().first to Pqc.generateDsa().first
        val other = Pqc.bundle(ok, od)
        assertFalse(Pqc.verify(other, data, sig))
    }

    @Test
    fun security_code_matches_both_sides() {
        if (!nativeAvailable) return
        val a = Pqc.bundle(Pqc.generateKem().first, Pqc.generateDsa().first)
        val b = Pqc.bundle(Pqc.generateKem().first, Pqc.generateDsa().first)
        assertTrue(Pqc.securityCode(a, b) == Pqc.securityCode(b, a)) // order-independent
    }
}
