//! `gtfs_ingest` — host build tool (P11a) that turns a region's GTFS feed into a
//! compact on-device transit index (`<feed>.transit`) plus a small JSON
//! manifest describing it, for bundling into the Maps offline region packs.
//!
//! Usage:
//!   gtfs_ingest <gtfs_dir> <feed_name> <out_dir>
//!
//! `gtfs_dir` is an UNZIPPED GTFS feed directory (containing `stops.txt`,
//! `routes.txt`, `trips.txt`, `stop_times.txt`, and optionally `calendar.txt` /
//! `calendar_dates.txt`). Unzipping is a one-line pre-step
//! (`unzip feed.zip -d <gtfs_dir>`); keeping this tool zip-free means it builds
//! with zero external crates and resolves fully offline — the LANGUAGE RULE's
//! Rust-first, no-Python requirement without dragging in a decompression dep.
//!
//! GTFS feeds are sourced per-region from the Transitous feed registry
//! (github.com/public-transport/transitous, `feeds/*.json`), which points at
//! each agency's official GTFS `.zip` — the same open data behind the P10
//! online boards.

mod gtfs;
mod index;

use std::path::{Path, PathBuf};
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 4 {
        eprintln!("usage: gtfs_ingest <gtfs_dir> <feed_name> <out_dir>");
        return ExitCode::from(2);
    }
    let gtfs_dir = PathBuf::from(&args[1]);
    let feed_name = &args[2];
    let out_dir = PathBuf::from(&args[3]);

    match run(&gtfs_dir, feed_name, &out_dir) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("gtfs_ingest: {e}");
            ExitCode::FAILURE
        }
    }
}

fn run(gtfs_dir: &Path, feed_name: &str, out_dir: &Path) -> Result<(), String> {
    let require = |name: &str| -> Result<gtfs::Csv, String> {
        gtfs::read_table(gtfs_dir, name)
            .ok_or_else(|| format!("missing required GTFS file: {name}"))
    };

    let stops = require("stops.txt")?;
    let routes = require("routes.txt")?;
    let trips = require("trips.txt")?;
    let stop_times = require("stop_times.txt")?;
    let calendar = gtfs::read_table(gtfs_dir, "calendar.txt");
    let calendar_dates = gtfs::read_table(gtfs_dir, "calendar_dates.txt");
    if calendar.is_none() && calendar_dates.is_none() {
        eprintln!(
            "gtfs_ingest: warning: no calendar.txt or calendar_dates.txt; \
             all services will be treated as never-scheduled by the planner"
        );
    }

    let (blob, stats) = index::build_index(
        feed_name,
        &stops,
        &routes,
        &trips,
        &stop_times,
        calendar.as_ref(),
        calendar_dates.as_ref(),
    )?;

    std::fs::create_dir_all(out_dir)
        .map_err(|e| format!("cannot create out dir {}: {e}", out_dir.display()))?;
    let index_path = out_dir.join(format!("{feed_name}.transit"));
    std::fs::write(&index_path, &blob)
        .map_err(|e| format!("cannot write {}: {e}", index_path.display()))?;

    // Per-feed manifest (hand-written JSON, no serde dep). Consumed by the
    // packaging step (P11c) to list transit parts alongside the pmtiles.
    let manifest = format!(
        "{{\n  \"feed\": {feed},\n  \"format_version\": {ver},\n  \"file\": {file},\n  \
         \"size_bytes\": {size},\n  \"stops\": {stops},\n  \"routes\": {routes},\n  \
         \"trips\": {trips},\n  \"transfers\": {transfers},\n  \
         \"bbox_e7\": [{min_lat}, {min_lon}, {max_lat}, {max_lon}]\n}}\n",
        feed = json_str(feed_name),
        ver = index::VERSION,
        file = json_str(&format!("{feed_name}.transit")),
        size = stats.size_bytes,
        stops = stats.stops,
        routes = stats.routes,
        trips = stats.trips,
        transfers = stats.transfers,
        min_lat = stats.min_lat_e7,
        min_lon = stats.min_lon_e7,
        max_lat = stats.max_lat_e7,
        max_lon = stats.max_lon_e7,
    );
    let manifest_path = out_dir.join(format!("{feed_name}.transit.json"));
    std::fs::write(&manifest_path, manifest)
        .map_err(|e| format!("cannot write {}: {e}", manifest_path.display()))?;

    eprintln!(
        "gtfs_ingest: wrote {} ({} KiB): {} stops, {} routes, {} trips, {} transfers",
        index_path.display(),
        stats.size_bytes / 1024,
        stats.stops,
        stats.routes,
        stats.trips,
        stats.transfers,
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
