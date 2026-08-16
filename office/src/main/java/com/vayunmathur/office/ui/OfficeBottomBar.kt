package com.vayunmathur.office.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheckBox
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconFormatAlignCenter
import com.vayunmathur.library.ui.IconFormatAlignJustify
import com.vayunmathur.library.ui.IconFormatAlignLeft
import com.vayunmathur.library.ui.IconFormatAlignRight
import com.vayunmathur.library.ui.IconFormatColorText
import com.vayunmathur.library.ui.IconFormatIndentDecrease
import com.vayunmathur.library.ui.IconFormatIndentIncrease
import com.vayunmathur.library.ui.IconFormatListBulleted
import com.vayunmathur.library.ui.IconFormatListNumbered
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.vayunmathur.office.odf.*
import com.vayunmathur.library.ui.odf.*
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.library.ui.EditorBaseButtons
import com.vayunmathur.library.ui.EditorFormat
import com.vayunmathur.library.ui.EditorFormatter
import com.vayunmathur.library.ui.LinkContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.office.R

/** What the shared bottom bar is currently formatting. (Phase 2) */
sealed interface FormatTarget {
    data object None : FormatTarget
    data class TextRun(val runStart: Int, val runEnd: Int, val selStart: Int, val selEnd: Int) : FormatTarget
    data class Cell(val sheet: Int, val row: Int, val col: Int) : FormatTarget
    data class Element(val slide: Int, val element: Int) : FormatTarget
}

/** Which insert actions are available for the current document type. (Phase 2) */
data class DocCaps(
    val insertImage: Boolean = false,
    val insertShape: Boolean = false,
    val insertChart: Boolean = false,
    val insertTable: Boolean = false
)

enum class ShapeKind { RECT, ELLIPSE, LINE }

/** Callbacks the shared bottom bar may invoke. */
data class BottomBarActions(
    val onTextColor: () -> Unit = {},
    val onCellTextColor: () -> Unit = {},
    val onCellBgColor: () -> Unit = {},
    val onSlideTextColor: () -> Unit = {},
    val onSlideFill: () -> Unit = {},
    val onSlideStroke: () -> Unit = {},
    val onFontSize: () -> Unit = {},
    val onInsertImage: () -> Unit = {},
    val onInsertShape: (ShapeKind) -> Unit = {},
    val onInsertChart: () -> Unit = {},
    val onInsertTable: () -> Unit = {},
    val onDeleteElement: () -> Unit = {},
    val onCellBorder: () -> Unit = {},
    val onCellComment: () -> Unit = {},
    val onCellResize: () -> Unit = {},
    val onSlideNotes: () -> Unit = {},
    val onSlideBackground: () -> Unit = {},
    val onSlideTransition: () -> Unit = {}
)

@Composable
internal fun FmtIcon(active: Boolean, enabled: Boolean, icon: @Composable () -> Unit, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        CompositionLocalProvider(
            LocalContentColor provides if (active && enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current
        ) {
            icon()
        }
    }
}

/**
 * Single shared format + insert bar hosted in the Scaffold bottom bar for all document types.
 * Renders a type-appropriate formatting branch plus a unified Insert menu. (Phase 2)
 */
@Composable
fun OfficeBottomBar(
    document: OdfDocument,
    target: FormatTarget,
    caps: DocCaps,
    viewModel: OfficeViewModel,
    actions: BottomBarActions,
    activeTableBlock: Int = -1,
    activeTableRow: Int = -1,
    activeTableCol: Int = -1
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (document) {
                is OdfDocument.TextDocument -> TextFormatControls(document, target as? FormatTarget.TextRun, viewModel, activeTableBlock, activeTableRow, activeTableCol, actions)
                is OdfDocument.Spreadsheet -> CellFormatControls(target as? FormatTarget.Cell, viewModel, actions)
                is OdfDocument.Presentation -> ElementFormatControls(target as? FormatTarget.Element, viewModel, actions)
                else -> {}
            }
            InsertControl(caps, actions)
        }
    }
}

@Composable
private fun InsertControl(caps: DocCaps, actions: BottomBarActions) {
    if (!(caps.insertImage || caps.insertShape || caps.insertChart || caps.insertTable)) return
    var menu by remember { mutableStateOf(false) }
    var shapeMenu by remember { mutableStateOf(false) }
    Box {
        FmtIcon(false, true, { IconAdd() }) { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (caps.insertImage) DropdownMenuItem(text = { Text(stringResource(R.string.image_1)) }, onClick = { menu = false; actions.onInsertImage() })
            if (caps.insertShape) DropdownMenuItem(text = { Text(stringResource(R.string.shape)) }, trailingIcon = { IconArrowDropDown() }, onClick = { shapeMenu = true })
            if (caps.insertChart) DropdownMenuItem(text = { Text(stringResource(R.string.chart_1)) }, onClick = { menu = false; actions.onInsertChart() })
            if (caps.insertTable) DropdownMenuItem(text = { Text(stringResource(R.string.table_1)) }, onClick = { menu = false; actions.onInsertTable() })
        }
        DropdownMenu(expanded = shapeMenu, onDismissRequest = { shapeMenu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rectangle)) }, onClick = { shapeMenu = false; menu = false; actions.onInsertShape(ShapeKind.RECT) })
            DropdownMenuItem(text = { Text(stringResource(R.string.ellipse)) }, onClick = { shapeMenu = false; menu = false; actions.onInsertShape(ShapeKind.ELLIPSE) })
            DropdownMenuItem(text = { Text(stringResource(R.string.line)) }, onClick = { shapeMenu = false; menu = false; actions.onInsertShape(ShapeKind.LINE) })
        }
    }
}

// --- Text document branch (ported from QuickFormatBar) ---

@Composable
private fun TextFormatControls(
    doc: OdfDocument.TextDocument,
    target: FormatTarget.TextRun?,
    viewModel: OfficeViewModel,
    activeTableBlock: Int,
    activeTableRow: Int,
    activeTableCol: Int,
    actions: BottomBarActions
) {
    val enabled = target != null
    val runStart = target?.runStart ?: -1
    val runEnd = target?.runEnd ?: -1
    val selStart = target?.selStart ?: 0
    val selEnd = target?.selEnd ?: 0
    val focusedPara = if (enabled) viewModel.runParagraphIndexAt(runStart, runEnd, selStart) else -1
    val para = (doc.content.getOrNull(focusedPara) as? OdfContentBlock.Paragraph)?.paragraph
    var styleMenu by remember { mutableStateOf(false) }
    var alignMenu by remember { mutableStateOf(false) }
    var tableMenu by remember { mutableStateOf(false) }

    val isBullet = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.BULLET
    val isNumbered = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.NUMBERED
    val isCheckbox = para?.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.CHECKBOX

    val textFormatter = TextRunFormatter(viewModel, runStart, runEnd, selStart, selEnd, enabled)

    val styleLabel = when (para?.style) {
        ParagraphStyle.HEADING1 -> "H1"; ParagraphStyle.HEADING2 -> "H2"; ParagraphStyle.HEADING3 -> "H3"; ParagraphStyle.HEADING4 -> "H4"; else -> "Normal"
    }
    val alignIcon: @Composable () -> Unit = when (para?.alignment) {
        TextAlign.Center -> { { IconFormatAlignCenter() } }
        TextAlign.End, TextAlign.Right -> { { IconFormatAlignRight() } }
        TextAlign.Justify -> { { IconFormatAlignJustify() } }
        else -> { { IconFormatAlignLeft() } }
    }
    fun setStyle(s: ParagraphStyle) { viewModel.mutateRunParagraphs(runStart, runEnd, selStart, selEnd) { it.copy(style = s) } }
    fun setAlign(a: TextAlign) { viewModel.mutateRunParagraphs(runStart, runEnd, selStart, selEnd) { it.copy(alignment = a) } }

    Box {
        TextButton(onClick = { styleMenu = true }, enabled = enabled) {
            Text(styleLabel)
            IconArrowDropDown()
        }
        DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.normal)) }, onClick = { styleMenu = false; setStyle(ParagraphStyle.BODY) })
            DropdownMenuItem(text = { Text(stringResource(R.string.heading_1)) }, onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING1) })
            DropdownMenuItem(text = { Text(stringResource(R.string.heading_2)) }, onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING2) })
            DropdownMenuItem(text = { Text(stringResource(R.string.heading_3)) }, onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING3) })
            DropdownMenuItem(text = { Text(stringResource(R.string.heading_4)) }, onClick = { styleMenu = false; setStyle(ParagraphStyle.HEADING4) })
        }
    }
    EditorBaseButtons(textFormatter)
    FmtIcon(false, enabled, { IconFormatColorText() }) { actions.onTextColor() }
    Box {
        FmtIcon(false, enabled, alignIcon) { alignMenu = true }
        DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.left)) }, leadingIcon = { IconFormatAlignLeft() }, onClick = { alignMenu = false; setAlign(TextAlign.Start) })
            DropdownMenuItem(text = { Text(stringResource(R.string.center)) }, leadingIcon = { IconFormatAlignCenter() }, onClick = { alignMenu = false; setAlign(TextAlign.Center) })
            DropdownMenuItem(text = { Text(stringResource(R.string.right)) }, leadingIcon = { IconFormatAlignRight() }, onClick = { alignMenu = false; setAlign(TextAlign.End) })
            DropdownMenuItem(text = { Text(stringResource(R.string.justify)) }, leadingIcon = { IconFormatAlignJustify() }, onClick = { alignMenu = false; setAlign(TextAlign.Justify) })
        }
    }
    FmtIcon(isBullet, enabled, { IconFormatListBulleted() }) { if (focusedPara >= 0) viewModel.toggleListItem(focusedPara) }
    FmtIcon(isNumbered, enabled, { IconFormatListNumbered() }) { if (focusedPara >= 0) viewModel.toggleNumberedList(focusedPara) }
    FmtIcon(isCheckbox, enabled, { IconCheckBox() }) { if (focusedPara >= 0) viewModel.toggleCheckbox(focusedPara) }
    FmtIcon(false, enabled, { IconFormatIndentIncrease() }) {
        if (focusedPara >= 0) {
            if (para?.style == ParagraphStyle.LIST_ITEM) viewModel.changeListLevel(focusedPara, 1)
            else viewModel.indentParagraph(focusedPara)
        }
    }
    FmtIcon(false, enabled, { IconFormatIndentDecrease() }) {
        if (focusedPara >= 0) {
            if (para?.style == ParagraphStyle.LIST_ITEM) viewModel.changeListLevel(focusedPara, -1)
            else viewModel.outdentParagraph(focusedPara)
        }
    }
    Box {
        val tableEnabled = activeTableBlock >= 0
        TextButton(onClick = { tableMenu = true }) {
            Text(stringResource(R.string.table))
            IconArrowDropDown()
        }
        DropdownMenu(expanded = tableMenu, onDismissRequest = { tableMenu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.insert_table_1)) }, onClick = { tableMenu = false; actions.onInsertTable() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.insert_row_below)) }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.textTableAddRow(activeTableBlock, activeTableRow) })
            DropdownMenuItem(text = { Text(stringResource(R.string.insert_column_right)) }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.textTableAddColumn(activeTableBlock, activeTableCol) })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete_row)) }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.textTableDeleteRow(activeTableBlock, activeTableRow) })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete_column)) }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.textTableDeleteColumn(activeTableBlock, activeTableCol) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.bold_cell)) }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.setTextTableCellSpanFormat(activeTableBlock, activeTableRow, activeTableCol) { it.copy(bold = !it.bold) } })
            DropdownMenuItem(text = { Text(stringResource(R.string.italic_cell)) }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.setTextTableCellSpanFormat(activeTableBlock, activeTableRow, activeTableCol) { it.copy(italic = !it.italic) } })
            DropdownMenuItem(text = { Text(stringResource(R.string.merge_with_right)) }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.mergeTextTableCells(activeTableBlock, activeTableRow, activeTableCol, activeTableRow, activeTableCol + 1) })
            DropdownMenuItem(text = { Text(stringResource(R.string.merge_with_below)) }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.mergeTextTableCells(activeTableBlock, activeTableRow, activeTableCol, activeTableRow + 1, activeTableCol) })
            DropdownMenuItem(text = { Text(stringResource(R.string.unmerge_cell)) }, enabled = tableEnabled, onClick = { tableMenu = false; viewModel.unmergeTextTableCells(activeTableBlock, activeTableRow, activeTableCol) })
        }
    }
    Box {
        var moreMenu by remember { mutableStateOf(false) }
        FmtIcon(false, enabled, { IconMoreVert() }) { moreMenu = true }
        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.font_size_2)) }, onClick = { moreMenu = false; actions.onFontSize() })
            DropdownMenuItem(text = { Text(stringResource(R.string.clear_formatting)) }, onClick = { moreMenu = false; viewModel.clearRunFormatting(runStart, runEnd, selStart, selEnd) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.demote_list_item)) }, enabled = focusedPara >= 0, onClick = { moreMenu = false; if (focusedPara >= 0) viewModel.changeListLevel(focusedPara, 1) })
            DropdownMenuItem(text = { Text(stringResource(R.string.promote_list_item)) }, enabled = focusedPara >= 0, onClick = { moreMenu = false; if (focusedPara >= 0) viewModel.changeListLevel(focusedPara, -1) })
            DropdownMenuItem(text = { Text(stringResource(R.string.restart_numbering)) }, enabled = focusedPara >= 0, onClick = { moreMenu = false; if (focusedPara >= 0) viewModel.restartNumbering(focusedPara) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.duplicate_paragraph)) }, enabled = focusedPara >= 0, onClick = { moreMenu = false; viewModel.duplicateParagraph(focusedPara) })
            DropdownMenuItem(text = { Text(stringResource(R.string.move_paragraph_up)) }, enabled = focusedPara > 0, onClick = { moreMenu = false; viewModel.moveParagraphUp(focusedPara) })
            DropdownMenuItem(text = { Text(stringResource(R.string.move_paragraph_down)) }, enabled = focusedPara >= 0, onClick = { moreMenu = false; viewModel.moveParagraphDown(focusedPara) })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete_paragraph), color = MaterialTheme.colorScheme.error) }, enabled = focusedPara >= 0, onClick = { moreMenu = false; viewModel.deleteParagraph(focusedPara) })
        }
    }
}

// --- Spreadsheet cell branch ---

@Composable
private fun CellFormatControls(target: FormatTarget.Cell?, viewModel: OfficeViewModel, actions: BottomBarActions) {
    val enabled = target != null
    val s = target?.sheet ?: -1
    val r = target?.row ?: -1
    val c = target?.col ?: -1
    var alignMenu by remember { mutableStateOf(false) }
    EditorBaseButtons(CellFormatter(viewModel, s, r, c, enabled))
    FmtIcon(false, enabled, { IconFormatColorText() }) { actions.onCellTextColor() }
    TextButton(onClick = { actions.onCellBgColor() }, enabled = enabled) { Text(stringResource(R.string.fill)) }
    Box {
        FmtIcon(false, enabled, { IconFormatAlignLeft() }) { alignMenu = true }
        DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.left)) }, leadingIcon = { IconFormatAlignLeft() }, onClick = { alignMenu = false; if (enabled) viewModel.setCellAlignment(s, r, c, TextAlign.Start) })
            DropdownMenuItem(text = { Text(stringResource(R.string.center)) }, leadingIcon = { IconFormatAlignCenter() }, onClick = { alignMenu = false; if (enabled) viewModel.setCellAlignment(s, r, c, TextAlign.Center) })
            DropdownMenuItem(text = { Text(stringResource(R.string.right)) }, leadingIcon = { IconFormatAlignRight() }, onClick = { alignMenu = false; if (enabled) viewModel.setCellAlignment(s, r, c, TextAlign.End) })
        }
    }
    TextButton(onClick = { if (enabled) viewModel.unmergeCells(s, r, c) }, enabled = enabled) { Text(stringResource(R.string.unmerge)) }
    Box {
        var moreMenu by remember { mutableStateOf(false) }
        var numMenu by remember { mutableStateOf(false) }
        FmtIcon(false, enabled, { IconMoreVert() }) { moreMenu = true }
        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.fill_down)) }, enabled = enabled, onClick = { moreMenu = false; if (enabled) viewModel.fillDownToEnd(s, r, c) })
            DropdownMenuItem(text = { Text(stringResource(R.string.border_color)) }, enabled = enabled, onClick = { moreMenu = false; actions.onCellBorder() })
            DropdownMenuItem(text = { Text(stringResource(R.string.comment_1)) }, enabled = enabled, onClick = { moreMenu = false; actions.onCellComment() })
            DropdownMenuItem(text = { Text(stringResource(R.string.row_column_size)) }, enabled = enabled, onClick = { moreMenu = false; actions.onCellResize() })
            DropdownMenuItem(text = { Text(stringResource(R.string.number_format)) }, trailingIcon = { IconArrowDropDown() }, enabled = enabled, onClick = { numMenu = true })
        }
        DropdownMenu(expanded = numMenu, onDismissRequest = { numMenu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.general)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, null) })
            DropdownMenuItem(text = { Text(stringResource(R.string.number_2_dp)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 2, grouping = true)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.integer)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 0)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.percent)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 0, percent = true)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.currency)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 2, currencySymbol = "$", grouping = true)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.date)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(isDate = true)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.time)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(isTime = true)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.scientific)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(decimals = 2, isScientific = true)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.fraction)) }, onClick = { numMenu = false; moreMenu = false; if (enabled) viewModel.setCellNumberFormat(s, r, c, OdfNumberFormat(isFraction = true, fractionDenominatorDigits = 2)) })
        }
    }
}

// --- EditorFormatter adapters: map the shared base buttons onto office actions ---

/** Inline character formatting for a text-document run selection. */
private class TextRunFormatter(
    private val viewModel: OfficeViewModel,
    private val runStart: Int,
    private val runEnd: Int,
    private val selStart: Int,
    private val selEnd: Int,
    override val enabled: Boolean,
) : EditorFormatter {
    override val supported = setOf(
        EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.UNDERLINE, EditorFormat.STRIKETHROUGH,
        EditorFormat.LINK,
    )

    override fun isActive(format: EditorFormat): Boolean = enabled && when (format) {
        EditorFormat.BOLD -> viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.bold }
        EditorFormat.ITALIC -> viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.italic }
        EditorFormat.UNDERLINE -> viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.underline }
        EditorFormat.STRIKETHROUGH -> viewModel.runRangeHasFormat(runStart, runEnd, selStart, selEnd) { it.strikethrough }
        else -> false
    }

    override fun toggle(format: EditorFormat) {
        if (!enabled) return
        when (format) {
            EditorFormat.BOLD -> { val t = !isActive(EditorFormat.BOLD); viewModel.applyRunSpanStyle(runStart, runEnd, selStart, selEnd) { it.copy(bold = t) } }
            EditorFormat.ITALIC -> { val t = !isActive(EditorFormat.ITALIC); viewModel.applyRunSpanStyle(runStart, runEnd, selStart, selEnd) { it.copy(italic = t) } }
            EditorFormat.UNDERLINE -> { val t = !isActive(EditorFormat.UNDERLINE); viewModel.applyRunSpanStyle(runStart, runEnd, selStart, selEnd) { it.copy(underline = t) } }
            EditorFormat.STRIKETHROUGH -> { val t = !isActive(EditorFormat.STRIKETHROUGH); viewModel.applyRunSpanStyle(runStart, runEnd, selStart, selEnd) { it.copy(strikethrough = t) } }
            else -> {}
        }
    }

    override fun linkContext(): LinkContext? {
        if (!enabled) return null
        val link = viewModel.linkAt(runStart, runEnd, selStart)
        if (link != null) return LinkContext(editing = true, text = link.text, url = link.url)
        if (selStart != selEnd) return LinkContext(editing = false, text = viewModel.runSelectedText(runStart, runEnd, selStart, selEnd), url = "")
        return null
    }

    override fun applyLink(context: LinkContext, text: String, url: String) {
        if (!enabled || url.isBlank()) return
        val link = viewModel.linkAt(runStart, runEnd, selStart)
        val gs: Int; val ge: Int
        if (link != null) { gs = link.gStart; ge = link.gEnd }
        else if (selStart != selEnd) { gs = selStart; ge = selEnd }
        else return
        viewModel.setLink(runStart, runEnd, gs, ge, text.ifBlank { url }, url)
    }

    override fun removeLink(context: LinkContext) {
        val link = viewModel.linkAt(runStart, runEnd, selStart) ?: return
        viewModel.removeLinkInRun(runStart, runEnd, link.gStart, link.gEnd)
    }
}

/** Inline character formatting for a spreadsheet cell. */
private class CellFormatter(
    private val viewModel: OfficeViewModel,
    private val sheet: Int,
    private val row: Int,
    private val col: Int,
    override val enabled: Boolean,
) : EditorFormatter {
    override val supported = setOf(EditorFormat.BOLD, EditorFormat.ITALIC)
    override fun toggle(format: EditorFormat) {
        if (!enabled) return
        when (format) {
            EditorFormat.BOLD -> viewModel.setCellBold(sheet, row, col)
            EditorFormat.ITALIC -> viewModel.setCellItalic(sheet, row, col)
            else -> {}
        }
    }
}

/** Inline character formatting for a presentation slide element. */
private class SlideFormatter(
    private val viewModel: OfficeViewModel,
    private val slide: Int,
    private val element: Int,
    override val enabled: Boolean,
) : EditorFormatter {
    override val supported = setOf(EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.UNDERLINE)
    override fun toggle(format: EditorFormat) {
        if (!enabled) return
        when (format) {
            EditorFormat.BOLD -> viewModel.toggleSlideElementBold(slide, element)
            EditorFormat.ITALIC -> viewModel.toggleSlideElementItalic(slide, element)
            EditorFormat.UNDERLINE -> viewModel.toggleSlideElementUnderline(slide, element)
            else -> {}
        }
    }
}

@Composable
private fun ElementFormatControls(target: FormatTarget.Element?, viewModel: OfficeViewModel, actions: BottomBarActions) {
    val enabled = target != null
    val s = target?.slide ?: -1
    val e = target?.element ?: -1
    var alignMenu by remember { mutableStateOf(false) }
    EditorBaseButtons(SlideFormatter(viewModel, s, e, enabled))
    FmtIcon(false, enabled, { IconFormatColorText() }) { actions.onSlideTextColor() }
    TextButton(onClick = { actions.onSlideFill() }, enabled = enabled) { Text(stringResource(R.string.fill)) }
    TextButton(onClick = { actions.onSlideStroke() }, enabled = enabled) { Text(stringResource(R.string.border)) }
    Box {
        FmtIcon(false, enabled, { IconFormatAlignLeft() }) { alignMenu = true }
        DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.left)) }, leadingIcon = { IconFormatAlignLeft() }, onClick = { alignMenu = false; if (enabled) viewModel.setSlideElementAlignment(s, e, TextAlign.Start) })
            DropdownMenuItem(text = { Text(stringResource(R.string.center)) }, leadingIcon = { IconFormatAlignCenter() }, onClick = { alignMenu = false; if (enabled) viewModel.setSlideElementAlignment(s, e, TextAlign.Center) })
            DropdownMenuItem(text = { Text(stringResource(R.string.right)) }, leadingIcon = { IconFormatAlignRight() }, onClick = { alignMenu = false; if (enabled) viewModel.setSlideElementAlignment(s, e, TextAlign.End) })
        }
    }
    FmtIcon(false, enabled, { IconDelete() }) { if (enabled) actions.onDeleteElement() }
    Box {
        var moreMenu by remember { mutableStateOf(false) }
        FmtIcon(false, enabled, { IconMoreVert() }) { moreMenu = true }
        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, enabled = enabled, onClick = { moreMenu = false; if (enabled) viewModel.duplicateSlideElement(s, e) })
            DropdownMenuItem(text = { Text(stringResource(R.string.bring_to_front)) }, enabled = enabled, onClick = { moreMenu = false; if (enabled) viewModel.reorderSlideElement(s, e, true) })
            DropdownMenuItem(text = { Text(stringResource(R.string.send_to_back)) }, enabled = enabled, onClick = { moreMenu = false; if (enabled) viewModel.reorderSlideElement(s, e, false) })
            DropdownMenuItem(text = { Text(stringResource(R.string.rotate_90)) }, enabled = enabled, onClick = { moreMenu = false; if (enabled) viewModel.setSlideElementRotation(s, e, 90f) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.speaker_notes_1)) }, onClick = { moreMenu = false; actions.onSlideNotes() })
            DropdownMenuItem(text = { Text(stringResource(R.string.slide_background)) }, onClick = { moreMenu = false; actions.onSlideBackground() })
            DropdownMenuItem(text = { Text(stringResource(R.string.slide_transition)) }, onClick = { moreMenu = false; actions.onSlideTransition() })
        }
    }
}
