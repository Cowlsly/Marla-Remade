package com.vayunmathur.games.pipes.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.pipes.R
import com.vayunmathur.games.pipes.platform.DailyProgress
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DailyCard(daily: DailyProgress, onOpen: () -> Unit) {
    Card(Modifier.clickable { onOpen() }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(stringResource(R.string.daily_challenge), style = MaterialTheme.typography.headlineMedium); Text(formatEpochDay(daily.day), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)) }
            Column(horizontalAlignment = Alignment.End) { Text("${daily.completed}/${daily.total}", style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.daily_streak, daily.streak.toInt()), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
private fun formatEpochDay(day: Long): String = LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
