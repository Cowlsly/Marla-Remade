package com.vayunmathur.appstore.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Which source a sync run is currently working on, for a progress line. */
enum class SyncStep { FDROID, MODERN_APPS }

/** What one sync run managed to do, per source. */
data class SyncReport(
    val fdroidCount: Int? = null,
    /** How many F-Droid apps carry the reproducible badge this sync. */
    val fdroidReproducible: Int = 0,
    val modernCount: Int? = null,
    /** Sources the user has switched off, which this run skipped rather than attempted. */
    val skipped: Set<AppSource> = emptySet(),
) {
    val anyFailed: Boolean
        get() = (AppSource.FDROID !in skipped && fdroidCount == null) ||
            (AppSource.MODERN_APPS !in skipped && modernCount == null)

    /** True when every offline source is switched off, so there was nothing to sync. */
    val allSkipped: Boolean
        get() = DefaultRepos.ALL.all { it.source in skipped }
}

/**
 * The offline catalogue: the F-Droid and Modern Apps repository indexes, as cached in
 * Room, plus the sync that refreshes them.
 *
 * Play is deliberately absent — it has no downloadable catalogue and is queried live (see
 * [com.vayunmathur.appstore.data.play.PlayRepository]). What lives here is everything the
 * store can answer without a network.
 *
 * Queries are targeted rather than "load every row and filter in Kotlin". The F-Droid
 * index is several thousand apps with descriptions and screenshot lists attached, and the
 * browse screen only ever shows a screenful of them.
 */
class CatalogRepository(
    context: Context,
    private val db: AppDatabase,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val fdroidProvider = FDroidAppProvider(db, appContext, DefaultRepos.FDROID)
    private val modernProvider = FDroidAppProvider(db, appContext, DefaultRepos.MODERN_APPS)

    val repos: StateFlow<List<RepoEntity>> =
        db.repoDao().allFlow().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Package → what the catalogue knows about it, without the payload.
     *
     * Backs both source attribution for installed apps and the F-Droid/Modern Apps side
     * of the update check, neither of which needs a single description.
     */
    val packageIndex: StateFlow<Map<String, PackageIndexRow>> = db.cachedAppDao().indexFlow()
        .map { rows -> rows.associateBy { it.packageName } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** This repo's own apps. Small, always shown in full, so it stays a live flow. */
    val modernApps: StateFlow<List<UnifiedApp>> =
        db.cachedAppDao().bySourceFlow(AppSource.MODERN_APPS.name)
            .map { rows -> rows.map { it.toUnifiedApp() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun sourceOf(packageName: String): AppSource? =
        packageIndex.value[packageName]?.source?.let {
            runCatching { AppSource.valueOf(it) }.getOrNull()
        }

    /** F-Droid's most recently published builds — the browse screen's "New and updated". */
    suspend fun recentlyUpdated(limit: Int = 30): List<UnifiedApp> = withContext(Dispatchers.IO) {
        runCatching { db.cachedAppDao().recentlyUpdated(limit).map { it.toUnifiedApp() } }
            .getOrDefault(emptyList())
    }

    suspend fun byCategory(category: String, limit: Int = 60): List<UnifiedApp> =
        withContext(Dispatchers.IO) {
            runCatching { db.cachedAppDao().byCategory(category, limit).map { it.toUnifiedApp() } }
                .getOrDefault(emptyList())
        }

    /** Categories present in the catalogue, most-populated first. */
    suspend fun categories(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            db.cachedAppDao().allCategoryStrings()
                .flatMap { it.split(",") }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key }
        }.getOrDefault(emptyList())
    }

    suspend fun searchLocal(query: String, limit: Int = 60): List<UnifiedApp> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            runCatching { db.cachedAppDao().searchAll(query, limit).map { it.toUnifiedApp() } }
                .getOrDefault(emptyList())
        }

    suspend fun byPackage(packageName: String): UnifiedApp? = withContext(Dispatchers.IO) {
        runCatching { db.cachedAppDao().byPackage(packageName)?.toUnifiedApp() }.getOrNull()
    }

    suspend fun byPackages(packages: List<String>): List<UnifiedApp> = withContext(Dispatchers.IO) {
        if (packages.isEmpty()) return@withContext emptyList()
        runCatching {
            packages.chunked(SQLITE_VARIABLE_LIMIT)
                .flatMap { db.cachedAppDao().byPackages(it) }
                .map { it.toUnifiedApp() }
        }.getOrDefault(emptyList())
    }

    /** Rows for packages that are installed but older than what the catalogue offers. */
    suspend fun updatesFor(installed: List<InstalledInfo>): List<UnifiedApp> {
        val index = packageIndex.value
        val stale = installed.filter { inst ->
            val known = index[inst.packageName] ?: return@filter false
            known.versionCode > inst.versionCode
        }.map { it.packageName }
        return byPackages(stale)
    }

    /**
     * Refresh both offline sources.
     *
     * Order matters: `packageName` is the cache table's primary key, so when a package is
     * published by both sources the *later* upsert wins the row. Modern Apps goes last —
     * every app in this repo is also on F-Droid, and the copy signed with this store's own
     * key is the one that can be verified end to end.
     *
     * Each source is independent: one failing leaves the other's rows updated and its own
     * previous rows untouched, rather than aborting the whole run.
     *
     * A source missing from [enabled] is skipped and its cached rows dropped, so nothing it
     * published is still offered by browse, search or the update check.
     */
    suspend fun sync(
        enabled: Set<AppSource>,
        onProgress: (SyncStep) -> Unit = {},
    ): SyncReport = withContext(Dispatchers.IO) {
        ensureDefaultRepos()
        purgeDisabled(enabled)

        val fdroid = if (DefaultRepos.FDROID.source in enabled) {
            onProgress(SyncStep.FDROID)
            runCatching { fdroidProvider.syncIntoDb() }.getOrNull()
        } else {
            null
        }

        val modern = if (DefaultRepos.MODERN_APPS.source in enabled) {
            onProgress(SyncStep.MODERN_APPS)
            runCatching { modernProvider.syncIntoDb() }.getOrNull()
        } else {
            null
        }

        SyncReport(
            fdroidCount = fdroid,
            fdroidReproducible = fdroidProvider.lastReproducibleCount,
            modernCount = modern,
            skipped = DefaultRepos.ALL.mapNotNullTo(mutableSetOf()) {
                it.source.takeIf { source -> source !in enabled }
            },
        )
    }

    /**
     * Drop the cached rows of every offline source not in [enabled].
     *
     * The [RepoEntity] itself stays, so the sources screen can still show what the repo is
     * pinned to and when it last synced — turning a source off is not the same as forgetting
     * it, and re-enabling it should not have to re-derive the pin.
     */
    suspend fun purgeDisabled(enabled: Set<AppSource>) = withContext(Dispatchers.IO) {
        DefaultRepos.ALL.filterNot { it.source in enabled }.forEach {
            db.cachedAppDao().deleteByRepo(it.url)
        }
    }

    /** Seed the fixed repository set and prune rows left by older configurations. */
    private suspend fun ensureDefaultRepos() {
        val existing = db.repoDao().all()
        val supportedUrls = DefaultRepos.ALL.mapTo(mutableSetOf()) { it.url }
        existing.filterNot { it.url in supportedUrls }.forEach {
            db.repoDao().deleteByUrl(it.url)
            db.cachedAppDao().deleteByRepo(it.url)
        }
        DefaultRepos.ALL.filter { descriptor ->
            existing.none { it.url == descriptor.url }
        }.forEach { db.repoDao().upsert(it.toEntity()) }

        // The retired GitHub provider never inserted a RepoEntity, so pruning rows cannot
        // discover its pseudo-key. This cleanup is cheap, idempotent, and safe to retain.
        db.cachedAppDao().deleteByRepo(DefaultRepos.LEGACY_MODERN_APPS_REPO_KEY)
    }

    private companion object {
        /** SQLite's default host-parameter ceiling; `IN (:packages)` binds one each. */
        const val SQLITE_VARIABLE_LIMIT = 900
    }
}
