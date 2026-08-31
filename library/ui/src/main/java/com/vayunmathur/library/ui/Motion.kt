package com.vayunmathur.library.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import kotlin.math.pow

/**
 * The animation scale, and the counterpart to [Spacing].
 *
 * Motion drifts the same way spacing does, and worse: a `tween()` written at a call site carries
 * no intent, so the next screen gets a different duration for the same interaction and the app
 * stops feeling like one app. Named curves and durations make an intentional choice legible.
 *
 * The easings are ports of AOSP Launcher3's `Interpolators`, whose `PathInterpolator` control
 * points map one-to-one onto [CubicBezierEasing]. Reach for the entry that names the interaction
 * rather than adding a new curve; a duration that is genuinely one-off is better as a literal at
 * the call site than as a fake scale entry.
 */
object Motion {

    /**
     * Opening a state: leaves immediately, arrives slowly. Launcher3's `EMPHASIZED_DECELERATE`,
     * used for the drawer rising and a folder growing.
     */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Closing a state. Launcher3's `EMPHASIZED_ACCELERATE`, the mirror of the above. */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Small UI that appears in place - a popup, a chip. Launcher3's `STANDARD`. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** The platform's general-purpose curve. Launcher3's `FAST_OUT_SLOW_IN`. */
    val FastOutSlowIn: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /**
     * A settle that starts fast and eases hard into its resting place - a dropped icon landing.
     * Launcher3's `DECELERATE_1_5`, which is `1 - (1 - t)^3` rather than a bezier.
     */
    val Decelerate: Easing = Easing { fraction -> 1f - (1f - fraction).pow(3f) }

    /**
     * Page snapping. Launcher3's `PagedView.SCROLL` interpolator, `(t - 1)^5 + 1`: much flatter at
     * the end than a bezier, which is what makes a paged workspace feel weighted rather than
     * springy.
     */
    val Scroll: Easing = Easing { fraction -> (fraction - 1f).pow(5f) + 1f }

    /** Neighbours sliding aside during a reorder. `CellLayout.REORDER_ANIMATION_DURATION`. */
    const val ReorderMillis = 150

    /**
     * How long a drag has to dwell over a cell before the reorder it implies is committed.
     * `CellLayout.REORDER_TIMEOUT`.
     *
     * A dwell rather than an immediate push, because a drag crossing the page passes over every
     * cell on the way: reordering on each of them would rearrange the whole page in transit.
     */
    const val ReorderTimeoutMillis = 650L

    /** A dropped item travelling from the finger to its cell. `DragLayer.DRAG_VIEW_DROP_DURATION`. */
    const val DropMillis = 285

    /**
     * The drawer settling open or closed after the finger lets go. Launcher3's
     * `config_allAppsOpenDuration` and `config_allAppsCloseDuration`, phone values — the open is
     * deliberately slow, which is most of why all-apps feels weighted rather than snappy.
     */
    const val DrawerOpenMillis = 600
    const val DrawerCloseMillis = 300

    /** A folder growing out of its icon. `config_materialFolderExpandDuration`. */
    const val FolderOpenMillis = 200
    const val FolderCloseMillis = 200

    /** The long-press menu. Short: it appears under a finger that is already there. */
    const val PopupOpenMillis = 150
    const val PopupCloseMillis = 100

    /** A widget snapping to its new span after a resize handle is dragged a whole cell. */
    const val ResizeMillis = 150

    /**
     * A page settling after a fling. Launcher3's `config_pageSnapAnimationDuration`, phone value.
     *
     * Long, and correctly so: `PagedView` pairs it with the very flat [Scroll] curve, so most of
     * the duration is spent barely moving. A short duration on that curve reads as a snap.
     */
    const val PageSnapMillis = 750

    /** Neighbours sliding aside. */
    fun <T> reorder(): FiniteAnimationSpec<T> = tween(ReorderMillis, easing = Standard)

    /** A dropped item landing in its cell. */
    fun <T> drop(): FiniteAnimationSpec<T> = tween(DropMillis, easing = Decelerate)

    /** A state being opened - drawer up, folder out. */
    fun <T> open(durationMillis: Int): FiniteAnimationSpec<T> =
        tween(durationMillis, easing = EmphasizedDecelerate)

    /** A state being closed. */
    fun <T> close(durationMillis: Int): FiniteAnimationSpec<T> =
        tween(durationMillis, easing = EmphasizedAccelerate)

    /** A page snapping into place, on `PagedView`'s own curve rather than a spring. */
    fun <T> pageSnap(): FiniteAnimationSpec<T> = tween(PageSnapMillis, easing = Scroll)

    /**
     * A movement whose only interesting property is how long it takes, on [FastOutSlowIn].
     *
     * The escape hatch for a genuinely one-off duration, so a screen can say "over this many
     * milliseconds" without reaching for `tween` itself. A duration that shows up twice wants a
     * named entry above instead.
     */
    fun <T> over(durationMillis: Int): FiniteAnimationSpec<T> =
        tween(durationMillis, easing = FastOutSlowIn)
}
