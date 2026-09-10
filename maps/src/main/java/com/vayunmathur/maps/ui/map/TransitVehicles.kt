package com.vayunmathur.maps.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.MapMarker
import com.vayunmathur.library.map.MarkerIcon
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.visibleBoundsOrWorld
import kotlinx.coroutines.delay

/**
 * How often the in-service vehicle set is recomputed, mirroring the ~1 Hz cadence the departures
 * board countdown uses ([com.vayunmathur.maps.ui.DeparturesSheet]). The native
 * `activeVehicles` folds schedule + realtime delay into each recompute; between recomputes the
 * renderer holds each sprite's last pushed position. One second of drift is a few metres at transit
 * speeds — small at the zooms this draws at — so a plain re-push each tick is the whole animation,
 * with no per-frame predictor. The native `Vehicle` carries a bearing but no speed, so there is no
 * cheap on-device dead-reckoning to do, and the shared sprite path is geography-agnostic; if smooth
 * 60 fps tweening is wanted later it belongs in the renderer's overlay draw, keyed on the stable
 * per-trip [OfflineRouter.Vehicle.id].
 */
private const val VEHICLE_TICK_MS = 1_000L

/**
 * Below this zoom the visible bbox spans too many in-service trips for a 1 Hz recompute to stay
 * cheap (the plan's own note on bounding the enumeration to the viewport), and the sprites would be
 * an unreadable swarm anyway. Matches the spirit of the traffic prefetch's own zoom gate.
 */
private const val VEHICLE_MIN_ZOOM = 11.0

/**
 * The simulated in-service transit vehicles for the visible bbox, recomputed at ~1 Hz and mapped to
 * renderer markers, or an empty list when the transit layer is off, the surface is hidden, or the
 * camera is zoomed too far out (see [VEHICLE_MIN_ZOOM]).
 *
 * Drive [com.vayunmathur.library.map.VectorMap]'s `vehicles` with the return value: it is pushed on
 * its own cadence, apart from the app pins, so a vehicle recompute never churns the pins and the
 * moving sprites stay out of the pin tap-pick. The loop samples the camera's live bounds each tick,
 * so a pan or zoom re-targets the enumeration without restarting the effect. Gated on the lifecycle
 * with the same ON_START/ON_STOP observer the renderer uses, so a backgrounded or hidden map stops
 * recomputing and clears its vehicles rather than leaving a stale swarm behind.
 */
@Composable
fun rememberTransitVehicles(
    camera: CameraState,
    transitEnabled: Boolean,
): List<MapMarker> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var started by remember { mutableStateOf(false) }
    var vehicles by remember { mutableStateOf(emptyList<MapMarker>()) }

    // The same ON_START/ON_STOP gate VulkanMapSurface stops the renderer on: a STARTED-but-not-
    // RESUMED surface (split-screen, PiP) is on screen and must keep its vehicles moving.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> started = true
                Lifecycle.Event.ON_STOP -> started = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(transitEnabled, started) {
        if (!transitEnabled || !started) {
            vehicles = emptyList()
            return@LaunchedEffect
        }
        while (true) {
            val bounds = camera.visibleBoundsOrWorld()
            vehicles = if (camera.position.zoom < VEHICLE_MIN_ZOOM) {
                emptyList()
            } else {
                OfflineRouter.activeVehicles(
                    context,
                    minLat = bounds.south,
                    minLon = bounds.west,
                    maxLat = bounds.north,
                    maxLon = bounds.east,
                ).map { v ->
                    MapMarker(
                        id = v.id,
                        position = GeoPoint(longitude = v.lon, latitude = v.lat),
                        icon = gtfsModeToMarkerIcon(v.mode),
                    )
                }
            }
            delay(VEHICLE_TICK_MS)
        }
    }
    return vehicles
}

/**
 * The renderer marker icon for a coarse GTFS mode label (see
 * [OfflineRouter.Vehicle.mode]). Trams/monorails, the rail family (subway/rail/funicular/aerial),
 * ferries and everything else (bus/trolleybus and any unknown) each fold onto one of the four
 * reserved `VEHICLE_*` sprites, which is as fine as the shared atlas draws.
 */
internal fun gtfsModeToMarkerIcon(mode: String): Int = when (mode) {
    "TRAM", "MONORAIL" -> MarkerIcon.VEHICLE_TRAM
    "SUBWAY", "RAIL", "FUNICULAR", "AERIAL" -> MarkerIcon.VEHICLE_TRAIN
    "FERRY" -> MarkerIcon.VEHICLE_FERRY
    else -> MarkerIcon.VEHICLE_BUS
}
