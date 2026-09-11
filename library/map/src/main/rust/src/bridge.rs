//! The JNI surface: a small, fixed set of entry points, and nothing per-feature or
//! per-vertex.
//!
//! Kotlin creates and destroys the renderer for a `Surface`, resizes it, tells it whether
//! the device is online, and hands it **one camera snapshot per frame**. Tile selection,
//! fetching, decode, tessellation and drawing all happen on this side, so the boundary is
//! crossed a handful of times a frame rather than thousands.
//!
//! # Threading
//!
//! `render` is called from Kotlin's `Choreographer` callback, so on the main thread; every
//! Vulkan call happens there and the renderer is never driven from two threads at once.
//! Tile work — the range fetch, the gzip inflate, the MVT decode and the tessellation —
//! runs on a worker thread and hands finished meshes back through a channel, which
//! `render` drains. That is the split the plan asks for: the expensive half off the
//! critical path, and no JNI in the hot loop.

use crate::camera::Camera;
use crate::marker::Marker;
use crate::overlay::{RouteSegment, RouteStyle};
use crate::style::{self, Layer, LayerToggles, Palette, SharedToggles};
use crate::tile::cache::{RangeCache, DEFAULT_MAX_BYTES};
use crate::tile::geometry::{self, TileMesh};
use crate::tile::select::{self, TileId};
use crate::tile::source::{
    basemap_origin, retry_delay_ms, CachingRangeReader, JniRangeFetcher, BASEMAP_ARCHIVE_URL,
};
use crate::vulkan::context::{ANativeWindow_acquire, ANativeWindow_fromSurface};
use crate::vulkan::renderer::{Renderer, UserPuck};
use jni::objects::{JClass, JFloatArray, JIntArray, JLongArray, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jlong};
use jni::JNIEnv;
use std::collections::{HashMap, HashSet};
use std::os::raw::c_void;
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex};
use tilecodec::mamaps::MamapsArchive;

/// The archive's zoom range, shared with the worker that reads it out of the header.
///
/// Atomics rather than a `Mutex` because `render` reads this every frame, and it must be
/// read live rather than copied once: the range is not known until the header has been
/// fetched, which is a network round trip *after* the surface exists. Copying it at startup
/// is how this was wrong before — it captured the guess and never corrected it, so the
/// renderer asked for a zoom level the archive does not contain and got nothing back.
struct ZoomRange {
    min: std::sync::atomic::AtomicU8,
    max: std::sync::atomic::AtomicU8,
}

impl ZoomRange {
    /// The published archive is z0-15. A wrong guess here is self-correcting once the
    /// header lands, but starting at the truth means the first frames are right too.
    fn unknown() -> ZoomRange {
        ZoomRange {
            min: std::sync::atomic::AtomicU8::new(0),
            max: std::sync::atomic::AtomicU8::new(15),
        }
    }

    fn set(&self, min: u8, max: u8) {
        self.min.store(min, std::sync::atomic::Ordering::Relaxed);
        self.max.store(max, std::sync::atomic::Ordering::Relaxed);
    }

    fn get(&self) -> (u8, u8) {
        (
            self.min.load(std::sync::atomic::Ordering::Relaxed),
            self.max.load(std::sync::atomic::Ordering::Relaxed),
        )
    }
}

/// How many tile workers run in parallel.
///
/// Each tile costs one or two **sequential** range requests — a leaf directory, then the
/// body — so its cost is dominated by round-trip latency, not CPU. One worker serialises
/// every tile behind every other: the archive probe measured a 24-tile screenful taking ~15
/// seconds that way, which is exactly the "really slow to change which elements load"
/// symptom. Four overlaps the waiting without opening enough sockets to matter.
const WORKER_COUNT: usize = 4;

/// The most tiles allowed resident on the GPU at once.
///
/// Eviction is the only thing bounding tile memory — there is no separate budget — so this is a
/// real limit rather than a safety net. It matters more now that descendants are kept: an
/// ancestor set is linear in depth, but a descendant set is not, and without a cap every deep
/// tile visited would stay resident for as long as the camera sat above it.
///
/// Sized from what the renderer reports rather than from theory. A dense z14 screenful logged
/// ~2.0 M triangles across 7 tiles, so a tile in a city centre costs single-digit megabytes of
/// vertex and index buffers; 64 leaves room for a ~12-tile viewport, its four ancestor levels and
/// a useful spread of descendants, while keeping the worst case in the hundreds of megabytes
/// rather than the gigabytes an uncapped depth-2 fan-out could reach. Going over drops
/// descendants first, so the cost of being wrong here is a briefly coarser zoom-out, not a blank
/// screen.
const RESIDENT_TILE_CAP: usize = 64;

/// How many finished tiles may be uploaded in one frame.
///
/// The drain used to be unbounded, so however many tiles the workers happened to finish between
/// two frames all landed in the next one. Uploading a tile is around a hundred `vkAllocateMemory`
/// calls, on the Choreographer callback, and a burst of them is what the frame-time tail is made
/// of. What is left stays in the channel and is picked up next frame — nothing is dropped, and
/// the tile is not re-requested, because its key is only removed from `in_flight` once it is
/// actually drained.
///
/// Four is a deliberate middle: a screenful is a couple of dozen tiles, so a cold pan fills in
/// over roughly ten frames, which is a fraction of the fetch and decode latency that preceded it
/// and so is not visible. Lower would start to look like a trickle; higher gives the tail back.
const UPLOADS_PER_FRAME: usize = 4;

/// What a worker reports back about a tile.
enum TileResult {
    /// Tessellated and ready to upload.
    Ready(TileMesh),
    /// The archive genuinely does not contain it — ordinary off the edge of coverage.
    /// Never retried.
    Absent,
    /// The fetch or decode failed. **Must** clear the in-flight marker so it can be tried
    /// again: leaving it set meant one transient network error blanked that tile for the
    /// rest of the session.
    Failed,
}

/// Everything one map surface owns. Handed to Kotlin as an opaque `jlong`.
struct MapHandle {
    renderer: Renderer,
    layers: &'static [Layer],
    /// Meshes finished by the workers, waiting to be uploaded on the render thread.
    finished: Receiver<(u64, TileResult)>,
    /// Tiles the workers should fetch.
    wanted: Sender<TileId>,
    /// Requested but not yet arrived, so a tile is not asked for sixty times a second
    /// while it is in flight.
    in_flight: HashSet<u64>,
    /// Tiles the archive does not contain. Remembered so they are not re-requested every
    /// frame forever — most of a coastal viewport is ocean.
    absent: HashSet<u64>,
    /// Tiles whose fetch or decode failed, as `key -> (consecutive failures, earliest retry)`.
    ///
    /// [`TileResult::Failed`] deliberately does not mark a tile [`TileResult::Absent`], so it
    /// is tried again — but with nothing recording *when*, "again" meant on the very next
    /// frame, and a tile that keeps failing was re-requested sixty times a second for as long
    /// as it stayed visible. This is the missing half: the same retry, at
    /// [`retry_delay_ms`](crate::tile::source::retry_delay_ms) intervals.
    ///
    /// `Instant`, not the camera's `time_seconds`, because that clock wraps hourly and a
    /// deadline across a wrap would either fire an hour early or an hour late.
    ///
    /// Cleared per tile on success. Bounded in practice the same way [`absent`](Self::absent)
    /// is: it holds one small entry per distinct tile that has actually failed this session.
    retry: HashMap<u64, (u32, std::time::Instant)>,
    online: Arc<OnlineFlag>,
    /// Light or dark. Switching costs nothing: colour is a push constant and the layer set
    /// is identical, so no tile is re-tessellated or re-uploaded.
    /// Light or dark, muted or not. Switching costs nothing: colour is a push constant and
    /// the layer set is identical, so no tile is re-tessellated or re-uploaded.
    palette: Palette,
    /// Which optional layers (POI, transit) are on, plus the generation that identifies
    /// them. Shared with the tile workers, which gate tessellation on it and stamp every
    /// mesh they build with the generation they read.
    toggles: Arc<SharedToggles>,
    /// Read live every frame, because the worker only learns it after fetching the header.
    zoom_range: Arc<ZoomRange>,
    /// Frames drawn, for the once-a-second diagnostic log.
    frames: u32,
    /// Last frame's density (device px per Dp), for the task-17 pick path's
    /// Dp→device-px conversion. Written every render call.
    density: f32,
}

/// Shared so Kotlin's connectivity callback can reach the reader on the worker thread.
struct OnlineFlag(std::sync::atomic::AtomicBool);

impl OnlineFlag {
    fn set(&self, online: bool) {
        self.0.store(online, std::sync::atomic::Ordering::Relaxed);
    }
    fn get(&self) -> bool {
        self.0.load(std::sync::atomic::Ordering::Relaxed)
    }
}

/// Create the renderer for `surface`. Returns 0 on failure, having logged why.
///
/// # Safety
///
/// Called from the JVM with a live `Surface`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_create<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    surface: JObject<'l>,
    cache_dir: JString<'l>,
    width: jint,
    height: jint,
    dark: jboolean,
    muted: jboolean,
    archive_path: JString<'l>,
) -> jlong {
    // The bridge back to `:library:network` has to be resolved before any worker thread
    // needs it; doing it here means the failure is visible at startup rather than as a
    // silently blank map. For a local file archive it is not used but still initialised;
    // a missing bridge is not a file-archive failure.
    if !jni_http::init(&mut env) {
        log("library:network is missing, so remote tiles cannot be fetched");
        // Continue — a file:// archive does not need the HTTP bridge.
    }

    let cache_dir: String = match env.get_string(&cache_dir) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let archive_path: String = if archive_path.is_null() {
        String::new()
    } else {
        match env.get_string(&archive_path) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        }
    };

    let window = unsafe {
        let raw_env = env.get_raw() as *mut c_void;
        let raw_surface = surface.as_raw() as *mut c_void;
        ANativeWindow_fromSurface(raw_env, raw_surface)
    };
    if window.is_null() {
        log("ANativeWindow_fromSurface returned null");
        return 0;
    }
    unsafe { ANativeWindow_acquire(window) };

    let renderer = match unsafe {
        Renderer::new(
            window,
            width.max(1) as u32,
            height.max(1) as u32,
            std::path::Path::new(&cache_dir),
        )
    } {
        Ok(r) => r,
        Err(e) => {
            log(&format!("Vulkan init failed: {e}"));
            // The renderer never took ownership, so the window is released here.
            unsafe { crate::vulkan::context::ANativeWindow_release(window) };
            return 0;
        }
    };

    let online = Arc::new(OnlineFlag(std::sync::atomic::AtomicBool::new(true)));
    let (wanted_tx, wanted_rx) = std::sync::mpsc::channel::<TileId>();
    let (finished_tx, finished_rx) = std::sync::mpsc::channel::<(u64, TileResult)>();

    let zoom_range = Arc::new(ZoomRange::unknown());
    // Both optional layers start off: the five existing consumers never asked for POI
    // icons or transit lines, and defaulting them on would make every one of them pay
    // for shaping labels it does not draw.
    let toggles = Arc::new(SharedToggles::new(LayerToggles::default()));
    // One `Receiver` shared by every worker, so whichever is free takes the next tile.
    let queue = Arc::new(Mutex::new(wanted_rx));
    match normalize_local_archive_path(&archive_path) {
        ArchiveSource::Default => {
            for index in 0..WORKER_COUNT {
                spawn_worker(
                    index,
                    BASEMAP_ARCHIVE_URL.to_string(),
                    cache_dir.clone(),
                    queue.clone(),
                    finished_tx.clone(),
                    online.clone(),
                    zoom_range.clone(),
                    toggles.clone(),
                );
            }
        }
        ArchiveSource::RemoteUrl(url) => {
            log(&format!("using remote archive override {}", url));
            for index in 0..WORKER_COUNT {
                spawn_worker(
                    index,
                    url.clone(),
                    cache_dir.clone(),
                    queue.clone(),
                    finished_tx.clone(),
                    online.clone(),
                    zoom_range.clone(),
                    toggles.clone(),
                );
            }
        }
        ArchiveSource::LocalFile(path) => {
            log(&format!("using local archive {}", path.display()));
            for index in 0..WORKER_COUNT {
                spawn_file_worker(
                    index,
                    Some(path.clone()),
                    queue.clone(),
                    finished_tx.clone(),
                    zoom_range.clone(),
                    toggles.clone(),
                );
            }
        }
    }

    let handle = Box::new(MapHandle {
        renderer,
        layers: style::layers(),
        finished: finished_rx,
        wanted: wanted_tx,
        in_flight: HashSet::new(),
        absent: HashSet::new(),
        retry: HashMap::new(),
        online,
        palette: Palette::new(dark != 0, muted != 0),
        toggles,
        zoom_range,
        frames: 0,
        density: 1.0,
    });
    Box::into_raw(handle) as jlong
}

/// A worker: opens its own view of the archive, then serves tile requests until the queue
/// closes.
///
/// Each worker holds its **own** [`MamapsArchive`], so its leaf-index cache and range
/// reads need no lock. The cost is one 16 KB header fetch per worker at startup and a
/// duplicated leaf cache; the alternative — one archive behind a mutex — would serialise
/// exactly the round trips this is meant to overlap. The on-disk range cache is shared, and
/// is safe to share because every entry is written temp-then-renamed.
#[allow(clippy::too_many_arguments)]
fn spawn_worker(
    index: usize,
    archive_url: String,
    cache_dir: String,
    queue: Arc<Mutex<Receiver<TileId>>>,
    finished: Sender<(u64, TileResult)>,
    online: Arc<OnlineFlag>,
    zoom_range: Arc<ZoomRange>,
    toggles: Arc<SharedToggles>,
) {
    let started = std::thread::Builder::new()
        .name(format!("map-tiles-{index}"))
        .spawn(move || {
            // Unchecked: the origin marker includes the archive's `build_id`, which is in the
            // header, which is read through this cache. Checked below, once, when it is known.
            let cache = RangeCache::open_unchecked(cache_dir, DEFAULT_MAX_BYTES);
            let reader = CachingRangeReader::new(archive_url.clone(), cache, JniRangeFetcher);
            reader.set_online(online.get());

            let mut archive = match MamapsArchive::open(reader) {
                Ok(a) => a,
                Err(e) => {
                    log(&format!("worker {index} cannot open the mamaps archive: {e}"));
                    return;
                }
            };
            // Now the `build_id` is known, so the cache can be told which build it holds. A
            // republish under the same name wipes it here rather than serving byte offsets from
            // the build before.
            archive
                .reader()
                .reset_origin(&basemap_origin(&archive_url, archive.header.build_id));
            // Publish the real range. Until this lands the renderer works from a guess, and
            // a guess that is too high asks for a zoom the archive does not contain and
            // silently gets nothing back.
            zoom_range.set(archive.header.min_zoom, archive.header.max_zoom);
            let layers = style::layers();
            // Read once from the header rather than per tile: it is a property of the archive.
            let rings_validated = archive.header.rings_validated();

            loop {
                // Hold the queue lock only long enough to take one tile, never across the
                // fetch — otherwise the workers would run strictly in turn.
                let next = match queue.lock() {
                    Ok(guard) => guard.recv(),
                    Err(_) => return,
                };
                let Ok(tile) = next else { return };
                let key = tile.key();

                // Read per tile, not once per worker: a toggle change has to reach the
                // very next tile built, and all three parts come from one snapshot so the
                // stamp can never describe different flags than the mesh was built with.
                let (enabled, kinds, generation) = toggles.get();
                let result = match archive.tile(tile.z, tile.x, tile.y) {
                    Ok(Some(body)) => TileResult::Ready(geometry::build_toggled(
                        &body,
                        layers,
                        tile.z,
                        tile.x,
                        tile.y,
                        rings_validated,
                        enabled,
                        &kinds,
                        generation,
                    )),
                    Ok(None) => TileResult::Absent,
                    Err(e) => {
                        log(&format!("tile {}/{}/{} failed: {e}", tile.z, tile.x, tile.y));
                        TileResult::Failed
                    }
                };
                // A closed receiver means the surface went away mid-decode.
                if finished.send((key, result)).is_err() {
                    return;
                }
            }
        })
        .is_ok();
    if !started {
        log(&format!("cannot start tile worker {index}"));
    }
}

fn spawn_file_worker(
    index: usize,
    path: Option<std::path::PathBuf>,
    queue: Arc<Mutex<Receiver<TileId>>>,
    finished: Sender<(u64, TileResult)>,
    zoom_range: Arc<ZoomRange>,
    toggles: Arc<SharedToggles>,
) {
    let Some(path) = path else {
        // Empty archive_path == remote fallback already handled by caller printing a log,
        // but keep symmetry for direct callers.
        return;
    };
    let started = std::thread::Builder::new()
        .name(format!("map-tiles-file-{index}"))
        .spawn(move || {
            let reader = match crate::tile::source::FileRangeReader::open(&path) {
                Ok(r) => r,
                Err(e) => {
                    log(&format!("file worker {index} cannot open {}: {e}", path.display()));
                    return;
                }
            };
            let mut archive = match MamapsArchive::open(reader) {
                Ok(a) => a,
                Err(e) => {
                    log(&format!("file worker {index} cannot open mamaps archive {}: {e}", path.display()));
                    return;
                }
            };
            zoom_range.set(archive.header.min_zoom, archive.header.max_zoom);
            let layers = style::layers();
            let rings_validated = archive.header.rings_validated();
            loop {
                let next = match queue.lock() {
                    Ok(guard) => guard.recv(),
                    Err(_) => return,
                };
                let Ok(tile) = next else { return };
                let key = tile.key();
                let (enabled, kinds, generation) = toggles.get();
                let result = match archive.tile(tile.z, tile.x, tile.y) {
                    Ok(Some(body)) => TileResult::Ready(geometry::build_toggled(
                        &body, layers, tile.z, tile.x, tile.y, rings_validated, enabled, &kinds,
                        generation,
                    )),
                    Ok(None) => TileResult::Absent,
                    Err(e) => {
                        log(&format!("file tile {}/{}/{} failed: {e}", tile.z, tile.x, tile.y));
                        TileResult::Failed
                    }
                };
                if finished.send((key, result)).is_err() {
                    return;
                }
            }
        })
        .is_ok();
    if !started {
        log(&format!("cannot start file tile worker {index}"));
    }
}

enum ArchiveSource {
    Default,
    RemoteUrl(String),
    LocalFile(std::path::PathBuf),
}

fn normalize_local_archive_path(raw: &str) -> ArchiveSource {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return ArchiveSource::Default;
    }
    let stripped = if let Some(rest) = trimmed.strip_prefix("file://") {
        rest
    } else {
        trimmed
    };
    let stripped = stripped.trim();
    if stripped.is_empty() {
        return ArchiveSource::Default;
    }
    if stripped.starts_with("http://") || stripped.starts_with("https://") {
        return ArchiveSource::RemoteUrl(stripped.to_string());
    }
    if stripped.contains("://") {
        return ArchiveSource::Default;
    }
    let looks_local = stripped.starts_with('/')
        || stripped.starts_with("C:\\")
        || stripped.starts_with("C:/")
        || stripped.starts_with("/sdcard")
        || stripped.starts_with("/data/")
        || stripped.starts_with("/storage/");
    if looks_local {
        return ArchiveSource::LocalFile(std::path::PathBuf::from(stripped));
    }
    ArchiveSource::Default
}

/// Draw one frame from a camera snapshot. Returns false if the frame was skipped.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_render<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    center_lon: jfloat,
    center_lat: jfloat,
    zoom: jfloat,
    bearing: jfloat,
    pitch: jfloat,
    width_dp: jfloat,
    height_dp: jfloat,
    density: jfloat,
    frame_time_nanos: jlong,
) -> jboolean {
    let Some(map) = handle_mut(handle) else { return 0 };

    // The camera zoom crosses the boundary untouched. MapLibre parity is
    // `camera::TILE_SIZE` being 512, the convention the archives are authored on, so
    // tile addressing is the plain floor of this zoom and every style ramp is
    // evaluated at the zoom the authored `basemap.json` meant by it. This was
    // previously a 256 grid with a +1 offset applied here, which reached the same
    // ground scale but fetched z+1 tiles — four times as many as MapLibre for the
    // same screenful — and read every ramp one level deep.
    //
    // `bearing` is degrees clockwise from north for whatever points up the screen: 0 on
    // every phone frame, and the car's heading during heading-up navigation.
    //
    // `pitch` is the tilt away from straight-down, clamped to the renderer's supported band
    // here so no matrix has to defend against a wild value. `frame_time_nanos` is the host's
    // Choreographer clock, reduced modulo an hour before it becomes an `f32` so a long uptime
    // does not blow past the ~7 significant digits an `f32` has and coarsen the animation clock
    // to tens of milliseconds — a once-an-hour wrap is invisible to the periodic effects that
    // read it.
    let camera = Camera {
        center_lon: center_lon as f64,
        center_lat: center_lat as f64,
        zoom: zoom as f64,
        width_dp,
        height_dp,
        density,
        bearing_deg: bearing as f64,
        pitch_deg: (pitch as f64).clamp(0.0, crate::camera::PITCH_MAX_DEG),
        time_seconds: ((frame_time_nanos.rem_euclid(crate::camera::CLOCK_WRAP_NANOS)) as f64 / 1_000_000_000.0) as f32,
    };
    // Task-17 pick needs the frame's density for Dp→device-px; remember it.
    map.density = density;

    // Upload whatever the workers finished, up to `UPLOADS_PER_FRAME`. Doing it here rather than
    // on a worker keeps every Vulkan call on one thread; bounding it keeps a burst of finished
    // tiles from landing in a single frame.
    let mut uploads = 0usize;
    while uploads < UPLOADS_PER_FRAME {
        let Ok((key, result)) = map.finished.try_recv() else { break };
        map.in_flight.remove(&key);
        match result {
            TileResult::Ready(mesh) => {
                uploads += 1;
                // It arrived, so whatever was failing has stopped. Anything else would leave a
                // tile that recovered still carrying a ten-second backoff for the session.
                map.retry.remove(&key);
                if let Err(e) = map.renderer.upload(key, &mesh) {
                    log(&format!("uploading a tile failed: {e}"));
                }
            }
            // Remembered, so a mostly-ocean viewport does not re-request the same empty
            // tiles every frame for the life of the surface.
            TileResult::Absent => {
                map.absent.insert(key);
                map.retry.remove(&key);
            }
            // Deliberately not recorded as absent: clearing `in_flight` above is what lets it
            // be tried again, which is the whole point of distinguishing this from `Absent`.
            // What is recorded is *when* — without a deadline the retry lands on the very next
            // frame, so a tile that keeps failing is re-requested sixty times a second and the
            // in-flight set never empties, which both storms the network and stops the
            // on-demand frame loop ever idling.
            TileResult::Failed => {
                let attempts = map.retry.get(&key).map_or(0, |(n, _)| *n).saturating_add(1);
                let wait = std::time::Duration::from_millis(retry_delay_ms(attempts));
                map.retry.insert(key, (attempts, std::time::Instant::now() + wait));
            }
        }
    }

    // Keep the visible tiles plus any ancestor of one that we already have, but **fetch
    // only the visible tiles**. An ancestor is a fallback for a tile still in flight, so
    // it is only worth drawing if it is already resident — fetching one spends a round trip
    // to show a blurrier version of a tile that is being fetched anyway, and because
    // ancestors sort first it spent that latency before requesting what the user is
    // actually looking at.
    //
    // `visible` goes to `retain` as well as to the fetch loop, because the other half of the
    // fallback — already-resident *descendants*, which are what stops a zoom-out blanking the
    // map — cannot be named in a keep list without enumerating tiles that were never fetched.
    let (min_zoom, max_zoom) = map.zoom_range.get();
    // A tile is "had" only if it was tessellated at the current toggle generation, so a
    // toggle change re-requests the resident set through this same loop rather than
    // needing a path of its own. The stale mesh keeps drawing until its replacement
    // arrives.
    let (_, _, generation) = map.toggles.get();
    let visible = select::visible(&camera, min_zoom, max_zoom);
    let keep: Vec<u64> =
        select::resident_set(&camera, min_zoom, max_zoom).iter().map(|t| t.key()).collect();
    let now = std::time::Instant::now();
    for tile in &visible {
        let key = tile.key();
        if map.renderer.has_tile(key, generation)
            || map.absent.contains(&key)
            // Still inside its backoff after a failure. Left in `retry` rather than removed
            // here, so the attempt count keeps climbing if it fails again.
            || map.retry.get(&key).is_some_and(|(_, at)| now < *at)
            || !map.in_flight.insert(key)
        {
            continue;
        }
        // A closed channel means every worker died; the map keeps drawing what it has.
        let _ = map.wanted.send(*tile);
    }
    // Drop backoffs for tiles that are no longer visible. Not just housekeeping: an entry whose
    // deadline has passed but which nothing re-requests would make `nextFrameDelayMillis`
    // answer "draw now" forever, spinning the on-demand loop at 60fps for a tile that is off
    // screen. Only the visible set is ever fetched, so only the visible set may hold a backoff.
    //
    // Linear rather than a `HashSet` of the visible keys, deliberately: this runs per frame,
    // `retry` is empty in the ordinary case (so the closure never runs), and a viewport is a
    // couple of dozen tiles. Building a set here would allocate every frame to save nothing.
    if !map.retry.is_empty() {
        map.retry.retain(|key, _| visible.iter().any(|t| t.key() == *key));
    }
    map.renderer.retain(&keep, &visible, RESIDENT_TILE_CAP);

    // Once a second, state what the renderer actually has. Every bug in this file so far has
    // been invisible from the outside: a viewport nobody measured, a zoom level the archive
    // does not contain, a tile stuck in flight forever. All of them would have been one line
    // of this away.
    map.frames += 1;
    if map.frames % 60 == 0 {
        let (tiles, meshes, draws, triangles) = map.renderer.stats();
        let (width_px, height_px) = map.renderer.extent();
        // `meshes` is what is resident, `draws` what the last frame actually submitted. They
        // differ wherever the authored style ramps a layer's width to zero, so reporting only
        // the first would claim roads are being drawn at zooms where they are gated out.
        log_info(&format!(
            "z{:.2} @{:.4},{:.4} b{:.0} vp {}x{}dp {}x{}px msaa {}x | resident {} tiles, {} meshes, \
             {} draws, {} tris | {} in flight, {} absent | archive z{}..{}",
            camera.zoom,
            camera.center_lon,
            camera.center_lat,
            camera.bearing_deg,
            camera.width_dp,
            camera.height_dp,
            width_px,
            height_px,
            map.renderer.samples(),
            tiles,
            meshes,
            draws,
            triangles,
            map.in_flight.len(),
            map.absent.len(),
            min_zoom,
            max_zoom,
        ));
    }

    // The active category filter goes to the renderer as well as to tessellation: a chip both
    // narrows which POIs are drawn and pulls its own kinds in earlier than the ambient map shows
    // them. See `Layer::draws_at_focused`.
    let (_, kinds, _) = map.toggles.get();
    match map.renderer.render(
        &camera,
        &map.layers,
        map.palette,
        style::background(map.palette.variant),
        &kinds,
    ) {
        Ok(drawn) => jboolean::from(drawn),
        Err(e) => {
            log(&format!("frame failed: {e}"));
            0
        }
    }
}

/// How long the host may wait before the next frame: `0` to draw again now, a positive number
/// of milliseconds to draw again then, or `-1` when nothing is pending at all.
///
/// The host renders on demand rather than every vsync, and asks this after each frame. It
/// answers for the pending work the Kotlin side cannot see:
///
/// - **tiles in flight** \u2014 draw now. A worker finishing a tile only reaches the screen through
///   [`render`](Java_com_vayunmathur_library_map_MapNative_render), which is what drains
///   `finished` and uploads. Nothing calls back into Kotlin when one lands, so idling with
///   requests outstanding leaves the map permanently missing whatever was still being fetched.
///   This also covers the bounded drain (a burst larger than `UPLOADS_PER_FRAME` finishes over
///   several frames, and the rest stay in `in_flight`) and the re-tessellation a layer toggle
///   triggers \u2014 both keep keys in `in_flight`.
/// - **the renderer's own pending work** \u2014 draw now. See [`Renderer::needs_frame`].
/// - **a failed tile inside its retry backoff** \u2014 draw *then*. This is why the answer is a
///   delay rather than a flag. A backed-off tile is deliberately not in `in_flight`, so
///   nothing else here reports it, and without a deadline to wake on, a tile that failed while
///   the camera was static would stay missing until the user happened to pan. Reporting the
///   wait instead lets the host sleep exactly that long and then retry once, rather than
///   either spinning through the backoff or never retrying at all.
///
/// Errs toward drawing throughout. A wasted frame costs one frame; a wrong `-1` freezes the
/// map until the user touches it.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_nextFrameDelayMillis<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jlong {
    let Some(map) = handle_mut(handle) else { return -1 };
    if !map.in_flight.is_empty() || map.renderer.needs_frame() {
        return 0;
    }
    let now = std::time::Instant::now();
    let soonest = map.retry.values().map(|(_, at)| *at).min();
    match soonest {
        // Already due but not yet re-requested: the fetch loop only runs inside a frame, so
        // this asks for the frame that will issue it.
        Some(at) if at <= now => 0,
        // At least 1, so a sub-millisecond wait is never confused with "draw now".
        Some(at) => (at - now).as_millis().max(1).min(jlong::MAX as u128) as jlong,
        None => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_resize<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    width: jint,
    height: jint,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer.resize(width.max(0) as u32, height.max(0) as u32);
    }
}

/// Switch palette. Free: colour is a push constant and the layer set is identical, so
/// nothing is re-tessellated or re-uploaded.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setPalette<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    dark: jboolean,
    muted: jboolean,
) {
    if let Some(map) = handle_mut(handle) {
        map.palette = Palette::new(dark != 0, muted != 0);
    }
}

/// Show the user-location puck at `lon`/`lat`, drawn inside the renderer's own frame.
///
/// Free in the same sense as
/// [`setPalette`](Java_com_vayunmathur_library_map_MapNative_setPalette): pure state,
/// nothing re-tessellated and nothing re-uploaded. The quad is already on the GPU and
/// everything that varies about the puck is a push constant.
///
/// `bearing` is degrees clockwise from north and is only read when `has_bearing` is set;
/// without it the dot draws and the cone does not, which is what a fix with no heading
/// should look like rather than one pointing north.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setUserPuck<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    lon: jfloat,
    lat: jfloat,
    bearing: jfloat,
    has_bearing: jboolean,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer.set_user_puck(Some(UserPuck {
            lon: lon as f64,
            lat: lat as f64,
            bearing: (has_bearing != 0).then_some(bearing),
        }));
    }
}

/// Take the puck away: no fix, or a host that stopped asking for one.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_clearUserPuck<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer.set_user_puck(None);
    }
}

/// Replace the app's pins with a marker set, drawn by the renderer as billboarded sprites.
///
/// Three parallel bulk arrays, the same convention as
/// [`setRoute`](Java_com_vayunmathur_library_map_MapNative_setRoute) and
/// [`setTrafficSpeeds`](Java_com_vayunmathur_library_map_MapNative_setTrafficSpeeds): `ids[i]` is
/// the host's own stable id for marker `i` (echoed back by
/// [`pickAt`](Java_com_vayunmathur_library_map_MapNative_pickAt)), `lonLat` holds
/// `[lon0, lat0, lon1, lat1, …]`, and `icons[i]` is the icon id (see `crate::marker::icon`). Bulk
/// arrays rather than a list of objects so a viewport's worth of pins crosses the boundary in a
/// few `get_*_array_region` reads with no per-pin JNI traffic; `float` coordinates for the same
/// reason the camera's are.
///
/// The whole set is replaced each call, not merged — a stale pin left behind would sit under the
/// finger and pick wrong. Moving the pins into the renderer is what stops them trailing the basemap
/// on a pan or tilt the way the Compose overlays did. Mismatched lengths are truncated to the
/// shortest; an empty set is the same as
/// [`clearMarkers`](Java_com_vayunmathur_library_map_MapNative_clearMarkers). Arrays that cannot be
/// read leave the markers **unchanged** rather than blanking them.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setMarkers<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    ids: JLongArray<'l>,
    lon_lat: JFloatArray<'l>,
    icons: JIntArray<'l>,
) {
    let Some(map) = handle_mut(handle) else { return };
    let id_len = env.get_array_length(&ids).unwrap_or(0).max(0) as usize;
    let icon_len = env.get_array_length(&icons).unwrap_or(0).max(0) as usize;
    let coord_len = env.get_array_length(&lon_lat).unwrap_or(0).max(0) as usize;
    // Each marker consumes two floats (lon, lat), so the coordinate array bounds the count too.
    let n = id_len.min(icon_len).min(coord_len / 2);
    if n == 0 {
        map.renderer.set_markers(Vec::new());
        return;
    }
    let mut id_buf = vec![0i64; n];
    let mut icon_buf = vec![0i32; n];
    let mut coord_buf = vec![0f32; n * 2];
    if env.get_long_array_region(&ids, 0, &mut id_buf).is_err()
        || env.get_int_array_region(&icons, 0, &mut icon_buf).is_err()
        || env.get_float_array_region(&lon_lat, 0, &mut coord_buf).is_err()
    {
        log("the marker arrays could not be read; leaving the markers unchanged");
        return;
    }
    let markers: Vec<Marker> = (0..n)
        .map(|i| Marker {
            // A Kotlin id is a signed `long` and an icon a signed `int`; the bit pattern is what
            // matters and the cast keeps it.
            id: id_buf[i] as u64,
            lon: coord_buf[i * 2] as f64,
            lat: coord_buf[i * 2 + 1] as f64,
            icon: icon_buf[i] as u32,
        })
        .collect();
    map.renderer.set_markers(markers);
}

/// Take every marker away: the host cleared its pins.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_clearMarkers<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer.set_markers(Vec::new());
    }
}

/// Replace the simulated transit vehicles with a set the renderer draws as billboarded sprites.
///
/// The same three parallel bulk arrays as
/// [`setMarkers`](Java_com_vayunmathur_library_map_MapNative_setMarkers) — `ids[i]` the host's
/// stable per-trip id, `lonLat` the flat `[lon0, lat0, lon1, lat1, …]`, and `icons[i]` the mode
/// sprite id (a vehicle uses the `VEHICLE_*` ids in `crate::marker::icon`) — because a vehicle is
/// just a [`Marker`] whose icon names a mode sprite, so it reuses the marker draw path verbatim.
///
/// Separate from [`setMarkers`](Java_com_vayunmathur_library_map_MapNative_setMarkers) so the app's
/// ~1 Hz vehicle recompute replaces only the vehicles, leaving the pins (which change on a tap or
/// search) untouched, and so the moving vehicle sprites stay out of the pin id-buffer pick. The
/// whole set is replaced each call — a trip that ended, left the bbox, or was cancelled must drop
/// out rather than linger. Mismatched lengths are truncated to the shortest; an empty set is the
/// same as [`clearVehicles`](Java_com_vayunmathur_library_map_MapNative_clearVehicles). Arrays that
/// cannot be read leave the vehicles **unchanged** rather than blanking them.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setVehicles<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    ids: JLongArray<'l>,
    lon_lat: JFloatArray<'l>,
    icons: JIntArray<'l>,
) {
    let Some(map) = handle_mut(handle) else { return };
    let id_len = env.get_array_length(&ids).unwrap_or(0).max(0) as usize;
    let icon_len = env.get_array_length(&icons).unwrap_or(0).max(0) as usize;
    let coord_len = env.get_array_length(&lon_lat).unwrap_or(0).max(0) as usize;
    // Each vehicle consumes two floats (lon, lat), so the coordinate array bounds the count too.
    let n = id_len.min(icon_len).min(coord_len / 2);
    if n == 0 {
        map.renderer.set_vehicles(Vec::new());
        return;
    }
    let mut id_buf = vec![0i64; n];
    let mut icon_buf = vec![0i32; n];
    let mut coord_buf = vec![0f32; n * 2];
    if env.get_long_array_region(&ids, 0, &mut id_buf).is_err()
        || env.get_int_array_region(&icons, 0, &mut icon_buf).is_err()
        || env.get_float_array_region(&lon_lat, 0, &mut coord_buf).is_err()
    {
        log("the vehicle arrays could not be read; leaving the vehicles unchanged");
        return;
    }
    let vehicles: Vec<Marker> = (0..n)
        .map(|i| Marker {
            id: id_buf[i] as u64,
            lon: coord_buf[i * 2] as f64,
            lat: coord_buf[i * 2 + 1] as f64,
            icon: icon_buf[i] as u32,
        })
        .collect();
    map.renderer.set_vehicles(vehicles);
}

/// Take every simulated vehicle away: the transit toggle went off, or the surface was hidden.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_clearVehicles<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer.set_vehicles(Vec::new());
    }
}

/// Draw a navigation route line over the basemap and under the puck.
///
/// `points` is a flat `[lon0, lat0, lon1, lat1, …]` array holding every coloured run's
/// points concatenated; `segment_lengths` is the point count of each run in order, and
/// `segment_colors` the ARGB fill of each, index for index. Three bulk arrays rather than a
/// list of objects because a route is thousands of points and a per-point JNI crossing is
/// exactly what the rest of this boundary exists to avoid; `float` rather than `double` for
/// the same reason the camera's coordinates are (see
/// [`render`](Java_com_vayunmathur_library_map_MapNative_render)) — seven significant
/// digits is about a centimetre at the equator, and a route is a shape to follow rather
/// than a survey.
///
/// A **list of coloured runs**, one casing: the phone colours the route per navigation
/// step (traffic bands, transit brand colours, a travelled grey behind the puck), and that
/// colouring now lives in the renderer so the route pans in lock-step with the basemap. The
/// casing is drawn once over the whole route and each run's fill over it in run colour. A
/// single-colour route (Android Auto) is just a one-run list.
///
/// Tessellated here, on the calling thread, and uploaded once. That is affordable because
/// it happens when the route is set and never again: the mesh is zoom-independent, so no
/// frame and no zoom step rebuilds it. See [`crate::overlay`].
///
/// An empty array, a run of fewer than two distinct points, or lengths that sum past the
/// points available all draw nothing for the affected run, and a route with no drawable run
/// is the same outcome as
/// [`clearRoute`](Java_com_vayunmathur_library_map_MapNative_clearRoute). Arrays that cannot
/// be read leave the route **unchanged** rather than blanking a route being followed.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setRoute<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    points: JFloatArray<'l>,
    segment_lengths: JIntArray<'l>,
    segment_colors: JIntArray<'l>,
    width_dp: jfloat,
    casing_dp: jfloat,
    casing_color: jint,
) {
    let Some(map) = handle_mut(handle) else { return };
    let point_floats = match env.get_array_length(&points) {
        Ok(length) => length.max(0) as usize,
        // Reading failed, so we know nothing about the intended route. Leaving the current
        // one alone beats blanking a route the driver is following.
        Err(_) => {
            log("the route array could not be measured; leaving the route unchanged");
            return;
        }
    };
    let segment_count = env.get_array_length(&segment_lengths).unwrap_or(0).max(0) as usize;
    let color_count = env.get_array_length(&segment_colors).unwrap_or(0).max(0) as usize;
    let segment_count = segment_count.min(color_count);

    let mut flat = vec![0f32; point_floats];
    if env.get_float_array_region(&points, 0, &mut flat).is_err() {
        log("the route array could not be read; leaving the route unchanged");
        return;
    }
    let mut lengths = vec![0i32; segment_count];
    let mut colors = vec![0i32; segment_count];
    if segment_count > 0
        && (env.get_int_array_region(&segment_lengths, 0, &mut lengths).is_err()
            || env.get_int_array_region(&segment_colors, 0, &mut colors).is_err())
    {
        log("the route segment arrays could not be read; leaving the route unchanged");
        return;
    }

    // Walk the flat point buffer run by run: each length is a point count, so it consumes
    // twice that many floats. A length that would overrun the buffer is clamped, so a
    // mismatched pair truncates rather than reading past the array.
    let mut segments: Vec<RouteSegment> = Vec::with_capacity(segment_count);
    let mut cursor = 0usize;
    for (len, color) in lengths.into_iter().zip(colors) {
        let count = len.max(0) as usize;
        let end = (cursor + count * 2).min(flat.len());
        let run: Vec<(f64, f64)> =
            flat[cursor..end].chunks_exact(2).map(|pair| (pair[0] as f64, pair[1] as f64)).collect();
        cursor = end;
        // ARGB arrives as a signed `int` because that is what a Kotlin colour is; the bit
        // pattern is what matters and the cast keeps it.
        segments.push(RouteSegment { points: run, color: color as u32 });
    }

    let style = RouteStyle { width_dp, casing_dp, casing_color: casing_color as u32 };
    let mesh = crate::overlay::tessellate(&segments, style);
    if let Err(e) = map.renderer.set_route(mesh.as_ref()) {
        log(&format!("uploading the route failed: {e}"));
    }
}

/// Take the route away: navigation ended, or the host cleared it.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_clearRoute<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Some(map) = handle_mut(handle) {
        if let Err(e) = map.renderer.set_route(None) {
            log(&format!("clearing the route failed: {e}"));
        }
    }
}

/// Dim everything outside the region containing this point, and report which one that is.
///
/// Takes a place's coordinates rather than a region id because nothing in the archive links the
/// two: a city is a `places` **node** with its own OSM id, while its outline is a `boundaries`
/// **relation**, and OSM does not oblige the node to be a member of the relation. Containment is
/// the link - a city label sits inside its own boundary.
///
/// `level_min`/`level_max` are the inclusive OSM `admin_level` band the selection means, and are
/// not optional: every label is contained by a whole stack of regions, so containment alone
/// cannot say whether a tap on "California" meant the state or the county its label sits in.
///
/// Returns the region's OSM relation id, or 0 when no resident tile covers the point. Returning
/// it rather than nothing lets the host tell "no region here" from "not loaded yet" and retry.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setRegionMask<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    lon: jfloat,
    lat: jfloat,
    level_min: jint,
    level_max: jint,
) -> jlong {
    let Some(map) = handle_mut(handle) else { return 0 };
    let levels = (level_min.max(0) as u16)..=(level_max.max(0) as u16);
    match map.renderer.region_at(lon as f64, lat as f64, levels) {
        Some(id) => {
            map.renderer.set_region_mask(Some(id));
            id as jlong
        }
        None => {
            map.renderer.set_region_mask(None);
            0
        }
    }
}

/// Take the region mask away: the details sheet closed, or the selection moved on.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_clearRegionMask<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer.set_region_mask(None);
    }
}

/// Turn the optional layers on or off, and narrow POI to a set of kinds.
///
/// Not free, unlike [`setPalette`](Java_com_vayunmathur_library_map_MapNative_setPalette):
/// POI and transit are gated at tessellation time so that leaving them off costs nothing,
/// which means turning one on invalidates every resident mesh. Bumping the generation is
/// all this does; the render loop notices the resident tiles are stale and re-requests
/// them through the existing worker pool, reading the archive it already has. Nothing is
/// refetched, nothing is evicted, and the old meshes keep drawing until the new ones land.
///
/// `kinds` is a comma-separated list of archive kind names — the app's category chips. Empty
/// means every kind the style draws. A name the schema has no id for is skipped rather than
/// refused: the chip list is app data and a typo there should narrow the map oddly, not blank it.
///
/// Traffic is a third optional layer but rides its own entry point
/// ([`setTrafficEnabled`](Java_com_vayunmathur_library_map_MapNative_setTrafficEnabled)) rather
/// than a fourth argument here, so adding it did not change this call's shape for the five
/// existing consumers.
///
/// A call that changes nothing bumps nothing, because the host is expected to call this
/// from a Compose effect that may re-run for unrelated reasons.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setLayers<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    poi: jboolean,
    transit: jboolean,
    kinds: JString<'l>,
) {
    let names: String = env.get_string(&kinds).map(Into::into).unwrap_or_default();
    let filter = style::KindFilter::new(
        names
            .split(',')
            .map(str::trim)
            .filter(|name| !name.is_empty())
            .filter_map(style::kind_id)
            .collect(),
    );
    if let Some(map) = handle_mut(handle) {
        // Traffic is carried through its own setter, so preserve whatever it was set to.
        let traffic = map.toggles.get().0.traffic;
        let wanted =
            LayerToggles { poi: poi != 0, transit: transit != 0, traffic };
        if map.toggles.set(wanted, filter) {
            log_info(&format!(
                "layers changed: poi={} transit={} kinds=[{names}]",
                wanted.poi, wanted.transit,
            ));
        }
    }
}

/// Turn the live-traffic overlay on or off.
///
/// Gates the overlay at **tessellation** like [`setLayers`], so an archive without the layer —
/// or a consumer that never enables traffic — pays nothing: flipping it on bumps the toggle
/// generation and the resident tiles re-tessellate with the traffic layer through the existing
/// worker pool (nothing refetched, nothing evicted). It also flips a per-frame draw guard so a
/// toggle-off stops the overlay drawing immediately, before that re-tessellation lands.
///
/// The per-segment colours arrive separately through
/// [`setTrafficSpeeds`](Java_com_vayunmathur_library_map_MapNative_setTrafficSpeeds); this call
/// is only the geometry gate. Its own entry point rather than a fourth argument to [`setLayers`]
/// so the existing layer call keeps its shape.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setTrafficEnabled<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    enabled: jboolean,
) {
    if let Some(map) = handle_mut(handle) {
        let on = enabled != 0;
        // The renderer's per-frame draw guard, set every call so it tracks the toggle even
        // when the tessellation state is otherwise unchanged.
        map.renderer.set_traffic_enabled(on);
        // Re-use the current POI/transit/kinds snapshot and flip only traffic, so this
        // shares the one generation counter with setLayers rather than racing a second.
        let (current, kinds, _) = map.toggles.get();
        let wanted = LayerToggles { traffic: on, ..current };
        if map.toggles.set(wanted, kinds) {
            log_info(&format!("traffic layer {}", if on { "on" } else { "off" }));
        }
    }
}

/// Push the live-traffic colour table: `ids[i]` is a segment's `component_id` and
/// `colors[i]` the fully-resolved ARGB the device wants drawn for it.
///
/// Two parallel arrays rather than a packed buffer, agreed with the device workstream: it is
/// what a Kotlin caller already has in hand (a `LongArray` of ids and an `IntArray` of ARGB
/// from its own palette), and it crosses the boundary in two bulk `get_*_array_region` reads
/// with no per-element JNI traffic. The device owns the theme, so the colours are final here —
/// the renderer only looks them up.
///
/// Replacing the whole table each call, not merging: a viewport's worth of readings arrives at
/// once, and a stale id left behind would colour a road the latest data no longer covers.
/// Ids absent from the table draw nothing (the basemap road shows through), which is the
/// no-data behaviour. Mismatched lengths are truncated to the shorter. This is a pure
/// state swap — no tessellation and no upload — so it is the recolour hot path and is cheap.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setTrafficSpeeds<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    ids: JLongArray<'l>,
    colors: JIntArray<'l>,
) {
    let Some(map) = handle_mut(handle) else { return };
    let id_len = env.get_array_length(&ids).unwrap_or(0).max(0) as usize;
    let color_len = env.get_array_length(&colors).unwrap_or(0).max(0) as usize;
    let n = id_len.min(color_len);
    if n == 0 {
        // An empty push is a clear: the host has no readings for the current viewport.
        map.renderer.clear_traffic();
        return;
    }
    let mut id_buf = vec![0i64; n];
    let mut color_buf = vec![0i32; n];
    if env.get_long_array_region(&ids, 0, &mut id_buf).is_err()
        || env.get_int_array_region(&colors, 0, &mut color_buf).is_err()
    {
        log("the traffic arrays could not be read; leaving the colours unchanged");
        return;
    }
    // The bit pattern is what matters: a Kotlin colour is a signed `int` and an id is a signed
    // `long`, and both reinterpret to the unsigned the renderer keys and paints with.
    let ids_u64: Vec<u64> = id_buf.into_iter().map(|v| v as u64).collect();
    let colors_u32: Vec<u32> = color_buf.into_iter().map(|v| v as u32).collect();
    map.renderer.set_traffic_speeds(&ids_u64, &colors_u32);
}

/// Take the live-traffic overlay away: the toggle went off, or the viewport moved off the
/// squares the host has readings for.
///
/// Clears the pushed colours so the overlay stops drawing on the very next frame, without
/// waiting for the toggle-driven re-tessellation to evict the geometry. Cheap and idempotent,
/// like [`clearRegionMask`](Java_com_vayunmathur_library_map_MapNative_clearRegionMask).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_clearTraffic<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer.clear_traffic();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setOnline<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    online: jboolean,
) {
    if let Some(map) = handle_mut(handle) {
        map.online.set(online != 0);
    }
}

/// Destroy the renderer and release its window.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_destroy<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // Dropping the handle drops the sender, which ends the worker's `recv` loop, and then
    // the renderer, which waits for the device to go idle before freeing anything.
    unsafe { drop(Box::from_raw(handle as *mut MapHandle)) };
}

/// Task-17 pick: placed labels intersecting the query box (Dp from the
/// viewport top-left). Returns `\u{1}`-joined `layerId/name/kind/lon/lat/featureId`
/// strings in placement order (topmost first); empty when nothing hits. Dp→device-px via the
/// last frame's density, remembered on the map handle (same density the
/// boxes were built with — boxes are device px, the query arrives in Dp).
///
/// `kind` is the feature's own kind, not its layer's first one, so a `poi-food` hit says
/// `cafe` rather than `restaurant`. `featureId` is the archive's stable id, or `0` for a
/// feature that has none.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_pickLabels<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    x0_dp: jfloat,
    y0_dp: jfloat,
    x1_dp: jfloat,
    y1_dp: jfloat,
) -> jni::objects::JObjectArray<'l> {
    let empty = env.new_object_array(0, "java/lang/String", JObject::null()).expect("pickLabels empty array");
    let Some(map) = handle_mut(handle) else { return empty };
    let density = map.density;
    let hits = map.renderer.pick_labels((
        x0_dp as f32 * density,
        y0_dp as f32 * density,
        x1_dp as f32 * density,
        y1_dp as f32 * density,
    ));
    let layers = &map.layers;
    let out = match env.new_object_array(
        hits.len() as i32,
        "java/lang/String",
        JObject::null(),
    ) {
        Ok(a) => a,
        Err(_) => return empty,
    };
    for (i, h) in hits.iter().enumerate() {
        let layer_id = layers.get(h.layer_index).map(|l| l.id.as_str()).unwrap_or("");
        let s = format!(
            "{}\u{1}{}\u{1}{}\u{1}{}\u{1}{}\u{1}{}",
            layer_id, h.name, h.kind, h.lon, h.lat, h.feature_id,
        );
        let Ok(js) = env.new_string(s) else { continue };
        let _ = env.set_object_array_element(&out, i as i32, js);
    }
    out
}

/// Pick the renderer-drawn marker under a tap: the id-buffer readback path.
///
/// `x`/`y` are Dp from the viewport top-left, converted to device px with the last frame's
/// density (the same conversion [`pickLabels`](Java_com_vayunmathur_library_map_MapNative_pickLabels)
/// makes), because the id buffer is device-px. Returns the tapped marker's own id — the value the
/// host set on it in [`setMarkers`](Java_com_vayunmathur_library_map_MapNative_setMarkers) — so the
/// host rejoins the tap to its feature without matching on position, or `0` when the tap hit no
/// marker. That replaces the Compose CPU hit-test, which trailed the basemap on a pan and could not
/// place a pin's box under tilt at all.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_pickAt<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    x_dp: jfloat,
    y_dp: jfloat,
) -> jlong {
    let Some(map) = handle_mut(handle) else { return 0 };
    let density = map.density;
    // Negative Dp is off the top-left of the viewport; clamp to zero before scaling so the cast to
    // an unsigned device coordinate cannot wrap. The native side clamps the far edges to the extent.
    let x = (x_dp.max(0.0) * density).round() as u32;
    let y = (y_dp.max(0.0) * density).round() as u32;
    map.renderer.pick_at(x, y) as jlong
}

fn handle_mut(handle: jlong) -> Option<&'static mut MapHandle> {
    if handle == 0 {
        return None;
    }
    // The handle is only ever the pointer `create` returned, and Kotlin drives all of
    // these from one thread.
    unsafe { Some(&mut *(handle as *mut MapHandle)) }
}

/// Logcat, at error level.
///
/// A renderer that fails silently is the thing this whole layer must not do: an empty map
/// looks exactly like a working map over the sea.
fn log(message: &str) {
    write_log(6, message);
}

/// Logcat, at info level, for the periodic frame report.
fn log_info(message: &str) {
    write_log(4, message);
}

fn write_log(priority: i32, message: &str) {
    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(priority: i32, tag: *const u8, text: *const u8) -> i32;
    }
    let tag = b"MapRenderer\0";
    let mut text: Vec<u8> = message.as_bytes().to_vec();
    text.push(0);
    unsafe {
        __android_log_write(priority, tag.as_ptr(), text.as_ptr());
    }
}
