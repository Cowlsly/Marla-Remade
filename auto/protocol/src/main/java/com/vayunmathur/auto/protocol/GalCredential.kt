package com.vayunmathur.auto.protocol

import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory

/**
 * The Google Automotive Link credential and the TLS context built from it.
 *
 * Head units authenticate the phone against a private `Google Automotive Link` root, so a
 * projection sender has to present a leaf signed by that root. The one we use is Google's
 * own, lifted out of the Android Auto APK; see `analysis/maauto/FINDINGS.md` and the entry
 * in `SUPPLY_CHAIN_RISKS.md`. **It expires 2026-12-09 and cannot be renewed by us.**
 *
 * Two details are load-bearing and easy to get backwards:
 *
 *  1. **The phone is the TLS server.** Gearhead calls `setUseClientMode(false)` and
 *     `setNeedClientAuth(true)`; the head unit connects as the client and must present its
 *     own GAL-signed certificate.
 *  2. **The GAL root is the only trust anchor.** The system trust store is not consulted,
 *     so the trust manager is built from a keystore holding just that one certificate under
 *     the alias `GAL`.
 */
object GalCredential {

    private const val ASSET_DIR = "gal"
    const val CERT_ASSET = "$ASSET_DIR/client-cert.pem"
    const val KEY_ASSET = "$ASSET_DIR/client-key.pem"
    const val ROOT_ASSET = "$ASSET_DIR/root.pem"

    /** Keystore alias for the GAL root, matching gearhead's. */
    private const val ROOT_ALIAS = "GAL"

    /**
     * Builds the TLS context from PEM text.
     *
     * Kept free of Android so the handshake can be exercised on the JVM; [fromAssets] is
     * the thin wrapper that reads the shipped files.
     */
    fun create(certPem: String, keyPem: String, rootPem: String): SSLContext {
        val certificates = CertificateFactory.getInstance("X.509")
        val leaf = certificates.generateCertificate(
            ByteArrayInputStream(certPem.toByteArray()),
        ) as X509Certificate
        val root = certificates.generateCertificate(
            ByteArrayInputStream(rootPem.toByteArray()),
        ) as X509Certificate

        // An empty password rather than null: KeyStore.setKeyEntry rejects null, and the
        // store never leaves memory.
        val empty = CharArray(0)

        val identity = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", parsePrivateKey(keyPem), empty, arrayOf(leaf))
        }
        val keyManagers = KeyManagerFactory
            .getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(identity, empty) }
            .keyManagers

        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry(ROOT_ALIAS, root)
        }
        val trustManagers = TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(anchors) }
            .trustManagers

        return SSLContext.getInstance("TLSv1.2").apply {
            init(keyManagers, trustManagers, null)
        }
    }

    /** Reads the credential shipped in this module's assets. */
    fun fromAssets(assets: AssetManager): SSLContext = create(
        certPem = assets.open(CERT_ASSET).use { it.readBytes().decodeToString() },
        keyPem = assets.open(KEY_ASSET).use { it.readBytes().decodeToString() },
        rootPem = assets.open(ROOT_ASSET).use { it.readBytes().decodeToString() },
    )

    /**
     * An engine configured the way gearhead configures its own: server side, client
     * certificate required.
     */
    fun serverEngine(context: SSLContext): SSLEngine = context.createSSLEngine().apply {
        useClientMode = false
        needClientAuth = true
    }

    private fun parsePrivateKey(pem: String): PrivateKey {
        val body = pem
            .substringAfter("-----BEGIN PRIVATE KEY-----")
            .substringBefore("-----END PRIVATE KEY-----")
            .filterNot(Char::isWhitespace)
        require(body.isNotEmpty()) { "no PKCS#8 PRIVATE KEY block in the credential" }
        val der = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }
}
