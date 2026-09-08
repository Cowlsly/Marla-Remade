package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.vayunmathur.library.ui.Switch
import kotlinx.coroutines.launch

/**
 * The opt-in for keeping this phone findable after it is switched off.
 *
 * Two rules this deliberately follows, because the thing being switched on is a radio that keeps
 * transmitting from a device the user believes is off:
 *
 * - **The explanation is always visible, not behind an info affordance.** Someone scrolling past
 *   should learn what the phone does without having to be curious first.
 * - **The three-day limit is shown whether the switch is on or off.** It is the most surprising
 *   property of the feature and the easiest one to leave out, so it is not conditional on
 *   anything. Users who switch this on and then lose the phone a week later should have been told
 *   up front, not left to conclude it was broken.
 */
@Composable
fun PoweredOffFindingSetting(peers: List<User>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var supported by remember { mutableStateOf<Boolean?>(null) }
    var enabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        supported = PoweredOffBeacon.isSupported(context)
        enabled = PoweredOffBeacon.isEnabled(context)
    }

    // Null while the check is still running. Rendering the switch first and then hiding it would
    // flash a control the user cannot have.
    if (supported == null) return

    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        if (supported == false) {
            Text(
                stringResource(R.string.poweroff_finding_title),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.poweroff_finding_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.poweroff_finding_title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(enabled, { on ->
                enabled = on
                scope.launch { PoweredOffBeacon.setEnabled(context, on) }
            })
        }
        Text(
            stringResource(R.string.poweroff_finding_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.poweroff_finding_limit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (enabled) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.poweroff_finding_off_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Only once the beacon is on: there is nothing to grant access to before that, and
            // offering the choice first would imply the phone is already findable.
            Spacer(Modifier.height(8.dp))
            PoweredOffGrantSetting(peers)
        }
    }
}
