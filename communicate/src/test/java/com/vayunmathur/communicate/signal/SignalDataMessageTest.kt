package com.vayunmathur.communicate.signal

import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The envelope timestamp and `DataMessage.timestamp` must be the same value: recipients reject a
 * mismatch outright ("Timestamps don't match!"), and the two were previously generated from separate
 * clock reads, so anything slow in between produced a message the peer silently discarded.
 *
 * `requiredProtocolVersion` must also reflect only the features actually used — claiming `CURRENT`
 * on a plain message makes any older client reject it as unsupported.
 */
class SignalDataMessageTest {
    @Test
    fun plainMessageClaimsNoProtocolFloor() {
        val dm = SignalPayload.buildDataMessage(body = "hello", timestamp = 1_700_000_000_000L)
        assertEquals(0, dm.requiredProtocolVersion)
        assertEquals(1_700_000_000_000L, dm.timestamp)
    }

    @Test
    fun reactionClaimsTheReactionsVersion() {
        val reaction = org.whispersystems.signalservice.internal.push.SignalServiceProtos.DataMessage.Reaction
            .newBuilder()
            .setEmoji("x")
            .setTargetSentTimestamp(1L)
            .build()
        val dm = SignalPayload.buildDataMessage(body = "", timestamp = 1L, reaction = reaction)
        // REACTIONS = 4
        assertEquals(4, dm.requiredProtocolVersion)
    }

    @Test
    fun pollClaimsThePollsVersion() {
        val poll = org.whispersystems.signalservice.internal.push.SignalServiceProtos.DataMessage.PollCreate
            .newBuilder()
            .setQuestion("q")
            .build()
        val dm = SignalPayload.buildDataMessage(body = "q", timestamp = 1L, pollCreate = poll)
        // POLLS = 8
        assertEquals(8, dm.requiredProtocolVersion)
    }

    @Test
    fun envelopeTimestampMatchesTheDataMessage() {
        val sent = 1_700_000_123_456L
        val dm = SignalPayload.buildDataMessage(body = "hi", timestamp = sent)
        val content = SignalPayload.buildContentWithDataMessage(dm)
        // What the send path puts on the OutgoingPushMessageList must equal what the peer validates.
        val body = SignalPayload.buildPutMessagesBody(
            destinationAci = "aci-1",
            messages = listOf(SignalPayload.OutgoingPushMessage(1, 1, 1, byteArrayOf(1))),
            timestamp = content.dataMessage.timestamp,
        )
        val root = kotlinx.serialization.json.Json
            .parseToJsonElement(body.toString(Charsets.UTF_8))
            .let { kotlinx.serialization.json.JsonObject(it.jsonObject) }
        assertEquals(sent, root["timestamp"]?.jsonPrimitive?.long)
    }
}
