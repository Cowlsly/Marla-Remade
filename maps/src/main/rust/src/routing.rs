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
    pub start_time: u32,
}

/// One coalesced navigation step (pre-localization). Marshaled to Kotlin
/// `OfflineRouter.RawStep` in `lib.rs`.
pub struct StepData {
    pub name_off: u32,
    pub dist_mm: u64,
    pub time_10ms: u64,
    pub coords: Vec<f64>, // flat [lon, lat, lon, lat, ...]
    pub maneuver: i32,
    pub speed_ratio: f64,
    pub is_transit: bool,
    pub feed_off: u32,
    pub code_off: u32,
    pub end_code_off: u32,
    pub stop_count: i32,
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
    let s = jnode.edge_ptr;
    let e_ptr = g.node(junction + 1).edge_ptr; // sentinel valid
    let jlat = jnode.lat_e7;
    let jlon = jnode.lon_e7;

    let mut coords = [LatLon { lat_e7: 0, lon_e7: 0 }; 256];
    // (signed angle diff for sorting, turn direction code)
    let mut opts: Vec<(f64, i32)> = Vec::new();

    for k in s..e_ptr {
        let edge = g.edge(k);
        if edge.type_ & TRANSIT_FLAG != 0 {
            continue;
        }
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
        let (nlat, nlon) = match g.get_edge_coordinates(k, &mut coords) {
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
        let e_ptr = g.node(u_global + 1).edge_ptr; // sentinel valid
        for j in node_u.edge_ptr..e_ptr {
            let e = g.edge(j);
            if e.type_ & TRANSIT_FLAG != 0 {
                continue;
            }
            if !is_mode_allowed(e.type_, mode) {
                continue;
            }
            if e.target >= g.node_count {
                continue;
            }

            if let Some((count, is_reversed)) = g.get_edge_coordinates(j, &mut coords) {
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
                            best.name_offset = e.name_offset;
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
                best.name_offset = e.name_offset;
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
    start_time: u32,
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
            let entry = scratch.get_entry(node, 0);
            entry.g_fwd = t_actual;
            entry.g_bwd = t_actual;
            entry.last_type = start.type_;
        }
        let n_data = g.get_node(node);
        let h = heuristic_time_10ms(g, n_data.lat_e7, n_data.lon_e7, end.proj_lat, end.proj_lon, mode);
        heap.push(t_actual.wrapping_add(h), node << 1);
    };
    push(start.node_a, start.dist_a_mm, scratch, heap);
    push(start.node_b, start.dist_b_mm, scratch, heap);

    Some(RoutingContext {
        start,
        end,
        target_node: 0xFFFF_FFFF,
        iterations: 0,
        start_time,
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
    while !heap.empty() && ctx.iterations < 1_000_000 {
        ctx.iterations += 1;
        let u_idx = heap.pop();
        let u = u_idx >> 1;
        let u_state = (u_idx & 1) as i32;

        if (u == ctx.end.node_a || u == ctx.end.node_b)
            && (mode != PUBLIC_TRANSIT || u_state == 1)
        {
            ctx.target_node = u_idx;
            break;
        }

        let (u_cost, u_actual_time, u_last_type, u_last_name_off) = {
            let e = scratch.get_entry(u, u_state);
            (e.g_fwd, e.g_bwd, e.last_type, e.last_name_off)
        };
        if u >= g.node_count {
            continue;
        }
        let n_u = g.node(u);

        if mode == DRIVING {
            ensure_traffic(n_u.lat_e7, n_u.lon_e7);
        }
        let s = n_u.edge_ptr;
        let e_ptr = g.node(u + 1).edge_ptr; // sentinel valid

        for i in s..e_ptr {
            let edge = g.edge(i);
            if !is_mode_allowed(edge.type_, mode) {
                continue;
            }

            let v_state: i32 = if mode == PUBLIC_TRANSIT && (edge.type_ & TRANSIT_FLAG) != 0 {
                1
            } else {
                0
            };
            let travel_time: u32 = if v_state == 1 {
                let is_boarding = (u_last_type & TRANSIT_FLAG) == 0;
                let is_transfer = !is_boarding && (edge.name_offset != u_last_name_off);

                let ta_u = g.get_node_transit_attr(u);
                // Only board/transfer at recognized transit stops.
                if (is_boarding || is_transfer) && ta_u.stop_code_off == 0xFFFF_FFFF {
                    continue;
                }

                let (tt, _wait, _move) = get_transit_edge_time_10ms(
                    g,
                    &edge,
                    u_actual_time,
                    ctx.start_time,
                    ta_u.feed_name_off,
                    is_boarding || is_transfer,
                );
                if tt == 0xFFFF_FFFF {
                    continue;
                }
                tt
            } else {
                // Only alight from transit at recognized transit stops.
                if mode == PUBLIC_TRANSIT && (u_last_type & TRANSIT_FLAG) != 0 {
                    let ta_u = g.get_node_transit_attr(u);
                    if ta_u.stop_code_off == 0xFFFF_FFFF {
                        continue;
                    }
                }
                get_edge_time_10ms(g, traffic, i, edge.dist_mm, edge.type_, edge.speed_limit, mode)
            };

            let v = edge.target;
            let new_g = u_cost.wrapping_add(travel_time);
            let update = {
                let entry_v = scratch.get_entry(v, v_state);
                if new_g < entry_v.g_fwd {
                    entry_v.g_fwd = new_g;
                    entry_v.g_bwd = new_g;
                    entry_v.p_fwd = u_idx;
                    entry_v.last_type = edge.type_;
                    entry_v.last_name_off = edge.name_offset;
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
                heap.push(new_g.wrapping_add(h), (v << 1) | v_state as u32);
            }
        }
    }
}

/// Accumulates coalesced steps while walking the reconstructed path.
struct StepBuilder<'a> {
    g: &'a Graph,
    traffic: &'a TrafficSpeeds,
    mode: i32,
    start_time_abs: u32,
    steps: Vec<StepData>,
    last_bearing: f64,
    current_elapsed_10ms: u32,
    last_was_transit: bool,
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
        feed_off: u32,
        code_off: u32,
        target_code_off: u32,
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

        let time_10ms: u32;
        let mut is_transit = false;
        if self.mode == PUBLIC_TRANSIT && (type_ & TRANSIT_FLAG) != 0 {
            is_transit = true;
            if edge_idx != INVALID_EDGE {
                let is_boarding = !self.last_was_transit;
                let is_transfer =
                    self.last_was_transit && (name_off != self.steps.last().unwrap().name_off);
                let edge = self.g.edge(edge_idx);
                let (tt, wait, transit_move) = get_transit_edge_time_10ms(
                    self.g,
                    &edge,
                    self.current_elapsed_10ms,
                    self.start_time_abs,
                    feed_off,
                    is_boarding || is_transfer,
                );
                let (wait, transit_move) = if tt == 0xFFFF_FFFF {
                    (0, 0)
                } else {
                    (wait, transit_move)
                };

                if is_boarding || is_transfer {
                    let penalty: u32 = 6000;
                    self.steps.push(StepData {
                        name_off,
                        dist_mm: 0,
                        time_10ms: (wait + penalty) as u64,
                        coords: vec![lon1, lat1],
                        maneuver: 21,
                        speed_ratio: 1.0,
                        is_transit: true,
                        feed_off,
                        code_off,
                        end_code_off: 0xFFFF_FFFF,
                        stop_count: 0,
                        lanes: Vec::new(),
                    });
                    self.current_elapsed_10ms += wait + penalty;
                    time_10ms = transit_move;
                } else {
                    time_10ms = transit_move;
                }
            } else {
                time_10ms = 0;
            }
        } else {
            time_10ms =
                get_edge_time_10ms(self.g, self.traffic, edge_idx, dist_mm, type_, limit, self.mode);
        }
        self.current_elapsed_10ms += time_10ms;
        self.last_was_transit = (type_ & TRANSIT_FLAG) != 0;
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

        let mut maneuver = if self.steps.is_empty() {
            0
        } else {
            get_maneuver(self.last_bearing, bearing)
        };
        if is_transit && !self.steps.is_empty() && self.steps.last().unwrap().maneuver != 21 {
            maneuver = 23; // RIDE
        }

        let push_new = {
            match self.steps.last() {
                None => true,
                Some(back) => {
                    name_off != back.name_off
                        || ratio_cat(ratio) != ratio_cat(back.speed_ratio)
                        || is_transit != back.is_transit
                        || (maneuver != 9 && maneuver != 0 && maneuver != 23)
                        || back.maneuver == 21
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
                maneuver,
                speed_ratio: ratio,
                is_transit,
                feed_off,
                code_off,
                end_code_off: 0xFFFF_FFFF,
                stop_count: 0,
                lanes,
            });
        }

        let back = self.steps.last_mut().unwrap();
        back.dist_mm += dist_mm as u64;
        back.time_10ms += time_10ms as u64;
        back.coords.push(lon2);
        back.coords.push(lat2);
        if is_transit && back.maneuver != 21 {
            back.end_code_off = target_code_off;
            back.stop_count += 1;
        }
        self.last_bearing = bearing;
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
    let mut path_indices: Vec<u32> = Vec::new();
    let mut curr = ctx.target_node;
    let mut safety = 0u32;
    while curr != 0xFFFF_FFFF && safety < 1_000_000 {
        path_indices.push(curr);
        curr = scratch.get_entry(curr >> 1, (curr & 1) as i32).p_fwd;
        safety += 1;
    }
    path_indices.reverse();
    if path_indices.is_empty() {
        return Vec::new();
    }

    let path_nodes: Vec<u32> = path_indices.iter().map(|&idx| idx >> 1).collect();

    let mut b = StepBuilder {
        g,
        traffic,
        mode,
        start_time_abs: ctx.start_time,
        steps: Vec::new(),
        last_bearing: 0.0,
        current_elapsed_10ms: 0,
        last_was_transit: false,
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
            g.get_edge_coordinates(j, &mut coords)
        } else {
            None
        };

        if let Some((count, is_reversed)) = geom.filter(|&(c, _)| c >= 2) {
            let num_pts = count;
            let seg_idx = ctx.start.segment_idx;
            let feed = g.get_node_feed_name_off(n0);
            let code = g.get_node_stop_code_off(n0);

            if n0 == ctx.start.node_a {
                let p_next = get_pt_at(&coords, count, is_reversed, seg_idx);
                let d1 = fast_dist_mm(
                    g, ctx.start.proj_lat, ctx.start.proj_lon, p_next.lat_e7, p_next.lon_e7,
                );
                b.add_segment(
                    ctx.start.proj_lat as f64 * 1e-7,
                    ctx.start.proj_lon as f64 * 1e-7,
                    p_next.lat_e7 as f64 * 1e-7,
                    p_next.lon_e7 as f64 * 1e-7,
                    ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, d1, j, feed, code, code,
                );
                let mut p = seg_idx as i32;
                while p >= 1 {
                    let p_from = get_pt_at(&coords, count, is_reversed, p as u32);
                    let p_to = get_pt_at(&coords, count, is_reversed, (p - 1) as u32);
                    let d_seg = fast_dist_mm(g, p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    b.add_segment(
                        p_from.lat_e7 as f64 * 1e-7, p_from.lon_e7 as f64 * 1e-7,
                        p_to.lat_e7 as f64 * 1e-7, p_to.lon_e7 as f64 * 1e-7,
                        ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, d_seg, j, feed, code, code,
                    );
                    p -= 1;
                }
            } else {
                let p_next = get_pt_at(&coords, count, is_reversed, seg_idx + 1);
                let d1 = fast_dist_mm(
                    g, ctx.start.proj_lat, ctx.start.proj_lon, p_next.lat_e7, p_next.lon_e7,
                );
                b.add_segment(
                    ctx.start.proj_lat as f64 * 1e-7,
                    ctx.start.proj_lon as f64 * 1e-7,
                    p_next.lat_e7 as f64 * 1e-7,
                    p_next.lon_e7 as f64 * 1e-7,
                    ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, d1, j, feed, code, code,
                );
                for p in seg_idx + 1..num_pts - 1 {
                    let p_from = get_pt_at(&coords, count, is_reversed, p);
                    let p_to = get_pt_at(&coords, count, is_reversed, p + 1);
                    let d_seg = fast_dist_mm(g, p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    b.add_segment(
                        p_from.lat_e7 as f64 * 1e-7, p_from.lon_e7 as f64 * 1e-7,
                        p_to.lat_e7 as f64 * 1e-7, p_to.lon_e7 as f64 * 1e-7,
                        ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, d_seg, j, feed, code, code,
                    );
                }
            }
        } else {
            let dist = if n0 == ctx.start.node_a {
                ctx.start.dist_a_mm
            } else {
                ctx.start.dist_b_mm
            };
            b.add_segment(
                ctx.start.proj_lat as f64 * 1e-7,
                ctx.start.proj_lon as f64 * 1e-7,
                node0.lat_e7 as f64 * 1e-7,
                node0.lon_e7 as f64 * 1e-7,
                ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, dist, INVALID_EDGE,
                g.get_node_feed_name_off(n0), g.get_node_stop_code_off(n0), g.get_node_stop_code_off(n0),
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

        let s = node_u.edge_ptr;
        let e_ptr = g.node(u + 1).edge_ptr;

        let mut best_e_idx = INVALID_EDGE;
        for k in s..e_ptr {
            if g.edge(k).target == v {
                best_e_idx = k;
                break;
            }
        }
        if best_e_idx == INVALID_EDGE {
            continue;
        }

        let e = g.edge(best_e_idx);
        let mut d = e.dist_mm;
        if d == 0 || (e.type_ & TRANSIT_FLAG) != 0 {
            d = accurate_dist_mm(node_u.lat_e7, node_u.lon_e7, node_v.lat_e7, node_v.lon_e7);
        }

        let feed = g.get_node_feed_name_off(u);
        let code_u = g.get_node_stop_code_off(u);
        let code_v = g.get_node_stop_code_off(v);

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

        if let Some((count, is_reversed)) =
            g.get_edge_coordinates(best_e_idx, &mut coords).filter(|&(c, _)| c >= 2)
        {
            for p in 0..count - 1 {
                let p1 = get_pt_at(&coords, count, is_reversed, p);
                let p2 = get_pt_at(&coords, count, is_reversed, p + 1);
                let seg_dist = fast_dist_mm(g, p1.lat_e7, p1.lon_e7, p2.lat_e7, p2.lon_e7);
                b.add_segment(
                    p1.lat_e7 as f64 * 1e-7, p1.lon_e7 as f64 * 1e-7,
                    p2.lat_e7 as f64 * 1e-7, p2.lon_e7 as f64 * 1e-7,
                    e.name_offset, e.type_, e.speed_limit, seg_dist, best_e_idx, feed, code_u, code_v,
                );
            }
        } else {
            b.add_segment(
                node_u.lat_e7 as f64 * 1e-7, node_u.lon_e7 as f64 * 1e-7,
                node_v.lat_e7 as f64 * 1e-7, node_v.lon_e7 as f64 * 1e-7,
                e.name_offset, e.type_, e.speed_limit, d, best_e_idx, feed, code_u, code_v,
            );
        }
    }

    // 3. End stub: path_nodes.back() -> proj_e
    {
        let nk = *path_nodes.last().unwrap();
        let nodek = g.get_node(nk);
        let j = ctx.end.edge_idx;
        let geom = if j != INVALID_EDGE {
            g.get_edge_coordinates(j, &mut coords)
        } else {
            None
        };

        if let Some((count, is_reversed)) = geom.filter(|&(c, _)| c >= 2) {
            let num_pts = count;
            let seg_idx = ctx.end.segment_idx;
            let feed = g.get_node_feed_name_off(nk);
            let code = g.get_node_stop_code_off(nk);

            if nk == ctx.end.node_a {
                for p in 0..seg_idx {
                    let p_from = get_pt_at(&coords, count, is_reversed, p);
                    let p_to = get_pt_at(&coords, count, is_reversed, p + 1);
                    let d_seg = fast_dist_mm(g, p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    b.add_segment(
                        p_from.lat_e7 as f64 * 1e-7, p_from.lon_e7 as f64 * 1e-7,
                        p_to.lat_e7 as f64 * 1e-7, p_to.lon_e7 as f64 * 1e-7,
                        ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, d_seg, j, feed, code, 0xFFFF_FFFF,
                    );
                }
                let p_last = get_pt_at(&coords, count, is_reversed, seg_idx);
                let d2 = fast_dist_mm(g, p_last.lat_e7, p_last.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon);
                b.add_segment(
                    p_last.lat_e7 as f64 * 1e-7, p_last.lon_e7 as f64 * 1e-7,
                    ctx.end.proj_lat as f64 * 1e-7, ctx.end.proj_lon as f64 * 1e-7,
                    ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, d2, j, feed, code, 0xFFFF_FFFF,
                );
            } else {
                let mut p = num_pts as i32 - 1;
                while p > seg_idx as i32 + 1 {
                    let p_from = get_pt_at(&coords, count, is_reversed, p as u32);
                    let p_to = get_pt_at(&coords, count, is_reversed, (p - 1) as u32);
                    let d_seg = fast_dist_mm(g, p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    b.add_segment(
                        p_from.lat_e7 as f64 * 1e-7, p_from.lon_e7 as f64 * 1e-7,
                        p_to.lat_e7 as f64 * 1e-7, p_to.lon_e7 as f64 * 1e-7,
                        ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, d_seg, j, feed, code, 0xFFFF_FFFF,
                    );
                    p -= 1;
                }
                let p_last = get_pt_at(&coords, count, is_reversed, seg_idx + 1);
                let d2 = fast_dist_mm(g, p_last.lat_e7, p_last.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon);
                b.add_segment(
                    p_last.lat_e7 as f64 * 1e-7, p_last.lon_e7 as f64 * 1e-7,
                    ctx.end.proj_lat as f64 * 1e-7, ctx.end.proj_lon as f64 * 1e-7,
                    ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, d2, j, feed, code, 0xFFFF_FFFF,
                );
            }
        } else {
            let dist = if nk == ctx.end.node_a {
                ctx.end.dist_a_mm
            } else {
                ctx.end.dist_b_mm
            };
            b.add_segment(
                nodek.lat_e7 as f64 * 1e-7, nodek.lon_e7 as f64 * 1e-7,
                ctx.end.proj_lat as f64 * 1e-7, ctx.end.proj_lon as f64 * 1e-7,
                ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, dist, INVALID_EDGE,
                g.get_node_feed_name_off(nk), g.get_node_stop_code_off(nk), 0xFFFF_FFFF,
            );
        }
    }

    b.steps
}
