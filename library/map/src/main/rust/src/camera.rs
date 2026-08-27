//! The camera: a snapshot from Kotlin to a per-tile clip-space matrix.
//!
//! Web Mercator with a 256-logical-px tile grid, matching `:library:map`'s
//! `Mercator.kt` exactly — the Kotlin side owns the public `Projection` in `Dp`, this
//! side has to agree with it or every overlay drifts from the basemap under it.
//!
//! Only the camera crosses JNI, once per frame. Everything per-tile is derived here.

/// Logical pixels across one tile. Web Mercator's convention, and `Mercator.kt`'s.
pub const TILE_SIZE: f64 = 256.0;

/// The camera as Kotlin measured it.
#[derive(Clone, Copy, Debug)]
pub struct Camera {
    pub center_lon: f64,
    pub center_lat: f64,
    pub zoom: f64,
    /// Viewport in logical pixels (Dp), as the Compose host measured it.
    pub width_dp: f32,
    pub height_dp: f32,
    /// Device pixels per Dp. The only place a physical pixel enters.
    pub density: f32,
}

/// A point in Web Mercator world pixels at some zoom.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct WorldPx {
    pub x: f64,
    pub y: f64,
}

/// Total map width and height in logical px at `zoom`.
pub fn world_size(zoom: f64) -> f64 {
    TILE_SIZE * 2f64.powf(zoom)
}

/// Project lon/lat degrees to world px at `zoom`.
pub fn project(lon: f64, lat: f64, zoom: f64) -> WorldPx {
    let size = world_size(zoom);
    // Mercator y is undefined at the poles; this is the standard web-mapping clamp
    // and the same constant `Mercator.kt` uses.
    let lat = lat.clamp(-85.051_128_78, 85.051_128_78);
    let x = (lon + 180.0) / 360.0 * size;
    let sin_lat = (lat * std::f64::consts::PI / 180.0).sin();
    let y = (0.5 - ((1.0 + sin_lat) / (1.0 - sin_lat)).ln() / (4.0 * std::f64::consts::PI)) * size;
    WorldPx { x, y }
}

/// Inverse of [`project`].
pub fn unproject(x: f64, y: f64, zoom: f64) -> (f64, f64) {
    let size = world_size(zoom);
    let lon = x / size * 360.0 - 180.0;
    let n = std::f64::consts::PI - 2.0 * std::f64::consts::PI * y / size;
    let lat = n.sinh().atan() * 180.0 / std::f64::consts::PI;
    (lon, lat)
}

impl Camera {
    /// The world-px position of the viewport's top-left corner.
    pub fn viewport_origin(&self) -> WorldPx {
        let center = project(self.center_lon, self.center_lat, self.zoom);
        WorldPx {
            x: center.x - self.width_dp as f64 / 2.0,
            y: center.y - self.height_dp as f64 / 2.0,
        }
    }

    /// The screen size of one tile at zoom level `z`, in logical px.
    pub fn tile_span_dp(&self, z: u8) -> f64 {
        TILE_SIZE * 2f64.powf(self.zoom - z as f64)
    }

    /// The screen size of one tile at zoom `z` in **device** px, which is what turns a
    /// pixel line width into tile-local units in the vertex shader.
    pub fn tile_span_px(&self, z: u8) -> f32 {
        (self.tile_span_dp(z) * self.density as f64) as f32
    }

    /// Column-major 4x4 taking tile-local 0..1 to Vulkan clip space.
    ///
    /// ```text
    /// clip.x =  2 * (tile_origin.x + u * span - viewport_origin.x) / width  - 1
    /// clip.y = -1 + 2 * (tile_origin.y + v * span - viewport_origin.y) / height
    /// ```
    ///
    /// Note the sign of y. Vulkan's clip space has **y down** — unlike OpenGL, and
    /// unlike WebGPU — and Mercator y also grows downward, so the two agree and no
    /// flip is needed. Adding one anyway mirrors the whole map vertically, which is
    /// easy to miss on a symmetric city and obvious on a coastline.
    pub fn tile_to_clip(&self, z: u8, x: u32, y: u32) -> [f32; 16] {
        let span = self.tile_span_dp(z);
        let origin = self.viewport_origin();
        let tile_x = x as f64 * span;
        let tile_y = y as f64 * span;

        let sx = 2.0 * span / self.width_dp as f64;
        let sy = 2.0 * span / self.height_dp as f64;
        let tx = 2.0 * (tile_x - origin.x) / self.width_dp as f64 - 1.0;
        let ty = 2.0 * (tile_y - origin.y) / self.height_dp as f64 - 1.0;

        [
            sx as f32, 0.0, 0.0, 0.0, //
            0.0, sy as f32, 0.0, 0.0, //
            0.0, 0.0, 1.0, 0.0, //
            tx as f32, ty as f32, 0.0, 1.0,
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn camera(zoom: f64) -> Camera {
        Camera {
            center_lon: 0.0,
            center_lat: 0.0,
            zoom,
            width_dp: 512.0,
            height_dp: 512.0,
            density: 1.0,
        }
    }

    /// Apply a column-major 4x4 to a 2D point, as the vertex shader does.
    fn transform(m: &[f32; 16], u: f32, v: f32) -> (f32, f32) {
        (m[0] * u + m[4] * v + m[12], m[1] * u + m[5] * v + m[13])
    }

    #[test]
    fn the_world_is_256_dp_per_tile() {
        assert_eq!(world_size(0.0), 256.0);
        assert_eq!(world_size(1.0), 512.0);
        assert_eq!(world_size(14.0), 256.0 * 16384.0);
    }

    #[test]
    fn project_and_unproject_round_trip() {
        for zoom in [0.0, 5.0, 11.0, 14.0, 18.0] {
            for &(lon, lat) in
                &[(0.0, 0.0), (-122.4194, 37.7749), (151.2093, -33.8688), (2.3522, 48.8566)]
            {
                let p = project(lon, lat, zoom);
                let (back_lon, back_lat) = unproject(p.x, p.y, zoom);
                assert!((back_lon - lon).abs() < 1e-9, "lon at z{zoom}: {back_lon} vs {lon}");
                assert!((back_lat - lat).abs() < 1e-9, "lat at z{zoom}: {back_lat} vs {lat}");
            }
        }
    }

    #[test]
    fn null_island_is_the_centre_of_the_world() {
        let p = project(0.0, 0.0, 0.0);
        assert!((p.x - 128.0).abs() < 1e-9);
        assert!((p.y - 128.0).abs() < 1e-9);
    }

    #[test]
    fn the_tile_containing_the_camera_covers_the_viewport_centre() {
        // At z1 centred on null island, the four tiles meet exactly at the centre of a
        // 512 Dp viewport, so tile 0/0's bottom-right corner lands at clip (0, 0).
        let camera = camera(1.0);
        let m = camera.tile_to_clip(1, 0, 0);
        let (x, y) = transform(&m, 1.0, 1.0);
        assert!(x.abs() < 1e-5, "x {x}");
        assert!(y.abs() < 1e-5, "y {y}");
    }

    #[test]
    fn clip_space_y_grows_downward_as_vulkan_and_mercator_both_do() {
        // The tile's top edge must land at a *smaller* clip y than its bottom edge. A
        // flip here mirrors the map.
        let camera = camera(1.0);
        let m = camera.tile_to_clip(1, 0, 0);
        let (_, top) = transform(&m, 0.0, 0.0);
        let (_, bottom) = transform(&m, 0.0, 1.0);
        assert!(top < bottom, "top {top} must be above bottom {bottom} in clip space");
    }

    #[test]
    fn a_full_screen_tile_fills_clip_space() {
        // At z0 with a 256 Dp viewport the single tile is exactly the screen, so its
        // corners are the corners of clip space.
        let camera = Camera { width_dp: 256.0, height_dp: 256.0, ..camera(0.0) };
        let m = camera.tile_to_clip(0, 0, 0);
        let (x0, y0) = transform(&m, 0.0, 0.0);
        let (x1, y1) = transform(&m, 1.0, 1.0);
        assert!((x0 - -1.0).abs() < 1e-5, "left {x0}");
        assert!((y0 - -1.0).abs() < 1e-5, "top {y0}");
        assert!((x1 - 1.0).abs() < 1e-5, "right {x1}");
        assert!((y1 - 1.0).abs() < 1e-5, "bottom {y1}");
    }

    #[test]
    fn adjacent_tiles_share_an_edge_with_no_gap() {
        // A seam here is a visible hairline between every pair of tiles.
        let camera = Camera { center_lon: -122.4194, center_lat: 37.7749, ..camera(12.0) };
        let left = camera.tile_to_clip(12, 654, 1583);
        let right = camera.tile_to_clip(12, 655, 1583);
        let (left_edge, _) = transform(&left, 1.0, 0.0);
        let (right_edge, _) = transform(&right, 0.0, 0.0);
        assert!((left_edge - right_edge).abs() < 1e-5, "{left_edge} vs {right_edge}");
    }

    #[test]
    fn overzoom_scales_a_tile_up_rather_than_leaving_a_hole() {
        // The archive stops at z16 and users keep zooming, so a z16 tile drawn at z19
        // must be 8x its normal size.
        let camera = camera(19.0);
        assert!((camera.tile_span_dp(16) - 256.0 * 8.0).abs() < 1e-9);
    }

    #[test]
    fn density_only_affects_the_pixel_span() {
        // tile_span_dp is a logical measurement and must not move with density;
        // tile_span_px is the only thing that scales, because it feeds a pixel width.
        let one = Camera { density: 1.0, ..camera(14.0) };
        let three = Camera { density: 3.0, ..camera(14.0) };
        assert_eq!(one.tile_span_dp(14), three.tile_span_dp(14));
        assert!((three.tile_span_px(14) / one.tile_span_px(14) - 3.0).abs() < 1e-5);
    }

    #[test]
    fn latitude_is_clamped_at_the_mercator_limit_rather_than_returning_infinity() {
        // Mercator y goes to infinity at the poles. The clamp puts +-90 exactly on the
        // top and bottom edges of the world, up to floating-point slack — so the
        // tolerance is one world pixel rather than zero.
        let size = world_size(4.0);
        for lat in [90.0, -90.0, 89.9, -89.9] {
            let p = project(0.0, lat, 4.0);
            assert!(p.y.is_finite(), "y at lat {lat} is {}", p.y);
            assert!(p.y >= -1.0 && p.y <= size + 1.0, "y at lat {lat} is {}, off the map", p.y);
        }
    }
}
