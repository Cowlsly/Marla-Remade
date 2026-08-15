package com.vayunmathur.weather.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wraps Android's framework [LocationManager] to provide a single-shot
 * "what's the device's current rough location?" suspending call. Mirrors the
 * pattern used by [com.vayunmathur.maps.util.FrameworkLocationManager] —
 * intentionally avoids the play-services-location dependency since no other
 * module in this repo brings it in.
 *
 * Strategy:
 *   1. Try the freshest of `getLastKnownLocation` from each provider.
 *   2. If nothing recent is available, ask GPS/Network for ONE update each
 *      and return whichever arrives first.
 *
 * Returns `null` if location permission isn't granted or if both providers
 * fail to deliver within the caller's timeout. Callers should wrap in
 * `withTimeoutOrNull(...)` to bound the wait.
 */
object LocationProvider {

    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Best-effort current location. Returns the last known fix immediately if
     * one is available; otherwise requests a single update.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.isProviderEnabled(it) }
        if (providers.isEmpty()) return null

        // 1. Quick path: cached last known location.
        providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { return it }

        // 2. Slow path: register one-shot listeners on every enabled provider
        //    and resume with the first fix.
        return suspendCancellableCoroutine { cont ->
            val listeners = mutableListOf<android.location.LocationListener>()
            var resumed = false
            providers.forEach { provider ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!resumed) {
                            resumed = true
                            listeners.forEach { runCatching { lm.removeUpdates(it) } }
                            cont.resume(location)
                        }
                    }
                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                    @Deprecated("Removed in API 29 but kept for older devices")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                }
                listeners += listener
                runCatching {
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, android.os.Looper.getMainLooper())
                }
            }
            cont.invokeOnCancellation {
                listeners.forEach { runCatching { lm.removeUpdates(it) } }
            }
        }
    }
}
