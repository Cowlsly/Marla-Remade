//! Build the compact **world** transit index from parsed GTFS tables (possibly
//! merged from many feeds) and serialize it to the on-disk `.transit` format.
//!
//! ON-DISK FORMAT v2 ("TRX2", little-endian, mmap-friendly, read via
//! `read_unaligned` on device). THIS LAYOUT MUST STAY IN SYNC WITH
//! `maps/src/main/rust/src/transit.rs`.
//!
//! The v2 redesign exists to make a single global (world) pack feasible. v1
//! stored `(arr,dep)` u32 per stop *per trip* (8 B) and referenced them via a
//! `u32 first_stoptime`, so a worldwide pack was ~10–20 GB and bumped the 4.29 B
//! stop-time ceiling. v2 instead:
//!   * factors each trip into `{ start_time, profile_id }` where a **profile** is
//!     the varint-delta-encoded run-time *shape* (per-stop hop + dwell offsets),
//!     deduplicated across all trips sharing that shape — this removes both the
//!     per-trip stop-time table and the u32 ceiling;
//!   * packs each RAPTOR route's trips as varints (start-time deltas + ids);
//!   * adds a **spatial grid** (sparse CSR) so nearest-stop / bbox queries are
//!     cell-local instead of O(all stops);
//!   * adds a **FEEDS** table + per-route `feed_idx` so many agencies merge into
//!     one pack without id collisions (ids are namespaced per feed at build).
//!
//! Header (80 bytes; all u32/i32 little-endian):
//!   u32 magic (MAGIC), u32 version (VERSION=2), u32 section_count,
//!   u32 stop_count, u32 route_count, u32 trip_count, u32 service_count,
//!   u32 profile_count, u32 feed_count, u32 grid_cell_count, u32 feed_name_off,
//!   i32 min_lat_e7, i32 min_lon_e7, i32 max_lat_e7, i32 max_lon_e7,
//!   i32 grid_lat0_e7, i32 grid_lon0_e7, u32 grid_cell_e7, u32 grid_cols,
//!   u32 grid_rows
//! Section directory: section_count * { u64 offset, u64 len } (absolute byte
//!   offset from file start, byte length; 8-byte aligned, may exceed 4 GB).
//!
//! Sections (index -> payload):
//!   0  STRINGS         NUL-terminated UTF-8 string pool; offsets are byte
//!                      offsets into this section, sentinel NONE = no string.
//!   1  STOPS           StopRec[stop_count] = { i32 lat_e7, i32 lon_e7,
//!                        u32 name_off, u32 code_off }.
//!   2  ROUTES          RouteRec[route_count] = { u32 name_off, u32 color,
//!                        u32 route_type, u32 feed_idx, u32 n_stops,
//!                        u32 first_route_stop, u32 n_trips, u32 trips_off };
//!                        `trips_off` is a byte offset into ROUTE_TRIPS.
//!   3  ROUTE_STOPS     u32[] ordered stop indices; RouteRec slices it by
//!                        [first_route_stop, +n_stops].
//!   4  ROUTE_TRIPS     varint stream. Each route's block (at RouteRec.trips_off,
//!                        n_trips entries) is, per trip in start-time order:
//!                        uvarint start_delta (Δ from previous trip's start_time,
//!                        first = absolute), uvarint profile_id,
//!                        uvarint service_idx, uvarint headsign_off.
//!   5  PROFILES        varint stream. Each profile (at PROFILES_IDX[id]) is:
//!                        uvarint n, uvarint dwell0, then for k in 1..n
//!                        uvarint hop[k], uvarint dwell[k]. Given a trip start
//!                        time T: dep[0]=T, arr[0]=T-dwell0, and for k>=1
//!                        arr[k]=dep[k-1]+hop[k], dep[k]=arr[k]+dwell[k].
//!   6  PROFILES_IDX    u32[profile_count + 1] byte offsets into PROFILES.
//!   7  STOP_ROUTES     u32[] route indices serving each stop (flattened).
//!   8  STOP_ROUTES_IDX u32[stop_count + 1] prefix offsets into STOP_ROUTES.
//!   9  TRANSFERS       Transfer[] = { u32 to_stop, u32 secs } footpaths.
//!  10  TRANSFERS_IDX   u32[stop_count + 1] prefix offsets into TRANSFERS.
//!  11  SERVICES        ServiceRec[service_count] = { u8 weekday_mask,
//!                        u8[3] _pad, u32 start_date, u32 end_date }.
//!  12  EXCEPTIONS      ExcRec[] = { u32 service_idx, u32 date, u32 added }
//!                        (added: 1 = service added on date, 0 = removed).
//!  13  GRID_CELL_IDS   u32[grid_cell_count] non-empty cell ids, ascending.
//!                        cell_id = row*grid_cols + col, row/col from the header
//!                        grid origin + grid_cell_e7.
//!  14  GRID_CELL_OFF   u32[grid_cell_count + 1] prefix offsets into GRID_STOPS.
//!  15  GRID_STOPS      u32[stop_count] stop indices grouped by cell (in the
//!                        GRID_CELL_IDS order).
//!  16  FEEDS           FeedRec[feed_count] = { u32 name_off }.

use crate::gtfs::{parse_gtfs_date, parse_gtfs_time, Csv};
use std::collections::HashMap;

pub const MAGIC: u32 = 0x5452_4958; // "TRIX"
pub const VERSION: u32 = 2;
pub const NONE: u32 = 0xFFFF_FFFF;
pub const SECTION_COUNT: usize = 17;
pub const HEADER_LEN: usize = 80;

const MAX_TRANSFER_M: f64 = 400.0;
const WALK_SPEED_M_S: f64 = 1.33;
const CELL_DEG: f64 = 0.004; // ~450 m latitude buckets for the transfer grid
const MAX_TRANSFERS_PER_STOP: usize = 16;

/// Spatial-grid cell size for the on-device nearest/bbox index (degrees).
/// ~2.2 km at the equator; access/egress (≤1 km) searches ±1 cell.
const GRID_CELL_DEG: f64 = 0.02;

// --- varint (unsigned LEB128) ---

fn write_uvarint(v: &mut Vec<u8>, mut x: u64) {
    loop {
        let b = (x & 0x7f) as u8;
        x >>= 7;
        if x != 0 {
            v.push(b | 0x80);
        } else {
            v.push(b);
            break;
        }
    }
}

/// Interning string pool producing byte offsets into the STRINGS section.
struct StringPool {
    bytes: Vec<u8>,
    map: HashMap<String, u32>,
}

impl StringPool {
    fn new() -> StringPool {
        // Byte 0 is a lone NUL so offset 0 is a valid empty string, keeping
        // NONE (0xFFFFFFFF) unambiguous.
        StringPool { bytes: vec![0], map: HashMap::new() }
    }

    fn intern(&mut self, s: &str) -> u32 {
        if s.is_empty() {
            return NONE;
        }
        if let Some(&off) = self.map.get(s) {
            return off;
        }
        let off = self.bytes.len() as u32;
        self.bytes.extend_from_slice(s.as_bytes());
        self.bytes.push(0);
        self.map.insert(s.to_string(), off);
        off
    }
}

struct StopTime {
    stop_idx: u32,
    seq: i64,
    arr: u32,
    dep: u32,
}

/// One input GTFS feed (already parsed). Multiple feeds merge into one pack;
/// their GTFS ids are namespaced by feed so they never collide.
pub struct FeedInput<'a> {
    pub name: String,
    pub stops: &'a Csv,
    pub routes: &'a Csv,
    pub trips: &'a Csv,
    pub stop_times: &'a Csv,
    pub calendar: Option<&'a Csv>,
    pub calendar_dates: Option<&'a Csv>,
}

/// Summary counts reported to the caller / manifest.
pub struct BuildStats {
    pub stops: usize,
    pub routes: usize,
    pub trips: usize,
    pub profiles: usize,
    pub feeds: usize,
    pub transfers: usize,
    pub min_lat_e7: i32,
    pub min_lon_e7: i32,
    pub max_lat_e7: i32,
    pub max_lon_e7: i32,
    pub size_bytes: usize,
    /// (section name, byte length) for the size-breakdown report.
    pub section_sizes: Vec<(&'static str, usize)>,
}

fn append_u32(v: &mut Vec<u8>, x: u32) {
    v.extend_from_slice(&x.to_le_bytes());
}
fn append_i32(v: &mut Vec<u8>, x: i32) {
    v.extend_from_slice(&x.to_le_bytes());
}

/// Approximate ground distance in metres (equirectangular; fine at footpath
/// range).
fn dist_m(lat1_e7: i32, lon1_e7: i32, lat2_e7: i32, lon2_e7: i32) -> f64 {
    let lat1 = lat1_e7 as f64 * 1e-7;
    let lat2 = lat2_e7 as f64 * 1e-7;
    let dlat = (lat2 - lat1) * 111_320.0;
    let mean = ((lat1 + lat2) * 0.5).to_radians();
    let dlon = (lon2_e7 as f64 * 1e-7 - lon1_e7 as f64 * 1e-7) * 111_320.0 * mean.cos();
    (dlat * dlat + dlon * dlon).sqrt()
}

/// A RAPTOR route: trips grouped by identical (feed, gtfs route, stop pattern).
struct RaptorRoute {
    feed_idx: u32,
    name_off: u32,
    color: u32,
    route_type: u32,
    stop_pattern: Vec<u32>,
    trips: Vec<usize>, // indices into `built_trips`
}

/// A trip reduced to its start time + run-time shape, ready for profile dedup.
struct BuiltTrip {
    service_idx: u32,
    headsign_off: u32,
    start_time: u32,             // departure at the first stop
    stoptimes: Vec<(u32, u32)>,  // (arr, dep) aligned to the route's stop_pattern
}

/// Register a service id that appears (e.g. only in calendar_dates), returning
/// its global index. Feed-local ids are namespaced by the caller.
fn ensure_service(
    sid: &str,
    map: &mut HashMap<String, u32>,
    mask: &mut Vec<u8>,
    start: &mut Vec<u32>,
    end: &mut Vec<u32>,
) -> u32 {
    if let Some(&i) = map.get(sid) {
        return i;
    }
    let i = mask.len() as u32;
    map.insert(sid.to_string(), i);
    mask.push(0);
    start.push(0);
    end.push(99_999_999);
    i
}

/// Build the full index blob from one or more GTFS feeds. `pack_name` is stored
/// in the string pool and surfaced to the on-device planner as a fallback.
pub fn build_index(
    pack_name: &str,
    feeds: &[FeedInput],
) -> Result<(Vec<u8>, BuildStats), String> {
    let mut pool = StringPool::new();
    let feed_name_off = pool.intern(pack_name);

    // --- Global (merged) accumulators ---
    let mut stop_lat: Vec<i32> = Vec::new();
    let mut stop_lon: Vec<i32> = Vec::new();
    let mut stop_name_off: Vec<u32> = Vec::new();
    let mut stop_code_off: Vec<u32> = Vec::new();
    let (mut min_lat, mut min_lon) = (i32::MAX, i32::MAX);
    let (mut max_lat, mut max_lon) = (i32::MIN, i32::MIN);

    // Global services (namespaced by feed) + exceptions (global service idx).
    let mut service_key_to_idx: HashMap<String, u32> = HashMap::new();
    let mut svc_mask: Vec<u8> = Vec::new();
    let mut svc_start: Vec<u32> = Vec::new();
    let mut svc_end: Vec<u32> = Vec::new();
    let mut exceptions: Vec<(u32, u32, u32)> = Vec::new();

    let mut feed_name_offs: Vec<u32> = Vec::new();
    let mut raptor_routes: Vec<RaptorRoute> = Vec::new();
    let mut built_trips: Vec<BuiltTrip> = Vec::new();

    for feed in feeds {
        let feed_idx = feed_name_offs.len() as u32;
        feed_name_offs.push(pool.intern(&feed.name));

        // --- Stops (this feed) -> global indices ---
        let mut stop_id_to_idx: HashMap<String, u32> = HashMap::new();
        for row in &feed.stops.rows {
            let id = feed.stops.get(row, "stop_id");
            if id.is_empty() {
                continue;
            }
            let lat: f64 = feed.stops.get(row, "stop_lat").trim().parse().unwrap_or(f64::NAN);
            let lon: f64 = feed.stops.get(row, "stop_lon").trim().parse().unwrap_or(f64::NAN);
            if lat.is_nan() || lon.is_nan() {
                continue;
            }
            let lat_e7 = (lat * 1e7) as i32;
            let lon_e7 = (lon * 1e7) as i32;
            let name = feed.stops.get(row, "stop_name");
            let code = feed.stops.get(row, "stop_code");
            let idx = stop_lat.len() as u32;
            stop_id_to_idx.insert(id.to_string(), idx);
            stop_lat.push(lat_e7);
            stop_lon.push(lon_e7);
            stop_name_off.push(pool.intern(name));
            stop_code_off.push(pool.intern(if code.is_empty() { id } else { code }));
            min_lat = min_lat.min(lat_e7);
            min_lon = min_lon.min(lon_e7);
            max_lat = max_lat.max(lat_e7);
            max_lon = max_lon.max(lon_e7);
        }

        // --- Route metadata (this feed) ---
        struct RouteMeta {
            name_off: u32,
            color: u32,
            route_type: u32,
        }
        let mut route_meta: HashMap<String, RouteMeta> = HashMap::new();
        for row in &feed.routes.rows {
            let id = feed.routes.get(row, "route_id");
            if id.is_empty() {
                continue;
            }
            let short = feed.routes.get(row, "route_short_name");
            let long = feed.routes.get(row, "route_long_name");
            let name = if !short.is_empty() { short } else { long };
            let color =
                u32::from_str_radix(feed.routes.get(row, "route_color").trim(), 16).unwrap_or(0);
            let rtype: u32 = feed.routes.get(row, "route_type").trim().parse().unwrap_or(3);
            route_meta.insert(
                id.to_string(),
                RouteMeta { name_off: pool.intern(name), color, route_type: rtype },
            );
        }

        // --- Trips (this feed) ---
        struct TripMeta {
            route_id: String,
            service_id: String,
            headsign_off: u32,
        }
        let mut trip_meta: HashMap<String, TripMeta> = HashMap::new();
        for row in &feed.trips.rows {
            let id = feed.trips.get(row, "trip_id");
            if id.is_empty() {
                continue;
            }
            let headsign = feed.trips.get(row, "trip_headsign");
            trip_meta.insert(
                id.to_string(),
                TripMeta {
                    route_id: feed.trips.get(row, "route_id").to_string(),
                    service_id: feed.trips.get(row, "service_id").to_string(),
                    headsign_off: pool.intern(headsign),
                },
            );
        }

        // --- stop_times grouped by trip (this feed) ---
        let mut trip_stoptimes: HashMap<String, Vec<StopTime>> = HashMap::new();
        for row in &feed.stop_times.rows {
            let trip_id = feed.stop_times.get(row, "trip_id");
            if trip_id.is_empty() {
                continue;
            }
            let stop_id = feed.stop_times.get(row, "stop_id");
            let stop_idx = match stop_id_to_idx.get(stop_id) {
                Some(&i) => i,
                None => continue,
            };
            let seq: i64 = feed.stop_times.get(row, "stop_sequence").trim().parse().unwrap_or(0);
            let dep = parse_gtfs_time(feed.stop_times.get(row, "departure_time"));
            let arr = parse_gtfs_time(feed.stop_times.get(row, "arrival_time"));
            let (arr, dep) = match (arr, dep) {
                (Some(a), Some(d)) => (a, d),
                (Some(a), None) => (a, a),
                (None, Some(d)) => (d, d),
                (None, None) => continue,
            };
            trip_stoptimes
                .entry(trip_id.to_string())
                .or_default()
                .push(StopTime { stop_idx, seq, arr, dep });
        }

        // --- Services (calendar.txt) namespaced by feed ---
        let feed_key = |id: &str| format!("{}|{}", feed.name, id);
        if let Some(cal) = feed.calendar {
            let days = [
                "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            ];
            for row in &cal.rows {
                let id = cal.get(row, "service_id");
                if id.is_empty() {
                    continue;
                }
                let key = feed_key(id);
                if service_key_to_idx.contains_key(&key) {
                    continue;
                }
                let mut mask = 0u8;
                for (b, d) in days.iter().enumerate() {
                    if cal.get(row, d).trim() == "1" {
                        mask |= 1 << b;
                    }
                }
                let idx = svc_mask.len() as u32;
                service_key_to_idx.insert(key, idx);
                svc_mask.push(mask);
                svc_start.push(parse_gtfs_date(cal.get(row, "start_date")).unwrap_or(0));
                svc_end.push(parse_gtfs_date(cal.get(row, "end_date")).unwrap_or(99_999_999));
            }
        }

        // --- Exceptions (calendar_dates.txt) ---
        if let Some(cd) = feed.calendar_dates {
            for row in &cd.rows {
                let sid = cd.get(row, "service_id");
                if sid.is_empty() {
                    continue;
                }
                let date = match parse_gtfs_date(cd.get(row, "date")) {
                    Some(d) => d,
                    None => continue,
                };
                let added = if cd.get(row, "exception_type").trim() == "1" { 1 } else { 0 };
                let sidx = ensure_service(
                    &feed_key(sid),
                    &mut service_key_to_idx,
                    &mut svc_mask,
                    &mut svc_start,
                    &mut svc_end,
                );
                exceptions.push((sidx, date, added));
            }
        }

        // --- Group this feed's trips into RAPTOR routes ---
        let mut pattern_key_to_route: HashMap<String, usize> = HashMap::new();
        for (trip_id, mut sts) in trip_stoptimes {
            if sts.len() < 2 {
                continue;
            }
            sts.sort_by_key(|s| s.seq);
            let pattern: Vec<u32> = sts.iter().map(|s| s.stop_idx).collect();
            let tmeta = match trip_meta.get(&trip_id) {
                Some(m) => m,
                None => continue,
            };
            let service_idx = ensure_service(
                &feed_key(&tmeta.service_id),
                &mut service_key_to_idx,
                &mut svc_mask,
                &mut svc_start,
                &mut svc_end,
            );
            let key = format!(
                "{}|{}",
                tmeta.route_id,
                pattern.iter().map(|s| s.to_string()).collect::<Vec<_>>().join(",")
            );
            let route_idx = *pattern_key_to_route.entry(key).or_insert_with(|| {
                let (name_off, color, rtype) = match route_meta.get(&tmeta.route_id) {
                    Some(m) => (m.name_off, m.color, m.route_type),
                    None => (NONE, 0, 3),
                };
                raptor_routes.push(RaptorRoute {
                    feed_idx,
                    name_off,
                    color,
                    route_type: rtype,
                    stop_pattern: pattern.clone(),
                    trips: Vec::new(),
                });
                raptor_routes.len() - 1
            });
            let bt = BuiltTrip {
                service_idx,
                headsign_off: tmeta.headsign_off,
                start_time: sts.first().map(|s| s.dep).unwrap_or(0),
                stoptimes: sts.iter().map(|s| (s.arr, s.dep)).collect(),
            };
            let ti = built_trips.len();
            built_trips.push(bt);
            raptor_routes[route_idx].trips.push(ti);
        }
    }

    let stop_count = stop_lat.len();
    if stop_count == 0 {
        return Err("no usable stops in any feed".to_string());
    }

    // --- Profiles: dedup run-time shapes into a varint table ---
    let mut profile_bytes: Vec<u8> = Vec::new();
    let mut profile_offsets: Vec<u32> = Vec::new(); // byte offset per profile id
    let mut profile_key_to_id: HashMap<Vec<u8>, u32> = HashMap::new();
    // Encode a trip's (arr,dep) sequence into a profile body; return its id.
    let mut intern_profile = |sts: &[(u32, u32)]| -> u32 {
        let n = sts.len();
        let mut body: Vec<u8> = Vec::new();
        write_uvarint(&mut body, n as u64);
        let (arr0, dep0) = sts[0];
        let dwell0 = dep0.saturating_sub(arr0);
        write_uvarint(&mut body, dwell0 as u64);
        let mut prev_dep = dep0;
        for k in 1..n {
            let (arr, dep) = sts[k];
            let hop = arr.saturating_sub(prev_dep);
            let dwell = dep.saturating_sub(arr);
            write_uvarint(&mut body, hop as u64);
            write_uvarint(&mut body, dwell as u64);
            prev_dep = dep;
        }
        if let Some(&id) = profile_key_to_id.get(&body) {
            return id;
        }
        let id = profile_offsets.len() as u32;
        profile_offsets.push(profile_bytes.len() as u32);
        profile_bytes.extend_from_slice(&body);
        profile_key_to_id.insert(body, id);
        id
    };

    // --- Flatten stops ---
    let mut sec_stops = Vec::with_capacity(stop_count * 16);
    for i in 0..stop_count {
        append_i32(&mut sec_stops, stop_lat[i]);
        append_i32(&mut sec_stops, stop_lon[i]);
        append_u32(&mut sec_stops, stop_name_off[i]);
        append_u32(&mut sec_stops, stop_code_off[i]);
    }

    // --- Routes + route_stops + route_trips (+ profiles) ---
    let mut sec_routes = Vec::new();
    let mut sec_route_stops = Vec::new();
    let mut sec_route_trips = Vec::new();
    let mut stop_routes: Vec<Vec<u32>> = vec![Vec::new(); stop_count];

    let mut trip_total: usize = 0;
    let route_count = raptor_routes.len();
    for (ridx, rr) in raptor_routes.iter().enumerate() {
        let first_route_stop = (sec_route_stops.len() / 4) as u32;
        for &s in &rr.stop_pattern {
            append_u32(&mut sec_route_stops, s);
            let rv = &mut stop_routes[s as usize];
            if rv.last() != Some(&(ridx as u32)) && !rv.contains(&(ridx as u32)) {
                rv.push(ridx as u32);
            }
        }
        let n_stops = rr.stop_pattern.len() as u32;

        // Sort this route's trips by first-stop departure, then varint-pack.
        let mut trip_order = rr.trips.clone();
        trip_order.sort_by_key(|&ti| built_trips[ti].start_time);
        let trips_off = sec_route_trips.len() as u32;
        let mut prev_start: u32 = 0;
        for &ti in &trip_order {
            let bt = &built_trips[ti];
            let profile_id = intern_profile(&bt.stoptimes);
            let start_delta = bt.start_time.saturating_sub(prev_start);
            write_uvarint(&mut sec_route_trips, start_delta as u64);
            write_uvarint(&mut sec_route_trips, profile_id as u64);
            write_uvarint(&mut sec_route_trips, bt.service_idx as u64);
            write_uvarint(&mut sec_route_trips, bt.headsign_off as u64);
            prev_start = bt.start_time;
            trip_total += 1;
        }
        let n_trips = trip_order.len() as u32;

        append_u32(&mut sec_routes, rr.name_off);
        append_u32(&mut sec_routes, rr.color);
        append_u32(&mut sec_routes, rr.route_type);
        append_u32(&mut sec_routes, rr.feed_idx);
        append_u32(&mut sec_routes, n_stops);
        append_u32(&mut sec_routes, first_route_stop);
        append_u32(&mut sec_routes, n_trips);
        append_u32(&mut sec_routes, trips_off);
    }

    // Profiles index (byte offset per id, plus terminating length).
    let mut sec_profiles_idx = Vec::new();
    for &off in &profile_offsets {
        append_u32(&mut sec_profiles_idx, off);
    }
    append_u32(&mut sec_profiles_idx, profile_bytes.len() as u32);
    let profile_count = profile_offsets.len();

    // --- stop_routes flattened + index ---
    let mut sec_stop_routes = Vec::new();
    let mut sec_stop_routes_idx = Vec::new();
    let mut acc: u32 = 0;
    for sr in &stop_routes {
        append_u32(&mut sec_stop_routes_idx, acc);
        for &r in sr {
            append_u32(&mut sec_stop_routes, r);
        }
        acc += sr.len() as u32;
    }
    append_u32(&mut sec_stop_routes_idx, acc);

    // --- Footpath transfers via a coarse spatial grid (cross-feed for free) ---
    let mut xgrid: HashMap<(i32, i32), Vec<u32>> = HashMap::new();
    let xcell = |lat_e7: i32, lon_e7: i32| -> (i32, i32) {
        (
            (lat_e7 as f64 * 1e-7 / CELL_DEG).floor() as i32,
            (lon_e7 as f64 * 1e-7 / CELL_DEG).floor() as i32,
        )
    };
    for i in 0..stop_count {
        xgrid.entry(xcell(stop_lat[i], stop_lon[i])).or_default().push(i as u32);
    }
    let mut transfer_total = 0usize;
    let mut sec_transfers = Vec::new();
    let mut sec_transfers_idx = Vec::new();
    let mut tacc: u32 = 0;
    for i in 0..stop_count {
        append_u32(&mut sec_transfers_idx, tacc);
        let (cx, cy) = xcell(stop_lat[i], stop_lon[i]);
        let mut cand: Vec<(u32, f64)> = Vec::new();
        for dx in -1..=1 {
            for dy in -1..=1 {
                if let Some(bucket) = xgrid.get(&(cx + dx, cy + dy)) {
                    for &j in bucket {
                        if j as usize == i {
                            continue;
                        }
                        let d = dist_m(
                            stop_lat[i],
                            stop_lon[i],
                            stop_lat[j as usize],
                            stop_lon[j as usize],
                        );
                        if d <= MAX_TRANSFER_M {
                            cand.push((j, d));
                        }
                    }
                }
            }
        }
        cand.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal));
        cand.truncate(MAX_TRANSFERS_PER_STOP);
        for (j, d) in cand {
            let secs = (d / WALK_SPEED_M_S).ceil() as u32;
            append_u32(&mut sec_transfers, j);
            append_u32(&mut sec_transfers, secs);
            tacc += 1;
            transfer_total += 1;
        }
    }
    append_u32(&mut sec_transfers_idx, tacc);

    // --- Services + exceptions ---
    let mut sec_services = Vec::new();
    for i in 0..svc_mask.len() {
        sec_services.push(svc_mask[i]);
        sec_services.extend_from_slice(&[0u8, 0, 0]);
        append_u32(&mut sec_services, svc_start[i]);
        append_u32(&mut sec_services, svc_end[i]);
    }
    let mut sec_exceptions = Vec::new();
    for &(sidx, date, added) in &exceptions {
        append_u32(&mut sec_exceptions, sidx);
        append_u32(&mut sec_exceptions, date);
        append_u32(&mut sec_exceptions, added);
    }

    // --- Spatial grid (sparse CSR keyed by cell id) ---
    let grid_lat0 = min_lat;
    let grid_lon0 = min_lon;
    let grid_cell_e7 = (GRID_CELL_DEG * 1e7) as u32;
    let cell_col = |lon_e7: i32| -> i64 {
        ((lon_e7 as i64 - grid_lon0 as i64) / grid_cell_e7 as i64).max(0)
    };
    let cell_row = |lat_e7: i32| -> i64 {
        ((lat_e7 as i64 - grid_lat0 as i64) / grid_cell_e7 as i64).max(0)
    };
    let grid_cols = (cell_col(max_lon) + 1) as u32;
    let grid_rows = (cell_row(max_lat) + 1) as u32;
    let mut grid: HashMap<u32, Vec<u32>> = HashMap::new();
    for i in 0..stop_count {
        let row = cell_row(stop_lat[i]) as u32;
        let col = cell_col(stop_lon[i]) as u32;
        let cell_id = row * grid_cols + col;
        grid.entry(cell_id).or_default().push(i as u32);
    }
    let mut cell_ids: Vec<u32> = grid.keys().copied().collect();
    cell_ids.sort_unstable();
    let mut sec_grid_cell_ids = Vec::with_capacity(cell_ids.len() * 4);
    let mut sec_grid_cell_off = Vec::with_capacity((cell_ids.len() + 1) * 4);
    let mut sec_grid_stops = Vec::with_capacity(stop_count * 4);
    let mut gacc: u32 = 0;
    for &cid in &cell_ids {
        append_u32(&mut sec_grid_cell_ids, cid);
        append_u32(&mut sec_grid_cell_off, gacc);
        for &s in &grid[&cid] {
            append_u32(&mut sec_grid_stops, s);
        }
        gacc += grid[&cid].len() as u32;
    }
    append_u32(&mut sec_grid_cell_off, gacc);
    let grid_cell_count = cell_ids.len();

    // --- Feeds ---
    let mut sec_feeds = Vec::with_capacity(feed_name_offs.len() * 4);
    for &off in &feed_name_offs {
        append_u32(&mut sec_feeds, off);
    }

    // --- Assemble file: header + directory + aligned sections ---
    let section_names: [&'static str; SECTION_COUNT] = [
        "STRINGS",
        "STOPS",
        "ROUTES",
        "ROUTE_STOPS",
        "ROUTE_TRIPS",
        "PROFILES",
        "PROFILES_IDX",
        "STOP_ROUTES",
        "STOP_ROUTES_IDX",
        "TRANSFERS",
        "TRANSFERS_IDX",
        "SERVICES",
        "EXCEPTIONS",
        "GRID_CELL_IDS",
        "GRID_CELL_OFF",
        "GRID_STOPS",
        "FEEDS",
    ];
    let sections: [&[u8]; SECTION_COUNT] = [
        &pool.bytes,
        &sec_stops,
        &sec_routes,
        &sec_route_stops,
        &sec_route_trips,
        &profile_bytes,
        &sec_profiles_idx,
        &sec_stop_routes,
        &sec_stop_routes_idx,
        &sec_transfers,
        &sec_transfers_idx,
        &sec_services,
        &sec_exceptions,
        &sec_grid_cell_ids,
        &sec_grid_cell_off,
        &sec_grid_stops,
        &sec_feeds,
    ];

    let dir_len = SECTION_COUNT * 16;
    let mut data_off = HEADER_LEN + dir_len;
    let align = |o: usize| (o + 7) & !7;
    let mut dir: Vec<(u64, u64)> = Vec::with_capacity(SECTION_COUNT);
    for s in sections.iter() {
        data_off = align(data_off);
        dir.push((data_off as u64, s.len() as u64));
        data_off += s.len();
    }
    let total = data_off;

    let mut out = Vec::with_capacity(total);
    append_u32(&mut out, MAGIC);
    append_u32(&mut out, VERSION);
    append_u32(&mut out, SECTION_COUNT as u32);
    append_u32(&mut out, stop_count as u32);
    append_u32(&mut out, route_count as u32);
    append_u32(&mut out, trip_total as u32);
    append_u32(&mut out, svc_mask.len() as u32);
    append_u32(&mut out, profile_count as u32);
    append_u32(&mut out, feed_name_offs.len() as u32);
    append_u32(&mut out, grid_cell_count as u32);
    append_u32(&mut out, feed_name_off);
    append_i32(&mut out, min_lat);
    append_i32(&mut out, min_lon);
    append_i32(&mut out, max_lat);
    append_i32(&mut out, max_lon);
    append_i32(&mut out, grid_lat0);
    append_i32(&mut out, grid_lon0);
    append_u32(&mut out, grid_cell_e7);
    append_u32(&mut out, grid_cols);
    append_u32(&mut out, grid_rows);
    debug_assert_eq!(out.len(), HEADER_LEN);
    for (off, len) in &dir {
        out.extend_from_slice(&off.to_le_bytes());
        out.extend_from_slice(&len.to_le_bytes());
    }
    for s in sections.iter() {
        let pad = align(out.len()) - out.len();
        out.extend(std::iter::repeat(0u8).take(pad));
        out.extend_from_slice(s);
    }

    let section_sizes: Vec<(&'static str, usize)> =
        section_names.iter().zip(sections.iter()).map(|(&n, s)| (n, s.len())).collect();

    let stats = BuildStats {
        stops: stop_count,
        routes: route_count,
        trips: trip_total,
        profiles: profile_count,
        feeds: feed_name_offs.len(),
        transfers: transfer_total,
        min_lat_e7: min_lat,
        min_lon_e7: min_lon,
        max_lat_e7: max_lat,
        max_lon_e7: max_lon,
        size_bytes: out.len(),
        section_sizes,
    };
    Ok((out, stats))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gtfs::parse_csv;

    // --- Minimal TRX2 reader mirroring maps/src/main/rust/src/transit.rs ---
    struct Reader {
        buf: Vec<u8>,
        sec: Vec<(usize, usize)>,
    }
    fn ru32(b: &[u8], off: usize) -> u32 {
        u32::from_le_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
    }
    fn ri32(b: &[u8], off: usize) -> i32 {
        i32::from_le_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
    }
    fn ru64(b: &[u8], off: usize) -> u64 {
        let mut a = [0u8; 8];
        a.copy_from_slice(&b[off..off + 8]);
        u64::from_le_bytes(a)
    }
    fn read_uvarint(b: &[u8], pos: &mut usize) -> u64 {
        let mut result = 0u64;
        let mut shift = 0;
        loop {
            let byte = b[*pos];
            *pos += 1;
            result |= ((byte & 0x7f) as u64) << shift;
            if byte & 0x80 == 0 {
                break;
            }
            shift += 7;
        }
        result
    }
    impl Reader {
        fn new(buf: Vec<u8>) -> Reader {
            let sc = ru32(&buf, 8) as usize;
            let mut sec = Vec::new();
            for i in 0..sc {
                let base = HEADER_LEN + i * 16;
                sec.push((ru64(&buf, base) as usize, ru64(&buf, base + 8) as usize));
            }
            Reader { buf, sec }
        }
        fn h(&self, idx: usize) -> u32 {
            ru32(&self.buf, idx * 4)
        }
        fn sec_bytes(&self, s: usize) -> &[u8] {
            let (o, l) = self.sec[s];
            &self.buf[o..o + l]
        }
        fn read_str(&self, off: u32) -> String {
            if off == NONE {
                return String::new();
            }
            let s = self.sec_bytes(0);
            let start = off as usize;
            let mut n = 0;
            while start + n < s.len() && s[start + n] != 0 {
                n += 1;
            }
            String::from_utf8_lossy(&s[start..start + n]).into_owned()
        }
        fn stop_ll(&self, i: u32) -> (f64, f64) {
            let s = self.sec_bytes(1);
            let base = i as usize * 16;
            (ri32(s, base) as f64 * 1e-7, ri32(s, base + 4) as f64 * 1e-7)
        }
        // RouteRec fields
        fn route(&self, r: u32) -> [u32; 8] {
            let s = self.sec_bytes(2);
            let base = r as usize * 32;
            let mut out = [0u32; 8];
            for (k, o) in out.iter_mut().enumerate() {
                *o = ru32(s, base + k * 4);
            }
            out
        }
        fn route_stop(&self, i: u32) -> u32 {
            ru32(self.sec_bytes(3), i as usize * 4)
        }
        // Decode a route's varint trips -> (start_time, profile_id, service_idx, headsign_off).
        fn route_trips(&self, rec: &[u32; 8]) -> Vec<(u32, u32, u32, u32)> {
            let n = rec[6];
            let trips_off = rec[7] as usize;
            let s = self.sec_bytes(4);
            let mut pos = trips_off;
            let mut prev = 0u32;
            let mut out = Vec::new();
            for _ in 0..n {
                let start = prev + read_uvarint(s, &mut pos) as u32;
                let pid = read_uvarint(s, &mut pos) as u32;
                let svc = read_uvarint(s, &mut pos) as u32;
                let head = read_uvarint(s, &mut pos) as u32;
                out.push((start, pid, svc, head));
                prev = start;
            }
            out
        }
        // Decode profile -> per-stop (arr_rel, dep_rel) relative to start_time.
        fn profile(&self, pid: u32) -> Vec<(i64, i64)> {
            let idx = self.sec_bytes(6);
            let off = ru32(idx, pid as usize * 4) as usize;
            let s = self.sec_bytes(5);
            let mut pos = off;
            let n = read_uvarint(s, &mut pos) as usize;
            let dwell0 = read_uvarint(s, &mut pos) as i64;
            let mut out = Vec::with_capacity(n);
            out.push((-dwell0, 0i64));
            let mut prev_dep = 0i64;
            for _ in 1..n {
                let hop = read_uvarint(s, &mut pos) as i64;
                let dwell = read_uvarint(s, &mut pos) as i64;
                let arr = prev_dep + hop;
                let dep = arr + dwell;
                out.push((arr, dep));
                prev_dep = dep;
            }
            out
        }
        fn feed_name(&self, feed_idx: u32) -> String {
            let s = self.sec_bytes(16);
            self.read_str(ru32(s, feed_idx as usize * 4))
        }
        // Sparse-grid nearest stop to (lat, lon).
        fn nearest(&self, lat: f64, lon: f64) -> Option<u32> {
            let lat0 = self.h(15) as i32; // grid_lat0_e7 (index 15)
            let lon0 = self.h(16) as i32;
            let cell = self.h(17) as i64; // grid_cell_e7
            let cols = self.h(18) as i64;
            let lat_e7 = (lat * 1e7) as i64;
            let lon_e7 = (lon * 1e7) as i64;
            let row0 = ((lat_e7 - lat0 as i64) / cell).max(0);
            let col0 = ((lon_e7 - lon0 as i64) / cell).max(0);
            let ids = self.sec_bytes(13);
            let offs = self.sec_bytes(14);
            let stops = self.sec_bytes(15);
            let n_cells = ids.len() / 4;
            let find = |cid: u32| -> Option<usize> {
                let (mut lo, mut hi) = (0usize, n_cells);
                while lo < hi {
                    let mid = (lo + hi) / 2;
                    let v = ru32(ids, mid * 4);
                    if v == cid {
                        return Some(mid);
                    } else if v < cid {
                        lo = mid + 1;
                    } else {
                        hi = mid;
                    }
                }
                None
            };
            let mut best = None;
            let mut bestd = f64::MAX;
            for dr in -1..=1 {
                for dc in -1..=1 {
                    let r = row0 + dr;
                    let c = col0 + dc;
                    if r < 0 || c < 0 || c >= cols {
                        continue;
                    }
                    let cid = (r * cols + c) as u32;
                    if let Some(ci) = find(cid) {
                        let s = ru32(offs, ci * 4) as usize;
                        let e = ru32(offs, (ci + 1) * 4) as usize;
                        for k in s..e {
                            let sid = ru32(stops, k * 4);
                            let (slat, slon) = self.stop_ll(sid);
                            let d = (slat - lat).powi(2) + (slon - lon).powi(2);
                            if d < bestd {
                                bestd = d;
                                best = Some(sid);
                            }
                        }
                    }
                }
            }
            best
        }
    }

    fn feed_a() -> (Csv, Csv, Csv, Csv, Csv) {
        let stops = parse_csv(
            "stop_id,stop_name,stop_lat,stop_lon,stop_code\n\
             S1,Alpha,37.70,-122.40,A1\n\
             S2,Beta,37.71,-122.41,B2\n\
             S3,Gamma,37.72,-122.42,C3\n",
        );
        let routes = parse_csv(
            "route_id,route_short_name,route_long_name,route_type,route_color\n\
             R1,N,Judah,0,0000FF\n",
        );
        let trips = parse_csv(
            "route_id,service_id,trip_id,trip_headsign\n\
             R1,WK,T1,Downtown\n\
             R1,WK,T2,Downtown\n",
        );
        let stop_times = parse_csv(
            "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n\
             T1,S1,1,08:00:00,08:00:00\n\
             T1,S2,2,08:05:00,08:06:00\n\
             T1,S3,3,08:10:00,08:10:00\n\
             T2,S1,1,09:00:00,09:00:00\n\
             T2,S2,2,09:05:00,09:06:00\n\
             T2,S3,3,09:10:00,09:10:00\n",
        );
        let calendar = parse_csv(
            "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n\
             WK,1,1,1,1,1,0,0,20240101,20241231\n",
        );
        (stops, routes, trips, stop_times, calendar)
    }

    fn feed_b() -> (Csv, Csv, Csv, Csv, Csv) {
        let stops = parse_csv(
            "stop_id,stop_name,stop_lat,stop_lon,stop_code\n\
             P1,East1,37.80,-122.27,E1\n\
             P2,East2,37.81,-122.26,E2\n",
        );
        let routes = parse_csv(
            "route_id,route_short_name,route_long_name,route_type,route_color\n\
             RB,51,Line 51,3,\n",
        );
        let trips = parse_csv(
            "route_id,service_id,trip_id,trip_headsign\n\
             RB,WK,TB1,Loop\n",
        );
        let stop_times = parse_csv(
            "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n\
             TB1,P1,1,07:00:00,07:00:00\n\
             TB1,P2,2,07:20:00,07:20:00\n",
        );
        let calendar = parse_csv(
            "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n\
             WK,1,1,1,1,1,0,0,20240101,20241231\n",
        );
        (stops, routes, trips, stop_times, calendar)
    }

    #[test]
    fn merges_two_feeds_and_roundtrips_profiles() {
        let (as_, ar, at, ast, ac) = feed_a();
        let (bs, br, bt, bst, bc) = feed_b();
        let feeds = vec![
            FeedInput {
                name: "sfmuni".to_string(),
                stops: &as_,
                routes: &ar,
                trips: &at,
                stop_times: &ast,
                calendar: Some(&ac),
                calendar_dates: None,
            },
            FeedInput {
                name: "actransit".to_string(),
                stops: &bs,
                routes: &br,
                trips: &bt,
                stop_times: &bst,
                calendar: Some(&bc),
                calendar_dates: None,
            },
        ];
        let (blob, stats) = build_index("world", &feeds).expect("build");
        let r = Reader::new(blob);

        // Header sanity.
        assert_eq!(r.h(0), MAGIC);
        assert_eq!(r.h(1), VERSION);
        assert_eq!(r.h(2), SECTION_COUNT as u32);
        assert_eq!(r.h(3), 5, "stop_count"); // 3 + 2
        assert_eq!(r.h(4), 2, "route_count");
        assert_eq!(r.h(5), 3, "trip_count"); // 2 + 1
        assert_eq!(r.h(8), 2, "feed_count");
        // Feed A's two trips share one shape; feed B has its own -> 2 profiles.
        assert_eq!(r.h(7), 2, "profile_count");
        assert_eq!(stats.profiles, 2);

        // Feeds table namespacing.
        assert_eq!(r.feed_name(0), "sfmuni");
        assert_eq!(r.feed_name(1), "actransit");

        // Route 0 = feed A's N-Judah. Decode its trips + profile.
        let rec0 = r.route(0);
        assert_eq!(rec0[3], 0, "route0 feed_idx");
        assert_eq!(rec0[4], 3, "route0 n_stops");
        assert_eq!(r.read_str(rec0[0]), "N");
        let trips = r.route_trips(&rec0);
        assert_eq!(trips.len(), 2);
        // Sorted by start_time: 08:00 then 09:00.
        assert_eq!(trips[0].0, 28800);
        assert_eq!(trips[1].0, 32400);
        // Both reference the same profile id (dedup).
        assert_eq!(trips[0].1, trips[1].1);

        // Reconstruct absolute arr/dep for trip T2 (start 09:00) and compare.
        let prof = r.profile(trips[1].1);
        let start = trips[1].0 as i64;
        let abs: Vec<(i64, i64)> =
            prof.iter().map(|&(a, d)| (start + a, start + d)).collect();
        assert_eq!(abs[0], (32400, 32400)); // S1 09:00/09:00
        assert_eq!(abs[1], (32700, 32760)); // S2 09:05/09:06
        assert_eq!(abs[2], (33000, 33000)); // S3 09:10/09:10

        // Route 1 belongs to feed B.
        let rec1 = r.route(1);
        assert_eq!(rec1[3], 1, "route1 feed_idx");
        assert_eq!(rec1[4], 2, "route1 n_stops");

        // Route stops of route 0 point at the first three (feed A) stops.
        for pos in 0..rec0[4] {
            let s = r.route_stop(rec0[5] + pos);
            assert!(s < 3, "route0 stop {s} should be a feed-A stop");
        }

        // Grid nearest lookups.
        assert_eq!(r.nearest(37.72, -122.42), Some(2), "Gamma");
        assert_eq!(r.nearest(37.80, -122.27), Some(3), "feed B P1");
    }
}
