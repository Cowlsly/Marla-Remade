package com.vayunmathur.communicate.signal

import com.vayunmathur.communicate.data.signal.call.SignalCallMessage
import com.vayunmathur.communicate.data.signal.call.SignalRemote
import com.vayunmathur.communicate.data.signal.call.toContent
import com.vayunmathur.communicate.data.signal.call.toProto
import com.vayunmathur.communicate.data.signal.call.toRingRtc
import org.signal.ringrtc.CallManager
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wire vectors for 1:1 call signaling. The details that matter: offers, answers and ICE updates carry
 * **opaque** V4 protobuf bytes and never SDP (those fields are `reserved`), a batch of ICE candidates
 * shares one call id, and `destinationDeviceId` is omitted for broadcast messages.
 */
class SignalCallMessageTest {
    private val opaque = byteArrayOf(1, 2, 3, 4)

    @Test
    fun offerCarriesOpaqueAndMediaType() {
        val content = SignalCallMessage.Offer(callId = 42L, opaque = opaque, video = true).toContent(null)
        val offer = content.callMessage.offer
        assertEquals(42L, offer.id)
        assertEquals(SignalServiceProtos.CallMessage.Offer.Type.OFFER_VIDEO_CALL, offer.type)
        assertContentEquals(opaque, offer.opaque.toByteArray())
        assertTrue(offer.hasOpaque())
    }

    @Test
    fun audioOfferUsesTheAudioType() {
        val content = SignalCallMessage.Offer(callId = 1L, opaque = opaque, video = false).toContent(null)
        assertEquals(
            SignalServiceProtos.CallMessage.Offer.Type.OFFER_AUDIO_CALL,
            content.callMessage.offer.type,
        )
    }

    @Test
    fun answerCarriesOpaque() {
        val content = SignalCallMessage.Answer(callId = 7L, opaque = opaque).toContent(2)
        assertEquals(7L, content.callMessage.answer.id)
        assertContentEquals(opaque, content.callMessage.answer.opaque.toByteArray())
    }

    @Test
    fun iceBatchSharesOneCallIdAndKeepsEveryCandidate() {
        val candidates = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        val content = SignalCallMessage.Ice(callId = 9L, candidates = candidates).toContent(null)
        val updates = content.callMessage.iceUpdateList
        assertEquals(3, updates.size)
        assertTrue(updates.all { it.id == 9L })
        assertContentEquals(candidates.map { it.first() }, updates.map { it.opaque.toByteArray().first() })
    }

    @Test
    fun hangupCarriesTypeAndExcludedDevice() {
        val content = SignalCallMessage.Hangup(
            callId = 5L,
            type = CallManager.HangupType.DECLINED,
            deviceId = 3,
        ).toContent(null)
        val hangup = content.callMessage.hangup
        assertEquals(5L, hangup.id)
        assertEquals(SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_DECLINED, hangup.type)
        assertEquals(3, hangup.deviceId)
    }

    @Test
    fun busyCarriesOnlyTheCallId() {
        val content = SignalCallMessage.Busy(callId = 11L).toContent(null)
        assertEquals(11L, content.callMessage.busy.id)
    }

    @Test
    fun destinationDeviceIdIsOmittedForBroadcast() {
        // Broadcast means every device; setting the field would make recipients drop it.
        val broadcast = SignalCallMessage.Offer(1L, opaque, false).toContent(null)
        assertFalse(broadcast.callMessage.hasDestinationDeviceId())

        val targeted = SignalCallMessage.Answer(1L, opaque).toContent(4)
        assertTrue(targeted.callMessage.hasDestinationDeviceId())
        assertEquals(4, targeted.callMessage.destinationDeviceId)
    }

    @Test
    fun hangupTypesRoundTripThroughTheProto() {
        for (type in CallManager.HangupType.values()) {
            assertEquals(type, type.toProto().toRingRtc(), "round-trip failed for $type")
        }
    }

    @Test
    fun remoteComparesByAciCaseInsensitively() {
        val a = SignalRemote("3F0E5A2C-1111-2222-3333-444455556666")
        val b = SignalRemote("3f0e5a2c-1111-2222-3333-444455556666")
        assertTrue(a.recipientEquals(b))
        assertFalse(a.recipientEquals(SignalRemote("7c1d9b4e-aaaa-bbbb-cccc-ddddeeeeffff")))
    }
}
