package com.vayunmathur.measure.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsDivider
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.measure.Route
import com.vayunmathur.measure.data.model.MeasurementKind
import com.vayunmathur.measure.data.model.SavedMeasurement
import com.vayunmathur.measure.data.model.UnitSystem
import com.vayunmathur.measure.domain.MeasureNative
import com.vayunmathur.measure.domain.Units
import com.vayunmathur.measure.ui.DiagnosticsActions
import com.vayunmathur.measure.ui.DiagnosticsUiState
import com.vayunmathur.measure.ui.MeasureViewModel
import com.vayunmathur.measure.ui.SavedActions
import com.vayunmathur.measure.ui.SavedUiState
import com.vayunmathur.measure.ui.SettingsActions
import com.vayunmathur.measure.ui.SettingsUiState

@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: MeasureViewModel) {
    val state by viewModel.settings.collectAsState()
    SettingsContent(
        state = state,
        actions = viewModel,
        onBack = { backStack.pop() },
        onOpenDiagnostics = { backStack.add(Route.Diagnostics) },
        onOpenSaved = { backStack.add(Route.Saved) },
    )
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenSaved: () -> Unit = {},
) {
    AppScaffold(title = "Settings", onNavigateBack = onBack) { padding ->
        Column(
            // The list is taller than the dialog on most phones; without this the
            // advanced section at the bottom is simply unreachable.
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(title = "Units") {
                SettingsSwitchRow(
                    title = "Imperial units",
                    supportingText = "Feet and inches instead of metres",
                    checked = state.unitSystem == UnitSystem.Imperial,
                    onCheckedChange = {
                        actions.setUnitSystem(if (it) UnitSystem.Imperial else UnitSystem.Metric)
                    },
                )
                SettingsSwitchRow(
                    title = "Fractional inches",
                    supportingText = "Show 3/16\" rather than 0.19\"",
                    checked = state.useFractionalInches,
                    enabled = state.unitSystem == UnitSystem.Imperial,
                    onCheckedChange = actions::setUseFractionalInches,
                )
            }
            SettingsDivider()
            SettingsSection(title = "Compass") {
                SettingsSwitchRow(
                    title = "Use true north",
                    supportingText = "Corrects for magnetic declination at your location",
                    checked = state.useTrueNorth,
                    onCheckedChange = actions::setUseTrueNorth,
                )
            }
            SettingsDivider()
            SettingsSection(title = "Calibration") {
                SettingsRow(
                    title = "Level",
                    supportingText = if (state.levelCalibrated) {
                        "Zeroed against a reference surface"
                    } else {
                        "Using raw gravity reading"
                    },
                    enabled = state.levelCalibrated,
                    onClick = actions::clearLevelCalibration,
                    trailingContent = { if (state.levelCalibrated) Text("Clear") },
                )
            }
            SettingsDivider()
            SettingsSection(title = "General") {
                SettingsSwitchRow(
                    title = "Haptic feedback",
                    checked = state.hapticsEnabled,
                    onCheckedChange = actions::setHapticsEnabled,
                )
                SettingsSwitchRow(
                    title = "Keep screen on",
                    supportingText = "While a measuring tool is open",
                    checked = state.keepScreenOn,
                    onCheckedChange = actions::setKeepScreenOn,
                )
                SettingsRow(title = "Saved measurements", onClick = onOpenSaved)
            }
            SettingsDivider()
            SettingsSection(title = "Advanced") {
                SettingsSwitchRow(
                    title = "Tracking diagnostics",
                    supportingText = "Feature counts, IMU rate and clock skew",
                    checked = state.showDiagnostics,
                    onCheckedChange = actions::setShowDiagnostics,
                )
                if (state.showDiagnostics) {
                    SettingsRow(title = "Open diagnostics", onClick = onOpenDiagnostics)
                }
            }
        }
    }
}

@Composable
fun SavedMeasurementsPage(backStack: NavBackStack<Route>, viewModel: MeasureViewModel) {
    val state by viewModel.saved.collectAsState()
    SavedMeasurementsContent(
        state = state,
        actions = viewModel,
        onBack = { backStack.pop() },
    )
}

@Composable
fun SavedMeasurementsContent(
    state: SavedUiState,
    actions: SavedActions,
    onBack: () -> Unit = {},
) {
    AppScaffold(title = "Saved", onNavigateBack = onBack) { padding ->
        if (state.measurements.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No saved measurements", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Measure something in AR and tap save",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(state.measurements, key = { it.id }) { m ->
                    SettingsRow(
                        title = m.label,
                        supportingText = formatMeasurement(m, state.unitSystem),
                        trailingContent = {
                            IconButton(onClick = { actions.delete(m.id) }) { IconDelete() }
                        },
                    )
                }
            }
        }
    }
}

private fun formatMeasurement(m: SavedMeasurement, system: UnitSystem): String = when (m.kind) {
    MeasurementKind.Area -> Units.formatArea(m.value, system)
    MeasurementKind.Angle -> Units.formatAngle(m.value)
    else -> Units.formatLength(m.value, system)
}

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

