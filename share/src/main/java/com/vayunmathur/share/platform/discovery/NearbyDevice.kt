package com.vayunmathur.share.platform.discovery

import kotlinx.serialization.Serializable

/**
 * A nearby device discovered via NSD (mDNS _up._tcp) or BLE (GATT 0xFCF1 Nearby Presence).
 *
 * Produced by [NsdDiscoveryManager] + [BleDiscoveryManager] and consumed by
 * [com.vayunmathur.share.platform.ShareViewModel] for the Send flow's nearby-device list.
 *
 * BetoCore model (Everyone / public-identity only):
 *  - TCP endpoint is the resolved host/port from the _up._tcp browse (ServerSocket(0) on WiFi-LAN).
 *  - Human-readable identity (endpointName) comes from the BLE Presence advert (Nearby Presence
 *    V0 unencrypted, DeviceInfo DE), not from any plaintext TXT/endpoint_info on mDNS.
 *  - Each mDNS advertisement carries a random 16-byte ServiceId; the instance name published under
 *    _up._tcp is the hex of that ServiceId.
 */
@Serializable
data class NearbyDevice(
    val endpointId: String,
    val endpointName: String,
    val serviceId: String? = null,
    val serviceName: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val source: DiscoverySource = DiscoverySource.Nsd,
    val extra: String? = null,
)

enum class DiscoverySource { Nsd, Ble, Both }
