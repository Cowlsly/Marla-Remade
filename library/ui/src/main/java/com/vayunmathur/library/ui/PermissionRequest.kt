package com.vayunmathur.library.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Walks the [ContextWrapper] chain to the hosting [Activity], or null. */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * A runtime-permission request that recovers from permanent denial.
 *
 * Returns a lambda to invoke on the button press: if the permission is already
 * granted it reports success; otherwise it launches the system request; and if
 * the request returns denied with no rationale — i.e. the user picked
 * "don't ask again" or it was already permanently denied — it opens the app's
 * settings so they can grant it there. This fixes the suite-wide bug where
 * pressing a permission button again after a permanent denial did nothing (the
 * system returns denied immediately without showing a dialog).
 *
 * [onResult] receives whether the permission ended up granted (settings changes
 * are picked up on the next resume/check, not reported here).
 */
@Composable
fun rememberPermissionRequest(
    permission: String,
    onResult: (granted: Boolean) -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            val activity = context.findActivity()
            if (activity == null ||
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            ) {
                openAppSettings(context)
            }
        }
        onResult(granted)
    }
    return {
        if (ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onResult(true)
        } else {
            launcher.launch(permission)
        }
    }
}

/**
 * [rememberPermissionRequest] for a set of permissions. Reports granted only when
 * ALL are granted; if any comes back permanently denied (denied + no rationale),
 * opens the app's settings.
 */
@Composable
fun rememberMultiplePermissionRequest(
    permissions: Array<String>,
    onResult: (allGranted: Boolean) -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val allGranted = result.values.all { it }
        if (!allGranted) {
            val activity = context.findActivity()
            val anyPermanentlyDenied = result.any { (perm, granted) ->
                !granted && (activity == null ||
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm))
            }
            if (anyPermanentlyDenied) openAppSettings(context)
        }
        onResult(allGranted)
    }
    return {
        if (permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            onResult(true)
        } else {
            launcher.launch(permissions)
        }
    }
}
