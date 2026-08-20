//! On-device transit index loader + RAPTOR journey planner (P11b / world pack).
//!
//! Consumes the compact `.transit` index produced by the host tool
//! `scripts/maps/gtfs_ingest` (P11a). The on-disk layout (format v2 "TRX2") is
//! documented at the top of that tool's `src/index.rs` and MUST stay in sync
//! with the section constants and record accessors here.
//!
//! v2 exists to make a single global (world) pack feasible. Instead of an 8 B
//! `(arr,dep)` stop-time per stop *per trip* (which blew a world pack past the
//! u32 stop-time ceiling and ~10–20 GB), a trip is just `{ start_time,
//! profile_id }`, where a **profile** is the varint-delta run-time *shape*
//! (per-stop hop + dwell), deduplicated across every trip with that shape. Trips
//! are varint-packed per route; a **spatial grid** (sparse CSR) makes
//! nearest-stop / access / egress cell-local instead of O(all stops); and a
//! **FEEDS** table + per-route `feed_idx` let many agencies merge into one pack.
//!
//! The planner is transfer-aware RAPTOR (round-based, earliest-arrival). Each
//! round only processes freshly-marked stops (a marked-stop queue) rather than
//! scanning all stops, and access/egress use the grid — both required so a
//! world-sized stop set stays fast. Access/egress walk straight-line to nearby
//! stops rather than the road graph, keeping transit decoupled from the
//! road-graph merge gap. The road A* still handles walking legs elsewhere.

use crate::graph::{read_at, MmapRegion};
use std::collections::HashMap;

// --- Format constants (mirror scripts/maps/gtfs_ingest/src/index.rs) ---
const MAGIC: u32 = 0x5452_4958; // "TRIX"
const VERSION: u32 = 2;
pub const NONE: u32 = 0xFFFF_FFFF;
const HEADER_LEN: usize = 80;
const SECTION_COUNT: usize = 17;

const SEC_STRINGS: usize = 0;
const SEC_STOPS: usize = 1;
const SEC_ROUTES: usize = 2;
const SEC_ROUTE_STOPS: usize = 3;
const SEC_ROUTE_TRIPS: usize = 4;
const SEC_PROFILES: usize = 5;
const SEC_PROFILES_IDX: usize = 6;
const SEC_STOP_ROUTES: usize = 7;
const SEC_STOP_ROUTES_IDX: usize = 8;
const SEC_TRANSFERS: usize = 9;
const SEC_TRANSFERS_IDX: usize = 10;
const SEC_SERVICES: usize = 11;
const SEC_EXCEPTIONS: usize = 12;
const SEC_GRID_CELL_IDS: usize = 13;
const SEC_GRID_CELL_OFF: usize = 14;
const SEC_GRID_STOPS: usize = 15;
const SEC_FEEDS: usize = 16;

const WALK_SPEED_M_S: f64 = 1.33;
/// Access/egress radius: how far we will walk to the first / from the last stop.
const ACCESS_RADIUS_M: f64 = 1000.0;
const MAX_ROUNDS: usize = 6;
const SECS_PER_DAY: u32 = 24 * 3600;

// --- On-disk fixed records (`#[repr(C, packed)]`, read via `read_at`). ---
// Variable-length sections (ROUTE_TRIPS, PROFILES) are decoded with `uvarint`.

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
    feed_idx: u32,
    n_stops: u32,
    first_route_stop: u32,
    n_trips: u32,
    trips_off: u32,
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

/// A trip decoded from a route's varint block.
#[derive(Clone, Copy)]
struct TripDec {
    start_time: u32,
    profile_id: u32,
    service_idx: u32,
    headsign_off: u32,
}

/// A decoded run-time profile: per-stop arrival/departure offsets relative to
/// the trip's `start_time` (`dep_rel[0] == 0`, `arr_rel[0] <= 0`).
struct ProfileDec {
    arr_rel: Vec<i32>,
    dep_rel: Vec<i32>,
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

/// mmap'd, read-only transit index for one pack (may hold many merged feeds).
pub struct TransitIndex {
    _region: MmapRegion,
    base: *const u8,
    stop_count: u32,
    service_count: u32,
    feed_count: u32,
    feed_name_off: u32,
    min_lat_e7: i32,
    min_lon_e7: i32,
    max_lat_e7: i32,
    max_lon_e7: i32,
    // Grid params.
    grid_lat0_e7: i32,
    grid_lon0_e7: i32,
    grid_cell_e7: u32,
    grid_cols: u32,
    grid_cell_count: u32,
    // Section (offset, len) directory.
    sec: [(usize, usize); SECTION_COUNT],
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
        if section_count as usize != SECTION_COUNT {
            return None;
        }
        let stop_count: u32 = unsafe { read_at::<u32>(base, 3) };
        let service_count: u32 = unsafe { read_at::<u32>(base, 6) };
        let feed_count: u32 = unsafe { read_at::<u32>(base, 8) };
        let grid_cell_count: u32 = unsafe { read_at::<u32>(base, 9) };
        let feed_name_off: u32 = unsafe { read_at::<u32>(base, 10) };
        let min_lat_e7: i32 = unsafe { read_at::<i32>(base, 11) };
        let min_lon_e7: i32 = unsafe { read_at::<i32>(base, 12) };
        let max_lat_e7: i32 = unsafe { read_at::<i32>(base, 13) };
        let max_lon_e7: i32 = unsafe { read_at::<i32>(base, 14) };
        let grid_lat0_e7: i32 = unsafe { read_at::<i32>(base, 15) };
        let grid_lon0_e7: i32 = unsafe { read_at::<i32>(base, 16) };
        let grid_cell_e7: u32 = unsafe { read_at::<u32>(base, 17) };
        let grid_cols: u32 = unsafe { read_at::<u32>(base, 18) };

        // Directory: SECTION_COUNT * (u64 offset, u64 len) starting at HEADER_LEN.
        let dir_base = unsafe { base.add(HEADER_LEN) };
        let mut sec = [(0usize, 0usize); SECTION_COUNT];
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
            feed_count,
            feed_name_off,
            min_lat_e7,
            min_lon_e7,
            max_lat_e7,
            max_lon_e7,
            grid_lat0_e7,
            grid_lon0_e7,
            grid_cell_e7,
            grid_cols,
            grid_cell_count,
            sec,
        })
    }

    fn sec_ptr(&self, section: usize) -> *const u8 {
        unsafe { self.base.add(self.sec[section].0) }
    }

    /// Read an unsigned LEB128 varint from `section` at byte position `pos`,
    /// advancing it. Mirrors `write_uvarint` in the producer.
    fn uvarint(&self, section: usize, pos: &mut usize) -> u64 {
        let (off, len) = self.sec[section];
        let mut result = 0u64;
        let mut shift = 0u32;
        loop {
            if *pos >= len {
                break;
            }
            let b = unsafe { *self.base.add(off + *pos) };
            *pos += 1;
            result |= ((b & 0x7f) as u64) << shift;
            if b & 0x80 == 0 {
                break;
            }
            shift += 7;
        }
        result
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

    /// Decode a route's varint trip block into `TripDec`s (start-time order).
    fn route_trips(&self, rec: &RouteRec) -> Vec<TripDec> {
        let n = rec.n_trips;
        let mut pos = rec.trips_off as usize;
        let mut prev: u32 = 0;
        let mut out = Vec::with_capacity(n as usize);
        for _ in 0..n {
            let start = prev.wrapping_add(self.uvarint(SEC_ROUTE_TRIPS, &mut pos) as u32);
            let profile_id = self.uvarint(SEC_ROUTE_TRIPS, &mut pos) as u32;
            let service_idx = self.uvarint(SEC_ROUTE_TRIPS, &mut pos) as u32;
            let headsign_off = self.uvarint(SEC_ROUTE_TRIPS, &mut pos) as u32;
            out.push(TripDec { start_time: start, profile_id, service_idx, headsign_off });
            prev = start;
        }
        out
    }

    /// Decode profile `pid` into per-stop offsets relative to `start_time`.
    fn profile(&self, pid: u32) -> ProfileDec {
        let off = unsafe { read_at::<u32>(self.sec_ptr(SEC_PROFILES_IDX), pid as usize) } as usize;
        let mut pos = off;
        let n = self.uvarint(SEC_PROFILES, &mut pos) as usize;
        let mut arr_rel = vec![0i32; n.max(1)];
        let mut dep_rel = vec![0i32; n.max(1)];
        if n == 0 {
            return ProfileDec { arr_rel, dep_rel };
        }
        let dwell0 = self.uvarint(SEC_PROFILES, &mut pos) as i64;
        arr_rel[0] = -(dwell0 as i32);
        dep_rel[0] = 0;
        let mut prev_dep = 0i64;
        for k in 1..n {
            let hop = self.uvarint(SEC_PROFILES, &mut pos) as i64;
            let dwell = self.uvarint(SEC_PROFILES, &mut pos) as i64;
            let arr = prev_dep + hop;
            let dep = arr + dwell;
            arr_rel[k] = arr as i32;
            dep_rel[k] = dep as i32;
            prev_dep = dep;
        }
        ProfileDec { arr_rel, dep_rel }
    }

    fn stop_ll(&self, i: u32) -> (f64, f64) {
        let s = self.stop(i);
        (s.lat_e7 as f64 * 1e-7, s.lon_e7 as f64 * 1e-7)
    }

    // --- Spatial grid (sparse CSR) ---

    fn cell_row(&self, lat_e7: i32) -> i64 {
        ((lat_e7 as i64 - self.grid_lat0_e7 as i64) / self.grid_cell_e7 as i64).max(0)
    }
    fn cell_col(&self, lon_e7: i32) -> i64 {
        ((lon_e7 as i64 - self.grid_lon0_e7 as i64) / self.grid_cell_e7 as i64).max(0)
    }

    /// Binary-search a cell id in GRID_CELL_IDS -> its index, if present.
    fn cell_index(&self, cell_id: u32) -> Option<usize> {
        let ids = self.sec_ptr(SEC_GRID_CELL_IDS);
        let (mut lo, mut hi) = (0usize, self.grid_cell_count as usize);
        while lo < hi {
            let mid = (lo + hi) / 2;
            let v = unsafe { read_at::<u32>(ids, mid) };
            if v == cell_id {
                return Some(mid);
            } else if v < cell_id {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        None
    }

    fn cell_stops(&self, cell_index: usize) -> (u32, u32) {
        let off = self.sec_ptr(SEC_GRID_CELL_OFF);
        let s = unsafe { read_at::<u32>(off, cell_index) };
        let e = unsafe { read_at::<u32>(off, cell_index + 1) };
        (s, e)
    }
    fn grid_stop(&self, i: u32) -> u32 {
        unsafe { read_at::<u32>(self.sec_ptr(SEC_GRID_STOPS), i as usize) }
    }

    /// Candidate stops within `radius_m` of `(lat,lon)` and their distances,
    /// using the grid so the scan is cell-local rather than O(all stops).
    fn stops_in_radius(&self, lat: f64, lon: f64, radius_m: f64) -> Vec<(u32, f64)> {
        let mut out: Vec<(u32, f64)> = Vec::new();
        if self.grid_cell_count == 0 || self.grid_cols == 0 || self.grid_cell_e7 == 0 {
            return out;
        }
        let lat_e7 = (lat * 1e7) as i32;
        let lon_e7 = (lon * 1e7) as i32;
        let row0 = self.cell_row(lat_e7);
        let col0 = self.cell_col(lon_e7);
        let cell_deg = self.grid_cell_e7 as f64 * 1e-7;
        let rad_deg = radius_m / 111_320.0;
        let cos = lat.to_radians().cos().abs().max(1e-6);
        // +1 cell of slack; longitude cells shrink with latitude (cos factor).
        let dr = (rad_deg / cell_deg).ceil() as i64 + 1;
        let dc = ((rad_deg / cos) / cell_deg).ceil() as i64 + 1;
        let cols = self.grid_cols as i64;
        for r in (row0 - dr)..=(row0 + dr) {
            if r < 0 {
                continue;
            }
            for c in (col0 - dc)..=(col0 + dc) {
                if c < 0 || c >= cols {
                    continue;
                }
                let cell_id = (r * cols + c) as u32;
                if let Some(ci) = self.cell_index(cell_id) {
                    let (s, e) = self.cell_stops(ci);
                    for k in s..e {
                        let sid = self.grid_stop(k);
                        let (slat, slon) = self.stop_ll(sid);
                        let d = dist_m(lat, lon, slat, slon);
                        if d <= radius_m {
                            out.push((sid, d));
                        }
                    }
                }
            }
        }
        out
    }

    /// Nearest stop to `(lat,lon)` within `max_m`, via the grid.
    fn nearest_stop(&self, lat: f64, lon: f64, max_m: f64) -> Option<(u32, f64)> {
        let mut best: Option<(u32, f64)> = None;
        for (s, d) in self.stops_in_radius(lat, lon, max_m) {
            if best.map(|(_, bd)| d < bd).unwrap_or(true) {
                best = Some((s, d));
            }
        }
        best
    }

    /// True if `lat`/`lon` (degrees) lie within the pack's bounding box (with a
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

    /// Pack-level name (fallback). Per-route feeds use [`Self::feed_name_of`].
    pub fn feed_name(&self) -> String {
        self.read_str(self.feed_name_off)
    }

    /// Name of feed `feed_idx` from the FEEDS table (per-route provenance).
    fn feed_name_of(&self, feed_idx: u32) -> String {
        if feed_idx >= self.feed_count {
            return self.feed_name();
        }
        let off = unsafe { read_at::<u32>(self.sec_ptr(SEC_FEEDS), feed_idx as usize) };
        self.read_str(off)
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

/// Absolute time = trip start_time + a profile offset (relative), clamped ≥ 0.
fn abs_time(start_time: u32, rel: i32) -> u32 {
    (start_time as i64 + rel as i64).max(0) as u32
}

/// Fetch (and cache) a decoded profile.
fn get_profile<'a>(
    cache: &'a mut HashMap<u32, ProfileDec>,
    idx: &TransitIndex,
    pid: u32,
) -> &'a ProfileDec {
    cache.entry(pid).or_insert_with(|| idx.profile(pid))
}

/// How a stop was first reached in a RAPTOR round, for journey reconstruction.
#[derive(Clone, Copy)]
enum Reached {
    Origin,
    /// Boarded `route` on its local `trip` at `board_stop`, alighting here.
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
    let n = idx.stop_count as usize;
    if n == 0 {
        return None;
    }

    let inf = u32::MAX;
    let mut best = vec![inf; n];
    let mut label = vec![Reached::Origin; n];
    let mut prof_cache: HashMap<u32, ProfileDec> = HashMap::new();

    // --- Access: walk from origin to nearby stops (grid-restricted). ---
    let access = idx.stops_in_radius(from_lat, from_lon, ACCESS_RADIUS_M);
    if access.is_empty() {
        return None;
    }
    let mut seeds: Vec<u32> = Vec::new();
    for (s, d) in access {
        let t = dep_secs + (d / WALK_SPEED_M_S).ceil() as u32;
        if t < best[s as usize] {
            best[s as usize] = t;
            label[s as usize] = Reached::Origin;
            seeds.push(s);
        }
    }

    // --- Egress: precompute walk time from nearby stops to the destination. ---
    let egress: Vec<(u32, u32)> = idx
        .stops_in_radius(to_lat, to_lon, ACCESS_RADIUS_M)
        .into_iter()
        .map(|(s, d)| (s, (d / WALK_SPEED_M_S).ceil() as u32))
        .collect();
    if egress.is_empty() {
        return None;
    }

    // Round 0 queue = access stops + their footpath transfers.
    let mut queue: Vec<u32> = seeds.clone();
    relax_transfers(idx, &mut best, &mut label, &seeds, &mut queue);

    // --- RAPTOR rounds ---
    for _round in 0..MAX_ROUNDS {
        if queue.is_empty() {
            break;
        }
        // Routes to scan, and the earliest marked stop-position on each.
        let mut route_earliest: HashMap<u32, u32> = HashMap::new();
        for &s in &queue {
            let (rs, re) = idx.stop_routes_range(s);
            for i in rs..re {
                let r = idx.stop_route(i);
                let route = idx.route(r);
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

        let mut improved: Vec<u32> = Vec::new();
        for (&r, &start_pos) in &route_earliest {
            let route = idx.route(r);
            let trips = idx.route_trips(&route);
            if trips.is_empty() {
                continue;
            }
            let mut cur_trip: Option<usize> = None; // local index into `trips`
            let mut board_stop: u32 = 0;
            for pos in start_pos..route.n_stops {
                let stop = idx.route_stop(route.first_route_stop + pos);

                // If riding, relax arrival at this stop.
                if let Some(ti) = cur_trip {
                    let td = trips[ti];
                    let arr_rel = {
                        let p = get_profile(&mut prof_cache, idx, td.profile_id);
                        p.arr_rel.get(pos as usize).copied()
                    };
                    if let Some(arr_rel) = arr_rel {
                        let arr = abs_time(td.start_time, arr_rel);
                        if arr < best[stop as usize] {
                            best[stop as usize] = arr;
                            label[stop as usize] =
                                Reached::Transit { route: r, trip: ti as u32, board_stop };
                            improved.push(stop);
                        }
                    }
                }

                // Can we (re)board an earlier trip here given our arrival?
                let ready = best[stop as usize];
                if ready != inf {
                    if let Some(ti) =
                        earliest_trip(idx, &mut prof_cache, &trips, pos, ready, weekday, date)
                    {
                        let board_here = match cur_trip {
                            None => true,
                            Some(cur) => {
                                let new_dep = trip_dep(idx, &mut prof_cache, &trips[ti], pos);
                                let cur_dep = trip_dep(idx, &mut prof_cache, &trips[cur], pos);
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

        // Next queue = freshly improved stops + their footpath transfers.
        let mut next: Vec<u32> = improved.clone();
        relax_transfers(idx, &mut best, &mut label, &improved, &mut next);
        queue = next;
        if improved.is_empty() {
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
    let origin_stop;
    loop {
        guard += 1;
        if guard > MAX_ROUNDS * 4 + 16 {
            origin_stop = cur;
            break;
        }
        match label[cur as usize] {
            Reached::Origin => {
                origin_stop = cur;
                break;
            }
            Reached::Walk { from_stop } => {
                legs.push(make_walk_leg(idx, from_stop, cur));
                cur = from_stop;
            }
            Reached::Transit { route, trip, board_stop } => {
                legs.push(make_transit_leg(idx, &mut prof_cache, route, trip, board_stop, cur));
                cur = board_stop;
            }
        }
    }
    legs.reverse();

    // Prepend origin access walk and append destination egress walk.
    let first_stop = origin_stop;
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
                arr_secs: best[first_stop as usize]
                    .min(dep_secs + (access_d / WALK_SPEED_M_S) as u32),
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

/// A single upcoming scheduled departure from a stop (offline board).
pub struct StopDeparture {
    pub route_name: String,
    pub headsign: String,
    pub feed: String,
    pub stop_code: String,
    pub route_color: u32,
    pub route_type: u32,
    /// Departure time in seconds since service-day midnight (may exceed 86400 for
    /// trips running past midnight).
    pub dep_secs: u32,
}

/// Build an offline departure board for the stop(s) nearest to `(lat,lon)`.
///
/// Gathers every route serving the nearest stop (and its co-located platforms
/// within [`STATION_RADIUS_M`]) via the grid and returns upcoming scheduled
/// departures at or after `dep_secs` (seconds since local midnight) on the query
/// service day, sorted by time and capped at `max`. Empty when no stop is near
/// or nothing departs — the caller then keeps whatever the online board returned.
pub fn stop_departures(
    idx: &TransitIndex,
    lat: f64,
    lon: f64,
    dep_secs: u32,
    weekday: u32,
    date: u32,
    max: usize,
) -> Vec<StopDeparture> {
    const STATION_RADIUS_M: f64 = 150.0;
    const NEAREST_MAX_M: f64 = 400.0;

    if idx.stop_count == 0 {
        return Vec::new();
    }
    // Nearest stop to the tapped point (matched by lat/lon — there is no
    // MOTIS<->baked stop id join), via the grid.
    let (nearest, _) = match idx.nearest_stop(lat, lon, NEAREST_MAX_M) {
        Some(v) => v,
        None => return Vec::new(),
    };
    let (blat, blon) = idx.stop_ll(nearest);

    let mut prof_cache: HashMap<u32, ProfileDec> = HashMap::new();
    let mut out: Vec<StopDeparture> = Vec::new();
    for (s, _) in idx.stops_in_radius(blat, blon, STATION_RADIUS_M) {
        let code = idx.read_str(idx.stop(s).code_off);
        let (rs, re) = idx.stop_routes_range(s);
        for i in rs..re {
            let r = idx.stop_route(i);
            let route = idx.route(r);
            // Position of this stop within the route's ordered stop list.
            let mut pos = u32::MAX;
            for p in 0..route.n_stops {
                if idx.route_stop(route.first_route_stop + p) == s {
                    pos = p;
                    break;
                }
            }
            // Skip if not on the route, or it's the terminus (no onward departure).
            if pos == u32::MAX || pos + 1 >= route.n_stops {
                continue;
            }
            let rname = idx.read_str(route.name_off);
            let feed = idx.feed_name_of(route.feed_idx);
            let trips = idx.route_trips(&route);
            for td in &trips {
                if !idx.service_runs(td.service_idx, weekday, date) {
                    continue;
                }
                let dep = trip_dep(idx, &mut prof_cache, td, pos);
                if dep < dep_secs {
                    continue;
                }
                out.push(StopDeparture {
                    route_name: rname.clone(),
                    headsign: idx.read_str(td.headsign_off),
                    feed: feed.clone(),
                    stop_code: code.clone(),
                    route_color: route.color,
                    route_type: route.route_type,
                    dep_secs: dep,
                });
            }
        }
    }
    out.sort_by_key(|d| d.dep_secs);
    out.truncate(max);
    out
}

/// Departure time (abs secs) of `trip` at stop-position `pos`.
fn trip_dep(
    idx: &TransitIndex,
    cache: &mut HashMap<u32, ProfileDec>,
    trip: &TripDec,
    pos: u32,
) -> u32 {
    let dep_rel = {
        let p = get_profile(cache, idx, trip.profile_id);
        p.dep_rel.get(pos as usize).copied().unwrap_or(0)
    };
    abs_time(trip.start_time, dep_rel)
}

/// Relax one-hop footpath transfers from each `seed` stop, appending any stop
/// whose best arrival improves into `out`.
fn relax_transfers(
    idx: &TransitIndex,
    best: &mut [u32],
    label: &mut [Reached],
    seeds: &[u32],
    out: &mut Vec<u32>,
) {
    for &s in seeds {
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
                out.push(tr.to_stop);
            }
        }
    }
}

/// Earliest trip (local index) on this route departing stop-position `pos` no
/// earlier than `ready` whose service runs on the query day.
fn earliest_trip(
    idx: &TransitIndex,
    cache: &mut HashMap<u32, ProfileDec>,
    trips: &[TripDec],
    pos: u32,
    ready: u32,
    weekday: u32,
    date: u32,
) -> Option<usize> {
    let mut best_ti: Option<usize> = None;
    let mut best_dep = u32::MAX;
    for (i, td) in trips.iter().enumerate() {
        if !idx.service_runs(td.service_idx, weekday, date) {
            continue;
        }
        let dep = trip_dep(idx, cache, td, pos);
        if dep >= ready && dep < best_dep {
            best_dep = dep;
            best_ti = Some(i);
        }
    }
    best_ti
}

fn make_transit_leg(
    idx: &TransitIndex,
    cache: &mut HashMap<u32, ProfileDec>,
    route_idx: u32,
    trip_local: u32,
    board_stop: u32,
    alight_stop: u32,
) -> TransitLeg {
    let route = idx.route(route_idx);
    let trips = idx.route_trips(&route);
    let td = trips.get(trip_local as usize).copied().unwrap_or(TripDec {
        start_time: 0,
        profile_id: 0,
        service_idx: 0,
        headsign_off: NONE,
    });
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
    let (dep, arr) = {
        let p = get_profile(cache, idx, td.profile_id);
        let dep = abs_time(td.start_time, p.dep_rel.get(board_pos as usize).copied().unwrap_or(0));
        let arr = abs_time(td.start_time, p.arr_rel.get(alight_pos as usize).copied().unwrap_or(0));
        (dep, arr)
    };

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
        feed: idx.feed_name_of(route.feed_idx),
        from_code: idx.read_str(idx.stop(board_stop).code_off),
        to_code: idx.read_str(idx.stop(alight_stop).code_off),
        headsign: idx.read_str(td.headsign_off),
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
    // Compile-time assertions that fixed on-disk record sizes match the writer.
    assert!(std::mem::size_of::<StopRec>() == 16);
    assert!(std::mem::size_of::<RouteRec>() == 32);
    assert!(std::mem::size_of::<TransferRec>() == 8);
    assert!(std::mem::size_of::<ServiceRec>() == 12);
    assert!(std::mem::size_of::<ExcRec>() == 12);
    let _ = SECS_PER_DAY;
};
