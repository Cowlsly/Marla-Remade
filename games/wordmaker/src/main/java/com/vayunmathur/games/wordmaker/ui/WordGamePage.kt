package com.vayunmathur.games.wordmaker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.platform.WordGameUiState
import com.vayunmathur.games.wordmaker.platform.WordMakerViewModel
import com.vayunmathur.library.util.AchievementsManager

fun WordGamePage(
    crosswordData: CrosswordData,
    currentLevel: Int,
    viewModel: WordMakerViewModel,
    achievementsManager: AchievementsManager,
    onOpenGameCenter: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val foundWords by viewModel.foundWords.collectAsState()
    val bonusWords by viewModel.bonusWords.collectAsState()
    val tapToSpell by viewModel.tapToSpell.collectAsState()
    val revealedHints by viewModel.revealedHints.collectAsState()
    val hintCooldownEnd by viewModel.hintCooldownEnd.collectAsState()
    val gameMode by viewModel.gameMode.collectAsState()
    val competitiveScore by viewModel.competitiveScore.collectAsState()
    val competitiveLevelNumber by viewModel.competitiveLevelNumber.collectAsState()
    val competitiveDeadline by viewModel.competitiveDeadline.collectAsState()
    val dailyDay by viewModel.dailyDay.collectAsState()
    val dailyStreak by viewModel.dailyStreak.collectAsState()
    val dailyBestStreak by viewModel.dailyBestStreak.collectAsState()

    val isCompetitive = gameMode == GameMode.COMPETITIVE
    val isDaily = gameMode == GameMode.DAILY
    val isWon = crosswordData.winsWith(foundWords)

    LaunchedEffect(isWon) {
        if (!isWon) return@LaunchedEffect
        if (isCompetitive) {
            viewModel.onCompetitiveWin()
            return@LaunchedEffect
        }
        if (isDaily) {
            viewModel.onDailyWin()
            achievementsManager.onAchievementUnlocked("first_daily")
            return@LaunchedEffect
        }
        if (currentLevel == 1) achievementsManager.onAchievementUnlocked("level_1_done")
        if (currentLevel == 861) achievementsManager.onAchievementUnlocked("manual_levels_done")

        achievementsManager.onProgressUpdated("manual_levels_done", currentLevel)
        achievementsManager.onProgressUpdated("level_50", currentLevel)
        achievementsManager.onProgressUpdated("level_100", currentLevel)
        achievementsManager.onProgressUpdated("level_500", currentLevel)
    }

    LaunchedEffect(dailyBestStreak) {
        if (dailyBestStreak <= 0L) return@LaunchedEffect
        achievementsManager.onProgressUpdated("daily_streak_7", dailyBestStreak.toInt())
        achievementsManager.onProgressUpdated("daily_streak_30", dailyBestStreak.toInt())
    }

    // The achievements manager belongs to the activity, not the ViewModel, so the two
    // achievement-reporting callbacks are stitched on here rather than pushed into the screen.
    val actions = remember(viewModel, achievementsManager) {
        object : WordGameActions by viewModel {
            override fun onSolutionWordFound(word: String) {
                if (word.length >= 7) achievementsManager.onAchievementUnlocked("long_word")
            }

            override suspend fun addBonusWord(word: String): Int {
                val newTotal = viewModel.addBonusWord(word)
                achievementsManager.onProgressUpdated("bonus_hunter", newTotal)
                return newTotal
            }
        }
    }

    WordGameScreen(
        state = WordGameUiState(
            crosswordData = crosswordData,
            currentLevel = currentLevel,
            foundWords = foundWords,
            bonusWords = bonusWords,
            tapToSpell = tapToSpell,
            revealedHints = revealedHints,
            hintCooldownEnd = hintCooldownEnd,
            gameMode = gameMode,
            competitiveScore = competitiveScore,
            competitiveLevelNumber = competitiveLevelNumber,
            competitiveDeadline = competitiveDeadline,
            dailyDay = dailyDay,
            dailyStreak = dailyStreak
        ),
        actions = actions,
        onOpenGameCenter = onOpenGameCenter,
        onOpenSettings = onOpenSettings
    )
}