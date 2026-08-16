package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.data.ChipCategory
import com.vayunmathur.games.logicgate.data.ChipLibrary
import com.vayunmathur.games.logicgate.data.CircuitEvaluator
import com.vayunmathur.games.logicgate.data.EvalResult
import com.vayunmathur.games.logicgate.data.LevelDef
import com.vayunmathur.games.logicgate.data.Levels
import com.vayunmathur.games.logicgate.platform.EvalStatus
import com.vayunmathur.games.logicgate.platform.LogicActions
import com.vayunmathur.games.logicgate.platform.LogicViewModel
import com.vayunmathur.games.logicgate.platform.UiState
import com.vayunmathur.games.logicgate.ui.components.BitDotsRow
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults
import kotlin.math.roundToInt

enum class ChipGroup { BIT, WORD, CUSTOM }

internal fun groupForCategory(cat: ChipCategory): ChipGroup = when (cat) {
    ChipCategory.PRIMITIVE, ChipCategory.FOUNDATION, ChipCategory.ROUTING -> ChipGroup.BIT
    ChipCategory.BUS, ChipCategory.ARITH -> ChipGroup.WORD
    ChipCategory.MEMORY, ChipCategory.CPU -> ChipGroup.CUSTOM
}

private suspend fun AwaitPointerEventScope.awaitFirstDownGlobal(): PointerInputChange {
    while (true) {
        val ev = awaitPointerEvent()
        if (ev.type == PointerEventType.Press) {
            val d = ev.changes.firstOrNull { !it.isConsumed } ?: continue
            return d
        }
    }
}

fun displayInputLabel(level: LevelDef, idx: Int): String {
    val raw = level.inputs.getOrNull(idx) ?: return ""
    val low = raw.lowercase()
    return when (low) {
        "a" -> "Input 1"
        "b" -> "Input 2"
        "c" -> "Input 3"
        "d" -> "Input 4"
        "e" -> "Input 5"
        "f" -> "Input 6"
        "g" -> "Input 7"
        "h" -> "Input 8"
        "in" -> "Input"
        "sel", "select" -> "Select"
        "sel0", "select0", "s0" -> "Select 0"
        "sel1", "select1", "s1" -> "Select 1"
        "sel2", "select2", "s2" -> "Select 2"
        "opcode" -> "Opcode"
        else -> {
            if (low.length == 1 && low[0] in 'a'..'h') "Input ${low[0] - 'a' + 1}"
            else raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}

fun displayOutputLabel(level: LevelDef, idx: Int): String {
    val raw = level.outputs.getOrNull(idx) ?: return ""
    val low = raw.lowercase()
    return when {
        low == "out" || low == "output" -> if (level.outputs.size == 1) "Output" else "Output ${idx + 1}"
        low == "sum" -> "Sum"
        low in listOf("carry", "cout", "borrow") -> "Carry"
        low == "q" -> "Q"
        low == "nq" -> "nQ"
        else -> raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

data class TableRowUi(
    val inputSlices: Map<Int, List<Boolean>>,
    val desiredSlices: Map<Int, List<Boolean>>,
    val actualSlices: Map<Int, List<Boolean>>?
)

private fun cellWidthForInput(level: LevelDef, idx: Int): Dp {
    val w = level.inputWidth(idx)
    return when {
        w >= 8 -> 168.dp // 8*14 + 7*4 = 140 needs 168 for padding
        w >= 4 -> 96.dp  // 4*14+3*4=68 fits 96
        else -> 72.dp
    }
}

private fun cellWidthForOutput(level: LevelDef, idx: Int): Dp {
    val w = level.outputWidth(idx)
    return when {
        w >= 8 -> 168.dp
        w >= 4 -> 96.dp
        else -> 72.dp
    }
}

/**
 * The circuit editor, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from. Everything it derives (truth table, evaluation, terminal layout) is a
 * pure function of [state] and the static level table.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    levelId: String,
    state: UiState,
    unlockedChips: Set<String>,
    actions: LogicActions,
    onBack: () -> Unit = {},
    onOpenLevel: (String) -> Unit = {},
    /**
     * Seeds the win dialog, which the app only ever opens from the evaluation result. A
     * preview sets it so the "level complete" moment can be captured directly.
     */
    initialShowWinDialog: Boolean = false,
) {
    val level = Levels.get(levelId)

    var canvasPosInWindow by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var canvasScale by remember { mutableStateOf(1f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }
    var draggingChipId by remember { mutableStateOf<String?>(null) }
    var draggingChipWindowPos by remember { mutableStateOf(Offset.Zero) }
    var selectedGroup by remember { mutableStateOf<ChipGroup?>(null) }
    var showIoSheet by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val totalInBits = level.totalInputBits
    val displayLimit = if (totalInBits <= 10) minOf(64, (1 shl totalInBits)) else 28
    val inputVectors: List<List<Boolean>> = remember(totalInBits, displayLimit, levelId) {
        val seed = 1234L
        if (totalInBits <= 10) (0 until (1 shl totalInBits)).take(displayLimit).map { c -> List(totalInBits) { i -> ((c shr i) and 1) == 1 } }
        else {
            val rnd = java.util.Random(seed)
            val vs = mutableListOf<List<Boolean>>()
            vs.add(List(totalInBits) { false })
            vs.add(List(totalInBits) { true })
            vs.add(List(totalInBits) { it % 2 == 0 })
            while (vs.size < displayLimit) vs.add(List(totalInBits) { rnd.nextBoolean() })
            vs
        }
    }

    var selectedIdxMutable by remember(levelId) { mutableStateOf(0) }
    var liveFlatInputs by remember(levelId) { mutableStateOf<List<Boolean>?>(null) }

    LaunchedEffect(levelId) {
        actions.selectLevel(levelId)
        liveFlatInputs = null
        selectedIdxMutable = 0
    }
    LaunchedEffect(state.evalStatus) {
        if (liveFlatInputs == null) {
            val failing = (state.evalStatus as? EvalStatus.Ok)?.failingRows
            selectedIdxMutable = if (failing != null && failing.isNotEmpty()) failing.first() % inputVectors.size else 0
        }
    }

    val flatInSelectedBase = remember(inputVectors, selectedIdxMutable) { inputVectors.getOrNull(selectedIdxMutable) ?: emptyList() }
    val effectiveFlat = liveFlatInputs ?: flatInSelectedBase

    val inputSlicesMap: Map<Int, List<Boolean>> = remember(effectiveFlat, level) {
        level.inputs.indices.associateWith { idx ->
            val off = level.inputBitOffset(idx); val wd = level.inputWidth(idx)
            if (off + wd <= effectiveFlat.size) effectiveFlat.slice(off until off + wd) else List(wd) { false }
        }
    }
    val inputBitSlices = inputSlicesMap
    val inputOnMap: Map<Int, Boolean> = remember(effectiveFlat, level) {
        level.inputs.indices.associateWith { idx ->
            val bits = inputSlicesMap[idx] ?: listOf(false)
            bits.any { it }
        }
    }
    val inputDecimals: Map<Int, Int> = remember(effectiveFlat, level) {
        level.inputs.indices.associateWith { idx ->
            val off = level.inputBitOffset(idx); val wd = level.inputWidth(idx)
            val slice = if (off + wd <= effectiveFlat.size) effectiveFlat.slice(off until off + wd) else List(wd) { false }
            ChipLibrary.bitsToInt(slice)
        }
    }
    val targetDef = remember(level.targetChipId) { try { ChipLibrary.get(level.targetChipId) } catch (_: Exception) { null } }
    val desiredFlatOut: List<Boolean> = remember(effectiveFlat, targetDef, level) {
        targetDef?.eval(effectiveFlat)?.take(level.totalOutputBits) ?: emptyList()
    }
    val desiredBitSlices: Map<Int, List<Boolean>> = remember(desiredFlatOut, level) {
        level.outputs.indices.associateWith { oi ->
            val off = level.outputBitOffset(oi); val wd = level.outputWidth(oi)
            if (off + wd <= desiredFlatOut.size) desiredFlatOut.slice(off until off + wd) else List(wd) { false }
        }
    }
    val desiredDecimals: Map<Int, Int> = remember(desiredFlatOut, level) {
        level.outputs.indices.associateWith { oi ->
            val off = level.outputBitOffset(oi); val wd = level.outputWidth(oi)
            val slice = if (off + wd <= desiredFlatOut.size) desiredFlatOut.slice(off until off + wd) else List(wd) { false }
            ChipLibrary.bitsToInt(slice)
        }
    }
    val actualEvalRows: List<List<Boolean>>? = remember(state.circuit, level) {
        val res = CircuitEvaluator.evaluate(level, state.circuit)
        if (res is EvalResult.Success) res.rows else null
    }
    val actualFlatOut: List<Boolean> = remember(actualEvalRows, selectedIdxMutable, liveFlatInputs) {
        if (liveFlatInputs != null) {
            val foundIdx = inputVectors.indexOfFirst { it == liveFlatInputs }
            if (foundIdx >= 0) actualEvalRows?.getOrNull(foundIdx) ?: emptyList() else actualEvalRows?.getOrNull(selectedIdxMutable) ?: emptyList()
        } else actualEvalRows?.getOrNull(selectedIdxMutable) ?: emptyList()
    }
    val actualBitSlices: Map<Int, List<Boolean>> = remember(actualFlatOut, level) {
        level.outputs.indices.associateWith { oi ->
            val off = level.outputBitOffset(oi); val wd = level.outputWidth(oi)
            if (off + wd <= actualFlatOut.size) actualFlatOut.slice(off until off + wd) else List(wd) { false }
        }
    }
    val actualDecimals: Map<Int, Int> = remember(actualFlatOut, level) {
        level.outputs.indices.associateWith { oi ->
            val off = level.outputBitOffset(oi); val wd = level.outputWidth(oi)
            val slice = if (off + wd <= actualFlatOut.size) actualFlatOut.slice(off until off + wd) else List(wd) { false }
            ChipLibrary.bitsToInt(slice)
        }
    }

    if (state.currentLevelId != levelId) {
        LoadingState(
            message = stringResource(R.string.loading),
            modifier = Modifier.fillMaxSize().background(Color(0xFF0F1E2D)),
        )
        return
    }

    val gateCost = state.circuit.totalNandCost()

    fun toggleInput(idx: Int) {
        val off = level.inputBitOffset(idx)
        val wd = level.inputWidth(idx)
        if (off >= effectiveFlat.size && effectiveFlat.isNotEmpty()) return
        val baseFlat = if (effectiveFlat.size == totalInBits) effectiveFlat else flatInSelectedBase.ifEmpty { List(totalInBits) { false } }
        val mutable = baseFlat.toMutableList()
        if (wd == 1) {
            if (off < mutable.size) mutable[off] = !mutable[off]
        } else {
            val slice = if (off + wd <= mutable.size) mutable.slice(off until off + wd) else List(wd) { false }
            var v = ChipLibrary.bitsToInt(slice)
            v = (v + 1) % (1 shl wd)
            for (k in 0 until wd) if (off + k < mutable.size) mutable[off + k] = ((v shr k) and 1) == 1
        }
        liveFlatInputs = mutable.toList()
        val found = inputVectors.indexOfFirst { it == mutable }
        if (found >= 0) selectedIdxMutable = found
    }

    val tableRows: List<TableRowUi> = remember(inputVectors, level, targetDef, actualEvalRows) {
        inputVectors.mapIndexed { rowIdx, flat ->
            val inSlices = level.inputs.indices.associateWith { i ->
                val off = level.inputBitOffset(i); val wd = level.inputWidth(i)
                if (off + wd <= flat.size) flat.slice(off until off + wd) else List(wd) { false }
            }
            val desiredFlat = targetDef?.eval(flat)?.take(level.totalOutputBits) ?: emptyList()
            val desSlices = level.outputs.indices.associateWith { oi ->
                val off = level.outputBitOffset(oi); val wd = level.outputWidth(oi)
                if (off + wd <= desiredFlat.size) desiredFlat.slice(off until off + wd) else List(wd) { false }
            }
            val actualFlat = actualEvalRows?.getOrNull(rowIdx)
            val actSlices = actualFlat?.let { af ->
                level.outputs.indices.associateWith { oi ->
                    val off = level.outputBitOffset(oi); val wd = level.outputWidth(oi)
                    if (off + wd <= af.size) af.slice(off until off + wd) else List(wd) { false }
                }
            }
            TableRowUi(inSlices, desSlices, actSlices)
        }
    }
    val failingSet = (state.evalStatus as? EvalStatus.Ok)?.failingRows?.toSet() ?: emptySet()

    // Win popup: surface as soon as the level is fully correct.
    val isWon = (state.evalStatus as? EvalStatus.Ok)?.isFullyCorrect == true
    var showWinDialog by remember(levelId) { mutableStateOf(initialShowWinDialog) }
    var winDismissed by remember(levelId) { mutableStateOf(false) }
    LaunchedEffect(isWon) { if (isWon && !winDismissed) showWinDialog = true }
    val nextLevelId = remember(levelId) {
        val idx = Levels.all.indexOfFirst { it.id == levelId }
        if (idx >= 0) Levels.all.getOrNull(idx + 1)?.id else null
    }
    val availableGroups = remember(level.allowedChipIds, unlockedChips) {
        level.allowedChipIds.filter { it in unlockedChips }
            .mapNotNull { try { groupForCategory(ChipLibrary.get(it).category) } catch (_: Exception) { null } }
            .toSet()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxW = maxWidth
        val maxH = maxHeight
        val isCompact = maxW < 600.dp
        val isPortrait = maxH > maxW
        val isCompactPortrait = isCompact && isPortrait
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(text = level.displayName, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Turing.headerPink, maxLines = 1)
                    },
                    navigationIcon = { IconNavigation(onBack) },
                    actions = {
                        Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Turing.rightTabOn).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(stringResource(R.string.gate, gateCost), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        AppBarActionBtn(glyph = "↩", enabled = state.canUndo) { actions.undo() }
                        AppBarActionBtn(glyph = "↪", enabled = state.canRedo) { actions.redo() }
                        Spacer(modifier = Modifier.width(4.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Turing.headerBg, titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White)
                )
            },
            containerColor = Turing.headerBg
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).background(Turing.headerBg)) {
                if (isCompactPortrait) {
                    // Phone portrait – solid blocks canvas hero
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { coords ->
                        canvasPosInWindow = coords.positionInWindow()
                        canvasSize = Size(coords.size.width.toFloat(), coords.size.height.toFloat())
                    }) {
                        CircuitCanvas(
                            level = level, gates = state.circuit.gates, wires = state.circuit.wires, outputMaps = state.circuit.outputMappings,
                            inputPositions = state.circuit.inputPositions, outputPositions = state.circuit.outputPositions,
                            wiringFrom = state.wiringFrom,
                            onCreateWire = { f: com.vayunmathur.games.logicgate.data.WireEnd, t: com.vayunmathur.games.logicgate.data.WireEnd -> actions.createWire(f, t) },
                            onStartWiring = { end: com.vayunmathur.games.logicgate.data.WireEnd -> actions.startWiring(end) },
                            onCancelWiring = { actions.cancelWiring() },
                            onGateMoveFinished = { id: String, x: Float, y: Float -> actions.onGateMoveFinished(id, x, y) },
                            onInputTermMoveFinished = { idx: Int, x: Float, y: Float -> actions.onInputMoveFinished(idx, x, y) },
                            onOutputTermMoveFinished = { idx: Int, x: Float, y: Float -> actions.onOutputMoveFinished(idx, x, y) },
                            onGateMove = { id: String, x: Float, y: Float -> actions.onGateMoved(id, x, y) },
                            onInputTermMove = { idx: Int, x: Float, y: Float -> actions.onInputMoved(idx, x, y) },
                            onOutputTermMove = { idx: Int, x: Float, y: Float -> actions.onOutputMoved(idx, x, y) },
                            onGateDelete = { id: String -> actions.removeGate(id) },
                            onWireDelete = { id: String -> actions.removeWire(id) },
                            onOutputMapDelete = { idx: Int -> actions.removeOutputMapping(idx) },
                            dragGhostLineEnd = state.dragGhostLineEnd,
                            onGhostLine = { off: Offset? -> actions.updateGhostLine(off) },
                            inputValues = inputDecimals, desiredOutputValues = desiredDecimals, outputValues = actualDecimals,
                            modifier = Modifier.fillMaxSize(), isCompact = true,
                            onToggleInput = { idx: Int -> toggleInput(idx) },
                            inputOnMap = inputOnMap,
                            inputBitSlices = inputBitSlices,
                            outputBitSlicesActual = actualBitSlices,
                            onViewportChange = { s: Float, o: Offset -> canvasScale = s; canvasOffset = o },
                            selectedGateId = state.selectedGateInstanceId,
                            onSelectGate = { id: String? -> actions.selectGate(id) }
                        )
                        draggingChipId?.let { chipId ->
                            val localOffset = Offset(draggingChipWindowPos.x - canvasPosInWindow.x, draggingChipWindowPos.y - canvasPosInWindow.y)
                            val isOver = localOffset.x >= 0f && localOffset.x <= canvasSize.width && localOffset.y >= 0f && localOffset.y <= canvasSize.height
                            val def = try { ChipLibrary.get(chipId) } catch (_: Exception) { null }
                            if (def != null) {
                                // Ghost previews the actual placed component (blue rect at real size, scaled to the canvas zoom).
                                val (gwPx, ghPx) = gatePlacedSizePx(def, density, textMeasurer)
                                val vw = gwPx * canvasScale; val vh = ghPx * canvasScale
                                Box(modifier = Modifier.offset {
                                    IntOffset((localOffset.x - vw / 2f).roundToInt(), (localOffset.y - vh / 2f).roundToInt())
                                }.size(with(density) { vw.toDp() }, with(density) { vh.toDp() }).clip(RoundedCornerShape(8.dp)).background(Turing.gateBlue.copy(alpha = if (isOver) 0.95f else 0.55f)).border(if (isOver) 2.dp else 1.dp, if (isOver) Color(0xFF22C55E) else Turing.gateBlueStroke, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                    Text(text = def.displayName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
                                }
                            }
                        }
                    }
                    MobileFilterRow(selected = selectedGroup, onSelect = { selectedGroup = it }, availableGroups = availableGroups, modifier = Modifier.fillMaxWidth())
                    MobileInventoryBar(
                        allowed = level.allowedChipIds, unlockedChips = unlockedChips, selectedGroup = selectedGroup,
                        onChipDragStart = { chipId: String, windowOffset: Offset -> draggingChipId = chipId; draggingChipWindowPos = windowOffset },
                        onChipDrag = { chipId: String, windowOffset: Offset -> draggingChipId = chipId; draggingChipWindowPos = windowOffset },
                        onChipDrop = { chipId: String, windowOffset: Offset ->
                            val local = Offset(windowOffset.x - canvasPosInWindow.x, windowOffset.y - canvasPosInWindow.y)
                            val isOver = local.x >= 0f && local.x <= canvasSize.width && local.y >= 0f && local.y <= canvasSize.height
                            if (isOver) {
                                // Map screen drop -> content coords (undo pan/zoom), centered under the finger using the real gate size.
                                val def = try { ChipLibrary.get(chipId) } catch (_: Exception) { null }
                                val (gwPx, ghPx) = if (def != null) gatePlacedSizePx(def, density, textMeasurer) else 0f to 0f
                                val content = Offset((local.x - canvasOffset.x) / canvasScale - gwPx / 2f, (local.y - canvasOffset.y) / canvasScale - ghPx / 2f)
                                actions.addGateAt(chipId, content.x, content.y)
                            }
                            draggingChipId = null; draggingChipWindowPos = Offset.Zero
                        }, modifier = Modifier.fillMaxWidth()
                    )
                    MobileTestbench(
                        level = level,
                        tableRows = tableRows,
                        selectedIdx = selectedIdxMutable,
                        onSelectRow = { rowIdx: Int ->
                            selectedIdxMutable = rowIdx
                            liveFlatInputs = inputVectors.getOrNull(rowIdx)
                        },
                        outputsConnected = state.circuit.outputMappings.isNotEmpty(),
                        failingSet = failingSet,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // Tablet/landscape – keep side panels but still solid blocks
                    Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                        Row(modifier = Modifier.weight(1f).fillMaxWidth().background(Turing.bg)) {
                            MobileLeftPanel(tick = selectedIdxMutable, simSpeed = if (selectedIdxMutable == 0) 0 else 20, level = level, inputDecimals = inputDecimals, inputBitSlices = inputBitSlices, desiredDecimals = desiredDecimals, desiredBitSlices = desiredBitSlices, actualDecimals = actualDecimals, modifier = Modifier.width(MobileDimens.leftPanelW).fillMaxHeight())
                            MobileMiddleToolbar(onClear = { actions.clearCircuit() }, onUndo = { actions.undo() }, onRedo = { actions.redo() }, canUndo = state.canUndo, canRedo = state.canRedo, modifier = Modifier.width(MobileDimens.toolbarWidth).fillMaxHeight())
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { coords ->
                                canvasPosInWindow = coords.positionInWindow(); canvasSize = Size(coords.size.width.toFloat(), coords.size.height.toFloat())
                            }) {
                                CircuitCanvas(
                                    level = level, gates = state.circuit.gates, wires = state.circuit.wires, outputMaps = state.circuit.outputMappings,
                                    inputPositions = state.circuit.inputPositions, outputPositions = state.circuit.outputPositions,
                                    wiringFrom = state.wiringFrom,
                                    onCreateWire = { f: com.vayunmathur.games.logicgate.data.WireEnd, t: com.vayunmathur.games.logicgate.data.WireEnd -> actions.createWire(f, t) },
                                    onStartWiring = { end: com.vayunmathur.games.logicgate.data.WireEnd -> actions.startWiring(end) },
                                    onCancelWiring = { actions.cancelWiring() },
                                    onGateMoveFinished = { id: String, x: Float, y: Float -> actions.onGateMoveFinished(id, x, y) },
                                    onInputTermMoveFinished = { idx: Int, x: Float, y: Float -> actions.onInputMoveFinished(idx, x, y) },
                                    onOutputTermMoveFinished = { idx: Int, x: Float, y: Float -> actions.onOutputMoveFinished(idx, x, y) },
                                    onGateMove = { id: String, x: Float, y: Float -> actions.onGateMoved(id, x, y) },
                                    onInputTermMove = { idx: Int, x: Float, y: Float -> actions.onInputMoved(idx, x, y) },
                                    onOutputTermMove = { idx: Int, x: Float, y: Float -> actions.onOutputMoved(idx, x, y) },
                                    onGateDelete = { id: String -> actions.removeGate(id) },
                                    onWireDelete = { id: String -> actions.removeWire(id) },
                                    onOutputMapDelete = { idx: Int -> actions.removeOutputMapping(idx) },
                                    dragGhostLineEnd = state.dragGhostLineEnd,
                                    onGhostLine = { off: Offset? -> actions.updateGhostLine(off) },
                                    inputValues = inputDecimals, desiredOutputValues = desiredDecimals, outputValues = actualDecimals,
                                    modifier = Modifier.fillMaxSize(), isCompact = isCompact,
                                    onToggleInput = { idx: Int -> toggleInput(idx) },
                                    inputOnMap = inputOnMap,
                                    inputBitSlices = inputBitSlices,
                                    outputBitSlicesActual = actualBitSlices,
                                    onViewportChange = { s: Float, o: Offset -> canvasScale = s; canvasOffset = o },
                                    selectedGateId = state.selectedGateInstanceId,
                                    onSelectGate = { id: String? -> actions.selectGate(id) }
                                )
                                draggingChipId?.let { chipId ->
                                    val localOffset = Offset(draggingChipWindowPos.x - canvasPosInWindow.x, draggingChipWindowPos.y - canvasPosInWindow.y)
                                    val isOver = localOffset.x >= 0f && localOffset.x <= canvasSize.width && localOffset.y >= 0f && localOffset.y <= canvasSize.height
                                    val def = try { ChipLibrary.get(chipId) } catch (_: Exception) { null }
                                    if (def != null) {
                                        val (gwPx, ghPx) = gatePlacedSizePx(def, density, textMeasurer)
                                        val vw = gwPx * canvasScale; val vh = ghPx * canvasScale
                                        Box(modifier = Modifier.offset {
                                            IntOffset((localOffset.x - vw / 2f).roundToInt(), (localOffset.y - vh / 2f).roundToInt())
                                        }.size(with(density) { vw.toDp() }, with(density) { vh.toDp() }).clip(RoundedCornerShape(8.dp)).background(Turing.gateBlue.copy(alpha = if (isOver) 0.95f else 0.55f)).border(if (isOver) 2.dp else 1.dp, if (isOver) Color(0xFF22C55E) else Turing.gateBlueStroke, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                            Text(text = def.displayName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
                                        }
                                    }
                                }
                            }
                        }
                        MobileFilterRow(selected = selectedGroup, onSelect = { selectedGroup = it }, availableGroups = availableGroups, modifier = Modifier.fillMaxWidth())
                        MobileInventoryBar(
                            allowed = level.allowedChipIds, unlockedChips = unlockedChips, selectedGroup = selectedGroup,
                            onChipDragStart = { chipId: String, windowOffset: Offset -> draggingChipId = chipId; draggingChipWindowPos = windowOffset },
                            onChipDrag = { chipId: String, windowOffset: Offset -> draggingChipId = chipId; draggingChipWindowPos = windowOffset },
                            onChipDrop = { chipId: String, windowOffset: Offset ->
                                val local = Offset(windowOffset.x - canvasPosInWindow.x, windowOffset.y - canvasPosInWindow.y)
                                val isOver = local.x >= 0f && local.x <= canvasSize.width && local.y >= 0f && local.y <= canvasSize.height
                                if (isOver) {
                                    val def = try { ChipLibrary.get(chipId) } catch (_: Exception) { null }
                                    val (gwPx, ghPx) = if (def != null) gatePlacedSizePx(def, density, textMeasurer) else 0f to 0f
                                    val content = Offset((local.x - canvasOffset.x) / canvasScale - gwPx / 2f, (local.y - canvasOffset.y) / canvasScale - ghPx / 2f)
                                    actions.addGateAt(chipId, content.x, content.y)
                                }
                                draggingChipId = null; draggingChipWindowPos = Offset.Zero
                            }, modifier = Modifier.fillMaxWidth()
                        )
                        MobileTestbench(
                            level = level,
                            tableRows = tableRows,
                            selectedIdx = selectedIdxMutable,
                            onSelectRow = { rowIdx: Int ->
                                selectedIdxMutable = rowIdx
                                liveFlatInputs = inputVectors.getOrNull(rowIdx)
                            },
                            outputsConnected = state.circuit.outputMappings.isNotEmpty(),
                            failingSet = failingSet,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (showIoSheet) {
                    MobileIoSheet(level = level, inputDecimals = inputDecimals, inputBitSlices = inputBitSlices, desiredDecimals = desiredDecimals, desiredBitSlices = desiredBitSlices, actualDecimals = actualDecimals, actualBitSlices = actualBitSlices, onDismiss = { showIoSheet = false })
                }
            }
        }
        if (showWinDialog) {
            AlertDialog(
                onDismissRequest = { showWinDialog = false; winDismissed = true },
                title = { Text(stringResource(R.string.level_complete_title), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF22C55E)) },
                text = { Text(stringResource(R.string.level_complete_body, level.displayName), fontSize = 15.sp, color = Color(0xFFB8C6D8)) },
                confirmButton = {
                    if (nextLevelId != null) {
                        Button(onClick = { showWinDialog = false; onOpenLevel(nextLevelId) }) {
                            Text(stringResource(R.string.next_level), fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(onClick = { showWinDialog = false; onBack() }) {
                            Text(stringResource(R.string.back_to_map), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showWinDialog = false; winDismissed = true }) {
                        Text(stringResource(R.string.keep_editing))
                    }
                }
            )
        }
    }
}

@Composable
private fun MobileFilterRow(selected: ChipGroup?, onSelect: (ChipGroup?) -> Unit, availableGroups: Set<ChipGroup>, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Row(modifier = modifier.height(48.dp).horizontalScroll(scroll).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val items: List<Pair<ChipGroup?, String>> = listOf(null to "ALL", ChipGroup.BIT to "BIT", ChipGroup.WORD to "WORD", ChipGroup.CUSTOM to "CUSTOM")
            .filter { it.first == null || it.first in availableGroups } // hide filters with no unlocked chips
        items.forEach { pair ->
            val g = pair.first
            val label = pair.second
            val isSel = selected == g
            Box(modifier = Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (isSel) Turing.rightTabOn else Color.Transparent).border(1.dp, if (isSel) Color(0xFF5A667A) else Color.White.copy(alpha = 0.32f), RoundedCornerShape(20.dp)).clickable { onSelect(g) }.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 14.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium, color = if (isSel) Color.White else Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun MobileInventoryBar(
    allowed: List<String>, unlockedChips: Set<String>, selectedGroup: ChipGroup?,
    onChipDragStart: (chipId: String, global: Offset) -> Unit,
    onChipDrag: (chipId: String, global: Offset) -> Unit,
    onChipDrop: (chipId: String, global: Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val rowScroll = rememberScrollState()
    Row(modifier = modifier.height(60.dp).background(Color(0xFF1A2332)).horizontalScroll(rowScroll).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val filtered = allowed.filter { it in unlockedChips }.filter { chipId ->
            if (selectedGroup == null) true else {
                val def = try { ChipLibrary.get(chipId) } catch (_: Exception) { null }
                def?.let { groupForCategory(it.category) == selectedGroup } ?: true
            }
        }.sortedBy { try { ChipLibrary.get(it).nandCost } catch (_: Exception) { 999 } }
        filtered.forEach { chipId ->
            MobileDraggableChipItem(chipId = chipId, chipOnDragStart = { id: String, g: Offset -> onChipDragStart(id, g) }, chipOnDrag = { id: String, g: Offset -> onChipDrag(id, g) }, chipOnDrop = { id: String, g: Offset -> onChipDrop(id, g) })
        }
        if (filtered.isEmpty()) {
            Text(stringResource(R.string.no_chips_all), fontSize = 13.sp, color = Color(0xFF6B7D96), modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun MobileDraggableChipItem(chipId: String, chipOnDragStart: (String, Offset) -> Unit, chipOnDrag: (String, Offset) -> Unit, chipOnDrop: (String, Offset) -> Unit) {
    val def = ChipLibrary.get(chipId)
    val baseCol = when (def.category) {
        ChipCategory.PRIMITIVE -> Color(0xFF153A45)
        ChipCategory.FOUNDATION -> Color(0xFF144A38)
        ChipCategory.ROUTING -> Color(0xFF4A3514)
        ChipCategory.BUS -> Color(0xFF2B284A)
        ChipCategory.ARITH -> Color(0xFF5A2A14)
        ChipCategory.MEMORY -> Color(0xFF3A1E52)
        ChipCategory.CPU -> Color(0xFF5E1840)
    }
    val busW = def.dominantBusWidth()
    val busColor = when (busW) { 4 -> Color(0xFFFFA126); 8 -> Color(0xFF4FC3FF); else -> Color(0xFF2BE4B8) }
    var chipPosInWindow by remember { mutableStateOf(Offset.Zero) }
    val chipPosState by rememberUpdatedState(chipPosInWindow)
    var isDragging by remember { mutableStateOf(false) }
    // Solid color only, one text – block name – no circles inside
    Box(modifier = Modifier.onGloballyPositioned { c -> chipPosInWindow = c.positionInWindow() }.widthIn(min = 52.dp, max = 132.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(baseCol).border(if (isDragging) 1.4.dp else 0.8.dp, if (isDragging) Color.White else busColor.copy(alpha = 0.65f), RoundedCornerShape(10.dp)).pointerInput(chipId) {
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDownGlobal()
                val startWindow = chipPosState + down.position
                var dragTotal = Offset.Zero
                var decided = false      // direction resolved yet?
                var pullOut = false      // vertical drag => lift the chip out to place it
                var curWindow = startWindow
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (ch.changedToUpIgnoreConsumed()) {
                        if (pullOut) chipOnDrop(chipId, curWindow)
                        isDragging = false; break
                    }
                    val delta = ch.position - ch.previousPosition
                    dragTotal += delta
                    curWindow += delta
                    if (!decided && dragTotal.getDistance() > slop) {
                        decided = true
                        // 45° split: more vertical -> pull out; more horizontal -> leave it to the row's scroll.
                        if (kotlin.math.abs(dragTotal.y) > kotlin.math.abs(dragTotal.x)) {
                            pullOut = true; isDragging = true; chipOnDragStart(chipId, curWindow)
                        } else break // don't consume: horizontalScroll parent takes over
                    }
                    if (pullOut) { ch.consume(); chipOnDrag(chipId, curWindow) }
                }
            }
        }
    }, contentAlignment = Alignment.Center) {
        Text(
            text = def.displayName,
            modifier = Modifier.padding(horizontal = 8.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileIoSheet(level: LevelDef, inputDecimals: Map<Int, Int>, inputBitSlices: Map<Int, List<Boolean>>, desiredDecimals: Map<Int, Int>, desiredBitSlices: Map<Int, List<Boolean>>, actualDecimals: Map<Int, Int>, actualBitSlices: Map<Int, List<Boolean>>, onDismiss: () -> Unit) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color(0xFF1D2A3A), contentColor = Color.White, scrimColor = Color.Black.copy(alpha = 0.4f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Turing.leftPanelCard).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.inputs), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                level.inputs.forEachIndexed { idx, _ ->
                    val dec = inputDecimals[idx] ?: 0
                    val bits = inputBitSlices[idx] ?: emptyList()
                    val label = displayInputLabel(level, idx)
                    Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, fontSize = 14.sp, color = Color(0xFFB8C6D8), modifier = Modifier.weight(1f))
                        BitDotsRow(bits = bits, dotSize = 16.dp, spacing = 6.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("$dec", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.width(48.dp))
                    }
                }
            }
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Turing.leftPanelCard).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.outputs), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                level.outputs.forEachIndexed { idx, _ ->
                    val decD = desiredDecimals[idx] ?: 0
                    val bitsD = desiredBitSlices[idx] ?: emptyList()
                    val decA = actualDecimals[idx] ?: decD
                    val bitsA = actualBitSlices[idx] ?: bitsD
                    val outLabel = displayOutputLabel(level, idx)
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.expected, outLabel), fontSize = 13.sp, color = Turing.orangeLabel, modifier = Modifier.weight(1f))
                            BitDotsRow(bits = bitsD, dotSize = 16.dp, spacing = 6.dp)
                            Text("$decD", fontSize = 14.sp, color = Color.White, modifier = Modifier.width(48.dp))
                        }
                        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.got_2, outLabel), fontSize = 13.sp, color = if (decD == decA) Turing.orangeLabel else Color(0xFFFF8A8A), modifier = Modifier.weight(1f))
                            BitDotsRow(bits = bitsA, dotSize = 16.dp, spacing = 6.dp)
                            Text("$decA", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (decD == decA) Color(0xFF8EF0B0) else Color(0xFFFF8A8A), modifier = Modifier.width(48.dp))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AppBarActionBtn(glyph: String, enabled: Boolean = true, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
            .background(Turing.iconBg.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, fontSize = 18.sp, color = if (enabled) tint else tint.copy(alpha = 0.4f))
    }
}

@Composable
private fun MobileLeftPanel(tick: Int, simSpeed: Int, level: LevelDef, inputDecimals: Map<Int, Int>, inputBitSlices: Map<Int, List<Boolean>>, desiredDecimals: Map<Int, Int>, desiredBitSlices: Map<Int, List<Boolean>>, actualDecimals: Map<Int, Int>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Turing.leftPanelBg).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Turing.leftPanelCard).padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.tick), fontSize = 13.sp, color = Color(0xFF8AA0BB)); Text("$tick", fontSize = 13.sp, color = Color.White) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.sim_speed), fontSize = 13.sp, color = Color(0xFF8AA0BB)); Text(stringResource(R.string.hz, simSpeed), fontSize = 13.sp, color = Color.White) }
        }
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Turing.leftPanelCard).padding(12.dp)) {
            Text(stringResource(R.string.inputs), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            level.inputs.forEachIndexed { idx, _ ->
                val dec = inputDecimals[idx] ?: 0
                val bits = inputBitSlices[idx] ?: emptyList()
                val label = displayInputLabel(level, idx)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 13.sp, color = Color(0xFFB8C6D8)); Spacer(modifier = Modifier.height(4.dp)); BitDotsRow(bits = bits, dotSize = 10.dp); Spacer(modifier = Modifier.height(3.dp)); Text("$dec", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.outputs), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            level.outputs.forEachIndexed { idx, _ ->
                val dec = actualDecimals[idx] ?: desiredDecimals[idx] ?: 0
                val bits = desiredBitSlices[idx] ?: emptyList()
                val label = displayOutputLabel(level, idx)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 13.sp, color = Color(0xFFB8C6D8)); Spacer(modifier = Modifier.height(4.dp)); BitDotsRow(bits = bits, dotSize = 10.dp); Spacer(modifier = Modifier.height(3.dp)); Text("$dec", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun MobileMiddleToolbar(onClear: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit, canUndo: Boolean, canRedo: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Turing.iconBarBg).padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MobileToolbarBtn("⊕", onClick = {}); MobileToolbarBtn("⊖", onClick = {}); Spacer(modifier = Modifier.height(4.dp)); MobileToolbarBtn("▶", sub = "${20}kHz", onClick = {}); MobileToolbarBtn("↗", onClick = onRedo); MobileToolbarBtn("↩", onClick = { if (canUndo) onUndo() }); MobileToolbarBtn("■", onClick = onClear)
        Box(modifier = Modifier.height(1.dp).fillMaxWidth(0.6f).background(Color(0xFF2A3A50)))
        MobileToolbarBtn("⬚", onClick = {}); MobileToolbarBtn("🗑", onClick = onClear); MobileToolbarBtn("✎", onClick = {}); MobileToolbarBtn("◍", onClick = {}); Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Turing.iconBg).border(0.8.dp, Color(0xFF2E425C), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text("8↔", fontSize = 13.sp, color = Color.White) }
    }
}

@Composable
private fun MobileToolbarBtn(text: String, sub: String? = null, onClick: () -> Unit = {}) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Turing.iconBg).border(0.8.dp, Color(0xFF2E425C), RoundedCornerShape(10.dp)).clickable { onClick() }, contentAlignment = Alignment.Center) { Text(text, fontSize = 20.sp, color = Color(0xFF9AA3BB)) }
        if (sub != null) Text(sub, fontSize = 11.sp, color = Color(0xFF7A8AA3))
    }
}

@Composable
private fun MobileTestbench(
    level: LevelDef,
    tableRows: List<TableRowUi>,
    selectedIdx: Int,
    onSelectRow: (Int) -> Unit,
    outputsConnected: Boolean,
    failingSet: Set<Int>,
    modifier: Modifier = Modifier
) {
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val inputCellWs = level.inputs.indices.map { cellWidthForInput(level, it) }
    val outCellWs = level.outputs.indices.map { cellWidthForOutput(level, it) }
    val checkW = 28.dp

    Column(
        modifier = modifier
            .background(Turing.bottomBg)
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .verticalScroll(vScroll)
            .navigationBarsPadding()
    ) {
            Row(modifier = Modifier.horizontalScroll(hScroll)) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    // Header – fixed grid, same padding as rows
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        level.inputs.forEachIndexed { idx, _ ->
                            Box(modifier = Modifier.width(inputCellWs[idx]), contentAlignment = Alignment.Center) {
                                Text(displayInputLabel(level, idx), fontSize = 12.sp, color = Turing.orangeLabel, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                        Box(modifier = Modifier.width(1.dp).height(16.dp).background(Color(0xFF3A3A52)))
                        level.outputs.forEachIndexed { idx, _ ->
                            Box(modifier = Modifier.width(outCellWs[idx]), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.exp), fontSize = 12.sp, color = Turing.orangeLabel, fontWeight = FontWeight.Bold)
                            }
                        }
                        level.outputs.forEachIndexed { idx, _ ->
                            Box(modifier = Modifier.width(outCellWs[idx]), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.got), fontSize = 12.sp, color = Turing.orangeLabel, fontWeight = FontWeight.Bold)
                            }
                        }
                        Box(modifier = Modifier.width(checkW))
                    }
                    Box(modifier = Modifier.height(1.dp).background(Color(0xFF3A3A52).copy(alpha = 0.5f)))
                    tableRows.forEachIndexed { rowIdx, row ->
                        val isSelected = rowIdx == selectedIdx
                        val isFailing = failingSet.contains(rowIdx)
                        Row(
                            modifier = Modifier
                                .heightIn(min = 28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        isFailing -> Color(0x1AFF8A8A)
                                        isSelected -> Turing.iconBg
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable { onSelectRow(rowIdx) }
                                .padding(vertical = 2.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            level.inputs.forEachIndexed { inIdx, _ ->
                                Box(modifier = Modifier.width(inputCellWs[inIdx]), contentAlignment = Alignment.Center) {
                                    val bits = row.inputSlices[inIdx] ?: emptyList()
                                    BitDotsRow(bits = bits, dotSize = 12.dp, spacing = 4.dp, maxDots = level.inputWidth(inIdx))
                                }
                            }
                            Box(modifier = Modifier.width(1.dp).height(20.dp).background(Color(0xFF3A3A52).copy(alpha = 0.5f)))
                            level.outputs.forEachIndexed { outIdx, _ ->
                                Box(modifier = Modifier.width(outCellWs[outIdx]), contentAlignment = Alignment.Center) {
                                    val bits = row.desiredSlices[outIdx] ?: emptyList()
                                    BitDotsRow(bits = bits, dotSize = 12.dp, spacing = 4.dp, maxDots = level.outputWidth(outIdx))
                                }
                            }
                            level.outputs.forEachIndexed { outIdx, _ ->
                                Box(modifier = Modifier.width(outCellWs[outIdx]), contentAlignment = Alignment.Center) {
                                    val bits = if (outputsConnected) row.actualSlices?.get(outIdx) else null
                                    if (bits != null) {
                                        BitDotsRow(bits = bits, dotSize = 12.dp, spacing = 4.dp, maxDots = level.outputWidth(outIdx))
                                    } else {
                                        // Placeholder gray dots matching width – keeps columns aligned
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            repeat(level.outputWidth(outIdx)) {
                                                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF3A3A52)))
                                            }
                                        }
                                    }
                                }
                            }
                            Box(modifier = Modifier.width(checkW), contentAlignment = Alignment.Center) {
                                // No check until an output is actually driven (nothing connected yet).
                                if (outputsConnected && row.actualSlices != null) {
                                    Text(if (isFailing) "✗" else "✓", fontSize = 13.sp, color = if (isFailing) Color(0xFFFF8A8A) else Color(0xFF22C55E), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
