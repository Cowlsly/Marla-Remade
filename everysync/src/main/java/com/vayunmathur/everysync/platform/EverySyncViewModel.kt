package com.vayunmathur.everysync.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.everysync.Settings
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.auth.DavCredentials
import com.vayunmathur.everysync.auth.OAuthManager
import com.vayunmathur.everysync.auth.TokenStore
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.everysync.sink.CalendarSink
import com.vayunmathur.everysync.sink.ContactsSink
import com.vayunmathur.everysync.sync.SyncScheduler
import com.vayunmathur.everysync.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EverySyncViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AccountStore.getInstance(app)

    val accounts = store.accountsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Account names with a sync currently in progress. */
    val syncing = SyncStatus.syncing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val interval = Settings.intervalFlow(app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60L)

    val wifiOnly = Settings.wifiOnlyFlow(app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun startOAuth(providerId: String) {
        viewModelScope.launch { OAuthManager.start(getApplication(), providerId) }
    }

    fun davLogin(providerId: String, baseUrl: String, username: String, password: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val provider = ProviderRegistry.get(providerId) ?: return@launch
            val accountName = "$username (${provider.displayName})"
            TokenStore.getInstance(app).putDav(accountName, DavCredentials(baseUrl, username, password))
            store.upsert(
                AccountConfig(
                    accountName = accountName,
                    providerId = providerId,
                    davBaseUrl = baseUrl,
                    davUsername = username,
                    enabledTypes = provider.capabilities,
                ),
            )
            SyncScheduler.syncNow(app, accountName)
            onDone()
        }
    }

    fun addHealthConnectAccount(providerId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val provider = ProviderRegistry.get(providerId) ?: return@launch
            val accountName = provider.displayName
            store.upsert(
                AccountConfig(
                    accountName = accountName,
                    providerId = providerId,
                    enabledTypes = provider.capabilities,
                ),
            )
            SyncScheduler.syncNow(app, accountName)
            onDone()
        }
    }

    fun syncNow(accountName: String) {
        SyncScheduler.syncNow(getApplication(), accountName)
    }

    fun toggleType(accountName: String, type: DataType, enabled: Boolean) {
        viewModelScope.launch {
            val config = store.get(accountName) ?: return@launch
            val types = if (enabled) config.enabledTypes + type else config.enabledTypes - type
            store.upsert(config.copy(enabledTypes = types))
            val app = getApplication<Application>()
            if (enabled) {
                // Re-sync so the just-enabled type is repopulated on-device.
                SyncScheduler.syncNow(app, accountName)
            } else {
                // Remove the disabled type's data from the on-device provider so it
                // doesn't linger; it will be pulled again if re-enabled.
                withContext(Dispatchers.IO) {
                    when (type) {
                        DataType.CONTACTS -> ContactsSink.purge(app, accountName)
                        DataType.CALENDAR -> CalendarSink.purge(app, accountName)
                        DataType.HEALTH -> {}
                    }
                }
            }
        }
    }

    fun setInterval(minutes: Long) {
        viewModelScope.launch {
            Settings.setIntervalMinutes(getApplication(), minutes)
            SyncScheduler.schedulePeriodic(getApplication())
        }
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch {
            Settings.setWifiOnly(getApplication(), value)
            SyncScheduler.schedulePeriodic(getApplication())
        }
    }

    fun setConflictPolicy(policy: String) {
        viewModelScope.launch { Settings.setConflictPolicy(getApplication(), policy) }
    }

    fun removeAccount(accountName: String) {
        viewModelScope.launch { store.remove(accountName) }
    }
}
