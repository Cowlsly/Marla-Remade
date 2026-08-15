package com.vayunmathur.vpn.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.vpn.R
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.service.VpnTunnelService
import com.vayunmathur.vpn.util.VpnViewModel
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun ConfigListPage(backStack: NavBackStack<Route>, vm: VpnViewModel) {
    val configs by vm.configs.collectAsState()
    val connectingId by vm.connectingId.collectAsState()
    val status by vm.status.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val needActivityMsg = stringResource(R.string.need_activity_to_grant_vpn_permission)

    // The only way to add a tunnel is opening a .conf file via SAF.
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importFromUri(context, uri)
    }

    LaunchedEffect(status) {
        status?.let { snackbar.showSnackbar(it); vm.clearStatus() }
    }

    ConfigListContent(
        configs = configs,
        connectingId = connectingId,
        // A tunnel only counts as connected once the service is actually up; between the tap
        // and that point the row shows the spinner instead.
        activeId = connectingId?.takeIf { VpnTunnelService.isRunning },
        snackbar = snackbar,
        // Open a .conf file — only import option per user request.
        onImport = { filePicker.launch(arrayOf("*/*")) },
        onToggleConnect = { cfg ->
            if (connectingId != null && VpnTunnelService.isRunning) {
                vm.stopVpn()
            } else {
                if (activity != null) vm.startVpn(activity, cfg)
                else scope.launch {
                    snackbar.showSnackbar(needActivityMsg)
                }
            }
        },
        onOpen = { cfg -> backStack.add(Route.Detail(cfg.id)) },
        onDelete = { cfg -> vm.delete(cfg) },
    )
}

/**
 * The tunnel list, with no ViewModel and no back stack so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@Composable
fun ConfigListContent(
    configs: List<VpnConfig>,
    connectingId: Long?,
    activeId: Long?,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onImport: () -> Unit = {},
    onToggleConnect: (VpnConfig) -> Unit = {},
    onOpen: (VpnConfig) -> Unit = {},
    onDelete: (VpnConfig) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vpn_wireguard_gotatun)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onImport) {
                IconAdd()
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (configs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.no_tunnels_yet), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.add_a_connection_by_opening_a_conf_file), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onImport) { Text(stringResource(R.string.open_conf_file)) }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(configs, key = { it.id }) { cfg ->
                        ConfigRow(
                            cfg,
                            isActive = activeId == cfg.id,
                            isConnecting = connectingId == cfg.id,
                            onConnect = { onToggleConnect(cfg) },
                            onClick = { onOpen(cfg) },
                            onDelete = { onDelete(cfg) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigRow(
    cfg: VpnConfig,
    isActive: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = if (isActive) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(cfg.name.ifBlank { "Unnamed" }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(cfg.peerEndpoint, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isConnecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect) {
                    Text(if (isActive) "Disconnect" else "Connect")
                }
                androidx.compose.material3.IconButton(onClick = onDelete) { IconDelete() }
            }
            if (isActive) {
                Text(stringResource(R.string.connected_via_gotatun_wireguard), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(stringResource(R.string.address, cfg.address), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.allowed, cfg.peerAllowedIPs), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
