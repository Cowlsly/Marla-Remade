package com.vayunmathur.measure.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.SettingsDivider
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.measure.Route
import com.vayunmathur.measure.data.model.UnitSystem
import com.vayunmathur.measure.platform.MeasureViewModel
import com.vayunmathur.measure.platform.SettingsActions
import com.vayunmathur.measure.platform.SettingsUiState

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

