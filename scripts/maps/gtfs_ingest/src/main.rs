//! `gtfs_ingest` — host build tool (P11a / world pack) that turns one or more
//! GTFS feeds into a single compact on-device transit index (`<pack>.transit`)
//! plus a small JSON manifest describing it, for the Maps offline packs.
//!
//! Usage:
//!   gtfs_ingest <out_dir> <pack_name> <feed>...          # feeds inline
//!   gtfs_ingest <out_dir> <pack_name> --manifest <file>  # feeds from a file
//!
//! Each `<feed>` is either `feed_name=gtfs_dir` or just `gtfs_dir` (the feed name
//! is then the directory's base name). An optional third field,
//! `feed_name=gtfs_dir=motis_prefix`, gives the feed's Transitous id namespace
//! (e.g. `us-ca-SF-bayarea`) so the pack can carry MOTIS stop ids; without it the
//! device simply reports none. A manifest file lists one such spec per line
//! (blank lines and `#` comments allowed). `gtfs_dir` is an UNZIPPED
//! GTFS feed directory (containing `stops.txt`, `routes.txt`, `trips.txt`,
//! `stop_times.txt`, and optionally `agency.txt` / `calendar.txt` /
//! `calendar_dates.txt` / `shapes.txt`).
//! Unzipping is a one-line pre-step (`unzip feed.zip -d <gtfs_dir>`); keeping
//! this tool zip-free means it builds with zero external crates and resolves
//! fully offline — the LANGUAGE RULE's Rust-first, no-Python requirement without
//! dragging in a decompression dep.
//!
//! Feeds merge into ONE pack: their GTFS ids are namespaced per feed so they
//! never collide, and cross-agency footpath transfers fall out of the merged
//! stop set for free. This is what makes a single global `world.transit`
//! feasible (see `src/index.rs` for the TRX2 format that keeps it small).
//!
//! GTFS feeds are sourced from the Transitous feed registry
//! (github.com/public-transport/transitous, `feeds/*.json`), which points at
//! each agency's official GTFS `.zip` — the same open data behind the P10
//! online boards. See `scripts/maps/build_world_transit.sh`.

use gtfs_ingest::gtfs;
use gtfs_ingest::index;
use gtfs_ingest::index::FeedInput;
use gtfs_ingest::manifest::{read_manifest, parse_feed_spec, FeedSpec};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 4 {
        eprintln!("usage: gtfs_ingest <out_dir> <pack_name> <feed>...");
        eprintln!("       gtfs_ingest <out_dir> <pack_name> --manifest <file>");
        eprintln!("  <feed> = feed_name=gtfs_dir[=motis_prefix]  |  gtfs_dir");
        return ExitCode::from(2);
    }
    let out_dir = PathBuf::from(&args[1]);
    let pack_name = args[2].clone();

    // Collect (feed_name, gtfs_dir, motis_prefix) triples, from a manifest or
    // inline args.
    let specs: Vec<FeedSpec> = if args[3] == "--manifest" {
        let file = match args.get(4) {
            Some(f) => f,
            None => {
                eprintln!("gtfs_ingest: --manifest requires a file path");
                return ExitCode::from(2);
            }
        };
        match read_manifest(Path::new(file)) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("gtfs_ingest: {e}");
                return ExitCode::FAILURE;
            }
        }
    } else {
        args[3..].iter().map(|s| parse_feed_spec(s)).collect()
    };

    if specs.is_empty() {
        eprintln!("gtfs_ingest: no feeds given");
        return ExitCode::from(2);
    }

    match run(&out_dir, &pack_name, &specs) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("gtfs_ingest: {e}");
            ExitCode::FAILURE
        }
    }
}

fn run(out_dir: &Path, pack_name: &str, specs: &[FeedSpec]) -> Result<(), String> {
    // Parse every feed up front so their CSVs outlive the FeedInput borrows.
    struct FeedTables {
        name: String,
        motis_prefix: String,
        stops: gtfs::Csv,
        routes: gtfs::Csv,
        trips: gtfs::Csv,
        stop_times: gtfs::Csv,
        calendar: Option<gtfs::Csv>,
        calendar_dates: Option<gtfs::Csv>,
        agency: Option<gtfs::Csv>,
        shapes: Option<std::collections::HashMap<String, gtfs::Shape>>,
    }

    let mut tables: Vec<FeedTables> = Vec::with_capacity(specs.len());
    for (name, dir, motis_prefix) in specs {
        let require = |file: &str| -> Result<gtfs::Csv, String> {
            gtfs::read_table(dir, file).ok_or_else(|| {
                format!("feed '{name}' ({}) missing required GTFS file: {file}", dir.display())
            })
        };
        let stops = require("stops.txt")?;
        let routes = require("routes.txt")?;
        let trips = require("trips.txt")?;
        let stop_times = require("stop_times.txt")?;
        let calendar = gtfs::read_table(dir, "calendar.txt");
        let calendar_dates = gtfs::read_table(dir, "calendar_dates.txt");
        let agency = gtfs::read_table(dir, "agency.txt");
        let shapes = gtfs::read_shapes(dir);
        if shapes.is_none() {
            eprintln!(
                "gtfs_ingest: warning: feed '{name}' has no shapes.txt; its ride legs \
                 will draw stop-to-stop"
            );
        }
        if calendar.is_none() && calendar_dates.is_none() {
            eprintln!(
                "gtfs_ingest: warning: feed '{name}' has no calendar.txt or \
                 calendar_dates.txt; its services will never be scheduled"
            );
        }
        // Without a timezone the device falls back to its own, which is wrong
        // for any feed outside the user's zone.
        if agency.as_ref().is_none_or(|a| {
            a.rows.first().is_none_or(|row| a.get(row, "agency_timezone").trim().is_empty())
        }) {
            eprintln!(
                "gtfs_ingest: warning: feed '{name}' has no agency_timezone; \
                 the device will route it in its own local time"
            );
        }
        eprintln!("gtfs_ingest: parsed feed '{name}' ({})", dir.display());
        tables.push(FeedTables {
            name: name.clone(),
            motis_prefix: motis_prefix.clone(),
            stops,
            routes,
            trips,
            stop_times,
            calendar,
            calendar_dates,
            agency,
            shapes,
        });
    }

    let feeds: Vec<FeedInput> = tables
        .iter()
        .map(|t| FeedInput {
            name: t.name.clone(),
            motis_prefix: t.motis_prefix.clone(),
            stops: &t.stops,
            routes: &t.routes,
            trips: &t.trips,
            stop_times: &t.stop_times,
            calendar: t.calendar.as_ref(),
            calendar_dates: t.calendar_dates.as_ref(),
            agency: t.agency.as_ref(),
            shapes: t.shapes.as_ref(),
        })
        .collect();

    std::fs::create_dir_all(out_dir)
        .map_err(|e| format!("cannot create out dir {}: {e}", out_dir.display()))?;
    let index_path = out_dir.join(format!("{pack_name}.transit"));
    // Straight to disk through a BufWriter: the pack is 1.5-4 GB for a world
    // build, and holding a second copy of it in a `Vec` to then `fs::write` was
    // pure overhead.
    let file = std::fs::File::create(&index_path)
        .map_err(|e| format!("cannot create {}: {e}", index_path.display()))?;
    let mut writer = std::io::BufWriter::new(file);
    let stats = index::build_index_to(pack_name, &feeds, &mut writer)?;
    writer
        .flush()
        .map_err(|e| format!("cannot finish writing {}: {e}", index_path.display()))?;
    drop(writer);

    // Manifest (hand-written JSON, no serde dep). Includes a per-section size
    // breakdown so the compression win (profiles vs the old stoptimes) is
    // visible without extra tooling.
    let sections_json = stats
        .section_sizes
        .iter()
        .map(|(n, sz)| format!("    {}: {}", json_str(n), sz))
        .collect::<Vec<_>>()
        .join(",\n");
    let manifest = format!(
        "{{\n  \"pack\": {pack},\n  \"format_version\": {ver},\n  \"file\": {file},\n  \
         \"size_bytes\": {size},\n  \"feeds\": {feeds},\n  \"stops\": {stops},\n  \
         \"routes\": {routes},\n  \"trips\": {trips},\n  \"profiles\": {profiles},\n  \
         \"transfers\": {transfers},\n  \"shaped_routes\": {shaped},\n  \
         \"dropped_shape_routes\": {dropped},\n  \
         \"bbox_e7\": [{min_lat}, {min_lon}, {max_lat}, {max_lon}],\n  \
         \"section_bytes\": {{\n{sections}\n  }}\n}}\n",
        pack = json_str(pack_name),
        ver = index::VERSION,
        file = json_str(&format!("{pack_name}.transit")),
        size = stats.size_bytes,
        feeds = stats.feeds,
        stops = stats.stops,
        routes = stats.routes,
        trips = stats.trips,
        profiles = stats.profiles,
        transfers = stats.transfers,
        shaped = stats.shaped_routes,
        dropped = stats.dropped_shape_routes,
        min_lat = stats.min_lat_e7,
        min_lon = stats.min_lon_e7,
        max_lat = stats.max_lat_e7,
        max_lon = stats.max_lon_e7,
        sections = sections_json,
    );
    let manifest_path = out_dir.join(format!("{pack_name}.transit.json"));
    std::fs::write(&manifest_path, manifest)
        .map_err(|e| format!("cannot write {}: {e}", manifest_path.display()))?;

    eprintln!(
        "gtfs_ingest: wrote {} ({:.1} MiB): {} feeds, {} stops, {} routes, {} trips, \
         {} profiles, {} transfers",
        index_path.display(),
        stats.size_bytes as f64 / (1024.0 * 1024.0),
        stats.feeds,
        stats.stops,
        stats.routes,
        stats.trips,
        stats.profiles,
        stats.transfers,
    );
    eprintln!(
        "gtfs_ingest: ride geometry: {} of {} routes shaped, {} dropped by validation, \
         {} with more than one shape_id",
        stats.shaped_routes, stats.routes, stats.dropped_shape_routes, stats.multi_shape_routes,
    );
    Ok(())
}

/// Minimal JSON string escaper for the manifest.
fn json_str(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}
