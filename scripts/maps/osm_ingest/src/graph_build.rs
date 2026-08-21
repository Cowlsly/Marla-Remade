//! Road-graph build: `.osm.pbf` -> `metadata.bin` / `nodes.bin` / `edges.bin` /
//! `lanes.bin` / `road_names.bin`.
//!
//! A faithful port of the former `scripts/maps/generator.cpp`, preserving the
//! on-disk contract byte for byte. The reader is
//! `maps/src/main/rust/src/graph.rs`, which is the authority for every layout
//! decision here:
//!
//! | File | Layout |
//! |---|---|
//! | `metadata.bin` | `u32 magic "MARG"`, `u32 version`, `u64 node_count` (16 B) |
//! | `nodes.bin` | `NodeMaster[node_count + 1]`: `i32 lat_e7, i32 lon_e7, u64 edge_ptr`; the trailing sentinel's `edge_ptr` is `edge_count` |
//! | `edges.bin` | `Edge[edge_count]`, **14 bytes each**: `u32 target, u32 dist_mm, u32 name_offset, u8 type, u8 speed_limit` |
//! | `lanes.bin` | `u64 byte_offsets[edge_count + 1]` then the packed `u16` mask blob |
//! | `road_names.bin` | deduped NUL-terminated string pool |
//!
//! `edge_count` is derived on device from `edges.bin`'s *file size*, so that file
//! must stay an exact multiple of 14 bytes.
//!
//! Two behavioural notes where this differs from the C++:
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

use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

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
const GRAPH_VERSION: u32 = 1;

/// Synthetic edges that reattach an isolated transit stop to the routable graph.
const RECONNECT_TYPE: u8 = 12;
const RECONNECT_SPEED: u8 = 5;

/// Sentinel in a `CachedWay`/`TmpEdge` lane offset meaning "no lane data".
const NO_LANES: u32 = 0xFFFF_FFFF;

/// Fallback stop code for a transit stop node with no `name`.
const UNNAMED_STOP: &[u8] = b"OSM_STOP";

pub struct Stats {
    pub node_count: u64,
    pub edge_count: u64,
    pub unique_names: usize,
    pub name_bytes: u32,
    pub lcc_size: u64,
    pub reconnected_stops: usize,
}

/// A plain (non-atomic) bitset. Bits are set from a single thread during the
/// merge and only read afterwards, so no atomics are needed.
struct Bitset {
    bits: Vec<u8>,
}

impl Bitset {
    fn new(size_bits: u64) -> Bitset {
        Bitset {
            bits: vec![0u8; (size_bits / 8 + 1) as usize],
        }
    }

    /// Returns true when this call is what set the bit.
    fn set(&mut self, idx: u64) -> bool {
        let byte = &mut self.bits[(idx / 8) as usize];
        let mask = 1u8 << (idx % 8);
        let was = *byte & mask != 0;
        *byte |= mask;
        !was
    }

    fn get(&self, idx: u64) -> bool {
        self.bits[(idx / 8) as usize] & (1u8 << (idx % 8)) != 0
    }
}

/// A routable way, cached from pass 1 so pass 3 can emit its edges without
/// re-reading the file.
#[derive(Clone)]
struct CachedWay {
    /// Pass 1 stores a chunk-local name id; the merge rewrites it to the pool
    /// offset (or `NO_NAME`).
    name: u32,
    first_ref: u64,
    ref_count: u32,
    type_: u8,
    speed_limit: u8,
    oneway: bool,
    fwd_lane_off: u32,
    fwd_lane_count: u16,
    bwd_lane_off: u32,
    bwd_lane_count: u16,
}

#[derive(Default)]
struct Pass1 {
    ways: Vec<CachedWay>,
    refs: Vec<i64>,
    lanes: Vec<u16>,
    names: LocalNames,
    stop_nodes: Vec<i64>,
}

#[derive(Clone, Copy)]
struct NodeTmp {
    spatial: u64,
    osm_id: i64,
    lat_e7: i32,
    lon_e7: i32,
    /// Chunk-local name id in pass 2, pool offset after the merge.
    stop_code: u32,
}

#[derive(Default)]
struct Pass2 {
    nodes: Vec<NodeTmp>,
    names: LocalNames,
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
}

pub fn build(input: &Path, out_dir: &Path) -> Result<Stats> {
    std::fs::create_dir_all(out_dir)
        .map_err(|e| Error(format!("cannot create {}: {e}", out_dir.display())))?;

    let blobs = pbf::scan_blobs(input)?;
    println!("Scanned {} data blob(s) in {}", blobs.len(), input.display());

    let mut pool = NamePool::new(BufWriter::new(create(&out_dir.join("road_names.bin"))?));

    // --- Pass 1: routable ways (cached) + transit stop nodes -----------------
    let (chunks, blob_kinds) = pbf::run_pass(
        input,
        &blobs,
        None,
        KIND_NODES | KIND_WAYS,
        "Pass 1: ways + stops",
        Pass1::default,
        pass1_blob,
    )?;

    let mut ways: Vec<CachedWay> = Vec::new();
    let mut way_refs: Vec<i64> = Vec::new();
    let mut lane_pool: Vec<u16> = Vec::new();
    let mut mask = Bitset::new(BITSET_SIZE);
    let mut node_count: u64 = 0;

    for chunk in chunks {
        let name_map = chunk.names.flush(&mut pool).map_err(io_err)?;
        let ref_base = way_refs.len() as u64;
        let lane_base = lane_pool.len() as u32;
        for mut w in chunk.ways {
            w.name = if w.name == u32::MAX {
                NO_NAME
            } else {
                name_map[w.name as usize]
            };
            w.first_ref += ref_base;
            if w.fwd_lane_count > 0 {
                w.fwd_lane_off += lane_base;
            }
            if w.bwd_lane_count > 0 {
                w.bwd_lane_off += lane_base;
            }
            ways.push(w);
        }
        for id in chunk.refs.iter().chain(chunk.stop_nodes.iter()) {
            if *id >= 0 && (*id as u64) < BITSET_SIZE && mask.set(*id as u64) {
                node_count += 1;
            }
        }
        way_refs.extend_from_slice(&chunk.refs);
        lane_pool.extend_from_slice(&chunk.lanes);
    }
    println!(
        "Cached {} routable way(s), {} node ref(s), {} graph node(s) expected",
        ways.len(),
        way_refs.len(),
        node_count
    );

    // --- Pass 2: coordinates and stop codes for the marked nodes -------------
    let (chunks, _) = pbf::run_pass(
        input,
        &blobs,
        Some(&blob_kinds),
        KIND_NODES,
        "Pass 2: node scan",
        Pass2::default,
        |state, block| pass2_blob(state, block, &mask),
    )?;

    let mut nodes: Vec<NodeTmp> = Vec::with_capacity(node_count as usize);
    for chunk in chunks {
        let name_map = chunk.names.flush(&mut pool).map_err(io_err)?;
        for mut n in chunk.nodes {
            if n.stop_code != u32::MAX {
                n.stop_code = name_map[n.stop_code as usize];
            } else {
                n.stop_code = NO_NAME;
            }
            nodes.push(n);
        }
    }
    drop(mask);
    let node_count = nodes.len() as u64;

    // Sort spatially so the device's Morton binary search works, then build the
    // osm_id -> local_id map the edge pass needs. The C++ used a hand-rolled
    // parallel LSD radix sort; `sort_by_key` is also stable, so ordering
    // semantics are unchanged.
    println!("Sorting {node_count} node(s) by spatial key...");
    nodes.sort_by_key(|n| n.spatial);
    let mut id_to_local: Vec<(i64, u32)> = nodes
        .iter()
        .enumerate()
        .map(|(i, n)| (n.osm_id, i as u32))
        .collect();
    id_to_local.sort_by_key(|e| e.0);

    // --- Pass 3: edges ------------------------------------------------------
    println!("Building edges from {} way(s)...", ways.len());
    let per_chunk = par::map_chunks(&ways, 4096, |_, chunk| {
        let mut out: Vec<TmpEdge> = Vec::new();
        for w in chunk {
            for i in 0..w.ref_count.saturating_sub(1) as usize {
                let u_osm = way_refs[w.first_ref as usize + i];
                let v_osm = way_refs[w.first_ref as usize + i + 1];
                let (u, v) = match (local_id(&id_to_local, u_osm), local_id(&id_to_local, v_osm)) {
                    (Some(u), Some(v)) => (u, v),
                    _ => continue,
                };
                let (a, b) = (&nodes[u as usize], &nodes[v as usize]);
                let dist = accurate_dist_mm(a.lat_e7, a.lon_e7, b.lat_e7, b.lon_e7);
                out.push(TmpEdge {
                    source: u,
                    target: v,
                    dist_mm: dist,
                    name_offset: w.name,
                    type_: w.type_,
                    speed_limit: w.speed_limit,
                    lane_off: w.fwd_lane_off,
                    lane_count: w.fwd_lane_count,
                });
                if !w.oneway {
                    out.push(TmpEdge {
                        source: v,
                        target: u,
                        dist_mm: dist,
                        name_offset: w.name,
                        type_: w.type_,
                        speed_limit: w.speed_limit,
                        lane_off: w.bwd_lane_off,
                        lane_count: w.bwd_lane_count,
                    });
                }
            }
        }
        out
    });
    let mut edges: Vec<TmpEdge> = per_chunk.into_iter().flatten().collect();
    drop(id_to_local);
    drop(ways);
    drop(way_refs);
    println!("Built {} edge(s), sorting by source...", edges.len());
    edges.sort_by_key(|e| e.source);

    // --- Reconnect isolated transit stops -----------------------------------
    let (lcc_size, reconnected) = reconnect_isolated_stops(&nodes, &mut edges);

    // --- Write --------------------------------------------------------------
    let edge_count = write_graph(out_dir, &nodes, &edges, &lane_pool)?;
    let unique_names = pool.unique_count();
    let name_bytes = pool.byte_len();
    pool.finish().map_err(io_err)?;

    println!("Done. Nodes: {node_count}  Edges: {edge_count}");
    Ok(Stats {
        node_count,
        edge_count,
        unique_names,
        name_bytes,
        lcc_size,
        reconnected_stops: reconnected,
    })
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
                    let type_ = tags::get_hw_id(w.tags.get_str("highway"));
                    if type_ == 0 {
                        return Ok(());
                    }
                    let name = match w.tags.get("name") {
                        Some(n) => state.names.id(n),
                        None => u32::MAX,
                    };
                    let speed_limit = tags::parse_maxspeed(w.tags.get_str("maxspeed"));
                    let oneway = w.tags.get_str("oneway") == Some("yes");

                    // Forward lanes come from turn:lanes:forward, or from plain
                    // turn:lanes on a oneway; backward from turn:lanes:backward.
                    // Plain `lanes*` only refine the count. No turn:lanes at all
                    // means no lane data, and the router infers lanes from
                    // junction topology instead.
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

                    let mut push_lanes = |lanes: &[u16]| -> (u32, u16) {
                        if lanes.is_empty() {
                            return (NO_LANES, 0);
                        }
                        let off = state.lanes.len() as u32;
                        state.lanes.extend_from_slice(lanes);
                        (off, lanes.len() as u16)
                    };
                    let (fwd_lane_off, fwd_lane_count) = push_lanes(&fwd);
                    let (bwd_lane_off, bwd_lane_count) = push_lanes(&bwd);

                    let first_ref = state.refs.len() as u64;
                    state.refs.extend_from_slice(w.refs);
                    state.ways.push(CachedWay {
                        name,
                        first_ref,
                        ref_count: w.refs.len() as u32,
                        type_,
                        speed_limit,
                        oneway,
                        fwd_lane_off,
                        fwd_lane_count,
                        bwd_lane_off,
                        bwd_lane_count,
                    });
                }
                Element::Relation(_) => {}
            }
            Ok(())
        },
    )?;
    Ok(kinds)
}

fn pass2_blob(state: &mut Pass2, block: &pbf::PrimitiveBlock, mask: &Bitset) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_NODES, &mut kinds, &mut |el: Element| {
        if let Element::Node(n) = el {
            if n.id < 0 || n.id as u64 >= BITSET_SIZE || !mask.get(n.id as u64) {
                return Ok(());
            }
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
                Some(code) => state.names.id(code),
                None => {
                    if tags::is_stop_node(
                        n.tags.get_str("highway"),
                        n.tags.get_str("railway"),
                        n.tags.get_str("public_transport"),
                    ) {
                        state.names.id(n.tags.get("name").unwrap_or(UNNAMED_STOP))
                    } else {
                        u32::MAX
                    }
                }
            };
            state.nodes.push(NodeTmp {
                spatial: spatial_from_e7(n.lat_e7, n.lon_e7),
                osm_id: n.id,
                lat_e7: n.lat_e7,
                lon_e7: n.lon_e7,
                stop_code,
            });
        }
        Ok(())
    })?;
    Ok(kinds)
}

fn local_id(mapping: &[(i64, u32)], osm_id: i64) -> Option<u32> {
    mapping
        .binary_search_by_key(&osm_id, |e| e.0)
        .ok()
        .map(|i| mapping[i].1)
}

/// Find the largest connected component, then hook every transit stop outside it
/// to its nearest node inside it with a synthetic bidirectional edge. Without
/// this a matched GTFS stop that OSM never attached to a road is unreachable and
/// the transit planner cannot get the user to it.
///
/// Returns `(lcc_size, edges_added / 2)`.
fn reconnect_isolated_stops(nodes: &[NodeTmp], edges: &mut Vec<TmpEdge>) -> (u64, usize) {
    let n = nodes.len();
    if n == 0 {
        return (0, 0);
    }
    println!("Identifying connected components...");

    // `edges` is sorted by source, so one linear cursor yields the CSR ranges.
    // u64 (not u32) so a graph past 4.29 G directed edges fails loudly rather than
    // silently truncating the ranges the component scan walks.
    let mut node_to_edge = vec![0u64; n + 1];
    let mut cursor = 0usize;
    for (i, slot) in node_to_edge.iter_mut().enumerate().take(n) {
        *slot = cursor as u64;
        while cursor < edges.len() && edges[cursor].source as usize == i {
            cursor += 1;
        }
    }
    node_to_edge[n] = cursor as u64;

    let mut component = vec![u32::MAX; n];
    let mut queue: Vec<u32> = Vec::with_capacity(n);
    let mut lcc = 0u32;
    let mut lcc_size = 0usize;
    let mut next_comp = 0u32;
    for start in 0..n {
        if component[start] != u32::MAX {
            continue;
        }
        let base = queue.len();
        queue.push(start as u32);
        component[start] = next_comp;
        let mut head = base;
        while head < queue.len() {
            let u = queue[head] as usize;
            head += 1;
            for e in node_to_edge[u]..node_to_edge[u + 1] {
                let v = edges[e as usize].target as usize;
                if component[v] == u32::MAX {
                    component[v] = next_comp;
                    queue.push(v as u32);
                }
            }
        }
        let size = queue.len() - base;
        if size > lcc_size {
            lcc_size = size;
            lcc = next_comp;
        }
        next_comp += 1;
    }
    println!("LCC {lcc}: {lcc_size} / {n} node(s)");

    let isolated: Vec<u32> = (0..n as u32)
        .filter(|i| nodes[*i as usize].stop_code != NO_NAME && component[*i as usize] != lcc)
        .collect();
    if isolated.is_empty() {
        return (lcc_size as u64, 0);
    }

    // The nodes are Morton-sorted, so index distance approximates spatial
    // distance: widen an index window around the stop until it contains an LCC
    // node, then take the closest one in that window.
    let found = par::map_chunks(&isolated, 256, |_, chunk| {
        let mut out: Vec<TmpEdge> = Vec::new();
        let mut failed = 0usize;
        for &i in chunk {
            let stop = &nodes[i as usize];
            let mut best: Option<(u32, u32)> = None;
            let mut radius = 1000usize;
            while radius <= 1_000_000 {
                let lo = (i as usize).saturating_sub(radius);
                let hi = (i as usize + radius).min(n);
                for j in lo..hi {
                    if component[j] != lcc {
                        continue;
                    }
                    let d = accurate_dist_mm(
                        stop.lat_e7,
                        stop.lon_e7,
                        nodes[j].lat_e7,
                        nodes[j].lon_e7,
                    );
                    if best.is_none_or(|(bd, _)| d < bd) {
                        best = Some((d, j as u32));
                    }
                }
                if best.is_some() {
                    break;
                }
                radius *= 10;
            }
            if let Some((dist, target)) = best {
                out.push(TmpEdge {
                    source: i,
                    target,
                    dist_mm: dist,
                    name_offset: NO_NAME,
                    type_: RECONNECT_TYPE,
                    speed_limit: RECONNECT_SPEED,
                    lane_off: NO_LANES,
                    lane_count: 0,
                });
                out.push(TmpEdge {
                    source: target,
                    target: i,
                    dist_mm: dist,
                    name_offset: NO_NAME,
                    type_: RECONNECT_TYPE,
                    speed_limit: RECONNECT_SPEED,
                    lane_off: NO_LANES,
                    lane_count: 0,
                });
            } else {
                failed += 1;
            }
        }
        (out, failed)
    });

    let unreachable: usize = found.iter().map(|(_, f)| f).sum();
    let new_edges: Vec<TmpEdge> = found.into_iter().flat_map(|(e, _)| e).collect();
    let added = new_edges.len() / 2;
    if unreachable > 0 {
        // The index window caps at 1e6 nodes, so a stop in a region with no
        // routable road within that window stays unreachable. Say so rather than
        // dropping it silently: an unreachable stop is exactly what this pass
        // exists to prevent.
        println!("WARNING: {unreachable} isolated stop(s) had no reachable road nearby");
    }
    if added > 0 {
        println!("Adding {} synthetic connection(s) for isolated stops", new_edges.len());
        edges.extend_from_slice(&new_edges);
        edges.sort_by_key(|e| e.source);
    }
    (lcc_size as u64, added)
}

fn write_graph(
    out_dir: &Path,
    nodes: &[NodeTmp],
    edges: &[TmpEdge],
    lane_pool: &[u16],
) -> Result<u64> {
    println!("Writing the single global graph...");
    let mut nodes_out = BufWriter::new(create(&out_dir.join("nodes.bin"))?);
    let mut edges_out = BufWriter::new(create(&out_dir.join("edges.bin"))?);
    let mut lanes_out = BufWriter::new(create(&out_dir.join("lanes.bin"))?);

    let mut lane_offsets: Vec<u64> = Vec::with_capacity(edges.len() + 1);
    let mut lane_blob: Vec<u16> = Vec::new();
    let mut edge_ptr: u64 = 0;
    let mut cursor = 0usize;

    for (lid, node) in nodes.iter().enumerate() {
        write_node(&mut nodes_out, node.lat_e7, node.lon_e7, edge_ptr).map_err(io_err)?;
        while cursor < edges.len() && edges[cursor].source as usize == lid {
            let e = &edges[cursor];
            write_edge(&mut edges_out, e).map_err(io_err)?;
            lane_offsets.push(lane_blob.len() as u64 * 2);
            if e.lane_count > 0 && e.lane_off != NO_LANES {
                let start = e.lane_off as usize;
                lane_blob.extend_from_slice(&lane_pool[start..start + e.lane_count as usize]);
            }
            edge_ptr += 1;
            cursor += 1;
        }
    }
    // Trailing sentinel so `graph.rs` can read node(v + 1).edge_ptr as the end of
    // node v's edge range.
    write_node(&mut nodes_out, 0, 0, edge_ptr).map_err(io_err)?;

    lane_offsets.push(lane_blob.len() as u64 * 2);
    for off in &lane_offsets {
        lanes_out.write_all(&off.to_le_bytes()).map_err(io_err)?;
    }
    for mask in &lane_blob {
        lanes_out.write_all(&mask.to_le_bytes()).map_err(io_err)?;
    }

    nodes_out.flush().map_err(io_err)?;
    edges_out.flush().map_err(io_err)?;
    lanes_out.flush().map_err(io_err)?;

    // metadata.bin's magic + version let the device refuse a pack directory
    // holding files from two different vintages instead of misreading it.
    let mut meta = create(&out_dir.join("metadata.bin"))?;
    meta.write_all(&GRAPH_MAGIC.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&GRAPH_VERSION.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&(nodes.len() as u64).to_le_bytes())
        .map_err(io_err)?;
    meta.flush().map_err(io_err)?;

    Ok(edge_ptr)
}

fn write_node<W: Write>(out: &mut W, lat_e7: i32, lon_e7: i32, edge_ptr: u64) -> std::io::Result<()> {
    out.write_all(&lat_e7.to_le_bytes())?;
    out.write_all(&lon_e7.to_le_bytes())?;
    out.write_all(&edge_ptr.to_le_bytes())
}

fn write_edge<W: Write>(out: &mut W, e: &TmpEdge) -> std::io::Result<()> {
    out.write_all(&e.target.to_le_bytes())?;
    out.write_all(&e.dist_mm.to_le_bytes())?;
    out.write_all(&e.name_offset.to_le_bytes())?;
    out.write_all(&[e.type_, e.speed_limit])
}

fn create(path: &Path) -> Result<File> {
    File::create(path).map_err(|e| Error(format!("cannot write {}: {e}", path.display())))
}

fn io_err(e: std::io::Error) -> Error {
    Error(e.to_string())
}

/// Parse the tool's command line: `road_graph IN.osm.pbf [--out DIR]`.
pub fn parse_args(args: &[String]) -> std::result::Result<(PathBuf, PathBuf), String> {
    let mut input: Option<PathBuf> = None;
    let mut out = PathBuf::from("map_data");
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--out" | "-o" => {
                i += 1;
                let dir = args.get(i).ok_or_else(|| "--out needs a directory".to_string())?;
                out = PathBuf::from(dir);
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
    Ok((input.ok_or_else(|| "missing IN.osm.pbf".to_string())?, out))
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
    }

    fn read_outputs(dir: &Path) -> Outputs {
        let f = |n: &str| std::fs::read(dir.join(n)).unwrap();
        Outputs {
            meta: f("metadata.bin"),
            nodes: f("nodes.bin"),
            edges: f("edges.bin"),
            lanes: f("lanes.bin"),
            names: f("road_names.bin"),
        }
    }

    fn node_at(o: &Outputs, i: usize) -> (i32, i32, u64) {
        let b = &o.nodes[i * 16..i * 16 + 16];
        (
            i32::from_le_bytes(b[0..4].try_into().unwrap()),
            i32::from_le_bytes(b[4..8].try_into().unwrap()),
            u64::from_le_bytes(b[8..16].try_into().unwrap()),
        )
    }

    fn edge_at(o: &Outputs, i: usize) -> (u32, u32, u32, u8, u8) {
        let b = &o.edges[i * 14..i * 14 + 14];
        (
            u32::from_le_bytes(b[0..4].try_into().unwrap()),
            u32::from_le_bytes(b[4..8].try_into().unwrap()),
            u32::from_le_bytes(b[8..12].try_into().unwrap()),
            b[12],
            b[13],
        )
    }

    fn name_at(o: &Outputs, off: u32) -> String {
        let start = off as usize;
        let end = start + o.names[start..].iter().position(|b| *b == 0).unwrap();
        String::from_utf8(o.names[start..end].to_vec()).unwrap()
    }

    #[test]
    fn synthetic_extract_produces_the_documented_layout() {
        let (pbf_path, dir) = testpbf::write_sample("graph_build");
        let stats = build(&pbf_path, &dir).unwrap();
        let o = read_outputs(&dir);

        // Nodes 1-4 come from the residential way, node 5 is the bus stop; the
        // cafe node (6) is on no routable way and carries no stop tag, so it is
        // not part of the graph at all.
        assert_eq!(stats.node_count, 5);
        assert_eq!(o.meta, {
            let mut want = Vec::new();
            want.extend(0x4752_414Du32.to_le_bytes());
            want.extend(1u32.to_le_bytes());
            want.extend(5u64.to_le_bytes());
            want
        });

        // nodes.bin holds node_count + 1 records; edges.bin is a multiple of 14.
        assert_eq!(o.nodes.len(), 16 * 6);
        assert_eq!(o.edges.len() % 14, 0);
        let edge_count = (o.edges.len() / 14) as u64;
        assert_eq!(edge_count, stats.edge_count);
        // 3 bidirectional segments + 1 oneway segment + 2 synthetic stop edges.
        assert_eq!(edge_count, 6 + 1 + 2);

        // The sentinel's edge_ptr is the edge count, and edge_ptr is monotonic.
        assert_eq!(node_at(&o, 5).2, edge_count);
        assert_eq!(node_at(&o, 0).2, 0);
        for i in 0..5 {
            assert!(node_at(&o, i).2 <= node_at(&o, i + 1).2);
        }

        // Nodes are Morton-ordered.
        let keys: Vec<u64> = (0..5)
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
        for lid in 0..5usize {
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
                seen.push((slat, slon, tlat, tlon, dist, type_, speed, name));
            }
        }
        assert_eq!(seen.len(), edge_count as usize);

        // Main St: 3 segments each way, type 7 (residential), no maxspeed.
        let main: Vec<_> = seen.iter().filter(|e| e.7 == "Main St").collect();
        assert_eq!(main.len(), 6);
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
        assert_eq!(stats.lcc_size, 4);

        // road_names.bin holds each unique string once. "Main St" is the only way
        // name; "Test Stop" is the bus stop's code. "Plaza" belongs to an
        // unrouted area and must not appear.
        assert_eq!(o.names, b"Main St\0Test Stop\0".to_vec());
        assert_eq!(stats.unique_names, 2);
        assert_eq!(stats.name_bytes, 18);

        // lanes.bin: u64 offsets[edge_count + 1] then the u16 blob. Only Main
        // St's forward direction carries turn:lanes.
        let offsets_bytes = (edge_count as usize + 1) * 8;
        assert!(o.lanes.len() >= offsets_bytes);
        let offsets: Vec<u64> = (0..=edge_count as usize)
            .map(|i| u64::from_le_bytes(o.lanes[i * 8..i * 8 + 8].try_into().unwrap()))
            .collect();
        assert!(offsets.windows(2).all(|w| w[0] <= w[1]));
        assert_eq!(*offsets.last().unwrap() as usize, o.lanes.len() - offsets_bytes);
        let blob: Vec<u16> = o.lanes[offsets_bytes..]
            .chunks_exact(2)
            .map(|c| u16::from_le_bytes(c.try_into().unwrap()))
            .collect();
        // Three forward Main St edges, two lane masks each.
        assert_eq!(blob, [[LANE_LEFT, LANE_THROUGH]; 3].concat());
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
        ] {
            assert_eq!(
                std::fs::read(dir_a.join(f)).unwrap(),
                std::fs::read(dir_b.join(f)).unwrap(),
                "{f} differs between runs"
            );
        }
    }

    #[test]
    fn args_default_the_output_directory() {
        let (input, out) = parse_args(&["cal.osm.pbf".into()]).unwrap();
        assert_eq!(input, PathBuf::from("cal.osm.pbf"));
        assert_eq!(out, PathBuf::from("map_data"));
        let (_, out) = parse_args(&["cal.osm.pbf".into(), "--out".into(), "d".into()]).unwrap();
        assert_eq!(out, PathBuf::from("d"));
        assert!(parse_args(&[]).is_err());
        assert!(parse_args(&["a".into(), "b".into()]).is_err());
        assert!(parse_args(&["--wat".into()]).is_err());
    }
}
