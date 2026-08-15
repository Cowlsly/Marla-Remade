package com.vayunmathur.web.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

class WebRepository private constructor(context: Context) :
    RoomRepository<WebDatabase>(context, WebDatabase::class, DB_NAME) {

    private val historyDao: HistoryDao get() = db.historyDao()
    private val bookmarkDao: BookmarkDao get() = db.bookmarkDao()
    private val sitePermissionDao: SitePermissionDao get() = db.sitePermissionDao()
    private val storageInfoDao: StorageInfoDao get() = db.storageInfoDao()
    private val downloadDao: DownloadDao get() = db.downloadDao()
    private val installedSiteDao: InstalledSiteDao get() = db.installedSiteDao()
    private val shieldSettingDao: ShieldSettingDao get() = db.shieldSettingDao()

    // History
    fun allHistoryFlow(): Flow<List<HistoryEntry>> = historyDao.allFlow()
    fun searchHistoryFlow(query: String): Flow<List<HistoryEntry>> = historyDao.searchFlow(query)
    suspend fun upsertHistory(entry: HistoryEntry): Long = historyDao.upsert(entry)
    suspend fun clearAllHistory() = historyDao.clearAll()
    suspend fun deleteHistoryBefore(before: Long) = historyDao.deleteBefore(before)
    suspend fun recentHistory(limit: Int): List<HistoryEntry> = historyDao.recent(limit)

    // Bookmark
    fun allBookmarksFlow(): Flow<List<Bookmark>> = bookmarkDao.allFlow()
    fun bookmarksByFolderFlow(folderId: Long?): Flow<List<Bookmark>> = bookmarkDao.byFolderFlow(folderId)
    suspend fun bookmarkByUrl(url: String): Bookmark? = bookmarkDao.byUrl(url)
    fun bookmarkByUrlFlow(url: String): Flow<Bookmark?> = bookmarkDao.byUrlFlow(url)
    suspend fun upsertBookmark(entry: Bookmark): Long = bookmarkDao.upsert(entry)
    suspend fun deleteBookmark(entry: Bookmark) = bookmarkDao.delete(entry)
    fun bookmarkFoldersFlow(): Flow<List<BookmarkFolder>> = bookmarkDao.foldersFlow()
    suspend fun upsertBookmarkFolder(folder: BookmarkFolder): Long = bookmarkDao.upsertFolder(folder)
    suspend fun deleteBookmarkFolder(folder: BookmarkFolder) = bookmarkDao.deleteFolder(folder)
    suspend fun deleteBookmarksByFolder(folderId: Long) = bookmarkDao.deleteByFolder(folderId)

    // SitePermission
    fun allSitePermissionsFlow(): Flow<List<SitePermission>> = sitePermissionDao.allFlow()
    suspend fun sitePermissionByOrigin(origin: String): SitePermission? = sitePermissionDao.byOrigin(origin)
    fun sitePermissionByOriginFlow(origin: String): Flow<SitePermission?> = sitePermissionDao.byOriginFlow(origin)
    suspend fun upsertSitePermission(p: SitePermission): Long = sitePermissionDao.upsert(p)
    suspend fun deleteSitePermission(p: SitePermission) = sitePermissionDao.delete(p)
    suspend fun clearAllSitePermissions() = sitePermissionDao.clearAll()
    suspend fun deleteSitePermissionOrigin(origin: String) = sitePermissionDao.deleteOrigin(origin)

    // StorageInfo
    fun allStorageInfosFlow(): Flow<List<StorageInfo>> = storageInfoDao.allFlow()
    suspend fun storageInfoByOrigin(origin: String): StorageInfo? = storageInfoDao.byOrigin(origin)
    suspend fun upsertStorageInfo(info: StorageInfo): Long = storageInfoDao.upsert(info)
    suspend fun deleteStorageInfo(info: StorageInfo) = storageInfoDao.delete(info)
    suspend fun deleteStorageInfoOrigin(origin: String) = storageInfoDao.deleteOrigin(origin)
    suspend fun clearAllStorageInfos() = storageInfoDao.clearAll()

    // Download
    fun allDownloadsFlow(): Flow<List<DownloadEntry>> = downloadDao.allFlow()
    suspend fun upsertDownload(d: DownloadEntry): Long = downloadDao.upsert(d)
    suspend fun deleteDownloadById(id: Long) = downloadDao.deleteById(id)
    suspend fun clearAllDownloads() = downloadDao.clearAll()

    // InstalledSite
    fun allInstalledSitesFlow(): Flow<List<InstalledSite>> = installedSiteDao.allFlow()
    suspend fun installedSiteById(id: String): InstalledSite? = installedSiteDao.byId(id)
    suspend fun installedSiteByOrigin(origin: String): InstalledSite? = installedSiteDao.byOrigin(origin)
    suspend fun upsertInstalledSite(site: InstalledSite) = installedSiteDao.upsert(site)
    suspend fun deleteInstalledSiteById(id: String) = installedSiteDao.deleteById(id)
    suspend fun clearAllInstalledSites() = installedSiteDao.clearAll()

    // ShieldSetting
    fun allShieldSettingsFlow(): Flow<List<ShieldSetting>> = shieldSettingDao.allFlow()
    suspend fun allShieldSettings(): List<ShieldSetting> = shieldSettingDao.all()
    suspend fun shieldSettingByHost(host: String): ShieldSetting? = shieldSettingDao.byHost(host)
    suspend fun upsertShieldSetting(setting: ShieldSetting) = shieldSettingDao.upsert(setting)
    suspend fun deleteShieldSettingHost(host: String) = shieldSettingDao.deleteHost(host)
    suspend fun clearAllShieldSettings() = shieldSettingDao.clearAll()

    companion object {
        @Volatile private var instance: WebRepository? = null
        fun get(context: Context): WebRepository =
            instance ?: synchronized(this) {
                instance ?: WebRepository(context).also { instance = it }
            }
    }
}
