//! Within-way chain building: degree-2 collapsing that never materialises a
//! segment array.
//!
//! [`crate::compact`] does the same job by building every segment, indexing them
//! into a CSR of incidences, and walking chains across way boundaries. That is
//! the better graph and the wrong shape for a planet: the segment array and its
//! incidence index are 114 GB of a 217 GB working set, and no amount of shrinking
//! records closes that on a 94 GB machine.
//!
//! This module gets the same collapse from two much smaller structures:
//!
//! * **one byte of degree per node**, accumulated by a pass that reads the way
//!   blobs and keeps nothing;
//! * **the way's own ref list**, walked positionally as it is decoded.
//!
//! # What it costs
//!
//! Chains may not cross from one OSM way into another. Every segment in a chain
//! then shares its way's type, speed limit, name, one-way-ness and lane masks *by
//! construction*, so the entire attribute-agreement half of
//! `compact::classify` — and the segment records it compared — disappears.
//!
//! The price is real and is an accepted regression: a node where two different
//! ways meet end-to-end with matching tags is collapsed by `compact` and kept
//! here. OSM splits long roads constantly, at every tag change and at the
//! 2000-node way ceiling, so this is not a rare case. Two things soften it: the
//! 256-point budget already forces cuts on long dense roads anyway, and more
//! surviving nodes strictly *improves* snapping.
//!
//! # What it buys beyond memory
//!
//! Two correctness simplifications fall out of walking positions instead of a
//! graph:
//!
//! * the walk cannot loop, because it advances through a finite ref list, so
//!   there is no "used" flag per segment and no anchorless-cycle phase — a ring
//!   way closes on its own first node for free;
//! * within one way every segment runs along ref order, so a one-way chain is
//!   already oriented along its traffic and there is no reorientation step to get
//!   the forward and backward lane masks the wrong way round.
//!
//! Both of those were bugs that had to be fixed once already.

use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use crate::compact::Chain;
use crate::geom;
use crate::graph_build::{way_attrs, Bitset, NodeIndex, WayAttrs};
use crate::names::{LocalNames, NamePool, NO_NAME};
use crate::osm::{visit_block, Element};
use crate::pbf::{self, BlobLoc, KIND_WAYS};
use crate::proto::{Error, Result};
use crate::spatial::accurate_dist_mm;
use crate::tags;

/// Is this consecutive pair of way refs a segment of the graph?
///
/// **Both the degree pass and the chain walk must ask this and nothing else.**
/// The degree array is what decides where a chain is cut, so a disagreement
/// between the two would either dead-end a chain at a node the degree array
/// thinks is a pass-through, or walk a chain straight through one it thinks is a
/// junction. Every other invariant here is recoverable; this one is not.
#[inline]
pub(crate) fn segment(index: &NodeIndex, a: i64, b: i64) -> Option<(u32, u32)> {
    let (u, v) = (index.dense(a)?, index.dense(b)?);
    // A way listing the same node twice in a row is a mapping artefact, not a
    // zero-length road. Counting it would give that node two phantom incidences
    // and so promote a genuine pass-through into a junction.
    (u != v).then_some((u, v))
}

/// Nodes with more incidences than a byte can hold are junctions whatever the
/// true count is, so the counter saturates. Only `== 2` changes a decision, and
/// no node with 255 incidences is a pass-through.
#[derive(Default)]
struct DegreePass {
    /// Both endpoints of every segment, in decode order. Per chunk, so the
    /// random scatter into the global array happens once, single-threaded, in the
    /// sink.
    endpoints: Vec<u32>,
}

/// One saturating incidence count per dense node id.
pub(crate) fn count_degrees(
    input: &Path,
    blobs: &[BlobLoc],
    blob_kinds: &[u8],
    index: &NodeIndex,
    slots: u32,
) -> Result<Vec<u8>> {
    let mut degree = vec![0u8; slots as usize];
    let _ = pbf::run_pass_sink(
        input,
        blobs,
        Some(blob_kinds),
        KIND_WAYS,
        "Pass 3: node degrees",
        DegreePass::default,
        |state: &mut DegreePass, block| {
            let mut kinds = 0u8;
            visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
                if let Element::Way(w) = el {
                    if tags::get_hw_id(w.tags.get_str("highway")) != 0 {
                        for pair in w.refs.windows(2) {
                            if let Some((u, v)) = segment(index, pair[0], pair[1]) {
                                state.endpoints.push(u);
                                state.endpoints.push(v);
                            }
                        }
                    }
                }
                Ok(())
            })?;
            Ok(kinds)
        },
        |chunk| {
            for e in chunk.endpoints {
                let d = &mut degree[e as usize];
                *d = d.saturating_add(1);
            }
            Ok(())
        },
    )?;
    Ok(degree)
}

/// Every chain in the file, plus the lane pool their offsets point into.
/// What the chain pass leaves behind. The chains themselves are on disk.
pub(crate) struct Chains {
    pub chain_count: u64,
    pub lanes: Vec<u16>,
    /// Dense ids a chain starts or ends at. Together with "degree is not 2" this
    /// is the surviving-node set.
    pub endpoints: Bitset,
    /// Chains cut because their geometry would not fit one edge.
    pub splits: usize,
    /// Directed edges an uncompacted build would have produced. Runs tile every
    /// segment exactly once, so this is exact without keeping the segments.
    pub raw_edge_count: u64,
    /// Which blobs the chain pass actually had to read. The load-bearing
    /// measurement for re-reading the way blobs rather than caching them.
    pub kinds: Vec<u8>,
}

#[derive(Default)]
struct ChainPass {
    chains: Vec<Chain>,
    pts: Vec<u32>,
    lanes: Vec<u16>,
    names: LocalNames,
    endpoints: Vec<u32>,
    splits: usize,
    raw_edge_count: u64,
}

/// Walk every routable way, cutting chains at nodes that must survive, and stream
/// the result into `spill`.
///
/// `degree` comes from [`count_degrees`] and `stop` marks nodes carrying a
/// transit stop code; both are indexed by dense id, as is `coords`.
///
/// Nothing chain-shaped is retained. Only one chunk's worth exists at a time,
/// which is what keeps this pass's footprint proportional to a chunk rather than
/// to the road network.
#[allow(clippy::too_many_arguments)]
pub(crate) fn build<W: Write + Send>(
    input: &Path,
    blobs: &[BlobLoc],
    blob_kinds: &[u8],
    index: &NodeIndex,
    coords: &[geom::Pt],
    degree: &[u8],
    stop: &Bitset,
    slots: u32,
    pool: &mut NamePool<W>,
    spill: &Spill,
) -> Result<Chains> {
    let mut out = Chains {
        chain_count: 0,
        lanes: Vec::new(),
        endpoints: Bitset::new(u64::from(slots)),
        splits: 0,
        raw_edge_count: 0,
        kinds: Vec::new(),
    };
    let mut writer = spill.writer()?;
    out.kinds = pbf::run_pass_sink(
        input,
        blobs,
        Some(blob_kinds),
        KIND_WAYS,
        "Pass 4: chains",
        ChainPass::default,
        |state: &mut ChainPass, block| chain_blob(state, block, index, coords, degree, stop),
        |chunk| {
            let name_map = chunk
                .names
                .flush(pool)
                .map_err(|e| Error(e.to_string()))?;
            let lane_base = out.lanes.len() as u32;
            out.lanes.extend_from_slice(&chunk.lanes);
            crate::graph_build::cap_lane_pool(out.lanes.len())?;
            for mut c in chunk.chains {
                let lo = c.pts_start as usize;
                let ids = &chunk.pts[lo..lo + c.pts_len as usize];
                c.name_offset = if c.name_offset == u32::MAX {
                    NO_NAME
                } else {
                    name_map[c.name_offset as usize]
                };
                if c.fwd_lane_count > 0 {
                    c.fwd_lane_off += lane_base;
                }
                if c.bwd_lane_count > 0 {
                    c.bwd_lane_off += lane_base;
                }
                // `pts_start` is assigned by the writer, so the chunk-local value
                // is only used to find the ids above.
                writer.push(&c, ids, coords)?;
            }
            for e in chunk.endpoints {
                out.endpoints.set(u64::from(e));
            }
            out.splits += chunk.splits;
            out.raw_edge_count += chunk.raw_edge_count;
            Ok(())
        },
    )?;
    out.chain_count = writer.finish()?;
    Ok(out)
}

fn chain_blob(
    state: &mut ChainPass,
    block: &pbf::PrimitiveBlock,
    index: &NodeIndex,
    coords: &[geom::Pt],
    degree: &[u8],
    stop: &Bitset,
) -> Result<u8> {
    let mut kinds = 0u8;
    let mut path: Vec<u32> = Vec::new();
    let mut hops: Vec<u32> = Vec::new();
    visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
        let Element::Way(w) = el else {
            return Ok(());
        };
        let type_ = tags::get_hw_id(w.tags.get_str("highway"));
        if type_ == 0 {
            return Ok(());
        }
        let attrs = way_attrs(&w, type_, &mut state.lanes, &mut state.names);
        path.clear();
        hops.clear();
        for pair in w.refs.windows(2) {
            let Some((u, v)) = segment(index, pair[0], pair[1]) else {
                // Not a segment — a dangling ref, or the way listing one node
                // twice in a row. Skip it and let the next accepted pair decide
                // whether it continues this run.
                continue;
            };
            if path.last() != Some(&u) {
                // The start of the way, the far side of a gap the graph does not
                // bridge, or the node the previous cut ended on.
                flush(state, &path, &hops, &attrs, coords);
                path.clear();
                hops.clear();
                path.push(u);
            }
            let (a, b) = (coords[u as usize], coords[v as usize]);
            hops.push(accurate_dist_mm(a.0, a.1, b.0, b.1));
            path.push(v);
            // `v` must survive when it is a junction, a dead end, or a transit
            // stop the reconnect pass addresses directly. Cut there; the next pair
            // restarts from it by the rule above.
            if degree[v as usize] != 2 || stop.get(u64::from(v)) {
                flush(state, &path, &hops, &attrs, coords);
                path.clear();
                hops.clear();
            }
        }
        // The way's last node is a chain boundary whatever its degree: a chain may
        // not continue into the next way.
        flush(state, &path, &hops, &attrs, coords);
        Ok(())
    })?;
    Ok(kinds)
}

/// Emit `path` as one or more chains, splitting it to fit the geometry budget.
fn flush(
    state: &mut ChainPass,
    path: &[u32],
    hops: &[u32],
    attrs: &WayAttrs,
    coords: &[geom::Pt],
) {
    if path.len() < 2 {
        return;
    }
    debug_assert_eq!(hops.len(), path.len() - 1);
    let runs = split_runs(path, coords);
    if runs.len() > 1 {
        state.splits += runs.len() - 1;
    }
    for (lo, hi) in runs {
        state.endpoints.push(path[lo]);
        state.endpoints.push(path[hi]);
        let pts_start = state.pts.len() as u64;
        state.pts.extend_from_slice(&path[lo..=hi]);
        // Directed edges an uncompacted build would have emitted for this run:
        // one per segment per permitted direction. Runs tile every segment of the
        // way exactly once, so summing them is exact without keeping the segments.
        state.raw_edge_count += (hi - lo) as u64 * if attrs.oneway { 1 } else { 2 };
        state.chains.push(Chain {
            pts_start,
            pts_len: (hi - lo + 1) as u32,
            dist_mm: hops[lo..hi].iter().fold(0u32, |a, d| a.saturating_add(*d)),
            name_offset: attrs.name,
            type_: attrs.type_,
            speed_limit: attrs.speed_limit,
            oneway: attrs.oneway,
            // Forward is along ref order, and so is the chain: no reorientation,
            // so no chance of handing the masks to the wrong end.
            fwd_lane_off: attrs.fwd_lane_off,
            fwd_lane_count: attrs.fwd_lane_count,
            bwd_lane_off: attrs.bwd_lane_off,
            bwd_lane_count: attrs.bwd_lane_count,
        });
    }
}

/// Cut `path` into runs that each fit one edge's geometry.
///
/// Returns inclusive index ranges. A run of two nodes is always allowed even if
/// its single segment is too long to delta-encode: the caller stores no geometry
/// for it, and the reader's straight-chord fallback is then exactly right,
/// because a two-node run *is* a straight chord.
fn split_runs(path: &[u32], coords: &[geom::Pt]) -> Vec<(usize, usize)> {
    let point = |n: u32| coords[n as usize];
    let mut runs = Vec::new();
    let mut start = 0usize;
    let mut cost = 1u64;
    let mut i = 1usize;
    while i < path.len() {
        let step = geom::segment_points(point(path[i - 1]), point(path[i]));
        if cost + step <= u64::from(geom::MAX_POINTS) {
            cost += step;
            i += 1;
            continue;
        }
        if i - 1 > start {
            // Close the run before this segment and retry it with a fresh budget
            // starting at the shared node.
            runs.push((start, i - 1));
            start = i - 1;
            cost = 1;
        } else {
            // One segment that cannot be encoded at all, even alone.
            runs.push((start, i));
            start = i;
            cost = 1;
            i += 1;
        }
    }
    if start < path.len() - 1 {
        runs.push((start, path.len() - 1));
    }
    runs
}

/// Does dense node `n` survive as a graph node?
///
/// A chain endpoint always does. So does anything whose incidence count is not
/// exactly two — including a count of zero, which is how an isolated transit stop
/// and a node stranded by a dangling reference both look.
#[inline]
pub(crate) fn survives(endpoints: &Bitset, degree: &[u8], n: u32) -> bool {
    endpoints.get(u64::from(n)) || degree[n as usize] != 2
}

// ---- the spill -----------------------------------------------------------
//
// Chains are the last structure in the build that scales with the road network
// rather than with the node set, and every stage after them is a sequential scan.
// So they go to two flat files and the stages read them back: the CSR build, the
// reconnect BFS and each write round all stream, and none of them needs the chain
// set resident.
//
// Two properties are worth the redundancy in the record:
//
// * **The header is fixed size**, so the chain count is the file's length divided
//   by the record size, and any stage can seek straight to chain *k* and process a
//   contiguous slice of chains without reading the ones before it.
// * **`chains.pts` holds coordinates, not node ids**, so `coords` — the largest
//   array in the build, and the only one indexed by *marked* rather than surviving
//   node — can be freed the moment the spill is written. The cost is that the two
//   endpoint node ids have to be repeated in the header, which is eight bytes a
//   chain against gigabytes of coordinates.

/// Bytes per record in `chains.hdr`. The trailing padding is reserved and written
/// as zero, so the record can grow a field without changing the stride.
pub(crate) const CHAIN_REC_BYTES: u64 = 48;

/// Bytes per point in `chains.pts`: `i32 lat_e7`, `i32 lon_e7`.
const CHAIN_PT_BYTES: u64 = 8;

/// One chain, as `chains.hdr` stores it.
#[derive(Clone, Copy)]
pub(crate) struct ChainRec {
    /// First point's index in `chains.pts`.
    pub pts_start: u64,
    pub pts_len: u32,
    /// Dense id of the first point. Kept even though the point itself is stored,
    /// because the writer needs the *node* to map to a final id and the
    /// coordinates cannot be mapped back to one.
    pub first: u32,
    /// Dense id of the last point.
    pub last: u32,
    pub dist_mm: u32,
    pub name_offset: u32,
    pub fwd_lane_off: u32,
    pub bwd_lane_off: u32,
    pub fwd_lane_count: u16,
    pub bwd_lane_count: u16,
    pub type_: u8,
    pub speed_limit: u8,
    pub oneway: bool,
}

impl ChainRec {
    /// This chain's polyline, from `chains.pts` read into memory.
    #[cfg(test)]
    pub fn pts<'a>(&self, all: &'a [geom::Pt]) -> &'a [geom::Pt] {
        let s = self.pts_start as usize;
        &all[s..s + self.pts_len as usize]
    }

    fn encode(&self, out: &mut [u8; CHAIN_REC_BYTES as usize]) {
        out.fill(0);
        out[0..8].copy_from_slice(&self.pts_start.to_le_bytes());
        out[8..12].copy_from_slice(&self.pts_len.to_le_bytes());
        out[12..16].copy_from_slice(&self.first.to_le_bytes());
        out[16..20].copy_from_slice(&self.last.to_le_bytes());
        out[20..24].copy_from_slice(&self.dist_mm.to_le_bytes());
        out[24..28].copy_from_slice(&self.name_offset.to_le_bytes());
        out[28..32].copy_from_slice(&self.fwd_lane_off.to_le_bytes());
        out[32..36].copy_from_slice(&self.bwd_lane_off.to_le_bytes());
        out[36..38].copy_from_slice(&self.fwd_lane_count.to_le_bytes());
        out[38..40].copy_from_slice(&self.bwd_lane_count.to_le_bytes());
        out[40] = self.type_;
        out[41] = self.speed_limit;
        out[42] = u8::from(self.oneway);
    }

    fn decode(b: &[u8]) -> ChainRec {
        let u32_at = |o: usize| u32::from_le_bytes(b[o..o + 4].try_into().expect("4 bytes"));
        let u16_at = |o: usize| u16::from_le_bytes(b[o..o + 2].try_into().expect("2 bytes"));
        ChainRec {
            pts_start: u64::from_le_bytes(b[0..8].try_into().expect("8 bytes")),
            pts_len: u32_at(8),
            first: u32_at(12),
            last: u32_at(16),
            dist_mm: u32_at(20),
            name_offset: u32_at(24),
            fwd_lane_off: u32_at(28),
            bwd_lane_off: u32_at(32),
            fwd_lane_count: u16_at(36),
            bwd_lane_count: u16_at(38),
            type_: b[40],
            speed_limit: b[41],
            oneway: b[42] != 0,
        }
    }
}

/// The pair of spill files, in a directory of their own so a build cannot
/// mistake them for pack output.
///
/// The two files can live on different filesystems, because they are read very
/// differently. `chains.hdr` is only ever streamed from the front, once per write
/// round, so a slow-but-roomy mount costs little. `chains.pts` is read *randomly*,
/// once per edge that stores a polyline, and that is the access pattern a
/// translation layer like drvfs punishes by an order of magnitude. At planet scale
/// the header is also the larger of the two — about 24 GB against 18 GB — so
/// separating them puts the bulk where there is room and the seeks where they are
/// cheap.
pub(crate) struct Spill {
    hdr_dir: PathBuf,
    pts_dir: PathBuf,
    hdr: PathBuf,
    pts: PathBuf,
}

impl Spill {
    /// Both files in one directory.
    #[cfg(test)]
    pub fn new(dir: &Path) -> Spill {
        Spill::split(dir, dir)
    }

    /// `chains.hdr` under `hdr_dir`, `chains.pts` under `pts_dir`.
    pub fn split(hdr_dir: &Path, pts_dir: &Path) -> Spill {
        let hdr_dir = hdr_dir.join("chain_spill");
        let pts_dir = pts_dir.join("chain_spill");
        Spill {
            hdr: hdr_dir.join("chains.hdr"),
            pts: pts_dir.join("chains.pts"),
            hdr_dir,
            pts_dir,
        }
    }

    fn make_dirs(&self) -> Result<()> {
        for dir in [&self.hdr_dir, &self.pts_dir] {
            std::fs::create_dir_all(dir)
                .map_err(|e| Error(format!("cannot create {}: {e}", dir.display())))?;
        }
        Ok(())
    }

    /// An incremental writer, for the chain pass to stream into.
    ///
    /// The alternative — accumulate every chain and every point, then write them —
    /// costs 11.7 GB on Europe and about 31 GB on a planet, which is most of the
    /// budget the spill exists to save. The pass sink is already single-threaded
    /// and called in chunk order, so writing from it keeps the files
    /// byte-deterministic.
    pub fn writer(&self) -> Result<SpillWriter> {
        self.make_dirs()?;
        Ok(SpillWriter {
            hdr: BufWriter::with_capacity(1 << 20, create(&self.hdr)?),
            pts: BufWriter::with_capacity(1 << 20, create(&self.pts)?),
            points: 0,
            chains: 0,
        })
    }

    /// Write `chains` and their points, resolving dense ids to coordinates.
    ///
    /// `pts_dense` must be laid out so each chain's range follows the one before
    /// it, which is how the chain pass builds it; the points then stream out in
    /// one sequential write and every `pts_start` carries over unchanged.
    pub fn write(
        &self,
        chains: &[Chain],
        pts_dense: &[u32],
        coords: &[geom::Pt],
    ) -> Result<()> {
        self.make_dirs()?;
        let mut hdr = BufWriter::new(create(&self.hdr)?);
        let mut rec = [0u8; CHAIN_REC_BYTES as usize];
        let mut expect_start = 0u64;
        for c in chains {
            let ids = c.pts(pts_dense);
            debug_assert_eq!(c.pts_start, expect_start, "chain points are not contiguous");
            expect_start += u64::from(c.pts_len);
            ChainRec {
                pts_start: c.pts_start,
                pts_len: c.pts_len,
                first: ids[0],
                last: ids[ids.len() - 1],
                dist_mm: c.dist_mm,
                name_offset: c.name_offset,
                fwd_lane_off: c.fwd_lane_off,
                bwd_lane_off: c.bwd_lane_off,
                fwd_lane_count: c.fwd_lane_count,
                bwd_lane_count: c.bwd_lane_count,
                type_: c.type_,
                speed_limit: c.speed_limit,
                oneway: c.oneway,
            }
            .encode(&mut rec);
            hdr.write_all(&rec).map_err(io_err)?;
        }
        hdr.flush().map_err(io_err)?;

        let mut out = BufWriter::new(create(&self.pts)?);
        for id in pts_dense {
            let (lat_e7, lon_e7) = coords[*id as usize];
            out.write_all(&lat_e7.to_le_bytes()).map_err(io_err)?;
            out.write_all(&lon_e7.to_le_bytes()).map_err(io_err)?;
        }
        out.flush().map_err(io_err)?;
        Ok(())
    }

    /// How many chains the spill holds, from the header file's size alone.
    pub fn chain_count(&self) -> Result<u64> {
        let len = std::fs::metadata(&self.hdr)
            .map_err(|e| Error(format!("cannot stat {}: {e}", self.hdr.display())))?
            .len();
        if len % CHAIN_REC_BYTES != 0 {
            return Err(Error(format!(
                "{} is {len} bytes, not a multiple of {CHAIN_REC_BYTES}",
                self.hdr.display()
            )));
        }
        Ok(len / CHAIN_REC_BYTES)
    }

    /// Read the whole spill back. The build itself streams; this is for tests,
    /// which need to see the two files as a unit to check they round-trip.
    #[cfg(test)]
    pub fn read_all(&self) -> Result<(Vec<ChainRec>, Vec<geom::Pt>)> {
        let count = self.chain_count()?;
        let mut hdr = self.headers()?;
        let mut chains = Vec::with_capacity(count as usize);
        while let Some(c) = hdr.next()? {
            chains.push(c);
        }

        let bytes = self.point_bytes()?;
        let mut src = BufReader::new(open(&self.pts)?);
        let mut buf = [0u8; CHAIN_PT_BYTES as usize];
        let mut pts = Vec::with_capacity((bytes / CHAIN_PT_BYTES) as usize);
        for _ in 0..bytes / CHAIN_PT_BYTES {
            src.read_exact(&mut buf).map_err(io_err)?;
            pts.push(decode_pt(&buf));
        }
        Ok((chains, pts))
    }

    /// Sequential reader over `chains.hdr`. Every stage after the chain pass is one
    /// of these: the degree count for the CSR, the target scatter, and one per
    /// write round.
    pub fn headers(&self) -> Result<HeaderReader> {
        Ok(HeaderReader {
            src: BufReader::with_capacity(1 << 20, open(&self.hdr)?),
            left: self.chain_count()?,
        })
    }

    /// Random-access reader over `chains.pts`.
    ///
    /// The write rounds visit chains in edge order, not chain order, so this is the
    /// one place the spill is not read sequentially. Each polyline is contiguous
    /// and only a few dozen bytes, so it is one seek per edge into a file the page
    /// cache has largely seen already.
    pub fn points(&self) -> Result<PointReader> {
        Ok(PointReader {
            file: open(&self.pts)?,
            buf: Vec::new(),
        })
    }

    #[cfg(test)]
    fn point_bytes(&self) -> Result<u64> {
        let bytes = std::fs::metadata(&self.pts)
            .map_err(|e| Error(format!("cannot stat {}: {e}", self.pts.display())))?
            .len();
        if bytes % CHAIN_PT_BYTES != 0 {
            return Err(Error(format!(
                "{} is {bytes} bytes, not a multiple of {CHAIN_PT_BYTES}",
                self.pts.display()
            )));
        }
        Ok(bytes)
    }

    /// Best-effort cleanup, also run on drop. A leftover spill is tens of
    /// gigabytes, but failing the build over an undeletable temporary would be
    /// worse than leaving it.
    pub fn remove(&self) {
        let _ = std::fs::remove_file(&self.hdr);
        let _ = std::fs::remove_file(&self.pts);
        // Only removes them if empty, so a shared parent survives.
        let _ = std::fs::remove_dir(&self.hdr_dir);
        let _ = std::fs::remove_dir(&self.pts_dir);
    }
}

impl Drop for Spill {
    /// The spill is a temporary, so its lifetime is the handle's. Without this, any
    /// error between writing it and the end of the build would strand it: the one
    /// path where a leftover is most likely is also the one nobody is watching.
    fn drop(&mut self) {
        self.remove();
    }
}

fn create(path: &Path) -> Result<File> {
    File::create(path).map_err(|e| Error(format!("cannot write {}: {e}", path.display())))
}

fn open(path: &Path) -> Result<File> {
    File::open(path).map_err(|e| Error(format!("cannot read {}: {e}", path.display())))
}

fn decode_pt(b: &[u8]) -> geom::Pt {
    (
        i32::from_le_bytes(b[0..4].try_into().expect("4 bytes")),
        i32::from_le_bytes(b[4..8].try_into().expect("4 bytes")),
    )
}

/// Streams `chains.hdr` and `chains.pts` from the front.
pub(crate) struct SpillWriter {
    hdr: BufWriter<File>,
    pts: BufWriter<File>,
    /// Points written so far, which is the next chain's `pts_start`.
    points: u64,
    chains: u64,
}

impl SpillWriter {
    /// Append one chain and its polyline.
    ///
    /// `ids` are the chain's dense node ids in order; their coordinates go to
    /// `chains.pts` and only the two endpoint *ids* are kept, in the header, since
    /// coordinates cannot be mapped back to a node.
    pub fn push(&mut self, c: &Chain, ids: &[u32], coords: &[geom::Pt]) -> Result<()> {
        debug_assert_eq!(ids.len(), c.pts_len as usize);
        let mut rec = [0u8; CHAIN_REC_BYTES as usize];
        ChainRec {
            pts_start: self.points,
            pts_len: c.pts_len,
            first: ids[0],
            last: ids[ids.len() - 1],
            dist_mm: c.dist_mm,
            name_offset: c.name_offset,
            fwd_lane_off: c.fwd_lane_off,
            bwd_lane_off: c.bwd_lane_off,
            fwd_lane_count: c.fwd_lane_count,
            bwd_lane_count: c.bwd_lane_count,
            type_: c.type_,
            speed_limit: c.speed_limit,
            oneway: c.oneway,
        }
        .encode(&mut rec);
        self.hdr.write_all(&rec).map_err(io_err)?;
        for id in ids {
            let (lat_e7, lon_e7) = coords[*id as usize];
            self.pts.write_all(&lat_e7.to_le_bytes()).map_err(io_err)?;
            self.pts.write_all(&lon_e7.to_le_bytes()).map_err(io_err)?;
        }
        self.points += u64::from(c.pts_len);
        self.chains += 1;
        Ok(())
    }

    /// Flush both files and report how many chains were written.
    pub fn finish(mut self) -> Result<u64> {
        self.hdr.flush().map_err(io_err)?;
        self.pts.flush().map_err(io_err)?;
        Ok(self.chains)
    }
}

/// Streams `chains.hdr` from the front.
pub(crate) struct HeaderReader {
    src: BufReader<File>,
    left: u64,
}

impl HeaderReader {
    #[allow(clippy::should_implement_trait)]
    pub fn next(&mut self) -> Result<Option<ChainRec>> {
        if self.left == 0 {
            return Ok(None);
        }
        let mut rec = [0u8; CHAIN_REC_BYTES as usize];
        self.src.read_exact(&mut rec).map_err(io_err)?;
        self.left -= 1;
        Ok(Some(ChainRec::decode(&rec)))
    }
}

/// Reads one chain's polyline out of `chains.pts`.
pub(crate) struct PointReader {
    file: File,
    /// Reused across reads: the write rounds call this once per edge, so a fresh
    /// allocation each time would be a billion of them at planet scale.
    buf: Vec<u8>,
}

impl PointReader {
    /// Replace `out` with the `len` points starting at point index `start`.
    pub fn read(&mut self, start: u64, len: u32, out: &mut Vec<geom::Pt>) -> Result<()> {
        out.clear();
        if len == 0 {
            return Ok(());
        }
        self.file
            .seek(SeekFrom::Start(start * CHAIN_PT_BYTES))
            .map_err(io_err)?;
        self.buf.clear();
        self.buf.resize(len as usize * CHAIN_PT_BYTES as usize, 0);
        self.file.read_exact(&mut self.buf).map_err(io_err)?;
        out.extend(self.buf.chunks_exact(CHAIN_PT_BYTES as usize).map(decode_pt));
        Ok(())
    }
}

fn io_err(e: std::io::Error) -> Error {
    Error(e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A straight line of nodes 0.001 degrees apart, well inside the i16 delta
    /// range, so nothing in these tests is interpolated.
    fn coords(n: usize) -> Vec<geom::Pt> {
        (0..n)
            .map(|i| (370_000_000 + (i as i32) * 10_000, -1_220_000_000))
            .collect()
    }

    #[test]
    fn a_run_is_split_to_fit_the_geometry_budget() {
        // Nodes 0.02 degrees apart: about 7 encoded points per segment, so ~36
        // segments fill one edge's 256-point budget.
        let far: Vec<geom::Pt> = (0..81)
            .map(|i| (370_000_000 + i * 200_000, -1_220_000_000))
            .collect();
        let path: Vec<u32> = (0..81).collect();
        let runs = split_runs(&path, &far);
        assert!(runs.len() >= 3, "{runs:?}");
        // The runs must tile the path, sharing their boundary nodes.
        assert_eq!(runs[0].0, 0);
        assert_eq!(runs.last().unwrap().1, 80);
        for w in runs.windows(2) {
            assert_eq!(w[0].1, w[1].0, "runs must share a node");
        }
        // Every run must be encodable, or be a bare chord the reader can infer.
        for (lo, hi) in &runs {
            let pts: Vec<geom::Pt> = path[*lo..=*hi].iter().map(|n| far[*n as usize]).collect();
            assert!(pts.len() == 2 || geom::fits(&pts), "{} points", pts.len());
        }
    }

    #[test]
    fn a_single_unencodable_segment_becomes_its_own_run() {
        // 30 degrees apart: one segment needs ~9155 encoded points, far past the
        // ceiling, so it must be emitted alone and left without geometry.
        let far = vec![(100_000_000, 0), (400_000_000, 0), (700_000_000, 0)];
        let runs = split_runs(&[0, 1, 2], &far);
        assert_eq!(runs, vec![(0, 1), (1, 2)]);
    }

    #[test]
    fn a_short_run_is_never_split() {
        let c = coords(5);
        assert_eq!(split_runs(&[0, 1, 2, 3, 4], &c), vec![(0, 4)]);
        assert_eq!(split_runs(&[0, 1], &c), vec![(0, 1)]);
    }

    fn chain(pts_start: u64, pts_len: u32) -> Chain {
        Chain {
            pts_start,
            pts_len,
            dist_mm: 4_000,
            name_offset: 17,
            type_: 7,
            speed_limit: 48,
            oneway: true,
            fwd_lane_off: 3,
            fwd_lane_count: 2,
            bwd_lane_off: 9,
            bwd_lane_count: 1,
        }
    }

    fn spill_dir(tag: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("osm_ingest_{tag}"));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn the_spill_round_trips_chains_and_resolves_points_to_coordinates() {
        let dir = spill_dir("spill_round_trip");
        let all = coords(6);
        // Two chains tiling six points, laid out contiguously as the chain pass
        // produces them.
        let dense: Vec<u32> = vec![0, 1, 2, 3, 4, 5];
        let src = vec![chain(0, 4), chain(4, 2)];

        let spill = Spill::new(&dir);
        spill.write(&src, &dense, &all).unwrap();
        assert_eq!(spill.chain_count().unwrap(), 2);

        let (back, pts) = spill.read_all().unwrap();
        assert_eq!(pts, all, "points come back as coordinates, in order");
        assert_eq!(back.len(), 2);
        // The endpoints are node ids, which coordinates alone could not recover.
        assert_eq!((back[0].first, back[0].last), (0, 3));
        assert_eq!((back[1].first, back[1].last), (4, 5));
        for (a, b) in src.iter().zip(&back) {
            assert_eq!(b.pts_start, a.pts_start);
            assert_eq!(b.pts_len, a.pts_len);
            assert_eq!(b.dist_mm, a.dist_mm);
            assert_eq!(b.name_offset, a.name_offset);
            assert_eq!(b.type_, a.type_);
            assert_eq!(b.speed_limit, a.speed_limit);
            assert_eq!(b.oneway, a.oneway);
            assert_eq!((b.fwd_lane_off, b.fwd_lane_count), (a.fwd_lane_off, a.fwd_lane_count));
            assert_eq!((b.bwd_lane_off, b.bwd_lane_count), (a.bwd_lane_off, a.bwd_lane_count));
        }
        // Each chain's polyline is addressable without reading the ones before it,
        // which is what the fixed-size header buys.
        assert_eq!(back[1].pts(&pts), &all[4..6]);

        spill.remove();
        assert!(spill.chain_count().is_err(), "the spill is gone after remove");
    }

    #[test]
    fn two_spills_of_the_same_chains_are_byte_identical() {
        let all = coords(6);
        let dense: Vec<u32> = vec![0, 1, 2, 3, 4, 5];
        let src = vec![chain(0, 4), chain(4, 2)];
        let mut written: Vec<(Vec<u8>, Vec<u8>)> = Vec::new();
        for tag in ["spill_det_a", "spill_det_b"] {
            let dir = spill_dir(tag);
            let spill = Spill::new(&dir);
            spill.write(&src, &dense, &all).unwrap();
            written.push((
                std::fs::read(&spill.hdr).unwrap(),
                std::fs::read(&spill.pts).unwrap(),
            ));
        }
        assert_eq!(written[0].0, written[1].0, "chains.hdr differs between runs");
        assert_eq!(written[0].1, written[1].1, "chains.pts differs between runs");
        // The record stride is what lets a later pass seek to chain k, so it is
        // part of the format and not an implementation detail.
        assert_eq!(written[0].0.len() as u64, 2 * CHAIN_REC_BYTES);
        assert_eq!(written[0].1.len() as u64, 6 * CHAIN_PT_BYTES);
    }

    #[test]
    fn streaming_the_spill_matches_writing_it_in_one_go() {
        // The chain pass streams into the spill from its sink so it never holds the
        // chain set; the reference path still writes it in one call. Those two must
        // produce the same bytes, or the two collapse paths would not be comparable
        // and the round-count byte-identity test would be checking the wrong thing.
        let all = coords(9);
        let dense: Vec<u32> = vec![0, 1, 2, 3, 4, 5, 6, 7, 8];
        let src = vec![chain(0, 4), chain(4, 2), chain(6, 3)];

        let batch = Spill::new(&spill_dir("spill_batch"));
        batch.write(&src, &dense, &all).unwrap();

        let streamed = Spill::new(&spill_dir("spill_stream"));
        let mut w = streamed.writer().unwrap();
        for c in &src {
            let lo = c.pts_start as usize;
            w.push(c, &dense[lo..lo + c.pts_len as usize], &all).unwrap();
        }
        assert_eq!(w.finish().unwrap(), 3);

        assert_eq!(
            std::fs::read(&batch.hdr).unwrap(),
            std::fs::read(&streamed.hdr).unwrap(),
            "chains.hdr differs between the batch and streaming writers"
        );
        assert_eq!(
            std::fs::read(&batch.pts).unwrap(),
            std::fs::read(&streamed.pts).unwrap(),
            "chains.pts differs between the batch and streaming writers"
        );
        assert_eq!(streamed.chain_count().unwrap(), 3);
    }

    #[test]
    fn splitting_the_two_files_across_directories_changes_no_bytes() {
        // The header and the points can live on different filesystems, because one
        // is streamed and the other is seeked. Which directory each lands in must
        // not affect what is in them.
        let all = coords(6);
        let dense: Vec<u32> = vec![0, 1, 2, 3, 4, 5];
        let src = vec![chain(0, 4), chain(4, 2)];

        let together = Spill::new(&spill_dir("spill_together"));
        together.write(&src, &dense, &all).unwrap();

        let hdr_dir = spill_dir("spill_apart_hdr");
        let pts_dir = spill_dir("spill_apart_pts");
        let apart = Spill::split(&hdr_dir, &pts_dir);
        apart.write(&src, &dense, &all).unwrap();

        assert_eq!(
            std::fs::read(&together.hdr).unwrap(),
            std::fs::read(&apart.hdr).unwrap()
        );
        assert_eq!(
            std::fs::read(&together.pts).unwrap(),
            std::fs::read(&apart.pts).unwrap()
        );
        // Each file really is under its own directory, and the reader finds both.
        assert!(apart.hdr.starts_with(&hdr_dir));
        assert!(apart.pts.starts_with(&pts_dir));
        let (chains, pts) = apart.read_all().unwrap();
        assert_eq!(chains.len(), 2);
        assert_eq!(pts, all);

        apart.remove();
        assert!(!apart.hdr.exists() && !apart.pts.exists());
    }

    #[test]
    fn a_header_that_is_not_a_whole_number_of_records_is_rejected() {
        let dir = spill_dir("spill_ragged");
        let all = coords(2);
        let spill = Spill::new(&dir);
        spill.write(&[chain(0, 2)], &[0, 1], &all).unwrap();
        // Truncating mid-record must fail loudly: read_all would otherwise decode a
        // partial record as a chain full of zeros, which is a structurally valid
        // chain pointing at node 0.
        let mut bytes = std::fs::read(&spill.hdr).unwrap();
        bytes.truncate(bytes.len() - 1);
        std::fs::write(&spill.hdr, &bytes).unwrap();
        assert!(spill.chain_count().is_err());
        assert!(spill.read_all().is_err());
    }
}
