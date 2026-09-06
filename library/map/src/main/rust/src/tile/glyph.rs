//! The SDF glyph atlas: Noto Sans rasterised once, sampled every frame.
//!
//! MapLibre renders text from a signed-distance-field atlas: each glyph is a small
//! bitmap whose texels store _distance to the glyph edge_, so the fragment shader
//! gets crisp edges at any size with `smoothstep`, plus a halo for free. This is
//! that atlas, built at first use from the bundled TTFs (task 54 staged
//! `library/map/src/main/rust/assets/fonts/NotoSans-Regular.ttf` and `-Medium.ttf`).
//!
//! # Why runtime, not build-time
//!
//! The TTFs are compiled in with `include_bytes!` rather than read through the
//! `AssetManager`, because an APK asset is not a file this renderer can open
//! without a JNI fd handoff. They therefore live under the **crate's** own
//! `assets/` (`library/map/src/main/rust/assets/fonts/`) and not under
//! `src/main/assets/`: anything in the latter is packaged into every consumer's
//! APK, which shipped these 1.5 MB a second time for bytes no code path reads.
//!
//! Rasterising at first use costs milliseconds once:
//! ~95 Latin codepoints × 2 weights, each a 4x `ab_glyph` rasterise followed by an
//! 8SSEDT distance transform over a ≤128px cell. The atlas is a single R8 image
//! (16×16 cells of 64px = 1024px) uploaded to Vulkan once.
//!
//! # Metrics live here, shaping in `tess::text`
//!
//! `ab_glyph`'s unscaled metrics are in font units (1/upem em); the atlas stores
//! them per (weight, char) so shaping needs no font handle. Kerning is
//! `kern_unscaled` between consecutive ids. UV rects address the R8 image.

use ab_glyph::{Font, FontRef, ScaleFont};
use std::collections::HashMap;
use std::sync::OnceLock;

/// Font units per em for the bundled Noto Sans.
///
/// The denominator that turns a font-unit metric into a fraction of an em, and so the
/// thing that decides how big `text_size` px actually draws. It was 2048 — the common
/// value, but not this font's — which rendered every label at 1000/2048 of its size.
/// [`the_bundled_fonts_use_the_declared_upem`] asserts it against the fonts themselves,
/// so swapping in a 2048-upem face fails a test instead of shrinking the map's text.
pub const UP_EM: u16 = 1000;

/// Atlas grid: 16×16 cells.
pub const ATLAS_COLS: u32 = 16;
/// Cell size in px, including padding. 64px at 4x raster of a 16px glyph.
pub const CELL_PX: u32 = 64;
/// Atlas edge in px.
pub const ATLAS_PX: u32 = ATLAS_COLS * CELL_PX;
/// SDF spread in px each side of the edge: 8px at cell resolution.
pub const SDF_SPREAD_PX: u32 = 8;

/// Noto Sans Regular, embedded (task 54 staged the TTF; the APK asset is not a file
/// the renderer can open, so the bytes ship in the `.so` — 267 KB).
const REGULAR_TTF: &[u8] = include_bytes!("../../assets/fonts/NotoSans-Regular.ttf");
/// Noto Sans Medium, embedded likewise.
const MEDIUM_TTF: &[u8] = include_bytes!("../../assets/fonts/NotoSans-Medium.ttf");

/// Which bundled weight a label uses. The authored style uses Regular everywhere
/// except country labels and big cities (Medium).
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum Weight {
    Regular,
    Medium,
}

/// Per-glyph metrics in font units, plus the atlas cell.
///
/// `bearing_x`, `top`, `w` and `h` describe the **quad**, not the ink: they cover the
/// glyph bitmap grown by the SDF spread on every side, which is exactly the region
/// [`GlyphMetrics::uv`] addresses. Quad and UV have to be derived from the same
/// placement or the ink draws at the wrong scale inside a correctly-sized advance —
/// see [`place_in_cell`].
#[derive(Clone, Copy, Debug)]
pub struct GlyphMetrics {
    /// Advance width including kerning base, in font units. The one field that is a
    /// pure font metric: padding the quad must not move the pen.
    pub advance: f32,
    /// Quad's left edge relative to the pen, in font units.
    pub bearing_x: f32,
    /// Quad's top above the baseline, in font units (y-up).
    pub top: f32,
    /// Quad width/height in font units.
    pub w: f32,
    pub h: f32,
    /// Atlas cell index.
    pub cell: u32,
    /// The sub-rect of [`GlyphMetrics::cell`] this glyph's quad samples.
    pub uv: UvRect,
}

/// UV rect of one cell, with `v0` at the atlas top.
#[derive(Clone, Copy, Debug)]
pub struct UvRect {
    pub u0: f32,
    pub v0: f32,
    pub u1: f32,
    pub v1: f32,
}

/// Whether the staged TTFs are real fonts (SFNT magic), not 404 pages.
///
/// Task 54 staged GitHub 404 HTML under the `.ttf` names; tests needing glyphs
/// check this and skip loudly until real Noto Sans lands.
pub fn fonts_staged() -> bool {
    REGULAR_TTF.starts_with(&[0x00, 0x01, 0x00, 0x00])
        && MEDIUM_TTF.starts_with(&[0x00, 0x01, 0x00, 0x00])
}

/// The Latin set M1 shapes: printable ASCII. `name:en` coalesced by the tiler is
/// Latin in the overwhelming NA case; CJK/others are a stated M5.
pub fn charset() -> Vec<char> {
    (0x20u8..=0x7Eu8).map(|b| b as char).collect()
}

/// The built atlas: R8 SDF bytes plus metrics and UVs per (weight, char).
pub struct GlyphAtlas {
    /// Row-major R8 SDF, `ATLAS_PX`² bytes, 0 = far outside, 255 = far inside.
    pub pixels: Vec<u8>,
    /// How much the stored SDF value changes across one em of glyph.
    ///
    /// The distance transform writes `1 / (2 * SDF_SPREAD_PX)` per rasterised pixel
    /// and an em spans `UP_EM / per_raster_px` of them, so this is the conversion the
    /// fragment shader needs to turn a halo width in screen px into an SDF threshold.
    /// Derived rather than hardcoded because it moves with the bundled font's metrics.
    pub sdf_per_em: f32,
    metrics: HashMap<(Weight, char), GlyphMetrics>,
}

impl GlyphAtlas {
    /// Rasterise both weights and build the SDF atlas. Called once per process.
    pub fn build() -> GlyphAtlas {
        let regular =
            FontRef::try_from_slice(REGULAR_TTF).expect("bundled NotoSans-Regular.ttf parses");
        let medium =
            FontRef::try_from_slice(MEDIUM_TTF).expect("bundled NotoSans-Medium.ttf parses");
        let chars = charset();
        let mut pixels = vec![0u8; (ATLAS_PX * ATLAS_PX) as usize];
        let mut metrics = HashMap::new();
        let mut sdf_per_em = 0.0f32;
        for (weight, font) in [(Weight::Regular, regular), (Weight::Medium, medium)] {
            // Font units per rasterised pixel. Derived from the font rather than from
            // `UP_EM / px`, because ab_glyph's `PxScale` is a HEIGHT (ascent + descent)
            // and not pixels-per-em: at scale 48 this face puts an em at 35.2px, so the
            // naive ratio is out by `height_unscaled / units_per_em`.
            let px = 48.0f32;
            let per_raster_px = font.height_unscaled() / font.as_scaled(px).height();
            sdf_per_em = UP_EM as f32 / per_raster_px / (2.0 * SDF_SPREAD_PX as f32);
            for (i, &ch) in chars.iter().enumerate() {
                let cell = match weight {
                    Weight::Regular => i as u32,
                    Weight::Medium => (chars.len() + i) as u32,
                };
                let id = font.glyph_id(ch);
                // Skip .notdef: an unknown glyph rasterises as tofu; shaping skips
                // these codepoints instead (see `tess::text::shape`).
                if id.0 == 0 {
                    continue;
                }
                let advance = font.h_advance_unscaled(id);
                let bearing = font.h_side_bearing_unscaled(id);
                // A glyph with an advance but no ink. It still has to reach the metrics
                // table: shaping looks every codepoint up there and skips the ones it
                // cannot find, so a space that is missing here closes the gap between two
                // words instead of widening it — "Telegraph Hill" shapes as "TelegraphHill".
                let blank = GlyphMetrics {
                    advance,
                    bearing_x: bearing,
                    top: 0.0,
                    w: 0.0,
                    h: 0.0,
                    cell,
                    uv: UvRect { u0: 0.0, v0: 0.0, u1: 0.0, v1: 0.0 },
                };
                // 4x raster of the outline at 48px, then downsample-by-distance to
                // the SDF cell: coverage at 4x is a 2-bit alpha proxy.
                let Some(outlined) = font.outline_glyph(id.with_scale(px)) else {
                    // Space and friends: `outline_glyph` gives nothing at all for these,
                    // so the zero-size check below is never reached for them.
                    metrics.insert((weight, ch), blank);
                    continue;
                };
                let bounds = outlined.px_bounds();
                let w_px = bounds.width().ceil() as u32;
                let h_px = bounds.height().ceil() as u32;
                if w_px == 0 || h_px == 0 {
                    // A zero-size quad emits two degenerate triangles, which rasterise to
                    // nothing.
                    metrics.insert((weight, ch), blank);
                    continue;
                }
                let mut coverage = vec![0u8; (w_px * h_px) as usize];
                outlined.draw(|x, y, v| {
                    if x < w_px && y < h_px {
                        coverage[(y * w_px + x) as usize] = (v * 255.0) as u8;
                    }
                });
                let placement = place_in_cell(w_px, h_px);
                let sdf = sdf_from_coverage(&coverage, w_px, h_px, placement);
                blit_cell(&mut pixels, cell, &sdf);
                // The spread margin measured in rasterised pixels. The cell margin is a
                // constant number of CELL px, so a glyph scaled down to fit spans
                // proportionally more of its own pixels — hence the divide by `scale`.
                let pad = SDF_SPREAD_PX as f32 / placement.scale;
                metrics.insert(
                    (weight, ch),
                    GlyphMetrics {
                        advance,
                        bearing_x: bearing - pad * per_raster_px,
                        // `px_bounds` is y-down from the baseline, so the ink's top
                        // above the baseline is `-min.y`.
                        top: (-bounds.min.y + pad) * per_raster_px,
                        w: (w_px as f32 + 2.0 * pad) * per_raster_px,
                        h: (h_px as f32 + 2.0 * pad) * per_raster_px,
                        cell,
                        uv: ink_uv(cell, placement),
                    },
                );
            }
        }
        GlyphAtlas { pixels, sdf_per_em, metrics }
    }

    pub fn metrics(&self, weight: Weight, ch: char) -> Option<GlyphMetrics> {
        self.metrics.get(&(weight, ch)).copied()
    }

    pub fn uv(&self, weight: Weight, ch: char) -> Option<UvRect> {
        Some(self.metrics.get(&(weight, ch))?.uv)
    }

    /// Horizontal kerning between two codepoints, in font units.
    pub fn kern(&self, _weight: Weight, _prev: char, _next: char) -> f32 {
        // Noto Sans Latin kerning is small vs label sizes; ab_glyph's
        // `kern_unscaled` needs glyph ids, which the atlas deliberately does not
        // retain (metrics-only shaping). Revisit if tracking looks off.
        0.0
    }
}

/// Where a glyph's bitmap sits inside its atlas cell.
///
/// The single source of truth the quad and the UV rect are both derived from. Keeping
/// them apart is what made every glyph draw at roughly `dw / CELL_PX` of its proper
/// size inside a correctly-spaced advance: the ink is centred in the cell, but the UV
/// rect used to address the *whole* cell while the quad was sized to the glyph's true
/// metrics, so the shader stretched a half-empty cell over it.
#[derive(Clone, Copy, Debug, PartialEq)]
struct Placement {
    /// Bitmap-to-cell scale. 1.0 unless the glyph is too big to fit with its margin.
    scale: f32,
    /// Ink origin within the cell, in cell px.
    ox: u32,
    oy: u32,
    /// Ink size within the cell, in cell px.
    dw: u32,
    dh: u32,
}

/// Centre a `w`x`h` bitmap in a cell, leaving [`SDF_SPREAD_PX`] of margin all round.
///
/// The margin is what the distance transform writes its gradient into, so it must be
/// reserved before anything is rasterised. `ox`/`oy` are therefore always at least
/// `SDF_SPREAD_PX`, which is what lets [`ink_uv`] pad outwards without leaving the cell.
fn place_in_cell(w: u32, h: u32) -> Placement {
    let avail = (CELL_PX - 2 * SDF_SPREAD_PX) as f32;
    let scale = (avail / w.max(h).max(1) as f32).min(1.0);
    let dw = ((w as f32 * scale).round() as u32).clamp(1, avail as u32);
    let dh = ((h as f32 * scale).round() as u32).clamp(1, avail as u32);
    Placement { scale, ox: (CELL_PX - dw) / 2, oy: (CELL_PX - dh) / 2, dw, dh }
}

/// The atlas rect covering a glyph's ink plus its spread margin.
///
/// No half-texel inset: the rect is strictly inside its cell by construction
/// (`ox >= SDF_SPREAD_PX`), so there is no neighbouring cell to bleed from.
fn ink_uv(cell: u32, placement: Placement) -> UvRect {
    let col = cell % ATLAS_COLS;
    let row = cell / ATLAS_COLS;
    let (cx, cy) = (col * CELL_PX, row * CELL_PX);
    let pad = SDF_SPREAD_PX;
    let n = ATLAS_PX as f32;
    UvRect {
        u0: (cx + placement.ox - pad) as f32 / n,
        v0: (cy + placement.oy - pad) as f32 / n,
        u1: (cx + placement.ox + placement.dw + pad) as f32 / n,
        v1: (cy + placement.oy + placement.dh + pad) as f32 / n,
    }
}

/// 8SSEDT-lite: exact brute-force distance transform over a small cell.
///
/// Cells are ≤128px; brute force is ~16k×256 ops per glyph worst case, once per
/// process — simpler than Felzenszwalb and exact. Inside/outside comes from the
/// 50% coverage threshold; distance is normalised by `SDF_SPREAD_PX`.
///
/// `placement` is where the caller has decided the bitmap sits in the cell, so the
/// same rect [`ink_uv`] addresses is the rect sampled here.
fn sdf_from_coverage(coverage: &[u8], w: u32, h: u32, placement: Placement) -> Vec<u8> {
    let inside = |x: i32, y: i32| -> bool {
        x >= 0 && y >= 0 && (x as u32) < w && (y as u32) < h && coverage[(y as u32 * w + x as u32) as usize] >= 128
    };
    let spread = SDF_SPREAD_PX as f32;
    let mut out = vec![0u8; (CELL_PX * CELL_PX) as usize];
    let Placement { scale, ox, oy, dw, dh } = placement;
    let sample = |cx: u32, cy: u32| -> bool {
        if cx < ox || cy < oy || cx >= ox + dw || cy >= oy + dh {
            return false;
        }
        let gx = ((cx - ox) as f32 / scale) as i32;
        let gy = ((cy - oy) as f32 / scale) as i32;
        inside(gx, gy)
    };
    // Search radius in *cell* px: the spread in glyph px scaled up. Distances are
    // divided back down by the same scale so the SDF is in glyph px everywhere.
    let r_cell = ((SDF_SPREAD_PX as f32 * scale).ceil() as u32).max(2);
    for cy in 0..CELL_PX {
        for cx in 0..CELL_PX {
            let me = sample(cx, cy);
            // Inside the stroke the distance is capped by the search radius, so a
            // thick stem never reaches full bright — but the shader only needs the
            // 0.5 crossing plus a smoothing band either side. Scale the inside
            // distance so the stem centre hits 1.0: full dynamic range at the edge.
            let mut best = spread;
            for dy in -(r_cell as i32)..=(r_cell as i32) {
                for dx in -(r_cell as i32)..=(r_cell as i32) {
                    let d_cell = ((dx * dx + dy * dy) as f32).sqrt();
                    if d_cell < best * scale
                        && sample(cx.saturating_add_signed(dx), cy.saturating_add_signed(dy)) != me
                    {
                        best = d_cell / scale;
                    }
                }
            }
            let value = if me {
                // Inside: 0.5 at the edge → 1.0 one px in. Stems thicker than 2px
                // saturate, which is correct for an SDF edge function.
                (0.5 + (best / 2.0).min(0.5)).clamp(0.0, 1.0)
            } else {
                (0.5 - best / (2.0 * spread)).clamp(0.0, 1.0)
            };
            out[(cy * CELL_PX + cx) as usize] = (value * 255.0) as u8;
        }
    }
    out
}

/// Expand one R8 SDF row into RGBA8 for upload: `[v, v, v, v]` per texel.
///
/// THE upload contract the fragment shader depends on (`shaders/symbol.frag`
/// samples `.r`). The SDF value goes in ALL FOUR channels deliberately: the
/// Stage-B device verdict proved a running binary sampling `.r == 0` with the
/// SDF in `.a` only — a channel divorce no in-tree step can produce (the view
/// uses identity swizzle, the staging copy cannot reorder UNORM channels), so
/// it came from a stale binary predating the RGBA8 expansion. Writing `v`
/// everywhere makes `.r` correct under ANY single-channel placement the bytes
/// ever had, and any future swap that breaks it fails
/// [`the_rgba8_expansion_carries_sdf_in_every_channel`] instead of the capture.
///
/// Lives here (host-compiled) rather than in `vulkan::images` (Android-only) so
/// the contract is testable without a device.
pub fn expand_sdf_r8_to_rgba8(r8: &[u8]) -> Vec<u8> {
    r8.iter().flat_map(|&v| [v, v, v, v]).collect()
}

/// Copy one cell's SDF into the atlas image.
fn blit_cell(atlas: &mut [u8], cell: u32, sdf: &[u8]) {
    let col = cell % ATLAS_COLS;
    let row = cell / ATLAS_COLS;
    for cy in 0..CELL_PX {
        let dst = ((row * CELL_PX + cy) * ATLAS_PX + col * CELL_PX) as usize;
        let src = (cy * CELL_PX) as usize;
        atlas[dst..dst + CELL_PX as usize].copy_from_slice(&sdf[src..src + CELL_PX as usize]);
    }
}

/// The process-wide atlas, built once.
pub fn atlas() -> &'static GlyphAtlas {
    static ATLAS: OnceLock<GlyphAtlas> = OnceLock::new();
    ATLAS.get_or_init(GlyphAtlas::build)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tile::glyph::fonts_staged;

    /// The staged TTFs are GitHub 404 pages, not fonts (task 54 re-fetch pending).
    /// These tests need real Noto Sans bytes; they run again once they land.
    fn real_fonts_staged() -> bool {
        fonts_staged()
    }

    #[test]
    fn both_bundled_fonts_parse_and_cover_ascii() {
        if !real_fonts_staged() {
            eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
            return;
        }
        let atlas = GlyphAtlas::build();
        for ch in ['A', 'a', '0', ' ', '-', '\''] {
            assert!(
                atlas.metrics(Weight::Regular, ch).is_some() || ch == ' ',
                "{ch:?} missing from Regular"
            );
            assert!(atlas.metrics(Weight::Medium, ch).is_some() || ch == ' ', "{ch:?} missing");
        }
    }

    #[test]
    fn the_atlas_is_a_full_r8_image() {
        if !real_fonts_staged() {
            eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
            return;
        }
        let atlas = GlyphAtlas::build();
        assert_eq!(atlas.pixels.len(), (ATLAS_PX * ATLAS_PX) as usize);
        // An SDF atlas is mostly edge gradient: with spread 8 the wells are narrow,
        // so assert the gradient band is well populated and both extremes exist.
        // (A fully-flat 127 image means the transform wrote nothing.)
        let min = *atlas.pixels.iter().min().unwrap();
        let max = *atlas.pixels.iter().max().unwrap();
        let mid = atlas.pixels.iter().filter(|&&v| (64..=192).contains(&v)).count();
        assert!(mid > 10_000, "mid {mid}");
        assert!(min < 64, "min {min}");
        assert!(max > 192, "max {max}");
    }

    /// The task-1 regression: the RGBA8 upload must carry the SDF in every
    /// channel — the exact bytes `vulkan::images` hands the driver and
    /// `symbol.frag` samples as `.r`. Needs no fonts: pure byte order.
    #[test]
    fn the_rgba8_expansion_carries_sdf_in_every_channel() {
        assert_eq!(
            expand_sdf_r8_to_rgba8(&[0, 127, 255]),
            vec![0, 0, 0, 0, 127, 127, 127, 127, 255, 255, 255, 255],
        );
        assert!(expand_sdf_r8_to_rgba8(&[]).is_empty());
    }

    /// P1 gibberish guard: insertion key and lookup key are the same char.
    /// Every ASCII codepoint the atlas claims must round-trip: metrics found
    /// under `(weight, ch)` must carry the cell assigned at insertion, and the
    /// UV rect must lie inside that cell. A mismatch here (codepoint vs
    /// glyph-id vs cluster indexing) renders the wrong glyph per quad —
    /// legible boxes, wrong letters.
    #[test]
    fn every_char_looks_up_the_cell_it_was_inserted_in() {
        if !real_fonts_staged() {
            eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
            return;
        }
        let atlas = GlyphAtlas::build();
        let chars = charset();
        for weight in [Weight::Regular, Weight::Medium] {
            let base = if weight == Weight::Regular { 0 } else { chars.len() as u32 };
            for (i, &ch) in chars.iter().enumerate() {
                let Some(m) = atlas.metrics(weight, ch) else { continue };
                // Insertion assigned Regular -> i, Medium -> len + i.
                assert_eq!(m.cell, base + i as u32, "{weight:?} {ch:?}");
                let Some(uv) = atlas.uv(weight, ch) else {
                    panic!("{weight:?} {ch:?} has metrics but no UV");
                };
                if m.w == 0.0 {
                    continue; // space: no ink, no rect
                }
                // The rect addresses this glyph's own cell and no neighbour's.
                let col = m.cell % ATLAS_COLS;
                let row = m.cell / ATLAS_COLS;
                let n = ATLAS_PX as f32;
                let (lo_u, lo_v) = ((col * CELL_PX) as f32 / n, (row * CELL_PX) as f32 / n);
                let (hi_u, hi_v) =
                    (((col + 1) * CELL_PX) as f32 / n, ((row + 1) * CELL_PX) as f32 / n);
                assert!(uv.u0 >= lo_u && uv.u1 <= hi_u, "{weight:?} {ch:?} u escapes its cell");
                assert!(uv.v0 >= lo_v && uv.v1 <= hi_v, "{weight:?} {ch:?} v escapes its cell");
            }
        }
    }

    /// THE regression this module was rewritten for. The UV rect and the quad
    /// have to describe the same region, or every glyph draws at a fraction of
    /// its size inside a correctly-spaced advance — tiny, letter-spaced text.
    ///
    /// Checked as an aspect-ratio identity, which is the part that cannot be
    /// fixed by scaling `text_size`: the rect's width:height must equal the
    /// quad's width:height. Addressing the whole 64px cell (the old bug) makes
    /// every rect square while the quads keep the glyphs' own proportions.
    #[test]
    fn a_glyphs_uv_rect_has_the_same_aspect_ratio_as_its_quad() {
        if !real_fonts_staged() {
            eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
            return;
        }
        let atlas = GlyphAtlas::build();
        let mut checked = 0;
        for ch in charset() {
            let Some(m) = atlas.metrics(Weight::Regular, ch) else { continue };
            if m.w == 0.0 || m.h == 0.0 {
                continue;
            }
            let uv = atlas.uv(Weight::Regular, ch).expect("metrics imply UV");
            let uv_aspect = (uv.u1 - uv.u0) / (uv.v1 - uv.v0);
            let quad_aspect = m.w / m.h;
            // A texel of rounding in the cell placement is the only slack here.
            assert!(
                (uv_aspect - quad_aspect).abs() < 0.06 * quad_aspect.max(1.0),
                "{ch:?}: rect aspect {uv_aspect:.4} vs quad aspect {quad_aspect:.4}",
            );
            checked += 1;
        }
        assert!(checked > 80, "only {checked} glyphs checked");
    }

    /// The quad is the ink grown by the spread, so it is always a little wider
    /// and taller than the ink itself — and a capital still has to occupy most
    /// of its advance. The old whole-cell UV made the drawn ink roughly half
    /// size, which this bounds from both sides.
    #[test]
    fn a_capitals_quad_is_the_right_size_against_its_advance() {
        if !real_fonts_staged() {
            eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
            return;
        }
        let atlas = GlyphAtlas::build();
        let m = atlas.metrics(Weight::Regular, 'H').expect("H is in the charset");
        // Cap height is ~0.714 em; the quad adds spread on both sides, so it
        // lands above that and well under a whole em and a half.
        let em = UP_EM as f32;
        assert!(m.h / em > 0.71, "H quad {:.3} em is shorter than its cap height", m.h / em);
        assert!(m.h / em < 1.3, "H quad {:.3} em is implausibly tall", m.h / em);
        // And it fills its advance rather than rattling around inside it.
        assert!(m.w > m.advance * 0.8, "H quad {} narrow against advance {}", m.w, m.advance);
    }

    /// `UP_EM` is the denominator for every font-unit metric, so a font whose real
    /// unitsPerEm differs draws all text at the wrong size — silently, because
    /// nothing else in the pipeline knows the em is wrong. The bundled faces
    /// declare 1000; a 2048 assumption shrank every label to 49% of its size.
    #[test]
    fn the_bundled_fonts_use_the_declared_upem() {
        if !real_fonts_staged() {
            eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
            return;
        }
        for (name, bytes) in [("Regular", REGULAR_TTF), ("Medium", MEDIUM_TTF)] {
            let font = FontRef::try_from_slice(bytes).expect("parses");
            assert_eq!(
                font.units_per_em(),
                Some(UP_EM as f32),
                "{name} declares a different em to UP_EM",
            );
        }
    }

    #[test]
    fn a_glyph_is_centred_in_its_cell_with_the_spread_reserved_all_round() {
        // ox/oy are what let `ink_uv` pad outwards without leaving the cell.
        for (w, h) in [(10, 34), (34, 34), (60, 20), (200, 200), (1, 1)] {
            let p = place_in_cell(w, h);
            assert!(p.ox >= SDF_SPREAD_PX, "{w}x{h}: ox {} under the margin", p.ox);
            assert!(p.oy >= SDF_SPREAD_PX, "{w}x{h}: oy {} under the margin", p.oy);
            assert!(p.ox + p.dw + SDF_SPREAD_PX <= CELL_PX, "{w}x{h}: overruns right");
            assert!(p.oy + p.dh + SDF_SPREAD_PX <= CELL_PX, "{w}x{h}: overruns bottom");
            assert!(p.scale > 0.0 && p.scale <= 1.0, "{w}x{h}: scale {}", p.scale);
        }
    }

    #[test]
    fn uv_rects_stay_inside_the_image() {
        if !real_fonts_staged() {
            eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
            return;
        }
        let atlas = GlyphAtlas::build();
        for ch in charset() {
            if let Some(uv) = atlas.uv(Weight::Regular, ch) {
                assert!(uv.u0 <= uv.u1 && uv.v0 <= uv.v1, "{ch:?}");
                assert!(uv.u1 <= 1.0 && uv.v1 <= 1.0, "{ch:?}");
            }
        }
    }
}
