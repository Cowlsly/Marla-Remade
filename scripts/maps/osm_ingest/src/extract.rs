//! Vector-layer extraction: `.osm.pbf` -> one geojsonseq per layer.
//!
//! The `osmium tags-filter | osmium export | normalize_*.py` chain, done in one
//! pass over the PBF with no external tools. Each layer's schema lives in its own
//! module ([`crate::safety`] and so on); this module owns the PBF traversal, the
//! bbox filter, the deterministic ordering and the output file.
//!
//! ## Pass ordering is fixed, and reversed
//!
//! Layers that need way or relation geometry cannot be done in one pass, because
//! a PBF stores nodes before the ways that reference them. So the traversal runs
//! **relations, then ways, then nodes** -- the reverse of the file order:
//!
//! 1. **Relations** decide which ways matter (a boundary's member ways, a route's
//!    member ways).
//! 2. **Ways** decide which node coordinates matter -- both their own refs and the
//!    refs of the ways relations claimed.
//! 3. **Nodes** supply those coordinates, and any node-based features.
//!
//! Only the passes a layer actually needs are run. `safety` is node-based, so it
//! runs one. The first pass to run is given `blob_kinds = None`, which is what
//! makes [`crate::pbf::run_pass`] build the per-blob kind mask that later passes
//! use to skip whole blobs.
//!
//! When way and relation layers land here, their node coordinates must be stored
//! the way [`crate::poi_build`] does it -- a sorted `Vec` of the needed ids plus an
//! index-aligned coordinate array, looked up by `binary_search` -- and **not** the
//! way [`crate::graph_build`] does it. That module allocates a bitset sized from
//! `BITSET_SIZE = 20e9`, i.e. 2.5 GB keyed by raw node id, and peaks around 10 GB
//! on California. The sorted-`Vec` form costs 16 bytes per *needed* node, which
//! for a selected subset is orders of magnitude smaller.

use std::collections::HashMap;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

use crate::bbox::{self, BBox};
use crate::geojson::{self, Coord, Feature, Geometry};
use crate::maxspeed::{self, MaxspeedTags};
use crate::osm::{visit_block, Element, Member, MEMBER_WAY};
use crate::pbf::{self, KIND_NODES, KIND_RELATIONS, KIND_WAYS};
use crate::proto::{Error, Result};
use crate::safety::{self, Kind, SafetyTags};
use crate::select::Select;
use crate::transit_lines::{self, TransitTags};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Layer {
    Safety,
    Maxspeed,
    TransitLines,
}

impl Layer {
    pub fn parse(s: &str) -> std::result::Result<Layer, String> {
        match s {
            "safety" => Ok(Layer::Safety),
            "maxspeed" => Ok(Layer::Maxspeed),
            "transit_lines" => Ok(Layer::TransitLines),
            other => Err(format!(
                "unknown --layer '{other}'; supported: {}",
                Layer::names().join(", ")
            )),
        }
    }

    pub fn names() -> Vec<&'static str> {
        vec!["safety", "maxspeed", "transit_lines"]
    }

    pub fn name(self) -> &'static str {
        match self {
            Layer::Safety => "safety",
            Layer::Maxspeed => "maxspeed",
            Layer::TransitLines => "transit_lines",
        }
    }
}

pub struct Options {
    pub layer: Layer,
    pub bbox: Option<BBox>,
}

#[derive(Debug, Default, PartialEq)]
pub struct Stats {
    pub features: usize,
    pub from_nodes: usize,
    pub from_ways: usize,
    pub from_relations: usize,
    /// Classified, but outside `--bbox`. Reported so an empty layer can be told
    /// apart from a badly placed box.
    pub outside_bbox: usize,
}

pub fn build(input: &Path, out: &Path, opts: &Options) -> Result<Stats> {
    // Fail before the passes rather than after them if the output is unwritable.
    if let Some(parent) = out.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)
            .map_err(|e| Error(format!("cannot create {}: {e}", parent.display())))?;
    }

    let blobs = pbf::scan_blobs(input)?;
    println!("Scanned {} data blob(s) in {}", blobs.len(), input.display());

    match opts.layer {
        Layer::Safety => build_safety(input, &blobs, out, opts),
        Layer::Maxspeed => build_maxspeed(input, &blobs, out, opts),
        Layer::TransitLines => build_transit_lines(input, &blobs, out, opts),
    }
}

// --- the node coordinate table --------------------------------------------

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
fn resolve_nodes(
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

// --- safety ---------------------------------------------------------------

/// One classified node, with the attributes it carries copied out of the block.
///
/// The pass borrows tag values from the `PrimitiveBlock` it is reading, which is
/// dropped when the blob is, so anything kept has to be owned.
struct SafetyRow {
    lat_e7: i32,
    lon_e7: i32,
    id: i64,
    kind: Kind,
    name: Option<String>,
    direction: Option<String>,
    operator: Option<String>,
    reference: Option<String>,
    surveillance_type: Option<String>,
}

#[derive(Default)]
struct SafetyPass {
    rows: Vec<SafetyRow>,
    outside_bbox: usize,
}

fn build_safety(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    out: &Path,
    opts: &Options,
) -> Result<Stats> {
    // The `osmium tags-filter` expression this layer used, as a cheap screen: it
    // reads three tags to reject almost every node, instead of the ten the
    // classifier and the property builder would read between them. Tag lookup is
    // a linear scan, so that matters at planet scale.
    let select = Select::parse(&safety::FILTERS)?;
    let bbox = opts.bbox.as_ref();

    let (chunks, _) = pbf::run_pass(
        input,
        blobs,
        None,
        KIND_NODES,
        "Pass 1: nodes",
        SafetyPass::default,
        |state, block| safety_blob(state, block, &select, bbox),
    )?;

    let mut rows: Vec<SafetyRow> = Vec::new();
    let mut outside_bbox = 0usize;
    for chunk in chunks {
        rows.extend(chunk.rows);
        outside_bbox += chunk.outside_bbox;
    }

    // Deterministic order, so two runs of the same input are byte-identical and a
    // diff against the previous build shows only real changes. Position first
    // keeps features that are near each other near each other in the file, which
    // is what the tiler wants; the id only breaks ties.
    rows.sort_by_key(|r| (r.lat_e7, r.lon_e7, r.id));

    let mut writer = BufWriter::new(create(out)?);
    let mut line: Vec<u8> = Vec::new();
    for row in &rows {
        let tags = SafetyTags {
            name: row.name.as_deref(),
            direction: row.direction.as_deref(),
            operator: row.operator.as_deref(),
            reference: row.reference.as_deref(),
            surveillance_type: row.surveillance_type.as_deref(),
            ..Default::default()
        };
        let f: Feature = safety::feature(
            row.kind,
            &tags,
            row.lon_e7 as f64 * 1e-7,
            row.lat_e7 as f64 * 1e-7,
            row.id,
        );
        geojson::write_feature(&mut writer, &f, &mut line).map_err(io_err)?;
    }
    writer.flush().map_err(io_err)?;

    println!(
        "Wrote {} safety feature(s) to {}",
        rows.len(),
        out.display()
    );
    if outside_bbox > 0 {
        println!("{outside_bbox} feature(s) dropped by --bbox");
    }
    Ok(Stats {
        features: rows.len(),
        from_nodes: rows.len(),
        from_ways: 0,
        from_relations: 0,
        outside_bbox,
    })
}

fn safety_blob(
    state: &mut SafetyPass,
    block: &pbf::PrimitiveBlock,
    select: &Select,
    bbox: Option<&BBox>,
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_NODES, &mut kinds, &mut |el: Element| {
        if let Element::Node(n) = el {
            if n.tags.is_empty() {
                return Ok(());
            }
            if !select.matches(|k| n.tags.get_str(k)) {
                return Ok(());
            }
            let t = SafetyTags {
                highway: n.tags.get_str("highway"),
                man_made: n.tags.get_str("man_made"),
                enforcement: n.tags.get_str("enforcement"),
                surveillance_type: n.tags.get_str("surveillance:type"),
                camera_type: n.tags.get_str("camera:type"),
                operator: n.tags.get_str("operator"),
                manufacturer: n.tags.get_str("manufacturer"),
                name: n.tags.get_str("name"),
                direction: n.tags.get_str("direction"),
                reference: n.tags.get_str("ref"),
            };
            let Some(kind) = safety::classify(&t) else {
                return Ok(());
            };
            // Counted after classification, so the number means "safety features
            // outside the box" rather than "nodes outside the box".
            if !bbox::keep_e7(bbox, n.lat_e7, n.lon_e7) {
                state.outside_bbox += 1;
                return Ok(());
            }
            state.rows.push(SafetyRow {
                lat_e7: n.lat_e7,
                lon_e7: n.lon_e7,
                id: n.id,
                kind,
                name: t.name.map(str::to_string),
                direction: t.direction.map(str::to_string),
                operator: t.operator.map(str::to_string),
                reference: t.reference.map(str::to_string),
                surveillance_type: t.surveillance_type.map(str::to_string),
            });
        }
        Ok(())
    })?;
    Ok(kinds)
}

// --- maxspeed -------------------------------------------------------------

/// One way with a posted limit, with its tag values copied out of the block.
struct MaxspeedRow {
    id: i64,
    refs: Vec<i64>,
    maxspeed: String,
    highway: Option<String>,
    name: Option<String>,
}

fn build_maxspeed(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    out: &Path,
    opts: &Options,
) -> Result<Stats> {
    let select = Select::parse(&maxspeed::FILTERS)?;

    // Pass 1 runs with `blob_kinds = None`, which is what makes run_pass build the
    // per-blob kind mask the node pass then uses to skip node-only blobs.
    let (chunks, blob_kinds) = pbf::run_pass(
        input,
        blobs,
        None,
        KIND_WAYS,
        "Pass 1: ways",
        Vec::<MaxspeedRow>::new,
        |state: &mut Vec<MaxspeedRow>, block| maxspeed_blob(state, block, &select),
    )?;
    let mut rows: Vec<MaxspeedRow> = Vec::new();
    for chunk in chunks {
        rows.extend(chunk);
    }
    println!("{} way(s) with a posted limit", rows.len());

    let table = NodeLocations::new(rows.iter().flat_map(|r| r.refs.iter().copied()).collect());
    println!("{} node location(s) needed", table.len());
    let table = resolve_nodes(input, blobs, &blob_kinds, "Pass 2: nodes", table)?;

    let mut lines: Vec<LineFeature> = Vec::with_capacity(rows.len());
    let mut outside_bbox = 0usize;
    for row in &rows {
        let coords = table.line(&row.refs);
        if coords.len() < 2 {
            continue;
        }
        if !parts_touch_bbox(std::slice::from_ref(&coords), opts.bbox.as_ref()) {
            outside_bbox += 1;
            continue;
        }
        let t = MaxspeedTags {
            maxspeed: Some(row.maxspeed.as_str()),
            highway: row.highway.as_deref(),
            name: row.name.as_deref(),
            ..Default::default()
        };
        let f = maxspeed::feature(&row.maxspeed, &t, Geometry::LineString(coords), row.id);
        lines.push(LineFeature { sort: ("way", row.id), rendered: render(&f) });
    }

    let written = write_lines(out, lines)?;
    println!("Wrote {} maxspeed feature(s) to {}", written, out.display());
    if outside_bbox > 0 {
        println!("{outside_bbox} feature(s) dropped by --bbox");
    }
    Ok(Stats {
        features: written,
        from_nodes: 0,
        from_ways: written,
        from_relations: 0,
        outside_bbox,
    })
}

fn maxspeed_blob(
    state: &mut Vec<MaxspeedRow>,
    block: &pbf::PrimitiveBlock,
    select: &Select,
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
        if let Element::Way(w) = el {
            if w.refs.len() < 2 || w.tags.is_empty() {
                return Ok(());
            }
            if !select.matches(|k| w.tags.get_str(k)) {
                return Ok(());
            }
            let t = MaxspeedTags {
                maxspeed: w.tags.get_str("maxspeed"),
                maxspeed_forward: w.tags.get_str("maxspeed:forward"),
                maxspeed_backward: w.tags.get_str("maxspeed:backward"),
                highway: w.tags.get_str("highway"),
                name: w.tags.get_str("name"),
            };
            if let Some(value) = maxspeed::extract(&t) {
                state.push(MaxspeedRow {
                    id: w.id,
                    refs: w.refs.to_vec(),
                    maxspeed: value.to_string(),
                    highway: t.highway.map(str::to_string),
                    name: t.name.map(str::to_string),
                });
            }
        }
        Ok(())
    })?;
    Ok(kinds)
}

// --- transit_lines --------------------------------------------------------

/// One classified way or relation, with its tag values copied out of the block.
struct TransitRow {
    /// `"way"` or `"relation"`, for the `osm_id` and for the sort.
    element: &'static str,
    id: i64,
    kind: &'static str,
    name: Option<String>,
    reference: Option<String>,
    colour: Option<String>,
    /// A way has one part. A relation has one per member way, in member order.
    /// Empty until the way pass fills a relation's in.
    parts: Vec<Vec<i64>>,
    /// A relation's member way ids, in member order.
    member_ways: Vec<i64>,
}

#[derive(Default)]
struct TransitWayPass {
    rows: Vec<TransitRow>,
    /// `(way id, its refs)` for ways a relation claimed. A per-chunk assoc list
    /// rather than a map: cheap to push, and the merge is single-threaded anyway.
    wanted: Vec<(i64, Vec<i64>)>,
}

fn build_transit_lines(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    out: &Path,
    opts: &Options,
) -> Result<Stats> {
    let way_select = Select::parse(&transit_lines::WAY_FILTERS)?;
    let rel_select = Select::parse(&transit_lines::RELATION_FILTERS)?;

    // Pass 1: relations, which decide which ways matter.
    let (chunks, blob_kinds) = pbf::run_pass(
        input,
        blobs,
        None,
        KIND_RELATIONS,
        "Pass 1: relations",
        Vec::<TransitRow>::new,
        |state: &mut Vec<TransitRow>, block| transit_relation_blob(state, block, &rel_select),
    )?;
    let mut rel_rows: Vec<TransitRow> = Vec::new();
    for chunk in chunks {
        rel_rows.extend(chunk);
    }
    let mut wanted_ways: Vec<i64> = rel_rows
        .iter()
        .flat_map(|r| r.member_ways.iter().copied())
        .collect();
    wanted_ways.sort_unstable();
    wanted_ways.dedup();
    println!(
        "{} route relation(s), {} member way(s)",
        rel_rows.len(),
        wanted_ways.len()
    );

    // Pass 2: ways, which are both features in their own right and the geometry
    // the relations need.
    let (chunks, _) = pbf::run_pass(
        input,
        blobs,
        Some(&blob_kinds),
        KIND_WAYS,
        "Pass 2: ways",
        TransitWayPass::default,
        |state, block| transit_way_blob(state, block, &way_select, &wanted_ways),
    )?;
    let mut way_rows: Vec<TransitRow> = Vec::new();
    let mut member_refs: HashMap<i64, Vec<i64>> = HashMap::new();
    for chunk in chunks {
        way_rows.extend(chunk.rows);
        member_refs.extend(chunk.wanted);
    }
    println!("{} railway way(s)", way_rows.len());

    // A relation's parts, in member order. Members whose way is missing from the
    // extract are simply absent, which is the same thing GDAL produced.
    for row in &mut rel_rows {
        row.parts = row
            .member_ways
            .iter()
            .filter_map(|w| member_refs.get(w).cloned())
            .collect();
    }

    // Pass 3: node coordinates for everything both earlier passes collected.
    let table = NodeLocations::new(
        way_rows
            .iter()
            .chain(rel_rows.iter())
            .flat_map(|r| r.parts.iter().flat_map(|p| p.iter().copied()))
            .collect(),
    );
    println!("{} node location(s) needed", table.len());
    let table = resolve_nodes(input, blobs, &blob_kinds, "Pass 3: nodes", table)?;

    let mut lines: Vec<LineFeature> = Vec::new();
    let mut outside_bbox = 0usize;
    let mut from_ways = 0usize;
    let mut from_relations = 0usize;
    for row in way_rows.iter().chain(rel_rows.iter()) {
        let parts: Vec<Vec<Coord>> = row
            .parts
            .iter()
            .map(|refs| table.line(refs))
            .filter(|p| p.len() >= 2)
            .collect();
        if parts.is_empty() {
            continue;
        }
        if !parts_touch_bbox(&parts, opts.bbox.as_ref()) {
            outside_bbox += 1;
            continue;
        }
        // A way is one line; a relation is a MultiLineString of its member ways,
        // deliberately unstitched. See the module docs in `transit_lines`.
        let geometry = if row.element == "way" {
            Geometry::LineString(parts.into_iter().next().expect("non-empty"))
        } else {
            Geometry::MultiLineString(parts)
        };
        let t = TransitTags {
            name: row.name.as_deref(),
            reference: row.reference.as_deref(),
            colour: row.colour.as_deref(),
            ..Default::default()
        };
        let f = transit_lines::feature(row.kind, &t, geometry, row.element, row.id);
        lines.push(LineFeature {
            sort: (row.element, row.id),
            rendered: render(&f),
        });
        if row.element == "way" {
            from_ways += 1;
        } else {
            from_relations += 1;
        }
    }

    let written = write_lines(out, lines)?;
    println!(
        "Wrote {written} transit_lines feature(s) to {} ({from_ways} way, {from_relations} relation)",
        out.display()
    );
    if outside_bbox > 0 {
        println!("{outside_bbox} feature(s) dropped by --bbox");
    }
    Ok(Stats {
        features: written,
        from_nodes: 0,
        from_ways,
        from_relations,
        outside_bbox,
    })
}

fn transit_relation_blob(
    state: &mut Vec<TransitRow>,
    block: &pbf::PrimitiveBlock,
    select: &Select,
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_RELATIONS, &mut kinds, &mut |el: Element| {
        if let Element::Relation(r) = el {
            if !select.matches(|k| r.tags.get_str(k)) {
                return Ok(());
            }
            let t = TransitTags {
                railway: r.tags.get_str("railway"),
                type_: r.tags.get_str("type"),
                route: r.tags.get_str("route"),
                name: r.tags.get_str("name"),
                reference: r.tags.get_str("ref"),
                colour: r.tags.get_str("colour"),
                color: r.tags.get_str("color"),
            };
            let Some(kind) = transit_lines::classify(&t) else {
                return Ok(());
            };
            // Every way member, whatever its role: a route's `forward`/`backward`
            // members are as much of the line as its unroled ones.
            let member_ways: Vec<i64> = r
                .members
                .iter()
                .filter(|m: &&Member| m.kind == MEMBER_WAY)
                .map(|m| m.id)
                .collect();
            if member_ways.is_empty() {
                return Ok(());
            }
            state.push(TransitRow {
                element: "relation",
                id: r.id,
                kind,
                name: t.name.map(str::to_string),
                reference: t.reference.map(str::to_string),
                colour: transit_lines::colour(&t).map(str::to_string),
                parts: Vec::new(),
                member_ways,
            });
        }
        Ok(())
    })?;
    Ok(kinds)
}

fn transit_way_blob(
    state: &mut TransitWayPass,
    block: &pbf::PrimitiveBlock,
    select: &Select,
    wanted: &[i64],
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
        if let Element::Way(w) = el {
            if w.refs.len() < 2 {
                return Ok(());
            }
            if wanted.binary_search(&w.id).is_ok() {
                state.wanted.push((w.id, w.refs.to_vec()));
            }
            if w.tags.is_empty() || !select.matches(|k| w.tags.get_str(k)) {
                return Ok(());
            }
            let t = TransitTags {
                railway: w.tags.get_str("railway"),
                type_: w.tags.get_str("type"),
                route: w.tags.get_str("route"),
                name: w.tags.get_str("name"),
                reference: w.tags.get_str("ref"),
                colour: w.tags.get_str("colour"),
                color: w.tags.get_str("color"),
            };
            if let Some(kind) = transit_lines::classify(&t) {
                state.rows.push(TransitRow {
                    element: "way",
                    id: w.id,
                    kind,
                    name: t.name.map(str::to_string),
                    reference: t.reference.map(str::to_string),
                    colour: transit_lines::colour(&t).map(str::to_string),
                    parts: vec![w.refs.to_vec()],
                    member_ways: Vec::new(),
                });
            }
        }
        Ok(())
    })?;
    Ok(kinds)
}

// --- shared output --------------------------------------------------------

/// A rendered feature plus the key it sorts on.
///
/// Rendering before the sort means the sort moves one `Vec<u8>` per feature rather
/// than a geometry, and the writer then has nothing left to decide.
struct LineFeature {
    sort: (&'static str, i64),
    rendered: Vec<u8>,
}

fn render(f: &Feature) -> Vec<u8> {
    let mut line = Vec::new();
    geojson::render_feature(f, &mut line);
    line
}

/// `true` when there is no bbox, or any vertex of any part is inside it.
///
/// Whole-element, matching `osmium extract`'s default `complete_ways` strategy: a
/// way with one node in the box is kept entire, overspill included. Truncating it
/// at the boundary would invent a vertex that is not in OSM, and the tiler clips
/// properly per tile later anyway.
fn parts_touch_bbox(parts: &[Vec<Coord>], bbox: Option<&BBox>) -> bool {
    let Some(b) = bbox else { return true };
    parts
        .iter()
        .flatten()
        .any(|(lon, lat)| b.contains(*lon, *lat))
}

/// Sort by `(element kind, id)` and write. Deterministic, so two runs of the same
/// input are byte-identical and a diff against the previous build shows only real
/// changes.
fn write_lines(out: &Path, mut lines: Vec<LineFeature>) -> Result<usize> {
    lines.sort_by_key(|l| l.sort);
    let mut writer = BufWriter::new(create(out)?);
    for l in &lines {
        writer.write_all(&l.rendered).map_err(io_err)?;
    }
    writer.flush().map_err(io_err)?;
    Ok(lines.len())
}

fn create(path: &Path) -> Result<File> {
    File::create(path).map_err(|e| Error(format!("cannot write {}: {e}", path.display())))
}

fn io_err(e: std::io::Error) -> Error {
    Error(e.to_string())
}

// --- CLI ------------------------------------------------------------------

pub struct Args {
    pub input: PathBuf,
    pub out: PathBuf,
    pub layer: Layer,
    pub bbox: Option<BBox>,
}

/// `osm_extract IN.osm.pbf --layer NAME --out FILE [--bbox BOX]`
pub fn parse_args(args: &[String]) -> std::result::Result<Args, String> {
    let mut input: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut layer: Option<Layer> = None;
    let mut bbox: Option<BBox> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            flag @ ("--out" | "--layer" | "--bbox") => {
                i += 1;
                let value = args
                    .get(i)
                    .ok_or_else(|| format!("{flag} needs a value"))?
                    .as_str();
                match flag {
                    "--out" => out = Some(PathBuf::from(value)),
                    "--layer" => layer = Some(Layer::parse(value)?),
                    _ => bbox = Some(BBox::parse(value).map_err(|e| e.0)?),
                }
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
    Ok(Args {
        input: input.ok_or_else(|| "missing IN.osm.pbf".to_string())?,
        out: out.ok_or_else(|| "--out is required".to_string())?,
        layer: layer.ok_or_else(|| "--layer is required".to_string())?,
        bbox,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testpbf;

    fn features(path: &Path) -> Vec<String> {
        std::fs::read_to_string(path)
            .unwrap()
            .lines()
            .map(str::to_string)
            .collect()
    }

    #[test]
    fn extracts_the_safety_layer_from_a_pbf() {
        let (pbf_path, dir) = testpbf::write_layers_sample("extract_safety");
        let out = dir.join("safety.geojsonseq");
        let stats = build(
            &pbf_path,
            &out,
            &Options {
                layer: Layer::Safety,
                bbox: None,
            },
        )
        .unwrap();

        // The fixture's four safety nodes: a speed camera, an ALPR, a stop sign
        // and traffic signals. Everything else in it belongs to other layers.
        assert_eq!(stats.features, 4);
        assert_eq!(stats.from_nodes, 4);
        assert_eq!((stats.from_ways, stats.from_relations), (0, 0));

        let lines = features(&out);
        assert_eq!(lines.len(), 4);
        let kinds: Vec<&str> = ["speed_camera", "alpr", "stop_sign", "traffic_signals"]
            .into_iter()
            .filter(|k| lines.iter().any(|l| l.contains(&format!("\"kind\":\"{k}\""))))
            .collect();
        assert_eq!(kinds, ["speed_camera", "alpr", "stop_sign", "traffic_signals"]);

        // Every line is a Point Feature with an osm_id in the node/N form.
        for l in &lines {
            assert!(l.starts_with("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\""), "{l}");
            assert!(l.contains("\"osm_id\":\"node/"), "{l}");
        }

        // The ALPR node keeps its operator and surveillance_type.
        let alpr = lines.iter().find(|l| l.contains("\"alpr\"")).unwrap();
        assert!(alpr.contains("\"operator\":\"Flock Safety\""), "{alpr}");
        assert!(alpr.contains("\"surveillance_type\":\"ALPR\""), "{alpr}");

        // The cafe node has a name and tags, but is not road furniture.
        assert!(!lines.iter().any(|l| l.contains("Corner Cafe")));
    }

    #[test]
    fn two_runs_are_byte_identical() {
        let (pbf_path, dir) = testpbf::write_layers_sample("extract_det");
        let run = |suffix: &str| {
            let out = dir.join(format!("safety{suffix}.geojsonseq"));
            build(
                &pbf_path,
                &out,
                &Options {
                    layer: Layer::Safety,
                    bbox: None,
                },
            )
            .unwrap();
            std::fs::read(out).unwrap()
        };
        assert_eq!(run("a"), run("b"));
    }

    #[test]
    fn a_bbox_that_excludes_everything_yields_an_empty_layer() {
        let (pbf_path, dir) = testpbf::write_layers_sample("extract_bbox");
        let out = dir.join("safety.geojsonseq");
        // The fixture sits near 37N 122W; this box is over the Atlantic.
        let stats = build(
            &pbf_path,
            &out,
            &Options {
                layer: Layer::Safety,
                bbox: Some(BBox::parse("-30,20,-20,30").unwrap()),
            },
        )
        .unwrap();
        assert_eq!(stats.features, 0);
        // The count says "4 safety features were outside the box", which is what
        // distinguishes a bad bbox from a PBF with no cameras in it.
        assert_eq!(stats.outside_bbox, 4);
        assert_eq!(features(&out).len(), 0);
    }

    #[test]
    fn a_bbox_that_includes_everything_changes_nothing() {
        let (pbf_path, dir) = testpbf::write_layers_sample("extract_bbox_all");
        let out = dir.join("safety.geojsonseq");
        let stats = build(
            &pbf_path,
            &out,
            &Options {
                layer: Layer::Safety,
                bbox: Some(BBox::parse("-123,36,-121,38").unwrap()),
            },
        )
        .unwrap();
        assert_eq!(stats.features, 4);
        assert_eq!(stats.outside_bbox, 0);
    }

    // --- maxspeed ---------------------------------------------------------

    fn extract_layer(tag: &str, layer: Layer) -> (Vec<String>, Stats) {
        let (pbf_path, dir) = testpbf::write_layers_sample(tag);
        let out = dir.join(format!("{}.geojsonseq", layer.name()));
        let stats = build(&pbf_path, &out, &Options { layer, bbox: None }).unwrap();
        (features(&out), stats)
    }

    #[test]
    fn extracts_the_maxspeed_layer_with_its_raw_values() {
        let (lines, stats) = extract_layer("extract_maxspeed", Layer::Maxspeed);
        // Two of the fixture's three highway ways carry a limit; the third does not.
        assert_eq!((stats.features, stats.from_ways), (2, 2));
        assert_eq!(stats.from_nodes, 0);
        assert_eq!(lines.len(), 2);

        // The whole point of the layer: the string survives verbatim.
        let mph = lines.iter().find(|l| l.contains("way/3001")).unwrap();
        assert!(mph.contains("\"maxspeed\":\"25 mph\""), "{mph}");
        assert!(mph.contains("\"highway\":\"residential\""), "{mph}");
        assert!(mph.contains("\"name\":\"Main St\""), "{mph}");
        assert!(mph.contains("\"type\":\"LineString\""), "{mph}");

        // maxspeed=none is kept as "none", and beats the directional tag.
        let none = lines.iter().find(|l| l.contains("way/3002")).unwrap();
        assert!(none.contains("\"maxspeed\":\"none\""), "{none}");
        assert!(!none.contains("30 mph"), "the direction-agnostic tag wins: {none}");

        // The way with no limit is absent.
        assert!(!lines.iter().any(|l| l.contains("way/3003")));

        // Geometry came from resolved node coordinates, not from nowhere.
        assert!(mph.contains("-122.43"), "{mph}");
    }

    // --- transit_lines ----------------------------------------------------

    #[test]
    fn extracts_the_transit_lines_layer_from_ways_and_relations() {
        let (lines, stats) = extract_layer("extract_transit", Layer::TransitLines);
        // Two railway ways plus one route relation. The platform way and the bus
        // route are both dropped.
        assert_eq!(stats.features, 3);
        assert_eq!((stats.from_ways, stats.from_relations), (2, 1));
        assert_eq!(lines.len(), 3);

        let subway = lines.iter().find(|l| l.contains("way/5001")).unwrap();
        assert!(subway.contains("\"kind\":\"subway\""), "{subway}");
        assert!(subway.contains("\"name\":\"Market St Subway\""), "{subway}");
        assert!(subway.contains("\"type\":\"LineString\""), "{subway}");

        // narrow_gauge folds into rail.
        let ng = lines.iter().find(|l| l.contains("way/5002")).unwrap();
        assert!(ng.contains("\"kind\":\"rail\""), "{ng}");

        assert!(!lines.iter().any(|l| l.contains("way/5003")), "no platform");
        assert!(!lines.iter().any(|l| l.contains("relation/9002")), "no bus route");
    }

    #[test]
    fn a_route_relation_becomes_an_unstitched_multilinestring() {
        let (lines, _) = extract_layer("extract_transit_rel", Layer::TransitLines);
        let route = lines.iter().find(|l| l.contains("relation/9001")).unwrap();
        assert!(route.contains("\"type\":\"MultiLineString\""), "{route}");
        assert!(route.contains("\"kind\":\"subway\""), "{route}");
        // The tags GDAL used to bury in an `other_tags` HSTORE are now just tags.
        assert!(route.contains("\"name\":\"Red Line\""), "{route}");
        assert!(route.contains("\"ref\":\"Red\""), "{route}");
        assert!(route.contains("\"colour\":\"#DA291C\""), "{route}");

        // Two member ways, so two parts: `[[[...]],[[...]]]` is three opening
        // brackets before the first coordinate pair.
        let at = route.find("\"coordinates\":").unwrap();
        assert!(route[at..].starts_with("\"coordinates\":[[["), "{route}");
        // Counting the part separators: two parts means one `]],[[`.
        assert_eq!(route.matches("]],[[").count(), 1, "two parts: {route}");
    }

    #[test]
    fn the_line_layers_are_deterministic_and_bbox_filtered() {
        for layer in [Layer::Maxspeed, Layer::TransitLines] {
            let (pbf_path, dir) = testpbf::write_layers_sample(&format!("det_{}", layer.name()));
            let run = |suffix: &str| {
                let out = dir.join(format!("{}{suffix}.geojsonseq", layer.name()));
                build(&pbf_path, &out, &Options { layer, bbox: None }).unwrap();
                std::fs::read(out).unwrap()
            };
            assert_eq!(run("a"), run("b"), "{} is not deterministic", layer.name());

            // The fixture's ways sit near 37.79N 122.42W; this box is Atlantic.
            let out = dir.join("empty.geojsonseq");
            let stats = build(
                &pbf_path,
                &out,
                &Options {
                    layer,
                    bbox: Some(BBox::parse("-30,20,-20,30").unwrap()),
                },
            )
            .unwrap();
            assert_eq!(stats.features, 0, "{}", layer.name());
            assert!(stats.outside_bbox > 0, "{}", layer.name());

            // And a box that contains them changes nothing.
            let out = dir.join("all.geojsonseq");
            let all = build(
                &pbf_path,
                &out,
                &Options {
                    layer,
                    bbox: Some(BBox::parse("-123,36,-121,38").unwrap()),
                },
            )
            .unwrap();
            assert_eq!(all.outside_bbox, 0, "{}", layer.name());
            assert!(all.features > 0, "{}", layer.name());
        }
    }

    // --- the coordinate table ---------------------------------------------

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

    #[test]
    fn args_need_a_layer_and_an_out() {
        let ok = parse_args(&[
            "in.pbf".into(),
            "--layer".into(),
            "safety".into(),
            "--out".into(),
            "s.geojsonseq".into(),
            "--bbox".into(),
            "-122.6,37.2,-121.7,37.9".into(),
        ])
        .unwrap();
        assert_eq!(ok.input, PathBuf::from("in.pbf"));
        assert_eq!(ok.layer, Layer::Safety);
        assert!(ok.bbox.is_some());

        assert!(parse_args(&["in.pbf".into()]).is_err());
        assert!(parse_args(&["in.pbf".into(), "--layer".into(), "safety".into()]).is_err());
        assert!(parse_args(&["--layer".into(), "safety".into(), "--out".into(), "o".into()]).is_err());
        // A bad layer name names the ones that do exist.
        let err = parse_args(&[
            "in.pbf".into(),
            "--layer".into(),
            "nope".into(),
            "--out".into(),
            "o".into(),
        ])
        .map(|_| ())
        .unwrap_err();
        assert!(err.contains("safety"), "{err}");
        // A malformed bbox is rejected here, not silently ignored.
        assert!(parse_args(&[
            "in.pbf".into(),
            "--layer".into(),
            "safety".into(),
            "--out".into(),
            "o".into(),
            "--bbox".into(),
            "1,2,3".into(),
        ])
        .is_err());
    }
}
