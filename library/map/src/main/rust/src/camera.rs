//! The camera: a snapshot from Kotlin to a per-tile clip-space matrix.
//!
//! Web Mercator on a 512-logical-px tile grid (world = 512 * 2^zoom), matching
//! `:library:map`'s `Mercator.kt` — the Kotlin side owns the public `Projection`
//! in `Dp`, this side has to agree with it or every overlay drifts from the
//! basemap under it.
//!
//! 512 is also what gives MapLibre parity: the vector archives are authored on
//! that convention, so at the same zoom float a tile covers the same ground and
//! renders at the same size. This used to be a 256 grid with a compensating +1
//! zoom offset applied at the JNI boundary, which produced the right ground scale
//! by a different route but had two costs: tile addressing took the floor of the
//! *offset* zoom, so a screenful fetched four times as many tiles as MapLibre
//! does, and every style ramp — widths, `text_size`, opacity and the `min_zoom`
//! layer gating — was evaluated one level away from the zoom the authored
//! `basemap.json` those values were transcribed from meant by it.
//!
//! Only the camera crosses JNI, once per frame. Everything per-tile is derived here.

/// Logical pixels across one tile: 512, the convention the archives are authored on.
///
/// World scale is 512 * 2^zoom, which is MapLibre's, so tile addressing is the plain
/// floor of the camera zoom and every ramp is evaluated at the zoom the style means.
pub const TILE_SIZE: f64 = 512.0;

/// Largest camera tilt we allow, in degrees.
///
/// Not cosmetic: at this cap the horizon still sits above the top of the screen (the
/// perspective's far edge is a finite ground distance), so [`Camera::screen_to_world`]
/// always meets the ground plane and tile selection never has to cover an infinite
/// trapezoid. `fy = d/half_h = 3` and the horizon enters the screen only past
/// `atan(fy) ≈ 71°`, so 60 leaves a margin. See [`Camera::pitch_deg`].
pub const PITCH_MAX_DEG: f64 = 60.0;

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
    /// Which compass direction points **up** the screen, in degrees clockwise from
    /// north. Zero is north-up, which is every path but heading-up car navigation.
    ///
    /// A rotation, not a tilt: it composes into the clip matrices as a plain 2x2. Tilt is
    /// [`pitch_deg`](Self::pitch_deg), which is the perspective term and composes separately.
    pub bearing_deg: f64,
    /// Camera tilt away from straight-down, in degrees, expected in `0..=`[`PITCH_MAX_DEG`]
    /// (the JNI boundary clamps it; the matrices below assume nothing).
    ///
    /// Zero is the classic top-down orthographic map — every path but the tilt gesture — and
    /// is short-circuited in every matrix builder so the ortho fast-path is byte-for-byte what
    /// it always was. Above zero [`world_quad_to_clip`](Self::world_quad_to_clip) produces a
    /// true perspective (real z, w-divide) and [`screen_quad_to_clip`](Self::screen_quad_to_clip)
    /// billboards its quad upright.
    pub pitch_deg: f64,
    /// Seconds since an arbitrary epoch, forwarded from the host's per-frame `frameTimeNanos`.
    ///
    /// Not part of the projection — it never enters a matrix, so it changes no camera test —
    /// but it rides on the camera because it is the other thing that arrives exactly once per
    /// frame. The renderer forwards it to shaders through the `Push.misc.w` slot; the animated
    /// workstreams (dash phase, LOD morph, vehicles) read it there.
    pub time_seconds: f32,
}

/// A point in Web Mercator world pixels at some zoom.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct WorldPx {
    pub x: f64,
    pub y: f64,
}

/// The tilt-dependent constants of the perspective projection, computed once per matrix.
///
/// Only built on the pitched path; the ortho fast-path never touches it. `d` is the
/// camera-to-centre distance and cancels at pitch 0, so its only job is setting how strong
/// the foreshortening is; `fx`/`fy` are focal terms; `depth_a`/`depth_b` map view distance to
/// Vulkan's `[0, 1]` clip depth (`ndc_z = depth_a - depth_b/w`).
#[derive(Clone, Copy)]
struct Perspective {
    fx: f64,
    fy: f64,
    d: f64,
    depth_a: f64,
    depth_b: f64,
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
    /// `(cos, sin)` of the bearing: the 2x2 that turns a world-px offset from the camera
    /// centre into a screen-px offset.
    ///
    /// Public because the symbol path needs the inverse of it — labels counter-rotate to
    /// stay upright, see [`crate::tess::text::upright`] — and deriving the angle twice is
    /// how the two would eventually disagree.
    ///
    /// Short-circuited at zero so the north-up path — every phone frame — takes the
    /// literal `(1, 0)` rather than `cos(0)`/`sin(0)`, and the matrices below reduce
    /// term by term to the unrotated ones.
    pub fn rotation(&self) -> (f64, f64) {
        if self.bearing_deg == 0.0 {
            return (1.0, 0.0);
        }
        let radians = self.bearing_deg.to_radians();
        (radians.cos(), radians.sin())
    }

    /// The world-px position of the viewport's top-left corner.
    ///
    /// Only meaningful north-up: a rotated viewport has no axis-aligned corner in world
    /// space. Anything that needs the ground a rotated viewport covers wants
    /// [`viewport_bounds`](Self::viewport_bounds) instead.
    pub fn viewport_origin(&self) -> WorldPx {
        let center = project(self.center_lon, self.center_lat, self.zoom);
        WorldPx {
            x: center.x - self.width_dp as f64 / 2.0,
            y: center.y - self.height_dp as f64 / 2.0,
        }
    }

    /// The world-px axis-aligned box the viewport covers.
    ///
    /// **Larger than the viewport whenever the camera is rotated**, and that is the whole
    /// reason this exists: a 45-degree bearing makes the covered box up to `sqrt(2)` times
    /// the viewport across each axis, so a caller that derived it from an unrotated
    /// `origin .. origin + size` leaves the four corners of the screen uncovered. At
    /// bearing zero it is exactly `viewport_origin() .. + (width, height)`.
    pub fn viewport_bounds(&self) -> (WorldPx, WorldPx) {
        let center = project(self.center_lon, self.center_lat, self.zoom);
        let (cos, sin) = self.rotation();
        let half_w = self.width_dp as f64 / 2.0;
        let half_h = self.height_dp as f64 / 2.0;
        // The half-extents of the rotated rectangle's bounding box: the support function
        // of a box under a rotation, which is why the terms are absolute values rather
        // than signed — the widest corner is on a different side for each quadrant.
        let extent_x = cos.abs() * half_w + sin.abs() * half_h;
        let extent_y = sin.abs() * half_w + cos.abs() * half_h;
        (
            WorldPx { x: center.x - extent_x, y: center.y - extent_y },
            WorldPx { x: center.x + extent_x, y: center.y + extent_y },
        )
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
        self.world_quad_to_clip(WorldPx { x: x as f64 * span, y: y as f64 * span }, span)
    }

    /// Column-major 4x4 taking a local 0..1 square to Vulkan clip space, where the
    /// square's `(0, 0)` corner sits at `origin` world px and its side is `span` world px
    /// at this camera's zoom.
    ///
    /// [`tile_to_clip`](Self::tile_to_clip) is the case where the square is a tile. A
    /// geographic overlay — a route line — is the case where it is not: its geometry is
    /// normalised into its own bounding square rather than into a tile, because it is not
    /// tile-bound and never goes through tile decode. Sharing the derivation is what keeps
    /// the overlay glued to the same ground as the basemap under it.
    ///
    /// Bearing enters here and only here (plus its screen-anchored sibling below), as the
    /// rotation that takes a world-px offset from the camera centre to a screen-px offset:
    ///
    /// ```text
    /// screen = ( cos*dx + sin*dy,
    ///           -sin*dx + cos*dy )
    /// ```
    ///
    /// with `dx`/`dy` measured from the centre. Its sign is fixed by what heading-up
    /// means: at a bearing of 90 the camera faces east, so east has to come out pointing
    /// up the screen and north pointing left. Getting it backwards mirrors the turn.
    pub fn world_quad_to_clip(&self, origin: WorldPx, span: f64) -> [f32; 16] {
        let center = project(self.center_lon, self.center_lat, self.zoom);
        let (cos, sin) = self.rotation();
        // Measured from the camera centre, not from a viewport corner: a rotated viewport
        // has no world-space corner to measure from, and the centre is the fixed point of
        // the rotation.
        let dx = origin.x - center.x;
        let dy = origin.y - center.y;
        if self.pitch_deg == 0.0 {
            let kx = 2.0 / self.width_dp as f64;
            let ky = 2.0 / self.height_dp as f64;

            return [
                (kx * cos * span) as f32, (ky * -sin * span) as f32, 0.0, 0.0, //
                (kx * sin * span) as f32, (ky * cos * span) as f32, 0.0, 0.0, //
                0.0, 0.0, 1.0, 0.0, //
                (kx * (cos * dx + sin * dy)) as f32, (ky * (-sin * dx + cos * dy)) as f32, 0.0, 1.0,
            ];
        }
        // Tilted: the same bearing-rotated screen offset as above, but kept as world px (Dp)
        // rather than pre-scaled to clip, and fed through the perspective divide. `a`/`b` are
        // the `u`/`v`/constant terms of a tile-local point's screen offset `sx`/`sy`.
        let a = [cos * span, sin * span, cos * dx + sin * dy];
        let b = [-sin * span, cos * span, -sin * dx + cos * dy];
        self.perspective_plane(a, b)
    }

    /// Column-major 4x4 taking a quad's local −1..1 coordinates to Vulkan clip space,
    /// for a quad of `radius_dp` centred on `lon`/`lat`.
    ///
    /// The screen-anchored sibling of [`tile_to_clip`](Self::tile_to_clip). Same
    /// derivation, but the extent is a fixed number of Dp instead of a tile span, so the
    /// quad keeps its screen size as the camera zooms while staying glued to its ground
    /// position. That is what an overlay wants and what a tile address cannot express:
    /// the alternative is to find the tile containing the point and borrow its matrix,
    /// which works for a POI icon because a POI *is* tile data, and is a fiction for
    /// anything that is not.
    ///
    /// The y-sign note on [`tile_to_clip`](Self::tile_to_clip) applies here too: Vulkan
    /// clip y and Mercator y both point down, so there is no flip.
    ///
    /// The quad's own axes **rotate with the map** under a bearing, rather than staying
    /// screen-aligned. That is what keeps the puck's bearing cone honest: the cone's angle
    /// is a geographic heading resolved inside the shader against the quad's local frame,
    /// so a frame that turns with the map leaves a north-pointing cone pointing north on
    /// the ground and up the screen when the camera faces north. Freezing the axes to the
    /// screen instead would leave the cone pointing at the top of a heading-up display no
    /// matter which way the car was going. The puck's dot and rim are circles, so the
    /// choice is invisible to everything else the quad draws.
    pub fn screen_quad_to_clip(&self, lon: f64, lat: f64, radius_dp: f64) -> [f32; 16] {
        let anchor = project(lon, lat, self.zoom);
        let center = project(self.center_lon, self.center_lat, self.zoom);
        let (cos, sin) = self.rotation();
        let dx = anchor.x - center.x;
        let dy = anchor.y - center.y;
        if self.pitch_deg == 0.0 {
            let kx = 2.0 / self.width_dp as f64;
            let ky = 2.0 / self.height_dp as f64;

            return [
                (kx * cos * radius_dp) as f32, (ky * -sin * radius_dp) as f32, 0.0, 0.0, //
                (kx * sin * radius_dp) as f32, (ky * cos * radius_dp) as f32, 0.0, 0.0, //
                0.0, 0.0, 1.0, 0.0, //
                (kx * (cos * dx + sin * dy)) as f32, (ky * (-sin * dx + cos * dy)) as f32, 0.0, 1.0,
            ];
        }
        // Tilted: project the anchor's ground point through the same perspective, then hang a
        // screen-aligned quad of fixed Dp size off it. The corners offset in clip by the
        // anchor's own `w`, so the perspective divide leaves a constant *screen* size — the quad
        // stays upright and keeps its radius rather than being smeared along the ground. Its
        // local axes still turn with the bearing, so the puck's cone points the right way.
        let (psin, pcos) = self.pitch_deg.to_radians().sin_cos();
        let p = self.perspective();
        let sx = cos * dx + sin * dy;
        let sy = -sin * dx + cos * dy;
        let aw = p.d - psin * sy;
        let ax = p.fx * sx;
        let ay = p.fy * pcos * sy;
        let az = p.depth_a * aw - p.depth_b;
        let rx = radius_dp / (self.width_dp as f64 / 2.0) * aw;
        let ry = radius_dp / (self.height_dp as f64 / 2.0) * aw;
        [
            (cos * rx) as f32, (-sin * ry) as f32, 0.0, 0.0, //
            (sin * rx) as f32, (cos * ry) as f32, 0.0, 0.0, //
            0.0, 0.0, 0.0, 0.0, //
            ax as f32, ay as f32, az as f32, aw as f32,
        ]
    }

    /// The tilt-dependent perspective constants for this camera. See [`Perspective`].
    fn perspective(&self) -> Perspective {
        let half_w = self.width_dp as f64 / 2.0;
        let half_h = self.height_dp as f64 / 2.0;
        // MapLibre-like: the centre sits 1.5 viewport-heights from the eye. `d` cancels at
        // pitch 0, so this only sets the foreshortening strength.
        let d = 1.5 * self.height_dp as f64;
        let n = 0.1 * d;
        let f = 10.0 * d;
        Perspective { fx: d / half_w, fy: d / half_h, d, depth_a: f / (f - n), depth_b: f * n / (f - n) }
    }

    /// Build the perspective clip matrix from the screen-flat plane coefficients.
    ///
    /// `a = [a_u, a_v, a_0]` and `b = [b_u, b_v, b_0]` give the bearing-rotated screen offset of
    /// a tile-local point `(u, v)` from the camera centre, in world px (Dp): `sx = a_u*u + a_v*v
    /// + a_0`, `sy = b_u*u + b_v*v + b_0`. The matrix maps `(u, v, height, 1)`; the third input
    /// is a world-px height above the ground plane (0 for every flat 2D layer, a real height for
    /// buildings/terrain).
    ///
    /// # Camera model (the seam WS-G extends to a heightfield)
    ///
    /// The ground is a plane at camera-to-centre distance `d`, tilted back by `pitch`. A point at
    /// screen-flat offset `(sx, sy)` and height `hh` sits in camera space at
    /// ```text
    /// Xc = sx
    /// Yc = -sy*cos + hh*sin
    /// Zc = -d + sy*sin + hh*cos      (w = -Zc, the perspective divisor)
    /// ```
    /// with `sy > 0` (lower on screen) nearer the eye. With `fx = d/half_w`, `fy = d/half_h` the
    /// projection reduces, at `pitch == 0` and `hh == 0`, term-for-term to the ortho matrix — the
    /// reason pitch 0 takes the fast path and this is only ever built when tilted. WS-G replaces
    /// the flat `hh` here (and the single plane solve in [`screen_to_world`](Self::screen_to_world))
    /// with a DEM sample; keep the forward and inverse in step.
    fn perspective_plane(&self, a: [f64; 3], b: [f64; 3]) -> [f32; 16] {
        let (sin, cos) = self.pitch_deg.to_radians().sin_cos();
        let Perspective { fx, fy, d, depth_a, depth_b } = self.perspective();
        let [au, av, a0] = a;
        let [bu, bv, b0] = b;
        // Column-major: the u, v, height and constant columns of (Xclip, Yclip, Zclip, Wclip).
        [
            (fx * au) as f32, (fy * cos * bu) as f32, (depth_a * (-sin * bu)) as f32, (-sin * bu) as f32, //
            (fx * av) as f32, (fy * cos * bv) as f32, (depth_a * (-sin * bv)) as f32, (-sin * bv) as f32, //
            0.0, (fy * -sin) as f32, (depth_a * -cos) as f32, (-cos) as f32, //
            (fx * a0) as f32, (fy * cos * b0) as f32, (depth_a * (d - sin * b0) - depth_b) as f32, (d - sin * b0) as f32,
        ]
    }

    /// The ground world-px under a screen point (Dp from the viewport top-left), by intersecting
    /// the eye ray with the flat ground plane.
    ///
    /// `None` when the point is at or above the horizon — impossible on-screen while
    /// `pitch_deg <= `[`PITCH_MAX_DEG`], which is the cap's whole purpose. This is the flat-plane
    /// seam WS-G extends to ray/heightfield: swap the single plane solve for a march against the
    /// DEM. It is the exact inverse of [`perspective_plane`](Self::perspective_plane)'s forward
    /// projection; keep the two in step.
    pub fn screen_to_world(&self, screen_x_dp: f64, screen_y_dp: f64) -> Option<WorldPx> {
        let center = project(self.center_lon, self.center_lat, self.zoom);
        let (cos, sin) = self.rotation();
        let half_w = self.width_dp as f64 / 2.0;
        let half_h = self.height_dp as f64 / 2.0;
        let sx;
        let sy;
        if self.pitch_deg == 0.0 {
            sx = screen_x_dp - half_w;
            sy = screen_y_dp - half_h;
        } else {
            let p = self.perspective();
            let (psin, pcos) = self.pitch_deg.to_radians().sin_cos();
            let ndc_x = (screen_x_dp - half_w) / half_w;
            let ndc_y = (screen_y_dp - half_h) / half_h;
            // Invert clip.y = fy*cos*sy / (d - sin*sy) for sy, then clip.x for sx.
            let denom = p.fy * pcos + ndc_y * psin;
            if denom <= 0.0 {
                return None; // at or above the horizon: the ray never meets the ground.
            }
            sy = ndc_y * p.d / denom;
            let w = p.d - psin * sy;
            sx = ndc_x * w / p.fx;
        }
        // Inverse bearing rotation: screen-flat offset back to a world-px offset from centre.
        Some(WorldPx { x: center.x + cos * sx - sin * sy, y: center.y + sin * sx + cos * sy })
    }

    /// The ground world-px under a screen point, intersecting the eye ray with the **displaced
    /// terrain** rather than the flat plane — the ray/heightfield extension of
    /// [`screen_to_world`](Self::screen_to_world) that WS-G's seam in
    /// [`perspective_plane`](Self::perspective_plane) anticipates.
    ///
    /// `height_at` returns the terrain height at a world-px ground position, in **world px** — the
    /// same unit the perspective matrix's `z` input uses (metres scaled by the tile's
    /// world-px-per-metre; see [`crate::tess::terrain`]). It is a closure rather than a field so
    /// this stays a pure function of the camera: the renderer passes a sampler over its resident
    /// heightmaps, tests pass a synthetic surface.
    ///
    /// At `pitch_deg == 0` the projection ignores height for x/y, so a tap lands on exactly the
    /// flat-plane point regardless of relief and this returns
    /// [`screen_to_world`](Self::screen_to_world) unchanged. Above zero it walks the eye ray from
    /// the eye toward the flat-plane hit, sampling `height_at`, and returns the first crossing of
    /// the terrain surface, refined by bisection.
    ///
    /// The march exploits that the map from screen-flat `(sx, sy, height)` to camera space is
    /// affine, so the eye ray is a straight line there: the eye is `(0, d·sin, d·cos)` (solving the
    /// camera model in [`perspective_plane`](Self::perspective_plane) for the origin) and the
    /// flat-plane hit is the ray's `height == 0` point, so the segment between them — extended a
    /// little past the plane for below-sea terrain — is the ray. `None` above the horizon
    /// (impossible on-screen under [`PITCH_MAX_DEG`]) or when the ray never meets the terrain, in
    /// which case the flat-plane hit is the best answer.
    pub fn screen_to_world_over_terrain(
        &self,
        screen_x_dp: f64,
        screen_y_dp: f64,
        height_at: impl Fn(WorldPx) -> f64,
    ) -> Option<WorldPx> {
        if self.pitch_deg == 0.0 {
            return self.screen_to_world(screen_x_dp, screen_y_dp);
        }
        // The flat-plane hit is the ray's height-0 point and, with the eye, fixes its direction.
        let plane = self.screen_to_world(screen_x_dp, screen_y_dp)?;
        let center = project(self.center_lon, self.center_lat, self.zoom);
        let (cos, sin) = self.rotation();
        // The plane hit as a screen-flat offset from the centre: the inverse of the final rotation
        // `screen_to_world` applies.
        let dxw = plane.x - center.x;
        let dyw = plane.y - center.y;
        let sx1 = cos * dxw + sin * dyw;
        let sy1 = -sin * dxw + cos * dyw;
        // The eye in the same (sx, sy, height) frame, and the ray toward the plane hit.
        let p = self.perspective();
        let (psin, pcos) = self.pitch_deg.to_radians().sin_cos();
        let eye = (0.0f64, p.d * psin, p.d * pcos);
        let dir = (sx1 - eye.0, sy1 - eye.1, 0.0 - eye.2);

        let world_at = |t: f64| {
            let sx = eye.0 + t * dir.0;
            let sy = eye.1 + t * dir.1;
            WorldPx { x: center.x + cos * sx - sin * sy, y: center.y + sin * sx + cos * sy }
        };
        let ray_height = |t: f64| eye.2 + t * dir.2;
        // Positive while the ray is above the terrain, non-positive once it has crossed below.
        let gap = |t: f64| ray_height(t) - height_at(world_at(t));

        // March from the eye toward (and a little past) the plane hit. Terrain above sea rises
        // toward the ray, so its crossing is at t <= 1; below-sea terrain can push it past 1, so the
        // march overshoots before giving up. The pitch cap keeps the whole thing bounded.
        const STEPS: usize = 96;
        const T_MAX: f64 = 1.5;
        if gap(0.0) <= 0.0 {
            // The eye is at or under the terrain: degenerate, fall back to the plane hit.
            return Some(plane);
        }
        let mut prev_t = 0.0;
        for i in 1..=STEPS {
            let t = T_MAX * i as f64 / STEPS as f64;
            if gap(t) <= 0.0 {
                // A crossing is bracketed in (prev_t, t]; bisect to refine it.
                let (mut lo, mut hi) = (prev_t, t);
                for _ in 0..40 {
                    let mid = 0.5 * (lo + hi);
                    if gap(mid) > 0.0 {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                return Some(world_at(0.5 * (lo + hi)));
            }
            prev_t = t;
        }
        // The ray grazed above every sample: the flat-plane hit is the best available answer.
        Some(plane)
    }

    /// The screen point (Dp from the viewport top-left) a ground world-px projects to, or `None`
    /// when it falls behind the eye. The forward twin of [`screen_to_world`](Self::screen_to_world);
    /// exists mainly so the round-trip is testable and the Kotlin `Projection` can mirror it.
    pub fn world_to_screen(&self, world: WorldPx) -> Option<(f64, f64)> {
        let center = project(self.center_lon, self.center_lat, self.zoom);
        let (cos, sin) = self.rotation();
        let half_w = self.width_dp as f64 / 2.0;
        let half_h = self.height_dp as f64 / 2.0;
        let dxw = world.x - center.x;
        let dyw = world.y - center.y;
        let sx = cos * dxw + sin * dyw;
        let sy = -sin * dxw + cos * dyw;
        if self.pitch_deg == 0.0 {
            return Some((half_w + sx, half_h + sy));
        }
        let p = self.perspective();
        let (psin, pcos) = self.pitch_deg.to_radians().sin_cos();
        let w = p.d - psin * sy;
        if w <= 0.0 {
            return None;
        }
        Some((half_w + (p.fx * sx / w) * half_w, half_h + (p.fy * pcos * sy / w) * half_h))
    }

    /// Lon/lat under a screen point (Dp from the viewport top-left), tilt-aware. See
    /// [`screen_to_world`](Self::screen_to_world).
    pub fn screen_to_lonlat(&self, screen_x_dp: f64, screen_y_dp: f64) -> Option<(f64, f64)> {
        self.screen_to_world(screen_x_dp, screen_y_dp).map(|w| unproject(w.x, w.y, self.zoom))
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
            bearing_deg: 0.0,
            pitch_deg: 0.0,
            time_seconds: 0.0,
        }
    }

    /// Apply a column-major 4x4 to a 2D point, as the vertex shader does.
    fn transform(m: &[f32; 16], u: f32, v: f32) -> (f32, f32) {
        (m[0] * u + m[4] * v + m[12], m[1] * u + m[5] * v + m[13])
    }

    /// Full 4-vector transform of `(x, y, z, 1)` — needed on the pitched path, where the
    /// perspective divide by `w` is not the identity `transform` assumes.
    fn transform4(m: &[f32; 16], x: f32, y: f32, z: f32) -> (f32, f32, f32, f32) {
        (
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14],
            m[3] * x + m[7] * y + m[11] * z + m[15],
        )
    }

    #[test]
    fn the_world_is_512_dp_per_tile() {
        // MapLibre's convention, and the one the archives are authored on: tile
        // addressing is the plain floor of the camera zoom.
        assert_eq!(world_size(0.0), 512.0);
        assert_eq!(world_size(1.0), 1024.0);
        assert_eq!(world_size(14.0), 512.0 * 16384.0);
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
        assert!((p.x - TILE_SIZE / 2.0).abs() < 1e-9);
        assert!((p.y - TILE_SIZE / 2.0).abs() < 1e-9);
    }

    #[test]
    fn the_tile_containing_the_camera_covers_the_viewport_centre() {
        // At z1 centred on null island, the four tiles meet exactly at the centre of a
        // 1024 Dp viewport, so tile 0/0's bottom-right corner lands at clip (0, 0).
        let camera = Camera { width_dp: 1024.0, height_dp: 1024.0, ..camera(1.0) };
        let m = camera.tile_to_clip(1, 0, 0);
        let (x, y) = transform(&m, 1.0, 1.0);
        assert!(x.abs() < 1e-5, "x {x}");
        assert!(y.abs() < 1e-5, "y {y}");
    }

    /// Task-1 camera-scale parity: the viewport centre always maps to clip
    /// origin through the shared matrix, whatever the tile grid. Pins the
    /// matrix half of the anchor contract on the 512 grid.
    #[test]
    fn an_anchor_at_viewport_centre_projects_to_clip_origin() {
        // z10 centred on SF: the SF tile-local centre must land at clip (0,0)
        // through tile_to_clip.
        let camera = Camera {
            center_lon: -122.4194,
            center_lat: 37.7749,
            zoom: 10.0,
            width_dp: 512.0,
            height_dp: 512.0,
            density: 1.0,
            bearing_deg: 0.0,
            pitch_deg: 0.0,
            time_seconds: 0.0,
        };
        // The tile containing SF at z10, and SF's tile-local position in it.
        let world = project(-122.4194, 37.7749, 10.0);
        let span = camera.tile_span_dp(10);
        let tx = (world.x / span).floor() as u32;
        let ty = (world.y / span).floor() as u32;
        let u = (world.x - tx as f64 * span) / span;
        let v = (world.y - ty as f64 * span) / span;
        assert!((0.0..=1.0).contains(&u) && (0.0..=1.0).contains(&v));
        let m = camera.tile_to_clip(10, tx, ty);
        let (cx, cy) = transform(&m, u as f32, v as f32);
        assert!(cx.abs() < 1e-4, "SF anchor clip x {cx} (tile {tx},{ty} local {u:.4},{v:.4})");
        assert!(cy.abs() < 1e-4, "SF anchor clip y {cy} (tile {tx},{ty} local {u:.4},{v:.4})");
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
        // At z0 with a 512 Dp viewport the single tile is exactly the screen, so its
        // corners are the corners of clip space.
        let camera = Camera { width_dp: 512.0, height_dp: 512.0, ..camera(0.0) };
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
        assert!((camera.tile_span_dp(16) - 512.0 * 8.0).abs() < 1e-9);
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

    #[test]
    fn a_screen_quad_on_the_camera_centre_lands_on_the_clip_origin() {
        // The puck's whole point is being glued to a ground position, and the camera
        // centre is the one position whose clip coordinate is known without arithmetic.
        let camera = Camera { center_lon: -122.4194, center_lat: 37.7749, ..camera(14.0) };
        let m = camera.screen_quad_to_clip(-122.4194, 37.7749, 28.0);
        let (x, y) = transform(&m, 0.0, 0.0);
        assert!(x.abs() < 1e-5, "x {x}");
        assert!(y.abs() < 1e-5, "y {y}");
    }

    #[test]
    fn a_screen_quad_keeps_its_dp_size_across_zooms_and_grows_with_the_viewport() {
        // A tile quad doubles on screen every zoom; this one must not, or the puck would
        // swell into a blue disc the size of a city block at z18.
        let close = Camera { center_lon: 0.0, center_lat: 0.0, ..camera(18.0) };
        let far = Camera { center_lon: 0.0, center_lat: 0.0, ..camera(4.0) };
        let (near_x, _) = transform(&close.screen_quad_to_clip(0.0, 0.0, 28.0), 1.0, 0.0);
        let (wide_x, _) = transform(&far.screen_quad_to_clip(0.0, 0.0, 28.0), 1.0, 0.0);
        assert!((near_x - wide_x).abs() < 1e-6, "{near_x} vs {wide_x}");
        // 28 Dp of a 512 Dp viewport is 28/256 of the half-width of clip space.
        assert!((near_x - 28.0 / 256.0).abs() < 1e-5, "{near_x}");
    }

    #[test]
    fn a_screen_quad_is_the_same_size_at_every_density() {
        // The radius is Dp, like `tile_span_dp`. Density enters only where a Dp becomes a
        // device pixel, which for the puck is the shader's radii — not this matrix.
        let one = Camera { density: 1.0, ..camera(14.0) };
        let three = Camera { density: 3.0, ..camera(14.0) };
        assert_eq!(one.screen_quad_to_clip(0.0, 0.0, 28.0), three.screen_quad_to_clip(0.0, 0.0, 28.0));
    }

    #[test]
    fn a_screen_quad_far_off_screen_falls_outside_the_clip_cube() {
        // A fix taken in another country must not smear a puck across the edge of the
        // viewport: the whole quad has to clip out.
        let camera = Camera { center_lon: -122.4194, center_lat: 37.7749, ..camera(14.0) };
        let m = camera.screen_quad_to_clip(2.3522, 48.8566, 28.0);
        let (left, _) = transform(&m, -1.0, 0.0);
        let (right, _) = transform(&m, 1.0, 0.0);
        assert!(left > 1.0 && right > 1.0, "Paris at {left}..{right} should be off to the right");
    }

    // --- bearing ------------------------------------------------------------

    #[test]
    fn a_zero_bearing_leaves_every_matrix_exactly_as_it_was() {
        // The whole Compose/phone path runs at bearing zero, so this is the regression
        // guard for it: the rotated derivation must reduce term for term, not merely to
        // within a tolerance.
        let north_up = Camera { center_lon: -122.4194, center_lat: 37.7749, ..camera(12.0) };
        let m = north_up.tile_to_clip(12, 654, 1583);
        assert_eq!(m[1], 0.0, "no shear into y");
        assert_eq!(m[4], 0.0, "no shear into x");
        let quad = north_up.screen_quad_to_clip(-122.4194, 37.7749, 28.0);
        assert_eq!(quad[1], 0.0);
        assert_eq!(quad[4], 0.0);
    }

    #[test]
    fn a_bearing_of_ninety_puts_east_at_the_top_of_the_screen() {
        // The sign of the rotation, which is the one thing easy to get backwards: facing
        // east means east is up and north is to the left. A mirrored rotation sends the
        // car around every corner the wrong way.
        let heading_east = Camera { bearing_deg: 90.0, ..camera(10.0) };
        let centre = project(0.0, 0.0, 10.0);
        let span = heading_east.tile_span_dp(10);
        // A point one tile-span due east of the camera centre, addressed through the
        // shared quad matrix so this pins the same arithmetic every draw uses.
        let m = heading_east
            .world_quad_to_clip(WorldPx { x: centre.x + span, y: centre.y }, span);
        let (x, y) = transform(&m, 0.0, 0.0);
        assert!(x.abs() < 1e-5, "east must sit on the vertical centreline, not at x {x}");
        assert!(y < -1e-3, "east must be above the centre, not at y {y}");

        // And due north lands to the left.
        let north = heading_east
            .world_quad_to_clip(WorldPx { x: centre.x, y: centre.y - span }, span);
        let (nx, ny) = transform(&north, 0.0, 0.0);
        assert!(nx < -1e-3, "north must be left of centre, not at x {nx}");
        assert!(ny.abs() < 1e-5, "north must sit on the horizontal centreline, not at y {ny}");
    }

    #[test]
    fn the_camera_centre_stays_on_the_clip_origin_at_every_bearing() {
        // The rotation's fixed point. If it drifts, the map slides sideways as the car
        // turns instead of pivoting under the puck.
        for bearing in [0.0, 37.0, 90.0, 180.0, 271.5, -45.0] {
            let camera = Camera {
                center_lon: -122.4194,
                center_lat: 37.7749,
                bearing_deg: bearing,
                ..camera(14.0)
            };
            let m = camera.screen_quad_to_clip(-122.4194, 37.7749, 28.0);
            let (x, y) = transform(&m, 0.0, 0.0);
            assert!(x.abs() < 1e-5, "bearing {bearing}: x {x}");
            assert!(y.abs() < 1e-5, "bearing {bearing}: y {y}");
        }
    }

    #[test]
    fn rotation_preserves_ground_distance_on_a_square_viewport() {
        // A rotation must not scale: two points a tile apart have to stay a tile apart on
        // screen whichever way the camera faces, or roads change width as the car turns.
        let span = camera(10.0).tile_span_dp(10);
        let centre = project(0.0, 0.0, 10.0);
        let length = |bearing: f64| {
            let c = Camera { bearing_deg: bearing, ..camera(10.0) };
            let m = c.world_quad_to_clip(WorldPx { x: centre.x + span, y: centre.y }, span);
            let (x, y) = transform(&m, 0.0, 0.0);
            (x * x + y * y).sqrt()
        };
        let north_up = length(0.0);
        for bearing in [17.0, 45.0, 90.0, 213.0] {
            assert!(
                (length(bearing) - north_up).abs() < 1e-5,
                "bearing {bearing} scaled the map: {} vs {north_up}",
                length(bearing),
            );
        }
    }

    #[test]
    fn the_viewport_bounds_are_the_plain_viewport_when_north_up() {
        let camera = Camera { center_lon: -122.4194, center_lat: 37.7749, ..camera(14.0) };
        let (min, max) = camera.viewport_bounds();
        let origin = camera.viewport_origin();
        assert!((min.x - origin.x).abs() < 1e-9, "{} vs {}", min.x, origin.x);
        assert!((min.y - origin.y).abs() < 1e-9);
        assert!((max.x - (origin.x + camera.width_dp as f64)).abs() < 1e-9);
        assert!((max.y - (origin.y + camera.height_dp as f64)).abs() < 1e-9);
    }

    #[test]
    fn a_rotated_viewport_covers_more_ground_than_an_axis_aligned_one() {
        // The corners of a rotated screen reach further out in world space than the
        // screen's own width and height. This is what tile selection has to be derived
        // from; deriving it from the unrotated box leaves the corners of the display
        // permanently empty.
        let square = Camera { bearing_deg: 45.0, ..camera(14.0) };
        let (min, max) = square.viewport_bounds();
        let across = max.x - min.x;
        let expected = 512.0 * 2f64.sqrt();
        assert!((across - expected).abs() < 1e-6, "{across} should be {expected}");

        // Every corner of the rotated viewport really is inside the box.
        let centre = project(square.center_lon, square.center_lat, square.zoom);
        let radians = 45f64.to_radians();
        for (sx, sy) in [(-256.0, -256.0), (256.0, -256.0), (256.0, 256.0), (-256.0, 256.0)] {
            // Screen offset back to world: the inverse of the rotation the matrix applies.
            let wx: f64 = centre.x + radians.cos() * sx - radians.sin() * sy;
            let wy: f64 = centre.y + radians.sin() * sx + radians.cos() * sy;
            assert!(wx >= min.x - 1e-6 && wx <= max.x + 1e-6, "corner x {wx} outside the box");
            assert!(wy >= min.y - 1e-6 && wy <= max.y + 1e-6, "corner y {wy} outside the box");
        }
    }

    // --- pitch / perspective ------------------------------------------------

    #[test]
    fn an_explicit_zero_pitch_is_the_untilted_matrix_byte_for_byte() {
        // The regression guard for the whole flat/phone path: adding the pitch and time fields
        // must not perturb a single bit of the matrix a north-up, level camera produces.
        let level = Camera { center_lon: -122.4194, center_lat: 37.7749, ..camera(12.0) };
        let untilted = level.tile_to_clip(12, 654, 1583);
        assert_eq!(untilted, Camera { pitch_deg: 0.0, ..level }.tile_to_clip(12, 654, 1583));
        // And the clock never reaches the matrix.
        assert_eq!(untilted, Camera { time_seconds: 98765.0, ..level }.tile_to_clip(12, 654, 1583));
        let quad = level.screen_quad_to_clip(-122.4194, 37.7749, 28.0);
        assert_eq!(quad, Camera { pitch_deg: 0.0, ..level }.screen_quad_to_clip(-122.4194, 37.7749, 28.0));
    }

    #[test]
    fn a_pitched_matrix_actually_tilts() {
        // Sanity that the pitched path is a *different* matrix, and a real perspective one:
        // its bottom row is no longer the ortho `(_, _, 0, 1)`, so a w-divide happens.
        let tilted = Camera { pitch_deg: 45.0, ..camera(12.0) }.tile_to_clip(12, 2048, 2048);
        assert!(tilted[15] != 1.0 || tilted[3] != 0.0 || tilted[7] != 0.0, "no perspective term");
    }

    #[test]
    fn the_pitched_centre_stays_on_the_clip_origin() {
        // The fixed point of the tilt: whatever the pitch, the camera centre projects to clip 0
        // and sits in front of the eye.
        for pitch in [15.0, 30.0, 45.0, 60.0] {
            let cam = Camera { center_lon: -122.4194, center_lat: 37.7749, pitch_deg: pitch, ..camera(12.0) };
            let m = cam.screen_quad_to_clip(-122.4194, 37.7749, 28.0);
            let (x, y, _, w) = transform4(&m, 0.0, 0.0, 0.0);
            assert!(w > 0.0, "pitch {pitch}: centre behind the eye (w {w})");
            assert!((x / w).abs() < 1e-5 && (y / w).abs() < 1e-5, "pitch {pitch}: centre at {},{}", x / w, y / w);
        }
    }

    #[test]
    fn screen_to_world_at_pitch_zero_is_the_plain_inverse() {
        let cam = Camera { center_lon: 10.0, center_lat: 20.0, ..camera(8.0) };
        let center = project(10.0, 20.0, 8.0);
        let w = cam.screen_to_world(256.0 + 30.0, 256.0 - 10.0).unwrap();
        assert!((w.x - (center.x + 30.0)).abs() < 1e-9, "x {}", w.x);
        assert!((w.y - (center.y - 10.0)).abs() < 1e-9, "y {}", w.y);
    }

    #[test]
    fn the_screen_centre_unprojects_to_the_camera_centre_at_any_pitch() {
        for pitch in [0.0, 20.0, 45.0, 60.0] {
            let cam = Camera { center_lon: -122.4, center_lat: 37.7, pitch_deg: pitch, ..camera(12.0) };
            let center = project(-122.4, 37.7, 12.0);
            let w = cam.screen_to_world(256.0, 256.0).unwrap();
            assert!((w.x - center.x).abs() < 1e-6 && (w.y - center.y).abs() < 1e-6, "pitch {pitch}");
        }
    }

    #[test]
    fn tilt_foreshortens_the_top_of_the_screen() {
        // The same screen distance above and below centre maps to *more* ground above, because
        // the top of a tilted view recedes toward the horizon. At pitch 0 the two are equal.
        let cam = Camera { pitch_deg: 45.0, ..camera(12.0) };
        let center = project(cam.center_lon, cam.center_lat, 12.0);
        let above = cam.screen_to_world(256.0, 256.0 - 100.0).unwrap();
        let below = cam.screen_to_world(256.0, 256.0 + 100.0).unwrap();
        let up = (center.y - above.y).abs();
        let down = (below.y - center.y).abs();
        assert!(up > down * 1.2, "top should recede: up {up} vs down {down}");
    }

    // --- ray/heightfield unproject (WS-G) -----------------------------------

    #[test]
    fn over_terrain_at_pitch_zero_is_the_flat_unproject() {
        // Overhead, height never touches x/y, so a tap lands on exactly the flat-plane point
        // whatever the relief — the pitch-0 map is unchanged.
        let cam = Camera { center_lon: 10.0, center_lat: 20.0, ..camera(12.0) };
        for &(x, y) in &[(256.0, 256.0), (120.0, 40.0), (400.0, 500.0)] {
            let flat = cam.screen_to_world(x, y).unwrap();
            let over = cam.screen_to_world_over_terrain(x, y, |_| 5000.0).unwrap();
            assert!((flat.x - over.x).abs() < 1e-9 && (flat.y - over.y).abs() < 1e-9);
        }
    }

    #[test]
    fn flat_zero_terrain_matches_the_plane() {
        // A terrain everywhere at sea level is the flat plane, so the heightfield hit must equal
        // the plane hit under tilt too.
        let cam = Camera { center_lon: -122.4, center_lat: 37.7, pitch_deg: 50.0, ..camera(13.0) };
        for &(x, y) in &[(256.0, 120.0), (256.0, 256.0), (300.0, 400.0)] {
            let flat = cam.screen_to_world(x, y).unwrap();
            let over = cam.screen_to_world_over_terrain(x, y, |_| 0.0).unwrap();
            assert!(
                (flat.x - over.x).abs() < 1e-4 && (flat.y - over.y).abs() < 1e-4,
                "zero terrain must match the plane: {flat:?} vs {over:?}",
            );
        }
    }

    #[test]
    fn higher_ground_is_hit_nearer_the_camera() {
        // A tap toward the top of a tilted screen looks up-map toward the horizon. Raising the
        // terrain makes the ray strike the hillside sooner, so the hit moves back toward the
        // camera centre (a smaller up-map distance) — the essence of hitting the displaced surface.
        let cam = Camera { center_lon: 0.0, center_lat: 0.0, pitch_deg: 55.0, ..camera(14.0) };
        let pixel = (256.0, 80.0); // above the centre: up-map, toward the horizon
        let flat = cam.screen_to_world(pixel.0, pixel.1).unwrap();
        // World-px heights well under the eye height (~d·cos = 766 px here), as real ~30 m terrain
        // at z14 is (about 0.2 world-px per metre).
        let low = cam.screen_to_world_over_terrain(pixel.0, pixel.1, |_| 30.0).unwrap();
        let high = cam.screen_to_world_over_terrain(pixel.0, pixel.1, |_| 90.0).unwrap();
        // Up-map is toward smaller world y here (north), so a nearer hit has a larger y.
        assert!(low.y > flat.y, "raised terrain is hit nearer the camera than the flat plane");
        assert!(high.y > low.y, "higher terrain is hit nearer still");
    }

    #[test]
    fn the_hit_lies_on_the_eye_ray_and_on_the_surface() {
        // The full guarantee for constant-height terrain: the returned point is (a) on the terrain
        // surface — its height is the sampled one — and (b) on the pixel's eye ray, i.e. collinear
        // with the eye and the flat-plane hit in the screen-flat (sx, sy, height) frame. Both are
        // checked against the documented camera model rather than the implementation.
        let height_dp = 891.0;
        let cam = Camera {
            center_lon: 2.35,
            center_lat: 48.85,
            pitch_deg: 45.0,
            width_dp: 411.0,
            height_dp,
            ..camera(13.0)
        };
        let center = project(2.35, 48.85, 13.0);
        let (cos, sin) = cam.rotation();
        // The eye in the (sx, sy, height) frame: (0, d·sin, d·cos), d = 1.5 viewport heights.
        let d = 1.5 * height_dp as f64;
        let (psin, pcos) = cam.pitch_deg.to_radians().sin_cos();
        let eye = (0.0, d * psin, d * pcos);

        let h0 = 300.0; // world-px, constant
        let pixel = (256.0, 150.0);
        let plane = cam.screen_to_world(pixel.0, pixel.1).unwrap();
        let hit = cam.screen_to_world_over_terrain(pixel.0, pixel.1, |_| h0).unwrap();

        // Screen-flat offsets of the plane hit and the terrain hit.
        let sflat = |w: WorldPx| {
            let dx = w.x - center.x;
            let dy = w.y - center.y;
            (cos * dx + sin * dy, -sin * dx + cos * dy)
        };
        let (px, py) = sflat(plane); // height 0
        let (hx, hy) = sflat(hit); // height h0

        // Collinearity: hit = eye + t·(plane − eye) for one t across all three coordinates. Solve
        // t from height, then confirm x and y agree.
        let t = (h0 - eye.2) / (0.0 - eye.2);
        let want_x = eye.0 + t * (px - eye.0);
        let want_y = eye.1 + t * (py - eye.1);
        assert!((hx - want_x).abs() < 1e-3, "hit off the ray in sx: {hx} vs {want_x}");
        assert!((hy - want_y).abs() < 1e-3, "hit off the ray in sy: {hy} vs {want_y}");
    }

    #[test]
    fn a_ramp_surface_is_hit_where_the_ray_meets_it() {
        // A sloped terrain (height rising with world y): the returned hit's own sampled height must
        // match the ray height there, so the point really sits on the surface rather than the plane.
        let height_dp = 891.0;
        let cam = Camera {
            center_lon: 0.0,
            center_lat: 0.0,
            pitch_deg: 50.0,
            width_dp: 411.0,
            height_dp,
            ..camera(14.0)
        };
        let center = project(0.0, 0.0, 14.0);
        let (cos, sin) = cam.rotation();
        let d = 1.5 * height_dp as f64;
        let (psin, pcos) = cam.pitch_deg.to_radians().sin_cos();
        let eye = (0.0, d * psin, d * pcos);

        // Height rises 0.2 world-px per world-px north of the centre (a gentle ramp).
        let ramp = |w: WorldPx| (center.y - w.y).max(0.0) * 0.2;
        let pixel = (256.0, 100.0);
        let hit = cam.screen_to_world_over_terrain(pixel.0, pixel.1, ramp).unwrap();

        // The ray height at the hit (from collinearity in the screen-flat frame) must equal the
        // terrain height sampled there.
        let dx = hit.x - center.x;
        let dy = hit.y - center.y;
        let (hsx, hsy) = (cos * dx + sin * dy, -sin * dx + cos * dy);
        // Recover t from whichever ray component moves most, then the ray height.
        let plane = cam.screen_to_world(pixel.0, pixel.1).unwrap();
        let pdx = plane.x - center.x;
        let pdy = plane.y - center.y;
        let (psx, psy) = (cos * pdx + sin * pdy, -sin * pdx + cos * pdy);
        let t = if (psy - eye.1).abs() > (psx - eye.0).abs() {
            (hsy - eye.1) / (psy - eye.1)
        } else {
            (hsx - eye.0) / (psx - eye.0)
        };
        let ray_h = eye.2 + t * (0.0 - eye.2);
        assert!(
            (ray_h - ramp(hit)).abs() < 1.0,
            "the hit must sit on the ramp: ray height {ray_h} vs terrain {}",
            ramp(hit),
        );
        // And it is not merely the flat answer: the ramp pushes the hit off the plane.
        assert!((hit.y - plane.y).abs() > 1e-3, "the ramp must move the hit off the flat plane");
    }
}

// device-verifier: mtime bump to force cargo recompile (no semantic change)
