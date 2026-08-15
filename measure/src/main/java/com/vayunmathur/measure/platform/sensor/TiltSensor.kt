package com.vayunmathur.measure.domain.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import com.vayunmathur.measure.domain.LevelMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which device edge is pointing at the ground, derived purely from gravity. */
enum class HeldOrientation(val label: String) {
    Portrait("Portrait"),
    PortraitUpsideDown("Portrait, upside down"),
    LandscapeLeft("Landscape, left edge down"),
    LandscapeRight("Landscape, right edge down"),
    Flat("Flat"),
}

data class Tilt(
    /** Front-to-back tilt for the flat 2D bubble, in degrees. */
    val pitchDeg: Double,
    /** Side-to-side tilt for the flat 2D bubble, in degrees. */
    val rollDeg: Double,
    /**
     * Deviation of the down-facing edge from level, in degrees.
     *
     * Measured against the nearest quarter turn, so it reads correctly whether the
     * phone is held upright, upside down, or on either side.
     */
    val edgeAngleDeg: Double,
    /** True when the device is lying close to flat, where the 2D bubble is the useful view. */
    val isFlat: Boolean,
    val orientation: HeldOrientation,
    /**
     * Rotation to apply to the edge bubble so it stays horizontal in the real world.
     *
     * Compensates for the gap between how the device is physically held and how the
     * activity happens to be rotated, which is why the level does not depend on
     * auto-rotate being enabled.
     */
    val uiRotationDeg: Float,
)

/**
 * Gravity-derived tilt for the bubble level.
 *
 * Uses `TYPE_GRAVITY` in preference to the raw accelerometer: the fused gravity sensor
 * has already had linear acceleration removed, so the bubble does not lurch when the
 * phone is set down. Falls back to the accelerometer where gravity is unavailable.
 *
 * `SENSOR_DELAY_UI` rather than `GAME` — a spirit level is read by eye, and the faster
 * rate only adds jitter and power draw.
 */
class TiltSensor(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Suppress("DEPRECATION")
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?

    private val _tilt = MutableStateFlow<Tilt?>(null)
    val tilt: StateFlow<Tilt?> = _tilt

    private var sx = 0.0
    private var sy = 0.0
    private var sz = 0.0
    private var first = true
    private var running = false

    /** Calibration offsets, subtracted from every reading. */
    private var pitchOffset = 0.0
    private var rollOffset = 0.0
    private var edgeOffset = 0.0

    private var lastRawPitch = 0.0
    private var lastRawRoll = 0.0
    private var lastRawEdge = 0.0

    fun start() {
        if (running) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        running = true
        first = true
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
    }

    fun setCalibration(pitch: Double, roll: Double, edge: Double) {
        pitchOffset = pitch
        rollOffset = roll
        edgeOffset = edge
    }

    /** Zero the level against whatever surface the device is resting on right now. */
    fun captureZero(): Triple<Double, Double, Double> {
        pitchOffset = lastRawPitch
        rollOffset = lastRawRoll
        edgeOffset = lastRawEdge
        return Triple(pitchOffset, rollOffset, edgeOffset)
    }

    fun clearCalibration() {
        pitchOffset = 0.0
        rollOffset = 0.0
        edgeOffset = 0.0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()

        if (first) {
            sx = x; sy = y; sz = z
            first = false
        } else {
            sx += (x - sx) * ALPHA
            sy += (y - sy) * ALPHA
            sz += (z - sz) * ALPHA
        }

        val r = LevelMath.fromGravity(sx, sy, sz)
        lastRawPitch = r.pitchDeg
        lastRawRoll = r.rollDeg
        lastRawEdge = r.edgeAngleDeg

        _tilt.value = Tilt(
            pitchDeg = r.pitchDeg - pitchOffset,
            rollDeg = r.rollDeg - rollOffset,
            edgeAngleDeg = r.edgeAngleDeg - edgeOffset,
            isFlat = r.isFlat,
            orientation = r.orientation,
            uiRotationDeg = LevelMath.uiRotation(r.quadrant, displayRotationDeg()),
        )
    }

    @Suppress("DEPRECATION")
    private fun displayRotationDeg(): Int = when (windowManager?.defaultDisplay?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private companion object {
        const val ALPHA = 0.2
    }
}
