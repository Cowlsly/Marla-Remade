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
import org.maplibre.spatialk.geojson.Position

class FrameworkLocationManager(context: Context) : SensorEventListener {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Sensor storage
    private var accelerometerReading = FloatArray(3)
    private var magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Callback to pass both Position and Heading (Compass). The heading is null until a
    // real one has been seen: "unknown" and "due north" are different things, and the map
    // draws the puck's bearing cone only for the second.
    private var onUpdate: ((Position, Float?) -> Unit)? = null
    // Callback for magnetometer accuracy so the UI can prompt for calibration.
    private var onAccuracy: ((Int) -> Unit)? = null
    private var lastLocation: Location? = null
    private var currentHeading: Float? = null
    /** Listener we registered with the OS so [stop] can unregister it. */
    private var registeredLocationListener: LocationListener? = null

    @SuppressLint("MissingPermission")
    fun startUpdates(
        onUpdateReceived: (Position, Float?) -> Unit,
        onAccuracyReceived: (Int) -> Unit = {},
    ): LocationListener {
        this.onUpdate = onUpdateReceived
        this.onAccuracy = onAccuracyReceived

        // 1. Setup GPS Updates
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocation = location
                // If location has a GPS bearing, we prioritize it while moving
                val heading = if (location.hasBearing()) location.bearing else currentHeading
                onUpdate?.invoke(Position(location.longitude, location.latitude), heading)
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
                    onUpdate?.invoke(Position(it.longitude, it.latitude), currentHeading)
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