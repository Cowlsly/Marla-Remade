package com.vayunmathur.appstore.data.installer

import android.content.Context
import android.util.Log
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.PinnedStampEntity
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.accrescent.IncompatibleDeviceException
import com.vayunmathur.appstore.data.play.CertUtil
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.appstore.data.security.InstallRequirement
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Where an install has got to.
 *
 * The old UI had a single `Float?` per package, which meant the whole verify-and-commit
 * tail — often several seconds on a large split install — showed as either "100%" or
 * nothing at all, and a rejected APK looked identical to a finished one.
 */
sealed interface InstallStage {
    data object Preparing : InstallStage
    data class Downloading(val fraction: Float) : InstallStage
    data object Verifying : InstallStage
    data object Installing : InstallStage

    /** The install is over and did not start. Cleared when the row is next touched. */
    data class Failed(val reason: String) : InstallStage
}

/**
 * Downloads, verifies and installs, for every source.
 *
 * Both paths end at the same place — [SessionInstaller.installSplits] with an
 * [InstallRequirement] describing what the bytes have to prove — but they get there
 * differently: F-Droid, Modern Apps and GrapheneOS publish a URL and a hash, while Play
 * requires an account, a purchase call and a delivery response that names the files.
 */
class InstallCoordinator(
    private val context: Context,
    private val db: AppDatabase,
    private val play: PlayRepository,
    private val accrescent: AccrescentRepository,
    private val ownSigningCertificates: () -> Set<String>,
) {
    private val sessionInstaller = SessionInstaller(context)
    private val playDownloader = PlayDownloader(context)

    private val _stages = MutableStateFlow<Map<String, InstallStage>>(emptyMap())
    val stages: StateFlow<Map<String, InstallStage>> = _stages.asStateFlow()

    /** Per-package result of the last install attempt's certificate/hash checks. */
    private val _verification = MutableStateFlow<Map<String, VerificationResult>>(emptyMap())
    val verification: StateFlow<Map<String, VerificationResult>> = _verification.asStateFlow()

    /** True while anything is downloading or installing. */
    fun isBusy(packageName: String): Boolean =
        _stages.value[packageName].let { it != null && it !is InstallStage.Failed }

    /**
     * Run one install to completion.
     *
     * Returns the outcome so a caller updating several apps in a row can wait for each
     * rather than poll a progress map, which is what the old "update all" did.
     */
    suspend fun install(app: UnifiedApp): SessionInstaller.Outcome {
        if (isBusy(app.packageName)) {
            return SessionInstaller.Outcome(false, VerificationResult.Rejected("already installing"))
        }
        stage(app.packageName, InstallStage.Preparing)
        return try {
            when (app.source) {
                AppSource.PLAYSTORE -> installFromPlay(app)
                AppSource.ACCRESCENT -> installFromAccrescent(app)
                else -> installFromUrl(app)
            }.also { outcome ->
                if (outcome.started) {
                    clear(app.packageName)
                } else {
                    stage(app.packageName, InstallStage.Failed(outcome.verification.shortReason()))
                }
                record(app.packageName, outcome.verification)
            }
        } catch (e: CancellationException) {
            clear(app.packageName)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Install failed for ${app.packageName}", e)
            stage(app.packageName, InstallStage.Failed(e.message ?: "download failed"))
            SessionInstaller.Outcome(false, VerificationResult.Rejected(e.message ?: "download failed"))
        }
    }

    /** Drop a terminal [InstallStage.Failed] marker, e.g. when the user retries. */
    fun dismissFailure(packageName: String) {
        if (_stages.value[packageName] is InstallStage.Failed) clear(packageName)
    }

    // --- Direct APK: Modern Apps, F-Droid and GrapheneOS ---------------------------

    private suspend fun installFromUrl(app: UnifiedApp): SessionInstaller.Outcome =
        withContext(Dispatchers.IO) {
            val apkUrl = app.apkUrl
                ?: return@withContext SessionInstaller.Outcome(
                    false, VerificationResult.Rejected("this source published no download")
                )

            val requirement = when (app.source) {
                // The trust root: whatever certificate this store is itself signed
                // with, read back from PackageManager rather than hardcoded.
                AppSource.MODERN_APPS -> InstallRequirement(
                    expectedPackage = app.packageName,
                    requiredSigners = ownSigningCertificates(),
                    expectedSha256 = app.apkSha256
                        ?.let { mapOf("${app.packageName}.apk" to it) } ?: emptyMap(),
                    signerOrigin = "this store",
                )
                // GrapheneOS re-hosts Google's official signed APKs; the signer and hash
                // come from its signed release metadata when a sync has cached them.
                AppSource.GRAPHENEOS -> InstallRequirement(
                    expectedPackage = app.packageName,
                    requiredSigners = app.expectedSigners.toSet(),
                    expectedSha256 = app.apkSha256
                        ?.let { mapOf("${app.packageName}.apk" to it) } ?: emptyMap(),
                    signerOrigin = "GrapheneOS's signed app list",
                )
                else -> InstallRequirement(
                    expectedPackage = app.packageName,
                    requiredSigners = app.expectedSigners.toSet(),
                    expectedSha256 = app.apkSha256
                        ?.let { mapOf("${app.packageName}.apk" to it) } ?: emptyMap(),
                    signerOrigin = "F-Droid's signed app list",
                )
            }

            val file = File(context.cacheDir, "${app.packageName}.apk")
            download(apkUrl, file, app.sizeBytes) { fraction ->
                stage(app.packageName, InstallStage.Downloading(fraction))
            }

            stage(app.packageName, InstallStage.Verifying)
            sessionInstaller.installSplits(app.packageName, listOf(file), requirement, file.length())
        }

    private fun download(url: String, target: File, expectedSize: Long, onProgress: (Float) -> Unit) {
        val rawConnection = URL(url).openConnection()
        val sslSocketFactory = NetworkClient.defaultSslSocketFactory
        if (sslSocketFactory != null && rawConnection is HttpsURLConnection) {
            rawConnection.sslSocketFactory = sslSocketFactory
        }
        val conn = (rawConnection as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // --- Accrescent ----------------------------------------------------------------

    /**
     * Install an Accrescent app: verified signed-list trust anchor + per-device split URLs.
     *
     * Fail closed at every step. The signing certificate and minimum version come only from
     * the ed25519-signed allowlist ([AccrescentRepository.refreshRepoData] +
     * [AccrescentRepository.entryFor]); if the list doesn't vouch for this app id, or the
     * refresh fails and nothing is cached, the install is refused before anything downloads.
     * The gRPC API supplies only the split URLs, which are then checked against that anchor by
     * [InstallVerifier] (correct signer + version code >= minimum). Accrescent publishes no
     * per-file hashes, so there is no `expectedSha256` — signature + min-version is the guarantee.
     */
    private suspend fun installFromAccrescent(app: UnifiedApp): SessionInstaller.Outcome =
        withContext(Dispatchers.IO) {
            // Refresh the signed allowlist (best effort — a cached, previously-verified copy is
            // fine), then read this app's trust anchor. No anchor, no install.
            accrescent.refreshRepoData()
            val trust = accrescent.entryFor(app.packageName)
                ?: return@withContext SessionInstaller.Outcome(
                    false,
                    VerificationResult.Rejected("Accrescent's signed app list does not vouch for this app"),
                )

            val splits = try {
                accrescent.downloadInfo(app.packageName)
            } catch (e: IncompatibleDeviceException) {
                return@withContext SessionInstaller.Outcome(
                    false, VerificationResult.Rejected("this app has no build for your device")
                )
            }
            if (splits.isEmpty()) {
                return@withContext SessionInstaller.Outcome(
                    false, VerificationResult.Rejected("Accrescent returned no files to download")
                )
            }

            val totalSize = splits.sumOf { it.size }.takeIf { it > 0 } ?: -1L
            var downloadedBytes = 0L
            val files = splits.mapIndexed { index, split ->
                val file = File(context.cacheDir, "${app.packageName}.split$index.apk")
                val start = downloadedBytes
                download(split.url, file, split.size) { fileFraction ->
                    if (totalSize > 0) {
                        val overall = (start + fileFraction * split.size) / totalSize
                        stage(app.packageName, InstallStage.Downloading(overall.coerceIn(0f, 1f)))
                    }
                }
                downloadedBytes += file.length()
                file
            }

            val requirement = InstallRequirement(
                expectedPackage = app.packageName,
                requiredSigners = setOf(trust.signingCertHash),
                expectedSha256 = emptyMap(),
                minVersionCode = trust.minVersionCode,
                signerOrigin = "Accrescent's signed app list",
            )

            stage(app.packageName, InstallStage.Verifying)
            sessionInstaller.installSplits(
                app.packageName, files, requirement, files.sumOf { it.length() }
            )
        }

    // --- Play ----------------------------------------------------------------------

    private suspend fun installFromPlay(app: UnifiedApp): SessionInstaller.Outcome =
        withContext(Dispatchers.IO) {
            // The listing that got the user here may be a cluster entry with no version
            // code, and purchase() needs one that matches what Play will serve.
            val details = play.details(app.packageName) ?: app
            val versionCode = details.versionCode.takeIf { it > 0 } ?: app.versionCode
            val offerType = details.offerType

            // Key rotation: Play wants the certificate of the copy already on the device
            // so it can serve a delivery the installer will accept as an update.
            val certHash = runCatching {
                CertUtil.getEncodedCertificateHashes(context, app.packageName).lastOrNull()
            }.getOrNull()

            var files = purchase(app.packageName, versionCode, offerType, certHash)
            if (files.isEmpty()) throw java.io.IOException("Play returned no files to download")

            var downloaded = playDownloader.downloadFiles(app.packageName, versionCode, files) { f ->
                stage(app.packageName, InstallStage.Downloading(f))
            }

            // Delivery URLs are short-lived; one expiry is worth a second purchase call
            // rather than making the user tap install again.
            if (downloaded.exceptionOrNull() is PlayDownloader.ExpiredUrlException) {
                files = purchase(app.packageName, versionCode, offerType, certHash)
                downloaded = playDownloader.downloadFiles(app.packageName, versionCode, files) { f ->
                    stage(app.packageName, InstallStage.Downloading(f))
                }
            }
            val localFiles = downloaded.getOrThrow()

            stage(app.packageName, InstallStage.Verifying)

            // Everything Play can actually give us, all enforced:
            //  - per-split SHA-256 from the delivery response (PlayFile.sha256)
            //  - the expected signing certificate from AppDetails.certificateSet
            //  - the source stamp, pinned TOFU (survives Play App Signing)
            //  - continuity with the installed copy (InstallVerifier, always)
            val expectedHashes = files.mapNotNull { f ->
                val name = f.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                f.sha256.takeIf { it.isNotBlank() }?.let { name to it }
            }.toMap()

            val requirement = InstallRequirement(
                expectedPackage = app.packageName,
                requiredSigners = details.expectedSigners.toSet(),
                expectedSha256 = expectedHashes,
                signerOrigin = "Google",
                pinnedStamp = db.pinnedStampDao().byPackage(app.packageName)?.stampSha256,
            )

            sessionInstaller.installSplits(
                app.packageName, localFiles, requirement, localFiles.sumOf { it.length() }
            )
        }

    /** Retries once without the installed-copy certificate, which Play sometimes rejects. */
    private suspend fun purchase(
        packageName: String,
        versionCode: Long,
        offerType: Int,
        certHash: String?,
    ) = try {
        play.purchase(packageName, versionCode, offerType, certHash)
    } catch (e: Exception) {
        if (certHash == null) throw e
        play.purchase(packageName, versionCode, offerType, null)
    }

    // --- Bookkeeping ----------------------------------------------------------------

    /**
     * Surface the verdict, and persist a newly observed source stamp so the next update
     * for this package is pinned to it.
     */
    private suspend fun record(packageName: String, result: VerificationResult) {
        _verification.value = _verification.value + (packageName to result)
        val stamp = result.stamp ?: return
        runCatching {
            db.pinnedStampDao().upsert(
                PinnedStampEntity(
                    packageName = packageName,
                    stampSha256 = stamp,
                    firstSeen = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun stage(packageName: String, stage: InstallStage) {
        _stages.value = _stages.value + (packageName to stage)
    }

    private fun clear(packageName: String) {
        _stages.value = _stages.value - packageName
    }

    private fun VerificationResult.shortReason(): String = when (this) {
        is VerificationResult.Rejected -> reason
        is VerificationResult.Unverified -> reason
        is VerificationResult.Verified -> "install did not start"
    }

    private companion object {
        const val TAG = "InstallCoordinator"
    }
}
