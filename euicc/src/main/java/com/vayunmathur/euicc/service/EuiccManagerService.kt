package com.vayunmathur.euicc.service

import android.service.euicc.EuiccService
import android.service.euicc.EuiccService.OTA_STATUS_NOT_UPDATED
import android.service.euicc.EuiccService.OtaStatusChangedCallback
import android.service.euicc.EuiccService.RESULT_FIRST_USER
import android.service.euicc.EuiccService.RESULT_MUST_DEACTIVATE_SIM
import android.service.euicc.EuiccService.RESULT_OK
import android.service.euicc.GetDefaultDownloadableSubscriptionListResult
import android.service.euicc.GetDownloadableSubscriptionMetadataResult
import android.service.euicc.GetEuiccProfileInfoListResult
import android.telephony.euicc.DownloadableSubscription
import android.telephony.euicc.EuiccInfo as FrameworkEuiccInfo
// EuiccProfileInfo is in android.service.euicc, not android.telephony.euicc where its
// siblings above live. Importing the latter compiles against the stubs but throws
// ClassNotFoundException the first time the framework asks for the profile list.
import android.service.euicc.EuiccProfileInfo
import com.vayunmathur.euicc.EuiccNative
import com.vayunmathur.euicc.data.EuiccInfo
import com.vayunmathur.euicc.data.Profile
import com.vayunmathur.euicc.telephony.EuiccChannelManager
import kotlinx.serialization.json.Json

/**
 * The system LPA entry point: the platform's `EuiccManager` binds this service
 * (once the app is the configured LPA and is platform-signed / carrier-privileged)
 * and routes all eSIM operations through it. Each callback drives the built-in
 * eUICC over the ISD-R logical channel via [EuiccChannelManager] and the native
 * SGP.22 core.
 *
 * There is a single soldered eUICC, so `slotId` is ignored. Live behavior requires
 * a platform-signed priv-app install and a real eUICC; it cannot be exercised here.
 */
class EuiccManagerService : EuiccService() {
    private val channelManager by lazy { EuiccChannelManager(applicationContext) }
    private val json = Json { ignoreUnknownKeys = true }

    override fun onGetEid(slotId: Int): String? =
        runCatching { channelManager.withIsdrChannel { EuiccNative.nativeGetEid() } }.getOrNull()

    override fun onGetOtaStatus(slotId: Int): Int = OTA_STATUS_NOT_UPDATED

    override fun onStartOtaIfNecessary(slotId: Int, statusChangedCallback: OtaStatusChangedCallback) {
        // No OTA channel for this LPA; report the terminal state and return.
        statusChangedCallback.onOtaStatusChanged(OTA_STATUS_NOT_UPDATED)
    }

    override fun onGetEuiccProfileInfoList(slotId: Int): GetEuiccProfileInfoListResult {
        val profiles = runCatching { loadProfiles() }.getOrNull()
            ?: return GetEuiccProfileInfoListResult(RESULT_MUST_DEACTIVATE_SIM, null, false)
        val infos = profiles.map { it.toFrameworkProfileInfo() }.toTypedArray()
        // Embedded (non-removable) eUICC.
        return GetEuiccProfileInfoListResult(RESULT_OK, infos, false)
    }

    override fun onGetEuiccInfo(slotId: Int): FrameworkEuiccInfo {
        val svn = runCatching {
            channelManager.withIsdrChannel {
                EuiccNative.nativeGetEuiccInfo()?.let { json.decodeFromString<EuiccInfo>(it).svn }
            }
        }.getOrNull().orEmpty()
        return FrameworkEuiccInfo(svn)
    }

    override fun onDeleteSubscription(slotId: Int, iccid: String): Int = runProfileOp(iccid) { raw ->
        EuiccNative.nativeDeleteProfile(raw)
    }

    override fun onSwitchToSubscription(slotId: Int, iccid: String?, forceDeactivateSim: Boolean): Int {
        if (iccid == null) return RESULT_FIRST_USER
        return runProfileOp(iccid) { raw -> EuiccNative.nativeEnableProfile(raw) }
    }

    override fun onUpdateSubscriptionNickname(slotId: Int, iccid: String, nickname: String?): Int =
        runProfileOp(iccid) { raw -> EuiccNative.nativeSetNickname(raw, nickname.orEmpty()) }

    override fun onDownloadSubscription(
        slotId: Int,
        subscription: DownloadableSubscription,
        switchAfterDownload: Boolean,
        forceDeactivateSim: Boolean,
    ): Int {
        val code = subscription.encodedActivationCode ?: return RESULT_FIRST_USER
        return runCatching {
            val raw = channelManager.withIsdrChannel { EuiccNative.nativeDownloadProfile(code) }
            val result = json.decodeFromString<DownloadOutcome>(raw)
            if (result.success) RESULT_OK else RESULT_FIRST_USER
        }.getOrDefault(RESULT_FIRST_USER)
    }

    override fun onEraseSubscriptions(slotId: Int): Int = RESULT_FIRST_USER

    override fun onRetainSubscriptionsForFactoryReset(slotId: Int): Int = RESULT_FIRST_USER

    override fun onGetDownloadableSubscriptionMetadata(
        slotId: Int,
        subscription: DownloadableSubscription,
        forceDeactivateSim: Boolean,
    ): GetDownloadableSubscriptionMetadataResult =
        // Resolving metadata requires the ES9+ round trip; return the request as-is.
        GetDownloadableSubscriptionMetadataResult(RESULT_OK, subscription)

    override fun onGetDefaultDownloadableSubscriptionList(
        slotId: Int,
        forceDeactivateSim: Boolean,
    ): GetDefaultDownloadableSubscriptionListResult =
        // No SM-DS discovery configured.
        GetDefaultDownloadableSubscriptionListResult(RESULT_OK, emptyArray())

    // --- helpers ---

    private fun loadProfiles(): List<Profile> =
        channelManager.withIsdrChannel {
            EuiccNative.nativeGetProfiles()?.let { json.decodeFromString<List<Profile>>(it) } ?: emptyList()
        }

    /** Resolves the framework ICCID to our raw ICCID and runs [op] in one channel session. */
    private fun runProfileOp(iccid: String, op: (String) -> Int): Int = runCatching {
        channelManager.withIsdrChannel {
            val raw = EuiccNative.nativeGetProfiles()
                ?.let { json.decodeFromString<List<Profile>>(it) }
                ?.firstOrNull { it.iccidDisplay == iccid || it.iccid == iccid }
                ?.iccid
                ?: return@withIsdrChannel RESULT_FIRST_USER
            if (op(raw) == 0) RESULT_OK else RESULT_FIRST_USER
        }
    }.getOrDefault(RESULT_FIRST_USER)

    private fun Profile.toFrameworkProfileInfo(): EuiccProfileInfo =
        EuiccProfileInfo.Builder(iccidDisplay)
            .setNickname(nickname)
            .setServiceProviderName(serviceProvider)
            .setProfileName(name)
            .setState(
                if (isEnabled) EuiccProfileInfo.PROFILE_STATE_ENABLED
                else EuiccProfileInfo.PROFILE_STATE_DISABLED,
            )
            .setProfileClass(
                when (profileClass) {
                    "test" -> EuiccProfileInfo.PROFILE_CLASS_TESTING
                    "provisioning" -> EuiccProfileInfo.PROFILE_CLASS_PROVISIONING
                    "operational" -> EuiccProfileInfo.PROFILE_CLASS_OPERATIONAL
                    else -> EuiccProfileInfo.PROFILE_CLASS_UNSET
                },
            )
            .build()

    @kotlinx.serialization.Serializable
    private data class DownloadOutcome(val success: Boolean = false, val message: String = "")
}
