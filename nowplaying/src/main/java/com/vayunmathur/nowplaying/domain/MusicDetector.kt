package com.vayunmathur.nowplaying.domain

/**
 * The boundary between this app and whatever decides that music is playing.
 *
 * Everything above this interface - the latching gate, the service, the UI, the history - is
 * plain Kotlin and knows nothing about how a score is produced. That matters because where the
 * scoring runs has already moved once: the plan was a Vulkan compute model, and the gate that is
 * actually being built is plain CPU Rust. Nothing above here had to change for that.
 *
 * Implementations are window-oriented, not streaming: a caller hands over a whole window of
 * audio and gets one score back. [reset] exists because an implementation may compare a window
 * against earlier ones, so a caller starting a fresh listening session must call it or the
 * first scores are relative to the previous session's audio.
 */
interface MusicDetector : AutoCloseable {

    /** False when the model could not be loaded. Every other member is then inert. */
    val isAvailable: Boolean

    /** Samples of mono [SAMPLE_RATE] PCM one [score] call consumes. */
    val windowSamples: Int

    /**
     * How confident this is that [window] contains music, in `0f..1f`, or null if the detector
     * is unavailable or the score is not yet meaningful.
     *
     * [window] must be exactly [windowSamples] mono [SAMPLE_RATE] samples. The caller slides a
     * buffer forward by whatever hop it likes and calls this once per hop; the returned scores
     * are what [LatchingGate] smooths.
     */
    fun score(window: ShortArray): Float?

    /** Discards any cross-window state so the next [score] starts a fresh session. */
    fun reset()

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 10 ms, the rate the gate scores at and the unit [LatchingGate] counts in. */
        const val HOP_SAMPLES = 160
    }
}
