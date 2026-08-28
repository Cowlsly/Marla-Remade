//! Coordinates for a known set of node ids, and the PBF pass that fills them.
//!
//! Split out of [`crate::extract`] so the tile generators can share it: resolving
//! way geometry is the one thing every layer build does, and it is the one place
//! where a careless data structure costs gigabytes.

use std::path::Path;

use crate::geojson::Coord;
use crate::osm::{visit_block, Element};
use crate::pbf::{self, KIND_NODES};
use crate::proto::Result;

/// Sentinel latitude for "this node's location was never seen".
const NO_LOC: i32 = i32::MIN;

/// Coordinates for a known set of node ids: a sorted id table plus an
/// index-aligned coordinate array, looked up by `binary_search`.
///
/// **This, and not [`crate::graph_build`]'s bitset.** That module allocates
/// `BITSET_SIZE = 20e9` bits -- 2.5 GB keyed by raw OSM node id, whatever the
/// extract -- and peaks around 10 GB on California. This form costs 16 bytes per
/// *needed* node, so a layer that wants a few hundred thousand nodes pays a few
/// megabytes. Same pattern as [`crate::poi_build`], which worked it out first.
pub struct NodeLocations {
    ids: Vec<i64>,
    locs: Vec<(i32, i32)>,
}

impl NodeLocations {
    /// `ids` need not be sorted or unique; both are done here.
    pub fn new(mut ids: Vec<i64>) -> NodeLocations {
        ids.sort_unstable();
        ids.dedup();
        // `dedup` shrinks the length and leaves the capacity alone, and this vector is built by
        // extension, so it arrives over-allocated by up to a factor of two. On a California extract
        // that is ~150 M ids and the slack is hundreds of megabytes held for the rest of the build,
        // next to a table of the same size. Given back before the locations are allocated.
        ids.shrink_to_fit();
        let locs = vec![(NO_LOC, NO_LOC); ids.len()];
        NodeLocations { ids, locs }
    }

    pub fn len(&self) -> usize {
        self.ids.len()
    }

    pub fn is_empty(&self) -> bool {
        self.ids.is_empty()
    }

    fn index_of(&self, id: i64) -> Option<u32> {
        self.ids.binary_search(&id).ok().map(|i| i as u32)
    }

    pub fn get(&self, id: i64) -> Option<(i32, i32)> {
        let (lat, lon) = self.locs[self.index_of(id)? as usize];
        (lat != NO_LOC).then_some((lat, lon))
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
    let ids = std::mem::take(&mut table.ids);
    let (chunks, _) = pbf::run_pass(
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
                    if let Ok(idx) = ids.binary_search(&n.id) {
                        state.push((idx as u32, n.lat_e7, n.lon_e7));
                    }
                }
                Ok(())
            })?;
            Ok(kinds)
        },
    )?;
    table.ids = ids;
    for chunk in chunks {
        for (idx, lat, lon) in chunk {
            table.locs[idx as usize] = (lat, lon);
        }
    }
    Ok(table)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn node_locations_are_keyed_by_a_sorted_table_not_by_raw_node_id() {
        // The point of the pattern: memory is O(needed), so ids can be as large as
        // OSM's real ones without allocating anything proportional to them.
        let huge = 12_345_678_901i64;
        let mut table = NodeLocations::new(vec![huge, 5, 5, 1]);
        assert_eq!(table.len(), 3, "sorted and deduped");
        assert_eq!(table.get(5), None, "unresolved until the node pass fills it");
        // Fill by index, as the merge does.
        let idx = table.index_of(5).unwrap() as usize;
        table.locs[idx] = (377_900_000, -1_224_200_000);
        assert_eq!(table.get(5), Some((377_900_000, -1_224_200_000)));
        assert_eq!(table.get(huge), None);
        assert_eq!(table.get(999), None, "not in the table at all");
    }

    #[test]
    fn an_unresolved_ref_leaves_a_gap_rather_than_dropping_the_way() {
        // An extract that cuts through a way leaves some of its nodes out of the
        // file. Keeping the rest is what osmium's complete_ways produces too.
        let mut table = NodeLocations::new(vec![1, 2, 3]);
        for (id, lat, lon) in [(1i64, 370_000_000, -1_220_000_000), (3, 370_020_000, -1_220_020_000)] {
            let idx = table.index_of(id).unwrap() as usize;
            table.locs[idx] = (lat, lon);
        }
        let line = table.line(&[1, 2, 3]);
        assert_eq!(line.len(), 2, "the middle vertex is missing, not the way");
        // lon/lat order, as GeoJSON wants.
        assert!((line[0].0 - -122.0).abs() < 1e-9);
        assert!((line[0].1 - 37.0).abs() < 1e-9);
    }
}
