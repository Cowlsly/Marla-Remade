package com.vayunmathur.games.alchemist.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vayunmathur.games.alchemist.R
import com.vayunmathur.games.alchemist.Route
import com.vayunmathur.games.alchemist.platform.AlchemistViewModel
import com.vayunmathur.games.alchemist.platform.HomeActions
import com.vayunmathur.games.alchemist.platform.HomeUiState
import com.vayunmathur.games.alchemist.platform.PlacedItem
import com.vayunmathur.games.alchemist.ui.components.DynamicAlchemyIcon
import com.vayunmathur.library.util.NavBackStack
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Binds [AlchemistViewModel] to the stateless [HomeScreen]. */
@Composable
fun HomePage(
    backStack: NavBackStack<Route>,
    viewModel: AlchemistViewModel,
    onOpenCollection: () -> Unit,
    onOpenGameCenter: () -> Unit
) {
    val availableItems by viewModel.availableItems.collectAsState()
    val paletteItems by viewModel.paletteItems.collectAsState()
    val hideExhausted by viewModel.hideExhausted.collectAsState()
    val allItems by viewModel.allItems.collectAsState()
    val activeItems by viewModel.placedElements.collectAsState()

    HomeScreen(
        state = HomeUiState(
            placedItems = activeItems,
            paletteItems = paletteItems,
            discoveredCount = availableItems.size,
            totalCount = allItems.size,
            hideExhausted = hideExhausted
        ),
        actions = viewModel,
        onOpenCollection = onOpenCollection,
        onOpenGameCenter = onOpenGameCenter,
        onOpenItemDetails = { backStack.add(Route.ItemDetails(it.toInt())) }
    )
}

/**
 * The crafting board, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    onOpenCollection: () -> Unit,
    onOpenGameCenter: () -> Unit,
    onOpenItemDetails: (Long) -> Unit
) {
    val activeItems = state.placedItems
    val paletteItems = state.paletteItems

    val scope = rememberCoroutineScope()

    var bottomBarTopInWindow by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var playAreaOffsetInWindow by remember { mutableStateOf(Offset.Zero) }
    var isDraggingBoardItem by remember { mutableStateOf(false) }

    // Tracking for the current item being "pulled out" of the bottom bar
    var draggingInventoryId by remember { mutableStateOf<Long?>(null) }
    var draggingInventoryOffset by remember { mutableStateOf(Offset.Zero) }

    var contextMenuElementId by remember { mutableStateOf<Long?>(null) }
    var contextMenuExpanded by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }

    val lazyList = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
                if (activeItems.isNotEmpty()) {
                    IconButton(onClick = { actions.clearElements() }) {
                        Icon(
                            painterResource(id = android.R.drawable.ic_menu_close_clear_cancel), "Clear"
                        )
                    }
                }
                IconButton(onClick = onOpenCollection) {
                    Icon(
                        painterResource(id = android.R.drawable.ic_menu_sort_by_size), "Collection"
                    )
                }
                IconButton(onClick = onOpenGameCenter) {
                    Icon(
                        painterResource(id = android.R.drawable.btn_star_big_on), "Achievements"
                    )
                }
                IconButton(onClick = { overflowExpanded = true }) {
                    IconMoreVert()
                }
                DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.hide_maxed_elements)) },
                        trailingIcon = { if (state.hideExhausted) IconCheck() },
                        onClick = {
                            actions.setHideExhausted(!state.hideExhausted)
                            overflowExpanded = false
                        }
                    )
                }
            })
        }) { paddingValues ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 1. PLAY AREA (Full Screen)
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(if (isDraggingBoardItem) 1f else 0f)
                    .onGloballyPositioned {
                        playAreaOffsetInWindow = it.positionInWindow()
                    }) {
                activeItems.forEach { item ->
                    key(item.key) {
                        DraggableElement(item = item, onDragStart = {
                            isDraggingBoardItem = true
                        }, onDragEnd = { finalOffset ->
                            isDraggingBoardItem = false
                            val limitY = bottomBarTopInWindow - playAreaOffsetInWindow.y - 48f
                            // DELETION: Triggered if any part of the item touches the bottom bar
                            if (finalOffset.y > limitY) {
                                actions.removeElement(item.key)
                            } else {
                                actions.updateElementPosition(item.key, finalOffset)
                                actions.tryCombine(item.key, finalOffset)
                            }
                        }, onLongClick = {
                            contextMenuElementId = item.id
                            contextMenuExpanded = true
                        }, onDoubleTap = {
                            actions.duplicateElement(item.key)
                        })
                    }
                }
            }

            // 2. BOTTOM PANEL OVERLAY
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp)
                    .height(192.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // 2.1 INVENTORY COUNT (discovered / total)
                Text(
                    stringResource(
                        R.string.counter, state.discoveredCount, state.totalCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                // 2.2 A-Z LETTER BAR
                val activeLetters = remember(paletteItems) {
                    paletteItems.mapNotNull { it.name.firstOrNull()?.uppercaseChar() }.toSet()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ('A'..'Z').filter { it in activeLetters }.forEach { letter ->
                        Text(
                            text = letter.toString(),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    val index = paletteItems.indexOfFirst {
                                        it.name.firstOrNull()?.uppercaseChar() == letter
                                    }
                                    if (index >= 0) {
                                        scope.launch { lazyList.animateScrollToItem(index) }
                                    }
                                }
                                .padding(horizontal = 2.dp, vertical = 4.dp)
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            bottomBarTopInWindow = it.positionInWindow().y
                        },
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                ) {
                    Crossfade(
                        targetState = isDraggingBoardItem,
                        label = "bottom_bar_crossfade"
                    ) { isDragging ->
                        if (isDragging) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.errorContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painterResource(id = android.R.drawable.ic_delete),
                                    contentDescription = stringResource(R.string.cd_delete),
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        } else {
                            LazyRow(
                                state = lazyList,
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(paletteItems, key = { it.id }) { item ->
                                    var itemPosInWindow by remember { mutableStateOf(Offset.Zero) }

                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .onGloballyPositioned {
                                                itemPosInWindow = it.positionInWindow()
                                            }) {
                                        Box(
                                            Modifier
                                                .size(64.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .combinedClickable(onLongClick = {
                                                    contextMenuElementId = item.id
                                                    contextMenuExpanded = true
                                                }, onClick = {})
                                                .pointerInput(item.id) {
                                                    // Direction split (45°): horizontal drag -> let the LazyRow scroll;
                                                    // vertical drag -> lift the item out to place it on the board.
                                                    val slop = viewConfiguration.touchSlop
                                                    awaitEachGesture {
                                                        val down = awaitFirstDown(requireUnconsumed = false)
                                                        var total = Offset.Zero
                                                        var decided = false
                                                        var pullOut = false
                                                        while (true) {
                                                            val ev = awaitPointerEvent()
                                                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                            if (ch.changedToUpIgnoreConsumed()) {
                                                                if (pullOut) {
                                                                    val limitY = bottomBarTopInWindow - playAreaOffsetInWindow.y - 48f
                                                                    if (draggingInventoryOffset.y < limitY) {
                                                                        actions.placeElement(item.id, draggingInventoryOffset)
                                                                    }
                                                                }
                                                                draggingInventoryId = null
                                                                break
                                                            }
                                                            val dragAmount = ch.position - ch.previousPosition
                                                            total += dragAmount
                                                            if (!decided && total.getDistance() > slop) {
                                                                decided = true
                                                                if (kotlin.math.abs(total.y) > kotlin.math.abs(total.x)) {
                                                                    pullOut = true
                                                                    draggingInventoryId = item.id
                                                                    val fingerInWindow = itemPosInWindow + ch.position
                                                                    draggingInventoryOffset = Offset(
                                                                        x = fingerInWindow.x - playAreaOffsetInWindow.x - 100f,
                                                                        y = fingerInWindow.y - playAreaOffsetInWindow.y - 100f
                                                                    )
                                                                } else break // horizontal -> don't consume; LazyRow scrolls
                                                            }
                                                            if (pullOut) { ch.consume(); draggingInventoryOffset += dragAmount }
                                                        }
                                                    }
                                                }
                                        ) {
                                            DynamicAlchemyIcon(item.id)
                                            if (item.final) {
                                                Icon(
                                                    painterResource(id = android.R.drawable.star_on),
                                                    contentDescription = stringResource(R.string.cd_final_item),
                                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .align(Alignment.BottomEnd)
                                                )
                                            }
                                        }
                                        Text(item.name, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. GLOBAL DRAG OVERLAY (item being pulled out of the bottom bar)
            draggingInventoryId?.let { id ->
                Box(Modifier
                    .offset {
                        IntOffset(
                            draggingInventoryOffset.x.roundToInt(),
                            draggingInventoryOffset.y.roundToInt()
                        )
                    }
                    .size(72.dp)
                ) { DynamicAlchemyIcon(id) }
            }
        }

        if (contextMenuExpanded) {
            DropdownMenu(
                expanded = contextMenuExpanded,
                onDismissRequest = { contextMenuExpanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.see_details)) }, onClick = {
                    contextMenuExpanded = false
                    contextMenuElementId?.let(onOpenItemDetails)
                })
            }
        }
    }
}

@Composable
fun DraggableElement(
    item: PlacedItem,
    onDragStart: () -> Unit,
    onDragEnd: (Offset) -> Unit,
    onLongClick: () -> Unit,
    onDoubleTap: () -> Unit
) {
    var currentOffset by remember(item.key) { mutableStateOf(item.offset) }

    Box(
        Modifier
            .offset {
                IntOffset(currentOffset.x.roundToInt(), currentOffset.y.roundToInt())
            }
            .size(72.dp)
            .combinedClickable(onLongClick = onLongClick, onClick = {})
            .pointerInput(item.key) {
                detectTapGestures(onDoubleTap = { onDoubleTap() })
            }
            .pointerInput(item.key) {
                detectDragGestures(
                    onDragStart = { _ -> onDragStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOffset += dragAmount
                    },
                    onDragEnd = { onDragEnd(currentOffset) },
                    onDragCancel = { onDragEnd(currentOffset) }
                )
            }) { DynamicAlchemyIcon(item.id) }
}
