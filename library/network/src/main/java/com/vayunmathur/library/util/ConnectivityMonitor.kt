package com.vayunmathur.library.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide signal for "do we have validated internet right now?".
 *
 * Backed by [ConnectivityManager.registerDefaultNetworkCallback] and gated on
 * [NetworkCapabilities.NET_CAPABILITY_VALIDATED] so a captive-portal or a link that is up but
 * cannot actually reach the internet reads as offline — that is what online-only features need.
 *
 * Callers must declare `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>`.
 */
object ConnectivityMonitor {
    private val _isOnline = MutableStateFlow(false)

    /** Latest validated-internet state, updated live. */
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    @Volatile private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        // Track which networks are currently validated; online iff any is.
        private val validated = mutableSetOf<Network>()

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            synchronized(validated) {
                if (ok) validated.add(network) else validated.remove(network)
                _isOnline.value = validated.isNotEmpty()
            }
        }

        override fun onLost(network: Network) {
            synchronized(validated) {
                validated.remove(network)
                _isOnline.value = validated.isNotEmpty()
            }
        }

        override fun onUnavailable() {
            synchronized(validated) {
                validated.clear()
                _isOnline.value = false
            }
        }
    }

    /** Idempotent. Safe to call from any app entry point (Activity/Application onCreate). */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            // Seed from the current default network so first reads aren't spuriously offline.
            _isOnline.value = currentlyValidated(cm)
            runCatching { cm.registerDefaultNetworkCallback(callback) }
            started = true
        }
    }

    /** Synchronous snapshot. Starts the monitor lazily if it hasn't been started yet. */
    fun isOnline(context: Context): Boolean {
        if (!started) start(context)
        return _isOnline.value
    }

    private fun currentlyValidated(cm: ConnectivityManager): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
