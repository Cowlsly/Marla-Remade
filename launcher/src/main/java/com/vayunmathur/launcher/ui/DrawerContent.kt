package com.vayunmathur.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.FastScroll
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.platform.DrawerActions
import com.vayunmathur.launcher.platform.DrawerApp
import com.vayunmathur.launcher.platform.DrawerUiState
import com.vayunmathur.launcher.ui.components.DragPayload
import com.vayunmathur.launcher.ui.components.FastScrollStrip
import com.vayunmathur.launcher.ui.components.LauncherAppIcon
import com.vayunmathur.launcher.ui.components.dragSource
import com.vayunmathur.launcher.ui.components.onAppWindowBounds
import com.vayunmathur.library.ui.Badge
import com.vayunmathur.library.ui.BadgedBox
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.PrimaryTabRow
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.launch

/**
 * The app drawer, as an overlay over the home screen rather than a separate destination.
 *
 * That is the whole reason it is not a nav route: an app has to be draggable straight out of here
 * and onto the grid, and a drag cannot survive crossing two destinations. Drawn inside the home
 * screen's gesture-owning root, every icon here registers with the same
 * [com.vayunmathur.launcher.ui.components.LauncherDragController] as a workspace item does — with
 * a null `itemId`, which is what marks it as something to insert rather than move. Launcher3
 * places AllApps in its `DragLayer` for exactly the same reason.
 *
 * A [LazyVerticalGrid] here, unlike the home screen: the drawer really is a flowing list of
 * uniform 1x1 items, which is what a lazy grid is for. The row spans and absolute cell
 * coordinates that rule it out for the workspace simply do not arise.
 *
 * [gridState] is hoisted because the home screen's gesture owner needs it: a downward swipe closes
 * the drawer, but only from the top of this list — anywhere else that swipe is this list's own
 * scrolling. [draggable] is false while the drawer is only part-way up, so a long press cannot pick
 * an app out of a drawer that is barely on screen. [strip] is hoisted for the same reason and is the
 * more delicate of the two: see [FastScrollStrip].
 */
@Composable
fun DrawerContent(
    state: DrawerUiState,
    actions: DrawerActions,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    draggable: Boolean = true,
    strip: FastScrollStrip? = null,
    onDismiss: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var showWork by remember { mutableStateOf(false) }

    BackHandler(enabled = true) { onDismiss() }

    // Work apps are a separate tab rather than mixed in, as Launcher3 does: they are a different
    // profile with different rules, and a personal and a work copy of one app are otherwise two
    // identical rows next to each other.
    val hasWork = state.apps.any { it.isWorkProfile }
    val work = hasWork && showWork
    val apps = if (hasWork) state.apps.filter { it.isWorkProfile == work } else state.apps

    Box(
        modifier = modifier
            .fillMaxSize()
            // A sheet with rounded top corners, not a full-bleed fill: the wallpaper stays visible
            // at the corners, which is what says this is something pulled up over the workspace
            // rather than a different screen. The blur behind it is the window's own, since a sheet
            // in the same window cannot blur what is behind that window by itself.
            .clip(RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = SHEET_ALPHA)),
    ) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            // The handle Launcher3 draws at the top of the sheet (`bottom_sheet_handle`). It says
            // the surface is draggable, which is the only affordance the swipe-to-close gesture has.
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = HANDLE_WIDTH, height = HANDLE_HEIGHT)
                        .clip(RoundedCornerShape(HANDLE_HEIGHT / 2))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }

            CommonSearchBar(
                value = state.query,
                onValueChange = actions::setQuery,
                // The shared default placeholder is a hardcoded "Search", which says nothing on a
                // screen that is only ever a list of apps.
                placeholder = "Search apps",
                padding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
            )

            // Predictions are about reaching for something without looking; a filtered list is
            // already the user telling us what they want, so the row would be in the way.
            if (state.query.isBlank() && !work && state.predictions.isNotEmpty()) {
                PredictionsRow(state, actions, draggable)
                // Launcher3's `apps_divider_view`: the predictions are a different kind of list from
                // the alphabet below them, and without a rule between them they read as its first row.
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            }

            if (hasWork) {
                PrimaryTabRow(selectedTabIndex = if (work) 1 else 0) {
                    Tab(
                        selected = !work,
                        onClick = { showWork = false },
                        text = { Text("Personal") },
                    )
                    Tab(
                        selected = work,
                        onClick = { showWork = true },
                        text = { Text("Work") },
                    )
                }
            }

            // Only on a build that can actually pause the profile, which is a privileged one. See
            // com.vayunmathur.launcher.platform.LauncherPrivilege.
            if (work) {
                state.workPaused?.let { paused ->
                    SettingsSwitchRow(
                        title = "Pause work apps",
                        supportingText = "Work apps and notifications stop until you turn this off",
                        checked = paused,
                        onCheckedChange = actions::setWorkPaused,
                    )
                }
            }

            if (apps.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            state.loading -> "Loading apps"
                            state.query.isBlank() -> "No apps found"
                            else -> "No apps match \"${state.query}\""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(DRAWER_CELL_MIN),
                    state = gridState,
                    contentPadding = PaddingValues(Spacing.sm),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(apps, key = { it.key.componentName.flattenToShortString() + it.key.profileSerial }) { app ->
                        DrawerCell(
                            app = app,
                            state = state,
                            actions = actions,
                            draggable = draggable,
                        )
                    }
                }

                // Overlaid on the grid's right edge rather than beside it, which is what Launcher3's
                // negative `fastscroll_end_margin` achieves: the strip is a wide touch region, and
                // letting it take layout space costs the grid a whole column.
                //
                // Only useful on an unfiltered list; once a query has cut it down, scrolling is
                // short and the strip is noise.
                if (state.query.isBlank()) {
                    FastScrollThumb(
                        apps = apps,
                        strip = strip,
                        gridState = gridState,
                        onJump = { index -> scope.launch { gridState.scrollToItem(index) } },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }

                strip?.active?.let { fraction ->
                    LetterBubble(apps = apps, fraction = fraction)
                }
            }
        }
    }
}

/**
 * The handful of apps most likely to be wanted, along the top.
 *
 * From a local launch count rather than from the system's `AppPredictionManager`, which is
 * system-only. See [com.vayunmathur.launcher.platform.LauncherViewModel]: the counts live in the
 * DataStore this module already uses, so there is no permission to ask for and nothing to migrate.
 */
@Composable
private fun PredictionsRow(state: DrawerUiState, actions: DrawerActions, draggable: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        state.predictions.forEach { app ->
            Box(modifier = Modifier.weight(1f)) {
                DrawerCell(
                    app = app,
                    state = state,
                    actions = actions,
                    draggable = draggable,
                    // Keyed apart from the same app in the grid below, or the two register as one
                    // drag source and the grid's bounds win.
                    keyPrefix = "prediction",
                )
            }
        }
    }
}

@Composable
private fun DrawerCell(
    app: DrawerApp,
    state: DrawerUiState,
    actions: DrawerActions,
    draggable: Boolean,
    modifier: Modifier = Modifier,
    keyPrefix: String = "drawer",
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val icon = @Composable {
        LauncherAppIcon(
            key = app.key,
            label = app.label,
            scale = state.iconScale,
            showLabel = state.showLabels,
        )
    }

    Box(
        modifier = modifier
            .onAppWindowBounds { bounds = it }
            // A null itemId marks this as an app rather than a workspace item, so a drop creates
            // a row instead of moving one. Long-press is not handled here at all - the home
            // screen's single gesture owner picks this up by hit-testing the registered bounds.
            .dragSource(
                key = "$keyPrefix-${app.key.componentName.flattenToShortString()}-${app.key.profileSerial}",
                enabled = draggable,
            ) {
                DragPayload(
                    itemId = null,
                    type = LauncherItemType.APPLICATION,
                    label = app.label,
                    key = app.key,
                    rect = CellRect(0, 0),
                    sourceBounds = bounds,
                )
            }
            .clickable {
                actions.launchApp(
                    app.key,
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    bounds.right.toInt(),
                    bounds.bottom.toInt(),
                )
            }
            .padding(vertical = Spacing.sm),
    ) {
        if (app.isWorkProfile) {
            // The badge the system draws on a work icon is part of the icon bitmap, which the
            // drawer's own list cannot rely on when an icon fails to rasterise - so the profile is
            // marked here too, where it cannot be lost.
            BadgedBox(badge = { Badge() }) { icon() }
        } else {
            icon()
        }
    }
}

/**
 * The initials down the right edge.
 *
 * Not clickable, and not draggable either: the whole strip is driven by [FastScrollStrip] from the
 * home screen's gesture owner, because a `pointerInput` here would be a second one in a hierarchy
 * that permits exactly one. What this composable does is publish where it is and what sections it
 * is showing, and draw the thumb.
 *
 * A **thumb**, and no letters. Launcher3's fast scroller is a track with a
 * `fastscroll_thumb_height` handle on it; the alphabet only ever appears as the big
 * `fastscroll_popup` letter beside the finger while scrubbing. A permanent column of letters down
 * the edge is a different control from a different launcher.
 *
 * The thumb's position is read inside the draw block, not at composition scope: the list reports a
 * new scroll offset every frame, and reading that in composition would recompose the drawer sixty
 * times a second while it scrolls.
 */
@Composable
private fun FastScrollThumb(
    apps: List<DrawerApp>,
    strip: FastScrollStrip?,
    gridState: LazyGridState,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = remember(apps) {
        buildMap {
            apps.forEachIndexed { index, app ->
                val initial = app.label.firstOrNull()?.uppercaseChar() ?: return@forEachIndexed
                val letter = if (initial.isLetter()) initial else '#'
                putIfAbsent(letter, index)
            }
        }.toList()
    }

    if (strip != null) {
        // In a SideEffect, because these are writes to state the gesture owner reads: doing them
        // straight from composition is the "backwards write" that leaves one frame disagreeing with
        // the next about how many sections there are.
        SideEffect {
            strip.sections = sections.size
            var jumped = -1
            strip.onFraction = { fraction ->
                val section = FastScroll.sectionAt(fraction, sections.size)
                // Only when the section actually changes. The finger reports a position every few
                // milliseconds, and each jump is a suspend scroll that cancels the one before it -
                // so re-issuing the same jump per event is a fight with the list's scroll mutex for
                // no movement at all.
                if (section != null && section != jumped) {
                    jumped = section
                    onJump(sections[section].second)
                }
            }
        }
        // The drawer closing leaves this composition, and stale bounds would keep swallowing every
        // touch down the right edge of the workspace.
        DisposableEffect(strip) {
            onDispose {
                strip.bounds = Rect.Zero
                strip.sections = 0
                strip.onFraction = {}
            }
        }
    }

    val track = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(INDEX_WIDTH)
            .padding(vertical = Spacing.sm)
            .onAppWindowBounds { strip?.bounds = it }
            .drawBehind {
                val width = THUMB_WIDTH.toPx()
                val height = THUMB_HEIGHT.toPx()
                val travel = (size.height - height).coerceAtLeast(0f)
                // The finger owns the thumb while it is scrubbing; otherwise the list does.
                val fraction = strip?.active ?: gridState.scrollFraction()
                drawRoundRect(
                    color = track,
                    topLeft = Offset(size.width - width, fraction * travel),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(width / 2f),
                    alpha = THUMB_ALPHA,
                )
            },
    )
}

/**
 * Roughly how far down the list is, for the thumb.
 *
 * By item index rather than by pixel: a lazy grid does not know the height of what it has not
 * measured, so an exact proportion is not available, and the thumb only has to be believable.
 */
private fun LazyGridState.scrollFraction(): Float {
    val info = layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total <= visible || visible == 0) return 0f
    return (firstVisibleItemIndex.toFloat() / (total - visible)).coerceIn(0f, 1f)
}

/**
 * The letter the finger is currently over, shown beside the strip.
 *
 * A fast scroller with no bubble is a scrollbar: the list flies past too quickly to read, so the
 * letter is the only thing telling the user where they are. Sized as Launcher3's
 * `fastscroll_popup_*`: 75x62dp with 32dp text, held clear of the strip by `fastscroll_popup_margin`.
 */
@Composable
private fun LetterBubble(apps: List<DrawerApp>, fraction: Float) {
    val letters = remember(apps) {
        apps.mapNotNull { app ->
            val initial = app.label.firstOrNull()?.uppercaseChar() ?: return@mapNotNull null
            if (initial.isLetter()) initial else '#'
        }.distinct()
    }
    val letter = FastScroll.sectionAt(fraction, letters.size)?.let { letters[it] } ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(end = INDEX_WIDTH + BUBBLE_MARGIN),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            modifier = Modifier
                // Beside the finger rather than under it, and following the same fraction the
                // scroller is using, so bubble and list cannot disagree.
                .layoutOffsetY { height -> (fraction * height).toInt() }
                .size(width = BUBBLE_WIDTH, height = BUBBLE_HEIGHT)
                .clip(RoundedCornerShape(BUBBLE_CORNER))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                letter.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** Offsets by a fraction of the parent's height, which `offset` alone cannot express. */
private fun Modifier.layoutOffsetY(y: (Int) -> Int): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(IntOffset(0, y(constraints.maxHeight) - placeable.height / 2))
        }
    },
)

/** Launcher3's `bottom_sheet_handle`. */
private val HANDLE_WIDTH = 48.dp
private val HANDLE_HEIGHT = 2.dp

/** Wide enough for a label under a 48dp icon without truncating most app names. */
private val DRAWER_CELL_MIN = 76.dp

/** Launcher3's `fastscroll_width`, which is the strip's touch region rather than its ink. */
private val INDEX_WIDTH = 58.dp

/** Launcher3's `fastscroll_thumb_height` and `fastscroll_track_min_width`. */
private val THUMB_HEIGHT = 52.dp
private val THUMB_WIDTH = 6.dp

/** Present without competing with the icons it sits beside. */
private const val THUMB_ALPHA = 0.5f

/** Launcher3's `fastscroll_popup_*`. */
private val BUBBLE_WIDTH = 75.dp
private val BUBBLE_HEIGHT = 62.dp
private val BUBBLE_MARGIN = 19.dp
private val BUBBLE_CORNER = 16.dp

/** The corner radius of the sheet the drawer is drawn as. */
private val SHEET_CORNER = 28.dp

/** Translucent over the blurred wallpaper, rather than a flat fill that hides it entirely. */
private const val SHEET_ALPHA = 0.92f
