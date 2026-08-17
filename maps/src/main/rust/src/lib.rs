//! Offline routing engine + live-traffic MVT tile encoder for the Maps app,
//! exposed to Kotlin (`com.vayunmathur.maps.util.OfflineRouter`) via JNI.
//!
//! Rust port of the former CMake/C++ `libofflinerouter` (`native-lib.cpp` +
//! `scratchpad.h` + `radix_heap.h`). The whole-world graph is mmap'd once and
//! shared read-only behind an `Arc`; routing state is serialized behind a mutex;
//! traffic speeds and the per-square segment cache live in their own locks,
//! mirroring the C++ globals but made explicit.

mod geometry;
mod graph;
mod mvt;
mod routing;
mod state;
mod transit;

use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::{Arc, Mutex, OnceLock, RwLock};

use jni::objects::{
    JByteArray, JLongArray, JObject, JObjectArray, JString, JValue,
};
use jni::sys::{jboolean, jbyteArray, jdouble, jdoubleArray, jint, jlong, jobjectArray};
use jni::JNIEnv;

use crate::geometry::TrafficSpeeds;
use crate::graph::Graph;
use crate::routing::{perform_search_loop, prepare_routing, reconstruct_path};
use crate::state::{RadixHeap, RoutingScratchpad};

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------

/// The immutable whole-world graph, set once by `init`.
static GRAPH: RwLock<Option<Arc<Graph>>> = RwLock::new(None);

fn graph() -> Option<Arc<Graph>> {
    GRAPH.read().ok()?.clone()
}

/// Live traffic speeds (global edge id -> km/h). Read (snapshot) per route,
/// written by `updateTrafficNative`.
fn traffic_speeds() -> &'static RwLock<TrafficSpeeds> {
    static S: OnceLock<RwLock<TrafficSpeeds>> = OnceLock::new();
    S.get_or_init(|| RwLock::new(HashMap::new()))
}

/// Per-square traffic segments + requested-square set for the overlay/tiles.
struct TrafficMeta {
    by_square: BTreeMap<i32, Vec<f64>>,
    requested: Vec<u32>,
}

fn traffic_meta() -> &'static Mutex<TrafficMeta> {
    static M: OnceLock<Mutex<TrafficMeta>> = OnceLock::new();
    M.get_or_init(|| {
        Mutex::new(TrafficMeta {
            by_square: BTreeMap::new(),
            requested: Vec::new(),
        })
    })
}

/// Reusable A* working sets, serialized so only one route runs at a time.
struct RouteState {
    scratch: RoutingScratchpad,
    heap: RadixHeap,
}

fn route_state() -> &'static Mutex<RouteState> {
    static R: OnceLock<Mutex<RouteState>> = OnceLock::new();
    R.get_or_init(|| {
        Mutex::new(RouteState {
            scratch: RoutingScratchpad::new(),
            heap: RadixHeap::new(),
        })
    })
}

// ---------------------------------------------------------------------------
// Traffic prefetch reverse-callback
// ---------------------------------------------------------------------------

/// Request the traffic square containing `(lat_e7, lon_e7)` from Kotlin if it
/// hasn't been requested yet. Mirrors the C++ `ensure_traffic_loaded`.
fn ensure_traffic_loaded(
    env: &mut JNIEnv,
    thiz: &JObject,
    lat_e7: i32,
    lon_e7: i32,
    force_async: bool,
) {
    let lat_idx = (lat_e7 as f64 * 1e-7).floor() as i32;
    let lon_idx = (lon_e7 as f64 * 1e-7).floor() as i32;
    let packed = (((lat_idx + 360) as u32) << 16) | (lon_idx + 720) as u32;

    {
        let mut meta = traffic_meta().lock().unwrap();
        if meta.requested.contains(&packed) {
            return;
        }
        meta.requested.push(packed);
    }

    let _ = env.call_method(
        thiz,
        "fetchTrafficData",
        "(DDDDIZ)V",
        &[
            JValue::Double(lat_idx as f64),
            JValue::Double(lon_idx as f64),
            JValue::Double(lat_idx as f64 + 1.0),
            JValue::Double(lon_idx as f64 + 1.0),
            JValue::Int(packed as i32),
            JValue::Bool(force_async as u8),
        ],
    );
}

// ---------------------------------------------------------------------------
// JNI: init
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_init<'local>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
    present_feeds: JObjectArray<'local>,
) -> jboolean {
    let base: String = match env.get_string(&base_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let mut feeds: HashSet<String> = HashSet::new();
    if !present_feeds.is_null() {
        let len = env.get_array_length(&present_feeds).unwrap_or(0);
        for i in 0..len {
            if let Ok(obj) = env.get_object_array_element(&present_feeds, i) {
                let s = JString::from(obj);
                // Convert to an owned String within this statement so the
                // borrowing `JavaStr` is dropped before `s`.
                let val: Option<String> = env.get_string(&s).ok().map(|js| js.into());
                if let Some(v) = val {
                    feeds.insert(v);
                }
            }
        }
    }

    match Graph::load(&base, feeds) {
        Some(g) => {
            if let Ok(mut w) = GRAPH.write() {
                *w = Some(Arc::new(g));
                1
            } else {
                0
            }
        }
        None => 0,
    }
}

// ---------------------------------------------------------------------------
// JNI: findRouteNative
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_findRouteNative<'local>(
    mut env: JNIEnv<'local>,
    thiz: JObject<'local>,
    s_lat: jdouble,
    s_lon: jdouble,
    e_lat: jdouble,
    e_lon: jdouble,
    mode: jint,
    start_time: jlong,
) -> jobjectArray {
    let null = std::ptr::null_mut();
    let g = match graph() {
        Some(g) => g,
        None => return null,
    };

    // Snapshot the current traffic speeds so the hot loop needs no locking and
    // concurrent updateTrafficNative calls don't block the whole route.
    let speeds: TrafficSpeeds = traffic_speeds().read().map(|m| m.clone()).unwrap_or_default();

    let state_mutex = route_state();
    let mut state = state_mutex.lock().unwrap();
    let RouteState { scratch, heap } = &mut *state;

    // prepare + search need the JNI env for the driving traffic prefetch.
    let ctx = {
        let mut ensure = |lat_e7: i32, lon_e7: i32| {
            ensure_traffic_loaded(&mut env, &thiz, lat_e7, lon_e7, false);
        };

        let mut ctx = match prepare_routing(
            &g, &speeds, &mut ensure, s_lat, s_lon, e_lat, e_lon, mode, start_time as u32, scratch,
            heap,
        ) {
            Some(c) => c,
            None => return null,
        };
        perform_search_loop(&g, &speeds, &mut ensure, mode, &mut ctx, scratch, heap);
        ctx
    };

    if ctx.target_node == 0xFFFF_FFFF {
        return null;
    }

    let steps = reconstruct_path(&g, &speeds, mode, &ctx, scratch);

    // --- Marshal steps into OfflineRouter.RawStep[] ---
    let class = match env.find_class("com/vayunmathur/maps/util/OfflineRouter$RawStep") {
        Ok(c) => c,
        Err(_) => return null,
    };
    let ctor = "(ILjava/lang/String;JJ[DDZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I[I)V";

    let array = match env.new_object_array(steps.len() as i32, &class, JObject::null()) {
        Ok(a) => a,
        Err(_) => return null,
    };

    for (i, step) in steps.iter().enumerate() {
        let name = g.road_name(step.name_off).unwrap_or_else(|| "Unknown Road".to_string());
        let jname: JObject = match env.new_string(&name) {
            Ok(s) => s.into(),
            Err(_) => return null,
        };
        let jgeom = match env.new_double_array(step.coords.len() as i32) {
            Ok(a) => a,
            Err(_) => return null,
        };
        if env.set_double_array_region(&jgeom, 0, &step.coords).is_err() {
            return null;
        }
        let jgeom_obj: JObject = jgeom.into();

        let make_opt_string = |env: &mut JNIEnv<'local>, off: u32| -> JObject<'local> {
            match g.road_name(off) {
                Some(s) => match env.new_string(&s) {
                    Ok(js) => js.into(),
                    Err(_) => JObject::null(),
                },
                None => JObject::null(),
            }
        };
        let jfeed = make_opt_string(&mut env, step.feed_off);
        let jcode = make_opt_string(&mut env, step.code_off);
        let jend = make_opt_string(&mut env, step.end_code_off);

        // Packed turn-lane guidance: one int per lane (dir * 2 + valid).
        let jlanes = match env.new_int_array(step.lanes.len() as i32) {
            Ok(a) => a,
            Err(_) => return null,
        };
        if !step.lanes.is_empty()
            && env.set_int_array_region(&jlanes, 0, &step.lanes).is_err()
        {
            return null;
        }
        let jlanes_obj: JObject = jlanes.into();

        let obj = match env.new_object(
            &class,
            ctor,
            &[
                JValue::Int(step.maneuver),
                JValue::Object(&jname),
                JValue::Long(step.dist_mm as i64),
                JValue::Long(step.time_10ms as i64),
                JValue::Object(&jgeom_obj),
                JValue::Double(step.speed_ratio),
                JValue::Bool(step.is_transit as u8),
                JValue::Object(&jfeed),
                JValue::Object(&jcode),
                JValue::Object(&jend),
                JValue::Int(step.stop_count),
                JValue::Object(&jlanes_obj),
            ],
        ) {
            Ok(o) => o,
            Err(_) => return null,
        };
        if env.set_object_array_element(&array, i as i32, &obj).is_err() {
            return null;
        }
    }

    array.into_raw()
}

// ---------------------------------------------------------------------------
// JNI: findTransitRouteNative (offline RAPTOR over a per-region transit index)
// ---------------------------------------------------------------------------

/// Plan an offline transit journey using the compact `.transit` index at
/// `<base_path>/<feed>.transit` (produced by `scripts/maps/gtfs_ingest`).
/// Returns `OfflineRouter.RawStep[]` (walk + ride legs) or `null` when the feed
/// is absent, does not cover the endpoints, or no journey exists — in which case
/// the Kotlin side falls back to the P10 online Transitous planner.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_findTransitRouteNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
    feed: JString<'local>,
    s_lat: jdouble,
    s_lon: jdouble,
    e_lat: jdouble,
    e_lon: jdouble,
    dep_secs: jint,
    weekday: jint,
    date: jint,
) -> jobjectArray {
    let null = std::ptr::null_mut();
    let base: String = match env.get_string(&base_path) {
        Ok(s) => s.into(),
        Err(_) => return null,
    };
    let feed_name: String = match env.get_string(&feed) {
        Ok(s) => s.into(),
        Err(_) => return null,
    };

    let index = match transit::TransitIndex::load(&base, &feed_name) {
        Some(i) => i,
        None => return null,
    };
    if !index.covers(s_lat, s_lon) || !index.covers(e_lat, e_lon) {
        return null;
    }

    let legs = match transit::plan(
        &index,
        s_lat,
        s_lon,
        e_lat,
        e_lon,
        dep_secs.max(0) as u32,
        weekday.max(0) as u32,
        date.max(0) as u32,
    ) {
        Some(l) if !l.is_empty() => l,
        _ => return null,
    };

    let class = match env.find_class("com/vayunmathur/maps/util/OfflineRouter$RawStep") {
        Ok(c) => c,
        Err(_) => return null,
    };
    let ctor =
        "(ILjava/lang/String;JJ[DDZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I[I)V";
    let array = match env.new_object_array(legs.len() as i32, &class, JObject::null()) {
        Ok(a) => a,
        Err(_) => return null,
    };

    for (i, leg) in legs.iter().enumerate() {
        let jname: JObject = match env.new_string(&leg.name) {
            Ok(s) => s.into(),
            Err(_) => return null,
        };
        let jgeom = match env.new_double_array(leg.coords.len() as i32) {
            Ok(a) => a,
            Err(_) => return null,
        };
        if !leg.coords.is_empty()
            && env.set_double_array_region(&jgeom, 0, &leg.coords).is_err()
        {
            return null;
        }
        let jgeom_obj: JObject = jgeom.into();

        let opt_str = |env: &mut JNIEnv<'local>, s: &str| -> JObject<'local> {
            if s.is_empty() {
                JObject::null()
            } else {
                match env.new_string(s) {
                    Ok(js) => js.into(),
                    Err(_) => JObject::null(),
                }
            }
        };
        let jfeed = if leg.is_transit {
            opt_str(&mut env, &leg.feed)
        } else {
            JObject::null()
        };
        let jcode = if leg.is_transit {
            opt_str(&mut env, &leg.from_code)
        } else {
            JObject::null()
        };
        let jend = if leg.is_transit {
            opt_str(&mut env, &leg.to_code)
        } else {
            JObject::null()
        };

        let duration_10ms: i64 = if leg.is_transit {
            (leg.arr_secs.saturating_sub(leg.dep_secs) as i64) * 100
        } else {
            (leg.dist_m / crate::graph::WALK_SPEED_M_S * 100.0) as i64
        };
        let dist_mm: i64 = (leg.dist_m * 1000.0) as i64;
        let maneuver: i32 = if leg.is_transit { 23 } else { 0 };

        let jlanes = match env.new_int_array(0) {
            Ok(a) => a,
            Err(_) => return null,
        };
        let jlanes_obj: JObject = jlanes.into();

        let obj = match env.new_object(
            &class,
            ctor,
            &[
                JValue::Int(maneuver),
                JValue::Object(&jname),
                JValue::Long(dist_mm),
                JValue::Long(duration_10ms),
                JValue::Object(&jgeom_obj),
                JValue::Double(1.0),
                JValue::Bool(leg.is_transit as u8),
                JValue::Object(&jfeed),
                JValue::Object(&jcode),
                JValue::Object(&jend),
                JValue::Int(leg.stop_count),
                JValue::Object(&jlanes_obj),
            ],
        ) {
            Ok(o) => o,
            Err(_) => return null,
        };
        if env.set_object_array_element(&array, i as i32, &obj).is_err() {
            return null;
        }
    }

    array.into_raw()
}

// ---------------------------------------------------------------------------
// JNI: updateTrafficNative
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_updateTrafficNative<'local>(
    env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    edge_ids: JLongArray<'local>,
    speeds: JByteArray<'local>,
    packed_square: jint,
) {
    let g = match graph() {
        Some(g) => g,
        None => return,
    };

    let len = env.get_array_length(&edge_ids).unwrap_or(0);
    if len <= 0 {
        // Still record an (empty) square so the overlay clears it.
        let mut meta = traffic_meta().lock().unwrap();
        meta.by_square.entry(packed_square).or_default().clear();
        return;
    }
    let mut ids = vec![0i64; len as usize];
    if env.get_long_array_region(&edge_ids, 0, &mut ids).is_err() {
        return;
    }
    let mut sp = vec![0i8; len as usize];
    if env.get_byte_array_region(&speeds, 0, &mut sp).is_err() {
        return;
    }

    let mut speeds_w = traffic_speeds().write().unwrap();
    let mut meta = traffic_meta().lock().unwrap();
    let segments = meta.by_square.entry(packed_square).or_default();
    segments.clear();

    for i in 0..len as usize {
        let edge_id = ids[i] as u64;
        let speed = sp[i] as u8;
        if edge_id < g.edge_count {
            let edge = g.edge(edge_id);
            if speed < 255 {
                speeds_w.insert(edge_id, speed);
                let node_u = g.node(g.find_node_idx_for_edge(edge_id));
                if edge.target < g.node_count {
                    let node_v = g.node(edge.target);
                    let ratio = if edge.speed_limit > 0 {
                        speed as f64 / edge.speed_limit as f64
                    } else {
                        1.0
                    };
                    segments.push(node_u.lat_e7 as f64 * 1e-7);
                    segments.push(node_u.lon_e7 as f64 * 1e-7);
                    segments.push(node_v.lat_e7 as f64 * 1e-7);
                    segments.push(node_v.lon_e7 as f64 * 1e-7);
                    segments.push(ratio);
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// JNI: notifyTrafficFetchFinishedNative (no-op, kept for ABI compatibility)
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_notifyTrafficFetchFinishedNative<
    'local,
>(
    _env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    _packed_square: jint,
) {
}

// ---------------------------------------------------------------------------
// JNI: getTrafficSegmentsNative
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_getTrafficSegmentsNative<
    'local,
>(
    env: JNIEnv<'local>,
    _thiz: JObject<'local>,
) -> jdoubleArray {
    let null = std::ptr::null_mut();
    let meta = traffic_meta().lock().unwrap();

    let mut flattened: Vec<f64> = Vec::new();
    for segments in meta.by_square.values() {
        flattened.extend_from_slice(segments);
        if flattened.len() > 50000 {
            break;
        }
    }
    if flattened.len() > 50000 {
        flattened.truncate(50000);
    }

    match env.new_double_array(flattened.len() as i32) {
        Ok(arr) => {
            if !flattened.is_empty()
                && env.set_double_array_region(&arr, 0, &flattened).is_err()
            {
                return null;
            }
            arr.into_raw()
        }
        Err(_) => null,
    }
}

// ---------------------------------------------------------------------------
// JNI: getTrafficTileNative
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_getTrafficTileNative<'local>(
    env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    z: jint,
    x: jint,
    y: jint,
) -> jbyteArray {
    let null = std::ptr::null_mut();
    let tile = {
        let meta = traffic_meta().lock().unwrap();
        mvt::generate_traffic_tile(&meta.by_square, z, x, y)
    };
    let bytes = match tile {
        Some(b) => b,
        None => return null,
    };

    // set_byte_array_region takes &[i8]; reinterpret the gzip bytes.
    let signed: &[i8] =
        unsafe { std::slice::from_raw_parts(bytes.as_ptr() as *const i8, bytes.len()) };
    match env.new_byte_array(bytes.len() as i32) {
        Ok(arr) => {
            if env.set_byte_array_region(&arr, 0, signed).is_err() {
                return null;
            }
            arr.into_raw()
        }
        Err(_) => null,
    }
}

// ---------------------------------------------------------------------------
// JNI: ensureTrafficLoadedNative
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_ensureTrafficLoadedNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    thiz: JObject<'local>,
    lat: jdouble,
    lon: jdouble,
    force_async: jboolean,
) {
    if graph().is_none() {
        return;
    }
    ensure_traffic_loaded(
        &mut env,
        &thiz,
        (lat * 1e7) as i32,
        (lon * 1e7) as i32,
        force_async != 0,
    );
}
