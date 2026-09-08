package com.vayunmathur.setupwizard.platform

import com.vayunmathur.setupwizard.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupFlowTest {

    @Test
    fun `owner walks every step in order`() {
        assertEquals(
            listOf(
                Route.Welcome,
                Route.Wifi,
                Route.Location,
                Route.Security,
                Route.Migration,
                Route.Gestures,
                Route.Finish,
            ),
            SetupFlow.steps(isPrimaryUser = true),
        )
    }

    /**
     * A secondary user is setting up a profile on a device that is already provisioned, so the
     * network and the navigation mode are not theirs to change.
     */
    @Test
    fun `secondary user is not offered device-wide steps`() {
        val steps = SetupFlow.steps(isPrimaryUser = false)
        listOf(Route.Wifi, Route.Gestures)
            .forEach { assertTrue(it !in steps, "$it should not be offered to a secondary user") }
    }

    @Test
    fun `both flows start at welcome and end at finish`() {
        listOf(true, false).forEach { primary ->
            val steps = SetupFlow.steps(primary)
            assertEquals(Route.Welcome, steps.first())
            assertEquals(Route.Finish, steps.last())
        }
    }

    @Test
    fun `next walks the whole flow exactly once`() {
        listOf(true, false).forEach { primary ->
            val expected = SetupFlow.steps(primary)
            val walked = generateSequence(Route.Welcome as Route) { SetupFlow.next(primary, it) }
            assertEquals(expected, walked.toList())
        }
    }

    @Test
    fun `there is nothing after the last step`() {
        assertNull(SetupFlow.next(isPrimaryUser = true, current = Route.Finish))
    }

    /**
     * The bootloader warning is a diversion off the welcome step, not a step, so it has no
     * successor of its own - Navigation resumes from Welcome instead.
     */
    @Test
    fun `the bootloader warning is not part of either flow`() {
        listOf(true, false).forEach { primary ->
            assertTrue(Route.OemUnlock !in SetupFlow.steps(primary))
            assertNull(SetupFlow.next(primary, Route.OemUnlock))
        }
    }
}
