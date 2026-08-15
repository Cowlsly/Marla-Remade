package com.vayunmathur.email.composer

import android.text.Editable
import android.text.Html
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.vayunmathur.library.ui.EmailAlignmentSpan
import com.vayunmathur.library.ui.EmailBlockQuoteSpan
import com.vayunmathur.library.ui.FontFamilySpan
import com.vayunmathur.library.ui.HeadingSpan
import com.vayunmathur.library.ui.HrSpan
import com.vayunmathur.library.ui.InlineCodeSpan
import org.xml.sax.XMLReader

/**
 * Custom TagHandler for HtmlCompat.fromHtml so that when we re-open a draft
 * (or setHtml) the rich spans we emitted via [serializeEmailHtml] are restored
 * into the Editable. HtmlCompat drops style="color"/text-align otherwise.
 *
 * Handled:
 *  - h1/h2/h3 → HeadingSpan
 *  - blockquote → EmailBlockQuoteSpan
 *  - code → InlineCodeSpan
 *  - span style="color:#RRGGBB" → ForegroundColorSpan
 *  - span style="background-color:#RRGGBB" → BackgroundColorSpan
 *  - span style="font-size:1.2em" → RelativeSizeSpan
 *  - span style="font-family:serif" → FontFamilySpan
 *  - div style="text-align:center" → EmailAlignmentSpan
 *  - hr → inserts OBJECT REPLACEMENT with HrSpan (so serializer emits <hr>)
 */
class EmailHtmlTagHandler : Html.TagHandler {

    private data class SpanMarker(val start: Int, val kind: String, val extra: String? = null)

    private val markers = mutableListOf<SpanMarker>()

    override fun handleTag(opening: Boolean, tag: String, output: Editable, xmlReader: XMLReader) {
        val t = tag.lowercase()
        if (opening) {
            when (t) {
                "h1" -> markers.add(SpanMarker(output.length, "h1"))
                "h2" -> markers.add(SpanMarker(output.length, "h2"))
                "h3" -> markers.add(SpanMarker(output.length, "h3"))
                "blockquote" -> markers.add(SpanMarker(output.length, "blockquote"))
                "code" -> markers.add(SpanMarker(output.length, "code"))
                "hr" -> {
                    // Insert object replacement and tag it – matches serializer contract
                    val pos = output.length
                    output.append("\uFFFC")
                    // Ensure next char newline already present via surrounding html parsing? Add explicit span here.
                    output.setSpan(HrSpan(), pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "span", "div" -> {
                    // Defer – we need attributes. XMLReader does not expose them easily,
                    // but we record start; closing tag will inspect substring? Instead we rely on
                    // a simpler heuristic: the raw attrs are available via reflection on the parser
                    // stack in some implementations. We approximate by reading style from the TagHandler
                    // argument – HtmlCompat does NOT give attrs, so we try to get them from the underlying
                    // parser's attribute list via xmlReader.
                    val style = extractStyle(xmlReader)
                    if (style != null) {
                        markers.add(SpanMarker(output.length, t, style))
                    }
                }
            }
        } else {
            when (t) {
                "h1", "h2", "h3" -> {
                    val start = popMarker(t) ?: return
                    val end = output.length
                    if (start >= end) return
                    val level = when (t) { "h1" -> 1; "h2" -> 2; else -> 3 }
                    output.setSpan(HeadingSpan(level), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "blockquote" -> {
                    val start = popMarker("blockquote") ?: return
                    val end = output.length
                    if (start >= end) return
                    output.setSpan(EmailBlockQuoteSpan(), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }
                "code" -> {
                    val start = popMarker("code") ?: return
                    val end = output.length
                    if (start >= end) return
                    output.setSpan(InlineCodeSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "span" -> {
                    val marker = popMarkerWithExtra("span") ?: return
                    val end = output.length
                    if (marker.start >= end) return
                    applySpanStyles(output, marker.start, end, marker.extra)
                }
                "div" -> {
                    val marker = popMarkerWithExtra("div")
                    val end = output.length
                    if (marker == null) return
                    if (marker.start >= end) return
                    applyDivStyles(output, marker.start, end, marker.extra)
                }
            }
        }
    }

    private fun popMarker(kind: String): Int? {
        for (i in markers.indices.reversed()) {
            if (markers[i].kind == kind && markers[i].extra == null) {
                val s = markers[i].start
                markers.removeAt(i)
                return s
            }
        }
        return null
    }

    private data class ExtraMarker(val start: Int, val extra: String?)
    private fun popMarkerWithExtra(kind: String): ExtraMarker? {
        for (i in markers.indices.reversed()) {
            if (markers[i].kind == kind) {
                val m = markers[i]
                markers.removeAt(i)
                return ExtraMarker(m.start, m.extra)
            }
        }
        return null
    }

    private fun applySpanStyles(output: Editable, start: Int, end: Int, styleAttr: String?) {
        if (styleAttr == null) return
        val map = parseStyle(styleAttr)
        map["color"]?.let { v ->
            parseColor(v)?.let { c -> output.setSpan(ForegroundColorSpan(c), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        map["background-color"]?.let { v ->
            parseColor(v)?.let { c -> output.setSpan(BackgroundColorSpan(c), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        map["font-size"]?.let { v ->
            parseFontSize(v)?.let { f -> output.setSpan(RelativeSizeSpan(f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        map["font-family"]?.let { v ->
            val fam = v.trim().removePrefix("\"").removeSuffix("\"").removePrefix("'").removeSuffix("'").trim()
            if (fam.isNotBlank()) output.setSpan(FontFamilySpan(fam), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun applyDivStyles(output: Editable, start: Int, end: Int, styleAttr: String?) {
        if (styleAttr == null) return
        val map = parseStyle(styleAttr)
        map["text-align"]?.let { a ->
            val css = a.lowercase().trim()
            if (css in setOf("left", "center", "right", "justify")) {
                output.setSpan(EmailAlignmentSpan(css), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            }
        }
        // Also divs may carry margin-left for indent – we keep IndentSpan path as before via standard Html handling;
        // serializer emits margin-left only for indent; we do not re-create IndentSpan here (Html.fromHtml loses it).
        // Users get indent via manual button anyway.
    }

    // --- Style parsing ---

    private fun parseStyle(style: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        style.split(";").forEach { decl ->
            val idx = decl.indexOf(':')
            if (idx > 0) {
                val k = decl.substring(0, idx).trim().lowercase()
                val v = decl.substring(idx + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) result[k] = v
            }
        }
        return result
    }

    private fun parseColor(v: String): Int? {
        val s = v.trim()
        return try {
            when {
                s.startsWith("#") -> {
                    var hex = s.removePrefix("#")
                    if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
                    if (hex.length == 6) hex = "FF$hex"
                    if (hex.length == 8) hex.toLong(16).toInt()
                    else null
                }
                s.startsWith("rgb") -> {
                    // rgb(0,0,0) or rgba – best-effort
                    val nums = Regex("\\d+").findAll(s).map { it.value.toInt() }.toList()
                    if (nums.size >= 3) android.graphics.Color.rgb(nums[0], nums[1], nums[2]) else null
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun parseFontSize(v: String): Float? {
        val s = v.trim().lowercase()
        return when {
            s.endsWith("em") -> s.removeSuffix("em").toFloatOrNull()
            s.endsWith("px") -> {
                // Assume base 16px
                s.removeSuffix("px").toFloatOrNull()?.let { it / 16f }
            }
            else -> s.toFloatOrNull()
        }?.coerceIn(0.5f, 3f)
    }

    /**
     * Try to extract the current tag's style attribute from the underlying XMLReader.
     * This works when the SAX parser exposes attributes via reflection (implementation
     * varies across Android versions). We try a few common paths; if none works we return null
     * and fall back to tag-only handling (headings/blockquote/code/hr still work).
     */
    private fun extractStyle(xmlReader: XMLReader): String? {
        return try {
            // Attempt: xmlReader is actually org.ccil.cowan.tagsoup.Parser or similar that holds attributes
            // We try to get the current element's attributes via reflection.
            // Strategy: if xmlReader has field "theNewElement" etc – we go generic: try to call getProperty
            val propAttempts = listOf(
                "http://xml.org/sax/properties/dom-node",
                "http://www.ccil.org/~cowan/tagsoup/properties/attributes"
            )
            // TagSoup path – most Android fromHtml uses TagSoup internally
            // The attributes are not exposed directly to TagHandler, but some versions
            // push them onto a stack accessible via "attributes" property.
            var attrs: org.xml.sax.Attributes? = null
            for (p in propAttempts) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    attrs = xmlReader.getProperty(p) as? org.xml.sax.Attributes
                    if (attrs != null) break
                } catch (_: Exception) { /* next */ }
            }
            // Fallback: reflect on field that might hold atts in org.ccil.cowan.tagsoup.Parser$Element
            if (attrs == null) {
                // Try private field theStack or similar
                val fields = xmlReader.javaClass.declaredFields
                for (f in fields) {
                    f.isAccessible = true
                    val v = f.get(xmlReader)
                    if (v is org.xml.sax.Attributes) {
                        attrs = v
                        break
                    }
                }
            }
            if (attrs != null) {
                val idx = attrs.getIndex("style")
                if (idx >= 0) attrs.getValue(idx) else null
            } else null
        } catch (_: Exception) { null }
    }
}
