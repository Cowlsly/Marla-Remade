package com.vayunmathur.maps.util
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.vayunmathur.library.map.GeoPoint

class FrameworkLocationManager(context: Context) : SensorEventListener {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Sensor storage
    private var accelerometerReading = FloatArray(3)
    private var magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Callback to pass both GeoPoint and Heading (Compass). The heading is null until a
    // real one has been seen: "unknown" and "due north" are different things, and the map
    // draws the puck's bearing cone only for the second.
    private var onUpdate: ((GeoPoint, Float?) -> Unit)? = null
    // Callback for magnetometer accuracy so the UI can prompt for calibration.
    private var onAccuracy: ((Int) -> Unit)? = null
    private var lastLocation: Location? = null
    /** What [acceptsFix] compares against. Kept beside [lastLocation] so the two never disagree. */
    private var lastFix: Fix? = null
    private var currentHeading: Float? = null
    /** Listener we registered with the OS so [stop] can unregister it. */
    private var registeredLocationListener: LocationListener? = null

    @SuppressLint("MissingPermission")
    fun startUpdates(
        onUpdateReceived: (GeoPoint, Float?) -> Unit,
        onAccuracyReceived: (Int) -> Unit = {},
    ): LocationListener {
        this.onUpdate = onUpdateReceived
        this.onAccuracy = onAccuracyReceived

        // 1. Setup GPS Updates
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val incoming = Fix(
                    fromGps = location.provider == LocationManager.GPS_PROVIDER,
                    accuracyM = if (location.hasAccuracy()) location.accuracy else 0f,
                    elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                )
                if (!acceptsFix(lastFix, incoming)) return
                lastFix = incoming
                lastLocation = location
                // If location has a GPS bearing, we prioritize it while moving
                val heading = if (location.hasBearing()) location.bearing else currentHeading
                onUpdate?.invoke(GeoPoint(location.longitude, location.latitude), heading)
            }
            @Deprecated("Overrides deprecated LocationListener.onStatusChanged")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        registeredLocationListener = locationListener

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener)
        }

        // 2. Setup Sensor Updates (Compass)
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { acc ->
            sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { mag ->
            sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI)
        }

        return locationListener
    }

    /**
     * Tear down all OS-registered callbacks. MUST be called when the owning
     * scope (typically the ViewModel) is destroyed, or the GPS radio stays
     * powered and the accel/magnetometer listeners leak.
     */
    fun stop() {
        registeredLocationListener?.let { runCatching { locationManager.removeUpdates(it) } }
        registeredLocationListener = null
        runCatching { sensorManager.unregisterListener(this) }
        onUpdate = null
        onAccuracy = null
        // Otherwise a restart compares the first fix against one from the previous session and
        // can reject it: `elapsedRealtimeNanos` keeps counting while updates are unregistered,
        // so the old fix would look fresh enough to keep winning.
        lastFix = null
        lastLocation = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            accelerometerReading = event.values.copyOf()
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magnetometerReading = event.values.copyOf()
        }

        // Calculate Orientation
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            // Convert radians to degrees and normalize to 0-360
            val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            val newHeading = (azimuth + 360) % 360

            // Low-pass filter, on the magnetometer path only: a GPS course is already
            // smooth and filtering it would lag the puck behind a turn. This used to be a
            // bare assignment under a comment claiming to smooth, which mattered less
            // while the puck was drawn in Compose a frame behind the map — that latency
            // was doing some incidental smoothing of its own, and drawing the puck in the
            // renderer takes it away.
            currentHeading = smoothHeading(currentHeading, newHeading)

            // Update UI if we have a location but the user is standing still
            lastLocation?.let {
                if (!it.hasBearing()) {
                    onUpdate?.invoke(GeoPoint(it.longitude, it.latitude), currentHeading)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Only the magnetometer's accuracy reflects compass calibration state.
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            onAccuracy?.invoke(accuracy)
        }
    }
}

/**
 * One low-pass step from [current] toward [target], both degrees clockwise from north.
 *
 * Interpolates along the **shortest arc**, so a compass crossing north moves 359° → 1° by
 * two degrees rather than spinning the puck's cone 358° the other way. A null [current] is
 * the first reading and is taken whole: easing in from an arbitrary starting angle would
 * sweep the cone across the screen on every cold start.
 */
internal fun smoothHeading(
    current: Float?,
    target: Float,
    alpha: Float = HEADING_SMOOTHING,
): Float {
    if (current == null) return target
    // +540 before the modulo so the operand is positive: Kotlin's Float `%` keeps the
    // sign of the dividend, which would leave this in -360..360 instead of -180..180.
    val delta = (target - current + 540f) % 360f - 180f
    return (current + delta * alpha + 360f) % 360f
}

/**
 * How much of each new compass reading to take. The magnetometer is registered at
 * `SENSOR_DELAY_UI` (~60 ms), so this is roughly a third of a second to settle — enough to
 * kill the jitter without the cone visibly trailing a deliberate turn.
 */
private const val HEADING_SMOOTHING = 0.15f

/**
 * The part of a fix that decides whether it is worth taking, independent of `android.location`
 * so the policy can be tested without a device.
 */
internal data class Fix(
    val fromGps: Boolean,
    /** Horizontal accuracy in metres, or `0` when the provider did not supply one. */
    val accuracyM: Float,
    /** Monotonic, unlike wall-clock time, so it survives a clock correction mid-drive. */
    val elapsedRealtimeNanos: Long,
)

/**
 * Whether [incoming] should replace [current] as the shown position.
 *
 * GPS and network are registered on the *same* listener, so without this every network fix
 * overwrote the GPS one that arrived a moment earlier. For a navigation app that is backwards:
 * network positions are typically hundreds of metres out against GPS's few, so the puck jumped
 * between the road and somewhere near the road, roughly once a second.
 *
 * The policy is therefore GPS-first rather than newest-wins, with two ways for a network fix to
 * still get through — otherwise a phone that loses sky view keeps showing the last rooftop it saw:
 *
 *  - **GPS has gone quiet** for [staleAfterNanos]. A stale fix is worse than a coarse one.
 *  - **Network is [ACCURACY_MARGIN]× more accurate**, which happens indoors where GPS degrades to
 *    a wide multipath estimate while wifi trilateration stays tight.
 *
 * Ages are measured between the two fixes rather than against "now", so this stays a pure
 * function of its arguments and a caller cannot get a different answer by asking later.
 */
internal fun acceptsFix(
    current: Fix?,
    incoming: Fix,
    staleAfterNanos: Long = GPS_STALE_NANOS,
): Boolean {
    if (current == null) return true
    if (incoming.fromGps) return true
    // Network replacing network: nothing to prefer, so take the fresher one.
    if (!current.fromGps) return true
    if (incoming.elapsedRealtimeNanos - current.elapsedRealtimeNanos >= staleAfterNanos) return true
    // `0` means the provider declined to say, which is not evidence of being better.
    if (incoming.accuracyM <= 0f || current.accuracyM <= 0f) return false
    return incoming.accuracyM * ACCURACY_MARGIN < current.accuracyM
}

/**
 * How long GPS may be silent before a network fix is taken instead.
 *
 * Both providers are requested at one second, so this is ten missed updates — long enough not to
 * flip providers on a single dropped fix under a bridge, short enough that walking into a building
 * updates the puck rather than stranding it at the door.
 */
private const val GPS_STALE_NANOS = 10_000_000_000L

/**
 * How much better a network fix must be to displace a fresh GPS one. Twice, not marginally: at
 * comparable accuracy GPS is the more trustworthy of the two, and swapping between providers of
 * similar quality would make the puck jitter for no gain.
 */
private const val ACCURACY_MARGIN = 2f