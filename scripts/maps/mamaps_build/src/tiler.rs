//! Stages B, D and E: features in, one `.mamaps` archive out.
//!
//! Zoom by zoom, and within a zoom tile by tile in ascending tile-id order, which is exactly what
//! [`StreamWriter`] requires. That ordering is free rather than arranged: `pmtiles::tile_id` is
//! zoom-major, so every id at z*n* precedes every id at z*n+1*, and sorting within a zoom finishes
//! it.
//!
//! # What is reused
//!
//! All of the geometry, from `tile_build`:
//!
//! * [`geom::project_geometry`] to web-mercator tile units, carrying per-vertex significance.
//! * [`simplify::annotate`] then [`simplify::filter`], which is significance-first simplification:
//!   a vertex's importance is computed **once** on the whole geometry and only then filtered per
//!   zoom, so the same vertex survives or does not regardless of which tile it lands in. Doing it
//!   the other way round is what lets a shared edge simplify differently on each side and open a
//!   seam.
//! * [`geom::tiles_touched`], whose per-segment bisection is not optional: a transcontinental line
//!   touches 16 632 tiles against 34 535 986 for its bounding box.
//! * [`clip`]'s Liang-Barsky for lines and Sutherland-Hodgman for rings, against a tile rect with a
//!   buffer so a stroke at the edge has geometry to join to.
//!
//! # Memory
//!
//! One zoom's `tile -> features` map at a time, held in memory. The plan's design spills that to
//! disk through `tile_build::spill` and will need to for a planet build; a California
//! water-and-buildings run is a few hundred megabytes, so this does not yet. The shape is the same
//! either way — the writer already streams — so the change is local when it is needed.

use std::collections::BTreeMap;

use tile_build::geom::{self, Geometry, IntGeometry, SigPt};
use tile_build::{clip, simplify};
use tilecodec::mamaps::body::{
    Body, Feature as BodyFeature, Layer as BodyLayer, Part, GEOM_LINE, GEOM_POLYGON, WINDING_HOLE,
    WINDING_OUTER,
};
use tilecodec::mamaps::write::{Options, StreamWriter};
use tilecodec::pmtiles::tile_id;
use tilecodec::proto::{err, Result};

use crate::extract::Feature;

/// The tile grid, matching MVT's so nothing downstream rescales.
pub const EXTENT: u32 = 4096;

/// How aggressively to simplify, as a multiple of `tile_build`'s per-zoom tolerance.
pub const DEFAULT_SIMPLIFICATION: f64 = 1.0;

/// Per-zoom counts for the build report.
#[derive(Debug, Default, Clone, Copy)]
pub struct ZoomStats {
    pub zoom: u8,
    pub tiles: u64,
    pub features: u64,
    pub points: u64,
    /// Features dropped because simplification left nothing, or the clip did.
    pub dropped: u64,
    pub bytes: u64,
}

pub struct Settings {
    pub min_zoom: u8,
    pub max_zoom: u8,
    pub simplification: f64,
    pub build_id: u64,
}

/// Tile every feature and write the archive.
pub fn build(features: &[Feature], settings: &Settings) -> Result<(Vec<u8>, Vec<ZoomStats>)> {
    let bbox = bounds_e7(features);
    let mut writer = StreamWriter::new(Options {
        min_zoom: settings.min_zoom,
        max_zoom: settings.max_zoom,
        build_id: settings.build_id,
        compress: true,
        // Not until stage C exists. Claiming it would let the renderer skip a repair pass that is
        // still the only thing standing between a bad ring and an earcut hang.
        rings_validated: false,
        min_lon_e7: bbox.0,
        min_lat_e7: bbox.1,
        max_lon_e7: bbox.2,
        max_lat_e7: bbox.3,
        ..Options::default()
    })?;

    let mut per_zoom = Vec::new();
    for z in settings.min_zoom..=settings.max_zoom {
        let mut stats = ZoomStats { zoom: z, ..ZoomStats::default() };
        // Keyed on the **tile id**, not on `(x, y)`. Those are different orders: `tile_id` walks a
        // Hilbert curve, so row-major `(x, y)` ascends through it out of sequence and the writer
        // rejects the archive. Cheap to get wrong and caught only by an ordering check, which is
        // why the writer has one.
        let mut tiles: BTreeMap<u64, BTreeMap<u8, BodyLayer>> = BTreeMap::new();
        let tolerance = simplify::tolerance_for(z, settings.max_zoom, settings.simplification);
        let buffer = geom::buffer_for(EXTENT);
        let mut touched: Vec<(u64, u64)> = Vec::new();

        for feature in features {
            if z < feature.class.min_zoom {
                continue;
            }
            let mut projected = geom::project_geometry(&feature.geometry, z, EXTENT);
            // Significance first, then filter: computed on the whole geometry so a vertex's fate
            // does not depend on which tile it lands in.
            simplify::annotate(&mut projected);
            let thinned = simplify::filter(&projected, tolerance);
            if is_empty(&thinned) {
                stats.dropped += 1;
                continue;
            }
            geom::tiles_touched(&thinned, z, EXTENT, buffer, &mut touched);
            for &(tx, ty) in &touched {
                let rect = geom::tile_rect(tx, ty, EXTENT, buffer);
                let clipped = clip::clip_geometry(&thinned, &rect);
                if is_empty(&clipped) {
                    continue;
                }
                let local = geom::to_tile(&clipped, tx, ty, EXTENT);
                let layer = tiles
                    .entry(tile_id(z, tx, ty))
                    .or_default()
                    .entry(feature.class.layer)
                    .or_insert_with(|| BodyLayer::new(feature.class.layer));
                let added = push(layer, feature, &local);
                stats.features += added.0;
                stats.points += added.1;
            }
        }

        // A `BTreeMap` keyed on the tile id, so the emit order **is** the writer's required order
        // and no sort step can be forgotten. A `HashMap` here is the single easiest way to lose
        // byte-identical rebuilds.
        for (id, layers) in tiles {
            // A layer that ended up empty \u2014 every feature in it fell below the minimum area after
            // clipping \u2014 costs bytes in the archive and a draw call on device for nothing.
            let layers: Vec<BodyLayer> =
                layers.into_values().filter(|l| !l.features.is_empty()).collect();
            if layers.is_empty() {
                continue;
            }
            let body = Body { extent: EXTENT as u16, layers };
            let encoded = tilecodec::mamaps::body::serialize(&body)?;
            stats.bytes += encoded.len() as u64;
            stats.tiles += 1;
            writer.append_encoded(id, &encoded)?;
        }
        per_zoom.push(stats);
    }

    let bytes = writer.finish()?;
    Ok((bytes, per_zoom))
}

/// Append one feature's tile-local geometry to a layer. Returns `(features, points)` added.
///
/// A polygon becomes **one feature per ring group**, so a feature's parts are exactly one exterior
/// and its holes — which is what the tessellator wants and what makes "one outer per feature" an
/// invariant worth stating.
fn push(layer: &mut BodyLayer, feature: &Feature, geometry: &IntGeometry) -> (u64, u64) {
    let class = &feature.class;
    let mut added = (0u64, 0u64);
    match geometry {
        IntGeometry::Polygons(polygons) => {
            // Below a few square pixels a polygon is a speck rather than detail, and there are
            // millions of them. Measured **after** clipping, on the shape that would actually be
            // drawn, so a large park clipped to a sliver of one tile is kept where it is big and
            // dropped where it is not.
            let floor = crate::schema::land::min_area_units(class.min_area_px, EXTENT);
            for rings in polygons {
                // A ring needs three distinct points plus the closing one; anything less bounds no
                // area. Filtered *before* anything is appended, because a part and its points have
                // to be committed together — the encoder requires the parts to tile the arena
                // exactly, so half-appending a group and rolling it back would leave orphaned
                // coordinates.
                let keep: Vec<(usize, &Vec<(i32, i32)>)> =
                    rings.iter().enumerate().filter(|(_, ring)| ring.len() >= 4).collect();
                // If the exterior did not survive, what is left is a hole with nothing to be a hole
                // in, which would paint as the inverse of the shape.
                if !keep.first().is_some_and(|(index, _)| *index == 0) {
                    continue;
                }
                if floor > 0.0 && ring_area(keep[0].1) < floor {
                    continue;
                }
                let parts_offset = layer.parts.len() as u32;
                for (index, ring) in &keep {
                    added.1 += push_part(
                        layer,
                        ring,
                        if *index == 0 { WINDING_OUTER } else { WINDING_HOLE },
                    );
                }
                layer.features.push(BodyFeature {
                    kind: class.kind,
                    kind_detail: class.kind_detail,
                    geom_type: GEOM_POLYGON,
                    flags: class.flags,
                    parts_offset,
                    part_count: keep.len() as u32,
                });
                added.0 += 1;
            }
        }
        IntGeometry::Lines(lines) => {
            let parts_offset = layer.parts.len() as u32;
            for line in lines {
                if line.len() < 2 {
                    continue;
                }
                added.1 += push_part(layer, line, WINDING_OUTER);
            }
            let part_count = layer.parts.len() as u32 - parts_offset;
            if part_count > 0 {
                layer.features.push(BodyFeature {
                    kind: class.kind,
                    kind_detail: class.kind_detail,
                    geom_type: GEOM_LINE,
                    flags: class.flags,
                    parts_offset,
                    part_count,
                });
                added.0 += 1;
            }
        }
        // Points are not carried: the renderer decoded them and threw them away.
        IntGeometry::Points(_) => {}
    }
    added
}

/// Append one path's points to a layer's arena, clamped to what an `i16` holds.
///
/// The clip buffer keeps a coordinate within a few percent of the extent, so `i16` has eight times
/// the headroom needed and this never bites. Clamping rather than asserting because a single stray
/// vertex should not fail a whole build.
fn push_part(layer: &mut BodyLayer, points: &[(i32, i32)], winding: u16) -> u64 {
    let coord_start = layer.coords.len() as u32;
    for &(x, y) in points {
        layer.coords.push((
            x.clamp(i16::MIN as i32, i16::MAX as i32) as i16,
            y.clamp(i16::MIN as i32, i16::MAX as i32) as i16,
        ));
    }
    layer.parts.push(Part { coord_start, point_count: points.len() as u32, winding });
    points.len() as u64
}

/// A ring's absolute area, by the shoelace formula.
///
/// `i64` throughout: a 4096-unit tile's coordinates cross-multiply to about 2^24 per term, and a
/// long ring accumulates thousands of them, which overflows an `i32` and would silently report a
/// huge shape as a tiny one.
fn ring_area(ring: &[(i32, i32)]) -> f64 {
    let mut twice = 0i64;
    for pair in ring.windows(2) {
        let ((x0, y0), (x1, y1)) = (pair[0], pair[1]);
        twice += x0 as i64 * y1 as i64 - x1 as i64 * y0 as i64;
    }
    (twice.abs() as f64) / 2.0
}

fn is_empty(g: &Geometry<SigPt>) -> bool {
    match g {
        Geometry::Points(points) => points.is_empty(),
        Geometry::Lines(lines) => lines.iter().all(|l| l.len() < 2),
        Geometry::Polygons(polygons) => polygons.iter().all(|rings| {
            rings.first().map(|ring| ring.len() < 4).unwrap_or(true)
        }),
    }
}

/// The bounding box of everything, in degrees times 1e7, for the archive header.
fn bounds_e7(features: &[Feature]) -> (i32, i32, i32, i32) {
    let mut out = (i32::MAX, i32::MAX, i32::MIN, i32::MIN);
    let mut seen = false;
    for feature in features {
        for (lon, lat) in coords(&feature.geometry) {
            seen = true;
            let (x, y) = ((lon * 1e7) as i32, (lat * 1e7) as i32);
            out.0 = out.0.min(x);
            out.1 = out.1.min(y);
            out.2 = out.2.max(x);
            out.3 = out.3.max(y);
        }
    }
    if seen {
        out
    } else {
        (0, 0, 0, 0)
    }
}

fn coords(g: &Geometry) -> Vec<(f64, f64)> {
    match g {
        Geometry::Points(points) => points.clone(),
        Geometry::Lines(lines) => lines.iter().flatten().copied().collect(),
        Geometry::Polygons(polygons) => {
            polygons.iter().flatten().flatten().copied().collect()
        }
    }
}

/// A build that produced nothing is a build whose schema matched nothing, which is worth failing on
/// rather than publishing an empty archive.
pub fn check_not_empty(stats: &[ZoomStats]) -> Result<()> {
    if stats.iter().all(|s| s.tiles == 0) {
        return err("the build produced no tiles: nothing in the input matched the schema");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::Class;
    use tilecodec::mamaps::dict;

    fn square(lon: f64, lat: f64, size: f64) -> Geometry {
        Geometry::Polygons(vec![vec![vec![
            (lon, lat),
            (lon + size, lat),
            (lon + size, lat + size),
            (lon, lat + size),
            (lon, lat),
        ]]])
    }

    fn lake(lon: f64, lat: f64, size: f64, min_zoom: u8) -> Feature {
        Feature {
            class: Class::area(dict::LAYER_WATER, crate::schema::kind("lake"), min_zoom),
            geometry: square(lon, lat, size),
        }
    }

    fn settings(min_zoom: u8, max_zoom: u8) -> Settings {
        Settings { min_zoom, max_zoom, simplification: DEFAULT_SIMPLIFICATION, build_id: 7 }
    }

    #[test]
    fn a_lake_tiles_and_reads_back_out_of_the_archive() {
        let features = vec![lake(-120.0, 35.0, 0.5, 0)];
        let (bytes, stats) = build(&features, &settings(0, 6)).expect("build");
        check_not_empty(&stats).expect("not empty");

        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read back");
        assert!(!entries.is_empty());
        // Every stored body holds the water layer and nothing else.
        for (_, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            let layer = body.layer(dict::LAYER_WATER).expect("water");
            assert!(!layer.features.is_empty());
            assert_eq!(layer.features[0].geom_type, GEOM_POLYGON);
            assert!(body.layer(dict::LAYER_BUILDINGS).is_none());
        }
    }

    /// A feature's `min_zoom` is what keeps a world tile from carrying every pond. Enforced here,
    /// so the archive does not hold what the style would not draw.
    #[test]
    fn a_feature_is_not_written_above_its_own_min_zoom() {
        let features = vec![lake(-120.0, 35.0, 0.01, 10)];
        let (_, stats) = build(&features, &settings(0, 11)).expect("build");
        for s in &stats {
            if s.zoom < 10 {
                assert_eq!(s.tiles, 0, "z{} should be empty", s.zoom);
            }
        }
        assert!(stats.iter().any(|s| s.zoom >= 10 && s.tiles > 0), "and present once it is due");
    }

    /// **Invariant 4 and 5 together.** Ids ascend across the whole archive because `tile_id` is
    /// zoom-major and every emit path is a `BTreeMap`, and the output is byte-identical run to run
    /// because nothing iterates a hash map.
    #[test]
    fn the_output_is_byte_identical_and_its_ids_ascend() {
        let features = vec![
            lake(-120.0, 35.0, 0.4, 0),
            lake(-119.0, 36.0, 0.3, 0),
            Feature {
                class: Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 0),
                geometry: square(-120.1, 35.1, 0.02),
            },
        ];
        let first = build(&features, &settings(0, 8)).expect("first").0;
        let second = build(&features, &settings(0, 8)).expect("second").0;
        assert_eq!(first, second, "two runs of the same input");

        let entries = tilecodec::mamaps::read::read_all(&first).expect("read");
        assert!(entries.windows(2).all(|p| p[1].0 > p[0].0), "ids ascend");
        for (id, _, _) in &entries {
            let (z, _, _) = tilecodec::pmtiles::tile_zxy(*id);
            let range = tilecodec::pmtiles::zoom_base(z)..tilecodec::pmtiles::zoom_base(z + 1);
            assert!(range.contains(id), "id {id} is outside z{z}");
        }
    }

    /// Two layers in one tile, which is the shape the format exists for: a cold tile is one range
    /// request whatever the style is drawing.
    #[test]
    fn a_tile_carrying_two_layers_holds_them_both() {
        let features = vec![
            lake(-120.0, 35.0, 0.02, 0),
            Feature {
                class: Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 0),
                geometry: square(-120.005, 35.005, 0.002),
            },
        ];
        let (bytes, _) = build(&features, &settings(14, 14)).expect("build");
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let both = entries.iter().any(|(_, _, body)| {
            let body = Body::parse(body).expect("parse");
            body.layer(dict::LAYER_WATER).is_some() && body.layer(dict::LAYER_BUILDINGS).is_some()
        });
        assert!(both, "some tile should carry both");
    }

    #[test]
    fn a_build_that_matched_nothing_fails_rather_than_publishing_an_empty_archive() {
        let stats = vec![ZoomStats { zoom: 0, ..ZoomStats::default() }];
        assert!(check_not_empty(&stats).is_err());
        assert!(build(&[], &settings(0, 2)).is_err(), "the writer refuses an empty archive");
    }

    /// Simplification is per zoom, so a shallow tile holds fewer points for the same shape. If this
    /// ever inverts, the tolerance is being applied at the wrong end.
    #[test]
    fn a_shallow_zoom_carries_fewer_points_than_a_deep_one() {
        // A wiggly line, so there is something to simplify away.
        let points: Vec<(f64, f64)> = (0..200)
            .map(|i| (-120.0 + i as f64 * 0.001, 35.0 + (i % 3) as f64 * 0.0005))
            .collect();
        let features = vec![Feature {
            class: Class::line(dict::LAYER_WATER, crate::schema::kind("river"), 0),
            geometry: Geometry::Lines(vec![points]),
        }];
        let (_, stats) = build(&features, &settings(6, 14)).expect("build");
        let at = |z: u8| stats.iter().find(|s| s.zoom == z).expect("zoom").points;
        assert!(at(6) < at(14), "z6 has {} points, z14 has {}", at(6), at(14));
    }
}
