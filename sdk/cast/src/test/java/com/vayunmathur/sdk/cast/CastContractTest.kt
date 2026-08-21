package com.vayunmathur.sdk.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What can be proved without two devices.
 *
 * The `Surface`-over-`Messenger` hop cannot be reached from a JVM test, and neither can the
 * encoder - so this covers the two things that *can* silently disagree and would be invisible on
 * hardware: the message codes and Bundle keys both halves index by, and the version boundary that
 * decides between "install Cast", "update Cast" and "go".
 */
class CastContractTest {

    @Test
    fun `the class names Cast is addressed by live under the Cast package`() {
        // Built by string concatenation, so a typo here is a ComponentName that resolves to nothing
        // and a bindService that returns false with no explanation.
        assertTrue(CastContract.PICKER_ACTIVITY.startsWith("${CastContract.CAST_PACKAGE}."))
        assertTrue(CastContract.SERVICE_CLASS.startsWith("${CastContract.CAST_PACKAGE}."))
        assertEquals("com.vayunmathur.cast.platform.CastPickerActivity", CastContract.PICKER_ACTIVITY)
        assertEquals("com.vayunmathur.cast.service.ContentCastService", CastContract.SERVICE_CLASS)
    }

    @Test
    fun `the permission is namespaced to Cast, so Cast is what declares it`() {
        // A consumer declares uses-permission against this exact string; a mismatch is a
        // SecurityException at bind time and nothing earlier.
        assertEquals("com.vayunmathur.cast.permission.STREAM_CONTENT", CastContract.PERMISSION)
    }

    @Test
    fun `no two message codes collide`() {
        val codes = listOf(
            CastContract.MSG_OPEN_SESSION,
            CastContract.MSG_CLOSE_SESSION,
            CastContract.MSG_SESSION_READY,
            CastContract.MSG_SESSION_ENDED,
        )
        assertEquals(codes.size, codes.toSet().size, "two Messenger `what` codes are the same")
    }

    @Test
    fun `no two bundle keys collide`() {
        // MSG_SESSION_READY carries the granted geometry, the Surface and the pipe in one Bundle, so
        // a duplicated key would silently overwrite one of them.
        val keys = listOf(
            CastContract.KEY_WIDTH,
            CastContract.KEY_HEIGHT,
            CastContract.KEY_WANT_AUDIO,
            CastContract.KEY_SURFACE,
            CastContract.KEY_AUDIO_FD,
            CastContract.KEY_GRANTED_WIDTH,
            CastContract.KEY_GRANTED_HEIGHT,
            CastContract.KEY_GRANTED_FRAME_RATE,
            CastContract.KEY_RECEIVER_NAME,
            CastContract.KEY_END_REASON,
        )
        assertEquals(keys.size, keys.toSet().size, "two Bundle keys are the same string")
        assertFalse(keys.any { it.isBlank() })
    }

    @Test
    fun `the requested and granted geometry keys are different`() {
        // They are written into the same conversation in opposite directions. If they were the same
        // strings the granted size would look like the requested one and clamping would appear to
        // work while doing nothing.
        assertTrue(CastContract.KEY_WIDTH != CastContract.KEY_GRANTED_WIDTH)
        assertTrue(CastContract.KEY_HEIGHT != CastContract.KEY_GRANTED_HEIGHT)
    }

    @Test
    fun `no two end reasons collide`() {
        val reasons = listOf(
            CastContract.REASON_CLIENT_CLOSED,
            CastContract.REASON_NO_SESSION,
            CastContract.REASON_RECEIVER_GONE,
            CastContract.REASON_PREEMPTED,
            CastContract.REASON_FAILED,
        )
        assertEquals(reasons.size, reasons.toSet().size, "two end reasons are the same int")
    }

    @Test
    fun `the audio format the pipe expects is the one the RTP timebase uses`() {
        assertEquals(48_000, CastContract.AUDIO_SAMPLE_RATE)
        assertEquals(2, CastContract.AUDIO_CHANNELS)
    }

    // ---- the capability probe ----

    @Test
    fun `an absent Cast is NOT_INSTALLED`() {
        assertEquals(CastClient.Support.NOT_INSTALLED, CastClient.supportFor(null))
    }

    @Test
    fun `a Cast older than the minimum needs an update`() {
        assertEquals(
            CastClient.Support.NEEDS_UPDATE,
            CastClient.supportFor(CastContract.MIN_CAST_VERSION_CODE - 1),
        )
    }

    @Test
    fun `the minimum version itself is ready, not one past it`() {
        // An off-by-one here would tell every user of the build that first shipped the service to go
        // and update it, with no update to find.
        assertEquals(
            CastClient.Support.READY,
            CastClient.supportFor(CastContract.MIN_CAST_VERSION_CODE),
        )
        assertEquals(
            CastClient.Support.READY,
            CastClient.supportFor(CastContract.MIN_CAST_VERSION_CODE + 1),
        )
    }
}
