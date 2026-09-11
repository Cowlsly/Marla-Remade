package com.vayunmathur.networklocation

/**
 * Plain domain types shared across the network-location pipeline (scan → offline
 * store lookup → cache → position estimate). Kept free of Android types so the
 * pieces compose cleanly.
 */

/**
 * Cellular access technology. Part of a cell's identity, not decoration: an LTE ECI and a GSM
 * CID are different numbers in the same range, so without this two unrelated towers in the same
 * network and area code alias onto one key.
 *
 * The wire values are baked into the `cells-v2.wpsdb` key and MUST match `wps_harvest`'s
 * `RadioType`. Append only.
 */
enum class RadioType(val wire: Int) {
    GSM(1),
    UMTS(2),
    LTE(3),
    NR(4),
}

/** A radio beacon we can resolve to a location: a WiFi access point or a cell tower. */
sealed interface BeaconId {
    data class Wifi(val bssid: String) : BeaconId

    /**
     * [cellId] is a [Long] because 5G's NCI is 36 bits; truncating it to an [Int] aliases
     * distinct gNB cells onto one another. [tacOrLac] holds a 5G 24-bit TAC as well as the
     * 16-bit LTE TAC and GSM/UMTS LAC.
     */
    data class Cell(
        val mcc: Int,
        val mnc: Int,
        val radio: RadioType,
        val cellId: Long,
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
