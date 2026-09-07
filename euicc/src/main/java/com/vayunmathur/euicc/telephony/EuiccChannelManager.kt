package com.vayunmathur.euicc.telephony

import android.content.Context
import android.telephony.IccOpenLogicalChannelResponse
import android.telephony.TelephonyManager
import android.telephony.UiccCardInfo
import android.telephony.UiccPortInfo
import android.util.Log
import com.vayunmathur.euicc.EuiccNative
import java.lang.reflect.Method

/** Thrown when the eUICC cannot be reached (no eUICC, no privilege, or an APDU error). */
class EuiccException(message: String) : Exception(message)

/**
 * Opens and drives a logical channel to the eUICC's ISD-R applet.
 *
 * SGP.22 local (ES10) operations run over an ISO-7816 logical channel selected onto the ISD-R
 * AID. This wraps the telephony `iccOpenLogicalChannel` / `iccTransmitApduLogicalChannel` /
 * `iccCloseLogicalChannel` APIs. The native core builds the command APDUs and calls back into
 * [EuiccNative.transmitApdu]; [transmit] performs the field-based transmit and handles
 * `61xx` GET RESPONSE / `6Cxx` Le-correction chaining.
 *
 * Everything here addresses the eUICC by **slot and port**, never by subscription. The
 * subscription-based overloads target the default subId, and an LPA's whole job is to run
 * before any subscription exists - on a phone with no SIM and no installed profile the default
 * subId is INVALID_SUBSCRIPTION_ID, so telephony rejects the call outright:
 *
 *     java.lang.IllegalArgumentException: Both subId and slotIndex in request are invalid.
 *
 * (PhoneInterfaceManager.getPhoneFromValidIccLogicalChannelRequest, which falls through to
 * that throw when neither field is set.) The port-based overloads take the `slotIndex` branch
 * instead and need only MODIFY_PHONE_STATE, which this app holds.
 *
 * Those overloads are `@SystemApi`, so they are absent from the public SDK this module builds
 * against and have to be reached reflectively. Telephony still enforces every permission on
 * its side - the reflection buys visibility, not privilege. It additionally restricts ISD-R to
 * the selected LPA, so this only works while this app is the resolved EuiccService.
 */
class EuiccChannelManager(context: Context) {
    private val telephony: TelephonyManager =
        context.applicationContext.getSystemService(TelephonyManager::class.java)
            ?: throw EuiccException("TelephonyManager unavailable")

    /** Physical slot and port of the eUICC, resolved once on first use. */
    private val target: EuiccTarget by lazy { resolveEuiccTarget() }

    private data class EuiccTarget(val slotIndex: Int, val portIndex: Int)

    /**
     * Finds the eUICC's physical slot and port.
     *
     * `getUiccCardsInfo` is public API and needs READ_PRIVILEGED_PHONE_STATE, which this app
     * declares. Unlike the subscription APIs it reports cards that carry no active profile,
     * which is exactly the state an LPA starts from.
     */
    private fun resolveEuiccTarget(): EuiccTarget {
        val cards: List<UiccCardInfo> = try {
            telephony.uiccCardsInfo
        } catch (e: SecurityException) {
            throw EuiccException("cannot read UICC card info - missing privileged permission: $e")
        }

        val euicc = cards.firstOrNull { it.isEuicc }
            ?: throw EuiccException("no eUICC found (${cards.size} UICC card(s) reported)")

        // Port 0 on a card that reports none: a non-MEP eUICC has exactly one implicit port.
        val port: UiccPortInfo? = euicc.ports.firstOrNull()
        val slotIndex = @Suppress("DEPRECATION") euicc.physicalSlotIndex
        if (slotIndex < 0) throw EuiccException("eUICC reports no physical slot index")

        val resolved = EuiccTarget(slotIndex, port?.portIndex ?: 0)
        Log.i(TAG, "eUICC at slot ${resolved.slotIndex} port ${resolved.portIndex}")
        return resolved
    }

    /**
     * Opens the ISD-R channel, installs it as [EuiccNative.activeChannel] for the duration of
     * [block] (so native ops can transmit), and closes it afterward.
     */
    @Synchronized
    fun <T> withIsdrChannel(block: () -> T): T {
        val response = openChannelByPort(target, ISDR_AID)
            ?: throw EuiccException("telephony returned no response opening ISD-R")
        if (response.status != IccOpenLogicalChannelResponse.STATUS_NO_ERROR) {
            throw EuiccException("cannot open ISD-R channel (status=${response.status})")
        }
        val channel = response.channel
        if (channel <= 0) throw EuiccException("invalid ISD-R channel ($channel)")
        return try {
            EuiccNative.activeChannel = { apdu -> transmit(channel, apdu) }
            block()
        } finally {
            EuiccNative.activeChannel = null
            runCatching { closeChannelByPort(target, channel) }
        }
    }

    /**
     * Transmits one command APDU on [channel] using the field-based telephony API, following
     * `61xx`/`6Cxx` chaining, and returns the full response (response data followed by the two
     * status bytes).
     */
    private fun transmit(channel: Int, command: ByteArray): ByteArray {
        require(command.size >= 4) { "APDU too short (${command.size} bytes)" }
        val cla = command[0].toInt() and 0xFF
        val ins = command[1].toInt() and 0xFF
        val p1 = command[2].toInt() and 0xFF
        val p2 = command[3].toInt() and 0xFF
        val p3: Int
        val dataHex: String
        when {
            command.size == 4 -> {
                p3 = 0; dataHex = ""
            }
            command.size == 5 -> {
                // Case 2: the fifth byte is Le.
                p3 = command[4].toInt() and 0xFF; dataHex = ""
            }
            else -> {
                // Case 3/4: fifth byte is Lc; ignore any trailing Le.
                val lc = command[4].toInt() and 0xFF
                val end = (5 + lc).coerceAtMost(command.size)
                p3 = lc
                dataHex = command.copyOfRange(5, end).toHex()
            }
        }

        val out = StringBuilder()
        var response = transmitByPort(target, channel, cla, ins, p1, p2, p3, dataHex)
        while (response.length >= 4) {
            val body = response.substring(0, response.length - 4)
            val sw1 = response.substring(response.length - 4, response.length - 2).toInt(16)
            val sw2 = response.substring(response.length - 2).toInt(16)
            when (sw1) {
                0x61 -> {
                    // More data available: GET RESPONSE for sw2 bytes.
                    out.append(body)
                    response = transmitByPort(target, channel, 0x00, 0xC0, 0x00, 0x00, sw2, "")
                }
                0x6C -> {
                    // Wrong Le: resend the original command with Le = sw2.
                    out.setLength(0)
                    response = transmitByPort(target, channel, cla, ins, p1, p2, sw2, dataHex)
                }
                else -> {
                    out.append(body)
                    out.append("%02X%02X".format(sw1, sw2))
                    return out.toString().hexToBytes()
                }
            }
        }
        return out.toString().hexToBytes()
    }

    private fun openChannelByPort(t: EuiccTarget, aid: String): IccOpenLogicalChannelResponse? =
        invoke(OPEN, t.slotIndex, t.portIndex, aid, 0) as IccOpenLogicalChannelResponse?

    private fun transmitByPort(
        t: EuiccTarget, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String,
    ): String = invoke(
        TRANSMIT, t.slotIndex, t.portIndex, channel, cla, ins, p1, p2, p3, data,
    ) as String? ?: ""

    private fun closeChannelByPort(t: EuiccTarget, channel: Int) {
        invoke(CLOSE, t.slotIndex, t.portIndex, channel)
    }

    /**
     * Unwraps InvocationTargetException so telephony's own error - the SecurityException when
     * this app is not the selected LPA, say - surfaces instead of a reflection wrapper.
     */
    private fun invoke(method: Method, vararg args: Any?): Any? = try {
        method.invoke(telephony, *args)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        throw e.cause ?: EuiccException("${method.name} failed: $e")
    } catch (e: IllegalAccessException) {
        throw EuiccException("${method.name} is not accessible: $e")
    }

    companion object {
        private const val TAG = "EuiccChannelManager"

        /** ISD-R application identifier (SGP.22). */
        const val ISDR_AID = "A0000005591010FFFFFFFF8900000100"

        private fun systemApi(name: String, vararg params: Class<*>): Method = try {
            TelephonyManager::class.java.getMethod(name, *params)
        } catch (e: NoSuchMethodException) {
            throw EuiccException("TelephonyManager.$name missing on this build: $e")
        }

        private val OPEN: Method by lazy {
            systemApi(
                "iccOpenLogicalChannelByPort",
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                String::class.java, Int::class.javaPrimitiveType!!,
            )
        }

        private val TRANSMIT: Method by lazy {
            systemApi(
                "iccTransmitApduLogicalChannelByPort",
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                String::class.java,
            )
        }

        private val CLOSE: Method by lazy {
            systemApi(
                "iccCloseLogicalChannelByPort",
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
        }
    }
}

private fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) sb.append("%02X".format(b.toInt() and 0xFF))
    return sb.toString()
}

private fun String.hexToBytes(): ByteArray {
    if (length % 2 != 0) return ByteArray(0)
    return ByteArray(length / 2) { i ->
        substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
