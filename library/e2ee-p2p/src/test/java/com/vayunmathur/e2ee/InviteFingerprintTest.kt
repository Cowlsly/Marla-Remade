package com.vayunmathur.e2ee

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InviteFingerprintTest {
    private val bundle = ByteArray(3184) { it.toByte() }

    @Test
    fun deterministic_and_truncated_to_length() {
        val fp = InviteFingerprint.compute(12345L, bundle)
        assertEquals(InviteFingerprint.LENGTH, fp.size)
        assertTrue(fp.contentEquals(InviteFingerprint.compute(12345L, bundle)), "same inputs, same digest")
    }

    @Test
    fun bound_to_the_user_id() {
        val fp = InviteFingerprint.compute(12345L, bundle)
        assertFalse(
            fp.contentEquals(InviteFingerprint.compute(12346L, bundle)),
            "a fingerprint must not carry over to another user's id",
        )
    }

    @Test
    fun changes_with_the_bundle() {
        val substituted = bundle.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        assertFalse(
            InviteFingerprint.compute(12345L, bundle).contentEquals(
                InviteFingerprint.compute(12345L, substituted)
            ),
            "a substituted key must not match the link's fingerprint",
        )
    }

    @Test
    fun length_delimits_the_id_from_the_bundle() {
        // Without the fixed-width id, id||bundle could be re-split: a shifted boundary
        // between the two would hash the same and let one invite's digest name another pair.
        assertFalse(
            InviteFingerprint.compute(0x0000000000000001L, byteArrayOf(2, 3)).contentEquals(
                InviteFingerprint.compute(0x0000000000000102L, byteArrayOf(3))
            )
        )
    }

    /**
     * Known-answer vectors shared with the Swift implementation (see `InviteFingerprint` in
     * Find-Family-iOS/FindFamilyiOS/Crypto/Crypto.swift). A link minted on one platform has to
     * verify on the other, and nothing else in either test suite would catch a divergence in
     * the domain string, the id width, or the truncation length.
     */
    @Test
    fun matches_the_cross_platform_test_vector() {
        assertEquals(
            "iXVzYL2J4imtjXPgh-dmtw",
            b64Url(InviteFingerprint.compute(12345L, bundle)),
        )
        // Negative ids exist in the wild (the iOS client generates them), so the id must be
        // hashed as its unsigned 64-bit bit pattern rather than sign-extended or formatted.
        assertEquals(
            "rjnCvl7blekCiZv2q9QJOA",
            b64Url(InviteFingerprint.compute(-2L, bundle)),
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun b64Url(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')
}
