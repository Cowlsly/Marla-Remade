package com.vayunmathur.health.ui.components

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.health.R
import com.vayunmathur.health.data.RepeatUnit
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.ui.medicalDateString
import com.vayunmathur.health.ui.toInstant
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.MultiCategoryPicker
import com.vayunmathur.library.ui.SettingsExposedSelectRow
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.ToggleButton
import com.vayunmathur.library.ui.rememberIs24Hour
import kotlinx.datetime.LocalTime

/**
 * The reminder schedule, edited inline on the medication form.
 *
 * Everything here writes straight into the ViewModel draft rather than into `remember`, because the
 * form is disposed whenever a picker opens over it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSection(
    draft: MedicalViewModel.MedicationDraft,
    onEdit: ((MedicalViewModel.MedicationDraft) -> MedicalViewModel.MedicationDraft) -> Unit,
    onPickTime: (index: Int) -> Unit,
    onPickUntil: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.remind_me))
        Switch(
            checked = draft.remindersEnabled,
            onCheckedChange = { on -> onEdit { it.copy(remindersEnabled = on) } },
        )
    }

    if (!draft.remindersEnabled) return

    // On Android 14+ this is auto-granted only to calling and alarm apps, so a health app starts
    // without it and the reminder would quietly be a heads-up notification instead of a takeover.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager != null && !manager.canUseFullScreenIntent()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.full_screen_intent_needed)) },
                    trailingContent = {
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                        "package:${context.packageName}".toUri(),
                                    )
                                )
                            }
                        ) { Text(stringResource(R.string.grant_full_screen)) }
                    },
                )
            }
        }
    }

    // Chips in a field, the same control the contacts app uses for categories — except that tapping
    // it opens the time picker rather than a menu, since a time of day does not come from a fixed
    // list. Tapping a chip removes that time.
    val is24Hour = rememberIs24Hour()
    MultiCategoryPicker(
        label = stringResource(R.string.dose_times),
        selected = draft.times,
        itemLabel = { seconds -> DateString.time(LocalTime.fromSecondOfDay(seconds), is24Hour) },
        onAddClick = { onPickTime(draft.times.size) },
        onRemove = { seconds ->
            // Never let the last one go: a schedule with no times can never fire, and an empty
            // field would read as a bug rather than a choice.
            if (draft.times.size > 1) {
                onEdit { d -> d.copy(times = d.times - seconds) }
            }
        },
        modifier = Modifier.padding(top = 8.dp),
    )

    val unitLabels = mapOf(
        RepeatUnit.Daily to stringResource(R.string.repeat_days),
        RepeatUnit.Weekly to stringResource(R.string.repeat_weeks),
    )
    LabeledTextField(
        value = draft.interval.toString(),
        onValueChange = { text ->
            val parsed = text.filter { it.isDigit() }.take(3).toIntOrNull() ?: 1
            onEdit { it.copy(interval = parsed.coerceIn(1, 365)) }
        },
        label = stringResource(R.string.repeat_every),
        keyboardType = KeyboardType.Number,
    )
    SettingsExposedSelectRow(
        label = stringResource(R.string.repeat_every),
        selected = draft.repeatUnit,
        options = RepeatUnit.entries,
        itemLabel = { unitLabels.getValue(it) },
        onSelect = { unit -> onEdit { it.copy(repeatUnit = unit) } },
    )

    if (draft.repeatUnit == RepeatUnit.Weekly) {
        Text(
            stringResource(R.string.on_these_days),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        val initials = listOf(
            R.string.day_initial_sunday,
            R.string.day_initial_monday,
            R.string.day_initial_tuesday,
            R.string.day_initial_wednesday,
            R.string.day_initial_thursday,
            R.string.day_initial_friday,
            R.string.day_initial_saturday,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            initials.forEachIndexed { bit, label ->
                val selected = (draft.daysOfWeek and (1 shl bit)) != 0
                ToggleButton(
                    checked = selected,
                    onCheckedChange = {
                        onEdit { it.copy(daysOfWeek = it.daysOfWeek xor (1 shl bit)) }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(label)) }
            }
        }
    }

    PickerField(
        label = stringResource(R.string.reminders_until),
        value = draft.remindersUntil?.let { medicalDateString(it.toInstant()) }.orEmpty(),
        placeholder = stringResource(R.string.no_end_date),
        onClick = onPickUntil,
    )
}
