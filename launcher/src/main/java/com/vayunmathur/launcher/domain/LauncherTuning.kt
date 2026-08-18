package com.vayunmathur.launcher.domain

/**
 * Launcher3's unitless constants.
 *
 * The ones that are neither a duration nor a dp, and therefore have nowhere in
 * [com.vayunmathur.library.ui.Motion] or [com.vayunmathur.library.ui.Spacing] to live. They are
 * here rather than as literals at their call sites because each is a *tuning* decision copied
 * from a specific place in AOSP, and a bare `0.55f` three files away from any explanation is the
 * thing that gets "simplified" to `0.5f` by the next reader.
 *
 * A `domain` object with no logic in it, which is unusual — but these are read from `ui` and from
 * pure code alike, and a second copy in either place is what this prevents.
 */
object LauncherTuning {

    /**
     * How much of an adaptive icon's bitmap is actually visible once it is masked.
     *
     * `IconNormalizer.ICON_VISIBLE_AREA_FACTOR`. Used to size the folder-merge radius against the
     * icon a drag is being folded into rather than against its whole bitmap - see [FolderMerge].
     */
    const val IconVisibleAreaFactor = 0.92f

    /**
     * How much of a page's width counts as its edge, where a dwelling drag flips to the next page.
     * Launcher3 uses a fixed dp region; a fraction travels better across screen widths.
     */
    const val PageEdgeZone = 0.08f

    /**
     * How much bigger a dragged item is than the same item in its cell, in dp added to its edge.
     *
     * Launcher3's `pre_drag_view_scale`, and it really is a dp rather than a factor: the drag view
     * grows by a fixed amount so a large icon is not magnified more than a small one.
     */
    const val DragLiftDp = 6f

    /**
     * What is left of an item in its cell while the drag layer draws a copy of it: nothing.
     *
     * Launcher3 calls `setVisibility(INVISIBLE)` on the dragged child. A dimmed ghost instead reads
     * as two copies of one icon, which is exactly what the drag view already is.
     */
    const val DraggedAlpha = 0f

    /** How far the workspace shrinks behind a fully open drawer, as Launcher3's does. */
    const val WorkspaceScaleBehindDrawer = 0.92f

    /**
     * How far an item provisionally displaced by a drag pulses back towards where it came from, as a
     * fraction of the icon size. `CellLayout.REORDER_PREVIEW_MAGNITUDE`.
     */
    const val ReorderPreviewMagnitude = 0.12f

    /** One period of that pulse. `ReorderPreviewAnimation.PREVIEW_DURATION`. */
    const val ReorderPreviewMillis = 300

    /**
     * How much narrower a pulsing item gets, in pixels off its width.
     * `ReorderPreviewAnimation.CHILD_DIVIDEND`, which AOSP divides by the child's width.
     */
    const val ReorderPreviewShrinkPx = 4f
}
