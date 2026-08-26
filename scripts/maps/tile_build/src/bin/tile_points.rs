//! `tile_points` — tile a geojsonseq point file into a single-layer PMTiles
//! archive. The `tippecanoe` replacement for point layers.
//!
//! Usage:
//!   tile_points --geojson IN.geojsonseq --out OUT.pmtiles --layer NAME
//!               [--minzoom N] [--maxzoom N] [--threads N]
//!
//! Input is one GeoJSON `Feature` per line with `Point` geometry, exactly what
//! `gtfs_ingest`'s `transit_stops` binary and `osm_ingest`'s `poi_extract` and
//! `osm_extract` emit. Only `Point` features are accepted; anything else is counted
//! and skipped.
//!
//! Unlike `tile_lines` and `tile_polygons` this has no per-tile byte budget: a
//! point layer's size is bounded by the number of pins the operator chose to bake,
//! and thinning it silently would change what the app can find rather than only how
//! it looks. It is also the path the published archives were built with, so it
//! stays as it is.

use std::process::ExitCode;
use tile_build::geojson;
use tile_build::geom::Geometry;
use tile_build::par;
use tile_build::tiling::{build_point_archive_with, Point};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut geojson_path = None;
    let mut out = None;
    let mut layer = None;
    let mut minzoom = 11u8;
    let mut maxzoom = 14u8;

    let mut i = 1;
    while i < args.len() {
        let take = |i: usize| args.get(i + 1).cloned();
        match args[i].as_str() {
            "--geojson" => {
                geojson_path = take(i);
                i += 2;
            }
            "--out" => {
                out = take(i);
                i += 2;
            }
            "--layer" => {
                layer = take(i);
                i += 2;
            }
            "--minzoom" => {
                minzoom = take(i).and_then(|v| v.parse().ok()).unwrap_or(minzoom);
                i += 2;
            }
            "--maxzoom" => {
                maxzoom = take(i).and_then(|v| v.parse().ok()).unwrap_or(maxzoom);
                i += 2;
            }
            "--threads" => {
                let Some(raw) = take(i) else {
                    eprintln!("tile_points: --threads needs a value");
                    return ExitCode::from(2);
                };
                match par::parse_threads(&raw) {
                    Ok(n) => par::set_threads(n),
                    Err(e) => {
                        eprintln!("tile_points: {e}");
                        return ExitCode::from(2);
                    }
                }
                i += 2;
            }
            "-h" | "--help" => {
                usage();
                return ExitCode::SUCCESS;
            }
            other => {
                eprintln!("tile_points: unexpected argument '{other}'");
                usage();
                return ExitCode::from(2);
            }
        }
    }

    let (Some(geojson_path), Some(out), Some(layer)) = (geojson_path, out, layer) else {
        eprintln!("tile_points: --geojson, --out and --layer are all required");
        usage();
        return ExitCode::from(2);
    };
    if minzoom > maxzoom {
        eprintln!("tile_points: --minzoom {minzoom} is above --maxzoom {maxzoom}");
        return ExitCode::from(2);
    }

    let text = match std::fs::read_to_string(&geojson_path) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("tile_points: cannot read {geojson_path}: {e}");
            return ExitCode::FAILURE;
        }
    };

    let mut points = Vec::new();
    let mut skipped = 0usize;
    for (n, line) in text.lines().enumerate() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        match parse_point(line) {
            Some(p) => points.extend(p),
            None => {
                skipped += 1;
                if skipped <= 5 {
                    eprintln!("tile_points: skipping line {} (not a Point feature)", n + 1);
                }
            }
        }
    }
    if skipped > 5 {
        eprintln!("tile_points: ... and {} more skipped line(s)", skipped - 5);
    }
    if points.is_empty() {
        eprintln!("tile_points: no point features in {geojson_path}");
        return ExitCode::FAILURE;
    }

    let bytes = match build_point_archive_with(&layer, &points, minzoom, maxzoom, true) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("tile_points: {e}");
            return ExitCode::FAILURE;
        }
    };
    if let Err(e) = std::fs::write(&out, &bytes) {
        eprintln!("tile_points: cannot write {out}: {e}");
        return ExitCode::FAILURE;
    }
    eprintln!(
        "tile_points: wrote {out} ({:.1} MiB): {} point(s), layer '{layer}', z{minzoom}-{maxzoom}",
        bytes.len() as f64 / (1024.0 * 1024.0),
        points.len(),
    );
    ExitCode::SUCCESS
}

fn usage() {
    eprintln!("usage: tile_points --geojson IN.geojsonseq --out OUT.pmtiles --layer NAME");
    eprintln!("                   [--minzoom N] [--maxzoom N] [--threads N]");
}

/// One line's points, or `None` when the line is not point geometry.
///
/// A `MultiPoint` becomes several points sharing one property set, which is what
/// a point layer means by it.
fn parse_point(line: &str) -> Option<Vec<Point>> {
    let f = geojson::parse_feature(line)?;
    let Geometry::Points(coords) = f.geometry else {
        return None;
    };
    if coords.is_empty() {
        return None;
    }
    Some(
        coords
            .into_iter()
            .map(|(lon, lat)| Point { lon, lat, props: f.props.clone() })
            .collect(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use tile_build::mvt::Value;

    fn one(line: &str) -> Option<Point> {
        parse_point(line).map(|mut v| v.remove(0))
    }

    #[test]
    fn parses_what_transit_stops_emits() {
        let line = r#"{"type":"Feature","geometry":{"type":"Point","coordinates":[-122.3968000,37.7929000]},"properties":{"name":"Embarcadero","motis_id":"us-ca-SF-bayarea_901201","route_type":1}}"#;
        let p = one(line).expect("a point feature");
        assert!((p.lon - -122.3968).abs() < 1e-9);
        assert!((p.lat - 37.7929).abs() < 1e-9);
        assert_eq!(
            p.props,
            vec![
                ("name".to_string(), Value::String("Embarcadero".into())),
                ("motis_id".to_string(), Value::String("us-ca-SF-bayarea_901201".into())),
                ("route_type".to_string(), Value::Uint(1)),
            ]
        );
    }

    #[test]
    fn parses_a_feature_with_no_motis_id() {
        let line = r#"{"type":"Feature","geometry":{"type":"Point","coordinates":[-1.5,2.5]},"properties":{"name":"Ferry Building","route_type":0}}"#;
        let p = one(line).unwrap();
        assert_eq!(p.props.len(), 2);
        assert!(p.props.iter().all(|(k, _)| k != "motis_id"));
    }

    #[test]
    fn handles_escapes_in_names() {
        let line = r#"{"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{"name":"A\"B\\C","route_type":3}}"#;
        let p = one(line).unwrap();
        assert_eq!(p.props[0].1, Value::String("A\"B\\C".into()));
    }

    #[test]
    fn parses_what_osm_extract_emits_for_the_safety_layer() {
        let line = r#"{"type":"Feature","geometry":{"type":"Point","coordinates":[-122.4194000,37.7749000]},"properties":{"kind":"speed_camera","direction":"forward","osm_id":"node/1001"}}"#;
        let p = one(line).unwrap();
        assert_eq!(p.props[0].1, Value::String("speed_camera".into()));
        assert_eq!(p.props[2].1, Value::String("node/1001".into()));
    }

    #[test]
    fn a_multipoint_becomes_several_points_sharing_one_property_set() {
        let line = r#"{"type":"Feature","geometry":{"type":"MultiPoint","coordinates":[[0,0],[1,1]]},"properties":{"name":"Pair"}}"#;
        let pts = parse_point(line).unwrap();
        assert_eq!(pts.len(), 2);
        assert_eq!(pts[0].props, pts[1].props);
    }

    #[test]
    fn rejects_non_point_geometry() {
        let line = r#"{"type":"Feature","geometry":{"type":"LineString","coordinates":[[0,0],[1,1]]},"properties":{}}"#;
        assert!(one(line).is_none(), "a point layer takes points");
        let line = r#"{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]},"properties":{}}"#;
        assert!(one(line).is_none());
    }

    #[test]
    fn rejects_junk() {
        assert!(one("").is_none());
        assert!(one("not json").is_none());
        assert!(one(r#"{"type":"Feature"}"#).is_none());
    }
}
