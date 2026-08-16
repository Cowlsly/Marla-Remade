package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SearchActions
import com.vayunmathur.maps.util.SearchResult
import com.vayunmathur.maps.util.SearchUiState
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:maps`. See `common-conventions-preview-metadata`.
 *
 * The basemap is a MapLibre native surface fed by pmtiles, and routing goes through the
 * Rust offline router over JNI — neither exists inside Layoutlib, so [MapPage] itself is
 * not previewable and is deliberately left alone. What these render is the Compose chrome
 * around the map: search, the directions panel, and the turn-by-turn overlay. The
 * navigation preview therefore shows the overlay over a flat surface rather than over the
 * map it normally floats on.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 */
class MetadataPreviews {

    private fun step(
        instruction: String,
        maneuver: RouteService.API.Maneuver,
        meters: Double,
        durationSeconds: Long,
    ) = RouteService.Step(
        distanceMeters = meters,
        staticDuration = durationSeconds.seconds,
        polyline = emptyList(),
        navInstruction = RouteService.API.NavInstruction(maneuver, instruction),
        travelMode = RouteService.TravelMode.DRIVE,
    )

    private val driveSteps = listOf(
        step("Head north on Valencia St", RouteService.API.Maneuver.DEPART, 240.0, 55),
        step("Turn right onto 16th St", RouteService.API.Maneuver.TURN_RIGHT, 880.0, 150),
        step("Turn left onto Folsom St", RouteService.API.Maneuver.TURN_LEFT, 1320.0, 220),
        step("Continue onto The Embarcadero", RouteService.API.Maneuver.STRAIGHT, 2050.0, 300),
        step("Keep right at the fork", RouteService.API.Maneuver.FORK_RIGHT, 610.0, 90),
        step("Merge onto Bay St", RouteService.API.Maneuver.MERGE, 940.0, 140),
        step("Slight left onto Ferry Plaza", RouteService.API.Maneuver.TURN_SLIGHT_LEFT, 310.0, 60),
    )

    private val ferryBuilding = SpecificFeature.GenericPlace(
        name = "Ferry Building",
        phone = null,
        website = null,
        openingHours = null,
        position = Position(-122.3933, 37.7955),
    )

    @PreviewTest
    @Preview(name = "1-search", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Search() {
        DynamicTheme(darkTheme = true) {
            SearchScreen(
                SearchUiState(
                    query = "ferry",
                    results = listOf(
                        SearchResult("1", "Ferry Building Marketplace", "1 Ferry Building, San Francisco", 37.7955, -122.3933, "Marketplace"),
                        SearchResult("2", "Ferry Plaza Farmers Market", "1 Ferry Building, San Francisco", 37.7959, -122.3937, "Farmers market"),
                        SearchResult("3", "Golden Gate Ferry Terminal", "Pier 1, San Francisco", 37.7936, -122.3927, "Ferry terminal"),
                        SearchResult("4", "Oakland Ferry Dock", "Clay St, Oakland", 37.7947, -122.2783, "Ferry terminal"),
                    ),
                ),
                SearchActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-directions", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Directions() {
        DynamicTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                // Same padding the bottom sheet gives this panel on the map page.
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    RouteSheet(
                        selectedFeature = SpecificFeature.Route(listOf(null, ferryBuilding)),
                        route = mapOf(
                            RouteService.TravelMode.DRIVE to RouteService.Route(
                                duration = 18.minutes,
                                distanceMeters = 6350.0,
                                polyline = emptyList(),
                                step = driveSteps,
                            ),
                            RouteService.TravelMode.TRANSIT to RouteService.Route(
                                duration = 31.minutes,
                                distanceMeters = 7100.0,
                                polyline = emptyList(),
                                step = driveSteps,
                            ),
                            RouteService.TravelMode.WALK to RouteService.Route(
                                duration = 82.minutes,
                                distanceMeters = 5900.0,
                                polyline = emptyList(),
                                step = driveSteps,
                            ),
                            RouteService.TravelMode.BICYCLE to RouteService.Route(
                                duration = 24.minutes,
                                distanceMeters = 6100.0,
                                polyline = emptyList(),
                                step = driveSteps,
                            ),
                        ),
                        selectedRouteType = RouteService.TravelMode.DRIVE,
                        setSelectedRouteType = {},
                    )
                }
            }
        }
    }

    @PreviewTest
    @Preview(name = "3-navigating", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Navigating() {
        DynamicTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                NavigationOverlay(
                    navState = NavigationSessionManager.NavState.Navigating(
                        NavigationProgress(
                            snappedPosition = Position(-122.4041, 37.7815),
                            segmentIndex = 14,
                            currentStepIndex = 2,
                            distanceAlongRoute = 2280.0,
                            distanceRemaining = 4070.0,
                            distanceToNextManeuver = 280.0,
                            fractionComplete = 0.36,
                            // The ETA strip counts down against the wall clock, so an
                            // absolute literal would render as "0 min". Offsetting from
                            // now is what keeps the strip showing a plausible trip.
                            etaEpochMs = System.currentTimeMillis() + 11 * 60_000L,
                            courseOverGround = 47f,
                            distanceOffRoute = 2.5,
                        )
                    ),
                    steps = driveSteps,
                    autoFollow = false,
                    onRecenter = {},
                    onEndTrip = {},
                    onDismissArrival = {},
                )
            }
        }
    }
}
