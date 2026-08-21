package com.vayunmathur.astronomy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.astronomy.R
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.platform.AstronomyViewModel
import com.vayunmathur.astronomy.platform.ConstellationMode
import com.vayunmathur.astronomy.platform.SettingsActions
import com.vayunmathur.astronomy.platform.SettingsUiState
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource

/** Binds [AstronomyViewModel] to the stateless [SettingsScreen]. */
@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: AstronomyViewModel) {
    val showConst by viewModel.constellationMode.collectAsState()
    val showGrid by viewModel.showGrid.collectAsState()
    val showDeep by viewModel.showDeepSky.collectAsState()
    val showPlanets by viewModel.showPlanets.collectAsState()
    val showBelow by viewModel.showBelowHorizon.collectAsState()
    val magLimit by viewModel.magLimit.collectAsState()
    val nightMode by viewModel.nightMode.collectAsState()
    val fov by viewModel.fovDeg.collectAsState()
    val observer by viewModel.observer.collectAsState()
    val catalog = viewModel.getCatalog()

    SettingsScreen(
        backStack = backStack,
        state = SettingsUiState(
            constellationMode = showConst,
            showGrid = showGrid,
            showDeepSky = showDeep,
            showPlanets = showPlanets,
            showBelowHorizon = showBelow,
            nightMode = nightMode,
            magLimit = magLimit,
            fovDeg = fov,
            latDeg = observer?.latDeg,
            lonDeg = observer?.lonDeg,
            starCount = catalog.stars.size,
            constellationCount = catalog.constellations.size,
            deepSkyCount = catalog.deepSky.size,
        ),
        actions = viewModel,
    )
}

/**
 * The settings screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@Composable
fun SettingsScreen(backStack: NavBackStack<Route>, state: SettingsUiState, actions: SettingsActions) {
    var latText by remember(state.latDeg) { mutableStateOf(state.latDeg?.toString() ?: "") }
    var lonText by remember(state.lonDeg) { mutableStateOf(state.lonDeg?.toString() ?: "") }

    val offLabel = stringResource(R.string.off)
    val linesLabel = stringResource(R.string.lines_only)
    val linesArtLabel = stringResource(R.string.lines_art)

    AppScaffold(title = stringResource(R.string.settings), onNavigateBack = { backStack.pop() }, scrollBehavior = appBarScrollBehavior()) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SettingsSection(title = stringResource(R.string.display)) {
                SettingsSelectRow(
                    title = stringResource(R.string.constellations),
                    selected = state.constellationMode,
                    options = listOf(ConstellationMode.OFF, ConstellationMode.LINES, ConstellationMode.LINES_AND_ART),
                    label = {
                        when (it) {
                            ConstellationMode.OFF -> offLabel
                            ConstellationMode.LINES -> linesLabel
                            ConstellationMode.LINES_AND_ART -> linesArtLabel
                        }
                    },
                    onSelect = { actions.setShowConstellations(it) },
                )
                SettingsSwitchRow(title = stringResource(R.string.coordinate_grid_whole_sphere), checked = state.showGrid, onCheckedChange = { actions.setShowGrid(it) })
                SettingsSwitchRow(title = stringResource(R.string.show_deep_sky), checked = state.showDeepSky, onCheckedChange = { actions.setShowDeepSky(it) })
                SettingsSwitchRow(title = stringResource(R.string.planets_sun_moon), checked = state.showPlanets, onCheckedChange = { actions.setShowPlanets(it) })
                SettingsSwitchRow(title = stringResource(R.string.show_below_horizon_all_sky), checked = state.showBelowHorizon, onCheckedChange = { actions.setShowBelowHorizon(it) })
                SettingsSwitchRow(title = stringResource(R.string.night_mode), checked = state.nightMode, onCheckedChange = { actions.setNightMode(it) })
            }

            SettingsDivider()

            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.magnitude_limit, state.magLimit.format(1)), style = MaterialTheme.typography.bodyMedium)
                Slider(value = state.magLimit, onValueChange = { actions.setMagLimit(it) }, valueRange = 1f..7f)

                Text(stringResource(R.string.fov, state.fovDeg.toInt()), style = MaterialTheme.typography.bodyMedium)
                Slider(value = state.fovDeg, onValueChange = { actions.setFov(it) }, valueRange = 10f..120f)
            }

            SettingsDivider()

            SettingsSection(title = stringResource(R.string.location)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = latText, onValueChange = { latText = it }, label = { Text(stringResource(R.string.latitude_deg)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = lonText, onValueChange = { lonText = it }, label = { Text(stringResource(R.string.longitude_deg)) }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        val lat = latText.toDoubleOrNull(); val lon = lonText.toDoubleOrNull()
                        if (lat != null && lon != null) actions.setManualLocation(lat, lon)
                    }) { Text(stringResource(R.string.save_location)) }
                    Button(onClick = { actions.refreshLocation() }) { Text(stringResource(R.string.use_current_location)) }
                }
            }

            SettingsDivider()

            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.notes_true_north_correction_via_geomagne), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.catalog_stars_constellations_dso, state.starCount, state.constellationCount, state.deepSkyCount), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun Float.format(d: Int): String = "%.${d}f".format(this)
