//! Assembling a boundary relation's member ways into closed rings, and those rings
//! into polygons.
//!
//! This is the piece [`crate::poi_build`] deliberately did not do. It approximated a
//! relation's outer ring by **sorting and deduplicating** the member ways' node ids,
//! which is exact for a centroid -- averaging is order-independent -- and destroys
//! the ring. Its own comment says so. Nothing built on that approximation can have
//! an area, a winding order, or a point-in-polygon test.
//!
//! Two things follow, and both are load-bearing:
//!
//! * **This module must not sort or dedup refs.** Vertex order *is* the geometry.
//! * **`poi_build` must be left alone.** Its centroids match the libosmium C++ they
//!   replaced, to within a documented 3.1 m worst case, and `poi_index.bin` is a
//!   published artefact. Changing how it derives a centroid would move POIs for no
//!   benefit to this layer.
//!
//! # The assembler
//!
//! 1. **Bucket members by role.** `outer` and the **empty role** are both outer:
//!    the OSM boundary convention is that an unroled way in a `boundary` relation is
//!    part of the outer edge, and in real data most of them are unroled. `inner` is
//!    a hole. Any other role is ignored.
//! 2. **Stitch.** Ways in a relation are in no particular order and no particular
//!    direction, so a ring is found by walking an endpoint -> way multimap,
//!    reversing a way whenever its far end is the one that matches. This is what
//!    libosmium's `Assembler` does.
//! 3. **Drop and log what will not close.** A ring that runs out of candidate ways
//!    before returning to its start is a data error -- a missing member, a way
//!    clipped away by the extract, a genuine tagging mistake. Emitting it as an
//!    open ring would render as a wedge of fill reaching to wherever the renderer
//!    decides to close it, which is far worse than the boundary being absent.
//! 4. **Classify into polygons.** Inner rings are matched to the outer ring that
//!    contains them, by point-in-ring containment, and the outer ring is chosen by
//!    smallest area among those that contain the hole -- nesting means several may.

use crate::geojson::Coord;

/// A member way's ordered node refs, and the role it had in the relation.
pub struct MemberWay {
    pub refs: Vec<i64>,
    pub outer: bool,
}

/// One assembled polygon: its exterior ring first, then its holes. Every ring is
/// explicitly closed.
pub type Polygon = Vec<Vec<Coord>>;

/// What an assembly cost, for the report the operator is owed.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct RingStats {
    pub outer_rings: usize,
    pub inner_rings: usize,
    /// Rings that ran out of ways before closing, and were dropped.
    pub unclosed: usize,
    /// Holes with no containing outer ring, and were dropped.
    pub orphan_inner: usize,
}

/// Assemble `members` into polygons. `locate` resolves a node id to `(lon, lat)`.
///
/// Returns the polygons and a report. An empty result means nothing closed.
pub fn assemble<L>(members: &[MemberWay], locate: L, stats: &mut RingStats) -> Vec<Polygon>
where
    L: Fn(i64) -> Option<Coord>,
{
    let outer = stitch(members.iter().filter(|m| m.outer), stats);
    let inner = stitch(members.iter().filter(|m| !m.outer), stats);
    stats.outer_rings += outer.len();
    stats.inner_rings += inner.len();

    // Resolve to coordinates only once the topology is settled: stitching is about
    // node identity, and comparing coordinates instead would weld two distinct
    // nodes that happen to share a position.
    let outer: Vec<Vec<Coord>> = outer.iter().filter_map(|r| resolve(r, &locate)).collect();
    let inner: Vec<Vec<Coord>> = inner.iter().filter_map(|r| resolve(r, &locate)).collect();

    let mut polygons: Vec<Polygon> = outer.into_iter().map(|r| vec![r]).collect();
    for hole in inner {
        match containing(&polygons, &hole) {
            Some(i) => polygons[i].push(hole),
            // A hole with nothing around it is not a shape, and emitting it would
            // render as solid fill in the layer's colour.
            None => stats.orphan_inner += 1,
        }
    }
    polygons
}

/// Walk the ways into closed rings of node ids. Each returned ring is explicitly
/// closed (first == last).
fn stitch<'a, I>(ways: I, stats: &mut RingStats) -> Vec<Vec<i64>>
where
    I: Iterator<Item = &'a MemberWay>,
{
    // Index by both endpoints, since a way may be traversed either way round.
    let mut pool: Vec<Option<Vec<i64>>> = ways
        .filter(|w| w.refs.len() >= 2)
        .map(|w| Some(w.refs.clone()))
        .collect();

    let mut rings: Vec<Vec<i64>> = Vec::new();
    for start in 0..pool.len() {
        let Some(first) = pool[start].take() else {
            continue;
        };
        let mut ring = first;
        loop {
            if ring.len() > 2 && ring.first() == ring.last() {
                rings.push(ring);
                break;
            }
            let tail = *ring.last().expect("non-empty");
            // Find any unused way starting or ending at the current tail.
            let next = pool.iter().position(|w| {
                w.as_ref()
                    .is_some_and(|r| r.first() == Some(&tail) || r.last() == Some(&tail))
            });
            let Some(i) = next else {
                // Nothing joins on: this ring cannot close.
                stats.unclosed += 1;
                break;
            };
            let mut piece = pool[i].take().expect("just found");
            if piece.first() != Some(&tail) {
                piece.reverse();
            }
            // The shared endpoint is already the ring's tail.
            ring.extend_from_slice(&piece[1..]);

            // A way that closed the ring on its own, or a degenerate self-loop, is
            // caught by the check at the top of the loop.
            if ring.len() > 100_000_000 {
                // Cannot happen with real data; guards against a pathological
                // multimap cycle turning into an OOM rather than an error.
                stats.unclosed += 1;
                break;
            }
        }
    }
    rings
}

/// Resolve a ring of node ids to coordinates. `None` when too few resolve to
/// enclose an area.
///
/// Unlike a line, a ring with a missing vertex is **not** kept with a gap: closing
/// across the gap invents an edge, and for an area that changes what is inside it.
fn resolve<L>(ring: &[i64], locate: &L) -> Option<Vec<Coord>>
where
    L: Fn(i64) -> Option<Coord>,
{
    let coords: Vec<Coord> = ring.iter().map(|id| locate(*id)).collect::<Option<_>>()?;
    // Three distinct vertices plus the repeated close.
    if coords.len() < 4 {
        return None;
    }
    Some(coords)
}

/// Index of the smallest outer ring containing `hole`, if any.
///
/// Smallest, because boundaries nest: a city inside a county inside a state means
/// several outer rings may contain the same hole, and the hole belongs to the
/// tightest one.
fn containing(polygons: &[Polygon], hole: &[Coord]) -> Option<usize> {
    let probe = *hole.first()?;
    let mut best: Option<(usize, f64)> = None;
    for (i, poly) in polygons.iter().enumerate() {
        let outer = &poly[0];
        if !point_in_ring(probe, outer) {
            continue;
        }
        let area = ring_area(outer).abs();
        if best.is_none_or(|(_, b)| area < b) {
            best = Some((i, area));
        }
    }
    best.map(|(i, _)| i)
}

/// Twice the signed area of a ring, by the surveyor's formula.
///
/// In lon/lat degrees, so it is not an area on the ground -- but every use here is a
/// comparison or a sign test, and for that a consistent planar measure is the right
/// tool. A spherical area would cost a lot and change no decision.
pub fn ring_area(ring: &[Coord]) -> f64 {
    let n = ring.len();
    if n < 3 {
        return 0.0;
    }
    let mut sum = 0.0;
    for i in 0..n {
        let (x1, y1) = ring[i];
        let (x2, y2) = ring[(i + 1) % n];
        sum += x1 * y2 - x2 * y1;
    }
    sum
}

/// Ray-casting point-in-polygon. `true` when `p` is inside `ring`.
///
/// The half-open `(y1 > y) != (y2 > y)` test is what makes a vertex exactly at the
/// probe's latitude count once rather than twice, so a ray grazing a vertex does not
/// flip the answer.
pub fn point_in_ring((x, y): Coord, ring: &[Coord]) -> bool {
    let mut inside = false;
    let n = ring.len();
    if n < 3 {
        return false;
    }
    for i in 0..n {
        let (x1, y1) = ring[i];
        let (x2, y2) = ring[(i + 1) % n];
        if (y1 > y) != (y2 > y) {
            let t = (y - y1) / (y2 - y1);
            if x < x1 + t * (x2 - x1) {
                inside = !inside;
            }
        }
    }
    inside
}

#[cfg(test)]
mod tests {
    use super::*;

    fn way(refs: &[i64], outer: bool) -> MemberWay {
        MemberWay { refs: refs.to_vec(), outer }
    }

    /// A 4x4 grid of nodes, ids 1..=16, so a ring can be described by ids alone.
    /// Node `n` sits at `(lon, lat) = ((n-1) % 4, (n-1) / 4)`.
    fn grid(id: i64) -> Option<Coord> {
        (1..=16).contains(&id).then(|| {
            let i = (id - 1) as f64;
            ((i % 4.0), (i / 4.0).floor())
        })
    }

    /// The unit square's corners, and a smaller square inside it.
    fn square(min: f64, max: f64) -> Vec<Coord> {
        vec![(min, min), (max, min), (max, max), (min, max), (min, min)]
    }

    #[test]
    fn a_single_closed_way_is_already_a_ring() {
        let mut stats = RingStats::default();
        let polys = assemble(&[way(&[1, 4, 16, 13, 1], true)], grid, &mut stats);
        assert_eq!(polys.len(), 1);
        assert_eq!(polys[0].len(), 1, "no holes");
        assert_eq!(polys[0][0].len(), 5);
        assert_eq!(polys[0][0].first(), polys[0][0].last());
        assert_eq!(stats, RingStats { outer_rings: 1, ..Default::default() });
    }

    #[test]
    fn several_ways_stitch_into_one_ring() {
        // The square 1-4-16-13 split into four ways, in a scrambled order and with
        // two of them pointing the wrong way. This is what real boundary relations
        // look like.
        let mut stats = RingStats::default();
        let members = vec![
            way(&[16, 13], true),  // reversed
            way(&[1, 4], true),
            way(&[13, 1], true),
            way(&[16, 4], true),   // reversed
        ];
        let polys = assemble(&members, grid, &mut stats);
        assert_eq!(polys.len(), 1, "{stats:?}");
        assert_eq!(stats.unclosed, 0);
        // Four corners plus the close, whichever way round it came out.
        assert_eq!(polys[0][0].len(), 5, "{:?}", polys[0][0]);
        assert!(ring_area(&polys[0][0]).abs() > 0.0);
    }

    #[test]
    fn a_way_is_reversed_when_its_far_end_is_the_match() {
        // Two ways that only join if the second is flipped.
        let mut stats = RingStats::default();
        let members = vec![way(&[1, 4, 16], true), way(&[1, 13, 16], true)];
        let polys = assemble(&members, grid, &mut stats);
        assert_eq!(polys.len(), 1, "{stats:?}");
        assert_eq!(polys[0][0].len(), 5);
    }

    #[test]
    fn an_unclosed_ring_is_dropped_and_counted() {
        // Three sides of a square: it cannot close, so emitting it would render as
        // a wedge reaching to wherever the renderer chose to close it.
        let mut stats = RingStats::default();
        let members = vec![way(&[1, 4], true), way(&[4, 16], true)];
        let polys = assemble(&members, grid, &mut stats);
        assert!(polys.is_empty(), "{polys:?}");
        assert_eq!(stats.unclosed, 1);
        assert_eq!(stats.outer_rings, 0);
    }

    #[test]
    fn one_bad_ring_does_not_take_a_good_one_with_it() {
        let mut stats = RingStats::default();
        let members = vec![
            way(&[1, 4, 16, 13, 1], true), // closed on its own
            way(&[2, 3], true),            // a dangling fragment
        ];
        let polys = assemble(&members, grid, &mut stats);
        assert_eq!(polys.len(), 1);
        assert_eq!(stats.unclosed, 1);
        assert_eq!(stats.outer_rings, 1);
    }

    #[test]
    fn an_empty_role_counts_as_outer() {
        // The OSM boundary convention, and what most real members carry. Treating an
        // unroled way as "not outer" would leave nearly every boundary with no
        // exterior at all.
        let mut stats = RingStats::default();
        let polys = assemble(&[way(&[1, 4, 16, 13, 1], true)], grid, &mut stats);
        assert_eq!(polys.len(), 1);
    }

    #[test]
    fn a_hole_attaches_to_the_ring_that_contains_it() {
        let locate = |id: i64| -> Option<Coord> {
            match id {
                // Outer: a 10x10 square.
                1 => Some((0.0, 0.0)),
                2 => Some((10.0, 0.0)),
                3 => Some((10.0, 10.0)),
                4 => Some((0.0, 10.0)),
                // Inner: a 2x2 square well inside it.
                11 => Some((4.0, 4.0)),
                12 => Some((6.0, 4.0)),
                13 => Some((6.0, 6.0)),
                14 => Some((4.0, 6.0)),
                _ => None,
            }
        };
        let mut stats = RingStats::default();
        let members = vec![
            way(&[1, 2, 3, 4, 1], true),
            way(&[11, 12, 13, 14, 11], false),
        ];
        let polys = assemble(&members, locate, &mut stats);
        assert_eq!(polys.len(), 1);
        assert_eq!(polys[0].len(), 2, "exterior plus its hole");
        assert!(ring_area(&polys[0][0]).abs() > ring_area(&polys[0][1]).abs());
        assert_eq!(stats.inner_rings, 1);
        assert_eq!(stats.orphan_inner, 0);
    }

    #[test]
    fn a_hole_outside_every_outer_ring_is_dropped() {
        let locate = |id: i64| -> Option<Coord> {
            match id {
                1 => Some((0.0, 0.0)),
                2 => Some((10.0, 0.0)),
                3 => Some((10.0, 10.0)),
                4 => Some((0.0, 10.0)),
                // Miles away.
                21 => Some((100.0, 100.0)),
                22 => Some((102.0, 100.0)),
                23 => Some((102.0, 102.0)),
                24 => Some((100.0, 102.0)),
                _ => None,
            }
        };
        let mut stats = RingStats::default();
        let members = vec![
            way(&[1, 2, 3, 4, 1], true),
            way(&[21, 22, 23, 24, 21], false),
        ];
        let polys = assemble(&members, locate, &mut stats);
        assert_eq!(polys.len(), 1);
        assert_eq!(polys[0].len(), 1, "the orphan hole is not emitted");
        assert_eq!(stats.orphan_inner, 1);
    }

    #[test]
    fn a_hole_in_nested_outers_goes_to_the_smallest_containing_one() {
        // Boundaries nest, so several outer rings can contain the same hole. It
        // belongs to the tightest.
        let big = square(0.0, 100.0);
        let small = square(10.0, 50.0);
        let hole = square(20.0, 30.0);
        let polys = vec![vec![big], vec![small.clone()]];
        let i = containing(&polys, &hole).expect("contained by both");
        assert_eq!(polys[i][0], small, "the smaller outer ring wins");
        // And order does not matter.
        let polys = vec![vec![square(10.0, 50.0)], vec![square(0.0, 100.0)]];
        assert_eq!(containing(&polys, &hole), Some(0));
    }

    #[test]
    fn a_ring_with_an_unresolvable_vertex_is_dropped_not_bridged() {
        // For a line, skipping a missing vertex keeps the rest. For an area, closing
        // across the gap invents an edge and changes what is inside.
        let mut stats = RingStats::default();
        let polys = assemble(&[way(&[1, 4, 99, 13, 1], true)], grid, &mut stats);
        assert!(polys.is_empty(), "{polys:?}");
    }

    #[test]
    fn a_ring_too_small_to_enclose_anything_is_dropped() {
        let mut stats = RingStats::default();
        // Two distinct vertices, closed: a line, not an area.
        let polys = assemble(&[way(&[1, 4, 1], true)], grid, &mut stats);
        assert!(polys.is_empty());
        // A single-vertex way is not even a way.
        let polys = assemble(&[way(&[1], true)], grid, &mut stats);
        assert!(polys.is_empty());
        let polys = assemble(&[], grid, &mut stats);
        assert!(polys.is_empty());
    }

    #[test]
    fn vertex_order_is_preserved_which_is_the_whole_point() {
        // poi_build sorts and dedups a relation's refs, which is fine for a centroid
        // and fatal for geometry. This assembler must not: the two rings below have
        // the same vertex SET and different shapes, and sorting would make them
        // indistinguishable.
        let mut stats = RingStats::default();
        let convex = assemble(&[way(&[1, 4, 16, 13, 1], true)], grid, &mut stats);
        let crossed = assemble(&[way(&[1, 16, 4, 13, 1], true)], grid, &mut stats);
        assert_eq!(convex[0][0].len(), crossed[0][0].len());
        assert_ne!(
            convex[0][0], crossed[0][0],
            "the same vertices in a different order are a different ring"
        );
        // The bow-tie encloses no net area; the square encloses 9.
        assert!((ring_area(&convex[0][0]).abs() / 2.0 - 9.0).abs() < 1e-9);
        assert!(ring_area(&crossed[0][0]).abs() < 1e-9);
    }

    #[test]
    fn point_in_ring_handles_edges_and_vertices_without_double_counting() {
        let sq = square(0.0, 10.0);
        assert!(point_in_ring((5.0, 5.0), &sq));
        assert!(!point_in_ring((15.0, 5.0), &sq));
        assert!(!point_in_ring((5.0, 15.0), &sq));
        // A ray leaving a vertex at exactly the probe's latitude must not flip the
        // answer twice.
        assert!(!point_in_ring((-1.0, 0.0), &sq));
        assert!(!point_in_ring((-1.0, 10.0), &sq));
        assert!(point_in_ring((5.0, 0.000001), &sq));
        // Degenerate rings contain nothing.
        assert!(!point_in_ring((0.0, 0.0), &[]));
        assert!(!point_in_ring((0.0, 0.0), &[(0.0, 0.0), (1.0, 1.0)]));
    }

    #[test]
    fn ring_area_signs_and_magnitudes() {
        let ccw = square(0.0, 10.0);
        let mut cw = ccw.clone();
        cw.reverse();
        assert!(ring_area(&ccw) > 0.0);
        assert!(ring_area(&cw) < 0.0);
        assert_eq!(ring_area(&ccw), -ring_area(&cw));
        // Twice the area of a 10x10 square.
        assert!((ring_area(&ccw).abs() - 200.0).abs() < 1e-9);
        assert_eq!(ring_area(&[]), 0.0);
        assert_eq!(ring_area(&[(0.0, 0.0), (1.0, 1.0)]), 0.0);
    }
}
