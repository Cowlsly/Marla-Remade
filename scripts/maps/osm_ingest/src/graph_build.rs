//! Road-graph build: `.osm.pbf` -> `metadata.bin` / `nodes.bin` / `edges.bin` /
//! `lanes.bin` / `intermediate.bin` / `road_names.bin`.
//!
//! A port of the former `scripts/maps/generator.cpp`, preserving the on-disk
//! contract byte for byte. The reader is `maps/src/main/rust/src/graph.rs`, which
//! is the authority for every layout decision here:
//!
//! | File | Layout |
//! |---|---|
//! | `metadata.bin` | `u32 magic "MARG"`, `u32 version`, `u64 node_count`, `u64 edge_count`, `u64 escape_count`, `u64 named_edges` (40 B) |
//! | `nodes.bin` | `NodeRec[node_count + 1]`, **12 bytes each**: `i32 lat_e7, i32 lon_e7, u32 edge_ptr`; the trailing sentinel's `edge_ptr` is `edge_count` |
//! | `edges.bin` | `EdgeRec[edge_count]`, **7 bytes each**: `i16 target_delta, u24 dist_mm, u8 type, u8 speed_limit`; then padding, `u32 escape_first[E.div_ceil(1024) + 1]`, `(u32 edge_idx, u32 target, u32 dist_mm) x escape_count`, padding, and the sparse name table — a presence bitmap with a rank index plus `u32 name_off[named_edges]`. See [`EdgeFile`] |
//! | `lanes.bin` | `u32 n`, then `(u32 edge_idx, u32 blob_byte_off) x (n + 1)` ascending by `edge_idx`, then the packed `u16` mask blob — **sparse**, only lane-bearing edges appear |
//! | `intermediate.bin` | the delta-encoded polyline blob ([`crate::geom`]) at offset 0, then a trailer: `u64 rank[..]`, `u8 present[..]` (one bit per directed edge), `u64 coarse[..]`, `u16 within[..]` over the *geometry* edges, and `u64 G`. See [`GeomFile`] |
//! | `road_names.bin` | deduped NUL-terminated string pool |
//!
//! Every file's length is now an exact function of `metadata.bin`'s counts, and
//! the reader checks each one. `road_names.bin` is the exception: it is a byte pool
//! with no count of its own, and reads into it are bounds-checked instead.
//!
//! Three behavioural notes where this differs from the C++:
//!
//! * It is **deterministic**. The C++ filled its node array via
//!   `idx.fetch_add(1)` from concurrent workers and interned names in
//!   thread-scheduling order, so its node order, `name_offset` values and
//!   therefore all of `road_names.bin`/`edges.bin`/`nodes.bin` differed between
//!   two runs of itself. Here every parallel stage merges per-chunk results in
//!   chunk order, so the output is a pure function of the input.
//! * The C++ kept two bitsets, `useful_nodes_mask` and `road_nodes_mask`, but
//!   set and cleared them in exactly the same places, so they always held
//!   identical contents. One bitset does the same job for half the memory.
//! * **Degree-2 chains are collapsed** ([`crate::compact`]) and the geometry they
//!   carried moves into `intermediate.bin`. Pass 3 therefore builds *undirected*
//!   segments and defers the split into directed edges until after compaction,
//!   because pairing a `u -> v` edge with its `v -> u` twin — which
//!   `REVERSE_GEOMETRY_FLAG` depends on — is only unambiguous while the two are
//!   still one object.
//!
//! # Passes, and why there are four
//!
//! 1. **Refs + stops** (nodes and ways): mark the node bitset. Nothing about a
//!    way is retained.
//! 2. **Rank index**: `popcount` below an id becomes that node's address, so the
//!    node array is implicitly ordered by OSM id. That deletes the sorted
//!    `(osm_id, local_id)` table the edge pass used to binary-search, the
//!    per-node `osm_id`, and the per-node spatial key.
//! 3. **Node scan**: scatter coordinates into their dense slots and record which
//!    slots a blob actually filled.
//! 4. **Ways** (way blobs only): re-read them and emit segments directly.
//!
//! The order is not the obvious one. Pass 1 marks *every* ref of every routable
//! way, including refs to nodes no blob in the file defines — an extract clipped
//! at a boundary is full of them. Under dense addressing those refs own slots, so
//! coordinates must be scattered, and their arrival recorded, *before* anything
//! reads the node array; otherwise a dangling ref reads as `(0, 0)`.

use std::fs::File;
use std::io::{BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};

use crate::compact::{self, Seg, REMOVED};
use crate::chains;
use crate::geom;
use crate::names::{LocalNames, NamePool, NO_NAME};
use crate::osm::{visit_block, Element};
use crate::par;
use crate::pbf::{self, KIND_NODES, KIND_WAYS};
use crate::proto::{Error, Result};
use crate::spatial::{accurate_dist_mm, spatial_from_e7};
use crate::tags;

/// Node ids at or above this are skipped, exactly as in the C++. The bitset that
/// tracks "is this node part of the graph" is sized from it (2.5 GB).
pub const BITSET_SIZE: u64 = 20_000_000_000;

/// `"MARG"` little-endian — `GRAPH_MAGIC` in `graph.rs`.
const GRAPH_MAGIC: u32 = 0x4752_414D;
/// Version 5 narrows an `edges.bin` record from 14 bytes to 11: `target` becomes an
/// `i16` delta from the edge's source and `dist_mm` a `u24`, with one escape table
/// behind the record array for the 0.3% that do not fit. Planet goes ~29.0 -> ~25.8
/// GB, and every value stays *exact*.
/// Version 6 moves `name_offset` out of the record into a sparse side table — two
/// thirds of edges have no name — taking the record to 7 bytes and planet to ~23.3 GB.
const GRAPH_VERSION: u32 = 6;

/// Geometry edges per coarse block in `intermediate.bin`'s two-level offset table
/// — `INTERMEDIATE_BLOCK` in `graph.rs`, and the two must agree.
///
/// A per-edge blob is at most 1016 bytes ([`geom::MAX_POINTS`] points, of which
/// only the interior is stored, 4 bytes each), so a block spans at most 32,512
/// bytes and the within-block half fits a `u16`. [`GeomFile::push_entry`] asserts
/// it. The table is indexed by an edge's *rank in the presence bitmap*, not by its
/// edge index, so every entry in a block describes a real blob.
const INTERMEDIATE_BLOCK: u64 = 32;

/// Smallest connected component that counts as a real road network for the
/// purpose of [`reconnect_isolated_stops`].
///
/// A stop already attached to a component this size is left alone; anything
/// smaller is treated as a mapping artefact and reconnected. The threshold is
/// capped at the largest component that actually exists, so a tiny graph — every
/// fixture in this file — still reconnects against its own road network.
const MIN_ROUTABLE_COMPONENT: u32 = 1000;

/// Synthetic edges that reattach an isolated transit stop to the routable graph.
const RECONNECT_TYPE: u8 = 12;
const RECONNECT_SPEED: u8 = 5;

/// Sentinel in a `CachedWay`/`TmpEdge` lane offset meaning "no lane data".
const NO_LANES: u32 = 0xFFFF_FFFF;

/// Sentinel in `TmpEdge::chain` meaning "this edge came from nowhere in
/// particular" — currently only the synthetic transit-stop connectors, which are
/// straight lines and so need no stored geometry.
const NO_CHAIN: u32 = 0xFFFF_FFFF;

/// `REVERSE_GEOMETRY_FLAG` in `graph.rs`: set in an edge's `type_` byte to mean
/// "my geometry is my twin's, read backwards". Only one edge of a bidirectional
/// pair stores the polyline, which halves the blob.
///
/// The reader locates the twin by scanning the *target's* edge range for an edge
/// pointing back at the source, and takes the first match — so this may only be
/// set when exactly one such edge exists. See [`twin_is_unique`].
const REVERSE_GEOMETRY_FLAG: u8 = 0x40;

/// Fallback stop code for a transit stop node with no `name`.
const UNNAMED_STOP: &[u8] = b"OSM_STOP";

/// Sentinel in a narrowed `target` delta meaning "this record's `target` and
/// `dist_mm` both live in the escape table".
///
/// An `edges.bin` record stores `target` as an `i16` delta from the edge's own
/// source rather than absolutely, which is only possible because the nodes are
/// sorted by a space-filling curve and an edge is short: [`Census`] measures how
/// often that fails. `i16::MIN` is reserved rather than used, so the representable
/// range is symmetric at ±32767.
const TARGET_DELTA_ESCAPE: i16 = i16::MIN;

/// Sentinel in a narrowed `u24 dist_mm` meaning the same thing.
///
/// 0xFFFFFF mm is 16.78 km, which a collapsed degree-2 chain can exceed in empty
/// country. One sentinel shared with [`TARGET_DELTA_ESCAPE`] means a single escape
/// table serves both fields, so a `dist_mm` outlier costs no extra branch and no
/// extra table.
const DIST_MM_ESCAPE: u32 = 0xFF_FFFF;

/// Bytes per `edges.bin` record — `EdgeRec` in `graph.rs`, and an on-disk contract
/// with it: `i16 target_delta, u24 dist_mm, u8 type_, u8 speed_limit`. `name_offset`
/// is not a field; it is a sparse side table behind the record array.
const EDGE_REC_BYTES: u64 = 7;

/// Directed edges per entry of `edges.bin`'s escape block index — `ESCAPE_BLOCK` in
/// `graph.rs`, and the two must agree.
const ESCAPE_BLOCK: u64 = 1024;

/// Alignment each section of a multi-section file starts on — `SECTION_ALIGN` in
/// `graph.rs`.
const SECTION_ALIGN: u64 = 8;

/// Round `n` up to the next multiple of `align`, which must be a power of two.
const fn align_up(n: u64, align: u64) -> u64 {
    (n + align - 1) & !(align - 1)
}

/// `target - source` when it fits the `i16` a record stores, `None` when the edge
/// has to escape.
///
/// This is *the* escape predicate: [`Census`] counts with it and [`EdgeFile::push`]
/// narrows with it, so the two cannot disagree by one and produce a pack that loads
/// while reading the wrong node.
#[inline]
fn target_delta(source: u32, target: u32) -> Option<i16> {
    let d = i64::from(target) - i64::from(source);
    i16::try_from(d).ok().filter(|d| *d != TARGET_DELTA_ESCAPE)
}

/// True when either field of this edge is unrepresentable inline.
#[inline]
fn edge_escapes(source: u32, target: u32, dist_mm: u32) -> bool {
    target_delta(source, target).is_none() || dist_mm >= DIST_MM_ESCAPE
}

/// One bucket per possible bit width of `|target - source|`, from a zero delta up
/// to a full `u32`. A named type only because arrays this long do not derive
/// `Default`.
pub struct DeltaBits([u64; 33]);

impl Default for DeltaBits {
    fn default() -> DeltaBits {
        DeltaBits([0u64; 33])
    }
}

impl std::ops::Deref for DeltaBits {
    type Target = [u64; 33];
    fn deref(&self) -> &[u64; 33] {
        &self.0
    }
}

impl std::ops::DerefMut for DeltaBits {
    fn deref_mut(&mut self) -> &mut [u64; 33] {
        &mut self.0
    }
}

/// What a narrower `edges.bin` record would cost, measured by the encoder itself
/// rather than by a script that might disagree with it by one.
///
/// Reported by `road_graph --stats`. Everything here is a *property of one
/// extract*, and the design that consumes it extrapolates to a planet 22x larger,
/// so the histogram matters more than any single rate: for nodes ordered by a
/// space-filling curve the tail should decay as `P(|delta| > n) ~ c * n^(-1/2)`,
/// and `c` is what transfers between extracts, not the escape rate itself.
#[derive(Default)]
pub struct Census {
    /// `delta_bits[b]` counts edges needing exactly `b` bits to be exceeded, i.e.
    /// for which `b` is the number of `k` with `2^k < |target - source|`. Suffix
    /// sums of it are the tail counts; see [`Census::delta_tail`].
    pub delta_bits: DeltaBits,
    /// Edges whose `|target - source|` will not fit an `i16`.
    pub delta_escapes: u64,
    /// Edges whose `dist_mm` reaches [`DIST_MM_ESCAPE`].
    pub dist_escapes: u64,
    /// Edges either sentinel forces into the escape table. Not the sum of the two
    /// above: an edge can fail both tests and still be one row.
    pub escapes: u64,
    pub max_delta: u32,
    pub max_dist_mm: u32,
    /// Largest out-degree of any node, which bounds a per-node `u8 degree` field.
    pub max_degree: u32,
    /// Nodes with no outgoing edge. These make several nodes share an `edge_ptr`,
    /// which is what makes `find_node_idx_for_edge`'s tie-break observable.
    pub degree_zero_nodes: u64,
    pub named_edges: u64,
}

impl Census {
    /// Edges whose `|target - source|` exceeds `1 << k`, for `k` in `0..32`.
    pub fn delta_tail(&self, k: u32) -> u64 {
        self.delta_bits[(k as usize + 1).min(32)..].iter().sum()
    }

    /// Record one directed edge.
    #[inline]
    fn edge(&mut self, source: u32, target: u32, dist_mm: u32, named: bool) {
        let delta = source.abs_diff(target);
        // The bucket is the count of `k` with `2^k < delta`, so a plain suffix sum
        // turns the histogram into the tail without a per-edge loop.
        let bits = if delta == 0 { 0 } else { 32 - (delta - 1).leading_zeros() };
        self.delta_bits[bits as usize] += 1;
        self.max_delta = self.max_delta.max(delta);
        self.max_dist_mm = self.max_dist_mm.max(dist_mm);
        if target_delta(source, target).is_none() {
            self.delta_escapes += 1;
        }
        if dist_mm >= DIST_MM_ESCAPE {
            self.dist_escapes += 1;
        }
        if edge_escapes(source, target, dist_mm) {
            self.escapes += 1;
        }
        if named {
            self.named_edges += 1;
        }
    }

    #[inline]
    fn node(&mut self, degree: u32) {
        self.max_degree = self.max_degree.max(degree);
        if degree == 0 {
            self.degree_zero_nodes += 1;
        }
    }
}

pub struct Stats {
    pub node_count: u64,
    pub edge_count: u64,
    pub unique_names: usize,
    pub name_bytes: u32,
    pub lcc_size: u64,
    /// Stops left alone because their own component is already routable.
    pub stops_already_connected: usize,
    pub reconnected_stops: usize,
    /// Stops that needed reconnecting and found no routable node in range.
    pub stops_unreachable: usize,
    /// Nodes before compaction, i.e. one per OSM geometry vertex.
    pub raw_node_count: u64,
    /// Directed edges an uncompacted build would have produced.
    pub raw_edge_count: u64,
    /// Chains cut because their geometry would not fit a single edge.
    pub chain_splits: usize,
    /// Edges carrying a stored polyline.
    pub geometry_edges: u64,
    /// Edges deferring to their twin's polyline.
    pub reversed_edges: u64,
    pub intermediate_bytes: u64,
    pub edges_bytes: u64,
    /// Edges whose `target` or `dist_mm` needed the escape table.
    pub escape_count: u64,
    /// Edges with a name at all, i.e. the length of the sparse name table.
    pub named_edges: u64,
    /// What a narrower record would cost. Always collected — it is a handful of
    /// adds per edge — and reported by `--stats`.
    pub census: Census,
}

/// Bytes per rank block. 64 bytes is 512 bits and one cache line, so a rank
/// costs one `u64` load plus at most eight `popcount`s while the index itself is
/// only 1/64th of the bitset it indexes.
///
/// Both the in-memory [`Bitset`] and `intermediate.bin`'s on-disk presence bitmap
/// use it, and for the latter it is an on-disk contract with `graph.rs`.
const RANK_BLOCK_BYTES: usize = 64;

/// Bits \u2014 for `intermediate.bin`, directed edges \u2014 one rank block covers.
const RANK_BLOCK_BITS: u64 = RANK_BLOCK_BYTES as u64 * 8;

/// A plain (non-atomic) bitset, optionally carrying a rank index.
///
/// Bits are set from a single thread during the merge and only read afterwards,
/// so no atomics are needed.
///
/// [`Bitset::build_rank`] turns it into a *dense addressing scheme*: the node
/// array becomes implicitly ordered by OSM id, with a node's index being the
/// number of set bits before its own. That is what removes the sorted
/// `(osm_id, local_id)` side table the edge pass used to binary-search, and with
/// it the need to store an `osm_id` per node at all.
pub(crate) struct Bitset {
    bits: Vec<u8>,
    /// Set bits before each [`RANK_BLOCK_BYTES`] block, with a total appended.
    /// Empty until [`Bitset::build_rank`].
    rank: Vec<u64>,
}

impl Bitset {
    pub(crate) fn new(size_bits: u64) -> Bitset {
        Bitset {
            bits: vec![0u8; (size_bits / 8 + 1) as usize],
            rank: Vec::new(),
        }
    }

    /// Returns true when this call is what set the bit.
    pub(crate) fn set(&mut self, idx: u64) -> bool {
        let byte = &mut self.bits[(idx / 8) as usize];
        let mask = 1u8 << (idx % 8);
        let was = *byte & mask != 0;
        *byte |= mask;
        !was
    }

    pub(crate) fn get(&self, idx: u64) -> bool {
        self.bits[(idx / 8) as usize] & (1u8 << (idx % 8)) != 0
    }

    /// Index the blocks covering ids up to and including `max_id`, returning the
    /// total number of set bits.
    ///
    /// Sized from the largest id actually seen rather than from the bitset's
    /// nominal width: `BITSET_SIZE` is 20 G ids, and indexing all of it would
    /// cost 313 MB to describe blocks no id reaches. `bits` itself is left at
    /// full width, because [`Bitset::get`] is still asked about ids past the
    /// largest marked one.
    fn build_rank(&mut self, max_id: u64) -> u64 {
        let used = (max_id / 8 + 1) as usize;
        let blocks = used.div_ceil(RANK_BLOCK_BYTES);
        self.rank = Vec::with_capacity(blocks + 1);
        let mut total = 0u64;
        for b in 0..blocks {
            self.rank.push(total);
            let start = b * RANK_BLOCK_BYTES;
            let end = (start + RANK_BLOCK_BYTES).min(used);
            total += self.bits[start..end]
                .iter()
                .map(|byte| u64::from(byte.count_ones()))
                .sum::<u64>();
        }
        self.rank.push(total);
        total
    }

    /// How many set bits come before `idx`. For a *set* bit that is its position
    /// in the sequence of set bits, i.e. its dense id.
    ///
    /// Meaningless for a clear bit: it returns the id of whichever node comes
    /// next, so callers must test [`Bitset::get`] first or they will silently
    /// alias two nodes together.
    #[inline]
    fn dense(&self, idx: u64) -> u32 {
        let byte = (idx / 8) as usize;
        let block = byte / RANK_BLOCK_BYTES;
        let start = block * RANK_BLOCK_BYTES;
        let whole: u32 = self.bits[start..byte].iter().map(|b| b.count_ones()).sum();
        let below = (self.bits[byte] & ((1u8 << (idx % 8)) - 1)).count_ones();
        let n = self.rank[block] + u64::from(whole) + u64::from(below);
        debug_assert!(n <= u64::from(u32::MAX), "dense id {n} does not fit a u32");
        n as u32
    }
}

/// Dense addressing for the graph's nodes: which OSM ids are in the graph, and
/// which of those the file actually supplied coordinates for.
///
/// Both tests matter, and for different reasons. The mask is what makes
/// [`Bitset::dense`] meaningful. `present` is what keeps *dangling references*
/// out: pass 1 marks every ref of every routable way, including refs to nodes no
/// blob in the file defines — an extract clipped at a boundary is full of them —
/// and those slots never receive coordinates. Left in, each would read as
/// `(0, 0)`, a point in the Atlantic that corrupts distances, spatial keys and
/// polylines while leaving every count looking plausible.
pub(crate) struct NodeIndex {
    mask: Bitset,
    present: Bitset,
}

impl NodeIndex {
    /// Dense id of a node that is both in the graph and really in the file.
    #[inline]
    pub(crate) fn dense(&self, osm_id: i64) -> Option<u32> {
        if osm_id < 0 || osm_id as u64 >= BITSET_SIZE || !self.mask.get(osm_id as u64) {
            return None;
        }
        let d = self.mask.dense(osm_id as u64);
        self.present.get(u64::from(d)).then_some(d)
    }
}

#[derive(Default)]
struct Pass1 {
    /// Node ids referenced by a routable way, and the ids of stop-tagged nodes.
    /// Per chunk and freed by the sink: nothing about a way is retained, because
    /// the ways pass re-reads them.
    refs: Vec<i64>,
    stop_nodes: Vec<i64>,
}

/// The ways-only pass: one [`Seg`] per consecutive resolvable ref pair.
///
/// Re-reading the way blobs replaces a cache of every routable way and every one
/// of its refs — 32 GB at planet scale — with the cost of inflating the way
/// blobs a second time. Ways are a small minority of a planet file's blobs and
/// `blob_kinds` lets the pass skip the rest outright, so the trade is heavily in
/// favour of re-reading.
#[derive(Default)]
struct WayPass {
    segs: Vec<Seg>,
    lanes: Vec<u16>,
    names: LocalNames,
}

#[derive(Default)]
struct Pass2 {
    /// `(dense id, lat_e7, lon_e7)`, scattered index-aligned in the sink.
    locs: Vec<(u32, i32, i32)>,
    /// `(dense id, stop code)`, as owned bytes. Interning is deferred until every
    /// way name is in the pool, because a name's offset is its position in the
    /// pool: interning a stop code early would shift every way name after it and
    /// rewrite the whole of `road_names.bin`.
    stops: Vec<(u32, Vec<u8>)>,
}

#[derive(Clone, Copy)]
struct TmpEdge {
    source: u32,
    target: u32,
    dist_mm: u32,
    name_offset: u32,
    type_: u8,
    speed_limit: u8,
    lane_off: u32,
    lane_count: u16,
    /// Chain this edge traverses, or [`NO_CHAIN`]. Only used to make the write
    /// order total; the geometry comes from the point range below.
    chain: u32,
    /// True when it runs from the chain's last point to its first, so the stored
    /// polyline has to be read backwards.
    chain_rev: bool,
    /// The chain's polyline in `chains.pts`. Carried here rather than looked up per
    /// edge: the header file is 24 GB at planet scale, so seeking back into it once
    /// per edge would be a billion cold random reads.
    pts_start: u64,
    pts_len: u32,
}

/// Build-time choices that change the shape of the build rather than its output
/// contract.
#[derive(Clone, Default)]
pub struct Options {
    /// Restrict chains to a single OSM way, which trades some collapsing for the
    /// segment array and its incidence index. See [`crate::chains`].
    pub within_way_chains: bool,
    /// How many source-partitioned rounds to write the pack in. One round is the
    /// whole graph at once; more rounds trade extra sequential passes over the
    /// chain spill for a proportionally smaller edge buffer. Zero means one.
    pub rounds: u32,
    /// Where to put the chain spill. Defaults to the output directory.
    ///
    /// Worth separating because `chains.pts` is the only file the build reads
    /// randomly — once per edge that stores a polyline — so it wants a disk whose
    /// seeks are cheap, while the output only ever needs sequential writes and can
    /// go wherever there is room. Putting the spill on a slow mount cost more wall
    /// clock on California than every other stage put together.
    pub spill_dir: Option<PathBuf>,
    /// Where to put `chains.pts` specifically, if it should not sit beside
    /// `chains.hdr`.
    ///
    /// The header is the larger file — about 24 GB against 18 GB at planet scale —
    /// but it is only ever streamed from the front, so it can live somewhere roomy
    /// and slow while the randomly-read points go somewhere fast and small.
    pub spill_pts_dir: Option<PathBuf>,
    /// Print the [`Census`] report after the build. Changes no output file.
    pub stats: bool,
}

impl Options {
    fn round_count(&self) -> u32 {
        self.rounds.max(1)
    }
}

pub fn build(input: &Path, out_dir: &Path) -> Result<Stats> {
    build_with(input, out_dir, Options::default())
}

pub fn build_with(input: &Path, out_dir: &Path, opts: Options) -> Result<Stats> {
    std::fs::create_dir_all(out_dir)
        .map_err(|e| Error(format!("cannot create {}: {e}", out_dir.display())))?;

    let blobs = pbf::scan_blobs(input)?;
    println!("Scanned {} data blob(s) in {}", blobs.len(), input.display());
    pbf::probe_compression(input, &blobs)?;

    let mut pool = NamePool::new(BufWriter::new(create(&out_dir.join("road_names.bin"))?));

    let mut mask = Bitset::new(BITSET_SIZE);
    let mut marked: u64 = 0;
    let mut max_id: u64 = 0;

    // --- Pass 1: which nodes the graph needs ---------------------------------
    // Folded through a sink rather than collected, so a chunk's ref list is
    // consumed and freed while later chunks are still decoding. Nothing about a
    // way survives this pass: the ways pass below re-reads the way blobs, which
    // is far cheaper than caching every routable way and every ref it holds.
    let blob_kinds = pbf::run_pass_sink(
        input,
        &blobs,
        None,
        KIND_NODES | KIND_WAYS,
        "Pass 1: refs + stops",
        Pass1::default,
        pass1_blob,
        |chunk| {
            for id in chunk.refs.iter().chain(chunk.stop_nodes.iter()) {
                if *id >= 0 && (*id as u64) < BITSET_SIZE {
                    if mask.set(*id as u64) {
                        marked += 1;
                    }
                    // The rank index is sized from the largest id actually
                    // marked, not from BITSET_SIZE: indexing all 20 G nominal ids
                    // would cost 313 MB to describe blocks no node reaches.
                    max_id = max_id.max(*id as u64);
                }
            }
            Ok(())
        },
    )?;
    println!("{marked} graph node(s) expected");
    let slots = cap_u32("marked graph node", marked)?;

    // --- The rank index ------------------------------------------------------
    // From here on a node's address is the number of marked ids below its own, so
    // the node array is implicitly ordered by OSM id and needs to store neither
    // the id nor a sorted side table to look it up by.
    let counted = mask.build_rank(max_id);
    if counted != marked {
        // The mask and the running count are maintained in the same loop, so a
        // disagreement means one of the two is wrong about which ids were set,
        // and every address derived from the rank would be off by however many.
        return Err(Error(format!(
            "rank index counted {counted} marked node(s) but pass 1 set {marked}"
        )));
    }

    // --- Pass 2: coordinates and stop codes for the marked nodes -------------
    // Zero-initialised, so the slots belonging to dangling refs are never touched
    // and never fault in: an untouched page of `coords` costs no real memory.
    let mut coords: Vec<geom::Pt> = vec![(0, 0); slots as usize];
    let mut present = Bitset::new(u64::from(slots));
    let mut stop = Bitset::new(u64::from(slots));
    let mut stop_codes: Vec<(u32, Vec<u8>)> = Vec::new();
    let mut node_count: u64 = 0;
    let _ = pbf::run_pass_sink(
        input,
        &blobs,
        Some(&blob_kinds),
        KIND_NODES,
        "Pass 2: node scan",
        Pass2::default,
        |state, block| pass2_blob(state, block, &mask),
        |chunk| {
            for (dense, lat_e7, lon_e7) in chunk.locs {
                coords[dense as usize] = (lat_e7, lon_e7);
                if present.set(u64::from(dense)) {
                    node_count += 1;
                }
            }
            for (dense, code) in chunk.stops {
                stop.set(u64::from(dense));
                stop_codes.push((dense, code));
            }
            Ok(())
        },
    )?;
    println!(
        "Located {node_count} node(s); {} marked id(s) had no coordinates in the file",
        marked - node_count
    );

    // Stop codes go into the pool only after every way name is in it, in the
    // order pass 2 met them, so `road_names.bin` is unaffected by dense
    // addressing and by where the way names are interned.
    let index = NodeIndex { mask, present };

    // --- Collapse ------------------------------------------------------------
    let spill_dir = opts.spill_dir.clone().unwrap_or_else(|| out_dir.to_path_buf());
    let spill_pts_dir = opts.spill_pts_dir.clone().unwrap_or_else(|| spill_dir.clone());
    let mut collapsed = if opts.within_way_chains {
        collapse_within_ways(
            input, &spill_dir, &spill_pts_dir, &blobs, &blob_kinds, &index, &coords, &stop,
            slots, &mut pool,
        )?
    } else {
        collapse_segments(
            input, &spill_dir, &spill_pts_dir, &blobs, &blob_kinds, &index, &coords, &stop,
            slots, &mut pool,
        )?
    };

    for (_, code) in &stop_codes {
        pool.intern(code).map_err(io_err)?;
    }
    drop(stop_codes);

    // `saturating_sub` because a file with no routable ways and no stop nodes
    // marks nothing, and there is then no largest id to index up to.
    let kept_count = cap_u32(
        "surviving node",
        collapsed.kept.build_rank(u64::from(slots).saturating_sub(1)),
    )?;
    let kept = collapsed.kept;
    println!(
        "Collapsed {node_count} node(s) -> {kept_count} ({} chain(s), {} split(s) for the \
         256-point limit)",
        collapsed.chain_count, collapsed.splits
    );
    cap_u32("chain", collapsed.chain_count)?;
    let raw_edge_count = collapsed.raw_edge_count;

    // --- Morton order -------------------------------------------------------
    // Deferred to here, and applied only to the survivors, because that is the
    // only set the device ever binary-searches. Sorting before the collapse would
    // have meant carrying a 64-bit spatial key through every node that was about
    // to be collapsed away.
    println!("Sorting {kept_count} surviving node(s) by spatial key...");
    let mut keys: Vec<(u64, u32)> = Vec::with_capacity(kept_count as usize);
    for i in 0..slots {
        if kept.get(u64::from(i)) {
            let (lat_e7, lon_e7) = coords[i as usize];
            keys.push((spatial_from_e7(lat_e7, lon_e7), i));
        }
    }
    debug_assert_eq!(keys.len(), kept_count as usize);
    keys.sort_by_key(|(spatial, _)| *spatial);

    let mut final_of_kept = vec![0u32; kept_count as usize];
    let mut node_coords: Vec<geom::Pt> = Vec::with_capacity(kept_count as usize);
    let mut stops_final = Bitset::new(u64::from(kept_count));
    for (final_id, (_, dense)) in keys.iter().enumerate() {
        final_of_kept[kept.dense(u64::from(*dense)) as usize] = final_id as u32;
        node_coords.push(coords[*dense as usize]);
        if stop.get(u64::from(*dense)) {
            stops_final.set(final_id as u64);
        }
    }
    drop(keys);
    drop(stop);
    // The chain spill holds coordinates, so nothing downstream needs the array
    // indexed by *marked* node — the largest in the build — any longer.
    drop(coords);
    drop(index);
    let ids = FinalIds {
        kept: &kept,
        of_kept: &final_of_kept,
    };

    // --- The edge index -----------------------------------------------------
    let csr = build_csr(&collapsed.spill, kept_count, &ids)?;

    // --- Reconnect isolated transit stops -----------------------------------
    let rec = reconnect_isolated_stops(&node_coords, &stops_final, &csr);
    let synth = rec.synth;
    drop(stops_final);

    // --- Write --------------------------------------------------------------
    let written = write_graph(
        out_dir,
        &node_coords,
        &collapsed.spill,
        &collapsed.lane_pool,
        &csr,
        &synth,
        &ids,
        opts.round_count(),
    )?;
    collapsed.spill.remove();
    let unique_names = pool.unique_count();
    let name_bytes = pool.byte_len();
    pool.finish().map_err(io_err)?;

    println!(
        "Done. Nodes: {}  Edges: {}  intermediate.bin: {} byte(s)",
        kept_count, written.edge_count, written.intermediate_bytes
    );
    Ok(Stats {
        node_count: u64::from(kept_count),
        edge_count: written.edge_count,
        unique_names,
        name_bytes,
        lcc_size: rec.lcc_size,
        stops_already_connected: rec.already_connected,
        reconnected_stops: synth.len() / 2,
        stops_unreachable: rec.unreachable,
        raw_node_count: node_count,
        raw_edge_count,
        chain_splits: collapsed.splits,
        geometry_edges: written.geometry_edges,
        reversed_edges: written.reversed_edges,
        intermediate_bytes: written.intermediate_bytes,
        edges_bytes: written.edges_bytes,
        escape_count: written.escape_count,
        named_edges: written.named_edges,
        census: written.census,
    })
}

/// What either collapse strategy hands the writer.
///
/// Both produce the same thing — a chain spill plus the set of nodes that survive
/// — and differ only in how much memory it costs to get there and whether a chain
/// may cross from one OSM way into the next.
///
/// The chains are always on disk, even for the reference path that had them in
/// memory anyway, so that there is one writer. That also means `coords` — the
/// largest array in the build, and the only one indexed by *marked* rather than
/// surviving node — can be freed before the writer runs.
struct Collapsed {
    spill: chains::Spill,
    /// Dense ids that survive as graph nodes.
    kept: Bitset,
    lane_pool: Vec<u16>,
    chain_count: u64,
    splits: usize,
    raw_edge_count: u64,
}

/// The reference path: build every segment, then collapse chains across way
/// boundaries with [`compact`].
///
/// Kept as the baseline the within-way path is measured against. Its cost is the
/// segment array and the incidence CSR over it, which together are the reason a
/// planet build does not fit.
#[allow(clippy::too_many_arguments)]
fn collapse_segments<W: std::io::Write + Send>(
    input: &Path,
    spill_dir: &Path,
    spill_pts_dir: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds: &[u8],
    index: &NodeIndex,
    coords: &[geom::Pt],
    stop: &Bitset,
    slots: u32,
    pool: &mut NamePool<W>,
) -> Result<Collapsed> {
    // One record per consecutive way-node pair, still carrying both directions'
    // lane data. Splitting into directed edges is deferred until after
    // compaction, because pairing a `u -> v` edge with its `v -> u` twin is only
    // unambiguous while they are still one object.
    let mut segs: Vec<Seg> = Vec::new();
    let mut lane_pool: Vec<u16> = Vec::new();
    let way_kinds = pbf::run_pass_sink(
        input,
        blobs,
        Some(blob_kinds),
        KIND_WAYS,
        "Pass 3: segments",
        WayPass::default,
        |state, block| way_blob(state, block, index, coords),
        |chunk| {
            let name_map = chunk.names.flush(pool).map_err(io_err)?;
            let lane_base = lane_pool.len() as u32;
            lane_pool.extend_from_slice(&chunk.lanes);
            cap_lane_pool(lane_pool.len())?;
            segs.reserve(chunk.segs.len());
            for mut s in chunk.segs {
                s.name_offset = if s.name_offset == u32::MAX {
                    NO_NAME
                } else {
                    name_map[s.name_offset as usize]
                };
                if s.fwd_lane_count > 0 {
                    s.fwd_lane_off += lane_base;
                }
                if s.bwd_lane_count > 0 {
                    s.bwd_lane_off += lane_base;
                }
                segs.push(s);
            }
            Ok(())
        },
    )?;
    report_way_blobs(blobs.len(), &way_kinds);

    let raw_edge_count: u64 = segs.iter().map(|s| if s.oneway { 1u64 } else { 2 }).sum();
    println!(
        "Built {} segment(s) ({raw_edge_count} directed edge(s) before compaction)",
        segs.len()
    );

    let comp = compact::compact(slots, &segs, &lane_pool, |n| coords[n as usize], |n| {
        stop.get(u64::from(n))
    });
    drop(segs);

    // A slot with no coordinates has no segments either, so compaction has no
    // reason to collapse it and would hand it a real node id. Filter it out here
    // instead: this is the second half of the dangling-reference guard.
    let mut kept = Bitset::new(u64::from(slots));
    for i in 0..slots {
        if comp.new_id[i as usize] != REMOVED && index.present.get(u64::from(i)) {
            kept.set(u64::from(i));
        }
    }
    let spill = chains::Spill::split(spill_dir, spill_pts_dir);
    spill.write(&comp.chains, &comp.pts, coords)?;
    Ok(Collapsed {
        spill,
        kept,
        lane_pool,
        chain_count: comp.chains.len() as u64,
        splits: comp.splits,
        raw_edge_count,
    })
}

/// The planet path: a byte of degree per node and a positional walk over each
/// way's refs. See [`crate::chains`] for what it costs and what it buys.
#[allow(clippy::too_many_arguments)]
fn collapse_within_ways<W: std::io::Write + Send>(
    input: &Path,
    spill_dir: &Path,
    spill_pts_dir: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds: &[u8],
    index: &NodeIndex,
    coords: &[geom::Pt],
    stop: &Bitset,
    slots: u32,
    pool: &mut NamePool<W>,
) -> Result<Collapsed> {
    let degree = chains::count_degrees(input, blobs, blob_kinds, index, slots)?;
    let spill = chains::Spill::split(spill_dir, spill_pts_dir);
    let built = chains::build(
        input, blobs, blob_kinds, index, coords, &degree, stop, slots, pool, &spill,
    )?;
    report_way_blobs(blobs.len(), &built.kinds);

    let mut kept = Bitset::new(u64::from(slots));
    for i in 0..slots {
        if index.present.get(u64::from(i)) && chains::survives(&built.endpoints, &degree, i) {
            kept.set(u64::from(i));
        }
    }
    drop(degree);

    let count = spill.chain_count()?;
    if count != built.chain_count {
        return Err(Error(format!(
            "the chain pass streamed {} chain(s) but chains.hdr holds {count}",
            built.chain_count
        )));
    }

    Ok(Collapsed {
        spill,
        kept,
        lane_pool: built.lanes,
        chain_count: count,
        splits: built.splits,
        raw_edge_count: built.raw_edge_count,
    })
}

/// The load-bearing measurement for re-reading the way blobs rather than caching
/// them: if a ways-only pass had to touch most blobs, the cache would have been
/// the cheaper of the two.
fn report_way_blobs(total: usize, kinds: &[u8]) {
    let read = kinds.iter().filter(|k| *k & KIND_WAYS != 0).count();
    println!(
        "The ways pass read {read} of {total} blob(s) ({:.1}%)",
        read as f64 * 100.0 / total.max(1) as f64
    );
}

/// Refuse a lane pool whose offsets would no longer fit a `u32`.
///
/// Same failure mode as the counts above and one step worse: a rebased offset that
/// wrapped onto [`NO_LANES`] reads back as "this edge has no lane data", so real
/// turn lanes would disappear from a build that reported success.
pub(crate) fn cap_lane_pool(len: usize) -> Result<()> {
    if len as u64 >= u64::from(NO_LANES) {
        return Err(Error(format!(
            "the lane pool reached {len} mask(s), past the {NO_LANES} an offset can address"
        )));
    }
    Ok(())
}

/// Refuse a count that no longer fits the `u32` ids the build and the on-disk
/// format are built on.
///
/// Every one of these is a silent truncation rather than a crash: a `u32` node id
/// or chain id that wrapped would address a real but wrong record, so the build
/// would succeed and the graph would be quietly wrong. The ceilings are ~2.5x
/// away at planet scale, which is close enough to be worth naming.
fn cap_u32(what: &str, n: u64) -> Result<u32> {
    u32::try_from(n).map_err(|_| {
        Error(format!(
            "{n} {what}(s) is past the {} this format can address",
            u32::MAX
        ))
    })
}

/// Dense id -> final (Morton) id, for the nodes that survived.
///
/// Indexed by rank over the surviving-node bitset rather than by dense id, so it
/// costs four bytes per *survivor* instead of four per marked id — a difference of
/// tens of gigabytes at planet scale, since the marked set includes every node any
/// routable way ever mentions.
struct FinalIds<'a> {
    kept: &'a Bitset,
    of_kept: &'a [u32],
}

impl FinalIds<'_> {
    #[inline]
    fn get(&self, dense: u32) -> u32 {
        self.of_kept[self.kept.dense(u64::from(dense)) as usize]
    }
}

/// Out-edges per surviving node, as a CSR in final id space.
///
/// Built by two sequential scans of the chain spill, and it pays for itself three
/// times over: [`twin_is_unique`] becomes a binary search in one node's group
/// rather than in the whole edge array, the component scan walks it instead of a
/// materialised edge array — which is what makes that scan affordable at all — and
/// each write round takes its expected edge count straight out of `edge_ptr`.
struct Csr {
    /// Directed edges before node `v`'s group. Length is `kept_count + 1`.
    edge_ptr: Vec<u64>,
    /// Each group's targets, ascending.
    targets: Vec<u32>,
}

impl Csr {
    #[inline]
    fn out(&self, v: u32) -> &[u32] {
        let (a, b) = (self.edge_ptr[v as usize], self.edge_ptr[v as usize + 1]);
        &self.targets[a as usize..b as usize]
    }

    fn edge_count(&self) -> u64 {
        *self.edge_ptr.last().expect("edge_ptr has a sentinel")
    }
}

/// One synthetic connector reattaching an isolated transit stop.
///
/// Kept apart from the CSR rather than merged into it: there are a handful of
/// these against billions of real edges, and inserting them would mean rebuilding
/// `targets` to make room.
#[derive(Clone, Copy)]
struct Synth {
    source: u32,
    target: u32,
    dist_mm: u32,
}

fn build_csr(spill: &chains::Spill, kept_count: u32, ids: &FinalIds) -> Result<Csr> {
    println!("Building the edge index over {kept_count} node(s)...");
    let mut edge_ptr = vec![0u64; kept_count as usize + 2];
    let mut hdr = spill.headers()?;
    while let Some(c) = hdr.next()? {
        edge_ptr[ids.get(c.first) as usize + 1] += 1;
        if !c.oneway {
            edge_ptr[ids.get(c.last) as usize + 1] += 1;
        }
    }
    for i in 0..=kept_count as usize {
        edge_ptr[i + 1] += edge_ptr[i];
    }

    let total = edge_ptr[kept_count as usize];
    let mut targets = vec![0u32; total as usize];
    let mut cursor: Vec<u64> = edge_ptr[..=kept_count as usize].to_vec();
    let mut hdr = spill.headers()?;
    while let Some(c) = hdr.next()? {
        let (a, b) = (ids.get(c.first), ids.get(c.last));
        let slot = &mut cursor[a as usize];
        targets[*slot as usize] = b;
        *slot += 1;
        if !c.oneway {
            let slot = &mut cursor[b as usize];
            targets[*slot as usize] = a;
            *slot += 1;
        }
    }
    edge_ptr.truncate(kept_count as usize + 1);

    // Per group, so `twin_is_unique` can binary-search it. Sorting the whole array
    // would be one comparison sort over a billion elements; this is millions of
    // sorts over a handful each.
    for v in 0..kept_count as usize {
        let (a, b) = (edge_ptr[v] as usize, edge_ptr[v + 1] as usize);
        targets[a..b].sort_unstable();
    }
    println!("{total} directed edge(s) from chains");
    Ok(Csr { edge_ptr, targets })
}

/// True when `target`'s edge range holds exactly one edge pointing back at
/// `source`.
///
/// `graph.rs` resolves a reversed edge by scanning the target's range for the
/// first edge whose own target is the source, so with two parallel roads between
/// the same pair of nodes it could pick the wrong twin and draw the wrong
/// polyline.
///
/// The synthetic connectors have to be counted too, even though they are not in
/// the CSR, because the reader does not know they are synthetic. In practice they
/// never collide — a connector runs from a component too small to be routable into
/// one that is, so no chain can join the same pair — but counting them makes that a
/// measured fact rather than an assumption, and it is a binary search in a list of
/// a few thousand.
fn twin_is_unique(csr: &Csr, synth: &[Synth], source: u32, target: u32) -> bool {
    let group = csr.out(target);
    let lo = group.partition_point(|t| *t < source);
    let hi = group.partition_point(|t| *t <= source);
    let from_synth = {
        let key = (target, source);
        let a = synth.partition_point(|s| (s.source, s.target) < key);
        let b = synth.partition_point(|s| (s.source, s.target) <= key);
        b - a
    };
    hi - lo + from_synth == 1
}

fn pass1_blob(state: &mut Pass1, block: &pbf::PrimitiveBlock) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(
        block,
        KIND_NODES | KIND_WAYS,
        &mut kinds,
        &mut |el: Element| {
            match el {
                Element::Node(n) => {
                    if tags::is_stop_node(
                        n.tags.get_str("highway"),
                        n.tags.get_str("railway"),
                        n.tags.get_str("public_transport"),
                    ) {
                        state.stop_nodes.push(n.id);
                    }
                }
                Element::Way(w) => {
                    if tags::get_hw_id(w.tags.get_str("highway")) != 0 {
                        state.refs.extend_from_slice(w.refs);
                    }
                }
                Element::Relation(_) => {}
            }
            Ok(())
        },
    )?;
    Ok(kinds)
}

/// Everything the graph takes from one routable way's tags.
pub(crate) struct WayAttrs {
    /// Chunk-local name id, or `u32::MAX` for an unnamed way.
    pub name: u32,
    pub type_: u8,
    pub speed_limit: u8,
    pub oneway: bool,
    pub fwd_lane_off: u32,
    pub fwd_lane_count: u16,
    pub bwd_lane_off: u32,
    pub bwd_lane_count: u16,
}

fn way_blob(
    state: &mut WayPass,
    block: &pbf::PrimitiveBlock,
    index: &NodeIndex,
    coords: &[geom::Pt],
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
        let Element::Way(w) = el else {
            return Ok(());
        };
        let type_ = tags::get_hw_id(w.tags.get_str("highway"));
        if type_ == 0 {
            return Ok(());
        }
        let attrs = way_attrs(&w, type_, &mut state.lanes, &mut state.names);
        for pair in w.refs.windows(2) {
            // The same predicate the within-way path uses, so the two collapse
            // paths agree on what a segment is. Asking a different question here
            // would make the reference path emit a zero-length self-loop for a
            // repeated ref, and the byte-identity comparison between the two would
            // stop meaning anything.
            let Some((u, v)) = chains::segment(index, pair[0], pair[1]) else {
                continue;
            };
            let (a, b) = (coords[u as usize], coords[v as usize]);
            state.segs.push(Seg {
                u,
                v,
                dist_mm: accurate_dist_mm(a.0, a.1, b.0, b.1),
                name_offset: attrs.name,
                type_: attrs.type_,
                speed_limit: attrs.speed_limit,
                oneway: attrs.oneway,
                fwd_lane_off: attrs.fwd_lane_off,
                fwd_lane_count: attrs.fwd_lane_count,
                bwd_lane_off: attrs.bwd_lane_off,
                bwd_lane_count: attrs.bwd_lane_count,
            });
        }
        Ok(())
    })?;
    Ok(kinds)
}

/// Lane masks are pushed into `lanes` and the name interned into `names`, both
/// chunk-local: the sink rebases the offsets when it folds the chunk in.
pub(crate) fn way_attrs(
    w: &crate::osm::WayView,
    type_: u8,
    lanes: &mut Vec<u16>,
    names: &mut LocalNames,
) -> WayAttrs {
    let name = match w.tags.get("name") {
        Some(n) => names.id(n),
        None => u32::MAX,
    };
    let speed_limit = tags::parse_maxspeed(w.tags.get_str("maxspeed"));
    let oneway = w.tags.get_str("oneway") == Some("yes");

    // Forward lanes come from turn:lanes:forward, or from plain turn:lanes on a
    // oneway; backward from turn:lanes:backward. Plain `lanes*` only refine the
    // count. No turn:lanes at all means no lane data, and the router infers lanes
    // from junction topology instead.
    let tl = w.tags.get_str("turn:lanes");
    let tlf = w.tags.get_str("turn:lanes:forward");
    let tlb = w.tags.get_str("turn:lanes:backward");
    let count_all = tags::parse_int_tag(w.tags.get_str("lanes"));
    let count_fwd = tags::parse_int_tag(w.tags.get_str("lanes:forward"));
    let count_bwd = tags::parse_int_tag(w.tags.get_str("lanes:backward"));

    let fwd_spec = tlf.or(if oneway { tl } else { None });
    let fwd_hint = if count_fwd > 0 {
        count_fwd
    } else if oneway {
        count_all
    } else {
        0
    };
    let bwd_spec = if oneway { None } else { tlb };
    let fwd = tags::build_dir_lanes(fwd_spec, fwd_hint);
    let bwd = tags::build_dir_lanes(bwd_spec, count_bwd);

    let mut push_lanes = |masks: &[u16]| -> (u32, u16) {
        if masks.is_empty() {
            return (NO_LANES, 0);
        }
        let off = lanes.len() as u32;
        lanes.extend_from_slice(masks);
        (off, masks.len() as u16)
    };
    let (fwd_lane_off, fwd_lane_count) = push_lanes(&fwd);
    let (bwd_lane_off, bwd_lane_count) = push_lanes(&bwd);

    WayAttrs {
        name,
        type_,
        speed_limit,
        oneway,
        fwd_lane_off,
        fwd_lane_count,
        bwd_lane_off,
        bwd_lane_count,
    }
}

fn pass2_blob(state: &mut Pass2, block: &pbf::PrimitiveBlock, mask: &Bitset) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_NODES, &mut kinds, &mut |el: Element| {
        if let Element::Node(n) = el {
            if n.id < 0 || n.id as u64 >= BITSET_SIZE || !mask.get(n.id as u64) {
                return Ok(());
            }
            let dense = mask.dense(n.id as u64);
            // A `gtfs:stop_code:<feed>` tag means this node is a matched GTFS
            // stop; its `name` (or the code itself) becomes the stop label the
            // transit planner shows. Otherwise fall back to the plain OSM stop
            // tags.
            let gtfs_code = n
                .tags
                .iter()
                .find(|(k, _)| k.starts_with(b"gtfs:stop_code:"))
                .map(|(_, v)| n.tags.get("name").unwrap_or(v));
            let stop_code = match gtfs_code {
                Some(code) => Some(code),
                None => tags::is_stop_node(
                    n.tags.get_str("highway"),
                    n.tags.get_str("railway"),
                    n.tags.get_str("public_transport"),
                )
                .then(|| n.tags.get("name").unwrap_or(UNNAMED_STOP)),
            };
            state.locs.push((dense, n.lat_e7, n.lon_e7));
            if let Some(code) = stop_code {
                state.stops.push((dense, code.to_vec()));
            }
        }
        Ok(())
    })?;
    Ok(kinds)
}

/// What one stop-reconnection pass did, for [`Stats`] and for the log line that
/// makes [`MIN_ROUTABLE_COMPONENT`] auditable.
struct Reconnected {
    lcc_size: u64,
    /// Bidirectional connectors, sorted by `(source, target)`.
    synth: Vec<Synth>,
    already_connected: usize,
    unreachable: usize,
}

/// Hook every transit stop that is *not* attached to a real road network onto the
/// nearest node that is. Without this a matched GTFS stop that OSM never attached
/// to a road is unreachable and the transit planner cannot get the user to it.
///
/// # Why component *size* and not the largest component
///
/// The question worth asking is "is this stop attached to a road network?", not
/// "is it attached to the biggest one?". On a region those coincide — the largest
/// component is 98.2% of California — so the pass used to find the largest
/// component and reconnect everything outside it. On a planet they do not:
/// continents are not road-connected, so the largest component is
/// Eurasia-plus-Africa at 57.7% and every stop in the Americas, Australia, Japan,
/// the UK and Indonesia was classified as isolated. The code then tried to bridge
/// an ocean to reach a road in France, failed 1,173,347 times — a quarter of all
/// stops — and paid for a widening search out to a million-node index window
/// before each failure.
///
/// So a stop needs reconnecting only when its own component is smaller than
/// [`MIN_ROUTABLE_COMPONENT`], and the target is the nearest node in *any*
/// component at or above that threshold. A stop alone on an untagged node is a
/// component of size 1 and is still connected; a stop on a small island's road
/// network is left alone, because it is already as reachable as that island gets;
/// a stop in North America connects to North America. That also removes the
/// pathology rather than its symptom: a qualifying node is now metres away, so the
/// widening search almost always succeeds on its first window.
///
/// The threshold is capped at the largest component that exists, because a graph
/// with no component of 1000 nodes still has a road network — it is just a small
/// one — and requiring a size nothing reaches would reconnect nothing at all.
///
/// One caveat on the word "component": the scan follows out-edges only, because
/// that is the index the build has — a reverse CSR would be another 4.3 GB of
/// `targets` at planet scale. So the partition is by out-reachability, and a node
/// fed only by incoming one-ways can be labelled separately from the network that
/// feeds it, undercounting that network's size. The error is in the safe direction:
/// such a node looks *less* connected than it is, so at worst it gains a connector
/// it did not need. The same traversal is what measured California's largest
/// component at 98.24%, which bounds how far off it can be in practice.
///
/// `coords` and `stops` are both in final (Morton) id space.
fn reconnect_isolated_stops(coords: &[geom::Pt], stops: &Bitset, csr: &Csr) -> Reconnected {
    let n = coords.len();
    if n == 0 {
        return Reconnected {
            lcc_size: 0,
            synth: Vec::new(),
            already_connected: 0,
            unreachable: 0,
        };
    }
    println!("Identifying connected components...");

    let mut component = vec![u32::MAX; n];
    // One size per component id. Planet has ~291 K components, so 1.2 MB.
    let mut sizes: Vec<u32> = Vec::new();
    let mut queue: Vec<u32> = Vec::with_capacity(n);
    for start in 0..n {
        if component[start] != u32::MAX {
            continue;
        }
        let id = sizes.len() as u32;
        let base = queue.len();
        queue.push(start as u32);
        component[start] = id;
        let mut head = base;
        while head < queue.len() {
            let u = queue[head];
            head += 1;
            for v in csr.out(u) {
                if component[*v as usize] == u32::MAX {
                    component[*v as usize] = id;
                    queue.push(*v);
                }
            }
        }
        sizes.push((queue.len() - base) as u32);
    }
    drop(queue);

    let lcc_size = sizes.iter().copied().max().unwrap_or(0);
    let threshold = MIN_ROUTABLE_COMPONENT.min(lcc_size);
    println!(
        "{} component(s), largest {lcc_size} / {n} node(s) ({:.1}%); a component of \
         {threshold}+ node(s) counts as routable",
        sizes.len(),
        f64::from(lcc_size) * 100.0 / n as f64
    );

    let routable = |node: u32| sizes[component[node as usize] as usize] >= threshold;
    let mut stop_count = 0usize;
    let mut isolated: Vec<u32> = Vec::new();
    for i in 0..n as u32 {
        if stops.get(u64::from(i)) {
            stop_count += 1;
            if !routable(i) {
                isolated.push(i);
            }
        }
    }
    let already_connected = stop_count - isolated.len();
    if isolated.is_empty() {
        println!("{stop_count} stop(s), all already on a routable component");
        return Reconnected {
            lcc_size: u64::from(lcc_size),
            synth: Vec::new(),
            already_connected,
            unreachable: 0,
        };
    }

    // The nodes are Morton-sorted, so index distance approximates spatial
    // distance: widen an index window around the stop until it contains a node in
    // a routable component, then take the closest one in that window.
    let found = par::map_chunks(&isolated, 256, |_, chunk| {
        let mut out: Vec<Synth> = Vec::new();
        let mut failed = 0usize;
        for &i in chunk {
            let stop = coords[i as usize];
            let mut best: Option<(u32, u32)> = None;
            let mut radius = 1000usize;
            while radius <= 1_000_000 {
                let lo = (i as usize).saturating_sub(radius);
                let hi = (i as usize + radius).min(n);
                for j in lo..hi {
                    if !routable(j as u32) {
                        continue;
                    }
                    let d = accurate_dist_mm(stop.0, stop.1, coords[j].0, coords[j].1);
                    if best.is_none_or(|(bd, _)| d < bd) {
                        best = Some((d, j as u32));
                    }
                }
                if best.is_some() {
                    break;
                }
                radius *= 10;
            }
            if let Some((dist_mm, target)) = best {
                out.push(Synth {
                    source: i,
                    target,
                    dist_mm,
                });
                out.push(Synth {
                    source: target,
                    target: i,
                    dist_mm,
                });
            } else {
                failed += 1;
            }
        }
        (out, failed)
    });

    let unreachable: usize = found.iter().map(|(_, f)| f).sum();
    let mut synth: Vec<Synth> = found.into_iter().flat_map(|(e, _)| e).collect();
    println!(
        "{stop_count} stop(s): {already_connected} already connected, {} reconnected, \
         {unreachable} found nothing",
        synth.len() / 2
    );
    if unreachable > 0 {
        // The index window caps at 1e6 nodes, so a stop with no routable road
        // within that window stays unreachable. Say so rather than dropping it
        // silently: an unreachable stop is exactly what this pass exists to
        // prevent. A large count here means MIN_ROUTABLE_COMPONENT is too high.
        println!("WARNING: {unreachable} isolated stop(s) had no routable road nearby");
    }
    synth.sort_by_key(|s| (s.source, s.target));
    Reconnected {
        lcc_size: u64::from(lcc_size),
        synth,
        already_connected,
        unreachable,
    }
}

/// What `write_graph` produced, for [`Stats`].
struct Written {
    edge_count: u64,
    geometry_edges: u64,
    reversed_edges: u64,
    intermediate_bytes: u64,
    edges_bytes: u64,
    escape_count: u64,
    named_edges: u64,
    census: Census,
}

/// Write the pack in `rounds` source-partitioned rounds.
///
/// # Why rounds, and not computed offsets
///
/// The obvious alternative is to compute each edge's index and `pwrite` it into
/// place. That is sound for `edges.bin` — fixed 14-byte records in a pre-sized
/// file — but wrong for the two blob files. The reader derives a polyline's length
/// from the next offset entry and a lane run's from the next index entry, so
/// `intermediate.bin` and `lanes.bin` are forced into edge-index order with
/// monotonic offsets, and writing a blob at a computed offset would mean knowing
/// every earlier blob's length first: a per-edge length array, a prefix sum and a
/// second encode pass.
///
/// Partitioning by source instead makes every write an append. Each round takes a
/// contiguous range of final node ids, scans the chain spill for the edges whose
/// *source* falls in that range, sorts that buffer, and writes both blobs, the
/// presence bitmap, the offset tables, `edges.bin` and `nodes.bin` sequentially.
/// Offsets come out monotonic by construction, `nodes.bin`'s running edge counter
/// works because the rounds ascend, and the `(source, target)` grouping the reader
/// and [`twin_is_unique`] depend on is restored within each round.
///
/// The cost is `rounds` sequential reads of the spill instead of one.
#[allow(clippy::too_many_arguments)]
fn write_graph(
    out_dir: &Path,
    node_coords: &[geom::Pt],
    spill: &chains::Spill,
    lane_pool: &[u16],
    csr: &Csr,
    synth: &[Synth],
    ids: &FinalIds,
    rounds: u32,
) -> Result<Written> {
    let kept = node_coords.len() as u32;
    let edge_count = csr.edge_count() + synth.len() as u64;
    // `nodes.bin`'s `edge_ptr` is a `u32`, so an edge count past that ceiling would
    // wrap and point a node at another node's edge range.
    cap_u32("directed edge", edge_count)?;
    println!("Writing {edge_count} edge(s) in {rounds} round(s)...");

    let mut nodes_out = BufWriter::new(create(&out_dir.join("nodes.bin"))?);
    let mut edges_out = EdgeFile::create(out_dir, "edges.bin", edge_count)?;

    // `intermediate.bin` puts its blob at offset 0 and everything that indexes it
    // in a trailer, so it is written strictly forwards with no scratch file at all
    // (see [`GeomFile`]). `lanes.bin` is sparse and so cannot size its index up
    // front; its blob streams to scratch instead and the index is written in front
    // of it.
    let mut inter = GeomFile::create(out_dir, "intermediate.bin", edge_count)?;
    let mut lanes = LaneFile::create(out_dir, "lanes.bin")?;

    let mut scratch: Vec<geom::Pt> = Vec::new();
    let mut encoded: Vec<u8> = Vec::new();
    let mut lane_bytes: Vec<u8> = Vec::new();
    let mut buffer: Vec<TmpEdge> = Vec::new();
    let mut points = spill.points()?;
    let mut edge_ptr: u64 = 0;
    let mut geometry_edges: u64 = 0;
    let mut reversed_edges: u64 = 0;
    let mut census = Census::default();

    for round in 0..rounds {
        // Split by node count. Contiguous and ascending, so the four output streams
        // stay append-only across the whole run.
        let lo = (u64::from(kept) * u64::from(round) / u64::from(rounds)) as u32;
        let hi = (u64::from(kept) * u64::from(round + 1) / u64::from(rounds)) as u32;

        buffer.clear();
        let mut hdr = spill.headers()?;
        let mut ci = 0u32;
        while let Some(c) = hdr.next()? {
            let (a, b) = (ids.get(c.first), ids.get(c.last));
            if (lo..hi).contains(&a) {
                buffer.push(chain_edge(&c, ci, a, b, false));
            }
            if !c.oneway && (lo..hi).contains(&b) {
                buffer.push(chain_edge(&c, ci, b, a, true));
            }
            ci += 1;
        }
        let synth_lo = synth.partition_point(|s| s.source < lo);
        let synth_hi = synth.partition_point(|s| s.source < hi);
        for s in &synth[synth_lo..synth_hi] {
            buffer.push(TmpEdge {
                source: s.source,
                target: s.target,
                dist_mm: s.dist_mm,
                name_offset: NO_NAME,
                type_: RECONNECT_TYPE,
                speed_limit: RECONNECT_SPEED,
                lane_off: NO_LANES,
                lane_count: 0,
                chain: NO_CHAIN,
                chain_rev: false,
                pts_start: 0,
                pts_len: 0,
            });
        }

        // The CSR already knows how many edges this range owns. Checking against it
        // catches both an edge landing in the wrong round and an edge whose source
        // is not a surviving node — which is what the old
        // `assert_eq!(cursor, edges.len())` was for, now checked per round and
        // before anything is written rather than after everything is.
        let expected = csr.edge_ptr[hi as usize] - csr.edge_ptr[lo as usize]
            + (synth_hi - synth_lo) as u64;
        if buffer.len() as u64 != expected {
            return Err(Error(format!(
                "round {round} covering node(s) {lo}..{hi} collected {} edge(s), \
                 but the edge index says {expected}",
                buffer.len()
            )));
        }

        // Grouped by source for the reader, then by target so a node's parallel
        // edges are adjacent, then by chain so the order is total. The sort is
        // stable and the buffer was built in chain order, so the two directions of
        // a self-loop keep the order they were emitted in.
        buffer.sort_by_key(|e| (e.source, e.target, e.chain));

        let mut cursor = 0usize;
        for v in lo..hi {
            let node = node_coords[v as usize];
            write_node(&mut nodes_out, node.0, node.1, edge_ptr as u32).map_err(io_err)?;
            let degree_base = edge_ptr;
            while cursor < buffer.len() && buffer[cursor].source == v {
                let e = &buffer[cursor];
                census.edge(e.source, e.target, e.dist_mm, e.name_offset != NO_NAME);

                // --- geometry ---
                let mut type_ = e.type_;
                let mut stores = false;
                // A two-point chain is just the chord between the edge's own
                // endpoints, and under the interior-only encoding that is what an
                // absent blob already means, so storing it would cost a presence
                // bit to say nothing — and, more to the point here, a read to find
                // that out.
                if e.chain != NO_CHAIN && e.pts_len >= 3 {
                    if e.chain_rev
                        && e.source != e.target
                        && twin_is_unique(csr, synth, e.source, e.target)
                    {
                        type_ |= REVERSE_GEOMETRY_FLAG;
                        reversed_edges += 1;
                    } else {
                        // Only now is the polyline actually needed. Deciding the
                        // flag first matters more than it looks: this is the only
                        // random read in the writer, so every edge that defers to
                        // its twin is one seek saved, and on a filesystem whose
                        // syscalls are expensive that dominated the whole build.
                        points.read(e.pts_start, e.pts_len, &mut scratch)?;
                        if e.chain_rev {
                            scratch.reverse();
                        }
                        assert!(
                            geom::fits(&scratch),
                            "chain of {} points cannot be encoded; the collapse should \
                             have split it",
                            scratch.len()
                        );
                        // The blob holds only the interior, so the reader rebuilds
                        // both ends out of `nodes.bin`.
                        assert_endpoints(&scratch, node_coords, e.source, e.target, e.chain);
                        encoded.clear();
                        let n = geom::encode(&scratch, &mut encoded);
                        debug_assert_eq!(n as usize, geom::encoded_points(&scratch) as usize);
                        inter.store(&encoded)?;
                        geometry_edges += 1;
                        stores = true;
                    }
                }
                if !stores {
                    inter.skip();
                }

                edges_out.push(e, type_)?;
                if e.lane_count > 0 && e.lane_off != NO_LANES {
                    let start = e.lane_off as usize;
                    lane_bytes.clear();
                    for m in &lane_pool[start..start + e.lane_count as usize] {
                        lane_bytes.extend_from_slice(&m.to_le_bytes());
                    }
                    lanes.push(edge_ptr, &lane_bytes)?;
                }
                edge_ptr += 1;
                cursor += 1;
            }
            census.node((edge_ptr - degree_base) as u32);
        }
        debug_assert_eq!(cursor, buffer.len(), "the round left edges unwritten");
    }

    if edge_ptr != edge_count {
        return Err(Error(format!(
            "wrote {edge_ptr} edge(s) into a file sized for {edge_count}"
        )));
    }
    // Trailing sentinel so `graph.rs` can read node(v + 1).edge_ptr as the end of
    // node v's edge range.
    write_node(&mut nodes_out, 0, 0, edge_ptr as u32).map_err(io_err)?;
    nodes_out.flush().map_err(io_err)?;
    let escape_count = edges_out.escapes.len() as u64;
    let named_edges = edges_out.name_offsets.len() as u64;
    let edges_bytes = edges_out.finish(edge_count)?;

    let intermediate_bytes = inter.finish(edge_count)?;
    lanes.finish()?;

    // metadata.bin's magic + version let the device refuse a pack directory
    // holding files from two different vintages instead of misreading it, and its
    // counts are what every other file's length is validated against.
    let mut meta = create(&out_dir.join("metadata.bin"))?;
    meta.write_all(&GRAPH_MAGIC.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&GRAPH_VERSION.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&u64::from(kept).to_le_bytes()).map_err(io_err)?;
    meta.write_all(&edge_count.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&escape_count.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&named_edges.to_le_bytes()).map_err(io_err)?;
    meta.flush().map_err(io_err)?;

    Ok(Written {
        edge_count,
        geometry_edges,
        reversed_edges,
        intermediate_bytes,
        edges_bytes,
        escape_count,
        named_edges,
        census,
    })
}

/// The endpoint guarantee `intermediate.bin`'s interior-only encoding rests on: a
/// chain's first and last points are the coordinates of its first and last node.
///
/// True by construction, since both come from the same `node_coords` array, but
/// never checked before v3 — and the reader now *depends* on it, because it rebuilds
/// both ends out of `nodes.bin`. If this ever stopped holding, the failure would be
/// a silently wrong polyline on device rather than a failed build.
fn assert_endpoints(
    chain: &[geom::Pt],
    node_coords: &[geom::Pt],
    source: u32,
    target: u32,
    ci: u32,
) {
    assert_eq!(
        chain[0], node_coords[source as usize],
        "chain {ci} starts off its source node {source}"
    );
    assert_eq!(
        chain[chain.len() - 1], node_coords[target as usize],
        "chain {ci} ends off its target node {target}"
    );
}

/// One directed edge of a chain, in final id space.
fn chain_edge(c: &chains::ChainRec, ci: u32, source: u32, target: u32, rev: bool) -> TmpEdge {
    TmpEdge {
        source,
        target,
        dist_mm: c.dist_mm,
        name_offset: c.name_offset,
        type_: c.type_,
        speed_limit: c.speed_limit,
        lane_off: if rev { c.bwd_lane_off } else { c.fwd_lane_off },
        lane_count: if rev { c.bwd_lane_count } else { c.fwd_lane_count },
        chain: ci,
        chain_rev: rev,
        pts_start: c.pts_start,
        pts_len: c.pts_len,
    }
}

/// `intermediate.bin`: the polyline blob at offset 0, then everything needed to
/// index it as a **trailer**.
///
/// ```text
/// [ blob ]
/// [ u64 rank[E.div_ceil(512) + 1] ]   set bits before each 64-byte block, total appended
/// [ u8  present[E.div_ceil(8)] ]      one bit per directed edge
/// [ u64 coarse[G.div_ceil(32) + 1] ]  G = the edges that store a polyline
/// [ u16 within[G + 1] ]
/// [ u64 G ]                           fixed-size trailer
/// ```
///
/// `off(g) = coarse[g / 32] + within[g]` for the *g*-th geometry edge, where
/// `g = rank(idx)`; entry *g* runs to `off(g + 1)`, so the sentinel gives the last
/// edge its length and the blob its total.
///
/// # Why the offsets describe geometry edges, not edges
///
/// 70.4% of a planet's directed edges store no polyline at all (measured on
/// Europe: 110,807,665 of 374,856,290 store one), and under v2 each of them still
/// owned a `u16` and a repeated coarse `u64`. A presence bitmap costs one bit per
/// edge and makes "absent" an explicit, free test, which takes 0.87 GB of tables
/// where v2 needed 2.42 GB. It also tightens the `u16` bound rather than loosening
/// it: every entry in a block is now a real blob, where before most were
/// zero-length.
///
/// # Why the tables come last
///
/// `G` is not known until the final round has run, so a reserved prefix cannot be
/// sized. Streaming the blob to scratch and copying it back \u2014 what [`LaneFile`]
/// does, which is free for 13 MB \u2014 would be ~23 GB of extra I/O here. A trailer
/// instead makes the whole file append-only with **no scratch file at all**: write
/// the blob as it is encoded, then append the tables and `G`. That is strictly
/// less I/O than v2, which copied a 2.4 GB prefix back.
///
/// The tables are held in memory rather than spilled, which is 0.87 GB at planet
/// scale against the blob's 7.63 GB.
struct GeomFile {
    path: PathBuf,
    out: BufWriter<File>,
    blob_len: u64,
    /// One bit per directed edge, sized up front from the edge count.
    present: PresenceBitmap,
    coarse: Vec<u64>,
    within: Vec<u16>,
    /// Blob offset of the first geometry edge in the block being filled, i.e. the
    /// coarse entry the `u16`s are currently relative to.
    block_base: u64,
    /// Directed edges seen so far, which is the index the next call describes.
    pushed: u64,
    /// Geometry edges so far, i.e. `G` once the file is complete.
    stored: u64,
}

impl GeomFile {
    /// Byte size of the trailer for `edge_count` directed edges of which
    /// `geometry_edges` store a polyline. The reader sizes it the same way, from
    /// `edges.bin`'s length and the final `u64`.
    fn trailer_bytes(edge_count: u64, geometry_edges: u64) -> u64 {
        PresenceBitmap::bytes(edge_count)
            + (geometry_edges.div_ceil(INTERMEDIATE_BLOCK) + 1) * 8
            + (geometry_edges + 1) * 2
            + 8
    }

    fn create(dir: &Path, name: &str, edge_count: u64) -> Result<GeomFile> {
        let path = dir.join(name);
        Ok(GeomFile {
            out: BufWriter::new(create(&path)?),
            path,
            blob_len: 0,
            present: PresenceBitmap::new(edge_count),
            coarse: Vec::new(),
            within: Vec::new(),
            block_base: 0,
            pushed: 0,
            stored: 0,
        })
    }

    /// This edge stores no polyline. Its presence bit stays clear and it owns no
    /// offset at all, which is the whole point of the bitmap.
    fn skip(&mut self) {
        self.pushed += 1;
    }

    /// Record `bytes` as this edge's polyline. Called once per storing edge, in
    /// ascending edge index, because the rounds ascend and each round writes its
    /// edges in index order.
    fn store(&mut self, bytes: &[u8]) -> Result<()> {
        debug_assert!(
            !bytes.is_empty(),
            "an interior-only blob of no bytes is the chord, which needs no presence bit"
        );
        self.present.set(self.pushed);
        self.push_entry();
        self.out.write_all(bytes).map_err(io_err)?;
        self.blob_len += bytes.len() as u64;
        self.stored += 1;
        self.pushed += 1;
        Ok(())
    }

    /// One `(coarse?, within)` pair for geometry index `self.stored`.
    fn push_entry(&mut self) {
        if self.stored.is_multiple_of(INTERMEDIATE_BLOCK) {
            self.block_base = self.blob_len;
            self.coarse.push(self.blob_len);
        }
        let within = self.blob_len - self.block_base;
        assert!(
            within <= u64::from(u16::MAX),
            "block starting at geometry edge {} spans {within} bytes, past the {} a u16 offset \
             can address; a per-edge blob should be at most {} bytes",
            self.stored - self.stored % INTERMEDIATE_BLOCK,
            u16::MAX,
            4 * (geom::MAX_POINTS - 2)
        );
        self.within.push(within as u16);
    }

    /// Append the sentinel entry and the whole trailer. Returns the finished
    /// file's size.
    fn finish(mut self, edge_count: u64) -> Result<u64> {
        if self.pushed != edge_count {
            return Err(Error(format!(
                "{} describes {} edge(s), not the {edge_count} written",
                self.path.display(),
                self.pushed
            )));
        }
        self.push_entry();
        // `coarse` is sized by `div_ceil`, so when `G` is not a multiple of the
        // block size it holds one slot past the last block the sentinel touched.
        // Filling it keeps the table exactly the length the reader computes.
        if !self.stored.is_multiple_of(INTERMEDIATE_BLOCK) {
            self.coarse.push(self.blob_len);
        }

        // The presence bitmap and its rank index, the same pair `edges.bin`'s names
        // use. The rank total is `G`, which is what lets the reader cross-check the
        // two halves of the trailer against each other.
        let total = self.present.write(&mut self.out)?;
        debug_assert_eq!(total, self.stored, "the presence bitmap disagrees with G");
        for c in &self.coarse {
            self.out.write_all(&c.to_le_bytes()).map_err(io_err)?;
        }
        for w in &self.within {
            self.out.write_all(&w.to_le_bytes()).map_err(io_err)?;
        }
        self.out.write_all(&self.stored.to_le_bytes()).map_err(io_err)?;
        self.out.flush().map_err(io_err)?;

        let want = self.blob_len + GeomFile::trailer_bytes(edge_count, self.stored);
        let got = std::fs::metadata(&self.path)
            .map_err(|e| Error(format!("cannot stat {}: {e}", self.path.display())))?
            .len();
        if got != want {
            return Err(Error(format!(
                "{} came out {got} byte(s) long, not the {want} the reader will compute",
                self.path.display()
            )));
        }
        Ok(want)
    }
}

/// A presence bitmap over the directed edges, built in memory and written with a
/// rank index in front of it.
///
/// The writer half of `graph.rs`'s `EdgeBitmap`, and an on-disk contract with it:
///
/// ```text
/// [ u64 rank[E.div_ceil(512) + 1] ]   set bits before each 64-byte block, total appended
/// [ u8  present[E.div_ceil(8)] ]      one bit per directed edge
/// ```
///
/// Used twice, for the same reason the reader's is: `intermediate.bin`'s stored
/// polylines and `edges.bin`'s names are both per-edge fields most edges do not have.
struct PresenceBitmap {
    bits: Vec<u8>,
    /// Bits set so far, i.e. the length of the side table this indexes.
    set: u64,
}

impl PresenceBitmap {
    /// Byte size of the rank index plus the bitmap for `edge_count` edges. The reader
    /// sizes them the same way.
    fn bytes(edge_count: u64) -> u64 {
        (edge_count.div_ceil(RANK_BLOCK_BITS) + 1) * 8 + edge_count.div_ceil(8)
    }

    fn new(edge_count: u64) -> PresenceBitmap {
        PresenceBitmap {
            bits: vec![0u8; edge_count.div_ceil(8) as usize],
            set: 0,
        }
    }

    /// Mark edge `idx` as having one. Must be called in ascending `idx`, which every
    /// caller satisfies because the rounds ascend and each round writes its edges in
    /// index order.
    fn set(&mut self, idx: u64) {
        debug_assert!(
            self.bits[(idx / 8) as usize] & (1u8 << (idx % 8)) == 0,
            "edge {idx} was already marked"
        );
        self.bits[(idx / 8) as usize] |= 1u8 << (idx % 8);
        self.set += 1;
    }

    /// Write the rank index then the bitmap. Returns the number of set bits, which is
    /// the rank index's appended total and the length the side table must have.
    fn write<W: Write>(&self, out: &mut W) -> Result<u64> {
        // Set bits before each block, with the total appended. That total is what
        // lets the reader tie the index to the table it indexes.
        let blocks = self.bits.len().div_ceil(RANK_BLOCK_BYTES);
        let mut total = 0u64;
        for b in 0..blocks {
            out.write_all(&total.to_le_bytes()).map_err(io_err)?;
            let start = b * RANK_BLOCK_BYTES;
            let end = (start + RANK_BLOCK_BYTES).min(self.bits.len());
            total += self.bits[start..end]
                .iter()
                .map(|byte| u64::from(byte.count_ones()))
                .sum::<u64>();
        }
        out.write_all(&total.to_le_bytes()).map_err(io_err)?;
        out.write_all(&self.bits).map_err(io_err)?;
        debug_assert_eq!(total, self.set, "the bitmap disagrees with its own counter");
        Ok(total)
    }
}

/// `edges.bin`, in five sections:
///
/// ```text
/// [ EdgeRec[E] ]                     7 B: i16 target_delta, u24 dist_mm,
///                                         u8 type_, u8 speed_limit
/// [ pad to SECTION_ALIGN ]
/// [ u32 escape_first[E.div_ceil(1024) + 1] ]   first escape row of each block
/// [ { u32 edge_idx, u32 target, u32 dist_mm } x escapes ]   ascending by edge_idx
/// [ pad to SECTION_ALIGN ]
/// [ u64 name rank[E.div_ceil(512) + 1] ][ u8 name present[E.div_ceil(8)] ]
/// [ u32 name_off[named_edges] ]                indexed by rank in the bitmap
/// ```
///
/// `target` is a signed delta from the edge's own source, which is affordable only
/// because the nodes are sorted by a space-filling curve: 99.699% of California's
/// deltas fit an `i16`. `dist_mm` is a `u24`, exact below 16.78 km, which is
/// 99.999% of edges. Either field's sentinel means *both* come from one escape row,
/// so the reader takes one branch and the table stays single.
///
/// `name_offset` is sparse rather than a field, because two thirds of edges have no
/// name — 62.3% of California's, 68.9% of Europe's — and it is read when an
/// instruction is emitted, never in the A* relaxation.
///
/// # Why the tables come last, and why they fit in memory
///
/// Neither the escape count nor the named-edge count is known until the final round
/// has run, so a reserved prefix cannot be sized. But unlike `intermediate.bin` the
/// record array is a fixed stride, so this needs no trailer to be self-locating: the
/// reader computes all five offsets from `metadata.bin`'s counts. The escape rows
/// accumulate in memory — 39 MB at planet scale against `edges.bin`'s 8.6 GB — as do
/// the name offsets (1.6 GB, the largest of them) and the bitmap (134 MB).
struct EdgeFile {
    path: PathBuf,
    out: BufWriter<File>,
    /// `(edge_idx, target, dist_mm)` for the edges a record cannot hold, in
    /// ascending `edge_idx` — which is the order they are pushed in, because the
    /// rounds ascend and each round writes its edges in index order.
    escapes: Vec<(u32, u32, u32)>,
    /// Which edges have a name, and the pool offsets of the ones that do, in the
    /// same order.
    named: PresenceBitmap,
    name_offsets: Vec<u32>,
    /// Directed edges written so far, which is the index the next call describes.
    pushed: u64,
}

impl EdgeFile {
    /// Byte size of the whole file. The reader computes it the same way, from
    /// `metadata.bin`.
    fn total_bytes(edge_count: u64, escapes: u64, named: u64) -> u64 {
        let rows = align_up(edge_count * EDGE_REC_BYTES, SECTION_ALIGN)
            + (edge_count.div_ceil(ESCAPE_BLOCK) + 1) * 4
            + escapes * 12;
        align_up(rows, SECTION_ALIGN) + PresenceBitmap::bytes(edge_count) + named * 4
    }

    fn create(dir: &Path, name: &str, edge_count: u64) -> Result<EdgeFile> {
        let path = dir.join(name);
        Ok(EdgeFile {
            out: BufWriter::new(create(&path)?),
            path,
            escapes: Vec::new(),
            named: PresenceBitmap::new(edge_count),
            name_offsets: Vec::new(),
            pushed: 0,
        })
    }

    /// Write one directed edge's record, escaping it if either field will not fit and
    /// recording its name if it has one.
    fn push(&mut self, e: &TmpEdge, type_: u8) -> Result<()> {
        let (delta, dist) = match target_delta(e.source, e.target) {
            Some(d) if e.dist_mm < DIST_MM_ESCAPE => (d, e.dist_mm),
            // Both sentinels, not just the one that failed: the reader tests either,
            // so writing both keeps the two tests in agreement whichever field a
            // future caller happens to have in hand.
            _ => {
                let idx = cap_u32("escaped edge index", self.pushed)?;
                self.escapes.push((idx, e.target, e.dist_mm));
                (TARGET_DELTA_ESCAPE, DIST_MM_ESCAPE)
            }
        };
        self.out.write_all(&delta.to_le_bytes()).map_err(io_err)?;
        self.out.write_all(&dist.to_le_bytes()[..3]).map_err(io_err)?;
        self.out.write_all(&[type_, e.speed_limit]).map_err(io_err)?;
        if e.name_offset != NO_NAME {
            self.named.set(self.pushed);
            self.name_offsets.push(e.name_offset);
        }
        self.pushed += 1;
        Ok(())
    }

    /// Pad to the section boundary, then append the block index, the escape rows, the
    /// name bitmap and the name offsets. Returns the finished file's size.
    fn finish(mut self, edge_count: u64) -> Result<u64> {
        if self.pushed != edge_count {
            return Err(Error(format!(
                "{} holds {} edge(s), not the {edge_count} the pack is sized for",
                self.path.display(),
                self.pushed
            )));
        }
        let mut at = edge_count * EDGE_REC_BYTES;
        at = self.pad_to(at)?;

        // `escape_first[b]` is the index of the first row in block `b`, i.e. the
        // count of rows below `b * ESCAPE_BLOCK`. The rows ascend, so one walk over
        // them fills the whole array, and the final entry is the row total — which is
        // what lets the reader tie the index to the section it indexes.
        let blocks = edge_count.div_ceil(ESCAPE_BLOCK) + 1;
        let mut row = 0usize;
        for b in 0..blocks {
            let limit = b * ESCAPE_BLOCK;
            while row < self.escapes.len() && u64::from(self.escapes[row].0) < limit {
                row += 1;
            }
            self.out.write_all(&(row as u32).to_le_bytes()).map_err(io_err)?;
        }
        if row != self.escapes.len() {
            return Err(Error(format!(
                "{}: the block index reached row {row} of {}, so a row is past the \
                 last block",
                self.path.display(),
                self.escapes.len()
            )));
        }
        at += blocks * 4;

        for (idx, target, dist_mm) in &self.escapes {
            self.out.write_all(&idx.to_le_bytes()).map_err(io_err)?;
            self.out.write_all(&target.to_le_bytes()).map_err(io_err)?;
            self.out.write_all(&dist_mm.to_le_bytes()).map_err(io_err)?;
        }
        at += self.escapes.len() as u64 * 12;
        self.pad_to(at)?;

        let named = self.named.write(&mut self.out)?;
        if named != self.name_offsets.len() as u64 {
            return Err(Error(format!(
                "{}: the name bitmap has {named} bit(s) set but {} offset(s) were \
                 collected",
                self.path.display(),
                self.name_offsets.len()
            )));
        }
        for off in &self.name_offsets {
            self.out.write_all(&off.to_le_bytes()).map_err(io_err)?;
        }
        self.out.flush().map_err(io_err)?;

        let want = EdgeFile::total_bytes(edge_count, self.escapes.len() as u64, named);
        let got = std::fs::metadata(&self.path)
            .map_err(|e| Error(format!("cannot stat {}: {e}", self.path.display())))?
            .len();
        if got != want {
            return Err(Error(format!(
                "{} came out {got} byte(s) long, not the {want} the reader will compute",
                self.path.display()
            )));
        }
        Ok(want)
    }

    /// Write zeroes up to the next section boundary, returning the new offset.
    fn pad_to(&mut self, at: u64) -> Result<u64> {
        let aligned = align_up(at, SECTION_ALIGN);
        let zeroes = [0u8; SECTION_ALIGN as usize];
        self.out.write_all(&zeroes[..(aligned - at) as usize]).map_err(io_err)?;
        Ok(aligned)
    }
}

/// `lanes.bin`, as a sparse index: `[ u32 n ][ (u32 edge_idx, u32 off) x (n + 1) ]`
/// then the `u16` mask blob.
///
/// `turn:lanes` is rare — 2.9 M of a planet's 1.07 G directed edges carry any — so
/// a dense `u64` per edge made 99.8% of an 8.60 GB file describe edges with no
/// lanes. Listing only the edges that have them is ~23 MB of index plus a 13 MB
/// blob. Entry *i*'s masks run from its offset to entry *i+1*'s, so the trailing
/// sentinel gives the last edge its length and the blob its total.
///
/// The round-partitioned writer cannot know `n` up front, so the blob streams to a
/// scratch file while the index accumulates in memory (23 MB at planet scale) and
/// the header plus index are written in front of it at the end.
struct LaneFile {
    path: PathBuf,
    blob_path: PathBuf,
    blob: BufWriter<File>,
    index: Vec<(u32, u32)>,
    blob_len: u64,
}

impl LaneFile {
    fn create(dir: &Path, name: &str) -> Result<LaneFile> {
        let blob_path = dir.join(format!("{name}.blob"));
        Ok(LaneFile {
            path: dir.join(name),
            blob: BufWriter::new(create(&blob_path)?),
            blob_path,
            index: Vec::new(),
            blob_len: 0,
        })
    }

    /// Record `bytes` as edge `idx`'s masks. Only called for edges that have any,
    /// and always with an ascending `idx`, because the rounds ascend and each round
    /// writes its edges in index order.
    fn push(&mut self, idx: u64, bytes: &[u8]) -> Result<()> {
        debug_assert!(!bytes.is_empty(), "an empty lane blob would read back as absent");
        debug_assert!(
            self.index.last().is_none_or(|(last, _)| *last < idx as u32),
            "lane index entries must ascend by edge index"
        );
        let off = self.blob_off()?;
        self.index.push((idx as u32, off));
        self.blob.write_all(bytes).map_err(io_err)?;
        self.blob_len += bytes.len() as u64;
        Ok(())
    }

    /// The current blob length as the `u32` an index entry stores.
    fn blob_off(&self) -> Result<u32> {
        u32::try_from(self.blob_len).map_err(|_| {
            Error(format!(
                "the lane blob reached {} bytes, past the {} a u32 offset can address",
                self.blob_len,
                u32::MAX
            ))
        })
    }

    /// Write the header and index, then the blob behind it. Returns the finished
    /// file's size.
    fn finish(mut self) -> Result<u64> {
        self.blob.flush().map_err(io_err)?;
        let n = u32::try_from(self.index.len()).map_err(|_| {
            Error(format!(
                "{} lane-bearing edge(s) is past the {} the index header can count",
                self.index.len(),
                u32::MAX
            ))
        })?;
        let mut out = BufWriter::new(create(&self.path)?);
        out.write_all(&n.to_le_bytes()).map_err(io_err)?;
        for (idx, off) in &self.index {
            out.write_all(&idx.to_le_bytes()).map_err(io_err)?;
            out.write_all(&off.to_le_bytes()).map_err(io_err)?;
        }
        // The sentinel's edge index is `u32::MAX` rather than a real one: it exists
        // only to give the last entry a length, and the reader's binary search
        // covers the first `n` entries, so no valid index may collide with it. Its
        // offset is the blob length, which is also how the reader length-validates
        // the blob.
        let blob_end = self.blob_off()?;
        out.write_all(&u32::MAX.to_le_bytes()).map_err(io_err)?;
        out.write_all(&blob_end.to_le_bytes()).map_err(io_err)?;

        let mut src = BufReader::with_capacity(1 << 20, open(&self.blob_path)?);
        std::io::copy(&mut src, &mut out).map_err(io_err)?;
        out.flush().map_err(io_err)?;
        Ok(4 + u64::from(n + 1) * 8 + self.blob_len)
    }
}

impl Drop for LaneFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.blob_path);
    }
}

/// `nodes.bin` is 12 bytes a record: `edge_ptr` is a `u32`, which planet's 1.07 G
/// directed edges leave 4x of headroom in. [`cap_u32`] on the edge count is what
/// keeps that safe — a wrapped `edge_ptr` would address a real but wrong node's
/// edge range.
fn write_node<W: Write>(
    out: &mut W,
    lat_e7: i32,
    lon_e7: i32,
    edge_ptr: u32,
) -> std::io::Result<()> {
    out.write_all(&lat_e7.to_le_bytes())?;
    out.write_all(&lon_e7.to_le_bytes())?;
    out.write_all(&edge_ptr.to_le_bytes())
}

fn create(path: &Path) -> Result<File> {
    File::create(path).map_err(|e| Error(format!("cannot write {}: {e}", path.display())))
}

fn open(path: &Path) -> Result<File> {
    File::open(path).map_err(|e| Error(format!("cannot read {}: {e}", path.display())))
}

fn io_err(e: std::io::Error) -> Error {
    Error(e.to_string())
}

/// Parse the tool's command line:
/// `road_graph IN.osm.pbf [--out DIR] [--within-way-chains] [--rounds N]
/// [--spill-dir DIR] [--spill-pts-dir DIR] [--stats]`.
pub fn parse_args(
    args: &[String],
) -> std::result::Result<(PathBuf, PathBuf, Options), String> {
    let mut input: Option<PathBuf> = None;
    let mut out = PathBuf::from("map_data");
    let mut opts = Options::default();
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--out" | "-o" => {
                i += 1;
                let dir = args.get(i).ok_or_else(|| "--out needs a directory".to_string())?;
                out = PathBuf::from(dir);
            }
            "--within-way-chains" => opts.within_way_chains = true,
            "--stats" => opts.stats = true,
            "--spill-dir" => {
                i += 1;
                let dir = args
                    .get(i)
                    .ok_or_else(|| "--spill-dir needs a directory".to_string())?;
                opts.spill_dir = Some(PathBuf::from(dir));
            }
            "--spill-pts-dir" => {
                i += 1;
                let dir = args
                    .get(i)
                    .ok_or_else(|| "--spill-pts-dir needs a directory".to_string())?;
                opts.spill_pts_dir = Some(PathBuf::from(dir));
            }
            "--rounds" => {
                i += 1;
                let n = args.get(i).ok_or_else(|| "--rounds needs a count".to_string())?;
                opts.rounds = n
                    .parse::<u32>()
                    .ok()
                    .filter(|n| *n > 0)
                    .ok_or_else(|| format!("--rounds wants a positive count, not {n}"))?;
            }
            a if a.starts_with('-') => return Err(format!("unknown option: {a}")),
            a => {
                if input.is_some() {
                    return Err(format!("unexpected extra argument: {a}"));
                }
                input = Some(PathBuf::from(a));
            }
        }
        i += 1;
    }
    Ok((
        input.ok_or_else(|| "missing IN.osm.pbf".to_string())?,
        out,
        opts,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tags::{LANE_LEFT, LANE_THROUGH};
    use crate::testpbf;

    struct Outputs {
        meta: Vec<u8>,
        nodes: Vec<u8>,
        edges: Vec<u8>,
        lanes: Vec<u8>,
        names: Vec<u8>,
        inter: Vec<u8>,
    }

    fn read_outputs(dir: &Path) -> Outputs {
        let f = |n: &str| std::fs::read(dir.join(n)).unwrap();
        Outputs {
            meta: f("metadata.bin"),
            nodes: f("nodes.bin"),
            edges: f("edges.bin"),
            lanes: f("lanes.bin"),
            names: f("road_names.bin"),
            inter: f("intermediate.bin"),
        }
    }

    fn node_at(o: &Outputs, i: usize) -> (i32, i32, u64) {
        let b = &o.nodes[i * 12..i * 12 + 12];
        (
            i32::from_le_bytes(b[0..4].try_into().unwrap()),
            i32::from_le_bytes(b[4..8].try_into().unwrap()),
            u64::from(u32::from_le_bytes(b[8..12].try_into().unwrap())),
        )
    }

    /// A `u64` field of `metadata.bin` at byte `at`.
    fn meta_u64(o: &Outputs, at: usize) -> u64 {
        u64::from_le_bytes(o.meta[at..at + 8].try_into().unwrap())
    }

    /// `edges.bin`, located and decoded exactly as `graph.rs::load` does: the record
    /// array sized from `edge_count`, padding to the section boundary, the escape
    /// block index and the rows.
    ///
    /// Every accessor below is a literal mirror of the device's, which is what makes
    /// these contract tests rather than a re-read of our own tables.
    struct Edges<'a> {
        bytes: &'a [u8],
        edge_count: u64,
        first_at: usize,
        rows_at: usize,
        /// The name presence bitmap's rank index, then the bitmap, then the offsets.
        name_rank_at: usize,
        name_present_at: usize,
        name_off_at: usize,
        named_edges: u64,
    }

    impl<'a> Edges<'a> {
        fn new(
            bytes: &'a [u8],
            edge_count: u64,
            escape_count: u64,
            named_edges: u64,
        ) -> Edges<'a> {
            let first_at = align_up(edge_count * EDGE_REC_BYTES, SECTION_ALIGN) as usize;
            let blocks = edge_count.div_ceil(ESCAPE_BLOCK) + 1;
            let rows_at = first_at + (blocks * 4) as usize;
            let name_rank_at =
                align_up(rows_at as u64 + escape_count * 12, SECTION_ALIGN) as usize;
            let rank_bytes = ((edge_count.div_ceil(RANK_BLOCK_BITS) + 1) * 8) as usize;
            Edges {
                bytes,
                edge_count,
                first_at,
                rows_at,
                name_rank_at,
                name_present_at: name_rank_at + rank_bytes,
                name_off_at: name_rank_at + PresenceBitmap::bytes(edge_count) as usize,
                named_edges,
            }
        }

        fn of(o: &'a Outputs) -> Edges<'a> {
            Edges::new(&o.edges, meta_u64(o, 16), meta_u64(o, 24), meta_u64(o, 32))
        }

        /// What the reader will compute the file's length to be.
        fn total_bytes(&self) -> usize {
            self.name_off_at + (self.named_edges * 4) as usize
        }

        /// True when edge `idx` has a name, from the presence bitmap.
        fn named(&self, idx: u64) -> bool {
            self.bytes[self.name_present_at + (idx / 8) as usize] & (1u8 << (idx % 8)) != 0
        }

        /// Edge `idx`'s rank in the name bitmap, computed as `graph.rs` does: the
        /// block's stored count plus a popcount of the bytes below it.
        fn name_rank(&self, idx: u64) -> u64 {
            let byte = (idx / 8) as usize;
            let block = byte / RANK_BLOCK_BYTES;
            let at = self.name_rank_at + block * 8;
            let mut n = u64::from_le_bytes(self.bytes[at..at + 8].try_into().unwrap());
            for b in block * RANK_BLOCK_BYTES..byte {
                n += u64::from(self.bytes[self.name_present_at + b].count_ones());
            }
            let partial = self.bytes[self.name_present_at + byte] & ((1u8 << (idx % 8)) - 1);
            n + u64::from(partial.count_ones())
        }

        /// Edge `idx`'s name offset, or [`NO_NAME`] when it has none.
        fn name_offset(&self, idx: u64) -> u32 {
            if !self.named(idx) {
                return NO_NAME;
            }
            let at = self.name_off_at + (self.name_rank(idx) * 4) as usize;
            u32::from_le_bytes(self.bytes[at..at + 4].try_into().unwrap())
        }

        fn escape_first(&self, block: u64) -> u32 {
            let at = self.first_at + (block * 4) as usize;
            u32::from_le_bytes(self.bytes[at..at + 4].try_into().unwrap())
        }

        /// `(edge_idx, target, dist_mm)` of escape row `r`.
        fn row(&self, r: u64) -> (u32, u32, u32) {
            let b = &self.bytes[self.rows_at + (r * 12) as usize..];
            (
                u32::from_le_bytes(b[0..4].try_into().unwrap()),
                u32::from_le_bytes(b[4..8].try_into().unwrap()),
                u32::from_le_bytes(b[8..12].try_into().unwrap()),
            )
        }

        /// `(target, dist_mm, name_offset, type_, speed_limit)` of edge `idx`, whose
        /// source is `source` — decoded as `Graph::edge` and
        /// `Graph::edge_name_offset` do, escape table and name bitmap included.
        fn get(&self, source: u32, idx: u64) -> (u32, u32, u32, u8, u8) {
            assert!(idx < self.edge_count, "edge {idx} is past the pack");
            let b = &self.bytes[(idx * EDGE_REC_BYTES) as usize..];
            let delta = i16::from_le_bytes([b[0], b[1]]);
            let dist = u32::from_le_bytes([b[2], b[3], b[4], 0]);
            let (type_, speed) = (b[5], b[6]);
            let name = self.name_offset(idx);
            if delta == TARGET_DELTA_ESCAPE || dist == DIST_MM_ESCAPE {
                let block = idx / ESCAPE_BLOCK;
                for r in self.escape_first(block)..self.escape_first(block + 1) {
                    let (row_idx, target, dist_mm) = self.row(u64::from(r));
                    if u64::from(row_idx) == idx {
                        return (target, dist_mm, name, type_, speed);
                    }
                }
                panic!("edge {idx} carries a sentinel but has no escape row");
            }
            (source.wrapping_add_signed(i32::from(delta)), dist, name, type_, speed)
        }
    }

    /// The edge's source: the **largest** node index whose `edge_ptr <= idx`, which
    /// is how `find_node_idx_for_edge` defines it. Taking the first such node instead
    /// would land on an empty range wherever degree-0 nodes share an `edge_ptr`.
    fn source_of(o: &Outputs, idx: u64) -> u32 {
        (0..node_count(o))
            .rev()
            .find(|v| node_at(o, *v).2 <= idx)
            .unwrap_or(0) as u32
    }

    /// `(target, dist_mm, name_offset, type_, speed_limit)` of edge `i`, with the
    /// source recovered from `nodes.bin`. Keeps every existing caller unchanged
    /// across the delta encoding, and exercises the source recovery as a side effect.
    fn edge_at(o: &Outputs, i: usize) -> (u32, u32, u32, u8, u8) {
        Edges::of(o).get(source_of(o, i as u64), i as u64)
    }

    fn name_at(o: &Outputs, off: u32) -> String {
        let start = off as usize;
        let end = start + o.names[start..].iter().position(|b| *b == 0).unwrap();
        String::from_utf8(o.names[start..end].to_vec()).unwrap()
    }

    fn edge_count(o: &Outputs) -> u64 {
        meta_u64(o, 16)
    }

    fn node_count(o: &Outputs) -> usize {
        o.nodes.len() / 12 - 1
    }

    /// `intermediate.bin`, located exactly as `graph.rs::load` does: `G` from the
    /// last 8 bytes, the four trailer tables sized from `G` and the edge count, and
    /// the blob as whatever is left over at offset 0.
    ///
    /// Every accessor below is a literal mirror of the device's, which is what makes
    /// these contract tests rather than a re-read of our own tables.
    struct Inter<'a> {
        bytes: &'a [u8],
        geometry_edges: u64,
        blob_bytes: usize,
        rank_at: usize,
        present_at: usize,
        coarse_at: usize,
        within_at: usize,
    }

    impl<'a> Inter<'a> {
        fn new(bytes: &'a [u8], edge_count: u64) -> Inter<'a> {
            let len = bytes.len();
            let geometry_edges = u64::from_le_bytes(bytes[len - 8..].try_into().unwrap());
            let rank_bytes = ((edge_count.div_ceil(RANK_BLOCK_BITS) + 1) * 8) as usize;
            let present_bytes = edge_count.div_ceil(8) as usize;
            let coarse_bytes =
                ((geometry_edges.div_ceil(INTERMEDIATE_BLOCK) + 1) * 8) as usize;
            let within_bytes = ((geometry_edges + 1) * 2) as usize;
            let blob_bytes =
                len - (rank_bytes + present_bytes + coarse_bytes + within_bytes + 8);
            Inter {
                bytes,
                geometry_edges,
                blob_bytes,
                rank_at: blob_bytes,
                present_at: blob_bytes + rank_bytes,
                coarse_at: blob_bytes + rank_bytes + present_bytes,
                within_at: blob_bytes + rank_bytes + present_bytes + coarse_bytes,
            }
        }

        /// Whether edge `i` stores a polyline of its own.
        fn present(&self, i: u64) -> bool {
            self.bytes[self.present_at + (i / 8) as usize] & (1u8 << (i % 8)) != 0
        }

        /// Set bits below `i`: the rank block, then a `popcount` of the whole bytes
        /// after it and of the partial byte `i` falls in.
        fn rank(&self, i: u64) -> u64 {
            let byte = (i / 8) as usize;
            let block = byte / RANK_BLOCK_BYTES;
            let at = self.rank_at + block * 8;
            let mut n = u64::from_le_bytes(self.bytes[at..at + 8].try_into().unwrap());
            for b in block * RANK_BLOCK_BYTES..byte {
                n += u64::from(self.bytes[self.present_at + b].count_ones());
            }
            let partial = self.bytes[self.present_at + byte] & ((1u8 << (i % 8)) - 1);
            n + u64::from(partial.count_ones())
        }

        /// Blob offset of the `g`-th geometry edge, reassembled from both levels.
        fn offset(&self, g: u64) -> u64 {
            let cb = self.coarse_at + (g / INTERMEDIATE_BLOCK) as usize * 8;
            let coarse = u64::from_le_bytes(self.bytes[cb..cb + 8].try_into().unwrap());
            let wb = self.within_at + g as usize * 2;
            let within = u16::from_le_bytes(self.bytes[wb..wb + 2].try_into().unwrap());
            coarse + u64::from(within)
        }

        /// Edge `i`'s stored polyline bytes, or `None` when it stores none — which
        /// is how the device spells "no geometry" now that a zero-length blob is a
        /// valid chord.
        fn blob(&self, i: u64) -> Option<&'a [u8]> {
            if !self.present(i) {
                return None;
            }
            let g = self.rank(i);
            let (s, e) = (self.offset(g) as usize, self.offset(g + 1) as usize);
            Some(&self.bytes[s..e])
        }
    }

    fn inter(o: &Outputs) -> Inter<'_> {
        Inter::new(&o.inter, edge_count(o))
    }

    /// `lanes.bin`'s sparse index as `(edge_idx, blob_byte_off)` pairs, the trailing
    /// sentinel included, plus the blob behind it.
    fn lane_index(o: &Outputs) -> (Vec<(u32, u32)>, Vec<u16>) {
        let n = u32::from_le_bytes(o.lanes[0..4].try_into().unwrap()) as usize;
        let index: Vec<(u32, u32)> = (0..=n)
            .map(|i| {
                let b = &o.lanes[4 + i * 8..4 + i * 8 + 8];
                (
                    u32::from_le_bytes(b[0..4].try_into().unwrap()),
                    u32::from_le_bytes(b[4..8].try_into().unwrap()),
                )
            })
            .collect();
        let blob = o.lanes[4 + (n + 1) * 8..]
            .chunks_exact(2)
            .map(|c| u16::from_le_bytes(c.try_into().unwrap()))
            .collect();
        (index, blob)
    }

    /// The device's `edge_lane_masks`: the same lower-bound binary search over the
    /// sparse index, so a divergence between the search and the file shows up here
    /// rather than on a phone.
    fn lane_masks(o: &Outputs, edge_idx: u64) -> Option<Vec<u16>> {
        let (index, blob) = lane_index(o);
        let n = index.len() - 1;
        let want = u32::try_from(edge_idx).ok()?;
        let mut lo = 0usize;
        let mut hi = n;
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            if index[mid].0 < want {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if lo >= n || index[lo].0 != want {
            return None;
        }
        let (start, end) = (index[lo].1 as usize / 2, index[lo + 1].1 as usize / 2);
        (end > start).then(|| blob[start..end].to_vec())
    }

    /// The local id whose coordinates match fixture node `osm_id`, or `None` if
    /// that node was collapsed away.
    fn local_of(o: &Outputs, osm_id: i64) -> Option<u32> {
        let (_, lat, lon) = testpbf::NODES.iter().find(|n| n.0 == osm_id).copied().unwrap();
        (0..node_count(o) as u32).find(|i| {
            let (l, g, _) = node_at(o, *i as usize);
            (l, g) == (lat, lon)
        })
    }

    /// Reimplementation of `graph.rs`'s `get_edge_coordinates`, reverse-geometry
    /// lookup included. The point of decoding it this way rather than reading our
    /// own chain table back is that it is the device's algorithm that has to
    /// agree with the file.
    fn edge_coords(o: &Outputs, edge_idx: u64) -> Option<(Vec<(i32, i32)>, bool)> {
        let (target, _, _, type_, _) = edge_at(o, edge_idx as usize);
        let t = inter(o);
        let pt = |n: u32| {
            let (lat, lon, _) = node_at(o, n as usize);
            (lat, lon)
        };
        // The blob is interior-only, so the two endpoints come from `nodes.bin`.
        let decode = |k: u64, from: u32, to: u32| -> Option<Vec<(i32, i32)>> {
            Some(geom::decode(t.blob(k)?, pt(from), pt(to)))
        };
        // Find the source by the same monotonic search the device uses.
        let u = (0..node_count(o) as u32)
            .rev()
            .find(|i| node_at(o, *i as usize).2 <= edge_idx)
            .unwrap();
        if type_ & REVERSE_GEOMETRY_FLAG != 0 {
            let s = node_at(o, target as usize).2;
            let e = node_at(o, target as usize + 1).2;
            for k in s..e {
                if edge_at(o, k as usize).0 == u {
                    // The twin runs `target -> u`, so it is seeded and terminated
                    // the other way round. `get_pt_at` is what flips it on device,
                    // so flip it here too and hand callers a source-to-target
                    // polyline either way.
                    let mut pts = decode(k, target, u)?;
                    pts.reverse();
                    return Some((pts, true));
                }
            }
            return None;
        }
        Some((decode(edge_idx, u, target)?, false))
    }

    #[test]
    fn rank_agrees_with_a_brute_force_popcount() {
        // Ids chosen to straddle byte and 512-bit block boundaries, since those
        // are the two places the three-term rank can be off by one.
        let ids: Vec<u64> = vec![
            0, 1, 7, 8, 9, 63, 64, 65, 511, 512, 513, 1023, 1024, 4095, 4096, 9999,
        ];
        let mut b = Bitset::new(*ids.last().unwrap());
        for id in &ids {
            assert!(b.set(*id), "id {id} listed twice");
        }
        let total = b.build_rank(*ids.last().unwrap());
        assert_eq!(total, ids.len() as u64);

        // A set bit's dense id is its position in the sorted set of set ids...
        for (want, id) in ids.iter().enumerate() {
            assert_eq!(b.dense(*id), want as u32, "id {id}");
        }
        // ...and for every id, set or not, the rank is the brute-force count of
        // set bits strictly below it.
        for id in 0..=*ids.last().unwrap() {
            let want = ids.iter().filter(|x| **x < id).count() as u32;
            assert_eq!(b.dense(id), want, "id {id}");
        }
    }

    #[test]
    fn an_empty_bitset_ranks_everything_to_zero() {
        let mut b = Bitset::new(1024);
        assert_eq!(b.build_rank(1024), 0);
        assert_eq!(b.dense(0), 0);
        assert_eq!(b.dense(1024), 0);
        assert!(!b.get(7));
    }

    #[test]
    fn setting_a_bit_twice_is_reported_once() {
        let mut b = Bitset::new(64);
        assert!(b.set(9));
        assert!(!b.set(9));
        assert!(b.get(9));
        assert_eq!(b.build_rank(64), 1);
    }

    #[test]
    fn synthetic_extract_produces_the_documented_layout() {
        let (pbf_path, dir) = testpbf::write_sample("graph_build");
        let stats = build(&pbf_path, &dir).unwrap();
        let o = read_outputs(&dir);

        // Nodes 1-4 come from the residential way, node 5 is the bus stop; the
        // cafe node (6) is on no routable way and carries no stop tag, so it is
        // not part of the graph at all. Node 3 is then collapsed: it has exactly
        // two neighbours (2 and 4) reached by two Main St segments that agree on
        // everything, so no route ever chooses anything there.
        assert_eq!(stats.raw_node_count, 5);
        assert_eq!(stats.node_count, 4);
        assert_eq!(local_of(&o, 3), None, "node 3 should have been collapsed");
        for id in [1, 2, 4, testpbf::STOP_NODE_ID] {
            assert!(local_of(&o, id).is_some(), "node {id} should have survived");
        }
        assert_eq!(o.meta, {
            let mut want = Vec::new();
            want.extend(0x4752_414Du32.to_le_bytes());
            want.extend(GRAPH_VERSION.to_le_bytes());
            want.extend(4u64.to_le_bytes());
            want.extend(stats.edge_count.to_le_bytes());
            want.extend(stats.escape_count.to_le_bytes());
            want.extend(stats.named_edges.to_le_bytes());
            want
        });

        // nodes.bin holds node_count + 1 12-byte records; edges.bin is the record
        // array padded to a section boundary, then the escape index and rows, then
        // the sparse name table — exactly the length the reader computes, not merely
        // a multiple of anything.
        assert_eq!(o.nodes.len(), 12 * 5);
        assert_eq!(o.edges.len(), Edges::of(&o).total_bytes());
        assert_eq!(stats.escape_count, 0, "no fixture edge is long enough to escape");
        // Main St runs 1-2 and 2-4 in both directions; the service road and the two
        // synthetic stop connectors carry no name.
        assert_eq!(stats.named_edges, 4);
        let edge_count = edge_count(&o);
        assert_eq!(edge_count, stats.edge_count);
        // Uncompacted this was 3 bidirectional segments + 1 oneway = 7 directed
        // edges. Collapsing node 3 merges two of the Main St pairs into one, so
        // 2 (1-2) + 2 (2-4 via 3) + 1 (service) + 2 synthetic stop edges.
        assert_eq!(stats.raw_edge_count, 7);
        assert_eq!(edge_count, 2 + 2 + 1 + 2);

        // The sentinel's edge_ptr is the edge count, and edge_ptr is monotonic.
        assert_eq!(node_at(&o, 4).2, edge_count);
        assert_eq!(node_at(&o, 0).2, 0);
        for i in 0..4 {
            assert!(node_at(&o, i).2 <= node_at(&o, i + 1).2);
        }

        // Nodes are Morton-ordered.
        let keys: Vec<u64> = (0..4)
            .map(|i| {
                let (lat, lon, _) = node_at(&o, i);
                spatial_from_e7(lat, lon)
            })
            .collect();
        assert!(keys.windows(2).all(|w| w[0] <= w[1]), "{keys:?}");

        // Every edge's source range agrees with the nodes.bin pointers, and the
        // whole edge set matches the fixture's geometry.
        // (source lat/lon, target lat/lon, dist_mm, type, speed_limit, name)
        type SeenEdge = (i32, i32, i32, i32, u32, u8, u8, String);
        let mut seen: Vec<SeenEdge> = Vec::new();
        for lid in 0..4usize {
            let (slat, slon, start) = node_at(&o, lid);
            let end = node_at(&o, lid + 1).2;
            for e in start..end {
                let (target, dist, name_off, type_, speed) = edge_at(&o, e as usize);
                let (tlat, tlon, _) = node_at(&o, target as usize);
                let name = if name_off == NO_NAME {
                    String::new()
                } else {
                    name_at(&o, name_off)
                };
                // The road class must be recoverable after masking the flag off;
                // `geometry.rs::ROAD_TYPE_MASK` is the device's half of this.
                seen.push((slat, slon, tlat, tlon, dist, type_ & !REVERSE_GEOMETRY_FLAG, speed, name));
            }
        }
        assert_eq!(seen.len(), edge_count as usize);

        // Main St: 2 segments each way now, type 7 (residential), no maxspeed.
        let main: Vec<_> = seen.iter().filter(|e| e.7 == "Main St").collect();
        assert_eq!(main.len(), 4);
        assert!(main.iter().all(|e| e.5 == 7 && e.6 == 0));
        // The service way is a oneway from node 4 to node 2, 30 mph -> 48 km/h.
        let service: Vec<_> = seen.iter().filter(|e| e.5 == 8).collect();
        assert_eq!(service.len(), 1);
        assert_eq!(service[0].6, 48);
        assert_eq!((service[0].0, service[0].1), (370_030_000, -1_220_000_000));
        assert_eq!((service[0].2, service[0].3), (370_010_000, -1_220_000_000));
        // The bus stop was reconnected both ways with the synthetic type/speed.
        let synth: Vec<_> = seen.iter().filter(|e| e.5 == 12).collect();
        assert_eq!(synth.len(), 2);
        assert!(synth.iter().all(|e| e.6 == 5 && e.7.is_empty()));
        assert_eq!(stats.reconnected_stops, 1);
        assert_eq!(stats.stops_already_connected, 0);
        assert_eq!(stats.stops_unreachable, 0);
        assert_eq!(stats.lcc_size, 3);

        // The collapsed pair carries the merged distance: node 2 -> node 4 is
        // two 10_000e7-unit hops, so twice the 1-2 edge's length.
        let n1 = local_of(&o, 1).unwrap();
        let n2 = local_of(&o, 2).unwrap();
        let n4 = local_of(&o, 4).unwrap();
        let dist_of = |from: u32, to: u32, ty: u8| -> u32 {
            let (_, _, s) = node_at(&o, from as usize);
            let e = node_at(&o, from as usize + 1).2;
            (s..e)
                .filter_map(|k| {
                    let (t, d, _, raw, _) = edge_at(&o, k as usize);
                    (t == to && raw & !REVERSE_GEOMETRY_FLAG == ty).then_some(d)
                })
                .next()
                .unwrap()
        };
        let short = dist_of(n1, n2, 7);
        let merged = dist_of(n2, n4, 7);
        assert!(
            merged.abs_diff(short * 2) <= 2,
            "merged {merged} should be about twice {short}"
        );

        // road_names.bin holds each unique string once. "Main St" is the only way
        // name; "Test Stop" is the bus stop's code. "Plaza" belongs to an
        // unrouted area and must not appear.
        assert_eq!(o.names, b"Main St\0Test Stop\0".to_vec());
        assert_eq!(stats.unique_names, 2);
        assert_eq!(stats.name_bytes, 18);

        // lanes.bin: [u32 n][(edge_idx, off) x (n + 1)][u16 blob]. Only Main St's
        // forward direction carries turn:lanes, and collapsing must not lose them:
        // the 1-2 edge plus the merged 2-4 edge. Every other edge is simply absent
        // from the index rather than owning an empty range.
        let (index, blob) = lane_index(&o);
        assert_eq!(index.len(), 3, "two lane-bearing edges plus the sentinel");
        assert!(
            index[..2].windows(2).all(|w| w[0].0 < w[1].0),
            "index entries must ascend by edge index: {index:?}"
        );
        assert_eq!(index[2].0, u32::MAX, "the sentinel is not a real edge index");
        assert_eq!(index[2].1 as usize, blob.len() * 2, "the sentinel is the blob length");
        assert_eq!(o.lanes.len(), 4 + 3 * 8 + blob.len() * 2);
        // Two forward Main St edges now instead of three, two lane masks each.
        assert_eq!(blob, [[LANE_LEFT, LANE_THROUGH]; 2].concat());
        // Both real entries resolve, which with two of them is the first and the
        // last the binary search can land on.
        for (edge_idx, _) in &index[..2] {
            assert_eq!(
                lane_masks(&o, u64::from(*edge_idx)),
                Some(vec![LANE_LEFT, LANE_THROUGH])
            );
        }
        // Every edge not in the index reads as "no lanes", which is the state
        // `routing.rs`'s topology fallback already handles.
        let listed: Vec<u32> = index[..2].iter().map(|(e, _)| *e).collect();
        for k in 0..edge_count {
            if !listed.contains(&(k as u32)) {
                assert_eq!(lane_masks(&o, k), None, "edge {k} should carry no lanes");
            }
        }
    }

    #[test]
    fn intermediate_bin_reproduces_the_collapsed_geometry() {
        let (pbf_path, dir) = testpbf::write_sample("graph_inter");
        let stats = build(&pbf_path, &dir).unwrap();
        let o = read_outputs(&dir);
        let edge_count = edge_count(&o);

        // Offsets reassemble monotonically from both levels, start at zero and end
        // at the blob length. The coarse entry for a block is the offset of that
        // block's first geometry edge, so every within-block value at g % 32 == 0
        // is zero. `G` itself is the trailer's last 8 bytes.
        let t = inter(&o);
        assert_eq!(t.geometry_edges, stats.geometry_edges);
        let offs: Vec<u64> = (0..=t.geometry_edges).map(|g| t.offset(g)).collect();
        assert_eq!(offs[0], 0);
        assert!(offs.windows(2).all(|w| w[0] <= w[1]), "{offs:?}");
        assert_eq!(
            *offs.last().unwrap(),
            t.blob_bytes as u64,
            "last offset must be the blob length"
        );
        for g in (0..=t.geometry_edges).step_by(INTERMEDIATE_BLOCK as usize) {
            let at = t.coarse_at + (g / INTERMEDIATE_BLOCK) as usize * 8;
            let coarse = u64::from_le_bytes(o.inter[at..at + 8].try_into().unwrap());
            assert_eq!(coarse, offs[g as usize], "coarse entry for the block at {g}");
        }
        // The presence bitmap and its rank index have to agree: an edge's rank is
        // the number of storing edges below it, and the total is `G`.
        let mut seen = 0u64;
        for k in 0..edge_count {
            assert_eq!(t.rank(k), seen, "rank at edge {k}");
            if t.present(k) {
                seen += 1;
            }
        }
        assert_eq!(seen, t.geometry_edges);
        assert_eq!(stats.intermediate_bytes, o.inter.len() as u64);

        // Every stored polyline must start at its edge's source and end at its
        // target, and obey the encoding limits.
        let mut with_geometry = 0u64;
        for k in 0..edge_count {
            let (target, _, _, _, _) = edge_at(&o, k as usize);
            let u = (0..node_count(&o) as u32)
                .rev()
                .find(|i| node_at(&o, *i as usize).2 <= k)
                .unwrap();
            let Some((pts, _)) = edge_coords(&o, k) else {
                continue;
            };
            with_geometry += 1;
            assert!(pts.len() <= geom::MAX_POINTS as usize);
            let (slat, slon, _) = node_at(&o, u as usize);
            let (tlat, tlon, _) = node_at(&o, target as usize);
            assert_eq!(pts[0], (slat, slon), "edge {k} geometry starts off its source");
            assert_eq!(
                pts[pts.len() - 1],
                (tlat, tlon),
                "edge {k} geometry ends off its target"
            );
        }

        // The two Main St edges across the collapsed node must pass through the
        // node that was removed. That is the whole claim of compaction: the
        // polyline is unchanged, only the node is gone.
        let (_, lat3, lon3) = testpbf::NODES.iter().find(|n| n.0 == 3).copied().unwrap();
        let through: Vec<_> = (0..edge_count)
            .filter_map(|k| edge_coords(&o, k))
            .filter(|(pts, _)| pts.contains(&(lat3, lon3)))
            .collect();
        assert_eq!(through.len(), 2, "both directions must retain node 3's vertex");
        for (pts, _) in &through {
            assert_eq!(pts.len(), 3);
        }
        assert_eq!(with_geometry, 2, "both directions of the merged pair resolve geometry");
        // Only one polyline is stored: the other direction defers to it. That is
        // what `REVERSE_GEOMETRY_FLAG` buys, and it halves the blob.
        assert_eq!(stats.geometry_edges, 1);
        assert_eq!(stats.reversed_edges, 1);
        let flagged: Vec<u64> = (0..edge_count)
            .filter(|k| edge_at(&o, *k as usize).3 & REVERSE_GEOMETRY_FLAG != 0)
            .collect();
        assert_eq!(flagged.len(), 1);
        // The flagged edge must be resolvable, which is only true because node 2
        // reaches node 4 exactly once. Node 4 reaches node 2 twice (the merged
        // Main St chain and the oneway service road), so had the chain been
        // oriented the other way the flag would have had to be suppressed.
        assert!(edge_coords(&o, flagged[0]).is_some());
    }

    /// A CSR over `kept` nodes from a list of directed edges.
    fn csr_of(kept: u32, edges: &[(u32, u32)]) -> Csr {
        let mut edge_ptr = vec![0u64; kept as usize + 1];
        for (s, _) in edges {
            edge_ptr[*s as usize + 1] += 1;
        }
        for i in 0..kept as usize {
            edge_ptr[i + 1] += edge_ptr[i];
        }
        let mut targets = vec![0u32; edges.len()];
        let mut cursor = edge_ptr.clone();
        for (s, t) in edges {
            targets[cursor[*s as usize] as usize] = *t;
            cursor[*s as usize] += 1;
        }
        for v in 0..kept as usize {
            targets[edge_ptr[v] as usize..edge_ptr[v + 1] as usize].sort_unstable();
        }
        Csr { edge_ptr, targets }
    }

    #[test]
    fn a_twin_is_unique_only_without_parallel_edges() {
        let csr = csr_of(3, &[(0, 1), (1, 0), (1, 2), (2, 1), (2, 1)]);
        // 1 -> 0 has exactly one twin (0 -> 1).
        assert!(twin_is_unique(&csr, &[], 1, 0));
        // 1 -> 2's twin side holds two parallel 2 -> 1 edges.
        assert!(!twin_is_unique(&csr, &[], 1, 2));
        // 2 -> 1's twin side has one 1 -> 2 edge.
        assert!(twin_is_unique(&csr, &[], 2, 1));
        // An absent twin is not unique either.
        assert!(!twin_is_unique(&csr, &[], 1, 1));
    }

    #[test]
    fn a_synthetic_connector_counts_towards_twin_uniqueness() {
        // The reader cannot tell a synthetic connector from a road, so a reversed
        // edge whose twin side also holds a connector back to its source would
        // resolve to whichever the scan met first. The connectors are not in the
        // CSR, so they have to be counted separately.
        let csr = csr_of(3, &[(0, 1), (1, 0)]);
        let synth = [
            Synth {
                source: 1,
                target: 0,
                dist_mm: 5,
            },
            Synth {
                source: 2,
                target: 0,
                dist_mm: 5,
            },
        ];
        // 0 -> 1's twin side now has the real 1 -> 0 *and* a connector 1 -> 0.
        assert!(!twin_is_unique(&csr, &synth, 0, 1));
        // 1 -> 0's twin side has only the real 0 -> 1; the connector points the
        // other way and must not be miscounted.
        assert!(twin_is_unique(&csr, &synth, 1, 0));
        // A node reachable only by a connector has exactly one edge back.
        assert!(twin_is_unique(&csr, &synth, 0, 2));
    }

    #[test]
    fn a_way_ref_to_a_node_absent_from_the_file_costs_only_its_own_pairs() {
        let (pbf_path, dir) = testpbf::write_dangling_sample("graph_dangling");
        let stats = build(&pbf_path, &dir).unwrap();
        let o = read_outputs(&dir);

        // The dangling ref is marked in the node bitset — marks come from way
        // refs — but no blob ever supplies its coordinates, so it must not reach
        // the graph at all.
        assert_eq!(stats.raw_node_count, 3);
        assert_eq!(node_count(&o), 3);

        // Every node in nodes.bin is one the file really defined. A phantom slot
        // would read (0, 0), which is a plausible-looking point in the Atlantic
        // and would corrupt distances and Morton keys without failing anything.
        let mut written: Vec<(i32, i32)> = (0..3)
            .map(|i| {
                let (lat, lon, _) = node_at(&o, i);
                (lat, lon)
            })
            .collect();
        written.sort();
        let mut want: Vec<(i32, i32)> = testpbf::DANGLING_NODES.iter().map(|n| (n.1, n.2)).collect();
        want.sort();
        assert_eq!(written, want);

        // The way is [1, 2, 999, 3], so the 1-2 pair resolves and both pairs
        // touching 999 do not: one bidirectional edge, and node 3 left isolated.
        assert_eq!(stats.raw_edge_count, 2);
        assert_eq!(stats.edge_count, 2);
        assert_eq!(stats.lcc_size, 2);
        for k in 0..stats.edge_count {
            let (target, _, _, type_, _) = edge_at(&o, k as usize);
            assert!((target as usize) < 3, "edge {k} targets a node that does not exist");
            assert_eq!(type_ & !REVERSE_GEOMETRY_FLAG, 7);
        }
    }

    #[test]
    fn two_runs_are_byte_identical() {
        let (pbf_path, dir_a) = testpbf::write_sample("graph_det_a");
        let (_, dir_b) = testpbf::write_sample("graph_det_b");
        build(&pbf_path, &dir_a).unwrap();
        build(&pbf_path, &dir_b).unwrap();
        for f in [
            "metadata.bin",
            "nodes.bin",
            "edges.bin",
            "lanes.bin",
            "road_names.bin",
            "intermediate.bin",
        ] {
            assert_eq!(
                std::fs::read(dir_a.join(f)).unwrap(),
                std::fs::read(dir_b.join(f)).unwrap(),
                "{f} differs between runs"
            );
        }
    }

    // ---- the within-way chain path ---------------------------------------

    fn within_opts() -> Options {
        Options {
            within_way_chains: true,
            ..Options::default()
        }
    }

    /// A line of nodes 0.001 degrees apart along 122°W, so no geometry in these
    /// shape tests is ever interpolated or split.
    fn line_nodes(ids: &[i64]) -> Vec<(i64, i32, i32)> {
        ids.iter()
            .map(|id| (*id, 370_000_000 + (*id as i32) * 10_000, -1_220_000_000))
            .collect()
    }

    /// Build `nodes`/`ways` both ways round and return `(legacy, within_way)`.
    fn both_paths(tag: &str, nodes: &[(i64, i32, i32)], ways: &[(i64, &[i64])]) -> (Stats, Stats) {
        let (pbf, dir) = testpbf::write_shape_sample(tag, nodes, ways);
        let legacy = build(&pbf, &dir.join("legacy")).unwrap();
        let within = build_with(
            &pbf,
            &dir.join("within"),
            within_opts(),
        )
        .unwrap();
        (legacy, within)
    }

    fn within_way(tag: &str, nodes: &[(i64, i32, i32)], ways: &[(i64, &[i64])]) -> (Stats, Outputs) {
        let (pbf, dir) = testpbf::write_shape_sample(tag, nodes, ways);
        let stats = build_with(
            &pbf,
            &dir,
            within_opts(),
        )
        .unwrap();
        (stats, read_outputs(&dir))
    }

    #[test]
    fn a_within_way_chain_collapses_a_plain_run() {
        let (stats, o) = within_way(
            "chain_run",
            &line_nodes(&[1, 2, 3, 4, 5]),
            &[(100, &[1, 2, 3, 4, 5])],
        );
        // Only the two ends survive, and the one chain becomes a bidirectional
        // pair carrying every interior vertex as geometry.
        assert_eq!(stats.raw_node_count, 5);
        assert_eq!(stats.node_count, 2);
        assert_eq!(stats.edge_count, 2);
        assert_eq!(stats.raw_edge_count, 8, "four segments, both directions");
        let (pts, _) = edge_coords(&o, 0).unwrap();
        assert_eq!(pts.len(), 5);
    }

    #[test]
    fn an_interior_node_that_another_way_ends_at_is_not_collapsed() {
        // Way 100's node 2 looks interior *within way 100*, but way 101 also ends
        // there, so globally it has three incidences and is a real junction. This
        // is the whole reason the degree count is global rather than per way: a
        // per-way count would read 2 here and collapse a T-junction away.
        let (stats, o) = within_way(
            "chain_tee",
            &line_nodes(&[1, 2, 3, 4]),
            &[(100, &[1, 2, 3]), (101, &[2, 4])],
        );
        assert_eq!(stats.node_count, 4, "nothing may collapse at a T-junction");
        // 1-2, 2-3 and 2-4, each both ways.
        assert_eq!(stats.edge_count, 6);
        for k in 0..stats.edge_count {
            assert!(edge_coords(&o, k).is_none(), "no chain here has an interior vertex");
        }
    }

    #[test]
    fn a_ring_way_closes_on_its_own_first_node() {
        // Every node on the ring has two incidences and none is special, so
        // `compact` has to hunt for an anchor to break it at. Walking positions
        // makes that free: the way's own first node is the boundary, and the walk
        // cannot loop because it is advancing through a finite ref list.
        let (stats, o) = within_way(
            "chain_ring",
            &line_nodes(&[1, 2, 3]),
            &[(100, &[1, 2, 3, 1])],
        );
        assert_eq!(stats.node_count, 1, "the ring keeps exactly one node");
        assert_eq!(stats.edge_count, 2, "a self-loop, both directions");
        for k in 0..stats.edge_count {
            let (target, _, _, _, _) = edge_at(&o, k as usize);
            assert_eq!(target, 0, "both edges close on the anchor");
            let (pts, _) = edge_coords(&o, k).unwrap();
            assert_eq!(pts.len(), 4, "the ring's shape is preserved as geometry");
            assert_eq!(pts[0], pts[3]);
        }
    }

    #[test]
    fn a_repeated_ref_is_skipped_without_breaking_the_run() {
        // `[1, 2, 2, 3]` is a mapping artefact, not a zero-length road at node 2.
        // Counting it would give node 2 four incidences and promote a genuine
        // pass-through into a junction, so both the degree pass and the walk must
        // reject it — and the walk must then still join 1-2 to 2-3.
        let nodes = line_nodes(&[1, 2, 3]);
        let ways: &[(i64, &[i64])] = &[(100, &[1, 2, 2, 3])];
        let (stats, o) = within_way("chain_dup", &nodes, ways);
        assert_eq!(stats.node_count, 2);
        assert_eq!(stats.edge_count, 2);
        let (pts, _) = edge_coords(&o, 0).unwrap();
        assert_eq!(pts.len(), 3, "node 2 survives as a vertex, not as a node");

        // The reference path asks the same question about a pair, so it must reach
        // the same answer. It used to emit a zero-length self-loop here instead.
        let (pbf, dir) = testpbf::write_shape_sample("chain_dup_ref", &nodes, ways);
        let a = dir.join("legacy");
        let b = dir.join("within");
        build(&pbf, &a).unwrap();
        build_with(&pbf, &b, within_opts()).unwrap();
        for f in ["nodes.bin", "edges.bin", "intermediate.bin"] {
            assert_eq!(
                std::fs::read(a.join(f)).unwrap(),
                std::fs::read(b.join(f)).unwrap(),
                "{f} differs on a repeated ref"
            );
        }
    }

    #[test]
    fn a_self_touching_way_cuts_at_the_node_it_revisits() {
        // `[1, 2, 3, 2, 4]` visits node 2 twice, giving it four incidences. The
        // positional walk cuts there both times without needing to notice that it
        // has been there before.
        let (stats, _) = within_way(
            "chain_touch",
            &line_nodes(&[1, 2, 3, 4]),
            &[(100, &[1, 2, 3, 2, 4])],
        );
        assert_eq!(stats.node_count, 3, "node 3 folds into the 2 -> 3 -> 2 loop");
        // 1-2 and 2-4 both ways, plus the self-loop at 2 both ways.
        assert_eq!(stats.edge_count, 6);
    }

    #[test]
    fn a_gap_left_by_a_dangling_ref_ends_the_run_it_interrupts() {
        // Node 999 is referenced but never defined, so 2-999 and 999-3 are not
        // segments. The run 1-2 must be closed and a new one started at 3, not
        // silently joined across the gap.
        let (pbf, dir) = testpbf::write_shape_sample(
            "chain_gap",
            &line_nodes(&[1, 2, 3, 4]),
            &[(100, &[1, 2, 999, 3, 4])],
        );
        let stats = build_with(
            &pbf,
            &dir,
            within_opts(),
        )
        .unwrap();
        assert_eq!(stats.raw_node_count, 4, "999 never becomes a node");
        assert_eq!(stats.node_count, 4, "1-2 and 3-4 are two separate chains");
        assert_eq!(stats.edge_count, 4);
    }

    #[test]
    fn within_way_chains_keep_the_node_where_two_ways_meet() {
        // The accepted regression, stated as a test rather than as an estimate.
        // Two residential ways meet end-to-end at node 3 and agree on every
        // attribute, so `compact` folds node 3 into one five-point chain. Chains
        // confined to a single way cannot, and node 3 stays.
        let (legacy, within) = both_paths(
            "chain_crossway",
            &line_nodes(&[1, 2, 3, 4, 5]),
            &[(100, &[1, 2, 3]), (101, &[3, 4, 5])],
        );
        assert_eq!(legacy.node_count, 2);
        assert_eq!(legacy.edge_count, 2);
        assert_eq!(within.node_count, 3, "node 3 is the cost of within-way chains");
        assert_eq!(within.edge_count, 4);
        // Both keep every original vertex, so nothing about the road's drawn shape
        // changes: the extra node is a routing cost, not a geometry loss.
        assert_eq!(legacy.raw_node_count, within.raw_node_count);
        assert_eq!(legacy.raw_edge_count, within.raw_edge_count);
    }

    #[test]
    fn the_within_way_path_agrees_with_the_reference_path_on_a_single_way() {
        // With one way there is no cross-way merge to lose, so the two paths must
        // produce byte-identical output. That is what makes the comparison above a
        // measurement of the regression rather than of an unrelated difference.
        let nodes = line_nodes(&[1, 2, 3, 4, 5, 6]);
        let ways: &[(i64, &[i64])] = &[(100, &[1, 2, 3, 4, 5, 6])];
        let (pbf, dir) = testpbf::write_shape_sample("chain_same", &nodes, ways);
        let a = dir.join("legacy");
        let b = dir.join("within");
        build(&pbf, &a).unwrap();
        build_with(
            &pbf,
            &b,
            within_opts(),
        )
        .unwrap();
        for f in [
            "metadata.bin",
            "nodes.bin",
            "edges.bin",
            "lanes.bin",
            "road_names.bin",
            "intermediate.bin",
        ] {
            assert_eq!(
                std::fs::read(a.join(f)).unwrap(),
                std::fs::read(b.join(f)).unwrap(),
                "{f} differs between the two collapse paths"
            );
        }
    }

    #[test]
    fn the_shared_fixture_is_byte_identical_on_both_paths() {
        // Nothing in this fixture can be merged across a way boundary: node 4 is
        // an endpoint of both Main St and the service road, and node 2 has three
        // incidences. So the two paths must agree byte for byte here — which makes
        // this a check on one-way orientation, lane masks, reverse-geometry flags
        // and stop reconnection all at once, since a within-way walk reaches all of
        // them by different code than `compact` does.
        let (pbf_path, dir) = testpbf::write_sample("chain_fixture");
        let a = dir.join("legacy");
        let b = dir.join("within");
        let legacy = build(&pbf_path, &a).unwrap();
        let within = build_with(
            &pbf_path,
            &b,
            within_opts(),
        )
        .unwrap();
        for f in [
            "metadata.bin",
            "nodes.bin",
            "edges.bin",
            "lanes.bin",
            "road_names.bin",
            "intermediate.bin",
        ] {
            assert_eq!(
                std::fs::read(a.join(f)).unwrap(),
                std::fs::read(b.join(f)).unwrap(),
                "{f} differs between the two collapse paths"
            );
        }
        assert_eq!(legacy.node_count, within.node_count);
        assert_eq!(legacy.raw_edge_count, within.raw_edge_count);
        assert_eq!(legacy.geometry_edges, within.geometry_edges);
        assert_eq!(legacy.reversed_edges, within.reversed_edges);
        assert_eq!(within.reconnected_stops, 1);
        let o = read_outputs(&b);
        assert_eq!(local_of(&o, 3), None, "node 3 is interior to Main St");
        assert_eq!(o.names, b"Main St\0Test Stop\0".to_vec());
    }

    #[test]
    fn two_within_way_runs_are_byte_identical() {
        let (pbf_path, dir_a) = testpbf::write_sample("chain_det_a");
        let (_, dir_b) = testpbf::write_sample("chain_det_b");
        let opts = within_opts();
        build_with(&pbf_path, &dir_a, opts.clone()).unwrap();
        build_with(&pbf_path, &dir_b, opts).unwrap();
        for f in [
            "metadata.bin",
            "nodes.bin",
            "edges.bin",
            "lanes.bin",
            "road_names.bin",
            "intermediate.bin",
        ] {
            assert_eq!(
                std::fs::read(dir_a.join(f)).unwrap(),
                std::fs::read(dir_b.join(f)).unwrap(),
                "{f} differs between runs"
            );
        }
    }

    #[test]
    fn a_file_with_no_routable_ways_produces_an_empty_graph() {
        // Nothing marks the node bitset, so the dense address space is empty and
        // there is no largest marked id to size a rank index against. Every count
        // has to come out zero rather than underflowing on the way there.
        let (pbf, dir) = testpbf::write_shape_sample("empty_graph", &line_nodes(&[1, 2, 3]), &[]);
        for opts in [Options::default(), within_opts()] {
            let out = dir.join(format!("w{}", opts.within_way_chains));
            let stats = build_with(&pbf, &out, opts).unwrap();
            assert_eq!(stats.raw_node_count, 0);
            assert_eq!(stats.node_count, 0);
            assert_eq!(stats.edge_count, 0);
            assert_eq!(stats.lcc_size, 0);
            let o = read_outputs(&out);
            // The smallest tables `graph.rs` can read zero edges through: one rank
            // entry, no presence bytes at all, one coarse entry, one within-block
            // entry and the `u64 G` trailer; plus `nodes.bin`'s trailing sentinel and
            // a lane index holding only its own header and sentinel.
            assert_eq!(o.nodes.len(), 12);
            // `edges.bin` is not empty even with no edges: the escape block index and
            // the name rank index each carry their final entry, which are the totals
            // the reader cross-checks `escape_count` and `named_edges` against. Four
            // bytes of "no escapes", padding, then eight of "no names".
            assert_eq!(o.edges.len() as u64, EdgeFile::total_bytes(0, 0, 0));
            assert_eq!(o.edges.len(), 4 + 4 + 8);
            assert!(o.edges.iter().all(|b| *b == 0), "every count is zero");
            assert_eq!(stats.escape_count, 0);
            assert_eq!(stats.named_edges, 0);
            assert_eq!(o.inter.len(), 8 + 8 + 2 + 8, "rank, coarse, within, G");
            assert_eq!(o.inter.len() as u64, GeomFile::trailer_bytes(0, 0));
            assert_eq!(o.lanes.len(), 4 + 8);
            assert_eq!(lane_index(&o).0, vec![(u32::MAX, 0)]);
            assert!(o.names.is_empty());
        }
    }

    // ---------------------------------------------------------------------
    // edges.bin narrowing: the boundaries no real extract contains
    // ---------------------------------------------------------------------

    fn tmp_edge(source: u32, target: u32, dist_mm: u32) -> TmpEdge {
        TmpEdge {
            source,
            target,
            dist_mm,
            name_offset: NO_NAME,
            type_: 1,
            speed_limit: 50,
            lane_off: NO_LANES,
            lane_count: 0,
            chain: NO_CHAIN,
            chain_rev: false,
            pts_start: 0,
            pts_len: 0,
        }
    }

    /// Push `spec` — `(source, target, dist_mm)` — through the real [`EdgeFile`] and
    /// read every record back through the mirror of `graph.rs`'s decode.
    ///
    /// The delta encoding's boundaries are unreachable from a PBF fixture: putting
    /// two nodes exactly 32,768 apart in Morton order would mean choosing
    /// coordinates for 32,767 nodes in between. So the encoder and the decoder are
    /// exercised as a pair directly, which is also where the off-by-one would be.
    fn roundtrip_edges(
        tag: &str,
        spec: &[(u32, u32, u32)],
    ) -> (Vec<(u32, u32, u32, u8, u8)>, Vec<u8>, u64) {
        let dir = std::env::temp_dir().join(format!("osm_ingest_edges_{tag}"));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let count = spec.len() as u64;
        let mut f = EdgeFile::create(&dir, "edges.bin", count).unwrap();
        for (source, target, dist) in spec {
            f.push(&tmp_edge(*source, *target, *dist), 1).unwrap();
        }
        let escapes = f.escapes.len() as u64;
        let named = f.name_offsets.len() as u64;
        let total = f.finish(count).unwrap();
        let bytes = std::fs::read(dir.join("edges.bin")).unwrap();
        assert_eq!(bytes.len() as u64, total, "finish() lied about the length");
        assert_eq!(total, EdgeFile::total_bytes(count, escapes, named));
        let e = Edges::new(&bytes, count, escapes, named);
        // The block index's last entry is the row total, which is what the reader
        // cross-checks `escape_count` against.
        let blocks = count.div_ceil(ESCAPE_BLOCK) + 1;
        assert_eq!(
            u64::from(e.escape_first(blocks - 1)),
            escapes,
            "the block index total disagrees with the row count"
        );
        let decoded = spec
            .iter()
            .enumerate()
            .map(|(i, (source, _, _))| e.get(*source, i as u64))
            .collect();
        (decoded, bytes, escapes)
    }

    #[test]
    fn every_target_delta_boundary_round_trips() {
        // Far enough from zero that every delta below is a representable u32 target.
        let source = 1_000_000u32;
        let deltas: [i64; 11] = [0, 1, -1, 32766, -32766, 32767, -32767, 32768, -32768, -32769, 40000];
        let spec: Vec<(u32, u32, u32)> = deltas
            .iter()
            .map(|d| (source, (i64::from(source) + d) as u32, 1234))
            .collect();
        let (decoded, _, escapes) = roundtrip_edges("deltas", &spec);
        for (i, d) in deltas.iter().enumerate() {
            assert_eq!(decoded[i].0, spec[i].1, "delta {d} did not round-trip");
            assert_eq!(decoded[i].1, 1234, "delta {d} disturbed dist_mm");
        }
        // ±32767 fit. ±32768, −32769 and +40000 do not — and −32768 is `i16::MIN`,
        // which is the sentinel, so it escapes even though its magnitude would fit.
        // That reservation is what keeps the representable range symmetric.
        assert_eq!(escapes, 4, "expected 32768, -32768, -32769 and 40000 to escape");
    }

    #[test]
    fn every_dist_mm_boundary_round_trips() {
        let source = 5u32;
        let dists = [0u32, 1, 0xFF_FFFE, 0xFF_FFFF, 0x100_0000, u32::MAX];
        let spec: Vec<(u32, u32, u32)> =
            dists.iter().map(|d| (source, source + 1, *d)).collect();
        let (decoded, _, escapes) = roundtrip_edges("dists", &spec);
        for (i, d) in dists.iter().enumerate() {
            assert_eq!(decoded[i].1, *d, "dist {d} did not round-trip");
            assert_eq!(decoded[i].0, source + 1, "dist {d} disturbed the target");
        }
        // 0xFFFFFE is the largest a u24 can hold and be a value; 0xFFFFFF is the
        // sentinel, so it escapes despite fitting. The escape keeps every distance
        // *exact* — the alternative considered was centimetres, which needs no table
        // but quantises all 1.07 G edges.
        assert_eq!(escapes, 3);
    }

    #[test]
    fn an_edge_that_fails_both_tests_is_one_row_not_two() {
        let spec = vec![(0u32, 500_000u32, 0x200_0000u32)];
        let (decoded, _, escapes) = roundtrip_edges("both", &spec);
        assert_eq!(escapes, 1, "one row serves both fields");
        assert_eq!(decoded[0].0, 500_000);
        assert_eq!(decoded[0].1, 0x200_0000);
    }

    #[test]
    fn an_escape_at_either_end_of_the_pack_is_found() {
        // The block index is a prefix-count array, so the first and last edge are
        // where an off-by-one in it shows up.
        let far = 200_000u32;
        let spec = vec![
            (far, 0, 10),
            (far, far + 1, 20),
            (far, far + 2, 30),
            (far, 0, 40),
        ];
        let (decoded, _, escapes) = roundtrip_edges("ends", &spec);
        assert_eq!(escapes, 2);
        let got: Vec<(u32, u32)> = decoded.iter().map(|d| (d.0, d.1)).collect();
        assert_eq!(got, vec![(0, 10), (far + 1, 20), (far + 2, 30), (0, 40)]);
    }

    #[test]
    fn a_pack_with_no_escapes_is_records_padding_and_an_index_sentinel() {
        let spec: Vec<(u32, u32, u32)> = (0..100u32).map(|i| (1000, 1000 + i, i)).collect();
        let (decoded, bytes, escapes) = roundtrip_edges("no_escapes", &spec);
        assert_eq!(escapes, 0);
        // Only padding separates the record array from the block index, so it is the
        // only thing keeping a wide load of the last record in bounds.
        let records = 100 * EDGE_REC_BYTES;
        let pad = align_up(records, SECTION_ALIGN) - records;
        assert_eq!(pad, 4, "100 x 7 = 700, which is 4 short of a multiple of 8");
        assert!(
            bytes[records as usize..(records + pad) as usize].iter().all(|b| *b == 0),
            "the padding must be zeroed, not whatever the buffer held"
        );
        assert_eq!(bytes.len() as u64, EdgeFile::total_bytes(100, 0, 0));
        for (i, d) in decoded.iter().enumerate() {
            assert_eq!((d.0, d.1), (1000 + i as u32, i as u32));
            assert_eq!(d.2, NO_NAME, "no fixture edge is named");
        }
    }

    #[test]
    fn escape_rows_spanning_block_boundaries_are_all_found() {
        // Every side of every block boundary a 1024-edge block has, plus both ends.
        let far = 500_000u32;
        let n = 3000u32;
        let escaping = [0u32, 1023, 1024, 1025, 2047, 2048, 2049, n - 1];
        let spec: Vec<(u32, u32, u32)> = (0..n)
            .map(|i| {
                // An escaping edge points a long way back; the rest are a short
                // forward delta.
                let target = if escaping.contains(&i) { i } else { far + i };
                (far, target, 1000 + i)
            })
            .collect();
        let (decoded, _, escapes) = roundtrip_edges("blocks", &spec);
        assert_eq!(escapes, escaping.len() as u64);
        for i in 0..n as usize {
            assert_eq!(
                (decoded[i].0, decoded[i].1),
                (spec[i].1, spec[i].2),
                "edge {i} decoded wrongly"
            );
        }
    }

    /// Named at edge 0 and E−1, unnamed either side of a rank-block boundary, and a
    /// name offset at both ends of the `u32` range.
    ///
    /// The rank index covers 512 edges an entry, so the boundary at 511/512 is where
    /// a rank computed from the wrong block would show up — and it is a boundary a
    /// small fixture never reaches.
    #[test]
    fn the_sparse_name_table_survives_every_boundary() {
        let dir = std::env::temp_dir().join("osm_ingest_edges_names");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let n = 1200u64;
        // Which edges get a name, and what offset. Deliberately includes both ends,
        // both sides of the 512-edge rank boundary and both sides of 1024.
        let named: Vec<u64> = vec![0, 1, 510, 511, 512, 513, 1022, 1023, 1024, 1025, n - 1];
        let mut f = EdgeFile::create(&dir, "edges.bin", n).unwrap();
        for i in 0..n {
            let mut e = tmp_edge(0, i as u32, 7);
            e.speed_limit = 90;
            if let Some(k) = named.iter().position(|x| *x == i) {
                // A distinct offset per named edge, so a rank off by one reads a
                // visibly wrong value rather than a plausible one.
                e.name_offset = (k as u32) * 17;
            }
            f.push(&e, REVERSE_GEOMETRY_FLAG | 3).unwrap();
        }
        let escapes = f.escapes.len() as u64;
        let named_count = f.name_offsets.len() as u64;
        assert_eq!(named_count, named.len() as u64);
        let total = f.finish(n).unwrap();
        let bytes = std::fs::read(dir.join("edges.bin")).unwrap();
        assert_eq!(bytes.len() as u64, total);
        let e = Edges::new(&bytes, n, escapes, named_count);
        for i in 0..n {
            let (target, dist, name, type_, speed) = e.get(0, i);
            assert_eq!(target, i as u32);
            assert_eq!(dist, 7);
            assert_eq!(type_, REVERSE_GEOMETRY_FLAG | 3, "edge {i}");
            assert_eq!(speed, 90, "edge {i}");
            let want = match named.iter().position(|x| *x == i) {
                Some(k) => (k as u32) * 17,
                None => NO_NAME,
            };
            assert_eq!(name, want, "edge {i}'s name offset");
        }
    }

    #[test]
    fn a_pack_where_no_edge_is_named_and_one_where_every_edge_is() {
        for all_named in [false, true] {
            let dir = std::env::temp_dir()
                .join(format!("osm_ingest_edges_named_{all_named}"));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).unwrap();
            let n = 600u64;
            let mut f = EdgeFile::create(&dir, "edges.bin", n).unwrap();
            for i in 0..n {
                let mut e = tmp_edge(0, i as u32, 1);
                if all_named {
                    e.name_offset = i as u32 * 3;
                }
                f.push(&e, 1).unwrap();
            }
            let named_count = f.name_offsets.len() as u64;
            assert_eq!(named_count, if all_named { n } else { 0 });
            let total = f.finish(n).unwrap();
            let bytes = std::fs::read(dir.join("edges.bin")).unwrap();
            assert_eq!(bytes.len() as u64, total);
            assert_eq!(total, EdgeFile::total_bytes(n, 0, named_count));
            let e = Edges::new(&bytes, n, 0, named_count);
            for i in 0..n {
                let want = if all_named { i as u32 * 3 } else { NO_NAME };
                assert_eq!(e.get(0, i).2, want, "edge {i} with all_named={all_named}");
            }
        }
    }

    #[test]
    fn the_round_count_does_not_change_a_single_output_byte() {
        // The strongest check on the rounds writer. Round boundaries move the split
        // between which edges each pass collects, which offsets each pass appends
        // and where `nodes.bin`'s running edge counter resumes, and none of that may
        // reach the file. 17 is prime, so with these node counts most of its rounds
        // are empty or hold a single node — exactly the boundaries an even split
        // would never exercise.
        let nodes = line_nodes(&[1, 2, 3, 4, 5, 6, 7, 8, 9]);
        let ways: &[(i64, &[i64])] = &[
            (100, &[1, 2, 3, 4]),
            (101, &[4, 5, 6]),
            (102, &[6, 7, 8, 9]),
            (103, &[3, 7]),
        ];
        let (pbf, dir) = testpbf::write_shape_sample("rounds_shape", &nodes, ways);
        let (stop_pbf, stop_dir) = testpbf::write_sample("rounds_fixture");

        for opts in [Options::default(), within_opts()] {
            for (input, base) in [(&pbf, &dir), (&stop_pbf, &stop_dir)] {
                let mut want: Option<Vec<Vec<u8>>> = None;
                for rounds in [1u32, 4, 17] {
                    let out = base.join(format!("r{rounds}_{}", opts.within_way_chains));
                    build_with(
                        input,
                        &out,
                        Options {
                            rounds,
                            ..opts.clone()
                        },
                    )
                    .unwrap();
                    let got: Vec<Vec<u8>> = [
                        "metadata.bin",
                        "nodes.bin",
                        "edges.bin",
                        "lanes.bin",
                        "road_names.bin",
                        "intermediate.bin",
                    ]
                    .iter()
                    .map(|f| std::fs::read(out.join(f)).unwrap())
                    .collect();
                    match &want {
                        None => want = Some(got),
                        Some(w) => assert_eq!(*w, got, "--rounds {rounds} changed the output"),
                    }
                    // The scratch blob must not survive into the pack.
                    assert!(!out.join("lanes.bin.blob").exists());
                    assert!(!out.join("chain_spill").exists(), "the spill was left behind");
                }
            }
        }
    }

    // ---- the v3 layouts at their boundaries -------------------------------

    #[test]
    fn a_block_of_maximal_polylines_cannot_escape_a_u16_within_offset() {
        // The arithmetic the two-level offset table rests on. A per-edge blob holds
        // only the interior points — 4 bytes each, at most `geom::MAX_POINTS - 2` of
        // them — so a block of `INTERMEDIATE_BLOCK` of them is the worst case the
        // u16 half has to hold. Keying the table on presence rank rather than on
        // edge index makes that bound tight rather than pessimistic: every entry in
        // a block is a real blob, where under v2 most were zero-length.
        let worst_edge = 4 * (u64::from(geom::MAX_POINTS) - 2);
        assert_eq!(worst_edge, 1016);
        assert!(
            INTERMEDIATE_BLOCK * worst_edge <= u64::from(u16::MAX),
            "a block of {INTERMEDIATE_BLOCK} maximal blobs is {} bytes",
            INTERMEDIATE_BLOCK * worst_edge
        );
    }

    #[test]
    fn the_presence_rank_agrees_with_a_brute_force_popcount() {
        // On-disk rank/select is a new primitive, so it is exercised on its own
        // rather than through a graph: ids straddling byte and 512-bit block
        // boundaries — the two places the three-term rank can be off by one — and an
        // edge count past a single rank block, which no fixture small enough to build
        // in a test would reach.
        const EDGES: u64 = 1500;
        let stored: Vec<u64> = vec![0, 1, 7, 8, 9, 63, 64, 65, 511, 512, 513, 1023, 1024, 1499];
        let dir = std::env::temp_dir().join("osm_ingest_present_rank");
        std::fs::create_dir_all(&dir).unwrap();
        let mut f = GeomFile::create(&dir, "intermediate.bin", EDGES).unwrap();
        for k in 0..EDGES {
            match stored.iter().position(|s| *s == k) {
                // A distinct length and content per stored edge, so an offset landing
                // on the wrong entry shows up rather than coinciding.
                Some(g) => f.store(&vec![g as u8; 4 * (g + 1)]).unwrap(),
                None => f.skip(),
            }
        }
        let size = f.finish(EDGES).unwrap();
        let bytes = std::fs::read(dir.join("intermediate.bin")).unwrap();
        assert_eq!(bytes.len() as u64, size);

        let t = Inter::new(&bytes, EDGES);
        assert_eq!(t.geometry_edges, stored.len() as u64);
        assert_eq!(t.blob_bytes, (1..=stored.len()).map(|n| 4 * n).sum::<usize>());
        for k in 0..EDGES {
            assert_eq!(t.present(k), stored.contains(&k), "presence bit {k}");
            // For every id, set or not, the rank is the brute-force count of set
            // bits strictly below it.
            let want = stored.iter().filter(|s| **s < k).count() as u64;
            assert_eq!(t.rank(k), want, "rank at {k}");
            if !stored.contains(&k) {
                assert_eq!(t.blob(k), None, "edge {k} stores nothing");
            }
        }
        // Each stored edge's blob is the one it wrote, found through that rank and
        // both offset levels.
        for (g, k) in stored.iter().enumerate() {
            assert_eq!(t.blob(*k), Some(&vec![g as u8; 4 * (g + 1)][..]), "blob at {k}");
        }
    }

    #[test]
    fn a_chain_at_the_256_point_limit_produces_the_largest_legal_blob() {
        // Exactly `geom::MAX_POINTS` vertices in one chain: the largest per-edge
        // blob the format allows, and so the worst case for the within-block u16.
        // Nodes 0.001 degrees apart, so nothing is interpolated and nothing splits.
        let ids: Vec<i64> = (1..=i64::from(geom::MAX_POINTS)).collect();
        let (stats, o) = within_way("inter_max_points", &line_nodes(&ids), &[(100, &ids)]);
        assert_eq!(stats.node_count, 2, "only the two ends of the run survive");
        assert_eq!(stats.chain_splits, 0, "256 points is exactly the budget");
        assert_eq!(stats.edge_count, 2);
        assert_eq!(stats.geometry_edges, 1, "the twin defers to the stored polyline");

        let t = inter(&o);
        let stored: Vec<u64> = (0..edge_count(&o)).filter(|k| t.present(*k)).collect();
        assert_eq!(stored.len(), 1);
        // Both endpoints are dropped, so 256 points cost 254 deltas.
        assert_eq!(
            t.blob(stored[0]).unwrap().len() as u64,
            4 * (u64::from(geom::MAX_POINTS) - 2)
        );
        assert_eq!(t.blob_bytes, 1016);
        // Both directions still decode the full polyline, one of them backwards.
        for k in 0..edge_count(&o) {
            let (pts, _) = edge_coords(&o, k).unwrap();
            assert_eq!(pts.len(), geom::MAX_POINTS as usize);
        }
    }

    #[test]
    fn the_two_level_offsets_cross_block_boundaries_exactly() {
        // 80 disjoint three-node ways: 80 chains, 160 directed edges and 80 stored
        // polylines, so the *geometry* indices the offset table is keyed on span
        // three coarse blocks and the two boundary cases — `g % 32 == 0` and
        // `g % 32 == 31` — both fall inside the graph rather than at its end, where
        // an off-by-one would be hidden by the sentinel.
        let mut nodes: Vec<(i64, i32, i32)> = Vec::new();
        let mut refs: Vec<Vec<i64>> = Vec::new();
        for w in 0..80i64 {
            let base = w * 10 + 1;
            for k in 0..3i64 {
                nodes.push((
                    base + k,
                    370_000_000 + (w as i32) * 100_000 + (k as i32) * 10_000,
                    -1_220_000_000,
                ));
            }
            refs.push(vec![base, base + 1, base + 2]);
        }
        let ways: Vec<(i64, &[i64])> = refs
            .iter()
            .enumerate()
            .map(|(i, r)| (100 + i as i64, r.as_slice()))
            .collect();
        let (_, o) = within_way("inter_blocks", &nodes, &ways);

        let e = edge_count(&o);
        assert_eq!(e, 160);
        let t = inter(&o);
        assert_eq!(t.geometry_edges, 80);
        assert!(
            t.geometry_edges > 2 * INTERMEDIATE_BLOCK,
            "the offset table must span three blocks"
        );

        // Each chain stores one 3-point polyline, which is a single interior delta,
        // and defers the other direction. So the offsets are a known sequence rather
        // than merely a self-consistent one, and exactly half the presence bits are
        // set.
        let offs: Vec<u64> = (0..=t.geometry_edges).map(|g| t.offset(g)).collect();
        assert!(offs.windows(2).all(|w| w[1] - w[0] == 4), "{offs:?}");
        assert_eq!(*offs.last().unwrap(), 80 * 4);
        assert_eq!((0..e).filter(|k| t.present(*k)).count(), 80);

        // The coarse entry for a block is its first geometry edge's absolute offset,
        // so the within-block value there is zero, and every offset in between
        // reassembles from the pair.
        let coarse = |b: u64| {
            let at = t.coarse_at + b as usize * 8;
            u64::from_le_bytes(o.inter[at..at + 8].try_into().unwrap())
        };
        let within = |g: u64| {
            let at = t.within_at + g as usize * 2;
            u16::from_le_bytes(o.inter[at..at + 2].try_into().unwrap())
        };
        for g in 0..=t.geometry_edges {
            assert_eq!(
                coarse(g / INTERMEDIATE_BLOCK) + u64::from(within(g)),
                offs[g as usize],
                "geometry edge {g} does not reassemble"
            );
            if g.is_multiple_of(INTERMEDIATE_BLOCK) {
                assert_eq!(within(g), 0, "a block's first entry starts at its coarse entry");
            }
        }
        // The whole file is the blob plus a trailer sized from `E` and `G`.
        assert_eq!(
            o.inter.len() as u64,
            *offs.last().unwrap() + GeomFile::trailer_bytes(e, t.geometry_edges)
        );
    }

    #[test]
    fn a_graph_where_every_edge_stores_geometry_and_one_where_none_does() {
        // The two ends of the presence bitmap. A ring collapsed to one node is a
        // self-loop both ways round, and a self-loop can never defer to a twin
        // (`twin_is_unique` refuses, since it would match itself), so both directions
        // store a polyline and every presence bit is set.
        let (all_stats, all) = within_way(
            "present_all",
            &line_nodes(&[1, 2, 3]),
            &[(100, &[1, 2, 3, 1])],
        );
        let t = inter(&all);
        assert_eq!(all_stats.edge_count, 2);
        assert_eq!(t.geometry_edges, 2);
        assert!((0..all_stats.edge_count).all(|k| t.present(k)));
        assert_eq!((t.rank(0), t.rank(1)), (0, 1));

        // A T-junction of two-node chains has no interior vertex anywhere, so nothing
        // is stored, the blob is empty and both offset levels hold only a sentinel.
        let (none_stats, none) = within_way(
            "present_none",
            &line_nodes(&[1, 2, 3, 4]),
            &[(100, &[1, 2]), (101, &[2, 3]), (102, &[2, 4])],
        );
        let t = inter(&none);
        assert_eq!(none_stats.edge_count, 6);
        assert_eq!(t.geometry_edges, 0);
        assert_eq!(t.blob_bytes, 0);
        assert!((0..none_stats.edge_count).all(|k| !t.present(k) && t.blob(k).is_none()));
        assert_eq!(t.offset(0), 0, "the sentinel is the empty blob's length");
        assert_eq!(
            none.inter.len() as u64,
            GeomFile::trailer_bytes(none_stats.edge_count, 0)
        );
    }

    #[test]
    #[should_panic(expected = "starts off its source node")]
    fn a_chain_that_starts_off_its_source_node_is_refused() {
        // Dropping the endpoints is only sound because a chain begins and ends on
        // its own nodes' coordinates. Feed the writer's check a mismatched chain to
        // prove it fires rather than encoding a polyline the reader would rebuild
        // wrongly.
        let coords = vec![(370_000_000, -1_220_000_000), (370_020_000, -1_220_000_000)];
        let chain = vec![(0, 0), (370_010_000, -1_220_000_000), coords[1]];
        assert_endpoints(&chain, &coords, 0, 1, 7);
    }

    #[test]
    #[should_panic(expected = "ends off its target node")]
    fn a_chain_that_ends_off_its_target_node_is_refused() {
        let coords = vec![(370_000_000, -1_220_000_000), (370_020_000, -1_220_000_000)];
        let chain = vec![coords[0], (370_010_000, -1_220_000_000), (0, 0)];
        assert_endpoints(&chain, &coords, 0, 1, 7);
    }

    #[test]
    fn a_graph_with_no_turn_lanes_has_an_empty_lane_index() {
        // The common case at planet scale: 99.7% of edges carry no `turn:lanes`, and
        // under the sparse layout they cost nothing at all rather than 8 bytes each.
        let (stats, o) = within_way(
            "lanes_none",
            &line_nodes(&[1, 2, 3, 4]),
            &[(100, &[1, 2, 3, 4])],
        );
        assert!(stats.edge_count > 0);
        let (index, blob) = lane_index(&o);
        assert_eq!(index, vec![(u32::MAX, 0)], "only the sentinel");
        assert!(blob.is_empty());
        assert_eq!(o.lanes.len(), 4 + 8);
        for k in 0..stats.edge_count {
            assert_eq!(lane_masks(&o, k), None);
        }
    }

    #[test]
    fn an_edge_count_past_the_u32_ceiling_is_refused() {
        // `nodes.bin`'s `edge_ptr` is a u32, so a wrapped edge count would point a
        // node at another node's edge range and the build would report success.
        assert_eq!(cap_u32("directed edge", u64::from(u32::MAX)).unwrap(), u32::MAX);
        let err = cap_u32("directed edge", u64::from(u32::MAX) + 1).unwrap_err();
        assert!(err.0.contains("directed edge"), "{}", err.0);
    }

    // ---- stop reconnection by component size ------------------------------

    /// Three road networks and a stop on each, plus a stop attached to nothing.
    ///
    /// This is the planet case stated at the smallest scale that can state it.
    /// Continents are not road-connected, so a planet's largest component is
    /// Eurasia-plus-Africa at 57.7% and the old "reconnect everything outside the
    /// largest component" rule tried to drag every American, Australian, Japanese,
    /// British and Indonesian stop across an ocean — failing 1,173,347 times. The
    /// rule is component *size*, so a stop already on a real road network is left
    /// alone whichever network that is.
    #[test]
    fn a_stop_on_any_routable_component_is_left_alone() {
        const A: u32 = 1200; // the largest component
        const B: u32 = 1000; // exactly MIN_ROUTABLE_COMPONENT, so still routable
        const C: u32 = 999; // one node short of it, so not
        let total = A + B + C + 1;

        // A is far west; B and C are adjacent in both index and space, so the
        // widening window around a stop in C reaches B before anything else.
        let mut coords: Vec<geom::Pt> = Vec::with_capacity(total as usize);
        for i in 0..A {
            coords.push((370_000_000 + i as i32 * 1000, -1_220_000_000));
        }
        for i in 0..B {
            coords.push((400_000_000 + i as i32 * 1000, -740_000_000));
        }
        for i in 0..C {
            coords.push((410_000_000 + i as i32 * 1000, -740_000_000));
        }
        // The stop attached to nothing sits beside B's far end.
        coords.push((400_999_500, -740_000_000));

        let mut edges: Vec<(u32, u32)> = Vec::new();
        let chain = |base: u32, len: u32, edges: &mut Vec<(u32, u32)>| {
            for i in 1..len {
                edges.push((base + i - 1, base + i));
                edges.push((base + i, base + i - 1));
            }
        };
        chain(0, A, &mut edges);
        chain(A, B, &mut edges);
        chain(A + B, C, &mut edges);
        let csr = csr_of(total, &edges);

        let mut stops = Bitset::new(u64::from(total));
        for s in [A / 2, A + B / 2, A + B + C / 2, A + B + C] {
            stops.set(u64::from(s));
        }

        let rec = reconnect_isolated_stops(&coords, &stops, &csr);
        assert_eq!(rec.lcc_size, u64::from(A));
        assert_eq!(
            rec.already_connected, 2,
            "the stops in A and in B are both already on a routable component"
        );
        assert_eq!(rec.unreachable, 0);
        // The stop in C and the stop attached to nothing, both connectors both ways.
        assert_eq!(rec.synth.len(), 4);
        for source in [A + B + C / 2, A + B + C] {
            let target = rec
                .synth
                .iter()
                .find(|s| s.source == source)
                .expect("every isolated stop gets a connector")
                .target;
            assert!(
                target < A + B,
                "stop {source} was connected to node {target}, which is in C"
            );
            assert_eq!(target, A + B - 1, "and to the nearest such node");
        }
        // Both directions of each connector are present, which is what makes the
        // stop reachable rather than merely reaching.
        for s in &rec.synth {
            assert!(
                rec.synth
                    .iter()
                    .any(|t| t.source == s.target && t.target == s.source),
                "connector {} -> {} has no return",
                s.source,
                s.target
            );
        }
    }

    #[test]
    fn the_routable_threshold_falls_back_to_the_largest_component() {
        // A graph whose largest component is under MIN_ROUTABLE_COMPONENT still has
        // a road network — it is just a small one. Requiring 1000 nodes there would
        // qualify nothing and reconnect nothing, so the threshold is capped at the
        // largest component that exists. Two nodes of road and a stop off to one
        // side: the stop must still find the road.
        let coords: Vec<geom::Pt> = vec![
            (370_000_000, -1_220_000_000),
            (370_010_000, -1_220_000_000),
            (380_000_000, -1_220_000_000),
        ];
        let csr = csr_of(3, &[(0, 1), (1, 0)]);
        let mut stops = Bitset::new(3);
        stops.set(2);

        let rec = reconnect_isolated_stops(&coords, &stops, &csr);
        assert_eq!(rec.lcc_size, 2);
        assert_eq!(rec.already_connected, 0);
        assert_eq!(rec.unreachable, 0);
        assert_eq!(rec.synth.len(), 2);
        assert_eq!(rec.synth[0].source, 1, "the nearest node of the only road");
        assert_eq!(rec.synth[0].target, 2);
    }

    #[test]
    fn args_default_the_output_directory() {
        let (input, out, opts) = parse_args(&["cal.osm.pbf".into()]).unwrap();
        assert_eq!(input, PathBuf::from("cal.osm.pbf"));
        assert_eq!(out, PathBuf::from("map_data"));
        assert!(!opts.within_way_chains, "the legacy path is the default");
        let (_, out, _) = parse_args(&["cal.osm.pbf".into(), "--out".into(), "d".into()]).unwrap();
        assert_eq!(out, PathBuf::from("d"));
        let (_, _, opts) =
            parse_args(&["cal.osm.pbf".into(), "--within-way-chains".into()]).unwrap();
        assert!(opts.within_way_chains);
        assert_eq!(opts.round_count(), 1, "one round is the default");
        assert!(opts.spill_dir.is_none(), "the spill defaults to the output dir");
        let (_, _, opts) = parse_args(&["cal.osm.pbf".into(), "--rounds".into(), "8".into()]).unwrap();
        assert_eq!(opts.round_count(), 8);
        let (_, _, opts) =
            parse_args(&["c.pbf".into(), "--spill-dir".into(), "/fast".into()]).unwrap();
        assert_eq!(opts.spill_dir, Some(PathBuf::from("/fast")));
        assert!(parse_args(&["a".into(), "--spill-dir".into()]).is_err());
        let (_, _, opts) = parse_args(&[
            "c.pbf".into(),
            "--spill-dir".into(),
            "/roomy".into(),
            "--spill-pts-dir".into(),
            "/fast".into(),
        ])
        .unwrap();
        assert_eq!(opts.spill_dir, Some(PathBuf::from("/roomy")));
        assert_eq!(opts.spill_pts_dir, Some(PathBuf::from("/fast")));
        assert!(parse_args(&["a".into(), "--spill-pts-dir".into()]).is_err());
        assert!(parse_args(&["a".into(), "--rounds".into(), "0".into()]).is_err());
        assert!(parse_args(&["a".into(), "--rounds".into(), "many".into()]).is_err());
        assert!(parse_args(&["a".into(), "--rounds".into()]).is_err());
        assert!(parse_args(&[]).is_err());
        assert!(parse_args(&["a".into(), "b".into()]).is_err());
        assert!(parse_args(&["--wat".into()]).is_err());
    }
}
