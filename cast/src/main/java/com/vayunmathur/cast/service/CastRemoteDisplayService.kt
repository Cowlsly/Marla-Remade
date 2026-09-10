package com.vayunmathur.cast.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.android.media.remotedisplay.RemoteDisplayProvider
import com.vayunmathur.cast.platform.remotedisplay.MaRemoteDisplayProvider

private const val TAG = "CastRemoteDisplaySvc"

/**
 * The service `RemoteDisplayProviderWatcher` in system_server binds to put MA Cast receivers in
 * Settings' Cast page. See [MaRemoteDisplayProvider] for what it publishes.
 *
 * Bound only by the platform, and only when this app is a privileged app holding
 * `REMOTE_DISPLAY_PROVIDER`; the `BIND_REMOTE_DISPLAY` guard in the manifest is the other half of
 * that check. On an ordinary install nothing ever binds this, which is why it is inert rather than
 * conditionally registered.
 *
 * `com.android.media.remotedisplay` is a shared library rather than part of the base framework, and
 * `<uses-library>` marks it optional so the app still installs on a stock device - so constructing
 * the provider is the point where its absence shows up, and it is caught rather than left to crash
 * a bind that should simply have found nothing.
 */
class CastRemoteDisplayService : Service() {

    private var provider: MaRemoteDisplayProvider? = null

    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "onBind called action=${intent?.action}")
        if (intent?.action != RemoteDisplayProvider.SERVICE_INTERFACE) {
            Log.w(TAG, "onBind: action mismatch (want ${RemoteDisplayProvider.SERVICE_INTERFACE}), returning null")
            return null
        }
        val existing = provider ?: try {
            MaRemoteDisplayProvider(this).also { provider = it }
        } catch (e: Throwable) {
            Log.e(TAG, "onBind: failed to construct MaRemoteDisplayProvider", e)
            return null
        }
        Log.i(TAG, "onBind: returning provider binder=${existing.binder}")
        return existing.binder
    }

    override fun onDestroy() {
        provider?.shutdown()
        provider = null
        super.onDestroy()
    }
}
