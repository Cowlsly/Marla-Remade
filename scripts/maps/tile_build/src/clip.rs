//! Clipping geometry to a tile's buffered rect.
//!
//! Two different algorithms, because lines and polygons need different answers
//! from a clip:
//!
//! * **Lines use Liang-Barsky.** A polyline crossing a tile can leave and re-enter
//!   it, so one input line yields **N output lines** -- the clip is a partition,
//!   not a truncation. Joining the pieces back up would draw a road straight
//!   across a bay it actually goes around.
//! * **Polygons use Sutherland-Hodgman.** A polygon must stay a closed area after
//!   clipping, so the parts of the tile boundary that bound the clipped shape have
//!   to become real edges. Liang-Barsky would give a set of disconnected arcs with
//!   no interior.
//!
//! ## What Sutherland-Hodgman does and does not do
//!
//! It clips against each of the four half-planes in turn, and it is exact for a
//! convex polygon. For a **concave** polygon whose clipped result is two or more
//! disjoint pieces, it returns a single ring joined by a degenerate sliver running
//! along the boundary. That sliver has zero area, so a scanline rasteriser fills
//! the right pixels, but it is one ring where a strict result would be two, and
//! that is not free: earcut cannot triangulate a self-touching ring reliably (see
//! `library/map/src/main/rust/src/tess/fill.rs`), so the renderer is left doing
//! the repair. Splitting them requires a general polygon clipper (Vatti or
//! Greiner-Hormann), which is a large piece of work. Documented here rather than
//! pretended away.
//!
//! ## Clip-introduced vertices are never removed
//!
//! A crossing this module interpolates is not in the source, so no annotation pass
//! measured it: it comes out of [`crate::geom::Vertex::boundary`] with significance
//! [`crate::geom::ALWAYS`]. That is what holds an exterior and a hole that both
//! reach the same tile edge to the same vertices along it. Thinning those two
//! coincident edges independently is what used to push a hole out through its own
//! exterior.
//!
//! ## The `NaN` question
//!
//! Both algorithms compare and divide. A non-finite vertex would make every
//! comparison false and could emit a `NaN` intersection, which
//! [`crate::geom::quantize`] then turns into a vertex at the tile origin. So
//! non-finite vertices are dropped on the way in; [`crate::geom::project`] clamps,
//! so they should not arise, but the clipper is the last place that can still tell
//! the difference between "outside" and "unknown".

use crate::geom::{Geometry, Pt, Rect, Vertex};

/// Clip a geometry to `rect`, in the same coordinate space as the input.
///
/// Points are kept or dropped whole; lines are partitioned; polygon rings are
/// clipped into new rings. Parts that vanish entirely are removed, so an empty
/// result means the geometry does not touch the rect at all.
pub fn clip_geometry<V: Vertex>(g: &Geometry<V>, rect: &Rect) -> Geometry<V> {
    match g {
        Geometry::Points(pts) => Geometry::Points(
            pts.iter()
                .copied()
                .filter(|p| finite(*p) && rect.contains(p.xy()))
                .collect(),
        ),
        Geometry::Lines(lines) => {
            let mut out = Vec::new();
            for line in lines {
                out.extend(clip_line(line, rect));
            }
            Geometry::Lines(out)
        }
        Geometry::Polygons(polys) => {
            let mut out = Vec::new();
            for rings in polys {
                let clipped = clip_polygon(rings, rect);
                if !clipped.is_empty() {
                    out.push(clipped);
                }
            }
            Geometry::Polygons(out)
        }
    }
}

fn finite<V: Vertex>(v: V) -> bool {
    let (x, y) = v.xy();
    x.is_finite() && y.is_finite()
}

/// Clip one polyline, returning the pieces that fall inside `rect`.
///
/// Consecutive segments that survive the clip and still meet are welded back into
/// one output line; a gap starts a new one. That is what makes the result a
/// partition of the original rather than a bag of segments.
pub fn clip_line<V: Vertex>(line: &[V], rect: &Rect) -> Vec<Vec<V>> {
    let mut out: Vec<Vec<V>> = Vec::new();
    let mut current: Vec<V> = Vec::new();

    for w in line.windows(2) {
        let (a, b) = (w[0], w[1]);
        if !finite(a) || !finite(b) {
            // Break the run: welding across a dropped vertex would invent an edge.
            if current.len() > 1 {
                out.push(std::mem::take(&mut current));
            } else {
                current.clear();
            }
            continue;
        }
        match clip_segment(a, b, rect) {
            None => {
                if current.len() > 1 {
                    out.push(std::mem::take(&mut current));
                } else {
                    current.clear();
                }
            }
            Some((ca, cb)) => {
                // By position, not by vertex: the near end of this segment and the
                // far end of the last are the same place but need not be the same
                // vertex -- one may be a source vertex and the other a crossing.
                if current.last().map(|v| v.xy()) == Some(ca.xy()) {
                    current.push(cb);
                } else {
                    if current.len() > 1 {
                        out.push(std::mem::take(&mut current));
                    } else {
                        current.clear();
                    }
                    current.push(ca);
                    current.push(cb);
                }
            }
        }
    }
    if current.len() > 1 {
        out.push(current);
    }
    out
}

/// Liang-Barsky: clip the segment `a -> b` to `rect`.
///
/// Returns the surviving sub-segment, or `None` when the segment misses the rect.
/// The algorithm narrows a parameter interval `[t0, t1]` along the segment against
/// each of the four boundaries; the segment is outside as soon as the interval
/// closes.
///
/// An endpoint the interval did not move is returned as itself, significance and
/// all; an endpoint it did move is a new vertex on the boundary.
///
/// A non-finite endpoint is `None`. Every comparison below would be false for a
/// `NaN`, so it would fall through as "inside" and emit a `NaN` intersection.
pub fn clip_segment<V: Vertex>(a: V, b: V, rect: &Rect) -> Option<(V, V)> {
    if !finite(a) || !finite(b) {
        return None;
    }
    let (ax, ay) = a.xy();
    let (bx, by) = b.xy();
    let (dx, dy) = (bx - ax, by - ay);
    let mut t0 = 0.0f64;
    let mut t1 = 1.0f64;

    // Each boundary is `p * t <= q`, where p is the direction component and q the
    // distance to the boundary.
    for &(p, q) in &[
        (-dx, ax - rect.min_x),
        (dx, rect.max_x - ax),
        (-dy, ay - rect.min_y),
        (dy, rect.max_y - ay),
    ] {
        if p == 0.0 {
            // Parallel to this boundary: inside iff already on the right side.
            if q < 0.0 {
                return None;
            }
            continue;
        }
        let t = q / p;
        if p < 0.0 {
            // Entering: raise the lower bound.
            if t > t1 {
                return None;
            }
            if t > t0 {
                t0 = t;
            }
        } else {
            // Leaving: lower the upper bound.
            if t < t0 {
                return None;
            }
            if t < t1 {
                t1 = t;
            }
        }
    }
    let at = |t: f64| (ax + t * dx, ay + t * dy);
    Some((
        if t0 == 0.0 { a } else { V::boundary(at(t0)) },
        if t1 == 1.0 { b } else { V::boundary(at(t1)) },
    ))
}

/// Clip a polygon's rings to `rect`.
///
/// A ring that clips away entirely is dropped. If the **exterior** ring
/// disappears the whole polygon does, holes included: a hole with no surrounding
/// area is not a shape, and emitting one would render as a solid patch of the
/// wrong colour.
pub fn clip_polygon<V: Vertex>(rings: &[Vec<V>], rect: &Rect) -> Vec<Vec<V>> {
    let mut out: Vec<Vec<V>> = Vec::new();
    for (i, ring) in rings.iter().enumerate() {
        let clipped = clip_ring(ring, rect);
        if clipped.len() < 3 {
            if i == 0 {
                return Vec::new();
            }
            continue;
        }
        out.push(clipped);
    }
    out
}

/// Sutherland-Hodgman: clip one ring against the four boundaries in turn.
///
/// The caller's closure convention is preserved: an explicitly closed ring
/// (first == last) is normalised on the way in and re-closed on the way out with a
/// **copy of the surviving first vertex**, so the two carry the same significance
/// and no later filter can open the ring by keeping one and dropping the other. An
/// unclosed ring stays unclosed. Fewer than three distinct vertices out means the
/// ring did not survive.
pub fn clip_ring<V: Vertex>(ring: &[V], rect: &Rect) -> Vec<V> {
    let was_closed = ring.len() > 1 && ring.first().map(|v| v.xy()) == ring.last().map(|v| v.xy());
    let open: Vec<V> = ring[..ring.len() - usize::from(was_closed)]
        .iter()
        .copied()
        .filter(|p| finite(*p))
        .collect();
    if open.len() < 3 {
        return Vec::new();
    }

    let mut current = open;
    for edge in [
        Edge::Left(rect.min_x),
        Edge::Right(rect.max_x),
        Edge::Bottom(rect.min_y),
        Edge::Top(rect.max_y),
    ] {
        if current.len() < 3 {
            return Vec::new();
        }
        let mut next: Vec<V> = Vec::with_capacity(current.len() + 4);
        for i in 0..current.len() {
            let a = current[(i + current.len() - 1) % current.len()];
            let b = current[i];
            let (a_in, b_in) = (edge.inside(a.xy()), edge.inside(b.xy()));
            if b_in {
                // Entering: the crossing comes first, then the vertex itself.
                if !a_in {
                    next.push(V::boundary(edge.intersect(a.xy(), b.xy())));
                }
                next.push(b);
            } else if a_in {
                // Leaving: only the crossing.
                next.push(V::boundary(edge.intersect(a.xy(), b.xy())));
            }
        }
        current = next;
    }

    // The clip can put two crossings on the same boundary point.
    current.dedup_by(|a, b| a.xy() == b.xy());
    if current.len() > 1 && current.first().map(|v| v.xy()) == current.last().map(|v| v.xy()) {
        current.pop();
    }
    if current.len() < 3 {
        return Vec::new();
    }
    if was_closed {
        let first = current[0];
        current.push(first);
    }
    current
}

/// Which side of a boundary a vertex is on, and where an edge crosses it.
#[derive(Clone, Copy)]
enum Edge {
    Left(f64),
    Right(f64),
    Bottom(f64),
    Top(f64),
}

impl Edge {
    fn inside(self, (x, y): Pt) -> bool {
        match self {
            Edge::Left(v) => x >= v,
            Edge::Right(v) => x <= v,
            Edge::Bottom(v) => y >= v,
            Edge::Top(v) => y <= v,
        }
    }

    /// Where `a -> b` crosses this boundary. Only called when the two vertices are
    /// on opposite sides, so the denominator cannot be zero.
    fn intersect(self, a: Pt, b: Pt) -> Pt {
        match self {
            Edge::Left(v) | Edge::Right(v) => {
                let t = (v - a.0) / (b.0 - a.0);
                (v, a.1 + t * (b.1 - a.1))
            }
            Edge::Bottom(v) | Edge::Top(v) => {
                let t = (v - a.1) / (b.1 - a.1);
                (a.0 + t * (b.0 - a.0), v)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A 10x10 rect at the origin, so every expected coordinate is readable.
    fn r() -> Rect {
        Rect { min_x: 0.0, min_y: 0.0, max_x: 10.0, max_y: 10.0 }
    }

    fn close(a: Pt, b: Pt) -> bool {
        (a.0 - b.0).abs() < 1e-9 && (a.1 - b.1).abs() < 1e-9
    }

    fn seg(a: Pt, b: Pt) -> Option<(Pt, Pt)> {
        clip_segment(a, b, &r())
    }

    // --- the clip-region matrix -------------------------------------------

    #[test]
    fn a_segment_wholly_inside_is_untouched() {
        let (a, b) = ((2.0, 2.0), (8.0, 7.0));
        let (ca, cb) = seg(a, b).unwrap();
        assert!(close(ca, a) && close(cb, b));
    }

    #[test]
    fn a_segment_wholly_outside_is_rejected_from_every_direction() {
        // The eight regions around the rect, plus two that straddle an axis
        // without ever entering (the case a naive "both endpoints outside" test
        // gets wrong is covered separately below).
        for (a, b) in [
            ((-5.0, 5.0), (-1.0, 5.0)),    // west
            ((11.0, 5.0), (20.0, 5.0)),    // east
            ((5.0, -5.0), (5.0, -1.0)),    // south
            ((5.0, 11.0), (5.0, 20.0)),    // north
            ((-5.0, -5.0), (-1.0, -1.0)),  // south-west
            ((11.0, -5.0), (20.0, -1.0)),  // south-east
            ((-5.0, 11.0), (-1.0, 20.0)),  // north-west
            ((11.0, 11.0), (20.0, 20.0)),  // north-east
            // Spans the rect's x range entirely above it.
            ((-5.0, 15.0), (15.0, 15.0)),
            // Passes diagonally past the north-west corner without touching: it is
            // already above the rect by the time it reaches x = 0.
            ((-5.0, 8.0), (2.0, 15.0)),
        ] {
            assert!(seg(a, b).is_none(), "{a:?} -> {b:?} must miss the rect");
        }
    }

    #[test]
    fn a_segment_crossing_one_boundary_is_cut_at_it() {
        // In from the west.
        let (ca, cb) = seg((-5.0, 5.0), (5.0, 5.0)).unwrap();
        assert!(close(ca, (0.0, 5.0)) && close(cb, (5.0, 5.0)));
        // Out to the east.
        let (ca, cb) = seg((5.0, 5.0), (15.0, 5.0)).unwrap();
        assert!(close(ca, (5.0, 5.0)) && close(cb, (10.0, 5.0)));
        // In from the south.
        let (ca, cb) = seg((5.0, -5.0), (5.0, 5.0)).unwrap();
        assert!(close(ca, (5.0, 0.0)) && close(cb, (5.0, 5.0)));
        // Out to the north.
        let (ca, cb) = seg((5.0, 5.0), (5.0, 15.0)).unwrap();
        assert!(close(ca, (5.0, 5.0)) && close(cb, (5.0, 10.0)));
    }

    #[test]
    fn a_segment_spanning_the_rect_is_cut_at_both_ends() {
        let (ca, cb) = seg((-10.0, 5.0), (20.0, 5.0)).unwrap();
        assert!(close(ca, (0.0, 5.0)) && close(cb, (10.0, 5.0)));
        // Diagonally corner to corner, both endpoints well outside.
        let (ca, cb) = seg((-10.0, -10.0), (20.0, 20.0)).unwrap();
        assert!(close(ca, (0.0, 0.0)) && close(cb, (10.0, 10.0)));
    }

    #[test]
    fn a_segment_clipping_a_corner_keeps_only_the_corner_sliver() {
        // Passes through the north-east corner region, entering and leaving.
        let (ca, cb) = seg((5.0, 15.0), (15.0, 5.0)).unwrap();
        assert!(close(ca, (10.0, 10.0)), "{ca:?}");
        assert!(close(cb, (10.0, 10.0)), "{cb:?}");
    }

    #[test]
    fn a_segment_parallel_to_a_boundary_is_handled_without_dividing_by_zero() {
        // Exactly along the southern edge: inside (the rect is closed).
        let (ca, cb) = seg((-5.0, 0.0), (15.0, 0.0)).unwrap();
        assert!(close(ca, (0.0, 0.0)) && close(cb, (10.0, 0.0)));
        // Parallel but outside.
        assert!(seg((-5.0, -1.0), (15.0, -1.0)).is_none());
        // Vertical along the western edge.
        let (ca, cb) = seg((0.0, -5.0), (0.0, 15.0)).unwrap();
        assert!(close(ca, (0.0, 0.0)) && close(cb, (0.0, 10.0)));
    }

    #[test]
    fn a_degenerate_segment_is_kept_only_if_inside() {
        let (ca, cb) = seg((5.0, 5.0), (5.0, 5.0)).unwrap();
        assert!(close(ca, (5.0, 5.0)) && close(cb, (5.0, 5.0)));
        assert!(seg((-5.0, -5.0), (-5.0, -5.0)).is_none());
        // Exactly on a corner counts as inside.
        assert!(seg((0.0, 0.0), (0.0, 0.0)).is_some());
    }

    #[test]
    fn a_non_finite_segment_is_rejected_rather_than_producing_nan() {
        assert!(seg((f64::NAN, 5.0), (5.0, 5.0)).is_none());
        assert!(seg((5.0, 5.0), (f64::INFINITY, 5.0)).is_none());
    }

    // --- polylines ---------------------------------------------------------

    #[test]
    fn a_polyline_inside_survives_as_one_piece() {
        let line = vec![(1.0, 1.0), (5.0, 5.0), (9.0, 2.0)];
        let out = clip_line(&line, &r());
        assert_eq!(out.len(), 1);
        assert_eq!(out[0], line);
    }

    #[test]
    fn a_polyline_leaving_and_re_entering_yields_two_pieces() {
        // Out to the east and back: one input line, two output lines. Welding them
        // would draw a straight edge across ground the line never covered.
        let line = vec![(5.0, 2.0), (15.0, 2.0), (15.0, 8.0), (5.0, 8.0)];
        let out = clip_line(&line, &r());
        assert_eq!(out.len(), 2, "{out:?}");
        assert!(close(out[0][0], (5.0, 2.0)) && close(out[0][1], (10.0, 2.0)));
        assert!(close(out[1][0], (10.0, 8.0)) && close(out[1][1], (5.0, 8.0)));
    }

    #[test]
    fn consecutive_surviving_segments_are_welded_into_one_line() {
        // Three segments, all inside: the output must be one 4-vertex line, not
        // three 2-vertex ones.
        let line = vec![(1.0, 1.0), (3.0, 3.0), (5.0, 2.0), (7.0, 6.0)];
        let out = clip_line(&line, &r());
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].len(), 4);
    }

    #[test]
    fn a_polyline_wholly_outside_yields_nothing() {
        let line = vec![(-5.0, -5.0), (-3.0, -4.0), (-1.0, -6.0)];
        assert!(clip_line(&line, &r()).is_empty());
    }

    #[test]
    fn a_polyline_that_only_grazes_a_corner_yields_a_degenerate_piece_or_nothing() {
        // A single touching point cannot make a line, so it must not be emitted as
        // a one-vertex "line" -- that encodes as a MoveTo with no LineTo.
        let line = vec![(5.0, 15.0), (15.0, 5.0)];
        for piece in clip_line(&line, &r()) {
            assert!(piece.len() >= 2, "no one-vertex lines: {piece:?}");
        }
    }

    #[test]
    fn a_dropped_vertex_breaks_the_run_rather_than_bridging_it() {
        let line = vec![(1.0, 1.0), (3.0, 3.0), (f64::NAN, 0.0), (7.0, 7.0), (9.0, 9.0)];
        let out = clip_line(&line, &r());
        assert_eq!(out.len(), 2, "{out:?}");
        assert_eq!(out[0], vec![(1.0, 1.0), (3.0, 3.0)]);
        assert_eq!(out[1], vec![(7.0, 7.0), (9.0, 9.0)]);
    }

    #[test]
    fn a_single_vertex_line_yields_nothing() {
        assert!(clip_line(&[(5.0, 5.0)], &r()).is_empty());
        assert!(clip_line::<Pt>(&[], &r()).is_empty());
    }

    // --- polygons ----------------------------------------------------------

    fn square(min: f64, max: f64) -> Vec<Pt> {
        vec![(min, min), (max, min), (max, max), (min, max), (min, min)]
    }

    fn area(ring: &[Pt]) -> f64 {
        let n = ring.len();
        let mut a = 0.0;
        for i in 0..n {
            let (x1, y1) = ring[i];
            let (x2, y2) = ring[(i + 1) % n];
            a += x1 * y2 - x2 * y1;
        }
        (a / 2.0).abs()
    }

    #[test]
    fn a_ring_inside_is_untouched_apart_from_its_closure_convention() {
        let ring = square(2.0, 8.0);
        let out = clip_ring(&ring, &r());
        assert_eq!(area(&out), area(&ring));
        assert_eq!(out.first(), out.last(), "a closed ring stays closed");
    }

    #[test]
    fn an_unclosed_ring_stays_unclosed() {
        let ring = vec![(2.0, 2.0), (8.0, 2.0), (8.0, 8.0), (2.0, 8.0)];
        let out = clip_ring(&ring, &r());
        assert_ne!(out.first(), out.last());
        assert_eq!(out.len(), 4);
    }

    #[test]
    fn a_ring_containing_the_rect_becomes_the_rect() {
        let ring = square(-100.0, 100.0);
        let out = clip_ring(&ring, &r());
        assert_eq!(area(&out), 100.0, "the whole 10x10 rect: {out:?}");
    }

    #[test]
    fn a_ring_half_outside_keeps_half_its_area() {
        // A 10x10 square offset 5 east: exactly half of it is in the rect.
        let ring = vec![(5.0, 0.0), (15.0, 0.0), (15.0, 10.0), (5.0, 10.0), (5.0, 0.0)];
        let out = clip_ring(&ring, &r());
        assert_eq!(area(&out), 50.0, "{out:?}");
        assert_eq!(out.first(), out.last());
    }

    #[test]
    fn a_ring_wholly_outside_disappears() {
        assert!(clip_ring(&square(20.0, 30.0), &r()).is_empty());
        // Touching the boundary but enclosing no area inside it.
        assert!(clip_ring(&square(10.0, 20.0), &r()).is_empty());
    }

    #[test]
    fn a_degenerate_ring_disappears() {
        assert!(clip_ring::<Pt>(&[], &r()).is_empty());
        assert!(clip_ring(&[(1.0, 1.0), (2.0, 2.0)], &r()).is_empty());
        // A closed ring of two distinct vertices is a line, not an area.
        assert!(clip_ring(&[(1.0, 1.0), (2.0, 2.0), (1.0, 1.0)], &r()).is_empty());
    }

    #[test]
    fn a_hole_survives_a_clip_that_does_not_reach_it() {
        let rings = vec![square(-5.0, 15.0), square(3.0, 6.0)];
        let out = clip_polygon(&rings, &r());
        assert_eq!(out.len(), 2, "exterior plus its hole");
        assert_eq!(area(&out[0]), 100.0, "the exterior became the rect");
        assert_eq!(area(&out[1]), 9.0, "the hole is untouched");
    }

    #[test]
    fn a_hole_clipped_away_is_dropped_but_the_polygon_stays() {
        let rings = vec![square(-5.0, 15.0), square(20.0, 25.0)];
        let out = clip_polygon(&rings, &r());
        assert_eq!(out.len(), 1, "only the exterior remains");
        assert_eq!(area(&out[0]), 100.0);
    }

    #[test]
    fn losing_the_exterior_ring_drops_the_holes_too() {
        // A hole with no surrounding area is not a shape. Emitting it would render
        // as a solid patch of whatever colour the layer paints.
        let rings = vec![square(20.0, 30.0), square(3.0, 6.0)];
        assert!(clip_polygon(&rings, &r()).is_empty());
    }

    #[test]
    fn a_concave_ring_clips_to_one_ring_with_the_right_total_area() {
        // A U shape straddling the eastern boundary: the clipped result is two
        // disjoint prongs, which Sutherland-Hodgman returns as ONE ring joined by a
        // zero-area sliver along the boundary. The area is still correct, which is
        // what the renderer cares about. See the module docs.
        let u = vec![
            (5.0, 0.0),
            (15.0, 0.0),
            (15.0, 3.0),
            (8.0, 3.0),
            (8.0, 7.0),
            (15.0, 7.0),
            (15.0, 10.0),
            (5.0, 10.0),
            (5.0, 0.0),
        ];
        let out = clip_ring(&u, &r());
        assert!(!out.is_empty());
        // Inside the rect: the left bar (5..10 x 0..10 = 50) minus the notch
        // (8..10 x 3..7 = 8) = 42.
        assert!((area(&out) - 42.0).abs() < 1e-9, "area {} from {out:?}", area(&out));
    }

    // --- the geometry-level entry point -----------------------------------

    #[test]
    fn clip_geometry_filters_points_whole() {
        let g = Geometry::Points(vec![(5.0, 5.0), (15.0, 5.0), (0.0, 0.0), (f64::NAN, 1.0)]);
        let Geometry::Points(out) = clip_geometry(&g, &r()) else { panic!() };
        assert_eq!(out, vec![(5.0, 5.0), (0.0, 0.0)]);
    }

    #[test]
    fn clip_geometry_flattens_partitioned_lines_into_one_list() {
        // Two input lines, the second of which splits in two: three parts out.
        let g = Geometry::Lines(vec![
            vec![(1.0, 1.0), (2.0, 2.0)],
            vec![(5.0, 2.0), (15.0, 2.0), (15.0, 8.0), (5.0, 8.0)],
        ]);
        let Geometry::Lines(out) = clip_geometry(&g, &r()) else { panic!() };
        assert_eq!(out.len(), 3);
    }

    #[test]
    fn clip_geometry_drops_polygons_that_vanish_entirely() {
        let g = Geometry::Polygons(vec![vec![square(2.0, 8.0)], vec![square(20.0, 30.0)]]);
        let Geometry::Polygons(out) = clip_geometry(&g, &r()) else { panic!() };
        assert_eq!(out.len(), 1);
    }

    #[test]
    fn clipping_to_a_buffered_tile_rect_keeps_the_overspill() {
        use crate::geom::{tile_rect, DEFAULT_BUFFER};
        // A line 3 units past tile 0's eastern edge, with a 5-unit buffer: the
        // overspill survives, which is what stops a seam at the tile join.
        let rect = tile_rect(0, 0, 4096, DEFAULT_BUFFER);
        let line = vec![(4090.0, 100.0), (4099.0, 100.0)];
        let out = clip_line(&line, &rect);
        assert_eq!(out.len(), 1);
        assert!(close(out[0][1], (4099.0, 100.0)), "inside the buffer: {out:?}");
        // Past the buffer, it is cut at buffer's edge.
        let line = vec![(4090.0, 100.0), (4200.0, 100.0)];
        let out = clip_line(&line, &rect);
        assert!(close(out[0][1], (4096.0 + DEFAULT_BUFFER, 100.0)), "{out:?}");
    }

    // --- significance ------------------------------------------------------

    /// Every vertex the clip interpolates is marked as one no threshold may drop,
    /// and every vertex that merely passes through keeps whatever it arrived with.
    /// That is the contract [`crate::simplify::filter`] relies on to keep two rings
    /// sharing a boundary edge on the same vertices.
    #[test]
    fn a_crossing_is_marked_unremovable_and_a_pass_through_keeps_its_significance() {
        use crate::geom::{SigPt, ALWAYS};

        let at = |x: f64, y: f64, sig: f64| SigPt { x, y, sig };
        // In from the west, out to the east: both ends are crossings, the middle
        // vertex is the source's own and keeps its score.
        let line = [at(-5.0, 5.0, 7.0), at(5.0, 5.0, 3.0), at(15.0, 5.0, 9.0)];
        let out = clip_line(&line, &r());
        assert_eq!(out.len(), 1, "{out:?}");
        let piece = &out[0];
        assert_eq!(piece.len(), 3);
        assert_eq!(piece[0], at(0.0, 5.0, ALWAYS), "the western crossing");
        assert_eq!(piece[1], at(5.0, 5.0, 3.0), "untouched, score and all");
        assert_eq!(piece[2], at(10.0, 5.0, ALWAYS), "the eastern crossing");

        // A segment entirely inside is handed back as the very same vertices, not
        // as recomputed ones: `a + 1.0 * (b - a)` is not always `b`.
        let inside = [at(2.0, 2.0, 4.0), at(8.0, 7.0, 6.0)];
        assert_eq!(clip_line(&inside, &r())[0], inside.to_vec());
    }

    /// A ring re-closed after clipping must close on a **copy** of its first
    /// surviving vertex. Two ends with different significance is how a filter opens
    /// a ring: it keeps one and drops the other.
    #[test]
    fn a_re_closed_ring_closes_on_a_copy_of_its_first_vertex() {
        use crate::geom::SigPt;

        let at = |x: f64, y: f64, sig: f64| SigPt { x, y, sig };
        // Half in, half out, so the ring is genuinely rebuilt by the clip.
        let ring = [
            at(5.0, 2.0, 1.0),
            at(15.0, 2.0, 2.0),
            at(15.0, 8.0, 3.0),
            at(5.0, 8.0, 4.0),
            at(5.0, 2.0, 1.0),
        ];
        let out = clip_ring(&ring, &r());
        assert!(out.len() > 3, "{out:?}");
        assert_eq!(out.first(), out.last(), "closed, on the same vertex exactly");
    }
}
