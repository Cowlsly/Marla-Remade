//! Douglas-Peucker simplification, in integer tile coordinates.
//!
//! Runs **after** the clip, so it only ever sees vertices already inside a tile's
//! buffered rect. That ordering matters: simplifying first and clipping second
//! would let a removed vertex change where an edge crosses the tile boundary, and
//! two adjacent tiles would disagree about the crossing point.
//!
//! ## Why integers
//!
//! The output is going straight into an MVT command stream, which is integer. Any
//! precision the algorithm preserves below one unit is discarded moments later, so
//! working in floats would only add rounding differences between runs. Distances
//! are compared **squared**, in `i128`, so there is no square root and no overflow:
//! a buffered coordinate fits in `i32`, and the cross product of two such spans
//! needs 64 bits before it is squared.
//!
//! ## Rings have two extra rules
//!
//! * **Closure is preserved.** The first and last vertex of a closed ring are both
//!   anchors, so the ring cannot be opened by simplification.
//! * **A ring never drops below four vertices**, i.e. three distinct corners plus
//!   the repeated close. Fewer than that encloses no area, and an MVT polygon with
//!   a two-vertex ring is a renderer's problem rather than a small one. When
//!   simplification would go that far the **ring is dropped instead**, and if that
//!   ring was the exterior the whole polygon goes with it. Keeping a collapsed
//!   sliver would paint a hairline in the layer's fill colour, which is worse than
//!   the feature being absent at that zoom.
//!
//! ## The tolerance policy is ours, not tippecanoe's
//!
//! [`tolerance_for`] is a fixed number of tile-extent units, and it returns zero at
//! the maximum zoom so the deepest tiles keep full detail. Because a tile is always
//! `extent` units across whatever the zoom, a constant tolerance is already
//! zoom-relative: the same 1 unit covers roughly 1000x more ground at z6 than at
//! z16, which is exactly the behaviour a pyramid wants. This is a deliberate,
//! documented policy rather than a reproduction of `--simplification`, and the
//! tests assert the policy's own invariants -- never equality with tippecanoe.

use crate::geom::{IPt, IntGeometry};

/// Simplification tolerance at [`crate::mvt::DEFAULT_EXTENT`], in extent units.
///
/// One unit is 1/4096 of a tile, which at z16 is under a metre. Below that,
/// detail is not representable in the tile anyway.
pub const DEFAULT_TOLERANCE: f64 = 1.0;

/// The least a ring may be reduced to: three distinct corners plus the close.
pub const MIN_RING_VERTICES: usize = 4;

/// The tolerance to use at zoom `z`, given the archive's maximum zoom and a
/// multiplier (1.0 for the default policy).
///
/// Zero at `max_zoom`: the deepest zoom is the one a user sees at full scale, and
/// it is also the only zoom whose geometry cannot be recovered from a deeper one.
pub fn tolerance_for(z: u8, max_zoom: u8, multiplier: f64) -> f64 {
    if z >= max_zoom {
        0.0
    } else {
        DEFAULT_TOLERANCE * multiplier
    }
}

/// Simplify every part of a geometry.
///
/// Points pass through: there is nothing in a point to remove, and dropping one
/// would silently thin a layer that the drop policy is supposed to own.
pub fn simplify(g: &IntGeometry, tolerance: f64) -> IntGeometry {
    if tolerance <= 0.0 {
        return g.clone();
    }
    match g {
        IntGeometry::Points(_) => g.clone(),
        IntGeometry::Lines(lines) => IntGeometry::Lines(
            lines
                .iter()
                .map(|l| simplify_line(l, tolerance))
                .filter(|l| l.len() >= 2)
                .collect(),
        ),
        IntGeometry::Polygons(polys) => IntGeometry::Polygons(
            polys
                .iter()
                .filter_map(|rings| {
                    let mut out: Vec<Vec<IPt>> = Vec::with_capacity(rings.len());
                    for (i, ring) in rings.iter().enumerate() {
                        match simplify_ring(ring, tolerance) {
                            Some(r) => out.push(r),
                            // The exterior collapsing takes the holes with it.
                            None if i == 0 => return None,
                            None => continue,
                        }
                    }
                    (!out.is_empty()).then_some(out)
                })
                .collect(),
        ),
    }
}

/// Douglas-Peucker on an open polyline. The endpoints are always kept.
pub fn simplify_line(line: &[IPt], tolerance: f64) -> Vec<IPt> {
    if line.len() < 3 || tolerance <= 0.0 {
        return line.to_vec();
    }
    let tol_sq = (tolerance * tolerance) as i128;
    let mut keep = vec![false; line.len()];
    keep[0] = true;
    keep[line.len() - 1] = true;
    mark(line, 0, line.len() - 1, tol_sq, &mut keep);
    line.iter()
        .zip(&keep)
        .filter(|(_, k)| **k)
        .map(|(p, _)| *p)
        .collect()
}

/// Douglas-Peucker on a ring, or `None` when the result would enclose no area.
///
/// A closed ring (first == last) stays closed. An unclosed one stays unclosed, and
/// its floor is one lower since it has no repeated vertex.
pub fn simplify_ring(ring: &[IPt], tolerance: f64) -> Option<Vec<IPt>> {
    let closed = ring.len() > 1 && ring.first() == ring.last();
    let floor = if closed { MIN_RING_VERTICES } else { MIN_RING_VERTICES - 1 };
    if ring.len() < floor {
        return None;
    }
    if tolerance <= 0.0 {
        return Some(ring.to_vec());
    }

    // Running Douglas-Peucker over the closed ring works because the chord from
    // the first vertex to the last is degenerate, so `perp_dist_sq` falls back to
    // point-to-point distance and picks the vertex furthest from the start. That
    // splits the ring into two arcs, each of which then simplifies normally.
    let simplified = simplify_line(ring, tolerance);
    if simplified.len() < floor {
        return None;
    }
    // Belt and braces: closure is what `keep[len-1] = true` guarantees, but a
    // polygon that silently opened would be a rendering bug nothing else catches.
    if closed && simplified.first() != simplified.last() {
        return None;
    }
    Some(simplified)
}

/// Mark the vertices Douglas-Peucker keeps between two anchors.
///
/// Iterative rather than recursive: a coastline ring can be hundreds of thousands
/// of vertices, and the recursion depth is only bounded by the data.
fn mark(pts: &[IPt], first: usize, last: usize, tol_sq: i128, keep: &mut [bool]) {
    let mut stack = vec![(first, last)];
    while let Some((a, b)) = stack.pop() {
        if b <= a + 1 {
            continue;
        }
        let mut worst = 0i128;
        let mut worst_i = a;
        for i in (a + 1)..b {
            let d = perp_dist_sq(pts[i], pts[a], pts[b]);
            if d > worst {
                worst = d;
                worst_i = i;
            }
        }
        if worst > tol_sq {
            keep[worst_i] = true;
            stack.push((a, worst_i));
            stack.push((worst_i, b));
        }
    }
}

/// Squared distance from `p` to the segment `a -> b`.
///
/// A zero-length chord degrades to point-to-point distance, which is what makes
/// the closed-ring case work.
fn perp_dist_sq(p: IPt, a: IPt, b: IPt) -> i128 {
    let (dx, dy) = ((b.0 - a.0) as i128, (b.1 - a.1) as i128);
    let (px, py) = ((p.0 - a.0) as i128, (p.1 - a.1) as i128);
    let len_sq = dx * dx + dy * dy;
    if len_sq == 0 {
        return px * px + py * py;
    }
    // Distance from the infinite line, not the segment: Douglas-Peucker measures
    // deviation from the chord, and both endpoints are anchors anyway.
    let cross = px * dy - py * dx;
    // cross^2 / len_sq, kept as an integer division. Truncating biases the
    // comparison very slightly towards dropping a vertex, which is the safe
    // direction: it can never keep a vertex that is genuinely below tolerance.
    (cross * cross) / len_sq
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_collinear_run_collapses_to_its_endpoints() {
        let line = vec![(0, 0), (10, 0), (20, 0), (30, 0), (40, 0)];
        assert_eq!(simplify_line(&line, 1.0), vec![(0, 0), (40, 0)]);
    }

    #[test]
    fn a_deviation_above_tolerance_is_kept_and_below_is_dropped() {
        // The middle vertex sits 5 units off the chord.
        let line = vec![(0, 0), (50, 5), (100, 0)];
        assert_eq!(simplify_line(&line, 1.0), line, "5 > 1, so it stays");
        assert_eq!(
            simplify_line(&line, 10.0),
            vec![(0, 0), (100, 0)],
            "5 < 10, so it goes"
        );
    }

    #[test]
    fn endpoints_are_never_removed() {
        // Even a line whose interior is entirely within tolerance keeps its ends.
        let line = vec![(0, 0), (1, 0), (2, 0), (100, 0)];
        let out = simplify_line(&line, 50.0);
        assert_eq!(out.first(), Some(&(0, 0)));
        assert_eq!(out.last(), Some(&(100, 0)));
        assert!(out.len() >= 2);
    }

    #[test]
    fn simplification_is_monotonic_in_the_tolerance() {
        // A zigzag of decreasing amplitude: a larger tolerance must never keep more
        // vertices than a smaller one.
        let line: Vec<IPt> = (0..40)
            .map(|i| (i * 10, if i % 2 == 0 { 40 - i } else { -(40 - i) }))
            .collect();
        let mut prev = usize::MAX;
        for tol in [0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0] {
            let n = simplify_line(&line, tol).len();
            assert!(
                n <= prev,
                "tolerance {tol} kept {n} vertices, more than the {prev} before it"
            );
            prev = n;
        }
        // And the extremes behave.
        assert_eq!(simplify_line(&line, 0.0), line, "no tolerance, no change");
        assert_eq!(simplify_line(&line, 1e9).len(), 2, "everything but the ends");
    }

    #[test]
    fn simplification_never_adds_a_vertex_or_reorders_them() {
        let line: Vec<IPt> = (0..50).map(|i| (i * 7, (i * i) % 23)).collect();
        for tol in [1.0, 3.0, 9.0] {
            let out = simplify_line(&line, tol);
            assert!(out.len() <= line.len());
            // The output is a subsequence of the input.
            let mut it = line.iter();
            for p in &out {
                assert!(it.any(|q| q == p), "{p:?} is not in the input, or is out of order");
            }
        }
    }

    #[test]
    fn a_closed_ring_stays_closed() {
        // A square with a redundant midpoint on each side.
        let ring = vec![
            (0, 0),
            (50, 0),
            (100, 0),
            (100, 50),
            (100, 100),
            (50, 100),
            (0, 100),
            (0, 50),
            (0, 0),
        ];
        let out = simplify_ring(&ring, 1.0).expect("a square survives");
        assert_eq!(out.first(), out.last(), "closure preserved");
        assert_eq!(out.len(), 5, "four corners plus the close: {out:?}");
    }

    #[test]
    fn an_unclosed_ring_stays_unclosed() {
        let ring = vec![(0, 0), (50, 0), (100, 0), (100, 100), (0, 100)];
        let out = simplify_ring(&ring, 1.0).unwrap();
        assert_ne!(out.first(), out.last());
        assert_eq!(out.len(), 4, "the collinear midpoint goes: {out:?}");
    }

    #[test]
    fn a_ring_is_dropped_rather_than_collapsed_below_four_vertices() {
        // A very thin sliver: at a large tolerance every interior vertex is within
        // tolerance of the chord, so Douglas-Peucker would leave 2 vertices. That
        // encloses no area, so the ring must be dropped instead.
        let ring = vec![(0, 0), (500, 1), (1000, 0), (500, -1), (0, 0)];
        assert_eq!(simplify_ring(&ring, 1000.0), None, "a collapsed ring is dropped");
        // At a tolerance it can actually survive, it does.
        assert!(simplify_ring(&ring, 0.5).is_some());
    }

    #[test]
    fn a_ring_that_is_already_too_small_is_rejected() {
        assert_eq!(simplify_ring(&[], 1.0), None);
        assert_eq!(simplify_ring(&[(0, 0)], 1.0), None);
        assert_eq!(simplify_ring(&[(0, 0), (1, 1)], 1.0), None);
        // Closed, but only two distinct vertices.
        assert_eq!(simplify_ring(&[(0, 0), (1, 1), (0, 0)], 1.0), None);
        // The minimum viable closed ring, at zero tolerance.
        assert!(simplify_ring(&[(0, 0), (10, 0), (0, 10), (0, 0)], 0.0).is_some());
    }

    #[test]
    fn a_ring_at_the_floor_is_dropped_only_when_it_is_below_tolerance() {
        // Four vertices is the floor, but Douglas-Peucker can still remove the two
        // interior ones -- the ring is a line from ring[0] back to ring[0], so both
        // corners are measured against a degenerate chord. Whether it survives is
        // therefore a question about its size, not its vertex count.
        let ring = vec![(0, 0), (1000, 0), (0, 1000), (0, 0)];
        assert_eq!(simplify_ring(&ring, 1.0), Some(ring.clone()), "1000 units across, 1 unit tolerance");
        assert_eq!(simplify_ring(&ring, 1e9), None, "sub-tolerance, so dropped not collapsed");
    }

    #[test]
    fn the_geometry_entry_point_drops_lines_that_fall_below_two_vertices() {
        // simplify_line always keeps both ends, so a 2-vertex line survives; the
        // filter exists for a degenerate 1-vertex input.
        let g = IntGeometry::Lines(vec![
            vec![(0, 0), (10, 0), (20, 0)],
            vec![(5, 5)],
        ]);
        let IntGeometry::Lines(out) = simplify(&g, 1.0) else { panic!() };
        assert_eq!(out.len(), 1);
        assert_eq!(out[0], vec![(0, 0), (20, 0)]);
    }

    #[test]
    fn losing_the_exterior_ring_drops_the_whole_polygon() {
        // A 1000x2 sliver and a 100000-unit square, at a 1000-unit tolerance: the
        // sliver is entirely sub-tolerance and the square is nowhere near it.
        let sliver = vec![(0, 0), (500, 1), (1000, 0), (500, -1), (0, 0)];
        let fat = vec![(0, 0), (100_000, 0), (100_000, 100_000), (0, 100_000), (0, 0)];
        // Exterior collapses: the polygon goes, hole and all.
        let g = IntGeometry::Polygons(vec![vec![sliver.clone(), fat.clone()]]);
        let IntGeometry::Polygons(out) = simplify(&g, 1000.0) else { panic!() };
        assert!(out.is_empty(), "{out:?}");
        // A hole collapsing leaves the exterior intact.
        let g = IntGeometry::Polygons(vec![vec![fat, sliver]]);
        let IntGeometry::Polygons(out) = simplify(&g, 1000.0) else { panic!() };
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].len(), 1, "the hole was dropped, the exterior kept");
    }

    #[test]
    fn points_pass_through_untouched() {
        // Thinning points is the drop policy's job, not simplification's.
        let g = IntGeometry::Points(vec![(0, 0), (1, 1), (2, 2), (3, 3)]);
        assert_eq!(simplify(&g, 1e9), g);
    }

    #[test]
    fn a_zero_tolerance_is_the_identity() {
        let g = IntGeometry::Lines(vec![vec![(0, 0), (1, 0), (2, 0), (3, 0)]]);
        assert_eq!(simplify(&g, 0.0), g);
        assert_eq!(simplify(&g, -1.0), g, "a negative tolerance is not a licence");
    }

    #[test]
    fn the_tolerance_policy_spares_the_deepest_zoom() {
        assert_eq!(tolerance_for(14, 14, 1.0), 0.0, "max zoom keeps full detail");
        assert_eq!(tolerance_for(15, 14, 1.0), 0.0, "and so does anything past it");
        assert_eq!(tolerance_for(13, 14, 1.0), DEFAULT_TOLERANCE);
        assert_eq!(tolerance_for(6, 14, 4.0), DEFAULT_TOLERANCE * 4.0);
    }

    #[test]
    fn the_distance_maths_does_not_overflow_at_the_coordinate_extremes() {
        // A span of the full i32 range, squared, needs more than 64 bits. If this
        // panics in debug or wraps in release, the comparison is meaningless.
        let big = i32::MAX / 2;
        let d = perp_dist_sq((0, big), (-big, 0), (big, 0));
        assert!(d > 0, "{d}");
        assert_eq!(perp_dist_sq((5, 5), (0, 0), (0, 0)), 50, "degenerate chord");
        assert_eq!(perp_dist_sq((5, 0), (0, 0), (10, 0)), 0, "on the chord");
    }
}
