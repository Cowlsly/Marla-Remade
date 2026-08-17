//! On-device transit index loader + RAPTOR journey planner (P11b).
//!
//! Consumes the compact per-region `.transit` index produced by the host tool
//! `scripts/maps/gtfs_ingest` (P11a). The on-disk layout is documented at the
//! top of that tool's `src/index.rs` and MUST stay in sync with the section
//! constants and record accessors here.
//!
//! The planner is transfer-aware RAPTOR (round-based, earliest-arrival with a
//! fewest-transfers tiebreak per the spike's recommendation). Access/egress use
//! a straight-line walk to nearby stops rather than the road graph, which keeps
//! transit decoupled from the road-graph merge gap (spike §1d gap 1). The road
//! A* still handles walking legs elsewhere.

use crate::graph::{read_at, MmapRegion};

// --- Format constants (mirror scripts/maps/gtfs_ingest/src/index.rs) ---
const MAGIC: u32 = 0x5452_4958; // "TRIX"
const VERSION: u32 = 1;
pub const NONE: u32 = 0xFFFF_FFFF;
const HEADER_LEN: usize = 48;

const SEC_STRINGS: usize = 0;
const SEC_STOPS: usize = 1;
const SEC_ROUTES: usize = 2;
const SEC_ROUTE_STOPS: usize = 3;
const SEC_TRIPS: usize = 4;
const SEC_STOPTIMES: usize = 5;
const SEC_STOP_ROUTES: usize = 6;
const SEC_STOP_ROUTES_IDX: usize = 7;
const SEC_TRANSFERS: usize = 8;
const SEC_TRANSFERS_IDX: usize = 9;
const SEC_SERVICES: usize = 10;
const SEC_EXCEPTIONS: usize = 11;

const WALK_SPEED_M_S: f64 = 1.33;
/// Access/egress radius: how far we will walk to the first / from the last stop.
const ACCESS_RADIUS_M: f64 = 1000.0;
const MAX_ROUNDS: usize = 6;
const SECS_PER_DAY: u32 = 24 * 3600;

// --- On-disk records (all `#[repr(C, packed)]`, read via `read_at`). ---

#[repr(C, packed)]
#[derive(Clone, Copy)]
struct StopRec {
    lat_e7: i32,
    lon_e7: i32,
    name_off: u32,
    code_off: u32,
}

#[repr(C, packed)]
#[derive(Clone, Copy)]
struct RouteRec {
    name_off: u32,
    color: u32,
    route_type: u32,
    n_stops: u32,
    first_route_stop: u32,
    n_trips: u32,
    first_trip: u32,
    _pad: u32,
}

#[repr(C, packed)]
#[derive(Clone, Copy)]
struct TripRec {
    route_idx: u32,
    service_idx: u32,
    headsign_off: u32,
    first_stoptime: u32,
}

#[repr(C, packed)]
#[derive(Clone, Copy)]
struct StopTimeRec {
    arr_s: u32,
    dep_s: u32,
}

#[repr(C, packed)]
#[derive(Clone, Copy)]
struct TransferRec {
    to_stop: u32,
    secs: u32,
}

#[repr(C, packed)]
#[derive(Clone, Copy)]
struct ServiceRec {
    weekday_mask: u8,
    _pad: [u8; 3],
    start_date: u32,
    end_date: u32,
}

#[repr(C, packed)]
#[derive(Clone, Copy)]
struct ExcRec {
    service_idx: u32,
    date: u32,
    added: u32,
}

/// A single leg of a planned journey, with owned strings ready for JNI.
pub struct TransitLeg {
    pub is_transit: bool,
    pub name: String,
    pub feed: String,
    pub from_code: String,
    pub to_code: String,
    /// GTFS trip headsign; retained for richer transit UI (not yet marshaled).
    #[allow(dead_code)]
    pub headsign: String,
    pub dep_secs: u32,
    pub arr_secs: u32,
    pub stop_count: i32,
    pub dist_m: f64,
    /// Flat `[lon, lat, lon, lat, ...]` polyline through the leg's stops.
    pub coords: Vec<f64>,
}

/// mmap'd, read-only transit index for one region/feed.
pub struct TransitIndex {
    _region: MmapRegion,
    base: *const u8,
    stop_count: u32,
    service_count: u32,
    feed_name_off: u32,
    min_lat_e7: i32,
    min_lon_e7: i32,
    max_lat_e7: i32,
    max_lon_e7: i32,
    // Section (offset, len) directory.
    sec: [(usize, usize); 12],
}

// Read-only after load; the raw pointer is sound to share.
unsafe impl Send for TransitIndex {}
unsafe impl Sync for TransitIndex {}

impl TransitIndex {
    /// Load `<base_dir>/<feed>.transit`. Returns `None` if absent or malformed.
    pub fn load(base_dir: &str, feed: &str) -> Option<TransitIndex> {
        let mut path = base_dir.to_string();
        if !path.is_empty() && !path.ends_with('/') {
            path.push('/');
        }
        path.push_str(feed);
        path.push_str(".transit");

        let region = MmapRegion::map(&path)?;
        if region.len < HEADER_LEN {
            return None;
        }
        let base = region.base();
        let magic: u32 = unsafe { read_at::<u32>(base, 0) };
        let version: u32 = unsafe { read_at::<u32>(base, 1) };
        if magic != MAGIC || version != VERSION {
            return None;
        }
        let section_count: u32 = unsafe { read_at::<u32>(base, 2) };
        if section_count as usize != 12 {
            return None;
        }
        let stop_count: u32 = unsafe { read_at::<u32>(base, 3) };
        let service_count: u32 = unsafe { read_at::<u32>(base, 6) };
        let feed_name_off: u32 = unsafe { read_at::<u32>(base, 7) };
        let min_lat_e7: i32 = unsafe { read_at::<i32>(base, 8) };
        let min_lon_e7: i32 = unsafe { read_at::<i32>(base, 9) };
        let max_lat_e7: i32 = unsafe { read_at::<i32>(base, 10) };
        let max_lon_e7: i32 = unsafe { read_at::<i32>(base, 11) };

        // Directory: 12 * (u64 offset, u64 len) starting at HEADER_LEN.
        let dir_base = unsafe { base.add(HEADER_LEN) };
        let mut sec = [(0usize, 0usize); 12];
        for (i, s) in sec.iter_mut().enumerate() {
            let off: u64 = unsafe { read_at::<u64>(dir_base, i * 2) };
            let len: u64 = unsafe { read_at::<u64>(dir_base, i * 2 + 1) };
            if off as usize + len as usize > region.len {
                return None;
            }
            *s = (off as usize, len as usize);
        }

        Some(TransitIndex {
            _region: region,
            base,
            stop_count,
            service_count,
            feed_name_off,
            min_lat_e7,
            min_lon_e7,
            max_lat_e7,
            max_lon_e7,
            sec,
        })
    }

    fn sec_ptr(&self, section: usize) -> *const u8 {
        unsafe { self.base.add(self.sec[section].0) }
    }

    fn read_str(&self, off: u32) -> String {
        if off == NONE {
            return String::new();
        }
        let (start, len) = self.sec[SEC_STRINGS];
        if off as usize >= len {
            return String::new();
        }
        unsafe {
            let p = self.base.add(start + off as usize);
            let mut n = 0usize;
            while off as usize + n < len && *p.add(n) != 0 {
                n += 1;
            }
            String::from_utf8_lossy(std::slice::from_raw_parts(p, n)).into_owned()
        }
    }

    fn stop(&self, i: u32) -> StopRec {
        unsafe { read_at::<StopRec>(self.sec_ptr(SEC_STOPS), i as usize) }
    }
    fn route(&self, i: u32) -> RouteRec {
        unsafe { read_at::<RouteRec>(self.sec_ptr(SEC_ROUTES), i as usize) }
    }
    fn route_stop(&self, i: u32) -> u32 {
        unsafe { read_at::<u32>(self.sec_ptr(SEC_ROUTE_STOPS), i as usize) }
    }
    fn trip(&self, i: u32) -> TripRec {
        unsafe { read_at::<TripRec>(self.sec_ptr(SEC_TRIPS), i as usize) }
    }
    fn stoptime(&self, i: u32) -> StopTimeRec {
        unsafe { read_at::<StopTimeRec>(self.sec_ptr(SEC_STOPTIMES), i as usize) }
    }
    fn stop_routes_range(&self, stop: u32) -> (u32, u32) {
        let idx = self.sec_ptr(SEC_STOP_ROUTES_IDX);
        let s = unsafe { read_at::<u32>(idx, stop as usize) };
        let e = unsafe { read_at::<u32>(idx, stop as usize + 1) };
        (s, e)
    }
    fn stop_route(&self, i: u32) -> u32 {
        unsafe { read_at::<u32>(self.sec_ptr(SEC_STOP_ROUTES), i as usize) }
    }
    fn transfers_range(&self, stop: u32) -> (u32, u32) {
        let idx = self.sec_ptr(SEC_TRANSFERS_IDX);
        let s = unsafe { read_at::<u32>(idx, stop as usize) };
        let e = unsafe { read_at::<u32>(idx, stop as usize + 1) };
        (s, e)
    }
    fn transfer(&self, i: u32) -> TransferRec {
        unsafe { read_at::<TransferRec>(self.sec_ptr(SEC_TRANSFERS), i as usize) }
    }
    fn service(&self, i: u32) -> ServiceRec {
        unsafe { read_at::<ServiceRec>(self.sec_ptr(SEC_SERVICES), i as usize) }
    }
    fn exception_count(&self) -> u32 {
        (self.sec[SEC_EXCEPTIONS].1 / std::mem::size_of::<ExcRec>()) as u32
    }
    fn exception(&self, i: u32) -> ExcRec {
        unsafe { read_at::<ExcRec>(self.sec_ptr(SEC_EXCEPTIONS), i as usize) }
    }

    fn stop_ll(&self, i: u32) -> (f64, f64) {
        let s = self.stop(i);
        (s.lat_e7 as f64 * 1e-7, s.lon_e7 as f64 * 1e-7)
    }

    /// True if `lat`/`lon` (degrees) lie within the feed's bounding box (with a
    /// small margin so points just outside still route via a nearby stop).
    pub fn covers(&self, lat: f64, lon: f64) -> bool {
        let m = 0.05; // ~5.5 km margin
        let lat_e7 = (lat * 1e7) as i32;
        let lon_e7 = (lon * 1e7) as i32;
        lat_e7 >= self.min_lat_e7 - (m * 1e7) as i32
            && lat_e7 <= self.max_lat_e7 + (m * 1e7) as i32
            && lon_e7 >= self.min_lon_e7 - (m * 1e7) as i32
            && lon_e7 <= self.max_lon_e7 + (m * 1e7) as i32
    }

    pub fn feed_name(&self) -> String {
        self.read_str(self.feed_name_off)
    }

    /// Whether `service_idx` runs on the query weekday/date.
    fn service_runs(&self, service_idx: u32, weekday: u32, date: u32) -> bool {
        if service_idx >= self.service_count {
            return false;
        }
        let s = self.service(service_idx);
        let start = s.start_date;
        let end = s.end_date;
        let mask = s.weekday_mask;
        let mut runs = date >= start && date <= end && (mask & (1 << (weekday & 7))) != 0;
        // Apply calendar_dates exceptions for this exact date.
        let n = self.exception_count();
        for i in 0..n {
            let e = self.exception(i);
            if e.service_idx == service_idx && e.date == date {
                runs = e.added == 1;
            }
        }
        runs
    }
}

/// Approximate ground distance in metres (equirectangular).
fn dist_m(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let dlat = (lat2 - lat1) * 111_320.0;
    let mean = ((lat1 + lat2) * 0.5).to_radians();
    let dlon = (lon2 - lon1) * 111_320.0 * mean.cos();
    (dlat * dlat + dlon * dlon).sqrt()
}

/// How a stop was first reached in a RAPTOR round, for journey reconstruction.
#[derive(Clone, Copy)]
enum Reached {
    Origin,
    /// Boarded `route` at `board_stop` on `trip`, alighting here.
    Transit { route: u32, trip: u32, board_stop: u32 },
    /// Walked from `from_stop` (footpath transfer or egress precursor).
    Walk { from_stop: u32 },
}

/// Plan an earliest-arrival transit journey between two WGS84 points departing
/// at `dep_secs` seconds since midnight on the given `weekday` (0=Mon..6=Sun)
/// and `date` (yyyymmdd). Returns the ordered legs, or `None` if unreachable.
pub fn plan(
    idx: &TransitIndex,
    from_lat: f64,
    from_lon: f64,
    to_lat: f64,
    to_lon: f64,
    dep_secs: u32,
    weekday: u32,
    date: u32,
) -> Option<Vec<TransitLeg>> {
    let n = idx.stop_count;
    if n == 0 {
        return None;
    }

    let inf = u32::MAX;
    // Best arrival time per stop (across all rounds), and per-round arrival used
    // for marking. `label` records how the best arrival was achieved.
    let mut best = vec![inf; n as usize];
    let mut label = vec![Reached::Origin; n as usize];
    let mut marked = vec![false; n as usize];

    // --- Access: walk from origin to nearby stops. ---
    let mut any_access = false;
    for s in 0..n {
        let (slat, slon) = idx.stop_ll(s);
        let d = dist_m(from_lat, from_lon, slat, slon);
        if d <= ACCESS_RADIUS_M {
            let t = dep_secs + (d / WALK_SPEED_M_S).ceil() as u32;
            if t < best[s as usize] {
                best[s as usize] = t;
                label[s as usize] = Reached::Origin;
                marked[s as usize] = true;
                any_access = true;
            }
        }
    }
    if !any_access {
        return None;
    }

    // --- Egress: precompute walk time from each stop to the destination. ---
    let mut egress: Vec<(u32, u32)> = Vec::new(); // (stop, walk_secs)
    for s in 0..n {
        let (slat, slon) = idx.stop_ll(s);
        let d = dist_m(to_lat, to_lon, slat, slon);
        if d <= ACCESS_RADIUS_M {
            egress.push((s, (d / WALK_SPEED_M_S).ceil() as u32));
        }
    }
    if egress.is_empty() {
        return None;
    }

    // Apply initial footpath transfers from the access stops (round 0 walk).
    apply_transfers(idx, &mut best, &mut label, &mut marked);

    // --- RAPTOR rounds ---
    for _round in 0..MAX_ROUNDS {
        // Collect the set of routes to scan and the earliest marked position on
        // each: route -> earliest route-stop-position index.
        let mut route_earliest: std::collections::HashMap<u32, u32> =
            std::collections::HashMap::new();
        for s in 0..n {
            if !marked[s as usize] {
                continue;
            }
            let (rs, re) = idx.stop_routes_range(s);
            for i in rs..re {
                let r = idx.stop_route(i);
                let route = idx.route(r);
                // Find position of stop s in this route's stop list.
                for pos in 0..route.n_stops {
                    if idx.route_stop(route.first_route_stop + pos) == s {
                        route_earliest
                            .entry(r)
                            .and_modify(|e| {
                                if pos < *e {
                                    *e = pos;
                                }
                            })
                            .or_insert(pos);
                        break;
                    }
                }
            }
        }
        for m in marked.iter_mut() {
            *m = false;
        }

        let mut improved = false;
        for (&r, &start_pos) in &route_earliest {
            let route = idx.route(r);
            // Current boarded trip while scanning this route forward.
            let mut cur_trip: Option<u32> = None; // global trip index
            let mut board_stop: u32 = 0;
            for pos in start_pos..route.n_stops {
                let stop = idx.route_stop(route.first_route_stop + pos);

                // If riding, relax arrival at this stop.
                if let Some(ti) = cur_trip {
                    let st = idx.stoptime(idx.trip(ti).first_stoptime + pos);
                    let arr = st.arr_s;
                    if arr < best[stop as usize] {
                        best[stop as usize] = arr;
                        label[stop as usize] = Reached::Transit {
                            route: r,
                            trip: ti,
                            board_stop,
                        };
                        marked[stop as usize] = true;
                        improved = true;
                    }
                }

                // Can we (re)board an earlier trip here given our arrival at
                // `stop` from a previous round?
                let ready = best[stop as usize];
                if ready != inf {
                    if let Some(ti) = earliest_trip(idx, &route, r, pos, ready, weekday, date) {
                        let board_here = match cur_trip {
                            None => true,
                            Some(cur) => {
                                let cur_dep = idx.stoptime(idx.trip(cur).first_stoptime + pos).dep_s;
                                let new_dep = idx.stoptime(idx.trip(ti).first_stoptime + pos).dep_s;
                                new_dep < cur_dep
                            }
                        };
                        if board_here {
                            cur_trip = Some(ti);
                            board_stop = stop;
                        }
                    }
                }
            }
        }

        // Footpath transfers from freshly improved stops.
        apply_transfers(idx, &mut best, &mut label, &mut marked);

        if !improved {
            break;
        }
    }

    // --- Pick the best destination stop (arrival + egress walk). ---
    let mut best_final = inf;
    let mut best_stop = u32::MAX;
    let mut best_walk = 0u32;
    for &(s, w) in &egress {
        if best[s as usize] == inf {
            continue;
        }
        let total = best[s as usize].saturating_add(w);
        if total < best_final {
            best_final = total;
            best_stop = s;
            best_walk = w;
        }
    }
    if best_stop == u32::MAX {
        return None;
    }

    // --- Reconstruct legs by backtracking labels. ---
    let mut legs: Vec<TransitLeg> = Vec::new();
    let mut cur = best_stop;
    let mut guard = 0;
    loop {
        guard += 1;
        if guard > (n as usize) * 4 + 16 {
            break;
        }
        match label[cur as usize] {
            Reached::Origin => break,
            Reached::Walk { from_stop } => {
                legs.push(make_walk_leg(idx, from_stop, cur));
                cur = from_stop;
            }
            Reached::Transit {
                route,
                trip,
                board_stop,
            } => {
                legs.push(make_transit_leg(idx, route, trip, board_stop, cur));
                cur = board_stop;
            }
        }
    }
    legs.reverse();

    // Prepend origin access walk and append destination egress walk.
    let first_stop = if let Some(l) = legs.first() {
        stop_of_leg_start(idx, l)
    } else {
        best_stop
    };
    let (flat, flon) = idx.stop_ll(first_stop);
    let access_d = dist_m(from_lat, from_lon, flat, flon);
    if access_d > 1.0 {
        legs.insert(
            0,
            TransitLeg {
                is_transit: false,
                name: "Walk".to_string(),
                feed: String::new(),
                from_code: String::new(),
                to_code: idx.read_str(idx.stop(first_stop).code_off),
                headsign: String::new(),
                dep_secs,
                arr_secs: best[first_stop as usize].min(dep_secs + (access_d / WALK_SPEED_M_S) as u32),
                stop_count: 0,
                dist_m: access_d,
                coords: vec![from_lon, from_lat, flon, flat],
            },
        );
    }
    let (elat, elon) = idx.stop_ll(best_stop);
    let egress_d = dist_m(to_lat, to_lon, elat, elon);
    if egress_d > 1.0 {
        legs.push(TransitLeg {
            is_transit: false,
            name: "Walk".to_string(),
            feed: String::new(),
            from_code: idx.read_str(idx.stop(best_stop).code_off),
            to_code: String::new(),
            headsign: String::new(),
            dep_secs: best[best_stop as usize],
            arr_secs: best[best_stop as usize].saturating_add(best_walk),
            stop_count: 0,
            dist_m: egress_d,
            coords: vec![elon, elat, to_lon, to_lat],
        });
    }

    if legs.is_empty() {
        return None;
    }
    Some(legs)
}

fn stop_of_leg_start(idx: &TransitIndex, leg: &TransitLeg) -> u32 {
    // Recover the leg's first stop index by matching its start coordinate.
    if leg.coords.len() < 2 {
        return 0;
    }
    let lon = leg.coords[0];
    let lat = leg.coords[1];
    let mut best = 0u32;
    let mut bestd = f64::MAX;
    for s in 0..idx.stop_count {
        let (slat, slon) = idx.stop_ll(s);
        let d = (slat - lat).abs() + (slon - lon).abs();
        if d < bestd {
            bestd = d;
            best = s;
        }
    }
    best
}

/// Relax one-hop footpath transfers from every currently-marked stop.
fn apply_transfers(
    idx: &TransitIndex,
    best: &mut [u32],
    label: &mut [Reached],
    marked: &mut [bool],
) {
    let n = idx.stop_count;
    let snapshot: Vec<u32> = (0..n).filter(|&s| marked[s as usize]).collect();
    for s in snapshot {
        let base_t = best[s as usize];
        if base_t == u32::MAX {
            continue;
        }
        let (ts, te) = idx.transfers_range(s);
        for i in ts..te {
            let tr = idx.transfer(i);
            let arr = base_t.saturating_add(tr.secs);
            if arr < best[tr.to_stop as usize] {
                best[tr.to_stop as usize] = arr;
                label[tr.to_stop as usize] = Reached::Walk { from_stop: s };
                marked[tr.to_stop as usize] = true;
            }
        }
    }
}

/// Earliest trip on `route` (global trip index) that departs stop-position `pos`
/// no earlier than `ready` seconds and whose service runs on the query day.
fn earliest_trip(
    idx: &TransitIndex,
    route: &RouteRec,
    _route_idx: u32,
    pos: u32,
    ready: u32,
    weekday: u32,
    date: u32,
) -> Option<u32> {
    let mut best_ti: Option<u32> = None;
    let mut best_dep = u32::MAX;
    for t in 0..route.n_trips {
        let ti = route.first_trip + t;
        let trip = idx.trip(ti);
        if !idx.service_runs(trip.service_idx, weekday, date) {
            continue;
        }
        let dep = idx.stoptime(trip.first_stoptime + pos).dep_s;
        if dep >= ready && dep < best_dep {
            best_dep = dep;
            best_ti = Some(ti);
        }
    }
    best_ti
}

fn make_transit_leg(
    idx: &TransitIndex,
    route_idx: u32,
    trip_idx: u32,
    board_stop: u32,
    alight_stop: u32,
) -> TransitLeg {
    let route = idx.route(route_idx);
    let trip = idx.trip(trip_idx);
    // Find board/alight positions along the route.
    let mut board_pos = 0u32;
    let mut alight_pos = 0u32;
    for pos in 0..route.n_stops {
        let s = idx.route_stop(route.first_route_stop + pos);
        if s == board_stop {
            board_pos = pos;
        }
        if s == alight_stop {
            alight_pos = pos;
        }
    }
    let dep = idx.stoptime(trip.first_stoptime + board_pos).dep_s;
    let arr = idx.stoptime(trip.first_stoptime + alight_pos).arr_s;

    let mut coords = Vec::new();
    let mut dist = 0.0;
    let mut prev: Option<(f64, f64)> = None;
    let mut count = 0i32;
    if alight_pos >= board_pos {
        for pos in board_pos..=alight_pos {
            let s = idx.route_stop(route.first_route_stop + pos);
            let (lat, lon) = idx.stop_ll(s);
            if let Some((plat, plon)) = prev {
                dist += dist_m(plat, plon, lat, lon);
            }
            prev = Some((lat, lon));
            coords.push(lon);
            coords.push(lat);
            count += 1;
        }
    }

    TransitLeg {
        is_transit: true,
        name: idx.read_str(route.name_off),
        feed: idx.feed_name(),
        from_code: idx.read_str(idx.stop(board_stop).code_off),
        to_code: idx.read_str(idx.stop(alight_stop).code_off),
        headsign: idx.read_str(trip.headsign_off),
        dep_secs: dep,
        arr_secs: arr,
        stop_count: (count - 1).max(0),
        dist_m: dist,
        coords,
    }
}

fn make_walk_leg(idx: &TransitIndex, from_stop: u32, to_stop: u32) -> TransitLeg {
    let (flat, flon) = idx.stop_ll(from_stop);
    let (tlat, tlon) = idx.stop_ll(to_stop);
    TransitLeg {
        is_transit: false,
        name: "Walk".to_string(),
        feed: String::new(),
        from_code: idx.read_str(idx.stop(from_stop).code_off),
        to_code: idx.read_str(idx.stop(to_stop).code_off),
        headsign: String::new(),
        dep_secs: 0,
        arr_secs: 0,
        stop_count: 0,
        dist_m: dist_m(flat, flon, tlat, tlon),
        coords: vec![flon, flat, tlon, tlat],
    }
}

const _: () = {
    // Compile-time assertions that on-disk record sizes match the writer.
    assert!(std::mem::size_of::<StopRec>() == 16);
    assert!(std::mem::size_of::<RouteRec>() == 32);
    assert!(std::mem::size_of::<TripRec>() == 16);
    assert!(std::mem::size_of::<StopTimeRec>() == 8);
    assert!(std::mem::size_of::<TransferRec>() == 8);
    assert!(std::mem::size_of::<ServiceRec>() == 12);
    assert!(std::mem::size_of::<ExcRec>() == 12);
    let _ = SECS_PER_DAY;
};
