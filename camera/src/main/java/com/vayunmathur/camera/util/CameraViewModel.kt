package com.vayunmathur.camera.util

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Rational
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.annotation.StringRes
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import com.vayunmathur.camera.R
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.SessionConfig
import androidx.camera.core.UseCase
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionSessionConfig
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.video.AudioSpec
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import androidx.camera.lifecycle.awaitInstance
import java.io.ByteArrayInputStream
import kotlin.math.atan2
import kotlin.math.roundToInt

enum class CameraMode { PHOTO, PORTRAIT, PANORAMA, PHOTOSPHERE, VIDEO, SLOW_MO, TIMELAPSE, CINEMATIC }
enum class FlashMode { ON, OFF, AUTO }
enum class TimerDuration(val seconds: Int) { NONE(0), THREE(3), FIVE(5), TEN(10) }
enum class AspectRatioOption(val label: String) { RATIO_16_9("16:9"), RATIO_4_3("4:3"), RATIO_1_1("1:1") }
enum class VideoCodec(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    AVC(R.string.codec_avc_label, R.string.codec_avc_description),
    HEVC(R.string.codec_hevc_label, R.string.codec_hevc_description),
    AV1(R.string.codec_av1_label, R.string.codec_av1_description),
}
enum class AudioInputSource(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val specValue: Int,
) {
    CAMCORDER(R.string.audio_source_camcorder_label, R.string.audio_source_camcorder_description, AudioSpec.SOURCE_CAMCORDER),
    MIC(R.string.audio_source_mic_label, R.string.audio_source_mic_description, AudioSpec.SOURCE_MIC),
    VOICE_COMMUNICATION(R.string.audio_source_voice_communication_label, R.string.audio_source_voice_communication_description, AudioSpec.SOURCE_VOICE_COMMUNICATION),
    UNPROCESSED(R.string.audio_source_unprocessed_label, R.string.audio_source_unprocessed_description, AudioSpec.SOURCE_UNPROCESSED),
}

/** Formats a zoom ratio for the zoom bar: ".5", "1x", "2x", or "1.5x". */
fun formatZoomLabel(ratio: Float): String = when {
    ratio < 1f -> ".${(ratio * 10).roundToInt()}"
    else -> {
        val rounded = (ratio * 10f).roundToInt() / 10f
        if (kotlin.math.abs(rounded - rounded.roundToInt()) < 0.05f) "${rounded.roundToInt()}x"
        else "%.1fx".format(rounded)
    }
}

data class ExposureTimeStop(val label: String, val nanos: Long?)

/**
 * Builds the warmth/shadows color matrix shared by the live preview and the
 * saved capture, so a photo looks the same as what the viewfinder showed.
 */
fun buildColorAdjustmentMatrix(warmth: Float, shadows: Float): ColorMatrix = ColorMatrix(
    floatArrayOf(
        1f + warmth * 0.15f, 0f, 0f, 0f, shadows * 40f,
        0f, 1f + warmth * 0.05f, 0f, 0f, shadows * 40f,
        0f, 0f, 1f - warmth * 0.15f, 0f, shadows * 40f,
        0f, 0f, 0f, 1f, 0f,
    )
)

class CameraViewModel(private val app: Application) : AndroidViewModel(app) {
    companion object {

        // Once the vendor NIGHT extension fails to bind on this device, don't try again for this
        // long (persisted). Re-probes after it expires in case a system update fixes the extender.
        private const val NIGHT_EXT_FAILURE_TTL_MS = 7L * 24 * 60 * 60 * 1000

        // Night-mode auto-detection tuning. Engage once average Y stays below ENGAGE for a few
        // frames; disengage once it climbs above DISENGAGE for a few frames. The gap between the
        // two thresholds is the hysteresis band that stops the moon button from flickering.
        private const val NIGHT_ENGAGE_LUMA = 40f
        private const val NIGHT_DISENGAGE_LUMA = 55f
        private const val NIGHT_DEBOUNCE_FRAMES = 4

        // Target night exposure/ISO used when night mode fires on an Auto exposure stop. The
        // single-frame emulation (fallback) uses the long ~1/4s target; the multi-frame burst uses
        // a shorter per-frame exposure so each frame has less motion blur and the merge recovers SNR.
        private const val NIGHT_TARGET_EXPOSURE_NANOS = 250_000_000L // ~1/4s
        private const val NIGHT_BURST_PER_FRAME_NANOS = 100_000_000L // ~1/10s, in the 1/15–1/8s range
        private const val NIGHT_ISO_FRACTION = 0.75f

        /** Safety cap on frames captured during a single press-and-hold burst. */
        private const val BURST_MAX = 30

        // Motion-Photo ring buffer: keep ~1.5s of analysis frames, capped by count to bound memory
        // (analysis frames can be high-res, so this count is deliberately conservative).
        private const val MOTION_WINDOW_NANOS = 1_500_000_000L
        private const val MOTION_MAX_FRAMES = 12

        val EXPOSURE_TIME_STOPS = listOf(
            ExposureTimeStop("Auto", null),
            ExposureTimeStop("1/4000s", 250_000L),
            ExposureTimeStop("1/2000s", 500_000L),
            ExposureTimeStop("1/1000s", 1_000_000L),
            ExposureTimeStop("1/500s", 2_000_000L),
            ExposureTimeStop("1/250s", 4_000_000L),
            ExposureTimeStop("1/125s", 8_000_000L),
            ExposureTimeStop("1/60s", 16_666_667L),
            ExposureTimeStop("1/30s", 33_333_333L),
            ExposureTimeStop("1/15s", 66_666_667L),
            ExposureTimeStop("1/8s", 125_000_000L),
            ExposureTimeStop("1/4s", 250_000_000L),
            ExposureTimeStop("1/2s", 500_000_000L),
            ExposureTimeStop("1s", 1_000_000_000L),
            ExposureTimeStop("2s", 2_000_000_000L),
            ExposureTimeStop("4s", 4_000_000_000L),
        )

        /**
         * EXIF tags copied from the original frame onto an adjusted capture. Orientation and GPS
         * are set separately (see writeCaptureExif); dimension tags are omitted so they aren't
         * left inconsistent with the re-encoded JPEG.
         */
        private val EXIF_TAGS_TO_COPY = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        )
    }

    private val ds = DataStoreUtils.getInstance(app)

    private val _cameraMode = MutableStateFlow(CameraMode.PHOTO)
    val cameraMode = _cameraMode.asStateFlow()

    private val _lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val lensFacing = _lensFacing.asStateFlow()

    private val _flashMode = MutableStateFlow(FlashMode.OFF)
    val flashMode = _flashMode.asStateFlow()

    private val _torchEnabled = MutableStateFlow(false)
    val torchEnabled = _torchEnabled.asStateFlow()

    private val _timerDuration = MutableStateFlow(TimerDuration.NONE)
    val timerDuration = _timerDuration.asStateFlow()

    private val _aspectRatio = MutableStateFlow(AspectRatioOption.RATIO_4_3)
    val aspectRatio = _aspectRatio.asStateFlow()

    private val _videoCodec = MutableStateFlow(
        when {
            CodecSupport.isHardwareAv1EncoderAvailable -> VideoCodec.AV1
            CodecSupport.isHevcEncoderAvailable -> VideoCodec.HEVC
            else -> VideoCodec.AVC
        }
    )
    val videoCodec = _videoCodec.asStateFlow()

    private val _audioInputSource = MutableStateFlow(AudioInputSource.CAMCORDER)
    val audioInputSource = _audioInputSource.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0L)
    val recordingDurationSec = _recordingDurationSec.asStateFlow()
    private var recordingTimerJob: kotlinx.coroutines.Job? = null

    private val _timerCountdown = MutableStateFlow(0)
    val timerCountdown = _timerCountdown.asStateFlow()
    private var timerCountdownJob: kotlinx.coroutines.Job? = null

    private val _qrResult = MutableStateFlow<String?>(null)
    val qrResult = _qrResult.asStateFlow()

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio = _zoomRatio.asStateFlow()

    private val _availableZoomLevels = MutableStateFlow(listOf("1x" to 1f))
    val availableZoomLevels = _availableZoomLevels.asStateFlow()

    // Whether the front-camera preview and saved selfie are horizontally mirrored (issue #632).
    // Default true preserves the long-standing mirror-like selfie behavior.
    private val _mirrorFront = MutableStateFlow(true)
    val mirrorFront = _mirrorFront.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing = _isCapturing.asStateFlow()

    private val _locationEnabled = MutableStateFlow(false)
    val locationEnabled = _locationEnabled.asStateFlow()

    private val _lastCaptureUri = MutableStateFlow<Uri?>(null)
    val lastCaptureUri = _lastCaptureUri.asStateFlow()

    private val _galleryThumbnail = MutableStateFlow<Bitmap?>(null)
    val galleryThumbnail = _galleryThumbnail.asStateFlow()

    private val _gridEnabled = MutableStateFlow(false)
    val gridEnabled = _gridEnabled.asStateFlow()

    // Horizon level indicator: a device-roll angle (degrees) read off the accelerometer/gravity
    // sensor, exposed only while the level overlay is enabled. Registered lazily so the sensor
    // isn't running when the overlay is off.
    private val _levelEnabled = MutableStateFlow(false)
    val levelEnabled = _levelEnabled.asStateFlow()

    private val _roll = MutableStateFlow(0f)
    val roll = _roll.asStateFlow()

    private val sensorManager by lazy { app.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    // Dedicated single thread for portrait segmentation so main thread stays free for preview rendering.
    private val bokehExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Bakes the same bokeh into the saved still. Separate from the preview's BokehAnalyzer, which is
    // owned by the composable and torn down with it; this one loads its model on the first capture.
    private val stillBokeh = StillBokehRenderer(app)
    private val levelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            // 0° when the device is held upright in portrait; positive as the top edge tilts right.
            _roll.value = Math.toDegrees(atan2(x.toDouble(), -y.toDouble())).toFloat()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private var levelSensorRegistered = false

    // Portrait blur strength (0..1 UI → ~0.4..1.8 shader blurScale). Scales the bokeh shader's
    // tap offsets so the user can dial the background blur up/down. Default 0.5 ≈ 1.0 blurScale.
    private val _blurStrength = MutableStateFlow(0.5f)
    val blurStrength = _blurStrength.asStateFlow()

    private val _exposureCompensation = MutableStateFlow(0f)
    val exposureCompensation = _exposureCompensation.asStateFlow()

    private val _warmth = MutableStateFlow(0f)
    val warmth = _warmth.asStateFlow()

    private val _shadows = MutableStateFlow(0f)
    val shadows = _shadows.asStateFlow()

    private val _exposureTimeIndex = MutableStateFlow(0)
    val exposureTimeIndex = _exposureTimeIndex.asStateFlow()

    // --- Manual pro controls (ISO). Index 0 == Auto. ---

    // ISO: index 0 == Auto; otherwise an index into [_isoStops] (+1). Stops are derived from the
    // sensor's SENSOR_INFO_SENSITIVITY_RANGE when the session binds.
    private val _manualIsoIndex = MutableStateFlow(0)
    val manualIsoIndex = _manualIsoIndex.asStateFlow()

    private val _isoStops = MutableStateFlow<List<Int>>(emptyList())
    val isoStops = _isoStops.asStateFlow()

    // Last auto-converged AE ISO / exposure, snapshotted off the preview's capture results so a
    // half-manual exposure (only ISO or only shutter set) can seed the other from the auto value.
    @Volatile private var lastAeIso: Int? = null
    @Volatile private var lastAeExposureNanos: Long? = null

    private val aeSnapshotCallback = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: android.hardware.camera2.CameraCaptureSession,
            request: android.hardware.camera2.CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult
        ) {
            result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)?.let { lastAeIso = it }
            result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastAeExposureNanos = it }
        }
    }

    // Night mode is fully automatic: brightness detection engages it, and the moon button lets
    // the user override it off for the current dark scene. nightModeActive drives the capture path.
    private val _lowLightDetected = MutableStateFlow(false)
    val lowLightDetected = _lowLightDetected.asStateFlow()

    private val _nightModeOverriddenOff = MutableStateFlow(false)
    val nightModeOverriddenOff = _nightModeOverriddenOff.asStateFlow()

    val nightModeActive = combine(_lowLightDetected, _nightModeOverriddenOff) { low, off -> low && !off }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Whether to offer the vendor NIGHT extension. We optimistically probe with
    // isSessionConfigSupported(), but the authoritative signal is the actual bind: if
    // setupNightPreviewSession() ever fails on this device, we remember that (persisted, with a
    // ~1-week TTL) and stop offering night for a week — so a broken extender (GrapheneOS/Pixel)
    // makes at most one failed attempt per week instead of engaging/failing in a loop.
    private val _nightExtensionUsable = MutableStateFlow(false)
    val nightExtensionUsable = _nightExtensionUsable.asStateFlow()

    private fun nightExtFailedRecently(): Boolean {
        val at = ds.getString("night_ext_failed_at")?.toLongOrNull() ?: return false
        return System.currentTimeMillis() - at < NIGHT_EXT_FAILURE_TTL_MS
    }

    private fun recordNightExtensionFailure() {
        viewModelScope.launch { ds.setString("night_ext_failed_at", System.currentTimeMillis().toString()) }
        _nightExtensionUsable.value = false
    }

    /**
     * Re-evaluates whether night should be offered on the current lens/mode. Cheap checks first
     * (mode + the daily failure cache), then the isSessionConfigSupported() probe. Heavy — call off
     * the main thread.
     */
    suspend fun refreshNightExtensionUsable(cameraMode: CameraMode) {
        _nightExtensionUsable.value = when {
            cameraMode != CameraMode.PHOTO -> false
            nightExtFailedRecently() -> false
            else -> isNightExtensionAvailable()
        }
    }

    // Preferred night detection: CameraX's getNightModeIndicator() (1.7.0-alpha02) — the OS/vendor
    // reports when the scene is dark enough that night mode is RECOMMENDED. We observe the bound
    // camera's indicator LiveData and drive _lowLightDetected from it. Where the device doesn't
    // support the indicator, onLuminance()'s luminance heuristic is used as a fallback instead.
    private var nightIndicatorLiveData: androidx.lifecycle.LiveData<Int>? = null
    var nightIndicatorSupported = false
        private set
    private val nightIndicatorObserver = androidx.lifecycle.Observer<Int> { state ->
        // Don't override a manual "off" for the current dark scene; the moon button owns that.
        if (_nightModeOverriddenOff.value) return@Observer
        // RECOMMENDED engages; anything else (NOT_RECOMMENDED / UNKNOWN) disengages, so night turns
        // off again when the scene brightens. (The toggle loop this used to cause on unusable devices
        // is now prevented by the daily failure cache in nightExtensionUsable instead.)
        _lowLightDetected.value = state == androidx.camera.core.NightModeIndicator.RECOMMENDED
    }

    /** Observe getNightModeIndicator() on the freshly-bound camera (call on the main thread). */
    private fun observeNightModeIndicator(cameraInfo: androidx.camera.core.CameraInfo) {
        stopObservingNightModeIndicator()
        nightIndicatorSupported = try { cameraInfo.isNightModeIndicatorSupported } catch (_: Exception) { false }
        if (!nightIndicatorSupported) return
        val ld = try { cameraInfo.getNightModeIndicator() } catch (_: Exception) { null } ?: return
        nightIndicatorLiveData = ld
        ld.observeForever(nightIndicatorObserver)
    }

    private fun stopObservingNightModeIndicator() {
        nightIndicatorLiveData?.removeObserver(nightIndicatorObserver)
        nightIndicatorLiveData = null
    }

    // Live NIGHT-extension processing strength (0..100) from CameraExtensionsInfo.getExtensionStrength(),
    // for a "night processing" indicator in the UI. Null when unavailable / not in an extension session.
    private val _extensionStrength = MutableStateFlow<Int?>(null)
    val extensionStrength = _extensionStrength.asStateFlow()
    private var extensionStrengthLiveData: androidx.lifecycle.LiveData<Int>? = null
    private val extensionStrengthObserver = androidx.lifecycle.Observer<Int> { s -> _extensionStrength.value = s }

    /** Observe the bound extension camera's processing strength (call on the main thread). */
    private fun observeExtensionStrength(cameraInfo: androidx.camera.core.CameraInfo) {
        stopObservingExtensionStrength()
        val mgr = extensionsManager ?: return
        val info = try { mgr.getCameraExtensionsInfo(cameraInfo) } catch (_: Exception) { return }
        if (!info.isExtensionStrengthAvailable) return
        val ld = try { info.getExtensionStrength() } catch (_: Exception) { null } ?: return
        extensionStrengthLiveData = ld
        ld.observeForever(extensionStrengthObserver)
    }

    private fun stopObservingExtensionStrength() {
        extensionStrengthLiveData?.removeObserver(extensionStrengthObserver)
        extensionStrengthLiveData = null
        _extensionStrength.value = null
    }

    // The vendor NIGHT extension can bind "successfully" (bindToLifecycle returns) yet fail
    // asynchronously when camera-pipe configures the ExtensionCaptureSession (GrapheneOS/Pixel:
    // ERROR_STREAM_CONFIG). That never throws from setupNightPreviewSession, so we watch the bound
    // extension camera's CameraState and treat any error as a real failure → cache it for a week.
    private var extensionCameraStateLiveData: androidx.lifecycle.LiveData<androidx.camera.core.CameraState>? = null
    private val extensionCameraStateObserver = androidx.lifecycle.Observer<androidx.camera.core.CameraState> { st ->
        val err = st.error ?: return@Observer
        Log.w("NightPreview", "night extension camera error type=${st.type} code=${err.code} – disabling night extension (cached)")
        recordNightExtensionFailure()
    }
    private fun observeExtensionCameraState(cameraInfo: androidx.camera.core.CameraInfo) {
        stopObservingExtensionCameraState()
        val ld = cameraInfo.cameraState
        extensionCameraStateLiveData = ld
        ld.observeForever(extensionCameraStateObserver)
    }
    private fun stopObservingExtensionCameraState() {
        extensionCameraStateLiveData?.removeObserver(extensionCameraStateObserver)
        extensionCameraStateLiveData = null
    }

    // Consecutive-frame counters backing the hysteresis + debounce in onLuminance().
    private var lowLumaFrames = 0
    private var highLumaFrames = 0

    private val _longExposureProgress = MutableStateFlow(0f)
    val longExposureProgress = _longExposureProgress.asStateFlow()

    private val _longExposureRemaining = MutableStateFlow("")
    val longExposureRemaining = _longExposureRemaining.asStateFlow()

    private var longExposureTimerJob: kotlinx.coroutines.Job? = null

    private var lastLocation: Location? = null
    private var currentRecording: Recording? = null
    private var sloMoFps: Int = 30

    // IMAGE_CAPTURE (system "take a photo") intent state. When capturing for a caller, we either
    // write the full-res JPEG to their EXTRA_OUTPUT Uri, or hand back a downscaled thumbnail.
    var captureForResult: Boolean = false
        private set
    private var resultOutputUri: Uri? = null

    /** Enters capture-for-result mode; [outputUri] is the caller's EXTRA_OUTPUT (may be null). */
    fun enableCaptureForResult(outputUri: Uri?) {
        captureForResult = true
        resultOutputUri = outputUri
    }

    val panoramaEngine = PanoramaEngine(app)

    // Unified manual session state (all modes bind through one CameraXViewfinder).
    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest = _surfaceRequest.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    // Cached per ProcessCameraProvider instance; ExtensionsManager must be tied to the same provider.
    private var extensionsManager: ExtensionsManager? = null
    private var sessionLifecycleOwner: ManualLifecycleOwner? = null
    private var boundCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    // The analyzer currently attached to imageAnalysis, so the night burst can swap in a temporary
    // frame collector and restore the previous analyzer (PhotoAnalyzer) when it finishes.
    private var currentAnalyzer: ImageAnalysis.Analyzer? = null

    /** True once the photo session's use cases (incl. ImageAnalysis) are bound. */
    private val _photoSessionActive = MutableStateFlow(false)
    val photoSessionActive = _photoSessionActive.asStateFlow()

    /**
     * True while the live preview is bound with the CameraX NIGHT extension (plain
     * PHOTO mode, low light). When set, capture goes straight through the already
     * extension-enabled session instead of the momentary rebind path.
     */
    private val _nightPreviewActive = MutableStateFlow(false)
    val nightPreviewActive = _nightPreviewActive.asStateFlow()

    // AE/AF lock (long-press-to-lock on the preview).
    private val _focusLocked = MutableStateFlow(false)
    val focusLocked = _focusLocked.asStateFlow()

    // Video recording paused (between pause() and resume()).
    private val _recordingPaused = MutableStateFlow(false)
    val recordingPaused = _recordingPaused.asStateFlow()

    // Mic mute for video (persisted). Applied live to the active recording.
    private val _micMuted = MutableStateFlow(false)
    val micMuted = _micMuted.asStateFlow()

    // True when an ImageCapture is bound alongside the video session (in-recording snapshots).
    private val _videoSnapshotSupported = MutableStateFlow(false)
    val videoSnapshotSupported = _videoSnapshotSupported.asStateFlow()

    // Hardware-shutter (volume key) events, collected by the UI to run the same capture action.
    private val _shutterEvents = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val shutterEvents = _shutterEvents.asSharedFlow()

    fun triggerShutter() {
        _shutterEvents.tryEmit(Unit)
    }

    // Burst mode (press-and-hold shutter): fires single-frame captures back-to-back with only one
    // in flight at a time, up to BURST_MAX, until the user releases (stopBurst).
    private val _burstActive = MutableStateFlow(false)
    val burstActive = _burstActive.asStateFlow()

    private val _burstCount = MutableStateFlow(0)
    val burstCount = _burstCount.asStateFlow()

    // Motion-Photo ring buffer: the last ~MOTION_WINDOW of analysis frames (RGB copies), fed by the
    // PhotoAnalyzer off the shared analysis stream and drained when a Motion Photo is captured.
    private class MotionFrame(val bitmap: Bitmap, val timestampNanos: Long, val rotationDegrees: Int)
    private val motionFrames = ArrayDeque<MotionFrame>()
    private val motionLock = Any()

    // High-speed session state — Slo-Mo-only (back camera, true HFR, no fallbacks)
    private var highSpeedVideoCapture: VideoCapture<Recorder>? = null
    private var highSpeedRecording: Recording? = null

    private val _highSpeedActive = MutableStateFlow(false)
    val highSpeedActive = _highSpeedActive.asStateFlow()

    /** Whether the back camera supports true HFR slo-mo on this device. */
    private val _sloMoSupported = MutableStateFlow<Boolean?>(null)
    val sloMoSupported = _sloMoSupported.asStateFlow()

    // Manual video session state (VIDEO / TIMELAPSE modes)
    private var videoCapture: VideoCapture<Recorder>? = null

    private val _videoSessionActive = MutableStateFlow(false)
    val videoSessionActive = _videoSessionActive.asStateFlow()

    /** True when the active video session is encoding AV1 (vs. the default codec). */
    var recordingWithAv1: Boolean = false
        private set

    /** True when the active video session is encoding HEVC/H.265. */
    var recordingWithHevc: Boolean = false
        private set

    fun setSloMoFps(fps: Int) {
        sloMoFps = fps
    }

    init {
        loadSettings()
        panoramaEngine.onSweepComplete = { finishPanoramaSweep() }
        viewModelScope.launch {
            _lastCaptureUri.collect { uri -> _galleryThumbnail.value = loadThumbnail(uri) }
        }
        // Probe Slo-Mo capability once (back camera only). UI hides Slo-Mo if unsupported.
        viewModelScope.launch {
            _sloMoSupported.value = probeSloMoSupport()
        }
        // DEBUG: Night mode resolution question – extension uses default resolution vs max-res, may be lower so can't take full quality?
        viewModelScope.launch {
            var last: List<Pair<String, Float>> = emptyList()
            availableZoomLevels.collect { levels ->
                if (levels != last) {
                    Log.d("NightPreview", "CameraViewModel availableZoomLevels FLOW emitted=$levels previous=$last nightPreviewActive=${_nightPreviewActive.value} photoActive=${_photoSessionActive.value} zoomRatio=${_zoomRatio.value} – if only [1x], zoom bar appears disappeared")
                    last = levels
                }
            }
        }
        viewModelScope.launch {
            var lastRes: android.util.Size? = null
            surfaceRequest.collect { req ->
                val res = req?.resolution
                if (res != lastRes) {
                    Log.d("NightPreview", "CameraViewModel surfaceRequest FLOW emitted res=$res previous=$lastRes nightPreviewActive=${_nightPreviewActive.value} photoActive=${_photoSessionActive.value} – null->val during extension bind, black if stuck null")
                    lastRes = res
                }
            }
        }
        viewModelScope.launch {
            nightModeActive.collect { active ->
                Log.d("NightPreview", "CameraViewModel nightModeActive FLOW=$active lowLight=${_lowLightDetected.value} overriddenOff=${_nightModeOverriddenOff.value} – button toggle drives this, triggers session rebind via useNightPreview in UI")
            }
        }
    }

    /**
     * Probe whether the BACK lens supports true high-speed video. Runs once at startup.
     * Does not affect other modes' quality.
     */
    private suspend fun probeSloMoSupport(): Boolean {
        return try {
            val provider = ProcessCameraProvider.awaitInstance(app)
            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
            val cameraInfo = provider.getCameraInfo(selector)
            val caps = Recorder.getHighSpeedVideoCapabilities(cameraInfo) ?: return false
            val quals = caps.getSupportedQualities(androidx.camera.core.DynamicRange.SDR)
            if (quals.isEmpty()) return false
            // Need at least one FHD/HD HFR range
            val preview = Preview.Builder().build()
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.FHD, Quality.HD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
            )
            val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
            val vc = VideoCapture.Builder(recorder).build()
            val tempConfig = HighSpeedVideoSessionConfig.Builder(vc)
                .setPreview(preview)
                .setSlowMotionEnabled(true)
                .build()
            val ranges = try {
                cameraInfo.getSupportedFrameRateRanges(tempConfig)
            } catch (_: Exception) { emptyList() }
            ranges.isNotEmpty() && ranges.any { it.upper >= 60 }
        } catch (e: Exception) {
            Log.w("SloMo", "Slo-Mo probe failed", e)
            false
        }
    }

    private suspend fun loadThumbnail(uri: Uri?): Bitmap? = uri?.let {
        withContext(Dispatchers.IO) {
            try {
                app.contentResolver.loadThumbnail(it, Size(96, 96), null)
            } catch (e: Exception) {
                Log.w("CameraViewModel", "Failed to load gallery thumbnail", e)
                null
            }
        }
    }

    private fun loadSettings() {
        ds.getString("camera_flash")?.let { _flashMode.value = FlashMode.valueOf(it) }
        ds.getString("camera_timer")?.let { _timerDuration.value = TimerDuration.valueOf(it) }
        ds.getString("camera_aspect_ratio")?.let { _aspectRatio.value = AspectRatioOption.valueOf(it) }
        ds.getString("camera_video_codec")?.let {
            _videoCodec.value = try { VideoCodec.valueOf(it) } catch (_: Exception) {
                when {
                    CodecSupport.isHardwareAv1EncoderAvailable -> VideoCodec.AV1
                    CodecSupport.isHevcEncoderAvailable -> VideoCodec.HEVC
                    else -> VideoCodec.AVC
                }
            }
        }
        ds.getString("camera_location")?.let { _locationEnabled.value = it.toBoolean() }
        ds.getString("camera_audio_source")?.let {
            _audioInputSource.value = try { AudioInputSource.valueOf(it) } catch (_: Exception) { AudioInputSource.CAMCORDER }
        }
        // Guard against a blank persisted value: Uri.parse("") yields a non-null
        ds.getString("camera_last_capture")?.takeIf { it.isNotBlank() }?.let { _lastCaptureUri.value = it.toUri() }
        ds.getString("camera_grid")?.let { _gridEnabled.value = it.toBoolean() }
        ds.getString("camera_level")?.let { _levelEnabled.value = it.toBoolean() }
        ds.getString("camera_mic_muted")?.let { _micMuted.value = it.toBoolean() }
        ds.getString("camera_zoom_ratio")?.toFloatOrNull()?.let { _zoomRatio.value = it }
        ds.getString("camera_mirror_front")?.let { _mirrorFront.value = it.toBoolean() }
        if (_levelEnabled.value) registerLevelSensor()
    }

    fun setFlashMode(mode: FlashMode) {
        _flashMode.value = mode
        viewModelScope.launch { ds.setString("camera_flash", mode.name) }
    }

    /**
     * Toggle horizontal mirroring of the front-camera preview and saved photo/video (issue #632).
     * Takes effect on the next capture; the preview updates immediately via the UI layer.
     */
    fun setMirrorFront(enabled: Boolean) {
        _mirrorFront.value = enabled
        viewModelScope.launch { ds.setString("camera_mirror_front", enabled.toString()) }
    }

    fun toggleTorch() {
        _torchEnabled.value = !_torchEnabled.value
    }

    fun setTimerDuration(duration: TimerDuration) {
        _timerDuration.value = duration
        viewModelScope.launch { ds.setString("camera_timer", duration.name) }
    }

    fun setAspectRatio(ratio: AspectRatioOption) {
        _aspectRatio.value = ratio
        // Re-apply the crop to the live capture use case so the next shot (and
        // its preview cropRect) matches the newly selected ratio without a rebind.
        imageCapture?.setCropAspectRatio(currentCropAspectRatio())
        viewModelScope.launch { ds.setString("camera_aspect_ratio", ratio.name) }
    }

    /**
     * Still-capture crop aspect ratio, expressed width:height in the portrait UI
     * orientation (the activity is portrait-locked) so it matches the on-screen
     * preview box. CameraX crops OutputFileOptions saves to this and sets the
     * ImageProxy cropRect for in-memory captures.
     */
    private fun currentCropAspectRatio(): Rational = when (_aspectRatio.value) {
        AspectRatioOption.RATIO_1_1 -> Rational(1, 1)
        AspectRatioOption.RATIO_4_3 -> Rational(3, 4)
        AspectRatioOption.RATIO_16_9 -> Rational(9, 16)
    }

    /** Center-crop [src] to [rect] (the ImageProxy cropRect), returning [src] unchanged for a
     *  full-frame/empty rect. Recycles the pre-crop bitmap when a new one is produced. */
    private fun cropToRect(src: Bitmap, rect: Rect): Bitmap {
        val left = rect.left.coerceIn(0, src.width)
        val top = rect.top.coerceIn(0, src.height)
        val w = rect.width().coerceAtMost(src.width - left)
        val h = rect.height().coerceAtMost(src.height - top)
        if (w <= 0 || h <= 0 || (left == 0 && top == 0 && w == src.width && h == src.height)) return src
        return Bitmap.createBitmap(src, left, top, w, h).also { if (it != src) src.recycle() }
    }

    /** Cycles the aspect ratio 4:3 → 16:9 → 1:1 → 4:3 (top-bar icon). */
    fun cycleAspectRatio() {
        val order = listOf(
            AspectRatioOption.RATIO_4_3,
            AspectRatioOption.RATIO_16_9,
            AspectRatioOption.RATIO_1_1
        )
        val next = order[(order.indexOf(_aspectRatio.value) + 1) % order.size]
        setAspectRatio(next)
    }

    fun setLocationEnabled(enabled: Boolean) {
        _locationEnabled.value = enabled
        viewModelScope.launch { ds.setString("camera_location", enabled.toString()) }
    }

    fun setVideoCodec(codec: VideoCodec) {
        _videoCodec.value = codec
        viewModelScope.launch { ds.setString("camera_video_codec", codec.name) }
    }

    fun setAudioInputSource(source: AudioInputSource) {
        _audioInputSource.value = source
        viewModelScope.launch { ds.setString("camera_audio_source", source.name) }
    }

    fun toggleGrid() {
        _gridEnabled.value = !_gridEnabled.value
        viewModelScope.launch { ds.setString("camera_grid", _gridEnabled.value.toString()) }
    }

    fun toggleLevel() {
        _levelEnabled.value = !_levelEnabled.value
        if (_levelEnabled.value) registerLevelSensor() else unregisterLevelSensor()
        viewModelScope.launch { ds.setString("camera_level", _levelEnabled.value.toString()) }
    }

    private fun registerLevelSensor() {
        if (levelSensorRegistered) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager.registerListener(levelListener, sensor, SensorManager.SENSOR_DELAY_UI)
        levelSensorRegistered = true
    }

    private fun unregisterLevelSensor() {
        if (!levelSensorRegistered) return
        sensorManager.unregisterListener(levelListener)
        levelSensorRegistered = false
    }

    /** Maps the 0..1 blur-strength UI value to the bokeh shader's blurScale multiplier. */
    fun setBlurStrength(value: Float) {
        _blurStrength.value = value.coerceIn(0f, 1f)
    }

    fun setExposureCompensation(value: Float) {
        _exposureCompensation.value = value
    }

    fun setWarmth(value: Float) {
        _warmth.value = value
    }

    fun setShadows(value: Float) {
        _shadows.value = value
    }

    fun setExposureTimeIndex(index: Int) {
        _exposureTimeIndex.value = index.coerceIn(0, EXPOSURE_TIME_STOPS.lastIndex)
        applyManualControls()
    }

    /** ISO index 0 == Auto; otherwise a 1-based index into [_isoStops]. */
    fun setManualIsoIndex(index: Int) {
        _manualIsoIndex.value = index.coerceIn(0, _isoStops.value.size)
        applyManualControls()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun camera2ControlOrNull(): androidx.camera.camera2.interop.Camera2CameraControl? = try {
        boundCamera?.cameraControl?.let {
            androidx.camera.camera2.interop.Camera2CameraControl.from(it)
        }
    } catch (e: Exception) {
        Log.w("CameraViewModel", "Camera2 control unavailable", e)
        null
    }

    /** The manual ISO for the current index, or null when set to Auto. */
    private fun manualIso(): Int? =
        _manualIsoIndex.value.takeIf { it > 0 }?.let { _isoStops.value.getOrNull(it - 1) }

    /** True when both shutter and ISO are on Auto (no manual exposure). */
    private fun isExposureAuto(): Boolean =
        _exposureTimeIndex.value == 0 && _manualIsoIndex.value == 0

    /**
     * Rebuilds a single [CaptureRequestOptions] from the current manual exposure/ISO state and
     * pushes it to the bound camera, affecting the live preview and subsequent stills. When on Auto
     * the options are cleared, reverting to CameraX's default auto behavior (including tap-to-focus).
     * Called on every manual-control change and re-applied after a session rebind.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun applyManualControls() {
        val cam2 = camera2ControlOrNull() ?: return
        val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()

        // Manual exposure / ISO with linkage: if either is manual, lock AE off and set both,
        // seeding the un-set one from the last auto-converged value (or a sensible default).
        val manualShutter = EXPOSURE_TIME_STOPS[_exposureTimeIndex.value].nanos
        val manualIso = manualIso()
        if (manualShutter != null || manualIso != null) {
            val exposure = manualShutter ?: lastAeExposureNanos ?: 16_666_667L // ~1/60s
            val iso = manualIso ?: lastAeIso ?: _isoStops.value.getOrNull(_isoStops.value.size / 2) ?: 400
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
            )
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, exposure
            )
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, iso
            )
        }

        try {
            // An empty options set clears any previously-applied manual 3A → full auto.
            cam2.setCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to apply manual controls", e)
        }
    }

    private fun resetManualControls() {
        _manualIsoIndex.value = 0
        _exposureTimeIndex.value = 0
    }

    /**
     * Feeds the PhotoAnalyzer's average scene luminance through a hysteresis + debounce filter so
     * night mode engages/disengages smoothly. When the scene brightens back up (true→false), the
     * user's per-scene override is reset so the next dark scene re-engages cleanly.
     */
    fun onLuminance(avg: Float) {
        // getNightModeIndicator() is authoritative when the device supports it — skip the luminance
        // heuristic so the two don't fight. This luma path is only the fallback for devices without
        // the indicator.
        if (nightIndicatorSupported) return
        val beforeLow = _lowLightDetected.value
        val beforeOff = _nightModeOverriddenOff.value
        Log.d("NightPreview", "onLuminance() avg=$avg lowLightBefore=$beforeLow overriddenOff=$beforeOff lowFrames=$lowLumaFrames highFrames=$highLumaFrames nightActive=${nightModeActive.value} photoActive=${_photoSessionActive.value} nightPreviewActive=${_nightPreviewActive.value} thread=${Thread.currentThread().name}")
        if (_lowLightDetected.value) {
            if (avg > NIGHT_DISENGAGE_LUMA) {
                highLumaFrames++
                lowLumaFrames = 0
                Log.d("NightPreview", "onLuminance() currently in low-light, avg $avg > disengage ${NIGHT_DISENGAGE_LUMA}, highFrames=$highLumaFrames/${NIGHT_DEBOUNCE_FRAMES}")
                if (highLumaFrames >= NIGHT_DEBOUNCE_FRAMES) {
                    Log.d("NightPreview", "onLuminance() DISENGAGING night – high luma for $NIGHT_DEBOUNCE_FRAMES frames, lowLight=true->false")
                    _lowLightDetected.value = false
                    _nightModeOverriddenOff.value = false
                    highLumaFrames = 0
                    Log.d("NightPreview", "onLuminance() after DISENGAGE lowLight=${_lowLightDetected.value} nightActive=${nightModeActive.value} – triggers teardown->setupPhotoSession() rebind")
                }
            } else {
                if (highLumaFrames != 0) Log.d("NightPreview", "onLuminance() resetting highFrames 0 (avg=$avg still below disengage)")
                highLumaFrames = 0
            }
        } else {
            if (avg < NIGHT_ENGAGE_LUMA) {
                lowLumaFrames++
                highLumaFrames = 0
                Log.d("NightPreview", "onLuminance() avg $avg < engage ${NIGHT_ENGAGE_LUMA}, lowFrames=$lowLumaFrames/${NIGHT_DEBOUNCE_FRAMES}")
                if (lowLumaFrames >= NIGHT_DEBOUNCE_FRAMES) {
                    Log.d("NightPreview", "onLuminance() ENGAGING night – low luma for $NIGHT_DEBOUNCE_FRAMES frames, lowLight=false->true")
                    _lowLightDetected.value = true
                    lowLumaFrames = 0
                    Log.d("NightPreview", "onLuminance() after ENGAGE lowLight=${_lowLightDetected.value} nightActive=${nightModeActive.value} overriddenOff=${_nightModeOverriddenOff.value} – if extension available, useNightPreview should become true and rebind to setupNightPreviewSession()")
                }
            } else {
                if (lowLumaFrames != 0) Log.d("NightPreview", "onLuminance() resetting lowFrames 0 (avg=$avg above engage)")
                lowLumaFrames = 0
            }
        }
        if (beforeLow != _lowLightDetected.value || beforeOff != _nightModeOverriddenOff.value) {
            Log.d("NightPreview", "onLuminance() STATE CHANGE low $beforeLow -> ${_lowLightDetected.value} off $beforeOff -> ${_nightModeOverriddenOff.value} nightActive ${nightModeActive.value}")
        }
    }

    /** Toggles the user's override for the current dark scene (moon button handler). */
    fun toggleNightModeOverride() {
        val before = _nightModeOverriddenOff.value
        _nightModeOverriddenOff.value = !_nightModeOverriddenOff.value
        Log.d("NightPreview", "toggleNightModeOverride() CLICK moon button beforeOff=$before afterOff=${_nightModeOverriddenOff.value} lowLight=${_lowLightDetected.value} nightActiveBefore=${!before && _lowLightDetected.value} nightActiveAfter=${nightModeActive.value} thread=${Thread.currentThread().name} – screen should transition? if extension binds, preview resolution changes and zoom bar may collapse to 1x")
    }

    private fun setLastCaptureUri(uri: Uri?) {
        _lastCaptureUri.value = uri
        viewModelScope.launch { ds.setString("camera_last_capture", uri?.toString() ?: "") }
    }

    fun switchCameraMode(newMode: CameraMode) {
        // A new mode starts with a clean night-detection slate (teardown no longer
        // resets it, since it also runs on night<->normal preview rebinds).
        if (newMode != _cameraMode.value) resetNightModeDetection()
        // Slo-Mo is back-camera only; enforce it when entering Slo-Mo.
        if (newMode == CameraMode.SLOW_MO) {
            if (_lensFacing.value != CameraSelector.LENS_FACING_BACK) {
                _lensFacing.value = CameraSelector.LENS_FACING_BACK
                resetNightModeDetection()
            }
        }
        _cameraMode.value = newMode
    }

    fun flipCamera() {
        // Disallow flipping while in Slo-Mo — it's back-camera only, true HFR.
        if (_cameraMode.value == CameraMode.SLOW_MO) return
        _lensFacing.value = if (_lensFacing.value == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        resetNightModeDetection()
    }

    private fun resetNightModeDetection() {
        _lowLightDetected.value = false
        _nightModeOverriddenOff.value = false
        lowLumaFrames = 0
        highLumaFrames = 0
    }

    /**
     * Whether captures should be horizontally mirrored to match the preview. CameraX mirrors the
     * front-camera preview but saves un-mirrored by default, so selfies otherwise come out flipped.
     * Controlled by the user-facing "mirror selfie" setting (issue #632); only ever applies to the
     * front lens.
     */
    private val mirrorCaptures: Boolean
        get() = _lensFacing.value == CameraSelector.LENS_FACING_FRONT && _mirrorFront.value

    fun setQrResult(text: String?) {
        _qrResult.value = text
    }

    /**
     * The CameraXViewfinder's built-in pinch-to-zoom already applied [ratio] to the camera, so this
     * only syncs the displayed value (used by the zoom bar). It does NOT call cameraControl again —
     * that would double-apply and could feedback-loop with onZoomRatioChanged.
     */
    fun onViewfinderZoomRatio(ratio: Float) {
        _zoomRatio.value = ratio
        viewModelScope.launch { ds.setString("camera_zoom_ratio", ratio.toString()) }
    }

    fun setZoomRatio(ratio: Float) {
        val cam = boundCamera
        val zs = cam?.cameraInfo?.zoomState?.value
        Log.d("NightPreview", "setZoomRatio() requested=$ratio clamped? min=${zs?.minZoomRatio} max=${zs?.maxZoomRatio} current=${zs?.zoomRatio} nightPreviewActive=${_nightPreviewActive.value} photoActive=${_photoSessionActive.value} boundCamera=${cam != null}")
        val clamped = zs?.let {
            ratio.coerceIn(it.minZoomRatio, it.maxZoomRatio)
        } ?: ratio
        if (clamped != ratio) Log.w("NightPreview", "setZoomRatio() CLAMPED $ratio -> $clamped due to zoomState min/max – vendor NIGHT often reports max=1x, causing bar to show only 1x")
        _zoomRatio.value = clamped
        viewModelScope.launch { ds.setString("camera_zoom_ratio", clamped.toString()) }
        try {
            cam?.cameraControl?.setZoomRatio(clamped)
        } catch (e: Exception) {
            Log.e("NightPreview", "setZoomRatio() setZoomRatio() threw (was hidden before)", e)
        }
    }

    /**
     * Restore the previously selected zoom after a (re)bind instead of snapping back to the
     * hardware default. [_zoomRatio] holds the last value the user picked (kept in memory across
     * teardown/rebind and loaded from DataStore on process restart); clamp it to the new lens'
     * supported range and re-apply it to the camera. Fixes issue #631 (zoom reset on resume).
     */
    private fun restoreZoom(minZoom: Float, maxZoom: Float) {
        val desired = _zoomRatio.value.coerceIn(minZoom, maxZoom)
        _zoomRatio.value = desired
        try {
            boundCamera?.cameraControl?.setZoomRatio(desired)
        } catch (e: Exception) {
            Log.e("NightPreview", "restoreZoom() setZoomRatio() threw", e)
        }
    }

    fun updateZoomLevels(minZoom: Float, maxZoom: Float) {
        Log.d("NightPreview", "updateZoomLevels() min=$minZoom max=$maxZoom nightPreviewActive=${_nightPreviewActive.value} photoActive=${_photoSessionActive.value} currentRatio=${_zoomRatio.value} thread=${Thread.currentThread().name}")
        val levels = mutableListOf<Pair<String, Float>>()
        // Wide-angle entry only when the lens can actually zoom out past 1x.
        if (minZoom < 0.95f) {
            levels.add(formatZoomLabel(minZoom) to minZoom)
        }
        levels.add("1x" to 1f)
        for (tele in listOf(2f, 5f)) {
            if (tele <= maxZoom + 0.05f) levels.add(formatZoomLabel(tele) to tele)
        }
        Log.d("NightPreview", "updateZoomLevels() emitting levels=$levels – if min=1f max=1f, only [1x] will show, explaining 'all zoom levels also disappear'")
        _availableZoomLevels.value = levels
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun updateLocation() {
        if (!_locationEnabled.value) return
        try {
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lastLocation = lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to read last known location", e)
        }
    }

    /**
     * Sets up a true high-speed (HFR) session for Slo-Mo.
     *
     * Requirements per product spec:
     * - Back camera only (no front-camera / selfie Slo-Mo)
     * - No normal-speed fallbacks: only bind true HFR (slowMotionEnabled=true); if it fails,
     *   return false so the caller knows the device can't do Slo-Mo.
     * - Quality handling is Slo-Mo-only and does NOT affect VIDEO/PHOTO/etc (those use their own
     *   QualitySelector in setupVideoSession/setupPhotoSession). This method's quality selection
     *   only touches the HFR Recorder built here.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun setupHighSpeedSession(): Boolean {
        return try {
            val provider = ProcessCameraProvider.awaitInstance(app)
            cameraProvider = provider
            provider.unbindAll()

            // Enforce back camera — Slo-Mo is disallowed on front.
            if (_lensFacing.value != CameraSelector.LENS_FACING_BACK) {
                _lensFacing.value = CameraSelector.LENS_FACING_BACK
            }
            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            val cameraInfo = provider.getCameraInfo(selector)
            val capabilities = Recorder.getHighSpeedVideoCapabilities(cameraInfo)
            if (capabilities == null) {
                Log.d("SloMo", "High-speed video not supported on back camera on this device")
                _sloMoSupported.value = false
                return false
            }

            // Only look at SDR qualities supported by the HFR path — this is Slo-Mo-local.
            // Bug fix for Pixel 8 black screen: default VideoCapture quality (often UHD) is
            // not supported for HFR on many devices; filtering to supported HFR qualities fixes bind.
            val supportedQualities = capabilities.getSupportedQualities(
                androidx.camera.core.DynamicRange.SDR
            )
            Log.d("SloMo", "High-speed supported qualities (back): $supportedQualities")

            if (supportedQualities.isEmpty()) {
                Log.e("SloMo", "High-speed reports no supported qualities")
                _sloMoSupported.value = false
                return false
            }

            // Build a QualitySelector using ONLY qualities that HFR actually supports.
            // Prefer FHD/HD which are the typical slo-mo resolutions on Pixel 8 (1080p 120/240fps).
            // This never touches other modes' quality selectors.
            val preferredOrder = listOf(Quality.FHD, Quality.HD, Quality.UHD, Quality.SD)
            val orderedQualities = preferredOrder.filter { it in supportedQualities }
                .ifEmpty { supportedQualities.toList() }
            val qualitySelector = QualitySelector.fromOrderedList(
                orderedQualities,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
            )

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider { request -> _surfaceRequest.value = request }

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            // MirrorMode is not allowed for high-speed video (HighSpeedVideoSessionConfig
            // validates and throws IllegalArgumentException). Slo-Mo is back-only anyway.
            val videoCapture = VideoCapture.Builder(recorder).build()
            highSpeedVideoCapture = videoCapture

            // Query available HFR frame-rate ranges via a temp config.
            val tempConfig = HighSpeedVideoSessionConfig.Builder(videoCapture)
                .setPreview(preview)
                .setSlowMotionEnabled(true)
                .build()
            val ranges = try {
                cameraInfo.getSupportedFrameRateRanges(tempConfig)
            } catch (e: Exception) {
                Log.w("SloMo", "Failed to query HFR ranges", e)
                emptyList()
            }
            Log.d("SloMo", "High-speed supported frame rate ranges: $ranges")

            if (ranges.isEmpty()) {
                Log.e("SloMo", "No high-speed frame rate ranges available")
                _sloMoSupported.value = false
                return false
            }

            // Try ranges from highest fps downwards — Pixel 8 back: 240fps, 120fps.
            // No normal-speed (<=30fps) fallbacks allowed; only true HFR >= 60fps.
            val hfrRanges = ranges.filter { it.upper >= 60 }.sortedByDescending { it.upper }
            if (hfrRanges.isEmpty()) {
                Log.e("SloMo", "No true HFR (>=60fps) ranges found")
                _sloMoSupported.value = false
                return false
            }

            var bound = false
            var lastError: Exception? = null
            for (range in hfrRanges) {
                try {
                    provider.unbindAll()
                    // Re-attach surface provider after unbindAll for preview to re-emit request
                    preview.setSurfaceProvider { request -> _surfaceRequest.value = request }

                    val configBuilder = HighSpeedVideoSessionConfig.Builder(videoCapture)
                        .setPreview(preview)
                        .setSlowMotionEnabled(true)
                        .setFrameRateRange(range)
                        .setAutoRotationEnabled(true)

                    val owner = ManualLifecycleOwner()
                    owner.start()
                    sessionLifecycleOwner?.destroy()
                    sessionLifecycleOwner = owner

                    boundCamera = provider.bindToLifecycle(owner, selector, configBuilder.build())
                    sloMoFps = range.upper
                    Log.d("SloMo", "High-speed session bound at ${range.upper}fps (range=$range), quality=$orderedQualities")
                    bound = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    Log.w("SloMo", "Failed to bind HFR at $range, trying next", e)
                    sessionLifecycleOwner?.destroy()
                    sessionLifecycleOwner = null
                }
            }

            if (!bound) {
                Log.e("SloMo", "All HFR ranges failed, last error: $lastError")
                _sloMoSupported.value = false
                return false
            }

            // Set anti-banding to reduce flicker under artificial light
            try {
                val cam2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(
                    boundCamera!!.cameraControl
                )
                cam2Control.setCaptureRequestOptions(
                    androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(
                            android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                            android.hardware.camera2.CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
                        )
                        .build()
                )
            } catch (e: Exception) {
                Log.w("SloMo", "Could not set anti-banding", e)
            }

            boundCamera?.cameraInfo?.zoomState?.value?.let {
                updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
                restoreZoom(it.minZoomRatio, it.maxZoomRatio)
                Log.d("NightPreview", "setupPhotoSession() after levels=
            _sloMoSupported.value = true
            _highSpeedActive.value = true
            true
        } catch (e: Exception) {
            Log.e("SloMo", "Failed to set up high-speed session", e)
            false
        }
    }

    /**
     * Binds [useCases] via a [SessionConfig] with auto-rotation enabled, so CameraX applies the
     * correct output rotation from the device sensors — no manual targetRotation plumbing needed.
     * Every session (photo/night/portrait/pano/video) binds through here.
     */
    private fun bindSession(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        selector: CameraSelector,
        vararg useCases: UseCase,
    ): Camera = provider.bindToLifecycle(
        owner,
        selector,
        SessionConfig.Builder(*useCases).setAutoRotationEnabled(true).build(),
    )

    /**
     * Binds a manual Preview + ImageCapture + ImageAnalysis session for the photo modes
     * (PHOTO / PORTRAIT / PANORAMA / PHOTOSPHERE / QR). ImageCapture requests the sensor's
     * maximum resolution; if the 3-stream max-res combination exceeds a device's stream-config
     * limits, it falls back to a default ImageCapture resolution. ImageAnalysis is always
     * capped (~1.2 MP) — see the note in [bind].
     */
    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun setupPhotoSession(): Boolean {
        Log.d("NightPreview", "setupPhotoSession() ENTRY thread=${Thread.currentThread().name} lens=${_lensFacing.value} surfaceBefore=${_surfaceRequest.value?.resolution}")
        return try {
            val provider = ProcessCameraProvider.awaitInstance(app)
            cameraProvider = provider
            Log.d("NightPreview", "setupPhotoSession() got providerHash=${provider.hashCode()}")
            provider.unbindAll()

            val selector = CameraSelector.Builder()
                .requireLensFacing(_lensFacing.value)
                .build()

            val previewBuilder = Preview.Builder()
            // Snapshot auto-converged AE ISO/exposure off the repeating preview requests so a
            // half-manual exposure can seed the un-set parameter.
            try {
                androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder)
                    .setSessionCaptureCallback(aeSnapshotCallback)
                Log.d("NightPreview", "setupPhotoSession() attached AE snapshot callback")
            } catch (e: Exception) {
                Log.e("NightPreview", "setupPhotoSession() Could not attach AE snapshot callback (was hidden as Warn)", e)
            }
            val preview = previewBuilder.build()
            preview.setSurfaceProvider { request ->
                Log.d("NightPreview", "setupPhotoSession() surfaceRequest emitted res=${request.resolution} format=${request.javaClass.simpleName} thread=${Thread.currentThread().name} nightActive=${nightModeActive.value}")
                _surfaceRequest.value = request
            }
            Log.d("NightPreview", "setupPhotoSession() preview surfaceProvider attached")

            val owner = ManualLifecycleOwner()
            owner.start()
            sessionLifecycleOwner = owner

            // Ultra HDR (JPEG with a gain map) when the sensor/pipeline supports it. Queried once
            // here; the bind ladder falls back to plain JPEG if the Ultra HDR combo can't bind.
            val ultraHdrSupported = try {
                val cameraInfo = provider.getCameraInfo(selector)
                val caps = ImageCapture.getImageCaptureCapabilities(cameraInfo).supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                Log.d("NightPreview", "setupPhotoSession() ultraHdrSupported=$caps selector=$selector")
                caps
            } catch (e: Exception) {
                Log.e("NightPreview", "setupPhotoSession() Could not query Ultra HDR support (was hidden as Warn)", e)
                false
            }

            fun bind(maxRes: Boolean, ultraHdr: Boolean): Camera {
                Log.d("NightPreview", "setupPhotoSession() bind(maxRes=$maxRes ultraHdr=$ultraHdr) START thread=${Thread.currentThread().name}")
                return try {
                    val selectorBuilder = ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    if (maxRes) {
                        selectorBuilder.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    }
                    val captureBuilder = ImageCapture.Builder()
                        .setResolutionSelector(selectorBuilder.build())
                        .setFlashMode(getImageCaptureFlashMode())
                    if (ultraHdr) {
                        captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                    }
                    val capture = captureBuilder.build()
                    imageCapture = capture
                    // Crop stills to the selected aspect ratio (1:1 / 16:9 / 4:3). CameraX crops
                    // OutputFileOptions saves to this and exposes it as cropRect for in-memory shots.
                    capture.setCropAspectRatio(currentCropAspectRatio())
                    // Cap the analysis stream at ~1.2 MP, independently of [maxRes] (which stays
                    // about ImageCapture — stills are unaffected and still come off the sensor at
                    // full resolution). Nothing reading this stream benefits from sensor
                    // resolution: PhotoAnalyzer samples average luminance, runs a ZXing decode
                    // over the whole Y plane, and copies each frame via toBitmap() for the
                    // Motion-Photo ring buffer, whose frames MotionPhotoEncoder then converts to
                    // I420 in a per-pixel loop. All of that is paid per frame and scales with
                    // area, so an uncapped stream made QR scanning and motion capture far more
                    // expensive on high-end sensors for no quality gain. Night mode is unaffected:
                    // captureNightBurst() shoots full-resolution frames through ImageCapture.
                    val analysisBuilder = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 960), // ~1.2 MP
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                    )
                                )
                                .build()
                        )
                    val analysis = analysisBuilder.build()
                    imageAnalysis = analysis
                    bindSession(provider, owner, selector, preview, capture, analysis).also {
                        Log.d("NightPreview", "setupPhotoSession() bind SUCCESS res=${it.cameraInfo} zoom min=${it.cameraInfo.zoomState.value?.minZoomRatio} max=${it.cameraInfo.zoomState.value?.maxZoomRatio}")
                    }
                } catch (e: Exception) {
                    Log.e("NightPreview", "setupPhotoSession() bind(maxRes=$maxRes ultra=$ultraHdr) EXCEPTION – root cause of black preview when fallback also fails", e)
                    throw e
                }
            }

            // Fallback ladder: UltraHDR+maxres → UltraHDR+default → JPEG+default.
            boundCamera = try {
                bind(maxRes = true, ultraHdr = ultraHdrSupported)
            } catch (e: Exception) {
                Log.e("NightPreview", "setupPhotoSession() Max-res bind failed (was Warn, hidden); retrying at default resolution. This is where resolution becomes lower and cannot take full quality!", e)
                try {
                    provider.unbindAll()
                } catch (e2: Exception) {
                    Log.e("NightPreview", "setupPhotoSession() unbindAll on fallback failed (hidden)", e2)
                }
                try {
                    bind(maxRes = false, ultraHdr = ultraHdrSupported)
                } catch (e2: Exception) {
                    Log.e("NightPreview", "setupPhotoSession() default-res ultraHdr=$ultraHdrSupported bind FAILED (was hidden)", e2)
                    if (!ultraHdrSupported) throw e2
                    Log.e("NightPreview", "setupPhotoSession() Ultra HDR bind failed (was Warn), falling back to plain JPEG – lower quality path")
                    try {
                        provider.unbindAll()
                    } catch (e3: Exception) {
                        Log.e("NightPreview", "setupPhotoSession() unbindAll on second fallback failed", e3)
                    }
                    bind(maxRes = false, ultraHdr = false)
                }
            }

            val zs = boundCamera?.cameraInfo?.zoomState?.value
            Log.d("NightPreview", "setupPhotoSession() bound zoomState min=${zs?.minZoomRatio} max=${zs?.maxZoomRatio} ratio=${zs?.zoomRatio} thread=${Thread.currentThread().name}")
            boundCamera?.cameraInfo?.zoomState?.value?.let {
                Log.d("NightPreview", "setupPhotoSession() calling updateZoomLevels min=${it.minZoomRatio} max=${it.maxZoomRatio} – should show .5,1x,2x,5x if >1x else only 1x")
                updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
                restoreZoom(it.minZoomRatio, it.maxZoomRatio)
                Log.d("NightPreview", "setupNightPreviewSession() updated zoomRatio=
            }
            readManualControlRanges()
            applyManualControls()
            boundCamera?.cameraInfo?.let { observeNightModeIndicator(it) }
            _photoSessionActive.value = true
            Log.d("NightPreview", "setupPhotoSession() SUCCESS photoActive=true surface=${_surfaceRequest.value?.resolution} nightIndicatorSupported=$nightIndicatorSupported")
            true
        } catch (e: Exception) {
            Log.e("NightPreview", "setupPhotoSession() OUTER CATCH – Failed to set up photo session – solid black root? ${e.javaClass.simpleName} ${e.message}", e)
            false
        }
    }

    /**
     * Binds the CameraX NIGHT extension for the live PREVIEW (plain PHOTO mode):
     * Preview + ImageCapture, and — when the vendor extension reports it supports
     * concurrent analysis via [ExtensionsManager.isImageAnalysisSupported] — an
     * ImageAnalysis stream too, so [PhotoAnalyzer] keeps sampling luminance and
     * night mode can auto-disengage when the scene brightens (otherwise the moon
     * button is the manual exit). Falls back to the normal photo session if the
     * extension isn't available or can't be bound.
     */
    suspend fun setupNightPreviewSession(): Boolean {
        Log.d("NightPreview", "setupNightPreviewSession() ENTRY thread=${Thread.currentThread().name} lens=${_lensFacing.value} surfaceBefore=${_surfaceRequest.value?.resolution}")
        return try {
            val provider = ProcessCameraProvider.awaitInstance(app)
            cameraProvider = provider
            Log.d("NightPreview", "setupNightPreviewSession() got cameraProvider=$provider")
            val mgr = getExtensionsManager(provider)
            Log.d("NightPreview", "setupNightPreviewSession() ExtensionsManager=${mgr != null} cacheExt=${extensionsManager != null}")
            if (mgr == null) {
                Log.w("NightPreview", "setupNightPreviewSession() manager NULL, falling back to normal photo session")
                return setupPhotoSession()
            }
            val baseSelector = CameraSelector.Builder()
                .requireLensFacing(_lensFacing.value)
                .build()
            val extAvail = try {
                mgr.isExtensionAvailable(baseSelector, ExtensionMode.NIGHT)
            } catch (e: Exception) {
                Log.e("NightPreview", "setupNightPreviewSession() isExtensionAvailable threw (was hidden)", e)
                false
            }
            Log.d("NightPreview", "setupNightPreviewSession() isExtensionAvailable(NIGHT)=$extAvail lens=${_lensFacing.value}")
            if (!extAvail) {
                Log.w("NightPreview", "setupNightPreviewSession() extension NOT available on lens=${_lensFacing.value}, falling back")
                return setupPhotoSession()
            }
            Log.d("NightPreview", "setupNightPreviewSession() unbinding all before night selector")
            try {
                provider.unbindAll()
                Log.d("NightPreview", "setupNightPreviewSession() provider.unbindAll SUCCESS")
            } catch (e: Exception) {
                Log.e("NightPreview", "setupNightPreviewSession() unbindAll FAILED (was hidden)", e)
                throw e
            }
            val analysisSupported = try {
                mgr.isImageAnalysisSupported(baseSelector, ExtensionMode.NIGHT)
            } catch (e: Exception) {
                Log.e("NightPreview", "setupNightPreviewSession() isImageAnalysisSupported query FAILED", e)
                false
            }
            Log.d("NightPreview", "setupNightPreviewSession() isImageAnalysisSupported=$analysisSupported")

            // Proven-on-stock path: getExtensionEnabledCameraSelector + bindToLifecycle. It's
            // deprecated in 1.7.0-alpha02, but it's what actually binds on devices where the vendor
            // NIGHT extender works. On GrapheneOS/Pixel it throws "Framework size list map ...", but
            // we never get here there: isNightExtensionAvailable() probes first and hides the mode.
            val nightSelector = mgr.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.NIGHT)

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider { request ->
                Log.d("NightPreview", "setupNightPreviewSession() NEW surfaceRequest emitted res=${request.resolution}")
                _surfaceRequest.value = request
            }

            val owner = ManualLifecycleOwner()
            owner.start()
            sessionLifecycleOwner = owner

            val capture = ImageCapture.Builder()
                .setFlashMode(getImageCaptureFlashMode())
                .build()
            imageCapture = capture

            fun bind(withAnalysis: Boolean): Camera {
                Log.d("NightPreview", "setupNightPreviewSession() bind(withAnalysis=$withAnalysis) START")
                return if (withAnalysis) {
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    imageAnalysis = analysis
                    bindSession(provider, owner, nightSelector, preview, capture, analysis)
                } else {
                    imageAnalysis = null
                    bindSession(provider, owner, nightSelector, preview, capture)
                }
            }

            boundCamera = try {
                bind(withAnalysis = analysisSupported)
            } catch (e: Exception) {
                Log.w("NightPreview", "setupNightPreviewSession() bind FAILED withAnalysis=$analysisSupported: ${e.javaClass.simpleName} ${e.message}", e)
                if (!analysisSupported) throw e
                try { provider.unbindAll() } catch (_: Exception) {}
                bind(withAnalysis = false)
            }

            val zs = boundCamera?.cameraInfo?.zoomState?.value
            Log.d("NightPreview", "setupNightPreviewSession() boundCamera zoomState min=${zs?.minZoomRatio} max=${zs?.maxZoomRatio} current=${zs?.zoomRatio} – vendor NIGHT extension often reports 1x-only; this explains zoom bar disappearing (only 1x). Full-res capture is still max-res? No, extension uses default resolution, lower than max-res photo session, cannot take full quality while in extension preview")
            boundCamera?.cameraInfo?.zoomState?.value?.let {
                Log.d("NightPreview", "setupNightPreviewSession() calling updateZoomLevels min=${it.minZoomRatio} max=${it.maxZoomRatio}")
                updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
                restoreZoom(it.minZoomRatio, it.maxZoomRatio)
                Log.d("NightPreview", "setupPanoramaSession() levels=
            }
            // Do NOT observe getNightModeIndicator() on the extension camera: it reports
            // UNKNOWN/NOT_RECOMMENDED there, which fights the normal session's RECOMMENDED reading
            // and flips _lowLightDetected → an engage/disengage toggle loop. Night stays engaged
            // (frozen at the value the normal session detected) until the moon button turns it off.
            // We do watch the extension camera's state to catch async ExtensionCaptureSession
            // failures, and its strength for the UI indicator.
            boundCamera?.cameraInfo?.let {
                observeExtensionStrength(it)
                observeExtensionCameraState(it)
            }
            _nightPreviewActive.value = true
            _photoSessionActive.value = true
            Log.d("NightPreview", "setupNightPreviewSession() SUCCESS – nightPreviewActive=true photoSessionActive=true surfaceRequest=${_surfaceRequest.value?.resolution}")
            true
        } catch (e: Exception) {
            Log.e("NightPreview", "setupNightPreviewSession() OUTER CATCH – FAILED to set up night preview, falling back to normal photo session. Root cause of solid black: exception=${e.javaClass.simpleName} msg=${e.message}", e)
            _nightPreviewActive.value = false
            // The extension genuinely can't bind here (e.g. GrapheneOS/Pixel). Remember it so night
            // isn't offered again for a week; flipping nightExtensionUsable false also makes the UI
            // drop useNightPreview immediately, so we don't loop back into this failure.
            recordNightExtensionFailure()
            val fallback = try {
                setupPhotoSession()
            } catch (e2: Exception) {
                Log.e("NightPreview", "setupNightPreviewSession() fallback setupPhotoSession() ALSO FAILED (double hidden)", e2)
                false
            }
            Log.d("NightPreview", "setupNightPreviewSession() fallback result=$fallback")
            fallback
        }
    }

    /**
     * Binds a lean Preview + capped-resolution ImageAnalysis session for the
     * panorama and photo-sphere modes. These sweep off the analysis stream and
     * never use ImageCapture. The analysis stream is capped at ~3 MP: the pano
     * analyzer decodes every delivered frame to a Bitmap, so an uncapped max-res
     * stream janks the preview, while the 8 MP compose canvas means higher
     * per-frame resolution barely affects the stitched output.
     */
    suspend fun setupPanoramaSession(): Boolean {
        return try {
            val provider = ProcessCameraProvider.awaitInstance(app)
            cameraProvider = provider
            provider.unbindAll()

            val selector = CameraSelector.Builder()
                .requireLensFacing(_lensFacing.value)
                .build()

            val previewBuilder = Preview.Builder()
            val preview = previewBuilder.build()
            preview.setSurfaceProvider { request -> _surfaceRequest.value = request }

            val owner = ManualLifecycleOwner()
            owner.start()
            sessionLifecycleOwner = owner

            // Cap the analysis stream at ~3 MP. The compose canvas is bounded to
            // 8 MP, so per-frame resolution beyond a few MP adds little to the
            // stitched output — but the analyzer converts every delivered frame to
            // a Bitmap, so a max-res stream makes that conversion (and its GC
            // churn) heavy enough to jank the preview during the sweep. ~3 MP keeps
            // it smooth. Falls back to the device-default analysis resolution if
            // this bound can't bind.
            fun bind(capped: Boolean): Camera {
                val analysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                if (capped) {
                    analysisBuilder.setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(2016, 1512), // ~3 MP
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            )
                            .build()
                    )
                }
                val analysis = analysisBuilder.build()
                imageAnalysis = analysis
                imageCapture = null // No ImageCapture in this session.
                return bindSession(provider, owner, selector, preview, analysis)
            }

            boundCamera = try {
                Log.d("NightPreview", "setupPanoramaSession() bind capped=true START")
                bind(capped = true)
            } catch (e: Exception) {
                Log.e("NightPreview", "setupPanoramaSession() Capped panorama bind FAILED (was hidden as Warn), retrying at default – resolution lower!", e)
                try {
                    provider.unbindAll()
                    Log.d("NightPreview", "setupPanoramaSession() fallback unbindAll SUCCESS")
                } catch (e2: Exception) {
                    Log.e("NightPreview", "setupPanoramaSession() fallback unbindAll FAILED (swallowed)", e2)
                }
                try {
                    bind(capped = false)
                } catch (e2: Exception) {
                    Log.e("NightPreview", "setupPanoramaSession() default bind ALSO FAILED – black root ${e2.javaClass.simpleName} ${e2.message}", e2)
                    throw e2
                }
            }

            val zsP = boundCamera?.cameraInfo?.zoomState?.value
            Log.d("NightPreview", "setupPanoramaSession() bound zoom min=${zsP?.minZoomRatio} max=${zsP?.maxZoomRatio} ratio=${zsP?.zoomRatio}")
            boundCamera?.cameraInfo?.zoomState?.value?.let {
                Log.d("NightPreview", "setupPanoramaSession() updateZoomLevels min=${it.minZoomRatio} max=${it.maxZoomRatio}")
                updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
                restoreZoom(it.minZoomRatio, it.maxZoomRatio)
                Log.d("NightPreview", "setupPortraitSession() after update levels=
            }
            _photoSessionActive.value = true
            Log.d("NightPreview", "setupPanoramaSession() SUCCESS photoActive=true surface=${_surfaceRequest.value?.resolution}")
            true
        } catch (e: Exception) {
            Log.e("NightPreview", "setupPanoramaSession() OUTER CATCH – Failed solid black? ${e.javaClass.simpleName} ${e.message}", e)
            false
        }
    }

    /**
     * Portrait session: full-resolution ImageCapture (final image stays max-res) but capped
     * ImageAnalysis (~0.8 MP, 1024x768) for smooth preview segmentation. This fixes the
     * "No supported surface combination" bind failures that happened when portrait reused
     * the then-uncapped 3-stream photo path (Preview + max ImageCapture + max ImageAnalysis) for a
     * model that only needs 256x256.
     *
     * Fallback ladder prioritizes keeping max-res capture:
     * capped+max+UHD → capped+max+JPEG → capped+default+UHD → capped+default+JPEG → default+default
     */
    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun setupPortraitSession(): Boolean {
        Log.d("NightPreview", "setupPortraitSession() ENTRY lens=${_lensFacing.value} thread=${Thread.currentThread().name}")
        return try {
            val provider = ProcessCameraProvider.awaitInstance(app)
            cameraProvider = provider
            Log.d("NightPreview", "setupPortraitSession() providerHash=${provider.hashCode()}")
            provider.unbindAll()

            val selector = CameraSelector.Builder()
                .requireLensFacing(_lensFacing.value)
                .build()

            val previewBuilder = Preview.Builder()
            try {
                androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder)
                    .setSessionCaptureCallback(aeSnapshotCallback)
                Log.d("NightPreview", "setupPortraitSession() attached AE snapshot callback")
            } catch (e: Exception) {
                Log.e("NightPreview", "setupPortraitSession() Could not attach AE snapshot callback (was Warn)", e)
            }
            val preview = previewBuilder.build()
            preview.setSurfaceProvider { request ->
                Log.d("NightPreview", "setupPortraitSession() surfaceRequest res=${request.resolution} thread=${Thread.currentThread().name}")
                _surfaceRequest.value = request
            }

            val owner = ManualLifecycleOwner()
            owner.start()
            sessionLifecycleOwner = owner

            val ultraHdrSupported = try {
                val cameraInfo = provider.getCameraInfo(selector)
                val sup = ImageCapture.getImageCaptureCapabilities(cameraInfo).supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                Log.d("NightPreview", "setupPortraitSession() ultraHdrSupported=$sup")
                sup
            } catch (e: Exception) {
                Log.e("NightPreview", "setupPortraitSession() Could not query Ultra HDR support (hidden)", e)
                false
            }

            fun bind(
                cappedAnalysis: Boolean,
                maxResCapture: Boolean,
                ultraHdr: Boolean
            ): Camera {
                Log.d("NightPreview", "setupPortraitSession() bind(capped=$cappedAnalysis maxRes=$maxResCapture ultra=$ultraHdr) START")
                return try {
                    // Capture: always try max-res first to keep final image full-res.
                    val captureSelectorBuilder = ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    if (maxResCapture) {
                        captureSelectorBuilder.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    }
                    val captureBuilder = ImageCapture.Builder()
                        .setResolutionSelector(captureSelectorBuilder.build())
                        .setFlashMode(getImageCaptureFlashMode())
                    if (ultraHdr) {
                        captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                    }
                    val capture = captureBuilder.build()
                    imageCapture = capture
                    // Crop stills to the selected aspect ratio (1:1 / 16:9 / 4:3). CameraX crops
                    // OutputFileOptions saves to this and exposes it as cropRect for in-memory shots.
                    capture.setCropAspectRatio(currentCropAspectRatio())

                    val analysisBuilder = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    if (cappedAnalysis) {
                        analysisBuilder.setResolutionSelector(
                            ResolutionSelector.Builder().setResolutionStrategy(
                                ResolutionStrategy(Size(1024, 768), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                            ).build()
                        )
                    } else if (maxResCapture) {
                        analysisBuilder.setResolutionSelector(
                            ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
                        )
                    }
                    val analysis = analysisBuilder.build()
                    imageAnalysis = analysis
                    bindSession(provider, owner, selector, preview, capture, analysis).also {
                        Log.d("NightPreview", "setupPortraitSession() bind SUCCESS capped=$cappedAnalysis maxRes=$maxResCapture ultra=$ultraHdr zoom min=${it.cameraInfo.zoomState.value?.minZoomRatio} max=${it.cameraInfo.zoomState.value?.maxZoomRatio}")
                    }
                } catch (e: Exception) {
                    Log.e("NightPreview", "setupPortraitSession() bind(capped=$cappedAnalysis maxRes=$maxResCapture ultra=$ultraHdr) FAILED – swallowed before! ${e.javaClass.simpleName} ${e.message}", e)
                    throw e
                }
            }

            // Build attempt ladder; keep max-res capture attempts first per user request.
            data class Attempt(val capped: Boolean, val maxRes: Boolean, val ultra: Boolean)
            val attempts = mutableListOf<Attempt>()
            if (ultraHdrSupported) {
                attempts.add(Attempt(true, true, true))
                attempts.add(Attempt(true, true, false))
                attempts.add(Attempt(true, false, true))
                attempts.add(Attempt(true, false, false))
                attempts.add(Attempt(false, false, true))
                attempts.add(Attempt(false, false, false))
            } else {
                attempts.add(Attempt(true, true, false))
                attempts.add(Attempt(true, false, false))
                attempts.add(Attempt(false, false, false))
            }

            var bound: Camera? = null
            var lastError: Exception? = null
            for ((capped, maxRes, ultra) in attempts) {
                try {
                    if (bound != null) {
                        Log.d("NightPreview", "setupPortraitSession() unbindAll before attempt capped=$capped maxRes=$maxRes ultra=$ultra")
                        provider.unbindAll()
                    }
                    bound = bind(capped, maxRes, ultra)
                    Log.d("NightPreview", "setupPortraitSession() bind ladder SUCCESS capped=$capped maxRes=$maxRes ultra=$ultra zoom min=${bound.cameraInfo.zoomState.value?.minZoomRatio} max=${bound.cameraInfo.zoomState.value?.maxZoomRatio}")
                    break
                } catch (e: Exception) {
                    lastError = e
                    Log.e("NightPreview", "setupPortraitSession() Portrait bind failed (capped=$capped maxRes=$maxRes ultra=$ultra) – was Warn with swallowed stack, root of black? ${e.javaClass.simpleName} msg=${e.message}", e)
                    try {
                        provider.unbindAll()
                    } catch (e2: Exception) {
                        Log.e("NightPreview", "setupPortraitSession() unbindAll in catch FAILED (hidden)", e2)
                    }
                }
            }
            if (bound == null) Log.e("NightPreview", "setupPortraitSession() ALL attempts FAILED! lastError=${lastError?.javaClass?.simpleName} ${lastError?.message} – produces black preview?", lastError ?: Exception("none"))
            boundCamera = bound ?: throw (lastError ?: IllegalStateException("Portrait session bind failed"))

            val zsPor = boundCamera?.cameraInfo?.zoomState?.value
            Log.d("NightPreview", "setupPortraitSession() final zoom min=${zsPor?.minZoomRatio} max=${zsPor?.maxZoomRatio} ratio=${zsPor?.zoomRatio} – if max=1, zoom bar will show only 1x")
            boundCamera?.cameraInfo?.zoomState?.value?.let {
                Log.d("NightPreview", "setupPortraitSession() updateZoomLevels min=${it.minZoomRatio} max=${it.maxZoomRatio}")
                updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
                restoreZoom(it.minZoomRatio, it.maxZoomRatio)
            }
            _sloMoSupported.value = true
            readManualControlRanges()
            applyManualControls()
            _photoSessionActive.value = true
            Log.d("NightPreview", "setupPortraitSession() SUCCESS photoActive=true surface=${_surfaceRequest.value?.resolution}")
            true
        } catch (e: Exception) {
            Log.e("NightPreview", "setupPortraitSession() OUTER CATCH – Failed black root? ${e.javaClass.simpleName} ${e.message}", e)
            false
        }
    }

    /** Reads the bound sensor's ISO range → stop list for the manual ISO control. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun readManualControlRanges() {
        Log.d("NightPreview", "readManualControlRanges() called bound=${boundCamera != null} thread=${Thread.currentThread().name}")
        val cam = boundCamera ?: run {
            Log.w("NightPreview", "readManualControlRanges() no bound camera, returning")
            return
        }
        try {
            val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo)
            val isoRange = info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            Log.d("NightPreview", "readManualControlRanges() isoRange=$isoRange")
            _isoStops.value = if (isoRange != null) {
                val filtered = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
                    .filter { it in isoRange.lower..isoRange.upper }
                    .ifEmpty { listOf(isoRange.lower, isoRange.upper) }
                Log.d("NightPreview", "readManualControlRanges() filtered stops=$filtered")
                filtered
            } else {
                Log.w("NightPreview", "readManualControlRanges() isoRange null, emitting emptyList -> ISO bar notAvailable")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("NightPreview", "readManualControlRanges() FAILED (was Warn, hidden) – could affect ISO bar + manual controls", e)
        }
    }

    suspend fun setupVideoSession(): Boolean {
        return try {
            val provider = ProcessCameraProvider.awaitInstance(app)
            cameraProvider = provider
            provider.unbindAll()

            val selector = CameraSelector.Builder()
                .requireLensFacing(_lensFacing.value)
                .build()
            val cameraInfo = provider.getCameraInfo(selector)

            val selectedCodec = _videoCodec.value
            // Respect user setting with fallback: AV1 > HEVC > AVC priority for availability check.
            // If selected codec isn't available, fall back to next best available.
            val useAv1 = selectedCodec == VideoCodec.AV1 &&
                CodecSupport.isHardwareAv1EncoderAvailable && av1SupportedByCamera(cameraInfo)
            val useHevc = !useAv1 && selectedCodec != VideoCodec.AVC &&
                CodecSupport.isHevcEncoderAvailable && hevcSupportedByCamera(cameraInfo)
            val useOpus = false
            // HLG10 (10-bit HDR) when the camera's video pipeline supports it. Uses the default
            // HEVC/H.264 codec (AV1 stays off). Preview and VideoCapture must share the dynamic
            // range or the bind fails, so both are gated together.
            val hlgSupported = hlgSupportedByCamera(cameraInfo)
            Log.d("VideoSession", "Codec selection: av1=$useAv1, hevc=$useHevc, opus=$useOpus, hlg10=$hlgSupported")

            // Cinematic mode enables video stabilization; all video modes always record at max
            // quality/fps (no UI picker).
            val cinematic = _cameraMode.value == CameraMode.CINEMATIC
            val bestFpsRange = highestFpsRange(cameraInfo)
            val stabilizationMode = if (cinematic) preferredStabilizationMode(cameraInfo) else null
            Log.d("VideoSession", "Video tuning: fps=$bestFpsRange, stabilization=$stabilizationMode")

            // Prefer UHD, then FHD, then HD, falling back to the next lower supported quality.
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
            )

            val owner = ManualLifecycleOwner()
            owner.start()
            sessionLifecycleOwner = owner

            fun bind(av1: Boolean, hevc: Boolean, opus: Boolean, hlg: Boolean, snapshot: Boolean): Camera {
                val dynamicRange = if (hlg) androidx.camera.core.DynamicRange.HLG_10_BIT
                    else androidx.camera.core.DynamicRange.SDR
                val previewBuilder = Preview.Builder()
                    .setDynamicRange(dynamicRange)
                applyVideoCaptureRequestOptions(previewBuilder, bestFpsRange, stabilizationMode)
                val preview = previewBuilder.build()
                preview.setSurfaceProvider { request -> _surfaceRequest.value = request }
                val recorderBuilder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .setAudioSource(_audioInputSource.value.specValue)
                if (av1) recorderBuilder.setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_AV1)
                else if (hevc) recorderBuilder.setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                if (opus) recorderBuilder.setAudioMimeType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                val captureBuilder = VideoCapture.Builder(recorderBuilder.build())
                    .setMirrorMode(
                        if (_mirrorFront.value) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY
                        else MirrorMode.MIRROR_MODE_OFF
                    )
                    .setDynamicRange(dynamicRange)
                applyVideoCaptureRequestOptions(captureBuilder, bestFpsRange, stabilizationMode)
                val capture = captureBuilder.build()
                videoCapture = capture
                recordingWithAv1 = av1
                recordingWithHevc = hevc
                return if (snapshot) {
                    // Extra ImageCapture use case enables taking a still while recording (SDR JPEG).
                    val still = ImageCapture.Builder()
                        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                        .build()
                    imageCapture = still
                    bindSession(provider, owner, selector, preview, capture, still)
                } else {
                    imageCapture = null
                    bindSession(provider, owner, selector, preview, capture)
                }
            }

            // Bind ladder: prefer HDR + snapshot, then drop the snapshot use case, then drop HDR.
            val attempts = buildList {
                add(hlgSupported to true)
                add(hlgSupported to false)
                if (hlgSupported) {
                    add(false to true)
                    add(false to false)
                }
            }
            var bound: Camera? = null
            var lastError: Exception? = null
            for ((hlg, snapshot) in attempts) {
                try {
                    provider.unbindAll()
                    bound = bind(useAv1, useHevc, useOpus, hlg, snapshot)
                    break
                } catch (e: Exception) {
                    lastError = e
                    Log.w("VideoSession", "Video bind failed (hlg=$hlg, snapshot=$snapshot); trying next", e)
                }
            }
            boundCamera = bound ?: throw (lastError ?: IllegalStateException("Video session bind failed"))
            _videoSnapshotSupported.value = imageCapture != null

            boundCamera?.cameraInfo?.zoomState?.value?.let {
                updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
                restoreZoom(it.minZoomRatio, it.maxZoomRatio)
            }
            _videoSessionActive.value = true
            true
        } catch (e: Exception) {
            Log.e("VideoSession", "Failed to set up video session", e)
            false
        }
    }

    private fun av1SupportedByCamera(cameraInfo: androidx.camera.core.CameraInfo): Boolean = try {
        val caps = Recorder.getVideoCapabilities(cameraInfo, MediaFormat.MIMETYPE_VIDEO_AV1)
        caps?.getSupportedQualities(androidx.camera.core.DynamicRange.SDR)?.isNotEmpty() == true
    } catch (e: Exception) {
        Log.w("VideoSession", "Could not query AV1 video capabilities", e)
        false
    }

    private fun hevcSupportedByCamera(cameraInfo: androidx.camera.core.CameraInfo): Boolean = try {
        val caps = Recorder.getVideoCapabilities(cameraInfo, MediaFormat.MIMETYPE_VIDEO_HEVC)
        caps?.getSupportedQualities(androidx.camera.core.DynamicRange.SDR)?.isNotEmpty() == true
    } catch (e: Exception) {
        Log.w("VideoSession", "Could not query HEVC video capabilities", e)
        false
    }

    private fun hlgSupportedByCamera(cameraInfo: androidx.camera.core.CameraInfo): Boolean = try {
        Recorder.getVideoCapabilities(cameraInfo)
            .supportedDynamicRanges
            .contains(androidx.camera.core.DynamicRange.HLG_10_BIT)
    } catch (e: Exception) {
        Log.w("VideoSession", "Could not query HLG10 dynamic-range support", e)
        false
    }

    /**
     * Stable fixed fps range for regular video.
     *
     * Previously we picked the absolute highest upper (e.g. 240) from
     * CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, which could be a variable range like
     * [30,60] or an HFR range like [120,120]. When AE varies the fps within a
     * variable range the HAL may switch sensor modes (full vs cropped), which shows
     * as a sudden zoom-in/out flicker. Only Slo-Mo uses the dedicated HFR path, so
     * it wasn't affected.
     *
     * Fix: only allow fixed ranges (lower == upper) and prefer 30fps. If no fixed
     * range exists we return null and let CameraX pick its default, which avoids the
     * crop-switch flicker.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun highestFpsRange(cameraInfo: androidx.camera.core.CameraInfo): android.util.Range<Int>? = try {
        val ranges = androidx.camera.camera2.interop.Camera2CameraInfo.from(cameraInfo)
            .getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            ) ?: return null

        // Only fixed ranges avoid variable-fps sensor-mode switches that cause FOV flicker.
        val fixed = ranges.filter { it.lower == it.upper }
        if (fixed.isNotEmpty()) {
            // Prefer exact 30fps; Cinematic with EIS often can't do fixed 60 uncropped, so
            // stick to 30 for cinematic.
            if (_cameraMode.value == CameraMode.CINEMATIC) {
                fixed.firstOrNull { it.upper == 30 }
                    ?: fixed.filter { it.upper <= 30 }.maxByOrNull { it.upper }
                    ?: fixed.minByOrNull { kotlin.math.abs(it.upper - 30) }
            } else {
                // For normal VIDEO/TIMELAPSE allow 60fps if available, but still fixed.
                fixed.firstOrNull { it.upper == 30 }
                    ?: fixed.firstOrNull { it.upper == 60 }
                    ?: fixed.filter { it.upper <= 30 }.maxByOrNull { it.upper }
                    ?: fixed.filter { it.upper <= 60 }.maxByOrNull { it.upper }
                    ?: fixed.minByOrNull { kotlin.math.abs(it.upper - 30) }
            }
        } else {
            // No fixed ranges: don't force a variable range like [30,60] — return null so
            // CameraX chooses a stable default and avoids the flicker.
            null
        }
    } catch (e: Exception) {
        Log.w("VideoSession", "Could not query supported frame-rate ranges", e)
        null
    }

    /**
     * Preferred video stabilization mode for Cinematic: preview-stabilization ("EIS") when the
     * device lists it, else on-mode, else null (unsupported → stabilization is skipped).
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun preferredStabilizationMode(cameraInfo: androidx.camera.core.CameraInfo): Int? = try {
        val modes = androidx.camera.camera2.interop.Camera2CameraInfo.from(cameraInfo)
            .getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
            )?.toList() ?: emptyList()
        when {
            // Preview stabilization only exists from API 33; the SDK_INT guard keeps a vendor
            // HAL that reports the raw mode value on an older release from being taken at its word.
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                modes.contains(
                    android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                ) -> android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            modes.contains(
                android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            ) -> android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else -> null
        }
    } catch (e: Exception) {
        Log.w("VideoSession", "Could not query video stabilization modes", e)
        null
    }

    /** Applies max-fps + (optional) stabilization capture options onto a Preview/VideoCapture builder. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun <T> applyVideoCaptureRequestOptions(
        builder: androidx.camera.core.ExtendableBuilder<T>,
        fpsRange: android.util.Range<Int>?,
        stabilizationMode: Int?
    ) {
        try {
            val extender = androidx.camera.camera2.interop.Camera2Interop.Extender(builder)
            if (fpsRange != null) {
                extender.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange
                )
            }
            if (stabilizationMode != null) {
                extender.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    stabilizationMode
                )
            }
        } catch (e: Exception) {
            Log.w("VideoSession", "Could not apply Camera2 video capture options", e)
        }
    }

    /** Tears down whatever session is currently bound and clears the shared preview surface. */
    fun teardownSession() {
        Log.d("NightPreview", "teardownSession() START thread=${Thread.currentThread().name} surface=${_surfaceRequest.value?.resolution} photoActive=${_photoSessionActive.value} nightPreviewActive=${_nightPreviewActive.value} highSpeedActive=${_highSpeedActive.value} videoActive=${_videoSessionActive.value} boundCamera=${boundCamera != null} provider=${cameraProvider != null}")
        stopObservingNightModeIndicator()
        stopObservingExtensionStrength()
        stopObservingExtensionCameraState()
        // CRITICAL: clear stale SurfaceRequest BEFORE unbind so CameraXViewfinder drops dead texture immediately (black-frame trap fix)
        _surfaceRequest.value = null
        Log.d("NightPreview", "teardownSession() cleared surfaceRequest -> null to avoid black-frame trap")
        currentRecording?.stop()
        currentRecording = null
        highSpeedRecording?.stop()
        highSpeedRecording = null
        try {
            imageAnalysis?.clearAnalyzer()
            Log.d("NightPreview", "teardownSession() cleared analyzer previous=${currentAnalyzer?.javaClass?.simpleName}")
        } catch (e: Exception) {
            Log.e("NightPreview", "teardownSession() clearAnalyzer failed (swallowed before)", e)
        }
        currentAnalyzer = null
        sessionLifecycleOwner?.destroy()
        sessionLifecycleOwner = null
        try {
            cameraProvider?.unbindAll()
            Log.d("NightPreview", "teardownSession() unbindAll SUCCESS")
        } catch (e: Exception) {
            Log.e("NightPreview", "teardownSession() unbindAll FAILED (was hidden)", e)
        }
        boundCamera = null
        imageCapture = null
        imageAnalysis = null
        videoCapture = null
        highSpeedVideoCapture = null
        cameraProvider = null
        _photoSessionActive.value = false
        _nightPreviewActive.value = false
        _highSpeedActive.value = false
        _videoSessionActive.value = false
        _videoSnapshotSupported.value = false
        _focusLocked.value = false
        // Note: night-mode detection is intentionally NOT reset here. Teardown
        // runs on every night<->normal preview rebind, and resetting would clear
        // nightModeActive mid-swap and thrash the session. Explicit resets live in
        // switchCameraMode / flipCamera instead.
        resetManualControls()
        clearMotionFrames()
    }

    // --- Unified manual session control wiring (targets the single bound camera) ---

    fun startFocusAndMetering(action: FocusMeteringAction) {
        // A fresh tap clears any existing AE/AF lock.
        _focusLocked.value = false
        boundCamera?.cameraControl?.startFocusAndMetering(action)
    }

    /**
     * Locks focus + exposure at the metered point by disabling auto-cancel, so 3A stays put until
     * the user taps again (long-press-to-lock). Sets [focusLocked] for the on-screen indicator.
     */
    fun lockFocusAndMetering(action: FocusMeteringAction) {
        val cam = boundCamera ?: return
        cam.cameraControl.startFocusAndMetering(action)
        _focusLocked.value = true
    }

    fun clearFocusLock() {
        boundCamera?.cameraControl?.cancelFocusAndMetering()
        _focusLocked.value = false
    }

    fun enableTorch(enabled: Boolean) {
        boundCamera?.cameraControl?.enableTorch(enabled)
    }

    fun applyExposureCompensation(value: Float) {
        val cam = boundCamera ?: return
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        val index = (value * range.upper).toInt().coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(index)
    }

    /** Pushes the current flash mode onto the bound ImageCapture (runtime-mutable, no rebind). */
    fun applyImageCaptureFlashMode() {
        imageCapture?.flashMode = getImageCaptureFlashMode()
    }

    /** Swaps the analyzer on the bound ImageAnalysis without rebinding. */
    fun setImageAnalyzer(analyzer: ImageAnalysis.Analyzer?) {
        val analysis = imageAnalysis ?: return
        currentAnalyzer = analyzer
        if (analyzer == null) analysis.clearAnalyzer()
        else analysis.setAnalyzer(ContextCompat.getMainExecutor(app), analyzer)
    }

    fun setImageAnalyzer(analyzer: ImageAnalysis.Analyzer?, executor: Executor) {
        val analysis = imageAnalysis ?: return
        currentAnalyzer = analyzer
        if (analyzer == null) analysis.clearAnalyzer()
        else analysis.setAnalyzer(executor, analyzer)
    }

    /** Convenience for portrait bokeh – always runs off the dedicated bokeh thread. */
    fun setBokehAnalyzer(analyzer: ImageAnalysis.Analyzer?) {
        setImageAnalyzer(analyzer, bokehExecutor)
    }

    private fun startRecordingTimer() {
        _isRecording.value = true
        _recordingPaused.value = false
        _recordingDurationSec.value = 0
        recordingTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                if (!_recordingPaused.value) _recordingDurationSec.value += 1
            }
        }
    }

    private fun stopRecordingTimer() {
        _isRecording.value = false
        _recordingPaused.value = false
        recordingTimerJob?.cancel()
        _recordingDurationSec.value = 0
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun toggleHighSpeedRecording() {
        // Cancel countdown if active
        if (_timerCountdown.value > 0) {
            timerCountdownJob?.cancel()
            timerCountdownJob = null
            _timerCountdown.value = 0
            return
        }

        if (_isRecording.value) {
            highSpeedRecording?.stop()
            highSpeedRecording = null
            stopRecordingTimer()
            return
        }

        val timer = _timerDuration.value
        if (timer.seconds > 0) {
            // Start countdown before recording
            timerCountdownJob = viewModelScope.launch {
                for (i in timer.seconds downTo 1) {
                    _timerCountdown.value = i
                    delay(1000)
                }
                _timerCountdown.value = 0
                timerCountdownJob = null
                startHighSpeedRecording()
            }
            return
        }

        startHighSpeedRecording()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startHighSpeedRecording() {
        val videoCapture = highSpeedVideoCapture ?: return

        val contentValues = MediaStoreSaver.videoValues("SLOMO_${MediaStoreSaver.timestamp()}")

        val outputOptions = MediaStoreOutputOptions.Builder(
            app.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        startRecordingTimer()

        Log.d("SloMo", "Starting high-speed recording at ${sloMoFps}fps")

        highSpeedRecording = videoCapture.output
            .prepareRecording(app, outputOptions)
            .start(ContextCompat.getMainExecutor(app)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    stopRecordingTimer()
                    if (event.hasError()) {
                        Log.e("SloMo", "Recording error: ${event.error} - ${event.cause?.message}")
                    } else {
                        Log.d("SloMo", "High-speed recording saved: ${event.outputResults.outputUri}")
                        setLastCaptureUri(event.outputResults.outputUri)
                    }
                }
            }
    }

    // --- Standard recording ---

    fun takePhoto() {
        val timer = _timerDuration.value
        if (timer.seconds > 0) {
            viewModelScope.launch {
                for (i in timer.seconds downTo 1) {
                    _timerCountdown.value = i
                    delay(1000)
                }
                _timerCountdown.value = 0
                capturePhoto()
            }
        } else {
            capturePhoto()
        }
    }

    private fun capturePhoto() {
        if (imageCapture == null) return
        when {
            // Night Sight mode (or auto-engaged night): the preview is bound with the vendor NIGHT
            // extension, so a plain single capture through that ImageCapture lets the vendor pipeline
            // produce the multi-frame night image.
            _nightPreviewActive.value -> captureSinglePhoto()
            // Multi-frame night capture only when night mode is active and exposure is fully auto.
            nightModeActive.value && isExposureAuto() -> captureNightPhoto()
            // Motion Photo for plain PHOTO captures (no warmth/shadows bake, not capturing for a
            // caller). Only at the native 4:3 ratio: the motion still is saved as raw JPEG bytes
            // (to preserve the Ultra HDR gain map + motion trailer), which can't carry CameraX's
            // crop. For 1:1/16:9 fall through to the single-shot path, which saves a cropped JPEG.
            _cameraMode.value == CameraMode.PHOTO && !captureForResult &&
                _warmth.value == 0f && _shadows.value == 0f &&
                _aspectRatio.value == AspectRatioOption.RATIO_4_3 -> captureMotionPhoto()
            else -> captureSinglePhoto()
        }
    }

    /**
     * Starts a press-and-hold burst: standard single-frame captures fired back-to-back (one in
     * flight at a time) with `IMG_<ts>_BURSTn` names, until [stopBurst] or [BURST_MAX] is reached.
     * Uses the plain capture path (no night/manual special-casing).
     */
    fun startBurst() {
        if (_burstActive.value) return
        val capture = imageCapture ?: return
        _burstActive.value = true
        _burstCount.value = 0
        val ts = MediaStoreSaver.timestamp()

        fun shootNext(n: Int) {
            if (!_burstActive.value || n > BURST_MAX) {
                _burstActive.value = false
                return
            }
            val values = MediaStoreSaver.imageValues("IMG_${ts}_BURST${n}.jpg")
            val metadata = ImageCapture.Metadata().apply {
                if (_locationEnabled.value) location = lastLocation
                isReversedHorizontal = mirrorCaptures
            }
            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                app.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ).setMetadata(metadata).build()

            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(app),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        _burstCount.value = n
                        outputFileResults.savedUri?.let { setLastCaptureUri(it) }
                        shootNext(n + 1)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraViewModel", "Burst frame $n failed", exception)
                        shootNext(n + 1)
                    }
                }
            )
        }
        shootNext(1)
    }

    fun stopBurst() {
        _burstActive.value = false
    }

    /** Appends an analysis frame to the Motion-Photo ring buffer, trimming by age then count. */
    fun addMotionFrame(bitmap: Bitmap, timestampNanos: Long, rotationDegrees: Int) {
        synchronized(motionLock) {
            motionFrames.addLast(MotionFrame(bitmap, timestampNanos, rotationDegrees))
            val cutoff = timestampNanos - MOTION_WINDOW_NANOS
            while (motionFrames.size > 1 && motionFrames.first().timestampNanos < cutoff) {
                motionFrames.removeFirst().bitmap.recycle()
            }
            while (motionFrames.size > MOTION_MAX_FRAMES) {
                motionFrames.removeFirst().bitmap.recycle()
            }
        }
    }

    private fun drainMotionFrames(): List<MotionFrame> = synchronized(motionLock) {
        val list = motionFrames.toList()
        motionFrames.clear()
        list
    }

    private fun clearMotionFrames() = synchronized(motionLock) {
        motionFrames.forEach { it.bitmap.recycle() }
        motionFrames.clear()
    }

    /**
     * Captures a Motion Photo: the still (captured in-memory so its bytes can carry the trailer) plus
     * the buffered ring-buffer frames encoded to a short MP4 and appended as a Google Motion Photo
     * trailer. Falls back to saving the plain still if no frames are buffered or encoding fails.
     * The still may be Ultra HDR (gain map preserved); the appended clip is SDR at analysis resolution.
     */
    private fun captureMotionPhoto() {
        val capture = imageCapture ?: return
        _isCapturing.value = true
        capture.takePicture(
            ContextCompat.getMainExecutor(app),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val degrees = image.imageInfo.rotationDegrees
                    val jpegBytes = try {
                        image.planes[0].buffer.let { buf ->
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        }
                    } finally {
                        image.close()
                    }
                    val frames = drainMotionFrames()
                    viewModelScope.launch {
                        val uri = withContext(Dispatchers.Default) {
                            assembleAndSaveMotionPhoto(jpegBytes, frames, degrees)
                        }
                        frames.forEach { it.bitmap.recycle() }
                        _isCapturing.value = false
                        if (uri != null) setLastCaptureUri(uri)
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraViewModel", "Motion Photo capture failed; falling back to still", exception)
                    _isCapturing.value = false
                    captureSinglePhoto()
                }
            }
        )
    }

    private fun assembleAndSaveMotionPhoto(
        jpegBytes: ByteArray,
        frames: List<MotionFrame>,
        degrees: Int
    ): Uri? {
        val values = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")
        // Only frames matching the newest frame's dimensions are encoded (a rebind can change size).
        val sized = frames.takeIf { it.isNotEmpty() }?.let { list ->
            val w = list.last().bitmap.width
            val h = list.last().bitmap.height
            list.filter { it.bitmap.width == w && it.bitmap.height == h }
        }.orEmpty()

        val bytes = if (sized.size >= 2) {
            val tmp = java.io.File(app.cacheDir, "motion_${System.currentTimeMillis()}.mp4")
            val spanNs = (sized.last().timestampNanos - sized.first().timestampNanos).coerceAtLeast(1)
            val fps = (sized.size * 1_000_000_000.0 / spanNs).roundToInt().coerceIn(5, 30)
            val ok = MotionPhotoEncoder.encode(
                sized.map { it.bitmap }, tmp, sized.last().rotationDegrees, fps
            )
            if (ok && tmp.exists()) {
                val mp4 = tmp.readBytes()
                tmp.delete()
                MotionPhotoWriter.assemble(jpegBytes, mp4)
            } else {
                tmp.delete()
                jpegBytes
            }
        } else {
            jpegBytes
        }
        return MediaStoreSaver.saveJpegBytes(app.contentResolver, values, bytes)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun captureSinglePhoto() {
        val capture = imageCapture ?: return
        _isCapturing.value = true
        val contentValues = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")

        val metadata = ImageCapture.Metadata().apply {
            if (_locationEnabled.value) location = lastLocation
            isReversedHorizontal = mirrorCaptures
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            app.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).setMetadata(metadata).build()

        val stop = EXPOSURE_TIME_STOPS[_exposureTimeIndex.value]
        // Manual shutter/ISO are already applied live via applyManualControls(); the only transient
        // per-capture override here is the night-mode emulation (fully-auto exposure + night active).
        // Skip it when the vendor NIGHT extension preview is bound: that session runs its own
        // multi-frame AE and rejects Camera2-interop AE_MODE_OFF/manual-exposure options, which makes
        // the capture fail. In that case a plain takePicture() lets the extension produce the shot.
        val nightExposure = if (nightModeActive.value && isExposureAuto() && !_nightPreviewActive.value)
            computeNightExposure() else null
        // Used only to drive the long-exposure countdown overlay.
        val exposureNanos = stop.nanos ?: nightExposure?.nanos
        val nightIso = nightExposure?.iso
        val cam2Control = try {
            boundCamera?.cameraControl?.let {
                androidx.camera.camera2.interop.Camera2CameraControl.from(it)
            }
        } catch (e: Exception) { Log.w("CameraViewModel", "Camera2 control unavailable", e); null }

        fun restoreAfterNight() {
            // Undo the transient night override by re-asserting the (auto) manual-control state.
            if (nightExposure != null) applyManualControls()
        }

        fun doCapture() {
            if (exposureNanos != null && exposureNanos >= 250_000_000L) {
                startLongExposureCountdown(exposureNanos)
            }
            fun finishCapture(uri: Uri?) {
                _isCapturing.value = false
                stopLongExposureCountdown()
                restoreAfterNight()
                if (uri != null) setLastCaptureUri(uri)
            }

            val warmth = _warmth.value
            val shadows = _shadows.value
            val mirror = mirrorCaptures
            val bokeh = _cameraMode.value == CameraMode.PORTRAIT
            val strength = _blurStrength.value
            if (bokeh || warmth != 0f || shadows != 0f) {
                // The warmth/shadows adjustment and the portrait bokeh only live in the preview
                // RenderEffect, so bake them into the pixels here: capture in-memory, re-run the
                // same shader/color matrix over the full-resolution frame, then re-encode.
                // Re-encoding drops the JPEG's EXIF, so we copy it back from the original frame
                // (plus GPS and the orientation tag) to match the normal path.
                // Caveat: this processed path is always SDR JPEG — decoding to an ARGB_8888 bitmap
                // discards any Ultra HDR gain map, so processed captures lose HDR even when
                // Ultra HDR is otherwise active. Normal (unprocessed) captures keep the gain map.
                capture.takePicture(
                    ContextCompat.getMainExecutor(app),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val degrees = image.imageInfo.rotationDegrees
                            // cropRect reflects setCropAspectRatio; apply it to the decoded bitmap
                            // since the raw JPEG buffer is always full-frame for in-memory captures.
                            val cropRect = Rect(image.cropRect)
                            val sourceJpeg = try {
                                image.planes[0].buffer.let { buf ->
                                    ByteArray(buf.remaining()).also { buf.get(it) }
                                }
                            } finally {
                                image.close()
                            }
                            viewModelScope.launch {
                                val uri = withContext(Dispatchers.IO) {
                                    val decoded = cropToRect(
                                        BitmapFactory.decodeByteArray(sourceJpeg, 0, sourceJpeg.size),
                                        cropRect
                                    )
                                    // The bokeh renderer folds the colour matrix and the mirror in
                                    // as it composites; it leaves `decoded` alone if it can't run,
                                    // so fall back to the plain colour pass.
                                    val adjusted = (if (bokeh) {
                                        stillBokeh.render(decoded, degrees, strength, warmth, shadows, mirror)
                                    } else null)
                                        ?: applyColorAdjustments(decoded, warmth, shadows, mirror)
                                    val values = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")
                                    MediaStoreSaver.saveBitmap(app.contentResolver, values, adjusted)
                                        ?.also { writeCaptureExif(it, sourceJpeg, degrees, mirrored = mirror) }
                                        .also { adjusted.recycle() }
                                }
                                finishCapture(uri)
                            }
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Log.e("CameraViewModel", "Adjusted capture failed", exception)
                            finishCapture(null)
                        }
                    }
                )
                return
            }

            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(app),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        finishCapture(outputFileResults.savedUri)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        finishCapture(null)
                    }
                }
            )
        }

        if (nightExposure != null && cam2Control != null) {
            try {
                val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                        android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                        nightExposure.nanos
                    )
                if (nightIso != null) {
                    options.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY,
                        nightIso
                    )
                }
                cam2Control.setCaptureRequestOptions(options.build())
                    .addListener({ doCapture() }, ContextCompat.getMainExecutor(app))
            } catch (e: Exception) {
                Log.w("CameraViewModel", "Failed to set night exposure", e)
                doCapture()
            }
        } else {
            doCapture()
        }
    }

    /**
     * Routes the night shutter: prefer CameraX Extensions NIGHT (vendor multi-frame processing)
     * when it's usable on this device, otherwise fall back to the custom burst + Rust merge. Uses
     * nightExtensionUsable (the gated/failure-cached value) so a device where the extension can't
     * bind (GrapheneOS/Pixel) goes straight to the working custom path instead of failing.
     */
    private fun captureNightPhoto() {
        viewModelScope.launch {
            if (_nightExtensionUsable.value) {
                captureNightPhotoExtension()
            } else {
                captureNightPhotoCustom()
            }
        }
    }

    /** Obtains (and caches) the ExtensionsManager bound to [provider]. Null if unavailable. */
    private suspend fun getExtensionsManager(provider: ProcessCameraProvider): ExtensionsManager? {
        Log.d("NightPreview", "getExtensionsManager() called providerHash=${provider.hashCode()} cachedExists=${extensionsManager != null} thread=${Thread.currentThread().name} startMs=${System.currentTimeMillis()}")
        extensionsManager?.let {
            Log.d("NightPreview", "getExtensionsManager() returning CACHED manager providerHash=${provider.hashCode()} manager=$it – NOTE: cached across provider instances, may be tied to old provider if provider changed!")
            return it
        }
        return try {
            val start = System.currentTimeMillis()
            val mgr = suspendCancellableCoroutine<ExtensionsManager> { cont ->
                Log.d("NightPreview", "getExtensionsManager() creating async instance for providerHash=${provider.hashCode()}")
                val future = ExtensionsManager.getInstanceAsync(app, provider)
                future.addListener({
                    try {
                        val res = future.get()
                        Log.d("NightPreview", "getExtensionsManager() future.get() SUCCESS res=$res elapsed=${System.currentTimeMillis() - start}ms")
                        cont.resume(res)
                    } catch (e: Exception) {
                        Log.e("NightPreview", "getExtensionsManager() future.get() FAILED elapsed=${System.currentTimeMillis() - start}ms – this was previously swallowed as Warn", e)
                        cont.cancel(e)
                    }
                }, ContextCompat.getMainExecutor(app))
            }
            Log.d("NightPreview", "getExtensionsManager() obtained mgr=$mgr caching for providerHash=${provider.hashCode()} elapsed=${System.currentTimeMillis() - start}ms")
            extensionsManager = mgr
            mgr
        } catch (e: Exception) {
            Log.e("NightPreview", "getExtensionsManager() EXCEPTION – ExtensionsManager unavailable (was hidden as Warn), root cause of black preview if NIGHT needed", e)
            null
        }
    }

    /** Whether the CameraX NIGHT extension is available on the current lens. */
    suspend fun isNightExtensionAvailable(): Boolean {
        val startMs = System.currentTimeMillis()
        Log.d("NightPreview", "isNightExtensionAvailable() START lensFacing=${_lensFacing.value} thread=${Thread.currentThread().name}")
        return try {
            val provider = ProcessCameraProvider.awaitInstance(app)
            Log.d("NightPreview", "isNightExtensionAvailable() got providerHash=${provider.hashCode()} elapsed=${System.currentTimeMillis() - startMs}ms")
            val mgr = getExtensionsManager(provider)
            Log.d("NightPreview", "isNightExtensionAvailable() ExtensionsManager=${mgr != null} elapsed=${System.currentTimeMillis() - startMs}ms")
            if (mgr == null) {
                Log.w("NightPreview", "isNightExtensionAvailable() manager NULL after ${System.currentTimeMillis() - startMs}ms, returning false -> moon button may show but useNightPreview false, so no visible transition")
                return false
            }
            val selector = CameraSelector.Builder()
                .requireLensFacing(_lensFacing.value)
                .build()
            if (!mgr.isExtensionAvailable(selector, ExtensionMode.NIGHT)) {
                Log.d("NightPreview", "isNightExtensionAvailable() isExtensionAvailable(NIGHT)=false lens=${_lensFacing.value}")
                return false
            }
            // Optimistic support query. Neither this nor a getCameraInfo probe reliably predicts the
            // GrapheneOS/Pixel extender failure (it only surfaces at actual bind time), so we accept a
            // yes here and rely on the daily failure cache (recordNightExtensionFailure) to stop
            // offering night after the first real bind failure.
            val cameraInfo = provider.getCameraInfo(selector)
            val nightConfig = ExtensionSessionConfig.Builder(ExtensionMode.NIGHT, mgr)
                .addUseCase(Preview.Builder().build())
                .addUseCase(ImageCapture.Builder().build())
                .build()
            val supported = try {
                cameraInfo.isSessionConfigSupported(nightConfig)
            } catch (e: Exception) {
                Log.w("NightPreview", "isNightExtensionAvailable() isSessionConfigSupported threw", e)
                false
            }
            Log.d("NightPreview", "isNightExtensionAvailable() isSessionConfigSupported(NIGHT)=$supported lens=${_lensFacing.value} total=${System.currentTimeMillis() - startMs}ms")
            supported
        } catch (e: Exception) {
            Log.e("NightPreview", "isNightExtensionAvailable() OUTER EXCEPTION – returning false, totalTook=${System.currentTimeMillis() - startMs}ms", e)
            false
        }
    }

    /**
     * On-demand NIGHT-extension capture: briefly rebinds a Preview + ImageCapture session with the
     * extension-enabled selector, takes one standard shot (the vendor handles the multi-frame night
     * merge internally), saves it, then restores the normal analysis-backed photo session so the
     * moon button keeps tracking brightness. The extension does its own timing, so no manual
     * long-exposure countdown here.
     */
    private suspend fun captureNightPhotoExtension() {
        _isCapturing.value = true
        // Drop the photo session so the UI's analyzer effect re-attaches PhotoAnalyzer once we restore.
        _photoSessionActive.value = false
        try {
            val provider = ProcessCameraProvider.awaitInstance(app)
            cameraProvider = provider
            val mgr = getExtensionsManager(provider)
            if (mgr == null) {
                // Unreachable in practice: entry is gated by isNightExtensionAvailable(), which
                // already resolved the manager. Bail out; finally restores the normal session.
                Log.w("CameraViewModel", "ExtensionsManager missing at night capture; skipping")
                return
            }
            provider.unbindAll()
            imageAnalysis = null
            currentAnalyzer = null

            val baseSelector = CameraSelector.Builder()
                .requireLensFacing(_lensFacing.value)
                .build()
            val nightSelector = mgr.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.NIGHT)

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider { request -> _surfaceRequest.value = request }

            val owner = ManualLifecycleOwner()
            owner.start()
            sessionLifecycleOwner = owner

            val capture = ImageCapture.Builder()
                .setFlashMode(getImageCaptureFlashMode())
                .build()
            imageCapture = capture
            boundCamera = bindSession(provider, owner, nightSelector, preview, capture)

            val contentValues = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")
            val metadata = ImageCapture.Metadata().apply {
                if (_locationEnabled.value) location = lastLocation
                isReversedHorizontal = mirrorCaptures
            }
            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                app.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).setMetadata(metadata).build()

            val savedUri = suspendCancellableCoroutine<Uri?> { cont ->
                capture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(app),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            cont.resume(outputFileResults.savedUri)
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Log.e("CameraViewModel", "Night extension capture failed", exception)
                            cont.resume(null)
                        }
                    }
                )
            }
            if (savedUri != null) setLastCaptureUri(savedUri)
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Night extension capture path failed", e)
        } finally {
            // Rebind the normal 3-stream session; sets _photoSessionActive=true so the UI re-attaches
            // PhotoAnalyzer. If teardown interrupted us, this is superseded by the lifecycle rebind.
            setupPhotoSession()
            _isCapturing.value = false
        }
    }

    /**
     * Multi-frame night capture: locks the sensor to a per-frame night exposure/ISO, collects a
     * burst off the ImageAnalysis stream, then aligns + merges + brightens it (via
     * [NightCaptureEngine]) and saves. Falls back to the single long-exposure capture if the burst
     * is empty or the merge fails, so the user always gets a shot.
     */
    private fun captureNightPhotoCustom() {
        _isCapturing.value = true
        val perFrame = computeNightExposure(NIGHT_BURST_PER_FRAME_NANOS)
        // The countdown overlay shows the total burst duration.
        startLongExposureCountdown(perFrame.nanos * NightCaptureEngine.NIGHT_BURST_COUNT)

        captureNightBurst(perFrame) { frames ->
            if (frames.isEmpty()) {
                Log.w("CameraViewModel", "Night burst produced no frames; falling back to single capture")
                stopLongExposureCountdown()
                captureSinglePhoto()
                return@captureNightBurst
            }
            viewModelScope.launch {
                val uri = withContext(Dispatchers.Default) {
                    val merged = NightCaptureEngine.merge(frames)
                    frames.forEach { it.recycle() }
                    merged?.let { bmp ->
                        val values = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")
                        MediaStoreSaver.saveBitmap(app.contentResolver, values, bmp)
                            .also { bmp.recycle() }
                    }
                }
                if (uri != null) {
                    // Merged pixels are already upright/mirrored, so the orientation tag is normal.
                    withContext(Dispatchers.IO) { writeCaptureExif(uri, null, 0) }
                    _isCapturing.value = false
                    stopLongExposureCountdown()
                    setLastCaptureUri(uri)
                } else {
                    Log.w("CameraViewModel", "Night merge failed; falling back to single capture")
                    stopLongExposureCountdown()
                    captureSinglePhoto()
                }
            }
        }
    }

    /**
     * Proper night burst: full-res capture via [ImageCapture] instead of low-res
     * [ImageAnalysis]. Locks 3A to [exposure], then fires [NightCaptureEngine.NIGHT_BURST_COUNT]
     * full-resolution in-memory captures, each converted to upright/consistent bitmap.
     * This avoids the previous path's analysis-resolution limitation and double JPEG
     * loss (now passed lossless RGBA to Rust via [StitchNative.newNightSession]).
     *
     * Falls back to empty list if [imageCapture] is unavailable, which makes
     * [captureNightPhotoCustom] fall back to single-frame long-exposure.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun captureNightBurst(exposure: NightExposure, onDone: (List<Bitmap>) -> Unit) {
        val capture = imageCapture ?: run {
            onDone(emptyList())
            return
        }
        val cam2Control = try {
            boundCamera?.cameraControl?.let {
                androidx.camera.camera2.interop.Camera2CameraControl.from(it)
            }
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Camera2 control unavailable", e)
            null
        }
        val mirror = mirrorCaptures
        val collected = mutableListOf<Bitmap>()
        var alreadyDone = false

        fun restore3A() {
            if (cam2Control != null) {
                try {
                    cam2Control.setCaptureRequestOptions(
                        androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                            .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME)
                            .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY)
                            .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE)
                            .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK)
                            .build()
                    )
                } catch (e: Exception) {
                    Log.w("CameraViewModel", "Failed to restore auto 3A after night burst", e)
                }
            }
        }

        fun finish() {
            if (alreadyDone) return
            alreadyDone = true
            restore3A()
            // Re-assert (auto) manual-control state to fully undo night override.
            try {
                applyManualControls()
            } catch (_: Exception) {}
            onDone(collected.toList())
        }

        fun takeNext() {
            if (collected.size >= NightCaptureEngine.NIGHT_BURST_COUNT) {
                finish()
                return
            }
            val cap = imageCapture ?: run {
                finish()
                return
            }
            cap.takePicture(
                ContextCompat.getMainExecutor(app),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            // toBitmap() is provided by CameraX (used also in BokehAnalyzer)
                            val raw = image.toBitmap()
                            val matrix = Matrix().apply {
                                postRotate(image.imageInfo.rotationDegrees.toFloat())
                                if (mirror) postScale(-1f, 1f)
                            }
                            val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                            if (upright !== raw) raw.recycle()
                            collected.add(upright)
                        } catch (e: Exception) {
                            Log.w("CameraViewModel", "Failed to convert night frame", e)
                        } finally {
                            image.close()
                        }
                        takeNext()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.w("CameraViewModel", "Night frame capture failed, continuing", exception)
                        takeNext()
                    }
                }
            )
        }

        // Lock AE off + set per-frame night exposure/ISO + lock AWB to avoid color drift, then start burst.
        if (cam2Control != null) {
            try {
                val optsBuilder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                        android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                        exposure.nanos
                    )
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK, true
                    )
                exposure.iso?.let {
                    optsBuilder.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, it
                    )
                }
                cam2Control.setCaptureRequestOptions(optsBuilder.build())
                    .addListener({ takeNext() }, ContextCompat.getMainExecutor(app))
            } catch (e: Exception) {
                Log.w("CameraViewModel", "Failed to set night exposure for burst", e)
                takeNext()
            }
        } else {
            takeNext()
        }
    }

    private data class NightExposure(val nanos: Long, val iso: Int?)

    /**
     * Derives a night exposure/ISO from the bound sensor's characteristics: clamps [targetNanos]
     * into the sensor's exposure-time range and picks a high fraction of its sensitivity range.
     * Falls back to [targetNanos] (and auto ISO) if the characteristics are unavailable.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun computeNightExposure(targetNanos: Long = NIGHT_TARGET_EXPOSURE_NANOS): NightExposure {
        val fallback = NightExposure(targetNanos, null)
        return try {
            val cam = boundCamera ?: return fallback
            val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo)
            val expRange = info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )
            val isoRange = info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            val nanos = expRange?.let {
                targetNanos.coerceIn(it.lower, it.upper)
            } ?: targetNanos
            val iso = isoRange?.let {
                (it.lower + ((it.upper - it.lower) * NIGHT_ISO_FRACTION).roundToInt())
                    .coerceIn(it.lower, it.upper)
            }
            NightExposure(nanos, iso)
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to read sensor ranges for night mode", e)
            fallback
        }
    }

    /**
     * Capture path for the system IMAGE_CAPTURE intent. If the caller supplied an EXTRA_OUTPUT
     * Uri, the full-resolution JPEG is written there and [onSaved] is invoked with a null
     * thumbnail (the documented "no data extra needed" contract). Otherwise a downscaled
     * thumbnail Bitmap is returned for the result "data" extra.
     */
    fun capturePhotoForResult(onSaved: (Bitmap?) -> Unit, onError: () -> Unit) {
        val capture = imageCapture ?: return onError()
        _isCapturing.value = true
        val executor = ContextCompat.getMainExecutor(app)
        val outputUri = resultOutputUri

        // Portrait bokeh / warmth / shadows only exist in the preview, so a shot taken in those
        // modes has to be re-processed before it goes back to the caller, exactly as
        // captureSinglePhoto() does for the gallery.
        if (_cameraMode.value == CameraMode.PORTRAIT || _warmth.value != 0f || _shadows.value != 0f) {
            capturePhotoForResultProcessed(capture, outputUri, onSaved, onError)
            return
        }

        if (outputUri != null) {
            val outputStream = try {
                app.contentResolver.openOutputStream(outputUri)
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Could not open EXTRA_OUTPUT for writing", e)
                null
            }
            if (outputStream == null) {
                _isCapturing.value = false
                return onError()
            }
            val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = mirrorCaptures }
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputStream)
                .setMetadata(metadata)
                .build()
            capture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    _isCapturing.value = false
                    onSaved(null)
                }
                override fun onError(exception: ImageCaptureException) {
                    _isCapturing.value = false
                    Log.e("CameraViewModel", "IMAGE_CAPTURE to EXTRA_OUTPUT failed", exception)
                    onError()
                }
            })
        } else {
            capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    _isCapturing.value = false
                    val mirror = mirrorCaptures
                    val thumbnail = try {
                        downscaledThumbnail(image, mirror)
                    } finally {
                        image.close()
                    }
                    onSaved(thumbnail)
                }
                override fun onError(exception: ImageCaptureException) {
                    _isCapturing.value = false
                    Log.e("CameraViewModel", "IMAGE_CAPTURE thumbnail capture failed", exception)
                    onError()
                }
            })
        }
    }

    /**
     * [capturePhotoForResult] for a shot that needs the preview's portrait bokeh / colour
     * adjustments baked in: capture in-memory, re-run them over the full-resolution frame, then
     * either re-encode into the caller's EXTRA_OUTPUT stream or hand back the "data" thumbnail.
     * Shares captureSinglePhoto()'s Ultra HDR caveat — the decode to ARGB_8888 drops the gain map.
     */
    private fun capturePhotoForResultProcessed(
        capture: ImageCapture,
        outputUri: Uri?,
        onSaved: (Bitmap?) -> Unit,
        onError: () -> Unit,
    ) {
        val warmth = _warmth.value
        val shadows = _shadows.value
        val mirror = mirrorCaptures
        val bokeh = _cameraMode.value == CameraMode.PORTRAIT
        val strength = _blurStrength.value

        capture.takePicture(
            ContextCompat.getMainExecutor(app),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val degrees = image.imageInfo.rotationDegrees
                    val cropRect = Rect(image.cropRect)
                    val sourceJpeg = try {
                        image.planes[0].buffer.let { buf ->
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        }
                    } finally {
                        image.close()
                    }
                    viewModelScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val decoded = cropToRect(
                                    BitmapFactory.decodeByteArray(sourceJpeg, 0, sourceJpeg.size),
                                    cropRect
                                )
                                val adjusted = (if (bokeh) {
                                    stillBokeh.render(decoded, degrees, strength, warmth, shadows, mirror)
                                } else null)
                                    ?: applyColorAdjustments(decoded, warmth, shadows, mirror)
                                // The gallery path leaves rotation to the EXIF tag, but a caller's
                                // Uri may not open "rw" for writeCaptureExif, so bake it into the
                                // pixels here and tag the result upright.
                                val upright = uprightCopy(adjusted, degrees, if (outputUri == null) 512 else 0)
                                adjusted.recycle()
                                if (outputUri == null) {
                                    upright
                                } else {
                                    val wrote = try {
                                        app.contentResolver.openOutputStream(outputUri)?.use { out ->
                                            upright.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                        } ?: false
                                    } finally {
                                        upright.recycle()
                                    }
                                    if (!wrote) error("Could not write to EXTRA_OUTPUT")
                                    writeCaptureExif(outputUri, sourceJpeg, 0)
                                    null
                                }
                            }
                        }
                        _isCapturing.value = false
                        result.fold(
                            onSuccess = { onSaved(it) },
                            onFailure = {
                                Log.e("CameraViewModel", "Processed IMAGE_CAPTURE failed", it)
                                onError()
                            }
                        )
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    _isCapturing.value = false
                    Log.e("CameraViewModel", "Processed IMAGE_CAPTURE capture failed", exception)
                    onError()
                }
            }
        )
    }

    /**
     * Rotates an already-processed capture's pixels upright, optionally scaling its long side down
     * to [maxSide] (0 = keep full resolution) for the result "data" thumbnail. Unlike
     * [downscaledThumbnail] it does not mirror — the processed bitmap already is. Always returns a
     * bitmap distinct from [src] so the caller can recycle it.
     */
    private fun uprightCopy(src: Bitmap, rotationDegrees: Int, maxSide: Int): Bitmap {
        val scale = if (maxSide > 0) {
            (maxSide.toFloat() / maxOf(src.width, src.height)).coerceAtMost(1f)
        } else 1f
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (scale < 1f) postScale(scale, scale)
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        // createBitmap hands back the input itself when the transform is an identity.
        return if (out === src) src.copy(Bitmap.Config.ARGB_8888, false) else out
    }

    /** Rotates the captured frame upright (mirroring for the front camera) and scales it down for the result "data" thumbnail. */
    private fun downscaledThumbnail(image: ImageProxy, mirror: Boolean): Bitmap {
        val raw = image.toBitmap()
        val matrix = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        val maxSide = 512
        val scale = maxSide.toFloat() / maxOf(upright.width, upright.height)
        if (scale >= 1f) return upright
        return upright.scale(
            (upright.width * scale).roundToInt(),
            (upright.height * scale).roundToInt()
        )
    }

    /**
     * Bakes the warmth/shadows color matrix into [src] (and horizontally mirrors it when [mirror]
     * is set, for front-camera parity with the preview), returning a new bitmap and recycling
     * [src]. Rotation is intentionally left to the EXIF orientation tag (see [writeCaptureExif]),
     * mirroring how the normal ImageCapture path stores orientation without rotating pixels.
     */
    private fun applyColorAdjustments(src: Bitmap, warmth: Float, shadows: Float, mirror: Boolean): Bitmap {
        val out = createBitmap(src.width, src.height)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(buildColorAdjustmentMatrix(warmth, shadows))
        }
        val canvas = Canvas(out)
        if (mirror) canvas.scale(-1f, 1f, src.width / 2f, src.height / 2f)
        canvas.drawBitmap(src, 0f, 0f, paint)
        src.recycle()
        return out
    }

    /**
     * Restores the EXIF that re-encoding a processed bitmap dropped: copies the original frame's
     * metadata tags (when [sourceJpeg] is provided), writes the orientation for [rotationDegrees],
     * and stamps GPS when location is enabled. For the night merge there is no single source frame,
     * so [sourceJpeg] is null and only orientation + GPS are written.
     */
    private fun writeCaptureExif(uri: Uri, sourceJpeg: ByteArray?, rotationDegrees: Int, mirrored: Boolean = false) {
        try {
            val source = sourceJpeg?.let { ExifInterface(ByteArrayInputStream(it)) }
            app.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val dest = ExifInterface(pfd.fileDescriptor)
                source?.let { src ->
                    EXIF_TAGS_TO_COPY.forEach { tag ->
                        src.getAttribute(tag)?.let { dest.setAttribute(tag, it) }
                    }
                }
                // When the pixels have already been horizontally mirrored (front camera),
                // the EXIF rotation must be inverted: flip and rotate don't commute, and
                // FLIP∘ROTATE(θ)∘FLIP = ROTATE(−θ). Without this, portrait selfies (θ=90/270)
                // save upside down; landscape (θ=0/180) commutes and is unaffected.
                val effectiveDegrees = if (mirrored) (360 - rotationDegrees) % 360 else rotationDegrees
                dest.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    when (effectiveDegrees) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90
                        180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270
                        else -> ExifInterface.ORIENTATION_NORMAL
                    }.toString()
                )
                if (_locationEnabled.value) lastLocation?.let { dest.setGpsInfo(it) }
                dest.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to write EXIF for adjusted capture", e)
        }
    }

    private fun startLongExposureCountdown(nanos: Long) {
        val durationMs = nanos / 1_000_000
        _longExposureProgress.value = 1f
        _longExposureRemaining.value = formatExposureRemaining(durationMs)
        longExposureTimerJob?.cancel()
        longExposureTimerJob = viewModelScope.launch {
            val start = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - start
                val remaining = (durationMs - elapsed).coerceAtLeast(0)
                _longExposureProgress.value = remaining.toFloat() / durationMs
                _longExposureRemaining.value = formatExposureRemaining(remaining)
                if (remaining <= 0) break
                delay(50)
            }
        }
    }

    private fun stopLongExposureCountdown() {
        longExposureTimerJob?.cancel()
        longExposureTimerJob = null
        _longExposureProgress.value = 0f
        _longExposureRemaining.value = ""
    }

    private fun formatExposureRemaining(ms: Long): String {
        val seconds = ms / 1000f
        return "%.1fs".format(seconds)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun toggleRecording() {
        // Cancel countdown if active
        if (_timerCountdown.value > 0) {
            timerCountdownJob?.cancel()
            timerCountdownJob = null
            _timerCountdown.value = 0
            return
        }

        if (_isRecording.value) {
            currentRecording?.stop()
            currentRecording = null
            stopRecordingTimer()
            return
        }

        val timer = _timerDuration.value
        if (timer.seconds > 0) {
            // Start countdown before recording
            timerCountdownJob = viewModelScope.launch {
                for (i in timer.seconds downTo 1) {
                    _timerCountdown.value = i
                    delay(1000)
                }
                _timerCountdown.value = 0
                timerCountdownJob = null
                startRecording()
            }
            return
        }

        startRecording()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: return

        val timestamp = MediaStoreSaver.timestamp()
        val prefix = when (_cameraMode.value) {
            CameraMode.TIMELAPSE -> "TL"
            CameraMode.CINEMATIC -> "CINE"
            else -> "VID"
        }
        val contentValues = MediaStoreSaver.videoValues("${prefix}_$timestamp")

        val cacheFile = java.io.File(app.cacheDir, "VID_$timestamp.mp4")
        val outputOptions = FileOutputOptions.Builder(cacheFile).build()
        val recordingMode = _cameraMode.value

        startRecordingTimer()

        var pending = capture.output.prepareRecording(app, outputOptions)
        val audioEnabled = recordingMode == CameraMode.VIDEO || recordingMode == CameraMode.CINEMATIC
        if (audioEnabled) {
            pending = pending.withAudioEnabled()
        }
        currentRecording = pending.start(ContextCompat.getMainExecutor(app)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                stopRecordingTimer()
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (!cacheFile.exists()) return@launch
                    val fileToSave = when (recordingMode) {
                        CameraMode.TIMELAPSE -> {
                            val processed = java.io.File(app.cacheDir, "TL_$timestamp.mp4")
                            try {
                                VideoProcessor.adjustSpeed(cacheFile, processed, 8f)
                                cacheFile.delete()
                                processed
                            } catch (e: Exception) {
                                // MediaMuxer can reject an av01 track (or an oversized keyframe)
                                // on some devices; keep the raw recording rather than crash.
                                Log.e("CameraViewModel", "Timelapse remux failed; saving unprocessed", e)
                                processed.delete()
                                cacheFile
                            }
                        }
                        else -> cacheFile
                    }
                    MediaStoreSaver.saveVideoFile(app.contentResolver, contentValues, fileToSave)?.let {
                        setLastCaptureUri(it)
                    }
                    fileToSave.delete()
                }
            }
        }
        // Apply the current mic-mute state to the freshly-started recording.
        if (audioEnabled) {
            try {
                currentRecording?.mute(_micMuted.value)
            } catch (e: Exception) {
                Log.w("CameraViewModel", "Failed to apply initial mic mute", e)
            }
        }
    }

    /** Pauses or resumes the active recording (video/cinematic). No-op if not recording. */
    fun togglePauseRecording() {
        val recording = currentRecording ?: return
        try {
            if (_recordingPaused.value) {
                recording.resume()
                _recordingPaused.value = false
            } else {
                recording.pause()
                _recordingPaused.value = true
            }
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to pause/resume recording", e)
        }
    }

    /** Toggles mic mute; applies live to the active recording. Persisted for the next session. */
    fun toggleMicMuted() {
        _micMuted.value = !_micMuted.value
        try {
            currentRecording?.mute(_micMuted.value)
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to toggle mic mute", e)
        }
        viewModelScope.launch { ds.setString("camera_mic_muted", _micMuted.value.toString()) }
    }

    /**
     * Captures a still while recording (video snapshot), using the ImageCapture bound alongside the
     * video session. No-op if the device couldn't bind the extra ImageCapture use case.
     */
    fun captureVideoSnapshot() {
        val capture = imageCapture ?: return
        val contentValues = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")
        val metadata = ImageCapture.Metadata().apply {
            if (_locationEnabled.value) location = lastLocation
            isReversedHorizontal = mirrorCaptures
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            app.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).setMetadata(metadata).build()
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(app),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    outputFileResults.savedUri?.let { setLastCaptureUri(it) }
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraViewModel", "Video snapshot failed", exception)
                }
            }
        )
    }

    fun startPanorama() = panoramaEngine.startSweep()

    fun stopPanorama() = finishPanoramaSweep()

    fun startPhotosphere() = panoramaEngine.startSweep(fullSphere = true)

    fun stopPhotosphere() = finishPanoramaSweep()

    @Volatile
    private var isFinishingPano = false

    private fun finishPanoramaSweep() {
        if (isFinishingPano) return
        isFinishingPano = true
        panoramaEngine.stopSweep()
        viewModelScope.launch {
            val result = panoramaEngine.stitch()
            if (result == null) {
                android.util.Log.e("CameraViewModel", "Panorama stitch failed – no output (check registration)")
            } else {
                val (jpeg, info) = result
                android.util.Log.i("CameraViewModel", "Panorama stitched ${jpeg.size} bytes, saving")
                val uri = panoramaEngine.saveToMediaStore(jpeg, info)
                if (uri != null) {
                    android.util.Log.i("CameraViewModel", "Panorama saved uri=$uri")
                    setLastCaptureUri(uri)
                } else {
                    android.util.Log.e("CameraViewModel", "Panorama MediaStore save returned null")
                }
            }
            panoramaEngine.reset()
            isFinishingPano = false
        }
    }

    fun getImageCaptureFlashMode(): Int = when (_flashMode.value) {
        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
    }

    override fun onCleared() {
        unregisterLevelSensor()
        try { bokehExecutor.shutdown() } catch (_: Exception) {}
        try { stillBokeh.close() } catch (_: Exception) {}
    }
}

private class ManualLifecycleOwner : LifecycleOwner {
    private val registry = androidx.lifecycle.LifecycleRegistry(this)
    override val lifecycle: androidx.lifecycle.Lifecycle get() = registry

    fun start() {
        registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
    }
}
