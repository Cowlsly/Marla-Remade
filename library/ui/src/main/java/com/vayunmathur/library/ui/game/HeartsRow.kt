package com.vayunmathur.library.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R
import com.vayunmathur.library.ui.Spacing

/**
 * The attempts a player has left on the current board.
 *
 * Spent hearts stay in place as dimmed shapes rather than disappearing, so the row never reflows and
 * the player can see at a glance how much margin is gone.
 *
 * The whole row carries one description instead of [total] separate ones, because "2 hearts left" is
 * the fact a screen-reader user wants — not a walk through three individual icons.
 */
@Composable
fun HeartsRow(
    remaining: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val safe = remaining.coerceIn(0, total)
    val description = pluralStringResource(R.plurals.game_hearts_left, safe, safe)
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        repeat(total) { index ->
            IconFavorite(
                Modifier.size(HeartSize),
                tint = if (index >= safe) MaterialTheme.colorScheme.surfaceContainerHighest
                else MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Large enough to read at a glance, small enough not to compete with the board. */
private val HeartSize = 20.dp
