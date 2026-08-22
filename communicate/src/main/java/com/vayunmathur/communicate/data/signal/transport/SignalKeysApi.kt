package com.vayunmathur.communicate.data.signal.transport

import android.util.Log
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.net.ssl.SSLSocketFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Pre-key fetching (`GET /v2/keys/...`), which is what lets a first message to a new recipient
 * establish a session.
 *
 * Response shape per the official client's `PreKeyResponse`/`PreKeyResponseItem`: a top-level
 * `identityKey` plus a `devices` array of `{deviceId, registrationId, signedPreKey, preKey,
 * pqPreKey}`. Note the Kyber key is named **`pqPreKey`** on the wire, and keys are base64 without
 * padding.
 */
@OptIn(ExperimentalEncodingApi::class)
object SignalKeysApi {
    private const val TAG = "SignalKeysApi"

    private val json = Json { ignoreUnknownKeys = true }

    class UnregisteredUserException(aci: String) : Exception("$aci is not registered")

    data class DeviceBundle(val deviceId: Int, val bundle: SignalE2E.ParsedPreKeyBundle)

    /**
     * Fetch pre-key bundles for [aci]. Requesting device 1 fetches `*` instead, which returns every
     * device the recipient has — device discovery is a side effect of the fetch.
     *
     * @throws UnregisteredUserException on 404.
     */
    suspend fun fetchPreKeys(
        aci: String,
        deviceId: Int,
        authHeader: String,
        sslSocketFactory: SSLSocketFactory?,
    ): List<DeviceBundle> {
        val specifier = if (deviceId == 1) "*" else deviceId.toString()
        val resp = NetworkClient.execute(
            "https://chat.signal.org/v2/keys/$aci/$specifier",
            method = "GET",
            headers = mapOf("Authorization" to "Basic $authHeader"),
            sslSocketFactory = sslSocketFactory,
        )
        if (resp.status == 404) throw UnregisteredUserException(aci)
        if (!resp.isSuccess) {
            Log.w(TAG, "prekey fetch for $aci failed: ${resp.status} ${resp.statusMessage}")
            return emptyList()
        }
        return parse(resp.text, aci)
    }

    /**
     * Devices without a Kyber pre-key are skipped: this protocol version requires PQXDH, and building
     * a non-post-quantum session instead would be a silent downgrade.
     *
     * [warn] is injectable so this stays testable off-device.
     */
    internal fun parse(
        body: String,
        aci: String,
        warn: (String) -> Unit = { Log.w(TAG, it) },
    ): List<DeviceBundle> {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            warn("unparseable prekey response for $aci: ${e.message}")
            return emptyList()
        }
        val identityKey = decode(root.str("identityKey")) ?: run {
            warn("prekey response for $aci has no identityKey")
            return emptyList()
        }
        val devices = try {
            root["devices"]?.jsonArray ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }

        val result = ArrayList<DeviceBundle>(devices.size)
        for (element in devices) {
            val device = try { element.jsonObject } catch (_: Exception) { continue }
            val deviceId = device.int("deviceId") ?: continue
            if (deviceId < 1) continue

            val signed = device.obj("signedPreKey")
            val signedPublic = decode(signed?.str("publicKey"))
            val signedSignature = decode(signed?.str("signature"))
            if (signed == null || signedPublic == null || signedSignature == null) {
                warn("$aci device $deviceId has no signed pre-key, skipping")
                continue
            }

            val pq = device.obj("pqPreKey")
            val pqPublic = decode(pq?.str("publicKey"))
            val pqSignature = decode(pq?.str("signature"))
            if (pq == null || pqPublic == null || pqSignature == null) {
                warn("$aci device $deviceId has no Kyber pre-key, skipping")
                continue
            }

            // The one-time EC pre-key is the only optional part; the server runs out of them.
            val oneTime = device.obj("preKey")
            val oneTimePublic = decode(oneTime?.str("publicKey"))

            result.add(
                DeviceBundle(
                    deviceId = deviceId,
                    bundle = SignalE2E.ParsedPreKeyBundle(
                        registrationId = device.int("registrationId") ?: 0,
                        preKeyId = if (oneTimePublic != null) oneTime?.int("keyId") else null,
                        preKeyPublic = oneTimePublic,
                        signedPreKeyId = signed.int("keyId") ?: 0,
                        signedPreKeyPublic = signedPublic,
                        signedPreKeySignature = signedSignature,
                        identityKey = identityKey,
                        kyberPreKeyId = pq.int("keyId") ?: 0,
                        kyberPreKeyPublic = pqPublic,
                        kyberPreKeySignature = pqSignature,
                    ),
                ),
            )
        }
        return result
    }

    private fun JsonObject.str(key: String): String? =
        try { this[key]?.jsonPrimitive?.content } catch (_: Exception) { null }

    private fun JsonObject.int(key: String): Int? =
        try { this[key]?.jsonPrimitive?.int } catch (_: Exception) { null }

    private fun JsonObject.obj(key: String): JsonObject? =
        try { this[key]?.jsonObject } catch (_: Exception) { null }

    /** Keys arrive base64 without padding, so restore it before decoding. */
    private fun decode(value: String?): ByteArray? {
        if (value.isNullOrEmpty()) return null
        val padded = when (value.length % 4) {
            2 -> "$value=="
            3 -> "$value="
            0 -> value
            else -> return null
        }
        return try { Base64.Default.decode(padded) } catch (_: Exception) { null }
    }
}
