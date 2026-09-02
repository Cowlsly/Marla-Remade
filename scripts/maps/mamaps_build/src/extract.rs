//! Stage A: `.osm.pbf` in, classified geometry in lon/lat out.
//!
//! Three passes, because a PBF is ordered nodes-then-ways-then-relations and a way's geometry needs
//! coordinates that arrived before its tags did:
//!
//! 1. **ways + relations.** Classify tags. Spill the node refs of every classified way to a
//!    sequential file and keep the member way ids of every classified relation. Node blobs are
//!    skipped outright.
//! 2. **ways again**, keeping the refs of the ways some relation lists as a member. A second pass
//!    rather than keeping every untagged way's refs from the first, because "every untagged way in
//!    California" is 40 M node ids and the members are a few hundred thousand.
//! 3. **nodes.** Fill coordinates for exactly the ids the first two passes asked for, at 16 bytes
//!    per needed node.
//!
//! That last point is the whole memory argument. [`osm_ingest::nodeloc::NodeLocations`] costs
//! `O(needed)`; `graph_build`'s bitset costs 2.5 GB keyed by raw OSM node id whatever the extract,
//! and peaks near 10 GB on California. A water-and-buildings build needs a few million nodes, so it
//! pays tens of megabytes.
//!
//! # Nothing large is resident across the passes
//!
//! Neither of the two big tables this stage produces is held in memory. Features go to
//! [`Store`]; classified ways go to [`WaySink`], exploiting the fact that pass 1 already sees them
//! in ascending id order so no sort is needed to read them back in the order the archive wants.
//! What stays resident is the set a sequential file cannot serve: the refs of the ways that some
//! relation lists as a member, because a relation reaches those by id in its own order.
//!
//! # Collecting the needed ids: two shapes, one switch
//!
//! Pass 3 needs the ids sorted and unique, and there are two ways to get there. The obvious one --
//! one `Vec<i64>` of every ref **with duplicates**, sorted and deduped in place -- is what
//! [`collect_needed_in_memory`] does, and it is right up to a point: it is exact, it needs no
//! assumption about the ids, and on anything smaller than a continent it is smaller than the
//! alternative. Past that point it is the largest thing in the whole build. North-america is ~19 GB
//! of it; planet projects to ~96 GB and a sort of 12 G elements.
//!
//! [`collect_needed_by_bitset`] is the other shape, taken above [`REFS_IN_MEMORY`]: a bit per node
//! id, which is at most ~1.7 GB *for any extract, forever*, because OSM's node ids are monotonic and
//! near 13 G. Walking it low to high yields sorted unique ids directly, so it removes the vector and
//! the sort together, in one streaming pass with no extra I/O. Both paths must produce the same
//! sequence and `the_two_ref_collectors_agree_on_the_same_input` holds them to it.

use std::collections::HashMap;
use std::path::Path;

use osm_ingest::nodeloc::{resolve_nodes, NodeLocations};
use osm_ingest::osm::{visit_block, Element, MEMBER_WAY};
use osm_ingest::pbf::{self, KIND_RELATIONS, KIND_WAYS};
use osm_ingest::proto::{err, Result};
use osm_ingest::rings::{self, MemberWay, RingStats};
use osm_ingest::select::Select;
use tile_build::geom::Geometry;
use tile_build::par;
use tile_build::progress::Progress;
use rayon::prelude::*;

use crate::schema::{self, Class, Layers};
use crate::store::{Sink, Store, WayCounts, WayReader, WaySink};

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
    /// Land polygons read from a prepared coastline product, if one was given.
    pub land_polygons: u64,
    pub rings: RingStats,
}

/// A classified way, held only from the blob it was decoded in until its chunk reaches [`WaySink`].
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
    /// A relation carrying a shape is stitched into rings; one carrying a border is emitted as its
    /// member lines. Which it is comes from [`Class::area`] rather than from the relation's `type`
    /// tag, because both an administrative border and a protected area are `type=boundary`.
    area: bool,
}

/// Read `input` and spill every feature the schema classifies to `spill_path`.
///
/// Features go to disk rather than into a `Vec`, because holding them was 4.9 GB of a measured
/// 10.03 GB California peak and nothing reads them until the tiler does. Classified ways go to a
/// scratch file beside it for the same reason. See [`crate::store`].
pub fn extract(
    input: &Path,
    layers: Layers,
    coastline: Option<&Path>,
    spill_path: &Path,
) -> Result<(Store, Stats)> {
    // Stage A's boundaries are printed with their elapsed time so an external RSS sampler can say
    // which of them the peak belongs to. Three candidates sit within seconds of each other -- the ref
    // vector, the id index built beside it, and the node pass's per-chunk accumulators -- and
    // guessing between them has already cost more than printing them does.
    let started = std::time::Instant::now();
    let mark = |what: &str| {
        println!("  [stage A] {what} at {:.1}s", started.elapsed().as_secs_f64());
    };
    let blobs = pbf::scan_blobs(input)?;
    let select = Select::parse(&schema::filters(layers))?;
    let mut stats = Stats::default();
    // Scratch for this function alone: written in pass 1, read twice below, and removed as soon as
    // the last read is done. Beside the feature spill, so a build directed at a writable output
    // directory needs nothing else to be writable.
    let ways_path = spill_path.with_extension("ways.tmp");

    // --- pass 1: ways and relations -------------------------------------------------------
    //
    // `run_pass_sink` rather than `run_pass`, because `run_pass`'s contract is to hand back every
    // chunk at once and these chunks hold the node refs of every classified way — the 2.7 GB this
    // spill exists to be rid of. Here a chunk is appended to the file and freed while its
    // neighbours are still decoding.
    //
    // `blob_kinds` comes back from this pass and lets the later ones skip whole blobs, which on a
    // planet extract is most of the file.
    let mut ways = WaySink::create(&ways_path)?;
    let mut relations: Vec<Relation> = Vec::new();
    let blob_kinds = pbf::run_pass_sink(
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
                        // Two relation types carry a shape. Anything else — a route, a site, a
                        // public_transport — does not.
                        if !matches!(
                            relation.tags.get_str("type"),
                            Some("multipolygon" | "boundary")
                        ) {
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
                            // **The schema decides**, not the relation's `type` tag. Both an
                            // administrative border and a protected area are `type=boundary`, and
                            // one is a line while the other is a shape — which is exactly the
                            // distinction `Class::area` exists to make.
                            let area = class.area;
                            state.1.push(Relation { class, members, area });
                        }
                    }
                    Element::Node(_) => {}
                }
                Ok(())
            })?;
            Ok(kinds)
        },
        |(chunk_ways, chunk_relations)| {
            // Chunks arrive in file order and a PBF's ways are sorted by id, so appending here
            // leaves the file in ascending id order. `WaySink::push` refuses an id that does not
            // advance rather than letting an unsorted file reorder the archive silently.
            for (id, way) in chunk_ways {
                ways.push(id, &way.class, &way.refs)?;
            }
            relations.extend(chunk_relations);
            Ok(())
        },
    )?;
    let WayCounts { ways: ways_classified, refs: way_refs, max_ref: way_max_ref } = ways.finish()?;
    stats.ways_classified = ways_classified;
    stats.relations_classified = relations.len() as u64;

    // --- pass 2: the refs of every relation member way ------------------------------------
    //
    // **Every** member, not just the ones pass 1 did not classify. A relation reaches its members by
    // id, in the order it lists them, which is the one random access in this stage and the one thing
    // a sequential spill file cannot serve. So the members — and only the members — stay resident,
    // and that is what lets the other several million classified ways go to disk. California has
    // 63 156 relations, so this table is small next to the one it replaces.
    let wanted: Vec<i64> = {
        let mut wanted: Vec<i64> = relations
            .iter()
            .flat_map(|r| r.members.iter().map(|(id, _)| *id))
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
    //
    // The classified ways are read off disk instead of walked in memory. Order does not matter to
    // either path below, so this is a plain streaming pass over the spill.
    let member_refs: usize = members.values().map(|refs| refs.len()).sum();
    let refs_total = way_refs as usize + member_refs;
    let max_ref = members
        .values()
        .flat_map(|refs| refs.iter().copied())
        .fold(way_max_ref, i64::max);
    let table = if refs_total <= REFS_IN_MEMORY {
        collect_needed_in_memory(&ways_path, &members, refs_total, &mark)?
    } else {
        collect_needed_by_bitset(&ways_path, &members, max_ref, &mark)?
    };
    mark("id index built, refs freed");
    stats.nodes_needed = table.len() as u64;
    let table = resolve_nodes(input, &blobs, &blob_kinds, "Pass 3: nodes", table)?;
    mark("coordinates resolved");
    // The split, when asked for. Note what it does and does not separate: the first number is the
    // protobuf decode of each block *plus* the id lookups inside it, because bracketing the lookups
    // alone would need a clock read per node and there are billions. The second is the write, which
    // is the half that is serialised behind `run_pass_sink`'s lock.
    let (scan, write) = osm_ingest::nodeloc::resolve_seconds();
    if scan + write > 0.0 {
        println!(
            "  [stage A] node pass CPU: decode+lookup {scan:.1}s  write {write:.1}s (serialised)",
        );
    }

    // --- materialise ----------------------------------------------------------------------
    //
    // Ways in **id order**, which is the order the spill file is already in. It used to be a sort of
    // a `HashMap`'s keys, for the same reason: the output has to be reproducible and hash order is
    // not. The one place in this pipeline where that was a real risk, and now it is a property of
    // the file rather than a step that could be forgotten.
    mark("materialising");
    let mut sink = Sink::create(spill_path)?;
    let mut reader = WayReader::open(&ways_path)?;
    let mut refs: Vec<i64> = Vec::new();
    // Silent until now, and it is not a short step: on a north-america extract this loop ran 623
    // seconds on one thread with nothing on stdout, which is indistinguishable from a hang.
    let mut bar = Progress::new(
        "Materialise: ways".to_string(),
        stats.ways_classified as usize,
        "way(s)",
        true,
    );
    // Batched, because the expensive part of a way is embarrassingly parallel and the cheap part
    // cannot be. `table.line` is a coordinate lookup per node -- an id search plus a random read of a
    // mapped file -- and `way_geometry` closes and winds rings; neither touches shared mutable state,
    // so a batch of them goes as wide as the pool. The sink then takes the results **in order**,
    // which is what keeps the archive byte-identical: the spill's feature order is the archive's.
    //
    // 64 Ki ways at ~10 nodes each is a few tens of MB of geometry in flight, against a build that
    // peaks near 7 GB.
    const MATERIALISE_BATCH: usize = 64 * 1024;
    let mut batch: Vec<(Class, Vec<i64>)> = Vec::with_capacity(MATERIALISE_BATCH);
    let mut built: Vec<Option<Geometry<(f64, f64)>>> = Vec::with_capacity(MATERIALISE_BATCH);
    loop {
        let more = reader.next(&mut refs)?;
        if let Some((_, class)) = more {
            batch.push((class, std::mem::take(&mut refs)));
        }
        // Flushed when full, and once more at the end with whatever is left.
        if batch.len() >= MATERIALISE_BATCH || (more.is_none() && !batch.is_empty()) {
            built.clear();
            par::install(|| {
                batch
                    .par_iter()
                    .map(|(class, refs)| way_geometry(&table.line(refs), class.area))
                    .collect_into_vec(&mut built);
            });
            for ((class, _), geometry) in batch.iter().zip(built.drain(..)) {
                match geometry {
                    Some(geometry) => {
                        sink.push(class, &geometry)?;
                        stats.features += 1;
                    }
                    None => stats.geometry_failed += 1,
                }
                bar.tick("way(s)");
            }
            batch.clear();
        }
        if more.is_none() {
            break;
        }
    }
    bar.finish("way(s)");
    // Nothing reads the ways spill after this: the relations below reach their members through
    // `members`, which is why that table is kept at all.
    drop(reader);
    let _ = std::fs::remove_file(&ways_path);

    let mut bar = Progress::new(
        "Materialise: relations".to_string(),
        relations.len(),
        "relation(s)",
        true,
    );
    for relation in &relations {
        bar.tick("relation(s)");
        // A boundary relation's members are the border. Each is emitted as its own line rather than
        // stitched: the renderer strokes them, and a gap between two member ways is invisible in a
        // stroke while a failed stitch would drop the whole border.
        if !relation.area {
            let lines: Vec<Vec<(f64, f64)>> = relation
                .members
                .iter()
                .filter_map(|(id, _)| members.get(id))
                .map(|refs| table.line(refs))
                .filter(|line| line.len() >= 2)
                .collect();
            if lines.is_empty() {
                stats.geometry_failed += 1;
                continue;
            }
            sink.push(&relation.class, &Geometry::Lines(lines))?;
            stats.features += 1;
            continue;
        }
        let member_ways: Vec<MemberWay> = relation
            .members
            .iter()
            .filter_map(|(id, inner)| {
                Some(MemberWay { refs: members.get(id)?.clone(), outer: !inner })
            })
            .collect();
        let polygons = rings::assemble(&member_ways, |id| locate(&table, id), &mut stats.rings);
        if polygons.is_empty() {
            stats.geometry_failed += 1;
            continue;
        }
        sink.push(&relation.class, &Geometry::Polygons(polygons))?;
        stats.features += 1;
    }
    bar.finish("relation(s)");
    // The mainland, last, because clipping it needs the extract's own bounding box and that is
    // only known once every OSM feature has been through the sink. Order in the file does not
    // matter: the tiler groups by layer id, so `earth` is the first layer of every body whenever it
    // was written.
    if let Some(path) = coastline {
        match sink.bbox_degrees() {
            Some(bbox) => {
                println!(
                    "reading land polygons within {:.3},{:.3} .. {:.3},{:.3}",
                    bbox.0, bbox.1, bbox.2, bbox.3,
                );
                stats.land_polygons = schema::earth::stream_prepared(path, bbox, &mut sink)?;
                stats.features += stats.land_polygons;
            }
            // Nothing to clip against. Land alone would be an archive of one layer, and the caller
            // almost certainly pointed at the wrong extract.
            None => return err("the extract produced no features to place land against".to_string()),
        }
    }
    let store = sink.finish(spill_path)?;
    // The one phase that had no mark after it, and it turned out to be the largest single item in the
    // build outside tiling: ~25 s of a 136 s California run. It is 170 M coordinate lookups through
    // the mapped node table plus 3.3 GB of spill written, all on one thread.
    mark("materialised and spilled");
    Ok((store, stats))
}

/// Node refs, duplicates included, above which the bitset replaces the sorted vector.
///
/// 256 M refs is a 2 GB `Vec<i64>` plus a sort of 256 M elements, and it is roughly a us-west
/// extract. Below it the vector is smaller than the bitset and this stays exactly as it always was;
/// above it the vector is the largest thing in stage A and the bitset is a constant.
const REFS_IN_MEMORY: usize = 256 << 20;

/// The node id a bitset will not be sized past.
///
/// OSM assigns node ids monotonically and is near 13 G, so this is roughly 4x today's high-water
/// mark and 2.6 GB of bitset. It is a bound on the *data*, and a synthetic or non-OSM input with one
/// sparse enormous id would otherwise ask for a bitset the size of the id, so it is an error rather
/// than a clamp: a clamp would silently drop the nodes above it.
const MAX_NODE_ID: i64 = 48 << 30;

/// The ids pass 3 must resolve, collected the way stage A always did.
///
/// One exactly-sized vector, then [`NodeLocations::new`] sorts and dedups it in place. Sized rather
/// than grown into because a `Vec` that doubles its way to 200 M ids holds the old and the new
/// allocation at once across the last realloc -- 1.1 GB plus 2.1 GB, for a length both earlier passes
/// already know.
fn collect_needed_in_memory(
    ways_path: &Path,
    members: &HashMap<i64, Vec<i64>>,
    refs_total: usize,
    mark: &dyn Fn(&str),
) -> Result<NodeLocations> {
    let mut needed: Vec<i64> = Vec::with_capacity(refs_total);
    {
        let mut reader = WayReader::open(ways_path)?;
        let mut refs: Vec<i64> = Vec::new();
        while reader.next(&mut refs)?.is_some() {
            needed.extend_from_slice(&refs);
        }
    }
    // A member way that pass 1 also classified already contributed its refs above; `members` adds
    // the ones — the great majority, since a multipolygon's rings usually carry no tags of their
    // own — that nothing else asked for.
    for refs in members.values() {
        needed.extend_from_slice(refs);
    }
    mark("refs collected");
    NodeLocations::new(needed)
}

/// The same ids, through a bitset over the node id space.
///
/// The vector above is `way_refs + member_refs` entries of eight bytes **with duplicates**, then a
/// sort of all of them. On a north-america extract that is ~19 GB; on a planet one it projects to
/// ~96 GB and a sort of 12 G elements, which is the second of the two things that stopped a planet
/// build before anything else got the chance to.
///
/// A bit per node id costs `max_id / 8` and **does not scale with the extract**: OSM node ids are
/// assigned monotonically and top out near 13 G, so this is at most ~1.7 GB for any extract, forever.
/// Streaming the refs into it and then walking it low to high yields sorted unique ids directly --
/// which removes the vector *and* the sort in one pass, with no extra I/O.
///
/// Not an external merge sort of the ref stream. That is the general answer and needs no assumption
/// about the ids, and it costs ~96 GB written and ~96 GB read at planet scale to compute what this
/// gets in one pass. The bound that makes the bitset safe -- monotonic, bounded ids -- is a property
/// of OSM's data rather than a hope about it, and [`MAX_NODE_ID`] is where that property is enforced.
fn collect_needed_by_bitset(
    ways_path: &Path,
    members: &HashMap<i64, Vec<i64>>,
    max_ref: i64,
    mark: &dyn Fn(&str),
) -> Result<NodeLocations> {
    let mut bits = NeededBits::new(max_ref)?;
    {
        let mut reader = WayReader::open(ways_path)?;
        let mut refs: Vec<i64> = Vec::new();
        while reader.next(&mut refs)?.is_some() {
            for &id in &refs {
                bits.set(id)?;
            }
        }
    }
    for refs in members.values() {
        for &id in refs {
            bits.set(id)?;
        }
    }
    mark("refs collected");
    // Ascending and unique by construction, which is `from_sorted`'s whole precondition.
    NodeLocations::from_sorted(bits.ids(), bits.len())
}

/// One bit per node id in `0..=max_id`.
struct NeededBits {
    words: Vec<u64>,
    /// `max_id + 1`. Kept because the last word is padded and a ref landing in that padding is out
    /// of range, not merely addressable.
    bits: usize,
    count: usize,
}

impl NeededBits {
    fn new(max_id: i64) -> Result<NeededBits> {
        if max_id > MAX_NODE_ID {
            return err(format!(
                "node id {max_id} is past the {MAX_NODE_ID} this build will size a bitset for; \
                 OSM's own ids are near 13 billion, so this input is not an OSM extract"
            ));
        }
        // `max_id` is a maximum, not a count, so the space is one larger.
        let bits = max_id.max(0) as usize + 1;
        Ok(NeededBits { words: vec![0u64; bits.div_ceil(64)], bits, count: 0 })
    }

    fn set(&mut self, id: i64) -> Result<()> {
        // A negative ref is not a node this planet has. Refused rather than skipped: it means the
        // spill or the PBF is not what it claims, and skipping would show as a way with a gap in it.
        if id < 0 {
            return err(format!("node ref {id} is negative"));
        }
        let at = id as usize;
        if at >= self.bits {
            return err(format!(
                "node ref {id} is past the {} the bitset was sized for",
                self.bits - 1
            ));
        }
        let word = &mut self.words[at / 64];
        let bit = 1u64 << (at % 64);
        if *word & bit == 0 {
            *word |= bit;
            self.count += 1;
        }
        Ok(())
    }

    /// How many distinct ids were set. The `len` [`NodeLocations::from_sorted`] sizes from.
    fn len(&self) -> usize {
        self.count
    }

    /// Every set id, ascending. `trailing_zeros` on a word at a time, so an empty stretch of the id
    /// space -- which is most of it, since a needed set is a subset of a subset -- costs one compare
    /// per 64 ids rather than one per id.
    fn ids(&self) -> impl Iterator<Item = i64> + '_ {
        self.words.iter().enumerate().flat_map(|(w, word)| {
            let mut rest = *word;
            std::iter::from_fn(move || {
                if rest == 0 {
                    return None;
                }
                let bit = rest.trailing_zeros() as usize;
                rest &= rest - 1;
                Some((w * 64 + bit) as i64)
            })
        })
    }
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

    /// The claim the bitset path rests on: it yields exactly the sequence the vector path's sort and
    /// dedup would, for the same input. Everything downstream -- the id index, the positions the node
    /// pass writes coordinates at -- follows from that one equality.
    #[test]
    fn the_bitset_yields_what_a_sort_and_dedup_would() {
        // Deliberately awkward: duplicates, a run of consecutive ids inside one word, gaps that skip
        // whole words, both ends of the space, and ids past `u32::MAX`.
        let refs: Vec<i64> = vec![
            0, 63, 64, 65, 1, 1, 1, 4_294_967_295, 4_294_967_296, 12_345_678_901, 128, 127, 2, 63,
            12_345_678_901, 500_000, 499_999, 0,
        ];
        let mut want = refs.clone();
        want.sort_unstable();
        want.dedup();

        let max = *refs.iter().max().expect("refs");
        let mut bits = NeededBits::new(max).expect("size the bitset");
        for &id in &refs {
            bits.set(id).expect("set a bit");
        }
        assert_eq!(bits.ids().collect::<Vec<i64>>(), want);
        assert_eq!(bits.len(), want.len(), "the count must match what `from_sorted` is sized with");
    }

    /// The empty and single-id cases, which the word-at-a-time walk makes easy to get wrong.
    #[test]
    fn an_empty_or_single_id_bitset_is_not_a_special_case() {
        let empty = NeededBits::new(0).expect("size");
        assert_eq!(empty.len(), 0);
        assert!(empty.ids().next().is_none());

        let mut one = NeededBits::new(0).expect("size");
        one.set(0).expect("set");
        assert_eq!(one.ids().collect::<Vec<i64>>(), vec![0]);
        assert_eq!(one.len(), 1);

        // The maximum is inclusive, so a bitset sized for it must hold it.
        let mut edge = NeededBits::new(64).expect("size");
        edge.set(64).expect("the maximum is in range");
        assert_eq!(edge.ids().collect::<Vec<i64>>(), vec![64]);
    }

    /// The bound that makes the bitset affordable is a property of OSM's ids, so an input that
    /// breaks it has to say so rather than ask for an allocation the size of one id.
    #[test]
    fn an_id_outside_osms_own_range_is_refused_rather_than_sized_for() {
        assert!(NeededBits::new(MAX_NODE_ID + 1).is_err(), "a wild maximum was sized for");
        assert!(NeededBits::new(i64::MAX).is_err(), "`i64::MAX` was sized for");

        let mut bits = NeededBits::new(100).expect("size");
        assert!(bits.set(-1).is_err(), "a negative ref was accepted");
        assert!(bits.set(101).is_err(), "a ref past the maximum was accepted");
        assert!(bits.set(100).is_ok());
    }

    /// End to end over a real ways spill: the two collectors must agree on the set they hand pass 3.
    #[test]
    fn the_two_ref_collectors_agree_on_the_same_input() {
        let path = std::env::temp_dir().join(format!(
            "mamaps_refcollect_{}_{:?}.ways.tmp",
            std::process::id(),
            std::thread::current().id(),
        ));
        let class = schema::Class::area(
            tilecodec::mamaps::dict::LAYER_WATER,
            schema::kind("lake"),
            0,
        );
        let mut sink = WaySink::create(&path).expect("create the ways spill");
        // Ascending way ids, as pass 1 produces; overlapping refs, as real ways have.
        for way in 0..64i64 {
            let refs: Vec<i64> = (0..8).map(|i| way * 5 + i).collect();
            sink.push(way + 1, &class, &refs).expect("push");
        }
        let counts = sink.finish().expect("finish");

        let mut members: HashMap<i64, Vec<i64>> = HashMap::new();
        // One member whose refs overlap the spill's, and one that is entirely new.
        members.insert(7, vec![30, 31, 32, 30]);
        members.insert(9, vec![10_000, 9_999, 10_000]);
        let member_refs: usize = members.values().map(|refs| refs.len()).sum();
        let max_ref = members
            .values()
            .flat_map(|refs| refs.iter().copied())
            .fold(counts.max_ref, i64::max);

        let quiet = |_: &str| {};
        let in_memory =
            collect_needed_in_memory(&path, &members, counts.refs as usize + member_refs, &quiet)
                .expect("the in-memory path");
        let by_bitset =
            collect_needed_by_bitset(&path, &members, max_ref, &quiet).expect("the bitset path");

        assert_eq!(
            in_memory.len(),
            by_bitset.len(),
            "the two paths disagree on how many distinct nodes are needed",
        );
        // And the same ids, not merely the same count: both tables must answer for every ref and
        // for nothing between them that was never asked for.
        for id in [0i64, 1, 30, 31, 32, 319, 9_999, 10_000] {
            assert_eq!(
                in_memory.contains(id),
                by_bitset.contains(id),
                "the two paths disagree about node {id}",
            );
        }
        assert!(in_memory.contains(10_000), "a member-only ref is missing");
        assert!(!in_memory.contains(5_000), "an id nothing asked for is present");

        let _ = std::fs::remove_file(&path);
    }

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
