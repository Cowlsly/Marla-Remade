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
 * How many instance names to try before giving up.
 *
 * Only reachable on platforms that actually report a failed registration; see [instanceLabel] for
 * the conflict that Android 14 swallows.
 */
private const val MAX_NAME_ATTEMPTS = 5

/**
 * Distinguishes our mDNS instance name from the device name.
 *
 * Android 14's `MdnsRecordRepository.addService` rejects a registration whose *instance name* is
 * already taken **without comparing the service type**, and `MdnsAdvertiser` then swallows the
 * `NameConflictException` - so `onRegistrationFailed` is never called and the app cannot tell it is
 * invisible. Every Google TV box hits this: the system's Android TV Remote service registers
 * `_androidtvremote2._tcp` under the device name, which is exactly what `defaultName()` returns from
 * `Build.MODEL`. Suffixing the instance name sidesteps it, and costs nothing visible because the
 * phone renders TXT `fn` (see `CastDiscoveryManager.toCastDevice`) and only falls back to the
 * instance name when that attribute is missing.
 */
private const val INSTANCE_TAG = "MA Cast"

/** The mDNS instance name for [friendlyName] on the [attempt]th try. */
private fun instanceLabel(friendlyName: String, attempt: Int): String =
    if (attempt <= 1) "$friendlyName ($INSTANCE_TAG)" else "$friendlyName ($INSTANCE_TAG $attempt)"

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

    /**
     * Bumped by [unadvertise] so a conflict retry scheduled by a registration we have since torn
     * down cannot resurrect it.
     */
    private var generation = 0

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
        register(manager, friendlyName, deviceId, port, limits, attempt = 1)
    }

    /**
     * Register the [attempt]th candidate instance name, retrying under a numbered variant when the
     * platform reports a conflict. Only the mDNS instance name varies - TXT `fn` always carries
     * [friendlyName], which is what the phone displays, so renaming is invisible to the user.
     */
    private fun register(
        manager: NsdManager,
        friendlyName: String,
        deviceId: String,
        port: Int,
        limits: DecoderLimits,
        attempt: Int,
    ) {
        val issuedAt = generation
        val instanceName = instanceLabel(friendlyName, attempt)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = instanceName
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
                        "(decoding ${limits.codecs.joinToString { it.label }.ifEmpty { "nothing" }})",
                )
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registeredName = null
                if (issuedAt != generation) return
                if (attempt >= MAX_NAME_ATTEMPTS) {
                    Log.w(
                        TAG,
                        "registration failed: $errorCode - gave up after $attempt names, " +
                            "so this TV is invisible",
                    )
                    listener = null
                    return
                }
                val next = instanceLabel(friendlyName, attempt + 1)
                Log.w(TAG, "registration failed: $errorCode for '$instanceName', retrying as '$next'")
                register(manager, friendlyName, deviceId, port, limits, attempt + 1)
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
        generation++
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
