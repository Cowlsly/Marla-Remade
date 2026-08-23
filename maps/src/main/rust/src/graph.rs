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
/// endpoints of every stored polyline, taking it to ~29 GB. Version 4 changes no
/// layout at all: it adds `edge_count` to `metadata.bin` so that nothing is
/// derived from a file's *length*, which is what the next round of record
/// narrowing would silently invalidate. There is no dual-path reader: an older
/// pack is rejected.
pub const GRAPH_VERSION: u32 = 4;

/// Sentinel `name_offset` meaning "this edge has no name".
///
/// `NO_NAME` in the generator, and an on-disk contract with it. Callers should
/// prefer [`Graph::edge_name_offset`], which spells absence as `None`; this exists
/// for the places that have to put a `u32` in a struct crossing the JNI boundary.
pub const NO_NAME: u32 = 0xFFFF_FFFF;

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
// (notably `EdgeRec` is 14 bytes, not the 16 a naturally-aligned layout would
// give). All reads go through `read_unaligned`, so element pointers need no
// alignment. The `const _` block below asserts every stride at compile time.

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

/// An `edges.bin` record as the generator writes it: 14 bytes.
///
/// Private, so a layout change stays inside this file. [`Graph::edge`] widens it
/// into [`Edge`].
#[repr(C, packed)]
#[derive(Clone, Copy)]
struct EdgeRec {
    target: u32,
    dist_mm: u32,
    name_offset: u32,
    type_: u8,
    speed_limit: u8,
}

/// The in-memory view of an `edges.bin` record, the same widening
/// [`NodeRec`]/[`NodeMaster`] uses.
///
/// Nothing above [`Graph::edge`] sees [`EdgeRec`], so the on-disk record is free to
/// narrow a field, delta-encode it against the edge's source or move it into a
/// sparse side table without any call site changing. That is the whole reason the
/// two types are separate: the alternative is editing the same dozen call sites
/// once per layout change.
///
/// `name_offset` is deliberately *not* here. It is read on the instruction-emission
/// path and never in the A* relaxation, so it belongs in
/// [`Graph::edge_name_offset`] where a sparse representation costs nothing;
/// including it in the view would put every future reader of that field back on
/// the hot path.
#[derive(Clone, Copy)]
pub struct Edge {
    pub target: u32,
    pub dist_mm: u32,
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

const _: () = {
    // Compile-time assertions that the fixed on-disk record sizes match the
    // writer. `#[repr(C, packed)]` is what makes them what they are; without these
    // a field reordered or widened by one byte changes every stride in the file and
    // still compiles, and the resulting pack loads and routes wrongly. The length
    // validation in `Graph::load` derives from these, so they are also what makes
    // that validation mean anything.
    assert!(std::mem::size_of::<NodeRec>() == 12);
    assert!(std::mem::size_of::<EdgeRec>() == 14);
    assert!(std::mem::size_of::<LaneEntry>() == 8);
};

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
    /// [`GRAPH_VERSION`] header, or if **any** file's length is not exactly what
    /// `metadata.bin`'s counts say it should be.
    ///
    /// # Why the lengths are checked, and checked exactly
    ///
    /// The version lives in `metadata.bin` while the layouts live in `nodes.bin`
    /// and `edges.bin`, so a matching version field cannot catch a pack directory
    /// assembled from two vintages — only the lengths can. `nodes.bin`'s length used
    /// not to be checked at all, and [`Graph::node`] documents that callers
    /// guarantee `id <= node_count` with no bounds test, so a short or stale file
    /// read straight past the mapping.
    ///
    /// Exactly, not "at least", because a file that is too *long* is just as much a
    /// vintage mismatch as one that is too short, and one comparison catches both.
    ///
    /// `road_names.bin` is the one file with no count to check against: it is a
    /// byte pool whose size is its own, and [`Graph::road_name`] bounds every read
    /// on it instead.
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

        // metadata.bin: u32 magic, u32 version, u64 node_count, u64 edge_count.
        // Scoped so the mapping is released before the big regions are mapped.
        let (node_count, edge_count) = {
            let meta = MmapRegion::map(&format!("{base}metadata.bin"))?;
            if meta.len < 24 {
                return None;
            }
            let magic = unsafe { read_at::<u32>(meta.base(), 0) };
            let version = unsafe { read_at::<u32>(meta.base(), 1) };
            if magic != GRAPH_MAGIC || version != GRAPH_VERSION {
                return None;
            }
            let nodes = unsafe { read_at::<u64>(meta.base().add(8), 0) };
            let edges = unsafe { read_at::<u64>(meta.base().add(16), 0) };
            // `edge_ptr` is a `u32`, so an edge count past that ceiling would have
            // wrapped in the writer and pointed a node at another node's range. The
            // generator refuses to produce one; this refuses to read one.
            if u32::try_from(edges).is_err() {
                return None;
            }
            (u32::try_from(nodes).ok()?, edges)
        };

        // `edge_count` now comes from the header rather than from `edges.bin`'s
        // length. That is the point of version 4: every later version narrows the
        // record, and a derived count would change silently with the stride and
        // mis-size `intermediate.bin`'s trailer — which is computed from it.
        let nodes_region = MmapRegion::map(&format!("{base}nodes.bin"))?;
        let edges_region = MmapRegion::map(&format!("{base}edges.bin"))?;
        // nodes.bin is `NodeRec[node_count + 1]`: one sentinel record past the real
        // nodes, whose `edge_ptr` is `edge_count`. Eight call sites read it.
        let want_nodes =
            (u64::from(node_count) + 1) * std::mem::size_of::<NodeRec>() as u64;
        if nodes_region.len as u64 != want_nodes {
            return None;
        }
        if edges_region.len as u64 != edge_count * std::mem::size_of::<EdgeRec>() as u64 {
            return None;
        }

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
            // Exactly, not "at least": the writer emits header + index + blob and
            // nothing else, so a longer file is a vintage mismatch too.
            let sentinel = unsafe { read_at::<LaneEntry>(index, n as usize) };
            if r.len as u64 != index_bytes + u64::from(sentinel.blob_off) {
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
    ///
    /// Deliberately asymmetric with [`Graph::node`], which is unchecked: the
    /// sentinel index `node_count` is a *valid* read that this method rejects, and
    /// [`Graph::edge_range`] depends on being able to make it.
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

    /// `[start, end)` of node `id`'s outgoing edges in `edges.bin`. `id` must be a
    /// real node, i.e. `id < node_count`.
    ///
    /// The only way to ask that question. Every caller that reads an `edge_ptr`
    /// wants a range, not an offset, so the pairing is expressed once here rather
    /// than transcribed at eight sites — which is also what stops a future
    /// `nodes.bin` layout from leaking out of this file.
    ///
    /// # What the sentinel record is for
    ///
    /// `nodes.bin` carries one record past the real nodes whose `edge_ptr` is
    /// `edge_count`. That is what makes this well-defined for the *last* real node:
    /// `id + 1` is read for every node, so without it the last one would read past
    /// the mapping and every other node's range would be one short of correct.
    /// `edge_range(node_count - 1).1 == edge_count` is the property, and it is
    /// load-bearing at eight sites.
    ///
    /// The sentinel index itself is the highest `id` this may be *passed plus one* —
    /// `edge_range(node_count)` would read `node_count + 1`, which does not exist.
    /// Every caller either iterates `0..node_count` or rejects `id >= node_count`
    /// first.
    ///
    /// Note that this goes through [`Graph::node`], which is unchecked, *not*
    /// [`Graph::get_node`], which would reject the sentinel index and silently
    /// return `edge_ptr = 0` — turning the last node's range into an empty one.
    #[inline]
    pub fn edge_range(&self, id: u32) -> (u64, u64) {
        debug_assert!(id < self.node_count, "node {id} is not a real node");
        (self.node(id).edge_ptr, self.node(id + 1).edge_ptr)
    }

    /// Edge `idx`, which must be one of node `source`'s outgoing edges.
    ///
    /// `source` is not needed to find the record today. It is in the signature
    /// because the record will stop storing `target` absolutely — a delta from the
    /// source is three bytes smaller — and because passing it makes the
    /// debug assertion below possible, which is the cheapest available guard
    /// against the one bug class that change introduces: decoding an edge against
    /// the wrong source produces a plausible node id, not a crash.
    #[inline]
    pub fn edge(&self, source: u32, idx: u64) -> Edge {
        debug_assert!(
            {
                let (s, e) = self.edge_range(source);
                (s..e).contains(&idx)
            },
            "edge {idx} is not in node {source}'s range {:?}",
            self.edge_range(source)
        );
        let r = unsafe { read_at::<EdgeRec>(self.edges, idx as usize) };
        Edge {
            target: r.target,
            dist_mm: r.dist_mm,
            type_: r.type_,
            speed_limit: r.speed_limit,
        }
    }

    /// Whether edge `idx` of node `source` points at `want`.
    ///
    /// Four call sites scan a node's edge range looking for the one edge that
    /// reaches a given node and never use the target as a value. Once `target` is
    /// stored as a delta they can answer that without widening the field at all, so
    /// the comparison lives here rather than being hand-inlined four times against
    /// whatever the record happens to hold.
    #[inline]
    pub fn edge_targets(&self, idx: u64, source: u32, want: u32) -> bool {
        self.edge(source, idx).target == want
    }

    /// Byte offset of edge `idx`'s name in the string pool, or `None` when it has
    /// none.
    ///
    /// Separate from [`Edge`] because 62% of a planet's edges are unnamed and this
    /// is read on the instruction-emission path, never in the A* relaxation — so the
    /// field can become a sparse side table without any hot-path cost. Being the
    /// sole reader is what makes that possible.
    #[inline]
    pub fn edge_name_offset(&self, idx: u64) -> Option<u32> {
        let r = unsafe { read_at::<EdgeRec>(self.edges, idx as usize) };
        (r.name_offset != NO_NAME).then_some(r.name_offset)
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
        if offset == NO_NAME || (offset as usize) >= self.road_names_size {
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

    /// The node whose edge range `edge_idx` falls in, i.e. the edge's source.
    ///
    /// Found by binary search on the monotonic `edge_ptr`, ~29 probes on a planet.
    /// Only for the one caller that genuinely has no source: an edge index arriving
    /// from Kotlin in `updateTrafficNative`. Everything else already holds the
    /// source, because it got the edge index by iterating a node's own range.
    ///
    /// # The tie-break is a contract, not an accident
    ///
    /// This returns the **largest** node index whose `edge_ptr <= edge_idx`. That is
    /// the unique owner of the edge — any larger node's `edge_ptr` is at least
    /// `edge_ptr(owner + 1)`, which is past `edge_idx` — but it is only the same
    /// thing as the *first* such node when every node has out-degree at least one.
    /// Degree-0 nodes exist (1,480 of California's 6.4 M, 0.023%), and they make
    /// runs of nodes share an `edge_ptr`. A reimplementation that returned the first
    /// of such a run would satisfy the description "a node whose `edge_ptr <=
    /// edge_idx`" and be wrong: that node's range is empty, and the result seeds
    /// [`Graph::decode_edge_coords`], so the failure is a polyline drawn from the
    /// wrong place rather than anything that reports an error.
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

    /// 64-bit Morton (Z-order) code from lat/lon degrees.
    ///
    /// The node array is sorted by this, so it decides both what
    /// [`crate::routing::find_nearest_edge`]'s fixed 800-node window covers and how
    /// far apart two adjacent nodes' *indices* are. It must agree bit for bit with
    /// `latlng_to_spatial` in `scripts/maps/osm_ingest/src/spatial.rs`, which is
    /// what sorts the array; that crate has a test comparing the two
    /// transcriptions.
    ///
    /// # Hilbert was tried and is not better
    ///
    /// Z-order's known flaw is the "Z" discontinuity at every quadrant boundary:
    /// the cells either side are neighbours on the ground and up to a whole
    /// quadrant apart in index. Hilbert has no long-range jumps at all, so it looks
    /// like the obvious win for `edges.bin`, which stores `target` as an `i16`
    /// delta from its source and needs an escape table for the pairs that do not
    /// fit.
    ///
    /// Measured on California (`road_graph --stats`), it is not. The two curves
    /// cross almost exactly at the `i16` boundary:
    ///
    /// | `|target - source|` > | Morton | Hilbert |
    /// |---|---|---|
    /// | 1,024 | 2.225% | 2.155% |
    /// | 16,384 | 0.4711% | 0.4648% |
    /// | **32,767** | **0.3014%** | **0.3076%** |
    /// | 262,144 | 0.0713% | 0.0892% |
    ///
    /// Hilbert wins below ~16 k and loses above it: it concentrates the
    /// distribution rather than shortening it, trading mid-range deltas for far
    /// ones. So the quadrant discontinuities are *not* what generates the 0.3%
    /// tail — mapping a 2-D neighbourhood onto a 1-D index costs that much however
    /// it is done — and the escape table is unavoidable. Not worth a node
    /// permutation change for 2% the wrong way.
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

    /// Decode the delta-encoded coordinate blob for `edge_idx`, whose source is
    /// `source`, into `out`. Returns `Some((count, is_reversed))`, with `count == 0`
    /// when the edge stores no polyline. `out` must hold at least 256 points.
    ///
    /// `source` must really be the node whose range `edge_idx` falls in. It seeds
    /// the decode, so a wrong one silently draws the polyline from the wrong place.
    /// Every call site iterating a node's edge range already has it; the one that
    /// does not recovers it with [`Graph::find_node_idx_for_edge`].
    pub fn get_edge_coordinates_from(
        &self,
        source: u32,
        edge_idx: u64,
        out: &mut [LatLon],
    ) -> Option<(u32, bool)> {
        if self.edges.is_null() || edge_idx >= self.edge_count {
            return None;
        }
        let e = self.edge(source, edge_idx);
        if source >= self.node_count || e.target >= self.node_count {
            return None;
        }

        if e.type_ & REVERSE_GEOMETRY_FLAG != 0 {
            // The twin runs `target -> source`, so it is seeded and terminated the
            // other way round. `get_pt_at` is what flips the result for callers.
            let (s, e_ptr) = self.edge_range(e.target);
            for k in s..e_ptr {
                if self.edge_targets(k, e.target, source) {
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
