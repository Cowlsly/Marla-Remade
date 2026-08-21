package com.vayunmathur.cast.domain

/**
 * A TV found on the LAN by browsing `_macast._tcp`.
 *
 * Everything here comes out of the mDNS TXT attributes, so a device is fully described before anything
 * is connected to. [id] is the receiver's own stable id rather than the mDNS instance name, because the
 * instance name changes when the TV is renamed and the id does not.
 *
 * **No capability bitmask, and no device kind.** Those were Cast's, and they decided which of four
 * receiver app ids to launch - a correctness boundary that no longer exists. Every device that answers
 * `_macast._tcp` is running our receiver, so every one of them takes audio and video.
 */
data class CastDevice(
    val id: String,
    val friendlyName: String,
    val host: String,
    /** The port the TV's control socket is listening on. Ephemeral, so it is never assumed. */
    val port: Int,
    /**
     * The mDNS instance name. Kept alongside [id] because `onServiceLost` reports only this, so it is
     * the only handle available for removing a device that went away.
     */
    val instanceName: String = id,
    /** The protocol version the TV advertised, so an incompatible one can be shown rather than tried. */
    val protocolVersion: Int = 0,
)
