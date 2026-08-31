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

    /** A /TR transfer function is transmitted as this many u8 samples over the mask value. */
    private const val TRANSFER_LUT_SIZE = 256
    /**
     * Largest raster payload this decoder will materialise for one image, in bytes.
     *
     * It is a LOCAL heap policy, not a wire invariant: Rust's own bound is looser in both
     * arms that reach it. `extract_image` downscales a decoded raster to 2048px on its long
     * side (16 MB of RGBA at worst, exactly this), but `extract_inline_image` only enforces
     * `MAX_IMAGE_PIXELS` (16 MP -> 64 MB of RGBA), and the format-1 JPEG passthrough hands
     * over the stream bytes with only a 64 MB ceiling on them. So a payload above this is a
     * legitimate stream we choose not to decode, NOT evidence that the buffer is corrupt —
     * which is why exceeding it skips the one primitive instead of ending the page.
     */
    private const val MAX_IMAGE_DATA_BYTES = 16 * 1024 * 1024

    /**
     * Decoded-pixel budget, deliberately a SEPARATE constant from [MAX_IMAGE_DATA_BYTES] even
     * though the two currently hold the same literal: one counts compressed bytes on the wire,
     * the other counts pixels after decode (16 Mi pixels is 64 MB of ARGB_8888). The same
     * conflation exists on the Rust side as `MAX_IMAGE_BYTES` / `MAX_IMAGE_PIXELS`; sharing one
     * literal between the two is how a change to either silently moves the other.
     *
     * Enforced per FORMAT, not globally — see `decodeBitmap`.
     *
     * TWIN of `graphics_state.rs:286`, and the invariant is ONE-DIRECTIONAL:
     * this value must be >= Rust's, never <.
     *
     * `images.rs:848` decimates until `out_w * out_h <= MAX_IMAGE_PIXELS` (Rust's), then the
     * `format == 0` branch of `decodeBitmap` drops anything above this one. If this were
     * LOWERED below Rust's, Rust would emit rasters in the gap believing it had decimated them
     * to fit, and they would vanish here — the large-CCITT bug (F13) silently returning, with
     * both sides individually "correct" and no diagnostic on either. Raising it is safe.
     *
     * Same shape as `wire.rs`'s `WIRE_VERSION <= kotlin_version` check: the consumer may run
     * ahead of the producer, never behind. Written as a bare literal with an explicit type so
     * that check's file-reading approach can be pointed at this line too.
     */
    private const val MAX_IMAGE_PIXELS: Long = 16777216 // 16 * 1024 * 1024

    /**
     * TWIN of `graphics_state.rs:284` (used at `images.rs:1078`), with the same
     * consumer->=producer invariant as [MAX_IMAGE_PIXELS]: Rust refuses an image past its
     * value, so a SMALLER value here drops images Rust considered valid.
     */
    private const val MAX_IMAGE_DIM: Int = 20000
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

    /**
     * This value, or [fallback] when the wire carried a non-finite one.
     *
     * Every float here arrives as four arbitrary bytes across JNI, so NaN and infinity are
     * always representable no matter what the producer guarantees — a desynced buffer alone
     * produces NaN from any bit pattern in the quiet range. They are worth singling out
     * because the usual Kotlin range guards do NOT stop them: `coerceIn` and `coerceAtLeast`
     * are written as `if (this < min) ... else this`, and every comparison against NaN is
     * false, so NaN passes through both untouched and lands in `Paint.textSize`,
     * `Paint.textScaleX` or a selection rectangle. Infinity survives a `> 0f` guard for the
     * same reason and then poisons the running geometry of a whole text run.
     *
     * `draw.rs` also bounds these at the producer, which is the right place for the
     * geometry; this is the decoder refusing to depend on that promise.
     */
    private fun Float.orIfNonFinite(fallback: Float): Float = if (isFinite()) this else fallback

    fun parse(bytes: ByteArray): SafePdfPage {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 12) throw IllegalArgumentException("Buffer too small")

        val firstInt = buf.int
        val wireVersion: Int
        val rawWidth: Float
        val rawHeight: Float
        val rawCount: Int

        if (firstInt == WIRE_MAGIC) {
            if (buf.remaining() < 16) throw IllegalArgumentException("v2/v3 header truncated")
            wireVersion = buf.int
            rawWidth = buf.float
            rawHeight = buf.float
            rawCount = buf.int
        } else {
            // v1 legacy: firstInt was actually width bits, reinterpret
            wireVersion = 1
            rawWidth = java.lang.Float.intBitsToFloat(firstInt)
            rawHeight = buf.float
            rawCount = buf.int
        }

        // Safety guards: count caps, version enforcement, dimension sanity.
        //
        // CLAMP, DO NOT THROW. A throw here happens before the per-primitive guard below, so
        // renderPage turns the whole page into null and the UI shows an indefinite spinner —
        // and the Rust producer carries no matching 20000pt bound, so it can legitimately emit
        // a page this side used to reject. Large-format CAD and poster PDFs land here and every
        // real viewer renders them. An over-large page drawn at a clamped size beats a page that
        // never appears.
        //
        // NaN is handled by the same expression: every comparison against it is false, so it
        // takes the fallback rather than reaching SafePdfPageCanvas, whose
        // `Modifier.aspectRatio(width / height)` requires a ratio > 0 and throws on NaN —
        // taking down the composition rather than the one page.
        val width = if (rawWidth.isFinite() && rawWidth > 0f) rawWidth.coerceAtMost(20000f) else 612f
        val height = if (rawHeight.isFinite() && rawHeight > 0f) rawHeight.coerceAtMost(20000f) else 792f
        if (width != rawWidth || height != rawHeight) {
            android.util.Log.w(TAG, "page dimensions $rawWidth x $rawHeight out of range, clamped to $width x $height")
        }
        val countRaw = rawCount.coerceAtLeast(0)
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
            // Neither direction throws: a throw here becomes a null page and an indefinite
            // spinner. An older-than-known version degrades the same way a newer one does —
            // parse what the tags allow and drop what they do not.
            android.util.Log.w(
                "SafePdfParser",
                "Wire version $wireVersion outside 1..$WIRE_VERSION, attempting best-effort parse",
            )
        }

        val primitives = ArrayList<PdfPrimitive>(count.coerceAtMost(4096))
        // Depth of the soft-mask bracket currently open, and the index at which the
        // outermost still-unterminated one began, so a page cut short can be trimmed back
        // to a well-formed boundary instead of left with a half-applied mask.
        var softMaskDepth = 0
        var outermostSoftMaskStart = -1
        for (primIndex in 0 until count) {
            if (!buf.hasRemaining()) break
            val primStart = buf.position()
            // The robustness contract above: a primitive cut short must keep the clean prefix,
            // never discard the page, because renderPage can only turn a throw into a null page
            // and the UI shows that as an indefinite spinner. The per-field `throw`s below
            // predate that contract and each one killed a whole page over its last primitive;
            // catching here converts every one of them — plus any unguarded relative get that
            // raises BufferUnderflowException — into the same `break` tag 13 already chose.
            // Deliberately not reindented: this wraps the `when` untouched so the guard is a
            // reviewable seven lines rather than a whole-body reindent.
            try {
            when (val tag = buf.get().toInt() and 0xFF) {
                TAG_TEXT -> {
                    val x = buf.float
                    val y = buf.float
                    // Reachable without exotic syntax: an overflowing `Tf` literal times a
                    // zero-scale matrix is `inf * 0` = NaN. Zero here becomes one pixel via
                    // the renderer's floor, rather than a NaN Paint.textSize.
                    val size = buf.float.orIfNonFinite(0f)
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
                            strokeWidth = sWidth.orIfNonFinite(0f)
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
                    // fall back to the size*0.5*len heuristic, and so does a non-finite
                    // one. buildEmbeddedGlyphs guards this with `advance > 0f`, which
                    // INFINITY passes, and it then scales the whole run's selection
                    // geometry to infinity off one overflowed glyph.
                    val advHeuristic = size * 0.5f * txt.length.coerceAtLeast(1)
                    val adv = if (isV7) {
                        if (buf.remaining() < 4) throw IllegalArgumentException("Text v7 advance truncated")
                        buf.float.orIfNonFinite(advHeuristic)
                    } else {
                        advHeuristic
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
                        // Fall back to the identity rather than trusting the producer to
                        // have bounded this. Rust bounds the geometric half (draw.rs
                        // `aniso.clamp`) but the Tz half is a bare `v / 100.0` at
                        // interpret.rs, and these bytes are the trust boundary regardless.
                        hScale = buf.float.orIfNonFinite(1f)
                    } else {
                        isBold = false
                        isItalic = false
                        fontFamily = 0
                        outline = false
                        hScale = 1f
                    }
                    // Unlike the scalars above there is no sane default for a position, and
                    // a NaN origin is not harmless: it paints nothing but still produces a
                    // selection rectangle whose distance comparisons are all false, which
                    // breaks nearestGlyph for the page. Every byte of this primitive has
                    // been consumed, so dropping it leaves the stream in sync, and Text
                    // carries no bracket that a later primitive is paired with.
                    if (!x.isFinite() || !y.isFinite()) {
                        android.util.Log.w(TAG, "text primitive $primIndex has a non-finite origin, dropping it")
                        continue
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
                    // u8 on the wire, so the allocation is bounded at 255 regardless.
                    // Rust's `MAX_DASH_LEN` is 32 (graphics_state.rs), not 64 — but
                    // `draw::emit_stroke` duplicates an odd-length array, since §8.4.3.6
                    // needs an even number of on/off phases, so the count actually EMITTED
                    // can be up to twice that. 64 is that doubled worst case, not the
                    // constant. Never reject — the old `> 32` throw discarded the whole
                    // page over one stroke's dash array, and nothing here depends on the
                    // producer's bound anyway.
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
                    if (w <= 0 || h <= 0 || w > MAX_IMAGE_DIM || h > MAX_IMAGE_DIM) {
                        // Unusable dimensions: consume this payload EXACTLY so the stream
                        // stays in sync, then drop the primitive. If it cannot be skipped
                        // cleanly the buffer is untrustworthy, so stop rather than decode
                        // garbage from a desynced offset.
                        //
                        // The PIXEL budget is deliberately NOT tested here. `format` is not
                        // read until below, so a test here can only reject every format
                        // alike — and Rust drops its own pixel guard for the JPEG
                        // passthrough precisely because the platform decoder subsamples it
                        // (images.rs:1082-1087). Rejecting here deleted every JPEG over
                        // 16 Mpx, which is an ordinary phone photo, with no bitmap, no log
                        // and nothing in logcat. The budget now applies per format in
                        // `decodeBitmap`, where the memory is actually committed.
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
                    if (len < 0) throw IllegalArgumentException("Negative image data length $len")
                    if (buf.remaining() < len) throw IllegalArgumentException("Image data truncated")
                    if (len > MAX_IMAGE_DATA_BYTES) {
                        // The buffer is intact here — the length field is exactly how many
                        // bytes to step over — so this resyncs. Throwing instead would break
                        // out of the loop and discard every LATER primitive too, blanking the
                        // rest of the page below a single over-sized JPEG or inline image.
                        android.util.Log.w(
                            TAG,
                            "image payload $len exceeds $MAX_IMAGE_DATA_BYTES, dropping this " +
                                "image and continuing the page",
                        )
                        buf.position(buf.position() + len)
                        continue
                    }
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
                    if (len < 0) throw IllegalArgumentException("Negative ImageTiled data length $len")
                    if (buf.remaining() < len) throw IllegalArgumentException("ImageTiled data truncated")
                    if (len > MAX_IMAGE_DATA_BYTES) {
                        android.util.Log.w(
                            TAG,
                            "tiling cell payload $len exceeds $MAX_IMAGE_DATA_BYTES, dropping " +
                                "this pattern and continuing the page",
                        )
                        buf.position(buf.position() + len)
                        continue
                    }
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
                    // Kotlin knows all 14 tags Rust emits, so an unknown tag means the stream
                    // has desynced: keep what decoded cleanly and stop.
                    android.util.Log.w(
                        TAG,
                        "unknown tag $tag at offset ${buf.position()} (wire v$wireVersion), " +
                            "prim $primIndex of $count — wire desync, truncating page",
                    )
                    break
                }
            }
            } catch (e: RuntimeException) {
                android.util.Log.w(
                    TAG,
                    "primitive $primIndex of $count failed to decode at offset $primStart " +
                        "(wire v$wireVersion), keeping the ${primitives.size} that decoded cleanly",
                    e,
                )
                break
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
        val out = ArrayList<SafeAnnotation>(buf.listCapacity(count, ANNOTATION_MIN_BYTES))
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
        val out = ArrayList<SafeFormField>(buf.listCapacity(count, FORM_FIELD_MIN_BYTES))
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
        val out = ArrayList<SafeSearchMatch>(buf.listCapacity(count, SEARCH_MATCH_BYTES))
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
        val out = ArrayList<SafeLink>(buf.listCapacity(count, LINK_MIN_BYTES))
        repeat(count) {
            val x0 = buf.float; val y0 = buf.float; val x1 = buf.float; val y1 = buf.float
            val dest = buf.int
            val uri = readString(buf)
            out.add(SafeLink(x0, y0, x1, y1, dest, uri))
        }
        return out
    }

    /**
     * Initial capacity for a listing whose header claims [count] records of at least
     * [minRecordBytes] each.
     *
     * The count is read straight off the wire, so a bare `ArrayList(count)` allocates an
     * `Object[count]` before a single record has been validated — a corrupt or truncated
     * header reading as ~2e9 raises OutOfMemoryError, which is an Error and so escapes
     * every `runCatching` between here and the composition rather than degrading to an
     * empty listing. The buffer cannot physically hold more than
     * `remaining / minRecordBytes` records, so cap on that: it can never clip a count a
     * well-formed buffer could justify, and it needs no invented constant. The `repeat`
     * loop still runs to `count` and stops on the underflow, so this only bounds the
     * pre-allocation, never the result.
     */
    private fun ByteBuffer.listCapacity(count: Int, minRecordBytes: Int): Int =
        count.coerceIn(0, remaining() / minRecordBytes)

    /** u64 id + u8 subtype + 4xf32 rect + u32 colour + the u16 /Contents length. */
    private const val ANNOTATION_MIN_BYTES = 8 + 1 + 16 + 4 + 2
    /** u64 id + u8 type + 4xf32 rect + two u16 string lengths + u8 checked. */
    private const val FORM_FIELD_MIN_BYTES = 8 + 1 + 16 + 2 + 2 + 1
    /** u32 page + 4xf32 rect, fixed width. */
    private const val SEARCH_MATCH_BYTES = 4 + 16
    /** 4xf32 rect + i32 destination page + the u16 URI length. */
    private const val LINK_MIN_BYTES = 16 + 4 + 2
    /** u16 level + i32 page + the u16 title length. */
    private const val OUTLINE_MIN_BYTES = 2 + 4 + 2

    /**
     * A u16-length-prefixed UTF-8 string, as every listing buffer writes them.
     *
     * No upper-bound rejection. Rust truncates each of these at `u16::MAX` — annotation
     * /Contents (annotations.rs:1980), form field /T and /V (forms.rs:452), a link URI
     * (forms.rs:351) and an outline title (forms.rs:1088) all use
     * `b.len().min(u16::MAX as usize)` — so any length the field can express is a length the
     * producer will legitimately send. The old 4096 cap sat below that and THREW on a longer
     * one, which is not survivable here the way it is inside [parse]: these parsers have no
     * per-record recovery, so a single 5 KB sticky-note comment or multi-line form value took
     * out the whole listing, and the throw propagates out of [SafePdfDocument.annotations] and
     * its siblings — none of which catch — into the composition. Exactly the reasoning already
     * applied to the Text primitive's length above.
     *
     * `len` is u16-bounded, so the allocation is capped at 64 KB regardless, and the remaining
     * check below is the real guard against a truncated buffer.
     */
    private fun readString(buf: ByteBuffer): String {
        if (buf.remaining() < 2) throw IllegalArgumentException("readString header truncated")
        val len = buf.short.toInt() and 0xFFFF
        if (buf.remaining() < len) throw IllegalArgumentException("readString truncated len=$len remaining=${buf.remaining()}")
        val b = ByteArray(len)
        buf.get(b)
        return String(b, Charsets.UTF_8)
    }

    /** Decode the outline buffer from `listOutline`. */
    fun parseOutline(bytes: ByteArray): List<SafeOutlineItem> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeOutlineItem>(buf.listCapacity(count, OUTLINE_MIN_BYTES))
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

    /**
     * Smallest power-of-two subsample bringing `w*h` within [MAX_IMAGE_PIXELS].
     * `BitmapFactory` rounds `inSampleSize` DOWN to a power of two, so compute one directly
     * rather than hand it a ratio it would round the wrong way (a rounded-down sample size
     * decodes LARGER than asked, which is the direction that OOMs).
     */
    internal fun sampleSizeFor(w: Int, h: Int): Int {
        var s = 1
        while ((w.toLong() / s) * (h.toLong() / s) > MAX_IMAGE_PIXELS) s = s shl 1
        return s
    }

    /** Decode an image payload: format 1 = JPEG bytes, 0 = raw RGBA8888. */
    private fun decodeBitmap(w: Int, h: Int, format: Int, data: ByteArray): android.graphics.Bitmap? {
        if (w <= 0 || h <= 0 || w > MAX_IMAGE_DIM || h > MAX_IMAGE_DIM) return null
        // The pixel budget is per format. The raw branch below allocates `w*h` ints before it
        // can do anything, so it must be refused outright — and Rust decimates that path, so
        // an oversized raw image is a contract violation rather than ordinary input. A JPEG
        // commits nothing until the decoder runs and is scaled down there instead.
        if (format != 1 && w.toLong() * h.toLong() > MAX_IMAGE_PIXELS) return null
        return try {
            when (format) {
                1 -> {
                    if (data.size > MAX_IMAGE_DATA_BYTES) {
                        android.util.Log.w("SafePdfParser", "JPEG too large ${data.size}")
                        null
                    } else {
                        // Subsample instead of dropping. Rust hands the JPEG over at full
                        // dimensions deliberately (images.rs:1082-1087) because this decoder
                        // is what is supposed to scale it; without inSampleSize it decodes at
                        // full size, so a 20 MP photo would commit ~80 MB of ARGB_8888.
                        val opts = android.graphics.BitmapFactory.Options()
                        opts.inSampleSize = sampleSizeFor(w, h)
                        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, opts)
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
