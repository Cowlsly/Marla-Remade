package com.vayunmathur.library.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * A wall clock that ticks every [period], handed back as a getter rather than a value.
 *
 * The return type is the whole point. Obtaining the clock subscribes nobody, so only the scope
 * that *invokes* the getter is invalidated when it ticks. The usual way to get this wrong is
 * `val now by viewModel.ticker.collectAsState()` at the top of a screen: that subscribes the
 * screen, and on a list screen a tick then recomposes every visible row even though only one
 * label in each row shows the time. Call the getter inside the row, slot or draw lambda that
 * actually displays it instead.
 *
 * Ticks land on the [period] boundary rather than being spaced by a plain `delay(period)`, which
 * drifts - a one-second clock spaced that way visibly skips a second every so often.
 *
 * Each call site owns its own coroutine for as long as it is in composition, so a screen picks the
 * period it needs. Prefer that to sharing one fast ticker across screens: a stopwatch showing
 * centiseconds and a world-clock list showing minutes have no business running at the same rate.
 */
@Composable
fun rememberClock(period: Duration = 1.seconds): () -> Long {
    val time = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(period) {
        val periodMs = period.inWholeMilliseconds.coerceAtLeast(1)
        while (true) {
            val now = System.currentTimeMillis()
            time.longValue = now
            delay(periodMs - now % periodMs)
        }
    }
    return remember(time) { { time.longValue } }
}
