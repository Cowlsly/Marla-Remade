package com.vayunmathur.launcher.ui.components

import android.appwidget.AppWidgetHostView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A hosted `AppWidgetHostView`, embedded in Compose.
 *
 * The host view has to be told the size it was actually given, not the size it asked for:
 * without [updateSize] a provider lays itself out for its declared minimum, which is why an
 * unsized widget looks cramped inside a wider span. That is also why [CellLayout] measures its
 * children with fixed constraints — the dp figures passed here come from the measured cell.
 *
 * The host view is swapped inside a plain `FrameLayout` rather than being the `AndroidView`
 * factory result, so a provider that has to be recreated does not force Compose to tear down
 * and rebuild the interop node. Call sites must wrap this in `key(item.id)`, or a workspace
 * re-emit reuses one item's node for another item's widget.
 *
 * The size is pushed **only when it changes**, and that is a performance requirement rather than
 * tidiness. `AndroidView`'s `update` runs on every recomposition, and a page recomposes on every
 * frame of a drag; `updateAppWidgetSize` is an IPC that makes the provider rebuild and re-send its
 * `RemoteViews`, so calling it per frame is dozens of round trips a second to every app with a
 * widget on the screen.
 */
@Composable
fun HostedWidget(
    appWidgetId: Int,
    widthDp: Int,
    heightDp: Int,
    createView: (Int) -> AppWidgetHostView?,
    updateSize: (AppWidgetHostView, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { container ->
            val existing = container.getChildAt(0) as? AppWidgetHostView
            val view = if (existing?.appWidgetId == appWidgetId) {
                existing
            } else {
                container.removeAllViews()
                container.tag = null
                createView(appWidgetId)?.also { container.addView(it) }
            }
            val size = widthDp to heightDp
            if (view != null && widthDp > 0 && heightDp > 0 && container.tag != size) {
                // Remembered on the container, which is ours, rather than on the host view, whose
                // tag belongs to whoever hosts it.
                container.tag = size
                updateSize(view, widthDp, heightDp)
            }
        },
    )
}
