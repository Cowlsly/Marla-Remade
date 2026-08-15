package com.vayunmathur.games.hub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.IconPerson
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.R
import com.vayunmathur.games.hub.ui.components.LevelBadge
import com.vayunmathur.games.hub.ui.components.StatCard
import com.vayunmathur.games.hub.ui.components.XpProgressBar
import com.vayunmathur.games.hub.util.ProfileActions
import com.vayunmathur.games.hub.util.ProfileUiState
import com.vayunmathur.games.hub.util.XpLevelCalculator
import com.vayunmathur.games.hub.util.formatPlaytime
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.ui.res.stringResource

private val avatarOptions = listOf(
    "person", "stadia_controller", "sports_esports", "emoji_events", "military_tech",
    "star", "bolt", "local_fire_department", "rocket", "diamond",
    "psychology", "lightbulb", "school", "workspace_premium", "king_bed"
)

/**
 * The profile screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview`.
 */
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    actions: ProfileActions,
    modifier: Modifier = Modifier,
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var selectedAvatar by remember { mutableStateOf<String?>(null) }

    if (showEditDialog && editName.isEmpty() && state.playerName != null) {
        editName = state.playerName
        selectedAvatar = state.avatarSymbol
    }

    Scaffold(topBar = {
        TopAppBar(
            actions = {
                androidx.compose.material3.IconButton(onClick = {
                    editName = state.playerName ?: ""
                    selectedAvatar = state.avatarSymbol
                    showEditDialog = true
                }) { IconPerson() }
            }
        )
    }) { padding ->
        LazyColumn(modifier = modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        LevelBadge(level = state.level, large = true)
                        Text(text = state.playerName ?: stringResource(R.string.player), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(text = state.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.level_1, state.level), style = MaterialTheme.typography.labelLarge)
                        XpProgressBar(totalXp = state.totalXp, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item { Text(stringResource(R.string.stats), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.playtime), value = formatPlaytime(state.stats.totalPlaytimeMs), modifier = Modifier.weight(1f))
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.sessions), value = "${state.stats.totalSessions}", modifier = Modifier.weight(1f))
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.achievements_for), value = "${state.stats.totalAchievementsUnlocked}/${state.stats.totalAchievements}", modifier = Modifier.weight(1f))
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.tab_games), value = "${state.stats.totalGames}", modifier = Modifier.weight(1f))
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.xp_2), value = "${state.totalXp}", modifier = Modifier.weight(1f))
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.best_streak), value = "${state.stats.longestStreak}d", modifier = Modifier.weight(1f))
                }
            }
            item { Text(stringResource(R.string.level_table), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
            items((1..maxOf(state.level + 2, 10)).toList(), key = { it }) { lvl ->
                val lvlXp = XpLevelCalculator.xpForLevel(lvl)
                val isCurrent = lvl == state.level
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isCurrent) androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    else androidx.compose.material3.CardDefaults.cardColors()
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.level_2, lvl, XpLevelCalculator.title(lvl), if (isCurrent) stringResource(R.string.you) else ""), fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                        Text(stringResource(R.string.xp, lvlXp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (showEditDialog) {
            AlertDialog(
                onDismissRequest = { showEditDialog = false; editName = "" },
                title = { Text(stringResource(R.string.edit_profile)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text(stringResource(R.string.display_name)) }, singleLine = true)
                        Text(stringResource(R.string.avatar), style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(avatarOptions) { sym ->
                                FilterChip(selected = selectedAvatar == sym, onClick = { selectedAvatar = if (selectedAvatar == sym) null else sym }, label = { Text(sym) })
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (editName.isNotBlank()) actions.updateDisplayName(editName.trim())
                        actions.updateAvatarSymbol(selectedAvatar)
                        showEditDialog = false; editName = ""
                    }) { Text(stringResource(UiR.string.save)) }
                },
                dismissButton = { TextButton(onClick = { showEditDialog = false; editName = "" }) { Text(stringResource(UiR.string.cancel)) } }
            )
        }
    }
}
