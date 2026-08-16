package com.vayunmathur.communicate.data.whatsapp.registration

/**
 * Pinned constants for WhatsApp primary-client registration attestation.
 *
 * ⚠️ ALL of these are recovered from ONE specific WhatsApp APK build and must stay in sync:
 *   apk-analysis/whatsapp — versionName 2.26.29.73, versionCode 262907320.
 *
 * The registration `token` (see [RegistrationAttestation.computeToken]) binds:
 *   - the app package name ("com.whatsapp"),
 *   - the app's `about_logo.png` bytes (bundled at assets/whatsapp/about_logo.png),
 *   - the app signing certificate DER (bundled at assets/whatsapp/signing_cert.der),
 *   - MD5(classes.dex) of the pinned APK ([CLASSES_DEX_MD5_HEX]),
 * so if you re-pin to a newer APK you MUST re-extract the logo, cert, dex MD5 and update
 * [WhatsAppProtocol.WA_VERSION]/[WA_VERSION_NAME].
 *
 * Evidence: p000X/C34029EuU.java (token), p000X/ES3.java (salt), p000X/ES4.java (server pubkey),
 * core/http/retry/RetryingHttpClient.java (ENC/H). See communicate/whatsapp-documentation.md.
 */
object WhatsAppRegistrationConstants {

    /** Official WhatsApp package name — first component of the token password. */
    const val PACKAGE_NAME = "com.whatsapp"

    /** PBKDF2 salt, Base64 (p000X/ES3.java:7). */
    const val TOKEN_SALT_B64 =
        "PkTwKSZqUfAUyR0rPQ8hYJ0wNsQQ3dW1+3SCnyTXIfEAxxS75FwkDf47wNv/c8pP3p0GXKR6OOQmhyERwx74fw1RYSU10I4r1gyBVDbRJ40pidjM41G1I1oN"

    /** PBKDF2 iteration count (C34029EuU.A01 → C00L.A08 arg 128). */
    const val TOKEN_PBKDF2_ITERATIONS = 128

    /** PBKDF2 derived-key length in BITS (C00L.A08 arg 512). */
    const val TOKEN_PBKDF2_KEYLEN_BITS = 512

    /** MD5(classes.dex) of the pinned base.apk, lower-case hex (16 bytes). */
    const val CLASSES_DEX_MD5_HEX = "eda19e0c342f17d7aae9ae5e4d50e439"

    /**
     * Official-identity values for the `_gi` device-integrity blob, so it describes the REAL
     * WhatsApp app rather than this client. Extracted from the SAME pinned APK as everything else
     * (com.whatsapp base.apk, versionName 2.26.29.73 / versionCode 262907320, pulled from the device
     * via `adb pull` — NOT committed). Keep in sync with [CLASSES_DEX_MD5_HEX], the bundled
     * signing cert + about_logo.png, and [WhatsAppProtocol.WA_VERSION] (all one APK).
     */
    const val OFFICIAL_APK_SHA256_B64 = "38wasuL4WBcWVxf5K0p6dc6ELF+OvBqshecYAn1awkM="

    /** Official com.whatsapp base.apk size in bytes (same pinned APK), for the `_gi` blob. */
    const val OFFICIAL_APK_SIZE = 120328807L

    /** Canonical official install path for the `_gi` blob (the real path is per-install random). */
    const val OFFICIAL_SOURCE_DIR = "/data/app/com.whatsapp/base.apk"

    /** Registration server static X25519 public key, hex (p000X/ES4.java). Used by the ENC wrapper. */
    const val REG_SERVER_X25519_PUBKEY_HEX =
        "8e8c0f74c3ebc5d7a6865c6c3c843856b06121cce8ea774d22fb6f122512302d"

    /** Bundled asset paths. */
    const val ASSET_ABOUT_LOGO = "whatsapp/about_logo.png"
    const val ASSET_SIGNING_CERT_DER = "whatsapp/signing_cert.der"

    /** Registration REST host. Endpoints live under https://<host>/v2/ endpoints . */
    const val REG_HOST = "v.whatsapp.net"

    fun endpoint(path: String): String = "https://$REG_HOST/v2/$path"

    /** e_keytype for Curve25519 (djb type). */
    const val KEY_TYPE_CURVE25519: Byte = 0x05

    /**
     * Key type byte for the post-quantum (Kyber1024 / ML-KEM) last-resort prekey. Mirrors the
     * curve25519 `0x05` type-byte convention: the PQ signed-prekey signature is computed over
     * `0x08 || pqPublicKey` (see [RegistrationKeys]/`WhatsAppPqPreKey`). Ref w2.md §2.1
     * (`e_pq_last_resort_sig` = `Ed25519(sign(e_ident+0x08+e_pq_val))`).
     */
    const val KEY_TYPE_KYBER: Byte = 0x08

    /** The fixed id assigned to our single PQ last-resort prekey (3B BE on the wire). */
    const val PQ_LAST_RESORT_KEY_ID = 1

    /** Expected Kyber1024 public key length in bytes (`e_pq_last_resort_val`, w2.md §2.1). */
    const val PQ_KYBER1024_PUBLIC_LEN = 1568
}
