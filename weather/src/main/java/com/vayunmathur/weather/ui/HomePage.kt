package com.vayunmathur.weather.ui

import androidx.activity.compose.BackHandler
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExpandVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.PullToRefreshBox
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.R
import com.vayunmathur.weather.Route
import com.vayunmathur.weather.ui.components.CurrentWeatherCard
import com.vayunmathur.weather.ui.components.DailyCard
import com.vayunmathur.weather.ui.components.HourlyCard
import com.vayunmathur.weather.ui.components.MainSearchBar
import com.vayunmathur.weather.ui.components.MetricGraphSheet
import com.vayunmathur.weather.ui.components.SelectedDateTimeHeader
import com.vayunmathur.weather.ui.components.SummaryCard
import com.vayunmathur.weather.ui.components.WeatherBlocks
import com.vayunmathur.weather.platform.DisplayUnits
import com.vayunmathur.weather.platform.LocationUiState
import com.vayunmathur.weather.domain.SelectedDateOrTime
import com.vayunmathur.weather.domain.WeatherMetric
import com.vayunmathur.weather.domain.formatHourAxisLabel
import com.vayunmathur.weather.domain.metricSeries
import com.vayunmathur.weather.domain.metricValueFormatter
import com.vayunmathur.weather.domain.parseLocalIsoToEpochSec
import com.vayunmathur.weather.domain.resolveConditions
import com.vayunmathur.weather.platform.WeatherActions
import com.vayunmathur.weather.platform.WeatherViewModel
import com.vayunmathur.weather.platform.precipitationNowcast
import com.vayunmathur.weather.platform.rememberDisplayUnits
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Binds [WeatherViewModel] and the back stack to the stateless [HomeScreen]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage(backStack: NavBackStack<Route>, viewModel: WeatherViewModel) {
    val locations = viewModel.savedLocations.collectAsState().value

    if (locations == null) {
        // Room hasn't answered yet. Hold a plain surface rather than falling
        // through to EmptyHome, which would flash the location chooser on
        // every cold start before the saved rows arrive.
        // RAW SCAFFOLD EXCEPTION: cold-start placeholder — a bare, bar-less,
        // content-less surface. No shared scaffold applies (they all draw chrome).
        Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {}
        return
    }

    if (locations.isEmpty()) {
        EmptyHome(
            viewModel = viewModel,
            onAddLocation = { backStack.add(Route.SearchLocation) },
        )
        return
    }

    var activeLocationId by remember { mutableStateOf(locations.first().id) }
    val activeLocation = locations.firstOrNull { it.id == activeLocationId } ?: locations.first()

    val forecasts by viewModel.forecasts.collectAsState()
    val selected by viewModel.selectedDateOrTime.collectAsState()
    val forecastState = forecasts[activeLocation.id]
    val forecast = forecastState?.forecast

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(activeLocation.id, lifecycleOwner) {
        // Only poll while the app is actually in the foreground; repeatOnLifecycle
        // cancels the loop when the app is backgrounded and restarts it on resume,
        // so we don't hit the network every 60s behind the user's back (the hourly
        // WorkManager job covers background refreshes).
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refreshAll()
                delay(60_000)
            }
        }
    }

    // "Rain in ~30 min" needs both the wall clock and the string table, so it is
    // resolved here rather than inside the screen — see WeatherUiContract.
    val context = LocalContext.current
    val nowcast = if (selected == null && forecast != null) {
        precipitationNowcast(context, forecast.minutely15, forecast.utcOffsetSeconds)
    } else {
        null
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeDrawer = { scope.launch { drawerState.close() } }

    HomeScreen(
        state = LocationUiState(
            location = activeLocation,
            forecast = forecast,
            airQuality = forecastState?.airQuality?.current,
            refreshing = forecastState?.refreshing == true,
            error = forecastState?.error,
            selected = selected,
        ),
        units = rememberDisplayUnits(),
        actions = viewModel,
        drawerState = drawerState,
        precipitationNowcast = nowcast,
        onOpenMap = { metric, isoTime ->
            backStack.add(
                Route.WeatherMap(
                    latitude = activeLocation.latitude,
                    longitude = activeLocation.longitude,
                    name = activeLocation.name,
                    isoTime = isoTime,
                    metric = metric.name,
                )
            )
        },
        drawerContent = {
            LocationsPage(
                backStack = backStack,
                viewModel = viewModel,
                activeLocation = activeLocation,
                onLocationSelect = { picked ->
                    activeLocationId = picked.id
                    viewModel.clearSelection()
                    closeDrawer()
                },
                onClose = { closeDrawer() },
            )
        },
    )
}

/**
 * The forecast page, with no dependency on the ViewModel, the back stack or the clock so it
 * can be rendered from a `@Preview` — see `src/screenshotTest`, which is where the store
 * listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: LocationUiState,
    units: DisplayUnits,
    actions: WeatherActions,
    drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed),
    /** Short-range rain outlook; reads a clock, so the binder resolves it. */
    precipitationNowcast: String? = null,
    /**
     * "Now" for the hourly strip and the sun arc. A parameter rather than a clock read so a
     * preview's fixed sample data can't age out from under it.
     */
    nowEpochSec: Long = System.currentTimeMillis() / 1000,
    onOpenMap: (WeatherMetric, String?) -> Unit = { _, _ -> },
    /** The locations drawer. Empty in a preview, which has no saved-location list. */
    drawerContent: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                drawerContent()
            }
        },
    ) {
        Scaffold(
            // RAW SCAFFOLD EXCEPTION: bespoke full-bleed home. The forecast
            // scrolls under a floating in-content MainSearchBar (there is no top
            // app bar), inside a ModalNavigationDrawer + PullToRefreshBox, and the
            // body consumes the scaffold insets itself. No shared scaffold fits.
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) { paddingValues ->
            ForecastColumn(
                state = state,
                units = units,
                actions = actions,
                drawerState = drawerState,
                paddingValues = paddingValues,
                precipitationNowcast = precipitationNowcast,
                nowEpochSec = nowEpochSec,
                onOpenMap = onOpenMap,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForecastColumn(
    state: LocationUiState,
    units: DisplayUnits,
    actions: WeatherActions,
    drawerState: DrawerState,
    paddingValues: PaddingValues,
    precipitationNowcast: String?,
    nowEpochSec: Long,
    onOpenMap: (WeatherMetric, String?) -> Unit,
) {
    var graphMetric by remember { mutableStateOf<WeatherMetric?>(null) }

    val forecast = state.forecast
    val selected = state.selected
    val scrollState = rememberScrollState()

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { actions.refreshAll(force = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            MainSearchBar(
                paddingValues = paddingValues,
                drawerState = drawerState,
                activeLocation = state.location,
            )

            if (forecast == null) {
                Box(modifier = Modifier.fillMaxSize().padding(top = 64.dp), contentAlignment = Alignment.TopCenter) {
                    val error = state.error
                    if (error != null) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                    } else {
                        LoadingIndicator()
                    }
                }
                return@Column
            }

            val current = forecast.current
            val daily = forecast.daily
            val resolved = resolveConditions(forecast, selected)

            var lastSelection by remember { mutableStateOf(selected) }
            LaunchedEffect(selected) { if (selected != null) lastSelection = selected }
            ExpandVisibility(visible = selected != null) {
                (selected ?: lastSelection)?.let { sel ->
                    SelectedDateTimeHeader(
                        selection = sel,
                        forecast = forecast,
                        use24Hour = units.use24Hour,
                        onClear = { actions.clearSelection() },
                    )
                }
            }

            if (current != null && resolved != null) {
                CurrentWeatherCard(
                    weatherCode = resolved.weatherCode,
                    isDay = resolved.isDay,
                    temperature = resolved.temperature,
                    apparentTemperature = resolved.apparentTemperature,
                    high = resolved.high,
                    low = resolved.low,
                    tempUnit = units.temperature,
                )
            }
            Column(
                // Include the navigation-bar inset so the last cards (Air quality /
                // Pollen) clear the system nav bar and can scroll fully into view.
                modifier = Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 24.dp,
                    bottom = paddingValues.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (selected == null) {
                    SummaryCard(forecast = forecast, tempUnit = units.temperature)
                }
                if (forecast.hourly != null) {
                    HourlyCard(
                        hourly = forecast.hourly,
                        tempUnit = units.temperature,
                        utcOffsetSeconds = forecast.utcOffsetSeconds,
                        use24Hour = units.use24Hour,
                        selectedIsoTime = (selected as? SelectedDateOrTime.Time)?.isoTime,
                        onHourSelected = { actions.toggleTime(it) },
                        scrollToIsoDate = (selected as? SelectedDateOrTime.Day)?.isoDate,
                        nowEpochSec = nowEpochSec,
                    )
                }
                if (daily != null) {
                    DailyCard(
                        daily = daily,
                        tempUnit = units.temperature,
                        selectedIsoDate = (selected as? SelectedDateOrTime.Day)?.isoDate,
                        onDaySelected = { actions.toggleDay(it) },
                    )
                }
                if (current != null && resolved != null) {
                    val sunriseEpoch = resolved.sunriseIso?.let { parseLocalIsoToEpochSec(it, forecast.utcOffsetSeconds) }
                    val sunsetEpoch = resolved.sunsetIso?.let { parseLocalIsoToEpochSec(it, forecast.utcOffsetSeconds) }
                    val moonriseEpoch = resolved.moonriseIso?.let { parseLocalIsoToEpochSec(it, forecast.utcOffsetSeconds) }
                    val moonsetEpoch = resolved.moonsetIso?.let { parseLocalIsoToEpochSec(it, forecast.utcOffsetSeconds) }
                    WeatherBlocks(
                        current = resolved.blockCurrent,
                        uvIndex = resolved.uvIndexMax,
                        air = state.airQuality,
                        sunriseEpochSec = sunriseEpoch,
                        sunsetEpochSec = sunsetEpoch,
                        precipitationMm = resolved.precipitationSum,
                        precipitationNowcast = precipitationNowcast,
                        daylightDurationSec = resolved.daylightDurationSec,
                        moonPhase = resolved.moonPhase,
                        moonriseEpochSec = moonriseEpoch,
                        moonsetEpochSec = moonsetEpoch,
                        onMetricSelected = { graphMetric = it },
                        tempUnit = units.temperature,
                        windUnit = units.wind,
                        pressureUnit = units.pressure,
                        use24Hour = units.use24Hour,
                        nowEpochSec = nowEpochSec,
                    )
                }
            }
        }

        val gm = graphMetric
        if (gm != null && forecast != null) {
            MetricGraphSheet(
                title = stringResource(gm.title),
                points = metricSeries(forecast, gm, selected),
                valueLabel = metricValueFormatter(gm, units.temperature, units.wind, units.pressure),
                timeLabel = { epoch -> formatHourAxisLabel(epoch, units.use24Hour) },
                onOpenMap = {
                    val iso = when (val s = selected) {
                        is SelectedDateOrTime.Time -> s.isoTime
                        is SelectedDateOrTime.Day -> "${s.isoDate}T00:00"
                        null -> null
                    }
                    onOpenMap(gm, iso)
                    graphMetric = null
                },
                onDismiss = { graphMetric = null },
            )
        }
    }
}

/**
 * Shown in place of the forecast when nothing is pinned yet. Stays on the ViewModel: it is
 * an empty state, not a listing shot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyHome(viewModel: WeatherViewModel, onAddLocation: () -> Unit) {
    val (onUseCurrent, requesting) = rememberRequestDeviceLocation(viewModel)

    AppScaffold(title = stringResource(R.string.weather_title), scrollBehavior = appBarScrollBehavior()) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.no_locations_yet), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.add_a_city_or_use_your_current_location),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
                Button(onClick = onAddLocation) { Text(stringResource(R.string.add_location)) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onUseCurrent() },
                    enabled = !requesting,
                ) {
                    if (requesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (requesting) stringResource(R.string.locating) else stringResource(R.string.use_current_location))
                }
            }
        }
    }
}
