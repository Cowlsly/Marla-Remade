package com.vayunmathur.share.platform

import android.Manifest
import android.os.Build

/**
 * Runtime permissions for the Share module (mirrors AndroidManifest.xml).
 *
 * Grouped so the UI can request just what it needs per flow and so
 * [ShareViewModel.requestPermissionsIfNeeded] can reason about grants.
 */
object SharePermissions {
    /** Needed to advertise/scan for discovery on Android 12+ (and for Quick Share visibility). */
    val BLE_ADVERTISE = Manifest.permission.BLUETOOTH_ADVERTISE
    val BLE_SCAN = Manifest.permission.BLUETOOTH_SCAN
    val BLE_CONNECT = Manifest.permission.BLUETOOTH_CONNECT

    /** Wi-Fi Direct / Hotspot transport (Android 13+ shows a dedicated dialog). */
    val NEARBY_WIFI = Manifest.permission.NEARBY_WIFI_DEVICES

    /** Foreground-service notification (Android 13+). */
    val POST_NOTIFICATIONS = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null

    /** BLE scan may still imply location on < S without neverForLocation flag. */
    val FINE_LOCATION = Manifest.permission.ACCESS_FINE_LOCATION
    val COARSE_LOCATION = Manifest.permission.ACCESS_COARSE_LOCATION

    fun sendFlowPermissions(): Array<String> = buildList {
        add(BLE_SCAN)
        add(BLE_CONNECT)
        add(NEARBY_WIFI)
        add(FINE_LOCATION)
        add(COARSE_LOCATION)
        POST_NOTIFICATIONS?.let { add(it) }
    }.toTypedArray()

    fun receiveFlowPermissions(): Array<String> = buildList {
        add(BLE_ADVERTISE)
        add(BLE_SCAN)
        add(BLE_CONNECT)
        add(NEARBY_WIFI)
        add(FINE_LOCATION)
        add(COARSE_LOCATION)
        POST_NOTIFICATIONS?.let { add(it) }
    }.toTypedArray()

    fun allSharePermissions(): Array<String> = buildList {
        add(BLE_ADVERTISE)
        add(BLE_SCAN)
        add(BLE_CONNECT)
        add(NEARBY_WIFI)
        add(FINE_LOCATION)
        add(COARSE_LOCATION)
        POST_NOTIFICATIONS?.let { add(it) }
    }.toTypedArray()
}
