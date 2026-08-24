package com.vayunmathur.library.ui.game

/**
 * `mm:ss`, and `h:mm:ss` once the clock has run past the hour.
 *
 * Shared because five games format an elapsed time and every one of them wants the same shape. A
 * duration is not a time of day, so `DateString` is the wrong home for it.
 *
 * Negative input is clamped: a countdown that has run out should read `00:00`, not `-1:-1`.
 */
fun formatDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
    else "%02d:%02d".format(minutes, secs)
}
