package com.vayunmathur.maps.ui.map

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoBounds
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.LayerOptions
import com.vayunmathur.library.map.MapMarker
import com.vayunmathur.library.map.MapOptions
import com.vayunmathur.library.map.MarkerIcon
import com.vayunmathur.library.map.RegionLevel
import com.vayunmathur.library.map.RegionMask
import com.vayunmathur.library.map.RouteOverlay
import com.vayunmathur.library.map.TrafficColorTable
import com.vayunmathur.library.map.UserPuck
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.ui.FreeHeightSheetState
import com.vayunmathur.maps.BuildConfig
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.osmPlace
import com.vayunmathur.maps.ipc.FamilyMember
import com.vayunmathur.maps.ui.FAMILY_LOCATION_LAYER_ID
import com.vayunmathur.maps.ui.PARKING_PIN_LAYER_ID
import com.vayunmathur.maps.ui.SAVED_PLACE_LAYER_ID
import com.vayunmathur.maps.ui.SEARCH_RESULT_LAYER_ID
import com.vayunmathur.maps.ui.map.MapFeaturePicker.Companion.NATIVE_LABEL_LAYER_IDS
import com.vayunmathur.maps.ui.map.MapFeaturePicker.Companion.toFeature1
import com.vayunmathur.maps.ui.theme.MapTokens
import com.vayunmathur.maps.ui.theme.mapTokens
import com.vayunmathur.maps.ui.toSelectedFamilyMember
import com.vayunmathur.maps.ui.toSelectedSavedPlace
import com.vayunmathur.maps.ui.toSelectedSearchResult
import com.vayunmathur.maps.util.MapTileCache
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.PoiCategories
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SearchResult
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.TransitStopsViewModel
import com.vayunmathur.maps.util.visibleBoundsOrWorld
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The map surface: the renderer, its overlay layers, and what a tap on it means.
 *
 * Renders with library:map's [VectorMap] (Vulkan basemap, phone-side only). The overlay
 * pins and route are plain Compose in [MapLayers], hit-tested in-memory - the renderer
 * has no vector-layer API for them. The user puck is the exception: it has no hit-testing,
 * so it moved into the renderer as a [UserPuck] and no longer drags behind a pan.
 * Tile-baked place labels resolve through the native pick
 * ([queryRenderedLabels][com.vayunmathur.library.map.Projection.queryRenderedLabels]).
 *
 * The transit toggle is the one layer the renderer draws itself, via [LayerOptions]; see
 * [pinFeatures] for what that means for hit-testing.
 */
@Composable
fun MapSurface(
    camera: CameraState,
    chrome: MapChromeState,
    viewModel: SelectedFeatureViewModel,
    searchViewModel: MapsSearchViewModel,
    transitViewModel: TransitStopsViewModel,
    sheetState: FreeHeightSheetState,
    selectedFeature: SpecificFeature?,
    route: RouteService.RouteType?,
    userPosition: GeoPoint,
    userBearing: Float?,
    navProgress: NavigationProgress?,
    searchResults: List<SearchResult>,
    savedPlaces: List<SavedPlace>,
    parkingSpot: ParkingSpot?,
    familyMembers: List<FamilyMember>,
    trafficEnabled: Boolean,
    satelliteEnabled: Boolean,
    safetyEnabled: Boolean,
    transitEnabled: Boolean,
    darkBasemap: Boolean,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    // The archive the renderer opens: the copy on this device if there is one, else null,
    // which leaves the renderer streaming the published archive by range request.
    val context = LocalContext.current
    val archivePath = remember(context) { resolveArchivePath(context) }

    // The renderer has carried a transit layer all along; :maps simply never asked for it,
    // so flipping the layers switch drew nothing but Compose stops. Remembered because
    // VulkanMapSurface keys a LaunchedEffect on this by equality and a fresh instance every
    // recomposition would churn it.
    val selectedCategory = chrome.selectedCategory
    val mapOptions = remember(transitEnabled, selectedCategory, trafficEnabled) {
        MapOptions(
            layerOptions = LayerOptions(
                poi = true,
                poiKinds = selectedCategory?.kinds.orEmpty(),
                transit = transitEnabled,
                // Gates the baked traffic layer's geometry (renderer layer 10). The colours
                // ride in separately via [trafficColors] below.
                traffic = trafficEnabled,
            ),
        )
    }

    // The live per-component colours: resolved from the current palette on the device (the
    // renderer only looks them up), pushed to the renderer as an id->ARGB table. Null when the
    // toggle is off or there is nothing to draw, which clears the overlay on the next frame —
    // the accumulated data stays cached in OfflineRouter so re-enabling is instant.
    val tokens = remember(darkBasemap) { mapTokens(darkBasemap) }
    val components by OfflineRouter.trafficComponents.collectAsState()
    val trafficColors = remember(components, tokens, trafficEnabled) {
        if (trafficEnabled) buildTrafficColorTable(components, tokens) else null
    }

    // The route, drawn inside the renderer's frame so it pans in lock-step with the basemap
    // (this is what retired the Compose `RouteLayer`). Recomputed on the same cadence that
    // layer used: on a route or palette change, and on the navigation bucket it keyed on
    // (`segmentIndex` + `distanceAlongRoute` quantised to 5 m), since the mid-step travelled
    // split moves the geometry. Only built when a route is actually selected.
    val routeOverlay = remember(
        selectedFeature, route, tokens,
        navProgress?.segmentIndex,
        navProgress?.distanceAlongRoute?.let { (it / 5.0).toInt() },
    ) {
        if (selectedFeature is SpecificFeature.Route && route is RouteService.Route) {
            buildRouteOverlay(route, navProgress, tokens)
        } else {
            null
        }
    }

    // The library takes a nullable GeoPoint, so :maps' two sentinels are converted here
    // and go no further: GeoPoint(0, 0) is this app's "no fix" and means null, and a null
    // bearing means no heading yet rather than due north.
    val userPuck = remember(userPosition, userBearing) {
        if (userPosition.latitude == 0.0 && userPosition.longitude == 0.0) {
            null
        } else {
            UserPuck(userPosition, userBearing)
        }
    }

    // The app's pins, moved into the renderer so they pan and tilt glued to the basemap instead of
    // trailing it a frame the way the Compose pin overlays did. Each pin gets a stable id and a
    // parallel map from that id to what a tap on it means, so a tap resolves through the renderer's
    // id buffer ([Projection.pickMarker]) rather than a CPU hit-test. Rebuilt only when the pin
    // inputs change, so a pan does not churn it. See [buildMarkers].
    val (markers, markerHits) = remember(searchResults, savedPlaces, parkingSpot, familyMembers) {
        buildMarkers(searchResults, savedPlaces, parkingSpot, familyMembers)
    }

    // Simulated in-service transit vehicles for the visible bbox, recomputed at ~1 Hz and drawn by
    // the renderer as billboarded sprites on their own overlay, apart from the pins above. Gated on
    // the transit toggle and the lifecycle, and empty below its zoom gate, so it costs nothing when
    // transit is off, the map is hidden, or the viewport is too wide to enumerate cheaply.
    val vehicles = rememberTransitVehicles(camera, transitEnabled)

    // Prefetch traffic for the visible 1° squares once the camera settles, so the overlay has
    // data independent of route search (today traffic is fetched only during a route search).
    // The native side dedups squares for the session, so a re-pan over an already-fetched
    // square is a cheap no-op and the fetched data is kept. Keyed on the toggle so it stops
    // when traffic is off. `collectLatest { delay() }` is the repo's debounce idiom (see
    // GooglePoiMapViewModel): a newer camera position cancels the pending delay, so the fetch
    // only fires after the camera stops moving.
    LaunchedEffect(camera, trafficEnabled) {
        if (!trafficEnabled) return@LaunchedEffect
        snapshotFlow { camera.position }.collectLatest {
            delay(TRAFFIC_PREFETCH_DEBOUNCE_MS)
            // The component overlay is dense and zoom-gated in the archive/renderer, and a
            // world-zoom viewport would enumerate hundreds of 1° squares, so only prefetch
            // once zoomed in enough for it to be useful.
            if (camera.position.zoom < TRAFFIC_MIN_ZOOM) return@collectLatest
            prefetchTrafficSquares(camera.visibleBoundsOrWorld())
        }
    }

    VectorMap(
        cameraState = camera,
        modifier = modifier,
        darkBasemap = darkBasemap,
        archivePath = archivePath,
        options = mapOptions,
        userPuck = userPuck,
        // The selected city/region's outline, dimmed outside. Derived from the sheet's own
        // selection rather than the tap, so a region picked from search masks too — and the
        // label's own kind supplies the admin level, because the point alone is inside every
        // region above it and would otherwise resolve to the smallest, not the one named.
        regionMask = when (selectedFeature) {
            is SpecificFeature.Admin0Label ->
                selectedFeature.position?.let { RegionMask(it, RegionLevel.COUNTRY) }
            is SpecificFeature.Admin1Label ->
                selectedFeature.position?.let { RegionMask(it, RegionLevel.REGION) }
            is SpecificFeature.Admin2Label ->
                selectedFeature.position?.let { RegionMask(it, RegionLevel.LOCALITY) }
            else -> null
        },
        // The live traffic overlay: baked geometry gated by [LayerOptions.traffic] above,
        // coloured by this id->ARGB table. Null clears it (toggle off / nothing to draw).
        trafficColors = trafficColors,
        // The route line, coloured per segment (traffic/transit/travelled) and drawn inside
        // the renderer's frame so it stays glued to the basemap on a pan. Null draws nothing.
        route = routeOverlay,
        // The app's pins, drawn by the renderer as billboarded sprites so they pan/tilt in
        // lock-step with the basemap. Their taps resolve through the id buffer below.
        markers = markers,
        // Simulated transit vehicles, on their own ~1 Hz overlay under the pins. Not tap targets.
        vehicles = vehicles,
        onMapClickWithScreen = { click ->
            coroutineScope.launch {
                val projection = camera.projection ?: return@launch
                val offset = click.screen
                // Renderer-drawn pins resolve through the GPU id buffer first: correct under tilt,
                // and never a frame behind the basemap the way the old Compose hit-test was. On a
                // miss (e.g. a pin pushed this very frame, before the id buffer caught up) fall back
                // to the CPU hit-test over the same features, which is tilt-aware via Projection.
                val hit = markerHits[projection.pickMarker(offset.x.value, offset.y.value)]
                    ?: run {
                        val pins = pinFeatures(
                            searchResults = searchResults,
                            savedPlaces = savedPlaces,
                            parkingSpot = parkingSpot,
                            familyMembers = familyMembers,
                        )
                        val picker = MapFeaturePicker(
                            source = FeatureSource { box, layerIds ->
                                featuresInBox(
                                    pins.filter { it.layerId in layerIds }.map { it.feature },
                                    box,
                                    projection,
                                )
                            },
                            transitEnabled = transitEnabled,
                        )
                        picker.pickPin(offset)
                    }

                when (hit) {
                    MapHit.Parking -> {
                        chrome.show(MapOverlay.Parking)
                        return@launch
                    }
                    is MapHit.Stop -> {
                        // Whatever place sheet was up is replaced, not stacked under: one tap
                        // should take one back to undo, and a board over a stale sheet takes two.
                        viewModel.set(null)
                        transitViewModel.openStop(hit.stop)
                        return@launch
                    }
                    is MapHit.Place -> {
                        // A tapped station POI carries no stop id: resolve the nearest
                        // baked stop and open its board instead of selecting the POI.
                        val station = hit.feature as? SpecificFeature.GenericPlace
                        if (station?.poiType == STATION_POI_TYPE) {
                            viewModel.set(null)
                            transitViewModel.openNearestStop(
                                station.position.latitude,
                                station.position.longitude,
                            )
                            return@launch
                        }
                        viewModel.stashRouteSelection()
                        viewModel.set(hit.feature)
                        sheetState.partialExpand()
                        return@launch
                    }
                    null -> Unit
                }

                // A POI the renderer actually drew, from the same collision pass that put
                // it on screen. Ranked below the app's own pins deliberately: a saved place
                // or a search result the user put there outranks ambient map furniture
                // underneath it, which is the order the old probe chain had too.
                val poi = click.poi
                if (poi != null) {
                    val type = PoiCategories.typeOfKind(poi.kind)
                    // A transit stop carries no stop id, so its tap opens the nearest baked
                    // stop's departure board rather than a place sheet.
                    if (PoiCategories.opensDepartureBoard(poi.kind)) {
                        viewModel.set(null)
                        transitViewModel.openNearestStop(
                            poi.position.latitude,
                            poi.position.longitude,
                            // The pack names a stop by its MOTIS id, which is a machine string.
                            // The tapped POI already has the name a person would recognise.
                            name = poi.name.ifBlank { null },
                        )
                        return@launch
                    }
                    // The archive carries a POI's kind, name and point and nothing else, so
                    // phone, website, hours and address are still joined from the offline
                    // index on IO inside `osmPlace`.
                    viewModel.stashRouteSelection()
                    viewModel.set(osmPlace(poi.name, poi.position, poiType = type))
                    sheetState.partialExpand()
                    return@launch
                }

                // Fall back to the basemap's own place labels from the native pick.
                // Resolving one may make a Wikidata round-trip, so the ViewModel owns
                // that rather than this handler. `VulkanMapSurface` registers the native
                // pick, so this is populated whenever a placed label is under the finger;
                // when nothing is, it falls through to reverse-geocode exactly like a
                // blank tap.
                // to reverse-geocode exactly like a blank tap.
                val label = viewModel.resolveAdminLabel(
                    projection.queryRenderedLabels(
                        DpRect(offset, DpSize.Zero),
                        NATIVE_LABEL_LAYER_IDS,
                    ).mapNotNull { it.toFeature1() }
                )
                if (label != null) {
                    viewModel.stashRouteSelection()
                    viewModel.set(label)
                    sheetState.partialExpand()
                    return@launch
                }

                // Nothing hit: reverse-geocode the point ("what's here?"). Online-only.
                val geo = click.position
                searchViewModel.reverseGeocode(geo.latitude, geo.longitude) { place ->
                    if (place != null) {
                        viewModel.stashRouteSelection()
                        viewModel.set(place)
                        coroutineScope.launch { sheetState.partialExpand() }
                    }
                }
            }
        },
    ) {
        MapLayers(
            cameraState = camera,
            satelliteEnabled = satelliteEnabled,
            safetyEnabled = safetyEnabled,
            transitEnabled = transitEnabled,
        )
    }
}

/**
 * Intent extra carrying a dev-only archive URL/path for the map renderer.
 *
 * DEBUG builds only (see [resolveArchivePath]): lets device-verifier point the
 * smoke test at a locally served archive without touching the prod default.
 */
const val EXTRA_ARCHIVE_PATH = "maps.intent.extra.ARCHIVE_PATH"

/**
 * Which archive the renderer opens, in precedence order.
 *
 * 1. [EXTRA_ARCHIVE_PATH] off the host Activity's launch intent. DEBUG-only by
 *    construction, so no launch flag (or stale intent) can redirect a release build.
 * 2. The device's own copy, downloaded once on first launch or `adb push`ed over it —
 *    [MapTileCache.localArchive] cannot tell the two apart, which is what makes
 *    sideloading a freshly tiled archive a push rather than a rebuild.
 * 3. Null, which leaves the renderer streaming the published archive by range request.
 *    Only reachable if the download gate was somehow satisfied without the file, since
 *    `InitialDownloadChecker` will not let the app start without it.
 */
private fun resolveArchivePath(context: android.content.Context): String? {
    val override = if (BuildConfig.DEBUG) {
        (context as? Activity)?.intent?.getStringExtra(EXTRA_ARCHIVE_PATH)?.ifBlank { null }
    } else {
        null
    }
    return override ?: MapTileCache.localArchive(context)
}

/**
 * OSM station-ish POI type whose taps open the departure board. Station POIs carry no
 * stop id of their own; see `TransitStopsViewModel.openNearestStop`.
 */
private const val STATION_POI_TYPE = PoiCategories.STATION_TYPE

/** A pin feature tagged with the probe layer it belongs to. */
private data class TaggedFeature(val layerId: String, val feature: Feature1)

/**
 * How long the camera must be still before a traffic prefetch fires. Matches the browse-time
 * debounce the POI driver uses; long enough that a fling does not fetch every square it flies
 * over, short enough that the overlay fills in promptly once you stop.
 */
private const val TRAFFIC_PREFETCH_DEBOUNCE_MS = 400L

/**
 * Below this zoom the per-component traffic overlay is neither drawn (the archive zoom-gates
 * it) nor worth fetching — a zoomed-out viewport spans too many 1° squares to enumerate.
 */
private const val TRAFFIC_MIN_ZOOM = 11.0

/**
 * Safety cap on how many 1° squares a single settle may request, so an unexpectedly wide
 * viewport (near the antimeridian, or a viewport measured before the zoom gate applies) can
 * never fan out into hundreds of fetches. At [TRAFFIC_MIN_ZOOM] a settled viewport is a
 * handful of squares, well under this.
 */
private const val TRAFFIC_MAX_SQUARES = 16L

/**
 * Ask the native prefetch to load traffic for every 1° square the viewport touches.
 *
 * [ensureTrafficLoadedNative] re-derives the packed square from the point and dedups, so
 * passing each cell's centre is enough and repeats are free. A viewport that would span more
 * than [TRAFFIC_MAX_SQUARES] cells (or an inverted/antimeridian box) is skipped rather than
 * enumerated.
 */
private fun prefetchTrafficSquares(bounds: GeoBounds) {
    val lonMin = floor(bounds.west).toInt()
    val lonMax = floor(bounds.east).toInt()
    val latMin = floor(bounds.south).toInt()
    val latMax = floor(bounds.north).toInt()
    val cells = (lonMax - lonMin + 1).toLong() * (latMax - latMin + 1).toLong()
    if (cells < 1L || cells > TRAFFIC_MAX_SQUARES) return
    for (lat in latMin..latMax) {
        for (lon in lonMin..lonMax) {
            OfflineRouter.ensureTrafficLoadedNative(lat + 0.5, lon + 0.5, true)
        }
    }
}

/**
 * Resolve the component-level traffic readings into an id→ARGB table for the renderer.
 *
 * Buckets mirror `staticColorFor` in `RouteOverlayBuilder`: `ratio < 0.5` jam, `< 0.9` slow,
 * else free, where `ratio = ratio_pct / 100`. `ratio_pct == 0` is "no data" and is dropped — an id
 * absent from the table draws nothing, so those segments fall back to the plain basemap road.
 * Colours come from [tokens], which already encodes the light/dark palette, so the renderer
 * only looks them up. Returns null when nothing is left to draw.
 */
private fun buildTrafficColorTable(
    components: OfflineRouter.TrafficComponents,
    tokens: MapTokens,
): TrafficColorTable? {
    val n = components.ids.size
    if (n == 0) return null
    val outIds = LongArray(n)
    val outArgb = IntArray(n)
    var k = 0
    for (i in 0 until n) {
        val pct = components.ratioPct[i].toInt() and 0xFF
        if (pct == 0) continue
        val ratio = pct / 100.0
        val color = when {
            ratio < 0.5 -> tokens.traffic.jam
            ratio < 0.9 -> tokens.traffic.slow
            else -> tokens.traffic.free
        }
        outIds[k] = components.ids[i]
        outArgb[k] = color.toArgb()
        k++
    }
    if (k == 0) return null
    return TrafficColorTable(outIds.copyOf(k), outArgb.copyOf(k))
}

/**
 * The tappable pin set: every Compose-drawn pin as the [Feature1] its resolver
 * (`toSelected*`) already understands, tagged for [MapFeaturePicker]'s per-layer
 * probes. Built from the same inputs the layers draw from, so the hit-test can never
 * disagree with what is on screen.
 *
 * ## Known limitation: renderer transit lines are not tappable
 *
 * The transit toggle now drives the renderer's own transit layer, so rail and route lines
 * are drawn in the basemap. Those are **geometry, not pins** — they have no Compose
 * counterpart here, and the native pick answers placed *labels* only, which is why
 * extending [MapFeaturePicker.NATIVE_LABEL_LAYER_IDS] would not reach them either
 * (`toFeature1` resolves admin place ids and nothing else). Tapping a rail line therefore
 * falls through to reverse-geocode, exactly as tapping empty basemap does. Live-departure
 * stop pins are unaffected: those are Compose-drawn and still probe normally.
 */
private fun pinFeatures(
    searchResults: List<SearchResult>,
    savedPlaces: List<SavedPlace>,
    parkingSpot: ParkingSpot?,
    familyMembers: List<FamilyMember>,
): List<TaggedFeature> = buildList {
    if (parkingSpot != null) {
        add(TaggedFeature(PARKING_PIN_LAYER_ID, parkingPinFeature(parkingSpot)))
    }
    for (result in searchResults) {
        add(TaggedFeature(SEARCH_RESULT_LAYER_ID, searchPinFeature(result)))
    }
    for (place in savedPlaces) {
        add(TaggedFeature(SAVED_PLACE_LAYER_ID, savedPinFeature(place)))
    }
    for (member in familyMembers) {
        add(TaggedFeature(FAMILY_LOCATION_LAYER_ID, familyPinFeature(member)))
    }
}

/**
 * The renderer marker set for the app's pins, plus a map from each marker's id to the [MapHit] a
 * tap on it means, resolved once here so the tap path is a lookup.
 *
 * The renderer draws these (billboarded, glued to the basemap); a tap reads back the marker's id
 * from the id buffer and this map turns it into the same [MapHit] the old CPU picker produced. Ids
 * are assigned per pin kind in disjoint ranges so they stay unique and never collide with the
 * pick-buffer's `0` = "nothing". A pin whose feature does not resolve (a malformed saved place, say)
 * is dropped rather than drawn as an untappable dot — the same outcome the CPU picker's
 * `firstNotNullOfOrNull` gave.
 */
private fun buildMarkers(
    searchResults: List<SearchResult>,
    savedPlaces: List<SavedPlace>,
    parkingSpot: ParkingSpot?,
    familyMembers: List<FamilyMember>,
): Pair<List<MapMarker>, Map<Long, MapHit>> {
    val markers = ArrayList<MapMarker>()
    val hits = HashMap<Long, MapHit>()
    // Disjoint id ranges per kind: 1 for the single parking pin, then a decade each for the lists.
    var id = 1L
    if (parkingSpot != null) {
        markers.add(MapMarker(id, GeoPoint(parkingSpot.lon, parkingSpot.lat), MarkerIcon.PARKING))
        hits[id] = MapHit.Parking
        id++
    }
    id = 100_000L
    for (result in searchResults) {
        val place = searchPinFeature(result).toSelectedSearchResult() ?: continue
        markers.add(MapMarker(id, GeoPoint(result.lon, result.lat), MarkerIcon.SEARCH))
        hits[id] = MapHit.Place(place)
        id++
    }
    id = 200_000L
    for (saved in savedPlaces) {
        val place = savedPinFeature(saved).toSelectedSavedPlace() ?: continue
        markers.add(MapMarker(id, GeoPoint(saved.lon, saved.lat), MarkerIcon.SAVED))
        hits[id] = MapHit.Place(place)
        id++
    }
    id = 300_000L
    for (member in familyMembers) {
        val place = familyPinFeature(member).toSelectedFamilyMember() ?: continue
        markers.add(MapMarker(id, GeoPoint(member.lng, member.lat), MarkerIcon.FAMILY))
        hits[id] = MapHit.Place(place)
        id++
    }
    return markers to hits
}
