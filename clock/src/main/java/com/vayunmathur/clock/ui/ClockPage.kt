package com.vayunmathur.clock.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.util.ClockUiState
import com.vayunmathur.clock.util.ClockViewModel
import com.vayunmathur.clock.util.WorldClock
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.vayunmathur.library.ui.DateString

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
            // A saved city with no entry in the city -> zone map is dropped, as before.
            worldClocks = timeZones.toList().mapNotNull { city ->
                cities?.get(city)?.let { WorldClock(city, TimeZone.of(it)) }
            },
        ),
    )
}

/**
 * The clock tab, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockScreen(backStack: NavBackStack<Route>, state: ClockUiState) {
    val time = state.now.toLocalDateTime(state.zone)
    Scaffold(floatingActionButton = {
        FloatingActionButton({
            backStack.add(Route.SelectTimeZonesDialog)
        }) { IconAdd() }
    }) { paddingValues ->
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = paddingValues, verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                val is24h = state.is24Hour
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(DateString.timeSecondsNumeric(time.time, is24h), style = MaterialTheme.typography.displayLarge)
                    if (!is24h) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (time.time.hour >= 12) stringResource(R.string.time_pm) else stringResource(R.string.time_am),
                            style = MaterialTheme.typography.displayMedium
                        )
                    }
                }
            }
            item {
                Text(DateString.dateWeekdayNoYear(time.date))
            }
            items(state.worldClocks) { worldClock ->
                val timeHere = state.now.toLocalDateTime(worldClock.zone)
                val is24h = state.is24Hour
                val amPm = if (is24h) "" else if (timeHere.time.hour >= 12) stringResource(R.string.time_pm) else stringResource(R.string.time_am)
                Card {
                    ListItem({Text(worldClock.city)}, trailingContent = {
                        Text(DateString.timeNumeric(timeHere.time, is24h) + amPm)
                    }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
        }
    }
}