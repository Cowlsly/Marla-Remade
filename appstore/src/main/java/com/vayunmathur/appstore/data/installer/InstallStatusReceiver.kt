package com.vayunmathur.appstore.data.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.vayunmathur.appstore.R
import com.vayunmathur.library.util.AppMessages

/**
 * Receives PackageInstaller commit callbacks.
 * Action: com.vayunmathur.appstore.INSTALL_STATUS
 *
 * This is the only place the real outcome of an install arrives. `commit()` returning
 * successfully only means the session was handed over, so a failure here used to reach
 * logcat and nowhere else, leaving the user with the "Installing X" message the ViewModel
 * had already shown and no indication anything had gone wrong.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_STATUS = "com.vayunmathur.appstore.INSTALL_STATUS"
        private const val TAG = "InstallStatusReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Forward user action intent
                val userAction = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                try {
                    userAction?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(it)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start user action: ${e.message}")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Install success for $pkg")
            }
            // The user backing out of the system prompt is not a failure worth reporting back.
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "Install aborted for $pkg")
            }
            else -> {
                Log.w(TAG, "Install failed for $pkg status=$status message=$message")
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        context.getString(R.string.install_failure_incompatible)
                    PackageInstaller.STATUS_FAILURE_STORAGE ->
                        context.getString(R.string.install_failure_storage)
                    PackageInstaller.STATUS_FAILURE_CONFLICT ->
                        context.getString(R.string.install_failure_conflict)
                    else -> message?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.install_failure_unknown)
                }
                AppMessages.show(
                    context.getString(R.string.install_failed_reason, pkg.orEmpty(), reason),
                    duration = AppMessages.Duration.Long,
                )
            }
        }
    }
}
