package com.vayunmathur.communicate.data.signal.call

import org.signal.ringrtc.CallManager
import org.signal.ringrtc.CameraControl
import org.webrtc.CapturerObserver
import android.util.Log as AndroidLog
import org.signal.ringrtc.Log as RingRtcLog

/** Routes RingRTC's internal logging into logcat. */
class SignalRingRtcLogger : RingRtcLog.Logger {
    override fun v(tag: String?, message: String?, t: Throwable?) {
        AndroidLog.v(tag ?: TAG, message ?: "", t)
    }

    override fun d(tag: String?, message: String?, t: Throwable?) {
        AndroidLog.d(tag ?: TAG, message ?: "", t)
    }

    override fun i(tag: String?, message: String?, t: Throwable?) {
        AndroidLog.i(tag ?: TAG, message ?: "", t)
    }

    override fun w(tag: String?, message: String?, t: Throwable?) {
        AndroidLog.w(tag ?: TAG, message ?: "", t)
    }

    override fun e(tag: String?, message: String?, t: Throwable?) {
        AndroidLog.e(tag ?: TAG, message ?: "", t)
    }

    private companion object {
        const val TAG = "RingRTC"
    }
}

/**
 * Camera control for audio-only calling: reports no capturer so RingRTC never tries to drive one.
 *
 * Video calls need a real implementation backed by `Camera2Enumerator`; until then an outgoing video
 * call would negotiate video and then send nothing.
 */
object NoCameraControl : CameraControl {
    override fun hasCapturer(): Boolean = false

    override fun initCapturer(observer: CapturerObserver) = Unit

    override fun setEnabled(enable: Boolean) = Unit

    override fun flip() = Unit

    override fun setOrientation(orientation: Int?) = Unit
}

/** Field trials RingRTC expects; `initialize` merges its own defaults over these. */
internal fun ringRtcFieldTrials(): Map<String, String> = emptyMap()

internal fun CallManager.CallMediaType.isVideo(): Boolean =
    this == CallManager.CallMediaType.VIDEO_CALL
