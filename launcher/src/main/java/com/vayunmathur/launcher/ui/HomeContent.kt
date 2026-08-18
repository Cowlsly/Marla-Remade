package com.vayunmathur.launcher.ui

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.DropPlan
import com.vayunmathur.launcher.domain.FolderMerge
import com.vayunmathur.launcher.domain.FolderRules
import com.vayunmathur.launcher.domain.GridPreview
import com.vayunmathur.launcher.domain.GridReorder
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.LauncherTuning
import com.vayunmathur.launcher.domain.PageCount
import com.vayunmathur.launcher.domain.ReorderDwell
import com.vayunmathur.launcher.domain.WidgetResize
import com.vayunmathur.launcher.platform.DrawerActions
import com.vayunmathur.launcher.platform.DrawerUiState
import com.vayunmathur.launcher.platform.FolderActions
import com.vayunmathur.launcher.platform.HomeActions
import com.vayunmathur.launcher.platform.HomeUiState
import com.vayunmathur.launcher.platform.ItemMenuActions
import com.vayunmathur.launcher.platform.ItemMenuUiState
import com.vayunmathur.launcher.platform.WidgetPickerActions
import com.vayunmathur.launcher.platform.WidgetPickerUiState
import com.vayunmathur.launcher.platform.WorkspaceItem
import com.vayunmathur.launcher.ui.components.CellLayout
import com.vayunmathur.launcher.ui.components.CellMetrics
import com.vayunmathur.launcher.ui.components.DragFeedback
import com.vayunmathur.launcher.ui.components.DragLayer
import com.vayunmathur.launcher.ui.components.DragPayload
import com.vayunmathur.launcher.ui.components.DropBar
import com.vayunmathur.launcher.ui.components.FastScrollStrip
import com.vayunmathur.launcher.ui.components.HostedWidget
import com.vayunmathur.launcher.ui.components.LauncherIconSize
import com.vayunmathur.launcher.ui.components.LauncherItemIcon
import com.vayunmathur.launcher.ui.components.LauncherPopup
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import com.vayunmathur.launcher.ui.components.MergeRing
import com.vayunmathur.launcher.ui.components.MissingWidget
import com.vayunmathur.launcher.ui.components.PageEdgeDwell
import com.vayunmathur.launcher.ui.components.PageIndicator
import com.vayunmathur.launcher.ui.components.VerticalSwipe
import com.vayunmathur.launcher.ui.components.WidgetResizeFrame
import com.vayunmathur.launcher.ui.components.cell
import com.vayunmathur.launcher.ui.components.dragSource
import com.vayunmathur.launcher.ui.components.dropTarget
import com.vayunmathur.launcher.ui.components.launcherDragInput
import com.vayunmathur.launcher.ui.components.onAppWindowBounds
import com.vayunmathur.launcher.ui.components.reorderPreview
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.scrim
import kotlinx.coroutines.launch

/**
 * The home screen: a pager of cell grids, a hotseat, the app drawer and any open folder, and the
 * drag machinery over the top.
 *
 * Everything is in this one composable's root [Box] on purpose. The drawer and an open folder are
 * overlays here rather than nav destinations, because that root is what carries
 * [launcherDragInput] — the single gesture owner — and a drag can only cross from the drawer to
 * the grid, or out of a folder onto the grid, if all three are inside it. Launcher3 arranges its
 * `DragLayer` the same way and for the same reason.
 *
 * The long-press popups are the exception, and deliberately so: they are [LauncherPopup]s in
 * windows of their own, because a popup that opens *while the finger is still down* cannot share a
 * pointer hierarchy with the gesture that opened it. Their scrim is drawn here, with no pointer
 * handling of its own, and dismissal belongs to the gesture owner — which is what makes one touch
 * dismiss the popup and do nothing else.
 *
 * No `AppScaffold`: a home screen has no top app bar, and the point of the transparent window plus
 * `containerColor` is that nothing opaque is drawn over the wallpaper.
 */
@Composable
fun HomeContent(
    state: HomeUiState,
    actions: HomeActions,
    drawerState: DrawerUiState = DrawerUiState(),
    drawerActions: DrawerActions = DrawerActions.Noop,
    folderActions: FolderActions = FolderActions.Noop,
    onOpenItemMenu: (Long) -> Unit = {},
    itemMenuState: ItemMenuUiState = ItemMenuUiState(),
    itemMenuActions: ItemMenuActions = ItemMenuActions.Noop,
    onOpenSettings: () -> Unit = {},
    onPickWallpaper: () -> Unit = {},
    widgetPickerState: WidgetPickerUiState = WidgetPickerUiState(),
    widgetPickerActions: WidgetPickerActions = WidgetPickerActions.Noop,
    widgetView: (Int) -> AppWidgetHostView? = { null },
    updateWidgetSize: (AppWidgetHostView, Int, Int) -> Unit = { _, _, _ -> },
    initialDrawerOpen: Boolean = false,
    initialOpenFolderId: Long? = null,
) {
    val drag = LocalLauncherDrag.current
    val density = LocalDensity.current
    // The trailing empty page exists only while something is in the air: it is somewhere to drag
    // *to*, and kept permanently it is just a page the user can reach and cannot get rid of.
    val pageCount = PageCount.pageCount(state.pages.keys.maxOrNull() ?: -1, drag.isDragging)
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val drawerGridState = rememberLazyGridState()
    // Stable, because the gesture owner is keyed on it: the drawer fills in where its A-Z strip is
    // and what it should do, from inside the very composition the gesture owner wraps.
    val fastScroll = remember { FastScrollStrip() }
    val scope = rememberCoroutineScope()
    var rootBounds by remember { mutableStateOf(Rect.Zero) }

    // Overlay and selection state, all transient: none of it is worth restoring, and the workspace
    // itself lives in the database.
    //
    // The drawer is a progress rather than a boolean because it is dragged: 0 is home, 1 is the
    // drawer, and every value between is a frame of the swipe that is opening it.
    //
    // A plain float rather than an `Animatable`, and that is a performance fix rather than a
    // simplification. `Animatable` has no synchronous setter, so following a finger through one
    // means launching a coroutine per pointer event, each cancelling the last through a mutator
    // mutex: at the rate a touchscreen reports that is an allocation and a cancellation per event on
    // the main thread, and - worse - any delta whose `snapTo` was cancelled before it applied was
    // simply lost. The drawer then travelled a fraction of what the finger did, which is what made
    // the swipe feel far longer than it is.
    val drawer = remember { mutableFloatStateOf(if (initialDrawerOpen) 1f else 0f) }
    // Where the drawer is animating to once the finger has let go, or null while nothing is.
    var drawerSettle by remember { mutableStateOf<Float?>(null) }
    var openFolderId by remember { mutableStateOf(initialOpenFolderId) }
    // The icon the open folder grew out of, so it can shrink back into it.
    var folderAnchor by remember { mutableStateOf(Rect.Zero) }
    val folder = remember { Animatable(if (initialOpenFolderId != null) 1f else 0f) }
    // The item whose popup is up and the icon it is anchored to, or - for a press on bare
    // wallpaper - the point the finger was at.
    var menuItemId by remember { mutableStateOf<Long?>(null) }
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }
    var optionsAnchor by remember { mutableStateOf<Rect?>(null) }
    val popup = remember { Animatable(0f) }
    var resizingId by remember { mutableStateOf<Long?>(null) }

    val allItems = state.pages.values.flatten() + state.hotseat
    val openFolder = openFolderId?.let { id -> allItems.firstOrNull { it.id == id } }
    // A folder that collapsed under us - its last child was dragged out - has no item left to
    // render, so the overlay closes itself rather than showing an empty sheet.
    if (openFolderId != null && openFolder == null) openFolderId = null

    val popupOpen = menuItemId != null || optionsAnchor != null

    // Composed from the first frame of the swipe, so it can be seen coming up, but only *reachable*
    // past the halfway point. Derived rather than read straight off the progress: reading
    // `drawer.floatValue` here would recompose this whole screen on every frame of the swipe, where
    // these only change twice.
    //
    // The workspace is still composed underneath an open overlay, so its icons would still be
    // registered as drag sources and a long press over the drawer could pick one up through it; the
    // drawer's own icons have the mirror-image problem while it is barely showing. Overlays consume
    // touches in Launcher3; here the equivalent is that whichever of the two is not in front is
    // un-draggable.
    val drawerVisible by remember { derivedStateOf { drawer.floatValue > 0f } }
    val drawerShown by remember { derivedStateOf { drawer.floatValue >= DRAWER_INTERACTIVE } }
    val overlayOpen = drawerShown || openFolder != null || popupOpen

    fun openFolder(id: Long, anchor: Rect) {
        folderAnchor = anchor
        openFolderId = id
        scope.launch {
            // Snapped first: a folder whose last close was cut short would otherwise open with no
            // animation at all.
            folder.snapTo(0f)
            folder.animateTo(1f, Motion.open(Motion.FolderOpenMillis))
        }
    }

    // Closed in two steps, because the sheet has to stay composed while it shrinks; clearing the id
    // first would take the thing being animated off screen before the animation ran.
    fun closeFolder() {
        scope.launch {
            folder.animateTo(0f, Motion.close(Motion.FolderCloseMillis))
            openFolderId = null
        }
    }

    fun openMenu(id: Long, anchor: Rect) {
        menuAnchor = anchor
        optionsAnchor = null
        menuItemId = id
        onOpenItemMenu(id)
        scope.launch {
            popup.snapTo(0f)
            popup.animateTo(1f, Motion.open(Motion.PopupOpenMillis))
        }
    }

    fun openOptions(at: Offset) {
        menuItemId = null
        optionsAnchor = Rect(at.x, at.y, at.x, at.y)
        scope.launch {
            popup.snapTo(0f)
            popup.animateTo(1f, Motion.open(Motion.PopupOpenMillis))
        }
    }

    fun closePopup() {
        scope.launch {
            popup.animateTo(0f, Motion.close(Motion.PopupCloseMillis))
            menuItemId = null
            optionsAnchor = null
        }
    }

    fun settleDrawer(to: Float) {
        // Blurred while the drawer is up, as Launcher3 blurs its wallpaper behind all-apps. Set on
        // the settle rather than per frame of the swipe: a window-level blur is not free, and
        // changing it sixty times a second is the one way to make it cost something visible.
        actions.setWallpaperBlurred(to > 0f)
        drawerSettle = to
    }

    // The settle, and the only thing that writes `drawer` other than the finger. Restarting it with
    // null is how the finger takes the value back mid-animation.
    LaunchedEffect(drawerSettle) {
        val to = drawerSettle ?: return@LaunchedEffect
        val from = drawer.floatValue
        animate(
            initialValue = from,
            targetValue = to,
            animationSpec = if (to > from) {
                Motion.open(Motion.DrawerOpenMillis)
            } else {
                Motion.close(Motion.DrawerCloseMillis)
            },
        ) { value, _ -> drawer.floatValue = value }
        drawerSettle = null
    }

    // Remembered, not rebuilt: the gesture owner is keyed on this, and a new instance every
    // recomposition would restart the `pointerInput` mid-gesture. State it needs from outside goes
    // through holders rather than being captured, or it would be reading the first composition's
    // values for the life of the screen.
    val shadeAvailable = remember { mutableStateOf(false) }
    SideEffect { shadeAvailable.value = state.canExpandShade }
    val flingThresholdPx = with(density) { DRAWER_FLING.toPx() }
    val drawerSwipe = remember {
        VerticalSwipe(
            claims = { travel ->
                val claimed = if (drawer.floatValue <= 0f) {
                    // Upwards from the workspace opens the drawer; downwards pulls the notification
                    // shade down, but only on a build that can actually do it.
                    travel < 0f || shadeAvailable.value
                } else {
                    // Downwards closes it, but only from the top of the list - anywhere else the
                    // swipe is the list's own scrolling, and claiming it here would take that away.
                    travel > 0f &&
                        drawerGridState.firstVisibleItemIndex == 0 &&
                        drawerGridState.firstVisibleItemScrollOffset == 0
                }
                // The finger takes the value back from any settle still running, and carries on from
                // wherever the drawer is now rather than from where the last swipe left it.
                if (claimed) drawerSettle = null
                claimed
            },
            onDrag = { delta ->
                // 1:1 with the finger over the drawer's whole travel, as Launcher3 tracks it: a slow
                // drag of a third of the screen does not open all-apps there either. What makes a
                // *short* swipe enough is the fling below, not a shortened range.
                val range = rootBounds.height
                if (range <= 0f) return@VerticalSwipe
                // Negative delta is upwards, which is more drawer. Written straight through, with no
                // coroutine and nothing to cancel, so no delta can be dropped.
                drawer.floatValue = (drawer.floatValue - delta / range).coerceIn(0f, 1f)
            },
            onRelease = { velocity ->
                val to = when {
                    velocity < -flingThresholdPx -> 1f
                    velocity > flingThresholdPx -> 0f
                    // Neither a throw nor a flick: wherever it was left decides.
                    else -> if (drawer.floatValue > DRAWER_COMMIT) 1f else 0f
                }
                // A downward swipe that never moved the drawer was for the shade. Decided on
                // release rather than on the first frame, so a swipe that changes its mind and goes
                // up still opens the drawer.
                if (to == 0f && drawer.floatValue <= 0f && velocity > 0f) {
                    actions.expandNotificationShade()
                }
                settleDrawer(to)
            },
        )
    }

    // Remembered, all of them, and for the same reason the ones in `HomePage` are: a callable
    // reference to a local function is a fresh object on every recomposition, and these go down into
    // every page and every cell. Handing them a new identity means nothing below can skip, so one
    // recomposition here became three pages of icons recomposing. They capture only state delegates
    // and remembered objects, so remembering them keeps them live.
    val onOpenFolder = remember { { id: Long, anchor: Rect -> openFolder(id, anchor) } }
    val onOpenMenu = remember { { id: Long, anchor: Rect -> openMenu(id, anchor) } }
    val onCloseFolder = remember { { closeFolder() } }
    val onClosePopup = remember { { closePopup() } }
    val onEndResize = remember { { resizingId = null } }

    // Launcher3 offers the handles the moment a widget lands, and only when the widget says it can
    // be resized at all.
    val onWidgetDropped = remember { { id: Long -> resizingId = id } }

    // Home is the workspace, always, on every fresh arrival. Launcher3 does this explicitly - it
    // sends itself to NORMAL when re-entered - and without it the drawer outlives the trip: a swipe
    // up into recents is the same upward flick that opens the drawer, so the drawer opens behind the
    // recents screen and is what you land on when you come back.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        drawerSettle = null
        drawer.floatValue = 0f
    }

    PageEdgeDwell(drag, pagerState, rootBounds.width) { pagerState.animateScrollToPage(it) }

    // The popups' windows are not focusable, so back never reaches them - which is what leaves it
    // here, with the rest of the dismissal logic.
    BackHandler(enabled = popupOpen) { closePopup() }

    // A drag in flight when the home screen goes away has nowhere to be dropped, and leaving the
    // payload set would show a stale drag layer on the way back in.
    DisposableEffect(drag) {
        onDispose { drag.cancel() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootBounds = it.boundsInWindow() }
            // The one gesture owner. Everything below relies on it consuming nothing until a long
            // press actually fires, or a swipe turns out to be vertical.
            .launcherDragInput(
                controller = drag,
                onDragStart = { payload ->
                    // An app picked up in the drawer, or a shortcut picked up in a popup, needs the
                    // grid it is being dropped onto to be visible, so whatever it came out of gets
                    // out of the way the moment the drag begins.
                    if (payload.itemId == null) settleDrawer(0f)
                    resizingId = null
                },
                onLongPressItem = { payload, anchor ->
                    // The popup, for a widget as for anything else. Launcher3 shows the resize frame
                    // on *drop*, not on long press - see `getWidgetResizeFrameRunnable` - and going
                    // straight to the handles here meant a widget could never be reached by its menu
                    // at all.
                    payload.itemId?.let { openMenu(it, anchor) }
                },
                onLongPressEmpty = ::openOptions,
                // Reading the two state holders rather than the `popupOpen` boolean, which is the
                // difference between a live answer and a dead one: this lambda is captured once, by
                // a `pointerInput` keyed on things that never change, so anything it closes over by
                // value stays at its first-composition value for the life of the screen. Capturing
                // the state delegates instead means the read happens when the gesture asks.
                popupOpen = { menuItemId != null || optionsAnchor != null },
                onDismissPopup = onClosePopup,
                fastScroll = fastScroll,
                verticalSwipe = drawerSwipe,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                // Sinking away as the drawer rises, as Launcher3 does. Read inside the layer block
                // rather than outside it, so a frame of the swipe redraws without recomposing the
                // whole workspace.
                .graphicsLayer {
                    val scale = 1f -
                        (1f - LauncherTuning.WorkspaceScaleBehindDrawer) * drawer.floatValue
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - drawer.floatValue
                },
        ) {
            DropBar(
                controller = drag,
                onRemove = { id ->
                    resizingId = null
                    actions.remove(id)
                },
                onUninstall = actions::uninstallItem,
                onAppInfo = actions::openItemInfo,
            )

            HorizontalPager(
                state = pagerState,
                // Disabled for the duration of a drag; page changes come from the edge dwell
                // instead. Leaving it on means the pager and the dragged icon fight over the same
                // horizontal movement.
                userScrollEnabled = !drag.isDragging,
                // The pager's default snap is a spring, which overshoots slightly. A workspace
                // page has weight and does not bounce, so this is `PagedView`'s own curve instead.
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    snapAnimationSpec = Motion.pageSnap(),
                ),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { page ->
                WorkspacePage(
                    state = state,
                    actions = actions,
                    page = page,
                    items = state.pages[page].orEmpty(),
                    resizingId = resizingId,
                    draggable = !overlayOpen,
                    onOpenFolder = onOpenFolder,
                    onEndResize = onEndResize,
                    onWidgetDropped = onWidgetDropped,
                    widgetView = widgetView,
                    updateWidgetSize = updateWidgetSize,
                )
            }

            PageIndicator(
                pageCount = pageCount,
                // A lambda, so a fling redraws the dots without recomposing this column.
                scrollProgress = {
                    pagerState.currentPage + pagerState.currentPageOffsetFraction
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Hotseat(
                state = state,
                actions = actions,
                draggable = !overlayOpen,
                onOpenFolder = onOpenFolder,
            )
        }

        // Drawn, not touched: dismissal is the gesture owner's, so a scrim that swallowed touches
        // would be a second thing arbitrating them.
        if (popupOpen) {
            Box(modifier = Modifier.fillMaxSize().scrim { popup.value * POPUP_SCRIM_ALPHA })
        }

        if (drawerVisible) {
            DrawerContent(
                state = drawerState,
                actions = drawerActions,
                gridState = drawerGridState,
                draggable = drawerShown,
                strip = fastScroll,
                onDismiss = { settleDrawer(0f) },
                // Rising with the swipe and fading in with it, so the wallpaper is still there
                // behind a drawer that is only half up.
                modifier = Modifier.graphicsLayer {
                    translationY = size.height * (1f - drawer.floatValue)
                    alpha = drawer.floatValue
                },
            )
        }

        if (openFolder != null) {
            FolderContent(
                folder = openFolder,
                actions = folderActions,
                showLabels = state.showLabels,
                iconScale = state.iconScale,
                anchor = folderAnchor,
                progress = { folder.value },
                onOpenItemMenu = onOpenMenu,
                onDragLeft = onCloseFolder,
                onDismiss = onCloseFolder,
            )
        }

        // Last, so the dragged item floats above the overlays it may have come out of.
        DragLayer(drag, state.iconScale)
        DragFeedback(drag)
    }

    // The three windows, all outside the gesture-owning root above. Each hosts its content in a
    // window of its own, which is exactly what keeps its rows tappable while the finger that opened
    // it is still down.
    val menuId = menuItemId
    if (menuId != null) {
        LauncherPopup(anchor = menuAnchor) { placement ->
            ItemMenuContent(
                state = itemMenuState,
                actions = itemMenuActions,
                placement = placement,
                progress = { popup.value },
                onDismiss = onClosePopup,
            )
        }
    }

    optionsAnchor?.let { anchor ->
        LauncherPopup(anchor = anchor) { placement ->
            WorkspaceOptionsContent(
                placement = placement,
                progress = { popup.value },
                onPickWallpaper = {
                    closePopup()
                    onPickWallpaper()
                },
                onOpenWidgets = {
                    closePopup()
                    widgetPickerActions.openWidgetPicker()
                },
                onOpenAppsList = {
                    closePopup()
                    settleDrawer(1f)
                },
                onOpenSettings = {
                    closePopup()
                    onOpenSettings()
                },
            )
        }
    }

    if (widgetPickerState.open) {
        ModalBottomSheet(onDismissRequest = widgetPickerActions::closeWidgetPicker) {
            WidgetPickerContent(state = widgetPickerState, actions = widgetPickerActions)
        }
    }
}

/**
 * One page of the grid.
 *
 * Everything transient about a drag over this page lives here rather than in the controller,
 * because it is all in *this page's* geometry: which cell the finger is over, which icon it is
 * close enough to fold into, and which rearrangement has been dwelt on long enough to commit.
 */
@Composable
private fun WorkspacePage(
    state: HomeUiState,
    actions: HomeActions,
    page: Int,
    items: List<WorkspaceItem>,
    resizingId: Long?,
    draggable: Boolean,
    onOpenFolder: (Long, Rect) -> Unit,
    onEndResize: () -> Unit,
    onWidgetDropped: (Long) -> Unit,
    widgetView: (Int) -> AppWidgetHostView?,
    updateWidgetSize: (AppWidgetHostView, Int, Int) -> Unit,
) {
    val drag = LocalLauncherDrag.current
    val density = LocalDensity.current
    var metrics by remember { mutableStateOf(CellMetrics.Empty) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val placed = remember(items) { items.associate { it.id to it.rect } }
    val iconSizePx = with(density) { (LauncherIconSize * state.iconScale).toPx() }

    // A resize in progress, previewed exactly as a drag is: the widget's own new rect plus whatever
    // it shoved aside, held here until the handle is released. Launcher3 commits on release too,
    // and it matters more than it sounds - a write per whole-cell step re-emits the workspace and
    // visibly reloads every hosted widget on the page.
    var resizeRect by remember { mutableStateOf<CellRect?>(null) }
    var resizePushed by remember { mutableStateOf<Map<Long, CellRect>>(emptyMap()) }
    LaunchedEffect(resizingId) {
        if (resizingId == null) {
            resizeRect = null
            resizePushed = emptyMap()
        }
    }

    // What a drop would do, and what the finger is close enough to fold into. Both are computed from
    // the controller's live position, so both are recomputed on every frame of a drag - which is why
    // they are computed in a child that draws nothing rather than here. Read here, they would
    // recompose this page and all twenty of its cells sixty times a second.
    val previewed = remember { mutableStateOf<DropPlan?>(null) }
    val merge = remember { mutableStateOf<MergeCandidate?>(null) }
    WorkspaceDragPreview(
        page = page,
        items = items,
        placed = placed,
        spec = state.grid,
        bounds = bounds,
        metrics = metrics,
        iconSizePx = iconSizePx,
        previewed = previewed,
        merge = merge,
    )

    CellLayout(
        spec = state.grid,
        onMetrics = { metrics = it },
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .dropTarget(
                key = "page-$page",
                priority = PAGE_PRIORITY,
                onDrop = { dropped, at ->
                    val mergeInto = mergeTargetAt(dropped, at, bounds, metrics, items, iconSizePx)
                    val draggedId = dropped.itemId
                    // Asked before the commit, because the answer needs the hosted view and this is
                    // the last point the payload is in hand. `RESIZE_NONE` widgets get no frame -
                    // offering handles that cannot move is worse than offering none.
                    val landedResizable = dropped.type == LauncherItemType.APPWIDGET &&
                        mergeInto == null &&
                        dropped.appWidgetId?.let { id ->
                            widgetView(id)?.appWidgetInfo?.resizeMode != AppWidgetProviderInfo.RESIZE_NONE
                        } == true
                    val landing = when {
                        mergeInto != null && draggedId != null -> {
                            actions.mergeIntoFolder(mergeInto.id, draggedId)
                            // Into the icon it is joining, which is what that folder looks like.
                            placed[mergeInto.id]?.let { cellBounds(it, bounds, metrics) }
                        }
                        else -> commitDrop(
                            actions = actions,
                            payload = dropped,
                            page = page,
                            // The dwelt plan, not one recomputed here: the user watched this
                            // arrangement happen, and it is the one that must be written. Only a
                            // drop that landed before the dwell's first frame has nothing to
                            // commit, and refusing that would send the item flying back for no
                            // reason - so it is planned on the spot instead.
                            plan = previewed.value
                                ?: planDrop(dropped, at, bounds, metrics, state.grid, placed, page),
                            bounds = bounds,
                            metrics = metrics,
                        )
                    }
                    if (landedResizable && draggedId != null) onWidgetDropped(draggedId)
                    landing
                },
            ),
    ) {
        // The bare page. A plain clickable rather than another pointerInput: the root gesture owner
        // bows out when nothing draggable is under the finger, so this only ever sees taps on empty
        // wallpaper - and the long press on it is the gesture owner's, not this one's.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onEndResize,
                ),
        )

        // An outline, and only for a widget. Launcher3 draws no target marker for an icon at all:
        // the neighbours sliding out of the way already say where it is going, and a filled
        // rectangle under a moving icon reads as a second copy of it. No need to exclude a merge
        // here - a widget can never be folded into anything.
        previewed.value
            ?.takeIf { drag.payload?.type == LauncherItemType.APPWIDGET }
            ?.let { plan ->
                Box(
                    modifier = Modifier
                        .cell(plan.target)
                        .padding(Spacing.xs)
                        .border(
                            PREVIEW_STROKE,
                            MaterialTheme.colorScheme.onSurface.copy(alpha = PREVIEW_ALPHA),
                            RoundedCornerShape(12.dp),
                        ),
                )
            }

        items.forEach { item ->
            // Keyed, or a workspace re-emit reuses one item's node - and its hosted
            // AppWidgetHostView - for another item.
            key(item.id) {
                val isResizing = resizingId == item.id
                val rect = when {
                    isResizing -> resizeRect ?: item.rect
                    else -> resizePushed[item.id]
                        ?: previewed.value?.displaced?.get(item.id)
                        ?: item.rect
                }
                WorkspaceCell(
                    item = item,
                    rect = rect,
                    state = state,
                    actions = actions,
                    page = page,
                    metrics = metrics,
                    isResizing = isResizing,
                    // A lambda, read inside the ring's draw block: merge progress changes on every
                    // frame the finger is near an icon, and passing the value would recompose every
                    // cell on the page for each of those frames.
                    mergeProgress = { merge.value?.takeIf { it.id == item.id }?.progress ?: 0f },
                    iconSizePx = iconSizePx,
                    draggable = draggable,
                    onOpenFolder = onOpenFolder,
                    onResizeStep = { candidateRect, edge ->
                        val pushed = WidgetResize.resizeWithPush(
                            spec = state.grid,
                            candidate = candidateRect,
                            others = placed
                                .filterKeys { it != item.id }
                                .mapValues { (id, at) -> resizePushed[id] ?: at },
                            direction = WidgetResize.pushDirection(edge),
                        )
                        if (pushed == null) {
                            false
                        } else {
                            resizeRect = candidateRect
                            resizePushed = resizePushed + pushed
                            true
                        }
                    },
                    onResizeRelease = {
                        resizeRect?.let { actions.resizeItem(item.id, it, resizePushed) }
                    },
                    widgetView = widgetView,
                    updateWidgetSize = updateWidgetSize,
                    modifier = Modifier.cell(rect),
                )
            }
        }
    }
}

/**
 * The per-frame half of a drag over this page: what a drop would rearrange, and what it would fold
 * into.
 *
 * Emits nothing. It exists purely so the reads of [LauncherDragController.position] live in a
 * composable with no content of its own — a page that read the finger's position directly would
 * recompose itself and every one of its cells on every frame of every drag, which is the difference
 * between a launcher that keeps up with a finger and one that does not. Results go out through
 * [previewed] and [merge], which change far less often than the position does.
 */
@Composable
private fun WorkspaceDragPreview(
    page: Int,
    items: List<WorkspaceItem>,
    placed: Map<Long, CellRect>,
    spec: GridSpec,
    bounds: Rect,
    metrics: CellMetrics,
    iconSizePx: Float,
    previewed: MutableState<DropPlan?>,
    merge: MutableState<MergeCandidate?>,
) {
    val drag = LocalLauncherDrag.current
    val payload = drag.payload?.takeIf { !drag.isSettling && bounds.contains(drag.position) }

    // Where the finger is close enough to an icon's centre to fold into it. A rival to the reorder
    // below rather than a drop target of its own: the cell's middle is a folder and the rest of it
    // is a hole in the grid, so one hit test decides which, and leaving the circle restarts the
    // reorder clock.
    val candidateMerge = payload?.let {
        mergeTargetAt(it, drag.position, bounds, metrics, items, iconSizePx)
    }
    merge.value = candidateMerge

    val candidate = payload
        ?.takeIf { candidateMerge == null }
        ?.let { planDrop(it, drag.position, bounds, metrics, spec, placed, page) }

    // The rearrangement is only *committed* after the dwell, which is what stops a drag crossing the
    // page from rearranging every cell it passes over. The committed plan is also what the drop
    // writes: with a dwell the release point is not necessarily the dwelt point.
    val dwell = remember { ReorderDwell(Motion.ReorderTimeoutMillis) }
    LaunchedEffect(candidate, drag.isDragging) {
        if (!drag.isDragging) {
            dwell.reset()
            previewed.value = null
            return@LaunchedEffect
        }
        // One iteration per frame until the dwell is satisfied, so the clock is the same one the
        // animation runs on rather than a wall clock nothing else uses.
        while (!dwell.update(withFrameMillis { it }, candidate)) {
            if (candidate == null) return@LaunchedEffect
        }
        previewed.value = dwell.committed
    }
}

/** One cell's content, plus its drag source. */
@Composable
private fun WorkspaceCell(
    item: WorkspaceItem,
    rect: CellRect,
    state: HomeUiState,
    actions: HomeActions,
    page: Int,
    metrics: CellMetrics,
    isResizing: Boolean,
    mergeProgress: () -> Float,
    iconSizePx: Float,
    draggable: Boolean,
    onOpenFolder: (Long, Rect) -> Unit,
    onResizeStep: (CellRect, WidgetResize.Edge) -> Boolean,
    onResizeRelease: () -> Unit,
    widgetView: (Int) -> AppWidgetHostView?,
    updateWidgetSize: (AppWidgetHostView, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drag = LocalLauncherDrag.current
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val beingDragged = drag.payload?.itemId == item.id
    // Animated, and held dimmed for the whole settle: the drag layer is still flying towards this
    // cell, and two solid copies of one icon is the artefact that would replace the jump.
    val dim by animateFloatAsState(
        targetValue = if (beingDragged) LauncherTuning.DraggedAlpha else 1f,
        animationSpec = Motion.reorder(),
        label = "cellDim",
    )

    Box(
        modifier = modifier
            // Pulsing while it stands somewhere provisionally, which is what says the arrangement
            // has not been committed yet. Does nothing once the two rects agree.
            .reorderPreview(from = item.rect, to = rect, iconSizePx = iconSizePx)
            .onAppWindowBounds { bounds = it }
            .dragSource(
                key = item.id,
                // A widget showing its resize frame is still movable: its handles sit on the
                // boundary and claim their own touches on the Main pass, so the interior stays the
                // gesture owner's. Disabling the source here is what made every move-drag on a
                // selected widget resize it instead.
                enabled = draggable,
            ) {
                DragPayload(
                    itemId = item.id,
                    type = item.type,
                    label = item.label,
                    key = item.key,
                    canUninstall = item.canUninstall,
                    appWidgetId = item.appWidgetId,
                    rect = item.rect,
                    origin = item.container,
                    originScreen = page,
                    sourceBounds = bounds,
                )
            }
            // Dimmed while it is the thing being dragged, since the drag layer is already drawing
            // a copy of it under the finger.
            .alpha(dim),
        // Centred, not top-aligned: the icon-and-label group is smaller than the cell it sits in,
        // and a grid of icons pinned to the top of taller cells reads as misaligned.
        contentAlignment = Alignment.Center,
    ) {
        // Always composed, never conditionally: it draws nothing at zero progress, and composing it
        // only when a merge is in flight would mean reading that progress here - which is the read
        // this page goes out of its way not to make.
        MergeRing(scale = state.iconScale, progress = mergeProgress)

        when (item.type) {
            LauncherItemType.APPWIDGET -> {
                val widgetId = item.appWidgetId
                if (widgetId == null) {
                    MissingWidget(item.label)
                } else {
                    HostedWidget(
                        appWidgetId = widgetId,
                        widthDp = with(density) {
                            (metrics.cellWidthPx * rect.spanX).toDp().value.toInt()
                        },
                        heightDp = with(density) {
                            (metrics.cellHeightPx * rect.spanY).toDp().value.toInt()
                        },
                        createView = widgetView,
                        updateSize = updateWidgetSize,
                    )
                }
                if (isResizing) {
                    WidgetResizeFrame(
                        // The previewed rect, not the saved one, so each step measures from the
                        // geometry the user is looking at.
                        rect = rect,
                        cellWidthPx = metrics.cellWidthPx,
                        cellHeightPx = metrics.cellHeightPx,
                        onStep = onResizeStep,
                        onRelease = onResizeRelease,
                        // Sized to the widget; the frame grows itself past these bounds.
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }

            LauncherItemType.FOLDER -> LauncherItemIcon(
                item = item,
                scale = state.iconScale,
                showLabel = state.showLabels,
                modifier = Modifier.clickable { onOpenFolder(item.id, bounds) },
            )

            else -> LauncherItemIcon(
                item = item,
                scale = state.iconScale,
                showLabel = state.showLabels,
                modifier = Modifier.clickable { actions.launchFrom(item, bounds) },
            )
        }
    }
}

/**
 * The row that is present on every page.
 *
 * A rank list rather than a cell grid: the hotseat is one row of fixed slots, so an insert shifts
 * its neighbours along — and, once the row is full, pushes the far end of it back onto the
 * workspace. See [com.vayunmathur.launcher.domain.HotseatArrange].
 */
@Composable
private fun Hotseat(
    state: HomeUiState,
    actions: HomeActions,
    draggable: Boolean,
    onOpenFolder: (Long, Rect) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HOTSEAT_HEIGHT)
            .padding(horizontal = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(state.grid.hotseatSlots) { slot ->
            val item = state.hotseat.getOrNull(slot)
            var slotBounds by remember { mutableStateOf(Rect.Zero) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .onAppWindowBounds { slotBounds = it }
                    .dropTarget(
                        key = "hotseat-$slot",
                        priority = HOTSEAT_PRIORITY,
                        // A widget has a span and the hotseat has none to give it.
                        accepts = { it.type != LauncherItemType.APPWIDGET },
                        onDrop = { payload, _ ->
                            val id = payload.itemId
                            if (id == null) {
                                // Straight from the drawer into a hotseat slot.
                                val key = payload.key
                                if (key == null) {
                                    null
                                } else {
                                    actions.addPendingToHotseat(key, slot)
                                    slotBounds
                                }
                            } else {
                                actions.commitMove(
                                    id,
                                    ContainerRef.Hotseat,
                                    0,
                                    CellRect(slot, 0),
                                    rank = slot,
                                )
                                slotBounds
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (item != null) {
                    key(item.id) { HotseatCell(item, state, actions, draggable, onOpenFolder) }
                }
            }
        }
    }
}

@Composable
private fun HotseatCell(
    item: WorkspaceItem,
    state: HomeUiState,
    actions: HomeActions,
    draggable: Boolean,
    onOpenFolder: (Long, Rect) -> Unit,
) {
    val drag = LocalLauncherDrag.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val dim by animateFloatAsState(
        targetValue = if (drag.payload?.itemId == item.id) LauncherTuning.DraggedAlpha else 1f,
        animationSpec = Motion.reorder(),
        label = "hotseatDim",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onAppWindowBounds { bounds = it }
            .dragSource(key = item.id, enabled = draggable) {
                DragPayload(
                    itemId = item.id,
                    type = item.type,
                    label = item.label,
                    key = item.key,
                    canUninstall = item.canUninstall,
                    // The hotseat's "cell" is its slot.
                    rect = CellRect(item.rank, 0),
                    origin = ContainerRef.Hotseat,
                    sourceBounds = bounds,
                )
            }
            .alpha(dim),
        contentAlignment = Alignment.Center,
    ) {
        LauncherItemIcon(
            item = item,
            scale = state.iconScale,
            // Labels are dropped in the hotseat: the slots are narrow, and these are the icons
            // seen often enough not to need naming.
            showLabel = false,
            modifier = Modifier.clickable {
                if (item.type == LauncherItemType.FOLDER) {
                    onOpenFolder(item.id, bounds)
                } else {
                    actions.launchFrom(item, bounds)
                }
            },
        )
    }
}

private fun HomeActions.launchFrom(item: WorkspaceItem, bounds: Rect) = launch(
    item,
    bounds.left.toInt(),
    bounds.top.toInt(),
    bounds.right.toInt(),
    bounds.bottom.toInt(),
)

/**
 * Writes a finished drag, and returns where the item ended up so it has somewhere to fly to.
 *
 * A drop that changes nothing writes nothing: with the popup opening on the long press, a drag only
 * begins once the finger has moved, but it can still wander away and come back, and a `moveTo` that
 * sets the same cell it already had would re-emit the whole workspace for no reason.
 */
private fun commitDrop(
    actions: HomeActions,
    payload: DragPayload,
    page: Int,
    plan: DropPlan?,
    bounds: Rect,
    metrics: CellMetrics,
): Rect? {
    val target = plan?.target ?: return null
    val landing = cellBounds(target, bounds, metrics) ?: return null
    val id = payload.itemId

    if (id == null) {
        // Not on the workspace yet: this came out of the drawer or a popup, so the drop creates the
        // row rather than moving one.
        val shortcut = payload.shortcut
        val key = payload.key
        return when {
            shortcut != null -> {
                actions.addPendingShortcutToHome(shortcut, page, target, plan.displaced)
                landing
            }
            key != null -> {
                actions.addPendingToHome(key, page, target, plan.displaced)
                landing
            }
            else -> null
        }
    }

    val unchanged = payload.origin is ContainerRef.Desktop &&
        payload.originScreen == page &&
        payload.rect == target &&
        plan.displaced.isEmpty()
    if (unchanged) return landing

    // The displaced neighbours go in the same commit, so the page never renders a state the
    // preview did not already show.
    actions.commitMove(id, ContainerRef.Desktop, page, target, 0, plan.displaced)
    return landing
}

/**
 * What a drop at [at] would do to this page.
 *
 * The dragged item is excluded from the layout the plan is made against: an item released back
 * onto its own cell would otherwise be pushed aside by the hole it is still recorded as filling.
 *
 * The push direction comes from the item's own origin cell rather than from the pixel path, which
 * makes it quantised and sticky for free: it changes only when the wanted cell does, and a cascade
 * that flipped direction frame to frame would thrash the page. It is only known for an item already
 * on *this* page — an app out of the drawer has no origin cell, and one from another page has one
 * that means nothing here, so both fall back to the undirected plan.
 */
private fun planDrop(
    payload: DragPayload,
    at: Offset,
    pageBounds: Rect,
    metrics: CellMetrics,
    spec: GridSpec,
    placed: Map<Long, CellRect>,
    page: Int,
): DropPlan? {
    if (metrics.cellWidthPx == 0 || metrics.cellHeightPx == 0) return null
    val (cellX, cellY) = metrics.cellAt(at.x - pageBounds.left, at.y - pageBounds.top)
    val wanted = CellRect(cellX, cellY, payload.rect.spanX, payload.rect.spanY)
    val fromHere = payload.origin is ContainerRef.Desktop && payload.originScreen == page
    val direction = if (fromHere) GridReorder.directionOf(payload.rect, wanted) else null
    return GridPreview.plan(spec, placed, payload.itemId, wanted, direction)
}

/**
 * The icon [at] is close enough to the centre of to fold into, or null for a plain reorder.
 *
 * The icon's centre is taken as its cell's centre. With a label under it the artwork actually sits
 * a few pixels higher, which shifts the circle down by less than the tolerance it is measuring.
 */
private fun mergeTargetAt(
    payload: DragPayload,
    at: Offset,
    pageBounds: Rect,
    metrics: CellMetrics,
    items: List<WorkspaceItem>,
    iconSizePx: Float,
): MergeCandidate? {
    // An app straight out of the drawer has no row to fold in yet, so it lands on the grid.
    if (payload.itemId == null) return null
    if (metrics.cellWidthPx == 0 || metrics.cellHeightPx == 0) return null

    var best: MergeCandidate? = null
    for (item in items) {
        if (item.id == payload.itemId) continue
        if (!FolderRules.canMerge(payload.type, item.type)) continue
        val centre = cellBounds(item.rect, pageBounds, metrics)?.center ?: continue
        val progress = FolderMerge.mergeProgress(
            dx = at.x - centre.x,
            dy = at.y - centre.y,
            iconSizePx = iconSizePx,
            cellWidthPx = metrics.cellWidthPx.toFloat(),
            cellHeightPx = metrics.cellHeightPx.toFloat(),
        )
        if (progress > (best?.progress ?: 0f)) best = MergeCandidate(item.id, progress)
    }
    return best
}

/** The icon a drag is close enough to fold into, and how close - which is what the ring shows. */
private data class MergeCandidate(val id: Long, val progress: Float)

/**
 * Where a cell is on screen, so a dropped item has somewhere to fly to.
 *
 * The leftover pixels [CellLayout] spreads over its leading cells are ignored here: only this
 * rect's centre is used, and the difference is a couple of pixels at the far edge of the grid.
 */
private fun cellBounds(rect: CellRect, pageBounds: Rect, metrics: CellMetrics): Rect? {
    if (metrics.cellWidthPx == 0 || metrics.cellHeightPx == 0) return null
    return Rect(
        left = pageBounds.left + rect.cellX * metrics.cellWidthPx.toFloat(),
        top = pageBounds.top + rect.cellY * metrics.cellHeightPx.toFloat(),
        right = pageBounds.left + rect.right * metrics.cellWidthPx.toFloat(),
        bottom = pageBounds.top + rect.bottom * metrics.cellHeightPx.toFloat(),
    )
}

/** Drop-target priorities: the hotseat and the drop bar both beat the bare page. */
private const val PAGE_PRIORITY = 0
private const val HOTSEAT_PRIORITY = 20

private const val PREVIEW_ALPHA = 0.12f
private val PREVIEW_STROKE = 2.dp
private val HOTSEAT_HEIGHT = 72.dp

/** Past halfway the drawer is the thing in front, and takes the icons with it. */
private const val DRAWER_INTERACTIVE = 0.5f

/**
 * How far the drawer has to have been dragged for a release to open it.
 *
 * Launcher3's `SUCCESS_TRANSITION_PROGRESS`. Measured against the stock launcher, a slow drag of a
 * third of the screen does not open all-apps there either - short swipes get in through the fling
 * below, which is why that threshold matters far more than this one.
 */
private const val DRAWER_COMMIT = 0.5f

/** Enough to put the workspace behind the popup, not enough to hide which icon it belongs to. */
private const val POPUP_SCRIM_ALPHA = 0.32f

/**
 * Past which a swipe counts as a throw rather than a drag.
 *
 * Launcher3's `base_swift_detector_fling_release_velocity`, which is `1dp` compared against a
 * velocity in **px per millisecond** - so a thousand dp per second. Deliberately brisk: the distance
 * threshold above is half the screen, and this is the only other way in, so a value much lower than
 * Launcher3's would open the drawer on swipes meant for the pager.
 */
private val DRAWER_FLING = 1000.dp
