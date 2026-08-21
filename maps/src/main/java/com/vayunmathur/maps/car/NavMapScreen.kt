package com.vayunmathur.maps.car

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.vayunmathur.maps.R
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.NavigationService
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.NavigationSessionManager.NavState
import com.vayunmathur.maps.util.RouteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.maplibre.spatialk.geojson.Position
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The main Android Auto screen (P12): a full-screen map drawn to the car
 * [android.view.Surface] by [CarMapRenderer], with a [NavigationTemplate] on top.
 *
 * While a navigation session is active (driven by the existing
 * [NavigationSessionManager] — the same singleton the phone uses) the template
 * shows the upcoming maneuver, step cue, distance to the maneuver, lane guidance
 * (P5a) and an ETA/remaining-distance [TravelEstimate], and the camera follows
 * the puck heading-up. When idle it shows the map centred on the current
 * location with a Search action.
 *
 * This screen only *renders* nav state and forwards camera/route/puck to the
 * renderer; all routing, progress, reroute and voice guidance stay in the
 * existing stack.
 */
class NavMapScreen(carContext: CarContext) : Screen(carContext) {

    private val renderer = CarMapRenderer(carContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java)
                    .setSurfaceCallback(renderer)
                // Prime the idle camera before the first nav fix arrives.
                pushCameraAndOverlay(NavigationSessionManager.state.value)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                renderer.destroy()
                scope.cancel()
            }
        })

        scope.launch {
            NavigationSessionManager.state.collect { state ->
                pushCameraAndOverlay(state)
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val state = NavigationSessionManager.state.value
        val builder = NavigationTemplate.Builder()
            .setActionStrip(buildActionStrip(state))

        when (state) {
            is NavState.Navigating -> {
                buildRoutingInfo(state.progress)?.let { builder.setNavigationInfo(it) }
                buildTravelEstimate(state.progress)?.let { builder.setDestinationTravelEstimate(it) }
            }
            NavState.Starting,
            NavState.Recalculating ->
                builder.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
            else -> { /* idle / arrived / failed: map + action strip only */ }
        }
        return builder.build()
    }

    // ----------------------------------------------------------------
    // Renderer feeding
    // ----------------------------------------------------------------

    private fun pushCameraAndOverlay(state: NavState) {
        renderer.setRoute(NavigationSessionManager.session.value.route?.polyline ?: emptyList())
        val progress = (state as? NavState.Navigating)?.progress
        if (progress != null) {
            renderer.setPuck(progress.snappedPosition)
            renderer.setCamera(
                target = progress.snappedPosition,
                zoom = 17.0,
                bearing = progress.courseOverGround.toDouble(),
                tilt = 45.0,
            )
        } else {
            val pos = lastKnownPosition()
            if (pos != null) {
                renderer.setPuck(pos)
                renderer.setCamera(pos, 15.0, 0.0, 0.0)
            }
        }
    }

    // ----------------------------------------------------------------
    // Template building
    // ----------------------------------------------------------------

    private fun buildActionStrip(state: NavState): ActionStrip {
        val builder = ActionStrip.Builder()
        builder.addAction(
            Action.Builder()
                .setTitle(carContext.getString(R.string.car_search_action))
                .setOnClickListener { screenManager.push(CarSearchScreen(carContext)) }
                .build()
        )
        val navigating = state is NavState.Navigating ||
            state is NavState.Recalculating || state is NavState.Starting
        if (navigating) {
            builder.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.nav_action_end))
                    .setOnClickListener { stopNavigation() }
                    .build()
            )
        }
        return builder.build()
    }

    private fun buildRoutingInfo(progress: NavigationProgress): RoutingInfo? {
        val steps = NavigationSessionManager.session.value.route?.step ?: return null
        val info = RoutingInfo.Builder()
        // The maneuver the driver is approaching is the START of the next step;
        // distanceToNextManeuver counts down to it (matches NavigationTts).
        val upcomingIdx = progress.currentStepIndex + 1
        if (upcomingIdx in steps.indices) {
            info.setCurrentStep(
                carStep(steps[upcomingIdx], withLanes = true),
                metersToDistance(progress.distanceToNextManeuver),
            )
            val nextIdx = upcomingIdx + 1
            if (nextIdx in steps.indices) {
                info.setNextStep(carStep(steps[nextIdx], withLanes = false))
            }
        } else {
            // On the final step: point at the destination.
            val destName = NavigationSessionManager.session.value.destinationName
                ?: carContext.getString(R.string.nav_default_destination)
            val dest = Step.Builder(destName)
                .setManeuver(Maneuver.Builder(Maneuver.TYPE_DESTINATION).build())
                .build()
            info.setCurrentStep(dest, metersToDistance(progress.distanceRemaining))
        }
        return info.build()
    }

    private fun carStep(step: RouteService.Step, withLanes: Boolean): Step {
        val cue = step.navInstruction.instructions
            .ifBlank { carContext.getString(R.string.car_continue) }
        val builder = Step.Builder(cue)
            .setManeuver(CarManeuvers.build(step.navInstruction.maneuver))
        if (withLanes && step.lanes.isNotEmpty()) {
            CarManeuvers.lanes(step.lanes).forEach { builder.addLane(it) }
        }
        return builder.build()
    }

    private fun buildTravelEstimate(progress: NavigationProgress): TravelEstimate? {
        val arrival = DateTimeWithZone.create(progress.etaEpochMs, TimeZone.getDefault())
        val remainingSec = ((progress.etaEpochMs - System.currentTimeMillis()) / 1000)
            .coerceAtLeast(0)
        return TravelEstimate.Builder(metersToDistance(progress.distanceRemaining), arrival)
            .setRemainingTimeSeconds(remainingSec)
            .build()
    }

    private fun metersToDistance(meters: Double): Distance =
        if (meters < 1000.0) {
            Distance.create(meters.roundToInt().toDouble(), Distance.UNIT_METERS)
        } else {
            Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS_P1)
        }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private fun stopNavigation() {
        runCatching {
            carContext.startService(
                Intent(carContext, NavigationService::class.java)
                    .apply { action = NavigationService.ACTION_STOP }
            )
        }
    }

    private fun lastKnownPosition(): Position? {
        if (ContextCompat.checkSelfPermission(
                carContext, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = carContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val loc: Location? = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        return loc?.let { Position(longitude = it.longitude, latitude = it.latitude) }
    }
}
