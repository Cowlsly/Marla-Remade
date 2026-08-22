package com.vayunmathur.communicate.data.call

import android.content.Context
import android.util.Log
import org.webrtc.NativeLibraryLoader
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-time WebRTC initialization shared by every calling line.
 *
 * WebRTC here comes from RingRTC, which compiles its WebRTC **into** `libringrtc_rffi.so` rather than
 * shipping a standalone `libjingle_peerconnection_so`. So the stock native loader has nothing to find:
 * loading `ringrtc` pulls the WebRTC natives in as a side effect, and `PeerConnectionFactory` must then
 * be initialized with a loader that does nothing.
 *
 * Getting this wrong compiles cleanly and fails at the first call with `UnsatisfiedLinkError`, which is
 * why it lives in one place instead of at each call site.
 */
object WebRtcInit {
    private const val TAG = "WebRtcInit"

    private val initialized = AtomicBoolean(false)

    /** Idempotent; safe to call before every call setup. */
    fun ensureInitialized(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        try {
            // Brings in the WebRTC natives along with RingRTC's own.
            System.loadLibrary("ringrtc")
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setNativeLibraryLoader(AlreadyLoaded)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
        } catch (t: Throwable) {
            // Let the next attempt retry rather than leaving the flag set on a half-initialized stack.
            initialized.set(false)
            Log.e(TAG, "WebRTC initialization failed", t)
            throw t
        }
    }

    private object AlreadyLoaded : NativeLibraryLoader {
        override fun load(name: String): Boolean = true
    }
}
