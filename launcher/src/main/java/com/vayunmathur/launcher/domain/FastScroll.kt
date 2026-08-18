package com.vayunmathur.launcher.domain

/**
 * Turning a finger's position on the A-Z strip into a section, and back again.
 *
 * The arithmetic of a fast scroller, which is easy to get subtly wrong and impossible to test on a
 * device: the strip's ends have to be reachable, so the first and last sections must be selectable
 * at fraction 0 and 1 exactly, and a finger dragged past either end must clamp rather than wrap.
 */
object FastScroll {

    /** The section [fraction] of the way down a strip of [sections], or null when there are none. */
    fun sectionAt(fraction: Float, sections: Int): Int? {
        if (sections <= 0) return null
        // Scaled by the count rather than by count-1, so every section owns an equal band of the
        // strip; the last band would otherwise be unreachable except at exactly 1.0.
        val index = (fraction.coerceIn(0f, 1f) * sections).toInt()
        return index.coerceIn(0, sections - 1)
    }

    /** Where section [index] sits along the strip, as the centre of its band. */
    fun fractionOf(index: Int, sections: Int): Float {
        if (sections <= 0) return 0f
        val clamped = index.coerceIn(0, sections - 1)
        return (clamped + 0.5f) / sections
    }
}
