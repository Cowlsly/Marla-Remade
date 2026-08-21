//! Build the compact **world** transit index from parsed GTFS tables (possibly
//! merged from many feeds) and serialize it to the on-disk `.transit` format.
//!
//! ON-DISK FORMAT v5 ("TRX2", little-endian, mmap-friendly, read via
//! `read_unaligned` on device). THIS LAYOUT MUST STAY IN SYNC WITH
//! `maps/src/main/rust/src/transit.rs`.
//!
//! v5 is v4 plus two purely additive sections (23-24) that let the device name a
//! stop in Transitous/MOTIS's own id space without a network round-trip, which is
//! what keeps the realtime `/stoptimes` overlay working now that the
//! `/map/stops` coordinate-to-id lookup is gone. A MOTIS stop id is
//! `<registry-file>-<source name>_<gtfs stop_id>`, so it is composed on device
//! from a per-feed prefix and the raw `stop_id`. They are parallel `u32` STRINGS
//! offsets rather than new `StopRec` fields because `StopRec` is a packed 16-byte
//! stride indexed by arithmetic — widening it would invalidate every older pack.
//!
//! v4 is v3 plus three purely additive sections (20-22) carrying GTFS
//! `shapes.txt` geometry, so a ride leg draws the path the vehicle actually
//! takes — and reports a truthful distance — instead of a line through its
//! stops. The device reader accepts v3 and v4, so a pack rebuild and an app
//! update can land in either order.
//!
//! v3 is v2 plus three purely additive sections (17-19); sections 0-16 are
//! byte-identical. They exist so the on-device planner can be correct and fast
//! on a world pack: `FEED_TZ` lets it route in the *feed's* timezone rather than
//! the device's, `EXCEPTIONS_IDX` turns the `calendar_dates` lookup from a full
//! scan per trip into a CSR range + binary search, and `STOP_ROUTE_POS` removes
//! the per-stop linear scan over a route's stop pattern.
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
//!   u32 magic (MAGIC), u32 version (VERSION=5), u32 section_count,
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
//!                        Sorted by (service_idx, date), one row per pair.
//!  13  GRID_CELL_IDS   u32[grid_cell_count] non-empty cell ids, ascending.
//!                        cell_id = row*grid_cols + col, row/col from the header
//!                        grid origin + grid_cell_e7.
//!  14  GRID_CELL_OFF   u32[grid_cell_count + 1] prefix offsets into GRID_STOPS.
//!  15  GRID_STOPS      u32[stop_count] stop indices grouped by cell (in the
//!                        GRID_CELL_IDS order).
//!  16  FEEDS           FeedRec[feed_count] = { u32 name_off }.
//!  17  FEED_TZ         u32[feed_count] STRINGS offsets holding each feed's IANA
//!                        timezone from `agency.txt` (`agency_timezone`, row 0),
//!                        or NONE when the feed has no `agency.txt`.
//!  18  EXCEPTIONS_IDX  u32[service_count + 1] prefix offsets into EXCEPTIONS,
//!                        so one service's exceptions are a contiguous,
//!                        date-ascending range.
//!  19  STOP_ROUTE_POS  u32[] parallel to STOP_ROUTES: the position of that stop
//!                        within that route's stop pattern (first occurrence).
//!  20  SHAPE_COORDS    Concatenated polyline blobs. Each blob is `u32
//!                        point_count` then, per point, a zigzag-varint lat
//!                        delta and lon delta (1e-7 deg, cumulative from zero).
//!                        Blobs are self-delimiting and deduplicated, so routes
//!                        producing identical geometry share one.
//!  21  ROUTE_SHAPE_IDX u32[route_count + 1]. For route i, the byte offset of
//!                        its blob in SHAPE_COORDS, or NONE when the feed had no
//!                        usable shape for it. NOT a prefix sum: offsets repeat
//!                        where routes share a blob. The final entry is
//!                        SHAPE_COORDS' byte length, which the reader uses as a
//!                        bound.
//!  22  ROUTE_STOP_SHAPE u32[] parallel to ROUTE_STOPS: that pattern stop's
//!                        vertex index within its route's shape (NONE when the
//!                        route has none). Non-decreasing within a route, so the
//!                        device can slice `shape[vertex(board)..=vertex(alight)]`.
//!  23  FEED_MOTIS_PREFIX u32[feed_count] STRINGS offsets holding each feed's
//!                        Transitous id prefix (`us-ca-SF-bayarea`), or NONE when
//!                        the build did not know it. Interned, so the prefix costs
//!                        one pool entry shared by every stop in that feed.
//!  24  STOP_GTFS_ID    u32[stop_count] STRINGS offsets holding each stop's raw
//!                        GTFS `stop_id`. Joined to the prefix with `_` to form a
//!                        MOTIS stop id. Kept separate from `StopRec.code_off`,
//!                        which falls back to `stop_id` only when `stop_code` is
//!                        blank and so cannot be relied on.

use crate::gtfs;
use crate::gtfs::{parse_gtfs_date, Csv, Shape};
use crate::shapes;
use std::collections::HashMap;
use std::io::Write;
use std::path::Path;

pub const MAGIC: u32 = 0x5452_4958; // "TRIX"
pub const VERSION: u32 = 5;
/// Oldest version the device reader still accepts, mirrored from
/// `maps/src/main/rust/src/transit.rs` so the host reader agrees on the range.
pub const VERSION_MIN: u32 = 3;
pub const NONE: u32 = 0xFFFF_FFFF;
pub const SECTION_COUNT: usize = 25;
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

/// FNV-1a, 64-bit. Only ever used to *find* dedup candidates, which are then
/// confirmed byte-for-byte against the buffer that already holds them — so a
/// collision costs a few duplicated bytes and never a wrong merge.
fn fnv1a(bytes: &[u8]) -> u64 {
    fnv1a_mix(0xcbf2_9ce4_8422_2325, bytes)
}

/// Continue an FNV-1a hash, for keys built from more than one piece.
fn fnv1a_mix(mut h: u64, bytes: &[u8]) -> u64 {
    for &b in bytes {
        h ^= b as u64;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}

/// Interning string pool producing byte offsets into the STRINGS section.
///
/// The pool does **not** keep a copy of what it interns: `bytes` is the only
/// copy, and the index is `hash -> offsets`, each candidate confirmed by
/// comparing it against `bytes`. On a world pack the second copy cost several
/// gigabytes on its own.
struct StringPool {
    bytes: Vec<u8>,
    by_hash: HashMap<u64, Vec<u32>>,
    /// Set when the pool would pass the 4 GiB that a u32 STRINGS offset can
    /// address. Checked once before the pack is assembled: silently wrapping
    /// here produces a pack the device will happily mmap and mis-read.
    overflowed: bool,
}

impl StringPool {
    fn new() -> StringPool {
        // Byte 0 is a lone NUL so offset 0 is a valid empty string, keeping
        // NONE (0xFFFFFFFF) unambiguous.
        StringPool { bytes: vec![0], by_hash: HashMap::new(), overflowed: false }
    }

    fn intern(&mut self, s: &str) -> u32 {
        if s.is_empty() {
            return NONE;
        }
        let h = fnv1a(s.as_bytes());
        if let Some(off) =
            self.by_hash.get(&h).and_then(|offs| {
                offs.iter().copied().find(|&off| self.matches_at(off, s))
            })
        {
            return off;
        }
        // `+ 1` for the terminating NUL; the last valid offset must still be a
        // u32, and NONE is reserved.
        if self.bytes.len() + s.len() + 1 >= NONE as usize {
            self.overflowed = true;
            return NONE;
        }
        let off = self.bytes.len() as u32;
        self.bytes.extend_from_slice(s.as_bytes());
        self.bytes.push(0);
        self.by_hash.entry(h).or_default().push(off);
        off
    }

    /// Whether the interned string at `off` is exactly `s`. The NUL check is
    /// what stops `s` matching a prefix of a longer entry.
    fn matches_at(&self, off: u32, s: &str) -> bool {
        let start = off as usize;
        let end = start + s.len();
        end < self.bytes.len()
            && self.bytes[end] == 0
            && &self.bytes[start..end] == s.as_bytes()
    }
}

/// The PROFILES section plus its dedup index: run-time shapes are shared by
/// every trip that runs them, which is what removed v1's per-trip stop-time
/// table. Like [`StringPool`], the index is `hash -> ids` and holds no second
/// copy of the bodies.
struct ProfileTable {
    bytes: Vec<u8>,
    /// Byte offset into `bytes` per profile id.
    offsets: Vec<u32>,
    by_hash: HashMap<u64, Vec<u32>>,
    /// Reused across trips so encoding one does not allocate.
    scratch: Vec<u8>,
}

impl ProfileTable {
    fn new() -> ProfileTable {
        ProfileTable {
            bytes: Vec::new(),
            offsets: Vec::new(),
            by_hash: HashMap::new(),
            scratch: Vec::new(),
        }
    }

    /// Encode a trip's `(arr, dep)` sequence as a profile body and return its id,
    /// reusing an identical existing profile.
    fn intern(&mut self, sts: &[(u32, u32)]) -> Result<u32, String> {
        let n = sts.len();
        self.scratch.clear();
        write_uvarint(&mut self.scratch, n as u64);
        let (arr0, dep0) = sts[0];
        write_uvarint(&mut self.scratch, dep0.saturating_sub(arr0) as u64);
        let mut prev_dep = dep0;
        for &(arr, dep) in &sts[1..] {
            write_uvarint(&mut self.scratch, arr.saturating_sub(prev_dep) as u64);
            write_uvarint(&mut self.scratch, dep.saturating_sub(arr) as u64);
            prev_dep = dep;
        }
        let h = fnv1a(&self.scratch);
        if let Some(id) = self.by_hash.get(&h).and_then(|ids| {
            ids.iter().copied().find(|&id| {
                let start = self.offsets[id as usize] as usize;
                self.bytes[start..].starts_with(&self.scratch)
            })
        }) {
            return Ok(id);
        }
        // PROFILES_IDX entries are u32 byte offsets into PROFILES.
        let off = u32::try_from(self.bytes.len()).map_err(|_| {
            "PROFILES exceeded 4 GiB, which a u32 PROFILES_IDX offset cannot address"
                .to_string()
        })?;
        let id = self.offsets.len() as u32;
        self.offsets.push(off);
        self.bytes.extend_from_slice(&self.scratch);
        self.by_hash.entry(h).or_default().push(id);
        Ok(id)
    }
}

/// The SHAPE_COORDS section plus its dedup index, so routes producing identical
/// geometry share one blob. Blobs are self-delimiting, so a candidate is
/// confirmed by a prefix comparison at its offset.
struct ShapeBlobs {
    bytes: Vec<u8>,
    by_hash: HashMap<u64, Vec<u32>>,
}

impl ShapeBlobs {
    fn new() -> ShapeBlobs {
        ShapeBlobs { bytes: Vec::new(), by_hash: HashMap::new() }
    }

    fn intern(&mut self, blob: &[u8]) -> Result<u32, String> {
        let h = fnv1a(blob);
        if let Some(off) = self.by_hash.get(&h).and_then(|offs| {
            offs.iter().copied().find(|&off| self.bytes[off as usize..].starts_with(blob))
        }) {
            return Ok(off);
        }
        // ROUTE_SHAPE_IDX holds u32 byte offsets and reserves NONE, so the
        // section itself has to stay below that.
        if self.bytes.len() >= NONE as usize {
            return Err(
                "SHAPE_COORDS exceeded the 4 GiB a u32 ROUTE_SHAPE_IDX offset can address"
                    .to_string(),
            );
        }
        let off = self.bytes.len() as u32;
        self.bytes.extend_from_slice(blob);
        self.by_hash.entry(h).or_default().push(off);
        Ok(off)
    }
}

/// One `stop_times.txt` row reduced to what the index needs: 24 bytes, against
/// the ~400 a `Csv` row costs and the 40 an `Option<f64>` dist and `i64`
/// sequence used to. This is the hot struct — a world corpus has billions.
struct StopTime {
    seq: u32,
    stop_idx: u32,
    arr: u32,
    dep: u32,
    /// `shape_dist_traveled` in the feed's own units, or NaN when absent. An
    /// ordering key only; see [`Shape::dist`] for why it is still `f64`.
    dist: f64,
}

/// One input GTFS feed (already parsed). Multiple feeds merge into one pack;
/// their GTFS ids are namespaced by feed so they never collide.
///
/// Holding `stop_times.txt` as a [`Csv`] costs roughly 10x the bytes of the file,
/// so the host tool uses [`FeedDir`] instead and lets the builder stream it.
pub struct FeedInput<'a> {
    pub name: String,
    /// Transitous id prefix for this feed (`us-ca-SF-bayarea`), used to compose
    /// MOTIS stop ids on device. Empty when the caller does not know it (the
    /// world build mangles feed names), which writes NONE and makes the device
    /// accessor return `None`.
    pub motis_prefix: String,
    pub stops: &'a Csv,
    pub routes: &'a Csv,
    pub trips: &'a Csv,
    pub stop_times: &'a Csv,
    pub calendar: Option<&'a Csv>,
    pub calendar_dates: Option<&'a Csv>,
    /// `agency.txt`, read only for `agency_timezone`. GTFS permits several
    /// agencies per feed; we take row 0 and assume one timezone per feed.
    pub agency: Option<&'a Csv>,
    /// `shapes.txt` keyed by `shape_id`. Optional: without it every route in this
    /// feed falls back to stop-to-stop ride geometry on device.
    pub shapes: Option<&'a HashMap<String, Shape>>,
}

/// The same feed, but with `stop_times.txt` left on disk to be streamed out of
/// `dir` rather than parsed into a [`Csv`] first. Everything else is small enough
/// to hold.
pub struct FeedDir<'a> {
    pub name: String,
    pub motis_prefix: String,
    /// The unzipped GTFS directory holding `stop_times.txt`.
    pub dir: &'a Path,
    pub stops: &'a Csv,
    pub routes: &'a Csv,
    pub trips: &'a Csv,
    pub calendar: Option<&'a Csv>,
    pub calendar_dates: Option<&'a Csv>,
    pub agency: Option<&'a Csv>,
    pub shapes: Option<&'a HashMap<String, Shape>>,
}

/// Where a feed's `stop_times.txt` comes from.
enum StopTimesSource<'a> {
    Table(&'a Csv),
    Dir(&'a Path),
}

/// The parts of a feed [`IndexBuilder::add`] needs, from either input form.
struct FeedView<'a> {
    name: &'a str,
    motis_prefix: &'a str,
    stops: &'a Csv,
    routes: &'a Csv,
    trips: &'a Csv,
    calendar: Option<&'a Csv>,
    calendar_dates: Option<&'a Csv>,
    agency: Option<&'a Csv>,
    shapes: Option<&'a HashMap<String, Shape>>,
    stop_times: StopTimesSource<'a>,
}

impl<'a> From<&'a FeedInput<'a>> for FeedView<'a> {
    fn from(f: &'a FeedInput<'a>) -> FeedView<'a> {
        FeedView {
            name: &f.name,
            motis_prefix: &f.motis_prefix,
            stops: f.stops,
            routes: f.routes,
            trips: f.trips,
            calendar: f.calendar,
            calendar_dates: f.calendar_dates,
            agency: f.agency,
            shapes: f.shapes,
            stop_times: StopTimesSource::Table(f.stop_times),
        }
    }
}

impl<'a> From<&'a FeedDir<'a>> for FeedView<'a> {
    fn from(f: &'a FeedDir<'a>) -> FeedView<'a> {
        FeedView {
            name: &f.name,
            motis_prefix: &f.motis_prefix,
            stops: f.stops,
            routes: f.routes,
            trips: f.trips,
            calendar: f.calendar,
            calendar_dates: f.calendar_dates,
            agency: f.agency,
            shapes: f.shapes,
            stop_times: StopTimesSource::Dir(f.dir),
        }
    }
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
    /// Routes that got `shapes.txt` ride geometry.
    pub shaped_routes: usize,
    /// Routes whose trips disagreed on `shape_id`; the modal one was used.
    pub multi_shape_routes: usize,
    /// Routes whose shape failed validation and fell back to stop-to-stop.
    pub dropped_shape_routes: usize,
    /// Stops dropped because `stop_lat`/`stop_lon` was missing, unparseable or
    /// outside the WGS84 ranges. A non-zero count is a data-quality signal, not
    /// a build error.
    pub dropped_stops_bad_coord: usize,
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
    /// The feed's own `route_id`. Kept because grouping is by
    /// `(route_id, stop_pattern)`: two GTFS routes over the same stops are two
    /// RAPTOR routes, and `pattern_index` confirms a candidate against this.
    route_id: String,
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
    /// Index into the feed's interned shape list, or [`NONE`] when the trip
    /// references no usable shape.
    shape_key: u32,
    /// `shape_dist_traveled` per pattern stop, when every stop has one.
    stop_dists: Option<Vec<f64>>,
}

/// Build the whole pack in memory from feeds whose tables are already parsed.
/// `pack_name` is stored in the string pool and surfaced to the on-device planner
/// as a fallback.
///
/// Convenience for tests and small corpora. The host tool drives
/// [`IndexBuilder`] directly instead, one feed at a time, so it never holds more
/// than one feed's tables — or a second copy of the pack.
pub fn build_index(
    pack_name: &str,
    feeds: &[FeedInput],
) -> Result<(Vec<u8>, BuildStats), String> {
    let mut builder = IndexBuilder::new(pack_name);
    for feed in feeds {
        builder.add_feed(feed)?;
    }
    let mut blob = Vec::new();
    let stats = builder.finish_to(&mut blob)?;
    Ok((blob, stats))
}

/// One GTFS route's own fields, before its trips are grouped into patterns.
struct RouteMeta {
    name_off: u32,
    color: u32,
    route_type: u32,
}

/// One GTFS trip's own fields, before its `stop_times` are attached.
struct TripMeta {
    route_id: String,
    service_id: String,
    headsign_off: u32,
    shape_id: String,
}

/// Accumulates GTFS feeds into one merged pack.
///
/// Feeds go in one at a time through [`IndexBuilder::add_feed`] (or
/// [`IndexBuilder::add_feed_dir`], which streams `stop_times.txt`), and each call
/// both ingests a feed **and finalizes it**: its routes, trips, profiles, shapes
/// and stop→route lists are serialized before the next feed is touched, so
/// nothing per-feed survives the feed boundary. That is legal because a route
/// never references a stop outside its own feed (`stop_id_to_idx` is per-feed),
/// which `add` asserts.
///
/// What crosses a boundary is only the pack itself — the section buffers, the
/// string pool, the profile and shape tables — plus `stop_lat`/`stop_lon`,
/// services and exceptions, which the transfers, both grids and the bbox need
/// once every feed is in.
pub struct IndexBuilder {
    pool: StringPool,
    /// STRINGS offset of the pack name, for the header.
    pack_name_off: u32,

    // Merged stop coordinates, in feed order; a feed's stops are one contiguous
    // run. Kept (unlike the name/code offsets, which go straight into STOPS)
    // because transfers, the transfer grid, the spatial grid and the bbox all
    // need them, and `grid_cols` is only known once the last feed is in. 8 bytes
    // a stop is ~80-160 MB on a world pack.
    stop_lat: Vec<i32>,
    stop_lon: Vec<i32>,
    min_lat: i32,
    min_lon: i32,
    max_lat: i32,
    max_lon: i32,

    // Services, namespaced by feed, plus exceptions against the global index.
    service_key_to_idx: HashMap<String, u32>,
    svc_mask: Vec<u8>,
    svc_start: Vec<u32>,
    svc_end: Vec<u32>,
    exceptions: Vec<(u32, u32, u32)>,

    feed_name_offs: Vec<u32>,
    feed_tz_offs: Vec<u32>,
    feed_motis_prefix_offs: Vec<u32>,

    // Sections, appended feed by feed.
    sec_stops: Vec<u8>,
    sec_stop_gtfs_id: Vec<u8>,
    sec_routes: Vec<u8>,
    sec_route_stops: Vec<u8>,
    sec_route_trips: Vec<u8>,
    sec_route_shape_idx: Vec<u8>,
    sec_route_stop_shape: Vec<u8>,
    sec_stop_routes: Vec<u8>,
    sec_stop_routes_idx: Vec<u8>,
    sec_stop_route_pos: Vec<u8>,
    profiles: ProfileTable,
    shape_blobs: ShapeBlobs,
    /// Running STOP_ROUTES entry count, i.e. the next STOP_ROUTES_IDX value.
    stop_routes_total: u32,

    route_count: usize,
    trip_total: usize,
    shaped_routes: usize,
    multi_shape_routes: usize,
    dropped_shape_routes: usize,
    dropped_stops_bad_coord: usize,
}

impl IndexBuilder {
    /// `pack_name` is stored in the string pool and surfaced to the on-device
    /// planner as a fallback.
    pub fn new(pack_name: &str) -> IndexBuilder {
        let mut pool = StringPool::new();
        let pack_name_off = pool.intern(pack_name);
        IndexBuilder {
            pool,
            pack_name_off,
            stop_lat: Vec::new(),
            stop_lon: Vec::new(),
            min_lat: i32::MAX,
            min_lon: i32::MAX,
            max_lat: i32::MIN,
            max_lon: i32::MIN,
            service_key_to_idx: HashMap::new(),
            svc_mask: Vec::new(),
            svc_start: Vec::new(),
            svc_end: Vec::new(),
            exceptions: Vec::new(),
            feed_name_offs: Vec::new(),
            feed_tz_offs: Vec::new(),
            feed_motis_prefix_offs: Vec::new(),
            sec_stops: Vec::new(),
            sec_stop_gtfs_id: Vec::new(),
            sec_routes: Vec::new(),
            sec_route_stops: Vec::new(),
            sec_route_trips: Vec::new(),
            sec_route_shape_idx: Vec::new(),
            sec_route_stop_shape: Vec::new(),
            sec_stop_routes: Vec::new(),
            sec_stop_routes_idx: Vec::new(),
            sec_stop_route_pos: Vec::new(),
            profiles: ProfileTable::new(),
            shape_blobs: ShapeBlobs::new(),
            stop_routes_total: 0,
            route_count: 0,
            trip_total: 0,
            shaped_routes: 0,
            multi_shape_routes: 0,
            dropped_shape_routes: 0,
            dropped_stops_bad_coord: 0,
        }
    }

    /// Register a feed-namespaced service id, returning its global index.
    fn ensure_service(&mut self, key: &str) -> u32 {
        if let Some(&i) = self.service_key_to_idx.get(key) {
            return i;
        }
        let i = self.svc_mask.len() as u32;
        self.service_key_to_idx.insert(key.to_string(), i);
        self.svc_mask.push(0);
        self.svc_start.push(0);
        self.svc_end.push(99_999_999);
        i
    }

    /// Ingest and finalize one feed whose tables are all already parsed.
    pub fn add_feed(&mut self, feed: &FeedInput) -> Result<(), String> {
        self.add(&feed.into())
    }

    /// Ingest and finalize one feed, streaming its `stop_times.txt` off disk.
    pub fn add_feed_dir(&mut self, feed: &FeedDir) -> Result<(), String> {
        self.add(&feed.into())
    }

    /// Bytes currently held in the sections, for progress reporting: this is
    /// what the finished pack will be, so it is the number to watch.
    pub fn section_bytes(&self) -> usize {
        self.pool.bytes.len()
            + self.profiles.bytes.len()
            + self.shape_blobs.bytes.len()
            + self.sec_stops.len()
            + self.sec_stop_gtfs_id.len()
            + self.sec_routes.len()
            + self.sec_route_stops.len()
            + self.sec_route_trips.len()
            + self.sec_route_shape_idx.len()
            + self.sec_route_stop_shape.len()
            + self.sec_stop_routes.len()
            + self.sec_stop_routes_idx.len()
            + self.sec_stop_route_pos.len()
    }

    /// `(stops, routes, trips)` accumulated so far.
    pub fn counts(&self) -> (usize, usize, usize) {
        (self.stop_lat.len(), self.route_count, self.trip_total)
    }

    fn add(&mut self, feed: &FeedView) -> Result<(), String> {
        let feed_idx = self.feed_name_offs.len() as u32;
        let feed_stop_start = self.stop_lat.len() as u32;
        let feed_exc_start = self.exceptions.len();
        self.feed_name_offs.push(self.pool.intern(feed.name));
        // Per-feed IANA timezone. Dedup in the pool means one shared
        // "America/Los_Angeles" no matter how many feeds use it.
        let tz = feed
            .agency
            .and_then(|a| a.rows.first().map(|row| a.get(row, "agency_timezone").trim()))
            .unwrap_or("");
        self.feed_tz_offs.push(self.pool.intern(tz));
        // Interned once per feed, so every stop's MOTIS id shares this entry.
        self.feed_motis_prefix_offs.push(self.pool.intern(feed.motis_prefix.trim()));

        // --- Stops (this feed) -> global indices ---
        let mut stop_id_to_idx: HashMap<String, u32> = HashMap::new();
        for row in &feed.stops.rows {
            let id = feed.stops.get(row, "stop_id");
            if id.is_empty() {
                continue;
            }
            // Out-of-range coordinates are dropped, not clamped: see
            // `gtfs::parse_lat_lon` for what one bad row does to the bbox.
            let Some((lat, lon)) =
                gtfs::parse_lat_lon(feed.stops.get(row, "stop_lat"), feed.stops.get(row, "stop_lon"))
            else {
                self.dropped_stops_bad_coord += 1;
                continue;
            };
            let lat_e7 = (lat * 1e7) as i32;
            let lon_e7 = (lon * 1e7) as i32;
            let name = feed.stops.get(row, "stop_name");
            let code = feed.stops.get(row, "stop_code");
            let idx = self.stop_lat.len() as u32;
            stop_id_to_idx.insert(id.to_string(), idx);
            self.stop_lat.push(lat_e7);
            self.stop_lon.push(lon_e7);
            // StopRec goes straight into the section: keeping the name/code/id
            // offsets in parallel `Vec<u32>`s only to flatten them at the end was
            // a second copy of STOPS plus a third of STOP_GTFS_ID.
            append_i32(&mut self.sec_stops, lat_e7);
            append_i32(&mut self.sec_stops, lon_e7);
            let name_off = self.pool.intern(name);
            append_u32(&mut self.sec_stops, name_off);
            let code_off = self.pool.intern(if code.is_empty() { id } else { code });
            append_u32(&mut self.sec_stops, code_off);
            // Interned explicitly: `code_off` above falls back to `stop_id` only
            // when `stop_code` is blank, so it cannot stand in for the real id.
            let gtfs_off = self.pool.intern(id);
            append_u32(&mut self.sec_stop_gtfs_id, gtfs_off);
            self.min_lat = self.min_lat.min(lat_e7);
            self.min_lon = self.min_lon.min(lon_e7);
            self.max_lat = self.max_lat.max(lat_e7);
            self.max_lon = self.max_lon.max(lon_e7);
        }

        // --- Route metadata (this feed) ---
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
            let name_off = self.pool.intern(name);
            route_meta
                .insert(id.to_string(), RouteMeta { name_off, color, route_type: rtype });
        }

        // --- Trips (this feed) ---
        let mut trip_meta: HashMap<String, TripMeta> = HashMap::new();
        for row in &feed.trips.rows {
            let id = feed.trips.get(row, "trip_id");
            if id.is_empty() {
                continue;
            }
            let headsign_off = self.pool.intern(feed.trips.get(row, "trip_headsign"));
            trip_meta.insert(
                id.to_string(),
                TripMeta {
                    route_id: feed.trips.get(row, "route_id").to_string(),
                    service_id: feed.trips.get(row, "service_id").to_string(),
                    headsign_off,
                    shape_id: feed.trips.get(row, "shape_id").trim().to_string(),
                },
            );
        }

        // --- stop_times grouped by trip (this feed) ---
        let mut trip_stoptimes: HashMap<String, Vec<StopTime>> = HashMap::new();
        let mut collect = |row: gtfs::StopTimeRow| {
            let stop_idx = match stop_id_to_idx.get(row.stop_id) {
                Some(&i) => i,
                None => return,
            };
            trip_stoptimes.entry(row.trip_id.to_string()).or_default().push(StopTime {
                seq: row.seq,
                stop_idx,
                arr: row.arr,
                dep: row.dep,
                dist: row.dist,
            });
        };
        match feed.stop_times {
            StopTimesSource::Table(csv) => {
                for row in &csv.rows {
                    let trip_id = csv.get(row, "trip_id");
                    if trip_id.is_empty() {
                        continue;
                    }
                    let Some((arr, dep)) = gtfs::stop_time_pair(
                        csv.get(row, "arrival_time"),
                        csv.get(row, "departure_time"),
                    ) else {
                        continue;
                    };
                    collect(gtfs::StopTimeRow {
                        trip_id,
                        stop_id: csv.get(row, "stop_id"),
                        seq: csv.get(row, "stop_sequence").trim().parse().unwrap_or(0),
                        arr,
                        dep,
                        dist: csv
                            .get(row, "shape_dist_traveled")
                            .trim()
                            .parse()
                            .unwrap_or(f64::NAN),
                    });
                }
            }
            StopTimesSource::Dir(dir) => {
                gtfs::stream_stop_times(dir, collect).ok_or_else(|| {
                    format!(
                        "feed '{}' ({}) missing required GTFS file: stop_times.txt",
                        feed.name,
                        dir.display()
                    )
                })?;
            }
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
                if self.service_key_to_idx.contains_key(&key) {
                    continue;
                }
                let mut mask = 0u8;
                for (b, d) in days.iter().enumerate() {
                    if cal.get(row, d).trim() == "1" {
                        mask |= 1 << b;
                    }
                }
                let idx = self.svc_mask.len() as u32;
                self.service_key_to_idx.insert(key, idx);
                self.svc_mask.push(mask);
                self.svc_start.push(parse_gtfs_date(cal.get(row, "start_date")).unwrap_or(0));
                self.svc_end
                    .push(parse_gtfs_date(cal.get(row, "end_date")).unwrap_or(99_999_999));
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
                let sidx = self.ensure_service(&feed_key(sid));
                self.exceptions.push((sidx, date, added));
            }
        }

        // --- Group this feed's trips into RAPTOR routes ---
        // Every one of these is a feed-local: `finalize_feed` below turns them
        // into section bytes before `add_feed` returns, which is what stops them
        // accumulating across 1272 feeds.
        let mut raptor_routes: Vec<RaptorRoute> = Vec::new();
        let mut built_trips: Vec<BuiltTrip> = Vec::new();
        // Referenced `shapes.txt` polylines, interned so a trip names its shape
        // by index instead of carrying a `String`.
        let mut shapes: Vec<(&str, &Shape)> = Vec::new();
        let mut shape_id_to_idx: HashMap<&str, u32> = HashMap::new();
        // Keyed by a hash of `(route_id, stop_pattern)`, with every candidate
        // compared in full. Unlike the other dedup tables a false match here
        // would fuse two distinct patterns into one route — wrong departures
        // rather than wasted bytes — so a hash alone is not enough. Comparing
        // against the pattern the route already stores also removes the ~240-byte
        // key string this used to allocate per trip.
        let mut pattern_index: HashMap<u64, Vec<usize>> = HashMap::new();
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
            let service_idx = self.ensure_service(&feed_key(&tmeta.service_id));
            let mut hash = fnv1a(tmeta.route_id.as_bytes());
            for &s in &pattern {
                hash = fnv1a_mix(hash, &s.to_le_bytes());
            }
            let slot = pattern_index.entry(hash).or_default();
            let route_idx = match slot.iter().copied().find(|&i| {
                raptor_routes[i].route_id == tmeta.route_id
                    && raptor_routes[i].stop_pattern == pattern
            }) {
                Some(i) => i,
                None => {
                    let (name_off, color, rtype) = match route_meta.get(&tmeta.route_id) {
                        Some(m) => (m.name_off, m.color, m.route_type),
                        None => (NONE, 0, 3),
                    };
                    raptor_routes.push(RaptorRoute {
                        feed_idx,
                        route_id: tmeta.route_id.clone(),
                        name_off,
                        color,
                        route_type: rtype,
                        stop_pattern: pattern,
                        trips: Vec::new(),
                    });
                    let i = raptor_routes.len() - 1;
                    slot.push(i);
                    i
                }
            };
            let shape_key = match feed
                .shapes
                .filter(|_| !tmeta.shape_id.is_empty())
                .and_then(|m| m.get_key_value(&tmeta.shape_id))
            {
                Some((id, sh)) => *shape_id_to_idx.entry(id.as_str()).or_insert_with(|| {
                    shapes.push((id.as_str(), sh));
                    shapes.len() as u32 - 1
                }),
                None => NONE,
            };
            built_trips.push(BuiltTrip {
                service_idx,
                headsign_off: tmeta.headsign_off,
                start_time: sts.first().map(|s| s.dep).unwrap_or(0),
                stoptimes: sts.iter().map(|s| (s.arr, s.dep)).collect(),
                shape_key,
                // Present only when every stop in the pattern carries one, which
                // is what `shapes::fit` requires before it trusts the key.
                stop_dists: sts
                    .iter()
                    .map(|s| (!s.dist.is_nan()).then_some(s.dist))
                    .collect::<Option<Vec<f64>>>(),
            });
            raptor_routes[route_idx].trips.push(built_trips.len() - 1);
        }

        self.finalize_feed(&raptor_routes, &built_trips, &shapes, feed_stop_start)?;

        // A feed's services are allocated contiguously while it is being added,
        // and its exceptions only ever reference them, so sorting this feed's
        // slice leaves the whole array sorted by (service, date) once every feed
        // is in. The sort must stay **stable**: `finish_to` collapses duplicate
        // (service, date) rows by keeping the last, which is CSV order.
        self.exceptions[feed_exc_start..].sort_by_key(|&(sidx, date, _)| (sidx, date));
        Ok(())
    }

    /// Serialize one feed's routes, trips, profiles, shapes and stop→route lists.
    ///
    /// `stop_first` is the first stop index this feed contributed. Because a
    /// route only ever references its own feed's stops, this feed's STOP_ROUTES
    /// entries cover exactly `stop_first..` and append onto a globally
    /// stop-ascending array.
    fn finalize_feed(
        &mut self,
        raptor_routes: &[RaptorRoute],
        built_trips: &[BuiltTrip],
        shapes: &[(&str, &Shape)],
        stop_first: u32,
    ) -> Result<(), String> {
        // (stop, route, position in the route's pattern), sorted into
        // stop-ascending order below. Replaces the old `vec![Vec::new(); stops]`
        // pair — 48 bytes of empty headers per stop, worldwide — and the
        // `contains` scan that went with it, which was quadratic on the routes
        // through a major interchange.
        let mut stop_routes: Vec<(u32, u32, u32)> = Vec::new();

        for (local_idx, rr) in raptor_routes.iter().enumerate() {
            let ridx = (self.route_count + local_idx) as u32;
            // Trips of one pattern normally share a `shape_id`. Where they do not
            // (versioned shapes, rare express variants) the modal one keeps the
            // common case exact; direction differences already form distinct
            // patterns, so they never land here.
            let mut votes: HashMap<u32, usize> = HashMap::new();
            for &ti in &rr.trips {
                let k = built_trips[ti].shape_key;
                if k != NONE {
                    *votes.entry(k).or_insert(0) += 1;
                }
            }
            if votes.len() > 1 {
                self.multi_shape_routes += 1;
            }
            let mut ranked: Vec<(u32, usize)> = votes.into_iter().collect();
            // Ties break on the `shape_id` itself, not on its interned index, so
            // the winner does not depend on the order trips were seen in.
            ranked.sort_by(|a, b| {
                b.1.cmp(&a.1).then(shapes[a.0 as usize].0.cmp(shapes[b.0 as usize].0))
            });
            let modal = ranked.first().map(|&(k, _)| k);
            let fitted = modal.and_then(|key| {
                let shape = shapes[key as usize].1;
                let stop_ll: Vec<(i32, i32)> = rr
                    .stop_pattern
                    .iter()
                    .map(|&s| (self.stop_lat[s as usize], self.stop_lon[s as usize]))
                    .collect();
                // `shape_dist_traveled` only helps when both files carry it, so
                // take it from a trip that actually uses the modal shape.
                let dists = rr
                    .trips
                    .iter()
                    .filter(|&&ti| built_trips[ti].shape_key == key)
                    .find_map(|&ti| built_trips[ti].stop_dists.as_ref())
                    .filter(|d| d.len() == rr.stop_pattern.len());
                shapes::fit(shape, &stop_ll, dists.map(|d| d.as_slice()))
            });
            if modal.is_some() {
                if fitted.is_some() {
                    self.shaped_routes += 1;
                } else {
                    self.dropped_shape_routes += 1;
                }
            }
            let shape_off = match &fitted {
                None => NONE,
                Some(f) => self.shape_blobs.intern(&shapes::encode(&f.points))?,
            };
            append_u32(&mut self.sec_route_shape_idx, shape_off);

            let first_route_stop = (self.sec_route_stops.len() / 4) as u32;
            for (pos, &s) in rr.stop_pattern.iter().enumerate() {
                debug_assert!(
                    s >= stop_first,
                    "route {ridx} references stop {s} from an earlier feed; per-feed \
                     finalization depends on patterns staying inside their own feed"
                );
                append_u32(&mut self.sec_route_stops, s);
                let vertex = fitted
                    .as_ref()
                    .and_then(|f| f.stop_vertices.get(pos).copied())
                    .unwrap_or(NONE);
                append_u32(&mut self.sec_route_stop_shape, vertex);
                stop_routes.push((s, ridx, pos as u32));
            }
            let n_stops = rr.stop_pattern.len() as u32;

            // Sort this route's trips by first-stop departure, then varint-pack.
            let mut trip_order: Vec<usize> = rr.trips.clone();
            trip_order.sort_by_key(|&ti| built_trips[ti].start_time);
            // RouteRec.trips_off is a u32 byte offset into ROUTE_TRIPS.
            let trips_off = u32::try_from(self.sec_route_trips.len()).map_err(|_| {
                "ROUTE_TRIPS exceeded 4 GiB, which a u32 RouteRec.trips_off cannot address"
                    .to_string()
            })?;
            let mut prev_start: u32 = 0;
            for &ti in &trip_order {
                let bt = &built_trips[ti];
                let profile_id = self.profiles.intern(&bt.stoptimes)?;
                let start_delta = bt.start_time.saturating_sub(prev_start);
                write_uvarint(&mut self.sec_route_trips, start_delta as u64);
                write_uvarint(&mut self.sec_route_trips, profile_id as u64);
                write_uvarint(&mut self.sec_route_trips, bt.service_idx as u64);
                write_uvarint(&mut self.sec_route_trips, bt.headsign_off as u64);
                prev_start = bt.start_time;
                self.trip_total += 1;
            }

            append_u32(&mut self.sec_routes, rr.name_off);
            append_u32(&mut self.sec_routes, rr.color);
            append_u32(&mut self.sec_routes, rr.route_type);
            append_u32(&mut self.sec_routes, rr.feed_idx);
            append_u32(&mut self.sec_routes, n_stops);
            append_u32(&mut self.sec_routes, first_route_stop);
            append_u32(&mut self.sec_routes, trip_order.len() as u32);
            append_u32(&mut self.sec_routes, trips_off);
        }
        self.route_count += raptor_routes.len();

        // Stable, so within a stop the routes stay in ascending route order and
        // the surviving position is the pattern's first occurrence of that stop —
        // matching the reader's `break`-on-match scan that STOP_ROUTE_POS
        // replaces.
        stop_routes.sort_by_key(|&(s, _, _)| s);
        stop_routes.dedup_by(|a, b| a.0 == b.0 && a.1 == b.1);
        let mut k = 0usize;
        for s in stop_first..self.stop_lat.len() as u32 {
            append_u32(&mut self.sec_stop_routes_idx, self.stop_routes_total);
            while let Some(&(stop, route, pos)) = stop_routes.get(k) {
                if stop != s {
                    break;
                }
                append_u32(&mut self.sec_stop_routes, route);
                append_u32(&mut self.sec_stop_route_pos, pos);
                self.stop_routes_total += 1;
                k += 1;
            }
        }
        debug_assert_eq!(k, stop_routes.len(), "a stop→route entry fell outside this feed");
        Ok(())
    }

    /// Serialize the sections that need every feed, and write the pack to `out`.
    pub fn finish_to(self, out: &mut impl Write) -> Result<BuildStats, String> {
        let IndexBuilder {
            pool,
            pack_name_off,
            stop_lat,
            stop_lon,
            min_lat,
            min_lon,
            max_lat,
            max_lon,
            service_key_to_idx: _,
            svc_mask,
            svc_start,
            svc_end,
            exceptions,
            feed_name_offs,
            feed_tz_offs,
            feed_motis_prefix_offs,
            sec_stops,
            sec_stop_gtfs_id,
            sec_routes,
            sec_route_stops,
            sec_route_trips,
            mut sec_route_shape_idx,
            sec_route_stop_shape,
            sec_stop_routes,
            mut sec_stop_routes_idx,
            sec_stop_route_pos,
            profiles,
            shape_blobs,
            stop_routes_total,
            route_count,
            trip_total,
            shaped_routes,
            multi_shape_routes,
            dropped_shape_routes,
            dropped_stops_bad_coord,
        } = self;
        let stop_count = stop_lat.len();
        if stop_count == 0 {
            return Err("no usable stops in any feed".to_string());
        }

        // Everything that depends on one feed alone is already serialized; what
        // is left needs the whole merged stop set.

        // Terminating bound the reader validates shape offsets against.
        append_u32(&mut sec_route_shape_idx, shape_blobs.bytes.len() as u32);
        // Terminating total, so the last stop's STOP_ROUTES range closes.
        append_u32(&mut sec_stop_routes_idx, stop_routes_total);

        // Profiles index (byte offset per id, plus terminating length).
        let mut sec_profiles_idx = Vec::new();
        for &off in &profiles.offsets {
            append_u32(&mut sec_profiles_idx, off);
        }
        append_u32(&mut sec_profiles_idx, profiles.bytes.len() as u32);
        let profile_count = profiles.offsets.len();

        // A wrapped STRINGS offset would name the wrong stop rather than fail, so
        // this is checked before anything is written.
        if pool.overflowed {
            return Err(
                "STRINGS exceeded the 4 GiB a u32 string offset can address; the pack cannot \
                 represent this many feeds"
                    .to_string(),
            );
        }

        // --- Footpath transfers via a coarse spatial grid (cross-feed for free) ---
        // A flat `(cell, stop)` vector sorted once, not a `HashMap` of buckets: on a
        // world pack the map is millions of cells each holding a tiny `Vec`, whose
        // headers and allocations cost far more than the stop ids in them. Sorting
        // by `(cell, stop)` keeps each cell's stops ascending, which is the order the
        // old bucket pushes produced and which the distance sort below tie-breaks on.
        let xcell = |lat_e7: i32, lon_e7: i32| -> (i32, i32) {
            (
                (lat_e7 as f64 * 1e-7 / CELL_DEG).floor() as i32,
                (lon_e7 as f64 * 1e-7 / CELL_DEG).floor() as i32,
            )
        };
        let mut xgrid: Vec<((i32, i32), u32)> = (0..stop_count)
            .map(|i| (xcell(stop_lat[i], stop_lon[i]), i as u32))
            .collect();
        xgrid.sort_unstable();
        let xbucket = |key: (i32, i32)| -> &[((i32, i32), u32)] {
            let lo = xgrid.partition_point(|&(k, _)| k < key);
            let hi = xgrid.partition_point(|&(k, _)| k <= key);
            &xgrid[lo..hi]
        };
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
                    for &(_, j) in xbucket((cx + dx, cy + dy)) {
                        if j as usize == i {
                            continue;
                        }
                        let d =
                            dist_m(stop_lat[i], stop_lon[i], stop_lat[j as usize], stop_lon[j as usize]);
                        if d <= MAX_TRANSFER_M {
                            cand.push((j, d));
                        }
                    }
                }
            }
            // Stable, so equidistant candidates keep the dx/dy/stop order above and
            // `truncate` drops a deterministic tail.
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
        // --- Exceptions: sorted by (service_idx, date), one row per pair, with a
        // CSR index so the device does a range lookup + binary search instead of a
        // full scan per trip. `add_feed` already stable-sorted each feed's slice
        // and a feed's services are a contiguous index range, so the whole array
        // is sorted; for duplicate (service, date) rows the last in CSV order
        // wins — matching the old reader, which scanned every exception without
        // breaking out. ---
        debug_assert!(
            exceptions.windows(2).all(|w| (w[0].0, w[0].1) <= (w[1].0, w[1].1)),
            "per-feed exception slices did not concatenate into a sorted array"
        );
        let mut exc_unique: Vec<(u32, u32, u32)> = Vec::with_capacity(exceptions.len());
        for e in exceptions {
            match exc_unique.last_mut() {
                Some(last) if last.0 == e.0 && last.1 == e.1 => *last = e,
                _ => exc_unique.push(e),
            }
        }
        let mut sec_exceptions = Vec::new();
        let mut sec_exceptions_idx = Vec::new();
        let mut eacc: u32 = 0;
        let mut ei = 0usize;
        for s in 0..svc_mask.len() as u32 {
            append_u32(&mut sec_exceptions_idx, eacc);
            while let Some(&(sidx, date, added)) = exc_unique.get(ei) {
                if sidx != s {
                    break;
                }
                append_u32(&mut sec_exceptions, sidx);
                append_u32(&mut sec_exceptions, date);
                append_u32(&mut sec_exceptions, added);
                eacc += 1;
                ei += 1;
            }
        }
        append_u32(&mut sec_exceptions_idx, eacc);

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
        // Sorted by `(cell_id, stop)`, which is exactly the two orderings the device
        // requires: GRID_CELL_IDS ascending, because the reader binary-searches it,
        // and each cell's GRID_STOPS run stop-ascending.
        let mut grid: Vec<(u32, u32)> = (0..stop_count)
            .map(|i| {
                let row = cell_row(stop_lat[i]) as u32;
                let col = cell_col(stop_lon[i]) as u32;
                (row * grid_cols + col, i as u32)
            })
            .collect();
        grid.sort_unstable();
        let mut sec_grid_cell_ids = Vec::new();
        let mut sec_grid_cell_off = Vec::new();
        let mut sec_grid_stops = Vec::with_capacity(stop_count * 4);
        let mut grid_cell_count = 0usize;
        for (k, &(cid, stop)) in grid.iter().enumerate() {
            if k == 0 || cid != grid[k - 1].0 {
                append_u32(&mut sec_grid_cell_ids, cid);
                append_u32(&mut sec_grid_cell_off, k as u32);
                grid_cell_count += 1;
            }
            append_u32(&mut sec_grid_stops, stop);
        }
        append_u32(&mut sec_grid_cell_off, grid.len() as u32);

        // --- Feeds ---
        let mut sec_feeds = Vec::with_capacity(feed_name_offs.len() * 4);
        for &off in &feed_name_offs {
            append_u32(&mut sec_feeds, off);
        }
        let mut sec_feed_tz = Vec::with_capacity(feed_tz_offs.len() * 4);
        for &off in &feed_tz_offs {
            append_u32(&mut sec_feed_tz, off);
        }
        let mut sec_feed_motis_prefix = Vec::with_capacity(feed_motis_prefix_offs.len() * 4);
        for &off in &feed_motis_prefix_offs {
            append_u32(&mut sec_feed_motis_prefix, off);
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
            "FEED_TZ",
            "EXCEPTIONS_IDX",
            "STOP_ROUTE_POS",
            "SHAPE_COORDS",
            "ROUTE_SHAPE_IDX",
            "ROUTE_STOP_SHAPE",
            "FEED_MOTIS_PREFIX",
            "STOP_GTFS_ID",
        ];
        let sections: [&[u8]; SECTION_COUNT] = [
            &pool.bytes,
            &sec_stops,
            &sec_routes,
            &sec_route_stops,
            &sec_route_trips,
            &profiles.bytes,
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
            &shape_blobs.bytes,
            &sec_route_shape_idx,
            &sec_route_stop_shape,
            &sec_feed_motis_prefix,
            &sec_stop_gtfs_id,
        ];

        let dir_len = SECTION_COUNT * 16;
        let mut data_off = HEADER_LEN + dir_len;
        let align = |o: usize| (o + 7) & !7;
        for s in sections.iter() {
            data_off = align(data_off) + s.len();
        }
        let total = data_off;

        let mut header: Vec<u8> = Vec::with_capacity(HEADER_LEN);
        append_u32(&mut header, MAGIC);
        append_u32(&mut header, VERSION);
        append_u32(&mut header, SECTION_COUNT as u32);
        append_u32(&mut header, stop_count as u32);
        append_u32(&mut header, route_count as u32);
        append_u32(&mut header, trip_total as u32);
        append_u32(&mut header, svc_mask.len() as u32);
        append_u32(&mut header, profile_count as u32);
        append_u32(&mut header, feed_name_offs.len() as u32);
        append_u32(&mut header, grid_cell_count as u32);
        append_u32(&mut header, pack_name_off);
        append_i32(&mut header, min_lat);
        append_i32(&mut header, min_lon);
        append_i32(&mut header, max_lat);
        append_i32(&mut header, max_lon);
        append_i32(&mut header, grid_lat0);
        append_i32(&mut header, grid_lon0);
        append_u32(&mut header, grid_cell_e7);
        append_u32(&mut header, grid_cols);
        append_u32(&mut header, grid_rows);
        debug_assert_eq!(header.len(), HEADER_LEN);

        let written =
            write_pack(out, &header, &sections).map_err(|e| format!("cannot write the pack: {e}"))?;
        debug_assert_eq!(written, total);

        let section_sizes: Vec<(&'static str, usize)> =
            section_names.iter().zip(sections.iter()).map(|(&n, s)| (n, s.len())).collect();

        Ok(BuildStats {
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
            size_bytes: written,
            section_sizes,
            shaped_routes,
            multi_shape_routes,
            dropped_shape_routes,
            dropped_stops_bad_coord,
        })
    }

}

/// Write header + section directory + 8-byte-aligned payloads to `out`, and
/// return the total byte length.
///
/// Every section's length is known by the time this runs, so the directory is
/// computed up front: nothing is reserved and seeked back to, which is what lets
/// the pack go straight to a `BufWriter` instead of being concatenated into one
/// multi-gigabyte `Vec` first.
fn write_pack(
    out: &mut impl Write,
    header: &[u8],
    sections: &[&[u8]; SECTION_COUNT],
) -> std::io::Result<usize> {
    const PAD: [u8; 8] = [0; 8];
    let align = |o: usize| (o + 7) & !7;

    let mut off = HEADER_LEN + SECTION_COUNT * 16;
    let mut dir = Vec::with_capacity(SECTION_COUNT * 16);
    for s in sections {
        off = align(off);
        dir.extend_from_slice(&(off as u64).to_le_bytes());
        dir.extend_from_slice(&(s.len() as u64).to_le_bytes());
        off += s.len();
    }
    let total = off;

    out.write_all(header)?;
    out.write_all(&dir)?;
    let mut pos = header.len() + dir.len();
    for s in sections {
        let pad = align(pos) - pos;
        out.write_all(&PAD[..pad])?;
        out.write_all(s)?;
        pos += pad + s.len();
    }
    debug_assert_eq!(pos, total);
    Ok(total)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gtfs::parse_csv;
    use crate::reader::{
        Reader, SEC_FEED_MOTIS_PREFIX, SEC_SHAPE_COORDS, SEC_STOP_GTFS_ID,
    };

    /// 24 bytes per `stop_times.txt` row is the whole point of the streaming
    /// loader — a world corpus has billions of them, and the old `Csv` +
    /// `Option<f64>` representation cost ~400 and 40.
    #[test]
    fn a_stop_time_stays_twentyfour_bytes() {
        assert_eq!(std::mem::size_of::<StopTime>(), 24);
    }

    fn agency(tz: &str) -> Csv {
        parse_csv(&format!("agency_id,agency_name,agency_timezone\nA,Agency,{tz}\n"))
    }

    /// `(shape_id, points as (lat, lon), optional shape_dist_traveled)`.
    type ShapeSpec<'a> = (&'a str, Vec<(f64, f64)>, Option<Vec<f64>>);

    fn shape_map(entries: Vec<ShapeSpec>) -> HashMap<String, Shape> {
        entries
            .into_iter()
            .map(|(id, pts, dist)| {
                (
                    id.to_string(),
                    Shape {
                        lat_e7: pts.iter().map(|&(la, _)| (la * 1e7) as i32).collect(),
                        lon_e7: pts.iter().map(|&(_, lo)| (lo * 1e7) as i32).collect(),
                        dist,
                    },
                )
            })
            .collect()
    }

    /// Every route's vertex indices must be non-decreasing and inside its blob,
    /// or the device's `shape[vertex(board)..=vertex(alight)]` slice is garbage.
    fn assert_shape_invariants(r: &Reader) {
        for route in 0..r.route_count() {
            let rec = r.route(route);
            let (n_stops, first) = (rec.n_stops, rec.first_route_stop);
            let vertices: Vec<u32> =
                (0..n_stops).map(|p| r.route_stop_shape(first + p)).collect();
            match r.route_shape_off(route) {
                None => assert!(
                    vertices.iter().all(|&v| v == NONE),
                    "route {route} has no shape but carries vertices {vertices:?}"
                ),
                Some(off) => {
                    let n = r.shape_points(off).len() as u32;
                    assert!(
                        vertices.windows(2).all(|w| w[1] >= w[0]),
                        "route {route} vertices not monotone: {vertices:?}"
                    );
                    assert!(
                        vertices.iter().all(|&v| v < n),
                        "route {route} vertices {vertices:?} exceed {n} points"
                    );
                }
            }
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
        let aa = agency("America/Los_Angeles");
        let ba = agency("America/New_York");
        // Two rows for the same (service, date) plus an unsorted extra date, to
        // exercise the sort + last-row-wins collapse.
        let acd = parse_csv(
            "service_id,date,exception_type\n\
             WK,20240704,1\n\
             WK,20240101,2\n\
             WK,20240704,2\n",
        );
        let feeds = vec![
            FeedInput {
                name: "sfmuni".to_string(),
                motis_prefix: "us-ca-SFMTA".to_string(),
                stops: &as_,
                routes: &ar,
                trips: &at,
                stop_times: &ast,
                calendar: Some(&ac),
                calendar_dates: Some(&acd),
                agency: Some(&aa),
                shapes: None,
            },
            FeedInput {
                name: "actransit".to_string(),
                // Left empty on purpose: a feed whose MOTIS prefix the build does
                // not know must still produce a valid pack.
                motis_prefix: String::new(),
                stops: &bs,
                routes: &br,
                trips: &bt,
                stop_times: &bst,
                calendar: Some(&bc),
                calendar_dates: None,
                agency: Some(&ba),
                shapes: None,
            },
        ];
        let (blob, stats) = build_index("world", &feeds).expect("build");
        let r = Reader::new(blob).expect("read back the pack");

        // Header sanity. `Reader::new` has already checked the magic.
        assert_eq!(r.version(), VERSION);
        assert_eq!(r.section_count(), SECTION_COUNT as u32);
        assert_eq!(r.stop_count(), 5, "stop_count"); // 3 + 2
        assert_eq!(r.route_count(), 2, "route_count");
        assert_eq!(r.trip_count(), 3, "trip_count"); // 2 + 1
        assert_eq!(r.feed_count(), 2, "feed_count");
        // Feed A's two trips share one shape; feed B has its own -> 2 profiles.
        assert_eq!(r.profile_count(), 2, "profile_count");
        assert_eq!(stats.profiles, 2);

        // Feeds table namespacing.
        assert_eq!(r.feed_name(0), "sfmuni");
        assert_eq!(r.feed_name(1), "actransit");
        // v3: per-feed IANA timezone from agency.txt.
        assert_eq!(r.feed_tz(0), "America/Los_Angeles");
        assert_eq!(r.feed_tz(1), "America/New_York");
        // v5: the Transitous prefix and raw stop_ids that compose a MOTIS stop id.
        assert_eq!(r.feed_motis_prefix(0), "us-ca-SFMTA");
        assert_eq!(r.feed_motis_prefix(1), "", "a feed with no known prefix writes NONE");
        // Feed A's stops come first, so 0..3 are its stop_ids. These are the raw
        // `stop_id` column, NOT `stop_code` ("A1"), which STOPS.code_off holds.
        assert_eq!(r.stop_gtfs_id(0), "S1");
        assert_eq!(r.stop_gtfs_id(1), "S2");
        assert_eq!(r.stop_gtfs_id(2), "S3");
        assert_ne!(
            r.sec_bytes(SEC_FEED_MOTIS_PREFIX).len(),
            0,
            "FEED_MOTIS_PREFIX must be populated so the manifest proves v5 landed"
        );
        assert_eq!(r.sec_bytes(SEC_STOP_GTFS_ID).len(), 5 * 4, "STOP_GTFS_ID is u32[stop_count]");

        // Route 0 = feed A's N-Judah. Decode its trips + profile.
        let rec0 = r.route(0);
        assert_eq!(rec0.feed_idx, 0, "route0 feed_idx");
        assert_eq!(rec0.n_stops, 3, "route0 n_stops");
        assert_eq!(r.read_str(rec0.name_off), "N");
        let trips = r.route_trips(&rec0);
        assert_eq!(trips.len(), 2);
        // Sorted by start_time: 08:00 then 09:00.
        assert_eq!(trips[0].start_time, 28800);
        assert_eq!(trips[1].start_time, 32400);
        // Both reference the same profile id (dedup).
        assert_eq!(trips[0].profile_id, trips[1].profile_id);

        // Reconstruct absolute arr/dep for trip T2 (start 09:00) and compare.
        let prof = r.profile(trips[1].profile_id);
        let start = trips[1].start_time as i64;
        let abs: Vec<(i64, i64)> =
            prof.iter().map(|&(a, d)| (start + a, start + d)).collect();
        assert_eq!(abs[0], (32400, 32400)); // S1 09:00/09:00
        assert_eq!(abs[1], (32700, 32760)); // S2 09:05/09:06
        assert_eq!(abs[2], (33000, 33000)); // S3 09:10/09:10

        // Route 1 belongs to feed B.
        let rec1 = r.route(1);
        assert_eq!(rec1.feed_idx, 1, "route1 feed_idx");
        assert_eq!(rec1.n_stops, 2, "route1 n_stops");

        // Route stops of route 0 point at the first three (feed A) stops.
        for pos in 0..rec0.n_stops {
            let s = r.route_stop(rec0.first_route_stop + pos);
            assert!(s < 3, "route0 stop {s} should be a feed-A stop");
        }

        // Grid nearest lookups.
        assert_eq!(r.nearest(37.72, -122.42), Some(2), "Gamma");
        assert_eq!(r.nearest(37.80, -122.27), Some(3), "feed B P1");

        // v3: STOP_ROUTE_POS is parallel to STOP_ROUTES and gives the stop's
        // position in the pattern, so pos matches a ROUTE_STOPS lookup.
        for stop in 0..r.stop_count() {
            for (route, pos) in r.stop_routes(stop) {
                let rec = r.route(route);
                assert!(pos < rec.n_stops, "stop {stop} pos {pos} out of route {route}");
                assert_eq!(
                    r.route_stop(rec.first_route_stop + pos),
                    stop,
                    "stop {stop} route {route}"
                );
            }
        }

        // v3: exceptions are date-sorted per service and collapsed to one row
        // per (service, date), with the last CSV row winning.
        let svc = r.route_trips(&rec0)[0].service_idx;
        assert_eq!(
            r.service_exceptions(svc),
            vec![(svc, 20240101, 0), (svc, 20240704, 0)],
            "sorted, deduped, last-row-wins"
        );
        // Feed B contributes no exceptions, so its services have empty ranges.
        let svc_b = r.route_trips(&rec1)[0].service_idx;
        assert!(r.service_exceptions(svc_b).is_empty());

        // v4: no shapes.txt anywhere, so every route falls back to stop-to-stop.
        assert!(r.route_shape_off(0).is_none());
        assert!(r.route_shape_off(1).is_none());
        assert_eq!(r.sec_bytes(SEC_SHAPE_COORDS).len(), 0, "SHAPE_COORDS is empty");
        assert_shape_invariants(&r);
    }

    // --- v4 shape ingest -----------------------------------------------------

    /// One feed, three collinear stops, one route, `shape_id` on both trips.
    /// `stop_times_extra` is appended verbatim so a test can add
    /// `shape_dist_traveled`.
    fn shaped_feed(shape_dist: bool) -> (Csv, Csv, Csv, Csv, Csv) {
        let stops = parse_csv(
            "stop_id,stop_name,stop_lat,stop_lon,stop_code\n\
             S1,Alpha,37.700,-122.400,A1\n\
             S2,Beta,37.710,-122.400,B2\n\
             S3,Gamma,37.720,-122.400,C3\n",
        );
        let routes = parse_csv(
            "route_id,route_short_name,route_long_name,route_type,route_color\n\
             R1,N,Judah,0,0000FF\n",
        );
        let trips = parse_csv(
            "route_id,service_id,trip_id,trip_headsign,shape_id\n\
             R1,WK,T1,Downtown,SH1\n\
             R1,WK,T2,Downtown,SH1\n",
        );
        let stop_times = if shape_dist {
            parse_csv(
                "trip_id,stop_id,stop_sequence,arrival_time,departure_time,shape_dist_traveled\n\
                 T1,S1,1,08:00:00,08:00:00,0.0\n\
                 T1,S2,2,08:05:00,08:06:00,1.3\n\
                 T1,S3,3,08:10:00,08:10:00,2.6\n\
                 T2,S1,1,09:00:00,09:00:00,0.0\n\
                 T2,S2,2,09:05:00,09:06:00,1.3\n\
                 T2,S3,3,09:10:00,09:10:00,2.6\n",
            )
        } else {
            parse_csv(
                "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n\
                 T1,S1,1,08:00:00,08:00:00\n\
                 T1,S2,2,08:05:00,08:06:00\n\
                 T1,S3,3,08:10:00,08:10:00\n\
                 T2,S1,1,09:00:00,09:00:00\n\
                 T2,S2,2,09:05:00,09:06:00\n\
                 T2,S3,3,09:10:00,09:10:00\n",
            )
        };
        let calendar = parse_csv(
            "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n\
             WK,1,1,1,1,1,0,0,20240101,20241231\n",
        );
        (stops, routes, trips, stop_times, calendar)
    }

    /// The route's shape, detouring east between each pair of stops.
    fn detour_shape(dist: bool) -> HashMap<String, Shape> {
        shape_map(vec![(
            "SH1",
            vec![
                (37.700, -122.400),
                (37.705, -122.390),
                (37.710, -122.400),
                (37.715, -122.390),
                (37.720, -122.400),
            ],
            // Kilometres, deliberately not metres: units are feed-defined.
            dist.then(|| vec![0.0, 0.65, 1.3, 1.95, 2.6]),
        )])
    }

    fn one_feed<'a>(
        t: &'a (Csv, Csv, Csv, Csv, Csv),
        ag: &'a Csv,
        shapes: Option<&'a HashMap<String, Shape>>,
    ) -> Vec<FeedInput<'a>> {
        vec![FeedInput {
            name: "sfmuni".to_string(),
            motis_prefix: "us-ca-SFMTA".to_string(),
            stops: &t.0,
            routes: &t.1,
            trips: &t.2,
            stop_times: &t.3,
            calendar: Some(&t.4),
            calendar_dates: None,
            agency: Some(ag),
            shapes,
        }]
    }

    #[test]
    fn projects_stops_onto_a_shape_without_shape_dist_traveled() {
        let t = shaped_feed(false);
        let ag = agency("America/Los_Angeles");
        let sh = detour_shape(false);
        let (blob, stats) =
            build_index("world", &one_feed(&t, &ag, Some(&sh))).expect("build");
        let r = Reader::new(blob).expect("read back the pack");
        assert_eq!(stats.shaped_routes, 1);
        assert_eq!(stats.dropped_shape_routes, 0);
        assert_eq!(stats.multi_shape_routes, 0);
        assert_shape_invariants(&r);

        let off = r.route_shape_off(0).expect("route 0 is shaped");
        let pts = r.shape_points(off);
        let rec = r.route(0);
        let vertices: Vec<u32> =
            (0..rec.n_stops).map(|p| r.route_stop_shape(rec.first_route_stop + p)).collect();
        // Trimmed to the boarded extent: first stop is vertex 0, last is the end.
        assert_eq!(vertices[0], 0);
        assert_eq!(*vertices.last().unwrap() as usize, pts.len() - 1);
        // Each stop's vertex is the stop's own projection, so a boarded span
        // starts exactly on the stop.
        for (p, &v) in vertices.iter().enumerate() {
            let stop = r.route_stop(rec.first_route_stop + p as u32);
            let (slat, slon) = r.stop_ll(stop);
            let (vlat, vlon) = pts[v as usize];
            assert!(
                (vlat as f64 * 1e-7 - slat).abs() < 1e-5
                    && (vlon as f64 * 1e-7 - slon).abs() < 1e-5,
                "vertex {v} at {vlat},{vlon} is not stop {stop} at {slat},{slon}"
            );
        }
        // The detour survives simplification, so the drawn ride is not straight.
        assert!(
            pts.iter().any(|&(_, lon)| lon > -1_223_950_000),
            "the eastward detour was simplified away: {pts:?}"
        );
    }

    #[test]
    fn shape_dist_traveled_on_both_files_is_used() {
        let t = shaped_feed(true);
        let ag = agency("America/Los_Angeles");
        let sh = detour_shape(true);
        let (blob, stats) =
            build_index("world", &one_feed(&t, &ag, Some(&sh))).expect("build");
        let r = Reader::new(blob).expect("read back the pack");
        assert_eq!(stats.shaped_routes, 1);
        assert_shape_invariants(&r);
        let rec = r.route(0);
        let vertices: Vec<u32> =
            (0..rec.n_stops).map(|p| r.route_stop_shape(rec.first_route_stop + p)).collect();
        assert!(vertices.windows(2).all(|w| w[1] > w[0]), "distinct stops, distinct vertices");
    }

    #[test]
    fn a_mismatched_shape_is_dropped_to_the_stop_to_stop_fallback() {
        let t = shaped_feed(false);
        let ag = agency("America/Los_Angeles");
        // The same shape_id, but 4000 km east.
        let sh = shape_map(vec![(
            "SH1",
            vec![(40.700, -74.000), (40.710, -74.000), (40.720, -74.000)],
            None,
        )]);
        let (blob, stats) =
            build_index("world", &one_feed(&t, &ag, Some(&sh))).expect("build");
        let r = Reader::new(blob).expect("read back the pack");
        assert_eq!(stats.shaped_routes, 0);
        assert_eq!(stats.dropped_shape_routes, 1);
        assert!(r.route_shape_off(0).is_none(), "a bad shape must not be stored");
        assert_eq!(r.sec_bytes(SEC_SHAPE_COORDS).len(), 0);
        assert_shape_invariants(&r);
    }

    #[test]
    fn a_reference_to_a_missing_shape_id_falls_back_silently() {
        let t = shaped_feed(false);
        let ag = agency("America/Los_Angeles");
        // shapes.txt exists but does not contain SH1.
        let sh = shape_map(vec![("OTHER", vec![(37.700, -122.400), (37.710, -122.400)], None)]);
        let (blob, stats) =
            build_index("world", &one_feed(&t, &ag, Some(&sh))).expect("build");
        let r = Reader::new(blob).expect("read back the pack");
        assert_eq!(stats.shaped_routes, 0);
        assert_eq!(stats.dropped_shape_routes, 0, "an absent shape is not a validation drop");
        assert!(r.route_shape_off(0).is_none());
        assert_shape_invariants(&r);
    }

    #[test]
    fn the_modal_shape_id_wins_when_a_pattern_disagrees() {
        let stops = parse_csv(
            "stop_id,stop_name,stop_lat,stop_lon,stop_code\n\
             S1,Alpha,37.700,-122.400,A1\n\
             S2,Beta,37.710,-122.400,B2\n",
        );
        let routes = parse_csv(
            "route_id,route_short_name,route_long_name,route_type,route_color\n\
             R1,N,Judah,0,\n",
        );
        // Two trips on SH_GOOD, one on SH_BAD: same pattern, so one RAPTOR route.
        let trips = parse_csv(
            "route_id,service_id,trip_id,trip_headsign,shape_id\n\
             R1,WK,T1,Downtown,SH_GOOD\n\
             R1,WK,T2,Downtown,SH_GOOD\n\
             R1,WK,T3,Downtown,SH_BAD\n",
        );
        let stop_times = parse_csv(
            "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n\
             T1,S1,1,08:00:00,08:00:00\n\
             T1,S2,2,08:10:00,08:10:00\n\
             T2,S1,1,09:00:00,09:00:00\n\
             T2,S2,2,09:10:00,09:10:00\n\
             T3,S1,1,10:00:00,10:00:00\n\
             T3,S2,2,10:10:00,10:10:00\n",
        );
        let calendar = parse_csv(
            "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n\
             WK,1,1,1,1,1,0,0,20240101,20241231\n",
        );
        let t = (stops, routes, trips, stop_times, calendar);
        let ag = agency("America/Los_Angeles");
        let sh = shape_map(vec![
            // The modal shape detours west, so it is identifiable in the output.
            (
                "SH_GOOD",
                vec![(37.700, -122.400), (37.705, -122.410), (37.710, -122.400)],
                None,
            ),
            ("SH_BAD", vec![(37.700, -122.400), (37.710, -122.400)], None),
        ]);
        let (blob, stats) =
            build_index("world", &one_feed(&t, &ag, Some(&sh))).expect("build");
        let r = Reader::new(blob).expect("read back the pack");
        assert_eq!(stats.multi_shape_routes, 1, "the disagreement is reported");
        assert_eq!(stats.shaped_routes, 1);
        let pts = r.shape_points(r.route_shape_off(0).expect("shaped"));
        assert!(
            pts.iter().any(|&(_, lon)| lon < -1_224_050_000),
            "the modal SH_GOOD detour is missing: {pts:?}"
        );
        assert_shape_invariants(&r);
    }

    #[test]
    fn two_routes_sharing_a_shape_store_it_once() {
        let stops = parse_csv(
            "stop_id,stop_name,stop_lat,stop_lon,stop_code\n\
             S1,Alpha,37.700,-122.400,A1\n\
             S2,Beta,37.710,-122.400,B2\n",
        );
        // Two GTFS routes over the same stop pattern -> two RAPTOR routes, one
        // shape_id between them.
        let routes = parse_csv(
            "route_id,route_short_name,route_long_name,route_type,route_color\n\
             R1,N,Judah,0,\n\
             R2,NX,Judah Express,0,\n",
        );
        let trips = parse_csv(
            "route_id,service_id,trip_id,trip_headsign,shape_id\n\
             R1,WK,T1,Downtown,SH1\n\
             R2,WK,T2,Downtown,SH1\n",
        );
        let stop_times = parse_csv(
            "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n\
             T1,S1,1,08:00:00,08:00:00\n\
             T1,S2,2,08:10:00,08:10:00\n\
             T2,S1,1,09:00:00,09:00:00\n\
             T2,S2,2,09:10:00,09:10:00\n",
        );
        let calendar = parse_csv(
            "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n\
             WK,1,1,1,1,1,0,0,20240101,20241231\n",
        );
        let t = (stops, routes, trips, stop_times, calendar);
        let ag = agency("America/Los_Angeles");
        let sh = shape_map(vec![(
            "SH1",
            vec![(37.700, -122.400), (37.705, -122.410), (37.710, -122.400)],
            None,
        )]);
        let (blob, stats) =
            build_index("world", &one_feed(&t, &ag, Some(&sh))).expect("build");
        let r = Reader::new(blob).expect("read back the pack");
        assert_eq!(r.route_count(), 2, "route_count");
        assert_eq!(stats.shaped_routes, 2);
        let a = r.route_shape_off(0).expect("route 0 shaped");
        let b = r.route_shape_off(1).expect("route 1 shaped");
        assert_eq!(a, b, "identical geometry must be stored once");
        assert_eq!(
            r.sec_bytes(SEC_SHAPE_COORDS).len(),
            shapes::encode(&r.shape_points(a)).len(),
            "SHAPE_COORDS holds exactly one blob"
        );
        assert_shape_invariants(&r);
    }

    #[test]
    fn a_shape_with_huge_gaps_roundtrips() {
        // Consecutive points ~40 km apart: an i16 delta at 1e-7 degrees would
        // overflow, which is why SHAPE_COORDS uses zigzag varints.
        let stops = parse_csv(
            "stop_id,stop_name,stop_lat,stop_lon,stop_code\n\
             S1,Alpha,37.700,-122.400,A1\n\
             S2,Beta,38.060,-122.400,B2\n",
        );
        let routes = parse_csv(
            "route_id,route_short_name,route_long_name,route_type,route_color\n\
             R1,X,Express,2,\n",
        );
        let trips = parse_csv(
            "route_id,service_id,trip_id,trip_headsign,shape_id\n\
             R1,WK,T1,North,SH1\n",
        );
        let stop_times = parse_csv(
            "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n\
             T1,S1,1,08:00:00,08:00:00\n\
             T1,S2,2,08:40:00,08:40:00\n",
        );
        let calendar = parse_csv(
            "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n\
             WK,1,1,1,1,1,0,0,20240101,20241231\n",
        );
        let t = (stops, routes, trips, stop_times, calendar);
        let ag = agency("America/Los_Angeles");
        let sh = shape_map(vec![(
            "SH1",
            vec![(37.700, -122.400), (37.880, -122.300), (38.060, -122.400)],
            None,
        )]);
        let (blob, stats) =
            build_index("world", &one_feed(&t, &ag, Some(&sh))).expect("build");
        let r = Reader::new(blob).expect("read back the pack");
        assert_eq!(stats.shaped_routes, 1);
        let pts = r.shape_points(r.route_shape_off(0).expect("shaped"));
        assert_eq!(pts.first(), Some(&(377_000_000, -1_224_000_000)));
        assert_eq!(pts.last(), Some(&(380_600_000, -1_224_000_000)));
        assert!(
            pts.iter().any(|&(lat, lon)| lat == 378_800_000 && lon == -1_223_000_000),
            "the midpoint deviates far more than the tolerance and must survive: {pts:?}"
        );
        assert_shape_invariants(&r);
    }
}
