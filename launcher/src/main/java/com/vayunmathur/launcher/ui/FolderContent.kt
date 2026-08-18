package com.vayunmathur.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.platform.FolderActions
import com.vayunmathur.launcher.platform.WorkspaceItem
import com.vayunmathur.launcher.ui.components.DragPayload
import com.vayunmathur.launcher.ui.components.LauncherItemIcon
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import com.vayunmathur.launcher.ui.components.dragSource
import com.vayunmathur.launcher.ui.components.dropTarget
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

/**
 * An open folder, as an overlay over the home screen.
 *
 * An overlay rather than a destination, so a child can be dragged straight out onto the grid: the
 * drag never leaves the home screen's single gesture owner, and the children register as ordinary
 * drag sources. Launcher3 adds its `Folder` to the `DragLayer` for the same reason.
 *
 * Two drop behaviours, both from the one long-press gesture:
 *
 *  - Released on a sibling — reorder to that sibling's position.
 *  - Dragged outside the folder's bounds — [onDragLeft] closes the folder, which unregisters these
 *    targets and hands the drag to the page underneath. That is what makes dragging an app out of
 *    a folder work rather than being swallowed by the overlay.
 *
 * It grows out of [anchor] — the bounds of the icon that was tapped — and shrinks back into it, so
 * the sheet is visibly the folder rather than a dialog that happened to open. [progress] is a
 * lambda rather than a value because it is read inside a `graphicsLayer` block: every frame of the
 * animation then redraws without recomposing the grid of children.
 */
@Composable
fun FolderContent(
    folder: WorkspaceItem,
    actions: FolderActions,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
    iconScale: Float = 1f,
    anchor: Rect = Rect.Zero,
    progress: () -> Float = { 1f },
    onOpenItemMenu: (Long, Rect) -> Unit = { _, _ -> },
    onDragLeft: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val drag = LocalLauncherDrag.current
    var sheetBounds by remember { mutableStateOf(Rect.Zero) }

    BackHandler(enabled = true) { onDismiss() }

    // Watched here rather than through the sheet's own drop-target onExit, because by the time the
    // finger leaves the folder the active target is a *child* slot, not the sheet - so the sheet
    // would never be told. Observing the position directly is the only reading that cannot be
    // missed.
    val draggedOutside = drag.isDragging && !sheetBounds.contains(drag.position)
    LaunchedEffect(draggedOutside) {
        if (draggedOutside) onDragLeft()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress() }
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            // Tapping the scrim closes the folder. No indication, because a full-screen ripple
            // reads as a broken button rather than a dismissal.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .padding(Spacing.lg)
                .fillMaxWidth()
                .onGloballyPositioned { sheetBounds = it.boundsInWindow() }
                .dropTarget(
                    key = "folder-${folder.id}",
                    priority = FOLDER_PRIORITY,
                    // Dropping in the folder but not on a child puts the item back where it was,
                    // which is exactly what a null landing rect animates.
                    onDrop = { _, _ -> null },
                )
                // Last in the chain, so the bounds recorded above and the drop target registered
                // above are the sheet's resting geometry rather than a frame of this animation -
                // which would otherwise feed back into the transform being computed from it.
                .graphicsLayer {
                    val shown = progress()
                    val from = if (sheetBounds.width > 0f) anchor.width / sheetBounds.width else 0f
                    val scale = from + (1f - from) * shown
                    scaleX = scale
                    scaleY = scale
                    // Centre on the icon at the start and on its resting place at the end.
                    translationX = (anchor.center.x - sheetBounds.center.x) * (1f - shown)
                    translationY = (anchor.center.y - sheetBounds.center.y) * (1f - shown)
                },
        ) {
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                // Editable in place, because a folder created by a drop has no name and naming it
                // is the first thing anyone wants to do.
                OutlinedTextField(
                    value = folder.label,
                    onValueChange = { actions.rename(folder.id, it) },
                    label = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(FOLDER_COLUMNS),
                    contentPadding = PaddingValues(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.fillMaxWidth().heightIn(max = FOLDER_MAX_HEIGHT),
                ) {
                    itemsIndexed(folder.children, key = { _, child -> child.id }) { index, child ->
                        FolderChild(
                            folder = folder,
                            child = child,
                            rank = index,
                            actions = actions,
                            showLabels = showLabels,
                            iconScale = iconScale,
                            dimmed = drag.payload?.itemId == child.id,
                            onOpenItemMenu = onOpenItemMenu,
                            onDismiss = onDismiss,
                            // A reorder rewrites every rank after the one that moved, so without
                            // this the siblings it displaced appear at their new slots instantly.
                            modifier = Modifier.animateItem(placementSpec = Motion.reorder()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderChild(
    folder: WorkspaceItem,
    child: WorkspaceItem,
    rank: Int,
    actions: FolderActions,
    showLabels: Boolean,
    iconScale: Float,
    dimmed: Boolean,
    onOpenItemMenu: (Long, Rect) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }

    Box(
        modifier = modifier
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .dragSource("folder-child-${child.id}") {
                DragPayload(
                    itemId = child.id,
                    type = child.type,
                    label = child.label,
                    key = child.key,
                    // The child's "cell" is its rank, which is what a sibling drop compares
                    // against to tell a reorder from a release in place.
                    rect = CellRect(rank, 0),
                    origin = ContainerRef.Folder(folder.id),
                    sourceBounds = bounds,
                )
            }
            .dropTarget(
                key = "folder-slot-${child.id}",
                priority = FOLDER_CHILD_PRIORITY,
                onDrop = { payload, _ ->
                    val moved = payload.itemId
                    // Released on itself: nothing to reorder. A long press already opened this
                    // child's popup without starting a drag, so getting here means the finger left
                    // and came back - which is a request for nothing at all.
                    if (moved != null && moved != child.id) {
                        actions.reorderInFolder(folder.id, moved, rank)
                    }
                    // This slot is where the moved child now sits, and where a child that came
                    // back to its own slot already was.
                    bounds
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        LauncherItemIcon(
            item = if (dimmed) child.copy(hidden = true) else child,
            scale = iconScale,
            showLabel = showLabels,
            modifier = Modifier.clickable {
                actions.launchChild(
                    child,
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    bounds.right.toInt(),
                    bounds.bottom.toInt(),
                )
                onDismiss()
            },
        )
    }
}

private const val FOLDER_COLUMNS = 4

/** Tall enough for three rows; past that the folder scrolls rather than filling the screen. */
private val FOLDER_MAX_HEIGHT = 320.dp

/** Above the page and the hotseat, so a drop inside the folder stays inside it. */
private const val FOLDER_PRIORITY = 60
private const val FOLDER_CHILD_PRIORITY = 70

private const val SCRIM_ALPHA = 0.5f
