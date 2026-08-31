package com.vayunmathur.appstore.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.appstore.util.UpdatesActions
import com.vayunmathur.appstore.util.UpdatesUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior

/** Binds [AppStoreViewModel] to the stateless [UpdatesScreen]. */
@Composable
fun UpdatesPage(viewModel: AppStoreViewModel, onAppClick: (UnifiedApp) -> Unit) {
    val state by viewModel.updatesUi.collectAsState()
    // Opening the tab is the request. The old screen made the user tap "Sync F-Droid" and
    // then "Check Play" by hand, which is not a thing anyone remembers to do.
    LaunchedEffect(Unit) {
        if (state.lastCheckedAt == 0L) viewModel.checkForUpdates()
    }
    UpdatesScreen(state = state, actions = viewModel, onAppClick = onAppClick)
}

@Composable
fun UpdatesScreen(
    state: UpdatesUiState,
    actions: UpdatesActions,
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    val anyInstalling = state.stages.values.any { it !is InstallStage.Failed }

    AppScaffold(
        title = {},
        actions = {
            if (state.isChecking) {
                CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp))
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.updates.isNotEmpty()) {
                    Button(
                        onClick = { actions.updateAll() },
                        enabled = !anyInstalling,
                        modifier = Modifier.weight(1f),
                    ) {
                        IconDownload()
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.update_all, state.updates.size))
                    }
                }
                OutlinedButton(
                    onClick = { actions.checkForUpdates() },
                    enabled = !state.isChecking,
                    modifier = if (state.updates.isEmpty()) Modifier.weight(1f) else Modifier,
                ) {
                    Text(stringResource(R.string.action_check_again))
                }
            }

            val status = state.statusMessage.takeIf { it.isNotBlank() }
                ?: state.lastCheckedAt.takeIf { it > 0 }?.let {
                    stringResource(
                        R.string.updates_last_checked,
                        DateUtils.getRelativeTimeSpanString(
                            it,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                        ),
                    )
                }
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (state.updates.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.all_apps_up_to_date),
                    message = stringResource(R.string.updates_empty_message),
                    icon = { IconCheck() },
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item("count") {
                        Text(
                            pluralStringResource(
                                R.plurals.updates_count,
                                state.updates.size,
                                state.updates.size,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(state.updates, key = { it.packageName }) { app ->
                        val stage = state.stages[app.packageName]
                        val installed = state.installedInfos[app.packageName]
                        val versionLabel = installed?.let { info ->
                            val old = info.versionName?.let { "$it (${info.versionCode})" }
                                ?: info.versionCode.toString()
                            val new = app.versionName?.let { "$it (${app.versionCode})" }
                                ?: app.versionCode.toString()
                            stringResource(R.string.version_update, old, new)
                        }
                        AppRow(
                            app = app,
                            isInstalled = true,
                            stage = stage,
                            installedIcon = state.installedIcons[app.packageName],
                            versionLabel = versionLabel,
                            onClick = { onAppClick(app) },
                            trailing = {
                                Button(
                                    onClick = { actions.install(app) },
                                    enabled = stage == null || stage is InstallStage.Failed,
                                ) {
                                    Text(stringResource(R.string.action_update))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
