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
//! _font units_ (1/upem em — ab_glyph unscaled metrics), so shaping is zoom- and
//! size-independent. [`shape_wrapped`] is the same thing broken into lines.
//! [`emit`] turns shaped lines into two triangles per glyph in tile-local 0..1, at the
//! label's anchor point, scaled by the per-frame pixel size the caller passes.
//! Tessellation is therefore a pure function of (tile, string); the camera zoom only
//! changes the scale factor, exactly like a road width.
//!
//! [`ATLAS`] (in `tile::glyph`) maps (weight, char) to UV rect + font-unit metrics,
//! shared by both functions.

use crate::style::Anchor;
use crate::tile::glyph::{GlyphAtlas, Weight, UP_EM};

/// Floats per vertex: `x, y, u, v`.
pub const FLOATS_PER_VERTEX: usize = 4;

/// One codepoint after shaping: pen position plus atlas lookup.
///
/// `bearing_x`, `top`, `w` and `h` are copied straight from [`crate::tile::glyph::GlyphMetrics`]
/// and describe the **quad** — the glyph bitmap grown by the SDF spread — not the ink,
/// so they pair exactly with the atlas UV rect. `advance` is the untouched font metric.
#[derive(Clone, Copy, Debug)]
pub struct ShapedGlyph {
    /// Pen x at this glyph's origin, in font units (1/upem em).
    pub pen_x: f32,
    /// The character.
    pub ch: char,
    /// Advance including kerning with the previous glyph, in font units.
    pub advance: f32,
    /// Quad's left edge relative to the pen, in font units.
    pub bearing_x: f32,
    /// Quad's top above the baseline, in font units, y-up.
    pub top: f32,
    /// Quad size in font units.
    pub w: f32,
    pub h: f32,
}

/// Cap height as a fraction of the em for the bundled Noto Sans (1462/2048).
///
/// What centres on a label's anchor. Centring the full em box instead sits the text
/// visibly low, because a place name's descender space is usually empty.
pub const CAP_HEIGHT_EM: f32 = 0.714;

/// Baseline-to-baseline distance as a fraction of the em, for a wrapped label.
///
/// MapLibre's `text-line-height` default, and the one this renderer uses: the reference
/// `pois` layer does not override it, and a wrapped POI label that does not match it
/// diverges more visibly with each extra line.
pub const LINE_HEIGHT_EM: f32 = 1.2;

/// One line of a shaped label: glyphs with pen positions restarting at zero.
#[derive(Clone, Debug)]
pub struct ShapedLine {
    pub glyphs: Vec<ShapedGlyph>,
    /// Total advance of this line, in font units.
    pub advance: f32,
}

/// Shape `text` with `atlas`'s metrics: pen advances plus kerning, no line breaking.
///
/// `uppercase` applies the authored `text-transform` **here**, before any metric is read,
/// which is the only place it can correctly go. Applying it at emission instead drew the
/// uppercase glyph into a quad sized and positioned from the lowercase one: every letter
/// that was lowercase in the source came out at x-height dimensions on the wrong baseline,
/// with an advance too narrow for the glyph in it.
///
/// Unknown codepoints are skipped (a tofu box is worse than a gap at M1; CJK is M5).
/// Returns the shaped glyphs and the total advance in font units.
pub fn shape(
    atlas: &GlyphAtlas,
    weight: Weight,
    text: &str,
    uppercase: bool,
) -> (Vec<ShapedGlyph>, f32) {
    let mut out = Vec::new();
    let mut pen_x = 0.0f32;
    let mut previous: Option<char> = None;
    for ch in text.chars() {
        let ch = if uppercase { ch.to_ascii_uppercase() } else { ch };
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

/// Shape `text` into lines, breaking so no line runs much past `max_width_em` ems.
///
/// [`shape`] is the single-line special case, and `max_width_em <= 0.0` returns exactly
/// that: one line holding the whole run. Place labels take that path, which is why they
/// are unaffected by any of this.
///
/// # Why not greedy wrapping
///
/// The obvious algorithm — fill a line until the next word overflows, then break — is a
/// *different* algorithm from MapLibre's, not an approximation of it. MapLibre minimises
/// total raggedness over all break sets by backtracking, so it will happily break a line
/// early to keep the following ones even. On a two-line label the two usually agree; on
/// three they visibly do not, and matching the reference is the whole point of this layer.
///
/// So this is a transcription of `determineLineBreaks`, including the parts that look like
/// bugs and are not:
///
/// * The target width counts whitespace, while the running cursor that positions the
///   breaks does not. That asymmetry is in the reference and changing it moves breaks.
/// * A tie in badness prefers the **later** break (`<=`, not `<`).
/// * The final line's badness is halved when short and doubled when long, which is what
///   makes a ragged-right block prefer a short last line.
pub fn shape_wrapped(
    atlas: &GlyphAtlas,
    weight: Weight,
    text: &str,
    uppercase: bool,
    max_width_em: f32,
) -> Vec<ShapedLine> {
    let (glyphs, advance) = shape(atlas, weight, text, uppercase);
    if glyphs.is_empty() {
        return Vec::new();
    }
    if max_width_em <= 0.0 {
        return vec![ShapedLine { glyphs, advance }];
    }
    let breaks = line_breaks(&glyphs, max_width_em * UP_EM as f32);
    let mut lines = Vec::with_capacity(breaks.len() + 1);
    let mut start = 0usize;
    for end in breaks.iter().copied().chain(std::iter::once(glyphs.len())) {
        let Some(run) = glyphs.get(start..end) else { continue };
        start = end;
        // Trimmed like MapLibre's `TaggedString.trim()`: the space a line broke at would
        // otherwise widen the line it left behind and indent the one it starts.
        let trimmed: Vec<&ShapedGlyph> = {
            let lead = run.iter().position(|g| !is_whitespace(g.ch));
            let Some(lead) = lead else { continue };
            let tail = run.iter().rposition(|g| !is_whitespace(g.ch)).unwrap_or(lead);
            run.get(lead..=tail).unwrap_or_default().iter().collect()
        };
        // Pen positions restart per line, so `emit` can place each line independently.
        let mut pen_x = 0.0f32;
        let mut out = Vec::with_capacity(trimmed.len());
        for g in trimmed {
            out.push(ShapedGlyph { pen_x, ..*g });
            pen_x += g.advance;
        }
        if !out.is_empty() {
            lines.push(ShapedLine { glyphs: out, advance: pen_x });
        }
    }
    if lines.is_empty() {
        return vec![ShapedLine { glyphs, advance }];
    }
    lines
}

/// MapLibre's `whitespace` set.
fn is_whitespace(ch: char) -> bool {
    matches!(ch, '\t' | '\n' | '\u{0b}' | '\u{0c}' | '\r' | ' ')
}

/// MapLibre's `breakable` set: where a line may be cut.
fn is_breakable(ch: char) -> bool {
    matches!(
        ch,
        '\n' | ' '
            | '&'
            | '('
            | ')'
            | '+'
            | '-'
            | '/'
            | '\u{ad}'
            | '\u{b7}'
            | '\u{200b}'
            | '\u{2010}'
            | '\u{2013}'
            | '\u{2027}'
    )
}

/// MapLibre's `calculatePenalty`, minus the ideographic arm (the atlas is ASCII).
///
/// A newline is a forced break, expressed as a penalty so large that no raggedness can
/// outweigh it, rather than as a special case in the search.
fn break_penalty(ch: char, next: char) -> f32 {
    let mut penalty = 0.0f32;
    if ch == '\n' {
        penalty -= 10_000.0;
    }
    if ch == '(' || ch == '\u{ff08}' {
        penalty += 50.0;
    }
    if next == ')' || next == '\u{ff09}' {
        penalty += 50.0;
    }
    penalty
}

/// MapLibre's `calculateBadness`: squared deviation from the target, plus the penalty.
fn badness(line_width: f32, target: f32, penalty: f32, last: bool) -> f32 {
    let raggedness = (line_width - target) * (line_width - target);
    if last {
        // A final line shorter than average reads as a normal ragged edge; a long one
        // reads as a wrapping failure, so it is punished four times as hard.
        return if line_width < target { raggedness / 2.0 } else { raggedness * 2.0 };
    }
    // `abs(p) * p` keeps a negative penalty (the forced newline) negative after squaring.
    raggedness + penalty.abs() * penalty
}

/// One candidate break in the search: where it is, and the best total cost of reaching it.
struct Candidate {
    /// Glyph index the next line starts at.
    index: usize,
    /// Cursor position when this break was considered, in font units.
    x: f32,
    /// Index into the candidate list of the break this one follows, if any.
    prior: Option<usize>,
    badness: f32,
}

/// MapLibre's `evaluateBreak`: the cheapest way to reach this break from any earlier one.
fn evaluate(
    index: usize,
    x: f32,
    target: f32,
    prior: &[Candidate],
    penalty: f32,
    last: bool,
) -> Candidate {
    let mut best_prior = None;
    let mut best = badness(x, target, penalty, last);
    for (at, earlier) in prior.iter().enumerate() {
        let cost = badness(x - earlier.x, target, penalty, last) + earlier.badness;
        // `<=`, matching the reference: on a tie the later break wins.
        if cost <= best {
            best_prior = Some(at);
            best = cost;
        }
    }
    Candidate { index, x, prior: best_prior, badness: best }
}

/// The glyph indices each line after the first starts at.
fn line_breaks(glyphs: &[ShapedGlyph], max_width: f32) -> Vec<usize> {
    // The target is the run split into as few equal lines as will fit, not `max_width`
    // itself: wrapping "Golden Gate Park" at 8 ems should give two balanced lines rather
    // than one full line and one word.
    let total: f32 = glyphs.iter().map(|g| g.advance).sum();
    let line_count = (total / max_width).ceil().max(1.0);
    let target = total / line_count;

    let mut candidates: Vec<Candidate> = Vec::new();
    let mut x = 0.0f32;
    for (i, g) in glyphs.iter().enumerate() {
        // Whitespace advances the pen but **not** the break cursor. Straight from the
        // reference; it makes a break measure the ink either side of it rather than the
        // gap, and removing it shifts break points.
        if !is_whitespace(g.ch) {
            x += g.advance;
        }
        let Some(next) = glyphs.get(i + 1) else { continue };
        if is_breakable(g.ch) {
            let penalty = break_penalty(g.ch, next.ch);
            let candidate = evaluate(i + 1, x, target, &candidates, penalty, false);
            candidates.push(candidate);
        }
    }
    let end = evaluate(glyphs.len(), x, target, &candidates, 0.0, true);

    let mut out = Vec::new();
    let mut cursor = end.prior;
    while let Some(at) = cursor {
        let Some(candidate) = candidates.get(at) else { break };
        out.push(candidate.index);
        cursor = candidate.prior;
    }
    out.reverse();
    out
}

/// Emit two triangles per glyph of a shaped block into `vertices`/`indices`.
///
/// `point` is the label's anchor in tile-local 0..1 and `anchor` says where the block sits
/// relative to it: `Center` straddles it, `Left` puts the block's left edge on it (so the
/// text runs right), `Right` its right edge. `offset_em` is `text-offset` in ems, pushing
/// the block further along that direction. `tile_span_px` is this tile's screen size in px
/// (so `text_px / tile_span_px` converts px to tile-local); `text_px` is the frame's label
/// size in screen px from the layer's `text_size` ramp. The authored `text-transform` was
/// already applied by [`shape`].
///
/// Justification follows the anchor, which is MapLibre's `text-justify: auto`: a
/// left-anchored block is left-justified, so its wrapped lines line up down the side
/// nearest the point.
///
/// A single line at `Center` with a zero offset reduces to exactly the arithmetic this
/// used to do, in the same order — which is what keeps every `places-*` label
/// byte-identical.
///
/// Each quad covers the glyph's ink **plus the SDF spread**, matching the atlas rect
/// the UV addresses — the two are derived from one placement in [`crate::tile::glyph`]
/// and must stay that way, or the ink scales independently of its advance.
///
/// Indices are relative to the first vertex already in `vertices`.
#[allow(clippy::too_many_arguments)]
pub fn emit(
    atlas: &GlyphAtlas,
    weight: Weight,
    lines: &[ShapedLine],
    point: (f32, f32),
    anchor: Anchor,
    offset_em: (f32, f32),
    text_px: f32,
    tile_span_px: f32,
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    let px_per_font_unit = text_px / UP_EM as f32 / tile_span_px;
    // One em in tile-local units. Derived through `px_per_font_unit` rather than from
    // `text_px` directly so the single-line centred path below stays bit-for-bit what it
    // was.
    let em = UP_EM as f32 * px_per_font_unit;
    // MapLibre takes the offset's magnitude and picks its sign from the anchor, and gives
    // a horizontal anchor no vertical component at all. `offset_em.1` is therefore unused
    // here — every anchor this renderer supports is horizontal.
    let offset_x = offset_em.0.abs() * em;
    let _ = offset_em.1;
    // The stack of baselines is centred where a single line's would sit, so one line is
    // unmoved and each extra line splits the difference either side of the anchor.
    let block_shift = (lines.len().saturating_sub(1)) as f32 * 0.5 * LINE_HEIGHT_EM * em;
    let first_baseline =
        point.1 + 0.5 * CAP_HEIGHT_EM * UP_EM as f32 * px_per_font_unit - block_shift;
    let widest = lines.iter().fold(0.0f32, |wide, line| wide.max(line.advance));

    for (index, line) in lines.iter().enumerate() {
        let origin_x = match anchor {
            // Every line centred on the point, which for one line is the old expression
            // in the old order.
            Anchor::Center => point.0 - line.advance * 0.5 * px_per_font_unit,
            // Left-justified: every line starts at the block's left edge, whatever the
            // widest line is.
            Anchor::Left => point.0 + offset_x,
            // Right-justified: every line ends at the block's right edge.
            Anchor::Right => {
                point.0 - offset_x - widest * px_per_font_unit
                    + (widest - line.advance) * px_per_font_unit
            }
        };
        let baseline_y = first_baseline + index as f32 * LINE_HEIGHT_EM * em;
        for g in &line.glyphs {
            let Some(uv) = atlas.uv(weight, g.ch) else { continue };
            let x0 = origin_x + (g.pen_x + g.bearing_x) * px_per_font_unit;
            let x1 = x0 + g.w * px_per_font_unit;
            // `top` is y-up above the baseline; tile space is y-down.
            let y0 = baseline_y - g.top * px_per_font_unit;
            let y1 = y0 + g.h * px_per_font_unit;
            let base = (vertices.len() / FLOATS_PER_VERTEX) as u32;
            vertices.extend_from_slice(&[x0, y0, uv.u0, uv.v0]);
            vertices.extend_from_slice(&[x1, y0, uv.u1, uv.v0]);
            vertices.extend_from_slice(&[x1, y1, uv.u1, uv.v1]);
            vertices.extend_from_slice(&[x0, y1, uv.u0, uv.v1]);
            indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
        }
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

    /// The single-line block a place label is, so the tests below read as they did
    /// before `emit` learned about lines.
    fn one(glyphs: Vec<ShapedGlyph>, advance: f32) -> Vec<ShapedLine> {
        vec![ShapedLine { glyphs, advance }]
    }

    /// `emit` as every place label calls it: one line, centred, no offset.
    fn emit_centred(
        atlas: &GlyphAtlas,
        weight: Weight,
        lines: &[ShapedLine],
        text_px: f32,
        tile_span_px: f32,
        vertices: &mut Vec<f32>,
        indices: &mut Vec<u32>,
    ) {
        emit(
            atlas,
            weight,
            lines,
            (0.5, 0.5),
            Anchor::Center,
            (0.0, 0.0),
            text_px,
            tile_span_px,
            vertices,
            indices,
        );
    }

    #[test]
    fn shaping_advances_the_pen_by_each_glyphs_advance() {
        let Some(atlas) = atlas() else { return };
        let (shaped, total) = shape(&atlas, Weight::Regular, "AB", false);
        assert_eq!(shaped.len(), 2);
        assert!(total > 0.0);
        assert_eq!(shaped[0].pen_x, 0.0);
        assert!((shaped[1].pen_x - shaped[0].advance).abs() < 1.0, "kern is small");
        assert!((total - (shaped[0].advance + shaped[1].advance)).abs() < 2.0);
    }

    #[test]
    fn unknown_codepoints_are_skipped_not_tofu() {
        let Some(atlas) = atlas() else { return };
        let (shaped, _) = shape(&atlas, Weight::Regular, "A\u{4E2D}B", false);
        assert_eq!(shaped.len(), 2, "CJK is M5; skip rather than tofu");
    }

    #[test]
    fn emit_produces_two_triangles_per_glyph() {
        let Some(atlas) = atlas() else { return };
        let (shaped, total) = shape(&atlas, Weight::Regular, "Hi", false);
        let (mut v, mut idx) = (Vec::new(), Vec::new());
        emit_centred(&atlas, Weight::Regular, &one(shaped, total), 12.0, 256.0, &mut v, &mut idx);
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

    /// The transform belongs to shaping, so the shaped run already carries the
    /// uppercase codepoints *and* their metrics. Doing it at emission instead drew an
    /// uppercase glyph in a lowercase glyph's quad.
    #[test]
    fn uppercase_transform_matches_the_authored_country_style() {
        let Some(atlas) = atlas() else { return };
        // x-height letters only: an ascender like `b` is already taller than a capital,
        // so it would not show the difference this is checking for.
        let (upper, total) = shape(&atlas, Weight::Medium, "ace", true);
        let spelled: String = upper.iter().map(|g| g.ch).collect();
        assert_eq!(spelled, "ACE", "shaping applies the transform");
        let (lower, lower_total) = shape(&atlas, Weight::Medium, "ace", false);
        assert!(total > lower_total, "capitals are wider, so the run's advance grows");
        for (u, l) in upper.iter().zip(lower.iter()) {
            assert!(u.top > l.top, "{:?} must sit at cap height, not x-height", u.ch);
        }
        let (mut v, mut idx) = (Vec::new(), Vec::new());
        emit_centred(&atlas, Weight::Medium, &one(upper, total), 12.0, 256.0, &mut v, &mut idx);
        assert_eq!(idx.len(), 18, "three glyphs still emit");
    }

    /// A space has an advance and no outline, so `outline_glyph` gives nothing for it.
    /// If that is treated as "no such glyph" the space never reaches the metrics table,
    /// shaping drops it, and "Telegraph Hill" renders as "TelegraphHill".
    #[test]
    fn a_space_advances_the_pen() {
        let Some(atlas) = atlas() else { return };
        let space = atlas.metrics(Weight::Regular, ' ').expect("space is in the charset");
        assert!(space.advance > 0.0, "a space has to carry an advance");
        assert_eq!(space.w, 0.0, "and no ink");

        let (shaped, total) = shape(&atlas, Weight::Regular, "a b", false);
        assert_eq!(shaped.len(), 3, "the space is shaped, not skipped");
        assert_eq!(shaped.iter().map(|g| g.ch).collect::<String>(), "a b");
        let (tight, tight_total) = shape(&atlas, Weight::Regular, "ab", false);
        assert_eq!(tight.len(), 2);
        assert!(total > tight_total + 100.0, "the gap has to be worth something");

        // And it draws nothing: a degenerate quad, or none at all.
        let (mut v, mut idx) = (Vec::new(), Vec::new());
        emit_centred(&atlas, Weight::Regular, &one(shaped, total), 64.0, 512.0, &mut v, &mut idx);
        assert!(idx.len() <= 18, "at most one quad per shaped glyph");
    }

    /// P1 gibberish guard, shaping half: "Sacramento" shapes one glyph per
    /// char, in order, each carrying its own codepoint — so the quad stream
    /// spells the input, not a rotation or subset of it.
    #[test]
    fn sacramento_shapes_in_order_with_its_own_codepoints() {
        let Some(atlas) = atlas() else { return };
        let (shaped, total) = shape(&atlas, Weight::Regular, "Sacramento", false);
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
        let (shaped, total) = shape(&atlas, Weight::Medium, "Sacramento", true);
        let (mut v, mut idx) = (Vec::new(), Vec::new());
        emit_centred(&atlas, Weight::Medium, &one(shaped.clone(), total), 16.0, 256.0, &mut v, &mut idx);
        assert_eq!(v.len() / FLOATS_PER_VERTEX, shaped.len() * 4);
        for (g, quad) in shaped.iter().zip(v.chunks(FLOATS_PER_VERTEX * 4)) {
            let uv = atlas.uv(Weight::Medium, g.ch).expect("shaped implies UV");
            let corners = [(uv.u0, uv.v0), (uv.u1, uv.v0), (uv.u1, uv.v1), (uv.u0, uv.v1)];
            for (vert, (eu, ev)) in quad.chunks(FLOATS_PER_VERTEX).zip(corners) {
                assert!((vert[2] - eu).abs() < 1e-6, "{:?} u", g.ch);
                assert!((vert[3] - ev).abs() < 1e-6, "{:?} v", g.ch);
            }
        }
    }

    #[test]
    fn an_empty_string_emits_nothing() {
        let Some(atlas) = atlas() else { return };
        let (shaped, total) = shape(&atlas, Weight::Regular, "", false);
        assert!(shaped.is_empty() && total == 0.0);
        let (mut v, mut idx) = (Vec::new(), Vec::new());
        emit_centred(&atlas, Weight::Regular, &one(shaped, total), 12.0, 256.0, &mut v, &mut idx);
        assert!(v.is_empty() && idx.is_empty());
        assert!(
            shape_wrapped(&atlas, Weight::Regular, "", false, 8.0).is_empty(),
            "nothing to shape is no lines, not one empty line",
        );
    }

    /// Screen size of the bounding box a run's quads cover, in px.
    fn emitted_extent_px(text: &str, text_px: f32, tile_span_px: f32) -> Option<(f32, f32)> {
        let atlas = atlas()?;
        let (shaped, total) = shape(&atlas, Weight::Regular, text, false);
        let (mut v, mut idx) = (Vec::new(), Vec::new());
        emit_centred(&atlas, Weight::Regular, &one(shaped, total), text_px, tile_span_px, &mut v, &mut idx);
        let xs: Vec<f32> = v.chunks(FLOATS_PER_VERTEX).map(|c| c[0]).collect();
        let ys: Vec<f32> = v.chunks(FLOATS_PER_VERTEX).map(|c| c[1]).collect();
        let span = |a: &[f32]| {
            let lo = a.iter().cloned().fold(f32::INFINITY, f32::min);
            let hi = a.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
            (hi - lo) * tile_span_px
        };
        Some((span(&xs), span(&ys)))
    }

    /// Emission has to size each quad straight from the atlas metrics. This is the
    /// join between the two halves of the text pipeline, and the half-size bug lived
    /// exactly here: the quad was right and the UV rect was not.
    #[test]
    fn emit_sizes_each_quad_from_its_atlas_metrics() {
        let Some(atlas) = atlas() else { return };
        let (text_px, tile_span_px) = (64.0f32, 512.0f32);
        let (shaped, total) = shape(&atlas, Weight::Regular, "H", false);
        let (mut v, mut idx) = (Vec::new(), Vec::new());
        emit_centred(&atlas, Weight::Regular, &one(shaped, total), text_px, tile_span_px, &mut v, &mut idx);
        let m = atlas.metrics(Weight::Regular, 'H').expect("H has metrics");
        let per_em = text_px / UP_EM as f32;
        let xs: Vec<f32> = v.chunks(FLOATS_PER_VERTEX).map(|c| c[0]).collect();
        let ys: Vec<f32> = v.chunks(FLOATS_PER_VERTEX).map(|c| c[1]).collect();
        let span = |a: &[f32]| {
            (a.iter().cloned().fold(f32::NEG_INFINITY, f32::max)
                - a.iter().cloned().fold(f32::INFINITY, f32::min))
                * tile_span_px
        };
        assert!((span(&xs) - m.w * per_em).abs() < 0.01, "{} vs {}", span(&xs), m.w * per_em);
        assert!((span(&ys) - m.h * per_em).abs() < 0.01, "{} vs {}", span(&ys), m.h * per_em);
    }

    /// THE regression guard for the half-size text bug, end to end.
    ///
    /// A capital's quad is its cap height grown by the SDF spread on both sides, so
    /// it lands comfortably above the cap height and under twice it. The two bugs
    /// this bounds both pushed it the other way: `UP_EM` of 2048 against a 1000-upem
    /// face halved everything, and ab_glyph's height-based `PxScale` shaved another
    /// quarter off the bitmap-to-font-unit conversion.
    #[test]
    fn a_capital_draws_around_its_cap_height() {
        let text_px = 64.0;
        let Some((_, height_px)) = emitted_extent_px("H", text_px, 512.0) else { return };
        let cap_px = CAP_HEIGHT_EM * text_px;
        assert!(
            height_px > cap_px * 1.3,
            "{height_px:.1}px is too small against a {cap_px:.1}px cap height",
        );
        assert!(height_px < cap_px * 2.0, "{height_px:.1}px is implausibly tall");
    }

    /// The horizontal counterpart: the run's ink has to track its advance width, so
    /// glyphs cannot drift apart from the pen positions that space them.
    #[test]
    fn a_runs_ink_fills_its_advance_width() {
        let Some(atlas) = atlas() else { return };
        let text_px = 64.0;
        let (_, total) = shape(&atlas, Weight::Regular, "HELLO", false);
        let advance_px = total / UP_EM as f32 * text_px;
        let Some((width_px, _)) = emitted_extent_px("HELLO", text_px, 512.0) else { return };
        // Wider than the advance by the spread margin at each end, never narrower.
        assert!(
            width_px > advance_px,
            "ink {width_px:.1}px rattles inside a {advance_px:.1}px advance",
        );
        assert!(width_px < advance_px * 1.35, "ink {width_px:.1}px overruns its advance");
    }

    /// Labels centre on their anchor. The old emission reduced to
    /// `y0 = anchor.y - top * k` with `top` measured from the ascender, which put
    /// every label most of an em above the point it was labelling.
    #[test]
    fn a_label_centres_its_cap_box_on_the_anchor() {
        let Some(atlas) = atlas() else { return };
        let (shaped, total) = shape(&atlas, Weight::Regular, "HELLO", false);
        let (mut v, mut idx) = (Vec::new(), Vec::new());
        let (text_px, tile_span_px) = (64.0f32, 512.0f32);
        emit_centred(&atlas, Weight::Regular, &one(shaped, total), text_px, tile_span_px, &mut v, &mut idx);
        let ys: Vec<f32> = v.chunks(FLOATS_PER_VERTEX).map(|c| c[1]).collect();
        let lo = ys.iter().cloned().fold(f32::INFINITY, f32::min);
        let hi = ys.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
        let centre_px = ((lo + hi) * 0.5 - 0.5) * tile_span_px;
        // Within a couple of px of the anchor, not most of an em above it. The quad
        // pads symmetrically, so the spread margin cancels out of the centre.
        assert!(centre_px.abs() < 3.0, "run centre sits {centre_px:.1}px off the anchor");
    }

    // --- wrapping, anchors and multi-line ----------------------------------

    /// **The constraint the whole phase is written under.** A place label is one line,
    /// centred, with no offset — and it has to come out of the generalised `emit` as the
    /// identical float stream, or every `places-*` label moves by a rounding error.
    ///
    /// Compared bit-for-bit rather than approximately: a near-miss here means the
    /// arithmetic was reassociated, and that is exactly the change that would go unnoticed.
    #[test]
    fn a_single_centred_line_is_bit_identical_to_the_old_layout() {
        let Some(atlas) = atlas() else { return };
        for (text, upper, weight) in [
            ("Sacramento", false, Weight::Regular),
            ("San Francisco Bay", false, Weight::Regular),
            ("CALIFORNIA", true, Weight::Medium),
        ] {
            for (text_px, tile_span_px) in [(12.0f32, 256.0f32), (64.0, 512.0), (17.5, 1024.0)] {
                let (shaped, total) = shape(&atlas, weight, text, upper);
                let px_per_font_unit = text_px / UP_EM as f32 / tile_span_px;
                // The pre-generalisation expressions, verbatim.
                let origin_x = 0.5 - total * 0.5 * px_per_font_unit;
                let baseline_y = 0.5 + 0.5 * CAP_HEIGHT_EM * UP_EM as f32 * px_per_font_unit;
                let mut expected: Vec<f32> = Vec::new();
                for g in &shaped {
                    let Some(uv) = atlas.uv(weight, g.ch) else { continue };
                    let x0 = origin_x + (g.pen_x + g.bearing_x) * px_per_font_unit;
                    let x1 = x0 + g.w * px_per_font_unit;
                    let y0 = baseline_y - g.top * px_per_font_unit;
                    let y1 = y0 + g.h * px_per_font_unit;
                    expected.extend_from_slice(&[x0, y0, uv.u0, uv.v0]);
                    expected.extend_from_slice(&[x1, y0, uv.u1, uv.v0]);
                    expected.extend_from_slice(&[x1, y1, uv.u1, uv.v1]);
                    expected.extend_from_slice(&[x0, y1, uv.u0, uv.v1]);
                }

                let (mut v, mut idx) = (Vec::new(), Vec::new());
                emit_centred(
                    &atlas,
                    weight,
                    &one(shaped, total),
                    text_px,
                    tile_span_px,
                    &mut v,
                    &mut idx,
                );
                assert_eq!(
                    v.iter().map(|f| f.to_bits()).collect::<Vec<_>>(),
                    expected.iter().map(|f| f.to_bits()).collect::<Vec<_>>(),
                    "{text:?} at {text_px}px/{tile_span_px}px moved",
                );
            }
        }
    }

    /// A place layer authors no `text-max-width`, so wrapping is off and the run stays
    /// whole however long it is.
    #[test]
    fn no_max_width_means_one_line_whatever_the_length() {
        let Some(atlas) = atlas() else { return };
        let long = "Rancho Santa Margarita and Some More Words";
        let lines = shape_wrapped(&atlas, Weight::Regular, long, false, 0.0);
        assert_eq!(lines.len(), 1);
        let (shaped, total) = shape(&atlas, Weight::Regular, long, false);
        assert_eq!(lines[0].glyphs.len(), shaped.len());
        assert_eq!(lines[0].advance.to_bits(), total.to_bits(), "same run, same advance");
    }

    /// The break has to fall at a word boundary and the space itself must be dropped, or
    /// the second line starts indented by a space that is not there.
    #[test]
    fn wrapping_breaks_at_a_space_and_drops_it() {
        let Some(atlas) = atlas() else { return };
        // Two words, each about 5 ems, against an 8-em limit: one break, in the middle.
        let lines = shape_wrapped(&atlas, Weight::Regular, "Golden Gate", false, 4.0);
        let spelled: Vec<String> =
            lines.iter().map(|l| l.glyphs.iter().map(|g| g.ch).collect()).collect();
        assert_eq!(spelled, vec!["Golden".to_string(), "Gate".to_string()]);
        for line in &lines {
            assert_eq!(line.glyphs.first().map(|g| g.pen_x), Some(0.0), "each line's pen restarts");
            assert!(!line.glyphs.iter().any(|g| g.ch == ' '), "the break's space is gone");
            let advance: f32 = line.glyphs.iter().map(|g| g.advance).sum();
            assert!((line.advance - advance).abs() < 1e-3, "the advance is the line's own");
        }
    }

    /// A word longer than the limit cannot be broken — there is no candidate inside it —
    /// so it has to overflow rather than vanish or be cut mid-glyph.
    #[test]
    fn an_unbreakable_word_overflows_rather_than_being_cut() {
        let Some(atlas) = atlas() else { return };
        let lines = shape_wrapped(&atlas, Weight::Regular, "Chargoggagoggmanchauggagogg", false, 4.0);
        assert_eq!(lines.len(), 1, "no break candidate, so no break");
        assert_eq!(
            lines[0].glyphs.iter().map(|g| g.ch).collect::<String>(),
            "Chargoggagoggmanchauggagogg",
        );
    }

    /// Backtracking is the difference from greedy wrapping, and this is where it shows:
    /// greedy fills line one until the next word will not fit, leaving a stub behind.
    /// MapLibre balances, so no line may be wildly shorter than the others.
    #[test]
    fn wrapping_balances_the_lines_rather_than_filling_greedily() {
        let Some(atlas) = atlas() else { return };
        let lines =
            shape_wrapped(&atlas, Weight::Regular, "Mount Diablo State Park", false, 8.0);
        assert!(lines.len() >= 2, "{} lines", lines.len());
        let widest = lines.iter().fold(0.0f32, |w, l| w.max(l.advance));
        let narrowest = lines.iter().fold(f32::MAX, |w, l| w.min(l.advance));
        // A greedy pass on this string leaves a one-word last line, i.e. a ratio far
        // worse than this. The bound is loose on purpose: it is testing that balancing
        // happens at all, not pinning one break.
        assert!(
            narrowest > widest * 0.4,
            "lines {:?} are ragged enough to look greedy",
            lines.iter().map(|l| l.advance).collect::<Vec<_>>(),
        );
    }

    /// MapLibre forces a break on a newline, and [`break_penalty`] transcribes that. It
    /// can never fire here, and this pins why rather than leaving a reader to wonder: the
    /// glyph atlas covers printable ASCII only, so [`shape`] drops `\n` before the breaker
    /// ever sees it. The arm stays because the set it belongs to is a verbatim copy of the
    /// reference's, and a partial copy is harder to check than a complete one.
    #[test]
    fn a_newline_is_dropped_by_shaping_before_it_can_force_a_break() {
        let Some(atlas) = atlas() else { return };
        let lines = shape_wrapped(&atlas, Weight::Regular, "A\nB", false, 40.0);
        let spelled: Vec<String> =
            lines.iter().map(|l| l.glyphs.iter().map(|g| g.ch).collect()).collect();
        assert_eq!(spelled, vec!["AB".to_string()], "the atlas has no newline glyph");
        assert!(atlas.metrics(Weight::Regular, '\n').is_none(), "and this is why");
    }

    /// Extra lines stack downward at 1.2 em and the block stays centred on the anchor, so
    /// a two-line label straddles its point instead of hanging off it.
    #[test]
    fn extra_lines_stack_at_the_line_height_around_the_anchor() {
        let Some(atlas) = atlas() else { return };
        let (text_px, span) = (64.0f32, 512.0f32);
        // The same word twice, so the two lines carry identical glyphs and the distance
        // between their ink tops is the baseline distance and nothing else. Two different
        // words would differ by their tallest letters as well.
        let lines = shape_wrapped(&atlas, Weight::Regular, "Gate Gate", false, 3.0);
        assert_eq!(lines.len(), 2, "{:?}", lines.iter().map(|l| l.advance).collect::<Vec<_>>());
        let (mut v, mut idx) = (Vec::new(), Vec::new());
        emit_centred(&atlas, Weight::Regular, &lines, text_px, span, &mut v, &mut idx);

        // Where each line's glyphs landed, by splitting the vertex stream at the line
        // boundary (4 vertices a glyph, in order).
        let per_glyph = FLOATS_PER_VERTEX * 4;
        let first_count = lines[0].glyphs.len() * per_glyph;
        let top_of = |slice: &[f32]| {
            slice.chunks(FLOATS_PER_VERTEX).map(|c| c[1]).fold(f32::MAX, f32::min)
        };
        let one = top_of(&v[..first_count]);
        let two = top_of(&v[first_count..]);
        let gap = (two - one) * span;
        assert!(
            (gap - LINE_HEIGHT_EM * text_px).abs() < 0.5,
            "lines are {gap:.2}px apart, not {:.2}px",
            LINE_HEIGHT_EM * text_px,
        );
        // And the pair straddles the anchor: the block's vertical centre is on it.
        let ys: Vec<f32> = v.chunks(FLOATS_PER_VERTEX).map(|c| c[1]).collect();
        let lo = ys.iter().cloned().fold(f32::INFINITY, f32::min);
        let hi = ys.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
        let centre_px = ((lo + hi) * 0.5 - 0.5) * span;
        assert!(centre_px.abs() < 3.0, "a two-line block sits {centre_px:.1}px off centre");
    }

    /// The anchor decides which side of the point the label sits on, and the offset pushes
    /// it further that way. `Left` means the block's left edge is at the point, so the text
    /// runs to the **right** — MapLibre's sense, and the one that reads backwards.
    #[test]
    fn the_anchor_puts_the_block_on_the_right_side_of_the_point() {
        let Some(atlas) = atlas() else { return };
        let (text_px, span) = (32.0f32, 512.0f32);
        let lines = shape_wrapped(&atlas, Weight::Regular, "Cafe", false, 8.0);
        let bounds = |anchor: Anchor, offset: f32| {
            let (mut v, mut idx) = (Vec::new(), Vec::new());
            emit(
                &atlas,
                Weight::Regular,
                &lines,
                (0.5, 0.5),
                anchor,
                (offset, 0.0),
                text_px,
                span,
                &mut v,
                &mut idx,
            );
            let xs: Vec<f32> = v.chunks(FLOATS_PER_VERTEX).map(|c| c[0]).collect();
            (
                xs.iter().cloned().fold(f32::INFINITY, f32::min),
                xs.iter().cloned().fold(f32::NEG_INFINITY, f32::max),
            )
        };
        let (left_lo, _) = bounds(Anchor::Left, 1.1);
        let (_, right_hi) = bounds(Anchor::Right, 1.1);
        let em = text_px / span;
        // The SDF spread pads the ink a little past the pen, so the edges land near the
        // offset rather than exactly on it.
        assert!(left_lo > 0.5, "a left-anchored label must sit right of its point");
        assert!(right_hi < 0.5, "a right-anchored label must sit left of its point");
        assert!(
            (left_lo - (0.5 + 1.1 * em)).abs() < 0.3 * em,
            "left edge {left_lo} is not ~1.1 em right of the point",
        );
        assert!(
            (right_hi - (0.5 - 1.1 * em)).abs() < 0.3 * em,
            "right edge {right_hi} is not ~1.1 em left of the point",
        );
        // Same block either side of the point: the two placements are a translation of
        // each other, so their ink spans match. (Their *edges* are not mirror images —
        // the first glyph's left bearing and the last one's right overhang differ.)
        let (left_lo2, left_hi) = bounds(Anchor::Left, 1.1);
        let (right_lo, right_hi2) = bounds(Anchor::Right, 1.1);
        assert!(
            ((left_hi - left_lo2) - (right_hi2 - right_lo)).abs() < 1e-6,
            "the two anchors drew different-sized blocks",
        );
    }

    /// The offset's sign comes from the anchor, not from the style: MapLibre takes its
    /// magnitude. A negative `text-offset` must not flip a left-anchored label across its
    /// own point.
    #[test]
    fn a_negative_offset_still_pushes_away_from_the_point() {
        let Some(atlas) = atlas() else { return };
        let lines = shape_wrapped(&atlas, Weight::Regular, "Bar", false, 8.0);
        let left_edge = |offset: f32| {
            let (mut v, mut idx) = (Vec::new(), Vec::new());
            emit(
                &atlas, Weight::Regular, &lines, (0.5, 0.5), Anchor::Left, (offset, 0.0),
                32.0, 512.0, &mut v, &mut idx,
            );
            v.chunks(FLOATS_PER_VERTEX).map(|c| c[0]).fold(f32::MAX, f32::min)
        };
        assert!((left_edge(-1.1) - left_edge(1.1)).abs() < 1e-6);
    }
}
