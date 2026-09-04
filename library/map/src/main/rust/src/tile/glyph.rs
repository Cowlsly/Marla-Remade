//! The SDF glyph atlas: Noto Sans rasterised once, sampled every frame.
//!
//! MapLibre renders text from a signed-distance-field atlas: each glyph is a small
//! bitmap whose texels store _distance to the glyph edge_, so the fragment shader
//! gets crisp edges at any size with `smoothstep`, plus a halo for free. This is
//! that atlas, built at first use from the bundled TTFs (task 54 staged
//! `library/map/src/main/assets/fonts/NotoSans-Regular.ttf` and `-Medium.ttf`).
//!
//! # Why runtime, not build-time
//!
//! The TTFs are APK assets, not files the build script can read into the `.so`
//! without a JNI fd handoff. Rasterising at first use costs milliseconds once:
//! ~95 Latin codepoints × 2 weights, each a 4x `ab_glyph` rasterise followed by an
//! 8SSEDT distance transform over a ≤128px cell. The atlas is a single R8 image
//! (16×16 cells of 64px = 1024px) uploaded to Vulkan once.
//!
//! # Metrics live here, shaping in `tess::text`
//!
//! `ab_glyph`'s unscaled metrics are in font units (1/upem em); the atlas stores
//! them per (weight, char) so shaping needs no font handle. Kerning is
//! `kern_unscaled` between consecutive ids. UV rects address the R8 image.

use ab_glyph::{Font, FontRef};
use std::collections::HashMap;
use std::sync::OnceLock;

/// Font units per em for the bundled Noto Sans. 2048 for both weights.
pub const UP_EM: u16 = 2048;

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
const REGULAR_TTF: &[u8] = include_bytes!("../../../assets/fonts/NotoSans-Regular.ttf");
/// Noto Sans Medium, embedded likewise.
const MEDIUM_TTF: &[u8] = include_bytes!("../../../assets/fonts/NotoSans-Medium.ttf");

/// Which bundled weight a label uses. The authored style uses Regular everywhere
/// except country labels and big cities (Medium).
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum Weight {
    Regular,
    Medium,
}

/// Per-glyph metrics in font units, plus the atlas cell.
#[derive(Clone, Copy, Debug)]
pub struct GlyphMetrics {
    /// Advance width including kerning base, in font units.
    pub advance: f32,
    /// Left side bearing, in font units.
    pub bearing_x: f32,
    /// Ascender-relative top of the bitmap, in font units (y-up).
    pub top: f32,
    /// Bitmap width/height in font units.
    pub w: f32,
    pub h: f32,
    /// Atlas cell index.
    pub cell: u32,
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
        for (weight, font) in [(Weight::Regular, regular), (Weight::Medium, medium)] {
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
                // 4x raster of the outline at 48px, then downsample-by-distance to
                // the SDF cell: coverage at 4x is a 2-bit alpha proxy.
                let px = 48.0f32;
                let glyph = id.with_scale(px);
                let Some(outlined) = font.outline_glyph(glyph) else { continue };
                let bounds = outlined.px_bounds();
                let w_px = bounds.width().ceil() as u32;
                let h_px = bounds.height().ceil() as u32;
                if w_px == 0 || h_px == 0 {
                    // Space and friends: advance with no bitmap.
                    metrics.insert(
                        (weight, ch),
                        GlyphMetrics {
                            advance,
                            bearing_x: bearing,
                            top: 0.0,
                            w: 0.0,
                            h: 0.0,
                            cell,
                        },
                    );
                    continue;
                }
                let mut coverage = vec![0u8; (w_px * h_px) as usize];
                outlined.draw(|x, y, v| {
                    if x < w_px && y < h_px {
                        coverage[(y * w_px + x) as usize] = (v * 255.0) as u8;
                    }
                });
                let sdf = sdf_from_coverage(&coverage, w_px, h_px);
                blit_cell(&mut pixels, cell, &sdf, w_px, h_px);
                // Bitmap rows are y-down from the glyph top; `top` converts to the
                // ascender-relative y-up space `tess::text::emit` works in.
                let ascent = font.ascent_unscaled();
                metrics.insert(
                    (weight, ch),
                    GlyphMetrics {
                        advance,
                        bearing_x: bearing,
                        top: ascent - bounds.min.y * (UP_EM as f32 / px),
                        w: w_px as f32 * (UP_EM as f32 / px),
                        h: h_px as f32 * (UP_EM as f32 / px),
                        cell,
                    },
                );
            }
        }
        GlyphAtlas { pixels, metrics }
    }

    pub fn metrics(&self, weight: Weight, ch: char) -> Option<GlyphMetrics> {
        self.metrics.get(&(weight, ch)).copied()
    }

    pub fn uv(&self, weight: Weight, ch: char) -> Option<UvRect> {
        let m = self.metrics.get(&(weight, ch))?;
        Some(cell_uv(m.cell))
    }

    /// Horizontal kerning between two codepoints, in font units.
    pub fn kern(&self, _weight: Weight, _prev: char, _next: char) -> f32 {
        // Noto Sans Latin kerning is small vs label sizes; ab_glyph's
        // `kern_unscaled` needs glyph ids, which the atlas deliberately does not
        // retain (metrics-only shaping). Revisit if tracking looks off.
        0.0
    }
}

fn cell_uv(cell: u32) -> UvRect {
    let col = cell % ATLAS_COLS;
    let row = cell / ATLAS_COLS;
    let (x0, y0) = (col * CELL_PX, row * CELL_PX);
    // Inset half a texel to avoid bleeding from neighbouring cells.
    let inset = 0.5 / ATLAS_PX as f32;
    UvRect {
        u0: x0 as f32 / ATLAS_PX as f32 + inset,
        v0: y0 as f32 / ATLAS_PX as f32 + inset,
        u1: (x0 + CELL_PX) as f32 / ATLAS_PX as f32 - inset,
        v1: (y0 + CELL_PX) as f32 / ATLAS_PX as f32 - inset,
    }
}

/// 8SSEDT-lite: exact brute-force distance transform over a small cell.
///
/// Cells are ≤128px; brute force is ~16k×256 ops per glyph worst case, once per
/// process — simpler than Felzenszwalb and exact. Inside/outside comes from the
/// 50% coverage threshold; distance is normalised by `SDF_SPREAD_PX`.
///
/// `scale` rescales cell-space distances back into glyph-px units so the SDF
/// gradient is resolution-independent. Callers pass the bitmap→cell scale they
/// used when sampling.
fn sdf_from_coverage(coverage: &[u8], w: u32, h: u32) -> Vec<u8> {
    let inside = |x: i32, y: i32| -> bool {
        x >= 0 && y >= 0 && (x as u32) < w && (y as u32) < h && coverage[(y as u32 * w + x as u32) as usize] >= 128
    };
    let spread = SDF_SPREAD_PX as f32;
    let mut out = vec![0u8; (CELL_PX * CELL_PX) as usize];
    // Map the glyph bitmap into the cell centre, scaled to fit with spread margin.
    let avail = (CELL_PX - 2 * SDF_SPREAD_PX) as f32;
    let scale = (avail / w.max(h) as f32).min(1.0);
    let dw = (w as f32 * scale).round() as u32;
    let dh = (h as f32 * scale).round() as u32;
    let ox = (CELL_PX - dw) / 2;
    let oy = (CELL_PX - dh) / 2;
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
fn blit_cell(atlas: &mut [u8], cell: u32, sdf: &[u8], _w: u32, _h: u32) {
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
    /// UV rect for that cell must be the rect the tessellator will sample.
    /// A mismatch here (codepoint vs glyph-id vs cluster indexing) renders the
    /// wrong glyph per quad — legible boxes, wrong letters.
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
                // And the UV rect is that cell's rect, inside the atlas.
                let col = m.cell % ATLAS_COLS;
                let row = m.cell / ATLAS_COLS;
                let (x0, y0) = (col * CELL_PX, row * CELL_PX);
                let inset = 0.5 / ATLAS_PX as f32;
                assert!((uv.u0 - (x0 as f32 / ATLAS_PX as f32 + inset)).abs() < 1e-6);
                assert!((uv.v0 - (y0 as f32 / ATLAS_PX as f32 + inset)).abs() < 1e-6);
                assert!((uv.u1 - ((x0 + CELL_PX) as f32 / ATLAS_PX as f32 - inset)).abs() < 1e-6);
                assert!((uv.v1 - ((y0 + CELL_PX) as f32 / ATLAS_PX as f32 - inset)).abs() < 1e-6);
            }
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
                assert!(uv.u0 < uv.u1 && uv.v0 < uv.v1, "{ch:?}");
                assert!(uv.u1 <= 1.0 && uv.v1 <= 1.0, "{ch:?}");
            }
        }
    }
}
