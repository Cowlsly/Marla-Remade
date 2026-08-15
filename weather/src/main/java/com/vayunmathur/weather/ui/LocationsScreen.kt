package com.vayunmathur.weather.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults
import com.vayunmathur.library.ui.SheetValue
import com.vayunmathur.library.ui.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.R
import com.vayunmathur.weather.Route
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.network.GeocodingResult
import com.vayunmathur.weather.network.WeatherApi
import com.vayunmathur.weather.ui.components.LocationItem
import com.vayunmathur.weather.ui.components.UseDeviceLocationCard
import com.vayunmathur.weather.domain.formatTemperatureCompact
import com.vayunmathur.weather.platform.LocationProvider
import com.vayunmathur.weather.platform.LocationRow
import com.vayunmathur.weather.platform.LocationsUiState
import com.vayunmathur.weather.platform.WeatherActions
import com.vayunmathur.weather.platform.WeatherViewModel
import com.vayunmathur.weather.platform.rememberTempUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.draggableHandle
import com.vayunmathur.library.ui.rememberReorderableLazyListState

/**
 * Composable helper that provides a device-location request action with
 * permission handling. Returns the onClick lambda and a loading flag.
 * Used by both [LocationsPage] and the empty-home state in `HomePage.kt`.
 */
@Composable
internal fun rememberRequestDeviceLocation(
    viewModel: WeatherViewModel,
): Pair<() -> Unit, Boolean> {
    val context = LocalContext.current
    val messenger = rememberMessenger()
    val scope = rememberCoroutineScope()
    val currentLocationLabel = stringResource(R.string.current_location)
    val couldntDetermineMsg = stringResource(R.string.couldn_t_determine_location)
    val permissionDeniedMsg = stringResource(R.string.location_permission_denied)
    var loading by remember { mutableStateOf(false) }

    val fetchLocation: () -> Unit = {
        loading = true
        scope.launch {
            val loc = LocationProvider.currentLocation(context)
            if (loc != null) {
                viewModel.setCurrentLocation(currentLocationLabel, loc.latitude, loc.longitude)
            } else {
                messenger.show(couldntDetermineMsg)
            }
            loading = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) {
            fetchLocation()
        } else {
            messenger.show(permissionDeniedMsg)
        }
    }

    val onClick = {
        if (LocationProvider.hasPermission(context)) {
            fetchLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    return onClick to loading
}

/**
 * Binds [WeatherViewModel] and the back stack to the stateless [LocationsScreen].
 *
 * The per-row "Last updated 4m ago" line is built here because it needs both a [Resources]
 * for the string and a clock; the screen only ever sees the finished text.
 */
@Composable
fun LocationsPage(
    backStack: NavBackStack<Route>,
    viewModel: WeatherViewModel,
    activeLocation: SavedLocation?,
    onLocationSelect: (SavedLocation) -> Unit,
    onClose: () -> Unit,
) {
    val locations = viewModel.savedLocations.collectAsState().value.orEmpty()
    val forecasts by viewModel.forecasts.collectAsState()
    val resources = LocalResources.current

    // Ticks every 30s so the "Last updated Xm ago" labels advance over time
    // rather than being frozen at whatever they read when the drawer opened.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }

    val (onAddCurrentLocation, deviceLocationLoading) = rememberRequestDeviceLocation(viewModel)

    val rows = locations.map { loc ->
        val state = forecasts[loc.id]
        LocationRow(
            location = loc,
            description = state?.fetchedAtEpochMs
                ?.takeIf { it > 0L }
                ?.let { resources.getString(R.string.last_updated, formatAgo(resources, it, nowMs)) }
                ?: resources.getString(R.string.no_data_yet),
            weatherCode = state?.forecast?.current?.weatherCode,
            isDay = (state?.forecast?.current?.isDay ?: 1) == 1,
        )
    }

    LocationsScreen(
        state = LocationsUiState(
            rows = rows,
            activeLocationId = activeLocation?.id,
            deviceLocationLoading = deviceLocationLoading,
        ),
        actions = viewModel,
        onLocationSelect = onLocationSelect,
        onClose = onClose,
        onSearchLocation = { backStack.add(Route.SearchLocation) },
        onUseDeviceLocation = onAddCurrentLocation,
    )
}

/**
 * Locations drawer content, with no dependency on the ViewModel or the back stack so it can
 * be rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from. Renders inside [HomeScreen]'s `ModalNavigationDrawer`: a Scaffold with a
 * back/close top bar, a scrollable list of [LocationItem]s, and a full-width "Search
 * location" Button pinned to the bottom (in the Scaffold's `bottomBar` slot).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(
    state: LocationsUiState,
    actions: WeatherActions,
    onLocationSelect: (SavedLocation) -> Unit = {},
    onClose: () -> Unit = {},
    onSearchLocation: () -> Unit = {},
    onUseDeviceLocation: () -> Unit = {},
) {
    var longPressedLocation: SavedLocation? by remember { mutableStateOf(null) }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))

    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var localData by remember { mutableStateOf(state.rows) }
    var hasDragged by remember { mutableStateOf(false) }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        localData = localData.toMutableList().apply { add(to.index, removeAt(from.index)) }
        hasDragged = true
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    LaunchedEffect(state.rows) {
        if (!reorderState.isAnyItemDragging) localData = state.rows
    }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && hasDragged) {
            actions.reorderLocations(localData.map { it.location })
            hasDragged = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = {
                    Text(
                        stringResource(R.string.locations),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        IconBack(tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
            )
        },
        bottomBar = {
            val context = LocalContext.current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onSearchLocation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconSearch()
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.search_location))
                }
                Button(
                    onClick = { openRegionalUnitsSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.set_units))
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding(), bottom = paddingValues.calculateBottomPadding()),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            ) {
                val showDeviceLocationCard = localData.none { it.location.isCurrent }
                if (showDeviceLocationCard) {
                    item {
                        UseDeviceLocationCard(
                            onClick = { if (!state.deviceLocationLoading) onUseDeviceLocation() },
                            isLoading = state.deviceLocationLoading,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                itemsIndexed(localData, key = { _, item -> item.location.id }) { idx, row ->
                    val loc = row.location
                    ReorderableItem(reorderState, key = loc.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp)
                        LocationItem(
                            location = loc,
                            description = row.description,
                            currentWeatherCode = row.weatherCode,
                            isDay = row.isDay,
                            isSelected = loc.id == state.activeLocationId,
                            onClick = { onLocationSelect(loc) },
                            onLongClick = { longPressedLocation = loc },
                            modifier = Modifier.shadow(elevation, MaterialTheme.shapes.extraLarge),
                            dragHandle = if (localData.size > 1) {
                                {
                                    IconButton(
                                        onClick = {},
                                        modifier = Modifier.draggableHandle(
                                            reorderState,
                                            key = loc.id,
                                            index = idx,
                                            onDragStarted = {
                                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = {
                                                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            },
                                        ),
                                    ) {
                                        IconDragHandle(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }

    val sheetLocation = longPressedLocation
    if (sheetLocation != null) {
        ModalBottomSheet(
            onDismissRequest = { longPressedLocation = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                Text(
                    text = sheetLocation.name,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    leadingContent = {
                        IconDelete(tint = MaterialTheme.colorScheme.onSurface)
                    },
                    content = { Text(stringResource(UiR.string.delete), color = MaterialTheme.colorScheme.onSurface) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(4.dp))
                com.vayunmathur.library.ui.TextButton(
                    onClick = {
                        actions.deleteLocation(sheetLocation)
                        longPressedLocation = null
                    },
                    modifier = Modifier.padding(start = 16.dp),
                ) { Text(stringResource(R.string.confirm_delete)) }
            }
        }
    }
}

/**
 * Opens the OS regional-units settings so the user can pick temperature/wind/etc. units.
 * The app itself stores no units config; it reads the system regional preferences (see
 * [com.vayunmathur.weather.platform.rememberTempUnit]).
 * on devices without a dedicated regional-preferences screen (pre-Android 14).
 */
private fun openRegionalUnitsSettings(context: Context) {
    // Value of Settings.ACTION_REGIONAL_PREFERENCES_SETTINGS (API 34); used as a literal so the
    // deep link still compiles/works when built against lower compile SDKs.
    val actions = listOf(
        "android.settings.REGIONAL_PREFERENCES_SETTINGS",
        Settings.ACTION_LOCALE_SETTINGS,
        Settings.ACTION_SETTINGS,
    )
    for (action in actions) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next, broader settings target.
        }
    }
}

/** Format a "X ago" delta from [nowMs] to the given epoch ms. */
private fun formatAgo(resources: Resources, epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val deltaSec = ((nowMs - epochMs) / 1000L).coerceAtLeast(0L)
    return when {
        deltaSec < 60 -> resources.getString(R.string.just_now)
        deltaSec < 3600 -> resources.getString(R.string.minutes_ago, (deltaSec / 60).toInt())
        deltaSec < 86_400 -> resources.getString(R.string.hours_ago, (deltaSec / 3600).toInt())
        else -> resources.getString(R.string.days_ago, (deltaSec / 86_400).toInt())
    }
}

/**
 * Search-location screen registered as a `DialogPage()` route entry.
 * Hosted by Navigation3's `DialogSceneStrategy` so it renders as a system
 * dialog instead of a full page. Picking a result inserts the location
 * and pops the back stack.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.FlowPreview::class,
)
@Composable
fun SearchLocationPage(backStack: NavBackStack<Route>, viewModel: WeatherViewModel) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodingResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var temps by remember { mutableStateOf<Map<Long, Double?>>(emptyMap()) }
    val tempUnit = rememberTempUnit()

    LaunchedEffect(Unit) {
        snapshotFlow { query }
            .debounce(300)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                flow {
                    if (q.isBlank()) {
                        emit(emptyList<GeocodingResult>())
                    } else {
                        searching = true
                        val res = runCatching { WeatherApi.geocode(q).results }.getOrDefault(emptyList())
                        searching = false
                        emit(res)
                    }
                }
            }
            .collect { results = it }
    }

    LaunchedEffect(results) {
        temps = emptyMap()
        coroutineScope {
            results.forEach { r ->
                launch {
                    val temp = runCatching {
                        WeatherApi.currentTemperature(r.latitude, r.longitude)
                    }.getOrNull()
                    temps = temps + (r.id to temp)
                }
            }
        }
    }

    com.vayunmathur.library.ui.Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .heightIn(min = 220.dp, max = 480.dp),
        ) {
            Text(
                stringResource(R.string.search_location),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            com.vayunmathur.library.ui.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.city_name_hint)) },
                leadingIcon = {
                    IconSearch()
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 320.dp)) {
                when {
                    searching && results.isEmpty() -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    query.isNotBlank() && results.isEmpty() && !searching -> {
                        Text(
                            stringResource(R.string.no_matches),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(results, key = { it.id }) { r ->
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    ),
                                    content = { Text(r.name) },
                                    supportingContent = {
                                        val parts = listOfNotNull(r.admin1, r.country).filter { it.isNotBlank() }
                                        if (parts.isNotEmpty()) Text(parts.joinToString(", "))
                                    },
                                    trailingContent = {
                                        if (temps.containsKey(r.id)) {
                                            temps[r.id]?.let { temp ->
                                                Text(
                                                    formatTemperatureCompact(temp, tempUnit),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        } else {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clickable {
                                            viewModel.addLocation(
                                                name = r.name,
                                                country = r.country.orEmpty(),
                                                latitude = r.latitude,
                                                longitude = r.longitude,
                                            )
                                            backStack.pop()
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
