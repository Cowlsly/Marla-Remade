package com.vayunmathur.appstore.data.security

import android.content.Context
import com.vayunmathur.appstore.data.AppProvider
import java.io.File

/**
 * What must hold for a downloaded set of APK files before they may be handed to
 * PackageInstaller. Built per install by the caller from whatever the source could
 * authenticate; see [InstallVerifier.verify] for how each field is enforced.
 */
data class InstallRequirement(
    val expectedPackage: String,
    /**
     * Certificate fingerprints the APK must be signed by, or prove rotation lineage to.
     * Non-empty means authoritative: a mismatch is a hard failure. Empty means the source
     * could not tell us.
     */
    val requiredSigners: Set<String> = emptySet(),
    /**
     * Expected SHA-256 per downloaded file, keyed by file name. A file present here must
     * match; files absent from the map are not hash-checked. Play supplies one entry per
     * split via `PlayFile.sha256`; F-Droid supplies one for the single APK.
     */
    val expectedSha256: Map<String, String> = emptyMap(),
    /** Human-readable origin of [requiredSigners], for the failure message. */
    val signerOrigin: String = "",
    /**
     * Previously pinned source-stamp fingerprint for this package, if any. The APK's
     * stamp (or its rotation lineage) must include it. Only used for Play, where the
     * APK signing key belongs to Google and cannot identify the publisher.
     */
    val pinnedStamp: String? = null,
    /** Refuse to install if the APK carries no verifiable source stamp at all. */
    val requireStamp: Boolean = false,
    /**
     * Minimum version code the base APK may declare, below which the install is rejected.
     * Used by Accrescent, whose ed25519-signed repodata carries a per-app minimum version
     * for rollback protection. Null (the default) means no floor, so every other source is
     * unaffected — the gate is additive and defaults to a no-op.
     */
    val minVersionCode: Long? = null,
)

sealed class VerificationResult {
    /** Every check that could be run, passed. */
    data class Verified(
        val detail: String,
        /** Stamp fingerprint to persist as this package's pin, if newly observed. */
        val stampToPin: String? = null,
    ) : VerificationResult()

    /** Nothing contradicted, but no publisher key or hash was available to check against. */
    data class Unverified(val reason: String, val stampToPin: String? = null) : VerificationResult()

    /** A check actively failed. The install must not proceed. */
    data class Rejected(val reason: String) : VerificationResult()

    val allowsInstall: Boolean get() = this !is Rejected

    val stamp: String?
        get() = when (this) {
            is Verified -> stampToPin
            is Unverified -> stampToPin
            is Rejected -> null
        }
}

/**
 * Runs the certificate and hash checks on downloaded files before install.
 *
 * Policy (chosen deliberately: block on contradiction, warn on absence):
 * - the archive must parse and declare [InstallRequirement.expectedPackage] — blocks a
 *   swapped APK being installed under a name the user thought they were getting;
 * - if the package is already installed, the new APK's signers must intersect the
 *   installed app's signer history. Android would refuse this anyway, but failing here
 *   turns a cryptic `INSTALL_FAILED_UPDATE_INCOMPATIBLE` into a stated key mismatch, and
 *   catches it before the bytes reach the system installer;
 * - a published SHA-256 must match;
 * - a published signer set must intersect the APK's signing lineage;
 * - with none of the above available, the result is [VerificationResult.Unverified] and
 *   the caller may proceed with a visible warning.
 */
object InstallVerifier {

    fun verify(
        context: Context,
        files: List<File>,
        requirement: InstallRequirement,
    ): VerificationResult {
        if (files.isEmpty()) return VerificationResult.Rejected("nothing was downloaded")

        // Parse every file. Splits legitimately parse to the same package as the base.
        val parsed = files.map { file -> file to ApkCertificates.archiveInfo(context, file) }
        val unreadable = parsed.filter { (_, info) -> info == null }
        if (unreadable.size == files.size) {
            return VerificationResult.Rejected("the download is not a valid signed app file")
        }

        val declared = parsed.mapNotNull { (_, info) -> info?.packageName }.toSet()
        if (declared.isNotEmpty() && requirement.expectedPackage !in declared) {
            return VerificationResult.Rejected(
                "the download is ${declared.joinToString()}, not ${requirement.expectedPackage}"
            )
        }

        // The catalogue filters on the target SDK each source *claims*, which a source
        // may not state at all. Re-check it against the manifest we now hold, so the floor
        // holds for every install regardless of what the listing said.
        //
        // Read it from the *base* APK specifically: a config split carries no <uses-sdk>,
        // so an arbitrary file's value (0, or absent) is meaningless and reading it first
        // by chance would either miss a low-target base or wrongly reject a valid one. Block
        // only when the base APK's target is actually readable and below the floor —
        // targetSdkVersion == 0 means "couldn't determine", which stays allowed by policy.
        val baseTargetSdk = parsed.firstOrNull { (_, info) ->
            info?.packageName == requirement.expectedPackage && info.splitNames.isNullOrEmpty()
        }?.second?.applicationInfo?.targetSdkVersion ?: 0
        if (baseTargetSdk in 1 until AppProvider.MIN_TARGET_SDK) {
            return VerificationResult.Rejected(
                "it is built for Android API $baseTargetSdk, and this store needs " +
                    "${AppProvider.MIN_TARGET_SDK} or newer"
            )
        }

        // Signers of the bytes we actually hold. Every readable file must agree, or the
        // set is mixed and something has been substituted.
        val perFileSigners = parsed
            .filter { (_, info) -> info != null }
            .map { (file, _) -> file to ApkCertificates.apkSigners(context, file) }
        val emptySigner = perFileSigners.firstOrNull { (_, signers) -> signers.isEmpty() }
        if (emptySigner != null) {
            return VerificationResult.Rejected("${emptySigner.first.name} is not signed")
        }
        val signerSets = perFileSigners.map { (_, signers) -> signers }.toSet()
        if (signerSets.size > 1) {
            return VerificationResult.Rejected("the app's files are not all signed by the same key")
        }
        val actualSigners = signerSets.first()

        // The stamp check and the required-signer check below both read the base APK, since a
        // config split carries neither a stamp nor a rotation lineage of its own.
        val base = baseApk(parsed, requirement.expectedPackage) ?: parsed.first()

        val checks = mutableListOf<String>()

        // Rollback guard (Accrescent): the base APK's version code must be at least the
        // minimum the source's signed allowlist records. Read from the base APK's manifest,
        // like the target-SDK check above. Fail closed — a version we cannot read (0) is below
        // any positive floor and is rejected, since the whole point is to refuse an old build.
        requirement.minVersionCode?.let { minVc ->
            val baseInfo = parsed.firstOrNull { (_, info) ->
                info?.packageName == requirement.expectedPackage && info.splitNames.isNullOrEmpty()
            }?.second
            val versionCode = baseInfo?.longVersionCode ?: 0L
            if (versionCode < minVc) {
                return VerificationResult.Rejected(
                    "it is version $versionCode, older than the minimum $minVc the source allows"
                )
            }
            checks += "version $versionCode is at or above the minimum $minVc"
        }

        val installed = ApkCertificates.installedSigners(context, requirement.expectedPackage)
        if (installed.isNotEmpty()) {
            if (actualSigners.none { it in installed }) {
                return VerificationResult.Rejected(
                    "it is signed with a different key than the copy you have installed"
                )
            }
            checks += "same key as the installed copy"
        }

        if (requirement.expectedSha256.isNotEmpty()) {
            var hashed = 0
            for (file in files) {
                val expected = requirement.expectedSha256[file.name] ?: continue
                val actual = ApkCertificates.sha256(file)
                if (!actual.equals(expected, ignoreCase = true)) {
                    return VerificationResult.Rejected(
                        "${file.name} does not match the hash the source published"
                    )
                }
                hashed++
            }
            // A published hash set that matches nothing we downloaded means the file
            // names moved under us; treat that as a failure rather than a silent skip.
            if (hashed == 0) {
                return VerificationResult.Rejected(
                    "the published hashes do not cover any of the files that downloaded"
                )
            }
            checks += "hash matches for $hashed of ${files.size} file(s)"
        }

        if (requirement.requiredSigners.isNotEmpty()) {
            val required = requirement.requiredSigners.map { it.lowercase() }.toSet()
            // Match the app's identity, not just the bytes: a source publishes the original
            // certificate, so an app that has rotated its signing key presents a current
            // certificate the source never listed. Accepting the proven rotation lineage keeps
            // a substituted APK out while letting a rotated one through, which is the same
            // call Android makes when it applies the update.
            val lineage = actualSigners + ApkCertificates.signerLineage(base.second)
            if (lineage.none { it in required }) {
                val origin = requirement.signerOrigin.ifBlank { "the source" }
                return VerificationResult.Rejected("it is not signed by the key $origin expects")
            }
            checks += "signed by the key ${requirement.signerOrigin.ifBlank { "the source" }} expects"
        }

        // Source stamp: a second identity that survives Play App Signing re-signing.
        // Checked on the base APK — splits do not carry their own stamp.
        var stampToPin: String? = null
        if (requirement.requireStamp || requirement.pinnedStamp != null) {
            val stamp = SourceStamp.of(base.first)
            if (stamp == null) {
                if (requirement.requireStamp) {
                    return VerificationResult.Rejected("it carries no publisher stamp to check")
                }
                if (requirement.pinnedStamp != null) {
                    return VerificationResult.Rejected(
                        "the publisher stamp is gone, and this app had one last time"
                    )
                }
            } else {
                val pinned = requirement.pinnedStamp
                if (pinned != null && stamp.lineage.none { it.equals(pinned, true) }) {
                    return VerificationResult.Rejected(
                        "the publisher stamp changed since you installed this app"
                    )
                }
                stampToPin = stamp.current
                checks += if (pinned != null) "same publisher stamp as before" else "publisher stamp saved"
            }
        }

        if (checks.isEmpty()) {
            return VerificationResult.Unverified(
                "the source published no key or hash to check this download against",
                stampToPin,
            )
        }
        return VerificationResult.Verified(checks.joinToString("; "), stampToPin)
    }

    /** The file, and its parsed manifest, declaring [packageName] without a split name. */
    private fun baseApk(
        parsed: List<Pair<File, android.content.pm.PackageInfo?>>,
        packageName: String,
    ): Pair<File, android.content.pm.PackageInfo?>? = parsed.firstOrNull { (_, info) ->
        info?.packageName == packageName && info.splitNames.isNullOrEmpty()
    }
}
