package com.vayunmathur.code.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import com.vayunmathur.code.syntax.LanguageSpec
import com.vayunmathur.code.syntax.SyntaxColors
import com.vayunmathur.code.syntax.TsColorSpan
import com.vayunmathur.code.syntax.rememberSyntaxColors
import com.vayunmathur.code.util.CodeActions
import com.vayunmathur.code.util.Completion
import com.vayunmathur.code.util.Diagnostic
import com.vayunmathur.code.util.DiagnosticSeverity
import com.vayunmathur.code.util.Edit
import com.vayunmathur.code.util.EditorDocument
import com.vayunmathur.code.util.TabUiState
import com.vayunmathur.code.util.computeFoldRegions
import com.vayunmathur.code.util.dedentSelection
import com.vayunmathur.code.util.indentSelection
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme

/**
 * The experimental virtualized editor engine (Phases 7–8). A [Canvas] draws only the lines inside
 * the viewport (with folded ranges skipped entirely), so syntax highlighting is per-visible-line.
 * It reuses the existing edit pipeline — input routes through [CodeActions.onEditorChange] /
 * [CodeActions.setSelection] — so undo/redo, auto-save and smart input keep working.
 *
 * Phase-8 additions: real line-hiding folds with gutter arrows, multi-cursor (Ctrl-D / Alt-tap),
 * indent guides, whitespace rendering, a minimap, and editor keyboard shortcuts. Find/replace,
 * autocomplete, soft-wrap and the system-keyboard IME (via [codeEditorInput]) are all wired; the
 * hardware-key path ([handleEditorKey]) remains for keys the IME does not deliver.
 */
@Composable
fun CodeEditorView(
    tab: TabUiState,
    actions: CodeActions,
    fontSize: Int,
    editorTheme: String,
    modifier: Modifier = Modifier,
    tabWidth: Int = 4,
    softWrap: Boolean = false,
    showWhitespace: Boolean = false,
    showIndentGuides: Boolean = false,
    showMinimap: Boolean = false,
    showFind: Boolean = false,
    onCloseFind: () -> Unit = {},
    initialQuery: String = "",
    completions: List<Completion> = emptyList(),
    showCompletions: Boolean = false,
    diagnostics: List<Diagnostic> = emptyList(),
    secondaryPane: Boolean = false,
    onValueChangeOverride: ((TextFieldValue) -> Unit)? = null,
) {
    val value = tab.value
    val text = value.text
    val colors = rememberSyntaxColors(editorTheme)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant
    val caretColor = MaterialTheme.colorScheme.primary
    val guideColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val extraCaretColor = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error
    val warningColor = Color(0xFFFFB300)
    val infoColor = gutterColor
    fun severityColor(s: DiagnosticSeverity): Color = when (s) {
        DiagnosticSeverity.ERROR -> errorColor
        DiagnosticSeverity.WARNING -> warningColor
        DiagnosticSeverity.INFO -> infoColor
    }
    val diagnosticsByLine = remember(diagnostics) { diagnostics.groupBy { it.line } }
    val spec = tab.language.spec
    // Native tree-sitter spans (parsed off the main thread); null → the regex tokenizer in
    // [annotatedLine] is used, both while the parse is in flight and when it is unavailable.
    val tsSpans = rememberTreeSitterSpans(text, tab.language, colors)

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = remember(fontSize, onSurface) {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, color = onSurface)
    }
    val metrics = remember(style) { measurer.measure("M", style) }
    val lineHeight = metrics.size.height.toFloat().coerceAtLeast(1f)
    val charWidth = metrics.size.width.toFloat().coerceAtLeast(1f)

    val lines = remember(text) { text.split("\n") }
    val lineStarts = remember(text) {
        IntArray(lines.size).also { starts ->
            var acc = 0
            for (i in lines.indices) {
                starts[i] = acc
                acc += lines[i].length + 1
            }
        }
    }
    val longestLine = remember(text) { lines.maxOfOrNull { it.length } ?: 0 }
    val gutterWidth = with(density) { ((lines.size.toString().length * 10) + 28).dp.toPx() }

    // Folding state lives on the tab (persisted per file); multi-cursor is view-local.
    val foldedHeaders = tab.foldedHeaders
    var extraCarets by remember(tab.name) { mutableStateOf(listOf<Int>()) }
    // In-progress IME composition region (reported by the system keyboard), drawn underlined.
    var composing by remember(tab.name) { mutableStateOf<TextRange?>(null) }

    val foldByHeader = remember(text) { computeFoldRegions(text).associateBy { it.startLine } }
    val hiddenLines = remember(text, foldedHeaders) {
        val hidden = HashSet<Int>()
        for (header in foldedHeaders) {
            val region = foldByHeader[header] ?: continue
            for (l in (region.startLine + 1)..region.endLine) hidden.add(l)
        }
        hidden
    }
    val visibleLines = remember(text, hiddenLines) { lines.indices.filter { it !in hiddenLines } }

    var scrollY by remember { mutableStateOf(0f) }
    var scrollX by remember { mutableStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val focusRequester = remember { FocusRequester() }

    // ---- Soft-wrap (Phase 5): each source line may span several visual rows. ----
    val wrapping = softWrap && viewport.width > 0
    val wrapWidthPx = (viewport.width - gutterWidth).coerceAtLeast(1f)
    val wrapConstraints = Constraints(maxWidth = wrapWidthPx.toInt().coerceAtLeast(1))
    // Wrapped-row count per source line (1 for empty lines / when not wrapping).
    val wrapCounts = remember(text, wrapping, wrapWidthPx.toInt(), style) {
        if (!wrapping) {
            IntArray(lines.size) { 1 }
        } else {
            IntArray(lines.size) { i ->
                val ln = lines[i]
                if (ln.isEmpty()) 1
                else measurer.measure(AnnotatedString(ln), style, constraints = wrapConstraints).lineCount.coerceAtLeast(1)
            }
        }
    }
    // Cumulative visual-row start for each visible source line (last entry = total visual rows).
    val visualStarts = remember(visibleLines, wrapCounts, wrapping) {
        IntArray(visibleLines.size + 1).also { arr ->
            var acc = 0
            for (k in visibleLines.indices) {
                arr[k] = acc
                acc += if (wrapping) wrapCounts[visibleLines[k]] else 1
            }
            arr[visibleLines.size] = acc
        }
    }
    val totalVisualRows = visualStarts.last()

    val totalHeight = lineHeight * totalVisualRows
    val totalWidth = if (wrapping) viewport.width.toFloat() else gutterWidth + longestLine * charWidth + charWidth
    val maxScrollY = (totalHeight - viewport.height).coerceAtLeast(0f)
    val maxScrollX = if (wrapping) 0f else (totalWidth - viewport.width).coerceAtLeast(0f)
    scrollY = scrollY.coerceIn(0f, maxScrollY)
    scrollX = scrollX.coerceIn(0f, maxScrollX)

    val vScroll = rememberScrollableState { delta ->
        val newY = (scrollY - delta).coerceIn(0f, maxScrollY)
        val consumed = scrollY - newY
        scrollY = newY
        consumed
    }
    val hScroll = rememberScrollableState { delta ->
        val newX = (scrollX - delta).coerceIn(0f, maxScrollX)
        val consumed = scrollX - newX
        scrollX = newX
        consumed
    }

    val emit: (TextFieldValue) -> Unit = onValueChangeOverride ?: actions::onEditorChange

    // ---- Find state (Phase 4): mirrors the classic editor, highlighted in the Canvas below. ----
    var query by remember(tab.name) { mutableStateOf(initialQuery) }
    var replacement by remember(tab.name) { mutableStateOf("") }
    var caseSensitive by remember(tab.name) { mutableStateOf(false) }
    var useRegex by remember(tab.name) { mutableStateOf(false) }
    var activeMatch by remember(tab.name) { mutableStateOf(0) }
    val regexValid = remember(query, useRegex) {
        !useRegex || query.isEmpty() || runCatching { Regex(query) }.isSuccess
    }
    val matches = remember(text, query, caseSensitive, useRegex) {
        findMatchRanges(text, query, caseSensitive, useRegex)
    }
    val highlightMatches = if (showFind) matches else emptyList()
    LaunchedEffect(matches.size) { if (activeMatch >= matches.size) activeMatch = 0 }
    val goTo: (Int) -> Unit = { target ->
        if (matches.isNotEmpty()) {
            val idx = ((target % matches.size) + matches.size) % matches.size
            activeMatch = idx
            val r = matches[idx]
            actions.setSelection(TextRange(r.first, r.last + 1))
        }
    }
    // Keep the active match scrolled into view as the user navigates.
    LaunchedEffect(activeMatch, matches.size) {
        if (showFind && matches.isNotEmpty() && viewport.height > 0) {
            val r = matches[activeMatch.coerceIn(0, matches.size - 1)]
            val k = visibleLines.indexOf(lineOfOffset(lineStarts, r.first))
            if (k >= 0) {
                val top = visualStarts[k] * lineHeight
                if (top < scrollY) {
                    scrollY = top
                } else if (top + lineHeight > scrollY + viewport.height) {
                    scrollY = (top - viewport.height + lineHeight).coerceAtLeast(0f)
                }
            }
        }
    }

    fun displayRowToSource(row: Int): Int? = visibleLines.getOrNull(row)

    /** Largest visible-line index k with `visualStarts[k] <= visualRow` (soft-wrap row lookup). */
    fun visualRowToVisibleIndex(visualRow: Int): Int {
        if (visibleLines.isEmpty()) return 0
        var lo = 0
        var hi = visibleLines.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (visualStarts[mid] <= visualRow) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun offsetAt(x: Float, y: Float): Int {
        val row = ((y + scrollY) / lineHeight).toInt()
        val source = displayRowToSource(row.coerceIn(0, (visibleLines.size - 1).coerceAtLeast(0))) ?: 0
        val col = (((x + scrollX) - gutterWidth) / charWidth).toInt().coerceIn(0, lines[source].length)
        return (lineStarts[source] + col).coerceIn(0, text.length)
    }

    /** Tap → text offset when soft-wrapping, using the tapped source line's wrapped layout. */
    fun offsetAtWrapped(x: Float, y: Float): Int {
        if (visibleLines.isEmpty()) return 0
        val visualRow = ((y + scrollY) / lineHeight).toInt().coerceIn(0, (totalVisualRows - 1).coerceAtLeast(0))
        val k = visualRowToVisibleIndex(visualRow)
        val source = visibleLines[k]
        val sub = (visualRow - visualStarts[k]).coerceAtLeast(0)
        val layout = measurer.measure(annotatedLine(lines[source], spec, colors), style, constraints = wrapConstraints)
        val localY = sub * lineHeight + lineHeight / 2f
        val localX = (x - gutterWidth).coerceAtLeast(0f)
        val local = runCatching { layout.getOffsetForPosition(Offset(localX, localY)) }
            .getOrDefault(0).coerceIn(0, lines[source].length)
        return (lineStarts[source] + local).coerceIn(0, text.length)
    }

    val editorCanvas: @Composable (Modifier) -> Unit = { canvasModifier ->
        Canvas(
            canvasModifier
                .onSizeChanged { viewport = it }
                .scrollable(vScroll, Orientation.Vertical)
                .scrollable(hScroll, Orientation.Horizontal)
                .pointerInput(text, visibleLines, wrapping) {
                    detectTapGestures { pos ->
                        if (wrapping) {
                            val visualRow = ((pos.y + scrollY) / lineHeight).toInt()
                                .coerceIn(0, (totalVisualRows - 1).coerceAtLeast(0))
                            val source = visibleLines.getOrNull(visualRowToVisibleIndex(visualRow))
                            if (pos.x < gutterWidth && source != null && foldByHeader.containsKey(source)) {
                                actions.toggleFold(source)
                            } else {
                                actions.setSelection(TextRange(offsetAtWrapped(pos.x, pos.y)))
                                extraCarets = emptyList()
                                focusRequester.requestFocus()
                            }
                        } else {
                            val row = ((pos.y + scrollY) / lineHeight).toInt()
                            val source = displayRowToSource(row)
                            if (pos.x < gutterWidth && source != null && foldByHeader.containsKey(source)) {
                                actions.toggleFold(source)
                            } else {
                                actions.setSelection(TextRange(offsetAt(pos.x, pos.y)))
                                extraCarets = emptyList()
                                focusRequester.requestFocus()
                            }
                        }
                    }
                }
                .focusRequester(focusRequester)
                .focusable()
                .onFocusChanged { if (it.isFocused) actions.focusPane(secondaryPane) }
                .codeEditorInput(
                    enabled = true,
                    value = value,
                    onValueChange = emit,
                    onComposingChange = { composing = it },
                )
                .onPreviewKeyEvent { event ->
                    handleEditorKey(
                        event = event,
                        value = value,
                        tabWidth = tabWidth,
                        extraCarets = extraCarets,
                        setExtraCarets = { extraCarets = it },
                        emit = emit,
                        move = { actions.setSelection(it) },
                        onSave = { actions.save() },
                        onComment = { actions.toggleComment() },
                        foldAll = { actions.foldAllInTab() },
                        unfoldAll = { actions.unfoldAll() },
                        toggleFoldAtCaret = { caret ->
                            val line = lineOfOffset(lineStarts, caret)
                            val header = foldByHeader.keys.filter { h ->
                                val r = foldByHeader[h]!!
                                line in r.startLine..r.endLine
                            }.maxOrNull()
                            if (header != null) actions.toggleFold(header)
                        },
                    )
                },
        ) {
            val caret = if (value.selection.collapsed) value.selection.start else -1
            val selMin = value.selection.min
            val selMax = value.selection.max

            if (!wrapping) {
            val firstRow = (scrollY / lineHeight).toInt().coerceAtLeast(0)
            val rowsInView = (size.height / lineHeight).toInt() + 2
            val lastRow = (firstRow + rowsInView).coerceAtMost(visibleLines.size - 1)

            for (row in firstRow..lastRow) {
                val source = visibleLines[row]
                val y = row * lineHeight - scrollY
                val lineStart = lineStarts[source]
                val lineText = lines[source]
                val lineLen = lineText.length

                if (caret in lineStart..(lineStart + lineLen)) {
                    drawRect(colors.currentLine, topLeft = Offset(0f, y), size = Size(size.width, lineHeight))
                }

                if (selMax > selMin) {
                    val a = (selMin - lineStart).coerceIn(0, lineLen)
                    val spansEol = selMax > lineStart + lineLen
                    val b = if (spansEol) lineLen else (selMax - lineStart).coerceIn(0, lineLen)
                    if (b > a || (selMin <= lineStart + lineLen && spansEol)) {
                        val startX = gutterWidth + a * charWidth - scrollX
                        val w = (b - a).coerceAtLeast(0) * charWidth + if (spansEol) charWidth else 0f
                        if (w > 0f) drawRect(colors.match, topLeft = Offset(startX, y), size = Size(w, lineHeight))
                    }
                }

                // Find-match highlight (Phase 4): the active match is drawn more strongly.
                if (highlightMatches.isNotEmpty()) {
                    for ((mi, m) in highlightMatches.withIndex()) {
                        val ms = m.first
                        val me = m.last + 1
                        if (me <= lineStart || ms >= lineStart + lineLen) continue
                        val a = (ms - lineStart).coerceIn(0, lineLen)
                        val b = (me - lineStart).coerceIn(0, lineLen)
                        if (b > a) {
                            val startX = gutterWidth + a * charWidth - scrollX
                            val w = (b - a) * charWidth
                            val matchColor = if (mi == activeMatch) caretColor.copy(alpha = 0.35f) else colors.match
                            drawRect(matchColor, topLeft = Offset(startX, y), size = Size(w, lineHeight))
                        }
                    }
                }

                if (showIndentGuides) {
                    val columns = leadingColumns(lineText, tabWidth)
                    var level = tabWidth
                    while (level < columns) {
                        val gx = gutterWidth + level * charWidth - scrollX
                        drawRect(guideColor, topLeft = Offset(gx, y), size = Size(1f, lineHeight))
                        level += tabWidth
                    }
                }

                if (showWhitespace) {
                    var i = 0
                    var col = 0
                    while (i < lineText.length && (lineText[i] == ' ' || lineText[i] == '\t')) {
                        val cx = gutterWidth + col * charWidth - scrollX
                        if (lineText[i] == ' ') {
                            drawCircle(guideColor, radius = 1.5f, center = Offset(cx + charWidth / 2f, y + lineHeight / 2f))
                            col++
                        } else {
                            col += tabWidth
                        }
                        i++
                    }
                }

                // Gutter: line number and fold arrow.
                val number = measurer.measure(AnnotatedString((source + 1).toString()), style)
                drawText(number, color = gutterColor, topLeft = Offset(gutterWidth - number.size.width - 6f, y))
                if (foldByHeader.containsKey(source)) {
                    val arrow = if (source in foldedHeaders) "\u25B8" else "\u25BE"
                    val arrowLayout = measurer.measure(AnnotatedString(arrow), style)
                    drawText(arrowLayout, color = gutterColor, topLeft = Offset(2f, y))
                }

                // Lines past MAX_LINE_HIGHLIGHT render unstyled, so only the columns inside the
                // viewport need measuring — a one-line multi-megabyte document (a minified JSON
                // blob, say) would otherwise lay its whole line out on every frame.
                if (lineLen > MAX_LINE_HIGHLIGHT) {
                    val firstCol = (scrollX / charWidth).toInt().coerceIn(0, lineLen)
                    val lastCol = (firstCol + (size.width / charWidth).toInt() + 2).coerceAtMost(lineLen)
                    if (lastCol > firstCol) {
                        val slice = measurer.measure(AnnotatedString(lineText.substring(firstCol, lastCol)), style)
                        drawText(slice, topLeft = Offset(gutterWidth + firstCol * charWidth - scrollX, y))
                    }
                } else {
                    val annotated = annotatedLine(lineText, spec, colors, tsSpans, lineStart)
                    drawText(measurer.measure(annotated, style), topLeft = Offset(gutterWidth - scrollX, y))
                }

                if (caret in lineStart..(lineStart + lineLen)) {
                    val cx = gutterWidth + (caret - lineStart) * charWidth - scrollX
                    drawRect(caretColor, topLeft = Offset(cx, y), size = Size(2f, lineHeight))
                }
                composing?.let { c ->
                    val cs = c.min
                    val ce = c.max
                    if (ce > lineStart && cs < lineStart + lineLen) {
                        val a = (cs - lineStart).coerceIn(0, lineLen)
                        val b = (ce - lineStart).coerceIn(0, lineLen)
                        if (b > a) {
                            val ux = gutterWidth + a * charWidth - scrollX
                            drawRect(caretColor, topLeft = Offset(ux, y + lineHeight - 2f), size = Size((b - a) * charWidth, 2f))
                        }
                    }
                }
                for (extra in extraCarets) {
                    if (extra in lineStart..(lineStart + lineLen)) {
                        val cx = gutterWidth + (extra - lineStart) * charWidth - scrollX
                        drawRect(extraCaretColor, topLeft = Offset(cx, y), size = Size(2f, lineHeight))
                    }
                }

                // Diagnostics: squiggly underline per range + a severity dot in the gutter.
                diagnosticsByLine[source]?.let { diags ->
                    for (d in diags) {
                        val a = d.startCol.coerceIn(0, lineLen)
                        val b = (if (d.endCol > d.startCol) d.endCol else lineLen).coerceIn(a + 1, (lineLen + 1))
                        val ax = gutterWidth + a * charWidth - scrollX
                        val bx = gutterWidth + b * charWidth - scrollX
                        drawSquiggle(ax, bx, y + lineHeight - 1f, severityColor(d.severity))
                    }
                    val worst = diags.minByOrNull { it.severity.ordinal }
                    if (worst != null) {
                        drawCircle(severityColor(worst.severity), radius = 3f, center = Offset(12f, y + lineHeight / 2f))
                    }
                }
            }
            } else {
                // ---- Soft-wrap draw path: one measured layout per visible source line. ----
                val firstVisual = (scrollY / lineHeight).toInt().coerceAtLeast(0)
                val rowsInView = (size.height / lineHeight).toInt() + 2
                val lastVisual = (firstVisual + rowsInView).coerceAtMost((totalVisualRows - 1).coerceAtLeast(0))
                if (totalVisualRows > 0) {
                    val firstK = visualRowToVisibleIndex(firstVisual)
                    val lastK = visualRowToVisibleIndex(lastVisual)
                    for (k in firstK..lastK) {
                        val source = visibleLines[k]
                        val lineTop = visualStarts[k] * lineHeight - scrollY
                        val lineStart = lineStarts[source]
                        val lineText = lines[source]
                        val lineLen = lineText.length
                        val layout = measurer.measure(annotatedLine(lineText, spec, colors, tsSpans, lineStart), style, constraints = wrapConstraints)
                        val blockHeight = wrapCounts[source] * lineHeight

                        if (caret in lineStart..(lineStart + lineLen)) {
                            drawRect(colors.currentLine, topLeft = Offset(0f, lineTop), size = Size(size.width, blockHeight))
                        }

                        if (selMax > selMin) {
                            val a = (selMin - lineStart).coerceIn(0, lineLen)
                            val b = (selMax - lineStart).coerceIn(0, lineLen)
                            if (b > a) {
                                val path = layout.getPathForRange(a, b).apply { translate(Offset(gutterWidth, lineTop)) }
                                drawPath(path, colors.match)
                            }
                        }

                        if (highlightMatches.isNotEmpty()) {
                            for ((mi, m) in highlightMatches.withIndex()) {
                                val ms = m.first
                                val me = m.last + 1
                                if (me <= lineStart || ms >= lineStart + lineLen) continue
                                val a = (ms - lineStart).coerceIn(0, lineLen)
                                val b = (me - lineStart).coerceIn(0, lineLen)
                                if (b > a) {
                                    val path = layout.getPathForRange(a, b).apply { translate(Offset(gutterWidth, lineTop)) }
                                    drawPath(path, if (mi == activeMatch) caretColor.copy(alpha = 0.35f) else colors.match)
                                }
                            }
                        }

                        val number = measurer.measure(AnnotatedString((source + 1).toString()), style)
                        drawText(number, color = gutterColor, topLeft = Offset(gutterWidth - number.size.width - 6f, lineTop))
                        if (foldByHeader.containsKey(source)) {
                            val arrow = if (source in foldedHeaders) "\u25B8" else "\u25BE"
                            drawText(measurer.measure(AnnotatedString(arrow), style), color = gutterColor, topLeft = Offset(2f, lineTop))
                        }

                        drawText(layout, topLeft = Offset(gutterWidth, lineTop))

                        if (caret in lineStart..(lineStart + lineLen)) {
                            val rect = layout.getCursorRect((caret - lineStart).coerceIn(0, lineLen))
                            drawRect(caretColor, topLeft = Offset(gutterWidth + rect.left, lineTop + rect.top), size = Size(2f, rect.height))
                        }
                        composing?.let { c ->
                            if (c.max > lineStart && c.min < lineStart + lineLen) {
                                val a = (c.min - lineStart).coerceIn(0, lineLen)
                                val b = (c.max - lineStart).coerceIn(0, lineLen)
                                if (b > a) {
                                    val path = layout.getPathForRange(a, b).apply { translate(Offset(gutterWidth, lineTop)) }
                                    drawPath(path, caretColor.copy(alpha = 0.25f))
                                }
                            }
                        }
                        for (extra in extraCarets) {
                            if (extra in lineStart..(lineStart + lineLen)) {
                                val rect = layout.getCursorRect((extra - lineStart).coerceIn(0, lineLen))
                                drawRect(extraCaretColor, topLeft = Offset(gutterWidth + rect.left, lineTop + rect.top), size = Size(2f, rect.height))
                            }
                        }

                        // Diagnostics: highlight the range (wrapped) + a gutter severity dot.
                        diagnosticsByLine[source]?.let { diags ->
                            for (d in diags) {
                                val a = d.startCol.coerceIn(0, lineLen)
                                val b = (if (d.endCol > d.startCol) d.endCol else lineLen).coerceIn(a + 1, lineLen + 1).coerceAtMost(lineLen)
                                if (b > a) {
                                    val path = layout.getPathForRange(a, b).apply { translate(Offset(gutterWidth, lineTop)) }
                                    drawPath(path, severityColor(d.severity).copy(alpha = 0.20f))
                                }
                            }
                            val worst = diags.minByOrNull { it.severity.ordinal }
                            if (worst != null) {
                                drawCircle(severityColor(worst.severity), radius = 3f, center = Offset(12f, lineTop + lineHeight / 2f))
                            }
                        }
                    }
                }
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        if (showFind) {
            FindBar(
                query = query,
                replacement = replacement,
                caseSensitive = caseSensitive,
                useRegex = useRegex,
                regexValid = regexValid,
                matchCount = matches.size,
                activeMatch = activeMatch,
                onQueryChange = { query = it; activeMatch = 0 },
                onReplacementChange = { replacement = it },
                onToggleCase = { caseSensitive = !caseSensitive },
                onToggleRegex = { useRegex = !useRegex; activeMatch = 0 },
                onNext = { goTo(activeMatch + 1) },
                onPrev = { goTo(activeMatch - 1) },
                onReplace = {
                    matches.getOrNull(activeMatch)?.let { range ->
                        if (useRegex) {
                            actions.replaceMatchRegex(range, query, replacement, caseSensitive)
                        } else {
                            actions.replaceRange(range, replacement)
                        }
                    }
                },
                onReplaceAll = {
                    if (useRegex) {
                        actions.replaceAllRegex(query, replacement, caseSensitive)
                    } else {
                        actions.replaceAll(matches, replacement)
                    }
                },
                onClose = onCloseFind,
            )
            HorizontalDivider()
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showMinimap) {
                Row(Modifier.fillMaxSize()) {
                    editorCanvas(Modifier.weight(1f).fillMaxHeight())
                    Minimap(
                        lines = lines,
                        lineHeight = lineHeight,
                        scrollY = scrollY,
                        maxScrollY = maxScrollY,
                        viewportHeight = viewport.height.toFloat(),
                        totalHeight = totalHeight,
                        color = gutterColor.copy(alpha = 0.5f),
                        viewportColor = caretColor.copy(alpha = 0.2f),
                        onScrollTo = { fraction -> scrollY = (fraction * maxScrollY).coerceIn(0f, maxScrollY) },
                        modifier = Modifier.width(56.dp).fillMaxHeight(),
                    )
                }
            } else {
                editorCanvas(Modifier.fillMaxSize())
            }

            // Autocomplete popup (Phase 4), anchored at the caret in Canvas coordinates.
            val caretOffset = value.selection.start
            if (showCompletions && completions.isNotEmpty() && value.selection.collapsed) {
                val src = lineOfOffset(lineStarts, caretOffset)
                val k = visibleLines.indexOf(src)
                if (k >= 0) {
                    val col = caretOffset - lineStarts[src]
                    val px: Int
                    val py: Int
                    if (wrapping) {
                        val layout = measurer.measure(annotatedLine(lines[src], spec, colors), style, constraints = wrapConstraints)
                        val rect = layout.getCursorRect(col.coerceIn(0, lines[src].length))
                        px = (gutterWidth + rect.left).roundToInt()
                        py = (visualStarts[k] * lineHeight - scrollY + rect.bottom).roundToInt()
                    } else {
                        px = (gutterWidth + col * charWidth - scrollX).roundToInt()
                        py = (k * lineHeight - scrollY + lineHeight).roundToInt()
                    }
                    CompletionPopup(
                        completions = completions,
                        offsetX = px,
                        offsetY = py,
                        onAccept = { actions.acceptCompletion(it) },
                        onDismiss = { actions.dismissCompletions() },
                    )
                }
            }
        }
    }
}

/** A narrow overview: one dim bar per source line (width ∝ length) plus a draggable viewport rect. */
@Composable
private fun Minimap(
    lines: List<String>,
    lineHeight: Float,
    scrollY: Float,
    maxScrollY: Float,
    viewportHeight: Float,
    totalHeight: Float,
    color: Color,
    viewportColor: Color,
    onScrollTo: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .background(color.copy(alpha = 0.05f))
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ ->
                    onScrollTo((change.position.y / size.height).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos -> onScrollTo((pos.y / size.height).coerceIn(0f, 1f)) }
            },
    ) {
        if (lines.isEmpty()) return@Canvas
        val rowH = (size.height / lines.size).coerceAtMost(3f)
        val maxLen = (lines.maxOfOrNull { it.length } ?: 1).coerceAtLeast(1)
        for (i in lines.indices) {
            val len = lines[i].length
            if (len == 0) continue
            val w = (len.toFloat() / maxLen) * (size.width - 4f)
            drawRect(color, topLeft = Offset(2f, i * rowH), size = Size(w, (rowH - 0.5f).coerceAtLeast(0.5f)))
        }
        // Viewport rectangle.
        if (totalHeight > 0f && viewportHeight > 0f) {
            val top = (scrollY / totalHeight) * size.height
            val h = (viewportHeight / totalHeight) * size.height
            drawRect(viewportColor, topLeft = Offset(0f, top), size = Size(size.width, h))
        }
    }
}

/** Tokenizes a single line with [spec] and paints rainbow brackets; returns a colored string. */
private fun annotatedLine(
    line: String,
    spec: LanguageSpec?,
    colors: SyntaxColors,
    tsSpans: List<TsColorSpan>? = null,
    lineStart: Int = 0,
): AnnotatedString {
    if (line.length > MAX_LINE_HIGHLIGHT) return AnnotatedString(line)
    // Tree-sitter path: paint the spans that fall on this line (over rainbow brackets).
    if (tsSpans != null && line.isNotEmpty()) {
        val builder = AnnotatedString.Builder(line)
        if (colors.brackets.isNotEmpty()) {
            var depth = 0
            val n = colors.brackets.size
            for (i in line.indices) {
                when (line[i]) {
                    '(', '[', '{' -> {
                        builder.addStyle(SpanStyle(color = colors.brackets[depth % n]), i, i + 1)
                        depth++
                    }
                    ')', ']', '}' -> {
                        depth = (depth - 1).coerceAtLeast(0)
                        builder.addStyle(SpanStyle(color = colors.brackets[depth % n]), i, i + 1)
                    }
                }
            }
        }
        val lineEnd = lineStart + line.length
        for (s in tsSpans) {
            if (s.end <= lineStart || s.start >= lineEnd) continue
            val a = (s.start - lineStart).coerceIn(0, line.length)
            val b = (s.end - lineStart).coerceIn(a, line.length)
            if (b > a) builder.addStyle(SpanStyle(color = s.color), a, b)
        }
        return builder.toAnnotatedString()
    }
    if (spec == null || line.isEmpty()) return AnnotatedString(line)
    val builder = AnnotatedString.Builder(line)
    if (colors.brackets.isNotEmpty()) {
        var depth = 0
        val n = colors.brackets.size
        for (i in line.indices) {
            when (line[i]) {
                '(', '[', '{' -> {
                    builder.addStyle(SpanStyle(color = colors.brackets[depth % n]), i, i + 1)
                    depth++
                }
                ')', ']', '}' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    builder.addStyle(SpanStyle(color = colors.brackets[depth % n]), i, i + 1)
                }
            }
        }
    }
    for (match in spec.regex.findAll(line)) {
        val kind = spec.kindFor(match) ?: continue
        builder.addStyle(SpanStyle(color = colors.colorFor(kind)), match.range.first, match.range.last + 1)
    }
    return builder.toAnnotatedString()
}

/** Leading indentation width in columns, expanding each tab to [tabWidth]. */
private fun leadingColumns(line: String, tabWidth: Int): Int {
    var col = 0
    for (c in line) {
        when (c) {
            ' ' -> col++
            '\t' -> col += tabWidth
            else -> return col
        }
    }
    return col
}

/** The 0-based line containing [offset], by binary search over [lineStarts]. */
private fun lineOfOffset(lineStarts: IntArray, offset: Int): Int {
    var lo = 0
    var hi = lineStarts.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
    }
    return lo
}

/**
 * Handles an editor key press. Text edits go through [emit] (so smart input, undo/redo and
 * auto-save apply); multi-caret inserts/deletes are computed with an [EditorDocument]. Returns true
 * when the event was consumed.
 */
private fun handleEditorKey(
    event: KeyEvent,
    value: TextFieldValue,
    tabWidth: Int,
    extraCarets: List<Int>,
    setExtraCarets: (List<Int>) -> Unit,
    emit: (TextFieldValue) -> Unit,
    move: (TextRange) -> Unit,
    onSave: () -> Unit,
    onComment: () -> Unit,
    foldAll: () -> Unit,
    unfoldAll: () -> Unit,
    toggleFoldAtCaret: (Int) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val text = value.text
    val start = value.selection.min
    val end = value.selection.max

    if (event.isCtrlPressed) {
        when (event.key) {
            Key.S -> { onSave(); return true }
            Key.Slash -> { onComment(); return true }
            Key.D -> {
                val range = selectionOrWord(text, value.selection)
                if (range != null) {
                    val word = text.substring(range.first, range.second)
                    val from = (extraCarets + end).max()
                    val idx = text.indexOf(word, from)
                    if (idx >= 0) setExtraCarets(extraCarets + (idx + word.length))
                }
                return true
            }
            Key.LeftBracket -> {
                if (event.isShiftPressed) foldAll() else toggleFoldAtCaret(start)
                return true
            }
            Key.RightBracket -> {
                if (event.isShiftPressed) unfoldAll() else toggleFoldAtCaret(start)
                return true
            }
        }
        return false
    }

    when (event.key) {
        Key.Escape -> {
            if (extraCarets.isNotEmpty()) {
                setExtraCarets(emptyList())
                return true
            }
            return false
        }
        Key.Tab -> {
            emit(if (event.isShiftPressed) dedentSelection(value, tabWidth) else indentSelection(value, " ".repeat(tabWidth)))
            return true
        }
        Key.DirectionLeft -> { move(TextRange((start - 1).coerceAtLeast(0))); return true }
        Key.DirectionRight -> { move(TextRange((end + 1).coerceAtMost(text.length))); return true }
        Key.Backspace -> {
            if (extraCarets.isNotEmpty()) {
                applyMultiCaret(text, start, extraCarets, insert = null, emit, setExtraCarets)
            } else if (start != end) {
                emit(TextFieldValue(text.substring(0, start) + text.substring(end), TextRange(start)))
            } else if (start > 0) {
                emit(TextFieldValue(text.substring(0, start - 1) + text.substring(start), TextRange(start - 1)))
            }
            return true
        }
        Key.Enter, Key.NumPadEnter -> {
            emit(TextFieldValue(text.substring(0, start) + "\n" + text.substring(end), TextRange(start + 1)))
            return true
        }
    }

    val codePoint = event.nativeKeyEvent.unicodeChar
    if (codePoint != 0) {
        val ch = codePoint.toChar().toString()
        if (extraCarets.isNotEmpty()) {
            applyMultiCaret(text, start, extraCarets, insert = ch, emit, setExtraCarets)
        } else {
            emit(TextFieldValue(text.substring(0, start) + ch + text.substring(end), TextRange(start + 1)))
        }
        return true
    }
    return false
}

/**
 * Applies a single-character [insert] (or a backspace when null) at the primary caret plus every
 * extra caret, using an [EditorDocument] for the edit, then re-emits the primary value and the
 * shifted extra carets.
 */
private fun applyMultiCaret(
    text: String,
    primary: Int,
    extras: List<Int>,
    insert: String?,
    emit: (TextFieldValue) -> Unit,
    setExtraCarets: (List<Int>) -> Unit,
) {
    val carets = (extras + primary).distinct().sorted()
    val doc = EditorDocument(text)
    val edits = if (insert != null) {
        carets.map { Edit(it, it, insert) }
    } else {
        carets.filter { it > 0 }.map { Edit(it - 1, it, "") }
    }
    if (edits.isEmpty()) return
    doc.applyEdits(edits)
    val delta = if (insert != null) insert.length else -1
    val newCarets = carets.mapIndexed { index, c ->
        if (insert != null) c + delta * (index + 1) else (c + delta * (index + 1)).coerceAtLeast(0)
    }
    val primaryIndex = carets.indexOf(primary)
    val newPrimary = newCarets.getOrElse(primaryIndex) { newCarets.lastOrNull() ?: 0 }
    emit(TextFieldValue(doc.text, TextRange(newPrimary.coerceIn(0, doc.length))))
    setExtraCarets(newCarets.filterIndexed { i, _ -> i != primaryIndex }.map { it.coerceIn(0, doc.length) })
}

/** The selection range (end-exclusive) if non-empty, else the identifier word around the caret. */
private fun selectionOrWord(text: String, selection: TextRange): Pair<Int, Int>? {
    if (!selection.collapsed) return selection.min to selection.max
    var start = selection.start
    var end = selection.start
    while (start > 0 && text[start - 1].isJavaIdentifierChar()) start--
    while (end < text.length && text[end].isJavaIdentifierChar()) end++
    return if (end > start) start to end else null
}

private fun Char.isJavaIdentifierChar(): Boolean = isLetterOrDigit() || this == '_'

/** Draws a zigzag ("squiggle") underline from [ax] to [bx] at baseline [yBase]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSquiggle(
    ax: Float,
    bx: Float,
    yBase: Float,
    color: Color,
) {
    if (bx <= ax) return
    val step = 3f
    val amp = 2f
    var x = ax
    var prev = Offset(ax, yBase)
    var up = false
    while (x < bx) {
        val nx = (x + step).coerceAtMost(bx)
        val ny = if (up) yBase - amp else yBase
        drawLine(color, prev, Offset(nx, ny), strokeWidth = 1f)
        prev = Offset(nx, ny)
        x = nx
        up = !up
    }
}

private const val MAX_LINE_HIGHLIGHT = 2000
