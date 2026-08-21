//! `tile_points` — tile a geojsonseq point file into a single-layer PMTiles
//! archive. The `tippecanoe` replacement for point layers.
//!
//! Usage:
//!   tile_points --geojson IN.geojsonseq --out OUT.pmtiles --layer NAME
//!               [--minzoom N] [--maxzoom N]
//!
//! Input is one GeoJSON `Feature` per line with `Point` geometry, exactly what
//! `gtfs_ingest`'s `transit_stops` binary and `osm_ingest`'s `poi_extract` emit.
//! Only `Point` features are accepted; anything else is counted and skipped, since
//! this tool deliberately has no clipper.

use std::process::ExitCode;
use tile_build::mvt::Value;
use tile_build::tiling::{build_point_archive, Point};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut geojson = None;
    let mut out = None;
    let mut layer = None;
    let mut minzoom = 11u8;
    let mut maxzoom = 14u8;

    let mut i = 1;
    while i < args.len() {
        let take = |i: usize| args.get(i + 1).cloned();
        match args[i].as_str() {
            "--geojson" => {
                geojson = take(i);
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

    let (Some(geojson), Some(out), Some(layer)) = (geojson, out, layer) else {
        eprintln!("tile_points: --geojson, --out and --layer are all required");
        usage();
        return ExitCode::from(2);
    };
    if minzoom > maxzoom {
        eprintln!("tile_points: --minzoom {minzoom} is above --maxzoom {maxzoom}");
        return ExitCode::from(2);
    }

    let text = match std::fs::read_to_string(&geojson) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("tile_points: cannot read {geojson}: {e}");
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
        match parse_point_feature(line) {
            Some(p) => points.push(p),
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
        eprintln!("tile_points: no point features in {geojson}");
        return ExitCode::FAILURE;
    }

    let bytes = match build_point_archive(&layer, &points, minzoom, maxzoom) {
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
    eprintln!("                   [--minzoom N] [--maxzoom N]");
}

/// Minimal GeoJSON `Feature`/`Point` reader for the one-line-per-feature shape our
/// own emitters produce. Deliberately not a general GeoJSON parser: it needs no
/// serde dependency, which is what keeps this crate resolving offline.
fn parse_point_feature(line: &str) -> Option<Point> {
    let coords = extract_after(line, "\"coordinates\":[")?;
    let close = coords.find(']')?;
    let mut nums = coords[..close].split(',');
    let lon: f64 = nums.next()?.trim().parse().ok()?;
    let lat: f64 = nums.next()?.trim().parse().ok()?;
    if !line.contains("\"Point\"") {
        return None;
    }

    let mut props = Vec::new();
    if let Some(rest) = extract_after(line, "\"properties\":{") {
        let end = rest.rfind('}').unwrap_or(rest.len());
        props = parse_props(&rest[..end]);
    }
    Some(Point { lon, lat, props })
}

fn extract_after<'a>(hay: &'a str, needle: &str) -> Option<&'a str> {
    hay.find(needle).map(|i| &hay[i + needle.len()..])
}

/// Parse a flat JSON object body into properties. Strings and numbers only, which
/// is all our emitters write.
fn parse_props(body: &str) -> Vec<(String, Value)> {
    let mut out = Vec::new();
    let b = body.as_bytes();
    let mut i = 0usize;
    while i < b.len() {
        // Key.
        while i < b.len() && b[i] != b'"' {
            i += 1;
        }
        if i >= b.len() {
            break;
        }
        i += 1;
        let ks = i;
        while i < b.len() && b[i] != b'"' {
            if b[i] == b'\\' {
                i += 1;
            }
            i += 1;
        }
        if i >= b.len() {
            break;
        }
        let key = unescape(&body[ks..i]);
        i += 1;
        while i < b.len() && b[i] != b':' {
            i += 1;
        }
        i += 1;
        while i < b.len() && (b[i] as char).is_whitespace() {
            i += 1;
        }
        if i >= b.len() {
            break;
        }
        // Value: string or number.
        if b[i] == b'"' {
            i += 1;
            let vs = i;
            while i < b.len() && b[i] != b'"' {
                if b[i] == b'\\' {
                    i += 1;
                }
                i += 1;
            }
            if i > b.len() {
                break;
            }
            out.push((key, Value::String(unescape(&body[vs..i.min(body.len())]))));
            i += 1;
        } else {
            let vs = i;
            while i < b.len() && b[i] != b',' && b[i] != b'}' {
                i += 1;
            }
            let raw = body[vs..i].trim();
            if let Ok(u) = raw.parse::<u64>() {
                out.push((key, Value::Uint(u)));
            } else if let Ok(f) = raw.parse::<f64>() {
                out.push((key, Value::Double(f)));
            } else if raw == "true" || raw == "false" {
                out.push((key, Value::Bool(raw == "true")));
            }
        }
        while i < b.len() && b[i] != b',' {
            i += 1;
        }
        i += 1;
    }
    out
}

fn unescape(s: &str) -> String {
    if !s.contains('\\') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match it.next() {
            Some('n') => out.push('\n'),
            Some('r') => out.push('\r'),
            Some('t') => out.push('\t'),
            Some('u') => {
                let hex: String = it.by_ref().take(4).collect();
                if let Some(ch) =
                    u32::from_str_radix(&hex, 16).ok().and_then(char::from_u32)
                {
                    out.push(ch);
                }
            }
            Some(other) => out.push(other),
            None => break,
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_what_transit_stops_emits() {
        let line = r#"{"type":"Feature","geometry":{"type":"Point","coordinates":[-122.3968000,37.7929000]},"properties":{"name":"Embarcadero","motis_id":"us-ca-SF-bayarea_901201","route_type":1}}"#;
        let p = parse_point_feature(line).expect("a point feature");
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
        let p = parse_point_feature(line).unwrap();
        assert_eq!(p.props.len(), 2);
        assert!(p.props.iter().all(|(k, _)| k != "motis_id"));
    }

    #[test]
    fn handles_escapes_in_names() {
        let line = r#"{"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{"name":"A\"B\\C","route_type":3}}"#;
        let p = parse_point_feature(line).unwrap();
        assert_eq!(p.props[0].1, Value::String("A\"B\\C".into()));
    }

    #[test]
    fn rejects_non_point_geometry() {
        let line = r#"{"type":"Feature","geometry":{"type":"LineString","coordinates":[[0,0],[1,1]]},"properties":{}}"#;
        assert!(parse_point_feature(line).is_none(), "no clipper, so lines are refused");
    }

    #[test]
    fn rejects_junk() {
        assert!(parse_point_feature("").is_none());
        assert!(parse_point_feature("not json").is_none());
        assert!(parse_point_feature(r#"{"type":"Feature"}"#).is_none());
    }
}
