package com.vayunmathur.launcher.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.domain.DropBarSecondary
import com.vayunmathur.launcher.domain.DropBarTargets
import com.vayunmathur.library.ui.ExpandVisibility
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconInfo
import com.vayunmathur.library.ui.IconUninstall
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text

/**
 * The bar of drop targets, shown at the top only while something is being dragged.
 *
 * A bar rather than the single Remove target it used to be, because Launcher3 has a bar: Remove is
 * always there, and beside it sits whichever second target makes sense for what is in the air —
 * Uninstall for an app the user installed, App info for one they cannot uninstall, and nothing at
 * all for a widget or a folder. Which is which is [DropBarTargets]' decision, so the rule is
 * testable rather than a `when` buried in a composable.
 */
@Composable
fun DropBar(
    controller: LauncherDragController,
    onRemove: (Long) -> Unit,
    onUninstall: (Long) -> Unit,
    onAppInfo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    ExpandVisibility(visible = controller.isDragging, modifier = modifier) {
        val payload = controller.payload
        val secondary = payload?.let {
            // An app straight out of the drawer was never on the workspace, so nothing about it can
            // be removed, uninstalled or inspected from here.
            if (it.itemId == null) null else DropBarTargets.secondaryFor(it.type, it.canUninstall)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DropBarTarget(
                key = "drop-remove",
                priority = REMOVE_PRIORITY,
                label = "Remove",
                icon = { IconDelete() },
                onDrop = onRemove,
            )

            when (secondary) {
                DropBarSecondary.Uninstall -> DropBarTarget(
                    key = "drop-uninstall",
                    priority = REMOVE_PRIORITY,
                    label = "Uninstall",
                    icon = { IconUninstall() },
                    onDrop = onUninstall,
                )
                DropBarSecondary.AppInfo -> DropBarTarget(
                    key = "drop-app-info",
                    priority = REMOVE_PRIORITY,
                    label = "App info",
                    icon = { IconInfo() },
                    onDrop = onAppInfo,
                )
                null -> Unit
            }
        }
    }
}

/**
 * One target in the bar.
 *
 * Lights up while the drag is over it, because the whole bar shares one priority and a target the
 * finger is not over must not look armed.
 */
@Composable
private fun RowScope.DropBarTarget(
    key: String,
    priority: Int,
    label: String,
    icon: @Composable () -> Unit,
    onDrop: (Long) -> Unit,
) {
    val controller = LocalLauncherDrag.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val armed = MaterialTheme.colorScheme.errorContainer
    val idle = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(TARGET_CORNER))
            // Drawn from the finger's position rather than composed from it: reading the position at
            // composition scope would recompose this bar on every frame of every drag.
            .drawBehind {
                val active = controller.isDragging && bounds.contains(controller.position)
                drawRect(if (active) armed else idle)
            }
            .dropTarget(
                key = key,
                priority = priority,
                accepts = { it.itemId != null },
                onDrop = { payload, _ ->
                    val id = payload.itemId
                    if (id == null) {
                        null
                    } else {
                        onDrop(id)
                        // Into the bar rather than nowhere: the item is being thrown away, so it
                        // should be seen arriving here and not flying back to its cell.
                        bounds
                    }
                },
            )
            .onAppWindowBounds { bounds = it }
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Text(label, modifier = Modifier.padding(start = Spacing.sm))
    }
}

private val TARGET_CORNER = 24.dp

/** Beats the page, the hotseat and a folder icon, so dropping on the bar always wins. */
const val REMOVE_PRIORITY = 100
