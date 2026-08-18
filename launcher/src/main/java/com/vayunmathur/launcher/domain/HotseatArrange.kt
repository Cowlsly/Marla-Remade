package com.vayunmathur.launcher.domain

/**
 * The new hotseat order after an insert, and whoever it pushed out.
 *
 * [ranks] is every id that stays, with the rank it stays at. [evicted] is the id that no longer
 * fits, or null when the row had room.
 */
data class HotseatPlan(val ranks: Map<Long, Int>, val evicted: Long? = null)

/**
 * Arranging the one row that is on every page.
 *
 * A rank list rather than a cell grid: the hotseat has fixed slots, so an insert shifts its
 * neighbours along instead of looking for a hole.
 *
 * The eviction is the part that was missing. Renumbering alone leaves more items than there are
 * slots, and the row draws exactly `hotseatSlots` of them — so the overflow was still in the
 * database, still counted as being in the hotseat, and simply never rendered anywhere. An item the
 * user can neither see nor get back is worse than one they watched being pushed out.
 */
object HotseatArrange {

    /**
     * Puts [id] at [toRank], returning where everything ends up.
     *
     * [current] is the row in rank order, and may or may not already contain [id] — reordering
     * within the row and moving in from the workspace are the same operation with the same answer,
     * so both go through here.
     *
     * The tail is what gives way, being the item furthest from the slot the user aimed at.
     */
    fun arrange(current: List<Long>, id: Long, toRank: Int, slots: Int): HotseatPlan {
        val limit = slots.coerceAtLeast(1)
        val without = current.filter { it != id }
        val index = toRank.coerceIn(0, minOf(without.size, limit - 1))
        val inserted = without.toMutableList().apply { add(index, id) }

        val evicted = inserted.lastOrNull()?.takeIf { inserted.size > limit }
        val kept = if (evicted == null) inserted else inserted.dropLast(1)
        return HotseatPlan(
            ranks = kept.withIndex().associate { (rank, item) -> item to rank },
            evicted = evicted,
        )
    }
}
