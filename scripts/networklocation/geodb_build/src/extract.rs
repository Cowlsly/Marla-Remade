//! What gets indexed, and how it is pulled out of a `.osm.pbf`.
//!
//! The database this replaces held **only** objects carrying both `addr:housenumber` and
//! `addr:street`. That left three large gaps, all of which show up as a geocoder that says
//! nothing useful:
//!
//! - **No streets.** Reverse geocoding could return a nearby house number or nothing at all;
//!   it could never say "you are on Foo Street".
//! - **No POIs or places.** A named building, shop or neighbourhood was invisible in both
//!   directions.
//! - **`addr:place` addresses dropped.** Many countries — much of Germany and Austria, and
//!   most of Japan and Korea — address buildings against a *place* rather than a street.
//!   Requiring `addr:street` silently discarded all of them.
//!
//! ## Pass ordering
//!
//! A PBF stores nodes before the ways that reference them and ways before the relations, so
//! anything needing way or relation geometry has to be read backwards, in three passes:
//! relations decide which ways matter, ways decide which node coordinates matter, and nodes
//! supply them. `osm_ingest::extract` documents this; the same ordering is reproduced here
//! because this crate drives the scan itself rather than emitting a layer.

use std::collections::HashMap;

use osm_ingest::bbox::{self, BBox};
use osm_ingest::nodeloc::{resolve_nodes, NodeLocations};
use osm_ingest::osm::{visit_block, Element, Tags, MEMBER_WAY};
use osm_ingest::pbf::{self, KIND_NODES, KIND_RELATIONS, KIND_WAYS};
use osm_ingest::proto::{Error, Result};

use crate::codec::Interner;
use crate::format::{in_range, DICTS, D_CITY, D_COUNTRY, D_HOUSE, D_NAME, D_POSTCODE, D_STATE,
    D_STREET, K_ADDRESS, K_PLACE, K_POI, K_STREET};

/// Minimum spacing between samples along a street, in e7 latitude units (~100 m).
const STREET_SAMPLE_E7: i64 = 10_000;
/// Cap on samples per street way, so one enormous way cannot dominate the database.
const STREET_MAX_SAMPLES: usize = 32;

/// Tag keys that make an object a POI when it also has a name.
const POI_KEYS: [&str; 6] = ["amenity", "shop", "tourism", "leisure", "office", "healthcare"];

/// `place=*` values worth indexing: inhabited places people search for and expect to be told
/// they are in. Deliberately excludes `place=locality` (often uninhabited), `region`, `ocean`
/// and the administrative-only values.
const PLACE_VALUES: [&str; 9] = [
    "city",
    "town",
    "village",
    "hamlet",
    "suburb",
    "neighbourhood",
    "quarter",
    "borough",
    "island",
];

/// One indexed feature, with its strings already interned.
///
/// Holding `String`s here instead would be the single thing that stops a planet build from
/// running: at roughly 640 million rows, seven `String`s each is about 250 GB of allocator
/// headers and heap. Interned, a row is 40 bytes and the whole set is 25 GB, with the distinct
/// strings stored once in [`Strings`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Row {
    /// Latitude, e7.
    pub lat_e7: i32,
    /// Longitude, e7.
    pub lon_e7: i32,
    /// One of the `K_*` constants.
    pub kind: u8,
    /// Dictionary ids, indexed by the `D_*` constants in [`crate::format`].
    pub ids: [u32; DICTS],
}

/// The string dictionaries, filled as extraction runs.
pub struct Strings {
    /// One interner per dictionary slot, in `D_*` order.
    pub dicts: Vec<Interner>,
}

impl Default for Strings {
    fn default() -> Strings {
        Strings { dicts: (0..DICTS).map(|_| Interner::new()).collect() }
    }
}

impl Strings {
    fn intern(&mut self, a: &Attrs) -> [u32; DICTS] {
        [
            self.dicts[D_NAME].intern(&a.name),
            self.dicts[D_HOUSE].intern(&a.house),
            self.dicts[D_STREET].intern(&a.street),
            self.dicts[D_CITY].intern(&a.city),
            self.dicts[D_STATE].intern(&a.state),
            self.dicts[D_COUNTRY].intern(&a.country),
            self.dicts[D_POSTCODE].intern(&a.postcode),
        ]
    }
}

/// Whether a classified feature's **tags** carry what its kind requires. Checked before
/// interning, so a useless feature never reaches the dictionaries.
fn attrs_ok(kind: u8, a: &Attrs) -> bool {
    match kind {
        // An address with neither a street nor a place cannot be written down, and a house
        // number on its own is not findable.
        K_ADDRESS => !a.house.is_empty() && !a.street.is_empty(),
        _ => !a.name.is_empty(),
    }
}

/// Whether a classified feature is worth storing at all.
fn is_useful(kind: u8, a: &Attrs, lat_e7: i32, lon_e7: i32) -> bool {
    in_range(lat_e7, lon_e7) && attrs_ok(kind, a)
}

/// What a pass decided about one element.
#[derive(Default, Clone)]
struct Attrs {
    name: String,
    house: String,
    street: String,
    city: String,
    state: String,
    country: String,
    postcode: String,
}

fn tag(tags: &Tags, key: &str) -> String {
    tags.get_str(key).unwrap_or("").trim().to_string()
}

fn read_attrs(tags: &Tags) -> Attrs {
    let street = {
        let s = tag(tags, "addr:street");
        // `addr:place` is how much of DE/AT/JP/KR addresses buildings; treating it as the
        // street is what makes those addresses representable at all.
        if s.is_empty() {
            tag(tags, "addr:place")
        } else {
            s
        }
    };
    let state = {
        let s = tag(tags, "addr:state");
        if s.is_empty() {
            tag(tags, "addr:province")
        } else {
            s
        }
    };
    Attrs {
        name: tag(tags, "name"),
        house: tag(tags, "addr:housenumber"),
        street,
        city: tag(tags, "addr:city"),
        state,
        country: tag(tags, "addr:country"),
        postcode: tag(tags, "addr:postcode"),
    }
}

/// Decide what an element is, from its tags alone. `None` means "not indexed".
fn classify(tags: &Tags) -> Option<u8> {
    if tags.get("addr:housenumber").is_some() {
        return Some(K_ADDRESS);
    }
    let named = tags.get("name").is_some();
    if !named {
        return None;
    }
    if tags.get("highway").is_some() {
        return Some(K_STREET);
    }
    if let Some(place) = tags.get_str("place") {
        if PLACE_VALUES.contains(&place) {
            return Some(K_PLACE);
        }
    }
    if POI_KEYS.iter().any(|k| tags.get(k).is_some()) {
        return Some(K_POI);
    }
    None
}

fn row_from(kind: u8, ids: [u32; DICTS], lat_e7: i32, lon_e7: i32) -> Row {
    Row { lat_e7, lon_e7, kind, ids }
}

// --------------------------------------------------------------------------- way handling
/// A way held between passes, with everything it needs except coordinates.
///
/// Attributes are already interned: a planet run holds a quarter of a billion of these, and
/// keeping seven `String`s per way is what would put the build over the machine's memory.
struct PendingWay {
    kind: u8,
    ids: [u32; DICTS],
    refs: Vec<i64>,
}

/// Vertex average of a closed or open way.
///
/// A closed ring repeats its first vertex; counting it twice biases the point, so it is
/// dropped. This is a vertex average and not a true polygon centroid, which for an outline
/// with unevenly spaced nodes differs by metres — acceptable for a search result's anchor.
fn average(coords: &[(i32, i32)]) -> Option<(i32, i32)> {
    let pts = if coords.len() > 2 && coords.first() == coords.last() {
        &coords[..coords.len() - 1]
    } else {
        coords
    };
    if pts.is_empty() {
        return None;
    }
    let mut lat = 0i64;
    let mut lon = 0i64;
    for &(a, o) in pts {
        lat += a as i64;
        lon += o as i64;
    }
    Some(((lat / pts.len() as i64) as i32, (lon / pts.len() as i64) as i32))
}

/// Sample points along a street.
///
/// One record per way would put a long road's only entry at its midpoint, so standing at one
/// end you would be told the nearest street is something else entirely. Samples are spread
/// **evenly along the way**, roughly [`STREET_SAMPLE_E7`] apart, always including both ends,
/// and capped at [`STREET_MAX_SAMPLES`].
///
/// Spreading evenly rather than walking until the cap is reached is what keeps a motorway
/// represented over its whole length: taking the first 32 samples at a fixed spacing would
/// cover the first few kilometres and leave the rest of the way invisible to reverse lookup.
fn sample_line(coords: &[(i32, i32)]) -> Vec<(i32, i32)> {
    let Some(&first) = coords.first() else { return Vec::new() };
    let Some(&last) = coords.last() else { return Vec::new() };
    if coords.len() == 1 {
        return vec![first];
    }

    // Manhattan distance in e7 units, longitude unscaled. Cheap, and only ever used to space
    // samples, never as a real distance.
    let seg = |a: (i32, i32), b: (i32, i32)| -> i64 {
        (a.0 as i64 - b.0 as i64).abs() + (a.1 as i64 - b.1 as i64).abs()
    };
    let total: i64 = coords.windows(2).map(|w| seg(w[0], w[1])).sum();
    if total == 0 {
        return vec![first];
    }

    let want = ((total / STREET_SAMPLE_E7) + 1).clamp(2, STREET_MAX_SAMPLES as i64) as usize;
    let step = total as f64 / (want - 1) as f64;

    let mut out = Vec::with_capacity(want);
    out.push(first);
    let mut acc = 0i64;
    for w in coords.windows(2) {
        acc += seg(w[0], w[1]);
        while out.len() < want - 1 && acc as f64 >= step * out.len() as f64 {
            out.push(w[1]);
        }
    }
    out.push(last);
    out.dedup();
    out
}

// --------------------------------------------------------------------------- driver
/// Counts describing what a run indexed, for the build report.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Stats {
    /// Rows from node elements.
    pub from_nodes: usize,
    /// Rows from way elements.
    pub from_ways: usize,
    /// Rows from relation elements.
    pub from_relations: usize,
    /// Addresses.
    pub addresses: usize,
    /// Street samples.
    pub streets: usize,
    /// Points of interest.
    pub pois: usize,
    /// Populated places.
    pub places: usize,
    /// Classified but outside `--bbox`.
    pub outside_bbox: usize,
    /// Classified but missing what its kind requires.
    pub incomplete: usize,
}

impl Stats {
    /// Rows produced.
    pub fn rows(&self) -> usize {
        self.addresses + self.streets + self.pois + self.places
    }
}

/// Read `input` and return every indexable feature, plus the dictionaries its strings were
/// interned into.
///
/// Every pass uses `run_pass_sink` rather than `run_pass`: the sink is called once per chunk,
/// in order, so a chunk's owned strings are interned and dropped immediately instead of every
/// chunk's strings being alive at once. On a planet run that is the difference between tens of
/// gigabytes and hundreds.
pub fn extract(
    input: &std::path::Path,
    bbox: Option<&BBox>,
) -> Result<(Vec<Row>, Strings, Stats)> {
    let blobs = pbf::scan_blobs(input)?;
    println!("Scanned {} data blob(s) in {}", blobs.len(), input.display());

    let mut stats = Stats::default();
    let mut rows: Vec<Row> = Vec::new();
    let mut strings = Strings::default();
    // Accumulated inside the pass sinks, which cannot borrow `stats` while it is also
    // borrowed by the way/relation materialization below.
    let mut incomplete = 0usize;

    // --- pass 1: relations decide which ways matter -----------------------
    let mut relations: Vec<(u8, [u32; DICTS], Vec<i64>)> = Vec::new();
    let kinds = {
        let relations = &mut relations;
        let strings = &mut strings;
        let incomplete = &mut incomplete;
        pbf::run_pass_sink(
            input,
            &blobs,
            None,
            KIND_RELATIONS,
            "relations",
            Vec::<(u8, Attrs, Vec<i64>)>::new,
            |acc, block| {
                let mut kinds = 0u8;
                visit_block(block, KIND_RELATIONS, &mut kinds, &mut |el| {
                    let Element::Relation(r) = el else { return Ok(()) };
                    // Only areas: a `multipolygon` or a `boundary` has an inside to put a
                    // point in. A route or a turn restriction does not.
                    let ty = r.tags.get("type").unwrap_or(b"");
                    if ty != b"multipolygon" && ty != b"boundary" {
                        return Ok(());
                    }
                    let Some(kind) = classify(&r.tags) else { return Ok(()) };
                    let members: Vec<i64> = r
                        .members
                        .iter()
                        .filter(|m| {
                            m.kind == MEMBER_WAY && (m.role == b"outer" || m.role.is_empty())
                        })
                        .map(|m| m.id)
                        .collect();
                    if members.is_empty() {
                        return Ok(());
                    }
                    acc.push((kind, read_attrs(&r.tags), members));
                    Ok(())
                })?;
                Ok(kinds)
            },
            |chunk| {
                for (kind, attrs, members) in chunk {
                    if !attrs_ok(kind, &attrs) {
                        *incomplete += 1;
                        continue;
                    }
                    relations.push((kind, strings.intern(&attrs), members));
                }
                Ok(())
            },
        )?
    };
    println!("  {} area relation(s)", relations.len());

    // Ways claimed by a relation, so the way pass keeps their refs even when the way itself is
    // untagged — which is the normal case for a multipolygon member.
    let mut claimed: Vec<i64> = relations.iter().flat_map(|(_, _, m)| m.iter().copied()).collect();
    claimed.sort_unstable();
    claimed.dedup();

    // --- pass 2: ways decide which node coordinates matter ----------------
    let mut ways: Vec<PendingWay> = Vec::new();
    let mut member_geom: HashMap<i64, Vec<i64>> = HashMap::new();
    {
        let ways = &mut ways;
        let member_geom = &mut member_geom;
        let strings = &mut strings;
        let claimed = &claimed;
        let incomplete = &mut incomplete;
        let _ = pbf::run_pass_sink(
            input,
            &blobs,
            Some(&kinds),
            KIND_WAYS,
            "ways",
            || (Vec::<(u8, Attrs, Vec<i64>)>::new(), Vec::<(i64, Vec<i64>)>::new()),
            |acc, block| {
                let mut kinds = 0u8;
                visit_block(block, KIND_WAYS, &mut kinds, &mut |el| {
                    let Element::Way(w) = el else { return Ok(()) };
                    if claimed.binary_search(&w.id).is_ok() {
                        acc.1.push((w.id, w.refs.to_vec()));
                    }
                    if let Some(kind) = classify(&w.tags) {
                        acc.0.push((kind, read_attrs(&w.tags), w.refs.to_vec()));
                    }
                    Ok(())
                })?;
                Ok(kinds)
            },
            |(tagged, members)| {
                for (kind, attrs, refs) in tagged {
                    if !attrs_ok(kind, &attrs) {
                        *incomplete += 1;
                        continue;
                    }
                    ways.push(PendingWay { kind, ids: strings.intern(&attrs), refs });
                }
                for (id, refs) in members {
                    let _ = member_geom.insert(id, refs);
                }
                Ok(())
            },
        )?;
    }
    println!("  {} tagged way(s), {} relation member way(s)", ways.len(), member_geom.len());

    // --- pass 3: node coordinates ----------------------------------------
    let mut needed: Vec<i64> = Vec::new();
    for w in &ways {
        needed.extend_from_slice(&w.refs);
    }
    for refs in member_geom.values() {
        needed.extend_from_slice(refs);
    }
    let table = NodeLocations::new(needed)?;
    println!("  {} distinct node(s) needed for geometry", table.len());
    let locs = resolve_nodes(input, &blobs, &kinds, "node coords", table)?;

    // --- materialize ways -------------------------------------------------
    for w in &ways {
        let coords: Vec<(i32, i32)> = w.refs.iter().filter_map(|&id| locs.get(id)).collect();
        if coords.is_empty() {
            continue;
        }
        let points: Vec<(i32, i32)> = if w.kind == K_STREET {
            sample_line(&coords)
        } else {
            average(&coords).into_iter().collect()
        };
        for (lat, lon) in points {
            if !bbox::keep_e7(bbox, lat, lon) {
                stats.outside_bbox += 1;
                continue;
            }
            push_counted(&mut rows, &mut stats, row_from(w.kind, w.ids, lat, lon), Origin::Way);
        }
    }

    // --- materialize relations -------------------------------------------
    for (kind, ids, members) in relations.drain(..) {
        let mut coords: Vec<(i32, i32)> = Vec::new();
        for id in &members {
            if let Some(refs) = member_geom.get(id) {
                coords.extend(refs.iter().filter_map(|&r| locs.get(r)));
            }
        }
        let Some((lat, lon)) = average(&coords) else { continue };
        if !bbox::keep_e7(bbox, lat, lon) {
            stats.outside_bbox += 1;
            continue;
        }
        push_counted(&mut rows, &mut stats, row_from(kind, ids, lat, lon), Origin::Relation);
    }

    drop(locs);
    drop(member_geom);
    drop(ways);

    // --- pass 4: node features -------------------------------------------
    {
        let rows = &mut rows;
        let stats = &mut stats;
        let strings = &mut strings;
        let _ = pbf::run_pass_sink(
            input,
            &blobs,
            Some(&kinds),
            KIND_NODES,
            "node features",
            Vec::<(u8, Attrs, i32, i32)>::new,
            |acc: &mut Vec<(u8, Attrs, i32, i32)>, block| {
                let mut kinds = 0u8;
                visit_block(block, KIND_NODES, &mut kinds, &mut |el| {
                    let Element::Node(n) = el else { return Ok(()) };
                    let Some(kind) = classify(&n.tags) else { return Ok(()) };
                    acc.push((kind, read_attrs(&n.tags), n.lat_e7, n.lon_e7));
                    Ok(())
                })?;
                Ok(kinds)
            },
            |chunk| {
                for (kind, attrs, lat, lon) in chunk {
                    if !bbox::keep_e7(bbox, lat, lon) {
                        stats.outside_bbox += 1;
                        continue;
                    }
                    if !is_useful(kind, &attrs, lat, lon) {
                        stats.incomplete += 1;
                        continue;
                    }
                    let ids = strings.intern(&attrs);
                    push_counted(rows, stats, row_from(kind, ids, lat, lon), Origin::Node);
                }
                Ok(())
            },
        )?;
    }

    if rows.is_empty() {
        return Err(Error("no indexable features found; check --bbox and the PBF".to_string()));
    }
    stats.incomplete += incomplete;
    Ok((rows, strings, stats))
}

enum Origin {
    Node,
    Way,
    Relation,
}

/// Record a materialized row, dropping any whose coordinates fell outside the representable
/// range. Tag-level completeness was already decided by [`attrs_ok`] before interning.
fn push_counted(rows: &mut Vec<Row>, stats: &mut Stats, row: Row, origin: Origin) {
    if !in_range(row.lat_e7, row.lon_e7) {
        stats.incomplete += 1;
        return;
    }
    match row.kind {
        K_ADDRESS => stats.addresses += 1,
        K_STREET => stats.streets += 1,
        K_POI => stats.pois += 1,
        _ => stats.places += 1,
    }
    match origin {
        Origin::Node => stats.from_nodes += 1,
        Origin::Way => stats.from_ways += 1,
        Origin::Relation => stats.from_relations += 1,
    }
    rows.push(row);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_closed_rings_repeated_vertex_does_not_bias_the_average() {
        // A unit square, with the first vertex repeated as OSM writes it.
        let ring = vec![(0, 0), (0, 100), (100, 100), (100, 0), (0, 0)];
        assert_eq!(average(&ring), Some((50, 50)));
        // Without the fix the repeated (0,0) would drag it to (40, 40).
        let naive: i64 = ring.iter().map(|c| c.0 as i64).sum::<i64>() / ring.len() as i64;
        assert_eq!(naive, 40, "premise: counting it twice biases the point");
    }

    #[test]
    fn an_open_way_averages_all_its_vertices() {
        assert_eq!(average(&[(0, 0), (10, 20)]), Some((5, 10)));
        assert_eq!(average(&[]), None);
    }

    #[test]
    fn a_short_street_yields_one_sample() {
        let line = vec![(37_7749300, -122_4194200), (37_7749400, -122_4194300)];
        assert_eq!(sample_line(&line).len(), 2, "endpoints are always anchored");
        assert_eq!(sample_line(&line[..1]).len(), 1);
    }

    #[test]
    fn a_long_street_is_sampled_along_its_length_not_just_at_its_middle() {
        // A degree of latitude in 1000 steps: 10 000 000 e7 units total.
        let line: Vec<(i32, i32)> =
            (0..1000).map(|i| (37_0000000 + i * 10_000, -122_0000000)).collect();
        let s = sample_line(&line);
        assert!(s.len() > 5, "expected several samples, got {}", s.len());
        assert!(s.len() <= STREET_MAX_SAMPLES, "cap not honoured: {}", s.len());
        assert_eq!(s[0], line[0], "the near end must be represented");
        assert_eq!(*s.last().unwrap(), *line.last().unwrap(), "and the far end");
        // Samples must be spread over the whole way, not bunched at the start: the midpoint
        // of the sample list should sit near the midpoint of the way.
        let mid = s[s.len() / 2].0;
        let want = (line[0].0 + line[line.len() - 1].0) / 2;
        assert!((mid - want).abs() < 1_000_000, "samples are bunched: mid {mid} vs {want}");
    }

    #[test]
    fn street_samples_respect_the_minimum_spacing() {
        // Vertices far closer together than the sample spacing.
        let line: Vec<(i32, i32)> = (0..500).map(|i| (37_0000000 + i * 10, -122_0000000)).collect();
        let s = sample_line(&line);
        // 499 gaps x 10 units = 4990 units total, under one sample spacing, so only the two
        // endpoints survive.
        assert_eq!(s.len(), 2, "got {s:?}");
    }

    #[test]
    fn a_zero_length_way_yields_one_point() {
        let line = vec![(37_0000000, -122_0000000); 5];
        assert_eq!(sample_line(&line), vec![(37_0000000, -122_0000000)]);
        assert!(sample_line(&[]).is_empty());
    }

    #[test]
    fn an_enormous_way_cannot_dominate_the_database() {
        let line: Vec<(i32, i32)> =
            (0..100_000).map(|i| (37_0000000 + i * 1_000, -122_0000000)).collect();
        let s = sample_line(&line);
        assert!(s.len() <= STREET_MAX_SAMPLES);
        // Even at the cap, the far end is still covered.
        assert_eq!(*s.last().unwrap(), *line.last().unwrap());
    }

    fn attrs(name: &str, house: &str, street: &str) -> Attrs {
        Attrs {
            name: name.to_string(),
            house: house.to_string(),
            street: street.to_string(),
            ..Attrs::default()
        }
    }

    #[test]
    fn an_address_needs_a_number_and_something_to_hang_it_on() {
        assert!(attrs_ok(K_ADDRESS, &attrs("", "123", "Main St")));
        assert!(!attrs_ok(K_ADDRESS, &attrs("", "123", "")));
        assert!(!attrs_ok(K_ADDRESS, &attrs("", "", "Main St")));
    }

    #[test]
    fn everything_else_needs_a_name() {
        assert!(attrs_ok(K_POI, &attrs("Blue Bottle", "", "")));
        assert!(!attrs_ok(K_POI, &attrs("", "", "")));
        assert!(attrs_ok(K_STREET, &attrs("Market Street", "", "")));
        assert!(attrs_ok(K_PLACE, &attrs("Mission District", "", "")));
    }

    #[test]
    fn out_of_range_coordinates_are_never_useful() {
        let a = attrs("somewhere", "", "");
        assert!(is_useful(K_POI, &a, 37_7749300, -122_4194200));
        assert!(!is_useful(K_POI, &a, 900_000_001, -122_4194200));
        assert!(!is_useful(K_POI, &a, 37_7749300, -1_800_000_001));
    }

    #[test]
    fn interning_assigns_one_id_per_distinct_string() {
        let mut s = Strings::default();
        let a = s.intern(&attrs("Blue Bottle", "", ""));
        let b = s.intern(&attrs("Blue Bottle", "", ""));
        let c = s.intern(&attrs("Other", "", ""));
        assert_eq!(a, b, "the same tags must intern to the same ids");
        assert_ne!(a[D_NAME], c[D_NAME]);
        assert_eq!(s.dicts[D_NAME].len(), 2);
        // The empty strings all collapse to one id per dictionary.
        assert_eq!(s.dicts[D_HOUSE].len(), 1);
    }

    #[test]
    fn addr_place_stands_in_for_addr_street() {
        // Much of DE/AT/JP/KR addresses buildings against a place, not a street. v2 required
        // addr:street and silently dropped all of them.
        let mut a = Attrs { house: "5".to_string(), ..Attrs::default() };
        assert!(!attrs_ok(K_ADDRESS, &a));
        a.street = "Marktplatz".to_string();
        assert!(attrs_ok(K_ADDRESS, &a));
    }
}
