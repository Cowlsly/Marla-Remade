package com.vayunmathur.vpn.ui.components

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.vpn.R

/** One selectable row: an installed app the user can route around the tunnel. */
internal data class BypassApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

/**
 * Android has no public API for reading the "Block connections without VPN" flag, so this
 * states the interaction plainly instead of trying to detect it.
 */
@Composable
internal fun LockdownWarning() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.bypass_lockdown_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = { openVpnSettings(context) }) {
                Text(stringResource(R.string.open_system_vpn_settings))
            }
        }
    }
}

/** Draws a PackageManager Drawable without pulling in an image-loading dependency. */
@Composable
internal fun AppIconImage(icon: Drawable?) {
    if (icon == null) {
        Box(Modifier.size(40.dp))
        return
    }
    Canvas(Modifier.size(40.dp)) {
        drawIntoCanvas { canvas ->
            icon.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            icon.draw(canvas.nativeCanvas)
        }
    }
}

internal fun loadApps(pm: PackageManager, selfPackage: String): List<BypassApp> {
    val installed = runCatching {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
    }.getOrDefault(emptyList())

    return installed
        .asSequence()
        .filter { it.packageName != selfPackage }
        // Only apps that can actually use the network; the rest would be noise.
        .filter { pm.checkPermission(android.Manifest.permission.INTERNET, it.packageName) == PackageManager.PERMISSION_GRANTED }
        .map { info: ApplicationInfo ->
            BypassApp(
                packageName = info.packageName,
                label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(info.packageName),
                icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
            )
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

internal fun openVpnSettings(context: android.content.Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: Exception) {}
    }
}
