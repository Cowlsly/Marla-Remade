# Spec coverage matrix (ISO 32000-1)

A per-feature inventory of what this renderer implements, what it deliberately ignores,
and — for each gap — how likely it is to appear in a real document and how visible its
absence would be.

The point of this document is that an **unimplemented** feature never shows up in code
review, because there is no code to review. It produces the same user-visible symptom as
a bug ("things that should be there are not rendered") while looking clean from the
inside. So the inventory is exhaustive even where the answer is boring.

## How to read this

| Status | Meaning |
| --- | --- |
| **full** | Implemented to spec, to the fidelity the flat-primitive wire format allows. |
| **partial** | Implemented, with a named limitation. The limitation is always stated. |
| **ignored** | Recognised and deliberately given no effect, with the reason stated. Not an oversight. |
| **missing** | Not implemented. Triaged below. |

"Ignored" is a real answer, not a euphemism. Round 2 removed an overprint approximation
because simulating it was **worse** than ignoring it: `white MULTIPLY dst == dst` turned
white knockout rectangles into no-ops and let content that was meant to be covered
reappear. Where a partial simulation would change the common case in order to improve the
rare one, this renderer ignores the feature and says so.

Triage columns are a judgement about real-world producers (Acrobat/Distiller, Illustrator,
InDesign, Word, LaTeX, Ghostscript, scanner firmware), not about the spec. A feature no
real producer emits is not worth implementing however cleanly it is specified.

---

## 1. Content-stream operators (Annex A, Table A.1)

**All 73 operators in Table A.1 are recognised.** There is no operator that falls through
to the catch-all arm, so there is no case where the interpreter silently skips a construct
it does not know about. The dispatch is the single `match op.operator.as_str()` in
`src/interpret.rs`.

`ID` is not listed separately: `lopdf` delivers an inline image as one `BI` operation whose
operand is the whole stream, so `BI`/`ID`/`EI` are one unit here (`EI` is matched as a
no-op for streams that emit it separately).

### Graphics state

| Op | Status | Note |
| --- | --- | --- |
| `q` `Q` | full | Bounded by `MAX_GRAPHICS_STACK_HARD`; overflow is counted so an unmatched `Q` cannot pop a frame it does not own. |
| `cm` | full | Non-finite results rejected, since a poisoned CTM makes whole regions vanish. |
| `w` `J` `j` `M` `d` | full | |
| `i` | full | Feeds `bezier_steps_for_flatness` (§10.6.2). |
| `gs` | **partial** | Table 58 subset — see §2. |
| `ri` | ignored | §8.6.5.8. See `/RI` in §2. |

### Path construction and painting

| Op | Status | Note |
| --- | --- | --- |
| `m` `l` `c` `v` `y` `h` `re` | full | `l`/`c` with no current point are no-ops per §8.5.2.1 rather than fabricating a subpath. |
| `S` `s` `f` `F` `f*` `B` `B*` `b` `b*` `n` | full | Including pattern fills/strokes and soft-mask bracketing. |
| `W` `W*` | full | One `ClipPush` per operator, carrying the full path (beziers retained) so holes survive. |
| `sh` | full | Shading types 1-7 all real. Every content stream that can host one now seeds its clip extent — see §7 item 1 for why that was load-bearing, and for the single documented remainder (Type 3 CharProcs). |

### Colour

| Op | Status | Note |
| --- | --- | --- |
| `CS` `cs` | full | Selecting a space resets the colour to its initial value (§8.6.8). |
| `SC` `sc` `SCN` `scn` | full | Including `/Pattern` with an underlying base space for uncoloured (`/PaintType 2`) patterns. |
| `G` `g` `RG` `rg` `K` `k` | full | |

### Text

| Op | Status | Note |
| --- | --- | --- |
| `BT` `ET` | full | `ET` commits an accumulated text clip. |
| `Tc` `Tw` `Tz` `TL` `Ts` `Tf` | full | |
| `Tr` | **partial** | Modes 0-3 full. Clip modes 4-7 build the clip from substitute-glyph records, so **Type 3 glyphs and glyphs with no recoverable Unicode contribute nothing to it**. See §7. |
| `Td` `TD` `Tm` `T*` | full | |
| `Tj` `TJ` `'` `"` | full | Vertical writing mode (`/W2`, `/DW2`) honoured on the advance and the position vector. |

### XObjects, images, marked and compatibility

| Op | Status | Note |
| --- | --- | --- |
| `Do` | full | Image and Form subtypes. `/Subtype /PS` is correctly ignored (§8.8.2 says a reader shall ignore PostScript XObjects). Form `/BBox` clip, `/Matrix`, `/Group`, and `/OC` all honoured. Recursion bounded at depth 10. The form recursion is gated on the surrounding BDC bracket's hidden flag (§8.11.2) — see §7 item 0. |
| `BI` … `EI` | full | With a lenient re-tokenizer, because `lopdf` wraps inline-image parsing in a nom `cut(...)` and one unparseable image used to blank the whole stream. |
| `BMC` `BDC` `EMC` | full | Optional content, with inherited hiding (§8.11.4.5) and 1:1 nesting. Every construct that can paint is gated on the hidden flag: paths, images, `sh`, patterns, text (via render mode 3, so hidden text stays searchable), Type 3 CharProcs (same), soft-mask brackets, and the form recursion. |
| `MP` `DP` | ignored | Marked-content *points*: no matching `EMC`, so correctly excluded from the stack. |
| `BX` `EX` | ignored | Correct by construction. §7.8.2 requires unrecognised operators inside a compatibility section to be ignored along with their operands; every unrecognised operator is already ignored everywhere, and `lopdf` groups operands with their operator, so the operands go too. |
| `d0` `d1` | **partial** | Recognised and treated as no-ops, which is right for the width/bbox declaration itself. **Not enforced:** §9.6.5 says a `d1` glyph is a shape only and colour operators inside it *shall be ignored*. Here they take effect. See §7. |

---

## 2. ExtGState parameters (§8.4.5, Table 58)

Implemented in the `"gs"` arm of `src/interpret.rs`. All 26 keys accounted for.

| Key | Status | Spec | Real-world likelihood | Visibility if absent |
| --- | --- | --- | --- | --- |
| `/LW` | full | 8.4.3.2 | common | — |
| `/LC` | full | 8.4.3.3 | common | — |
| `/LJ` | full | 8.4.3.4 | common | — |
| `/ML` | full | 8.4.3.5 | common | — |
| `/D` | full | 8.4.3.6 | common | — |
| `/FL` | **full (added this round)** | 8.4.5 / 10.6.2 | uncommon | low — visibly faceted large curves |
| `/Font` | **full (added this round)** | 8.4.5 / 9.3.1 | rare | high — text at the wrong place, spacing and encoding. Two documented limits: the registration bypasses the font cache, and an *inherited* selection does not resolve inside a nested stream (as is already true of an inherited `Tf` resource name). |
| `/BM` | full | 11.3.5 | very common | — |
| `/SMask` | full | 11.6.5 | very common | — |
| `/CA` | full | 11.6.4.4 | very common | — |
| `/ca` | full | 11.6.4.4 | very common | - |
| `/RI` | ignored | 8.6.5.8 | common (as `/RelativeColorimetric`) | none |
| `/OP` `/op` `/OPM` | ignored | 8.6.7 | common in print PDFs | none on an RGB device; **simulating it is actively harmful** |
| `/TR` `/TR2` | ignored | 10.4 | rare non-identity | see below |
| `/HT` | ignored | 10.5 | uncommon | none |
| `/BG` `/BG2` | ignored | 10.3 | uncommon | none |
| `/UCR` `/UCR2` | ignored | 10.3 | uncommon | none |
| `/SM` | ignored | 10.6.3 | common | none |
| `/SA` | ignored | 10.6 | common | very low |
| `/TK` | ignored | 9.3.8 | rare | very low |
| `/AIS` | ignored | 11.6.4.3 | rare | low |

### Why `/TR` and `/TR2` are ignored

This was the leading suspicion going into this round, so it is worth stating the reasoning
rather than the conclusion.

`/TR` is real and it does apply to **all** painting, not only to soft masks. But it sits in
Clause 10, *Rendering*, alongside halftones, black generation and undercolour removal, and
§10.4 defines it as operating on the **device colour components after conversion into the
device colour space**. It is press calibration: the mechanism by which a document
compensates for a specific output device's tone response. It is not a description of what
the page looks like.

Three things follow.

1. Our device colour space is 8-bit sRGB assembled from whatever space the document used.
   A curve authored to linearise a CMYK press applied to those components is not "more
   correct" — it is a different wrong answer.
2. Honouring it would have to be all-or-nothing, and it cannot be. Fill, stroke and text
   colours are computed in Rust and could be remapped through a LUT. A `DCTDecode` image is
   handed to the renderer as **JPEG bytes** and cannot be. So an inverting `/TR` — the one
   case where `/TR` is dramatic rather than subtle — would invert the vector artwork and
   leave every photograph alone. A uniformly wrong page is better than a half-inverted one.
   This is round 2's overprint lesson applied verbatim.
3. Non-identity `/TR` is rare in the wild. The overwhelmingly common occurrences are
   `/TR /Identity` and `/TR2 /Default`, which are no-ops by definition.

**The half of transfer functions that genuinely matters is implemented.** §11.6.5.2's soft
mask `/TR` is a completely different parameter: it maps the *mask value*, not device
colour, it is device-**in**dependent, and an inverting one (`{ 1 exch sub }`, or Type 2 with
`/C0 [1] /C1 [0]`) is the standard idiom for "mask out where the group is bright" — so
ignoring it hides exactly the wrong half of the content. That one is parsed
(`functions::read_transfer_lut`), sampled to a 256-entry LUT, carried on the wire as
`SoftMaskTransfer` (tag 13) and applied by the renderer. Note the asymmetry: the same key
name in two dictionaries, one ignorable and one not.

### Why `/HT` is ignored

Deliberately, not accidentally. A halftone screen exists to represent continuous tone on a
**bilevel** device by trading spatial resolution for tonal resolution. The output here is
8-bit-per-channel antialiased RGB, which represents the requested tone directly. Applying a
screen could only throw tonal resolution away. Same reasoning covers `/BG`, `/BG2`, `/UCR`
and `/UCR2`, which are defined only for the DeviceGray → DeviceCMYK conversion that never
happens here.

---

## 3. Colour spaces (§8.6, Table 61)

| Space | Status | Note |
| --- | --- | --- |
| DeviceGray, DeviceRGB, DeviceCMYK | full | Plus the `/G` `/RGB` `/CMYK` inline abbreviations. |
| CalGray, CalRGB | full | White point, gamma, matrix. |
| Lab | full | White point and `/Range`. |
| ICCBased | **partial** | Resolved through `/Alternate`, or by `/N` when absent. There is no ICC transform engine; this is what every non-colour-managed viewer does and the deviation is small for the sRGB-like profiles that dominate. |
| Indexed | full | Any base space, `/Hival`, lookup as string or stream. |
| Separation | full | Tint transform through any function type. |
| DeviceN | full | As Separation, n-component. |
| Pattern | full | With and without an underlying base space. |
| `/DefaultRGB` `/DefaultGray` `/DefaultCMYK` | **missing** | §8.6.5.6 resource-level substitution of a CIE-based space for a device space. Likelihood: rare. Visibility: none — ignoring them means using the device space directly, which is what viewers do anyway. Not worth implementing. |

## 4. Shadings (§8.7.4.5)

| Type | Status |
| --- | --- |
| 1 function-based | full |
| 2 axial | full — `/Domain`, both `/Extend` flags, `/Background`, `/BBox` |
| 3 radial | full — including the non-negative-radius constraint and both `/Extend` flags |
| 4 free-form Gouraud triangle mesh | full |
| 5 lattice-form Gouraud mesh | full — `/VerticesPerRow` |
| 6 Coons patch mesh | full |
| 7 tensor-product patch mesh | full — including the 4 interior control points |

`/Function` on types 4-7 (colour as a single parametric value through a function rather
than as direct components, §8.7.4.5.5) is handled. `/AntiAlias` (Table 78) is ignored;
the spec itself calls it a hint that may be disregarded.

## 5. Functions (§7.10)

| Type | Status |
| --- | --- |
| 0 sampled | full — n-D multilinear interpolation, all of Table 39's `/BitsPerSample` values, `/Encode`, `/Decode` |
| 2 exponential | full — with `/Range`-implied output arity when `/C0`/`/C1` are absent |
| 3 stitching | full |
| 4 PostScript calculator | full — the entire operator set of Table 42 |

Array-of-functions is supported everywhere a `/Function` may be an array.

## 6. Filters (§7.4) and fonts (§9.5-9.7)

All ten filters are recognised, with abbreviations: `ASCIIHexDecode`, `ASCII85Decode`,
`LZWDecode`, `FlateDecode` (both with `/Predictor`), `RunLengthDecode`, `CCITTFaxDecode`,
`DCTDecode`, `JPXDecode` (decoded in-process via the `openjp2` port, not passed through),
`JBIG2Decode`, `Crypt`.

Font types: Type1, MMType1, TrueType, Type3, and Type0 over CIDFontType0/CIDFontType2 are
all real. Embedded programs are rendered as true outlines from `/FontFile` (Type 1
eexec), `/FontFile2` (TrueType/OpenType) and `/FontFile3` (bare CFF and OpenType-CFF);
substitute typefaces are the fallback, not the primary path. The standard 14 fall back to
AFM metrics. The documented exception is the compiled code→CID tables of the predefined
CJK CMaps.

---

## 7. Triage: what is actually left

Ordered by (likelihood × visibility), which is the only ordering worth having.

0. **A form XObject inside a disabled optional-content group painted anyway — FIXED.**
   The single highest-impact gap found across all rounds, and the strongest candidate for
   the original "renders things that shouldn't even be there" report. `oc_stack` is a local
   in `interpret_content_seeded` that no nested stream inherits. The `Do` arm gated its
   Image branch, its `/BBox` `ClipPush` and its `GroupPush` on the hidden flag — but not the
   recursive interpret call. So `/OC1 BDC /Fm0 Do EMC` with OC1 switched OFF painted the
   form's ENTIRE contents, and painted them UNCLIPPED, because the clip that would have
   bounded them was suppressed by the very flag the recursion ignored. §8.11.2: content in
   a disabled optional-content group shall not be drawn.

   Round 1 fixed the BMC/BDC/EMC balance and round 2 fixed the text operators; `Do` was
   missed by both, and by two full review passes, because there is no wrong-looking code to
   review — the gate is simply absent. The XObject's own `/OC` key (§8.11.3.3) was always
   handled; only the surrounding bracket state was lost.

   Fixed by gating the recursion rather than threading the flag into it: a hidden form is
   skipped whole, which is both cheaper and impossible to get half-right. `text_only`
   deliberately still descends, because that caller is `search::build_index` and the policy
   set in the `Tj` arm is that a hidden layer is hidden, not absent — its text stays
   searchable. Covered by `a_form_xobject_in_a_disabled_oc_group_paints_nothing`, which
   asserts both directions and pins the fixture with an ON-layer control.

   Every other recursion site was audited for the same omission and all are already gated:
   tiling-pattern cells and pattern strokes (behind the painting arms' hidden check),
   soft-mask groups (same), Type 3 CharProcs (via render mode 3 in `show_string_type3`).
   Annotation appearance streams are not inside page-content brackets at all and carry
   their own `/OC` (§12.5.3), so `oc_stack` does not apply to them.

0b. **Group alpha was silently dropped whenever a soft mask was active — FIXED.**
   §11.6.6 resets the alpha constants to 1.0 on entering a transparency group, because
   `/ca` applies ONCE to the group's composited result. That reset is gated on
   `pushed_group`, and round 2 had made the group push and the soft-mask bracket mutually
   exclusive — so a form carrying both `/ca` < 1 and an `/SMask` applied `ca` to every
   element inside instead, over-darkening wherever that content overlapped itself. Exactly
   the failure the comment two lines above the reset warns about.

   Fixed by making the two non-exclusive. `sm_start` is captured above the push, so
   `wrap_with_soft_mask` inserts `SoftMaskPush` outside the group: the group composites
   (applying `ca` once) inside the masked layer, then the mask applies to that result, which
   is the §11.6.5.1 order. Covered by
   `group_alpha_resets_even_when_a_soft_mask_is_active`, whose fixture uses two OVERLAPPING
   fills because overlap is the only shape that can witness the bug.

   The reset stays gated on `pushed_group`, so when `MAX_PRIMITIVES` or the group-depth cap
   demotes the push, `ca` is still applied per element. That is deliberate, not a residual:
   with no composite to apply it to once, resetting anyway would drop `ca` entirely and
   paint the form fully opaque — wrong for all content, where per-element is wrong only
   where content overlaps itself. Round 2's overprint lesson again.

1. **`sh` in a nested content stream — FIXED in round 3, everywhere including the one
   documented remaining case.** §8.7.4.1 requires `sh` to cover the whole clip region, so `rasterize_shading`
   refuses to invent an extent and paints *nothing* when given neither a shading `/BBox` nor
   a clip extent. That extent is a separate ARGUMENT and is not the `ClipPush` primitive — a
   clip bounds the shading at render time, the extent is what lets it produce a raster at
   all. Only the page-level caller seeded it; every nested stream reached the interpreter
   through `interpret_content`, which passes `None`. A gradient drawn with `sh` inside a form
   XObject was therefore invisible.

   Now seeded from the box each stream is clipped to: form XObjects (§8.10.1), soft-mask
   groups (§11.6.5.2), tiling-pattern cells including both malformed-pattern fallbacks
   (§8.7.3.1), and annotation appearance streams (§12.5.5, fixed by `residuals`).

   One of these was worse than "invisible gradient" and worth calling out, because it shows
   how a missing seed can corrupt a DECISION rather than just an output. The periodic-raster
   path rasterizes a pattern cell once and repeats it as a bitmap, but only if the cell
   consists solely of fills and strokes. Unseeded, a cell whose content was `sh` produced no
   Image primitive at all, so the cell *looked* like pure fills/strokes, the periodic path
   was taken, and the gradient was silently dropped from every tile. Seeded, the Image
   appears, the gate correctly rejects it, and the per-tile path paints it.

   **Remaining, deliberately:** Type 3 glyph CharProcs (`draw.rs`). Unlike every case above
   there is no correct box to seed from — §9.6.4.2 permits an all-zero `/FontBBox`, in which
   case no assumptions may be made about glyph extent, and §9.6.5 does not clip a CharProc to
   it. Inventing one would be the guess `rasterize_shading` exists to refuse. A CharProc
   containing `sh` is also close to unheard of; real Type 3 glyphs are image masks or simple
   fills.

   Worth remembering as a pattern: the comment on that early return in `images.rs` asserts
   the `None` arm "should be unreachable in practice" because the page device box is seeded.
   It was accurate about the caller it considered and silently wrong about five others. A
   claim of unreachability is only as good as its enumeration of callers.
2. **ExtGState `/Font` — FIXED in round 3.** Total-text-degradation failure when present,
   though rarely present.
3. **ExtGState `/FL` — FIXED in round 3.** The one Table 58 key that had full plumbing
   (`bezier_steps_for_flatness`) and no parser.
4. **`/Interpolate` on images — built, never wired.** `images::image_should_interpolate`
   computes the right answer and nothing calls it, so every image is bilinearly smoothed on
   magnification, including bilevel art: a magnified QR code, barcode or scanned fax gets a
   grey ramp and a translucent fringe instead of hard black and white (§8.9.5.1 Table 89).
   Blocked on the wire format — `wire.rs` reserves a v11 `u8 interpolate` in the Image arm
   and the Kotlin parser already has the `isV11` gate, so this needs a `Prim::Image` field,
   a `WIRE_VERSION` bump and a Kotlin constant bump **atomically**. Cross-language; not
   landed here.
5. **Text clip (`Tr` 4-7) from Type 3 glyphs and from glyphs with no recoverable Unicode.**
   The clip is accumulated on the Kotlin side from substitute-glyph text records, and those
   two cases emit none, so the clip ends up under-constrained: content that should show
   through letterforms floods the whole area instead. Note the failure direction — the
   renderer guards with `if (hasTextClip)`, so an empty accumulator is a no-op rather than
   a blackout, which is the safe way round. Fixing it means clipping to `Fill` contours on
   the Kotlin side; cross-language, and niche.
6. **`d1` colour suppression (§9.6.5).** A `d1` Type 3 glyph is a shape only and colour
   operators inside it shall be ignored; here they take effect. Worst case is a glyph whose
   CharProc sets white and paints invisibly where a conforming reader would use the current
   fill colour. Real Type 3 producers (dvips, Metafont, TeX bitmap fonts) emit `d1` followed
   by an image mask and no colour operators, so this is close to unreachable in practice.
   Deliberately not fixed: threading a "colour is locked" flag through `interpret_content`
   changes a signature used by four files this round's ownership splits across, for a case
   no producer generates.
7. **`/DefaultRGB` / `/DefaultGray` / `/DefaultCMYK` (§8.6.5.6).** Not worth implementing;
   see §3.

Everything else in Clause 10 is ignored on purpose, and §2 says why for each key.

## 8. Dead-code verdicts

Three warnings survived earlier rounds. The distinction that matters is *leftover* versus
*built and never wired up* — the second is a real bug. Established by comparing
`cargo check --lib` against `cargo check --profile test --lib`: an item that warns in the
first but not the second is read **only by tests**.

| Item | Verdict |
| --- | --- |
| `images::image_should_interpolate` | **Built and never wired up — a real gap.** Its own doc comment names the handoff and `wire.rs` reserves the field for it. Blocked on wire.rs + Kotlin; see §7 item 4. |
| `images::shading_device_size` | **Superseded leftover — delete.** `rasterize_shading`'s own `auto_size()` closure does the same job from the device-space clip bbox, which is the right quantity; `shading_device_size` uses the unit-square→device matrix, which is not. Every caller passes `size == 0`, so `auto_size()` always wins. Its only reference is its own test. |
| `FontInfo::base_font` | **Diagnostic field, not unfinished work.** Its only reader is a `println!` in `debug_ishi_test`. The parts of the base font name that matter downstream are already distilled into `style.bold`/`style.italic` and `family` at parse time and shipped in the v8 `fontFlags` byte. Using the raw string for exact-family substitution would need a per-primitive wire string that nobody has started. Keep and annotate, or delete. |

Note these three no longer surface under `cargo check --all-targets`, which compiles the
tests and so sees the test-only readers. The verdicts above stand; reproduce them with
`cargo check --lib`. `shading_device_size`'s verdict has since been actioned: it is gone
from the tree, and a grep for it now returns nothing. The other two are unchanged.

## 9. Memory, which is a coverage question too

A feature that aborts the process is not implemented either. `bench` measured peak heap on
a 400_000-rect page at 533 MiB, of which 415 MiB is the `Vec<Operation>` materialised in
full before interpretation starts, against 32 MiB of primitives at the `MAX_PRIMITIVES`
cap. So `MAX_PRIMITIVES` does not bound page memory in any meaningful way.

`MAX_CONTENT_OPS` was lowered 1_000_000 -> 660_000 as a deliberate cap rather than a
streaming refactor: a defensible bound now beats a half-finished refactor, and truncating
the tail of a page beats an uncatchable process abort that loses the whole document. At
`bench`'s 544 B/op that cuts the admitted worst case from ~519 MiB to ~342 MiB while
discarding no primitive the old value could have produced, because every operator above
~660_000 could only be read after `MAX_PRIMITIVES` had already stopped emitting.

Two things learned in the process are worth recording here rather than only in the code.
First, `MAX_CONTENT_OPS >= 2 * MAX_PRIMITIVES` is necessary but not sufficient: it is stated
for the densest shape (`re f`, 2 operators per prim), and a page also spends operators on
colour, `q`/`Q`, `cm` and clip setup, so setting the cap to exactly 2x leaves
`MAX_PRIMITIVES` unreachable and quietly disarms the truncation test. Second, a cap here
cannot bind on the path that matters without help: `content::MAX_OPERATIONS` ties the
lenient recovery tokenizer to it, but the strict `lopdf` parse materialises the vector in
full before `take` is applied, so a well-formed heavy page — the one `bench` measured — is
unaffected until `content.rs` truncates and shrinks after the parse.

The measurement table and the full reasoning live on the `MAX_CONTENT_OPS` and
`MAX_PRIMITIVES` doc comments in `src/graphics_state.rs`, kept next to the numbers so they
cannot drift apart from them.
