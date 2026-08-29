package com.vayunmathur.pdf.util

import androidx.compose.ui.geometry.Offset
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the compact little-endian primitive buffer produced by the native
 * renderer ([PdfNative.renderPage]) into a [SafePdfPage].
 *
 * Wire format v11 (must stay in sync with `pdf/src/main/rust/src/wire.rs`):
 * ```
 * header: u32 MAGIC=0x50444657, u32 VERSION (11 is the newest understood; Rust emits 10),
 *         f32 pageWidth, f32 pageHeight, u32 primitiveCount
 *  Legacy v1 fallback: header is f32 W,H,u32 count (no magic)
 *  v2..v10 fallbacks: same layout with fewer trailing fields for older cached pages
 * per primitive: u8 tag, then payload
 *   1 Text:   f32 x, f32 y, f32 size, u32 argb, u16 len, [utf8 bytes], u8 hasStroke, u32 strokeArgb, f32 strokeWidth, u8 renderMode (v4), u8 blend (v5), f32 advance (v7), u8 fontFlags (v8 bold italic), f32 hScale (v8)
 *   2 Fill:   u32 argb, u8 evenOdd, u16 nContours, [u16 nPts, [f32 x,y]...]... (v6), u8 blend (v5)
 *   3 Stroke: u32 argb, f32 width, u8 nDash, [f32 dash]..., f32 phase, u8 cap, u8 join, f32 miter, u16 nPts, [f32 x, y]..., u8 blend (v5)
 *   4 Image:  6*f32 ctm, u32 w, u32 h, u8 format, f32 alpha (v9), u8 blend (v10), u8 interpolate (v11), u32 len, [bytes]
 *   5 ClipPush: u8 evenOdd, u16 nPts, [f32 x,y]..., u16 nPathOps, [...] (v4)
 *   6 ClipPop: empty
 *   7 GroupPush: u8 isolated, u8 knockout, f32 alpha, u8 blend
 *   8 GroupPop: empty
 *   9 TextClipApply: empty (v4)
 *   10 SoftMaskPush: u8 maskType (0 alpha, 1 lum) (v5)
 *   11 SoftMaskContent: empty (v5)
 *   12 SoftMaskPop: empty (v5)
 *   13 SoftMaskTransfer: 256 * u8 LUT over the mask value (v11) — the /TR of the immediately
 *      preceding SoftMaskPush. Handled regardless of the declared wire version, so Rust may
 *      start emitting it without a version bump and an older stream that never contains it is
 *      unaffected.
 *   14 ImageTiled: 6*f32 ctm, u32 w, u32 h, f32 xstep, f32 ystep, i32 i0, i32 j0, u32 nx,
 *      u32 ny, f32 alpha, u8 blend, u32 len, [RGBA8888 bytes] — one repeating cell for a
 *      tiling pattern. No format byte; the payload is always raw RGBA8888. Tag-gated, so an
 *      absent emitter is a no-op.
 * ```
 * Pure function -> unit-testable. v9 adds per-image alpha, v10 per-image blend mode, v11 the
 * per-image /Interpolate flag. Rust still declares v10 until it writes that byte, at which
 * point the two must be bumped together — see the note on WIRE_VERSION in wire.rs.
 *
 * Robustness contract: a single malformed or over-sized primitive must never discard the
 * page. [parse] returns whatever decoded cleanly, because the caller
 * ([SafePdfDocument.renderPage]) can only turn a thrown exception into a null page, which
 * the UI shows as an indefinite loading spinner.
 */
object SafePdfParser {

    private const val TAG = "SafePdfParser"

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
    private const val TAG_SMASK_TRANSFER = 13
    private const val TAG_IMAGE_TILED = 14

    private const val PATHOP_MOVE = 0
    private const val PATHOP_LINE = 1
    private const val PATHOP_CUBIC = 2
    private const val PATHOP_CLOSE = 3

    const val WIRE_MAGIC: Int = 0x50444657 // 'PDFW' little-endian as u32
    /**
     * Highest wire version this parser understands — NOT the version the producer emits.
     * `WIRE_VERSION` in `pdf/src/main/rust/src/wire.rs` is the emitted one and currently
     * declares 10; it may lag this constant, because every field newer than what it
     * declares stays gated off below, but it must never exceed it. A higher version is not
     * rejected here — [parse] warns and carries on with those gates closed, so it reads too
     * few bytes per primitive and desyncs the rest of the buffer.
     *
     * Rust's `wire::tests::wire_version_is_not_ahead_of_the_kotlin_parser` reads these two
     * declarations straight out of this file to assert that, so keep each of them on one
     * line in exactly the form `const val WIRE_VERSION: Int = <n>` / `= 0x<hex>`.
     */
    const val WIRE_VERSION: Int = 11
    private const val WIRE_VERSION_V2 = 2
    private const val WIRE_VERSION_V4 = 4
    private const val WIRE_VERSION_V5 = 5
    private const val WIRE_VERSION_V6 = 6
    private const val WIRE_VERSION_V7 = 7
    private const val WIRE_VERSION_V8 = 8
    private const val WIRE_VERSION_V9 = 9
    private const val WIRE_VERSION_V10 = 10
    private const val WIRE_VERSION_V11 = 11
    /**
     * Upper bound on primitives decoded from one page — a BACKSTOP against a corrupt count
     * field, not a truncation policy. Over the bound we clamp and render a partial page,
     * never throw, because a throw here discards the whole page.
     *
     * It must sit comfortably ABOVE Rust's own ceiling, which is what actually bounds the
     * stream. Rust enforces `MAX_PRIMITIVES = 300000` (graphics_state.rs:166) at every
     * content-emitting push — guards at ~57 sites across interpret.rs, draw.rs and
     * annotations.rs, plus a hard `prims.truncate` in draw.rs.
     *
     * DO NOT LOWER THIS TO MATCH 300000. The guards protect content-emitting pushes, but the
     * bracket BOOKKEEPING around them is emitted for structural correctness whether or not the
     * cap has been reached — dropping a `ClipPop` would leave its clip un-restored and blank
     * the rest of the page, so several of those pushes and pops are deliberately unguarded
     * (e.g. interpret.rs:247, :1145-1148, :1555). The emitted count can therefore exceed
     * 300000, and a clamp set to exactly 300000 would cut exactly that bookkeeping.
     *
     * The excess is NOT limited to closers. `exceeding_the_primitive_cap_keeps_the_bracket_
     * structure_intact` measures three shapes: overshoot 1 at one fill per tile, 2 at forty
     * fills per tile, and 6 for a form XObject crossing the cap mid-operator, where the excess
     * is ["ClipPop", "ClipPop", "ClipPush", "ClipPop", "ClipPop", "ClipPop"] — a `Do` still
     * pushes its mandatory `/BBox` clip (§8.10.2) and pops it again even though it emits no
     * content. So it is bracket bookkeeping, pushes included, not bracket closers.
     *
     * No tight upper bound on the overshoot has been established, and two attempts to derive
     * one from bracket depth were wrong. Do not replace this constant with a formula: it is a
     * backstop against a corrupt count field, so the correct posture is generous headroom
     * (~700k here) rather than a bound we would have to keep re-proving.
     *
     * NOTE: this clamp is not the tightest bound anywhere. `MAX_CONTENT_OPS`
     * (graphics_state.rs:250, applied at interpret.rs:274 as `ops.iter().take(..)`) was raised
     * from 200000 to 1000000 once it was established that `ops` is a fully-materialised Vec, so
     * `take` frees no memory and the constant was a time guard being used as a memory guard.
     * Rust's `MAX_PRIMITIVES = 300000` is now the binding producer bound. Covered executably by
     * `golden_tests::content_beyond_the_caps_truncates_to_a_valid_page`, which derives its
     * content size from whichever cap is smaller rather than hard-coding one.
     */
    const val MAX_PRIMITIVES = 1_000_000
    const val MAX_ANNOTATIONS = 10000

    /** A /TR transfer function is transmitted as this many u8 samples over the mask value. */
    private const val TRANSFER_LUT_SIZE = 256
    /**
     * Cap on a tiling pattern's lattice extent per axis. The extent only decides how large a
     * region the REPEAT shader is asked to cover, so a big count is not itself expensive, but
     * an absurd one would push the region path's coordinates past what Skia can represent.
     */
    private const val MAX_LATTICE_CELLS = 100_000
    /**
     * Largest deviation from a straight line, in 8-bit mask levels, still treated as affine.
     * Three levels is below the visible threshold for a mask edge.
     */
    private const val TRANSFER_FIT_TOLERANCE = 3f / 255f

    /**
     * Reduce a /TR LUT to `gain * m + bias` by least squares, marking the result non-affine
     * when the fit is worse than [TRANSFER_FIT_TOLERANCE].
     *
     * PDF 32000-1 §11.6.5.2 requires the mask value to pass through /TR, and an inverting /TR
     * is the standard idiom for "mask out where the group is bright" — so ignoring it hides
     * exactly the wrong half. A soft mask is drawn into a `Canvas.saveLayer`, and the only
     * per-pixel transform available on that composite is a `ColorMatrixColorFilter`, which is
     * affine. Inverting and gain/bias curves fit exactly and so are applied exactly. A
     * genuinely non-linear /TR (gamma, threshold, sampled type-0) cannot be: Android has no
     * LUT colour filter, a saveLayer has no readable pixel buffer to run a table over, and
     * `RuntimeShader`/AGSL cannot act as the filter on a saveLayer composite. Running the
     * table per pixel per frame is not viable either, since drawSafePage re-runs on every
     * frame at every zoom level. Those are reported as non-affine and left untransformed
     * rather than approximated, because a wrong curve hides the wrong half of the group —
     * the exact fault this exists to fix. KNOWN LIMITATION.
     */
    private fun fitTransferLut(lut: ByteArray): PdfPrimitive.SoftMaskTransfer {
        val n = lut.size
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val x = i / (n - 1).toDouble()
            val y = (lut[i].toInt() and 0xFF) / 255.0
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val denom = n * sxx - sx * sx
        // Degenerate only if every sample x is identical, which cannot happen for n > 1.
        val gain = if (denom != 0.0) (n * sxy - sx * sy) / denom else 0.0
        val bias = (sy - gain * sx) / n
        var maxErr = 0.0
        for (i in 0 until n) {
            val x = i / (n - 1).toDouble()
            val y = (lut[i].toInt() and 0xFF) / 255.0
            val e = kotlin.math.abs(gain * x + bias - y)
            if (e > maxErr) maxErr = e
        }
        val affine = maxErr <= TRANSFER_FIT_TOLERANCE
        if (!affine) {
            android.util.Log.w(TAG, "soft-mask /TR is not affine (max err $maxErr), leaving mask untransformed")
        }
        return PdfPrimitive.SoftMaskTransfer(gain.toFloat(), bias.toFloat(), affine)
    }

    fun parse(bytes: ByteArray): SafePdfPage {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 12) throw IllegalArgumentException("Buffer too small")

        val firstInt = buf.int
        val wireVersion: Int
        val width: Float
        val height: Float
        val countRaw: Int

        if (firstInt == WIRE_MAGIC) {
            if (buf.remaining() < 16) throw IllegalArgumentException("v2/v3 header truncated")
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
        if (countRaw < 0) {
            throw IllegalArgumentException("Negative primitive count: $countRaw")
        }
        val count = countRaw.coerceAtMost(MAX_PRIMITIVES)
        if (count < countRaw) {
            android.util.Log.w(TAG, "primitive count $countRaw exceeds $MAX_PRIMITIVES, rendering the first $count")
        }

        val isV2OrV3 = wireVersion >= WIRE_VERSION_V2
        val isV4 = wireVersion >= WIRE_VERSION_V4
        val isV5 = wireVersion >= WIRE_VERSION_V5
        val isV6 = wireVersion >= WIRE_VERSION_V6
        val isV7 = wireVersion >= WIRE_VERSION_V7
        val isV8 = wireVersion >= WIRE_VERSION_V8
        val isV9 = wireVersion >= WIRE_VERSION_V9
        val isV10 = wireVersion >= WIRE_VERSION_V10
        val isV11 = wireVersion >= WIRE_VERSION_V11
        // Accept v1 (legacy), v2, v3 and v4. Newer versions are tolerated via
        // forward-compat parsing as long as the tags are known.
        if (wireVersion !in 1..WIRE_VERSION) {
            if (wireVersion > WIRE_VERSION) {
                android.util.Log.w("SafePdfParser", "Wire version $wireVersion > $WIRE_VERSION, attempting forward compat parse")
            } else {
                throw IllegalArgumentException("Unsupported wire version: $wireVersion")
            }
        }

        val primitives = ArrayList<PdfPrimitive>(count.coerceAtMost(4096))
        // Depth of the soft-mask bracket currently open, and the index at which the
        // outermost still-unterminated one began, so a page cut short can be trimmed back
        // to a well-formed boundary instead of left with a half-applied mask.
        var softMaskDepth = 0
        var outermostSoftMaskStart = -1
        for (primIndex in 0 until count) {
            if (!buf.hasRemaining()) break
            when (val tag = buf.get().toInt() and 0xFF) {
                TAG_TEXT -> {
                    val x = buf.float
                    val y = buf.float
                    val size = buf.float
                    val argb = buf.int
                    val len = buf.short.toInt() and 0xFFFF
                    // No upper-bound rejection: len is u16-bounded and Rust truncates at
                    // MAX_TEXT_BYTES (65535), so anything up to that is legitimate. The old
                    // 4096 cap threw and destroyed the entire page over one long run.
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
                    // u8 on the wire, so the allocation is bounded at 255 regardless; Rust
                    // caps at MAX_DASH_LEN (64). Never reject — the old `> 32` throw
                    // discarded the whole page over one stroke's dash array.
                    val nDash = buf.get().toInt() and 0xFF
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
                    if (w <= 0 || h <= 0 || w > 20000 || h > 20000 ||
                        w.toLong() * h.toLong() > 16 * 1024 * 1024
                    ) {
                        // Unusable dimensions: consume this payload EXACTLY so the stream
                        // stays in sync, then drop the primitive. If it cannot be skipped
                        // cleanly the buffer is untrustworthy, so stop rather than decode
                        // garbage from a desynced offset.
                        val fixed = 1 + (if (isV9) 4 else 0) + (if (isV10) 1 else 0) +
                            (if (isV11) 1 else 0) + 4
                        if (buf.remaining() < fixed) break
                        buf.get() // format
                        if (isV9) buf.float // alpha
                        if (isV10) buf.get() // blend
                        if (isV11) buf.get() // interpolate
                        val skipLen = buf.int
                        if (skipLen < 0 || buf.remaining() < skipLen) break
                        buf.position(buf.position() + skipLen)
                        continue
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
                    // v11: PDF 32000-1 §8.9.5.1 Table 89 — /Interpolate defaults to false and
                    // bilevel art must never be smoothed. Rust decides per image; an older
                    // wire has no opinion, so default to smoothing as before.
                    val imgInterpolate = if (isV11) {
                        if (buf.remaining() < 1) throw IllegalArgumentException("Image v11 interpolate truncated")
                        buf.get().toInt() != 0
                    } else true
                    val len = buf.int
                    if (len < 0 || len > 16*1024*1024) throw IllegalArgumentException("Image data length out of bounds $len")
                    if (buf.remaining() < len) throw IllegalArgumentException("Image data truncated")
                    val data = ByteArray(len)
                    buf.get(data)
                    val bmp = decodeBitmap(w, h, format, data)
                    primitives.add(PdfPrimitive.Image(ctm, bmp, imgAlpha, imgBlend, imgInterpolate))
                }

                TAG_CLIP_PUSH -> {
                    val evenOdd = buf.get().toInt() != 0
                    val pts = readPoints(buf)
                    val pathOps = if (isV4) readPathOps(buf) else null
                    // Always emit, even when the geometry is degenerate. Rust increments its
                    // clip depth for such a push and so still sends the matching ClipPop;
                    // dropping the push here left that pop unmatched, and it then released an
                    // ENCLOSING clip early — content bleeding outside its box. drawSafePage
                    // already handles a degenerate push by saving without narrowing the clip.
                    primitives.add(PdfPrimitive.ClipPush(evenOdd, pts, pathOps))
                }

                TAG_CLIP_POP -> {
                    primitives.add(PdfPrimitive.ClipPop)
                }

                TAG_TEXT_CLIP_APPLY -> {
                    primitives.add(PdfPrimitive.TextClipApply)
                }

                TAG_GROUP_PUSH -> {
                    if (buf.remaining() < 7) throw IllegalArgumentException("GroupPush truncated")
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
                    if (softMaskDepth == 0) outermostSoftMaskStart = primitives.size
                    softMaskDepth++
                    primitives.add(PdfPrimitive.SoftMaskPush(maskType))
                }

                TAG_SMASK_CONTENT -> {
                    primitives.add(PdfPrimitive.SoftMaskContent)
                }

                TAG_SMASK_POP -> {
                    primitives.add(PdfPrimitive.SoftMaskPop)
                    if (softMaskDepth > 0) softMaskDepth--
                }

                TAG_SMASK_TRANSFER -> {
                    // Consistent with the robustness contract above: a truncated tail means
                    // the buffer ended, so keep the prefix that decoded rather than throwing
                    // and losing the whole page over the last primitive.
                    if (buf.remaining() < TRANSFER_LUT_SIZE) {
                        android.util.Log.w(TAG, "SoftMaskTransfer truncated, truncating page")
                        break
                    }
                    val lut = ByteArray(TRANSFER_LUT_SIZE)
                    buf.get(lut)
                    primitives.add(fitTransferLut(lut))
                }

                TAG_IMAGE_TILED -> {
                    val ctm = FloatArray(6) { buf.float }
                    val w = buf.int
                    val h = buf.int
                    val xstep = buf.float
                    val ystep = buf.float
                    val i0 = buf.int
                    val j0 = buf.int
                    val nxRaw = buf.int
                    val nyRaw = buf.int
                    if (buf.remaining() < 5) throw IllegalArgumentException("ImageTiled truncated")
                    val tileAlpha = buf.float.coerceIn(0f, 1f)
                    val tileBlend = BlendMode.fromCode(buf.get().toInt() and 0xFF)
                    val len = buf.int
                    if (len < 0 || len > 16*1024*1024) throw IllegalArgumentException("ImageTiled data length out of bounds $len")
                    if (buf.remaining() < len) throw IllegalArgumentException("ImageTiled data truncated")
                    val data = ByteArray(len)
                    buf.get(data)
                    // Format 0: the cell is always raw RGBA8888, so there is no format byte.
                    val bmp = decodeBitmap(w, h, 0, data)
                    // The lattice extent only sizes the filled region — a REPEAT shader tiles
                    // infinitely — so an absurd count costs nothing to draw, but clamp it so it
                    // cannot produce non-finite path coordinates.
                    val nx = nxRaw.coerceIn(0, MAX_LATTICE_CELLS)
                    val ny = nyRaw.coerceIn(0, MAX_LATTICE_CELLS)
                    primitives.add(
                        PdfPrimitive.ImageTiled(ctm, bmp, xstep, ystep, i0, j0, nx, ny, tileAlpha, tileBlend)
                    )
                }

                else -> {
                    // The payload length of an unknown tag is unknowable, so the read
                    // position is now inside a payload and every later tag byte would be
                    // random data — which decodes into plausible-looking garbage primitives.
                    // Kotlin knows all 12 tags Rust emits, so an unknown tag means the stream
                    // has desynced: keep what decoded cleanly and stop.
                    android.util.Log.w(
                        TAG,
                        "unknown tag $tag at offset ${buf.position()} (wire v$wireVersion), " +
                            "prim $primIndex of $count — wire desync, truncating page",
                    )
                    break
                }
            }
        }

        // A page cut short — by the count clamp, a desync, or a truncated buffer — can end
        // inside a soft-mask bracket, which renders actively WRONG rather than merely
        // partial: with no mask ever composited the masked content draws fully opaque
        // (a vignette becomes a hard block), and a half-drawn mask erases a ragged region.
        // Trim back to before the bracket, unless that would cost most of the page.
        if (softMaskDepth > 0 && outermostSoftMaskStart >= 0) {
            val dropped = primitives.size - outermostSoftMaskStart
            if (outermostSoftMaskStart >= primitives.size / 2) {
                android.util.Log.w(TAG, "page ended inside a soft-mask bracket, dropping $dropped trailing prims")
                primitives.subList(outermostSoftMaskStart, primitives.size).clear()
            } else {
                android.util.Log.w(TAG, "page ended inside a soft-mask bracket spanning most of the page, keeping it")
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
