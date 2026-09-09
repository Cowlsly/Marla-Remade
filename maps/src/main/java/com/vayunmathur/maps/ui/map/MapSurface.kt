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
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.LayerOptions
import com.vayunmathur.library.map.MapOptions
import com.vayunmathur.library.map.RegionLevel
import com.vayunmathur.library.map.RegionMask
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
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.PoiCategories
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SearchResult
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.TransitStopsViewModel
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
    // DEBUG-only dev override so device-verifier can point the smoke test at a
    // locally built archive (e.g. a freshly tiled california.mamaps). Release
    // builds always use the prod default (null).
    val context = LocalContext.current
    val archivePath = remember(context) { resolveDevArchivePath(context) }

    // The renderer has carried a transit layer all along; :maps simply never asked for it,
    // so flipping the layers switch drew nothing but Compose stops. Remembered because
    // VulkanMapSurface keys a LaunchedEffect on this by equality and a fresh instance every
    // recomposition would churn it.
    val selectedCategory = chrome.selectedCategory
    val mapOptions = remember(transitEnabled, selectedCategory) {
        MapOptions(
            layerOptions = LayerOptions(
                poi = true,
                poiKinds = selectedCategory?.kinds.orEmpty(),
                transit = transitEnabled,
            ),
        )
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

                when (val hit = picker.pickPin(offset)) {
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
            selectedFeature = selectedFeature,
            route = route,
            cameraState = camera,
            navProgress = navProgress,
            searchResults = searchResults,
            savedPlaces = savedPlaces,
            parkingSpot = parkingSpot,
            familyMembers = familyMembers,
            trafficEnabled = trafficEnabled,
            satelliteEnabled = satelliteEnabled,
            safetyEnabled = safetyEnabled,
            transitEnabled = transitEnabled,
            darkBasemap = darkBasemap,
        )
    }
}

/**
 * Intent extra carrying a dev-only archive URL/path for the map renderer.
 *
 * DEBUG builds only (see [resolveDevArchivePath]): lets device-verifier point the
 * smoke test at a locally served archive without touching the prod default.
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
private const val STATION_POI_TYPE = PoiCategories.STATION_TYPE

/** A pin feature tagged with the probe layer it belongs to. */
private data class TaggedFeature(val layerId: String, val feature: Feature1)

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
