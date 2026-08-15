package com.vayunmathur.measure.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.sensor.OrientationManager
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.measure.data.model.Anchor
import com.vayunmathur.measure.data.model.MeasurementKind
import com.vayunmathur.measure.data.model.SavedMeasurement
import com.vayunmathur.measure.data.model.TrackingQuality
import com.vayunmathur.measure.data.model.UnitSystem
import com.vayunmathur.measure.data.model.distanceTo
import com.vayunmathur.measure.domain.MeasureNative
import com.vayunmathur.measure.domain.sensor.ImuRecorder
import com.vayunmathur.measure.domain.sensor.TiltSensor
import com.vayunmathur.measure.domain.polygonArea
import com.vayunmathur.measure.domain.polygonPerimeter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the sensor lifecycles and the VIO session, and implements every actions
 * interface in the UI contract so pages can stay previewable.
 */
class MeasureViewModel(app: Application) : AndroidViewModel(app),
    CompassActions, LevelActions,
    ArMeasureActions, SavedActions, SettingsActions, DiagnosticsActions {

    private val ds = DataStoreUtils.getInstance(app)

    val orientationManager = OrientationManager(app)
    val tiltSensor = TiltSensor(app)
    val imuRecorder = ImuRecorder(app)

    private val _compass = MutableStateFlow(CompassUiState())
    val compass: StateFlow<CompassUiState> = _compass

    private val _level = MutableStateFlow(LevelUiState())
    val level: StateFlow<LevelUiState> = _level

    private val _ruler = MutableStateFlow(RulerUiState())
    val ruler: StateFlow<RulerUiState> = _ruler

    private val _ar = MutableStateFlow(ArMeasureUiState())
    val ar: StateFlow<ArMeasureUiState> = _ar

    private val _saved = MutableStateFlow(SavedUiState())
    val saved: StateFlow<SavedUiState> = _saved

    private val _settings = MutableStateFlow(SettingsUiState())
    val settings: StateFlow<SettingsUiState> = _settings

    private val _diagnostics = MutableStateFlow(DiagnosticsUiState())
    val diagnostics: StateFlow<DiagnosticsUiState> = _diagnostics

    private var nextAnchorId = 1L
    private var nextMeasurementId = 1L

    init {
        loadPreferences()
        observeSensors()
    }

    private fun loadPreferences() {
        val imperial = ds.getBoolean(KEY_IMPERIAL, false)
        val system = if (imperial) UnitSystem.Imperial else UnitSystem.Metric
        val fractional = ds.getBoolean(KEY_FRACTIONAL, true)
        val trueNorth = ds.getBoolean(KEY_TRUE_NORTH, true)
        val diagnostics = ds.getBoolean(KEY_DIAGNOSTICS, false)
        val haptics = ds.getBoolean(KEY_HAPTICS, true)
        val keepOn = ds.getBoolean(KEY_KEEP_SCREEN_ON, true)
        val levelPitch = ds.getDouble(KEY_LEVEL_PITCH)
        val levelRoll = ds.getDouble(KEY_LEVEL_ROLL)
        val levelEdge = ds.getDouble(KEY_LEVEL_EDGE)

        if (levelPitch != null && levelRoll != null) {
            tiltSensor.setCalibration(levelPitch, levelRoll, levelEdge ?: 0.0)
        }

        _settings.value = SettingsUiState(
            unitSystem = system,
            useFractionalInches = fractional,
            useTrueNorth = trueNorth,
            levelCalibrated = levelPitch != null,
            showDiagnostics = diagnostics,
            hapticsEnabled = haptics,
            keepScreenOn = keepOn,
        )
        _compass.value = _compass.value.copy(useTrueNorth = trueNorth)
        _ruler.value = _ruler.value.copy(unitSystem = system)
        _level.value = _level.value.copy(
            unitSystem = system,
            isCalibrated = levelPitch != null,
        )
        _ar.value = _ar.value.copy(unitSystem = system)
        _saved.value = _saved.value.copy(unitSystem = system)
    }

    private fun observeSensors() {
        viewModelScope.launch {
            orientationManager.orientation.collect { o ->
                if (o == null) return@collect
                _compass.value = _compass.value.copy(
                    azimuthTrueDeg = o.azimuthTrueDeg,
                    azimuthMagDeg = o.azimuthMagDeg,
                    declinationDeg = o.declinationDeg,
                    accuracy = o.accuracy,
                    hasLocation = o.declinationDeg != 0.0,
                )
            }
        }
        viewModelScope.launch {
            tiltSensor.tilt.collect { t ->
                if (t == null) return@collect
                _level.value = _level.value.copy(
                    pitchDeg = t.pitchDeg,
                    rollDeg = t.rollDeg,
                    edgeAngleDeg = t.edgeAngleDeg,
                    isFlat = t.isFlat,
                    orientation = t.orientation,
                    uiRotationDeg = t.uiRotationDeg,
                )
                // Gravity-derived tilt, not the orientation pitch: OrientationManager's
                // remap targets the AR "window" model, where a phone lying flat reads
                // near -90 rather than 0, which would keep this warning permanently on.
                _compass.value = _compass.value.copy(
                    tiltWarning = kotlin.math.abs(t.pitchDeg) > COMPASS_TILT_LIMIT_DEG ||
                        kotlin.math.abs(t.rollDeg) > COMPASS_TILT_LIMIT_DEG,
                )
            }
        }
    }

    fun startSensors() {
        orientationManager.start()
        tiltSensor.start()
    }

    fun stopSensors() {
        orientationManager.stop()
        tiltSensor.stop()
    }

    /** Set the observer location so magnetic declination can be resolved. */
    fun updateLocation(lat: Double, lon: Double) {
        orientationManager.updateLocation(lat, lon)
    }

    // ---- CompassActions ----

    override fun setUseTrueNorth(v: Boolean) {
        _compass.value = _compass.value.copy(useTrueNorth = v)
        _settings.value = _settings.value.copy(useTrueNorth = v)
        persistBoolean(KEY_TRUE_NORTH, v)
    }

    override fun holdBearing() {
        val s = _compass.value
        val bearing = if (s.useTrueNorth) s.azimuthTrueDeg else s.azimuthMagDeg
        _compass.value = s.copy(heldBearingDeg = bearing)
    }

    override fun clearHeldBearing() {
        _compass.value = _compass.value.copy(heldBearingDeg = null)
    }

    // ---- LevelActions ----

    override fun calibrateZero() {
        val (pitch, roll, edge) = tiltSensor.captureZero()
        _level.value = _level.value.copy(isCalibrated = true)
        _settings.value = _settings.value.copy(levelCalibrated = true)
        viewModelScope.launch {
            ds.setDouble(KEY_LEVEL_PITCH, pitch)
            ds.setDouble(KEY_LEVEL_ROLL, roll)
            ds.setDouble(KEY_LEVEL_EDGE, edge)
        }
    }

    override fun clearCalibration() {
        tiltSensor.clearCalibration()
        _level.value = _level.value.copy(isCalibrated = false)
        _settings.value = _settings.value.copy(levelCalibrated = false)
        viewModelScope.launch {
            ds.setDouble(KEY_LEVEL_PITCH, Double.NaN)
            ds.setDouble(KEY_LEVEL_ROLL, Double.NaN)
            ds.setDouble(KEY_LEVEL_EDGE, Double.NaN)
        }
    }

    /** Ruler scale, taken from the display's reported physical pixel density. */
    fun setPixelsPerMm(v: Float) {
        if (v > 0f) _ruler.value = _ruler.value.copy(pixelsPerMm = v)
    }

    // ---- ArMeasureActions ----

    fun onTrackingUpdate(quality: TrackingQuality, hasPlane: Boolean) {
        _ar.value = _ar.value.copy(quality = quality, hasPlane = hasPlane)
    }

    fun setCameraPermission(granted: Boolean) {
        _ar.value = _ar.value.copy(cameraPermissionGranted = granted)
    }

    /** Called by the AR page with a world point resolved by the native engine. */
    fun addResolvedAnchor(x: Double, y: Double, z: Double, onPlane: Boolean) {
        val anchor = Anchor(nextAnchorId++, x, y, z, onPlane)
        val anchors = _ar.value.anchors + anchor
        _ar.value = _ar.value.copy(anchors = anchors).recomputed()
    }

    override fun undoAnchor() {
        val anchors = _ar.value.anchors.dropLast(1)
        _ar.value = _ar.value.copy(anchors = anchors, polygonClosed = false).recomputed()
    }

    override fun clearAnchors() {
        _ar.value = _ar.value.copy(
            anchors = emptyList(),
            distanceM = null,
            areaM2 = null,
            perimeterM = null,
            polygonClosed = false,
        )
    }

    override fun closePolygon() {
        if (_ar.value.anchors.size < 3) return
        _ar.value = _ar.value.copy(polygonClosed = true).recomputed()
    }

    override fun saveCurrentMeasurement(label: String) {
        val s = _ar.value
        val (kind, value) = when {
            s.polygonClosed && s.areaM2 != null -> MeasurementKind.Area to s.areaM2
            s.distanceM != null -> MeasurementKind.Distance to s.distanceM
            else -> return
        }
        val m = SavedMeasurement(
            id = nextMeasurementId++,
            label = label.ifBlank { "Measurement" },
            kind = kind,
            value = value,
            recordedAtEpochMs = System.currentTimeMillis(),
        )
        _saved.value = _saved.value.copy(measurements = _saved.value.measurements + m)
    }

    override fun resetTracking() {
        clearAnchors()
        _ar.value = _ar.value.copy(quality = TrackingQuality.Initialising, hasPlane = false)
    }

    /**
     * Recompute derived measurements from the anchor list.
     *
     * Distance is between the last two anchors; area and perimeter only once the
     * polygon is explicitly closed, since an open chain has no meaningful area.
     */
    private fun ArMeasureUiState.recomputed(): ArMeasureUiState {
        val distance = if (anchors.size >= 2) {
            anchors[anchors.size - 2].distanceTo(anchors[anchors.size - 1])
        } else {
            null
        }
        val area = if (polygonClosed && anchors.size >= 3) polygonArea(anchors) else null
        val perimeter = if (polygonClosed && anchors.size >= 3) {
            polygonPerimeter(anchors, closed = true)
        } else {
            null
        }
        return copy(distanceM = distance, areaM2 = area, perimeterM = perimeter)
    }

    // ---- SavedActions ----

    override fun delete(id: Long) {
        _saved.value = _saved.value.copy(
            measurements = _saved.value.measurements.filterNot { it.id == id }
        )
    }

    override fun rename(id: Long, label: String) {
        _saved.value = _saved.value.copy(
            measurements = _saved.value.measurements.map {
                if (it.id == id) it.copy(label = label) else it
            }
        )
    }

    // ---- SettingsActions ----

    override fun setUnitSystem(v: UnitSystem) {
        _settings.value = _settings.value.copy(unitSystem = v)
        _ruler.value = _ruler.value.copy(unitSystem = v)
        _level.value = _level.value.copy(unitSystem = v)
        _ar.value = _ar.value.copy(unitSystem = v)
        _saved.value = _saved.value.copy(unitSystem = v)
        persistBoolean(KEY_IMPERIAL, v == UnitSystem.Imperial)
    }

    override fun setUseFractionalInches(v: Boolean) {
        _settings.value = _settings.value.copy(useFractionalInches = v)
        persistBoolean(KEY_FRACTIONAL, v)
    }

    override fun setShowDiagnostics(v: Boolean) {
        _settings.value = _settings.value.copy(showDiagnostics = v)
        persistBoolean(KEY_DIAGNOSTICS, v)
    }

    override fun setHapticsEnabled(v: Boolean) {
        _settings.value = _settings.value.copy(hapticsEnabled = v)
        persistBoolean(KEY_HAPTICS, v)
    }

    override fun setKeepScreenOn(v: Boolean) {
        _settings.value = _settings.value.copy(keepScreenOn = v)
        persistBoolean(KEY_KEEP_SCREEN_ON, v)
    }

    override fun clearLevelCalibration() = clearCalibration()

    // ---- DiagnosticsActions ----

    fun updateDiagnostics(update: DiagnosticsUiState.() -> DiagnosticsUiState) {
        _diagnostics.value = _diagnostics.value.update()
    }

    private fun persistBoolean(key: String, value: Boolean) {
        viewModelScope.launch { ds.setBoolean(key, value) }
    }

    override fun onCleared() {
        stopSensors()
        imuRecorder.stop()
    }

    companion object {
        private const val COMPASS_TILT_LIMIT_DEG = 35.0

        private const val KEY_IMPERIAL = "measure_imperial"
        private const val KEY_FRACTIONAL = "measure_fractional_inches"
        private const val KEY_TRUE_NORTH = "measure_true_north"
        private const val KEY_DIAGNOSTICS = "measure_show_diagnostics"
        private const val KEY_HAPTICS = "measure_haptics"
        private const val KEY_KEEP_SCREEN_ON = "measure_keep_screen_on"
        private const val KEY_LEVEL_PITCH = "measure_level_pitch_offset"
        private const val KEY_LEVEL_ROLL = "measure_level_roll_offset"
        private const val KEY_LEVEL_EDGE = "measure_level_edge_offset"
    }
}

/** Quality code from the native engine mapped onto the UI enum. */
fun trackingQualityFrom(code: Int): TrackingQuality = when (code) {
    MeasureNative.QUALITY_LIMITED -> TrackingQuality.Limited
    MeasureNative.QUALITY_GOOD -> TrackingQuality.Good
    MeasureNative.QUALITY_LOST -> TrackingQuality.Lost
    else -> TrackingQuality.Initialising
}
