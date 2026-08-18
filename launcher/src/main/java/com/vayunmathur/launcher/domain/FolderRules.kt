package com.vayunmathur.launcher.domain

/**
 * When one item dropped onto another becomes a folder, and when a folder stops being
 * worth keeping.
 *
 * Pure predicates rather than logic inlined into the drop handler, because "can these
 * merge" is asked twice per drag — once to draw the highlight under the finger, once to
 * commit — and the two answers must never disagree.
 */
object FolderRules {

    /** Below this, a folder is just an icon with extra taps, so it collapses. */
    const val MIN_CHILDREN = 2

    /**
     * Whether dropping [dragged] onto [target] should produce or grow a folder.
     *
     * Widgets are excluded in both directions: they have a span, and a folder child has no
     * cell coordinates to give one. A folder dropped onto another item is a move, not a
     * merge — nesting folders is a maze, and Launcher3 does not allow it either.
     */
    fun canMerge(dragged: LauncherItemType, target: LauncherItemType): Boolean {
        if (dragged == LauncherItemType.APPWIDGET || target == LauncherItemType.APPWIDGET) return false
        if (dragged == LauncherItemType.FOLDER) return false
        return true
    }

    /**
     * Whether [childCount] children left in a folder means it should go away, promoting the
     * remaining child (if any) into the folder's own cell.
     */
    fun shouldCollapse(childCount: Int): Boolean = childCount < MIN_CHILDREN

    /**
     * Default name for a folder created by a drop.
     *
     * Deliberately empty rather than "Folder": an unnamed folder renders as just its
     * children, and a placeholder name is something the user then has to clear.
     */
    const val DEFAULT_FOLDER_TITLE = ""

    /** Ranks are dense and zero-based, so the next child goes after the current highest. */
    fun nextRank(existingRanks: Collection<Int>): Int = (existingRanks.maxOrNull() ?: -1) + 1

    /**
     * Re-numbers [orderedIds] to dense ranks. Called after a removal so a folder that has
     * lost its second child does not leave a hole that the next insert falls into.
     */
    fun denseRanks(orderedIds: List<Long>): Map<Long, Int> =
        orderedIds.withIndex().associate { (index, id) -> id to index }
}
