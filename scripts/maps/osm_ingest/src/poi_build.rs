//! POI extraction: `.osm.pbf` -> a geojsonseq for tippecanoe plus
//! `poi_names.bin` and `poi_index.bin`.
//!
//! A port of the former `scripts/maps/poi_extract.cpp`. The three outputs are
//! mutually consistent — same POI set, same coordinates:
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
}

/// A `type=multipolygon`/`boundary` relation that is itself a POI.
struct RelArea {
    id: i64,
    type_: u16,
    name: Vec<u8>,
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

pub fn build(input: &Path, geojson: &Path, names: &Path, index: &Path) -> Result<Stats> {
    // Fail before the three passes rather than after them if an output path is
    // unwritable.
    for path in [geojson, names, index] {
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
            pois.push(make_poi(poi, area.type_, area.id, &area.name));
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
            pois.push(make_poi(poi, area.type_, -area.id, &area.name));
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

    let written = write_outputs(&pois, geojson, names, index)?;
    Ok(Stats {
        records: written.0,
        unique_names: written.1,
        name_bytes: written.2,
        from_nodes,
        from_ways,
        from_relations,
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

fn make_poi((lat, lon): (f64, f64), type_: u16, osm_id: i64, name: &[u8]) -> Poi {
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
    }
}

/// Returns `(records, unique_names, name_bytes)`.
fn write_outputs(
    pois: &[Poi],
    geojson: &Path,
    names: &Path,
    index: &Path,
) -> Result<(usize, usize, u32)> {
    let mut pool = NamePool::new(BufWriter::new(create(names)?));
    let mut index_out = BufWriter::new(create(index)?);
    let mut geojson_out = BufWriter::new(create(geojson)?);
    let mut line: Vec<u8> = Vec::new();

    for p in pois {
        let off = pool.intern(&p.name).map_err(io_err)?;
        index_out.write_all(&p.lat_e7.to_le_bytes()).map_err(io_err)?;
        index_out.write_all(&p.lon_e7.to_le_bytes()).map_err(io_err)?;
        index_out.write_all(&off.to_le_bytes()).map_err(io_err)?;
        index_out.write_all(&p.type_.to_le_bytes()).map_err(io_err)?;

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

    index_out.flush().map_err(io_err)?;
    geojson_out.flush().map_err(io_err)?;
    let unique = pool.unique_count();
    let bytes = pool.byte_len();
    pool.finish().map_err(io_err)?;
    println!(
        "Wrote {} record(s), {unique} unique name(s), {bytes} name byte(s)",
        pois.len()
    );
    Ok((pois.len(), unique, bytes))
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
}

/// `poi_extract IN.osm.pbf --geojson FILE --names FILE --index FILE`
pub fn parse_args(args: &[String]) -> std::result::Result<Args, String> {
    let mut input: Option<PathBuf> = None;
    let mut geojson: Option<PathBuf> = None;
    let mut names: Option<PathBuf> = None;
    let mut index: Option<PathBuf> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            flag @ ("--geojson" | "--names" | "--index") => {
                let flag = flag.to_string();
                i += 1;
                let value = args
                    .get(i)
                    .map(PathBuf::from)
                    .ok_or_else(|| format!("{flag} needs a value"))?;
                match flag.as_str() {
                    "--geojson" => geojson = Some(value),
                    "--names" => names = Some(value),
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
    Ok(Args {
        input: input.ok_or_else(|| "missing IN.osm.pbf".to_string())?,
        geojson: geojson.ok_or_else(|| "--geojson is required".to_string())?,
        names: names.ok_or_else(|| "--names is required".to_string())?,
        index: index.ok_or_else(|| "--index is required".to_string())?,
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

    #[test]
    fn extracts_nodes_closed_ways_and_relations() {
        let (pbf_path, dir) = testpbf::write_sample("poi_build");
        let stats = build(
            &pbf_path,
            &dir.join("pois.geojsonseq"),
            &dir.join("poi_names.bin"),
            &dir.join("poi_index.bin"),
        )
        .unwrap();

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
            )
            .unwrap();
        };
        run("a");
        run("b");
        for (a, b) in [
            ("poisa.geojsonseq", "poisb.geojsonseq"),
            ("namesa.bin", "namesb.bin"),
            ("indexa.bin", "indexb.bin"),
        ] {
            assert_eq!(
                std::fs::read(dir.join(a)).unwrap(),
                std::fs::read(dir.join(b)).unwrap(),
                "{a} differs between runs"
            );
        }
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
            "i".into(),
        ])
        .unwrap();
        assert_eq!(ok.input, PathBuf::from("in.pbf"));
        assert_eq!(ok.index, PathBuf::from("i"));
        assert!(parse_args(&["in.pbf".into()]).is_err());
        assert!(parse_args(&["in.pbf".into(), "--geojson".into()]).is_err());
    }
}
