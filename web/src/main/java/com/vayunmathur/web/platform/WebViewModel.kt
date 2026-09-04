@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.web.platform

import kotlin.uuid.Uuid
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.web.data.Bookmark
import com.vayunmathur.web.data.BookmarkFolder
import com.vayunmathur.web.data.DownloadEntry
import com.vayunmathur.web.data.FaviconStore
import com.vayunmathur.web.data.HistoryEntry
import com.vayunmathur.web.data.InstalledSite
import com.vayunmathur.web.data.SitePermission
import com.vayunmathur.web.data.TabThumbnailStore
import com.vayunmathur.web.data.WebRepository
import com.vayunmathur.web.data.ShieldSetting
import com.vayunmathur.web.data.StorageInfo
import androidx.core.content.ContextCompat
import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.domain.LocalNetwork
import com.vayunmathur.web.domain.ShieldLevel
import com.vayunmathur.web.domain.ShieldsSettings
import com.vayunmathur.web.platform.shields.FarblingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "WebViewModel"
private const val P_SAVED_TABS = "web_saved_tabs"
private const val P_ACTIVE_TAB = "web_active_tab_id"
private const val P_CACHE_MODE = "web_cache_mode"
private const val P_JS_ENABLED = "web_js_enabled"
private const val P_BLOCK_THIRD_PARTY = "web_block_third_party"
private const val P_DESKTOP_MODE = "web_desktop_mode"
private const val P_SEARCH_ENGINE = "web_search_engine"
private const val P_SHIELD_LEVEL = "web_shield_level"
private const val P_SHIELD_TRACKERS = "web_shield_trackers"
private const val P_SHIELD_COSMETIC = "web_shield_cosmetic"
private const val P_SHIELD_FINGERPRINT = "web_shield_fingerprint"
private const val P_SHIELD_HTTPS = "web_shield_https"
private const val P_LOCAL_NETWORK_DENIED = "web_local_network_denied"
private const val P_SEARCH_BAR_BOTTOM = "web_search_bar_bottom"

data class PermissionPrompt(
    val id: String = Uuid.random().toString(),
    val origin: String,
    val types: List<SitePermissionType>,
    val onGrant: (List<SitePermissionType>) -> Unit,
    val onDeny: () -> Unit,
)

class WebViewModel(
    private val repository: WebRepository,
    private val context: Context,
    /** Identifies this window's independent tab set; the default window keeps the legacy pref keys. */
    private val windowId: String = DEFAULT_WINDOW_ID,
    /** An incognito window: every tab is private and nothing is persisted. */
    val incognito: Boolean = false,
    /**
     * Site exceptions read before the UI was allowed to render. Seeding them here rather
     * than waiting for [ShieldSettingDao.allFlow] is what stops the first page of a cold
     * start from being farbled on a site the user turned shields off for.
     */
    initialShieldSettings: List<ShieldSetting> = emptyList(),
) : ViewModel() {

    companion object {
        const val DEFAULT_WINDOW_ID = "main"

        /** Asked at most once per process, however many windows are open. */
        @Volatile
        private var localNetworkAsked = false
    }

    // Persistence keys are namespaced per window; the default window keeps the legacy keys for back-compat.
    private val savedTabsKey = if (windowId == DEFAULT_WINDOW_ID) P_SAVED_TABS else "${P_SAVED_TABS}_$windowId"
    private val activeTabKey = if (windowId == DEFAULT_WINDOW_ID) P_ACTIVE_TAB else "${P_ACTIVE_TAB}_$windowId"

    private fun blankTab() = BrowserTab(id = Uuid.random().toString(), url = "", isPrivate = incognito)

    val tabs = mutableStateListOf<BrowserTab>()
    var activeTabId by mutableStateOf<String?>(null)
        private set

    var omniboxText by mutableStateOf("")
    var omniboxFocused by mutableStateOf(false)
    var searchDraft by mutableStateOf("")

    var searchEngine by mutableStateOf(SearchEngine.DEFAULT)
    val homepage: String get() = searchEngine.homepage

    var cacheMode by mutableStateOf(CacheMode.DEFAULT)
    var jsEnabled by mutableStateOf(true)
    var blockThirdPartyCookies by mutableStateOf(false)
    var desktopMode by mutableStateOf(false)

    /** Toolbar edge. Defaults to the bottom, within thumb reach on a phone. */
    var searchBarAtBottom by mutableStateOf(true)

    /** Global Brave Shields defaults; per-site overrides live in [shieldSettings]. */
    var shields by mutableStateOf(ShieldsSettings.AGGRESSIVE_DEFAULTS)
        private set

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks

    private val _folders = MutableStateFlow<List<BookmarkFolder>>(emptyList())
    val folders: StateFlow<List<BookmarkFolder>> = _folders

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history

    private val _sitePermissions = MutableStateFlow<List<SitePermission>>(emptyList())
    val sitePermissions: StateFlow<List<SitePermission>> = _sitePermissions

    private val _storageInfos = MutableStateFlow<List<StorageInfo>>(emptyList())
    val storageInfos: StateFlow<List<StorageInfo>> = _storageInfos

    private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val downloads: StateFlow<List<DownloadEntry>> = _downloads

    private val _installedSites = MutableStateFlow<List<InstalledSite>>(emptyList())
    val installedSites: StateFlow<List<InstalledSite>> = _installedSites

    private val _shieldSettings = MutableStateFlow(initialShieldSettings)
    val shieldSettings: StateFlow<List<ShieldSetting>> = _shieldSettings

    /**
     * Per-host overrides mirrored synchronously because `shouldInterceptRequest` runs on the
     * render thread and cannot wait on a coroutine or a database read.
     */
    private val shieldOverrides = java.util.concurrent.ConcurrentHashMap<String, ShieldsSettings>()
        .apply { initialShieldSettings.forEach { put(it.host, it.toSettings()) } }

    /**
     * Blocked-request tallies. The counting side is hit from the render thread, so the
     * authoritative totals live in a concurrent map and only the UI mirror is a snapshot
     * state ΓÇö writing Compose state off the main thread is not safe.
     */
    private val blockedTotals = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val blockedPublishPending = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val blockedCounts = mutableStateMapOf<String, Int>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Whether the shields panel is open; the host it targets is always the active tab's. */
    var showShieldsPanel by mutableStateOf(false)

    var pendingPermissionPrompt by mutableStateOf<PermissionPrompt?>(null)
        private set

    var pendingGeolocationPrompt by mutableStateOf<Triple<String, () -> Unit, () -> Unit>?>(null)
        private set

    /** The LAN host whose page needs [WebPermissions.LOCAL_NETWORK] before it can load. */
    var pendingLocalNetworkHost by mutableStateOf<String?>(null)
        private set

    /** A previous denial, remembered across process death so we stop asking. */
    private var localNetworkDenied = false

    var pendingFileChooser by mutableStateOf<Pair<android.webkit.ValueCallback<Array<Uri>>, android.webkit.WebChromeClient.FileChooserParams>?>(null)
        private set

    var showTabSwitcher by mutableStateOf(false)

    private val tabTitles = mutableMapOf<String, String>()
    private val tabProgress = mutableMapOf<String, Float>()
    private val tabCanGoBack = mutableMapOf<String, Boolean>()
    private val tabCanGoForward = mutableMapOf<String, Boolean>()
    private val tabCurrentUrl = mutableMapOf<String, String>()

    // PWA / installed site detection per tab
    val pwaInfos = mutableStateMapOf<String, PwaInfo>()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Resolves a tab's live WebView. `BrowserPage` owns the pool, so tab-lifecycle code here
     * has no other way to reach the view it needs to draw before a tab stops being active.
     */
    private var liveWebViews: ((String) -> WebView?)? = null

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val sp = context.getSharedPreferences("web_prefs", Context.MODE_PRIVATE)
                    // Incognito windows never restore persisted tabs ΓÇö they start fresh and private.
                    val savedTabs = if (incognito) null else sp.getString(savedTabsKey, null)
                    val activeId = if (incognito) null else sp.getString(activeTabKey, null)
                    val cacheModeName = sp.getString(P_CACHE_MODE, null)
                    val searchEngineName = sp.getString(P_SEARCH_ENGINE, null)
                    val js = sp.getBoolean(P_JS_ENABLED, true)
                    val blockThird = sp.getBoolean(P_BLOCK_THIRD_PARTY, false)
                    val desktop = sp.getBoolean(P_DESKTOP_MODE, false)
                    val lanDenied = sp.getBoolean(P_LOCAL_NETWORK_DENIED, false)
                    val barAtBottom = sp.getBoolean(P_SEARCH_BAR_BOTTOM, true)
                    val defaults = ShieldsSettings.AGGRESSIVE_DEFAULTS
                    val savedShields = ShieldsSettings(
                        level = sp.getString(P_SHIELD_LEVEL, null)
                            ?.let { runCatching { ShieldLevel.valueOf(it) }.getOrNull() }
                            ?: defaults.level,
                        blockTrackers = sp.getBoolean(P_SHIELD_TRACKERS, true),
                        cosmeticFiltering = sp.getBoolean(P_SHIELD_COSMETIC, true),
                        fingerprintProtection = sp.getBoolean(P_SHIELD_FINGERPRINT, true),
                        httpsUpgrade = sp.getBoolean(P_SHIELD_HTTPS, true),
                    )
                    withContext(Dispatchers.Main) {
                        shields = savedShields
                        cacheModeName?.let { runCatching { CacheMode.valueOf(it) }.getOrNull()?.let { cm -> cacheMode = cm } }
                        searchEngineName?.let { runCatching { SearchEngine.valueOf(it) }.getOrNull()?.let { se -> searchEngine = se } }
                        jsEnabled = js
                        blockThirdPartyCookies = blockThird
                        desktopMode = desktop
                        localNetworkDenied = lanDenied
                        searchBarAtBottom = barAtBottom

                        // Capture any tabs that were already created (e.g., from an external intent arriving before restore finishes)
                        val preExisting = tabs.toList()
                        val preExistingActive = activeTabId

                        val decodedSaved: List<BrowserTab>? = if (savedTabs != null) {
                            runCatching { json.decodeFromString<List<BrowserTab>>(savedTabs) }.getOrNull()?.takeIf { it.isNotEmpty() }
                        } else null

                        when {
                            decodedSaved != null -> {
                                if (preExisting.isNotEmpty()) {
                                    // Merge: keep saved tabs + any extra tabs created before restore (like external URL)
                                    val decodedIds = decodedSaved.map { it.id }.toSet()
                                    val extras = preExisting.filter { it.id !in decodedIds }
                                    tabs.clear()
                                    tabs.addAll(decodedSaved)
                                    if (extras.isNotEmpty()) {
                                        tabs.addAll(extras)
                                        // If the active tab was one of the extras, keep it active (new tab wins)
                                        if (preExistingActive != null && extras.any { it.id == preExistingActive }) {
                                            activeTabId = preExistingActive
                                        } else {
                                            activeTabId = activeId ?: tabs.firstOrNull()?.id
                                        }
                                    } else {
                                        activeTabId = activeId ?: tabs.firstOrNull()?.id
                                    }
                                } else {
                                    tabs.clear()
                                    tabs.addAll(decodedSaved)
                                    activeTabId = activeId ?: tabs.firstOrNull()?.id
                                }
                            }
                            preExisting.isNotEmpty() -> {
                                // No saved tabs but we already have tabs (external intent race) -> keep them
                                if (activeTabId == null) activeTabId = preExisting.firstOrNull()?.id
                            }
                            else -> {
                                // No saved and no pre-existing -> will create blank below
                            }
                        }

                        if (tabs.isEmpty()) {
                            val tab = blankTab()
                            tabs.add(tab)
                            activeTabId = tab.id
                        } else {
                            // Migrate legacy new-tabs that were saved as duckduckgo.com -> blank New Tab
                            val migrated = tabs.map { t ->
                                val isHomepage = t.url == BrowserUtils.HOMEPAGE || t.url == "${BrowserUtils.HOMEPAGE}/"
                                val isPlaceholderTitle = t.title.isBlank() || t.title == BrowserUtils.HOMEPAGE || t.title == "${BrowserUtils.HOMEPAGE}/"
                                if (isHomepage && isPlaceholderTitle) t.copy(url = "", title = "") else t
                            }
                            if (migrated != tabs.toList()) {
                                tabs.clear()
                                tabs.addAll(migrated)
                            }
                            // Ensure active id is still valid after migration
                            if (activeTabId == null || tabs.none { it.id == activeTabId }) {
                                activeTabId = activeId ?: tabs.firstOrNull()?.id
                            }
                        }
                        activeTab?.let {
                            omniboxText = if (it.url.isBlank() || it.url == "about:blank") "" else it.url
                            searchDraft = omniboxText
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load prefs", e)
                    withContext(Dispatchers.Main) {
                        if (tabs.isEmpty()) {
                            val tab = blankTab()
                            tabs.add(tab)
                            activeTabId = tab.id
                        }
                    }
                }
            }
        }

        viewModelScope.launch { repository.allBookmarksFlow().collect { _bookmarks.value = it } }
        viewModelScope.launch { repository.bookmarkFoldersFlow().collect { _folders.value = it } }
        viewModelScope.launch { repository.allHistoryFlow().collect { _history.value = it } }
        viewModelScope.launch { repository.allSitePermissionsFlow().collect { _sitePermissions.value = it } }
        viewModelScope.launch { repository.allStorageInfosFlow().collect { _storageInfos.value = it } }
        viewModelScope.launch { repository.allDownloadsFlow().collect { _downloads.value = it } }
        viewModelScope.launch { repository.allInstalledSitesFlow().collect { _installedSites.value = it } }
        viewModelScope.launch {
            repository.allShieldSettingsFlow().collect { settings ->
                _shieldSettings.value = settings
                shieldOverrides.clear()
                settings.forEach { shieldOverrides[it.host] = it.toSettings() }
            }
        }
    }

    // ---- Brave Shields ----

    /**
     * Resolved shields for [host]. Safe to call from the WebView render thread: it only
     * touches the concurrent override map, never the Compose-backed tab list.
     *
     * @param isPrivate the owning tab is private, which forces the full aggressive preset
     */
    fun shieldsFor(host: String, isPrivate: Boolean = false): EffectiveShields {
        if (isPrivate || incognito) {
            return EffectiveShields.resolve(ShieldsSettings.AGGRESSIVE_DEFAULTS)
        }
        return EffectiveShields.resolve(shields, shieldOverrides[host])
    }

    /**
     * Snapshot of the farbling decision for every site, for the document-start script.
     * Changes to this are what force a script re-registration.
     */
    fun farblingConfig(): FarblingConfig = FarblingConfig.of(shields, shieldOverrides.toMap())

    fun updateShields(settings: ShieldsSettings) {
        shields = settings
        persistPrefs()
    }

    fun updateSiteShields(host: String, settings: ShieldsSettings) {
        if (host.isBlank()) return
        // Mirror immediately so the next request sees it without waiting for Room.
        if (settings.isEmpty) shieldOverrides.remove(host) else shieldOverrides[host] = settings
        viewModelScope.launch {
            if (settings.isEmpty) {
                repository.deleteShieldSettingHost(host)
            } else {
                repository.upsertShieldSetting(ShieldSetting.from(host, settings))
            }
        }
    }

    fun clearSiteShields() {
        shieldOverrides.clear()
        viewModelScope.launch { repository.clearAllShieldSettings() }
    }

    /** Called from the render thread on every blocked request; coalesces UI updates. */
    fun onRequestBlocked(tabId: String) {
        blockedTotals.computeIfAbsent(tabId) { java.util.concurrent.atomic.AtomicInteger() }
            .incrementAndGet()
        if (blockedPublishPending.putIfAbsent(tabId, true) == null) {
            mainHandler.post {
                blockedPublishPending.remove(tabId)
                blockedCounts[tabId] = blockedTotals[tabId]?.get() ?: 0
            }
        }
    }

    fun resetBlockedCount(tabId: String) {
        blockedTotals.remove(tabId)
        blockedCounts[tabId] = 0
    }

    fun blockedCount(tabId: String): Int = blockedCounts[tabId] ?: 0

    // ---- Thumbnails and favicons ----

    fun setWebViewLookup(lookup: ((String) -> WebView?)?) { liveWebViews = lookup }

    /** The tab's page thumbnail, or null when there isn't one yet. Compose-observable. */
    fun thumbnailFor(tabId: String): Bitmap? = TabThumbnailStore.get(tabId)

    /** The site icon for [url]'s host, or null if that host has never been visited. */
    fun faviconFor(url: String): Bitmap? = FaviconStore.forUrl(url)

    fun captureThumbnail(tabId: String, webView: WebView) {
        val tab = tabs.find { it.id == tabId } ?: return
        // A blank new tab would store a white rectangle, which reads worse than the
        // placeholder the grid draws when there is no thumbnail at all.
        if (tab.isNewTab) return
        TabThumbnailStore.capture(tabId, webView, incognito || tab.isPrivate)
    }

    /** Draws the tab that is about to stop being active, so its tile is not left stale. */
    private fun captureActiveTab() {
        val id = activeTabId ?: return
        val webView = liveWebViews?.invoke(id) ?: return
        captureThumbnail(id, webView)
    }

    val activeTab: BrowserTab? get() = tabs.find { it.id == activeTabId }

    fun onTabUrlChange(tabId: String, url: String) {
        tabCurrentUrl[tabId] = url
        updateTab(tabId) { it.copy(url = url) }
        persistTabs()
        if (tabId == activeTabId && !omniboxFocused) {
            omniboxText = if (url.isBlank() || url == "about:blank") "" else url
        }
        if (url.startsWith("http")) {
            val origin = BrowserUtils.originFromUrl(url)
            val host = BrowserUtils.hostFromUrl(url)
            viewModelScope.launch {
                val existing = repository.storageInfoByOrigin(origin)
                if (existing == null) {
                    repository.upsertStorageInfo(StorageInfo(origin = origin, host = host, lastSeen = System.currentTimeMillis()))
                } else {
                    repository.upsertStorageInfo(existing.copy(lastSeen = System.currentTimeMillis(), host = host))
                }
            }
        }
    }

    fun onTabTitleChange(tabId: String, title: String) {
        tabTitles[tabId] = title
        updateTab(tabId) { it.copy(title = title) }
        persistTabs()
    }

    fun getTabTitle(tabId: String): String = tabTitles[tabId] ?: tabs.find { it.id == tabId }?.title ?: ""

    fun onPwaInfoDetected(tabId: String, info: PwaInfo) {
        pwaInfos[tabId] = info
    }

    fun onTabProgress(tabId: String, progress: Float) { tabProgress[tabId] = progress }
    fun onTabCanGoBack(tabId: String, value: Boolean) { tabCanGoBack[tabId] = value }
    fun onTabCanGoForward(tabId: String, value: Boolean) { tabCanGoForward[tabId] = value }

    fun getProgress(tabId: String) = tabProgress[tabId] ?: 0f
    fun getCanGoBack(tabId: String) = tabCanGoBack[tabId] ?: false
    fun getCanGoForward(tabId: String) = tabCanGoForward[tabId] ?: false
    fun getCurrentUrl(tabId: String) = tabCurrentUrl[tabId] ?: tabs.find { it.id == tabId }?.url ?: ""
    fun getPwaInfo(tabId: String) = pwaInfos[tabId]

    private fun updateTab(tabId: String, transform: (BrowserTab) -> BrowserTab) {
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx >= 0) tabs[idx] = transform(tabs[idx])
    }

    fun newTab(url: String = "", makeActive: Boolean = true, isPrivate: Boolean = false) {
        if (makeActive) captureActiveTab()
        // Every tab in an incognito window is private, regardless of the caller's request.
        val tab = BrowserTab(id = Uuid.random().toString(), url = url, isPrivate = isPrivate || incognito)
        tabs.add(tab)
        if (makeActive) {
            activeTabId = tab.id
            omniboxFocused = false
            omniboxText = if (url.isBlank() || url == "about:blank") "" else url
            searchDraft = if (url.isBlank() || url == "about:blank") "" else url
        }
        persistTabs()
    }

    fun closeTab(tabId: String) {
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return
        tabs.removeAt(idx)
        tabTitles.remove(tabId)
        tabProgress.remove(tabId)
        tabCanGoBack.remove(tabId)
        tabCanGoForward.remove(tabId)
        tabCurrentUrl.remove(tabId)
        blockedCounts.remove(tabId)
        blockedTotals.remove(tabId)
        pwaInfos.remove(tabId)
        TabThumbnailStore.remove(tabId)
        if (activeTabId == tabId) {
            activeTabId = when {
                tabs.isEmpty() -> {
                    val tab = blankTab()
                    tabs.add(tab)
                    tab.id
                }
                idx < tabs.size -> tabs[idx].id
                else -> tabs.last().id
            }
            val cur = activeTab
            omniboxText = if (cur == null || cur.url.isBlank() || cur.url == "about:blank") "" else cur.url
            searchDraft = omniboxText
        }
        persistTabs()
    }

    /**
     * Tab order is the persisted order — [persistTabsSync] writes the list as it stands — so a
     * reorder needs nothing beyond moving the entry and saving.
     */
    fun moveTab(from: Int, to: Int) {
        if (from == to || from !in tabs.indices || to !in tabs.indices) return
        tabs.add(to, tabs.removeAt(from))
        persistTabs()
    }

    fun switchToTab(tabId: String) {
        if (tabId != activeTabId) captureActiveTab()
        activeTabId = tabId
        val cur = activeTab
        omniboxText = if (cur == null || cur.url.isBlank() || cur.url == "about:blank") "" else cur.url
        searchDraft = omniboxText
        showTabSwitcher = false
        persistTabs()
    }

    fun navigateActiveTab(input: String) {
        val active = activeTab ?: return
        val dest = BrowserUtils.toNavigationUrl(input, searchEngine)
        noteNavigation(dest)
        onTabUrlChange(active.id, dest)
        omniboxFocused = false
    }

    // ---- Local network permission ----

    /**
     * Raises [pendingLocalNetworkHost] when [url] is a LAN address we cannot reach yet.
     *
     * Called from the omnibox (so the prompt lands before the first request in the common
     * typed-URL case) and from `onPageStarted` as the catch-all, since
     * `shouldOverrideUrlLoading` never fires for a programmatic `loadUrl`, a redirect or a
     * session restore.
     *
     * Classification is syntactic only — this runs on the main thread and must never do DNS.
     */
    fun noteNavigation(url: String) {
        val permission = WebPermissions.LOCAL_NETWORK ?: return
        if (localNetworkAsked || localNetworkDenied || pendingLocalNetworkHost != null) return
        val host = LocalNetwork.hostOf(url)
        if (host.isEmpty() || !LocalNetwork.isLanHostSyntactic(host)) return
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return
        pendingLocalNetworkHost = host
    }

    /** Dismisses the prompt. A denial is remembered so LAN pages stop nagging. */
    fun clearLocalNetworkPrompt(denied: Boolean) {
        pendingLocalNetworkHost = null
        localNetworkAsked = true
        if (!denied) return
        localNetworkDenied = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.getSharedPreferences("web_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean(P_LOCAL_NETWORK_DENIED, true).apply()
            }.onFailure { Log.e(TAG, "persist local network denial failed", it) }
        }
    }

    fun recordHistoryVisit(url: String, title: String) {
        if (url.isBlank() || url == "about:blank" || url.startsWith("data:")) return
        if (activeTab?.isPrivate == true) return
        viewModelScope.launch {
            runCatching { repository.upsertHistory(HistoryEntry(url = url, title = title)) }
                .onFailure { Log.e(TAG, "recordHistory", it) }
        }
    }

    fun addBookmark(url: String, title: String, folderId: Long? = null) {
        viewModelScope.launch { runCatching { repository.upsertBookmark(Bookmark(url = url, title = title, folderId = folderId)) } }
    }

    fun removeBookmark(bookmark: Bookmark) {
        viewModelScope.launch { repository.deleteBookmark(bookmark) }
    }

    fun createFolder(name: String) {
        viewModelScope.launch { runCatching { repository.upsertBookmarkFolder(BookmarkFolder(name = name)) } }
    }

    fun deleteFolder(folder: BookmarkFolder) {
        viewModelScope.launch {
            runCatching {
                repository.deleteBookmarksByFolder(folder.id)
                repository.deleteBookmarkFolder(folder)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearAllHistory() }
    }

    fun updateCacheMode(mode: CacheMode) {
        cacheMode = mode
        persistPrefs()
    }

    fun updateSearchEngine(engine: SearchEngine) {
        searchEngine = engine
        persistPrefs()
    }

    fun updateJsEnabled(enabled: Boolean) {
        jsEnabled = enabled
        persistPrefs()
    }

    fun updateBlockThirdParty(block: Boolean) {
        blockThirdPartyCookies = block
        persistPrefs()
    }

    fun updateDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        persistPrefs()
    }

    fun updateSearchBarAtBottom(atBottom: Boolean) {
        searchBarAtBottom = atBottom
        persistPrefs()
    }

    // ---- PWA / Installed sites ----
    fun installAsPwa(
        tabId: String,
        url: String,
        pwaInfo: PwaInfo?,
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val origin = BrowserUtils.originFromUrl(url)
                val id = PwaHelper.shortcutId(url)
                val title = PwaHelper.displayTitle(pwaInfo, tabs.find { it.id == tabId }?.title ?: "", url)
                val entry = InstalledSite(
                    id = id,
                    url = url,
                    title = title,
                    shortName = pwaInfo?.shortName ?: "",
                    iconUrl = pwaInfo?.iconUrl,
                    faviconUrl = pwaInfo?.faviconUrl,
                    themeColor = pwaInfo?.themeColor,
                    backgroundColor = pwaInfo?.backgroundColor,
                    displayMode = pwaInfo?.displayMode ?: "standalone",
                    startUrl = pwaInfo?.startUrl ?: url,
                    origin = origin,
                )
                repository.upsertInstalledSite(entry)
                val accepted = PwaHelper.requestPinShortcut(
                    context = context,
                    url = url,
                    title = title,
                    iconUrl = entry.iconUrl,
                    faviconUrl = entry.faviconUrl,
                )
                onResult(accepted)
            } catch (e: Exception) {
                Log.e(TAG, "installAsPwa failed", e)
                onResult(false)
            }
        }
    }

    fun removeInstalledSite(id: String) {
        viewModelScope.launch { repository.deleteInstalledSiteById(id) }
    }

    // ---- Site permissions ----
    fun requestWebPermission(
        origin: String,
        types: List<SitePermissionType>,
        grant: (List<SitePermissionType>) -> Unit,
        deny: () -> Unit
    ) {
        viewModelScope.launch {
            val saved = repository.sitePermissionByOrigin(origin)
            val (toAsk, preGranted) = if (saved != null) {
                val determined = types.mapNotNull { t ->
                    when (t) {
                        SitePermissionType.CAMERA -> saved.cameraAllowed?.let { t to it }
                        SitePermissionType.MICROPHONE -> saved.microphoneAllowed?.let { t to it }
                        SitePermissionType.LOCATION -> saved.locationAllowed?.let { t to it }
                        SitePermissionType.NOTIFICATIONS -> saved.notificationsAllowed?.let { t to it }
                    }
                }
                val grantedFromSaved = determined.filter { it.second }.map { it.first }
                val remaining = types.filter { type -> determined.none { it.first == type } }
                remaining to grantedFromSaved
            } else {
                types to emptyList()
            }

            if (toAsk.isEmpty()) {
                if (preGranted.isNotEmpty()) {
                    withContext(Dispatchers.Main) { grant(preGranted) }
                } else {
                    withContext(Dispatchers.Main) { deny() }
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                pendingPermissionPrompt = PermissionPrompt(
                    origin = origin,
                    types = toAsk,
                    onGrant = { grantedNow ->
                        persistPermission(origin, grantedNow, toAsk)
                        grant(preGranted + grantedNow)
                    },
                    onDeny = {
                        persistPermission(origin, emptyList(), toAsk)
                        if (preGranted.isNotEmpty()) grant(preGranted) else deny()
                    }
                )
            }
        }
    }

    private fun persistPermission(origin: String, granted: List<SitePermissionType>, requested: List<SitePermissionType>) {
        viewModelScope.launch {
            val existing = repository.sitePermissionByOrigin(origin) ?: SitePermission(origin = origin)
            var updated = existing
            requested.forEach { t ->
                val isGranted = t in granted
                updated = when (t) {
                    SitePermissionType.CAMERA -> updated.copy(cameraAllowed = isGranted)
                    SitePermissionType.MICROPHONE -> updated.copy(microphoneAllowed = isGranted)
                    SitePermissionType.LOCATION -> updated.copy(locationAllowed = isGranted)
                    SitePermissionType.NOTIFICATIONS -> updated.copy(notificationsAllowed = isGranted)
                }
            }
            repository.upsertSitePermission(updated.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    fun clearPermissionPrompt() { pendingPermissionPrompt = null }

    fun requestGeolocation(origin: String, onAllow: () -> Unit, onDeny: () -> Unit) {
        viewModelScope.launch {
            val saved = repository.sitePermissionByOrigin(origin)
            when (saved?.locationAllowed) {
                true -> { withContext(Dispatchers.Main) { onAllow() }; return@launch }
                false -> { withContext(Dispatchers.Main) { onDeny() }; return@launch }
                null -> {}
            }
            withContext(Dispatchers.Main) {
                pendingGeolocationPrompt = Triple(origin, onAllow, onDeny)
            }
        }
    }

    fun grantGeolocation(origin: String) {
        pendingGeolocationPrompt?.let { (orig, allow, _) ->
            persistPermission(orig, listOf(SitePermissionType.LOCATION), listOf(SitePermissionType.LOCATION))
            allow()
        }
        pendingGeolocationPrompt = null
    }

    fun denyGeolocation() {
        pendingGeolocationPrompt?.let { (orig, _, deny) ->
            persistPermission(orig, emptyList(), listOf(SitePermissionType.LOCATION))
            deny()
        }
        pendingGeolocationPrompt = null
    }

    fun requestFileChooser(
        callback: android.webkit.ValueCallback<Array<Uri>>,
        params: android.webkit.WebChromeClient.FileChooserParams
    ) {
        pendingFileChooser = callback to params
    }

    fun clearFileChooser() {
        pendingFileChooser?.first?.onReceiveValue(null)
        pendingFileChooser = null
    }

    fun deliverFileChooserResult(uris: Array<Uri>?) {
        pendingFileChooser?.first?.onReceiveValue(uris)
        pendingFileChooser = null
    }

    fun updateStorageFootprint(
        origin: String,
        cookieCount: Int,
        hasLocalStorage: Boolean,
        hasIndexedDb: Boolean,
        hasServiceWorker: Boolean,
        estBytes: Long
    ) {
        viewModelScope.launch {
            val existing = repository.storageInfoByOrigin(origin)
            val info = if (existing != null) {
                existing.copy(
                    cookieCount = cookieCount,
                    hasLocalStorage = hasLocalStorage || existing.hasLocalStorage,
                    hasIndexedDb = hasIndexedDb || existing.hasIndexedDb,
                    hasServiceWorker = hasServiceWorker || existing.hasServiceWorker,
                    estimatedBytes = if (estBytes > 0) estBytes else existing.estimatedBytes,
                    lastSeen = System.currentTimeMillis()
                )
            } else {
                StorageInfo(
                    origin = origin,
                    host = BrowserUtils.hostFromUrl(origin),
                    cookieCount = cookieCount,
                    hasLocalStorage = hasLocalStorage,
                    hasIndexedDb = hasIndexedDb,
                    hasServiceWorker = hasServiceWorker,
                    estimatedBytes = estBytes,
                    lastSeen = System.currentTimeMillis()
                )
            }
            repository.upsertStorageInfo(info)
        }
    }

    fun clearSiteData(origin: String) {
        viewModelScope.launch {
            repository.deleteStorageInfoOrigin(origin)
            repository.deleteSitePermissionOrigin(origin)
        }
    }

    fun clearAllSiteData() {
        viewModelScope.launch {
            repository.clearAllStorageInfos()
            repository.clearAllSitePermissions()
        }
    }

    fun revokePermission(origin: String, type: SitePermissionType) {
        viewModelScope.launch {
            val existing = repository.sitePermissionByOrigin(origin) ?: return@launch
            val updated = when (type) {
                SitePermissionType.CAMERA -> existing.copy(cameraAllowed = null)
                SitePermissionType.MICROPHONE -> existing.copy(microphoneAllowed = null)
                SitePermissionType.LOCATION -> existing.copy(locationAllowed = null)
                SitePermissionType.NOTIFICATIONS -> existing.copy(notificationsAllowed = null)
            }
            if (updated.cameraAllowed == null && updated.microphoneAllowed == null && updated.locationAllowed == null && updated.notificationsAllowed == null) {
                repository.deleteSitePermission(updated)
            } else {
                repository.upsertSitePermission(updated.copy(updatedAt = System.currentTimeMillis()))
            }
        }
    }

    fun addDownload(url: String, fileName: String, mime: String?, length: Long) {
        viewModelScope.launch {
            repository.upsertDownload(DownloadEntry(url = url, fileName = fileName, mimeType = mime, contentLength = length))
        }
    }

    fun clearAllDownloads() {
        viewModelScope.launch { repository.clearAllDownloads() }
    }

    fun onClearedPersist() { persistTabsSync() }

    override fun onCleared() {
        onClearedPersist()
        super.onCleared()
    }

    private fun persistTabs() {
        viewModelScope.launch(Dispatchers.IO) { persistTabsSync() }
    }

    private fun persistTabsSync() {
        // Incognito windows leave no trace on disk.
        if (incognito) return
        try {
            val sp = context.getSharedPreferences("web_prefs", Context.MODE_PRIVATE)
            val toSave = tabs.filter { !it.isPrivate }
            sp.edit()
                .putString(savedTabsKey, json.encodeToString(toSave))
                .putString(activeTabKey, activeTabId)
                .apply()
        } catch (e: Exception) { Log.e(TAG, "persistTabs failed", e) }
    }

    private fun persistPrefs() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sp = context.getSharedPreferences("web_prefs", Context.MODE_PRIVATE)
                sp.edit()
                    .putString(P_CACHE_MODE, cacheMode.name)
                    .putString(P_SEARCH_ENGINE, searchEngine.name)
                    .putBoolean(P_JS_ENABLED, jsEnabled)
                    .putBoolean(P_BLOCK_THIRD_PARTY, blockThirdPartyCookies)
                    .putBoolean(P_DESKTOP_MODE, desktopMode)
                    .putBoolean(P_SEARCH_BAR_BOTTOM, searchBarAtBottom)
                    .putString(P_SHIELD_LEVEL, shields.level?.name)
                    .putBoolean(P_SHIELD_TRACKERS, shields.blockTrackers != false)
                    .putBoolean(P_SHIELD_COSMETIC, shields.cosmeticFiltering != false)
                    .putBoolean(P_SHIELD_FINGERPRINT, shields.fingerprintProtection != false)
                    .putBoolean(P_SHIELD_HTTPS, shields.httpsUpgrade != false)
                    .apply()
            } catch (e: Exception) { Log.e(TAG, "persistPrefs failed", e) }
        }
    }

    fun externalIntentUrl(url: String) {
        // Per product requirement: external links from other apps always open a new tab.
        newTab(url = url, makeActive = true)
    }
}

class WebViewModelFactory(
    private val repository: WebRepository,
    private val context: Context,
    private val windowId: String = WebViewModel.DEFAULT_WINDOW_ID,
    private val incognito: Boolean = false,
    private val initialShieldSettings: List<ShieldSetting> = emptyList(),
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WebViewModel::class.java)) {
            return WebViewModel(repository, context, windowId, incognito, initialShieldSettings) as T
        }
        throw IllegalArgumentException("Unknown ViewModel $modelClass")
    }
}
