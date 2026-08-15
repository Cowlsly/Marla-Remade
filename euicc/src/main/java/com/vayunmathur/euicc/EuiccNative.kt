// PACKAGE STRUCTURE EXCEPTION (JNI): FQN frozen for native RegisterNatives/symbol mangling
package com.vayunmathur.euicc

/**
 * JNI entry points into the native SGP.22 core (libeuicc.so).
 *
 * The Rust side owns the ASN.1 + ES10 protocol logic and drives the eUICC by
 * calling back into [transmitApdu], which forwards each command APDU over the
 * telephony logical channel currently opened by [com.vayunmathur.euicc.telephony.EuiccChannelManager].
 * Only marshalling lives here; see euicc/src/main/rust/.
 *
 * The native `nativeXxx` operations are only valid while a channel is open, i.e.
 * inside `EuiccChannelManager.withIsdrChannel { ... }`, which installs
 * [activeChannel] for the duration of the block.
 */
object EuiccNative {
    init {
        System.loadLibrary("euicc")
    }

    /**
     * The transmit function for the currently open ISD-R logical channel, or
     * null when no channel is open. Set by `EuiccChannelManager.withIsdrChannel`.
     */
    @Volatile
    @JvmStatic
    var activeChannel: ((ByteArray) -> ByteArray)? = null

    /**
     * Called by the native core to send one command APDU to the eUICC. Returns
     * the response bytes (response data followed by the two status bytes).
     */
    @JvmStatic
    fun transmitApdu(command: ByteArray): ByteArray =
        (activeChannel ?: error("no active eUICC channel")).invoke(command)

    /** Returns the native core's version string. */
    external fun nativeVersion(): String

    /** Returns the 32-hex-digit EID, or null on error. */
    external fun nativeGetEid(): String?

    /** Returns the EUICCInfo1 subset as a JSON string, or null on error. */
    external fun nativeGetEuiccInfo(): String?

    /** Returns the installed profiles as a JSON array string, or null on error. */
    external fun nativeGetProfiles(): String?

    /** Enables the profile with [iccid] (raw hex). 0 = success, else error code / -1. */
    external fun nativeEnableProfile(iccid: String): Int

    /** Disables the profile with [iccid] (raw hex). 0 = success, else error code / -1. */
    external fun nativeDisableProfile(iccid: String): Int

    /** Deletes the profile with [iccid] (raw hex). 0 = success, else error code / -1. */
    external fun nativeDeleteProfile(iccid: String): Int

    /** Sets the nickname of the profile with [iccid] (raw hex). 0 = success, else error / -1. */
    external fun nativeSetNickname(iccid: String, nickname: String): Int

    /** Returns pending notifications as a JSON array string, or null on error. */
    external fun nativeListNotifications(): String?

    /** Removes the notification with sequence number [seq]. 0 = success, else error / -1. */
    external fun nativeRemoveNotification(seq: Int): Int

    /**
     * Runs the full SGP.22 download for an activation code and returns a JSON
     * `{"success":Boolean,"message":String}` string. Must be called while the
     * ISD-R channel is open (inside `withIsdrChannel`).
     */
    external fun nativeDownloadProfile(activationCode: String): String
}
