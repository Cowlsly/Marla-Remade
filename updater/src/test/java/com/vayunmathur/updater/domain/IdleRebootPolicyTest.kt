package com.vayunmathur.updater.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdleRebootPolicyTest {

    private val minute = 60_000L
    private val idle = IdleRebootPolicy.MIN_IDLE_MILLIS

    @Test
    fun `an interactive device is never rebooted`() {
        assertFalse(
            IdleRebootPolicy.shouldReboot(
                interactive = true,
                lastInteractiveMillis = 0L,
                nowMillis = idle * 10,
            ),
        )
    }

    @Test
    fun `a device idle for long enough is rebooted`() {
        assertTrue(
            IdleRebootPolicy.shouldReboot(
                interactive = false,
                lastInteractiveMillis = 1_000_000L,
                nowMillis = 1_000_000L + idle,
            ),
        )
    }

    @Test
    fun `a device only briefly idle is left alone`() {
        // A phone put down between messages has its screen off. Rebooting then is exactly the
        // behaviour the idle wait exists to prevent.
        assertFalse(
            IdleRebootPolicy.shouldReboot(
                interactive = false,
                lastInteractiveMillis = 1_000_000L,
                nowMillis = 1_000_000L + minute,
            ),
        )
    }

    @Test
    fun `the threshold is inclusive`() {
        assertTrue(
            IdleRebootPolicy.shouldReboot(
                interactive = false,
                lastInteractiveMillis = 0L + 1L,
                nowMillis = 1L + idle,
            ),
        )
    }

    @Test
    fun `an unobserved device is not rebooted`() {
        // Zero means "never seen interactive", not "idle since 1970". Treating it as a
        // timestamp would reboot on the very first poll after applying.
        assertFalse(
            IdleRebootPolicy.shouldReboot(
                interactive = false,
                lastInteractiveMillis = 0L,
                nowMillis = System.currentTimeMillis(),
            ),
        )
    }

    @Test
    fun `a negative last-seen timestamp is not rebooted`() {
        assertFalse(
            IdleRebootPolicy.shouldReboot(
                interactive = false,
                lastInteractiveMillis = -5L,
                nowMillis = System.currentTimeMillis(),
            ),
        )
    }

    @Test
    fun `a clock moving backwards does not trigger a reboot`() {
        // An NTP correction or a manual date change makes the span negative. That is a
        // meaningless measurement, not a very long idle period.
        assertFalse(
            IdleRebootPolicy.shouldReboot(
                interactive = false,
                lastInteractiveMillis = 2_000_000L,
                nowMillis = 1_000_000L,
            ),
        )
    }

    @Test
    fun `the threshold is configurable for callers that want a different wait`() {
        assertTrue(
            IdleRebootPolicy.shouldReboot(
                interactive = false,
                lastInteractiveMillis = 1_000_000L,
                nowMillis = 1_000_000L + minute,
                minIdleMillis = minute,
            ),
        )
    }
}
