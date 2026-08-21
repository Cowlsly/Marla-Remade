package com.vayunmathur.web.platform

import android.Manifest
import android.os.Build

/** Runtime permissions for the Web module (mirrors AndroidManifest.xml). */
object WebPermissions {
    /**
     * Local Network Protections (Android 16+). Without it the OS refuses every socket to a
     * private-range address, and unlike camera or geolocation WebView has no callback for it:
     * the connection just fails.
     *
     * `minSdk` is 31 and lint runs with `abortOnError = false`, so this SDK gate is the only
     * thing standing between us and a silent `NewApi` ship. The manifest entry itself stays
     * unguarded, per repo convention.
     */
    val LOCAL_NETWORK =
        if (Build.VERSION.SDK_INT >= 36) Manifest.permission.ACCESS_LOCAL_NETWORK else null
}
