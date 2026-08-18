package com.vayunmathur.launcher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.LauncherTuning
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Motion
import kotlinx.coroutines.launch

/**
 * The dragged item, following the finger.
 *
 * Drawn once at the root of the home screen rather than by moving the item within its own cell:
 * a drag crosses pages and containers, and a child of one page's [CellLayout] cannot be drawn
 * outside it. The real item stays put and dimmed while this floats above everything.
 *
 * The two animations here are what make a drag read as picking something up and putting it down:
 *
 *  - **Lift.** The icon grows by [LauncherTuning.DragLiftDp] over the frames after the long press
 *    fires, rather than appearing already enlarged, so the moment of grabbing it is visible. A
 *    fixed dp rather than a factor, as Launcher3's `pre_drag_view_scale` is: a large icon should
 *    not be magnified more than a small one.
 *  - **Settle.** On release it travels to [LauncherDragController.landing] and shrinks back to
 *    grid size, then tells the controller the drag is over. Launcher3's `DragLayer` does the same
 *    thing over the same 285ms, and it is what stops a drop from looking like a teleport.
 *
 * All of this state is scoped to one drag: the composable returns before remembering anything when
 * nothing is being dragged, so each new drag starts from a scale of one.
 */
@Composable
fun DragLayer(controller: LauncherDragController, iconScale: Float, modifier: Modifier = Modifier) {
    val payload = controller.payload ?: return
    val density = LocalDensity.current
    val size = LauncherIconSize * iconScale
    val landing = controller.landing

    val lift = remember { Animatable(1f) }
    val settle = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    // The grown size as a factor of this icon's own size, since a `graphicsLayer` scales rather
    // than resizes.
    val lifted = 1f + with(density) { LauncherTuning.DragLiftDp.dp.toPx() } /
        with(density) { size.toPx() }

    LaunchedEffect(Unit) {
        lift.animateTo(lifted, Motion.open(Motion.PopupOpenMillis))
    }

    LaunchedEffect(landing) {
        val destination = landing ?: return@LaunchedEffect
        settle.snapTo(controller.position - liftOffset(density))
        // Shrinking as it arrives, so the icon is grid-sized by the time it is over its cell.
        launch { lift.animateTo(1f, Motion.drop()) }
        settle.animateTo(destination.center, Motion.drop())
        controller.settled()
    }

    // `isRunning` rather than `landing != null`, because the frame that first sees a landing is
    // composed before the effect above has snapped the animation to the finger.
    val center = if (settle.isRunning) settle.value else controller.position - liftOffset(density)

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val half = with(density) { size.toPx() } / 2f
                    translationX = center.x - half
                    translationY = center.y - half
                    scaleX = lift.value
                    scaleY = lift.value
                    transformOrigin = TransformOrigin.Center
                }
                .size(size),
        ) {
            if (payload.type == LauncherItemType.APPWIDGET) {
                // A widget has no icon to lift, and rendering a live hosted view under the
                // finger would mean two views for one appWidgetId.
                Box(
                    modifier = Modifier
                        .shadow(DRAG_ELEVATION, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                )
            } else {
                LauncherAppIcon(
                    key = payload.key,
                    label = payload.label,
                    scale = iconScale,
                    showLabel = false,
                )
            }
        }
    }
}

/** Held slightly above the fingertip that is placing it, so the item is not hidden by it. */
private fun liftOffset(density: Density): Offset =
    Offset(0f, with(density) { DRAG_LIFT.toPx() })

/** Held clear of the fingertip, so the item being placed is not hidden by the hand placing it. */
private val DRAG_LIFT = 24.dp

/** Only the widget placeholder is an opaque shape, so it is the only thing that can cast one. */
private val DRAG_ELEVATION = 8.dp
