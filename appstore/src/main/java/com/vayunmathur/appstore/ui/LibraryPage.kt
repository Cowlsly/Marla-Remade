package com.vayunmathur.appstore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.appstore.util.LibraryActions
import com.vayunmathur.appstore.util.LibraryUiState
import com.vayunmathur.appstore.util.SourceFilter
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.Text

/** Binds [AppStoreViewModel] to the stateless [LibraryScreen]. */
@Composable
fun LibraryPage(viewModel: AppStoreViewModel, onAppClick: (UnifiedApp) -> Unit) {
    val state by viewModel.library.collectAsState()
    LibraryScreen(state = state, actions = viewModel, onAppClick = onAppClick)
}

/**
 * Every app installed on the device, grouped by which source offers it.
 *
 * Uninstall lives only on an app's own page — a delete button next to every row in a long
 * list is too easy to hit by accident.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    AppScaffold(
        title = stringResource(
            R.string.library_title,
            state.counts[SourceFilter.ALL] ?: state.apps.size,
        ),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SourceFilter.entries.toList(), key = { it.name }) { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { actions.setLibraryFilter(filter) },
                        label = {
                            Text(
                                stringResource(
                                    R.string.filter_with_count,
                                    stringResource(filter.labelRes()),
                                    state.counts[filter] ?: 0,
                                )
                            )
                        },
                    )
                }
            }

            if (state.apps.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.library_empty_title),
                    message = stringResource(R.string.library_empty_message),
                    icon = { IconPackage() },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.apps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            isInstalled = true,
                            installedIcon = state.installedIcons[app.packageName],
                            onClick = { onAppClick(app) },
                        )
                    }
                }
            }
        }
    }
}
