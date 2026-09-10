//! Per-lane turn arrows: turning an OSM `turn:lanes` mask into a drawable arrow, and placing one
//! arrow per lane at a road's junction end.
//!
//! The data half of this lives in the archive (`tilecodec::mamaps::body::LaneTurns`, one `LANE_*`
//! mask per lane, forward and backward); this is the render half. It is deliberately split into two
//! pure, testable pieces — [`arrow_for`] (which glyph a mask draws) and [`place_arrows`] (where the
//! arrows sit and which way they point) — so the choice of glyph and the placement geometry are
//! unit tests rather than screenshots. The GPU draw that turns an [`ArrowInstance`] into pixels is
//! the renderer's job.

use tilecodec::mamaps::body::{
    LaneTurns, LANE_LEFT, LANE_MERGE_TO_LEFT, LANE_MERGE_TO_RIGHT, LANE_REVERSE, LANE_RIGHT,
    LANE_SHARP_LEFT, LANE_SHARP_RIGHT, LANE_SLIGHT_LEFT, LANE_SLIGHT_RIGHT, LANE_THROUGH,
};

/// The arrow a single lane draws, chosen from its `turn:lanes` indication set.
///
/// A lane may carry several indications at once (`through;right` is common), so the mask is not one
/// of these — [`arrow_for`] reduces the set to the single glyph to draw. `Reverse` is a U-turn;
/// `MergeLeft`/`MergeRight` are the tapering-lane markers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TurnArrow {
    Through,
    SlightLeft,
    Left,
    SharpLeft,
    SlightRight,
    Right,
    SharpRight,
    Reverse,
    MergeLeft,
    MergeRight,
}

/// The arrow to draw for one lane's `LANE_*` mask, or `None` when the lane has no indication
/// (`LANE_NONE`, or an empty/unknown mask).
///
/// A lane often carries several indications; a single arrow has to be picked. The rule, highest
/// priority first, is **straightest-and-least-surprising wins**: a lane you can go straight through
/// is drawn as a through arrow even if it also allows a turn, because that is the movement a driver
/// defaults to; failing that the sharper, more committing turns are preferred over their slight
/// variants so the arrow does not under-state the manoeuvre; merges and U-turns come last as they
/// are the rarest and never co-occur with a through in real data. The ranking is total, so the
/// choice is deterministic for any mask.
pub fn arrow_for(mask: u16) -> Option<TurnArrow> {
    // In priority order; the first bit present wins.
    const RANK: &[(u16, TurnArrow)] = &[
        (LANE_THROUGH, TurnArrow::Through),
        (LANE_LEFT, TurnArrow::Left),
        (LANE_RIGHT, TurnArrow::Right),
        (LANE_SHARP_LEFT, TurnArrow::SharpLeft),
        (LANE_SHARP_RIGHT, TurnArrow::SharpRight),
        (LANE_SLIGHT_LEFT, TurnArrow::SlightLeft),
        (LANE_SLIGHT_RIGHT, TurnArrow::SlightRight),
        (LANE_MERGE_TO_LEFT, TurnArrow::MergeLeft),
        (LANE_MERGE_TO_RIGHT, TurnArrow::MergeRight),
        (LANE_REVERSE, TurnArrow::Reverse),
    ];
    RANK.iter().find(|(bit, _)| mask & bit != 0).map(|(_, arrow)| *arrow)
}

/// One arrow to draw for one lane, in tile-local coordinates.
///
/// The anchor sits on the road's centreline a short way back from the junction; the renderer then
/// pushes it sideways onto its lane of the carriageway, which is why the instance carries `ordinal`
/// and `count` rather than a baked offset (the offset is a screen measurement that changes with
/// zoom). `angle` is the road's heading at the end, in radians, so the glyph points the way the
/// traffic flows.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ArrowInstance {
    /// Anchor on the centreline, tile-local (extent units).
    pub anchor: (f32, f32),
    /// Heading of travel at the junction, radians (atan2(dy, dx)).
    pub angle: f32,
    /// This lane's index from the left, and the lane count, for the lateral fan.
    pub ordinal: u8,
    pub count: u8,
    /// The glyph to draw.
    pub arrow: TurnArrow,
}

/// How far back from the junction the arrows sit, as a fraction of the road's final segment.
///
/// Placed a little inside the tile rather than exactly on the node so the whole arrow — which draws
/// ahead of its anchor — stays on the carriageway instead of overhanging the junction.
const SETBACK: f32 = 0.5;

/// Place one arrow per lane for a road's turn masks, in tile-local coordinates.
///
/// `line` is the road's centreline (extent units). Forward lanes are placed at the **last** point
/// (the way's junction end) heading toward it; backward lanes at the **first** point heading toward
/// it (i.e. back down the way). A lane whose mask has no indication draws nothing. The `ordinal`
/// runs left to right in the direction of travel, matching the mask order the archive stores.
///
/// Returns an empty vec for a line too short to have a heading, so a degenerate clip draws nothing
/// rather than an arrow pointing nowhere.
pub fn place_arrows(line: &[(f32, f32)], turns: &LaneTurns) -> Vec<ArrowInstance> {
    let mut out = Vec::new();
    if line.len() < 2 {
        return out;
    }
    place_dir(line, &turns.forward, false, &mut out);
    place_dir(line, &turns.backward, true, &mut out);
    out
}

/// One direction's arrows. `backward` reverses which end and which way the heading points.
fn place_dir(line: &[(f32, f32)], masks: &[u16], backward: bool, out: &mut Vec<ArrowInstance>) {
    if masks.is_empty() {
        return;
    }
    // The junction node and the point just before it along the direction of travel.
    let (tip, prev) = if backward {
        (line[0], line[1])
    } else {
        (line[line.len() - 1], line[line.len() - 2])
    };
    let (dx, dy) = (tip.0 - prev.0, tip.1 - prev.1);
    if dx == 0.0 && dy == 0.0 {
        return;
    }
    let angle = dy.atan2(dx);
    let anchor = (tip.0 - dx * SETBACK, tip.1 - dy * SETBACK);
    let count = masks.len().min(u8::MAX as usize) as u8;
    for (i, &mask) in masks.iter().enumerate().take(u8::MAX as usize) {
        if let Some(arrow) = arrow_for(mask) {
            out.push(ArrowInstance { anchor, angle, ordinal: i as u8, count, arrow });
        }
    }
}

/// The extra rotation an arrow's glyph takes on top of the road heading, in radians, so the arrow
/// points where the manoeuvre leads rather than straight down the lane.
///
/// A single straight-arrow shape ([`unit_arrow_triangles`]) is rotated by `heading + turn_offset`
/// rather than carrying a distinct shape per indication — far less geometry and impossible to get
/// subtly wrong per variant. Left turns are **negative** (tile-space `y` grows southward, so the
/// left of an eastbound road is `-y`, reached by a negative rotation); right turns positive. Through
/// is straight, reverse is a half turn, and the merges lean like their slight cousins.
pub fn turn_offset(arrow: TurnArrow) -> f32 {
    use std::f32::consts::PI;
    match arrow {
        TurnArrow::Through => 0.0,
        TurnArrow::SlightLeft => -PI / 6.0,
        TurnArrow::Left => -PI / 2.0,
        TurnArrow::SharpLeft => -3.0 * PI / 4.0,
        TurnArrow::SlightRight => PI / 6.0,
        TurnArrow::Right => PI / 2.0,
        TurnArrow::SharpRight => 3.0 * PI / 4.0,
        TurnArrow::Reverse => PI,
        TurnArrow::MergeLeft => -PI / 6.0,
        TurnArrow::MergeRight => PI / 6.0,
    }
}

/// A straight arrow pointing along `+x`, as a triangle list in unit coordinates (roughly `-1..1`).
///
/// Three triangles — a rectangular shaft and a triangular head — nine vertices. The renderer
/// rotates, scales to a screen size and positions each copy; keeping the shape here (and pure)
/// makes it a unit test rather than a screenshot. Vertices are `(x, y)` pairs, three per triangle.
pub const UNIT_ARROW_TRIANGLES: [(f32, f32); 9] = [
    // Shaft, two triangles.
    (-0.8, -0.15),
    (0.2, -0.15),
    (0.2, 0.15),
    (-0.8, -0.15),
    (0.2, 0.15),
    (-0.8, 0.15),
    // Head, one triangle, tip at +x.
    (0.2, -0.4),
    (1.0, 0.0),
    (0.2, 0.4),
];

/// A straight arrow pointing along `+x`, as a triangle list in unit coordinates (roughly `-1..1`).
///
/// A borrow of [`UNIT_ARROW_TRIANGLES`]. It used to build a fresh `Vec` per call, and
/// [`arrow_verts`] calls it once **per arrow per frame**, so a junction-dense tile heap-allocated
/// and freed a nine-element vector hundreds of times a frame for a constant.
pub fn unit_arrow_triangles() -> &'static [(f32, f32)] {
    &UNIT_ARROW_TRIANGLES
}

/// Transform the unit arrow into tile-local triangle vertices for one placed arrow, appending
/// `x, y` pairs (three per triangle) to `out`.
///
/// `scale` is tile-local units per unit-arrow coordinate (the arrow's screen size ÷ the tile's
/// screen span); `lateral` is the sideways lane offset in the same tile-local units, applied
/// perpendicular to the **road heading** so each lane's arrow sits over its lane; the glyph is
/// rotated by `heading + turn_offset(arrow)` so it points where the lane leads. This is the exact
/// math the renderer runs per frame, factored out so it is a unit test rather than a screenshot.
pub fn arrow_verts(inst: &ArrowInstance, scale: f32, lateral: f32, out: &mut Vec<f32>) {
    // Perpendicular to the road heading (left is -y in tile space), for the lane offset.
    let (rc, rs) = (inst.angle.cos(), inst.angle.sin());
    let (cx, cy) = (inst.anchor.0 - rs * lateral, inst.anchor.1 + rc * lateral);
    let glyph = inst.angle + turn_offset(inst.arrow);
    let (gc, gs) = (glyph.cos(), glyph.sin());
    for &(ux, uy) in unit_arrow_triangles() {
        let rx = ux * gc - uy * gs;
        let ry = ux * gs + uy * gc;
        out.push(cx + rx * scale);
        out.push(cy + ry * scale);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The glyph rule is total and prefers a through movement, then the committing turn over its
    /// slight variant. A lane with no indication draws nothing.
    #[test]
    fn the_arrow_rule_prefers_through_then_the_sharper_turn() {
        assert_eq!(arrow_for(LANE_THROUGH), Some(TurnArrow::Through));
        assert_eq!(arrow_for(LANE_LEFT), Some(TurnArrow::Left));
        // `through;right` is a through arrow: that is the default movement.
        assert_eq!(arrow_for(LANE_THROUGH | LANE_RIGHT), Some(TurnArrow::Through));
        // A committing turn beats its slight variant when both are somehow set.
        assert_eq!(arrow_for(LANE_LEFT | LANE_SLIGHT_LEFT), Some(TurnArrow::Left));
        assert_eq!(arrow_for(LANE_REVERSE), Some(TurnArrow::Reverse));
        assert_eq!(arrow_for(tilecodec::mamaps::body::LANE_NONE), None, "no indication, no arrow");
        assert_eq!(arrow_for(0), None, "an empty mask draws nothing");
    }

    /// Forward lanes anchor near the way's end heading toward it, one arrow per marked lane, in
    /// left-to-right ordinal order; a lane with no indication is skipped but does not shift the
    /// others' ordinals.
    #[test]
    fn forward_arrows_sit_at_the_end_and_point_along_the_road() {
        // A straight eastbound road: heading is 0 radians, end at x=100.
        let line = [(0.0, 0.0), (50.0, 0.0), (100.0, 0.0)];
        let turns = LaneTurns {
            forward: vec![LANE_LEFT, tilecodec::mamaps::body::LANE_NONE, LANE_THROUGH | LANE_RIGHT],
            backward: vec![],
        };
        let arrows = place_arrows(&line, &turns);
        assert_eq!(arrows.len(), 2, "the unmarked middle lane draws nothing");
        assert!(arrows.iter().all(|a| a.angle.abs() < 1e-6), "eastbound heading is 0");
        assert!(arrows.iter().all(|a| a.count == 3), "the count is the whole lane set");
        assert_eq!((arrows[0].ordinal, arrows[0].arrow), (0, TurnArrow::Left));
        assert_eq!((arrows[1].ordinal, arrows[1].arrow), (2, TurnArrow::Through), "ordinal kept");
        // Anchored back from the junction, not on it.
        assert!(arrows[0].anchor.0 < 100.0 && arrows[0].anchor.0 > 50.0);
    }

    /// Backward lanes anchor at the other end and point the other way.
    #[test]
    fn backward_arrows_point_down_the_way_from_its_start() {
        let line = [(0.0, 0.0), (100.0, 0.0)];
        let turns = LaneTurns { forward: vec![], backward: vec![LANE_THROUGH] };
        let arrows = place_arrows(&line, &turns);
        assert_eq!(arrows.len(), 1);
        // Heading toward the start of an eastbound way is due west: pi radians.
        assert!((arrows[0].angle.abs() - std::f32::consts::PI).abs() < 1e-6);
        assert!(arrows[0].anchor.0 > 0.0 && arrows[0].anchor.0 < 100.0);
    }

    /// A line too short to have a heading draws nothing rather than an arrow pointing nowhere.
    #[test]
    fn a_degenerate_line_places_no_arrows() {
        let turns = LaneTurns { forward: vec![LANE_THROUGH], backward: vec![] };
        assert!(place_arrows(&[(1.0, 1.0)], &turns).is_empty());
        assert!(place_arrows(&[], &turns).is_empty());
        // Two coincident points have no direction.
        assert!(place_arrows(&[(5.0, 5.0), (5.0, 5.0)], &turns).is_empty());
    }

    /// The glyph shape is a whole number of triangles, and the turn offsets point left negative /
    /// right positive with through straight — the sign convention the renderer rotates by.
    #[test]
    fn the_arrow_glyph_is_triangles_and_turns_have_the_right_sign() {
        let tris = unit_arrow_triangles();
        assert_eq!(tris.len() % 3, 0, "a triangle list is a multiple of three vertices");
        assert!(!tris.is_empty());
        // The head reaches furthest in +x, so the arrow points along its local +x axis.
        let max_x = tris.iter().fold(f32::MIN, |m, &(x, _)| m.max(x));
        assert!((max_x - 1.0).abs() < 1e-6, "the tip is at +x");
        assert_eq!(turn_offset(TurnArrow::Through), 0.0);
        assert!(turn_offset(TurnArrow::Left) < 0.0, "left is a negative rotation");
        assert!(turn_offset(TurnArrow::Right) > 0.0, "right is a positive rotation");
        assert!(turn_offset(TurnArrow::SharpLeft) < turn_offset(TurnArrow::Left), "sharper is more");
        assert!(turn_offset(TurnArrow::SlightLeft) > turn_offset(TurnArrow::Left), "slighter is less");
        assert!((turn_offset(TurnArrow::Reverse).abs() - std::f32::consts::PI).abs() < 1e-6);
    }

    /// The renderer's per-arrow transform: a through arrow points along the road (its tip furthest
    /// along +heading), the lane offset shifts it perpendicular to the heading, and a left arrow's
    /// tip lands to the left of the road.
    #[test]
    fn arrow_verts_rotate_scale_and_offset_into_the_lane() {
        // Eastbound through arrow at tile-centre, no lane offset: tip furthest in +x.
        let through = ArrowInstance {
            anchor: (0.5, 0.5),
            angle: 0.0,
            ordinal: 0,
            count: 1,
            arrow: TurnArrow::Through,
        };
        let mut v = Vec::new();
        arrow_verts(&through, 0.1, 0.0, &mut v);
        assert_eq!(v.len(), unit_arrow_triangles().len() * 2);
        let max_x = v.chunks(2).map(|p| p[0]).fold(f32::MIN, f32::max);
        let tip_y = v.chunks(2).max_by(|a, b| a[0].total_cmp(&b[0])).unwrap()[1];
        assert!((max_x - 0.6).abs() < 1e-5, "tip a scale-length ahead of the anchor in +x");
        assert!((tip_y - 0.5).abs() < 1e-5, "and level with it, no offset");
        // A non-zero lateral offset shifts the whole glyph off the centreline (perpendicular to
        // the heading); the sign of the real offset comes from the lane's place across the road.
        let mut v2 = Vec::new();
        arrow_verts(&through, 0.1, 0.2, &mut v2);
        let mean_y2 = v2.chunks(2).map(|p| p[1]).sum::<f32>() / (v2.len() / 2) as f32;
        assert!((mean_y2 - 0.5).abs() > 0.1, "the lane offset moves the arrow off the centreline");
        // A left arrow on an eastbound road points north (-y): its tip is above the anchor.
        let left = ArrowInstance { arrow: TurnArrow::Left, ..through };
        let mut v3 = Vec::new();
        arrow_verts(&left, 0.1, 0.0, &mut v3);
        let tip = v3.chunks(2).min_by(|a, b| a[1].total_cmp(&b[1])).unwrap();
        assert!(tip[1] < 0.5, "a left turn's tip is north of the anchor");
    }
}
