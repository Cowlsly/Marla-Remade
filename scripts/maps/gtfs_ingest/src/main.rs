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
use gtfs_ingest::index::FeedDir;
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
    std::fs::create_dir_all(out_dir)
        .map_err(|e| format!("cannot create out dir {}: {e}", out_dir.display()))?;
    let index_path = out_dir.join(format!("{pack_name}.transit"));
    // Straight to disk through a BufWriter: the pack is 1.5-4 GB for a world
    // build, and holding a second copy of it in a `Vec` to then `fs::write` was
    // pure overhead.
    let file = std::fs::File::create(&index_path)
        .map_err(|e| format!("cannot create {}: {e}", index_path.display()))?;
    let mut writer = std::io::BufWriter::new(file);

    // Ingestion stays SERIAL and is overlapped with reading the next feed.
    //
    // `IndexBuilder` is one giant cross-feed accumulator — `StringPool::intern` hands
    // back a byte offset that is written straight into section payloads, and
    // `ROUTE_TRIPS` encodes `headsign_off` as a uvarint inside a varint stream — so
    // sharding it would mean remapping every offset and re-encoding every trip list.
    // Parsing CSV is the CPU cost; ingesting is pointer-shuffling. Overlapping the two
    // gets most of the win for none of that risk and needs no format change.
    //
    // Depth ONE, deliberately. The comment above is the reason: a feed's tables cost
    // several times the feed on disk, and parsing every feed up front is what made a
    // world build need ~400 GB. One feed queued plus one being parsed plus one being
    // ingested is three resident, and three is the most this is willing to spend
    // without a measurement on real feeds to justify more.
    let mut builder = index::IndexBuilder::new(pack_name);
    let (tx, rx) = std::sync::mpsc::sync_channel::<Result<Loaded, String>>(1);
    let total = specs.len();
    let result: Result<(), String> = std::thread::scope(|scope| {
        scope.spawn(move || {
            for spec in specs {
                // A send failure means the consumer stopped, which happens only when
                // it is already returning an error of its own.
                if tx.send(load_feed(spec)).is_err() {
                    return;
                }
            }
        });

        for (n, loaded) in rx.iter().enumerate() {
            let f = loaded?;
            if f.shapes.is_none() {
                eprintln!(
                    "gtfs_ingest: warning: feed '{}' has no shapes.txt; its ride legs \
                     will draw stop-to-stop",
                    f.name
                );
            }
            if f.calendar.is_none() && f.calendar_dates.is_none() {
                eprintln!(
                    "gtfs_ingest: warning: feed '{}' has no calendar.txt or \
                     calendar_dates.txt; its services will never be scheduled",
                    f.name
                );
            }
            // Without a timezone the device falls back to its own, which is wrong
            // for any feed outside the user's zone.
            if f.agency.as_ref().is_none_or(|a| {
                a.rows.first().is_none_or(|row| a.get(row, "agency_timezone").trim().is_empty())
            }) {
                eprintln!(
                    "gtfs_ingest: warning: feed '{}' has no agency_timezone; \
                     the device will route it in its own local time",
                    f.name
                );
            }
            builder.add_feed_dir(&FeedDir {
                name: f.name.clone(),
                motis_prefix: f.motis_prefix.clone(),
                dir: &f.dir,
                stops: &f.stops,
                routes: &f.routes,
                trips: &f.trips,
                calendar: f.calendar.as_ref(),
                calendar_dates: f.calendar_dates.as_ref(),
                agency: f.agency.as_ref(),
                shapes: f.shapes.as_ref(),
            })?;
            // Per feed, so a regression in the size or count curve shows at feed 200
            // rather than at hour six.
            let (stops_so_far, routes_so_far, trips_so_far) = builder.counts();
            eprintln!(
                "gtfs_ingest: [{}/{total}] {}: {:.1} MiB of sections, {stops_so_far} stops, \
                 {routes_so_far} routes, {trips_so_far} trips",
                n + 1,
                f.name,
                builder.section_bytes() as f64 / (1024.0 * 1024.0),
            );
        }
        Ok(())
    });
    result?;

    let stats = builder.finish_to(&mut writer)?;
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
         \"dropped_stops_bad_coord\": {bad_coord},\n  \
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
        bad_coord = stats.dropped_stops_bad_coord,
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
    if stats.dropped_stops_bad_coord > 0 {
        // Loud, because a coordinate outside the WGS84 ranges would otherwise
        // saturate into the bbox and break the device's coverage test.
        eprintln!(
            "gtfs_ingest: warning: dropped {} stops with a missing or out-of-range \
             stop_lat/stop_lon",
            stats.dropped_stops_bad_coord,
        );
    }
    Ok(())
}

/// One feed's tables, read off disk and owned.
///
/// Exists so reading can happen on a different thread from ingesting: [`FeedDir`]
/// borrows, and a borrow cannot cross the channel that carries the feed to the
/// builder.
struct Loaded {
    name: String,
    motis_prefix: String,
    dir: PathBuf,
    stops: gtfs::Csv,
    routes: gtfs::Csv,
    trips: gtfs::Csv,
    calendar: Option<gtfs::Csv>,
    calendar_dates: Option<gtfs::Csv>,
    agency: Option<gtfs::Csv>,
    shapes: Option<std::collections::HashMap<String, gtfs::Shape>>,
}

/// Read one feed's tables. `stop_times.txt` is NOT read here — `add_feed_dir`
/// streams it from `dir`, which is why the prefetch does not carry the largest file
/// in the feed and why its memory cost is bounded by the smaller tables.
fn load_feed(spec: &FeedSpec) -> Result<Loaded, String> {
    let (name, dir, motis_prefix) = spec;
    let require = |file: &str| -> Result<gtfs::Csv, String> {
        gtfs::read_table(dir, file).ok_or_else(|| {
            format!("feed '{name}' ({}) missing required GTFS file: {file}", dir.display())
        })
    };
    Ok(Loaded {
        name: name.clone(),
        motis_prefix: motis_prefix.clone(),
        dir: dir.clone(),
        stops: require("stops.txt")?,
        routes: require("routes.txt")?,
        trips: require("trips.txt")?,
        calendar: gtfs::read_table(dir, "calendar.txt"),
        calendar_dates: gtfs::read_table(dir, "calendar_dates.txt"),
        agency: gtfs::read_table(dir, "agency.txt"),
        shapes: gtfs::read_shapes(dir),
    })
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

#[cfg(test)]
mod tests {
    use super::*;

    /// A minimal but complete GTFS feed, distinct per tag.
    fn write_feed(root: &Path, tag: &str) -> PathBuf {
        let dir = root.join(tag);
        std::fs::create_dir_all(&dir).unwrap();
        let w = |file: &str, body: String| std::fs::write(dir.join(file), body).unwrap();
        w("agency.txt", format!("agency_id,agency_name,agency_timezone\nA{tag},Agency {tag},America/Los_Angeles\n"));
        w("routes.txt", format!("route_id,agency_id,route_short_name,route_type\nR{tag},A{tag},{tag}1,3\n"));
        w("trips.txt", format!("route_id,service_id,trip_id,trip_headsign\nR{tag},S{tag},T{tag},To {tag}\n"));
        w("calendar.txt", format!("service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nS{tag},1,1,1,1,1,1,1,20260101,20271231\n"));
        w("stops.txt", format!("stop_id,stop_name,stop_lat,stop_lon\nS{tag}1,Stop {tag} One,37.7{},-122.4{}\nS{tag}2,Stop {tag} Two,37.8{},-122.3{}\n", tag.len(), tag.len(), tag.len(), tag.len()));
        w("stop_times.txt", format!("trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT{tag},08:00:00,08:00:00,S{tag}1,1\nT{tag},08:10:00,08:10:00,S{tag}2,2\n"));
        dir
    }

    /// The pack must depend on feed ORDER and nothing else.
    ///
    /// Reading is pipelined onto another thread while `IndexBuilder` ingests, so the
    /// thing that could break is the order `add_feed_dir` sees. Two assertions pin it:
    /// the same specs twice must be byte-identical (the pipeline is deterministic), and
    /// reversed specs must NOT be (order genuinely decides the string-pool offsets, so
    /// the first assertion is not passing for trivial reasons).
    #[test]
    fn the_pack_is_deterministic_and_order_sensitive() {
        let root = std::env::temp_dir().join(format!("gtfs_pack_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let dirs: Vec<PathBuf> = ["a", "bb", "ccc", "dddd", "eeeee"]
            .iter()
            .map(|t| write_feed(&root, t))
            .collect();
        let specs: Vec<FeedSpec> = ["a", "bb", "ccc", "dddd", "eeeee"]
            .iter()
            .zip(&dirs)
            .map(|(t, d)| (t.to_string(), d.clone(), format!("p{t}")))
            .collect();

        let build = |name: &str, specs: &[FeedSpec]| -> Vec<u8> {
            let out = root.join(name);
            run(&out, "world", specs).unwrap();
            std::fs::read(out.join("world.transit")).unwrap()
        };

        let once = build("one", &specs);
        let twice = build("two", &specs);
        assert!(once == twice, "the pipelined pack build is not deterministic");

        let mut reversed = specs.clone();
        reversed.reverse();
        let backwards = build("rev", &reversed);
        assert!(
            backwards != once,
            "feed order does not affect the pack, so the determinism check above \
             proves nothing about ordering"
        );
        let _ = std::fs::remove_dir_all(&root);
    }
}