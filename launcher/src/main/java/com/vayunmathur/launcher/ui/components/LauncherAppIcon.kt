package com.vayunmathur.launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.platform.ComponentKey
import com.vayunmathur.launcher.platform.LocalIconLoader
import com.vayunmathur.library.ui.IconApps
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Nominal icon edge before the user's scale factor. Every cell is sized around this. */
val LauncherIconSize = 48.dp

/**
 * The dispatcher every icon rasterises on, with its concurrency bounded.
 *
 * Each miss is a binder call to resolve the activity, a `Drawable` load out of another app's
 * resources and a bitmap rasterisation, and opening the drawer asks for a hundred and fifty of them
 * at once. On bare [Dispatchers.IO] that is a hundred and fifty threads contending for binder and
 * allocating bitmaps simultaneously, which is slower in wall-clock terms than doing them in batches
 * *and* it starves everything else on the device while it happens.
 *
 * Bounded, but not narrowly. The bound has to stay comfortably above the number of icons visible at
 * once, or loading becomes visibly sequential: at two, a folder's four children appeared one at a
 * time behind the rest of the workspace, which reads as a folder with one app in it.
 */
internal val IconLoadDispatcher = Dispatchers.IO.limitedParallelism(8)

/**
 * One app icon.
 *
 * Drawn with `Image(bitmap = ...)` rather than through the shared `Icon…()` helpers, because
 * an icon from `LauncherApps` is an arbitrary `Drawable` — adaptive, sometimes animated,
 * sometimes badged for a work profile — and neither `ImageVector` nor `Painter` can represent
 * one. Rasterisation happens off the main thread the first time and comes from
 * [com.vayunmathur.launcher.platform.IconCache] afterwards. Until it resolves, and for an app
 * whose icon fails to load at all, a placeholder is drawn so the grid never reflows.
 *
 * This is not a hole in the "all icons come from `Icons.kt`" rule: that rule governs semantic
 * UI glyphs — the settings cog, the back arrow — which still all come from there. These are
 * third-party app artwork.
 */
@Composable
fun LauncherAppIcon(
    key: ComponentKey?,
    label: String,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    showLabel: Boolean = true,
    dimmed: Boolean = false,
) {
    val loader = LocalIconLoader.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key, loader) {
        val target = key ?: return@produceState
        value = withContext(IconLoadDispatcher) { loader.appIcon(target) }
    }

    LauncherIconCell(
        label = label,
        modifier = modifier,
        scale = scale,
        showLabel = showLabel,
        dimmed = dimmed,
    ) {
        LauncherIconImage(bitmap, label, scale)
    }
}

/**
 * The icon artwork, or the placeholder when there is none.
 *
 * Internal so [LauncherItemIcon] can draw shortcut artwork the same way; a second copy of
 * this would be a second chance for an icon to end up a different size.
 */
@Composable
internal fun LauncherIconImage(bitmap: ImageBitmap?, label: String, scale: Float) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(LauncherIconSize * scale),
        )
    } else {
        IconApps(Modifier.size(LauncherIconSize * scale))
    }
}

/**
 * The icon-over-label arrangement every cell shares.
 *
 * One place, so an app, a shortcut and a folder are all exactly the same size with their
 * labels on the same baseline — a grid of mismatched icons is the thing this prevents.
 */
@Composable
internal fun LauncherIconCell(
    label: String,
    modifier: Modifier,
    scale: Float,
    showLabel: Boolean,
    dimmed: Boolean,
    icon: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (dimmed) DIMMED_ALPHA else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        if (showLabel) {
            Text(
                label,
                // Shadowed, always, exactly as Launcher3's `BubbleTextView` is. Not decoration: a
                // label is drawn straight onto whatever wallpaper the user chose, and plain
                // `onSurface` text is illegible over a light one - which is most photographs.
                style = MaterialTheme.typography.labelSmall.copy(shadow = labelShadow(density)),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

/** Enough to read as "present but not usable" without looking like a rendering bug. */
private const val DIMMED_ALPHA = 0.4f

/**
 * Launcher3's *key* shadow on an icon label: `keyShadowBlur` 0.5dp at `keyShadowOffset` 0.5dp, in
 * `workspaceKeyShadowColor` `#89000000`.
 *
 * `DoubleShadowBubbleTextView` layers a second, ambient shadow under this one - 1.5dp of blur at
 * `#40000000` with no offset - which a Compose [Shadow] cannot express, since a `TextStyle` carries
 * exactly one. The key shadow is the one that does the work of separating text from a light
 * wallpaper, so it is the one kept.
 */
private fun labelShadow(density: Density): Shadow = with(density) {
    Shadow(
        color = Color(0x89000000),
        offset = Offset(0.5.dp.toPx(), 0.5.dp.toPx()),
        blurRadius = 0.5.dp.toPx(),
    )
}
