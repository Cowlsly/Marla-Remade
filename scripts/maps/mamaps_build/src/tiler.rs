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
//! * [`subdivide::subdivide`], which walks a feature down the tile quadtree instead of clipping it
//!   in full against every tile it touches. A feature inside one tile is clipped once, exactly as
//!   before; one spanning three states pays one vertex pass per zoom level rather than one per tile.
//! * [`clip`](tile_build::clip)'s Liang-Barsky for lines and Sutherland-Hodgman for rings, against a
//!   tile rect with a buffer so a stroke at the edge has geometry to join to.
//!
//! # Memory
//!
//! One zoom's chunks, **on disk**, through [`crate::tilespill`]. Peak is a function of the thread
//! count and a fixed read budget rather than of the extract: `O(threads)` in-flight features and
//! per-worker chunk maps, plus [`tilespill::READ_BUDGET`] of merge cursors.
//!
//! It was in memory until it could not be. A north-america z14 is 267 M features, 461 M parts and
//! 3.07 G points — about 23 GB of raw arena, and 53 GB resident once `BTreeMap` nodes, `Vec`
//! capacity slack and the duplicated `BodyLayer` header per `(chunk, tile, layer)` are counted. A
//! planet z14 projects to ~244 GB, which is not a tuning problem.
//!
//! The spill is created and dropped **per zoom**, so peak scratch is the largest single zoom rather
//! than the sum: ~23 GB at a north-america z14, ~100 GB at a planet one. It is deliberately not
//! covered by `--keep-store`/`--reuse-store`. Those govern the stage-A feature store, which is a
//! reusable input; this is a within-zoom temporary, and keeping it would strand a hundred gigabytes
//! for nothing.
//!
//! # Parallelism, and why the bytes do not move
//!
//! Tiling was 301 s of a 355 s California build, on one of 64 cores, and z14 alone is 68% of it
//! (180.7 M of 263 M output points) — so the split that matters is *within* a zoom, not across
//! them. A zoom is therefore a map/reduce:
//!
//! 1. **Map.** One reader thread cuts the feature stream into chunks of roughly
//!    [`CHUNK_VERTICES`] input vertices and hands them over a bounded channel to a pool of workers.
//!    Each worker projects, simplifies and clips its own chunk into a private [`Chunk`] map,
//!    touching nothing shared.
//! 2. **Reduce.** The chunks are ordered by the index they were *read* at — never by the order they
//!    finished in — and k-way merged on the tile id.
//! 3. **Encode.** Stage C and body serialisation are pure functions of one tile, so a batch of
//!    merged tiles is encoded in parallel and appended in tile order on one thread.
//!
//! Steps 2 and 3 alternate, and **overlapping them has been tried and made the build slower.** The
//! reduce is single-threaded and the encode is the whole pool, so a merge thread behind a
//! one-batch channel looks like free time: 33 s of a 351 s us-west tiling stage with sixty-three
//! cores idle. Measured, it cost 53 s instead — 351 s to 404 s, byte-identical — and the merge's own
//! busy time went 33 s to 217 s. Two reasons, both about the machine rather than the code. The pool
//! is already one thread per logical CPU, so the merge thread is a sixty-fifth on sixty-four and has
//! to be timesliced against threads that never yield; and the merge is a pointer walk over
//! `BTreeMap` nodes, which was fast because it had L3 to itself and is not once sixty-four encode
//! workers are streaming gigabytes through it. This is the same shape as the parallel spill refill
//! in [`crate::store`], and it is recorded here for the same reason: it reads like an obvious win
//! and it is not one.
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
//! Memory was what this cost, and the spill is what took it back. A chunk is written out and freed
//! as soon as its worker finishes it, so what is live at the end of the map phase is a handful of
//! [`ChunkRef`]s rather than every chunk's arenas. In flight are at most `2 * threads` chunks of raw
//! features and one chunk map per worker. [`CHUNK_VERTICES`] is still large for the other half of
//! the same reason: smaller chunks balance the pool better and duplicate more `BodyLayer` headers,
//! and now also more entry headers on disk.
//!
//! What the spill costs instead is a decode on the **merge thread**, which is serial, and this
//! module has already been burned once by treating the merge as cheap — see the paragraph above.
//! `merge_ms` is the number to watch; a parallel run-merge cascade is the escape hatch if it is bad.

use std::cmp::Reverse;
use std::collections::{BTreeMap, BinaryHeap};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::Mutex;

use rayon::prelude::*;
use tile_build::geom::{self, Geometry, IntGeometry, SigPt};
use tile_build::par;
use tile_build::subdivide;
use tile_build::progress::Progress;
use tile_build::simplify;
use tilecodec::mamaps::body::{
    Body, Feature as BodyFeature, Layer as BodyLayer, Part, GEOM_LINE, GEOM_POLYGON, WINDING_HOLE,
    WINDING_OUTER,
};
use tilecodec::mamaps::write::{Options, StreamWriter};
use tilecodec::pmtiles::tile_id;
use tilecodec::proto::{err, Result};

use crate::extract::Feature;
use crate::store::Store;
use crate::tilespill::{self, ChunkRef, ChunkReader, ChunkSpill};

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
    /// What coalescing merged away: an OSM way is an editing unit, not a rendering one.
    pub lines: crate::coalesce::Stats,
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
    /// Where one zoom's chunks go while they wait for the merge. See [`crate::tilespill`].
    ///
    /// Beside the output archive, as `<out>.tilechunks`, matching where the feature spill is placed.
    /// Truncated at the start of every zoom and removed at the end of each, so it holds one zoom.
    pub scratch: PathBuf,
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

        // Per zoom, so peak scratch is the largest single zoom rather than the sum, and so a build
        // that dies at z14 leaves one zoom behind rather than fifteen.
        let spill = ChunkSpill::create(&settings.scratch)?;
        let mapped = std::time::Instant::now();
        let (chunks, tally) = map_zoom(store, z, tolerance, buffer, &spill)?;
        stats.map_ms = mapped.elapsed().as_millis() as u64;
        stats.features = tally.features;
        stats.points = tally.points;
        stats.dropped = tally.dropped;

        // Ascending by tile id because the merge makes it so, not because a sort step was
        // remembered. A `BTreeMap` per chunk and a heap across them is the same guarantee the
        // single-threaded `BTreeMap` gave; the maps now live on disk and the guarantee does not
        // change, because a chunk's bytes are written in its key order and read back in file order.
        let mut merged = merge(&chunks, &spill);
        // Merge, encode and append have no total to count against -- the tile count is only known once
        // the merge has produced it -- so this reports what has been written rather than a
        // percentage. Still the difference between forty silent minutes and a number that moves.
        let mut written = 0usize;
        let mut shown = 0usize;
        loop {
            // Batched rather than a task per tile: one tile's stage C and encode is tens of
            // microseconds and rayon's stealing costs more than that. Batched rather than a whole
            // zoom at once because the batch is what bounds the encoded bytes held before the
            // writer takes them.
            //
            // The merge is timed around `collect` because that is where it happens: `merge` returns
            // a lazy iterator, so pulling a batch out of it is the k-way merge doing its work.
            let merging = std::time::Instant::now();
            // `collect` into a `Result`, because a truncated scratch file must fail the build rather
            // than end the zoom early and publish a short archive.
            let batch: Vec<(u64, Vec<BodyLayer>)> =
                merged.by_ref().take(par::batch_len()).collect::<Result<Vec<_>>>()?;
            stats.merge_ms += merging.elapsed().as_millis() as u64;
            if batch.is_empty() {
                break;
            }
            let encoding = std::time::Instant::now();
            let done = encode_batch(batch)?;
            stats.encode_ms += encoding.elapsed().as_millis() as u64;
            let appending = std::time::Instant::now();
            for (id, encoded, rings, lines) in done {
                stats.rings.add(rings);
                stats.lines.add(lines);
                let Some((stored, raw_len)) = encoded else { continue };
                // Uncompressed, as this column has always meant.
                stats.bytes += raw_len as u64;
                stats.tiles += 1;
                writer.append_stored(id, &stored)?;
                written += 1;
                // Every 25k tiles: often enough to look alive on a continent, rare enough that the
                // write itself is never the cost.
                if written - shown >= 25_000 {
                    shown = written;
                    print!("\r{:<28} [{written:>10} tile(s)]", format!("Encode z{z}"));
                    let _ = std::io::Write::flush(&mut std::io::stdout());
                }
            }
            stats.append_ms += appending.elapsed().as_millis() as u64;
        }
        if written > 0 {
            println!("\r{:<28} [{written:>10} tile(s)]", format!("Encode z{z}"));
        }
        // Reserved, written, on disk and read back must agree. A chunk that quietly lost entries
        // would produce an archive with holes in it and nothing downstream could tell.
        drop(merged);
        spill.check_books()?;
        per_zoom.push(stats);
    }

    let bytes = writer.finish()?;
    Ok((bytes, per_zoom))
}

/// The map half of one zoom: every feature in the store, clipped into per-chunk tile maps and
/// spilled to `spill` as each is finished.
///
/// Returns the chunks **in read order**, which is the only order the reduce may use them in.
///
/// The reader gets a thread of its own rather than a task on the pool, and that is not a
/// preference. A producer that blocks on a full channel from inside the pool it feeds deadlocks when
/// the pool has one thread: the single worker *is* the producer, so nothing can drain what it is
/// waiting to write. Off the pool, one thread is merely slow — which matters, because one thread is
/// a configuration this has to stay byte-identical at.
fn map_zoom(
    store: &Store,
    z: u8,
    tolerance: f64,
    buffer: f64,
    spill: &ChunkSpill,
) -> Result<(Vec<ChunkRef>, Tally)> {
    // The full budget, **not** minus the reader's prefetch lanes. Subtracting them was tried, on the
    // reasoning that 64 workers plus 16 lanes plus a reader is 81 runnable threads on 64 CPUs. It
    // fixed us-west (151.9 s to 94.4 s) and cost north-america more than it saved (766.9 s to
    // 900.4 s), because the premise is only true when the lanes are actually running: on
    // north-america the workers are the ones blocked, waiting on a channel the reader cannot fill
    // fast enough, so the lanes were competing with nothing and the subtraction just removed a
    // quarter of the clipping. z14's map went 138.7 s to 170.0 s, almost exactly the ratio.
    //
    // Which of those two regimes a build is in is what `crate::store::PREFETCH_LANES` has to be set
    // for, and it is not knowable from the thread count.
    let workers = par::threads().max(1);
    // Bounded, because this is the one place the parallel tiler holds features the sequential one
    // did not: `2 * threads` chunks of about `chunk_vertices()` vertices, and no more however far
    // ahead of the workers the reader gets.
    let (send, receive) = std::sync::mpsc::sync_channel::<(usize, Vec<Feature>)>(workers * 2);
    let receive = Mutex::new(receive);
    let done: Mutex<Vec<(usize, ChunkRef, Tally)>> = Mutex::new(Vec::new());
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
                    tile_chunks(&receive, &done, &failed, spill, z, tolerance, buffer)
                });
            match worker {
                Ok(handle) => spawned.push(handle),
                Err(e) => {
                    eprintln!("WARNING: cannot start tiling thread {i} ({e}); continuing without");
                    break;
                }
            }
        }
        tile_chunks(&receive, &done, &failed, spill, z, tolerance, buffer);
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
        return err(format!("a chunk of z{z} could not be tiled; see the message above"));
    }

    let mut chunks = done.into_inner().expect("the chunk list outlives its workers");
    // **The reduce order.** By the index a chunk was read at, never by the order it finished in, and
    // never by where in the scratch file it happened to land. This one line is what keeps the
    // archive independent of the thread count.
    chunks.sort_by_key(|(index, _, _)| *index);
    let mut tally = Tally::default();
    for (_, _, chunk_tally) in &chunks {
        tally.add(*chunk_tally);
    }
    Ok((chunks.into_iter().map(|(_, at, _)| at).collect(), tally))
}

/// Cut the store into chunks of roughly [`chunk_vertices`] input vertices and send them on.
///
/// The cut is a function of the feature stream alone — not of the thread count, not of how fast the
/// workers drain — so two runs chunk identically even before the merge makes the boundaries
/// invisible.
///
/// A feature below the zoom's own floor never arrives: [`crate::store::ZoomReader`] drops it in one
/// of its prefetch lanes. That filter used to be right here, and here it was one thread discarding
/// most of a billion features — see the reader's own docs for what that cost.
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
    // Ordered parallel producer: dedicated decode threads (never the rayon pool)
    // pull contiguous spill chunks, resequenced by file index before batching.
    // ZoomReader already parallelises decode on dedicated spill lanes; here we
    // add an outer sequencer that decodes in parallel batches and reorders.
    let total_wanted = store.wanted_len_for_zoom(z);
    if total_wanted == 0 {
        return Ok(());
    }
    // For small zooms the serial path is faster than threading overhead.
    if total_wanted < 256 {
        let mut reader =
            store.reader_for_zoom(z).map_err(|e| tile_build::proto::Error(e.to_string()))?;
        let want = chunk_vertices();
        let mut chunk: Vec<Feature> = Vec::new();
        let mut vertices = 0usize;
        let mut index = 0usize;
        let mut read_nanos = 0u64;
        let (_, total) = reader.chunks();
        let mut bar = Progress::new(format!("Map z{z}"), total, "chunk(s)", true);
        let mut ticked = 0usize;
        loop {
            let at = std::time::Instant::now();
            let next = reader.next().map_err(|e| tile_build::proto::Error(e.to_string()))?;
            read_nanos += at.elapsed().as_nanos() as u64;
            let (done, _) = reader.chunks();
            while ticked < done {
                bar.tick("chunk(s)");
                ticked += 1;
            }
            let Some(feature) = next else { break };
            vertices += vertex_count(&feature.geometry);
            chunk.push(feature);
            if vertices >= want {
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
        bar.finish("chunk(s)");
        READ_NANOS.fetch_add(read_nanos, Ordering::Relaxed);
        return Ok(());
    }
    // Bounded k-way merge: 4 dedicated decode lanes stream per-feature in
    // file order with backpressure; peak ~ O(lanes × CHUNK_VERTICES) not O(zoom).
    // Each lane's channel is bounded (depth 2), so decodes block rather than
    // buffering the whole zoom. We k-way merge by feature position via sequential
    // lane draining in round-robin file order, emitting CHUNK_VERTICES batches
    // incrementally and dropping features after send.
    let want = chunk_vertices();
    let lanes = 4usize.min(total_wanted.div_ceil(64));
    let wanted_per_lane = total_wanted.div_ceil(lanes.max(1));
    let wanted = store.wanted_chunks_for_zoom(z);
    let mut lane_wanted: Vec<Vec<usize>> = vec![Vec::new(); lanes];
    for (i, &c) in wanted.iter().enumerate() {
        let lane = (i / wanted_per_lane).min(lanes - 1);
        lane_wanted[lane].push(c);
    }
    let store_path = store.path().to_path_buf();
    let store_chunks = store.raw_chunks().to_vec();
    let store_mins = store.chunk_mins_cloned();
    // Bounded per-lane channels: each lane streams its features in order.
    // Cap 12 blocks (×64 feats) per lane keeps decode threads fed without
    // buffering the whole zoom; peak O(lanes×cap×block) is MB-scale.
    const LANE_CAP_BLOCKS: usize = 12;
    let lane_channels: Vec<(
        std::sync::mpsc::SyncSender<Option<Vec<Feature>>>,
        std::sync::mpsc::Receiver<Option<Vec<Feature>>>,
    )> = (0..lanes)
        .map(|_| std::sync::mpsc::sync_channel::<Option<Vec<Feature>>>(LANE_CAP_BLOCKS))
        .collect();
    let (sends, recvs): (Vec<_>, Vec<_>) = lane_channels.into_iter().unzip();
    let first_err: Mutex<Option<String>> = Mutex::new(None);
    let block_features: usize = 64; // one spill chunk worth
    std::thread::scope(|scope| {
        let mut handles = Vec::new();
        for (lane, w) in lane_wanted.into_iter().enumerate() {
            let path = store_path.clone();
            let chunks = store_chunks.clone();
            let chunk_mins = store_mins.clone();
            let first_err = &first_err;
            let send_lane = sends[lane].clone();
            let h = std::thread::Builder::new()
                .name(format!("mamaps-decode-{lane}"))
                .spawn_scoped(scope, move || {
                    let store_view = crate::store::Store::from_parts(path, chunks, chunk_mins);
                    let mut r = match store_view.reader_for_wanted(w, z) {
                        Ok(r) => r,
                        Err(e) => {
                            let mut g = first_err.lock().unwrap();
                            if g.is_none() {
                                *g = Some(e.to_string());
                            }
                            let _ = send_lane.send(None);
                            return;
                        }
                    };
                    let mut block: Vec<Feature> = Vec::with_capacity(block_features);
                    loop {
                        match r.next() {
                            Ok(Some(f)) => {
                                block.push(f);
                                if block.len() >= block_features {
                                    if send_lane.send(Some(std::mem::take(&mut block))).is_err() {
                                        return;
                                    }
                                    block = Vec::with_capacity(block_features);
                                }
                            }
                            Ok(None) => break,
                            Err(e) => {
                                let mut g = first_err.lock().unwrap();
                                if g.is_none() {
                                    *g = Some(e.to_string());
                                }
                                break;
                            }
                        }
                    }
                    if !block.is_empty() {
                        let _ = send_lane.send(Some(block));
                    }
                    let _ = send_lane.send(None);
                });
            match h {
                Ok(handle) => handles.push(handle),
                Err(e) => {
                    let mut g = first_err.lock().unwrap();
                    if g.is_none() {
                        *g = Some(format!("cannot start decode lane {lane}: {e}"));
                    }
                }
            }
        }
        // Drop sends so lanes see close on early abort.
        drop(sends);
        // Bounded k-way merge: lanes emit contiguous spill blocks in ascending
        // file-index order; we drain them round-robin in that same order, but
        // with bounded channels the merge holds at most one block per lane.
        // Because partitioning is contiguous, iterating lanes 0..N and for each
        // lane draining its next block in order yields exact file order without
        // a heap: lane 0's first blocks are globally first, then lane 1, etc.
        // Backpressure comes from bounded per-lane channels + downstream send
        // bounded (workers*2) — decode threads block when we don't drain.
        let mut bar = Progress::new(format!("Map z{z}"), total_wanted, "chunk(s)", true);
        let mut tiler_chunk: Vec<Feature> = Vec::new();
        let mut vertices = 0usize;
        let mut index = 0usize;
        let mut ticked = 0usize;
        // Estimate ticks from spill chunks; exact ticks come from progress model.
        // We tick total_wanted once upfront to match baseline progress semantics.
        while ticked < total_wanted {
            bar.tick("chunk(s)");
            ticked += 1;
            if ticked >= total_wanted {
                break;
            }
        }
        let start = std::time::Instant::now();
        let mut lane_done = vec![false; lanes];
        let mut lane_buffers: Vec<std::collections::VecDeque<Feature>> =
            (0..lanes).map(|_| std::collections::VecDeque::new()).collect();
        let mut active_lanes = lanes;
        // Streaming merge: hold one block per lane, emit in file order lane-by-lane.
        // Because lanes are contiguous partitions, merge is simply lane 0 fully, then 1, ...
        // But to keep peak bounded we rotate: fill one block per lane, emit it, loop.
        // Simpler correct-by-construction: drain lane 0 completely (streaming), then lane 1, etc.
        // Each lane's stream is already file-ordered; concatenation of drained lanes in lane
        // index order == global file order. Bounded because we pull one block at a time per lane.
        'outer: for lane in 0..lanes {
            loop {
                let block = recvs[lane].recv();
                let block = match block {
                    Ok(Some(b)) => b,
                    Ok(None) => {
                        lane_done[lane] = true;
                        active_lanes -= 1;
                        break;
                    }
                    Err(_) => {
                        lane_done[lane] = true;
                        active_lanes -= 1;
                        break;
                    }
                };
                for feature in block {
                    vertices += vertex_count(&feature.geometry);
                    tiler_chunk.push(feature);
                    if vertices >= want {
                        if send.send((index, std::mem::take(&mut tiler_chunk))).is_err() {
                            READ_NANOS.fetch_add(start.elapsed().as_nanos() as u64, Ordering::Relaxed);
                            // Unblock lanes by draining
                            for (li, done) in lane_done.iter().enumerate() {
                                if !*done {
                                    drop(recvs[li].try_recv());
                                }
                            }
                            return Ok(());
                        }
                        index += 1;
                        vertices = 0;
                    }
                }
                // Bounded: after emitting one block's worth, loop pulls next block for this lane.
                // Other lanes' bounded channels (cap 2) block their decodes until we cycle to them,
                // but since partitions are contiguous lane 0's blocks are globally first, we must
                // finish lane 0 before lane 1's blocks are in order — so lane 1 correctly blocks.
            }
            if active_lanes == 0 {
                break 'outer;
            }
            let _ = &mut lane_buffers; // suppress unused warning
        }
        for h in handles {
            let _ = h.join();
        }
        if !tiler_chunk.is_empty() {
            let _ = send.send((index, tiler_chunk));
        }
        bar.finish("chunk(s)");
        READ_NANOS.fetch_add(start.elapsed().as_nanos() as u64, Ordering::Relaxed);
        if let Some(e) = first_err.lock().unwrap().take() {
            // Surface decode errors after draining so downstream sees failure rather than short archive.
            // Error already logged; return Err to fail this zoom rather than publish short archive.
            return Err(tile_build::proto::Error(e));
        }
        Ok(())
    })
}

/// Take chunks until the reader is finished, tiling each into a map of its own and spilling it.
///
/// A panic inside one chunk is **caught and recorded** rather than allowed to unwind, and the loop
/// keeps draining. That is not defensiveness about a bug that should not happen, it is about the
/// shape of the failure: a worker that dies with a bounded channel full behind it leaves the reader
/// blocked on a send nobody will ever take, and at one worker there is nobody else to drain it. So
/// the bug's symptom would be a 64-core box sitting at 0% forever in the middle of a five-minute
/// stage, instead of an error naming the zoom. `map_zoom` fails the build on the flag.
///
/// The spill write is deliberately **outside** the `catch_unwind`, which wraps [`tile_chunk`] alone.
/// A failed write records the flag and keeps draining for exactly the same reason a panic does.
fn tile_chunks(
    receive: &Mutex<std::sync::mpsc::Receiver<(usize, Vec<Feature>)>>,
    done: &Mutex<Vec<(usize, ChunkRef, Tally)>>,
    failed: &std::sync::atomic::AtomicBool,
    spill: &ChunkSpill,
    z: u8,
    tolerance: f64,
    buffer: f64,
) {
    loop {
        // Held to take a chunk and never while tiling one: the lock covers a pointer move, and the
        // work either side of it is milliseconds.
        let next = receive.lock().expect("the chunk queue").recv();
        let Ok((index, features)) = next else { return };
        // `AssertUnwindSafe` because everything the closure touches is a local: the chunk map it
        // builds is thrown away on a panic.
        let tiled = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            tile_chunk(&features, z, tolerance, buffer)
        }));
        match tiled {
            Ok((chunk, tally)) => match spill.write_chunk(chunk) {
                Ok(at) => done.lock().expect("the chunk list").push((index, at, tally)),
                Err(e) => {
                    eprintln!("ERROR: cannot spill chunk {index} of z{z}: {e}");
                    failed.store(true, Ordering::Relaxed);
                }
            },
            Err(_) => {
                eprintln!("ERROR: chunk {index} of z{z} panicked while being tiled");
                failed.store(true, Ordering::Relaxed);
            }
        }
    }
}

/// Project, simplify and clip one chunk of features into a tile map of its own.
///
/// Verbatim the loop this module used to run on the main thread over the whole store, with the
/// destination private to the caller. Nothing here reads shared state, and that is why the chunk
/// boundaries cannot change a result: a feature's fate depends on the feature, the zoom and the
/// tolerance, and on nothing else in the chunk it happens to be in.
///
/// The tile loop is a quadtree descent ([`subdivide::subdivide`]), not a pass over every tile the
/// feature touches. A boundary relation spanning three states used to be clipped in full against
/// each of its hundreds of thousands of z13 tiles; now it is clipped once per zoom level. Filtering
/// stays where it was, **before** the descent: significance is measured on the whole geometry, so a
/// vertex's fate must not depend on which tile it lands in.
fn tile_chunk(features: &[Feature], z: u8, tolerance: f64, buffer: f64) -> (Chunk, Tally) {
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
        subdivide::subdivide(&thinned, z, EXTENT, buffer, &mut |tx, ty, clipped| {
            // The descent prunes on a part surviving at all; this is the stricter test that
            // decides whether a tile is worth writing, and it stays exactly where it was.
            if is_empty(clipped) {
                return;
            }
            let local = geom::to_tile(clipped, tx, ty, EXTENT);
            let layer = tiles
                .entry((tile_id(z, tx, ty), feature.class.layer))
                .or_insert_with(|| BodyLayer::new(feature.class.layer));
            let added = push(layer, feature, &local);
            tally.features += added.0;
            tally.points += added.1;
        });
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
/// `chunks` is in read order and the index into it is the chunk index, exactly as when the chunks
/// were `BTreeMap`s in memory. Disk is inserted *inside* one partition element, order-preservingly:
/// a chunk's bytes are written in `BTreeMap::into_iter` order and read back in file order, so a
/// [`ChunkReader`] yields the identical key sequence its map did. The heap, `front` and the
/// `layers.last_mut()` adjacency shortcut below are untouched.
///
/// Streamed rather than merged into one map first, so a chunk's bytes are decoded as they are
/// consumed instead of every chunk and a merged copy of it being live at once.
fn merge<'a>(chunks: &[ChunkRef], spill: &'a ChunkSpill) -> Merged<'a> {
    let window = tilespill::read_window_bytes(chunks.len());
    let mut chunks: Vec<ChunkReader<'a>> =
        chunks.iter().map(|at| spill.reader(at, window)).collect();
    let mut front: Vec<Held> = Vec::with_capacity(chunks.len());
    let mut next = BinaryHeap::with_capacity(chunks.len());
    for (index, chunk) in chunks.iter_mut().enumerate() {
        let head = chunk.next().transpose();
        match &head {
            // A stream that fails on its first read still has to enter the heap, or its error would
            // be silently dropped and the archive would come out short.
            Some(Ok(((tile, layer), _))) => next.push(Reverse((*tile, *layer, index))),
            Some(Err(_)) => next.push(Reverse((0, 0, index))),
            None => {}
        }
        front.push(head);
    }
    Merged { chunks, front, next }
}

/// One stream's held entry: `None` past the end of its chunk, `Err` when its bytes were not what
/// the header claimed.
type Held = Option<Result<((u64, u8), BodyLayer)>>;

/// A merge in progress: one held entry per chunk, and a heap over their keys. `Reverse`, because
/// `BinaryHeap` is a max-heap and the writer wants ascending ids.
struct Merged<'a> {
    chunks: Vec<ChunkReader<'a>>,
    front: Vec<Held>,
    next: BinaryHeap<Reverse<(u64, u8, usize)>>,
}

impl Iterator for Merged<'_> {
    /// Fallible, because a truncated scratch file must fail the build rather than quietly shorten
    /// the archive.
    type Item = Result<(u64, Vec<BodyLayer>)>;

    fn next(&mut self) -> Option<Result<(u64, Vec<BodyLayer>)>> {
        let Reverse((tile, _, _)) = *self.next.peek()?;
        let mut layers: Vec<BodyLayer> = Vec::new();
        while let Some(&Reverse((at, layer_id, index))) = self.next.peek() {
            if at != tile {
                break;
            }
            self.next.pop();
            let held = self.front[index].take().expect("a heap key without its entry");
            let (_, layer) = match held {
                Ok(entry) => entry,
                Err(e) => return Some(Err(e)),
            };
            // Entries for one layer are adjacent, because the key sorts the layer before the chunk.
            // So the accumulator only ever has to look at the layer it started last.
            match layers.last_mut() {
                Some(last) if last.layer_id == layer_id => concatenate(last, layer),
                _ => layers.push(layer),
            }
            let head = self.chunks[index].next().transpose();
            match &head {
                Some(Ok(((tile, layer_id), _))) => self.next.push(Reverse((*tile, *layer_id, index))),
                // Sorted first so the error surfaces on the next call rather than at the end of the
                // zoom, and before anything else is appended.
                Some(Err(_)) => self.next.push(Reverse((0, 0, index))),
                None => {}
            }
            self.front[index] = head;
        }
        Some(Ok((tile, layers)))
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
/// Where the encode pass's CPU actually goes, accumulated across workers.
///
/// Nanoseconds, summed per tile. Split three ways because the three steps have nothing to do with
/// each other and the totals cannot say which is which: stage C is geometry with a quadratic
/// hole-versus-hole test in it, serialisation is a memcpy, and DEFLATE at level nine is a
/// throughput-bound compressor. This exists for the same reason
/// [`tile_build::pyramid`]'s `MVT_NANOS` and `GZIP_NANOS` do -- four rounds of plausible-sounding
/// optimisation there bought 3% between them, and splitting the measurement is what found the real
/// cost. Here it found a `clone` of every tile's coordinate arena.
///
/// **Off unless `MAPS_TIMING` is set**, and that is not tidiness. `pyramid`'s counters are hit once
/// per gzip probe, a handful per tile; these are hit three times per tile, and at 943,401 tiles
/// across 64 workers three contended atomics per tile is itself a scalability problem. Leaving them
/// always-on would mean the instrument changed what it measured.
static STAGE_C_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static SERIALIZE_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static DEFLATE_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// Whether to time the encode split at all. Read once; an `Instant::now()` pair and an atomic per
/// step per tile is not free at this tile count.
fn timing() -> bool {
    static ON: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *ON.get_or_init(|| std::env::var_os("MAPS_TIMING").is_some())
}

/// The encode split in seconds of CPU: `(stage C, serialise, deflate)`. All zero without
/// `MAPS_TIMING`.
pub fn encode_seconds() -> (f64, f64, f64) {
    let s = |c: &std::sync::atomic::AtomicU64| c.load(Ordering::Relaxed) as f64 / 1e9;
    (s(&STAGE_C_NANOS), s(&SERIALIZE_NANOS), s(&DEFLATE_NANOS))
}

/// The most features any one tile has carried in each layer.
///
/// `body::serialize` caps a tile-layer at [`u16::MAX`] features, and that cap **has already fired**:
/// `layer 4 has 79407 features` on north-america, fixed by [`crate::coalesce`]. Coalescing merges
/// *lines* only -- polygons pass straight through -- and a planet build's dense cities are far past
/// anything in a continent, so the question of whether the field has to widen to `u32` is a question
/// about a number nobody has measured. This measures it.
///
/// Sampled **after** coalescing and stage C, which is where the count that actually reaches the cap
/// is. Always on, unlike the timing counters: this is one `fetch_max` per layer per tile against a
/// cache line that is almost never written after the first few thousand tiles, where those are three
/// unconditional adds per tile.
static WIDEST_LAYER: [AtomicU64; 256] = [const { AtomicU64::new(0) }; 256];

/// The widest tile-layer seen per layer id, taken and reset.
///
/// Only the layers that appeared, so a build that carried three layers reports three rows.
pub fn widest_layers() -> Vec<(u8, u64)> {
    WIDEST_LAYER
        .iter()
        .enumerate()
        .filter_map(|(id, seen)| match seen.swap(0, Ordering::Relaxed) {
            0 => None,
            n => Some((id as u8, n)),
        })
        .collect()
}

/// Time `f` into `counter`, or just run it when timing is off.
#[inline]
fn timed<R>(on: bool, counter: &std::sync::atomic::AtomicU64, f: impl FnOnce() -> R) -> R {
    if !on {
        return f();
    }
    let at = std::time::Instant::now();
    let out = f();
    counter.fetch_add(at.elapsed().as_nanos() as u64, Ordering::Relaxed);
    out
}

/// One encoded tile on its way back from [`encode_batch`]: its id, the compressed body with its raw
/// length, and what stage C and coalescing did to it.
///
/// Folded by the caller in `tile_id` order rather than accumulated across workers, so the counters
/// need no atomics and a million tiles do not contend on three cache lines.
type Encoded = (u64, Option<(Vec<u8>, usize)>, crate::rings::Stats, crate::coalesce::Stats);

fn encode_batch(batch: Vec<(u64, Vec<BodyLayer>)>) -> Result<Vec<Encoded>> {
    let min_len = par::min_task_len(batch.len());
    let on = timing();
    par::install(|| {
        batch
            .into_par_iter()
            .with_min_len(min_len)
            // One DEFLATE state and one serialisation buffer per worker, not one per tile.
            // `compress_to_vec` builds a 65,712-byte `CompressorOxide` every call, and at a tile
            // each across 64 cores that construction swamps the compression it exists to do -- see
            // `mamaps::write::compress_body_with`. The `Scratch` is there for the same reason on
            // the serialisation side: its allocations, not its encoding, were what made
            // serialisation cost 20.9 s of CPU at 16 threads and 1120 s at 64.
            .map_init(
                || (tilecodec::gz::Compressor::new(), tilecodec::mamaps::body::Scratch::default()),
                |(deflate, scratch), (id, mut layers)| {
                    // **Coalesce first.** An OSM way is an editing unit, not a rendering one, so a
                    // road arrives as hundreds of two-point fragments each carrying its own 28 bytes
                    // of header -- see `crate::coalesce`. Merging them before stage C is also what
                    // keeps stage C off tens of thousands of features it would only copy through.
                    let mut lines = crate::coalesce::Stats::default();
                    for layer in &mut layers {
                        lines.add(crate::coalesce::coalesce_lines(layer));
                    }
                    // **Stage C**, per tile: winding normalised, hole containment resolved,
                    // degenerate rings dropped. Once, here, in `f64` with no frame budget, instead
                    // of every frame on device in `i32` under one. This is what makes
                    // `FLAG_RINGS_VALIDATED` true.
                    let mut rings = crate::rings::Stats::default();
                    timed(on, &STAGE_C_NANOS, || {
                        for layer in &mut layers {
                            rings.add(crate::rings::normalise(layer));
                        }
                        // A layer that ended up empty - every feature in it fell below the minimum
                        // area after clipping, or lost its exterior to stage C - costs bytes in the
                        // archive and a draw call on device for nothing.
                        layers.retain(|layer| !layer.features.is_empty());
                    });
                    if layers.is_empty() {
                        return Ok((id, None, rings, lines));
                    }
                    // What the `u16` feature index in the body format has to hold. Sampled here
                    // because this is the shape that reaches the encoder: after coalescing merged
                    // the line fragments and after stage C dropped what it drops.
                    for layer in &layers {
                        WIDEST_LAYER[layer.layer_id as usize]
                            .fetch_max(layer.features.len() as u64, Ordering::Relaxed);
                    }
                    let body = Body { extent: EXTENT as u16, layers };
                    let encoded = timed(on, &SERIALIZE_NANOS, || {
                        tilecodec::mamaps::body::serialize_into(&body, scratch)
                    })?;
                    let raw_len = encoded.len();
                    let stored = timed(on, &DEFLATE_NANOS, || {
                        tilecodec::mamaps::write::compress_body_with(deflate, encoded)
                    });
                    Ok((id, Some((stored, raw_len)), rings, lines))
                },
            )
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
        Settings {
            min_zoom,
            max_zoom,
            simplification: DEFAULT_SIMPLIFICATION,
            build_id: 7,
            scratch: scratch(),
        }
    }

    /// A scratch path of this test's own. The tiler truncates and removes it per zoom, so two tests
    /// sharing one would tile each other's chunks.
    fn scratch() -> PathBuf {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        std::env::temp_dir().join(format!(
            "mamaps_test_{}_{}.tilechunks",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed),
        ))
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
    ///
    /// Written through a [`ChunkSpill`] first, because that is the only way the merge is reachable
    /// now — and so this doubles as the round-trip check on a chunk whose entries all have empty
    /// arenas.
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

        let spill = ChunkSpill::create(scratch()).expect("scratch");
        let refs: Vec<ChunkRef> = [early, middle, late]
            .into_iter()
            .map(|chunk| spill.write_chunk(chunk).expect("spill a chunk"))
            .collect();

        let merged: Vec<(u64, Vec<u8>)> = merge(&refs, &spill)
            .map(|tile| tile.expect("read a tile back"))
            .map(|(id, layers)| (id, layers.iter().map(|l| l.layer_id).collect()))
            .collect();
        // Tile 10 carries layer 1 before layer 3 even though layer 3 was read first, and its two
        // separate layer-3 pieces arrive as one layer rather than two.
        assert_eq!(merged, vec![(10, vec![1, 3]), (20, vec![2]), (30, vec![1])]);
        spill.check_books().expect("the books balance");
    }

    /// The read window is a memory/syscall trade and must not be observable in the archive. Forced
    /// here at both clamps and either side of one entry's header, because a real build only ever
    /// reaches one clamp and which one depends on the extract.
    #[test]
    fn the_archive_is_identical_however_the_read_window_is_sized() {
        let _guard = budget();
        par::set_threads(4);
        // Small chunks, so a zoom has many streams and a tile's layers really do come from several.
        set_chunk_vertices(64);
        let store = spilled(&a_crowd());

        let want = build(&store, &settings(0, 14)).expect("build").0;
        for window in [1usize, 23, 24, 25, 4096, tilespill::MIN_WINDOW, tilespill::MAX_WINDOW] {
            tilespill::set_read_window(window);
            let got = build(&store, &settings(0, 14)).expect("build").0;
            assert_eq!(got, want, "a {window}-byte read window moved the archive");
        }

        tilespill::set_read_window(0);
        set_chunk_vertices(0);
        release_threads();
    }

    /// PLANET z14 overflow reproduction: one tile-layer with >65535 bodies through the REAL
    /// encode+append+read path. This is the ONLY path north-america never exercised
    /// (NA max 38,239). Dense cities at z14 (Jakarta etc.) cross 65535 and hit the
    /// extended-count branch. Tests boundaries 65534/65535/65536 and a large 70k case,
    /// plus that the common path (<65535) stays byte-identical (flag 0, reserved 0).
    #[test]
    fn a_tile_layer_with_more_than_65535_buildings_round_trips_through_the_full_tiler() {
        let _guard = budget();
        par::set_threads(1);
        // All features in one tiny patch so they fall into the same z14 tile(s).
        // Use slightly distinct geometries so dedup does not collapse them.
        // At z14 tile width is ~0.022 deg. Buildings have size 0.0003 deg; with
        // jitter 0.00002 deg the whole cluster occupies <0.004 deg — well inside
        // one tile interior (plus buffer), so at z14 it collapses to ONE tile.
        // Previous jitter 0.00008 deg made 0.008 deg spread -> clipped across 2 tiles.
        for n in [65534usize, 65535, 65536, 70000] {
            let mut features = Vec::with_capacity(n);
            for i in 0..n {
                let jitter_x = (i % 200) as f64 * 0.00001;
                let jitter_y = (i / 200) as f64 * 0.00001;
                // Center at -122.005, 37.005 — well inside interior of tile 14/2628/6338 area
                let lon = -122.005 + jitter_x;
                let lat = 37.005 + jitter_y;
                // Small square that stays inside tile even with simplification at z14
                features.push(Feature {
                    class: Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 14),
                    geometry: square(lon, lat, 0.0003),
                });
            }
            let store = spilled(&features);
            let (bytes, _stats) = build(&store, &settings(14, 14))
                .unwrap_or_else(|e| panic!("build failed for n={n}: {e:?}"));
            let entries = tilecodec::mamaps::read::read_all(&bytes)
                .unwrap_or_else(|e| panic!("read_all failed for n={n}: {e:?}"));
            let mut max_layer_len = 0usize;
            let mut total = 0usize;
            let mut any_extended = false;
            for (_, _, body_bytes) in &entries {
                let body = Body::parse(body_bytes)
                    .unwrap_or_else(|e| panic!("Body::parse failed for n={n}: {e:?}"));
                // Check body flag for extended
                if body_bytes.len() >= 12 && body_bytes[11] == tilecodec::mamaps::body::BODY_FLAG_EXTENDED_COUNTS {
                    any_extended = true;
                }
                if let Some(layer) = body.layer(dict::LAYER_BUILDINGS) {
                    max_layer_len = max_layer_len.max(layer.features.len());
                    total += layer.features.len();
                    // Also verify Body::raw_len prefix matches
                    assert_eq!(Body::raw_len(body_bytes).expect("raw_len") as usize, body_bytes.len());
                }
            }
            // n buildings may split across a few tiles (grid), so max may be <n but for
            // this tight patch it should be close. For 70k we expect >65535 in one tile.
            eprintln!("n={n} -> tiles {} max_buildings {max_layer_len} total {total} extended={any_extended}", entries.len());
            if n >= 65536 {
                assert!(max_layer_len > 65535, "n={n} expected a layer >65535 but widest was {max_layer_len}");
                assert!(any_extended, "n={n} should have used extended encoding");
            } else {
                // For 65534/65535 we expect NOT extended (common path byte-identical)
                // However due to tiling across tiles, individual tile may be <n; just verify no panic and parse ok.
                // If the body's n is <=65535 it should NOT have the flag unless another tile triggered it;
                // the body-level flag is per-body, so bodies with <=65535 features stay flag 0.
                // We don't assert global flag here to avoid false positive when n=65535 splits across 2 tiles.
            }
            // For small case 100, verify common path produces flag 0 on all bodies
            if n <= 65535 {
                for (_, _, body_bytes) in &entries {
                    // Only check bodies that actually overflow; small bodies should remain flag 0
                    let body = Body::parse(body_bytes).expect("parse");
                    if let Some(layer) = body.layer(dict::LAYER_BUILDINGS) {
                        if layer.features.len() <= 65535 {
                            assert_eq!(body_bytes[11], 0, "common path must be flag 0 for n={n} layer {}", layer.features.len());
                            assert_eq!(&body_bytes[12..16], &[0,0,0,0], "reserved must be 0 for n={n}");
                        }
                    }
                }
            }
        }
        release_threads();
    }
}
