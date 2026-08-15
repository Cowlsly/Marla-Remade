package com.vayunmathur.communicate.data.signal.transport

import android.os.Build
import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.signal.SignalAuthData
import org.json.JSONObject
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketRequestMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketResponseMessage
import java.security.SecureRandom

/**
 * Payload builder for the Signal primary client.
 *
 * Real Signal wire (grounded in C:\Users\Vayun\signal-ref):
 *  - Signal-Android lib/libsignal-service/src/main/protowire/SignalService.proto
 *  - libsignal/rust/net/src/proto/chat_websocket.proto
 *  - libsignal/rust/protocol/src/proto/{wire,sealed_sender}.proto
 *
 * Transport framing: binary protobuf WebSocketMessage (type REQUEST/RESPONSE, uint64 id).
 * All chat sends go via single path PUT /v1/messages/{destinationAci} (or /v1/messages/multi)
 * with body = encrypted Content. No per-action sub-paths.
 */
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

    // ---- Single send path: PUT /v1/messages/{aci} and /multi ----

    fun buildPutMessagesRequest(
        destinationAci: String,
        encryptedContentBytes: ByteArray,
        isMulti: Boolean = false,
        id: Long = nextRequestId(),
    ): WebSocketRequestMessage {
        val path = if (isMulti) "/v1/messages/multi" else "/v1/messages/$destinationAci"
        return buildWebSocketRequestMessage(verb = "PUT", path = path, body = encryptedContentBytes, id = id)
    }

    fun encodePutMessagesRequest(
        destinationAci: String,
        encryptedContentBytes: ByteArray,
        isMulti: Boolean = false,
        id: Long = nextRequestId(),
    ): ByteArray = buildWebSocketMessageForRequest(buildPutMessagesRequest(destinationAci, encryptedContentBytes, isMulti, id)).toByteArray()

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
