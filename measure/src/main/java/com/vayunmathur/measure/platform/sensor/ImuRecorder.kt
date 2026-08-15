package com.vayunmathur.measure.domain.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Collects gyroscope and accelerometer samples for the VIO engine.
 *
 * Prefers the **uncalibrated** variants: the engine estimates and removes bias itself,
 * and the calibrated sensors apply their own time-varying correction whose changes look
 * like real motion to a dead-reckoning integrator.
 *
 * `SENSOR_DELAY_FASTEST` requests the hardware maximum (400 Hz on the ICM45631 in recent
 * Pixels). Samples are drained in batches rather than pushed individually because
 * per-sample JNI overhead at that rate would exceed the integration work itself.
 */
class ImuRecorder(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val lock = Any()

    /** Interleaved `[tNs, gx, gy, gz, ax, ay, az]`, matching the JNI packing. */
    private val pending = ArrayList<Double>(VALUES_PER_SAMPLE * 512)

    private var lastGyro = FloatArray(3)
    private var haveGyro = false
    private var running = false

    private var sampleCount = 0L
    private var firstSampleNs = 0L
    private var lastSampleNs = 0L

    fun start() {
        if (running) return
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (gyro == null || accel == null) return

        sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST)
        running = true
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
        synchronized(lock) { pending.clear() }
        haveGyro = false
    }

    /** Effective sample rate since the first sample, for the diagnostics screen. */
    fun rateHz(): Double {
        val span = (lastSampleNs - firstSampleNs) * 1e-9
        return if (span > 0.1) sampleCount / span else 0.0
    }

    fun lastTimestampNs(): Long = lastSampleNs

    /** Take everything queued so far, leaving the buffer empty. */
    fun drain(): DoubleArray = synchronized(lock) {
        if (pending.isEmpty()) return DoubleArray(0)
        val out = DoubleArray(pending.size)
        for (i in pending.indices) out[i] = pending[i]
        pending.clear()
        out
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_GYROSCOPE -> {
                lastGyro[0] = event.values[0]
                lastGyro[1] = event.values[1]
                lastGyro[2] = event.values[2]
                haveGyro = true
            }

            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_ACCELEROMETER -> {
                // The accelerometer drives sample emission, pairing each reading with
                // the most recent gyro value. The two sensors are not synchronised, and
                // interpolating between gyro samples at 400 Hz would buy accuracy far
                // below the noise floor.
                if (!haveGyro) return
                synchronized(lock) {
                    if (pending.size >= MAX_PENDING_VALUES) {
                        // Camera stalled or screen off: drop the oldest half rather
                        // than grow without bound.
                        pending.subList(0, pending.size / 2).clear()
                    }
                    pending.add(event.timestamp.toDouble())
                    pending.add(lastGyro[0].toDouble())
                    pending.add(lastGyro[1].toDouble())
                    pending.add(lastGyro[2].toDouble())
                    pending.add(event.values[0].toDouble())
                    pending.add(event.values[1].toDouble())
                    pending.add(event.values[2].toDouble())
                }
                if (sampleCount == 0L) firstSampleNs = event.timestamp
                lastSampleNs = event.timestamp
                sampleCount++
            }
        }
    }

    private companion object {
        const val VALUES_PER_SAMPLE = 7
        /** ~20 s of headroom at 400 Hz. */
        const val MAX_PENDING_VALUES = VALUES_PER_SAMPLE * 8000
    }
}
