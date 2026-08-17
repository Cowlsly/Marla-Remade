package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiPin
import com.vayunmathur.maps.util.MapTileCache
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SearchResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.sources.rememberVectorSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Position

@Composable
@MaplibreComposable
fun MyMapLayers(
    selectedFeature: SpecificFeature?,
    route: RouteService.RouteType?,
    styleJson: String?,
    userPosition: Position,
    userBearing: Float,
    navProgress: com.vayunmathur.maps.util.NavigationProgress? = null,
    googlePins: List<GooglePoiPin> = emptyList(),
    searchResults: List<SearchResult> = emptyList(),
    savedPlaces: List<com.vayunmathur.maps.data.SavedPlace> = emptyList(),
    parkingSpot: com.vayunmathur.maps.data.ParkingSpot? = null,
    transitStops: List<com.vayunmathur.maps.data.transit.TransitStop> = emptyList(),
    familyMembers: List<com.vayunmathur.maps.ipc.FamilyMember> = emptyList(),
    trafficEnabled: Boolean = true,
    satelliteEnabled: Boolean = false,
    safetyEnabled: Boolean = false,
    transitEnabled: Boolean = false,
) {
    val trafficVersion by OfflineRouter.trafficVersion.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        // OfflineRouter.initialize does asset-listing I/O — push to IO. The
        // @Synchronized fun itself is idempotent so recomposition is safe.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            OfflineRouter.initialize(context)
        }
    }

    key(styleJson) {
        var routeSource by remember { mutableStateOf<GeoJsonSource?>(null) }
        var userSource by remember { mutableStateOf<GeoJsonSource?>(null) }
        
        // Traffic tiles come from OfflineRouter's on-device loopback tile server,
        // which only has data to serve after a live traffic fetch has populated
        // the native store (trafficVersion increments then). Gating the source +
        // layer on trafficVersion > 0 (and a non-blank server URL) means we never
        // request http://localhost/traffic/... before the server/traffic is
        // actually available — that had spammed hundreds of "Failed to connect to
        // localhost" tile errors on every startup/pan.
        val trafficUrl = OfflineRouter.trafficTileUrl
        val trafficReady = trafficEnabled && trafficUrl.isNotBlank() && trafficVersion > 0
        val trafficSource = rememberVectorSource(
            tiles = if (trafficReady) listOf("$trafficUrl?v=$trafficVersion") else emptyList(),
            options = TileSetOptions(maxZoom = 14)
        )

        // Admin borders (country/region/city) are baked into the v5 basemap
        // PMTiles (P13), replacing the old admin0/admin1 FlatGeobuf assets. This
        // one vector source feeds the search/selection highlight below.
        val adminSource = rememberVectorSource(MapTileCache.BASEMAP_PMTILES_URL)

        LaunchedEffect(Unit) {
            userSource = GeoJsonSource(
                "user-location-geojson",
                GeoJsonData.Features(
                    Feature(
                        org.maplibre.spatialk.geojson.Point(userPosition),
                        JsonObject(mapOf("bearing" to JsonPrimitive(userBearing)))
                    )
                ),
                GeoJsonOptions()
            )
            routeSource = GeoJsonSource(
                "route-geojson",
                GeoJsonData.Features(
                    Feature1(
                        LineString(listOf(Position(0.0, 0.0), Position(0.0, 0.0))),
                        JsonObject(emptyMap())
                    )
                ),
                GeoJsonOptions()
            )
        }


        // Satellite / aerial imagery (P6, Decision D11). Gated: renders nothing
        // until a raster tile source is hosted. Drawn first so it sits beneath
        // the overlays.
        SatelliteLayer(satelliteEnabled)

        if (trafficReady) {
            LineLayer(
                "traffic-layer",
                trafficSource,
                sourceLayer = "traffic",
                color = feature["color"].cast<StringValue>().convertToColor(),
                width = interpolate(
                    linear(),
                    zoom(),
                    11 to const(0.8.dp),
                    12 to const(1.2.dp),
                    14 to const(2.dp),
                    18 to const(4.dp)
                ),
                opacity = const(0.6f),
                cap = const(LineCap.Butt)
            )
        }

        // Safety / road-furniture layer (P6). Gated on the P13 PMTiles v5.
        SafetyLayer(safetyEnabled)

        // Custom Google POI overlay (replaces suppressed native basemap POIs).
        // Rendered above traffic but below the user puck.
        GooglePoiLayer(googlePins)

        // Saved-place pins (Home / Work / starred list). Tap re-selects the
        // place → PlaceSheet (Vela's SavedPin).
        SavedPlacesLayer(savedPlaces)

        // Live family-location pins (P18), pushed by the findfamily bound
        // service while the map is open. Tap → select the person → Directions.
        FamilyLocationLayer(familyMembers)

        // Parking pin (P9). Tap → parking sheet (handled in MapPage.onMapClick).
        ParkingLayer(parkingSpot)

        // Nearby transit stops (P10). Shown only when the Transit layer is on;
        // tap a stop → live departure board (handled in MapPage.onMapClick).
        if (transitEnabled) {
            TransitStopsLayer(transitStops)
        }

        // Posted-speed-limit probe overlay (Decision D4). Invisible; queried
        // under the puck during navigation. No-op until the tileset is hosted.
        MaxspeedLayer()

        // Search-result pins (Vela MARKERS_LAYER analog) — drawn above the
        // ambient POI overlay so a query's hits stand out; tap re-selects.
        SearchResultLayer(searchResults)

        userSource?.let { src ->
            LaunchedEffect(userPosition, userBearing) {
                src.setData(
                    GeoJsonData.Features(
                        Feature(
                            org.maplibre.spatialk.geojson.Point(userPosition),
                            JsonObject(mapOf("bearing" to JsonPrimitive(userBearing)))
                        )
                    )
                )
            }

            org.maplibre.compose.layers.CircleLayer(
                "user-location-dot",
                src,
                color = const(Color(0xFF0E35F1)),
                radius = const(8.dp),
                strokeColor = const(Color.White),
                strokeWidth = const(2.dp)
            )

            org.maplibre.compose.layers.SymbolLayer(
                "user-location-bearing",
                src,
                iconImage = image(const("arrow")),
                iconRotate = feature["bearing"].cast(),
                iconRotationAlignment = const(org.maplibre.compose.expressions.value.IconRotationAlignment.Map),
                iconSize = const(0.6f),
                iconColor = const(Color(0xFF0E35F1))
            )
        }

        routeSource?.let { routeSource ->
            when (selectedFeature) {
                is SpecificFeature.Admin0Label ->
                    // Country highlight: filter the v5 admin_country layer by
                    // ISO_A2 (the key CountryMap.getAdmin0 matched on the FGB).
                    AdminHighlight(adminSource, "admin_country", "ISO_A2", selectedFeature.iso)
                is SpecificFeature.Admin1Label ->
                    // Region/state highlight: filter admin_region by iso_3166_2
                    // (the key CountryMap.getAdmin1 matched on the FGB).
                    AdminHighlight(adminSource, "admin_region", "iso_3166_2", selectedFeature.iso)
                is SpecificFeature.Route -> {
                    if (route != null) {
                        LaunchedEffect(
                            route, routeSource, styleJson,
                            navProgress?.segmentIndex,
                            navProgress?.distanceAlongRoute?.let { (it / 5.0).toInt() }
                        ) {
                            if (route is RouteService.Route) {
                                val features: List<Feature1> = buildRouteFeatures(
                                    route, context, navProgress
                                )
                                routeSource.setData(
                                    GeoJsonData.Features(FeatureCollection(features))
                                )
                            }
                        }
                        LineLayer(
                            "route",
                            routeSource,
                            color = feature["route-color"].cast<StringValue>().convertToColor(),
                            width = const(8.dp),
                            cap = const(LineCap.Round)
                        )
                    }
                }
                else -> Unit
            }
        }
    }
}

/**
 * Highlight the searched/selected admin area (country or region) from the v5
 * admin vector-tile layer, replacing the old FlatGeobuf inverted-mask.
 *
 * Vector tiles clip geometry per-tile, so rather than reconstruct the full
 * polygon for an inverted world-mask we render the feature itself — a translucent
 * fill plus a bold outline — filtered to the one feature whose [key] equals
 * [value] ([key]=`ISO_A2` for countries, `iso_3166_2` for regions). MapLibre
 * applies the filter across every tile the feature spans, so the whole area
 * lights up equivalently to the old mask. Matching by the same keys CountryMap
 * used keeps the search→highlight UX intact.
 */
@Composable
@MaplibreComposable
private fun AdminHighlight(
    source: VectorSource,
    sourceLayer: String,
    key: String,
    value: String,
) {
    val match = feature[key].cast<StringValue>() eq const(value)
    FillLayer(
        "admin-highlight-fill",
        source,
        sourceLayer = sourceLayer,
        filter = match,
        color = const(Color.Red.copy(alpha = 0.12f)),
    )
    LineLayer(
        "admin-highlight-outline",
        source,
        sourceLayer = sourceLayer,
        filter = match,
        color = const(Color.Red),
        width = const(3.dp),
    )
}

/**
 * Compute the per-step `route-color` for the static (non-navigating) case.
 * Driving uses traffic-aware red/amber/green, transit uses the GTFS feed
 * color when available, walk/bike fall through to a single blue.
 */
private fun staticColorFor(
    step: RouteService.Step,
    context: android.content.Context,
): String {
    return when (step.travelMode) {
        RouteService.TravelMode.DRIVE -> when {
            step.speedRatio < 0.5 -> "#F44336" // Red
            step.speedRatio < 0.9 -> "#FFC107" // Amber/Yellow
            else -> "#4CAF50"                  // Green
        }
        RouteService.TravelMode.TRANSIT -> {
            val feed = step.transitDetails?.feedName
            if (feed != null) {
                com.vayunmathur.maps.util.GTFSProvider.getRouteColor(
                    context, feed, step.transitDetails.transitLine.name
                ) ?: "#FF0000"
            } else "#FF0000"
        }
        else -> "#1710F1"
    }
}

/** Color shown for the portion of the route the user has already traveled. */
private const val TRAVELED_GRAY = "#9E9E9E"

/**
 * Build the GeoJSON `Feature` list for the route polyline.
 *
 * When [navProgress] is null this returns one feature per [Step] with the
 * mode-aware static color.
 *
 * When [navProgress] is non-null the polyline is split at the snapped point
 * so that:
 *  - steps strictly before the current step get the traveled-gray color
 *  - the current step is split: portion behind the snap → gray; portion
 *    ahead → original mode color
 *  - steps strictly after keep their original color
 *
 * Splitting at the segment level requires matching the snapped segment
 * index (which is into the FULL `route.polyline`) to the corresponding
 * vertex inside the current step's local polyline. The math here is the
 * mirror of [com.vayunmathur.maps.util.PolylineIndex]'s `stepRanges`
 * construction (cursor walk; steps share endpoints).
 */
private fun buildRouteFeatures(
    route: RouteService.Route,
    context: android.content.Context,
    navProgress: com.vayunmathur.maps.util.NavigationProgress?,
): List<Feature1> {
    if (navProgress == null) {
        return route.step.filter { it.polyline.size >= 2 }.map { step ->
            Feature1(
                LineString(step.polyline),
                JsonObject(mapOf("route-color" to JsonPrimitive(staticColorFor(step, context))))
            )
        }
    }

    val currentStepIdx = navProgress.currentStepIndex
    val snappedSegIdx = navProgress.segmentIndex // index into route.polyline
    val snappedPos = navProgress.snappedPosition

    val out = mutableListOf<Feature1>()
    // Walk the full polyline alongside the steps the same way PolylineIndex
    // builds stepRanges, so we know the vertex range for each step.
    var cursor = 0
    for ((stepIdx, step) in route.step.withIndex()) {
        val stepLen = step.polyline.size
        if (stepLen < 2) {
            // Nothing to render for a degenerate step. Cursor stays where it
            // was (mirrors PolylineIndex's `ranges.add(cursor..cursor)` /
            // skipping the cursor advance).
            continue
        }
        val first = cursor
        val last = (first + stepLen - 1).coerceAtMost(route.polyline.size - 1)
        val color = staticColorFor(step, context)

        when {
            stepIdx < currentStepIdx -> {
                // Entirely behind: gray.
                out += Feature1(
                    LineString(step.polyline),
                    JsonObject(mapOf("route-color" to JsonPrimitive(TRAVELED_GRAY)))
                )
            }
            stepIdx > currentStepIdx -> {
                // Entirely ahead: original color.
                out += Feature1(
                    LineString(step.polyline),
                    JsonObject(mapOf("route-color" to JsonPrimitive(color)))
                )
            }
            snappedSegIdx < first -> {
                // Snap fell on an earlier step than our step-index math
                // attributed to this step (e.g. brief off-by-one near a
                // boundary, or a glitchy GPS fix). Treat the whole step as
                // ahead rather than fabricating a gray spur from a snapped
                // position that isn't on this step's segments.
                out += Feature1(
                    LineString(step.polyline),
                    JsonObject(mapOf("route-color" to JsonPrimitive(color)))
                )
            }
            snappedSegIdx > last -> {
                // Snap fell on a later step. Treat the whole step as behind.
                out += Feature1(
                    LineString(step.polyline),
                    JsonObject(mapOf("route-color" to JsonPrimitive(TRAVELED_GRAY)))
                )
            }
            else -> {
                // The active step: split at the snap point. snappedSegIdx is
                // guaranteed in [first, last] by the two guards above.
                val localSnapVertex = snappedSegIdx - first
                // Behind portion: vertices 0..localSnapVertex, with the
                // snapped position appended so the gray ends exactly under
                // the user.
                val behindVertices = step.polyline.subList(0, localSnapVertex + 1).toMutableList()
                behindVertices.add(snappedPos)
                if (behindVertices.size >= 2) {
                    out += Feature1(
                        LineString(behindVertices),
                        JsonObject(mapOf("route-color" to JsonPrimitive(TRAVELED_GRAY)))
                    )
                }
                // Ahead portion: snapped position, then remaining vertices.
                val aheadVertices = mutableListOf(snappedPos)
                aheadVertices.addAll(step.polyline.subList(localSnapVertex + 1, stepLen))
                if (aheadVertices.size >= 2) {
                    out += Feature1(
                        LineString(aheadVertices),
                        JsonObject(mapOf("route-color" to JsonPrimitive(color)))
                    )
                }
            }
        }
        cursor = last
    }
    return out
}
