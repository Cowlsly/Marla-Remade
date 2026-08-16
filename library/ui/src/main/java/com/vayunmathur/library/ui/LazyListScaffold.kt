package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A scaffold whose body is a single lazily-composed list.
 *
 * The scaffold's [PaddingValues] are forwarded as the [LazyColumn]'s
 * `contentPadding` on all four sides, so items scroll clear of the bars and
 * nothing double-insets or drops the bottom inset. Only a symmetric
 * [horizontalPadding] is folded onto the forwarded start/end (as
 * [DetailScaffold]'s list does with `Spacing.lg`); the top and bottom are always
 * exactly the scaffold's own insets.
 *
 * Use this for a list screen that supplies its own [topBar]/[floatingActionButton]
 * and custom [content] items - grouped cards, headers, selection rows - that the
 * flat-item [ListPage] cannot express.
 */
@Composable
fun LazyListScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    horizontalPadding: Dp = 0.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
    ) { innerPadding ->
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = horizontalPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}
