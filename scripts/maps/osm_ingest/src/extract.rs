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

use crate::admin::{self, AdminTags};
use crate::bbox::{self, BBox};
use crate::geojson::{self, Coord, Feature, Geometry};
use crate::maxspeed::{self, MaxspeedTags};
use crate::nodeloc::{resolve_nodes, NodeLocations};
use crate::osm::{visit_block, Element, Member, MEMBER_WAY};
use crate::pbf::{self, KIND_NODES, KIND_RELATIONS, KIND_WAYS};
use crate::proto::{Error, Result};
use crate::rings::{self, MemberWay, RingStats};
use crate::roads::{self, RoadTags};
use crate::safety::{self, Kind, SafetyTags};
use crate::select::Select;
use crate::transit_lines::{self, TransitTags};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Layer {
    Safety,
    Maxspeed,
    Roads,
    TransitLines,
    AdminCity,
}

impl Layer {
    pub fn parse(s: &str) -> std::result::Result<Layer, String> {
        match s {
            "safety" => Ok(Layer::Safety),
            "maxspeed" => Ok(Layer::Maxspeed),
            "roads" => Ok(Layer::Roads),
            "transit_lines" => Ok(Layer::TransitLines),
            "admin_city" => Ok(Layer::AdminCity),
            other => Err(format!(
                "unknown --layer '{other}'; supported: {}",
                Layer::names().join(", ")
            )),
        }
    }

    pub fn names() -> Vec<&'static str> {
        vec!["safety", "maxspeed", "roads", "transit_lines", "admin_city"]
    }

    pub fn name(self) -> &'static str {
        match self {
            Layer::Safety => "safety",
            Layer::Maxspeed => "maxspeed",
            Layer::Roads => "roads",
            Layer::TransitLines => "transit_lines",
            Layer::AdminCity => "admin_city",
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

impl Stats {
    /// A layer assembled from two element kinds, reporting none of one of them.
    ///
    /// `transit_lines` is the case that matters: a rail corridor is covered by both
    /// a `railway=*` way and a `type=route` relation, and only the relation carries
    /// `colour`, `ref` and the route's `name`. A ways-only layer therefore looks
    /// full, has the right feature count, and renders every line in the app's grey
    /// `rail` fallback. It is not proof of a broken build -- a rural extract really
    /// can hold track that no PTv2 route covers -- so this is a warning, and the
    /// build scripts own the checks that can be certain.
    pub fn missing_half(&self, layer: Layer) -> Option<String> {
        if layer != Layer::TransitLines || self.from_relations > 0 || self.from_ways == 0 {
            return None;
        }
        Some(format!(
            "{} railway way(s) but 0 route relation(s). Only relations carry `colour`, \
             `ref` and the route `name`, so every line will render grey.",
            self.from_ways
        ))
    }
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
        Layer::Roads => build_roads(input, &blobs, out, opts),
        Layer::TransitLines => build_transit_lines(input, &blobs, out, opts),
        Layer::AdminCity => build_admin_city(input, &blobs, out, opts),
    }
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

// --- roads ----------------------------------------------------------------

/// One road way, with every tag value the layer emits copied out of the block.
///
/// The tag strings are owned because the pass borrows them from the
/// `PrimitiveBlock` it is reading, which is dropped with the blob. Thirteen
/// `Option<String>` per road is the one place this layer is heavier than
/// `maxspeed`, and since this is every road rather than only the ones with a posted
/// limit, `build_roads` consumes the rows as it renders them rather than holding
/// both the rows and the output at once.
struct RoadRow {
    id: i64,
    refs: Vec<i64>,
    class: u8,
    maxspeed: Option<String>,
    lanes: Option<String>,
    lanes_forward: Option<String>,
    lanes_backward: Option<String>,
    turn_lanes: Option<String>,
    turn_lanes_forward: Option<String>,
    turn_lanes_backward: Option<String>,
    oneway: Option<String>,
    width: Option<String>,
    bridge: Option<String>,
    tunnel: Option<String>,
    layer: Option<String>,
}

impl RoadRow {
    fn tags(&self) -> RoadTags<'_> {
        RoadTags {
            // `highway` is not re-read: `class` already holds what it classified to.
            highway: None,
            maxspeed: self.maxspeed.as_deref(),
            lanes: self.lanes.as_deref(),
            lanes_forward: self.lanes_forward.as_deref(),
            lanes_backward: self.lanes_backward.as_deref(),
            turn_lanes: self.turn_lanes.as_deref(),
            turn_lanes_forward: self.turn_lanes_forward.as_deref(),
            turn_lanes_backward: self.turn_lanes_backward.as_deref(),
            oneway: self.oneway.as_deref(),
            width: self.width.as_deref(),
            bridge: self.bridge.as_deref(),
            tunnel: self.tunnel.as_deref(),
            layer: self.layer.as_deref(),
        }
    }
}

fn build_roads(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    out: &Path,
    opts: &Options,
) -> Result<Stats> {
    let select = Select::parse(&roads::FILTERS)?;

    // Same two passes as `maxspeed`: ways, then the node coordinates they need.
    let (chunks, blob_kinds) = pbf::run_pass(
        input,
        blobs,
        None,
        KIND_WAYS,
        "Pass 1: ways",
        Vec::<RoadRow>::new,
        |state: &mut Vec<RoadRow>, block| roads_blob(state, block, &select),
    )?;
    let mut rows: Vec<RoadRow> = Vec::new();
    for chunk in chunks {
        rows.extend(chunk);
    }
    println!("{} road way(s)", rows.len());

    let table = NodeLocations::new(rows.iter().flat_map(|r| r.refs.iter().copied()).collect());
    println!("{} node location(s) needed", table.len());
    let table = resolve_nodes(input, blobs, &blob_kinds, "Pass 2: nodes", table)?;

    let mut lines: Vec<LineFeature> = Vec::with_capacity(rows.len());
    let mut outside_bbox = 0usize;
    // `drain` rather than a borrow: at planet scale the rows and the rendered output
    // would otherwise both be resident, and the rows are the larger half.
    for row in rows.drain(..) {
        let coords = table.line(&row.refs);
        if coords.len() < 2 {
            continue;
        }
        if !parts_touch_bbox(std::slice::from_ref(&coords), opts.bbox.as_ref()) {
            outside_bbox += 1;
            continue;
        }
        let f = roads::feature(row.class, &row.tags(), Geometry::LineString(coords), row.id);
        lines.push(LineFeature { sort: ("way", row.id), rendered: render(&f) });
    }

    let written = write_lines(out, lines)?;
    println!("Wrote {} roads feature(s) to {}", written, out.display());
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

fn roads_blob(
    state: &mut Vec<RoadRow>,
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
            let t = RoadTags { highway: w.tags.get_str("highway"), ..Default::default() };
            let Some(class) = roads::classify(&t) else {
                return Ok(());
            };
            let own = |k: &str| w.tags.get_str(k).map(str::to_string);
            state.push(RoadRow {
                id: w.id,
                refs: w.refs.to_vec(),
                class,
                maxspeed: own("maxspeed"),
                lanes: own("lanes"),
                lanes_forward: own("lanes:forward"),
                lanes_backward: own("lanes:backward"),
                turn_lanes: own("turn:lanes"),
                turn_lanes_forward: own("turn:lanes:forward"),
                turn_lanes_backward: own("turn:lanes:backward"),
                oneway: own("oneway"),
                width: own("width"),
                bridge: own("bridge"),
                tunnel: own("tunnel"),
                layer: own("layer"),
            });
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
            // Way members that are the route's path. `forward`/`backward` count;
            // `platform*` and `stop*` do not - see [`transit_lines::member_is_path`].
            let member_ways: Vec<i64> = r
                .members
                .iter()
                .filter(|m: &&Member| {
                    m.kind == MEMBER_WAY && transit_lines::member_is_path(m.role)
                })
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

// --- admin_city -----------------------------------------------------------

/// One `admin_level=8` boundary relation, with its member ways and tags copied out.
struct AdminRow {
    id: i64,
    name: Option<String>,
    name_en: Option<String>,
    /// `(way id, is_outer)` in member order.
    members: Vec<(i64, bool)>,
}

#[derive(Default)]
struct AdminWayPass {
    /// `(way id, its refs)` for ways a boundary relation claimed.
    wanted: Vec<(i64, Vec<i64>)>,
}

fn build_admin_city(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    out: &Path,
    opts: &Options,
) -> Result<Stats> {
    let select = Select::parse(&admin::FILTERS)?;

    // Pass 1: relations, which decide which ways matter.
    let (chunks, blob_kinds) = pbf::run_pass(
        input,
        blobs,
        None,
        KIND_RELATIONS,
        "Pass 1: relations",
        Vec::<AdminRow>::new,
        |state: &mut Vec<AdminRow>, block| admin_relation_blob(state, block, &select),
    )?;
    let mut rows: Vec<AdminRow> = Vec::new();
    for chunk in chunks {
        rows.extend(chunk);
    }
    let mut wanted_ways: Vec<i64> = rows.iter().flat_map(|r| r.members.iter().map(|m| m.0)).collect();
    wanted_ways.sort_unstable();
    wanted_ways.dedup();
    println!(
        "{} admin_level=8 relation(s), {} member way(s)",
        rows.len(),
        wanted_ways.len()
    );

    // Pass 2: the member ways' node refs, IN ORDER. Nothing sorts or dedups them:
    // vertex order is the geometry. See the `rings` module docs.
    let (chunks, _) = pbf::run_pass(
        input,
        blobs,
        Some(&blob_kinds),
        KIND_WAYS,
        "Pass 2: ways",
        AdminWayPass::default,
        |state, block| admin_way_blob(state, block, &wanted_ways),
    )?;
    let mut member_refs: HashMap<i64, Vec<i64>> = HashMap::new();
    for chunk in chunks {
        member_refs.extend(chunk.wanted);
    }

    // Pass 3: node coordinates.
    let table = NodeLocations::new(
        member_refs.values().flat_map(|r| r.iter().copied()).collect(),
    );
    println!("{} node location(s) needed", table.len());
    let table = resolve_nodes(input, blobs, &blob_kinds, "Pass 3: nodes", table)?;

    let locate = |id: i64| -> Option<Coord> {
        table
            .get(id)
            .map(|(lat_e7, lon_e7)| (lon_e7 as f64 * 1e-7, lat_e7 as f64 * 1e-7))
    };

    let mut lines: Vec<LineFeature> = Vec::new();
    let mut stats = RingStats::default();
    let mut outside_bbox = 0usize;
    let mut no_geometry = 0usize;
    for row in &rows {
        let members: Vec<MemberWay> = row
            .members
            .iter()
            .filter_map(|(way, outer)| {
                member_refs.get(way).map(|refs| MemberWay {
                    refs: refs.clone(),
                    outer: *outer,
                })
            })
            .collect();
        let polygons = rings::assemble(&members, locate, &mut stats);
        if polygons.is_empty() {
            no_geometry += 1;
            continue;
        }
        if !parts_touch_bbox(
            &polygons.iter().flatten().cloned().collect::<Vec<_>>(),
            opts.bbox.as_ref(),
        ) {
            outside_bbox += 1;
            continue;
        }
        // A relation with one ring is a Polygon; several are a MultiPolygon. Both
        // are what `keep_geometry` accepted, and the tiler takes either.
        let geometry = if polygons.len() == 1 {
            Geometry::Polygon(polygons.into_iter().next().expect("non-empty"))
        } else {
            Geometry::MultiPolygon(polygons)
        };
        let t = AdminTags {
            name: row.name.as_deref(),
            name_en_tag: row.name_en.as_deref(),
            ..Default::default()
        };
        let Some(f) = admin::feature(&t, geometry, row.id) else {
            continue;
        };
        lines.push(LineFeature {
            sort: ("relation", row.id),
            rendered: render(&f),
        });
    }

    let written = write_lines(out, lines)?;
    println!(
        "Wrote {written} admin_city feature(s) to {} ({} outer, {} inner ring(s))",
        out.display(),
        stats.outer_rings,
        stats.inner_rings
    );
    // Loud, because an unclosed ring is a data problem the operator can act on --
    // usually a member missing from the extract.
    if stats.unclosed > 0 || stats.orphan_inner > 0 || no_geometry > 0 {
        println!(
            "{} ring(s) would not close, {} orphan hole(s) dropped, {} relation(s) yielded no geometry",
            stats.unclosed, stats.orphan_inner, no_geometry
        );
    }
    if outside_bbox > 0 {
        println!("{outside_bbox} feature(s) dropped by --bbox");
    }
    Ok(Stats {
        features: written,
        from_nodes: 0,
        from_ways: 0,
        from_relations: written,
        outside_bbox,
    })
}

fn admin_relation_blob(
    state: &mut Vec<AdminRow>,
    block: &pbf::PrimitiveBlock,
    select: &Select,
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_RELATIONS, &mut kinds, &mut |el: Element| {
        if let Element::Relation(r) = el {
            if !select.matches(|k| r.tags.get_str(k)) {
                return Ok(());
            }
            let t = AdminTags {
                boundary: r.tags.get_str("boundary"),
                admin_level: r.tags.get_str("admin_level"),
                name_en_tag: r.tags.get_str("name:en"),
                name: r.tags.get_str("name"),
                type_: r.tags.get_str("type"),
            };
            if !admin::is_city(&t) {
                return Ok(());
            }
            // An empty role means outer, per the OSM boundary convention: most real
            // members are unroled, and treating them as holes would leave nearly
            // every boundary with no exterior at all.
            let members: Vec<(i64, bool)> = r
                .members
                .iter()
                .filter(|m: &&Member| m.kind == MEMBER_WAY)
                .filter_map(|m| match m.role {
                    b"outer" | b"" => Some((m.id, true)),
                    b"inner" => Some((m.id, false)),
                    // Anything else (`label`, `admin_centre`, a subarea) is not part
                    // of the edge.
                    _ => None,
                })
                .collect();
            if members.is_empty() {
                return Ok(());
            }
            state.push(AdminRow {
                id: r.id,
                name: t.name.map(str::to_string),
                name_en: t.name_en_tag.map(str::to_string),
                members,
            });
        }
        Ok(())
    })?;
    Ok(kinds)
}

fn admin_way_blob(
    state: &mut AdminWayPass,
    block: &pbf::PrimitiveBlock,
    wanted: &[i64],
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
        if let Element::Way(w) = el {
            if w.refs.len() >= 2 && wanted.binary_search(&w.id).is_ok() {
                state.wanted.push((w.id, w.refs.to_vec()));
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
    /// `None` leaves the pool at whatever `par::threads()` decides.
    pub threads: Option<usize>,
}

/// `osm_extract IN.osm.pbf --layer NAME --out FILE [--bbox BOX] [--threads N]`
pub fn parse_args(args: &[String]) -> std::result::Result<Args, String> {
    let mut input: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut layer: Option<Layer> = None;
    let mut bbox: Option<BBox> = None;
    let mut threads: Option<usize> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            flag @ ("--out" | "--layer" | "--bbox" | "--threads") => {
                i += 1;
                let value = args
                    .get(i)
                    .ok_or_else(|| format!("{flag} needs a value"))?
                    .as_str();
                match flag {
                    "--out" => out = Some(PathBuf::from(value)),
                    "--layer" => layer = Some(Layer::parse(value)?),
                    "--threads" => threads = Some(crate::par::parse_threads(value)?),
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
        threads,
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

    /// The point of `--threads`: it must change only how fast the work happens.
    /// Counts that do not divide the chunk count are the interesting ones.
    #[test]
    fn the_thread_cap_changes_no_bytes() {
        let (pbf_path, dir) = testpbf::write_layers_sample("extract_threads");
        let run = |n: usize| {
            crate::par::set_threads(n);
            let out = dir.join(format!("safety.t{n}.geojsonseq"));
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
        let serial = run(1);
        for n in [2, 3, 7, 32] {
            assert_eq!(serial, run(n), "{n} threads perturbed the output");
        }
        crate::par::clear_threads();
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

    // --- roads ------------------------------------------------------------

    #[test]
    fn extracts_the_roads_layer_with_lanes_speed_and_width() {
        let (lines, stats) = extract_layer("extract_roads", Layer::Roads);
        // All three of the fixture's highway ways, including the one with no
        // speed limit: `roads` is every road, not only the ones with a limit.
        assert_eq!((stats.features, stats.from_ways), (3, 3));
        assert_eq!(lines.len(), 3);

        // The fully-attributed motorway.
        let m = lines.iter().find(|l| l.contains("way/3002")).unwrap();
        assert!(m.contains("\"class\":1"), "motorway is class 1: {m}");
        assert!(m.contains("\"lanes\":3"), "{m}");
        // through|through|right as the graph's own LANE_* masks, left to right.
        assert!(
            m.contains(&format!(
                "\"turn_lanes_forward\":\"{}|{}|{}\"",
                crate::tags::LANE_THROUGH,
                crate::tags::LANE_THROUGH,
                crate::tags::LANE_RIGHT
            )),
            "{m}"
        );
        assert!(m.contains("\"oneway\":1"), "{m}");
        assert!(m.contains("\"width\":12.00"), "{m}");
        assert!(m.contains("\"bridge\":1"), "{m}");
        assert!(m.contains("\"layer\":1"), "{m}");
        // `maxspeed=none` keeps its string and gets no number: it is not 0 km/h.
        assert!(m.contains("\"maxspeed\":\"none\""), "{m}");
        assert!(!m.contains("maxspeed_kmh"), "{m}");

        // A posted mph limit arrives as both forms.
        let r = lines.iter().find(|l| l.contains("way/3001")).unwrap();
        assert!(r.contains("\"class\":7"), "residential is class 7: {r}");
        assert!(r.contains("\"maxspeed\":\"25 mph\""), "{r}");
        assert!(r.contains("\"maxspeed_kmh\":40"), "{r}");
        assert!(r.contains("\"type\":\"LineString\""), "{r}");
        // Geometry came from resolved node coordinates.
        assert!(r.contains("-122.43"), "{r}");

        // And the service road with nothing on it is class + osm_id only.
        let s = lines.iter().find(|l| l.contains("way/3003")).unwrap();
        assert!(s.contains("\"properties\":{\"class\":8,\"osm_id\":\"way/3003\"}"), "{s}");

        // The railway ways and the platform are not roads.
        assert!(!lines.iter().any(|l| l.contains("way/5001")));
    }

    #[test]
    fn roads_is_deterministic_and_bbox_filtered() {
        let (pbf_path, dir) = testpbf::write_layers_sample("det_roads");
        let run = |suffix: &str| {
            let out = dir.join(format!("roads{suffix}.geojsonseq"));
            build(&pbf_path, &out, &Options { layer: Layer::Roads, bbox: None }).unwrap();
            std::fs::read(out).unwrap()
        };
        assert_eq!(run("a"), run("b"));

        let stats = build(
            &pbf_path,
            &dir.join("empty.geojsonseq"),
            &Options {
                layer: Layer::Roads,
                bbox: Some(BBox::parse("-30,20,-20,30").unwrap()),
            },
        )
        .unwrap();
        assert_eq!(stats.features, 0);
        assert_eq!(stats.outside_bbox, 3);
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
    fn a_routes_platform_members_are_not_drawn_as_track() {
        // The fixture's route relation carries a third member: a *closed*
        // `railway=platform` way, `role=platform`. Assembling geometry from every
        // member drew a box around the station.
        let (lines, stats) = extract_layer("extract_transit_platform", Layer::TransitLines);
        let route = lines.iter().find(|l| l.contains("relation/9001")).unwrap();
        assert_eq!(route.matches("]],[[").count(), 1, "the two path members only: {route}");
        // The platform's corners sit at 122.404-122.405W, nowhere near the path.
        assert!(!route.contains("-122.4050000"), "platform outline drawn: {route}");
        assert!(!route.contains("-122.4040000"), "platform outline drawn: {route}");
        // And it is not a feature in its own right either, roled or not.
        assert!(!lines.iter().any(|l| l.contains("way/5004")));
        assert_eq!((stats.from_ways, stats.from_relations), (2, 1));
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

    #[test]
    fn a_transit_lines_layer_with_no_relations_is_reported() {
        // Ways-only is the shape a GDAL-less legacy build produces: the right
        // feature count, and no `colour` anywhere, so every line renders grey.
        let ways_only = Stats { features: 900, from_ways: 900, ..Default::default() };
        assert!(ways_only.missing_half(Layer::TransitLines).is_some());
        // Both halves present, or nothing at all, are both fine.
        let both = Stats {
            features: 900,
            from_ways: 800,
            from_relations: 100,
            ..Default::default()
        };
        assert_eq!(both.missing_half(Layer::TransitLines), None);
        assert_eq!(Stats::default().missing_half(Layer::TransitLines), None);
        // And the check is specific to the two-source layer.
        assert_eq!(ways_only.missing_half(Layer::Maxspeed), None);
    }

    // --- admin_city -------------------------------------------------------

    #[test]
    fn assembles_an_admin_city_boundary_from_its_member_ways() {
        let (lines, stats) = extract_layer("extract_admin", Layer::AdminCity);
        // Only the admin_level=8 relation. The county at level 6 is dropped.
        assert_eq!(stats.features, 1, "{lines:?}");
        assert_eq!((stats.from_relations, stats.from_ways), (1, 0));

        let f = &lines[0];
        assert!(f.contains("\"admin_level\":8"), "a number, not a string: {f}");
        assert!(f.contains("\"name\":\"Oakland\""), "{f}");
        assert!(f.contains("\"osm_id\":\"relation/9101\""), "{f}");
        // No name:en on the fixture, and the city level has no fallback to `name`.
        assert!(!f.contains("name_en"), "{f}");
        assert!(!lines.iter().any(|l| l.contains("Alameda County")));
    }

    #[test]
    fn an_admin_outer_ring_is_stitched_and_its_hole_is_kept() {
        let (lines, _) = extract_layer("extract_admin_rings", Layer::AdminCity);
        let f = &lines[0];
        // One outer ring plus one hole: a Polygon, not a MultiPolygon.
        assert!(f.contains("\"type\":\"Polygon\""), "{f}");
        // Two rings means one `]],[[` separator between them.
        assert_eq!(f.matches("]],[[").count(), 1, "exterior plus one hole: {f}");

        // The exterior came from two ways -- one roled `outer`, one unroled, and one
        // of them traversed backwards -- so all four corners must be present and the
        // ring must close on the corner it started from.
        let at = f.find("\"coordinates\":[[").unwrap() + "\"coordinates\":[".len();
        let end = at + f[at..].find("]],").unwrap() + 1;
        let outer = &f[at..end];
        for corner in [
            "[-122.4000000,37.8000000]",
            "[-122.3600000,37.8000000]",
            "[-122.3600000,37.8400000]",
            "[-122.4000000,37.8400000]",
        ] {
            assert!(outer.contains(corner), "missing outer corner {corner}: {outer}");
        }
        assert_eq!(outer.matches("[-122.4000000,37.8000000]").count(), 2, "closed: {outer}");

        // The hole is the second ring, and is inside the first.
        let hole = &f[end..];
        assert!(hole.contains("[-122.3900000,37.8100000]"), "{hole}");
    }

    #[test]
    fn admin_city_is_deterministic_and_bbox_filtered() {
        let (pbf_path, dir) = testpbf::write_layers_sample("det_admin");
        let run = |suffix: &str| {
            let out = dir.join(format!("admin{suffix}.geojsonseq"));
            build(
                &pbf_path,
                &out,
                &Options { layer: Layer::AdminCity, bbox: None },
            )
            .unwrap();
            std::fs::read(out).unwrap()
        };
        assert_eq!(run("a"), run("b"));

        let stats = build(
            &pbf_path,
            &dir.join("empty.geojsonseq"),
            &Options {
                layer: Layer::AdminCity,
                bbox: Some(BBox::parse("-30,20,-20,30").unwrap()),
            },
        )
        .unwrap();
        assert_eq!(stats.features, 0);
        assert_eq!(stats.outside_bbox, 1);
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

    #[test]
    fn threads_is_optional_and_must_be_positive() {
        let base: Vec<String> = vec![
            "in.pbf".into(),
            "--layer".into(),
            "safety".into(),
            "--out".into(),
            "o".into(),
        ];
        assert_eq!(parse_args(&base).unwrap().threads, None);

        let mut with = base.clone();
        with.extend(["--threads".to_string(), "6".to_string()]);
        assert_eq!(parse_args(&with).unwrap().threads, Some(6));

        let mut zero = base.clone();
        zero.extend(["--threads".to_string(), "0".to_string()]);
        assert!(parse_args(&zero).is_err());

        let mut bare = base;
        bare.push("--threads".into());
        assert!(parse_args(&bare).is_err());
    }
}
