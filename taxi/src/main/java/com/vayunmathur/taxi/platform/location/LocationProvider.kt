package com.vayunmathur.taxi.platform.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Location and geocoding on the platform APIs only — the target device is degoogled, so
 * `play-services-location` and the Places SDK are both unavailable.
 */
object LocationProvider {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): LatLng? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        // A recent cached fix is good enough to centre a map and anchor a pickup, and avoids
        // making the user wait on a GPS lock before they can see any prices.
        providers.firstNotNullOfOrNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }?.let { return LatLng(it.latitude, it.longitude) }

        val provider = providers.firstOrNull() ?: return null
        // Bounded: a live request that never gets a fix (indoors, no signal) must not suspend
        // the caller indefinitely, and the listener has to come off either way.
        return withTimeoutOrNull(LIVE_FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        manager.removeUpdates(this)
                        if (cont.isActive) cont.resume(LatLng(location.latitude, location.longitude))
                    }
                }
                runCatching {
                    manager.requestSingleUpdateCompat(provider, listener, context)
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
            }
        }
    }

    private const val LIVE_FIX_TIMEOUT_MS = 15_000L

    @SuppressLint("MissingPermission")
    private fun LocationManager.requestSingleUpdateCompat(
        provider: String,
        listener: android.location.LocationListener,
        context: Context,
    ) {
        requestLocationUpdates(provider, 0L, 0f, listener, context.mainLooper)
    }

    /**
     * Forward-geocodes a typed address. Returns an empty list when no geocoder backend answers.
     *
     * Deliberately does not gate on [Geocoder.isPresent] — it reports false on some degoogled
     * builds that nonetheless resolve fine, so we attempt the lookup and degrade on failure,
     * matching findfamily's `fetchAddress`.
     */
    suspend fun search(context: Context, query: String): List<Place> {
        if (query.isBlank()) return emptyList()
        val geocoder = Geocoder(context, Locale.getDefault())
        return withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    // onError must be supplied: its default implementation is empty, so a
                    // geocoder failure would otherwise leave this coroutine suspended forever.
                    geocoder.getFromLocationName(
                        query,
                        5,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                if (cont.isActive) cont.resume(addresses.map { it.toPlace() })
                            }

                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(emptyList())
                            }
                        },
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocationName(query, 5) }
                    .getOrNull()
                    ?.map { it.toPlace() }
                    ?: emptyList()
            }
        }
    }

    suspend fun describe(context: Context, location: LatLng): Place {
        val fallback = Place("Current location", null, location)
        val geocoder = Geocoder(context, Locale.getDefault())
        return withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(
                        location.latitude,
                        location.longitude,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                if (cont.isActive) {
                                    cont.resume(addresses.firstOrNull()?.toPlace() ?: fallback)
                                }
                            }

                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(fallback)
                            }
                        },
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching {
                    geocoder.getFromLocation(location.latitude, location.longitude, 1)
                }.getOrNull()?.firstOrNull()?.toPlace() ?: fallback
            }
        }
    }
}

private fun android.location.Address.toPlace(): Place {
    val line = (0..maxAddressLineIndex).mapNotNull { getAddressLine(it) }.firstOrNull()
    // Prefer a street address with the house number (subThoroughfare) so a pickup reads
    // "123 Main St" rather than just "Main St"; fall back to a POI/feature name when there
    // is no house number (typical of searched landmarks).
    val houseAndStreet = subThoroughfare?.let { house ->
        thoroughfare?.let { street -> "$house $street" }
    }
    return Place(
        name = houseAndStreet ?: featureName ?: thoroughfare ?: line ?: "Dropped pin",
        address = line,
        location = LatLng(latitude, longitude),
    )
}
