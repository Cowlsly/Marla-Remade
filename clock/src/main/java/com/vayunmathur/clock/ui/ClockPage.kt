package com.vayunmathur.clock.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.util.ClockUiState
import com.vayunmathur.clock.util.ClockViewModel
import com.vayunmathur.clock.util.WorldClock
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import kotlinx.datetime.TimeZone

/** Binds [ClockViewModel] to the stateless [ClockScreen]. */
@Composable
fun ClockPage(backStack: NavBackStack<Route>, ds: DataStoreUtils, clockViewModel: ClockViewModel) {
    val context = LocalContext.current
    val now by clockViewModel.now.collectAsState()
    val cities by clockViewModel.cities.collectAsState()
    val timeZones by ds.stringSetFlow("time_zones").collectAsState(setOf())

    ClockScreen(
        backStack = backStack,
        state = ClockUiState(
            now = now,
            zone = TimeZone.currentSystemDefault(),
            is24Hour = DateFormat.is24HourFormat(context),
            worldClocks = timeZones.toList().mapNotNull { city ->
                cities?.get(city)?.let { WorldClock(city, TimeZone.of(it)) }
            },
        ),
    )
}
