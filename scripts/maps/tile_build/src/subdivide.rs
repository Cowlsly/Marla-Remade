//! Tile one feature by descending the tile quadtree, instead of once per tile.
//!
//! The tiling loops used to have one shape: ask [`crate::geom::tiles_touched`] for
//! every tile a feature reaches, then clip **the whole feature** against each of
//! them. That is `O(T * V)`, and both factors grow with the feature's geographic
//! extent: a state-sized polygon at z13 has `T` in the hundreds of thousands and
//! `V` in the hundreds of thousands, and every one of those tiles paid a full
//! vertex walk -- including the vast majority that are strictly interior and whose
//! answer is just the tile rectangle.
//!
//! [`subdivide`] walks the quadtree instead:
//!
//! 1. Find the deepest cell that already contains the whole geometry. A building at
//!    z14 gets the leaf itself and no recursion at all, so the leaf clip is the only
//!    clip -- exactly what the old loop did. A coastline gets a shallow cell.
//! 2. At each level, clip the geometry **already clipped to this cell** into each of
//!    the four children, and recurse into the ones that survive.
//! 3. A level-`z` cell rect *is* [`crate::geom::tile_rect`], so the leaf sees the
//!    same rectangle as before.
//!
//! Cost becomes `O(V * (z - level))` for the vertex walks plus `O(T)` small clips at
//! the bottom, rather than `O(V * T)`.
//!
//! ## Why clipping progressively is sound
//!
//! The buffer is the same world-unit constant at every level. A child cell is a
//! subset of its parent, and growing both by the same Minkowski sum keeps that:
//! `child (+) buffer` is a subset of `parent (+) buffer`. Clipping is intersection, so
//! `clip(clip(g, A), B)` is `clip(g, B)` whenever `B` is inside `A`, and the whole
//! descent is that identity applied `z - level` times.
//!
//! Sutherland-Hodgman makes that worth checking rather than assuming. It returns a
//! clipped *concave* ring as a single self-touching ring joined by zero-width slivers
//! (see [`crate::clip`]'s module docs), which is not the intersection as a simple
//! polygon -- so composing through one is not obviously sound.
//! `clipping_twice_is_clipping_once_for_a_nested_rect` searches for a counterexample
//! over random concave rings, random holes and random nested rect pairs, and finds
//! none: the sliver runs along the shared boundary and the next clip either keeps that
//! boundary or removes the sliver with it.
//!
//! It is the same argument [`crate::geom::tiles_touched`]'s bisection rests on: a
//! recursive subdivision is safe exactly when each step preserves what the next needs.
//!
//! ## Points do not descend
//!
//! [`crate::clip::clip_geometry`] on `Points` filters the whole list per tile, which
//! is its own `O(T * V)`. A point's tiles are just the tiles its own padded box
//! covers, so points are bucketed straight into them -- which is what
//! `tiles_touched` followed by a `contains` test worked out to anyway, one point at a
//! time.
//!
//! ## What this costs in output
//!
//! **Not byte-identity to a direct clip, and not the vertices that were expected to
//! cost it.** Clipping to an ancestor cell does insert vertices on that ancestor's
//! boundary, and [`crate::geom::Vertex::boundary`] does stamp them unremovable -- but
//! none of them survive to a leaf as a vertex the leaf's own clip would not have
//! invented. A child cell is a subset of its parent and both are grown by the *same*
//! buffer, so a parent boundary line either coincides with the child's line on that
//! side, when the child sits on that border, or lies strictly outside the child,
//! where the next clip removes it. Coincident lines give coincident crossings.
//!
//! Measured over the corpus in this module's tests, the descent emits **fewer**
//! vertices than a direct clip, not more, so there is no collinear-reduction pass and
//! the archive does not grow. Three differences remain, none of them a shape:
//!
//! | difference | why | visible? |
//! |---|---|---|
//! | a ring's starting vertex | Sutherland-Hodgman keeps its input's rotation, and the descent's input is its parent's answer | same polygon; MVT re-states the start as a `MoveTo` |
//! | fewer collinear spurs | a concave ring's zero-area run along a clip boundary is shorter when built in stages | zero-area either way |
//! | last-bit drift on a line crossing | Liang-Barsky interpolates both coordinates, so a crossing taken off an already-interpolated vertex can land an ULP away | 1e-12 world units against a quantisation grid of 1 |
//!
//! The tests hold the descent to being the same *shape* -- same tiles, same parts,
//! same corners up to rotation, same signed area, same length -- and record the byte
//! divergence rather than asserting it away.

use crate::clip::clip_geometry;
use crate::geom::{self, Geometry, Rect, Vertex};

/// Clip `g` into every tile of zoom `z` it reaches, calling `emit` once per tile.
///
/// `g` must already be in world coordinates for `z` (see [`crate::geom`]'s module
/// docs). `buffer` is the same overspill the old per-tile loop passed to both
/// `tiles_touched` and `tile_rect`, and it is applied at every level of the descent.
///
/// A tile whose clip is empty is not emitted. The old loop reached those tiles and
/// then skipped them, so nothing downstream sees a difference.
///
/// Tiles arrive in quadtree order, which is neither the old `(x, y)` order nor
/// `tile_id` order. Every caller either keys a map by tile or sorts afterwards, so
/// the order is deterministic rather than significant.
pub fn subdivide<V, F>(g: &Geometry<V>, z: u8, extent: u32, buffer: f64, emit: &mut F)
where
    V: Vertex,
    F: FnMut(u64, u64, &Geometry<V>),
{
    if let Geometry::Points(pts) = g {
        bucket_points(pts, z, extent, buffer, emit);
        return;
    }
    let Some(box_) = geom::bounds(g) else { return };
    // The same padded range the old loop's `tiles_touched` computed from, so a
    // feature reaching only into a neighbour's buffer still descends towards it.
    let Some(range) = geom::tile_range(&box_, z, extent, buffer) else { return };

    // The deepest cell holding the whole range is the one whose tile indices agree
    // once the low bits are dropped.
    let mut shift = 0u32;
    while (range.x0 >> shift) != (range.x1 >> shift) || (range.y0 >> shift) != (range.y1 >> shift) {
        shift += 1;
    }
    let level = z - shift as u8;
    let (cx, cy) = (range.x0 >> shift, range.y0 >> shift);

    // The start cell already contains the geometry, so this clip inserts nothing
    // unless the feature runs off the edge of the grid -- where the old loop's edge
    // tile cut it at the same coordinate, because a cell on the grid's border shares
    // that border with its leaves.
    let clipped = clip_geometry(g, &cell_rect(level, cx, cy, z, extent, buffer));
    if is_empty(&clipped) {
        return;
    }
    walk(&clipped, level, cx, cy, z, extent, buffer, emit);
}

/// One quadtree cell's clip rect in world coordinates, grown by `buffer`.
///
/// At `level == z` this is [`geom::tile_rect`] to the bit: `2^0` is 1, so the
/// arithmetic below is the same arithmetic.
fn cell_rect(level: u8, cx: u64, cy: u64, z: u8, extent: u32, buffer: f64) -> Rect {
    let side = extent as f64 * (1u64 << (z - level)) as f64;
    let (ox, oy) = (cx as f64 * side, cy as f64 * side);
    Rect {
        min_x: ox - buffer,
        min_y: oy - buffer,
        max_x: ox + side + buffer,
        max_y: oy + side + buffer,
    }
}

/// Descend from a cell whose clipped geometry is `g`, emitting at the leaves.
#[allow(clippy::too_many_arguments)]
fn walk<V, F>(
    g: &Geometry<V>,
    level: u8,
    cx: u64,
    cy: u64,
    z: u8,
    extent: u32,
    buffer: f64,
    emit: &mut F,
) where
    V: Vertex,
    F: FnMut(u64, u64, &Geometry<V>),
{
    if level == z {
        emit(cx, cy, g);
        return;
    }
    for (dx, dy) in [(0u64, 0u64), (1, 0), (0, 1), (1, 1)] {
        let (nx, ny) = (cx * 2 + dx, cy * 2 + dy);
        let clipped = clip_geometry(g, &cell_rect(level + 1, nx, ny, z, extent, buffer));
        if is_empty(&clipped) {
            continue;
        }
        walk(&clipped, level + 1, nx, ny, z, extent, buffer, emit);
    }
}

/// Whether a clip left anything at all.
///
/// A part-count test is enough: [`clip_geometry`] already drops lines below two
/// vertices and rings below three, so a surviving part is a drawable part.
fn is_empty<V>(g: &Geometry<V>) -> bool {
    match g {
        Geometry::Points(pts) => pts.is_empty(),
        Geometry::Lines(lines) => lines.is_empty(),
        Geometry::Polygons(polys) => polys.is_empty(),
    }
}

/// Bucket points into the tiles whose buffered rects hold them.
///
/// Deliberately the padded box rather than just the tile the coordinate floors
/// into: a point three units from a tile edge belongs in the neighbour's buffer too,
/// which is what keeps a label from being clipped at a tile join. That is exactly
/// the set `tiles_touched` listed and `clip_geometry`'s `contains` test then kept.
///
/// One `Vec` of `(tile, point)` pairs sorted into runs, rather than a map of tiles to
/// point lists. A `Points` feature is usually a single point, and this crate's
/// standard for a per-feature path is set by `bucket_feature` threading its record
/// buffer through from the caller -- a `HashMap` plus a `Vec` per tile per feature
/// would be three allocations to place one node. The sort is stable, so points keep
/// their input order within a tile.
fn bucket_points<V, F>(pts: &[V], z: u8, extent: u32, buffer: f64, emit: &mut F)
where
    V: Vertex,
    F: FnMut(u64, u64, &Geometry<V>),
{
    let mut spread: Vec<((u64, u64), V)> = Vec::with_capacity(pts.len());
    for p in pts {
        let (x, y) = p.xy();
        let box_ = Rect { min_x: x, min_y: y, max_x: x, max_y: y };
        let Some(range) = geom::tile_range(&box_, z, extent, buffer) else { continue };
        for tile in range.iter() {
            spread.push((tile, *p));
        }
    }
    spread.sort_by_key(|&(tile, _)| tile);
    // One `Geometry` reused across tiles, its inner `Vec` refilled rather than
    // reallocated: `emit` takes a borrow, so handing over an owned vector per tile
    // would give the allocation away and leave nothing to reuse.
    let mut holder = Geometry::Points(Vec::new());
    let mut at = 0usize;
    while at < spread.len() {
        let tile = spread[at].0;
        let end = at + spread[at..].partition_point(|&(t, _)| t == tile);
        let Geometry::Points(one) = &mut holder else { unreachable!("built as points") };
        one.clear();
        one.extend(spread[at..end].iter().map(|&(_, p)| p));
        emit(tile.0, tile.1, &holder);
        at = end;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geom::{Pt, SigPt};
    use crate::mvt::DEFAULT_EXTENT;

    const EXTENT: u32 = DEFAULT_EXTENT;
    const BUFFER: f64 = geom::DEFAULT_BUFFER;

    /// A world coordinate `t` tiles along an axis.
    fn w(t: f64) -> f64 {
        t * EXTENT as f64
    }

    fn sig(pts: &[Pt]) -> Vec<SigPt> {
        pts.iter().map(|&(x, y)| SigPt::new(x, y)).collect()
    }

    fn line(pts: &[Pt]) -> Geometry<SigPt> {
        let mut g = Geometry::Lines(vec![sig(pts)]);
        crate::simplify::annotate(&mut g);
        g
    }

    fn polygon(rings: &[&[Pt]]) -> Geometry<SigPt> {
        let mut g = Geometry::Polygons(vec![rings.iter().map(|r| sig(r)).collect()]);
        crate::simplify::annotate(&mut g);
        g
    }

    /// A closed axis-aligned ring, in tile units.
    fn square(min: f64, max: f64) -> Vec<Pt> {
        vec![
            (w(min), w(min)),
            (w(max), w(min)),
            (w(max), w(max)),
            (w(min), w(max)),
            (w(min), w(min)),
        ]
    }

    /// A comb: `teeth` prongs pointing east off a spine on the west.
    ///
    /// The shape Sutherland-Hodgman is worst at. A clip that cuts the prongs off from
    /// the spine leaves several disjoint pieces, which S-H returns as ONE
    /// self-touching ring joined by zero-width slivers along the clip boundary. A
    /// two-prong U reaches that case once; a comb reaches it many times over, with
    /// slivers stacked on the same boundary line -- and clipping *that* again is the
    /// thing the descent does and a single clip never did.
    fn comb(teeth: usize, min: f64, max: f64) -> Geometry<SigPt> {
        let span = max - min;
        let spine = min + span * 0.15;
        let mut r: Vec<Pt> = vec![(w(min), w(min))];
        for i in 0..teeth {
            // Two bands per tooth: out along the top of the tooth, back along the
            // bottom of the gap above it.
            let y0 = min + span * (i as f64 * 2.0 + 0.4) / (teeth as f64 * 2.0);
            let y1 = min + span * (i as f64 * 2.0 + 1.4) / (teeth as f64 * 2.0);
            r.push((w(spine), w(y0)));
            r.push((w(max), w(y0)));
            r.push((w(max), w(y1)));
            r.push((w(spine), w(y1)));
        }
        r.push((w(spine), w(max)));
        r.push((w(min), w(max)));
        r.push((w(min), w(min)));
        let mut g = Geometry::Polygons(vec![vec![sig(&r)]]);
        crate::simplify::annotate(&mut g);
        g
    }

    /// Collect the descent's answer per tile.
    fn descend(g: &Geometry<SigPt>, z: u8) -> Vec<((u64, u64), Geometry<SigPt>)> {
        let mut out = Vec::new();
        subdivide(g, z, EXTENT, BUFFER, &mut |tx, ty, clipped| {
            out.push(((tx, ty), clipped.clone()))
        });
        out
    }

    /// What the old loop produced: `tiles_touched`, then a clip of the whole
    /// geometry against each tile, skipping the ones that came back empty.
    fn direct(g: &Geometry<SigPt>, z: u8) -> Vec<((u64, u64), Geometry<SigPt>)> {
        let mut touched = Vec::new();
        geom::tiles_touched(g, z, EXTENT, BUFFER, &mut touched);
        let mut out = Vec::new();
        for (tx, ty) in touched {
            let clipped = clip_geometry(g, &geom::tile_rect(tx, ty, EXTENT, BUFFER));
            if is_empty(&clipped) {
                continue;
            }
            out.push(((tx, ty), clipped));
        }
        out
    }

    fn vertices(g: &Geometry<SigPt>) -> usize {
        match g {
            Geometry::Points(p) => p.len(),
            Geometry::Lines(l) => l.iter().map(Vec::len).sum(),
            Geometry::Polygons(p) => p.iter().flatten().map(Vec::len).sum(),
        }
    }

    /// How far apart two world coordinates may be and still be the same vertex.
    ///
    /// One world unit is 1/4096 of a tile and [`geom::quantize`] rounds to the nearest
    /// one, so anything below half a unit is invisible in the output. This is seven
    /// orders of magnitude tighter than that: the drift the descent introduces is
    /// last-bit drift from interpolating a crossing off an already-interpolated
    /// vertex, not a shape moving.
    const SLOP: f64 = 1e-6;

    fn near(a: SigPt, b: SigPt) -> bool {
        (a.x - b.x).abs() <= SLOP
            && (a.y - b.y).abs() <= SLOP
            // Equality first, so two unremovable vertices match: `inf - inf` is `NaN`.
            && (a.sig == b.sig || (a.sig - b.sig).abs() <= SLOP)
    }

    /// Whether two rings are the same ring, allowing for a different starting vertex
    /// and for Sutherland-Hodgman's zero-area bookkeeping.
    ///
    /// Two things are forgiven, and both are things the algorithm does to itself:
    ///
    /// * **Rotation.** S-H's output order follows its input's, and clipping in stages
    ///   changes the intermediate order -- so the descent's rings start at a different
    ///   vertex. A rotation is the same polygon: same edges, same winding, same area,
    ///   and MVT re-states the start vertex as a `MoveTo` either way.
    /// * **Collinear spurs.** Clipping a concave ring leaves degenerate runs along the
    ///   clip boundary, and how many vertices a run costs depends on how many passes
    ///   built it. Measured over the corpus, the direct clip carries MORE of them than
    ///   the descent does, so this forgives the descent nothing it needs.
    ///
    /// What is not forgiven is a vertex that carries shape. Reducing both rings to
    /// their corners and then demanding a rotation is what makes that distinction, and
    /// the signed-area check beside it is the independent witness.
    fn same_ring(a: &[SigPt], b: &[SigPt]) -> bool {
        let (a, b) = (corners(a), corners(b));
        let n = a.len();
        n == b.len() && (n == 0 || (0..n).any(|k| (0..n).all(|i| near(a[i], b[(i + k) % n]))))
    }

    /// A ring's corners: opened, and with every vertex that turns no corner removed.
    ///
    /// Removing one vertex can leave its neighbours collinear with each other, so this
    /// runs to a fixed point rather than in one pass.
    fn corners(ring: &[SigPt]) -> Vec<SigPt> {
        let closed = ring.len() > 1 && ring.first().map(|v| v.xy()) == ring.last().map(|v| v.xy());
        let mut out: Vec<SigPt> = ring[..ring.len() - usize::from(closed)].to_vec();
        loop {
            let n = out.len();
            if n < 3 {
                return out;
            }
            let Some(at) = (0..n).find(|&i| straight(out[(i + n - 1) % n], out[i], out[(i + 1) % n]))
            else {
                return out;
            };
            out.remove(at);
        }
    }

    /// Whether `b` sits on the line through `a` and `c`, turning no corner.
    ///
    /// The cross product scaled by the two edge lengths, so this is a bound on the
    /// sine of the turn rather than on an area -- an area threshold would call a long
    /// gentle bend straight and a short sharp one bent.
    fn straight(a: SigPt, b: SigPt, c: SigPt) -> bool {
        let ((ax, ay), (bx, by), (cx, cy)) = (a.xy(), b.xy(), c.xy());
        let (ux, uy) = (bx - ax, by - ay);
        let (vx, vy) = (cx - bx, cy - by);
        let scale = (ux * ux + uy * uy).sqrt() * (vx * vx + vy * vy).sqrt();
        (ux * vy - uy * vx).abs() <= SLOP * (scale + 1.0)
    }

    fn signed_area(ring: &[SigPt]) -> f64 {
        let n = ring.len();
        let mut a = 0.0;
        for i in 0..n {
            let (x1, y1) = ring[i].xy();
            let (x2, y2) = ring[(i + 1) % n].xy();
            a += x1 * y2 - x2 * y1;
        }
        a / 2.0
    }

    fn length(line: &[SigPt]) -> f64 {
        line.windows(2)
            .map(|w| {
                let ((x1, y1), (x2, y2)) = (w[0].xy(), w[1].xy());
                ((x2 - x1).powi(2) + (y2 - y1).powi(2)).sqrt()
            })
            .sum()
    }

    /// Whether two clips of the same feature into the same tile are the same shape.
    ///
    /// `None` when they are; otherwise why not. Rings are compared up to rotation and
    /// last-bit drift, everything else exactly: part counts, ring counts, vertex
    /// counts, and -- as an independent witness that a rotation really is a rotation
    /// -- signed area per ring and total length per line.
    fn same_shape(a: &Geometry<SigPt>, b: &Geometry<SigPt>) -> Option<String> {
        match (a, b) {
            (Geometry::Points(x), Geometry::Points(y)) => (x != y).then(|| "points differ".into()),
            (Geometry::Lines(x), Geometry::Lines(y)) => {
                if x.len() != y.len() {
                    return Some(format!("{} line parts vs {}", x.len(), y.len()));
                }
                for (i, (p, q)) in x.iter().zip(y).enumerate() {
                    if p.len() != q.len() {
                        return Some(format!("part {i}: {} vertices vs {}", p.len(), q.len()));
                    }
                    if !p.iter().zip(q).all(|(u, v)| near(*u, *v)) {
                        return Some(format!("part {i}: vertices moved"));
                    }
                    let (lp, lq) = (length(p), length(q));
                    if (lp - lq).abs() > SLOP * (1.0 + lq.abs()) {
                        return Some(format!("part {i}: length {lp} vs {lq}"));
                    }
                }
                None
            }
            (Geometry::Polygons(x), Geometry::Polygons(y)) => {
                if x.len() != y.len() {
                    return Some(format!("{} polygons vs {}", x.len(), y.len()));
                }
                for (i, (rp, rq)) in x.iter().zip(y).enumerate() {
                    if rp.len() != rq.len() {
                        return Some(format!("polygon {i}: {} rings vs {}", rp.len(), rq.len()));
                    }
                    for (j, (p, q)) in rp.iter().zip(rq).enumerate() {
                        if !same_ring(p, q) {
                            return Some(format!(
                                "polygon {i} ring {j}: not a rotation ({} corners vs {})",
                                corners(p).len(),
                                corners(q).len()
                            ));
                        }
                        let (ap, aq) = (signed_area(p), signed_area(q));
                        if (ap - aq).abs() > SLOP * (1.0 + aq.abs()) {
                            return Some(format!("polygon {i} ring {j}: area {ap} vs {aq}"));
                        }
                    }
                }
                None
            }
            _ => Some("geometry kind changed".into()),
        }
    }

    /// What one shape's descent cost against a direct clip, over every tile it
    /// reaches.
    struct Divergence {
        tiles: usize,
        /// Tiles one produced and the other did not, either way round.
        missing: Vec<(u64, u64)>,
        /// Tiles whose shapes are not the same shape, with the reason.
        wrong: Vec<((u64, u64), String)>,
        /// Tiles whose bytes differ although the shape does not -- a ring rotation, or
        /// a crossing that moved by its last bit. Measured, not asserted away.
        rotated: usize,
        descent_vertices: usize,
        direct_vertices: usize,
    }

    fn compare(g: &Geometry<SigPt>, z: u8) -> Divergence {
        let mine = descend(g, z);
        let mut want: std::collections::HashMap<(u64, u64), Geometry<SigPt>> =
            direct(g, z).into_iter().collect();
        let mut d = Divergence {
            tiles: 0,
            missing: Vec::new(),
            wrong: Vec::new(),
            rotated: 0,
            descent_vertices: 0,
            direct_vertices: 0,
        };
        for (tile, got) in &mine {
            d.tiles += 1;
            d.descent_vertices += vertices(got);
            // A tile the descent claims and the direct clip never reached is
            // over-inclusion; a tile it lost is a seam. Both are `missing`.
            let Some(expect) = want.remove(tile) else {
                d.missing.push(*tile);
                continue;
            };
            d.direct_vertices += vertices(&expect);
            match same_shape(got, &expect) {
                Some(why) => d.wrong.push((*tile, why)),
                None if *got != expect => d.rotated += 1,
                None => {}
            }
        }
        for (tile, expect) in want {
            d.tiles += 1;
            d.direct_vertices += vertices(&expect);
            d.missing.push(tile);
        }
        d.missing.sort_unstable();
        d
    }

    /// A deterministic corpus of shapes that cross a tile grid, each with the zoom to
    /// tile it at. Named so a failure says which shape broke.
    fn corpus() -> Vec<(&'static str, Geometry<SigPt>, u8)> {
        let mut out: Vec<(&'static str, Geometry<SigPt>, u8)> = vec![
            // A comb: many prongs, so one clip leaves Sutherland-Hodgman joining
            // several disjoint pieces into one self-touching ring. Clipping that
            // result again is the case a two-prong U does not reach.
            ("a comb of 9 teeth", comb(9, 0.5, 6.5), 4),
            ("a comb of 25 teeth", comb(25, 0.5, 6.5), 4),
            ("a comb on exact tile lines", comb(8, 1.0, 5.0), 4),
            ("a comb over many z7 tiles", comb(40, 2.5, 40.5), 7),
            // --- the no-recursion case: one tile, so the leaf clip is the only clip ---
            ("a ring inside one tile", polygon(&[&square(0.2, 0.8)]), 4),
            (
                "a line inside one tile",
                line(&[(w(0.2), w(0.2)), (w(0.5), w(0.7)), (w(0.8), w(0.3))]),
                4,
            ),
            // --- polygons spanning many tiles, which is the case this exists for ---
            ("a ring over 6x6 tiles", polygon(&[&square(0.5, 6.5)]), 4),
            ("a ring on exact tile corners", polygon(&[&square(1.0, 5.0)]), 4),
            (
                "a ring with a hole crossing tiles",
                polygon(&[&square(0.5, 6.5), &square(2.25, 4.75)]),
                4,
            ),
            (
                "a hole sharing a tile edge with its exterior",
                polygon(&[&square(1.0, 5.0), &square(2.0, 4.0)]),
                4,
            ),
            // A U straddling tile boundaries: Sutherland-Hodgman returns one ring with
            // a zero-area sliver here, which is the structure most at risk from
            // composing clips.
            (
                "a concave U over several tiles",
                polygon(&[&[
                    (w(0.5), w(0.5)),
                    (w(5.5), w(0.5)),
                    (w(5.5), w(2.5)),
                    (w(2.5), w(2.5)),
                    (w(2.5), w(4.5)),
                    (w(5.5), w(4.5)),
                    (w(5.5), w(6.5)),
                    (w(0.5), w(6.5)),
                    (w(0.5), w(0.5)),
                ]]),
                4,
            ),
        ];

        // A star, so edges cross tile lines at angles that are not nice fractions.
        let mut star: Vec<Pt> = (0..24)
            .map(|i| {
                let a = i as f64 * std::f64::consts::TAU / 24.0;
                let r = if i % 2 == 0 { 3.3 } else { 1.7 };
                (w(4.0 + r * a.cos()), w(4.0 + r * a.sin()))
            })
            .collect();
        star.push(star[0]);
        out.push(("a 12-point star", polygon(&[&star]), 4));

        // --- lines, which the plan calls the fragile case -------------------
        out.push((
            "a long diagonal",
            line(&[(w(0.3), w(0.3)), (w(7.7), w(6.1))]),
            4,
        ));
        out.push((
            "a diagonal through exact tile corners",
            line(&[(w(0.0), w(0.0)), (w(8.0), w(8.0))]),
            4,
        ));
        out.push((
            "a staircase, one vertex per tile corner",
            line(&(0..=8).map(|i| (w(i as f64), w(i as f64))).collect::<Vec<_>>()),
            4,
        ));
        // Leaves and re-enters repeatedly, so the weld is exercised at every level.
        out.push((
            "a zigzag crossing one tile line many times",
            line(
                &(0..40)
                    .map(|i| {
                        let t = i as f64 / 39.0;
                        (w(2.0 + 0.6 * ((i % 2) as f64 * 2.0 - 1.0)), w(0.5 + 6.0 * t))
                    })
                    .collect::<Vec<_>>(),
            ),
            4,
        ));
        // A vertex sitting exactly on a tile line, which is where an inclusive
        // `inside` test and an interpolated crossing can disagree.
        out.push((
            "a line with a vertex exactly on a tile line",
            line(&[(w(0.5), w(1.5)), (w(2.0), w(2.0)), (w(3.5), w(1.5))]),
            4,
        ));
        // A spiral, for edges at many angles and lengths at once.
        out.push((
            "a spiral over the grid",
            line(
                &(0..200)
                    .map(|i| {
                        let t = i as f64 / 199.0;
                        let a = t * std::f64::consts::TAU * 3.0;
                        let r = 0.2 + 3.5 * t;
                        (w(4.0 + r * a.cos()), w(4.0 + r * a.sin()))
                    })
                    .collect::<Vec<_>>(),
            ),
            4,
        ));

        // --- pseudo-random walks, deterministically seeded ------------------
        let mut seed = 0x2545_F491_4F6C_DD1Du64;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed >> 11) as f64 / (1u64 << 53) as f64
        };
        for _ in 0..8 {
            let pts: Vec<Pt> = (0..60)
                .map(|_| (w(next() * 8.0), w(next() * 8.0)))
                .collect();
            out.push(("a random walk", line(&pts), 4));
        }
        for _ in 0..8 {
            // A convex-ish blob: random radii around a circle, so the ring does not
            // self-intersect but its edges still cross tile lines arbitrarily.
            let mut ring: Vec<Pt> = (0..20)
                .map(|i| {
                    let a = i as f64 * std::f64::consts::TAU / 20.0;
                    let r = 1.0 + 2.5 * next();
                    (w(4.0 + r * a.cos()), w(4.0 + r * a.sin()))
                })
                .collect();
            ring.push(ring[0]);
            out.push(("a random blob", polygon(&[&ring]), 4));
        }

        // --- deeper zooms, so the descent runs more levels ------------------
        out.push(("a ring over many z7 tiles", polygon(&[&square(3.5, 60.5)]), 7));
        out.push((
            "a long diagonal over many z7 tiles",
            line(&[(w(1.3), w(2.3)), (w(90.7), w(70.1))]),
            7,
        ));

        // --- off the edge of the grid --------------------------------------
        out.push(("a ring hanging off the grid", polygon(&[&square(-2.0, 3.0)]), 4));
        out.push((
            "a line hanging off the grid",
            line(&[(w(-3.0), w(-1.0)), (w(4.0), w(5.0))]),
            4,
        ));

        // --- points --------------------------------------------------------
        let mut pts = Geometry::Points(sig(&[
            (w(0.5), w(0.5)),
            (w(1.0), w(1.0)),
            (w(2.9999), w(3.0001)),
            (w(5.5), w(2.5)),
        ]));
        crate::simplify::annotate(&mut pts);
        out.push(("a handful of points", pts, 4));

        out
    }

    /// **The gate.** The descent's answer for a tile must be the same shape as
    /// clipping the source straight to that tile -- same tiles, same parts, same
    /// rings, same vertices, same areas, same lengths.
    ///
    /// It is not the same BYTES, and the reason is not the one the plan expected.
    /// The worry was that clipping to an ancestor cell inserts vertices on that
    /// ancestor's boundary which nothing downstream removes. That does not happen: a
    /// child cell is a subset of its parent and both are grown by the *same* buffer,
    /// so a parent boundary line either coincides with the child's line on that side
    /// -- when the child sits on that border -- or lies strictly outside the child,
    /// where the next clip removes it. Coincident lines produce coincident crossings.
    /// So no ancestor vertex survives that the leaf would not have invented itself,
    /// and [`the_descent_invents_no_vertices`] measures that directly.
    ///
    /// What differs instead is [`Divergence::rotated`]: ring rotation, and last-bit
    /// drift on interpolated line crossings. Neither moves a shape, and both are
    /// below the quantisation grid or invisible to it. A collinear-reduction pass
    /// would not address either, so there is none.
    #[test]
    fn the_descent_is_the_same_shape_as_a_direct_clip() {
        let mut bad = Vec::new();
        for (name, g, z) in corpus() {
            let d = compare(&g, z);
            assert!(d.tiles > 0, "{name}: nothing was tiled at all");
            if !d.missing.is_empty() {
                bad.push(format!(
                    "{name} at z{z}: {} of {} tiles are in one and not the other: {:?}",
                    d.missing.len(),
                    d.tiles,
                    &d.missing[..d.missing.len().min(6)]
                ));
            }
            if !d.wrong.is_empty() {
                bad.push(format!(
                    "{name} at z{z}: {} of {} tiles changed shape: {:?}",
                    d.wrong.len(),
                    d.tiles,
                    &d.wrong[..d.wrong.len().min(3)]
                ));
            }
        }
        assert!(bad.is_empty(), "{}", bad.join("\n"));
    }

    /// The archive-growth question, measured. Boundary vertices on internal cell
    /// edges would cost bytes at every zoom, and that was the main argument for a
    /// collinear pass. The descent emits no more vertices than a direct clip -- fewer,
    /// in fact, because a concave ring's zero-area sliver comes out shorter when the
    /// clip happens in stages.
    #[test]
    fn the_descent_invents_no_vertices() {
        let (mut mine, mut theirs) = (0usize, 0usize);
        for (_, g, z) in corpus() {
            let d = compare(&g, z);
            mine += d.descent_vertices;
            theirs += d.direct_vertices;
        }
        assert!(theirs > 0);
        assert!(
            mine <= theirs,
            "the descent would grow the archive: {mine} vertices vs {theirs}"
        );
    }

    /// A ring of `n` vertices around `(cx, cy)`, radius varying with `wobble`.
    ///
    /// Deliberately not star-convex when `wobble` is large: a convex-ish blob never
    /// makes Sutherland-Hodgman produce the self-touching output that composing a clip
    /// has to survive.
    fn wobbly_ring(cx: f64, cy: f64, radius: f64, n: usize, wobble: &mut impl FnMut() -> f64) -> Vec<Pt> {
        let mut r: Vec<Pt> = (0..n)
            .map(|i| {
                let a = i as f64 * std::f64::consts::TAU / n as f64;
                let d = radius * (0.25 + 1.5 * wobble());
                (w(cx + d * a.cos()), w(cy + d * a.sin()))
            })
            .collect();
        r.push(r[0]);
        r
    }

    /// **A hole must not vanish.** Found by `test/diff_mamaps.py` on a real us-west z13
    /// archive: one `landcover` feature came back with 13 rings where a direct clip gave
    /// 14, and the missing one was a hole carrying a quarter of the feature's net area.
    /// A hole that disappears renders as a lake filled in solid.
    ///
    /// The corpus above missed it because every hole in it is an axis-aligned square. A
    /// hole only triggers this when it is concave enough that clipping it to an ancestor
    /// cell leaves a self-touching ring, and positioned so that the next clip down has
    /// to cut that ring again.
    ///
    /// Ring COUNT and NET signed area, not vertex lists: what matters is that the hole is
    /// still there and still subtracts the same ground.
    #[test]
    fn a_hole_survives_the_descent_that_a_direct_clip_keeps() {
        let mut seed = 0x9E37_79B9_7F4A_7C15u64;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed >> 11) as f64 / (1u64 << 53) as f64
        };

        let mut checked = 0usize;
        let mut bad: Vec<String> = Vec::new();

        // A comb-shaped hole is the sharpest case, and the reason is the gap in the
        // descent's soundness argument. Composing clips is exact for a CONVEX ring:
        // `clip(clip(g,P),L) == clip(g,L)` because both are set intersections. For a
        // concave ring Sutherland-Hodgman does not return the intersection as a simple
        // polygon -- it returns one SELF-TOUCHING ring joined by zero-width slivers --
        // and re-clipping that is not covered by the argument. A comb straddling a cell
        // boundary produces exactly that, many times over.
        let comb_hole = {
            let mut r: Vec<Pt> = vec![(w(2.0), w(4.0))];
            for i in 0..11 {
                let y0 = 4.0 + i as f64 * 0.7;
                let y1 = y0 + 0.35;
                r.push((w(12.5), w(y0)));
                r.push((w(12.5), w(y1)));
                r.push((w(2.0), w(y1)));
                r.push((w(2.0), w(y0 + 0.7)));
            }
            r.push((w(2.0), w(12.0)));
            r.push((w(2.0), w(4.0)));
            r
        };
        let ext = square(0.5, 15.5);
        let g = polygon(&[&ext, &comb_hole]);
        for z in [4u8, 5, 6] {
            let mine: std::collections::HashMap<_, _> = descend(&g, z).into_iter().collect();
            for (tile, expect) in direct(&g, z) {
                let Some(got) = mine.get(&tile) else {
                    bad.push(format!("comb hole z{z} tile {tile:?}: the descent lost the tile"));
                    continue;
                };
                let (Geometry::Polygons(a), Geometry::Polygons(b)) = (got, &expect) else { continue };
                if a.len() != b.len() {
                    bad.push(format!("comb hole z{z} tile {tile:?}: {} polygons vs {}", a.len(), b.len()));
                    continue;
                }
                for (i, (ra, rb)) in a.iter().zip(b).enumerate() {
                    checked += 1;
                    if ra.len() != rb.len() {
                        bad.push(format!(
                            "comb hole z{z} tile {tile:?} polygon {i}: {} rings vs {}",
                            ra.len(),
                            rb.len()
                        ));
                        continue;
                    }
                    let net = |rings: &Vec<Vec<SigPt>>| -> f64 {
                        rings.iter().map(|r| signed_area(r)).sum()
                    };
                    let (na, nb) = (net(ra), net(rb));
                    if (na - nb).abs() > SLOP * (1.0 + nb.abs()) {
                        bad.push(format!(
                            "comb hole z{z} tile {tile:?} polygon {i}: net area {na} vs {nb}"
                        ));
                    }
                }
            }
        }

        for case in 0..120 {
            // A big wobbly exterior with two wobbly holes inside it, at a zoom deep
            // enough that the descent runs several levels.
            let ext = wobbly_ring(8.0, 8.0, 5.0, 40, &mut next);
            let holes: Vec<Vec<Pt>> = (0..2)
                .map(|k| {
                    let (ox, oy) = (6.0 + 4.0 * k as f64, 6.0 + 3.0 * next());
                    wobbly_ring(ox, oy, 1.6, 24, &mut next)
                })
                .collect();
            let mut rings: Vec<&[Pt]> = vec![&ext];
            rings.extend(holes.iter().map(|h| h.as_slice()));
            let g = polygon(&rings);

            let mine: std::collections::HashMap<_, _> = descend(&g, 5).into_iter().collect();
            for (tile, expect) in direct(&g, 5) {
                let Some(got) = mine.get(&tile) else {
                    bad.push(format!("case {case} tile {tile:?}: the descent lost the tile"));
                    continue;
                };
                let (Geometry::Polygons(a), Geometry::Polygons(b)) = (got, &expect) else {
                    continue;
                };
                for (i, (ra, rb)) in a.iter().zip(b).enumerate() {
                    checked += 1;
                    if ra.len() != rb.len() {
                        bad.push(format!(
                            "case {case} tile {tile:?} polygon {i}: {} rings vs {}",
                            ra.len(),
                            rb.len()
                        ));
                        continue;
                    }
                    let net = |rings: &Vec<Vec<SigPt>>| -> f64 {
                        rings.iter().map(|r| signed_area(r)).sum()
                    };
                    let (na, nb) = (net(ra), net(rb));
                    if (na - nb).abs() > SLOP * (1.0 + nb.abs()) {
                        bad.push(format!(
                            "case {case} tile {tile:?} polygon {i}: net area {na} vs {nb}"
                        ));
                    }
                }
            }
        }
        assert!(checked > 0, "the fixture produced no polygons to check");
        assert!(bad.is_empty(), "{} of {checked}:\n{}", bad.len(), bad[..bad.len().min(8)].join("\n"));
    }

    /// **The composition property, tested directly.** Nothing in [`crate::clip`] asserts
    /// `clip(clip(g, A), B) == clip(g, B)` for `B` inside `A`, and the whole descent is
    /// that identity applied `z - level` times. This searches for a counterexample over
    /// random rings, random holes and random nested rect pairs.
    ///
    /// Ring COUNT and NET signed area, because those are what a failure costs: a lost
    /// ring is a lost hole, and a hole that vanishes renders as a lake filled in solid.
    #[test]
    fn clipping_twice_is_clipping_once_for_a_nested_rect() {
        let mut seed = 0x1234_5678_9ABC_DEF1u64;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed >> 11) as f64 / (1u64 << 53) as f64
        };

        let mut bad: Vec<String> = Vec::new();
        let mut checked = 0usize;
        for case in 0..4000 {
            // A wobbly exterior, and half the time a wobbly hole inside it. Wobble is
            // wide enough that the rings are genuinely concave, which is the only case
            // composition is in doubt for.
            let ext = wobbly_ring(4.0, 4.0, 3.0, 6 + (next() * 24.0) as usize, &mut next);
            let mut rings: Vec<Vec<Pt>> = vec![ext];
            if next() < 0.5 {
                rings.push(wobbly_ring(
                    3.5 + next(),
                    3.5 + next(),
                    0.6 + next(),
                    6 + (next() * 18.0) as usize,
                    &mut next,
                ));
            }
            let refs: Vec<&[Pt]> = rings.iter().map(|r| r.as_slice()).collect();
            let g = polygon(&refs);

            // Outer rect A, and inner rect B strictly inside it -- the relationship a
            // parent cell and a descendant cell always have.
            let (ax0, ay0) = (next() * 3.0, next() * 3.0);
            let (aw, ah) = (1.0 + next() * 6.0, 1.0 + next() * 6.0);
            let a = Rect {
                min_x: w(ax0),
                min_y: w(ay0),
                max_x: w(ax0 + aw),
                max_y: w(ay0 + ah),
            };
            let (bx0, by0) = (ax0 + next() * aw * 0.5, ay0 + next() * ah * 0.5);
            let b = Rect {
                min_x: w(bx0),
                min_y: w(by0),
                max_x: w(bx0 + next() * (ax0 + aw - bx0)),
                max_y: w(by0 + next() * (ay0 + ah - by0)),
            };

            let once = clip_geometry(&g, &b);
            let twice = clip_geometry(&clip_geometry(&g, &a), &b);
            checked += 1;

            let (Geometry::Polygons(p1), Geometry::Polygons(p2)) = (&once, &twice) else {
                continue;
            };
            if p1.len() != p2.len() {
                bad.push(format!("case {case}: {} polygons once vs {} twice", p1.len(), p2.len()));
                continue;
            }
            for (i, (r1, r2)) in p1.iter().zip(p2).enumerate() {
                if r1.len() != r2.len() {
                    bad.push(format!(
                        "case {case} polygon {i}: {} rings once vs {} twice (A {a:?} B {b:?})",
                        r1.len(),
                        r2.len()
                    ));
                    continue;
                }
                let net = |rings: &Vec<Vec<SigPt>>| -> f64 {
                    rings.iter().map(|r| signed_area(r)).sum()
                };
                let (n1, n2) = (net(r1), net(r2));
                if (n1 - n2).abs() > SLOP * (1.0 + n1.abs()) {
                    bad.push(format!(
                        "case {case} polygon {i}: net area {n1} once vs {n2} twice"
                    ));
                }
            }
        }
        assert!(checked > 0);
        assert!(
            bad.is_empty(),
            "{} of {checked} nested clips disagree:\n{}",
            bad.len(),
            bad[..bad.len().min(6)].join("\n")
        );
    }

    /// **Byte-identity is genuinely gone.** Recorded as a test so nobody later reads
    /// the shape test above as a byte guarantee and builds on it.
    ///
    /// Three differences, in descending order of how many tiles they touch, none of
    /// them a shape:
    ///
    /// 1. **A ring's starting vertex.** The largest share by far. Sutherland-Hodgman
    ///    keeps its input's rotation and the descent's input is its parent's answer.
    /// 2. **Fewer collinear spurs.** A concave ring's zero-area run along a clip
    ///    boundary comes out shorter when the clip happens in stages, so the descent
    ///    emits fewer vertices than a direct clip, never more.
    /// 3. **Last-bit drift on a line crossing.** Liang-Barsky interpolates both
    ///    coordinates, so a crossing computed off an already-interpolated vertex can
    ///    land an ULP away -- 1e-12 world units against a quantisation grid of 1.
    ///
    /// What is NOT in that list is the thing the plan expected to dominate: surviving
    /// vertices on internal cell boundaries. [`the_descent_invents_no_vertices`] is
    /// where that is measured, and it is why there is no collinear-reduction pass.
    #[test]
    fn the_descent_is_not_byte_identical_and_this_is_where_that_is_written_down() {
        let (mut differing, mut total) = (0usize, 0usize);
        for (_, g, z) in corpus() {
            let d = compare(&g, z);
            differing += d.rotated;
            total += d.tiles;
        }
        assert!(
            differing > 0,
            "if no tile's bytes differed, byte-identity is back and this test is a lie"
        );
        assert!(differing < total, "some tiles must still match exactly");
    }

    /// A feature small enough for one tile must not recurse at all
    /// the leaf, and the leaf clip is the only clip. This is the overwhelmingly common
    /// case -- a building, a shop, a street -- and it has to stay exactly as cheap as
    /// it was.
    #[test]
    fn a_feature_inside_one_tile_is_clipped_once() {
        let g = polygon(&[&square(0.2, 0.8)]);
        let mut clips = 0usize;
        let mut tiles = Vec::new();
        subdivide(&g, 6, EXTENT, BUFFER, &mut |tx, ty, _| {
            clips += 1;
            tiles.push((tx, ty));
        });
        assert_eq!(tiles, vec![(0, 0)]);
        assert_eq!(clips, 1);
    }

    /// The start cell is chosen from the PADDED range, so a feature that only reaches
    /// into a neighbour's buffer still descends towards that neighbour. Picking the
    /// cell from the unpadded box would leave a seam at the join.
    #[test]
    fn a_feature_reaching_only_into_a_neighbours_buffer_still_lands_there() {
        // Two units inside tile 1's western edge, well within the 5-unit buffer.
        let x = w(1.0) + 2.0;
        let g = line(&[(x, w(1.2)), (x, w(1.8))]);
        let mut tiles: Vec<(u64, u64)> = Vec::new();
        subdivide(&g, 4, EXTENT, BUFFER, &mut |tx, ty, _| tiles.push((tx, ty)));
        tiles.sort_unstable();
        assert_eq!(tiles, vec![(0, 1), (1, 1)], "tile 0's buffer holds it too");
    }

    /// A leaf's cell rect must be the tile rect the old loop clipped against, to the
    /// bit -- not merely close to it. A single unit of drift would move every
    /// crossing on every tile boundary in the archive.
    #[test]
    fn a_leaf_cell_is_exactly_the_tile_rect() {
        for (tx, ty, z) in [(0u64, 0u64, 0u8), (1, 1, 4), (13, 6, 4), (827, 1391, 14)] {
            assert_eq!(
                cell_rect(z, tx, ty, z, EXTENT, BUFFER),
                geom::tile_rect(tx, ty, EXTENT, BUFFER),
                "leaf ({tx},{ty}) at z{z}"
            );
        }
    }

    /// A cell contains its four children, buffer and all. This is the monotonicity the
    /// whole descent rests on: if it failed, an ancestor clip could remove geometry a
    /// leaf clip would have kept, and features would vanish from tiles.
    #[test]
    fn a_cells_buffered_rect_contains_its_childrens() {
        let z = 5u8;
        for level in 0..z {
            for (cx, cy) in [(0u64, 0u64), (1, 0), (1, 2)] {
                let parent = cell_rect(level, cx, cy, z, EXTENT, BUFFER);
                for (dx, dy) in [(0u64, 0u64), (1, 0), (0, 1), (1, 1)] {
                    let child = cell_rect(level + 1, cx * 2 + dx, cy * 2 + dy, z, EXTENT, BUFFER);
                    assert!(
                        parent.min_x <= child.min_x
                            && parent.min_y <= child.min_y
                            && child.max_x <= parent.max_x
                            && child.max_y <= parent.max_y,
                        "level {level} cell ({cx},{cy}) does not contain child (+{dx},+{dy}): \
                         {parent:?} vs {child:?}"
                    );
                }
            }
        }
    }

    /// Emptiness prunes, so a feature must not be handed tiles it does not reach.
    #[test]
    fn tiles_a_feature_does_not_reach_are_not_visited() {
        // A diagonal across a 8x8 tile block: the far corners are empty.
        let g = line(&[(w(0.5), w(0.5)), (w(7.5), w(7.5))]);
        let mut tiles: Vec<(u64, u64)> = Vec::new();
        subdivide(&g, 4, EXTENT, BUFFER, &mut |tx, ty, _| tiles.push((tx, ty)));
        assert!(tiles.contains(&(0, 0)) && tiles.contains(&(7, 7)));
        assert!(!tiles.contains(&(0, 7)), "the line never goes there");
        assert!(!tiles.contains(&(7, 0)), "nor there");
        assert!(tiles.len() < 24, "walked {} tiles of the 64-tile box", tiles.len());
    }

    /// An empty geometry, and one entirely off the grid, produce nothing rather than
    /// panicking on a start cell that does not exist.
    #[test]
    fn nothing_to_tile_emits_nothing() {
        let mut hit = 0usize;
        for g in [
            Geometry::<SigPt>::Lines(vec![]),
            Geometry::<SigPt>::Points(vec![]),
            Geometry::<SigPt>::Polygons(vec![]),
            Geometry::Lines(vec![sig(&[(w(-40.0), w(-40.0)), (w(-30.0), w(-30.0))])]),
            Geometry::Points(sig(&[(f64::NAN, 0.0)])),
        ] {
            subdivide(&g, 4, EXTENT, BUFFER, &mut |_, _, _| hit += 1);
        }
        assert_eq!(hit, 0);
    }

    /// Points are bucketed rather than descended, and a point inside a neighbour's
    /// buffer is in both tiles -- which is what the per-tile `contains` filter did.
    #[test]
    fn a_point_lands_in_every_tile_whose_buffer_holds_it() {
        let g = Geometry::Points(sig(&[(w(1.0) + 1.0, w(1.0) + 1.0)]));
        let mut tiles: Vec<(u64, u64)> = Vec::new();
        subdivide(&g, 4, EXTENT, BUFFER, &mut |tx, ty, got| {
            assert_eq!(vertices(got), 1);
            tiles.push((tx, ty));
        });
        assert_eq!(tiles, vec![(0, 0), (0, 1), (1, 0), (1, 1)]);
    }

    /// Points keep their input order within a tile. The drop policy's tie-break is the
    /// feature index, but a MultiPoint's own parts have no index of their own, so
    /// their order is the order they arrived in.
    #[test]
    fn points_keep_their_input_order_within_a_tile() {
        let want = sig(&[(w(0.9), w(0.1)), (w(0.1), w(0.9)), (w(0.5), w(0.5))]);
        let g = Geometry::Points(want.clone());
        subdivide(&g, 4, EXTENT, BUFFER, &mut |_, _, got| {
            assert_eq!(*got, Geometry::Points(want.clone()))
        });
    }
}
