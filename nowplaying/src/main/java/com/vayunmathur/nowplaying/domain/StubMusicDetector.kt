package com.vayunmathur.nowplaying.domain

/**
 * Stands in until the CPU music gate in `:library:ml` lands.
 *
 * It reports nothing rather than guessing: [isAvailable] is false and [score] returns null, so the
 * app shows its "detector unavailable" state instead of a fabricated verdict. That is the same call
 * [NoSongMatcher] makes, for the same reason.
 *
 * Swapping in the real detector is one line in `ListenerService`.
 */
class StubMusicDetector : MusicDetector {
    override val isAvailable = false

    override val windowSamples = MusicDetector.HOP_SAMPLES

    override fun score(window: ShortArray): Float? = null

    override fun reset() = Unit

    override fun close() = Unit
}
