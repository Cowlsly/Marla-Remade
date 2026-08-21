//! `transit_dump` — a sort-stable, semantic text dump of a `.transit` pack.
//!
//! The pack's *bytes* are deliberately non-reproducible: trips are grouped out of
//! a `HashMap`, so RAPTOR route order, profile ids and service indices all vary
//! run to run (see the crate README). Byte-diffing two packs therefore proves
//! nothing. This dump exists so a refactor of the builder can be verified the
//! only way that is actually valid: dump before, dump after, `diff`, expect
//! empty.
//!
//! Everything whose order is a `HashMap` artefact is either sorted (route blocks,
//! trip lines) or replaced by a content signature (services, referenced by their
//! calendar + exceptions rather than by index). Everything whose order *is*
//! contractual — stop indices, grid cells, a route's stop pattern, a route's
//! trips by start time — is dumped in that order, so a reordering shows up.
//!
//! Usage: `transit_dump <pack.transit> [out.txt]`  (stdout when `out.txt` is
//! omitted). Output is one line per stop and one per trip, so it is much larger
//! than the pack; it is meant for the small verification corpora, not `world`.

use gtfs_ingest::reader::{Reader, RouteRec};
use std::collections::HashMap;
use std::io::{BufWriter, Write};
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 || args.len() > 3 {
        eprintln!("usage: transit_dump <pack.transit> [out.txt]");
        return ExitCode::from(2);
    }
    let reader = match Reader::open(std::path::Path::new(&args[1])) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("transit_dump: {e}");
            return ExitCode::FAILURE;
        }
    };
    let result = match args.get(2) {
        Some(path) => match std::fs::File::create(path) {
            Ok(f) => dump(&reader, &mut BufWriter::new(f)),
            Err(e) => Err(format!("cannot create {path}: {e}")),
        },
        None => {
            let stdout = std::io::stdout();
            dump(&reader, &mut BufWriter::new(stdout.lock()))
        }
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("transit_dump: {e}");
            ExitCode::FAILURE
        }
    }
}

fn dump(r: &Reader, out: &mut impl Write) -> Result<(), String> {
    let write = |out: &mut dyn Write, s: String| -> Result<(), String> {
        out.write_all(s.as_bytes()).map_err(|e| format!("write failed: {e}"))
    };

    write(out, format!("pack {:?}\n", r.pack_name()))?;
    write(out, format!("version {} sections {}\n", r.version(), r.section_count()))?;
    write(
        out,
        format!(
            "counts stops={} routes={} trips={} services={} profiles={} feeds={} grid_cells={}\n",
            r.stop_count(),
            r.route_count(),
            r.trip_count(),
            r.service_count(),
            r.profile_count(),
            r.feed_count(),
            r.grid_cell_count(),
        ),
    )?;
    let (min_lat, min_lon, max_lat, max_lon) = r.bbox();
    write(out, format!("bbox {min_lat} {min_lon} {max_lat} {max_lon}\n"))?;
    let g = r.grid();
    write(
        out,
        format!(
            "grid origin={},{} cell={} cols={} rows={}\n",
            g.lat0_e7, g.lon0_e7, g.cell_e7, g.cols, g.rows
        ),
    )?;

    // Services are referenced by content, not index: a service that only ever
    // appears on a trip is registered while iterating a `HashMap`, so its index
    // is one of the things that legitimately moves between runs.
    let sigs: Vec<String> = (0..r.service_count()).map(|s| service_sig(r, s)).collect();

    // --- Routes, grouped by feed and sorted within it ---
    let mut per_feed: Vec<Vec<String>> = vec![Vec::new(); r.feed_count() as usize];
    for ridx in 0..r.route_count() {
        let rec = r.route(ridx);
        per_feed[rec.feed_idx as usize].push(route_block(r, ridx, &rec, &sigs));
    }
    for feed in 0..r.feed_count() {
        write(
            out,
            format!(
                "feed {feed} name={:?} tz={:?} motis={:?}\n",
                r.feed_name(feed),
                r.feed_tz(feed),
                r.feed_motis_prefix(feed)
            ),
        )?;
        let blocks = &mut per_feed[feed as usize];
        blocks.sort();
        for b in blocks.iter() {
            write(out, b.clone())?;
        }
    }

    // --- Stops, in index order (per-feed contiguous, stops.txt order within) ---
    for s in 0..r.stop_count() {
        let (lat, lon) = r.stop_ll_e7(s);
        // A stop's routes come out of the builder in route order, which moves;
        // name them and sort.
        let mut routes: Vec<String> = r
            .stop_routes(s)
            .iter()
            .map(|&(route, pos)| format!("{:?}@{pos}", r.read_str(r.route(route).name_off)))
            .collect();
        routes.sort();
        let transfers: Vec<String> =
            r.transfers(s).iter().map(|&(to, secs)| format!("{to}:{secs}")).collect();
        write(
            out,
            format!(
                "stop {s} gtfs={:?} name={:?} code={:?} ll={lat},{lon} routes=[{}] transfers=[{}]\n",
                r.stop_gtfs_id(s),
                r.stop_name(s),
                r.stop_code(s),
                routes.join(" "),
                transfers.join(" "),
            ),
        )?;
    }

    // --- Distinct service definitions, sorted, with how many services share one ---
    let mut sig_counts: HashMap<&str, usize> = HashMap::new();
    for sig in &sigs {
        *sig_counts.entry(sig.as_str()).or_insert(0) += 1;
    }
    let mut distinct: Vec<(&&str, &usize)> = sig_counts.iter().collect();
    distinct.sort();
    for (sig, n) in distinct {
        write(out, format!("service x{n} {sig}\n"))?;
    }

    // --- Spatial grid, in file order (the reader binary-searches it ascending) ---
    let cells = r.grid_cells();
    assert!(
        cells.windows(2).all(|w| w[0].0 < w[1].0),
        "GRID_CELL_IDS must be strictly ascending or the device's binary search breaks"
    );
    for (cid, stops) in &cells {
        let list: Vec<String> = stops.iter().map(|s| s.to_string()).collect();
        write(out, format!("cell {cid} stops=[{}]\n", list.join(" ")))?;
    }

    out.flush().map_err(|e| format!("flush failed: {e}"))
}

/// One service's calendar plus its exceptions, as a content signature.
fn service_sig(r: &Reader, service_idx: u32) -> String {
    let svc = r.service(service_idx);
    let exc = r.service_exceptions(service_idx);
    assert!(
        exc.windows(2).all(|w| w[0].1 < w[1].1),
        "service {service_idx} exceptions must be date-ascending and deduped, got {exc:?}"
    );
    let dates: Vec<String> = exc
        .iter()
        .map(|&(sidx, date, added)| {
            assert_eq!(sidx, service_idx, "EXCEPTIONS row outside its CSR range");
            format!("{}{date}", if added == 1 { '+' } else { '-' })
        })
        .collect();
    format!(
        "days={:07b} {}..{} exc=[{}]",
        svc.weekday_mask,
        svc.start_date,
        svc.end_date,
        dates.join(" ")
    )
}

/// A whole route: its identity, geometry summary, stop pattern and every trip
/// with profile-expanded absolute times.
fn route_block(r: &Reader, ridx: u32, rec: &RouteRec, sigs: &[String]) -> String {
    let stops = r.route_stops(rec);
    let pattern: Vec<String> = stops
        .iter()
        .enumerate()
        .map(|(pos, &s)| {
            let v = r.route_stop_shape(rec.first_route_stop + pos as u32);
            if v == u32::MAX {
                format!("{s}")
            } else {
                format!("{s}@{v}")
            }
        })
        .collect();
    let shape = match r.route_shape_off(ridx) {
        None => "none".to_string(),
        Some(off) => {
            let pts = r.shape_points(off);
            format!("n={},sum={:016x}", pts.len(), fnv1a_points(&pts))
        }
    };
    let mut lines = vec![format!(
        "  route name={:?} type={} color={:06x} shape={shape} stops=[{}]\n",
        r.read_str(rec.name_off),
        rec.route_type,
        rec.color,
        pattern.join(" "),
    )];

    // Trips are start-time ordered by construction, but ties are `HashMap`
    // order, so sort the rendered lines. Zero-padding keeps that a numeric sort.
    let mut trips: Vec<String> = r
        .route_trips(rec)
        .iter()
        .map(|t| {
            let start = t.start_time as i64;
            let times: Vec<String> = r
                .profile(t.profile_id)
                .iter()
                .map(|&(arr, dep)| format!("{}:{}", start + arr, start + dep))
                .collect();
            assert_eq!(
                times.len(),
                stops.len(),
                "route {ridx} trip at {start} has {} profile entries for {} stops",
                times.len(),
                stops.len()
            );
            format!(
                "    trip start={:08} svc={} headsign={:?} times=[{}]\n",
                t.start_time,
                sigs[t.service_idx as usize],
                r.read_str(t.headsign_off),
                times.join(" "),
            )
        })
        .collect();
    trips.sort();
    lines.append(&mut trips);
    lines.concat()
}

/// FNV-1a over a polyline's coordinates: a full comparison of the geometry
/// without printing every vertex of every route.
fn fnv1a_points(pts: &[(i32, i32)]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for &(lat, lon) in pts {
        for b in lat.to_le_bytes().iter().chain(lon.to_le_bytes().iter()) {
            h ^= *b as u64;
            h = h.wrapping_mul(0x100_0000_01b3);
        }
    }
    h
}
