//! Geographic overlay polylines: the navigation route drawn inside the renderer.
//!
//! On the phone the route is a Compose overlay *above* the map surface. Android Auto
//! hands the app a bare `Surface` with no view hierarchy, so there is nothing to put an
//! overlay in and the line has to be drawn by the renderer itself.
//!
//! # A list of coloured segments, one casing
//!
//! The phone colours the route per navigation step — traffic red/amber/green, transit
//! brand colours, a travelled grey behind the puck — and that colouring now lives here
//! rather than in a Compose overlay, so the route pans in lock-step with the basemap the
//! way the puck, region mask and traffic already do. A route is therefore a *list* of
//! coloured runs: [`tessellate`] takes a slice of [`RouteSegment`], each its own polyline
//! and fill colour, and strokes them into one shared mesh.
//!
//! The casing is drawn once over the whole mesh at the wider width and one colour, then
//! each segment's fill is drawn over it in segment colour — so the outline stays
//! continuous across the colour changes while every run keeps its own fill. The car
//! (Android Auto) passes a single segment through the same path, which is the old
//! single-colour route with no special case.
//!
//! # It reuses the road tessellator, and it has to
//!
//! [`crate::tess::stroke`] already strokes every road in the basemap: miter joins, butt
//! caps, a unit join normal per vertex and the width supplied as a push constant. A route
//! is a line with a width, so it goes through the same function and comes out in the same
//! vertex format, drawn by the same `line` pipeline and the same shaders. Writing a second
//! tessellator would be a second set of join bugs to find.
//!
//! # Why there is no re-tessellation on zoom
//!
//! `stroke` wants tile-local coordinates in `0..extent` and produces positions in `0..1`.
//! A route is not tile-bound, so it gets a square of its own: its bounding box, squared
//! off, with the geometry normalised into it. That square is then placed by
//! [`crate::camera::Camera::world_quad_to_clip`] exactly the way a tile is placed by
//! [`crate::camera::Camera::tile_to_clip`].
//!
//! The pay-off is that **the mesh is zoom-independent**. Web Mercator is a pure scale in
//! zoom — `project(lon, lat, z)` is `project(lon, lat, 0) * 2^z` — so normalising by the
//! bounding box divides that factor out and the local coordinates are the same number at
//! every zoom. Only the matrix and the pixel width change per frame, and both are already
//! per-frame values. So a route is tessellated **once, when it is set**, and a car that
//! sits in a navigation session for an hour re-tessellates nothing: no per-frame work, no
//! per-zoom-step work, no threshold to tune. That was the thing worth getting right here,
//! because the phone can afford to redo geometry on a zoom step and a car on battery
//! cannot.
//!
//! The cost is precision. Positions are `f32` in `0..1` against a span that is the whole
//! route's bounding box, so a continent-crossing route resolves to about a hundredth of a
//! Dp on screen — invisible — while a city-scale one is exact to well under a pixel.

use crate::camera::{project, WorldPx};
use crate::tess::stroke;

/// Local coordinate resolution of the route's bounding square.
///
/// A tile uses 4096; this is far finer because the square is the whole route rather than
/// one tile, and a long route quantised on a tile-sized grid would visibly stair-step.
/// `1 << 20` keeps quantisation to a millionth of the bounding box while staying exactly
/// representable in the `f32` the tessellator divides it into.
pub const EXTENT: u32 = 1 << 20;

/// How a route line is painted. Widths are Dp; colours are ARGB, as everywhere else here.
///
/// The fill colour is **not** here: it is per-segment (see [`RouteSegment`]), because the
/// whole point of the route overlay is to colour each run differently. What stays shared
/// is the geometry of the stroke — the line width, and the casing that outlines the lot.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RouteStyle {
    /// The route line's own width.
    pub width_dp: f32,
    /// The casing's width **on each side** of the route line. Zero draws no casing.
    ///
    /// A route without one is genuinely hard to follow: it crosses roads of its own
    /// colour family and a busy basemap, and the outline is what separates it from them.
    pub casing_dp: f32,
    /// The casing's colour, ARGB. Unused when [`casing_dp`](Self::casing_dp) is zero.
    ///
    /// Passed rather than derived from the palette, because the reference draws a **white**
    /// casing on a light basemap — a contrast rule computed here would pick a dark one and
    /// silently change the look the port is supposed to restore.
    pub casing_color: u32,
}

/// One coloured run of a route: a polyline and the ARGB its fill is painted.
///
/// A route arrives as a list of these — a driving route split into traffic bands, a transit
/// route into per-line colours, an active navigation leg into a travelled grey behind the
/// puck and the mode colour ahead. Each run carries its whole polyline, including the point
/// it shares with its neighbours, so the strokes meet end to end under the shared casing.
pub struct RouteSegment {
    /// The run's lon/lat points, in order.
    pub points: Vec<(f64, f64)>,
    /// The run's fill colour, ARGB.
    pub color: u32,
}

/// Where one coloured run lands in a [`RouteMesh`]'s shared index buffer.
///
/// The whole route is stroked into one vertex/index pair so the casing can be drawn over it
/// in a single pass; each run's fill is then a `draw_indexed` of its own slice of that index
/// buffer, in its own colour.
#[derive(Clone, Copy, Debug)]
pub struct RouteSegmentRange {
    /// The run's fill colour, ARGB.
    pub color: u32,
    /// First index into [`RouteMesh::indices`] belonging to this run.
    pub index_offset: u32,
    /// How many indices this run owns.
    pub index_count: u32,
}

/// Where a route's bounding square sits and how it is painted.
///
/// Separate from the vertices so the renderer can keep it beside the GPU buffers after the
/// CPU-side mesh has been dropped: it is 40 bytes, and it is everything a frame needs to
/// build the matrix and the two push blocks.
#[derive(Clone, Copy, Debug)]
pub struct RoutePlacement {
    /// The bounding square's top-left corner, in world px at zoom 0.
    pub origin: WorldPx,
    /// The bounding square's side, in world px at zoom 0.
    pub span: f64,
    /// How the two passes over this geometry are painted.
    pub style: RouteStyle,
}

/// A route tessellated into its own bounding square, ready to upload.
pub struct RouteMesh {
    /// Where it goes and how it looks.
    pub placement: RoutePlacement,
    /// [`stroke::FLOATS_PER_VERTEX`] floats per vertex, in the `line` pipeline's format.
    /// All segments share this one buffer.
    pub vertices: Vec<f32>,
    /// Triangle indices into [`vertices`](Self::vertices), all segments concatenated.
    pub indices: Vec<u32>,
    /// Each coloured run's slice of [`indices`](Self::indices), in draw order. The casing
    /// draws every index at once; the fills draw one slice each, in run colour.
    pub segments: Vec<RouteSegmentRange>,
}

impl RoutePlacement {
    /// The bounding square's origin and side in world px at `zoom`.
    ///
    /// Mercator scales by `2^zoom` and nothing else, so this is the whole of what the
    /// camera needs to place a mesh built at zoom 0.
    pub fn at_zoom(&self, zoom: f64) -> (WorldPx, f64) {
        let scale = 2f64.powf(zoom);
        (WorldPx { x: self.origin.x * scale, y: self.origin.y * scale }, self.span * scale)
    }

    /// The route line's half-width in **device px** — what a fill pass pushes.
    pub fn fill_half(&self, density: f32) -> f32 {
        self.style.width_dp * 0.5 * density
    }

    /// The casing's half-width in **device px**, or `None` when there is no casing.
    ///
    /// The casing stands `casing_dp` outside the route line on each side, so it is the
    /// fill half-width plus that. `None` when [`casing_dp`](RouteStyle::casing_dp) is zero:
    /// `line.vert` floors every band at half a pixel so a hairline still rasterises, so a
    /// zero-width casing pass would paint a pixel of casing colour poking out from under
    /// the route rather than nothing.
    pub fn casing_half(&self, density: f32) -> Option<f32> {
        (self.style.casing_dp > 0.0).then(|| self.fill_half(density) + self.style.casing_dp * density)
    }
}

/// Tessellate a list of coloured runs into one [`RouteMesh`], or `None` when there is
/// nothing to draw.
///
/// Every run's points share one bounding square, so the whole route is one vertex/index
/// pair — the casing is then a single draw over the lot and each run's fill is a draw of
/// its own index slice (see [`RouteMesh::segments`]). A single-colour route (the car) is
/// just a one-run list and takes the same path.
///
/// `None` covers an empty list and a list every run of which is degenerate — empty, a
/// single point, or all-coincident points, none of which has a direction to stroke. A run
/// that is degenerate on its own is skipped rather than failing the whole route, so one
/// glitchy zero-length step does not blank a route being followed. A caller clearing the
/// route passes an empty slice and gets `None`, which is the same thing.
pub fn tessellate(segments: &[RouteSegment], style: RouteStyle) -> Option<RouteMesh> {
    // The bounding box spans every run's points, so all runs normalise into the same
    // square and their strokes line up. Zoom 0 is the reference the mesh is stored at:
    // any zoom would do, since the normalisation divides the scale out, and zero needs
    // no argument.
    let mut min = WorldPx { x: f64::MAX, y: f64::MAX };
    let mut max = WorldPx { x: f64::MIN, y: f64::MIN };
    let mut any = false;
    for segment in segments {
        for &(lon, lat) in &segment.points {
            let p = project(lon, lat, 0.0);
            min.x = min.x.min(p.x);
            min.y = min.y.min(p.y);
            max.x = max.x.max(p.x);
            max.y = max.y.max(p.y);
            any = true;
        }
    }
    if !any {
        return None;
    }
    // Squared off, so one span serves both axes and the local coordinates stay in 0..1
    // the way a tile's do. A route running due north has zero width and would otherwise
    // divide by zero on x.
    let span = (max.x - min.x).max(max.y - min.y);
    if !span.is_finite() || span <= 0.0 {
        return None;
    }

    let mut vertices = Vec::new();
    let mut indices = Vec::new();
    let mut ranges = Vec::new();
    let mut coords: Vec<i32> = Vec::new();
    for segment in segments {
        if segment.points.len() < 2 {
            continue;
        }
        coords.clear();
        coords.reserve(segment.points.len() * 2);
        for &(lon, lat) in &segment.points {
            let p = project(lon, lat, 0.0);
            coords.push((((p.x - min.x) / span) * EXTENT as f64).round() as i32);
            coords.push((((p.y - min.y) / span) * EXTENT as f64).round() as i32);
        }
        let index_offset = indices.len() as u32;
        // Not `gapped`: the casing is a second draw of this same geometry at a wider push
        // constant, so one plain band centred on the run is all the geometry needed.
        // `stroke` bases its indices on the vertices already present, so appending run
        // after run to the shared buffers just works.
        stroke::stroke(&coords, EXTENT, false, &mut vertices, &mut indices);
        let index_count = indices.len() as u32 - index_offset;
        if index_count == 0 {
            continue;
        }
        ranges.push(RouteSegmentRange { color: segment.color, index_offset, index_count });
    }
    if ranges.is_empty() {
        return None;
    }
    Some(RouteMesh {
        placement: RoutePlacement { origin: min, span, style },
        vertices,
        indices,
        segments: ranges,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::camera::Camera;

    /// The car's route paint: `#1A73E8` over a white casing, at the phone's authored route
    /// width (`maps/.../ui/map/RouteOverlayBuilder.kt`). The old renderer's `12f`/`18f` were
    /// physical px and have no Dp equivalent — see `RouteStyle`'s KDoc on the Kotlin side.
    fn style() -> RouteStyle {
        RouteStyle {
            width_dp: 8.0,
            casing_dp: 2.0,
            casing_color: 0xFFFF_FFFF,
        }
    }

    /// The fill colour the car draws: what a single-run route is painted.
    const FILL: u32 = 0xFF1A_73E8;

    /// Wrap a bare polyline as the one coloured run a single-colour route is.
    fn one(points: &[(f64, f64)]) -> Vec<RouteSegment> {
        vec![RouteSegment { points: points.to_vec(), color: FILL }]
    }

    /// A three-point route across San Francisco.
    fn sf_route() -> Vec<(f64, f64)> {
        vec![(-122.4194, 37.7749), (-122.3894, 37.7949), (-122.3694, 37.7849)]
    }

    fn camera(zoom: f64, bearing: f64) -> Camera {
        Camera {
            center_lon: -122.4194,
            center_lat: 37.7749,
            zoom,
            width_dp: 411.0,
            height_dp: 891.0,
            density: 3.0,
            bearing_deg: bearing,
            pitch_deg: 0.0,
            time_seconds: 0.0,
        }
    }

    /// Where a local `(u, v)` in the route's square lands on screen, in Dp from the
    /// viewport centre — the same arithmetic the vertex shader and the viewport transform
    /// do between them.
    fn screen_dp(mesh: &RouteMesh, camera: &Camera, u: f32, v: f32) -> (f64, f64) {
        let (origin, span) = mesh.placement.at_zoom(camera.zoom);
        let m = camera.world_quad_to_clip(origin, span);
        let x = m[0] * u + m[4] * v + m[12];
        let y = m[1] * u + m[5] * v + m[13];
        (x as f64 * camera.width_dp as f64 / 2.0, y as f64 * camera.height_dp as f64 / 2.0)
    }

    #[test]
    fn a_route_becomes_a_stroked_band() {
        let mesh = tessellate(&one(&sf_route()), style()).expect("three points stroke");
        assert_eq!(
            mesh.vertices.len() / stroke::FLOATS_PER_VERTEX,
            6,
            "two vertices per point, one band",
        );
        assert_eq!(mesh.indices.len(), 12, "two triangles per segment");
        assert!(mesh.vertices.iter().all(|f| f.is_finite()));
        // One run, owning the whole index buffer, in the fill colour.
        assert_eq!(mesh.segments.len(), 1);
        assert_eq!(mesh.segments[0].color, FILL);
        assert_eq!(mesh.segments[0].index_offset, 0);
        assert_eq!(mesh.segments[0].index_count, mesh.indices.len() as u32);
    }

    #[test]
    fn coloured_runs_each_get_their_own_index_slice() {
        // Two runs, two colours: the mesh is one buffer, but each run owns a contiguous,
        // non-overlapping slice of the indices that together cover the whole thing — which
        // is what lets the casing draw the lot once and each fill draw its own slice.
        let segments = vec![
            RouteSegment { points: vec![(-122.42, 37.77), (-122.40, 37.79)], color: 0xFF00_FF00 },
            RouteSegment { points: vec![(-122.40, 37.79), (-122.38, 37.78)], color: 0xFFFF_0000 },
        ];
        let mesh = tessellate(&segments, style()).expect("two runs");
        assert_eq!(mesh.segments.len(), 2);
        assert_eq!(mesh.segments[0].color, 0xFF00_FF00);
        assert_eq!(mesh.segments[1].color, 0xFFFF_0000);
        assert_eq!(mesh.segments[0].index_offset, 0);
        assert_eq!(
            mesh.segments[1].index_offset,
            mesh.segments[0].index_count,
            "the second run starts where the first ends",
        );
        assert_eq!(
            mesh.segments[1].index_offset + mesh.segments[1].index_count,
            mesh.indices.len() as u32,
            "the runs together cover every index",
        );
        assert!(mesh.vertices.iter().all(|f| f.is_finite()));
    }

    #[test]
    fn a_degenerate_run_is_skipped_without_failing_the_route() {
        // One good run and one glitchy single-point run: the good one still draws, and the
        // degenerate one contributes no range rather than blanking the whole route.
        let segments = vec![
            RouteSegment { points: vec![(-122.42, 37.77), (-122.40, 37.79)], color: FILL },
            RouteSegment { points: vec![(-122.40, 37.79)], color: 0xFFFF_0000 },
        ];
        let mesh = tessellate(&segments, style()).expect("the good run draws");
        assert_eq!(mesh.segments.len(), 1);
        assert_eq!(mesh.segments[0].color, FILL);
    }

    #[test]
    fn a_degenerate_route_draws_nothing_rather_than_dividing_by_zero() {
        assert!(tessellate(&[], style()).is_none());
        assert!(tessellate(&one(&[(-122.4, 37.7)]), style()).is_none());
        // Every point in the same place: no bounding box, no direction, no line.
        assert!(tessellate(&one(&[(-122.4, 37.7), (-122.4, 37.7), (-122.4, 37.7)]), style()).is_none());
    }

    #[test]
    fn a_route_running_due_north_still_has_a_square_to_live_in() {
        // Zero width on x, which is what squaring the bounding box exists to survive.
        let mesh = tessellate(&one(&[(-122.4, 37.7), (-122.4, 37.8)]), style()).expect("a meridian");
        assert!(mesh.placement.span > 0.0);
        assert!(mesh.vertices.iter().all(|f| f.is_finite()));
    }

    #[test]
    fn repeated_points_do_not_produce_a_nan() {
        // A router emits coincident points, and a zero-length segment has no direction —
        // a NaN normal takes the whole strip off screen, not just that segment.
        let doubled = vec![
            (-122.4194, 37.7749),
            (-122.4194, 37.7749),
            (-122.3894, 37.7949),
            (-122.3894, 37.7949),
            (-122.3694, 37.7849),
        ];
        let mesh = tessellate(&one(&doubled), style()).expect("a route");
        for (at, value) in mesh.vertices.iter().enumerate() {
            assert!(value.is_finite(), "float {at} is {value}");
        }
    }

    #[test]
    fn the_local_geometry_is_the_same_number_at_every_zoom() {
        // The claim the whole design rests on: Mercator is a pure scale in zoom, so
        // normalising by the bounding box divides that scale out and the mesh never needs
        // rebuilding. If this stops holding, a route in a car starts re-tessellating on
        // every pinch — which is the cost this design exists to avoid.
        let route = sf_route();
        let mesh = tessellate(&one(&route), style()).expect("a route");
        let reference: Vec<(f64, f64)> = route
            .iter()
            .map(|&(lon, lat)| {
                let p = project(lon, lat, 0.0);
                (
                    (p.x - mesh.placement.origin.x) / mesh.placement.span,
                    (p.y - mesh.placement.origin.y) / mesh.placement.span,
                )
            })
            .collect();
        for zoom in [4.0, 10.0, 14.0, 18.0, 22.0] {
            let (origin, span) = mesh.placement.at_zoom(zoom);
            for (at, &(lon, lat)) in route.iter().enumerate() {
                let p = project(lon, lat, zoom);
                let u = (p.x - origin.x) / span;
                let v = (p.y - origin.y) / span;
                let (ru, rv) = reference[at];
                assert!((u - ru).abs() < 1e-12, "z{zoom} point {at}: u {u} vs {ru}");
                assert!((v - rv).abs() < 1e-12, "z{zoom} point {at}: v {v} vs {rv}");
            }
        }
    }

    #[test]
    fn a_routes_points_land_where_the_camera_projects_them() {
        // The overlay is glued to the ground or it is useless, so its square has to place
        // a point exactly where `project` puts it. Checked in Dp from the viewport centre,
        // which is where a drift of even a few pixels would be visible against the road
        // the route is following.
        let route = sf_route();
        let mesh = tessellate(&one(&route), style()).expect("a route");
        for zoom in [8.0, 12.0, 16.0] {
            let camera = camera(zoom, 0.0);
            let centre = project(camera.center_lon, camera.center_lat, zoom);
            for &(lon, lat) in &route {
                let world = project(lon, lat, zoom);
                let (origin, span) = mesh.placement.at_zoom(zoom);
                let u = ((world.x - origin.x) / span) as f32;
                let v = ((world.y - origin.y) / span) as f32;
                let (sx, sy) = screen_dp(&mesh, &camera, u, v);
                let (wx, wy) = (world.x - centre.x, world.y - centre.y);
                assert!((sx - wx).abs() < 0.05, "z{zoom} x {sx} vs {wx}");
                assert!((sy - wy).abs() < 0.05, "z{zoom} y {sy} vs {wy}");
            }
        }
    }

    #[test]
    fn a_route_turns_with_the_camera() {
        // Under a bearing the overlay has to rotate with the basemap, not stay north-up:
        // it goes through the same matrix, so this pins that it really is the same one.
        let route = sf_route();
        let mesh = tessellate(&one(&route), style()).expect("a route");
        let zoom = 14.0;
        let north_up = camera(zoom, 0.0);
        let turned = camera(zoom, 90.0);
        let world = project(route[1].0, route[1].1, zoom);
        let (origin, span) = mesh.placement.at_zoom(zoom);
        let u = ((world.x - origin.x) / span) as f32;
        let v = ((world.y - origin.y) / span) as f32;

        let (nx, ny) = screen_dp(&mesh, &north_up, u, v);
        let (tx, ty) = screen_dp(&mesh, &turned, u, v);
        // A 90-degree bearing maps (x, y) to (y, -x).
        assert!((tx - ny).abs() < 0.05, "{tx} should be the north-up y {ny}");
        assert!((ty - -nx).abs() < 0.05, "{ty} should be minus the north-up x {nx}");
        // And the distance from the centre is unchanged, because a rotation is not a
        // scale.
        assert!(
            ((tx * tx + ty * ty).sqrt() - (nx * nx + ny * ny).sqrt()).abs() < 0.05,
            "the rotation scaled the route",
        );
    }

    #[test]
    fn the_casing_is_wider_than_the_route() {
        let mesh = tessellate(&one(&sf_route()), style()).expect("a route");
        let fill = mesh.placement.fill_half(3.0);
        let casing = mesh.placement.casing_half(3.0).expect("a casing");
        assert!(casing > fill, "{casing} must stand outside {fill}");
        // 8 Dp wide at density 3 is a 12 device-px half-width, plus 2 Dp of casing a side.
        assert!((fill - 12.0).abs() < 1e-6, "{fill}");
        assert!((casing - 18.0).abs() < 1e-6, "{casing}");
    }

    #[test]
    fn no_casing_means_no_casing_pass_at_all() {
        // `casing_dp = 0` is the documented way to switch the outline off. It must give no
        // casing half-width rather than a zero one: `line.vert` floors a band at half a
        // pixel so a hairline still rasterises, so a zero-width casing would poke a pixel
        // of casing colour out from under the route.
        let plain = RouteStyle { casing_dp: 0.0, ..style() };
        let mesh = tessellate(&one(&sf_route()), plain).expect("a route");
        assert!(mesh.placement.casing_half(2.0).is_none());
        assert!(mesh.placement.fill_half(2.0) > 0.0);
    }
}
