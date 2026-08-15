package com.vayunmathur.clock.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.util.ClockViewModel
import com.vayunmathur.clock.util.StopwatchUiState
import com.vayunmathur.library.util.NavBackStack

/** Binds [ClockViewModel] to the stateless [StopwatchScreen]. */
@Composable
fun StopwatchPage(backStack: NavBackStack<Route>, clockViewModel: ClockViewModel) {
    val isRunning by clockViewModel.stopwatchRunning.collectAsState()
    val countingTime by clockViewModel.stopwatchCountingTime.collectAsState()
    val lapTimes by clockViewModel.lapTimes.collectAsState()

    StopwatchScreen(
        backStack = backStack,
        state = StopwatchUiState(isRunning = isRunning, countingTime = countingTime, lapTimes = lapTimes),
        actions = clockViewModel,
    )
}
