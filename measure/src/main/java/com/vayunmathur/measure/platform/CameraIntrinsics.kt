package com.vayunmathur.measure.domain

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size

/**
 * Pinhole intrinsics for the analysis stream, in analysis-resolution pixels.
 */
data class Intrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    /** True when these came from the factory calibration rather than an FOV estimate. */
    val calibrated: Boolean,
)

/**
 * Resolves camera intrinsics for un-projecting pixels into rays.
 *
 * Prefers `LENS_INTRINSIC_CALIBRATION`, which recent Pixels populate with per-device
 * factory values — far better than deriving a focal length from the advertised FOV,
 * which ignores lens distortion and manufacturing spread. Falls back to focal length
 * and sensor size when the calibration tag is absent.
 *
 * The calibration is expressed against the sensor's **active array**, so it must be
 * rescaled to whatever resolution the analysis stream actually delivers.
 */
object CameraIntrinsicsResolver {

    fun resolve(context: Context, analysisSize: Size, cameraId: String? = null): Intrinsics? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return null
        val id = cameraId ?: firstBackCameraId(manager) ?: return null
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return null

        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return null
        val arrayW = activeArray.width().toDouble()
        val arrayH = activeArray.height().toDouble()
        if (arrayW <= 0 || arrayH <= 0) return null

        // The analysis stream is centre-cropped to its own aspect ratio before being
        // scaled down, so the two axes generally do not share a scale factor.
        val scaleX = analysisSize.width / arrayW
        val scaleY = analysisSize.height / arrayH

        val calibration = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        if (calibration != null && calibration.size >= 4) {
            val fx = calibration[0].toDouble() * scaleX
            val fy = calibration[1].toDouble() * scaleY
            val cx = calibration[2].toDouble() * scaleX
            val cy = calibration[3].toDouble() * scaleY
            if (fx > 1.0 && fy > 1.0) {
                return Intrinsics(fx, fy, cx, cy, calibrated = true)
            }
        }

        // Fallback: focal length in millimetres against physical sensor size.
        val focalMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()?.toDouble() ?: return null
        val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        if (physical.width <= 0f || physical.height <= 0f) return null

        val fxPx = focalMm / physical.width * arrayW * scaleX
        val fyPx = focalMm / physical.height * arrayH * scaleY
        return Intrinsics(
            fx = fxPx,
            fy = fyPx,
            cx = analysisSize.width / 2.0,
            cy = analysisSize.height / 2.0,
            calibrated = false,
        )
    }

    /**
     * True when camera frame timestamps share the `elapsedRealtimeNanos` clock the
     * sensors use. Without this the two streams cannot be fused and VIO will not
     * converge, so the UI should refuse rather than produce silent nonsense.
     */
    fun timestampsAlignWithSensors(context: Context, cameraId: String? = null): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false
        val id = cameraId ?: firstBackCameraId(manager) ?: return false
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return false
        return chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
    }

    private fun firstBackCameraId(manager: CameraManager): String? =
        runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrNull()
}
