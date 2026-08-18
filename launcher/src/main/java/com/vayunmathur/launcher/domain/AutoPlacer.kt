package com.vayunmathur.launcher.domain

/**
 * Places items across pages without being told where to put them: first-run seeding, a
 * widget added from the picker, an item promoted out of a collapsing folder, and every
 * item after a grid-size change.
 *
 * The rule throughout is **never drop an item**. Running out of room adds a page; a span
 * too large for the new grid is clamped. Silently discarding something the user placed is
 * the one outcome worse than an ugly layout.
 */
object AutoPlacer {

    /**
     * First page (in ascending order, including a brand new one) with room for [span].
     *
     * [existing] is every item already on the desktop. Pages need not be contiguous — a
     * gap is simply an empty page that everything fits on.
     */
    fun place(spec: GridSpec, existing: List<PagedRect>, span: CellRect): PagedRect {
        val clamped = spec.clampSpan(span)
        val byScreen = existing.groupBy { it.screen }
        val lastScreen = byScreen.keys.maxOrNull() ?: -1
        for (screen in 0..(lastScreen + 1)) {
            val occupancy = CellOccupancy.of(spec, byScreen[screen]?.map { it.rect } ?: emptyList())
            val found = GridPlacer.findFirstVacant(occupancy, clamped.spanX, clamped.spanY)
            if (found != null) return PagedRect(screen, found)
        }
        // Unreachable for any span the grid can hold, since screen lastScreen+1 is empty.
        // A span wider than the grid is impossible even so, and clampSpan has already
        // ruled that out.
        error("no room for span ${clamped.spanX}x${clamped.spanY} on a ${spec.columns}x${spec.rows} grid")
    }

    /**
     * Places [spans] in order, each seeing the ones before it. Returned in the same order
     * as the input.
     */
    fun placeAll(spec: GridSpec, existing: List<PagedRect>, spans: List<CellRect>): List<PagedRect> {
        val placed = existing.toMutableList()
        return spans.map { span ->
            place(spec, placed, span).also { placed.add(it) }
        }
    }

    /**
     * Re-lays out every desktop item for a new grid shape.
     *
     * Reading order is preserved (page, then row, then column) and items are re-placed
     * first-fit, so growing the grid pulls items forward and **shrinking it spills the
     * overflow onto new pages** rather than dropping it. Ranks and containers are not this
     * function's business; only desktop items need regridding, since the hotseat is one
     * row of ranks and folder children have no cell coordinates.
     */
    fun regrid(items: List<GridItem>, to: GridSpec): List<GridItem> {
        val ordered = items.sortedWith(
            compareBy({ it.screen }, { it.rect.cellY }, { it.rect.cellX }),
        )
        val placed = mutableListOf<PagedRect>()
        return ordered.map { item ->
            val target = place(to, placed, item.rect)
            placed.add(target)
            GridItem(item.id, target.screen, target.rect)
        }
    }
}
