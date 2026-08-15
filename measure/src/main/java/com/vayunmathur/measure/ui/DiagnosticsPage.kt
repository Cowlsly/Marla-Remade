package com.vayunmathur.measure.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsDivider
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.measure.Route
import com.vayunmathur.measure.platform.DiagnosticsActions
import com.vayunmathur.measure.platform.DiagnosticsUiState
import com.vayunmathur.measure.platform.MeasureViewModel

@Composable
fun DiagnosticsPage(backStack: NavBackStack<Route>, viewModel: MeasureViewModel) {
    val state by viewModel.diagnostics.collectAsState()
    DiagnosticsContent(
        state = state,
        actions = viewModel,
        onBack = { backStack.pop() },
    )
}

@Composable
fun DiagnosticsContent(
    state: DiagnosticsUiState,
    actions: DiagnosticsActions,
    onBack: () -> Unit = {},
) {
    AppScaffold(title = "Diagnostics", onNavigateBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = "Engine") {
                DiagRow("Native engine", if (state.nativeEngineAvailable) "loaded" else "unavailable")
                DiagRow("Landmarks", state.landmarkCount.toString())
                DiagRow("Scale confidence", "%.2f".format(state.scaleConfidence))
            }
            SettingsDivider()
            SettingsSection(title = "Tracking") {
                DiagRow("Features detected", state.featureCount.toString())
                DiagRow("Features tracked", state.trackedCount.toString())
                DiagRow("Frame rate", "%.1f Hz".format(state.frameRateHz))
            }
            SettingsDivider()
            SettingsSection(title = "Sensors") {
                DiagRow("IMU rate", "%.0f Hz".format(state.imuRateHz))
                DiagRow(
                    "Camera/IMU clock skew",
                    "%.1f ms".format(state.timestampSkewMs),
                )
            }
            SettingsDivider()
            SettingsSection(title = "Intrinsics") {
                DiagRow("Focal length", "%.1f px".format(state.focalPx))
                DiagRow(
                    "Principal point",
                    "%.1f, %.1f".format(state.principalPointPx.first, state.principalPointPx.second),
                )
            }
            SettingsDivider()
            SettingsRow(title = "Reset tracking", onClick = actions::resetTracking)
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
