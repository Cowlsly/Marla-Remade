package com.vayunmathur.pdf.util

import androidx.compose.ui.geometry.Offset
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the compact little-endian primitive buffer produced by the native
 * renderer ([PdfNative.renderPage]) into a [SafePdfPage].
 *
 * Wire format v9 (must stay in sync with `pdf/rust/src/wire.rs`):
 * ```
 * header: u32 MAGIC=0x50444657, u32 VERSION=9, f32 pageWidth, f32 pageHeight, u32 primitiveCount
 *  Legacy v1 fallback: header is f32 W,H,u32 count (no magic)
 *  v2..v9 fallbacks: same layout with fewer trailing fields for older cached pages
 * per primitive: u8 tag, then payload
 *   1 Text:   f32 x, f32 y, f32 size, u32 argb, u16 len, [utf8 bytes], u8 hasStroke, u32 strokeArgb, f32 strokeWidth, u8 renderMode (v4), u8 blend (v5), f32 advance (v7), u8 fontFlags (v8 bold italic), f32 hScale (v8)
 *   2 Fill:   u32 argb, u8 evenOdd, u16 nContours, [u16 nPts, [f32 x,y]...]... (v6), u8 blend (v5)
 *   3 Stroke: u32 argb, f32 width, u8 nDash, [f32 dash]..., f32 phase, u8 cap, u8 join, f32 miter, u16 nPts, [f32 x, y]..., u8 blend (v5)
 *   4 Image:  6*f32 ctm, u32 w, u32 h, u8 format, f32 alpha (v9), u8 blend (v10), u32 len, [bytes]
 *   5 ClipPush: u8 evenOdd, u16 nPts, [f32 x,y]..., u16 nPathOps, [...] (v4)
 *   6 ClipPop: empty
 *   7 GroupPush: u8 isolated, u8 knockout, f32 alpha, u8 blend
 *   8 GroupPop: empty
 *   9 TextClipApply: empty (v4)
 *   10 SoftMaskPush: u8 maskType (0 alpha, 1 lum) (v5)
 *   11 SoftMaskContent: empty (v5)
 *   12 SoftMaskPop: empty (v5)
 * ```
 * Pure function -> unit-testable. Guards via MAX_PRIMITIVES avoid OOM. v9 adds per-image alpha.
 */
object SafePdfParser {

    private const val TAG_TEXT = 1
    private const val TAG_FILL = 2
    private const val TAG_STROKE = 3
    private const val TAG_IMAGE = 4
    private const val TAG_CLIP_PUSH = 5
    private const val TAG_CLIP_POP = 6
    private const val TAG_GROUP_PUSH = 7
    private const val TAG_GROUP_POP = 8
    private const val TAG_TEXT_CLIP_APPLY = 9
    private const val TAG_SMASK_PUSH = 10
    private const val TAG_SMASK_CONTENT = 11
    private const val TAG_SMASK_POP = 12

    private const val PATHOP_MOVE = 0
    private const val PATHOP_LINE = 1
    private const val PATHOP_CUBIC = 2
    private const val PATHOP_CLOSE = 3

    const val WIRE_MAGIC: Int = 0x50444657 // 'PDFW' little-endian as u32
    const val WIRE_VERSION: Int = 10
    private const val WIRE_VERSION_V2 = 2
    private const val WIRE_VERSION_V4 = 4
    private const val WIRE_VERSION_V5 = 5
    private const val WIRE_VERSION_V6 = 6
    private const val WIRE_VERSION_V7 = 7
    private const val WIRE_VERSION_V8 = 8
    private const val WIRE_VERSION_V9 = 9
    private const val WIRE_VERSION_V10 = 10
    const val MAX_PRIMITIVES = 50000
    const val MAX_ANNOTATIONS = 10000

    fun parse(bytes: ByteArray): SafePdfPage {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 12) throw IllegalArgumentException("Buffer too small")

        val firstInt = buf.int
        val wireVersion: Int
        val width: Float
        val height: Float
        val countRaw: Int

        if (firstInt == WIRE_MAGIC) {
            if (buf.remaining() < 12) throw IllegalArgumentException("v2/v3 header truncated")
            wireVersion = buf.int
            width = buf.float
            height = buf.float
            countRaw = buf.int
        } else {
            // v1 legacy: firstInt was actually width bits, reinterpret
            wireVersion = 1
            width = java.lang.Float.intBitsToFloat(firstInt)
            height = buf.float
            countRaw = buf.int
        }

        // Safety guards: count caps, version enforcement, dimension sanity
        if (width <= 0f || height <= 0f || width > 20000f || height > 20000f) {
            throw IllegalArgumentException("Invalid page dimensions $width x $height")
        }
        if (countRaw < 0 || countRaw > MAX_PRIMITIVES) {
            throw IllegalArgumentException("Primitive count out of bounds: $countRaw")
        }

        val isV2OrV3 = wireVersion >= WIRE_VERSION_V2
        val isV4 = wireVersion >= WIRE_VERSION_V4
        val isV5 = wireVersion >= WIRE_VERSION_V5
        val isV6 = wireVersion >= WIRE_VERSION_V6
        val isV7 = wireVersion >= WIRE_VERSION_V7
        val isV8 = wireVersion >= WIRE_VERSION_V8
        val isV9 = wireVersion >= WIRE_VERSION_V9
        val isV10 = wireVersion >= WIRE_VERSION_V10
        // Accept v1 (legacy), v2, v3 and v4. Newer versions are tolerated via
        // forward-compat parsing as long as the tags are known.
        if (wireVersion !in 1..WIRE_VERSION) {
            if (wireVersion > WIRE_VERSION) {
                android.util.Log.w("SafePdfParser", "Wire version $wireVersion > $WIRE_VERSION, attempting forward compat parse")
            } else {
                throw IllegalArgumentException("Unsupported wire version: $wireVersion")
            }
        }

        val primitives = ArrayList<PdfPrimitive>(countRaw.coerceAtLeast(0))
        repeat(countRaw) {
            if (!buf.hasRemaining()) return@repeat
            when (val tag = buf.get().toInt() and 0xFF) {
                TAG_TEXT -> {
                    val x = buf.float
                    val y = buf.float
                    val size = buf.float
                    val argb = buf.int
                    val len = buf.short.toInt() and 0xFFFF
                    if (len < 0 || len > 4096) throw IllegalArgumentException("Text length out of bounds $len")
                    if (buf.remaining() < len) throw IllegalArgumentException("Text length truncated")
                    val strBytes = ByteArray(len)
                    buf.get(strBytes)
                    val strokeColor: Int?
                    val strokeWidth: Float
                    if (isV2OrV3) {
                        if (buf.remaining() < 9) throw IllegalArgumentException("Text v2 truncated")
                        val hasStroke = buf.get().toInt() != 0
                        val sArgb = buf.int
                        val sWidth = buf.float
                        if (hasStroke) {
                            strokeColor = sArgb
                            strokeWidth = sWidth
                        } else {
                            strokeColor = null
                            strokeWidth = 0f
                        }
                    } else {
                        strokeColor = null
                        strokeWidth = 0f
                    }
                    val renderMode = if (isV4) {
                        if (buf.remaining() < 1) throw IllegalArgumentException("Text v4 renderMode truncated")
                        buf.get().toInt() and 0xFF
                    } else 0
                    val blend = if (isV5) {
                        if (buf.remaining() < 1) throw IllegalArgumentException("Text v5 blend truncated")
                        BlendMode.fromCode(buf.get().toInt() and 0xFF)
                    } else BlendMode.Normal
                    val txt = String(strBytes, Charsets.UTF_8)
                    // v7 carries the true device-space glyph advance; older wires
                    // fall back to the size*0.5*len heuristic.
                    val adv = if (isV7) {
                        if (buf.remaining() < 4) throw IllegalArgumentException("Text v7 advance truncated")
                        buf.float
                    } else {
                        size * 0.5f * txt.length.coerceAtLeast(1)
                    }
                    val isBold: Boolean
                    val isItalic: Boolean
                    val fontFamily: Int
                    val outline: Boolean
                    val hScale: Float
                    if (isV8) {
                        if (buf.remaining() < 5) throw IllegalArgumentException("Text v8 fontFlags truncated")
                        val fontFlags = buf.get().toInt() and 0xFF
                        isBold = fontFlags and 1 != 0
                        isItalic = fontFlags and 2 != 0
                        // Bits 2-3 carry the generic family: 0 sans, 1 serif, 2 mono.
                        fontFamily = (fontFlags shr 2) and 0x3
                        // Bit 4: glyph already drawn as outline fills (don't paint).
                        outline = fontFlags and 0x10 != 0
                        hScale = buf.float
                    } else {
                        isBold = false
                        isItalic = false
                        fontFamily = 0
                        outline = false
                        hScale = 1f
                    }
                    primitives.add(
                        PdfPrimitive.Text(
                            origin = Offset(x, y),
                            size = size,
                            color = argb,
                            text = txt,
                            strokeColor = strokeColor,
                            strokeWidth = strokeWidth,
                            advance = adv,
                            renderMode = renderMode,
                            blend = blend,
                            isBold = isBold,
                            isItalic = isItalic,
                            fontFamily = fontFamily,
                            outline = outline,
                            hScale = hScale,
                        )
                    )
                }

                TAG_FILL -> {
                    val argb = buf.int
                    val evenOdd = buf.get().toInt() != 0
                    val contours = if (isV6) {
                        val n = buf.short.toInt() and 0xFFFF
                        if (n > MAX_PRIMITIVES) throw IllegalArgumentException("Fill contour count out of bounds $n")
                        (0 until n).map { readPoints(buf) }
                    } else {
                        // v5 and earlier: a single polygon per fill.
                        listOf(readPoints(buf))
                    }
                    val blend = if (isV5) {
                        if (buf.remaining() < 1) throw IllegalArgumentException("Fill v5 blend truncated")
                        BlendMode.fromCode(buf.get().toInt() and 0xFF)
                    } else BlendMode.Normal
                    primitives.add(PdfPrimitive.FillPath(argb, evenOdd, contours, blend))
                }

                TAG_STROKE -> {
                    val argb = buf.int
                    val strokeWidth = buf.float
                    val nDash = buf.get().toInt() and 0xFF
                    if (nDash < 0 || nDash > 32) throw IllegalArgumentException("Dash count out of bounds $nDash")
                    if (buf.remaining() < nDash*4+4) throw IllegalArgumentException("Stroke dash truncated")
                    val dash = FloatArray(nDash) { buf.float }
                    val dashPhase = buf.float
                    val cap: Int
                    val join: Int
                    val miter: Float
                    if (isV2OrV3) {
                        if (buf.remaining() < 6) throw IllegalArgumentException("Stroke v2 cap/join truncated")
                        cap = buf.get().toInt() and 0xFF
                        join = buf.get().toInt() and 0xFF
                        miter = buf.float
                    } else {
                        cap = 0; join = 0; miter = 10f
                    }
                    val points = readPoints(buf)
                    val blend = if (isV5) {
                        if (buf.remaining() < 1) throw IllegalArgumentException("Stroke v5 blend truncated")
                        BlendMode.fromCode(buf.get().toInt() and 0xFF)
                    } else BlendMode.Normal
                    primitives.add(
                        PdfPrimitive.StrokePath(argb, strokeWidth, dash, dashPhase, points, cap, join, miter, blend)
                    )
                }

                TAG_IMAGE -> {
                    val ctm = FloatArray(6) { buf.float }
                    val w = buf.int
                    val h = buf.int
                    if (w <= 0 || h <= 0 || w > 20000 || h > 20000) {
                        if (buf.remaining() >= 5) {
                            buf.get() // fmt
                            if (isV9 && buf.remaining() >= 4) buf.float // alpha v9
                            if (isV10 && buf.remaining() >= 1) buf.get() // blend v10
                            val len = buf.int
                            if (len >=0 && buf.remaining() >= len) buf.position(buf.position()+len)
                        }
                        return@repeat
                    }
                    if (w.toLong()*h.toLong() > 16*1024*1024) {
                        if (buf.remaining() >= 5) {
                            buf.get()
                            if (isV9 && buf.remaining() >= 4) buf.float
                            if (isV10 && buf.remaining() >= 1) buf.get()
                            val len = buf.int
                            if (len >=0 && buf.remaining() >= len) buf.position(buf.position()+len)
                        }
                        return@repeat
                    }
                    val format = buf.get().toInt()
                    val imgAlpha = if (isV9) {
                        if (buf.remaining() < 4) throw IllegalArgumentException("Image v9 alpha truncated")
                        buf.float.coerceIn(0f,1f)
                    } else 1f
                    val imgBlend = if (isV10) {
                        if (buf.remaining() < 1) throw IllegalArgumentException("Image v10 blend truncated")
                        BlendMode.fromCode(buf.get().toInt() and 0xFF)
                    } else BlendMode.Normal
                    val len = buf.int
                    if (len < 0 || len > 16*1024*1024) throw IllegalArgumentException("Image data length out of bounds $len")
                    if (buf.remaining() < len) throw IllegalArgumentException("Image data truncated")
                    val data = ByteArray(len)
                    buf.get(data)
                    val bmp = decodeBitmap(w, h, format, data)
                    primitives.add(PdfPrimitive.Image(ctm, bmp, imgAlpha, imgBlend))
                }

                TAG_CLIP_PUSH -> {
                    val evenOdd = buf.get().toInt() != 0
                    val pts = readPoints(buf)
                    val pathOps = if (isV4) readPathOps(buf) else null
                    // Degenerate clip guard (shoelace <1e-3 or <3 pts) already enforced in Rust, but double-guard in Kotlin saveCount restore.
                    if (pts.size >= 3 || (pathOps != null && pathOps.isNotEmpty())) {
                        primitives.add(PdfPrimitive.ClipPush(evenOdd, pts, pathOps))
                    }
                }

                TAG_CLIP_POP -> {
                    primitives.add(PdfPrimitive.ClipPop)
                }

                TAG_TEXT_CLIP_APPLY -> {
                    primitives.add(PdfPrimitive.TextClipApply)
                }

                TAG_GROUP_PUSH -> {
                    if (buf.remaining() < 6) throw IllegalArgumentException("GroupPush truncated")
                    val isolated = buf.get().toInt() != 0
                    val knockout = buf.get().toInt() != 0
                    val alpha = buf.float.coerceIn(0f,1f)
                    val blendCode = buf.get().toInt() and 0xFF
                    primitives.add(PdfPrimitive.GroupPush(isolated, knockout, alpha, BlendMode.fromCode(blendCode)))
                }

                TAG_GROUP_POP -> {
                    primitives.add(PdfPrimitive.GroupPop)
                }

                TAG_SMASK_PUSH -> {
                    if (buf.remaining() < 1) throw IllegalArgumentException("SoftMaskPush truncated")
                    val maskType = buf.get().toInt() and 0xFF
                    primitives.add(PdfPrimitive.SoftMaskPush(maskType))
                }

                TAG_SMASK_CONTENT -> {
                    primitives.add(PdfPrimitive.SoftMaskContent)
                }

                TAG_SMASK_POP -> {
                    primitives.add(PdfPrimitive.SoftMaskPop)
                }

                else -> {
                    // P1 fix (#12 Kotlin audit): unknown tag should skip not crash whole page (forward compat)
                    android.util.Log.w("SafePdfParser", "Unknown primitive tag $tag wireVersion=$wireVersion width=$width — skipping")
                    // We cannot know payload length, so just skip this primitive
                }
            }
        }

        return SafePdfPage(width, height, primitives)
    }

    /** Decode the annotation listing buffer from `listAnnotations`. */
    fun parseAnnotations(bytes: ByteArray): List<SafeAnnotation> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeAnnotation>(count.coerceAtLeast(0))
        repeat(count) {
            val id = buf.long
            val subtype = buf.get().toInt()
            val x0 = buf.float; val y0 = buf.float; val x1 = buf.float; val y1 = buf.float
            val color = buf.int
            val contents = readString(buf)
            out.add(SafeAnnotation(id, subtype, x0, y0, x1, y1, color, contents))
        }
        return out
    }

    /** Decode the form-field listing buffer from `listFormFields`. */
    fun parseFormFields(bytes: ByteArray): List<SafeFormField> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeFormField>(count.coerceAtLeast(0))
        repeat(count) {
            val id = buf.long
            val type = buf.get().toInt()
            val x0 = buf.float; val y0 = buf.float; val x1 = buf.float; val y1 = buf.float
            val name = readString(buf)
            val value = readString(buf)
            val checked = buf.get().toInt() != 0
            out.add(SafeFormField(id, type, x0, y0, x1, y1, name, value, checked))
        }
        return out
    }

    /** Decode the search-match buffer from `searchDocument`. */
    fun parseSearchMatches(bytes: ByteArray): List<SafeSearchMatch> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeSearchMatch>(count.coerceAtLeast(0))
        repeat(count) {
            val page = buf.int
            out.add(SafeSearchMatch(page, buf.float, buf.float, buf.float, buf.float))
        }
        return out
    }

    /** Decode the link listing buffer from `listLinks`. */
    fun parseLinks(bytes: ByteArray): List<SafeLink> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeLink>(count.coerceAtLeast(0))
        repeat(count) {
            val x0 = buf.float; val y0 = buf.float; val x1 = buf.float; val y1 = buf.float
            val dest = buf.int
            val uri = readString(buf)
            out.add(SafeLink(x0, y0, x1, y1, dest, uri))
        }
        return out
    }

    private fun readString(buf: ByteBuffer): String {
        if (buf.remaining() < 2) throw IllegalArgumentException("readString header truncated")
        val len = buf.short.toInt() and 0xFFFF
        if (len > 4096) throw IllegalArgumentException("readString length $len exceeds 4096 cap (v9 guard)")
        if (buf.remaining() < len) throw IllegalArgumentException("readString truncated len=$len remaining=${buf.remaining()}")
        val b = ByteArray(len)
        buf.get(b)
        return String(b, Charsets.UTF_8)
    }

    /** Decode the outline buffer from `listOutline`. */
    fun parseOutline(bytes: ByteArray): List<SafeOutlineItem> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeOutlineItem>(count.coerceAtLeast(0))
        repeat(count) {
            val level = buf.short.toInt() and 0xFFFF
            val page = buf.int
            val title = readString(buf)
            out.add(SafeOutlineItem(level, page, title))
        }
        return out
    }

    private fun readPoints(buf: ByteBuffer): List<Offset> {
        val n = buf.short.toInt() and 0xFFFF
        val points = ArrayList<Offset>(n.coerceAtLeast(0))
        repeat(n) {
            if (buf.remaining() < 8) return@repeat
            val x = buf.float
            val y = buf.float
            points.add(Offset(x, y))
        }
        return points
    }

    /** Decode the v4 bezier-retentive clip path-ops section. */
    private fun readPathOps(buf: ByteBuffer): List<PathOp> {
        val n = buf.short.toInt() and 0xFFFF
        val ops = ArrayList<PathOp>(n.coerceAtLeast(0))
        repeat(n) {
            if (!buf.hasRemaining()) return@repeat
            when (buf.get().toInt() and 0xFF) {
                PATHOP_MOVE -> {
                    if (buf.remaining() < 8) return@repeat
                    ops.add(PathOp.Move(buf.float, buf.float))
                }
                PATHOP_LINE -> {
                    if (buf.remaining() < 8) return@repeat
                    ops.add(PathOp.Line(buf.float, buf.float))
                }
                PATHOP_CUBIC -> {
                    if (buf.remaining() < 24) return@repeat
                    ops.add(PathOp.Cubic(buf.float, buf.float, buf.float, buf.float, buf.float, buf.float))
                }
                PATHOP_CLOSE -> ops.add(PathOp.Close)
                else -> {}
            }
        }
        return ops
    }

    /** Decode an image payload: format 1 = JPEG bytes, 0 = raw RGBA8888. */
    private fun decodeBitmap(w: Int, h: Int, format: Int, data: ByteArray): android.graphics.Bitmap? {
        if (w <= 0 || h <= 0 || w > 20000 || h > 20000 || w.toLong()*h.toLong() > 16*1024*1024) return null
        return try {
            when (format) {
                1 -> {
                    if (data.size > 16*1024*1024) {
                        android.util.Log.w("SafePdfParser", "JPEG too large ${data.size}")
                        null
                    } else {
                        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                    }
                }
                0 -> {
                    if (data.size < w * h * 4) return null
                    val pixels = IntArray(w * h)
                    var p = 0
                    for (i in pixels.indices) {
                        val r = data[p].toInt() and 0xFF
                        val g = data[p + 1].toInt() and 0xFF
                        val b = data[p + 2].toInt() and 0xFF
                        val a = data[p + 3].toInt() and 0xFF
                        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        p += 4
                    }
                    android.graphics.Bitmap.createBitmap(
                        pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888
                    )
                }
                else -> {
                    // Rust returns no image at all for a format it could not produce, so an
                    // unknown format here is a wire mismatch, not a failed decode to paper over.
                    android.util.Log.w("SafePdfParser", "Unknown bitmap format $format")
                    null
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("SafePdfParser", "decodeBitmap failed w=$w h=$h format=$format", t)
            null
        }
    }
}
