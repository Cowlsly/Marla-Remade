package com.vayunmathur.clock.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.clock.R
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.ringtoneTitle

val SNOOZE_OPTIONS = listOf(1, 5, 10, 15, 20, 30)
val GRADUAL_OPTIONS = listOf(0, 5, 15, 30, 60)

fun gradualLabel(context: android.content.Context, seconds: Int): String = if (seconds <= 0) context.getString(R.string.gradual_off) else "${seconds}s"

@Composable
fun AlarmOptionControls(
    ringtoneUri: String?,
    vibrate: Boolean,
    snoozeMinutes: Int,
    gradualVolumeSeconds: Int,
    onRingtoneClick: () -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onSnoozeChange: (Int) -> Unit,
    onGradualChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OptionRow(label = stringResource(R.string.sound)) {
            TextButton(onClick = onRingtoneClick) { Text(ringtoneTitle(context, ringtoneUri)) }
        }
        OptionRow(label = stringResource(R.string.vibrate)) {
            Switch(checked = vibrate, onCheckedChange = onVibrateChange)
        }
        OptionRow(label = stringResource(R.string.snooze_length)) {
            OptionDropdown(
                value = "$snoozeMinutes min",
                options = SNOOZE_OPTIONS.map { it to "$it min" },
                onSelect = onSnoozeChange,
            )
        }
        OptionRow(label = stringResource(R.string.gradually_increase_volume)) {
            OptionDropdown(
                value = gradualLabel(context, gradualVolumeSeconds),
                options = GRADUAL_OPTIONS.map { it to gradualLabel(context, it) },
                onSelect = onGradualChange,
            )
        }
    }
}

@Composable
private fun OptionRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        trailing()
    }
}

@Composable
private fun OptionDropdown(value: String, options: List<Pair<Int, String>>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(value) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}
