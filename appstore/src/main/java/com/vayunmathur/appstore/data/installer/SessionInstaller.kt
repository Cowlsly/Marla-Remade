package com.vayunmathur.appstore.data.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Process
import android.util.Log
import com.vayunmathur.appstore.data.security.InstallRequirement
import com.vayunmathur.appstore.data.security.InstallVerifier
import com.vayunmathur.appstore.data.security.VerificationResult
import java.io.File

/**
 * Installer for single + split APKs via PackageInstaller.
 *
 * [requirement] is mandatory rather than defaulted: every install path has to state what
 * it expects of the bytes, so a new caller cannot accidentally inherit "check nothing".
 * Verification runs before the session is created, so rejected bytes are never written
 * into a PackageInstaller session at all.
 */
class SessionInstaller(
    private val context: Context,
) {

    companion object {
        private const val TAG = "SessionInstaller"
    }

    /** Outcome of an install attempt, including why it was refused. */
    data class Outcome(val started: Boolean, val verification: VerificationResult)

    suspend fun installSplits(
        packageName: String,
        files: List<File>,
        requirement: InstallRequirement,
        totalSize: Long = -1L
    ): Outcome {
        if (files.isEmpty()) {
            Log.w(TAG, "No files to install for $packageName")
            return Outcome(false, VerificationResult.Rejected("nothing was downloaded"))
        }

        val verification = InstallVerifier.verify(context, files, requirement)
        if (verification is VerificationResult.Rejected) {
            Log.e(TAG, "Refusing to install $packageName: ${verification.reason}")
            files.forEach { runCatching { it.delete() } }
            return Outcome(false, verification)
        }

        val started = commit(packageName, files, totalSize)
        return Outcome(started, verification)
    }

    private fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess

    private fun commit(packageName: String, files: List<File>, totalSize: Long): Boolean {
        val computedSize = if (totalSize > 0) totalSize else files.sumOf { it.length() }
        return try {
            val pm = context.packageManager
            val installer = pm.packageInstaller

            // Abandon any of our own still-pending sessions for this package before opening a
            // new one. A previous attempt the user dismissed at the system prompt (or one that
            // failed after the session was created) leaves a pending session, and committing a
            // second session for the same package makes PackageManager reject it with
            // INSTALL_FAILED_DUPLICATE_PACKAGE ("Duplicate package ... in pending install
            // requests"). Multi-split installs (e.g. Accrescent) hit this most, being slower.
            runCatching {
                installer.mySessions
                    .filter { it.appPackageName == packageName }
                    .forEach { runCatching { installer.abandonSession(it.sessionId) } }
            }

            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                if (computedSize > 0) setSize(computedSize)
                setAppPackageName(packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // API 34+. Below that the system fills the installer package
                    // in from the calling UID anyway.
                    setInstallerPackageName(context.packageName)
                    setRequestUpdateOwnership(true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
                }
                // Silent install is only offered for updates: the OS honours
                // USER_ACTION_NOT_REQUIRED when this app is the target's update owner, and
                // a first-time install of a package owned by someone else would prompt
                // anyway. First installs keep the confirmation dialog unconditionally.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isInstalled(packageName)) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                setInstallLocation(android.content.pm.PackageInfo.INSTALL_LOCATION_AUTO)
                setOriginatingUid(Process.myUid())
            }

            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)

            try {
                for (file in files) {
                    val name = file.name
                    session.openWrite(name, 0, file.length()).use { out ->
                        file.inputStream().use { input ->
                            input.copyTo(out)
                        }
                        session.fsync(out)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Write failed for $packageName: ${e.message}", e)
                try { session.abandon() } catch (_: Exception) {}
                return false
            }

            val intent = Intent(context, InstallStatusReceiver::class.java).apply {
                action = InstallStatusReceiver.ACTION_INSTALL_STATUS
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, intent, pendingFlags
            )
            session.commit(pendingIntent.intentSender)
            session.close()

            Log.i(TAG, "Commit started for $packageName sessionId=$sessionId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Install failed for $packageName: ${e.message}", e)
            false
        }
    }

}
