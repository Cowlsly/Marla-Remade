//! Screen-space text labels from SDF glyphs: ASCII shaping plus quad emission.
//!
//! M1 covers _place_ labels only (country/region/locality/neighbourhood): point
//! features from the v2 `places` layer whose display name is the body's name-table
//! string. No shaping engine, no BiDi, no complex scripts — `name:en` coalesced by
//! the tiler is Latin in the overwhelming NA case, and CJK/others are a stated M5.
//!
//! # Data flow
//!
//! [`shape`] turns a string into one [`ShapedGlyph`] per codepoint: pen advance in
//! _font units_ (1/2048 em — ab_glyph unscaled metrics), so shaping is zoom- and
//! size-independent. [`emit`] turns shaped glyphs into two triangles each in
//! tile-local 0..1, at the label's anchor point, scaled by the per-frame pixel size
//! the caller passes. Tessellation is therefore a pure function of (tile, string);
//! the camera zoom only changes the scale factor, exactly like a road width.
//!
//! [`ATLAS`] (in `tile::glyph`) maps (weight, char) to UV rect + font-unit metrics,
//! shared by both functions.

use crate::tile::glyph::{GlyphAtlas, Weight, UP_EM};

/// Floats per vertex: `x, y, u, v`.
pub const FLOATS_PER_VERTEX: usize = 4;

/// One codepoint after shaping: pen position plus atlas lookup.
#[derive(Clone, Copy, Debug)]
pub struct ShapedGlyph {
    /// Pen x at this glyph's origin, in font units (1/upem em).
    pub pen_x: f32,
    /// The character.
    pub ch: char,
    /// Advance including kerning with the previous glyph, in font units.
    pub advance: f32,
    /// Horizontal bearing (left side bearing), in font units.
    pub bearing_x: f32,
    /// Ascender-relative top of the glyph bitmap, in font units. ab_glyph's
    /// `px_bounds` is y-down from the baseline; this is stored y-up so `emit`
    /// stays in tile space without a flip.
    pub top: f32,
    /// Bitmap size in font units.
    pub w: f32,
    pub h: f32,
}

/// Shape `text` with `atlas`'s metrics: pen advances plus kerning, no line breaking.
///
/// Unknown codepoints are skipped (a tofu box is worse than a gap at M1; CJK is M5).
/// Returns the shaped glyphs and the total advance in font units.
pub fn shape(atlas: &GlyphAtlas, weight: Weight, text: &str) -> (Vec<ShapedGlyph>, f32) {
    let mut out = Vec::new();
    let mut pen_x = 0.0f32;
    let mut previous: Option<char> = None;
    for ch in text.chars() {
        let Some(metrics) = atlas.metrics(weight, ch) else { continue };
        if let Some(prev) = previous {
            pen_x += atlas.kern(weight, prev, ch);
        }
        out.push(ShapedGlyph {
            pen_x,
            ch,
            advance: metrics.advance,
            bearing_x: metrics.bearing_x,
            top: metrics.top,
            w: metrics.w,
            h: metrics.h,
        });
        pen_x += metrics.advance;
        previous = Some(ch);
    }
    (out, pen_x)
}

/// Emit two triangles per glyph into `vertices`/`indices`.
///
/// `anchor` is the label centre in tile-local 0..1; `px_per_font_unit` converts font
/// units to tile-local units at this frame's zoom (`text_px / upem / tile_span_px`);
/// `uppercase` applies the authored `text-transform` (country/region/subplace).
/// UVs come from the atlas; a `1.0` v-coordinate is the atlas top.
///
/// Indices are relative to the first vertex already in `vertices`.
/// Emit two triangles per glyph into `vertices`/`indices`.
///
/// `anchor` is the label centre in tile-local 0..1; `tile_span_px` is this tile's
/// screen size in px (so `text_px / tile_span_px` converts px to tile-local);
/// `text_px` is the frame's label size in screen px from the layer's `text_size`
/// ramp; `uppercase` applies the authored `text-transform`.
///
/// Positions come out tile-local and already scaled: zooming changes `text_px`
/// per frame, so the caller re-emits per frame — labels are cheap (a few dozen
/// quads a tile) unlike roads. See `tile::symbol` for why per-frame emission is
/// correct here. UVs come from the atlas.
///
/// Indices are relative to the first vertex already in `vertices`.
pub fn emit(
    atlas: &GlyphAtlas,
    weight: Weight,
    shaped: &[ShapedGlyph],
    total_advance: f32,
    anchor: (f32, f32),
    text_px: f32,
    tile_span_px: f32,
    uppercase: bool,
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    let px_per_font_unit = text_px / UP_EM as f32 / tile_span_px;
    // Centre the run on the anchor; the baseline sits half an x-height above centre
    // is approximated by centring the em box — good to a pixel at label sizes.
    let origin_x = anchor.0 - total_advance * 0.5 * px_per_font_unit;
    // `top` is measured from the ascender; rebase so the em box centres on the anchor.
    // Ascender ≈ 0.75 em for Noto; the em box spans -0.25..0.75 in `top` space.
    let origin_y = anchor.1;
    for g in shaped {
        let ch = if uppercase { g.ch.to_ascii_uppercase() } else { g.ch };
        let Some(uv) = atlas.uv(weight, ch) else { continue };
        let x0 = origin_x + (g.pen_x + g.bearing_x) * px_per_font_unit;
        let x1 = x0 + g.w * px_per_font_unit;
        // y-down bitmap rows become y-down tile offsets from the ascender line.
        let ascender = 0.75 * UP_EM as f32 * px_per_font_unit;
        let y0 = origin_y - ascender + (0.75 * UP_EM as f32 - g.top) * px_per_font_unit;
        let y1 = y0 + g.h * px_per_font_unit;
        let base = (vertices.len() / FLOATS_PER_VERTEX) as u32;
        vertices.extend_from_slice(&[x0, y0, uv.u0, uv.v0]);
        vertices.extend_from_slice(&[x1, y0, uv.u1, uv.v0]);
        vertices.extend_from_slice(&[x1, y1, uv.u1, uv.v1]);
        vertices.extend_from_slice(&[x0, y1, uv.u0, uv.v1]);
        indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tile::glyph::fonts_staged;

    fn atlas() -> Option<GlyphAtlas> {
        if !fonts_staged() {
            eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
            return None;
        }
        Some(GlyphAtlas::build())
    }

    #[test]
    fn shaping_advances_the_pen_by_each_glyphs_advance() {
        let Some(atlas) = atlas() else { return };
        let (shaped, total) = shape(&atlas, Weight::Regular, "AB");
        assert_eq!(shaped.len(), 2);
        assert!(total > 0.0);
        assert_eq!(shaped[0].pen_x, 0.0);
        assert!((shaped[1].pen_x - shaped[0].advance).abs() < 1.0, "kern is small");
        assert!((total - (shaped[0].advance + shaped[1].advance)).abs() < 2.0);
    }

    #[test]
    fn unknown_codepoints_are_skipped_not_tofu() {
        let Some(atlas) = atlas() else { return };
        let (shaped, _) = shape(&atlas, Weight::Regular, "A\u{4E2D}B");
        assert_eq!(shaped.len(), 2, "CJK is M5; skip rather than tofu");
    }

    #[test]
    fn emit_produces_two_triangles_per_glyph() {
        let Some(atlas) = atlas() else { return };
        let (shaped, total) = shape(&atlas, Weight::Regular, "Hi");
        let (mut v, mut idx) = (Vec::new(), Vec::new());
        emit(&atlas, Weight::Regular, &shaped, total, (0.5, 0.5), 12.0, 256.0, false, &mut v, &mut idx);
        assert_eq!(v.len() / FLOATS_PER_VERTEX, 8);
        assert_eq!(idx.len(), 12);
        for f in &v {
            assert!(f.is_finite());
        }
        // UVs land inside the atlas.
        for chunk in v.chunks(FLOATS_PER_VERTEX) {
            assert!((0.0..=1.0).contains(&chunk[2]), "u {}", chunk[2]);
            assert!((0.0..=1.0).contains(&chunk[3]), "v {}", chunk[3]);
        }
    }

    #[test]
    fn uppercase_transform_matches_the_authored_country_style() {
        let Some(atlas) = atlas() else { return };
        let (lower, total) = shape(&atlas, Weight::Medium, "abc");
        let (mut v, mut idx) = (Vec::new(), Vec::new());
        emit(&atlas, Weight::Medium, &lower, total, (0.5, 0.5), 12.0, 256.0, true, &mut v, &mut idx);
        assert_eq!(idx.len(), 18, "three glyphs still emit");
    }

    /// P1 gibberish guard, shaping half: "Sacramento" shapes one glyph per
    /// char, in order, each carrying its own codepoint — so the quad stream
    /// spells the input, not a rotation or subset of it.
    #[test]
    fn sacramento_shapes_in_order_with_its_own_codepoints() {
        let Some(atlas) = atlas() else { return };
        let (shaped, total) = shape(&atlas, Weight::Regular, "Sacramento");
        let spelled: String = shaped.iter().map(|g| g.ch).collect();
        assert_eq!(spelled, "Sacramento");
        assert_eq!(shaped.len(), 10);
        assert!(total > 0.0);
        for pair in shaped.windows(2) {
            assert!(pair[1].pen_x > pair[0].pen_x, "pen advances left to right");
        }
    }

    /// P1 gibberish guard, emission half: each emitted quad's UV corners are
    /// the atlas cell of that quad's glyph — the binding the task requires
    /// between position stream and UV stream. (Exercised with uppercase on,
    /// matching the country/region/subplace layers, so the transformed
    /// codepoint is what the UV lookup must use.)
    #[test]
    fn each_emitted_quad_samples_its_own_glyphs_cell() {
        let Some(atlas) = atlas() else { return };
        let (shaped, total) = shape(&atlas, Weight::Medium, "Sacramento");
        let (mut v, mut idx) = (Vec::new(), Vec::new());
        emit(&atlas, Weight::Medium, &shaped, total, (0.5, 0.5), 16.0, 256.0, true, &mut v, &mut idx);
        assert_eq!(v.len() / FLOATS_PER_VERTEX, shaped.len() * 4);
        for (g, quad) in shaped.iter().zip(v.chunks(FLOATS_PER_VERTEX * 4)) {
            let drawn = g.ch.to_ascii_uppercase();
            let uv = atlas.uv(Weight::Medium, drawn).expect("shaped implies UV");
            let corners = [(uv.u0, uv.v0), (uv.u1, uv.v0), (uv.u1, uv.v1), (uv.u0, uv.v1)];
            for (vert, (eu, ev)) in quad.chunks(FLOATS_PER_VERTEX).zip(corners) {
                assert!((vert[2] - eu).abs() < 1e-6, "{drawn:?} u");
                assert!((vert[3] - ev).abs() < 1e-6, "{drawn:?} v");
            }
        }
    }

    #[test]
    fn an_empty_string_emits_nothing() {
        let Some(atlas) = atlas() else { return };
        let (shaped, total) = shape(&atlas, Weight::Regular, "");
        assert!(shaped.is_empty() && total == 0.0);
        let (mut v, mut idx) = (Vec::new(), Vec::new());
        emit(&atlas, Weight::Regular, &shaped, total, (0.5, 0.5), 12.0, 256.0, false, &mut v, &mut idx);
        assert!(v.is_empty() && idx.is_empty());
    }
}
