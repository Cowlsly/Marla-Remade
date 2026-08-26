package com.vayunmathur.appstore.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.appstore.MainActivity
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.installer.InstallCoordinator
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.TimeUnit

/**
 * Checks for updates in the background and says so once, quietly.
 *
 * The store used to require two manual taps — "Sync F-Droid", then "Check Play" — which
 * is not something anyone remembers to do, so an app installed here would sit on a stale
 * It refreshes the offline catalogues, asks Play about the
 * packages neither of them lists, and posts a notification only when the set of available
 * updates has actually changed since the last notification.
 *
 * If the user has opted in to unattended updates ([SettingsRepository.autoInstallUpdates]),
 * it also downloads and installs — as a foreground service — the updates it can apply
 * silently: packages this store is the installer or update owner of, on API 31+. Everything
 * it can't install without a prompt is left to the notification, exactly as before.
 * Auto-install is off by default.
 */
class UpdateCheckWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        // WorkManager can cold-start the process without MainActivity initializing TLS.
        NetworkClient.init(context, TrustBundle.STANDARD)
        val scope = CoroutineScope(SupervisorJob())
        val db = AppStoreDatabaseRepository.get(context).database
        val catalog = CatalogRepository(context, db, scope)
        val installedRepo = InstalledAppsRepository(context)
        val play = PlayRepository(context)
        val accrescent = AccrescentRepository(context, db)

        catalog.sync()
        installedRepo.refresh()
        play.restore()

        val installed = installedRepo.updatable.value
        val fromCatalog = catalog.updatesFor(installed)

        // Only Play can answer for packages the offline catalogues have never heard of — except
        // the Sandboxed Google Play components, which Play also hosts but must never update
        // here: only the builds GrapheneOS re-hosts are the ones this device can use.
        val index = catalog.packageIndex.value
        val unknown = installed
            .filter {
                it.packageName !in index &&
                    it.packageName !in SandboxedGooglePlay.PACKAGES
            }
            .map { it.packageName }
        val remote = play.details(unknown).associateBy { it.packageName }
        val fromPlay = installed.mapNotNull { inst ->
            remote[inst.packageName]?.takeIf { it.versionCode > inst.versionCode }
        }

        // Accrescent: refresh its signed allowlist, then ask its API for a newer build of each
        // installed package it vouches for. Auto-install still routes through InstallCoordinator,
        // which re-verifies signer + min-version before committing.
        accrescent.refreshRepoData()
        val accrescentIds = accrescent.appIds()
        val fromAccrescent = installed
            .filter { it.packageName in accrescentIds }
            .mapNotNull { inst ->
                val update = runCatching {
                    accrescent.updateInfo(inst.packageName, inst.versionCode)
                }.getOrNull() ?: return@mapNotNull null
                val details = accrescent.details(inst.packageName) ?: UnifiedApp(
                    packageName = inst.packageName,
                    source = AppSource.ACCRESCENT,
                    name = inst.packageName.substringAfterLast('.'),
                )
                details.copy(versionCode = update.versionCode, versionName = update.versionName)
            }

        // The surviving row's source decides which download-and-verify path the update takes,
        // so it has to be the same precedence the rest of the store uses.
        val updates = (fromCatalog + fromPlay + fromAccrescent)
            .sortedBy { it.source.priority }
            .distinctBy { it.packageName }

        val settings = SettingsRepository(context, scope)
        val autoInstall = settings.readAutoInstallUpdates()

        if (autoInstall && updates.isNotEmpty()) {
            val eligible = updates.filter { canSilentlyUpdate(it.packageName) }
            if (eligible.isNotEmpty()) {
                autoInstall(eligible, db, play, accrescent)
                installedRepo.refresh()
            }
            // Only nag about the updates we could not apply on our own.
            val remaining = updates.filterNot { canSilentlyUpdate(it.packageName) }
            notifyIfChanged(
                remaining.map { it.packageName }.toSortedSet(),
                remaining.size,
                needsConfirmation = true,
            )
        } else {
            notifyIfChanged(
                updates.map { it.packageName }.toSortedSet(),
                updates.size,
                needsConfirmation = false,
            )
        }

        accrescent.shutdown()
        scope.cancel()
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "Update check failed", e)
        Result.retry()
    }

    /**
     * Notify only when the set of updatable packages differs from last time.
     *
     * Without this, a daily check on a phone with one perpetually-stale app would post the
     * same notification every day until the user turned notifications off.
     *
     * [needsConfirmation] distinguishes updates left over from an auto-install run, which the OS
     * will not let us apply unattended, from an ordinary "updates are waiting" nudge. It is part
     * of the de-dupe signature so toggling the setting re-posts with the matching wording.
     */
    private fun notifyIfChanged(packages: Set<String>, count: Int, needsConfirmation: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val signature = packages.joinToString(",") + "|$needsConfirmation"
        if (prefs.getString(KEY_LAST_NOTIFIED, "") == signature) return
        prefs.edit { putString(KEY_LAST_NOTIFIED, signature) }

        if (count == 0) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        createChannel()
        val body = context.getString(
            if (needsConfirmation) R.string.updates_confirm_notification_body
            else R.string.updates_notification_body
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                context.resources.getQuantityString(
                    if (needsConfirmation) R.plurals.updates_confirm_count
                    else R.plurals.updates_count,
                    count,
                    count,
                )
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

/**
     * Download and silently install a set of updates, promoting the worker to a foreground
     * service for the duration so the OS doesn't kill it mid-install.
     *
     * [canSilentlyUpdate] has already confirmed each package will install without a prompt,
     * so nothing here can surface a dialog with the user absent.
     */
    private suspend fun autoInstall(
        apps: List<UnifiedApp>,
        db: AppDatabase,
        play: PlayRepository,
        accrescent: AccrescentRepository,
    ) {
        runCatching { setForeground(installingForegroundInfo(apps.size)) }
        val installer = InstallCoordinator(
            context = context,
            db = db,
            play = play,
            accrescent = accrescent,
            ownSigningCertificates = { ApkCertificates.selfSigners(context) },
        )
        var installed = 0
        for (app in apps) {
            val outcome = runCatching { installer.install(app) }.getOrNull()
            if (outcome?.started == true) installed++
        }
        if (installed > 0) notifyInstalled(installed)
    }

    /**
     * Whether an update to [packageName] would install without a system dialog.
     *
     * Only true when this store is the package's installer of record — or, on API 34+, its
     * update owner. For anything else the OS would raise a confirmation the background job
     * can't usefully answer, so those are left to the notification instead.
     */
    private fun canSilentlyUpdate(packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            val info = context.packageManager.getInstallSourceInfo(packageName)
            val self = context.packageName
            val ownsUpdate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                info.updateOwnerPackageName == self
            ownsUpdate || info.installingPackageName == self
        }.getOrDefault(false)
    }

    private fun installingForegroundInfo(count: Int): ForegroundInfo {
        createChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                context.resources.getQuantityString(R.plurals.updates_installing, count, count)
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                INSTALL_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(INSTALL_NOTIFICATION_ID, notification)
        }
    }

    private fun notifyInstalled(count: Int) {
        if (!canPostNotifications()) return
        createChannel()
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                context.resources.getQuantityString(R.plurals.updates_installed, count, count)
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(INSTALLED_NOTIFICATION_ID, notification)
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.updates_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.updates_channel_description) }
        )
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        private const val CHANNEL_ID = "appstore-updates"
        private const val NOTIFICATION_ID = 4201
        private const val INSTALL_NOTIFICATION_ID = 4202
        private const val INSTALLED_NOTIFICATION_ID = 4203
        private const val PREFS = "appstore-update-check"
        private const val KEY_LAST_NOTIFIED = "last_notified_packages"

        /**
         * Twelve hours, and no immediate run.
         *
         * A sync pulls F-Droid's reproducibility feed and signed index — tens of megabytes
         * — so kicking one off every time the activity starts would be a lot of somebody's
         * data allowance. `KEEP` leaves an already-scheduled chain alone, and the periodic
         * request only fires on an unmetered connection.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        private const val WORK_NAME = "AppStoreUpdateCheck"
    }
}
