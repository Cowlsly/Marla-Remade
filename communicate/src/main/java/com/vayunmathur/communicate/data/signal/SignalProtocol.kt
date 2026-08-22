package com.vayunmathur.communicate.data.signal

import android.util.Log
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketRequestMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketResponseMessage
import java.util.UUID

/**
 * Frame encode/decode and Envelope/Content handling for the Signal primary client.
 *
 * Real Signal wire (grounded in C:\Users\Vayun\signal-ref):
 *  - libsignal/rust/net/src/proto/chat_websocket.proto (WebSocketMessage, uint64 id)
 *  - lib/libsignal-service/src/main/protowire/SignalService.proto
 *    (Envelope, Content, DataMessage, ReceiptMessage, TypingMessage, etc.)
 *  - libsignal/rust/protocol/src/proto/{wire,sealed_sender}.proto (PQXDH wire)
 *
 * Inbound: WebSocketMessage (binary protobuf) -> WebSocketRequestMessage.body contains
 *          Envelope bytes (Envelope.content is a serialized CiphertextMessage; libsignal owns its
 *          version framing, so nothing is stripped from it here).
 * Outbound: Build Content -> pad -> encrypt via SignalE2E -> PUT /v1/messages/{aci}.
 */
object SignalProtocol {
    private const val TAG = "SignalProtocol"

    private const val PADDING_BLOCK_SIZE = 80
    private const val TERMINATOR = 0x80.toByte()

    data class SignalEnvelope(
        val type: SignalServiceProtos.Envelope.Type,
        val sourceAci: String,
        val sourceDevice: Int,
        val timestamp: Long,
        val content: ByteArray,
        val serverGuid: String? = null,
        val serverGuidBinary: ByteArray? = null,
        val serverTimestamp: Long = 0L,
        val destinationAci: String? = null,
        val isGroup: Boolean = false,
        val groupId: ByteArray? = null,
        val story: Boolean = false,
        val urgent: Boolean = true,
        val rawEnvelope: SignalServiceProtos.Envelope? = null,
    )

    sealed interface ParsedContent {
        data class Data(val dataMessage: SignalServiceProtos.DataMessage, val raw: SignalServiceProtos.Content) : ParsedContent
        data class Receipt(val receiptMessage: SignalServiceProtos.ReceiptMessage) : ParsedContent
        data class Typing(val typingMessage: SignalServiceProtos.TypingMessage) : ParsedContent
        data class Edit(val editMessage: SignalServiceProtos.EditMessage) : ParsedContent
        data class Call(val callMessage: SignalServiceProtos.CallMessage) : ParsedContent
        data class Null(val nullMessage: SignalServiceProtos.NullMessage) : ParsedContent
        data class Sync(val syncMessage: SignalServiceProtos.SyncMessage) : ParsedContent
        data class Story(val storyMessage: SignalServiceProtos.StoryMessage) : ParsedContent
        data class DecryptionError(val bytes: ByteArray) : ParsedContent
        data class Unknown(val raw: SignalServiceProtos.Content) : ParsedContent
    }

    fun parseWebSocketMessage(bytes: ByteArray): WebSocketMessage? = try {
        WebSocketMessage.parseFrom(bytes)
    } catch (e: InvalidProtocolBufferException) {
        Log.w(TAG, "parseWebSocketMessage failed: ${e.message}")
        null
    }

    fun encodeWebSocketRequest(request: WebSocketRequestMessage): ByteArray =
        WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.REQUEST).setRequest(request).build().toByteArray()

    fun encodeWebSocketResponse(response: WebSocketResponseMessage): ByteArray =
        WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.RESPONSE).setResponse(response).build().toByteArray()

    fun buildWsResponseProto(id: Long, status: Int = 200, message: String = "OK"): WebSocketResponseMessage =
        WebSocketResponseMessage.newBuilder().setId(id).setStatus(status).setMessage(message).build()

    fun encodeWsAck(id: Long, status: Int = 200): ByteArray =
        encodeWebSocketResponse(buildWsResponseProto(id, status))

    fun buildWsRequest(
        verb: String,
        path: String,
        body: ByteArray? = null,
        id: Long = nextRequestId(),
        headers: List<String> = emptyList(),
    ): WebSocketRequestMessage {
        val b = WebSocketRequestMessage.newBuilder().setVerb(verb).setPath(path).setId(id)
        if (body != null && body.isNotEmpty()) b.setBody(ByteString.copyFrom(body))
        if (headers.isNotEmpty()) b.addAllHeaders(headers)
        return b.build()
    }

    fun nextRequestId(): Long = (java.security.SecureRandom().nextLong() and Long.MAX_VALUE).let { if (it == 0L) 1L else it }

    fun parseEnvelope(bytes: ByteArray): SignalServiceProtos.Envelope? = try {
        SignalServiceProtos.Envelope.parseFrom(bytes)
    } catch (e: InvalidProtocolBufferException) {
        Log.w(TAG, "parseEnvelope failed: ${e.message}")
        null
    }

    fun parseEnvelopeFromRequest(request: WebSocketRequestMessage): SignalServiceProtos.Envelope? {
        if (!request.hasBody()) return null
        return parseEnvelope(request.body.toByteArray())
    }

    fun parseEnvelopeFromWsMessage(wsMessage: WebSocketMessage): SignalServiceProtos.Envelope? {
        if (!wsMessage.hasRequest()) return null
        return parseEnvelopeFromRequest(wsMessage.request)
    }

    fun toSignalEnvelope(envelope: SignalServiceProtos.Envelope): SignalEnvelope {
        val sourceAci = when {
            envelope.hasSourceServiceIdBinary() -> bytesToAciString(envelope.sourceServiceIdBinary.toByteArray())
            envelope.hasSourceServiceId() -> envelope.sourceServiceId
            else -> ""
        }
        val destAci = when {
            envelope.hasDestinationServiceIdBinary() -> bytesToAciString(envelope.destinationServiceIdBinary.toByteArray())
            envelope.hasDestinationServiceId() -> envelope.destinationServiceId
            else -> null
        }
        val serverGuidBinary = if (envelope.hasServerGuidBinary()) envelope.serverGuidBinary.toByteArray() else null
        val serverGuid = when {
            serverGuidBinary != null -> bytesToUuidString(serverGuidBinary)
            envelope.hasServerGuid() -> envelope.serverGuid
            else -> null
        }
        return SignalEnvelope(
            type = if (envelope.hasType()) envelope.type else SignalServiceProtos.Envelope.Type.UNKNOWN,
            sourceAci = sourceAci,
            sourceDevice = if (envelope.hasSourceDeviceId()) envelope.sourceDeviceId else 1,
            timestamp = if (envelope.hasClientTimestamp()) envelope.clientTimestamp else if (envelope.hasServerTimestamp()) envelope.serverTimestamp else 0L,
            content = if (envelope.hasContent()) envelope.content.toByteArray() else ByteArray(0),
            serverGuid = serverGuid,
            serverGuidBinary = serverGuidBinary,
            serverTimestamp = if (envelope.hasServerTimestamp()) envelope.serverTimestamp else 0L,
            destinationAci = destAci,
            story = if (envelope.hasStory()) envelope.story else false,
            urgent = if (envelope.hasUrgent()) envelope.urgent else true,
            rawEnvelope = envelope,
        )
    }

    fun tryParseEnvelopeFromWsBytes(wsBytes: ByteArray): SignalEnvelope? {
        val wsMessage = parseWebSocketMessage(wsBytes) ?: return null
        if (wsMessage.type != WebSocketMessage.Type.REQUEST) return null
        if (!wsMessage.hasRequest()) return null
        val req = wsMessage.request
        val path = if (req.hasPath()) req.path else ""
        val isMessage = path.contains("/api/v1/message") || path.contains("/v1/messages")
        val isQueueEmpty = path.contains("/api/v1/queue/empty") || path.contains("/v1/queue/empty")
        if (!isMessage && !isQueueEmpty) {
            if (path.contains("keepalive")) return null
            if (!req.hasBody()) return null
        }
        if (isQueueEmpty) return null
        val envelope = parseEnvelopeFromRequest(req) ?: return null
        return toSignalEnvelope(envelope)
    }

    fun getRequestId(wsBytes: ByteArray): Long? {
        val ws = parseWebSocketMessage(wsBytes) ?: return null
        return if (ws.hasRequest() && ws.request.hasId()) ws.request.id else null
    }

    fun getRequestId(wsMessage: WebSocketMessage): Long? =
        if (wsMessage.hasRequest() && wsMessage.request.hasId()) wsMessage.request.id else null

    fun isQueueEmptySignal(wsBytes: ByteArray): Boolean {
        val ws = parseWebSocketMessage(wsBytes) ?: return false
        if (!ws.hasRequest()) return false
        val path = if (ws.request.hasPath()) ws.request.path else return false
        return path.contains("queue/empty")
    }

    /**
     * Pad to a multiple of [PADDING_BLOCK_SIZE] with a trailing `0x80` terminator followed by zeroes.
     * Applied to the serialized Content before encryption. Port of the official client's
     * `PushTransportDetails.getPaddedMessageBody`.
     */
    fun padMessageBody(body: ByteArray): ByteArray {
        // The +1/-1 leaves the cipher room for its own single padding byte; without it the cipher
        // adds a full extra block.
        val padded = ByteArray(paddedLength(body.size + 1) - 1)
        body.copyInto(padded)
        padded[body.size] = TERMINATOR
        return padded
    }

    /**
     * Strip [padMessageBody]'s terminator and trailing zeroes from a decrypted plaintext. Returns the
     * input unchanged when the padding is malformed, matching the official client rather than
     * guessing at a length.
     */
    fun stripMessagePadding(padded: ByteArray): ByteArray {
        var paddingStart = 0
        for (i in padded.indices.reversed()) {
            if (padded[i] == TERMINATOR) {
                paddingStart = i
                break
            }
            if (padded[i] != 0x00.toByte()) {
                Log.w(TAG, "malformed padding, leaving message unstripped")
                return padded
            }
        }
        return padded.copyOfRange(0, paddingStart)
    }

    private fun paddedLength(length: Int): Int {
        val withTerminator = length + 1
        val blocks = (withTerminator + PADDING_BLOCK_SIZE - 1) / PADDING_BLOCK_SIZE
        return blocks * PADDING_BLOCK_SIZE
    }

    fun parseContent(plaintext: ByteArray): SignalServiceProtos.Content? = try {
        if (plaintext.isEmpty()) return null
        SignalServiceProtos.Content.parseFrom(plaintext)
    } catch (e: InvalidProtocolBufferException) {
        Log.w(TAG, "parseContent failed: ${e.message}")
        null
    }

    fun classifyContent(content: SignalServiceProtos.Content): ParsedContent {
        return when {
            content.hasDataMessage() -> ParsedContent.Data(content.dataMessage, content)
            content.hasReceiptMessage() -> ParsedContent.Receipt(content.receiptMessage)
            content.hasTypingMessage() -> ParsedContent.Typing(content.typingMessage)
            content.hasEditMessage() -> ParsedContent.Edit(content.editMessage)
            content.hasCallMessage() -> ParsedContent.Call(content.callMessage)
            content.hasNullMessage() -> ParsedContent.Null(content.nullMessage)
            content.hasSyncMessage() -> ParsedContent.Sync(content.syncMessage)
            content.hasStoryMessage() -> ParsedContent.Story(content.storyMessage)
            content.hasDecryptionErrorMessage() -> ParsedContent.DecryptionError(content.decryptionErrorMessage.toByteArray())
            else -> ParsedContent.Unknown(content)
        }
    }

    fun parseAndClassifyContent(plaintext: ByteArray): ParsedContent? {
        val content = parseContent(plaintext) ?: return null
        return classifyContent(content)
    }

    fun buildDataMessageContent(
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        groupMasterKey: ByteArray? = null,
        groupRevision: Int? = null,
        groupChange: ByteArray? = null,
        bodyRanges: List<SignalServiceProtos.BodyRange> = emptyList(),
    ): SignalServiceProtos.Content {
        val dmBuilder = SignalServiceProtos.DataMessage.newBuilder().setBody(body).setTimestamp(timestamp)
        if (groupMasterKey != null) {
            val g = SignalServiceProtos.GroupContextV2.newBuilder().setMasterKey(ByteString.copyFrom(groupMasterKey))
            if (groupRevision != null) g.setRevision(groupRevision)
            if (groupChange != null) g.setGroupChange(ByteString.copyFrom(groupChange))
            dmBuilder.setGroupV2(g)
        }
        if (bodyRanges.isNotEmpty()) dmBuilder.addAllBodyRanges(bodyRanges)
        return SignalServiceProtos.Content.newBuilder().setDataMessage(dmBuilder).build()
    }

    fun buildReceiptContent(
        type: SignalServiceProtos.ReceiptMessage.Type,
        timestamps: List<Long>,
    ): SignalServiceProtos.Content {
        val receipt = SignalServiceProtos.ReceiptMessage.newBuilder().setType(type)
        timestamps.forEach { receipt.addTimestamp(it) }
        return SignalServiceProtos.Content.newBuilder().setReceiptMessage(receipt).build()
    }

    fun buildTypingContent(
        action: SignalServiceProtos.TypingMessage.Action,
        timestamp: Long = System.currentTimeMillis(),
        groupId: ByteArray? = null,
    ): SignalServiceProtos.Content {
        val typing = SignalServiceProtos.TypingMessage.newBuilder().setTimestamp(timestamp).setAction(action)
        if (groupId != null) typing.setGroupId(ByteString.copyFrom(groupId))
        return SignalServiceProtos.Content.newBuilder().setTypingMessage(typing).build()
    }

    fun buildEditContent(
        targetSentTimestamp: Long,
        newDataMessage: SignalServiceProtos.DataMessage,
    ): SignalServiceProtos.Content {
        val edit = SignalServiceProtos.EditMessage.newBuilder()
            .setTargetSentTimestamp(targetSentTimestamp)
            .setDataMessage(newDataMessage)
        return SignalServiceProtos.Content.newBuilder().setEditMessage(edit).build()
    }

    fun generateMessageId(): String = UUID.randomUUID().toString()

    fun toConversationId(sourceAci: String, groupMasterKey: ByteArray?): String {
        return when {
            groupMasterKey != null -> "group:${groupMasterKey.joinToString("") { "%02x".format(it) }.take(16)}"
            sourceAci.isNotEmpty() -> sourceAci
            else -> "unknown"
        }
    }

    fun toConversationId(sourceAci: String, groupId: String?): String = when {
        !groupId.isNullOrEmpty() -> "group:$groupId"
        sourceAci.isNotEmpty() -> sourceAci
        else -> "unknown"
    }

    private fun bytesToAciString(bytes: ByteArray): String {
        if (bytes.size == 16) return bytesToUuidString(bytes)
        if (bytes.size == 17) return bytesToUuidString(bytes.copyOfRange(1, 17))
        return try { String(bytes, Charsets.UTF_8).takeIf { it.isNotBlank() } ?: bytesToUuidString(bytes.take(16).toByteArray()) } catch (_: Exception) { "" }
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
            UUID(msb, lsb).toString()
        } catch (_: Exception) { "" }
    }

    @Deprecated("Use binary WebSocketMessage helpers")
    data class WsFrame(
        val type: String,
        val verb: String?,
        val path: String?,
        val id: String?,
        val status: Int?,
        val body: ByteArray?,
        val raw: String,
    )

    @Deprecated("Use parseWebSocketMessage(bytes)")
    fun parseWsFrame(text: String): WsFrame? = try {
        val obj = org.json.JSONObject(text)
        val type = obj.optString("type", "")
        val bodyB64 = obj.optString("body", "")
        val body = if (bodyB64.isNotEmpty()) runCatching { android.util.Base64.decode(bodyB64, android.util.Base64.NO_WRAP) }.getOrNull() else null
        WsFrame(type = type, verb = if (obj.has("verb")) obj.optString("verb") else null, path = if (obj.has("path")) obj.optString("path") else null, id = if (obj.has("id")) obj.optString("id") else null, status = if (obj.has("status")) obj.optInt("status") else null, body = body, raw = text)
    } catch (e: Exception) {
        Log.w(TAG, "parseWsFrame failed: ${e.message}")
        null
    }

    @Deprecated("Use encodeWsAck(id: Long)")
    fun buildWsResponse(id: String, status: Int = 200): String {
        val o = org.json.JSONObject()
        o.put("type", "RESPONSE")
        o.put("id", id)
        o.put("status", status)
        return o.toString()
    }

    @Deprecated("Use parseEnvelope / toSignalEnvelope")
    fun tryParseEnvelope(frame: WsFrame): SignalEnvelope? = null
}
