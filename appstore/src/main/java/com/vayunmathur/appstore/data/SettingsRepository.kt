package com.vayunmathur.appstore.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "appstore-settings")

/**
 * User-adjustable preferences for the store.
 *
 * The persisted value is mirrored into a [StateFlow] so the settings UI can render the
 * stored choice without a suspending read. The default is applied on read
 * ([DEFAULT_AUTO_INSTALL_UPDATES]) rather than written at first launch, so an existing user
 * with nothing stored still gets the documented default.
 */
class SettingsRepository(
    private val context: Context,
    scope: CoroutineScope,
) {
    private val _autoInstallUpdates = MutableStateFlow(DEFAULT_AUTO_INSTALL_UPDATES)

    /**
     * Whether the periodic update check may also download and install updates on its own,
     * with no tap. Off by default: this installs apps without the user present, so it is
     * strictly opt-in. With it off the same updates wait for the user to ask for them.
     * Either way the install itself is silent, so this only decides who starts it. Only
     * ever acts on updates that install silently (see the worker).
     */
    val autoInstallUpdates: StateFlow<Boolean> = _autoInstallUpdates.asStateFlow()

    init {
        scope.launch {
            context.settingsDataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .collect { prefs -> _autoInstallUpdates.value = prefs.autoInstallUpdates() }
        }
    }

    suspend fun setAutoInstallUpdates(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_INSTALL_UPDATES] = enabled }
    }

    /**
     * One-shot read of the persisted value, for callers with no long-lived scope to
     * collect a flow — the [UpdateCheckWorker] is cold-started by WorkManager and needs the
     * committed choice, not the not-yet-populated flow default.
     */
    suspend fun readAutoInstallUpdates(): Boolean = read().autoInstallUpdates()

    private suspend fun read(): Preferences =
        context.settingsDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()

    private fun Preferences.autoInstallUpdates(): Boolean =
        this[KEY_AUTO_INSTALL_UPDATES] ?: DEFAULT_AUTO_INSTALL_UPDATES

    companion object {
        const val DEFAULT_AUTO_INSTALL_UPDATES = false
        private val KEY_AUTO_INSTALL_UPDATES =
            booleanPreferencesKey("auto_install_updates")
    }
}
