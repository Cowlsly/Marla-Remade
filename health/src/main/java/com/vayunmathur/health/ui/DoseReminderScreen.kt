package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconMedication
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

/**
 * What a due dose looks like when it takes over the screen.
 *
 * Stateless and free of the activity so it can be rendered from a preview. Deliberately sparse:
 * this appears over the keyguard, often in the dark, and the only two things that matter are which
 * medication it is and the two buttons.
 */
@Composable
fun DoseReminderScreen(
    medicationName: String,
    amount: String?,
    onTaken: () -> Unit,
    onSnooze: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            IconMedication(tint = HealthColors.Medical)
            Text(
                stringResource(R.string.dose_due_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                medicationName,
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!amount.isNullOrBlank()) {
                Text(
                    amount,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(
                onClick = onTaken,
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
            ) { Text(stringResource(R.string.dose_taken)) }
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text(stringResource(R.string.dose_snooze)) }
        }
    }
}
