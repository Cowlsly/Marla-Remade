package com.vayunmathur.passwords.util

import android.content.Context
import android.util.Log
import androidx.credentials.provider.CallingAppInfo
import java.security.MessageDigest
import java.security.SecureRandom

object PasskeyUtils {

    private const val TAG = "PasskeyUtils"

    private val secureRandom = SecureRandom()

    fun generateCredentialId(): ByteArray {
        val id = ByteArray(16)
        secureRandom.nextBytes(id)
        return id
    }

    // -- Origin resolution --

    fun getPrivilegedOrigin(callingAppInfo: CallingAppInfo, context: Context): String? {
        return try {
            val allowList = context.assets.open("passkeys_privileged_browsers.json")
                .bufferedReader().use { it.readText() }
            val origin = callingAppInfo.getOrigin(allowList)
            if (!origin.isNullOrEmpty()) {
                Log.d(TAG, "Resolved privileged browser origin: $origin")
                origin.removeSuffix("/")
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "No privileged browser match: ${e.message}")
            null
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun getAndroidOrigin(callingAppInfo: CallingAppInfo): String {
        val fingerprint = callingAppInfo.signingInfo
            .apkContentsSigners
            .firstOrNull()
            ?.toByteArray()
            ?.let { MessageDigest.getInstance("SHA-256").digest(it) }
            ?.toHexString(HexFormat.UpperCase)
            ?: "unknown"
        return "android:apk-key-hash:$fingerprint"
    }

    // -- AuthenticatorData builder --

    fun buildAuthenticatorData(
        rpId: String,
        userPresent: Boolean = true,
        userVerified: Boolean = true,
        backupEligible: Boolean = true,
        backupState: Boolean = true,
        attestedCredentialData: Boolean = false,
        signCount: Int = 0,
    ): ByteArray {
        var flags = 0
        if (userPresent) flags = flags or 0x01
        if (userVerified) flags = flags or 0x04
        if (backupEligible) flags = flags or 0x08
        if (backupState) flags = flags or 0x10
        if (attestedCredentialData) flags = flags or 0x40

        val rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray())
        return rpIdHash +
            byteArrayOf(flags.toByte()) +
            byteArrayOf(
                (signCount shr 24).toByte(),
                (signCount shr 16).toByte(),
                (signCount shr 8).toByte(),
                signCount.toByte()
            )
    }
}
