package com.vayunmathur.library.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DataStoreUtils private constructor(context: Context) {
    private val dataStore = createDataStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Eagerly mirror the persisted preferences so the synchronous getters below
    // can read the latest snapshot without blocking the calling thread.
    private val state: StateFlow<Preferences> =
        dataStore.data.stateIn(scope, SharingStarted.Eagerly, emptyPreferences())

    private fun <T> getWithFallback(key: Preferences.Key<T>): T? {
        return state.value[key]
    }

    fun getByteArray(name: String): ByteArray? {
        return getWithFallback(byteArrayPreferencesKey(name))
    }

    /** Suspend variant that awaits DataStore hydration (safe on cold start / service start). */
    suspend fun getByteArrayAwait(name: String): ByteArray? {
        return dataStore.data.first()[byteArrayPreferencesKey(name)]
    }

    suspend fun setByteArray(name: String, value: ByteArray, onlyIfAbsent: Boolean = false) {
        dataStore.edit {
            if(onlyIfAbsent && it.contains(byteArrayPreferencesKey(name))) return@edit
            it[byteArrayPreferencesKey(name)] = value
        }
    }

    fun getLong(name: String): Long? {
        return getWithFallback(longPreferencesKey(name))
    }

    /** Suspend variant that awaits DataStore hydration. */
    suspend fun getLongAwait(name: String): Long? {
        return dataStore.data.first()[longPreferencesKey(name)]
    }

    // `dataStore.data` re-emits the whole preference set on every write, so without this a
    // collector of one key wakes up for every change to any other. Anything watching a
    // handful of keys at once (the keyboard watches twelve) otherwise does that much
    // redundant work on each save.
    fun booleanFlow(name: String): Flow<Boolean> {
        return dataStore.data.mapNotNull { it[booleanPreferencesKey(name)] }.distinctUntilChanged()
    }

    /** Suspend variant that awaits DataStore hydration. */
    suspend fun getBooleanAwait(name: String, default: Boolean = false): Boolean {
        return dataStore.data.first()[booleanPreferencesKey(name)] ?: default
    }

    /** Defaulted variant: unlike [booleanFlow], emits before the key is first written. */
    fun booleanFlow(name: String, default: Boolean): Flow<Boolean> {
        return dataStore.data.map { it[booleanPreferencesKey(name)] ?: default }.distinctUntilChanged()
    }

    suspend fun setBoolean(name: String, value: Boolean) {
        dataStore.edit {
            it[booleanPreferencesKey(name)] = value
        }
    }

    fun longFlow(s: String): Flow<Long> {
        return dataStore.data.mapNotNull { it[longPreferencesKey(s)] }.distinctUntilChanged()
    }

    fun longFlow(name: String, default: Long): Flow<Long> {
        return dataStore.data.map { it[longPreferencesKey(name)] ?: default }.distinctUntilChanged()
    }

    suspend fun setLong(s: String, userid: Long, onlyIfAbsent: Boolean = false) {
        dataStore.edit {
            if(onlyIfAbsent && it.contains(longPreferencesKey(s))) return@edit
            it[longPreferencesKey(s)] = userid
        }
    }

    /** Atomically raises the stored value to [value] when it is larger. Returns true if it changed. */
    suspend fun setLongIfGreater(name: String, value: Long): Boolean {
        var updated = false
        dataStore.edit {
            val current = it[longPreferencesKey(name)] ?: 0L
            if (value > current) {
                it[longPreferencesKey(name)] = value
                updated = true
            }
        }
        return updated
    }

    fun doubleFlow(string: String): Flow<Double> {
        return dataStore.data.mapNotNull { it[doublePreferencesKey(string)] }.distinctUntilChanged()
    }

    fun getDouble(name: String): Double? {
        return getWithFallback(doublePreferencesKey(name))
    }

    suspend fun setDouble(string: String, progress: Double) {
        dataStore.edit {
            it[doublePreferencesKey(string)] = progress
        }
    }

    fun getString(string: String): String? {
        return getWithFallback(stringPreferencesKey(string))
    }

    /** Suspend variant that awaits DataStore hydration. */
    suspend fun getStringAwait(name: String): String? {
        return dataStore.data.first()[stringPreferencesKey(name)]
    }

    suspend fun setString(string: String, value: String, onlyIfAbsent: Boolean = false) {
        dataStore.edit {
            if (onlyIfAbsent && it.contains(stringPreferencesKey(string))) return@edit
            it[stringPreferencesKey(string)] = value
        }
    }

    /** Read-modify-write [name] in one edit, so concurrent callers can't drop each other's change. */
    suspend fun updateString(name: String, transform: (String?) -> String) {
        dataStore.edit {
            it[stringPreferencesKey(name)] = transform(it[stringPreferencesKey(name)])
        }
    }

    fun stringFlow(key: String): Flow<String> {
        return dataStore.data.mapNotNull { it[stringPreferencesKey(key)] }.distinctUntilChanged()
    }

    fun stringSetFlow(key: String): Flow<Set<String>> {
        return dataStore.data.map { it[stringSetPreferencesKey(key)] ?: emptySet() }.distinctUntilChanged()
    }

    /**
     * Suspend variant that awaits DataStore hydration. Use this rather than a snapshot read
     * when the caller may run before the store has loaded — e.g. a service started at boot.
     */
    suspend fun getStringSetAwait(name: String): Set<String> {
        return dataStore.data.first()[stringSetPreferencesKey(name)] ?: emptySet()
    }

    fun addStringToSet(string: String, id: String) {
        scope.launch {
            dataStore.edit {
                val set = it[stringSetPreferencesKey(string)] ?: setOf()
                it[stringSetPreferencesKey(string)] = set + id
            }
        }
    }

    /** Atomically adds [id] to the set, returning true only if it was not already present. */
    suspend fun addStringToSetIfAbsent(string: String, id: String): Boolean {
        var added = false
        dataStore.edit {
            val set = it[stringSetPreferencesKey(string)] ?: setOf()
            if (id !in set) {
                it[stringSetPreferencesKey(string)] = set + id
                added = true
            }
        }
        return added
    }

    fun removeStringFromSet(string: String, id: String) {
        scope.launch {
            dataStore.edit {
                val set = it[stringSetPreferencesKey(string)] ?: setOf()
                it[stringSetPreferencesKey(string)] = set - id
            }
        }
    }

    /**
     * Drop [names] entirely, whatever type they hold. Deleting the thing a namespaced key belongs to
     * has to delete the key too, or the store grows a tail of state nothing can ever reach again.
     */
    suspend fun removeKeys(names: Collection<String>) {
        dataStore.edit { prefs ->
            for (name in names) {
                prefs.remove(stringSetPreferencesKey(name))
                prefs.remove(longPreferencesKey(name))
            }
        }
    }

    fun getBoolean(string: String, bool: Boolean): Boolean {
        return getWithFallback(booleanPreferencesKey(string)) ?: bool
    }

    companion object {
        @Volatile
        private var instance: DataStoreUtils? = null
        @Volatile
        private var deviceProtectedInstance: DataStoreUtils? = null
        /**
         * @param deviceProtected when true, the store is backed by device-protected storage
         *   (available in Direct Boot, i.e. before the first unlock after a reboot). Use this
         *   for components that must run on the lock screen — e.g. an IME used to type the
         *   unlock password. Data in device-protected storage is not credential-encrypted, so
         *   only put non-sensitive settings there. The two modes are separate singletons/files.
         */
        fun getInstance(context: Context, deviceProtected: Boolean = false): DataStoreUtils {
            if (deviceProtected) {
                return deviceProtectedInstance ?: synchronized(this) {
                    deviceProtectedInstance ?: DataStoreUtils(
                        context.applicationContext.createDeviceProtectedStorageContext()
                    ).also { deviceProtectedInstance = it }
                }
            }
            // First check (no locking for performance)
            return instance ?: synchronized(this) {
                // Second check (inside lock to ensure only one thread initializes)
                instance ?: DataStoreUtils(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}

private fun createDataStore(context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create { context.filesDir.resolve("datastore_default.preferences_pb") }
