package com.vayunmathur.cast.tv.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.vayunmathur.cast.protocol.DecoderLimits
import com.vayunmathur.cast.protocol.MACAST_SERVICE_TYPE
import com.vayunmathur.cast.protocol.PROTOCOL_VERSION

private const val TAG = "ReceiverAdvertiser"

/**
 * TXT attributes on the registration.
 *
 * The phone needs a readable name before it connects to anything, and it needs to know this is a
 * version it can talk to - so both go in the record rather than in `TV_IDENTITY`. The decoder limits
 * are *not* here: they are big, they change nothing about whether the phone should offer the device,
 * and putting them in TXT would mean trusting an unauthenticated mDNS record for the frame size.
 */
private const val TXT_FRIENDLY_NAME = "fn"
private const val TXT_PROTOCOL_VERSION = "pv"
private const val TXT_ID = "id"

/**
 * Announces this TV as `_macast._tcp` on the LAN.
 *
 * The registration half of `:share`'s `NsdDiscoveryManager.advertise`, kept to the same shape
 * including its Android 16 trap: with Local Network Protections, `registerService` throws
 * `SecurityException` unless `ACCESS_LOCAL_NETWORK` has been granted, and a receiver that failed to
 * advertise is indistinguishable from one nobody has tried to connect to. [localNetworkBlocked]
 * exists so the idle screen can say which of the two happened.
 */
class ReceiverAdvertiser(context: Context) {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var listener: NsdManager.RegistrationListener? = null

    /** True when the platform refused the registration, not when nothing has connected. */
    var localNetworkBlocked: Boolean = false
        private set

    /** The instance name the platform settled on, which it may rename on a conflict. */
    var registeredName: String? = null
        private set

    /**
     * Advertise [friendlyName] on [port]. Safe to call repeatedly; re-advertising replaces the prior
     * registration rather than stacking a second one.
     *
     * [limits] is accepted but deliberately not published - see the TXT note above. It is here so
     * the caller has one place that knows what the receiver is offering.
     */
    fun advertise(friendlyName: String, deviceId: String, port: Int, limits: DecoderLimits) {
        val manager = nsdManager ?: run {
            Log.w(TAG, "NsdManager unavailable - this TV cannot announce itself")
            return
        }
        unadvertise()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = friendlyName
            serviceType = MACAST_SERVICE_TYPE
            setPort(port)
            setAttribute(TXT_FRIENDLY_NAME, friendlyName)
            setAttribute(TXT_PROTOCOL_VERSION, PROTOCOL_VERSION.toString())
            setAttribute(TXT_ID, deviceId)
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
                localNetworkBlocked = false
                Log.i(
                    TAG,
                    "advertised $MACAST_SERVICE_TYPE as ${info.serviceName} on port $port " +
                        "(up to ${limits.maxWidth}x${limits.maxHeight} @ ${limits.maxFrameRate}fps)",
                )
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed: $errorCode")
                registeredName = null
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "unregistration failed: $errorCode")
            }
        }
        listener = registration
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registration)
        } catch (e: SecurityException) {
            // Android 16+ Local Network Protections. Nothing about the record is wrong; the OS
            // blocks mDNS outright until ACCESS_LOCAL_NETWORK is granted, and until then this TV is
            // simply not on anybody's list.
            Log.e(TAG, "mDNS blocked - ACCESS_LOCAL_NETWORK not granted, so this TV is invisible", e)
            localNetworkBlocked = true
            listener = null
        } catch (e: Exception) {
            Log.w(TAG, "registerService threw", e)
            listener = null
        }
    }

    fun unadvertise() {
        val manager = nsdManager ?: return
        val active = listener ?: return
        try {
            manager.unregisterService(active)
        } catch (_: Exception) {
        }
        listener = null
        registeredName = null
    }
}
