package com.vayunmathur.clock.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.platform.ClockUiState
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.platform.WorldClock
import com.vayunmathur.clock.platform.WorldClockCities
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.rememberClock
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** Binds [ClockViewModel] to the stateless [ClockScreen]. */
@Composable
fun ClockPage(backStack: NavBackStack<Route>, ds: DataStoreUtils, clockViewModel: ClockViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The clock tab shows seconds in the header and minutes in the rows, so it ticks once a
    // second - not on the ViewModel's 100ms stopwatch flow, which this used to share. A getter
    // rather than a value so the tick reaches only the labels that display it.
    val clock = rememberClock()
    val now = remember(clock) { { Instant.fromEpochMilliseconds(clock()) } }
    val cities by clockViewModel.cities.collectAsState()
    val selectedCities by WorldClockCities.flow(ds).collectAsState(listOf())

    ClockScreen(
        backStack = backStack,
        state = ClockUiState(
            now = now,
            zone = TimeZone.currentSystemDefault(),
            is24Hour = DateFormat.is24HourFormat(context),
            worldClocks = selectedCities.mapNotNull { city ->
                cities?.get(city)?.let { WorldClock(city, TimeZone.of(it)) }
            },
        ),
        onReorder = { reordered -> scope.launch { WorldClockCities.set(ds, reordered) } },
    )
}
