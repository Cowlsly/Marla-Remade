package com.vayunmathur.launcher.platform

import android.app.Application
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import androidx.core.net.toUri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.launcher.data.LauncherItemEntity
import com.vayunmathur.launcher.data.LauncherRepository
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.PackageKey
import com.vayunmathur.launcher.domain.toRaw
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the workspace, the app list, the widget host and the preferences, and implements
 * every actions interface in the UI contract so the pages stay previewable.
 *
 * Also the one [IconLoader]: icon rasterisation is cached per process, and the cache has to
 * outlive any single screen.
 */
class LauncherViewModel(app: Application) : AndroidViewModel(app),
    HomeActions, DrawerActions, FolderActions, ItemMenuActions, WidgetPickerActions,
    SettingsActions, IconLoader {

    private val ds = DataStoreUtils.getInstance(app)
    private val repository = LauncherRepository.get(app)
    private val densityDpi = app.resources.displayMetrics.densityDpi

    val appsMonitor = LauncherAppsMonitor(app, viewModelScope)
    private val iconCache = IconCache(iconSizePx = (densityDpi * ICON_CACHE_DP / 160f).toInt())

    /**
     * What this build is allowed to do beyond what any launcher can. False everywhere on an
     * ordinary device, which is what keeps the privileged features invisible there.
     */
    val privilege = LauncherPrivilege(app) { bridge }

    val widgetHost = LauncherWidgetHost(app)
    private val widgets = WidgetBindFlow(app, widgetHost, AppWidgetManager.getInstance(app))

    /**
     * Set by `MainActivity` for the whole time it exists, and cleared when it goes.
     *
     * The bind and configure flows and the HOME-role prompt all need to start something for
     * a result, which only an Activity can do. With no DI framework the alternatives were
     * threading a callback through every action signature or keeping this one nullable
     * reference; the reference is clearer, and nulling it in `onDestroy` is what keeps it
     * from outliving the Activity.
     */
    var bridge: ActivityBridge? = null

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home

    private val _drawer = MutableStateFlow(DrawerUiState(loading = true))
    val drawer: StateFlow<DrawerUiState> = _drawer

    private val _itemMenu = MutableStateFlow(ItemMenuUiState())
    val itemMenu: StateFlow<ItemMenuUiState> = _itemMenu

    private val _widgetPicker = MutableStateFlow(WidgetPickerUiState())
    val widgetPicker: StateFlow<WidgetPickerUiState> = _widgetPicker

    private val _settings = MutableStateFlow(SettingsUiState())
    val settings: StateFlow<SettingsUiState> = _settings

    /** Kept whole so the drawer can filter without re-reading the monitor. */
    private var allApps: List<DrawerApp> = emptyList()

    /** Cache of the last committed layout, so a drop can look up its neighbours. */
    private var savedItems: List<LauncherItemEntity> = emptyList()

    /** Launch counts by flattened component, which is what the predictions row is ordered by. */
    private val launchCounts = mutableMapOf<String, Long>()

    /**
     * Whether each package is a system app, cached for the life of the process.
     *
     * `getApplicationInfo` is a binder call, and the answer is asked for once per row on every
     * workspace rebuild - which is every write. Uncached that is a round trip per icon per drag
     * commit, on the main thread, for something that cannot change without the package being
     * replaced (which takes the row with it through reconciliation anyway).
     */
    private val systemApps = mutableMapOf<String, Boolean>()

    init {
        loadPreferences()
        loadLaunchCounts()
        observeWorkspace()
    }

    // ------------------------------------------------------------------
    // Lifecycle, driven by MainActivity
    // ------------------------------------------------------------------

    fun onStart() {
        widgetHost.startListeningSafely()
        appsMonitor.start(::onAppsChanged)
    }

    fun onStop() {
        appsMonitor.stop()
        widgetHost.stopListeningSafely()
    }

    /**
     * Cold-start housekeeping: drop rows whose app is gone, hide rows whose app is merely away,
     * and hand back widget ids nothing references any more.
     *
     * Refuses to run while the app list is empty. An empty list is indistinguishable from "every
     * app has been uninstalled", and acting on it would delete the entire home screen - which is
     * exactly what happens if `getActivityList` fails transiently, or if this is called before
     * the first [LauncherAppsMonitor.refresh] has returned.
     */
    fun reconcileNow() {
        val installed = appsMonitor.installedKeys()
        if (installed.isEmpty()) return
        viewModelScope.launch {
            val orphaned = repository.reconcile(
                installed = installed,
                unavailable = appsMonitor.unavailable.value,
                boundWidgetIds = widgetHost.boundIds(),
            )
            orphaned.forEach(widgets::release)
            widgets.releaseOrphans(repository.usedWidgetIds())
            refreshDefaultHome()
        }
    }

    private fun onAppsChanged(stale: PackageKey?) {
        // A package update changes its icon, so the cached bitmap for it is wrong now.
        stale?.let { iconCache.evictPackage(it.packageName, it.profileSerial) }
        allApps = appsMonitor.apps.value.map {
            DrawerApp(key = it.key, label = it.label, isWorkProfile = it.isWorkProfile)
        }
        applyDrawerQuery(_drawer.value.query)
        refreshWorkProfile()
        seedIfEmpty()
        reconcileNow()
    }

    /**
     * Whether the work profile is paused, for the drawer's Work tab.
     *
     * Left null unless this build can actually change it, so the tab has no switch on it rather than
     * one that does nothing.
     */
    private fun refreshWorkProfile() {
        val workApp = appsMonitor.apps.value.firstOrNull { it.isWorkProfile }
        val paused = if (workApp == null || !privilege.canToggleQuietMode()) {
            null
        } else {
            privilege.isQuietModeEnabled(workApp.user)
        }
        _drawer.value = _drawer.value.copy(workPaused = paused)
        _home.value = _home.value.copy(canExpandShade = privilege.canExpandNotificationShade())
    }

    // ------------------------------------------------------------------
    // Preferences
    // ------------------------------------------------------------------

    private fun loadPreferences() {
        val columns = ds.getLong(KEY_COLUMNS)?.toInt() ?: DefaultGrid.columns
        val rows = ds.getLong(KEY_ROWS)?.toInt() ?: DefaultGrid.rows
        val hotseat = ds.getLong(KEY_HOTSEAT)?.toInt() ?: DefaultGrid.hotseatSlots
        val showLabels = ds.getBoolean(KEY_SHOW_LABELS, true)
        val iconScale = (ds.getDouble(KEY_ICON_SCALE) ?: 1.0).toFloat()

        _settings.value = SettingsUiState(
            columns = columns,
            rows = rows,
            hotseatSlots = hotseat,
            showLabels = showLabels,
            iconScale = iconScale,
        )
        _home.value = _home.value.copy(
            grid = GridSpec(columns, rows, hotseat),
            showLabels = showLabels,
            iconScale = iconScale,
        )
        _drawer.value = _drawer.value.copy(showLabels = showLabels, iconScale = iconScale)
    }

    private fun observeWorkspace() {
        viewModelScope.launch {
            repository.items.collect { items ->
                savedItems = items
                // Mapped off the main thread: every row's label comes from the app list and every
                // app row asks the package manager whether it can be uninstalled, and this runs on
                // every single write - including each step of a drag commit.
                val next = withContext(Dispatchers.Default) { workspaceFrom(items) }
                _home.value = _home.value.copy(
                    loading = false,
                    pages = next.first,
                    hotseat = next.second,
                )
            }
        }
    }

    /** The desktop by page and the hotseat in rank order, built from the saved rows. */
    private fun workspaceFrom(
        items: List<LauncherItemEntity>,
    ): Pair<Map<Int, List<WorkspaceItem>>, List<WorkspaceItem>> {
        val byContainer = items.groupBy { it.container }
        val folderChildren = items
            .mapNotNull { item -> (item.container as? ContainerRef.Folder)?.let { it.id to item } }
            .groupBy({ it.first }, { it.second })
        // Memoised for the length of one rebuild: several rows commonly share a package, and each
        // answer is a PackageManager call.
        val uninstallable = mutableMapOf<PackageKey, Boolean>()

        fun toUi(entity: LauncherItemEntity): WorkspaceItem = WorkspaceItem(
            id = entity.id,
            type = entity.itemType,
            label = entity.title ?: labelFor(entity),
            screen = entity.screen,
            container = entity.container,
            rect = entity.rect,
            rank = entity.rank,
            key = entity.className?.let {
                ComponentKey(ComponentName(entity.packageName.orEmpty(), it), entity.profileSerial)
            },
            shortcutId = entity.shortcutId,
            appWidgetId = entity.appWidgetId,
            appWidgetProvider = entity.appWidgetProvider,
            hidden = entity.hidden,
            canUninstall = uninstallable.getOrPut(
                PackageKey(entity.packageName.orEmpty(), entity.profileSerial),
            ) { canUninstall(entity) },
            children = folderChildren[entity.id]
                ?.sortedBy { it.rank }
                ?.map { child ->
                    // One level only: folders cannot nest, so a child never has children.
                    WorkspaceItem(
                        id = child.id,
                        type = child.itemType,
                        label = child.title ?: labelFor(child),
                        container = child.container,
                        rank = child.rank,
                        key = child.className?.let {
                            ComponentKey(ComponentName(child.packageName.orEmpty(), it), child.profileSerial)
                        },
                        shortcutId = child.shortcutId,
                        hidden = child.hidden,
                    )
                }
                .orEmpty(),
        )

        val desktop = byContainer[ContainerRef.Desktop].orEmpty().map(::toUi)
        return desktop.groupBy { it.screen } to
            byContainer[ContainerRef.Hotseat].orEmpty().sortedBy { it.rank }.map(::toUi)
    }

    /**
     * Whether this row's app could be uninstalled from here.
     *
     * The same test the item popup applies, and the reason it is not just "is it an app": a system
     * app cannot be removed at all, and a copy in another profile cannot be removed from this one.
     */
    private fun canUninstall(entity: LauncherItemEntity): Boolean {
        if (entity.itemType != LauncherItemType.APPLICATION) return false
        val className = entity.className ?: return false
        val component = ComponentName(entity.packageName.orEmpty(), className)
        val entry = appsMonitor.entryFor(component, entity.profileSerial) ?: return false
        return !entry.isWorkProfile && !isSystemApp(entry)
    }

    private fun labelFor(entity: LauncherItemEntity): String {
        val className = entity.className ?: return ""
        val component = ComponentName(entity.packageName.orEmpty(), className)
        return appsMonitor.entryFor(component, entity.profileSerial)?.label.orEmpty()
    }

    // ------------------------------------------------------------------
    // First run
    // ------------------------------------------------------------------

    /**
     * Seeds a first run: the hotseat from the apps that answer the obvious intents, and one
     * row on the first page from what is left.
     *
     * Guarded on the table being empty rather than on a "seeded" flag, so a user who
     * deliberately clears their home screen does not have it refilled on the next launch.
     */
    private fun seedIfEmpty() {
        viewModelScope.launch {
            if (!repository.isEmpty()) return@launch
            val apps = appsMonitor.apps.value
            if (apps.isEmpty()) return@launch

            val spec = _home.value.grid
            val hotseatApps = SEED_INTENTS
                .mapNotNull { resolveApp(it) }
                .distinctBy { it.componentName }
                .take(spec.hotseatSlots)
            val hotseatComponents = hotseatApps.map { it.componentName }.toSet()
            val firstRow = apps
                .filter { it.componentName !in hotseatComponents && !it.isWorkProfile }
                .take(spec.columns)

            repository.seed(
                spec = spec,
                apps = firstRow.map(::entityForApp),
                hotseat = hotseatApps.map(::entityForApp),
            )
        }
    }

    private fun entityForApp(entry: AppEntry) = LauncherItemEntity(
        itemType = LauncherItemType.APPLICATION,
        containerId = ContainerRef.Desktop.toRaw(),
        packageName = entry.componentName.packageName,
        className = entry.componentName.className,
        profileSerial = entry.profileSerial,
    )

    private fun resolveApp(intent: Intent): AppEntry? {
        val pm = getApplication<Application>().packageManager
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val packageName = resolved.activityInfo?.packageName ?: return null
        return appsMonitor.apps.value.firstOrNull {
            it.componentName.packageName == packageName && !it.isWorkProfile
        }
    }

    // ------------------------------------------------------------------
    // IconLoader
    // ------------------------------------------------------------------

    override fun appIcon(key: ComponentKey): ImageBitmap? =
        iconCache.get(key) { appsMonitor.icon(key.componentName, key.profileSerial, densityDpi) }

    override fun shortcutIcon(packageName: String, shortcutId: String, profileSerial: Long): ImageBitmap? {
        val user = appsMonitor.userFor(profileSerial)
        val shortcut = appsMonitor.shortcuts(packageName, user).firstOrNull { it.id == shortcutId }
            ?: return null
        // Keyed on the shortcut id rather than an activity, so two shortcuts from the same
        // app do not share one cache entry.
        return iconCache.get(ComponentKey(ComponentName(packageName, shortcutId), profileSerial)) {
            appsMonitor.shortcutIcon(shortcut, densityDpi)
        }
    }

    override fun widgetPreview(provider: String, profileSerial: Long): ImageBitmap? {
        val component = widgets.unflatten(provider) ?: return null
        val info = widgets.providers(appsMonitor.userFor(profileSerial))
            .firstOrNull { it.provider == component } ?: return null
        return iconCache.get(ComponentKey(component, profileSerial)) {
            // Providers that ship only a previewLayout have no preview image, and there is
            // nothing to render one into here - those fall back to a generic icon.
            runCatching { info.loadPreviewImage(getApplication(), densityDpi) }.getOrNull()
                ?: runCatching { info.loadIcon(getApplication(), densityDpi) }.getOrNull()
        }
    }

    // ------------------------------------------------------------------
    // HomeActions
    // ------------------------------------------------------------------

    override fun launch(item: WorkspaceItem, left: Int, top: Int, right: Int, bottom: Int) {
        if (item.hidden) {
            AppMessages.show("${item.label} is not available right now")
            return
        }
        val bounds = Rect(left, top, right, bottom)
        val options = bridge?.launchAnimationOptions(left, top, right, bottom)
        val shortcutId = item.shortcutId
        val key = item.key ?: return
        if (shortcutId != null) {
            val user = appsMonitor.userFor(key.profileSerial)
            val shortcut = appsMonitor.shortcuts(key.componentName.packageName, user)
                .firstOrNull { it.id == shortcutId }
            if (shortcut != null && appsMonitor.startShortcut(shortcut, bounds, options)) return
        }
        val entry = appsMonitor.entryFor(key.componentName, key.profileSerial)
        if (entry == null || !appsMonitor.launch(entry, bounds, options)) {
            AppMessages.show("Could not open ${item.label}")
            return
        }
        countLaunch(key)
    }

    override fun commitMove(
        id: Long,
        container: ContainerRef,
        screen: Int,
        rect: CellRect,
        rank: Int,
        displaced: Map<Long, CellRect>,
    ) {
        viewModelScope.launch {
            val from = savedItems.firstOrNull { it.id == id }?.container
            if (container is ContainerRef.Hotseat) {
                repository.moveToHotseat(id, rank, _home.value.grid)
            } else {
                repository.moveTo(id, container, screen, rect, rank, displaced)
            }
            // Dragging the second-to-last child out of a folder has to take the folder with it,
            // and the move above is what emptied it - so the collapse is checked afterwards
            // rather than being the mover's business.
            if (from is ContainerRef.Folder && from != container) {
                repository.collapseFolderIfNeeded(from.id)
            }
        }
    }

    override fun mergeIntoFolder(targetId: Long, draggedId: Long) {
        viewModelScope.launch {
            val target = savedItems.firstOrNull { it.id == targetId } ?: return@launch
            if (target.itemType == LauncherItemType.FOLDER) {
                repository.addToFolder(targetId, draggedId)
            } else {
                repository.createFolder(targetId, draggedId)
            }
        }
    }

    override fun addPendingToHome(
        key: ComponentKey,
        screen: Int,
        rect: CellRect,
        displaced: Map<Long, CellRect>,
    ) {
        viewModelScope.launch {
            val id = repository.upsert(entityForKey(key))
            repository.moveTo(id, ContainerRef.Desktop, screen, rect, displaced = displaced)
        }
    }

    override fun addPendingToHotseat(key: ComponentKey, slot: Int) {
        viewModelScope.launch {
            repository.moveToHotseat(
                repository.upsert(entityForKey(key)),
                slot,
                _home.value.grid,
            )
        }
    }

    private fun entityForKey(key: ComponentKey) = LauncherItemEntity(
        itemType = LauncherItemType.APPLICATION,
        containerId = ContainerRef.Desktop.toRaw(),
        packageName = key.componentName.packageName,
        className = key.componentName.className,
        profileSerial = key.profileSerial,
    )

    override fun setWallpaperBlurred(blurred: Boolean) {
        bridge?.setWallpaperBlurRadius(if (blurred) WALLPAPER_BLUR_PX else 0)
    }

    override fun expandNotificationShade() {
        if (!privilege.expandNotificationShade()) {
            AppMessages.show("Could not open the notification shade")
        }
    }

    override fun remove(id: Long) {
        viewModelScope.launch {
            repository.remove(id)?.let(widgets::release)
        }
    }

    override fun resizeItem(id: Long, rect: CellRect, displaced: Map<Long, CellRect>) {
        viewModelScope.launch { repository.resizeTo(id, rect, displaced) }
    }

    override fun uninstallItem(id: Long) {
        val entity = savedItems.firstOrNull { it.id == id } ?: return
        val packageName = entity.packageName ?: return
        bridge?.requestUninstall(packageName)
    }

    override fun openItemInfo(id: Long) {
        val entity = savedItems.firstOrNull { it.id == id } ?: return
        val className = entity.className ?: return
        val component = ComponentName(entity.packageName.orEmpty(), className)
        val entry = appsMonitor.entryFor(component, entity.profileSerial) ?: return
        appsMonitor.startAppDetails(entry, null)
    }

    // ------------------------------------------------------------------
    // DrawerActions
    // ------------------------------------------------------------------

    override fun setQuery(query: String) = applyDrawerQuery(query)

    private fun applyDrawerQuery(query: String) {
        val trimmed = query.trim()
        val matches = if (trimmed.isEmpty()) {
            allApps
        } else {
            // Prefix match first, then anywhere: typing "ca" should offer Calendar before
            // Vacation Planner.
            val (prefix, contains) = allApps
                .filter { it.label.contains(trimmed, ignoreCase = true) }
                .partition { it.label.startsWith(trimmed, ignoreCase = true) }
            prefix + contains
        }
        _drawer.value = _drawer.value.copy(
            query = query,
            apps = matches,
            predictions = predictedApps(),
            loading = false,
        )
    }

    /**
     * The most-launched apps, as the predictions row.
     *
     * A launch count kept in the DataStore rather than the platform's `AppPredictionManager`, which
     * needs a system signature. Crude next to the real predictor, but it needs no permission, it is
     * right about the top few apps within a day of use, and it is the same shape of answer - so the
     * privileged predictor can replace the source without the row changing.
     */
    private fun predictedApps(): List<DrawerApp> {
        if (launchCounts.isEmpty()) return emptyList()
        val byKey = allApps.associateBy { it.key.componentName.flattenToShortString() }
        return launchCounts.entries
            .sortedByDescending { it.value }
            .mapNotNull { byKey[it.key] }
            .filterNot { it.isWorkProfile }
            .take(PREDICTION_COUNT)
    }

    /**
     * Counts a launch, and remembers it.
     *
     * Flattened into one preference string rather than a row per app: it is a handful of counters
     * read whole and written whole, and a Room table for it would be a migration for nothing.
     */
    private fun countLaunch(key: ComponentKey) {
        val flattened = key.componentName.flattenToShortString()
        launchCounts[flattened] = (launchCounts[flattened] ?: 0) + 1
        _drawer.value = _drawer.value.copy(predictions = predictedApps())
        viewModelScope.launch {
            ds.setString(
                KEY_LAUNCH_COUNTS,
                launchCounts.entries.joinToString("\n") { "${it.key}\t${it.value}" },
            )
        }
    }

    private fun loadLaunchCounts() {
        val stored = ds.getString(KEY_LAUNCH_COUNTS).orEmpty()
        stored.lineSequence().forEach { line ->
            val (flattened, count) = line.split('\t').takeIf { it.size == 2 } ?: return@forEach
            count.toLongOrNull()?.let { launchCounts[flattened] = it }
        }
    }

    override fun launchApp(key: ComponentKey, left: Int, top: Int, right: Int, bottom: Int) {
        val entry = appsMonitor.entryFor(key.componentName, key.profileSerial) ?: return
        val options = bridge?.launchAnimationOptions(left, top, right, bottom)
        if (!appsMonitor.launch(entry, Rect(left, top, right, bottom), options)) {
            AppMessages.show("Could not open ${entry.label}")
            return
        }
        countLaunch(key)
    }

    override fun setWorkPaused(paused: Boolean) {
        val workApp = appsMonitor.apps.value.firstOrNull { it.isWorkProfile } ?: return
        if (!privilege.setQuietMode(workApp.user, paused)) {
            AppMessages.show("Could not change the work profile")
            return
        }
        _drawer.value = _drawer.value.copy(workPaused = paused)
    }

    // ------------------------------------------------------------------
    // FolderActions
    // ------------------------------------------------------------------

    override fun rename(id: Long, title: String) {
        viewModelScope.launch { repository.setTitle(id, title) }
    }

    override fun launchChild(item: WorkspaceItem, left: Int, top: Int, right: Int, bottom: Int) =
        launch(item, left, top, right, bottom)

    override fun reorderInFolder(folderId: Long, itemId: Long, rank: Int) {
        viewModelScope.launch { repository.reorderInFolder(folderId, itemId, rank) }
    }

    // ------------------------------------------------------------------
    // ItemMenuActions
    // ------------------------------------------------------------------

    fun openItemMenu(id: Long) {
        val entity = savedItems.firstOrNull { it.id == id }
        // Folder children are not on a page or in the hotseat, so the search has to reach into
        // folders too - a child's menu is opened by releasing it in place inside its folder.
        val onWorkspace = _home.value.pages.values.flatten() + _home.value.hotseat
        val item = (onWorkspace + onWorkspace.flatMap { it.children }).firstOrNull { it.id == id }
        if (entity == null || item == null) {
            _itemMenu.value = ItemMenuUiState()
            return
        }
        val key = item.key
        val entry = key?.let { appsMonitor.entryFor(it.componentName, it.profileSerial) }
        val shortcuts = if (key != null && item.type == LauncherItemType.APPLICATION) {
            appsMonitor.shortcuts(key.componentName.packageName, appsMonitor.userFor(key.profileSerial))
                .filterNot { it.isPinned && it.id == item.shortcutId }
                .mapNotNull { shortcut ->
                    val label = (shortcut.shortLabel ?: shortcut.longLabel)?.toString()
                        ?: return@mapNotNull null
                    ShortcutEntry(shortcut.id, label, shortcut.`package`, key.profileSerial)
                }
        } else {
            emptyList()
        }
        _itemMenu.value = ItemMenuUiState(
            item = item,
            shortcuts = shortcuts,
            canUninstall = entry != null && !entry.isWorkProfile && !isSystemApp(entry),
        )
    }

    private fun isSystemApp(entry: AppEntry): Boolean =
        systemApps.getOrPut(entry.componentName.packageName) {
            runCatching {
                val flags = getApplication<Application>().packageManager
                    .getApplicationInfo(entry.componentName.packageName, 0)
                    .flags
                flags and ApplicationInfo.FLAG_SYSTEM != 0
            }.getOrDefault(true)
        }

    override fun openAppInfo(item: WorkspaceItem) {
        val key = item.key ?: return
        val entry = appsMonitor.entryFor(key.componentName, key.profileSerial) ?: return
        appsMonitor.startAppDetails(entry, null)
    }

    override fun uninstall(item: WorkspaceItem) {
        val key = item.key ?: return
        bridge?.requestUninstall(key.componentName.packageName)
    }

    override fun launchShortcut(entry: ShortcutEntry) {
        val user = appsMonitor.userFor(entry.profileSerial)
        val shortcut = appsMonitor.shortcuts(entry.packageName, user)
            .firstOrNull { it.id == entry.shortcutId } ?: return
        appsMonitor.startShortcut(shortcut, null)
    }

    override fun pinShortcutToHome(entry: ShortcutEntry) {
        viewModelScope.launch {
            val row = pinnedShortcutRow(entry) ?: return@launch
            repository.addToFirstVacantCell(_home.value.grid, row)
        }
    }

    override fun addPendingShortcutToHome(
        shortcut: ShortcutEntry,
        screen: Int,
        rect: CellRect,
        displaced: Map<Long, CellRect>,
    ) {
        viewModelScope.launch {
            val row = pinnedShortcutRow(shortcut) ?: return@launch
            val id = repository.upsert(row)
            repository.moveTo(id, ContainerRef.Desktop, screen, rect, displaced = displaced)
        }
    }

    /**
     * The row for a shortcut, pinned with the system first.
     *
     * Pinned before persisted: an unpinned shortcut can be revoked by its app at any time, and a
     * row pointing at a revoked shortcut can never be launched.
     */
    private suspend fun pinnedShortcutRow(entry: ShortcutEntry): LauncherItemEntity? {
        val user = appsMonitor.userFor(entry.profileSerial)
        val shortcut = appsMonitor.shortcuts(entry.packageName, user)
            .firstOrNull { it.id == entry.shortcutId } ?: return null
        if (!appsMonitor.pinShortcut(shortcut)) {
            AppMessages.show("Could not add ${entry.label}")
            return null
        }
        return LauncherItemEntity(
            itemType = LauncherItemType.DEEP_SHORTCUT,
            containerId = ContainerRef.Desktop.toRaw(),
            title = entry.label,
            packageName = entry.packageName,
            className = shortcut.activity?.className,
            profileSerial = entry.profileSerial,
            shortcutId = entry.shortcutId,
        )
    }

    // ------------------------------------------------------------------
    // WidgetPickerActions
    // ------------------------------------------------------------------

    /**
     * Enumerates every installed provider, which queries the package manager — so it happens when
     * the picker opens rather than being kept live in the workspace state.
     */
    private fun loadWidgetPicker() {
        _widgetPicker.value = _widgetPicker.value.copy(loading = true)
        val spec = _home.value.grid
        val groups = appsMonitor.apps.value
            .map { it.profileSerial }
            .distinct()
            .flatMap { serial ->
                widgets.providers(appsMonitor.userFor(serial)).map { info ->
                    val (spanX, spanY) = widgets.spanFor(info, CELL_WIDTH_DP, CELL_HEIGHT_DP)
                    val label = runCatching { info.loadLabel(getApplication<Application>().packageManager) }
                        .getOrNull().orEmpty()
                    val appLabel = appsMonitor.apps.value
                        .firstOrNull { it.componentName.packageName == info.provider.packageName }
                        ?.label
                        ?: info.provider.packageName
                    appLabel to WidgetEntry(
                        provider = info.provider.flattenToString(),
                        label = label,
                        description = runCatching {
                            info.loadDescription(getApplication())?.toString()
                        }.getOrNull().orEmpty(),
                        spanX = spanX.coerceAtMost(spec.columns),
                        spanY = spanY.coerceAtMost(spec.rows),
                        profileSerial = serial,
                    )
                }
            }
            .groupBy({ it.first }, { it.second })
            .map { (appLabel, entries) -> WidgetGroup(appLabel, entries.sortedBy { it.label }) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appLabel })

        _widgetPicker.value = _widgetPicker.value.copy(loading = false)
        applyWidgetQuery(_widgetPicker.value.query, groups)
    }

    private var allWidgetGroups: List<WidgetGroup> = emptyList()

    private fun applyWidgetQuery(query: String, source: List<WidgetGroup>? = null) {
        if (source != null) allWidgetGroups = source
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            allWidgetGroups
        } else {
            allWidgetGroups.mapNotNull { group ->
                if (group.appLabel.contains(trimmed, ignoreCase = true)) return@mapNotNull group
                val matches = group.widgets.filter { it.label.contains(trimmed, ignoreCase = true) }
                if (matches.isEmpty()) null else group.copy(widgets = matches)
            }
        }
        _widgetPicker.value = _widgetPicker.value.copy(query = query, groups = filtered)
    }

    override fun setWidgetQuery(query: String) = applyWidgetQuery(query)

    override fun openWidgetPicker() {
        _widgetPicker.value = _widgetPicker.value.copy(open = true)
        loadWidgetPicker()
    }

    override fun closeWidgetPicker() {
        _widgetPicker.value = _widgetPicker.value.copy(open = false)
    }

    override fun addWidget(entry: WidgetEntry) {
        val bridge = bridge ?: return
        val component = widgets.unflatten(entry.provider) ?: return
        val info = widgets.providers(appsMonitor.userFor(entry.profileSerial))
            .firstOrNull { it.provider == component }
        if (info == null) {
            AppMessages.show("${entry.label} is no longer available")
            return
        }
        // Closed before the bind flow starts, not after it finishes: the consent dialog is another
        // activity, and a sheet still up behind it would be what the user comes back to.
        closeWidgetPicker()
        widgets.add(
            provider = info,
            bridge = bridge,
            profileSerial = entry.profileSerial,
            onBound = { appWidgetId ->
                viewModelScope.launch {
                    repository.addToFirstVacantCell(
                        _home.value.grid,
                        LauncherItemEntity(
                            itemType = LauncherItemType.APPWIDGET,
                            containerId = ContainerRef.Desktop.toRaw(),
                            spanX = entry.spanX,
                            spanY = entry.spanY,
                            title = entry.label,
                            packageName = component.packageName,
                            className = component.className,
                            profileSerial = entry.profileSerial,
                            appWidgetId = appWidgetId,
                            appWidgetProvider = entry.provider,
                        ),
                    )
                }
            },
            onCancelled = { AppMessages.show("${entry.label} was not added") },
        )
    }

    /** The hosted view for a placed widget, or null when the provider has gone away. */
    fun widgetView(appWidgetId: Int): AppWidgetHostView? {
        val info = widgets.providerInfo(appWidgetId) ?: return null
        return runCatching { widgets.createView(appWidgetId, info) }.getOrNull()
    }

    fun updateWidgetSize(view: AppWidgetHostView, widthDp: Int, heightDp: Int) =
        widgets.updateSize(view, widthDp, heightDp)

    // ------------------------------------------------------------------
    // SettingsActions
    // ------------------------------------------------------------------

    override fun setColumns(columns: Int) = changeGrid { it.copy(columns = columns) }

    override fun setRows(rows: Int) = changeGrid { it.copy(rows = rows) }

    override fun setHotseatSlots(slots: Int) = changeGrid { it.copy(hotseatSlots = slots) }

    /**
     * Applies a grid change and re-lays the workspace out for it.
     *
     * The regrid runs before the new spec reaches the UI, so the home screen never renders
     * old coordinates against a new grid - which would briefly show items overlapping or
     * hanging off the edge.
     */
    private fun changeGrid(transform: (GridSpec) -> GridSpec) {
        val next = transform(_home.value.grid)
        viewModelScope.launch {
            repository.regrid(next)
            _home.value = _home.value.copy(grid = next)
            _settings.value = _settings.value.copy(
                columns = next.columns,
                rows = next.rows,
                hotseatSlots = next.hotseatSlots,
            )
            ds.setLong(KEY_COLUMNS, next.columns.toLong())
            ds.setLong(KEY_ROWS, next.rows.toLong())
            ds.setLong(KEY_HOTSEAT, next.hotseatSlots.toLong())
        }
    }

    override fun setShowLabels(show: Boolean) {
        _settings.value = _settings.value.copy(showLabels = show)
        _home.value = _home.value.copy(showLabels = show)
        _drawer.value = _drawer.value.copy(showLabels = show)
        viewModelScope.launch { ds.setBoolean(KEY_SHOW_LABELS, show) }
    }

    override fun setIconScale(scale: Float) {
        val clamped = scale.coerceIn(MIN_ICON_SCALE, MAX_ICON_SCALE)
        _settings.value = _settings.value.copy(iconScale = clamped)
        _home.value = _home.value.copy(iconScale = clamped)
        _drawer.value = _drawer.value.copy(iconScale = clamped)
        viewModelScope.launch { ds.setDouble(KEY_ICON_SCALE, clamped.toDouble()) }
    }

    override fun pickWallpaper() {
        bridge?.pickWallpaper()
    }

    override fun requestDefaultHome() {
        bridge?.requestHomeRole()
    }

    fun refreshDefaultHome() {
        _settings.value = _settings.value.copy(isDefaultHome = isDefaultHome())
    }

    private fun isDefaultHome(): Boolean {
        val app = getApplication<Application>()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = app.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == app.packageName
    }

    /** A locale change relabels and re-themes every icon, so nothing cached survives it. */
    fun onLocaleChanged() {
        iconCache.clear()
        appsMonitor.refresh()
    }

    override fun onCleared() {
        appsMonitor.stop()
        widgetHost.stopListeningSafely()
        bridge = null
    }

    companion object {
        /** Rasterisation size for cached icons, in dp. Generous so scaling up stays sharp. */
        private const val ICON_CACHE_DP = 72

        /** Nominal cell size used only to turn a provider's minWidth/Height into a span. */
        private const val CELL_WIDTH_DP = 72
        private const val CELL_HEIGHT_DP = 88

        const val MIN_ICON_SCALE = 0.8f
        const val MAX_ICON_SCALE = 1.4f

        /** Grid sizes offered in settings. */
        val COLUMN_OPTIONS = listOf(3, 4, 5, 6)
        val ROW_OPTIONS = listOf(4, 5, 6, 7)

        private val SEED_INTENTS = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_VIEW, "https://example.com".toUri()),
            Intent("android.media.action.IMAGE_CAPTURE"),
            Intent(Intent.ACTION_SENDTO, "mailto:".toUri()),
        )

        private const val KEY_COLUMNS = "launcher_grid_columns"
        private const val KEY_ROWS = "launcher_grid_rows"
        private const val KEY_HOTSEAT = "launcher_hotseat_slots"
        private const val KEY_SHOW_LABELS = "launcher_show_labels"
        private const val KEY_ICON_SCALE = "launcher_icon_scale"
        private const val KEY_LAUNCH_COUNTS = "launcher_launch_counts"

        /** A single row of the drawer's grid, which is all a predictions row should ever be. */
        private const val PREDICTION_COUNT = 5

        /** Launcher3's own all-apps blur, in pixels; density-independent enough not to be a dp. */
        private const val WALLPAPER_BLUR_PX = 60
    }
}
