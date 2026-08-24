package com.vayunmathur.games.nonogram.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Everything the game remembers.
 *
 * Casual progress is a single level number plus the marks on that one level; there is no per-level
 * record, because advancing clears the marks and there is no way back. Daily progress is kept
 * separately and stamped with the day it belongs to, so yesterday's work can never bleed into today's
 * puzzle — see [dailyDay] and `ensureDailyDay`.
 */
class NonogramDataStore(private val context: Context) {

    val currentLevel: Flow<Int> = context.dataStore.data.map { it[LEVEL_KEY] ?: 1 }

    val filledCells: Flow<Set<Int>> = context.dataStore.data.map { it[FILLED_KEY].toCells() }
    val crossedCells: Flow<Set<Int>> = context.dataStore.data.map { it[CROSSED_KEY].toCells() }

    val gameMode: Flow<GameMode> = context.dataStore.data.map { prefs ->
        prefs[GAME_MODE_KEY]?.let { runCatching { GameMode.valueOf(it) }.getOrNull() }
            ?: GameMode.CASUAL
    }

    val showMistakes: Flow<Boolean> = context.dataStore.data.map { it[SHOW_MISTAKES_KEY] ?: true }

    /** Lifetime count of completed puzzles, which survives [saveLevel] clearing the board. */
    val puzzlesCompleted: Flow<Int> = context.dataStore.data.map { it[COMPLETED_KEY] ?: 0 }

    // ---- Daily ----

    val dailyDay: Flow<Long> = context.dataStore.data.map { it[DAILY_DAY_KEY] ?: NO_DAY }
    val dailyFilledCells: Flow<Set<Int>> =
        context.dataStore.data.map { it[DAILY_FILLED_KEY].toCells() }
    val dailyCrossedCells: Flow<Set<Int>> =
        context.dataStore.data.map { it[DAILY_CROSSED_KEY].toCells() }

    suspend fun setGameMode(mode: GameMode) {
        context.dataStore.edit { it[GAME_MODE_KEY] = mode.name }
    }

    suspend fun setShowMistakes(enabled: Boolean) {
        context.dataStore.edit { it[SHOW_MISTAKES_KEY] = enabled }
    }

    /**
     * Moves to [level] and wipes the board in the same write.
     *
     * One edit rather than two, so a crash between them cannot leave the previous level's marks on the
     * new puzzle.
     */
    suspend fun saveLevel(level: Int) {
        context.dataStore.edit { prefs ->
            prefs[LEVEL_KEY] = level
            prefs[FILLED_KEY] = emptySet()
            prefs[CROSSED_KEY] = emptySet()
        }
    }

    /** Clears the current level's marks without advancing, for the restart button. */
    suspend fun clearMarks(daily: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[if (daily) DAILY_FILLED_KEY else FILLED_KEY] = emptySet()
            prefs[if (daily) DAILY_CROSSED_KEY else CROSSED_KEY] = emptySet()
        }
    }

    /**
     * Records the mark on [index], keeping the two sets disjoint.
     *
     * A cell is either filled, crossed, or neither, so setting one mark always removes the other.
     */
    suspend fun setMark(index: Int, mark: CellMark, daily: Boolean) {
        val filledKey = if (daily) DAILY_FILLED_KEY else FILLED_KEY
        val crossedKey = if (daily) DAILY_CROSSED_KEY else CROSSED_KEY
        context.dataStore.edit { prefs ->
            val filled = prefs[filledKey].toCells().toMutableSet()
            val crossed = prefs[crossedKey].toCells().toMutableSet()
            filled.remove(index)
            crossed.remove(index)
            when (mark) {
                CellMark.FILLED -> filled.add(index)
                CellMark.CROSSED -> crossed.add(index)
                CellMark.BLANK -> Unit
            }
            prefs[filledKey] = filled.mapTo(mutableSetOf()) { it.toString() }
            prefs[crossedKey] = crossed.mapTo(mutableSetOf()) { it.toString() }
        }
    }

    /** Bumps and returns the lifetime completed count. */
    suspend fun recordCompleted(): Int {
        var total = 0
        context.dataStore.edit { prefs ->
            total = (prefs[COMPLETED_KEY] ?: 0) + 1
            prefs[COMPLETED_KEY] = total
        }
        return total
    }

    /**
     * Rolls the daily board over to [day] if it is not already there.
     *
     * Clearing on rollover is what stops yesterday's marks appearing on today's puzzle. The early
     * return keeps re-entering daily mode from wiping work in progress.
     */
    suspend fun ensureDailyDay(day: Long) {
        context.dataStore.edit { prefs ->
            if (prefs[DAILY_DAY_KEY] == day) return@edit
            prefs[DAILY_DAY_KEY] = day
            prefs[DAILY_FILLED_KEY] = emptySet()
            prefs[DAILY_CROSSED_KEY] = emptySet()
        }
    }

    private companion object {
        val LEVEL_KEY = intPreferencesKey("current_level")
        val FILLED_KEY = stringSetPreferencesKey("filled_cells")
        val CROSSED_KEY = stringSetPreferencesKey("crossed_cells")
        val GAME_MODE_KEY = stringPreferencesKey("game_mode")
        val SHOW_MISTAKES_KEY = booleanPreferencesKey("show_mistakes")
        val COMPLETED_KEY = intPreferencesKey("puzzles_completed")

        val DAILY_DAY_KEY = longPreferencesKey("daily_day")
        val DAILY_FILLED_KEY = stringSetPreferencesKey("daily_filled_cells")
        val DAILY_CROSSED_KEY = stringSetPreferencesKey("daily_crossed_cells")

        /** Sentinel for "no daily played yet"; distinct from epoch day 0, which is a real date. */
        const val NO_DAY = Long.MIN_VALUE
    }
}

/** Cell indices are stored as strings because DataStore has no int-set type. */
private fun Set<String>?.toCells(): Set<Int> =
    this?.mapNotNullTo(mutableSetOf()) { it.toIntOrNull() } ?: emptySet()
