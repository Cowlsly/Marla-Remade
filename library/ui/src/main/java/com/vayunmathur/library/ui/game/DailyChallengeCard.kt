package com.vayunmathur.library.ui.game

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
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The entry point to a game's daily challenge, for games that keep it on a list screen rather than
 * behind a mode switch.
 *
 * Carries the date, how far through today's set the player is, and the streak — the three things that
 * decide whether they tap it. Filled in the primary container colour so it reads as the one live thing
 * on a list of otherwise static packs.
 *
 * @param day the daily's epoch day, formatted in the user's locale.
 * @param completed how many of [total] parts of today's challenge are done.
 */
@Composable
fun DailyChallengeCard(
    day: Long,
    completed: Int,
    total: Int,
    streak: Long,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.clickable { onOpen() },
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    stringResource(R.string.game_daily_challenge),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    formatEpochDay(day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$completed/$total", style = MaterialTheme.typography.titleMedium)
                DailyStreakText(streak)
            }
        }
    }
}

private fun formatEpochDay(day: Long): String =
    LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
