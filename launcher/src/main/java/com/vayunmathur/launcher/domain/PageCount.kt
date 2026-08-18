package com.vayunmathur.launcher.domain

/**
 * How many pages the workspace has.
 *
 * One line, and it exists only to pin the behaviour: the trailing empty page is there **for the
 * duration of a drag and no longer**. Launcher3 adds it when a drag starts and takes it away when
 * the drag ends, because its purpose is to be somewhere to drag *to*. Kept permanently, it is an
 * empty page the user can page onto for no reason and cannot get rid of.
 */
object PageCount {

    /** [maxOccupied] is the highest page index holding anything, or -1 for an empty workspace. */
    fun pageCount(maxOccupied: Int, isDragging: Boolean): Int {
        val occupied = maxOccupied + 1
        val spare = if (isDragging) 1 else 0
        return (occupied + spare).coerceAtLeast(1)
    }
}
