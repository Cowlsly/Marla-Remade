//! Where one section of road runs into another that carries a different number of lanes.
//!
//! # The defect
//!
//! [`crate::coalesce`]'s merge key includes `lane_count`, so a road whose lane count
//! changes arrives as two features. [`crate::tile::geometry`] gives each its own
//! [`CarriagewayMesh`](crate::tile::geometry::CarriagewayMesh) and
//! `Renderer::record_carriageways` pushes each a half-width of
//! `lane width x lanes / 2`. **A mesh has exactly one width.** So where a four-lane
//! section abuts a two-lane one the carriageway steps, and nothing connects the old
//! lanes to the new — a hard shoulder-step mid-road.
//!
//! # Why the fix is here and not in the tiler
//!
//! Because a mesh has exactly one width, no geometry the tiler could emit removes the
//! step; a transition feature would be drawn flat like every other and would only turn
//! one step into two. The tiler also knows nothing extra: `coalesce_lines_with_ids` runs
//! on an already-clipped, already-tile-local layer, which is the same view this module
//! has. And the one structure in the build that carries real topology — the v6 routing
//! graph behind the `junction` layer — collapses degree-2 chains, which is exactly what
//! a mid-block lane change is. It has already thrown the node away.
//!
//! # A width that belongs to the node, not to either section
//!
//! Two abutting ends could each taper to what they think the other is and still
//! disagree. So neither decides: the width where they meet is `min` of the two lane
//! counts, a function of the pair alone, and each section ramps to it over
//! [`TAPER_M`] of its own length. Both derive the same number from the same two inputs,
//! so a step is not detected and repaired — it cannot be expressed. The narrower section
//! computes a ratio of one and does not taper at all.
//!
//! Ends pair only when the pairing is *mutually* the straightest at that node, so a side
//! street ending at a junction never narrows the road it joins, and a road clipped at
//! the tile border pairs with nothing and keeps the width it has always had.

use std::collections::HashMap;

use crate::tess::ribbon::Taper;
use tilecodec::mamaps::body::{Body, GEOM_LINE};
use tilecodec::mamaps::dict::LAYER_ROADS;

/// How far back from a lane-count change the carriageway ramps to its new width, in
/// metres.
///
/// A ground length rather than a screen one, so the taper is the same stretch of road at
/// every zoom and the geometry stays a function of the tile. Roughly the merge taper a
/// 50 km/h urban road is signed for; a motorway's is longer, but this module has no
/// speed to scale by and a short taper reads as a transition where no taper reads as a
/// wall.
pub const TAPER_M: f64 = 40.0;

/// How straight two ends have to run into each other to be one road continuing.
///
/// The dot product of the two outward directions, which is `-1` for a perfectly straight
/// through-run. `-0.8` admits about 37 degrees of bend — enough for a lane drop on a
/// curve, far short of a side street.
const STRAIGHT_ENOUGH: f32 = -0.8;

/// The carriageway's lane count: the tagged total, or what an untagged road is worth.
///
/// **The one place this fallback is written.** Most roads carry no `lanes` tag at all,
/// and a zero would push a zero width and draw nothing; OSM's own reading of an untagged
/// road is one lane each way, which is also what makes the centre line appear. The
/// transition scan and the mesh that gets tapered have to agree on it exactly — two
/// layers disagreeing about how wide an untagged road is has caused more than one bug
/// here — so they call this rather than each carrying a copy.
pub fn carriageway_lanes(lane_count: u8, oneway: bool) -> u8 {
    match lane_count {
        0 => {
            if oneway {
                1
            } else {
                2
            }
        }
        count => count,
    }
}

/// The tile-local length of a [`TAPER_M`] ramp, in the `0..1` units [`Taper`] takes.
///
/// Zero for a tile with no ground width, which leaves every taper flat rather than
/// dividing by nothing.
pub fn taper_run(ground_width_m: f64) -> f32 {
    if ground_width_m > 0.0 {
        (TAPER_M / ground_width_m) as f32
    } else {
        0.0
    }
}

/// One end of one carriageway part, as the scan sees it.
#[derive(Debug, Clone, Copy)]
struct End {
    point: (i32, i32),
    /// The direction the road leaves this end in — *away* from its own geometry. Two
    /// ends that continue each other therefore point opposite ways.
    away: (f32, f32),
    lanes: u8,
}

/// Every point in this tile where the carriageway changes width, and what width it
/// changes to.
#[derive(Debug, Default, Clone)]
pub struct Nodes {
    lanes: HashMap<(i32, i32), u8>,
}

impl Nodes {
    /// Scan the tile's road layer for abutting sections of differing lane count.
    ///
    /// Roads only. A junction connector is one lane through an intersection by
    /// construction and is placed with a setback from the arms, so it neither needs a
    /// taper nor should contribute a width to one.
    pub fn scan(tile: &Body) -> Nodes {
        let Some(source) = tile.layer(LAYER_ROADS) else { return Nodes::default() };
        let mut ends: Vec<End> = Vec::new();
        for feature in &source.features {
            if feature.geom_type != GEOM_LINE {
                continue;
            }
            let lanes = carriageway_lanes(feature.lane_count, feature.is_oneway());
            for part in source.parts_of(feature) {
                ends.extend(part_ends(source.points(part), lanes));
            }
        }
        Nodes { lanes: transitions(&ends) }
    }

    /// The taper for one part of a `lanes`-wide section, given a ramp length from
    /// [`taper_run`].
    ///
    /// `lanes` must be the count the mesh is drawn at — [`carriageway_lanes`], not the
    /// raw tag — or the ratio is against a width the renderer never uses.
    pub fn taper(&self, coords: &[i32], lanes: u8, run: f32) -> Taper {
        if self.lanes.is_empty() || coords.len() < 4 || lanes == 0 {
            return Taper::NONE;
        }
        let last = coords.len() / 2 - 1;
        let ratio = |point: (i32, i32)| match self.lanes.get(&point) {
            Some(&node) if node < lanes => f32::from(node) / f32::from(lanes),
            _ => 1.0,
        };
        Taper::new(
            ratio((coords[0], coords[1])),
            ratio((coords[last * 2], coords[last * 2 + 1])),
            run,
        )
    }
}

/// The two ends of one part, or nothing when it has no two distinct points to take a
/// direction from.
///
/// Takes the tile's own `(i16, i16)` points rather than the flat form the tessellator
/// uses, so scanning a tile costs no allocation per part.
fn part_ends(points: &[(i16, i16)], lanes: u8) -> Vec<End> {
    let n = points.len();
    if n < 2 {
        return Vec::new();
    }
    let at = |i: usize| (i32::from(points[i].0), i32::from(points[i].1));
    let first = at(0);
    let last = at(n - 1);
    // The nearest point that is not coincident, so a repeated coordinate — which the
    // simplifier does leave behind — does not produce a zero direction.
    let after_first = (1..n).map(at).find(|&p| p != first);
    let before_last = (0..n - 1).rev().map(at).find(|&p| p != last);
    match (after_first, before_last) {
        (Some(second), Some(penultimate)) => vec![
            End { point: first, away: direction(second, first), lanes },
            End { point: last, away: direction(penultimate, last), lanes },
        ],
        _ => Vec::new(),
    }
}

/// The unit direction from `from` to `to`, or zero when they coincide — which
/// [`part_ends`] has already excluded, so a zero here simply never pairs.
fn direction(from: (i32, i32), to: (i32, i32)) -> (f32, f32) {
    let (dx, dy) = ((to.0 - from.0) as f32, (to.1 - from.1) as f32);
    let length = (dx * dx + dy * dy).sqrt();
    if length > 0.0 {
        (dx / length, dy / length)
    } else {
        (0.0, 0.0)
    }
}

/// Reduce a tile's part ends to the points where the carriageway changes width.
///
/// At each point the straightest *pair* of ends is the road continuing through it; every
/// other end there belongs to something joining, not continuing. Taking the single
/// global minimum makes the pair mutual for free — the two ends of the straightest pair
/// are each other's straightest — which is what stops two sections deriving different
/// partners and so different widths.
fn transitions(ends: &[End]) -> HashMap<(i32, i32), u8> {
    let mut at_point: HashMap<(i32, i32), Vec<usize>> = HashMap::new();
    for (i, end) in ends.iter().enumerate() {
        at_point.entry(end.point).or_default().push(i);
    }

    let mut out = HashMap::new();
    for (point, here) in at_point {
        if here.len() < 2 {
            continue;
        }
        let mut best: Option<(f32, u8, u8)> = None;
        for (rank, &a) in here.iter().enumerate() {
            for &b in &here[rank + 1..] {
                let (one, two) = (ends[a], ends[b]);
                let dot = one.away.0 * two.away.0 + one.away.1 * two.away.1;
                if dot > STRAIGHT_ENOUGH {
                    continue;
                }
                if best.is_none_or(|(previous, _, _)| dot < previous) {
                    best = Some((dot, one.lanes, two.lanes));
                }
            }
        }
        // Equal counts are the same road merely cut in two — there is nothing to ramp
        // between, and recording it would make every part of every road taper to itself.
        if let Some((_, one, two)) = best {
            if one != two {
                out.insert(point, one.min(two));
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A straight run of `lanes` lanes from `from` to `to`, as a part's two ends.
    fn section(from: (i16, i16), to: (i16, i16), lanes: u8) -> Vec<End> {
        part_ends(&[from, to], lanes)
    }

    fn scanned(sections: &[Vec<End>]) -> HashMap<(i32, i32), u8> {
        transitions(&sections.iter().flatten().copied().collect::<Vec<_>>())
    }

    #[test]
    fn the_lane_fallback_matches_the_width_the_carriageway_is_drawn_at() {
        // Pinned against `tile/geometry.rs`'s carriageway branch. If these two ever
        // disagree the taper ramps to a width the renderer does not draw.
        assert_eq!(carriageway_lanes(0, false), 2, "untagged two-way is one lane each way");
        assert_eq!(carriageway_lanes(0, true), 1, "untagged one-way is one lane");
        assert_eq!(carriageway_lanes(4, false), 4, "a tagged count wins");
        assert_eq!(carriageway_lanes(1, true), 1);
        assert_eq!(carriageway_lanes(255, false), 255);
    }

    #[test]
    fn two_sections_of_differing_width_transition_at_the_point_they_share() {
        let found = scanned(&[
            section((0, 0), (100, 0), 4),
            section((100, 0), (200, 0), 2),
        ]);
        assert_eq!(found.get(&(100, 0)), Some(&2), "the narrower of the two");
        assert_eq!(found.len(), 1, "and nothing at the far ends: {found:?}");
    }

    #[test]
    fn both_sections_derive_the_same_width_whichever_side_asks() {
        // The property that makes a step unrepresentable rather than merely corrected,
        // asserted the way the defect is seen: as two kerbs that do or do not meet.
        let nodes = Nodes { lanes: scanned(&[
            section((0, 0), (1000, 0), 4),
            section((1000, 0), (2000, 0), 2),
        ]) };
        let run = 0.2;
        let wide = kerbs(&[0, 0, 1000, 0], nodes.taper(&[0, 0, 1000, 0], 4, run), 4);
        let narrow =
            kerbs(&[1000, 0, 2000, 0], nodes.taper(&[1000, 0, 2000, 0], 2, run), 2);

        // Where they meet: the wide section's last pair against the narrow one's first.
        let (wide_left, wide_right) = wide[wide.len() - 1];
        let (narrow_left, narrow_right) = narrow[0];
        assert!((wide_left - narrow_left).abs() < 1e-6, "{wide_left} vs {narrow_left}");
        assert!((wide_right - narrow_right).abs() < 1e-6, "{wide_right} vs {narrow_right}");
        // And it is the narrow road's width they agree on, not some average: two lanes
        // of the half-lane-each-side offset the renderer pushes.
        assert!((wide_right - wide_left - 2.0 * LANE).abs() < 1e-6);

        // The defect itself, for contrast: without the taper the wide section ends two
        // lanes wider and that difference is the shoulder-step seen on the device.
        let stepped = kerbs(&[0, 0, 1000, 0], Taper::NONE, 4);
        let (stepped_left, stepped_right) = stepped[stepped.len() - 1];
        assert!(
            (stepped_right - stepped_left) - (narrow_right - narrow_left) > LANE,
            "the untapered end should be at least a lane wider",
        );
    }

    /// One lane's half-width in tile-local units, standing in for the style ramp.
    const LANE: f32 = 0.01;

    /// The two kerbs at each point of a tessellated part, in tile-local units.
    ///
    /// Reproduces what actually reaches a pixel: `record_carriageways` pushes
    /// `lane width x lanes / 2` and `road_surface.vert` offsets by
    /// `normal * t * halfWidth`. Asserting on this rather than on a ratio is what makes
    /// these tests fail if the renderer and the taper ever stop agreeing.
    fn kerbs(coords: &[i32], taper: Taper, lanes: u8) -> Vec<(f32, f32)> {
        use crate::tess::ribbon::{ribbon_tapered, FLOATS_PER_VERTEX};
        let half_width = LANE * f32::from(lanes) / 2.0;
        let mut v = Vec::new();
        ribbon_tapered(coords, 1000, taper, &mut v, &mut Vec::new());
        (0..v.len() / FLOATS_PER_VERTEX / 2)
            .map(|i| {
                let at = |vertex: usize| {
                    let f = vertex * FLOATS_PER_VERTEX;
                    // The y offset, because these fixtures all run east and the kerbs
                    // are therefore north and south of the centreline.
                    v[f + 1] + v[f + 3] * v[f + 4] * half_width
                };
                (at(i * 2), at(i * 2 + 1))
            })
            .collect()
    }

    /// How wide a tapered part is at each end, in lanes.
    fn end_lanes(taper: Taper, lanes: u8) -> (f32, f32) {
        let widths = kerbs(&[0, 0, 1000, 0], taper, lanes);
        let span = |(left, right): (f32, f32)| (right - left) / LANE;
        (span(widths[0]), span(widths[widths.len() - 1]))
    }

    #[test]
    fn equal_lane_counts_are_not_a_transition() {
        // A road cut in two by something that is not a lane change — a bridge, a surface
        // change — must not pinch where the pieces meet.
        let found = scanned(&[
            section((0, 0), (100, 0), 3),
            section((100, 0), (200, 0), 3),
        ]);
        assert!(found.is_empty(), "{found:?}");
    }

    #[test]
    fn a_side_street_never_narrows_the_road_it_joins() {
        // Three ends at one point: the road continuing straight through, and a two-lane
        // street arriving at right angles. The straight pair wins, and it agrees, so
        // there is no transition at all.
        let found = scanned(&[
            section((0, 0), (100, 0), 4),
            section((100, 0), (200, 0), 4),
            section((100, 100), (100, 0), 2),
        ]);
        assert!(found.is_empty(), "the side street is not the through pair: {found:?}");
    }

    #[test]
    fn a_side_street_does_not_decide_a_transition_between_two_others() {
        // Same junction, but the through road does change width. The transition is
        // between the two straight ends, and the side street's narrower count is
        // ignored rather than taken as the minimum.
        let found = scanned(&[
            section((0, 0), (100, 0), 6),
            section((100, 0), (200, 0), 4),
            section((100, 100), (100, 0), 1),
        ]);
        assert_eq!(found.get(&(100, 0)), Some(&4), "min of the through pair, not of all three");
    }

    #[test]
    fn ends_that_are_not_straight_enough_do_not_pair() {
        // A sharp fork rather than a road continuing: 90 degrees apart, well past the
        // bend a lane drop can have.
        let found = scanned(&[
            section((0, 0), (100, 0), 4),
            section((100, 0), (100, 100), 2),
        ]);
        assert!(found.is_empty(), "{found:?}");
    }

    #[test]
    fn a_gentle_bend_still_pairs() {
        // A lane drop on a curve is a real thing and must not be missed for being a few
        // degrees off straight.
        let found = scanned(&[
            section((0, 0), (1000, 0), 4),
            section((1000, 0), (1990, 140), 2),
        ]);
        assert_eq!(found.get(&(1000, 0)), Some(&2), "about 8 degrees of bend: {found:?}");
    }

    #[test]
    fn a_part_that_touches_nothing_gets_no_taper() {
        let nodes = Nodes { lanes: scanned(&[section((0, 0), (100, 0), 4)]) };
        assert_eq!(nodes.taper(&[0, 0, 100, 0], 4, 0.2), Taper::NONE);
    }

    #[test]
    fn a_road_clipped_at_the_tile_border_keeps_the_width_it_has() {
        // Only one section is in this tile, so its far end pairs with nothing. It must
        // draw exactly as it does today rather than tapering into the seam.
        let nodes = Nodes { lanes: scanned(&[
            section((0, 0), (100, 0), 4),
            section((100, 0), (200, 0), 2),
        ]) };
        let clipped = nodes.taper(&[200, 0, 400, 0], 2, 0.2);
        assert_eq!(clipped, Taper::NONE, "neither end of it is a transition");
    }

    #[test]
    fn the_wider_section_tapers_and_the_narrower_one_does_not() {
        let nodes = Nodes { lanes: scanned(&[
            section((0, 0), (1000, 0), 4),
            section((1000, 0), (2000, 0), 2),
        ]) };
        assert_ne!(nodes.taper(&[0, 0, 1000, 0], 4, 0.2), Taper::NONE, "the wide one ramps");
        assert_eq!(
            nodes.taper(&[1000, 0, 2000, 0], 2, 0.2),
            Taper::NONE,
            "the narrow one is already the node's width",
        );
    }

    #[test]
    fn a_section_narrower_at_both_ends_tapers_at_both() {
        let nodes = Nodes { lanes: scanned(&[
            section((0, 0), (100, 0), 2),
            section((100, 0), (200, 0), 6),
            section((200, 0), (300, 0), 3),
        ]) };
        let middle = nodes.taper(&[100, 0, 200, 0], 6, 0.2);
        let (start, end) = end_lanes(middle, 6);
        assert!((start - 2.0).abs() < 1e-5, "two lanes at the start: {start}");
        assert!((end - 3.0).abs() < 1e-5, "three at the end: {end}");
    }

    #[test]
    fn a_ground_length_ramp_is_the_same_road_at_every_tile_size() {
        // A z14 tile is about 1900 m across at San Francisco's latitude and a z16 one a
        // quarter of that, so the tile-local run has to be four times as long there for
        // the taper to be the same stretch of tarmac.
        let coarse = taper_run(1900.0);
        let fine = taper_run(475.0);
        assert!((fine / coarse - 4.0).abs() < 1e-3, "{fine} vs {coarse}");
        assert!((coarse - (TAPER_M / 1900.0) as f32).abs() < 1e-9);
        assert_eq!(taper_run(0.0), 0.0, "a tile with no ground width cannot ramp");
        assert_eq!(taper_run(-1.0), 0.0);
    }

    #[test]
    fn a_degenerate_part_contributes_no_ends() {
        assert!(part_ends(&[], 4).is_empty());
        assert!(part_ends(&[(5, 5)], 4).is_empty());
        assert!(part_ends(&[(5, 5), (5, 5)], 4).is_empty(), "coincident points have no direction");
        assert_eq!(part_ends(&[(5, 5), (5, 5), (9, 5)], 4).len(), 2, "a repeat is stepped over");
    }

    #[test]
    fn a_repeated_coordinate_does_not_break_the_direction() {
        // The simplifier leaves these behind and `tess::ribbon::dedupe` drops them, so
        // the scan has to step over them too or a real transition is missed.
        let ends = part_ends(&[(0, 0), (0, 0), (100, 0)], 4);
        assert_eq!(ends.len(), 2);
        assert!((ends[0].away.0 + 1.0).abs() < 1e-6, "leaving the start heading west");
        assert!((ends[1].away.0 - 1.0).abs() < 1e-6, "leaving the end heading east");
    }
}
