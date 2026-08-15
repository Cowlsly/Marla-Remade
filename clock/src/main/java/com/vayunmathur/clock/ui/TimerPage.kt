package com.vayunmathur.clock.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.ui.components.sendTimerNotification
import com.vayunmathur.clock.util.ClockViewModel
import com.vayunmathur.clock.util.TimerActions
import com.vayunmathur.clock.util.TimerUiState
import com.vayunmathur.library.util.NavBackStack
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Binds [ClockViewModel] to the stateless [TimerScreen]. */
@Composable
fun TimerPage(backStack: NavBackStack<Route>, clockViewModel: ClockViewModel) {
    val now by clockViewModel.now.collectAsState()
    val timers by clockViewModel.timers.collectAsState()
    val context = LocalContext.current

    val actions = remember(clockViewModel, context) {
        object : TimerActions {
            override fun start(duration: Duration, name: String) {
                val timer = Timer(true, name, Clock.System.now(), duration, duration)
                clockViewModel.upsert(timer) { sendTimerNotification(context, timer.copy(id = it), true) }
            }
            override fun delete(timer: Timer) { sendTimerNotification(context, timer, false); clockViewModel.delete(timer) }
            override fun addMinute(timer: Timer) {
                val updated = timer.copy(remainingLength = timer.remainingLength + 1.minutes, totalLength = timer.totalLength + 1.minutes)
                clockViewModel.upsert(updated)
                if (timer.isRunning) sendTimerNotification(context, updated, true)
            }
            override fun reset(timer: Timer) {
                clockViewModel.upsert(timer.copy(isRunning = false, remainingLength = timer.totalLength, remainingStartTime = Clock.System.now()))
                sendTimerNotification(context, timer, false)
            }
            override fun toggle(timer: Timer) {
                when {
                    !timer.isRunning && timer.remainingLength <= Duration.ZERO -> {
                        val restarted = timer.copy(isRunning = true, remainingLength = timer.totalLength, remainingStartTime = Clock.System.now())
                        clockViewModel.upsert(restarted); sendTimerNotification(context, restarted, true)
                    }
                    timer.isRunning -> { clockViewModel.upsert(timer.stopped()); sendTimerNotification(context, timer, false) }
                    else -> { val started = timer.started(); clockViewModel.upsert(started); sendTimerNotification(context, started, true) }
                }
            }
        }
    }

    TimerScreen(backStack = backStack, state = TimerUiState(now = now, timers = timers), actions = actions)
}
