package com.vayunmathur.share.platform.receive

/** Floor between two progress posts, so the pump is never throttled by the notifier. */
internal const val PROGRESS_MIN_INTERVAL_MS = 500L

/**
 * Decides which of a transfer's state emissions become notification posts.
 *
 * `bytesReceived` advances once per socket read, which for a 3 MB file over Wi-Fi is thousands
 * of emissions. Posting each one would make `notify()` the bottleneck of the transfer, so:
 *
 * - a change of notification *kind* always posts, immediately — those are the four things the
 *   user actually needs to see, and none of them can be dropped;
 * - progress within one kind posts only when the integer percentage changes, and at most once
 *   per [PROGRESS_MIN_INTERVAL_MS].
 *
 * Pure and clock-injected so the gate is testable without Android. `kind` is an ordinal rather
 * than the enum because the enum is private to the notifier.
 */
internal class PostGate {
    private var lastKind = -1
    private var lastPercent = -1
    private var lastPostAt = Long.MIN_VALUE

    /** True if this emission should be posted, recording it as posted when so. */
    fun admit(kind: Int, percent: Int, now: Long): Boolean {
        val due = when {
            kind != lastKind -> true
            percent == lastPercent -> false
            else -> now - lastPostAt >= PROGRESS_MIN_INTERVAL_MS
        }
        if (due) {
            lastKind = kind
            lastPercent = percent
            lastPostAt = now
        }
        return due
    }
}
