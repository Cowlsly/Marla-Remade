package com.vayunmathur.vpn.platform

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.Flow

/**
 * Packages the user has chosen to keep off the tunnel ("split tunnelling").
 *
 * Applied at tunnel setup via `VpnService.Builder.addDisallowedApplication`, which is
 * all-or-nothing per package and cannot be combined with `addAllowedApplication`.
 *
 * Caveat worth knowing about: if the user turns on **Block connections without VPN**
 * (lockdown) in Android's system VPN settings, the platform drops any traffic that isn't
 * inside the tunnel. Bypassed apps are by definition outside it, so they lose network
 * access entirely rather than falling back to the normal connection. There is no public
 * API to read the lockdown flag, so the UI warns rather than detects — see BypassListPage.
 */
object BypassList {
    /** DataStore key holding the set of bypassed package names. */
    const val KEY = "bypass_packages"

    fun flow(context: Context): Flow<Set<String>> =
        DataStoreUtils.getInstance(context).stringSetFlow(KEY)

    /** Await-based read: the tunnel service can start before DataStore has hydrated. */
    suspend fun load(context: Context): Set<String> =
        DataStoreUtils.getInstance(context).getStringSetAwait(KEY)

    fun setBypassed(context: Context, packageName: String, bypassed: Boolean) {
        val ds = DataStoreUtils.getInstance(context)
        if (bypassed) ds.addStringToSet(KEY, packageName) else ds.removeStringFromSet(KEY, packageName)
    }
}
