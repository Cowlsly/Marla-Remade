//! Offline routing engine + live-traffic MVT tile encoder for the Maps app,
//! exposed to Kotlin (`com.vayunmathur.maps.util.OfflineRouter`) via JNI.
//!
//! Rust port of the former CMake/C++ `libofflinerouter` (`native-lib.cpp` +
//! `scratchpad.h` + `radix_heap.h`). The whole-world graph is mmap'd once and
//! shared read-only behind an `Arc`; routing state is serialized behind a mutex;
//! traffic speeds and the per-square segment cache live in their own locks,
//! mirroring the C++ globals but made explicit.

// `graph`, `geometry`, `routing` and `state` are `pub` so the host-side
// route differential (`examples/route_diff.rs`) can drive the real engine
// instead of reimplementing it. Nothing outside this crate links the cdylib,
// so the wider surface costs nothing on device.
pub mod geometry;
pub mod graph;
mod mvt;
pub mod routing;
pub mod state;
mod transit;

use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, Mutex, OnceLock, RwLock};

use jni::objects::{
    JByteArray, JDoubleArray, JIntArray, JLongArray, JObject, JObjectArray, JString, JValue,
};
use jni::sys::{jboolean, jbyteArray, jdouble, jdoubleArray, jint, jobjectArray, jstring};
use jni::JNIEnv;

use crate::geometry::TrafficSpeeds;
use crate::graph::{Graph, WALK};
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
) -> jboolean {
    let base: String = match env.get_string(&base_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    match Graph::load(&base) {
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
            &g, &speeds, &mut ensure, s_lat, s_lon, e_lat, e_lon, mode, scratch, heap,
        ) {
            Some(c) => c,
            None => return null,
        };
        perform_search_loop(&g, &speeds, &mut ensure, mode, &mut ctx, scratch, heap);
        ctx
    };

    // A route that never leaves the edge it snapped to reaches no node, so
    // `target_node` stays unset; `ctx.direct` is the route in that case.
    if ctx.target_node == 0xFFFF_FFFF && ctx.direct.is_none() {
        return null;
    }

    let steps = reconstruct_path(&g, &speeds, mode, &ctx, scratch);

    // --- Marshal steps into OfflineRouter.RawStep[] ---
    let class = match env.find_class("com/vayunmathur/maps/util/OfflineRouter$RawStep") {
        Ok(c) => c,
        Err(_) => return null,
    };
    // MUST match the descriptor at the transit call site below: one ctor, two
    // callers. The trailing two Strings are the ride's MOTIS board/alight ids.
    let ctor = "(ILjava/lang/String;JJ[DDZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I[ILjava/lang/String;IIILjava/lang/String;Ljava/lang/String;)V";

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
                // Transit-only tail. The ctor descriptor is shared with the
                // RAPTOR path; the road graph has no timetable, so it fills the
                // transit fields with nulls/zeros.
                JValue::Bool(0),
                JValue::Object(&JObject::null()),
                JValue::Object(&JObject::null()),
                JValue::Object(&JObject::null()),
                JValue::Int(0),
                JValue::Object(&jlanes_obj),
                JValue::Object(&JObject::null()),
                JValue::Int(0),
                JValue::Int(0),
                JValue::Int(0),
                // No MOTIS stop ids on a road-graph step.
                JValue::Object(&JObject::null()),
                JValue::Object(&JObject::null()),
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

/// How far apart a walk leg's endpoints may be before we stop trying to route it
/// on the road graph. RAPTOR caps access/egress at 1 km and transfers at 400 m,
/// so this only excludes pathological legs.
const WALK_SNAP_MAX_M: f64 = 2_000.0;
/// Per-request budget of road searches. A plan can hold several itineraries with
/// two to four walk legs each, and every search serialises behind the same
/// `route_state()` mutex, on top of RAPTOR.
const WALK_SNAP_MAX_SEARCHES: usize = 8;
/// A road path this many times longer than the straight line is a bad snap (the
/// far side of a motorway, a ferry terminal). Keep the straight line rather than
/// pair a wildly longer polyline with RAPTOR's original duration.
const WALK_SNAP_MAX_RATIO: f64 = 2.5;

/// Approximate ground distance in metres (equirectangular).
fn crow_m(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let dlat = (lat2 - lat1) * 111_320.0;
    let dlon = (lon2 - lon1) * 111_320.0 * ((lat1 + lat2) * 0.5).to_radians().cos();
    (dlat * dlat + dlon * dlon).sqrt()
}

/// The straight line a walk leg was drawn as, when it is worth a road search.
/// A `TransitLeg` carries no endpoints of its own, only that line — which works
/// uniformly for access (user → stop), egress (stop → user) and transfer
/// (stop → stop) legs. Returns `(s_lat, s_lon, e_lat, e_lon)`.
fn walk_snap_endpoints(leg: &transit::TransitLeg) -> Option<(f64, f64, f64, f64)> {
    if leg.kind != transit::LegKind::Walk || leg.dist_m > WALK_SNAP_MAX_M {
        return None;
    }
    let n = leg.coords.len();
    if n < 4 {
        return None;
    }
    Some((leg.coords[1], leg.coords[0], leg.coords[n - 1], leg.coords[n - 2]))
}

/// Whether a `road_m` road path is a plausible redraw of a `crow`-metre straight
/// line. Rejecting the implausible ones keeps the straight line, which reads far
/// better than a wildly longer polyline paired with RAPTOR's original duration.
fn walk_snap_plausible(crow: f64, road_m: f64) -> bool {
    crow <= 1.0 || road_m <= crow * WALK_SNAP_MAX_RATIO
}

/// Redraw each walk leg along the road graph, in place.
///
/// Only `coords` and `dist_m` change. `dep_secs`/`arr_secs` are what RAPTOR
/// planned the journey around, and re-timing them would desynchronise it from
/// the departure it was built for — so a road-routed walk, being longer than the
/// crow-flies estimate RAPTOR used, leaves a shown ETA slightly optimistic. That
/// is the same heuristic the pack's TRANSFERS table is already built on, and the
/// ratio guard bounds how wrong the drawn line can get.
fn snap_walk_legs(legs: &mut [transit::TransitLeg]) {
    let g = match graph() {
        Some(g) => g,
        None => return,
    };
    let speeds: TrafficSpeeds = traffic_speeds().read().map(|m| m.clone()).unwrap_or_default();
    let mut state = match route_state().lock() {
        Ok(s) => s,
        Err(_) => return,
    };
    let RouteState { scratch, heap } = &mut *state;
    // WALK, not PUBLIC_TRANSIT: `is_mode_allowed` grants them the same road types
    // today, but WALK states the intent and cannot drift.
    let mut no_traffic = |_: i32, _: i32| {};
    let mut budget = WALK_SNAP_MAX_SEARCHES;

    for leg in legs.iter_mut() {
        if budget == 0 {
            break;
        }
        let (s_lat, s_lon, e_lat, e_lon) = match walk_snap_endpoints(leg) {
            Some(p) => p,
            None => continue,
        };
        budget -= 1;

        let mut ctx = match prepare_routing(
            &g, &speeds, &mut no_traffic, s_lat, s_lon, e_lat, e_lon, WALK, scratch, heap,
        ) {
            Some(c) => c,
            None => continue,
        };
        perform_search_loop(&g, &speeds, &mut no_traffic, WALK, &mut ctx, scratch, heap);
        if ctx.target_node == 0xFFFF_FFFF && ctx.direct.is_none() {
            continue;
        }

        // Consecutive steps repeat their joint vertex, so drop each step's first
        // point after the first step.
        let steps = reconstruct_path(&g, &speeds, WALK, &ctx, scratch);
        let mut coords: Vec<f64> = Vec::new();
        let mut dist_mm = 0u64;
        for step in &steps {
            dist_mm += step.dist_mm;
            let skip = if coords.is_empty() { 0 } else { 2 };
            if step.coords.len() > skip {
                coords.extend_from_slice(&step.coords[skip..]);
            }
        }
        if coords.len() < 4 {
            continue;
        }
        // The search starts and ends at a projection onto the nearest road, not
        // at the stop itself. Stitch the true endpoints back on so the drawn walk
        // meets its stop marker, and count those stubs.
        let mut road_m = dist_mm as f64 / 1000.0;
        road_m += crow_m(s_lat, s_lon, coords[1], coords[0]);
        road_m += crow_m(coords[coords.len() - 1], coords[coords.len() - 2], e_lat, e_lon);
        if !walk_snap_plausible(leg.dist_m, road_m) {
            continue;
        }
        let mut stitched = Vec::with_capacity(coords.len() + 4);
        stitched.push(s_lon);
        stitched.push(s_lat);
        stitched.extend_from_slice(&coords);
        stitched.push(e_lon);
        stitched.push(e_lat);
        leg.coords = stitched;
        leg.dist_m = road_m;
    }
}

/// Decode the realtime overlay's parallel arrays. `coords` is interleaved
/// `[lat, lon, ...]` and `times` is interleaved
/// `[sched_secs, delay_secs, cancelled, ...]`, so one entry spans 2 doubles,
/// 1 string and 3 ints. Any length mismatch yields no overlay rather than a
/// partial one — a wrong fingerprint would silently mis-delay a trip.
fn read_delay_entries<'local>(
    env: &mut JNIEnv<'local>,
    coords: &JDoubleArray<'local>,
    routes: &JObjectArray<'local>,
    times: &JIntArray<'local>,
) -> Vec<transit::DelayEntry> {
    let n = match env.get_array_length(routes) {
        Ok(n) if n > 0 => n as usize,
        _ => return Vec::new(),
    };
    if env.get_array_length(coords).unwrap_or(0) as usize != n * 2
        || env.get_array_length(times).unwrap_or(0) as usize != n * 3
    {
        return Vec::new();
    }
    let mut ll = vec![0f64; n * 2];
    if env.get_double_array_region(coords, 0, &mut ll).is_err() {
        return Vec::new();
    }
    let mut tv = vec![0i32; n * 3];
    if env.get_int_array_region(times, 0, &mut tv).is_err() {
        return Vec::new();
    }

    let mut out = Vec::with_capacity(n);
    for i in 0..n {
        let name: Option<String> = match env.get_object_array_element(routes, i as i32) {
            Ok(obj) => {
                let s = JString::from(obj);
                // Convert to an owned String within this statement so the
                // borrowing `JavaStr` is dropped before `s`.
                let owned: Option<String> = env.get_string(&s).ok().map(|js| js.into());
                owned
            }
            Err(_) => None,
        };
        let name = match name {
            Some(n) => n,
            None => continue,
        };
        out.push(transit::DelayEntry {
            lat: ll[i * 2],
            lon: ll[i * 2 + 1],
            route_name: name,
            sched_secs: tv[i * 3].max(0) as u32,
            delay_secs: tv[i * 3 + 1],
            cancelled: tv[i * 3 + 2] != 0,
        });
    }
    out
}

/// Plan an offline transit journey using the compact `.transit` index at
/// `<base_path>/<feed>.transit` (produced by `scripts/maps/gtfs_ingest`).
/// Returns `OfflineRouter.RawStep[]` (walk + wait + ride legs) or `null` when
/// the feed is absent, does not cover the endpoints, or no journey exists — in
/// which case the Kotlin side falls back to the P10 online Transitous planner.
///
/// The `overlay_*` arrays carry MOTIS realtime board entries so RAPTOR avoids
/// cancelled trips and plans against live times; pass empty arrays for a
/// schedule-only plan. See `transit::DelayOverlay` for why the join is a
/// fingerprint rather than a trip id.
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
    prev_weekday: jint,
    prev_date: jint,
    overlay_coords: JDoubleArray<'local>,
    overlay_routes: JObjectArray<'local>,
    overlay_times: JIntArray<'local>,
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

    let entries =
        read_delay_entries(&mut env, &overlay_coords, &overlay_routes, &overlay_times);
    let overlay = if entries.is_empty() {
        None
    } else {
        Some(transit::DelayOverlay::build(&index, &entries))
    };

    let mut legs = match transit::plan(
        &index,
        s_lat,
        s_lon,
        e_lat,
        e_lon,
        dep_secs.max(0) as u32,
        transit::Schedule {
            day: transit::QueryDay {
                weekday: weekday.max(0) as u32,
                date: date.max(0) as u32,
                prev_weekday: prev_weekday.max(0) as u32,
                prev_date: prev_date.max(0) as u32,
            },
            overlay: overlay.as_ref(),
        },
    ) {
        Some(l) if !l.is_empty() => l,
        _ => return null,
    };
    snap_walk_legs(&mut legs);

    let class = match env.find_class("com/vayunmathur/maps/util/OfflineRouter$RawStep") {
        Ok(c) => c,
        Err(_) => return null,
    };
    // MUST match the descriptor at the driving call site above: one ctor, two
    // callers. The trailing two Strings are the ride's MOTIS board/alight ids.
    let ctor = "(ILjava/lang/String;JJ[DDZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I[ILjava/lang/String;IIILjava/lang/String;Ljava/lang/String;)V";
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
        let is_transit = leg.kind.is_transit();
        // A WAIT leg needs its stop name for the "wait at X" instruction, but
        // must not carry a feed or it would render as a ride in the UI.
        let has_stops = leg.kind != transit::LegKind::Walk;
        let jfeed = if is_transit { opt_str(&mut env, &leg.feed) } else { JObject::null() };
        let jcode = if has_stops { opt_str(&mut env, &leg.from_stop) } else { JObject::null() };
        let jend = if has_stops { opt_str(&mut env, &leg.to_stop) } else { JObject::null() };
        let jheadsign = if is_transit {
            opt_str(&mut env, &leg.headsign)
        } else {
            JObject::null()
        };
        // Only a ride carries realtime, so only a ride carries stop ids. Empty on
        // a pre-v5 pack, which leaves the overlay with nothing to ask about.
        let jboard = if is_transit {
            opt_str(&mut env, &leg.board_stop_motis_id)
        } else {
            JObject::null()
        };
        let jalight = if is_transit {
            opt_str(&mut env, &leg.alight_stop_motis_id)
        } else {
            JObject::null()
        };

        // Every leg's duration comes from RAPTOR's own times. A walk leg's
        // `dist_m` may have been redrawn along the road graph, which is longer
        // than the estimate the journey was planned on, so deriving the duration
        // from it would contradict the departure the plan was built around.
        let duration_10ms: i64 = (leg.arr_secs.saturating_sub(leg.dep_secs) as i64) * 100;
        let dist_mm: i64 = (leg.dist_m * 1000.0) as i64;
        // Ordinals mirror RouteService.API.Maneuver.
        let maneuver: i32 = match leg.kind {
            transit::LegKind::Walk => 0,  // MANEUVER_UNSPECIFIED
            transit::LegKind::Wait => 21, // WAIT
            transit::LegKind::Ride => 22, // RIDE
        };

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
                JValue::Bool(is_transit as u8),
                JValue::Object(&jfeed),
                JValue::Object(&jcode),
                JValue::Object(&jend),
                JValue::Int(leg.stop_count),
                JValue::Object(&jlanes_obj),
                JValue::Object(&jheadsign),
                JValue::Int(leg.route_color as i32),
                JValue::Int(leg.dep_secs as i32),
                JValue::Int(leg.arr_secs as i32),
                JValue::Object(&jboard),
                JValue::Object(&jalight),
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
// JNI: getStopDeparturesNative (offline scheduled departure board)
// ---------------------------------------------------------------------------

/// Offline scheduled departure board from the `<base>/<feed>.transit` index for
/// the stop nearest `(lat,lon)`. `dep_secs` = seconds since local midnight,
/// `weekday` 0=Mon..6=Sun, `date` yyyymmdd. Returns
/// `OfflineRouter.RawDeparture[]` (sorted, upcoming, scheduled-only) or `null`
/// when the feed is absent / doesn't cover the point — the Kotlin side then
/// keeps the online board.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_getStopDeparturesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
    feed: JString<'local>,
    lat: jdouble,
    lon: jdouble,
    dep_secs: jint,
    weekday: jint,
    date: jint,
    prev_weekday: jint,
    prev_date: jint,
    overlay_coords: JDoubleArray<'local>,
    overlay_routes: JObjectArray<'local>,
    overlay_times: JIntArray<'local>,
    max: jint,
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
    if !index.covers(lat, lon) {
        return null;
    }

    let entries =
        read_delay_entries(&mut env, &overlay_coords, &overlay_routes, &overlay_times);
    let overlay = if entries.is_empty() {
        None
    } else {
        Some(transit::DelayOverlay::build(&index, &entries))
    };

    let deps = transit::stop_departures(
        &index,
        lat,
        lon,
        dep_secs.max(0) as u32,
        transit::Schedule {
            day: transit::QueryDay {
                weekday: weekday.max(0) as u32,
                date: date.max(0) as u32,
                prev_weekday: prev_weekday.max(0) as u32,
                prev_date: prev_date.max(0) as u32,
            },
            overlay: overlay.as_ref(),
        },
        max.max(0) as usize,
    );

    let class = match env.find_class("com/vayunmathur/maps/util/OfflineRouter$RawDeparture") {
        Ok(c) => c,
        Err(_) => return null,
    };
    let ctor =
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIIIZZ)V";
    let array = match env.new_object_array(deps.len() as i32, &class, JObject::null()) {
        Ok(a) => a,
        Err(_) => return null,
    };

    for (i, d) in deps.iter().enumerate() {
        let jroute: JObject = match env.new_string(&d.route_name) {
            Ok(s) => s.into(),
            Err(_) => return null,
        };
        let jheadsign: JObject = match env.new_string(&d.headsign) {
            Ok(s) => s.into(),
            Err(_) => return null,
        };
        let jfeed: JObject = match env.new_string(&d.feed) {
            Ok(s) => s.into(),
            Err(_) => return null,
        };
        let jcode: JObject = match env.new_string(&d.stop_code) {
            Ok(s) => s.into(),
            Err(_) => return null,
        };
        let obj = match env.new_object(
            &class,
            ctor,
            &[
                JValue::Object(&jroute),
                JValue::Object(&jheadsign),
                JValue::Object(&jfeed),
                JValue::Object(&jcode),
                JValue::Int(d.route_color as i32),
                JValue::Int(d.route_type as i32),
                JValue::Int(d.dep_secs as i32),
                JValue::Int(d.delay_secs),
                JValue::Bool(d.cancelled as u8),
                JValue::Bool(d.real_time as u8),
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
// JNI: getFeedTimezoneNative (IANA tz of the feed covering a coordinate)
// ---------------------------------------------------------------------------

/// IANA timezone (e.g. `America/Los_Angeles`) of the feed covering
/// `(lat, lon)` in `<base_path>/<feed>.transit`, from the v3 `FEED_TZ` section.
/// Returns null when the pack is absent/stale, doesn't cover the point, or its
/// feed had no `agency.txt` — the caller then falls back to the device zone.
/// Callers need this because the index is world-merged: every query time must be
/// expressed in the feed's local frame, not the device's.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_getFeedTimezoneNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
    feed: JString<'local>,
    lat: jdouble,
    lon: jdouble,
) -> jstring {
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
    if !index.covers(lat, lon) {
        return null;
    }
    let tz = index.timezone_at(lat, lon);
    if tz.is_empty() {
        return null;
    }
    match env.new_string(&tz) {
        Ok(s) => s.into_raw(),
        Err(_) => null,
    }
}

// ---------------------------------------------------------------------------
// JNI: nearestStopMotisIdNative (MOTIS id of the stop nearest a coordinate)
// ---------------------------------------------------------------------------

/// MOTIS/Transitous stop id (e.g. `us-ca-SF-bayarea_901201`) of the stop nearest
/// `(lat, lon)` in `<base_path>/<feed>.transit`, from the v5 `FEED_MOTIS_PREFIX` +
/// `STOP_GTFS_ID` sections. Returns null when the pack is absent, predates v5,
/// doesn't cover the point, or its feed's Transitous source name was unknown at
/// build time.
///
/// Exists because the departure board fetches its realtime overlay *before* it
/// knows which stop the board is for, so it needs to name the stop up front. A
/// local lookup, no network — which is the whole point of baking the id.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_nearestStopMotisIdNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
    feed: JString<'local>,
    lat: jdouble,
    lon: jdouble,
) -> jstring {
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
    if !index.covers(lat, lon) {
        return null;
    }
    match index.nearest_stop_motis_id(lat, lon) {
        Some(id) => match env.new_string(&id) {
            Ok(s) => s.into_raw(),
            Err(_) => null,
        },
        None => null,
    }
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

#[cfg(test)]
mod tests {
    use super::*;

    fn walk_leg(dist_m: f64, coords: Vec<f64>) -> transit::TransitLeg {
        transit::TransitLeg {
            kind: transit::LegKind::Walk,
            name: "Walk".to_string(),
            feed: String::new(),
            from_stop: String::new(),
            to_stop: String::new(),
            headsign: String::new(),
            route_color: 0,
            dep_secs: 28_800,
            arr_secs: 29_100,
            stop_count: 0,
            dist_m,
            coords,
            board_stop_motis_id: String::new(),
            alight_stop_motis_id: String::new(),
        }
    }

    #[test]
    fn walk_snapping_reads_its_endpoints_off_the_straight_line() {
        let leg = walk_leg(300.0, vec![-122.400, 37.700, -122.400, 37.703]);
        assert_eq!(
            walk_snap_endpoints(&leg),
            Some((37.700, -122.400, 37.703, -122.400)),
            "coords are [lon, lat] pairs; endpoints come back as (lat, lon)"
        );
    }

    #[test]
    fn walk_snapping_skips_a_leg_beyond_the_distance_cap() {
        let leg = walk_leg(
            WALK_SNAP_MAX_M + 1.0,
            vec![-122.400, 37.700, -122.400, 37.730],
        );
        assert!(walk_snap_endpoints(&leg).is_none());
    }

    #[test]
    fn walk_snapping_skips_non_walk_legs_and_degenerate_geometry() {
        let mut ride = walk_leg(300.0, vec![-122.400, 37.700, -122.400, 37.703]);
        ride.kind = transit::LegKind::Ride;
        assert!(walk_snap_endpoints(&ride).is_none(), "a ride leg keeps its shape geometry");
        let stub = walk_leg(300.0, vec![-122.400, 37.700]);
        assert!(walk_snap_endpoints(&stub).is_none(), "a single point has no line to route");
    }

    #[test]
    fn a_road_path_far_longer_than_the_straight_line_is_rejected() {
        assert!(walk_snap_plausible(100.0, 240.0));
        assert!(!walk_snap_plausible(100.0, 260.0));
        // A zero-length line has no ratio to test against.
        assert!(walk_snap_plausible(0.0, 500.0));
    }

    #[test]
    fn walk_snapping_is_a_no_op_without_a_graph() {
        // No `init` has run in this test binary, so GRAPH is None and every leg
        // must come back exactly as RAPTOR drew it.
        assert!(graph().is_none(), "no graph is loaded in unit tests");
        let coords = vec![-122.400, 37.700, -122.400, 37.703];
        let mut legs = vec![walk_leg(300.0, coords.clone())];
        snap_walk_legs(&mut legs);
        assert_eq!(legs[0].coords, coords);
        assert_eq!(legs[0].dist_m, 300.0);
    }
}
