//! Build the compact per-region transit index from parsed GTFS tables and
//! serialize it to the on-disk `.transit` format.
//!
//! ON-DISK FORMAT (little-endian, mmap-friendly, read via `read_unaligned` on
//! device). THIS LAYOUT MUST STAY IN SYNC WITH
//! `maps/src/main/rust/src/transit.rs`.
//!
//! Header (48 bytes):
//!   u32 magic (MAGIC), u32 version (VERSION), u32 section_count,
//!   u32 stop_count, u32 route_count, u32 trip_count, u32 service_count,
//!   u32 feed_name_off, i32 min_lat_e7, i32 min_lon_e7, i32 max_lat_e7,
//!   i32 max_lon_e7
//! Section directory: section_count * { u64 offset, u64 len } (absolute byte
//!   offset from file start, byte length). Sections are 8-byte aligned.
//!
//! Sections (index -> payload):
//!   0 STRINGS          NUL-terminated UTF-8 string pool; offsets are byte
//!                      offsets into this section, sentinel NONE = no string.
//!   1 STOPS            StopRec[stop_count]  = { i32 lat_e7, i32 lon_e7,
//!                                               u32 name_off, u32 code_off }
//!   2 ROUTES           RouteRec[route_count]= { u32 name_off, u32 color,
//!                        u32 route_type, u32 n_stops, u32 first_route_stop,
//!                        u32 n_trips, u32 first_trip, u32 _pad }
//!   3 ROUTE_STOPS      u32[] ordered stop indices; RouteRec slices it by
//!                        [first_route_stop, +n_stops].
//!   4 TRIPS            TripRec[trip_count] = { u32 route_idx, u32 service_idx,
//!                        u32 headsign_off, u32 first_stoptime }; a trip's
//!                        stop_times are STOPTIMES[first_stoptime, +n_stops].
//!   5 STOPTIMES        StopTime[] = { u32 arr_s, u32 dep_s } seconds since
//!                        service midnight (may exceed 86400).
//!   6 STOP_ROUTES      u32[] route indices serving each stop (flattened).
//!   7 STOP_ROUTES_IDX  u32[stop_count + 1] prefix offsets into STOP_ROUTES.
//!   8 TRANSFERS        Transfer[] = { u32 to_stop, u32 secs } footpaths.
//!   9 TRANSFERS_IDX    u32[stop_count + 1] prefix offsets into TRANSFERS.
//!  10 SERVICES         ServiceRec[service_count] = { u8 weekday_mask,
//!                        u8[3] _pad, u32 start_date, u32 end_date }.
//!  11 EXCEPTIONS       ExcRec[] = { u32 service_idx, u32 date, u32 added }
//!                        (added: 1 = service added on date, 0 = removed).

use crate::gtfs::{parse_gtfs_date, parse_gtfs_time, Csv};
use std::collections::HashMap;

pub const MAGIC: u32 = 0x5452_4958; // "TRIX"
pub const VERSION: u32 = 1;
pub const NONE: u32 = 0xFFFF_FFFF;
pub const SECTION_COUNT: usize = 12;

const MAX_TRANSFER_M: f64 = 400.0;
const WALK_SPEED_M_S: f64 = 1.33;
const CELL_DEG: f64 = 0.004; // ~450 m latitude buckets for the transfer grid
const MAX_TRANSFERS_PER_STOP: usize = 16;

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

/// Summary counts reported to the caller / manifest.
pub struct BuildStats {
    pub stops: usize,
    pub routes: usize,
    pub trips: usize,
    pub transfers: usize,
    pub min_lat_e7: i32,
    pub min_lon_e7: i32,
    pub max_lat_e7: i32,
    pub max_lon_e7: i32,
    pub size_bytes: usize,
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

/// Build the full index blob from the GTFS tables. `feed_name` is stored in the
/// string pool and surfaced to the on-device planner.
pub fn build_index(
    feed_name: &str,
    stops_csv: &Csv,
    routes_csv: &Csv,
    trips_csv: &Csv,
    stop_times_csv: &Csv,
    calendar_csv: Option<&Csv>,
    calendar_dates_csv: Option<&Csv>,
) -> Result<(Vec<u8>, BuildStats), String> {
    let mut pool = StringPool::new();
    let feed_name_off = pool.intern(feed_name);

    // --- Stops ---
    let mut stop_id_to_idx: HashMap<String, u32> = HashMap::new();
    let mut stop_lat: Vec<i32> = Vec::new();
    let mut stop_lon: Vec<i32> = Vec::new();
    let mut stop_name_off: Vec<u32> = Vec::new();
    let mut stop_code_off: Vec<u32> = Vec::new();
    let (mut min_lat, mut min_lon) = (i32::MAX, i32::MAX);
    let (mut max_lat, mut max_lon) = (i32::MIN, i32::MIN);

    for row in &stops_csv.rows {
        let id = stops_csv.get(row, "stop_id");
        if id.is_empty() {
            continue;
        }
        let lat: f64 = stops_csv.get(row, "stop_lat").trim().parse().unwrap_or(f64::NAN);
        let lon: f64 = stops_csv.get(row, "stop_lon").trim().parse().unwrap_or(f64::NAN);
        if lat.is_nan() || lon.is_nan() {
            continue;
        }
        let lat_e7 = (lat * 1e7) as i32;
        let lon_e7 = (lon * 1e7) as i32;
        let name = stops_csv.get(row, "stop_name");
        let code = stops_csv.get(row, "stop_code");
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
    let stop_count = stop_lat.len();
    if stop_count == 0 {
        return Err("no usable stops in feed".to_string());
    }

    // --- Route metadata (from routes.txt) ---
    struct RouteMeta {
        name_off: u32,
        color: u32,
        route_type: u32,
    }
    let mut route_meta: HashMap<String, RouteMeta> = HashMap::new();
    for row in &routes_csv.rows {
        let id = routes_csv.get(row, "route_id");
        if id.is_empty() {
            continue;
        }
        let short = routes_csv.get(row, "route_short_name");
        let long = routes_csv.get(row, "route_long_name");
        let name = if !short.is_empty() { short } else { long };
        let color = u32::from_str_radix(routes_csv.get(row, "route_color").trim(), 16).unwrap_or(0);
        let rtype: u32 = routes_csv.get(row, "route_type").trim().parse().unwrap_or(3);
        route_meta.insert(
            id.to_string(),
            RouteMeta { name_off: pool.intern(name), color, route_type: rtype },
        );
    }

    // --- Trips (route_id, service_id, headsign) ---
    struct TripMeta {
        route_id: String,
        service_id: String,
        headsign_off: u32,
    }
    let mut trip_meta: HashMap<String, TripMeta> = HashMap::new();
    for row in &trips_csv.rows {
        let id = trips_csv.get(row, "trip_id");
        if id.is_empty() {
            continue;
        }
        let headsign = trips_csv.get(row, "trip_headsign");
        trip_meta.insert(
            id.to_string(),
            TripMeta {
                route_id: trips_csv.get(row, "route_id").to_string(),
                service_id: trips_csv.get(row, "service_id").to_string(),
                headsign_off: pool.intern(headsign),
            },
        );
    }

    // --- stop_times grouped by trip ---
    let mut trip_stoptimes: HashMap<String, Vec<StopTime>> = HashMap::new();
    for row in &stop_times_csv.rows {
        let trip_id = stop_times_csv.get(row, "trip_id");
        if trip_id.is_empty() {
            continue;
        }
        let stop_id = stop_times_csv.get(row, "stop_id");
        let stop_idx = match stop_id_to_idx.get(stop_id) {
            Some(&i) => i,
            None => continue,
        };
        let seq: i64 = stop_times_csv.get(row, "stop_sequence").trim().parse().unwrap_or(0);
        let dep = parse_gtfs_time(stop_times_csv.get(row, "departure_time"));
        let arr = parse_gtfs_time(stop_times_csv.get(row, "arrival_time"));
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

    // --- Services (calendar.txt) ---
    let mut service_id_to_idx: HashMap<String, u32> = HashMap::new();
    let mut svc_mask: Vec<u8> = Vec::new();
    let mut svc_start: Vec<u32> = Vec::new();
    let mut svc_end: Vec<u32> = Vec::new();
    if let Some(cal) = calendar_csv {
        let days = [
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        ];
        for row in &cal.rows {
            let id = cal.get(row, "service_id");
            if id.is_empty() {
                continue;
            }
            let mut mask = 0u8;
            for (b, d) in days.iter().enumerate() {
                if cal.get(row, d).trim() == "1" {
                    mask |= 1 << b;
                }
            }
            let idx = svc_mask.len() as u32;
            service_id_to_idx.insert(id.to_string(), idx);
            svc_mask.push(mask);
            svc_start.push(parse_gtfs_date(cal.get(row, "start_date")).unwrap_or(0));
            svc_end.push(parse_gtfs_date(cal.get(row, "end_date")).unwrap_or(99_999_999));
        }
    }
    // Register any service_ids that appear only in calendar_dates.
    let ensure_service =
        |sid: &str,
         map: &mut HashMap<String, u32>,
         mask: &mut Vec<u8>,
         start: &mut Vec<u32>,
         end: &mut Vec<u32>| -> u32 {
            if let Some(&i) = map.get(sid) {
                return i;
            }
            let i = mask.len() as u32;
            map.insert(sid.to_string(), i);
            mask.push(0);
            start.push(0);
            end.push(99_999_999);
            i
        };

    // --- Exceptions (calendar_dates.txt) ---
    let mut exceptions: Vec<(u32, u32, u32)> = Vec::new(); // (service_idx, date, added)
    if let Some(cd) = calendar_dates_csv {
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
                sid,
                &mut service_id_to_idx,
                &mut svc_mask,
                &mut svc_start,
                &mut svc_end,
            );
            exceptions.push((sidx, date, added));
        }
    }

    // --- Group trips into RAPTOR routes by (gtfs route, ordered stop pattern) ---
    struct RaptorRoute {
        meta_route_id: String,
        stop_pattern: Vec<u32>,
        trips: Vec<usize>, // indices into `trips` below
    }
    struct BuiltTrip {
        service_idx: u32,
        headsign_off: u32,
        dep_first: u32,
        stoptimes: Vec<(u32, u32)>, // (arr, dep) aligned to stop_pattern
    }
    let mut raptor_routes: Vec<RaptorRoute> = Vec::new();
    let mut pattern_key_to_route: HashMap<String, usize> = HashMap::new();
    let mut built_trips: Vec<BuiltTrip> = Vec::new();

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
        let service_idx = match service_id_to_idx.get(&tmeta.service_id) {
            Some(&i) => i,
            None => ensure_service(
                &tmeta.service_id,
                &mut service_id_to_idx,
                &mut svc_mask,
                &mut svc_start,
                &mut svc_end,
            ),
        };
        let key = format!(
            "{}|{}",
            tmeta.route_id,
            pattern.iter().map(|s| s.to_string()).collect::<Vec<_>>().join(",")
        );
        let route_idx = *pattern_key_to_route.entry(key).or_insert_with(|| {
            raptor_routes.push(RaptorRoute {
                meta_route_id: tmeta.route_id.clone(),
                stop_pattern: pattern.clone(),
                trips: Vec::new(),
            });
            raptor_routes.len() - 1
        });
        let bt = BuiltTrip {
            service_idx,
            headsign_off: tmeta.headsign_off,
            dep_first: sts.first().map(|s| s.dep).unwrap_or(0),
            stoptimes: sts.iter().map(|s| (s.arr, s.dep)).collect(),
        };
        let ti = built_trips.len();
        built_trips.push(bt);
        raptor_routes[route_idx].trips.push(ti);
    }

    // --- Flatten into on-disk sections ---
    let mut sec_stops = Vec::new();
    for i in 0..stop_count {
        append_i32(&mut sec_stops, stop_lat[i]);
        append_i32(&mut sec_stops, stop_lon[i]);
        append_u32(&mut sec_stops, stop_name_off[i]);
        append_u32(&mut sec_stops, stop_code_off[i]);
    }

    let mut sec_routes = Vec::new();
    let mut sec_route_stops = Vec::new();
    let mut sec_trips = Vec::new();
    let mut sec_stoptimes = Vec::new();
    // stop -> set of route indices (dedup while preserving order)
    let mut stop_routes: Vec<Vec<u32>> = vec![Vec::new(); stop_count];

    let mut trip_global: u32 = 0;
    let route_count = raptor_routes.len();
    for (ridx, rr) in raptor_routes.iter().enumerate() {
        let meta = route_meta.get(&rr.meta_route_id);
        let (name_off, color, rtype) = match meta {
            Some(m) => (m.name_off, m.color, m.route_type),
            None => (NONE, 0, 3),
        };
        let first_route_stop = (sec_route_stops.len() / 4) as u32;
        for &s in &rr.stop_pattern {
            append_u32(&mut sec_route_stops, s);
            let rv = &mut stop_routes[s as usize];
            if rv.last() != Some(&(ridx as u32)) && !rv.contains(&(ridx as u32)) {
                rv.push(ridx as u32);
            }
        }
        let n_stops = rr.stop_pattern.len() as u32;

        // Sort this route's trips by departure at the first stop.
        let mut trip_order = rr.trips.clone();
        trip_order.sort_by_key(|&ti| built_trips[ti].dep_first);
        let first_trip = trip_global;
        for &ti in &trip_order {
            let bt = &built_trips[ti];
            let first_stoptime = (sec_stoptimes.len() / 8) as u32;
            for &(arr, dep) in &bt.stoptimes {
                append_u32(&mut sec_stoptimes, arr);
                append_u32(&mut sec_stoptimes, dep);
            }
            append_u32(&mut sec_trips, ridx as u32);
            append_u32(&mut sec_trips, bt.service_idx);
            append_u32(&mut sec_trips, bt.headsign_off);
            append_u32(&mut sec_trips, first_stoptime);
            trip_global += 1;
        }
        let n_trips = trip_global - first_trip;

        append_u32(&mut sec_routes, name_off);
        append_u32(&mut sec_routes, color);
        append_u32(&mut sec_routes, rtype);
        append_u32(&mut sec_routes, n_stops);
        append_u32(&mut sec_routes, first_route_stop);
        append_u32(&mut sec_routes, n_trips);
        append_u32(&mut sec_routes, first_trip);
        append_u32(&mut sec_routes, 0); // _pad
    }
    let trip_count = trip_global as usize;

    // stop_routes flattened + index
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

    // --- Footpath transfers via a coarse spatial grid ---
    let mut grid: HashMap<(i32, i32), Vec<u32>> = HashMap::new();
    let cell = |lat_e7: i32, lon_e7: i32| -> (i32, i32) {
        (
            (lat_e7 as f64 * 1e-7 / CELL_DEG).floor() as i32,
            (lon_e7 as f64 * 1e-7 / CELL_DEG).floor() as i32,
        )
    };
    for i in 0..stop_count {
        grid.entry(cell(stop_lat[i], stop_lon[i])).or_default().push(i as u32);
    }
    let mut transfers: Vec<Vec<(u32, u32)>> = vec![Vec::new(); stop_count];
    let mut transfer_total = 0usize;
    for i in 0..stop_count {
        let (cx, cy) = cell(stop_lat[i], stop_lon[i]);
        let mut cand: Vec<(u32, f64)> = Vec::new();
        for dx in -1..=1 {
            for dy in -1..=1 {
                if let Some(bucket) = grid.get(&(cx + dx, cy + dy)) {
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
            transfers[i].push((j, secs));
            transfer_total += 1;
        }
    }
    let mut sec_transfers = Vec::new();
    let mut sec_transfers_idx = Vec::new();
    let mut tacc: u32 = 0;
    for tr in &transfers {
        append_u32(&mut sec_transfers_idx, tacc);
        for &(to, secs) in tr {
            append_u32(&mut sec_transfers, to);
            append_u32(&mut sec_transfers, secs);
        }
        tacc += tr.len() as u32;
    }
    append_u32(&mut sec_transfers_idx, tacc);

    // Services + exceptions
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

    // --- Assemble file: header + directory + aligned sections ---
    let sections: [&[u8]; SECTION_COUNT] = [
        &pool.bytes,
        &sec_stops,
        &sec_routes,
        &sec_route_stops,
        &sec_trips,
        &sec_stoptimes,
        &sec_stop_routes,
        &sec_stop_routes_idx,
        &sec_transfers,
        &sec_transfers_idx,
        &sec_services,
        &sec_exceptions,
    ];

    let header_len = 48usize;
    let dir_len = SECTION_COUNT * 16;
    let mut data_off = header_len + dir_len;
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
    append_u32(&mut out, trip_count as u32);
    append_u32(&mut out, svc_mask.len() as u32);
    append_u32(&mut out, feed_name_off);
    append_i32(&mut out, min_lat);
    append_i32(&mut out, min_lon);
    append_i32(&mut out, max_lat);
    append_i32(&mut out, max_lon);
    for (off, len) in &dir {
        out.extend_from_slice(&off.to_le_bytes());
        out.extend_from_slice(&len.to_le_bytes());
    }
    for s in sections.iter() {
        let pad = align(out.len()) - out.len();
        out.extend(std::iter::repeat(0u8).take(pad));
        out.extend_from_slice(s);
    }

    let stats = BuildStats {
        stops: stop_count,
        routes: route_count,
        trips: trip_count,
        transfers: transfer_total,
        min_lat_e7: min_lat,
        min_lon_e7: min_lon,
        max_lat_e7: max_lat,
        max_lon_e7: max_lon,
        size_bytes: out.len(),
    };
    Ok((out, stats))
}
