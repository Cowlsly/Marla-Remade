package com.vayunmathur.launcher.domain

import kotlin.math.abs

/** One of the four ways a drag can push its neighbours. */
enum class PushDirection { Left, Up, Right, Down }

/**
 * Pushing a page's items *along* a drag rather than into the nearest hole.
 *
 * The difference is the whole feel of a reorder. [GridPlacer.findNearestVacant] answers "where is
 * there room", which is right for an install but wrong under a finger: dragging an icon leftwards
 * across a row makes each icon it passes jump to whatever gap happens to be closest, which reads
 * as the page scattering. Launcher3 instead shoves the occupant one cell further in the direction
 * the finger is already travelling, and shoves whatever *that* lands on, and so on — so a row
 * shuffles along like beads on a wire.
 *
 * All-or-nothing on purpose. A cascade that runs out of grid halfway would leave the page
 * rearranged for a move that then does not happen, or worse, two items in one cell. When it cannot
 * be done, [pushAlong] says so and the caller falls back.
 */
object GridReorder {

    /**
     * The direction [to] lies in from [from], or null when the two are the same cell.
     *
     * Quantised to the dominant axis, ties going horizontal, because a cascade needs one direction
     * rather than a vector: pushing diagonally would mean moving each occupant twice. The caller is
     * expected to keep the last non-null answer while the finger holds still, which is what stops
     * the cascade flip-flopping between two directions frame to frame.
     */
    fun directionOf(from: CellRect, to: CellRect): PushDirection? {
        val dx = to.cellX - from.cellX
        val dy = to.cellY - from.cellY
        if (dx == 0 && dy == 0) return null
        return if (abs(dx) >= abs(dy)) {
            if (dx > 0) PushDirection.Right else PushDirection.Left
        } else {
            if (dy > 0) PushDirection.Down else PushDirection.Up
        }
    }

    /**
     * Where each item has to go for [wanted] to be free, pushing along [direction], or null when
     * some item in the chain would end up off the grid.
     *
     * Breadth-first over the chain rather than recursive descent, so the cascade is bounded by the
     * number of items on the page: each one may move at most once, and a second push of the same
     * item aborts the whole plan. Without that, two items shoved into each other's paths would
     * loop.
     */
    fun pushAlong(
        spec: GridSpec,
        others: Map<Long, CellRect>,
        wanted: CellRect,
        direction: PushDirection,
    ): Map<Long, CellRect>? {
        if (!fits(spec, wanted)) return null

        val layout = others.toMutableMap()
        val moved = LinkedHashMap<Long, CellRect>()
        // The item now sitting in each region, so a region does not count as blocking itself.
        val pending = ArrayDeque<Pair<Long?, CellRect>>()
        pending += null to wanted

        while (pending.isNotEmpty()) {
            val (owner, region) = pending.removeFirst()
            val blocking = layout
                .filter { it.key != owner && it.value.overlaps(region) }
                .map { it.key to it.value }
            for ((id, rect) in blocking) {
                if (id in moved) return null
                val to = shiftedClear(rect, region, direction)
                if (!fits(spec, to)) return null
                layout[id] = to
                moved[id] = to
                pending += id to to
            }
        }
        return moved
    }

    /** [rect] moved the fewest whole cells along [direction] that takes it clear of [region]. */
    private fun shiftedClear(rect: CellRect, region: CellRect, direction: PushDirection): CellRect =
        when (direction) {
            PushDirection.Right -> rect.movedTo(region.right, rect.cellY)
            PushDirection.Left -> rect.movedTo(region.cellX - rect.spanX, rect.cellY)
            PushDirection.Down -> rect.movedTo(rect.cellX, region.bottom)
            PushDirection.Up -> rect.movedTo(rect.cellX, region.cellY - rect.spanY)
        }

    private fun fits(spec: GridSpec, rect: CellRect): Boolean =
        rect.cellX >= 0 && rect.cellY >= 0 &&
            rect.right <= spec.columns && rect.bottom <= spec.rows
}
