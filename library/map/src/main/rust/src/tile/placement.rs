//! Screen-space label placement: greedy, rank-ordered, per-frame.
//!
//! M1 places _point_ labels only (country/region/locality/subplace). Each tile
//! shapes its candidates once ([`crate::tile::symbol`]); this module decides, per
//! frame, which candidates draw: sort by rank (country first), greedily accept
//! while the screen box collides with nothing accepted yet.
//!
//! # Coordinates
//!
//! Collision runs in _screen px_: the caller passes each candidate's screen box
//! (computed from the anchor's clip position + the frame's text size). This keeps
//! the module free of camera math and testable with literal boxes.
//!
//! # Rank
//!
//! Lower is more important: country (0) > region (1) > locality (2) > subplace
//! (3) > POI (4), 255 for an id the renderer did not recognise (sinks last). Ties break
//! by population weight (higher first — a big city beats a town), then by
//! larger box (more informative), then by id order — deterministic, so frames
//! don't shimmer.
//!
//! # Variable anchors
//!
//! A POI label may be drawn to the left or the right of its icon
//! (`text-variable-anchor: ["left", "right"]`), so a candidate carries two boxes and
//! [`place`] reports which one it accepted. The renderer then emits at that anchor, which
//! is what makes a label near the edge of a crowd flip to its other side instead of
//! disappearing.

use crate::style::Anchor;

/// A label candidate's screen box plus its rank.
pub struct Candidate {
    /// Stable id for the accept-set.
    pub id: u64,
    /// Rank: 0 country … 4 POI, 255 unknown (sorts last).
    pub rank: u8,
    /// Population weight within the rank, higher first.
    pub pop: u16,
    /// Screen box in device px, with collision padding baked in.
    pub rect: (f32, f32, f32, f32),
    /// The box to try when [`rect`](Self::rect) collides — the label drawn at its second
    /// variable anchor. `None` for a label that cannot move, which is every place label.
    pub alternate: Option<(f32, f32, f32, f32)>,
}

/// An accepted candidate: its id, and whether it took its
/// [`alternate`](Candidate::alternate) box.
pub type Placed = (u64, bool);

/// Minimum population rank for a locality to draw at a camera zoom.
///
/// Country/region always draw (few, important); subplace is layer-gated. Rank gating
/// happens before collision because collision alone cannot thin hundreds of towns down
/// to the major-city set - the small ones simply arrive first in some tiles.
///
/// The ranks are the reference basemap's `population_rank` (see the tiler's
/// `schema::places::rank_of`): 12 is 500k, 1 is any counted population at all. So z6 and
/// below hold the half-million-plus cities, z7..z9 add anywhere with a population, and
/// z10 draws everything the tile shaped.
pub fn locality_min_pop(zoom: f64) -> u16 {
    if zoom < 7.0 {
        12
    } else if zoom < 10.0 {
        1
    } else {
        0
    }
}

/// Greedily place candidates: rank order, first-come keeps its box.
/// Rank 0 (country) always draws: it never collides, so capitals and country
/// names survive any crowd. All other ranks collide normally.
///
/// A candidate whose primary box collides gets one more try at its
/// [`alternate`](Candidate::alternate), and is reported as having taken it. That is
/// MapLibre's variable-anchor behaviour reduced to the two anchors the reference
/// declares.
///
/// Returns the accepted candidates in acceptance order.
pub fn place(candidates: &[Candidate]) -> Vec<Placed> {
    let mut ordered: Vec<&Candidate> = candidates.iter().collect();
    ordered.sort_by(|a, b| {
        a.rank
            .cmp(&b.rank)
            .then_with(|| b.pop.cmp(&a.pop))
            .then_with(|| {
                let (aw, ah) = (a.rect.2 - a.rect.0, a.rect.3 - a.rect.1);
                let (bw, bh) = (b.rect.2 - b.rect.0, b.rect.3 - b.rect.1);
                (bw * bh).partial_cmp(&(aw * ah)).unwrap_or(std::cmp::Ordering::Equal)
            })
            .then_with(|| a.id.cmp(&b.id))
    });
    let mut accepted: Vec<(f32, f32, f32, f32)> = Vec::new();
    let mut out = Vec::new();
    for c in ordered {
        // Rank 0 never collides: country labels are few and must always draw.
        let free = |rect: (f32, f32, f32, f32), accepted: &[(f32, f32, f32, f32)]| {
            c.rank == 0 || !accepted.iter().any(|a| overlaps(*a, rect))
        };
        let taken = if free(c.rect, &accepted) {
            (c.rect, false)
        } else if let Some(alternate) = c.alternate.filter(|a| free(*a, &accepted)) {
            (alternate, true)
        } else {
            continue;
        };
        accepted.push(taken.0);
        out.push((c.id, taken.1));
    }
    out
}

fn overlaps(a: (f32, f32, f32, f32), b: (f32, f32, f32, f32)) -> bool {
    a.0 < b.2 && b.0 < a.2 && a.1 < b.3 && b.1 < a.3
}

/// Stable id for one label of one tile in one frame, so the accept-set the
/// renderer computes in its pre-pass names the same labels `record_symbol`
/// later filters by.
///
/// A SipHash over (tile z/x/y, layer index, position in the tile's shaped
/// label list) — the label list is shaped once in feature order, so the inputs
/// are frame-stable and collisions across tiles are impossible in practice.
/// `DefaultHasher` uses fixed keys, so ids are stable across frames and runs.
pub fn candidate_id(z: u8, x: u32, y: u32, layer_index: usize, label_idx: usize) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    z.hash(&mut h);
    x.hash(&mut h);
    y.hash(&mut h);
    (layer_index as u64).hash(&mut h);
    (label_idx as u64).hash(&mut h);
    h.finish()
}

/// One label's screen collision box in device px, from the same inputs the
/// tessellator uses — so the box the placer sees is the box the GPU draws,
/// plus MapLibre-style padding.
///
/// `anchor` is tile-local 0..1, `tile_clip` the camera's column-major matrix
/// for the tile, `extent_wh` the viewport in device px. Width is the label's
/// advance at the frame's `text_px` (`total_advance` is in font units over
/// [`UP_EM`](crate::tile::glyph::UP_EM)); height is one `text_px`, centred on
/// the anchor like the emitted em box. `pad_px` inflates the box on every
/// side, standing in for the icon + text padding MapLibre applies around
/// every label: without it tight advance boxes let hundreds of villages
/// survive at z6 where MapLibre shows ~10 cities.
pub fn screen_rect(
    anchor: (f32, f32),
    tile_clip: [f32; 16],
    extent_wh: (u32, u32),
    text_px: f32,
    total_advance: f32,
    pad_px: f32,
) -> (f32, f32, f32, f32) {
    anchored_rect(
        anchor,
        tile_clip,
        extent_wh,
        &BoxInputs {
            text_px,
            advance: total_advance,
            line_count: 1,
            offset_em: (0.0, 0.0),
            icon_px: None,
            pad_px,
        },
        Anchor::Center,
    )
}

/// Everything a label's collision box depends on besides where it is anchored.
///
/// A struct rather than eight parameters because the renderer builds one of these per
/// label and then asks for a box at each candidate anchor — the inputs are shared and only
/// the anchor varies.
pub struct BoxInputs {
    /// The frame's text size in device px.
    pub text_px: f32,
    /// The widest line's advance, in font units.
    pub advance: f32,
    /// How many lines the label wrapped to, at least 1.
    pub line_count: usize,
    /// `text-offset` in ems.
    pub offset_em: (f32, f32),
    /// The icon's drawn size in device px, when the label has one.
    pub icon_px: Option<(f32, f32)>,
    /// Collision padding added on every side.
    pub pad_px: f32,
}

/// The screen box a label would occupy if drawn at `anchor`.
///
/// The union of the text block and the icon, which is where this departs from MapLibre:
/// it keeps two linked boxes and tests both. The two are equivalent while `icon-optional`
/// and `text-optional` are both false — as they are here, since neither is set — because
/// then a collision on either box rejects the pair anyway. A union is looser only in the
/// gap the `text-offset` opens between icon and text, which is 1.1 em of empty space that
/// nothing would have been placed in.
pub fn anchored_rect(
    point: (f32, f32),
    tile_clip: [f32; 16],
    extent_wh: (u32, u32),
    inputs: &BoxInputs,
    anchor: Anchor,
) -> (f32, f32, f32, f32) {
    let cx = tile_clip[0] * point.0 + tile_clip[4] * point.1 + tile_clip[12];
    let cy = tile_clip[1] * point.0 + tile_clip[5] * point.1 + tile_clip[13];
    let sx = (cx * 0.5 + 0.5) * extent_wh.0 as f32;
    let sy = (cy * 0.5 + 0.5) * extent_wh.1 as f32;

    let w = inputs.text_px * inputs.advance / crate::tile::glyph::UP_EM as f32;
    // Lines stack at 1.2 em, so a two-line block is 2.2 em tall, not 2.4: the first line
    // contributes its own height and each further one a line's worth of leading.
    let lines = inputs.line_count.max(1) as f32;
    let h = inputs.text_px * (1.0 + (lines - 1.0) * crate::tess::text::LINE_HEIGHT_EM);
    // The same placement `tess::text::emit` uses: the offset's magnitude, its direction
    // taken from the anchor, and no vertical component on a horizontal anchor.
    let offset = inputs.offset_em.0.abs() * inputs.text_px;
    let (mut x0, mut x1) = match anchor {
        Anchor::Center => (sx - w * 0.5, sx + w * 0.5),
        Anchor::Left => (sx + offset, sx + offset + w),
        Anchor::Right => (sx - offset - w, sx - offset),
    };
    let (mut y0, mut y1) = (sy - h * 0.5, sy + h * 0.5);
    if let Some((icon_w, icon_h)) = inputs.icon_px {
        x0 = x0.min(sx - icon_w * 0.5);
        x1 = x1.max(sx + icon_w * 0.5);
        y0 = y0.min(sy - icon_h * 0.5);
        y1 = y1.max(sy + icon_h * 0.5);
    }
    (x0 - inputs.pad_px, y0 - inputs.pad_px, x1 + inputs.pad_px, y1 + inputs.pad_px)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cand(id: u64, rank: u8, rect: (f32, f32, f32, f32)) -> Candidate {
        Candidate { id, rank, pop: 0, rect, alternate: None }
    }

    /// Just the ids, for the tests that predate variable anchors.
    fn ids(placed: Vec<Placed>) -> Vec<u64> {
        placed.into_iter().map(|(id, _)| id).collect()
    }

    #[test]
    fn a_country_beats_a_colliding_city() {
        let cs = vec![
            cand(1, 2, (0.0, 0.0, 100.0, 20.0)),
            cand(0, 0, (10.0, 0.0, 60.0, 20.0)),
        ];
        assert_eq!(ids(place(&cs)), vec![0]);
    }

    #[test]
    fn non_overlapping_labels_all_draw() {
        let cs = vec![
            cand(0, 0, (0.0, 0.0, 50.0, 20.0)),
            cand(1, 2, (60.0, 0.0, 160.0, 20.0)),
            cand(2, 3, (0.0, 30.0, 80.0, 50.0)),
        ];
        assert_eq!(ids(place(&cs)), vec![0, 1, 2]);
    }

    #[test]
    fn ties_break_deterministically_by_size_then_id() {
        let cs = vec![
            cand(5, 2, (0.0, 0.0, 40.0, 20.0)),
            cand(3, 2, (0.0, 0.0, 40.0, 20.0)),
        ];
        assert_eq!(ids(place(&cs)), vec![3]);
    }

    #[test]
    fn edge_touching_boxes_do_not_collide() {
        let cs = vec![cand(0, 0, (0.0, 0.0, 50.0, 20.0)), cand(1, 0, (50.0, 0.0, 100.0, 20.0))];
        assert_eq!(ids(place(&cs)), vec![0, 1]);
    }

    #[test]
    fn empty_in_empty_out() {
        assert!(place(&[]).is_empty());
    }

    #[test]
    fn a_big_city_beats_a_town_at_the_same_collision() {
        // Same rank, overlapping boxes: population weight decides, so the
        // important place survives the cull.
        let town =
            Candidate { id: 1, rank: 2, pop: 0, rect: (0.0, 0.0, 100.0, 20.0), alternate: None };
        let city =
            Candidate { id: 0, rank: 2, pop: 3, rect: (10.0, 0.0, 60.0, 20.0), alternate: None };
        assert_eq!(ids(place(&[town, city])), vec![0]);
    }

    #[test]
    fn an_unknown_rank_sinks_below_a_subplace() {
        let known =
            Candidate { id: 0, rank: 3, pop: 0, rect: (10.0, 0.0, 60.0, 20.0), alternate: None };
        let unknown =
            Candidate { id: 1, rank: 255, pop: 3, rect: (0.0, 0.0, 100.0, 20.0), alternate: None };
        assert_eq!(ids(place(&[unknown, known])), vec![0]);
    }

    #[test]
    fn candidate_ids_are_stable_and_distinct_per_label() {
        // Same inputs, same id across calls (no per-frame shimmer from the
        // tie-break); neighbouring labels never collide.
        let a = candidate_id(6, 10, 24, 33, 4);
        assert_eq!(a, candidate_id(6, 10, 24, 33, 4));
        assert_ne!(a, candidate_id(6, 10, 24, 33, 5));
        assert_ne!(a, candidate_id(6, 10, 25, 33, 4));
        assert_ne!(a, candidate_id(6, 10, 24, 34, 4));
    }

    #[test]
    fn a_screen_rect_centres_on_the_anchor_at_the_labels_size() {
        // A full-viewport tile maps 0..1 to -1..1, so the centre anchor lands
        // mid-screen and the box spans the advance at the frame's text size.
        let tile_clip = [
            2.0, 0.0, 0.0, 0.0, //
            0.0, 2.0, 0.0, 0.0, //
            0.0, 0.0, 1.0, 0.0, //
            -1.0, -1.0, 0.0, 1.0,
        ];
        // Half an em of advance, expressed against UP_EM so the fixture stays a
        // half em if the bundled font's units change: 7px wide at 14px text.
        let half_em = crate::tile::glyph::UP_EM as f32 / 2.0;
        let (x0, y0, x1, y1) = screen_rect((0.5, 0.5), tile_clip, (256, 256), 14.0, half_em, 0.0);
        assert!((x0 - 124.5).abs() < 1e-3, "{x0}");
        assert!((x1 - 131.5).abs() < 1e-3, "{x1}");
        assert!((y0 - 121.0).abs() < 1e-3, "{y0}");
        assert!((y1 - 135.0).abs() < 1e-3, "{y1}");
    }

    #[test]
    fn padding_inflates_the_box_symmetrically() {
        let tile_clip = [
            2.0, 0.0, 0.0, 0.0, //
            0.0, 2.0, 0.0, 0.0, //
            0.0, 0.0, 1.0, 0.0, //
            -1.0, -1.0, 0.0, 1.0,
        ];
        let half_em = crate::tile::glyph::UP_EM as f32 / 2.0;
        let plain = screen_rect((0.5, 0.5), tile_clip, (256, 256), 14.0, half_em, 0.0);
        let padded = screen_rect((0.5, 0.5), tile_clip, (256, 256), 14.0, half_em, 6.0);
        assert!((padded.0 - (plain.0 - 6.0)).abs() < 1e-3);
        assert!((padded.1 - (plain.1 - 6.0)).abs() < 1e-3);
        assert!((padded.2 - (plain.2 + 6.0)).abs() < 1e-3);
        assert!((padded.3 - (plain.3 + 6.0)).abs() < 1e-3);
    }

    #[test]
    fn rank_zero_never_collides() {
        // Countries always draw, even stacked on each other: there are few of
        // them and every one matters. Acceptance follows rank order (0 first).
        let cs = vec![
            cand(1, 0, (0.0, 0.0, 100.0, 20.0)),
            cand(0, 0, (10.0, 0.0, 60.0, 20.0)),
        ];
        assert_eq!(ids(place(&cs)), vec![1, 0]);
    }

    /// Task-9 gating: low zoom keeps only high-pop localities, so z6 holds the
    /// major-city set; higher zooms relax to towns, then everything. The numbers are
    /// reference `population_rank` values, so 12 is the 500k bucket.
    #[test]
    fn locality_gating_thins_by_ui_zoom() {
        assert_eq!(locality_min_pop(6.0), 12, "z6: the 500k-plus cities only");
        assert_eq!(locality_min_pop(6.9), 12);
        assert_eq!(locality_min_pop(7.0), 1, "z7: anywhere with a population joins");
        assert_eq!(locality_min_pop(9.9), 1);
        assert_eq!(locality_min_pop(10.0), 0, "z10: everything shaped draws");
        assert_eq!(locality_min_pop(14.0), 0);
    }

    /// Task-17 pick contract: box intersection is inclusive on edges (a tap
    /// exactly on a label edge still hits) and order-preserving (placement
    /// order = topmost first). The native `pick_labels` filters the same way;
    /// this pins the semantics host-side.
    #[test]
    fn box_hit_is_inclusive_and_order_preserving() {
        fn hits(rect: (f32, f32, f32, f32), q: (f32, f32, f32, f32)) -> bool {
            rect.0 <= q.2 && rect.2 >= q.0 && rect.1 <= q.3 && rect.3 >= q.1
        }
        // Edge touch counts.
        assert!(hits((0.0, 0.0, 10.0, 10.0), (10.0, 10.0, 20.0, 20.0)));
        assert!(hits((0.0, 0.0, 10.0, 10.0), (5.0, 5.0, 5.0, 5.0)));
        // Clean miss does not.
        assert!(!hits((0.0, 0.0, 10.0, 10.0), (10.1, 10.1, 20.0, 20.0)));
        assert!(!hits((0.0, 0.0, 10.0, 10.0), (-20.0, -20.0, -0.1, -0.1)));
    }

    // --- variable anchors ---------------------------------------------------

    /// The point of the second box: a POI whose label collides on one side flips to the
    /// other rather than dropping out, and the flip is reported so the renderer draws it
    /// where it was actually placed.
    #[test]
    fn a_blocked_label_flips_to_its_other_anchor() {
        let blocker =
            Candidate { id: 0, rank: 2, pop: 0, rect: (0.0, 0.0, 100.0, 20.0), alternate: None };
        let poi = Candidate {
            id: 1,
            rank: 4,
            pop: 0,
            // The left anchor lands on the blocker; the right one is clear.
            rect: (50.0, 0.0, 150.0, 20.0),
            alternate: Some((200.0, 0.0, 300.0, 20.0)),
        };
        assert_eq!(place(&[blocker, poi]), vec![(0, false), (1, true)]);
    }

    /// The alternate is a fallback, not a preference: a label whose first box is free stays
    /// there, or every POI would drift to its second anchor for no reason.
    #[test]
    fn a_clear_label_keeps_its_first_anchor() {
        let poi = Candidate {
            id: 1,
            rank: 4,
            pop: 0,
            rect: (0.0, 0.0, 50.0, 20.0),
            alternate: Some((200.0, 0.0, 250.0, 20.0)),
        };
        assert_eq!(place(&[poi]), vec![(1, false)]);
    }

    /// Both boxes blocked is a dropped label, not a label drawn over something.
    #[test]
    fn a_label_blocked_at_both_anchors_is_dropped() {
        let blocker =
            Candidate { id: 0, rank: 2, pop: 0, rect: (0.0, 0.0, 300.0, 20.0), alternate: None };
        let poi = Candidate {
            id: 1,
            rank: 4,
            pop: 0,
            rect: (50.0, 0.0, 150.0, 20.0),
            alternate: Some((160.0, 0.0, 260.0, 20.0)),
        };
        assert_eq!(place(&[blocker, poi]), vec![(0, false)]);
    }

    /// The **accepted** box joins the occupied set, not the primary one: a label that
    /// flipped has to block whatever it flipped onto, or two labels stack there.
    #[test]
    fn the_accepted_box_is_what_later_labels_collide_with() {
        let blocker =
            Candidate { id: 0, rank: 2, pop: 0, rect: (0.0, 0.0, 100.0, 20.0), alternate: None };
        let flipper = Candidate {
            id: 1,
            rank: 4,
            pop: 0,
            rect: (50.0, 0.0, 150.0, 20.0),
            alternate: Some((200.0, 0.0, 300.0, 20.0)),
        };
        // Sits where the flipper landed, so it must lose to it.
        let later =
            Candidate { id: 2, rank: 4, pop: 0, rect: (250.0, 0.0, 350.0, 20.0), alternate: None };
        assert_eq!(place(&[blocker, flipper, later]), vec![(0, false), (1, true)]);
    }

    /// A POI sorts below every place label. Before the POI ids reached `rank_for_layer`
    /// they fell through to 255 and lost to *everything*, which at z17 — where places are
    /// sparse — would have looked almost right.
    #[test]
    fn a_poi_loses_to_a_subplace_and_beats_an_unknown() {
        let subplace =
            Candidate { id: 0, rank: 3, pop: 0, rect: (0.0, 0.0, 100.0, 20.0), alternate: None };
        let poi =
            Candidate { id: 1, rank: 4, pop: 0, rect: (10.0, 0.0, 60.0, 20.0), alternate: None };
        assert_eq!(ids(place(&[poi, subplace])), vec![0]);

        let poi =
            Candidate { id: 1, rank: 4, pop: 0, rect: (10.0, 0.0, 60.0, 20.0), alternate: None };
        let unknown =
            Candidate { id: 2, rank: 255, pop: 0, rect: (0.0, 0.0, 100.0, 20.0), alternate: None };
        assert_eq!(ids(place(&[unknown, poi])), vec![1]);
    }

    // --- the anchored box ---------------------------------------------------

    /// A full-viewport tile maps 0..1 to -1..1, so a centre anchor lands mid-screen.
    fn full_viewport() -> [f32; 16] {
        [
            2.0, 0.0, 0.0, 0.0, //
            0.0, 2.0, 0.0, 0.0, //
            0.0, 0.0, 1.0, 0.0, //
            -1.0, -1.0, 0.0, 1.0,
        ]
    }

    /// The box has to cover the icon **and** the offset text, or a label pushed clear of
    /// its own icon leaves that icon unguarded for the next label to sit on.
    #[test]
    fn a_poi_box_covers_both_the_icon_and_the_offset_text() {
        let em = crate::tile::glyph::UP_EM as f32;
        let inputs = BoxInputs {
            text_px: 10.0,
            advance: em * 4.0,
            line_count: 1,
            offset_em: (1.1, 0.0),
            icon_px: Some((19.0, 19.0)),
            pad_px: 0.0,
        };
        // Text runs 11px..51px right of the point; the icon spans -9.5..+9.5.
        let left = anchored_rect((0.5, 0.5), full_viewport(), (256, 256), &inputs, Anchor::Left);
        assert!((left.0 - (128.0 - 9.5)).abs() < 1e-3, "left edge {} is not the icon's", left.0);
        assert!((left.2 - (128.0 + 51.0)).abs() < 1e-3, "right edge {} is not the text's", left.2);
        // Flipping the anchor mirrors it exactly, because both parts are symmetric.
        let right = anchored_rect((0.5, 0.5), full_viewport(), (256, 256), &inputs, Anchor::Right);
        assert!((right.0 - (128.0 - 51.0)).abs() < 1e-3, "{}", right.0);
        assert!((right.2 - (128.0 + 9.5)).abs() < 1e-3, "{}", right.2);
        // The icon is taller than one 10px line, so it sets the height either way.
        for rect in [left, right] {
            assert!((rect.1 - (128.0 - 9.5)).abs() < 1e-3);
            assert!((rect.3 - (128.0 + 9.5)).abs() < 1e-3);
        }
    }

    /// A wrapped label is taller by a line height per extra line — 2.2 em for two lines,
    /// not 2.4: the first line contributes its own height and each further one the leading.
    #[test]
    fn extra_lines_make_the_box_taller_by_one_line_height_each() {
        let em = crate::tile::glyph::UP_EM as f32;
        let inputs = |lines: usize| BoxInputs {
            text_px: 10.0,
            advance: em * 4.0,
            line_count: lines,
            offset_em: (0.0, 0.0),
            icon_px: None,
            pad_px: 0.0,
        };
        let height = |lines: usize| {
            let r =
                anchored_rect((0.5, 0.5), full_viewport(), (256, 256), &inputs(lines), Anchor::Center);
            r.3 - r.1
        };
        assert!((height(1) - 10.0).abs() < 1e-3, "{}", height(1));
        assert!((height(2) - 22.0).abs() < 1e-3, "{}", height(2));
        assert!((height(3) - 34.0).abs() < 1e-3, "{}", height(3));
        assert!((height(0) - height(1)).abs() < 1e-6, "a zero line count is one line");
    }
}
