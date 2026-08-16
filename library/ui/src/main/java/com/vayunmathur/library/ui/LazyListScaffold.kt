@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A scaffold whose body is a single lazily-composed list.
 *
 * Collapses the `Scaffold { pad -> LazyColumn(contentPadding = ...) { ... } }`
 * combo every list screen was writing by hand, and owns the one detail those
 * copies kept getting wrong: the [LazyColumn]'s `contentPadding` is the
 * scaffold's own [PaddingValues] on *all four sides* (mirroring [DetailScaffold]'s
 * `DetailList`), so items scroll clear of the bars and nothing double-insets or
 * drops the bottom inset. Only a symmetric [horizontalPadding] is folded onto the
 * forwarded start/end; the top and bottom are always exactly the scaffold's.
 *
 * The caller supplies the chrome the list needs: an add-style [floatingActionButton]
 * and a swappable [topBar] - e.g. the normal-vs-selection top bar a multi-select
 * screen toggles between (see `SelectionMode.kt` for that pattern). Everything else
 * matches the shared [Scaffold] wrapper (same param names/types) so it stays a
 * drop-in replacement.
 */
@Composable
fun LazyListScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    state: LazyListState = rememberLazyListState(),
    horizontalPadding: Dp = 0.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    containerColor: Color = MaterialTheme.colorScheme.background,
    content: LazyListScope.() -> Unit,
) = Scaffold(
    modifier,
    topBar,
    bottomBar,
    snackbarHost,
    floatingActionButton,
    floatingActionButtonPosition,
    containerColor,
) { pad ->
    val dir = LocalLayoutDirection.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(
            start = pad.calculateStartPadding(dir) + horizontalPadding,
            end = pad.calculateEndPadding(dir) + horizontalPadding,
            top = pad.calculateTopPadding(),
            bottom = pad.calculateBottomPadding(),
        ),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
