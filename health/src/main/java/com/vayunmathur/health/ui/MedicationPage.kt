package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.domain.DoseSchedule
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.ui.components.MedicalStorageNotice
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconMedication
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SwipeActionsBox
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.itemMotion
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.library.util.NavBackStack
import kotlin.time.Clock

/**
 * The medication log, split into what the user is taking now and what they have finished.
 *
 * Same Room-first arrangement as [VaccinationsPage]: rows here may have been typed in this app or
 * imported from a pharmacy through Health Connect, and they render identically either way. The
 * import runs on every appearance, so there is no refresh control and no top bar to hang one on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationPage(backStack: NavBackStack<Route>, viewModel: MedicalViewModel) {
    val entries by viewModel.medications.collectAsState()
    var pendingDelete by remember { mutableStateOf<MedicationEntry?>(null) }

    LaunchedEffect(Unit) { viewModel.importFromHealthConnect() }

    val schedules by viewModel.schedules.collectAsState()
    val current = entries.filter { it.status == MedicationStatus.Active }
    val past = entries.filter { it.status != MedicationStatus.Active }

    fun openEditor(entry: MedicationEntry) {
        viewModel.startMedicationDraft(entry.id)
        backStack.add(Route.EditMedication(entry.id))
    }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_medication_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteMedication(entry)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    LazyListScaffold(
        scrollBehavior = appBarScrollBehavior(),
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.startMedicationDraft()
                    backStack.add(Route.EditMedication())
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
                    title = stringResource(R.string.no_medications),
                    message = stringResource(R.string.no_medications_message),
                    icon = { IconMedication() },
                )
            }
        }

        if (current.isNotEmpty() && past.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.medication_current)) }
        }
        items(current, key = { it.id }) { entry ->
            SwipeActionsBox(
                modifier = itemMotion(),
                enableStartToEnd = false,
                onEndToStart = { pendingDelete = entry },
            ) { MedicationCard(entry, schedules[entry.id]) { openEditor(entry) } }
        }

        if (past.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.medication_past)) }
        }
        items(past, key = { it.id }) { entry ->
            SwipeActionsBox(
                modifier = itemMotion(),
                enableStartToEnd = false,
                onEndToStart = { pendingDelete = entry },
            ) { MedicationCard(entry, schedules[entry.id]) { openEditor(entry) } }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun MedicationCard(
    entry: MedicationEntry,
    schedule: MedicationSchedule?,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        ListItem(
            headlineContent = {
                Text(listOfNotNull(entry.displayName, entry.strength).joinToString(" "))
            },
            overlineContent = { Text(medicationPeriod(entry)) },
            supportingContent = {
                Text(detailLine(entry.doseForm, entry.dosageText, nextDoseText(schedule)))
            },
            leadingContent = { IconMedication(tint = HealthColors.Medical) },
            trailingContent = { Text(stringResource(entry.status.selectorLabelRes())) },
        )
    }
}

/** "Next dose …" for a scheduled medication, or null when it has no reminders. */
@Composable
private fun nextDoseText(schedule: MedicationSchedule?): String? {
    if (schedule == null || !schedule.enabled) return null
    val next = DoseSchedule.nextDose(schedule, Clock.System.now()) ?: return null
    return stringResource(R.string.next_dose, DateString.dateTime(next, rememberIs24Hour()))
}

@Composable
private fun medicationPeriod(entry: MedicationEntry): String {
    val start = medicalDateString(entry.startedAt)
    val end = entry.endedAt ?: return start
    return stringResource(R.string.date_range, start, medicalDateString(end))
}

internal fun MedicationStatus.selectorLabelRes() = when (this) {
    MedicationStatus.Active -> R.string.medication_status_active
    MedicationStatus.Completed -> R.string.medication_status_completed
    MedicationStatus.Stopped -> R.string.medication_status_stopped
}
