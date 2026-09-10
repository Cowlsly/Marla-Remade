//! Core A* routing: edge snapping, search, and path reconstruction.
//!
//! Port of the `--- CORE ROUTING ---` and path-reconstruction sections of
//! `native-lib.cpp`. Kept JNI-free: `perform_search_loop` calls back through an
//! `ensure_traffic` closure for the DRIVING traffic prefetch, and
//! `reconstruct_path` returns plain [`StepData`] that `lib.rs` marshals into the
//! Kotlin `RawStep[]`.

use crate::geometry::*;
use crate::graph::*;
use crate::state::{RadixHeap, RoutingScratchpad};

pub struct SnappedEdge {
    pub node_a: u32,
    pub node_b: u32,
    pub proj_lat: i32,
    pub proj_lon: i32,
    pub dist_a_mm: u32,
    pub dist_b_mm: u32,
    pub type_: u8,
    pub speed_limit: u8,
    pub name_offset: u32,
    pub edge_idx: u64,
    pub segment_idx: u32,
}

impl SnappedEdge {
    fn empty(proj_lat: i32, proj_lon: i32) -> SnappedEdge {
        SnappedEdge {
            node_a: 0xFFFF_FFFF,
            node_b: 0xFFFF_FFFF,
            proj_lat,
            proj_lon,
            dist_a_mm: 0,
            dist_b_mm: 0,
            type_: 0,
            speed_limit: 0,
            name_offset: 0xFFFF_FFFF,
            edge_idx: INVALID_EDGE,
            segment_idx: 0,
        }
    }
}

pub struct RoutingContext {
    pub start: SnappedEdge,
    pub end: SnappedEdge,
    pub target_node: u32,
    pub iterations: i32,
    /// A route that stays on the edge it started on, when both ends snapped to
    /// the same road. See [`direct_path`].
    pub direct: Option<Direct>,
}

/// A route from the start projection to the end projection that never leaves the
/// edge they both snapped to.
///
/// Without this, a trip along one road is forced out to a junction and back,
/// because the A* search can only start and finish at nodes: it is seeded at the
/// start edge's two endpoints and stops at one of the end edge's two endpoints,
/// and on a single edge those are the *same* two nodes. The search therefore
/// "succeeds" immediately at a shared endpoint and the reconstruction walks from
/// the start projection back to that endpoint and forward again.
///
/// Uncompacted, an edge was one pair of adjacent OSM vertices — a few metres —
/// so the detour was invisible. Once degree-2 chains collapse, an edge is a whole
/// road between junctions, and a 20 m walk became a 2 km round trip. Measured on
/// the SLO fixture, 63 of 80 short probes were affected, the worst going from 0 m
/// to 2 km.
pub struct Direct {
    /// Start projection to end projection along the road, inclusive.
    pub coords: Vec<LatLon>,
    /// Ground elevation in metres parallel to [`Direct::coords`].
    pub elevations: Vec<f64>,
    pub dist_mm: u32,
    pub time_10ms: u32,
    pub name_offset: u32,
    /// Road class with any flag bits already masked off.
    pub type_: u8,
    pub speed_limit: u8,
    /// The directed edge actually travelled, for traffic lookup.
    pub edge_idx: u64,
}

/// One coalesced navigation step (pre-localization). Marshaled to Kotlin
/// `OfflineRouter.RawStep` in `lib.rs`.
pub struct StepData {
    pub name_off: u32,
    pub dist_mm: u64,
    pub time_10ms: u64,
    pub coords: Vec<f64>, // flat [lon, lat, lon, lat, ...]
    /// Ground elevation in metres for each coordinate in [`StepData::coords`], so
    /// `elevations.len() == coords.len() / 2`. Interpolated from the per-node
    /// elevation baked into the graph (WS-G): interior polyline vertices are
    /// linearly interpolated by cumulative distance between their edge's two node
    /// elevations. All zero when the graph carries no `elevation.bin`.
    pub elevations: Vec<f64>,
    pub maneuver: i32,
    pub speed_ratio: f64,
    /// Derived turn-lane guidance for this step's maneuver. Each entry is one
    /// available turn lane at the junction (ordered left→right), packed as
    /// `dir_mask * 2 + valid` where `dir_mask` is a bitmask of maneuver-enum
    /// ordinals the lane offers (a real OSM lane can allow several turns, e.g.
    /// through+right) and `valid` is 1 when that lane leads onto the taken route.
    /// Built from real OSM `turn:lanes` when present, else from junction
    /// topology. Empty when the junction has no meaningful choice (single
    /// continuation) or no node context.
    pub lanes: Vec<i32>,
}

/// Sentinel for "no junction node" in [`StepBuilder::add_segment`].
const INVALID_NODE: u32 = 0xFFFF_FFFF;

/// Cumulative ascent and descent (metres) over a sequence of per-coordinate
/// elevations: ascent sums the positive steps, descent the magnitude of the
/// negative ones. Pure, so the route-profile arithmetic is testable without a
/// graph.
pub fn ascent_descent(elevs: &[f64]) -> (f64, f64) {
    let mut asc = 0.0;
    let mut desc = 0.0;
    for w in elevs.windows(2) {
        let d = w[1] - w[0];
        if d > 0.0 {
            asc += d;
        } else {
            desc -= d;
        }
    }
    (asc, desc)
}

/// Total cumulative ascent/descent (metres) of a whole route. Consecutive steps
/// share their join coordinate, so each step after the first contributes its
/// elevations minus that first (duplicate) point, giving one continuous profile.
pub fn route_ascent_descent(steps: &[StepData]) -> (f64, f64) {
    let mut all: Vec<f64> = Vec::new();
    for s in steps {
        if all.is_empty() {
            all.extend_from_slice(&s.elevations);
        } else if s.elevations.len() > 1 {
            all.extend_from_slice(&s.elevations[1..]);
        }
    }
    ascent_descent(&all)
}

/// Linear interpolation of an elevation for every entry of `cum` (a monotonic
/// non-decreasing cumulative-distance array), ramping from `e0` at distance 0 to
/// `e1` at the final distance. A zero-length span reads `e0` throughout. Pure.
fn interp_by_cumdist(cum: &[f64], e0: f64, e1: f64) -> Vec<f64> {
    let total = cum.last().copied().unwrap_or(0.0);
    cum.iter()
        .map(|&c| if total > 0.0 { e0 + (e1 - e0) * c / total } else { e0 })
        .collect()
}

/// Per-point elevations (metres) for `pts` in traversal order, linearly
/// interpolated by cumulative ground distance between the source elevation `e_src`
/// (at `pts[0]`) and the target elevation `e_dst` (at the last point). Interior
/// polyline vertices have no baked elevation of their own, so they ride the ramp
/// between the two junction nodes the edge connects.
fn edge_point_elevations(g: &Graph, pts: &[LatLon], e_src: f64, e_dst: f64) -> Vec<f64> {
    let n = pts.len();
    if n == 0 {
        return Vec::new();
    }
    if n == 1 {
        return vec![e_src];
    }
    let mut cum = vec![0f64; n];
    for i in 1..n {
        let d = fast_dist_mm(g, pts[i - 1].lat_e7, pts[i - 1].lon_e7, pts[i].lat_e7, pts[i].lon_e7);
        cum[i] = cum[i - 1] + f64::from(d);
    }
    interp_by_cumdist(&cum, e_src, e_dst)
}

/// The elevation (metres) of a projection sitting `dist_a_mm` along an edge whose
/// two endpoint nodes are at `e_a` and `e_b`, `dist_b_mm` being the remaining
/// distance to the far node. A degenerate zero-length edge reads `e_a`.
fn proj_elevation(g: &Graph, node_a: u32, node_b: u32, dist_a_mm: u32, dist_b_mm: u32) -> f64 {
    let e_a = f64::from(g.node_elevation(node_a));
    let e_b = f64::from(g.node_elevation(node_b));
    let total = f64::from(dist_a_mm) + f64::from(dist_b_mm);
    if total > 0.0 {
        e_a + (e_b - e_a) * f64::from(dist_a_mm) / total
    } else {
        e_a
    }
}

/// Convert an OSM lane indication mask (`LANE_*` bits from the generator) into a
/// maneuver-ordinal bitmask (bit `i` set => `Maneuver` ordinal `i` is offered by
/// the lane). Ordinals match [`get_maneuver`] and the Kotlin
/// `RouteService.API.Maneuver` enum. An unmarked ("none") lane maps to STRAIGHT
/// so every lane always carries at least one arrow.
fn osm_mask_to_dir_mask(osm: u16) -> u32 {
    let mut m: u32 = 0;
    if osm & LANE_THROUGH != 0 {
        m |= 1 << 9; // STRAIGHT
    }
    if osm & LANE_NONE != 0 {
        m |= 1 << 9; // unmarked -> through
    }
    if osm & LANE_LEFT != 0 {
        m |= 1 << 4; // TURN_LEFT
    }
    if osm & LANE_SLIGHT_LEFT != 0 {
        m |= 1 << 1; // TURN_SLIGHT_LEFT
    }
    if osm & LANE_SHARP_LEFT != 0 {
        m |= 1 << 2; // TURN_SHARP_LEFT
    }
    if osm & LANE_RIGHT != 0 {
        m |= 1 << 8; // TURN_RIGHT
    }
    if osm & LANE_SLIGHT_RIGHT != 0 {
        m |= 1 << 5; // TURN_SLIGHT_RIGHT
    }
    if osm & LANE_SHARP_RIGHT != 0 {
        m |= 1 << 6; // TURN_SHARP_RIGHT
    }
    if osm & LANE_REVERSE != 0 {
        m |= 1 << 3; // UTURN_LEFT
    }
    if osm & LANE_MERGE_TO_LEFT != 0 {
        m |= 1 << 1; // slight left
    }
    if osm & LANE_MERGE_TO_RIGHT != 0 {
        m |= 1 << 5; // slight right
    }
    if m == 0 {
        m |= 1 << 9; // default to STRAIGHT
    }
    m
}

/// Whether a lane whose maneuver set is `dir_mask` leads onto the route when the
/// taken maneuver is `taken`. Exact match, with a small tolerance so a dedicated
/// left lane also serves a slight/sharp-left maneuver (and symmetrically right).
fn lane_serves(dir_mask: u32, taken: i32) -> bool {
    let has = |o: i32| o >= 0 && o < 31 && (dir_mask & (1u32 << o)) != 0;
    match taken {
        9 => has(9),           // STRAIGHT
        4 => has(4) || has(1), // TURN_LEFT
        1 => has(1) || has(4), // TURN_SLIGHT_LEFT
        2 => has(2) || has(4), // TURN_SHARP_LEFT
        3 => has(3),           // UTURN_LEFT
        8 => has(8) || has(5), // TURN_RIGHT
        5 => has(5) || has(8), // TURN_SLIGHT_RIGHT
        6 => has(6) || has(8), // TURN_SHARP_RIGHT
        _ => has(taken),
    }
}

/// Build packed lane guidance from the real OSM lanes of the `approach` edge —
/// the edge the driver is on as they reach the maneuver junction. Each returned
/// int is `dir_mask * 2 + valid` where `dir_mask` is a maneuver-ordinal bitmask
/// (a lane can offer several turns) and `valid` marks a lane that leads onto the
/// taken route. Returns `None` when the edge has no real lane tags, so callers
/// fall back to [`junction_lanes`] topology inference.
fn real_lanes(g: &Graph, approach: u64, taken: i32) -> Option<Vec<i32>> {
    if approach == INVALID_EDGE {
        return None;
    }
    let masks = g.edge_lane_masks(approach)?;
    if masks.is_empty() {
        return None;
    }
    let packed = masks
        .iter()
        .map(|&osm| {
            let dir_mask = osm_mask_to_dir_mask(osm);
            let valid = if lane_serves(dir_mask, taken) { 1 } else { 0 };
            ((dir_mask as i32) << 1) | valid
        })
        .collect();
    Some(packed)
}

/// Derive the available turn lanes at `junction` for a maneuver taken with the
/// given `incoming_bearing`. Purely topological: enumerates the junction's
/// outgoing driveable edges, classifies each by turn direction relative to the
/// incoming heading, and marks the one(s) matching `taken`. Returns packed
/// `dir_mask * 2 + valid` entries sorted left→right (each topology lane offers a
/// single direction, so `dir_mask` has one bit set), or empty when there is no
/// real choice (<= 1 option). Used as the fallback when an edge has no real OSM
/// turn:lanes.
fn junction_lanes(
    g: &Graph,
    junction: u32,
    prev_node: u32,
    incoming_bearing: f64,
    taken: i32,
) -> Vec<i32> {
    if junction >= g.node_count {
        return Vec::new();
    }
    let jnode = g.node(junction);
    let (s, e_ptr) = g.edge_range(junction);
    let jlat = jnode.lat_e7;
    let jlon = jnode.lon_e7;

    let mut coords = [LatLon { lat_e7: 0, lon_e7: 0 }; 256];
    // (signed angle diff for sorting, turn direction code)
    let mut opts: Vec<(f64, i32)> = Vec::new();

    for k in s..e_ptr {
        let edge = g.edge(junction, k);
        if !is_mode_allowed(edge.type_, DRIVING) {
            continue;
        }
        if edge.target >= g.node_count {
            continue;
        }
        // Skip the edge back the way we came — that's a U-turn, not a lane.
        if edge.target == prev_node {
            continue;
        }

        // Outgoing heading: junction -> first vertex leaving the junction.
        let (nlat, nlon) = match g.get_edge_coordinates_from(junction, k, &mut coords) {
            Some((count, is_rev)) if count >= 2 => {
                let p1 = get_pt_at(&coords, count, is_rev, 1);
                (p1.lat_e7, p1.lon_e7)
            }
            _ => {
                let n = g.get_node(edge.target);
                (n.lat_e7, n.lon_e7)
            }
        };

        let out_bearing = get_bearing(jlat, jlon, nlat, nlon);
        let mut ad = out_bearing - incoming_bearing;
        while ad < -180.0 {
            ad += 360.0;
        }
        while ad > 180.0 {
            ad -= 360.0;
        }
        let dir = get_maneuver(incoming_bearing, out_bearing);
        if !opts.iter().any(|&(_, d)| d == dir) {
            opts.push((ad, dir));
        }
        if opts.len() >= 8 {
            break;
        }
    }

    if opts.len() <= 1 {
        return Vec::new();
    }
    opts.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
    opts.iter()
        .map(|&(_, dir)| {
            let dir_mask: i32 = if (0..31).contains(&dir) { 1 << dir } else { 0 };
            (dir_mask << 1) | if dir == taken { 1 } else { 0 }
        })
        .collect()
}

/// Snap a WGS84 point to the nearest routable edge for `mode`.
pub fn find_nearest_edge(g: &Graph, lat: f64, lon: f64, mode: i32) -> SnappedEdge {
    let target_spatial = Graph::latlng_to_spatial(lat, lon);
    let p_lat = (lat * 1e7) as i32;
    let p_lon = (lon * 1e7) as i32;
    let mut best = SnappedEdge::empty(p_lat, p_lon);

    if g.node_count == 0 {
        return best;
    }

    let mut min_snap_dist: u32 = 0xFFFF_FFFF;

    // Binary search the globally Morton-sorted node array for the node just
    // below the target code.
    let mut low: u32 = 0;
    let mut high: u32 = g.node_count - 1;
    let mut local_center: u32 = 0;
    while low <= high {
        let mid = low + (high - low) / 2;
        if Graph::node_spatial_id(&g.node(mid)) < target_spatial {
            local_center = mid;
            low = mid + 1;
        } else {
            if mid == 0 {
                break;
            }
            high = mid - 1;
        }
    }

    let mut coords = [LatLon { lat_e7: 0, lon_e7: 0 }; 256];

    const WINDOW: i32 = 800;
    let lo = std::cmp::max(0, local_center as i32 - WINDOW);
    let hi = std::cmp::min(g.node_count as i32 - 1, local_center as i32 + WINDOW);
    for i in lo..=hi {
        let u_global = i as u32;
        let node_u = g.node(u_global);
        let (e_start, e_ptr) = g.edge_range(u_global);
        for j in e_start..e_ptr {
            let e = g.edge(u_global, j);
            if !is_mode_allowed(e.type_, mode) {
                continue;
            }
            if e.target >= g.node_count {
                continue;
            }

            if let Some((count, is_reversed)) = g.get_edge_coordinates_from(u_global, j, &mut coords)
            {
                if count >= 2 {
                    let num_pts = count;
                    let mut current_dist_from_start_mm: u32 = 0;
                    for p in 0..num_pts - 1 {
                        let p1 = get_pt_at(&coords, count, is_reversed, p);
                        let p2 = get_pt_at(&coords, count, is_reversed, p + 1);

                        let proj =
                            get_projection(g, p_lat, p_lon, p1.lat_e7, p1.lon_e7, p2.lat_e7, p2.lon_e7);
                        if proj.dist_mm < min_snap_dist {
                            min_snap_dist = proj.dist_mm;
                            best.node_a = u_global;
                            best.node_b = e.target;
                            best.proj_lat = proj.lat_e7;
                            best.proj_lon = proj.lon_e7;
                            best.type_ = e.type_;
                            best.speed_limit = e.speed_limit;
                            best.name_offset = g.edge_name_offset(j).unwrap_or(NO_NAME);
                            best.edge_idx = j;
                            best.segment_idx = p;

                            let dist_to_proj_seg_mm =
                                fast_dist_mm(g, p1.lat_e7, p1.lon_e7, proj.lat_e7, proj.lon_e7);
                            best.dist_a_mm = current_dist_from_start_mm + dist_to_proj_seg_mm;
                            best.dist_b_mm = e.dist_mm.saturating_sub(best.dist_a_mm);
                        }
                        current_dist_from_start_mm +=
                            fast_dist_mm(g, p1.lat_e7, p1.lon_e7, p2.lat_e7, p2.lon_e7);
                    }
                    continue;
                }
            }

            // No geometry: snap to the straight node-to-node segment.
            let node_v = g.node(e.target);
            let p = get_projection(
                g, p_lat, p_lon, node_u.lat_e7, node_u.lon_e7, node_v.lat_e7, node_v.lon_e7,
            );
            if p.dist_mm < min_snap_dist {
                min_snap_dist = p.dist_mm;
                best.node_a = u_global;
                best.node_b = e.target;
                best.proj_lat = p.lat_e7;
                best.proj_lon = p.lon_e7;
                best.dist_a_mm = fast_dist_mm(g, p.lat_e7, p.lon_e7, node_u.lat_e7, node_u.lon_e7);
                best.dist_b_mm = fast_dist_mm(g, p.lat_e7, p.lon_e7, node_v.lat_e7, node_v.lon_e7);
                best.type_ = e.type_;
                best.speed_limit = e.speed_limit;
                best.name_offset = g.edge_name_offset(j).unwrap_or(NO_NAME);
                best.edge_idx = j;
                best.segment_idx = 0;
            }
        }
    }
    best
}

/// Snap endpoints, seed the open set. Returns `None` if snapping fails.
#[allow(clippy::too_many_arguments)]
pub fn prepare_routing(
    g: &Graph,
    traffic: &TrafficSpeeds,
    ensure_traffic: &mut dyn FnMut(i32, i32),
    s_lat: f64,
    s_lon: f64,
    e_lat: f64,
    e_lon: f64,
    mode: i32,
    scratch: &mut RoutingScratchpad,
    heap: &mut RadixHeap,
) -> Option<RoutingContext> {
    scratch.reset();
    heap.clear();

    let start = find_nearest_edge(g, s_lat, s_lon, mode);
    let end = find_nearest_edge(g, e_lat, e_lon, mode);
    if start.node_a == 0xFFFF_FFFF || end.node_a == 0xFFFF_FFFF {
        return None;
    }

    if mode == DRIVING {
        ensure_traffic(start.proj_lat, start.proj_lon);
    }

    let push = |node: u32, travel_dist_mm: u32, scratch: &mut RoutingScratchpad, heap: &mut RadixHeap| {
        let t_actual = get_edge_time_10ms(
            g, traffic, INVALID_EDGE, travel_dist_mm, start.type_, start.speed_limit, mode,
        );
        {
            let entry = scratch.get_entry(node);
            entry.g_fwd = t_actual;
            entry.g_bwd = t_actual;
        }
        let n_data = g.get_node(node);
        let h = heuristic_time_10ms(g, n_data.lat_e7, n_data.lon_e7, end.proj_lat, end.proj_lon, mode);
        heap.push(t_actual.wrapping_add(h), node);
    };
    push(start.node_a, start.dist_a_mm, scratch, heap);
    push(start.node_b, start.dist_b_mm, scratch, heap);

    let direct = direct_path(g, traffic, &start, &end, mode);

    Some(RoutingContext {
        start,
        end,
        target_node: 0xFFFF_FFFF,
        iterations: 0,
        direct,
    })
}

/// Polyline of `edge_idx` in source-to-target order, falling back to the straight
/// chord when the edge stores no geometry.
fn edge_polyline(g: &Graph, edge_idx: u64, source: u32, target: u32) -> Vec<LatLon> {
    let mut buf = [LatLon { lat_e7: 0, lon_e7: 0 }; 256];
    if let Some((count, is_reversed)) = g.get_edge_coordinates_from(source, edge_idx, &mut buf) {
        if count >= 2 {
            return (0..count).map(|p| get_pt_at(&buf, count, is_reversed, p)).collect();
        }
    }
    let a = g.get_node(source);
    let b = g.get_node(target);
    vec![
        LatLon { lat_e7: a.lat_e7, lon_e7: a.lon_e7 },
        LatLon { lat_e7: b.lat_e7, lon_e7: b.lon_e7 },
    ]
}

/// The other direction of the same road: the *only* edge from `target` back to
/// `source`, or `None` when there is none or more than one.
///
/// Uniqueness is required, not incidental. `graph.rs` resolves
/// [`REVERSE_GEOMETRY_FLAG`] by taking the first such edge, and the generator's
/// `twin_is_unique` refuses to set the flag unless exactly one exists — so
/// accepting the first match here would let a *parallel but different* road
/// between the same two junctions pass as the twin, and its name, type and speed
/// limit would then be attached to a polyline belonging to the other road. The
/// synthetic transit-stop connectors make that shape real: the graph fixture has
/// two edges from one node to another, a collapsed street and a one-way service
/// road.
///
/// A self-loop (an anchorless ring collapsed to one node) would otherwise match
/// itself and so claim that its own reverse direction exists.
fn twin_edge(g: &Graph, source: u32, target: u32) -> Option<u64> {
    if target >= g.node_count || source == target {
        return None;
    }
    let (s, e) = g.edge_range(target);
    let mut found = None;
    for k in s..e {
        if g.edge_targets(k, target, source) {
            if found.is_some() {
                return None;
            }
            found = Some(k);
        }
    }
    found
}

/// Where a point sits along `poly`: `(segment index, distance from the start)`.
/// The point is expected to already lie on the polyline, so the nearest segment
/// is unambiguous.
fn locate_on(g: &Graph, poly: &[LatLon], lat_e7: i32, lon_e7: i32) -> (usize, u32) {
    let mut best = (0usize, 0u32);
    let mut best_off = u32::MAX;
    let mut acc: u32 = 0;
    for i in 0..poly.len() - 1 {
        let (a, b) = (poly[i], poly[i + 1]);
        let p = get_projection(g, lat_e7, lon_e7, a.lat_e7, a.lon_e7, b.lat_e7, b.lon_e7);
        if p.dist_mm < best_off {
            best_off = p.dist_mm;
            best = (i, acc.saturating_add(fast_dist_mm(g, a.lat_e7, a.lon_e7, p.lat_e7, p.lon_e7)));
        }
        acc = acc.saturating_add(fast_dist_mm(g, a.lat_e7, a.lon_e7, b.lat_e7, b.lon_e7));
    }
    best
}

/// The stretch of `poly` between two located points, endpoints included. Always
/// at least two points: when both project onto the same segment the result is
/// just the two projections, which for coincident ends is a valid zero-length
/// route.
fn sub_polyline(poly: &[LatLon], from: (usize, LatLon), to: (usize, LatLon)) -> Vec<LatLon> {
    let mut out = vec![from.1];
    for p in poly.iter().take(to.0 + 1).skip(from.0 + 1) {
        out.push(*p);
    }
    out.push(to.1);
    out
}

/// Build the on-edge route when both ends snapped to the same road, or `None`
/// when they did not or when travelling it in the required direction is not
/// allowed.
fn direct_path(
    g: &Graph,
    traffic: &TrafficSpeeds,
    start: &SnappedEdge,
    end: &SnappedEdge,
    mode: i32,
) -> Option<Direct> {
    if start.edge_idx == INVALID_EDGE || end.edge_idx == INVALID_EDGE {
        return None;
    }
    let twin = twin_edge(g, start.node_a, start.node_b);
    // Same directed edge, or the one unambiguous other direction of one road.
    // Anything else — including a second, parallel road between the same pair of
    // nodes — is left to the search, because its polyline is a different road.
    if end.edge_idx != start.edge_idx && Some(end.edge_idx) != twin {
        return None;
    }

    let poly = edge_polyline(g, start.edge_idx, start.node_a, start.node_b);
    if poly.len() < 2 {
        return None;
    }
    let s_pt = LatLon { lat_e7: start.proj_lat, lon_e7: start.proj_lon };
    let e_pt = LatLon { lat_e7: end.proj_lat, lon_e7: end.proj_lon };
    let s_at = locate_on(g, &poly, s_pt.lat_e7, s_pt.lon_e7);
    let e_at = locate_on(g, &poly, e_pt.lat_e7, e_pt.lon_e7);

    // Going backwards along the polyline means driving the twin, which only
    // exists when the road is not one-way. The twin leaves the *other* end, so the
    // source has to be chosen with the edge, not recovered afterwards.
    let forward = s_at <= e_at;
    let (edge_idx, edge_source) = if forward {
        (start.edge_idx, start.node_a)
    } else {
        (twin?, start.node_b)
    };
    let e = g.edge(edge_source, edge_idx);
    if !is_mode_allowed(e.type_, mode) {
        return None;
    }

    let coords = if forward {
        sub_polyline(&poly, (s_at.0, s_pt), (e_at.0, e_pt))
    } else {
        let mut c = sub_polyline(&poly, (e_at.0, e_pt), (s_at.0, s_pt));
        c.reverse();
        c
    };

    let mut dist_mm: u32 = 0;
    for w in coords.windows(2) {
        dist_mm = dist_mm
            .saturating_add(fast_dist_mm(g, w[0].lat_e7, w[0].lon_e7, w[1].lat_e7, w[1].lon_e7));
    }
    let type_ = e.type_ & ROAD_TYPE_MASK;
    // Elevation ramps from the start projection to the end projection: each is itself an
    // interpolation of the edge's two node elevations at its own distance along the road, and the
    // interior coords ride the cumulative-distance ramp between them.
    let elev_s = proj_elevation(g, start.node_a, start.node_b, start.dist_a_mm, start.dist_b_mm);
    let elev_e = proj_elevation(g, end.node_a, end.node_b, end.dist_a_mm, end.dist_b_mm);
    let elevations = edge_point_elevations(g, &coords, elev_s, elev_e);
    Some(Direct {
        time_10ms: get_edge_time_10ms(g, traffic, edge_idx, dist_mm, type_, e.speed_limit, mode),
        coords,
        elevations,
        dist_mm,
        name_offset: g.edge_name_offset(edge_idx).unwrap_or(NO_NAME),
        type_,
        speed_limit: e.speed_limit,
        edge_idx,
    })
}

/// A* main loop. Fills `ctx.target_node` on success.
pub fn perform_search_loop(
    g: &Graph,
    traffic: &TrafficSpeeds,
    ensure_traffic: &mut dyn FnMut(i32, i32),
    mode: i32,
    ctx: &mut RoutingContext,
    scratch: &mut RoutingScratchpad,
    heap: &mut RadixHeap,
) {
    // Cap is a safety net only. The 36M-node CA graph needs ~6.4M expansions for
    // the longest routes (SF->LA), so the old 1M cap aborted them ("no route").
    // Reaching one end of the destination's edge is not the same as arriving.
    // The search can only stop at nodes, so it stops at `end.node_a` or
    // `end.node_b` and `reconstruct_path` then walks the leftover stub to the
    // projection. Stopping at whichever anchor is reached *first* ignores how
    // long that stub is, and after compaction the two anchors are a whole road
    // apart rather than a few metres, so the wrong choice costs the length of
    // the street. Score both completions and keep the better one.
    let mut best_total = u32::MAX;
    while !heap.empty() && ctx.iterations < 25_000_000 {
        ctx.iterations += 1;
        let u = heap.pop();

        let u_cost = scratch.get_entry(u).g_fwd;
        if u >= g.node_count {
            continue;
        }
        let n_u = g.node(u);

        // `f` is a lower bound on any route through `u`, and the heap pops in
        // non-decreasing `f` order, so once it cannot beat a completed route
        // nothing left can.
        let f_u = u_cost.saturating_add(heuristic_time_10ms(
            g, n_u.lat_e7, n_u.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon, mode,
        ));
        if f_u >= best_total {
            break;
        }

        if u == ctx.end.node_a || u == ctx.end.node_b {
            let stub = if u == ctx.end.node_a {
                ctx.end.dist_a_mm
            } else {
                ctx.end.dist_b_mm
            };
            let total = u_cost.saturating_add(get_edge_time_10ms(
                g, traffic, INVALID_EDGE, stub, ctx.end.type_, ctx.end.speed_limit, mode,
            ));
            if total < best_total {
                best_total = total;
                ctx.target_node = u;
            }
            // Keep expanding: this anchor may also be on the way to the other.
        }

        if mode == DRIVING {
            ensure_traffic(n_u.lat_e7, n_u.lon_e7);
        }
        let (s, e_ptr) = g.edge_range(u);

        for i in s..e_ptr {
            let edge = g.edge(u, i);
            if !is_mode_allowed(edge.type_, mode) {
                continue;
            }

            let travel_time =
                get_edge_time_10ms(g, traffic, i, edge.dist_mm, edge.type_, edge.speed_limit, mode);

            let v = edge.target;
            let new_g = u_cost.wrapping_add(travel_time);
            let update = {
                let entry_v = scratch.get_entry(v);
                if new_g < entry_v.g_fwd {
                    entry_v.g_fwd = new_g;
                    entry_v.g_bwd = new_g;
                    entry_v.p_fwd = u;
                    true
                } else {
                    false
                }
            };
            if update {
                let n_v = g.get_node(v);
                let h = heuristic_time_10ms(
                    g, n_v.lat_e7, n_v.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon, mode,
                );
                heap.push(new_g.wrapping_add(h), v);
            }
        }
    }
}

/// Accumulates coalesced steps while walking the reconstructed path.
struct StepBuilder<'a> {
    g: &'a Graph,
    traffic: &'a TrafficSpeeds,
    mode: i32,
    steps: Vec<StepData>,
    last_bearing: f64,
    /// Junction node for the next segment's maneuver (or [`INVALID_NODE`]).
    pending_junction: u32,
    /// Node we arrive from at that junction, to exclude the U-turn lane.
    pending_prev: u32,
    /// Edge the driver is on approaching that junction, source of real OSM
    /// turn:lanes ([`INVALID_EDGE`] when unknown / at the first maneuver).
    pending_approach: u64,
}

impl<'a> StepBuilder<'a> {
    #[allow(clippy::too_many_arguments)]
    fn add_segment(
        &mut self,
        lat1: f64,
        lon1: f64,
        lat2: f64,
        lon2: f64,
        name_off: u32,
        type_: u8,
        limit: u8,
        dist_mm: u32,
        edge_idx: u64,
        elev1: f64,
        elev2: f64,
    ) {
        // Junction context for lane derivation is set on the builder before the
        // first segment of a main-path edge and consumed (then cleared) here.
        let junction_node = self.pending_junction;
        let prev_node = self.pending_prev;
        let approach_edge = self.pending_approach;
        self.pending_junction = INVALID_NODE;
        self.pending_prev = INVALID_NODE;
        self.pending_approach = INVALID_EDGE;
        let mut ratio = 1.0;
        if edge_idx != INVALID_EDGE {
            let traffic_speed = *self.traffic.get(&edge_idx).unwrap_or(&0);
            if traffic_speed > 0 && limit > 0 {
                ratio = traffic_speed as f64 / limit as f64;
            }
        }

        let time_10ms =
            get_edge_time_10ms(self.g, self.traffic, edge_idx, dist_mm, type_, limit, self.mode);
        let bearing = get_bearing(
            (lat1 * 1e7) as i32,
            (lon1 * 1e7) as i32,
            (lat2 * 1e7) as i32,
            (lon2 * 1e7) as i32,
        );

        let ratio_cat = |r: f64| -> i32 {
            if r < 0.5 {
                0
            } else if r < 0.9 {
                1
            } else {
                2
            }
        };

        let maneuver = if self.steps.is_empty() {
            0
        } else {
            get_maneuver(self.last_bearing, bearing)
        };

        let push_new = {
            match self.steps.last() {
                None => true,
                Some(back) => {
                    name_off != back.name_off
                        || ratio_cat(ratio) != ratio_cat(back.speed_ratio)
                        || (maneuver != 9 && maneuver != 0)
                }
            }
        };
        if push_new {
            let lanes = if junction_node != INVALID_NODE {
                // Prefer real OSM turn:lanes from the approach edge; fall back to
                // topology inference at the junction when the edge has no tags.
                real_lanes(self.g, approach_edge, maneuver).unwrap_or_else(|| {
                    junction_lanes(self.g, junction_node, prev_node, self.last_bearing, maneuver)
                })
            } else {
                Vec::new()
            };
            self.steps.push(StepData {
                name_off,
                dist_mm: 0,
                time_10ms: 0,
                coords: vec![lon1, lat1],
                elevations: vec![elev1],
                maneuver,
                speed_ratio: ratio,
                lanes,
            });
        }

        let back = self.steps.last_mut().unwrap();
        back.dist_mm += dist_mm as u64;
        back.time_10ms += time_10ms as u64;
        back.coords.push(lon2);
        back.coords.push(lat2);
        back.elevations.push(elev2);
        self.last_bearing = bearing;
    }
}

/// Total time of the searched route, including the stub from the last node to the
/// destination projection. [`u32::MAX`] when the search found nothing.
fn network_time_10ms(
    g: &Graph,
    traffic: &TrafficSpeeds,
    mode: i32,
    ctx: &RoutingContext,
    scratch: &mut RoutingScratchpad,
) -> u32 {
    if ctx.target_node == INVALID_NODE {
        return u32::MAX;
    }
    let reached = scratch.get_entry(ctx.target_node).g_fwd;
    // `g_fwd` stops at the node; the walk from there to the projection is the
    // part `reconstruct_path`'s end stub adds.
    let stub = if ctx.target_node == ctx.end.node_a {
        ctx.end.dist_a_mm
    } else {
        ctx.end.dist_b_mm
    };
    let stub_time = get_edge_time_10ms(
        g, traffic, INVALID_EDGE, stub, ctx.end.type_, ctx.end.speed_limit, mode,
    );
    reached.saturating_add(stub_time)
}

/// Turn a [`Direct`] route into steps, reusing the same coalescing the searched
/// path gets so the two are indistinguishable downstream.
fn direct_steps(g: &Graph, traffic: &TrafficSpeeds, mode: i32, d: &Direct) -> Vec<StepData> {
    let mut b = StepBuilder {
        g,
        traffic,
        mode,
        steps: Vec::new(),
        last_bearing: 0.0,
        pending_junction: INVALID_NODE,
        pending_prev: INVALID_NODE,
        pending_approach: INVALID_EDGE,
    };
    for (i, w) in d.coords.windows(2).enumerate() {
        let dist = fast_dist_mm(g, w[0].lat_e7, w[0].lon_e7, w[1].lat_e7, w[1].lon_e7);
        b.add_segment(
            f64::from(w[0].lat_e7) * 1e-7,
            f64::from(w[0].lon_e7) * 1e-7,
            f64::from(w[1].lat_e7) * 1e-7,
            f64::from(w[1].lon_e7) * 1e-7,
            d.name_offset,
            d.type_,
            d.speed_limit,
            dist,
            d.edge_idx,
            d.elevations[i],
            d.elevations[i + 1],
        );
    }
    b.steps
}

#[cfg(test)]
mod tests {
    use super::*;

    fn step(elevations: Vec<f64>) -> StepData {
        StepData {
            name_off: NO_NAME,
            dist_mm: 0,
            time_10ms: 0,
            coords: elevations.iter().flat_map(|_| [0.0, 0.0]).collect(),
            elevations,
            maneuver: 0,
            speed_ratio: 1.0,
            lanes: Vec::new(),
        }
    }

    #[test]
    fn ascent_and_descent_sum_the_signed_steps() {
        // A climb to 10, back to 5, up to 20: ascent 10 + 15 = 25, descent 5.
        let (asc, desc) = ascent_descent(&[0.0, 10.0, 5.0, 20.0]);
        assert_eq!(asc, 25.0);
        assert_eq!(desc, 5.0);
        // A flat profile has neither.
        assert_eq!(ascent_descent(&[7.0, 7.0, 7.0]), (0.0, 0.0));
        // Too short to have any step.
        assert_eq!(ascent_descent(&[3.0]), (0.0, 0.0));
    }

    #[test]
    fn a_route_over_synthetic_terrain_has_the_expected_profile_and_cumulative_ascent() {
        // Two coalesced steps that share their join coordinate (elevation 5). The route profile is
        // the concatenation minus that duplicate: [0, 10, 5, 20, 15].
        let steps = vec![step(vec![0.0, 10.0, 5.0]), step(vec![5.0, 20.0, 15.0])];
        let (asc, desc) = route_ascent_descent(&steps);
        // Climbs: 0->10 (+10) and 5->20 (+15) = 25. Drops: 10->5 (-5) and 20->15 (-5) = 10.
        assert_eq!(asc, 25.0, "cumulative ascent");
        assert_eq!(desc, 10.0, "cumulative descent");
    }

    #[test]
    fn a_single_climbing_edge_interpolates_monotonically() {
        // Interior vertices ride the ramp between the two node elevations by cumulative distance.
        let elevs = interp_by_cumdist(&[0.0, 25.0, 50.0, 100.0], 0.0, 200.0);
        assert_eq!(elevs, vec![0.0, 50.0, 100.0, 200.0]);
        assert!(elevs.windows(2).all(|w| w[1] >= w[0]), "a climb is monotonic non-decreasing");
    }

    #[test]
    fn a_zero_length_span_reads_the_source_elevation() {
        // A degenerate edge (both endpoints coincident) has no ramp; every point reads e0.
        assert_eq!(interp_by_cumdist(&[0.0, 0.0, 0.0], 42.0, 99.0), vec![42.0, 42.0, 42.0]);
    }
}

/// Rebuild the step list from `ctx.target_node`. Returns an empty vec if no
/// path exists.
pub fn reconstruct_path(
    g: &Graph,
    traffic: &TrafficSpeeds,
    mode: i32,
    ctx: &RoutingContext,
    scratch: &mut RoutingScratchpad,
) -> Vec<StepData> {
    // Staying on the edge both ends snapped to usually wins, but not always: a
    // C-shaped road whose two ends meet a straight one is faster left and
    // rejoined. Take whichever is quicker, and take the on-edge route outright
    // when the search found nothing at all.
    if let Some(d) = &ctx.direct {
        if d.time_10ms <= network_time_10ms(g, traffic, mode, ctx, scratch) {
            return direct_steps(g, traffic, mode, d);
        }
    }

    let mut path_nodes: Vec<u32> = Vec::new();
    let mut curr = ctx.target_node;
    let mut safety = 0u32;
    while curr != 0xFFFF_FFFF && safety < 1_000_000 {
        path_nodes.push(curr);
        curr = scratch.get_entry(curr).p_fwd;
        safety += 1;
    }
    path_nodes.reverse();
    if path_nodes.is_empty() {
        return Vec::new();
    }

    let mut b = StepBuilder {
        g,
        traffic,
        mode,
        steps: Vec::new(),
        last_bearing: 0.0,
        pending_junction: INVALID_NODE,
        pending_prev: INVALID_NODE,
        pending_approach: INVALID_EDGE,
    };

    let mut coords = [LatLon { lat_e7: 0, lon_e7: 0 }; 256];

    // 1. Start stub: proj_s -> path_nodes[0]
    {
        let n0 = path_nodes[0];
        let node0 = g.get_node(n0);
        let j = ctx.start.edge_idx;
        let geom = if j != INVALID_EDGE {
            g.get_edge_coordinates_from(ctx.start.node_a, j, &mut coords)
        } else {
            None
        };

        if let Some((count, is_reversed)) = geom.filter(|&(c, _)| c >= 2) {
            let num_pts = count;
            let seg_idx = ctx.start.segment_idx;
            // Per-point elevation for the whole start edge (node_a -> node_b traversal order), and
            // the projection's own interpolated elevation where the route joins the edge.
            let pts: Vec<LatLon> =
                (0..count).map(|p| get_pt_at(&coords, count, is_reversed, p)).collect();
            let elevs = edge_point_elevations(
                g,
                &pts,
                f64::from(g.node_elevation(ctx.start.node_a)),
                f64::from(g.node_elevation(ctx.start.node_b)),
            );
            let elev_proj = proj_elevation(
                g, ctx.start.node_a, ctx.start.node_b, ctx.start.dist_a_mm, ctx.start.dist_b_mm,
            );

            if n0 == ctx.start.node_a {
                let p_next = pts[seg_idx as usize];
                let d1 = fast_dist_mm(
                    g, ctx.start.proj_lat, ctx.start.proj_lon, p_next.lat_e7, p_next.lon_e7,
                );
                b.add_segment(
                    ctx.start.proj_lat as f64 * 1e-7,
                    ctx.start.proj_lon as f64 * 1e-7,
                    p_next.lat_e7 as f64 * 1e-7,
                    p_next.lon_e7 as f64 * 1e-7,
                    ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, d1, j,
                    elev_proj, elevs[seg_idx as usize],
                );
                let mut p = seg_idx as i32;
                while p >= 1 {
                    let p_from = pts[p as usize];
                    let p_to = pts[(p - 1) as usize];
                    let d_seg = fast_dist_mm(g, p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    b.add_segment(
                        p_from.lat_e7 as f64 * 1e-7, p_from.lon_e7 as f64 * 1e-7,
                        p_to.lat_e7 as f64 * 1e-7, p_to.lon_e7 as f64 * 1e-7,
                        ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, d_seg, j,
                        elevs[p as usize], elevs[(p - 1) as usize],
                    );
                    p -= 1;
                }
            } else {
                let p_next = pts[seg_idx as usize + 1];
                let d1 = fast_dist_mm(
                    g, ctx.start.proj_lat, ctx.start.proj_lon, p_next.lat_e7, p_next.lon_e7,
                );
                b.add_segment(
                    ctx.start.proj_lat as f64 * 1e-7,
                    ctx.start.proj_lon as f64 * 1e-7,
                    p_next.lat_e7 as f64 * 1e-7,
                    p_next.lon_e7 as f64 * 1e-7,
                    ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, d1, j,
                    elev_proj, elevs[seg_idx as usize + 1],
                );
                for p in seg_idx + 1..num_pts - 1 {
                    let p_from = pts[p as usize];
                    let p_to = pts[p as usize + 1];
                    let d_seg = fast_dist_mm(g, p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    b.add_segment(
                        p_from.lat_e7 as f64 * 1e-7, p_from.lon_e7 as f64 * 1e-7,
                        p_to.lat_e7 as f64 * 1e-7, p_to.lon_e7 as f64 * 1e-7,
                        ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, d_seg, j,
                        elevs[p as usize], elevs[p as usize + 1],
                    );
                }
            }
        } else {
            let dist = if n0 == ctx.start.node_a {
                ctx.start.dist_a_mm
            } else {
                ctx.start.dist_b_mm
            };
            let elev_proj = proj_elevation(
                g, ctx.start.node_a, ctx.start.node_b, ctx.start.dist_a_mm, ctx.start.dist_b_mm,
            );
            b.add_segment(
                ctx.start.proj_lat as f64 * 1e-7,
                ctx.start.proj_lon as f64 * 1e-7,
                node0.lat_e7 as f64 * 1e-7,
                node0.lon_e7 as f64 * 1e-7,
                ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, dist, INVALID_EDGE,
                elev_proj, f64::from(g.node_elevation(n0)),
            );
        }
    }

    // 2. Main path segments
    let mut prev_edge_idx = INVALID_EDGE;
    for i in 0..path_nodes.len() - 1 {
        let u = path_nodes[i];
        let v = path_nodes[i + 1];
        if u >= g.node_count {
            continue;
        }
        let node_u = g.node(u);
        let node_v = g.get_node(v);

        let (s, e_ptr) = g.edge_range(u);

        let mut best_e_idx = INVALID_EDGE;
        for k in s..e_ptr {
            if g.edge_targets(k, u, v) {
                best_e_idx = k;
                break;
            }
        }
        if best_e_idx == INVALID_EDGE {
            continue;
        }

        let e = g.edge(u, best_e_idx);
        let e_name = g.edge_name_offset(best_e_idx).unwrap_or(NO_NAME);
        let mut d = e.dist_mm;
        if d == 0 {
            d = accurate_dist_mm(node_u.lat_e7, node_u.lon_e7, node_v.lat_e7, node_v.lon_e7);
        }

        // Lane guidance is derived at the junction where this edge begins
        // (node u), excluding the node we arrived from. Set it just before the
        // edge's first segment; add_segment consumes and clears it so only the
        // maneuver segment picks up lanes. `pending_approach` is the edge the
        // driver is on reaching u (the previous main-path edge), which carries
        // the real OSM turn:lanes for the maneuver at u.
        let prev_node = if i > 0 { path_nodes[i - 1] } else { INVALID_NODE };
        b.pending_junction = u;
        b.pending_prev = prev_node;
        b.pending_approach = prev_edge_idx;
        prev_edge_idx = best_e_idx;

        if let Some((count, is_reversed)) = g
            .get_edge_coordinates_from(u, best_e_idx, &mut coords)
            .filter(|&(c, _)| c >= 2)
        {
            let pts: Vec<LatLon> =
                (0..count).map(|p| get_pt_at(&coords, count, is_reversed, p)).collect();
            let elevs = edge_point_elevations(
                g,
                &pts,
                f64::from(g.node_elevation(u)),
                f64::from(g.node_elevation(v)),
            );
            for p in 0..count - 1 {
                let p1 = pts[p as usize];
                let p2 = pts[p as usize + 1];
                let seg_dist = fast_dist_mm(g, p1.lat_e7, p1.lon_e7, p2.lat_e7, p2.lon_e7);
                b.add_segment(
                    p1.lat_e7 as f64 * 1e-7, p1.lon_e7 as f64 * 1e-7,
                    p2.lat_e7 as f64 * 1e-7, p2.lon_e7 as f64 * 1e-7,
                    e_name, e.type_, e.speed_limit, seg_dist, best_e_idx,
                    elevs[p as usize], elevs[p as usize + 1],
                );
            }
        } else {
            b.add_segment(
                node_u.lat_e7 as f64 * 1e-7, node_u.lon_e7 as f64 * 1e-7,
                node_v.lat_e7 as f64 * 1e-7, node_v.lon_e7 as f64 * 1e-7,
                e_name, e.type_, e.speed_limit, d, best_e_idx,
                f64::from(g.node_elevation(u)), f64::from(g.node_elevation(v)),
            );
        }
    }

    // 3. End stub: path_nodes.back() -> proj_e
    {
        let nk = *path_nodes.last().unwrap();
        let nodek = g.get_node(nk);
        let j = ctx.end.edge_idx;
        let geom = if j != INVALID_EDGE {
            g.get_edge_coordinates_from(ctx.end.node_a, j, &mut coords)
        } else {
            None
        };

        if let Some((count, is_reversed)) = geom.filter(|&(c, _)| c >= 2) {
            let num_pts = count;
            let seg_idx = ctx.end.segment_idx;
            let pts: Vec<LatLon> =
                (0..count).map(|p| get_pt_at(&coords, count, is_reversed, p)).collect();
            let elevs = edge_point_elevations(
                g,
                &pts,
                f64::from(g.node_elevation(ctx.end.node_a)),
                f64::from(g.node_elevation(ctx.end.node_b)),
            );
            let elev_proj = proj_elevation(
                g, ctx.end.node_a, ctx.end.node_b, ctx.end.dist_a_mm, ctx.end.dist_b_mm,
            );

            if nk == ctx.end.node_a {
                for p in 0..seg_idx {
                    let p_from = pts[p as usize];
                    let p_to = pts[p as usize + 1];
                    let d_seg = fast_dist_mm(g, p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    b.add_segment(
                        p_from.lat_e7 as f64 * 1e-7, p_from.lon_e7 as f64 * 1e-7,
                        p_to.lat_e7 as f64 * 1e-7, p_to.lon_e7 as f64 * 1e-7,
                        ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, d_seg, j,
                        elevs[p as usize], elevs[p as usize + 1],
                    );
                }
                let p_last = pts[seg_idx as usize];
                let d2 = fast_dist_mm(g, p_last.lat_e7, p_last.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon);
                b.add_segment(
                    p_last.lat_e7 as f64 * 1e-7, p_last.lon_e7 as f64 * 1e-7,
                    ctx.end.proj_lat as f64 * 1e-7, ctx.end.proj_lon as f64 * 1e-7,
                    ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, d2, j,
                    elevs[seg_idx as usize], elev_proj,
                );
            } else {
                let mut p = num_pts as i32 - 1;
                while p > seg_idx as i32 + 1 {
                    let p_from = pts[p as usize];
                    let p_to = pts[(p - 1) as usize];
                    let d_seg = fast_dist_mm(g, p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    b.add_segment(
                        p_from.lat_e7 as f64 * 1e-7, p_from.lon_e7 as f64 * 1e-7,
                        p_to.lat_e7 as f64 * 1e-7, p_to.lon_e7 as f64 * 1e-7,
                        ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, d_seg, j,
                        elevs[p as usize], elevs[(p - 1) as usize],
                    );
                    p -= 1;
                }
                let p_last = pts[seg_idx as usize + 1];
                let d2 = fast_dist_mm(g, p_last.lat_e7, p_last.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon);
                b.add_segment(
                    p_last.lat_e7 as f64 * 1e-7, p_last.lon_e7 as f64 * 1e-7,
                    ctx.end.proj_lat as f64 * 1e-7, ctx.end.proj_lon as f64 * 1e-7,
                    ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, d2, j,
                    elevs[seg_idx as usize + 1], elev_proj,
                );
            }
        } else {
            let dist = if nk == ctx.end.node_a {
                ctx.end.dist_a_mm
            } else {
                ctx.end.dist_b_mm
            };
            let elev_proj = proj_elevation(
                g, ctx.end.node_a, ctx.end.node_b, ctx.end.dist_a_mm, ctx.end.dist_b_mm,
            );
            b.add_segment(
                nodek.lat_e7 as f64 * 1e-7, nodek.lon_e7 as f64 * 1e-7,
                ctx.end.proj_lat as f64 * 1e-7, ctx.end.proj_lon as f64 * 1e-7,
                ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, dist, INVALID_EDGE,
                f64::from(g.node_elevation(nk)), elev_proj,
            );
        }
    }

    b.steps
}
