package com.vayunmathur.launcher.domain

/**
 * Finds somewhere on a single page to put something.
 *
 * Used while dragging: the finger names a preferred cell every frame, and the drop
 * preview has to land on a real vacancy rather than wherever the finger happens to be.
 */
object GridPlacer {

    /**
     * The vacant position closest to [preferred], or null when the page cannot hold the
     * span at all.
     *
     * Distance is measured between rectangle origins, and ties break towards the smaller
     * row then column so the result is stable frame to frame — a preview that flickers
     * between two equidistant holes is worse than one that picks the wrong one
     * consistently.
     */
    fun findNearestVacant(occupancy: CellOccupancy, preferred: CellRect): CellRect? {
        if (occupancy.isFree(preferred)) return preferred

        var best: CellRect? = null
        var bestDistance = Int.MAX_VALUE
        for (y in 0..(occupancy.rows - preferred.spanY)) {
            for (x in 0..(occupancy.columns - preferred.spanX)) {
                val candidate = preferred.movedTo(x, y)
                if (!occupancy.isFree(candidate)) continue
                val dx = x - preferred.cellX
                val dy = y - preferred.cellY
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = candidate
                }
            }
        }
        return best
    }

    /** Top-left-most vacancy that fits the span, or null when the page is too full. */
    fun findFirstVacant(occupancy: CellOccupancy, spanX: Int, spanY: Int): CellRect? {
        for (y in 0..(occupancy.rows - spanY)) {
            for (x in 0..(occupancy.columns - spanX)) {
                val candidate = CellRect(x, y, spanX, spanY)
                if (occupancy.isFree(candidate)) return candidate
            }
        }
        return null
    }
}
