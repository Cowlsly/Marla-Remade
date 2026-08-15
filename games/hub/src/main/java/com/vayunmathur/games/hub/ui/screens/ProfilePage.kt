package com.vayunmathur.games.hub.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.util.ProfileUiState
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel

/** Binds [GameHubViewModel] to the stateless [ProfileScreen]. */
@Composable
fun ProfilePage(viewModel: GameHubViewModel, modifier: Modifier = Modifier) {
    val profile by viewModel.profileFlow.collectAsStateWithLifecycle()
    val xp by viewModel.totalXpFlow.collectAsStateWithLifecycle()
    val level by viewModel.levelFlow.collectAsStateWithLifecycle()
    val title by viewModel.titleFlow.collectAsStateWithLifecycle()
    val crossStats by viewModel.statsFlow.collectAsStateWithLifecycle()

    ProfileScreen(
        state = ProfileUiState(
            playerName = profile?.displayName,
            avatarSymbol = profile?.avatarSymbol,
            level = level,
            title = title,
            totalXp = xp,
            stats = crossStats,
        ),
        actions = viewModel,
        modifier = modifier,
    )
}
