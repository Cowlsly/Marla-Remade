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
 *       MSG_PLAYBACK_STATE                ─►     (position, duration, playing, …)
 *                                    ◄──  MSG_PLAYBACK_COMMAND (the TV remote was pressed)
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
     *
     * **Bumped for the transport controls, and that is a deliberate refusal rather than a
     * degradation.** A Cast without [MSG_PLAYBACK_STATE] would stream perfectly and give the
     * television a remote whose every button did nothing, which is a worse failure than not casting:
     * the user has no way to tell it apart from a broken TV. So the old app blocks the cast and says
     * to update, in the same breath as the protocol version bump that already forces both halves to
     * ship together.
     */
    const val MIN_CAST_VERSION_CODE = 20260823L

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

    /**
     * Where playback is, so the television can draw a seek bar for content only it can see.
     *
     * Carries [KEY_POSITION_MS] and its neighbours as an absolute snapshot rather than a delta, which
     * is what makes a dropped one cost nothing. Fire and forget: there is no reply, and a client that
     * stops sending simply leaves the TV's overlay stale until the session ends.
     *
     * Ignored unless a session is open, so a client may send it without tracking readiness itself.
     */
    const val MSG_PLAYBACK_STATE = 5

    // ---- service → client ----

    /**
     * The session is live. Carries [KEY_SURFACE], optionally [KEY_AUDIO_FD], and the granted
     * geometry.
     */
    const val MSG_SESSION_READY = 3

    /** The session is over, for the reason in [KEY_END_REASON]. The `Surface` is invalid from here. */
    const val MSG_SESSION_ENDED = 4

    /**
     * Somebody pressed a button on the television's remote. Carries [KEY_ACTION] and maybe
     * [KEY_ACTION_VALUE].
     *
     * Delivered only for a session this client opened, and only for app content - screen mirroring has
     * no transport to control. A client that does not handle these is not broken: the TV's overlay
     * will simply never appear, because it is only mounted once state arrives.
     */
    const val MSG_PLAYBACK_COMMAND = 6

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

    // ---- MSG_PLAYBACK_STATE payload ----

    /**
     * Where playback is and where it ends, in milliseconds.
     *
     * A duration of zero or less means "no known end", which the TV renders without a bar rather than
     * as a zero-length one.
     */
    const val KEY_POSITION_MS = "positionMs"
    const val KEY_DURATION_MS = "durationMs"

    /**
     * Whether media is advancing, which is not the same as whether the user asked for it to.
     *
     * Report the player's own "is playing" rather than its play-when-ready flag: the TV extrapolates
     * position between snapshots, and a stall reported as playing would run its seek bar ahead of the
     * picture and then jerk it back.
     */
    const val KEY_PLAYING = "playing"

    /** For a spinner. Separate from [KEY_PLAYING] because a stall is not a pause. */
    const val KEY_BUFFERING = "buffering"

    /** Tempo multiplier, 1.0 being normal. */
    const val KEY_SPEED = "speed"

    /** Media volume as 0..1. The same level on both ends - see [ACTION_SET_VOLUME]. */
    const val KEY_VOLUME = "volume"

    /**
     * Whether there is anything to skip to.
     *
     * Carried because the TV cannot know: what is next is the client's own idea of a queue, and a
     * remote that offers a button doing nothing is worse than one that offers none.
     */
    const val KEY_HAS_NEXT = "hasNext"
    const val KEY_HAS_PREVIOUS = "hasPrevious"

    // ---- MSG_PLAYBACK_COMMAND payload ----

    /** One of the `ACTION_` constants. Unknown values must be ignored, not treated as an error. */
    const val KEY_ACTION = "action"

    /**
     * The action's argument, for the actions that take one: milliseconds for [ACTION_SEEK_TO], a
     * multiplier for [ACTION_SET_SPEED], 0..1 for [ACTION_SET_VOLUME]. Absent otherwise.
     */
    const val KEY_ACTION_VALUE = "actionValue"

    /**
     * What the remote asked for.
     *
     * **Deliberately a second definition of `:cast:protocol`'s `PlaybackAction`, not a reuse of it.**
     * This module is the public client contract and every consumer compiles against it; giving it a
     * dependency on the streaming protocol would hand every casting app the wire format, the crypto
     * and the RTP packetiser, which is the whole thing brokering exists to avoid. `:cast` depends on
     * both and owns the mapping between them, so the duplication is one file wide and one function
     * deep.
     *
     * Ints rather than an enum for the same reason [MSG_OPEN_SESSION] is: this crosses a `Bundle`,
     * and a `Serializable` enum in a `Bundle` is a class-loading problem waiting for a client built
     * against a different SDK version.
     */
    const val ACTION_PLAY = 0
    const val ACTION_PAUSE = 1

    /**
     * Whichever of play and pause the client is not currently doing.
     *
     * Its own action rather than the TV choosing from its last snapshot: that snapshot can be half a
     * second old, and two quick presses resolved against it would both send the same thing.
     */
    const val ACTION_TOGGLE = 2

    /** [KEY_ACTION_VALUE] is an absolute position in milliseconds. */
    const val ACTION_SEEK_TO = 3

    /** The client's own skip interval, so the two ends cannot disagree about how far it is. */
    const val ACTION_SKIP_FORWARD = 4
    const val ACTION_SKIP_BACK = 5

    const val ACTION_NEXT = 6
    const val ACTION_PREVIOUS = 7

    /** [KEY_ACTION_VALUE] is a tempo multiplier. */
    const val ACTION_SET_SPEED = 8

    /**
     * [KEY_ACTION_VALUE] is 0..1, and is the level for *both* ends.
     *
     * The client is expected to move its own media volume to match, so that the level survives the
     * session ending and local playback resumes where the TV left it.
     */
    const val ACTION_SET_VOLUME = 9

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
