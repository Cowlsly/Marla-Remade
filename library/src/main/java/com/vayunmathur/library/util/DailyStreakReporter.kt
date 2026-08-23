package com.vayunmathur.library.util

import android.content.Context
import com.vayunmathur.sdk.games.DailyStreak
import com.vayunmathur.sdk.games.GameHubClient

/**
 * Forwards a game's daily-puzzle streak to the hub, right where the game already records it.
 *
 * Fail-soft in both directions: [GameHubClient] no-ops when the hub isn't installed, and any
 * other failure is swallowed — a game must never break because the hub is unhappy.
 */
object DailyStreakReporter {

    suspend fun report(context: Context, gameId: String, streak: DailyChallengeStore.Streak, day: Long) {
        try {
            GameHubClient(context, gameId).reportDailyStreak(
                DailyStreak(
                    currentStreak = streak.current.toInt(),
                    longestStreak = streak.best.toInt(),
                    lastCompletedDay = day,
                )
            )
        } catch (_: Exception) { }
    }
}
