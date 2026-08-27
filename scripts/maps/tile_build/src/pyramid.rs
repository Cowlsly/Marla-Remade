//! The tile pyramid driver, and the drop policy.
//!
//! Turns a list of lon/lat features into a PMTiles archive:
//!
//! ```text
//! per zoom, per feature: project -> annotate -> tile range
//! per tile:              clip -> filter -> to_tile -> encode -> drop policy
//!                        -> gzip -> pmtiles::Builder
//! ```
//!
//! # The drop policy
//!
//! tippecanoe keeps a tile under its size limit with `--drop-densest-as-needed`, a
//! heuristic that removes whichever features are locally densest and whose result
//! depends on the order it happened to visit them. We do not reproduce it. Instead:
//!
//! 1. Features in a tile are put in a **stable importance order**: largest bounding
//!    box first, ties broken by the feature's index in the input. Size is the best
//!    cheap proxy for "matters at this zoom" -- at z6 a state boundary should
//!    survive and a suburban street should not -- and the index makes it total, so
//!    two runs over the same input always drop the same features.
//! 2. The largest prefix of that order which fits the gzipped byte budget is kept,
//!    found by binary search. A prefix, so the kept set is always the top-k most
//!    important, never a scattered subset.
//! 3. If even one feature does not fit, that one feature is kept anyway and the
//!    tile is reported as over budget. An empty tile is a hole in the map; an
//!    oversized one is a slow tile.
//!
//! Consequences worth being explicit about:
//!
//! * **Per-tile feature counts will not match tippecanoe's.** That is the point of
//!   `test/diff_pmtiles.py --max-feature-delta`: the divergence is bounded and
//!   measured rather than unknown.
//! * **`--extend-zooms-if-still-dropping` is deliberately not reproduced.** It can
//!   push an archive past its own `--maximum-zoom`, so an archive's advertised zoom
//!   range stops being a fact about its contents. Here `--maxzoom` is exactly the
//!   deepest zoom present.
//! * **`--detect-shared-borders` is not implemented.** Two *different* admin
//!   polygons that share a border are simplified independently, so at low zoom the
//!   border can show as a hairline gap or a hairline overlap. Reproducing that needs
//!   a shared topology pass across features, which is a project of its own. The
//!   intra-feature case — a hole and the exterior it shares a clipped tile edge with
//!   — is not affected: [`crate::simplify`] annotates each vertex once on the
//!   unclipped source and filters per zoom, so both rings keep the same vertices
//!   along the edge.

use crate::clip::clip_geometry;
use crate::geom::{self, Geometry, IntGeometry};
use crate::mvt::{self, FeatureRef as MvtFeature, GeomType, Value, DEFAULT_EXTENT};
use crate::par;
use crate::pmtiles::{self, Builder};
use crate::progress::Progress;
use crate::proto::{err, Error, Result};
use crate::simplify;
use crate::spill::{self, GeomKind};
use rayon::prelude::*;
use std::path::Path;

/// tippecanoe's default maximum gzipped tile size, and what the published
/// archives were built against.
pub const DEFAULT_MAX_TILE_BYTES: usize = 500_000;

/// One feature to tile: geometry in lon/lat, plus its properties.
#[derive(Debug, Clone)]
pub struct Feature {
    pub geometry: Geometry,
    pub props: Vec<(String, Value)>,
}

pub struct Options {
    pub layer: String,
    pub min_zoom: u8,
    pub max_zoom: u8,
    pub extent: u32,
    /// Simplification tolerance multiplier; 1.0 is the default policy.
    pub simplification: f64,
    pub max_tile_bytes: usize,
    /// Print a per-zoom progress bar to stdout while tiling.
    ///
    /// Off by default so the library stays silent: the binaries turn it on, and the
    /// tests would otherwise interleave bars with their output.
    pub progress: bool,
    /// Print how long each zoom's bucket and encode pass took, to stderr.
    ///
    /// Separate from `progress` and from [`ZoomStats`]: a duration cannot go in the
    /// report, because the report is compared for equality by the byte-identity tests.
    /// This is the measurement that says which phase to optimise next, and guessing
    /// that from a total is how you end up optimising the wrong one.
    pub timing: bool,
}
impl Options {
    pub fn new(layer: impl Into<String>, min_zoom: u8, max_zoom: u8) -> Options {
        Options {
            layer: layer.into(),
            min_zoom,
            max_zoom,
            extent: DEFAULT_EXTENT,
            simplification: 1.0,
            max_tile_bytes: DEFAULT_MAX_TILE_BYTES,
            progress: false,
            timing: false,
        }
    }

    /// Show the tiling progress bar. What the `tile_*` binaries call.
    pub fn with_progress(mut self) -> Options {
        self.progress = true;
        self
    }
}

/// A one-line progress bar for one zoom's tile loop.
///
/// The count is CANDIDATE tiles, which is the loop's length -- larger than the `tiles`
/// column in the report below, because a candidate whose geometry clips away to
/// nothing is never written. Saying "candidate" keeps the two numbers from looking
/// like a contradiction.
const CANDIDATES: &str = "candidate tile(s)";

/// What one zoom cost, for the per-zoom report the drop policy owes the operator.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct ZoomStats {
    pub zoom: u8,
    pub tiles: usize,
    /// Feature instances placed into tiles before the drop policy ran. A feature
    /// spanning four tiles counts four times, which is what the budget sees.
    pub placed: usize,
    pub kept: usize,
    pub dropped: usize,
    /// Tiles that could not be brought under budget even at one feature.
    pub over_budget: usize,
    pub largest_tile_bytes: usize,
}

/// One feature's contribution to one tile, ready for the drop policy.
///
/// Everything is borrowed. Both producers already own the geometry and the properties
/// somewhere -- the in-memory one in a per-tile vector, the streaming one in a loaded
/// bucket -- and a third copy per candidate would be the largest allocation in the
/// tiler. `extent` is [`extent_of`] precomputed at construction rather than recomputed
/// inside the importance sort, which would rescan every vertex `O(n log n)` times.
struct TileCandidate<'a> {
    /// The feature's position in the input, which is what makes the importance order
    /// total and therefore the drop policy deterministic.
    seq: u64,
    geom: &'a IntGeometry,
    props: &'a [(String, Value)],
    extent: i64,
}

/// One encoded candidate tile, waiting for the sequential fold that writes it.
///
/// The parallel passes produce these and the fold consumes them in `tile_id` order;
/// keeping the fields named is what makes that fold readable, since it has to update
/// five different counters plus the archive.
struct EncodedTile {
    id: u64,
    body: Vec<u8>,
    kept: usize,
    over_budget: bool,
    /// Candidates the tile held before the drop policy ran.
    placed: usize,
}

/// The stable importance order: largest bounding box first, input position breaking
/// ties.
///
/// Taken as loose fields so the streaming producer can sort its records by
/// `(tile_id, importance)` without first building candidates it cannot borrow from a
/// vector it is still sorting. One function either way, so the two producers cannot
/// disagree about which features a tight budget drops -- a disagreement no test
/// comparing anything less than whole bytes would catch.
fn by_importance_keys(
    a_extent: i64,
    a_seq: u64,
    b_extent: i64,
    b_seq: u64,
) -> std::cmp::Ordering {
    b_extent.cmp(&a_extent).then_with(|| a_seq.cmp(&b_seq))
}

fn by_importance(a: &TileCandidate, b: &TileCandidate) -> std::cmp::Ordering {
    by_importance_keys(a.extent, a.seq, b.extent, b.seq)
}

/// The candidate tiles of one zoom as `(tile_id, tx, ty)`, ascending by `tile_id`.
///
/// `(tx, ty)` order is **not** `tile_id` order -- at z1 the lexicographic walk gives
/// ids 1, 2, 4, 3 -- so sorting on the pair is not enough. [`Builder`] sorts by id
/// itself and so does not care, but [`pmtiles::StreamBuilder`] hard-errors on a
/// non-ascending id, and both producers visiting tiles in the same order is what lets
/// their outputs be compared byte for byte.
fn tiles_in_id_order(z: u8, tiles: impl IntoIterator<Item = (u64, u64)>) -> Vec<(u64, u64, u64)> {
    let mut out: Vec<(u64, u64, u64)> = tiles
        .into_iter()
        .map(|(tx, ty)| (pmtiles::tile_id(z, tx, ty), tx, ty))
        .collect();
    out.sort_unstable();
    out
}

/// One feature's contribution to one zoom's spill: every record it produces, in one
/// allocation.
///
/// One `Vec` per feature rather than one per record, because a coastline at z16
/// reaches thousands of tiles and a `Vec` each would make the allocator the hot
/// path. `spans` indexes `blob`, so pushing is `set.push(id, &blob[at..at + len])`.
struct BucketedFeature {
    blob: Vec<u8>,
    spans: Vec<(u64, usize, usize)>,
}

/// Project one feature, clip it into every tile it reaches, and encode a spill
/// record per tile.
///
/// Pure: every argument is shared except the two scratch buffers, which are the
/// caller's per-worker `touched` and `rec`. That is what lets the bucket pass run
/// this across the pool — and the reason the buffers are passed in rather than
/// declared here is the one the serial loop had, unchanged: allocating them per
/// feature would cost three million allocations a zoom.
///
/// Takes the geometry and properties rather than a [`Feature`] so the chunked read path
/// can pass a [`crate::spill::NormalizedFeature`]'s fields straight in. The two types
/// hold the same two things, and converting would mean copying a geometry per feature
/// per zoom to gain nothing.
#[allow(clippy::too_many_arguments)]
fn bucket_feature(
    geometry: &Geometry,
    props: &[(String, Value)],
    seq: u64,
    z: u8,
    opts: &Options,
    buffer: f64,
    tolerance: f64,
    touched: &mut Vec<(u64, u64)>,
    rec: &mut Vec<u8>,
) -> Result<BucketedFeature> {
    let t0 = std::time::Instant::now();
    // Project once per feature per zoom, not once per tile: a coastline can cross
    // thousands of tiles and the projection is the expensive part. Annotating here
    // for the same reason, and because it has to see the ring UNCLIPPED -- see
    // `simplify`'s module docs.
    let mut projected = geom::project_geometry(geometry, z, opts.extent);
    simplify::annotate(&mut projected);
    geom::tiles_touched(&projected, z, opts.extent, buffer, touched);
    let mut out = BucketedFeature {
        blob: Vec::new(),
        spans: Vec::with_capacity(touched.len()),
    };
    for &(tx, ty) in touched.iter() {
        let rect = geom::tile_rect(tx, ty, opts.extent, buffer);
        let clipped = clip_geometry(&projected, &rect);
        let filtered = simplify::filter(&clipped, tolerance);
        let local = geom::to_tile(&filtered, tx, ty, opts.extent);
        if local.is_empty() {
            continue;
        }
        rec.clear();
        spill::encode_record(
            pmtiles::tile_id(z, tx, ty),
            seq,
            extent_of(&local),
            &local,
            props,
            rec,
        )?;
        let at = out.blob.len();
        out.blob.extend_from_slice(rec);
        out.spans
            .push((pmtiles::tile_id(z, tx, ty), at, out.blob.len() - at));
    }
    BUCKET_NANOS.fetch_add(
        t0.elapsed().as_nanos() as u64,
        std::sync::atomic::Ordering::Relaxed,
    );
    Ok(out)
}

/// Build the archive, and a per-zoom report.
pub fn build_archive(features: &[Feature], opts: &Options) -> Result<(Vec<u8>, Vec<ZoomStats>)> {
    if opts.min_zoom > opts.max_zoom {
        return err("minzoom is above maxzoom");
    }
    if opts.extent == 0 {
        return err("extent 0");
    }

    let geom_type = dominant_geom_type(features);
    let buffer = geom::buffer_for(opts.extent);
    let mut builder = Builder::new();
    builder.min_zoom = opts.min_zoom;
    builder.max_zoom = opts.max_zoom;
    builder.center_zoom = opts.min_zoom;
    builder.metadata = metadata(&opts.layer, opts.min_zoom, opts.max_zoom).into_bytes();
    if let Some(b) = lonlat_bounds(features) {
        builder.min_lon_e7 = e7(b.min_x);
        builder.min_lat_e7 = e7(b.min_y);
        builder.max_lon_e7 = e7(b.max_x);
        builder.max_lat_e7 = e7(b.max_y);
        builder.center_lon_e7 = e7((b.min_x + b.max_x) / 2.0);
        builder.center_lat_e7 = e7((b.min_y + b.max_y) / 2.0);
    }

    let mut report = Vec::new();
    for z in opts.min_zoom..=opts.max_zoom {
        let mut stats = ZoomStats { zoom: z, ..Default::default() };
        let tolerance = simplify::tolerance_for(z, opts.max_zoom, opts.simplification);

        // Project once per zoom, not once per tile: a coastline can cross thousands
        // of tiles and the projection is the expensive part. Annotate here too, both
        // for that reason and because significance has to be measured on the
        // UNCLIPPED ring. Per-feature pure, so it maps across the pool; `collect`
        // into a Vec keeps it in input order.
        //
        // Identical to `bucket_feature`'s prologue on purpose: the byte-identity
        // tests compare the two producers, so a step added to one and not the other
        // is a test failure rather than a silent divergence.
        let projected: Vec<Option<Geometry<geom::SigPt>>> = par::install(|| {
            features
                .par_iter()
                .map(|f| {
                    let mut p = geom::project_geometry(&f.geometry, z, opts.extent);
                    simplify::annotate(&mut p);
                    Some(p)
                })
                .collect()
        });

        // tile -> the features that reach it, in input order.
        let mut by_tile: std::collections::HashMap<(u64, u64), Vec<usize>> =
            std::collections::HashMap::new();
        // Reused across features so the per-feature tile list is not reallocated
        // 3 million times per zoom.
        let mut touched: Vec<(u64, u64)> = Vec::new();
        for (i, p) in projected.iter().enumerate() {
            let Some(p) = p else { continue };
            // Per-segment boxes, not the whole feature's: see `geom::tiles_touched`.
            // The whole-feature box is what made a country-spanning line get clipped
            // into millions of z16 tiles it never enters.
            geom::tiles_touched(p, z, opts.extent, buffer, &mut touched);
            for &(tx, ty) in &touched {
                by_tile.entry((tx, ty)).or_default().push(i);
            }
        }

        let tiles = tiles_in_id_order(z, by_tile.keys().copied());
        let mut bar = Progress::new(
            format!("{} z{z}", opts.layer),
            tiles.len(),
            CANDIDATES,
            opts.progress,
        );
        // Across tiles, not within one: a tile holds a handful of features, so mapping
        // its clip loop over the pool costs more in scheduling than the clipping is
        // worth. Tiles are independent and there are many, which is the seam. The fold
        // below is sequential and in `tile_id` order, so `stats` and the archive are
        // exactly what the serial version produced.
        for chunk in tiles.chunks(par::batch_len()) {
            let done: Vec<Option<EncodedTile>> = par::install(|| {
                chunk
                    .par_iter()
                    .with_min_len(par::min_task_len(chunk.len()))
                    .map_init(crate::gz::Compressor::new, |gz, &(id, tx, ty)| {
                        let indices = &by_tile[&(tx, ty)];
                        let rect = geom::tile_rect(tx, ty, opts.extent, buffer);

                        // Clip, thin, move into the tile. Anything that vanishes
                        // here never reached the tile in the first place, so it is not
                        // a "drop".
                        let mut placed: Vec<(usize, i64, IntGeometry)> =
                            Vec::with_capacity(indices.len());
                        for &i in indices {
                            let p = projected[i].as_ref().expect("filtered above");
                            let clipped = clip_geometry(p, &rect);
                            let filtered = simplify::filter(&clipped, tolerance);
                            let local = geom::to_tile(&filtered, tx, ty, opts.extent);
                            if local.is_empty() {
                                continue;
                            }
                            placed.push((i, extent_of(&local), local));
                        }
                        if placed.is_empty() {
                            return Ok(None);
                        }

                        let mut candidates: Vec<TileCandidate> = placed
                            .iter()
                            .map(|(i, extent, g)| TileCandidate {
                                seq: *i as u64,
                                geom: g,
                                props: &features[*i].props,
                                extent: *extent,
                            })
                            .collect();
                        candidates.sort_by(by_importance);

                        let (body, kept, over) =
                            fit_tile(&candidates, &opts.layer, geom_type, opts, gz)?;
                        Ok(Some(EncodedTile {
                            id,
                            body,
                            kept,
                            over_budget: over,
                            placed: candidates.len(),
                        }))
                    })
                    .collect::<Result<Vec<_>>>()
            })?;

            for one in done {
                bar.tick(CANDIDATES);
                let Some(t) = one else { continue };
                stats.placed += t.placed;
                stats.kept += t.kept;
                stats.dropped += t.placed - t.kept;
                if t.over_budget {
                    stats.over_budget += 1;
                }
                stats.largest_tile_bytes = stats.largest_tile_bytes.max(t.body.len());
                stats.tiles += 1;
                builder.add_tile_raw(t.id, t.body);
            }
        }
        bar.finish(CANDIDATES);
        report.push(stats);
    }

    Ok((builder.build()?, report))
}

/// Where the encode pass's time actually goes, accumulated across workers.
///
/// Nanoseconds, summed over every `fit_tile` probe. Only read under `--timing`, and
/// the two `Instant::now()` calls are per PROBE (a handful per tile), not per feature,
/// so leaving them in costs nothing measurable.
///
/// These exist because four rounds of plausible-sounding micro-optimisation to this
/// function -- a galloping search, hoisting the geometry encode, borrowing the value
/// dictionary key, boxing the compressor -- bought 3% between them. Splitting the
/// measurement is what identified the real cost.
static MVT_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static GZIP_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
/// CPU spent inside [`bucket_feature`], summed over workers. Compared against the
/// bucket pass's WALL time, this says whether that pass is bound by its parallel
/// geometry work or by the serial stream read and spill write around it.
static BUCKET_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// Encode the largest prefix of `candidates` that fits the byte budget.
///
/// Binary search on the prefix length: `O(log n)` gzip calls per tile rather than
/// one per dropped feature, and the answer does not depend on the search order.
/// Returns the gzipped body, how many features it holds, and whether it is still
/// over budget -- which happens when even a single feature does not fit, and is the
/// one case worth telling the operator about.
fn fit_tile(
    candidates: &[TileCandidate],
    layer_name: &str,
    geom_type: GeomType,
    opts: &Options,
    gz: &mut crate::gz::Compressor,
) -> Result<(Vec<u8>, usize, bool)> {
    // Geometry command integers, computed ONCE for the whole tile.
    //
    // These do not depend on `n`, and the binary search calls `encode` about
    // `log2(len)` times -- so building them inside the probe re-did the same work
    // eleven times over, one fresh `Vec<u32>` allocation per candidate per probe. On
    // California z11 that is 4.36 M records x ~11 probes of pure waste, and it was
    // most of the 92 s that zoom spent encoding.
    let geoms: Vec<Vec<u32>> = candidates
        .iter()
        .map(|c| match c.geom {
            IntGeometry::Points(p) => mvt::encode_points(p),
            IntGeometry::Lines(l) => mvt::encode_lines(l),
            IntGeometry::Polygons(p) => mvt::encode_polygons(p),
        })
        .collect();
    // Candidates whose geometry survived, ascending. A candidate that clips away to
    // nothing is skipped rather than emitted, exactly as before.
    let live: Vec<usize> = (0..candidates.len())
        .filter(|&i| !geoms[i].is_empty())
        .collect();

    let mut encode = |n: usize| -> Vec<u8> {
        // `live` is ascending, so this is precisely the surviving candidates among the
        // first `n` -- the same set the old per-probe loop produced.
        let refs = live
            .iter()
            .take_while(|&&i| i < n)
            .map(|&i| MvtFeature {
                id: None,
                geom_type,
                geometry: &geoms[i],
                // Properties are BORROWED. Cloning them per candidate per tile was
                // ~35% of this function's cost, and a feature reaching many tiles paid
                // it once per tile. See `mvt::FeatureRef`.
                props: candidates[i].props,
            });
        let t0 = std::time::Instant::now();
        let body = mvt::encode_tile_from(layer_name, opts.extent, refs);
        let t1 = std::time::Instant::now();
        let out = gz.compress(&body);
        MVT_NANOS.fetch_add(
            (t1 - t0).as_nanos() as u64,
            std::sync::atomic::Ordering::Relaxed,
        );
        GZIP_NANOS.fetch_add(
            t1.elapsed().as_nanos() as u64,
            std::sync::atomic::Ordering::Relaxed,
        );
        out
    };

    let all = encode(candidates.len());
    if all.len() <= opts.max_tile_bytes {
        return Ok((all, candidates.len(), false));
    }

    // Largest n in 1..len with encode(n) within budget. `lo` is always known to
    // fit or to be the floor of 1; `hi` is known not to.
    //
    // The probe SEQUENCE is part of the output contract, so do not "improve" this into
    // a galloping search. Gzipped size is not strictly monotone in n -- adding a
    // feature can occasionally compress to fewer bytes -- so a search that visits
    // different n lands on a different boundary. Tried on California: galloping up
    // from 1 changed the drop count from 710,215 to 710,217 and moved the archive,
    // while saving nothing, because the cost here is not the number of probes.
    let mut lo = 1usize;
    let mut hi = candidates.len();
    let mut best = encode(1);
    while lo < hi {
        let mid = lo + (hi - lo).div_ceil(2);
        let body = encode(mid);
        if body.len() <= opts.max_tile_bytes {
            best = body;
            lo = mid;
        } else {
            hi = mid - 1;
        }
    }
    // One feature that does not fit is kept anyway: an empty tile is a hole in the
    // map, an oversized one is merely slow.
    let over = best.len() > opts.max_tile_bytes;
    Ok((best, lo, over))
}

/// A cheap importance proxy: the geometry's span in tile units, as a single
/// number. Bigger means more of the tile is affected by keeping it.
fn extent_of(g: &IntGeometry) -> i64 {
    let mut min = (i32::MAX, i32::MAX);
    let mut max = (i32::MIN, i32::MIN);
    let mut seen = false;
    let mut add = |(x, y): (i32, i32)| {
        seen = true;
        min = (min.0.min(x), min.1.min(y));
        max = (max.0.max(x), max.1.max(y));
    };
    match g {
        IntGeometry::Points(p) => p.iter().for_each(|p| add(*p)),
        IntGeometry::Lines(l) => l.iter().flatten().for_each(|p| add(*p)),
        IntGeometry::Polygons(p) => p.iter().flatten().flatten().for_each(|p| add(*p)),
    }
    if !seen {
        return 0;
    }
    (max.0 as i64 - min.0 as i64) + (max.1 as i64 - min.1 as i64)
}

/// The MVT geometry type for the layer.
///
/// MVT tags each feature individually, but a layer is styled as one thing, so a
/// mixed layer is a mistake somewhere upstream. Taking the first feature's type
/// and applying it throughout makes that mistake visible as wrong rendering rather
/// than hiding it as a silently split layer.
fn dominant_geom_type(features: &[Feature]) -> GeomType {
    geom_type_of(features.first().map(|f| GeomKind::of(&f.geometry)))
}

/// The same rule, from a kind a source reported rather than a feature in hand.
fn geom_type_of(kind: Option<GeomKind>) -> GeomType {
    match kind {
        Some(GeomKind::Points) => GeomType::Point,
        Some(GeomKind::Lines) => GeomType::LineString,
        Some(GeomKind::Polygons) => GeomType::Polygon,
        None => GeomType::Unknown,
    }
}

fn lonlat_bounds(features: &[Feature]) -> Option<geom::Rect> {
    features.iter().fold(None, |acc, f| fold_bounds(acc, &f.geometry))
}

/// Grow `acc` to cover `g`. The whole of [`lonlat_bounds`], so the streaming path can
/// fold the same function over a source it only ever sees one feature at a time and
/// arrive at the same header bytes.
pub fn fold_bounds(acc: Option<geom::Rect>, g: &Geometry) -> Option<geom::Rect> {
    let Some(b) = geom::bounds(g) else { return acc };
    Some(match acc {
        None => b,
        Some(a) => geom::Rect {
            min_x: a.min_x.min(b.min_x),
            min_y: a.min_y.min(b.min_y),
            max_x: a.max_x.max(b.max_x),
            max_y: a.max_y.max(b.max_y),
        },
    })
}

fn e7(deg: f64) -> i32 {
    (deg * 1e7).round().clamp(i32::MIN as f64, i32::MAX as f64) as i32
}

/// The `json` metadata blob a PMTiles archive carries. MapLibre does not need it to
/// render a styled layer, but `pmtiles show` and friends read it, so emitting a
/// truthful `vector_layers` list keeps the archive introspectable.
fn metadata(layer_name: &str, min_zoom: u8, max_zoom: u8) -> String {
    format!(
        "{{\"vector_layers\":[{{\"id\":\"{layer_name}\",\"minzoom\":{min_zoom},\
         \"maxzoom\":{max_zoom}}}]}}"
    )
}

// --- the streaming producer -----------------------------------------------

/// How much disk and recursion the streaming producer may use.
pub struct StreamLimits {
    /// Buckets per partition level. Must be a power of four so a bucket is exactly one
    /// quadtree cell's descendants, and must stay under the process's file-descriptor
    /// limit — see [`crate::spill::BucketSet`].
    pub buckets: usize,
    /// A bucket over this many bytes is re-partitioned rather than loaded. This is the
    /// number that sets peak memory, because a loaded bucket is resident.
    pub bucket_budget_bytes: u64,
    pub max_repartition_depth: u32,
}

/// 256 buckets: four levels of the quadtree per partition, and well under any
/// descriptor limit.
pub const DEFAULT_BUCKETS: usize = 256;

/// 512 MiB of encoded records per bucket. Decoded they cost more, so this is a budget
/// on the spill bytes rather than a promise about RSS; Phase 6's measurement is what
/// turns it into one.
pub const DEFAULT_BUCKET_BUDGET_BYTES: u64 = 512 << 20;

/// Recursion cap, so a pathological range cannot spin.
///
/// Not the thing that terminates the recursion: a child's span is its parent's range
/// divided by the bucket count, so it strictly shrinks and the single-`tile_id` floor is
/// always reached. This is the backstop, and it has to have room for the smallest useful
/// bucket count — 4 buckets need one level per zoom, so 24 covers z16 with headroom
/// while 8 would refuse a build the partition could have finished.
pub const DEFAULT_MAX_REPARTITION_DEPTH: u32 = 24;

impl Default for StreamLimits {
    fn default() -> StreamLimits {
        StreamLimits {
            buckets: DEFAULT_BUCKETS,
            bucket_budget_bytes: DEFAULT_BUCKET_BUDGET_BYTES,
            max_repartition_depth: DEFAULT_MAX_REPARTITION_DEPTH,
        }
    }
}

/// A rewindable feature stream, which is all [`build_archive_to`] needs of its input.
///
/// Rewindable because the producer makes one pass per zoom: six passes over a compact
/// binary is far cheaper than six re-parses of planet GeoJSON, and it is what lets the
/// features not be resident.
///
/// `bounds` and `geom_kind` come from a fold the caller already did while writing the
/// source, so there is no bounds pre-pass:
/// [`crate::pmtiles::StreamBuilder`] serialises its header last.
pub trait FeatureSource {
    fn rewind(&mut self) -> Result<()>;
    fn next(&mut self) -> Result<Option<Feature>>;
    /// How many features a pass will yield, for the progress bar.
    fn len(&self) -> u64;
    fn is_empty(&self) -> bool {
        self.len() == 0
    }
    fn bounds(&self) -> Option<geom::Rect>;
    /// The FIRST feature's kind, the rule [`dominant_geom_type`] already applies.
    fn geom_kind(&self) -> Option<GeomKind>;

    /// A positionally-readable view of the same features, when there is one.
    ///
    /// `Some` lets the bucket pass read and decode the source across the pool rather
    /// than through this cursor; `None` keeps it sequential. Only the file-backed source
    /// has one: [`SliceSource`]'s features are already resident and there is nothing to
    /// read. It is also `None` for a file written before the index existed, or an empty
    /// one, so the sequential path stays the fallback rather than a special case.
    fn chunks(&self) -> Option<&spill::NormalizedChunks> {
        None
    }
}

/// A [`FeatureSource`] over features already in memory.
///
/// What the byte-identity tests drive the streaming producer from, so they need no
/// temporary input file and compare exactly the same features the in-memory path saw.
pub struct SliceSource<'a> {
    features: &'a [Feature],
    at: usize,
    bounds: Option<geom::Rect>,
    kind: Option<GeomKind>,
}

impl<'a> SliceSource<'a> {
    pub fn new(features: &'a [Feature]) -> SliceSource<'a> {
        SliceSource {
            features,
            at: 0,
            bounds: lonlat_bounds(features),
            kind: features.first().map(|f| GeomKind::of(&f.geometry)),
        }
    }
}

impl FeatureSource for SliceSource<'_> {
    fn rewind(&mut self) -> Result<()> {
        self.at = 0;
        Ok(())
    }

    fn next(&mut self) -> Result<Option<Feature>> {
        let f = self.features.get(self.at).cloned();
        if f.is_some() {
            self.at += 1;
        }
        Ok(f)
    }

    fn len(&self) -> u64 {
        self.features.len() as u64
    }

    fn bounds(&self) -> Option<geom::Rect> {
        self.bounds
    }

    fn geom_kind(&self) -> Option<GeomKind> {
        self.kind
    }
}

/// A [`FeatureSource`] over [`crate::spill`]'s normalized file. The production path.
pub struct NormalizedSource {
    reader: spill::NormalizedReader,
    summary: spill::NormalizedSummary,
    chunks: Option<spill::NormalizedChunks>,
}

impl NormalizedSource {
    /// `summary` is what [`crate::spill::NormalizedWriter::finish`] returned for this
    /// file. Passed in rather than re-derived, because deriving it would be the pre-pass
    /// the design exists to avoid.
    pub fn open(
        path: impl Into<std::path::PathBuf>,
        summary: spill::NormalizedSummary,
    ) -> Result<NormalizedSource> {
        let path = path.into();
        // A second handle on the same file, for the chunked reads. Separate because the
        // sequential reader owns a cursor and positional reads must not disturb it.
        let chunks = if summary.chunk_count() == 0 {
            None
        } else {
            Some(spill::NormalizedChunks::open(
                path.clone(),
                summary.chunks.clone(),
            )?)
        };
        Ok(NormalizedSource {
            reader: spill::NormalizedReader::open(path)?,
            summary,
            chunks,
        })
    }
}

impl FeatureSource for NormalizedSource {
    fn rewind(&mut self) -> Result<()> {
        self.reader.rewind()
    }

    fn next(&mut self) -> Result<Option<Feature>> {
        Ok(self.reader.next()?.map(|f| Feature {
            geometry: f.geometry,
            props: f.props,
        }))
    }

    fn len(&self) -> u64 {
        self.summary.count
    }

    fn bounds(&self) -> Option<geom::Rect> {
        self.summary.bounds
    }

    fn geom_kind(&self) -> Option<GeomKind> {
        self.summary.geom_kind
    }

    fn chunks(&self) -> Option<&spill::NormalizedChunks> {
        self.chunks.as_ref()
    }
}

/// Features bucketed in one zoom's first pass.
const BUCKETED: &str = "feature(s) bucketed";

/// Spill records encoded in one zoom's second pass.
const ENCODED: &str = "record(s) encoded";

/// One zoom's bucket pass: project every feature, clip it into each tile it reaches, and
/// write a spill record per `(feature, tile)`.
///
/// Two read shapes, one output. Both derive `seq` from the feature's index in the source
/// rather than from arrival order, which is what makes them interchangeable:
/// `encode_buckets` sorts each bucket by the total key `(tile_id, extent, seq)`, so push
/// order never reaches the archive.
#[allow(clippy::too_many_arguments)]
fn bucket_pass(
    src: &mut impl FeatureSource,
    z: u8,
    opts: &Options,
    buffer: f64,
    tolerance: f64,
    set: &mut spill::BucketSet,
    bar: &mut Progress,
) -> Result<()> {
    if let Some(chunks) = src.chunks() {
        return bucket_chunked(chunks, z, opts, buffer, tolerance, set, bar);
    }
    bucket_serial(src, z, opts, buffer, tolerance, set, bar)
}

/// Read serially, project and clip across the pool, push serially.
///
/// The fallback shape, for a source that is only a cursor. Everything between the read
/// and the write -- projection, clipping, simplification, record encoding -- is
/// per-feature pure and is where the time goes, so that part threads; the read does not.
/// Live memory is one batch's records, which is what bounds the batch.
#[allow(clippy::too_many_arguments)]
fn bucket_serial(
    src: &mut impl FeatureSource,
    z: u8,
    opts: &Options,
    buffer: f64,
    tolerance: f64,
    set: &mut spill::BucketSet,
    bar: &mut Progress,
) -> Result<()> {
    let mut touched: Vec<(u64, u64)> = Vec::new();
    let mut rec = Vec::new();
    let mut seq = 0u64;
    let batch_len = par::batch_len();
    let mut batch: Vec<Feature> = Vec::with_capacity(batch_len);
    loop {
        batch.clear();
        while batch.len() < batch_len {
            match src.next()? {
                Some(f) => batch.push(f),
                None => break,
            }
        }
        if batch.is_empty() {
            break;
        }
        let first = seq;
        let encoded: Vec<Result<BucketedFeature>> = if batch.len() == 1 {
            // One feature is not worth a pool round trip, and this is the path the
            // single-threaded case and the tail of every stream take.
            vec![bucket_feature(
                &batch[0].geometry,
                &batch[0].props,
                first,
                z,
                opts,
                buffer,
                tolerance,
                &mut touched,
                &mut rec,
            )]
        } else {
            par::install(|| {
                batch
                    .par_iter()
                    .enumerate()
                    .with_min_len(par::min_task_len(batch.len()))
                    .map_init(
                        || (Vec::new(), Vec::new()),
                        |(touched, rec), (i, f)| {
                            bucket_feature(
                                &f.geometry,
                                &f.props,
                                first + i as u64,
                                z,
                                opts,
                                buffer,
                                tolerance,
                                touched,
                                rec,
                            )
                        },
                    )
                    .collect()
            })
        };
        for one in encoded {
            let one = one?;
            for &(id, at, len) in &one.spans {
                set.push(id, &one.blob[at..at + len])?;
            }
            bar.tick(BUCKETED);
        }
        seq += batch.len() as u64;
    }
    Ok(())
}

/// Read, decode, project and clip across the pool; push serially.
///
/// The production shape. The serial path's remaining bottleneck was `src.next()`: it
/// decodes a geometry and a property vector per feature, allocating both, and does so
/// once per zoom on whichever single thread owns the cursor while the pool waits. Here a
/// worker takes a whole chunk, reads its byte range positionally, and decodes it itself,
/// so the decode scales with the pool and the pass no longer has a serial middle.
///
/// Chunk `c` starts at feature `c * NORM_CHUNK_FEATURES` by construction of the index,
/// which is what lets this reproduce the sequential path's `seq` exactly rather than
/// approximately.
#[allow(clippy::too_many_arguments)]
fn bucket_chunked(
    chunks: &spill::NormalizedChunks,
    z: u8,
    opts: &Options,
    buffer: f64,
    tolerance: f64,
    set: &mut spill::BucketSet,
    bar: &mut Progress,
) -> Result<()> {
    // One chunk per task, and as many chunks per batch as the serial path holds
    // features, so peak live memory is unchanged: a batch's encoded records bound it
    // either way.
    let per_batch = (par::batch_len() / spill::NORM_CHUNK_FEATURES as usize).max(1);
    let total = chunks.chunk_count();
    let mut ids: Vec<usize> = Vec::with_capacity(per_batch);
    let mut at = 0usize;
    while at < total {
        let end = (at + per_batch).min(total);
        ids.clear();
        ids.extend(at..end);
        let encoded: Vec<Result<Vec<BucketedFeature>>> = par::install(|| {
            ids.par_iter()
                .with_min_len(par::min_task_len(ids.len()))
                .map_init(
                    || (Vec::new(), Vec::new(), Vec::new(), Vec::new()),
                    |(scratch, feats, touched, rec), &ci| {
                        chunks.read_into(ci, scratch, feats)?;
                        let first = ci as u64 * spill::NORM_CHUNK_FEATURES;
                        let mut out = Vec::with_capacity(feats.len());
                        for (j, f) in feats.iter().enumerate() {
                            out.push(bucket_feature(
                                &f.geometry,
                                &f.props,
                                first + j as u64,
                                z,
                                opts,
                                buffer,
                                tolerance,
                                touched,
                                rec,
                            )?);
                        }
                        Ok(out)
                    },
                )
                .collect()
        });
        for one in encoded {
            for f in one? {
                for &(id, o, len) in &f.spans {
                    set.push(id, &f.blob[o..o + len])?;
                }
                bar.tick(BUCKETED);
            }
        }
        at = end;
    }
    Ok(())
}

/// Build the archive straight to `out`, with peak memory set by the tile count.
///
/// The same drop policy, the same encoder and the same [`ZoomStats`] as
/// [`build_archive`] — which stays as the oracle the byte-identity tests pin this
/// against — but nothing proportional to the input bytes is ever resident. Per zoom:
///
/// 1. **The bucket pass** rewinds `src`, projects each feature once, finds the tiles it
///    touches, and for each of them clips, moves into the tile and simplifies, writing a
///    [`crate::spill`] record. Empties are skipped exactly as [`build_archive`] skips
///    them, so `placed` counts the same things.
/// 2. **The encode pass** walks the buckets in ascending index, re-partitioning any that
///    exceed the budget, and for each loaded bucket sorts by `(tile_id, importance)`,
///    groups runs of equal `tile_id`, and calls the same [`fit_tile`].
///
/// The bucket set is dropped at the end of each zoom, so peak spill disk is the largest
/// SINGLE zoom rather than the sum of them — `tile_id` is zoom-major, so every id at
/// `z` precedes every id at `z+1` and a zoom can be finished before the next begins.
///
/// I/O, with `F` the source, `S_z` a zoom's spill, `T` the archive and `Z` zooms:
/// `read(F) · Z + write(S_z) + read(S_z)` per zoom, plus `3·T` for the writer's scratch
/// round trip. Disk peak is `F + max(S_z) + T`, plus one full copy of an over-budget
/// bucket per level of re-partition below it — an ancestor set's files stay alive while
/// its children are drained, so a heavily skewed metro range costs
/// `depth × that bucket's bytes` on top.
pub fn build_archive_to(
    out: impl AsRef<Path>,
    scratch_dir: impl AsRef<Path>,
    opts: &Options,
    src: &mut impl FeatureSource,
    limits: &StreamLimits,
) -> Result<Vec<ZoomStats>> {
    if opts.min_zoom > opts.max_zoom {
        return err("minzoom is above maxzoom");
    }
    if opts.extent == 0 {
        return err("extent 0");
    }
    // This path needs `zoom_base(max_zoom + 1)` for the bucket range, which
    // `build_archive` never computes and which overflows above z30. Refused rather than
    // left to panic: the in-memory path merely produces nonsense that deep, and a
    // message is a better failure than a shift overflow.
    if opts.max_zoom > 30 {
        return err(format!(
            "maxzoom {} is above 30, the deepest zoom a PMTiles tile id is exact at",
            opts.max_zoom
        ));
    }
    let out = out.as_ref();
    let scratch_dir = scratch_dir.as_ref();
    std::fs::create_dir_all(scratch_dir)
        .map_err(|e| Error(format!("cannot create {}: {e}", scratch_dir.display())))?;

    let geom_type = geom_type_of(src.geom_kind());
    let buffer = geom::buffer_for(opts.extent);

    // Beside the OUTPUT, not in the spill directory: `finish` copies this section into
    // the archive, and a cross-filesystem copy of 40 GB would be needlessly slow.
    let tile_scratch = {
        let mut p = out.as_os_str().to_owned();
        p.push(".tiledata");
        std::path::PathBuf::from(p)
    };
    let mut builder = pmtiles::StreamBuilder::new(tile_scratch)?;
    builder.min_zoom = opts.min_zoom;
    builder.max_zoom = opts.max_zoom;
    builder.center_zoom = opts.min_zoom;
    builder.metadata = metadata(&opts.layer, opts.min_zoom, opts.max_zoom).into_bytes();
    match src.bounds() {
        Some(b) => {
            builder.min_lon_e7 = e7(b.min_x);
            builder.min_lat_e7 = e7(b.min_y);
            builder.max_lon_e7 = e7(b.max_x);
            builder.max_lat_e7 = e7(b.max_y);
            builder.center_lon_e7 = e7((b.min_x + b.max_x) / 2.0);
            builder.center_lat_e7 = e7((b.min_y + b.max_y) / 2.0);
        }
        // `Builder::new` defaults latitude to +/-850_511_290 and `StreamBuilder::new` to
        // +/-850_511_287. Three units, and on an input with no bounds nothing else
        // overwrites them -- so leaving them alone would make the two producers disagree
        // on an empty archive's header.
        None => {
            builder.min_lon_e7 = -1_800_000_000;
            builder.min_lat_e7 = -850_511_290;
            builder.max_lon_e7 = 1_800_000_000;
            builder.max_lat_e7 = 850_511_290;
        }
    }

    let mut report = Vec::new();
    for z in opts.min_zoom..=opts.max_zoom {
        let mut stats = ZoomStats { zoom: z, ..Default::default() };
        let tolerance = simplify::tolerance_for(z, opts.max_zoom, opts.simplification);

        // Named per zoom so a set cannot be mistaken for the previous zoom's, and
        // scoped to the zoom so its files go before the next one allocates disk.
        let mut set = spill::BucketSet::new(
            scratch_dir,
            &format!("z{z}"),
            pmtiles::zoom_base(z),
            pmtiles::zoom_base(z + 1),
            limits.buckets,
        )?;

        src.rewind()?;
        let mut bar = Progress::new(
            format!("{} z{z} bucket", opts.layer),
            src.len() as usize,
            BUCKETED,
            opts.progress,
        );
        let phase = std::time::Instant::now();
        bucket_pass(src, z, opts, buffer, tolerance, &mut set, &mut bar)?;
        bar.finish(BUCKETED);
        let bucketed_in = phase.elapsed();
        set.seal()?;
        let phase = std::time::Instant::now();

        let mut bar = Progress::new(
            format!("{} z{z} encode", opts.layer),
            set.total_records() as usize,
            ENCODED,
            opts.progress,
        );
        let mut prof = EncodeProfile::default();
        encode_buckets(
            &set,
            scratch_dir,
            z,
            0,
            opts,
            geom_type,
            limits,
            &mut builder,
            &mut stats,
            &mut bar,
            &mut prof,
        )?;
        bar.finish(ENCODED);
        if opts.timing {
            let (_, x, y) = pmtiles::tile_zxy(prof.max_group_id);
            use std::sync::atomic::Ordering::Relaxed;
            let mvt_s = MVT_NANOS.swap(0, Relaxed) as f64 / 1e9;
            let gz_s = GZIP_NANOS.swap(0, Relaxed) as f64 / 1e9;
            eprintln!(
                "  z{z}: bucket {:.1}s [{:.0}s CPU], encode {:.1}s [mvt {:.0}s + gzip \
                 {:.0}s of CPU across all workers] ({} record(s), {} tile(s), biggest \
                 z{z}/{x}/{y} with {} candidate(s) = {:.1}%)",
                bucketed_in.as_secs_f64(),
                BUCKET_NANOS.swap(0, Relaxed) as f64 / 1e9,
                phase.elapsed().as_secs_f64(),
                mvt_s,
                gz_s,
                set.total_records(),
                prof.groups,
                prof.max_group,
                prof.max_group as f64 / set.total_records().max(1) as f64 * 100.0,
            );
        }
        report.push(stats);
    }

    builder.finish(out)?;
    Ok(report)
}

/// What the encode pass spent its time on, for `--timing`.
///
/// The interesting number is the LARGEST group: the encode pass parallelises across
/// tiles, so one tile holding a large fraction of a zoom's records sets a floor no
/// thread count can get under.
#[derive(Default)]
struct EncodeProfile {
    groups: usize,
    max_group: usize,
    max_group_id: u64,
}

/// Drain one bucket set in ascending index, recursing into anything over budget.
///
/// Sub-buckets ascend within a parent and parents ascend, so the ids handed to
/// [`crate::pmtiles::StreamBuilder`] ascend at every depth.
#[allow(clippy::too_many_arguments)]
fn encode_buckets(
    set: &spill::BucketSet,
    scratch_dir: &Path,
    z: u8,
    depth: u32,
    opts: &Options,
    geom_type: GeomType,
    limits: &StreamLimits,
    builder: &mut pmtiles::StreamBuilder,
    stats: &mut ZoomStats,
    bar: &mut Progress,
    prof: &mut EncodeProfile,
) -> Result<()> {
    for i in 0..set.len() {
        let records = set.records_in(i);
        if records == 0 {
            continue;
        }
        let bytes = set.bytes_in(i);
        let (lo, hi) = set.range_of(i);

        if bytes > limits.bucket_budget_bytes {
            // `--buckets 1` would give a child covering exactly this range, so the split
            // is a no-op that copies the bucket once per level until the depth cap. A
            // split has to actually narrow the range to be worth doing.
            let splittable = hi - lo > 1
                && limits.buckets > 1
                && depth < limits.max_repartition_depth;
            if !splittable {
                // A single tile's candidates cannot be split any further, and capping
                // them would change which features survive -- an output change, and out
                // of scope. Erroring beats being killed by the OOM reaper halfway
                // through a planet run with nothing to show for it.
                if hi - lo == 1 {
                    let (_, x, y) = pmtiles::tile_zxy(lo);
                    return err(format!(
                        "z{z}/{x}/{y} alone holds {records} spill record(s) in {bytes} byte(s), \
                         over the {}-byte bucket budget; raise --bucket-budget-bytes",
                        limits.bucket_budget_bytes
                    ));
                }
                return err(format!(
                    "z{z} tile ids {lo}..{hi} hold {records} spill record(s) in {bytes} byte(s), \
                     over the {}-byte bucket budget, and the range cannot be split further \
                     (--buckets {}, --max-repartition-depth {}, depth {depth})",
                    limits.bucket_budget_bytes, limits.buckets, limits.max_repartition_depth
                ));
            }
            // `lo` is unique across every sibling at every depth, so the child's files
            // cannot collide with anyone else's.
            let mut child = spill::BucketSet::new(
                scratch_dir,
                &format!("z{z}_d{}_{lo}", depth + 1),
                lo,
                hi,
                limits.buckets,
            )?;
            let mut reader = set
                .reader(i)?
                .ok_or_else(|| Error(format!("bucket {i} of z{z} vanished mid-pass")))?;
            let mut buf = Vec::new();
            while let Some(r) = reader.next()? {
                buf.clear();
                r.encode(&mut buf)?;
                child.push(r.tile_id, &buf)?;
            }
            child.seal()?;
            encode_buckets(
                &child,
                scratch_dir,
                z,
                depth + 1,
                opts,
                geom_type,
                limits,
                builder,
                stats,
                bar,
                prof,
            )?;
            continue;
        }

        let mut recs = set.load(i)?;
        // The total sort key: tile id first, then the drop policy's own order, so a
        // group is already in importance order when `fit_tile` sees it.
        recs.sort_by(|a, b| {
            a.tile_id
                .cmp(&b.tile_id)
                .then_with(|| by_importance_keys(a.extent, a.seq, b.extent, b.seq))
        });

        // Runs of equal `tile_id`. Each run is one candidate tile, and `fit_tile` is
        // pure -- all-shared refs, a fresh `Layer`/`Tile` per call, no statics -- so the
        // runs are independent and that is the seam.
        //
        // Parallel WITHIN a bucket rather than across buckets: one bucket is resident at
        // a time either way, so peak memory is unchanged and `--bucket-budget-bytes`
        // keeps meaning what it says. Going across buckets instead would hold one
        // decoded bucket per thread and turn a 690 MiB peak into ~22 GB at 32 threads.
        let mut groups: Vec<(usize, usize)> = Vec::new();
        let mut k = 0usize;
        while k < recs.len() {
            let id = recs[k].tile_id;
            let mut j = k;
            while j < recs.len() && recs[j].tile_id == id {
                j += 1;
            }
            groups.push((k, j));
            prof.groups += 1;
            if j - k > prof.max_group {
                prof.max_group = j - k;
                prof.max_group_id = id;
            }
            k = j;
        }

        let encode_group = |gz: &mut crate::gz::Compressor,
                            &(k, j): &(usize, usize)|
         -> Result<EncodedTile> {
            let candidates: Vec<TileCandidate> = recs[k..j]
                .iter()
                .map(|r| TileCandidate {
                    seq: r.seq,
                    geom: &r.geom,
                    props: &r.props,
                    extent: r.extent,
                })
                .collect();
            let (body, kept, over) = fit_tile(&candidates, &opts.layer, geom_type, opts, gz)?;
            Ok(EncodedTile {
                id: recs[k].tile_id,
                body,
                kept,
                over_budget: over,
                placed: candidates.len(),
            })
        };

        // A batch at a time, so live memory is a bounded number of encoded bodies
        // rather than every body in the bucket. The fold below is sequential and in
        // ascending group order, which is what keeps `add_tile_raw` fed ascending ids
        // and the `stats` sums and `largest_tile_bytes` max identical to a serial run.
        for chunk in groups.chunks(par::batch_len()) {
            let done: Vec<EncodedTile> = par::install(|| {
                chunk
                    .par_iter()
                    .with_min_len(par::min_task_len(chunk.len()))
                    .map_init(crate::gz::Compressor::new, encode_group)
                    .collect::<Result<Vec<_>>>()
            })?;
            for t in done {
                for _ in 0..t.placed {
                    bar.tick(ENCODED);
                }
                stats.placed += t.placed;
                stats.kept += t.kept;
                stats.dropped += t.placed - t.kept;
                if t.over_budget {
                    stats.over_budget += 1;
                }
                stats.largest_tile_bytes = stats.largest_tile_bytes.max(t.body.len());
                stats.tiles += 1;
                builder.add_tile_raw(t.id, &t.body)?;
            }
        }
    }
    Ok(())
}

/// Print the per-zoom report the drop policy owes the operator.
pub fn print_report(report: &[ZoomStats], out: &mut impl std::io::Write) -> std::io::Result<()> {
    writeln!(out, "  zoom  tiles   placed     kept  dropped  largest")?;
    for s in report {
        writeln!(
            out,
            "  z{:<4} {:>6} {:>8} {:>8} {:>8} {:>8}{}",
            s.zoom,
            s.tiles,
            s.placed,
            s.kept,
            s.dropped,
            s.largest_tile_bytes,
            if s.over_budget > 0 {
                format!("  ({} tile(s) over budget)", s.over_budget)
            } else {
                String::new()
            }
        )?;
    }
    Ok(())
}

// --- the shared CLI -------------------------------------------------------

/// Which geometry a binary will tile. A layer is styled as one thing, so mixing
/// kinds in one archive is a mistake upstream; each binary accepts exactly one and
/// counts what it turns away.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Accept {
    Lines,
    Polygons,
}

impl Accept {
    fn matches(self, g: &Geometry) -> bool {
        matches!(
            (self, g),
            (Accept::Lines, Geometry::Lines(_)) | (Accept::Polygons, Geometry::Polygons(_))
        )
    }

    fn describe(self) -> &'static str {
        match self {
            Accept::Lines => "LineString or MultiLineString",
            Accept::Polygons => "Polygon or MultiPolygon",
        }
    }
}

/// `tile_lines` and `tile_polygons` are the same program with a different
/// [`Accept`], so they share one implementation and differ only in the name they
/// print.
pub fn cli_main(name: &str, accept: Accept, argv: &[String]) -> std::process::ExitCode {
    use std::io::BufRead;
    use std::process::ExitCode;

    let usage = || {
        eprintln!("usage: {name} --geojson IN.geojsonseq --out OUT.pmtiles --layer NAME");
        eprintln!("                  [--minzoom N] [--maxzoom N] [--simplification F]");
        eprintln!("                  [--max-tile-bytes N] [--extent N] [--threads N] [--timing]");
        eprintln!("                  [--stream [--spill-dir DIR] [--buckets N]");
        eprintln!("                            [--bucket-budget-bytes N] [--max-repartition-depth N]]");
        eprintln!();
        eprintln!("  --stream  keep peak memory proportional to the TILE COUNT rather than the");
        eprintln!("            input bytes, by spilling to --spill-dir. Required for a");
        eprintln!("            planet-scale layer; unnecessary for a metro extract. Output is");
        eprintln!("            byte-identical either way.");
    };

    let mut geojson = None;
    let mut out = None;
    let mut layer = None;
    let mut min_zoom = 11u8;
    let mut max_zoom = 14u8;
    let mut simplification = 1.0f64;
    let mut max_tile_bytes = DEFAULT_MAX_TILE_BYTES;
    let mut extent = DEFAULT_EXTENT;
    let mut stream = false;
    let mut timing = false;
    let mut spill_dir: Option<String> = None;
    let mut limits = StreamLimits::default();

    let mut i = 0;
    while i < argv.len() {
        let value = || argv.get(i + 1).cloned();
        match argv[i].as_str() {
            "--geojson" => {
                geojson = value();
                i += 2;
            }
            "--out" => {
                out = value();
                i += 2;
            }
            "--layer" => {
                layer = value();
                i += 2;
            }
            "--stream" => {
                stream = true;
                i += 1;
            }
            "--spill-dir" => {
                spill_dir = value();
                i += 2;
            }
            // A bad numeric value is fatal rather than falling back to the default:
            // silently tiling z11-14 when the caller asked for z0-8 produces an
            // archive that looks fine and is wrong.
            flag @ ("--minzoom" | "--maxzoom" | "--simplification" | "--max-tile-bytes"
            | "--extent" | "--buckets" | "--bucket-budget-bytes"
            | "--max-repartition-depth") => {
                let Some(raw) = value() else {
                    eprintln!("{name}: {flag} needs a value");
                    return ExitCode::from(2);
                };
                let ok = match flag {
                    "--minzoom" => raw.parse().map(|v| min_zoom = v).is_ok(),
                    "--maxzoom" => raw.parse().map(|v| max_zoom = v).is_ok(),
                    "--simplification" => raw.parse().map(|v| simplification = v).is_ok(),
                    "--max-tile-bytes" => raw.parse().map(|v| max_tile_bytes = v).is_ok(),
                    "--extent" => raw.parse().map(|v| extent = v).is_ok(),
                    "--buckets" => raw.parse().map(|v| limits.buckets = v).is_ok(),
                    "--bucket-budget-bytes" => {
                        raw.parse().map(|v| limits.bucket_budget_bytes = v).is_ok()
                    }
                    _ => raw.parse().map(|v| limits.max_repartition_depth = v).is_ok(),
                };
                if !ok {
                    eprintln!("{name}: {flag} wants a number, got '{raw}'");
                    return ExitCode::from(2);
                }
                i += 2;
            }
            "--timing" => {
                timing = true;
                i += 1;
            }
            "--threads" => {
                let Some(raw) = value() else {
                    eprintln!("{name}: --threads needs a value");
                    return ExitCode::from(2);
                };
                match par::parse_threads(&raw) {
                    Ok(n) => par::set_threads(n),
                    Err(e) => {
                        eprintln!("{name}: {e}");
                        return ExitCode::from(2);
                    }
                }
                i += 2;
            }
            "-h" | "--help" => {
                usage();
                return ExitCode::SUCCESS;
            }
            other => {
                eprintln!("{name}: unexpected argument '{other}'");
                usage();
                return ExitCode::from(2);
            }
        }
    }

    let (Some(geojson), Some(out), Some(layer)) = (geojson, out, layer) else {
        eprintln!("{name}: --geojson, --out and --layer are all required");
        usage();
        return ExitCode::from(2);
    };
    if min_zoom > max_zoom {
        eprintln!("{name}: --minzoom {min_zoom} is above --maxzoom {max_zoom}");
        return ExitCode::from(2);
    }
    // Checked before the parse pass rather than after it: a bad bucket count would
    // otherwise fail an hour into a planet run, having already written the whole
    // normalized file.
    if stream && (!limits.buckets.is_power_of_two() || limits.buckets.trailing_zeros() % 2 != 0) {
        eprintln!(
            "{name}: --buckets must be a power of four so a bucket is one quadtree cell, \
             got {}",
            limits.buckets
        );
        return ExitCode::from(2);
    }

    let opts = Options {
        layer: layer.clone(),
        min_zoom,
        max_zoom,
        extent,
        simplification,
        max_tile_bytes,
        // A planet layer spends minutes to hours per zoom, so the operator gets a bar.
        progress: true,
        timing,
    };

    // Read a line at a time rather than slurping the file: a planet geojsonseq is tens
    // of gigabytes and `read_to_string` keeps all of it resident alongside whatever the
    // parse produces, so the two peaks land together for no reason.
    let file = match std::fs::File::open(&geojson) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("{name}: cannot read {geojson}: {e}");
            return ExitCode::FAILURE;
        }
    };
    let mut lines = std::io::BufReader::with_capacity(1 << 20, file).lines();

    if stream {
        let spill_dir = spill_dir.unwrap_or_else(|| format!("{out}.spill"));
        // Both temporaries clean themselves up, so a run that dies mid-planet cannot
        // strand tens of gigabytes in a directory nobody is watching.
        let normalized = crate::spill::NormalizedFile::new(
            std::path::Path::new(&spill_dir).join("features.bin"),
        );
        let mut writer = match crate::spill::NormalizedWriter::create(normalized.path()) {
            Ok(w) => w,
            Err(e) => {
                eprintln!("{name}: {e}");
                return ExitCode::FAILURE;
            }
        };
        let mut skipped = 0usize;
        let mut n = 0usize;
        for line in lines.by_ref() {
            let line = match line {
                Ok(l) => l,
                Err(e) => {
                    eprintln!("{name}: cannot read {geojson}: {e}");
                    return ExitCode::FAILURE;
                }
            };
            n += 1;
            let line = line.trim();
            if line.is_empty() {
                continue;
            }
            match crate::geojson::parse_feature(line) {
                Some(f) if accept.matches(&f.geometry) => {
                    if let Err(e) = writer.push(&f.geometry, &f.props) {
                        eprintln!("{name}: {e}");
                        return ExitCode::FAILURE;
                    }
                }
                _ => {
                    skipped += 1;
                    writer.skip();
                    if skipped <= 5 {
                        eprintln!("{name}: skipping line {n} (not a {})", accept.describe());
                    }
                }
            }
        }
        if skipped > 5 {
            eprintln!("{name}: ... and {} more skipped line(s)", skipped - 5);
        }
        let summary = match writer.finish() {
            Ok(s) => s,
            Err(e) => {
                eprintln!("{name}: {e}");
                return ExitCode::FAILURE;
            }
        };
        if summary.count == 0 {
            eprintln!("{name}: no {} features in {geojson}", accept.describe());
            return ExitCode::FAILURE;
        }
        eprintln!(
            "{name}: normalized {} feature(s) into {}",
            summary.count,
            normalized.path().display()
        );

        let count = summary.count;
        let mut src = match NormalizedSource::open(normalized.path(), summary) {
            Ok(s) => s,
            Err(e) => {
                eprintln!("{name}: {e}");
                return ExitCode::FAILURE;
            }
        };
        let report = match build_archive_to(&out, &spill_dir, &opts, &mut src, &limits) {
            Ok(r) => r,
            Err(e) => {
                eprintln!("{name}: {e}");
                return ExitCode::FAILURE;
            }
        };
        // Only if empty: every bucket set and the normalized file remove themselves, so
        // anything left here is a leftover worth seeing rather than deleting.
        drop(src);
        drop(normalized);
        let _ = std::fs::remove_dir(&spill_dir);

        let size = std::fs::metadata(&out).map(|m| m.len()).unwrap_or(0);
        eprintln!(
            "{name}: wrote {out} ({:.1} MiB): {count} feature(s), layer '{layer}', \
             z{min_zoom}-{max_zoom}",
            size as f64 / (1024.0 * 1024.0),
        );
        report_and_exit(name, &report, max_tile_bytes)
    } else {
        let mut features = Vec::new();
        let mut skipped = 0usize;
        let mut n = 0usize;
        for line in lines.by_ref() {
            let line = match line {
                Ok(l) => l,
                Err(e) => {
                    eprintln!("{name}: cannot read {geojson}: {e}");
                    return ExitCode::FAILURE;
                }
            };
            n += 1;
            let line = line.trim();
            if line.is_empty() {
                continue;
            }
            match crate::geojson::parse_feature(line) {
                Some(f) if accept.matches(&f.geometry) => features.push(Feature {
                    geometry: f.geometry,
                    props: f.props,
                }),
                _ => {
                    skipped += 1;
                    if skipped <= 5 {
                        eprintln!("{name}: skipping line {n} (not a {})", accept.describe());
                    }
                }
            }
        }
        if skipped > 5 {
            eprintln!("{name}: ... and {} more skipped line(s)", skipped - 5);
        }
        if features.is_empty() {
            eprintln!("{name}: no {} features in {geojson}", accept.describe());
            return ExitCode::FAILURE;
        }

        let (bytes, report) = match build_archive(&features, &opts) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("{name}: {e}");
                return ExitCode::FAILURE;
            }
        };
        if let Err(e) = std::fs::write(&out, &bytes) {
            eprintln!("{name}: cannot write {out}: {e}");
            return ExitCode::FAILURE;
        }
        eprintln!(
            "{name}: wrote {out} ({:.1} MiB): {} feature(s), layer '{layer}', \
             z{min_zoom}-{max_zoom}",
            bytes.len() as f64 / (1024.0 * 1024.0),
            features.len(),
        );
        report_and_exit(name, &report, max_tile_bytes)
    }
}

/// The per-zoom report and the drop summary, shared by both paths so a `--stream` build
/// tells the operator exactly what a non-streamed one does.
fn report_and_exit(
    name: &str,
    report: &[ZoomStats],
    max_tile_bytes: usize,
) -> std::process::ExitCode {
    let _ = print_report(report, &mut std::io::stderr());
    let dropped: usize = report.iter().map(|s| s.dropped).sum();
    if dropped > 0 {
        eprintln!(
            "{name}: {dropped} feature placement(s) dropped by the {max_tile_bytes}-byte \
             per-tile budget"
        );
    }
    std::process::ExitCode::SUCCESS
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mvt::Tile;
    use crate::pmtiles::Archive;

    fn line(coords: &[(f64, f64)], props: Vec<(&str, Value)>) -> Feature {
        Feature {
            geometry: Geometry::Lines(vec![coords.to_vec()]),
            props: props.into_iter().map(|(k, v)| (k.to_string(), v)).collect(),
        }
    }

    fn square(min_lon: f64, min_lat: f64, size: f64, name: &str) -> Feature {
        Feature {
            geometry: Geometry::Polygons(vec![vec![vec![
                (min_lon, min_lat),
                (min_lon + size, min_lat),
                (min_lon + size, min_lat + size),
                (min_lon, min_lat + size),
                (min_lon, min_lat),
            ]]]),
            props: vec![("name".to_string(), Value::String(name.into()))],
        }
    }

    fn tile_at(archive: &Archive, z: u8, lon: f64, lat: f64) -> Option<Tile> {
        let (fx, fy) = geom::project(lon, lat, z);
        archive
            .tile(z, fx.floor() as u64, fy.floor() as u64)
            .unwrap()
            .map(|b| Tile::decode(&b).unwrap())
    }

    #[test]
    fn a_line_archive_round_trips_through_the_reader() {
        let features = vec![line(
            &[(-122.42, 37.77), (-122.40, 37.79), (-122.38, 37.78)],
            vec![("maxspeed", Value::String("25 mph".into()))],
        )];
        let (bytes, report) = build_archive(&features, &Options::new("maxspeed", 10, 12)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert_eq!((a.header.min_zoom, a.header.max_zoom), (10, 12));
        assert!(String::from_utf8_lossy(&a.metadata).contains("maxspeed"));
        assert_eq!(report.len(), 3);

        for z in 10..=12u8 {
            let tile = tile_at(&a, z, -122.42, 37.77).unwrap_or_else(|| panic!("a tile at z{z}"));
            let l = tile.layer("maxspeed").unwrap();
            assert_eq!(l.features.len(), 1, "z{z}");
            assert_eq!(l.features[0].geom_type, GeomType::LineString);
            assert_eq!(
                l.features[0].get("maxspeed"),
                Some(&Value::String("25 mph".into()))
            );
            // And the geometry decodes as a line.
            assert!(mvt::decode_lines(&l.features[0].geometry).is_some());
        }
    }

    #[test]
    fn a_polygon_archive_keeps_its_winding_and_its_hole() {
        let with_hole = Feature {
            geometry: Geometry::Polygons(vec![vec![
                vec![(-122.5, 37.7), (-122.3, 37.7), (-122.3, 37.9), (-122.5, 37.9), (-122.5, 37.7)],
                vec![(-122.45, 37.75), (-122.35, 37.75), (-122.35, 37.85), (-122.45, 37.85), (-122.45, 37.75)],
            ]]),
            props: vec![("name".to_string(), Value::String("Oakland".into()))],
        };
        let (bytes, _) = build_archive(&[with_hole], &Options::new("admin_city", 9, 10)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        let tile = tile_at(&a, 10, -122.4, 37.8).expect("a tile");
        let f = &tile.layer("admin_city").unwrap().features[0];
        assert_eq!(f.geom_type, GeomType::Polygon);
        let rings = mvt::decode_polygons(&f.geometry).expect("polygon geometry");
        assert_eq!(rings.len(), 1);
        assert_eq!(rings[0].len(), 2, "exterior plus its hole: {rings:?}");
        assert!(mvt::signed_area(&rings[0][0]) > 0, "exterior positive");
        assert!(mvt::signed_area(&rings[0][1]) < 0, "interior negative");
    }

    /// A z6 tile spans `360/64 = 5.625` degrees of longitude, so `-123.75` is
    /// exactly the edge between tile x 9 and tile x 10. Everything below is built
    /// around that so a fixture can be made to straddle a real tile boundary
    /// without having to invert the projection.
    const Z6_TILE_EDGE_LON: f64 = -123.75;

    /// A polygon whose exterior **and** hole both cross [`Z6_TILE_EDGE_LON`], with
    /// enough sub-tolerance jitter along every edge that filtering has something to
    /// remove on both sides of the boundary.
    ///
    /// One z6 extent unit is `5.625 / 4096` degrees, about `0.00137`, so the jitter
    /// below is a third of a unit: under the 1-unit tolerance, and therefore exactly
    /// the detail a coarse zoom is supposed to drop.
    fn straddling_polygon() -> Feature {
        let jitter = |i: usize| (i % 5) as f64 * 0.0005;
        let ring = |west: f64, east: f64, south: f64, north: f64| {
            let mut r: Vec<(f64, f64)> = Vec::new();
            let (w, h) = (east - west, north - south);
            for i in 0..=200 {
                let t = i as f64 / 200.0;
                r.push((west + w * t, south + jitter(i)));
            }
            for i in 0..=20 {
                let t = i as f64 / 20.0;
                r.push((east - jitter(i), south + h * t));
            }
            for i in 0..=200 {
                let t = i as f64 / 200.0;
                r.push((east - w * t, north - jitter(i)));
            }
            for i in 0..=20 {
                let t = i as f64 / 20.0;
                r.push((west + jitter(i), north - h * t));
            }
            r.push(r[0]);
            r
        };
        let e = Z6_TILE_EDGE_LON;
        Feature {
            geometry: Geometry::Polygons(vec![vec![
                ring(e - 2.25, e + 2.75, 36.0, 40.0),
                ring(e - 1.25, e + 1.75, 37.0, 39.0),
            ]]),
            props: vec![("name".to_string(), Value::String("straddler".into()))],
        }
    }

    /// Crossing-number point-in-ring, with a point on the ring counting as inside.
    ///
    /// Integer throughout, and `i64` for the cross products: two `i32` spans
    /// multiply to 62 bits, so containment is decided exactly rather than nearly.
    fn in_ring(ring: &[(i32, i32)], p: (i32, i32)) -> bool {
        let n = ring.len();
        let mut inside = false;
        for i in 0..n {
            let (ax, ay) = ring[i];
            let (bx, by) = ring[(i + 1) % n];
            let cross = (bx as i64 - ax as i64) * (p.1 as i64 - ay as i64)
                - (by as i64 - ay as i64) * (p.0 as i64 - ax as i64);
            if cross == 0
                && p.0 >= ax.min(bx)
                && p.0 <= ax.max(bx)
                && p.1 >= ay.min(by)
                && p.1 <= ay.max(by)
            {
                return true;
            }
            if (ay > p.1) != (by > p.1) {
                let d = by as i64 - ay as i64;
                let u = d * (p.0 as i64 - ax as i64);
                let t = (p.1 as i64 - ay as i64) * (bx as i64 - ax as i64);
                if (d > 0) == (u < t) {
                    inside = !inside;
                }
            }
        }
        inside
    }

    /// The invariant the whole annotate-then-filter design exists to hold: a hole
    /// that crossed a tile boundary is still inside its exterior once the tile has
    /// been built.
    ///
    /// A hole straddling its exterior is not something earcut can express, so the
    /// renderer has to drop it (`library/map/src/main/rust/src/tess/fill.rs`) and the
    /// island or lake fills in solid. Simplifying each clipped ring on its own is
    /// what used to produce them, so this asserts on the built archive rather than
    /// on any one stage.
    #[test]
    fn a_hole_crossing_a_tile_boundary_stays_inside_its_exterior() {
        // maxzoom above the zoom under test, so z6 is genuinely being thinned:
        // `tolerance_for` spares only the deepest zoom.
        let (bytes, _) =
            build_archive(&[straddling_polygon()], &Options::new("land", 6, 7)).unwrap();
        let a = Archive::parse(&bytes).unwrap();

        let mut checked = 0;
        for (id, raw) in a.iter_tiles().unwrap() {
            let (z, _, _) = pmtiles::tile_zxy(id);
            let tile = Tile::decode(&crate::gz::decompress(raw).unwrap()).unwrap();
            let f = &tile.layer("land").unwrap().features[0];
            for rings in mvt::decode_polygons(&f.geometry).expect("polygon geometry") {
                let (exterior, holes) = rings.split_first().expect("an exterior");
                for hole in holes {
                    for &p in hole {
                        assert!(
                            in_ring(exterior, p),
                            "z{z} tile {id}: hole vertex {p:?} escaped its exterior"
                        );
                    }
                    checked += 1;
                }
            }
        }
        assert!(checked > 0, "the fixture produced no holes to check");
    }

    /// Two tiles that overlap must agree, vertex for vertex, on the geometry inside
    /// the overlap. They clip the same annotated source and threshold it the same
    /// way, so the only thing that can make them disagree is thinning a clipped ring
    /// against its own endpoints -- which is what a seam at a tile join looks like.
    ///
    /// The comparison is over the band strictly inside BOTH buffered rects: the
    /// crossings each tile's own clip introduced sit exactly on the edge of that
    /// band and belong to one tile only.
    #[test]
    fn two_adjacent_tiles_agree_on_the_geometry_they_share() {
        let (bytes, _) =
            build_archive(&[straddling_polygon()], &Options::new("land", 6, 7)).unwrap();
        let a = Archive::parse(&bytes).unwrap();

        let extent = DEFAULT_EXTENT;
        let buffer = geom::buffer_for(extent);
        let (edge_tile_x, _) = geom::project(Z6_TILE_EDGE_LON, 0.0, 6);
        let edge = edge_tile_x * extent as f64;
        let east_tx = edge_tile_x as u64;
        // The straddling polygon covers lat 36..40, which at z6 is one tile row.
        let ty = geom::project(0.0, 38.0, 6).1.floor() as u64;

        // Every vertex of the feature, in WORLD coordinates, that lies strictly
        // inside both tiles' buffered rects.
        let shared = |tx: u64| -> Vec<(i64, i64)> {
            let tile = a
                .tile(6, tx, ty)
                .unwrap()
                .map(|b| Tile::decode(&b).unwrap())
                .unwrap_or_else(|| panic!("a tile at z6/{tx}/{ty}"));
            let f = &tile.layer("land").unwrap().features[0];
            let ox = tx as i64 * extent as i64;
            let mut out: Vec<(i64, i64)> = mvt::decode_polygons(&f.geometry)
                .expect("polygon geometry")
                .into_iter()
                .flatten()
                .flatten()
                .map(|(x, y)| (x as i64 + ox, y as i64))
                .filter(|&(x, _)| ((x as f64) - edge).abs() < buffer)
                .collect();
            out.sort_unstable();
            out
        };

        let west = shared(east_tx - 1);
        let east = shared(east_tx);
        assert!(!west.is_empty(), "the fixture reaches neither side of the edge");
        assert_eq!(west, east, "the two tiles disagree inside their overlap");
    }

    #[test]
    fn a_feature_spanning_several_tiles_appears_in_each_of_them() {
        // A line across most of California at z6 lands in more than one tile.
        let features = vec![line(&[(-124.0, 40.0), (-116.0, 33.0)], vec![])];
        let (bytes, report) = build_archive(&features, &Options::new("l", 6, 6)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert!(report[0].tiles > 1, "{:?}", report[0]);
        assert!(a.header.addressed_tiles > 1);
        // Every tile it reaches actually carries geometry.
        for (_, raw) in a.iter_tiles().unwrap() {
            let tile = Tile::decode(&crate::gz::decompress(raw).unwrap()).unwrap();
            let l = tile.layer("l").unwrap();
            assert_eq!(l.features.len(), 1);
            assert!(!l.features[0].geometry.is_empty());
        }
    }

    /// The failure mode of walking segments instead of filling the bounding box is
    /// UNDER-inclusion: a tile the line crosses but which no segment box happens to
    /// name would leave a hole in the middle of a drawn line.
    ///
    /// A long diagonal at z10 is the sharpest test of that. Every column of tiles
    /// between its two ends must hold at least one tile -- a missing column is a
    /// visible break in the line.
    #[test]
    fn a_long_diagonal_leaves_no_gap_in_the_tiles_it_covers() {
        let (west, east) = ((-124.0, 42.0), (-114.0, 33.0));
        let features = vec![line(&[west, east], vec![])];
        let (bytes, report) = build_archive(&features, &Options::new("l", 10, 10)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert!(report[0].tiles > 50, "a real spread of tiles: {:?}", report[0]);

        let (wx, wy) = geom::project(west.0, west.1, 10);
        let (ex, ey) = geom::project(east.0, east.1, 10);
        let (x0, x1) = (wx.floor() as u64, ex.floor() as u64);
        let (ylo, yhi) = (wy.floor() as u64, ey.floor() as u64);

        // Probe every column the line spans; each must carry at least one tile
        // somewhere in the y band the line occupies.
        for x in x0..=x1 {
            let mut found = false;
            for y in ylo..=yhi {
                if a.tile(10, x, y).unwrap().is_some() {
                    found = true;
                    break;
                }
            }
            assert!(found, "column x={x} has no tile: the line has a gap there");
        }

        // And every archived tile really carries geometry, so none is a stray.
        for (_, raw) in a.iter_tiles().unwrap() {
            let tile = Tile::decode(&crate::gz::decompress(raw).unwrap()).unwrap();
            let l = tile.layer("l").unwrap();
            assert!(!l.features[0].geometry.is_empty());
        }
    }

    #[test]
    fn tiles_a_feature_does_not_reach_are_not_created() {
        let features = vec![line(&[(-122.42, 37.77), (-122.41, 37.78)], vec![])];
        let (bytes, _) = build_archive(&features, &Options::new("l", 12, 12)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        // A small line at z12 covers a handful of tiles, not the whole grid.
        assert!(a.header.addressed_tiles < 10, "{}", a.header.addressed_tiles);
        assert!(tile_at(&a, 12, -74.0, 40.7).is_none(), "nothing in New York");
    }

    #[test]
    fn two_runs_are_byte_identical() {
        let features = vec![
            line(&[(-122.42, 37.77), (-122.40, 37.79)], vec![("a", Value::Uint(1))]),
            square(-122.5, 37.7, 0.1, "A"),
        ];
        let opts = Options::new("l", 10, 12);
        let (a, ra) = build_archive(&features, &opts).unwrap();
        let (b, rb) = build_archive(&features, &opts).unwrap();
        assert_eq!(a, b, "determinism is a regression surface here");
        assert_eq!(ra, rb);
    }

    // --- the drop policy ---------------------------------------------------

    #[test]
    fn nothing_is_dropped_when_the_tile_fits() {
        let features: Vec<Feature> = (0..50)
            .map(|i| {
                let d = i as f64 * 0.0001;
                line(&[(-122.42 + d, 37.77), (-122.41 + d, 37.78)], vec![])
            })
            .collect();
        let (_, report) = build_archive(&features, &Options::new("l", 12, 12)).unwrap();
        assert_eq!(report[0].dropped, 0, "{:?}", report[0]);
        assert_eq!(report[0].over_budget, 0);
        assert!(report[0].kept >= 50);
    }

    #[test]
    fn a_tight_budget_drops_the_least_important_features_first() {
        // One long line and many short ones in the same tile, with a budget only a
        // few features wide. Importance is bbox span, so the long one must survive.
        let mut features = vec![line(
            &[(-122.45, 37.75), (-122.35, 37.85)],
            vec![("id", Value::String("long".into()))],
        )];
        for i in 0..200 {
            let d = i as f64 * 0.00005;
            features.push(line(
                &[(-122.40 + d, 37.80), (-122.3999 + d, 37.8001)],
                vec![("id", Value::String(format!("short{i}")))],
            ));
        }
        let mut opts = Options::new("l", 11, 11);
        opts.max_tile_bytes = 400;
        let (bytes, report) = build_archive(&features, &opts).unwrap();
        assert!(report[0].dropped > 0, "the budget must have bitten: {:?}", report[0]);

        let a = Archive::parse(&bytes).unwrap();
        let tile = tile_at(&a, 11, -122.40, 37.80).expect("a tile");
        let ids: Vec<String> = tile
            .layer("l")
            .unwrap()
            .features
            .iter()
            .filter_map(|f| match f.get("id") {
                Some(Value::String(s)) => Some(s.clone()),
                _ => None,
            })
            .collect();
        assert!(ids.contains(&"long".to_string()), "the long line survives: {ids:?}");
    }

    #[test]
    fn a_dropping_tile_stays_within_its_budget() {
        let features: Vec<Feature> = (0..400)
            .map(|i| {
                let d = i as f64 * 0.00002;
                line(
                    &[(-122.40 + d, 37.80), (-122.399 + d, 37.801)],
                    vec![("name", Value::String(format!("street number {i}")))],
                )
            })
            .collect();
        let mut opts = Options::new("l", 11, 11);
        opts.max_tile_bytes = 600;
        let (bytes, report) = build_archive(&features, &opts).unwrap();
        assert!(report[0].dropped > 0);
        assert!(
            report[0].largest_tile_bytes <= 600,
            "largest tile {} exceeds the 600-byte budget",
            report[0].largest_tile_bytes
        );
        // The archive still reads, which is the thing a bad drop breaks.
        let a = Archive::parse(&bytes).unwrap();
        for (_, raw) in a.iter_tiles().unwrap() {
            assert!(Tile::decode(&crate::gz::decompress(raw).unwrap()).is_ok());
        }
    }

    #[test]
    fn one_feature_too_big_for_the_budget_is_kept_and_reported() {
        // An empty tile is a hole in the map; an oversized one is merely slow.
        let long: Vec<(f64, f64)> = (0..4000)
            .map(|i| (-122.42 + (i % 97) as f64 * 0.0001, 37.77 + (i % 89) as f64 * 0.0001))
            .collect();
        let features = vec![line(&long, vec![("name", Value::String("wiggly".into()))])];
        let mut opts = Options::new("l", 14, 14);
        opts.max_tile_bytes = 50;
        let (bytes, report) = build_archive(&features, &opts).unwrap();
        assert!(report[0].over_budget > 0, "{:?}", report[0]);
        assert!(report[0].kept > 0, "the tile must not be empty");
        let a = Archive::parse(&bytes).unwrap();
        assert!(a.header.addressed_tiles > 0);
    }

    #[test]
    fn the_drop_policy_is_deterministic_under_a_tight_budget() {
        let features: Vec<Feature> = (0..300)
            .map(|i| {
                let d = i as f64 * 0.00003;
                line(
                    &[(-122.40 + d, 37.80), (-122.3995 + d, 37.8005)],
                    vec![("name", Value::String(format!("f{i}")))],
                )
            })
            .collect();
        let mut opts = Options::new("l", 11, 11);
        opts.max_tile_bytes = 700;
        let (a, ra) = build_archive(&features, &opts).unwrap();
        let (b, rb) = build_archive(&features, &opts).unwrap();
        assert_eq!(a, b);
        assert_eq!(ra, rb);
        assert!(ra[0].dropped > 0, "the test is only meaningful if it dropped");
    }

    #[test]
    fn simplification_thins_the_shallow_zooms_and_spares_the_deepest() {
        // A wiggly line: at maxzoom the tolerance is zero, so every vertex the clip
        // left must still be there; below it, fewer.
        let coords: Vec<(f64, f64)> = (0..300)
            .map(|i| (-122.45 + i as f64 * 0.0002, 37.80 + (i % 2) as f64 * 0.00005))
            .collect();
        let (bytes, _) = build_archive(&[line(&coords, vec![])], &Options::new("l", 8, 12)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        let vertices_at = |z: u8| -> usize {
            let mut n = 0;
            for (id, raw) in a.iter_tiles().unwrap() {
                if pmtiles::tile_zxy(id).0 != z {
                    continue;
                }
                let tile = Tile::decode(&crate::gz::decompress(raw).unwrap()).unwrap();
                for f in &tile.layer("l").unwrap().features {
                    n += mvt::decode_lines(&f.geometry)
                        .map(|ls| ls.iter().map(|l| l.len()).sum::<usize>())
                        .unwrap_or(0);
                }
            }
            n
        };
        let deep = vertices_at(12);
        let shallow = vertices_at(8);
        assert!(deep > 0 && shallow > 0);
        assert!(
            shallow < deep,
            "z8 kept {shallow} vertices and z12 kept {deep}; simplification did nothing"
        );
    }

    #[test]
    fn features_that_clip_away_entirely_are_not_counted_as_drops() {
        // Two features far apart: each tile sees one of them, and the other's
        // absence is a clip, not a budget decision.
        let features = vec![
            line(&[(-122.42, 37.77), (-122.41, 37.78)], vec![]),
            line(&[(-74.01, 40.71), (-74.00, 40.72)], vec![]),
        ];
        let (_, report) = build_archive(&features, &Options::new("l", 12, 12)).unwrap();
        assert_eq!(report[0].dropped, 0, "{:?}", report[0]);
        assert_eq!(report[0].kept, report[0].placed);
    }

    #[test]
    fn an_empty_input_produces_an_empty_but_valid_archive() {
        let (bytes, report) = build_archive(&[], &Options::new("l", 10, 12)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert_eq!(a.header.addressed_tiles, 0);
        assert!(report.iter().all(|s| s.tiles == 0));
    }

    #[test]
    fn bad_options_are_refused() {
        let features = vec![line(&[(0.0, 0.0), (1.0, 1.0)], vec![])];
        let mut opts = Options::new("l", 12, 10);
        assert!(build_archive(&features, &opts).is_err(), "minzoom above maxzoom");
        opts = Options::new("l", 10, 12);
        opts.extent = 0;
        assert!(build_archive(&features, &opts).is_err(), "extent 0");
    }

    #[test]
    fn the_report_prints_one_row_per_zoom() {
        let (_, report) = build_archive(
            &[line(&[(-122.42, 37.77), (-122.41, 37.78)], vec![])],
            &Options::new("l", 10, 11),
        )
        .unwrap();
        let mut out = Vec::new();
        print_report(&report, &mut out).unwrap();
        let text = String::from_utf8(out).unwrap();
        assert!(text.contains("z10"), "{text}");
        assert!(text.contains("z11"), "{text}");
    }

    // --- the streaming producer --------------------------------------------
    //
    // `build_archive` is covered by every test above, so pinning the streaming producer
    // against it byte for byte inherits all of it -- the drop policy, the importance
    // order, the simplification, the header, the deduplication, the run coalescing and
    // the directory split -- without restating any of it. This is the same contract
    // `tiling.rs`'s `the_streaming_join_is_byte_identical_to_the_in_memory_one` holds
    // the merge to.

    struct Scratch(std::path::PathBuf);

    impl Scratch {
        fn new(name: &str) -> Scratch {
            let d = std::env::temp_dir().join(format!(
                "tb_pyr_{}_{name}_{:?}",
                std::process::id(),
                std::thread::current().id()
            ));
            let _ = std::fs::remove_dir_all(&d);
            std::fs::create_dir_all(&d).unwrap();
            Scratch(d)
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    /// Drive both producers from one `Vec<Feature>` and return the streamed bytes.
    ///
    /// Asserts the archive bytes AND the `ZoomStats` are equal, and that nothing was
    /// left behind in the scratch directory -- a stranded per-zoom bucket set would make
    /// peak disk the SUM of the zooms rather than the largest one.
    fn assert_identical(
        name: &str,
        features: &[Feature],
        opts: &Options,
        limits: &StreamLimits,
    ) -> Vec<u8> {
        let scratch = Scratch::new(name);
        let (want_bytes, want_report) = build_archive(features, opts).unwrap();

        let out = scratch.0.join("streamed.pmtiles");
        let mut src = SliceSource::new(features);
        let got_report = build_archive_to(&out, &scratch.0, opts, &mut src, limits).unwrap();
        let got_bytes = std::fs::read(&out).unwrap();

        assert_eq!(got_report, want_report, "{name}: the per-zoom reports differ");
        assert_eq!(
            got_bytes.len(),
            want_bytes.len(),
            "{name}: streamed {} bytes, in-memory {}",
            got_bytes.len(),
            want_bytes.len()
        );
        assert!(
            got_bytes == want_bytes,
            "{name}: the two archives differ byte for byte"
        );

        let leftovers: Vec<String> = std::fs::read_dir(&scratch.0)
            .unwrap()
            .filter_map(|e| e.ok())
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| n != "streamed.pmtiles")
            .collect();
        assert!(
            leftovers.is_empty(),
            "{name}: the spill was left behind: {leftovers:?}"
        );
        assert!(
            !out.with_extension("pmtiles.tiledata").exists(),
            "{name}: the writer's scratch was left behind"
        );
        got_bytes
    }

    /// A fixture covering every branch the two producers could disagree on: a line
    /// spanning many tiles, a polygon with a hole, and two features with identical
    /// geometry so the `seq` tie-break in the importance order has to decide between
    /// them.
    fn mixed_fixture() -> Vec<Feature> {
        let mut features = vec![
            // Long enough at z11-12 to land in many tiles, which is what makes the
            // bucket partition non-trivial.
            line(&[(-124.0, 42.0), (-114.0, 33.0)], vec![("id", Value::Uint(0))]),
            square(-122.5, 37.7, 0.4, "A"),
        ];
        // A polygon with a hole.
        features.push(Feature {
            geometry: Geometry::Polygons(vec![vec![
                vec![
                    (-122.5, 37.7),
                    (-122.3, 37.7),
                    (-122.3, 37.9),
                    (-122.5, 37.9),
                    (-122.5, 37.7),
                ],
                vec![
                    (-122.45, 37.75),
                    (-122.35, 37.75),
                    (-122.35, 37.85),
                    (-122.45, 37.85),
                    (-122.45, 37.75),
                ],
            ]]),
            props: vec![("name".to_string(), Value::String("holey".into()))],
        });
        // Two identical geometries: tied on `extent_of`, so only `seq` orders them.
        for k in 0..2 {
            features.push(line(
                &[(-122.42, 37.77), (-122.40, 37.79)],
                vec![("tied", Value::Uint(k))],
            ));
        }
        features
    }

    #[test]
    fn the_streaming_producer_is_byte_identical_to_the_in_memory_one() {
        let features = mixed_fixture();
        let opts = Options::new("l", 10, 12);
        assert_identical("mixed", &features, &opts, &StreamLimits::default());
    }

    /// The drop policy is where the two could diverge without the output looking wrong,
    /// so it gets a budget tight enough to bite.
    #[test]
    fn the_streaming_producer_matches_under_a_tight_tile_budget() {
        let mut features = vec![line(
            &[(-122.45, 37.75), (-122.35, 37.85)],
            vec![("id", Value::String("long".into()))],
        )];
        for i in 0..200 {
            let d = i as f64 * 0.00005;
            features.push(line(
                &[(-122.40 + d, 37.80), (-122.3999 + d, 37.8001)],
                vec![("id", Value::String(format!("short{i}")))],
            ));
        }
        let mut opts = Options::new("l", 11, 11);
        opts.max_tile_bytes = 400;
        let (_, report) = build_archive(&features, &opts).unwrap();
        assert!(report[0].dropped > 0, "the fixture must drop: {:?}", report[0]);
        assert_identical("tight", &features, &opts, &StreamLimits::default());
    }

    /// The `over_budget` path: one feature that does not fit is kept anyway, and both
    /// producers must keep the same one and report it the same way.
    #[test]
    fn the_streaming_producer_matches_when_a_single_feature_is_over_budget() {
        let long: Vec<(f64, f64)> = (0..4000)
            .map(|i| (-122.42 + (i % 97) as f64 * 0.0001, 37.77 + (i % 89) as f64 * 0.0001))
            .collect();
        let features = vec![line(&long, vec![("name", Value::String("wiggly".into()))])];
        let mut opts = Options::new("l", 14, 14);
        opts.max_tile_bytes = 50;
        let (_, report) = build_archive(&features, &opts).unwrap();
        assert!(report[0].over_budget > 0, "the fixture must be over budget");
        assert_identical("overbudget", &features, &opts, &StreamLimits::default());
    }

    /// Re-partitioning a dense bucket is the expected path at planet scale, not an
    /// exotic one, so it gets its own byte-identity check rather than being trusted.
    ///
    /// Four buckets per level and a budget far below one zoom's spill forces recursion
    /// all the way down to single-tile ranges; the archive must not move by a byte.
    #[test]
    fn re_partitioning_does_not_perturb_a_byte() {
        let features = mixed_fixture();
        let opts = Options::new("l", 10, 12);
        let limits = StreamLimits {
            buckets: 4,
            // Well under one zoom's total spill and comfortably above one tile's, which
            // is what makes this a recursion test rather than an error test.
            bucket_budget_bytes: 2_000,
            max_repartition_depth: DEFAULT_MAX_REPARTITION_DEPTH,
        };
        assert_identical("recursion", &features, &opts, &limits);
    }

    /// The fixture whose drop policy actually bites: one long feature plus 200 short
    /// ones, at a budget that cannot hold them all.
    fn tight_fixture() -> Vec<Feature> {
        let mut features = vec![line(
            &[(-122.45, 37.75), (-122.35, 37.85)],
            vec![("id", Value::String("long".into()))],
        )];
        for i in 0..200 {
            let d = i as f64 * 0.00005;
            features.push(line(
                &[(-122.40 + d, 37.80), (-122.3999 + d, 37.8001)],
                vec![("id", Value::String(format!("short{i}")))],
            ));
        }
        features
    }

    /// The gate for threading the tilers: the thread count must change only how fast
    /// they run.
    ///
    /// Both producers, at 1, 2, 3 and 32 threads, against the serial result. Three is
    /// there on purpose — counts that divide neither the bucket count (256, or 4 in the
    /// recursion case) nor the batch length are the ones that catch an off-by-one in a
    /// chunked fold, and a count above the core count catches an ordering bug that a
    /// saturated pool would hide.
    ///
    /// Run over three cases, because they are three different code paths: the flat
    /// partition, the recursing one, and a budget tight enough that the drop policy
    /// runs — the last being the only one where a perturbed candidate order changes
    /// which features survive rather than merely where they land.
    #[test]
    fn the_thread_count_changes_no_bytes() {
        let recursing = StreamLimits {
            buckets: 4,
            bucket_budget_bytes: 2_000,
            max_repartition_depth: DEFAULT_MAX_REPARTITION_DEPTH,
        };
        let mut tight_opts = Options::new("l", 11, 11);
        tight_opts.max_tile_bytes = 400;

        let cases: Vec<(&str, Vec<Feature>, Options, StreamLimits)> = vec![
            (
                "flat",
                mixed_fixture(),
                Options::new("l", 10, 12),
                StreamLimits::default(),
            ),
            (
                "recursing",
                mixed_fixture(),
                Options::new("l", 10, 12),
                recursing,
            ),
            (
                "tight",
                tight_fixture(),
                tight_opts,
                StreamLimits::default(),
            ),
        ];

        for (tag, features, opts, limits) in cases {
            let run = |n: usize| {
                par::set_threads(n);
                let scratch = Scratch::new(&format!("threads_{tag}_{n}"));
                let (mem, mem_report) = build_archive(&features, &opts).unwrap();
                let out = scratch.0.join("streamed.pmtiles");
                let mut src = SliceSource::new(&features);
                let stream_report =
                    build_archive_to(&out, &scratch.0, &opts, &mut src, &limits).unwrap();
                (mem, mem_report, std::fs::read(&out).unwrap(), stream_report)
            };

            let (base_mem, base_mem_report, base_stream, base_stream_report) = run(1);
            // The property the whole crate rests on, restated at one thread so a
            // failure below cannot be blamed on the producers disagreeing.
            assert!(base_mem == base_stream, "{tag}: the producers disagree serially");

            for n in [2, 3, 32] {
                let (mem, mem_report, stream, stream_report) = run(n);
                assert_eq!(
                    mem_report, base_mem_report,
                    "{tag}: {n} threads changed the in-memory report"
                );
                assert_eq!(
                    stream_report, base_stream_report,
                    "{tag}: {n} threads changed the streamed report"
                );
                assert!(
                    mem == base_mem,
                    "{tag}: {n} threads perturbed the in-memory archive"
                );
                assert!(
                    stream == base_stream,
                    "{tag}: {n} threads perturbed the streamed archive"
                );
            }
        }
        par::clear_threads();
    }

    /// An unsplittable group over budget errors, naming the tile, rather than being
    /// loaded and killed by the OOM reaper. Capping candidates per tile instead would
    /// change which features survive, which is an output change.
    #[test]
    fn an_unsplittable_over_budget_tile_group_is_named_in_the_error() {
        let scratch = Scratch::new("unsplittable");
        let long: Vec<(f64, f64)> = (0..4000)
            .map(|i| (-122.42 + (i % 97) as f64 * 0.0001, 37.77 + (i % 89) as f64 * 0.0001))
            .collect();
        let features = vec![line(&long, vec![("name", Value::String("wiggly".into()))])];
        let opts = Options::new("l", 14, 14);
        let limits = StreamLimits {
            buckets: 4,
            bucket_budget_bytes: 64,
            max_repartition_depth: DEFAULT_MAX_REPARTITION_DEPTH,
        };
        let mut src = SliceSource::new(&features);
        let e = build_archive_to(
            scratch.0.join("out.pmtiles"),
            &scratch.0,
            &opts,
            &mut src,
            &limits,
        )
        .expect_err("an unsplittable over-budget group must error");
        let msg = e.to_string();
        assert!(msg.contains("z14/"), "the error must name the tile: {msg}");
        assert!(msg.contains("spill record(s)"), "and the record count: {msg}");
        assert!(
            msg.contains("--bucket-budget-bytes"),
            "and what to do about it: {msg}"
        );
    }

    /// An empty input is what catches the `Builder`/`StreamBuilder` default-bounds
    /// divergence: nothing overwrites the latitude fields, and they differ by three
    /// units between the two writers.
    #[test]
    fn an_empty_input_streams_to_an_empty_but_valid_archive() {
        let opts = Options::new("l", 10, 12);
        let bytes = assert_identical("empty", &[], &opts, &StreamLimits::default());
        let a = Archive::parse(&bytes).unwrap();
        assert_eq!(a.header.addressed_tiles, 0);
        assert_eq!(a.header.min_lat_e7, -850_511_290, "Builder's default, not StreamBuilder's");
        assert_eq!(a.header.max_lat_e7, 850_511_290);
    }

    #[test]
    fn two_streaming_runs_are_byte_identical() {
        let features = mixed_fixture();
        let opts = Options::new("l", 10, 12);
        let a = assert_identical("det_a", &features, &opts, &StreamLimits::default());
        let b = assert_identical("det_b", &features, &opts, &StreamLimits::default());
        assert_eq!(a, b, "determinism is a regression surface here");
    }

    #[test]
    fn the_streaming_producer_refuses_the_same_bad_options() {
        let scratch = Scratch::new("badopts");
        let features = vec![line(&[(0.0, 0.0), (1.0, 1.0)], vec![])];
        let limits = StreamLimits::default();
        for opts in [
            Options::new("l", 12, 10),
            Options { extent: 0, ..Options::new("l", 10, 12) },
        ] {
            let mut src = SliceSource::new(&features);
            assert!(
                build_archive_to(
                    scratch.0.join("out.pmtiles"),
                    &scratch.0,
                    &opts,
                    &mut src,
                    &limits
                )
                .is_err(),
                "bad options must be refused by both producers"
            );
        }
    }

    /// `--buckets 1` cannot narrow a range, so an over-budget bucket must fail fast
    /// rather than copy itself to disk once per level up to the depth cap.
    #[test]
    fn a_single_bucket_partition_fails_fast_instead_of_recursing() {
        let scratch = Scratch::new("onebucket");
        let features = mixed_fixture();
        let opts = Options::new("l", 10, 10);
        let limits = StreamLimits {
            buckets: 1,
            bucket_budget_bytes: 1,
            max_repartition_depth: DEFAULT_MAX_REPARTITION_DEPTH,
        };
        let mut src = SliceSource::new(&features);
        let e = build_archive_to(
            scratch.0.join("out.pmtiles"),
            &scratch.0,
            &opts,
            &mut src,
            &limits,
        )
        .expect_err("a one-bucket partition cannot split, so it must error");
        assert!(
            e.to_string().contains("cannot be split further"),
            "the error must say why: {e}"
        );
    }

    /// `zoom_base(max_zoom + 1)` overflows above z30, which only the streaming path
    /// computes. A message beats a panic.
    #[test]
    fn a_zoom_beyond_the_tile_id_range_is_refused() {
        let scratch = Scratch::new("deepzoom");
        let features = vec![line(&[(0.0, 0.0), (1.0, 1.0)], vec![])];
        // Refused before any zoom runs, so this costs nothing: a z30 bucket pass over a
        // one-degree line would touch millions of tiles.
        let opts = Options::new("l", 31, 31);
        let mut src = SliceSource::new(&features);
        let e = build_archive_to(
            scratch.0.join("out.pmtiles"),
            &scratch.0,
            &opts,
            &mut src,
            &StreamLimits::default(),
        )
        .expect_err("z31 must be refused");
        assert!(e.to_string().contains("above 30"), "{e}");
    }

    /// The same normalized file forced through the sequential path, so a test can pin the
    /// chunked reader against it. Everything delegates; `chunks` is left at its `None`
    /// default, which is the whole point.
    struct SerialOnly(NormalizedSource);

    impl FeatureSource for SerialOnly {
        fn rewind(&mut self) -> Result<()> {
            self.0.rewind()
        }
        fn next(&mut self) -> Result<Option<Feature>> {
            self.0.next()
        }
        fn len(&self) -> u64 {
            self.0.len()
        }
        fn bounds(&self) -> Option<geom::Rect> {
            self.0.bounds()
        }
        fn geom_kind(&self) -> Option<GeomKind> {
            self.0.geom_kind()
        }
    }

    /// The chunked reader is the production read path, so it must produce exactly what the
    /// sequential one does, at every thread count.
    ///
    /// `tight_fixture` is 201 features against `NORM_CHUNK_FEATURES` of 64, so the index
    /// spans four chunks and ends mid-chunk — the shape that catches an off-by-one in the
    /// chunk-to-`seq` mapping. The tight budget matters more: it makes the drop policy
    /// run, so a perturbed `seq` changes which features SURVIVE rather than only which
    /// bucket they land in. Without it a wrong `seq` would still sort out and the test
    /// would pass while the mapping was broken.
    ///
    /// Checked by mutation: dropping the chunk base (`first = 0`, so `seq` collides
    /// across chunks) fails this. Adding a constant to it does NOT, and should not —
    /// `seq` is only ever a tie-break, so a uniform shift preserves every comparison.
    #[test]
    fn the_chunked_reader_matches_the_sequential_one() {
        let features = tight_fixture();
        let mut opts = Options::new("l", 11, 11);
        opts.max_tile_bytes = 400;
        let limits = StreamLimits::default();

        let held = Scratch::new("chunked_src");
        let norm = held.0.join("features.bin");
        let mut w = crate::spill::NormalizedWriter::create(&norm).unwrap();
        for f in &features {
            w.push(&f.geometry, &f.props).unwrap();
        }
        let summary = w.finish().unwrap();

        // The fixture has to span several chunks and end mid-chunk, or this degenerates
        // into the single-chunk case and asserts nothing about the mapping.
        assert!(
            summary.chunk_count() > 1,
            "fixture must span several chunks, got {}",
            summary.chunk_count()
        );
        assert_ne!(
            summary.count % crate::spill::NORM_CHUNK_FEATURES,
            0,
            "fixture must end mid-chunk to exercise the partial tail"
        );

        let serial = {
            let scratch = Scratch::new("chunked_serial");
            let out = scratch.0.join("out.pmtiles");
            let mut src = SerialOnly(NormalizedSource::open(&norm, summary.clone()).unwrap());
            assert!(src.chunks().is_none(), "this must take the serial path");
            let report = build_archive_to(&out, &scratch.0, &opts, &mut src, &limits).unwrap();
            (std::fs::read(&out).unwrap(), report)
        };

        for n in [1, 2, 3, 32] {
            par::set_threads(n);
            let scratch = Scratch::new(&format!("chunked_{n}"));
            let out = scratch.0.join("out.pmtiles");
            let mut src = NormalizedSource::open(&norm, summary.clone()).unwrap();
            assert!(src.chunks().is_some(), "this must take the chunked path");
            let report = build_archive_to(&out, &scratch.0, &opts, &mut src, &limits).unwrap();
            assert_eq!(
                report, serial.1,
                "{n} threads: the chunked reader changed the report"
            );
            assert!(
                std::fs::read(&out).unwrap() == serial.0,
                "{n} threads: the chunked reader perturbed the archive"
            );
        }
        par::clear_threads();
    }

    /// The production source is a file, and it must yield exactly what the slice-backed
    /// one does -- otherwise the byte-identity tests above are testing a path nothing
    /// ships.
    #[test]
    fn the_normalized_source_produces_the_same_archive_as_the_slice_one() {
        let scratch = Scratch::new("normsrc");
        let features = mixed_fixture();
        let opts = Options::new("l", 10, 12);
        let limits = StreamLimits::default();

        let (want, want_report) = build_archive(&features, &opts).unwrap();

        let norm = scratch.0.join("features.bin");
        let mut w = crate::spill::NormalizedWriter::create(&norm).unwrap();
        for f in &features {
            w.push(&f.geometry, &f.props).unwrap();
        }
        let summary = w.finish().unwrap();
        assert_eq!(summary.count, features.len() as u64);

        let out = scratch.0.join("out.pmtiles");
        let mut src = NormalizedSource::open(&norm, summary).unwrap();
        let report = build_archive_to(&out, &scratch.0, &opts, &mut src, &limits).unwrap();
        assert_eq!(report, want_report);
        assert!(
            std::fs::read(&out).unwrap() == want,
            "the file-backed source must produce the same bytes"
        );
    }
}
