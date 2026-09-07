//! `transit_shapes` — host build tool emitting the basemap's `transit` layer as
//! newline-delimited GeoJSON (geojsonseq), one `LineString` per distinct rail
//! polyline, carrying the agency's own `route_color`.
//!
//! Usage:
//!   transit_shapes <out.geojsonseq> <feed>...
//!   transit_shapes <out.geojsonseq> --manifest FILE
//!
//! `<feed>` is `feed_name=gtfs_dir[=motis_prefix]` or a bare `gtfs_dir`, exactly
//! as `gtfs_ingest` takes them, so the same `feeds.manifest`
//! `build_ca_transit.ps1` writes drives every tool in this crate.
//!
//! # Why GTFS rather than OSM route relations
//!
//! The layer used to be derived from `type=route` relations in the `.osm.pbf`,
//! which got both halves wrong. A relation's members include its **platforms**,
//! and the member role is not available where the colour is assigned, so every
//! platform way came out as a wide coloured band beside the track. And the colour
//! itself was the relation's `colour=` tag when it had one and a per-mode guess
//! when it did not. `routes.txt` carries the agency's official `route_color` and
//! `shapes.txt` carries the vehicle's real path with no platforms in it at all.
//!
//! # Why not read the `.transit` pack
//!
//! `reader.rs` already decodes a built pack host-side, but the pack's geometry is
//! fitted to a stop pattern, simplified at `shapes::SIMPLIFY_TOLERANCE_M` and
//! trimmed to `[first stop, last stop]`. That is right for drawing a journey leg
//! and wrong for a basemap line, which wants the untrimmed, unsimplified
//! alignment. So this reads the raw feeds.
//!
//! Like the rest of the crate: no serde, the JSON lines are hand-written.

use gtfs_ingest::bundle;
use gtfs_ingest::gtfs::{self, Csv};
use gtfs_ingest::manifest::{parse_feed_spec, read_manifest, FeedSpec};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Mutex;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() >= 2 && (args[1] == "-h" || args[1] == "--help") {
        usage();
        return ExitCode::SUCCESS;
    }
    if args.len() < 3 {
        usage();
        return ExitCode::from(2);
    }
    let out = PathBuf::from(&args[1]);

    let specs: Vec<FeedSpec> = if args[2] == "--manifest" {
        let Some(file) = args.get(3) else {
            eprintln!("transit_shapes: --manifest requires a file path");
            return ExitCode::from(2);
        };
        match read_manifest(Path::new(file)) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("transit_shapes: {e}");
                return ExitCode::FAILURE;
            }
        }
    } else {
        args[2..].iter().map(|s| parse_feed_spec(s)).collect()
    };

    if specs.is_empty() {
        eprintln!("transit_shapes: no feeds given");
        usage();
        return ExitCode::from(2);
    }

    match run(&out, &specs) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("transit_shapes: {e}");
            ExitCode::FAILURE
        }
    }
}

fn usage() {
    eprintln!("usage: transit_shapes <out.geojsonseq> <feed>...");
    eprintln!("       transit_shapes <out.geojsonseq> --manifest FILE");
    eprintln!("  <feed> = feed_name=gtfs_dir[=motis_prefix]  |  gtfs_dir");
}

/// One emitted polyline.
#[derive(Clone)]
struct Line {
    /// The shape's points in `shape_pt_sequence` order, as `(lat_e7, lon_e7)`.
    points: Vec<(i32, i32)>,
    /// `0xRRGGBB`, never zero — zero means "no colour" on the wire and
    /// `Sink::push_transit` refuses it.
    color: u32,
    mode: &'static str,
    /// True when `color` is this mode's fallback rather than the agency's.
    fallback: bool,
    /// `route_short_name` or, failing that, `route_long_name`. The order within a
    /// bundle is the colour and then this, and the tiler reads neither.
    route: String,
    /// Feed name and `route_id`, which is what makes a route one route across the
    /// branches and short-turns it publishes. Never emitted.
    route_key: String,
    /// This colour's index among its corridor's distinct colours. See [`bundle`].
    ordinal: u8,
    /// How many distinct colours that corridor carries. One outside a corridor.
    lanes: u8,
    /// How far into its lane this piece sits, over 255. See [`bundle`].
    taper: u8,
}

/// What one feed contributes, before the cross-feed passes.
#[derive(Default)]
struct FeedLines {
    lines: Vec<Line>,
    routes_kept: usize,
    routes_without_shape: usize,
    /// Distinct usable `shape_id`s seen.
    shapes_read: usize,
}

/// GTFS `route_type` → the basemap's mode vocabulary, or `None` for a mode this
/// layer does not carry (bus, ferry, trolleybus, aerial lift, ...).
///
/// The extended ranges are here because European feeds use them almost
/// exclusively: a feed publishing its metro as 402 rather than 1 would otherwise
/// contribute nothing at all.
fn mode_of(route_type: u32) -> Option<&'static str> {
    Some(match route_type {
        // GTFS 0 is "tram, streetcar, light rail" in one value. `light_rail`
        // rather than `tram` because that is what North American agencies put
        // there — Muni Metro, MAX, Link — and its zoom floor is the shallower of
        // the two, so a street tram filed under 0 surfaces one zoom early rather
        // than a light rail line vanishing until z13.
        0 => "light_rail",
        1 => "subway",
        2 => "train",
        // Cable tram and funicular: short, street-level, and the tram zoom floor.
        5 | 7 => "tram",
        12 => "monorail",
        100..=117 => "train",
        400..=404 => "subway",
        405 => "monorail",
        900..=906 => "tram",
        _ => return None,
    })
}

/// The fallback line colour per mode, as `0xRRGGBB`, for a route naming none.
///
/// **A matched pair with `mamaps_build`'s `schema::transit::MODES`**: the five
/// names above and the five colours here are that table, duplicated because this
/// crate is deliberately detached from the Android workspace and has no
/// dependencies, so the two cannot share code. A change to either is a change to
/// both.
fn fallback_color(mode: &str) -> u32 {
    match mode {
        "subway" => 0xE4002B,
        "light_rail" => 0x00985F,
        "tram" => 0xFFD200,
        "train" => 0x0057A8,
        "monorail" => 0x9D9D9D,
        _ => 0x666666,
    }
}

/// A `route_color` cell as `0xRRGGBB`, or `None` to fall back per mode.
///
/// GTFS spells it as bare `RRGGBB`; a leading `#` is accepted because feeds put
/// one there anyway. Zero is refused rather than emitted: it means "no colour" on
/// the wire, so a route that really is black takes its mode's colour instead.
fn route_color(raw: &str) -> Option<u32> {
    let hex = raw.trim();
    let hex = hex.strip_prefix('#').unwrap_or(hex);
    if hex.len() != 6 || !hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    let value = u32::from_str_radix(hex, 16).ok()?;
    (value != 0).then_some(value)
}

/// A content hash of one polyline, for dedup.
///
/// Rounded to e6 — about 0.1 m — so that two feeds publishing the same alignment
/// with the last digit differing still collapse to one line. FNV-1a over the
/// rounded pairs rather than the coordinates themselves: a world feed set is
/// millions of polylines and holding every one of them as a key would cost more
/// than the layer does. Sixty-four bits over that many lines is a collision
/// chance around one in ten million, against heavy visible over-draw if the
/// dedup is skipped.
///
/// Exact after rounding, and so direction-sensitive. The near-match that
/// collapses a route's two directions is [`bundle::same_line`]; this is the
/// cheap cross-feed pass that runs over every line in the set.
fn polyline_hash(points: &[(i32, i32)]) -> u64 {
    let mut h = 0xcbf2_9ce4_8422_2325u64;
    let mut eat = |v: i32| {
        for b in v.to_le_bytes() {
            h ^= b as u64;
            h = h.wrapping_mul(0x100_0000_01b3);
        }
    };
    for (lat, lon) in points {
        eat(lat.div_euclid(10));
        eat(lon.div_euclid(10));
    }
    h
}

/// Read one feed and reduce it to candidate lines.
fn read_feed(spec: &FeedSpec) -> Result<FeedLines, String> {
    let (name, dir, _) = spec;
    let require = |file: &str| -> Result<Csv, String> {
        gtfs::read_table(dir, file).ok_or_else(|| {
            format!("feed '{name}' ({}) missing required GTFS file: {file}", dir.display())
        })
    };
    let routes_csv = require("routes.txt")?;
    let trips_csv = require("trips.txt")?;
    let Some(shapes) = gtfs::read_shapes(dir) else {
        // Optional in GTFS, and a feed without it can still route — it just draws
        // nothing. Not an error, or one bus-only agency would fail a whole region.
        eprintln!(
            "transit_shapes: warning: feed '{name}' has no usable shapes.txt, so it draws no lines"
        );
        return Ok(FeedLines::default());
    };

    let rail = rail_routes(&routes_csv);
    let by_route = shape_ids_by_route(&trips_csv, &rail);

    let mut out = FeedLines { routes_kept: rail.len(), ..FeedLines::default() };
    for (route_id, shape_ids) in &by_route {
        let (mode, color, fallback, route) = &rail[route_id.as_str()];
        let before = out.lines.len();
        // Every distinct shape, whole. What a route publishes is one alignment per
        // direction plus a short-turn or a branch variant or two, all of them overlapping,
        // and sorting that out is not a per-route job: the same track is published again by
        // every other feed that covers the city. `run` subtracts them all at once.
        for shape_id in shape_ids {
            let Some(shape) = shapes.get(shape_id.as_str()) else { continue };
            // Two points at minimum, or it is not a line.
            if shape.lat_e7.len() < 2 {
                continue;
            }
            out.shapes_read += 1;
            out.lines.push(Line {
                points: shape
                    .lat_e7
                    .iter()
                    .zip(&shape.lon_e7)
                    .map(|(&lat, &lon)| (lat, lon))
                    .collect(),
                color: *color,
                mode,
                fallback: *fallback,
                route: route.clone(),
                route_key: format!("{name}\u{0}{route_id}"),
                ordinal: 0,
                lanes: 1,
                taper: 255,
            });
        }
        if out.lines.len() == before {
            out.routes_without_shape += 1;
        }
    }
    // A rail route no trip references at all never reaches the loop above.
    out.routes_without_shape += rail.len() - by_route.len();
    Ok(out)
}

/// `route_id` → `(mode, colour, is a fallback colour, display name)` for every
/// rail route in the feed. Everything else, bus included, is dropped here.
fn rail_routes(routes: &Csv) -> HashMap<String, (&'static str, u32, bool, String)> {
    let mut out = HashMap::new();
    for row in &routes.rows {
        let id = routes.get(row, "route_id");
        if id.is_empty() {
            continue;
        }
        let Ok(route_type) = routes.get(row, "route_type").trim().parse::<u32>() else { continue };
        let Some(mode) = mode_of(route_type) else { continue };
        let (color, fallback) = match route_color(routes.get(row, "route_color")) {
            Some(color) => (color, false),
            None => (fallback_color(mode), true),
        };
        let short = routes.get(row, "route_short_name").trim();
        let long = routes.get(row, "route_long_name").trim();
        let name = if short.is_empty() { long } else { short };
        out.insert(id.to_string(), (mode, color, fallback, name.to_string()));
    }
    out
}

/// The distinct `shape_id`s each rail route's trips reference.
///
/// One line per **distinct polyline**, not per trip and not per RAPTOR route
/// group: a route's trips reference many shapes (one per direction, one per
/// short-turn variant) and every trip carrying its own line would draw the same
/// alignment hundreds of times. `BTreeMap`/`BTreeSet` rather than the hashed
/// pair, because the output has to be byte-identical between runs.
fn shape_ids_by_route(
    trips: &Csv,
    rail: &HashMap<String, (&'static str, u32, bool, String)>,
) -> BTreeMap<String, BTreeSet<String>> {
    let mut out: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    for row in &trips.rows {
        let route_id = trips.get(row, "route_id");
        if !rail.contains_key(route_id) {
            continue;
        }
        let shape_id = trips.get(row, "shape_id").trim();
        if shape_id.is_empty() {
            continue;
        }
        out.entry(route_id.to_string()).or_default().insert(shape_id.to_string());
    }
    out
}

fn run(out_path: &Path, specs: &[FeedSpec]) -> Result<(), String> {
    let mut seen: HashSet<(u64, u32)> = HashSet::new();
    let mut lines: Vec<Line> = Vec::new();
    // Track already drawn, per colour and per mode. Two of one colour must never draw as
    // parallel lines — nothing distinguishes them, so the second is pure over-draw — and what
    // stops that is the slot, which is assigned per colour so they land on top of each other.
    // This drops the ones that are wholly redundant as well, so the layer does not carry a
    // route's two directions and every other feed's copy of them.
    //
    // The per-mode cover is for a line wearing its mode's fallback colour, which means the
    // feed gave it none: if that track is already drawn in *someone's* colour, a line with
    // no colour of its own should not be drawn over it in a guessed one.
    let mut by_color: BTreeMap<(&'static str, u32), bundle::Covered> = BTreeMap::new();
    let mut by_mode: BTreeMap<&'static str, bundle::Covered> = BTreeMap::new();
    let (mut kept, mut without_shape, mut deduped, mut fell_back) = (0usize, 0usize, 0usize, 0usize);
    let (mut shapes_read, mut merged) = (0usize, 0usize);

    // Feeds are parsed in parallel and folded in sequentially.
    //
    // Parsing is pure per feed and was most of the wall clock: a world-scale set spent ~40 of
    // its 54 minutes here, single-threaded, on machines with dozens of idle cores. The fold
    // below cannot move — `seen`, `by_color` and `by_mode` are order-dependent, and feed order
    // is what decides which duplicate of a shared alignment survives — so only the reads are
    // spread out, and their results are put back into spec order before any of them is folded.
    // The output is therefore bit-identical to the sequential version.
    //
    // Chunked rather than read-all-then-fold: a chunk of twice the pool keeps every core busy
    // while capping how many parsed feeds are alive at once, so peak memory follows the pool
    // size and not the number of feeds.
    let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1);
    let chunk_size = threads.saturating_mul(2).max(1);

    for chunk in specs.chunks(chunk_size) {
        let next = AtomicUsize::new(0);
        let parsed: Mutex<Vec<(usize, Result<FeedLines, String>)>> =
            Mutex::new(Vec::with_capacity(chunk.len()));
        std::thread::scope(|scope| {
            for _ in 0..threads.min(chunk.len()) {
                scope.spawn(|| loop {
                    let i = next.fetch_add(1, Ordering::Relaxed);
                    if i >= chunk.len() {
                        break;
                    }
                    let read = read_feed(&chunk[i]);
                    parsed.lock().expect("feed reader pool").push((i, read));
                });
            }
        });
        let mut parsed = parsed.into_inner().expect("feed reader pool");
        // Back into spec order, so the fold below sees exactly the sequence it used to.
        parsed.sort_unstable_by_key(|(i, _)| *i);

        for (i, feed) in parsed {
            let spec = &chunk[i];
            let feed = feed?;
            kept += feed.routes_kept;
            without_shape += feed.routes_without_shape;
            shapes_read += feed.shapes_read;
            let mut new = 0usize;
            for line in feed.lines {
                // Across feeds as well as within one, so an alignment published by both a city
                // feed and the regional feed that merges it draws once.
                //
                // Keyed on the colour too, which the hash alone was not: two services running
                // the same track are routinely published against one `shape_id`, and dropping
                // one of them because its geometry had been seen is how a corridor loses a
                // line. This is only the fast path for the subtraction below, which would reach
                // the same answer the slow way.
                if !seen.insert((polyline_hash(&line.points), line.color)) {
                    deduped += 1;
                    continue;
                }
                let mode_cover = by_mode.entry(line.mode).or_default();
                let redundant = if line.fallback {
                    mode_cover.contains(&line.points)
                } else {
                    by_color.entry((line.mode, line.color)).or_default().contains(&line.points)
                };
                if redundant {
                    merged += 1;
                    continue;
                }
                mode_cover.add(&line.points);
                by_color.entry((line.mode, line.color)).or_default().add(&line.points);
                if line.fallback {
                    fell_back += 1;
                }
                lines.push(line);
                new += 1;
            }
            eprintln!("transit_shapes: feed '{}': {new} new line(s)", spec.0);
        }
    }
    drop(by_color);
    drop(by_mode);

    // Which routes share a corridor, which lane each takes in it, and where each line has
    // to be cut for that lane to change. Once, over the whole set: neither the tiler nor
    // the renderer sees more than one tile, so deciding it there is what puts a jog in every
    // route at every tile seam.
    let bundled = expand_corridor_spans(&mut lines);

    // Deterministic output, so a rebuild produces a byte-identical layer and the
    // tile diff is empty when nothing changed. Feed order already fixes which
    // duplicate survives; this fixes the order they are written in.
    lines.sort_by(|a, b| {
        a.points
            .cmp(&b.points)
            .then_with(|| a.color.cmp(&b.color))
            .then_with(|| a.mode.cmp(b.mode))
            .then_with(|| (a.ordinal, a.lanes, a.taper).cmp(&(b.ordinal, b.lanes, b.taper)))
            .then_with(|| a.route.cmp(&b.route))
    });

    let file = std::fs::File::create(out_path)
        .map_err(|e| format!("cannot write {}: {e}", out_path.display()))?;
    let mut out = BufWriter::new(file);
    let mut buf: Vec<u8> = Vec::new();
    for line in &lines {
        buf.clear();
        buf.extend_from_slice(
            b"{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[",
        );
        for (i, (lat, lon)) in line.points.iter().enumerate() {
            if i > 0 {
                buf.push(b',');
            }
            write!(buf, "[{:.7},{:.7}]", *lon as f64 * 1e-7, *lat as f64 * 1e-7)
                .map_err(io_err)?;
        }
        write!(
            buf,
            "]}},\"properties\":{{\"color\":\"{:06X}\",\"mode\":\"{}\",\"ordinal\":{},\
             \"lanes\":{},\"taper\":{},\"route\":\"",
            line.color, line.mode, line.ordinal, line.lanes, line.taper,
        )
        .map_err(io_err)?;
        json_escape(line.route.as_bytes(), &mut buf);
        buf.extend_from_slice(b"\"}}\n");
        out.write_all(&buf).map_err(io_err)?;
    }
    out.flush().map_err(io_err)?;

    eprintln!(
        "transit_shapes: wrote {} ({} feed(s), {kept} rail route(s) kept, \
         {without_shape} with no usable shape, {shapes_read} shape(s) read, \
         {deduped} republished byte-for-byte, {merged} already drawn in their colour, \
         {} line(s), {bundled} in a shared corridor, \
         {fell_back} on a per-mode fallback colour)",
        out_path.display(),
        specs.len(),
        lines.len(),
    );
    Ok(())
}

/// Cut every line at its corridor boundaries and give each piece its lane, so one line in
/// becomes several out — the stretch before a corridor on its own geometry, the stretch
/// inside it on the corridor's reference geometry at its lane, and a taper between. Returns
/// how many pieces came out offset into a corridor.
fn expand_corridor_spans(lines: &mut Vec<Line>) -> usize {
    // A route is a route across every branch and short-turn it publishes, so all of
    // them take one lane. `BTreeMap` and first-seen ids, because a `HashMap`'s order
    // would decide the lanes and the output has to be byte-identical between runs.
    let mut ids: BTreeMap<&str, u32> = BTreeMap::new();
    let mut routes: Vec<u32> = Vec::with_capacity(lines.len());
    for line in lines.iter() {
        let next = ids.len() as u32;
        routes.push(*ids.entry(line.route_key.as_str()).or_insert(next));
    }
    let spans = {
        let candidates: Vec<bundle::Candidate> = lines
            .iter()
            .zip(&routes)
            .map(|(line, route)| bundle::Candidate {
                points: &line.points,
                route: *route,
                color: line.color,
                name: &line.route,
            })
            .collect();
        bundle::assign(&candidates)
    };
    let mut bundled = 0usize;
    let mut out: Vec<Line> = Vec::with_capacity(lines.len());
    for (line, spans) in lines.drain(..).zip(spans) {
        for span in spans {
            // A cut can leave nothing between its two ends, and two points at minimum is
            // what makes a line.
            if span.points.len() < 2 {
                continue;
            }
            if span.lanes > 1 {
                bundled += 1;
            }
            out.push(Line {
                points: span.points,
                ordinal: span.ordinal,
                lanes: span.lanes,
                taper: span.taper,
                ..line.clone()
            });
        }
    }
    *lines = out;
    bundled
}

fn io_err(e: impl std::fmt::Display) -> String {
    format!("write failed: {e}")
}

/// Escape a name for a JSON string. UTF-8 bytes pass through verbatim; only the
/// JSON-mandatory escapes and C0 controls are rewritten. Mirrors
/// `transit_stops`' escaper so both layers quote identically.
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
    fn only_rail_route_types_are_carried() {
        assert_eq!(mode_of(0), Some("light_rail"));
        assert_eq!(mode_of(1), Some("subway"));
        assert_eq!(mode_of(2), Some("train"));
        assert_eq!(mode_of(5), Some("tram"), "cable tram");
        assert_eq!(mode_of(7), Some("tram"), "funicular");
        assert_eq!(mode_of(12), Some("monorail"));
        assert_eq!(mode_of(3), None, "a bus is not rail");
        assert_eq!(mode_of(4), None, "nor a ferry");
        assert_eq!(mode_of(11), None, "nor a trolleybus");
        // The extended ranges European feeds publish instead of the basic set.
        assert_eq!(mode_of(109), Some("train"), "suburban railway");
        assert_eq!(mode_of(117), Some("train"));
        assert_eq!(mode_of(403), Some("subway"), "metro");
        assert_eq!(mode_of(405), Some("monorail"));
        assert_eq!(mode_of(906), Some("tram"));
        assert_eq!(mode_of(700), None, "bus service");
        assert_eq!(mode_of(1200), None, "ferry service");
    }

    #[test]
    fn a_route_colour_is_bare_hex_and_never_zero() {
        assert_eq!(route_color("E4002B"), Some(0xE4002B));
        assert_eq!(route_color(" e4002b "), Some(0xE4002B), "trimmed, case-insensitive");
        assert_eq!(route_color("#E4002B"), Some(0xE4002B), "a stray hash is tolerated");
        assert_eq!(route_color(""), None, "absent");
        assert_eq!(route_color("red"), None, "a name is not a colour");
        assert_eq!(route_color("F00"), None, "GTFS has no short form");
        assert_eq!(route_color("GGGGGG"), None, "not hex");
        // Black is the one legal value that cannot be put on the wire.
        assert_eq!(route_color("000000"), None);
    }

    /// The fallback table is the mode vocabulary, and none of it may be zero:
    /// `Sink::push_transit` refuses a zero colour outright.
    #[test]
    fn every_mode_has_a_nonzero_fallback() {
        for mode in ["subway", "light_rail", "tram", "train", "monorail"] {
            assert_ne!(fallback_color(mode), 0, "{mode}");
        }
    }

    #[test]
    fn one_line_per_distinct_shape_not_per_trip() {
        let routes = parse_csv(
            "route_id,route_short_name,route_type,route_color\n\
             SUB,N,1,0054A5\n\
             BUS,38,3,FFFFFF\n",
        );
        let rail = rail_routes(&routes);
        assert_eq!(rail.len(), 1, "the bus route is dropped");
        // Four trips over two shapes, plus a bus trip and a trip with no shape.
        let trips = parse_csv(
            "route_id,trip_id,shape_id\n\
             SUB,T1,OUTBOUND\n\
             SUB,T2,OUTBOUND\n\
             SUB,T3,INBOUND\n\
             SUB,T4,INBOUND\n\
             SUB,T5,\n\
             BUS,T6,BUSSHAPE\n",
        );
        let by_route = shape_ids_by_route(&trips, &rail);
        assert_eq!(by_route.len(), 1);
        assert_eq!(
            by_route["SUB"].iter().cloned().collect::<Vec<String>>(),
            vec!["INBOUND".to_string(), "OUTBOUND".to_string()],
            "two distinct shapes for four trips",
        );
    }

    /// The dedup key. Two feeds tracing the same alignment must collapse; two
    /// different alignments must not.
    #[test]
    fn the_polyline_hash_collapses_only_the_same_alignment() {
        let line: Vec<(i32, i32)> =
            vec![(37_700_000, -122_400_000), (37_705_000, -122_390_000), (37_710_000, -122_400_000)];
        assert_eq!(polyline_hash(&line), polyline_hash(&line));
        // A sub-0.1 m difference in the last digit is the same alignment.
        let jittered: Vec<(i32, i32)> =
            vec![(37_700_003, -122_400_000), (37_705_001, -122_390_000), (37_710_000, -122_400_000)];
        assert_eq!(polyline_hash(&jittered), polyline_hash(&line));
        // A real difference is not.
        let elsewhere: Vec<(i32, i32)> =
            vec![(37_700_000, -122_400_000), (37_705_000, -122_390_000), (37_800_000, -122_400_000)];
        assert_ne!(polyline_hash(&elsewhere), polyline_hash(&line));
        // And neither is the same points in the other direction: this pass is exact
        // after rounding, and the near-match that collapses a route's two directions
        // is `bundle::same_line`, one layer up.
        let reversed: Vec<(i32, i32)> = line.iter().rev().copied().collect();
        assert_ne!(polyline_hash(&reversed), polyline_hash(&line));
    }

    /// End to end over two feeds that share one line, which is the case cross-feed
    /// dedup exists for: Transitous ships a merged regional feed *and* its member
    /// agencies, so one alignment is published twice.
    #[test]
    fn two_feeds_sharing_an_alignment_emit_it_once() {
        let root = std::env::temp_dir().join(format!("gtfs_shapes_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);

        // `shared` is byte-identical in both feeds; each feed also has one of its
        // own. The second feed's copy of the shared route is uncoloured, so if the
        // wrong one survived the colour would change.
        let write_feed = |tag: &str, color: &str, own: &str| -> PathBuf {
            let dir = root.join(tag);
            std::fs::create_dir_all(&dir).unwrap();
            let w = |file: &str, body: String| std::fs::write(dir.join(file), body).unwrap();
            w(
                "routes.txt",
                format!(
                    "route_id,route_short_name,route_type,route_color\n\
                     SHARED,S,1,{color}\n\
                     OWN,O,0,00FF00\n\
                     BUS,B,3,123456\n"
                ),
            );
            w(
                "trips.txt",
                "route_id,trip_id,shape_id\n\
                 SHARED,T1,SH_SHARED\n\
                 SHARED,T2,SH_SHARED\n\
                 OWN,T3,SH_OWN\n\
                 BUS,T4,SH_BUS\n"
                    .to_string(),
            );
            w(
                "shapes.txt",
                format!(
                    "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n\
                     SH_SHARED,37.700,-122.400,1\n\
                     SH_SHARED,37.710,-122.390,2\n\
                     SH_OWN,{own},-122.300,1\n\
                     SH_OWN,{own},-122.290,2\n\
                     SH_BUS,37.600,-122.500,1\n\
                     SH_BUS,37.610,-122.490,2\n"
                ),
            );
            dir
        };

        let specs: Vec<FeedSpec> = vec![
            ("a".to_string(), write_feed("a", "0054A5", "37.800"), String::new()),
            // No colour on `b`'s copy of SHARED, so `a`'s must be the one kept.
            ("b".to_string(), write_feed("b", "", "37.900"), String::new()),
        ];

        let out = root.join("routes.geojsonseq");
        run(&out, &specs).unwrap();
        let text = std::fs::read_to_string(&out).unwrap();
        let emitted: Vec<&str> = text.lines().collect();

        assert_eq!(emitted.len(), 3, "one shared line plus one per feed: {text}");
        assert_eq!(
            text.matches("\"mode\":\"subway\"").count(),
            1,
            "the shared alignment must dedup to one feature: {text}"
        );
        assert!(text.contains("\"color\":\"0054A5\""), "the first feed's colour wins: {text}");
        assert_eq!(text.matches("\"mode\":\"light_rail\"").count(), 2, "one per feed: {text}");
        assert!(!text.contains("122.5"), "the bus shape must not be emitted: {text}");
        // Nothing on the wire may carry colour zero, and the uncoloured route took
        // its mode's fallback rather than black.
        assert!(!text.contains("\"color\":\"000000\""), "{text}");
        // Byte-identical between runs.
        let again = root.join("routes.again.geojsonseq");
        run(&again, &specs).unwrap();
        assert_eq!(std::fs::read(&out).unwrap(), std::fs::read(&again).unwrap());

        let _ = std::fs::remove_dir_all(&root);
    }

    /// A route's own two directions draw as one line; its branch draws only where it
    /// branches. THE case: a corridor of six services drew twelve lines before this.
    #[test]
    fn a_routes_two_directions_draw_as_one_line() {
        let root =
            std::env::temp_dir().join(format!("gtfs_shapes_collapse_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        let w = |file: &str, body: &str| std::fs::write(root.join(file), body).unwrap();
        w("routes.txt", "route_id,route_short_name,route_type,route_color\nN,N,0,00985F\n");
        w(
            "trips.txt",
            "route_id,trip_id,shape_id\nN,T1,OUT\nN,T2,BACK\nN,T3,BRANCH\n",
        );
        // OUT runs 5.5 km north; BACK is the other track, 9 m west, run the other way;
        // BRANCH follows OUT for 2.7 km and then strikes east for 4 km.
        w(
            "shapes.txt",
            "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n\
             OUT,37.7000,-122.4000,1\n\
             OUT,37.7250,-122.4000,2\n\
             OUT,37.7500,-122.4000,3\n\
             BACK,37.7500,-122.40010,1\n\
             BACK,37.7250,-122.40010,2\n\
             BACK,37.7000,-122.40010,3\n\
             BRANCH,37.7000,-122.4000,1\n\
             BRANCH,37.7250,-122.4000,2\n\
             BRANCH,37.7250,-122.3550,3\n",
        );

        let feed = read_feed(&("solo".to_string(), root.clone(), String::new())).unwrap();
        assert_eq!(feed.shapes_read, 3, "every shape reaches the cross-feed pass whole");

        let out = root.join("routes.geojsonseq");
        run(&out, &vec![("solo".to_string(), root.clone(), String::new())]).unwrap();
        let text = std::fs::read_to_string(&out).unwrap();
        let emitted: Vec<&str> = text.lines().collect();
        assert_eq!(emitted.len(), 2, "the trunk once, plus the branch's own leg: {text}");
        // Nothing of one colour may be parallel to itself, so the second direction is gone
        // and the branch kept only what it added.
        assert_eq!(
            text.matches("\"lanes\":1").count(),
            2,
            "one service, no fan: {text}",
        );
        assert!(emitted.iter().any(|l| l.contains("-122.355")), "the branch's leg: {text}");

        let _ = std::fs::remove_dir_all(&root);
    }

    /// Two services over one track fan out either side of it, and a third route
    /// running elsewhere stays on its own alignment.
    #[test]
    fn routes_sharing_a_corridor_take_lanes_either_side_of_it() {
        let root = std::env::temp_dir().join(format!("gtfs_shapes_spread_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        let w = |file: &str, body: &str| std::fs::write(root.join(file), body).unwrap();
        w(
            "routes.txt",
            "route_id,route_short_name,route_type,route_color\n\
             A,A,1,0000FF\n\
             B,B,1,FF0000\n\
             C,C,1,00FF00\n",
        );
        w("trips.txt", "route_id,trip_id,shape_id\nA,T1,SA\nB,T2,SB\nC,T3,SC\n");
        // SA and SB are one track 9 m apart; SC is a kilometre east.
        let mut shapes = String::from("shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n");
        for i in 0..21 {
            let lat = 37.7 + i as f64 * 0.0005;
            shapes.push_str(&format!("SA,{lat:.4},-122.40000,{}\n", i + 1));
            shapes.push_str(&format!("SB,{lat:.4},-122.40010,{}\n", i + 1));
            shapes.push_str(&format!("SC,{lat:.4},-122.38000,{}\n", i + 1));
        }
        w("shapes.txt", &shapes);

        let out = root.join("routes.geojsonseq");
        let specs: Vec<FeedSpec> = vec![("solo".to_string(), root.clone(), String::new())];
        run(&out, &specs).unwrap();
        let text = std::fs::read_to_string(&out).unwrap();

        assert_eq!(text.lines().count(), 3, "{text}");
        // The corridor reports two colours and hands out both ordinals; which side of the
        // track each lands on is the renderer's arithmetic, not this file's.
        assert_eq!(text.matches("\"ordinal\":0,\"lanes\":2").count(), 1, "{text}");
        assert_eq!(text.matches("\"ordinal\":1,\"lanes\":2").count(), 1, "{text}");
        // And the route with the corridor to itself is not moved off its alignment.
        assert_eq!(text.matches("\"ordinal\":0,\"lanes\":1").count(), 1, "{text}");

        // Byte-identical between runs, lanes included.
        let again = root.join("routes.again.geojsonseq");
        run(&again, &specs).unwrap();
        assert_eq!(std::fs::read(&out).unwrap(), std::fs::read(&again).unwrap());

        let _ = std::fs::remove_dir_all(&root);
    }

    /// The cross-feed pass the exact hash cannot do. Transitous and 511-style regional
    /// feeds republish their member agencies' lines **re-surveyed**, a few metres off, so
    /// the hashes differ — and every city inside such a feed would otherwise draw twice and
    /// pair each line with its own duplicate into a two-route corridor instead of joining the
    /// corridor it really shares.
    #[test]
    fn a_line_another_feed_re_surveyed_collapses_onto_the_first() {
        let root = std::env::temp_dir().join(format!("gtfs_shapes_resurvey_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);

        // `city` and `regional` trace one alignment; the regional copy is shifted about 4 m
        // east and carries an extra intermediate point, so no hash can match them. The
        // regional feed also has a differently-coloured service over the *same* track,
        // which must survive: that is what the corridor slots exist to draw.
        let write_feed = |tag: &str, lon_a: &str, lon_b: &str, extra: &str| -> PathBuf {
            let dir = root.join(tag);
            std::fs::create_dir_all(&dir).unwrap();
            let w = |file: &str, body: String| std::fs::write(dir.join(file), body).unwrap();
            w(
                "routes.txt",
                "route_id,route_short_name,route_type,route_color\nN,N,1,0054A5\n".to_string(),
            );
            w("trips.txt", "route_id,trip_id,shape_id\nN,T1,SH\n".to_string());
            w(
                "shapes.txt",
                format!(
                    "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n\
                     SH,37.7000,{lon_a},1\n{extra}SH,37.7100,{lon_b},3\n"
                ),
            );
            dir
        };
        let city = write_feed("city", "-122.40000", "-122.40000", "");
        let regional = write_feed(
            "regional",
            "-122.400045",
            "-122.400045",
            "SH,37.7050,-122.400045,2\n",
        );
        // A second service of another colour over the same track, in its own feed.
        let other = root.join("other");
        std::fs::create_dir_all(&other).unwrap();
        std::fs::write(
            other.join("routes.txt"),
            "route_id,route_short_name,route_type,route_color\nK,K,1,E31E24\n",
        )
        .unwrap();
        std::fs::write(other.join("trips.txt"), "route_id,trip_id,shape_id\nK,T1,SH\n").unwrap();
        std::fs::write(
            other.join("shapes.txt"),
            "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n\
             SH,37.7000,-122.40000,1\n\
             SH,37.7100,-122.40000,2\n",
        )
        .unwrap();

        let specs: Vec<FeedSpec> = vec![
            ("city".to_string(), city, String::new()),
            ("regional".to_string(), regional, String::new()),
            ("other".to_string(), other, String::new()),
        ];
        let out = root.join("routes.geojsonseq");
        run(&out, &specs).unwrap();
        let text = std::fs::read_to_string(&out).unwrap();

        assert_eq!(
            text.matches("\"color\":\"0054A5\"").count(),
            1,
            "the re-surveyed republication must collapse onto the first: {text}",
        );
        assert_eq!(
            text.matches("\"color\":\"E31E24\"").count(),
            1,
            "a different service over the same track must survive: {text}",
        );
        // And the two that survive share the track, so they fan out either side of it
        // rather than both collapsing to one lane.
        assert_eq!(text.matches("\"ordinal\":0,\"lanes\":2").count(), 1, "{text}");
        assert_eq!(text.matches("\"ordinal\":1,\"lanes\":2").count(), 1, "{text}");

        let _ = std::fs::remove_dir_all(&root);
    }

    /// A rail route whose trips name a shape the feed does not carry is reported,
    /// not silently emitted as an empty line.
    #[test]
    fn a_route_with_no_usable_shape_is_counted_and_drawn_nothing() {
        let root = std::env::temp_dir().join(format!("gtfs_shapes_noshape_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        let w = |file: &str, body: &str| std::fs::write(root.join(file), body).unwrap();
        w("routes.txt", "route_id,route_short_name,route_type,route_color\nR,R,2,\n");
        w("trips.txt", "route_id,trip_id,shape_id\nR,T1,MISSING\n");
        // One point is not a line, so even a present shape can be unusable.
        w(
            "shapes.txt",
            "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\nOTHER,37.7,-122.4,1\n",
        );

        let feed = read_feed(&("solo".to_string(), root.clone(), String::new())).unwrap();
        assert_eq!(feed.routes_kept, 1);
        assert_eq!(feed.routes_without_shape, 1);
        assert!(feed.lines.is_empty());

        let _ = std::fs::remove_dir_all(&root);
    }
}
