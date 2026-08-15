package com.vayunmathur.everysync

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.Flow

/** Global EverySync settings backed by DataStore. */
object Settings {
    const val CONFLICT_LWW = "lww"
    const val CONFLICT_REMOTE = "remote"
    const val CONFLICT_LOCAL = "local"

    private const val KEY_INTERVAL = "global_interval_minutes"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_CONFLICT = "conflict_policy"

    fun intervalMinutes(context: Context): Long =
        DataStoreUtils.getInstance(context).getLong(KEY_INTERVAL) ?: 60L

    suspend fun setIntervalMinutes(context: Context, minutes: Long) {
        DataStoreUtils.getInstance(context).setLong(KEY_INTERVAL, minutes.coerceAtLeast(15))
    }

    fun intervalFlow(context: Context): Flow<Long> =
        DataStoreUtils.getInstance(context).longFlow(KEY_INTERVAL, 60L)

    fun wifiOnly(context: Context): Boolean =
        DataStoreUtils.getInstance(context).getBoolean(KEY_WIFI_ONLY, false)

    suspend fun setWifiOnly(context: Context, value: Boolean) {
        DataStoreUtils.getInstance(context).setBoolean(KEY_WIFI_ONLY, value)
    }

    fun wifiOnlyFlow(context: Context): Flow<Boolean> =
        DataStoreUtils.getInstance(context).booleanFlow(KEY_WIFI_ONLY)

    fun conflictPolicy(context: Context): String =
        DataStoreUtils.getInstance(context).getString(KEY_CONFLICT)?.ifBlank { null } ?: CONFLICT_LWW

    suspend fun setConflictPolicy(context: Context, policy: String) {
        DataStoreUtils.getInstance(context).setString(KEY_CONFLICT, policy)
    }
}
