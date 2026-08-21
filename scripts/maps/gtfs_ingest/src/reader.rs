//! A host-side reader for the `.transit` (TRX2) packs [`crate::index`] writes.
//!
//! This is a **deliberate reimplementation** of the on-device reader
//! `maps/src/main/rust/src/transit.rs`, not shared code. Two independent
//! decoders of the same bytes must agree; keeping them separate is what makes a
//! layout mistake in either one show up as a failing test or a dump diff instead
//! of a pack the device silently mis-reads. Anything learned here must be
//! mirrored there by hand — that is the point.
//!
//! Used by `index`'s tests and by `src/bin/transit_dump.rs`.

use crate::index::{HEADER_LEN, MAGIC, NONE, SECTION_COUNT, VERSION, VERSION_MIN};

// Section slot numbers. Slot `i` must describe section `i`; the device reader
// hardcodes the same numbers.
pub const SEC_STRINGS: usize = 0;
pub const SEC_STOPS: usize = 1;
pub const SEC_ROUTES: usize = 2;
pub const SEC_ROUTE_STOPS: usize = 3;
pub const SEC_ROUTE_TRIPS: usize = 4;
pub const SEC_PROFILES: usize = 5;
pub const SEC_PROFILES_IDX: usize = 6;
pub const SEC_STOP_ROUTES: usize = 7;
pub const SEC_STOP_ROUTES_IDX: usize = 8;
pub const SEC_TRANSFERS: usize = 9;
pub const SEC_TRANSFERS_IDX: usize = 10;
pub const SEC_SERVICES: usize = 11;
pub const SEC_EXCEPTIONS: usize = 12;
pub const SEC_GRID_CELL_IDS: usize = 13;
pub const SEC_GRID_CELL_OFF: usize = 14;
pub const SEC_GRID_STOPS: usize = 15;
pub const SEC_FEEDS: usize = 16;
pub const SEC_FEED_TZ: usize = 17;
pub const SEC_EXCEPTIONS_IDX: usize = 18;
pub const SEC_STOP_ROUTE_POS: usize = 19;
pub const SEC_SHAPE_COORDS: usize = 20;
pub const SEC_ROUTE_SHAPE_IDX: usize = 21;
pub const SEC_ROUTE_STOP_SHAPE: usize = 22;
pub const SEC_FEED_MOTIS_PREFIX: usize = 23;
pub const SEC_STOP_GTFS_ID: usize = 24;

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

/// Read one unsigned LEB128 varint, advancing `pos`.
pub fn read_uvarint(b: &[u8], pos: &mut usize) -> u64 {
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

fn zigzag(u: u64) -> i64 {
    ((u >> 1) as i64) ^ -((u & 1) as i64)
}

/// One ROUTES entry (a 32-byte stride).
#[derive(Clone, Copy, Debug)]
pub struct RouteRec {
    pub name_off: u32,
    pub color: u32,
    pub route_type: u32,
    pub feed_idx: u32,
    pub n_stops: u32,
    pub first_route_stop: u32,
    pub n_trips: u32,
    /// Byte offset into ROUTE_TRIPS.
    pub trips_off: u32,
}

/// One trip decoded out of a route's ROUTE_TRIPS varint block.
#[derive(Clone, Copy, Debug)]
pub struct TripRec {
    pub start_time: u32,
    pub profile_id: u32,
    pub service_idx: u32,
    pub headsign_off: u32,
}

/// One SERVICES entry (a 12-byte stride).
#[derive(Clone, Copy, Debug)]
pub struct ServiceRec {
    /// Bit `b` set = runs on day `b`, Monday = 0.
    pub weekday_mask: u8,
    pub start_date: u32,
    pub end_date: u32,
}

/// The header's grid parameters.
#[derive(Clone, Copy, Debug)]
pub struct GridParams {
    pub lat0_e7: i32,
    pub lon0_e7: i32,
    pub cell_e7: u32,
    pub cols: u32,
    pub rows: u32,
}

pub struct Reader {
    buf: Vec<u8>,
    sec: Vec<(usize, usize)>,
}

impl Reader {
    /// Validate the header and index the section directory.
    pub fn new(buf: Vec<u8>) -> Result<Reader, String> {
        if buf.len() < HEADER_LEN {
            return Err(format!("pack is {} bytes, shorter than the header", buf.len()));
        }
        let magic = ru32(&buf, 0);
        if magic != MAGIC {
            return Err(format!("bad magic {magic:#x}"));
        }
        let version = ru32(&buf, 4);
        if !(VERSION_MIN..=VERSION).contains(&version) {
            return Err(format!("version {version} outside {VERSION_MIN}..={VERSION}"));
        }
        let count = ru32(&buf, 8) as usize;
        if count != SECTION_COUNT {
            return Err(format!("section_count {count}, expected {SECTION_COUNT}"));
        }
        if buf.len() < HEADER_LEN + count * 16 {
            return Err("pack is truncated inside the section directory".to_string());
        }
        let mut sec = Vec::with_capacity(count);
        for i in 0..count {
            let base = HEADER_LEN + i * 16;
            let off = ru64(&buf, base) as usize;
            let len = ru64(&buf, base + 8) as usize;
            match off.checked_add(len) {
                Some(end) if end <= buf.len() => {}
                _ => return Err(format!("section {i} at {off}+{len} runs past the file")),
            }
            sec.push((off, len));
        }
        Ok(Reader { buf, sec })
    }

    /// Read the pack at `path`.
    pub fn open(path: &std::path::Path) -> Result<Reader, String> {
        let buf = std::fs::read(path)
            .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
        Reader::new(buf)
    }

    /// The `idx`-th u32 of the header.
    fn hdr(&self, idx: usize) -> u32 {
        ru32(&self.buf, idx * 4)
    }

    pub fn version(&self) -> u32 {
        self.hdr(1)
    }
    pub fn section_count(&self) -> u32 {
        self.hdr(2)
    }
    pub fn stop_count(&self) -> u32 {
        self.hdr(3)
    }
    pub fn route_count(&self) -> u32 {
        self.hdr(4)
    }
    pub fn trip_count(&self) -> u32 {
        self.hdr(5)
    }
    pub fn service_count(&self) -> u32 {
        self.hdr(6)
    }
    pub fn profile_count(&self) -> u32 {
        self.hdr(7)
    }
    pub fn feed_count(&self) -> u32 {
        self.hdr(8)
    }
    pub fn grid_cell_count(&self) -> u32 {
        self.hdr(9)
    }
    /// The pack name the device falls back to.
    pub fn pack_name(&self) -> String {
        self.read_str(self.hdr(10))
    }
    /// `(min_lat_e7, min_lon_e7, max_lat_e7, max_lon_e7)`.
    pub fn bbox(&self) -> (i32, i32, i32, i32) {
        (
            self.hdr(11) as i32,
            self.hdr(12) as i32,
            self.hdr(13) as i32,
            self.hdr(14) as i32,
        )
    }
    pub fn grid(&self) -> GridParams {
        GridParams {
            lat0_e7: self.hdr(15) as i32,
            lon0_e7: self.hdr(16) as i32,
            cell_e7: self.hdr(17),
            cols: self.hdr(18),
            rows: self.hdr(19),
        }
    }

    pub fn sec_bytes(&self, s: usize) -> &[u8] {
        let (o, l) = self.sec[s];
        &self.buf[o..o + l]
    }

    /// The NUL-terminated string at STRINGS offset `off`; "" for [`NONE`].
    pub fn read_str(&self, off: u32) -> String {
        if off == NONE {
            return String::new();
        }
        let s = self.sec_bytes(SEC_STRINGS);
        let start = off as usize;
        let mut n = 0;
        while start + n < s.len() && s[start + n] != 0 {
            n += 1;
        }
        String::from_utf8_lossy(&s[start..start + n]).into_owned()
    }

    /// `(lat, lon)` in degrees.
    pub fn stop_ll(&self, i: u32) -> (f64, f64) {
        let (lat, lon) = self.stop_ll_e7(i);
        (lat as f64 * 1e-7, lon as f64 * 1e-7)
    }

    pub fn stop_ll_e7(&self, i: u32) -> (i32, i32) {
        let s = self.sec_bytes(SEC_STOPS);
        let base = i as usize * 16;
        (ri32(s, base), ri32(s, base + 4))
    }

    pub fn stop_name(&self, i: u32) -> String {
        self.read_str(ru32(self.sec_bytes(SEC_STOPS), i as usize * 16 + 8))
    }

    pub fn stop_code(&self, i: u32) -> String {
        self.read_str(ru32(self.sec_bytes(SEC_STOPS), i as usize * 16 + 12))
    }

    /// The raw GTFS `stop_id` (v5 STOP_GTFS_ID).
    pub fn stop_gtfs_id(&self, i: u32) -> String {
        self.read_str(ru32(self.sec_bytes(SEC_STOP_GTFS_ID), i as usize * 4))
    }

    pub fn route(&self, r: u32) -> RouteRec {
        let s = self.sec_bytes(SEC_ROUTES);
        let base = r as usize * 32;
        RouteRec {
            name_off: ru32(s, base),
            color: ru32(s, base + 4),
            route_type: ru32(s, base + 8),
            feed_idx: ru32(s, base + 12),
            n_stops: ru32(s, base + 16),
            first_route_stop: ru32(s, base + 20),
            n_trips: ru32(s, base + 24),
            trips_off: ru32(s, base + 28),
        }
    }

    /// ROUTE_STOPS entry `i` (an absolute stop index).
    pub fn route_stop(&self, i: u32) -> u32 {
        ru32(self.sec_bytes(SEC_ROUTE_STOPS), i as usize * 4)
    }

    /// A route's stop pattern.
    pub fn route_stops(&self, rec: &RouteRec) -> Vec<u32> {
        (0..rec.n_stops).map(|p| self.route_stop(rec.first_route_stop + p)).collect()
    }

    /// Decode a route's varint trip block, in start-time order.
    pub fn route_trips(&self, rec: &RouteRec) -> Vec<TripRec> {
        let s = self.sec_bytes(SEC_ROUTE_TRIPS);
        let mut pos = rec.trips_off as usize;
        let mut prev = 0u32;
        let mut out = Vec::with_capacity(rec.n_trips as usize);
        for _ in 0..rec.n_trips {
            let start_time = prev + read_uvarint(s, &mut pos) as u32;
            out.push(TripRec {
                start_time,
                profile_id: read_uvarint(s, &mut pos) as u32,
                service_idx: read_uvarint(s, &mut pos) as u32,
                headsign_off: read_uvarint(s, &mut pos) as u32,
            });
            prev = start_time;
        }
        out
    }

    /// Profile `pid` as per-stop `(arr, dep)` offsets relative to a trip's
    /// `start_time`. `arr[0]` is negative by the first dwell.
    pub fn profile(&self, pid: u32) -> Vec<(i64, i64)> {
        let idx = self.sec_bytes(SEC_PROFILES_IDX);
        let off = ru32(idx, pid as usize * 4) as usize;
        let s = self.sec_bytes(SEC_PROFILES);
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

    pub fn feed_name(&self, feed_idx: u32) -> String {
        self.read_str(ru32(self.sec_bytes(SEC_FEEDS), feed_idx as usize * 4))
    }

    pub fn feed_tz(&self, feed_idx: u32) -> String {
        self.read_str(ru32(self.sec_bytes(SEC_FEED_TZ), feed_idx as usize * 4))
    }

    pub fn feed_motis_prefix(&self, feed_idx: u32) -> String {
        self.read_str(ru32(self.sec_bytes(SEC_FEED_MOTIS_PREFIX), feed_idx as usize * 4))
    }

    pub fn service(&self, service_idx: u32) -> ServiceRec {
        let s = self.sec_bytes(SEC_SERVICES);
        let base = service_idx as usize * 12;
        ServiceRec {
            weekday_mask: s[base],
            start_date: ru32(s, base + 4),
            end_date: ru32(s, base + 8),
        }
    }

    /// `(service_idx, date, added)` exceptions for one service, via the CSR.
    pub fn service_exceptions(&self, service_idx: u32) -> Vec<(u32, u32, u32)> {
        let idx = self.sec_bytes(SEC_EXCEPTIONS_IDX);
        let s = self.sec_bytes(SEC_EXCEPTIONS);
        let lo = ru32(idx, service_idx as usize * 4) as usize;
        let hi = ru32(idx, (service_idx as usize + 1) * 4) as usize;
        (lo..hi)
            .map(|i| (ru32(s, i * 12), ru32(s, i * 12 + 4), ru32(s, i * 12 + 8)))
            .collect()
    }

    /// `(route_idx, stop_pos)` pairs serving `stop`, via STOP_ROUTES(+_POS).
    pub fn stop_routes(&self, stop: u32) -> Vec<(u32, u32)> {
        let idx = self.sec_bytes(SEC_STOP_ROUTES_IDX);
        let routes = self.sec_bytes(SEC_STOP_ROUTES);
        let pos = self.sec_bytes(SEC_STOP_ROUTE_POS);
        let lo = ru32(idx, stop as usize * 4) as usize;
        let hi = ru32(idx, (stop as usize + 1) * 4) as usize;
        (lo..hi).map(|i| (ru32(routes, i * 4), ru32(pos, i * 4))).collect()
    }

    /// `(to_stop, secs)` footpaths from `stop`.
    pub fn transfers(&self, stop: u32) -> Vec<(u32, u32)> {
        let idx = self.sec_bytes(SEC_TRANSFERS_IDX);
        let s = self.sec_bytes(SEC_TRANSFERS);
        let lo = ru32(idx, stop as usize * 4) as usize;
        let hi = ru32(idx, (stop as usize + 1) * 4) as usize;
        (lo..hi).map(|i| (ru32(s, i * 8), ru32(s, i * 8 + 4))).collect()
    }

    /// Every non-empty grid cell as `(cell_id, stops)`, in file order (which the
    /// reader requires to be `cell_id`-ascending).
    pub fn grid_cells(&self) -> Vec<(u32, Vec<u32>)> {
        let ids = self.sec_bytes(SEC_GRID_CELL_IDS);
        let offs = self.sec_bytes(SEC_GRID_CELL_OFF);
        let stops = self.sec_bytes(SEC_GRID_STOPS);
        (0..self.grid_cell_count() as usize)
            .map(|c| {
                let lo = ru32(offs, c * 4) as usize;
                let hi = ru32(offs, (c + 1) * 4) as usize;
                (ru32(ids, c * 4), (lo..hi).map(|k| ru32(stops, k * 4)).collect())
            })
            .collect()
    }

    /// Byte offset of route `r`'s shape blob in SHAPE_COORDS, or `None`.
    pub fn route_shape_off(&self, r: u32) -> Option<usize> {
        let idx = self.sec_bytes(SEC_ROUTE_SHAPE_IDX);
        let route_count = self.route_count() as usize;
        assert_eq!(idx.len(), (route_count + 1) * 4, "ROUTE_SHAPE_IDX size");
        let off = ru32(idx, r as usize * 4);
        if off == NONE {
            return None;
        }
        let bound = ru32(idx, route_count * 4) as usize;
        assert_eq!(bound, self.sec_bytes(SEC_SHAPE_COORDS).len(), "ROUTE_SHAPE_IDX bound");
        assert!(off as usize + 4 <= bound, "shape offset {off} out of SHAPE_COORDS");
        Some(off as usize)
    }

    /// Decode the whole polyline at byte offset `off` in SHAPE_COORDS.
    pub fn shape_points(&self, off: usize) -> Vec<(i32, i32)> {
        let s = self.sec_bytes(SEC_SHAPE_COORDS);
        let n = ru32(s, off) as usize;
        let mut pos = off + 4;
        let (mut lat, mut lon) = (0i64, 0i64);
        (0..n)
            .map(|_| {
                lat += zigzag(read_uvarint(s, &mut pos));
                lon += zigzag(read_uvarint(s, &mut pos));
                (lat as i32, lon as i32)
            })
            .collect()
    }

    /// Vertex index for ROUTE_STOPS entry `i` ([`NONE`] when unshaped).
    pub fn route_stop_shape(&self, i: u32) -> u32 {
        ru32(self.sec_bytes(SEC_ROUTE_STOP_SHAPE), i as usize * 4)
    }

    /// Nearest stop to `(lat, lon)` via the sparse spatial grid, searching ±1
    /// cell exactly as the device does.
    pub fn nearest(&self, lat: f64, lon: f64) -> Option<u32> {
        let g = self.grid();
        let lat_e7 = (lat * 1e7) as i64;
        let lon_e7 = (lon * 1e7) as i64;
        let row0 = ((lat_e7 - g.lat0_e7 as i64) / g.cell_e7 as i64).max(0);
        let col0 = ((lon_e7 - g.lon0_e7 as i64) / g.cell_e7 as i64).max(0);
        let ids = self.sec_bytes(SEC_GRID_CELL_IDS);
        let offs = self.sec_bytes(SEC_GRID_CELL_OFF);
        let stops = self.sec_bytes(SEC_GRID_STOPS);
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
                if r < 0 || c < 0 || c >= g.cols as i64 {
                    continue;
                }
                if let Some(ci) = find((r * g.cols as i64 + c) as u32) {
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
