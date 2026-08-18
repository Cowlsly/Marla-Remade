package com.vayunmathur.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.platform.ItemMenuActions
import com.vayunmathur.launcher.platform.ItemMenuUiState
import com.vayunmathur.launcher.platform.ShortcutEntry
import com.vayunmathur.launcher.ui.components.DragPayload
import com.vayunmathur.launcher.ui.components.PopupPlacement
import com.vayunmathur.launcher.ui.components.dragSource
import com.vayunmathur.launcher.ui.components.launcherPopupSurface
import com.vayunmathur.launcher.ui.components.onAppWindowBounds
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.IconApps
import com.vayunmathur.library.ui.IconInfo
import com.vayunmathur.library.ui.IconUninstall
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsDivider
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text

/**
 * The long-press popup for one workspace item.
 *
 * The body of a [com.vayunmathur.launcher.ui.components.LauncherPopup], which is a window of its
 * own — and that is what lets it appear while the finger that long-pressed is still down, keep its
 * rows tappable, and let a shortcut row be dragged straight out onto the grid. Positioning and
 * dismissal are not its business: the popup places it against the icon, and the home screen's
 * gesture owner is what closes it.
 *
 * [progress] is a lambda because it is only ever read inside `graphicsLayer` blocks, so the whole
 * open and close animation redraws without recomposing a single row.
 */
@Composable
fun ItemMenuContent(
    state: ItemMenuUiState,
    actions: ItemMenuActions,
    modifier: Modifier = Modifier,
    placement: PopupPlacement = PopupPlacement(),
    progress: () -> Float = { 1f },
    onDismiss: () -> Unit = {},
) {
    val item = state.item ?: return
    var confirmUninstall by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .width(POPUP_WIDTH)
            .launcherPopupSurface(placement, progress),
    ) {
        Text(
            item.label.ifBlank { "Item" },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )

        if (state.shortcuts.isNotEmpty()) {
            state.shortcuts.forEachIndexed { index, shortcut ->
                ShortcutRow(
                    shortcut = shortcut,
                    actions = actions,
                    index = index,
                    progress = progress,
                    onDismiss = onDismiss,
                )
            }
            SettingsDivider()
        }

        if (item.type != LauncherItemType.FOLDER && item.type != LauncherItemType.APPWIDGET) {
            SettingsRow(
                title = "App info",
                leadingContent = { IconInfo() },
                onClick = {
                    actions.openAppInfo(item)
                    onDismiss()
                },
            )
        }

        if (state.canUninstall) {
            SettingsRow(
                title = "Uninstall",
                leadingContent = { IconUninstall() },
                onClick = { confirmUninstall = true },
            )
        }
    }

    if (confirmUninstall) {
        ConfirmDialog(
            title = "Uninstall ${item.label}?",
            message = "The app and its data will be removed from this device.",
            confirmLabel = "Uninstall",
            dismissLabel = "Cancel",
            destructive = true,
            onConfirm = {
                actions.uninstall(item)
                onDismiss()
            },
            onDismiss = { confirmUninstall = false },
        )
    }
}

/**
 * One shortcut, tappable, pinnable and draggable.
 *
 * Draggable across windows: [onAppWindowBounds] and [dragSource] both translate by the popup's own
 * offset, so a row here registers with the drag controller where it really is on screen. The payload
 * carries a null `itemId`, which is what marks it as something to insert rather than move.
 *
 * The explicit "Add" affordance stays: long-press belongs to the gesture owner, so there is no
 * hidden way to pin.
 *
 * The rows arrive one after another rather than together. Their share of [progress] is offset by
 * position, which is the stagger Launcher3 gives its shortcut list.
 */
@Composable
private fun ShortcutRow(
    shortcut: ShortcutEntry,
    actions: ItemMenuActions,
    index: Int,
    progress: () -> Float,
    onDismiss: () -> Unit,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }

    Box(
        modifier = Modifier
            .onAppWindowBounds { bounds = it }
            .dragSource(key = "shortcut-${shortcut.packageName}-${shortcut.shortcutId}") {
                DragPayload(
                    itemId = null,
                    type = LauncherItemType.DEEP_SHORTCUT,
                    label = shortcut.label,
                    shortcut = shortcut,
                    rect = CellRect(0, 0),
                    sourceBounds = bounds,
                )
            }
            .graphicsLayer {
                val row = rowProgress(progress(), index)
                alpha = row
                translationY = (1f - row) * ROW_RISE.toPx()
            },
    ) {
        SettingsRow(
            title = shortcut.label,
            leadingContent = { IconApps() },
            onClick = {
                actions.launchShortcut(shortcut)
                onDismiss()
            },
            trailingContent = {
                Text(
                    "Add",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            actions.pinShortcutToHome(shortcut)
                            onDismiss()
                        }
                        .padding(Spacing.xs),
                )
            },
        )
    }
}

/** Each row's own share of the open, offset by its position in the list. */
private fun rowProgress(shown: Float, index: Int): Float =
    ((shown - index * ROW_STAGGER) / (1f - ROW_STAGGER)).coerceIn(0f, 1f)

/** Small enough that the popup still reads as one object rather than a list assembling itself. */
private const val ROW_STAGGER = 0.12f
private val ROW_RISE = 8.dp

/** Launcher3's `bg_popup_item_width`. */
private val POPUP_WIDTH = 216.dp
