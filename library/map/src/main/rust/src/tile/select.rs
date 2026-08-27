//! Which tiles cover the viewport.
//!
//! A pure function of the camera, so it cannot accidentally depend on GPU state. Two
//! behaviours are load-bearing:
//!
//! * **Overzoom.** Past the archive's `max_zoom` the same tiles are kept and drawn
//!   larger, because the tile span is `256 * 2^(zoom - z)`. The archive stops at z16 and
//!   users zoom past it; without this the map goes blank.
//! * **No horizontal wrap.** The renderer draws one world, not a repeating one. `maps`
//!   gets antimeridian wrapping from MapLibre; the five consumer apps show a city, and
//!   duplicating every tile's geometry to render the seam twice would cost more than it
//!   is worth.

use crate::camera::{Camera, TILE_SIZE};

/// A tile to have resident, and where it sits.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Hash)]
pub struct TileId {
    pub z: u8,
    pub x: u32,
    pub y: u32,
}

impl TileId {
    /// A key for the residency map, packing z/x/y into one integer.
    ///
    /// z up to 22 needs 5 bits and x/y up to 2^22 need 22 each, so 49 bits are used and
    /// no two tiles collide.
    pub fn key(&self) -> u64 {
        ((self.z as u64) << 44) | ((self.x as u64) << 22) | self.y as u64
    }
}

/// The tiles covering `camera`'s viewport, clamped to the archive's zoom range.
pub fn visible(camera: &Camera, min_zoom: u8, max_zoom: u8) -> Vec<TileId> {
    if camera.width_dp <= 0.0 || camera.height_dp <= 0.0 {
        return Vec::new();
    }
    let z = (camera.zoom.floor().max(0.0) as u32).clamp(min_zoom as u32, max_zoom as u32) as u8;
    let n = 1i64 << z;
    let span = camera.tile_span_dp(z);
    let origin = camera.viewport_origin();

    let min_tx = (origin.x / span).floor() as i64;
    let max_tx = ((origin.x + camera.width_dp as f64) / span).floor() as i64;
    let min_ty = (origin.y / span).floor() as i64;
    let max_ty = ((origin.y + camera.height_dp as f64) / span).floor() as i64;

    let mut out = Vec::new();
    for ty in min_ty..=max_ty {
        if ty < 0 || ty >= n {
            continue;
        }
        for tx in min_tx..=max_tx {
            if tx < 0 || tx >= n {
                continue;
            }
            out.push(TileId { z, x: tx as u32, y: ty as u32 });
        }
    }
    out
}

/// How many levels of ancestor to *keep* when they are already resident.
///
/// Four covers a 16x zoom jump, which is more than a pinch produces in one gesture.
pub const ANCESTOR_DEPTH: u8 = 4;

/// The tiles worth **keeping resident**: the visible ones, plus any ancestor of a visible
/// tile.
///
/// This is deliberately *not* the fetch list. An ancestor is a fallback for a tile that has
/// not arrived yet, so it is only useful if we **already have it** — fetching one costs a
/// round trip to draw a blurrier version of a tile that is being fetched anyway. Doing that
/// tripled the network for a screenful (24 fetches instead of 12) and, because ancestors sort
/// first, spent all that latency *before* requesting the tiles the user is actually looking
/// at. MapLibre renders the parent it happens to have cached; it does not go and fetch one.
///
/// Ancestors are returned **before** the tiles they stand in for, which is the order the
/// renderer draws them in, so a child covers its parent rather than the reverse.
pub fn resident_set(camera: &Camera, min_zoom: u8, max_zoom: u8) -> Vec<TileId> {
    let exact = visible(camera, min_zoom, max_zoom);
    if exact.is_empty() {
        return exact;
    }
    let mut out: Vec<TileId> = Vec::with_capacity(exact.len() * 2);
    let mut seen = std::collections::HashSet::new();

    // Coarsest first, so the draw order is ancestors under descendants.
    for level in (1..=ANCESTOR_DEPTH).rev() {
        for tile in &exact {
            if tile.z < min_zoom + level {
                continue;
            }
            let ancestor =
                TileId { z: tile.z - level, x: tile.x >> level, y: tile.y >> level };
            if seen.insert(ancestor.key()) {
                out.push(ancestor);
            }
        }
    }
    for tile in exact {
        if seen.insert(tile.key()) {
            out.push(tile);
        }
    }
    out
}

/// A rough bound on how many tiles a viewport can want, for capacity hints.
pub fn bound(camera: &Camera) -> usize {
    let across = camera.width_dp as f64 / TILE_SIZE + 2.0;
    let down = camera.height_dp as f64 / TILE_SIZE + 2.0;
    (across * down).ceil() as usize
}

#[cfg(test)]
mod tests {
    use super::*;

    fn camera(lon: f64, lat: f64, zoom: f64, w: f32, h: f32) -> Camera {
        Camera {
            center_lon: lon,
            center_lat: lat,
            zoom,
            width_dp: w,
            height_dp: h,
            density: 1.0,
        }
    }

    #[test]
    fn the_viewport_is_covered_at_an_exact_zoom() {
        // z2 centred on null island: the world is 1024 Dp across, so a 256 Dp viewport
        // straddles the four tiles around the centre.
        let tiles = visible(&camera(0.0, 0.0, 2.0, 256.0, 256.0), 0, 16);
        assert_eq!(tiles.len(), 4, "the centre of the world is a four-tile corner");
        assert!(tiles.iter().all(|t| t.z == 2));
        let mut coords: Vec<(u32, u32)> = tiles.iter().map(|t| (t.x, t.y)).collect();
        coords.sort_unstable();
        assert_eq!(coords, vec![(1, 1), (1, 2), (2, 1), (2, 2)]);
    }

    #[test]
    fn a_fractional_zoom_uses_the_floor() {
        let tiles = visible(&camera(0.0, 0.0, 2.5, 512.0, 512.0), 0, 16);
        assert!(tiles.iter().all(|t| t.z == 2), "z2.5 draws z2 tiles, larger");
    }

    #[test]
    fn past_the_archives_max_zoom_the_same_tiles_are_drawn_larger() {
        // The archive stops at z16 and users keep zooming. Without overzoom the map goes
        // blank at z17.
        let tiles = visible(&camera(-122.4194, 37.7749, 19.0, 411.0, 891.0), 0, 16);
        assert!(!tiles.is_empty(), "z19 must still be covered");
        assert!(tiles.iter().all(|t| t.z == 16), "clamped to the archive's max zoom");
        // A z16 tile at z19 is 8x its normal size, so a phone viewport needs very few.
        assert!(tiles.len() <= 4, "only a handful of overzoomed tiles: {}", tiles.len());
    }

    #[test]
    fn below_the_archives_min_zoom_the_lowest_available_tiles_are_used() {
        let tiles = visible(&camera(0.0, 0.0, 1.0, 400.0, 400.0), 5, 16);
        assert!(tiles.iter().all(|t| t.z == 5));
    }

    #[test]
    fn tiles_off_the_edge_of_the_world_are_not_requested() {
        // At the antimeridian the viewport runs past x = 2^z, and there is no wrap.
        let tiles = visible(&camera(179.99, 0.0, 3.0, 800.0, 400.0), 0, 16);
        let n = 1u32 << 3;
        assert!(!tiles.is_empty());
        for t in &tiles {
            assert!(t.x < n, "x {} outside the grid", t.x);
            assert!(t.y < n, "y {} outside the grid", t.y);
        }
    }

    #[test]
    fn the_poles_clamp_in_y() {
        let tiles = visible(&camera(0.0, 84.9, 4.0, 400.0, 900.0), 0, 16);
        let n = 1u32 << 4;
        assert!(tiles.iter().all(|t| t.y < n));
        assert!(tiles.iter().any(|t| t.y == 0), "the top row is included");
    }

    #[test]
    fn an_unmeasured_viewport_asks_for_nothing() {
        assert!(visible(&camera(0.0, 0.0, 5.0, 0.0, 0.0), 0, 16).is_empty());
    }

    #[test]
    fn the_tile_count_stays_within_the_area_bound() {
        // A sanity bound on GPU residency: a phone at z14 should be tens of tiles, not
        // hundreds. Each resident tile costs vertex and index buffers per layer.
        let c = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
        let tiles = visible(&c, 0, 16);
        assert!(tiles.len() <= bound(&c), "{} exceeds the bound {}", tiles.len(), bound(&c));
        assert!(tiles.len() >= 6, "a phone viewport at z14 covers at least six tiles");
    }

    #[test]
    fn ancestors_are_included_and_ordered_under_their_descendants() {
        // Without ancestors the map goes blank on every zoom step: z changes, every resident
        // tile is dropped, and nothing draws until the new level arrives.
        let c = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
        let exact = visible(&c, 0, 16);
        let all = resident_set(&c, 0, 16);
        assert!(all.len() > exact.len(), "ancestors must be added");

        // Every exact tile is still present.
        for tile in &exact {
            assert!(all.contains(tile), "{tile:?} was dropped");
        }
        // Every ancestor really is one: the child's coordinates shifted right.
        for tile in &all {
            if exact.contains(tile) {
                continue;
            }
            let covers = exact.iter().any(|child| {
                child.z > tile.z && {
                    let shift = child.z - tile.z;
                    child.x >> shift == tile.x && child.y >> shift == tile.y
                }
            });
            assert!(covers, "{tile:?} is not an ancestor of any visible tile");
        }
        // Coarsest first: the renderer draws in this order, so a parent must precede its
        // child or a stale parent lands on top of the sharp child.
        let zooms: Vec<u8> = all.iter().map(|t| t.z).collect();
        let mut sorted = zooms.clone();
        sorted.sort_unstable();
        assert_eq!(zooms, sorted, "ancestors must come before descendants");
    }

    #[test]
    fn ancestors_never_go_below_the_archives_minimum_zoom() {
        let c = camera(0.0, 0.0, 6.0, 411.0, 891.0);
        for tile in resident_set(&c, 5, 16) {
            assert!(tile.z >= 5, "{tile:?} is below the archive's min zoom");
        }
    }

    #[test]
    fn ancestors_are_distinct_and_bounded() {
        let c = camera(-122.4194, 37.7749, 16.0, 411.0, 891.0);
        let all = resident_set(&c, 0, 16);
        let mut keys: Vec<u64> = all.iter().map(|t| t.key()).collect();
        let count = keys.len();
        keys.sort_unstable();
        keys.dedup();
        assert_eq!(count, keys.len(), "a tile is listed twice");
        // Residency is GPU memory, so this must stay proportional to the viewport rather
        // than growing with depth.
        assert!(all.len() < bound(&c) * 3, "{} tiles is too many to keep resident", all.len());
    }

    #[test]
    fn an_unmeasured_viewport_asks_for_nothing_even_with_ancestors() {
        // The viewport is null until Compose measures it. Asking for ancestors of nothing
        // must stay nothing rather than falling back to the whole world.
        let unmeasured = camera(0.0, 0.0, 5.0, 0.0, 0.0);
        assert!(visible(&unmeasured, 0, 16).is_empty());
        assert!(resident_set(&unmeasured, 0, 16).is_empty());
    }

    #[test]
    fn every_tile_is_distinct() {
        let tiles = visible(&camera(2.3522, 48.8566, 12.0, 411.0, 891.0), 0, 16);
        let mut keys: Vec<u64> = tiles.iter().map(|t| t.key()).collect();
        let count = keys.len();
        keys.sort_unstable();
        keys.dedup();
        assert_eq!(count, keys.len(), "no tile is requested twice");
    }

    #[test]
    fn tile_keys_do_not_collide_across_the_archives_zoom_range() {
        let mut seen = std::collections::HashSet::new();
        for z in 0..=16u8 {
            let n = 1u32 << z;
            let mut coords = vec![0, n / 2, n - 1];
            coords.dedup();
            for &x in &coords {
                for &y in &coords {
                    assert!(seen.insert(TileId { z, x, y }.key()), "z{z}/{x}/{y} collided");
                }
            }
        }
    }
}
