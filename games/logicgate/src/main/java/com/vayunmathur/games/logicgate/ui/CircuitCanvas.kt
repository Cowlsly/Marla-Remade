package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.data.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object Turing {
    val bg = Color(0xFF2B4D68)
    val headerBg = Color(0xFF2E2B44)
    val headerPink = Color(0xFFE66A7E)
    val leftPanelBg = Color(0xFF1D2A3A)
    val leftPanelCard = Color(0xFF243447)
    val iconBarBg = Color(0xFF1C2C3E)
    val iconBg = Color(0xFF22364D)
    val bottomBg = Color(0xFF2A2A44)
    val inputRed = Color(0xFFC93B3B)
    val inputBorder = Color(0xFFFF9A9A)
    val gateTeal = Color(0xFF0F7A6E)
    val gateStroke = Color(0xFF4BE8C6)
    val gateBlue = Color(0xFF2C6FB5)
    val gateBlueStroke = Color(0xFF7CB6EC)
    val bitGreen = Color(0xFF2ECC71)
    val bitRed = Color(0xFFE74C4C)
    val busOrange = Color(0xFFFFA53D)
    val busBlue = Color(0xFF4FC3FF)
    val wireThin = Color(0xFF3DD68A)
    val wireOrange = Color(0xFFFFA53D)
    val wireBlue = Color(0xFF4EC8FF)
    val wireYellow = Color(0xFFFDE68A)
    val ghostBad = Color(0x66FFFFFF)
    val pinOut = Color(0xFFA7F3D0)
    val orangeLabel = Color(0xFFF0A040)
    val rightTabOn = Color(0xFF3D455C)
    val rightTabOff = Color(0xFF1E2636)
}

// Logic-gate silhouettes (Turing-Complete style): triangle=NOT/buffer, D=AND/NAND,
// bullet=OR/NOR, bullet+double-back=XOR/XNOR, rounded rect=everything else.
// Inverting gates (NAND/NOR/XNOR/NOT) also get the negation bubble at the output.
private enum class GateShape { TRIANGLE, DSHAPE, ORSHAPE, RECT }
private data class GateStyle(val shape: GateShape, val inverting: Boolean, val doubleBack: Boolean)

private fun gateStyleFor(def: ChipDef): GateStyle {
    // All components render as compact rounded rectangles.
    return GateStyle(GateShape.RECT, inverting = false, doubleBack = false)
}

// Size a placed component (px): width fits the label, height grows per pin for spacing. Shared by the canvas and the drag ghost.
internal fun gatePlacedSizePx(def: ChipDef, density: Density, textMeasurer: TextMeasurer): Pair<Float, Float> {
    val maxPins = max(def.inputCount, def.outputCount)
    val measured = try {
        textMeasurer.measure(def.displayName, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)).size.width.toFloat()
    } catch (_: Exception) { def.displayName.length * with(density) { 8.dp.toPx() } }
    val wPx = (measured + with(density) { 16.dp.toPx() }).coerceIn(with(density) { 44.dp.toPx() }, with(density) { 104.dp.toPx() })
    val hDp = if (maxPins <= 1) 30.dp else (20.dp * maxPins + 12.dp)
    return wPx to with(density) { hDp.toPx() }
}

// Builds a gate body path occupying [left, left+w] x [0,h].
private fun buildGateBody(shape: GateShape, left: Float, w: Float, h: Float): Path {
    val p = Path()
    when (shape) {
        GateShape.DSHAPE -> {
            val r = (h / 2f).coerceAtMost(w / 2f)
            p.moveTo(left, 0f); p.lineTo(left + w - r, 0f)
            p.arcTo(Rect(left + w - 2f * r, 0f, left + w, h), -90f, 180f, false)
            p.lineTo(left, h); p.close()
        }
        GateShape.ORSHAPE -> {
            p.moveTo(left, 0f)
            p.quadraticTo(left + w * 0.30f, h * 0.5f, left, h)   // concave back
            p.quadraticTo(left + w * 0.72f, h, left + w, h * 0.5f) // bottom to tip
            p.quadraticTo(left + w * 0.72f, 0f, left, 0f)          // tip to top
            p.close()
        }
        GateShape.TRIANGLE -> {
            p.moveTo(left, 0f); p.lineTo(left + w, h / 2f); p.lineTo(left, h); p.close()
        }
        GateShape.RECT -> {}
    }
    return p
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGate(style: GateStyle, fill: Color, stroke: Color, strokeW: Float) {
    val w = size.width; val h = size.height
    if (style.shape == GateShape.RECT) {
        val cr = androidx.compose.ui.geometry.CornerRadius(with(this) { 8.dp.toPx() }, with(this) { 8.dp.toPx() })
        drawRoundRect(fill, size = size, cornerRadius = cr)
        drawRoundRect(stroke, size = size, cornerRadius = cr, style = Stroke(strokeW))
        return
    }
    val bubbleR = if (style.inverting) h * 0.13f else 0f
    val backGap = if (style.doubleBack) h * 0.16f else 0f
    val left = backGap
    val bodyW = (w - bubbleR * 2f - backGap).coerceAtLeast(h * 0.6f)
    val body = buildGateBody(style.shape, left, bodyW, h)
    drawPath(body, fill)
    drawPath(body, stroke, style = Stroke(strokeW))
    if (style.doubleBack) {
        val back = Path().apply {
            moveTo(0f, 0f)
            quadraticTo(bodyW * 0.30f, h * 0.5f, 0f, h)
        }
        drawPath(back, stroke, style = Stroke(strokeW))
    }
    if (style.inverting) {
        val cx = left + bodyW + bubbleR; val cy = h / 2f
        drawCircle(fill, bubbleR, Offset(cx, cy))
        drawCircle(stroke, bubbleR, Offset(cx, cy), style = Stroke(strokeW))
    }
}

data class GateBox(val chip: PlacedChip, val left: Float, val top: Float, val w: Float, val h: Float, val pinOut: Float = 0f) {
    fun inputPos(i: Int, count: Int): Offset = Offset(left - pinOut, if (count <= 1) top + h / 2f else top + h / (count + 1) * (i + 1))
    fun outputPos(i: Int, count: Int): Offset = Offset(left + w + pinOut, if (count <= 1) top + h / 2f else top + h / (count + 1) * (i + 1))
    fun inputPosLocal(count: Int, pinIdx: Int): Offset = Offset(-pinOut, if (count <= 1) h / 2f else h / (count + 1) * (pinIdx + 1))
    fun outputPosLocal(count: Int, pinIdx: Int): Offset = Offset(w + pinOut, if (count <= 1) h / 2f else h / (count + 1) * (pinIdx + 1))
}

data class TerminalBox(val idx: Int, val center: Offset, val name: String, val isInput: Boolean, val pillW: Float)
data class HitInput(val end: WireEnd, val pos: Offset)

// Large virtual work area so gates can be dragged well beyond the viewport (pan/zoom to reach them).
private const val CANVAS_MARGIN = 4000f

private fun clampGateWithPin(pos: Offset, w: Float, h: Float, pinOut: Float, canvasSize: Size, padding: Dp, density: androidx.compose.ui.unit.Density): Offset {
    if (canvasSize.width <= 0f || canvasSize.height <= 0f) return pos
    return Offset(
        pos.x.coerceIn(-CANVAS_MARGIN, canvasSize.width + CANVAS_MARGIN),
        pos.y.coerceIn(-CANVAS_MARGIN, canvasSize.height + CANVAS_MARGIN)
    )
}

@Composable
fun CircuitCanvas(
    level: LevelDef,
    gates: List<PlacedChip>,
    wires: List<Wire>,
    outputMaps: List<OutputMapping>,
    inputPositions: Map<Int, IoPos>,
    outputPositions: Map<Int, IoPos>,
    wiringFrom: WireEnd?,
    onCreateWire: (from: WireEnd, to: WireEnd) -> Unit,
    onStartWiring: (WireEnd) -> Unit,
    onCancelWiring: () -> Unit,
    onGateMoveFinished: (id: String, x: Float, y: Float) -> Unit,
    onInputTermMoveFinished: (idx: Int, x: Float, y: Float) -> Unit,
    onOutputTermMoveFinished: (idx: Int, x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
    onGateMove: (id: String, x: Float, y: Float) -> Unit = { _, _, _ -> },
    onInputTermMove: (idx: Int, x: Float, y: Float) -> Unit = { _, _, _ -> },
    onOutputTermMove: (idx: Int, x: Float, y: Float) -> Unit = { _, _, _ -> },
    onGateDelete: (String) -> Unit,
    onWireDelete: (String) -> Unit,
    onOutputMapDelete: (Int) -> Unit,
    dragGhostLineEnd: Offset?,
    onGhostLine: (Offset?) -> Unit,
    inputValues: Map<Int, Int> = emptyMap(),
    desiredOutputValues: Map<Int, Int> = emptyMap(),
    outputValues: Map<Int, Int> = emptyMap(),
    isCompact: Boolean = false,
    onToggleInput: (Int) -> Unit = {},
    inputOnMap: Map<Int, Boolean> = emptyMap(),
    inputBitSlices: Map<Int, List<Boolean>> = emptyMap(),
    outputBitSlicesActual: Map<Int, List<Boolean>> = emptyMap(),
    // Reports the current pan/zoom so callers can map screen drops to content coords: content = (screen - offset) / scale
    onViewportChange: (scale: Float, offset: Offset) -> Unit = { _, _ -> },
    selectedGateId: String? = null,
    onSelectGate: (String?) -> Unit = {}
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    var canvasSizePx by remember { mutableStateOf(Size.Zero) }
    // Pinch-to-zoom transform (content space -> screen: p*scale + offset, origin top-left)
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Explicit start anchor for the wiring ghost — WireEnd(gate, pin) is ambiguous between
    // an input and output pin, so we remember the exact pin the drag started from.
    var wiringAnchor by remember { mutableStateOf<Offset?>(null) }
    // Drag-a-gate-to-the-bottom-to-delete (Alchemist style).
    var gateDragActive by remember { mutableStateOf(false) }
    var gateDragArmed by remember { mutableStateOf(false) }
    val deleteBandPx = with(density) { 72.dp.toPx() }
    fun inDeleteZone(contentCenterY: Float): Boolean {
        val chH = canvasSizePx.height
        if (chH <= 0f) return false
        val screenRelY = contentCenterY * scale + offset.y
        return screenRelY > chH - deleteBandPx
    }

    val pinHitR = with(density) { if (isCompact) 36.dp.toPx() else 28.dp.toPx() }
    val termWireDotR = with(density) { if (isCompact) 32.dp.toPx() else 26.dp.toPx() }
    val wireHitThreshold = with(density) { if (isCompact) 40.dp.toPx() else 34.dp.toPx() }
    val pinOutsideDp: Dp = 14.dp
    val pinOutsidePx = with(density) { pinOutsideDp.toPx() }
    val termMinWpx = with(density) { 78.dp.toPx() }
    val termMaxWpx = with(density) { 124.dp.toPx() }
    val termHpx = with(density) { if (isCompact) 46.dp.toPx() else 42.dp.toPx() } // must match TuringBigTerminal.visualH

    fun gateSizeFor(def: ChipDef): Pair<Float, Float> = gatePlacedSizePx(def, density, textMeasurer)

    val gateBoxes: List<GateBox> = remember(gates, isCompact, pinOutsidePx) {
        gates.map { g ->
            val def = ChipLibrary.get(g.chipId)
            val (w, h) = gateSizeFor(def)
            GateBox(g, g.x, g.y, w, h, pinOut = pinOutsidePx)
        }
    }
    val chipDefs = remember(gates) { gates.associate { it.instanceId to ChipLibrary.get(it.chipId) } }

    fun pillW(display: String): Float {
        val measured = try {
            val layout = textMeasurer.measure(display, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold))
            layout.size.width.toFloat()
        } catch (_: Exception) { display.length * 12f }
        return (with(density) { 28.dp.toPx() } + measured).coerceIn(termMinWpx, termMaxWpx)
    }
    fun visualPillW(raw: Float): Float = raw.coerceIn(termMinWpx, termMaxWpx)

    val inputLayouts: List<TerminalBox> = remember(level.inputs, inputPositions, canvasSizePx) {
        level.inputs.mapIndexed { i, _ ->
            val label = displayInputLabel(level, i)
            val pw = pillW(label)
            val vw = visualPillW(pw)
            val default = defaultInputPos(i, level.inputs.size, canvasSizePx, vw, termHpx)
            val p = inputPositions[i]
            val center = if (p != null) Offset(p.x, p.y) else default
            TerminalBox(i, center, label, true, pw)
        }
    }
    val outputLayouts: List<TerminalBox> = remember(level.outputs, outputPositions, canvasSizePx) {
        level.outputs.mapIndexed { i, _ ->
            val label = displayOutputLabel(level, i)
            val pw = pillW(label)
            val vw = visualPillW(pw)
            val default = defaultOutputPos(i, level.outputs.size, canvasSizePx, vw, termHpx)
            val p = outputPositions[i]
            val center = if (p != null) Offset(p.x, p.y) else default
            TerminalBox(i, center, label, false, pw)
        }
    }

    fun dotForInput(t: TerminalBox): Offset = Offset(t.center.x + visualPillW(t.pillW) / 2f + pinOutsidePx + 8f, t.center.y)
    fun dotForOutput(t: TerminalBox): Offset = Offset(t.center.x - visualPillW(t.pillW) / 2f - pinOutsidePx - 8f, t.center.y)

    fun resolveSourceWith(boxes: Map<String, GateBox>, inLayouts: List<TerminalBox>, end: WireEnd): Offset? {
        if (end.instanceId.startsWith("__IN_")) {
            val idx = end.instanceId.removePrefix("__IN_").toIntOrNull() ?: return null
            return inLayouts.find { it.idx == idx }?.let { dotForInput(it) }
        }
        val gr = boxes[end.instanceId] ?: return null
        val def = chipDefs[end.instanceId] ?: return null
        return gr.outputPos(end.pinIndex.coerceIn(0, max(0, def.outputCount - 1)), def.outputCount)
    }
    fun resolveSinkWith(boxes: Map<String, GateBox>, outLayouts: List<TerminalBox>, end: WireEnd): Offset? {
        if (end.instanceId.startsWith("__OUT_")) {
            val idx = end.instanceId.removePrefix("__OUT_").toIntOrNull() ?: return null
            return outLayouts.find { it.idx == idx }?.let { dotForOutput(it) }
        }
        val gr = boxes[end.instanceId] ?: return null
        val def = chipDefs[end.instanceId] ?: return null
        return gr.inputPos(end.pinIndex.coerceIn(0, max(0, def.inputCount - 1)), def.inputCount)
    }
    fun resolveSource(end: WireEnd): Offset? = resolveSourceWith(gateBoxes.associateBy { it.chip.instanceId }, inputLayouts, end)
    fun resolveSink(end: WireEnd): Offset? = resolveSinkWith(gateBoxes.associateBy { it.chip.instanceId }, outputLayouts, end)

    // Find the pin endpoint nearest to an absolute canvas position, excluding the drag's origin instance.
    // Direction (source vs sink) is resolved later by the ViewModel's createWire auto-orient.
    fun resolveTargetAt(pos: Offset, excludeInstance: String?): WireEnd? {
        var best: WireEnd? = null
        var bestD = Float.MAX_VALUE
        for (box in gateBoxes) {
            if (box.chip.instanceId == excludeInstance) continue
            val def = chipDefs[box.chip.instanceId] ?: continue
            for (j in 0 until def.inputCount) {
                val d = (pos - box.inputPos(j, def.inputCount)).getDistance()
                if (d < pinHitR && d < bestD) { bestD = d; best = WireEnd(box.chip.instanceId, j) }
            }
            for (j in 0 until def.outputCount) {
                val d = (pos - box.outputPos(j, def.outputCount)).getDistance()
                if (d < pinHitR && d < bestD) { bestD = d; best = WireEnd(box.chip.instanceId, j) }
            }
        }
        for (t in inputLayouts) {
            if ("__IN_${t.idx}" == excludeInstance) continue
            val d = (pos - dotForInput(t)).getDistance()
            if (d < termWireDotR && d < bestD) { bestD = d; best = WireEnd("__IN_${t.idx}", 0) }
        }
        for (t in outputLayouts) {
            if ("__OUT_${t.idx}" == excludeInstance) continue
            val d = (pos - dotForOutput(t)).getDistance()
            if (d < termWireDotR && d < bestD) { bestD = d; best = WireEnd("__OUT_${t.idx}", 0) }
        }
        return best
    }

    val gateBoxesRef = remember { mutableStateOf(gateBoxes) }
    val inputLayoutsRef = remember { mutableStateOf(inputLayouts) }
    val outputLayoutsRef = remember { mutableStateOf(outputLayouts) }
    val wiresRef = remember { mutableStateOf(wires) }
    val outputMapsRef = remember { mutableStateOf(outputMaps) }
    val wiringFromRef = remember { mutableStateOf(wiringFrom) }

    LaunchedEffect(gateBoxes, inputLayouts, outputLayouts, wires, outputMaps, canvasSizePx, wiringFrom) {
        gateBoxesRef.value = gateBoxes
        inputLayoutsRef.value = inputLayouts
        outputLayoutsRef.value = outputLayouts
        wiresRef.value = wires
        outputMapsRef.value = outputMaps
        wiringFromRef.value = wiringFrom
    }
    LaunchedEffect(wiringFrom) { if (wiringFrom == null) wiringAnchor = null }
    val onViewportChangeState by rememberUpdatedState(onViewportChange)
    LaunchedEffect(scale, offset) { onViewportChangeState(scale, offset) }

    val onCreateWireState by rememberUpdatedState(onCreateWire)
    val onStartWiringState by rememberUpdatedState(onStartWiring)
    val onCancelWiringState by rememberUpdatedState(onCancelWiring)
    val onGateMoveFinishedState by rememberUpdatedState(onGateMoveFinished)
    val onInputTermMoveFinishedState by rememberUpdatedState(onInputTermMoveFinished)
    val onOutputTermMoveFinishedState by rememberUpdatedState(onOutputTermMoveFinished)
    val onGateMoveState by rememberUpdatedState(onGateMove)
    val onInputTermMoveState by rememberUpdatedState(onInputTermMove)
    val onOutputTermMoveState by rememberUpdatedState(onOutputTermMove)
    val onGateDeleteState by rememberUpdatedState(onGateDelete)
    val onWireDeleteState by rememberUpdatedState(onWireDelete)
    val onOutputMapDeleteState by rememberUpdatedState(onOutputMapDelete)
    val onGhostLineState by rememberUpdatedState(onGhostLine)
    val onToggleInputState by rememberUpdatedState(onToggleInput)
    val onSelectGateState by rememberUpdatedState(onSelectGate)

    Box(
        modifier = modifier.fillMaxSize().background(Turing.bg).clipToBounds()
            // Two-finger pinch/pan, intercepted in the Initial pass so single-finger
            // gestures still reach the gates/terminals/wires below.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val p0 = pressed[0]; val p1 = pressed[1]
                            // Only transform once both pointers were already down last frame,
                            // otherwise the just-landed finger produces a bogus first delta.
                            if (p0.previousPressed && p1.previousPressed) {
                                val curDist = (p0.position - p1.position).getDistance()
                                val prevDist = (p0.previousPosition - p1.previousPosition).getDistance()
                                val zoom = if (prevDist > 0.01f) curDist / prevDist else 1f
                                val centroid = (p0.position + p1.position) / 2f
                                val prevCentroid = (p0.previousPosition + p1.previousPosition) / 2f
                                val pan = centroid - prevCentroid
                                var newOffset = offset + pan
                                val contentUnder = (centroid - newOffset) / scale
                                val newScale = (scale * zoom).coerceIn(0.15f, 3.5f)
                                newOffset = centroid - contentUnder * newScale
                                scale = newScale
                                offset = newOffset
                            }
                            pressed.forEach { it.consume() }
                        }
                    }
                }
            }
    ) {
      // Infinite grid drawn in screen space so it fills the whole viewport at any pan/zoom.
      Canvas(modifier = Modifier.fillMaxSize()) {
          val step = (if (isCompact) 72f else 84f) * scale
          if (step >= 6f) {
              val w = size.width; val h = size.height
              val startX = offset.x.mod(step); val startY = offset.y.mod(step)
              val lineCol = Color.White.copy(alpha = 0.08f)
              val sw = if (isCompact) 1.5f else 1.2f
              var x = startX
              while (x <= w) { drawLine(lineCol, Offset(x, 0f), Offset(x, h), strokeWidth = sw); x += step }
              var y = startY
              while (y <= h) { drawLine(lineCol, Offset(0f, y), Offset(w, y), strokeWidth = sw); y += step }
              val dotCol = Color.White.copy(alpha = if (isCompact) 0.12f else 0.07f)
              val dotR = (if (isCompact) 1.6f else 1.1f) * scale.coerceIn(0.5f, 1.5f)
              var gx = startX
              while (gx <= w) { var gy = startY; while (gy <= h) { drawCircle(dotCol, dotR, Offset(gx, gy)); gy += step }; gx += step }
          }
      }
      Box(
        modifier = Modifier.fillMaxSize()
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .onSizeChanged { canvasSizePx = Size(it.width.toFloat(), it.height.toFloat()) }
      ) {
        Canvas(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            fun resolveSrcLive(end: WireEnd): Offset? = resolveSourceWith(gateBoxesRef.value.associateBy { it.chip.instanceId }, inputLayoutsRef.value, end)
            fun resolveSnkLive(end: WireEnd): Offset? = resolveSinkWith(gateBoxesRef.value.associateBy { it.chip.instanceId }, outputLayoutsRef.value, end)
            fun closestWireLive(pos: Offset): Wire? {
                var best: Wire? = null
                var bestD = wireHitThreshold
                for (w in wiresRef.value) {
                    val a = resolveSrcLive(w.from) ?: continue
                    val b = resolveSnkLive(w.to) ?: continue
                    val d = distPointToOrth(pos, a, b)
                    if (d < bestD) { bestD = d; best = w }
                }
                return best
            }
            fun closestOMLive(pos: Offset): OutputMapping? {
                var best: OutputMapping? = null
                var bestD = wireHitThreshold
                for (om in outputMapsRef.value) {
                    val a = resolveSrcLive(om.from) ?: continue
                    val b = outputLayoutsRef.value.find { it.idx == om.outputIndex }?.let { t ->
                        Offset(t.center.x - t.pillW.coerceIn(termMinWpx, termMaxWpx) / 2f - pinOutsidePx - 8f, t.center.y)
                    } ?: continue
                    val d = distPointToOrth(pos, a, b)
                    if (d < bestD) { bestD = d; best = om }
                }
                return best
            }
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    val cw = closestWireLive(downPos)
                    if (cw != null) {
                        onWireDeleteState(cw.id)
                        while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                        continue
                    }
                    val om = closestOMLive(downPos)
                    if (om != null) {
                        onOutputMapDeleteState(om.outputIndex)
                        while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                        continue
                    }
                    if (wiringFromRef.value != null) {
                        var hitResult: HitInput? = null
                        for (box in gateBoxesRef.value) {
                            val def = try { ChipLibrary.get(box.chip.chipId) } catch (_: Exception) { null } ?: continue
                            for (j in 0 until def.inputCount) {
                                val pp = box.inputPos(j, def.inputCount)
                                if ((downPos - pp).getDistance() < pinHitR) { hitResult = HitInput(WireEnd(box.chip.instanceId, j), pp); break }
                            }
                            if (hitResult != null) break
                        }
                        if (hitResult == null) {
                            for (t in outputLayoutsRef.value) {
                                val dp = Offset(t.center.x - t.pillW.coerceIn(termMinWpx, termMaxWpx) / 2f - pinOutsidePx - 8f, t.center.y)
                                if ((downPos - dp).getDistance() < termWireDotR) { hitResult = HitInput(WireEnd("__OUT_${t.idx}", 0), dp); break }
                            }
                        }
                        if (hitResult == null) {
                            for (box in gateBoxesRef.value) {
                                val def = try { ChipLibrary.get(box.chip.chipId) } catch (_: Exception) { null } ?: continue
                                for (j in 0 until def.outputCount) {
                                    val pp = box.outputPos(j, def.outputCount)
                                    if ((downPos - pp).getDistance() < pinHitR) { hitResult = HitInput(WireEnd(box.chip.instanceId, j), pp); break }
                                }
                                if (hitResult != null) break
                            }
                        }
                        if (hitResult == null) {
                            for (t in inputLayoutsRef.value) {
                                val dp = Offset(t.center.x + t.pillW.coerceIn(termMinWpx, termMaxWpx) / 2f + pinOutsidePx + 8f, t.center.y)
                                if ((downPos - dp).getDistance() < termWireDotR) { hitResult = HitInput(WireEnd("__IN_${t.idx}", 0), dp); break }
                            }
                        }
                        val hit = hitResult
                        if (hit != null && wiringFromRef.value!!.instanceId != hit.end.instanceId) onCreateWireState(wiringFromRef.value!!, hit.end) else onCancelWiringState()
                        onGhostLineState(null)
                        while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                        continue
                    }
                    // Empty-space tap: clear the current selection.
                    onSelectGateState(null)
                }
            }
        }) {
            for (w in wires) {
                val a = resolveSource(w.from) ?: continue
                val b = resolveSink(w.to) ?: continue
                val (col, thick) = wireStyleForWidth(w.busWidth, isCompact)
                drawOrthWire(a, b, col, thick, false, false)
            }
            for (om in outputMaps) {
                val a = resolveSource(om.from) ?: continue
                val b = outputLayouts.find { it.idx == om.outputIndex }?.let { dotForOutput(it) } ?: continue
                val srcWidth = try { val g = gateBoxes.find { it.chip.instanceId == om.from.instanceId }; g?.let { ChipLibrary.get(it.chip.chipId).outputPinWidth(om.from.pinIndex) } ?: 1 } catch (_: Exception) { 1 }
                val (_, thick) = wireStyleForWidth(srcWidth, isCompact)
                drawOrthWire(a, b, Turing.wireBlue, thick, false, false)
            }
            val gStart = wiringAnchor ?: wiringFrom?.let { resolveSource(it) ?: resolveSink(it) }
            val gEnd = dragGhostLineEnd
            if (gStart != null && gEnd != null) {
                var found = false
                for (box in gateBoxes) {
                    val def = try { ChipLibrary.get(box.chip.chipId) } catch (_: Exception) { null } ?: continue
                    for (j in 0 until def.inputCount) if ((gEnd - box.inputPos(j, def.inputCount)).getDistance() < pinHitR) { found = true; break }
                    if (!found) for (j in 0 until def.outputCount) if ((gEnd - box.outputPos(j, def.outputCount)).getDistance() < pinHitR) { found = true; break }
                    if (found) break
                }
                if (!found) {
                    for (t in inputLayouts) if ((gEnd - dotForInput(t)).getDistance() < termWireDotR + 6f) { found = true; break }
                    if (!found) for (t in outputLayouts) if ((gEnd - dotForOutput(t)).getDistance() < termWireDotR + 6f) { found = true; break }
                }
                drawOrthWire(gStart, gEnd, if (found) Turing.wireYellow else Turing.ghostBad, 3f, true, true)
            } else if (wiringFrom != null) {
                (wiringAnchor ?: resolveSource(wiringFrom) ?: resolveSink(wiringFrom))?.let { drawCircle(Color.Yellow.copy(alpha = 0.28f), if (isCompact) 28f else 22f, it) }
            }
        }

        inputLayouts.forEach { t ->
            TuringBigTerminal(
                box = t, isInput = true,
                inputWidth = try { level.inputWidth(t.idx) } catch (_: Exception) { 1 },
                canvasSize = canvasSizePx, wiringFrom = wiringFrom, ghostEnd = dragGhostLineEnd,
                onMoveFinished = { idx, x, y -> onInputTermMoveFinishedState(idx, x, y) },
                onMove = { idx, x, y -> onInputTermMoveState(idx, x, y) },
                onStartWiring = { end -> onStartWiringState(end); wiringAnchor = dotForInput(t); onGhostLineState(dotForInput(t)) },
                onCompleteWiring = { from, to -> onCreateWireState(from, to); onGhostLineState(null) },
                onGhost = { off -> onGhostLineState(off) },
                onCancel = { onCancelWiringState(); onGhostLineState(null) },
                resolveTargetAt = { pos, excl -> resolveTargetAt(pos, excl) },
                density = density, pinHitR = pinHitR, termWireDotR = termWireDotR, isCompact = isCompact,
                onToggleInput = { idx -> onToggleInputState(idx) },
                pinOutsideDp = pinOutsideDp, termMinWpx = termMinWpx, termMaxWpx = termMaxWpx, pinOutsidePx = pinOutsidePx,
                isOn = inputOnMap[t.idx] ?: (inputBitSlices[t.idx]?.firstOrNull() == true)
            )
        }
        outputLayouts.forEach { t ->
            TuringBigTerminal(
                box = t, isInput = false,
                inputWidth = try { level.outputWidth(t.idx) } catch (_: Exception) { 1 },
                canvasSize = canvasSizePx, wiringFrom = wiringFrom, ghostEnd = dragGhostLineEnd,
                onMoveFinished = { idx, x, y -> onOutputTermMoveFinishedState(idx, x, y) },
                onMove = { idx, x, y -> onOutputTermMoveState(idx, x, y) },
                onStartWiring = { end -> onStartWiringState(end); wiringAnchor = dotForOutput(t); onGhostLineState(dotForOutput(t)) },
                onCompleteWiring = { from, to -> onCreateWireState(from, to); onGhostLineState(null) },
                onGhost = { off -> onGhostLineState(off) },
                onCancel = { onCancelWiringState(); onGhostLineState(null) },
                resolveTargetAt = { pos, excl -> resolveTargetAt(pos, excl) },
                density = density, pinHitR = pinHitR, termWireDotR = termWireDotR, isCompact = isCompact,
                pinOutsideDp = pinOutsideDp, termMinWpx = termMinWpx, termMaxWpx = termMaxWpx, pinOutsidePx = pinOutsidePx,
                isOn = outputValues[t.idx]?.let { it != 0 } ?: (desiredOutputValues[t.idx]?.let { it != 0 } ?: outputBitSlicesActual[t.idx]?.firstOrNull() == true)
            )
        }

        gateBoxes.forEach { gBox ->
            TuringGate(
                gateBox = gBox, chipDef = chipDefs[gBox.chip.instanceId], canvasSize = canvasSizePx,
                wiringFrom = wiringFrom, wiringAnchor = wiringAnchor, ghostEnd = dragGhostLineEnd,
                isSelected = selectedGateId == gBox.chip.instanceId,
                onSelect = { id -> onSelectGateState(id) },
                onMoveFinished = { id, x, y -> onGateMoveFinishedState(id, x, y) },
                onMove = { id, x, y -> onGateMoveState(id, x, y) },
                onDelete = { id -> onGateDeleteState(id) },
                onStartWiring = { end -> onStartWiringState(end) },
                onCompleteWiring = { from, to -> onCreateWireState(from, to) },
                onGhost = { off -> onGhostLineState(off) },
                onCancel = { onCancelWiringState(); onGhostLineState(null) },
                resolveTargetAt = { pos, excl -> resolveTargetAt(pos, excl) },
                onAnchor = { pos -> wiringAnchor = pos },
                inDeleteZone = { y -> inDeleteZone(y) },
                onDragZone = { active, armed -> gateDragActive = active; gateDragArmed = armed },
                density = density, pinHitR = pinHitR, isCompact = isCompact
            )
        }
      }
      // Delete zone: drag a component here (toward the inventory) to remove it.
      if (gateDragActive) {
          Box(
              modifier = Modifier
                  .align(Alignment.BottomCenter)
                  .fillMaxWidth()
                  .height(with(density) { deleteBandPx.toDp() })
                  .background(if (gateDragArmed) Color(0xE6B91C1C) else Color(0x66B91C1C)),
              contentAlignment = Alignment.Center
          ) {
              androidx.compose.material3.Text(
                  text = if (gateDragArmed) "Release to delete" else "Drag here to delete",
                  color = Color.White,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.Bold
              )
          }
      }
    }
}

@Composable
private fun TuringGate(
    gateBox: GateBox,
    chipDef: ChipDef?,
    canvasSize: Size,
    wiringFrom: WireEnd?,
    wiringAnchor: Offset?,
    ghostEnd: Offset?,
    isSelected: Boolean,
    onSelect: (String) -> Unit,
    onMoveFinished: (String, Float, Float) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onDelete: (String) -> Unit,
    onStartWiring: (WireEnd) -> Unit,
    onCompleteWiring: (WireEnd, WireEnd) -> Unit,
    onGhost: (Offset?) -> Unit,
    onCancel: () -> Unit,
    resolveTargetAt: (Offset, String?) -> WireEnd?,
    onAnchor: (Offset) -> Unit,
    inDeleteZone: (Float) -> Boolean,
    onDragZone: (Boolean, Boolean) -> Unit,
    density: androidx.compose.ui.unit.Density,
    pinHitR: Float,
    isCompact: Boolean = false
) {
    val def = chipDef ?: return
    val id = gateBox.chip.instanceId
    var localPos by remember(id) { mutableStateOf(Offset(gateBox.left, gateBox.top)) }
    val localPosState by rememberUpdatedState(localPos)
    var dragging by remember(id) { mutableStateOf(false) }
    LaunchedEffect(gateBox.left, gateBox.top) { if (!dragging) localPos = Offset(gateBox.left, gateBox.top) }
    val w = gateBox.w; val h = gateBox.h
    val wDp = with(density) { w.toDp() }
    val hDp = with(density) { h.toDp() }
    val gateStyle = remember(def.id) { gateStyleFor(def) }
    val isTriangle = gateStyle.shape == GateShape.TRIANGLE

    Box(modifier = Modifier.graphicsLayer { translationX = localPos.x; translationY = localPos.y }) {
        Box(
            modifier = Modifier
                .size(wDp, hDp)
                .drawBehind {
                    val strokeCol = when { isSelected -> Color.Yellow; dragging -> Color.White; else -> Turing.gateBlueStroke }
                    val strokeW = (if (isSelected || dragging) 2.dp else 1.4.dp).toPx()
                    drawGate(gateStyle, Turing.gateBlue, strokeCol, strokeW)
                }
                .pointerInput(id) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var dragTotal = Offset.Zero
                            dragging = true
                            val downTime = System.currentTimeMillis()
                            var longHandled = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (ch.changedToUpIgnoreConsumed()) {
                                    val armed = dragTotal.getDistance() > 4f && inDeleteZone(localPos.y + h / 2f)
                                    onDragZone(false, false)
                                    if (!longHandled) {
                                        when {
                                            armed -> onDelete(id) // dropped in the delete band -> remove
                                            dragTotal.getDistance() > 4f -> onMoveFinished(id, localPos.x, localPos.y)
                                            else -> onSelect(id) // tap selects (yellow outline)
                                        }
                                    }
                                    break
                                }
                                val delta = ch.position - ch.previousPosition
                                dragTotal += delta
                                val elapsed = System.currentTimeMillis() - downTime
                                if (!longHandled && elapsed > 520 && dragTotal.getDistance() < 12f) { longHandled = true; onDragZone(false, false); onDelete(id); break }
                                if (dragTotal.getDistance() > 4f) {
                                    ch.consume()
                                    localPos = clampGateWithPin(localPos + delta, w, h, gateBox.pinOut, canvasSize, 12.dp, density)
                                    onMove(id, localPos.x, localPos.y) // keep connected wires attached live
                                    onDragZone(true, inDeleteZone(localPos.y + h / 2f))
                                }
                            }
                            dragging = false
                        }
                    }
                },
            contentAlignment = if (isTriangle) Alignment.CenterStart else Alignment.Center
        ) {
            // Triangle points right, so keep the label in the wide left portion.
            androidx.compose.material3.Text(text = def.displayName, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(start = if (isTriangle) 6.dp else 4.dp, end = if (isTriangle) 14.dp else 4.dp))
        }
        for (j in 0 until def.inputCount) {
            val lp = gateBox.inputPosLocal(def.inputCount, j)
            val isHover = ghostEnd?.let { (localPos + lp - it).getDistance() < pinHitR } ?: false
            // Source pin (the one being dragged from) — identified precisely by the wiring anchor
            // since WireEnd(id, pin) can't tell an input from an output of the same index.
            val isSrc = wiringFrom?.instanceId == id && wiringAnchor != null && (localPos + lp - wiringAnchor).getDistance() < 12f
            val hitSize = 28.dp; val dotSize = 12.dp
            val halfHit = with(density) { hitSize.toPx() } / 2f
            val onStartState by rememberUpdatedState(onStartWiring)
            val onCompleteState by rememberUpdatedState(onCompleteWiring)
            val onCancelState by rememberUpdatedState(onCancel)
            val onGhostState by rememberUpdatedState(onGhost)
            val wiringFromState by rememberUpdatedState(wiringFrom)
            val resolveTargetState by rememberUpdatedState(resolveTargetAt)
            val onAnchorState by rememberUpdatedState(onAnchor)
            Box(
                modifier = Modifier.offset { IntOffset((lp.x - halfHit).toInt(), (lp.y - halfHit).toInt()) }.size(hitSize).pointerInput(id, j) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if ((down.position - Offset(halfHit, halfHit)).getDistance() > pinHitR) {
                                while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                                continue
                            }
                            if (wiringFromState != null && wiringFromState!!.instanceId != id) {
                                onCompleteState(wiringFromState!!, WireEnd(id, j)); onGhostState(null)
                                while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                                continue
                            }
                            val pinAbs = localPosState + lp
                            onStartState(WireEnd(id, j)); onAnchorState(pinAbs); onGhostState(pinAbs)
                            var cur = pinAbs
                            while (true) {
                                val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (ch.changedToUpIgnoreConsumed()) {
                                    val target = resolveTargetState(cur, id)
                                    if (target != null) onCompleteState(WireEnd(id, j), target) else onCancelState()
                                    onGhostState(null); break
                                }
                                cur = pinAbs + (ch.position - down.position); onGhostState(cur); ch.consume()
                            }
                        }
                    }
                },
                contentAlignment = Alignment.Center
            ) { Box(modifier = Modifier.size(dotSize).clip(CircleShape).background(if (isSrc || isHover) Color.Yellow else Color.White).border(1.dp, Color.Black.copy(alpha = 0.35f), CircleShape)) }
        }
        for (j in 0 until def.outputCount) {
            val lp = gateBox.outputPosLocal(def.outputCount, j)
            val isSrc = wiringFrom?.instanceId == id && wiringAnchor != null && (localPos + lp - wiringAnchor).getDistance() < 12f
            val hitSize = 28.dp; val dotSize = 12.dp
            val halfHit = with(density) { hitSize.toPx() } / 2f
            val onStartState by rememberUpdatedState(onStartWiring)
            val onCompleteState by rememberUpdatedState(onCompleteWiring)
            val onCancelState by rememberUpdatedState(onCancel)
            val onGhostState by rememberUpdatedState(onGhost)
            val wiringFromState by rememberUpdatedState(wiringFrom)
            val resolveTargetState by rememberUpdatedState(resolveTargetAt)
            val onAnchorState by rememberUpdatedState(onAnchor)
            Box(
                modifier = Modifier.offset { IntOffset((lp.x - halfHit).toInt(), (lp.y - halfHit).toInt()) }.size(hitSize).pointerInput(id, j) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if ((down.position - Offset(halfHit, halfHit)).getDistance() > pinHitR) {
                                while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                                continue
                            }
                            if (wiringFromState != null && wiringFromState!!.instanceId != id) {
                                onCompleteState(wiringFromState!!, WireEnd(id, j)); onGhostState(null)
                                while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                                continue
                            }
                            val pinAbs = localPosState + lp
                            onStartState(WireEnd(id, j)); onAnchorState(pinAbs); onGhostState(pinAbs)
                            var cur = pinAbs
                            while (true) {
                                val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (ch.changedToUpIgnoreConsumed()) {
                                    val target = resolveTargetState(cur, id)
                                    if (target != null) onCompleteState(WireEnd(id, j), target) else onCancelState()
                                    onGhostState(null); break
                                }
                                cur = pinAbs + (ch.position - down.position); onGhostState(cur); ch.consume()
                            }
                        }
                    }
                },
                contentAlignment = Alignment.Center
            ) {
                if (isSrc) Box(modifier = Modifier.size(hitSize).clip(CircleShape).background(Color.Yellow.copy(alpha = 0.18f)))
                Box(modifier = Modifier.size(dotSize).clip(CircleShape).background(if (isSrc) Color.Yellow else Turing.pinOut).border(1.dp, Color.Black.copy(alpha = 0.35f), CircleShape))
            }
        }
    }
}

@Composable
private fun TuringBigTerminal(
    box: TerminalBox,
    isInput: Boolean,
    inputWidth: Int,
    canvasSize: Size,
    wiringFrom: WireEnd?,
    ghostEnd: Offset?,
    onMoveFinished: (Int, Float, Float) -> Unit,
    onMove: (Int, Float, Float) -> Unit,
    onStartWiring: (WireEnd) -> Unit,
    onCompleteWiring: (WireEnd, WireEnd) -> Unit,
    onGhost: (Offset?) -> Unit,
    onCancel: () -> Unit,
    resolveTargetAt: (Offset, String?) -> WireEnd?,
    density: androidx.compose.ui.unit.Density,
    pinHitR: Float,
    termWireDotR: Float,
    isCompact: Boolean = false,
    onToggleInput: (Int) -> Unit = {},
    pinOutsideDp: Dp,
    termMinWpx: Float,
    termMaxWpx: Float,
    pinOutsidePx: Float,
    isOn: Boolean = false
) {
    var center by remember(box.idx, box.center) { mutableStateOf(box.center) }
    val centerState by rememberUpdatedState(center)
    var dragging by remember(box.idx) { mutableStateOf(false) }
    LaunchedEffect(box.center) { if (!dragging) center = box.center }
    val visualW = box.pillW.coerceIn(termMinWpx, termMaxWpx)
    val visualH = with(density) { if (isCompact) 46.dp.toPx() else 42.dp.toPx() }
    val halfW = visualW / 2f; val halfH = visualH / 2f
    val isWiringSrc = wiringFrom?.instanceId == "__${if (isInput) "IN" else "OUT"}_${box.idx}"
    val label = box.name
    val bgColor = if (isOn) Turing.bitGreen else Turing.inputRed
    val toggleState by rememberUpdatedState(onToggleInput)
    val moveFinishedState by rememberUpdatedState(onMoveFinished)
    val moveState by rememberUpdatedState(onMove)
    val startWiringState by rememberUpdatedState(onStartWiring)
    val completeWiringState by rememberUpdatedState(onCompleteWiring)
    val ghostState by rememberUpdatedState(onGhost)
    val cancelState by rememberUpdatedState(onCancel)
    val wiringFromState by rememberUpdatedState(wiringFrom)
    val resolveTargetState by rememberUpdatedState(resolveTargetAt)
    val selfEndId = "__${if (isInput) "IN" else "OUT"}_${box.idx}"
    Box(modifier = Modifier.graphicsLayer { translationX = center.x - halfW; translationY = center.y - halfH }) {
        Box(
            modifier = Modifier
                .size(with(density) { visualW.toDp() }, with(density) { visualH.toDp() })
                .clip(RoundedCornerShape(16.dp))
                .background(bgColor)
                .border(1.3.dp, Turing.inputBorder, RoundedCornerShape(16.dp))
                .pointerInput(box.idx) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val pinLocalX = if (isInput) visualW + pinOutsidePx else -pinOutsidePx
                            if ((down.position - Offset(pinLocalX, halfH)).getDistance() < termWireDotR + 10f) {
                                while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                                continue
                            }
                            var dragTotal = Offset.Zero; dragging = true; val downTime = System.currentTimeMillis()
                            while (true) {
                                val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (ch.changedToUpIgnoreConsumed()) {
                                    val elapsed = System.currentTimeMillis() - downTime
                                    if (dragTotal.getDistance() < 8f && elapsed < 300L && isInput) toggleState(box.idx)
                                    else if (dragTotal.getDistance() > 4f) { moveFinishedState(box.idx, center.x, center.y) }
                                    break
                                }
                                val delta = ch.position - ch.previousPosition
                                dragTotal += delta
                                if (dragTotal.getDistance() > 8f) {
                                    ch.consume()
                                    center = clampTerm(center + delta, visualW, canvasSize, pinOutsidePx, density)
                                    moveState(box.idx, center.x, center.y) // keep connected wires attached live
                                }
                            }
                            dragging = false
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) { androidx.compose.material3.Text(text = label, fontSize = 12.sp, color = Color.White, maxLines = 1, fontWeight = FontWeight.Bold, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp)) }
        val pinHitSize = 36.dp; val pinDotSize = 14.dp
        val pinMod = if (isInput) Modifier.offset { IntOffset((visualW + pinOutsidePx - with(density) { pinHitSize.toPx() } / 2f + 8f).toInt(), (halfH - with(density) { pinHitSize.toPx() } / 2f).toInt()) }
        else Modifier.offset { IntOffset((-pinOutsidePx - with(density) { pinHitSize.toPx() } / 2f - 8f).toInt(), (halfH - with(density) { pinHitSize.toPx() } / 2f).toInt()) }
        Box(modifier = Modifier.then(pinMod).size(pinHitSize).clip(CircleShape).pointerInput(box.idx) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (wiringFromState != null && wiringFromState!!.instanceId != selfEndId) {
                        completeWiringState(wiringFromState!!, WireEnd(selfEndId, 0)); ghostState(null)
                        while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                        continue
                    }
                    val dotAbs = Offset(centerState.x + if (isInput) halfW + pinOutsidePx + 8f else -halfW - pinOutsidePx - 8f, centerState.y)
                    startWiringState(WireEnd(selfEndId, 0)); ghostState(dotAbs)
                    var cur = dotAbs
                    while (true) {
                        val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (ch.changedToUpIgnoreConsumed()) {
                            val target = resolveTargetState(cur, selfEndId)
                            if (target != null) completeWiringState(WireEnd(selfEndId, 0), target) else cancelState()
                            ghostState(null); break
                        }
                        cur = dotAbs + (ch.position - down.position); ghostState(cur); ch.consume()
                    }
                }
            }
        }, contentAlignment = Alignment.Center) { Box(modifier = Modifier.size(pinDotSize).clip(CircleShape).background(if (isWiringSrc) Color.Yellow else if (isInput) Color(0xFF38BDF8) else Color(0xFFF87171)).border(1.2.dp, Color.Black, CircleShape)) }
    }
}

private fun wireStyleForWidth(busWidth: Int, isCompact: Boolean = false): Pair<Color, Float> =
    when {
        busWidth >= 8 -> Turing.wireBlue to 5.2f
        busWidth >= 2 -> Turing.wireOrange to 3.8f
        else -> Turing.wireThin to if (isCompact) 3.2f else 2.6f
    }

private fun DrawScope.drawOrthWire(from: Offset, to: Offset, color: Color, thickPx: Float, isGhost: Boolean, dash: Boolean) {
    val dx = to.x - from.x; val stub = 18f
    val path = Path().apply {
        moveTo(from.x, from.y); lineTo(from.x + stub, from.y)
        if (abs(to.y - from.y) > 6f) {
            val ix = if (abs(dx) < 50f) (from.x + to.x) / 2f else from.x + 32f + (dx * 0.15f).coerceIn(0f, 80f)
            lineTo(ix, from.y); lineTo(ix, to.y); lineTo(to.x - 6f, to.y)
        } else lineTo(to.x - 6f, to.y)
        lineTo(to.x, to.y)
    }
    if (!isGhost) drawPath(path, color.copy(alpha = 0.18f), style = Stroke(width = thickPx + 4.5f))
    if (dash) drawPath(path, if (color == Turing.ghostBad) color else Turing.wireYellow.copy(alpha = 0.9f), style = Stroke(width = thickPx, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f)))
    else drawPath(path, color, style = Stroke(width = thickPx))
    drawCircle(color, thickPx * 0.55f + 1.2f, to); drawCircle(Color.White.copy(alpha = 0.9f), 1.8f, to)
    if (abs(to.y - from.y) > 18f) {
        val ix = if (abs(dx) < 50f) (from.x + to.x) / 2f else from.x + 32f + (dx * 0.15f).coerceIn(0f, 80f)
        drawCircle(color, 3.2f, Offset(ix, from.y)); drawCircle(color, 3.2f, Offset(ix, to.y))
        drawCircle(Color.White.copy(alpha = 0.7f), 1f, Offset(ix, from.y)); drawCircle(Color.White.copy(alpha = 0.7f), 1f, Offset(ix, to.y))
    }
}

private fun distPointToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ap = p - a; val ab = b - a; val ab2 = ab.x * ab.x + ab.y * ab.y
    if (ab2 == 0f) return (p - a).getDistance()
    var t = (ap.x * ab.x + ap.y * ab.y) / ab2; t = t.coerceIn(0f, 1f)
    val proj = Offset(a.x + ab.x * t, a.y + ab.y * t)
    return (p - proj).getDistance()
}
private fun distPointToOrth(p: Offset, from: Offset, to: Offset): Float {
    val dx = to.x - from.x; val ix = if (abs(dx) < 50f) (from.x + to.x) / 2f else from.x + 32f + (dx * 0.15f).coerceIn(0f, 80f)
    val p1 = from; val p2 = Offset(from.x + 18f, from.y); val p3 = Offset(ix, from.y); val p4 = Offset(ix, to.y); val p5 = Offset(to.x, to.y)
    var best = distPointToSegment(p, p1, p2)
    best = min(best, distPointToSegment(p, p2, p3)); best = min(best, distPointToSegment(p, p3, p4)); best = min(best, distPointToSegment(p, p4, p5))
    return best
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitFirstDown(requireUnconsumed: Boolean = true): androidx.compose.ui.input.pointer.PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
            val down = event.changes.firstOrNull { if (requireUnconsumed) !it.isConsumed else true } ?: continue; return down
        }
    }
}
