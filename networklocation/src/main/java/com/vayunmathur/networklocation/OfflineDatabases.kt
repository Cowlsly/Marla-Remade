package com.vayunmathur.networklocation

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException

/**
 * The offline databases the provider reads, and where they live on disk.
 *
 * These used to ship as APK assets, but at ~3.5 GB they dominated the MAOS system image and
 * pushed the factory install zip past fastboot's 4 GiB zip limit. They are downloaded on
 * request instead, to device-protected storage so the always-on provider can reach them
 * before the user unlocks after a reboot.
 *
 * Absence is a supported state, not an error: every reader treats a zero handle as "no data",
 * so beacon lookups miss and the geocoder reports itself unavailable.
 *
 * ## Filenames carry the format version
 *
 * A new database format gets a new filename rather than a version field the readers negotiate.
 * The files are a cache of a published artifact, not user data, so there is nothing to migrate
 * — an old file is simply a name nobody asks for, and [pruneStale] reclaims its multiple
 * gigabytes. This keeps exactly one format alive in each reader.
 */
object OfflineDatabases {
    const val GEOCODER = "geocoder-v3.geodb"
    const val WIFI = "wifi-v2.wpsdb"
    const val CELL = "cells-v2.wpsdb"

    /** Names shipped by earlier versions of the app, deleted on sight to reclaim space. */
    private val SUPERSEDED = listOf("geocoder.geodb", "wifi.wpsdb", "cells.wpsdb")

    private const val GEOCODER_URL = "https://data.vayunmathur.com/geocoder/$GEOCODER"
    private const val WPS_BASE_URL = "https://data.vayunmathur.com/wps/"

    /**
     * Expected SHA-256 of each published database, verified by `:library:downloadservice`
     * after the transfer and refetched on mismatch.
     *
     * Presence alone is too weak a check: a store is several gigabytes fetched in resumable
     * chunks, and a truncated file is non-empty, so without a checksum it reads as installed
     * and then fails its magic check on every open, forever.
     *
     * A null hash means the download is accepted unverified. The beacon stores stay null until
     * a crawl has been run and published.
     */
    fun sha256For(name: String): String? = when (name) {
        GEOCODER -> "97284c7a1f7d07db4c448ce62c6f3b86cbf4d38293e6c4d4d8157a04394e9593"
        else -> null
    }

    /** Download source for [name], matching the mirror the DBs are published to. */
    fun urlFor(name: String): String = when (name) {
        GEOCODER -> GEOCODER_URL
        else -> WPS_BASE_URL + name
    }

    /**
     * Device-protected storage, which is readable before first unlock. The provider is bound by
     * the framework and runs unattended, so it cannot wait for the user to unlock to find its
     * data. Note this only pays off once the services are also directBootAware.
     */
    fun dir(context: Context): File = context.createDeviceProtectedStorageContext().filesDir

    fun file(context: Context, name: String): File = File(dir(context), name)

    fun isPresent(context: Context, name: String): Boolean = file(context, name).length() > 0

    fun allPresent(context: Context): Boolean =
        listOf(GEOCODER, WIFI, CELL).all { isPresent(context, it) }

    /** Delete databases in a format no reader understands any more. Safe to call repeatedly. */
    fun pruneStale(context: Context) {
        for (name in SUPERSEDED) {
            val f = file(context, name)
            if (f.isFile) f.delete()
        }
    }

    /**
     * Open [name] for the native readers, which take an fd plus a base offset. Assets lived at
     * an offset inside the APK; a standalone file starts at 0. Returns null when the database
     * has not been downloaded yet.
     */
    fun openReadOnly(context: Context, name: String): ParcelFileDescriptor? {
        val f = file(context, name)
        if (!f.isFile || f.length() == 0L) return null
        return try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: IOException) {
            null
        }
    }
}
