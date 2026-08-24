package com.vayunmathur.library.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R
import com.vayunmathur.library.ui.Text

/**
 * How many days running the player has cleared the daily board.
 *
 * Reads as "5 day streak" rather than "Streak: 5" so it needs no separate label, and pluralises so a
 * first day is not "1 days".
 */
@Composable
fun DailyStreakText(
    streak: Long,
    modifier: Modifier = Modifier,
) {
    val days = streak.coerceAtLeast(0).toInt()
    Text(
        text = pluralStringResource(R.plurals.game_daily_streak, days, days),
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
