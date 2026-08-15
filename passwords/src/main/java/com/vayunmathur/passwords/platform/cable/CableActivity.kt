package com.vayunmathur.passwords.cable

import com.vayunmathur.library.ui.R as UiR
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.passwords.R

/**
 * Entry point for scanned caBLE QR codes. The Camera app resolves a `FIDO:/…` value to a generic
 * "open URL" whose `fido:` scheme lands here via an `ACTION_VIEW` intent-filter (see the manifest).
 *
 * Flow: validate the URI → a single strong-biometric prompt that serves as both consent and user
 * verification → request the Nearby-devices / notification runtime permissions (first run only) →
 * start [CableService]. The activity finishes immediately after handing off. The credential
 * database is opened by the service via the no-auth key, so the app itself never needs unlocking.
 */
class CableActivity : FragmentActivity() {

    private var fidoUri: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startSessionAndFinish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data?.toString() ?: intent?.getStringExtra(CableService.EXTRA_FIDO_URI)
        if (uri == null || !looksLikeFido(uri)) {
            Log.e(TAG, "No FIDO caBLE URI in intent")
            finish()
            return
        }
        fidoUri = uri
        verifyUser()
    }

    private fun looksLikeFido(uri: String): Boolean =
        uri.startsWith("fido:", ignoreCase = true) || uri.startsWith("FIDO:/")

    /** Single biometric prompt = approval + user verification for the cross-device sign-in. */
    private fun verifyUser() {
        val canAuth = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.e(TAG, "Strong biometric unavailable: $canAuth")
            finish()
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    requestPermissionsThenStart()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.d(TAG, "Biometric error $errorCode: $errString")
                    finish()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.cable_prompt_title))
                .setSubtitle(getString(R.string.cable_prompt_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(getString(UiR.string.cancel))
                .build()
        )
    }

    private fun requestPermissionsThenStart() {
        val needed = buildList {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) startSessionAndFinish()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startSessionAndFinish() {
        val uri = fidoUri
        if (uri != null) {
            val intent = Intent(this, CableService::class.java).apply {
                putExtra(CableService.EXTRA_FIDO_URI, uri)
                putExtra(CableService.EXTRA_USER_VERIFIED, true)
            }
            ContextCompat.startForegroundService(this, intent)
        }
        finish()
    }

    companion object {
        private const val TAG = "CableActivity"
    }
}
