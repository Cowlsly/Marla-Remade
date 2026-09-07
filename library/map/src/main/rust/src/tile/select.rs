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

    /// Unpack a [`key`](Self::key). The residency map is keyed by integer, so this is what
    /// lets a tile's position be recovered without storing the id alongside it.
    pub fn from_key(key: u64) -> TileId {
        TileId {
            z: (key >> 44) as u8,
            x: ((key >> 22) & 0x3F_FFFF) as u32,
            y: (key & 0x3F_FFFF) as u32,
        }
    }

    /// Whether this tile lies within `depth` levels *beneath* `other` — that is, `other` is
    /// an ancestor of it, or the same tile.
    pub fn descends_from(&self, other: &TileId, depth: u8) -> bool {
        if self.z < other.z || self.z - other.z > depth {
            return false;
        }
        let shift = self.z - other.z;
        self.x >> shift == other.x && self.y >> shift == other.y
    }
}

/// How many levels of descendant to *keep* when they are already resident.
///
/// The mirror of [`ANCESTOR_DEPTH`], and the reason zooming out no longer blanks the map. An
/// ancestor stands in while a finer tile loads; nothing stood in while a *coarser* one loaded,
/// because the tiles on screen were the new tile's descendants and were evicted the moment the
/// camera moved. The map then drew nothing for as long as the fetch took.
///
/// Two rather than four: a descendant set grows as 4^depth where an ancestor set is linear, and
/// two levels already covers a 4x zoom-out — more than one pinch produces. This is a bound on GPU
/// memory, not a lookahead.
pub const DESCENDANT_DEPTH: u8 = 2;

/// Whether a resident tile is worth keeping as a stand-in for a visible one that has not arrived.
///
/// Deliberately a predicate over what is *already resident*, rather than something
/// [`resident_set`] could enumerate: naming every descendant means 4^[`DESCENDANT_DEPTH`] keys per
/// visible tile, most of which were never fetched, and the keep list is asserted to stay
/// proportional to the viewport.
pub fn stands_in_for_visible(key: u64, visible: &[TileId], depth: u8) -> bool {
    let tile = TileId::from_key(key);
    visible.iter().any(|v| tile.descends_from(v, depth))
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
/// Nor is it the whole keep list. Descendants are the other half of the fallback — see
/// [`stands_in_for_visible`] — and cannot be named here without enumerating tiles that were
/// never fetched.
///
/// Ancestors are returned **before** the tiles they stand in for. The renderer sorts by zoom
/// before drawing, so this is for the residency cap rather than for draw order: coarser tiles
/// are the cheaper, more widely useful stand-ins and should be the last thing evicted.
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
        // z2 centred on null island: the world is 1024 Dp across (256 grid),
        // so a 256 Dp viewport straddles the four tiles around the centre.
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
        // (512px tiles cover 4x the area of 256px ones, so the same viewport
        // needs roughly a quarter the tiles.)
        let c = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
        let tiles = visible(&c, 0, 16);
        assert!(tiles.len() <= bound(&c), "{} exceeds the bound {}", tiles.len(), bound(&c));
        assert!(!tiles.is_empty(), "a phone viewport at z14 covers tiles");
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

    #[test]
    fn a_key_round_trips() {
        // `from_key` is what lets the renderer recover a tile's position from its residency map
        // key alone, so the two must agree at the edges of the bit packing as well as the middle.
        for z in 0..=22u8 {
            let n = 1u32 << z;
            for &(x, y) in &[(0, 0), (n - 1, n - 1), (n / 2, n / 3)] {
                let tile = TileId { z, x, y };
                assert_eq!(TileId::from_key(tile.key()), tile);
            }
        }
    }

    #[test]
    fn a_tile_descends_from_its_ancestors_and_from_itself() {
        let tile = TileId { z: 12, x: 2048, y: 1362 };
        assert!(tile.descends_from(&tile, 2), "a tile stands in for itself");
        assert!(tile.descends_from(&TileId { z: 11, x: 1024, y: 681 }, 2));
        assert!(tile.descends_from(&TileId { z: 10, x: 512, y: 340 }, 2));
    }

    #[test]
    fn descent_stops_at_the_depth_and_never_goes_upwards() {
        let tile = TileId { z: 12, x: 2048, y: 1362 };
        // Three levels up is a real ancestor, but past the depth we are willing to hold.
        assert!(!tile.descends_from(&TileId { z: 9, x: 256, y: 170 }, 2));
        // An ancestor does not descend from its own descendant.
        assert!(!TileId { z: 10, x: 512, y: 340 }.descends_from(&tile, 2));
        // A neighbour at the same zoom shares no ground.
        assert!(!tile.descends_from(&TileId { z: 11, x: 1025, y: 681 }, 2));
    }

    #[test]
    fn a_resident_descendant_stands_in_for_a_visible_tile() {
        // The zoom-out case: the camera has pulled back to z10 and those tiles are still in
        // flight, but the z12 tiles from a moment ago are resident and cover the same ground.
        let visible = vec![TileId { z: 10, x: 512, y: 340 }];
        assert!(stands_in_for_visible(TileId { z: 12, x: 2048, y: 1362 }.key(), &visible, 2));
        assert!(stands_in_for_visible(TileId { z: 11, x: 1024, y: 681 }.key(), &visible, 2));
        // Deeper than we hold, and elsewhere in the world.
        assert!(!stands_in_for_visible(TileId { z: 13, x: 4096, y: 2724 }.key(), &visible, 2));
        assert!(!stands_in_for_visible(TileId { z: 12, x: 8, y: 8 }.key(), &visible, 2));
    }

    #[test]
    fn the_keep_list_stays_free_of_descendants() {
        // Descendants are recognised against what is resident, never enumerated into the keep
        // list: naming them would be 4^DESCENDANT_DEPTH keys per visible tile, almost all of
        // which were never fetched. This is the invariant that lets the bound above hold.
        let c = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
        let exact = visible(&c, 0, 16);
        let deepest = exact.iter().map(|t| t.z).max().expect("a tile");
        for tile in resident_set(&c, 0, 16) {
            assert!(tile.z <= deepest, "{tile:?} is deeper than any visible tile");
        }
    }
}
