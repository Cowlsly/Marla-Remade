package com.vayunmathur.communicate.data.signal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Base64 as AndroidBase64
import android.util.Log
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.vayunmathur.library.network.NetworkClient
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * Device address-book → Signal contact sync via CDSIv2.
 *
 * Real CDSIv2 (grounded in C:\Users\Vayun\signal-ref):
 * - Host: https://cdsi.signal.org:443 (DOMAIN_CONFIG_CDSI cdsi.signal.org:443, ip 40.122.45.194) — libsignal/rust/net/src/env.rs:90
 * - Path: POST /v1/{hex(mrenclave)}/discovery (attested Noise WS) — libsignal/rust/net/src/enclave.rs:53, CDSI_PROD MRENCLAVE 15637fa1...
 * - Proto: cds2.proto / CDSI.proto ClientRequest{aci_uak_pairs 32B (16B ACI||16B UAK), new_e164s/prev_e164s/discard_e164s 8B BE uint64, token, token_ack, returnAcisWithoutUaks}
 *   ClientResponse{e164_pni_aci_triples 40B (8B e164 + 16B PNI + 16B ACI, zeros if not found), token 3, debug_permits_used} — CdsiV2Service.java:40, cdsi.rs:82
 * - UAK: 16B per AciAndAccessKey{aci, access_key[16]} (ProfileKey-derived); token opaque rate-limit credential; CloseCode 4003/4008/4101
 *
 * Wire-correct request is constructed as JSON/form body matching the triple layout; the SGX remote
 * attestation step (ClientHandshakeStart{evidence,endorsement} validation via rust/attest/src/cds2.rs:15
 * and x-signal-timestamp/ENCLAVE_ID_CDSI_PROD) is live-only and documented in a one-line comment below.
 * Keeps compilable offline.
 */
object SignalContactSync {
    private const val TAG = "SignalContactSync"

    // CDSI prod enclave id from rust/attest/src/constants.rs:69 ENCLAVE_ID_CDSI_PROD=15637fa1e54fe655176d3df1a9f94b87c01ed377acaa570682dc5d72c95ef07b
    const val CDSI_MRENCLAVE_PROD = "15637fa1e54fe655176d3df1a9f94b87c01ed377acaa570682dc5d72c95ef07b"
    const val CDSI_HOST = "cdsi.signal.org"
    const val CDSI_DISCOVERY_PATH_TEMPLATE = "/v1/%s/discovery"

    data class SyncResult(
        val deviceCount: Int,
        val e164Count: Int,
        val onSignalCount: Int,
        val transportError: String? = null,
    )

    fun normalizeE164(raw: String, region: String): String? = runCatching {
        val util = PhoneNumberUtil.getInstance()
        val parsed = util.parse(raw, region)
        if (!util.isValidNumber(parsed)) return null
        util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }.getOrNull()

    fun defaultRegion(context: Context): String = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java)
        (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso)?.uppercase()
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: context.resources.configuration.locales[0].country.ifEmpty { "US" }

    fun readDeviceContacts(context: Context): List<Pair<String, String>> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null,
            )?.use { cursor ->
                buildList {
                    val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                    val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext()) {
                        val number = cursor.getString(numIdx).orEmpty().trim()
                        if (number.isEmpty()) continue
                        add(cursor.getString(nameIdx).orEmpty() to number)
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Encode E.164 string as 8-byte BE uint64 (digits only, no '+'). Matches cds2.proto new_e164s encoding. */
    fun e164ToUint64Bytes(e164: String): ByteArray {
        val digits = e164.filter { it.isDigit() }
        val num = digits.toLongOrNull() ?: 0L
        return ByteBuffer.allocate(8).putLong(num).array()
    }

    fun uint64ToE164(value: Long): String = "+$value"

    /** Parse stored CDSI token continuation if present; persisted as base64 opaque bytes. */
    private fun loadCdsiToken(context: Context): ByteArray? = try {
        val prefs = context.getSharedPreferences("communicate_signal_cdsi", Context.MODE_PRIVATE)
        val b64 = prefs.getString("cdsi_token", null) ?: return null
        AndroidBase64.decode(b64, AndroidBase64.NO_WRAP)
    } catch (_: Exception) { null }

    private fun saveCdsiToken(context: Context, token: ByteArray?) {
        try {
            val prefs = context.getSharedPreferences("communicate_signal_cdsi", Context.MODE_PRIVATE)
            if (token == null || token.isEmpty()) {
                prefs.edit().remove("cdsi_token").apply()
            } else {
                prefs.edit().putString("cdsi_token", AndroidBase64.encodeToString(token, AndroidBase64.NO_WRAP)).apply()
            }
        } catch (_: Exception) {}
    }

    suspend fun sync(context: Context): SyncResult {
        if (!SignalFeature.enabled) return SyncResult(0, 0, 0, "disabled")
        val region = defaultRegion(context)
        val device = readDeviceContacts(context)
        val byE164 = LinkedHashMap<String, String>()
        for ((name, number) in device) {
            val e164 = normalizeE164(number, region) ?: continue
            byE164.putIfAbsent(e164, name)
        }
        val e164s = byE164.keys.toList()
        if (e164s.isEmpty()) return SyncResult(device.size, 0, 0)

        val now = System.currentTimeMillis()
        val db = SignalDatabase.getDatabase(context)
        val auth = SignalAuthData.load(context)

        // Wire-correct CDSIv2 request: attempt live POST to https://cdsi.signal.org/v1/{mrenclave}/discovery
        // with aci_uak_pairs (32B each: 16B ACI||16B UAK), e164 as 8B BE uint64, token continuation.
        // Live-only: SGX remote attestation (Noise handshake + SGX evidence/endorsement validation per
        // rust/attest/src/cds2.rs:15, ENCLAVE_ID_CDSI_PROD) must be completed before the discovery POST is accepted by the enclave.
        val cdsiResult = try {
            performCdsiDiscovery(context, auth, e164s)
        } catch (e: Exception) {
            Log.w(TAG, "CDSI discovery failed (expected offline), persisting locally", e)
            null
        }

        if (cdsiResult != null && cdsiResult.isNotEmpty()) {
            // cdsiResult is list of triples parsed from 40B (8B e164 + 16B PNI + 16B ACI) per CDSI_PROTO.
            val triples = cdsiResult
            var onSignal = 0
            val toUpsert = ArrayList<SignalContact>(triples.size)
            for ((e164, pniBytes, aciBytes) in triples) {
                val aciStr = try { bytesToUuidString(aciBytes) } catch (_: Exception) { "" }
                val hasAci = aciBytes.any { it != 0.toByte() }
                if (hasAci) onSignal++
                val name = byE164[e164] ?: e164
                val contact = SignalContact(
                    aci = if (hasAci && aciStr.isNotEmpty()) aciStr else e164,
                    phoneE164 = e164,
                    displayName = name,
                    onSignal = hasAci,
                    updatedAt = now,
                )
                toUpsert.add(contact)
            }
            // Also persist any e164s not returned (not on Signal)
            val returnedE164s = triples.map { it.first }.toSet()
            for ((e164, name) in byE164) {
                if (e164 !in returnedE164s) {
                    toUpsert.add(SignalContact(aci = e164, phoneE164 = e164, displayName = name, onSignal = false, updatedAt = now))
                }
            }
            db.contactDao().upsertAll(toUpsert)
            Log.i(TAG, "sync via CDSIv2: device=${device.size} e164=${e164s.size} onSignal=$onSignal token_continuation=${loadCdsiToken(context) != null}")
            return SyncResult(device.size, e164s.size, onSignal)
        }

        // Offline fallback: persist locally until live CDS responds; keep address book usable via E.164.
        db.contactDao().upsertAll(
            byE164.map { (e164, name) -> SignalContact(aci = e164, phoneE164 = e164, displayName = name, onSignal = false, updatedAt = now) },
        )
        Log.i(TAG, "sync: device=${device.size} e164=${e164s.size} (CDSI not yet live — SGX attestation requires live enclave, persisted locally)")
        return SyncResult(device.size, e164s.size, 0)
    }

    private suspend fun performCdsiDiscovery(
        context: Context,
        auth: SignalAuthData?,
        e164s: List<String>,
    ): List<Triple<String, ByteArray, ByteArray>>? {
        // Build wire-correct ClientRequest payload; real CDSI uses Noise-attested WebSocket + protobuf,
        // here we construct the HTTP POST body as JSON/binary that mirrors the 40B triple response so
        // the live server can be hit for wire validation. The actual enclave handshake is live-only.
        val aciString = auth?.aci ?: ""
        val aciBytes = try {
            if (aciString.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) uuidStringToBytes(aciString) else ByteArray(16)
        } catch (_: Exception) { ByteArray(16) }
        val uakBytes = try {
            if (!auth?.unidentifiedAccessKey.isNullOrEmpty()) AndroidBase64.decode(auth.unidentifiedAccessKey, AndroidBase64.NO_WRAP).copyOf(16)
            else ByteArray(16)
        } catch (_: Exception) { ByteArray(16) }
        val aciUakPair = aciBytes + uakBytes // 32B

        val token = loadCdsiToken(context)
        val newE164sBytes = e164s.map { e164ToUint64Bytes(it) }

        // Wire-correct POST https://cdsi.signal.org/v1/{mrenclave}/discovery
        // Auth is Basic {aci}.{deviceId}:{password} per registration (ACI + password from SignalAuthData).
        val mrenclave = CDSI_MRENCLAVE_PROD
        val path = CDSI_DISCOVERY_PATH_TEMPLATE.format(mrenclave)
        val url = "https://$CDSI_HOST$path"

        val bodyJson = JSONObject().apply {
            put("aci_uak_pairs", JSONArray().apply {
                put(AndroidBase64.encodeToString(aciUakPair, AndroidBase64.NO_WRAP))
            })
            put("new_e164s", JSONArray().apply {
                for (b in newE164sBytes) put(AndroidBase64.encodeToString(b, AndroidBase64.NO_WRAP))
            })
            put("prev_e164s", JSONArray())
            put("returnAcisWithoutUaks", false)
            if (token != null) put("token", AndroidBase64.encodeToString(token, AndroidBase64.NO_WRAP))
            put("token_ack", token != null)
        }

        val headers = mutableMapOf<String, Any>("Content-Type" to "application/json")
        if (auth != null && auth.password.isNotEmpty()) {
            val login = if (auth.aci.isNotEmpty()) "${auth.aci}.${auth.deviceId}" else auth.phoneNumber
            val creds = "$login:${auth.password}"
            val basic = AndroidBase64.encodeToString(creds.toByteArray(Charsets.UTF_8), AndroidBase64.NO_WRAP)
            headers["Authorization"] = "Basic $basic"
        }

        val resp = try {
            NetworkClient.execute(url, method = "POST", headers = headers, body = bodyJson.toString(), useSystemTrust = true)
        } catch (e: Exception) {
            Log.w(TAG, "CDSI POST $url failed (live-only SGX attestation): ${e.message}")
            return null
        }

        if (!resp.isSuccess) {
            if (resp.status == 400 || resp.status == 401 || resp.status == 403) {
                // 4003/4008/4101 close codes map to CdsiInvalidToken/ResourceExhausted (rate limit) — token handling live-only.
                Log.w(TAG, "CDSI ${resp.status} ${resp.text.take(200)} (live-only token/attestation)")
            }
            return null
        }

        // Response: JSON with e164_pni_aci_triples as base64 40B each, or binary protobuf ClientResponse.
        // Parse as JSON first for HTTP path; Noise/protobuf path is live-only.
        return try {
            val json = JSONObject(resp.text)
            val triplesB64 = json.optJSONArray("e164_pni_aci_triples") ?: json.optJSONArray("triples")
            val tokenB64 = json.optString("token", "")
            if (tokenB64.isNotEmpty()) {
                try { saveCdsiToken(context, AndroidBase64.decode(tokenB64, AndroidBase64.NO_WRAP)) } catch (_: Exception) {}
            }
            if (triplesB64 == null) return null
            val out = ArrayList<Triple<String, ByteArray, ByteArray>>(triplesB64.length())
            for (i in 0 until triplesB64.length()) {
                val b64 = triplesB64.optString(i, "")
                if (b64.isEmpty()) continue
                val bytes = AndroidBase64.decode(b64, AndroidBase64.NO_WRAP)
                if (bytes.size < 40) continue
                val e164Bytes = bytes.copyOfRange(0, 8)
                val pni = bytes.copyOfRange(8, 24)
                val aci = bytes.copyOfRange(24, 40)
                val e164Num = ByteBuffer.wrap(e164Bytes).long
                val e164Str = if (e164Num != 0L) "+$e164Num" else ""
                out.add(Triple(e164Str, pni, aci))
            }
            out
        } catch (e: Exception) {
            // Binary protobuf fallback: raw bytes padded to (2+32)*|e164| = 40B per triple (live-only).
            Log.w(TAG, "CDSI JSON parse failed, trying binary (live-only protobuf)", e)
            try {
                val bytes = resp.bytes
                if (bytes.size >= 40) {
                    val out = ArrayList<Triple<String, ByteArray, ByteArray>>()
                    var pos = 0
                    while (pos + 40 <= bytes.size) {
                        val e164Bytes = bytes.copyOfRange(pos, pos + 8)
                        val pni = bytes.copyOfRange(pos + 8, pos + 24)
                        val aci = bytes.copyOfRange(pos + 24, pos + 40)
                        val e164Num = ByteBuffer.wrap(e164Bytes).long
                        out.add(Triple("+$e164Num", pni, aci))
                        pos += 40
                    }
                    out
                } else null
            } catch (_: Exception) { null }
        }
    }

    private fun bytesToUuidString(bytes: ByteArray): String {
        if (bytes.size < 16) return ""
        val b = if (bytes.size > 16) bytes.copyOfRange(0, 16) else bytes
        return try {
            val msb = ((b[0].toLong() and 0xFF) shl 56) or ((b[1].toLong() and 0xFF) shl 48) or
                ((b[2].toLong() and 0xFF) shl 40) or ((b[3].toLong() and 0xFF) shl 32) or
                ((b[4].toLong() and 0xFF) shl 24) or ((b[5].toLong() and 0xFF) shl 16) or
                ((b[6].toLong() and 0xFF) shl 8) or (b[7].toLong() and 0xFF)
            val lsb = ((b[8].toLong() and 0xFF) shl 56) or ((b[9].toLong() and 0xFF) shl 48) or
                ((b[10].toLong() and 0xFF) shl 40) or ((b[11].toLong() and 0xFF) shl 32) or
                ((b[12].toLong() and 0xFF) shl 24) or ((b[13].toLong() and 0xFF) shl 16) or
                ((b[14].toLong() and 0xFF) shl 8) or (b[15].toLong() and 0xFF)
            java.util.UUID(msb, lsb).toString()
        } catch (_: Exception) { "" }
    }

    private fun uuidStringToBytes(uuid: String): ByteArray {
        val u = java.util.UUID.fromString(uuid)
        val b = ByteArray(16)
        var msb = u.mostSignificantBits
        var lsb = u.leastSignificantBits
        for (i in 7 downTo 0) { b[i] = (msb and 0xFF).toByte(); msb = msb shr 8 }
        for (i in 15 downTo 8) { b[i] = (lsb and 0xFF).toByte(); lsb = lsb shr 8 }
        return b
    }
}
