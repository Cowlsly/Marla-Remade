//! On-device transit index loader + RAPTOR journey planner (P11b / world pack).
//!
//! Consumes the compact `.transit` index produced by the host tool
//! `scripts/maps/gtfs_ingest` (P11a). The on-disk layout (format v5 "TRX2") is
//! documented at the top of that tool's `src/index.rs` and MUST stay in sync
//! with the section constants and record accessors here.
//!
//! v5 adds two sections (23-24) holding a per-feed Transitous id prefix and each
//! stop's raw GTFS `stop_id`, which compose into a MOTIS stop id
//! (`us-ca-SF-bayarea_901201`). That is what lets the realtime `/stoptimes`
//! overlay name a stop without the `/map/stops` coordinate lookup. v3 and v4
//! packs are still accepted and simply report no MOTIS ids.
//!
//! v4 adds three sections (20-22) carrying GTFS `shapes.txt` geometry, so a ride
//! leg draws the vehicle's real path instead of a line through its stops. v3
//! packs are still accepted and fall back to stop-to-stop geometry.
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
//! road-graph merge gap; `lib.rs` re-draws the resulting walk legs along the
//! road graph afterwards, without re-timing them.

use crate::graph::{read_at, MmapRegion};
use std::collections::HashMap;

// --- Format constants (mirror scripts/maps/gtfs_ingest/src/index.rs) ---
const MAGIC: u32 = 0x5452_4958; // "TRIX"
/// Newest format this reader understands.
const VERSION: u32 = 5;
/// Oldest format still accepted. Reading both means an app update and a pack
/// rebuild can land in either order without offline transit silently degrading
/// to the online planner in between.
const VERSION_MIN: u32 = 3;
pub const NONE: u32 = 0xFFFF_FFFF;
const HEADER_LEN: usize = 80;
/// Section count of the newest format. The section directory is sized to this
/// and an older pack simply leaves the trailing entries empty.
const SECTION_COUNT: usize = 25;
const SECTION_COUNT_V3: usize = 20;

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
const SEC_FEED_TZ: usize = 17;
const SEC_EXCEPTIONS_IDX: usize = 18;
const SEC_STOP_ROUTE_POS: usize = 19;
const SEC_SHAPE_COORDS: usize = 20;
const SEC_ROUTE_SHAPE_IDX: usize = 21;
const SEC_ROUTE_STOP_SHAPE: usize = 22;
const SEC_FEED_MOTIS_PREFIX: usize = 23;
const SEC_STOP_GTFS_ID: usize = 24;

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

/// What a journey leg is; maps onto `RouteService.API.Maneuver` at the JNI
/// boundary (`Walk` → UNSPECIFIED, `Wait` → WAIT, `Ride` → RIDE).
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum LegKind {
    Walk,
    /// Waiting at a stop for the next departure.
    Wait,
    /// Riding a transit vehicle.
    Ride,
}

impl LegKind {
    pub fn is_transit(self) -> bool {
        self == LegKind::Ride
    }
}

/// A single leg of a planned journey, with owned strings ready for JNI.
pub struct TransitLeg {
    pub kind: LegKind,
    /// Route short/long name for a ride (or the awaited route for a wait).
    pub name: String,
    pub feed: String,
    /// Stop name (falling back to `stop_code` when the feed has no name).
    pub from_stop: String,
    pub to_stop: String,
    /// GTFS `trip_headsign`.
    pub headsign: String,
    /// GTFS `route_color` as packed 0xRRGGBB, or 0 when the feed omits it.
    pub route_color: u32,
    pub dep_secs: u32,
    pub arr_secs: u32,
    pub stop_count: i32,
    pub dist_m: f64,
    /// Flat `[lon, lat, lon, lat, ...]` polyline through the leg's stops.
    pub coords: Vec<f64>,
    /// MOTIS/Transitous ids of the ride's board and alight stops (v5 packs only;
    /// empty otherwise, and empty for non-ride legs). The realtime overlay asks
    /// `/stoptimes` about these directly, which is what removed the old
    /// coordinate-to-id round trip through `/map/stops`.
    pub board_stop_motis_id: String,
    pub alight_stop_motis_id: String,
}

/// What keeps an index's bytes alive: an mmap of the pack file in production, or
/// an owned buffer in tests (a `Vec`'s heap allocation doesn't move when the
/// enum does, so `base` stays valid).
enum Backing {
    Mmap(MmapRegion),
    #[cfg(test)]
    Owned(Vec<u8>),
}

impl Backing {
    fn base(&self) -> *const u8 {
        match self {
            Backing::Mmap(r) => r.base(),
            #[cfg(test)]
            Backing::Owned(v) => v.as_ptr(),
        }
    }
    fn len(&self) -> usize {
        match self {
            Backing::Mmap(r) => r.len,
            #[cfg(test)]
            Backing::Owned(v) => v.len(),
        }
    }
}

/// Read-only transit index for one pack (may hold many merged feeds).
pub struct TransitIndex {
    _backing: Backing,
    base: *const u8,
    stop_count: u32,
    route_count: u32,
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

        TransitIndex::parse(Backing::Mmap(MmapRegion::map(&path)?))
    }

    /// Build an index over an in-memory TRX2 blob, so the planner can be tested
    /// without a pack file (the crate's mmap loader is Unix-only).
    #[cfg(test)]
    pub fn from_bytes(bytes: Vec<u8>) -> Option<TransitIndex> {
        TransitIndex::parse(Backing::Owned(bytes))
    }

    /// Validate the TRX2 header + section directory. A pack whose version this
    /// build does not know is rejected here, which is what lets the caller treat
    /// offline transit as unavailable rather than misread it. Versions
    /// `VERSION_MIN..=VERSION` are all accepted; the directory is sized from the
    /// header's own `section_count`, so an older pack loads with the newer
    /// sections left empty and every read of them gated on a non-zero length.
    fn parse(backing: Backing) -> Option<TransitIndex> {
        if backing.len() < HEADER_LEN {
            return None;
        }
        let base = backing.base();
        let magic: u32 = unsafe { read_at::<u32>(base, 0) };
        let version: u32 = unsafe { read_at::<u32>(base, 1) };
        if magic != MAGIC || !(VERSION_MIN..=VERSION).contains(&version) {
            return None;
        }
        let section_count = unsafe { read_at::<u32>(base, 2) } as usize;
        if section_count < SECTION_COUNT_V3 {
            return None;
        }
        if HEADER_LEN + section_count.saturating_mul(16) > backing.len() {
            return None;
        }
        let stop_count: u32 = unsafe { read_at::<u32>(base, 3) };
        let route_count: u32 = unsafe { read_at::<u32>(base, 4) };
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

        // Directory: section_count * (u64 offset, u64 len) starting at HEADER_LEN.
        // A newer pack may carry more sections than this build knows; ignore the
        // tail rather than rejecting it.
        let dir_base = unsafe { base.add(HEADER_LEN) };
        let mut sec = [(0usize, 0usize); SECTION_COUNT];
        for (i, s) in sec.iter_mut().enumerate().take(section_count.min(SECTION_COUNT)) {
            let off: u64 = unsafe { read_at::<u64>(dir_base, i * 2) };
            let len: u64 = unsafe { read_at::<u64>(dir_base, i * 2 + 1) };
            if off as usize + len as usize > backing.len() {
                return None;
            }
            *s = (off as usize, len as usize);
        }

        Some(TransitIndex {
            _backing: backing,
            base,
            stop_count,
            route_count,
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
    /// Position of the stop within that route's stop pattern, parallel to
    /// [`Self::stop_route`] (first occurrence, as the writer records it).
    fn stop_route_pos(&self, i: u32) -> u32 {
        unsafe { read_at::<u32>(self.sec_ptr(SEC_STOP_ROUTE_POS), i as usize) }
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
    fn exception(&self, i: u32) -> ExcRec {
        unsafe { read_at::<ExcRec>(self.sec_ptr(SEC_EXCEPTIONS), i as usize) }
    }
    /// `[start, end)` range of EXCEPTIONS belonging to `service_idx`, ascending
    /// by date. v3 CSR index; replaces a full scan of every exception per call.
    fn exceptions_range(&self, service_idx: u32) -> (u32, u32) {
        let idx = self.sec_ptr(SEC_EXCEPTIONS_IDX);
        let s = unsafe { read_at::<u32>(idx, service_idx as usize) };
        let e = unsafe { read_at::<u32>(idx, service_idx as usize + 1) };
        (s, e)
    }

    /// Byte offset of `route_idx`'s polyline within SHAPE_COORDS, or `None` when
    /// the pack predates v4, or the ingester found no usable `shapes.txt`
    /// geometry for that route. Offsets are not a prefix sum: routes sharing a
    /// `shape_id` share one blob, so several may point at the same offset.
    fn route_shape_off(&self, route_idx: u32) -> Option<usize> {
        if route_idx >= self.route_count {
            return None;
        }
        if self.sec[SEC_ROUTE_SHAPE_IDX].1 < (self.route_count as usize + 1) * 4 {
            return None;
        }
        let off = unsafe { read_at::<u32>(self.sec_ptr(SEC_ROUTE_SHAPE_IDX), route_idx as usize) };
        // NONE marks "no shape"; an offset with no room for the point count is
        // corrupt and gets the same fallback.
        if (off as usize).saturating_add(4) > self.sec[SEC_SHAPE_COORDS].1 {
            return None;
        }
        Some(off as usize)
    }

    /// Vertex index within its route's shape for ROUTE_STOPS entry `i`, or
    /// [`NONE`] when the pack carries no shape for it.
    fn route_stop_shape(&self, i: u32) -> u32 {
        if (i as usize + 1) * 4 > self.sec[SEC_ROUTE_STOP_SHAPE].1 {
            return NONE;
        }
        unsafe { read_at::<u32>(self.sec_ptr(SEC_ROUTE_STOP_SHAPE), i as usize) }
    }

    /// Decode vertices `from..=to` of the shape blob at byte offset `off`, as
    /// `(lat, lon)` degrees. Deltas accumulate from the blob's first point, so
    /// decoding always starts there and discards the head.
    fn shape_slice(&self, off: usize, from: u32, to: u32) -> Vec<(f64, f64)> {
        let point_count = unsafe {
            read_at::<u32>(self.base.add(self.sec[SEC_SHAPE_COORDS].0 + off), 0)
        };
        if to < from || to >= point_count {
            return Vec::new();
        }
        let mut pos = off + 4;
        let mut lat_e7 = 0i64;
        let mut lon_e7 = 0i64;
        let mut out = Vec::with_capacity((to - from + 1) as usize);
        for v in 0..=to {
            lat_e7 += zigzag(self.uvarint(SEC_SHAPE_COORDS, &mut pos));
            lon_e7 += zigzag(self.uvarint(SEC_SHAPE_COORDS, &mut pos));
            if v >= from {
                out.push((lat_e7 as f64 * 1e-7, lon_e7 as f64 * 1e-7));
            }
        }
        out
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

    /// Display name of a stop, falling back to its code when the feed's
    /// `stop_name` is blank. The UI presents these as names, not codes.
    fn stop_label(&self, i: u32) -> String {
        let s = self.stop(i);
        let name = self.read_str(s.name_off);
        if name.is_empty() {
            self.read_str(s.code_off)
        } else {
            name
        }
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

    /// IANA timezone of feed `feed_idx` from FEED_TZ, or empty when the feed had
    /// no `agency.txt`.
    fn feed_tz_of(&self, feed_idx: u32) -> String {
        if feed_idx >= self.feed_count {
            return String::new();
        }
        let off = unsafe { read_at::<u32>(self.sec_ptr(SEC_FEED_TZ), feed_idx as usize) };
        self.read_str(off)
    }

    /// Transitous id prefix of feed `feed_idx` (`us-ca-SF-bayarea`), or `None` on
    /// a pre-v5 pack or a feed whose prefix the build did not know.
    fn feed_motis_prefix_of(&self, feed_idx: u32) -> Option<String> {
        if feed_idx >= self.feed_count {
            return None;
        }
        // v3/v4 packs carry no such section; its length is the version gate.
        if self.sec[SEC_FEED_MOTIS_PREFIX].1 < (self.feed_count as usize) * 4 {
            return None;
        }
        let off =
            unsafe { read_at::<u32>(self.sec_ptr(SEC_FEED_MOTIS_PREFIX), feed_idx as usize) };
        if off == NONE {
            return None;
        }
        let prefix = self.read_str(off);
        if prefix.is_empty() {
            None
        } else {
            Some(prefix)
        }
    }

    /// MOTIS/Transitous stop id for `stop_idx`, composed as
    /// `<feed prefix>_<gtfs stop_id>` (e.g. `us-ca-SF-bayarea_901201`). This is
    /// what the realtime overlay passes to `/stoptimes`, so it replaces the
    /// coordinate-to-id round trip through `/map/stops`.
    ///
    /// `None` on a pre-v5 pack, or when the feed's prefix was unknown at build
    /// time. `StopRec` carries no `feed_idx` — only `RouteRec` does — so the feed
    /// is resolved through a route serving the stop, as [`Self::timezone_at`] does.
    pub fn motis_stop_id(&self, stop_idx: u32) -> Option<String> {
        if stop_idx >= self.stop_count {
            return None;
        }
        if self.sec[SEC_STOP_GTFS_ID].1 < (self.stop_count as usize) * 4 {
            return None;
        }
        let id_off =
            unsafe { read_at::<u32>(self.sec_ptr(SEC_STOP_GTFS_ID), stop_idx as usize) };
        if id_off == NONE {
            return None;
        }
        let gtfs_id = self.read_str(id_off);
        if gtfs_id.is_empty() {
            return None;
        }
        let (rs, re) = self.stop_routes_range(stop_idx);
        for i in rs..re {
            let feed_idx = self.route(self.stop_route(i)).feed_idx;
            if let Some(prefix) = self.feed_motis_prefix_of(feed_idx) {
                return Some(format!("{prefix}_{gtfs_id}"));
            }
        }
        None
    }

    /// MOTIS id of the stop nearest `(lat, lon)`, for a caller that must name a
    /// stop before it knows which one it wants — the departure board fetches its
    /// realtime overlay before running the board query. Local lookup, no network.
    pub fn nearest_stop_motis_id(&self, lat: f64, lon: f64) -> Option<String> {
        const NEAREST_MAX_M: f64 = 400.0;
        let (stop, _) = self.nearest_stop(lat, lon, NEAREST_MAX_M)?;
        self.motis_stop_id(stop)
    }

    /// IANA timezone of the feed covering `(lat, lon)`, resolved via the nearest
    /// stop and one of the routes serving it. Stops carry no `feed_idx` — only
    /// `RouteRec` does — so the route hop is required. Empty when nothing is
    /// near enough or the feed has no timezone.
    pub fn timezone_at(&self, lat: f64, lon: f64) -> String {
        const NEAREST_MAX_M: f64 = 5000.0;
        let (stop, _) = match self.nearest_stop(lat, lon, NEAREST_MAX_M) {
            Some(v) => v,
            None => return String::new(),
        };
        let (rs, re) = self.stop_routes_range(stop);
        for i in rs..re {
            let tz = self.feed_tz_of(self.route(self.stop_route(i)).feed_idx);
            if !tz.is_empty() {
                return tz;
            }
        }
        String::new()
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
        let runs = date >= start && date <= end && (mask & (1 << (weekday & 7))) != 0;
        // A `calendar_dates` row for this exact date overrides the weekly mask.
        // EXCEPTIONS is date-sorted within the service's CSR range (v3), so this
        // is a binary search rather than a scan of the whole pack.
        let (mut lo, mut hi) = self.exceptions_range(service_idx);
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            let e = self.exception(mid);
            if e.date == date {
                return e.added == 1;
            } else if e.date < date {
                lo = mid + 1;
            } else {
                hi = mid;
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

/// Undo the producer's zigzag encoding (`(n << 1) ^ (n >> 63)`).
fn zigzag(u: u64) -> i64 {
    ((u >> 1) as i64) ^ -((u & 1) as i64)
}

/// Absolute time in the **query day's** frame: trip `start_time` plus a profile
/// offset. GTFS stores a trip running past midnight on its own service day
/// (`24:30:00`), so a trip inherited from the previous service day is shifted
/// back one full day.
fn abs_time_on(start_time: u32, rel: i32, prev_day: bool) -> u32 {
    let shift = if prev_day { SECS_PER_DAY as i64 } else { 0 };
    (start_time as i64 + rel as i64 - shift).max(0) as u32
}

/// The two service days a query may draw trips from: the query day itself, and
/// the day before it, whose `>24:00:00` trips run into the query day. Weekdays
/// are 0=Mon..6=Sun and dates are `yyyymmdd`, both in the **feed's** timezone.
#[derive(Clone, Copy)]
pub struct QueryDay {
    pub weekday: u32,
    pub date: u32,
    pub prev_weekday: u32,
    pub prev_date: u32,
}

/// The query-specific view of the timetable: which service days are eligible,
/// and what realtime knows about them. Threaded through the RAPTOR rounds.
#[derive(Clone, Copy)]
pub struct Schedule<'a> {
    pub day: QueryDay,
    pub overlay: Option<&'a DelayOverlay>,
}

impl Schedule<'_> {
    /// Whether `service_idx` runs on the query day (`prev = false`) or on the
    /// preceding service day (`prev = true`).
    fn runs(&self, idx: &TransitIndex, service_idx: u32, prev: bool) -> bool {
        if prev {
            idx.service_runs(service_idx, self.day.prev_weekday, self.day.prev_date)
        } else {
            idx.service_runs(service_idx, self.day.weekday, self.day.date)
        }
    }

    fn adjustment(&self, route: u32, pos: u32, sched: u32) -> Option<Adjustment> {
        self.overlay.and_then(|o| o.get(route, pos, sched))
    }

    /// How much earlier than its timetable slot any trip could depart.
    fn max_early_secs(&self) -> u32 {
        self.overlay.map_or(0, |o| o.max_early_secs)
    }
}

/// Realtime delays and cancellations lifted from a MOTIS departure board, so
/// RAPTOR plans against live times instead of the bare timetable.
///
/// The join is a **fingerprint**, not a trip id: TRX2 stores no `trip_id` (the
/// ingest tool discards it) and MOTIS's `tripId` is an opaque internally-encoded
/// handle, not a bare GTFS id. Worse, a v2 *profile* is deliberately shared
/// across many trips, so a delay can never hang off one. Entries are therefore
/// keyed on `(route_idx, stop_pos, scheduled_departure)` and resolved **once**,
/// at construction — never inside the RAPTOR hot loop.
#[derive(Default)]
pub struct DelayOverlay {
    entries: HashMap<(u32, u32, u32), Adjustment>,
    /// Largest amount by which any entry pulls a departure **earlier** (seconds,
    /// ≥ 0). `earliest_trip`'s early break needs this: a vehicle running early
    /// makes `dep < start_time`, so the break bound has to be widened by it or
    /// the scan can stop before a trip whose live departure is earlier.
    max_early_secs: u32,
}

#[derive(Clone, Copy)]
struct Adjustment {
    delay_secs: i32,
    cancelled: bool,
}

/// One realtime board entry as it crosses the JNI boundary.
pub struct DelayEntry {
    /// Board-stop coordinates; matched to a baked stop via the spatial grid,
    /// because MOTIS stop ids share no namespace with the pack.
    pub lat: f64,
    pub lon: f64,
    /// MOTIS `routeShortName`, matched against the baked route name.
    pub route_name: String,
    /// Scheduled departure, seconds since feed-local midnight.
    pub sched_secs: u32,
    pub delay_secs: i32,
    pub cancelled: bool,
}

impl DelayOverlay {
    /// Resolve realtime board entries against the index. Entries whose stop or
    /// route can't be matched are dropped — a miss must leave the schedule
    /// untouched, never guess.
    pub fn build(idx: &TransitIndex, entries: &[DelayEntry]) -> DelayOverlay {
        /// A MOTIS stop and its baked counterpart are the same platform, so keep
        /// this tight enough that adjacent platforms don't collide.
        const MATCH_RADIUS_M: f64 = 60.0;

        let mut out = DelayOverlay::default();
        for e in entries {
            if e.delay_secs == 0 && !e.cancelled {
                continue;
            }
            let stop = match idx.nearest_stop(e.lat, e.lon, MATCH_RADIUS_M) {
                Some((s, _)) => s,
                None => continue,
            };
            let (rs, re) = idx.stop_routes_range(stop);
            for i in rs..re {
                let r = idx.stop_route(i);
                if idx.read_str(idx.route(r).name_off) != e.route_name {
                    continue;
                }
                out.max_early_secs = out.max_early_secs.max((-e.delay_secs).max(0) as u32);
                out.entries.insert(
                    (r, idx.stop_route_pos(i), e.sched_secs),
                    Adjustment { delay_secs: e.delay_secs, cancelled: e.cancelled },
                );
            }
        }
        out
    }

    fn get(&self, route: u32, pos: u32, sched: u32) -> Option<Adjustment> {
        self.entries.get(&(route, pos, sched)).copied()
    }
}

/// Apply an overlay adjustment to a scheduled time, clamped ≥ 0.
fn delayed(sched: u32, delay_secs: i32) -> u32 {
    (sched as i64 + delay_secs as i64).max(0) as u32
}

/// The trip being ridden while scanning a route, plus the realtime shift that
/// applies to the rest of its journey.
#[derive(Clone, Copy)]
struct Boarding {
    /// Local index into the route's decoded trip list.
    trip: usize,
    /// This trip belongs to the previous service day (a `>24:00:00` trip).
    prev_day: bool,
    delay_secs: i32,
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
    /// Boarded `route` at `board_stop` (see [`Boarding`]), alighting here.
    Transit { route: u32, board_stop: u32, boarding: Boarding },
    /// Walked from `from_stop` (footpath transfer or egress precursor).
    Walk { from_stop: u32 },
}

/// Earliest known arrival per stop, plus how it was reached and the destination
/// bound that prunes the search.
///
/// Sparse because the flat `vec![_; stop_count]` pair this replaced wrote ~175 MB
/// per call at planetary stop counts, twice per query, to serve a touched set that
/// target pruning keeps proportional to the query. Probes now pay a hash instead,
/// which the round loop can afford.
struct Arrivals {
    best: HashMap<u32, u32>,
    label: HashMap<u32, Reached>,
    /// Walk seconds from each stop within egress range of the destination.
    egress: HashMap<u32, u32>,
    /// Best egress-adjusted arrival at the destination so far, or `u32::MAX`.
    ///
    /// Every onward move costs non-negative time, so a journey through a stop
    /// reached at or after this can never beat it. That makes it a sound cutoff
    /// rather than a heuristic — without one, six rounds diffuse across the whole
    /// planetary component.
    target: u32,
}

impl Arrivals {
    fn new(egress: &[(u32, u32)]) -> Arrivals {
        let mut by_stop: HashMap<u32, u32> = HashMap::new();
        for &(s, w) in egress {
            by_stop.entry(s).and_modify(|e| *e = (*e).min(w)).or_insert(w);
        }
        Arrivals {
            best: HashMap::new(),
            label: HashMap::new(),
            egress: by_stop,
            target: u32::MAX,
        }
    }

    /// Earliest known arrival at `stop`, or `u32::MAX` if unreached.
    fn at(&self, stop: u32) -> u32 {
        self.best.get(&stop).copied().unwrap_or(u32::MAX)
    }

    /// Whether reaching a stop at `t` could still improve the destination.
    fn useful(&self, t: u32) -> bool {
        t < self.target
    }

    /// Record reaching `stop` at `t` via `how`, if that beats both the stop's own
    /// best and [`Self::target`]. Reports whether it did, and tightens the target
    /// when `stop` can walk to the destination.
    fn improve(&mut self, stop: u32, t: u32, how: Reached) -> bool {
        if !self.useful(t) || t >= self.at(stop) {
            return false;
        }
        self.best.insert(stop, t);
        self.label.insert(stop, how);
        if let Some(&w) = self.egress.get(&stop) {
            self.target = self.target.min(t.saturating_add(w));
        }
        true
    }

    /// How `stop` was reached. Unreached stops read as [`Reached::Origin`], which
    /// terminates reconstruction rather than looping.
    fn how(&self, stop: u32) -> Reached {
        self.label.get(&stop).copied().unwrap_or(Reached::Origin)
    }
}

/// Plan an earliest-arrival transit journey between two WGS84 points departing
/// at `dep_secs` seconds since midnight. All times are in the feed's local frame
/// (see [`QueryDay`]). When `sched` carries an overlay, cancelled trips are
/// skipped and delayed ones are planned at their live times. Returns the ordered
/// legs, or `None` if unreachable.
pub fn plan(
    idx: &TransitIndex,
    from_lat: f64,
    from_lon: f64,
    to_lat: f64,
    to_lon: f64,
    dep_secs: u32,
    sched: Schedule,
) -> Option<Vec<TransitLeg>> {
    let n = idx.stop_count as usize;
    if n == 0 {
        return None;
    }

    let inf = u32::MAX;
    let mut prof_cache: HashMap<u32, ProfileDec> = HashMap::new();

    // --- Egress: precompute walk time from nearby stops to the destination. ---
    // Before access, so the very first access stop already prunes against a
    // walk-only journey when the two ranges overlap.
    let egress: Vec<(u32, u32)> = idx
        .stops_in_radius(to_lat, to_lon, ACCESS_RADIUS_M)
        .into_iter()
        .map(|(s, d)| (s, (d / WALK_SPEED_M_S).ceil() as u32))
        .collect();
    if egress.is_empty() {
        return None;
    }
    let mut reach = Arrivals::new(&egress);

    // --- Access: walk from origin to nearby stops (grid-restricted). ---
    let access = idx.stops_in_radius(from_lat, from_lon, ACCESS_RADIUS_M);
    if access.is_empty() {
        return None;
    }
    let mut seeds: Vec<u32> = Vec::new();
    for (s, d) in access {
        let t = dep_secs + (d / WALK_SPEED_M_S).ceil() as u32;
        if reach.improve(s, t, Reached::Origin) {
            seeds.push(s);
        }
    }

    // Round 0 queue = access stops + their footpath transfers.
    let mut queue: Vec<u32> = seeds.clone();
    relax_transfers(idx, &mut reach, &seeds, &mut queue);

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
                let pos = idx.stop_route_pos(i);
                route_earliest
                    .entry(r)
                    .and_modify(|e| {
                        if pos < *e {
                            *e = pos;
                        }
                    })
                    .or_insert(pos);
            }
        }

        let mut improved: Vec<u32> = Vec::new();
        for (&r, &start_pos) in &route_earliest {
            let route = idx.route(r);
            let trips = idx.route_trips(&route);
            if trips.is_empty() {
                continue;
            }
            let mut cur_trip: Option<Boarding> = None;
            let mut board_stop: u32 = 0;
            for pos in start_pos..route.n_stops {
                let stop = idx.route_stop(route.first_route_stop + pos);

                // If riding, relax arrival at this stop. A delayed vehicle
                // arrives late everywhere downstream, so the boarding shift
                // carries through the rest of the leg.
                if let Some(b) = cur_trip {
                    let td = trips[b.trip];
                    let arr_rel = {
                        let p = get_profile(&mut prof_cache, idx, td.profile_id);
                        p.arr_rel.get(pos as usize).copied()
                    };
                    if let Some(arr_rel) = arr_rel {
                        let arr = delayed(
                            abs_time_on(td.start_time, arr_rel, b.prev_day),
                            b.delay_secs,
                        );
                        let how = Reached::Transit { route: r, board_stop, boarding: b };
                        if reach.improve(stop, arr, how) {
                            improved.push(stop);
                        }
                    }
                }

                // Can we (re)board an earlier trip here given our arrival? Skipped
                // once this stop is too late to beat the destination, which is what
                // keeps `earliest_trip` off the routes that cannot matter.
                let ready = reach.at(stop);
                if ready != inf && reach.useful(ready) {
                    if let Some(cand) =
                        earliest_trip(idx, &mut prof_cache, r, &trips, pos, ready, sched)
                    {
                        let board_here = match cur_trip {
                            None => true,
                            Some(cur) => {
                                let new_dep =
                                    trip_dep(idx, &mut prof_cache, &trips[cand.trip], pos, cand);
                                let cur_dep =
                                    trip_dep(idx, &mut prof_cache, &trips[cur.trip], pos, cur);
                                new_dep < cur_dep
                            }
                        };
                        if board_here {
                            cur_trip = Some(cand);
                            board_stop = stop;
                        }
                    }
                }
            }
        }

        // Next queue = freshly improved stops + their footpath transfers.
        let mut next: Vec<u32> = improved.clone();
        relax_transfers(idx, &mut reach, &improved, &mut next);
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
        if reach.at(s) == inf {
            continue;
        }
        let total = reach.at(s).saturating_add(w);
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
            // A cycle in the labels would otherwise yield a silently truncated
            // journey; bail so the caller falls back to the online planner.
            return None;
        }
        match reach.how(cur) {
            Reached::Origin => {
                origin_stop = cur;
                break;
            }
            Reached::Walk { from_stop } => {
                legs.push(make_walk_leg(
                    idx,
                    from_stop,
                    cur,
                    reach.at(from_stop),
                    reach.at(cur),
                ));
                cur = from_stop;
            }
            Reached::Transit { route, board_stop, boarding } => {
                legs.push(make_transit_leg(
                    idx,
                    &mut prof_cache,
                    route,
                    boarding,
                    board_stop,
                    cur,
                ));
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
                kind: LegKind::Walk,
                name: "Walk".to_string(),
                feed: String::new(),
                from_stop: String::new(),
                to_stop: idx.stop_label(first_stop),
                headsign: String::new(),
                route_color: 0,
                dep_secs,
                arr_secs: reach
                    .at(first_stop)
                    .min(dep_secs + (access_d / WALK_SPEED_M_S).ceil() as u32),
                stop_count: 0,
                dist_m: access_d,
                coords: vec![from_lon, from_lat, flon, flat],
                // Walk legs carry no realtime, so they need no MOTIS ids.
                board_stop_motis_id: String::new(),
                alight_stop_motis_id: String::new(),
            },
        );
    }
    let (elat, elon) = idx.stop_ll(best_stop);
    let egress_d = dist_m(to_lat, to_lon, elat, elon);
    if egress_d > 1.0 {
        legs.push(TransitLeg {
            kind: LegKind::Walk,
            name: "Walk".to_string(),
            feed: String::new(),
            from_stop: idx.stop_label(best_stop),
            to_stop: String::new(),
            headsign: String::new(),
            route_color: 0,
            dep_secs: reach.at(best_stop),
            arr_secs: reach.at(best_stop).saturating_add(best_walk),
            stop_count: 0,
            dist_m: egress_d,
            coords: vec![elon, elat, to_lon, to_lat],
            board_stop_motis_id: String::new(),
            alight_stop_motis_id: String::new(),
        });
    }

    if legs.is_empty() {
        return None;
    }
    Some(insert_wait_legs(legs, dep_secs))
}

/// Minimum gap before we surface an explicit WAIT leg, so a 30-second dwell
/// doesn't become its own step.
const MIN_WAIT_SECS: u32 = 60;

/// Insert an explicit WAIT leg before each ride the traveller has to wait for.
/// RAPTOR already knows both times; without this the wait is invisible and the
/// journey's step durations don't add up to its total. `dep_secs` is when the
/// traveller is ready, which is what the first leg waits from.
fn insert_wait_legs(legs: Vec<TransitLeg>, dep_secs: u32) -> Vec<TransitLeg> {
    let mut out: Vec<TransitLeg> = Vec::with_capacity(legs.len());
    for leg in legs {
        let ready = out.last().map_or(dep_secs, |prev| prev.arr_secs);
        if leg.kind == LegKind::Ride && leg.dep_secs >= ready + MIN_WAIT_SECS {
            out.push(TransitLeg {
                kind: LegKind::Wait,
                name: leg.name.clone(),
                feed: String::new(),
                from_stop: leg.from_stop.clone(),
                to_stop: leg.from_stop.clone(),
                headsign: leg.headsign.clone(),
                route_color: leg.route_color,
                dep_secs: ready,
                arr_secs: leg.dep_secs,
                stop_count: 0,
                dist_m: 0.0,
                coords: Vec::new(),
                // A wait happens at the following ride's board stop, whose id that
                // ride already carries; duplicating it here would double the
                // overlay's fetch for one stop.
                board_stop_motis_id: String::new(),
                alight_stop_motis_id: String::new(),
            });
        }
        out.push(leg);
    }
    out
}

/// A single upcoming departure from a stop (offline board).
pub struct StopDeparture {
    pub route_name: String,
    pub headsign: String,
    pub feed: String,
    pub stop_code: String,
    pub route_color: u32,
    pub route_type: u32,
    /// Scheduled departure in seconds since the query day's midnight.
    pub dep_secs: u32,
    /// Realtime shift from the overlay; 0 when there is no live data.
    pub delay_secs: i32,
    /// True when realtime says this trip is cancelled.
    pub cancelled: bool,
    /// Whether the overlay had live data for this departure at all.
    pub real_time: bool,
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
    sched: Schedule,
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
            let pos = idx.stop_route_pos(i);
            // Skip if it's the terminus (no onward departure).
            if pos + 1 >= route.n_stops {
                continue;
            }
            let rname = idx.read_str(route.name_off);
            let feed = idx.feed_name_of(route.feed_idx);
            let trips = idx.route_trips(&route);
            for td in &trips {
                // Sweep the previous service day too, so a 00:30 query still
                // finds a trip GTFS stored as 24:30:00 the day before.
                for prev_day in [true, false] {
                    if !sched.runs(idx, td.service_idx, prev_day) {
                        continue;
                    }
                    let Some(scheduled) = trip_sched_dep(idx, &mut prof_cache, td, pos, prev_day)
                    else {
                        continue;
                    };
                    let adj = sched.adjustment(r, pos, scheduled);
                    // Compare against the *live* time so a delayed trip that has
                    // not left yet stays on the board.
                    if delayed(scheduled, adj.map_or(0, |a| a.delay_secs)) < dep_secs {
                        continue;
                    }
                    out.push(StopDeparture {
                        route_name: rname.clone(),
                        headsign: idx.read_str(td.headsign_off),
                        feed: feed.clone(),
                        stop_code: code.clone(),
                        route_color: route.color,
                        route_type: route.route_type,
                        dep_secs: scheduled,
                        delay_secs: adj.map_or(0, |a| a.delay_secs),
                        cancelled: adj.is_some_and(|a| a.cancelled),
                        real_time: adj.is_some(),
                    });
                }
            }
        }
    }
    out.sort_by_key(|d| delayed(d.dep_secs, d.delay_secs));
    out.truncate(max);
    out
}

/// Departure time (query-day secs) of `trip` at stop-position `pos`, including
/// the realtime shift the traveller boarded with.
fn trip_dep(
    idx: &TransitIndex,
    cache: &mut HashMap<u32, ProfileDec>,
    trip: &TripDec,
    pos: u32,
    boarding: Boarding,
) -> u32 {
    let dep_rel = {
        let p = get_profile(cache, idx, trip.profile_id);
        p.dep_rel.get(pos as usize).copied().unwrap_or(0)
    };
    delayed(abs_time_on(trip.start_time, dep_rel, boarding.prev_day), boarding.delay_secs)
}

/// Scheduled (timetable) departure of `trip` at `pos` in the query day's frame,
/// ignoring realtime. `None` when `prev_day` is set but the trip does not
/// actually run into the query day — i.e. its GTFS time is below `24:00:00`, so
/// it belongs wholly to yesterday and must not be considered at all.
fn trip_sched_dep(
    idx: &TransitIndex,
    cache: &mut HashMap<u32, ProfileDec>,
    trip: &TripDec,
    pos: u32,
    prev_day: bool,
) -> Option<u32> {
    let dep_rel = {
        let p = get_profile(cache, idx, trip.profile_id);
        p.dep_rel.get(pos as usize).copied().unwrap_or(0)
    };
    let t = trip.start_time as i64 + dep_rel as i64
        - if prev_day { SECS_PER_DAY as i64 } else { 0 };
    if t < 0 {
        return None;
    }
    Some(t as u32)
}

/// Relax one-hop footpath transfers from each `seed` stop, appending any stop
/// whose best arrival improves into `out`.
fn relax_transfers(
    idx: &TransitIndex,
    reach: &mut Arrivals,
    seeds: &[u32],
    out: &mut Vec<u32>,
) {
    for &s in seeds {
        let base_t = reach.at(s);
        if base_t == u32::MAX {
            continue;
        }
        let (ts, te) = idx.transfers_range(s);
        for i in ts..te {
            let tr = idx.transfer(i);
            let arr = base_t.saturating_add(tr.secs);
            if reach.improve(tr.to_stop, arr, Reached::Walk { from_stop: s }) {
                out.push(tr.to_stop);
            }
        }
    }
}

/// Earliest trip on this route departing stop-position `pos` no earlier than
/// `ready`, whose service runs on `day` and which realtime hasn't cancelled.
/// Times are in the query day's frame and include any realtime delay.
fn earliest_trip(
    idx: &TransitIndex,
    cache: &mut HashMap<u32, ProfileDec>,
    route_idx: u32,
    trips: &[TripDec],
    pos: u32,
    ready: u32,
    sched: Schedule,
) -> Option<Boarding> {
    let mut best: Option<Boarding> = None;
    let mut best_dep = u32::MAX;
    // Trips are written start-time sorted and every profile offset is >= 0, so
    // `dep >= start_time - max_early`. Once start_time passes that bound, no
    // later trip on this day can improve on `best_dep`. Exact, not a heuristic:
    // with no realtime the slack is 0, and realtime can only pull a departure
    // earlier by `max_early_secs`.
    let slack = sched.max_early_secs();
    // Previous-day trips first: theirs are the earliest times in this frame.
    for prev_day in [true, false] {
        // A previous-day trip departs at `start_time + rel - SECS_PER_DAY` in this
        // frame, so its `start_time` has to clear the bound by a whole day. The
        // day belongs in the bound rather than disabling the break: exempting the
        // pass made it an unconditional scan of every trip on the route, each with
        // a `service_runs` binary search.
        let day_shift = if prev_day { SECS_PER_DAY } else { 0 };
        for (i, td) in trips.iter().enumerate() {
            if best.is_some()
                && td.start_time >= best_dep.saturating_add(slack).saturating_add(day_shift)
            {
                break;
            }
            if !sched.runs(idx, td.service_idx, prev_day) {
                continue;
            }
            // A previous-day trip only counts if it actually runs into the query
            // day (a GTFS time at or past 24:00:00).
            let Some(scheduled) = trip_sched_dep(idx, cache, td, pos, prev_day) else {
                continue;
            };
            let adj = sched.adjustment(route_idx, pos, scheduled);
            if adj.is_some_and(|a| a.cancelled) {
                continue;
            }
            let delay_secs = adj.map_or(0, |a| a.delay_secs);
            let dep = delayed(scheduled, delay_secs);
            if dep >= ready && dep < best_dep {
                best_dep = dep;
                best = Some(Boarding { trip: i, prev_day, delay_secs });
            }
        }
    }
    best
}

fn make_transit_leg(
    idx: &TransitIndex,
    cache: &mut HashMap<u32, ProfileDec>,
    route_idx: u32,
    boarding: Boarding,
    board_stop: u32,
    alight_stop: u32,
) -> TransitLeg {
    let route = idx.route(route_idx);
    let trips = idx.route_trips(&route);
    let td = trips.get(boarding.trip).copied().unwrap_or(TripDec {
        start_time: 0,
        profile_id: 0,
        service_idx: 0,
        headsign_off: NONE,
    });
    // Find board/alight positions along the route. This keeps the linear scan
    // (and its last-match semantics) rather than using STOP_ROUTE_POS, which
    // records the *first* occurrence — for a route that visits a stop twice the
    // two differ, and this runs once per leg, not in the RAPTOR hot loop.
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
        let dep = abs_time_on(
            td.start_time,
            p.dep_rel.get(board_pos as usize).copied().unwrap_or(0),
            boarding.prev_day,
        );
        let arr = abs_time_on(
            td.start_time,
            p.arr_rel.get(alight_pos as usize).copied().unwrap_or(0),
            boarding.prev_day,
        );
        (delayed(dep, boarding.delay_secs), delayed(arr, boarding.delay_secs))
    };

    let mut coords = Vec::new();
    let mut dist = 0.0;
    let mut count = 0i32;
    if alight_pos >= board_pos {
        count = (alight_pos - board_pos + 1) as i32;
        // Prefer the feed's own `shapes.txt` geometry (v4 packs): it is the path
        // the vehicle actually takes, and it makes `dist_m` truthful — a line
        // through the stops under-reports every ride. A v3 pack, or a route the
        // ingester could not attach a shape to, falls back to one vertex per stop.
        let shaped = idx.route_shape_off(route_idx).and_then(|off| {
            let from = idx.route_stop_shape(route.first_route_stop + board_pos);
            let to = idx.route_stop_shape(route.first_route_stop + alight_pos);
            if from == NONE || to == NONE {
                return None;
            }
            let pts = idx.shape_slice(off, from, to);
            if pts.len() < 2 {
                None
            } else {
                Some(pts)
            }
        });
        let points = shaped.unwrap_or_else(|| {
            (board_pos..=alight_pos)
                .map(|pos| idx.stop_ll(idx.route_stop(route.first_route_stop + pos)))
                .collect()
        });
        let mut prev: Option<(f64, f64)> = None;
        for (lat, lon) in points {
            if let Some((plat, plon)) = prev {
                dist += dist_m(plat, plon, lat, lon);
            }
            prev = Some((lat, lon));
            coords.push(lon);
            coords.push(lat);
        }
    }

    TransitLeg {
        kind: LegKind::Ride,
        name: idx.read_str(route.name_off),
        feed: idx.feed_name_of(route.feed_idx),
        from_stop: idx.stop_label(board_stop),
        to_stop: idx.stop_label(alight_stop),
        headsign: idx.read_str(td.headsign_off),
        route_color: route.color,
        dep_secs: dep,
        arr_secs: arr,
        stop_count: (count - 1).max(0),
        dist_m: dist,
        coords,
        board_stop_motis_id: idx.motis_stop_id(board_stop).unwrap_or_default(),
        alight_stop_motis_id: idx.motis_stop_id(alight_stop).unwrap_or_default(),
    }
}

fn make_walk_leg(
    idx: &TransitIndex,
    from_stop: u32,
    to_stop: u32,
    dep_secs: u32,
    arr_secs: u32,
) -> TransitLeg {
    let (flat, flon) = idx.stop_ll(from_stop);
    let (tlat, tlon) = idx.stop_ll(to_stop);
    TransitLeg {
        kind: LegKind::Walk,
        name: "Walk".to_string(),
        feed: String::new(),
        from_stop: idx.stop_label(from_stop),
        to_stop: idx.stop_label(to_stop),
        headsign: String::new(),
        route_color: 0,
        dep_secs,
        arr_secs,
        stop_count: 0,
        dist_m: dist_m(flat, flon, tlat, tlon),
        coords: vec![flon, flat, tlon, tlat],
        board_stop_motis_id: String::new(),
        alight_stop_motis_id: String::new(),
    }
}

const _: () = {
    // Compile-time assertions that fixed on-disk record sizes match the writer.
    assert!(std::mem::size_of::<StopRec>() == 16);
    assert!(std::mem::size_of::<RouteRec>() == 32);
    assert!(std::mem::size_of::<TransferRec>() == 8);
    assert!(std::mem::size_of::<ServiceRec>() == 12);
    assert!(std::mem::size_of::<ExcRec>() == 12);
};

#[cfg(test)]
mod tests {
    use super::*;

    // --- A minimal TRX2 writer (v3 or v4), so the planner can be exercised
    // without a pack file. It is deliberately independent of
    // `scripts/maps/gtfs_ingest`: if the two drift, these tests fail, which is
    // the point (the real writer lives in another crate and cannot be linked).

    const GRID_CELL_E7: u32 = 200_000; // 0.02 deg, as the ingest tool uses
    const MAX_TRANSFER_M: f64 = 400.0;

    struct Stop {
        lat: f64,
        lon: f64,
        name: &'static str,
        code: &'static str,
        /// Raw GTFS `stop_id`, baked into v5's STOP_GTFS_ID section.
        gtfs_id: &'static str,
    }

    struct Trip {
        start: u32,
        /// `(arr, dep)` per pattern position; `arr[0]` may equal `start`.
        stoptimes: Vec<(u32, u32)>,
        service: u32,
        headsign: &'static str,
    }

    /// A GTFS `shapes.txt` polyline plus, parallel to the route's pattern, each
    /// stop's vertex index within it.
    struct Shape {
        points: Vec<(f64, f64)>,
        stop_vertices: Vec<u32>,
    }

    struct Route {
        name: &'static str,
        color: u32,
        route_type: u32,
        feed: u32,
        pattern: Vec<u32>,
        trips: Vec<Trip>,
        /// v4 ride geometry. `None` reproduces a route the ingester could not
        /// attach a shape to, and is what every route in a v3 blob looks like.
        shape: Option<Shape>,
    }

    struct Service {
        mask: u8,
        start: u32,
        end: u32,
    }

    struct Pack {
        stops: Vec<Stop>,
        routes: Vec<Route>,
        services: Vec<Service>,
        /// `(service_idx, yyyymmdd, added)`, in any order.
        exceptions: Vec<(u32, u32, u32)>,
        /// `(feed name, IANA timezone, Transitous MOTIS prefix)`. An empty prefix
        /// reproduces a feed whose id space the build could not derive.
        feeds: Vec<(&'static str, &'static str, &'static str)>,
    }

    #[derive(Default)]
    struct Pool {
        bytes: Vec<u8>,
        map: HashMap<String, u32>,
    }

    impl Pool {
        fn new() -> Pool {
            Pool { bytes: vec![0], map: HashMap::new() }
        }
        fn intern(&mut self, s: &str) -> u32 {
            if s.is_empty() {
                return NONE;
            }
            if let Some(&o) = self.map.get(s) {
                return o;
            }
            let off = self.bytes.len() as u32;
            self.bytes.extend_from_slice(s.as_bytes());
            self.bytes.push(0);
            self.map.insert(s.to_string(), off);
            off
        }
    }

    fn u32b(v: &mut Vec<u8>, x: u32) {
        v.extend_from_slice(&x.to_le_bytes());
    }
    fn i32b(v: &mut Vec<u8>, x: i32) {
        v.extend_from_slice(&x.to_le_bytes());
    }
    fn uvarint(v: &mut Vec<u8>, mut x: u64) {
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
    fn zigzag_varint(v: &mut Vec<u8>, x: i64) {
        uvarint(v, ((x << 1) ^ (x >> 63)) as u64);
    }

    impl Pack {
        /// Serialize to a TRX2 blob at `version`. Overridable so both a
        /// stale-pack rejection and the older-format guards can be tested; below
        /// v4 the shape sections are omitted, below v5 the MOTIS id sections are.
        fn build_with_version(&self, version: u32) -> Vec<u8> {
            let mut pool = Pool::new();
            let pack_name_off = pool.intern("testpack");

            let n_stops = self.stops.len();
            let mut sec_stops = Vec::new();
            let mut sec_stop_gtfs_id = Vec::new();
            let (mut min_lat, mut min_lon) = (i32::MAX, i32::MAX);
            let (mut max_lat, mut max_lon) = (i32::MIN, i32::MIN);
            let lat_e7: Vec<i32> = self.stops.iter().map(|s| (s.lat * 1e7) as i32).collect();
            let lon_e7: Vec<i32> = self.stops.iter().map(|s| (s.lon * 1e7) as i32).collect();
            for i in 0..n_stops {
                min_lat = min_lat.min(lat_e7[i]);
                min_lon = min_lon.min(lon_e7[i]);
                max_lat = max_lat.max(lat_e7[i]);
                max_lon = max_lon.max(lon_e7[i]);
            }
            for (i, s) in self.stops.iter().enumerate() {
                i32b(&mut sec_stops, lat_e7[i]);
                i32b(&mut sec_stops, lon_e7[i]);
                let n = pool.intern(s.name);
                let c = pool.intern(s.code);
                u32b(&mut sec_stops, n);
                u32b(&mut sec_stops, c);
                let g = pool.intern(s.gtfs_id);
                u32b(&mut sec_stop_gtfs_id, g);
            }

            // Profiles, deduplicated by encoded body exactly as the writer does.
            let mut profile_bytes: Vec<u8> = Vec::new();
            let mut profile_offsets: Vec<u32> = Vec::new();
            let mut profile_ids: HashMap<Vec<u8>, u32> = HashMap::new();
            let mut intern_profile = |sts: &[(u32, u32)]| -> u32 {
                let mut body = Vec::new();
                uvarint(&mut body, sts.len() as u64);
                let (arr0, dep0) = sts[0];
                uvarint(&mut body, dep0.saturating_sub(arr0) as u64);
                let mut prev_dep = dep0;
                for &(arr, dep) in &sts[1..] {
                    uvarint(&mut body, arr.saturating_sub(prev_dep) as u64);
                    uvarint(&mut body, dep.saturating_sub(arr) as u64);
                    prev_dep = dep;
                }
                if let Some(&id) = profile_ids.get(&body) {
                    return id;
                }
                let id = profile_offsets.len() as u32;
                profile_offsets.push(profile_bytes.len() as u32);
                profile_bytes.extend_from_slice(&body);
                profile_ids.insert(body, id);
                id
            };

            let mut sec_routes = Vec::new();
            let mut sec_route_stops = Vec::new();
            let mut sec_route_trips = Vec::new();
            let mut sec_route_stop_shape = Vec::new();
            let mut sec_route_shape_idx = Vec::new();
            let mut shape_bytes: Vec<u8> = Vec::new();
            // Deduplicated by encoded body, standing in for the real writer's
            // dedup by `shape_id`.
            let mut shape_offsets: HashMap<Vec<u8>, u32> = HashMap::new();
            let mut stop_routes: Vec<Vec<(u32, u32)>> = vec![Vec::new(); n_stops];
            let mut trip_total = 0usize;

            for (ridx, r) in self.routes.iter().enumerate() {
                let first_route_stop = (sec_route_stops.len() / 4) as u32;
                for (pos, &s) in r.pattern.iter().enumerate() {
                    u32b(&mut sec_route_stops, s);
                    let vertex = r
                        .shape
                        .as_ref()
                        .and_then(|sh| sh.stop_vertices.get(pos).copied())
                        .unwrap_or(NONE);
                    u32b(&mut sec_route_stop_shape, vertex);
                    let sr = &mut stop_routes[s as usize];
                    if !sr.iter().any(|&(rr, _)| rr == ridx as u32) {
                        sr.push((ridx as u32, pos as u32));
                    }
                }
                let shape_off = match &r.shape {
                    None => NONE,
                    Some(sh) => {
                        let mut body = Vec::new();
                        u32b(&mut body, sh.points.len() as u32);
                        let (mut plat, mut plon) = (0i64, 0i64);
                        for &(lat, lon) in &sh.points {
                            let lat_e7 = (lat * 1e7).round() as i64;
                            let lon_e7 = (lon * 1e7).round() as i64;
                            zigzag_varint(&mut body, lat_e7 - plat);
                            zigzag_varint(&mut body, lon_e7 - plon);
                            plat = lat_e7;
                            plon = lon_e7;
                        }
                        match shape_offsets.get(&body) {
                            Some(&off) => off,
                            None => {
                                let off = shape_bytes.len() as u32;
                                shape_bytes.extend_from_slice(&body);
                                shape_offsets.insert(body, off);
                                off
                            }
                        }
                    }
                };
                u32b(&mut sec_route_shape_idx, shape_off);
                let trips_off = sec_route_trips.len() as u32;
                let mut ordered: Vec<&Trip> = r.trips.iter().collect();
                ordered.sort_by_key(|t| t.start);
                let mut prev_start = 0u32;
                for t in &ordered {
                    let pid = intern_profile(&t.stoptimes);
                    uvarint(&mut sec_route_trips, t.start.saturating_sub(prev_start) as u64);
                    uvarint(&mut sec_route_trips, pid as u64);
                    uvarint(&mut sec_route_trips, t.service as u64);
                    let h = pool.intern(t.headsign);
                    uvarint(&mut sec_route_trips, h as u64);
                    prev_start = t.start;
                    trip_total += 1;
                }
                let name_off = pool.intern(r.name);
                u32b(&mut sec_routes, name_off);
                u32b(&mut sec_routes, r.color);
                u32b(&mut sec_routes, r.route_type);
                u32b(&mut sec_routes, r.feed);
                u32b(&mut sec_routes, r.pattern.len() as u32);
                u32b(&mut sec_routes, first_route_stop);
                u32b(&mut sec_routes, ordered.len() as u32);
                u32b(&mut sec_routes, trips_off);
            }

            let mut sec_profiles_idx = Vec::new();
            for &o in &profile_offsets {
                u32b(&mut sec_profiles_idx, o);
            }
            u32b(&mut sec_profiles_idx, profile_bytes.len() as u32);

            let mut sec_stop_routes = Vec::new();
            let mut sec_stop_routes_idx = Vec::new();
            let mut sec_stop_route_pos = Vec::new();
            let mut acc = 0u32;
            for sr in &stop_routes {
                u32b(&mut sec_stop_routes_idx, acc);
                for &(r, pos) in sr {
                    u32b(&mut sec_stop_routes, r);
                    u32b(&mut sec_stop_route_pos, pos);
                }
                acc += sr.len() as u32;
            }
            u32b(&mut sec_stop_routes_idx, acc);

            // Footpath transfers: every pair within MAX_TRANSFER_M.
            let mut sec_transfers = Vec::new();
            let mut sec_transfers_idx = Vec::new();
            let mut tacc = 0u32;
            for i in 0..n_stops {
                u32b(&mut sec_transfers_idx, tacc);
                for j in 0..n_stops {
                    if i == j {
                        continue;
                    }
                    let d = dist_m(
                        self.stops[i].lat,
                        self.stops[i].lon,
                        self.stops[j].lat,
                        self.stops[j].lon,
                    );
                    if d <= MAX_TRANSFER_M {
                        u32b(&mut sec_transfers, j as u32);
                        u32b(&mut sec_transfers, (d / WALK_SPEED_M_S).ceil() as u32);
                        tacc += 1;
                    }
                }
            }
            u32b(&mut sec_transfers_idx, tacc);

            let mut sec_services = Vec::new();
            for s in &self.services {
                sec_services.push(s.mask);
                sec_services.extend_from_slice(&[0u8, 0, 0]);
                u32b(&mut sec_services, s.start);
                u32b(&mut sec_services, s.end);
            }

            // Sorted by (service, date) with a CSR index, as v3 requires.
            let mut exc = self.exceptions.clone();
            exc.sort_by_key(|&(s, d, _)| (s, d));
            let mut sec_exceptions = Vec::new();
            let mut sec_exceptions_idx = Vec::new();
            let mut eacc = 0u32;
            let mut ei = 0usize;
            for s in 0..self.services.len() as u32 {
                u32b(&mut sec_exceptions_idx, eacc);
                while exc.get(ei).is_some_and(|&(sidx, _, _)| sidx == s) {
                    let (sidx, date, added) = exc[ei];
                    u32b(&mut sec_exceptions, sidx);
                    u32b(&mut sec_exceptions, date);
                    u32b(&mut sec_exceptions, added);
                    eacc += 1;
                    ei += 1;
                }
            }
            u32b(&mut sec_exceptions_idx, eacc);

            // Sparse grid, keyed by cell id, ascending.
            let cell_col = |lon: i32| ((lon as i64 - min_lon as i64) / GRID_CELL_E7 as i64).max(0);
            let cell_row = |lat: i32| ((lat as i64 - min_lat as i64) / GRID_CELL_E7 as i64).max(0);
            let grid_cols = (cell_col(max_lon) + 1) as u32;
            let grid_rows = (cell_row(max_lat) + 1) as u32;
            let mut grid: HashMap<u32, Vec<u32>> = HashMap::new();
            for i in 0..n_stops {
                let cid = cell_row(lat_e7[i]) as u32 * grid_cols + cell_col(lon_e7[i]) as u32;
                grid.entry(cid).or_default().push(i as u32);
            }
            let mut cell_ids: Vec<u32> = grid.keys().copied().collect();
            cell_ids.sort_unstable();
            let mut sec_grid_cell_ids = Vec::new();
            let mut sec_grid_cell_off = Vec::new();
            let mut sec_grid_stops = Vec::new();
            let mut gacc = 0u32;
            for &cid in &cell_ids {
                u32b(&mut sec_grid_cell_ids, cid);
                u32b(&mut sec_grid_cell_off, gacc);
                for &s in &grid[&cid] {
                    u32b(&mut sec_grid_stops, s);
                }
                gacc += grid[&cid].len() as u32;
            }
            u32b(&mut sec_grid_cell_off, gacc);

            let mut sec_feeds = Vec::new();
            let mut sec_feed_tz = Vec::new();
            let mut sec_feed_motis_prefix = Vec::new();
            for &(name, tz, motis) in &self.feeds {
                let n = pool.intern(name);
                let t = pool.intern(tz);
                let m = pool.intern(motis);
                u32b(&mut sec_feeds, n);
                u32b(&mut sec_feed_tz, t);
                u32b(&mut sec_feed_motis_prefix, m);
            }

            let mut sections: Vec<&[u8]> = vec![
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
                &sec_feed_tz,
                &sec_exceptions_idx,
                &sec_stop_route_pos,
            ];
            // The shape sections exist only from v4, so a v3 blob is byte-identical
            // to what the previous format produced.
            if version >= 4 {
                u32b(&mut sec_route_shape_idx, shape_bytes.len() as u32);
                sections.push(&shape_bytes);
                sections.push(&sec_route_shape_idx);
                sections.push(&sec_route_stop_shape);
            }
            // Likewise the MOTIS id sections exist only from v5.
            if version >= 5 {
                sections.push(&sec_feed_motis_prefix);
                sections.push(&sec_stop_gtfs_id);
            }
            let section_count = sections.len();

            let align = |o: usize| (o + 7) & !7;
            let mut data_off = HEADER_LEN + section_count * 16;
            let mut dir: Vec<(u64, u64)> = Vec::new();
            for s in sections.iter() {
                data_off = align(data_off);
                dir.push((data_off as u64, s.len() as u64));
                data_off += s.len();
            }

            let mut out = Vec::with_capacity(data_off);
            u32b(&mut out, MAGIC);
            u32b(&mut out, version);
            u32b(&mut out, section_count as u32);
            u32b(&mut out, n_stops as u32);
            u32b(&mut out, self.routes.len() as u32);
            u32b(&mut out, trip_total as u32);
            u32b(&mut out, self.services.len() as u32);
            u32b(&mut out, profile_offsets.len() as u32);
            u32b(&mut out, self.feeds.len() as u32);
            u32b(&mut out, cell_ids.len() as u32);
            u32b(&mut out, pack_name_off);
            i32b(&mut out, min_lat);
            i32b(&mut out, min_lon);
            i32b(&mut out, max_lat);
            i32b(&mut out, max_lon);
            i32b(&mut out, min_lat);
            i32b(&mut out, min_lon);
            u32b(&mut out, GRID_CELL_E7);
            u32b(&mut out, grid_cols);
            u32b(&mut out, grid_rows);
            assert_eq!(out.len(), HEADER_LEN);
            for (off, len) in &dir {
                out.extend_from_slice(&off.to_le_bytes());
                out.extend_from_slice(&len.to_le_bytes());
            }
            for s in sections.iter() {
                out.resize(align(out.len()), 0);
                out.extend_from_slice(s);
            }
            out
        }

        fn index(&self) -> TransitIndex {
            TransitIndex::from_bytes(self.build_with_version(VERSION)).expect("index loads")
        }
    }

    /// Weekdays Mon-Fri, all of 2024.
    fn weekdays() -> Service {
        Service { mask: 0b0011_1111 & 0b0001_1111, start: 20_240_101, end: 20_241_231 }
    }

    /// Three stops ~1 km apart on one route, with an 08:00 and an 09:00 trip.
    fn one_route_pack() -> Pack {
        Pack {
            stops: vec![
                Stop { lat: 37.700, lon: -122.400, name: "Alpha", code: "A1", gtfs_id: "901201" },
                Stop { lat: 37.710, lon: -122.400, name: "Beta", code: "B2", gtfs_id: "901202" },
                Stop { lat: 37.720, lon: -122.400, name: "Gamma", code: "C3", gtfs_id: "901203" },
            ],
            routes: vec![Route {
                name: "N",
                color: 0x0000FF,
                route_type: 0,
                feed: 0,
                pattern: vec![0, 1, 2],
                trips: vec![
                    Trip {
                        start: 28_800,
                        stoptimes: vec![(28_800, 28_800), (29_100, 29_160), (29_400, 29_400)],
                        service: 0,
                        headsign: "Downtown",
                    },
                    Trip {
                        start: 32_400,
                        stoptimes: vec![(32_400, 32_400), (32_700, 32_760), (33_000, 33_000)],
                        service: 0,
                        headsign: "Downtown",
                    },
                ],
                shape: None,
            }],
            services: vec![weekdays()],
            exceptions: Vec::new(),
            feeds: vec![("sfmuni", "America/Los_Angeles", "us-ca-SFMTA")],
        }
    }

    /// [`one_route_pack`] plus a `shapes.txt` polyline that detours east between
    /// each pair of stops, so shaped geometry is distinguishable from a straight
    /// line through the stops by both vertex count and length.
    fn shaped_route_pack() -> Pack {
        let mut pack = one_route_pack();
        pack.routes[0].shape = Some(Shape {
            points: vec![
                (37.700, -122.400),
                (37.705, -122.390),
                (37.710, -122.400),
                (37.715, -122.390),
                (37.720, -122.400),
            ],
            stop_vertices: vec![0, 2, 4],
        });
        pack
    }

    /// 2024-01-03 was a Wednesday.
    fn wednesday() -> QueryDay {
        QueryDay {
            weekday: 2,
            date: 20_240_103,
            prev_weekday: 1,
            prev_date: 20_240_102,
        }
    }

    fn sched(day: QueryDay) -> Schedule<'static> {
        Schedule { day, overlay: None }
    }

    #[test]
    fn plans_a_ride_between_two_stops() {
        let pack = one_route_pack();
        let idx = pack.index();
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");

        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.name, "N");
        assert_eq!(ride.headsign, "Downtown");
        assert_eq!(ride.route_color, 0x0000FF);
        assert_eq!(ride.feed, "sfmuni");
        // Stop *names*, not codes — the UI presents these as names.
        assert_eq!(ride.from_stop, "Alpha");
        assert_eq!(ride.to_stop, "Gamma");
        // Ready at 25000 < 28800, so it takes the 08:00 trip.
        assert_eq!(ride.dep_secs, 28_800);
        assert_eq!(ride.arr_secs, 29_400);
        assert_eq!(ride.stop_count, 2);
    }

    #[test]
    fn emits_a_wait_leg_before_a_ride() {
        let pack = one_route_pack();
        let idx = pack.index();
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let wait = legs.iter().find(|l| l.kind == LegKind::Wait).expect("a wait leg");
        // No access-walk leg is emitted here (the origin is on the stop), so the
        // wait covers the whole idle period from the query time.
        assert_eq!(wait.dep_secs, 25_000);
        assert_eq!(wait.arr_secs, 28_800);
        assert_eq!(wait.name, "N");
    }

    #[test]
    fn a_sub_minute_wait_is_not_its_own_step() {
        let mut pack = one_route_pack();
        // Departs ~30 s after the traveller is ready: real, but not worth a step.
        pack.routes[0].trips = vec![Trip {
            start: 25_030,
            stoptimes: vec![(25_030, 25_030), (25_330, 25_330), (25_630, 25_630)],
            service: 0,
            headsign: "Downtown",
        }];
        let idx = pack.index();
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        assert!(legs.iter().any(|l| l.kind == LegKind::Ride));
        assert!(
            !legs.iter().any(|l| l.kind == LegKind::Wait),
            "a sub-MIN_WAIT_SECS wait must not become its own step"
        );
    }

    #[test]
    fn walk_legs_carry_their_timings() {
        let pack = one_route_pack();
        let idx = pack.index();
        // Offset from the stops so there is a real access and egress walk.
        let legs = plan(&idx, 37.6994, -122.4004, 37.7206, -122.4004, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let walks: Vec<&TransitLeg> = legs.iter().filter(|l| l.kind == LegKind::Walk).collect();
        assert_eq!(walks.len(), 2, "an access and an egress walk");
        for leg in walks {
            assert!(leg.arr_secs > leg.dep_secs, "walk leg has no timing");
            assert!(leg.dep_secs > 0, "walk leg departs at 0");
        }
    }

    #[test]
    fn finds_a_trip_stored_past_midnight() {
        let mut pack = one_route_pack();
        // A single 24:30:00 trip: GTFS files it under the previous service day.
        pack.routes[0].trips = vec![Trip {
            start: 88_200,
            stoptimes: vec![(88_200, 88_200), (88_500, 88_500), (88_800, 88_800)],
            service: 0,
            headsign: "Owl",
        }];
        let idx = pack.index();

        // 00:20 on Thursday: the trip belongs to Wednesday's service day and runs
        // at 00:30 in Thursday's frame.
        let day = QueryDay {
            weekday: 3,
            date: 20_240_104,
            prev_weekday: 2,
            prev_date: 20_240_103,
        };
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 1_200, sched(day))
            .expect("the 24:30 trip is reachable at 00:20");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.dep_secs, 1_800, "shifted into the query day's frame");
        assert_eq!(ride.arr_secs, 2_400);
    }

    #[test]
    fn a_calendar_dates_removal_cancels_the_day() {
        let mut pack = one_route_pack();
        // exception_type 2 (removed) for the query date.
        pack.exceptions = vec![(0, 20_240_103, 0)];
        let idx = pack.index();
        assert!(
            plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday())).is_none(),
            "service removed for this date, so no journey"
        );
        // The neighbouring date is unaffected — the CSR range is date-keyed.
        let thursday = QueryDay {
            weekday: 3,
            date: 20_240_104,
            prev_weekday: 2,
            prev_date: 20_240_103,
        };
        assert!(plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(thursday)).is_some());
    }

    #[test]
    fn a_calendar_dates_addition_runs_off_schedule() {
        let mut pack = one_route_pack();
        // Saturday is not in the weekday mask, but an exception adds it.
        pack.exceptions = vec![(0, 20_240_106, 1)];
        let idx = pack.index();
        let saturday = QueryDay {
            weekday: 5,
            date: 20_240_106,
            prev_weekday: 4,
            prev_date: 20_240_105,
        };
        assert!(plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(saturday)).is_some());
    }

    #[test]
    fn the_overlay_skips_a_cancelled_trip() {
        let pack = one_route_pack();
        let idx = pack.index();
        // Cancel the 08:00 departure from Alpha (route 0, stop position 0).
        let overlay = DelayOverlay::build(
            &idx,
            &[DelayEntry {
                lat: 37.700,
                lon: -122.400,
                route_name: "N".to_string(),
                sched_secs: 28_800,
                delay_secs: 0,
                cancelled: true,
            }],
        );
        let legs = plan(
            &idx,
            37.700,
            -122.400,
            37.720,
            -122.400,
            25_000,
            Schedule { day: wednesday(), overlay: Some(&overlay) },
        )
        .expect("the 09:00 trip is still available");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.dep_secs, 32_400, "fell through to the 09:00 trip");
    }

    #[test]
    fn the_overlay_delays_a_trip() {
        let pack = one_route_pack();
        let idx = pack.index();
        let overlay = DelayOverlay::build(
            &idx,
            &[DelayEntry {
                lat: 37.700,
                lon: -122.400,
                route_name: "N".to_string(),
                sched_secs: 28_800,
                delay_secs: 300,
                cancelled: false,
            }],
        );
        let legs = plan(
            &idx,
            37.700,
            -122.400,
            37.720,
            -122.400,
            25_000,
            Schedule { day: wednesday(), overlay: Some(&overlay) },
        )
        .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        // The delay propagates to every downstream time on that trip.
        assert_eq!(ride.dep_secs, 29_100);
        assert_eq!(ride.arr_secs, 29_700);
    }

    #[test]
    fn an_unmatched_overlay_entry_leaves_the_schedule_alone() {
        let pack = one_route_pack();
        let idx = pack.index();
        // Right stop, wrong route name -> must not be applied.
        let overlay = DelayOverlay::build(
            &idx,
            &[DelayEntry {
                lat: 37.700,
                lon: -122.400,
                route_name: "38R".to_string(),
                sched_secs: 28_800,
                delay_secs: 600,
                cancelled: false,
            }],
        );
        let legs = plan(
            &idx,
            37.700,
            -122.400,
            37.720,
            -122.400,
            25_000,
            Schedule { day: wednesday(), overlay: Some(&overlay) },
        )
        .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.dep_secs, 28_800);
    }

    #[test]
    fn an_early_running_trip_still_wins_despite_the_scan_break() {
        // Regression: the scan breaks once start_time passes best_dep, which is
        // only sound if realtime can't pull a departure *earlier*. A vehicle
        // running early has a negative delay, so the bound must be widened.
        let mut pack = one_route_pack();
        pack.routes[0].trips.push(Trip {
            start: 36_000,
            stoptimes: vec![(36_000, 36_000), (36_300, 36_360), (36_600, 36_600)],
            service: 0,
            headsign: "Downtown",
        });
        let idx = pack.index();
        // The 10:00 trip is running 80 min early, so it actually leaves at 08:40 —
        // after the 08:00 trip has gone, but before the scheduled 09:00 one.
        let overlay = DelayOverlay::build(
            &idx,
            &[DelayEntry {
                lat: 37.700,
                lon: -122.400,
                route_name: "N".to_string(),
                sched_secs: 36_000,
                delay_secs: -4_800,
                cancelled: false,
            }],
        );
        let legs = plan(
            &idx,
            37.700,
            -122.400,
            37.720,
            -122.400,
            30_600,
            Schedule { day: wednesday(), overlay: Some(&overlay) },
        )
        .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(
            ride.dep_secs, 31_200,
            "the early-running 10:00 trip departs at 08:40, beating the scheduled 09:00"
        );
    }

    #[test]
    fn an_overnight_trip_survives_the_previous_day_scan_break() {
        // The previous-day pass used to be exempt from the break, making it an
        // unconditional scan of the route. It now breaks on a day-shifted bound, so
        // the earliest overnight trip must still win with later ones present.
        let mut pack = one_route_pack();
        pack.routes[0].trips = vec![
            Trip {
                start: 88_200, // 24:30 -> 00:30 in the query day
                stoptimes: vec![(88_200, 88_200), (88_500, 88_500), (88_800, 88_800)],
                service: 0,
                headsign: "Owl",
            },
            Trip {
                start: 91_800, // 25:30 -> 01:30
                stoptimes: vec![(91_800, 91_800), (92_100, 92_100), (92_400, 92_400)],
                service: 0,
                headsign: "Owl",
            },
            Trip {
                start: 95_400, // 26:30 -> 02:30
                stoptimes: vec![(95_400, 95_400), (95_700, 95_700), (96_000, 96_000)],
                service: 0,
                headsign: "Owl",
            },
        ];
        let idx = pack.index();
        let day = QueryDay {
            weekday: 3,
            date: 20_240_104,
            prev_weekday: 2,
            prev_date: 20_240_103,
        };
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 1_200, sched(day))
            .expect("the 24:30 trip is reachable at 00:20");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.dep_secs, 1_800, "the first overnight trip, not a later one");
    }

    #[test]
    fn a_later_overnight_trip_is_found_once_the_first_has_gone() {
        // The break only applies once a candidate exists, so a query that misses
        // the earliest overnight trip must keep scanning to the next one rather
        // than stopping at the day-shifted bound.
        let mut pack = one_route_pack();
        pack.routes[0].trips = vec![
            Trip {
                start: 88_200, // departs 00:30, already gone at 01:00
                stoptimes: vec![(88_200, 88_200), (88_500, 88_500), (88_800, 88_800)],
                service: 0,
                headsign: "Owl",
            },
            Trip {
                start: 91_800, // departs 01:30
                stoptimes: vec![(91_800, 91_800), (92_100, 92_100), (92_400, 92_400)],
                service: 0,
                headsign: "Owl",
            },
        ];
        let idx = pack.index();
        let day = QueryDay {
            weekday: 3,
            date: 20_240_104,
            prev_weekday: 2,
            prev_date: 20_240_103,
        };
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 3_600, sched(day))
            .expect("the 25:30 trip is still catchable at 01:00");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.dep_secs, 5_400, "the 25:30 trip, shifted into the query day");
    }

    /// Two routes reaching the destination: a direct slow one found in round 1, and
    /// a two-leg faster one that only completes in round 2. Target pruning cuts the
    /// search against the best destination arrival so far, so this pins that it
    /// still admits a genuine later improvement instead of settling for the direct
    /// route.
    #[test]
    fn target_pruning_still_admits_a_faster_journey_found_in_a_later_round() {
        let pack = Pack {
            stops: vec![
                Stop { lat: 37.700, lon: -122.400, name: "Alpha", code: "A1", gtfs_id: "901201" },
                Stop { lat: 37.710, lon: -122.400, name: "Beta", code: "B2", gtfs_id: "901202" },
                Stop { lat: 37.720, lon: -122.400, name: "Gamma", code: "C3", gtfs_id: "901203" },
            ],
            routes: vec![
                Route {
                    name: "Slow",
                    color: 0x0000FF,
                    route_type: 0,
                    feed: 0,
                    pattern: vec![0, 2],
                    trips: vec![Trip {
                        start: 28_800,
                        stoptimes: vec![(28_800, 28_800), (36_000, 36_000)],
                        service: 0,
                        headsign: "Direct",
                    }],
                    shape: None,
                },
                Route {
                    name: "FastA",
                    color: 0x00FF00,
                    route_type: 0,
                    feed: 0,
                    pattern: vec![0, 1],
                    trips: vec![Trip {
                        start: 28_800,
                        stoptimes: vec![(28_800, 28_800), (29_400, 29_400)],
                        service: 0,
                        headsign: "Feeder",
                    }],
                    shape: None,
                },
                Route {
                    name: "FastB",
                    color: 0xFF0000,
                    route_type: 0,
                    feed: 0,
                    pattern: vec![1, 2],
                    trips: vec![Trip {
                        start: 30_000,
                        stoptimes: vec![(30_000, 30_000), (30_600, 30_600)],
                        service: 0,
                        headsign: "Onward",
                    }],
                    shape: None,
                },
            ],
            services: vec![weekdays()],
            exceptions: Vec::new(),
            feeds: vec![("sfmuni", "America/Los_Angeles", "us-ca-SFMTA")],
        };
        let idx = pack.index();
        // 07:46, not 08:00: the sub-metre access walk rounds up to one second, which
        // would put `ready` a second past an 08:00 departure.
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 28_000, sched(wednesday()))
            .expect("a journey exists");

        let rides: Vec<&TransitLeg> = legs.iter().filter(|l| l.kind == LegKind::Ride).collect();
        assert_eq!(
            rides.iter().map(|l| l.name.as_str()).collect::<Vec<_>>(),
            vec!["FastA", "FastB"],
            "took the direct route the pruning bound was first set from"
        );
        assert_eq!(rides.last().unwrap().arr_secs, 30_600, "08:30, not the direct 10:00");
    }

    #[test]
    fn a_previous_day_trip_that_never_crosses_midnight_is_ignored() {
        // A plain 08:00 trip belongs wholly to its own service day. Swept as
        // "yesterday" it must be dropped, not clamped to 00:00:00 and offered as
        // a departure at midnight.
        let pack = one_route_pack();
        let idx = pack.index();
        let midnight = QueryDay {
            weekday: 3,
            date: 20_240_104,
            prev_weekday: 2,
            prev_date: 20_240_103,
        };
        let board = stop_departures(&idx, 37.700, -122.400, 0, sched(midnight), 10);
        assert!(
            board.iter().all(|d| d.dep_secs > 0),
            "yesterday's daytime trips must not surface at 00:00:00, got {:?}",
            board.iter().map(|d| d.dep_secs).collect::<Vec<_>>()
        );
        assert_eq!(board.len(), 2, "only today's two trips");
    }

    #[test]
    fn resolves_each_feeds_timezone_by_coordinate() {
        // Two feeds in two zones, far enough apart that the grid separates them.
        let pack = Pack {
            stops: vec![
                Stop { lat: 37.700, lon: -122.400, name: "West1", code: "W1", gtfs_id: "W001" },
                Stop { lat: 37.710, lon: -122.400, name: "West2", code: "W2", gtfs_id: "W002" },
                Stop { lat: 40.700, lon: -74.000, name: "East1", code: "E1", gtfs_id: "E001" },
                Stop { lat: 40.710, lon: -74.000, name: "East2", code: "E2", gtfs_id: "E002" },
            ],
            routes: vec![
                Route {
                    name: "N",
                    color: 0,
                    route_type: 0,
                    feed: 0,
                    pattern: vec![0, 1],
                    trips: vec![Trip {
                        start: 28_800,
                        stoptimes: vec![(28_800, 28_800), (29_400, 29_400)],
                        service: 0,
                        headsign: "West",
                    }],
                    shape: None,
                },
                Route {
                    name: "A",
                    color: 0,
                    route_type: 1,
                    feed: 1,
                    pattern: vec![2, 3],
                    trips: vec![Trip {
                        start: 28_800,
                        stoptimes: vec![(28_800, 28_800), (29_400, 29_400)],
                        service: 0,
                        headsign: "East",
                    }],
                    shape: None,
                },
            ],
            services: vec![weekdays()],
            exceptions: Vec::new(),
            feeds: vec![
                ("sfmuni", "America/Los_Angeles", "us-ca-SFMTA"),
                ("mta", "America/New_York", "us-ny-MTA"),
            ],
        };
        let idx = pack.index();
        assert_eq!(idx.timezone_at(37.700, -122.400), "America/Los_Angeles");
        assert_eq!(idx.timezone_at(40.700, -74.000), "America/New_York");
        // Nothing within range -> empty, so the caller keeps the device zone.
        assert_eq!(idx.timezone_at(0.0, 0.0), "");
    }

    #[test]
    fn rejects_a_stale_pack_version() {
        let pack = one_route_pack();
        assert!(
            TransitIndex::from_bytes(pack.build_with_version(VERSION_MIN - 1)).is_none(),
            "a v2 pack must be rejected so Kotlin falls back to MOTIS"
        );
    }

    #[test]
    fn a_v3_pack_still_parses_and_plans() {
        // The rollout guard: a device on the newest reader must keep serving
        // offline transit from a v3 pack rather than rejecting it.
        let pack = shaped_route_pack();
        let idx = TransitIndex::from_bytes(pack.build_with_version(VERSION_MIN))
            .expect("a v3 pack still loads");
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(
            ride.coords.len(),
            6,
            "v3 carries no shape sections, so the ride draws one vertex per stop"
        );
    }

    #[test]
    fn a_v5_pack_composes_motis_stop_ids() {
        let idx = one_route_pack().index();
        // <feed prefix>_<raw gtfs stop_id>, the id `/stoptimes` expects. Note it is
        // the stop_id and not `code` ("A1"), which is a different GTFS column.
        assert_eq!(idx.motis_stop_id(0).as_deref(), Some("us-ca-SFMTA_901201"));
        assert_eq!(idx.motis_stop_id(2).as_deref(), Some("us-ca-SFMTA_901203"));
        // Out of range rather than a panic or a bogus id.
        assert_eq!(idx.motis_stop_id(99), None);
    }

    #[test]
    fn pre_v5_packs_report_no_motis_stop_id() {
        // The two id sections are absent, so the accessor must degrade to None
        // instead of reading whatever follows the shape sections.
        let pack = one_route_pack();
        for version in [VERSION_MIN, 4] {
            let idx = TransitIndex::from_bytes(pack.build_with_version(version))
                .expect("an older pack still loads");
            assert_eq!(idx.motis_stop_id(0), None, "v{version} carries no MOTIS ids");
        }
    }

    #[test]
    fn a_feed_with_no_known_prefix_reports_no_motis_stop_id() {
        // build_world_transit.sh mangles feed names, so its packs cannot name a
        // Transitous source; those stops must simply have no id.
        let mut pack = one_route_pack();
        pack.feeds = vec![("sfmuni", "America/Los_Angeles", "")];
        let idx = pack.index();
        assert_eq!(idx.motis_stop_id(0), None);
    }

    #[test]
    fn a_ride_leg_follows_the_gtfs_shape() {
        let pack = shaped_route_pack();
        let idx = pack.index();
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");

        assert_eq!(ride.coords.len(), 10, "all five shape vertices, as [lon, lat] pairs");
        let lons: Vec<f64> = ride.coords.chunks(2).map(|c| c[0]).collect();
        assert!(
            lons.iter().any(|&lon| (lon - -122.390).abs() < 1e-9),
            "the eastward detour vertices are drawn, got {lons:?}"
        );
        // Stop-to-stop is 0.02 deg of latitude; the detour must be longer.
        let crow = dist_m(37.700, -122.400, 37.720, -122.400);
        assert!(
            ride.dist_m > crow,
            "shape distance {} must exceed the crow-flies {crow}",
            ride.dist_m
        );
        assert_eq!(ride.stop_count, 2, "stop_count still counts stops, not vertices");
    }

    #[test]
    fn a_ride_leg_slices_the_shape_to_the_boarded_span() {
        let pack = shaped_route_pack();
        let idx = pack.index();
        // Alpha -> Beta only: vertices 0..=2, not the whole polyline.
        let legs = plan(&idx, 37.700, -122.400, 37.710, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.coords.len(), 6, "vertices 0, 1, 2");
        assert!((ride.coords[0] - -122.400).abs() < 1e-9);
        assert!((ride.coords[1] - 37.700).abs() < 1e-9);
        assert!((ride.coords[4] - -122.400).abs() < 1e-9);
        assert!((ride.coords[5] - 37.710).abs() < 1e-9);
    }

    #[test]
    fn a_route_without_a_shape_draws_stop_to_stop() {
        // A v4 pack whose feed had no usable shape for this route: NONE offsets,
        // and the leg falls back to one vertex per stop.
        let pack = one_route_pack();
        let idx = pack.index();
        assert!(idx.route_shape_off(0).is_none());
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.coords.len(), 6, "Alpha, Beta, Gamma");
        let crow = dist_m(37.700, -122.400, 37.720, -122.400);
        assert!((ride.dist_m - crow).abs() < 1.0);
    }

    #[test]
    fn routes_sharing_a_shape_share_one_blob() {
        let mut pack = shaped_route_pack();
        let twin = Route {
            name: "N-express",
            color: 0,
            route_type: 0,
            feed: 0,
            pattern: vec![0, 2],
            trips: vec![Trip {
                start: 28_800,
                stoptimes: vec![(28_800, 28_800), (29_400, 29_400)],
                service: 0,
                headsign: "Downtown",
            }],
            shape: pack.routes[0].shape.take().map(|sh| Shape {
                points: sh.points.clone(),
                stop_vertices: vec![0, 4],
            }),
        };
        pack.routes[0].shape = twin.shape.as_ref().map(|sh| Shape {
            points: sh.points.clone(),
            stop_vertices: vec![0, 2, 4],
        });
        pack.routes.push(twin);
        let idx = pack.index();
        assert_eq!(
            idx.route_shape_off(0),
            idx.route_shape_off(1),
            "identical polylines must be stored once"
        );
        assert!(idx.route_shape_off(0).is_some());
    }

    #[test]
    fn stop_route_pos_matches_the_route_pattern() {
        // A loop route visiting a stop twice: STOP_ROUTE_POS must record the
        // FIRST occurrence, matching the scan it replaced.
        let mut pack = one_route_pack();
        pack.routes[0].pattern = vec![0, 1, 2, 1];
        pack.routes[0].trips = vec![Trip {
            start: 28_800,
            stoptimes: vec![
                (28_800, 28_800),
                (29_100, 29_100),
                (29_400, 29_400),
                (29_700, 29_700),
            ],
            service: 0,
            headsign: "Loop",
        }];
        let idx = pack.index();
        let (s, e) = idx.stop_routes_range(1);
        assert_eq!(e - s, 1, "one route serves stop 1");
        assert_eq!(idx.stop_route_pos(s), 1, "first occurrence, not the last");
    }

    #[test]
    fn departure_board_lists_upcoming_departures_with_realtime() {
        let pack = one_route_pack();
        let idx = pack.index();
        let board = stop_departures(&idx, 37.700, -122.400, 25_000, sched(wednesday()), 10);
        assert_eq!(board.len(), 2, "both trips are upcoming");
        assert_eq!(board[0].dep_secs, 28_800);
        assert_eq!(board[0].route_name, "N");
        assert_eq!(board[0].headsign, "Downtown");
        assert!(!board[0].real_time, "no overlay, so scheduled only");
        assert_eq!(board[0].delay_secs, 0);

        let overlay = DelayOverlay::build(
            &idx,
            &[DelayEntry {
                lat: 37.700,
                lon: -122.400,
                route_name: "N".to_string(),
                sched_secs: 28_800,
                delay_secs: 120,
                cancelled: false,
            }],
        );
        let live = stop_departures(
            &idx,
            37.700,
            -122.400,
            25_000,
            Schedule { day: wednesday(), overlay: Some(&overlay) },
            10,
        );
        let delayed_dep = live.iter().find(|d| d.dep_secs == 28_800).expect("the 08:00 trip");
        assert!(delayed_dep.real_time);
        assert_eq!(delayed_dep.delay_secs, 120);
        assert!(!delayed_dep.cancelled);
    }

    #[test]
    fn departure_board_excludes_the_terminus() {
        let pack = one_route_pack();
        let idx = pack.index();
        // Gamma is the last stop on the only route, so nothing departs from it.
        assert!(stop_departures(&idx, 37.720, -122.400, 0, sched(wednesday()), 10).is_empty());
    }

    #[test]
    fn returns_none_when_nothing_runs_on_the_query_day() {
        let pack = one_route_pack();
        let idx = pack.index();
        // Sunday is not in the weekday mask and there is no exception.
        let sunday = QueryDay {
            weekday: 6,
            date: 20_240_107,
            prev_weekday: 5,
            prev_date: 20_240_106,
        };
        assert!(plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(sunday)).is_none());
    }

    /// The hand-built harness above is an independent *reimplementation* of the
    /// writer, which is what makes it a useful cross-check — but it also means both
    /// sides could drift from the real producer together. This reads a pack
    /// actually emitted by `scripts/maps/gtfs_ingest`, so the two crates are pinned
    /// to each other rather than to a shared assumption.
    ///
    /// Regenerate after any format change (from the repo root):
    ///
    /// ```text
    /// cargo run --release --manifest-path scripts/maps/gtfs_ingest/Cargo.toml -- \
    ///     maps/src/main/rust/test_fixtures mini \
    ///     mini=scripts/maps/gtfs_ingest/test_fixtures/mini_feed
    /// ```
    ///
    /// then delete the `mini.transit.json` manifest it writes alongside. The bytes
    /// are a captured artefact, not a reproducible one: the ingester groups trips
    /// out of a `HashMap`, so route order varies between runs. The assertions are
    /// therefore semantic, which is what catches drift anyway.
    #[test]
    fn reads_a_pack_written_by_the_real_ingester() {
        let bytes = include_bytes!("../test_fixtures/mini.transit").to_vec();
        let idx = TransitIndex::from_bytes(bytes).expect("the committed fixture parses");

        // The fixture feed: 3 stops on one route, two trips, a shape detouring east
        // between each pair of stops.
        assert_eq!(idx.stop_count, 3);
        assert_eq!(idx.route_count, 1);
        assert_eq!(idx.timezone_at(37.700, -122.400), "America/Los_Angeles");

        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.name, "N");
        assert_eq!(ride.feed, "mini");
        assert_eq!(ride.from_stop, "Alpha");
        assert_eq!(ride.to_stop, "Gamma");
        assert_eq!(ride.dep_secs, 28_800);
        assert_eq!(ride.arr_secs, 29_400);
        assert_eq!(ride.stop_count, 2);

        // The v4 sections the ingester wrote must decode into real geometry: more
        // vertices than the three stops, and a longer path than the straight line.
        assert!(
            ride.coords.len() > 6,
            "expected shape geometry, got {} coords",
            ride.coords.len()
        );
        let lons: Vec<f64> = ride.coords.chunks(2).map(|c| c[0]).collect();
        assert!(
            lons.iter().any(|&lon| lon > -122.395),
            "the eastward detour is missing: {lons:?}"
        );
        let crow = dist_m(37.700, -122.400, 37.720, -122.400);
        assert!(ride.dist_m > crow, "shape distance {} must exceed {crow}", ride.dist_m);
    }
}
