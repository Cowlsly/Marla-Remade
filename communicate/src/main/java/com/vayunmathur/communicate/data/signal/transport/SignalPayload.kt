package com.vayunmathur.communicate.data.signal.transport

import android.os.Build
import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.signal.SignalAuthData
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.json.JSONObject
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketRequestMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketResponseMessage
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Payload builder for the Signal primary client.
 *
 * Real Signal wire (grounded in C:\Users\Vayun\signal-ref):
 *  - Signal-Android lib/libsignal-service/src/main/protowire/SignalService.proto
 *  - libsignal/rust/net/src/proto/chat_websocket.proto
 *  - libsignal/rust/protocol/src/proto/{wire,sealed_sender}.proto
 *
 * Transport framing: binary protobuf WebSocketMessage (type REQUEST/RESPONSE, uint64 id).
 * Chat sends go via PUT /v1/messages/{destinationAci} with a JSON OutgoingPushMessageList body
 * carrying one base64 ciphertext per destination device.
 */
@OptIn(ExperimentalEncodingApi::class)
object SignalPayload {

    private val secureRandom = SecureRandom()

    // ---- WebSocket framing (binary protobuf) ----

    fun buildWebSocketRequestMessage(
        verb: String,
        path: String,
        body: ByteArray? = null,
        id: Long = nextRequestId(),
        headers: List<String> = emptyList(),
    ): WebSocketRequestMessage {
        val b = WebSocketRequestMessage.newBuilder()
            .setVerb(verb)
            .setPath(path)
            .setId(id)
        if (body != null && body.isNotEmpty()) b.setBody(ByteString.copyFrom(body))
        if (headers.isNotEmpty()) b.addAllHeaders(headers)
        return b.build()
    }

    fun buildWebSocketResponseMessage(
        id: Long,
        status: Int = 200,
        message: String = "OK",
        body: ByteArray? = null,
        headers: List<String> = emptyList(),
    ): WebSocketResponseMessage {
        val b = WebSocketResponseMessage.newBuilder()
            .setId(id)
            .setStatus(status)
            .setMessage(message)
        if (body != null && body.isNotEmpty()) b.setBody(ByteString.copyFrom(body))
        if (headers.isNotEmpty()) b.addAllHeaders(headers)
        return b.build()
    }

    fun buildWebSocketMessageForRequest(request: WebSocketRequestMessage): WebSocketMessage =
        WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.REQUEST).setRequest(request).build()

    fun buildWebSocketMessageForResponse(response: WebSocketResponseMessage): WebSocketMessage =
        WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.RESPONSE).setResponse(response).build()

    fun encodeWebSocketRequest(
        verb: String,
        path: String,
        body: ByteArray? = null,
        id: Long = nextRequestId(),
        headers: List<String> = emptyList(),
    ): ByteArray = buildWebSocketMessageForRequest(buildWebSocketRequestMessage(verb, path, body, id, headers)).toByteArray()

    fun encodeWebSocketResponse(
        id: Long,
        status: Int = 200,
        message: String = "OK",
        body: ByteArray? = null,
    ): ByteArray = buildWebSocketMessageForResponse(buildWebSocketResponseMessage(id, status, message, body)).toByteArray()

    // ---- Single send path: PUT /v1/messages/{aci} ----

    /**
     * One encrypted message for one destination device, matching the official client's
     * `OutgoingPushMessage`. [type] is an **Envelope** type, which is a different numbering space from
     * `CiphertextMessage` — see [envelopeTypeFor].
     */
    data class OutgoingPushMessage(
        val type: Int,
        val destinationDeviceId: Int,
        val destinationRegistrationId: Int,
        val content: ByteArray,
    )

    /**
     * Map a `CiphertextMessage` type to the `Envelope.Type` the server expects. The two enums do not
     * share values: `WHISPER_TYPE` is 2 but `DOUBLE_RATCHET` is 1.
     */
    fun envelopeTypeFor(ciphertextMessageType: Int): Int = when (ciphertextMessageType) {
        CiphertextMessage.PREKEY_TYPE ->
            SignalServiceProtos.Envelope.Type.PREKEY_MESSAGE.number
        CiphertextMessage.WHISPER_TYPE ->
            SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET.number
        else -> throw IllegalArgumentException("unsendable ciphertext type $ciphertextMessageType")
    }

    /**
     * The `PUT /v1/messages/{aci}` JSON body (`OutgoingPushMessageList`). `content` is base64 **with**
     * padding here, unlike the unpadded encoding used for pre-keys.
     */
    fun buildPutMessagesBody(
        destinationAci: String,
        messages: List<OutgoingPushMessage>,
        timestamp: Long,
        online: Boolean = false,
        urgent: Boolean = true,
    ): ByteArray {
        val list = buildJsonObject {
            put("destination", destinationAci)
            put("timestamp", timestamp)
            put("online", online)
            put("urgent", urgent)
            putJsonArray("messages") {
                messages.forEach { m ->
                    add(
                        buildJsonObject {
                            put("type", m.type)
                            put("destinationDeviceId", m.destinationDeviceId)
                            put("destinationRegistrationId", m.destinationRegistrationId)
                            // Padded base64 here, unlike the unpadded encoding used for pre-keys.
                            put("content", Base64.Default.encode(m.content))
                        },
                    )
                }
            }
        }
        return list.toString().toByteArray(Charsets.UTF_8)
    }

    fun putMessagesPath(destinationAci: String, story: Boolean = false): String =
        "/v1/messages/$destinationAci?story=$story"

    fun buildPutMessagesRequest(
        destinationAci: String,
        jsonBody: ByteArray,
        story: Boolean = false,
        id: Long = nextRequestId(),
    ): WebSocketRequestMessage = buildWebSocketRequestMessage(
        verb = "PUT",
        path = putMessagesPath(destinationAci, story),
        body = jsonBody,
        id = id,
        headers = listOf("content-type:application/json"),
    )

    fun buildKeepaliveRequest(id: Long = nextRequestId()): WebSocketRequestMessage =
        buildWebSocketRequestMessage(verb = "PUT", path = "/v1/keepalive", id = id)

    // ---- Content / DataMessage builders (plaintext, before E2E encrypt) ----

    fun buildDataMessage(
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        groupV2MasterKey: ByteArray? = null,
        groupV2Revision: Int? = null,
        groupV2Change: ByteArray? = null,
        bodyRanges: List<SignalServiceProtos.BodyRange> = emptyList(),
        attachments: List<SignalServiceProtos.AttachmentPointer> = emptyList(),
        quote: SignalServiceProtos.DataMessage.Quote? = null,
        reaction: SignalServiceProtos.DataMessage.Reaction? = null,
        delete: SignalServiceProtos.DataMessage.Delete? = null,
        pollCreate: SignalServiceProtos.DataMessage.PollCreate? = null,
        pollVote: SignalServiceProtos.DataMessage.PollVote? = null,
        pollTerminate: SignalServiceProtos.DataMessage.PollTerminate? = null,
        expireTimer: Int? = null,
        profileKey: ByteArray? = null,
        requiredProtocolVersion: Int? = null,
    ): SignalServiceProtos.DataMessage {
        val b = SignalServiceProtos.DataMessage.newBuilder()
            .setBody(body)
            .setTimestamp(timestamp)
        if (groupV2MasterKey != null || groupV2Revision != null || groupV2Change != null) {
            val g = SignalServiceProtos.GroupContextV2.newBuilder()
            if (groupV2MasterKey != null) g.setMasterKey(ByteString.copyFrom(groupV2MasterKey))
            if (groupV2Revision != null) g.setRevision(groupV2Revision)
            if (groupV2Change != null) g.setGroupChange(ByteString.copyFrom(groupV2Change))
            b.setGroupV2(g)
        }
        if (bodyRanges.isNotEmpty()) b.addAllBodyRanges(bodyRanges)
        if (attachments.isNotEmpty()) b.addAllAttachments(attachments)
        if (quote != null) b.setQuote(quote)
        if (reaction != null) b.setReaction(reaction)
        if (delete != null) b.setDelete(delete)
        if (pollCreate != null) b.setPollCreate(pollCreate)
        if (pollVote != null) b.setPollVote(pollVote)
        if (pollTerminate != null) b.setPollTerminate(pollTerminate)
        if (expireTimer != null) b.setExpireTimer(expireTimer)
        if (profileKey != null) b.setProfileKey(ByteString.copyFrom(profileKey))
        if (requiredProtocolVersion != null) b.setRequiredProtocolVersion(requiredProtocolVersion)
        return b.build()
    }

    fun buildContentWithDataMessage(
        dataMessage: SignalServiceProtos.DataMessage,
        senderKeyDistributionMessage: ByteArray? = null,
        pniSignatureMessage: SignalServiceProtos.PniSignatureMessage? = null,
    ): SignalServiceProtos.Content {
        val b = SignalServiceProtos.Content.newBuilder().setDataMessage(dataMessage)
        if (senderKeyDistributionMessage != null) b.setSenderKeyDistributionMessage(ByteString.copyFrom(senderKeyDistributionMessage))
        if (pniSignatureMessage != null) b.setPniSignatureMessage(pniSignatureMessage)
        return b.build()
    }

    fun buildReceiptMessage(
        type: SignalServiceProtos.ReceiptMessage.Type,
        timestamps: List<Long>,
    ): SignalServiceProtos.ReceiptMessage {
        val b = SignalServiceProtos.ReceiptMessage.newBuilder().setType(type)
        timestamps.forEach { b.addTimestamp(it) }
        return b.build()
    }

    fun buildContentForReceipt(
        type: SignalServiceProtos.ReceiptMessage.Type,
        timestamps: List<Long>,
    ): SignalServiceProtos.Content =
        SignalServiceProtos.Content.newBuilder().setReceiptMessage(buildReceiptMessage(type, timestamps)).build()

    fun buildTypingMessage(
        timestamp: Long,
        action: SignalServiceProtos.TypingMessage.Action,
        groupId: ByteArray? = null,
    ): SignalServiceProtos.TypingMessage {
        val b = SignalServiceProtos.TypingMessage.newBuilder().setTimestamp(timestamp).setAction(action)
        if (groupId != null) b.setGroupId(ByteString.copyFrom(groupId))
        return b.build()
    }

    fun buildContentForTyping(
        timestamp: Long,
        action: SignalServiceProtos.TypingMessage.Action,
        groupId: ByteArray? = null,
    ): SignalServiceProtos.Content =
        SignalServiceProtos.Content.newBuilder().setTypingMessage(buildTypingMessage(timestamp, action, groupId)).build()

    fun buildContentForEdit(
        targetSentTimestamp: Long,
        newDataMessage: SignalServiceProtos.DataMessage,
    ): SignalServiceProtos.Content {
        val edit = SignalServiceProtos.EditMessage.newBuilder()
            .setTargetSentTimestamp(targetSentTimestamp)
            .setDataMessage(newDataMessage)
            .build()
        return SignalServiceProtos.Content.newBuilder().setEditMessage(edit).build()
    }

    fun buildReaction(
        emoji: String,
        remove: Boolean,
        targetAuthorAci: String? = null,
        targetAuthorAciBinary: ByteArray? = null,
        targetSentTimestamp: Long,
    ): SignalServiceProtos.DataMessage.Reaction {
        val b = SignalServiceProtos.DataMessage.Reaction.newBuilder()
            .setEmoji(emoji)
            .setRemove(remove)
            .setTargetSentTimestamp(targetSentTimestamp)
        if (targetAuthorAci != null) b.setTargetAuthorAci(targetAuthorAci)
        if (targetAuthorAciBinary != null) b.setTargetAuthorAciBinary(ByteString.copyFrom(targetAuthorAciBinary))
        return b.build()
    }

    fun buildDelete(targetSentTimestamp: Long): SignalServiceProtos.DataMessage.Delete =
        SignalServiceProtos.DataMessage.Delete.newBuilder().setTargetSentTimestamp(targetSentTimestamp).build()

    fun buildBodyRange(
        start: Int,
        length: Int,
        mentionAci: String? = null,
        mentionAciBinary: ByteArray? = null,
        style: SignalServiceProtos.BodyRange.Style? = null,
    ): SignalServiceProtos.BodyRange {
        val b = SignalServiceProtos.BodyRange.newBuilder().setStart(start).setLength(length)
        if (mentionAci != null) b.setMentionAci(mentionAci)
        if (mentionAciBinary != null) b.setMentionAciBinary(ByteString.copyFrom(mentionAciBinary))
        if (style != null) b.setStyle(style)
        return b.build()
    }

    fun buildGroupContextV2(masterKey: ByteArray, revision: Int, groupChange: ByteArray? = null): SignalServiceProtos.GroupContextV2 {
        val b = SignalServiceProtos.GroupContextV2.newBuilder()
            .setMasterKey(ByteString.copyFrom(masterKey))
            .setRevision(revision)
        if (groupChange != null) b.setGroupChange(ByteString.copyFrom(groupChange))
        return b.build()
    }

    fun buildPollCreate(question: String, options: List<String>, allowMultiple: Boolean = false): SignalServiceProtos.DataMessage.PollCreate =
        SignalServiceProtos.DataMessage.PollCreate.newBuilder()
            .setQuestion(question)
            .setAllowMultiple(allowMultiple)
            .addAllOptions(options)
            .build()

    fun buildPollVote(
        targetAuthorAciBinary: ByteArray? = null,
        targetSentTimestamp: Long,
        optionIndexes: List<Int>,
        voteCount: Int? = null,
    ): SignalServiceProtos.DataMessage.PollVote {
        val b = SignalServiceProtos.DataMessage.PollVote.newBuilder()
            .setTargetSentTimestamp(targetSentTimestamp)
            .addAllOptionIndexes(optionIndexes)
        if (targetAuthorAciBinary != null) b.setTargetAuthorAciBinary(ByteString.copyFrom(targetAuthorAciBinary))
        if (voteCount != null) b.setVoteCount(voteCount)
        return b.build()
    }

    fun buildAccountAttributes(
        auth: SignalAuthData,
        fetchesMessages: Boolean = true,
        registrationLock: String? = null,
    ): String {
        val caps = JSONObject().apply {
            put("storage", true)
            put("versionedExpirationTimer", true)
            put("attachmentBackfill", true)
            put("spqr", true)
            put("usernameChangeSyncMessage", true)
            put("senderKey", true)
        }
        val obj = JSONObject()
        obj.put("fetchesMessages", fetchesMessages)
        obj.put("registrationId", auth.effectiveAciRegId())
        if (auth.pniRegistrationId != 0) obj.put("pniRegistrationId", auth.pniRegistrationId)
        else if (auth.effectiveAciRegId() != auth.registrationId) obj.put("pniRegistrationId", auth.registrationId)
        obj.put("name", auth.profileName)
        if (auth.unidentifiedAccessKey.isNotEmpty()) obj.put("unidentifiedAccessKey", auth.unidentifiedAccessKey)
        obj.put("capabilities", caps)
        obj.put("discoverableByPhoneNumber", true)
        if (registrationLock != null) obj.put("registrationLock", registrationLock)
        else if (auth.registrationLock != null) obj.put("registrationLock", auth.registrationLock)
        obj.put("signalingKey", JSONObject.NULL)
        return obj.toString()
    }

    fun userAgent(): String {
        val device = "${Build.MANUFACTURER}-${Build.MODEL}".replace(' ', '_')
        return "Signal-Android 7.20.0 Android/${Build.VERSION.RELEASE} Device/$device"
    }

    fun nextRequestId(): Long = (secureRandom.nextLong() and Long.MAX_VALUE).let { if (it == 0L) 1L else it }

    @Deprecated("Use proto buildWebSocketRequestMessage / encodeWebSocketRequest")
    fun buildWebSocketRequest(
        verb: String,
        path: String,
        body: ByteArray? = null,
        id: String = java.util.UUID.randomUUID().toString(),
    ): String {
        val obj = JSONObject()
        obj.put("type", "REQUEST")
        obj.put("verb", verb)
        obj.put("path", path)
        obj.put("id", id)
        if (body != null && body.isNotEmpty()) obj.put("body", android.util.Base64.encodeToString(body, android.util.Base64.NO_WRAP))
        return obj.toString()
    }

    @Deprecated("Use proto buildWebSocketResponseMessage / encodeWebSocketResponse")
    fun buildWebSocketResponse(id: String, status: Int = 200, body: ByteArray? = null): String {
        val obj = JSONObject()
        obj.put("type", "RESPONSE")
        obj.put("status", status)
        obj.put("id", id)
        if (body != null) obj.put("body", android.util.Base64.encodeToString(body, android.util.Base64.NO_WRAP))
        return obj.toString()
    }

    @Deprecated("Use buildDataMessage protobuf overload")
    fun buildDataMessageLegacy(
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        groupId: ByteArray? = null,
        quoteId: Long? = null,
    ): JSONObject {
        val data = JSONObject()
        data.put("body", body)
        data.put("timestamp", timestamp)
        if (groupId != null) data.put("groupId", android.util.Base64.encodeToString(groupId, android.util.Base64.NO_WRAP))
        if (quoteId != null) data.put("quoteId", quoteId)
        return data
    }
}
