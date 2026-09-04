package com.vayunmathur.maps.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ipc.FamilyMember
import com.vayunmathur.maps.ui.FamilyLocationLayer
import com.vayunmathur.maps.ui.MaPoisLayer
import com.vayunmathur.maps.ui.ParkingLayer
import com.vayunmathur.maps.ui.RoadsLayer
import com.vayunmathur.maps.ui.RouteLayer
import com.vayunmathur.maps.ui.SafetyLayer
import com.vayunmathur.maps.ui.SatelliteLayer
import com.vayunmathur.maps.ui.SavedPlacesLayer
import com.vayunmathur.maps.ui.SearchResultLayer
import com.vayunmathur.maps.ui.TransitStopsLayer
import com.vayunmathur.maps.ui.drawUserIcon
import com.vayunmathur.maps.ui.theme.mapTokens
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.geojson.Position

/**
 * The overlay layers over the Vulkan basemap: pins, the route polyline and the user puck.
 *
 * Everything here is plain Compose positioned through library:map's Projection — the
 * renderer has no vector/raster layer API, so native map layers have nowhere to go.
 * Per-pin hit-testing moved with them: MapSurface rebuilds the same features these draw
 * and feeds them to [MapFeaturePicker].
 *
 * What still draws: ambient POIs (from the offline index, not the `ma_pois` tile layer),
 * saved places, search results, family members, the parking pin, the route polyline and
 * the user puck with its bearing arc.
 *
 * Deliberately NOT drawn (renderer gaps, reported to lead — symbol-renderer owns the
 * renderer API): the baked `roads` overlay (casing/surface/probe), the Google traffic
 * raster ([trafficEnabled] is kept so the toggle plumbing survives), the `safety` icons,
 * the `transit_lines` overlay and the `transit_stops` pins. The basemap itself still draws
 * its own road network; what is lost is the overlay styling plus the posted-limit probe.
 * Station POIs (type 50) still open the departure board via `openNearestStop`, so the
 * transit board itself keeps working.
 */
@Composable
fun MapLayers(
    selectedFeature: SpecificFeature?,
    route: RouteService.RouteType?,
    cameraState: CameraState,
    userPosition: Position,
    userBearing: Float,
    navProgress: NavigationProgress? = null,
    searchResults: List<SearchResult> = emptyList(),
    savedPlaces: List<SavedPlace> = emptyList(),
    parkingSpot: ParkingSpot? = null,
    familyMembers: List<FamilyMember> = emptyList(),
    trafficEnabled: Boolean = true,
    satelliteEnabled: Boolean = false,
    safetyEnabled: Boolean = false,
    transitEnabled: Boolean = false,
    poiFilterTypes: Set<Int>? = null,
    // Which basemap palette is in play, for the route colours. Not derivable from a style
    // JSON any more: there is no style JSON — the renderer takes a dark flag directly.
    darkBasemap: Boolean = false,
) {
    val context = LocalContext.current
    val tokens = remember(darkBasemap) { mapTokens(darkBasemap) }

    LaunchedEffect(Unit) {
        // OfflineRouter.initialize does asset-listing I/O — push to IO. The
        // @Synchronized fun itself is idempotent so recomposition is safe.
        withContext(Dispatchers.IO) {
            OfflineRouter.initialize(context)
        }
    }

    // Satellite / aerial imagery. Gated: renders nothing until a raster tile source
    // is hosted. Drawn first so it sits beneath the overlays.
    SatelliteLayer(satelliteEnabled)

    // Our own road rendering — no-op until the renderer can draw vector source-layers.
    // Drawn FIRST among the vector overlays so every pin, line and highlight below sits
    // on top of the roads once it lands.
    RoadsLayer()

    // Traffic raster ([trafficEnabled]) — no-op: the keyless Google tiles have no raster
    // layer to mount on. Toggle plumbing is kept so the sheet still works.
    @Suppress("UNUSED_EXPRESSION")
    trafficEnabled

    // Safety / road-furniture layer. Gated on the toggle; no-op until the renderer can
    // draw vector source-layers.
    SafetyLayer(safetyEnabled)

    // OSM transit-lines overlay — no-op until the renderer can draw vector source-layers.
    // Harmless while the source-layer is absent.
    @Suppress("UNUSED_EXPRESSION")
    transitEnabled

    // Ambient POI pins, rendered from the offline index.
    MaPoisLayer(cameraState, poiFilterTypes)

    // Saved-place pins (Home / Work / starred list). Tap re-selects the
    // place → PlaceSheet (Vela's SavedPin).
    SavedPlacesLayer(savedPlaces, cameraState)

    // Live family-location pins, pushed by the findfamily bound
    // service while the map is open. Tap → select the person → Directions.
    FamilyLocationLayer(familyMembers, cameraState)

    // Parking pin. Tap → parking sheet (handled in MapSurface.onMapClick).
    ParkingLayer(parkingSpot, cameraState)

    if (transitEnabled) {
        // Baked GTFS stop pins. No-op until the renderer can draw vector source-layers;
        // tap a stop → live departure board (handled in MapSurface.onMapClick).
        TransitStopsLayer()
    }

    // Search-result pins — drawn above the
    // ambient POI overlay so a query's hits stand out; tap re-selects.
    SearchResultLayer(searchResults, cameraState)

    // User puck with the bearing arc.
    Canvas(Modifier.fillMaxSize()) {
        drawUserIcon(userPosition, userBearing, cameraState)
    }

    if (selectedFeature is SpecificFeature.Route && route is RouteService.Route) {
        RouteLayer(route, navProgress, tokens, cameraState)
    }
}
