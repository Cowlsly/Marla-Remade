package com.vayunmathur.games.hub.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import com.vayunmathur.games.hub.data.GamesHubRepository
import com.vayunmathur.games.hub.data.entities.AchievementDefEntity
import com.vayunmathur.games.hub.data.entities.AchievementProgressEntity
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import com.vayunmathur.sdk.games.GameHubContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

open class GamesHubProvider : ContentProvider() {

    companion object {
        private const val GAMES_PACKAGE_PREFIX = "com.vayunmathur.games"

        private const val CODE_GAMES = 1
        private const val CODE_GAME_ITEM = 2
        private const val CODE_ACH_DEFS = 10
        private const val CODE_ACH_PROGRESS = 11
        private const val CODE_ACH_PROGRESS_ITEM = 12
        private const val CODE_LEGACY_ACHIEVEMENTS = 20
        private const val CODE_LEGACY_ACHIEVEMENT_ITEM = 21
        private const val CODE_SESSIONS_BY_GAME = 50
        private const val CODE_SESSION_ITEM = 51

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(GameHubContract.AUTHORITY, "games", CODE_GAMES)
            addURI(GameHubContract.AUTHORITY, "games/*", CODE_GAME_ITEM)
            addURI(GameHubContract.LEGACY_AUTHORITY, "games", CODE_GAMES)
            addURI(GameHubContract.LEGACY_AUTHORITY, "games/*", CODE_GAME_ITEM)

            addURI(GameHubContract.AUTHORITY, "achievements/*/defs", CODE_ACH_DEFS)
            addURI(GameHubContract.LEGACY_AUTHORITY, "achievements/*/defs", CODE_ACH_DEFS)
            addURI(GameHubContract.AUTHORITY, "achievements/*/progress", CODE_ACH_PROGRESS)
            addURI(GameHubContract.LEGACY_AUTHORITY, "achievements/*/progress", CODE_ACH_PROGRESS)
            addURI(GameHubContract.AUTHORITY, "achievements/*/progress/*", CODE_ACH_PROGRESS_ITEM)
            addURI(GameHubContract.LEGACY_AUTHORITY, "achievements/*/progress/*", CODE_ACH_PROGRESS_ITEM)

            addURI(GameHubContract.AUTHORITY, "achievements/*", CODE_LEGACY_ACHIEVEMENTS)
            addURI(GameHubContract.LEGACY_AUTHORITY, "achievements/*", CODE_LEGACY_ACHIEVEMENTS)
            addURI(GameHubContract.AUTHORITY, "achievements/*/*", CODE_LEGACY_ACHIEVEMENT_ITEM)
            addURI(GameHubContract.LEGACY_AUTHORITY, "achievements/*/*", CODE_LEGACY_ACHIEVEMENT_ITEM)

            addURI(GameHubContract.AUTHORITY, "sessions/*", CODE_SESSIONS_BY_GAME)
            addURI(GameHubContract.LEGACY_AUTHORITY, "sessions/*", CODE_SESSIONS_BY_GAME)
            addURI(GameHubContract.AUTHORITY, "sessions/*/*", CODE_SESSION_ITEM)
            addURI(GameHubContract.LEGACY_AUTHORITY, "sessions/*/*", CODE_SESSION_ITEM)
        }
    }

    private fun getRepo(): GamesHubRepository {
        val ctx = context ?: error("GamesHubProvider context null")
        return GamesHubRepository.get(ctx)
    }

    override fun onCreate(): Boolean = true

    // The signature permission on this provider only proves "signed by the Modern Apps key",
    // which every app in the repo is. Narrow that to the games namespace as well.
    private fun enforceGamesCaller() {
        if (Binder.getCallingUid() == Process.myUid()) return
        val caller = callingPackage
        if (caller != GAMES_PACKAGE_PREFIX && caller?.startsWith("$GAMES_PACKAGE_PREFIX.") != true) {
            throw SecurityException("GamesHubProvider: caller $caller is outside the games namespace")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceGamesCaller()
        val v = values ?: return null
        return runBlocking(Dispatchers.IO) {
            val repo = getRepo()
            val database = repo.database
            try {
                when (uriMatcher.match(uri)) {
                    CODE_GAME_ITEM, CODE_GAMES -> {
                        val entity = valuesToGameMerged(v, uri, repo)
                        repo.upsertGame(entity)
                        repo.upsertActivity(
                            ActivityEventEntity(
                                type = ActivityEventEntity.TYPE_GAME_REGISTERED,
                                gameId = entity.gameId,
                                title = "${entity.displayName} registered",
                                description = entity.description
                            )
                        )
                        uri
                    }
                    CODE_ACH_DEFS, CODE_LEGACY_ACHIEVEMENTS -> {
                        val def = valuesToAchievementDef(v, uri)
                        repo.upsertDef(def)
                        uri
                    }
                    CODE_ACH_PROGRESS -> {
                        val prog = valuesToProgress(v, uri)
                        val wasUnlocked = repo.getProgress(prog.gameId, prog.achievementId)?.isUnlocked ?: false
                        val def = repo.getAchievementDef(prog.gameId, prog.achievementId)
                        repo.upsertProgress(prog)
                        if (prog.isUnlocked && !wasUnlocked && def != null) {
                            repo.upsertActivity(
                                ActivityEventEntity(
                                    type = ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED,
                                    gameId = prog.gameId,
                                    title = def.name,
                                    description = def.description
                                )
                            )
                        }
                        uri
                    }
                    CODE_ACH_PROGRESS_ITEM, CODE_LEGACY_ACHIEVEMENT_ITEM -> {
                        val prog = valuesToProgress(v, uri)
                        val def = repo.getAchievementDef(prog.gameId, prog.achievementId)
                        val existing = repo.getProgress(prog.gameId, prog.achievementId)
                        val merged = if (existing != null) {
                            val newProgress = maxOf(existing.progress, prog.progress)
                            val newUnlocked = existing.isUnlocked || prog.isUnlocked
                            val newUnlockedAt = when {
                                newUnlocked && existing.unlockedAt != null -> existing.unlockedAt
                                newUnlocked -> prog.unlockedAt ?: System.currentTimeMillis()
                                else -> existing.unlockedAt
                            }
                            existing.copy(
                                progress = newProgress,
                                isUnlocked = newUnlocked,
                                unlockedAt = newUnlockedAt,
                                lastUpdated = System.currentTimeMillis()
                            )
                        } else prog
                        repo.upsertProgress(merged)
                        val wasUnlockedBefore = existing?.isUnlocked ?: false
                        if (merged.isUnlocked && !wasUnlockedBefore && def != null) {
                            repo.upsertActivity(
                                ActivityEventEntity(
                                    type = ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED,
                                    gameId = merged.gameId,
                                    title = def.name,
                                    description = def.description
                                )
                            )
                        }
                        if (uriMatcher.match(uri) == CODE_LEGACY_ACHIEVEMENT_ITEM) {
                            if (repo.getAchievementDef(prog.gameId, prog.achievementId) == null) {
                                repo.upsertDef(
                                    AchievementDefEntity(
                                        gameId = prog.gameId,
                                        achievementId = prog.achievementId,
                                        name = prog.achievementId,
                                        description = "",
                                        xpReward = 25
                                    )
                                )
                            }
                        }
                        uri
                    }
                    CODE_SESSIONS_BY_GAME -> {
                        val session = valuesToSession(v, uri)
                        ensureGameExists(session.gameId, repo)
                        database.gameDao().markPlayed(session.gameId, session.startTime)
                        repo.upsertSession(session)
                        uri
                    }
                    CODE_SESSION_ITEM -> {
                        val session = valuesToSession(v, uri)
                        ensureGameExists(session.gameId, repo)
                        if (repo.getSessionById(session.sessionId) == null) {
                            database.gameDao().markPlayed(session.gameId, session.startTime)
                        }
                        repo.upsertSession(session)
                        uri
                    }
                    else -> null
                }
            } catch (e: Exception) {
                android.util.Log.e("GamesHubProvider", "insert failed $uri", e)
                null
            }
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceGamesCaller()
        val v = values ?: return 0
        return runBlocking(Dispatchers.IO) {
            val repo = getRepo()
            val database = repo.database
            try {
                when (uriMatcher.match(uri)) {
                    CODE_ACH_PROGRESS_ITEM, CODE_LEGACY_ACHIEVEMENT_ITEM -> {
                        val gameId = uri.pathSegments.getOrNull(1) ?: v.getAsString(GameHubContract.AchievementProgressCols.GAME_ID) ?: v.getAsString("game_id") ?: return@runBlocking 0
                        val achId = uri.pathSegments.lastOrNull()?.takeIf { it != "progress" }
                            ?: v.getAsString(GameHubContract.AchievementProgressCols.ACHIEVEMENT_ID) ?: v.getAsString("achievement_id") ?: return@runBlocking 0
                        val legacyUnlocked = v.getAsInteger("unlocked")
                        val isUnlocked = when {
                            legacyUnlocked != null -> legacyUnlocked == 1
                            v.containsKey(GameHubContract.AchievementProgressCols.IS_UNLOCKED) ->
                                (v.getAsInteger(GameHubContract.AchievementProgressCols.IS_UNLOCKED) ?: 0) == 1
                            else -> false
                        }
                        val unlockedAt = v.getAsLong("unlocked_at") ?: v.getAsLong(GameHubContract.AchievementProgressCols.UNLOCKED_AT)
                        val progressVal = v.getAsInteger(GameHubContract.AchievementProgressCols.PROGRESS) ?: v.getAsInteger("progress") ?: 0

                        val existing = repo.getProgress(gameId, achId)
                        val wasUnlockedBefore = existing?.isUnlocked ?: false
                        val merged = if (existing != null) {
                            existing.copy(
                                progress = maxOf(existing.progress, progressVal),
                                isUnlocked = existing.isUnlocked || isUnlocked,
                                unlockedAt = existing.unlockedAt ?: unlockedAt ?: if (isUnlocked) System.currentTimeMillis() else null,
                                lastUpdated = System.currentTimeMillis()
                            )
                        } else {
                            AchievementProgressEntity(
                                gameId = gameId,
                                achievementId = achId,
                                progress = progressVal,
                                isUnlocked = isUnlocked,
                                unlockedAt = unlockedAt ?: if (isUnlocked) System.currentTimeMillis() else null,
                                lastUpdated = System.currentTimeMillis()
                            )
                        }
                        repo.upsertProgress(merged)
                        if (merged.isUnlocked && !wasUnlockedBefore) {
                            val def = repo.getAchievementDef(gameId, achId)
                            repo.upsertActivity(
                                ActivityEventEntity(
                                    type = ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED,
                                    gameId = gameId,
                                    title = def?.name ?: achId,
                                    description = def?.description
                                )
                            )
                        }
                        1
                    }
                    CODE_SESSION_ITEM -> {
                        val sessionId = uri.pathSegments.lastOrNull() ?: return@runBlocking 0
                        val existing = repo.getSessionById(sessionId) ?: return@runBlocking 0
                        val endTime = v.getAsLong(GameHubContract.Sessions.END_TIME) ?: v.getAsLong("end_time") ?: System.currentTimeMillis()
                        val duration = (endTime - existing.startTime).coerceAtLeast(0L)
                        repo.endSession(sessionId, endTime, duration)
                        database.gameDao().addPlaytime(existing.gameId, duration)
                        repo.upsertActivity(
                            ActivityEventEntity(
                                type = ActivityEventEntity.TYPE_SESSION_COMPLETED,
                                gameId = existing.gameId,
                                title = "Played ${existing.gameId}",
                                description = "Session ${duration / 1000}s"
                            )
                        )
                        1
                    }
                    else -> 0
                }
            } catch (e: Exception) {
                android.util.Log.e("GamesHubProvider", "update failed $uri", e)
                0
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        enforceGamesCaller()
        return runBlocking(Dispatchers.IO) {
            val repo = getRepo()
            val database = repo.database
            try {
                when (uriMatcher.match(uri)) {
                    CODE_GAMES -> cursorFromGames(database.gameDao().getAll())
                    CODE_GAME_ITEM -> {
                        val gameId = uri.pathSegments.getOrNull(1) ?: return@runBlocking null
                        val game = database.gameDao().getById(gameId)
                        cursorFromGames(listOfNotNull(game))
                    }
                    CODE_ACH_DEFS -> {
                        val gameId = uri.pathSegments.getOrNull(1) ?: return@runBlocking null
                        cursorFromAchievementDefs(repo.flowDefsByGame(gameId).first())
                    }
                    CODE_LEGACY_ACHIEVEMENTS -> {
                        val gameId = uri.pathSegments.getOrNull(1) ?: return@runBlocking null
                        val allDefs = repo.flowDefsByGame(gameId).first()
                        val progressMap = repo.flowProgressByGame(gameId).first().associateBy { it.achievementId }
                        cursorFromLegacyAchievements(allDefs, progressMap)
                    }
                    CODE_ACH_PROGRESS -> {
                        val gameId = uri.pathSegments.getOrNull(1) ?: return@runBlocking null
                        cursorFromProgress(repo.flowProgressByGame(gameId).first())
                    }
                    CODE_ACH_PROGRESS_ITEM -> {
                        val gameId = uri.pathSegments.getOrNull(1) ?: return@runBlocking null
                        val achId = uri.pathSegments.getOrNull(3) ?: return@runBlocking null
                        val prog = repo.getProgress(gameId, achId)
                        cursorFromProgress(listOfNotNull(prog))
                    }
                    CODE_LEGACY_ACHIEVEMENT_ITEM -> {
                        val gameId = uri.pathSegments.getOrNull(1) ?: return@runBlocking null
                        val achId = uri.pathSegments.getOrNull(2) ?: return@runBlocking null
                        cursorFromLegacyAchievementItem(repo.getAchievementDef(gameId, achId), repo.getProgress(gameId, achId))
                    }
                    else -> null
                }
            } catch (e: Exception) {
                android.util.Log.e("GamesHubProvider", "query failed $uri", e)
                null
            }
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null

    private fun valuesToGame(v: ContentValues, uri: Uri): HubGameEntity {
        val segGameId = uri.pathSegments.getOrNull(1)
        val gameId = v.getAsString(GameHubContract.Games.GAME_ID) ?: v.getAsString("game_id") ?: segGameId ?: error("gameId missing")
        return HubGameEntity(
            gameId = gameId,
            packageName = v.getAsString(GameHubContract.Games.PACKAGE_NAME) ?: v.getAsString("package_name") ?: "unknown",
            displayName = v.getAsString(GameHubContract.Games.DISPLAY_NAME) ?: v.getAsString("display_name") ?: gameId,
            description = v.getAsString(GameHubContract.Games.DESCRIPTION) ?: v.getAsString("description"),
            versionName = v.getAsString(GameHubContract.Games.VERSION_NAME) ?: v.getAsString("version_name"),
            versionCode = v.getAsLong(GameHubContract.Games.VERSION_CODE) ?: v.getAsLong("version_code"),
            registeredAt = v.getAsLong(GameHubContract.Games.REGISTERED_AT) ?: v.getAsLong("registered_at") ?: System.currentTimeMillis(),
            lastSeenAt = v.getAsLong(GameHubContract.Games.LAST_SEEN_AT) ?: v.getAsLong("last_seen_at") ?: System.currentTimeMillis()
        )
    }

    private suspend fun valuesToGameMerged(v: ContentValues, uri: Uri, repo: GamesHubRepository): HubGameEntity {
        val segGameId = uri.pathSegments.getOrNull(1)
        val gameId = v.getAsString(GameHubContract.Games.GAME_ID) ?: v.getAsString("game_id") ?: segGameId ?: error("gameId missing")
        val existing = repo.getGame(gameId)
        if (existing == null) return valuesToGame(v, uri)
        return existing.copy(
            packageName = v.getAsString(GameHubContract.Games.PACKAGE_NAME) ?: v.getAsString("package_name") ?: existing.packageName,
            displayName = v.getAsString(GameHubContract.Games.DISPLAY_NAME) ?: v.getAsString("display_name") ?: existing.displayName,
            description = v.getAsString(GameHubContract.Games.DESCRIPTION) ?: v.getAsString("description") ?: existing.description,
            versionName = v.getAsString(GameHubContract.Games.VERSION_NAME) ?: v.getAsString("version_name") ?: existing.versionName,
            versionCode = v.getAsLong(GameHubContract.Games.VERSION_CODE) ?: v.getAsLong("version_code") ?: existing.versionCode,
            lastSeenAt = v.getAsLong(GameHubContract.Games.LAST_SEEN_AT) ?: v.getAsLong("last_seen_at") ?: System.currentTimeMillis()
        )
    }

    private suspend fun ensureGameExists(gameId: String, repo: GamesHubRepository) {
        if (repo.getGame(gameId) == null) {
            repo.upsertGame(
                HubGameEntity(
                    gameId = gameId,
                    packageName = "unknown",
                    displayName = gameId.replaceFirstChar { it.uppercase() },
                    lastSeenAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun valuesToAchievementDef(v: ContentValues, uri: Uri): AchievementDefEntity {
        val segGameId = uri.pathSegments.getOrNull(1)
        val gameId = v.getAsString(GameHubContract.AchievementDefs.GAME_ID) ?: v.getAsString("game_id") ?: segGameId ?: error("gameId missing in def")
        val achId = v.getAsString(GameHubContract.AchievementDefs.ACHIEVEMENT_ID) ?: v.getAsString("achievement_id") ?: error("achievement_id missing")
        return AchievementDefEntity(
            gameId = gameId,
            achievementId = achId,
            name = v.getAsString(GameHubContract.AchievementDefs.NAME) ?: v.getAsString("name") ?: achId,
            description = v.getAsString(GameHubContract.AchievementDefs.DESCRIPTION) ?: v.getAsString("description") ?: "",
            xpReward = v.getAsInteger(GameHubContract.AchievementDefs.XP_REWARD) ?: v.getAsInteger("xp_reward") ?: 25,
            targetProgress = v.getAsInteger(GameHubContract.AchievementDefs.TARGET_PROGRESS) ?: v.getAsInteger("target_progress") ?: 1,
            isSecret = (v.getAsInteger(GameHubContract.AchievementDefs.IS_SECRET) ?: v.getAsInteger("is_secret") ?: 0) == 1,
            tier = v.getAsString(GameHubContract.AchievementDefs.TIER) ?: v.getAsString("tier") ?: "BRONZE",
            iconResName = v.getAsString(GameHubContract.AchievementDefs.ICON_RES_NAME) ?: v.getAsString("icon_res_name")
        )
    }

    private fun valuesToProgress(v: ContentValues, uri: Uri): AchievementProgressEntity {
        val segs = uri.pathSegments
        val segGameId = segs.getOrNull(1)
        val segAchId = when {
            segs.size >= 4 && segs[2] == "progress" -> segs[3]
            segs.size == 3 && segs[1] != "defs" && segs[1] != "progress" -> segs[2]
            else -> null
        }
        val gameId = v.getAsString(GameHubContract.AchievementProgressCols.GAME_ID) ?: v.getAsString("game_id") ?: segGameId ?: error("gameId missing in progress")
        val achId = v.getAsString(GameHubContract.AchievementProgressCols.ACHIEVEMENT_ID) ?: v.getAsString("achievement_id") ?: segAchId ?: error("achievementId missing in progress")
        val legacyUnlocked = v.getAsInteger("unlocked")
        val newUnlocked = v.getAsInteger(GameHubContract.AchievementProgressCols.IS_UNLOCKED)
        val isUnlocked = when {
            legacyUnlocked != null -> legacyUnlocked == 1
            newUnlocked != null -> newUnlocked == 1
            else -> false
        }
        return AchievementProgressEntity(
            gameId = gameId,
            achievementId = achId,
            progress = v.getAsInteger(GameHubContract.AchievementProgressCols.PROGRESS) ?: v.getAsInteger("progress") ?: if (isUnlocked) 1 else 0,
            isUnlocked = isUnlocked,
            unlockedAt = v.getAsLong(GameHubContract.AchievementProgressCols.UNLOCKED_AT) ?: v.getAsLong("unlocked_at") ?: if (isUnlocked) System.currentTimeMillis() else null,
            lastUpdated = v.getAsLong(GameHubContract.AchievementProgressCols.LAST_UPDATED) ?: System.currentTimeMillis()
        )
    }

    private fun valuesToSession(v: ContentValues, uri: Uri): PlaySessionEntity {
        val segGameId = uri.pathSegments.getOrNull(1)
        val segSessionId = uri.pathSegments.getOrNull(2)
        return PlaySessionEntity(
            gameId = v.getAsString(GameHubContract.Sessions.GAME_ID) ?: v.getAsString("game_id") ?: segGameId ?: error("gameId missing in session"),
            sessionId = v.getAsString(GameHubContract.Sessions.SESSION_ID) ?: v.getAsString("session_id") ?: segSessionId ?: error("sessionId missing"),
            startTime = v.getAsLong(GameHubContract.Sessions.START_TIME) ?: v.getAsLong("start_time") ?: System.currentTimeMillis(),
            endTime = v.getAsLong(GameHubContract.Sessions.END_TIME) ?: v.getAsLong("end_time"),
            durationMs = v.getAsLong(GameHubContract.Sessions.DURATION_MS) ?: v.getAsLong("duration_ms")
        )
    }

    private fun cursorFromGames(games: List<HubGameEntity>): MatrixCursor {
        val c = MatrixCursor(arrayOf("game_id", "package_name", "display_name", "description", "version_name", "version_code", "registered_at", "last_seen_at", "last_played_at", "total_playtime_ms", "total_sessions"))
        for (g in games) {
            c.addRow(arrayOf<Any?>(g.gameId, g.packageName, g.displayName, g.description, g.versionName, g.versionCode, g.registeredAt, g.lastSeenAt, g.lastPlayedAt, g.totalPlaytimeMs, g.totalSessions))
        }
        return c
    }

    private fun cursorFromAchievementDefs(defs: List<com.vayunmathur.games.hub.data.entities.AchievementDefEntity>): MatrixCursor {
        val c = MatrixCursor(arrayOf("game_id", "achievement_id", "name", "description", "xp_reward", "target_progress", "is_secret", "tier", "icon_res_name"))
        for (d in defs) {
            c.addRow(arrayOf<Any?>(d.gameId, d.achievementId, d.name, d.description, d.xpReward, d.targetProgress, if (d.isSecret) 1 else 0, d.tier, d.iconResName))
        }
        return c
    }

    private fun cursorFromProgress(progresses: List<AchievementProgressEntity>): MatrixCursor {
        val c = MatrixCursor(arrayOf("game_id", "achievement_id", "progress", "is_unlocked", "unlocked_at", "last_updated"))
        for (p in progresses) {
            c.addRow(arrayOf<Any?>(p.gameId, p.achievementId, p.progress, if (p.isUnlocked) 1 else 0, p.unlockedAt, p.lastUpdated))
        }
        return c
    }

    private fun cursorFromLegacyAchievements(defs: List<AchievementDefEntity>, progressMap: Map<String, AchievementProgressEntity>): MatrixCursor {
        val c = MatrixCursor(arrayOf("achievement_id", "game_id", "name", "description", "icon_res_name", "unlocked", "unlocked_at"))
        for (d in defs) {
            val prog = progressMap[d.achievementId]
            c.addRow(arrayOf<Any?>(d.achievementId, d.gameId, d.name, d.description, d.iconResName, if (prog?.isUnlocked == true) 1 else 0, prog?.unlockedAt))
        }
        return c
    }

    private fun cursorFromLegacyAchievementItem(def: AchievementDefEntity?, prog: AchievementProgressEntity?): MatrixCursor {
        val c = MatrixCursor(arrayOf("achievement_id", "game_id", "name", "description", "icon_res_name", "unlocked", "unlocked_at"))
        val unlocked = prog?.isUnlocked == true
        c.addRow(arrayOf<Any?>(def?.achievementId ?: prog?.achievementId ?: "", def?.gameId ?: prog?.gameId ?: "", def?.name ?: "", def?.description ?: "", def?.iconResName, if (unlocked) 1 else 0, prog?.unlockedAt))
        return c
    }
}

class GamesHubLegacyProvider : GamesHubProvider()
