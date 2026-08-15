package com.vayunmathur.games.hub.data

import android.content.Context
import com.vayunmathur.games.hub.data.dao.AchievementWithProgress
import com.vayunmathur.games.hub.data.entities.AchievementDefEntity
import com.vayunmathur.games.hub.data.entities.AchievementProgressEntity
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import com.vayunmathur.games.hub.data.entities.PlayerProfileEntity
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single owner of [GamesHubDatabase].
 */
class GamesHubRepository private constructor(context: Context) :
    RoomRepository<GamesHubDatabase>(context, GamesHubDatabase::class, DB_NAME) {

    private val gameDao get() = db.gameDao()
    private val achievementDao get() = db.achievementDao()
    private val sessionDao get() = db.sessionDao()
    private val profileDao get() = db.profileDao()
    private val activityDao get() = db.activityDao()

    val database: GamesHubDatabase get() = db

    // ------------------------------------------------------------------
    // GameDao
    // ------------------------------------------------------------------

    fun gamesFlow(): Flow<List<HubGameEntity>> = gameDao.flowAll()
    fun gameByIdFlow(gameId: String): Flow<HubGameEntity?> = gameDao.flowById(gameId)
    suspend fun getGame(gameId: String): HubGameEntity? = gameDao.getById(gameId)
    suspend fun getAllGames(): List<HubGameEntity> = gameDao.getAll()
    suspend fun upsertGame(game: HubGameEntity) = gameDao.upsert(game)
    suspend fun markPlayed(gameId: String, timestamp: Long = System.currentTimeMillis()) = gameDao.markPlayed(gameId, timestamp)
    suspend fun addPlaytime(gameId: String, increment: Long) = gameDao.addPlaytime(gameId, increment)
    suspend fun clearGames() = gameDao.clearAll()
    fun totalPlaytimeFlow(): Flow<Long> = gameDao.flowTotalPlaytimeMs()
    fun totalSessionsFlow(): Flow<Int> = gameDao.flowTotalSessions()

    // ------------------------------------------------------------------
    // AchievementDao
    // ------------------------------------------------------------------

    suspend fun upsertDef(def: AchievementDefEntity) = achievementDao.upsertDef(def)
    fun achievementDefsByGameFlow(gameId: String): Flow<List<AchievementDefEntity>> = achievementDao.flowDefsByGame(gameId)
    suspend fun getAchievementDef(gameId: String, achievementId: String): AchievementDefEntity? = achievementDao.getDef(gameId, achievementId)
    fun totalDefsCountFlow(): Flow<Int> = achievementDao.flowTotalDefsCount()
    suspend fun upsertProgress(progress: AchievementProgressEntity) = achievementDao.upsertProgress(progress)
    suspend fun getProgress(gameId: String, achievementId: String): AchievementProgressEntity? = achievementDao.getProgress(gameId, achievementId)
    fun progressByGameFlow(gameId: String): Flow<List<AchievementProgressEntity>> = achievementDao.flowProgressByGame(gameId)
    fun allWithProgressFlow(): Flow<List<AchievementWithProgress>> = achievementDao.flowAllWithProgress()
    fun byGameWithProgressFlow(gameId: String): Flow<List<AchievementWithProgress>> = achievementDao.flowByGameWithProgress(gameId)
    fun unlockedWithProgressFlow(): Flow<List<AchievementWithProgress>> = achievementDao.flowUnlockedWithProgress()
    fun totalXpFlow(): Flow<Int> = achievementDao.flowTotalXp()
    fun defsByGameFlow(gameId: String): Flow<List<AchievementDefEntity>> = achievementDao.flowDefsByGame(gameId)
    fun unlockedProgressFlow(): Flow<List<AchievementProgressEntity>> = achievementDao.flowUnlockedProgress()
    suspend fun clearDefs() = achievementDao.clearDefs()
    suspend fun clearProgress() = achievementDao.clearProgress()

    // Expose raw achievement flow helpers used by GamesHubProvider (first())
    fun flowDefsByGame(gameId: String): Flow<List<AchievementDefEntity>> = achievementDao.flowDefsByGame(gameId)
    fun flowProgressByGame(gameId: String): Flow<List<AchievementProgressEntity>> = achievementDao.flowProgressByGame(gameId)
    suspend fun getDef(gameId: String, achievementId: String, unused: Boolean = false): AchievementDefEntity? =
        achievementDao.getDef(gameId, achievementId)

    // ------------------------------------------------------------------
    // SessionDao
    // ------------------------------------------------------------------

    suspend fun upsertSession(session: PlaySessionEntity) = sessionDao.upsert(session)
    suspend fun getSessionById(sessionId: String): PlaySessionEntity? = sessionDao.getBySessionId(sessionId)
    fun sessionsByGameFlow(gameId: String): Flow<List<PlaySessionEntity>> = sessionDao.flowByGame(gameId)
    fun allSessionsFlow(): Flow<List<PlaySessionEntity>> = sessionDao.flowAll()
    suspend fun endSession(sessionId: String, endTime: Long, durationMs: Long) = sessionDao.endSession(sessionId, endTime, durationMs)
    fun totalPlaytimeSessionsFlow(): Flow<Long> = sessionDao.flowTotalPlaytimeMs()
    suspend fun clearSessions() = sessionDao.clearAll()

    // ------------------------------------------------------------------
    // ProfileDao
    // ------------------------------------------------------------------

    fun profileFlow(): Flow<PlayerProfileEntity?> = profileDao.flowProfile()
    suspend fun getProfile(): PlayerProfileEntity? = profileDao.getProfile()
    suspend fun upsertProfile(profile: PlayerProfileEntity) = profileDao.upsert(profile)

    // ------------------------------------------------------------------
    // ActivityDao
    // ------------------------------------------------------------------

    suspend fun upsertActivity(event: ActivityEventEntity) = activityDao.upsert(event)
    fun recentActivityFlow(limit: Int = 50): Flow<List<ActivityEventEntity>> = activityDao.flowRecent(limit)
    fun allActivityFlow(): Flow<List<ActivityEventEntity>> = activityDao.flowAll()
    fun activityByGameFlow(gameId: String, limit: Int = 20): Flow<List<ActivityEventEntity>> = activityDao.flowByGame(gameId, limit)
    suspend fun clearActivities() = activityDao.clearAll()

    companion object {
        @Volatile
        private var instance: GamesHubRepository? = null

        fun get(context: Context): GamesHubRepository =
            instance ?: synchronized(this) {
                instance ?: GamesHubRepository(context).also { instance = it }
            }
    }
}
