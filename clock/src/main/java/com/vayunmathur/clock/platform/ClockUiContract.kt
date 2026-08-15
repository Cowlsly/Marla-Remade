package com.vayunmathur.clock.platform

import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.Timer
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The UI contract between [ClockViewModel] and the four tabs.
 *
 * Screens take a state value plus an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from (see `src/screenshotTest`). It lives in `util` rather than `ui` so the
 * dependency runs one way: `ui` depends on `util`, never the reverse.
 *
 * Every actions method has a no-op default body, so `Noop` is the whole implementation a
 * preview needs. Anything the screens would otherwise read from a [android.content.Context]
 * — the 12h/24h setting, the wall clock, the device time zone — is resolved by the binder
 * and passed in as state, which is also what makes a preview reproducible.
 */

/** Everything the alarm list draws. */
data class AlarmUiState(
    val alarms: List<Alarm> = emptyList(),
    val is24Hour: Boolean = false,
)

/** Alarm list callbacks. All of them persist the edited alarm and re-arm its schedule. */
interface AlarmActions {
    fun setName(alarm: Alarm, name: String) {}
    fun setTime(alarm: Alarm, time: LocalTime) {}
    fun setEnabled(alarm: Alarm, enabled: Boolean) {}
    fun setDays(alarm: Alarm, days: Int) {}
    fun delete(alarm: Alarm) {}

    /** Open the system ringtone picker for [alarm]; the result is applied by the binder. */
    fun pickRingtone(alarm: Alarm) {}
    fun setVibrate(alarm: Alarm, vibrate: Boolean) {}
    fun setSnoozeMinutes(alarm: Alarm, minutes: Int) {}
    fun setGradualVolumeSeconds(alarm: Alarm, seconds: Int) {}

    companion object {
        val Noop: AlarmActions = object : AlarmActions {}
    }
}

/** One city on the world-clock list, with its zone already resolved from the city name. */
data class WorldClock(val city: String, val zone: TimeZone)

/** Everything the clock tab draws. */
data class ClockUiState(
    val now: Instant,
    val zone: TimeZone,
    val is24Hour: Boolean = false,
    val worldClocks: List<WorldClock> = emptyList(),
)

/** Everything the timer tab draws. */
data class TimerUiState(
    val now: Instant,
    val timers: List<Timer> = emptyList(),
)

/** Timer callbacks. Each also posts or clears the timer's countdown notification. */
interface TimerActions {
    fun start(duration: Duration, name: String) {}
    fun delete(timer: Timer) {}
    fun addMinute(timer: Timer) {}
    fun reset(timer: Timer) {}

    /** Play/pause, or restart a timer that has already finished. */
    fun toggle(timer: Timer) {}

    companion object {
        val Noop: TimerActions = object : TimerActions {}
    }
}

/** Remaining duration for [timer] at instant [now], clamped to >= 0. */
fun timerRemaining(timer: Timer, now: Instant): Duration {
    val raw = if (timer.isRunning) {
        timer.remainingLength - (now - timer.remainingStartTime)
    } else {
        timer.remainingLength
    }
    return raw.coerceAtLeast(Duration.ZERO)
}

/** Everything the stopwatch tab draws. */
data class StopwatchUiState(
    val isRunning: Boolean = false,
    val countingTime: Duration = Duration.ZERO,
    /** Cumulative elapsed time at each lap, oldest first. */
    val lapTimes: List<Duration> = emptyList(),
)

/** Stopwatch callbacks. [ClockViewModel] implements these directly. */
interface StopwatchActions {
    fun toggleStopwatch() {}
    fun resetStopwatch() {}
    fun addLap() {}

    companion object {
        val Noop: StopwatchActions = object : StopwatchActions {}
    }
}
