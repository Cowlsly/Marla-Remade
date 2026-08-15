package com.vayunmathur.games.hub.ui.screens

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.R
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.ui.components.ActivityItemCard
import com.vayunmathur.games.hub.ui.components.GameCard
import com.vayunmathur.games.hub.ui.components.LevelBadge
import com.vayunmathur.games.hub.ui.components.StatCard
import com.vayunmathur.games.hub.ui.components.StreakCard
import com.vayunmathur.games.hub.ui.components.XpProgressBar
import com.vayunmathur.games.hub.util.DashboardActions
import com.vayunmathur.games.hub.util.DashboardUiState
import com.vayunmathur.games.hub.util.formatPlaytime
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.ui.res.stringResource

/**
 * The dashboard, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 *
 * [iconFor] and [topBarActions] are the two things that genuinely need a device: installed
 * app icons, and the backup menu's file pickers (which need an Activity). Both default to
 * nothing so a preview can leave them out.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    actions: DashboardActions,
    modifier: Modifier = Modifier,
    iconFor: (HubGameEntity) -> Drawable? = { null },
    topBarActions: @Composable RowScope.() -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = topBarActions
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Card(onClick = actions::openProfile, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LevelBadge(level = state.level, large = true)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text = state.playerName ?: stringResource(R.string.player), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(text = state.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            XpProgressBar(totalXp = state.totalXp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (state.stats.currentStreak > 0 || state.stats.longestStreak > 0) {
                item { StreakCard(currentStreak = state.stats.currentStreak, longestStreak = state.stats.longestStreak, modifier = Modifier.fillMaxWidth()) }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.playtime), value = formatPlaytime(state.stats.totalPlaytimeMs), modifier = Modifier.weight(1f))
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.tab_games), value = "${state.stats.totalGames}", modifier = Modifier.weight(1f))
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.achievements_for), value = "${state.stats.totalAchievementsUnlocked}/${state.stats.totalAchievements}", modifier = Modifier.weight(1f))
                }
            }

            if (state.recentlyPlayed.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.continue_playing), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = actions::openGamesList) { Text(stringResource(R.string.see_all)) }
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.recentlyPlayed, key = { it.gameId }) { game ->
                            GameCard(
                                game = game, isInstalled = game.gameId in state.installedGameIds,
                                achievementProgress = state.achievementProgressByGame[game.gameId],
                                iconDrawable = iconFor(game),
                                onClick = { actions.openGame(game.gameId) },
                                onPlay = { actions.playGame(game) },
                                modifier = Modifier.fillParentMaxWidth(0.85f)
                            )
                        }
                    }
                }
            }

            if (state.recentActivity.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.recent_activity), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = actions::openActivity) { Text(stringResource(R.string.see_all)) }
                    }
                }
                items(state.recentActivity.take(5), key = { it.id }) { event -> ActivityItemCard(event = event, onGameClick = actions::openGame) }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.no_activity_yet_play_a_game), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

fun launchGame(context: Context, game: HubGameEntity) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(game.packageName)
        if (intent != null) context.startActivity(intent)
    } catch (_: Exception) { }
}
