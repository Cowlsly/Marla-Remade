//! Coordinates for a known set of node ids, and the PBF pass that fills them.
//!
//! Split out of [`crate::extract`] so the tile generators can share it: resolving
//! way geometry is the one thing every layer build does, and it is the one place
//! where a careless data structure costs gigabytes.

use std::path::{Path, PathBuf};
use std::sync::atomic;

use crate::geojson::Coord;
use crate::osm::{visit_block, Element};
use crate::pbf::{self, KIND_NODES};
use crate::proto::{Error, Result};

/// Sentinel latitude for "this node's location was never seen".
/// Coordinates for a known set of node ids: a compressed id index plus an
/// index-aligned coordinate array.
///
/// **This, and not [`crate::graph_build`]'s bitset.** That module allocates
/// `BITSET_SIZE = 20e9` bits -- 2.5 GB keyed by raw OSM node id, whatever the
/// extract -- and peaks around 10 GB on California. This form costs per *needed*
/// node, so a layer that wants a few hundred thousand nodes pays megabytes. Same
/// pattern as [`crate::poi_build`], which worked it out first.
///
/// # Why the ids are compressed
///
/// A sorted `Vec<i64>` is the obvious id table and it was the first one here. On a California
/// extract it is 154 M ids, so 1.23 GB -- and next to an equally large coordinate array it made this
/// structure about 73% of the whole generator's peak. The ids are sorted and dense enough that the
/// gaps between them are tiny: they are a *subset* of a PBF's node ids, but a large one, so the
/// average gap is a couple of units and encodes in a single byte. [`IdIndex`] stores one full id per
/// block of [`BLOCK`] and varint gaps for the rest, which is about 1.2 bytes per id instead of 8.
///
/// Lookup did not get slower for it, and probably got faster. A binary search over 1.23 GB touches
/// ~27 scattered cache lines and misses on most of them; this searches a 19 MB base array and then
/// scans at most 63 contiguous bytes, which is one or two lines.
pub struct NodeLocations {
    ids: IdIndex,
    locs: Locs,
}

/// The coordinate array, in a memory-mapped file rather than on the heap.
///
/// Eight bytes per needed node is 1.23 GB on a California extract, and unlike the ids it will not
/// compress: latitude and longitude in id order are high-entropy, so there is no structure to exploit.
/// What there is instead is locality — materialisation walks ways in id order and a way's nodes sit
/// close together in a PBF — so a mapped file lets the OS keep the working set and nothing else.
///
/// # Resolved-ness is a bitset, not a sentinel
///
/// A fresh mapping reads as zeroes, and `(0, 0)` is a real coordinate, so zero cannot mean
/// "unresolved". Writing `i32::MIN` across the whole file would work and would also dirty every page
/// of 1.23 GB before the pass that fills it, which is most of the cost this exists to avoid. A bit per
/// node is 19 MB, stays in memory, and leaves the mapping untouched until something is actually
/// written to it.
struct Locs {
    map: Option<memmap2::MmapMut>,
    /// One bit per node, set when a coordinate has been written.
    resolved: Vec<u64>,
    /// Deleted on drop. Scratch, and large enough that leaving it behind would matter.
    path: Option<PathBuf>,
    len: usize,
}

impl Locs {
    fn new(len: usize) -> Result<Locs> {
        if len == 0 {
            return Ok(Locs { map: None, resolved: Vec::new(), path: None, len: 0 });
        }
        let path = std::env::temp_dir().join(format!(
            "osm_nodeloc_{}_{}.bin",
            std::process::id(),
            // Distinct per table, so two in one process cannot share a file.
            NEXT_LOCS.fetch_add(1, atomic::Ordering::Relaxed),
        ));
        let bytes = len as u64 * 8;
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(&path)
            .map_err(|e| Error(format!("cannot create {}: {e}", path.display())))?;
        // Sets the length without writing anything: on both platforms this leaves a sparse or
        // zero-filled file, so the 1.23 GB is not touched until a page is used.
        file.set_len(bytes)
            .map_err(|e| Error(format!("cannot size {}: {e}", path.display())))?;
        // SAFETY: this process owns `path` exclusively — it is named after the pid and a counter, was
        // just created with `truncate`, and is deleted in `Drop`. Nothing else can resize it while the
        // mapping is live, which is the one thing that would make the slice dangle.
        let map = unsafe { memmap2::MmapMut::map_mut(&file) }
            .map_err(|e| Error(format!("cannot map {}: {e}", path.display())))?;
        Ok(Locs {
            map: Some(map),
            resolved: vec![0u64; len.div_ceil(64)],
            path: Some(path),
            len,
        })
    }

    fn set(&mut self, index: usize, lat_e7: i32, lon_e7: i32) {
        let Some(map) = self.map.as_mut() else { return };
        let at = index * 8;
        map[at..at + 4].copy_from_slice(&lat_e7.to_le_bytes());
        map[at + 4..at + 8].copy_from_slice(&lon_e7.to_le_bytes());
        self.resolved[index / 64] |= 1 << (index % 64);
    }

    fn get(&self, index: usize) -> Option<(i32, i32)> {
        if index >= self.len || self.resolved[index / 64] & (1 << (index % 64)) == 0 {
            return None;
        }
        let map = self.map.as_ref()?;
        let at = index * 8;
        let lat = i32::from_le_bytes(map[at..at + 4].try_into().expect("four bytes"));
        let lon = i32::from_le_bytes(map[at + 4..at + 8].try_into().expect("four bytes"));
        Some((lat, lon))
    }
}

impl Drop for Locs {
    fn drop(&mut self) {
        // The mapping goes before the file, or Windows refuses the delete.
        self.map = None;
        if let Some(path) = self.path.take() {
            let _ = std::fs::remove_file(path);
        }
    }
}

static NEXT_LOCS: atomic::AtomicU64 = atomic::AtomicU64::new(0);

/// Ids per block of [`IdIndex`].
///
/// The trade is the usual one: a larger block amortises its 12 bytes of base and offset over more
/// ids, and lengthens the scan that follows the binary search. 64 puts the overhead at 0.19 bytes per
/// id and keeps the worst-case scan inside a couple of cache lines.
const BLOCK: usize = 64;

/// A sorted, unique id set as block bases plus varint gaps.
///
/// Searchable through `&self` alone, because [`resolve_nodes`] hands it to every worker in a PBF pass
/// while the sink writes coordinates.
#[derive(Default)]
struct IdIndex {
    /// The first id of each block, ascending. What the binary search runs over.
    bases: Vec<i64>,
    /// Where each block's gaps start in `deltas`, with a sentinel so `offsets[b + 1]` is always the
    /// end of block `b`.
    offsets: Vec<u32>,
    /// `BLOCK - 1` varint gaps per block, each the difference from the previous id.
    deltas: Vec<u8>,
    len: usize,
}

impl IdIndex {
    /// Build from ids that are already sorted and unique.
    fn build(ids: &[i64]) -> IdIndex {
        let blocks = ids.len().div_ceil(BLOCK);
        let mut index = IdIndex {
            bases: Vec::with_capacity(blocks),
            offsets: Vec::with_capacity(blocks + 1),
            // One byte per gap is the common case, so this is the right first guess.
            deltas: Vec::with_capacity(ids.len()),
            len: ids.len(),
        };
        for block in ids.chunks(BLOCK) {
            index.bases.push(block[0]);
            index.offsets.push(index.deltas.len() as u32);
            let mut previous = block[0];
            for &id in &block[1..] {
                // Non-negative because the input is sorted and unique, so the gap is at least one.
                put_uvarint((id - previous) as u64, &mut index.deltas);
                previous = id;
            }
        }
        index.offsets.push(index.deltas.len() as u32);
        index.deltas.shrink_to_fit();
        index
    }

    fn len(&self) -> usize {
        self.len
    }

    /// The position of `id`, or `None` if it is not in the set.
    fn find(&self, id: i64) -> Option<u32> {
        let block = match self.bases.binary_search(&id) {
            // A base is an id, so this is a hit on the first of a block.
            Ok(block) => return Some((block * BLOCK) as u32),
            // Before the first id in the set.
            Err(0) => return None,
            Err(next) => next - 1,
        };
        let mut current = self.bases[block];
        let mut at = self.offsets[block] as usize;
        let end = self.offsets[block + 1] as usize;
        let mut position = 0usize;
        while at < end {
            let (gap, used) = get_uvarint(&self.deltas[at..]);
            at += used;
            current += gap as i64;
            position += 1;
            if current == id {
                return Some((block * BLOCK + position) as u32);
            }
            // The block is ascending, so once it is past `id` the id is absent.
            if current > id {
                return None;
            }
        }
        None
    }
}

/// LEB128, as the rest of this crate spells varints.
fn put_uvarint(mut value: u64, out: &mut Vec<u8>) {
    while value >= 0x80 {
        out.push((value as u8) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

/// Returns `(value, bytes consumed)`.
fn get_uvarint(bytes: &[u8]) -> (u64, usize) {
    let mut value = 0u64;
    let mut shift = 0u32;
    for (i, &b) in bytes.iter().enumerate() {
        value |= ((b & 0x7f) as u64) << shift;
        if b < 0x80 {
            return (value, i + 1);
        }
        shift += 7;
    }
    (value, bytes.len())
}

impl NodeLocations {
    /// `ids` need not be sorted or unique; both are done here.
    ///
    /// Fallible because the coordinate array is a mapped file, and a temporary directory that is full
    /// or read-only is a real thing to report rather than to panic on.
    pub fn new(mut ids: Vec<i64>) -> Result<NodeLocations> {
        ids.sort_unstable();
        ids.dedup();
        let index = IdIndex::build(&ids);
        // The plain vector goes here rather than being kept alongside: it is 8 bytes per id against
        // the index's ~1.2, and holding both would give back the saving. Freed before the coordinate
        // array is mapped, so the two never peak together.
        drop(ids);
        let locs = Locs::new(index.len())?;
        Ok(NodeLocations { ids: index, locs })
    }

    pub fn len(&self) -> usize {
        self.ids.len()
    }

    pub fn is_empty(&self) -> bool {
        self.ids.len() == 0
    }

    fn index_of(&self, id: i64) -> Option<u32> {
        self.ids.find(id)
    }

    pub fn get(&self, id: i64) -> Option<(i32, i32)> {
        self.locs.get(self.index_of(id)? as usize)
    }

    /// A way's geometry in lon/lat order, with unresolved refs skipped.
    ///
    /// A ref can be unresolved for a legitimate reason: an extract cut through the
    /// way, so some of its nodes are simply not in the file. Skipping the gap keeps
    /// the rest of the line rather than discarding the whole way, which is what
    /// `osmium extract`'s `complete_ways` produces for the same input.
    pub fn line(&self, refs: &[i64]) -> Vec<Coord> {
        refs.iter()
            .filter_map(|id| self.get(*id))
            .map(|(lat_e7, lon_e7)| (lon_e7 as f64 * 1e-7, lat_e7 as f64 * 1e-7))
            .collect()
    }
}

/// The node pass: fill in every coordinate the earlier passes asked for.
///
/// Uses [`pbf::run_pass_sink`] rather than [`pbf::run_pass`], because `run_pass`'s contract is to
/// hand every chunk's accumulator back at once and these accumulators are the largest thing this
/// pass touches. On California they are 154 M nodes at 12 bytes -- about 1.8 GB -- and they would be
/// alive next to the 2.5 GB table they are about to be folded into, so the two peak together. Here a
/// chunk is folded and freed while its neighbours are still decoding, which also overlaps the merge
/// with the I/O. `pbf.rs` names this exact hazard in `run_pass_sink`'s own doc comment.
///
/// The merge order is unchanged: `run_pass_sink` calls its sink in chunk order, which is the order
/// `run_pass` returned chunks in, so two blocks reporting the same node still resolve to the same
/// coordinate.
pub fn resolve_nodes(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds: &[u8],
    label: &str,
    mut table: NodeLocations,
) -> Result<NodeLocations> {
    if table.is_empty() {
        return Ok(table);
    }
    // Moved out so the workers can search it while the sink writes coordinates; put back below.
    let ids = std::mem::take(&mut table.ids);
    let locs = &mut table.locs;
    pbf::run_pass_sink(
        input,
        blobs,
        Some(blob_kinds),
        KIND_NODES,
        label,
        Vec::<(u32, i32, i32)>::new,
        |state: &mut Vec<(u32, i32, i32)>, block| {
            let mut kinds = 0u8;
            visit_block(block, KIND_NODES, &mut kinds, &mut |el: Element| {
                if let Element::Node(n) = el {
                    if let Some(idx) = ids.find(n.id) {
                        state.push((idx, n.lat_e7, n.lon_e7));
                    }
                }
                Ok(())
            })?;
            Ok(kinds)
        },
        |chunk| {
            for (idx, lat, lon) in chunk {
                locs.set(idx as usize, lat, lon);
            }
            Ok(())
        },
    )?;
    table.ids = ids;
    Ok(table)
}

#[cfg(test)]
mod tests {
    /// **The test the compressed index lives or dies by.** It has to answer exactly what a plain
    /// `binary_search` over the same ids would, for ids that are present and ids that are not, and it
    /// has to hold at block boundaries and across gaps too large for a one-byte varint.
    ///
    /// Driven by a deterministic LCG rather than a fixed list so it covers thousands of cases and
    /// still fails identically every run.
    #[test]
    fn the_compressed_index_answers_exactly_what_a_binary_search_would() {
        let mut state = 0x2545_F491_4F6C_DD1Du64;
        let mut next = || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };

        // Gaps drawn so most are tiny (the real distribution) and some are enormous, which is what
        // forces multi-byte varints.
        let mut ids: Vec<i64> = Vec::new();
        let mut id: i64 = 1;
        for _ in 0..5_000 {
            let r = next();
            let gap = match r % 100 {
                0 => (r >> 8) % 5_000_000 + 1,
                1..=9 => (r >> 8) % 1_000 + 1,
                _ => (r >> 8) % 4 + 1,
            };
            id += gap as i64;
            ids.push(id);
        }
        let index = IdIndex::build(&ids);
        assert_eq!(index.len(), ids.len());

        // Every id present resolves to its own position.
        for (position, &id) in ids.iter().enumerate() {
            assert_eq!(index.find(id), Some(position as u32), "id {id} at {position}");
        }
        // And nothing else resolves at all. Probing every id +/- 1 covers the interesting misses:
        // just below a hit, just above one, and inside a gap.
        for &id in &ids {
            for probe in [id - 1, id + 1] {
                let expected = ids.binary_search(&probe).ok().map(|i| i as u32);
                assert_eq!(index.find(probe), expected, "probe {probe}");
            }
        }
        // Outside the set entirely, at both ends.
        assert_eq!(index.find(0), None);
        assert_eq!(index.find(i64::MIN), None);
        assert_eq!(index.find(ids[ids.len() - 1] + 1), None);
        assert_eq!(index.find(i64::MAX), None);
    }

    /// A block boundary is the one place an off-by-one would hide, so it is checked directly rather
    /// than left to the random case.
    #[test]
    fn a_block_boundary_resolves_on_both_sides() {
        // Three full blocks and a partial one, with a gap of exactly one so positions and ids differ
        // by a constant and an off-by-one is unmissable.
        let ids: Vec<i64> = (0..BLOCK as i64 * 3 + 7).map(|i| 1_000 + i).collect();
        let index = IdIndex::build(&ids);
        for (position, &id) in ids.iter().enumerate() {
            assert_eq!(index.find(id), Some(position as u32));
        }
        // The first id of each block is a base, which is the branch that returns without scanning.
        for block in 0..4 {
            let position = block * BLOCK;
            if position < ids.len() {
                assert_eq!(index.find(ids[position]), Some(position as u32));
            }
        }
        assert_eq!(index.find(999), None, "below the set");
        assert_eq!(index.find(1_000 + ids.len() as i64), None, "above the set");
    }

    #[test]
    fn an_empty_or_single_id_set_is_not_a_special_case_that_panics() {
        let empty = IdIndex::build(&[]);
        assert_eq!(empty.len(), 0);
        assert_eq!(empty.find(1), None);

        let one = IdIndex::build(&[42]);
        assert_eq!(one.len(), 1);
        assert_eq!(one.find(42), Some(0));
        assert_eq!(one.find(41), None);
        assert_eq!(one.find(43), None);
    }

    /// The saving this was done for. A plain `Vec<i64>` is 8 bytes an id; this has to be a small
    /// fraction of that or it is not worth the scan.
    #[test]
    fn the_index_costs_about_one_byte_per_id() {
        // Gaps of one to four, which is roughly what a real needed-node set looks like.
        let ids: Vec<i64> = (0..100_000i64).map(|i| 5 + i * 3).collect();
        let index = IdIndex::build(&ids);
        let bytes = index.bases.len() * 8 + index.offsets.len() * 4 + index.deltas.len();
        let plain = ids.len() * 8;
        assert!(
            bytes * 4 < plain,
            "the index is {bytes} bytes against {plain} for a plain vector, which is not worth it",
        );
    }

    #[test]
    fn a_varint_round_trips_at_every_width() {
        for value in [0u64, 1, 0x7f, 0x80, 0x3fff, 0x4000, u32::MAX as u64, u64::MAX] {
            let mut bytes = Vec::new();
            put_uvarint(value, &mut bytes);
            let (read, used) = get_uvarint(&bytes);
            assert_eq!((read, used), (value, bytes.len()), "value {value}");
        }
    }
    use super::*;

    #[test]
    fn node_locations_are_keyed_by_a_sorted_table_not_by_raw_node_id() {
        // The point of the pattern: memory is O(needed), so ids can be as large as
        // OSM's real ones without allocating anything proportional to them.
        let huge = 12_345_678_901i64;
        let mut table = NodeLocations::new(vec![huge, 5, 5, 1]).expect("map the coordinate file");
        assert_eq!(table.len(), 3, "sorted and deduped");
        assert_eq!(table.get(5), None, "unresolved until the node pass fills it");
        // Fill by index, as the merge does.
        let idx = table.index_of(5).unwrap() as usize;
        table.locs.set(idx, 377_900_000, -1_224_200_000);
        assert_eq!(table.get(5), Some((377_900_000, -1_224_200_000)));
        assert_eq!(table.get(huge), None);
        assert_eq!(table.get(999), None, "not in the table at all");
    }

    #[test]
    fn an_unresolved_ref_leaves_a_gap_rather_than_dropping_the_way() {
        // An extract that cuts through a way leaves some of its nodes out of the
        // file. Keeping the rest is what osmium's complete_ways produces too.
        let mut table = NodeLocations::new(vec![1, 2, 3]).expect("map the coordinate file");
        for (id, lat, lon) in [(1i64, 370_000_000, -1_220_000_000), (3, 370_020_000, -1_220_020_000)] {
            let idx = table.index_of(id).unwrap() as usize;
            table.locs.set(idx, lat, lon);
        }
        let line = table.line(&[1, 2, 3]);
        assert_eq!(line.len(), 2, "the middle vertex is missing, not the way");
        // lon/lat order, as GeoJSON wants.
        assert!((line[0].0 - -122.0).abs() < 1e-9);
        assert!((line[0].1 - 37.0).abs() < 1e-9);
    }
}
