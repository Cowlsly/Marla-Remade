package com.vayunmathur.passwords.platform.cable

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.math.BigInteger
import java.security.interfaces.ECPublicKey

/**
 * Known-answer tests for the platform-crypto (Conscrypt/SunEC) implementations behind the
 * caBLE handshake.
 *
 * These used to compare against Bouncy Castle as a live oracle; they now pin the same
 * behaviour to published constants instead — RFC 5869 Appendix A for HKDF-SHA256, and the
 * NIST P-256 domain parameters for point decompression — so the crypto is checked against
 * the standard rather than against another implementation, with no extra dependency.
 */
class CryptoParityTest {

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    // ---------------------------------------------------------------------
    // HKDF-SHA256 — RFC 5869 Appendix A test vectors
    // ---------------------------------------------------------------------

    @Test
    fun hkdf_matches_rfc5869_basic_vector() {
        // A.1: IKM 22x0b, 13-byte salt, 10-byte info, L = 42.
        assertContentEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"),
            CableKeys.hkdf(
                ikm = ByteArray(22) { 0x0b },
                salt = ByteArray(13) { it.toByte() },
                info = ByteArray(10) { (0xf0 + it).toByte() },
                length = 42,
            ),
        )
    }

    @Test
    fun hkdf_matches_rfc5869_long_input_vector() {
        // A.2: 80-byte IKM, 80-byte salt, 80-byte info, L = 82 — spans several expand rounds.
        assertContentEquals(
            hex("b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87"),
            CableKeys.hkdf(
                ikm = ByteArray(0x50) { it.toByte() },
                salt = ByteArray(0x50) { (0x60 + it).toByte() },
                info = ByteArray(0x50) { (0xb0 + it).toByte() },
                length = 82,
            ),
        )
    }

    @Test
    fun hkdf_matches_rfc5869_empty_salt_vector() {
        // A.3: zero-length salt and info — the shape caBLE uses for the QR-derived keys.
        val expected = hex(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"
        )
        val ikm = ByteArray(22) { 0x0b }
        assertContentEquals(expected, CableKeys.hkdf(ikm, ByteArray(0), ByteArray(0), 42))
        // A null salt must be treated as the RFC's zero salt, identically to an empty one
        // (BoringSSL parity — Chromium relies on this for the QR secret).
        assertContentEquals(expected, CableKeys.hkdf(ikm, null, ByteArray(0), 42))
    }

    @Test
    fun hkdf_output_lengths_are_prefixes_of_each_other() {
        // Expand is a counter-mode stream: a shorter request must be a prefix of a longer one.
        val ikm = ByteArray(16) { it.toByte() }
        val info = "test-info".toByteArray()
        for (salt in listOf<ByteArray?>(null, ByteArray(0), "some-salt".toByteArray())) {
            val full = CableKeys.hkdf(ikm, salt, info, 100)
            for (len in intArrayOf(10, 16, 32, 64, 100)) {
                assertContentEquals(
                    full.copyOf(len),
                    CableKeys.hkdf(ikm, salt, info, len),
                    "salt=${salt?.size} len=$len",
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // P-256 point encoding — NIST domain parameters
    // ---------------------------------------------------------------------

    private val p = BigInteger(
        "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16
    )
    private val curveB = BigInteger(
        "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16
    )

    /** y² == x³ - 3x + b (mod p) */
    private fun onCurve(x: BigInteger, y: BigInteger): Boolean =
        y.multiply(y).subtract(
            x.multiply(x).multiply(x).subtract(x.multiply(BigInteger.valueOf(3))).add(curveB)
        ).mod(p).signum() == 0

    @Test
    fun p256_decompresses_the_standard_base_point() {
        // The secp256r1 generator, as published in SEC 2 / FIPS 186-4.
        val gx = BigInteger(
            "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16
        )
        val gy = BigInteger(
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16
        )
        // Gy is odd, so the compressed form carries the 0x03 prefix.
        val compressed = hex(
            "036b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
        )
        val decoded = P256.decodePoint(compressed) as ECPublicKey
        assertEquals(gx, decoded.w.affineX)
        assertEquals(gy, decoded.w.affineY)
    }

    @Test
    fun p256_compressed_decode_round_trips_and_stays_on_curve() {
        repeat(20) {
            val kp = P256.generateKeyPair()
            val original = kp.public as ECPublicKey

            val fromCompressed = P256.decodePoint(P256.toCompressed(kp.public)) as ECPublicKey
            assertEquals(original.w.affineX, fromCompressed.w.affineX)
            assertEquals(original.w.affineY, fromCompressed.w.affineY)

            val fromUncompressed = P256.decodePoint(P256.toUncompressed(kp.public)) as ECPublicKey
            assertEquals(original.w.affineX, fromUncompressed.w.affineX)
            assertEquals(original.w.affineY, fromUncompressed.w.affineY)

            assertTrue(
                fromCompressed.w.affineX.let { _ -> onCurve(fromCompressed.w.affineX, fromCompressed.w.affineY) },
                "decompressed point off curve",
            )
            // The recovered Y's parity must match the prefix that was encoded.
            val prefix = P256.toCompressed(kp.public)[0].toInt() and 0xff
            assertEquals(prefix == 0x03, fromCompressed.w.affineY.testBit(0))
        }
    }

    @Test
    fun p256_ecdh_is_symmetric() {
        val a = P256.generateKeyPair()
        val b = P256.generateKeyPair()
        val ab = P256.ecdh(a.private, P256.decodePoint(P256.toUncompressed(b.public)))
        val ba = P256.ecdh(b.private, P256.decodePoint(P256.toUncompressed(a.public)))
        assertContentEquals(ab, ba)
        assertEquals(P256.DH_OUTPUT_SIZE, ab.size)
    }
}
