package com.vayunmathur.maps.ui

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.BottomSheetScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.CompassCalibrationBanner
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMyLocation
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.SheetValue
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberBottomSheetScaffoldState
import com.vayunmathur.library.ui.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.R
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.parse
import com.vayunmathur.maps.util.MapTileCache
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.GooglePoiMapViewModel
import com.vayunmathur.maps.util.MapSettingsViewModel
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.draggableHandle
import com.vayunmathur.library.ui.rememberReorderableLazyListState
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.maps.R as MapsR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPage(backStack: NavBackStack<Route>, viewModel: SelectedFeatureViewModel, savedPlacesViewModel: SavedPlacesViewModel, poiViewModel: GooglePoiMapViewModel, searchViewModel: MapsSearchViewModel, settingsViewModel: MapSettingsViewModel, parkingViewModel: com.vayunmathur.maps.util.ParkingViewModel, transitViewModel: com.vayunmathur.maps.util.TransitStopsViewModel) {
    val selectedFeature by viewModel.selectedFeature.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val savedHome by savedPlacesViewModel.home.collectAsState()
    val savedWork by savedPlacesViewModel.work.collectAsState()
    val savedList by savedPlacesViewModel.saved.collectAsState()

    // All saved places drawn as pins: Home, Work and the starred list, deduped.
    val savedPins = remember(savedHome, savedWork, savedList) {
        (listOfNotNull(savedHome, savedWork) + savedList).distinct()
    }

    // Parking memory (P9): the single active parking spot (pin + recall).
    val parkingSpot by parkingViewModel.active.collectAsState()
    var showParkingSheet by remember { mutableStateOf(false) }

    // Map-layer visibility toggles (P6 layers sheet, persisted via DataStore).
    val trafficEnabled by settingsViewModel.trafficLayer.collectAsState()
    val satelliteEnabled by settingsViewModel.satelliteLayer.collectAsState()
    val safetyEnabled by settingsViewModel.safetyLayer.collectAsState()
    val transitEnabled by settingsViewModel.transitLayer.collectAsState()
    var showLayersSheet by remember { mutableStateOf(false) }

    // Effective map palette (P14): resolve the P6 Map-theme setting against the
    // OS dark mode using the same rule DynamicTheme applies (a null override =
    // follow the system). Drives the runtime dark recolor of the basemap below,
    // so the map flips light<->dark together with the rest of the app chrome.
    val themeMode by settingsViewModel.themeMode.collectAsState()
    val darkMap = themeMode.darkOverride ?: isSystemInDarkTheme()

    // Public transit (P10): nearby stops overlay + live departure board. Stops
    // are only fetched/drawn while the Transit layer is on.
    val transitStops by transitViewModel.stops.collectAsState()
    val selectedTransitStop by transitViewModel.selected.collectAsState()
    val departuresState by transitViewModel.departures.collectAsState()

    // Google POI overlay pins (viewport scrape → custom layer, replacing the
    // suppressed native basemap POIs).
    val googlePins by poiViewModel.pins.collectAsState()

    // Search-result pins (from the Google search page) drawn on the map.
    val searchResults by searchViewModel.results.collectAsState()

    // Live family-location pins (P18): the findfamily bound service is bound
    // while this screen is composed (see rememberFamilyMembers' DisposableEffect)
    // and pushes updates only while bound. Empty when findfamily is absent.
    val familyMembers by com.vayunmathur.maps.ipc.rememberFamilyMembers()

    val camera = rememberCameraState(CameraPosition(target = Position(-118.243683,34.052235), zoom = 5.0))

    LaunchedEffect(camera.position, transitEnabled) {
        if (camera.position.zoom >= 11.0) {
            delay(300) // Debounce idle-driven overlay refresh
            val projection = camera.projection
            if (projection != null) {
                val bbox = projection.queryVisibleBoundingBox()
                // Refresh nearby transit stops (P10) only while the layer is on
                // (VM debounces + caches; wide views clear the overlay).
                if (transitEnabled) {
                    transitViewModel.onViewport(bbox.north, bbox.east, bbox.south, bbox.west)
                }
            }
        }
    }

    // P23: feed the Google POI overlay the live viewport as the camera MOVES
    // (not only on idle) so the VM can refresh snappily when the centre jumps.
    // The VM debounces, applies a min-interval and LRU-caches the keyless scrape,
    // and prefetches a padded bbox, so streaming every camera change here can't
    // hammer Google.
    LaunchedEffect(Unit) {
        snapshotFlow { camera.position }
            .collect { pos ->
                if (pos.zoom >= 11.0) {
                    val bbox = camera.projection?.queryVisibleBoundingBox() ?: return@collect
                    poiViewModel.onViewport(bbox.north, bbox.east, bbox.south, bbox.west)
                }
            }
    }

    // Online-tiles-only: the basemap always streams live (offline zone tile
    // packs were removed). Offline ROUTING still works via the downloaded graph.
    val hybridUrl = MapTileCache.BASEMAP_PMTILES_URL

    // Inside MapPage
    var json by remember { mutableStateOf<String?>(null) }

    // Read the style asset on Dispatchers.IO (file open), then the hybrid
    // patch step is light enough to stay on the same coroutine. Re-runs on a
    // theme change (darkMap) so the recolored style reloads and flips live.
    LaunchedEffect(hybridUrl, darkMap) {
        val updatedStyle = withContext<String>(Dispatchers.IO) {
            val rawStyle = context.assets.open("style.json").bufferedReader().readText()
            patchStyleForHybrid(
                rawStyle,
                MapTileCache.BASEMAP_PMTILES_URL,
                hybridUrl,
                darkMap,
            )
        }
        Log.d(
            "MapPage",
            "style patched base=${MapTileCache.BASEMAP_PMTILES_URL} hybrid=$hybridUrl " +
                "darkMap=$darkMap jsonLen=${updatedStyle.length}",
        )
        json = updatedStyle
    }

    // --- LOCATION & OSM INITIALIZATION ---
    val userPosition by viewModel.userPosition.collectAsState()
    val userBearing by viewModel.userBearing.collectAsState()
    val userHeadingAccuracy by viewModel.userHeadingAccuracy.collectAsState()

    val inactiveNavigation by viewModel.inactiveNavigation.collectAsState()

    // --- ROUTE COMPUTATION ---
    val route by viewModel.routes.collectAsState(null)

    // --- NAVIGATION SESSION ---
    val navState by com.vayunmathur.maps.util.NavigationSessionManager.state.collectAsState()
    val isNavigating = navState !is com.vayunmathur.maps.util.NavigationSessionManager.NavState.Idle
    var autoFollow by remember { mutableStateOf(true) }
    // North-up vs heading-up during navigation (Vela onCompassTap idea).
    var navNorthUp by remember { mutableStateOf(false) }
    // Posted speed limit under the puck (P5b maxspeed overlay; null when the
    // tileset is unhosted or the road has no limit).
    var postedLimit by remember { mutableStateOf<com.vayunmathur.maps.data.PostedLimit?>(null) }
    var lastProgrammaticMoveMs by remember { mutableStateOf(0L) }
    val activeRoute = com.vayunmathur.maps.util.NavigationSessionManager.currentRoute
    val navProgress = (navState as? com.vayunmathur.maps.util.NavigationSessionManager.NavState.Navigating)?.progress

    // --- UI & BOTTOM SHEET STATE ---
    var allowProgrammaticHide by retain { mutableStateOf(false) }

    val scaffoldState = rememberBottomSheetScaffoldState(
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded),
            confirmValueChange = {
                it != SheetValue.Hidden || allowProgrammaticHide
            }
        )
    )

    LaunchedEffect(Unit) {
        // Restore-on-recompose: raise the sheet if something is already selected —
        // unless a deep link / auto-select is about to open the compact PANE instead
        // (handled by the pendingFocus effect below), so we don't full-expand first.
        if (selectedFeature != null && viewModel.pendingFocus.value == null) {
            scaffoldState.bottomSheetState.expand()
        }
    }

    // Search auto-select (P17 contact address) + external geo:/maps deep links land
    // here: fly to the place and open the Vela-style bottom PANE (partial/peek), not a
    // full-screen sheet. A StateFlow-backed request survives a cold start, so a link
    // that selected a place before the map composed still animates + peeks once ready.
    val pendingFocus by viewModel.pendingFocus.collectAsState()
    LaunchedEffect(pendingFocus) {
        val req = pendingFocus ?: return@LaunchedEffect
        camera.animateTo(
            camera.position.copy(
                target = req.position,
                zoom = req.zoom ?: maxOf(camera.position.zoom, 14.0),
            )
        )
        scaffoldState.bottomSheetState.partialExpand()
        viewModel.consumeFocus()
    }

    suspend fun hide() {
        allowProgrammaticHide = true
        scaffoldState.bottomSheetState.hide()
        allowProgrammaticHide = false
    }

    fun openSearch() {
        // Style may not have finished loading — fall back to a world-spanning
        // bbox so the search query still works.
        val bbox = camera.projection?.queryVisibleBoundingBox()
        backStack.add(
            Route.SearchPage(
                null,
                bbox?.east ?: 180.0,
                bbox?.west ?: -180.0,
                bbox?.north ?: 85.0,
                bbox?.south ?: -85.0,
            )
        )
    }

    // Browse category chip → open search pre-filled with the category query.
    fun openCategorySearch(query: String) {
        val bbox = camera.projection?.queryVisibleBoundingBox()
        backStack.add(
            Route.SearchPage(
                null,
                bbox?.east ?: 180.0,
                bbox?.west ?: -180.0,
                bbox?.north ?: 85.0,
                bbox?.south ?: -85.0,
                query,
            )
        )
    }

    BackHandler(selectedFeature != null) {
        coroutineScope.launch {
            viewModel.set(null)
            hide()
        }
    }

    BackHandler(selectedFeature == null && inactiveNavigation != null) {
        viewModel.setInactiveNavigation(null)
    }

    var selectedRouteType by retain { mutableStateOf(RouteService.TravelMode.DRIVE) }

    // --- RENDER ---
    // While actively navigating we don't want the bottom sheet to slide up
    // automatically; the in-screen overlay is the primary nav UI.
    LaunchedEffect(isNavigating) {
        if (isNavigating) {
            hide()
        }
    }

    // Camera follow: animate to snapped position / bearing whenever we get
    // a new progress sample AND the user hasn't panned away.
    LaunchedEffect(navProgress, autoFollow, navNorthUp) {
        val p = navProgress ?: return@LaunchedEffect
        if (!autoFollow) return@LaunchedEffect
        lastProgrammaticMoveMs = System.currentTimeMillis()
        camera.animateTo(
            camera.position.copy(
                target = p.snappedPosition,
                bearing = if (navNorthUp) 0.0 else p.courseOverGround.toDouble(),
                tilt = if (navNorthUp) 0.0 else 60.0,
                zoom = 17.0,
            ),
            kotlin.time.Duration.parse("800ms"),
        )
    }

    // Poll the posted speed limit under the puck from the maxspeed overlay
    // (P5b). Inert (always null) until the maxspeed tileset is hosted.
    LaunchedEffect(navProgress) {
        val p = navProgress
        val projection = camera.projection
        postedLimit = if (p != null && projection != null) {
            queryPostedLimit(projection, p.snappedPosition)
        } else {
            null
        }
    }

    // Detect user-initiated camera moves: if isCameraMoving becomes true
    // outside the ~1.2s window after our own animateTo, treat it as a pan
    // and disable auto-follow until the user taps Recenter.
    LaunchedEffect(camera.isCameraMoving, isNavigating) {
        if (!isNavigating) return@LaunchedEffect
        if (camera.isCameraMoving &&
            System.currentTimeMillis() - lastProgrammaticMoveMs > 1_200
        ) {
            autoFollow = false
        }
    }

    BottomSheetScaffold({
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 48.dp, top = 8.dp)) {
            BottomSheetContent(viewModel, selectedFeature, { viewModel.set(it) }, route, selectedRouteType, { selectedRouteType = it }, inactiveNavigation, savedPlacesViewModel, navState)
        }
    }, Modifier, scaffoldState, 170.dp) { paddingValues ->
        AppScaffold(
            title = {
                // Search bar lives IN the top app bar (Google-Maps style).
                val searchName = if (selectedFeature is SpecificFeature.RoutableFeature) {
                    (selectedFeature as SpecificFeature.RoutableFeature).name
                } else {
                    stringResource(MapsR.string.search_placeholder)
                }
                Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { openSearch() }.padding(start = 12.dp),
                    ) {
                        IconSearch(Modifier.size(20.dp))
                        Text(
                            searchName,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            maxLines = 1,
                        )
                        // Contact address shortcut (P17): pick a contact's postal
                        // address, run the P3 Google search and auto-select the
                        // first hit — same path as tapping a search result.
                        ContactAddressButton(onAddress = { address ->
                            val bbox = camera.projection?.queryVisibleBoundingBox()
                            val nearLat = ((bbox?.north ?: 85.0) + (bbox?.south ?: -85.0)) / 2.0
                            val nearLon = ((bbox?.east ?: 180.0) + (bbox?.west ?: -180.0)) / 2.0
                            searchViewModel.searchAndSelectFirst(address, nearLat, nearLon) { first ->
                                if (first != null) {
                                    if (selectedFeature is SpecificFeature.Route) {
                                        viewModel.setInactiveNavigation(selectedFeature as SpecificFeature.Route)
                                    }
                                    // Select the top hit + fly there + open the place PANE
                                    // (peek), the Vela place-card behaviour. The callback fires
                                    // after the (possibly delayed) scrape resolves, so late
                                    // results still land correctly.
                                    viewModel.selectAndFocus(
                                        searchViewModel.toFeature(first),
                                        zoom = maxOf(camera.position.zoom, 14.0),
                                    )
                                }
                            }
                        })
                        // Voice search (P8): a transcript opens the search page
                        // pre-filled, which runs the P3 Google search.
                        VoiceSearchButton(onResult = { openCategorySearch(it) })
                    }
                }
            },
            modifier = Modifier.padding(top = paddingValues.calculateTopPadding()),
            actions = {
                IconButton({
                    backStack.add(Route.SettingsPage)
                }) {
                    IconSettings()
                }
            },
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                json?.let { json ->
                    MaplibreMap(
                        Modifier,
                        BaseStyle.Json(json),
                        camera,
                        options = MapOptions(
                            RenderOptions(),
                            GestureOptions.Standard,
                            OrnamentOptions.AllDisabled
                        ),
                        onMapClick = { latLng, offset ->
                            coroutineScope.launch {
                                val projection = camera.projection
                                // Parking pin (P9): tapping the saved car spot
                                // opens the parking sheet instead of selecting a
                                // place.
                                val parkingHit = projection?.queryRenderedFeatures(
                                    offset,
                                    setOf(PARKING_PIN_LAYER_ID)
                                )?.isNotEmpty() == true
                                if (parkingHit) {
                                    showParkingSheet = true
                                    return@launch
                                }

                                // Transit stop (P10): tapping a stop opens its
                                // live departure board instead of selecting a
                                // place. Only hit-tested while the layer is on.
                                if (transitEnabled) {
                                    val stopHit = projection?.queryRenderedFeatures(
                                        offset,
                                        setOf(TRANSIT_STOP_LAYER_ID)
                                    )?.firstNotNullOfOrNull { it.toTransitStop() }
                                    if (stopHit != null) {
                                        transitViewModel.openStop(stopHit)
                                        return@launch
                                    }
                                }

                                // Hit-test the search-result pins first, then the
                                // ambient Google POI overlay — a pin tap re-selects
                                // the place as a GenericPlace so
                                // SelectedFeatureViewModel.currentPoiInfo fetches
                                // the enrichment and GooglePoiEnrichment renders.
                                val pinHit = projection?.queryRenderedFeatures(
                                    offset,
                                    setOf(SEARCH_RESULT_LAYER_ID)
                                )?.firstNotNullOfOrNull { it.toSelectedSearchResult() }
                                    ?: projection?.queryRenderedFeatures(
                                        offset,
                                        setOf(SAVED_PLACE_LAYER_ID)
                                    )?.firstNotNullOfOrNull { it.toSelectedSavedPlace() }
                                    ?: projection?.queryRenderedFeatures(
                                        offset,
                                        setOf(FAMILY_LOCATION_LAYER_ID)
                                    )?.firstNotNullOfOrNull { it.toSelectedFamilyMember() }
                                    ?: projection?.queryRenderedFeatures(
                                        offset,
                                        setOf(GOOGLE_POI_LAYER_ID)
                                    )?.firstNotNullOfOrNull { it.toSelectedGooglePoi() }
                                if (pinHit != null) {
                                    if (selectedFeature is SpecificFeature.Route) viewModel.setInactiveNavigation(
                                        selectedFeature as SpecificFeature.Route
                                    )
                                    viewModel.set(pinHit)
                                    scaffoldState.bottomSheetState.expand()
                                    return@launch
                                }

                                // Otherwise fall back to basemap admin labels
                                // (country/region). Native POIs are suppressed
                                // and the amenity-DB enrichment path is gone.
                                val features = projection?.queryRenderedFeatures(
                                    offset,
                                    setOf("places_country", "places_region").flatMap {
                                        listOf("${it}_base", "${it}_hybrid")
                                    }.toSet()
                                ) ?: emptyList()
                                // parse() may do a Wikidata round-trip per
                                // feature; queryRenderedFeatures returns one
                                // Feature PER LAYER at the tap point, so stop at
                                // the first parseable hit instead of doing every
                                // round-trip serially in the foreground.
                                val firstFeature = withContext(Dispatchers.IO) {
                                    features.firstNotNullOfOrNull { raw ->
                                        runCatching { parse(raw) }.getOrNull()
                                    }
                                }

                                if (firstFeature != null) {
                                    if (selectedFeature is SpecificFeature.Route) viewModel.setInactiveNavigation(
                                        selectedFeature as SpecificFeature.Route
                                    )
                                    viewModel.set(firstFeature)
                                    scaffoldState.bottomSheetState.expand()
                                    return@launch
                                }

                                // Nothing hit: reverse-geocode the tapped point
                                // ("what's here?"). Replaces the removed address
                                // FTS geocoder — online-only (Decision D2).
                                searchViewModel.reverseGeocode(latLng.latitude, latLng.longitude) { place ->
                                    if (place != null) {
                                        if (selectedFeature is SpecificFeature.Route) viewModel.setInactiveNavigation(
                                            selectedFeature as SpecificFeature.Route
                                        )
                                        viewModel.set(place)
                                        coroutineScope.launch { scaffoldState.bottomSheetState.expand() }
                                    }
                                }
                            }
                            ClickResult.Pass
                        }
                ) {
                        MyMapLayers(selectedFeature, route?.get(selectedRouteType), json, userPosition, userBearing, navProgress, googlePins, searchResults, savedPins, parkingSpot, transitStops, familyMembers, trafficEnabled, satelliteEnabled, safetyEnabled, transitEnabled)
                    }
                }

                // ROUTE OVERLAY HEADERS
                if(selectedFeature is SpecificFeature.Route || inactiveNavigation != null) {
                    val routeFeature = if(selectedFeature is SpecificFeature.Route) selectedFeature as SpecificFeature.Route else inactiveNavigation!!
                    val listState = rememberLazyListState()
                    val state = rememberReorderableLazyListState(listState, onMove = { from, to ->
                        // swap their indices in the list
                        val newList = routeFeature.waypoints.toMutableList()
                        val temp = newList[from.index]
                        newList[from.index] = newList[to.index]
                        newList[to.index] = temp
                        viewModel.set(routeFeature.copy(waypoints = newList))
                    })
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).fillMaxWidth()
                    ) {
                        itemsIndexed(routeFeature.waypoints, key = { idx, it -> it?.position?.toString()?:"" }) { idx, item ->
                            ReorderableItem(reorderState = state, key = item?.position?.toString() ?: "") { isDragging ->

                                val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)

                                Card(shape = verticalShape(idx, routeFeature.waypoints.size), elevation = CardDefaults.cardElevation(elevation)) {
                                    ListItem({
                                        Text(
                                            item?.name
                                                ?: stringResource(MapsR.string.your_location)
                                        )
                                    }, Modifier.clickable {
                                        // Style may not have finished loading
                                        // — fall back to a world-spanning bbox
                                        // so the search query still works.
                                        val bbox = camera.projection?.queryVisibleBoundingBox()
                                        backStack.add(Route.SearchPage(idx,
                                            bbox?.east ?: 180.0,
                                            bbox?.west ?: -180.0,
                                            bbox?.north ?: 85.0,
                                            bbox?.south ?: -85.0))
                                    }, trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if(idx > 0 && idx < routeFeature.waypoints.size - 1) {
                                                IconButton({
                                                    val newList = routeFeature.waypoints.toMutableList()
                                                    newList.removeAt(idx)
                                                    viewModel.set(routeFeature.copy(waypoints = newList))
                                                }) {
                                                    IconClose()
                                                }
                                            }
                                            IconDragHandle(Modifier.draggableHandle(state, key = item?.position?.toString() ?: "", index = idx))
                                        }
                                    }, colors = ListItemDefaults.colors(Color.Transparent))
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        // Quick category chips (Vela's browse CategoryChips),
                        // wired to the P3 Google search categories. (Home/Work
                        // quick-access slots moved to the search page.)
                        CategoryChips(
                            onCategory = { openCategorySearch(it) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        // Compass calibration hint for the heading puck. Only show
                        // when the heading is genuinely unreliable (UNRELIABLE/LOW);
                        // MEDIUM is good enough and shouldn't nag the user.
                        if (userHeadingAccuracy <= android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
                            CompassCalibrationBanner(userHeadingAccuracy)
                        }
                    }
                }

                // Browse map controls (Decision D6): a scale bar plus the FAB
                // stack (my-location, layers, compass). Surfaced only while
                // browsing — hidden during navigation (the nav overlay owns its
                // own controls) and while a place/route is selected (the bottom
                // sheet takes over the lower half of the screen).
                if (selectedFeature == null && inactiveNavigation == null && !isNavigating) {
                    MapScaleBar(
                        zoom = camera.position.zoom,
                        latitude = camera.position.target.latitude,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .padding(16.dp),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Compass sits on top and only shows when the map is
                        // rotated; layers in the middle; my-location is the
                        // primary action, closest to the thumb.
                        CompassButton(
                            bearing = camera.position.bearing,
                            onResetNorth = {
                                coroutineScope.launch {
                                    camera.animateTo(
                                        camera.position.copy(bearing = 0.0, tilt = 0.0)
                                    )
                                }
                            },
                        )
                        LayersButton(onClick = { showLayersSheet = true })
                        // Parking memory (P9): with no saved spot, tap saves the
                        // current location; with a saved spot, tap recenters on
                        // it and opens the parking sheet ("find my car").
                        FloatingActionButton(
                            onClick = {
                                val spot = parkingSpot
                                if (spot == null) {
                                    val p = userPosition
                                    if (p.latitude != 0.0 || p.longitude != 0.0) {
                                        parkingViewModel.saveParking(p.latitude, p.longitude)
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
                                    showParkingSheet = true
                                }
                            }
                        ) {
                            Text(stringResource(MapsR.string.parking_pin_glyph))
                        }
                        FloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    camera.animateTo(
                                        camera.position.copy(
                                            target = userPosition,
                                            zoom = maxOf(camera.position.zoom, 15.0),
                                        )
                                    )
                                }
                            }
                        ) {
                            IconMyLocation()
                        }
                    }
                }

                // Live navigation overlay (top maneuver card, bottom ETA strip,
                // recenter FAB, arrival card). Hidden when nav is Idle.
                NavigationOverlay(
                    navState = navState,
                    steps = activeRoute?.step ?: emptyList(),
                    autoFollow = autoFollow,
                    onRecenter = { autoFollow = true },
                    onEndTrip = {
                        com.vayunmathur.maps.util.NavigationSessionManager.stop()
                        context.stopService(android.content.Intent(context, com.vayunmathur.maps.util.NavigationService::class.java))
                        autoFollow = true
                        navNorthUp = false
                    },
                    onDismissArrival = {
                        com.vayunmathur.maps.util.NavigationSessionManager.stop()
                        context.stopService(android.content.Intent(context, com.vayunmathur.maps.util.NavigationService::class.java))
                    },
                    postedLimit = postedLimit,
                    northUp = navNorthUp,
                    onToggleNorthUp = { navNorthUp = !navNorthUp },
                    destinationName = com.vayunmathur.maps.util.NavigationSessionManager.destinationName,
                )

                // Map-layers toggle sheet (P6), opened from the LayersButton.
                if (showLayersSheet) {
                    LayersSheet(
                        onDismiss = { showLayersSheet = false },
                        trafficEnabled = trafficEnabled,
                        onTrafficChange = { settingsViewModel.setTrafficLayer(it) },
                        satelliteEnabled = satelliteEnabled,
                        onSatelliteChange = { settingsViewModel.setSatelliteLayer(it) },
                        safetyEnabled = safetyEnabled,
                        onSafetyChange = { settingsViewModel.setSafetyLayer(it) },
                        transitEnabled = transitEnabled,
                        onTransitChange = { settingsViewModel.setTransitLayer(it) },
                    )
                }

                // Parking sheet (P9): saved time + note, clear, and directions
                // back to the car through the existing routing path.
                if (showParkingSheet) {
                    parkingSpot?.let { spot ->
                        ParkingSheet(
                            spot = spot,
                            onDismiss = { showParkingSheet = false },
                            onClear = {
                                parkingViewModel.clear()
                                showParkingSheet = false
                            },
                            onDirections = {
                                val feature = spot.toFeature(
                                    context.getString(MapsR.string.parking_title)
                                )
                                if (selectedFeature is SpecificFeature.Route) {
                                    viewModel.setInactiveNavigation(selectedFeature as SpecificFeature.Route)
                                }
                                viewModel.set(SpecificFeature.Route(listOf(null, feature)))
                                showParkingSheet = false
                                coroutineScope.launch { scaffoldState.bottomSheetState.expand() }
                            },
                            onNoteChange = { parkingViewModel.updateNote(it) },
                        )
                    }
                }

                // Departure board (P10): opened by tapping a transit stop. Live
                // board from Transitous (online-only); dismiss clears selection.
                if (selectedTransitStop != null) {
                    DeparturesSheet(
                        state = departuresState,
                        onDismiss = { transitViewModel.closeStop() },
                        onRefresh = { transitViewModel.refresh() },
                    )
                }
            }
        }
    }
}

// Native basemap layers suppressed at runtime — amenities are Google-only now
// (custom overlay layer). Keeping this in code (vs editing style.json) makes it
// OTA-swappable per Decision D1.
private val SUPPRESSED_LAYERS = setOf("pois")

fun patchStyleForHybrid(
    jsonString: String,
    baseLocalUrl: String,
    hybridUrl: String,
    dark: Boolean = false,
): String {
    val json = Json { ignoreUnknownKeys = true }
    val root = json.parseToJsonElement(jsonString).jsonObject

    val newSources = buildJsonObject {
        putJsonObject("protomaps_base") {
            put("type", "vector")
            put("url", baseLocalUrl)
            // The v5-ca base data stops at z15 but the merged pmtiles advertise
            // maxzoom 16, so base layers vanish at z16 while the separate overlay
            // sources (safety/maxspeed/transit_lines/admin) keep rendering. Cap the
            // source at 15 so MapLibre OVERZOOMS z15 tiles past z15, keeping the
            // base visible at max zoom.
            put("maxzoom", 15)
        }
        putJsonObject("protomaps_hybrid") {
            put("type", "vector")
            put("url", hybridUrl)
            put("maxzoom", 15)
        }
    }

    val oldLayers = root["layers"]?.jsonArray ?: buildJsonArray {}
    val newLayers = buildJsonArray {
        oldLayers.forEach { layerElement ->
            val layer = layerElement.jsonObject
            val id = layer["id"]?.jsonPrimitive?.content ?: ""
            val type = layer["type"]?.jsonPrimitive?.content ?: ""

            // Suppress native basemap POIs at runtime (Decision D1) — amenities
            // are Google-only now, rendered on the custom overlay layer. Dropping
            // the source layer here (rather than editing style.json) keeps it
            // OTA-swappable. Also drops the would-be _base/_hybrid variants.
            if (id in SUPPRESSED_LAYERS) return@forEach

            // Dark palette (P14): recolor the base Protomaps paint at runtime so
            // we don't duplicate the 3544-line style.json. Only colour keys are
            // swapped; width/opacity/dasharray expressions are preserved.
            val darkPaint = if (dark) darkenPaint(id, layer["paint"] as? JsonObject) else null

            if (type == "background") {
                add(buildJsonObject {
                    layer.forEach { (k, v) -> if (!(dark && k == "paint")) put(k, v) }
                    if (darkPaint != null) put("paint", darkPaint)
                })
            } else {
                // Zoom 0-7: Base Local
                add(buildJsonObject {
                    layer.forEach { (k, v) -> if (!(dark && k == "paint")) put(k, v) }
                    if (darkPaint != null) put("paint", darkPaint)
                    put("id", "${id}_base")
                    put("source", "protomaps_base")
                    put("maxzoom", 7)
                })
                // Zoom 7+: Hybrid (Local Only)
                add(buildJsonObject {
                    layer.forEach { (k, v) -> if (!(dark && k == "paint")) put(k, v) }
                    if (darkPaint != null) put("paint", darkPaint)
                    put("id", "${id}_hybrid")
                    put("source", "protomaps_hybrid")
                    put("minzoom", 7)
                })
            }
        }
    }

    return buildJsonObject {
        root.forEach { (k, v) -> if (k != "sources" && k != "layers") put(k, v) }
        put("sources", newSources)
        put("layers", newLayers)
    }.toString()
}

/**
 * Rebuild a layer's `paint` for the dark palette (P14): copy every property
 * verbatim and only swap the colour keys, so zoom-driven width/opacity/dasharray
 * expressions keep working. `text-halo-width` etc. are left untouched. Layers
 * without a colour key (e.g. the icon-only `roads_oneway`) come back unchanged.
 */
private fun darkenPaint(id: String, paint: JsonObject?): JsonObject? {
    if (paint == null) return null
    val base = darkBaseColor(id)
    val (text, halo) = darkTextColors(id)
    return buildJsonObject {
        paint.forEach { (k, v) ->
            when (k) {
                "background-color", "fill-color", "line-color" -> put(k, base)
                "text-color" -> put(k, text)
                "text-halo-color" -> put(k, halo)
                else -> put(k, v)
            }
        }
    }
}

/**
 * Dark fill/line/background colour for a base Protomaps layer. Keyed by layer id
 * (the style's ids are stable), falling through prefix/substring rules for the
 * many road variants. Casing colours must be matched before the highway/major
 * rules because e.g. `roads_highway_casing_early` contains both tokens.
 */
private fun darkBaseColor(id: String): String = when {
    id == "background" || id == "earth" -> "#1b1d22"
    id == "water" -> "#0d1b2a"
    id == "water_stream" || id == "water_river" -> "#24455f"
    id == "landcover" -> "#1f2a22"
    id == "landuse_park" -> "#1e2b20"
    id == "landuse_urban_green" -> "#23362a"
    id == "landuse_hospital" -> "#2b2528"
    id == "landuse_industrial" -> "#20262b"
    id == "landuse_school" -> "#282520"
    id == "landuse_beach" -> "#2c2a22"
    id == "landuse_zoo" -> "#213030"
    id == "landuse_aerodrome" -> "#212228"
    id == "landuse_runway" -> "#2b2d33"
    id == "landuse_pedestrian" -> "#242229"
    id == "landuse_pier" -> "#202225"
    id.startsWith("landuse") -> "#1f2126"
    id == "buildings" -> "#22262c"
    id.startsWith("boundaries") -> "#4a4f57"
    id == "roads_rail" -> "#3a3e45"
    id.startsWith("roads_runway") || id.startsWith("roads_taxiway") -> "#2b2d33"
    id.contains("casing") -> "#111318"
    id.startsWith("roads_tunnels") -> "#2b2e35"
    id.contains("highway") || id.contains("major") || id.contains("link") -> "#464b54"
    id.startsWith("roads") -> "#34383f"
    else -> "#26282e"
}

/**
 * Dark (text-color, text-halo-color) for a label/POI symbol layer: light text on
 * a near-black halo so labels stay legible over the dark basemap. Water labels
 * keep a blue tint over the dark-navy water.
 */
private fun darkTextColors(id: String): Pair<String, String> = when (id) {
    "places_locality" -> "#e4e8ee" to "#101216"
    "places_country" -> "#9aa0aa" to "#101216"
    "places_region" -> "#80868f" to "#101216"
    "places_subplace" -> "#b0b6c0" to "#101216"
    "earth_label_islands" -> "#9aa0aa" to "#101216"
    "water_waterway_label", "water_label_ocean", "water_label_lakes" -> "#6f8fce" to "#0d1b2a"
    "roads_shields" -> "#c8ccd4" to "#101216"
    "roads_labels_major", "roads_labels_minor", "address_label" -> "#b8bdc6" to "#101216"
    else -> "#c9ced6" to "#101216"
}
