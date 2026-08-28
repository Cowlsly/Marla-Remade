//! Stages B, D and E: features in, one `.mamaps` archive out.
//!
//! Zoom by zoom, and within a zoom tile by tile in ascending tile-id order, which is exactly what
//! [`StreamWriter`] requires. That ordering is free rather than arranged: `pmtiles::tile_id` is
//! zoom-major, so every id at z*n* precedes every id at z*n+1*, and sorting within a zoom finishes
//! it.
//!
//! # What is reused
//!
//! All of the geometry, from `tile_build`:
//!
//! * [`geom::project_geometry`] to web-mercator tile units, carrying per-vertex significance.
//! * [`simplify::annotate`] then [`simplify::filter`], which is significance-first simplification:
//!   a vertex's importance is computed **once** on the whole geometry and only then filtered per
//!   zoom, so the same vertex survives or does not regardless of which tile it lands in. Doing it
//!   the other way round is what lets a shared edge simplify differently on each side and open a
//!   seam.
//! * [`geom::tiles_touched`], whose per-segment bisection is not optional: a transcontinental line
//!   touches 16 632 tiles against 34 535 986 for its bounding box.
//! * [`clip`]'s Liang-Barsky for lines and Sutherland-Hodgman for rings, against a tile rect with a
//!   buffer so a stroke at the edge has geometry to join to.
//!
//! # Memory
//!
//! One zoom's `tile -> features` map at a time, held in memory. The plan's design spills that to
//! disk through `tile_build::spill` and will need to for a planet build; a California
//! water-and-buildings run is a few hundred megabytes, so this does not yet. The shape is the same
//! either way — the writer already streams — so the change is local when it is needed.
//!
//! # Parallelism, and why the bytes do not move
//!
//! Tiling was 301 s of a 355 s California build, on one of 64 cores, and z14 alone is 68% of it
//! (180.7 M of 263 M output points) — so the split that matters is *within* a zoom, not across
//! them. A zoom is therefore a map/reduce:
//!
//! 1. **Map.** One reader thread cuts the feature stream into chunks of roughly
//!    [`CHUNK_VERTICES`] input vertices and hands them over a bounded channel to a pool of workers.
//!    Each worker projects, simplifies, bisects and clips its own chunk into a private [`Chunk`]
//!    map, touching nothing shared.
//! 2. **Reduce.** The chunks are ordered by the index they were *read* at — never by the order they
//!    finished in — and k-way merged on the tile id.
//! 3. **Encode.** Stage C and body serialisation are pure functions of one tile, so a batch of
//!    merged tiles is encoded in parallel and appended in tile order on one thread.
//!
//! The reason this is byte-identical is stronger than "the merge is sorted". Concatenating a
//! partition of a sequence in partition order reproduces the sequence, so a tile layer's features
//! land in exactly the order the store yielded them — which makes the archive independent not only
//! of the thread count but of *where the chunk boundaries fall*. Both halves are asserted, in
//! `the_archive_is_identical_at_every_thread_count` and
//! `the_archive_is_identical_however_the_features_are_chunked`.
//!
//! What would break it, and what this module is shaped to avoid:
//!
//! * A `HashMap` anywhere a tile, a layer or a feature is emitted from. Both maps here are
//!   `BTreeMap`s, and the only hash map in the path is the writer's dedup bucket table, which is
//!   probed and never walked.
//! * A reduce that folds partial results as they *complete* — a `reduce`, a `fold`, a channel of
//!   finished work. A layer's feature order would then follow the scheduler, and the damage would
//!   be a different feature *ordering* within a tile rather than a different picture: every pixel
//!   would still be right and every byte would be wrong.
//! * Emitting from the workers. Tile ids must ascend for [`StreamWriter`], so the append stays on
//!   one thread and only the pure work fans out.
//!
//! Memory is what this costs. All of a zoom's chunks are live at the end of the map phase, holding
//! the same coordinates the sequential map held plus one `BodyLayer` header per `(chunk, tile,
//! layer)` for each tile more than one chunk touches; the merge then frees each chunk's nodes as it
//! consumes them. In flight on top of that are at most `2 * threads` chunks of raw features. That
//! duplication is why [`CHUNK_VERTICES`] is as large as it is: smaller chunks balance the pool
//! better and duplicate more headers.

use std::cmp::Reverse;
use std::collections::{btree_map, BTreeMap, BinaryHeap};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Mutex;

use rayon::prelude::*;
use tile_build::geom::{self, Geometry, IntGeometry, SigPt};
use tile_build::par;
use tile_build::{clip, simplify};
use tilecodec::mamaps::body::{
    Body, Feature as BodyFeature, Layer as BodyLayer, Part, GEOM_LINE, GEOM_POLYGON, WINDING_HOLE,
    WINDING_OUTER,
};
use tilecodec::mamaps::write::{Options, StreamWriter};
use tilecodec::pmtiles::tile_id;
use tilecodec::proto::{err, Result};

use crate::extract::Feature;
use crate::store::Store;

/// The tile grid, matching MVT's so nothing downstream rescales.
pub const EXTENT: u32 = 4096;

/// How aggressively to simplify, as a multiple of `tile_build`'s per-zoom tolerance.
pub const DEFAULT_SIMPLIFICATION: f64 = 1.0;

/// Input vertices per chunk handed to one worker.
///
/// By **vertex** rather than by feature, because feature cost spans four orders of magnitude in the
/// same file: a building is five points and a state boundary is hundreds of thousands, so a chunk of
/// *n* features is not a unit of work at all. A feature bigger than this on its own becomes its own
/// chunk, which is the right answer for a coastline.
///
/// The size trades two costs against each other. Smaller chunks balance the pool better and give the
/// reader less to get through before a worker can start; larger chunks duplicate fewer `BodyLayer`
/// headers across chunks that touch the same tile, which is the whole of this design's memory
/// overhead.
///
/// **Raised from 64 Ki after tracing where the cost actually is.** An RSS trace of a California build
/// put stage A at 15 s under 1.6 GB and the real peak at t+256 s — 2.45 GB, deep in z13/z14 and
/// plateaued across the whole of it. That plateau is this duplication. Raising the chunk to 512 Ki
/// took the build from 325 s to 181 s and the peak from 2.45 GB to 2.19 GB: at 64 Ki there were ~3 300
/// chunks per zoom, and the allocation and merging of that many per-chunk maps dominated both `map`
/// and `encode`. 512 Ki still leaves hundreds of chunks at California z14, which is several per core
/// on a 64-core box, so the pool stays fed.
///
/// 2 Mi was measured too and was not better — 182 s and 2.21 GB, inside the noise — so this is close
/// to the floor of what the constant alone can buy.
const CHUNK_VERTICES: usize = 512 * 1024;

/// Worker stack, matching [`par`]'s pool and what the toolchain gives `main` on Windows.
///
/// The geometry called below is iterative, so 2 MiB would very likely do — but "very likely" is not
/// what you want from a stack, and getting it wrong aborts partway through a build instead of
/// returning an error.
const WORKER_STACK: usize = 8 * 1024 * 1024;

/// Set only by tests, which build the same archive at several chunk sizes to show the output does
/// not depend on where the boundaries fall. Zero means [`CHUNK_VERTICES`].
static CHUNK_OVERRIDE: AtomicUsize = AtomicUsize::new(0);

fn chunk_vertices() -> usize {
    match CHUNK_OVERRIDE.load(Ordering::Relaxed) {
        0 => CHUNK_VERTICES,
        n => n,
    }
}

#[cfg(test)]
fn set_chunk_vertices(n: usize) {
    CHUNK_OVERRIDE.store(n, Ordering::Relaxed);
}

/// Adopt `RAYON_NUM_THREADS` when nothing else has set the thread budget.
///
/// [`par::threads`] answers to `--threads` and then `MAPS_THREADS`, spelled that way so that one
/// export governs a whole multi-tool build, and it deliberately ignores rayon's own variable because
/// it builds its own pool. But `RAYON_NUM_THREADS` is the knob anyone reaches for to pin a Rust
/// program to one thread — including whoever is checking that this archive is identical at one
/// thread and at sixty-four — and ignoring it would make that check assert nothing. So it is
/// honoured, below `MAPS_THREADS` rather than above it, once per process.
fn adopt_thread_budget() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        if std::env::var_os("MAPS_THREADS").is_some() {
            return;
        }
        let wanted = std::env::var("RAYON_NUM_THREADS")
            .ok()
            .and_then(|raw| raw.trim().parse::<usize>().ok())
            .filter(|n| *n > 0);
        if let Some(n) = wanted {
            par::set_threads(n);
        }
    });
}

/// Per-zoom counts for the build report.
#[derive(Debug, Default, Clone, Copy)]
pub struct ZoomStats {
    pub zoom: u8,
    pub tiles: u64,
    pub features: u64,
    pub points: u64,
    /// Features dropped because simplification left nothing, or the clip did.
    pub dropped: u64,
    pub bytes: u64,
    /// What stage C had to correct.
    pub rings: crate::rings::Stats,
    /// Milliseconds in each phase of this zoom: map, merge, encode, append.
    ///
    /// Kept because guessing which one dominates has already cost two wrong optimisations. The map
    /// and encode phases run on the pool and the other two are serial, so the split is what says
    /// whether more cores would help at all or whether Amdahl has already won.
    pub map_ms: u64,
    pub merge_ms: u64,
    pub encode_ms: u64,
    pub append_ms: u64,
}

pub struct Settings {
    pub min_zoom: u8,
    pub max_zoom: u8,
    pub simplification: f64,
    pub build_id: u64,
}

/// One chunk's share of a zoom, keyed on `(tile id, layer id)`.
///
/// Keyed on the **tile id**, not on `(x, y)`. Those are different orders: `tile_id` walks a Hilbert
/// curve, so row-major `(x, y)` ascends through it out of sequence and the writer rejects the
/// archive. Cheap to get wrong and caught only by an ordering check, which is why the writer has
/// one.
///
/// Flat rather than `tile -> layer -> layer`. A nested map pays a whole inner `BTreeMap` node —
/// eleven `BodyLayer` slots, most of a kilobyte — for every tile, and nearly every tile carries one
/// or two layers; with a chunk map per chunk that overhead is multiplied by the chunk count.
/// Flattening puts eleven *entries* in a node instead, and changes no order: `(tile, layer)`
/// ascending is tile-major with layers in id order inside it, which is what the nested form yielded
/// and what the writer needs.
type Chunk = BTreeMap<(u64, u8), BodyLayer>;

/// What one chunk contributed to a zoom's counters. Only ever summed, so the order they are summed
/// in cannot change the answer.
#[derive(Debug, Default, Clone, Copy)]
struct Tally {
    features: u64,
    points: u64,
    dropped: u64,
}

impl Tally {
    fn add(&mut self, other: Tally) {
        self.features += other.features;
        self.points += other.points;
        self.dropped += other.dropped;
    }
}

/// Tile every feature and write the archive.
/// Tile every feature in `store` into a `.mamaps` archive.
///
/// The store is read **once per zoom**, in order, rather than held in memory for all of them. Fifteen
/// sequential passes over a file cost seconds on any modern disk; holding the features cost 4.9 GB of
/// a measured 10.03 GB California peak.
pub fn build(store: &Store, settings: &Settings) -> Result<(Vec<u8>, Vec<ZoomStats>)> {
    adopt_thread_budget();
    let bbox = store.bbox();
    let mut writer = StreamWriter::new(Options {
        min_zoom: settings.min_zoom,
        max_zoom: settings.max_zoom,
        build_id: settings.build_id,
        compress: true,
        // Stage C runs on every tile below, so the claim is true. It is what lets the renderer
        // skip its repair pass — and the renderer still keeps that pass, gated, because a claim is
        // only as good as the generator making it.
        rings_validated: true,
        min_lon_e7: bbox.0,
        min_lat_e7: bbox.1,
        max_lon_e7: bbox.2,
        max_lat_e7: bbox.3,
        ..Options::default()
    })?;

    let mut per_zoom = Vec::new();
    for z in settings.min_zoom..=settings.max_zoom {
        let mut stats = ZoomStats { zoom: z, ..ZoomStats::default() };
        let tolerance = simplify::tolerance_for(z, settings.max_zoom, settings.simplification);
        let buffer = geom::buffer_for(EXTENT);

        let mapped = std::time::Instant::now();
        let (chunks, tally) = map_zoom(store, z, tolerance, buffer)?;
        stats.map_ms = mapped.elapsed().as_millis() as u64;
        stats.features = tally.features;
        stats.points = tally.points;
        stats.dropped = tally.dropped;

        // Ascending by tile id because the merge makes it so, not because a sort step was
        // remembered. A `BTreeMap` per chunk and a heap across them is the same guarantee the
        // single-threaded `BTreeMap` gave.
        let mut merged = merge(chunks);
        loop {
            // Batched rather than a task per tile: one tile's stage C and encode is tens of
            // microseconds and rayon's stealing costs more than that. Batched rather than a whole
            // zoom at once because the batch is what bounds the encoded bytes held before the
            // writer takes them.
            //
            // The merge is timed around `collect` because that is where it happens: `merge` returns
            // a lazy iterator, so pulling a batch out of it is the k-way merge doing its work.
            let merging = std::time::Instant::now();
            let batch: Vec<(u64, Vec<BodyLayer>)> =
                merged.by_ref().take(par::batch_len()).collect();
            stats.merge_ms += merging.elapsed().as_millis() as u64;
            if batch.is_empty() {
                break;
            }
            let encoding = std::time::Instant::now();
            let done = encode_batch(batch)?;
            stats.encode_ms += encoding.elapsed().as_millis() as u64;
            let appending = std::time::Instant::now();
            for (id, encoded, rings) in done {
                stats.rings.add(rings);
                let Some((stored, raw_len)) = encoded else { continue };
                // Uncompressed, as this column has always meant.
                stats.bytes += raw_len as u64;
                stats.tiles += 1;
                writer.append_stored(id, &stored)?;
            }
            stats.append_ms += appending.elapsed().as_millis() as u64;
        }
        per_zoom.push(stats);
    }

    let bytes = writer.finish()?;
    Ok((bytes, per_zoom))
}

/// The map half of one zoom: every feature in the store, clipped into per-chunk tile maps.
///
/// Returns the chunks **in read order**, which is the only order the reduce may use them in.
///
/// The reader gets a thread of its own rather than a task on the pool, and that is not a
/// preference. A producer that blocks on a full channel from inside the pool it feeds deadlocks when
/// the pool has one thread: the single worker *is* the producer, so nothing can drain what it is
/// waiting to write. Off the pool, one thread is merely slow — which matters, because one thread is
/// a configuration this has to stay byte-identical at.
fn map_zoom(store: &Store, z: u8, tolerance: f64, buffer: f64) -> Result<(Vec<Chunk>, Tally)> {
    let workers = par::threads().max(1);
    // Bounded, because this is the one place the parallel tiler holds features the sequential one
    // did not: `2 * threads` chunks of about `chunk_vertices()` vertices, and no more however far
    // ahead of the workers the reader gets.
    let (send, receive) = std::sync::mpsc::sync_channel::<(usize, Vec<Feature>)>(workers * 2);
    let receive = Mutex::new(receive);
    let done: Mutex<Vec<(usize, Chunk, Tally)>> = Mutex::new(Vec::new());
    let failed = std::sync::atomic::AtomicBool::new(false);

    std::thread::scope(|scope| -> Result<()> {
        let reader = std::thread::Builder::new()
            .name("mamaps-read".to_string())
            .spawn_scoped(scope, move || read_chunks(store, z, send))
            .map_err(|e| tile_build::proto::Error(format!("cannot start the store reader: {e}")))?;

        // One worker runs on this thread whatever happens, so a machine that will not give us
        // threads is slow rather than a build hung on a channel nobody is draining.
        let mut spawned = Vec::new();
        for i in 1..workers {
            let worker = std::thread::Builder::new()
                .name(format!("mamaps-tile-{i}"))
                .stack_size(WORKER_STACK)
                .spawn_scoped(scope, || {
                    tile_chunks(&receive, &done, &failed, z, tolerance, buffer)
                });
            match worker {
                Ok(handle) => spawned.push(handle),
                Err(e) => {
                    eprintln!("WARNING: cannot start tiling thread {i} ({e}); continuing without");
                    break;
                }
            }
        }
        tile_chunks(&receive, &done, &failed, z, tolerance, buffer);
        for handle in spawned {
            handle.join().map_err(|_| {
                tile_build::proto::Error("a tiling thread panicked".to_string())
            })?;
        }
        reader.join().map_err(|_| {
            tile_build::proto::Error("the store reader thread panicked".to_string())
        })?
    })?;

    if failed.load(Ordering::Relaxed) {
        // Refused rather than published. A skipped chunk is a handful of features missing from a
        // handful of tiles: no error on device, no visibly broken tile, just an archive that is
        // quietly not the one the report describes.
        return err(format!("a chunk of z{z} panicked while being tiled; see the message above"));
    }

    let mut chunks = done.into_inner().expect("the chunk list outlives its workers");
    // **The reduce order.** By the index a chunk was read at, never by the order it finished in.
    // This one line is what keeps the archive independent of the thread count.
    chunks.sort_by_key(|(index, _, _)| *index);
    let mut tally = Tally::default();
    for (_, _, chunk_tally) in &chunks {
        tally.add(*chunk_tally);
    }
    Ok((chunks.into_iter().map(|(_, chunk, _)| chunk).collect(), tally))
}

/// Cut the store into chunks of roughly [`chunk_vertices`] input vertices and send them on.
///
/// The cut is a function of the feature stream alone — not of the thread count, not of how fast the
/// workers drain — so two runs chunk identically even before the merge makes the boundaries
/// invisible.
///
/// A feature below the zoom's own floor is dropped here rather than sent. It costs nothing to
/// recognise, and at z0 shipping them would be shipping the whole store.
/// Nanoseconds spent deserialising the spill, summed across every zoom.
///
/// Measured because `map_ms` covers the whole of [`map_zoom`], reader included, so it reports the
/// single-threaded reader as though it were parallel work. This is the number that says how much of
/// the map phase cannot be spread over the pool: time inside `reader.next()` alone, excluding any
/// blocking on the bounded channel, which is the workers being slow rather than the reader being the
/// bottleneck.
static READ_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// Take the accumulated reader time and reset it.
pub fn read_seconds() -> f64 {
    READ_NANOS.swap(0, Ordering::Relaxed) as f64 / 1e9
}

fn read_chunks(
    store: &Store,
    z: u8,
    send: std::sync::mpsc::SyncSender<(usize, Vec<Feature>)>,
) -> Result<()> {
    // The store speaks osm_ingest's error type and the tiler tile_build's; both wrap a string.
    // Zoom-filtered: a chunk holding only features deeper than `z` is seeked past rather than
    // parsed. At the shallow zooms that is nearly the whole spill.
    let mut reader =
        store.reader_for_zoom(z).map_err(|e| tile_build::proto::Error(e.to_string()))?;
    let want = chunk_vertices();
    let mut chunk: Vec<Feature> = Vec::new();
    let mut vertices = 0usize;
    let mut index = 0usize;
    let mut read_nanos = 0u64;
    loop {
        // Timed around the deserialise alone. The send below can block on a full channel, and that is
        // the workers being busy rather than the reader being the bottleneck.
        let at = std::time::Instant::now();
        let next = reader.next().map_err(|e| tile_build::proto::Error(e.to_string()))?;
        read_nanos += at.elapsed().as_nanos() as u64;
        let Some(feature) = next else { break };
        if z < feature.class.min_zoom {
            continue;
        }
        vertices += vertex_count(&feature.geometry);
        chunk.push(feature);
        if vertices >= want {
            // A closed receiver means every worker is already gone, which only happens on a panic
            // that is being reported anyway. Stopping quietly beats a second, vaguer error.
            if send.send((index, std::mem::take(&mut chunk))).is_err() {
                READ_NANOS.fetch_add(read_nanos, Ordering::Relaxed);
                return Ok(());
            }
            index += 1;
            vertices = 0;
        }
    }
    if !chunk.is_empty() {
        let _ = send.send((index, chunk));
    }
    READ_NANOS.fetch_add(read_nanos, Ordering::Relaxed);
    Ok(())
}

/// Take chunks until the reader is finished, tiling each into a map of its own.
///
/// A panic inside one chunk is **caught and recorded** rather than allowed to unwind, and the loop
/// keeps draining. That is not defensiveness about a bug that should not happen, it is about the
/// shape of the failure: a worker that dies with a bounded channel full behind it leaves the reader
/// blocked on a send nobody will ever take, and at one worker there is nobody else to drain it. So
/// the bug's symptom would be a 64-core box sitting at 0% forever in the middle of a five-minute
/// stage, instead of an error naming the zoom. `map_zoom` fails the build on the flag.
fn tile_chunks(
    receive: &Mutex<std::sync::mpsc::Receiver<(usize, Vec<Feature>)>>,
    done: &Mutex<Vec<(usize, Chunk, Tally)>>,
    failed: &std::sync::atomic::AtomicBool,
    z: u8,
    tolerance: f64,
    buffer: f64,
) {
    // Reused across chunks, because `tiles_touched` fills a caller's buffer precisely so that a
    // transcontinental line's 16 632 tiles are not a fresh allocation per feature.
    let mut touched: Vec<(u64, u64)> = Vec::new();
    loop {
        // Held to take a chunk and never while tiling one: the lock covers a pointer move, and the
        // work either side of it is milliseconds.
        let next = receive.lock().expect("the chunk queue").recv();
        let Ok((index, features)) = next else { return };
        // `AssertUnwindSafe` because everything the closure touches is a local: the chunk map it
        // builds is thrown away on a panic and `touched` is a scratch buffer that `tiles_touched`
        // overwrites from the start each time.
        let tiled = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            tile_chunk(&features, z, tolerance, buffer, &mut touched)
        }));
        match tiled {
            Ok((chunk, tally)) => done.lock().expect("the chunk list").push((index, chunk, tally)),
            Err(_) => {
                eprintln!("ERROR: chunk {index} of z{z} panicked while being tiled");
                failed.store(true, Ordering::Relaxed);
            }
        }
    }
}

/// Project, simplify, bisect and clip one chunk of features into a tile map of its own.
///
/// Verbatim the loop this module used to run on the main thread over the whole store, with the
/// destination private to the caller. Nothing here reads shared state, and that is why the chunk
/// boundaries cannot change a result: a feature's fate depends on the feature, the zoom and the
/// tolerance, and on nothing else in the chunk it happens to be in.
fn tile_chunk(
    features: &[Feature],
    z: u8,
    tolerance: f64,
    buffer: f64,
    touched: &mut Vec<(u64, u64)>,
) -> (Chunk, Tally) {
    let mut tiles: Chunk = BTreeMap::new();
    let mut tally = Tally::default();
    for feature in features {
        let mut projected = geom::project_geometry(&feature.geometry, z, EXTENT);
        // Significance first, then filter: computed on the whole geometry so a vertex's fate
        // does not depend on which tile it lands in.
        simplify::annotate(&mut projected);
        let thinned = simplify::filter(&projected, tolerance);
        if is_empty(&thinned) {
            tally.dropped += 1;
            continue;
        }
        geom::tiles_touched(&thinned, z, EXTENT, buffer, touched);
        for &(tx, ty) in touched.iter() {
            let rect = geom::tile_rect(tx, ty, EXTENT, buffer);
            let clipped = clip::clip_geometry(&thinned, &rect);
            if is_empty(&clipped) {
                continue;
            }
            let local = geom::to_tile(&clipped, tx, ty, EXTENT);
            let layer = tiles
                .entry((tile_id(z, tx, ty), feature.class.layer))
                .or_insert_with(|| BodyLayer::new(feature.class.layer));
            let added = push(layer, feature, &local);
            tally.features += added.0;
            tally.points += added.1;
        }
    }
    (tiles, tally)
}

/// Input vertices in a geometry, for chunking. Counted rather than estimated, because it is the
/// number that decides how long a chunk takes.
fn vertex_count(geometry: &Geometry) -> usize {
    match geometry {
        Geometry::Points(points) => points.len(),
        Geometry::Lines(lines) => lines.iter().map(Vec::len).sum(),
        Geometry::Polygons(polygons) => polygons.iter().flatten().map(Vec::len).sum(),
    }
}

/// K-way merge one zoom's chunks into ascending tiles, each carrying its layers in id order.
///
/// This is the reduce, and its ordering is the whole correctness argument. Keying the heap on
/// `(tile, layer, chunk index)` makes the entries for one tile arrive layer-major and, within a
/// layer, in **chunk order** — so appending them as they arrive lays a layer's features down in the
/// order the store yielded them. That is not merely *a* deterministic order; it is the order the
/// single-threaded tiler produced, which is why the archive did not move.
///
/// Streamed rather than merged into one map first, so a chunk's `BTreeMap` nodes are freed as they
/// are consumed instead of every chunk and a merged copy of it being live at once.
fn merge(chunks: Vec<Chunk>) -> Merged {
    let mut chunks: Vec<btree_map::IntoIter<(u64, u8), BodyLayer>> =
        chunks.into_iter().map(BTreeMap::into_iter).collect();
    let mut front: Vec<Option<((u64, u8), BodyLayer)>> = Vec::with_capacity(chunks.len());
    let mut next = BinaryHeap::with_capacity(chunks.len());
    for (index, chunk) in chunks.iter_mut().enumerate() {
        let head = chunk.next();
        if let Some(((tile, layer), _)) = &head {
            next.push(Reverse((*tile, *layer, index)));
        }
        front.push(head);
    }
    Merged { chunks, front, next }
}

/// A merge in progress: one held entry per chunk, and a heap over their keys. `Reverse`, because
/// `BinaryHeap` is a max-heap and the writer wants ascending ids.
struct Merged {
    chunks: Vec<btree_map::IntoIter<(u64, u8), BodyLayer>>,
    front: Vec<Option<((u64, u8), BodyLayer)>>,
    next: BinaryHeap<Reverse<(u64, u8, usize)>>,
}

impl Iterator for Merged {
    type Item = (u64, Vec<BodyLayer>);

    fn next(&mut self) -> Option<(u64, Vec<BodyLayer>)> {
        let Reverse((tile, _, _)) = *self.next.peek()?;
        let mut layers: Vec<BodyLayer> = Vec::new();
        while let Some(&Reverse((at, layer_id, index))) = self.next.peek() {
            if at != tile {
                break;
            }
            self.next.pop();
            let (_, layer) = self.front[index].take().expect("a heap key without its entry");
            // Entries for one layer are adjacent, because the key sorts the layer before the chunk.
            // So the accumulator only ever has to look at the layer it started last.
            match layers.last_mut() {
                Some(last) if last.layer_id == layer_id => concatenate(last, layer),
                _ => layers.push(layer),
            }
            let head = self.chunks[index].next();
            if let Some(((tile, layer_id), _)) = &head {
                self.next.push(Reverse((*tile, *layer_id, index)));
            }
            self.front[index] = head;
        }
        Some((tile, layers))
    }
}

/// Append one chunk's share of a layer onto another chunk's.
///
/// Rebased, not rebuilt: a `BodyLayer` is three parallel arenas and a feature addresses its parts by
/// index, so concatenating them means shifting `parts_offset` by the parts already there and
/// `coord_start` by the coordinates. The result is byte for byte what [`push`] would have produced
/// had both chunks' features gone into one layer in this order, which is the claim the whole design
/// rests on and what `concatenating_two_chunks_of_a_layer_is_one_layer` holds it to.
fn concatenate(into: &mut BodyLayer, from: BodyLayer) {
    let parts_base = into.parts.len() as u32;
    let coords_base = into.coords.len() as u32;
    into.features.extend(from.features.into_iter().map(|mut feature| {
        feature.parts_offset += parts_base;
        feature
    }));
    into.parts.extend(from.parts.into_iter().map(|mut part| {
        part.coord_start += coords_base;
        part
    }));
    into.coords.extend_from_slice(&from.coords);
}

/// Stage C and body encoding for a batch of merged tiles, in parallel, results in tile order.
///
/// Both halves are pure functions of a single tile, which is the only reason this can fan out at
/// all: [`crate::rings::normalise`] rewrites one layer's own arenas and nothing else, and
/// [`tilecodec::mamaps::body::serialize`] only reads. `map(..).collect()` preserves the input's
/// order — rayon's collect is positional, not completion-ordered — so the caller appends in
/// ascending tile id with no sort of its own.
///
/// A tile whose layers all emptied out comes back as `None` rather than being dropped here, because
/// its stage C counters still belong in the build report.
#[allow(clippy::type_complexity)]
/// Stage C, serialise and **compress** one batch of merged tiles, in parallel.
///
/// Compression belongs here rather than in the writer's append. DEFLATE at level nine is the single
/// most expensive step per tile and the one least able to be stolen: left in `append_encoded` it ran
/// on the appending thread, downstream of this whole parallel phase, and on a California z14 it was
/// ~900 MB through one core while sixty-three sat idle. That is why a 64-thread build was *slower*
/// than the sequential one it replaced.
///
/// Returns the raw length alongside the compressed frame, because the per-zoom `bytes` column has
/// always reported *uncompressed* body size and moving compression must not silently change what a
/// build report means.
fn encode_batch(
    batch: Vec<(u64, Vec<BodyLayer>)>,
) -> Result<Vec<(u64, Option<(Vec<u8>, usize)>, crate::rings::Stats)>> {
    let min_len = par::min_task_len(batch.len());
    par::install(|| {
        batch
            .into_par_iter()
            .with_min_len(min_len)
            .map(|(id, mut layers)| {
                // **Stage C**, per tile: winding normalised, hole containment resolved, degenerate
                // rings dropped. Once, here, in `f64` with no frame budget, instead of every frame
                // on device in `i32` under one. This is what makes `FLAG_RINGS_VALIDATED` true.
                let mut rings = crate::rings::Stats::default();
                for layer in &mut layers {
                    rings.add(crate::rings::normalise(layer));
                }
                // A layer that ended up empty - every feature in it fell below the minimum area
                // after clipping, or lost its exterior to stage C - costs bytes in the archive and
                // a draw call on device for nothing.
                layers.retain(|layer| !layer.features.is_empty());
                if layers.is_empty() {
                    return Ok((id, None, rings));
                }
                let body = Body { extent: EXTENT as u16, layers };
                let encoded = tilecodec::mamaps::body::serialize(&body)?;
                let raw_len = encoded.len();
                let stored = tilecodec::mamaps::write::compress_body(&encoded);
                Ok((id, Some((stored, raw_len)), rings))
            })
            .collect()
    })
}

/// Append one feature's tile-local geometry to a layer. Returns `(features, points)` added.
///
/// A polygon becomes **one feature per ring group**, so a feature's parts are exactly one exterior
/// and its holes — which is what the tessellator wants and what makes "one outer per feature" an
/// invariant worth stating.
fn push(layer: &mut BodyLayer, feature: &Feature, geometry: &IntGeometry) -> (u64, u64) {
    let class = &feature.class;
    let mut added = (0u64, 0u64);
    match geometry {
        IntGeometry::Polygons(polygons) => {
            // Below a few square pixels a polygon is a speck rather than detail, and there are
            // millions of them. Measured **after** clipping, on the shape that would actually be
            // drawn, so a large park clipped to a sliver of one tile is kept where it is big and
            // dropped where it is not.
            let floor = crate::schema::land::min_area_units(class.min_area_px, EXTENT);
            for rings in polygons {
                // A ring needs three distinct points plus the closing one; anything less bounds no
                // area. Filtered *before* anything is appended, because a part and its points have
                // to be committed together — the encoder requires the parts to tile the arena
                // exactly, so half-appending a group and rolling it back would leave orphaned
                // coordinates.
                let keep: Vec<(usize, &Vec<(i32, i32)>)> =
                    rings.iter().enumerate().filter(|(_, ring)| ring.len() >= 4).collect();
                // If the exterior did not survive, what is left is a hole with nothing to be a hole
                // in, which would paint as the inverse of the shape.
                if !keep.first().is_some_and(|(index, _)| *index == 0) {
                    continue;
                }
                if floor > 0.0 && ring_area(keep[0].1) < floor {
                    continue;
                }
                let parts_offset = layer.parts.len() as u32;
                for (index, ring) in &keep {
                    added.1 += push_part(
                        layer,
                        ring,
                        if *index == 0 { WINDING_OUTER } else { WINDING_HOLE },
                    );
                }
                layer.features.push(BodyFeature {
                    kind: class.kind,
                    kind_detail: class.kind_detail,
                    geom_type: GEOM_POLYGON,
                    flags: class.flags,
                    parts_offset,
                    part_count: keep.len() as u32,
                });
                added.0 += 1;
            }
        }
        IntGeometry::Lines(lines) => {
            let parts_offset = layer.parts.len() as u32;
            for line in lines {
                if line.len() < 2 {
                    continue;
                }
                added.1 += push_part(layer, line, WINDING_OUTER);
            }
            let part_count = layer.parts.len() as u32 - parts_offset;
            if part_count > 0 {
                layer.features.push(BodyFeature {
                    kind: class.kind,
                    kind_detail: class.kind_detail,
                    geom_type: GEOM_LINE,
                    flags: class.flags,
                    parts_offset,
                    part_count,
                });
                added.0 += 1;
            }
        }
        // Points are not carried: the renderer decoded them and threw them away.
        IntGeometry::Points(_) => {}
    }
    added
}

/// Append one path's points to a layer's arena, clamped to what an `i16` holds.
///
/// The clip buffer keeps a coordinate within a few percent of the extent, so `i16` has eight times
/// the headroom needed and this never bites. Clamping rather than asserting because a single stray
/// vertex should not fail a whole build.
fn push_part(layer: &mut BodyLayer, points: &[(i32, i32)], winding: u16) -> u64 {
    let coord_start = layer.coords.len() as u32;
    for &(x, y) in points {
        layer.coords.push((
            x.clamp(i16::MIN as i32, i16::MAX as i32) as i16,
            y.clamp(i16::MIN as i32, i16::MAX as i32) as i16,
        ));
    }
    layer.parts.push(Part { coord_start, point_count: points.len() as u32, winding });
    points.len() as u64
}

/// A ring's absolute area, by the shoelace formula.
///
/// `i64` throughout: a 4096-unit tile's coordinates cross-multiply to about 2^24 per term, and a
/// long ring accumulates thousands of them, which overflows an `i32` and would silently report a
/// huge shape as a tiny one.
fn ring_area(ring: &[(i32, i32)]) -> f64 {
    let mut twice = 0i64;
    for pair in ring.windows(2) {
        let ((x0, y0), (x1, y1)) = (pair[0], pair[1]);
        twice += x0 as i64 * y1 as i64 - x1 as i64 * y0 as i64;
    }
    (twice.abs() as f64) / 2.0
}

fn is_empty(g: &Geometry<SigPt>) -> bool {
    match g {
        Geometry::Points(points) => points.is_empty(),
        Geometry::Lines(lines) => lines.iter().all(|l| l.len() < 2),
        Geometry::Polygons(polygons) => polygons.iter().all(|rings| {
            rings.first().map(|ring| ring.len() < 4).unwrap_or(true)
        }),
    }
}



/// A build that produced nothing is a build whose schema matched nothing, which is worth failing on
/// rather than publishing an empty archive.
pub fn check_not_empty(stats: &[ZoomStats]) -> Result<()> {
    if stats.iter().all(|s| s.tiles == 0) {
        return err("the build produced no tiles: nothing in the input matched the schema");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The tests hold features in memory and the tiler reads them from a file, so they spill
    /// first. Keeps a test about tiling from reading like a test about plumbing.
    fn spilled(features: &[Feature]) -> crate::store::Store {
        crate::store::Store::of(features).expect("spill")
    }
    use crate::schema::Class;
    use tilecodec::mamaps::dict;

    fn square(lon: f64, lat: f64, size: f64) -> Geometry {
        Geometry::Polygons(vec![vec![vec![
            (lon, lat),
            (lon + size, lat),
            (lon + size, lat + size),
            (lon, lat + size),
            (lon, lat),
        ]]])
    }

    fn lake(lon: f64, lat: f64, size: f64, min_zoom: u8) -> Feature {
        Feature {
            class: Class::area(dict::LAYER_WATER, crate::schema::kind("lake"), min_zoom),
            geometry: square(lon, lat, size),
        }
    }

    fn settings(min_zoom: u8, max_zoom: u8) -> Settings {
        Settings { min_zoom, max_zoom, simplification: DEFAULT_SIMPLIFICATION, build_id: 7 }
    }

    /// The thread budget and the chunk size are process-wide, and `cargo test` runs these tests in
    /// one process on several threads. The tests that set either take this lock against each other —
    /// not for safety, an `AtomicUsize` is safe, but so that a test asserting "this is what one
    /// thread produces" really is running on one thread when it says so.
    ///
    /// A poisoned lock is taken anyway: poisoning means another test panicked, and *its* failure is
    /// the one worth reading rather than a cascade of lock errors on top of it.
    static BUDGET: Mutex<()> = Mutex::new(());

    fn budget() -> std::sync::MutexGuard<'static, ()> {
        let guard = BUDGET.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        // Trip the once-only environment adoption here, *before* the test sets its own count. Left
        // to `build`, the first call would run it after `set_threads(1)` and could quietly put
        // `RAYON_NUM_THREADS` back — leaving a test that says "one thread" asserting nothing.
        adopt_thread_budget();
        guard
    }

    /// `par::clear_threads` is `#[cfg(test)]` *inside* `tile_build`, so it does not exist from here.
    /// Putting the box's own count back is the same thing for a test process.
    fn release_threads() {
        par::set_threads(std::thread::available_parallelism().map(|n| n.get()).unwrap_or(4));
    }

    #[test]
    fn a_lake_tiles_and_reads_back_out_of_the_archive() {
        let features = vec![lake(-120.0, 35.0, 0.5, 0)];
        let (bytes, stats) = build(&spilled(&features), &settings(0, 6)).expect("build");
        check_not_empty(&stats).expect("not empty");

        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read back");
        assert!(!entries.is_empty());
        // Every stored body holds the water layer and nothing else.
        for (_, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            let layer = body.layer(dict::LAYER_WATER).expect("water");
            assert!(!layer.features.is_empty());
            assert_eq!(layer.features[0].geom_type, GEOM_POLYGON);
            assert!(body.layer(dict::LAYER_BUILDINGS).is_none());
        }
    }

    /// A feature's `min_zoom` is what keeps a world tile from carrying every pond. Enforced here,
    /// so the archive does not hold what the style would not draw.
    #[test]
    fn a_feature_is_not_written_above_its_own_min_zoom() {
        let features = vec![lake(-120.0, 35.0, 0.01, 10)];
        let (_, stats) = build(&spilled(&features), &settings(0, 11)).expect("build");
        for s in &stats {
            if s.zoom < 10 {
                assert_eq!(s.tiles, 0, "z{} should be empty", s.zoom);
            }
        }
        assert!(stats.iter().any(|s| s.zoom >= 10 && s.tiles > 0), "and present once it is due");
    }

    /// **Invariant 4 and 5 together.** Ids ascend across the whole archive because `tile_id` is
    /// zoom-major and every emit path is a `BTreeMap`, and the output is byte-identical run to run
    /// because nothing iterates a hash map.
    #[test]
    fn the_output_is_byte_identical_and_its_ids_ascend() {
        let features = vec![
            lake(-120.0, 35.0, 0.4, 0),
            lake(-119.0, 36.0, 0.3, 0),
            Feature {
                class: Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 0),
                geometry: square(-120.1, 35.1, 0.02),
            },
        ];
        let first = build(&spilled(&features), &settings(0, 8)).expect("first").0;
        let second = build(&spilled(&features), &settings(0, 8)).expect("second").0;
        assert_eq!(first, second, "two runs of the same input");

        let entries = tilecodec::mamaps::read::read_all(&first).expect("read");
        assert!(entries.windows(2).all(|p| p[1].0 > p[0].0), "ids ascend");
        for (id, _, _) in &entries {
            let (z, _, _) = tilecodec::pmtiles::tile_zxy(*id);
            let range = tilecodec::pmtiles::zoom_base(z)..tilecodec::pmtiles::zoom_base(z + 1);
            assert!(range.contains(id), "id {id} is outside z{z}");
        }
    }

    /// Two layers in one tile, which is the shape the format exists for: a cold tile is one range
    /// request whatever the style is drawing.
    #[test]
    fn a_tile_carrying_two_layers_holds_them_both() {
        let features = vec![
            lake(-120.0, 35.0, 0.02, 0),
            Feature {
                class: Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 0),
                geometry: square(-120.005, 35.005, 0.002),
            },
        ];
        let (bytes, _) = build(&spilled(&features), &settings(14, 14)).expect("build");
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let both = entries.iter().any(|(_, _, body)| {
            let body = Body::parse(body).expect("parse");
            body.layer(dict::LAYER_WATER).is_some() && body.layer(dict::LAYER_BUILDINGS).is_some()
        });
        assert!(both, "some tile should carry both");
    }

    #[test]
    fn a_build_that_matched_nothing_fails_rather_than_publishing_an_empty_archive() {
        let stats = vec![ZoomStats { zoom: 0, ..ZoomStats::default() }];
        assert!(check_not_empty(&stats).is_err());
        assert!(build(&spilled(&[]), &settings(0, 2)).is_err(), "the writer refuses an empty archive");
    }

    /// Simplification is per zoom, so a shallow tile holds fewer points for the same shape. If this
    /// ever inverts, the tolerance is being applied at the wrong end.
    #[test]
    fn a_shallow_zoom_carries_fewer_points_than_a_deep_one() {
        // A wiggly line, so there is something to simplify away.
        let points: Vec<(f64, f64)> = (0..200)
            .map(|i| (-120.0 + i as f64 * 0.001, 35.0 + (i % 3) as f64 * 0.0005))
            .collect();
        let features = vec![Feature {
            class: Class::line(dict::LAYER_WATER, crate::schema::kind("river"), 0),
            geometry: Geometry::Lines(vec![points]),
        }];
        let (_, stats) = build(&spilled(&features), &settings(6, 14)).expect("build");
        let at = |z: u8| stats.iter().find(|s| s.zoom == z).expect("zoom").points;
        assert!(at(6) < at(14), "z6 has {} points, z14 has {}", at(6), at(14));
    }

    /// Enough features, spread over enough tiles, that every zoom has several chunks to merge and
    /// several tiles per chunk. A single-tile fixture would pass any merge, correct or not.
    fn a_crowd() -> Vec<Feature> {
        let mut features = Vec::new();
        for i in 0..60 {
            let (row, column) = (i / 10, i % 10);
            let (lon, lat) = (-120.0 + column as f64 * 0.03, 35.0 + row as f64 * 0.03);
            features.push(lake(lon, lat, 0.02, 0));
            features.push(Feature {
                class: Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 0),
                geometry: square(lon + 0.004, lat + 0.004, 0.004),
            });
            // A line as well, so the merge has to rebase a `GEOM_LINE` feature's parts too, and a
            // long one so it crosses tiles rather than sitting inside one.
            features.push(Feature {
                class: Class::line(dict::LAYER_WATER, crate::schema::kind("river"), 0),
                geometry: Geometry::Lines(vec![(0..40)
                    .map(|k| (lon + k as f64 * 0.002, lat + (k % 5) as f64 * 0.001))
                    .collect()]),
            });
        }
        features
    }

    /// **The property the parallel tiler exists to keep.** The archive is a function of the input,
    /// not of the thread count.
    ///
    /// One thread is not a formality: it is the configuration where the reader and the single worker
    /// share one queue, and it is the configuration a producer running on the pool would deadlock
    /// in. Three is the awkward one, where chunks outnumber threads unevenly and completion order
    /// is guaranteed not to be read order.
    #[test]
    fn the_archive_is_identical_at_every_thread_count() {
        let _budget = budget();
        let store = spilled(&a_crowd());
        // Small enough that the crowd above becomes many chunks, so the merge is doing real work at
        // every thread count rather than folding one chunk into itself.
        set_chunk_vertices(64);
        par::set_threads(1);
        let (one, first_stats) = build(&store, &settings(0, 12)).expect("one thread");
        for threads in [2usize, 3, 8, 17] {
            par::set_threads(threads);
            let (bytes, stats) = build(&store, &settings(0, 12)).expect("many threads");
            assert_eq!(bytes.len(), one.len(), "{threads} threads changed the archive length");
            assert_eq!(bytes, one, "{threads} threads changed the archive bytes");
            // The report is published beside the archive and diffed too, so it has to agree as
            // well: a counter summed in completion order would still be right, and one accumulated
            // per chunk into a shared total would not.
            for (a, b) in first_stats.iter().zip(&stats) {
                assert_eq!(
                    (a.zoom, a.tiles, a.features, a.points, a.dropped, a.bytes),
                    (b.zoom, b.tiles, b.features, b.points, b.dropped, b.bytes),
                    "{threads} threads changed the z{} counters",
                    a.zoom,
                );
                assert_eq!(a.rings, b.rings, "{threads} threads changed z{} stage C", a.zoom);
            }
        }
        set_chunk_vertices(0);
        release_threads();
    }

    /// The stronger claim, and the one that makes the thread count irrelevant rather than merely
    /// tested: concatenating a partition of the feature stream in partition order reproduces the
    /// stream, so **where** the chunk boundaries fall cannot matter either.
    ///
    /// A chunk of one vertex means one chunk per feature — every tile in the merge is then assembled
    /// from as many pieces as it has features, which is the worst case for [`concatenate`] and the
    /// one where a wrong `parts_offset` could not hide.
    #[test]
    fn the_archive_is_identical_however_the_features_are_chunked() {
        let _budget = budget();
        let store = spilled(&a_crowd());
        par::set_threads(4);
        set_chunk_vertices(1);
        let one_per_feature = build(&store, &settings(0, 12)).expect("tiny chunks").0;
        for size in [7usize, 64, 4096, 1 << 20] {
            set_chunk_vertices(size);
            let bytes = build(&store, &settings(0, 12)).expect("build").0;
            assert_eq!(bytes, one_per_feature, "a chunk of {size} vertices changed the archive");
        }
        set_chunk_vertices(0);
        release_threads();
    }

    /// Feature order **within a tile layer** is the store's order, asserted rather than assumed.
    ///
    /// This is the failure the whole module is arranged around, and it is invisible from outside: a
    /// merge that folded chunks in completion order would still put every feature in the right tile,
    /// still draw the right picture, and still write different bytes every run. So each feature
    /// carries a `kind_detail` counting up in store order, and the decoded archive has to count up
    /// too.
    #[test]
    fn feature_order_within_a_tile_layer_is_the_store_order() {
        let _budget = budget();
        // All in one small patch so they pile into the same handful of z14 tiles, each labelled with
        // its position in the store.
        let features: Vec<Feature> = (0..40u16)
            .map(|i| Feature {
                class: Class {
                    kind_detail: i,
                    ..Class::area(dict::LAYER_WATER, crate::schema::kind("lake"), 0)
                },
                geometry: square(-120.0 + i as f64 * 0.00005, 35.0, 0.004),
            })
            .collect();
        let store = spilled(&features);
        set_chunk_vertices(1);
        par::set_threads(8);
        let (bytes, _) = build(&store, &settings(14, 14)).expect("build");
        set_chunk_vertices(0);
        release_threads();

        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let mut widest = 0usize;
        for (id, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            let Some(layer) = body.layer(dict::LAYER_WATER) else { continue };
            let order: Vec<u16> = layer.features.iter().map(|f| f.kind_detail).collect();
            let mut ascending = order.clone();
            ascending.sort_unstable();
            assert_eq!(order, ascending, "tile {id} holds its features out of store order");
            widest = widest.max(order.len());
        }
        // And that assertion has to have had something to bite on: a tile carrying one feature is
        // in order however badly the merge behaves.
        assert!(widest >= 20, "no tile gathered enough features to order (widest was {widest})");
    }

    /// [`concatenate`] against the thing it claims to equal: one layer built by pushing both
    /// features in one go. Not "the offsets look plausible" but "the layer is the same layer".
    #[test]
    fn concatenating_two_chunks_of_a_layer_is_one_layer() {
        let class = Class::area(dict::LAYER_WATER, crate::schema::kind("lake"), 0);
        let feature = Feature { class, geometry: square(0.0, 0.0, 1.0) };
        // Tile-local already, so the fixture is about the arenas rather than about projection, and
        // big enough that no minimum-area floor can drop it.
        let box_at = |x: i32| {
            IntGeometry::Polygons(vec![vec![vec![
                (x, 0),
                (x + 500, 0),
                (x + 500, 500),
                (x, 500),
                (x, 0),
            ]]])
        };

        let mut together = BodyLayer::new(dict::LAYER_WATER);
        assert_eq!(push(&mut together, &feature, &box_at(0)), (1, 5));
        push(&mut together, &feature, &box_at(1000));

        let mut first = BodyLayer::new(dict::LAYER_WATER);
        push(&mut first, &feature, &box_at(0));
        let mut second = BodyLayer::new(dict::LAYER_WATER);
        push(&mut second, &feature, &box_at(1000));
        concatenate(&mut first, second);

        assert_eq!(first, together, "two chunks concatenated are not the one-pass layer");
        // Spelled out as well, because `assert_eq` on the whole layer would also pass if both were
        // empty, and an arena its parts do not tile exactly is what the encoder rejects.
        assert_eq!(first.features.len(), 2);
        assert_eq!(first.features[1].parts_offset, 1);
        assert_eq!(first.parts[1].coord_start, first.parts[0].point_count);
        assert_eq!(first.coords.len(), 10);
    }

    /// The merge's contract on its own, without a build around it: ascending tiles, and layers in id
    /// order within a tile, with same-layer contributions from several chunks collapsed into one.
    #[test]
    fn the_merge_yields_ascending_tiles_with_their_layers_in_id_order() {
        let mut early: Chunk = BTreeMap::new();
        early.insert((10, 3), BodyLayer::new(3));
        early.insert((30, 1), BodyLayer::new(1));
        let mut middle: Chunk = BTreeMap::new();
        middle.insert((10, 1), BodyLayer::new(1));
        middle.insert((20, 2), BodyLayer::new(2));
        let mut late: Chunk = BTreeMap::new();
        late.insert((10, 3), BodyLayer::new(3));

        let merged: Vec<(u64, Vec<u8>)> = merge(vec![early, middle, late])
            .map(|(id, layers)| (id, layers.iter().map(|l| l.layer_id).collect()))
            .collect();
        // Tile 10 carries layer 1 before layer 3 even though layer 3 was read first, and its two
        // separate layer-3 pieces arrive as one layer rather than two.
        assert_eq!(merged, vec![(10, vec![1, 3]), (20, vec![2]), (30, vec![1])]);
    }
}
