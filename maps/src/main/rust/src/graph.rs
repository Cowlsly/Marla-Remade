//! Whole-world routing graph: mmap loader, on-disk packed structs, derived
//! cost tables, spatial (Morton) indexing and delta-decoded edge geometry.
//!
//! Faithful port of the data model in the old `native-lib.cpp` (`NodeMaster`,
//! `Edge`, the `m_file` mmap loader in `init`, and the geometry helpers around
//! it). The graph is loaded once and is read-only afterwards, so it is shared
//! behind an `Arc` (see `lib.rs`).

#[cfg(unix)]
use std::ffi::CString;
use std::ptr;

// --- Travel modes (must match Kotlin RouteService.TravelMode.ordinal) ---
pub const DRIVING: i32 = 0;
pub const PUBLIC_TRANSIT: i32 = 1;
pub const WALK: i32 = 2;
pub const BICYCLE: i32 = 3;

// --- OSM-derived road types ---
pub const MOTORWAY: u8 = 1;
pub const LIVING_STREET: u8 = 9;
pub const STEPS: u8 = 15;

pub const REVERSE_GEOMETRY_FLAG: u8 = 0x40;

// --- OSM turn:lanes indication bits (one u16 mask per lane) ---
// Emitted per directed edge by `scripts/maps/osm_ingest` (`road_graph`) into
// `lanes.bin` and decoded here into per-lane turn-direction sets. A lane with no
// marking ("none"/empty) is stored as `LANE_NONE`. These bits are an on-disk
// contract with the generator; keep the two in sync.
pub const LANE_NONE: u16 = 1 << 0;
pub const LANE_THROUGH: u16 = 1 << 1;
pub const LANE_LEFT: u16 = 1 << 2;
pub const LANE_SLIGHT_LEFT: u16 = 1 << 3;
pub const LANE_SHARP_LEFT: u16 = 1 << 4;
pub const LANE_RIGHT: u16 = 1 << 5;
pub const LANE_SLIGHT_RIGHT: u16 = 1 << 6;
pub const LANE_SHARP_RIGHT: u16 = 1 << 7;
pub const LANE_REVERSE: u16 = 1 << 8;
pub const LANE_MERGE_TO_LEFT: u16 = 1 << 9;
pub const LANE_MERGE_TO_RIGHT: u16 = 1 << 10;

/// Sentinel for "no edge" where a u64 global edge index is expected.
pub const INVALID_EDGE: u64 = 0xFFFF_FFFF_FFFF_FFFF;

pub const WALK_SPEED_M_S: f64 = 4.5 / 3.6;
pub const BICYCLE_SPEED_M_S: f64 = 16.0 / 3.6;
/// Upper bound on any driving edge's effective speed (km/h). The A* heuristic is
/// scaled to this speed AND every edge's effective speed is clamped to it in
/// [`crate::geometry::get_edge_time_10ms`], which together keep the heuristic
/// CONSISTENT (never overestimates a single edge). This is required for the
/// monotonic radix heap: if an edge could be faster than the heuristic assumes,
/// a relaxed node's f-value can fall below the last popped key, the heap
/// mis-buckets it, and A* terminates on a suboptimal path (the "detours the
/// wrong way / no route" bug). 130 km/h ≈ 80 mph covers every real US limit.
pub const MAX_DRIVING_KMH: f64 = 130.0;
pub const DEG_TO_RAD: f64 = std::f64::consts::PI / 180.0;

// --- metadata.bin header ---
/// `"MARG"` (Modern-Apps Road Graph), little-endian. Added when the duplicated
/// per-OSM-node transit nodes were removed: that changed `node_count` and
/// `edge_count`, so a pack directory holding files from two vintages reads
/// garbage. The magic + version make that a clean rejection instead.
pub const GRAPH_MAGIC: u32 = 0x4752_414D;
pub const GRAPH_VERSION: u32 = 1;

// --- On-disk packed structs ---
// `#[repr(C, packed)]` reproduces the exact byte strides the generator writes
// (notably `Edge` is 14 bytes, not the 16 a naturally-aligned layout would give).
// All reads go through `read_unaligned`, so element pointers need no alignment.

#[repr(C, packed)]
#[derive(Clone, Copy)]
pub struct NodeMaster {
    pub lat_e7: i32,
    pub lon_e7: i32,
    pub edge_ptr: u64,
}

#[repr(C, packed)]
#[derive(Clone, Copy)]
pub struct Edge {
    pub target: u32,
    pub dist_mm: u32,
    pub name_offset: u32,
    pub type_: u8,
    pub speed_limit: u8,
}

#[derive(Clone, Copy)]
pub struct LatLon {
    pub lat_e7: i32,
    pub lon_e7: i32,
}

/// Owns a read-only `mmap` region and unmaps it on drop.
pub(crate) struct MmapRegion {
    ptr: *mut libc::c_void,
    pub(crate) len: usize,
}

// The graph is only ever read after init, so sharing the raw pointer across
// threads is sound.
unsafe impl Send for MmapRegion {}
unsafe impl Sync for MmapRegion {}

impl MmapRegion {
    /// mmap `path` read-only. Returns `None` for missing/empty/unreadable files,
    /// mirroring the C++ `m_file` lambda.
    #[cfg(unix)]
    pub(crate) fn map(path: &str) -> Option<MmapRegion> {
        let c = CString::new(path).ok()?;
        unsafe {
            let fd = libc::open(c.as_ptr(), libc::O_RDONLY);
            if fd < 0 {
                return None;
            }
            let end = libc::lseek(fd, 0, libc::SEEK_END);
            if end <= 0 {
                libc::close(fd);
                return None;
            }
            let len = end as usize;
            let ptr = libc::mmap(
                ptr::null_mut(),
                len,
                libc::PROT_READ,
                libc::MAP_SHARED,
                fd,
                0,
            );
            libc::close(fd);
            if ptr == libc::MAP_FAILED {
                return None;
            }
            Some(MmapRegion { ptr, len })
        }
    }

    /// This crate ships to Android, so the real loader is Unix-only. The stub
    /// exists purely so a host `cargo test` can build and exercise the parts
    /// that don't need a pack file (the in-memory transit index).
    #[cfg(not(unix))]
    pub(crate) fn map(_path: &str) -> Option<MmapRegion> {
        None
    }

    #[inline]
    pub(crate) fn base(&self) -> *const u8 {
        self.ptr as *const u8
    }
}

#[cfg(unix)]
impl Drop for MmapRegion {
    fn drop(&mut self) {
        unsafe {
            libc::munmap(self.ptr, self.len);
        }
    }
}

/// Read a `Copy` value of type `T` from `base` at element index `idx`
/// (byte offset `idx * size_of::<T>()`), tolerating any alignment.
#[inline(always)]
pub(crate) unsafe fn read_at<T: Copy>(base: *const u8, idx: usize) -> T {
    (base.add(idx * std::mem::size_of::<T>()) as *const T).read_unaligned()
}

/// The whole-world routing dataset. Immutable after construction.
pub struct Graph {
    // mmap regions kept alive for the lifetime of the graph.
    _nodes_region: MmapRegion,
    _edges_region: MmapRegion,
    _intermediate_region: Option<MmapRegion>,
    _road_names_region: Option<MmapRegion>,
    _lanes_region: Option<MmapRegion>,

    nodes: *const u8,
    pub node_count: u32, // real nodes; nodes.bin has node_count + 1 (sentinel)
    edges: *const u8,
    pub edge_count: u64,

    intermediate_edge_offsets: *const u8, // u64[edge_count + 1] byte offsets
    intermediate_data: *const u8,         // delta-encoded coordinate bytes
    has_intermediate: bool,

    // Real OSM turn-lane data, indexed by edge (see `edge_lane_masks`). Optional:
    // absent until the graph is regenerated with lane extraction, in which case
    // routing falls back to topology-inferred lanes.
    lane_edge_offsets: *const u8, // u64[edge_count + 1] byte offsets into lane_data
    lane_data: *const u8,         // packed u16 per-lane masks
    has_lanes: bool,

    road_names: *const u8,
    pub road_names_size: usize,

    // Derived cost tables (computed in `load`, matching the C++ `init`).
    pub lon_to_mm_scale: [u32; 4096],
    pub time_scale_fixed: [u64; 4],
    pub edge_time_multipliers: [[u64; 16]; 4],
}

unsafe impl Send for Graph {}
unsafe impl Sync for Graph {}

impl Graph {
    /// Load the graph from `base` (a directory path, trailing slash optional).
    /// Returns `None` if the mandatory nodes/edges/metadata files are absent, if
    /// `metadata.bin` is not a [`GRAPH_VERSION`] header, or if `intermediate.bin`
    /// is too short for this `edge_count` (a mixed-vintage pack directory).
    pub fn load(base: &str) -> Option<Graph> {
        let mut base = base.to_string();
        if !base.is_empty() && !base.ends_with('/') {
            base.push('/');
        }

        // metadata.bin: u32 magic, u32 version, u64 node_count. Scoped so the
        // mapping is released before the big regions are mapped.
        let node_count = {
            let meta = MmapRegion::map(&format!("{base}metadata.bin"))?;
            if meta.len < 16 {
                return None;
            }
            let magic = unsafe { read_at::<u32>(meta.base(), 0) };
            let version = unsafe { read_at::<u32>(meta.base(), 1) };
            if magic != GRAPH_MAGIC || version != GRAPH_VERSION {
                return None;
            }
            (unsafe { read_at::<u64>(meta.base().add(8), 0) }) as u32
        };

        let nodes_region = MmapRegion::map(&format!("{base}nodes.bin"))?;
        let edges_region = MmapRegion::map(&format!("{base}edges.bin"))?;
        let edge_count = (edges_region.len / std::mem::size_of::<Edge>()) as u64;

        // intermediate.bin: [ u64 edge_offsets[edge_count + 1] ][ coord blob ].
        // Length-validated: the offset array is sized from `edge_count`, so a
        // stale file from a different graph vintage would be read out of bounds.
        let offsets_bytes = (edge_count + 1) * std::mem::size_of::<u64>() as u64;
        let intermediate_region = MmapRegion::map(&format!("{base}intermediate.bin"))
            .filter(|r| r.len as u64 >= offsets_bytes);
        let (intermediate_edge_offsets, intermediate_data, has_intermediate) =
            match &intermediate_region {
                Some(r) => {
                    let offsets = r.base();
                    let data = unsafe { r.base().add(offsets_bytes as usize) };
                    (offsets, data, true)
                }
                None => (ptr::null(), ptr::null(), false),
            };

        let road_names_region = MmapRegion::map(&format!("{base}road_names.bin"));
        let (road_names, road_names_size) = match &road_names_region {
            Some(r) => (r.base(), r.len),
            None => (ptr::null(), 0),
        };

        // lanes.bin: [ u64 edge_offsets[edge_count + 1] ][ u16 lane-mask blob ].
        // Optional and validated: a truncated/mismatched file disables real
        // lanes so routing falls back to topology inference.
        let lanes_region = MmapRegion::map(&format!("{base}lanes.bin"));
        let (lane_edge_offsets, lane_data, has_lanes) = match &lanes_region {
            Some(r) if r.len as u64 >= offsets_bytes => {
                let offsets = r.base();
                let data = unsafe { r.base().add(offsets_bytes as usize) };
                (offsets, data, true)
            }
            _ => (ptr::null(), ptr::null(), false),
        };

        let nodes = nodes_region.base();
        let edges = edges_region.base();

        // --- Derived tables (identical formulas to the C++ init) ---
        let mut lon_to_mm_scale = [0u32; 4096];
        for (i, scale) in lon_to_mm_scale.iter_mut().enumerate() {
            let lat_deg = (((i as i64 - 2048) << 19) as f64) / 1e7;
            *scale = ((111_139_000.0 / 1e7) * (lat_deg * DEG_TO_RAD).cos() * 1024.0) as u32;
        }

        let calc_scale =
            |speed_m_s: f64| -> u64 { ((100.0 / (speed_m_s * 1000.0)) * 4_294_967_296.0) as u64 };

        let mut time_scale_fixed = [0u64; 4];
        time_scale_fixed[WALK as usize] = calc_scale(WALK_SPEED_M_S);
        time_scale_fixed[BICYCLE as usize] = calc_scale(BICYCLE_SPEED_M_S);
        // Heuristic speed MUST be >= the fastest achievable edge speed (which is
        // clamped to MAX_DRIVING_KMH in get_edge_time_10ms) so the heuristic stays
        // consistent for the monotonic radix heap. See MAX_DRIVING_KMH.
        time_scale_fixed[DRIVING as usize] = calc_scale(MAX_DRIVING_KMH / 3.6);
        // PUBLIC_TRANSIT only ever walks in the road graph (see is_mode_allowed),
        // so it MUST use walk speed. Leaving it at a vehicle speed makes the A*
        // heuristic wildly over-optimistic: still correct, but catastrophically
        // slow because almost the whole graph gets expanded.
        time_scale_fixed[PUBLIC_TRANSIT as usize] = calc_scale(WALK_SPEED_M_S);

        let mut edge_time_multipliers = [[0u64; 16]; 4];
        for (m, row) in edge_time_multipliers.iter_mut().enumerate() {
            for (r, multiplier) in row.iter_mut().enumerate() {
                let speed_m_s = if m == DRIVING as usize {
                    match r as u8 {
                        1 => 105.0 / 3.6, // MOTORWAY
                        2 => 85.0 / 3.6,  // TRUNK
                        3 => 65.0 / 3.6,  // PRIMARY
                        4 => 55.0 / 3.6,  // SECONDARY
                        5 => 45.0 / 3.6,  // TERTIARY
                        _ => 30.0 / 3.6,
                    }
                } else if m == BICYCLE as usize {
                    BICYCLE_SPEED_M_S
                } else {
                    WALK_SPEED_M_S
                };
                *multiplier = ((100.0 / (speed_m_s * 1000.0)) * 4_294_967_296.0) as u64;
            }
        }

        Some(Graph {
            _nodes_region: nodes_region,
            _edges_region: edges_region,
            _intermediate_region: intermediate_region,
            _road_names_region: road_names_region,
            _lanes_region: lanes_region,
            nodes,
            node_count,
            edges,
            edge_count,
            intermediate_edge_offsets,
            intermediate_data,
            has_intermediate,
            lane_edge_offsets,
            lane_data,
            has_lanes,
            road_names,
            road_names_size,
            lon_to_mm_scale,
            time_scale_fixed,
            edge_time_multipliers,
        })
    }

    // --- Raw accessors (all unaligned-safe) ---

    #[inline]
    pub fn node(&self, id: u32) -> NodeMaster {
        // Callers guarantee id <= node_count (sentinel index is valid).
        unsafe { read_at::<NodeMaster>(self.nodes, id as usize) }
    }

    /// Safe node fetch: returns a zeroed node for out-of-range ids, mirroring
    /// the C++ `get_node` fallback.
    #[inline]
    pub fn get_node(&self, id: u32) -> NodeMaster {
        if self.nodes.is_null() || id >= self.node_count {
            return NodeMaster {
                lat_e7: 0,
                lon_e7: 0,
                edge_ptr: 0,
            };
        }
        self.node(id)
    }

    #[inline]
    pub fn edge(&self, idx: u64) -> Edge {
        unsafe { read_at::<Edge>(self.edges, idx as usize) }
    }

    /// Real OSM per-lane turn-indication masks for directed edge `idx`, ordered
    /// left→right. Each `u16` is a set of `LANE_*` bits. Returns `None` when the
    /// graph has no lane data or this edge carries none (callers then fall back
    /// to topology-inferred lanes).
    pub fn edge_lane_masks(&self, idx: u64) -> Option<Vec<u16>> {
        if !self.has_lanes || idx >= self.edge_count {
            return None;
        }
        let start = unsafe { read_at::<u64>(self.lane_edge_offsets, idx as usize) };
        let end = unsafe { read_at::<u64>(self.lane_edge_offsets, idx as usize + 1) };
        if end <= start {
            return None;
        }
        let count = ((end - start) / std::mem::size_of::<u16>() as u64) as usize;
        if count == 0 {
            return None;
        }
        let mut out = Vec::with_capacity(count);
        for k in 0..count {
            let byte_off = start as usize + k * std::mem::size_of::<u16>();
            let mask = unsafe { (self.lane_data.add(byte_off) as *const u16).read_unaligned() };
            out.push(mask);
        }
        Some(out)
    }

    #[inline]
    fn intermediate_offset(&self, idx: u64) -> u64 {
        unsafe { read_at::<u64>(self.intermediate_edge_offsets, idx as usize) }
    }

    /// Borrow a NUL-terminated road/feed/stop name from the string pool at
    /// `offset`. Returns `None` for the sentinel or out-of-range offsets.
    pub fn road_name(&self, offset: u32) -> Option<String> {
        if offset == 0xFFFF_FFFF || (offset as usize) >= self.road_names_size {
            return None;
        }
        Some(self.cstr_at(offset as usize))
    }

    /// Read the NUL-terminated string starting at byte `offset` in the pool.
    fn cstr_at(&self, offset: usize) -> String {
        unsafe {
            let start = self.road_names.add(offset);
            let mut len = 0usize;
            while offset + len < self.road_names_size && *start.add(len) != 0 {
                len += 1;
            }
            let slice = std::slice::from_raw_parts(start, len);
            String::from_utf8_lossy(slice).into_owned()
        }
    }

    /// Largest node index whose `edge_ptr <= edge_idx` (edge_ptr is monotonic).
    pub fn find_node_idx_for_edge(&self, edge_idx: u64) -> u32 {
        let mut low: i64 = 0;
        let mut high: i64 = self.node_count as i64 - 1;
        let mut res: u32 = 0;
        while low <= high {
            let mid = low + (high - low) / 2;
            if self.node(mid as u32).edge_ptr <= edge_idx {
                res = mid as u32;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        res
    }

    /// 64-bit Morton code from lat/lon degrees (matches C++ `latlng_to_spatial`).
    pub fn latlng_to_spatial(lat: f64, lon: f64) -> u64 {
        let x = (lon + 180.0) / 360.0;
        let y = (lat + 90.0) / 180.0;
        let ix = (x * 4_294_967_295.0) as u32;
        let iy = (y * 4_294_967_295.0) as u32;
        let mut res: u64 = 0;
        for i in 0..32u64 {
            res |= (((ix >> i) & 1) as u64) << (2 * i);
            res |= (((iy >> i) & 1) as u64) << (2 * i + 1);
        }
        res
    }

    #[inline]
    pub fn node_spatial_id(node: &NodeMaster) -> u64 {
        Graph::latlng_to_spatial(node.lat_e7 as f64 * 1e-7, node.lon_e7 as f64 * 1e-7)
    }

    /// Decode the delta-encoded coordinate blob for `edge_idx` into `out`.
    /// Returns `Some((count, is_reversed))` when geometry is available,
    /// mirroring `get_edge_coordinates`. `out` must hold at least 256 points.
    pub fn get_edge_coordinates(&self, edge_idx: u64, out: &mut [LatLon]) -> Option<(u32, bool)> {
        if self.edges.is_null() || edge_idx >= self.edge_count {
            return None;
        }
        let e = self.edge(edge_idx);

        if e.type_ & REVERSE_GEOMETRY_FLAG != 0 {
            let u_global = self.find_node_idx_for_edge(edge_idx);
            let v_global = e.target;
            if v_global < self.node_count && self.has_intermediate {
                let s = self.node(v_global).edge_ptr;
                let e_ptr = self.node(v_global + 1).edge_ptr; // sentinel valid
                for k in s..e_ptr {
                    if self.edge(k).target == u_global {
                        let start_byte = self.intermediate_offset(k);
                        let end_byte = self.intermediate_offset(k + 1);
                        let cnt =
                            self.decode_edge_coords(start_byte, (end_byte - start_byte) as u32, out);
                        return Some((cnt, true));
                    }
                }
            }
            None
        } else if self.has_intermediate {
            let start_byte = self.intermediate_offset(edge_idx);
            let end_byte = self.intermediate_offset(edge_idx + 1);
            let cnt = self.decode_edge_coords(start_byte, (end_byte - start_byte) as u32, out);
            Some((cnt, false))
        } else {
            None
        }
    }

    /// Delta-decode `byte_len` bytes at byte offset `data_off` in the
    /// intermediate blob into `out`. Returns the number of decoded points.
    fn decode_edge_coords(&self, data_off: u64, byte_len: u32, out: &mut [LatLon]) -> u32 {
        if byte_len < 8 {
            return 0;
        }
        let data = unsafe { self.intermediate_data.add(data_off as usize) };
        // First point is absolute (int32 lat_e7, int32 lon_e7).
        let mut lat = unsafe { (data as *const i32).read_unaligned() };
        let mut lon = unsafe { (data.add(4) as *const i32).read_unaligned() };
        out[0] = LatLon {
            lat_e7: lat,
            lon_e7: lon,
        };

        let mut count: u32 = 1;
        let mut off: u32 = 8;
        while off + 4 <= byte_len && count < 256 {
            let d_lat = unsafe { (data.add(off as usize) as *const i16).read_unaligned() };
            let d_lon = unsafe { (data.add(off as usize + 2) as *const i16).read_unaligned() };
            lat = lat.wrapping_add(d_lat as i32);
            lon = lon.wrapping_add(d_lon as i32);
            out[count as usize] = LatLon {
                lat_e7: lat,
                lon_e7: lon,
            };
            count += 1;
            off += 4;
        }
        count
    }
}

/// Fetch point `idx` from a decoded coordinate buffer, honouring reversal.
#[inline]
pub fn get_pt_at(coords: &[LatLon], count: u32, is_reversed: bool, idx: u32) -> LatLon {
    if is_reversed {
        coords[(count - 1 - idx) as usize]
    } else {
        coords[idx as usize]
    }
}
