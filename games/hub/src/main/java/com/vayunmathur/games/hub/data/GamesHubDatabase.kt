package com.vayunmathur.games.hub.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.vayunmathur.games.hub.data.dao.ActivityDao
import com.vayunmathur.games.hub.data.dao.AchievementDao
import com.vayunmathur.games.hub.data.dao.GameDao
import com.vayunmathur.games.hub.data.dao.ProfileDao
import com.vayunmathur.games.hub.data.dao.SessionDao
import com.vayunmathur.games.hub.data.dao.StreakDao
import com.vayunmathur.games.hub.data.entities.AchievementDefEntity
import com.vayunmathur.games.hub.data.entities.AchievementProgressEntity
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.DailyStreakEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import com.vayunmathur.games.hub.data.entities.PlayerProfileEntity
import com.vayunmathur.library.util.DatabaseMigrations

const val DB_NAME = "games-hub-db"

@Database(
    entities = [
        HubGameEntity::class,
        AchievementDefEntity::class,
        AchievementProgressEntity::class,
        PlaySessionEntity::class,
        PlayerProfileEntity::class,
        ActivityEventEntity::class,
        DailyStreakEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class GamesHubDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun achievementDao(): AchievementDao
    abstract fun sessionDao(): SessionDao
    abstract fun profileDao(): ProfileDao
    abstract fun activityDao(): ActivityDao
    abstract fun streakDao(): StreakDao

    companion object : DatabaseMigrations {
        override val migrations: List<Migration> = listOf(
            // Per-game daily-puzzle streaks. Schemas aren't exported here, so this DDL is written
            // to byte-match what Room generates for DailyStreakEntity — Room validates it on open.
            Migration(1, 2) { db ->
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_streaks` (" +
                        "`gameId` TEXT NOT NULL, " +
                        "`currentStreak` INTEGER NOT NULL, " +
                        "`longestStreak` INTEGER NOT NULL, " +
                        "`lastCompletedDay` INTEGER NOT NULL, " +
                        "`lastUpdated` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`gameId`))",
                )
            },
        )
    }
}
