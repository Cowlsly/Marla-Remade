package com.vayunmathur.pdf.util

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-decoding tests for [SafePdfParser].
 *
 * Byte buffers are built by [WireWriter], transcribed from `wire::serialize` in
 * `pdf/src/main/rust/src/wire.rs`, so a field that moves on one side and not the other
 * shows up here as a decode mismatch rather than as a silently garbled page.
 */
class SafePdfParserTest {

    // ---- header and version compatibility ----

    /**
     * Rust greps these two declarations out of the Kotlin source
     * (`wire::tests::wire_version_is_not_ahead_of_the_kotlin_parser`). Pinning them here
     * means a rename or reformat that breaks that grep fails a Kotlin test too, instead of
     * silently disabling the cross-language check.
     */
    @Test
    fun wireConstantsAreThePinnedValues() {
        assertEquals(0x50444657, SafePdfParser.WIRE_MAGIC)
        assertEquals(11, SafePdfParser.WIRE_VERSION)
    }

    /**
     * Rust declares `WIRE_VERSION = 10` and Kotlin understands up to 11. Every tag must
     * decode from the version Rust actually emits — a mishandled version renders every page
     * of every document blank.
     */
    @Test
    fun theVersionRustActuallyEmitsDecodesEveryTag() {
        val page = SafePdfParser.parse(
            WireWriter(version = 10)
                .text(renderMode = 2)
                .fill()
                .stroke(dash = floatArrayOf(3f, 2f))
                .image()
                .imageTiled()
                .clipPush()
                .clipPop()
                .groupPush()
                .groupPop()
                .textClipApply()
                .softMaskPush()
                .softMaskContent()
                .softMaskPop()
                .build()
        )
        assertEquals(13, page.primitives.size)
        assertIs<PdfPrimitive.Text>(page.primitives[0])
        assertIs<PdfPrimitive.FillPath>(page.primitives[1])
        assertIs<PdfPrimitive.StrokePath>(page.primitives[2])
        assertIs<PdfPrimitive.Image>(page.primitives[3])
        assertIs<PdfPrimitive.ImageTiled>(page.primitives[4])
        assertIs<PdfPrimitive.ClipPush>(page.primitives[5])
        assertEquals(PdfPrimitive.ClipPop, page.primitives[6])
        assertIs<PdfPrimitive.GroupPush>(page.primitives[7])
        assertEquals(PdfPrimitive.GroupPop, page.primitives[8])
        assertEquals(PdfPrimitive.TextClipApply, page.primitives[9])
        assertIs<PdfPrimitive.SoftMaskPush>(page.primitives[10])
        assertEquals(PdfPrimitive.SoftMaskContent, page.primitives[11])
        assertEquals(PdfPrimitive.SoftMaskPop, page.primitives[12])
    }

    @Test
    fun pageDimensionsComeFromTheHeader() {
        val page = SafePdfParser.parse(WireWriter(width = 595f, height = 842f).fill().build())
        assertEquals(595f, page.width)
        assertEquals(842f, page.height)
    }

    /** A legacy v1 buffer has no magic; the first f32 is the width. */
    @Test
    fun legacyV1HeaderIsReinterpretedAsWidth() {
        val page = SafePdfParser.parse(
            WireWriter(version = 1, width = 300f, height = 400f, legacyV1 = true).fill().build()
        )
        assertEquals(300f, page.width)
        assertEquals(400f, page.height)
        // v1 gates every later field off: one contour read as a bare polyline, no blend byte.
        val fill = assertIs<PdfPrimitive.FillPath>(page.primitives.single())
        assertEquals(1, fill.contours.size)
        assertEquals(BlendMode.Normal, fill.blend)
    }

    /** A version above what Kotlin knows must warn and carry on, never throw. */
    @Test
    fun aFutureWireVersionIsToleratedRatherThanRejected() {
        val page = SafePdfParser.parse(WireWriter(version = 99).fill().build())
        assertEquals(1, page.primitives.size)
    }

    @Test
    fun versionZeroIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            SafePdfParser.parse(WireWriter(version = 0).fill().build())
        }
    }

    @Test
    fun implausiblePageDimensionsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            SafePdfParser.parse(WireWriter(width = 0f).fill().build())
        }
        assertFailsWith<IllegalArgumentException> {
            SafePdfParser.parse(WireWriter(height = 50_000f).fill().build())
        }
    }

    @Test
    fun aBufferTooSmallForAHeaderIsRejected() {
        assertFailsWith<IllegalArgumentException> { SafePdfParser.parse(ByteArray(4)) }
    }

    // ---- per-tag decoding ----

    @Test
    fun textDecodesEveryV11Field() {
        val page = SafePdfParser.parse(
            WireWriter()
                .text(
                    x = 12.5f, y = 34.25f, size = 18f, argb = 0xFF203040.toInt(), text = "Hi \u00e9",
                    strokeArgb = 0xFF010203.toInt(), strokeWidth = 1.5f, renderMode = 2, blend = 1,
                    advance = 42.5f, bold = true, italic = true, family = 2, outline = true, hScale = 0.75f,
                )
                .build()
        )
        val t = assertIs<PdfPrimitive.Text>(page.primitives.single())
        assertEquals(Offset(12.5f, 34.25f), t.origin)
        assertEquals(18f, t.size)
        assertEquals(0xFF203040.toInt(), t.color)
        assertEquals("Hi \u00e9", t.text)
        assertEquals(0xFF010203.toInt(), t.strokeColor)
        assertEquals(1.5f, t.strokeWidth)
        assertEquals(2, t.renderMode)
        assertEquals(BlendMode.Multiply, t.blend)
        assertEquals(42.5f, t.advance)
        assertTrue(t.isBold)
        assertTrue(t.isItalic)
        assertEquals(2, t.fontFamily)
        assertTrue(t.outline)
        assertEquals(0.75f, t.hScale)
    }

    /**
     * Rust writes a zeroed stroke triple when there is no stroke, and the `hasStroke` flag is
     * what distinguishes that from a real black hairline stroke.
     */
    @Test
    fun textWithoutTheStrokeFlagHasNoStrokeColour() {
        val page = SafePdfParser.parse(WireWriter().text(strokeArgb = null).build())
        val t = assertIs<PdfPrimitive.Text>(page.primitives.single())
        assertNull(t.strokeColor)
        assertEquals(0f, t.strokeWidth)
    }

    /** The font-flag bit layout: bit 0 bold, bit 1 italic, bits 2-3 family, bit 4 outline. */
    @Test
    fun fontFlagBitsAreUnpackedIndependently() {
        fun flags(bold: Boolean, italic: Boolean, family: Int, outline: Boolean): PdfPrimitive.Text {
            val page = SafePdfParser.parse(
                WireWriter().text(bold = bold, italic = italic, family = family, outline = outline).build()
            )
            return assertIs(page.primitives.single())
        }
        flags(bold = true, italic = false, family = 0, outline = false).let {
            assertTrue(it.isBold); assertTrue(!it.isItalic); assertEquals(0, it.fontFamily); assertTrue(!it.outline)
        }
        flags(bold = false, italic = true, family = 1, outline = false).let {
            assertTrue(!it.isBold); assertTrue(it.isItalic); assertEquals(1, it.fontFamily)
        }
        flags(bold = false, italic = false, family = 0, outline = true).let {
            assertTrue(it.outline); assertEquals(0, it.fontFamily)
        }
    }

    /** Before v7 there is no advance on the wire, so the heuristic must fill in. */
    @Test
    fun preV7TextFallsBackToTheAdvanceHeuristic() {
        val page = SafePdfParser.parse(WireWriter(version = 6).text(size = 10f, text = "abcd").build())
        val t = assertIs<PdfPrimitive.Text>(page.primitives.single())
        assertEquals(10f * 0.5f * 4, t.advance)
    }

    @Test
    fun aBlendCodeOutsideTheEnumFallsBackToNormal() {
        val page = SafePdfParser.parse(WireWriter().text(blend = 200).build())
        val t = assertIs<PdfPrimitive.Text>(page.primitives.single())
        assertEquals(BlendMode.Normal, t.blend)
    }

    /**
     * Rust sends the blend mode as the PDF 32000-1 §11.3.5 index, so every code 0..15 must
     * round-trip to a distinct mode. A duplicated or missing entry silently substitutes the
     * wrong separable blend for a whole class of transparency groups, and because the fallback
     * is `Normal` a gap looks exactly like "no blending requested".
     */
    @Test
    fun everyBlendCodeOnTheWireDecodesToItsOwnMode() {
        val decoded = (0..15).map { code ->
            val page = SafePdfParser.parse(WireWriter().text(blend = code).build())
            assertIs<PdfPrimitive.Text>(page.primitives.single()).blend
        }
        assertEquals(16, decoded.toSet().size, "two wire blend codes decoded to the same mode")
        decoded.forEachIndexed { code, mode -> assertEquals(code, mode.code) }
    }

    /**
     * v6 carries a contour count so holes and glyph counters arrive as separate contours of
     * one path; flattening them into a single contour would fill the holes in.
     */
    @Test
    fun fillDecodesMultipleContours() {
        val outer = listOf(0f to 0f, 20f to 0f, 20f to 20f, 0f to 20f)
        val hole = listOf(5f to 5f, 15f to 5f, 15f to 15f, 5f to 15f)
        val page = SafePdfParser.parse(
            WireWriter().fill(argb = 0xFFAABBCC.toInt(), evenOdd = true, contours = listOf(outer, hole), blend = 4)
                .build()
        )
        val f = assertIs<PdfPrimitive.FillPath>(page.primitives.single())
        assertEquals(0xFFAABBCC.toInt(), f.color)
        assertTrue(f.evenOdd)
        assertEquals(2, f.contours.size)
        assertEquals(Offset(5f, 5f), f.contours[1][0])
        assertEquals(BlendMode.Darken, f.blend)
    }

    @Test
    fun strokeDecodesDashCapJoinAndMiter() {
        val page = SafePdfParser.parse(
            WireWriter().stroke(
                argb = 0xFF00FF00.toInt(), width = 3.5f, dash = floatArrayOf(4f, 2f, 1f),
                dashPhase = 1.25f, cap = 1, join = 2, miter = 4f,
                pts = listOf(1f to 2f, 3f to 4f, 5f to 6f), blend = 2,
            ).build()
        )
        val s = assertIs<PdfPrimitive.StrokePath>(page.primitives.single())
        assertEquals(0xFF00FF00.toInt(), s.color)
        assertEquals(3.5f, s.width)
        assertContentEquals(floatArrayOf(4f, 2f, 1f), s.dash)
        assertEquals(1.25f, s.dashPhase)
        assertEquals(1, s.cap)
        assertEquals(2, s.join)
        assertEquals(4f, s.miter)
        assertEquals(3, s.points.size)
        assertEquals(Offset(5f, 6f), s.points[2])
        assertEquals(BlendMode.Screen, s.blend)
    }

    /**
     * The image payload is the only one whose length depends on the wire version, so it is
     * the one that desyncs the rest of the buffer if a gate is wrong. At v10 — what Rust
     * emits today — no `/Interpolate` byte is present, and a parser that read one anyway
     * would consume the next primitive's tag.
     */
    @Test
    fun theImagePayloadLengthTracksTheDeclaredVersion() {
        val v10 = SafePdfParser.parse(WireWriter(version = 10).image().fill().build())
        assertEquals(2, v10.primitives.size)
        assertTrue(assertIs<PdfPrimitive.Image>(v10.primitives[0]).interpolate, "pre-v11 defaults to smoothing")
        assertIs<PdfPrimitive.FillPath>(v10.primitives[1])

        val v11 = SafePdfParser.parse(WireWriter(version = 11).image(interpolate = false).fill().build())
        assertEquals(2, v11.primitives.size)
        assertTrue(!assertIs<PdfPrimitive.Image>(v11.primitives[0]).interpolate)
        assertIs<PdfPrimitive.FillPath>(v11.primitives[1])
    }

    @Test
    fun imageDecodesCtmAlphaAndBlend() {
        val ctm = floatArrayOf(100f, 1f, 2f, 200f, 30f, 40f)
        val page = SafePdfParser.parse(
            WireWriter().image(ctm = ctm, alpha = 0.5f, blend = 1).fill().build()
        )
        val img = assertIs<PdfPrimitive.Image>(page.primitives[0])
        assertContentEquals(ctm, img.ctm)
        assertEquals(0.5f, img.alpha)
        assertEquals(BlendMode.Multiply, img.blend)
    }

    @Test
    fun imageAlphaIsClampedToTheUnitRange() {
        val page = SafePdfParser.parse(WireWriter().image(alpha = 4f).image(alpha = -2f).build())
        assertEquals(1f, assertIs<PdfPrimitive.Image>(page.primitives[0]).alpha)
        assertEquals(0f, assertIs<PdfPrimitive.Image>(page.primitives[1]).alpha)
    }

    /**
     * An unusable size must consume its payload EXACTLY and drop only that primitive; a
     * short-read here would desync every primitive after it.
     */
    @Test
    fun anImageWithUnusableDimensionsIsSkippedWithoutDesyncingTheStream() {
        val page = SafePdfParser.parse(
            WireWriter().image(w = 0, h = 0, data = ByteArray(64)).fill(argb = 0xFF121212.toInt()).build()
        )
        val fill = assertIs<PdfPrimitive.FillPath>(page.primitives.single())
        assertEquals(0xFF121212.toInt(), fill.color)
    }

    /**
     * An oversized raster payload is NOT a corrupt buffer, and treating it as one blanks the
     * rest of the page.
     *
     * Rust's producer bound is looser than the 16 MB this decoder is willing to materialise:
     * `extract_inline_image` only enforces `MAX_IMAGE_PIXELS` (16 MP, i.e. 64 MB of RGBA) and
     * the format-1 JPEG passthrough forwards the stream bytes with a 64 MB ceiling. A 20 MB
     * photo scan is therefore an ordinary, well-formed stream. The length field says exactly
     * how many bytes to step over, so the decoder must drop that one image and carry on —
     * ending the page instead would lose every LATER primitive as well.
     */
    @Test
    fun anOversizedImagePayloadDropsOnlyThatImage() {
        val page = SafePdfParser.parse(
            WireWriter()
                .image(w = 8, h = 8, data = ByteArray(16 * 1024 * 1024 + 1))
                .fill(argb = 0xFF191919.toInt())
                .stroke(argb = 0xFF282828.toInt())
                .build()
        )
        assertEquals(
            2,
            page.primitives.size,
            "an over-sized image must cost one primitive, not the rest of the page",
        )
        assertEquals(0xFF191919.toInt(), assertIs<PdfPrimitive.FillPath>(page.primitives[0]).color)
        assertEquals(0xFF282828.toInt(), assertIs<PdfPrimitive.StrokePath>(page.primitives[1]).color)
    }

    /** Same contract for a tiling-pattern cell (tag 14). */
    @Test
    fun anOversizedTilingCellDropsOnlyThatPattern() {
        val page = SafePdfParser.parse(
            WireWriter()
                .imageTiled(w = 4, h = 4, data = ByteArray(16 * 1024 * 1024 + 1))
                .fill(argb = 0xFF373737.toInt())
                .build()
        )
        assertEquals(0xFF373737.toInt(), assertIs<PdfPrimitive.FillPath>(page.primitives.single()).color)
    }

    /**
     * A payload the buffer cannot actually contain is a different case: there is nothing to
     * skip to, so the page keeps its clean prefix and stops. This pins the two apart, so the
     * skip above cannot be widened into "ignore truncation".
     */
    @Test
    fun anImagePayloadLongerThanTheBufferStopsAfterThePrefix() {
        val whole = WireWriter().fill(argb = 0xFF464646.toInt()).image(data = ByteArray(64)).build()
        val page = SafePdfParser.parse(whole.copyOf(whole.size - 32))
        assertEquals(0xFF464646.toInt(), assertIs<PdfPrimitive.FillPath>(page.primitives.single()).color)
    }

    @Test
    fun imageTiledDecodesTheLatticeAndCell() {
        val ctm = floatArrayOf(8f, 0f, 0f, 8f, 5f, 6f)
        val page = SafePdfParser.parse(
            WireWriter().imageTiled(
                ctm = ctm, xstep = 9f, ystep = 11f, i0 = -3, j0 = -4, nx = 7, ny = 8,
                alpha = 0.25f, blend = 1,
            ).build()
        )
        val t = assertIs<PdfPrimitive.ImageTiled>(page.primitives.single())
        assertContentEquals(ctm, t.ctm)
        assertEquals(9f, t.xstep)
        assertEquals(11f, t.ystep)
        assertEquals(-3, t.i0)
        assertEquals(-4, t.j0)
        assertEquals(7, t.nx)
        assertEquals(8, t.ny)
        assertEquals(0.25f, t.alpha)
        assertEquals(BlendMode.Multiply, t.blend)
    }

    /** An absurd lattice extent must clamp, so the region path cannot get non-finite coords. */
    @Test
    fun anAbsurdLatticeExtentIsClamped() {
        val page = SafePdfParser.parse(
            WireWriter().imageTiled(nx = Int.MAX_VALUE, ny = -5).build()
        )
        val t = assertIs<PdfPrimitive.ImageTiled>(page.primitives.single())
        assertEquals(100_000, t.nx)
        assertEquals(0, t.ny)
    }

    @Test
    fun clipPushCarriesBothThePolylineAndTheBezierPath() {
        val ops = listOf(
            PathOp.Move(1f, 2f),
            PathOp.Line(3f, 4f),
            PathOp.Cubic(5f, 6f, 7f, 8f, 9f, 10f),
            PathOp.Close,
        )
        val page = SafePdfParser.parse(
            WireWriter().clipPush(evenOdd = true, pts = WireWriter.square(), pathOps = ops).build()
        )
        val c = assertIs<PdfPrimitive.ClipPush>(page.primitives.single())
        assertTrue(c.evenOdd)
        assertEquals(4, c.points.size)
        assertEquals(ops, c.pathOps)
    }

    /**
     * Rust increments its clip depth for a degenerate push and still sends the matching pop,
     * so dropping the push here would let that pop release an ENCLOSING clip early.
     */
    @Test
    fun aDegenerateClipPushIsStillEmitted() {
        val page = SafePdfParser.parse(
            WireWriter().clipPush(pts = emptyList()).clipPop().build()
        )
        assertEquals(2, page.primitives.size)
        val c = assertIs<PdfPrimitive.ClipPush>(page.primitives[0])
        assertTrue(c.points.isEmpty())
        assertEquals(PdfPrimitive.ClipPop, page.primitives[1])
    }

    @Test
    fun groupPushDecodesFlagsAlphaAndBlend() {
        val page = SafePdfParser.parse(
            WireWriter().groupPush(isolated = true, knockout = true, alpha = 0.5f, blend = 15).build()
        )
        val g = assertIs<PdfPrimitive.GroupPush>(page.primitives.single())
        assertTrue(g.isolated)
        assertTrue(g.knockout)
        assertEquals(0.5f, g.alpha)
        assertEquals(BlendMode.Luminosity, g.blend)
    }

    @Test
    fun softMaskPushCarriesTheMaskType() {
        val page = SafePdfParser.parse(
            WireWriter().softMaskPush(1).softMaskContent().softMaskPop().build()
        )
        assertEquals(1, assertIs<PdfPrimitive.SoftMaskPush>(page.primitives[0]).maskType)
    }

    // ---- /TR transfer function fitting ----

    /** An identity /TR must fit exactly as gain 1, bias 0 — the mask passes through. */
    @Test
    fun anIdentityTransferFitsAsIdentity() {
        val lut = ByteArray(256) { it.toByte() }
        val tr = fitOf(lut)
        assertTrue(tr.affine)
        assertEquals(1f, tr.gain, 1e-4f)
        assertEquals(0f, tr.bias, 1e-4f)
    }

    /**
     * An inverting /TR is the standard "mask out where the group is bright" idiom, so getting
     * this wrong hides exactly the wrong half of the group.
     */
    @Test
    fun anInvertingTransferFitsAsNegativeGain() {
        val lut = ByteArray(256) { (255 - it).toByte() }
        val tr = fitOf(lut)
        assertTrue(tr.affine)
        assertEquals(-1f, tr.gain, 1e-4f)
        assertEquals(1f, tr.bias, 1e-4f)
    }

    @Test
    fun aConstantTransferFitsAsZeroGain() {
        val lut = ByteArray(256) { 128.toByte() }
        val tr = fitOf(lut)
        assertTrue(tr.affine)
        assertEquals(0f, tr.gain, 1e-4f)
        assertEquals(128f / 255f, tr.bias, 1e-4f)
    }

    /**
     * A curve no straight line can represent must be reported non-affine and left alone; the
     * documented choice is to skip it rather than approximate it, because a wrong curve hides
     * the wrong half of the group.
     */
    @Test
    fun aStepTransferIsReportedNonAffine() {
        val lut = ByteArray(256) { if (it < 128) 0 else 255.toByte() }
        assertTrue(!fitOf(lut).affine)
    }

    private fun fitOf(lut: ByteArray): PdfPrimitive.SoftMaskTransfer {
        val page = SafePdfParser.parse(
            WireWriter().softMaskPush().softMaskTransfer(lut).softMaskContent().softMaskPop().build()
        )
        return assertIs(page.primitives[1])
    }

    // ---- robustness: the page must survive a bad stream ----

    /**
     * An unknown tag means the read position is already inside a payload, so every later tag
     * byte is random data that decodes into plausible-looking garbage. Keep the clean prefix
     * and stop; do NOT keep parsing from a desynced offset.
     */
    @Test
    fun anUnknownTagTruncatesInsteadOfManufacturingGarbage() {
        val page = SafePdfParser.parse(
            WireWriter()
                .fill(argb = 0xFF010101.toInt())
                .rawTag(200)
                .fill(argb = 0xFF020202.toInt())
                .build()
        )
        val fill = assertIs<PdfPrimitive.FillPath>(page.primitives.single())
        assertEquals(0xFF010101.toInt(), fill.color)
    }

    /**
     * The count is a backstop against a corrupt header field, not a truncation policy: over
     * the cap it must clamp and render a partial page, because a throw discards the page.
     */
    @Test
    fun aCountOverTheCapClampsRatherThanDiscardingThePage() {
        val page = SafePdfParser.parse(
            WireWriter().fill().fill().apply { declaredCount = SafePdfParser.MAX_PRIMITIVES + 7 }.build()
        )
        assertEquals(2, page.primitives.size)
    }

    /** A count larger than the buffer holds must stop at the end, not throw. */
    @Test
    fun aCountLargerThanTheBufferStopsCleanly() {
        val page = SafePdfParser.parse(
            WireWriter().fill().fill().apply { declaredCount = 500 }.build()
        )
        assertEquals(2, page.primitives.size)
    }

    @Test
    fun aNegativeCountIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            SafePdfParser.parse(WireWriter().fill().apply { declaredCount = -1 }.build())
        }
    }

    /** A truncated /TR tail keeps the prefix rather than losing the page over one primitive. */
    @Test
    fun aTruncatedTransferLutTruncatesThePage() {
        val full = WireWriter().fill(argb = 0xFF030303.toInt()).softMaskTransfer(ByteArray(256)).build()
        val page = SafePdfParser.parse(full.copyOf(full.size - 100))
        val fill = assertIs<PdfPrimitive.FillPath>(page.primitives.single())
        assertEquals(0xFF030303.toInt(), fill.color)
    }

    /**
     * A page that ends inside a soft-mask bracket renders actively WRONG, not merely partial:
     * with no mask ever composited the masked content draws fully opaque, so a vignette
     * becomes a hard block. Trim back to the bracket.
     */
    @Test
    fun aPageEndingInsideASoftMaskBracketIsTrimmedBackToIt() {
        val page = SafePdfParser.parse(
            WireWriter().fill().fill().fill().softMaskPush().fill().build()
        )
        assertEquals(3, page.primitives.size)
        assertTrue(page.primitives.all { it is PdfPrimitive.FillPath })
    }

    /** Unless trimming would cost most of the page, in which case the partial mask stays. */
    @Test
    fun anUnterminatedBracketSpanningMostOfThePageIsKept() {
        val page = SafePdfParser.parse(
            WireWriter().softMaskPush().fill().fill().fill().build()
        )
        assertEquals(4, page.primitives.size)
        assertIs<PdfPrimitive.SoftMaskPush>(page.primitives[0])
    }

    /** A balanced bracket is never trimmed. */
    @Test
    fun aBalancedSoftMaskBracketSurvives() {
        val page = SafePdfParser.parse(
            WireWriter().softMaskPush().fill().softMaskContent().fill().softMaskPop().build()
        )
        assertEquals(5, page.primitives.size)
    }

    /**
     * The robustness contract on [SafePdfParser]: a buffer cut off mid-primitive must keep the
     * prefix that decoded cleanly. [SafePdfDocument.renderPage] can only turn a throw into a
     * null page — an indefinite spinner — so throwing here loses a page that was almost
     * entirely readable. Tag 13 already chose `break` for this case; the per-field throws now
     * funnel into the same path.
     */
    @Test
    fun aBufferCutMidPrimitiveKeepsThePrefixInsteadOfLosingThePage() {
        val full = WireWriter().fill(argb = 0xFF040404.toInt()).text(text = "hello world").build()
        val page = SafePdfParser.parse(full.copyOf(full.size - 6))
        val fill = assertIs<PdfPrimitive.FillPath>(page.primitives.single())
        assertEquals(0xFF040404.toInt(), fill.color)
    }

    /**
     * The same for a primitive whose very first fixed field is cut off, which raises
     * `BufferUnderflowException` from an unguarded relative get rather than the parser's own
     * `IllegalArgumentException`. Both must truncate, not propagate.
     */
    @Test
    fun aBufferCutAtAPrimitiveHeaderAlsoKeepsThePrefix() {
        val full = WireWriter().fill(argb = 0xFF050505.toInt()).stroke().build()
        // Leave the stroke's tag byte in place but almost none of its payload.
        val page = SafePdfParser.parse(full.copyOf(full.size - 30))
        val fill = assertIs<PdfPrimitive.FillPath>(page.primitives.single())
        assertEquals(0xFF050505.toInt(), fill.color)
    }

    @Test
    fun anEmptyPageDecodesToNoPrimitives() {
        val page = SafePdfParser.parse(WireWriter().build())
        assertEquals(0, page.primitives.size)
    }

    // ---- listing buffers ----

    @Test
    fun readStringRejectsALengthTheBufferCannotHold() {
        val bytes = java.nio.ByteBuffer.allocate(4 + 8 + 1 + 16 + 4 + 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putLong(7L).put(2)
            .putFloat(0f).putFloat(0f).putFloat(1f).putFloat(1f)
            .putInt(0)
            .putShort(9999)
            .array()
        assertFailsWith<IllegalArgumentException> { SafePdfParser.parseAnnotations(bytes) }
    }

    /**
     * Rust truncates every listing string at `u16::MAX`, not at 4096, so a long annotation
     * comment or multi-line form value is a well-formed record. Rejecting it threw straight
     * out of `SafePdfDocument.annotations`, which does not catch, so one long sticky note
     * took down the whole listing.
     */
    @Test
    fun aListingStringLongerThanFourKilobytesDecodes() {
        val contents = "x".repeat(9000).toByteArray(Charsets.UTF_8)
        val bytes = java.nio.ByteBuffer.allocate(4 + 8 + 1 + 16 + 4 + 2 + contents.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putLong(7L).put(2)
            .putFloat(0f).putFloat(0f).putFloat(1f).putFloat(1f)
            .putInt(0)
            .putShort(contents.size.toShort()).put(contents)
            .array()
        assertEquals(9000, SafePdfParser.parseAnnotations(bytes).single().contents.length)
    }

    @Test
    fun annotationsDecodeWithTheirContents() {
        val contents = "note".toByteArray(Charsets.UTF_8)
        val bytes = java.nio.ByteBuffer.allocate(4 + 8 + 1 + 16 + 4 + 2 + contents.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putLong(42L).put(2)
            .putFloat(1f).putFloat(2f).putFloat(3f).putFloat(4f)
            .putInt(0xFFFF0000.toInt())
            .putShort(contents.size.toShort()).put(contents)
            .array()
        val a = SafePdfParser.parseAnnotations(bytes).single()
        assertEquals(42L, a.id)
        assertEquals(2, a.subtype)
        assertEquals(4f, a.y1)
        assertEquals("note", a.contents)
    }

    /**
     * A listing's record count is read straight off the wire and used to size the result list,
     * so a corrupt or truncated header used to allocate an `Object[count]` before a single
     * record had been validated. At a count near `Int.MAX_VALUE` that is an OutOfMemoryError —
     * an [Error], not an exception, so it escapes the `runCatching` in `SafePdfDocument` that
     * exists to degrade a bad listing to an empty one, and takes the viewer down instead.
     *
     * The buffer physically cannot hold more records than `remaining / minRecordBytes`, so the
     * pre-allocation is capped on that. The loop still runs to the claimed count and stops on
     * the underflow, which is why a plain [RuntimeException] is the expected outcome here.
     */
    @Test
    fun anAbsurdListingCountFailsWithoutExhaustingTheHeap() {
        val headers = mapOf<String, (ByteArray) -> Any>(
            "annotations" to SafePdfParser::parseAnnotations,
            "form fields" to SafePdfParser::parseFormFields,
            "links" to SafePdfParser::parseLinks,
            "outline" to SafePdfParser::parseOutline,
            "search matches" to SafePdfParser::parseSearchMatches,
        )
        // A count of Int.MAX_VALUE over an otherwise empty buffer.
        val bytes = java.nio.ByteBuffer.allocate(4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(Int.MAX_VALUE)
            .array()
        for ((what, parse) in headers) {
            assertFailsWith<RuntimeException>("$what sized its result list from the wire count") {
                parse(bytes)
            }
        }
    }

    /** And a count the buffer CAN justify still decodes every record. */
    @Test
    fun aListingCountTheBufferCanHoldStillDecodesEveryRecord() {
        val buf = java.nio.ByteBuffer.allocate(4 + 3 * (16 + 4 + 2))
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(3)
        repeat(3) { i ->
            buf.putFloat(i.toFloat()).putFloat(0f).putFloat(1f).putFloat(1f)
                .putInt(i).putShort(0)
        }
        val links = SafePdfParser.parseLinks(buf.array())
        assertEquals(3, links.size)
        assertEquals(2, links[2].destPage)
    }

    // ---- the cross-language seam ----

    /**
     * Pins [WireWriter]'s Image arm to the byte count Rust's own serializer produces, so the
     * two transcriptions of the wire format cannot drift apart silently.
     *
     * THE SEAM THIS CLOSES. Every other test in this file decodes bytes from [WireWriter],
     * which is a hand transcription of `wire::serialize`. Rust's `round_trips_all_primitives`
     * reads its output back with a hand-written Reader, which is a second transcription. So
     * both languages verify themselves against their own copy of the format and NOTHING
     * compares Kotlin's decoder against Rust's actual bytes. A field that changed width on
     * one side only would leave all 49 tests here green while every real page desynced — the
     * exact "random shapes appear on the page" failure this decoder exists to prevent.
     *
     * WHY THE IMAGE ARM SPECIFICALLY. It is the one primitive Rust pins to an exact length
     * independently, in `wire::tests::wire_version_matches_the_image_payload_layout`:
     *
     *     let v10_len = (4 + 4 + 4 + 4 + 4) + 1 + 24 + 4 + 4 + 1 + 4 + 1 + 4 + 4;   // = 67
     *     assert_eq!((serialize(&page).len(), WIRE_VERSION), (v10_len, 10), ...)
     *
     * for a 1x1 image with four bytes of data. Asserting the same 67 here couples the two:
     * change the Image layout in `wire.rs` and that test fails; change it in [WireWriter] and
     * this one does. Reproducing the arithmetic term by term rather than writing `67` is
     * deliberate — a bare total would still match if two fields changed by offsetting amounts.
     *
     * VERSION 10, NOT [SafePdfParser.WIRE_VERSION]. Rust EMITS 10; the parser merely
     * understands up to 11. Building at the parser's constant would add the v11 interpolate
     * byte and describe a stream nothing currently produces.
     *
     * RESIDUAL, stated because it is not closed: the other thirteen tags have no Rust-side
     * length constant to pair with, so they remain transcription-against-transcription. A full
     * fix is a golden buffer emitted by Rust and checked into the test resources; this covers
     * the arm that has actually moved twice (v9 alpha, v10 blend) and is next in line to move
     * again (v11 interpolate).
     */
    @Test
    fun theImageArmMatchesTheByteCountRustSerializes() {
        val header = 4 + 4 + 4 + 4 + 4
        val payload = 1 + 24 + 4 + 4 + 1 + 4 + 1 + 4 + 4
        val bytes = WireWriter(version = 10)
            .image(w = 1, h = 1, format = 0, data = ByteArray(4))
            .build()
        assertEquals(
            header + payload,
            bytes.size,
            "WireWriter's Image arm has drifted from wire::serialize — see " +
                "wire::tests::wire_version_matches_the_image_payload_layout, which pins the " +
                "same figure on the Rust side",
        )
        // And it must still decode, so the length agreeing is not a coincidence of two
        // offsetting field-width changes.
        val page = SafePdfParser.parse(bytes)
        assertIs<PdfPrimitive.Image>(page.primitives.single())
    }

    /**
     * The v11 interpolate byte is exactly one byte wider, and nothing else moves. This is the
     * change `wire.rs` documents as next, and the one its comment warns "makes the parser eat
     * the first byte of the image's u32 len as interpolate and desync every primitive from
     * there on" if the two sides land out of step.
     */
    @Test
    fun theV11ImageArmIsExactlyOneByteWiderThanV10() {
        val v10 = WireWriter(version = 10).image(w = 1, h = 1, data = ByteArray(4)).build()
        val v11 = WireWriter(version = 11).image(w = 1, h = 1, data = ByteArray(4)).build()
        assertEquals(1, v11.size - v10.size, "the v11 delta is the single interpolate byte")
        assertIs<PdfPrimitive.Image>(SafePdfParser.parse(v11).primitives.single())
    }
}
