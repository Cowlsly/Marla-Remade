package com.vayunmathur.web.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vayunmathur.library.ui.HorizontalFlick
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.itemMotion
import com.vayunmathur.library.ui.rememberHaptics
import com.vayunmathur.library.ui.rememberReorderableLazyGridState
import com.vayunmathur.library.ui.reorderGridDragHandle
import com.vayunmathur.web.R
import com.vayunmathur.web.platform.BrowserTab
import com.vayunmathur.web.ui.components.TabTile
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Fixed rather than adaptive: two columns is what makes a tile big enough to recognise a page in. */
private const val COLUMNS = 2

/** How far across itself a tile has to travel before letting go of it closes the tab. */
private const val FLICK_FRACTION = 0.4f

/**
 * The alternative to [FLICK_FRACTION], in px/s: a short, fast throw means the same thing as a long,
 * slow drag. This is the only thing release velocity decides — never *which* gesture happened.
 */
private const val FLICK_VELOCITY = 1200f

/** How long the flicked tile takes to finish leaving, before the tab is actually closed. */
private const val FLICK_OUT_MILLIS = 150

/**
 * The tab switcher: every open tab as a square showing the top of its page.
 *
 * Stateless, so the store-listing previews can render it with literal tabs. [thumbnailFor] and
 * [faviconFor] default to supplying nothing, which is also what a real device gives on a cold
 * start — see [TabTile] for why that is the case worth getting right.
 *
 * Two gestures share each tile, and which one happens is settled by intent at the start rather
 * than by velocity at release: a long press picks the tile up to reorder it, a clearly horizontal
 * drag flicks it closed, and a vertical one is left alone so the grid scrolls. Releasing mid-motion
 * is normal when reordering, so a scheme that read the release would close tabs that were being
 * moved — and closing a tab cannot be undone.
 *
 * Flicking is suppressed when there is only one tab, because closing the last tab immediately
 * creates a blank one, which would appear under the finger and read as the flick having failed.
 * The close button stays on every tile regardless: it is the affordance that works with TalkBack.
 */
@Composable
fun TabSwitcher(
    tabs: List<BrowserTab>,
    activeTabId: String?,
    onSwitch: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onReorder: (from: Int, to: Int) -> Unit = { _, _ -> },
    onNewIncognitoTab: () -> Unit = {},
    onNewWindow: () -> Unit = onNewTab,
    onNewIncognitoWindow: () -> Unit = onNewIncognitoTab,
    isIncognitoWindow: Boolean = tabs.find { it.id == activeTabId }?.isPrivate == true,
    thumbnailFor: (String) -> Bitmap? = { null },
    faviconFor: (String) -> Bitmap? = { null },
) {
    val haptics = rememberHaptics()
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        onReorder(from.index, to.index)
    }
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.98f))) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(pluralStringResource(R.plurals.tabs, tabs.size, tabs.size)) },
                navigationIcon = { IconButton(onClick = onDismiss) { IconClose() } },
                actions = { IconButton(onClick = onNewTab) { IconAdd() } }
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tabs, key = { it.id }) { tab ->
                    val scope = rememberCoroutineScope()
                    var flickX by remember { mutableFloatStateOf(0f) }
                    var widthPx by remember { mutableFloatStateOf(0f) }
                    val dragging = reorderState.draggingKey == tab.id
                    val motion = itemMotion(Motion.reorder())
                    // The dragged tile follows the finger, so it cannot also be laid out by the
                    // grid's placement animation; the rest glide aside on the reorder spec.
                    val itemModifier = if (dragging) {
                        Modifier.zIndex(1f).graphicsLayer {
                            val translation = reorderState.draggingItemTranslation
                            translationX = translation.x
                            translationY = translation.y
                        }
                    } else {
                        motion.graphicsLayer {
                            translationX = flickX
                            alpha = 1f - (abs(flickX) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f) * 0.6f
                        }
                    }
                    TabTile(
                        tab = tab,
                        isActive = tab.id == activeTabId,
                        thumbnail = thumbnailFor(tab.id),
                        favicon = faviconFor(tab.url),
                        onSwitch = { onSwitch(tab.id) },
                        onClose = { onClose(tab.id) },
                        modifier = itemModifier.onSizeChanged { widthPx = it.width.toFloat() },
                        gestureModifier = Modifier.reorderGridDragHandle(
                            reorderState = reorderState,
                            key = tab.id,
                            itemCount = tabs.size,
                            onDragStarted = { haptics.longPress() },
                            onSwap = { haptics.tick() },
                            onDragStopped = { haptics.confirm() },
                            horizontalFlick = HorizontalFlick(
                                enabled = { tabs.size > 1 },
                                onDrag = { flickX = it },
                                onRelease = { dx, velocity ->
                                    val thrown = abs(velocity) > FLICK_VELOCITY && velocity * dx > 0f
                                    val far = widthPx > 0f && abs(dx) > widthPx * FLICK_FRACTION
                                    scope.launch {
                                        if (thrown || far) {
                                            val exit = if (dx < 0f) -widthPx else widthPx
                                            animate(flickX, exit, animationSpec = Motion.close(FLICK_OUT_MILLIS)) { v, _ ->
                                                flickX = v
                                            }
                                            onClose(tab.id)
                                        } else {
                                            animate(flickX, 0f, animationSpec = Motion.drop()) { v, _ -> flickX = v }
                                        }
                                    }
                                },
                            ),
                        ),
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { if (isIncognitoWindow) onNewIncognitoTab() else onNewTab() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(
                                    if (isIncognitoWindow) R.string.new_incognito_tab else R.string.new_tab
                                )
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onNewWindow, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.new_window))
                            }
                            OutlinedButton(onClick = onNewIncognitoWindow, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.new_incognito_window))
                            }
                        }
                    }
                }
            }
        }
    }
}
