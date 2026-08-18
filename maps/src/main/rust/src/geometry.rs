//! Distance/time/bearing math and road-permission rules.
//!
//! Direct port of the `--- GEOMETRY ---`, `--- ROAD PERMISSIONS ---` and the
//! transit-scheduling helpers from `native-lib.cpp`. Integer arithmetic and
//! casts are reproduced exactly to keep routing output bit-identical.

use std::collections::HashMap;

use crate::graph::{
    Edge, Graph, DEG_TO_RAD, DRIVING, INVALID_EDGE, LIVING_STREET, MAX_DRIVING_KMH, MOTORWAY,
    PUBLIC_TRANSIT, STEPS, TRANSIT_FLAG,
};

/// Live traffic snapshot: global edge id -> speed (km/h). 0 means "unknown".
pub type TrafficSpeeds = HashMap<u64, u8>;

#[inline]
pub fn is_mode_allowed(road_type: u8, mode: i32) -> bool {
    let ty = road_type & 0x7F;
    if mode == DRIVING {
        return (MOTORWAY..=LIVING_STREET).contains(&ty);
    }
    if mode == PUBLIC_TRANSIT {
        return (road_type & TRANSIT_FLAG != 0) || (MOTORWAY..=STEPS).contains(&ty);
    }
    (MOTORWAY..=STEPS).contains(&ty)
}

/// Fast equirectangular distance in millimetres (octagonal `max/min` approx),
/// using the precomputed cos-scaled longitude table.
#[inline(always)]
pub fn fast_dist_mm(g: &Graph, lat1_e7: i32, lon1_e7: i32, lat2_e7: i32, lon2_e7: i32) -> u32 {
    let dlat = (lat1_e7 as i64 - lat2_e7 as i64).abs();
    let dlon = (lon1_e7 as i64 - lon2_e7 as i64).abs();

    // 1 unit (1e-7 deg) latitude is ~11.1139 mm.
    let dy_mm = (dlat * 111_139) / 10_000;

    let mid = (lat1_e7.wrapping_add(lat2_e7)) / 2;
    let scale_idx = ((mid >> 19) + 2048) as u32;
    let scale = g.lon_to_mm_scale[(scale_idx & 4095) as usize] as i64;
    let dx_mm = (dlon * scale) >> 10;

    let (max_v, min_v): (u64, u64) = if dx_mm > dy_mm {
        (dx_mm as u64, dy_mm as u64)
    } else {
        (dy_mm as u64, dx_mm as u64)
    };
    (max_v - (max_v >> 5) + (min_v >> 1) - (min_v >> 3)) as u32
}

/// Haversine distance in millimetres, saturating at u32::MAX.
#[inline]
pub fn accurate_dist_mm(lat1_e7: i32, lon1_e7: i32, lat2_e7: i32, lon2_e7: i32) -> u32 {
    const R: f64 = 6_371_000_800.0;
    let phi1 = (lat1_e7 as f64 * 1e-7) * DEG_TO_RAD;
    let phi2 = (lat2_e7 as f64 * 1e-7) * DEG_TO_RAD;
    let delta_phi = (lat2_e7 - lat1_e7) as f64 * 1e-7 * DEG_TO_RAD;
    let delta_lambda = (lon2_e7 - lon1_e7) as f64 * 1e-7 * DEG_TO_RAD;
    let s_dphi = (delta_phi / 2.0).sin();
    let s_dlamb = (delta_lambda / 2.0).sin();
    let a = s_dphi * s_dphi + phi1.cos() * phi2.cos() * s_dlamb * s_dlamb;
    let dist = R * 2.0 * a.sqrt().atan2((1.0 - a).sqrt());
    if dist >= u32::MAX as f64 {
        return u32::MAX;
    }
    if dist < 0.0 {
        return 0;
    }
    dist as u32
}

/// Time (10 ms units) to traverse a non-transit edge, applying live traffic for
/// driving.
#[inline]
pub fn get_edge_time_10ms(
    g: &Graph,
    traffic: &TrafficSpeeds,
    edge_id: u64,
    dist_mm: u32,
    type_: u8,
    limit: u8,
    mode: i32,
) -> u32 {
    if mode == DRIVING {
        let traffic_speed = if edge_id != INVALID_EDGE {
            *traffic.get(&edge_id).unwrap_or(&0)
        } else {
            0
        };
        let effective_limit = if traffic_speed > 0 { traffic_speed } else { limit };
        // Clamp to the heuristic's max speed so no edge is ever faster than the
        // heuristic assumes — required to keep the A* heuristic consistent for the
        // monotonic radix heap (see MAX_DRIVING_KMH).
        let effective_limit = effective_limit.min(MAX_DRIVING_KMH as u8);
        if effective_limit > 0 {
            let speed_m_s = effective_limit as f64 / 3.6;
            return (dist_mm as f64 / (speed_m_s * 10.0)) as u32;
        }
    }
    let multiplier = g.edge_time_multipliers[(mode & 0x3) as usize][((type_ & 0x7F) & 0xF) as usize];
    ((dist_mm as u64 * multiplier) >> 32) as u32
}

/// Optimistic A* heuristic in 10 ms units.
#[inline]
pub fn heuristic_time_10ms(g: &Graph, lat1: i32, lon1: i32, lat2: i32, lon2: i32, mode: i32) -> u32 {
    let dist_mm = fast_dist_mm(g, lat1, lon1, lat2, lon2);
    let scaled_time = dist_mm as u64 * g.time_scale_fixed[(mode & 0x3) as usize];
    (scaled_time >> 32) as u32
}

/// GTFS transit edge cost with next-departure scheduling. Returns
/// `0xFFFFFFFF` when no voyage is usable. Mirrors `get_transit_edge_time_10ms`.
#[inline]
pub fn get_transit_edge_time_10ms(
    g: &Graph,
    edge: &Edge,
    current_time_from_start: u32,
    start_time_abs: u32,
    feed_off: u32,
    is_boarding_or_transfer: bool,
) -> (u32, u32, u32) {
    // Returns (total_time, wait_out, travel_out).
    if !g.has_transit_voyages() {
        return (0xFFFF_FFFF, 0, 0);
    }

    let voyage_offset = edge.dist_mm as u64;
    let voyage_count = edge.speed_limit as u32;
    if voyage_count == 0 {
        return (0xFFFF_FFFF, 0, 0);
    }

    // Feed not present offline: report just the (unscheduled) voyage-0 duration.
    if feed_off != 0xFFFF_FFFF && (feed_off as usize) < g.road_names_size {
        if let Some(feed_name) = g.road_name(feed_off) {
            if !g.present_feeds.contains(&feed_name) {
                let travel = g.transit_voyage_at(voyage_offset + 1).dep_delta as u32;
                return (travel, 0, travel);
            }
        }
    }

    const BOARDING_PENALTY: u32 = 6000; // 1 minute to platform
    const DAY_10MS: u32 = 24 * 3600 * 100;
    let abs_now = (start_time_abs + current_time_from_start) % DAY_10MS;
    let arrival_at_platform =
        (abs_now + if is_boarding_or_transfer { BOARDING_PENALTY } else { 0 }) % DAY_10MS;

    let mut best_wait: u32 = 0xFFFF_FFFF;
    let mut best_travel: u32 = 0;

    let wait_for = |dep: u32| -> u32 {
        let dep_mod = dep % DAY_10MS;
        if dep_mod >= arrival_at_platform {
            dep_mod - arrival_at_platform
        } else {
            (DAY_10MS - arrival_at_platform) + dep_mod
        }
    };

    // Voyage 0.
    let dep0 = g.transit_dep_u32(voyage_offset);
    let duration0 = g.transit_voyage_at(voyage_offset + 1).dep_delta as u32;
    {
        let wait = wait_for(dep0);
        if wait < best_wait {
            best_wait = wait;
            best_travel = duration0;
        }
    }

    // Voyages 1+.
    let mut dep_prev = dep0;
    for i in 1..voyage_count as u64 {
        let compact = g.transit_voyage_at(voyage_offset + 1 + i);
        let dep = dep_prev.wrapping_add(compact.dep_delta as u32 * 100);
        dep_prev = dep;
        let wait = wait_for(dep);
        if wait < best_wait {
            best_wait = wait;
            best_travel = compact.duration as u32;
        }
    }

    if best_wait == 0xFFFF_FFFF {
        return (0xFFFF_FFFF, 0, 0);
    }

    let total =
        (if is_boarding_or_transfer { BOARDING_PENALTY } else { 0 }) + best_wait + best_travel;
    (total, best_wait, best_travel)
}

pub fn get_bearing(lat1: i32, lon1: i32, lat2: i32, lon2: i32) -> f64 {
    let f1 = (lat1 as f64 / 1e7) * DEG_TO_RAD;
    let f2 = (lat2 as f64 / 1e7) * DEG_TO_RAD;
    let dl = ((lon2 - lon1) as f64 / 1e7) * DEG_TO_RAD;
    let y = dl.sin() * f2.cos();
    let x = f1.cos() * f2.sin() - f1.sin() * f2.cos() * dl.cos();
    y.atan2(x) * (180.0 / std::f64::consts::PI)
}

pub fn get_maneuver(prev_bearing: f64, next_bearing: f64) -> i32 {
    let mut angle_diff = next_bearing - prev_bearing;
    while angle_diff < -180.0 {
        angle_diff += 360.0;
    }
    while angle_diff > 180.0 {
        angle_diff -= 360.0;
    }
    if !(-155.0..=155.0).contains(&angle_diff) {
        return 3;
    }
    if angle_diff < -100.0 {
        return 2;
    }
    if angle_diff < -45.0 {
        return 4;
    }
    if angle_diff < -10.0 {
        return 1;
    }
    if angle_diff < 10.0 {
        return 9;
    }
    if angle_diff < 45.0 {
        return 5;
    }
    if angle_diff < 100.0 {
        return 8;
    }
    6
}

pub struct Projection {
    pub lat_e7: i32,
    pub lon_e7: i32,
    pub dist_mm: u32,
}

/// Project point (px,py) onto segment (x1,y1)-(x2,y2). All coords are e7.
pub fn get_projection(
    g: &Graph,
    px: i32,
    py: i32,
    x1: i32,
    y1: i32,
    x2: i32,
    y2: i32,
) -> Projection {
    let lat_avg = (x1 + x2) as f64 / 2.0 * 1e-7;
    let lon_scale = (lat_avg * DEG_TO_RAD).cos();

    let dx = x2 as f64 - x1 as f64;
    let dy = (y2 as f64 - y1 as f64) * lon_scale;
    let dpx = px as f64 - x1 as f64;
    let dpy = (py as f64 - y1 as f64) * lon_scale;

    let mag_sq = dx * dx + dy * dy;
    let mut t = if mag_sq == 0.0 {
        0.0
    } else {
        (dpx * dx + dpy * dy) / mag_sq
    };
    t = t.clamp(0.0, 1.0);

    let proj_lat = (x1 as f64 + t * (x2 - x1) as f64) as i32;
    let proj_lon = (y1 as f64 + t * (y2 - y1) as f64) as i32;
    Projection {
        lat_e7: proj_lat,
        lon_e7: proj_lon,
        dist_mm: fast_dist_mm(g, px, py, proj_lat, proj_lon),
    }
}
