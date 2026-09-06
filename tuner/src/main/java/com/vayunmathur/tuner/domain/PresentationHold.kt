package com.vayunmathur.tuner.domain

/**
 * Keeps the last reading on screen for a while after the detector stops producing one.
 *
 * Both tabs need this and for the same reason: the detector answers "what is sounding in this
 * frame", which is a measurement and is allowed to stop the instant the signal falls into the
 * noise, while the display answers "what should the user be looking at", which must not. A
 * plucked string decays, detection drops, and a display bound straight to the detector blinks
 * out - on the Chord tab as a strobe through the decay, on the Note tab as a reading that
 * vanishes while the player is still turning the peg.
 *
 * Two properties matter and they pull in opposite directions:
 *
 * - a fresh detection is shown **immediately** ([present] never delays), because holding a
 *   stale reading over a genuine change is worse than flicker - the user would be tuning
 *   against a note they are no longer playing;
 * - a *missing* detection is absorbed for [holdFrames] before the reading is dropped.
 *
 * [isStale] exists so the UI can say which of the two it is showing. A held reading is still
 * useful but it is no longer live, and presenting it identically to a live one would be
 * dishonest in exactly the way that matters most on a tuner.
 */
class PresentationHold<T : Any>(private val holdFrames: Int) {
    private var value: T? = null
    private var idleFrames = 0

    /** What should be on screen, or null when there is nothing to show. */
    val current: T? get() = value

    /** True when [current] is being held rather than freshly detected. */
    val isStale: Boolean get() = value != null && idleFrames > 0

    /** A fresh detection. Replaces what is shown straight away. */
    fun present(fresh: T) {
        value = fresh
        idleFrames = 0
    }

    /** No detection this frame. Returns true when the hold expired and [current] was dropped. */
    fun idle(): Boolean {
        if (value == null) return false
        if (++idleFrames < holdFrames) return false
        reset()
        return true
    }

    fun reset() {
        value = null
        idleFrames = 0
    }
}
