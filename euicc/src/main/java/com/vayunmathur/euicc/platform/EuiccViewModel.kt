package com.vayunmathur.euicc.platform

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.euicc.EuiccNative
import com.vayunmathur.euicc.data.EuiccInfo
import com.vayunmathur.euicc.data.Notification
import com.vayunmathur.euicc.data.Profile
import com.vayunmathur.euicc.telephony.EuiccChannelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Aggregate UI state for the LPA screen. */
data class EuiccScreenState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Transient success/info notice (e.g. after a download). */
    val message: String? = null,
    val eid: String? = null,
    val info: EuiccInfo? = null,
    val profiles: List<Profile> = emptyList(),
    val notifications: List<Notification> = emptyList(),
)

/** Native download outcome (`{success, message}`). */
@kotlinx.serialization.Serializable
private data class DownloadResult(val success: Boolean = false, val message: String = "")

class EuiccViewModel(app: Application) : AndroidViewModel(app) {
    private val channelManager = EuiccChannelManager(app)
    private val json = Json { ignoreUnknownKeys = true }

    var state by mutableStateOf(EuiccScreenState())
        private set

    init {
        reload()
    }

    /** Reads EID, eUICC info, profiles, and notifications in one channel session. */
    fun reload() {
        val carryMessage = state.message
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    channelManager.withIsdrChannel {
                        EuiccScreenState(
                            loading = false,
                            message = carryMessage,
                            eid = EuiccNative.nativeGetEid(),
                            info = EuiccNative.nativeGetEuiccInfo()?.let { json.decodeFromString<EuiccInfo>(it) },
                            profiles = EuiccNative.nativeGetProfiles()
                                ?.let { json.decodeFromString<List<Profile>>(it) } ?: emptyList(),
                            notifications = EuiccNative.nativeListNotifications()
                                ?.let { json.decodeFromString<List<Notification>>(it) } ?: emptyList(),
                        )
                    }
                }
            }
            state = outcome.getOrElse {
                state.copy(loading = false, error = it.message ?: "eUICC unavailable")
            }
        }
    }

    /** Downloads and installs the profile for an activation code, then reloads. */
    fun downloadProfile(activationCode: String) {
        state = state.copy(loading = true, error = null, message = null)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    channelManager.withIsdrChannel { EuiccNative.nativeDownloadProfile(activationCode) }
                }
            }
            outcome.fold(
                onSuccess = { raw ->
                    val result = runCatching { json.decodeFromString<DownloadResult>(raw) }.getOrNull()
                    when {
                        result == null -> state = state.copy(loading = false, error = "Download failed")
                        result.success -> {
                            state = state.copy(message = result.message)
                            reload()
                        }
                        else -> state = state.copy(loading = false, error = result.message)
                    }
                },
                onFailure = { state = state.copy(loading = false, error = it.message ?: "Download failed") },
            )
        }
    }

    fun enable(iccid: String) = action { EuiccNative.nativeEnableProfile(iccid) }
    fun disable(iccid: String) = action { EuiccNative.nativeDisableProfile(iccid) }
    fun delete(iccid: String) = action { EuiccNative.nativeDeleteProfile(iccid) }
    fun rename(iccid: String, nickname: String) = action { EuiccNative.nativeSetNickname(iccid, nickname) }
    fun removeNotification(seq: Int) = action { EuiccNative.nativeRemoveNotification(seq) }

    /** Runs a single ES10 mutation in its own channel session, then reloads. */
    private fun action(op: () -> Int) {
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { channelManager.withIsdrChannel { op() } }
            }
            val error = outcome.fold(
                onSuccess = { code -> if (code == 0) null else "Operation failed (code $code)" },
                onFailure = { it.message ?: "Operation failed" },
            )
            if (error == null) {
                reload()
            } else {
                state = state.copy(loading = false, error = error)
            }
        }
    }
}
