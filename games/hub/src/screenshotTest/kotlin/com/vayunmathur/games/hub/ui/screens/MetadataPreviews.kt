package com.vayunmathur.games.hub.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.util.DashboardActions
import com.vayunmathur.games.hub.util.DashboardUiState
import com.vayunmathur.games.hub.util.GamesListActions
import com.vayunmathur.games.hub.util.GamesListUiState
import com.vayunmathur.games.hub.util.ProfileActions
import com.vayunmathur.games.hub.util.ProfileUiState
import com.vayunmathur.games.hub.viewmodel.CrossGameStats
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

/**
 * Store listing images for `:games:hub`, rendered from Compose previews instead of from an
 * instrumented test on a device. See `common-conventions-preview-metadata`.
 *
 * The three shots match what the old on-device generator produced — dashboard, games list,
 * profile — but the sample data is literal here rather than seeded into Room.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 *
 * Timestamps are relative to now so the "2h ago" style labels stay in their intended
 * bucket; the rendered text is the same on every run.
 */
class MetadataPreviews {

    private val now = System.currentTimeMillis()

    private val games = listOf(
        HubGameEntity("chess", "com.vayunmathur.games.chess", "Chess", "Classic chess with puzzles and a human-like AI", "1.0", registeredAt = now - 28 * DAY, lastSeenAt = now - HOUR, lastPlayedAt = now - HOUR, totalPlaytimeMs = 5 * HOUR + 23 * 60_000L, totalSessions = 42),
        HubGameEntity("alchemist", "com.vayunmathur.games.alchemist", "Alchemist", "Combine elements", registeredAt = now - 25 * DAY, lastSeenAt = now - 3 * HOUR, lastPlayedAt = now - 3 * HOUR, totalPlaytimeMs = 3 * HOUR, totalSessions = 28),
        HubGameEntity("pipes", "com.vayunmathur.games.pipes", "Pipes", "Connect the pipes", registeredAt = now - 20 * DAY, lastSeenAt = now - 5 * HOUR, lastPlayedAt = now - 5 * HOUR, totalPlaytimeMs = 2 * HOUR + 45 * 60_000L, totalSessions = 35),
        HubGameEntity("solitaire", "com.vayunmathur.games.solitaire", "Solitaire", "Classic Klondike", registeredAt = now - 18 * DAY, lastSeenAt = now - 8 * HOUR, lastPlayedAt = now - 8 * HOUR, totalPlaytimeMs = 4 * HOUR, totalSessions = 51),
        HubGameEntity("wordmaker", "com.vayunmathur.games.wordmaker", "Word Maker", "Create words from letters", registeredAt = now - 15 * DAY, lastSeenAt = now - 12 * HOUR, lastPlayedAt = now - 12 * HOUR, totalPlaytimeMs = HOUR + 30 * 60_000L, totalSessions = 19),
        HubGameEntity("unblockjam", "com.vayunmathur.games.unblockjam", "Unblock Jam", "Slide blocks to clear the path", registeredAt = now - 10 * DAY, lastSeenAt = now - DAY, lastPlayedAt = now - DAY - 2 * HOUR, totalPlaytimeMs = 55 * 60_000L, totalSessions = 12),
    )

    private val achievementProgress = mapOf(
        "chess" to (2 to 3),
        "alchemist" to (1 to 1),
        "pipes" to (1 to 1),
        "solitaire" to (1 to 1),
        "wordmaker" to (1 to 1),
        "unblockjam" to (1 to 1),
    )

    /** 1225 XP is level 4 ("Casual Gamer") under [com.vayunmathur.games.hub.util.XpLevelCalculator]. */
    private val stats = CrossGameStats(
        totalPlaytimeMs = 17 * HOUR + 38 * 60_000L,
        totalSessions = 187,
        totalGames = 6,
        totalAchievementsUnlocked = 7,
        totalAchievements = 8,
        totalXp = 1225,
        level = 4,
        currentStreak = 5,
        longestStreak = 12,
    )

    @PreviewTest
    @Preview(name = "1-dashboard", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Dashboard() {
        DynamicTheme(darkTheme = true) {
            DashboardScreen(
                state = DashboardUiState(
                    playerName = "Alex Rivera",
                    level = 4,
                    title = "Casual Gamer",
                    totalXp = 1225,
                    stats = stats,
                    recentlyPlayed = games.take(3),
                    recentActivity = listOf(
                        ActivityEventEntity(id = 1, type = ActivityEventEntity.TYPE_SESSION_COMPLETED, gameId = "chess", title = "Played Chess", description = "Session 25m", timestamp = now - HOUR),
                        ActivityEventEntity(id = 2, type = ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED, gameId = "solitaire", title = "Card Shark", description = "Won 10 games", timestamp = now - 2 * HOUR),
                        ActivityEventEntity(id = 3, type = ActivityEventEntity.TYPE_LEVEL_UP, title = "Reached level 4", description = "Casual Gamer", timestamp = now - 6 * HOUR),
                    ),
                    achievementProgressByGame = achievementProgress,
                    installedGameIds = games.mapTo(mutableSetOf()) { it.gameId },
                ),
                actions = DashboardActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-games", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Games() {
        DynamicTheme(darkTheme = true) {
            GamesListScreen(
                state = GamesListUiState(
                    games = games,
                    achievementProgressByGame = achievementProgress,
                    installedGameIds = games.mapTo(mutableSetOf()) { it.gameId },
                ),
                actions = GamesListActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-profile", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Profile() {
        DynamicTheme(darkTheme = true) {
            ProfileScreen(
                state = ProfileUiState(
                    playerName = "Alex Rivera",
                    avatarSymbol = "stadia_controller",
                    level = 4,
                    title = "Casual Gamer",
                    totalXp = 1225,
                    stats = stats,
                ),
                actions = ProfileActions.Noop,
            )
        }
    }
}
