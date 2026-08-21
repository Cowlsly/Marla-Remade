package com.vayunmathur.cast.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.library.ui.IconCastConnected
import com.vayunmathur.library.ui.IconTv
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * One discovered receiver.
 *
 * Always a TV icon: every device answering `_macast._tcp` is running our receiver, so the speaker and
 * group distinctions the Cast version drew - which came from a capability bitmask and decided which of
 * four receiver app ids to launch - no longer describe anything.
 */
@Composable
fun CastDeviceRow(
    device: CastDevice,
    isConnected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        leadingContent = {
            if (isConnected) {
                IconCastConnected(tint = MaterialTheme.colorScheme.primary)
            } else {
                IconTv()
            }
        },
        supportingContent = { Text(stringResource(R.string.cast_device_subtitle, device.host)) },
        content = { Text(device.friendlyName) },
    )
}
