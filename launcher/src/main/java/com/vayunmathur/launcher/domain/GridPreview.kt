package com.vayunmathur.launcher.domain

/**
 * Where everything on a page goes if a drag is released at a particular cell.
 *
 * [target] is where the dragged item lands. [displaced] is every *other* item that has to move to
 * make room, as `id` to its new rect; items absent from it stay where they are.
 *
 * One value for both the live preview and the commit, which is the point: the page renders the
 * plan while the finger is down and the drop writes the same plan, so nothing jumps at the end.
 */
data class DropPlan(val target: CellRect, val displaced: Map<Long, CellRect> = emptyMap())

/**
 * Works out what a drop would do to a page, without touching the page.
 *
 * Separate from [GridPlacer] because the question is different. `GridPlacer` answers "where is
 * there a hole", which is what an install or a regrid needs. This answers "what if the user
 * insists on *this* cell" — the dragged item takes the cell under the finger and its neighbours
 * slide out of the way, which is what makes a launcher grid feel like it is being rearranged
 * rather than being told where there is room. Launcher3 calls the same idea a reorder.
 *
 * Pure, and therefore the part of dragging that can be tested without a device.
 */
object GridPreview {

    /**
 * The plan for dropping [draggedId] with span and position [wanted], or null when the page
     * cannot take it at all.
     *
     * Four outcomes, in the order they are tried:
     *
     *  1. [wanted] is free — nothing else moves.
     *  2. [direction] is known and the occupants can be pushed that way — [GridReorder.pushAlong],
     *     which is what makes a reorder read as the row shuffling along under the finger.
     *  3. They cannot, but every occupant has *somewhere* to go — they are pushed there. This is
     *     Launcher3's own fallback, and it is also the whole answer when the direction is unknown,
     *     which is the case for a drop that never moved.
     *  4. An occupant has nowhere to go — the push is abandoned *whole* rather than in part, and
     *     the item falls back to the nearest hole. Half a push would leave the page rearranged
     *     for no reason, or worse, leave an item with no cell at all.
     */
    fun plan(
        spec: GridSpec,
        placed: Map<Long, CellRect>,
        draggedId: Long?,
        wanted: CellRect,
        direction: PushDirection? = null,
    ): DropPlan? {
        val others = placed.filterKeys { it != draggedId }

        val settled = CellOccupancy.of(spec, others.values)
        if (settled.isFree(wanted)) return DropPlan(wanted)

        if (direction != null) {
            GridReorder.pushAlong(spec, others, wanted, direction)?.let {
                return DropPlan(wanted, it)
            }
        }

        pushAside(spec, others, wanted)?.let { return DropPlan(wanted, it) }

        return GridPlacer.findNearestVacant(settled, wanted)?.let { DropPlan(it) }
    }

    /**
     * Where each occupant of [wanted] goes, or null if any of them has nowhere to go.
     *
     * Occupants are moved in reading order, and each one's new cell is marked before the next is
     * placed, so two items pushed out of the same widget-sized hole cannot be sent to the same
     * cell. That ordering is also what makes the result stable frame to frame while the finger
     * hovers, which a preview has to be.
     */
    private fun pushAside(
        spec: GridSpec,
        others: Map<Long, CellRect>,
        wanted: CellRect,
    ): Map<Long, CellRect>? {
        val occupants = others.entries
            .filter { it.value.overlaps(wanted) }
            .sortedWith(compareBy({ it.value.cellY }, { it.value.cellX }))

        val occupancy = CellOccupancy(spec)
        others.forEach { (id, rect) -> if (occupants.none { it.key == id }) occupancy.mark(rect) }
        // The dragged item is taking this, so nothing may be pushed into it.
        occupancy.mark(wanted)

        val moves = mutableMapOf<Long, CellRect>()
        occupants.forEach { (id, rect) ->
            val to = GridPlacer.findNearestVacant(occupancy, rect) ?: return null
            occupancy.mark(to)
            moves[id] = to
        }
        return moves
    }
}
