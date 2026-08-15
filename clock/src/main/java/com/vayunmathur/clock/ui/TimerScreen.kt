package com.vayunmathur.clock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.ui.components.TimerCard
import com.vayunmathur.clock.ui.components.TimerKeypadContent
import com.vayunmathur.clock.platform.TimerActions
import com.vayunmathur.clock.platform.TimerUiState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.util.NavBackStack

/**
 * The timer tab — keypad while there is nothing to show, countdown cards otherwise — with
 * no dependency on the ViewModel so it can be rendered from a `@Preview`. See
 * `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(
    backStack: NavBackStack<Route>,
    state: TimerUiState,
    actions: TimerActions,
    initialAddingTimer: Boolean = false,
    initialKeypadInput: String = "",
) {
    val timers = state.timers
    var isAddingTimer by remember { mutableStateOf(initialAddingTimer) }
    val showKeypad = timers.isEmpty() || isAddingTimer
    Scaffold(
        floatingActionButton = {
            if (!showKeypad) {
                FloatingActionButton({ isAddingTimer = true }) { IconAdd() }
            }
        }
    ) { paddingValues ->
        if (showKeypad) {
            TimerKeypadContent(
                paddingValues = paddingValues,
                onStart = { duration, name -> actions.start(duration, name); isAddingTimer = false },
                onCancel = { if (timers.isNotEmpty()) isAddingTimer = false },
                showCancel = timers.isNotEmpty(),
                initialInput = initialKeypadInput
            )
        } else {
            LazyColumn(
                contentPadding = paddingValues + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(timers, key = { it.id }) { timer -> TimerCard(timer, state.now, actions) }
            }
        }
    }
}
