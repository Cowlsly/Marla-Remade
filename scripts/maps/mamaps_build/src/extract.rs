//! Stage A: `.osm.pbf` in, classified geometry in lon/lat out.
//!
//! Three passes, because a PBF is ordered nodes-then-ways-then-relations and a way's geometry needs
//! coordinates that arrived before its tags did:
//!
//! 1. **ways + relations.** Classify tags. Keep the node refs of every classified way and the member
//!    way ids of every classified relation. Node blobs are skipped outright.
//! 2. **ways again**, keeping the refs of the ways that turned out to be relation members. A second
//!    pass rather than keeping every untagged way's refs from the first, because "every untagged way
//!    in California" is 40 M node ids and the members are a few thousand.
//! 3. **nodes.** Fill coordinates for exactly the ids the first two passes asked for, at 16 bytes
//!    per needed node.
//!
//! That last point is the whole memory argument. [`osm_ingest::nodeloc::NodeLocations`] costs
//! `O(needed)`; `graph_build`'s bitset costs 2.5 GB keyed by raw OSM node id whatever the extract,
//! and peaks near 10 GB on California. A water-and-buildings build needs a few million nodes, so it
//! pays tens of megabytes.

use std::collections::HashMap;
use std::path::Path;

use osm_ingest::nodeloc::{resolve_nodes, NodeLocations};
use osm_ingest::osm::{visit_block, Element, MEMBER_WAY};
use osm_ingest::pbf::{self, KIND_RELATIONS, KIND_WAYS};
use osm_ingest::proto::Result;
use osm_ingest::rings::{self, MemberWay, RingStats};
use osm_ingest::select::Select;
use tile_build::geom::Geometry;

use crate::schema::{self, Class, Layers};

/// One classified feature, in lon/lat, ready to tile.
pub struct Feature {
    pub class: Class,
    pub geometry: Geometry,
}

/// What a run of stage A did, for the build report.
#[derive(Debug, Default, Clone)]
pub struct Stats {
    pub ways_classified: u64,
    pub relations_classified: u64,
    pub features: u64,
    /// Classified elements whose geometry could not be built — an extract cut through them, or a
    /// relation's rings would not close.
    pub geometry_failed: u64,
    pub nodes_needed: u64,
    pub rings: RingStats,
}

/// A classified way, held between passes.
struct Way {
    class: Class,
    refs: Vec<i64>,
}

/// A classified relation, held between passes.
struct Relation {
    class: Class,
    /// `(way id, role is inner)`, in the order the relation lists them, which is what
    /// [`rings::assemble`] stitches.
    members: Vec<(i64, bool)>,
}

/// Read `input` and return every feature the schema classifies.
pub fn extract(input: &Path, layers: Layers) -> Result<(Vec<Feature>, Stats)> {
    let blobs = pbf::scan_blobs(input)?;
    let select = Select::parse(&schema::filters(layers))?;
    let mut stats = Stats::default();

    // --- pass 1: ways and relations -------------------------------------------------------
    //
    // `blob_kinds` comes back from this pass and lets the later ones skip whole blobs, which on a
    // planet extract is most of the file.
    let (chunks, blob_kinds) = pbf::run_pass(
        input,
        &blobs,
        None,
        KIND_WAYS | KIND_RELATIONS,
        "Pass 1: ways and relations",
        || (Vec::<(i64, Way)>::new(), Vec::<Relation>::new()),
        |state, block| {
            let mut kinds = 0u8;
            visit_block(block, KIND_WAYS | KIND_RELATIONS, &mut kinds, &mut |el| {
                match el {
                    Element::Way(way) => {
                        if !select.matches(|k| way.tags.get_str(k)) {
                            return Ok(());
                        }
                        if let Some(class) = schema::classify(&way.tags, true, layers) {
                            state.0.push((way.id, Way { class, refs: way.refs.to_vec() }));
                        }
                    }
                    Element::Relation(relation) => {
                        // `type=multipolygon` is what makes a relation an area. A route or a
                        // boundary relation carrying a water tag is not a lake.
                        if relation.tags.get_str("type") != Some("multipolygon") {
                            return Ok(());
                        }
                        if !select.matches(|k| relation.tags.get_str(k)) {
                            return Ok(());
                        }
                        if let Some(class) = schema::classify(&relation.tags, false, layers) {
                            let members = relation
                                .members
                                .iter()
                                .filter(|m| m.kind == MEMBER_WAY)
                                .map(|m| (m.id, m.role == b"inner"))
                                .collect();
                            state.1.push(Relation { class, members });
                        }
                    }
                    Element::Node(_) => {}
                }
                Ok(())
            })?;
            Ok(kinds)
        },
    )?;

    let mut ways: HashMap<i64, Way> = HashMap::new();
    let mut relations: Vec<Relation> = Vec::new();
    for (chunk_ways, chunk_relations) in chunks {
        ways.extend(chunk_ways);
        relations.extend(chunk_relations);
    }
    stats.ways_classified = ways.len() as u64;
    stats.relations_classified = relations.len() as u64;

    // --- pass 2: the member ways pass 1 did not keep --------------------------------------
    let wanted: Vec<i64> = {
        let mut wanted: Vec<i64> = relations
            .iter()
            .flat_map(|r| r.members.iter().map(|(id, _)| *id))
            .filter(|id| !ways.contains_key(id))
            .collect();
        wanted.sort_unstable();
        wanted.dedup();
        wanted
    };
    let mut members: HashMap<i64, Vec<i64>> = HashMap::new();
    if !wanted.is_empty() {
        let (chunks, _) = pbf::run_pass(
            input,
            &blobs,
            Some(&blob_kinds),
            KIND_WAYS,
            "Pass 2: member ways",
            Vec::<(i64, Vec<i64>)>::new,
            |state, block| {
                let mut kinds = 0u8;
                visit_block(block, KIND_WAYS, &mut kinds, &mut |el| {
                    if let Element::Way(way) = el {
                        if wanted.binary_search(&way.id).is_ok() {
                            state.push((way.id, way.refs.to_vec()));
                        }
                    }
                    Ok(())
                })?;
                Ok(kinds)
            },
        )?;
        for chunk in chunks {
            members.extend(chunk);
        }
    }

    // --- pass 3: the coordinates those two asked for --------------------------------------
    let mut needed: Vec<i64> = Vec::new();
    for way in ways.values() {
        needed.extend_from_slice(&way.refs);
    }
    for refs in members.values() {
        needed.extend_from_slice(refs);
    }
    for relation in &relations {
        for (id, _) in &relation.members {
            if let Some(way) = ways.get(id) {
                needed.extend_from_slice(&way.refs);
            }
        }
    }
    let table = NodeLocations::new(needed);
    stats.nodes_needed = table.len() as u64;
    let table = resolve_nodes(input, &blobs, &blob_kinds, "Pass 3: nodes", table)?;

    // --- materialise ----------------------------------------------------------------------
    //
    // Ways in **id order**, not hash order, because the output has to be reproducible and a
    // `HashMap`'s iteration is not. The one place in this pipeline where that is a real risk.
    let mut out = Vec::with_capacity(ways.len() + relations.len());
    let mut ids: Vec<i64> = ways.keys().copied().collect();
    ids.sort_unstable();
    for id in ids {
        let way = &ways[&id];
        let line = table.line(&way.refs);
        match way_geometry(&line, way.class.area) {
            Some(geometry) => {
                out.push(Feature { class: way.class, geometry });
                stats.features += 1;
            }
            None => stats.geometry_failed += 1,
        }
    }
    for relation in &relations {
        let member_ways: Vec<MemberWay> = relation
            .members
            .iter()
            .filter_map(|(id, inner)| {
                let refs = ways.get(id).map(|w| &w.refs).or_else(|| members.get(id))?;
                Some(MemberWay { refs: refs.clone(), outer: !inner })
            })
            .collect();
        let polygons = rings::assemble(&member_ways, |id| locate(&table, id), &mut stats.rings);
        if polygons.is_empty() {
            stats.geometry_failed += 1;
            continue;
        }
        out.push(Feature {
            class: relation.class,
            geometry: Geometry::Polygons(polygons),
        });
        stats.features += 1;
    }
    Ok((out, stats))
}

/// A node's location in lon/lat, which is the order [`rings::assemble`] and GeoJSON both want.
///
/// [`NodeLocations::get`] returns lat/lon, matching the PBF's own field order.
fn locate(table: &NodeLocations, id: i64) -> Option<(f64, f64)> {
    let (lat_e7, lon_e7) = table.get(id)?;
    Some((lon_e7 as f64 * 1e-7, lat_e7 as f64 * 1e-7))
}

/// A way's coordinates as the geometry its class says it is.
///
/// A closed way is a ring only if the tags call it an area; the same shape is a cul-de-sac
/// otherwise. A ring that does not close is closed here rather than dropped, which is what
/// `osmium`'s own export does — an extract cut through the way is the usual reason.
fn way_geometry(line: &[(f64, f64)], area: bool) -> Option<Geometry> {
    if !area {
        return (line.len() >= 2).then(|| Geometry::Lines(vec![line.to_vec()]));
    }
    // Three distinct points at minimum, plus the closing one.
    if line.len() < 3 {
        return None;
    }
    let mut ring = line.to_vec();
    if ring.first() != ring.last() {
        ring.push(ring[0]);
    }
    if ring.len() < 4 {
        return None;
    }
    Some(Geometry::Polygons(vec![vec![ring]]))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_open_way_is_a_line_and_a_closed_one_an_area_only_if_tagged() {
        let square = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0), (0.0, 0.0)];
        // Tagged an area: a ring.
        match way_geometry(&square, true).expect("area") {
            Geometry::Polygons(polygons) => {
                assert_eq!(polygons.len(), 1);
                assert_eq!(polygons[0][0].len(), 5, "already closed, so nothing is added");
            }
            other => panic!("{other:?}"),
        }
        // The same shape untagged: a loop road, which is a line.
        assert!(matches!(way_geometry(&square, false), Some(Geometry::Lines(_))));
    }

    /// An extract that cuts through a way leaves it unclosed. Closing it is what `osmium export`
    /// does; dropping it would punch a hole in the coastline at every extract boundary.
    #[test]
    fn an_unclosed_area_is_closed_rather_than_dropped() {
        let open = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)];
        match way_geometry(&open, true).expect("closed") {
            Geometry::Polygons(polygons) => {
                let ring = &polygons[0][0];
                assert_eq!(ring.len(), 4);
                assert_eq!(ring.first(), ring.last(), "the ring closes");
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn a_degenerate_way_produces_nothing() {
        assert!(way_geometry(&[], true).is_none());
        assert!(way_geometry(&[(0.0, 0.0)], true).is_none());
        assert!(way_geometry(&[(0.0, 0.0), (1.0, 1.0)], true).is_none(), "two points bound no area");
        assert!(way_geometry(&[(0.0, 0.0)], false).is_none(), "one point is not a line");
        // But two points are a line.
        assert!(way_geometry(&[(0.0, 0.0), (1.0, 1.0)], false).is_some());
    }
}
