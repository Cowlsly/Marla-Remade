package com.vayunmathur.launcher.domain

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Whether a drag is close enough to an icon to fold into it, rather than to reorder past it.
 *
 * The distinction the grid otherwise cannot draw: a cell is not one target but two. Its middle is a
 * folder and the rest of it is a hole in the grid.
 *
 * Launcher3's radius, from `CellLayout.getFolderCreationRadius`, is the mean of two things:
 *
 * ```
 * iconVisibleRadius = ICON_VISIBLE_AREA_FACTOR * iconSizePx / 2   // 0.92, so 0.46 * iconSizePx
 * folderRadius      = (reorderRadius + iconVisibleRadius) / 2
 * ```
 *
 * where `reorderRadius` for a 1x1 cell that could accept a folder is the distance from the icon's
 * centre to the *nearest edge of its cell* — "halfway between the reorder radius and the icon", as
 * the AOSP comment puts it. So the threshold is not a fixed fraction of the icon: it grows with the
 * cell, which matters on a three-column grid where cells are much wider than icons.
 *
 * Distances arrive as a component pair rather than as a point, so this stays free of
 * `androidx.compose` and therefore testable: [dx] and [dy] are the drag position minus the icon's
 * centre, in pixels.
 */
object FolderMerge {

    fun willCreateFolder(
        dx: Float,
        dy: Float,
        iconSizePx: Float,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): Boolean = mergeProgress(dx, dy, iconSizePx, cellWidthPx, cellHeightPx) > 0f

    /**
     * How far into the merge zone the drag is: 0 on or outside the threshold, 1 dead centre.
     *
     * Exposed separately from [willCreateFolder] because Launcher3 does not just decide — it
     * *shows* the decision coming, growing a ring around the icon as the drag closes in. A boolean
     * alone would make folder creation feel like it happened by accident.
     */
    fun mergeProgress(
        dx: Float,
        dy: Float,
        iconSizePx: Float,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): Float {
        val radius = radius(iconSizePx, cellWidthPx, cellHeightPx)
        if (radius <= 0f) return 0f
        // Squared, so no square root is taken until it is known to be needed.
        val distanceSquared = dx * dx + dy * dy
        if (distanceSquared >= radius * radius) return 0f
        return 1f - sqrt(distanceSquared) / radius
    }

    /** The merge radius in pixels, which is what the growing ring is drawn against. */
    fun radius(iconSizePx: Float, cellWidthPx: Float, cellHeightPx: Float): Float {
        // The icon sits centred in its cell, so the nearest cell edge is half the smaller dimension.
        val reorderRadius = min(cellWidthPx, cellHeightPx) / 2f
        val iconVisibleRadius = LauncherTuning.IconVisibleAreaFactor * iconSizePx / 2f
        return (reorderRadius + iconVisibleRadius) / 2f
    }
}
