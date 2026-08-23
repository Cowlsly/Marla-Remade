package com.vayunmathur.appstore.data.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.io.File
import java.security.MessageDigest

/**
 * Reads signing certificates out of APK files and installed packages.
 *
 * A "fingerprint" here is always the lowercase-hex SHA-256 of the DER-encoded X.509
 * signing certificate. That is the same value `apksigner verify --print-certs` prints
 * as *Signer #1 certificate SHA-256 digest*, and the same value F-Droid publishes as a
 * package's `signer` in its index — so index-supplied and locally-computed values are
 * directly comparable with no conversion.
 *
 * [PackageManager.getPackageArchiveInfo] performs a real APK signature verification
 * (v3/v2 block, falling back to v1) while parsing, so a certificate coming back from
 * [apkSigners] means the file genuinely carries a valid signature by that certificate —
 * not merely that a certificate is embedded somewhere in it.
 */
object ApkCertificates {

    /** Fingerprints of the certificates that actually signed [apk]. Empty if unparseable. */
    fun apkSigners(context: Context, apk: File): Set<String> =
        fingerprints(signaturesOf(archiveInfo(context, apk), contentsOnly = true))

    /**
     * Fingerprints an APK may sign *for*: the certificates that signed its bytes plus any
     * rotated ancestors it carries proof of lineage to.
     *
     * A source publishes an app's *identity* certificate, which under v3 key rotation stays
     * the original while the bytes carry the current one. An expected signer must therefore be
     * matched against this set, not [apkSigners] — otherwise a legitimately rotated build
     * looks like a substituted one. This is the same lineage Android itself accepts when it
     * applies the update.
     *
     * Takes an already-parsed [archiveInfo] rather than the file, since parsing re-verifies the
     * whole archive and the install path has done that already.
     */
    fun signerLineage(info: PackageInfo?): Set<String> =
        fingerprints(signaturesOf(info, contentsOnly = false))

    /**
     * Fingerprints currently trusted for an installed [packageName]. Includes rotated
     * ancestors (`signingCertificateHistory`) so an app that has legitimately rotated its
     * key still matches an APK signed by either the old or the new certificate.
     */
    fun installedSigners(context: Context, packageName: String): Set<String> = try {
        val info = context.packageManager
            .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fingerprints(signaturesOf(info, contentsOnly = false))
    } catch (_: Exception) {
        emptySet()
    }

    /**
     * Fingerprints of the certificate **this app** is signed with.
     *
     * Read back out of PackageManager at runtime rather than compared against a constant
     * compiled into the source, so there is no fingerprint in the codebase that could
     * drift from — or be edited independently of — the key that actually ships.
     */
    fun selfSigners(context: Context): Set<String> = installedSigners(context, context.packageName)

    /** Parsed manifest of an APK on disk, or null if it isn't a readable, validly-signed APK. */
    fun archiveInfo(context: Context, apk: File): PackageInfo? = try {
        @Suppress("DEPRECATION")
        context.packageManager
            .getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (_: Exception) {
        null
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().toHex()
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** Short form for UI: first 8 hex bytes, colon separated. */
    fun abbreviate(fingerprint: String): String =
        fingerprint.chunked(2).take(8).joinToString(":").uppercase()

    /**
     * Normalise a certificate fingerprint to lowercase hex.
     *
     * F-Droid publishes hex; Play's `CertificateSet.sha256` is base64url of the raw
     * 32 bytes (its sibling `certificateHash` uses the same encoding for SHA-1 — see the
     * `08wXWKFU633Z_75SlQFnM8loIWE` style values hardcoded in the Play Store binary).
     * Returns null for anything that decodes to neither.
     */
    fun normalizeFingerprint(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.length == 64 && trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return trimmed.lowercase()
        }
        return try {
            val flags = android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING or
                android.util.Base64.URL_SAFE
            val bytes = android.util.Base64.decode(trimmed, flags)
            if (bytes.size == 32) bytes.toHex() else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [contentsOnly] true asks "what signed these bytes" (`apkContentsSigners`); false asks
     * "what may sign for this identity" and therefore also accepts rotated ancestors.
     */
    private fun signaturesOf(info: PackageInfo?, contentsOnly: Boolean): Array<Signature> {
        val signing = info?.signingInfo ?: return emptyArray()
        val contents = signing.apkContentsSigners ?: emptyArray()
        if (contentsOnly || signing.hasMultipleSigners()) return contents
        return contents + (signing.signingCertificateHistory ?: emptyArray())
    }

    private fun fingerprints(signatures: Array<Signature>): Set<String> =
        signatures.mapNotNull { sig ->
            try {
                MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).toHex()
            } catch (_: Exception) {
                null
            }
        }.toSet()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
