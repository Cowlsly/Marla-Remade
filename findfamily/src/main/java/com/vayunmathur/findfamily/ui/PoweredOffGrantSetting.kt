package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.platform.PoweredOffBeacon
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.launch

/**
 * Who, besides this phone, may locate it once it is switched off.
 *
 * ## Why this is a list of people and not one more switch
 * A powered-off phone cannot decrypt its own sightings, so the feature is inert until somebody
 * else holds a key. The tempting shortcut is to send that key to everyone the user already shares
 * location with, and it is the wrong one: that roster means "may see where I am now", which the
 * user can withdraw at any moment by turning sharing off. A recovery key cannot be withdrawn in
 * that sense — see [R.string.poweroff_grant_revoke_warning] — so consent for the first is not
 * consent for the second. Everyone starts off.
 *
 * ## Why both warnings are always on screen
 * The same rule the switch above follows, for the same reason, and here it matters more. Two
 * things about this feature cannot be engineered away, so the only honest place for them is the
 * screen where the user decides:
 *
 *  - **Choosing someone lets them track the phone**, not merely read where it has been. They need
 *    the beacon secret to know which handles to fetch, and that same secret derives the phone's
 *    identifiers. One key, both capabilities, no way to split them.
 *  - **Removing someone is forward-only.** Revoking rotates both the recovery keypair and the
 *    beacon secret, so it does end the tracking above — but only from that moment. Sightings the
 *    peer already fetched stay readable to them and nothing can change that.
 *
 * Neither is a footnote and neither is behind an info icon. A user who discovers the first one
 * afterwards was misled by this screen, not by the Bluetooth stack.
 */
@Composable
fun PoweredOffGrantSetting(peers: List<User>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(emptySet<Long>()) }

    LaunchedEffect(Unit) { granted = PoweredOffBeacon.recoveryGrantees(context) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.poweroff_grant_title),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.poweroff_grant_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.poweroff_grant_tracking_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.poweroff_grant_revoke_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        if (peers.isEmpty()) {
            Text(
                stringResource(R.string.poweroff_grant_no_peers),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        for (peer in peers) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(peer.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(peer.id in granted, { on ->
                    // Optimistic, then reconciled from the store once the grant or the rotation
                    // has actually been written. Delivery can fail — the peer may be offline —
                    // and the roster is what decides who gets the next redistribution, so the
                    // switch follows the roster rather than the send.
                    granted = if (on) granted + peer.id else granted - peer.id
                    scope.launch {
                        if (on) PoweredOffBeacon.grantRecovery(context, peer.id)
                        else PoweredOffBeacon.revokeRecovery(context, peer.id)
                        granted = PoweredOffBeacon.recoveryGrantees(context)
                    }
                })
            }
        }
    }
}
