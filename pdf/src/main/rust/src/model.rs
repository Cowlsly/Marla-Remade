use crate::*;

/// Drawing primitives in the page's *display* space: `page_base_matrix` has
/// already been applied, so `/CropBox` origin and `/Rotate` are baked into every
/// coordinate here. The origin is still bottom-left and Kotlin performs only the
/// Y-flip and the fit-to-width scale — it must NOT re-apply a crop or rotation.
/// Extended v3 adds GroupPush/Pop and blend. v8 adds font style flags for
/// bold/italic synthesis.
pub(crate) enum Prim {
    Text {
        x: f32,
        y: f32,
        size: f32,
        argb: u32,
        text: String,
        stroke_argb: Option<u32>,
        stroke_width: Option<f32>,
        /// Accurate advance for search rect alignment (not serialized in v2, v3 adds).
        advance: f32,
        /// PDF text rendering mode (Tr): 0 fill, 1 stroke, 2 fill+stroke, 3
        /// invisible, 4-6 = 0-2 plus add-to-clip, 7 clip-only. Serialized in v4.
        render_mode: u8,
        /// Blend mode (from the graphics-state `/BM`). Serialized in v5.
        blend: BlendMode,
        /// Font style flags recovered from BaseFont / FontDescriptor (v8).
        is_bold: bool,
        is_italic: bool,
        /// Generic font family for substitute typeface selection: 0 sans-serif,
        /// 1 serif, 2 monospace (packed into the v8 fontFlags byte, bits 2-3).
        font_family: u8,
        /// True when the glyph's real outline was already emitted as Fill/Stroke
        /// prims (embedded-font rendering); Kotlin then keeps this Text only for
        /// selection/search and does not paint it. Packed into fontFlags bit 4.
        outline: bool,
        /// PDF text rise (Ts) and horizontal scale (Tz fraction) for verif; used for
        /// spacing / synthetic slant debugging (not strictly needed on wire but helps).
        h_scale: f32,
    },
    Fill {
        argb: u32,
        even_odd: bool,
        /// One or more closed contours making up a single fill region. Interior
        /// contours (glyph counters / holes) are cut out by the even-odd or
        /// nonzero winding rule when all contours are filled as one path.
        contours: Vec<Vec<(f32, f32)>>,
        /// Blend mode (from the graphics-state `/BM`). Serialized in v5.
        blend: BlendMode,
    },
    Stroke {
        argb: u32,
        width: f32,
        /// Dash segment lengths in device space (empty = solid).
        dash: Vec<f32>,
        dash_phase: f32,
        cap: u8,
        join: u8,
        miter: f32,
        pts: Vec<(f32, f32)>,
        /// Blend mode (from the graphics-state `/BM`). Serialized in v5.
        blend: BlendMode,
    },
    /// A raster image placed by mapping the unit square through `ctm` (PDF image
    /// space). `format`: 0 = raw RGBA8888 (`w*h*4` bytes), 1 = JPEG bytes.
    Image {
        ctm: Mat,
        w: u32,
        h: u32,
        format: u8,
        data: Vec<u8>,
        /// Per-image alpha (from SMask or explicit) * alpha_fill, for transparency group compositing.
        alpha: f32,
        /// Blend mode (from the graphics-state `/BM`) for compositing the image.
        blend: BlendMode,
    },
    /// A tiling pattern (§8.7.3.3) as ONE cell bitmap repeated periodically, rather than
    /// as thousands of re-interpreted cells. Renders as a `BitmapShader` with
    /// `TileMode.REPEAT`: one draw call regardless of how many tiles are covered, and
    /// memory is O(one cell) instead of O(tiles).
    ///
    /// `ctm` maps the unit square onto ONE CELL, i.e. one period of the lattice. The
    /// bitmap's own dimensions ARE the repeat period, which is why `w`/`h` must be
    /// rasterized at `/XStep` x `/YStep` and NOT at the pattern `/BBox`: the two are
    /// independent in PDF, and a bitmap sized to the bbox would silently retile at the
    /// wrong spacing. Where the step exceeds the bbox the extra margin is transparent
    /// padding, which scales with the cell.
    ///
    /// Only emitted for NON-overlapping patterns. §8.7.3.1 permits `/XStep` smaller than
    /// the bbox, with later tiles painted over earlier ones; a periodic repeat cannot
    /// express overlap at any cost, so that case must use the per-tile path instead.
    /// `images::rasterize_pattern_cell` enforces both rules.
    ImageTiled {
        ctm: Mat,
        /// Cell raster, always RGBA8888 (`w*h*4` bytes). Transparent where unpainted.
        w: u32,
        h: u32,
        data: Vec<u8>,
        /// Lattice period in the `ctm`'s own (pattern) space. ADVISORY ONLY: do NOT
        /// compare these against `w`/`h`. Cell resolution is a free parameter — an
        /// over-budget cell is scaled down UNIFORMLY, which is transparent to a renderer
        /// that maps the bitmap's own corners onto the one-cell quad from `ctm`, so the
        /// bitmap dimensions and the period are related only by an arbitrary scale.
        /// (Cropping the cell or fitting sub-tiles inside it WOULD break the period; only
        /// uniform scaling is safe. `rasterize_pattern_cell` only ever scales uniformly.)
        xstep: f32,
        ystep: f32,
        /// Lattice extent to cover, in cell indices relative to `ctm`'s origin. A
        /// `REPEAT` shader may instead rely on the active clip, provided it covers at
        /// least this range.
        i0: i32,
        j0: i32,
        nx: u32,
        ny: u32,
        alpha: f32,
        blend: BlendMode,
    },
    ClipPush {
        even_odd: bool,
        /// Full path with bezier retention: flat encoding where cubic points are marked via flag?
        /// For v2 compatibility we keep as polygon pts (flattened). For v3 we emit with bezier as separate but here storage remains polyline with extra flag in serialization step.
        pts: Vec<(f32, f32)>,
        /// Raw bezier path for accurate clipping (optional, used for v3 wire).
        path_ops: Option<Vec<PathOp>>,
    },
    ClipPop,
    /// Marker emitted at `ET` when the just-ended text object used a clip render
    /// mode (Tr 4-7): the accumulated glyph outlines are intersected into the
    /// clip on the Kotlin side. v4 only.
    TextClipApply,
    /// Transparency group push (v3). Emits saveLayer with alpha/blend in Kotlin.
    GroupPush {
        isolated: bool,
        knockout: bool,
        alpha: f32,
        blend: BlendMode,
    },
    GroupPop,
    /// Begin an ExtGState soft-masked region (v5). `mask_type`: 0 = alpha,
    /// 1 = luminosity. Primitives until `SoftMaskContent` are the masked
    /// content; those from `SoftMaskContent` to `SoftMaskPop` are the mask.
    SoftMaskPush { mask_type: u8 },
    /// `/TR` transfer function for the soft mask just pushed, as a 256-entry lookup
    /// table (§11.6.5.2). Emitted IMMEDIATELY AFTER `SoftMaskPush` and only when `/TR`
    /// is not the identity; absent therefore means identity, which is the common case
    /// and costs nothing.
    ///
    /// A separate primitive rather than a field on `SoftMaskPush` so it is purely
    /// additive: not emitting it is a no-op, whereas a versioned field that someone
    /// forgets to write desynchronises the wire stream from that byte onward.
    ///
    /// Boxed because 256 bytes inline would grow every `Prim` in the page vector by
    /// ~3x — see `MAX_PRIMITIVES` for why per-prim size is load-bearing here.
    SoftMaskTransfer(Box<[u8; 256]>),
    /// Marker: switch from masked content to mask drawing (v5).
    SoftMaskContent,
    /// End a soft-masked region; composite the mask onto the content (v5).
    SoftMaskPop,
}

/// Path operation for bezier-retentive clip (Phase 5 fidelity).
#[derive(Clone)]
pub(crate) enum PathOp {
    Move(f32,f32),
    Line(f32,f32),
    Cubic(f32,f32,f32,f32,f32,f32),
    Close,
}

pub(crate) struct PageData {
    pub(crate) width: f32,
    pub(crate) height: f32,
    pub(crate) prims: Vec<Prim>,
}

/// Multiply the alpha channel of a primitive's color by `alpha_mul` (0..1). Used to
/// honor an annotation's constant opacity (`/CA`). Images: scale per-image alpha.
pub(crate) fn scale_prim_alpha(prim: &mut Prim, alpha_mul: f64) {
    let scale = |argb: &mut u32| {
        let a = ((*argb >> 24) & 0xFF) as f64;
        let na = (a * alpha_mul).round().clamp(0.0, 255.0) as u32;
        *argb = (*argb & 0x00FF_FFFF) | (na << 24);
    };
    let scale_opt = |argb: &mut Option<u32>| {
        if let Some(v) = argb {
            let a = ((*v >> 24) & 0xFF) as f64;
            let na = (a * alpha_mul).round().clamp(0.0, 255.0) as u32;
            *v = (*v & 0x00FF_FFFF) | (na << 24);
        }
    };
    match prim {
        Prim::Text { argb, stroke_argb, .. } => { scale(argb); scale_opt(stroke_argb); },
        Prim::Fill { argb, .. } => scale(argb),
        Prim::Stroke { argb, .. } => scale(argb),
        Prim::Image { alpha: img_a, .. } => {
            let cur = *img_a as f64;
            let na = (cur * alpha_mul.clamp(0.0,1.0)).clamp(0.0,1.0) as f32;
            *img_a = if na.is_nan() { 1.0 } else { na };
        },
        Prim::ImageTiled { alpha: img_a, .. } => {
            let cur = *img_a as f64;
            let na = (cur * alpha_mul.clamp(0.0,1.0)).clamp(0.0,1.0) as f32;
            *img_a = if na.is_nan() { 1.0 } else { na };
        },
        Prim::ClipPush { .. } => {},
        Prim::ClipPop => {},
        Prim::TextClipApply => {},
        Prim::GroupPush { alpha: ga, .. } => { let cur = *ga as f64; *ga = (cur * alpha_mul.clamp(0.0,1.0)) as f32; },
        Prim::GroupPop => {},
        Prim::SoftMaskPush { .. } => {},
        // A transfer LUT is a mask-shape function, not colour; constant opacity does
        // not scale it. Scaling it here would distort the mask curve rather than fade it.
        Prim::SoftMaskTransfer(_) => {},
        Prim::SoftMaskContent => {},
        Prim::SoftMaskPop => {},
    }
}

pub(crate) fn apply_alpha_to_argb(argb: u32, alpha_mul: f64) -> u32 {
    if (alpha_mul - 1.0).abs() < 1e-6 {
        return argb;
    }
    let a = ((argb >> 24) & 0xFF) as f64;
    let na = (a * alpha_mul).round().clamp(0.0, 255.0) as u32;
    (argb & 0x00FF_FFFF) | (na << 24)
}

// ---------------------------------------------------------------------------
// Object helpers

// ---------------------------------------------------------------------------
