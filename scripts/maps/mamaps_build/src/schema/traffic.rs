//! `traffic`: one line per drivable **component** segment of the v6 routing graph.
//!
//! The one layer whose geometry is not in the `.osm.pbf` and not in a prepared product either: it
//! is read straight from the app's v6 routing graph — `metadata.bin`, `nodes.bin`, `edges.bin` and
//! `intermediate.bin`, produced by `scripts/maps/osm_ingest`'s `road_graph`. Building from the
//! graph is what makes every feature's `component_id` match, byte for byte and with no coordination
//! file, the ids the traffic server computes from the same graph. The authoritative spec is
//! `analysis/traffic_id_contract.md`; the reader below mirrors `maps/src/main/rust/src/graph.rs`.
//!
//! # What one feature is
//!
//! A big edge's polyline is `source_node -> interior points -> target_node`. A **component**
//! segment is one consecutive vertex pair of that polyline, and each becomes one `GEOM_LINE`
//! feature carrying `component_id = (big_edge_id << 16) | seg_index` in the body's id side table.
//! The tiler does not coalesce this layer, so its features stay one-to-one with those ids.
//!
//! # Emit-each-segment-once (dedup), verbatim from the contract §3
//!
//! A two-way road is two directed edges over the same ground. To draw it once — and, more
//! importantly, so the archive and the server pick the **same** directed `big_edge_id` for a
//! physical segment — this canonicalises identically to WS1:
//!
//! 1. Skip any edge with `REVERSE_GEOMETRY_FLAG` (`0x40`): it is the reverse twin of a *curved*
//!    two-way road and stores no geometry of its own; its non-flag twin owns the shape.
//! 2. A straight two-way chord stores no blob and the flag is set on neither direction, so step 1
//!    alone would emit both. For an edge with no stored geometry that has a drivable twin, emit
//!    only the canonical direction (`source_idx < target_idx`). A genuine one-way has no twin, so
//!    it is emitted regardless.
//!
//! # Drivable
//!
//! An edge is drivable iff `(type_ & 0x3F)` is in `1..=9` (motorway..living_street). The road-class
//! bits are masked before the range test so the reverse-geometry flag never trips it.

use std::path::Path;

use osm_ingest::proto::{err, Result};
use tile_build::geom::Geometry;
use tilecodec::mamaps::dict::{self, LAYER_TRAFFIC};

use super::Class;
use crate::store::Sink;

/// The zoom the traffic overlay first surfaces at.
///
/// Component lines are dense — roughly the whole drivable network as one line per segment — so a
/// traffic overlay only earns its bytes where a street network reads at all. z12 is the shallowest
/// zoom a city's arterials are legible; below it the overlay would be a red smear. Raise it to trim
/// the archive further, lower it to show motorway congestion sooner. The store packs `min_zoom` in
/// five bits, so anything up to 31 is representable.
pub const MIN_ZOOM: u8 = 12;

/// Bits reserved for the component-segment index inside a packed component id. Shared on-disk/wire
/// contract with the server and the device — keep in sync with `graph.rs::COMPONENT_SEG_BITS` and
/// `analysis/traffic_id_contract.md` §1.
pub const COMPONENT_SEG_BITS: u32 = 16;
pub const COMPONENT_SEG_MASK: u64 = (1 << COMPONENT_SEG_BITS) - 1;

/// `component_id = (big_edge_id << 16) | seg_index`.
#[inline]
pub fn pack_component_id(big_edge_id: u64, seg_index: u32) -> u64 {
    debug_assert!(u64::from(seg_index) <= COMPONENT_SEG_MASK);
    (big_edge_id << COMPONENT_SEG_BITS) | u64::from(seg_index)
}

/// The inverse of [`pack_component_id`].
#[inline]
pub fn unpack_component_id(component_id: u64) -> (u64, u32) {
    (component_id >> COMPONENT_SEG_BITS, (component_id & COMPONENT_SEG_MASK) as u32)
}

/// The class every traffic segment carries: layer `traffic`, no kind and no detail.
///
/// No `kind`/`kind_detail`, because the layer is not drawn by the basemap style — it is filtered on
/// and recoloured by the renderer from a pushed id→speed table, keyed on the `component_id` in the
/// body's id side table rather than on any interned attribute. Keeping it out of [`dict::KINDS`]
/// avoids disturbing the frozen kind table for a value nothing matches by name.
pub fn traffic_class() -> Class {
    Class {
        layer: LAYER_TRAFFIC,
        kind: dict::NONE,
        kind_detail: dict::NONE,
        flags: 0,
        area: false,
        min_zoom: MIN_ZOOM,
        min_area_px: 0.0,
    }
}

// --- v6 graph on-disk constants (see traffic_id_contract.md §2 / graph.rs) -----------------

const MARG_MAGIC: u32 = 0x4752_414D; // "MARG"
const GRAPH_VERSION: u32 = 6;
/// One escape block index per 1024 edges (`graph.rs` ESCAPE_BLOCK).
const ESCAPE_BLOCK: u64 = 1024;
/// One coarse geometry offset per 32 geometry edges (`graph.rs` INTERMEDIATE_BLOCK).
const INTERMEDIATE_BLOCK: u64 = 32;
/// One rank word per 512 edges = 64 present bytes (`graph.rs` RANK_BLOCK_BYTES).
const RANK_BLOCK_BYTES: usize = 64;
/// `type_` bit 6: this edge stores no geometry of its own; its twin runs `target -> source`.
const REVERSE_GEOMETRY_FLAG: u8 = 0x40;
/// The low six bits of `type_` are the road class.
pub(crate) const ROAD_TYPE_MASK: u8 = 0x3F;
/// A `target_delta` of `i16::MIN` means the true target is in the escape table.
const TARGET_DELTA_ESCAPE: i16 = i16::MIN;
/// A `dist` u24 of all-ones means the true target and distance are in the escape table.
const DIST_MM_ESCAPE: u32 = 0x00FF_FFFF;
/// The most vertices a big edge's polyline holds (`geom.rs` MAX_POINTS); the reader stops here.
const MAX_POINTS: usize = 256;

fn u16_at(buf: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([buf[at], buf[at + 1]])
}
fn i16_at(buf: &[u8], at: usize) -> i16 {
    i16::from_le_bytes([buf[at], buf[at + 1]])
}
fn u32_at(buf: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([buf[at], buf[at + 1], buf[at + 2], buf[at + 3]])
}
fn i32_at(buf: &[u8], at: usize) -> i32 {
    i32::from_le_bytes([buf[at], buf[at + 1], buf[at + 2], buf[at + 3]])
}
fn u64_at(buf: &[u8], at: usize) -> u64 {
    u64::from_le_bytes([
        buf[at],
        buf[at + 1],
        buf[at + 2],
        buf[at + 3],
        buf[at + 4],
        buf[at + 5],
        buf[at + 6],
        buf[at + 7],
    ])
}

fn align_up8(n: usize) -> usize {
    (n + 7) & !7
}

/// Bytes an [`EdgeBitmap`] over `edges` edges occupies: a `u64` rank word per 512 edges plus one
/// present byte per 8 edges (`graph.rs` `EdgeBitmap`).
fn present_offset(edges: u64) -> usize {
    ((edges.div_ceil(512) + 1) * 8) as usize
}
fn edge_bitmap_bytes(edges: u64) -> usize {
    present_offset(edges) + edges.div_ceil(8) as usize
}

/// The subset of the v6 graph reader the traffic layer needs: node coordinates, edge targets and
/// types, and big-edge geometry. Names, distances and speed limits are skipped — the server reads
/// those; the archive carries only geometry and the id.
///
/// `pub(crate)` because [`crate::schema::junction`] reads the same four files for the same reason
/// and must not carry a second copy of this byte layout.
pub(crate) struct Graph {
    nodes: Vec<u8>,
    edges: Vec<u8>,
    inter: Vec<u8>,
    pub(crate) node_count: u64,
    escape_first_off: usize,
    escapes_off: usize,
    // intermediate.bin sub-table offsets
    geom_rank_off: usize,
    geom_present_off: usize,
    coarse_off: usize,
    within_off: usize,
}

impl Graph {
    pub(crate) fn load(dir: &Path) -> Result<Graph> {
        let read = |name: &str| -> Result<Vec<u8>> {
            let path = dir.join(name);
            std::fs::read(&path)
                .map_err(|e| osm_ingest::proto::Error(format!("cannot read {}: {e}", path.display())))
        };
        let meta = read("metadata.bin")?;
        if meta.len() < 40 {
            return err(format!("metadata.bin is {} bytes, a v6 MARG header is 40", meta.len()));
        }
        if u32_at(&meta, 0) != MARG_MAGIC {
            return err("metadata.bin is not a MARG graph header".to_string());
        }
        let version = u32_at(&meta, 4);
        if version != GRAPH_VERSION {
            return err(format!(
                "the graph is version {version}; the traffic layer reads v{GRAPH_VERSION}"
            ));
        }
        let node_count = u64_at(&meta, 8);
        let edge_count = u64_at(&meta, 16);
        let escape_count = u64_at(&meta, 24);
        if edge_count > u32::MAX as u64 {
            return err(format!("edge_count {edge_count} does not fit u32"));
        }
        if escape_count > edge_count {
            return err(format!(
                "escape_count {escape_count} exceeds edge_count {edge_count}"
            ));
        }

        let nodes = read("nodes.bin")?;
        let want_nodes = (node_count + 1)
            .checked_mul(12)
            .ok_or_else(|| osm_ingest::proto::Error("node table size overflows".to_string()))?;
        if nodes.len() as u64 != want_nodes {
            return err(format!(
                "nodes.bin is {} bytes, expected {} for {node_count}+1 records",
                nodes.len(),
                want_nodes,
            ));
        }

        let edges = read("edges.bin")?;
        let escape_first_off = align_up8((edge_count as usize) * 7);
        let escape_blocks = edge_count.div_ceil(ESCAPE_BLOCK) + 1;
        let escapes_off = escape_first_off + (escape_blocks as usize) * 4;
        let want_edges_min = escapes_off + (escape_count as usize) * 12;
        if edges.len() < want_edges_min {
            return err(format!(
                "edges.bin is {} bytes, too short for {edge_count} edges and {escape_count} escapes",
                edges.len(),
            ));
        }

        let inter = read("intermediate.bin")?;
        if inter.len() < 8 {
            return err("intermediate.bin is too short to hold its geometry-edge count".to_string());
        }
        let g_edges = u64_at(&inter, inter.len() - 8);
        if g_edges > edge_count {
            return err(format!(
                "intermediate.bin claims {g_edges} geometry edges, past the {edge_count} in the graph"
            ));
        }
        let bitmap_bytes = edge_bitmap_bytes(edge_count);
        let coarse_bytes = ((g_edges.div_ceil(INTERMEDIATE_BLOCK) + 1) * 8) as usize;
        let within_bytes = ((g_edges + 1) * 2) as usize;
        let trailer = bitmap_bytes + coarse_bytes + within_bytes + 8;
        if inter.len() < trailer {
            return err(format!(
                "intermediate.bin is {} bytes, too short for its {trailer}-byte trailer",
                inter.len(),
            ));
        }
        let blob_bytes = inter.len() - trailer;
        let geom_rank_off = blob_bytes;
        let geom_present_off = blob_bytes + present_offset(edge_count);
        let coarse_off = blob_bytes + bitmap_bytes;
        let within_off = coarse_off + coarse_bytes;

        Ok(Graph {
            nodes,
            edges,
            inter,
            node_count,
            escape_first_off,
            escapes_off,
            geom_rank_off,
            geom_present_off,
            coarse_off,
            within_off,
        })
    }

    /// A node's `(lat_e7, lon_e7)`.
    pub(crate) fn node(&self, n: u64) -> (i32, i32) {
        let base = (n as usize) * 12;
        (i32_at(&self.nodes, base), i32_at(&self.nodes, base + 4))
    }

    /// A node's first outgoing edge index. The sentinel record (index `node_count`) holds
    /// `edge_count`, so `edge_ptr(n+1)` is always defined for a real node `n`.
    pub(crate) fn edge_ptr(&self, n: u64) -> u32 {
        u32_at(&self.nodes, (n as usize) * 12 + 8)
    }

    pub(crate) fn edge_type(&self, idx: u32) -> u8 {
        self.edges[(idx as usize) * 7 + 5]
    }

    /// The target node of edge `idx` whose source is `s`, decoding the escape table when the
    /// delta or distance is sentinelled.
    pub(crate) fn edge_target(&self, idx: u32, s: u32) -> Result<u32> {
        let base = (idx as usize) * 7;
        let delta = i16_at(&self.edges, base);
        let dist = (self.edges[base + 2] as u32)
            | ((self.edges[base + 3] as u32) << 8)
            | ((self.edges[base + 4] as u32) << 16);
        if delta == TARGET_DELTA_ESCAPE || dist == DIST_MM_ESCAPE {
            self.escape_target(idx)
        } else {
            Ok(s.wrapping_add_signed(delta as i32))
        }
    }

    fn escape_target(&self, idx: u32) -> Result<u32> {
        let block = (idx as u64 / ESCAPE_BLOCK) as usize;
        let lo = u32_at(&self.edges, self.escape_first_off + block * 4);
        let hi = u32_at(&self.edges, self.escape_first_off + (block + 1) * 4);
        for r in lo..hi {
            let rb = self.escapes_off + (r as usize) * 12;
            if u32_at(&self.edges, rb) == idx {
                return Ok(u32_at(&self.edges, rb + 4));
            }
        }
        err(format!("edge {idx} is escaped but has no escape row"))
    }

    /// Does edge `idx` store an interior-point blob?
    pub(crate) fn geom_contains(&self, idx: u32) -> bool {
        let byte = self.geom_present_off + (idx as usize) / 8;
        self.inter[byte] & (1u8 << (idx % 8)) != 0
    }

    /// The rank of edge `idx` among the geometry-carrying edges: the number of set bits strictly
    /// below it in the presence bitmap.
    fn geom_rank(&self, idx: u32) -> u64 {
        let byte = (idx as usize) / 8;
        let block = byte / RANK_BLOCK_BYTES;
        let mut n = u64_at(&self.inter, self.geom_rank_off + block * 8);
        for b in (block * RANK_BLOCK_BYTES)..byte {
            n += self.inter[self.geom_present_off + b].count_ones() as u64;
        }
        let mask = ((1u16 << (idx % 8)) - 1) as u8;
        n += (self.inter[self.geom_present_off + byte] & mask).count_ones() as u64;
        n
    }

    /// The blob byte offset of the `g`th geometry edge: `coarse[g/32] + within[g]`.
    fn geom_offset(&self, g: u64) -> u64 {
        let coarse = u64_at(&self.inter, self.coarse_off + (g / INTERMEDIATE_BLOCK) as usize * 8);
        let within = u16_at(&self.inter, self.within_off + (g as usize) * 2) as u64;
        coarse + within
    }

    /// The `(start, end)` byte range of edge `idx`'s interior-point blob, or `None` when it stores
    /// no geometry (a straight chord).
    fn intermediate_range(&self, idx: u32) -> Option<(usize, usize)> {
        if !self.geom_contains(idx) {
            return None;
        }
        let g = self.geom_rank(idx);
        Some((self.geom_offset(g) as usize, self.geom_offset(g + 1) as usize))
    }

    /// The full polyline of edge `idx` in canonical `source -> target` order, as `(lat_e7, lon_e7)`
    /// vertices: the source node, any stored interior points, then the target node.
    pub(crate) fn polyline(&self, idx: u32, s: u32, t: u32) -> Vec<(i32, i32)> {
        let (slat, slon) = self.node(s as u64);
        let (tlat, tlon) = self.node(t as u64);
        let mut out = Vec::with_capacity(4);
        out.push((slat, slon));
        if let Some((start, end)) = self.intermediate_range(idx) {
            let (mut lat, mut lon) = (slat, slon);
            let mut off = start;
            // count starts at 1 for the source; the reader stops at MAX_POINTS total, matching
            // `graph.rs::decode_edge_coords`.
            let mut count = 1usize;
            while off + 4 <= end && count + 1 < MAX_POINTS {
                let d_lat = i16_at(&self.inter, off) as i32;
                let d_lon = i16_at(&self.inter, off + 2) as i32;
                lat = lat.wrapping_add(d_lat);
                lon = lon.wrapping_add(d_lon);
                out.push((lat, lon));
                count += 1;
                off += 4;
            }
        }
        out.push((tlat, tlon));
        out
    }

    /// Is there a drivable edge running `from -> to`? Used to tell a straight two-way chord (which
    /// has such a twin and is emitted only in the canonical direction) from a one-way (which has
    /// none and is always emitted).
    pub(crate) fn has_drivable_twin(&self, from: u32, to: u32) -> Result<bool> {
        let lo = self.edge_ptr(from as u64);
        let hi = self.edge_ptr(from as u64 + 1);
        for e in lo..hi {
            let rc = self.edge_type(e) & ROAD_TYPE_MASK;
            if (1..=9).contains(&rc) && self.edge_target(e, from)? == to {
                return Ok(true);
            }
        }
        Ok(false)
    }
}

/// Read the v6 graph at `dir` and push one line feature per drivable component segment into `sink`,
/// each carrying its packed `component_id`. Returns the number of segments emitted.
///
/// Modelled on [`crate::schema::earth::stream_prepared`] and
/// [`crate::schema::transit::stream_routes`]: a source of geometry outside the `.osm.pbf`, read
/// after the OSM passes and streamed straight into the feature sink. Unlike those two it is not
/// clipped to a bbox here — the graph is already the built region, and the tiler clips each segment
/// per tile like any other line.
pub fn stream_graph(dir: &Path, sink: &mut Sink) -> Result<u64> {
    let graph = Graph::load(dir)?;
    let class = traffic_class();
    let mut emitted = 0u64;
    for n in 0..graph.node_count {
        let s = n as u32;
        let lo = graph.edge_ptr(n);
        let hi = graph.edge_ptr(n + 1);
        for idx in lo..hi {
            let ty = graph.edge_type(idx);
            // Step 1: the reverse twin of a curved two-way owns no geometry; its forward twin does.
            if ty & REVERSE_GEOMETRY_FLAG != 0 {
                continue;
            }
            // A driving overlay: motorway..living_street only.
            let rc = ty & ROAD_TYPE_MASK;
            if !(1..=9).contains(&rc) {
                continue;
            }
            let target = graph.edge_target(idx, s)?;
            if target as u64 >= graph.node_count {
                return err(format!(
                    "edge {idx} targets node {target}, past the {} in the graph",
                    graph.node_count,
                ));
            }
            // Step 2: a straight two-way chord is emitted only in the canonical direction; a
            // one-way (no drivable twin back) is always emitted.
            if !graph.geom_contains(idx) && graph.has_drivable_twin(target, s)? && s >= target {
                continue;
            }
            let verts = graph.polyline(idx, s, target);
            for seg in 0..verts.len().saturating_sub(1) {
                let (a_lat, a_lon) = verts[seg];
                let (b_lat, b_lon) = verts[seg + 1];
                // A zero-length segment draws nothing and would only cost a feature and an id.
                if a_lat == b_lat && a_lon == b_lon {
                    continue;
                }
                let component_id = pack_component_id(idx as u64, seg as u32);
                let line = vec![vec![
                    (a_lon as f64 * 1e-7, a_lat as f64 * 1e-7),
                    (b_lon as f64 * 1e-7, b_lat as f64 * 1e-7),
                ]];
                sink.push_named(&class, &Geometry::Lines(line), None, component_id)?;
                emitted += 1;
            }
        }
    }
    Ok(emitted)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// One directed edge of a synthetic graph.
    pub(crate) struct EdgeSpec {
        pub source: u32,
        pub target: u32,
        pub type_: u8,
        /// Interior points as `(d_lat, d_lon)` deltas, chained from the source. An empty list is a
        /// straight chord, which is how the presence bitmap says "this edge stores no geometry".
        pub interior: Vec<(i16, i16)>,
    }

    pub(crate) struct GraphFixture {
        pub dir: std::path::PathBuf,
    }
    impl Drop for GraphFixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }

    /// Write a real v6 graph to a fresh temp directory, byte for byte as the contract specifies.
    ///
    /// `pub(crate)` because [`crate::schema::junction`] reads the same four files and tests against
    /// the same layout; a second writer would be a second chance to disagree with `osm_ingest`.
    ///
    /// `edges` must be grouped by `source` ascending, which is what makes `nodes.bin`'s `edge_ptr`
    /// a CSR row pointer. `lanes` is `(edge_idx, masks)` ascending, and writes no `lanes.bin` at
    /// all when empty — which is the common shape of a real graph.
    pub(crate) fn write_graph(
        tag: &str,
        coords: &[(i32, i32)],
        edges: &[EdgeSpec],
        lanes: &[(u32, Vec<u16>)],
    ) -> GraphFixture {
        use std::sync::atomic::{AtomicU64, Ordering};
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let dir = std::env::temp_dir().join(format!(
            "mamaps_{tag}_{}_{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed),
        ));
        std::fs::create_dir_all(&dir).expect("mkdir");

        let node_count = coords.len() as u64;
        let edge_count = edges.len() as u64;
        assert!(
            edges.windows(2).all(|w| w[0].source <= w[1].source),
            "a CSR row pointer needs the edges grouped by source",
        );

        // metadata.bin (40 bytes).
        let mut meta = Vec::new();
        meta.extend_from_slice(&MARG_MAGIC.to_le_bytes());
        meta.extend_from_slice(&GRAPH_VERSION.to_le_bytes());
        meta.extend_from_slice(&node_count.to_le_bytes());
        meta.extend_from_slice(&edge_count.to_le_bytes());
        meta.extend_from_slice(&0u64.to_le_bytes()); // escape_count
        meta.extend_from_slice(&0u64.to_le_bytes()); // named_edges
        std::fs::write(dir.join("metadata.bin"), &meta).expect("meta");

        // nodes.bin: NodeRec[node_count + 1], 12 bytes each. `edge_ptr` is the index of the first
        // edge leaving each node, and the sentinel holds `edge_count`.
        let mut nodes = Vec::new();
        for (n, (lat, lon)) in coords.iter().enumerate() {
            let first = edges.iter().position(|e| e.source as usize >= n).unwrap_or(edges.len());
            nodes.extend_from_slice(&lat.to_le_bytes());
            nodes.extend_from_slice(&lon.to_le_bytes());
            nodes.extend_from_slice(&(first as u32).to_le_bytes());
        }
        nodes.extend_from_slice(&0i32.to_le_bytes());
        nodes.extend_from_slice(&0i32.to_le_bytes());
        nodes.extend_from_slice(&(edge_count as u32).to_le_bytes());
        std::fs::write(dir.join("nodes.bin"), &nodes).expect("nodes");

        // edges.bin: EdgeRec[edge_count], 7 bytes: i16 target_delta, u24 dist, u8 type_, u8 speed.
        let mut raw = Vec::new();
        for e in edges {
            let delta = (i64::from(e.target) - i64::from(e.source)) as i16;
            raw.extend_from_slice(&delta.to_le_bytes());
            // dist: a plausible non-escape value.
            raw.extend_from_slice(&[0x10, 0x00, 0x00]);
            raw.push(e.type_);
            raw.push(50); // speed_limit
        }
        // Pad the EdgeRec section to an 8-byte boundary, then the all-zero escape block index.
        while raw.len() % 8 != 0 {
            raw.push(0);
        }
        for _ in 0..(edge_count.div_ceil(ESCAPE_BLOCK) + 1) {
            raw.extend_from_slice(&0u32.to_le_bytes());
        }
        std::fs::write(dir.join("edges.bin"), &raw).expect("edges");

        // intermediate.bin: the interior-point blob, then the presence bitmap, the coarse offsets
        // and the per-geometry-edge within-block offsets, then the geometry-edge count.
        let mut blob = Vec::new();
        let mut within = Vec::new();
        let mut present = vec![0u8; edge_count.div_ceil(8).max(1) as usize];
        let mut g_edges = 0u64;
        for (idx, e) in edges.iter().enumerate() {
            if e.interior.is_empty() {
                continue;
            }
            present[idx / 8] |= 1 << (idx % 8);
            within.extend_from_slice(&(blob.len() as u16).to_le_bytes());
            for (d_lat, d_lon) in &e.interior {
                blob.extend_from_slice(&d_lat.to_le_bytes());
                blob.extend_from_slice(&d_lon.to_le_bytes());
            }
            g_edges += 1;
        }
        within.extend_from_slice(&(blob.len() as u16).to_le_bytes());

        let mut inter = blob;
        // One rank word per RANK_BLOCK_BYTES of presence, all zero: every fixture here is well
        // under 512 edges, so the rank of the first block is 0 and the rest is a popcount scan.
        for _ in 0..(edge_count.div_ceil(512) + 1) {
            inter.extend_from_slice(&0u64.to_le_bytes());
        }
        inter.extend_from_slice(&present);
        for _ in 0..(g_edges.div_ceil(INTERMEDIATE_BLOCK) + 1) {
            inter.extend_from_slice(&0u64.to_le_bytes());
        }
        inter.extend_from_slice(&within);
        inter.extend_from_slice(&g_edges.to_le_bytes());
        std::fs::write(dir.join("intermediate.bin"), &inter).expect("inter");

        // lanes.bin: [u32 n][(u32 edge_idx, u32 blob_off) x (n + 1)][u16 blob]. Sparse, so a graph
        // with no tagged turn lanes carries no file at all.
        if !lanes.is_empty() {
            let mut index = Vec::new();
            let mut masks = Vec::new();
            index.extend_from_slice(&(lanes.len() as u32).to_le_bytes());
            for (idx, lane_masks) in lanes {
                index.extend_from_slice(&idx.to_le_bytes());
                index.extend_from_slice(&(masks.len() as u32).to_le_bytes());
                for m in lane_masks {
                    masks.extend_from_slice(&m.to_le_bytes());
                }
            }
            // The trailing sentinel exists only to give the last edge an end offset.
            index.extend_from_slice(&u32::MAX.to_le_bytes());
            index.extend_from_slice(&(masks.len() as u32).to_le_bytes());
            index.extend_from_slice(&masks);
            std::fs::write(dir.join("lanes.bin"), &index).expect("lanes");
        }

        GraphFixture { dir }
    }

    /// The traffic layer's own graph: four nodes in a square. Edges:
    /// * 0: node0 -> node1, one-way, curved (one interior point). Emitted: 2 segments.
    /// * 1: node1 -> node2, straight two-way (canonical, `1 < 2`). Emitted: 1 segment.
    /// * 2: node2 -> node1, straight two-way (non-canonical twin of edge 1). Skipped.
    /// * 3: node2 -> node3, pedestrian (type 10). Skipped (not drivable).
    fn write_fixture() -> GraphFixture {
        let coords: [(i32, i32); 4] = [
            (35_000_000, -120_000_000),
            (35_000_000, -119_990_000),
            (35_010_000, -119_990_000),
            (35_010_000, -120_000_000),
        ];
        let edge = |source, target, type_, interior: &[(i16, i16)]| EdgeSpec {
            source,
            target,
            type_,
            interior: interior.to_vec(),
        };
        write_graph(
            "traffic",
            &coords,
            &[
                // An interior point roughly midway, curving off the straight chord.
                edge(0, 1, 1, &[(3_000, -5_000)]),
                edge(1, 2, 7, &[]),
                edge(2, 1, 7, &[]),
                edge(2, 3, 10, &[]),
            ],
            &[],
        )
    }

    #[test]
    fn the_component_id_packing_round_trips() {
        for (edge, seg) in [(0u64, 0u32), (1, 0), (5, 254), (1_000_000, 42)] {
            let id = pack_component_id(edge, seg);
            assert_eq!(unpack_component_id(id), (edge, seg));
        }
        // The contract's worked example.
        assert_eq!(pack_component_id(5, 3), (5 << 16) | 3);
    }

    #[test]
    fn a_synthetic_graph_streams_the_expected_segments() {
        let fixture = write_fixture();
        let spill = fixture.dir.join("features.tmp");
        let mut sink = Sink::create(&spill).expect("sink");
        let emitted = stream_graph(&fixture.dir, &mut sink).expect("stream");
        let store = sink.finish(&spill).expect("finish");

        // Edge 0 (curved, 3 vertices) -> 2 segments; edge 1 (straight canonical) -> 1 segment.
        // Edge 2 is the non-canonical twin of edge 1 (skipped); edge 3 is pedestrian (skipped).
        assert_eq!(emitted, 3, "two segments from the curved edge, one from the straight two-way");
        assert_eq!(store.len(), 3);

        // Read every feature back and confirm each id unpacks to a valid (big_edge_id, seg_index).
        let mut reader = store.reader().expect("reader");
        let mut ids = Vec::new();
        while let Some(feature) = reader.next().expect("read") {
            assert_eq!(feature.class.layer, LAYER_TRAFFIC);
            assert_eq!(feature.class.min_zoom, MIN_ZOOM);
            let (edge, seg) = unpack_component_id(feature.id);
            assert!(edge < 4, "edge {edge} is past the graph");
            assert!(seg <= 254, "seg_index {seg} does not fit the contract");
            ids.push((edge, seg));
        }
        // The curved edge 0 contributes segments 0 and 1; the straight edge 1 contributes segment 0.
        assert!(ids.contains(&(0, 0)), "{ids:?}");
        assert!(ids.contains(&(0, 1)), "{ids:?}");
        assert!(ids.contains(&(1, 0)), "{ids:?}");
        let _ = std::fs::remove_file(&spill);
    }
}
