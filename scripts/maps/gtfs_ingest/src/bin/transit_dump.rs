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
//! Usage:
//!   `transit_dump <pack.transit> [out.txt]`   full dump (stdout when omitted)
//!   `transit_dump --at <lat>,<lon> <pack>`    one stop's departure board
//!
//! The full dump is one line per stop and one per trip, so it is much larger than
//! the pack — meant for the small verification corpora, not `world`. `--at` is the
//! spot check for a big pack: it answers "does this coordinate resolve to the
//! right stop, do plausible routes depart it, and does it reach another agency's
//! stops on foot" without materializing gigabytes of text.

use gtfs_ingest::reader::{Reader, RouteRec};
use std::collections::{HashMap, HashSet};
use std::io::{BufWriter, Write};
use std::process::ExitCode;

enum Cmd<'a> {
    Dump { pack: &'a str, out: Option<&'a str> },
    Probe { pack: &'a str, lat: f64, lon: f64 },
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let cmd = match parse_args(&args) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("transit_dump: {e}\n{USAGE}");
            // 2 for "you invoked it wrong", 1 for "it ran and failed".
            return ExitCode::from(2);
        }
    };
    let result = match cmd {
        Cmd::Probe { pack, lat, lon } => Reader::open(std::path::Path::new(pack))
            .and_then(|r| {
                let stdout = std::io::stdout();
                probe(&r, lat, lon, &mut BufWriter::new(stdout.lock()))
            }),
        Cmd::Dump { pack, out } => Reader::open(std::path::Path::new(pack)).and_then(|r| {
            match out {
                Some(path) => match std::fs::File::create(path) {
                    Ok(f) => dump(&r, &mut BufWriter::new(f)),
                    Err(e) => Err(format!("cannot create {path}: {e}")),
                },
                None => {
                    let stdout = std::io::stdout();
                    dump(&r, &mut BufWriter::new(stdout.lock()))
                }
            }
        }),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("transit_dump: {e}");
            ExitCode::FAILURE
        }
    }
}

const USAGE: &str = "usage: transit_dump <pack.transit> [out.txt]\n       \
                     transit_dump --at <lat>,<lon> <pack.transit>";

fn parse_args(args: &[String]) -> Result<Cmd<'_>, String> {
    match args.get(1).map(String::as_str) {
        Some("--at") => {
            let (at, pack) = match (args.get(2), args.get(3), args.len()) {
                (Some(at), Some(pack), 4) => (at.as_str(), pack.as_str()),
                _ => return Err("--at takes a lat,lon and one pack".to_string()),
            };
            let (lat, lon) =
                at.split_once(',').ok_or_else(|| format!("--at wants lat,lon, got {at:?}"))?;
            let lat: f64 = lat.trim().parse().map_err(|_| format!("bad latitude {lat:?}"))?;
            let lon: f64 = lon.trim().parse().map_err(|_| format!("bad longitude {lon:?}"))?;
            // Checked because the grid lookup clamps rather than rejects, so a
            // transposed `lon,lat` would otherwise return a confidently wrong
            // "nearest" stop instead of an error.
            if !(-90.0..=90.0).contains(&lat) || !(-180.0..=180.0).contains(&lon) {
                return Err(format!("{lat},{lon} is not a lat,lon — are they the right way round?"));
            }
            Ok(Cmd::Probe { pack, lat, lon })
        }
        Some(pack) if args.len() <= 3 => {
            Ok(Cmd::Dump { pack, out: args.get(2).map(String::as_str) })
        }
        _ => Err("expected a pack path".to_string()),
    }
}

/// Seconds since service midnight as `HH:MM:SS`; hours may exceed 24, which is
/// legal GTFS and means "after midnight on the service day".
fn hms(secs: u32) -> String {
    format!("{:02}:{:02}:{:02}", secs / 3600, (secs / 60) % 60, secs % 60)
}

/// Approximate ground distance in metres (equirectangular; fine at this range).
fn metres(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let dlat = (lat2 - lat1) * 111_320.0;
    let dlon = (lon2 - lon1) * 111_320.0 * ((lat1 + lat2) * 0.5).to_radians().cos();
    (dlat * dlat + dlon * dlon).sqrt()
}

/// The nearest stop to `(lat, lon)` with every route that serves it, that route's
/// departures *from this stop*, and the footpaths leading away from it.
///
/// `first`/`last` span every trip on the route regardless of calendar, so `svcs`
/// reports how many distinct services those trips belong to — a route whose span
/// looks odd is usually one mixing a weekday and a weekend calendar. At a route's
/// terminus the profile's departure equals its arrival, which is marked rather
/// than presented as a departure that never happens.
fn probe(r: &Reader, lat: f64, lon: f64, out: &mut impl Write) -> Result<(), String> {
    let stop = r
        .nearest(lat, lon)
        .ok_or_else(|| format!("no stop near {lat},{lon}; is it inside the pack's bbox?"))?;
    let (min_lat, min_lon, max_lat, max_lon) = r.bbox();
    let (slat, slon) = r.stop_ll(stop);
    let mut w = |s: String| out.write_all(s.as_bytes()).map_err(|e| format!("write: {e}"));

    w(format!(
        "pack {:?} bbox {:.6},{:.6} .. {:.6},{:.6}\n",
        r.pack_name(),
        min_lat as f64 * 1e-7,
        min_lon as f64 * 1e-7,
        max_lat as f64 * 1e-7,
        max_lon as f64 * 1e-7,
    ))?;
    // The distance matters: `nearest` searches only ±1 grid cell, exactly as the
    // device does, so it answers with whatever it finds there — a coordinate well
    // outside the transit area still resolves to *something*.
    w(format!(
        "nearest stop {stop} name={:?} gtfs={:?} code={:?} at {slat:.6},{slon:.6} ({:.0} m away)\n",
        r.stop_name(stop),
        r.stop_gtfs_id(stop),
        r.stop_code(stop),
        metres(lat, lon, slat, slon),
    ))?;

    // Which feed a stop belongs to is not stored; it is whichever feeds' routes
    // serve it, which is also what makes a footpath between two feeds visible.
    let feeds_of = |s: u32| -> Vec<String> {
        let mut names: Vec<String> = r
            .stop_routes(s)
            .iter()
            .map(|&(route, _)| r.feed_name(r.route(route).feed_idx))
            .collect::<HashSet<_>>()
            .into_iter()
            .collect();
        names.sort();
        names
    };

    let mut routes = r.stop_routes(stop);
    routes.sort_by_key(|&(route, _)| r.read_str(r.route(route).name_off));
    for (route, pos) in routes {
        let rec = r.route(route);
        let trips = r.route_trips(&rec);
        // The board is the departure at *this* stop, not the trip's start.
        let mut deps: Vec<u32> = trips
            .iter()
            .map(|t| {
                let prof = r.profile(t.profile_id);
                (t.start_time as i64 + prof[pos as usize].1) as u32
            })
            .collect();
        deps.sort_unstable();
        let services: HashSet<u32> = trips.iter().map(|t| t.service_idx).collect();
        w(format!(
            "  route {:?} type={} feed={:?} pos={pos}/{}{} trips={} svcs={} first={} last={}\n",
            r.read_str(rec.name_off),
            rec.route_type,
            r.feed_name(rec.feed_idx),
            rec.n_stops,
            if pos + 1 == rec.n_stops { " terminus" } else { "" },
            deps.len(),
            services.len(),
            deps.first().copied().map_or("-".to_string(), hms),
            deps.last().copied().map_or("-".to_string(), hms),
        ))?;
    }

    let here = feeds_of(stop);
    for (to, secs) in r.transfers(stop) {
        let there = feeds_of(to);
        // "cross-feed", not "cross-agency": one feed may carry several agencies,
        // and only the feed is recorded per route.
        let cross = !here.is_empty() && there.iter().any(|f| !here.contains(f));
        w(format!(
            "  footpath {secs}s -> stop {to} name={:?} feeds={:?}{}\n",
            r.stop_name(to),
            there,
            if cross { "  [cross-feed]" } else { "" },
        ))?;
    }
    out.flush().map_err(|e| format!("flush failed: {e}"))
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
