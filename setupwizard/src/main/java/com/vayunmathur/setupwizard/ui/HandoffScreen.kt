package com.vayunmathur.setupwizard.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

private const val TAG = "HandoffScreen"

/**
 * A step with no interface of its own, which exists only to run somebody else's: Wi-Fi setup,
 * lock-screen enrolment, the updater's security-preview settings.
 *
 * Nothing is drawn. The other activity covers the screen for as long as the step lasts, and
 * putting a spinner behind it would only be visible in the moment it is being replaced.
 *
 * [intent] is evaluated once, on entry, and returning null means the step does not apply to
 * this device - nothing handles it, or it has already been done. That is not an error: the
 * step reports [onUnavailable] and the flow moves on, which is also what happens if launching
 * throws. The upstream wizard let that exception escape, which on an image missing one of
 * these targets is a device that cannot finish first boot.
 */
@Composable
fun HandoffScreen(
    intent: () -> Intent?,
    onCancelled: () -> Unit,
    onCompleted: () -> Unit,
    onUnavailable: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_CANCELED) onCancelled() else onCompleted()
    }

    // Not saveable on purpose. The Activity handles its own configuration changes, so nothing
    // short of process death loses this - and after process death the step genuinely has not
    // launched anything. A value that survived when the composition did not would leave the
    // user on a blank screen with no way forward.
    var launched by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (launched) return@LaunchedEffect
        launched = true
        val target = intent()
        if (target == null) {
            onUnavailable()
            return@LaunchedEffect
        }
        try {
            launcher.launch(target)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "nothing handled ${target.action}; skipping the step", e)
            onUnavailable()
        }
    }
}
