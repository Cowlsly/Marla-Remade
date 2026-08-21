//! Tile a point layer, and merge tilesets — the `tippecanoe` and `tile-join`
//! replacements.
//!
//! Only points are *generated*: `transit_stops` is the layer we build, and a point
//! needs no clipping, simplification or winding-order handling. Lines and polygons
//! are only ever *carried through* by [`merge_tiles`], which never looks inside a
//! geometry stream.

use crate::mvt::{self, Feature, GeomType, Layer, Tile, Value, DEFAULT_EXTENT};
use crate::pmtiles::{self, Archive, Builder};
use crate::proto::Result;
use std::collections::HashMap;

/// One point to be tiled.
#[derive(Debug, Clone)]
pub struct Point {
    pub lon: f64,
    pub lat: f64,
    pub props: Vec<(String, Value)>,
}

/// Web-Mercator project a lon/lat to fractional tile coordinates at `z`.
///
/// Latitude is clamped to the Mercator limit: the projection diverges at the
/// poles, and a feed with a `0,0`-style placeholder stop would otherwise produce
/// an infinity.
fn project(lon: f64, lat: f64, z: u8) -> (f64, f64) {
    let n = (1u64 << z) as f64;
    let lat = lat.clamp(-85.051_128_78, 85.051_128_78);
    let x = (lon.clamp(-180.0, 180.0) + 180.0) / 360.0 * n;
    let s = lat.to_radians().sin();
    let y = (0.5 - (((1.0 + s) / (1.0 - s)).ln()) / (4.0 * std::f64::consts::PI)) * n;
    (x, y)
}

/// Bucket points into tiles for one zoom and encode each as an MVT.
///
/// Returns `(tile_id, mvt body)`. Points landing outside the tile grid are
/// dropped rather than clamped into the edge tile, which would pile every bad
/// coordinate onto one pin.
pub fn tile_points(
    layer_name: &str,
    points: &[Point],
    z: u8,
    extent: u32,
) -> Vec<(u64, Vec<u8>)> {
    let n = 1u64 << z;
    let mut by_tile: HashMap<(u64, u64), Vec<(i32, i32, usize)>> = HashMap::new();
    for (i, p) in points.iter().enumerate() {
        let (fx, fy) = project(p.lon, p.lat, z);
        if !fx.is_finite() || !fy.is_finite() {
            continue;
        }
        let (tx, ty) = (fx.floor(), fy.floor());
        if tx < 0.0 || ty < 0.0 || tx >= n as f64 || ty >= n as f64 {
            continue;
        }
        let (tx, ty) = (tx as u64, ty as u64);
        // Position within the tile, quantised to the extent grid.
        let px = ((fx - tx as f64) * extent as f64).round() as i32;
        let py = ((fy - ty as f64) * extent as f64).round() as i32;
        by_tile.entry((tx, ty)).or_default().push((
            px.clamp(0, extent as i32),
            py.clamp(0, extent as i32),
            i,
        ));
    }

    let mut out = Vec::with_capacity(by_tile.len());
    for ((tx, ty), mut pts) in by_tile {
        // Deterministic feature order, so a rebuild is byte-identical.
        pts.sort_by_key(|&(x, y, i)| (x, y, i));
        let mut layer = Layer::new(layer_name);
        layer.extent = extent;
        for (x, y, i) in pts {
            layer.features.push(Feature {
                id: None,
                geom_type: GeomType::Point,
                geometry: mvt::encode_points(&[(x, y)]),
                props: points[i].props.clone(),
            });
        }
        let tile = Tile { layers: vec![layer] };
        out.push((pmtiles::tile_id(z, tx, ty), tile.encode()));
    }
    out.sort_by_key(|(id, _)| *id);
    out
}

/// Build a single-layer point archive across `min_zoom..=max_zoom`.
pub fn build_point_archive(
    layer_name: &str,
    points: &[Point],
    min_zoom: u8,
    max_zoom: u8,
) -> Result<Vec<u8>> {
    let mut b = Builder::new();
    b.min_zoom = min_zoom;
    b.max_zoom = max_zoom;
    b.center_zoom = min_zoom;
    b.metadata = point_metadata(layer_name, min_zoom, max_zoom).into_bytes();
    for z in min_zoom..=max_zoom {
        for (id, body) in tile_points(layer_name, points, z, DEFAULT_EXTENT) {
            b.add_tile_raw(id, crate::gz::compress(&body));
        }
    }
    b.build()
}

/// The `json` metadata blob a PMTiles archive carries. MapLibre does not need it
/// to render a styled layer, but `pmtiles show` and friends read it, so emitting a
/// truthful vector_layers list keeps the archive introspectable.
fn point_metadata(layer_name: &str, min_zoom: u8, max_zoom: u8) -> String {
    format!(
        "{{\"vector_layers\":[{{\"id\":\"{layer_name}\",\"minzoom\":{min_zoom},\
         \"maxzoom\":{max_zoom}}}]}}"
    )
}

/// Merge several tilesets into one, unioning each tile's layers.
///
/// This is the `tile-join` replacement. Later inputs win a name collision, so a
/// freshly built layer replaces a stale copy of itself in the base archive. Tiles
/// present in only one input are carried through as-is.
///
/// Both the decode and the re-encode are lossless for geometry, which is what lets
/// the base tileset's lines and polygons survive a merge untouched.
pub fn merge_archives(inputs: &[&Archive]) -> Result<Vec<u8>> {
    let mut min_zoom = u8::MAX;
    let mut max_zoom = 0u8;
    // tile_id -> the tile's MVT from each input, in input order.
    let mut collected: HashMap<u64, Vec<Vec<u8>>> = HashMap::new();
    for a in inputs {
        min_zoom = min_zoom.min(a.header.min_zoom);
        max_zoom = max_zoom.max(a.header.max_zoom);
        for (id, raw) in a.iter_tiles()? {
            let body = match a.header.tile_compression {
                pmtiles::COMPRESSION_NONE => raw.to_vec(),
                _ => crate::gz::decompress(raw)?,
            };
            collected.entry(id).or_default().push(body);
        }
    }

    let first = inputs.first();
    let mut b = Builder::new();
    b.min_zoom = if min_zoom == u8::MAX { 0 } else { min_zoom };
    b.max_zoom = max_zoom;
    if let Some(a) = first {
        b.metadata = a.metadata.clone();
        b.min_lon_e7 = a.header.min_lon_e7;
        b.min_lat_e7 = a.header.min_lat_e7;
        b.max_lon_e7 = a.header.max_lon_e7;
        b.max_lat_e7 = a.header.max_lat_e7;
        b.center_zoom = a.header.center_zoom;
        b.center_lon_e7 = a.header.center_lon_e7;
        b.center_lat_e7 = a.header.center_lat_e7;
    }

    let mut ids: Vec<u64> = collected.keys().copied().collect();
    ids.sort_unstable();
    for id in ids {
        let bodies = &collected[&id];
        let merged = if bodies.len() == 1 {
            // Single source: re-encode is unnecessary, so skip it entirely and keep
            // the original bytes.
            bodies[0].clone()
        } else {
            merge_tiles(bodies)?
        };
        b.add_tile_raw(id, crate::gz::compress(&merged));
    }
    b.build()
}

/// Union the layers of several MVT bodies for the same tile. Later bodies win a
/// layer-name collision.
pub fn merge_tiles(bodies: &[Vec<u8>]) -> Result<Vec<u8>> {
    let mut out = Tile::new();
    for body in bodies {
        let tile = Tile::decode(body)?;
        for layer in tile.layers {
            match out.layers.iter_mut().find(|l| l.name == layer.name) {
                Some(existing) => *existing = layer,
                None => out.layers.push(layer),
            }
        }
    }
    Ok(out.encode())
}

#[cfg(test)]
mod tests {
    use super::*;

    const REAL_TILE: &[u8] = include_bytes!("../tests/fixtures/v5ca_z11_tile.mvt");

    fn pt(lon: f64, lat: f64, name: &str) -> Point {
        Point {
            lon,
            lat,
            props: vec![("name".to_string(), Value::String(name.to_string()))],
        }
    }

    #[test]
    fn projection_anchors_are_right() {
        // z0: the whole world is one tile, and 0,0 sits at its centre.
        let (x, y) = project(0.0, 0.0, 0);
        assert!((x - 0.5).abs() < 1e-9, "lon 0 -> x 0.5, got {x}");
        assert!((y - 0.5).abs() < 1e-9, "lat 0 -> y 0.5, got {y}");
        // The antimeridian and the Mercator top corner.
        let (x, _) = project(-180.0, 0.0, 0);
        assert!(x.abs() < 1e-9);
        let (_, y) = project(0.0, 85.051_128_78, 0);
        assert!(y.abs() < 1e-6, "Mercator top -> y 0, got {y}");
        // A pole must clamp rather than diverge.
        let (_, y) = project(0.0, 90.0, 4);
        assert!(y.is_finite(), "lat 90 must clamp, got {y}");
    }

    #[test]
    fn a_point_lands_in_the_expected_tile() {
        // San Francisco at z11 is a well-known tile: x=327, y=791.
        let (fx, fy) = project(-122.4194, 37.7749, 11);
        assert_eq!((fx.floor() as u64, fy.floor() as u64), (327, 791));
    }

    #[test]
    fn tiling_buckets_points_and_round_trips_them() {
        let pts = vec![
            pt(-122.4194, 37.7749, "SF"),
            pt(-122.4180, 37.7760, "SF2"),
            // Far away, so it must land in a different tile.
            pt(-74.0060, 40.7128, "NYC"),
        ];
        let tiles = tile_points("transit_stops", &pts, 11, DEFAULT_EXTENT);
        assert_eq!(tiles.len(), 2, "two distinct tiles");

        let sf_id = pmtiles::tile_id(11, 327, 791);
        let (_, body) = tiles.iter().find(|(id, _)| *id == sf_id).expect("the SF tile");
        let tile = Tile::decode(body).unwrap();
        let layer = tile.layer("transit_stops").unwrap();
        assert_eq!(layer.features.len(), 2, "both SF points in one tile");
        for f in &layer.features {
            assert_eq!(f.geom_type, GeomType::Point);
            let decoded = mvt::decode_points(&f.geometry).unwrap();
            assert_eq!(decoded.len(), 1);
            let (x, y) = decoded[0];
            assert!(
                (0..=DEFAULT_EXTENT as i32).contains(&x)
                    && (0..=DEFAULT_EXTENT as i32).contains(&y),
                "({x},{y}) inside the extent grid"
            );
        }
    }

    #[test]
    fn tiling_is_deterministic() {
        let pts = vec![pt(-122.42, 37.77, "a"), pt(-122.41, 37.78, "b")];
        assert_eq!(
            tile_points("l", &pts, 12, DEFAULT_EXTENT),
            tile_points("l", &pts, 12, DEFAULT_EXTENT),
        );
    }

    #[test]
    fn a_point_archive_round_trips_at_every_zoom() {
        let pts = vec![pt(-122.4194, 37.7749, "Embarcadero"), pt(-118.2437, 34.0522, "Union")];
        let bytes = build_point_archive("transit_stops", &pts, 11, 13).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert_eq!((a.header.min_zoom, a.header.max_zoom), (11, 13));
        assert!(
            String::from_utf8_lossy(&a.metadata).contains("transit_stops"),
            "metadata names the layer"
        );
        for z in 11..=13u8 {
            let (fx, fy) = project(-122.4194, 37.7749, z);
            let body = a
                .tile(z, fx.floor() as u64, fy.floor() as u64)
                .unwrap()
                .unwrap_or_else(|| panic!("a tile at z{z}"));
            let tile = Tile::decode(&body).unwrap();
            let l = tile.layer("transit_stops").unwrap();
            assert_eq!(l.features.len(), 1, "z{z}");
            assert_eq!(
                l.features[0].get("name"),
                Some(&Value::String("Embarcadero".into()))
            );
        }
    }

    #[test]
    fn merging_unions_layers_and_preserves_the_base_geometry() {
        // A real tippecanoe tile (polygons + lines) merged with our own point
        // layer: the union must keep all four layers, and the base geometry must
        // come through byte-identically.
        let base = REAL_TILE.to_vec();
        let mut ours = Layer::new("transit_stops");
        ours.features.push(Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(100, 200)]),
            props: vec![("motis_id".to_string(), Value::String("us-ca-X_1".into()))],
        });
        let overlay = Tile { layers: vec![ours] }.encode();

        let merged = merge_tiles(&[base, overlay]).unwrap();
        let tile = Tile::decode(&merged).unwrap();
        let mut names = tile.layer_names();
        names.sort_unstable();
        assert_eq!(names, vec!["earth", "roads", "transit_stops", "water"]);

        let before = Tile::decode(REAL_TILE).unwrap();
        for name in ["earth", "roads", "water"] {
            let b = before.layer(name).unwrap();
            let a = tile.layer(name).unwrap();
            assert_eq!(b.features.len(), a.features.len(), "{name} feature count");
            for (bf, af) in b.features.iter().zip(a.features.iter()) {
                assert_eq!(bf.geometry, af.geometry, "{name} geometry must be untouched");
                assert_eq!(bf.geom_type, af.geom_type);
            }
        }
        assert_eq!(
            tile.layer("transit_stops").unwrap().features[0].get("motis_id"),
            Some(&Value::String("us-ca-X_1".into()))
        );
    }

    #[test]
    fn a_later_input_replaces_a_colliding_layer() {
        let mut old = Layer::new("transit_stops");
        old.features.push(Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(1, 1)]),
            props: vec![("v".to_string(), Value::Uint(1))],
        });
        let mut new = Layer::new("transit_stops");
        new.features.push(Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(2, 2)]),
            props: vec![("v".to_string(), Value::Uint(2))],
        });
        let merged = merge_tiles(&[
            Tile { layers: vec![old] }.encode(),
            Tile { layers: vec![new] }.encode(),
        ])
        .unwrap();
        let tile = Tile::decode(&merged).unwrap();
        assert_eq!(tile.layers.len(), 1, "not duplicated");
        let l = tile.layer("transit_stops").unwrap();
        assert_eq!(l.features.len(), 1);
        assert_eq!(l.features[0].get("v"), Some(&Value::Uint(2)), "the later one wins");
    }

    #[test]
    fn merging_archives_unions_overlapping_and_disjoint_tiles() {
        // Base: two tiles. Overlay: one overlapping, one new.
        let mut base = Builder::new();
        base.min_zoom = 11;
        base.max_zoom = 11;
        let mut l = Layer::new("water");
        l.features.push(Feature {
            id: None,
            geom_type: GeomType::Polygon,
            geometry: vec![mvt::command(mvt::CMD_MOVE_TO, 1), 0, 0],
            props: vec![],
        });
        let water = Tile { layers: vec![l] }.encode();
        base.add_tile(11, 327, 791, &water);
        base.add_tile(11, 100, 100, &water);
        let base_bytes = base.build().unwrap();

        let mut ov = Builder::new();
        ov.min_zoom = 11;
        ov.max_zoom = 11;
        let mut sl = Layer::new("transit_stops");
        sl.features.push(Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(5, 5)]),
            props: vec![],
        });
        let stops = Tile { layers: vec![sl] }.encode();
        ov.add_tile(11, 327, 791, &stops);
        ov.add_tile(11, 500, 500, &stops);
        let ov_bytes = ov.build().unwrap();

        let ba = Archive::parse(&base_bytes).unwrap();
        let oa = Archive::parse(&ov_bytes).unwrap();
        let merged = merge_archives(&[&ba, &oa]).unwrap();
        let m = Archive::parse(&merged).unwrap();

        // Overlapping tile carries both layers.
        let t = Tile::decode(&m.tile(11, 327, 791).unwrap().unwrap()).unwrap();
        let mut names = t.layer_names();
        names.sort_unstable();
        assert_eq!(names, vec!["transit_stops", "water"]);
        // Base-only tile survives.
        let t = Tile::decode(&m.tile(11, 100, 100).unwrap().unwrap()).unwrap();
        assert_eq!(t.layer_names(), vec!["water"]);
        // Overlay-only tile survives.
        let t = Tile::decode(&m.tile(11, 500, 500).unwrap().unwrap()).unwrap();
        assert_eq!(t.layer_names(), vec!["transit_stops"]);
    }

    #[test]
    fn merging_a_single_archive_is_a_faithful_copy() {
        let pts = vec![pt(-122.4194, 37.7749, "SF")];
        let bytes = build_point_archive("transit_stops", &pts, 11, 11).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        let merged = merge_archives(&[&a]).unwrap();
        let m = Archive::parse(&merged).unwrap();
        assert_eq!(m.header.addressed_tiles, a.header.addressed_tiles);
        let (fx, fy) = project(-122.4194, 37.7749, 11);
        assert_eq!(
            m.tile(11, fx.floor() as u64, fy.floor() as u64).unwrap(),
            a.tile(11, fx.floor() as u64, fy.floor() as u64).unwrap(),
        );
    }
}
