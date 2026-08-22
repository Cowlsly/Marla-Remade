package com.vayunmathur.communicate.data.signal

import android.util.Log
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ServiceId
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator

/**
 * Safety numbers — the 60-digit fingerprint over both parties' identity keys that users compare out of
 * band to detect a substituted key.
 *
 * Parameters match the official client so the numbers agree with what the other side sees:
 * `NumericFingerprintGenerator(5200)`, version 2, and each side identified by its ACI's 16 raw UUID
 * bytes. Any deviation produces a number that looks plausible but never matches.
 */
object SignalSafetyNumber {
    private const val TAG = "SignalSafetyNumber"
    private const val ITERATIONS = 5200
    private const val VERSION = 2

    /**
     * The displayable safety number for a pair of identities, or null if either ACI or key is unusable.
     * The result is symmetric: both sides compute the same string.
     *
     * [warn] is injectable so this stays testable off-device.
     */
    fun compute(
        localAci: String,
        localIdentityKey: ByteArray,
        remoteAci: String,
        remoteIdentityKey: ByteArray,
        warn: (String) -> Unit = { Log.w(TAG, it) },
    ): String? = try {
        val generator = NumericFingerprintGenerator(ITERATIONS)
        val fingerprint = generator.createFor(
            VERSION,
            aciBytes(localAci),
            IdentityKey(localIdentityKey),
            aciBytes(remoteAci),
            IdentityKey(remoteIdentityKey),
        )
        fingerprint.displayableFingerprint.displayText
    } catch (t: Throwable) {
        warn("could not compute a safety number: ${t.message}")
        null
    }

    /** Group the 60 digits into 12 blocks of 5, the way both apps display them. */
    fun format(displayText: String): String =
        displayText.chunked(5).joinToString(" ")

    private fun aciBytes(aci: String): ByteArray =
        ServiceId.Aci.parseFromString(aci).toServiceIdBinary()
}
