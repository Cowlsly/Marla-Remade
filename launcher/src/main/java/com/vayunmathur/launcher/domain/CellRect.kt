package com.vayunmathur.launcher.domain

/**
 * A rectangle of grid cells, in cell units.
 *
 * The grid is the launcher's coordinate system: an icon is 1x1, a widget is whatever
 * span it was given. Everything about placement is expressed in these rather than in
 * pixels, so all of it is testable without a device.
 */
data class CellRect(val cellX: Int, val cellY: Int, val spanX: Int = 1, val spanY: Int = 1) {
    /** Exclusive right edge. */
    val right: Int get() = cellX + spanX

    /** Exclusive bottom edge. */
    val bottom: Int get() = cellY + spanY

    val area: Int get() = spanX * spanY

    fun movedTo(x: Int, y: Int): CellRect = copy(cellX = x, cellY = y)

    fun overlaps(other: CellRect): Boolean =
        cellX < other.right && other.cellX < right && cellY < other.bottom && other.cellY < bottom
}

/** The shape of one grid. [hotseatSlots] is the number of columns in the bottom row. */
data class GridSpec(val columns: Int, val rows: Int, val hotseatSlots: Int = columns) {
    val cellsPerPage: Int get() = columns * rows

    /**
     * Shrinks a span so it can actually be placed on this grid. A widget declaring 4x2
     * cannot go on a 3-column grid, and providers' min/max span metadata is unreliable
     * enough that clamping is the only safe response.
     */
    fun clampSpan(rect: CellRect): CellRect = rect.copy(
        spanX = rect.spanX.coerceIn(1, columns),
        spanY = rect.spanY.coerceIn(1, rows),
    )
}

/** A [CellRect] together with the page it sits on. */
data class PagedRect(val screen: Int, val rect: CellRect)

/** An item reduced to just its identity and placement, which is all the grid logic needs. */
data class GridItem(val id: Long, val screen: Int, val rect: CellRect)
