package com.vayunmathur.updater.domain

/**
 * When it is acceptable to reboot a device out from under its owner.
 *
 * A payload is applied to the *inactive* slot, so once it is written the device keeps running
 * normally and only the reboot is left. That reboot is the one genuinely disruptive act in the
 * whole update, and the answer to "when" is: when nobody is using it. Never on a deadline —
 * there is no update urgent enough to justify taking a phone away mid-sentence, and the applied
 * slot will still be there tomorrow.
 *
 * "Idle" is the screen having been off continuously for [MIN_IDLE_MILLIS]. The screen going off
 * is not enough on its own: a phone put down for thirty seconds between messages is off, and
 * rebooting then is exactly the behaviour this is meant to avoid.
 *
 * The caller polls, recording the last time it saw the device in use. That makes this a pure
 * function of two timestamps and survives the process being killed between polls, which over a
 * multi-hour wait it certainly will be.
 */
object IdleRebootPolicy {

    /** Long enough that the device is genuinely put down, not just between glances. */
    const val MIN_IDLE_MILLIS: Long = 30 * 60 * 1000L

    fun shouldReboot(
        /** `PowerManager.isInteractive()` right now. */
        interactive: Boolean,
        /** When the device was last seen interactive; 0 when never observed. */
        lastInteractiveMillis: Long,
        nowMillis: Long,
        minIdleMillis: Long = MIN_IDLE_MILLIS,
    ): Boolean {
        if (interactive) return false
        // No observation yet, so there is no idle span to measure. Wait for a poll that has one
        // rather than treating "unknown" as "idle since the epoch" and rebooting immediately.
        if (lastInteractiveMillis <= 0L) return false
        val idleFor = nowMillis - lastInteractiveMillis
        // Negative means the wall clock moved backwards — an NTP correction, or the user
        // changing the date. The span is meaningless rather than huge; do not act on it.
        if (idleFor < 0L) return false
        return idleFor >= minIdleMillis
    }
}
