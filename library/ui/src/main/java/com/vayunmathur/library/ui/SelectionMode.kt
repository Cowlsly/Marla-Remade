package com.vayunmathur.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * Multi-select state for a list screen.
 *
 * contacts, notes, photos and youpipe each implemented this: a set of selected
 * ids, a derived "is anything selected" flag, select-all and clear, and a
 * contextual top bar showing the count. They disagreed on the details - in
 * particular whether the system back button clears the selection, which it
 * should, and which only some of them did.
 *
 * Backed by a Compose [MutableState] so that reading [count] or [isActive]
 * subscribes the caller to changes; a plain field would leave the top bar
 * showing a stale count.
 */
class SelectionState<T> internal constructor(private val state: MutableState<Set<T>>) {

    val selected: Set<T> get() = state.value
    val count: Int get() = state.value.size
    val isActive: Boolean get() = state.value.isNotEmpty()

    fun isSelected(id: T): Boolean = id in state.value

    fun toggle(id: T) {
        state.value = if (id in state.value) state.value - id else state.value + id
    }

    fun select(id: T) { state.value = state.value + id }

    fun selectAll(ids: Collection<T>) { state.value = ids.toSet() }

    fun clear() { state.value = emptySet() }
}

/**
 * Remembers a [SelectionState].
 *
 * Plain `remember`, not `rememberSaveable`: an arbitrary id type has no
 * automatic Saver, and every activity here already declares `configChanges`
 * covering orientation, so it is not recreated on rotation and the selection
 * survives anyway.
 */
@Composable
fun <T> rememberSelectionState(): SelectionState<T> {
    val state = remember { mutableStateOf(emptySet<T>()) }
    return remember(state) { SelectionState(state) }
}

/**
 * Contextual app bar shown while a selection is active.
 *
 * Handles the back button itself: back clears the selection rather than
 * leaving the screen, which is what a user expects and what some of the
 * hand-rolled versions missed.
 */
@Composable
fun <T> SelectionTopAppBar(
    state: SelectionState<T>,
    countLabel: (Int) -> String,
    actions: @Composable RowScope.() -> Unit = {},
) {
    BackHandler(enabled = state.isActive) { state.clear() }
    TopAppBar(
        title = { Text(countLabel(state.count)) },
        navigationIcon = { IconNavigation({ state.clear() }) },
        actions = actions,
    )
}
