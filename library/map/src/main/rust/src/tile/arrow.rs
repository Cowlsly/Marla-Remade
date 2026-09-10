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
/// pushes it sideways onto its lane of the carriageway, which is why the instance carries
/// `ordinal`, `count` and `fan_offset` rather than a baked offset (the offset is a screen
/// measurement that changes with zoom). `angle` is the road's heading at the end, in radians, so
/// the glyph points the way the traffic flows.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ArrowInstance {
    /// Anchor on the centreline, tile-local (extent units).
    pub anchor: (f32, f32),
    /// Heading of travel at the junction, radians (atan2(dy, dx)).
    pub angle: f32,
    /// This lane's index from the left, and the lane count, for the lateral fan.
    pub ordinal: u8,
    pub count: u8,
    /// The shift that puts this direction's fan on its own half of the carriageway, in lane
    /// widths, positive to the right of travel — see [`fan_offset`]. Zero on a one-way and
    /// wherever the road's total lane count is unknown, which keeps the fan centred.
    pub fan_offset: f32,
    /// The glyph to draw.
    pub arrow: TurnArrow,
    /// Absolute heading of the exit this lane leads to, radians, when the archive knows it.
    ///
    /// The angle the glyph actually bends through is `exit_angle - angle`, which is whatever the
    /// two roads happen to meet at. `None` means the archive carries only the `LANE_*` class and
    /// no geometry, and the glyph falls back to [`nominal_turn_angle`]; see [`turn_angle`].
    pub exit_angle: Option<f32>,
    /// Traffic keeps left on this tile, which is the side a U-turn is made from — see
    /// [`nominal_turn_angle`], the only angle the convention changes.
    pub left_hand: bool,
}

/// How far back from the junction the arrows sit, as a fraction of the road's final segment.
///
/// Placed a little inside the tile rather than exactly on the node so the whole arrow — which draws
/// ahead of its anchor — stays on the carriageway instead of overhanging the junction.
///
/// Must stay well under `0.5`. At exactly a half the forward and backward anchors are both the
/// midpoint of a two-point line, so the two directions' arrows land on the same spot pointing
/// opposite ways and draw as a single shaft with a head at each end.
const SETBACK: f32 = 0.15;

/// Place one arrow per lane for a road's turn masks, in tile-local coordinates.
///
/// `line` is the road's centreline (extent units). Forward lanes are placed at the **last** point
/// (the way's junction end) heading toward it; backward lanes at the **first** point heading toward
/// it (i.e. back down the way). A lane whose mask has no indication draws nothing. The `ordinal`
/// runs left to right in the direction of travel, matching the mask order the archive stores.
///
/// `total_lanes` is the road's whole lane count (both directions) and `left_hand` its driving
/// convention; together they decide which half of the carriageway a direction's fan sits on, via
/// [`fan_offset`]. `total_lanes` of zero means the road carries no `lanes` tag.
///
/// Returns an empty vec for a line too short to have a heading, so a degenerate clip draws nothing
/// rather than an arrow pointing nowhere.
pub fn place_arrows(
    line: &[(f32, f32)],
    turns: &LaneTurns,
    total_lanes: u8,
    left_hand: bool,
) -> Vec<ArrowInstance> {
    let mut out = Vec::new();
    if line.len() < 2 {
        return out;
    }
    place_dir(line, &turns.forward, false, total_lanes, left_hand, &mut out);
    place_dir(line, &turns.backward, true, total_lanes, left_hand, &mut out);
    out
}

/// How far this direction's fan sits from the road's centreline, in lane widths, positive to the
/// right of the direction of travel.
///
/// A direction's lanes occupy one *half* of the carriageway, not the middle of it: under right-hand
/// traffic they are the rightmost `count` of the road's `total_lanes` and under left-hand traffic
/// the leftmost. [`place_arrows`] builds the fan centred on the road's centreline, so this is the
/// shift that moves it onto that half — half the lanes the other direction takes, signed by the
/// convention. Without it every arrow on a two-way road is drawn a full half-carriageway into the
/// oncoming lanes, in either convention.
///
/// Zero, and so no shift at all, whenever the direction *is* the whole road: a one-way carries
/// every lane, and a road whose `lanes` tag is missing or disagrees with the mask list has no
/// trustworthy total to measure a half from. Both keep the centred fan, which is where every arrow
/// sat before the convention reached this pass.
pub fn fan_offset(count: u8, total_lanes: u8, left_hand: bool) -> f32 {
    let spare = f32::from(total_lanes.saturating_sub(count)) / 2.0;
    if left_hand {
        -spare
    } else {
        spare
    }
}

/// Where this arrow's lane sits across the road, in lane widths from the centreline, positive to
/// the right of the direction of travel.
///
/// The fan — `ordinal + 0.5 - count / 2` — spreads a direction's lanes about its own centre, and
/// [`fan_offset`] moves that centre onto the half of the carriageway the direction occupies.
/// Multiplying by one lane's width in device pixels is the whole of the renderer's lateral
/// placement, which is why it lives here as a pure function rather than in the Android-only pass.
pub fn lane_centre(inst: &ArrowInstance) -> f32 {
    let count = f32::from(inst.count.max(1));
    f32::from(inst.ordinal) + 0.5 - count / 2.0 + inst.fan_offset
}

/// One direction's arrows. `backward` reverses which end and which way the heading points.
fn place_dir(
    line: &[(f32, f32)],
    masks: &[u16],
    backward: bool,
    total_lanes: u8,
    left_hand: bool,
    out: &mut Vec<ArrowInstance>,
) {
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
    let fan_offset = fan_offset(count, total_lanes, left_hand);
    for (i, &mask) in masks.iter().enumerate().take(u8::MAX as usize) {
        if let Some(arrow) = arrow_for(mask) {
            let ordinal = i as u8;
            out.push(ArrowInstance {
                anchor,
                angle,
                ordinal,
                count,
                arrow,
                exit_angle: None,
                fan_offset,
                left_hand,
            });
        }
    }
}

/// The angle the glyph bends through for an indication, in radians, when nothing better is known.
///
/// Left turns are **negative** (tile-space `y` grows southward, so the left of an eastbound road is
/// `-y`); right turns positive. These are nominal — one representative angle per `LANE_*` class —
/// and are only the fallback: [`turn_angle`] prefers the real geometric angle wherever the lane's
/// exit heading is known, because a junction's arms meet at whatever angles the roads happen to
/// take, not at a handful of tidy multiples of thirty degrees.
///
/// Every angle here is fixed by its `LANE_*` class alone except the U-turn, which is the one
/// manoeuvre the driving convention turns around: it crosses the oncoming stream to the far kerb,
/// so it hooks left where traffic keeps right and right where traffic keeps left.
pub fn nominal_turn_angle(arrow: TurnArrow, left_hand: bool) -> f32 {
    use std::f32::consts::PI;
    match arrow {
        TurnArrow::Through => 0.0,
        TurnArrow::SlightLeft => -PI / 6.0,
        TurnArrow::Left => -PI / 2.0,
        TurnArrow::SharpLeft => -3.0 * PI / 4.0,
        TurnArrow::SlightRight => PI / 6.0,
        TurnArrow::Right => PI / 2.0,
        TurnArrow::SharpRight => 3.0 * PI / 4.0,
        TurnArrow::Reverse => {
            if left_hand {
                PI
            } else {
                -PI
            }
        }
        TurnArrow::MergeLeft => -PI / 6.0,
        TurnArrow::MergeRight => PI / 6.0,
    }
}

/// How far this arrow bends, in radians: the measured turn from the lane's approach heading to the
/// exit it leads to, or [`nominal_turn_angle`] when the archive carries no exit heading.
///
/// Wrapped to `(-pi, pi]`, so a manoeuvre is always drawn as the short way round.
pub fn turn_angle(inst: &ArrowInstance) -> f32 {
    match inst.exit_angle {
        Some(exit) => wrap_pi(exit - inst.angle),
        None => nominal_turn_angle(inst.arrow, inst.left_hand),
    }
}

/// Wrap an angle into `(-pi, pi]`.
fn wrap_pi(angle: f32) -> f32 {
    use std::f32::consts::PI;
    let wrapped = (angle + PI).rem_euclid(2.0 * PI) - PI;
    if wrapped <= -PI {
        wrapped + 2.0 * PI
    } else {
        wrapped
    }
}

// This glyph and the lane-guidance glyphs in
// `library/ui/src/main/java/com/vayunmathur/library/ui/Icons.kt` (`laneVector`) are deliberate
// counterparts: the same shaft-then-bend-then-filled-head construction over the same `LANE_*`
// vocabulary, so a manoeuvre reads the same painted on the road as it does in the guidance bar.
// One is Compose vector paths and the other is Rust tessellation, so there is no code to share
// across that boundary — the two have to be changed together or the map and the bar drift apart.

/// Half-width of the arrow's shaft, in unit-arrow coordinates.
const SHAFT_HALF_WIDTH: f32 = 0.15;
/// The back of the shaft: the straight run `TAIL_X..BEND_START_X` lies along the lane.
const TAIL_X: f32 = -0.9;
/// Where the bend leaves the straight run — the counterpart of `LANE_FORK_Y` in `Icons.kt`.
const BEND_START_X: f32 = 0.0;
/// Arc length of the bend, the same whatever the angle, so every glyph carries the same amount of
/// paint and a sharper turn curls tighter instead of reaching further across the road.
const BEND_LEN: f32 = 0.55;
const HEAD_LEN: f32 = 0.45;
const HEAD_HALF_WIDTH: f32 = 0.4;
/// Pieces the bend is sampled into. Six is smooth at the ~9dp the arrow draws at, and is fixed
/// rather than angle-dependent so a glyph's vertex count is the constant [`ARROW_VERTS`].
const BEND_SEGMENTS: usize = 6;

/// Vertices in one arrow's triangle list, whatever angle it bends through.
///
/// One quad (six vertices) per centreline segment, plus the head's three. Constant so the renderer
/// can size a tile's whole vertex buffer up front — this used to be `UNIT_ARROW_TRIANGLES.len()`
/// back when a single straight shape was merely rotated.
pub const ARROW_VERTS: usize = 6 * (BEND_SEGMENTS + 1) + 3;

/// The glyph's centreline for a bend of `turn` radians: the tail, then the bend sampled into
/// [`BEND_SEGMENTS`] pieces, each point paired with the path's heading there.
///
/// The bend is a circular arc of fixed length [`BEND_LEN`] and therefore radius `BEND_LEN / |turn|`
/// — tangent to the lane where it starts and pointing exactly along `turn` where it ends, for any
/// `turn` at all rather than for a handful of buckets. As `turn` approaches zero the radius
/// diverges and the arc becomes the straight continuation of the shaft, which is taken directly.
fn unit_centreline(turn: f32) -> [((f32, f32), f32); BEND_SEGMENTS + 2] {
    let mut pts = [((TAIL_X, 0.0), 0.0f32); BEND_SEGMENTS + 2];
    let sweep = turn.abs();
    let side = if turn < 0.0 { -1.0 } else { 1.0 };
    // Below this an arc of this length departs from its chord by far less than a pixel, and
    // `BEND_LEN / sweep` is on its way to overflowing; draw the straight run it is indistinguishable
    // from instead.
    let straight = sweep < 1e-3;
    let radius = if straight { 0.0 } else { BEND_LEN / sweep };
    for i in 0..=BEND_SEGMENTS {
        let t = i as f32 / BEND_SEGMENTS as f32;
        let u = sweep * t;
        let point = if straight {
            (BEND_START_X + BEND_LEN * t, 0.0)
        } else {
            (BEND_START_X + radius * u.sin(), side * radius * (1.0 - u.cos()))
        };
        pts[i + 1] = (point, side * u);
    }
    pts
}

/// One edge of the shaft at a centreline point, offset perpendicular to the heading there.
fn shaft_edge(((x, y), heading): ((f32, f32), f32), half_width: f32) -> (f32, f32) {
    (x - heading.sin() * half_width, y + heading.cos() * half_width)
}

/// The arrow as a triangle list in unit coordinates (roughly `-1..1`), running along `+x` — the
/// direction of travel — and then bending through `turn` radians.
///
/// A left turn is therefore an L: it follows the lane and only then bends, the way a road-surface
/// marking and the navigation lane bar both draw one. It is emphatically *not* a straight arrow
/// aimed sideways across the road, which is what rotating a single fixed shape produced. `turn` is
/// continuous, so every angle a junction can present is drawable.
///
/// Returned by value as a fixed-size array rather than a `Vec`: [`arrow_verts`] calls this once per
/// arrow per frame, so it must not allocate. Vertices are `(x, y)`, three per triangle, with the
/// head last in the order base, tip, base.
pub fn unit_arrow_triangles(turn: f32) -> [(f32, f32); ARROW_VERTS] {
    let mut out = [(0.0f32, 0.0f32); ARROW_VERTS];
    let pts = unit_centreline(turn);
    // The shaft, one quad per centreline segment. Both corners of a segment's end use that point's
    // own heading, so consecutive quads share an edge exactly and the ribbon has no gap at a joint.
    let mut n = 0;
    for pair in pts.windows(2) {
        let (a, b) = (pair[0], pair[1]);
        let (al, ar) = (shaft_edge(a, -SHAFT_HALF_WIDTH), shaft_edge(a, SHAFT_HALF_WIDTH));
        let (bl, br) = (shaft_edge(b, -SHAFT_HALF_WIDTH), shaft_edge(b, SHAFT_HALF_WIDTH));
        out[n..n + 6].copy_from_slice(&[al, bl, br, al, br, ar]);
        n += 6;
    }
    // The head: a filled triangle off the end of the bend, aimed along where the bend left off.
    let ((ex, ey), heading) = pts[pts.len() - 1];
    let (hc, hs) = (heading.cos(), heading.sin());
    out[n] = (ex + hs * HEAD_HALF_WIDTH, ey - hc * HEAD_HALF_WIDTH);
    out[n + 1] = (ex + hc * HEAD_LEN, ey + hs * HEAD_LEN);
    out[n + 2] = (ex - hs * HEAD_HALF_WIDTH, ey + hc * HEAD_HALF_WIDTH);
    out
}

/// Transform the unit arrow into tile-local triangle vertices for one placed arrow, appending
/// `x, y` pairs (three per triangle) to `out`.
///
/// `scale` is tile-local units per unit-arrow coordinate (the arrow's screen size ÷ the tile's
/// screen span); `lateral` is the sideways lane offset in the same tile-local units, applied
/// perpendicular to the road heading so each lane's arrow sits over its lane. The glyph is rotated
/// by the road heading alone — the manoeuvre is *built into* the shape by [`turn_angle`] rather
/// than added to the rotation. This is the exact math the renderer runs per frame, factored out so
/// it is a unit test rather than a screenshot.
pub fn arrow_verts(inst: &ArrowInstance, scale: f32, lateral: f32, out: &mut Vec<f32>) {
    // Perpendicular to the road heading (left is -y in tile space), for the lane offset.
    let (rc, rs) = (inst.angle.cos(), inst.angle.sin());
    let (cx, cy) = (inst.anchor.0 - rs * lateral, inst.anchor.1 + rc * lateral);
    for (ux, uy) in unit_arrow_triangles(turn_angle(inst)) {
        let rx = ux * rc - uy * rs;
        let ry = ux * rs + uy * rc;
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
        let arrows = place_arrows(&line, &turns, 3, false);
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
        let arrows = place_arrows(&line, &turns, 1, false);
        assert_eq!(arrows.len(), 1);
        // Heading toward the start of an eastbound way is due west: pi radians.
        assert!((arrows[0].angle.abs() - std::f32::consts::PI).abs() < 1e-6);
        assert!(arrows[0].anchor.0 > 0.0 && arrows[0].anchor.0 < 100.0);
    }

    /// A line too short to have a heading draws nothing rather than an arrow pointing nowhere.
    #[test]
    fn a_degenerate_line_places_no_arrows() {
        let turns = LaneTurns { forward: vec![LANE_THROUGH], backward: vec![] };
        assert!(place_arrows(&[(1.0, 1.0)], &turns, 1, false).is_empty());
        assert!(place_arrows(&[], &turns, 1, false).is_empty());
        // Two coincident points have no direction.
        assert!(place_arrows(&[(5.0, 5.0), (5.0, 5.0)], &turns, 1, false).is_empty());
    }

    /// The glyph runs *along* the lane before it bends, which is what makes a left turn read as a
    /// left turn rather than as a straight arrow aimed across the road.
    #[test]
    fn the_glyph_runs_along_the_lane_and_then_bends() {
        use std::f32::consts::PI;
        // A through arrow is straight along +x with its tip at the far end and no sideways reach.
        let straight = unit_arrow_triangles(0.0);
        assert_eq!(straight.len() % 3, 0, "a triangle list is a multiple of three vertices");
        let max_x = straight.iter().fold(f32::MIN, |m, &(x, _)| m.max(x));
        assert!((max_x - 1.0).abs() < 1e-5, "the tip is at +x");
        assert!(straight.iter().all(|&(_, y)| y.abs() < HEAD_HALF_WIDTH + 1e-5), "and it is straight");

        // A left turn is an L. Its tail still lies on the lane axis pointing down the lane, and
        // only the far end has swung out to -y; a rotated straight arrow would instead have swung
        // its tail out to +y and crossed the road.
        let left = unit_arrow_triangles(-PI / 2.0);
        let tail_x = left.iter().fold(f32::MAX, |m, &(x, _)| m.min(x));
        assert!((tail_x - TAIL_X).abs() < 1e-5, "the shaft starts behind the anchor, on the axis");
        let tail_y = left
            .iter()
            .filter(|&&(x, _)| (x - TAIL_X).abs() < 1e-5)
            .fold(0.0f32, |m, &(_, y)| m.max(y.abs()));
        assert!(tail_y <= SHAFT_HALF_WIDTH + 1e-5, "the tail sits on the lane it leaves");
        let tip = left[ARROW_VERTS - 2];
        assert!(tip.0 > 0.0, "the arrow advances down the lane first");
        assert!(tip.1 < -0.5, "and only then swings its head out to the left");
    }

    /// The bend is the angle it is given, whatever that angle is — the whole point of measuring the
    /// junction rather than bucketing it.
    #[test]
    fn the_bend_takes_any_angle_a_junction_happens_to_have() {
        for &turn in &[0.0f32, 0.07, -0.41, 0.83, -1.27, 2.6, -3.0] {
            let glyph = unit_arrow_triangles(turn);
            assert_eq!(glyph.len(), ARROW_VERTS, "the vertex count does not vary with the angle");
            let (base_a, tip, base_b) =
                (glyph[ARROW_VERTS - 3], glyph[ARROW_VERTS - 2], glyph[ARROW_VERTS - 1]);
            let mid = ((base_a.0 + base_b.0) / 2.0, (base_a.1 + base_b.1) / 2.0);
            let aimed = (tip.1 - mid.1).atan2(tip.0 - mid.0);
            assert!((aimed - turn).abs() < 1e-4, "asked for {turn}, head aims along {aimed}");
        }
    }

    /// And it tracks that angle continuously: two junctions a hair apart draw glyphs a hair apart,
    /// which a bucketed angle could not do.
    #[test]
    fn a_slightly_different_junction_draws_a_slightly_different_arrow() {
        let near = unit_arrow_triangles(0.50);
        let nudged = unit_arrow_triangles(0.51);
        let far = unit_arrow_triangles(0.60);
        let spread = |a: &[(f32, f32); ARROW_VERTS], b: &[(f32, f32); ARROW_VERTS]| {
            a.iter().zip(b).map(|(p, q)| (p.0 - q.0).hypot(p.1 - q.1)).fold(0.0f32, f32::max)
        };
        assert!(spread(&near, &nudged) > 1e-4, "the glyph tracks the angle, it does not snap");
        assert!(spread(&near, &nudged) < spread(&near, &far), "and tracks it proportionately");
    }

    /// A measured exit heading beats the nominal class angle, and the turn is always the short way
    /// round.
    #[test]
    fn the_measured_exit_beats_the_nominal_angle_and_wraps_the_short_way() {
        let inst = ArrowInstance {
            anchor: (0.5, 0.5),
            angle: 0.0,
            ordinal: 0,
            count: 1,
            arrow: TurnArrow::Right,
            exit_angle: None,
            fan_offset: 0.0,
            left_hand: false,
        };
        let nominal = nominal_turn_angle(TurnArrow::Right, false);
        assert!((turn_angle(&inst) - nominal).abs() < 1e-6, "no exit heading, fall back to class");
        // An exit 22 degrees off the approach bends 22 degrees, not the 90 its class nominates.
        let measured = ArrowInstance { exit_angle: Some(0.384), ..inst };
        assert!((turn_angle(&measured) - 0.384).abs() < 1e-5);
        // Approach and exit either side of the wrap point: the turn is 0.28 radians, not -6.
        let wrapped = ArrowInstance { angle: 3.0, exit_angle: Some(-3.0), ..inst };
        let short_way = 2.0 * std::f32::consts::PI - 6.0;
        assert!((turn_angle(&wrapped) - short_way).abs() < 1e-5, "got {}", turn_angle(&wrapped));
    }

    /// The nominal angles keep the left-negative / right-positive sign convention the placement
    /// geometry and the renderer are both written against.
    #[test]
    fn the_nominal_turn_angles_have_the_right_sign() {
        let nominal = |arrow| nominal_turn_angle(arrow, false);
        assert!(nominal(TurnArrow::Through).abs() < 1e-6);
        assert!(nominal(TurnArrow::Left) < 0.0, "left is a negative bend");
        assert!(nominal(TurnArrow::Right) > 0.0, "right is a positive bend");
        let (left, sharp) = (TurnArrow::Left, TurnArrow::SharpLeft);
        assert!(nominal(sharp) < nominal(left), "sharper bends further");
        let slight = TurnArrow::SlightLeft;
        assert!(nominal(slight) > nominal(left), "slighter bends less");
    }

    /// The U-turn is the one nominal angle the driving convention turns around: it is made across
    /// the oncoming stream, so it hooks left where traffic keeps right and right where it keeps
    /// left. Every other class is the same bend under either convention.
    ///
    /// Checked as a half turn and a sign rather than against `±PI` exactly, and as an exact
    /// negation of each other rather than by comparing two independently computed floats.
    #[test]
    fn a_u_turn_hooks_toward_the_kerb_traffic_drives_on() {
        use std::f32::consts::PI;
        let right_hand = nominal_turn_angle(TurnArrow::Reverse, false);
        let left_hand = nominal_turn_angle(TurnArrow::Reverse, true);
        assert!((right_hand.abs() - PI).abs() < 1e-6, "a U-turn is a half turn either way");
        assert!((left_hand.abs() - PI).abs() < 1e-6, "a U-turn is a half turn either way");
        assert!(right_hand < 0.0, "hooks left where traffic keeps right");
        assert!(left_hand > 0.0, "and right where traffic keeps left");
        assert!((right_hand + left_hand).abs() < 1e-6, "the two are the same turn mirrored");
        // And it is the only one that moves: every other class ignores the convention.
        for arrow in [
            TurnArrow::Through,
            TurnArrow::SlightLeft,
            TurnArrow::Left,
            TurnArrow::SharpLeft,
            TurnArrow::SlightRight,
            TurnArrow::Right,
            TurnArrow::SharpRight,
            TurnArrow::MergeLeft,
            TurnArrow::MergeRight,
        ] {
            let (r, l) = (nominal_turn_angle(arrow, false), nominal_turn_angle(arrow, true));
            assert_eq!(r, l, "{arrow:?} is not a convention-dependent manoeuvre");
        }
    }

    /// And the convention reaches the glyph: a U-turn placed on a left-hand-traffic road bends the
    /// other way from the same lane on a right-hand one.
    #[test]
    fn the_tiles_convention_reaches_the_u_turn_glyph() {
        let line = [(0.0, 0.0), (100.0, 0.0)];
        let turns = LaneTurns { forward: vec![LANE_REVERSE], backward: vec![] };
        let bend = |left_hand| {
            let arrows = place_arrows(&line, &turns, 1, left_hand);
            assert_eq!(arrows.len(), 1);
            turn_angle(&arrows[0])
        };
        assert!(bend(false) < 0.0, "right-hand traffic hooks its U-turn left");
        assert!(bend(true) > 0.0, "left-hand traffic hooks it right");
        // A measured exit heading still wins: the convention is only the fallback's business.
        let mut arrows = place_arrows(&line, &turns, 1, true);
        arrows[0].exit_angle = Some(-0.5);
        assert!(turn_angle(&arrows[0]) < 0.0, "a known exit beats the nominal U-turn");
    }

    /// The two directions' arrows do not land on top of each other, which is what drew as a single
    /// double-headed shaft.
    #[test]
    fn the_two_directions_do_not_stack_into_one_double_headed_shaft() {
        // With a half-segment setback both anchors were the midpoint of the same two-point line:
        // the same spot, opposite headings, i.e. one shaft with a head at each end.
        let line = [(0.0, 0.0), (100.0, 0.0)];
        let turns = LaneTurns { forward: vec![LANE_THROUGH], backward: vec![LANE_THROUGH] };
        let arrows = place_arrows(&line, &turns, 2, false);
        assert_eq!(arrows.len(), 2);
        let gap = (arrows[0].anchor.0 - arrows[1].anchor.0).abs();
        assert!(gap > 1.0, "the two directions anchor apart, not on top of each other");
        assert!(arrows[0].anchor.0 > 50.0, "forward sits back from the way's end");
        assert!(arrows[1].anchor.0 < 50.0, "backward sits back from the way's start");
    }

    /// The shaft never folds through itself, however tight the bend.
    ///
    /// The inner edge of the bend has radius `BEND_LEN / |turn| - SHAFT_HALF_WIDTH`, smallest at a
    /// U-turn; if the constants ever let that go negative the ribbon inverts and the glyph draws as
    /// a bow tie. Checked as consistent triangle winding rather than by re-deriving the radius.
    #[test]
    fn the_shaft_does_not_fold_at_the_tightest_bend() {
        use std::f32::consts::PI;
        for step in -24i32..=24 {
            let turn = PI * step as f32 / 24.0;
            let glyph = unit_arrow_triangles(turn);
            // The shaft is everything before the head's three vertices.
            for tri in glyph[..ARROW_VERTS - 3].chunks(3) {
                let (a, b, c) = (tri[0], tri[1], tri[2]);
                let area = (b.0 - a.0) * (c.1 - a.1) - (b.1 - a.1) * (c.0 - a.0);
                assert!(area > 1e-6, "shaft triangle inverted at turn {turn}, area {area}");
            }
        }
    }

    /// A direction's lanes sit on its own half of the carriageway rather than straddling the
    /// centreline, and which half is the driving convention: the left-hand fan is the mirror image
    /// of the right-hand one.
    ///
    /// Compared as a sign and as a sum against its mirror rather than for exact equality — the two
    /// are the same arithmetic with one negation, but not bit-for-bit.
    #[test]
    fn each_direction_keeps_to_its_own_half_and_mirrors_with_the_convention() {
        let line = [(0.0, 0.0), (100.0, 0.0)];
        // Two lanes each way, both directions tagged: four lanes across the road.
        let turns = LaneTurns {
            forward: vec![LANE_LEFT, LANE_THROUGH],
            backward: vec![LANE_THROUGH, LANE_RIGHT],
        };
        let centres = |left_hand| {
            place_arrows(&line, &turns, 4, left_hand).iter().map(lane_centre).collect::<Vec<f32>>()
        };
        let right = centres(false);
        let left = centres(true);
        assert_eq!(right.len(), 4, "two lanes each way");
        // Every lane of a direction is on the driver's own side of the centreline — measured in
        // that direction's own travel frame, so both directions come out the same side.
        assert!(right.iter().all(|&c| c > 0.0), "right-hand traffic keeps right: {right:?}");
        assert!(left.iter().all(|&c| c < 0.0), "left-hand traffic keeps left: {left:?}");
        // And the two conventions are mirror images across the centreline.
        for (r, l) in right.iter().zip(left.iter().rev()) {
            assert!((r + l).abs() < 1e-5, "{r} does not mirror {l}");
        }
        // The outermost lane of a four-lane road is a lane and a half from its centre, so the fan
        // lands on the carriageway rather than a half-road short of it.
        let outermost = right.iter().fold(0.0f32, |m, &c| m.max(c));
        assert!((outermost - 1.5).abs() < 1e-5, "outermost lane at {outermost}, not 1.5");
    }

    /// A one-way carries every lane, so its fan stays centred on the road — and a road whose total
    /// is unknown or disagrees keeps the centred fan it has always had. Neither depends on the
    /// convention, which is what makes the no-convention path behave exactly as it did before.
    #[test]
    fn a_one_way_and_an_unknown_total_keep_the_centred_fan_either_way() {
        let line = [(0.0, 0.0), (100.0, 0.0)];
        let turns =
            LaneTurns { forward: vec![LANE_LEFT, LANE_THROUGH, LANE_RIGHT], backward: vec![] };
        let cases =
            [(3u8, "a three-lane one-way"), (0, "no lanes tag"), (2, "a total that disagrees")];
        for (total, what) in cases {
            for left_hand in [false, true] {
                let centres: Vec<f32> =
                    place_arrows(&line, &turns, total, left_hand).iter().map(lane_centre).collect();
                assert_eq!(centres.len(), 3, "{what}");
                assert!(centres[1].abs() < 1e-6, "{what}: the middle lane is the centreline");
                assert!((centres[0] + centres[2]).abs() < 1e-6, "{what}: the fan is centred");
                assert!(centres[0] < 0.0, "{what}: ordinal 0 is the leftmost lane");
                assert!(centres[2] > 0.0, "{what}: and the last ordinal the rightmost");
            }
        }
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
            exit_angle: None,
            fan_offset: 0.0,
            left_hand: false,
        };
        let mut v = Vec::new();
        arrow_verts(&through, 0.1, 0.0, &mut v);
        assert_eq!(v.len(), ARROW_VERTS * 2);
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
