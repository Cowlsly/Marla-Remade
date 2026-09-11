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
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.ui.components.MedicalStorageNotice
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconScience
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SwipeActionsBox
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.itemMotion
import com.vayunmathur.library.util.NavBackStack

/**
 * The lab results log, newest first.
 *
 * A result outside its own reference range is tinted with the error colour. That is the one thing
 * anyone scanning this list is looking for, and it is information the record already carries — the
 * range travels with the result in FHIR — so showing it costs nothing and hiding it would be perverse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabResultsPage(backStack: NavBackStack<Route>, viewModel: MedicalViewModel) {
    val entries by viewModel.labResults.collectAsState()
    var pendingDelete by remember { mutableStateOf<LabResultEntry?>(null) }

    LaunchedEffect(Unit) { viewModel.importFromHealthConnect() }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_lab_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteLabResult(entry)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    LazyListScaffold(
        title = stringResource(R.string.lab_results),
        scrollBehavior = appBarScrollBehavior(),
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.startLabDraft()
                    backStack.add(Route.EditLabResult())
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
                    title = stringResource(R.string.no_lab_results),
                    message = stringResource(R.string.no_lab_results_message),
                    icon = { IconScience() },
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
                        viewModel.startLabDraft(entry.id)
                        backStack.add(Route.EditLabResult(entry.id))
                    },
                ) {
                    ListItem(
                        headlineContent = { Text(entry.displayName) },
                        overlineContent = { Text(medicalDateString(entry.takenAt)) },
                        supportingContent = { Text(detailLine(referenceText(entry), entry.note)) },
                        leadingContent = { IconScience(tint = HealthColors.Medical) },
                        trailingContent = {
                            Text(
                                resultText(entry),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (entry.isOutOfRange) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

/** "5.6 %" for a numeric result, or the qualitative word for one that isn't. */
private fun resultText(entry: LabResultEntry): String {
    val numeric = entry.value ?: return entry.valueText.orEmpty()
    val shown = if (numeric == numeric.toLong().toDouble()) {
        numeric.toLong().toString()
    } else {
        numeric.toString()
    }
    return listOfNotNull(shown, entry.unit?.takeIf { it.isNotBlank() }).joinToString(" ")
}

/** "4.0 – 5.6" when the result carries a reference range, else null. */
private fun referenceText(entry: LabResultEntry): String? {
    val low = entry.referenceLow
    val high = entry.referenceHigh
    if (low == null && high == null) return null
    return "${low ?: ""} – ${high ?: ""}".trim()
}
