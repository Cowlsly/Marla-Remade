package com.vayunmathur.library.map

/**
 * A live-traffic colour push: one fully-resolved ARGB per drawn component segment.
 *
 * [ids] holds each segment's `component_id` (`packed(big_edge_id, seg_index)`, the shared
 * traffic id contract) and [argb] the `0xAARRGGBB` the host wants drawn for it, index for
 * index. The host owns the theme, so the colours are final — the renderer only looks them up.
 *
 * A segment whose id is not in [ids] draws nothing and the basemap road shows through, so a
 * host sends only the segments it has a reading for (omitting no-data rather than encoding it).
 * Passed to [VectorMap]'s `trafficColors`; `null` or an empty table clears the overlay.
 *
 * Equality is by **content**, not array identity, so a host that rebuilds an identical table on
 * recomposition does not trigger a redundant re-push: the effect that drives this is keyed on
 * the value. Mismatched array lengths are truncated to the shorter on the native side.
 */
data class TrafficColorTable(val ids: LongArray, val argb: IntArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrafficColorTable) return false
        return ids.contentEquals(other.ids) && argb.contentEquals(other.argb)
    }

    override fun hashCode(): Int = 31 * ids.contentHashCode() + argb.contentHashCode()

    /** True when there is nothing to draw, which the surface treats the same as `null`. */
    fun isEmpty(): Boolean = ids.isEmpty() || argb.isEmpty()
}
