@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.maps.util

import kotlin.concurrent.atomics.*
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.RouteService.TravelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.spatialk.geojson.Position

/**
 * Process-global state machine for an in-progress navigation session.
 *
 * Mirrors the pattern used by `findfamily/.../UwbSessionManager`: a singleton
 * `object` owns the entire lifecycle, exposes a [StateFlow] for any observer
 * (UI, foreground service notification), and is fed by [NavigationService]
 * which keeps the process alive while the user navigates.
 *
 * Why a singleton (not a ViewModel-scoped owner): the user expects driving
 * navigation to continue when the app is backgrounded or the screen turns off
 * (foreground service requirement), and to survive activity recreation
 * (rotation, system-initiated kills). A ViewModel can't do either.
 */
object NavigationSessionManager {

    private const val TAG = "NavSession"

    /** Distance (m) off the polyline before we start considering "off-route". */
    private const val OFF_ROUTE_THRESHOLD_M = 50.0

    /** How long we must remain off-route before auto-recalculating (ms). */
    private const val OFF_ROUTE_DEBOUNCE_MS = 5_000L

    /** Minimum gap between consecutive recalc attempts (ms). */
    private const val RECALC_COOLDOWN_MS = 15_000L

    /** Distance (m) below which we consider the user "arrived". */
    private const val ARRIVAL_RADIUS_M = 30.0

    /** Speed (m/s) above which we trust GPS course-over-ground for bearing. */
    private const val GPS_COURSE_MIN_SPEED_MPS = 1.0f

    /** UI / notification state for the navigation session. */
    sealed interface NavState {
        data object Idle : NavState
        data object Starting : NavState
        data class Navigating(val progress: NavigationProgress) : NavState
        data object Recalculating : NavState
        data object Arrived : NavState
        data class Failed(val reason: String) : NavState
    }

    private val _state: MutableStateFlow<NavState> = MutableStateFlow(NavState.Idle)
    val state: StateFlow<NavState> = _state.asStateFlow()

    /** Travel mode for the active session (or last session). Used by UI/notification. */
    @Volatile var travelMode: TravelMode? = null
        private set

    /** Human-readable destination shown in the notification. */
    @Volatile var destinationName: String? = null
        private set

    /** The active route (snapshot used by UI for step list / polyline split). */
    @Volatile var currentRoute: RouteService.Route? = null
        private set

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context

    private var polylineIndex: PolylineIndex? = null
    private var destination: Position? = null
    private var lastSegmentIndex: Int = 0
    private var offRouteSinceMs: Long? = null
    private var lastRecalcMs: Long = 0L
    private var recalcAttempts: Int = 0
    private var locationJob: Job? = null
    private var recalcJob: Job? = null

    // Location/compass source. We don't reuse FrameworkLocationManager because
    // navigation needs the raw Location for speed-based bearing selection,
    // which FrameworkLocationManager doesn't expose.
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var registeredLocationListener: LocationListener? = null
    private var registeredSensorListener: SensorEventListener? = null

    /** Compass-derived heading (from sensor fusion). */
    @Volatile private var compassBearing: Float = 0f

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        Log.i(TAG, "init")
    }

    /**
     * Begin navigating along [route] in [mode]. [destination] is used for
     * off-route recalculation. [destinationLabel] populates the notification.
     */
    fun start(
        route: RouteService.Route,
        mode: TravelMode,
        destination: Position,
        destinationLabel: String,
    ) {
        if (!initialized.load()) {
            Log.w(TAG, "start() called before init()")
            return
        }
        if (_state.value !is NavState.Idle && _state.value !is NavState.Failed) {
            Log.w(TAG, "start() called while already in state ${_state.value}; ignoring")
            return
        }
        Log.i(TAG, "start(mode=$mode, label=$destinationLabel, steps=${route.step.size}, dist=${route.distanceMeters})")
        currentRoute = route
        travelMode = mode
        destinationName = destinationLabel
        this.destination = destination
        polylineIndex = PolylineIndex(route)
        lastSegmentIndex = 0
        offRouteSinceMs = null
        recalcAttempts = 0
        _state.value = NavState.Starting
        startLocationCollection()
    }

    /** End the navigation session. Cancels jobs, stops location, resets state. */
    fun stop() {
        Log.i(TAG, "stop")
        locationJob?.cancel(); locationJob = null
        recalcJob?.cancel(); recalcJob = null
        stopLocationCollection()
        // Drop TTS bookkeeping so a follow-up start() doesn't suppress
        // threshold cues whose stepIndex happens to collide with the old
        // route's. NavigationService.shutdown() also fully tears down TTS
        // when the service is destroyed; this is the in-process equivalent
        // for stop-without-service-teardown paths.
        NavigationTts.reset()
        polylineIndex = null
        currentRoute = null
        travelMode = null
        destinationName = null
        destination = null
        lastSegmentIndex = 0
        offRouteSinceMs = null
        _state.value = NavState.Idle
    }

    // ----------------------------------------------------------------
    // Location / sensor wiring
    // ----------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startLocationCollection() {
        // Hard gate on ACCESS_FINE_LOCATION — without it requestLocationUpdates
        // throws SecurityException.
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = NavState.Failed("Location permission not granted")
            return
        }

        if (locationManager == null) {
            locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        }
        if (sensorManager == null) {
            sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }

        val lm = locationManager!!
        val sm = sensorManager!!

        val locListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleLocation(location)
            }
            @Deprecated("Required by old LocationListener interface; status callbacks are no longer delivered on API 29+.")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        registeredLocationListener = locListener
        runCatching {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, locListener)
            }
        }.onFailure { Log.e(TAG, "requestLocationUpdates failed", it) }

        // Compass fusion for the stationary case (start-of-route, traffic stop).
        val sensorListener = object : SensorEventListener {
            private val accel = FloatArray(3)
            private val mag = FloatArray(3)
            private val rot = FloatArray(9)
            private val angles = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> event.values.copyInto(accel)
                    Sensor.TYPE_MAGNETIC_FIELD -> event.values.copyInto(mag)
                }
                if (SensorManager.getRotationMatrix(rot, null, accel, mag)) {
                    SensorManager.getOrientation(rot, angles)
                    val az = Math.toDegrees(angles[0].toDouble()).toFloat()
                    compassBearing = (az + 360f) % 360f
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        registeredSensorListener = sensorListener
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }

        // Seed an initial fix from the last known location so the UI doesn't
        // sit on Starting until the next GPS tick.
        runCatching {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            last?.let { handleLocation(it) }
        }
    }

    private fun stopLocationCollection() {
        registeredLocationListener?.let { listener ->
            runCatching { locationManager?.removeUpdates(listener) }
        }
        registeredLocationListener = null
        registeredSensorListener?.let { listener ->
            runCatching { sensorManager?.unregisterListener(listener) }
        }
        registeredSensorListener = null
    }

    // ----------------------------------------------------------------
    // Per-fix processing
    // ----------------------------------------------------------------

    private fun handleLocation(location: Location) {
        // Once we've Arrived (or hit a terminal Failed state) we want fixes
        // to stop driving the state machine — otherwise normal GPS jitter
        // (±10 m around the destination) flicks the user back into
        // Navigating and re-enqueues the service's 8s self-stop timer.
        if (_state.value is NavState.Arrived || _state.value is NavState.Failed) return

        val index = polylineIndex ?: return
        val position = Position(longitude = location.longitude, latitude = location.latitude)
        val snap = index.snap(position, lastSegmentIndex)
        lastSegmentIndex = snap.segmentIndex

        val bearing = chooseBearing(location)
        val progress = index.deriveProgress(
            snap,
            courseOverGround = bearing,
            speedMps = if (location.hasSpeed()) location.speed else 0f,
        )

        // Arrival check first — once arrived, stop processing and let the UI
        // show its end-of-trip card before being torn down.
        if (progress.distanceRemaining <= ARRIVAL_RADIUS_M) {
            _state.value = NavState.Arrived
            // Drop the location subscription so subsequent fixes can't
            // un-arrive us. The service's auto-cleanup timer takes over.
            stopLocationCollection()
            return
        }

        // Off-route detection + debounced recalc. Skip for transit — a
        // train/bus rider is genuinely "off" the polyline most of the
        // trip (tunnels, GPS drift inside a vehicle), and a recalc would
        // replace the planned transit route with a walking route mid-trip.
        val now = System.currentTimeMillis()
        if (travelMode != TravelMode.TRANSIT && snap.distanceOffRoute > OFF_ROUTE_THRESHOLD_M) {
            val since = offRouteSinceMs ?: run {
                offRouteSinceMs = now
                now
            }
            val elapsed = now - since
            val canRecalc = (now - lastRecalcMs) >= RECALC_COOLDOWN_MS && recalcAttempts < 3
            if (elapsed >= OFF_ROUTE_DEBOUNCE_MS && canRecalc && _state.value !is NavState.Recalculating) {
                triggerRecalculate(position)
                return
            }
        } else {
            offRouteSinceMs = null
        }

        _state.value = NavState.Navigating(progress)
    }

    private fun chooseBearing(location: Location): Float = when {
        location.hasBearing() && location.hasSpeed() && location.speed >= GPS_COURSE_MIN_SPEED_MPS ->
            location.bearing
        else -> compassBearing
    }

    // ----------------------------------------------------------------
    // Off-route recalculation
    // ----------------------------------------------------------------

    private fun triggerRecalculate(from: Position) {
        val dest = destination ?: return
        val mode = travelMode ?: return
        Log.i(TAG, "off-route; recalculating from $from to $dest (attempt ${recalcAttempts + 1})")
        _state.value = NavState.Recalculating
        recalcAttempts++
        lastRecalcMs = System.currentTimeMillis()
        recalcJob?.cancel()
        recalcJob = scope.launch {
            val newRoute = runCatching {
                // Wrap the existing destination + current pos into a Route
                // request the same way the bottom sheet does.
                val fromFeature = SpecificFeature.GenericPlace(
                    name = "From",
                    phone = null,
                    website = null,
                    openingHours = null,
                    position = from,
                )
                val toFeature = SpecificFeature.GenericPlace(
                    name = destinationName ?: "Destination",
                    phone = null,
                    website = null,
                    openingHours = null,
                    position = dest,
                )
                val routeFeature = SpecificFeature.Route(listOf(fromFeature, toFeature))
                OfflineRouter.getRouteMulti(appContext, routeFeature, from, mode)
            }.getOrNull()

            if (newRoute == null) {
                Log.w(TAG, "recalc failed")
                if (recalcAttempts >= 3) {
                    _state.value = NavState.Failed("Could not recalculate route")
                } else {
                    // Don't immediately rip the user back to Failed — drop
                    // back into Navigating on the old route; the next off-route
                    // window may succeed.
                    offRouteSinceMs = null
                }
                return@launch
            }

            // If the user reached the destination while the recalc was in
            // flight, drop the recalc result on the floor — otherwise we'd
            // overwrite the (still-valid) Arrived state with a fresh,
            // longer route and the user would be "un-arrived".
            if (_state.value is NavState.Arrived) {
                Log.i(TAG, "recalc completed but user already arrived; discarding new route")
                return@launch
            }

            // Swap in the new polyline. Reset state for the new shape.
            currentRoute = newRoute
            polylineIndex = PolylineIndex(newRoute)
            lastSegmentIndex = 0
            offRouteSinceMs = null
            recalcAttempts = 0
            NavigationTts.reset()
            // The location listener is still running; the next fix will move
            // us into Navigating on the new route.
            Log.i(TAG, "recalc succeeded; new route ${newRoute.distanceMeters}m / ${newRoute.step.size} steps")
        }
    }
}
