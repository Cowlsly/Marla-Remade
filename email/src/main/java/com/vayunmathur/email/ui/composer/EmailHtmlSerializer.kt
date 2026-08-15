package com.vayunmathur.email.composer

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.core.text.htmlEncode
import com.vayunmathur.library.ui.EmailAlignmentSpan
import com.vayunmathur.library.ui.EmailBlockQuoteSpan
import com.vayunmathur.library.ui.FontFamilySpan
import com.vayunmathur.library.ui.HeadingSpan
import com.vayunmathur.library.ui.HrSpan
import com.vayunmathur.library.ui.IndentSpan
import com.vayunmathur.library.ui.InlineCodeSpan
import com.vayunmathur.library.ui.OrderedListSpan
import kotlin.math.min

/**
 * Serialize a Spanned (from HtmlEditor) to HTML, emitting <img src="cid:...">
 * for [CidImageSpan] and preserving bold/italic/underline/strike/links/lists/indent
 * plus rich formatting: headings, alignment, blockquote, hr, colors, font size/family, inline code.
 */

fun serializeEmailHtml(spanned: Spanned): String {
    if (spanned.isEmpty()) return ""
    val len = spanned.length
    val out = StringBuilder()

    data class Para(val start: Int, val end: Int)

    fun paras(): List<Para> {
        val list = mutableListOf<Para>()
        var pos = 0
        val text = spanned.toString()
        while (pos < len) {
            val nl = text.indexOf('\n', pos).let { if (it < 0) len else it }
            list.add(Para(pos, nl))
            pos = nl + 1
        }
        if (list.isEmpty() && len == 0) return emptyList()
        return list
    }

    val allParas = paras()
    var currentList: String? = null
    var orderedCounter = 0

    fun closeList() {
        if (currentList != null) {
            out.append("</${currentList}>")
            currentList = null
            orderedCounter = 0
        }
    }

    fun hasBullet(p: Para): Boolean {
        return spanned.getSpans(p.start, p.end, BulletSpan::class.java).any {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }
    }

    fun orderedSpan(p: Para): OrderedListSpan? {
        return spanned.getSpans(p.start, p.end, OrderedListSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }
    }

    fun indentLevel(p: Para): Int {
        return spanned.getSpans(p.start, p.end, IndentSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }?.level ?: 0
    }

    fun headingLevel(p: Para): Int? {
        return spanned.getSpans(p.start, p.end, HeadingSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }?.level
    }

    fun alignment(p: Para): String? {
        return spanned.getSpans(p.start, p.end, EmailAlignmentSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }?.alignmentCss
    }

    fun isBlockquote(p: Para): Boolean {
        return spanned.getSpans(p.start, p.end, EmailBlockQuoteSpan::class.java).any {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }
    }

    fun isHr(p: Para): Boolean {
        return spanned.getSpans(p.start, p.end, HrSpan::class.java).any {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }
    }

    fun inlineHtmlFor(p: Para): String {
        if (p.start >= p.end) return ""
        return buildInlineHtml(spanned, p.start, p.end)
    }

    for (para in allParas) {
        // Horizontal rule takes precedence
        if (isHr(para)) {
            closeList()
            out.append("<hr>")
            continue
        }

        if (para.start >= para.end) {
            closeList()
            val lvl = indentLevel(para)
            if (lvl > 0) out.append("<div style=\"margin-left: ${lvl * 24}px\"><br></div>")
            else out.append("<br>")
            continue
        }

        val bullet = hasBullet(para)
        val oSpan = orderedSpan(para)
        val indentLvl = indentLevel(para)
        val hLevel = headingLevel(para)
        val align = alignment(para)
        val blockquote = isBlockquote(para)

        val rawInline = inlineHtmlFor(para)
        val inner = if (rawInline.isBlank()) "<br>" else rawInline

        if (bullet || oSpan != null) {
            // List item – embed heading/blockquote/alignment inside <li> if present for minimal email-safe output
            val liInner: String = when {
                hLevel != null -> {
                    val alignPart = if (align != null && align != "left") "text-align:$align;" else ""
                    "<h$hLevel style=\"margin:0;${alignPart}\">$inner</h$hLevel>"
                }
                blockquote -> {
                    val alignPart = if (align != null && align != "left") ";text-align:$align" else ""
                    "<blockquote style=\"border-left:2px solid #ccc;margin:0 0 0 8px;padding-left:8px$alignPart\">$inner</blockquote>"
                }
                align != null && align != "left" -> "<div style=\"text-align:$align\">$inner</div>"
                else -> inner
            }
            val withIndent = if (indentLvl > 0) "<div style=\"margin-left: ${indentLvl * 24}px\">$liInner</div>" else liInner

            when {
                bullet -> {
                    if (currentList != "ul") {
                        closeList()
                        out.append("<ul>")
                        currentList = "ul"
                    }
                    out.append("<li>$withIndent</li>")
                }
                else -> {
                    if (currentList != "ol") {
                        closeList()
                        out.append("<ol>")
                        currentList = "ol"
                        orderedCounter = 0
                    }
                    orderedCounter++
                    out.append("<li>$withIndent</li>")
                }
            }
        } else {
            closeList()
            when {
                hLevel != null -> {
                    val styles = mutableListOf<String>()
                    styles.add("margin:0.5em 0")
                    if (align != null && align != "left") styles.add("text-align:$align")
                    if (indentLvl > 0) styles.add("margin-left:${indentLvl * 24}px")
                    out.append("<h$hLevel style=\"${styles.joinToString(";")}\">$inner</h$hLevel>")
                }
                blockquote -> {
                    val styles = mutableListOf<String>()
                    styles.add("border-left:2px solid #ccc")
                    styles.add("margin:0 0 0 8px")
                    styles.add("padding-left:8px")
                    if (indentLvl > 0) styles.add("margin-left:${indentLvl * 24}px")
                    if (align != null && align != "left") styles.add("text-align:$align")
                    out.append("<blockquote style=\"${styles.joinToString(";")}\">$inner</blockquote>")
                }
                else -> {
                    if (align != null || indentLvl > 0) {
                        val styles = mutableListOf<String>()
                        if (align != null && align != "left") styles.add("text-align:$align")
                        if (indentLvl > 0) styles.add("margin-left:${indentLvl * 24}px")
                        out.append("<div style=\"${styles.joinToString(";")}\">$inner</div>")
                    } else {
                        out.append("<div>$inner</div>")
                    }
                }
            }
        }
    }
    closeList()
    val result = out.toString()
    return result.ifBlank { "<div><br></div>" }
}

private fun buildInlineHtml(spanned: Spanned, start: Int, end: Int): String {
    val sb = StringBuilder()
    var i = start
    while (i < end) {
        val char = spanned[i]
        // CID image spans first
        val cidSpans = spanned.getSpans(i, i + 1, CidImageSpan::class.java).filter {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        if (cidSpans.isNotEmpty()) {
            val cs = cidSpans.first()
            sb.append("<img src=\"cid:${cs.cid}\">")
            val spanEnd = min(spanned.getSpanEnd(cs), end)
            i = spanEnd
            continue
        }
        // HR spans – emit <hr> and advance
        val hrSpans = spanned.getSpans(i, i + 1, HrSpan::class.java).filter {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        if (hrSpans.isNotEmpty()) {
            sb.append("<hr>")
            val spanEnd = min(spanned.getSpanEnd(hrSpans.first()), end)
            i = spanEnd
            continue
        }
        if (char == '\uFFFC') {
            i++
            continue
        }
        val nextChange = findNextSpanBoundary(spanned, i, end)
        val slice = spanned.subSequence(i, nextChange).toString()
        var piece = slice.htmlEncode()

        // Determine active inline spans at i
        val hasCode = spanned.getSpans(i, i + 1, InlineCodeSpan::class.java).any {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        val hasBold = spanned.getSpans(i, i + 1, StyleSpan::class.java).any {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i && (it.style and Typeface.BOLD) != 0
        }
        val hasItalic = spanned.getSpans(i, i + 1, StyleSpan::class.java).any {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i && (it.style and Typeface.ITALIC) != 0
        }
        val hasUnderline = spanned.getSpans(i, i + 1, UnderlineSpan::class.java).any {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        val hasStrike = spanned.getSpans(i, i + 1, StrikethroughSpan::class.java).any {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        val urlSpan = spanned.getSpans(i, i + 1, URLSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        val fgSpan = spanned.getSpans(i, i + 1, ForegroundColorSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        val bgSpan = spanned.getSpans(i, i + 1, BackgroundColorSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        val sizeSpan = spanned.getSpans(i, i + 1, RelativeSizeSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        // Font family: our custom FontFamilySpan has priority; fallback to TypefaceSpan with family != monospace and not InlineCode
        val fontFamilySpan = spanned.getSpans(i, i + 1, FontFamilySpan::class.java).firstOrNull {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        val typefaceFamilySpan = spanned.getSpans(i, i + 1, TypefaceSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i && it !is InlineCodeSpan
        }

        // Build nesting inside-out: innermost first (code), outermost last (color)
        if (hasCode) {
            piece = "<code style=\"font-family:monospace;background:#f5f5f5;padding:1px 4px;border-radius:3px\">$piece</code>"
        }
        if (hasStrike) piece = "<s>$piece</s>"
        if (hasUnderline) piece = "<u>$piece</u>"
        if (hasItalic) piece = "<i>$piece</i>"
        if (hasBold) piece = "<b>$piece</b>"
        if (urlSpan != null) {
            piece = "<a href=\"${escapeAttr(urlSpan.url ?: "")}\">$piece</a>"
        }
        // Font family
        val familyName: String? = fontFamilySpan?.familyName ?: typefaceFamilySpan?.family
        if (familyName != null) {
            val cssFamily = when (familyName.lowercase()) {
                "monospace" -> "monospace"
                "serif" -> "serif"
                "sans-serif", "sans_serif", "sans" -> "sans-serif"
                else -> familyName
            }
            piece = "<span style=\"font-family:${escapeAttr(cssFamily)}\">$piece</span>"
        }
        if (sizeSpan != null) {
            val factor = sizeSpan.sizeChange
            // Emit em-based size, clamp for email safety. Locale.ROOT: this is a CSS
            // value, a comma decimal separator would make it invalid.
            val sizeStr = "${String.format(java.util.Locale.ROOT, "%.2f", factor)}em"
            piece = "<span style=\"font-size:$sizeStr\">$piece</span>"
        }
        if (bgSpan != null) {
            val hex = colorToHex(bgSpan.backgroundColor)
            piece = "<span style=\"background-color:$hex\">$piece</span>"
        }
        if (fgSpan != null) {
            val hex = colorToHex(fgSpan.foregroundColor)
            piece = "<span style=\"color:$hex\">$piece</span>"
        }

        sb.append(piece)
        i = nextChange
    }
    return sb.toString().replace("\n", "<br>")
}

private fun findNextSpanBoundary(spanned: Spanned, from: Int, end: Int): Int {
    var next = end
    val spans = spanned.getSpans(from, end, Any::class.java)
    for (sp in spans) {
        val s = spanned.getSpanStart(sp)
        val e = spanned.getSpanEnd(sp)
        if (s > from && s < next) next = s
        if (e > from && e < next) next = e
    }
    if (next == from) next = from + 1
    return min(next, end)
}

private fun escapeAttr(value: String): String {
    return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}

private fun colorToHex(color: Int): String {
    // Strip alpha, produce #RRGGBB
    return String.format("#%06X", 0xFFFFFF and color)
}
