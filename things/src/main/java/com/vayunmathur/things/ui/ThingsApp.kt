package com.vayunmathur.things.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.things.platform.BleManager
import androidx.compose.ui.res.stringResource
import com.vayunmathur.things.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThingsApp(
    totalMl: Int,
    goalMl: Int,
    messages: List<String>,
    connectionState: String,
    scanning: Boolean,
    discoveredDevices: List<BleManager.BleDevice>,
    onScanClick: () -> Unit,
    onDeviceClick: (BleManager.BleDevice) -> Unit,
    onDisconnectClick: () -> Unit
) {
    AppScaffold(
        title = stringResource(R.string.hydration),
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HydrationCard(totalMl = totalMl, goalMl = goalMl)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(connectionState, style = MaterialTheme.typography.titleMedium)
                if (connectionState == "Connected") {
                    OutlinedButton(onClick = onDisconnectClick) { Text(stringResource(R.string.disconnect)) }
                } else {
                    Button(onClick = onScanClick, enabled = !scanning) { Text(stringResource(R.string.scan)) }
                }
            }

            if (discoveredDevices.isNotEmpty() && connectionState != "Connected") {
                Text(stringResource(R.string.devices_found), style = MaterialTheme.typography.labelLarge)
                discoveredDevices.forEach { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceClick(device) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (messages.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.today_s_drinks), style = MaterialTheme.typography.titleSmall)
            }

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(messages) { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HydrationCard(totalMl: Int, goalMl: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.today), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.ml, totalMl),
                style = MaterialTheme.typography.displayMedium
            )
            Spacer(Modifier.height(12.dp))
            val progress = (totalMl.toFloat() / goalMl.coerceAtLeast(1)).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.goal_ml, goalMl),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
