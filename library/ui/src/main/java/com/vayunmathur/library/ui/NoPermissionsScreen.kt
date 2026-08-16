package com.vayunmathur.library.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * The screen shown in place of content that a permission is gating.
 *
 * Twelve apps gate their UI on a grant and none of them used the shared
 * screen - calendar, contacts and findfamily each defined their own function
 * with this very name. The reason is that the old one could only ever request
 * an `Array<String>` of runtime permissions, which left out Health Connect
 * (its own contract) and everything behind a Settings intent - all-files
 * access, exact alarms, VPN consent, notification listeners - which cannot be
 * requested that way at all.
 *
 * So this takes no permissions and launches nothing. The caller owns the
 * contract and passes [onRequest]; all that is shared is the layout, which is
 * the part that was drifting. [PermissionsChecker] below still covers the
 * ordinary runtime case in one line.
 *
 * [onOpenSettings] is worth wiring up wherever a permission can be permanently
 * denied, since at that point the in-app request silently does nothing and the
 * user has no way forward. [openAppSettings] is the usual implementation.
 */
@Composable
fun PermissionWall(
    title: String,
    actionLabel: String,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
    rationale: String? = null,
    icon: @Composable (() -> Unit)? = null,
    settingsLabel: String? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    EmptyState(
        title = title,
        modifier = modifier,
        message = rationale,
        icon = icon,
        action = {
            Button(onClick = onRequest) { Text(actionLabel) }
            if (onOpenSettings != null && settingsLabel != null) {
                TextButton(onClick = onOpenSettings) { Text(settingsLabel) }
            }
        },
    )
}

/** Opens this app's entry in system settings, for permanently denied permissions. */
fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/**
 * Full-screen [PermissionWall] for ordinary runtime permissions, which
 * requests them itself.
 *
 * Kept for callers that only need the simple case; anything with a different
 * contract should use [PermissionWall] directly.
 */
@Composable
fun NoPermissionsScreen(
    permissions: Array<String>,
    text: String,
    rationale: String? = null,
    // Last so it can still be passed as a trailing lambda.
    setHasPermissions: (Boolean) -> Unit,
) {
    val permissionRequestor = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsResult ->
        setHasPermissions(permissionsResult.values.all { it })
    }
    // Button press recovers from permanent denial by opening app settings; the initial
    // auto-ask below stays a plain request so entering the screen never jumps to settings.
    val requestOrOpenSettings = rememberMultiplePermissionRequest(permissions) { setHasPermissions(it) }
    LaunchedEffect(Unit) {
        permissionRequestor.launch(permissions)
    }
    Scaffold { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            PermissionWall(
                title = text,
                actionLabel = text,
                onRequest = requestOrOpenSettings,
                rationale = rationale,
            )
        }
    }
}

@Composable
fun PermissionsChecker(permissions: Array<String>, text: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(permissions.all {
            ContextCompat.checkSelfPermission(
                context,
                it
            ) == PackageManager.PERMISSION_GRANTED
        })
    }
    if (!hasPermissions) {
        NoPermissionsScreen(permissions, text) { hasPermissions = it }
    } else {
        content()
    }
}
