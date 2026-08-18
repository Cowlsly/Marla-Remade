@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.vayunmathur.launcher.ui.components

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Density
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.library.ui.Motion

/**
 * Absolute cell placement for one item, read by [CellLayout].
 *
 * Parent data rather than a modifier chain that positions the child itself: the layout has
 * to measure each child with the *fixed* size its span works out to. A `Box` with an
 * absolute `offset` would leave children measuring themselves against the whole page, and
 * an `AppWidgetHostView` sized that way lays itself out for the wrong dimensions.
 *
 * This is also where every grid animation comes from. A reorder push, a drop settling, a widget
 * resize and a regrid are all one event — *this child's rect changed* — so instead of an animator
 * per case, the change is animated by [androidx.compose.animation.animateBounds] against the
 * [LookaheadScope] that [CellLayout] provides. The fixed-constraint measurement above becomes the
 * lookahead pass and is otherwise untouched, so that sizing guarantee still holds.
 *
 * Composable, because that animation has state to remember. Used outside a [CellLayout] it
 * degrades to plain parent data.
 */
@Composable
fun Modifier.cell(rect: CellRect): Modifier {
    val placement = this.then(CellParentData(rect))
    val lookahead = LocalCellLookahead.current ?: return placement
    return placement.animateBounds(lookahead, boundsTransform = CellBounds)
}

internal class CellParentData(val rect: CellRect) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@CellParentData

    override fun equals(other: Any?): Boolean = other is CellParentData && other.rect == rect

    override fun hashCode(): Int = rect.hashCode()
}

/**
 * The scope cell children animate against, published by [CellLayout] rather than passed down so
 * [cell] stays a one-argument modifier at every call site.
 */
internal val LocalCellLookahead = compositionLocalOf<LookaheadScope?> { null }

/** One duration for every cell move, whatever caused it — as `CellLayout` does in Launcher3. */
private val CellBounds = BoundsTransform { _, _ -> Motion.reorder() }
