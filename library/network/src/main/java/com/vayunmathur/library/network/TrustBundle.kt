package com.vayunmathur.library.network

/**
 * Reduced CA trust bundles. Each bundle maps to a set of DER roots shipped in
 * `assets/ca/`.  SYSTEM means "use platform default" (null factory) — needed for
 * user-supplied hosts (vpn, custom email servers, browser).
 *
 * - FIRST_PARTY: ~6 roots for api.vayunmathur.com, data.vayunmathur.com, findfamily.cc
 *   Cloudflare Universal SSL: ISRG X1/X2 + GTS R1-R4 (SSL.com optional, add if observed).
 * - STANDARD: FIRST_PARTY + DigiCert G2/G3, Baltimore CyberTrust, Amazon Root CA 1-4,
 *   Sectigo AAA, USERTrust RSA, GoDaddy Root G2 — covers F-Droid (ISRG), GitHub
 *   (DigiCert), Play/Aurora (Google GTS), DuckDuckGo (DigiCert), open-meteo (ISRG),
 *   and the Cover Art Archive redirect chain (coverartarchive.org -> archive.org /
 *   ia*.us.archive.org, served on GoDaddy certs).
 * - EXTENDED: STANDARD + Microsoft RSA 2017, Apple Root G2/G3, Apple IST CA 2 G1 — for
 *   everysync (Google + Apple CalDAV) and messages non-Signal (Googleapis GTS + FB/DigiCert).
 * - MUSICBRAINZ: STANDARD + GlobalSign Root R3. Tidal splits its audio CDN across two
 *   roots — the `sp-*-cf.audio.tidal.com` hosts are Amazon (already in STANDARD) but the
 *   `sp-*-fa.audio.tidal.com` hosts serve `*.audio.tidal.com` off GlobalSign, so which
 *   CDN a playback manifest happens to name decides whether the download validates. Kept
 *   as its own bundle rather than folded into STANDARD so the other STANDARD apps do not
 *   silently gain a root only musicbrainz needs.
 * - SYSTEM: platform default; email/web/vpn dynamic hosts.
 */
enum class TrustBundle {
    FIRST_PARTY,
    STANDARD,
    EXTENDED,
    MUSICBRAINZ,
    SYSTEM,
    ;

    fun assetPaths(): List<String> = when (this) {
        FIRST_PARTY -> listOf(
            "ca/isrgrootx1.der",
            "ca/isrgrootx2.der",
            "ca/gts-root-r1.der",
            "ca/gts-root-r2.der",
            "ca/gts-root-r3.der",
            "ca/gts-root-r4.der",
        )
        STANDARD -> FIRST_PARTY.assetPaths() + listOf(
            "ca/digicert-global-g2.der",
            "ca/digicert-global-g3.der",
            "ca/baltimore-cybertrust.der",
            "ca/amazon-root-ca1.der",
            "ca/amazon-root-ca2.der",
            "ca/amazon-root-ca3.der",
            "ca/amazon-root-ca4.der",
            // aaa-cert (AAA Certificate Services) is retired from Mozilla bundle;
            // sectigo chain covered by usertrust-rsa. Kept optional via BundledTrust w/ warning if present.
            "ca/usertrust-rsa.der",
            // archive.org and ia*.us.archive.org (Cover Art Archive redirect targets)
            // serve GoDaddy-issued certs, so their root is required to fetch/embed cover art.
            "ca/godaddy-root-g2.der",
        )
        EXTENDED -> STANDARD.assetPaths() + listOf(
            "ca/microsoft-rsa-2017.der",
            "ca/apple-root-g2.der",
            "ca/apple-root-g3.der",
            "ca/apple-ist-ca2-g1.der",
        )
        // Tidal's api and its CloudFront audio hosts are Amazon-rooted and already covered by
        // STANDARD, which is why sign-in and playback lookup work without this; only the
        // Fastly-fronted `*.audio.tidal.com` certificate needs the GlobalSign root.
        MUSICBRAINZ -> STANDARD.assetPaths() + listOf(
            "ca/globalsign-root-r3.der",
        )
        SYSTEM -> emptyList()
    }
}
