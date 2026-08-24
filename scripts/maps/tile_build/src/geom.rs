//! Geometry in tile space: projection, bounds, tile ranges and quantisation.
//!
//! This is the front of the tiling pipeline. Each stage is a separate function so
//! each can be tested on its own, and they compose in one fixed order:
//!
//! ```text
//! lon/lat  --project_geometry-->  world     (tile units x extent, per zoom)
//! world    --clip::clip_geometry->  world   (against one tile's buffered rect)
//! world    --translate----------->  tile    (origin at the tile's corner)
//! tile     --quantize------------>  integer tile coordinates
//! integer  --simplify::simplify-->  integer (fewer vertices)
//! integer  --mvt::encode_*------->  command stream
//! ```
//!
//! ## Why "world x extent" and not fractional tile units
//!
//! Projection is the only expensive step, and a coastline crossing a thousand
//! tiles must not be projected a thousand times. So a geometry is projected
//! **once per zoom** into coordinates already scaled to the tile extent: tile
//! `(tx, ty)` then occupies `[tx*extent, (tx+1)*extent]`, and moving into it is a
//! subtraction. Clipping happens in that same space, against a rect built by
//! [`tile_rect`], so only the small clipped result is ever translated.
//!
//! ## Why the clip runs before the quantisation
//!
//! A clip introduces new vertices where an edge crosses the tile boundary, and
//! those crossings are not on the integer grid. Clipping in floats and rounding
//! afterwards puts the crossing within half a unit of the true intersection;
//! rounding first and clipping the integer polyline moves the whole edge before
//! the intersection is even computed, and the error compounds along a long edge.

use crate::mvt::DEFAULT_EXTENT;

/// A vertex. Longitude/latitude before projection, world or tile coordinates
/// after -- see each function for which space it works in.
pub type Pt = (f64, f64);

/// A vertex on the integer tile grid, ready to encode.
pub type IPt = (i32, i32);

/// Tile-boundary overspill, in extent units at [`DEFAULT_EXTENT`].
///
/// Geometry is clipped to the tile rect grown by this much, so a line crossing a
/// tile edge is drawn to slightly beyond it. Without it a renderer stroking a
/// 2 px-wide road would show a seam at every tile boundary, because the join and
/// the cap would have nothing on the far side to align with. 5 units at extent
/// 4096 is tippecanoe's default and what the published archives were built with.
pub const DEFAULT_BUFFER: f64 = 5.0;

/// [`DEFAULT_BUFFER`] scaled to an arbitrary extent, so the overspill stays the
/// same fraction of a tile.
pub fn buffer_for(extent: u32) -> f64 {
    DEFAULT_BUFFER * extent as f64 / DEFAULT_EXTENT as f64
}

/// A geometry, in whichever coordinate space the producing function documents.
///
/// The three variants are the multi- forms only: a single LineString is a
/// `Lines` of one, and a single Polygon a `Polygons` of one. Collapsing the
/// singular cases removes a whole layer of branching from the clipper and the
/// encoders, and MVT itself draws no distinction -- geometry type is per feature,
/// not per part.
#[derive(Debug, Clone, PartialEq)]
pub enum Geometry {
    Points(Vec<Pt>),
    Lines(Vec<Vec<Pt>>),
    /// Each polygon is `[exterior, hole, hole, ...]`. Ring orientation is *not*
    /// significant here; [`crate::mvt::encode_polygons`] derives it from the
    /// signed area rather than trusting the input.
    Polygons(Vec<Vec<Vec<Pt>>>),
}

/// The integer counterpart of [`Geometry`], always in one tile's coordinates.
#[derive(Debug, Clone, PartialEq)]
pub enum IntGeometry {
    Points(Vec<IPt>),
    Lines(Vec<Vec<IPt>>),
    Polygons(Vec<Vec<Vec<IPt>>>),
}

impl IntGeometry {
    pub fn is_empty(&self) -> bool {
        match self {
            IntGeometry::Points(p) => p.is_empty(),
            IntGeometry::Lines(l) => l.iter().all(|p| p.len() < 2),
            IntGeometry::Polygons(p) => p.iter().all(|rings| {
                rings.first().is_none_or(|r| r.len() < 3)
            }),
        }
    }
}

/// An axis-aligned box.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Rect {
    pub min_x: f64,
    pub min_y: f64,
    pub max_x: f64,
    pub max_y: f64,
}

impl Rect {
    pub fn contains(&self, (x, y): Pt) -> bool {
        x >= self.min_x && x <= self.max_x && y >= self.min_y && y <= self.max_y
    }

    pub fn intersects(&self, other: &Rect) -> bool {
        self.min_x <= other.max_x
            && other.min_x <= self.max_x
            && self.min_y <= other.max_y
            && other.min_y <= self.max_y
    }
}

/// Web-Mercator project a lon/lat to fractional tile coordinates at `z`.
///
/// Latitude is clamped to the Mercator limit: the projection diverges at the
/// poles, and a feed with a `0,0`-style placeholder coordinate would otherwise
/// produce an infinity.
///
/// This is the single copy of the projection in the crate. Two copies that drift
/// apart would put a point layer and a line layer at subtly different places in
/// the same tile.
pub fn project(lon: f64, lat: f64, z: u8) -> Pt {
    let n = (1u64 << z) as f64;
    let lat = lat.clamp(-85.051_128_78, 85.051_128_78);
    let x = (lon.clamp(-180.0, 180.0) + 180.0) / 360.0 * n;
    let s = lat.to_radians().sin();
    let y = (0.5 - (((1.0 + s) / (1.0 - s)).ln()) / (4.0 * std::f64::consts::PI)) * n;
    (x, y)
}

/// [`project`], scaled so one tile spans `extent` units. This is "world"
/// coordinates: tile `(tx, ty)` occupies `[tx*extent, (tx+1)*extent]`.
pub fn project_scaled(lon: f64, lat: f64, z: u8, extent: u32) -> Pt {
    let (x, y) = project(lon, lat, z);
    (x * extent as f64, y * extent as f64)
}

/// Project a whole lon/lat geometry into world coordinates for one zoom.
pub fn project_geometry(g: &Geometry, z: u8, extent: u32) -> Geometry {
    let p = |&(lon, lat): &Pt| project_scaled(lon, lat, z, extent);
    match g {
        Geometry::Points(pts) => Geometry::Points(pts.iter().map(p).collect()),
        Geometry::Lines(lines) => {
            Geometry::Lines(lines.iter().map(|l| l.iter().map(p).collect()).collect())
        }
        Geometry::Polygons(polys) => Geometry::Polygons(
            polys
                .iter()
                .map(|rings| rings.iter().map(|r| r.iter().map(p).collect()).collect())
                .collect(),
        ),
    }
}

/// The bounding box of every vertex, or `None` when there are none.
///
/// Non-finite vertices are skipped. They should not exist -- [`project`] clamps --
/// but a `NaN` reaching [`tile_range`] would silently produce an empty range,
/// and dropping it here at least keeps the rest of the geometry.
pub fn bounds(g: &Geometry) -> Option<Rect> {
    let mut r: Option<Rect> = None;
    let mut add = |(x, y): Pt| {
        if !x.is_finite() || !y.is_finite() {
            return;
        }
        r = Some(match r {
            None => Rect { min_x: x, min_y: y, max_x: x, max_y: y },
            Some(b) => Rect {
                min_x: b.min_x.min(x),
                min_y: b.min_y.min(y),
                max_x: b.max_x.max(x),
                max_y: b.max_y.max(y),
            },
        });
    };
    match g {
        Geometry::Points(pts) => pts.iter().for_each(|p| add(*p)),
        Geometry::Lines(lines) => lines.iter().flatten().for_each(|p| add(*p)),
        Geometry::Polygons(polys) => polys.iter().flatten().flatten().for_each(|p| add(*p)),
    }
    r
}

/// An inclusive range of tile coordinates.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TileRange {
    pub x0: u64,
    pub y0: u64,
    pub x1: u64,
    pub y1: u64,
}

impl TileRange {
    /// Every `(x, y)` in the range, row-major, so a tiling pass is deterministic.
    pub fn iter(&self) -> impl Iterator<Item = (u64, u64)> + '_ {
        (self.y0..=self.y1).flat_map(move |y| (self.x0..=self.x1).map(move |x| (x, y)))
    }

    pub fn len(&self) -> u64 {
        (self.x1 - self.x0 + 1) * (self.y1 - self.y0 + 1)
    }

    pub fn is_empty(&self) -> bool {
        false
    }
}

/// Every tile a geometry can reach at zoom `z`, sorted and deduplicated.
///
/// The point of this over `tile_range(bounds(g))` is long thin diagonal features. A
/// bounding box is a terrible approximation of a route: measured on one
/// transcontinental rail relation (40° of longitude, 2000 vertices) at z16, the box
/// covers **34,535,986** tiles and the route passes through **29,276** — a 1180x
/// difference, and each of those tiles ran a full clip and simplify of the whole
/// geometry. That is why `transit_lines` at planet scale took hours.
///
/// Each SEGMENT's own box is used instead, and their union taken. Real OSM geometry
/// has short segments, so those boxes are 1-4 tiles each and the total tracks the
/// tiles actually traversed. It stays a conservative superset — a segment's box always
/// contains the segment — so no tile a feature reaches can be missed, which a
/// line-walking algorithm would have to get exactly right to be safe.
///
/// Polygons keep whole-ring boxes: a polygon legitimately covers its interior tiles,
/// and per-segment boxes would drop every tile strictly inside the ring.
pub fn tiles_touched(g: &Geometry, z: u8, extent: u32, pad: f64, out: &mut Vec<(u64, u64)>) {
    out.clear();
    let mut push_box = |ax: f64, ay: f64, bx: f64, by: f64| {
        if !ax.is_finite() || !ay.is_finite() || !bx.is_finite() || !by.is_finite() {
            return;
        }
        let b = Rect {
            min_x: ax.min(bx),
            min_y: ay.min(by),
            max_x: ax.max(bx),
            max_y: ay.max(by),
        };
        if let Some(r) = tile_range(&b, z, extent, pad) {
            out.extend(r.iter());
        }
    };
    match g {
        // A point's box is a point; one range call each is already tight.
        Geometry::Points(pts) => {
            for &(x, y) in pts {
                push_box(x, y, x, y);
            }
        }
        Geometry::Lines(lines) => {
            for line in lines {
                match line.as_slice() {
                    [] => {}
                    // A degenerate one-point "line" still occupies a tile.
                    [(x, y)] => push_box(*x, *y, *x, *y),
                    _ => {
                        for seg in line.windows(2) {
                            push_box(seg[0].0, seg[0].1, seg[1].0, seg[1].1);
                        }
                    }
                }
            }
        }
        Geometry::Polygons(polys) => {
            for rings in polys {
                // The exterior ring's own box, which is the polygon's footprint --
                // interior tiles included, because the fill reaches them.
                let Some(ext) = rings.first() else { continue };
                let mut min = (f64::INFINITY, f64::INFINITY);
                let mut max = (f64::NEG_INFINITY, f64::NEG_INFINITY);
                for &(x, y) in ext {
                    if !x.is_finite() || !y.is_finite() {
                        continue;
                    }
                    min = (min.0.min(x), min.1.min(y));
                    max = (max.0.max(x), max.1.max(y));
                }
                push_box(min.0, min.1, max.0, max.1);
            }
        }
    }
    // A feature must appear in a tile's list exactly once: adjacent segments share
    // tiles, and a duplicate would encode the whole geometry twice into one tile.
    out.sort_unstable();
    out.dedup();
}

/// The tiles a world-space bounding box touches at zoom `z`, grown by `pad`
/// world units so a feature just outside a tile still reaches into its buffer.
///
/// Returns `None` when the box lies entirely off the grid. It is otherwise
/// clamped to the grid: a geometry straddling the antimeridian is truncated
/// rather than wrapped, which is what the published archives do and what the
/// renderer expects.
pub fn tile_range(b: &Rect, z: u8, extent: u32, pad: f64) -> Option<TileRange> {
    let n = 1u64 << z;
    let e = extent as f64;
    let last = (n - 1) as f64;

    let fx0 = ((b.min_x - pad) / e).floor();
    let fy0 = ((b.min_y - pad) / e).floor();
    let fx1 = ((b.max_x + pad) / e).floor();
    let fy1 = ((b.max_y + pad) / e).floor();
    if !fx0.is_finite() || !fy0.is_finite() || !fx1.is_finite() || !fy1.is_finite() {
        return None;
    }
    // Entirely off the grid on either axis: no tile can hold it.
    if fx1 < 0.0 || fy1 < 0.0 || fx0 > last || fy0 > last {
        return None;
    }
    Some(TileRange {
        x0: fx0.max(0.0) as u64,
        y0: fy0.max(0.0) as u64,
        x1: fx1.min(last) as u64,
        y1: fy1.min(last) as u64,
    })
}

/// One tile's clip rect in **world** coordinates, grown by `buffer`.
pub fn tile_rect(tx: u64, ty: u64, extent: u32, buffer: f64) -> Rect {
    let e = extent as f64;
    let (ox, oy) = (tx as f64 * e, ty as f64 * e);
    Rect {
        min_x: ox - buffer,
        min_y: oy - buffer,
        max_x: ox + e + buffer,
        max_y: oy + e + buffer,
    }
}

/// Shift a geometry by `(dx, dy)`. Applied after the clip, with the tile's world
/// origin negated, to put the geometry in tile coordinates.
pub fn translate(g: &Geometry, dx: f64, dy: f64) -> Geometry {
    let t = |&(x, y): &Pt| (x + dx, y + dy);
    match g {
        Geometry::Points(pts) => Geometry::Points(pts.iter().map(t).collect()),
        Geometry::Lines(lines) => {
            Geometry::Lines(lines.iter().map(|l| l.iter().map(t).collect()).collect())
        }
        Geometry::Polygons(polys) => Geometry::Polygons(
            polys
                .iter()
                .map(|rings| rings.iter().map(|r| r.iter().map(t).collect()).collect())
                .collect(),
        ),
    }
}

/// Convenience for the two steps that always follow a clip: translate into the
/// tile, then round onto the integer grid.
pub fn to_tile(g: &Geometry, tx: u64, ty: u64, extent: u32) -> IntGeometry {
    let e = extent as f64;
    quantize(&translate(g, -(tx as f64 * e), -(ty as f64 * e)))
}

/// Round onto the integer tile grid, dropping consecutive duplicates.
///
/// Quantisation is what creates duplicates: two vertices a thousandth of a unit
/// apart become the same integer, and an MVT `LineTo` with a zero delta is a
/// wasted three bytes that some renderers treat as a degenerate segment. Rings
/// keep their explicit closing vertex here; [`crate::mvt::encode_polygons`]
/// strips it, since `ClosePath` implies it.
pub fn quantize(g: &Geometry) -> IntGeometry {
    match g {
        Geometry::Points(pts) => IntGeometry::Points(pts.iter().map(round_pt).collect()),
        Geometry::Lines(lines) => {
            IntGeometry::Lines(lines.iter().map(|l| dedup(l.iter().map(round_pt))).collect())
        }
        Geometry::Polygons(polys) => IntGeometry::Polygons(
            polys
                .iter()
                .map(|rings| rings.iter().map(|r| dedup(r.iter().map(round_pt))).collect())
                .collect(),
        ),
    }
}

fn round_pt(&(x, y): &Pt) -> IPt {
    (round_i32(x), round_i32(y))
}

/// Round to the nearest integer, saturating rather than wrapping.
///
/// Quantisation runs after the clip, so a coordinate here is always within a
/// buffered tile and nowhere near the `i32` limits. If one is not, `as i32` on a
/// `NaN` is silently `0` and on a huge float is silently the saturated bound --
/// both plausible-looking coordinates. Saturating deliberately keeps the bogus
/// value bogus, so it shows up as an obviously wrong vertex rather than a subtly
/// wrong one.
fn round_i32(v: f64) -> i32 {
    if v.is_nan() {
        return 0;
    }
    v.round().clamp(i32::MIN as f64, i32::MAX as f64) as i32
}

fn dedup(it: impl Iterator<Item = IPt>) -> Vec<IPt> {
    let mut out: Vec<IPt> = Vec::new();
    for p in it {
        if out.last() != Some(&p) {
            out.push(p);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn projection_anchors_are_right() {
        // z0: the whole world is one tile, and 0,0 sits at its centre.
        let (x, y) = project(0.0, 0.0, 0);
        assert!((x - 0.5).abs() < 1e-9, "lon 0 -> x 0.5, got {x}");
        assert!((y - 0.5).abs() < 1e-9, "lat 0 -> y 0.5, got {y}");
        let (x, _) = project(-180.0, 0.0, 0);
        assert!(x.abs() < 1e-9);
        let (_, y) = project(0.0, 85.051_128_78, 0);
        assert!(y.abs() < 1e-6, "Mercator top -> y 0, got {y}");
        // A pole must clamp rather than diverge.
        let (_, y) = project(0.0, 90.0, 4);
        assert!(y.is_finite(), "lat 90 must clamp, got {y}");
        // San Francisco at z11 is a well-known tile.
        let (fx, fy) = project(-122.4194, 37.7749, 11);
        assert_eq!((fx.floor() as u64, fy.floor() as u64), (327, 791));
    }

    #[test]
    fn scaling_puts_a_point_inside_its_own_tile() {
        let extent = DEFAULT_EXTENT;
        let (wx, wy) = project_scaled(-122.4194, 37.7749, 11, extent);
        let (tx, ty) = (327u64, 791u64);
        let local = (wx - tx as f64 * extent as f64, wy - ty as f64 * extent as f64);
        assert!(
            (0.0..=extent as f64).contains(&local.0) && (0.0..=extent as f64).contains(&local.1),
            "{local:?} must be inside [0,{extent}]"
        );
    }

    #[test]
    fn buffer_scales_with_the_extent() {
        assert_eq!(buffer_for(DEFAULT_EXTENT), DEFAULT_BUFFER);
        assert_eq!(buffer_for(8192), DEFAULT_BUFFER * 2.0);
        assert_eq!(buffer_for(2048), DEFAULT_BUFFER / 2.0);
    }

    #[test]
    fn bounds_cover_every_part_and_ignore_non_finite() {
        let g = Geometry::Lines(vec![
            vec![(0.0, 0.0), (10.0, 5.0)],
            vec![(-3.0, 20.0), (4.0, 1.0)],
        ]);
        assert_eq!(
            bounds(&g),
            Some(Rect { min_x: -3.0, min_y: 0.0, max_x: 10.0, max_y: 20.0 })
        );
        assert_eq!(bounds(&Geometry::Points(vec![])), None);

        // A NaN vertex is skipped rather than poisoning the whole box.
        let g = Geometry::Points(vec![(1.0, 2.0), (f64::NAN, 0.0), (3.0, 4.0)]);
        assert_eq!(
            bounds(&g),
            Some(Rect { min_x: 1.0, min_y: 2.0, max_x: 3.0, max_y: 4.0 })
        );
        assert_eq!(bounds(&Geometry::Points(vec![(f64::NAN, f64::NAN)])), None);
    }

    #[test]
    fn a_tile_range_covers_exactly_the_tiles_touched() {
        let e = 4096u32;
        // Entirely inside tile (1,1).
        let b = Rect { min_x: 1.5 * e as f64, min_y: 1.5 * e as f64, max_x: 1.6 * e as f64, max_y: 1.6 * e as f64 };
        assert_eq!(
            tile_range(&b, 4, e, 0.0),
            Some(TileRange { x0: 1, y0: 1, x1: 1, y1: 1 })
        );
        // Straddling into (2,2).
        let b = Rect { min_x: 1.5 * e as f64, min_y: 1.5 * e as f64, max_x: 2.5 * e as f64, max_y: 2.5 * e as f64 };
        assert_eq!(
            tile_range(&b, 4, e, 0.0),
            Some(TileRange { x0: 1, y0: 1, x1: 2, y1: 2 })
        );
    }

    #[test]
    fn the_pad_pulls_in_the_neighbouring_tile() {
        let e = 4096u32;
        // Two units inside tile 1's left edge. With no pad it touches only tile 1;
        // with a 5-unit pad it also reaches into tile 0's buffer, which is the
        // whole reason the buffer exists.
        let x = e as f64 + 2.0;
        let b = Rect { min_x: x, min_y: x, max_x: x, max_y: x };
        assert_eq!(
            tile_range(&b, 4, e, 0.0),
            Some(TileRange { x0: 1, y0: 1, x1: 1, y1: 1 })
        );
        assert_eq!(
            tile_range(&b, 4, e, 5.0),
            Some(TileRange { x0: 0, y0: 0, x1: 1, y1: 1 })
        );
    }

    #[test]
    fn a_tile_range_clamps_to_the_grid_and_rejects_the_wholly_outside() {
        let e = 4096u32;
        let n = 1u64 << 4;
        // Overhanging both ends is clamped, not wrapped.
        let b = Rect { min_x: -100.0, min_y: -100.0, max_x: (n as f64 + 3.0) * e as f64, max_y: (n as f64 + 3.0) * e as f64 };
        assert_eq!(
            tile_range(&b, 4, e, 0.0),
            Some(TileRange { x0: 0, y0: 0, x1: n - 1, y1: n - 1 })
        );
        // Wholly off the grid.
        let b = Rect { min_x: -10.0 * e as f64, min_y: 0.0, max_x: -9.0 * e as f64, max_y: e as f64 };
        assert_eq!(tile_range(&b, 4, e, 0.0), None);
        // NaN in, None out.
        let b = Rect { min_x: f64::NAN, min_y: 0.0, max_x: 1.0, max_y: 1.0 };
        assert_eq!(tile_range(&b, 4, e, 0.0), None);
    }

    /// The whole point of `tiles_touched`: a long diagonal must not claim the empty
    /// corners of its bounding box. This is the case that made `transit_lines` at
    /// planet scale take hours.
    #[test]
    fn a_diagonal_line_skips_the_corners_of_its_bounding_box() {
        let extent = 4096u32;
        let e = extent as f64;
        // A staircase across a 40 x 40 tile square, one vertex per tile step, so the
        // line genuinely passes through ~40 tiles out of 1600.
        let pts: Vec<Pt> = (0..=40).map(|i| (i as f64 * e, i as f64 * e)).collect();
        let g = Geometry::Lines(vec![pts]);

        let mut touched = Vec::new();
        tiles_touched(&g, 8, extent, 0.0, &mut touched);

        let bbox = tile_range(&bounds(&g).unwrap(), 8, extent, 0.0).unwrap();
        assert_eq!(bbox.len(), 41 * 41, "the box really is that big");
        // Every vertex sits exactly on a tile corner here, which is the worst case:
        // each segment's box spans 2x2 tiles rather than 1. Even so the walk is an
        // order of magnitude below the box, and that ratio is what grows with zoom.
        assert!(
            (touched.len() as u64) * 10 < bbox.len(),
            "walked {} tiles, more than a tenth of the box's {}",
            touched.len(),
            bbox.len()
        );

        // Every tile on the diagonal is present...
        for i in 0..=40u64 {
            assert!(touched.contains(&(i, i)), "tile ({i},{i}) is on the line");
        }
        // ...and the far corners, which the line never approaches, are not.
        assert!(!touched.contains(&(0, 40)), "bottom-left corner is empty");
        assert!(!touched.contains(&(40, 0)), "top-right corner is empty");
    }

    /// Safety property: it may over-include, never under-include. Anything it lists
    /// is inside the feature's bounding box, and every segment's own box is covered.
    #[test]
    fn tiles_touched_is_a_subset_of_the_bounding_box_and_covers_every_segment() {
        let extent = 4096u32;
        let e = extent as f64;
        let g = Geometry::Lines(vec![vec![
            (0.5 * e, 0.5 * e),
            (3.5 * e, 1.5 * e),
            (2.0 * e, 6.0 * e),
        ]]);

        let mut touched = Vec::new();
        tiles_touched(&g, 8, extent, 0.0, &mut touched);
        let bbox = tile_range(&bounds(&g).unwrap(), 8, extent, 0.0).unwrap();
        let inside: Vec<(u64, u64)> = bbox.iter().collect();
        for t in &touched {
            assert!(inside.contains(t), "{t:?} is outside the bounding box");
        }

        // Each segment's own tile range must be fully present, which is what makes
        // this a conservative superset of the tiles the line actually enters.
        let pts = match &g {
            Geometry::Lines(l) => l[0].clone(),
            _ => unreachable!(),
        };
        for seg in pts.windows(2) {
            let b = Rect {
                min_x: seg[0].0.min(seg[1].0),
                min_y: seg[0].1.min(seg[1].1),
                max_x: seg[0].0.max(seg[1].0),
                max_y: seg[0].1.max(seg[1].1),
            };
            for t in tile_range(&b, 8, extent, 0.0).unwrap().iter() {
                assert!(touched.contains(&t), "segment tile {t:?} was dropped");
            }
        }
    }

    /// A polygon covers its interior, so its footprint stays the whole ring box --
    /// per-segment boxes would drop every tile strictly inside it.
    #[test]
    fn a_polygon_still_claims_its_interior_tiles() {
        let extent = 4096u32;
        let e = extent as f64;
        let ring = vec![
            (0.5 * e, 0.5 * e),
            (5.5 * e, 0.5 * e),
            (5.5 * e, 5.5 * e),
            (0.5 * e, 5.5 * e),
            (0.5 * e, 0.5 * e),
        ];
        let g = Geometry::Polygons(vec![vec![ring]]);

        let mut touched = Vec::new();
        tiles_touched(&g, 8, extent, 0.0, &mut touched);

        // (3,3) is strictly inside and touches no edge.
        assert!(touched.contains(&(3, 3)), "interior tile must be filled");
        assert_eq!(touched.len(), 36, "the whole 6x6 footprint");
    }

    /// Adjacent segments share tiles; a duplicate would encode the geometry twice.
    #[test]
    fn tiles_touched_deduplicates_and_sorts() {
        let extent = 4096u32;
        let e = extent as f64;
        // Many short segments all inside one tile.
        let pts: Vec<Pt> = (0..20).map(|i| (0.1 * e + i as f64, 0.1 * e)).collect();
        let mut touched = Vec::new();
        tiles_touched(&Geometry::Lines(vec![pts]), 8, extent, 0.0, &mut touched);
        assert_eq!(touched, vec![(0, 0)]);

        let mut sorted = touched.clone();
        sorted.sort_unstable();
        assert_eq!(touched, sorted, "output is sorted for a deterministic pass");
    }

    #[test]
    fn tile_range_iterates_row_major() {
        let r = TileRange { x0: 2, y0: 5, x1: 3, y1: 6 };
        assert_eq!(r.len(), 4);
        assert_eq!(
            r.iter().collect::<Vec<_>>(),
            vec![(2, 5), (3, 5), (2, 6), (3, 6)]
        );
    }

    #[test]
    fn a_tile_rect_is_the_tile_plus_its_buffer() {
        let r = tile_rect(2, 3, 4096, 5.0);
        assert_eq!(
            r,
            Rect { min_x: 8192.0 - 5.0, min_y: 12288.0 - 5.0, max_x: 12288.0 + 5.0, max_y: 16384.0 + 5.0 }
        );
        assert!(r.contains((8192.0, 12288.0)));
        assert!(r.contains((8192.0 - 4.0, 12288.0 - 4.0)), "inside the buffer");
        assert!(!r.contains((8192.0 - 6.0, 12288.0)), "beyond the buffer");
    }

    #[test]
    fn to_tile_translates_then_rounds() {
        // A vertex at world (4096.4, 8191.6) is (0.4, 4095.6) inside tile (1,1),
        // which rounds to (0, 4096).
        let g = Geometry::Lines(vec![vec![(4096.4, 8191.6), (5000.0, 9000.0)]]);
        let IntGeometry::Lines(lines) = to_tile(&g, 1, 1, 4096) else {
            panic!("lines in, lines out")
        };
        assert_eq!(lines[0][0], (0, 4096));
        assert_eq!(lines[0][1], (904, 4904));
    }

    #[test]
    fn quantisation_drops_the_duplicates_it_creates() {
        // Three vertices within a rounding unit of each other collapse to one.
        let g = Geometry::Lines(vec![vec![
            (10.1, 10.1),
            (10.2, 10.3),
            (9.9, 10.0),
            (20.0, 20.0),
        ]]);
        let IntGeometry::Lines(lines) = quantize(&g) else { panic!() };
        assert_eq!(lines[0], vec![(10, 10), (20, 20)]);

        // Non-adjacent repeats are kept: a line that doubles back is a real shape.
        let g = Geometry::Lines(vec![vec![(0.0, 0.0), (5.0, 0.0), (0.0, 0.0)]]);
        let IntGeometry::Lines(lines) = quantize(&g) else { panic!() };
        assert_eq!(lines[0], vec![(0, 0), (5, 0), (0, 0)]);
    }

    #[test]
    fn quantisation_keeps_a_rings_closing_vertex() {
        // ClosePath is applied by the encoder, not here, so the ring stays closed
        // through simplification -- which needs the closure to measure against.
        let ring = vec![(0.0, 0.0), (4.0, 0.0), (4.0, 4.0), (0.0, 4.0), (0.0, 0.0)];
        let IntGeometry::Polygons(polys) = quantize(&Geometry::Polygons(vec![vec![ring]])) else {
            panic!()
        };
        assert_eq!(polys[0][0].len(), 5);
        assert_eq!(polys[0][0].first(), polys[0][0].last());
    }

    #[test]
    fn a_non_finite_vertex_saturates_rather_than_wrapping() {
        // `f64::NAN as i32` is 0 in Rust and `1e300 as i32` is the saturated bound,
        // but both are silent. Keeping the saturation explicit means an out-of-range
        // vertex stays obviously out of range instead of landing somewhere
        // plausible; a NaN has no meaningful bound, so it becomes the origin.
        let g = Geometry::Points(vec![(f64::NAN, 1e300), (-1e300, 5.0)]);
        let IntGeometry::Points(pts) = quantize(&g) else { panic!() };
        assert_eq!(pts, vec![(0, i32::MAX), (i32::MIN, 5)]);
    }

    #[test]
    fn emptiness_is_judged_per_geometry_kind() {
        assert!(IntGeometry::Points(vec![]).is_empty());
        assert!(!IntGeometry::Points(vec![(0, 0)]).is_empty());
        assert!(IntGeometry::Lines(vec![vec![(0, 0)]]).is_empty());
        assert!(!IntGeometry::Lines(vec![vec![(0, 0), (1, 1)]]).is_empty());
        // Three distinct vertices are the least that can enclose an area.
        assert!(IntGeometry::Polygons(vec![vec![vec![(0, 0), (1, 0)]]]).is_empty());
        assert!(!IntGeometry::Polygons(vec![vec![vec![(0, 0), (1, 0), (1, 1)]]]).is_empty());
    }

    #[test]
    fn rects_intersect_symmetrically_and_touch_counts() {
        let a = Rect { min_x: 0.0, min_y: 0.0, max_x: 10.0, max_y: 10.0 };
        let b = Rect { min_x: 10.0, min_y: 10.0, max_x: 20.0, max_y: 20.0 };
        let c = Rect { min_x: 11.0, min_y: 0.0, max_x: 20.0, max_y: 10.0 };
        assert!(a.intersects(&b) && b.intersects(&a), "touching corners overlap");
        assert!(!a.intersects(&c) && !c.intersects(&a));
    }
}
