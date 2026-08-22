package com.vayunmathur.communicate.data.signal.call

import org.signal.ringrtc.Remote

/**
 * The other end of a 1:1 Signal call, identified by ACI.
 *
 * RingRTC hands this back on every callback, so it is how a signaling message gets addressed to the
 * right person.
 */
data class SignalRemote(val aci: String) : Remote {
    override fun recipientEquals(remote: Remote?): Boolean =
        remote is SignalRemote && remote.aci.equals(aci, ignoreCase = true)
}
