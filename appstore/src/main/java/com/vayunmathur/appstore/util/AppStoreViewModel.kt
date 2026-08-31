package com.vayunmathur.appstore.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.CatalogRepository
import com.vayunmathur.appstore.data.InstalledAppsRepository
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.PlayStoreLinks
import com.vayunmathur.appstore.data.SandboxedGooglePlay
import com.vayunmathur.appstore.data.SettingsRepository
import com.vayunmathur.appstore.data.SyncStep
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.installer.InstallCoordinator
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.play.PlayAuthState
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.appstore.data.priority
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One ViewModel, four screens, and no data-layer logic of its own.
 *
 * The previous version was 900 lines that owned the Play session, the PackageManager
 * sweep, the F-Droid sync and the download/verify/install pipeline all at once. Those now
 * live in [CatalogRepository], [PlayRepository], [InstalledAppsRepository] and
 * [InstallCoordinator]; what is left here is the job this class is actually for — turning
 * their flows into per-screen state and turning taps into calls.
 */
class AppStoreViewModel(
    private val context: Application,
    db: AppDatabase,
) : ViewModel(), HomeActions, SearchActions, AppDetailActions, UpdatesActions, LibraryActions {

    private val catalog = CatalogRepository(context, db, viewModelScope)
    private val play = PlayRepository(context)
    private val accrescent = AccrescentRepository(context, db)
    private val installedRepo = InstalledAppsRepository(context)
    private val settings = SettingsRepository(context, viewModelScope)
    private val installer = InstallCoordinator(context, db, play, accrescent) { ownSigningCertificates }

    /** Off-by-default: the periodic check may also download and install updates unattended. */
    val autoInstallUpdates: StateFlow<Boolean> = settings.autoInstallUpdates

    /** SHA-256 of this app's own signing certificate — the Modern Apps trust root. */
    val ownSigningCertificates: Set<String> by lazy { ApkCertificates.selfSigners(context) }

    val repos = catalog.repos

    // --- Raw state ------------------------------------------------------------------

    private val _statusMessage = MutableStateFlow("")

    /** Kept apart from [_statusMessage] so a transient sync line can't erase it. */
    private val _playError = MutableStateFlow("")
    private val _isSyncing = MutableStateFlow(false)
    private val _isLoadingHome = MutableStateFlow(false)
    private val _isCheckingUpdates = MutableStateFlow(false)
    private val _lastUpdateCheck = MutableStateFlow(0L)

    private val _playSections = MutableStateFlow<List<AppSection>>(emptyList())
    private val _recentlyUpdated = MutableStateFlow<List<UnifiedApp>>(emptyList())

    /** Accrescent listings for the home carousel, from the gRPC listing API. */
    private val _accrescentApps = MutableStateFlow<List<UnifiedApp>>(emptyList())

    /**
     * App ids Accrescent's signed allowlist vouches for. Drives library attribution for an
     * installed Accrescent app. Empty until the first repodata refresh populates it.
     */
    private val _accrescentPackages = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The Sandboxed Google Play bundle rows. Seeded with stand-ins so the section is on
     * screen immediately; [loadHome] swaps in richer catalogue rows when a sync has cached
     * them. These install from GrapheneOS's release server, never Play.
     * Kept in [SandboxedGooglePlay.PACKAGES] order so the ordered install reads straight off it.
     */
    private val _sandboxedGooglePlay =
        MutableStateFlow(SandboxedGooglePlay.placeholders())
    private val _categories = MutableStateFlow<List<String>>(emptyList())
    private val _selectedCategory = MutableStateFlow<String?>(null)
    private val _categoryApps = MutableStateFlow<List<UnifiedApp>>(emptyList())

    private val _query = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<List<UnifiedApp>>(emptyList())
    private val _searchFilter = MutableStateFlow(SourceFilter.ALL)
    private val _isSearching = MutableStateFlow(false)
    private val _hasSearched = MutableStateFlow(false)

    private val _selectedApp = MutableStateFlow<UnifiedApp?>(null)
    private val _isLoadingDetails = MutableStateFlow(false)

    private val _catalogUpdates = MutableStateFlow<List<UnifiedApp>>(emptyList())
    private val _playUpdates = MutableStateFlow<List<UnifiedApp>>(emptyList())
    private val _accrescentUpdates = MutableStateFlow<List<UnifiedApp>>(emptyList())

    private val _libraryFilter = MutableStateFlow(SourceFilter.ALL)

    /**
     * Installed packages Play confirmed it actually hosts.
     *
     * Drives library attribution so a sideloaded app isn't labelled Play just for being
     * unrecognised. Empty until the first resolution; a package no offline source lists
     * stays out of the library until Play vouches for it. A failed lookup leaves the
     * previous answer in place rather than emptying it — see [refreshPlayInstalledPackages].
     */
    private val _playInstalledPackages = MutableStateFlow<Set<String>>(emptySet())

    private var searchJob: Job? = null
    private var detailJob: Job? = null

    // --- Derived state ----------------------------------------------------------------

    /** Everything every screen needs to draw a row: installed, its icon, its progress. */
    private val chrome: StateFlow<RowChrome> = combine(
        installedRepo.apps,
        installedRepo.icons,
        installer.stages,
    ) { installed, icons, stages ->
        RowChrome(installed, installed.map { it.packageName }.toSet(), icons, stages)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RowChrome())

    val updates: StateFlow<List<UnifiedApp>> = combine(
        _catalogUpdates,
        _playUpdates,
        _accrescentUpdates,
        installedRepo.apps,
    ) { catalogUpdates, playUpdates, accrescentUpdates, installed ->
        val installedVersions = installed.associate { it.packageName to it.versionCode }
        (catalogUpdates + playUpdates + accrescentUpdates)
            // The surviving row's source decides which download-and-verify path the update
            // takes, so it has to be the same precedence search and the library use.
            .sortedBy { it.source.priority }
            .distinctBy { it.packageName }
            // Re-check against what is on the device rather than trusting the lists.
            // _playUpdates is a snapshot from the last network check, so without this a
            // Play app stays in the list after it has been updated, until the next check.
            .filter { app ->
                val installedVersion = installedVersions[app.packageName] ?: return@filter false
                app.versionCode > installedVersion
            }
            .sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val sections: StateFlow<List<AppSection>> = combine(
        catalog.modernApps,
        _playSections,
        _recentlyUpdated,
        _sandboxedGooglePlay,
        combine(
            _categoryApps,
            _selectedCategory,
            _accrescentApps,
        ) { apps, category, accrescent -> Triple(apps, category, accrescent) },
    ) { modern, playSections, recent, sandboxed, (categoryApps, category, accrescentApps) ->
        buildSections(modern, playSections, recent, sandboxed, accrescentApps, categoryApps, category)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val home: StateFlow<HomeUiState> = combine(
        sections,
        _categories,
        _selectedCategory,
        chrome,
        combine(
            updates,
            _isSyncing,
            _isLoadingHome,
            _statusMessage,
            _playError,
        ) { u, syncing, loading, msg, playError ->
            HomeChrome(u.size, syncing, loading, msg.ifBlank { playError })
        },
    ) { built, categories, category, rows, homeChrome ->
        HomeUiState(
            sections = built,
            categories = categories,
            selectedCategory = category,
            updateCount = homeChrome.updateCount,
            installedPackages = rows.installedPackages,
            installedIcons = rows.icons,
            stages = rows.stages,
            isLoading = homeChrome.isLoading,
            isSyncing = homeChrome.isSyncing,
            statusMessage = homeChrome.message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    val search: StateFlow<SearchUiState> = combine(
        _query,
        combine(_searchResults, _searchFilter) { results, filter ->
            results to filter
        },
        _isSearching,
        _hasSearched,
        chrome,
    ) { query, (results, filter), searching, searched, rows ->
        SearchUiState(
            query = query,
            results = results.filter { filter.source == null || it.source == filter.source },
            filter = filter,
            isSearching = searching,
            hasSearched = searched,
            installedPackages = rows.installedPackages,
            installedIcons = rows.icons,
            stages = rows.stages,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    val detail: StateFlow<AppDetailUiState> = combine(
        _selectedApp,
        _isLoadingDetails,
        chrome,
        installer.verification,
    ) { app, loading, rows, verification ->
        val pkg = app?.packageName
        AppDetailUiState(
            app = app,
            installedInfo = rows.installed.find { it.packageName == pkg },
            verification = verification[pkg],
            stage = rows.stages[pkg],
            installedIcon = rows.icons[pkg],
            isLoadingDetails = loading,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppDetailUiState())

    val updatesUi: StateFlow<UpdatesUiState> = combine(
        updates,
        chrome,
        _isCheckingUpdates,
        _lastUpdateCheck,
        _statusMessage,
    ) { list, rows, checking, checkedAt, message ->
        UpdatesUiState(
            updates = list,
            installedIcons = rows.icons,
            installedInfos = rows.installed.associateBy { it.packageName },
            stages = rows.stages,
            isChecking = checking,
            lastCheckedAt = checkedAt,
            statusMessage = message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UpdatesUiState())

    val library: StateFlow<LibraryUiState> = combine(
        chrome,
        catalog.packageIndex,
        _playInstalledPackages,
        _accrescentPackages,
        _libraryFilter,
    ) { rows, index, playPackages, accrescentPackages, filter ->
        // Source attribution, highest priority first:
        //  1. GrapheneOS's Sandboxed Google Play components (GSF/GMS/Vending) are Google's
        //     APKs re-hosted by GrapheneOS and installed from its release server, so they
        //     are attributed to GrapheneOS — never Play — even though Play lists them too.
        //  2. Whatever the offline catalogue (F-Droid / Modern Apps) recorded.
        //  3. Accrescent, for packages its signed allowlist vouches for.
        //  4. Play, but only for packages Play confirmed it hosts (see _playInstalledPackages).
        // A package no source vouches for — sideloaded, or from a store we don't track — is
        // left out entirely rather than mislabelled as Play.
        fun sourceOf(pkg: String): AppSource? = when {
            pkg in SandboxedGooglePlay.PACKAGES -> AppSource.GRAPHENEOS
            else -> index[pkg]?.source?.let { runCatching { AppSource.valueOf(it) }.getOrNull() }
                ?: AppSource.ACCRESCENT.takeIf { pkg in accrescentPackages }
                ?: AppSource.PLAYSTORE.takeIf { pkg in playPackages }
        }

        val all = rows.installed.mapNotNull { info ->
            sourceOf(info.packageName)?.let { info.toUnifiedApp(it) }
        }
        LibraryUiState(
            apps = all.filter { filter.source == null || it.source == filter.source },
            filter = filter,
            counts = SourceFilter.entries.associateWith { f ->
                if (f.source == null) all.size else all.count { it.source == f.source }
            },
            installedIcons = rows.icons,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    // --- Lifecycle -------------------------------------------------------------------

    init {
        viewModelScope.launch {
            installedRepo.refresh()
            play.restore()
            loadHome()
            refreshPlayInstalledPackages()
        }
        viewModelScope.launch { loadAccrescent() }
        viewModelScope.launch {
            // Recompute catalogue-side updates whenever either half changes. The Play
            // half needs a network call and is driven by checkForUpdates() instead.
            combine(catalog.packageIndex, installedRepo.updatable) { _, installed -> installed }
                .collect { installed -> _catalogUpdates.value = catalog.updatesFor(installed) }
        }
        viewModelScope.launch {
            _categories.value = catalog.categories()
        }
        viewModelScope.launch {
            // Say so when Play is unreachable. Without this the store just quietly shows
            // fewer results, which looks like the search finding nothing.
            play.authState.collect { state ->
                _playError.value = (state as? PlayAuthState.Error)
                    ?.let { context.getString(R.string.play_unavailable, it.message) }
                    .orEmpty()
            }
        }
    }

    fun refreshInstalled() {
        viewModelScope.launch {
            installedRepo.refresh()
            refreshPlayInstalledPackages()
        }
    }

    override fun onCleared() {
        // Release the Accrescent gRPC channel (and its okhttp connection pool).
        accrescent.shutdown()
        super.onCleared()
    }

    /**
     * Ask Play which installed packages it actually hosts, for library attribution.
     *
     * Only packages neither offline source lists and that aren't GrapheneOS components are
     * worth asking about — the rest are already attributed. Play returning nothing (no
     * account, no network) leaves the previous answer in place rather than emptying the
     * library, so a transient failure can't hide apps Play was known to host.
     */
    private suspend fun refreshPlayInstalledPackages() {
        val index = catalog.packageIndex.value
        val candidates = installedRepo.apps.value
            .map { it.packageName }
            .filter { it !in index && it !in SandboxedGooglePlay.PACKAGES }
        if (candidates.isEmpty()) {
            _playInstalledPackages.value = emptySet()
            return
        }
        val available = play.details(candidates).map { it.packageName }.toSet()
        if (available.isNotEmpty()) _playInstalledPackages.value = available
    }

    /**
     * Refresh Accrescent's signed allowlist and its home listings. Both fail soft: a network
     * blip leaves the previous rows and attribution set in place rather than emptying them.
     */
    private suspend fun loadAccrescent() {
        accrescent.refreshRepoData()
        val ids = accrescent.appIds()
        if (ids.isNotEmpty()) _accrescentPackages.value = ids
        val page = accrescent.listApps()
        if (page.apps.isNotEmpty()) _accrescentApps.value = page.apps.take(CAROUSEL_LIMIT)
    }

    /**
     * The available Accrescent update for [packageName] as an installable listing, or null when
     * there is none. The version code is the update's, so the [updates] filter keeps it only
     * while it is genuinely newer than what is installed.
     */
    private suspend fun accrescentUpdate(packageName: String, currentVersionCode: Long): UnifiedApp? {
        val update = runCatching {
            accrescent.updateInfo(packageName, currentVersionCode)
        }.getOrNull() ?: return null
        val details = accrescent.details(packageName) ?: UnifiedApp(
            packageName = packageName,
            source = AppSource.ACCRESCENT,
            name = packageName.substringAfterLast('.'),
        )
        return details.copy(versionCode = update.versionCode, versionName = update.versionName)
    }

    // --- Home ---------------------------------------------------------------------

    /**
     * Fill the home screen.
     *
     * The offline rows come straight from Room and are already on screen by the time this
     * runs; what it adds is Play's editorial clusters and top chart, which need an
     * anonymous account. Those failing is normal — no network, no account — and leaves the
     * offline rows exactly as they were rather than emptying the screen.
     */
    private suspend fun loadHome() {
        _isLoadingHome.value = true
        _recentlyUpdated.value = catalog.recentlyUpdated(RECENT_LIMIT)

        // The Sandboxed Google Play components come from GrapheneOS's release server, not
        // Play. Enrich the stand-ins with richer catalogue rows (icon, size, signer, hash)
        // when a sync has cached them; keep the stand-ins, and the section, when it hasn't.
        val cached = catalog.byPackages(SandboxedGooglePlay.PACKAGES).associateBy { it.packageName }
        if (cached.isNotEmpty()) {
            _sandboxedGooglePlay.value =
                _sandboxedGooglePlay.value.map { cached[it.packageName] ?: it }
        }

        val clusters = play.homeClusters()
        _playSections.value = clusters
            .filter { it.apps.isNotEmpty() }
            .take(PLAY_CLUSTER_LIMIT)
            .map { AppSection("play-${it.title}", it.title, it.apps.take(CAROUSEL_LIMIT)) }

        if (_playSections.value.isEmpty()) {
            // No account, or Play changed its stream shape. A top chart is one request and
            // still gives the screen something beyond this repo's own dozen apps.
            val chart = play.topChart()
            if (chart.isNotEmpty()) {
                _playSections.value = listOf(
                    AppSection(
                        id = "play-top",
                        title = context.getString(R.string.section_play_top_charts),
                        apps = chart.take(CAROUSEL_LIMIT),
                    )
                )
            }
        }
        _isLoadingHome.value = false
    }

    private fun buildSections(
        modern: List<UnifiedApp>,
        playSections: List<AppSection>,
        recent: List<UnifiedApp>,
        sandboxed: List<UnifiedApp>,
        accrescent: List<UnifiedApp>,
        categoryApps: List<UnifiedApp>,
        category: String?,
    ): List<AppSection> = buildList {
        // A chosen category replaces the browsing rows: the user asked a narrow question
        // and a wall of unrelated carousels underneath it is just noise.
        if (category != null) {
            add(
                AppSection(
                    id = "category",
                    title = category,
                    apps = categoryApps,
                    layout = SectionLayout.LIST,
                    subtitle = context.getString(R.string.section_category_subtitle),
                )
            )
            return@buildList
        }
        if (modern.isNotEmpty()) {
            add(
                AppSection(
                    id = "modern",
                    title = context.getString(R.string.section_modern_apps),
                    apps = modern,
                    subtitle = context.getString(R.string.section_modern_apps_subtitle),
                )
            )
        }
        if (sandboxed.isNotEmpty()) {
            add(
                AppSection(
                    id = SandboxedGooglePlay.SECTION_ID,
                    title = context.getString(R.string.section_sandboxed_google_play),
                    apps = sandboxed,
                    subtitle = context.getString(R.string.section_sandboxed_google_play_subtitle),
                )
            )
        }
        addAll(playSections)
        if (accrescent.isNotEmpty()) {
            add(
                AppSection(
                    id = "accrescent",
                    title = context.getString(R.string.section_accrescent),
                    apps = accrescent,
                    subtitle = context.getString(R.string.section_accrescent_subtitle),
                )
            )
        }
        if (recent.isNotEmpty()) {
            add(
                AppSection(
                    id = "recent",
                    title = context.getString(R.string.section_recently_updated),
                    apps = recent,
                    subtitle = context.getString(R.string.section_recently_updated_subtitle),
                )
            )
        }
    }

    override fun selectCategory(category: String?) {
        _selectedCategory.value = category
        viewModelScope.launch {
            _categoryApps.value = if (category == null) emptyList() else catalog.byCategory(category)
        }
    }

    override fun refresh() = syncSources()

    /** Re-download both offline catalogues, then reload the home rows from them. */
    fun syncSources() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            val report = catalog.sync { step ->
                _statusMessage.value = context.getString(
                    when (step) {
                        SyncStep.FDROID -> R.string.sync_step_fdroid
                        SyncStep.MODERN_APPS -> R.string.sync_step_modern_apps
                    }
                )
            }
            _statusMessage.value = ""
            _isSyncing.value = false

            AppMessages.show(
                when {
                    !report.anyFailed -> context.getString(
                        R.string.sync_done,
                        (report.fdroidCount ?: 0) + (report.modernCount ?: 0),
                    )
                    report.fdroidCount == null && report.modernCount == null ->
                        context.getString(R.string.sync_failed_all)
                    report.fdroidCount == null -> context.getString(R.string.sync_failed_fdroid)
                    else -> context.getString(R.string.sync_failed_modern_apps)
                }
            )

            _categories.value = catalog.categories()
            loadHome()
            loadAccrescent()
            installedRepo.refresh()
        }
    }

    // --- Search -----------------------------------------------------------------------

    override fun setSearch(query: String) {
        _query.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _hasSearched.value = false
            _isSearching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _isSearching.value = true

            // Local first and published immediately: the F-Droid catalogue is on disk, so
            // there is no reason to make the user wait on Play before seeing anything.
            val local = catalog.searchLocal(query)
            _searchResults.value = rank(local, query)

            // Accrescent search is client-side over the listings already cached from the home
            // carousel (its API has no search RPC), so it adds no network round-trip here.
            val accrescentResults = accrescent.search(query)
            val remote = play.search(query)
            _searchResults.value = rank(merge(local, remote, accrescentResults), query)
            _isSearching.value = false
            _hasSearched.value = true
        }
    }

    override fun setSearchFilter(filter: SourceFilter) {
        _searchFilter.value = filter
    }

    /**
     * Combine catalogue, Play and Accrescent hits, one row per package.
     *
     * Where several sources offer a package, [AppSource.PRIORITY] decides which row survives —
     * notably keeping the GrapheneOS row for the Sandboxed Google Play components rather than
     * Play's listing of the same three packages. Sorting is stable, so each source's own
     * relevance ordering is preserved within its rank, and [rank] re-sorts the result anyway.
     */
    private fun merge(vararg lists: List<UnifiedApp>): List<UnifiedApp> =
        lists.asSequence()
            .flatten()
            .sortedBy { it.source.priority }
            .distinctBy { it.packageName }
            .toList()

    /** Exact hits first, then name prefixes, then everything else alphabetically. */
    private fun rank(apps: List<UnifiedApp>, query: String): List<UnifiedApp> {
        val q = query.trim().lowercase()
        fun score(app: UnifiedApp): Int {
            val name = app.name.lowercase()
            return when {
                name == q || app.packageName.lowercase() == q -> 0
                name.startsWith(q) -> 1
                name.split(' ').any { it.startsWith(q) } -> 2
                name.contains(q) -> 3
                else -> 4
            }
        }
        return apps.sortedWith(compareBy({ score(it) }, { it.name.lowercase() }))
    }

    // --- Detail -----------------------------------------------------------------------

    fun selectApp(app: UnifiedApp) {
        detailJob?.cancel()
        _selectedApp.value = app
        detailJob = viewModelScope.launch {
            // Play lists the Sandboxed Google Play components too, so a search hit for one can
            // arrive carrying AppSource.PLAYSTORE. Describe it from GrapheneOS regardless: that
            // is the row an install would actually use, and the Play build is the wrong
            // artifact for the device even though Play would happily deliver it. Prefer the
            // cached row — the stand-in carries no version, size, signer or hash, and on a cold
            // start nothing has enriched it yet.
            sandboxedGooglePlayRow(app.packageName)?.let { sandboxed ->
                _selectedApp.value = catalog.byPackage(app.packageName) ?: sandboxed
                return@launch
            }
            // The catalogue row wins whenever there is one, even if the user tapped a Play
            // tile for the same package. It is the row an install would actually use — it
            // carries the signer and hash an authenticated index published — so showing
            // the Play listing here would describe a download this store is not going to
            // make. Only F-Droid and Modern Apps rows are ever cached, so this never
            // replaces one Play listing with another.
            val cached = catalog.byPackage(app.packageName)
            if (cached != null) {
                _selectedApp.value = cached
                return@launch
            }
            // Accrescent listings from the home carousel are shells (no version code, no signer
            // yet); fetch the full listing + package info + trust anchor before the page settles.
            if (app.source == AppSource.ACCRESCENT) {
                _isLoadingDetails.value = true
                val details = accrescent.details(app.packageName)
                if (details != null) _selectedApp.value = details
                _isLoadingDetails.value = false
                return@launch
            }
            // Play listings from a cluster are shells: no description, no
            // screenshots, no version code. Fill them in before the page settles.
            if (app.source == AppSource.PLAYSTORE && app.screenshots.isEmpty()) {
                _isLoadingDetails.value = true
                val details = play.details(app.packageName)
                if (details != null) _selectedApp.value = details
                _isLoadingDetails.value = false
            }
        }
    }

    /** Open a package the store only knows by name, e.g. from a `market://` link. */
    fun selectPackage(packageName: String) {
        viewModelScope.launch {
            sandboxedGooglePlayRow(packageName)?.let {
                selectApp(it)
                return@launch
            }
            val known = catalog.byPackage(packageName)
            if (known != null) {
                selectApp(known)
                return@launch
            }
            _isLoadingDetails.value = true
            _selectedApp.value = UnifiedApp(
                packageName = packageName,
                source = AppSource.PLAYSTORE,
                name = packageName.substringAfterLast('.'),
            )
            val details = play.details(packageName)
            if (details != null) _selectedApp.value = details
            _isLoadingDetails.value = false
        }
    }

    fun clearSelection() {
        detailJob?.cancel()
        _selectedApp.value = null
    }

    /**
     * The GrapheneOS row for a Sandboxed Google Play component, or null for any other package.
     *
     * Carries whatever [loadHome] enriched the stand-in with, so this is the best row the store
     * holds for GSF, GMS or Vending.
     */
    private fun sandboxedGooglePlayRow(packageName: String): UnifiedApp? =
        _sandboxedGooglePlay.value.firstOrNull { it.packageName == packageName }

    // --- Actions ----------------------------------------------------------------------

    override fun install(app: UnifiedApp) {
        viewModelScope.launch {
            val outcome = installer.install(app)
            AppMessages.show(
                when (val v = outcome.verification) {
                    is VerificationResult.Rejected ->
                        context.getString(R.string.install_blocked, app.name, v.reason)
                    is VerificationResult.Unverified ->
                        if (outcome.started) context.getString(R.string.install_started_unverified, app.name)
                        else context.getString(R.string.install_failed, app.name)
                    is VerificationResult.Verified ->
                        if (outcome.started) context.getString(R.string.install_started, app.name)
                        else context.getString(R.string.install_failed, app.name)
                }
            )
            if (outcome.started) {
                // PackageInstaller commits asynchronously; give it a moment before the
                // installed list is re-read, or the row still shows the old version.
                delay(INSTALL_SETTLE_MS)
                installedRepo.refresh()
            }
        }
    }

    override fun dismissInstallFailure(packageName: String) = installer.dismissFailure(packageName)

    /**
     * Install the Sandboxed Google Play bundle in dependency order.
     *
     * Sequential and awaited, like [updateAll]: GSF and GMS provide the accounts and the
     * provider Vending talks to, so they must land first, and each first-time install shows
     * its own PackageInstaller confirmation — firing them at once would bury the user in
     * prompts and let the store client install before the services it needs.
     */
    override fun installSandboxedGooglePlay() {
        viewModelScope.launch {
            for (app in _sandboxedGooglePlay.value) {
                installer.install(app)
            }
            delay(INSTALL_SETTLE_MS)
            installedRepo.refresh()
        }
    }

    override fun openApp(packageName: String) {
        val launchIntent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull()
        if (launchIntent == null) {
            openInPlayStore(packageName)
            return
        }
        startActivity(launchIntent)
    }

    override fun uninstallApp(packageName: String) {
        val started = startActivity(
            Intent(Intent.ACTION_DELETE, "package:$packageName".toUri())
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        )
        if (!started) AppMessages.show(context.getString(R.string.uninstaller_unavailable))
    }

    override fun openInPlayStore(packageName: String) {
        if (startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()))) return
        startActivity(Intent(Intent.ACTION_VIEW, PlayStoreLinks.playStoreUrl(packageName).toUri()))
    }

    override fun openInBrowser(url: String) {
        if (url.isBlank()) return
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    override fun shareApp(app: UnifiedApp) {
        val link = app.website ?: PlayStoreLinks.playStoreUrl(app.packageName)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_app_text, app.name, link))
        }
        startActivity(Intent.createChooser(share, null))
    }

    // --- Updates ----------------------------------------------------------------------

    override fun checkForUpdates() {
        if (_isCheckingUpdates.value) return
        viewModelScope.launch {
            _isCheckingUpdates.value = true
            _statusMessage.value = context.getString(R.string.updates_checking)

            installedRepo.refresh()
            _catalogUpdates.value = catalog.updatesFor(installedRepo.updatable.value)

            // Only ask Play about packages neither offline source lists — for the rest the
            // catalogue already answered, and Play would just re-answer it over the network.
            // The Sandboxed Google Play components are held back too: Play hosts newer builds
            // of all three, but only GrapheneOS's are the ones this device can use, so no
            // update is better than the wrong one until its release metadata is synced.
            val index = catalog.packageIndex.value
            val installed = installedRepo.updatable.value
            val playCandidates = installed
                .filter {
                    it.packageName !in index &&
                        it.packageName !in SandboxedGooglePlay.PACKAGES
                }
                .map { it.packageName }

            val remote = play.details(playCandidates).associateBy { it.packageName }
            _playUpdates.value = installed.mapNotNull { inst ->
                remote[inst.packageName]?.takeIf { it.versionCode > inst.versionCode }
            }
            // The same response tells us which of these packages Play actually hosts, which
            // the library uses to tell a genuine Play app from a sideloaded one.
            if (remote.isNotEmpty()) _playInstalledPackages.value = remote.keys.toSet()

            // Accrescent: refresh the signed allowlist, then ask its API for a newer build of
            // each installed package it vouches for.
            accrescent.refreshRepoData()
            val accrescentIds = accrescent.appIds()
            if (accrescentIds.isNotEmpty()) _accrescentPackages.value = accrescentIds
            _accrescentUpdates.value = installed
                .filter { it.packageName in accrescentIds }
                .mapNotNull { inst -> accrescentUpdate(inst.packageName, inst.versionCode) }

            _lastUpdateCheck.value = System.currentTimeMillis()
            _statusMessage.value = ""
            _isCheckingUpdates.value = false
        }
    }

    override fun updateAll() {
        viewModelScope.launch {
            // Sequential on purpose: updates this store isn't the update owner of still get a
            // system confirmation dialog, and firing them concurrently buries the user in
            // prompts.
            for (app in updates.value) {
                installer.install(app)
            }
            delay(INSTALL_SETTLE_MS)
            installedRepo.refresh()
        }
    }

    /** Turn fully unattended (no-tap) background update installation on or off. */
    fun setAutoInstallUpdates(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoInstallUpdates(enabled) }
    }

    // --- Library ------------------------------------------------------------------------

    override fun setLibraryFilter(filter: SourceFilter) {
        _libraryFilter.value = filter
    }

    // --- Helpers --------------------------------------------------------------------------

    private fun startActivity(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    private fun String.toUri(): Uri = Uri.parse(this)

    /** An installed package as a listing, for the library screen. */
    private fun InstalledInfo.toUnifiedApp(source: AppSource) = UnifiedApp(
        packageName = packageName,
        source = source,
        name = name,
        versionName = versionName,
        versionCode = versionCode,
        lastUpdated = lastUpdateTime,
    )

    private data class RowChrome(
        val installed: List<InstalledInfo> = emptyList(),
        val installedPackages: Set<String> = emptySet(),
        val icons: Map<String, Drawable> = emptyMap(),
        val stages: Map<String, InstallStage> = emptyMap(),
    )

    private data class HomeChrome(
        val updateCount: Int,
        val isSyncing: Boolean,
        val isLoading: Boolean,
        val message: String,
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
        const val INSTALL_SETTLE_MS = 1_500L
        const val RECENT_LIMIT = 30
        const val CAROUSEL_LIMIT = 20
        const val PLAY_CLUSTER_LIMIT = 4
    }
}

class AppStoreViewModelFactory(
    private val context: Context,
    private val db: AppDatabase,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AppStoreViewModel(context.applicationContext as Application, db) as T
}
