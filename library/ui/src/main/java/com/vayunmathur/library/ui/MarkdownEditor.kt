package com.vayunmathur.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.IntegrationInstructions
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.parseMarkdown
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.R

/**
 * A reusable Markdown body field: a [BasicTextField] that renders the markdown
 * live (bold shows bold, headings grow, …) via the shared [parseMarkdown]. The
 * caller owns the raw markdown via [value]/[onValueChange]; styled rendering is
 * derived for display only so the cursor stays aligned with the text.
 *
 * Pair it with a [MarkdownFormatToolbar] (typically in a Scaffold bottomBar,
 * shown only while [onFocusChanged] reports focus). The formatting logic is
 * shared so the notes, contacts, calendar and email editors never diverge. For
 * HTML consumers, convert with [markdownToHtml].
 */
@Composable
fun MarkdownEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val styled = value.copy(
        annotatedString = parseMarkdown(
            value.text,
            showMarkers = false,
            process = false,
            softWrap = false,
        )
    )
    BasicTextField(
        value = styled,
        onValueChange = { nv ->
            if (nv.text == value.text && nv.selection.collapsed) {
                tryToggleCheckbox(nv.selection.start, value)?.let { onValueChange(it); return@BasicTextField }
            }
            onValueChange(nv)
        },
        modifier = modifier.fillMaxWidth().onFocusChanged { onFocusChanged(it.isFocused) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
        cursorBrush = SolidColor(LocalContentColor.current),
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                    )
                }
                inner()
            }
        },
    )
}

/** [EditorFormatter] over a markdown [TextFieldValue]. */
class MarkdownFormatter(
    private val value: TextFieldValue,
    private val onValueChange: (TextFieldValue) -> Unit,
) : EditorFormatter {
    override val supported = setOf(
        EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.STRIKETHROUGH,
        EditorFormat.BULLET, EditorFormat.LINK,
    )

    override fun isActive(format: EditorFormat): Boolean = when (format) {
        EditorFormat.BOLD -> isInlineFormatActive(value.text, value.selection, "**")
        EditorFormat.ITALIC -> isInlineFormatActive(value.text, value.selection, "*")
        EditorFormat.STRIKETHROUGH -> isInlineFormatActive(value.text, value.selection, "~~")
        EditorFormat.BULLET -> isLinePrefixActive(value.text, value.selection, "- ")
        else -> false
    }

    override fun toggle(format: EditorFormat) {
        val nv = when (format) {
            EditorFormat.BOLD -> toggleInlineFormat(value, "**")
            EditorFormat.ITALIC -> toggleInlineFormat(value, "*")
            EditorFormat.STRIKETHROUGH -> toggleInlineFormat(value, "~~")
            EditorFormat.BULLET -> toggleLinePrefix(value, "- ")
            else -> return
        }
        onValueChange(nv)
    }

    override fun linkContext(): LinkContext? = markdownLinkContext(value)

    override fun applyLink(context: LinkContext, text: String, url: String) =
        onValueChange(applyMarkdownLink(value, context, text, url))
}

/**
 * Shared bottom formatting toolbar driving a markdown [TextFieldValue]. Renders
 * the mandated base buttons via [EditorBaseButtons] plus markdown-specific extras
 * (inline code, headings, quote, numbered list, checkbox, code block, rule).
 */
@Composable
fun MarkdownFormatToolbar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = MarkdownFormatter(value, onValueChange)
    var showHeadingMenu by remember { mutableStateOf(false) }

    fun apply(transform: (TextFieldValue) -> TextFieldValue) = onValueChange(transform(value))

    EditorBottomBar(modifier, scrollable = true) {
        EditorBaseButtons(formatter)

        FormatIconButton(
            Icons.Filled.Code, "Inline code",
            active = isInlineFormatActive(value.text, value.selection, "`"),
        ) { apply { toggleInlineFormat(it, "`") } }

        Box {
            FormatIconButton(
                Icons.Filled.Title, "Heading",
                active = getActiveHeadingLevel(value.text, value.selection.start) != null,
            ) { showHeadingMenu = true }
            DropdownMenu(expanded = showHeadingMenu, onDismissRequest = { showHeadingMenu = false }) {
                (1..3).forEach { level ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.heading, level)) }, onClick = {
                        apply { insertHeading(it, level) }
                        showHeadingMenu = false
                    })
                }
            }
        }

        FormatIconButton(
            Icons.Filled.FormatQuote, "Quote",
            active = isLinePrefixActive(value.text, value.selection, "> "),
        ) { apply { toggleLinePrefix(it, "> ") } }

        FormatIconButton(
            Icons.Filled.FormatListNumbered, "Numbered list",
            active = isLinePrefixActive(value.text, value.selection, "1. "),
        ) { apply { toggleLinePrefix(it, "1. ") } }

        FormatIconButton(
            Icons.Filled.CheckBox, "Checkbox",
            active = isLinePrefixActive(value.text, value.selection, "- [ ] "),
        ) { apply { toggleLinePrefix(it, "- [ ] ") } }

        FormatIconButton(Icons.Filled.IntegrationInstructions, "Code block") { apply { insertCodeBlock(it) } }
        FormatIconButton(Icons.Filled.HorizontalRule, "Horizontal rule") { apply { insertHorizontalRule(it) } }
    }
}

// ---------------------------------------------------------------------------
// Markdown editing helpers (shared by the notes and email editors).
// ---------------------------------------------------------------------------

private fun isCheckboxPrefix(line: String) =
    line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ")

private fun stripLinePrefix(line: String): String {
    Regex("^#{1,6} ").find(line)?.let { return line.substring(it.value.length) }
    if (isCheckboxPrefix(line)) return line.substring(6)
    if (line.startsWith("- ")) return line.substring(2)
    Regex("^\\d+\\. ").find(line)?.let { return line.substring(it.value.length) }
    if (line.startsWith("> ")) return line.substring(2)
    return line
}

private fun getLinePrefix(line: String): String {
    Regex("^#{1,6} ").find(line)?.let { return it.value }
    if (isCheckboxPrefix(line)) return line.substring(0, 6)
    if (line.startsWith("- ")) return "- "
    Regex("^\\d+\\. ").find(line)?.let { return it.value }
    if (line.startsWith("> ")) return "> "
    return ""
}

private fun getSelectedLines(text: String, selection: TextRange): Pair<Int, Int> {
    val start = minOf(selection.start, selection.end).coerceIn(0, text.length)
    val end = maxOf(selection.start, selection.end).coerceIn(0, text.length)
    val blockStart = text.lastIndexOf('\n', start - 1) + 1
    val effectiveEnd = if (start != end && end > 0 && text.getOrNull(end - 1) == '\n') end - 1 else end
    val blockEnd = text.indexOf('\n', effectiveEnd).let { if (it == -1) text.length else it }
    return blockStart to blockEnd.coerceAtLeast(blockStart)
}

private fun matchesPrefix(line: String, prefix: String): Boolean = when {
    prefix == "1. " -> Regex("^\\d+\\. ").containsMatchIn(line)
    prefix == "- [ ] " -> isCheckboxPrefix(line)
    prefix == "- " -> line.startsWith("- ") && !isCheckboxPrefix(line)
    else -> line.startsWith(prefix)
}

private fun computeBlockSelection(
    text: String,
    selection: TextRange,
    blockStart: Int,
    lines: List<String>,
    newLines: List<String>,
    newBlockText: String,
): TextRange = if (selection.collapsed) {
    val lineIndex = text.substring(blockStart, selection.start).count { it == '\n' }
    val diff = newLines[lineIndex].length - lines[lineIndex].length
    TextRange((selection.start + diff).coerceAtLeast(blockStart))
} else {
    TextRange(blockStart, blockStart + newBlockText.length)
}

private fun hasInlineMarker(content: String, marker: String): Boolean {
    if (content.length < marker.length * 2) return false
    if (!content.startsWith(marker) || !content.endsWith(marker)) return false
    if (marker == "*" && content.startsWith("**") && !content.startsWith("***")) return false
    if (marker == "*" && content.endsWith("**") && !content.endsWith("***")) return false
    return true
}

fun toggleInlineFormat(value: TextFieldValue, marker: String): TextFieldValue {
    val text = value.text
    val sel = value.selection
    val start = minOf(sel.start, sel.end)
    val end = maxOf(sel.start, sel.end)
    if (start == end) {
        // No selection: drop an empty pair and place the cursor between them.
        val newText = text.substring(0, start) + marker + marker + text.substring(start)
        return value.copy(text = newText, selection = TextRange(start + marker.length))
    }

    val selected = text.substring(start, end)

    // The markers are part of the selection itself.
    if (hasInlineMarker(selected, marker)) {
        val unwrapped = selected.substring(marker.length, selected.length - marker.length)
        val newText = text.substring(0, start) + unwrapped + text.substring(end)
        return value.copy(text = newText, selection = TextRange(start, start + unwrapped.length))
    }

    // The markers sit just outside the selection (greedy, delimiter-run aware).
    if (emphasisActive(text, start, end, marker)) {
        val newText = text.substring(0, start - marker.length) + selected + text.substring(end + marker.length)
        return value.copy(text = newText, selection = TextRange(start - marker.length, start - marker.length + selected.length))
    }

    // Otherwise wrap exactly the selected characters (e.g. a single word), not the line.
    val newText = text.substring(0, start) + marker + selected + marker + text.substring(end)
    return value.copy(text = newText, selection = TextRange(start + marker.length, start + marker.length + selected.length))
}

fun toggleLinePrefix(value: TextFieldValue, prefix: String): TextFieldValue {
    val text = value.text
    val selection = value.selection
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    val lines = text.substring(blockStart, blockEnd).split("\n")

    val allHavePrefix = lines.all { matchesPrefix(it, prefix) }

    val newLines = if (allHavePrefix) {
        lines.map { stripLinePrefix(it) }
    } else {
        lines.mapIndexed { index, line ->
            val stripped = stripLinePrefix(line)
            if (prefix == "1. ") "${index + 1}. $stripped" else prefix + stripped
        }
    }

    val newBlockText = newLines.joinToString("\n")
    val newText = text.substring(0, blockStart) + newBlockText + text.substring(blockEnd)
    return value.copy(text = newText, selection = computeBlockSelection(text, selection, blockStart, lines, newLines, newBlockText))
}

fun insertHeading(value: TextFieldValue, level: Int): TextFieldValue {
    val text = value.text
    val selection = value.selection
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    val lines = text.substring(blockStart, blockEnd).split("\n")
    val targetPrefix = "#".repeat(level) + " "

    val allHaveHeading = lines.all { Regex("^#{1,6} ").find(it)?.value == targetPrefix }

    val newLines = if (allHaveHeading) {
        lines.map { stripLinePrefix(it) }
    } else {
        lines.map { targetPrefix + stripLinePrefix(it) }
    }

    val newBlockText = newLines.joinToString("\n")
    val newText = text.substring(0, blockStart) + newBlockText + text.substring(blockEnd)
    return value.copy(text = newText, selection = computeBlockSelection(text, selection, blockStart, lines, newLines, newBlockText))
}

fun isInlineFormatActive(text: String, selection: TextRange, marker: String): Boolean {
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    if (start == end) return false
    val selected = text.substring(start, end)
    return hasInlineMarker(selected, marker) || emphasisActive(text, start, end, marker)
}

/** Length of the run of [ch] immediately to the left of [pos]. */
private fun runLengthLeft(text: String, pos: Int, ch: Char): Int {
    var i = pos; var n = 0
    while (i > 0 && text[i - 1] == ch) { n++; i-- }
    return n
}

/** Length of the run of [ch] immediately to the right of [pos]. */
private fun runLengthRight(text: String, pos: Int, ch: Char): Int {
    var i = pos; var n = 0
    while (i < text.length && text[i] == ch) { n++; i++ }
    return n
}

/**
 * Whether [marker] emphasis surrounds the selection, using greedy CommonMark
 * delimiter-run semantics. For asterisks a run is consumed as bold pairs (`**`)
 * with any leftover single asterisk being italic (`*`), so `**`=bold, `*`=italic
 * and `***`=both with no ambiguity. Other markers (`~~`, `` ` ``) just need a
 * full run on each side.
 */
private fun emphasisActive(text: String, start: Int, end: Int, marker: String): Boolean {
    val ch = marker[0]
    val left = runLengthLeft(text, start, ch)
    val right = runLengthRight(text, end, ch)
    return if (ch == '*') {
        if (marker == "*") (left % 2 == 1) && (right % 2 == 1) // italic = leftover single
        else left >= 2 && right >= 2                            // bold = a pair available
    } else {
        left >= marker.length && right >= marker.length
    }
}

fun isLinePrefixActive(text: String, selection: TextRange, prefix: String): Boolean {
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    return text.substring(blockStart, blockEnd).split("\n").all { matchesPrefix(it, prefix) }
}

fun getActiveHeadingLevel(text: String, cursorPos: Int): Int? {
    val lineStart = text.lastIndexOf('\n', (cursorPos - 1).coerceAtLeast(0)) + 1
    val lineEnd = text.indexOf('\n', cursorPos).let { if (it == -1) text.length else it }
    val match = Regex("^(#{1,6}) ").find(text.substring(lineStart, lineEnd)) ?: return null
    return match.groupValues[1].length
}

fun insertCodeBlock(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val selection = value.selection
    if (selection.collapsed) {
        val insert = "```\n\n```"
        val newText = text.substring(0, selection.start) + insert + text.substring(selection.start)
        return value.copy(text = newText, selection = TextRange(selection.start + 4))
    }
    val selectedText = text.substring(selection.start, selection.end)
    val newText = text.substring(0, selection.start) + "```\n" + selectedText + "\n```" + text.substring(selection.end)
    return value.copy(text = newText, selection = TextRange(selection.start + 4, selection.start + 4 + selectedText.length))
}

fun insertHorizontalRule(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val cursor = value.selection.start
    val insert = "\n---\n"
    val newText = text.substring(0, cursor) + insert + text.substring(cursor)
    return value.copy(text = newText, selection = TextRange(cursor + insert.length))
}

private val markdownLinkRegex = Regex("\\[([^\\]]*)]\\(([^)]*)\\)")

/** An existing `[text](url)` link spanning [range] in the document. */
data class MarkdownLinkMatch(val range: IntRange, val text: String, val url: String)

/** The markdown link whose `[text](url)` span contains [cursor], or null. */
fun findMarkdownLinkAt(text: String, cursor: Int): MarkdownLinkMatch? {
    for (m in markdownLinkRegex.findAll(text)) {
        if (cursor >= m.range.first && cursor <= m.range.last + 1) {
            return MarkdownLinkMatch(m.range.first..m.range.last, m.groupValues[1], m.groupValues[2])
        }
    }
    return null
}

/** Link-button state for a markdown [value]; null means the button is disabled. */
fun markdownLinkContext(value: TextFieldValue): LinkContext? {
    val sel = value.selection
    val existing = if (sel.collapsed) findMarkdownLinkAt(value.text, sel.start) else null
    return when {
        existing != null -> LinkContext(editing = true, text = existing.text, url = existing.url)
        !sel.collapsed -> LinkContext(
            editing = false,
            text = value.text.substring(minOf(sel.start, sel.end), maxOf(sel.start, sel.end)),
            url = "",
        )
        else -> null
    }
}

/** Create or edit a markdown link given a [context] (from [markdownLinkContext]). */
fun applyMarkdownLink(value: TextFieldValue, context: LinkContext, text: String, url: String): TextFieldValue {
    val replacement = "[$text]($url)"
    return if (context.editing) {
        val m = findMarkdownLinkAt(value.text, value.selection.start) ?: return value
        val newText = value.text.substring(0, m.range.first) + replacement + value.text.substring(m.range.last + 1)
        value.copy(text = newText, selection = TextRange(m.range.first + replacement.length))
    } else {
        val start = minOf(value.selection.start, value.selection.end)
        val end = maxOf(value.selection.start, value.selection.end)
        val newText = value.text.substring(0, start) + replacement + value.text.substring(end)
        value.copy(text = newText, selection = TextRange(start + replacement.length))
    }
}

private val checkboxPattern = Regex("^(\\s*- )\\[([ xX])] ")

fun tryToggleCheckbox(offset: Int, value: TextFieldValue): TextFieldValue? {
    val text = value.text
    val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
    val lineEnd = text.indexOf('\n', offset).let { if (it == -1) text.length else it }
    val match = checkboxPattern.find(text.substring(lineStart, lineEnd)) ?: return null
    val bracketOffset = lineStart + match.groups[1]!!.value.length
    if (offset > bracketOffset + 3) return null
    val newChar = if (match.groups[2]!!.value.lowercase() == "x") " " else "x"
    val newText = text.substring(0, bracketOffset + 1) + newChar + text.substring(bracketOffset + 2)
    return value.copy(text = newText, selection = TextRange(offset))
}

fun findCheckboxPositions(text: String): List<Pair<Int, Boolean>> =
    Regex("(?m)^(\\s*- )\\[([ xX])] ").findAll(text).map { match ->
        val bracketOffset = match.groups[1]!!.range.last + 1
        val isChecked = match.groups[2]!!.value.lowercase() == "x"
        bracketOffset to isChecked
    }.toList()

// ---------------------------------------------------------------------------
// Markdown -> HTML conversion (for HTML consumers such as email).
// ---------------------------------------------------------------------------

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private val htmlLinkRegex = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
private val htmlBoldStarRegex = Regex("\\*\\*(.+?)\\*\\*")
private val htmlBoldUnderRegex = Regex("__(.+?)__")
private val htmlStrikeRegex = Regex("~~(.+?)~~")
private val htmlItalicStarRegex = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")
private val htmlItalicUnderRegex = Regex("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)")
private val htmlInlineCodeRegex = Regex("`([^`]+)`")

private fun inlineMarkdownToHtml(s: String): String {
    var t = escapeHtml(s)
    t = htmlLinkRegex.replace(t) { m -> "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>" }
    t = htmlBoldStarRegex.replace(t) { "<b>${it.groupValues[1]}</b>" }
    t = htmlBoldUnderRegex.replace(t) { "<b>${it.groupValues[1]}</b>" }
    t = htmlStrikeRegex.replace(t) { "<s>${it.groupValues[1]}</s>" }
    t = htmlItalicStarRegex.replace(t) { "<i>${it.groupValues[1]}</i>" }
    t = htmlItalicUnderRegex.replace(t) { "<i>${it.groupValues[1]}</i>" }
    t = htmlInlineCodeRegex.replace(t) { "<code>${it.groupValues[1]}</code>" }
    return t
}

/**
 * Converts the markdown produced by [MarkdownEditor] into HTML suitable for an
 * email body. Handles headings, bold/italic/strikethrough/inline-code, links,
 * bullet/numbered lists, checkboxes, blockquotes, fenced code blocks and rules.
 */
fun markdownToHtml(md: String): String {
    val lines = md.split("\n")
    val sb = StringBuilder()
    var inUl = false
    var inOl = false

    fun closeLists() {
        if (inUl) { sb.append("</ul>"); inUl = false }
        if (inOl) { sb.append("</ol>"); inOl = false }
    }

    val headingRe = Regex("^(#{1,6})\\s+(.*)$")
    val checkboxRe = Regex("^\\s*- \\[([ xX])]\\s+(.*)$")
    val bulletRe = Regex("^\\s*[-*+]\\s+(.*)$")
    val numberedRe = Regex("^\\s*\\d+[.)]\\s+(.*)$")
    val quoteRe = Regex("^>\\s?(.*)$")

    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        val trimmed = line.trimStart()

        val heading = headingRe.matchEntire(line)
        val checkbox = checkboxRe.matchEntire(line)
        val bullet = if (checkbox == null) bulletRe.matchEntire(line) else null
        val numbered = numberedRe.matchEntire(line)
        val quote = quoteRe.matchEntire(line)

        when {
            trimmed.startsWith("```") -> {
                closeLists()
                sb.append("<pre><code>")
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    sb.append(escapeHtml(lines[i])).append("\n")
                    i++
                }
                sb.append("</code></pre>")
                i++ // skip closing fence (if present)
            }
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                closeLists(); sb.append("<hr>"); i++
            }
            heading != null -> {
                closeLists()
                val level = heading.groupValues[1].length
                sb.append("<h$level>").append(inlineMarkdownToHtml(heading.groupValues[2])).append("</h$level>")
                i++
            }
            checkbox != null -> {
                if (inOl) { sb.append("</ol>"); inOl = false }
                if (!inUl) { sb.append("<ul>"); inUl = true }
                val checked = checkbox.groupValues[1].lowercase() == "x"
                sb.append("<li><input type=\"checkbox\" disabled")
                if (checked) sb.append(" checked")
                sb.append("> ").append(inlineMarkdownToHtml(checkbox.groupValues[2])).append("</li>")
                i++
            }
            bullet != null -> {
                if (inOl) { sb.append("</ol>"); inOl = false }
                if (!inUl) { sb.append("<ul>"); inUl = true }
                sb.append("<li>").append(inlineMarkdownToHtml(bullet.groupValues[1])).append("</li>")
                i++
            }
            numbered != null -> {
                if (inUl) { sb.append("</ul>"); inUl = false }
                if (!inOl) { sb.append("<ol>"); inOl = true }
                sb.append("<li>").append(inlineMarkdownToHtml(numbered.groupValues[1])).append("</li>")
                i++
            }
            quote != null -> {
                closeLists()
                sb.append("<blockquote>").append(inlineMarkdownToHtml(quote.groupValues[1])).append("</blockquote>")
                i++
            }
            line.isBlank() -> {
                closeLists(); sb.append("<br>"); i++
            }
            else -> {
                closeLists()
                sb.append("<p>").append(inlineMarkdownToHtml(line)).append("</p>")
                i++
            }
        }
    }
    closeLists()
    return "<html><body>$sb</body></html>"
}
