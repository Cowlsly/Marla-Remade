package com.vayunmathur.networklocation

/**
 * Plain domain types shared across the network-location pipeline (scan → offline
 * store lookup → cache → position estimate). Kept free of Android types so the
 * pieces compose cleanly.
 */

/** A radio beacon we can resolve to a location: a WiFi access point or a cell tower. */
sealed interface BeaconId {
    data class Wifi(val bssid: String) : BeaconId

    data class Cell(
        val mcc: Int,
        val mnc: Int,
        val cellId: Int,
        val tacOrLac: Int,
    ) : BeaconId
}

/**
 * A beacon whose coordinates are known (from the offline stores or the local cache).
 * [accuracyMeters] is the beacon's own horizontal accuracy radius.
 */
data class BeaconFix(
    val id: BeaconId,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
)

/** An estimated device position: the output of the Rust weighted-centroid solver. */
data class DevicePosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
)
