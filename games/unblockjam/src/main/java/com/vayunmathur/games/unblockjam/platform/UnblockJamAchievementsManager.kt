package com.vayunmathur.games.unblockjam.platform

import android.content.Context
import com.vayunmathur.games.unblockjam.data.CompletedLevelsRepository
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DailyChallengeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UnblockJamAchievementsManager(
    context: Context,
    json: String,
    private val repository: CompletedLevelsRepository
) : AchievementsManager(context, json) {

    private val dailyStore = DailyChallengeStore(context, "unblockjam_daily")

    override fun checkExistingAchievements() {
        val stats = repository.getLevelStats()
        if (stats.isNotEmpty()) onAchievementUnlocked("first_level")
        onProgressUpdated("level_50", stats.size)
        onProgressUpdated("moves_1000", repository.getTotalMoves())
        onProgressUpdated("undo_master", repository.getUndoCount())
        onProgressUpdated("all_levels_pack_0", stats.size)

        CoroutineScope(Dispatchers.IO).launch {
            val bestStreak = dailyStore.bestStreak()
            if (bestStreak > 0) {
                onProgressUpdated("daily_streak_7", bestStreak.toInt())
                onProgressUpdated("daily_streak_30", bestStreak.toInt())
            }
        }
    }
}
