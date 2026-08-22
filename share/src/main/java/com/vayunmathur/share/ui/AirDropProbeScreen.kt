package com.vayunmathur.share.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.share.R
import com.vayunmathur.share.Route
import com.vayunmathur.share.platform.discovery.apple.AirDropProbe
import com.vayunmathur.share.platform.discovery.apple.AirDropProbeReport

/** Page wrapper for [AirDropProbeScreen], with the top bar's back button wired to [backStack]. */
@Composable
fun AirDropProbePage(backStack: NavBackStack<Route>) {
    AppScaffold(
        title = stringResource(R.string.share_airdrop_probe),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        AirDropProbeScreen(modifier = Modifier.fillMaxSize().padding(padding))
    }
}

/**
 * Diagnostic screen for [AirDropProbe].
 *
 * Exists so the probe can be run on a real handset next to a real iPhone and the result read
 * off the screen instead of out of logcat. Deliberately plain: it renders the report verbatim,
 * including raw hex, because the point is to see exactly what arrived.
 */
@Composable
fun AirDropProbeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    val probe = remember { AirDropProbe(context) }

    // produceState keyed on `running` is what ties the radios to the toggle: flipping it off
    // cancels the producer, which cancels the probe flow, which stops the BLE scan and P2P
    // discovery. Leaving those running after the user stopped the probe would drain the
    // battery invisibly.
    val report by produceState<AirDropProbeReport?>(initialValue = null, running) {
        if (!running) {
            value = null
            return@produceState
        }
        probe.run().collect { value = it }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.share_airdrop_probe_idle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        stringResource(R.string.share_airdrop_probe_howto),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (running) {
                            Button(onClick = { running = false }) {
                                Text(stringResource(R.string.share_airdrop_probe_stop))
                            }
                            CircularProgressIndicator()
                        } else {
                            OutlinedButton(onClick = { running = true }) {
                                Text(stringResource(R.string.share_airdrop_probe_start))
                            }
                        }
                    }
                }
            }
        }

        val current = report
        if (current == null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.share_airdrop_probe_none),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            return@LazyColumn
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    current.summary().trimEnd(),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        item {
            ProbeSection(stringResource(R.string.share_airdrop_probe_beacons)) {
                if (current.appleBeacons.isEmpty()) {
                    Text(
                        stringResource(R.string.share_airdrop_probe_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                current.appleBeacons.forEach { beacon ->
                    val label = if (beacon.isAirDropping) "AIRDROP" else "apple"
                    Text(
                        "$label ${beacon.address} rssi=${beacon.rssi}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (beacon.isAirDropping) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                    beacon.tlvs.forEach { tlv ->
                        Text(
                            "  $tlv",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        item {
            ProbeSection(stringResource(R.string.share_airdrop_probe_p2p)) {
                val reason = current.p2p.unavailableReason
                if (reason != null) {
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (current.p2p.peers.isEmpty() && reason == null) {
                    Text(
                        stringResource(R.string.share_airdrop_probe_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                current.p2p.peers.values.forEach { peer ->
                    Text(
                        "peer \"${peer.deviceName}\" ${peer.deviceAddress} ${peer.statusName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                current.p2p.services.values.forEach { svc ->
                    Text(
                        "svc ${svc.instanceName} ${svc.registrationType} ${svc.txt}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        item {
            ProbeSection(stringResource(R.string.share_airdrop_probe_mdns)) {
                if (current.mdns.isEmpty()) {
                    Text(
                        stringResource(R.string.share_airdrop_probe_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                current.mdns.forEach { record ->
                    Text(
                        "${record.serviceType} \"${record.serviceName}\" " +
                            if (record.resolved) "${record.host}:${record.port}" else "(unresolved)",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProbeSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
