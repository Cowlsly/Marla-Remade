package com.vayunmathur.cast.domain

/** What the device is, which is all the icon and the subtitle need. */
enum class CastDeviceKind { Tv, Speaker, Group }

/**
 * A device found on the LAN by browsing `_googlecast._tcp`.
 *
 * Everything here comes out of the mDNS record's TXT attributes, so a device is fully
 * described before anything is connected to. [id] is the receiver's own UUID (`id`) rather
 * than the mDNS instance name, because the instance name changes when a device is renamed
 * and the UUID does not.
 */
data class CastDevice(
    val id: String,
    val friendlyName: String,
    val host: String,
    /**
     * The mDNS instance name. Kept alongside [id] because `onServiceLost` reports only this,
     * so it is the only handle available for removing a device that went away.
     */
    val instanceName: String = id,
    val port: Int = CAST_PORT,
    val model: String? = null,
    /**
     * `rs` - what the device is doing right now, e.g. "YouTube" or "Backdrop". Blank on an
     * idle device, which is why it is only shown when non-empty.
     */
    val statusText: String? = null,
    /** `ca`, a bitmask. See [kind] for the two bits that matter. */
    val capabilities: Int = 0,
) {
    /**
     * Bit 0 (`VIDEO_OUT`) means there is a screen; bit 5 (`MULTIZONE_GROUP`) means this is a
     * speaker group rather than one device. Anything else is a speaker.
     *
     * A group is checked first only for naming: groups do not set `VIDEO_OUT`, so the order
     * is not load-bearing, but reading it in this order makes the intent obvious.
     */
    val kind: CastDeviceKind
        get() = when {
            capabilities and CAPABILITY_MULTIZONE_GROUP != 0 -> CastDeviceKind.Group
            capabilities and CAPABILITY_VIDEO_OUT != 0 -> CastDeviceKind.Tv
            else -> CastDeviceKind.Speaker
        }

    private companion object {
        const val CAPABILITY_VIDEO_OUT = 1 shl 0
        const val CAPABILITY_MULTIZONE_GROUP = 1 shl 5
    }
}
