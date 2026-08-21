package com.vayunmathur.clock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.platform.ClockUiState
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import kotlinx.datetime.toLocalDateTime

/**
 * The clock tab, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockScreen(backStack: NavBackStack<Route>, state: ClockUiState) {
    val time = state.now.toLocalDateTime(state.zone)
    LazyListScaffold(floatingActionButton = {
        FloatingActionButton({
            backStack.add(Route.SelectTimeZonesDialog)
        }) { IconAdd() }
    }, verticalArrangement = Arrangement.spacedBy(4.dp), scrollBehavior = appBarScrollBehavior()) {
        item {
            val is24h = state.is24Hour
            Row(Modifier.fillParentMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
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
            Text(DateString.dateWeekdayNoYear(time.date), Modifier.fillParentMaxWidth(), textAlign = TextAlign.Center)
        }
        items(state.worldClocks) { worldClock ->
            val timeHere = state.now.toLocalDateTime(worldClock.zone)
            val is24h = state.is24Hour
            val amPm = if (is24h) "" else if (timeHere.time.hour >= 12) stringResource(R.string.time_pm) else stringResource(R.string.time_am)
            Card(Modifier.fillParentMaxWidth()) {
                ListItem({Text(worldClock.city)}, trailingContent = {
                    Text(DateString.timeNumeric(timeHere.time, is24h) + amPm)
                }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    }
}
