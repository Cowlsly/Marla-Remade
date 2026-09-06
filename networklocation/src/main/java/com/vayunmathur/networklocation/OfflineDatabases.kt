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
 * so beacon lookups fall through to gs-loc and the geocoder reports itself unavailable.
 */
object OfflineDatabases {
    const val GEOCODER = "geocoder.geodb"
    const val WIFI = "wifi.wpsdb"
    const val CELL = "cells.wpsdb"

    private const val GEOCODER_URL = "https://data.vayunmathur.com/geocoder/geocoder.geodb"
    private const val WPS_BASE_URL = "https://data.vayunmathur.com/wps/"

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
