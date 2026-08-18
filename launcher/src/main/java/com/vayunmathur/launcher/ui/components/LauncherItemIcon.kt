package com.vayunmathur.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.platform.LocalIconLoader
import com.vayunmathur.launcher.platform.WorkspaceItem
import com.vayunmathur.library.ui.IconFolder
import com.vayunmathur.library.ui.MaterialTheme
import kotlinx.coroutines.withContext

/**
 * Whatever a workspace item should look like in its cell.
 *
 * The one entry point the home screen and an open folder both use, so an app, a pinned
 * shortcut and a folder cannot drift apart in size or label treatment. Widgets are not handled
 * here — they are a hosted view, not an icon.
 */
@Composable
fun LauncherItemIcon(
    item: WorkspaceItem,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    showLabel: Boolean = true,
) {
    when (item.type) {
        LauncherItemType.FOLDER -> FolderIcon(item, modifier, scale, showLabel)
        LauncherItemType.DEEP_SHORTCUT -> ShortcutIcon(item, modifier, scale, showLabel)
        else -> LauncherAppIcon(
            key = item.key,
            label = item.label,
            modifier = modifier,
            scale = scale,
            showLabel = showLabel,
            dimmed = item.hidden,
        )
    }
}

/** A pinned deep shortcut: the same cell as an app, with artwork from a different source. */
@Composable
private fun ShortcutIcon(item: WorkspaceItem, modifier: Modifier, scale: Float, showLabel: Boolean) {
    val loader = LocalIconLoader.current
    val key = item.key
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key, item.shortcutId, loader) {
        val target = key ?: return@produceState
        val shortcutId = item.shortcutId ?: return@produceState
        value = withContext(IconLoadDispatcher) {
            loader.shortcutIcon(target.componentName.packageName, shortcutId, target.profileSerial)
        }
    }

    LauncherIconCell(
        label = item.label,
        modifier = modifier,
        scale = scale,
        showLabel = showLabel,
        dimmed = item.hidden,
    ) {
        LauncherIconImage(bitmap, item.label, scale)
    }
}

/**
 * A folder, drawn as a filled tile with its first few children previewed inside.
 *
 * Children rather than a generic folder glyph, because what is inside is the only thing that
 * tells one folder from another — especially a folder created by a drop, which has no name yet.
 *
 * The tile behind them is filled, stroked and shadowed, as Launcher3's `FolderIcon` is. It is not
 * decoration either: without a background the previewed children float directly on the wallpaper
 * and a folder is indistinguishable from four very small apps.
 */
@Composable
private fun FolderIcon(item: WorkspaceItem, modifier: Modifier, scale: Float, showLabel: Boolean) {
    LauncherIconCell(
        label = item.label,
        modifier = modifier,
        scale = scale,
        showLabel = showLabel,
        dimmed = false,
    ) {
        Box(
            modifier = Modifier
                .size(LauncherIconSize * scale)
                .shadow(FOLDER_ELEVATION, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = FOLDER_ALPHA))
                .border(FOLDER_STROKE, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .padding(FOLDER_INSET),
            contentAlignment = Alignment.Center,
        ) {
            val preview = item.children.take(FOLDER_PREVIEW_COUNT)
            if (preview.isEmpty()) {
                IconFolder(Modifier.size(LauncherIconSize * scale * FOLDER_GLYPH_SCALE))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    preview.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            row.forEach { child ->
                                LauncherAppIcon(
                                    key = child.key,
                                    label = child.label,
                                    scale = scale * FOLDER_CHILD_SCALE,
                                    showLabel = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Enough to read the folder at a glance without the tiles becoming specks. */
private const val FOLDER_PREVIEW_COUNT = 4
private const val FOLDER_CHILD_SCALE = 0.42f
private const val FOLDER_GLYPH_SCALE = 0.6f
private val FOLDER_INSET = 3.dp
private val FOLDER_STROKE = 1.dp

/** Off the wallpaper, since a folder is a container sitting on it rather than part of it. */
private val FOLDER_ELEVATION = 2.dp

/** Translucent, so the wallpaper still reads faintly through a screen full of folders. */
private const val FOLDER_ALPHA = 0.9f
