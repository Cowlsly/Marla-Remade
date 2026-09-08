package com.vayunmathur.updater.domain

/**
 * Whether a fetched [UpdateMetadata] is actually newer than what is running.
 *
 * Compares BUILD DATE, not the build string. The build id is a date-derived number and sorts
 * correctly today, but it is the publisher's identifier and nothing guarantees it stays
 * numerically ordered. `ro.build.date.utc` is a real timestamp and is what the system itself
 * uses to reason about update ordering, so it is the honest comparison.
 *
 * Strictly greater-than, so re-publishing the same build is not offered as an update.
 */
object UpdateComparison {

    fun isNewer(current: CurrentBuild, candidate: UpdateMetadata): Boolean =
        candidate.buildDateUtcSeconds > current.buildDateUtcSeconds

    /**
     * The already-installed build, read from system properties.
     *
     * Kept as a value type rather than read inline so the comparison is unit-testable without a
     * device — [UpdateComparison] is the only logic here worth getting wrong.
     */
    data class CurrentBuild(
        val build: String,
        val buildDateUtcSeconds: Long,
    )
}
