package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.PregnancyStatus
import com.vayunmathur.health.data.SmokingStatus
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.ui.components.MedicalStorageNotice
import com.vayunmathur.health.ui.components.PickerField
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FormSection
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsExposedSelectRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.dialog.DateSelection
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private const val DUE_DATE_KEY = "about-you/due-date"

/**
 * The standing facts about the user: pregnancy status and smoking status.
 *
 * These are the two categories Health Connect models as observations of a *current state* rather
 * than events, so they are a short form of selectors rather than a log. Both are things a clinician
 * checks before prescribing or screening, which is why they get their own screen and their own
 * consent toggles rather than being buried in the conditions list.
 *
 * Each change writes a new dated observation rather than editing the previous one — see
 * `MedicalViewModel.setPregnancyStatus`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutYouPage(backStack: NavBackStack<Route>, viewModel: MedicalViewModel) {
    val profile by viewModel.profile.collectAsState()

    LaunchedEffect(Unit) { viewModel.importFromHealthConnect() }

    ResultEffect<DateSelection>(DUE_DATE_KEY) { picked ->
        viewModel.setPregnancyStatus(profile.pregnancyStatus, picked.date)
    }

    LazyListScaffold(
        title = stringResource(R.string.about_you),
        scrollBehavior = appBarScrollBehavior(),
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!viewModel.healthConnectAvailable) {
            item { MedicalStorageNotice() }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                FormSection(
                    title = stringResource(R.string.section_pregnancy),
                    modifier = Modifier.padding(16.dp),
                ) {
                    val labels = PregnancyStatus.entries.associateWith {
                        stringResource(it.labelRes())
                    }
                    SettingsExposedSelectRow(
                        label = stringResource(R.string.field_pregnancy_status),
                        selected = profile.pregnancyStatus,
                        options = PregnancyStatus.entries,
                        itemLabel = { labels.getValue(it) },
                        onSelect = { picked ->
                            viewModel.setPregnancyStatus(
                                picked,
                                // Clearing the status clears the date with it: an expected delivery
                                // date on a "not pregnant" record is contradictory.
                                if (picked == PregnancyStatus.Pregnant) {
                                    profile.dueDate?.toLocalDate()
                                } else {
                                    null
                                },
                            )
                        },
                    )
                    if (profile.pregnancyStatus == PregnancyStatus.Pregnant) {
                        PickerField(
                            label = stringResource(R.string.field_due_date),
                            value = profile.dueDate?.let { medicalDateString(it) }.orEmpty(),
                            placeholder = stringResource(R.string.not_recorded),
                            onClick = {
                                backStack.add(
                                    Route.MedicalDatePicker(
                                        DUE_DATE_KEY,
                                        profile.dueDate?.toLocalDate() ?: today(),
                                        allowClear = true,
                                    )
                                )
                            },
                        )
                    }
                    profile.pregnancyRecordedAt?.let {
                        RecordedLine(medicalDateString(it))
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                FormSection(
                    title = stringResource(R.string.section_smoking),
                    modifier = Modifier.padding(16.dp),
                ) {
                    val labels = SmokingStatus.entries.associateWith {
                        stringResource(it.labelRes())
                    }
                    SettingsExposedSelectRow(
                        label = stringResource(R.string.field_smoking_status),
                        selected = profile.smokingStatus,
                        options = SmokingStatus.entries,
                        itemLabel = { labels.getValue(it) },
                        onSelect = { picked -> viewModel.setSmokingStatus(picked) },
                    )
                    profile.smokingRecordedAt?.let {
                        RecordedLine(medicalDateString(it))
                    }
                }
            }
        }
    }
}

/** When the answer above was last given. These are dated observations, not settings. */
@Composable
private fun RecordedLine(date: String) {
    Text(
        stringResource(R.string.recorded_on, date),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun today() =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

internal fun PregnancyStatus.labelRes() = when (this) {
    PregnancyStatus.Unknown -> R.string.pregnancy_unknown
    PregnancyStatus.Pregnant -> R.string.pregnancy_pregnant
    PregnancyStatus.NotPregnant -> R.string.pregnancy_not_pregnant
}

internal fun SmokingStatus.labelRes() = when (this) {
    SmokingStatus.Unknown -> R.string.smoking_unknown
    SmokingStatus.Never -> R.string.smoking_never
    SmokingStatus.Former -> R.string.smoking_former
    SmokingStatus.Current -> R.string.smoking_current
}
