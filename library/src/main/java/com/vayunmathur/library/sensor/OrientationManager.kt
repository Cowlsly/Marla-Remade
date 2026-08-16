package com.vayunmathur.library.sensor

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*

data class DeviceOrientation(
    val azimuthTrueDeg: Double, // true-north yaw of device top
    val azimuthMagDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
    val accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
    val declinationDeg: Double = 0.0,
    // "Window" model: the view looks where the BACK of the phone points, i.e. the
    // device -Z axis (the camera's optical axis), expressed in world ENU. Hold the
    // phone up like a pane of glass and what is drawn matches what's behind it (and
    // what the AR camera sees). Flat screen-up => back faces the ground => nadir;
    // vertical => horizon; back facing the zenith => alt +90.
    // viewRotationDeg is the true roll about that -Z axis, so world-anchored content
    // stays fixed as the phone rolls.
    val pointingAzTrueDeg: Double = azimuthTrueDeg,
    val pointingAltDeg: Double = 0.0,
    val viewRotationDeg: Double = 0.0
) {
    val pointingAzRad get() = Math.toRadians(pointingAzTrueDeg)
    val pointingAltRad get() = Math.toRadians(pointingAltDeg)
    val viewRotationRad get() = Math.toRadians(viewRotationDeg)
}

class OrientationManager(private val context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
    private val _orientation = MutableStateFlow<DeviceOrientation?>(null)
    val orientation: StateFlow<DeviceOrientation?> = _orientation
    private var declinationDeg = 0.0
    private var accel = FloatArray(3)
    private var mag = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false
    private var smoothedAz = 0.0
    private var smoothedPitch = 0.0
    private var smoothedRoll = 0.0
    private var smoothedPointingAzMag = 0.0
    private var smoothedPointingAlt = 0.0
    private var smoothedViewRot = 0.0
    private var first = true
    private val alpha = 0.15f
    private val alphaPointing = 0.15f
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private var running = false
    private var useRotationVector = true
    private var lastWorldMatrix = FloatArray(9)

    fun updateLocation(lat: Double, lon: Double, alt: Float = 0f) {
        declinationDeg = try {
            GeomagneticField(lat.toFloat(), lon.toFloat(), alt, System.currentTimeMillis()).declination.toDouble()
        } catch (_: Exception) { 0.0 }
    }

    fun start() {
        if (running) return
        running = true; first = true
        val rotVec = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotVec != null) {
            sensorManager.registerListener(this, rotVec, SensorManager.SENSOR_DELAY_GAME)
            useRotationVector = true
        } else {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            useRotationVector = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        hasAccel = false; hasMag = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event.values)
            Sensor.TYPE_ACCELEROMETER -> { accel = event.values.clone(); hasAccel = true; if (!useRotationVector) tryFallback() }
            Sensor.TYPE_MAGNETIC_FIELD -> { mag = event.values.clone(); hasMag = true; if (!useRotationVector) tryFallback() }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, acc: Int) { accuracy = acc }

    @Suppress("DEPRECATION")
    private fun handleRotationVector(values: FloatArray) {
        val RdeviceToWorld = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(RdeviceToWorld, values)
        lastWorldMatrix = RdeviceToWorld.clone()

        val displayRot = windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val remapped = FloatArray(9)
        when (displayRot) {
            Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(RdeviceToWorld, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(RdeviceToWorld, SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(RdeviceToWorld, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Z, remapped)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(RdeviceToWorld, SensorManager.AXIS_MINUS_Z, SensorManager.AXIS_X, remapped)
            else -> SensorManager.remapCoordinateSystem(RdeviceToWorld, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        }
        val o = FloatArray(3); SensorManager.getOrientation(remapped, o)
        val azMag = deviceTopHeadingDeg(RdeviceToWorld)
        val pitch = Math.toDegrees(o[1].toDouble()); val roll = Math.toDegrees(o[2].toDouble())

        val (pointAzMag, pointAlt) = pointingFromDeviceToWorld(RdeviceToWorld)
        val vrot = viewRotationFromDeviceToWorld(RdeviceToWorld)

        if (first) {
            smoothedAz = azMag; smoothedPitch = pitch; smoothedRoll = roll
            smoothedPointingAzMag = pointAzMag; smoothedPointingAlt = pointAlt
            smoothedViewRot = if (vrot.isNaN()) 0.0 else vrot
            first = false
        } else {
            var d = azMag - smoothedAz; d = ((d + 540) % 360) - 180
            smoothedAz = (smoothedAz + d * alpha + 360) % 360
            smoothedPitch += (pitch - smoothedPitch) * alpha
            smoothedRoll += (roll - smoothedRoll) * alpha
            var dp = pointAzMag - smoothedPointingAzMag; dp = ((dp + 540) % 360) - 180
            smoothedPointingAzMag = (smoothedPointingAzMag + dp * alphaPointing + 360) % 360
            smoothedPointingAlt = smoothedPointingAlt + (pointAlt - smoothedPointingAlt) * alphaPointing
            if (!vrot.isNaN()) { var dv = vrot - smoothedViewRot; dv = ((dv + 540) % 360) - 180; smoothedViewRot += dv * alpha }
        }
        val trueAz = (smoothedAz + declinationDeg + 360) % 360
        val pointTrueAz = (smoothedPointingAzMag + declinationDeg + 360) % 360
        _orientation.value = DeviceOrientation(
            azimuthTrueDeg = trueAz,
            azimuthMagDeg = smoothedAz,
            pitchDeg = smoothedPitch,
            rollDeg = smoothedRoll,
            accuracy = accuracy,
            declinationDeg = declinationDeg,
            pointingAzTrueDeg = pointTrueAz,
            pointingAltDeg = smoothedPointingAlt,
            viewRotationDeg = smoothedViewRot
        )
    }

    // Window model: the view forward is the back of the phone = device -Z axis (the
    // camera's optical axis) in world ENU. -Z is the negated third column of the
    // device->world matrix. Back facing the zenith => alt +90, horizon => 0, ground
    // => -90. Uses the whole axis (not Y) so it's stable to charging-port up/down.
    // Flat-compass heading: azimuth of the device +Y (top edge) projected onto the
    // horizontal plane, taken straight from the device->world matrix. Matches the
    // "true-north yaw of device top" contract and, like the pointing* values, is robust to
    // tilt — unlike getOrientation()[0], which gimbal-locks near vertical and was additionally
    // being read off the AR "window" remap (correct only for a phone held up, not a flat
    // compass). This is why the sky view pointed accurately while the compass drifted.
    private fun deviceTopHeadingDeg(R: FloatArray): Double {
        val east = R[1].toDouble()
        val north = R[4].toDouble()
        return (Math.toDegrees(atan2(east, north)) + 360) % 360
    }

    private fun pointingFromDeviceToWorld(R: FloatArray): Pair<Double, Double> {
        val east = -R[2].toDouble()
        val north = -R[5].toDouble()
        val up = -R[8].toDouble()
        var az = Math.toDegrees(atan2(east, north)); az = (az + 360) % 360
        val alt = Math.toDegrees(asin(up.coerceIn(-1.0, 1.0)))
        return az to alt
    }

    // Roll about the viewing axis, returned as the rotation (deg) to apply to the
    // projected sky so celestial-up lines up with the phone's physical up. Derived
    // straight from the rotation matrix (getOrientation's "roll" is about device Y,
    // which is the wrong axis once the phone is held vertical to look at the sky).
    // Returns NaN near the zenith/nadir where roll is undefined.
    private fun viewRotationFromDeviceToWorld(R: FloatArray): Double {
        // Camera forward = device -Z (out the back, toward the sky).
        val fx = -R[2].toDouble(); val fy = -R[5].toDouble(); val fz = -R[8].toDouble()
        // Device screen "up" = +Y column (top edge of the phone), already ⟂ to f.
        val ux = R[1].toDouble(); val uy = R[4].toDouble(); val uz = R[7].toDouble()
        // Celestial-up reference: world zenith (0,0,1) projected into the screen plane.
        val zf = fz // world zenith · f
        var px = -zf * fx; var py = -zf * fy; var pz = 1.0 - zf * fz
        val pl = sqrt(px * px + py * py + pz * pz)
        if (pl < 1e-6) return Double.NaN // looking near straight up/down: roll undefined
        px /= pl; py /= pl; pz /= pl
        val dot = px * ux + py * uy + pz * uz
        // (P_up × u) · f => signed angle of device-up from celestial-up about f.
        val cx = py * uz - pz * uy; val cy = pz * ux - px * uz; val cz = px * uy - py * ux
        val crossDotF = cx * fx + cy * fy + cz * fz
        val rollRad = atan2(crossDotF, dot)
        // Rotate the drawn sky by -roll so it stays fixed to the world as the phone rolls.
        return Math.toDegrees(-rollRad)
    }

    @Suppress("DEPRECATION")
    private fun tryFallback() {
        if (!hasAccel || !hasMag) return
        val R = FloatArray(9); val I = FloatArray(9)
        if (!SensorManager.getRotationMatrix(R, I, accel, mag)) return
        lastWorldMatrix = R.clone()
        val displayRot = windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val remapped = FloatArray(9)
        when (displayRot) {
            Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Z, remapped)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_MINUS_Z, SensorManager.AXIS_X, remapped)
            else -> SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        }
        val o = FloatArray(3); SensorManager.getOrientation(remapped, o)
        val azMag = deviceTopHeadingDeg(R)
        val pitch = Math.toDegrees(o[1].toDouble()); val roll = Math.toDegrees(o[2].toDouble())
        val (pAz, pAlt) = pointingFromDeviceToWorld(R)
        val vrot = viewRotationFromDeviceToWorld(R)
        if (first) {
            smoothedAz = azMag; smoothedPitch = pitch; smoothedRoll = roll
            smoothedPointingAzMag = pAz; smoothedPointingAlt = pAlt
            smoothedViewRot = if (vrot.isNaN()) 0.0 else vrot
            first = false
        } else {
            var d = azMag - smoothedAz; d = ((d + 540) % 360) - 180
            smoothedAz = (smoothedAz + d * alpha + 360) % 360
            smoothedPitch += (pitch - smoothedPitch) * alpha
            smoothedRoll += (roll - smoothedRoll) * alpha
            var dp = pAz - smoothedPointingAzMag; dp = ((dp + 540) % 360) - 180
            smoothedPointingAzMag = (smoothedPointingAzMag + dp * alphaPointing + 360) % 360
            smoothedPointingAlt = smoothedPointingAlt + (pAlt - smoothedPointingAlt) * alphaPointing
            if (!vrot.isNaN()) { var dv = vrot - smoothedViewRot; dv = ((dv + 540) % 360) - 180; smoothedViewRot += dv * alpha }
        }
        val trueAz = (smoothedAz + declinationDeg + 360) % 360
        val pTrueAz = (smoothedPointingAzMag + declinationDeg + 360) % 360
        _orientation.value = DeviceOrientation(
            azimuthTrueDeg = trueAz,
            azimuthMagDeg = smoothedAz,
            pitchDeg = smoothedPitch,
            rollDeg = smoothedRoll,
            accuracy = accuracy,
            declinationDeg = declinationDeg,
            pointingAzTrueDeg = pTrueAz,
            pointingAltDeg = smoothedPointingAlt,
            viewRotationDeg = smoothedViewRot
        )
    }
}
