package com.vayunmathur.communicate.data.signal.call

import android.content.Context
import android.util.Log
import org.signal.ringrtc.CameraControl
import org.webrtc.Camera2Capturer
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame

/**
 * Camera plumbing for calls.
 *
 * Present even on an audio call, and that is the point: Signal's offer always carries a video m-line, and
 * WebRTC requires an answer's m-lines to match the offer's in number and order. With no capturer RingRTC
 * builds no video transceiver, the answer comes back audio-only, and the peer connection rejects it with
 * "The order of m-lines in answer doesn't match order in offer" - so every inbound call failed instantly.
 *
 * The capture device is therefore created for every call but only *started* when video is enabled.
 */
class SignalCamera(
    private val appContext: Context,
    private val eglBase: EglBase,
) : CameraControl, CameraVideoCapturer.CameraSwitchHandler {

    private val capturer: CameraVideoCapturer? = createCapturer()
    private var capturing = false
    private var frontFacing = true

    /** True whenever the device has a usable camera, matching what the offer expects to negotiate. */
    override fun hasCapturer(): Boolean = capturer != null

    override fun initCapturer(observer: CapturerObserver) {
        val capturer = this.capturer ?: return
        try {
            capturer.initialize(
                SurfaceTextureHelper.create("SignalCameraThread", eglBase.eglBaseContext),
                appContext,
                observer,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not initialize the camera capturer", t)
        }
    }

    override fun setEnabled(enable: Boolean) {
        val capturer = this.capturer ?: return
        try {
            if (enable && !capturing) {
                capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
                capturing = true
            } else if (!enable && capturing) {
                capturer.stopCapture()
                capturing = false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not ${if (enable) "start" else "stop"} video capture", t)
        }
    }

    override fun flip() {
        frontFacing = !frontFacing
        try {
            capturer?.switchCamera(this)
        } catch (t: Throwable) {
            Log.w(TAG, "could not switch camera", t)
        }
    }

    override fun setOrientation(orientation: Int?) = Unit

    fun dispose() {
        try {
            if (capturing) capturer?.stopCapture()
            capturer?.dispose()
        } catch (_: Throwable) {
        }
        capturing = false
    }

    override fun onCameraSwitchDone(isFrontCamera: Boolean) {
        frontFacing = isFrontCamera
    }

    override fun onCameraSwitchError(error: String?) {
        Log.w(TAG, "camera switch failed: $error")
    }

    private fun createCapturer(): CameraVideoCapturer? {
        if (!Camera2Enumerator.isSupported(appContext)) {
            Log.w(TAG, "Camera2 unsupported; calls will negotiate without video")
            return null
        }
        val enumerator = Camera2Enumerator(appContext)
        val names = try { enumerator.deviceNames } catch (t: Throwable) {
            Log.w(TAG, "could not enumerate cameras", t)
            return null
        }
        // Front first, since that is what a video call would use; any camera is enough to negotiate.
        val preferred = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull()
        if (preferred == null) {
            Log.w(TAG, "no camera on this device; calls will negotiate without video")
            return null
        }
        return try {
            Camera2Capturer(appContext, preferred, null)
        } catch (t: Throwable) {
            Log.w(TAG, "could not create a camera capturer for $preferred", t)
            null
        }
    }

    private companion object {
        const val TAG = "SignalCamera"
        const val CAPTURE_WIDTH = 640
        const val CAPTURE_HEIGHT = 480
        const val CAPTURE_FPS = 30
    }
}

/**
 * Forwards frames to a renderer when one is attached, and drops them otherwise.
 *
 * RingRTC needs a sink at `proceed()` time, but the UI that renders video does not exist until later (and
 * not at all for an audio call), so the destination is swappable.
 *
 * Note it must **not** release frames: the producer (`SurfaceTextureHelper`) releases its own reference
 * once `onFrame` returns, so releasing here drops the refcount below zero and crashes the decoder thread
 * with "release() called on an object with refcount < 1". A sink that wants to keep a frame calls
 * `retain()`; a sink that wants to drop one simply returns.
 */
class SwappableVideoSink : org.webrtc.VideoSink {
    @Volatile
    private var delegate: org.webrtc.VideoSink? = null

    fun attach(sink: org.webrtc.VideoSink?) {
        delegate = sink
    }

    override fun onFrame(frame: VideoFrame?) {
        delegate?.onFrame(frame)
    }
}
