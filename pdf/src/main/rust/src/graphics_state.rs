use crate::*;

#[derive(Clone, Copy, PartialEq, Eq, Default, Debug)]
#[repr(u8)]
pub(crate) enum BlendMode {
    #[default]
    Normal = 0,
    Multiply = 1,
    Screen = 2,
    Overlay = 3,
    Darken = 4,
    Lighten = 5,
    ColorDodge = 6,
    ColorBurn = 7,
    HardLight = 8,
    SoftLight = 9,
    Difference = 10,
    Exclusion = 11,
    Hue = 12,
    Saturation = 13,
    Color = 14,
    Luminosity = 15,
}

impl BlendMode {
    /// Map a `/BM` name to a mode, or `None` when the name is not one of the
    /// Table 136 / Table 137 blend modes.
    ///
    /// This is separate from [`BlendMode::from_name`] because RECOGNITION cannot be
    /// read back off the result: `/Normal` and `/Compatible` are themselves legitimate
    /// Table 136 names that map to `Normal`, so `from_name(n) == Normal` conflates
    /// "the file asked for Normal" with "we have never heard of this name".
    ///
    /// §11.6.3 needs the distinction. When `/BM` is an ARRAY the reader shall use the
    /// first name in it that it RECOGNISES, and the array form exists so a file can
    /// name a future or vendor-specific mode first with a supported fallback behind
    /// it — `[/FutureVendorMode /Multiply]` shall composite as Multiply. Picking the
    /// first name outright would yield Normal; skipping every name that maps to Normal
    /// would mis-handle `[/Normal /Multiply]` in the other direction.
    pub(crate) fn from_name_checked(name: &[u8]) -> Option<Self> {
        Some(match name {
            b"Normal" | b"Compatible" => BlendMode::Normal,
            b"Multiply" => BlendMode::Multiply,
            b"Screen" => BlendMode::Screen,
            b"Overlay" => BlendMode::Overlay,
            b"Darken" => BlendMode::Darken,
            b"Lighten" => BlendMode::Lighten,
            b"ColorDodge" => BlendMode::ColorDodge,
            b"ColorBurn" => BlendMode::ColorBurn,
            b"HardLight" => BlendMode::HardLight,
            b"SoftLight" => BlendMode::SoftLight,
            b"Difference" => BlendMode::Difference,
            b"Exclusion" => BlendMode::Exclusion,
            b"Hue" => BlendMode::Hue,
            b"Saturation" => BlendMode::Saturation,
            b"Color" => BlendMode::Color,
            b"Luminosity" => BlendMode::Luminosity,
            _ => return None,
        })
    }

    /// Map a `/BM` name to a mode, treating an unrecognised name as `Normal`
    /// (§11.6.3). Use [`BlendMode::from_name_checked`] instead when the caller has to
    /// tell an unrecognised name from an explicit `/Normal`.
    pub(crate) fn from_name(name: &[u8]) -> Self {
        BlendMode::from_name_checked(name).unwrap_or(BlendMode::Normal)
    }
}

#[cfg(test)]
mod blend_mode_tests {
    use super::*;

    // §11.6.3: for an ARRAY /BM the reader uses the first name it RECOGNISES. That
    // rule is only expressible if recognition is distinguishable from the Normal
    // result, which is what `from_name_checked` exists for. Both directions matter:
    // a leading /Normal must WIN (it is recognised), and a leading vendor name must
    // be SKIPPED rather than collapsing the array to Normal.
    #[test]
    fn recognition_is_distinguishable_from_the_normal_result() {
        assert_eq!(BlendMode::from_name_checked(b"Normal"), Some(BlendMode::Normal));
        assert_eq!(BlendMode::from_name_checked(b"Compatible"), Some(BlendMode::Normal));
        assert_eq!(BlendMode::from_name_checked(b"Multiply"), Some(BlendMode::Multiply));
        assert_eq!(BlendMode::from_name_checked(b"FutureVendorMode"), None);
        assert_eq!(BlendMode::from_name_checked(b""), None);
        // The lenient wrapper still folds an unrecognised name to Normal.
        assert_eq!(BlendMode::from_name(b"FutureVendorMode"), BlendMode::Normal);

        // The two array cases the distinction exists to separate.
        let first_recognised = |names: &[&[u8]]| -> BlendMode {
            names
                .iter()
                .find_map(|n| BlendMode::from_name_checked(n))
                .unwrap_or(BlendMode::Normal)
        };
        assert_eq!(
            first_recognised(&[b"Normal", b"Multiply"]),
            BlendMode::Normal,
            "a leading /Normal is recognised and wins"
        );
        assert_eq!(
            first_recognised(&[b"FutureVendorMode", b"Multiply"]),
            BlendMode::Multiply,
            "an unrecognised leading name falls through to the supported fallback"
        );
    }

    // All four non-separable modes (Table 137) are present; a missing one silently
    // composites as Normal, which looks plausible and is wrong.
    #[test]
    fn all_table_136_and_137_names_are_recognised() {
        for n in [
            &b"Normal"[..], b"Compatible", b"Multiply", b"Screen", b"Overlay", b"Darken",
            b"Lighten", b"ColorDodge", b"ColorBurn", b"HardLight", b"SoftLight",
            b"Difference", b"Exclusion", b"Hue", b"Saturation", b"Color", b"Luminosity",
        ] {
            assert!(
                BlendMode::from_name_checked(n).is_some(),
                "{} is a Table 136/137 blend mode",
                String::from_utf8_lossy(n)
            );
        }
    }
}

/// Active ExtGState soft mask. The mask group is rendered at the CTM in effect
/// when the mask was set (per ISO 32000 11.6.5.2), optionally over a `/BC`
/// backdrop color.
#[derive(Clone)]
pub(crate) struct SoftMask {
    pub(crate) group_id: ObjectId,
    /// 0 = alpha, 1 = luminosity.
    pub(crate) mask_type: u8,
    pub(crate) ctm: Mat,
    /// `/BC` backdrop color components (in the group's colorspace), if given.
    pub(crate) backdrop: Option<Vec<f64>>,
    /// `/TR` transfer function sampled to a 256-entry lookup table (§11.6.5.2), or
    /// `None` for `/Identity` and for any function within one 8-bit step of it.
    ///
    /// 11.6.5.2 requires the mask value to be passed through `/TR` before use. An
    /// *inverting* `/TR` is the standard idiom for "mask out where the group is bright",
    /// so ignoring one hides exactly the wrong region rather than merely being
    /// imprecise. Populated by `functions::read_transfer_lut`; carried to the renderer
    /// on the `SoftMaskPush` wire record.
    pub(crate) tr: Option<[u8; 256]>,
}

#[derive(Clone)]
pub(crate) struct GraphicsState {
    pub(crate) ctm: Mat,
    pub(crate) fill: u32,
    pub(crate) stroke: u32,
    pub(crate) line_width: f64,
    pub(crate) line_cap: u8,
    pub(crate) line_join: u8,
    pub(crate) miter_limit: f64,
    pub(crate) alpha_fill: f64,
    pub(crate) alpha_stroke: f64,
    pub(crate) non_stroke_cs: CsKind,
    pub(crate) stroke_cs: CsKind,
    pub(crate) font_key: Vec<u8>,
    pub(crate) font_size: f64,
    /// Character spacing (Tc), user-space units.
    pub(crate) char_spacing: f64,
    /// Word spacing (Tw), user-space units (applies to single-byte code 32).
    pub(crate) word_spacing: f64,
    /// Horizontal scaling (Tz) as a fraction (100% = 1.0).
    pub(crate) h_scale: f64,
    /// Text rise (Ts), user-space units.
    pub(crate) rise: f64,
    /// Text leading (TL), user-space units. Part of the text state, so it is
    /// saved/restored by q/Q.
    pub(crate) leading: f64,
    /// Text rendering mode (Tr). 3 = invisible, 7 = clip-only (not drawn).
    pub(crate) render_mode: i64,
    /// Dash pattern (user-space segment lengths) and phase; empty = solid.
    pub(crate) dash: Vec<f64>,
    pub(crate) dash_phase: f64,
    pub(crate) flatness: f64,
    pub(crate) blend_mode: BlendMode,
    /// Active fill/stroke pattern (object id) when the colorspace is `/Pattern`.
    pub(crate) fill_pattern: Option<ObjectId>,
    pub(crate) stroke_pattern: Option<ObjectId>,
    /// Active ExtGState soft mask, or `None` when `/SMask` is `/None`.
    pub(crate) soft_mask: Option<SoftMask>,
}

impl Default for GraphicsState {
    fn default() -> Self {
        GraphicsState {
            ctm: IDENTITY,
            fill: 0xFF00_0000,
            stroke: 0xFF00_0000,
            line_width: 1.0,
            line_cap: 0,
            line_join: 0,
            miter_limit: 10.0,
            alpha_fill: 1.0,
            alpha_stroke: 1.0,
            non_stroke_cs: CsKind::DeviceGray,
            stroke_cs: CsKind::DeviceGray,
            font_key: Vec::new(),
            font_size: 0.0,
            char_spacing: 0.0,
            word_spacing: 0.0,
            h_scale: 1.0,
            rise: 0.0,
            leading: 0.0,
            render_mode: 0,
            dash: Vec::new(),
            dash_phase: 0.0,
            flatness: 0.0,
            blend_mode: BlendMode::Normal,
            fill_pattern: None,
            stroke_pattern: None,
            soft_mask: None,
        }
    }
}

/// Bezier flattening is adaptive: see `interpret::bezier_steps_for_flatness`,
/// which derives the segment count from the curve's device-space control-polygon
/// length with `/Flatness` as the tolerance (10.6.2), clamped to 4..64. There is
/// deliberately no fixed step constant here — a fixed count left large curves
/// visibly faceted, which was finding E-16.
pub(crate) const MAX_CLIP_DEPTH: usize = 64;
/// NOT the enforced `q` nesting limit, despite the name. The interpreter's only
/// use of it is to derive [`interpret::MAX_GRAPHICS_STACK_HARD`] (`* 16` = 2048),
/// which is what the `q` arm actually checks; nothing checks 128. Left as the
/// stated base because the hard cap is documented relative to it, but do not read
/// this as "128 saved states" — §8.4.2 puts no limit on `q` nesting and the
/// interpreter admits 2048 before it starts dropping saves.
pub(crate) const MAX_GRAPHICS_STACK: usize = 128;
/// Ceiling on primitives emitted for one page, and — since [`MAX_CONTENT_OPS`] was raised
/// off 200_000 — the binding constraint on OUTPUT for every content shape rather than only
/// for text-heavy ones.
///
/// It is NOT the page's memory budget, despite what this comment used to claim. `bench`
/// measured net live bytes through the global allocator (release, median of 5+ runs) and
/// the primitive vector is a small minority of the heap. RETAINED and PEAK are separated
/// because they answer different questions — peak-during-parse includes transient parser
/// scratch and so must NOT be attributed to the operator vector:
///
/// | rects requested | ops parsed | ops RETAINED | ops peak | FULL peak | prims retained | prims bytes |
/// |---|---|---|---|---|---|---|
/// | 50_000 | 100_001 | 51.92 MiB | 53.87 MiB | 66.69 MiB | 50_000 | 5.34 MiB |
/// | 200_000 | 400_001 | 207.68 MiB | 215.56 MiB | 266.69 MiB | 200_000 | 21.36 MiB |
/// | 400_000 | 800_001 | 415.36 MiB | 431.11 MiB | 533.37 MiB | 300_000 (CAPPED) | 32.04 MiB |
///
/// So at the cap: 32 MiB of primitives against 415 MiB genuinely RETAINED in the
/// `Vec<Operation>` that `content::page_operations` materialises in full BEFORE the
/// interpreter loop starts, inside a 533 MiB peak. That vector costs a measured floor of
/// ~544 bytes PER OPERATOR regardless of how many operands the operator carries, so it
/// scales with operator COUNT alone and cannot be reduced by simplifying content.
///
/// Neither this cap nor [`MAX_CONTENT_OPS`] bounds that vector — see the note there on why
/// `take` frees nothing. Anyone trying to reduce peak memory has to attack the operator
/// vector (streaming the content parse), not this number; lowering this only discards
/// output.
///
/// Both content shapes can reach it, which is worth knowing before anyone moves it: a path
/// page emits roughly one prim per two operators, while `show_string` emits one Text prim
/// PER GLYPH, so a single 60-character `Tj` is one operator and sixty primitives. A dense
/// table or a phone-book page therefore reaches this from a few thousand operators.
///
/// Sizing: `size_of::<Prim>()` is 112 bytes — measured by `bench` on this tree, i.e. AFTER
/// `SoftMaskTransfer` was boxed, so the boxing is already reflected. `SoftMaskTransfer` is
/// consequently no longer the widest variant; `Image`/`ImageTiled` are, carrying a `Mat`
/// (48 B) plus a `Vec<u8>` (24 B). A const assertion in `model.rs` pins this. A simple Fill
/// or Text adds two heap allocations on top, so ~150-200 bytes each in practice — the
/// 32 MiB above.
///
/// The serialised copy in the wire buffer is held simultaneously with `prims` and costs a
/// measured ~51 bytes per primitive (`bench`, allocator delta across the serialise call
/// itself — NOT derived by subtracting the columns above, which would also capture every
/// other render-path allocation and overstate it). At this cap that is ~14.6 MiB, so
/// raising the cap scales both terms and the wire copy is the smaller one by roughly 2x.
///
/// A Rust OOM is an uncatchable process abort, so this remains a floor the Kotlin-side
/// bitmap/primitive caps cannot substitute for; the Kotlin bound should sit ABOVE this as a
/// backstop against a corrupt count field, never below.
pub(crate) const MAX_PRIMITIVES: usize = 300000;
pub(crate) const MAX_ANNOTATIONS: usize = 10000;
pub(crate) const MAX_IMAGE_DIM: u32 = 20000;
pub(crate) const MAX_IMAGE_BYTES: usize = 16 * 1024 * 1024;
pub(crate) const MAX_IMAGE_PIXELS: usize = 16 * 1024 * 1024; // ~16 MP cap
/// Decoded raster images are downscaled (preserving aspect) so their longer side
/// does not exceed this, capping per-image RGBA memory on device. 2048px is well
/// above a full page's on-screen pixel width, so quality is preserved while a
/// large source (e.g. 2480×3452 ≈ 33 MB) drops to ≈ 12 MB.
pub(crate) const IMAGE_DOWNSCALE_MAX_DIM: u32 = 2048;
/// Patch/vertex-record cap for mesh shadings (Types 4-7). Illustrator gradient
/// meshes routinely exceed a few thousand patches, and truncating mid-mesh leaves
/// a straight-edged cut across the gradient, so this is deliberately generous;
/// [`MAX_SHADING_TRIANGLES`] is the binding limit on actual work.
pub(crate) const MAX_SHADING_PATCHES: usize = 8000;
/// Total tessellated triangles kept from one mesh shading. A well-formed mesh
/// tiles its area, so per-triangle rasterization cost shrinks as the patch count
/// grows and the real cost stays near the raster area; this bounds the
/// pathological heavily-overlapping case.
pub(crate) const MAX_SHADING_TRIANGLES: usize = 1_000_000;
/// Bound on a SINGLE shading's raster, applied by both `images::rasterize_shading`
/// (types 1-3) and `shading::rasterize_shading_mesh` (types 4-7).
///
/// These rasters are NOT transient in aggregate: every one on the page stays live in
/// `prims` until the page is finished and is then copied wholesale into the wire
/// buffer, so peak residency is roughly twice the sum of all of them — reached
/// inside Rust, before Kotlin is handed anything. A cap on the Kotlin bitmap heap
/// cannot prevent that, because a Rust OOM is an uncatchable process abort. At
/// `auto_size()`'s 1024 long-side clamp a near-square shading would otherwise reach
/// 4 MB, so a gradient-heavy page (issue #321: 131 shadings) peaked near 500 MB x2.
pub(crate) const MAX_SHADING_RASTER_BYTES: usize = 1024 * 1024;
/// Tighter bound for AXIAL and RADIAL shadings (types 2 and 3) specifically.
///
/// Their colour is a function of a single scalar `t`, looked up from a 256-entry LUT, so
/// the raster contains at most 256 distinct colours no matter how large it is — a
/// 512x512 ramp carries strictly no more information than a 256x256 one, and the
/// renderer bilinearly upscales either. This is not a quality/memory tradeoff, it is
/// removing bytes that cannot encode anything.
///
/// It matters because axial and radial are the overwhelming majority in the wild
/// (issue #321's 131-shading page is all gradients), and per-shading is the only lever
/// available until a PER-PAGE budget exists: these rasters all stay live in `prims`
/// until the page is finished and are then copied wholesale into the wire buffer, so
/// peak residency is roughly twice their sum. 4x off the dominant case.
///
/// Types 1 (function-based, colour varies in 2-D over /Domain) and 4-7 (mesh) keep
/// [`MAX_SHADING_RASTER_BYTES`]; their colour really does vary per pixel.
pub(crate) const MAX_GRADIENT_RASTER_BYTES: usize = 256 * 1024;
/// Ceiling on one rasterized tiling-pattern cell (§8.7.3.3). A pattern cell is small by
/// definition — this is a 256x256 RGBA bitmap — and capping the RASTER is what lets the
/// tile COUNT be uncapped: replicating one bitmap across a hatched region costs a fixed
/// amount of memory, whereas re-emitting the cell's primitives per tile is what forced
/// the old 400-tile cap that left 99% of the region blank.
pub(crate) const MAX_TILE_RASTER_BYTES: usize = 256 * 1024;
pub(crate) const MAX_TYPE3_GLYPHS: usize = 500;
pub(crate) const MAX_TYPE3_PRIMS_PER_GLYPH: usize = 1000;
pub(crate) const MAX_PATTERN_RECURSION: u32 = 4;
pub(crate) const MAX_OC_STACK: usize = 32;
pub(crate) const MAX_SUBPATHS: usize = 20000;
/// Maximum dash-array entries accepted from `d` or from an ExtGState `/D`.
///
/// The comment here used to justify 32 as matching "the Kotlin wire decoder's bound".
/// That bound no longer exists: `SafePdfParser.kt`'s `TAG_STROKE` arm reads the count
/// as a `u8` and never rejects or clamps below 255 — the `> 32` throw it describes was
/// removed because it discarded a whole page over one stroke. (Its own comment there
/// still says "Rust caps at MAX_DASH_LEN (64)", which is stale in the other direction;
/// routed to the wire owner.) So nothing downstream requires 32.
///
/// What DOES bind: `wire.rs` writes the count as a `u8` and truncates at 255, and
/// `draw::emit_stroke` duplicates an odd-length array (§8.4.3.6 needs an even number
/// of on/off phases), so the emitted length can be twice what is stored here. 32 keeps
/// that doubled worst case at 64, comfortably inside the `u8`, and is 4x the longest
/// dash pattern seen in practice. §8.4.3.6 puts no limit on the array, so exceeding
/// this silently truncates the pattern — which is why it is set well above real files
/// rather than at the PostScript-era limit of 8.
pub(crate) const MAX_DASH_LEN: usize = 32;
/// Ceiling on operators READ from one content stream, via the `ops.iter().take(...)`
/// in `interpret_content_seeded`.
///
/// This is a TIME guard, not a memory guard, and the distinction is load-bearing: `ops` is
/// a fully-materialised `Vec` before that loop ever runs, so `take` frees nothing — it only
/// stops work. The memory guard is [`MAX_PRIMITIVES`], which is enforced at every single
/// content-emitting push.
///
/// MEASURED CAVEAT (`bench`, release, allocator-instrumented): that operator vector is the
/// DOMINANT term in the page's memory — 415.36 MiB genuinely RETAINED on an 800_000-operator
/// page, inside a 533 MiB peak, against 32 MiB of primitives. Its cost is a measured floor
/// of ~544 bytes PER OPERATOR irrespective of operand count, so it tracks operator COUNT
/// alone. Neither cap bounds it: this one cannot, because the vector already exists in full
/// when it is applied, and [`MAX_PRIMITIVES`] bounds only the output. Bounding it for real
/// needs a streaming content parse. Do not read "the memory guard is MAX_PRIMITIVES" as
/// "the page's memory is bounded" — raising THIS constant raises retained memory linearly
/// at ~544 B per operator, which is the one real cost of having raised it off 200_000.
///
/// At the old 200_000 this was nonetheless the SMALLEST of the three caps in series
/// (here, `MAX_PRIMITIVES`, and the Kotlin decoder's own bound), so it silently bound
/// first and made the other two unreachable. One `re f` pair is two operators, so a plain
/// path page topped out at ~100_000 prims — a third of the 300_000 already sanctioned
/// here, and a tenth of what the Kotlin side allows. Raising the consumer-side bound could
/// not possibly help while this one truncated the stream first, which is why a dense
/// vector page stopped rendering part-way through.
///
/// Sized from [`MAX_PRIMITIVES`] plus headroom for the operators that paint nothing. At the
/// densest realistic shape of 2 operators per primitive, 600_000 operators is where a path
/// page saturates the 300_000-prim cap — but a page also spends operators on colour,
/// `q`/`Q`, `cm` and clip setup, so exactly 2x leaves `MAX_PRIMITIVES` unreachable by
/// however many of those it has (at exactly 600_000,
/// `exceeding_the_primitive_cap_keeps_the_bracket_structure_intact` fell ~7 prims short of
/// its own cap). 660_000 is that floor plus 10%.
///
/// It was 1_000_000, and that headroom was not free: the operators above ~660_000 could only
/// ever be read AFTER `MAX_PRIMITIVES` had stopped emitting, so at `bench`'s measured
/// 544 B/op they cost ~176 MiB to admit content that by construction cannot reach the
/// canvas. The admitted worst case drops from ~519 MiB to ~342 MiB without discarding a
/// single primitive the old value would have produced.
///
/// What it does cost: no value here makes `MAX_PRIMITIVES` reachable for EVERY page, because
/// operators-per-primitive is unbounded — a CAD or map export at `q cm … Q` per element runs
/// ~5 operators per primitive and now truncates around 130_000 prims. That is a deliberate
/// trade, not an oversight. Truncation loses the tail of a page; an OOM is an uncatchable
/// process abort that loses the page, the document and the process. It is also still 3x what
/// the old 200_000 admitted, which is the value that caused the original
/// stops-rendering-part-way bug.
///
/// This cap CANNOT be the whole answer, and the reason is worth stating so nobody assumes it
/// is: `content::MAX_OPERATIONS` ties the lenient recovery tokenizer to this number, so for a
/// stream that took the recovery path the bound is real. The STRICT `lopdf` parse — which is
/// the path a well-formed heavy page takes, and the path `bench` measured at 533 MiB — is
/// not bounded by it, because the vector is complete before `take` is ever applied. Making
/// this cap bind there needs a `truncate` plus `shrink_to_fit` immediately after the strict
/// parse in `content.rs` (truncate alone drops the length and keeps the allocation), or a
/// streaming parse. Both live in `content.rs`.
pub(crate) const MAX_CONTENT_OPS: usize = 660_000;
