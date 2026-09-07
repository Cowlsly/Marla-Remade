package com.vayunmathur.setupwizard.platform

import com.vayunmathur.setupwizard.Route

/**
 * The order of the steps.
 *
 * Two orders, not one with skips in it, because the difference is not a matter of degree: a
 * secondary user is setting up a profile on a device that is already provisioned, so the
 * network, the clock, the updater channel and the navigation mode are all settled and not
 * theirs to change. What remains is the handful of things that are per user.
 *
 * [Route.OemUnlock] is deliberately absent from both: it is a diversion off the welcome step,
 * not a step, and continuing from it resumes at whatever follows [Route.Welcome].
 */
object SetupFlow {

    private val primaryUserSteps = listOf(
        Route.Welcome,
        Route.Wifi,
        Route.DateTime,
        Route.Location,
        Route.Security,
        Route.UpdaterSecurityPreview,
        Route.Migration,
        Route.Gestures,
        Route.Finish,
    )

    private val secondaryUserSteps = listOf(
        Route.Welcome,
        Route.Location,
        Route.Security,
        Route.Migration,
        Route.Finish,
    )

    fun steps(isPrimaryUser: Boolean): List<Route> =
        if (isPrimaryUser) primaryUserSteps else secondaryUserSteps

    /**
     * The step after [current], or null when [current] is the last one.
     *
     * Null rather than an exception on the final step: the original threw, but the only caller
     * that could reach it is the finish step, which does not ask.
     */
    fun next(isPrimaryUser: Boolean, current: Route): Route? {
        val steps = steps(isPrimaryUser)
        val index = steps.indexOf(current)
        if (index < 0) return null
        return steps.getOrNull(index + 1)
    }
}
