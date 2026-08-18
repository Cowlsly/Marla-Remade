package com.vayunmathur.launcher.platform

import android.content.ComponentName
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** An activity for a given profile. Two profiles' copies of an app are different icons. */
data class ComponentKey(val componentName: ComponentName, val profileSerial: Long)

/**
 * App icons, rasterised once and kept.
 *
 * `Icon()` and `painterResource` are unavailable here and would not help anyway: an icon
 * from [LauncherApps] is an arbitrary [Drawable] — adaptive, sometimes animated,
 * sometimes badged for a work profile — and neither `ImageVector` nor `Painter` can
 * represent one. So icons are drawn with `Image(bitmap = ...)`, which means rasterising,
 * which means caching: a page of 20 icons re-rasterised on every recomposition is
 * visible jank, and a drag recomposes constantly.
 *
 * (This is not a hole in the "all icons come from `Icons.kt`" rule. That rule governs
 * semantic UI glyphs — the settings cog, the back arrow — which still all come from
 * there. These are third-party app artwork.)
 *
 * Bounded by count rather than bytes because every entry is rasterised to the same
 * [iconSizePx], so entries are all the same size.
 */
class IconCache(private val iconSizePx: Int) {

    private val bitmaps = object : LruCache<ComponentKey, ImageBitmap>(MAX_ENTRIES) {}

    /**
     * The cached bitmap, rasterising through [load] on a miss.
     *
     * [load] is called on the caller's thread, so callers must not invoke this from
     * composition directly — go through `LauncherAppIcon`, which does it in a
     * `produceState`.
     */
    fun get(key: ComponentKey, load: () -> Drawable?): ImageBitmap? {
        bitmaps.get(key)?.let { return it }
        val drawable = runCatching(load).getOrNull() ?: return null
        // A zero-intrinsic-size drawable (some adaptive icons report -1) would produce a
        // 0x0 bitmap and throw, so the target size is always explicit.
        val bitmap = drawable.toBitmap(iconSizePx, iconSizePx).asImageBitmap()
        bitmaps.put(key, bitmap)
        return bitmap
    }

    /** Drops everything. Called on a locale change, which relabels and re-themes icons. */
    fun clear() = bitmaps.evictAll()

    /** Drops one app's entries, so a package update shows its new icon. */
    fun evictPackage(packageName: String, profileSerial: Long) {
        bitmaps.snapshot().keys
            .filter { it.componentName.packageName == packageName && it.profileSerial == profileSerial }
            .forEach { bitmaps.remove(it) }
    }

    private companion object {
        /** Comfortably more than two pages plus a full drawer viewport. */
        const val MAX_ENTRIES = 512
    }
}
