package com.vayunmathur.communicate.domain

/**
 * A call duration split into the units a call log row shows, coarsest first.
 *
 * A null unit is omitted from the row rather than rendered as zero, so an exact hour reads
 * "1 hr" and not "1 hr 0 min". [seconds] survives alone at zero so a call that never connected
 * still reads as a duration instead of as an empty string.
 */
data class CallDurationParts(
    val hours: Int?,
    val minutes: Int?,
    val seconds: Int?,
)

/**
 * Split [totalSeconds] into at most the two coarsest units that carry information.
 *
 * Seconds are dropped once the call has run past the hour: at that scale they are noise, and a
 * three-unit row does not fit beside the call type and timestamp.
 *
 * Negative input is clamped, since a provider row with a nonsense duration should read as zero.
 */
fun callDurationParts(totalSeconds: Long): CallDurationParts {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = (safe / 3600).toInt()
    val minutes = ((safe % 3600) / 60).toInt()
    val seconds = (safe % 60).toInt()
    return when {
        hours > 0 -> CallDurationParts(hours, minutes.takeIf { it > 0 }, null)
        minutes > 0 -> CallDurationParts(null, minutes, seconds.takeIf { it > 0 })
        else -> CallDurationParts(null, null, seconds)
    }
}
