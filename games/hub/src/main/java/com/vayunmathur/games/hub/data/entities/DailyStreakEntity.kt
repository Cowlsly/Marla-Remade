package com.vayunmathur.games.hub.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A game's daily-puzzle streak, mirrored from the game.
 *
 * One row per game rather than one per day: the game's `DailyChallengeStore` is the source of
 * truth and only retains current/best/last-day, so there is no per-day history to keep.
 */
@Entity(tableName = "daily_streaks")
data class DailyStreakEntity(
    @PrimaryKey val gameId: String,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    /** Epoch day, so a lapsed streak is recognisable without trusting the write time. */
    val lastCompletedDay: Long = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)
