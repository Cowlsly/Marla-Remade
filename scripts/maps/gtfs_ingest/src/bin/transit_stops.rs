//! `transit_stops` — host build tool emitting the basemap's `transit_stops` layer
//! as newline-delimited GeoJSON (geojsonseq), one `Point` per served GTFS stop.
//!
//! Usage:
//!   transit_stops --geojson OUT.geojsonseq <feed>...
//!   transit_stops --geojson OUT.geojsonseq --manifest FILE
//!
//! `<feed>` is `feed_name=gtfs_dir[=motis_prefix]` or a bare `gtfs_dir`, exactly
//! as `gtfs_ingest` takes them, so the same `feeds.manifest`
//! `build_ca_transit.ps1` writes drives both tools.
//!
//! This replaces the per-viewport `GET /api/v1/map/stops` fetch: stops are static
//! data, so they belong in the basemap rather than behind a network round-trip on
//! every camera idle. Each feature carries
//!
//!   * `motis_id`   — `<motis_prefix>_<gtfs stop_id>`, the id the realtime
//!                    `/stoptimes` overlay needs. Omitted when the feed's prefix
//!                    is unknown, in which case a tap can still open the offline
//!                    board but gets no live delays.
//!   * `name`       — `stop_name`.
//!   * `route_type` — the GTFS route type of the most prominent mode serving the
//!                    stop, so the layer can be styled per mode.
//!
//! Mirrors `build_pois_layer.sh`'s shape: a Rust binary writes the geojsonseq
//! directly, with no osmium or python stage.

use gtfs_ingest::gtfs::{self, Csv};
use gtfs_ingest::manifest::{parse_feed_spec, read_manifest, FeedSpec};
use std::collections::HashMap;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut geojson: Option<PathBuf> = None;
    let mut manifest: Option<PathBuf> = None;
    let mut inline: Vec<String> = Vec::new();

    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--geojson" => {
                geojson = args.get(i + 1).map(PathBuf::from);
                i += 2;
            }
            "--manifest" => {
                manifest = args.get(i + 1).map(PathBuf::from);
                i += 2;
            }
            "-h" | "--help" => {
                usage();
                return ExitCode::SUCCESS;
            }
            other => {
                inline.push(other.to_string());
                i += 1;
            }
        }
    }

    let Some(geojson) = geojson else {
        eprintln!("transit_stops: --geojson is required");
        usage();
        return ExitCode::from(2);
    };

    let specs: Vec<FeedSpec> = match &manifest {
        Some(m) => match read_manifest(m) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("transit_stops: {e}");
                return ExitCode::FAILURE;
            }
        },
        None => inline.iter().map(|s| parse_feed_spec(s)).collect(),
    };

    if specs.is_empty() {
        eprintln!("transit_stops: no feeds given");
        usage();
        return ExitCode::from(2);
    }

    match run(&geojson, &specs) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("transit_stops: {e}");
            ExitCode::FAILURE
        }
    }
}

fn usage() {
    eprintln!("usage: transit_stops --geojson OUT.geojsonseq <feed>...");
    eprintln!("       transit_stops --geojson OUT.geojsonseq --manifest FILE");
    eprintln!("  <feed> = feed_name=gtfs_dir[=motis_prefix]  |  gtfs_dir");
}

/// One emitted stop.
struct Stop {
    lat_e7: i32,
    lon_e7: i32,
    name: String,
    /// Empty when the feed carried no MOTIS prefix.
    motis_id: String,
    route_type: u32,
}

fn run(geojson: &Path, specs: &[FeedSpec]) -> Result<(), String> {
    // Dedup key: Transitous ships merged regional feeds (`SF-bayarea`) *and* the
    // member agencies (`SFMTA`), so one physical platform appears several times.
    // Keying on a coarsened position plus the name collapses those without
    // merging genuinely distinct stops: 1e-4 deg is ~11 m, well under the gap
    // between the two sides of a street.
    let mut seen: HashMap<(i32, i32, String), usize> = HashMap::new();
    let mut stops: Vec<Stop> = Vec::new();
    let mut total_rows = 0usize;

    for (name, dir, motis_prefix) in specs {
        let require = |file: &str| -> Result<Csv, String> {
            gtfs::read_table(dir, file).ok_or_else(|| {
                format!("feed '{name}' ({}) missing required GTFS file: {file}", dir.display())
            })
        };
        let stops_csv = require("stops.txt")?;
        let routes_csv = require("routes.txt")?;
        let trips_csv = require("trips.txt")?;
        let stop_times_csv = require("stop_times.txt")?;
        if motis_prefix.is_empty() {
            eprintln!(
                "transit_stops: warning: feed '{name}' has no MOTIS prefix; its stops \
                 will carry no motis_id and get no realtime delays"
            );
        }

        let stop_route_type = derive_stop_modes(&routes_csv, &trips_csv, &stop_times_csv);

        let mut emitted = 0usize;
        for row in &stops_csv.rows {
            total_rows += 1;
            let id = stops_csv.get(row, "stop_id");
            if id.is_empty() {
                continue;
            }
            // A stop no trip ever calls at is not boardable, and includes the
            // unserved parent stations GTFS feeds carry alongside their platforms.
            let Some(&route_type) = stop_route_type.get(id) else { continue };
            let lat: f64 = match stops_csv.get(row, "stop_lat").trim().parse() {
                Ok(v) => v,
                Err(_) => continue,
            };
            let lon: f64 = match stops_csv.get(row, "stop_lon").trim().parse() {
                Ok(v) => v,
                Err(_) => continue,
            };
            if !lat.is_finite() || !lon.is_finite() {
                continue;
            }
            let stop_name = stops_csv.get(row, "stop_name").trim();
            let motis_id = if motis_prefix.is_empty() {
                String::new()
            } else {
                format!("{motis_prefix}_{id}")
            };

            let key = (
                (lat * 1e4).round() as i32,
                (lon * 1e4).round() as i32,
                stop_name.to_lowercase(),
            );
            let candidate = Stop {
                lat_e7: (lat * 1e7).round() as i32,
                lon_e7: (lon * 1e7).round() as i32,
                name: stop_name.to_string(),
                motis_id,
                route_type,
            };
            match seen.get(&key) {
                // Prefer the duplicate we can name in MOTIS's id space, so the
                // surviving pin is the one that can fetch live departures.
                Some(&existing) => {
                    let keep = stops[existing].motis_id.is_empty()
                        && !candidate.motis_id.is_empty();
                    if keep {
                        stops[existing] = candidate;
                    }
                }
                None => {
                    seen.insert(key, stops.len());
                    stops.push(candidate);
                    emitted += 1;
                }
            }
        }
        eprintln!("transit_stops: feed '{name}': {emitted} new stop(s)");
    }

    // Deterministic output, so a rebuild produces a byte-identical layer and the
    // tile diff is empty when nothing changed.
    stops.sort_by(|a, b| {
        a.lat_e7
            .cmp(&b.lat_e7)
            .then(a.lon_e7.cmp(&b.lon_e7))
            .then_with(|| a.name.cmp(&b.name))
    });

    let file = std::fs::File::create(geojson)
        .map_err(|e| format!("cannot write {}: {e}", geojson.display()))?;
    let mut out = BufWriter::new(file);
    let mut line: Vec<u8> = Vec::new();
    let mut with_id = 0usize;
    for s in &stops {
        line.clear();
        write!(
            line,
            "{{\"type\":\"Feature\",\"geometry\":{{\"type\":\"Point\",\"coordinates\":[{:.7},{:.7}]}},\"properties\":{{\"name\":\"",
            s.lon_e7 as f64 * 1e-7,
            s.lat_e7 as f64 * 1e-7
        )
        .map_err(io_err)?;
        json_escape(s.name.as_bytes(), &mut line);
        line.extend_from_slice(b"\"");
        if !s.motis_id.is_empty() {
            with_id += 1;
            line.extend_from_slice(b",\"motis_id\":\"");
            json_escape(s.motis_id.as_bytes(), &mut line);
            line.extend_from_slice(b"\"");
        }
        writeln!(line, ",\"route_type\":{}}}}}", s.route_type).map_err(io_err)?;
        out.write_all(&line).map_err(io_err)?;
    }
    out.flush().map_err(io_err)?;

    eprintln!(
        "transit_stops: wrote {} ({} stop(s) from {} rows, {with_id} with a motis_id)",
        geojson.display(),
        stops.len(),
        total_rows,
    );
    Ok(())
}

/// `stop_id` -> the GTFS route type of the most prominent mode calling there.
///
/// GTFS has no stop->route table, so it is recovered through
/// `stop_times` -> `trips` -> `routes`.
fn derive_stop_modes(routes: &Csv, trips: &Csv, stop_times: &Csv) -> HashMap<String, u32> {
    let mut route_type: HashMap<&str, u32> = HashMap::new();
    for row in &routes.rows {
        let id = routes.get(row, "route_id");
        if id.is_empty() {
            continue;
        }
        let rt: u32 = routes.get(row, "route_type").trim().parse().unwrap_or(3);
        route_type.insert(id, normalize_route_type(rt));
    }

    let mut trip_type: HashMap<&str, u32> = HashMap::new();
    for row in &trips.rows {
        let id = trips.get(row, "trip_id");
        if id.is_empty() {
            continue;
        }
        if let Some(&rt) = route_type.get(trips.get(row, "route_id")) {
            trip_type.insert(id, rt);
        }
    }

    let mut out: HashMap<String, u32> = HashMap::new();
    for row in &stop_times.rows {
        let stop_id = stop_times.get(row, "stop_id");
        if stop_id.is_empty() {
            continue;
        }
        let Some(&rt) = trip_type.get(stop_times.get(row, "trip_id")) else { continue };
        match out.get(stop_id) {
            Some(&existing) if mode_rank(existing) <= mode_rank(rt) => {}
            _ => {
                out.insert(stop_id.to_string(), rt);
            }
        }
    }
    out
}

/// Fold GTFS's extended route types (the 100-1799 ranges) onto the basic set, so
/// the layer only ever has to style a handful of values.
fn normalize_route_type(rt: u32) -> u32 {
    match rt {
        0..=7 | 11 | 12 => rt,
        100..=199 => 2,  // railway service
        200..=299 => 3,  // coach service
        300..=399 => 2,  // suburban / regional rail
        400..=499 => 1,  // urban railway / metro
        500..=599 => 1,  // metro
        600..=699 => 1,  // underground
        700..=799 => 3,  // bus service
        800..=899 => 11, // trolleybus service
        900..=999 => 0,  // tram service
        1000..=1099 => 4, // water transport
        1200..=1299 => 4, // ferry service
        1300..=1399 => 6, // aerial lift
        1400..=1499 => 7, // funicular
        _ => 3,
    }
}

/// Styling priority when several modes call at one stop: rail-like modes outrank
/// buses, so a subway entrance that also has a bus stop still reads as a subway.
/// Lower is more prominent.
fn mode_rank(rt: u32) -> u8 {
    match rt {
        1 => 0,      // subway / metro
        2 => 1,      // rail
        0 => 2,      // tram / light rail
        12 => 3,     // monorail
        4 => 4,      // ferry
        6 => 5,      // aerial lift
        7 => 6,      // funicular
        5 => 7,      // cable tram
        3 | 11 => 8, // bus / trolleybus
        _ => 9,
    }
}

fn io_err(e: impl std::fmt::Display) -> String {
    format!("write failed: {e}")
}

/// Escape a name for a JSON string. UTF-8 bytes pass through verbatim; only the
/// JSON-mandatory escapes and C0 controls are rewritten. Mirrors
/// `osm_ingest::poi_build`'s escaper so both layers quote identically.
fn json_escape(s: &[u8], out: &mut Vec<u8>) {
    for &b in s {
        match b {
            b'"' => out.extend_from_slice(b"\\\""),
            b'\\' => out.extend_from_slice(b"\\\\"),
            b'\n' => out.extend_from_slice(b"\\n"),
            b'\r' => out.extend_from_slice(b"\\r"),
            b'\t' => out.extend_from_slice(b"\\t"),
            0x00..=0x1f => {
                out.extend_from_slice(format!("\\u{:04x}", b).as_bytes());
            }
            _ => out.push(b),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use gtfs_ingest::gtfs::parse_csv;

    #[test]
    fn extended_route_types_fold_onto_the_basic_set() {
        assert_eq!(normalize_route_type(3), 3, "plain bus is unchanged");
        assert_eq!(normalize_route_type(109), 2, "suburban railway -> rail");
        assert_eq!(normalize_route_type(401), 1, "metro -> subway");
        assert_eq!(normalize_route_type(717), 3, "share taxi -> bus");
        assert_eq!(normalize_route_type(900), 0, "tram service -> tram");
        assert_eq!(normalize_route_type(1501), 3, "unknown -> bus");
    }

    #[test]
    fn rail_outranks_bus_at_a_shared_stop() {
        let routes = parse_csv(
            "route_id,route_type\n\
             BUS,3\n\
             SUB,1\n",
        );
        let trips = parse_csv(
            "route_id,trip_id\n\
             BUS,TB\n\
             SUB,TS\n",
        );
        // The bus trip is listed first, so a naive first-wins would pick bus.
        let stop_times = parse_csv(
            "trip_id,stop_id,stop_sequence\n\
             TB,SHARED,1\n\
             TS,SHARED,1\n\
             TB,BUSONLY,2\n",
        );
        let modes = derive_stop_modes(&routes, &trips, &stop_times);
        assert_eq!(modes.get("SHARED"), Some(&1), "subway wins over bus");
        assert_eq!(modes.get("BUSONLY"), Some(&3));
    }

    #[test]
    fn an_unserved_stop_gets_no_mode() {
        let routes = parse_csv("route_id,route_type\nR,3\n");
        let trips = parse_csv("route_id,trip_id\nR,T\n");
        let stop_times = parse_csv("trip_id,stop_id,stop_sequence\nT,A,1\n");
        let modes = derive_stop_modes(&routes, &trips, &stop_times);
        // A parent station or orphan row no trip calls at is absent, which is what
        // keeps it out of the layer.
        assert_eq!(modes.get("STATION"), None);
        assert_eq!(modes.get("A"), Some(&3));
    }

    #[test]
    fn json_escaping_covers_quotes_and_controls() {
        let mut out = Vec::new();
        json_escape("A\"B\\C\tD".as_bytes(), &mut out);
        assert_eq!(String::from_utf8(out).unwrap(), "A\\\"B\\\\C\\tD");
        // Non-ASCII passes through as UTF-8 rather than being \u-escaped.
        let mut utf8 = Vec::new();
        json_escape("Béziers".as_bytes(), &mut utf8);
        assert_eq!(String::from_utf8(utf8).unwrap(), "Béziers");
    }
}
