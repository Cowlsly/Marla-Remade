package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.ConditionStatus
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.ui.components.MedicalStorageNotice
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconMonitorHeart
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SwipeActionsBox
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.itemMotion
import com.vayunmathur.library.util.NavBackStack

/** The diagnosis list — a FHIR problem list, newest onset first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConditionsPage(backStack: NavBackStack<Route>, viewModel: MedicalViewModel) {
    val entries by viewModel.conditions.collectAsState()
    var pendingDelete by remember { mutableStateOf<ConditionEntry?>(null) }

    LaunchedEffect(Unit) { viewModel.importFromHealthConnect() }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_condition_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteCondition(entry)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    LazyListScaffold(
        title = stringResource(R.string.conditions),
        scrollBehavior = appBarScrollBehavior(),
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.startConditionDraft()
                    backStack.add(Route.EditCondition())
                }
            ) { IconAdd() }
        },
    ) {
        if (!viewModel.healthConnectAvailable) {
            item { MedicalStorageNotice() }
        }

        if (entries.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.no_conditions),
                    message = stringResource(R.string.no_conditions_message),
                    icon = { IconMonitorHeart() },
                )
            }
        }

        items(entries, key = { it.id }) { entry ->
            SwipeActionsBox(
                modifier = itemMotion(),
                enableStartToEnd = false,
                onEndToStart = { pendingDelete = entry },
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        viewModel.startConditionDraft(entry.id)
                        backStack.add(Route.EditCondition(entry.id))
                    },
                ) {
                    ListItem(
                        headlineContent = { Text(entry.displayName) },
                        overlineContent = { Text(medicalDateString(entry.onsetAt)) },
                        supportingContent = {
                            Text(detailLine(entry.icd10Code, entry.note))
                        },
                        leadingContent = { IconMonitorHeart(tint = HealthColors.Medical) },
                        trailingContent = { Text(stringResource(entry.status.labelRes())) },
                    )
                }
            }
        }
    }
}

internal fun ConditionStatus.labelRes() = when (this) {
    ConditionStatus.Active -> R.string.condition_status_active
    ConditionStatus.Recurrence -> R.string.condition_status_recurrence
    ConditionStatus.Remission -> R.string.condition_status_remission
    ConditionStatus.Resolved -> R.string.condition_status_resolved
}
