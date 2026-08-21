package com.vayunmathur.appstore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.SandboxedGooglePlay
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.appstore.util.HomeActions
import com.vayunmathur.appstore.util.HomeUiState
import com.vayunmathur.appstore.util.SectionLayout
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior

/** Binds [AppStoreViewModel] to the stateless [HomeScreen]. */
@Composable
fun HomePage(
    viewModel: AppStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenSources: () -> Unit,
) {
    val state by viewModel.home.collectAsState()
    HomeScreen(
        state = state,
        actions = viewModel,
        onAppClick = onAppClick,
        onOpenUpdates = onOpenUpdates,
        onOpenSources = onOpenSources,
    )
}

/**
 * The store front: a few curated rows rather than one undifferentiated list.
 *
 * The rows come from three places and are deliberately labelled as such — this repo's own
 * apps, Play's editorial clusters, and F-Droid's newest reproduced builds. Which source a
 * row comes from is information, not a verdict; see
 * [com.vayunmathur.appstore.data.security.TrustProfile].
 *
 * Stateless so it can be rendered from a `@Preview` — see `src/screenshotTest`, which is
 * where the store listing images come from.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    onAppClick: (UnifiedApp) -> Unit = {},
    onOpenUpdates: () -> Unit = {},
    onOpenSources: () -> Unit = {},
) {
    AppScaffold(
        title = stringResource(R.string.app_name),
        actions = {
            IconButton(onClick = { actions.refresh() }, enabled = !state.isSyncing) {
                IconRefresh()
            }
            IconButton(onClick = onOpenSources) { IconSettings() }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.isSyncing || state.statusMessage.isNotBlank()) {
                item("status") { StatusLine(state.statusMessage, state.isSyncing) }
            }

            if (state.updateCount > 0) {
                item("updates") { UpdatesBanner(state.updateCount, onOpenUpdates) }
            }

            if (state.categories.isNotEmpty()) {
                item("categories") {
                    CategoryRow(
                        categories = state.categories,
                        selected = state.selectedCategory,
                        onSelect = actions::selectCategory,
                    )
                }
            }

            if (state.sections.isEmpty()) {
                item("empty") {
                    if (state.isLoading) {
                        LoadingRow()
                    } else {
                        EmptyState(
                            title = stringResource(R.string.home_empty_title),
                            message = stringResource(R.string.home_empty_message),
                            icon = { IconPackage() },
                            modifier = Modifier.height(320.dp),
                        )
                    }
                }
            }

            state.sections.forEach { section ->
                item("${section.id}-header") {
                    if (section.id == SandboxedGooglePlay.SECTION_ID) {
                        SandboxedGooglePlayHeader(
                            title = section.title,
                            subtitle = section.subtitle,
                            allInstalled = section.apps.isNotEmpty() &&
                                section.apps.all { it.packageName in state.installedPackages },
                            onInstallAll = actions::installSandboxedGooglePlay,
                        )
                    } else {
                        SectionHeader(section.title, section.subtitle)
                    }
                }
                when (section.layout) {
                    SectionLayout.CAROUSEL -> item("${section.id}-body") {
                        AppCarousel(
                            apps = section.apps,
                            installedPackages = state.installedPackages,
                            installedIcons = state.installedIcons,
                            onAppClick = onAppClick,
                        )
                    }
                    SectionLayout.LIST -> items(
                        section.apps,
                        key = { "${section.id}-${it.packageName}" },
                    ) { app ->
                        AppRow(
                            app = app,
                            isInstalled = app.packageName in state.installedPackages,
                            stage = state.stages[app.packageName],
                            installedIcon = state.installedIcons[app.packageName],
                            onClick = { onAppClick(app) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SandboxedGooglePlayHeader(
    title: String,
    subtitle: String?,
    allInstalled: Boolean,
    onInstallAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeader(title, subtitle, Modifier.weight(1f))
        Button(
            onClick = onInstallAll,
            enabled = !allInstalled,
            modifier = Modifier.padding(end = 16.dp),
        ) {
            IconDownload()
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_install_all))
        }
    }
}

@Composable
private fun StatusLine(message: String, isSyncing: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSyncing) {
            CircularProgressIndicator(Modifier.size(14.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            message.ifBlank { stringResource(R.string.sync_in_progress) },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UpdatesBanner(count: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconDownload()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.updates_count, count, count),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.updates_banner_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconChevronRight()
        }
    }
}

@Composable
private fun CategoryRow(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("all") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.category_all)) },
            )
        }
        items(categories, key = { it }) { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(if (selected == category) null else category) },
                label = { Text(category) },
            )
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        Modifier.fillMaxWidth().height(160.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
    }
}
