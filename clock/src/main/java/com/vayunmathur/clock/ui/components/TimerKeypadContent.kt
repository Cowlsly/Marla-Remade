package com.vayunmathur.clock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.clock.R
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun TimerKeypadContent(
    paddingValues: PaddingValues,
    onStart: (Duration, String) -> Unit,
    onCancel: () -> Unit,
    showCancel: Boolean,
    initialInput: String = ""
) {
    var input by remember { mutableStateOf(initialInput) }
    var timerName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
            val padded = input.padStart(6, '0')
            TimeUnitDisplay(padded.substring(0, 2), "h", input.length >= 5)
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            TimeUnitDisplay(padded.substring(2, 4), "m", input.length >= 3)
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            TimeUnitDisplay(padded.substring(4, 6), "s", input.isNotEmpty())
        }

        OutlinedTextField(
            value = timerName,
            onValueChange = { timerName = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.timer_name_optional)) },
            singleLine = true
        )

        val appendDigits: (String) -> Unit = { input = (input + it).takeLast(6).trimStart('0') }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            KeypadRow("1", "2", "3", appendDigits)
            KeypadRow("4", "5", "6", appendDigits)
            KeypadRow("7", "8", "9", appendDigits)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                KeypadButton("00", Modifier.weight(1f)) { appendDigits("00") }
                KeypadButton("0", Modifier.weight(1f)) { appendDigits("0") }
                KeypadButton("\u232B", Modifier.weight(1f)) { input = input.dropLast(1) }
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            if (showCancel) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.CenterStart).size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                ) { IconClose() }
            }
            val duration = remember(input) {
                val padded = input.padStart(6, '0')
                val h = padded.substring(0, 2).toIntOrNull() ?: 0
                val m = padded.substring(2, 4).toIntOrNull() ?: 0
                val s = padded.substring(4, 6).toIntOrNull() ?: 0
                (h.hours + m.minutes + s.seconds)
            }
            IconButton(
                onClick = { if (duration.inWholeSeconds > 0) onStart(duration, timerName) },
                enabled = duration.inWholeSeconds > 0,
                modifier = Modifier.align(Alignment.Center).size(80.dp).clip(CircleShape).background(if (duration.inWholeSeconds > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            ) {
                IconPlay(tint = if (duration.inWholeSeconds > 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
            }
        }
    }
}
