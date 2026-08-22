package com.vayunmathur.communicate.signal

import com.vayunmathur.communicate.data.signal.SignalDeviceMismatch
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire vectors for the send envelope. The details that break silently: `CiphertextMessage` and
 * `Envelope` type numbers are different spaces, message `content` is base64 **with** padding (pre-keys
 * are unpadded), and 409/410 name devices under different keys.
 */
class SignalSendEnvelopeTest {
    private val json = Json

    // CiphertextMessage constants: WHISPER_TYPE = 2, PREKEY_TYPE = 3.
    // Envelope constants: DOUBLE_RATCHET = 1, PREKEY_MESSAGE = 3.
    @Test
    fun mapsCiphertextTypesOntoEnvelopeTypes() {
        assertEquals(1, SignalPayload.envelopeTypeFor(2))
        assertEquals(3, SignalPayload.envelopeTypeFor(3))
    }

    @Test
    fun rejectsCiphertextTypesThatCannotBeSent() {
        // Sender-key (7) and plaintext (8) do not belong on this path.
        assertFailsWith<IllegalArgumentException> { SignalPayload.envelopeTypeFor(7) }
        assertFailsWith<IllegalArgumentException> { SignalPayload.envelopeTypeFor(8) }
    }

    @Test
    fun buildsTheOutgoingPushMessageListShape() {
        val body = SignalPayload.buildPutMessagesBody(
            destinationAci = "aci-1",
            messages = listOf(
                SignalPayload.OutgoingPushMessage(3, 1, 4444, byteArrayOf(1, 2, 3)),
                SignalPayload.OutgoingPushMessage(1, 2, 5555, byteArrayOf(4, 5)),
            ),
            timestamp = 1700000000000L,
        )
        val root = json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        assertEquals("aci-1", root["destination"]?.jsonPrimitive?.content)
        assertEquals(1700000000000L, root["timestamp"]?.jsonPrimitive?.content?.toLong())
        assertEquals("false", root["online"]?.jsonPrimitive?.content)
        assertEquals("true", root["urgent"]?.jsonPrimitive?.content)

        val messages = root["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        val first = messages[0].jsonObject
        assertEquals("3", first["type"]?.jsonPrimitive?.content)
        assertEquals("1", first["destinationDeviceId"]?.jsonPrimitive?.content)
        assertEquals("4444", first["destinationRegistrationId"]?.jsonPrimitive?.content)
        // 3 bytes -> 4 base64 chars, and padding must be present.
        assertEquals("AQID", first["content"]?.jsonPrimitive?.content)
        assertEquals("BAU=", messages[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun putPathCarriesTheStoryFlag() {
        assertEquals("/v1/messages/aci-1?story=false", SignalPayload.putMessagesPath("aci-1"))
        assertEquals("/v1/messages/aci-1?story=true", SignalPayload.putMessagesPath("aci-1", story = true))
    }

    @Test
    fun parses409MismatchedDevices() {
        val m = SignalDeviceMismatch.parse(409, """{"missingDevices":[2,3],"extraDevices":[4]}""")!!
        assertEquals(setOf(2, 3), m.fetch)
        assertEquals(setOf(4), m.archive)
    }

    @Test
    fun parses410StaleDevicesAsArchiveOnly() {
        // Stale sessions are archived, not refetched directly; the retry rebuilds them.
        val m = SignalDeviceMismatch.parse(410, """{"staleDevices":[2]}""")!!
        assertTrue(m.fetch.isEmpty())
        assertEquals(setOf(2), m.archive)
    }

    @Test
    fun returnsNullWhenNoDevicesAreNamed() {
        assertNull(SignalDeviceMismatch.parse(409, """{"missingDevices":[],"extraDevices":[]}"""))
        assertNull(SignalDeviceMismatch.parse(410, """{"staleDevices":[]}"""))
    }

    @Test
    fun returnsNullForOtherStatusesOrJunk() {
        assertNull(SignalDeviceMismatch.parse(400, """{"missingDevices":[1]}"""))
        assertNull(SignalDeviceMismatch.parse(409, "not json"))
    }

    @Test
    fun ignoresTheOtherStatusFieldNames() {
        // A 409 body must not pick up staleDevices, and vice versa.
        assertNull(SignalDeviceMismatch.parse(409, """{"staleDevices":[2]}"""))
        assertNull(SignalDeviceMismatch.parse(410, """{"missingDevices":[2]}"""))
    }
}
