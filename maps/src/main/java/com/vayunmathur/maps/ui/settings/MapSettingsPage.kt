package com.vayunmathur.maps.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconClearNight
import com.vayunmathur.library.ui.IconMap
import com.vayunmathur.library.ui.IconRuler
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconVolumeUp
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSelectRow
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.maps.R
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.DistanceUnit
import com.vayunmathur.maps.data.ThemeMode
import com.vayunmathur.maps.util.MapSettingsViewModel
import com.vayunmathur.library.util.NavBackStack

/**
 * Maps settings screen (P6): units, map theme / day-night, and the voice-guidance
 * toggle, plus entry points to the saved-places screen and the offline-maps
 * screen. Built entirely from the shared `library/ui` settings components (no raw
 * Scaffold) and backed by [MapSettingsViewModel] (DataStore).
 *
 * Wiring of the persisted values into behaviour lives at the point of use:
 *  - theme → `MainActivity` passes it to `DynamicTheme`,
 *  - voice guidance → `NavigationService` gates TTS on it,
 *  - distance unit → persisted here for display code to consume.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSettingsPage(backStack: NavBackStack<Route>, viewModel: MapSettingsViewModel) {
    val unit by viewModel.distanceUnit.collectAsState()
    val theme by viewModel.themeMode.collectAsState()
    val voice by viewModel.voiceGuidance.collectAsState()

    // Pre-resolve option labels — the SettingsSelectRow `label` lambda is a plain
    // (non-@Composable) function, so stringResource can't be called inside it.
    val unitLabels = mapOf(
        DistanceUnit.KILOMETERS to stringResource(R.string.settings_units_km),
        DistanceUnit.MILES to stringResource(R.string.settings_units_mi),
    )
    val themeLabels = mapOf(
        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
    )

    AppScaffold(
        title = stringResource(R.string.settings_title),
        backStack = backStack,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_display)) {
                SettingsSelectRow(
                    title = stringResource(R.string.settings_units),
                    selected = unit,
                    options = DistanceUnit.entries,
                    label = { unitLabels.getValue(it) },
                    onSelect = { viewModel.setDistanceUnit(it) },
                    leadingContent = { IconRuler() },
                )
                SettingsSelectRow(
                    title = stringResource(R.string.settings_theme),
                    selected = theme,
                    options = ThemeMode.entries,
                    label = { themeLabels.getValue(it) },
                    onSelect = { viewModel.setThemeMode(it) },
                    leadingContent = { IconClearNight() },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_navigation)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_voice_guidance),
                    supportingText = stringResource(R.string.settings_voice_guidance_desc),
                    checked = voice,
                    onCheckedChange = { viewModel.setVoiceGuidance(it) },
                    leadingContent = { IconVolumeUp() },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_places)) {
                com.vayunmathur.library.ui.SettingsRow(
                    title = stringResource(R.string.saved_places_title),
                    onClick = { backStack.add(Route.SavedPlacesPage) },
                    leadingContent = { IconStar() },
                )
                com.vayunmathur.library.ui.SettingsRow(
                    title = stringResource(R.string.downloaded_maps),
                    onClick = { backStack.add(Route.DownloadedMapsPage) },
                    leadingContent = { IconMap() },
                )
            }
        }
    }
}
