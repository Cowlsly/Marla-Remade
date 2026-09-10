package com.vayunmathur.maps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.CompassCalibrationHint
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FreeHeightBottomSheetScaffold
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.OverlayAction
import com.vayunmathur.library.ui.SheetValue
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.TopAppBarOverlay
import com.vayunmathur.library.ui.rememberFreeHeightSheetState
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ui.map.LayerToggles
import com.vayunmathur.maps.ui.map.MapFabStack
import com.vayunmathur.maps.ui.map.MapOverlay
import com.vayunmathur.maps.ui.map.MapOverlays
import com.vayunmathur.maps.ui.map.MapSearchBar
import com.vayunmathur.maps.ui.map.MapSurface
import com.vayunmathur.maps.ui.map.NavigationCameraFollow
import com.vayunmathur.maps.ui.map.WaypointList
import com.vayunmathur.maps.ui.map.rememberMapChromeState
import com.vayunmathur.maps.ui.streetview.StreetViewPegman
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.util.MapSettingsViewModel
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.PoiIndex
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SearchActions
import com.vayunmathur.maps.util.SearchResult
import com.vayunmathur.maps.util.SearchUiState
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.visibleBoundsOrWorld
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.maps.R as MapsR

/** Cold-start camera: San Francisco at z14, where the baked POIs are dense enough to see. */
private val INITIAL_CAMERA = CameraPosition(target = GeoPoint(-122.4194, 37.7749), zoom = 14.0)

/**
 * An open search sheet.
 *
 * Search used to be a nav destination, so these three lived in the route. They are still needed —
 * the bias centre because the Google search is biased toward what the user can see, and
 * [waypointIndex] because picking a result while editing a route replaces that leg rather than
 * selecting a place — but now they are the identity of a sheet, not of a page. `null` is the
 * closed state, which is why the whole thing is one nullable value rather than a flag plus three
 * fields that only mean anything while the flag is set.
 */
private data class SearchRequest(
    val waypointIndex: Int?,
    val nearLat: Double,
    val nearLon: Double,
)

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
    val searchQuery by searchViewModel.query.collectAsState()
    val searchRecents by searchViewModel.recents.collectAsState()
    val searching by searchViewModel.searching.collectAsState()
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

    val navState by NavigationSessionManager.state.collectAsState()
    // Collected, not read off a field: a recalculation swaps the route mid-session, and reading
    // it as a plain property meant this screen kept drawing the OLD route's steps against the
    // new progress until something else happened to recompose.
    val navSession by NavigationSessionManager.session.collectAsState()
    val isNavigating = navState !is NavigationSessionManager.NavState.Idle
    val navProgress = (navState as? NavigationSessionManager.NavState.Navigating)?.progress

    val sheetState = rememberFreeHeightSheetState(SheetValue.Hidden)
    // A second, independent sheet stacked over the first, rather than a mode of it. The two have
    // different peek heights and different lifetimes, and driving one state through both would
    // mean re-measuring the sheet at the moment its content swaps — which is exactly when the
    // learned content ceiling and the peek floor disagree.
    val searchSheetState = rememberFreeHeightSheetState(SheetValue.Hidden)
    var searchRequest by remember { mutableStateOf<SearchRequest?>(null) }
    // How much of the bottom the collapsed search bar is occupying, so the FAB stack and the
    // scale bar can clear it the same way they clear a sheet. Zero whenever the bar is not drawn.
    var searchBarLiftPx by remember { mutableIntStateOf(0) }
    val searchState = SearchUiState(
        searchQuery, searchResults, searchRecents, savedHome, savedWork, searching,
    )
    val browsing = selectedFeature == null && inactiveNavigation == null && !isNavigating
    // The bar is chrome, not a sheet, so it is composed away rather than animated out. Its
    // measured height therefore has to be discounted explicitly — a stale one would leave the
    // FAB stack floating above a bar that is no longer there.
    val searchBarVisible = browsing && searchRequest == null

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

    // GAP (deferred, renderer has no vector-layer query API): the posted-limit probe
    // used to hit-test the baked `roads` overlay under the puck. Until renderer query
    // support lands the badge shows without a limit; clear any stale value instead of
    // leaving the previous trip's limit up.
    LaunchedEffect(navProgress) {
        if (navProgress == null) chrome.postedLimit = null
    }

    fun openSearch(query: String? = null, waypointIndex: Int? = null) {
        val bbox = camera.visibleBoundsOrWorld()
        val nearLat = (bbox.north + bbox.south) / 2.0
        val nearLon = (bbox.east + bbox.west) / 2.0
        // The category chips and the voice transcript both arrive as a query to run, which used
        // to be a route argument the destination replayed on arrival. There is no arrival any
        // more, so it runs here.
        if (!query.isNullOrBlank()) searchViewModel.setQuery(query, nearLat, nearLon)
        searchRequest = SearchRequest(waypointIndex, nearLat, nearLon)
    }

    // Latched rather than derived, so the hand-back below cannot fire on first composition. A
    // cold start can arrive with a place already selected and a deep link about to focus it, and
    // that path owns the pane — see the `pendingFocus` effect above.
    var searchWasOpen by remember { mutableStateOf(false) }

    // Rebuilt only when the label changes: `TopAppBarOverlay` takes a list, so an inline one
    // would be a fresh instance every recomposition of the map.
    val settingsLabel = stringResource(MapsR.string.settings_title)
    val settingsAction = remember(settingsLabel, backStack) {
        listOf(
            OverlayAction(
                icon = { IconSettings() },
                contentDescription = settingsLabel,
                onClick = { backStack.add(Route.SettingsPage) },
            )
        )
    }

    // The two sheets take turns: search covers the place pane while it is up, and hands it back
    // on the way out. The selection is read off the ViewModel rather than the collected state
    // because picking a result sets it and closes search in the same breath, and the flow has not
    // necessarily delivered by the time this effect restarts.
    LaunchedEffect(searchRequest) {
        if (searchRequest != null) {
            searchWasOpen = true
            launch { sheetState.hide() }
            searchSheetState.partialExpand()
        } else if (searchWasOpen) {
            searchWasOpen = false
            launch { searchSheetState.hide() }
            if (viewModel.selectedFeature.value != null) sheetState.partialExpand()
        }
    }

    // Selecting anything on the still-live map underneath supersedes the search: the user has
    // found what they were looking for by pointing at it.
    LaunchedEffect(selectedFeature) {
        if (selectedFeature != null) searchRequest = null
    }

    // Clearing the selection is enough: the `LaunchedEffect(selectedFeature)` above is the single
    // dismissal path for every sheet state, so back does not hide the sheet itself. Doing both
    // raced two `hide()` coroutines against each other for no benefit.
    BackHandler(selectedFeature != null) {
        viewModel.set(null)
    }

    BackHandler(selectedFeature == null && inactiveNavigation != null) {
        viewModel.setInactiveNavigation(null)
    }

    // Registered last so it wins: back out of search before back does anything to the selection
    // the search sheet is currently covering.
    BackHandler(searchRequest != null) {
        searchRequest = null
    }

    // The search sheet is the outer of the two, so it draws over the place pane rather than
    // fighting it for the bottom of the window. Its content lambda is empty while search is
    // closed, which the scaffold treats as "no sheet" and does not place — so this wrapper costs
    // nothing at all on the browse screen.
    FreeHeightBottomSheetScaffold({
        val request = searchRequest
        if (request != null) {
            val searchActions = remember(request) {
                object : SearchActions {
                    override fun setQuery(query: String) {
                        searchViewModel.setQuery(query, request.nearLat, request.nearLon)
                    }

                    override fun clearRecents() {
                        searchViewModel.clearRecents()
                    }

                    override fun selectSavedPlace(place: SavedPlace) {
                        viewModel.set(place.toFeature())
                        searchRequest = null
                    }

                    override fun selectResult(result: SearchResult) {
                        searchViewModel.recordRecent(result.title)
                        // Launched because building the feature reads the POI attribute sidecar
                        // off the main thread; the close stays inside so the selection is in
                        // place before the sheet hands the screen back.
                        coroutineScope.launch {
                            val feature = searchViewModel.toFeature(result)
                            val index = request.waypointIndex
                            // The selection can have changed under the sheet — the map is live —
                            // so tolerate a non-Route current selection rather than crashing.
                            val current = viewModel.selectedFeature.value
                            if (index != null && current is SpecificFeature.Route) {
                                viewModel.set(
                                    current.copy(
                                        waypoints = current.waypoints.mapIndexed { i, waypoint ->
                                            if (i == index) feature else waypoint
                                        }
                                    )
                                )
                            } else {
                                viewModel.set(feature)
                            }
                            searchRequest = null
                        }
                    }

                    override fun pickContactAddress(address: String) {
                        searchViewModel.searchAndSelectFirst(
                            address, request.nearLat, request.nearLon,
                        ) { first ->
                            if (first != null) selectResult(first)
                        }
                    }

                    override fun back() {
                        searchRequest = null
                    }
                }
            }
            SearchSheet(searchState, searchActions, Modifier.padding(top = Spacing.sm))
        }
    },
        Modifier,
        searchSheetState,
        // Roughly a third of the room the sheet has: enough to open on the field, the chips and
        // the first result or two, and no more. This is a floor as much as an opening height —
        // a drag cannot take the sheet below its peek — so a taller one would mean the map could
        // never be more than half uncovered for as long as search is open, which is the opposite
        // of the point. A fraction rather than a dp because it is a statement about the window,
        // not about the sheet's own type: it has to hold on a tablet and in landscape, and it
        // must not move when the user changes font size.
        sheetPeekFraction = 0.35f,
        // The phase, not just "is search open": the three phases with nothing to list are a fixed
        // height, so a sheet that opened on one of them has learned a ceiling that would trap the
        // results list at it once results arrive.
        contentKey = searchState.phase.takeIf { searchRequest != null },
    ) { _ ->
        FreeHeightBottomSheetScaffold({
            // Padding only when there is something to pad. An unconditional wrapper measures its
            // own padding even with no content, which defeats the scaffold's "don't place a sheet
            // that measured to nothing" guard and leaves a bare handle on screen.
            if (selectedFeature != null || route != null || inactiveNavigation != null) {
                Column(Modifier.padding(horizontal = Spacing.lg).padding(top = Spacing.sm)) {
                    BottomSheetContent(
                        viewModel,
                        selectedFeature,
                        route,
                        chrome.selectedRouteType,
                        { chrome.selectedRouteType = it },
                        savedPlacesViewModel,
                        transitViewModel,
                        navState,
                    )
                }
            }
        },
            Modifier,
            sheetState,
            MapChromeMetrics.sheetPeekHeight,
            // The peek is measured off this rather than taken from sheetPeekHeight, so a place
            // peeks at exactly its title through its action row whatever the font scale. A
            // selection with no header falls back to the fixed height.
            sheetHeader = {
                BottomSheetHeader(
                    viewModel,
                    selectedFeature,
                    { viewModel.set(it) },
                    inactiveNavigation,
                    savedPlacesViewModel,
                    Modifier.padding(horizontal = Spacing.lg).padding(top = Spacing.sm),
                )
            },
            contentKey = listOf(selectedFeature, chrome.selectedRouteType),
        ) { _ ->
            // No app bar. The map is the whole screen and every piece of chrome floats over it,
            // which is also what keeps the renderer's surface edge-to-edge — a padded parent here
            // is what used to leave a dead strip along the navigation bar.
            Box(Modifier.fillMaxSize()) {
                MapSurface(
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

                // A direct child of the map's own box, not the inset one below: the peg's
                // drop point is read in this box's coordinates and projected as-is.
                //
                // Gated on search separately from `browsing` because the sheets draw over
                // this box: while search is up, a drop in the bottom third lands on the sheet
                // rather than the map, and half a working drag is worse than no peg.
                if (browsing && searchRequest == null) StreetViewPegman(camera)

                // The chrome's inset, in one place. `windowInsetsPadding` *consumes* what it
                // applies, so the pieces below that inset themselves — the FAB stack, the scale
                // bar, the navigation overlay, the overlay bar — become no-ops rather than
                // insetting twice, and the waypoint list, which never did, picks up the status
                // bar it used to get from the app bar.
                Box(Modifier.windowInsetsPadding(WindowInsets.systemBars).fillMaxSize()) {
                    val routeFeature =
                        (selectedFeature as? SpecificFeature.Route) ?: inactiveNavigation
                    Column(Modifier.align(Alignment.TopCenter)) {
                        // Turn-by-turn puts its maneuver banner in this exact slot, so the bar
                        // stands down for the duration rather than floating over it.
                        if (!isNavigating) {
                            TopAppBarOverlay(
                                actions = settingsAction,
                                // The chips ride in the bar's title rather than in a row of their
                                // own below it. They bring their own filled containers, which is
                                // what keeps them readable over the map; the bar itself stays
                                // transparent, so the map runs straight through behind them.
                                title = {
                                    // Dropped while a route is up: the waypoint list takes that
                                    // space and filtering POIs is not what you are doing then —
                                    // the same rule the chips followed before they moved. The
                                    // slot stays, so the settings button does not shift.
                                    if (routeFeature == null) {
                                        CategoryChips(
                                            onCategory = { chrome.toggleCategory(it) },
                                            selected = chrome.selectedCategory,
                                            // Inside the scroll, never as a margin: the chips have
                                            // to slide past the screen inset rather than clip
                                            // against it. The bar gives its title no inset of its
                                            // own precisely so this can be the only one.
                                            contentPadding = PaddingValues(
                                                start = Spacing.lg,
                                                end = Spacing.sm,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                        if (routeFeature != null) {
                            WaypointList(
                                route = routeFeature,
                                onReorder = { viewModel.set(it) },
                                onEditWaypoint = { index -> openSearch(waypointIndex = index) },
                            )
                        } else {
                            // The quiet variant: a hint over the map, not a card. See
                            // [CompassCalibrationHint].
                            CompassCalibrationHint(
                                accuracy = userHeadingAccuracy,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = Spacing.sm),
                            )
                        }
                    }

                    // The search entry point, where the top app bar's used to be but at the other
                    // end of the screen. Browse-only: an open sheet — either of them — owns the
                    // bottom and brings its own field or its own title.
                    if (searchBarVisible) {
                        MapSearchBar(
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
                                        // Fly there and open the peek pane, the same direct-open
                                        // path a geo:/maps deep link takes.
                                        viewModel.selectAndFocus(
                                            place,
                                            zoom = maxOf(camera.position.zoom, 14.0),
                                        )
                                    } else {
                                        messenger.show(noResultsMessage)
                                    }
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                // Measured, not assumed: the bar's height is what the FAB stack
                                // and the scale bar have to clear, and it moves with the font
                                // scale. Outside the padding, so the margin is included.
                                .onSizeChanged { searchBarLiftPx = it.height }
                                .padding(MapChromeMetrics.chromeMargin),
                        )
                    }

                    // Browse controls, plus the layers and settings buttons, which stay out while
                    // a place is selected and ride above the sheet — see [MapFabStack].
                    // GAP (deferred, camera is target+zoom only): bearing is always 0
                    // north-up, so the compass hides itself and reset-north is a no-op.
                    MapFabStack(
                        zoom = camera.position.zoom,
                        latitude = camera.position.target.latitude,
                        bearing = 0.0,
                        browsing = browsing,
                        // Whichever of the three is occupying the bottom: at most one sheet is
                        // ever up, and the search bar is only drawn when neither is.
                        lift = {
                            maxOf(
                                sheetState.liftPx,
                                searchSheetState.liftPx,
                                if (searchBarVisible) searchBarLiftPx.toFloat() else 0f,
                            ).roundToInt()
                        },
                        // GAP (deferred, camera is target+zoom only): nothing to reset —
                        // the map is always north-up. Kept so the control slot survives.
                        onResetNorth = {},
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
                                            target = GeoPoint(spot.lon, spot.lat),
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
                        // GAP (deferred, camera is target+zoom only): heading-up follow
                        // is unsupported — the map is always north-up. The toggle slot is
                        // kept so the nav chrome survives; it records the preference for
                        // when the renderer can rotate.
                        onToggleNorthUp = { chrome.northUp = !chrome.northUp },
                        destinationName = navSession.destinationName,
                        darkBasemap = darkMap,
                        route = navSession.route,
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
}

/** End the session and stop the foreground service that outlives this screen. */
private fun stopNavigation(context: android.content.Context) {
    NavigationSessionManager.stop()
    context.stopService(
        android.content.Intent(context, com.vayunmathur.maps.util.NavigationService::class.java)
    )
}
