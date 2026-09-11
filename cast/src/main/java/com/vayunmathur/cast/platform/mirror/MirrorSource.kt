package com.vayunmathur.cast.platform.mirror

import android.media.projection.MediaProjection
import com.vayunmathur.cast.platform.remotedisplay.CastSystemDisplay

/**
 * What [MirrorEngine] is encoding.
 *
 * The pipeline underneath - H.264 into an encoder input surface, RTP over UDP, RTCP feedback - never
 * had anything to do with screen capture; `MediaProjection` was simply the only way anything got into
 * it. Naming the two cases makes that explicit, and is the whole of what `:sdk:cast` needed from
 * `:cast`.
 */
sealed interface MirrorSource {

    /**
     * The name the TV shows as the source. Empty means the phone's own name, which is what screen
     * mirroring wants.
     */
    val appLabel: String

    /** The phone's screen, captured into the encoder's input surface by a `VirtualDisplay`. */
    class Screen(val projection: MediaProjection) : MirrorSource {
        override val appLabel: String get() = ""
    }

    /**
     * A SYSTEM-OWNED display, created by this app and handed to the framework.
     *
     * The desktop-mode path, and the difference from [Screen] is not cosmetic. [Screen] takes a
     * `MediaProjection` and mirrors the phone: same content, same layout, one screen shown twice,
     * and a consent dialog every session. This creates a *separate* display that the window
     * manager can place activities on, so the TV can be a second desktop rather than a copy of
     * the phone - and whether it mirrors or extends is the system's choice, exposed by Settings'
     * own switch, because the display carries `ALLOWS_CONTENT_MODE_SWITCH`.
     *
     * There is no consent Activity because there is no screen capture: nothing here reads the
     * phone's screen. It needs `ADD_TRUSTED_DISPLAY` instead, which cast holds through the
     * SYSTEM_AUTOMOTIVE_PROJECTION role pinned in `MaosFrameworkResRRO`.
     *
     * [displayId] is published back to the route via `RemoteDisplay.setPresentationDisplayId`
     * once the display exists; `MediaRouterService` only ever reads that id, it never creates a
     * display itself.
     */
    class SystemDisplay : MirrorSource {
        override val appLabel: String get() = ""

        /** Framework display id, or -1 before [MirrorEngine.start] has created it. */
        var displayId: Int = -1
            internal set

        /**
         * The TV's `receiverId`, which the display's stable unique id is built from.
         *
         * Stable across sessions on purpose: the framework keys every persisted display
         * preference - resolution, rotation, and the mirror-or-desktop connection choice - on the
         * unique id, and gates the lot behind `DisplayDevice.hasStableUniqueId()`. Building it
         * from anything per-session would give the same television a new identity on every
         * connect, so nothing the user chose would ever be remembered.
         *
         * Null before the handshake names the receiver, in which case no unique id is set and the
         * framework falls back to its own per-session one.
         */
        var receiverId: String? = null
            internal set

        /**
         * The desktop resolutions to declare on the display, largest (the default) first.
         *
         * These become the display's `supportedModes`, which is what populates Android's
         * external-display resolution picker. Empty until [com.vayunmathur.cast.platform.CastController]
         * computes them from the TV's advertised modes and this phone's encoder limits.
         */
        var supportedModes: List<CaptureGeometry> = emptyList()
            internal set

        /**
         * The virtual display itself, which **outlives any one [MirrorEngine]**.
         *
         * Held here rather than in the engine because a resolution change re-negotiates the
         * stream: the encoder and the RTP transport are torn down and rebuilt, and if the display
         * went with them the desktop's windows would be destroyed and its `displayId` would
         * change - invalidating the very Settings page the user made the choice on. So the engine
         * borrows it and re-points it at each new encoder surface, and only
         * [com.vayunmathur.cast.platform.CastController] releases it, when the session really
         * ends.
         */
        var display: CastSystemDisplay? = null
            internal set
    }

    /**
     * Another app's content: it is handed the encoder's input surface and draws into it itself, and
     * writes PCM into a pipe if [wantAudio].
     *
     * [appLabel] is resolved by `CastPickerActivity` from the framework's `callingPackage`, never
     * self-reported by the app - which is what makes it something the TV can display without it
     * being a claim the sender could have forged.
     */
    class Content(
        override val appLabel: String,
        val wantAudio: Boolean,
    ) : MirrorSource
}
