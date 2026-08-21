package com.vayunmathur.cast.platform.mirror

import android.media.projection.MediaProjection

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
