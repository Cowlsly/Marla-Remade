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

/// Set in an [`Edge::type_`] byte to mean "this edge stores no geometry of its
/// own; decode its twin's and read it backwards".
///
/// `type_` is therefore a bitfield, not a plain road class:
/// `[bits 0-5 road class][bit 6 reverse geometry][bit 7 reserved]`. Anything
/// interpreting it as a road class must mask with
/// [`crate::geometry::ROAD_TYPE_MASK`] first.
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
/// Version 2 narrowed `nodes.bin` to 12-byte records, made `lanes.bin` a sparse
/// index and gave `intermediate.bin` two-level offsets — 50.8 GB of planet became
/// 34 GB. Version 3 gave `intermediate.bin` a presence bitmap and dropped both
/// endpoints of every stored polyline, taking it to ~29 GB. There is no dual-path
/// reader: an older pack is rejected.
pub const GRAPH_VERSION: u32 = 3;

/// Geometry edges per coarse block in `intermediate.bin`'s two-level offset table.
///
/// A per-edge blob is at most 1016 bytes (4 per interior point, capped at
/// `MAX_POINTS - 2` = 254 of them by [`Graph::decode_edge_coords`]), so a block
/// spans at most 32 x 1016 = 32,512 bytes and the within-block half fits a `u16`.
/// That bound is the invariant the whole layout rests on; the generator asserts
/// it. The table is indexed by an edge's rank in the presence bitmap, so every
/// entry in a block describes a real blob.
pub const INTERMEDIATE_BLOCK: u64 = 32;

/// Bytes of presence bitmap one entry of `intermediate.bin`'s rank index covers.
/// 64 bytes is 512 edges and one cache line, so a rank costs one `u64` load plus
/// at most eight `popcount`s. `RANK_BLOCK_BYTES` in the generator, and the two
/// must agree.
pub const GEOMETRY_RANK_BLOCK_BYTES: u64 = 64;

// --- On-disk packed structs ---
// `#[repr(C, packed)]` reproduces the exact byte strides the generator writes
// (notably `Edge` is 14 bytes, not the 16 a naturally-aligned layout would give).
// All reads go through `read_unaligned`, so element pointers need no alignment.

/// A `nodes.bin` record: 12 bytes, with `edge_ptr` narrowed to a `u32`.
///
/// Planet has 1.07 G directed edges, which leaves 4x headroom, and the four bytes
/// saved per node are 1.7 GB. [`Graph::node`] widens it into [`NodeMaster`], so
/// nothing above this file sees the narrower field.
#[repr(C, packed)]
#[derive(Clone, Copy)]
struct NodeRec {
    lat_e7: i32,
    lon_e7: i32,
    edge_ptr: u32,
}

/// One `lanes.bin` index entry: the edge that carries lanes, and where its masks
/// start in the blob.
#[repr(C, packed)]
#[derive(Clone, Copy)]
struct LaneEntry {
    edge_idx: u32,
    blob_off: u32,
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

/// The in-memory view of a `nodes.bin` record, with `edge_ptr` widened back to a
/// `u64` so nothing above [`Graph::node`] cares that the file stores 32 bits.
#[derive(Clone, Copy)]
pub struct NodeMaster {
    pub lat_e7: i32,
    pub lon_e7: i32,
    pub edge_ptr: u64,
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
    _intermediate_region: MmapRegion,
    _road_names_region: Option<MmapRegion>,
    _lanes_region: Option<MmapRegion>,

    nodes: *const u8,
    pub node_count: u32, // real nodes; nodes.bin has node_count + 1 (sentinel)
    edges: *const u8,
    pub edge_count: u64,

    // `intermediate.bin`, blob first and every table in a trailer. `off(g) =
    // coarse[g / INTERMEDIATE_BLOCK] + within[g]` for the g-th *geometry* edge,
    // where g is the edge's rank in `present`.
    intermediate_data: *const u8,    // delta-encoded coordinate bytes, at offset 0
    intermediate_rank: *const u8,    // u64[E.div_ceil(512) + 1]
    intermediate_present: *const u8, // u8[E.div_ceil(8)], one bit per directed edge
    intermediate_coarse: *const u8,  // u64[G.div_ceil(BLOCK) + 1]
    intermediate_within: *const u8,  // u16[G + 1]

    // Real OSM turn-lane data (see `edge_lane_masks`). Sparse: only the edges that
    // actually carry `turn:lanes` appear, which on a planet is 2.9 M of 1.07 G.
    // Optional as a whole too — absent until the graph is regenerated with lane
    // extraction, in which case routing falls back to topology-inferred lanes.
    lane_index: *const u8, // LaneEntry[lane_edges + 1], ascending by edge_idx
    lane_edges: u32,
    lane_data: *const u8, // packed u16 per-lane masks

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
    /// Returns `None` if any mandatory file is absent, if `metadata.bin` is not a
    /// [`GRAPH_VERSION`] header, or if `intermediate.bin` is too short for this
    /// `edge_count` (a mixed-vintage pack directory).
    ///
    /// `intermediate.bin` is mandatory, not optional. The generator collapses
    /// degree-2 chains, so an edge is a whole road between junctions and its
    /// polyline is the only record of the road's shape. Without the file,
    /// `find_nearest_edge` would project onto a straight chord spanning an entire
    /// street: snapping and drawn geometry would both be wrong, not merely
    /// coarse. Refusing to load says so instead of routing badly.
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

        // intermediate.bin:
        //   [ blob ][ u64 rank[..] ][ u8 present[..] ][ u64 coarse[..] ]
        //   [ u16 within[..] ][ u64 G ]
        // Everything is recoverable from the last 8 bytes plus `edge_count`: the
        // tables are sized from `G` and from `E`, and the blob is whatever is left
        // over at offset 0. Validated on both axes, because a stale file from a
        // different graph vintage would otherwise be read out of bounds.
        let intermediate_region = MmapRegion::map(&format!("{base}intermediate.bin"))?;
        let inter_len = intermediate_region.len as u64;
        if inter_len < 8 {
            return None;
        }
        let geometry_edges =
            unsafe { read_at::<u64>(intermediate_region.base().add((inter_len - 8) as usize), 0) };
        // `G` can never exceed the number of directed edges. Checking it before
        // anything is sized from it is also what stops a garbage trailer from
        // overflowing the table arithmetic below into a plausible-looking total.
        if geometry_edges > edge_count {
            return None;
        }
        let rank_bytes = (edge_count.div_ceil(GEOMETRY_RANK_BLOCK_BYTES * 8) + 1)
            * std::mem::size_of::<u64>() as u64;
        let present_bytes = edge_count.div_ceil(8);
        let coarse_bytes = (geometry_edges.div_ceil(INTERMEDIATE_BLOCK) + 1)
            * std::mem::size_of::<u64>() as u64;
        let within_bytes = (geometry_edges + 1) * std::mem::size_of::<u16>() as u64;
        let trailer = rank_bytes + present_bytes + coarse_bytes + within_bytes + 8;
        if inter_len < trailer {
            return None;
        }
        let blob_bytes = inter_len - trailer;
        let intermediate_data = intermediate_region.base();
        let intermediate_rank = unsafe { intermediate_data.add(blob_bytes as usize) };
        let intermediate_present = unsafe { intermediate_rank.add(rank_bytes as usize) };
        let intermediate_coarse = unsafe { intermediate_present.add(present_bytes as usize) };
        let intermediate_within = unsafe { intermediate_coarse.add(coarse_bytes as usize) };
        // The two halves of the trailer have to agree with each other and with the
        // blob: the sentinel offset is the blob's length, and the rank total is
        // `G`. Both are cheap, and between them they catch a truncated file, a
        // mismatched vintage and a generator that lost count.
        let sentinel = unsafe {
            read_at::<u64>(
                intermediate_coarse,
                (geometry_edges / INTERMEDIATE_BLOCK) as usize,
            ) + u64::from(read_at::<u16>(intermediate_within, geometry_edges as usize))
        };
        if sentinel != blob_bytes {
            return None;
        }
        let rank_total = unsafe {
            read_at::<u64>(
                intermediate_rank,
                (edge_count.div_ceil(GEOMETRY_RANK_BLOCK_BYTES * 8)) as usize,
            )
        };
        if rank_total != geometry_edges {
            return None;
        }

        let road_names_region = MmapRegion::map(&format!("{base}road_names.bin"));
        let (road_names, road_names_size) = match &road_names_region {
            Some(r) => (r.base(), r.len),
            None => (ptr::null(), 0),
        };

        // lanes.bin: [ u32 n ][ LaneEntry[n + 1] ][ u16 lane-mask blob ]. The index
        // is sized by its own header rather than by `edge_count`, so it is validated
        // against that; a truncated/mismatched file disables real lanes so routing
        // falls back to topology inference.
        let lanes_region = MmapRegion::map(&format!("{base}lanes.bin"));
        let lanes = lanes_region.as_ref().and_then(|r| {
            if r.len < 4 {
                return None;
            }
            let n = unsafe { read_at::<u32>(r.base(), 0) };
            let index = unsafe { r.base().add(4) };
            let index_bytes = 4 + (u64::from(n) + 1) * std::mem::size_of::<LaneEntry>() as u64;
            if (r.len as u64) < index_bytes {
                return None;
            }
            // The sentinel entry's offset is the blob length, so checking it
            // validates the blob as well as the index. That matters because
            // `edge_lane_masks` reads at offsets taken straight from the file.
            let sentinel = unsafe { read_at::<LaneEntry>(index, n as usize) };
            if (r.len as u64) < index_bytes + u64::from(sentinel.blob_off) {
                return None;
            }
            // Nothing else in this file mentions `edge_count`, so a stale-vintage
            // `lanes.bin` whose own header is self-consistent would otherwise load
            // and attribute masks to the wrong edges. Entries ascend, so bounding
            // the last one bounds them all.
            if n > 0 {
                let last = unsafe { read_at::<LaneEntry>(index, n as usize - 1) };
                if u64::from(last.edge_idx) >= edge_count {
                    return None;
                }
            }
            Some((index, n, unsafe { r.base().add(index_bytes as usize) }))
        });
        let (lane_index, lane_edges, lane_data) = lanes.unwrap_or((ptr::null(), 0, ptr::null()));

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
            intermediate_data,
            intermediate_rank,
            intermediate_present,
            intermediate_coarse,
            intermediate_within,
            lane_index,
            lane_edges,
            lane_data,
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
        let r = unsafe { read_at::<NodeRec>(self.nodes, id as usize) };
        NodeMaster {
            lat_e7: r.lat_e7,
            lon_e7: r.lon_e7,
            edge_ptr: u64::from(r.edge_ptr),
        }
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
    ///
    /// The index is sparse, so "this edge has no lanes" is spelled as absence from
    /// it and found by binary search. `turn:lanes` is rare — 2.9 M of a planet's
    /// 1.07 G edges — and this is called once per maneuver rather than per edge,
    /// so ~22 probes buys back 8.6 GB of dense offset table.
    pub fn edge_lane_masks(&self, idx: u64) -> Option<Vec<u16>> {
        if self.lane_index.is_null() || idx >= self.edge_count {
            return None;
        }
        let want = u32::try_from(idx).ok()?;
        let entry = |i: u32| unsafe { read_at::<LaneEntry>(self.lane_index, i as usize) };
        // Lower bound over the real entries; the trailing sentinel is only ever
        // read for its offset, which is how the last edge gets a length.
        let mut lo = 0u32;
        let mut hi = self.lane_edges;
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            if entry(mid).edge_idx < want {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if lo >= self.lane_edges || entry(lo).edge_idx != want {
            return None;
        }
        let start = entry(lo).blob_off;
        let end = entry(lo + 1).blob_off;
        if end <= start {
            return None;
        }
        let count = ((end - start) as usize) / std::mem::size_of::<u16>();
        let mut out = Vec::with_capacity(count);
        for k in 0..count {
            let byte_off = start as usize + k * std::mem::size_of::<u16>();
            let mask = unsafe { (self.lane_data.add(byte_off) as *const u16).read_unaligned() };
            out.push(mask);
        }
        Some(out)
    }

    /// True when edge `idx` stores a polyline of its own.
    ///
    /// 70.4% of a planet's directed edges do not: their polyline is either their
    /// twin's (see [`REVERSE_GEOMETRY_FLAG`]) or nothing but the chord between
    /// their endpoints, which needs no bytes to say.
    #[inline]
    fn stores_geometry(&self, idx: u64) -> bool {
        let byte = unsafe { read_at::<u8>(self.intermediate_present, (idx / 8) as usize) };
        byte & (1u8 << (idx % 8)) != 0
    }

    /// How many edges below `idx` store a polyline, i.e. the offset-table index of
    /// edge `idx` itself when its own bit is set.
    ///
    /// One `u64` load for the block, then at most 64 bytes of `popcount`. Only ever
    /// meaningful for an edge whose bit *is* set, so callers must test
    /// [`Graph::stores_geometry`] first or they will read another edge's blob.
    #[inline]
    fn geometry_rank(&self, idx: u64) -> u64 {
        let byte = idx / 8;
        let block = byte / GEOMETRY_RANK_BLOCK_BYTES;
        let mut n = unsafe { read_at::<u64>(self.intermediate_rank, block as usize) };
        for b in block * GEOMETRY_RANK_BLOCK_BYTES..byte {
            n += u64::from(unsafe { read_at::<u8>(self.intermediate_present, b as usize) }.count_ones());
        }
        let partial = unsafe { read_at::<u8>(self.intermediate_present, byte as usize) };
        n + u64::from((partial & ((1u8 << (idx % 8)) - 1)).count_ones())
    }

    /// Byte offset of the `g`-th geometry edge's polyline in the intermediate blob.
    ///
    /// Two loads instead of one: a `u64` per [`INTERMEDIATE_BLOCK`] geometry edges
    /// plus a `u16` each. Same cost profile, and 1.5 GB smaller at planet scale
    /// than one `u64` apiece.
    #[inline]
    fn geometry_offset(&self, g: u64) -> u64 {
        let coarse =
            unsafe { read_at::<u64>(self.intermediate_coarse, (g / INTERMEDIATE_BLOCK) as usize) };
        let within = unsafe { read_at::<u16>(self.intermediate_within, g as usize) };
        coarse + u64::from(within)
    }

    /// `(byte offset, length)` of edge `idx`'s stored polyline, or `None` when it
    /// stores none.
    #[inline]
    fn intermediate_range(&self, idx: u64) -> Option<(u64, u32)> {
        if !self.stores_geometry(idx) {
            return None;
        }
        let g = self.geometry_rank(idx);
        let start = self.geometry_offset(g);
        let end = self.geometry_offset(g + 1);
        Some((start, (end - start) as u32))
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
    /// Returns `Some((count, is_reversed))`, with `count == 0` when the edge stores
    /// no polyline. `out` must hold at least 256 points.
    ///
    /// Prefer [`Graph::get_edge_coordinates_from`] wherever the source node is
    /// already known: this wrapper has to recover it with a ~29-probe binary
    /// search, and the stored blob is now interior-only, so the source is needed to
    /// decode at all rather than merely to find the twin.
    pub fn get_edge_coordinates(&self, edge_idx: u64, out: &mut [LatLon]) -> Option<(u32, bool)> {
        if self.edges.is_null() || edge_idx >= self.edge_count {
            return None;
        }
        let source = self.find_node_idx_for_edge(edge_idx);
        self.get_edge_coordinates_from(source, edge_idx, out)
    }

    /// [`Graph::get_edge_coordinates`] for a caller that already holds `edge_idx`'s
    /// source node — which every call site iterating a node's edge range does.
    ///
    /// `source` must really be the node whose range `edge_idx` falls in. It seeds
    /// the decode, so a wrong one silently draws the polyline from the wrong place.
    pub fn get_edge_coordinates_from(
        &self,
        source: u32,
        edge_idx: u64,
        out: &mut [LatLon],
    ) -> Option<(u32, bool)> {
        if self.edges.is_null() || edge_idx >= self.edge_count {
            return None;
        }
        let e = self.edge(edge_idx);
        if source >= self.node_count || e.target >= self.node_count {
            return None;
        }

        if e.type_ & REVERSE_GEOMETRY_FLAG != 0 {
            // The twin runs `target -> source`, so it is seeded and terminated the
            // other way round. `get_pt_at` is what flips the result for callers.
            let s = self.node(e.target).edge_ptr;
            let e_ptr = self.node(e.target + 1).edge_ptr; // sentinel valid
            for k in s..e_ptr {
                if self.edge(k).target == source {
                    let cnt = self.decode_edge_coords(k, e.target, source, out);
                    return Some((cnt, true));
                }
            }
            None
        } else {
            let cnt = self.decode_edge_coords(edge_idx, source, e.target, out);
            Some((cnt, false))
        }
    }

    /// Delta-decode edge `idx`'s polyline into `out`, seeded with node `source`'s
    /// coordinates and terminated with node `target`'s. Returns the number of
    /// decoded points, or 0 when the edge stores no polyline.
    ///
    /// The blob holds *only* the interior points, because the first and last are
    /// always the edge's own endpoints and `nodes.bin` already has both. Nothing
    /// here distinguishes an absent blob from an empty one; the presence bitmap
    /// does, in [`Graph::intermediate_range`].
    fn decode_edge_coords(&self, idx: u64, source: u32, target: u32, out: &mut [LatLon]) -> u32 {
        let Some((data_off, byte_len)) = self.intermediate_range(idx) else {
            return 0;
        };
        let a = self.node(source);
        let mut lat = a.lat_e7;
        let mut lon = a.lon_e7;
        out[0] = LatLon {
            lat_e7: lat,
            lon_e7: lon,
        };

        let data = unsafe { self.intermediate_data.add(data_off as usize) };
        let mut count: u32 = 1;
        let mut off: u32 = 0;
        // One slot is reserved for the target, so the interior stops one short of
        // the 256 points every caller's buffer holds.
        while off + 4 <= byte_len && count + 1 < 256 {
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

        let b = self.node(target);
        out[count as usize] = LatLon {
            lat_e7: b.lat_e7,
            lon_e7: b.lon_e7,
        };
        count + 1
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
