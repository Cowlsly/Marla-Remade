package com.vayunmathur.communicate.data.signal.transport

import android.util.Log
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.signal.libsignal.protocol.ServiceId
import org.signal.libsignal.zkgroup.ServerPublicParams
import org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse
import org.signal.libsignal.zkgroup.auth.ClientZkAuthOperations
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.storageservice.storage.protos.groups.Group
import javax.net.ssl.SSLSocketFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.days

/**
 * GroupsV2 against Signal's storage service.
 *
 * Paths, hosts and the auth format are taken from the official client rather than inferred:
 * - credentials: `GET /v1/certificate/auth/group` on **chat** (`GroupsV2ApiHelper.kt`)
 * - group state: `GET /v2/groups/` on **storage** (`PushServiceSocket.GROUPSV2_GROUP`, `makeStorageRequest`)
 * - group auth is Basic, with the group's *public* params as the username and a zkgroup credential
 *   presentation as the password, both hex (`GroupsV2AuthorizationString`)
 *
 * Note the group endpoints are **not** on chat.signal.org — sending them there is why the previous
 * hand-rolled group calls could never have worked.
 */
@OptIn(ExperimentalEncodingApi::class)
object SignalGroupsApi {
    private const val TAG = "SignalGroupsApi"

    /** From the official client's build config: `STORAGE_URL`. */
    private const val STORAGE_URL = "https://storage.signal.org"
    private const val CHAT_URL = "https://chat.signal.org"

    private const val CREDENTIAL_PATH = "/v1/certificate/auth/group"
    private const val GROUP_PATH = "/v2/groups/"

    private val json = Json { ignoreUnknownKeys = true }

    /** A credential and the day it is valid for. The server returns seven days at a time. */
    data class DayCredential(val redemptionTimeSeconds: Long, val credential: ByteArray)

    /**
     * Fetch a week of auth credentials. Official caches these; we fetch per use, which is slower but has no
     * staleness to get wrong.
     */
    suspend fun fetchCredentials(
        authHeader: String,
        sslSocketFactory: SSLSocketFactory?,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): List<DayCredential> {
        // Signal issues credentials on day boundaries, so the window must be day-aligned.
        val today = nowSeconds - (nowSeconds % 86_400)
        val end = today + 7.days.inWholeSeconds
        val url = "$CHAT_URL$CREDENTIAL_PATH?redemptionStartSeconds=$today&redemptionEndSeconds=$end"
        val resp = try {
            NetworkClient.execute(
                url,
                method = "GET",
                headers = mapOf("Authorization" to "Basic $authHeader"),
                sslSocketFactory = sslSocketFactory,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not fetch group auth credentials", t)
            return emptyList()
        }
        if (!resp.isSuccess) {
            Log.w(TAG, "group credential request failed: ${resp.status} ${resp.statusMessage}")
            return emptyList()
        }
        return parseCredentials(resp.text)
    }

    internal fun parseCredentials(body: String, warn: (String) -> Unit = { Log.w(TAG, it) }): List<DayCredential> =
        try {
            json.parseToJsonElement(body).jsonObject["credentials"]?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val time = obj["redemptionTime"]?.jsonPrimitive?.long ?: return@mapNotNull null
                val credential = obj["credential"]?.jsonPrimitive?.content
                    ?.let { runCatching { Base64.Default.decode(it) }.getOrNull() }
                    ?: return@mapNotNull null
                DayCredential(time, credential)
            } ?: emptyList()
        } catch (e: Exception) {
            warn("unparseable group credential response: ${e.message}")
            emptyList()
        }

    /**
     * Build the Basic auth value the group endpoints require.
     *
     * The presentation proves membership without revealing which member we are, which is why group requests do
     * not use ordinary account auth.
     */
    fun authorizationFor(
        auth: SignalAuthData,
        secretParams: GroupSecretParams,
        credential: DayCredential,
    ): String? = try {
        val operations = ClientZkAuthOperations(ServerPublicParams(serverPublicParams()))
        val aci = ServiceId.Aci.parseFromString(auth.aci)
        val pni = ServiceId.Pni.parseFromString(auth.pni)
        val withPni = operations.receiveAuthCredentialWithPniAsServiceId(
            aci,
            pni,
            credential.redemptionTimeSeconds,
            AuthCredentialWithPniResponse(credential.credential),
        )
        val presentation = operations.createAuthCredentialPresentation(secretParams, withPni)
        val username = secretParams.publicParams.serialize().toHex()
        val password = presentation.serialize().toHex()
        Base64.Default.encode("$username:$password".toByteArray(Charsets.UTF_8))
    } catch (t: Throwable) {
        Log.w(TAG, "could not build a group authorization", t)
        null
    }

    /** Fetch and return the group's current server state, still encrypted. */
    suspend fun fetchGroup(
        authorization: String,
        sslSocketFactory: SSLSocketFactory?,
    ): Group? {
        val resp = try {
            NetworkClient.execute(
                "$STORAGE_URL$GROUP_PATH",
                method = "GET",
                headers = mapOf("Authorization" to "Basic $authorization"),
                sslSocketFactory = sslSocketFactory,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not fetch the group", t)
            return null
        }
        if (!resp.isSuccess) {
            Log.w(TAG, "group fetch failed: ${resp.status} ${resp.statusMessage}")
            return null
        }
        return try {
            // The response is a GroupResponse wrapping the Group; older servers returned the Group directly.
            val bytes = resp.bytes ?: return null
            runCatching { org.signal.storageservice.storage.protos.groups.GroupResponse.parseFrom(bytes).group }
                .getOrNull()
                ?: Group.parseFrom(bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "could not parse the group response", t)
            null
        }
    }

    /** Signal's zkgroup server public params, needed to verify credentials it issues. */
    private fun serverPublicParams(): ByteArray = Base64.Default.decode(SERVER_PUBLIC_PARAMS_B64)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Production zkgroup server public params, copied verbatim from the official client's
     * `ZKGROUP_SERVER_PUBLIC_PARAMS` (`app/build.gradle.kts`). These are public verification parameters, not a
     * secret, and they must match the server exactly or every credential fails to verify.
     */
    private const val SERVER_PUBLIC_PARAMS_B64 =
        "AMhf5ywVwITZMsff/eCyudZx9JDmkkkbV6PInzG4p8x3VqVJSFiMvnvlEKWuRob/1eaIetR31IYeAbm0NdOuHH8Qi+Rexi1wLl" +
            "pzIo1gstHWBfZzy1+qHRV5A4TqPp15YzBPm0WSggW6PbSn+F4lf57VCnHF7p8SvzAA2ZZJPYJURt8X7bbg+H3i+PEjH9DXIt" +
            "NEqs2sNcug37xZQDLm7X36nOoGPs54XsEGzPdEV+itQNGUFEjY6X9Uv+Acuks7NpyGvCoKxGwgKgE5XyJ+nNKlyHHOLb6N1N" +
            "uHyBrZrgtY/JYJHRooo5CEqYKBqdFnmbTVGEkCvJKxLnjwKWf+fEPoWeQFj5ObDjcKMZf2Jm2Ae69x+ikU5gBXsRmoF94GXT" +
            "LfN0/vLt98KDPnxwAQL9j5V1jGOY8jQl6MLxEs56cwXN0dqCnImzVH3TZT1cJ8SW1BRX6qIVxEzjsSGx3yxF3suAilPMqGRp" +
            "4ffyopjMD1JXiKR2RwLKzizUe5e8XyGOy9fplzhw3jVzTRyUZTRSZKkMLWcQ/gv0E4aONNqs4P+NameAZYOD12qRkxosQQP5" +
            "uux6B2nRyZ7sAV54DgFyLiRcq1FvwKw2EPQdk4HDoePrO/RNUbyNddnM/mMgj4FW65xCoT1LmjrIjsv/Ggdlx46ueczhMgtB" +
            "unx1/w8k8V+l8LVZ8gAT6wkU5J+DPQalQguMg12Jzug3q4TbdHiGCmD9EunCwOmsLuLJkz6EcSYXtrlDEnAM+hicw7iergYL" +
            "LlMXpfTdGxJCWJmP4zqUFeTTmsmhsjGBt7NiEB/9pFFEB3pSbf4iiUukw63Eo8Aqnf4iwob6X1QviCWuc8t0LUlT9vALgh/f" +
            "2DPVOOmR0RW6bgRvc7DSF20V/omg+YBw=="
}
