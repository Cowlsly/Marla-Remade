//! POI extraction: `.osm.pbf` -> a geojsonseq for tippecanoe plus
//! `poi_names.bin`, `poi_index.bin` and `poi_attrs.bin`.
//!
//! A port of the former `scripts/maps/poi_extract.cpp`. The four outputs are
//! mutually consistent — same POI set, same order, same coordinates:
//!
//! 1. **geojsonseq** newline-delimited GeoJSON `Point` features, fed to
//!    tippecanoe to build the `ma_pois` source layer. Properties: `name`
//!    (string), `type` (number), `osm_id` (number).
//! 2. **`poi_names.bin`** deduped NUL-terminated UTF-8 name table; a "name start
//!    index" is the byte offset of a name's first byte (the same convention as
//!    `road_names.bin`).
//! 3. **`poi_index.bin`** flat 14-byte little-endian records —
//!    `i32 lat_e7, i32 lon_e7, u32 name_off, u16 type` — sorted by the 64-bit
//!    Morton key of `(lat, lon)`, so the app can mmap and binary-scan a spatial
//!    range. The record contract lives in
//!    `maps/src/main/java/com/vayunmathur/maps/util/PoiIndex.kt`.
//! 4. **`poi_attrs.bin`** the attribute sidecar: opening hours, phone, website,
//!    address, cuisine and wheelchair access, indexed by the ORDINAL of the
//!    matching `poi_index.bin` record. See [`crate::poi_attrs`] for the layout.
//!    Kept separate because the 14-byte record is full and its width is load
//!    bearing in three places.
//!
//! A POI is any node, or closed-way / multipolygon area, carrying BOTH a `name`
//! and one of the recognised POI keys (see [`crate::tags::classify`]). Area
//! geometry is reduced to a representative centroid because POIs render as
//! points.
//!
//! ## Where this differs from the libosmium version
//!
//! libosmium assembled true polygon rings (`MultipolygonManager` + `Assembler`).
//! Porting a ring assembler is a large piece of work on its own, so a relation's
//! outer ring is approximated by the deduplicated node ids of its `outer`-role
//! member ways. That is exact when the ring is a single closed way; when it is
//! split across several, the ring's true vertex order is lost and the centroid
//! shifts by a few metres at most. Node- and closed-way-derived POIs match the
//! old tool exactly.

use std::collections::HashMap;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

use crate::geojson::json_escape;
use crate::names::NamePool;
use crate::osm::{self, visit_block, Element, MEMBER_WAY};
use crate::pbf::{self, KIND_NODES, KIND_RELATIONS, KIND_WAYS};
use crate::poi_attrs::AttrPool;
use crate::poi_side;
use crate::proto::{Error, Result};
use crate::spatial::spatial_from_e7;
use crate::tags::{self, PoiTags};

/// Relation types libosmium's `MultipolygonManager` accepts as areas.
const AREA_RELATION_TYPES: [&str; 2] = ["multipolygon", "boundary"];

/// Sentinel latitude for "this node's location was never seen".
const NO_LOC: i32 = i32::MIN;

pub struct Stats {
    pub records: usize,
    pub unique_names: usize,
    pub name_bytes: u32,
    pub from_nodes: usize,
    pub from_ways: usize,
    pub from_relations: usize,
    /// POIs carrying at least one sidecar attribute.
    pub with_attrs: usize,
    pub unique_attrs: usize,
    pub attr_bytes: usize,
    /// Populated cells in `poi_spatial.bin`.
    pub spatial_cells: usize,
    /// `(record, word)` entries in `poi_name_index.bin`.
    pub name_entries: usize,
}

struct Poi {
    lat: f64,
    lon: f64,
    lat_e7: i32,
    lon_e7: i32,
    morton: u64,
    type_: u16,
    osm_id: i64,
    name: Vec<u8>,
    /// Encoded sidecar record body, empty when the POI has no attributes.
    attrs: Vec<u8>,
}

/// A `type=multipolygon`/`boundary` relation that is itself a POI.
struct RelArea {
    id: i64,
    type_: u16,
    name: Vec<u8>,
    attrs: Vec<u8>,
    outer_ways: Vec<i64>,
}

#[derive(Default)]
struct RelPass {
    areas: Vec<RelArea>,
}

/// A closed way that is a POI in its own right.
struct WayArea {
    id: i64,
    type_: u16,
    name: Vec<u8>,
    attrs: Vec<u8>,
    refs: Vec<i64>,
}

#[derive(Default)]
struct WayPass {
    areas: Vec<WayArea>,
    /// Node refs of ways that are outer members of a POI relation.
    outer_refs: Vec<(i64, Vec<i64>)>,
}

#[derive(Default)]
struct NodePass {
    pois: Vec<Poi>,
    /// `(index into the needed-node table, lat_e7, lon_e7)`.
    locs: Vec<(u32, i32, i32)>,
}

pub fn build(
    input: &Path,
    geojson: &Path,
    names: &Path,
    index: &Path,
    attrs: &Path,
    spatial: &Path,
    name_index: &Path,
) -> Result<Stats> {
    // Fail before the three passes rather than after them if an output path is
    // unwritable.
    for path in [geojson, names, index, attrs, spatial, name_index] {
        if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
            std::fs::create_dir_all(parent)
                .map_err(|e| Error(format!("cannot create {}: {e}", parent.display())))?;
        }
    }

    let blobs = pbf::scan_blobs(input)?;
    println!("Scanned {} data blob(s) in {}", blobs.len(), input.display());

    // --- Pass 1: area relations ---------------------------------------------
    let (chunks, blob_kinds) = pbf::run_pass(
        input,
        &blobs,
        None,
        KIND_RELATIONS,
        "Pass 1: relations",
        RelPass::default,
        relation_blob,
    )?;
    let mut rel_areas: Vec<RelArea> = Vec::new();
    for chunk in chunks {
        rel_areas.extend(chunk.areas);
    }
    let mut outer_wanted: Vec<i64> = rel_areas
        .iter()
        .flat_map(|a| a.outer_ways.iter().copied())
        .collect();
    outer_wanted.sort_unstable();
    outer_wanted.dedup();
    println!("{} POI relation(s)", rel_areas.len());

    // --- Pass 2: closed-way areas + the relations' outer ways ----------------
    let (chunks, _) = pbf::run_pass(
        input,
        &blobs,
        Some(&blob_kinds),
        KIND_WAYS,
        "Pass 2: ways",
        WayPass::default,
        |state, block| way_blob(state, block, &outer_wanted),
    )?;
    let mut way_areas: Vec<WayArea> = Vec::new();
    let mut outer_refs: HashMap<i64, Vec<i64>> = HashMap::new();
    for chunk in chunks {
        way_areas.extend(chunk.areas);
        outer_refs.extend(chunk.outer_refs);
    }

    // Every node whose location an area centroid needs.
    let mut needed: Vec<i64> = way_areas
        .iter()
        .flat_map(|a| a.refs.iter().copied())
        .chain(outer_refs.values().flat_map(|r| r.iter().copied()))
        .collect();
    needed.sort_unstable();
    needed.dedup();
    println!(
        "{} closed-way area(s), {} node location(s) needed",
        way_areas.len(),
        needed.len()
    );

    // --- Pass 3: node POIs + the node locations areas need -------------------
    let (chunks, _) = pbf::run_pass(
        input,
        &blobs,
        Some(&blob_kinds),
        KIND_NODES,
        "Pass 3: nodes",
        NodePass::default,
        |state, block| node_blob(state, block, &needed),
    )?;
    let mut pois: Vec<Poi> = Vec::new();
    let mut locs: Vec<(i32, i32)> = vec![(NO_LOC, NO_LOC); needed.len()];
    for chunk in chunks {
        pois.extend(chunk.pois);
        for (idx, lat, lon) in chunk.locs {
            locs[idx as usize] = (lat, lon);
        }
    }
    let from_nodes = pois.len();

    let location = |id: i64| -> Option<(i32, i32)> {
        let idx = needed.binary_search(&id).ok()?;
        let (lat, lon) = locs[idx];
        (lat != NO_LOC).then_some((lat, lon))
    };

    // --- Area centroids ------------------------------------------------------
    for area in &way_areas {
        // A closed way's ring is its own node list minus the repeated closing
        // node.
        if let Some(poi) = ring_centroid(area.refs[..area.refs.len() - 1].iter().copied(), &location)
        {
            pois.push(make_poi(poi, area.type_, area.id, &area.name, area.attrs.clone()));
        }
    }
    let from_ways = pois.len() - from_nodes;

    for area in &rel_areas {
        // libosmium assembled a true ring from the member ways; we cannot, so
        // deduplicate the member node ids instead. That is exact when the outer
        // ring is a single closed way, and drops the endpoints shared between
        // consecutive ways when it is split across several.
        let mut refs: Vec<i64> = area
            .outer_ways
            .iter()
            .filter_map(|w| outer_refs.get(w))
            .flat_map(|r| r.iter().copied())
            .collect();
        refs.sort_unstable();
        refs.dedup();
        if let Some(poi) = ring_centroid(refs.iter().copied(), &location) {
            pois.push(make_poi(poi, area.type_, -area.id, &area.name, area.attrs.clone()));
        }
    }
    let from_relations = pois.len() - from_nodes - from_ways;
    println!(
        "Extracted {} POI(s): {from_nodes} node, {from_ways} closed-way, {from_relations} relation",
        pois.len()
    );

    // Morton order, ties broken by osm_id, so both poi_index.bin and the geojson
    // are stable across runs.
    pois.sort_by_key(|p| (p.morton, p.osm_id));

    let written = write_outputs(&pois, geojson, names, index, attrs, spatial, name_index)?;
    Ok(Stats {
        records: written.records,
        unique_names: written.unique_names,
        name_bytes: written.name_bytes,
        from_nodes,
        from_ways,
        from_relations,
        with_attrs: written.with_attrs,
        unique_attrs: written.unique_attrs,
        attr_bytes: written.attr_bytes,
        spatial_cells: written.spatial_cells,
        name_entries: written.name_entries,
    })
}

fn poi_tags<'a>(t: &osm::Tags<'_, 'a>) -> PoiTags<'a> {
    PoiTags {
        railway: t.get_str("railway"),
        public_transport: t.get_str("public_transport"),
        amenity: t.get_str("amenity"),
        shop: t.get_str("shop"),
        tourism: t.get_str("tourism"),
        leisure: t.get_str("leisure"),
        healthcare: t.get_str("healthcare"),
        office: t.get_str("office"),
    }
}

fn relation_blob(state: &mut RelPass, block: &pbf::PrimitiveBlock) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_RELATIONS, &mut kinds, &mut |el: Element| {
        if let Element::Relation(r) = el {
            let is_area = r
                .tags
                .get_str("type")
                .is_some_and(|t| AREA_RELATION_TYPES.contains(&t));
            if !is_area {
                return Ok(());
            }
            let name = match r.tags.get("name") {
                Some(n) if !n.is_empty() => n,
                _ => return Ok(()),
            };
            if let Some(type_) = tags::classify(&poi_tags(&r.tags)) {
                state.areas.push(RelArea {
                    id: r.id,
                    type_,
                    name: name.to_vec(),
                    attrs: crate::poi_attrs::encode(|k| r.tags.get(k)),
                    outer_ways: r
                        .members
                        .iter()
                        .filter(|m| m.kind == MEMBER_WAY && (m.role == b"outer" || m.role.is_empty()))
                        .map(|m| m.id)
                        .collect(),
                });
            }
        }
        Ok(())
    })?;
    Ok(kinds)
}

fn way_blob(
    state: &mut WayPass,
    block: &pbf::PrimitiveBlock,
    outer_wanted: &[i64],
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
        if let Element::Way(w) = el {
            if outer_wanted.binary_search(&w.id).is_ok() {
                state.outer_refs.push((w.id, w.refs.to_vec()));
            }
            // libosmium's `MultipolygonManager::after_way` builds a standalone
            // area from every closed way with more than 3 nodes that is not
            // tagged `area=no` — including ways that are also members of a
            // multipolygon relation, so a campus tagged on both the relation and
            // its rings yields a POI either way. It compares end *locations*;
            // comparing the end refs is equivalent for real OSM data and needs no
            // location index here.
            let closed = w.refs.len() > 3 && w.refs.first() == w.refs.last();
            if !closed || w.tags.get_str("area") == Some("no") {
                return Ok(());
            }
            let name = match w.tags.get("name") {
                Some(n) if !n.is_empty() => n,
                _ => return Ok(()),
            };
            if let Some(type_) = tags::classify(&poi_tags(&w.tags)) {
                state.areas.push(WayArea {
                    id: w.id,
                    type_,
                    name: name.to_vec(),
                    attrs: crate::poi_attrs::encode(|k| w.tags.get(k)),
                    refs: w.refs.to_vec(),
                });
            }
        }
        Ok(())
    })?;
    Ok(kinds)
}

fn node_blob(state: &mut NodePass, block: &pbf::PrimitiveBlock, needed: &[i64]) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_NODES, &mut kinds, &mut |el: Element| {
        if let Element::Node(n) = el {
            if let Ok(idx) = needed.binary_search(&n.id) {
                state.locs.push((idx as u32, n.lat_e7, n.lon_e7));
            }
            if n.tags.is_empty() {
                return Ok(());
            }
            let name = match n.tags.get("name") {
                Some(v) if !v.is_empty() => v,
                _ => return Ok(()),
            };
            if let Some(type_) = tags::classify(&poi_tags(&n.tags)) {
                state.pois.push(make_poi(
                    (n.lat_e7 as f64 * 1e-7, n.lon_e7 as f64 * 1e-7),
                    type_,
                    n.id,
                    name,
                    crate::poi_attrs::encode(|k| n.tags.get(k)),
                ));
            }
        }
        Ok(())
    })?;
    Ok(kinds)
}

/// Average of an area ring's vertices, reproducing libosmium's representative
/// point.
///
/// `refs` is the ring's *distinct* vertices. libosmium stored a ring closed — the
/// start vertex appears again at the end — and its Assembler sorted the ring's
/// segments before walking them, so the ring always starts at the vertex with the
/// smallest `(lon, lat)`. That vertex is therefore the one counted twice, which is
/// why it is added again here: averaging the way's own node list instead would
/// double the way's *first* node and land metres off on a small building.
fn ring_centroid<I, L>(refs: I, location: &L) -> Option<(f64, f64)>
where
    I: Iterator<Item = i64>,
    L: Fn(i64) -> Option<(i32, i32)>,
{
    let mut sum_lat = 0.0f64;
    let mut sum_lon = 0.0f64;
    let mut n = 0u64;
    let mut start: Option<(i32, i32)> = None;
    for id in refs {
        if let Some((lat_e7, lon_e7)) = location(id) {
            sum_lat += lat_e7 as f64 * 1e-7;
            sum_lon += lon_e7 as f64 * 1e-7;
            n += 1;
            // libosmium's Location orders by x (lon) then y (lat).
            if start.is_none_or(|(slat, slon)| (lon_e7, lat_e7) < (slon, slat)) {
                start = Some((lat_e7, lon_e7));
            }
        }
    }
    let (slat, slon) = start?;
    sum_lat += slat as f64 * 1e-7;
    sum_lon += slon as f64 * 1e-7;
    Some((sum_lat / (n + 1) as f64, sum_lon / (n + 1) as f64))
}

fn make_poi(
    (lat, lon): (f64, f64),
    type_: u16,
    osm_id: i64,
    name: &[u8],
    attrs: Vec<u8>,
) -> Poi {
    let lat_e7 = (lat * 1e7).round() as i32;
    let lon_e7 = (lon * 1e7).round() as i32;
    Poi {
        lat,
        lon,
        lat_e7,
        lon_e7,
        // Derived from the *stored* integers, not the pre-rounding f64, so the
        // sort order matches the key a reader recomputes from `poi_index.bin`.
        morton: spatial_from_e7(lat_e7, lon_e7),
        type_,
        osm_id,
        name: name.to_vec(),
        attrs,
    }
}

struct Written {
    records: usize,
    unique_names: usize,
    name_bytes: u32,
    with_attrs: usize,
    unique_attrs: usize,
    attr_bytes: usize,
    spatial_cells: usize,
    name_entries: usize,
}

fn write_outputs(
    pois: &[Poi],
    geojson: &Path,
    names: &Path,
    index: &Path,
    attrs: &Path,
    spatial: &Path,
    name_index: &Path,
) -> Result<Written> {
    let mut pool = NamePool::new(BufWriter::new(create(names)?));
    let mut index_out = BufWriter::new(create(index)?);
    let mut geojson_out = BufWriter::new(create(geojson)?);
    // The sidecar is indexed by record ordinal, so it is filled in this same loop
    // over the same Morton-sorted vector. Any second pass over `pois` would be an
    // opportunity for the two files to disagree.
    let mut attr_pool = AttrPool::new();
    let mut line: Vec<u8> = Vec::new();

    for p in pois {
        let off = pool.intern(&p.name).map_err(io_err)?;
        index_out.write_all(&p.lat_e7.to_le_bytes()).map_err(io_err)?;
        index_out.write_all(&p.lon_e7.to_le_bytes()).map_err(io_err)?;
        index_out.write_all(&off.to_le_bytes()).map_err(io_err)?;
        index_out.write_all(&p.type_.to_le_bytes()).map_err(io_err)?;
        attr_pool.push(&p.attrs).map_err(io_err)?;

        line.clear();
        write!(
            line,
            "{{\"type\":\"Feature\",\"geometry\":{{\"type\":\"Point\",\"coordinates\":[{:.7},{:.7}]}},\"properties\":{{\"name\":\"",
            p.lon, p.lat
        )
        .map_err(io_err)?;
        json_escape(&p.name, &mut line);
        writeln!(line, "\",\"type\":{},\"osm_id\":{}}}}}", p.type_, p.osm_id).map_err(io_err)?;
        geojson_out.write_all(&line).map_err(io_err)?;
    }

    let mut attrs_out = BufWriter::new(create(attrs)?);
    attr_pool.write(&mut attrs_out).map_err(io_err)?;
    attrs_out.flush().map_err(io_err)?;

    // Both side files are derived from the same Morton-sorted vector as the index, in
    // the same function, for the same reason the sidecar is: they join by record
    // ordinal, and any second pass over `pois` is an opportunity to disagree.
    let coords: Vec<(i32, i32)> = pois.iter().map(|p| (p.lat_e7, p.lon_e7)).collect();
    let mut spatial_out = BufWriter::new(create(spatial)?);
    let spatial_cells = poi_side::write_spatial(&mut spatial_out, &coords).map_err(io_err)?;
    spatial_out.flush().map_err(io_err)?;

    let name_slices: Vec<&[u8]> = pois.iter().map(|p| p.name.as_slice()).collect();
    let mut name_index_out = BufWriter::new(create(name_index)?);
    let name_entries =
        poi_side::write_name_index(&mut name_index_out, &name_slices).map_err(io_err)?;
    name_index_out.flush().map_err(io_err)?;

    index_out.flush().map_err(io_err)?;
    geojson_out.flush().map_err(io_err)?;
    let unique = pool.unique_count();
    let bytes = pool.byte_len();
    pool.finish().map_err(io_err)?;
    println!(
        "Wrote {} record(s), {unique} unique name(s), {bytes} name byte(s)",
        pois.len()
    );
    println!(
        "Wrote {} POI(s) with attributes, {} unique record(s), {} sidecar byte(s)",
        attr_pool.with_attrs(),
        attr_pool.unique_count(),
        attr_pool.total_len()
    );
    println!(
        "Wrote {spatial_cells} populated grid cell(s), {name_entries} name index entr(ies)"
    );
    Ok(Written {
        records: pois.len(),
        unique_names: unique,
        name_bytes: bytes,
        with_attrs: attr_pool.with_attrs(),
        unique_attrs: attr_pool.unique_count(),
        attr_bytes: attr_pool.total_len(),
        spatial_cells,
        name_entries,
    })
}

fn create(path: &Path) -> Result<File> {
    File::create(path).map_err(|e| Error(format!("cannot write {}: {e}", path.display())))
}

fn io_err(e: std::io::Error) -> Error {
    Error(e.to_string())
}

pub struct Args {
    pub input: PathBuf,
    pub geojson: PathBuf,
    pub names: PathBuf,
    pub index: PathBuf,
    pub attrs: PathBuf,
    pub spatial: PathBuf,
    pub name_index: PathBuf,
    /// `None` leaves the pool at whatever `par::threads()` decides.
    pub threads: Option<usize>,
}

/// `poi_extract IN.osm.pbf --geojson FILE --names FILE --index FILE [--attrs FILE]`
/// `[--spatial FILE] [--name-index FILE] [--threads N]`
///
/// The optional outputs default to their conventional names beside `--index`, so a
/// caller that predates any of them keeps working and still emits them. Making them
/// required would break build_pois_layer.sh and build_all.* on the same commit.
pub fn parse_args(args: &[String]) -> std::result::Result<Args, String> {
    let mut input: Option<PathBuf> = None;
    let mut geojson: Option<PathBuf> = None;
    let mut names: Option<PathBuf> = None;
    let mut index: Option<PathBuf> = None;
    let mut attrs: Option<PathBuf> = None;
    let mut spatial: Option<PathBuf> = None;
    let mut name_index: Option<PathBuf> = None;
    let mut threads: Option<usize> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--threads" => {
                i += 1;
                let value = args
                    .get(i)
                    .ok_or_else(|| "--threads needs a value".to_string())?;
                threads = Some(crate::par::parse_threads(value)?);
            }
            flag @ ("--geojson" | "--names" | "--index" | "--attrs" | "--spatial"
            | "--name-index") => {
                let flag = flag.to_string();
                i += 1;
                let value = args
                    .get(i)
                    .map(PathBuf::from)
                    .ok_or_else(|| format!("{flag} needs a value"))?;
                match flag.as_str() {
                    "--geojson" => geojson = Some(value),
                    "--names" => names = Some(value),
                    "--attrs" => attrs = Some(value),
                    "--spatial" => spatial = Some(value),
                    "--name-index" => name_index = Some(value),
                    _ => index = Some(value),
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
    // Resolved in the order the usage line lists them, so a bare `in.pbf` still
    // complains about the first thing it is missing.
    let input = input.ok_or_else(|| "missing IN.osm.pbf".to_string())?;
    let geojson = geojson.ok_or_else(|| "--geojson is required".to_string())?;
    let names = names.ok_or_else(|| "--names is required".to_string())?;
    let index = index.ok_or_else(|| "--index is required".to_string())?;
    let attrs = attrs.unwrap_or_else(|| index.with_file_name("poi_attrs.bin"));
    let spatial = spatial.unwrap_or_else(|| index.with_file_name("poi_spatial.bin"));
    let name_index = name_index.unwrap_or_else(|| index.with_file_name("poi_name_index.bin"));
    Ok(Args {
        input,
        geojson,
        names,
        index,
        attrs,
        spatial,
        name_index,
        threads,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testpbf;

    struct Record {
        lat_e7: i32,
        lon_e7: i32,
        name: String,
        type_: u16,
    }

    fn read_records(dir: &Path) -> (Vec<Record>, Vec<String>) {
        let index = std::fs::read(dir.join("poi_index.bin")).unwrap();
        let names = std::fs::read(dir.join("poi_names.bin")).unwrap();
        let geojson = std::fs::read_to_string(dir.join("pois.geojsonseq")).unwrap();
        assert_eq!(index.len() % 14, 0, "records must be exactly 14 bytes");
        let records = index
            .chunks_exact(14)
            .map(|r| {
                let off = u32::from_le_bytes(r[8..12].try_into().unwrap()) as usize;
                let end = off + names[off..].iter().position(|b| *b == 0).unwrap();
                Record {
                    lat_e7: i32::from_le_bytes(r[0..4].try_into().unwrap()),
                    lon_e7: i32::from_le_bytes(r[4..8].try_into().unwrap()),
                    name: String::from_utf8(names[off..end].to_vec()).unwrap(),
                    type_: u16::from_le_bytes(r[12..14].try_into().unwrap()),
                }
            })
            .collect();
        let lines = geojson.lines().map(|l| l.to_string()).collect();
        (records, lines)
    }

    /// Decode `poi_attrs.bin` the way [`crate::poi_attrs`] documents it: one
    /// `(key, value)` list per record ordinal, empty where the POI had nothing.
    fn read_attrs(dir: &Path) -> Vec<Vec<(u8, String)>> {
        use crate::poi_attrs::{HEADER_BYTES, MAGIC, NO_ATTRS, VERSION};
        let bytes = std::fs::read(dir.join("poi_attrs.bin")).unwrap();
        assert_eq!(&bytes[0..4], &MAGIC);
        assert_eq!(bytes[4], VERSION);
        let count = u32::from_le_bytes(bytes[8..12].try_into().unwrap()) as usize;
        let blob = &bytes[HEADER_BYTES + 4 * count..];
        (0..count)
            .map(|i| {
                let at = HEADER_BYTES + 4 * i;
                let off = u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
                if off == NO_ATTRS {
                    return Vec::new();
                }
                let at = off as usize;
                let len = u16::from_le_bytes(blob[at..at + 2].try_into().unwrap()) as usize;
                let body = &blob[at + 2..at + 2 + len];
                let mut out = Vec::new();
                let mut j = 0;
                while j + 3 <= body.len() {
                    let vlen = u16::from_le_bytes(body[j + 1..j + 3].try_into().unwrap()) as usize;
                    out.push((
                        body[j],
                        String::from_utf8(body[j + 3..j + 3 + vlen].to_vec()).unwrap(),
                    ));
                    j += 3 + vlen;
                }
                out
            })
            .collect()
    }

    fn build_sample(tag: &str) -> (Stats, PathBuf) {
        let (pbf_path, dir) = testpbf::write_sample(tag);
        let stats = build(
            &pbf_path,
            &dir.join("pois.geojsonseq"),
            &dir.join("poi_names.bin"),
            &dir.join("poi_index.bin"),
            &dir.join("poi_attrs.bin"),
            &dir.join("poi_spatial.bin"),
            &dir.join("poi_name_index.bin"),
        )
        .unwrap();
        (stats, dir)
    }

    #[test]
    fn extracts_nodes_closed_ways_and_relations() {
        let (stats, dir) = build_sample("poi_build");

        // The cafe node, the closed "Plaza" way and the "Riverside Park"
        // relation. The bus stop node has a name but `highway=bus_stop` is not a
        // POI key, so it is correctly absent.
        assert_eq!(
            (stats.from_nodes, stats.from_ways, stats.from_relations),
            (1, 1, 1)
        );
        assert_eq!(stats.records, 3);

        let (records, lines) = read_records(&dir);
        assert_eq!(records.len(), 3);
        assert_eq!(lines.len(), 3);

        let by_name = |n: &str| records.iter().find(|r| r.name == n).unwrap();
        // amenity=cafe -> 1, at the cafe node's own location.
        let cafe = by_name("Corner Cafe");
        assert_eq!(cafe.type_, 1);
        assert_eq!((cafe.lat_e7, cafe.lon_e7), (370_040_000, -1_220_010_000));
        // The closed way's ring is nodes 1-4 plus a repeat of the ring's start,
        // which libosmium picks as the vertex with the smallest (lon, lat) — here
        // node 1. So the average is over lat 370_000_000 twice, then 010, 020, 030.
        let plaza = by_name("Plaza");
        assert_eq!(plaza.type_, 1);
        assert_eq!(plaza.lat_e7, 370_012_000);
        assert_eq!(plaza.lon_e7, -1_220_000_000);
        // leisure=park -> 12. The relation's outer ring IS that closed way, the
        // case the approximation reproduces exactly, so the centroid must match.
        let park = by_name("Riverside Park");
        assert_eq!(park.type_, 12);
        assert_eq!((park.lat_e7, park.lon_e7), (plaza.lat_e7, plaza.lon_e7));

        // Records are Morton-ordered.
        let keys: Vec<u64> = records
            .iter()
            .map(|r| crate::spatial::spatial_from_e7(r.lat_e7, r.lon_e7))
            .collect();
        assert!(keys.windows(2).all(|w| w[0] <= w[1]), "{keys:?}");

        // The geojson mirrors the index exactly, in the same order.
        assert!(lines[0].starts_with("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":["));
        for (line, rec) in lines.iter().zip(&records) {
            assert!(line.contains(&format!("\"name\":\"{}\"", rec.name)), "{line}");
            assert!(line.contains(&format!("\"type\":{}", rec.type_)), "{line}");
            assert!(
                line.contains(&format!("{:.7},{:.7}", rec.lon_e7 as f64 * 1e-7, rec.lat_e7 as f64 * 1e-7)),
                "{line}"
            );
        }
        // The relation's osm_id is negated so way and relation ids cannot collide.
        let park_line = lines.iter().find(|l| l.contains("Riverside Park")).unwrap();
        assert!(park_line.contains(&format!("\"osm_id\":{}", -testpbf::RELATION_ID)));
    }

    #[test]
    fn ring_centroid_doubles_the_smallest_lon_lat_vertex() {
        // libosmium sorted a ring's segments before walking them, so the ring
        // always starts — and therefore repeats — at the vertex with the smallest
        // (lon, lat). Averaging the way's own node list instead doubles the way's
        // FIRST node, which put small buildings metres off.
        let pts = [
            (1, (350_000_000, -1_200_000_000)),
            (2, (350_000_000, -1_200_010_000)), // smallest lon
            (3, (350_030_000, -1_200_010_000)),
            (4, (350_030_000, -1_200_000_000)),
        ];
        let location = |id: i64| pts.iter().find(|(i, _)| *i == id).map(|(_, l)| *l);

        // Same ring, three different starting vertices: the centroid must not move.
        for order in [
            vec![1, 2, 3, 4],
            vec![3, 4, 1, 2],
            vec![4, 3, 2, 1],
        ] {
            let (lat, lon) = ring_centroid(order.iter().copied(), &location).unwrap();
            // lat: (350.0 + 350.0 + 350.03 + 350.03 + 350.0[repeat of node 2]) / 5
            assert_eq!((lat * 1e7).round() as i64, 350_012_000, "order {order:?}");
            // lon: (-120.0 - 120.001 - 120.001 - 120.0 - 120.001) / 5
            assert_eq!((lon * 1e7).round() as i64, -1_200_006_000, "order {order:?}");
        }

        // Ties on lon are broken by lat, matching libosmium's Location ordering.
        let flat = [
            (1, (350_020_000, -1_200_000_000)),
            (2, (350_010_000, -1_200_000_000)),
        ];
        let loc2 = |id: i64| flat.iter().find(|(i, _)| *i == id).map(|(_, l)| *l);
        let (lat, _) = ring_centroid([1i64, 2].into_iter(), &loc2).unwrap();
        // Node 2 has the smaller lat, so it is the repeated vertex.
        assert_eq!((lat * 1e7).round() as i64, 350_013_333);

        // Nothing resolvable -> no POI rather than a (0, 0) centroid.
        assert!(ring_centroid([99i64].into_iter(), &location).is_none());
    }

    #[test]
    fn two_runs_are_byte_identical() {
        let (pbf_path, dir) = testpbf::write_sample("poi_det");
        let run = |suffix: &str| {
            build(
                &pbf_path,
                &dir.join(format!("pois{suffix}.geojsonseq")),
                &dir.join(format!("names{suffix}.bin")),
                &dir.join(format!("index{suffix}.bin")),
                &dir.join(format!("attrs{suffix}.bin")),
                &dir.join(format!("spatial{suffix}.bin")),
                &dir.join(format!("nameidx{suffix}.bin")),
            )
            .unwrap();
        };
        run("a");
        run("b");
        for (a, b) in [
            ("poisa.geojsonseq", "poisb.geojsonseq"),
            ("namesa.bin", "namesb.bin"),
            ("indexa.bin", "indexb.bin"),
            ("attrsa.bin", "attrsb.bin"),
            ("spatiala.bin", "spatialb.bin"),
            ("nameidxa.bin", "nameidxb.bin"),
        ] {
            assert_eq!(
                std::fs::read(dir.join(a)).unwrap(),
                std::fs::read(dir.join(b)).unwrap(),
                "{a} differs between runs"
            );
        }
    }

    /// The sidecar's join to `poi_index.bin` is by ordinal, so the two files have to
    /// have exactly the same length in records and agree row for row.
    #[test]
    fn the_attribute_sidecar_lines_up_with_the_index_by_ordinal() {
        use crate::poi_attrs::{
            KEY_CUISINE, KEY_HOUSENUMBER, KEY_OPENING_HOURS, KEY_PHONE, KEY_STREET, KEY_WEBSITE,
        };
        let (stats, dir) = build_sample("poi_attrs_join");
        let (records, _) = read_records(&dir);
        let attrs = read_attrs(&dir);

        assert_eq!(attrs.len(), records.len(), "one slot per index record");
        assert_eq!(stats.with_attrs, 1, "only the cafe node carries any");
        assert_eq!(stats.unique_attrs, 1);

        let cafe = records.iter().position(|r| r.name == "Corner Cafe").unwrap();
        assert_eq!(
            attrs[cafe],
            vec![
                (KEY_OPENING_HOURS, "24/7".to_string()),
                // The fixture tags `contact:phone` rather than `phone`, so the alias
                // has to be read.
                (KEY_PHONE, "+1-555-0100".to_string()),
                (KEY_WEBSITE, "https://cafe.example".to_string()),
                (KEY_HOUSENUMBER, "120".to_string()),
                (KEY_STREET, "Market St".to_string()),
                (KEY_CUISINE, "coffee_shop".to_string()),
            ]
        );

        for (i, a) in attrs.iter().enumerate() {
            if i != cafe {
                assert!(a.is_empty(), "{} has no attributes", records[i].name);
            }
        }
    }

    /// Adding the sidecar must not have widened the record everything else measures
    /// the file by. `record_count = filesize / 14` is asserted in the README too.
    #[test]
    fn the_index_record_is_still_fourteen_bytes() {
        let (stats, dir) = build_sample("poi_width");
        let index = std::fs::read(dir.join("poi_index.bin")).unwrap();
        assert_eq!(index.len(), stats.records * 14);
    }

    #[test]
    fn args_require_all_three_outputs() {
        let ok = parse_args(&[
            "in.pbf".into(),
            "--geojson".into(),
            "g".into(),
            "--names".into(),
            "n".into(),
            "--index".into(),
            "out/poi_index.bin".into(),
        ])
        .unwrap();
        assert_eq!(ok.input, PathBuf::from("in.pbf"));
        assert_eq!(ok.index, PathBuf::from("out/poi_index.bin"));
        // Defaulted beside the index, so a caller written before the sidecar existed
        // still emits one rather than silently skipping it.
        assert_eq!(ok.attrs, PathBuf::from("out/poi_attrs.bin"));
        assert!(ok.threads.is_none(), "the pool defaults to the box");
        assert!(parse_args(&["in.pbf".into()]).is_err());
        assert!(parse_args(&["in.pbf".into(), "--geojson".into()]).is_err());
    }

    #[test]
    fn threads_must_be_positive() {
        let base: Vec<String> = vec![
            "in.pbf".into(),
            "--geojson".into(),
            "g".into(),
            "--names".into(),
            "n".into(),
            "--index".into(),
            "i".into(),
        ];
        let mut with = base.clone();
        with.extend(["--threads".to_string(), "3".to_string()]);
        assert_eq!(parse_args(&with).unwrap().threads, Some(3));

        let mut zero = base.clone();
        zero.extend(["--threads".to_string(), "0".to_string()]);
        assert!(parse_args(&zero).is_err());

        let mut bare = base;
        bare.push("--threads".into());
        assert!(parse_args(&bare).is_err());
    }

    #[test]
    fn an_explicit_attrs_path_overrides_the_default() {
        let ok = parse_args(&[
            "in.pbf".into(),
            "--geojson".into(),
            "g".into(),
            "--names".into(),
            "n".into(),
            "--index".into(),
            "i".into(),
            "--attrs".into(),
            "elsewhere/a.bin".into(),
        ])
        .unwrap();
        assert_eq!(ok.attrs, PathBuf::from("elsewhere/a.bin"));
    }
}
