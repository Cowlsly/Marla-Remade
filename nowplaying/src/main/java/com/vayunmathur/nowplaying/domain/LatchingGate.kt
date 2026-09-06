package com.vayunmathur.nowplaying.domain

/**
 * Turns the detector's per-window scores into a stable "music is playing" verdict.
 *
 * A raw threshold on a single score flickers, so this smooths over a short run and latches with
 * hysteresis: it takes a sustained run above [positiveThreshold] to switch on and a run below
 * [negativeThreshold] to switch off, and scores landing between the two hold the current verdict.
 *
 * # The thresholds are a guess, and are meant to be changed
 *
 * The reference detector this one follows keeps its thresholds outside the model - the descriptor
 * that would have carried them is a layout table with no values in it, so there is nothing to
 * recover. The defaults here are a tuned guess, and are named parameters for that reason: the
 * detector feeding them is still being built, and tuning them against it is expected.
 *
 * Every count is in units of one detector hop, so what they mean in seconds depends on the hop the
 * caller is running at.
 */
class LatchingGate(
    private val positiveThreshold: Float = DEFAULT_POSITIVE_THRESHOLD,
    private val negativeThreshold: Float = DEFAULT_NEGATIVE_THRESHOLD,
    private val windowHops: Int = DEFAULT_WINDOW_HOPS,
    private val hopsBeforePositive: Int = DEFAULT_HOPS_BEFORE_POSITIVE,
    private val hopsBeforeNegative: Int = DEFAULT_HOPS_BEFORE_NEGATIVE,
) {
    private val window = FloatArray(windowHops)
    private var cursor = 0
    private var filled = 0
    private var aboveRun = 0
    private var belowRun = 0

    /** Whether music is currently considered to be playing. */
    var isMusic: Boolean = false
        private set

    /** The windowed mean of the most recent scores, which is what the thresholds are applied to. */
    var smoothedScore: Float = 0f
        private set

    /** Feeds one hop's score. Returns true when [isMusic] changed on this hop. */
    fun push(score: Float): Boolean {
        window[cursor] = score
        cursor = (cursor + 1) % windowHops
        if (filled < windowHops) filled++

        // Summed fresh each hop rather than carried: a running total drifts over the millions of
        // hops an always-on session accumulates.
        var total = 0f
        for (i in 0 until filled) total += window[i]
        smoothedScore = total / filled

        when {
            smoothedScore >= positiveThreshold -> {
                aboveRun++
                belowRun = 0
            }
            smoothedScore < negativeThreshold -> {
                belowRun++
                aboveRun = 0
            }
            else -> {
                aboveRun = 0
                belowRun = 0
            }
        }

        val was = isMusic
        if (isMusic) {
            if (belowRun >= hopsBeforeNegative) isMusic = false
        } else {
            if (aboveRun >= hopsBeforePositive) isMusic = true
        }
        return isMusic != was
    }

    fun reset() {
        window.fill(0f)
        cursor = 0
        filled = 0
        aboveRun = 0
        belowRun = 0
        isMusic = false
        smoothedScore = 0f
    }

    companion object {
        /**
         * Smoothed score at or above which a hop counts towards latching on.
         *
         * The detector's own thresholds are lower, but 0.90 is where real music separates from
         * speech: ringtones hold the positive state 67-99% of the time and speech holds it 0%.
         */
        const val DEFAULT_POSITIVE_THRESHOLD = 0.90f

        /** Smoothed score below which a hop counts towards latching off. */
        const val DEFAULT_NEGATIVE_THRESHOLD = 0.85f

        /** Hops the mean is taken over, so one bad hop cannot flip the verdict. */
        const val DEFAULT_WINDOW_HOPS = 5

        /** Consecutive hops above [DEFAULT_POSITIVE_THRESHOLD] before music is declared. */
        const val DEFAULT_HOPS_BEFORE_POSITIVE = 5

        /**
         * Consecutive hops below [DEFAULT_NEGATIVE_THRESHOLD] before music is withdrawn, giving a
         * 500 ms floor on how briefly the gate can believe music at a 10 ms hop.
         *
         * Much larger than its positive counterpart. The score dips through the band constantly
         * even while music plays, so a short run here makes the state drop and immediately re-arm:
         * at 1 hop the gate emits 100-200 events per minute on *every* input, silence included.
         */
        const val DEFAULT_HOPS_BEFORE_NEGATIVE = 50
    }
}
