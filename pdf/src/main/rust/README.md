# pdf_render — memory-safe PDF renderer (Rust + JNI)

Parses PDFs entirely in Rust with [`lopdf`] (no pdfium / no system PDF stack)
and reduces each page to plain drawing primitives — text runs (with accurate
advance), filled/stroked polygons, raster images (JPEG, JPEG2000 via openjp2,
JBIG2 via hayro-jbig2, CCITTFax G3/G4 via the `fax` crate), clipping with bezier
retention, axial/radial and mesh shadings, tiling/shading patterns (for both
fills and strokes), Type 3 fonts, transparency groups, and ExtGState soft masks —
for the "Open PDF (safe)" viewer. Built into `libpdf_render.so` and called from
Kotlin via `PdfNative`.

The point of "safe": all untrusted binary parsing happens in memory-safe Rust,
and the JNI boundary only ever passes a flat little-endian buffer of geometry +
UTF-8 text, so Kotlin never touches the raw PDF bytes.

## Scope (implemented)

- **PDF functions** (`functions.rs`): a full evaluator for all four function
  types — Type 0 sampled (multilinear interpolation), Type 2 exponential, Type 3
  stitching, and Type 4 PostScript calculator (a bounded stack machine over the
  arithmetic/comparison/stack/control operator subset). Shared by colorspaces
  and shadings.
- **Text** with `/ToUnicode` decoding (1-byte simple fonts and 2-byte
  Identity-H / Type0), full base encodings (WinAnsi, **real MacRoman**, Standard,
  Symbol, ZapfDingbats), a comprehensive Adobe Glyph List, and embedded-program
  encoding recovery: TrueType `cmap` (formats 0/2/4/6/8/10/12/13/14), **Type 1 `/FontFile`** clear-text
  `/Encoding` scan, and **CFF `/FontFile3`** charset+Encoding recovery
  (`cff.rs`). Text render modes 0–7 including the clip modes (4–7) and the
  invisible mode 3, which emits a non-painting record so a scanned page's OCR
  layer stays selectable and searchable. Accurate
  glyph advance from `/Widths`/`/W`/`/DW` + `Tc`/`Tw`/`Th`.
- **Encoding resolution follows the spec's priority order** (9.6.6.1): for a
  symbolic font that declares neither `/Encoding` nor `/BaseEncoding`, the font
  program's **built-in** encoding outranks any implicit base encoding, and
  `/Differences` is layered on top of whichever base applies. StandardEncoding /
  WinAnsi is backfilled only for codes the built-in encoding does not cover, so a
  font whose symbolic flag is set spuriously still decodes. Previously the
  implicit WinAnsi base populated every printable code and shadowed the built-in
  encoding entirely, which made symbolic fonts render — and copy, and index —
  Latin letters where the document wanted symbols. For symbolic TrueType the
  `cmap` subtable precedence is also the spec's (9.6.6.4): **(3,0) Microsoft
  Symbol before (1,0) Macintosh**, rather than whichever subtable the font
  happened to store first. A symbolic font that carries only a (3,0)/(1,0)
  subtable and **no Unicode subtable** no longer yields an empty map: `gid →
  unicode` is recovered from the `post` table's glyph names through the Adobe
  Glyph List, so such fonts are still selectable and searchable. Glyph names are
  an authoritative Unicode source; Unicode is never invented from the raw
  character code, which would corrupt the text index.
- **Font parsing is cached per operation** (`FontCacheScope`): `FontInfo` — which
  owns the whole decompressed embedded font program — used to be rebuilt on every
  `interpret_content` call, so a font shared by *N* pages was parsed *N* times to
  render and another *N* times to build the search index. The cache is keyed by
  the font dictionary's object id *plus* a hash of that dictionary with every
  indirect reference resolved, so a colliding id from another document cannot be
  served, and is scoped to a single top-level operation, then dropped: even that
  key is complete only while the document is unmutated, so a short-lived cache is
  correct where a global one would serve stale fonts after a `docedit`. Direct
  (non-indirect) font dictionaries have no id and are never cached.
- **Embedded glyph outlines**: TrueType/OpenType, bare CFF (`/Type1C`,
  `/CIDFontType0C`) and bare Type 1 programs are parsed and their real glyph
  contours are emitted as vector fills/strokes (`outlines.rs`), so paint modes
  0–2 draw the document's own typeface rather than a substitute. Type 2
  charstrings (including the `flex`/`flex1`/`hflex`/`hflex1` operators of Adobe
  TN#5177 §4.2) are interpreted by `ttf-parser`; the Type 1 interpreter in
  `type1.rs` implements the equivalent `OtherSubrs` 0/1/2 flex protocol itself,
  where the seven reference points must not restart the contour and the end point
  must be returned for the trailing `pop pop setcurrentpoint`. `/CIDToGIDMap`
  (`/Identity` or a stream) is applied when selecting CID glyphs, and a
  present-but-zero entry — or a CID past the end of the stream — is honored as
  `.notdef` (9.7.4.2) rather than falling through to identity and drawing an
  unrelated glyph. Outline emission is never gated on a successful Unicode
  mapping: a glyph with no `/ToUnicode` entry still paints. Fonts without a
  usable program fall back to a substitute system typeface positioned by the
  PDF's own metrics.
- **Vertical writing** (`WMode 1`): the writing mode is taken from the `/Encoding`
  CMap name, the Type0 dict and the descendant, and `/W2` //`DW2` drive the
  vertical advance and position vector.
- **Type 3 fonts**: glyph CharProc content streams are interpreted and drawn
  (`draw.rs::show_string_type3`), mapped through the font matrix and bounded by
  `MAX_TYPE3_GLYPHS` / `MAX_TYPE3_PRIMS_PER_GLYPH`. Hitting the per-glyph bound
  cuts at the cap and appends whatever clip/group closers the cut orphaned
  (innermost first), so an over-long glyph loses its tail rather than being dropped
  and cannot leave the renderer's save/restore stack unbalanced for the rest of the
  page. A soft-mask bracket is handled specially rather than truncated, because its
  mask is the content that *follows* `SoftMaskContent` and appending a bare
  `SoftMaskPop` would leave an empty mask: if the cap falls in the masked content the
  surplus is dropped and the mask moved back on, so the glyph keeps both its content
  and its real mask; if the cap falls inside the mask itself the bracket is completed
  instead.
- **Vector paths**: lines, rectangles, filled/stroked paths, flattened beziers
  for drawing; clip paths retain the exact beziers via `PathOp`
  (Move/Line/Cubic/Close) and are drawn in Kotlin with `cubicTo` (wire v4).
- **Color**: `g/G/rg/RG/k/K` plus `CS/cs/SC/sc/SCN/scn`. Separation and DeviceN
  evaluate their tint transforms through the full `PdfFunction` evaluator (any
  function type, all N inputs), plus Lab, CalRGB/CalGray, ICCBased (alternate),
  and Indexed. **Raster image samples** are converted through the same
  colorspace machinery (with LUTs for single-component and indexed images), so
  Separation/DeviceN/Lab/ICC images are colored correctly. Image transparency
  covers `/SMask` (soft mask, with `/Matte` un-premultiplication), explicit
  stencil `/Mask` images, and color-key masking compared against pre-conversion
  samples (correct for CMYK/DeviceN, not just RGB). CMYK/YCCK JPEGs (including
  Adobe APP14 inverted CMYK) are decoded in Rust; JPX honors its enumerated
  color space (gray/RGB/YCC/CMYK + alpha).
- **Shadings** (`images.rs` + `shading.rs`): Type 1 (function-based), Type 2
  axial, Type 3 radial (all using `PdfFunction`), and real mesh shadings —
  Type 4 free-form Gouraud (flag-driven triangle strips), Type 5 lattice
  (`/VerticesPerRow`), and Type 6/7 Coons/tensor patches subdivided from their
  actual boundary Bézier curves — Type 7 uses the full bicubic tensor surface
  from its 4 interior control points. Radial (Type 3) shadings solve the true
  circle-family parameter per pixel (honoring `/Extend` and non-negative radii),
  rather than approximating them as axial. A byte-accurate `BitReader` handles
  arbitrary `BitsPerCoordinate/Component/Flag`. Axial/radial/function shadings
  are rasterized at a resolution derived from their device footprint (sharp when
  zoomed) and, when they lack a `/BBox`, cover the current clip extent.
- **Patterns** (`interpret.rs`): PatternType 2 (shading) patterns are rasterized
  with the pattern `/Matrix` and clipped to the fill region; PatternType 1
  (tiling) patterns replay their content stream tiled across the fill bbox
  (colored and uncolored `/PaintType`), bounded by `MAX_PATTERN_RECURSION` and a
  per-pattern tile cap. Both tiling and shading patterns are honored for
  **strokes** too: the stroked path is converted to outline quads and the
  pattern is painted within each segment (`paint_pattern_stroke`).
- **Filters** (`filters.rs`): ASCIIHex, ASCII85, RunLength, LZW and Flate, both
  now with PNG (Predictor 10–15) **and TIFF (Predictor 2, all of 1/2/4/8/16-bit)**
  support; CCITT G3 (1-D) / G4 (2-D); JBIG2 and DCT/JPX passthrough. Decode
  failures for JPX/CCITT/DCT no longer fall through to reinterpreting encoded
  bytes as raw samples.
- **Encryption** (`crypto.rs` + `decrypt.rs`): open and save with the Standard
  security handler — RC4-128 and **AES-128 (V4/R4)** and **AES-256 (V5/R6)**.
  `save_encrypted` defaults to AES-128.
- **Wire format v10**: header `MAGIC 0x50444657 VERSION=10 f32 w,h u32 count`;
  tags 1 Text, 2 Fill, 3 Stroke, 4 Image, 5 ClipPush (with a bezier-retentive
  path-ops section), 6 ClipPop, 7 GroupPush, 8 GroupPop, 9 TextClipApply,
  10 SoftMaskPush, 11 SoftMaskContent, 12 SoftMaskPop, 13 SoftMaskTransfer (a
  256-byte `/TR` LUT, emitted only for a non-identity `/TR`), 14 ImageTiled (a
  rasterized tiling-pattern cell plus its step and lattice extent, so a periodic
  fill costs one bitmap rather than one image per tile). Tags 13 and 14 are
  *tag*-gated rather than version-gated — a decoder that never sees one is
  unaffected — so the declared version stays 10. Text carries its render
  mode (v4); Text/Fill/Stroke each carry a per-primitive blend byte (v5); Text
  also carries its device advance (v7) and bold/italic/family/outline flags plus
  the horizontal scale (v8); images carry per-image alpha (v9) and blend (v10).
  `SafePdfParser.kt` parses v10 and keeps the earlier versions as fallbacks. It is
  also written to consume a **v11** that adds the per-image `/Interpolate` byte, but
  that read is gated on v11 and Rust still declares v10, so the byte is neither
  written nor read; the two sides are consistent, not desynchronized. Whoever bumps
  Rust to v11 must start writing the byte in the same change (see the `/Interpolate`
  limitation).
- **Blend modes**: honored not just on transparency groups but on individual
  fills, strokes and text — the graphics-state `/BM` travels with each Text/Fill/
  Stroke primitive and is applied per-draw in Kotlin via `android.graphics.BlendMode`.
  All separable and non-separable modes are supported natively with no per-device
  caveat: the module's `minSdk` is 31 and that API is 29, so no fallback path is
  needed.
- **ExtGState soft masks** (`/SMask`): luminosity and alpha soft masks set via
  `gs` apply to all subsequent drawing while active — fills, strokes, text,
  images, shadings, and Form XObjects — not only forms. Rust brackets each
  masked draw with SoftMaskPush/Content/Pop, rendering the `/G` group at the CTM
  in effect when the mask was set; a `/BC` backdrop (luminosity masks) is painted
  as a backdrop rectangle so uncovered areas take the backdrop luminance. Kotlin
  composites with nested `saveLayer`s — a `DST_IN` mask layer, plus a
  luminance→alpha `ColorMatrix` layer for luminosity masks. `/SMask /None`
  clears the mask. An affine `/TR` is applied; see limitations for non-linear ones.
- **Kotlin drawing**: bezier clips via `cubicTo`; text-clip modes accumulate
  glyph outlines (`Paint.getTextPath`) and intersect them into the clip at the
  `TextClipApply` marker; each blend mode maps directly to
  `android.graphics.BlendMode`.
- **Search**: a per-page text index stores both a lowercased (byte-aligned) and
  original-case string, so case-sensitive and case-insensitive search are both
  exact.
- **Annotations / forms**: `subtype_code` covers the full ISO 32000 annotation
  subtype set; text-field appearance regeneration honors `/Q` alignment,
  multiline wrapping and comb (`/MaxLen`) fields, alongside checkboxes,
  **radio-button groups** (setting one clears its siblings and updates the parent
  `/V`) and **Choice fields** (records `/V` + the matched `/Opt` index in `/I`
  with a regenerated appearance). Annotations that ship without an `/AP` stream
  get a **synthesized appearance** for the common types — Square, Circle, Line,
  Ink, Highlight, Underline and StrikeOut (via `/QuadPoints`, `/C`, `/IC`,
  `/BS`).

## Known cross-fix interaction risks (round-3 review)

These came out of a review of the ~90 changes that landed across rounds 1 and 2,
looking specifically for fixes that conflict, cancel or double-apply rather than
for individually-wrong code. All were verified against the working tree. The
first two are open because they live outside the reviewing agent's file set.

- **Optional content does not gate form XObject content.** `oc_stack` is a local
  in `interpret_content_seeded` and is not threaded into nested streams, so
  `/OC1 BDC /Fm0 Do EMC` with `OC1` OFF paints the form's whole contents —
  and, because the `/BBox` clip is suppressed by the same hidden flag, paints
  them *unclipped*. Round 1 fixed the BMC/BDC/EMC balance and round 2 fixed the
  text operators, but the `Do` recursion was never gated. An XObject's own `/OC`
  key (§8.11.3.3) *is* honoured; it is only the BDC-bracket state that is lost.
- **The §11.6.6 group alpha reset is disabled whenever a soft mask is active.**
  Round 2 made the transparency-group push and the soft-mask bracket mutually
  exclusive (`should_emit_group = is_transparency_group && !use_smask`), and the
  reset of `alpha_fill`/`alpha_stroke` to 1.0 is gated on the push having
  happened. A group form drawn with both `/ca < 1` and an `/SMask` therefore
  applies `ca` to every element inside the group instead of once to the
  composited result. Same hole when `MAX_PRIMITIVES` or the group-depth cap
  demotes the push. Identical output for non-overlapping content; over-darkens
  overlapping content.
- **Annotation `/CA` used to be applied twice** — fixed. `render_annotation`
  scaled the alpha of every prim it had just emitted, which hit both a nested
  `GroupPush`'s alpha *and* every prim inside that group (CA² on the contents),
  and also hit the soft-mask group's own prims, which `wrap_with_soft_mask`
  appends after `SoftMaskContent` in the same range — so `/CA` altered the mask
  rather than the content. It now wraps the appearance in one
  `GroupPush{alpha: ca}`/`GroupPop`, which is the §12.5.2 + §11.6.6 model and
  applies the opacity once, at composite time.

Verified clean, with the evidence, so a future change knows what it is allowed to
disturb:

- **Mask polarity is inverted exactly once on every convention.** `/Decode` is
  read in exactly five places in `images.rs`, and `decode_mask_stream_gray`
  deliberately does not read it at all — polarity comes only from its
  `MaskPolarity` argument, and the caller applies `/Decode` once afterwards. The
  CCITT stencil path folds `/Decode` into its `black_bit` and then calls
  `stencilize` with `invert: false` precisely so it is not re-applied. The CCITT
  and JBIG2 stencil paths are now covered by tests as well as by reading —
  including `/BlackIs1`, the second inversion source, and the `/BlackIs1` +
  `/Decode [1 0]` pair that cancel. `stencilize` zeroes alpha and leaves the RGB
  of a transparent pixel as the raster left it, so those tests state polarity in
  terms of alpha; the colour beneath an unpainted pixel is not part of the
  contract. The DCT and JPX mask branches remain untested.
- **Neither round-2 cache leaks.** The font cache is a `thread_local` installed
  by an RAII `FontCacheScope` (one per page render, one per search-index build)
  and keyed by the font's `ObjectId` plus a hash of its dictionary with every
  indirect reference *resolved* — not by resource name, so two
  pages both binding `/F1` to different font objects stay distinct, and not by
  the `Document`'s address, which is not an identity: `Document`s are held by
  value in the registry's `IndexMap`, so one can be replaced at the address
  another just vacated and a scope spanning both would serve the first
  document's fonts for the second. Resolving through the references also catches
  two font dicts that are byte-identical but whose `/Widths 7 0 R` names
  different arrays. It has no
  invalidation hook and needs none: it does not exist between operations, so a
  `docedit` mutation cannot be served a stale entry. Residual fragility: the key
  is complete only while the document is unmutated *within* a scope, which is
  what scoping it to one top-level operation buys. The soft-mask
  coalescing memo is a local in `interpret_content_seeded`, and its key includes
  the group id, mask type, the CTM as exact `f64::to_bits()`, `/BC` and the whole
  256-byte `/TR` LUT, so a mask cannot be reused at a different CTM.
- **Clip and group brackets balance on every path**, including the truncation
  and cap paths: every structural pop is emitted *without* a `MAX_PRIMITIVES`
  guard while every content push has one, so the cap can never suppress a
  closer, and the end-of-stream drain closes whatever is left.

### Deliberate omission: GB18030's four-byte plane

Now implemented. `EncodingCMap::code_len` dispatches on the first byte alone,
which cannot see GB18030's four-byte plane (distinguished from its two-byte plane
only by the second byte being `0x30`-`0x39`). Round 2 left this alone on the
grounds that widening the lookahead for every font to fix a rare case was not
obviously right. `code_len_at` resolves it as a *strict refinement* instead: it
only ever chooses a longer range, and only when that range also matches the
second byte. No other predefined codespace has two ranges of different length
sharing a first byte, so for every font but GB18030 the answer is provably
identical to `code_len` — which removes the tradeoff that made the deferral
reasonable. `gb18030_four_byte_plane_needs_the_second_byte` asserts both halves,
exhaustively over all 256 first bytes for the other families.


## Genuinely unsupported (documented, not silently skipped)

Per-feature spec coverage, and the rationale for each deliberately-ignored
feature: [SPEC_COVERAGE.md](SPEC_COVERAGE.md).

### An OCG listed in both `/ON` and `/OFF` renders as OFF

§8.11.4.3 gives `/ON` and `/OFF` as overrides of `/BaseState` and does not spell
out the result when the same group appears in both. `OcConfig::is_ocg_visible`
applies them in the order Table 101 lists them — `/BaseState` (default ON), then
`/ON`, then `/OFF` — so `/OFF` is applied last and wins, which is what mainstream
viewers do and therefore how a file authored against them was meant to look.

`/BlackPoint` on CIE-based spaces (§8.6.5.2, §8.6.5.4) is a genuine deviation of
this kind: the conversion adapts from `/WhitePoint` only, so the effect is a
slight shift in the darkest tones rather than missing content.

> ### WARNING: Redaction does not remove content — it covers it
>
> **Do not rely on `/Redact` to scrub sensitive data.** Applying a redaction
> paints an opaque box and deletes a text-showing operator only when its *start
> point* falls inside the redaction rect. Everything else survives in the saved
> file and stays extractable by any other tool: images, vector art, and any text
> that begins outside the box and runs into it. The pre-redaction content stream
> is left in the document.
>
> This is a **security** limitation, not a fidelity one — the page looks redacted
> while the data is still there. The removal is a heuristic over text-showing
> operators, not a content-stream rewrite. There is currently no user-facing
> warning on the apply action.


- **Public-key / certificate encryption** (`/Filter /Adobe.PubSec`): decryption
  requires the recipient's private key, which the viewer does not possess.
  Reported as `DecryptStatus::Unsupported`.
- **Predefined *named* non-Identity CMaps** (`90ms-RKSJ-H`, `GBK-EUC-H`,
  `ETen-B5-H`, `KSCms-UHC-H`, ...): the code→CID table of a predefined CMap is an
  external Adobe resource that cannot be embedded, so CID lookups fall back to
  identity and *glyph selection* for these encodings is wrong.
  What *is* handled: the **codespace ranges** of every mixed-width family are
  installed, so 1-byte and 2-byte codes are segmented correctly and the byte
  stream no longer desynchronizes (9.7.6.2). This is the difference between "some
  wrong glyphs" and "every character after the first 1-byte code is garbage",
  because a mis-sized code shifts the whole remainder of the string. It also makes
  `/ToUnicode` (which is keyed by *code*, not CID) resolve, so text extraction,
  search and substitute-font painting work even where the embedded glyphs do not.
  Covered families: Shift-JIS (`*-RKSJ-*`), Japanese EUC (`EUC-H/V`), GBK
  (`GBK*`), EUC-CN (`GB*-EUC-*`), Big5 (`*-B5-*`, `B5pc-*`), Korean UHC/EUC-KR
  (`*UHC*`, `KSC*`), and the variable-length `Uni*-UTF8-*` (1–4 byte) and
  `Uni*-UTF16-*` (2/4-byte surrogate) families. `Identity-H/V`, the pure-2-byte
  `Uni*-UCS2-*` families and the `<2121>`–`<7E7E>` ISO-2022 families (`H`, `V`,
  `Add-H`, `Ext-H`) need no codespace and are fully segmented already.
  `usecmap` is honored, inheriting the referenced predefined CMap's codespace.
  **Embedded** CMap *streams* are parsed for real code→CID ranges, variable-length
  codespaces and `/WMode`, and are fully supported.
  Two known gaps: shipping the ~40 compiled predefined code→CID tables is the
  remaining work (they are registry-specific integer tables that must be copied
  from the Adobe resources — inventing values would silently select confidently
  wrong glyphs, which is worse than the identity fallback); and `GBK2K-H`
  (GB18030) has a 4-byte plane distinguished only by the *second* byte, which the
  first-byte-only code-length dispatch cannot see, so those rare codes still
  mis-segment.
- **Vertical writing position vector `v_y` per CID**: `/W2` and `/DW2` are parsed
  and applied — the vertical advance `w1_y`, the horizontal offset `v_x` and the
  `[880 -1000]` `/DW2` default all drive layout — but a *CID-specific* `v_y` is
  discarded and the `/DW2` value is used for every glyph. Also note the vertical
  advance keeps applying `Tc`/`Tw` in the direction that widens the gap, which
  deviates from the literal formula in 9.4.4 (where a positive `Tc` would tighten
  negative-`w1` vertical text) in favour of matching the horizontal behaviour.
- **CFF2 and other font programs `ttf-parser` cannot open**: an unparseable
  `/FontFile[23]` yields no outline program, and the glyph falls back to being
  painted with a substitute system typeface using the PDF's own widths. Text is
  still positioned, selectable and searchable; only the glyph shapes are
  approximate.
- **Composite fonts with no `/ToUnicode`**: for a Type0 font that embeds no
  outline program and supplies no `/ToUnicode`, the character codes are CIDs with
  no recoverable Unicode meaning, so the extracted text is not trustworthy. The
  proper fix is the predefined `Adobe-Japan1-UCS2`-style CID→Unicode tables,
  which is the same compiled-table work as the entry above.
- **Search matches the exact Unicode the PDF supplies, with no normalization**:
  case folding is applied (and only where it preserves byte length, so the
  lowercased and original-case page strings stay aligned for one span table), but
  **ligatures are not expanded and diacritics are not folded**. Searching `find`
  will not match text typeset with the `fi` ligature (U+FB01), and `café` will not
  match `cafe`. The ligature case is the one users hit, because professionally
  typeset PDFs use `fi`/`fl` routinely and whether the ligature survives into the
  text depends entirely on what the font's `/ToUnicode` maps it to.
- **Embedded font programs are never rasterized by the renderer**: glyphs either
  arrive as real outline fills from the core, or are drawn with a substitute
  system typeface chosen by generic family (sans/serif/mono) with synthesized
  bold/italic. Positioning stays correct because the core supplies a per-glyph
  origin and advance, but letterforms differ where no embedded outline is
  available.
- **Text clipping (`Tr` 4–7) clips with the substitute typeface's outline**, not
  the embedded glyph outline, so a text-clipped region has the right position and
  size but the wrong letterform edges.
- **Transparency group `/K` (knockout) is ignored**: a knockout group renders as
  if non-knockout, so overlapping elements inside one incorrectly show through
  each other instead of each replacing the last (11.4.6 / 11.6.6).
  `Canvas.saveLayer` composites each element against the running result rather
  than against the group's initial backdrop, which is what knockout requires.
- **Transparency group `/I false` (non-isolated) is ignored**: a non-isolated
  group renders as isolated, so a group carrying a non-Normal blend mode fails to
  blend with the page backdrop and looks flat or too opaque (11.4.5).
  `saveLayer` always yields an isolated layer and there is no way to seed it with
  the existing backdrop.
  Both flags *are* parsed and carried over the wire (`u8 isolated, u8 knockout`);
  neither variant is expressible with Canvas layers.
- **Soft-mask `/TR` transfer functions are applied only when affine** (11.6.5.2):
  the mask value is passed through `/TR`, carried to the renderer as a 256-entry
  LUT (wire tag 13) and least-squares fitted to `gain * m + bias` in Kotlin.
  Inverting and gain/bias curves fit exactly and are applied exactly — which
  covers the case that matters, since an inverting `/TR` is the standard idiom for
  "mask out where the group is bright" and ignoring it hid exactly the wrong half.
  A *nearly* linear curve is accepted and applied as its best-fit line, the
  tolerance being a maximum absolute error of `3/255`. Only a genuinely
  non-linear `/TR` (gamma, threshold, sampled Type 0) is refused: it is detected,
  logged once at parse time, and left **untransformed** rather than approximated,
  because a wrong curve hides the wrong half of the group — the exact fault this
  exists to prevent. What a user sees in that case is a mask with the wrong
  falloff — a vignette that fades over the wrong distance, or a mask with visibly
  wrong contrast — rather than a subtly approximated one, since the mask is applied
  as though `/TR` were identity. An identity `/TR` never reaches the wire; it is
  dropped in Rust, which is cheaper and indistinguishable from applying it.
  The remaining gap is a platform one: a soft mask is composited in a
  `Canvas.saveLayer`, whose only per-pixel hook is the affine
  `ColorMatrixColorFilter`; Android has no alpha-LUT filter, a `saveLayer` exposes
  no readable pixel buffer, and `RuntimeShader` cannot act as that filter.
- **Overprint** (`/OP`, `/op`, `/OPM`): parsed into the graphics state but
  deliberately not applied. Overprint controls which *device colorants* are left
  unmarked (8.6.7) and has no meaning on an additive RGB compositor with no
  separations. It was previously approximated as a Multiply blend, which was
  actively harmful: `white MULTIPLY dst == dst` turned white knockout rectangles
  into no-ops and let content that should have been covered show through.
- **Incremental save / detached-signature verification**: `save_to` always
  rewrites the whole document (no `/Prev` xref append), so cryptographic
  signature *verification* and byte-range-preserving incremental updates are not
  implemented. Signing scaffolding (`prepare_signature`) and detached CMS
  creation exist, but existing signatures are not validated on open.
- **`/Interpolate` is decided but never transmitted** (8.9.5.1): an image that asks
  for smoothing and one that asks for none are filtered identically, so a magnified
  QR code, barcode, scanned fax or 1-bit stencil turns into grey mush.
  `image_should_interpolate` computes the right answer in Rust, but `Prim::Image`
  has no field for it, the Image arm writes no interpolate byte, and the renderer
  filters unconditionally. The same root cause has a second surface: a rasterized
  tiling-pattern cell (`ImageTiled`) is drawn with bitmap filtering hard-coded on,
  so a bilevel hatch pattern blurs when zoomed.
  The two sides are consistent about it rather than desynchronized: the wire spec
  defines the byte at v11 and the Kotlin decoder is written to consume it, but that
  read is gated on v11 and Rust still declares v10 — so the byte is never written
  and never read, and the gate fails safe. This is the same half-wired shape `/TR`
  had before it was completed, which is why it can look finished from either side
  alone. Whoever bumps Rust to v11 **must** write the byte in the same change;
  `wire.rs` says so at the constant.
- **A very dense page is truncated at 300,000 drawing primitives**: past that the
  rest of the page is not emitted, so a large-format vector drawing — a map, CAD
  sheet or dense chart — renders only partially. This is a memory floor rather
  than a tunable: a `Prim` costs ~120–180 bytes in practice, so 300,000 is roughly
  40–55 MB held simultaneously with the ~15 MB serialized wire copy, and a Rust
  OOM is an uncatchable process abort. The cap is enforced at every
  content-emitting push. The truncation is **silent**: nothing on the wire flags
  it and the viewer surfaces no message, so the page simply renders its top
  portion and looks as though it finished normally. The separate operator ceiling
  (`MAX_CONTENT_OPS`, a *time* guard) sits deliberately above it at 1,000,000 so
  it only catches pathological streams — it was formerly 200,000, which made it
  the binding constraint and cut an ordinary dense vector page off at ~100,000
  primitives, a third of what was already sanctioned.
- **No per-page cap on shading memory**: a page with very many gradients can use a
  lot of memory. Each shading raster is capped individually — 256 KB for
  axial/radial gradients, 1 MB for function-based and mesh shadings — but nothing
  bounds their total, so a page with hundreds of gradients holds every one at once.
- **Some tiling patterns are drawn at reduced density over a large area**
  (8.7.3.3): most tiled fills now repeat across the whole region at full density —
  a cell made only of fills and strokes is rasterized **once** and emitted as a
  periodic bitmap, so the tile count stops mattering. That includes *overlapping*
  patterns, where `/XStep` or `/YStep` is smaller than the `/BBox` (8.7.3.1): such a
  pattern is still periodic with period `(XStep, YStep)`, and the cell is drawn at
  every lattice offset that reaches one period window, so the spill from
  neighbouring cells is composited in rather than lost.
  Two cases still fall back to replaying the cell per tile, where past 20,000 tiles
  the lattice is thinned uniformly (stride = `ceil(sqrt(need / 20000))`) to stay
  inside the primitive budget — the region remains **fully covered**, just coarser
  than the document asks for: a cell containing **text**, an **image** or its own
  **clipping/group** (the cell rasterizer has no glyph rasterizer and ignores
  images, clips and groups, so rasterizing it would silently drop that content);
  and a pattern overlapping so heavily that one period needs more than 64 cell
  copies to resolve, which is bounded because the cost is per-period, not per-tile.
- **Overlapping shapes under one soft mask blend slightly wrong**: where many
  shapes overlap beneath a single partially-transparent `/SMask`, the composite in
  the overlap region is a little off, because consecutive paints under the same
  mask are coalesced into ONE masked layer and therefore composited as a group
  rather than individually. The coalescing is deliberate and not a shortcut: one
  bracket per painting operator re-expanded the mask for every operator and pushed
  pages past the primitive cap, truncating real content — a visibly worse outcome
  than a slight blend error.
- **The search index is invalidated by an RAII guard, not by the mutators
  themselves**: `ensure_index` is a pure memo keyed by handle and never re-reads
  the document, so the "a search answers from the current document" contract rests
  entirely on every mutating entry point dropping the entry. `invalidate_index`
  used to have no callers at all, which meant redacting, annotating, filling a
  form field or moving a page left search answering from the pre-edit text —
  including, in the privacy-relevant case, surfacing and highlighting text that
  had just been redacted. Each mutating `extern` fn in `jni_bindings.rs` now holds
  an `InvalidateSearchIndex` guard for the whole call, so the eviction happens on
  the unwind path too and a panic that leaves a document half-edited cannot leave
  a matching index behind. The residual fragility is that it is per-entry-point
  rather than structural: a *new* mutating binding that forgets the guard
  reintroduces the staleness, because there is still no single accessor that
  observes "this document changed".
- **Form fields with no appearance stream are invisible**: a field that relies on
  the viewer to build its own appearance (`/NeedAppearances` set, no `/AP`)
  renders as nothing at all rather than as a box showing its value, because
  appearance generation runs only when the user edits a field, not on load.
- **Non-ASCII text typed into a form field or text annotation is mangled in the
  saved file**: it displays correctly inside this app, but the generated
  appearance stream writes UTF-8 bytes into a Latin-1 encoded font, so every other
  viewer shows `Ã©` where `é` was typed. The text is not lost, but the file is
  wrong everywhere except here.
- **Overlapping strokes in a translucent annotation darken where they cross**: a
  semi-transparent Ink or Highlight annotation whose own strokes overlap is
  composited per-stroke rather than as a single group, so intersections come out
  darker than the author intended (11.6.2 wants group semantics).
- **Multi-select list boxes collapse to a single selection**: choosing several
  options stores only one, because the value setter takes a single string.
- **Rich-text field formatting is dropped**: a field with `/RV` shows its plain
  text — correct content, but no bold, italic or colour runs.
- **Standard stamp annotations show their wording in a plain outlined box** instead
  of real artwork: an Approved stamp renders as the word APPROVED in a rectangle,
  because Acrobat's stamp graphics are not shipped.
- **Pushbuttons are presented as toggleable checkboxes** even though a pushbutton
  has no value.
- **Physical page sizing (`/UserUnit`) is not surfaced**; page layout and
  rendering are unaffected by it.
- **True redaction**: see the highlighted warning at the top of this section —
  `/Redact` covers content rather than removing it.
- **Large JBIG2 images render as nothing at all**: a JBIG2 image wider or taller
  than 20,000px, or over 16 megapixels, comes out fully transparent — a deliberate
  memory guard in `jbig2.rs` with no partial-decode path.
- **JBIG2 decode failures are silent**: an image that fails to decode renders as an
  invisible gap rather than a placeholder, so a failure is indistinguishable from a
  legitimately blank region. Deliberate (the red placeholder was removed), but it
  means nothing signals the loss.
- **CCITTFax `/EncodedByteAlign` and mixed 1-D/2-D G3 (`K > 0`)**: a G3/G4 fax
  image with `/EncodedByteAlign true` renders as diagonal streaks or noise, since
  each row must restart on a byte boundary. This is genuinely blocked by the `fax`
  crate's API rather than merely unimplemented: `ByteReader`'s `partial`/`valid`
  fields and `fill()` are private, its only public surface is `new` plus two debug
  printers, and `Group3Decoder`/`Group4Decoder` own their reader internally, so
  there is no way to realign the bit reader. `K > 0` is likewise a best-effort
  attempt (try G3, then fall back to G4) rather than real per-row mode switching,
  so a genuinely mixed page can decode partially or not at all. Pure G3/G4 — the
  common cases — decode correctly.

## Prerequisites (local + CI)

`rustup target add aarch64-linux-android`. `./gradlew :pdf:assembleDebug`
triggers `cargoNdkBuild` with NDK 29.

## Host tests

```sh
cd pdf/src/main/rust
cargo test
```

Covers PDF function evaluation (all four types), radial-shading parameterization,
mesh-shading rasterization, stroke-outline quad generation, Type 3 glyph
emission, image colorspace conversion, LZW/TIFF predictors, RC4/AES-128/AES-256
save round-trips, CFF/Type 1 encoding recovery, Type 1 charstring `flex`
(one contour, correct current point), mixed-width predefined-CMap codespace
segmentation, the per-operation font cache (shared inside a scope, uncached
without one), `Tw` applying only to single-byte code 32, the untrusted-font-data
loop clamps, case-sensitive search, and the wire round-trip (including
per-primitive blend and soft-mask markers).

[`lopdf`]: https://crates.io/crates/lopdf
