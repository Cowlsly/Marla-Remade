//! Which tiles cover the viewport.
//!
//! A pure function of the camera, so it cannot accidentally depend on GPU state. Three
//! behaviours are load-bearing:
//!
//! * **Overzoom.** Past the archive's `max_zoom` the same tiles are kept and drawn
//!   larger, because the tile span is `256 * 2^(zoom - z)`. The archive stops at z16 and
//!   users zoom past it; without this the map goes blank.
//! * **No horizontal wrap.** The renderer draws one world, not a repeating one. `maps`
//!   gets antimeridian wrapping from MapLibre; the five consumer apps show a city, and
//!   duplicating every tile's geometry to render the seam twice would cost more than it
//!   is worth.
//! * **Rotation widens the footprint.** Coverage comes from
//!   [`Camera::viewport_bounds`], which is the bounding box of the *rotated* viewport,
//!   not `origin .. origin + size`. A heading-up camera at 45 degrees covers `sqrt(2)`
//!   times the viewport across each axis, and a selection derived from the unrotated box
//!   leaves the four corners of the display blank — which is exactly where the road the
//!   driver is about to turn onto is.

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

    /// The tile `levels` levels *above* this one (its coarser ancestor), or `None` past the
    /// root. `levels == 0` is the tile itself. WS-D uses this to ask whether a coarser
    /// stand-in is resident under a fading finer tile.
    pub fn ancestor(&self, levels: u8) -> Option<TileId> {
        if self.z < levels {
            return None;
        }
        Some(TileId { z: self.z - levels, x: self.x >> levels, y: self.y >> levels })
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

/// The world-px axis-aligned box the viewport covers on the ground, accounting for **both**
/// bearing and tilt.
///
/// At pitch 0 this is exactly [`Camera::viewport_bounds`] — the bounding box of the rotated
/// viewport. Under tilt the top of the screen recedes toward the horizon, so the ground the
/// viewport actually covers is a trapezoid reaching far past that box; a selection derived from
/// the untilted box leaves the top of the display blank exactly where the distance the driver is
/// looking toward is. So the four screen corners are unprojected through the tilt-aware ray/plane
/// ([`Camera::screen_to_world`]) and the box is grown to hold them. The [`PITCH_MAX_DEG`] cap keeps
/// every corner below the horizon, so each resolves and the trapezoid stays finite.
fn coverage(camera: &Camera) -> (crate::camera::WorldPx, crate::camera::WorldPx) {
    let (mut min, mut max) = camera.viewport_bounds();
    if camera.pitch_deg == 0.0 {
        return (min, max);
    }
    let w = camera.width_dp as f64;
    let h = camera.height_dp as f64;
    // The top edge recedes furthest, so its two corners are the far edge of the trapezoid; the
    // bottom corners are the near edge. Their AABB bounds the whole trapezoid because a screen line
    // maps to a straight line on the ground plane.
    for &(sx, sy) in &[(0.0, 0.0), (w, 0.0), (w, h), (0.0, h)] {
        if let Some(p) = camera.screen_to_world(sx, sy) {
            min.x = min.x.min(p.x);
            min.y = min.y.min(p.y);
            max.x = max.x.max(p.x);
            max.y = max.y.max(p.y);
        }
    }
    (min, max)
}

/// The tiles covering `camera`'s viewport, clamped to the archive's zoom range.
///
/// Coverage is the bounding box of the viewport **as the camera actually orients it** — bearing
/// *and* tilt — so a rotation pulls in the extra ring of tiles the rotated corners reach and a tilt
/// pulls in the trapezoid of ground the receding top of the screen covers. At bearing zero and
/// pitch zero the box is the viewport and this is what it always was.
pub fn visible(camera: &Camera, min_zoom: u8, max_zoom: u8) -> Vec<TileId> {
    if camera.width_dp <= 0.0 || camera.height_dp <= 0.0 {
        return Vec::new();
    }
    let z = (camera.zoom.floor().max(0.0) as u32).clamp(min_zoom as u32, max_zoom as u32) as u8;
    let n = 1i64 << z;
    let span = camera.tile_span_dp(z);
    let (min, max) = coverage(camera);

    let min_tx = (min.x / span).floor() as i64;
    let max_tx = (max.x / span).floor() as i64;
    let min_ty = (min.y / span).floor() as i64;
    let max_ty = (max.y / span).floor() as i64;

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

/// How long a finer LOD tile takes to cross-fade in over its coarse ancestor, in seconds (WS-D).
///
/// Short enough to read as an anti-pop rather than an animation; measured against the shared
/// clock (`Camera::time_seconds` / `Push.misc.w`) from the tile's `uploaded_at` stamp.
pub const LOD_FADE_SECONDS: f32 = 0.3;

/// The per-tile opacity of a tile `now - uploaded_at` seconds after its GPU buffers landed: 0 at
/// upload, ramping linearly to 1 over `duration` (WS-D LOD cross-fade). Both times share the
/// `Camera::time_seconds` epoch. A non-positive `duration` disables the fade (opaque at once).
pub fn lod_fade_alpha(now: f32, uploaded_at: f32, duration: f32) -> f32 {
    if duration <= 0.0 {
        return 1.0;
    }
    ((now - uploaded_at) / duration).clamp(0.0, 1.0)
}

/// Whether a tile stamped `uploaded_at` is still inside its cross-fade, and so would draw
/// differently on the next frame even from an identical camera.
///
/// This is what stops the on-demand frame loop idling mid-fade and freezing a tile at
/// half opacity. It is deliberately **not** `lod_fade_alpha(..) < 1.0`: the fade is gated on
/// a resident ancestor (see [`tile_lod_alpha`]) and this is not, so it answers "is this tile
/// young enough that the fade *could* be running" rather than "is it running". Erring that
/// way costs a few frames on a tile that was never going to fade; erring the other way stops
/// the clock on one that was.
///
/// The elapsed time is taken **modulo [`CLOCK_WRAP_SECONDS`]**, because the shared clock wraps
/// hourly (see [`Camera::time_seconds`](crate::camera::Camera::time_seconds)). A plain
/// subtraction is not merely imprecise across a wrap, it is wrong in the worst direction: a
/// tile stamped at 3599.5 against a clock that has just wrapped to 0.0 gives -3599.5, which is
/// below any sane `duration`, so the window would read as open and pin the frame loop awake
/// for the remaining hour — the exact thing on-demand rendering exists to prevent.
pub fn fade_in_progress(now: f32, uploaded_at: f32, duration: f32) -> bool {
    if duration <= 0.0 {
        return false;
    }
    (now - uploaded_at).rem_euclid(crate::camera::CLOCK_WRAP_SECONDS) < duration
}

/// Whether a coarser ancestor of `key` (up to [`ANCESTOR_DEPTH`] levels up) is currently
/// resident, and so is drawn underneath as an opaque stand-in.
///
/// This gates the fade: a finer tile may fade in only when there is an ancestor to show through
/// the gap. A tile with no resident ancestor draws fully opaque immediately, so a freshly
/// fetched area never fades up from the background.
pub fn has_resident_ancestor(key: u64, resident: &std::collections::HashSet<u64>) -> bool {
    let tile = TileId::from_key(key);
    (1..=ANCESTOR_DEPTH)
        .any(|levels| tile.ancestor(levels).is_some_and(|a| resident.contains(&a.key())))
}

/// The per-tile LOD cross-fade opacity the renderer writes into `Push.morph.x` for tile `key`.
///
/// Ramps 0→1 over `duration` from `uploaded_at` when a coarse ancestor is resident to stand in
/// under the gap; otherwise fully opaque, so a tile with nothing beneath it never fades up from
/// the background. `resident` is the set of currently resident tile keys.
pub fn tile_lod_alpha(
    key: u64,
    uploaded_at: f32,
    now: f32,
    duration: f32,
    resident: &std::collections::HashSet<u64>,
) -> f32 {
    if has_resident_ancestor(key, resident) {
        lod_fade_alpha(now, uploaded_at, duration)
    } else {
        1.0
    }
}

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
///
/// Measured off the same tilt-and-rotation coverage box [`visible`] selects from, so it stays a
/// bound rather than becoming a lie the moment the camera turns or tilts.
pub fn bound(camera: &Camera) -> usize {
    let (min, max) = coverage(camera);
    let across = (max.x - min.x) / TILE_SIZE + 2.0;
    let down = (max.y - min.y) / TILE_SIZE + 2.0;
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
            bearing_deg: 0.0,
            pitch_deg: 0.0,
            time_seconds: 0.0,
        }
    }

    /// A freshly-resident tile ramps its opacity 0→1 across `LOD_FADE_SECONDS`, so a finer LOD
    /// fades in rather than popping. Clamped at both ends.
    #[test]
    fn a_fresh_tile_ramps_zero_to_one_over_the_duration() {
        let uploaded_at = 10.0;
        let d = LOD_FADE_SECONDS;
        assert_eq!(lod_fade_alpha(uploaded_at, uploaded_at, d), 0.0, "0 at upload");
        assert!(
            (lod_fade_alpha(uploaded_at + d * 0.5, uploaded_at, d) - 0.5).abs() < 1e-4,
            "halfway through the fade",
        );
        assert_eq!(lod_fade_alpha(uploaded_at + d, uploaded_at, d), 1.0, "opaque at the end");
        // Before upload (clock races the stamp) and long after both clamp.
        assert_eq!(lod_fade_alpha(uploaded_at - 1.0, uploaded_at, d), 0.0);
        assert_eq!(lod_fade_alpha(uploaded_at + 10.0, uploaded_at, d), 1.0);
    }

    /// A tile resident long enough for the fade to complete is fully opaque.
    #[test]
    fn a_long_resident_tile_is_fully_opaque() {
        let now = 5000.0;
        let uploaded_at = now - 100.0;
        assert_eq!(lod_fade_alpha(now, uploaded_at, LOD_FADE_SECONDS), 1.0);
    }

    /// The coarse ancestor stays fully opaque underneath a fading finer child: the child ramps
    /// (an ancestor is resident to cover the gap) while the ancestor, having nothing resident
    /// above it, draws at full opacity.
    #[test]
    fn coarse_ancestor_is_opaque_under_a_fading_child() {
        use std::collections::HashSet;
        let coarse = TileId { z: 10, x: 5, y: 5 };
        let fine = TileId { z: 11, x: 10, y: 10 };
        assert_eq!(fine.ancestor(1), Some(coarse), "fine descends from coarse");

        let resident: HashSet<u64> = [coarse.key(), fine.key()].into_iter().collect();
        let now = 20.0;
        let uploaded_at = now; // both just landed this frame

        // The child fades because its ancestor is resident underneath.
        let child_alpha = tile_lod_alpha(fine.key(), uploaded_at, now, LOD_FADE_SECONDS, &resident);
        assert_eq!(child_alpha, 0.0, "the child starts transparent and ramps in");

        // The ancestor has no coarser tile resident above it, so it never fades — it is the
        // opaque stand-in the child fades over.
        let ancestor_alpha =
            tile_lod_alpha(coarse.key(), uploaded_at, now, LOD_FADE_SECONDS, &resident);
        assert_eq!(ancestor_alpha, 1.0, "the coarse ancestor stays fully opaque");

        // Partway through, the child is partly there and the ancestor is still solid.
        let mid = now + LOD_FADE_SECONDS * 0.5;
        assert!(
            (tile_lod_alpha(fine.key(), uploaded_at, mid, LOD_FADE_SECONDS, &resident) - 0.5)
                .abs()
                < 1e-4,
        );
        assert_eq!(
            tile_lod_alpha(coarse.key(), uploaded_at, mid, LOD_FADE_SECONDS, &resident),
            1.0,
        );
    }

    /// With no coarse ancestor resident there is nothing to show through a gap, so a freshly
    /// fetched tile draws fully opaque immediately instead of fading up from the background.
    #[test]
    fn a_lone_fresh_tile_does_not_fade() {
        use std::collections::HashSet;
        let lone = TileId { z: 11, x: 10, y: 10 };
        let resident: HashSet<u64> = [lone.key()].into_iter().collect();
        let now = 3000.0;
        assert_eq!(
            tile_lod_alpha(lone.key(), now, now, LOD_FADE_SECONDS, &resident),
            1.0,
            "no ancestor underneath — draw opaque, never fade over the background",
        );
    }

    /// The fade window is open for exactly as long as the fade runs, so the on-demand frame
    /// loop keeps drawing across it and stops once the tile has settled. A tile frozen at
    /// half opacity is what this prevents.
    #[test]
    fn the_fade_window_is_open_for_exactly_the_fade() {
        let uploaded_at = 100.0;
        let d = LOD_FADE_SECONDS;
        assert!(fade_in_progress(uploaded_at, uploaded_at, d), "open at upload");
        assert!(fade_in_progress(uploaded_at + d * 0.5, uploaded_at, d), "open halfway");
        // The instant the ramp reaches 1.0 there is nothing left to animate.
        assert!(!fade_in_progress(uploaded_at + d, uploaded_at, d), "shut when opaque");
        assert!(!fade_in_progress(uploaded_at + d * 10.0, uploaded_at, d), "shut long after");
    }

    /// The window is open wherever the opacity ramp is still moving. Checked against
    /// [`lod_fade_alpha`] rather than restated, because the two drifting apart is exactly how
    /// the loop would idle mid-fade.
    #[test]
    fn the_fade_window_covers_every_frame_the_opacity_is_still_ramping() {
        let uploaded_at = 42.0;
        let d = LOD_FADE_SECONDS;
        for step in 0..40 {
            let now = uploaded_at + d * step as f32 / 20.0;
            if lod_fade_alpha(now, uploaded_at, d) < 1.0 {
                assert!(
                    fade_in_progress(now, uploaded_at, d),
                    "opacity is {} at {now} but the window says settled",
                    lod_fade_alpha(now, uploaded_at, d),
                );
            }
        }
    }

    /// A clock that reads before the stamp is the hourly wrap of the shared clock, and the
    /// elapsed time has to be taken modulo the period. Getting this wrong does not cost a few
    /// frames — it holds the frame loop open for the rest of the hour, which is the whole
    /// defect on-demand rendering exists to fix.
    #[test]
    fn a_wrapped_clock_measures_the_real_elapsed_time() {
        let d = LOD_FADE_SECONDS;
        // Uploaded 0.5s ago in real time, across a wrap: the fade has finished.
        assert!(
            !fade_in_progress(0.0, 3599.5, d),
            "0.5s elapsed across the wrap is past a 0.3s fade — the loop must be allowed to idle",
        );
        // Uploaded 0.1s ago in real time, across a wrap: still fading.
        assert!(fade_in_progress(0.0, 3599.9, d), "0.1s elapsed across the wrap is mid-fade");
        // The far side of the wrap must not read as an hour of pending work.
        assert!(
            !fade_in_progress(1.0, 2000.0, d),
            "a stamp far behind the clock must not pin the loop awake",
        );
    }

    /// A disabled fade animates nothing, so it must never hold the frame loop open — that
    /// would be a permanent 60fps with no visible change.
    #[test]
    fn a_disabled_fade_never_holds_the_loop_open() {
        assert!(!fade_in_progress(10.0, 10.0, 0.0));
        assert!(!fade_in_progress(10.0, 10.0, -1.0));
    }

    /// The wrap period the fade window measures against has to be the one the bridge actually
    /// reduces the clock by, or every elapsed time across a wrap is wrong by the difference.
    #[test]
    fn the_clock_period_matches_the_bridges_reduction() {
        assert_eq!(
            crate::camera::CLOCK_WRAP_SECONDS,
            crate::camera::CLOCK_WRAP_NANOS as f32 / 1_000_000_000.0,
        );
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
    fn a_rotated_viewport_pulls_in_the_tiles_its_corners_reach() {
        // The heading-up failure this guards: a rotated screen's corners stick out past
        // the unrotated box, and a selection that ignores that leaves them blank. z12 with
        // a phone-shaped viewport at 45 degrees must ask for strictly more than north-up
        // does, and every north-up tile must still be in the set.
        let north_up = camera(-122.4194, 37.7749, 12.0, 411.0, 891.0);
        let turned = Camera { bearing_deg: 45.0, ..north_up };
        let straight = visible(&north_up, 0, 16);
        let rotated = visible(&turned, 0, 16);
        assert!(
            rotated.len() > straight.len(),
            "a 45-degree camera covers more ground: {} vs {}",
            rotated.len(),
            straight.len(),
        );
        for tile in &straight {
            assert!(rotated.contains(tile), "{tile:?} was dropped by the rotation");
        }
    }

    #[test]
    fn every_corner_of_a_rotated_viewport_lands_in_a_selected_tile() {
        // The property that actually matters, checked against the same rotation the clip
        // matrix applies rather than against a tile count: whatever the bearing, the
        // ground under each corner of the screen belongs to a tile that was asked for.
        for bearing in [0.0, 30.0, 45.0, 90.0, 137.0, 250.0, -80.0] {
            let c = Camera { bearing_deg: bearing, ..camera(2.3522, 48.8566, 13.0, 411.0, 891.0) };
            let tiles = visible(&c, 0, 16);
            let span = c.tile_span_dp(13);
            let centre = crate::camera::project(c.center_lon, c.center_lat, c.zoom);
            let radians = bearing.to_radians();
            let (half_w, half_h) = (c.width_dp as f64 / 2.0, c.height_dp as f64 / 2.0);
            for (sx, sy) in [
                (-half_w, -half_h),
                (half_w, -half_h),
                (half_w, half_h),
                (-half_w, half_h),
            ] {
                let wx = centre.x + radians.cos() * sx - radians.sin() * sy;
                let wy = centre.y + radians.sin() * sx + radians.cos() * sy;
                let tx = (wx / span).floor() as i64;
                let ty = (wy / span).floor() as i64;
                assert!(
                    tiles.iter().any(|t| t.x as i64 == tx && t.y as i64 == ty),
                    "bearing {bearing}: nothing covers the corner in tile 13/{tx}/{ty}",
                );
            }
        }
    }

    #[test]
    fn the_rotated_tile_count_stays_within_the_area_bound() {
        // The bound is what sizes the residency hint, so it has to grow with the rotation
        // rather than being quietly exceeded by every turned frame.
        let c = Camera { bearing_deg: 45.0, ..camera(-122.4194, 37.7749, 14.0, 411.0, 891.0) };
        let tiles = visible(&c, 0, 16);
        assert!(tiles.len() <= bound(&c), "{} exceeds the bound {}", tiles.len(), bound(&c));
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
    fn a_tilted_viewport_pulls_in_the_trapezoid_toward_the_horizon() {
        // Under tilt the top of the screen recedes toward the horizon, so the covered ground is a
        // trapezoid larger than the flat viewport box. A pitched camera must ask for strictly more
        // tiles than the same level camera, and every level-camera tile must still be present.
        let level = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
        let tilted = Camera { pitch_deg: 55.0, ..level };
        let flat = visible(&level, 0, 16);
        let pitched = visible(&tilted, 0, 16);
        assert!(
            pitched.len() > flat.len(),
            "a tilted camera covers the receding trapezoid: {} vs {}",
            pitched.len(),
            flat.len(),
        );
        for tile in &flat {
            assert!(pitched.contains(tile), "{tile:?} was dropped by the tilt");
        }
        assert!(pitched.len() <= bound(&tilted), "the bound must grow with the tilt too");
    }

    #[test]
    fn every_on_screen_ground_point_of_a_tilted_view_lands_in_a_selected_tile() {
        // The property that matters: whatever the tilt (and bearing), the ground under each screen
        // corner belongs to a tile that was asked for. Uses the same tilt-aware unproject the
        // renderer draws with, so selection and drawing agree.
        let c = Camera {
            pitch_deg: 50.0,
            bearing_deg: 30.0,
            ..camera(2.3522, 48.8566, 13.0, 411.0, 891.0)
        };
        let tiles = visible(&c, 0, 16);
        let span = c.tile_span_dp(13);
        for &(sx, sy) in &[(0.0, 0.0), (411.0, 0.0), (411.0, 891.0), (0.0, 891.0), (205.0, 20.0)] {
            let ground = c.screen_to_world(sx, sy).expect("below the horizon under the cap");
            let tx = (ground.x / span).floor() as i64;
            let ty = (ground.y / span).floor() as i64;
            assert!(
                tiles.iter().any(|t| t.x as i64 == tx && t.y as i64 == ty),
                "nothing covers the on-screen point at ({sx},{sy}) -> tile 13/{tx}/{ty}",
            );
        }
    }

    #[test]
    fn a_zero_pitch_selection_is_unchanged() {
        // The regression guard: adding tilt coverage must not perturb the flat/phone path. At pitch
        // 0 the coverage box is exactly the viewport bounds, so the selection is what it always was.
        let c = camera(-122.4194, 37.7749, 14.0, 411.0, 891.0);
        let turned = Camera { bearing_deg: 37.0, ..c };
        assert_eq!(visible(&c, 0, 16), visible(&Camera { pitch_deg: 0.0, ..c }, 0, 16));
        assert_eq!(visible(&turned, 0, 16), visible(&Camera { pitch_deg: 0.0, ..turned }, 0, 16));
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
