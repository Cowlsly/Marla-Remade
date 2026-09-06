package com.vayunmathur.networklocation

import android.app.Service
import android.content.Intent
import android.location.Address
import android.location.GeocoderParams
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.android.location.provider.GeocodeProvider
import java.util.Locale

/**
 * System geocoder backed entirely by the offline planet DB — no network. Bound by the
 * framework via the `com.android.location.service.GeocodeProvider` action; the app must be the
 * configured geocode provider (config_geocoderProviderPackageName) and hold INSTALL_LOCATION_PROVIDER.
 *
 * The search itself runs in native Rust ([GeocoderNative]) over `geocoder.geodb`, which is
 * downloaded to device-protected storage rather than bundled (see [OfflineDatabases]). The
 * provider contract: return null on success (results appended to `addrs`) or an error string.
 * Until the download lands there is no online fallback, so every request reports unavailable.
 */
class GeocodeService : Service() {
    private var pfd: ParcelFileDescriptor? = null
    private var handle: Long = 0L

    private val provider: GeocodeProvider by lazy {
        object : GeocodeProvider() {
            override fun onGetFromLocation(
                latitude: Double,
                longitude: Double,
                maxResults: Int,
                params: GeocoderParams,
                addrs: MutableList<Address>,
            ): String? {
                if (!ensureOpen()) return "geocoder database unavailable"
                val flat = GeocoderNative.reverse(handle, latitude, longitude) ?: return null
                if (flat.size >= GeocoderNative.FIELDS_PER_ADDRESS) addrs.add(flat.toAddress(0, params.locale))
                return null
            }

            override fun onGetFromLocationName(
                locationName: String,
                lowerLeftLatitude: Double,
                lowerLeftLongitude: Double,
                upperRightLatitude: Double,
                upperRightLongitude: Double,
                maxResults: Int,
                params: GeocoderParams,
                addrs: MutableList<Address>,
            ): String? {
                if (!ensureOpen()) return "geocoder database unavailable"
                val parts = locationName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size < 4) return null
                val country = parts[parts.size - 1]
                val state = parts[parts.size - 2]
                val city = parts[parts.size - 3]
                val street = parts[parts.size - 4]
                val flat = GeocoderNative.forward(
                    handle, country, state, city, street, maxResults.coerceIn(1, 50),
                ) ?: return null
                val stride = GeocoderNative.FIELDS_PER_ADDRESS
                var base = 0
                while (base + stride <= flat.size && addrs.size < maxResults) {
                    addrs.add(flat.toAddress(base, params.locale))
                    base += stride
                }
                return null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        openDatabase()
    }

    /**
     * Open the database on first use after it has arrived, and report whether it is usable.
     *
     * The service is bound by the framework for the life of the boot, so it is normally
     * created long before the multi-gigabyte download finishes. Nothing signals completion,
     * so retry here: a single stat on the miss path is far cheaper than a geocode, and it
     * means the geocoder starts answering without waiting for a reboot.
     */
    @Synchronized
    private fun ensureOpen(): Boolean {
        if (handle == 0L && OfflineDatabases.isPresent(this, OfflineDatabases.GEOCODER)) {
            openDatabase()
        }
        return handle != 0L
    }

    /**
     * (Re)open the geocoder DB. Safe to call again once a download completes: the previous
     * handle is released first, and a still-absent file just leaves the handle at 0.
     */
    @Synchronized
    fun openDatabase() {
        closeDatabase()
        if (!GeocoderNative.available) return
        val fd = OfflineDatabases.openReadOnly(this, OfflineDatabases.GEOCODER) ?: return
        pfd = fd
        handle = GeocoderNative.open(fd.fd, 0L, fd.statSize)
    }

    @Synchronized
    private fun closeDatabase() {
        if (handle != 0L) {
            GeocoderNative.close(handle)
            handle = 0L
        }
        pfd?.close()
        pfd = null
    }

    override fun onBind(intent: Intent?): IBinder? = provider.binder

    override fun onDestroy() {
        closeDatabase()
        super.onDestroy()
    }

    /** A flat result slice `[lat, lon, house, street, city, state, country, postcode]` at [base]. */
    private fun Array<String>.toAddress(base: Int, locale: Locale): Address {
        val a = Address(locale)
        a.latitude = this[base].toDoubleOrNull() ?: 0.0
        a.longitude = this[base + 1].toDoubleOrNull() ?: 0.0
        val house = this[base + 2]
        val street = this[base + 3]
        val city = this[base + 4]
        val state = this[base + 5]
        val country = this[base + 6]
        val postcode = this[base + 7]
        val line = listOf(house, street).filter { it.isNotEmpty() }.joinToString(" ")
        if (line.isNotEmpty()) a.setAddressLine(0, line)
        if (house.isNotEmpty()) a.featureName = house
        if (street.isNotEmpty()) a.thoroughfare = street
        if (city.isNotEmpty()) a.locality = city
        if (state.isNotEmpty()) a.adminArea = state
        if (postcode.isNotEmpty()) a.postalCode = postcode
        if (country.isNotEmpty()) a.countryCode = country
        return a
    }
}
