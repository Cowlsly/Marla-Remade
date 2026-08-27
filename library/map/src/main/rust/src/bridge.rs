//! The JNI surface: five entry points, and nothing per-feature or per-vertex.
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
use crate::style::{self, Layer, Palette};
use crate::tile::cache::{RangeCache, DEFAULT_MAX_BYTES};
use crate::tile::geometry::{self, TileMesh};
use crate::tile::select::{self, TileId};
use crate::tile::source::{CachingRangeReader, JniRangeFetcher, BASEMAP_PMTILES_URL, CACHE_FORMAT};
use crate::vulkan::context::{ANativeWindow_acquire, ANativeWindow_fromSurface};
use crate::vulkan::renderer::Renderer;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jlong};
use jni::JNIEnv;
use std::collections::HashSet;
use std::os::raw::c_void;
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex};
use tilecodec::mvt::Tile;
use tilecodec::stream::StreamArchive;

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
    layers: Vec<Layer>,
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
    online: Arc<OnlineFlag>,
    /// Light or dark. Switching costs nothing: colour is a push constant and the layer set
    /// is identical, so no tile is re-tessellated or re-uploaded.
    /// Light or dark, muted or not. Switching costs nothing: colour is a push constant and
    /// the layer set is identical, so no tile is re-tessellated or re-uploaded.
    palette: Palette,
    /// Read live every frame, because the worker only learns it after fetching the header.
    zoom_range: Arc<ZoomRange>,
    /// Frames drawn, for the once-a-second diagnostic log.
    frames: u32,
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
) -> jlong {
    // The bridge back to `:library:network` has to be resolved before any worker thread
    // needs it; doing it here means the failure is visible at startup rather than as a
    // silently blank map.
    if !jni_http::init(&mut env) {
        log("library:network is missing, so tiles cannot be fetched");
        return 0;
    }

    let cache_dir: String = match env.get_string(&cache_dir) {
        Ok(s) => s.into(),
        Err(_) => return 0,
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

    let renderer = match unsafe { Renderer::new(window, width.max(1) as u32, height.max(1) as u32) } {
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
    // One `Receiver` shared by every worker, so whichever is free takes the next tile.
    let queue = Arc::new(Mutex::new(wanted_rx));
    for index in 0..WORKER_COUNT {
        spawn_worker(
            index,
            cache_dir.clone(),
            queue.clone(),
            finished_tx.clone(),
            online.clone(),
            zoom_range.clone(),
        );
    }

    let handle = Box::new(MapHandle {
        renderer,
        layers: style::layers(),
        finished: finished_rx,
        wanted: wanted_tx,
        in_flight: HashSet::new(),
        absent: HashSet::new(),
        online,
        palette: Palette::new(dark != 0, muted != 0),
        zoom_range,
        frames: 0,
    });
    Box::into_raw(handle) as jlong
}

/// A worker: opens its own view of the archive, then serves tile requests until the queue
/// closes.
///
/// Each worker holds its **own** [`StreamArchive`], so its leaf-directory cache and range
/// reads need no lock. The cost is one 16 KB header fetch per worker at startup and a
/// duplicated leaf cache; the alternative — one archive behind a mutex — would serialise
/// exactly the round trips this is meant to overlap. The on-disk range cache is shared, and
/// is safe to share because every entry is written temp-then-renamed.
fn spawn_worker(
    index: usize,
    cache_dir: String,
    queue: Arc<Mutex<Receiver<TileId>>>,
    finished: Sender<(u64, TileResult)>,
    online: Arc<OnlineFlag>,
    zoom_range: Arc<ZoomRange>,
) {
    let started = std::thread::Builder::new()
        .name(format!("map-tiles-{index}"))
        .spawn(move || {
            let cache = RangeCache::open(
                cache_dir,
                &format!("{CACHE_FORMAT}|{BASEMAP_PMTILES_URL}"),
                DEFAULT_MAX_BYTES,
            );
            let reader = CachingRangeReader::new(BASEMAP_PMTILES_URL, cache, JniRangeFetcher);
            reader.set_online(online.get());

            let mut archive = match StreamArchive::open(reader) {
                Ok(a) => a,
                Err(e) => {
                    log(&format!("worker {index} cannot open the pmtiles archive: {e}"));
                    return;
                }
            };
            // Publish the real range. Until this lands the renderer works from a guess, and
            // a guess that is too high asks for a zoom the archive does not contain and
            // silently gets nothing back.
            zoom_range.set(archive.header.min_zoom, archive.header.max_zoom);
            let layers = style::layers();

            loop {
                // Hold the queue lock only long enough to take one tile, never across the
                // fetch — otherwise the workers would run strictly in turn.
                let next = match queue.lock() {
                    Ok(guard) => guard.recv(),
                    Err(_) => return,
                };
                let Ok(tile) = next else { return };
                let key = tile.key();

                let result = match archive.tile(tile.z, tile.x, tile.y) {
                    Ok(Some(body)) => match Tile::decode(&body) {
                        Ok(decoded) => TileResult::Ready(geometry::build(
                            &decoded, &layers, tile.z, tile.x, tile.y,
                        )),
                        Err(e) => {
                            log(&format!(
                                "tile {}/{}/{} did not decode: {e}",
                                tile.z, tile.x, tile.y
                            ));
                            TileResult::Failed
                        }
                    },
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
    width_dp: jfloat,
    height_dp: jfloat,
    density: jfloat,
) -> jboolean {
    let Some(map) = handle_mut(handle) else { return 0 };

    let camera = Camera {
        center_lon: center_lon as f64,
        center_lat: center_lat as f64,
        zoom: zoom as f64,
        width_dp,
        height_dp,
        density,
    };

    // Upload whatever the workers finished. Doing it here rather than on a worker keeps
    // every Vulkan call on one thread.
    while let Ok((key, result)) = map.finished.try_recv() {
        map.in_flight.remove(&key);
        match result {
            TileResult::Ready(mesh) => {
                if let Err(e) = map.renderer.upload(key, &mesh) {
                    log(&format!("uploading a tile failed: {e}"));
                }
            }
            // Remembered, so a mostly-ocean viewport does not re-request the same empty
            // tiles every frame for the life of the surface.
            TileResult::Absent => {
                map.absent.insert(key);
            }
            // Deliberately not recorded: clearing `in_flight` above is what lets it be
            // tried again, which is the whole point of distinguishing this from `Absent`.
            TileResult::Failed => {}
        }
    }

    // Keep the visible tiles plus any ancestor of one that we already have, but **fetch
    // only the visible tiles**. An ancestor is a fallback for a tile still in flight, so
    // it is only worth drawing if it is already resident — fetching one spends a round trip
    // to show a blurrier version of a tile that is being fetched anyway, and because
    // ancestors sort first it spent that latency before requesting what the user is
    // actually looking at.
    let (min_zoom, max_zoom) = map.zoom_range.get();
    let keep: Vec<u64> =
        select::resident_set(&camera, min_zoom, max_zoom).iter().map(|t| t.key()).collect();
    for tile in select::visible(&camera, min_zoom, max_zoom) {
        let key = tile.key();
        if map.renderer.has_tile(key) || map.absent.contains(&key) || !map.in_flight.insert(key)
        {
            continue;
        }
        // A closed channel means every worker died; the map keeps drawing what it has.
        let _ = map.wanted.send(tile);
    }
    map.renderer.retain(&keep);

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
            "z{:.2} @{:.4},{:.4} vp {}x{}dp {}x{}px | resident {} tiles, {} meshes, {} draws, \
             {} tris | {} in flight, {} absent | archive z{}..{}",
            camera.zoom,
            camera.center_lon,
            camera.center_lat,
            camera.width_dp,
            camera.height_dp,
            width_px,
            height_px,
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

    match map.renderer.render(&camera, &map.layers, map.palette, style::background(map.palette.variant)) {
        Ok(drawn) => jboolean::from(drawn),
        Err(e) => {
            log(&format!("frame failed: {e}"));
            0
        }
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
