package com.vayunmathur.health.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.MedicalAttachment
import com.vayunmathur.health.data.VaccinationEntry
import com.vayunmathur.health.platform.AttachmentStore
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.ui.components.AttachmentChip
import com.vayunmathur.health.ui.components.MedicalStorageNotice
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconVaccine
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SwipeActionsBox
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.itemMotion
import com.vayunmathur.library.util.NavBackStack

/**
 * The vaccination log — every immunisation, newest first.
 *
 * Entries come from Room, which holds both what the user typed here and anything imported out of
 * Health Connect, so the list is identical in shape whether or not this device supports FHIR
 * records. The banner is the only thing that changes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VaccinationsPage(backStack: NavBackStack<Route>, viewModel: MedicalViewModel) {
    val entries by viewModel.vaccinations.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<VaccinationEntry?>(null) }

    LaunchedEffect(Unit) { viewModel.importFromHealthConnect() }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_vaccination_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteVaccination(entry)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    LazyListScaffold(
        title = stringResource(R.string.vaccinations),
        scrollBehavior = appBarScrollBehavior(),
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        actions = {
            if (viewModel.healthConnectAvailable) {
                IconButton(onClick = { viewModel.importFromHealthConnect() }) { IconRefresh() }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.startVaccinationDraft()
                    backStack.add(Route.EditVaccination())
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
                    title = stringResource(R.string.no_vaccinations),
                    message = stringResource(R.string.no_vaccinations_message),
                    icon = { IconVaccine() },
                )
            }
        }

        items(entries, key = { it.id }) { entry ->
            SwipeActionsBox(
                modifier = itemMotion(),
                enableStartToEnd = false,
                onEndToStart = { pendingDelete = entry },
            ) {
                VaccinationCard(
                    entry = entry,
                    attachments = attachments[entry.id].orEmpty(),
                    context = context,
                    onClick = {
                        viewModel.startVaccinationDraft(entry.id)
                        backStack.add(Route.EditVaccination(entry.id))
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VaccinationCard(
    entry: VaccinationEntry,
    attachments: List<MedicalAttachment>,
    context: Context,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column {
            ListItem(
                headlineContent = { Text(entry.displayName) },
                overlineContent = { Text(medicalDateString(entry.occurredAt)) },
                supportingContent = entry.cvxCode?.let { code ->
                    { Text(stringResource(R.string.cvx_code, code)) }
                },
                leadingContent = { IconVaccine(tint = HealthColors.Medical) },
            )
            if (attachments.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachments.forEach { attachment ->
                        AttachmentChip(
                            displayName = attachment.displayName,
                            mimeType = attachment.mimeType,
                            file = AttachmentStore.fileFor(context, attachment.fileName),
                            onClick = {
                                ExternalIntents.openFile(
                                    context,
                                    AttachmentStore.uriFor(context, attachment.fileName),
                                    attachment.mimeType,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
