package com.vayunmathur.sdk.cast

/**
 * The wire contract between an app that wants its content on a TV and the installed Cast app.
 *
 * Constants only, no framework logic, so both halves can compile against the same names rather than
 * against a byte-for-byte copy of them - which is the one thing `FamilyLocationProtocol` gets wrong
 * by living twice. `GameHubContract` is the shape this follows.
 *
 * **This is not a streaming API.** The SDK owns no sockets, no encoders and no crypto: it hands over
 * a size and gets back a [android.view.Surface] to draw into and a pipe to write PCM to, and Cast does
 * everything from there. That is what makes one pairing per TV serve every app on the device, and why
 * a consumer needs no network permission at all.
 *
 * The sequence, in the order it happens:
 *
 * ```
 * app   launch the picker Activity for result ─►   user picks a TV, pairs if needed
 *                                    ◄──  RESULT_OK
 *       bind the service, MSG_OPEN_SESSION ─►     (width, height, wantAudio)
 *                                    ◄──  MSG_SESSION_READY (Surface, audio pipe, granted size)
 *       … render into the Surface, write PCM into the pipe …
 *       unbind, or MSG_CLOSE_SESSION      ─►
 *                                    ◄──  MSG_SESSION_ENDED (reason)
 * ```
 */
object CastContract {

    /** The app that owns the sockets, the pairing and the encoders. */
    const val CAST_PACKAGE = "com.vayunmathur.cast"

    /**
     * Class names rather than a compile-time dependency on `:cast`: an SDK that made every consumer
     * pull in the whole Cast app would defeat the point of brokering.
     */
    const val PICKER_ACTIVITY = "$CAST_PACKAGE.platform.CastPickerActivity"
    const val SERVICE_CLASS = "$CAST_PACKAGE.service.ContentCastService"

    /**
     * Signature-level, so only apps signed with the Modern Apps key can bind.
     *
     * Worth being honest about what that does and does not buy: it means any first-party app can
     * stream, and nothing narrower is available to a `Messenger` service, because `Message` dispatch
     * goes through a `Handler` and the Binder calling identity is gone by the time the handler runs.
     * The sending app's *name* is therefore never taken from the app - see [KEY_GRANTED_WIDTH]'s
     * neighbours: there is no app-label key here at all. Cast resolves the label from the
     * `callingPackage` the framework attaches to the picker Activity.
     */
    const val PERMISSION = "com.vayunmathur.cast.permission.STREAM_CONTENT"

    /**
     * The lowest Cast `versionCode` that serves SDK sessions.
     *
     * Must stay in lockstep with the root `version.txt` value at the build that first shipped
     * [SERVICE_CLASS]. An older Cast has no such service, so binding would fail with nothing to say
     * why; probing the version code first is what turns that into an actionable "update Cast".
     */
    const val MIN_CAST_VERSION_CODE = 20260816L

    // ---- client → service ----

    /**
     * Open a session. Carries [KEY_WIDTH], [KEY_HEIGHT] and [KEY_WANT_AUDIO], and its `replyTo` is
     * where [MSG_SESSION_READY] and [MSG_SESSION_ENDED] are sent.
     */
    const val MSG_OPEN_SESSION = 1

    /**
     * End the session without unbinding.
     *
     * Unbinding ends it too - that is the point of a bound service, and it is what covers the client
     * being killed - so this exists only for an app that wants to stop casting and keep the binding.
     */
    const val MSG_CLOSE_SESSION = 2

    // ---- service → client ----

    /**
     * The session is live. Carries [KEY_SURFACE], optionally [KEY_AUDIO_FD], and the granted
     * geometry.
     */
    const val MSG_SESSION_READY = 3

    /** The session is over, for the reason in [KEY_END_REASON]. The `Surface` is invalid from here. */
    const val MSG_SESSION_ENDED = 4

    // ---- MSG_OPEN_SESSION payload ----

    /** Requested frame width in pixels. Clamped; read the granted size back, do not assume it. */
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"

    /** Whether the app intends to write PCM. False means video only and no pipe is created. */
    const val KEY_WANT_AUDIO = "wantAudio"

    // ---- MSG_SESSION_READY payload ----

    /**
     * The encoder's input surface, as a `Parcelable`.
     *
     * What the app draws into: a `MediaCodec` input surface, so everything rendered to it is encoded
     * and sent. The app must not release it - Cast owns it, and releasing it from the client side is
     * what would break the *next* session rather than this one.
     */
    const val KEY_SURFACE = "surface"

    /**
     * The write end of a pipe for PCM, present only when [KEY_WANT_AUDIO] was set and audio started.
     *
     * A pipe rather than messages because 48 kHz stereo 16-bit is ~192 KB/s: as `Messenger` messages
     * that is fifty Binder transactions a second competing with everything else on the binder
     * thread. Absent means audio was asked for and could not be provided; the video session is still
     * live, so a caller should keep playing rather than treat it as a failure.
     *
     * Format is fixed and not negotiated: [AUDIO_SAMPLE_RATE], [AUDIO_CHANNELS], 16-bit
     * little-endian, interleaved.
     */
    const val KEY_AUDIO_FD = "audioFd"

    /**
     * What Cast actually agreed with the TV, which is not necessarily what was asked for.
     *
     * Clamped to the TV's reported decoder limits and to what this phone's encoder will accept. An
     * app that laid out for the size it requested would be stretched, so this is returned rather
     * than left to be assumed.
     */
    const val KEY_GRANTED_WIDTH = "grantedWidth"
    const val KEY_GRANTED_HEIGHT = "grantedHeight"
    const val KEY_GRANTED_FRAME_RATE = "grantedFrameRate"

    /** The TV's name, for a "Playing on <TV>" panel where the video used to be. */
    const val KEY_RECEIVER_NAME = "receiverName"

    // ---- MSG_SESSION_ENDED payload ----

    /** One of the `REASON_` constants. */
    const val KEY_END_REASON = "endReason"

    /** The app asked, via [MSG_CLOSE_SESSION] or by unbinding. */
    const val REASON_CLIENT_CLOSED = 0

    /** No TV was connected, or the picker was never completed. */
    const val REASON_NO_SESSION = 1

    /** The TV went away, or the control channel closed. */
    const val REASON_RECEIVER_GONE = 2

    /** Screen mirroring, or another app's session, took the single session slot. */
    const val REASON_PREEMPTED = 3

    /** The encoder or the stream negotiation failed. */
    const val REASON_FAILED = 4

    // ---- the fixed audio format ----

    /** Matches the RTP timebase Cast negotiates; resampling here would only add latency. */
    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_CHANNELS = 2
}
