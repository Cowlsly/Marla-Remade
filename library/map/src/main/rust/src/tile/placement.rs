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
//! (3), 255 for an id the renderer did not recognise (sinks last). Ties break
//! by population weight (higher first — a big city beats a town), then by
//! larger box (more informative), then by id order — deterministic, so frames
//! don't shimmer.

/// A label candidate's screen box plus its rank.
pub struct Candidate {
    /// Stable id for the accept-set.
    pub id: u64,
    /// Rank: 0 country … 3 subplace, 255 unknown (sorts last).
    pub rank: u8,
    /// Population weight within the rank, higher first.
    pub pop: u16,
    /// Screen box in device px, with collision padding baked in.
    pub rect: (f32, f32, f32, f32),
}

/// Minimum population weight for a locality to draw at a camera zoom (UI
/// zoom, matching MapLibre's per-zoom selection — NOT the offset zoom).
/// Country/region always draw (few, important); subplace is layer-gated.
/// At z6 only pop>=2 localities (million-plus cities + capitals) survive, so
/// the frame holds ~the major-city set instead of every town; z7+ relaxes to
/// pop>=1, z10+ draws everything shaped. Evaluated at the UI zoom so the
/// zoom+1 render offset doesn't shift selection a level.
pub fn locality_min_pop(ui_zoom: f64) -> u16 {
    if ui_zoom < 7.0 {
        2
    } else if ui_zoom < 10.0 {
        1
    } else {
        0
    }
}

/// Greedily place candidates: rank order, first-come keeps its box.
/// Rank 0 (country) always draws: it never collides, so capitals and country
/// names survive any crowd. All other ranks collide normally.
///
/// Returns the ids of the accepted candidates, in acceptance order.
pub fn place(candidates: &[Candidate]) -> Vec<u64> {
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
    let mut accepted: Vec<&Candidate> = Vec::new();
    let mut out = Vec::new();
    for c in ordered {
        // Rank 0 never collides: country labels are few and must always draw.
        if c.rank != 0 && accepted.iter().any(|a| overlaps(a.rect, c.rect)) {
            continue;
        }
        accepted.push(c);
        out.push(c.id);
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
    let cx = tile_clip[0] * anchor.0 + tile_clip[4] * anchor.1 + tile_clip[12];
    let cy = tile_clip[1] * anchor.0 + tile_clip[5] * anchor.1 + tile_clip[13];
    let sx = (cx * 0.5 + 0.5) * extent_wh.0 as f32;
    let sy = (cy * 0.5 + 0.5) * extent_wh.1 as f32;
    let w = text_px * total_advance / crate::tile::glyph::UP_EM as f32;
    let h = text_px;
    (sx - w * 0.5 - pad_px, sy - h * 0.5 - pad_px, sx + w * 0.5 + pad_px, sy + h * 0.5 + pad_px)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cand(id: u64, rank: u8, rect: (f32, f32, f32, f32)) -> Candidate {
        Candidate { id, rank, pop: 0, rect }
    }

    #[test]
    fn a_country_beats_a_colliding_city() {
        let cs = vec![
            cand(1, 2, (0.0, 0.0, 100.0, 20.0)),
            cand(0, 0, (10.0, 0.0, 60.0, 20.0)),
        ];
        assert_eq!(place(&cs), vec![0]);
    }

    #[test]
    fn non_overlapping_labels_all_draw() {
        let cs = vec![
            cand(0, 0, (0.0, 0.0, 50.0, 20.0)),
            cand(1, 2, (60.0, 0.0, 160.0, 20.0)),
            cand(2, 3, (0.0, 30.0, 80.0, 50.0)),
        ];
        assert_eq!(place(&cs), vec![0, 1, 2]);
    }

    #[test]
    fn ties_break_deterministically_by_size_then_id() {
        let cs = vec![
            cand(5, 2, (0.0, 0.0, 40.0, 20.0)),
            cand(3, 2, (0.0, 0.0, 40.0, 20.0)),
        ];
        assert_eq!(place(&cs), vec![3]);
    }

    #[test]
    fn edge_touching_boxes_do_not_collide() {
        let cs = vec![cand(0, 0, (0.0, 0.0, 50.0, 20.0)), cand(1, 0, (50.0, 0.0, 100.0, 20.0))];
        assert_eq!(place(&cs), vec![0, 1]);
    }

    #[test]
    fn empty_in_empty_out() {
        assert!(place(&[]).is_empty());
    }

    #[test]
    fn a_big_city_beats_a_town_at_the_same_collision() {
        // Same rank, overlapping boxes: population weight decides, so the
        // important place survives the cull.
        let town = Candidate { id: 1, rank: 2, pop: 0, rect: (0.0, 0.0, 100.0, 20.0) };
        let city = Candidate { id: 0, rank: 2, pop: 3, rect: (10.0, 0.0, 60.0, 20.0) };
        assert_eq!(place(&[town, city]), vec![0]);
    }

    #[test]
    fn an_unknown_rank_sinks_below_a_subplace() {
        let known =
            Candidate { id: 0, rank: 3, pop: 0, rect: (10.0, 0.0, 60.0, 20.0) };
        let unknown =
            Candidate { id: 1, rank: 255, pop: 3, rect: (0.0, 0.0, 100.0, 20.0) };
        assert_eq!(place(&[unknown, known]), vec![0]);
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
        // mid-screen and the box spans the advance at the frame's text size:
        // "Testland" shapes ~0.11 of an em... measured here via total_advance.
        let tile_clip = [
            2.0, 0.0, 0.0, 0.0, //
            0.0, 2.0, 0.0, 0.0, //
            0.0, 0.0, 1.0, 0.0, //
            -1.0, -1.0, 0.0, 1.0,
        ];
        // total_advance 1024 font units = half an em: 7px wide at 14px text.
        let (x0, y0, x1, y1) = screen_rect((0.5, 0.5), tile_clip, (256, 256), 14.0, 1024.0, 0.0);
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
        let plain = screen_rect((0.5, 0.5), tile_clip, (256, 256), 14.0, 1024.0, 0.0);
        let padded = screen_rect((0.5, 0.5), tile_clip, (256, 256), 14.0, 1024.0, 6.0);
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
        assert_eq!(place(&cs), vec![1, 0]);
    }

    /// Task-9 gating: low UI zoom keeps only high-pop localities, so z6 holds
    /// the major-city set; higher zooms relax to towns, then everything.
    #[test]
    fn locality_gating_thins_by_ui_zoom() {
        assert_eq!(locality_min_pop(6.0), 2, "z6: million-plus only");
        assert_eq!(locality_min_pop(6.9), 2);
        assert_eq!(locality_min_pop(7.0), 1, "z7: towns join");
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
}
