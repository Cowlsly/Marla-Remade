//! Simplification, in two halves: annotate once, then filter per zoom.
//!
//! [`annotate`] runs Douglas-Peucker **once** over each unclipped source ring or
//! line and records, on every vertex, how much shape that vertex is responsible
//! for. [`filter`] then keeps a vertex when its significance clears the zoom's
//! threshold. This is geojson-vt's design, and it is here for one reason:
//!
//! ## Why not Douglas-Peucker per tile
//!
//! [`crate::clip::clip_polygon`] clips each ring of a polygon on its own, so a hole
//! that crossed a tile edge ends up sharing an edge **exactly** with the clipped
//! exterior along the tile boundary. Running Douglas-Peucker on each ring
//! afterwards anchors each at its own first and last vertex -- positions that have
//! nothing to do with each other between two rings -- so those two coincident
//! edges get thinned differently and move apart by up to a unit. The hole then
//! straddles or escapes its exterior, earcut cannot express that, and the renderer
//! has no choice but to drop the hole: every affected island and lake fills in
//! solid.
//!
//! Annotating first fixes it by construction. A vertex's significance is a property
//! of the vertex, not of the ring it is being simplified inside, so it is kept or
//! dropped consistently in every ring and every tile it appears in. Vertices the
//! clipper invents on the boundary carry [`crate::geom::ALWAYS`], so a clipped ring
//! is never re-simplified along the edge it was cut at.
//!
//! ## Why annotating once is enough
//!
//! [`crate::geom::project`] is a pure scale by `2^z`, so every perpendicular
//! distance at one zoom is the same constant multiple of the distance at another:
//! significance is scale-invariant, and the zoom only picks the threshold. That is
//! also why the annotation pass uses the tolerance at `max_zoom`, which
//! [`tolerance_for`] defines as zero -- full recursion, so every vertex receives
//! its true significance rather than only those above some floor.
//!
//! It is also cheaper than what it replaces: once per feature per zoom instead of
//! once per feature per **tile**.
//!
//! ## Rings have two extra rules
//!
//! * **Closure is preserved.** A closed ring is filtered without its explicit
//!   closing vertex and re-closed with a copy of whichever vertex survived first,
//!   so the two can never disagree.
//! * **A ring never drops below four vertices**, i.e. three distinct corners plus
//!   the repeated close. Fewer than that encloses no area, and an MVT polygon with
//!   a two-vertex ring is a renderer's problem rather than a small one. When
//!   filtering would go that far the **ring is dropped instead**, and if that ring
//!   was the exterior the whole polygon goes with it. Keeping a collapsed sliver
//!   would paint a hairline in the layer's fill colour, which is worse than the
//!   feature being absent at that zoom.
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

use crate::geom::{Geometry, SigPt, Vertex, ALWAYS};

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

/// Record every vertex's significance, in place, on an unclipped geometry.
///
/// Must run **before** the clip: the whole point is that a vertex is measured
/// against its neighbours in the source, not against wherever a tile boundary
/// happened to cut the ring.
///
/// Points are left alone. There is nothing in a point to remove, and thinning a
/// point layer is the drop policy's job.
pub fn annotate(g: &mut Geometry<SigPt>) {
    match g {
        Geometry::Points(_) => {}
        Geometry::Lines(lines) => lines.iter_mut().for_each(|l| annotate_path(l)),
        Geometry::Polygons(polys) => polys
            .iter_mut()
            .flatten()
            .for_each(|ring| annotate_path(ring)),
    }
}

/// Keep the vertices whose significance clears `tolerance`.
///
/// A pure filter: no vertex is measured, moved or invented here, which is what
/// makes two rings that share an edge agree along it.
pub fn filter(g: &Geometry<SigPt>, tolerance: f64) -> Geometry<SigPt> {
    if tolerance <= 0.0 {
        return g.clone();
    }
    let tol_sq = tolerance * tolerance;
    match g {
        Geometry::Points(_) => g.clone(),
        Geometry::Lines(lines) => Geometry::Lines(
            lines
                .iter()
                .map(|l| filter_line(l, tol_sq))
                .filter(|l| l.len() >= 2)
                .collect(),
        ),
        Geometry::Polygons(polys) => Geometry::Polygons(
            polys
                .iter()
                .filter_map(|rings| {
                    let mut out: Vec<Vec<SigPt>> = Vec::with_capacity(rings.len());
                    for (i, ring) in rings.iter().enumerate() {
                        match filter_ring(ring, tol_sq) {
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

/// Filter an open polyline. Its endpoints carry [`ALWAYS`] -- either from
/// [`annotate`], or from the clipper having put them on a tile boundary -- so they
/// survive without a special case for them.
pub fn filter_line(line: &[SigPt], tol_sq: f64) -> Vec<SigPt> {
    line.iter().copied().filter(|v| v.sig > tol_sq).collect()
}

/// Filter a ring, or `None` when the result would enclose no area.
///
/// A closed ring (first == last) stays closed; an unclosed one stays unclosed, and
/// its floor is one lower since it has no repeated vertex.
pub fn filter_ring(ring: &[SigPt], tol_sq: f64) -> Option<Vec<SigPt>> {
    let closed = ring.len() > 1 && ring.first().map(|v| v.xy()) == ring.last().map(|v| v.xy());
    let floor = if closed { MIN_RING_VERTICES } else { MIN_RING_VERTICES - 1 };
    if ring.len() < floor {
        return None;
    }
    // The closing vertex is not filtered on its own: re-closing with a copy of the
    // surviving first vertex is what makes it impossible to open the ring.
    let open = &ring[..ring.len() - usize::from(closed)];
    let mut out: Vec<SigPt> = open.iter().copied().filter(|v| v.sig > tol_sq).collect();
    if out.len() + usize::from(closed) < floor {
        return None;
    }
    if closed {
        out.push(out[0]);
    }
    Some(out)
}

/// Run Douglas-Peucker to exhaustion over one path, writing each vertex's
/// significance as it goes.
///
/// The path's own two ends are [`ALWAYS`]: a line must keep its endpoints, and for
/// a closed ring the two ends are the same place, so anchoring both is what keeps
/// the ring closed.
fn annotate_path(pts: &mut [SigPt]) {
    let Some(last) = pts.len().checked_sub(1) else { return };
    pts[0].sig = ALWAYS;
    pts[last].sig = ALWAYS;
    if pts.len() < 3 {
        return;
    }
    mark(pts, 0, last);
}

/// Measure every vertex between two anchors and recurse into both halves.
///
/// Iterative rather than recursive: a coastline ring can be hundreds of thousands
/// of vertices, and the recursion depth is only bounded by the data.
///
/// There is no tolerance here -- recursion continues while any vertex is off the
/// chord at all, which is [`tolerance_for`] at `max_zoom` and is what gives every
/// vertex its true significance rather than a lower bound on it. A vertex that is
/// exactly on every chord it is ever measured against keeps significance zero and
/// is dropped by any non-zero threshold, which is correct: it carries no shape.
fn mark(pts: &mut [SigPt], first: usize, last: usize) {
    let mut stack = vec![(first, last)];
    while let Some((a, b)) = stack.pop() {
        if b <= a + 1 {
            continue;
        }
        let mut worst = 0.0f64;
        let mut worst_i = a;
        for i in (a + 1)..b {
            let d = seg_dist_sq(pts[i], pts[a], pts[b]);
            if d > worst {
                worst = d;
                worst_i = i;
            }
        }
        if worst > 0.0 {
            pts[worst_i].sig = worst;
            stack.push((a, worst_i));
            stack.push((worst_i, b));
        }
    }
}

/// Squared distance from `p` to the **segment** `a -> b`.
///
/// The segment, not the infinite line it lies on. The two agree wherever the foot
/// of the perpendicular is between the endpoints, which is the only case an open
/// polyline's first pass produces -- but a closed ring's first chord is degenerate
/// (`a == b`), and past that the chords are arbitrary, so a vertex beyond an
/// endpoint would otherwise be scored by a perpendicular that misses the segment
/// entirely. This is geojson-vt's `getSqSegDist`.
fn seg_dist_sq(p: SigPt, a: SigPt, b: SigPt) -> f64 {
    let (mut x, mut y) = (a.x, a.y);
    let (dx, dy) = (b.x - x, b.y - y);
    let len_sq = dx * dx + dy * dy;
    if len_sq > 0.0 {
        let t = ((p.x - x) * dx + (p.y - y) * dy) / len_sq;
        if t > 1.0 {
            x = b.x;
            y = b.y;
        } else if t > 0.0 {
            x += dx * t;
            y += dy * t;
        }
    }
    let (dx, dy) = (p.x - x, p.y - y);
    dx * dx + dy * dy
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geom::Pt;

    /// Annotate a path and hand back the vertices a tolerance keeps, as plain pairs.
    fn thin(pts: &[Pt], tolerance: f64) -> Vec<Pt> {
        let mut g = line_of(pts);
        annotate(&mut g);
        let Geometry::Lines(out) = filter(&g, tolerance) else { panic!() };
        match out.first() {
            Some(l) => l.iter().map(|v| v.xy()).collect(),
            None => Vec::new(),
        }
    }

    fn line_of(pts: &[Pt]) -> Geometry<SigPt> {
        Geometry::Lines(vec![pts.iter().map(|&(x, y)| SigPt::new(x, y)).collect()])
    }

    fn ring_of(pts: &[Pt]) -> Vec<SigPt> {
        pts.iter().map(|&(x, y)| SigPt::new(x, y)).collect()
    }

    /// Annotate one ring on its own and filter it, as the polygon path would.
    fn thin_ring(pts: &[Pt], tolerance: f64) -> Option<Vec<Pt>> {
        let mut ring = ring_of(pts);
        annotate_path(&mut ring);
        filter_ring(&ring, tolerance * tolerance)
            .map(|r| r.iter().map(|v| v.xy()).collect())
    }

    #[test]
    fn a_collinear_run_collapses_to_its_endpoints() {
        let line = [(0.0, 0.0), (10.0, 0.0), (20.0, 0.0), (30.0, 0.0), (40.0, 0.0)];
        assert_eq!(thin(&line, 1.0), vec![(0.0, 0.0), (40.0, 0.0)]);
    }

    #[test]
    fn a_deviation_above_tolerance_is_kept_and_below_is_dropped() {
        // The middle vertex sits 5 units off the chord.
        let line = [(0.0, 0.0), (50.0, 5.0), (100.0, 0.0)];
        assert_eq!(thin(&line, 1.0), line.to_vec(), "5 > 1, so it stays");
        assert_eq!(
            thin(&line, 10.0),
            vec![(0.0, 0.0), (100.0, 0.0)],
            "5 < 10, so it goes"
        );
    }

    #[test]
    fn endpoints_are_never_removed() {
        // Even a line whose interior is entirely within tolerance keeps its ends.
        let line = [(0.0, 0.0), (1.0, 0.0), (2.0, 0.0), (100.0, 0.0)];
        let out = thin(&line, 50.0);
        assert_eq!(out.first(), Some(&(0.0, 0.0)));
        assert_eq!(out.last(), Some(&(100.0, 0.0)));
        assert_eq!(out.len(), 2);
    }

    #[test]
    fn thinning_is_monotonic_in_the_tolerance() {
        // A zigzag of decreasing amplitude: a larger tolerance must never keep more
        // vertices than a smaller one.
        let line: Vec<Pt> = (0..40)
            .map(|i| {
                let a = (40 - i) as f64;
                ((i * 10) as f64, if i % 2 == 0 { a } else { -a })
            })
            .collect();
        let mut prev = usize::MAX;
        for tol in [0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0] {
            let n = thin(&line, tol).len();
            assert!(
                n <= prev,
                "tolerance {tol} kept {n} vertices, more than the {prev} before it"
            );
            prev = n;
        }
        // And the extremes behave.
        assert_eq!(thin(&line, 0.0), line, "no tolerance, no change");
        assert_eq!(thin(&line, 1e9).len(), 2, "everything but the ends");
    }

    #[test]
    fn thinning_never_adds_a_vertex_or_reorders_them() {
        let line: Vec<Pt> = (0..50).map(|i| ((i * 7) as f64, ((i * i) % 23) as f64)).collect();
        for tol in [1.0, 3.0, 9.0] {
            let out = thin(&line, tol);
            assert!(out.len() <= line.len());
            // The output is a subsequence of the input.
            let mut it = line.iter();
            for p in &out {
                assert!(it.any(|q| q == p), "{p:?} is not in the input, or is out of order");
            }
        }
    }

    /// The property the whole design exists for. Two rings that share an edge --
    /// which is what a clip against a tile boundary produces -- must agree on it
    /// vertex for vertex after filtering, whatever else happens to either ring.
    #[test]
    fn a_vertex_shared_between_two_rings_is_kept_or_dropped_in_both() {
        use crate::clip::clip_polygon;
        use crate::geom::Rect;

        let rect = Rect { min_x: 0.0, min_y: 0.0, max_x: 100.0, max_y: 100.0 };
        // An exterior and a hole that both hang out over the eastern boundary, so
        // the clip gives them a shared run of vertices along x = 100.
        let exterior: Vec<Pt> = vec![
            (10.0, 10.0),
            (150.0, 10.0),
            (150.0, 90.0),
            (10.0, 90.0),
            (10.0, 10.0),
        ];
        let hole: Vec<Pt> = vec![
            (40.0, 30.0),
            (150.0, 30.0),
            (150.0, 70.0),
            (40.0, 70.0),
            (40.0, 30.0),
        ];
        let mut g = Geometry::Polygons(vec![vec![ring_of(&exterior), ring_of(&hole)]]);
        annotate(&mut g);
        let Geometry::Polygons(polys) = &g else { panic!() };
        let clipped = clip_polygon(&polys[0], &rect);
        assert_eq!(clipped.len(), 2, "exterior and hole both survive the clip");

        // Every vertex the clip put on the eastern boundary appears in both rings.
        let on_edge = |rings: &Vec<Vec<SigPt>>| -> Vec<Pt> {
            rings
                .iter()
                .flatten()
                .map(|v| v.xy())
                .filter(|(x, _)| *x == 100.0)
                .collect()
        };
        for tol in [0.5, 1.0, 8.0, 64.0] {
            let g = Geometry::Polygons(vec![clipped.clone()]);
            let Geometry::Polygons(out) = filter(&g, tol) else { panic!() };
            let kept = on_edge(&out[0]);
            let before = on_edge(&vec![clipped[0].clone(), clipped[1].clone()]);
            assert_eq!(
                kept, before,
                "tolerance {tol} moved a boundary vertex: {kept:?} vs {before:?}"
            );
        }
    }

    /// Clip-introduced vertices carry `ALWAYS`, so no tolerance can remove them --
    /// which is the only thing holding two clipped rings to the same boundary edge.
    #[test]
    fn boundary_vertices_survive_the_coarsest_tolerance() {
        use crate::clip::clip_ring;
        use crate::geom::Rect;

        let rect = Rect { min_x: 0.0, min_y: 0.0, max_x: 10.0, max_y: 10.0 };
        let mut ring = ring_of(&[(-5.0, -5.0), (15.0, -5.0), (15.0, 15.0), (-5.0, 15.0), (-5.0, -5.0)]);
        annotate_path(&mut ring);
        let clipped = clip_ring(&ring, &rect);
        // Every surviving vertex is a crossing the clipper invented.
        assert!(clipped.iter().all(|v| v.sig == ALWAYS), "{clipped:?}");
        let out = filter_ring(&clipped, 1e18).expect("nothing to drop");
        assert_eq!(out.len(), clipped.len());
    }

    #[test]
    fn a_closed_ring_stays_closed() {
        // A square with a redundant midpoint on each side.
        let ring = [
            (0.0, 0.0),
            (50.0, 0.0),
            (100.0, 0.0),
            (100.0, 50.0),
            (100.0, 100.0),
            (50.0, 100.0),
            (0.0, 100.0),
            (0.0, 50.0),
            (0.0, 0.0),
        ];
        let out = thin_ring(&ring, 1.0).expect("a square survives");
        assert_eq!(out.first(), out.last(), "closure preserved");
        assert_eq!(out.len(), 5, "four corners plus the close: {out:?}");
    }

    #[test]
    fn an_unclosed_ring_stays_unclosed() {
        let ring = [(0.0, 0.0), (50.0, 0.0), (100.0, 0.0), (100.0, 100.0), (0.0, 100.0)];
        let out = thin_ring(&ring, 1.0).unwrap();
        assert_ne!(out.first(), out.last());
        assert_eq!(out.len(), 4, "the collinear midpoint goes: {out:?}");
    }

    #[test]
    fn a_ring_is_dropped_rather_than_collapsed_below_four_vertices() {
        // A very thin sliver: at a large tolerance every vertex but the two ends is
        // sub-tolerance, which leaves 2 and encloses no area. Drop it instead.
        let ring = [(0.0, 0.0), (500.0, 1.0), (1000.0, 0.0), (500.0, -1.0), (0.0, 0.0)];
        assert_eq!(thin_ring(&ring, 1000.0), None, "a collapsed ring is dropped");
        // At a tolerance it can actually survive, it does.
        assert!(thin_ring(&ring, 0.5).is_some());
    }

    #[test]
    fn a_ring_that_is_already_too_small_is_rejected() {
        assert_eq!(thin_ring(&[], 1.0), None);
        assert_eq!(thin_ring(&[(0.0, 0.0)], 1.0), None);
        assert_eq!(thin_ring(&[(0.0, 0.0), (1.0, 1.0)], 1.0), None);
        // Closed, but only two distinct vertices.
        assert_eq!(thin_ring(&[(0.0, 0.0), (1.0, 1.0), (0.0, 0.0)], 1.0), None);
        // The minimum viable closed ring, at zero tolerance.
        let tri = [(0.0, 0.0), (10.0, 0.0), (0.0, 10.0), (0.0, 0.0)];
        assert!(thin_ring(&tri, 0.0).is_some());
    }

    #[test]
    fn a_ring_at_the_floor_is_dropped_only_when_it_is_below_tolerance() {
        // Four vertices is the floor, but filtering can still remove the two
        // interior ones: whether the ring survives is a question about its size,
        // not its vertex count.
        let ring = [(0.0, 0.0), (1000.0, 0.0), (0.0, 1000.0), (0.0, 0.0)];
        assert_eq!(
            thin_ring(&ring, 1.0),
            Some(ring.to_vec()),
            "1000 units across, 1 unit tolerance"
        );
        assert_eq!(thin_ring(&ring, 1e9), None, "sub-tolerance, so dropped not collapsed");
    }

    #[test]
    fn the_geometry_entry_point_drops_lines_that_fall_below_two_vertices() {
        let mut g = Geometry::Lines(vec![
            ring_of(&[(0.0, 0.0), (10.0, 0.0), (20.0, 0.0)]),
            ring_of(&[(5.0, 5.0)]),
        ]);
        annotate(&mut g);
        let Geometry::Lines(out) = filter(&g, 1.0) else { panic!() };
        assert_eq!(out.len(), 1);
        assert_eq!(
            out[0].iter().map(|v| v.xy()).collect::<Vec<_>>(),
            vec![(0.0, 0.0), (20.0, 0.0)]
        );
    }

    #[test]
    fn losing_the_exterior_ring_drops_the_whole_polygon() {
        // A 1000x2 sliver and a 100000-unit square, at a 1000-unit tolerance: the
        // sliver is entirely sub-tolerance and the square is nowhere near it.
        let sliver = [(0.0, 0.0), (500.0, 1.0), (1000.0, 0.0), (500.0, -1.0), (0.0, 0.0)];
        let fat = [
            (0.0, 0.0),
            (100_000.0, 0.0),
            (100_000.0, 100_000.0),
            (0.0, 100_000.0),
            (0.0, 0.0),
        ];
        // Exterior collapses: the polygon goes, hole and all.
        let mut g = Geometry::Polygons(vec![vec![ring_of(&sliver), ring_of(&fat)]]);
        annotate(&mut g);
        let Geometry::Polygons(out) = filter(&g, 1000.0) else { panic!() };
        assert!(out.is_empty(), "{out:?}");
        // A hole collapsing leaves the exterior intact.
        let mut g = Geometry::Polygons(vec![vec![ring_of(&fat), ring_of(&sliver)]]);
        annotate(&mut g);
        let Geometry::Polygons(out) = filter(&g, 1000.0) else { panic!() };
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].len(), 1, "the hole was dropped, the exterior kept");
    }

    #[test]
    fn points_pass_through_untouched() {
        // Thinning points is the drop policy's job, not simplification's.
        let mut g = Geometry::Points(ring_of(&[(0.0, 0.0), (1.0, 1.0), (2.0, 2.0)]));
        annotate(&mut g);
        assert_eq!(filter(&g, 1e9), g);
    }

    #[test]
    fn a_zero_tolerance_is_the_identity() {
        let mut g = line_of(&[(0.0, 0.0), (1.0, 0.0), (2.0, 0.0), (3.0, 0.0)]);
        annotate(&mut g);
        assert_eq!(filter(&g, 0.0), g);
        assert_eq!(filter(&g, -1.0), g, "a negative tolerance is not a licence");
    }

    #[test]
    fn the_tolerance_policy_spares_the_deepest_zoom() {
        assert_eq!(tolerance_for(14, 14, 1.0), 0.0, "max zoom keeps full detail");
        assert_eq!(tolerance_for(15, 14, 1.0), 0.0, "and so does anything past it");
        assert_eq!(tolerance_for(13, 14, 1.0), DEFAULT_TOLERANCE);
        assert_eq!(tolerance_for(6, 14, 4.0), DEFAULT_TOLERANCE * 4.0);
    }

    #[test]
    fn the_distance_is_to_the_segment_not_the_line_it_lies_on() {
        let p = |x: f64, y: f64| SigPt::new(x, y);
        // Beyond `b`: the perpendicular to the infinite line would say 0.
        assert_eq!(seg_dist_sq(p(20.0, 0.0), p(0.0, 0.0), p(10.0, 0.0)), 100.0);
        assert_eq!(seg_dist_sq(p(-5.0, 0.0), p(0.0, 0.0), p(10.0, 0.0)), 25.0);
        // Between them, it is the perpendicular.
        assert_eq!(seg_dist_sq(p(5.0, 3.0), p(0.0, 0.0), p(10.0, 0.0)), 9.0);
        assert_eq!(seg_dist_sq(p(5.0, 0.0), p(0.0, 0.0), p(10.0, 0.0)), 0.0);
        // A degenerate chord, which is what a closed ring's first pass measures
        // against, is point-to-point.
        assert_eq!(seg_dist_sq(p(5.0, 5.0), p(0.0, 0.0), p(0.0, 0.0)), 50.0);
    }

    #[test]
    fn significance_is_scale_invariant() {
        // The same shape at two scales must rank its vertices the same way, which is
        // what lets one annotation pass serve every zoom.
        let shape: Vec<Pt> = vec![
            (0.0, 0.0),
            (10.0, 3.0),
            (20.0, 1.0),
            (30.0, 12.0),
            (40.0, 2.0),
            (50.0, 0.0),
        ];
        let mut small = line_of(&shape);
        let mut big = line_of(&shape.iter().map(|(x, y)| (x * 8.0, y * 8.0)).collect::<Vec<_>>());
        annotate(&mut small);
        annotate(&mut big);
        let sig = |g: &Geometry<SigPt>| -> Vec<f64> {
            let Geometry::Lines(l) = g else { panic!() };
            l[0].iter().map(|v| v.sig).collect()
        };
        for (a, b) in sig(&small).iter().zip(sig(&big)) {
            if a.is_finite() {
                assert!((b - a * 64.0).abs() < 1e-9, "{a} scaled to {b}, not {}", a * 64.0);
            } else {
                assert!(b.is_infinite());
            }
        }
        // And a threshold scaled with them keeps the same vertices.
        let kept = |g: &Geometry<SigPt>, tol: f64| -> usize {
            let Geometry::Lines(l) = filter(g, tol) else { panic!() };
            l[0].len()
        };
        for tol in [0.5, 1.0, 2.0, 4.0] {
            assert_eq!(kept(&small, tol), kept(&big, tol * 8.0), "at tolerance {tol}");
        }
    }
}
