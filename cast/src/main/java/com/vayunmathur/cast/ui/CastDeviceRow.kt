package com.vayunmathur.cast.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.CastDeviceKind
import com.vayunmathur.library.ui.IconCastConnected
import com.vayunmathur.library.ui.IconSpeaker
import com.vayunmathur.library.ui.IconTv
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * One discovered receiver.
 *
 * The leading icon is the device's own [CastDeviceKind], which comes out of the mDNS capability
 * bitmask - a Nest speaker and a Google TV look nothing alike to the user, and showing the same
 * icon for both makes the list unreadable in a house with several of each.
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
            when {
                isConnected -> IconCastConnected(tint = MaterialTheme.colorScheme.primary)
                device.kind == CastDeviceKind.Tv -> IconTv()
                else -> IconSpeaker()
            }
        },
        supportingContent = subtitle(device)?.let { { Text(it) } },
        content = { Text(device.friendlyName) },
    )
}

/**
 * Model plus status, but only the parts that exist: an idle receiver publishes no `rs`, and an
 * empty second line makes the rows different heights for no reason.
 */
private fun subtitle(device: CastDevice): String? = listOfNotNull(
    device.model?.takeIf { it.isNotBlank() },
    device.statusText?.takeIf { it.isNotBlank() },
).takeIf { it.isNotEmpty() }?.joinToString(" - ")
