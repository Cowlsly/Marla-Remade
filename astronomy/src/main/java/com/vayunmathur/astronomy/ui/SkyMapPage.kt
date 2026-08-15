package com.vayunmathur.astronomy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import com.vayunmathur.astronomy.R
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.domain.projection.ViewState
import com.vayunmathur.astronomy.platform.AstronomyViewModel
import com.vayunmathur.astronomy.platform.ConstellationMode
import com.vayunmathur.astronomy.platform.SkyMapActions
import com.vayunmathur.astronomy.platform.SkyMapUiState
import com.vayunmathur.astronomy.ui.components.CameraBackground
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.ExperimentalTime
import androidx.compose.ui.res.stringResource

// Astronomy scrubs across a wide time range, so the relative jumps are hours/days
// rather than the minutes/seconds FindFamily uses.
private val AstronomyHistorySteps = listOf(
    HistoryStep("-1d", -86_400L),
    HistoryStep("-4h", -14_400L),
    HistoryStep("-1h", -3_600L),
    HistoryStep("+1h", 3_600L),
    HistoryStep("+4h", 14_400L),
    HistoryStep("+1d", 86_400L),
)

/** Binds [AstronomyViewModel] to the stateless [SkyMapScreen]. */
@OptIn(ExperimentalTime::class)
@Composable
fun SkyMapPage(backStack: NavBackStack<Route>, viewModel: AstronomyViewModel) {
    val visibleSky by viewModel.visibleSky.collectAsState()
    val fov by viewModel.fovDeg.collectAsState()
    val constMode by viewModel.constellationMode.collectAsState()
    val showGrid by viewModel.showGrid.collectAsState()
    val showDeep by viewModel.showDeepSky.collectAsState()
    val showPlanets by viewModel.showPlanets.collectAsState()
    val nightMode by viewModel.nightMode.collectAsState()
    // removed still mode – always tracking
    val deviceOrient by viewModel.deviceOrientation.collectAsState()
    val trajectory by viewModel.trajectory.collectAsState()
    val selectedId by viewModel.selectedObjectId.collectAsState()
    val viewCenter by viewModel.viewCenter.collectAsState()
    val simTime by viewModel.simTime.collectAsState()
    val isLive by viewModel.isLive.collectAsState()

    val (centerAz, centerAlt) = remember(viewCenter, deviceOrient) { viewModel.resolveCenter() }
    val rotation = remember(viewCenter, deviceOrient) { viewModel.resolveRotation() }

    SkyMapScreen(
        backStack = backStack,
        state = SkyMapUiState(
            visibleSky = visibleSky,
            simTime = simTime,
            timeZone = TimeZone.currentSystemDefault(),
            isLive = isLive,
            fovDeg = fov,
            centerAzRad = centerAz,
            centerAltRad = centerAlt,
            rotationRad = rotation,
            constellationMode = constMode,
            showGrid = showGrid,
            showDeepSky = showDeep,
            showPlanets = showPlanets,
            nightMode = nightMode,
            trajectory = trajectory,
            selectedObjectId = selectedId,
        ),
        actions = viewModel,
    )
}

/**
 * The sky map, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@Composable
fun SkyMapScreen(
    backStack: NavBackStack<Route>,
    state: SkyMapUiState,
    actions: SkyMapActions,
    /**
     * Seed for the screen's own UI-only state (whether the camera AR overlay is up). The
     * app always takes the default; a preview can set it to capture that mode directly.
     */
    initialCameraOn: Boolean = false,
) {
    var screenW by remember { mutableFloatStateOf(1080f) }
    var screenH by remember { mutableFloatStateOf(1920f) }
    // Camera feed replaces the sky background in place (AR overlay) instead of a
    // separate page.
    var cameraOn by remember { mutableStateOf(initialCameraOn) }

    val viewState = remember(state.centerAzRad, state.centerAltRad, state.rotationRad, state.fovDeg, screenW, screenH) {
        ViewState(state.centerAzRad, state.centerAltRad, state.fovDeg, screenW, screenH, state.rotationRad)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { backStack.add(Route.Search) }) { IconSearch() }
                    IconButton(onClick = { cameraOn = !cameraOn }) {
                        if (cameraOn) IconCameraOff() else IconCamera()
                    }
                    IconButton(onClick = { backStack.add(Route.Settings) }) { IconSettings() }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize()
                .onSizeChanged { sz -> screenW = sz.width.toFloat(); screenH = sz.height.toFloat() }
        ) {
            if (cameraOn) {
                CameraBackground(Modifier.fillMaxSize()) { cameraOn = false }
            }

            SkyCanvas(
                visibleSky = state.visibleSky,
                viewState = viewState,
                showConstellationLines = state.constellationMode != ConstellationMode.OFF,
                showConstellationArt = state.constellationMode == ConstellationMode.LINES_AND_ART,
                showGrid = state.showGrid,
                showDeepSky = state.showDeepSky,
                showPlanets = state.showPlanets,
                transparentBackground = cameraOn,
                trajectory = state.trajectory,
                selectedId = state.selectedObjectId,
                onPan = { _, _ -> /* disabled – always tracks phone */ },
                onZoom = { actions.setFov(it) },
                onTap = { _ -> },
                onObjectTap = { id -> actions.selectObject(id) },
                onObjectOpen = { id -> actions.selectObject(id); backStack.add(Route.ObjectDetail(id)) },
                modifier = Modifier.fillMaxSize()
            )

            HistoryScrubber(backStack, state, actions)

            if (state.nightMode) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x44FF0000)))
            }
        }
    }
}

/**
 * Bottom-center time picker sharing its look/behaviour with FindFamily's history
 * scrubber (see [com.vayunmathur.library.ui.HistoryScrubberCard]). Seeds from the
 * current sim time; while in "Now" mode the sky tracks live, and any interaction
 * switches to the chosen simulated instant.
 */
@OptIn(ExperimentalTime::class)
@Composable
private fun BoxScope.HistoryScrubber(
    backStack: NavBackStack<Route>,
    state: SkyMapUiState,
    actions: SkyMapActions,
) {
    val scrubber = rememberHistoryScrubberState(
        initialInstant = state.simTime,
        initialNowMode = state.isLive,
        timeZone = state.timeZone
    )

    LaunchedEffect(scrubber.instant, scrubber.nowMode) {
        actions.setTime(scrubber.instant, live = scrubber.nowMode)
    }

    HistoryScrubberCard(
        state = scrubber,
        steps = AstronomyHistorySteps,
        onDateChipClick = { backStack.add(Route.HistoryDatePicker(scrubber.date)) }
    )

    ResultEffect<LocalDate>("AstroHistoryDatePicker") { scrubber.setDate(it) }
}
