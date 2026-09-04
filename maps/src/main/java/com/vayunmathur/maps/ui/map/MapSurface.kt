package com.vayunmathur.maps.ui.map

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.ui.FreeHeightSheetState
import com.vayunmathur.maps.BuildConfig
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ipc.FamilyMember
import com.vayunmathur.maps.ui.FAMILY_LOCATION_LAYER_ID
import com.vayunmathur.maps.ui.MA_POIS_LAYER_ID
import com.vayunmathur.maps.ui.PARKING_PIN_LAYER_ID
import com.vayunmathur.maps.ui.SAVED_PLACE_LAYER_ID
import com.vayunmathur.maps.ui.SEARCH_RESULT_LAYER_ID
import com.vayunmathur.maps.ui.map.MapFeaturePicker.Companion.NATIVE_LABEL_LAYER_IDS
import com.vayunmathur.maps.ui.map.MapFeaturePicker.Companion.toFeature1
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.PoiCategories
import com.vayunmathur.maps.util.PoiIndex
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SearchResult
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.TransitStopsViewModel
import kotlinx.coroutines.launch
import org.maplibre.spatialk.geojson.Position

/**
 * The map surface: the renderer, its overlay layers, and what a tap on it means.
 *
 * Renders with library:map's [VectorMap] (Vulkan basemap, phone-side only). The overlay
 * pins/routes/puck are plain Compose in [MapLayers], hit-tested in-memory — the renderer
 * has no vector-layer API. Tile-baked place labels resolve through the native pick
 * ([queryRenderedLabels][com.vayunmathur.library.map.Projection.queryRenderedLabels]).
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
    userPosition: Position,
    userBearing: Float,
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
    // DEBUG-only dev override so device-verifier can point the smoke test at a
    // local v2 archive (prod planet.mamaps is v1 and won't open with the v2
    // reader). Release builds always use the prod default (null).
    val context = LocalContext.current
    val archivePath = remember(context) { resolveDevArchivePath(context) }

    VectorMap(
        cameraState = camera,
        modifier = modifier,
        darkBasemap = darkBasemap,
        archivePath = archivePath,
        // GAP (deferred, renderer has no raster-layer API): the Google traffic tiles
        // have nothing to mount on. [trafficEnabled] is kept so the toggle plumbing
        // survives; see also the no-op branch in [MapLayers].
        onMapClickWithScreen = { click ->
            coroutineScope.launch {
                val projection = camera.projection ?: return@launch
                val offset = click.screen
                // Pins live in Compose, not the renderer: rebuild the same features the
                // layers draw and hit-test them in screen space. Rebuilt per tap (not
                // remembered) so a pin added while the map is open is tappable on the
                // next tap, not the next recomposition.
                val pins = pinFeatures(
                    projection = projection,
                    searchResults = searchResults,
                    savedPlaces = savedPlaces,
                    parkingSpot = parkingSpot,
                    familyMembers = familyMembers,
                    poiFilterTypes = chrome.selectedCategory?.types,
                    zoom = camera.position.zoom,
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

                when (val hit = picker.pickPin(offset)) {
                    MapHit.Parking -> {
                        chrome.show(MapOverlay.Parking)
                        return@launch
                    }
                    is MapHit.Stop -> {
                        transitViewModel.openStop(hit.stop)
                        return@launch
                    }
                    is MapHit.Place -> {
                        // A tapped station POI carries no stop id: resolve the nearest
                        // baked stop and open its board instead of selecting the POI.
                        val station = hit.feature as? SpecificFeature.GenericPlace
                        if (station?.poiType == STATION_POI_TYPE) {
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

                // Fall back to the basemap's own place labels from the native pick.
                // Resolving one may make a Wikidata round-trip, so the ViewModel owns
                // that rather than this handler. Empty until the renderer registers
                // its pick (and when nothing placed hits) — then this falls through
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
            selectedFeature = selectedFeature,
            route = route,
            cameraState = camera,
            userPosition = userPosition,
            userBearing = userBearing,
            navProgress = navProgress,
            searchResults = searchResults,
            savedPlaces = savedPlaces,
            parkingSpot = parkingSpot,
            familyMembers = familyMembers,
            trafficEnabled = trafficEnabled,
            satelliteEnabled = satelliteEnabled,
            safetyEnabled = safetyEnabled,
            transitEnabled = transitEnabled,
            poiFilterTypes = chrome.selectedCategory?.types,
            darkBasemap = darkBasemap,
        )
    }
}

/**
 * Intent extra carrying a dev-only archive URL/path for the map renderer.
 *
 * DEBUG builds only (see [resolveDevArchivePath]): lets device-verifier point the
 * smoke test at a locally served v2 archive without touching the prod default.
 */
const val EXTRA_ARCHIVE_PATH = "maps.intent.extra.ARCHIVE_PATH"

/**
 * The renderer archive override, or null for the prod default.
 *
 * Reads [EXTRA_ARCHIVE_PATH] off the host Activity's launch intent. DEBUG-only by
 * construction: release builds return null unconditionally, so no launch flag (or
 * stale intent) can ever redirect prod traffic at a dev archive.
 */
private fun resolveDevArchivePath(context: android.content.Context): String? {
    if (!BuildConfig.DEBUG) return null
    val activity = context as? Activity ?: return null
    return activity.intent?.getStringExtra(EXTRA_ARCHIVE_PATH)?.ifBlank { null }
}

/**
 * OSM station-ish POI type whose taps open the departure board. Station POIs carry no
 * stop id of their own; see `TransitStopsViewModel.openNearestStop`.
 */
private const val STATION_POI_TYPE = 50

/** A pin feature tagged with the probe layer it belongs to. */
private data class TaggedFeature(val layerId: String, val feature: Feature1)

/**
 * The tappable pin set: every Compose-drawn pin as the [Feature1] its resolver
 * (`toSelected*`) already understands, tagged for [MapFeaturePicker]'s per-layer
 * probes. Built from the same inputs the layers draw from, so the hit-test can never
 * disagree with what is on screen. (The baked transit-stop pins are a renderer-side
 * no-op with no Compose pins, so the transit probe correctly finds nothing until
 * renderer vector-layer support lands.)
 */
private fun pinFeatures(
    projection: com.vayunmathur.library.map.Projection,
    searchResults: List<SearchResult>,
    savedPlaces: List<SavedPlace>,
    parkingSpot: ParkingSpot?,
    familyMembers: List<FamilyMember>,
    poiFilterTypes: Set<Int>?,
    zoom: Double,
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
    // Ambient POIs come from the offline index viewport with the same category
    // filter + min-zoom gating MaPoisLayer draws, so the hit-test matches the screen.
    val bounds = projection.queryVisibleBoundingBox()
    for (poi in PoiIndex.inViewport(bounds.west, bounds.south, bounds.east, bounds.north)
        .filter { hit -> (poiFilterTypes.isNullOrEmpty() || hit.type in poiFilterTypes) &&
            zoom >= PoiCategories.minZoom(hit.type) }) {
        add(TaggedFeature(MA_POIS_LAYER_ID, poiPinFeature(poi)))
    }
}
