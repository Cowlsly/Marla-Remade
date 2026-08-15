package com.vayunmathur.passwords.cable

import java.security.MessageDigest

/**
 * caBLE v2 assigned tunnel-server domains.
 *
 * A 16-bit domain id is either a well-known assigned index (`< 256`) or, for larger values, an
 * algorithmically derived `<base32>.<tld>` name. Mirrors Chromium
 * `device::cablev2::tunnelserver` (`//device/fido/cable/v2_handshake.cc`).
 *
 * In the QR authenticator flow we choose which tunnel server to use, so in practice only the
 * assigned indices 0/1 are needed (index 0 = Google's server, which every initiator knows).
 *
 * ⚠️ The algorithmic derivation for ids ≥ 256 is UNVERIFIED against Chromium and only reached if we
 * ever pick a non-assigned domain (we don't in v1).
 */
object TunnelDomains {
    /** Assigned domains, indexed by id. 0 = Google, 1 = Apple. */
    private val ASSIGNED = arrayOf("cable.ua5v.com", "cable.auth.com")

    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"
    private val TLDS = arrayOf("com", "org", "net", "info")

    /** The always-safe default: index 0, which every initiator is guaranteed to know. */
    const val DEFAULT_ID = 0

    fun decode(domainId: Int): String {
        require(domainId in 0..0xFFFF) { "domain id out of range: $domainId" }
        if (domainId < 256) {
            require(domainId < ASSIGNED.size) { "unknown assigned tunnel domain id: $domainId" }
            return ASSIGNED[domainId]
        }
        return deriveAlgorithmicDomain(domainId)
    }

    /**
     * Chromium's derived-domain scheme for ids ≥ 256 (`tunnelserver::DecodeDomain`). Only reached
     * if we ever pick a non-assigned domain, which v1 does not (we always use id 0).
     */
    private fun deriveAlgorithmicDomain(domainId: Int): String {
        // template = "caBLEv2 tunnel server domain" (28) || U16LE(domain) || 0x00  == 31 bytes
        val input = "caBLEv2 tunnel server domain".toByteArray(Charsets.US_ASCII) +
            byteArrayOf((domainId and 0xFF).toByte(), ((domainId ushr 8) and 0xFF).toByte(), 0)
        val digest = MessageDigest.getInstance("SHA-256").digest(input)

        var result = 0L
        for (i in 0 until 8) result = result or ((digest[i].toLong() and 0xFF) shl (8 * i))

        val tld = TLDS[(result and 0x3).toInt()]
        result = result ushr 2

        val sb = StringBuilder("cable.")
        while (result != 0L) {
            sb.append(BASE32[(result and 0x1F).toInt()])
            result = result ushr 5
        }
        return sb.append('.').append(tld).toString()
    }
}
