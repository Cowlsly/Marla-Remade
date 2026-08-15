package com.vayunmathur.everysync.sync

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.vayunmathur.everysync.data.Settings
import com.vayunmathur.everysync.auth.AccountStore
import java.util.concurrent.TimeUnit

/** Schedules background + on-demand syncs via WorkManager and the SyncAdapter framework. */
object SyncScheduler {
    private const val PERIODIC_WORK = "EverySyncPeriodic"
    private const val IMMEDIATE_WORK = "EverySyncNow"

    /** (Re)schedule the periodic background sync using the current global settings. */
    fun schedulePeriodic(context: Context) {
        val interval = Settings.intervalMinutes(context).coerceAtLeast(15)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (Settings.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request,
        )
    }

    /** Immediately sync a single account. */
    fun syncNow(context: Context, accountName: String) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(Data.Builder().putString(SyncWorker.KEY_ACCOUNT, accountName).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$IMMEDIATE_WORK-$accountName", ExistingWorkPolicy.REPLACE, request,
        )
        requestSystemSync(context, accountName)
    }

    /** Immediately sync every account. */
    fun syncAllNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request,
        )
    }

    /** Ask the SyncAdapter framework to run for the contacts + calendar authorities. */
    private fun requestSystemSync(context: Context, accountName: String) {
        val account = AccountStore.getInstance(context).systemAccount(accountName)
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        runCatching { ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras) }
        runCatching { ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras) }
    }
}
