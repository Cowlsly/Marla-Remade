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
import com.vayunmathur.maps.data.CountryMap
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiPin
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.RouteService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
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
import org.maplibre.compose.sources.rememberVectorSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Polygon
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
        var outlineSource by remember { mutableStateOf<GeoJsonSource?>(null) }
        var userSource by remember { mutableStateOf<GeoJsonSource?>(null) }
        
        val trafficUrl = OfflineRouter.trafficTileUrl
        val trafficSource = rememberVectorSource(
            tiles = if (trafficUrl.isNotBlank()) listOf("$trafficUrl?v=$trafficVersion") else emptyList(),
            options = TileSetOptions(maxZoom = 14)
        )

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
            outlineSource = GeoJsonSource(
                "selected-country-geojson",
                GeoJsonData.Features(
                    Feature(
                        Polygon(
                            coordinates =
                            listOf(
                                listOf(
                                    Position(-180.0, -90.0),
                                    Position(180.0, -90.0),
                                    Position(180.0, 90.0),
                                    Position(-180.0, 90.0),
                                    Position(-180.0, -90.0)
                                )
                            )
                        ),
                        JsonObject(emptyMap())
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

        // Custom Google POI overlay (replaces suppressed native basemap POIs).
        // Rendered above traffic but below the user puck.
        GooglePoiLayer(googlePins)

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

        outlineSource?.let { outlineSource ->
            routeSource?.let { routeSource ->
                when (selectedFeature) {
                    is SpecificFeature.Admin0Label -> {
                        LaunchedEffect(selectedFeature, outlineSource, styleJson) {
                            // Loading the admin0 FlatGeobuf is a few MB +
                            // linear scan — do it on IO. And tolerate ISOs
                            // missing from the asset (small territories,
                            // Wikidata changes) instead of crashing.
                            val polygon = kotlinx.coroutines.withContext(
                                kotlinx.coroutines.Dispatchers.IO
                            ) {
                                runCatching { CountryMap.getAdmin0(context, selectedFeature.iso) }.getOrNull()
                            } ?: return@LaunchedEffect
                            outlineSource.setData(
                                GeoJsonData.Features(
                                    FeatureCollection(
                                        listOf(createInvertedMask(polygon))
                                    )
                                )
                            )
                        }
                        FillLayer(
                            "global-mask",
                            outlineSource,
                            color = const(Color.Black.copy(alpha = 0.4f))
                        )
                        LineLayer(
                            "layer2",
                            outlineSource,
                            color = const(Color.Red)
                        )
                    }
                    is SpecificFeature.Admin1Label -> {
                        LaunchedEffect(selectedFeature, outlineSource, styleJson) {
                            val polygon = kotlinx.coroutines.withContext(
                                kotlinx.coroutines.Dispatchers.IO
                            ) {
                                runCatching { CountryMap.getAdmin1(context, selectedFeature.iso) }.getOrNull()
                            } ?: return@LaunchedEffect
                            outlineSource.setData(
                                GeoJsonData.Features(
                                    FeatureCollection(
                                        listOf(createInvertedMask(polygon))
                                    )
                                )
                            )
                        }
                        FillLayer(
                            "global-mask",
                            outlineSource,
                            color = const(Color.Black.copy(alpha = 0.4f))
                        )
                        LineLayer(
                            "layer2",
                            outlineSource,
                            color = const(Color.Red)
                        )
                    }
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
}

private fun createInvertedMask(countryFeature: Feature1): Feature1 {
    // 1. World Rectangle (Clockwise)
    val worldOuterRing =
        listOf(
            Position(-180.0, 90.0), // Top Left
            Position(180.0, 90.0), // Top Right
            Position(180.0, -90.0), // Bottom Right
            Position(-180.0, -90.0), // Bottom Left
            Position(-180.0, 90.0) // Close
        )

    // 2. Extract and Reverse the country rings (force them to be holes)
    val holes =
        when (val geom = countryFeature.geometry) {
            is Polygon -> listOf(geom.coordinates.first().reversed())
            is MultiPolygon -> geom.coordinates.map { it.first().reversed() }
            else -> emptyList()
        }

    // 3. Create Polygon: [Outer, Hole1, Hole2...]
    val donutGeometry = Polygon(listOf(worldOuterRing) + holes)
    return Feature(geometry = donutGeometry, properties = countryFeature.properties)
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
