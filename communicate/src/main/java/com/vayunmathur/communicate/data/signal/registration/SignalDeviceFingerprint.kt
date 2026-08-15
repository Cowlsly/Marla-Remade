package com.vayunmathur.communicate.data.signal.registration

import android.content.Context

/**
 * Signal has no WhatsApp-style fdid/installId/recoveryToken/attestationKey attestation.
 *
 * Kept as a no-op stub so registration code that referenced it remains compilable; Signal's
 * verification-session flow uses optional FCM pushToken + mcc/mnc only (VerificationSessionMetadataRequestBody)
 * and SVR enclave for registrationLock (live-only). Do not send fingerprint headers to chat.signal.org.
 */
class SignalDeviceFingerprint private constructor() {
    companion object {
        fun getOrCreate(context: Context): SignalDeviceFingerprint = SignalDeviceFingerprint()
        fun clear(context: Context) {}
    }
}
