package com.vayunmathur.youpipe.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for all YouPipe persisted data.
 *
 * Owns the one [SubscriptionDatabase] instance (via [RoomRepository]) and is the
 * only place the 12 DAOs are touched. The [com.vayunmathur.youpipe.util.YouPipeViewModel],
 * [com.vayunmathur.youpipe.util.DownloadWorker], and
 * [com.vayunmathur.youpipe.util.SubscriptionFetchTask] all read/write through here
 * so they share one live, invalidation-backed view of the data.
 *
 * Read access is exposed as cold [Flow]s (consumers apply `stateIn`/`map` as before).
 * Writes are `suspend` wrappers over the existing atomic DAO queries, so no caller
 * ever holds a DAO directly.
 *
 * Uses the default `passwords-db` file name (same as all three previous
 * `buildDatabase<SubscriptionDatabase>()` call sites — MainActivity, DownloadWorker,
 * SubscriptionFetchTask — so the same DB file continues to be opened).
 */
class SubscriptionRepository private constructor(context: Context) :
    RoomRepository<SubscriptionDatabase>(context, SubscriptionDatabase::class) {

    private val subscriptionDao: SubscriptionDao get() = db.subscriptionDao()
    private val subscriptionCategoryDao: SubscriptionCategoryDao get() = db.subscriptionCategoryDao()
    private val subscriptionVideoDao: SubscriptionVideoDao get() = db.subscriptionVideoDao()
    private val historyVideoDao: HistoryVideoDao get() = db.historyVideoDao()
    private val downloadedVideoDao: DownloadedVideoDao get() = db.downloadedVideoDao()
    private val cachedRelatedVideoDao: CachedRelatedVideoDao get() = db.cachedRelatedVideoDao()
    private val recommendationImpressionDao: RecommendationImpressionDao get() = db.recommendationImpressionDao()
    private val recommendationPreferencesDao: RecommendationPreferencesDao get() = db.recommendationPreferencesDao()
    private val channelPreferenceDao: ChannelPreferenceDao get() = db.channelPreferenceDao()
    private val keywordPreferenceDao: KeywordPreferenceDao get() = db.keywordPreferenceDao()
    private val playlistDao: PlaylistDao get() = db.playlistDao()
    private val playlistItemDao: PlaylistItemDao get() = db.playlistItemDao()

    // ------------------------------------------------------------------
    // Read flows (cold)
    // ------------------------------------------------------------------

    val subscriptions: Flow<List<Subscription>> get() = subscriptionDao.getAllFlow()
    val subscriptionCategories: Flow<List<SubscriptionCategory>> get() = subscriptionCategoryDao.getAllFlow()
    val subscriptionVideos: Flow<List<SubscriptionVideo>> get() = subscriptionVideoDao.getAllFlow()
    val historyVideos: Flow<List<HistoryVideo>> get() = historyVideoDao.getAllFlow()
    val downloadedVideos: Flow<List<DownloadedVideo>> get() = downloadedVideoDao.getAllFlow()
    val playlists: Flow<List<Playlist>> get() = playlistDao.getAllFlow()
    val playlistItems: Flow<List<PlaylistItem>> get() = playlistItemDao.getAllFlow()
    val cachedRelatedVideos: Flow<List<CachedRelatedVideo>> get() = cachedRelatedVideoDao.getAllFlow()
    val recommendationImpressions: Flow<List<RecommendationImpression>> get() = recommendationImpressionDao.getAllFlow()
    val recommendationPreferencesFlow: Flow<RecommendationPreferences?> get() = recommendationPreferencesDao.getFlow()
    val channelPreferences: Flow<List<ChannelPreference>> get() = channelPreferenceDao.getAllFlow()
    val keywordPreferences: Flow<List<KeywordPreference>> get() = keywordPreferenceDao.getAllFlow()

    // By-id flows
    fun historyById(id: Long): Flow<HistoryVideo?> = historyVideoDao.getByIdFlow(id)
    fun downloadedById(id: Long): Flow<DownloadedVideo?> = downloadedVideoDao.getByIdFlow(id)
    fun playlistById(id: Long): Flow<Playlist?> = playlistDao.getByIdFlow(id)
    fun subscriptionById(id: Long): Flow<Subscription?> = subscriptionDao.getByIdFlow(id)
    fun subscriptionVideoById(id: Long): Flow<SubscriptionVideo?> = subscriptionVideoDao.getByIdFlow(id)
    fun subscriptionCategoryById(id: Long): Flow<SubscriptionCategory?> = subscriptionCategoryDao.getByIdFlow(id)
    fun playlistItemsFor(playlistId: Long): Flow<List<PlaylistItem>> = playlistItemDao.getForPlaylistFlow(playlistId)

    // ------------------------------------------------------------------
    // Subscription
    // ------------------------------------------------------------------

    suspend fun getAllSubscriptions(): List<Subscription> = subscriptionDao.getAll()
    suspend fun upsertSubscription(value: Subscription): Long = subscriptionDao.upsert(value)
    suspend fun upsertSubscriptions(values: List<Subscription>) = subscriptionDao.upsertAll(values)
    suspend fun deleteSubscription(value: Subscription): Int = subscriptionDao.delete(value)
    suspend fun clearAllSubscriptions() = subscriptionDao.clearAll()

    // ------------------------------------------------------------------
    // SubscriptionCategory
    // ------------------------------------------------------------------

    suspend fun replaceCategory(originalCategoryName: String?, categoryName: String, ids: List<Long>) =
        subscriptionCategoryDao.replaceCategory(originalCategoryName, categoryName, ids)

    suspend fun deleteCategory(categoryName: String) = subscriptionCategoryDao.deleteCategory(categoryName)
    suspend fun upsertSubscriptionCategories(items: List<SubscriptionCategory>) = subscriptionCategoryDao.upsertAll(items)

    // ------------------------------------------------------------------
    // SubscriptionVideo
    // ------------------------------------------------------------------

    suspend fun getAllSubscriptionVideos(): List<SubscriptionVideo> = subscriptionVideoDao.getAll()
    suspend fun upsertSubscriptionVideos(values: List<SubscriptionVideo>) = subscriptionVideoDao.upsertAll(values)

    // ------------------------------------------------------------------
    // HistoryVideo
    // ------------------------------------------------------------------

    suspend fun getAllHistoryVideos(): List<HistoryVideo> = historyVideoDao.getAll()
    suspend fun upsertHistoryVideo(value: HistoryVideo): Long = historyVideoDao.upsert(value)
    suspend fun upsertHistoryVideos(values: List<HistoryVideo>) = historyVideoDao.upsertAll(values)
    suspend fun deleteHistoryVideosByIds(ids: List<Long>) = historyVideoDao.deleteByIds(ids)
    suspend fun deleteHistoryVideo(value: HistoryVideo) = historyVideoDao.delete(value)
    suspend fun clearAllHistory() = historyVideoDao.clearAll()

    // ------------------------------------------------------------------
    // DownloadedVideo
    // ------------------------------------------------------------------

    suspend fun upsertDownloadedVideo(value: DownloadedVideo): Long = downloadedVideoDao.upsert(value)
    suspend fun deleteDownloadedVideo(value: DownloadedVideo): Int = downloadedVideoDao.delete(value)

    // ------------------------------------------------------------------
    // CachedRelatedVideo
    // ------------------------------------------------------------------

    suspend fun getAllCachedRelatedVideos(): List<CachedRelatedVideo> = cachedRelatedVideoDao.getAll()
    suspend fun upsertCachedRelatedVideos(values: List<CachedRelatedVideo>) = cachedRelatedVideoDao.upsertAll(values)
    suspend fun deleteCachedRelatedOlderThan(cutoff: kotlin.time.Instant) = cachedRelatedVideoDao.deleteOlderThan(cutoff)

    // ------------------------------------------------------------------
    // RecommendationImpression
    // ------------------------------------------------------------------

    suspend fun getAllRecommendationImpressions(): List<RecommendationImpression> = recommendationImpressionDao.getAll()
    suspend fun recordRecommendationImpression(videoID: Long, channelKey: String, source: String, now: kotlin.time.Instant) =
        recommendationImpressionDao.recordImpression(videoID, channelKey, source, now)
    suspend fun deleteRecommendationImpressionsOlderThan(cutoff: kotlin.time.Instant) = recommendationImpressionDao.deleteOlderThan(cutoff)
    suspend fun clearAllRecommendationImpressions() = recommendationImpressionDao.clearAll()

    // ------------------------------------------------------------------
    // RecommendationPreferences
    // ------------------------------------------------------------------

    suspend fun getRecommendationPreferences(): RecommendationPreferences? = recommendationPreferencesDao.get()
    suspend fun upsertRecommendationPreferences(value: RecommendationPreferences) = recommendationPreferencesDao.upsert(value)
    suspend fun clearAllRecommendationPreferences() = recommendationPreferencesDao.clearAll()

    // ------------------------------------------------------------------
    // ChannelPreference
    // ------------------------------------------------------------------

    suspend fun getAllChannelPreferences(): List<ChannelPreference> = channelPreferenceDao.getAll()
    suspend fun getChannelPreference(channelKey: String): ChannelPreference? = channelPreferenceDao.get(channelKey)
    suspend fun upsertChannelPreference(value: ChannelPreference) = channelPreferenceDao.upsert(value)
    suspend fun deleteChannelPreference(channelKey: String) = channelPreferenceDao.delete(channelKey)
    suspend fun clearAllChannelPreferences() = channelPreferenceDao.clearAll()

    // ------------------------------------------------------------------
    // KeywordPreference
    // ------------------------------------------------------------------

    suspend fun getAllKeywordPreferences(): List<KeywordPreference> = keywordPreferenceDao.getAll()
    suspend fun upsertKeywordPreference(value: KeywordPreference) = keywordPreferenceDao.upsert(value)
    suspend fun deleteKeywordPreference(keyword: String) = keywordPreferenceDao.delete(keyword)
    suspend fun clearAllKeywordPreferences() = keywordPreferenceDao.clearAll()

    // ------------------------------------------------------------------
    // Playlist
    // ------------------------------------------------------------------

    suspend fun getAllPlaylists(): List<Playlist> = playlistDao.getAll()
    suspend fun upsertPlaylist(value: Playlist): Long = playlistDao.upsert(value)
    suspend fun upsertPlaylists(values: List<Playlist>) = playlistDao.upsertAll(values)
    suspend fun deletePlaylist(value: Playlist): Int = playlistDao.delete(value)

    // ------------------------------------------------------------------
    // PlaylistItem
    // ------------------------------------------------------------------

    suspend fun getPlaylistItemsForPlaylist(playlistId: Long): List<PlaylistItem> = playlistItemDao.getForPlaylist(playlistId)
    suspend fun upsertPlaylistItem(value: PlaylistItem): Long = playlistItemDao.upsert(value)
    suspend fun upsertPlaylistItems(values: List<PlaylistItem>) = playlistItemDao.upsertAll(values)
    suspend fun deletePlaylistItem(value: PlaylistItem): Int = playlistItemDao.delete(value)
    suspend fun deletePlaylistItemByVideo(playlistId: Long, videoID: Long) = playlistItemDao.deleteByVideo(playlistId, videoID)

    companion object {
        @Volatile
        private var instance: SubscriptionRepository? = null

        fun get(context: Context): SubscriptionRepository =
            instance ?: synchronized(this) {
                instance ?: SubscriptionRepository(context).also { instance = it }
            }
    }
}
