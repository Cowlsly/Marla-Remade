use crate::*;

#[derive(Clone, Copy, PartialEq, Eq, Default)]
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
    pub(crate) fn from_name(name: &[u8]) -> Self {
        match name {
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
            _ => BlendMode::Normal,
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
pub(crate) const MAX_GRAPHICS_STACK: usize = 128;
/// Ceiling on primitives emitted for one page. This is the real MEMORY budget and, since
/// [`MAX_CONTENT_OPS`] was raised off 200_000, the binding constraint for every content
/// shape rather than only for text-heavy ones.
///
/// Both shapes can reach it, which is worth knowing before anyone moves it: a path page
/// emits roughly one prim per two operators, while `show_string` emits one Text prim PER
/// GLYPH, so a single 60-character `Tj` is one operator and sixty primitives. A dense
/// table or a phone-book page therefore reaches this from a few thousand operators.
///
/// Sizing: `size_of::<Prim>()` is ~88 bytes and a simple Fill or Text adds two heap
/// allocations, so ~120-180 bytes each in practice — 300_000 is roughly 40-55 MB in
/// `prims`, held simultaneously with the ~15 MB serialised copy in the wire buffer. Raising
/// it scales both. A Rust OOM is an uncatchable process abort, so this is a floor the
/// Kotlin-side bitmap/primitive caps cannot substitute for; the Kotlin bound should sit
/// ABOVE this as a backstop against a corrupt count field, never below.
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
/// Maximum dash-array entries. Held at 32 to match the Kotlin wire decoder's
/// bound: a longer array used to make the parser reject the page outright, and
/// even with that softened to a clamp, exceeding it silently truncates the dash
/// pattern. Real dash arrays are almost never longer than 8.
pub(crate) const MAX_DASH_LEN: usize = 32;
/// Ceiling on operators READ from one content stream, via the `ops.iter().take(...)`
/// in `interpret_content_seeded`.
///
/// This is a TIME guard, not a memory guard, and the distinction is load-bearing: `ops` is
/// a fully-materialised `Vec` before that loop ever runs, so `take` frees nothing — it only
/// stops work. The memory guard is [`MAX_PRIMITIVES`], which is enforced at every single
/// content-emitting push.
///
/// At the old 200_000 this was nonetheless the SMALLEST of the three caps in series
/// (here, `MAX_PRIMITIVES`, and the Kotlin decoder's own bound), so it silently bound
/// first and made the other two unreachable. One `re f` pair is two operators, so a plain
/// path page topped out at ~100_000 prims — a third of the 300_000 already sanctioned
/// here, and a tenth of what the Kotlin side allows. Raising the consumer-side bound could
/// not possibly help while this one truncated the stream first, which is why a dense
/// vector page stopped rendering part-way through.
///
/// Sized so [`MAX_PRIMITIVES`] is the binding constraint for the densest realistic shape
/// (2 operators per primitive needs 600_000, leaving ~40% headroom) and this one only
/// catches genuinely pathological streams. Note this does NOT raise the memory ceiling by
/// one byte: `MAX_PRIMITIVES` was always the budget, it just could not be reached.
pub(crate) const MAX_CONTENT_OPS: usize = 1_000_000;
