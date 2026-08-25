package com.vayunmathur.maps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FreeHeightBottomSheetScaffold
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.SheetValue
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.rememberFreeHeightSheetState
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ui.map.LayerToggles
import com.vayunmathur.maps.ui.map.MapBrowseHeader
import com.vayunmathur.maps.ui.map.MapFabStack
import com.vayunmathur.maps.ui.map.MapOverlay
import com.vayunmathur.maps.ui.map.MapOverlays
import com.vayunmathur.maps.ui.map.MapSearchBar
import com.vayunmathur.maps.ui.map.MapSurface
import com.vayunmathur.maps.ui.map.NavigationCameraFollow
import com.vayunmathur.maps.ui.map.WaypointList
import com.vayunmathur.maps.ui.map.mapSearchLabel
import com.vayunmathur.maps.ui.map.rememberMapChromeState
import com.vayunmathur.maps.ui.map.style.rememberMapStyle
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.util.MapSettingsViewModel
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.PoiIndex
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.visibleBoundsOrWorld
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.spatialk.geojson.Position
import com.vayunmathur.maps.R as MapsR

/** Cold-start camera: San Francisco at z14, where the baked POIs are dense enough to see. */
private val INITIAL_CAMERA = CameraPosition(target = Position(-122.4194, 37.7749), zoom = 14.0)

/**
 * The map screen.
 *
 * This composable is the wiring: it collects the ViewModel state, decides what the chrome should
 * show, and hands each piece to a stateless component in [com.vayunmathur.maps.ui.map]. The
 * pieces themselves — the search bar, the FAB stack, the sheets, the hit-test, the camera
 * follow, the style patch — each live in their own file and none of them know about this one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPage(
    backStack: NavBackStack<Route>,
    viewModel: SelectedFeatureViewModel,
    savedPlacesViewModel: SavedPlacesViewModel,
    searchViewModel: MapsSearchViewModel,
    settingsViewModel: MapSettingsViewModel,
    parkingViewModel: com.vayunmathur.maps.util.ParkingViewModel,
    transitViewModel: com.vayunmathur.maps.util.TransitStopsViewModel,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val messenger = rememberMessenger()
    val noResultsMessage = stringResource(MapsR.string.no_results_found)

    val chrome = rememberMapChromeState()
    val camera = rememberCameraState(INITIAL_CAMERA)

    val selectedFeature by viewModel.selectedFeature.collectAsState()
    val inactiveNavigation by viewModel.inactiveNavigation.collectAsState()
    val route by viewModel.routes.collectAsState(null)
    val userPosition by viewModel.userPosition.collectAsState()
    val userBearing by viewModel.userBearing.collectAsState()
    val userHeadingAccuracy by viewModel.userHeadingAccuracy.collectAsState()

    val savedHome by savedPlacesViewModel.home.collectAsState()
    val savedWork by savedPlacesViewModel.work.collectAsState()
    val savedList by savedPlacesViewModel.saved.collectAsState()
    // Home, Work and the starred list drawn as one pin set, deduped.
    val savedPins = remember(savedHome, savedWork, savedList) {
        (listOfNotNull(savedHome, savedWork) + savedList).distinct()
    }

    val parkingSpot by parkingViewModel.active.collectAsState()
    val searchResults by searchViewModel.results.collectAsState()
    val selectedTransitStop by transitViewModel.selected.collectAsState()
    val departuresState by transitViewModel.departures.collectAsState()

    // The findfamily service is bound only while this screen is composed, so this is empty when
    // findfamily is absent.
    val familyMembers by com.vayunmathur.maps.ipc.rememberFamilyMembers()

    val trafficEnabled by settingsViewModel.trafficLayer.collectAsState()
    val satelliteEnabled by settingsViewModel.satelliteLayer.collectAsState()
    val safetyEnabled by settingsViewModel.safetyLayer.collectAsState()
    val transitEnabled by settingsViewModel.transitLayer.collectAsState()

    // Resolve the P6 map-theme setting against the OS the same way DynamicTheme does, so the map
    // flips light/dark together with the rest of the chrome.
    val themeMode by settingsViewModel.themeMode.collectAsState()
    val darkMap = themeMode.darkOverride ?: isSystemInDarkTheme()
    val mapStyle by rememberMapStyle(isDark = darkMap)

    val navState by NavigationSessionManager.state.collectAsState()
    // Collected, not read off a field: a recalculation swaps the route mid-session, and reading
    // it as a plain property meant this screen kept drawing the OLD route's steps against the
    // new progress until something else happened to recompose.
    val navSession by NavigationSessionManager.session.collectAsState()
    val isNavigating = navState !is NavigationSessionManager.NavState.Idle
    val navProgress = (navState as? NavigationSessionManager.NavState.Navigating)?.progress

    val sheetState = rememberFreeHeightSheetState(SheetValue.Hidden)
    val browsing = selectedFeature == null && inactiveNavigation == null && !isNavigating

    // Re-map the offline POI side files once the map is ready, so a first-run download that
    // landed after PoiIndex.initialize first ran (and no-op'd) is picked up rather than staying
    // poisoned. reload() is a cheap idempotent re-mmap.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { PoiIndex.reload(context) }
    }

    LaunchedEffect(Unit) {
        // Raise the sheet if something is already selected — unless a deep link is about to open
        // the compact pane instead, which the pendingFocus effect below handles.
        if (selectedFeature != null && viewModel.pendingFocus.value == null) {
            sheetState.partialExpand()
        }
    }

    // Contact-address auto-select and external geo:/maps deep links land here. A StateFlow-backed
    // request survives a cold start, so a link that selected a place before the map composed
    // still animates and peeks once it is ready.
    val pendingFocus by viewModel.pendingFocus.collectAsState()
    LaunchedEffect(pendingFocus) {
        val request = pendingFocus ?: return@LaunchedEffect
        camera.animateTo(
            camera.position.copy(
                target = request.position,
                zoom = request.zoom ?: maxOf(camera.position.zoom, 14.0),
            )
        )
        sheetState.partialExpand()
        viewModel.consumeFocus()
    }

    // While navigating the in-screen overlay is the primary UI, so the sheet stays down.
    LaunchedEffect(isNavigating) {
        if (isNavigating) sheetState.hide()
    }

    // Nothing selected means the sheet has nothing to draw, and its peek height is fixed — so
    // leaving it up would show a blank card rather than collapsing.
    LaunchedEffect(selectedFeature) {
        if (selectedFeature == null) sheetState.hide()
    }

    NavigationCameraFollow(camera, chrome, navProgress, isNavigating)

    // Poll the posted limit under the puck, off the `roads` overlay's probe layer.
    // Null when there is no road there, or none of its tags is a speed limit.
    LaunchedEffect(navProgress) {
        val progress = navProgress
        val projection = camera.projection
        chrome.postedLimit = if (progress != null && projection != null) {
            queryPostedLimit(projection, progress.snappedPosition)
        } else {
            null
        }
    }

    fun openSearch(query: String? = null, waypointIndex: Int? = null) {
        val bbox = camera.visibleBoundsOrWorld()
        backStack.add(
            Route.SearchPage(waypointIndex, bbox.east, bbox.west, bbox.north, bbox.south, query)
        )
    }

    BackHandler(selectedFeature != null) {
        coroutineScope.launch {
            viewModel.set(null)
            sheetState.hide()
        }
    }

    BackHandler(selectedFeature == null && inactiveNavigation != null) {
        viewModel.setInactiveNavigation(null)
    }

    FreeHeightBottomSheetScaffold({
        Column(Modifier.padding(horizontal = Spacing.lg).padding(top = Spacing.sm)) {
            BottomSheetContent(
                viewModel,
                selectedFeature,
                { viewModel.set(it) },
                route,
                chrome.selectedRouteType,
                { chrome.selectedRouteType = it },
                inactiveNavigation,
                savedPlacesViewModel,
                transitViewModel,
                navState,
            )
        }
    }, Modifier, sheetState, MapChromeMetrics.sheetPeekHeight, contentKey = listOf(selectedFeature, chrome.selectedRouteType)) { paddingValues ->
        AppScaffold(
            title = {
                MapSearchBar(
                    label = mapSearchLabel(
                        (selectedFeature as? SpecificFeature.RoutableFeature)?.name
                    ),
                    onOpenSearch = { query -> openSearch(query) },
                    onContactAddress = { address ->
                        val bbox = camera.visibleBoundsOrWorld()
                        searchViewModel.resolveAndSelect(
                            address,
                            (bbox.north + bbox.south) / 2.0,
                            (bbox.east + bbox.west) / 2.0,
                        ) { place ->
                            if (place != null) {
                                viewModel.stashRouteSelection()
                                // Fly there and open the peek pane, the same direct-open path a
                                // geo:/maps deep link takes.
                                viewModel.selectAndFocus(
                                    place,
                                    zoom = maxOf(camera.position.zoom, 14.0),
                                )
                            } else {
                                messenger.show(noResultsMessage)
                            }
                        }
                    },
                )
            },
            modifier = Modifier.padding(top = paddingValues.calculateTopPadding()),
            actions = {
                IconButton({ backStack.add(Route.SettingsPage) }) { IconSettings() }
            },
            scrollBehavior = appBarScrollBehavior(),
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                MapSurface(
                    style = mapStyle,
                    camera = camera,
                    chrome = chrome,
                    viewModel = viewModel,
                    searchViewModel = searchViewModel,
                    transitViewModel = transitViewModel,
                    sheetState = sheetState,
                    selectedFeature = selectedFeature,
                    route = route?.get(chrome.selectedRouteType),
                    userPosition = userPosition,
                    userBearing = userBearing,
                    navProgress = navProgress,
                    searchResults = searchResults,
                    savedPlaces = savedPins,
                    parkingSpot = parkingSpot,
                    familyMembers = familyMembers,
                    trafficEnabled = trafficEnabled,
                    satelliteEnabled = satelliteEnabled,
                    safetyEnabled = safetyEnabled,
                    transitEnabled = transitEnabled,
                    darkBasemap = darkMap,
                )

                val routeFeature = (selectedFeature as? SpecificFeature.Route) ?: inactiveNavigation
                if (routeFeature != null) {
                    WaypointList(
                        route = routeFeature,
                        onReorder = { viewModel.set(it) },
                        onEditWaypoint = { index -> openSearch(waypointIndex = index) },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                } else {
                    MapBrowseHeader(
                        selectedCategory = chrome.selectedCategory,
                        onCategory = { chrome.toggleCategory(it) },
                        headingAccuracy = userHeadingAccuracy,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                // Browse controls, plus the layers button, which stays out while a place is
                // selected and rides above the sheet — see [MapFabStack].
                MapFabStack(
                    zoom = camera.position.zoom,
                    latitude = camera.position.target.latitude,
                    bearing = camera.position.bearing,
                    browsing = browsing,
                    lift = { sheetState.liftPx.roundToInt() },
                    onResetNorth = {
                        coroutineScope.launch {
                            camera.animateTo(camera.position.copy(bearing = 0.0, tilt = 0.0))
                        }
                    },
                    onLayers = { chrome.show(MapOverlay.Layers) },
                    onParking = {
                        val spot = parkingSpot
                        if (spot == null) {
                            val position = userPosition
                            if (position.latitude != 0.0 || position.longitude != 0.0) {
                                parkingViewModel.saveParking(position.latitude, position.longitude)
                            }
                        } else {
                            coroutineScope.launch {
                                camera.animateTo(
                                    camera.position.copy(
                                        target = Position(spot.lon, spot.lat),
                                        zoom = maxOf(camera.position.zoom, 15.0),
                                    )
                                )
                            }
                            chrome.show(MapOverlay.Parking)
                        }
                    },
                    onMyLocation = {
                        coroutineScope.launch {
                            camera.animateTo(
                                camera.position.copy(
                                    target = userPosition,
                                    zoom = maxOf(camera.position.zoom, 15.0),
                                )
                            )
                        }
                    },
                )

                NavigationOverlay(
                    navState = navState,
                    steps = navSession.route?.step ?: emptyList(),
                    autoFollow = chrome.autoFollow,
                    onRecenter = { chrome.autoFollow = true },
                    onEndTrip = {
                        stopNavigation(context)
                        chrome.autoFollow = true
                        chrome.northUp = false
                    },
                    onDismissArrival = { stopNavigation(context) },
                    postedLimit = chrome.postedLimit,
                    northUp = chrome.northUp,
                    onToggleNorthUp = { chrome.northUp = !chrome.northUp },
                    destinationName = navSession.destinationName,
                    darkBasemap = darkMap,
                )

                MapOverlays(
                    overlay = chrome.overlay,
                    onDismiss = { chrome.dismissOverlay() },
                    layers = LayerToggles(
                        traffic = trafficEnabled,
                        satellite = satelliteEnabled,
                        safety = safetyEnabled,
                        transit = transitEnabled,
                        onTraffic = { settingsViewModel.setTrafficLayer(it) },
                        onSatellite = { settingsViewModel.setSatelliteLayer(it) },
                        onSafety = { settingsViewModel.setSafetyLayer(it) },
                        onTransit = { settingsViewModel.setTransitLayer(it) },
                    ),
                    parkingSpot = parkingSpot,
                    onClearParking = {
                        parkingViewModel.clear()
                        chrome.dismissOverlay()
                    },
                    onParkingDirections = {
                        val spot = parkingSpot ?: return@MapOverlays
                        val feature = spot.toFeature(context.getString(MapsR.string.parking_title))
                        viewModel.stashRouteSelection()
                        viewModel.set(SpecificFeature.Route(listOf(null, feature)))
                        chrome.dismissOverlay()
                        coroutineScope.launch { sheetState.partialExpand() }
                    },
                    onParkingNoteChange = { parkingViewModel.updateNote(it) },
                    selectedStop = selectedTransitStop,
                    departures = departuresState,
                    onCloseStop = { transitViewModel.closeStop() },
                    onRefreshDepartures = { transitViewModel.refresh() },
                )
            }
        }
    }
}

/** End the session and stop the foreground service that outlives this screen. */
private fun stopNavigation(context: android.content.Context) {
    NavigationSessionManager.stop()
    context.stopService(
        android.content.Intent(context, com.vayunmathur.maps.util.NavigationService::class.java)
    )
}
