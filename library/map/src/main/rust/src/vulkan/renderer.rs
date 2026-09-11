//! The frame: tile residency, and one render pass per frame.

use crate::camera::Camera;
use crate::marker::{Marker, MARKER_SIZE_DP};
use crate::overlay::{RouteMesh, RoutePlacement, RouteSegmentRange};
use crate::style::paint::Stroke;
use crate::style::{Anchor, Layer, LayerKind, Palette};
use crate::tile::geometry::{self, TileMesh};
use crate::tile::select;
use crate::vulkan::buffers::{Buffer, ScratchRing};
use crate::vulkan::cache::ShaderCache;
use crate::vulkan::context::{ANativeWindow, Context};
use crate::vulkan::images::{AtlasSet, SampledImage};
use crate::vulkan::pick::Pick;
use crate::vulkan::pipeline::{Pipelines, Push, MORPH_NONE, NO_MARKINGS};
use crate::vulkan::swapchain::Swapchain;
use ash::vk;
use std::cell::Cell;
use std::cmp::Reverse;
use std::collections::HashMap;
use std::collections::HashSet;
use std::ops::RangeInclusive;
use tilecodec::mamaps::dict::LAYER_JUNCTION;

/// How many frames may be in flight. Two is enough to keep the GPU fed behind vsync
/// without adding latency the user can feel when panning.
const FRAMES_IN_FLIGHT: usize = 2;

/// One layer's geometry, resident on the GPU.
struct LayerBuffers {
    layer_index: usize,
    kind: LayerKind,
    vertices: Buffer,
    indices: Buffer,
    index_count: u32,
    /// Set when the mesh carries its own colour — see
    /// [`geometry::LayerMesh::color_override`].
    color_override: Option<u32>,
    /// The lane inputs this mesh draws with — see [`geometry::LayerMesh::lane`].
    lane: (u8, u8, u8),
}

/// One tile's geometry, resident on the GPU.
struct ResidentTile {
    layers: Vec<LayerBuffers>,
    /// The tile's extruded 3D buildings, or `None` below z14 / where the tile has none. Drawn in
    /// its own depth-tested pass ([`Renderer::record_buildings`]), not the flat layer loop.
    buildings: Option<BuildingBuffers>,
    /// The tile's DEM-displaced ground grid, or `None` where the tile carries no heightmap. Drawn
    /// in its own depth-tested pass ([`Renderer::record_terrain`]) before the flat layer loop; a
    /// tile with no terrain draws its flat `earth` fill in the layer loop as before.
    terrain: Option<TerrainBuffers>,
    /// The region shapes in this tile, for the selection mask. Uploaded with the rest of the
    /// tile so selecting a region costs no tessellation and no allocation.
    regions: Vec<RegionBuffers>,
    /// The live-traffic component segments in this tile, each keyed by its `component_id`.
    /// Uploaded with the rest of the tile; coloured per frame from the pushed table so a new
    /// speed reading never re-uploads or re-tessellates them.
    traffic: Vec<TrafficBuffers>,
    /// The road carriageways in this tile, one per distinct set of road-shape push inputs, plus
    /// the lane connectors through its junctions. Drawn in their own pass
    /// ([`Renderer::record_carriageways`]) through the ribbon pipeline, not the flat layer loop,
    /// because the vertex format and three of the push slots differ. Empty below the carriageway
    /// layer's zoom window.
    carriageways: Vec<CarriagewayBuffers>,
    /// The tile's driving convention paints the line between opposing streams yellow rather than
    /// white. A property of the tile, not of a road, so it rides here and not on each mesh.
    yellow_centre: bool,
    /// Shaped symbol candidates (CPU-side): the renderer emits quads per frame
    /// at the frame's text size. Shaped once on the worker thread.
    labels: Vec<geometry::ShapedLabel>,
    /// Per-lane turn arrows (CPU-side): placed once on the worker thread from the archive's
    /// turn-lane table. The renderer builds their triangles per frame — rotated, scaled to a screen
    /// size and offset into their lane — because all three follow the camera, exactly as the
    /// carriageway's own width does. Empty below the lane zoom gate and on any tile with no
    /// `turn:lanes`.
    arrows: Vec<crate::tile::arrow::ArrowInstance>,
    z: u8,
    x: u32,
    y: u32,
    /// The clock (`Camera::time_seconds`) when this tile's GPU buffers were created, in the
    /// same epoch as `Push.misc.w`. WS-D ramps the tile's `Push.morph.x` opacity from 0 to 1
    /// over [`LOD_FADE_SECONDS`] from this stamp so a finer LOD fades in over its coarse
    /// ancestor instead of popping.
    uploaded_at: f32,
    /// The toggle generation this was tessellated at — see
    /// [`crate::style::SharedToggles`].
    generation: u32,
}

/// One live-traffic component segment, on the GPU.
///
/// The line pipeline's own vertex format, so it draws exactly like a road — the only thing
/// that differs is the colour, which is looked up from [`Renderer::traffic_colors`] by
/// [`id`](Self::id) at draw time rather than coming from a style layer.
struct TrafficBuffers {
    /// The segment's `component_id`, the key into the pushed colour table.
    id: u64,
    vertices: Buffer,
    indices: Buffer,
    index_count: u32,
}

/// One tile's carriageway surface for one set of road-shape inputs, on the GPU.
///
/// The three shape fields are push constants rather than vertex attributes, which is why they key
/// the mesh split: every road in the tile that agrees on all three shares this draw.
struct CarriagewayBuffers {
    /// Index into the style's layer list, for the asphalt colour and the lane width ramp.
    layer_index: usize,
    lanes: u8,
    split: f32,
    oneway: bool,
    vertices: Buffer,
    indices: Buffer,
    index_count: u32,
}

/// One tile's extruded 3D buildings, on the GPU.
///
/// One combined mesh per tile — every building in the tile, walls and roofs — in the 7-float
/// `tess::roof` vertex format, drawn depth-tested through [`Pipelines::building`]. `None` on a tile
/// with no buildings, which is every tile below z14.
struct BuildingBuffers {
    vertices: Buffer,
    indices: Buffer,
    index_count: u32,
}

/// One tile's DEM-displaced ground grid, on the GPU (WS-G, 3D terrain relief).
///
/// One combined mesh per tile in the 6-float `tess::terrain` vertex format (position + height +
/// normal), drawn depth-tested through [`Pipelines::terrain`] before the flat layer loop. `None` on
/// a tile with no heightmap, which then draws its flat `earth` fill instead.
struct TerrainBuffers {
    vertices: Buffer,
    indices: Buffer,
    index_count: u32,
}

/// One region's tessellated shape within one tile, on the GPU.
struct RegionBuffers {
    /// The OSM relation this piece came from, matched against the selected region.
    id: u64,
    vertices: Buffer,
    indices: Buffer,
    index_count: u32,
    /// Exterior rings in tile-local 0..1, kept on the CPU for [`Renderer::region_at`].
    rings: Vec<Vec<(f32, f32)>>,
    /// Total absolute ring area, for preferring the smallest region containing a point.
    area: f32,
    /// The region's OSM `admin_level`, so a lookup can ask for a state rather than whatever
    /// happens to be smallest at that point.
    level: u16,
}

/// A transient per-frame buffer pair (one symbol draw's vertices + indices),
/// held for [`FRAMES_IN_FLIGHT`] frames so no in-flight command buffer still
/// references it at destroy time. Same grace rule as [`Renderer::retiring`],
/// but buffers are not tiles — so they retire in their own queue, with no
/// per-frame `device_wait_idle` stall (that wedged the guest under load).
struct TransientBuffers {
    vbuf: Buffer,
    ibuf: Buffer,
    frames: usize,
}

/// What the symbol placer accepted: candidate id → (it took its alternate anchor, where in
/// acceptance order it landed).
type AcceptSet = HashMap<u64, (bool, u32)>;

/// Everything [`Renderer::place_symbols`] reads that can change what it decides.
///
/// Floats are compared as bit patterns rather than by value. This is an identity test — "is this
/// the same camera the last accept-set was computed from" — and not a question about numeric
/// closeness, so bits are both the correct comparison and the one that needs no epsilon.
#[derive(PartialEq, Eq)]
struct PlacementKey {
    center_lon: u64,
    center_lat: u64,
    zoom: u64,
    bearing: u64,
    pitch: u64,
    width_dp: u32,
    height_dp: u32,
    density: u32,
    extent: (u32, u32),
    filter: crate::style::KindFilter,
    /// Every resident tile with its upload stamp, so a tile that arrives or is replaced re-places
    /// even though the camera has not moved. `camera.time_seconds` is deliberately *not* in this
    /// key: it changes every frame and enters no collision box.
    tiles: Vec<(u64, u32)>,
    /// The style, by layer count. A style or toggle change re-tessellates the resident set, which
    /// restamps every tile above, so this only has to catch the layer set itself changing.
    layers: usize,
}

/// Per-frame synchronisation and its command buffer.
struct Frame {
    command_buffer: vk::CommandBuffer,
    /// Signalled when this frame's commands have finished, so its buffers can be reused.
    in_flight: vk::Fence,
    /// Signalled when the swapchain image is ready to draw into.
    image_available: vk::Semaphore,
    /// Signalled when drawing is done, so presentation can start.
    render_finished: vk::Semaphore,
}

/// The user's own location, as the host last reported it.
///
/// `bearing` is degrees clockwise from north, and `None` when the fix carries no heading
/// — which is what a cold start looks like before the compass has settled. That is a
/// different thing from a heading of zero, and drawing them the same way points the cone
/// spuriously north for the first second of every session.
#[derive(Clone, Copy, Debug)]
pub struct UserPuck {
    pub lon: f64,
    pub lat: f64,
    pub bearing: Option<f32>,
}

/// Something drawn on top of every tile, from the same camera value as the tiles.
///
/// Every variant draws from the shared unit quad and is pure `Copy`/owned state — no vertex
/// buffers with a retirement rule — which is why the route line is deliberately *not* one of
/// these (it lives in [`Renderer::route`] beside [`Renderer::selected_region`]).
///
/// Draw order is fixed in [`record_overlays`](Renderer::record_overlays), not by position in the
/// vec: markers (and WS-F's vehicles) draw first, the puck last, so the user's own location stays
/// on top of the pins around it.
///
/// # The shared sprite/billboard contract (WS-C owns; WS-F extends)
///
/// [`Markers`](Self::Markers) draws app pins as billboarded atlas sprites (see [`crate::marker`]).
/// [`Vehicles`](Self::Vehicles) (WS-F) is a sibling arm for simulated transit vehicles that reuses
/// the exact same [`draw_markers`](Renderer::draw_markers) path and sprite atlas — a vehicle is a
/// [`Marker`] whose icon names a mode sprite (bus/tram/train/ferry) — so the bulk many-sprites case
/// is one extra match arm and one bulk setter, with no new pipeline or atlas. Vehicles are pushed
/// on their own ~1 Hz cadence, replaced as a set by [`set_vehicles`](Renderer::set_vehicles)
/// independently of the app pins, and are deliberately *not* pickable (see
/// [`pick_at`](Renderer::pick_at)) — a moving simulated sprite is not a tap target.
enum Overlay {
    Puck(UserPuck),
    /// App pins: parking, transit stops, search results, saved places, family members. Replaces
    /// the Compose pin overlays so they pan and tilt in lock-step with the basemap.
    Markers(Vec<Marker>),
    /// WS-F simulated transit vehicles: a bus/tram/train/ferry sprite per in-service trip in the
    /// visible bbox, pushed at ~1 Hz. Drawn through the same billboarded sprite path as
    /// [`Markers`](Self::Markers), under the pins and the puck.
    Vehicles(Vec<Marker>),
}

/// The navigation route, resident on the GPU.
///
/// Uploaded once by [`Renderer::set_route`] and never touched again until the route
/// changes: the mesh is zoom-independent by construction (see [`crate::overlay`]), so a
/// frame does nothing but build one matrix and push a casing plus one colour/width pair
/// per coloured run. That is the difference between a route that costs nothing in a
/// two-hour drive and one that re-tessellates on every zoom step.
struct RouteBuffers {
    placement: RoutePlacement,
    vertices: Buffer,
    indices: Buffer,
    index_count: u32,
    /// Each coloured run's slice of [`indices`](Self::indices) and its fill colour. The
    /// casing draws the whole index buffer once; each fill draws one of these slices.
    segments: Vec<RouteSegmentRange>,
}

/// Where `lon`/`lat` falls inside tile `z/x/y`, in tile-local 0..1, or `None` if it is outside.
///
/// Web Mercator, matching the projection the tiler cut the archive with.
fn tile_local(lon: f64, lat: f64, z: u8, x: u32, y: u32) -> Option<(f32, f32)> {
    let n = f64::from(1u32 << z);
    let sin = lat.to_radians().sin().clamp(-0.9999, 0.9999);
    let world_x = (lon + 180.0) / 360.0 * n;
    let world_y = (0.5 - ((1.0 + sin) / (1.0 - sin)).ln() / (4.0 * std::f64::consts::PI)) * n;
    let u = world_x - f64::from(x);
    let v = world_y - f64::from(y);
    (0.0..=1.0).contains(&u).then_some(())?;
    (0.0..=1.0).contains(&v).then_some(())?;
    Some((u as f32, v as f32))
}

/// Even-odd point-in-polygon over a closed ring.
fn contains(ring: &[(f32, f32)], u: f32, v: f32) -> bool {
    let mut inside = false;
    for window in ring.windows(2) {
        let (x0, y0) = window[0];
        let (x1, y1) = window[1];
        if (y0 > v) != (y1 > v) && u < (x1 - x0) * (v - y0) / (y1 - y0) + x0 {
            inside = !inside;
        }
    }
    inside
}

/// How dark the world outside the selected region goes. Alpha, not a colour swap, so the map
/// stays legible underneath — the point is to say "this is the boundary", not to hide the rest.
const SCRIM_COLOR: u32 = 0x8C00_0000;

/// Column-major identity, for an overlay whose vertices are already in clip space.
const IDENTITY: [f32; 16] = [
    1.0, 0.0, 0.0, 0.0, //
    0.0, 1.0, 0.0, 0.0, //
    0.0, 0.0, 1.0, 0.0, //
    0.0, 0.0, 0.0, 1.0,
];

/// The unit quad every screen-anchored overlay draws with: four vertices in −1..1 and the
/// two triangles over them.
///
/// Uploaded once in [`Renderer::new`], because everything that varies about an overlay —
/// where it is, how big, what colour, which way it points — rides in the matrix and the
/// push constants. So the per-frame cost is one `cmd_push_constants` and one
/// `cmd_draw_indexed`, not the pair of `vkAllocateMemory` calls a transient buffer pays.
struct Quad {
    vertices: Buffer,
    indices: Buffer,
}

/// The four corners of the unit square, in the −1..1 the puck shaders read as a local
/// coordinate.
const QUAD_VERTICES: [f32; 8] = [-1.0, -1.0, 1.0, -1.0, 1.0, 1.0, -1.0, 1.0];
const QUAD_INDICES: [u32; 6] = [0, 1, 2, 0, 2, 3];

/// The puck's blue, from the `drawUserIcon` in `maps` this replaces.
const PUCK_COLOR: u32 = 0xFF0E_35F1;
/// The white rim's radius in Dp, and the blue dot's on top of it.
const PUCK_RIM_DP: f32 = 9.5;
const PUCK_DOT_DP: f32 = 8.0;
/// The bearing cone: the radius its stroke is centred on, and half that stroke's width.
const PUCK_CONE_DP: f32 = 20.0;
const PUCK_CONE_HALF_STROKE_DP: f32 = 4.0;
/// The quad's radius. The cone's outer edge is at 24 Dp, but its radial gradient only
/// reaches zero at 28 — the radius the Compose original's `Brush.radialGradient` used —
/// so a quad any tighter would clip the falloff.
const PUCK_QUAD_DP: f32 = 28.0;

/// The turn-arrow glyph: its screen size in Dp (the unit arrow spans roughly `-1..1`, so this is a
/// touch under its half-extent), and its colour. A muted near-white so the arrows read on the dark
/// carriageway without competing with the route line's saturated blue.
const ARROW_DP: f32 = 9.0;
const ARROW_COLOR: u32 = 0xE6EE_F1F5;

/// Stroke width of a live-traffic segment, in Dp.
///
/// A single constant width rather than a style ramp: the overlay is one legible band drawn
/// over the road casing, not a road class that has to grow and shrink with zoom. A shade
/// wider than a minor road so the colour reads as an overlay on top of the network rather
/// than as the road itself. Applied as a per-frame push constant (halved and density-scaled
/// like every other stroke), so it re-tessellates nothing.
const TRAFFIC_WIDTH_DP: f32 = 4.0;

/// The shallowest camera zoom the 3D buildings draw at, matching the `buildings` style layer's
/// `minzoom`. A tile may carry building geometry as a deeper-zoom ancestor stand-in, but the pass
/// is gated on the camera's own zoom so buildings appear only once the map is zoomed in far enough
/// for the extruded detail to read — below it the map is the flat basemap it always was.
const BUILDINGS_DRAW_MIN_ZOOM: f64 = 14.0;

pub struct Renderer {
    context: Context,
    swapchain: Swapchain,
    pipelines: Pipelines,
    /// Sampled-image infra shared by the glyph atlas and the sprite atlas: one
    /// pool/layout, one set per atlas. Uploaded once at startup from the
    /// CPU-built atlas bytes.
    atlas_set: AtlasSet,
    glyph_atlas: Option<SampledImage>,
    glyph_set: Option<vk::DescriptorSet>,
    /// The POI icon sheet, uploaded beside the glyphs. `None` when the sheet would
    /// not decode, which leaves POI labels drawing without icons rather than not at
    /// all.
    sprite_atlas: Option<SampledImage>,
    sprite_set: Option<vk::DescriptorSet>,
    command_pool: vk::CommandPool,
    frames: Vec<Frame>,
    frame_index: usize,
    tiles: HashMap<u64, ResidentTile>,
    /// Retired buffers waiting for the frames that might still reference them.
    retiring: Vec<(usize, ResidentTile)>,
    /// The persistent shader-compilation cache, seeded from disk at startup and shared by every
    /// pipeline and by [`Pick`]. Outlives [`Pipelines`], which is destroyed and rebuilt whenever
    /// the render pass changes — that is the whole point, since the rebuild is what used to
    /// recompile twelve pipelines inside a frame.
    pipeline_cache: ShaderCache,
    /// Transient per-frame symbol buffers, same grace rule as `retiring`.
    transients: Vec<TransientBuffers>,
    /// One scratch bump allocator per frame in flight, which every symbol draw's geometry is
    /// suballocated from. Indexed by [`frame_index`](Self::frame_index) and reset once that
    /// frame's fence has signalled. See [`ScratchRing`].
    scratch: Vec<ScratchRing>,
    window: *mut ANativeWindow,
    pub width: u32,
    pub height: u32,
    /// Set when the swapchain needs rebuilding: a resize, a rotation, or an out-of-date
    /// present.
    needs_rebuild: bool,
    /// Draw calls actually submitted by the last recorded frame.
    ///
    /// Counted where they are issued rather than re-derived, because a layer can be resident
    /// and still not drawn — the authored style ramps a road's width to zero outside the zooms
    /// it is meant for, and [`record`](Self::record) skips it. Any second implementation of
    /// that test would drift out of step with the one that matters and the number would start
    /// lying again, more subtly.
    ///
    /// A `Cell` because `record` takes `&self`; the frame path is single-threaded, as the
    /// module docs of [`crate::bridge`] set out.
    submitted_draws: Cell<usize>,
    /// Task-17 pick state: the last frame's PLACED labels — accept-set id,
    /// screen box in DEVICE px, layer index, display name, kind string, and
    /// anchor lon/lat — so `pick_labels` answers without re-tessellating.
    /// Refreshed by `record_inner` every frame; read by the JNI pick path.
    placed: std::cell::RefCell<Vec<PlacedHit>>,
    /// The last symbol placement and the state it was computed from.
    ///
    /// [`place_symbols`](Self::place_symbols) projects a collision box for every glyph of every
    /// curved label and then runs a solver that is quadratic in accepted boxes, all of it on the
    /// Choreographer callback. None of that depends on the frame clock, so a camera that has not
    /// moved gets last frame's answer instead of the same computation again.
    placement_cache: std::cell::RefCell<Option<(PlacementKey, AcceptSet)>>,
    /// What this frame draws on top of every tile, in order. See [`Overlay`].
    overlays: Vec<Overlay>,
    /// The geometry every overlay shares, uploaded once.
    quad: Quad,
    /// The OSM relation whose shape is punched out of the mask scrim, if any.
    ///
    /// Not an [`Overlay`]: an overlay draws itself over the tiles, while this one is a property
    /// of how every tile is drawn — two pipelines and a stencil rather than one quad.
    selected_region: Option<u64>,
    /// The navigation route line, or `None` when no route is set.
    route: Option<RouteBuffers>,
    /// The live-traffic colour table: `component_id → ARGB`, pushed from the host each update.
    ///
    /// The device owns the theme and palette, so it sends fully-resolved colours; the renderer
    /// only looks them up. A segment whose id is absent draws nothing (see [`record_traffic`]),
    /// which keeps the overlay to the roads traffic actually covers rather than flooding the
    /// whole network with a neutral tint. Replacing this map is the whole of a recolour — no
    /// geometry is touched — so new speeds cost no tessellation.
    ///
    /// [`record_traffic`]: Self::record_traffic
    traffic_colors: HashMap<u64, u32>,
    /// Whether the traffic overlay is drawn this frame. Set from the host's layer toggle; the
    /// geometry is also gated at tessellation, so this is the cheap per-frame guard that stops
    /// resident traffic meshes drawing in the window before a toggle-off re-tessellation lands.
    traffic_enabled: bool,
    /// The offscreen id-buffer pass, for tap picking. Self-contained (its own render pass, pipeline
    /// and target); invoked out of band by [`pick_at`](Self::pick_at), never in the frame loop.
    pick: Pick,
    /// The last camera a frame was recorded with, so [`pick_at`](Self::pick_at) can place markers
    /// against the frame the user is actually looking at. `None` before the first frame.
    last_camera: Option<Camera>,
}

/// One placed label as the pick path sees it: everything `pickLabels` needs
/// to answer without touching tiles, layers, or the camera.
#[derive(Clone)]
pub struct PlacedHit {
    /// Screen box in device px (same box the placer accepted).
    pub rect: (f32, f32, f32, f32),
    /// Index into the style layer list (maps to the flat layer id).
    pub layer_index: usize,
    /// Display name as shaped.
    pub name: String,
    /// Kind string (`country`/`region`/`locality`/…).
    pub kind: String,
    /// The archive's stable id for the feature, or
    /// [`ID_NONE`](tilecodec::mamaps::body::ID_NONE) when it has none. Lets the host rejoin the
    /// hit against its own data without matching on name and position.
    pub feature_id: u64,
    /// Anchor lon/lat in degrees.
    pub lon: f64,
    pub lat: f64,
}

/// The interned `kind`'s name, or empty when it is [`dict::NONE`] or past the table.
///
/// The archive stores kinds as ids into a frozen dictionary; the pick path reports names,
/// because that is what the host filters and switches on.
fn kind_name(kind: u16) -> String {
    use tilecodec::mamaps::dict;
    usize::from(kind)
        .checked_sub(1)
        .and_then(|at| dict::KINDS.get(at))
        .map(|name| (*name).to_string())
        .unwrap_or_default()
}

impl Renderer {
    /// # Safety
    ///
    /// `window` must be an acquired `ANativeWindow`; the renderer releases it on drop.
    pub unsafe fn new(
        window: *mut ANativeWindow,
        width: u32,
        height: u32,
        cache_dir: &std::path::Path,
    ) -> Result<Renderer, String> {
        let context = Context::new(window)?;
        let swapchain = Swapchain::new(&context, width, height)?;
        let atlas_set = AtlasSet::new(&context.device)?;
        // Before any pipeline is created, so the very first launch's twelve compiles are the ones
        // that get recorded. Its own subdirectory of `cache_dir`, not `cache_dir` itself: the tile
        // range cache deletes every *file* in its directory when the archive origin changes
        // (`tile::cache::RangeCache::invalidate_on_origin_change`), so a blob there would be wiped
        // by an unrelated archive republish and read as a random cold start. That deletion is
        // `remove_file`, which does not recurse into a subdirectory.
        let cache_path = cache_dir.join("pipeline");
        let mut pipeline_cache = ShaderCache::open(
            &context.instance,
            context.physical_device,
            &context.device,
            Some(&cache_path),
        );
        let pipelines = Pipelines::new(
            &context.device,
            swapchain.render_pass,
            swapchain.samples,
            Some(atlas_set.layout),
            pipeline_cache.handle(),
        )?;

        let pool_info = vk::CommandPoolCreateInfo::default()
            .queue_family_index(context.queue_family_index)
            // Each frame's buffer is re-recorded every frame, so it must be individually
            // resettable rather than requiring a whole-pool reset.
            .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
        let command_pool = context
            .device
            .create_command_pool(&pool_info, None)
            .map_err(|e| format!("create_command_pool {e:?}"))?;
        // The glyph atlas is a process-global built from the bundled fonts; upload
        // it now so every symbol draw can bind it. Failure is non-fatal: labels
        // simply don't draw until a build with working fonts (see fonts_staged).
        // Logging goes through eprintln: bridge::log needs `__android_log_write`,
        // which links on device but not on the host test binary.
        let (glyph_atlas, glyph_set) = unsafe {
            match try_upload_glyph_atlas(&context, &atlas_set, command_pool) {
                Ok((image, set)) => (Some(image), Some(set)),
                Err(e) => {
                    eprintln!("glyph atlas upload skipped: {e}");
                    (None, None)
                }
            }
        };
        // The POI sprite sheet, on the same terms: a failure costs icons, not the map.
        // It takes the second of the two sets `AtlasSet` sizes its pool for.
        let (sprite_atlas, sprite_set) = unsafe {
            match try_upload_sprite_atlas(&context, &atlas_set, command_pool) {
                Ok((image, set)) => (Some(image), Some(set)),
                Err(e) => {
                    eprintln!("sprite atlas upload skipped: {e}");
                    (None, None)
                }
            }
        };

        let allocate = vk::CommandBufferAllocateInfo::default()
            .command_pool(command_pool)
            .level(vk::CommandBufferLevel::PRIMARY)
            .command_buffer_count(FRAMES_IN_FLIGHT as u32);
        let command_buffers = context
            .device
            .allocate_command_buffers(&allocate)
            .map_err(|e| format!("allocate_command_buffers {e:?}"))?;

        let mut frames = Vec::with_capacity(FRAMES_IN_FLIGHT);
        for &command_buffer in &command_buffers {
            // Created signalled, so the first frame does not wait on a fence nothing has
            // submitted to.
            let fence_info =
                vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED);
            let semaphore_info = vk::SemaphoreCreateInfo::default();
            frames.push(Frame {
                command_buffer,
                in_flight: context
                    .device
                    .create_fence(&fence_info, None)
                    .map_err(|e| format!("create_fence {e:?}"))?,
                image_available: context
                    .device
                    .create_semaphore(&semaphore_info, None)
                    .map_err(|e| format!("create_semaphore {e:?}"))?,
                render_finished: context
                    .device
                    .create_semaphore(&semaphore_info, None)
                    .map_err(|e| format!("create_semaphore {e:?}"))?,
            });
        }

        // Read the extent before the swapchain moves into the struct: the surface may have
        // given us a different size than we asked for.
        let width = swapchain.extent.width;
        let height = swapchain.extent.height;

        // The overlay geometry, uploaded once and never touched again. Unlike the
        // atlases this is not optional: it is 32 bytes of vertices, and a device that
        // cannot allocate that cannot draw a tile either.
        let quad = Quad {
            vertices: Buffer::upload(
                &context.instance,
                context.physical_device,
                &context.device,
                vk::BufferUsageFlags::VERTEX_BUFFER,
                &QUAD_VERTICES,
            )?,
            indices: Buffer::upload(
                &context.instance,
                context.physical_device,
                &context.device,
                vk::BufferUsageFlags::INDEX_BUFFER,
                &QUAD_INDICES,
            )?,
        };

        // The offscreen id-buffer pass for tap picking. Self-contained and format-stable, so it is
        // built once here and survives swapchain rebuilds (only its target is resized).
        let pick = Pick::new(&context, pipeline_cache.handle())?;
        // Everything that compiles a shader has now run once. On a cold cache this is the write
        // that makes every later launch cheap; on a warm one the blob has not grown and this does
        // nothing.
        pipeline_cache.persist(&context.device);

        Ok(Renderer {
            context,
            swapchain,
            pipelines,
            atlas_set,
            glyph_atlas,
            glyph_set,
            sprite_atlas,
            sprite_set,
            command_pool,
            frames,
            frame_index: 0,
            tiles: HashMap::new(),
            retiring: Vec::new(),
            pipeline_cache,
            transients: Vec::new(),
            scratch: (0..FRAMES_IN_FLIGHT).map(|_| ScratchRing::default()).collect(),
            window,
            width,
            height,
            needs_rebuild: false,
            submitted_draws: Cell::new(0),
            placed: std::cell::RefCell::new(Vec::new()),
            placement_cache: std::cell::RefCell::new(None),
            overlays: Vec::new(),
            quad,
            selected_region: None,
            route: None,
            traffic_colors: HashMap::new(),
            traffic_enabled: false,
            pick,
            last_camera: None,
        })
    }

    /// Show the user-location puck, or take it away with `None`.
    ///
    /// Pure state, like [`set_palette`](crate::bridge): a fix arrives at about 1 Hz while
    /// the frame loop runs at 60, so the puck is set out of band and read by whichever
    /// frame happens next, rather than being an argument on [`render`](Self::render).
    pub fn set_user_puck(&mut self, puck: Option<UserPuck>) {
        self.overlays.retain(|overlay| !matches!(overlay, Overlay::Puck(_)));
        if let Some(puck) = puck {
            self.overlays.push(Overlay::Puck(puck));
        }
    }

    /// Replace the app's pins with `markers`, or clear them with an empty slice.
    ///
    /// Pure state like [`set_user_puck`](Self::set_user_puck): the host pushes the whole visible
    /// pin set out of band (from a tap, a search, a family fix), and whichever frame runs next
    /// draws it. Replacing rather than merging, for the same reason [`set_traffic_speeds`] does —
    /// a stale pin left behind would sit under the finger and pick wrong.
    ///
    /// Cheap: the geometry is the shared unit quad billboarded per marker in
    /// [`record_overlays`](Self::record_overlays), so nothing is tessellated or uploaded here.
    /// WS-F's `set_vehicles` is modelled on this exactly.
    ///
    /// [`set_traffic_speeds`]: Self::set_traffic_speeds
    pub fn set_markers(&mut self, markers: Vec<Marker>) {
        self.overlays.retain(|overlay| !matches!(overlay, Overlay::Markers(_)));
        if !markers.is_empty() {
            self.overlays.push(Overlay::Markers(markers));
        }
    }

    /// Replace the simulated transit vehicles with `vehicles`, or clear them with an empty vec.
    ///
    /// Modelled exactly on [`set_markers`](Self::set_markers): the host's 1 Hz ticker recomputes the
    /// in-service vehicles for the visible bbox and pushes the whole set out of band, and whichever
    /// frame runs next draws it. Replacing rather than merging so a trip that has ended, left the
    /// bbox, or been cancelled drops out cleanly rather than lingering at a stale position.
    ///
    /// A separate [`Overlay`] arm from the pins so the two are pushed on their own cadences — the
    /// vehicles churn every second while the pins change only on a tap/search — and so the vehicles
    /// stay out of the marker id-buffer pick (see [`pick_at`](Self::pick_at)).
    ///
    /// Cheap in the same sense as [`set_markers`](Self::set_markers): the geometry is the shared
    /// unit quad billboarded per vehicle in [`record_overlays`](Self::record_overlays), so nothing
    /// is tessellated or uploaded here. Between the 1 Hz recomputes the sprites hold their last
    /// pushed position; the native side folds schedule + realtime delay into each recompute.
    pub fn set_vehicles(&mut self, vehicles: Vec<Marker>) {
        self.overlays.retain(|overlay| !matches!(overlay, Overlay::Vehicles(_)));
        if !vehicles.is_empty() {
            self.overlays.push(Overlay::Vehicles(vehicles));
        }
    }

    /// Draw `mesh` as the navigation route, or take the route away with `None`.
    ///
    /// Pure state like [`set_user_puck`](Self::set_user_puck), and for a stronger reason:
    /// a route arrives once when the driver starts navigating and then does not change
    /// for the rest of the trip, so it has no business being an argument on
    /// [`render`](Self::render).
    ///
    /// The old buffers go through the same frames-in-flight grace queue the transient
    /// symbol buffers use — a command buffer submitted last frame may still be reading
    /// them, and freeing a live vertex buffer is the classic Vulkan use-after-free.
    ///
    /// On upload failure the route is left cleared rather than half-set, so a device that
    /// cannot allocate draws no route instead of a route with no indices.
    pub fn set_route(&mut self, mesh: Option<&RouteMesh>) -> Result<(), String> {
        if let Some(previous) = self.route.take() {
            self.transients.push(TransientBuffers {
                vbuf: previous.vertices,
                ibuf: previous.indices,
                frames: FRAMES_IN_FLIGHT,
            });
        }
        let Some(mesh) = mesh else { return Ok(()) };
        if mesh.indices.is_empty() {
            return Ok(());
        }
        unsafe {
            let vertices = Buffer::upload(
                &self.context.instance,
                self.context.physical_device,
                &self.context.device,
                vk::BufferUsageFlags::VERTEX_BUFFER,
                &mesh.vertices,
            )?;
            let indices = match Buffer::upload(
                &self.context.instance,
                self.context.physical_device,
                &self.context.device,
                vk::BufferUsageFlags::INDEX_BUFFER,
                &mesh.indices,
            ) {
                Ok(buffer) => buffer,
                Err(e) => {
                    vertices.destroy(&self.context.device);
                    return Err(e);
                }
            };
            self.route = Some(RouteBuffers {
                placement: mesh.placement,
                vertices,
                indices,
                index_count: mesh.indices.len() as u32,
                segments: mesh.segments.clone(),
            });
        }
        Ok(())
    }

    /// Dim everything outside one region, or take the mask away with `None`.
    ///
    /// Takes the region's OSM relation id, not a point: a region reaches the archive as one
    /// clipped polygon per tile, and the id is what says those pieces are the same region. Pure
    /// state for the same reason as [`set_user_puck`](Self::set_user_puck) — a selection arrives
    /// from a tap, not from the frame loop.
    pub fn set_region_mask(&mut self, region: Option<u64>) {
        self.selected_region = region;
    }

    /// Replace the live-traffic colour table with a host-pushed `component_id → ARGB` set.
    ///
    /// `ids` and `colors` are parallel: `colors[i]` is the fully-resolved ARGB the device
    /// (which owns the theme and palette) wants drawn for segment `ids[i]`. A mismatched pair
    /// of lengths is truncated to the shorter, so a malformed push degrades to fewer coloured
    /// segments rather than a panic.
    ///
    /// This is the entire cost of a recolour: the map is rebuilt and read at draw, and no
    /// vertex buffer is touched — the geometry was tessellated once and stays. Ids not present
    /// after this call draw nothing (see [`record_traffic`](Self::record_traffic)).
    pub fn set_traffic_speeds(&mut self, ids: &[u64], colors: &[u32]) {
        let n = ids.len().min(colors.len());
        self.traffic_colors.clear();
        self.traffic_colors.reserve(n);
        for (&id, &color) in ids.iter().zip(colors).take(n) {
            self.traffic_colors.insert(id, color);
        }
    }

    /// Drop every pushed traffic colour, so the overlay draws nothing until the next push.
    ///
    /// What the host calls on toggle-off or when the viewport moves off the fetched squares:
    /// it clears the visible overlay in the very next frame without waiting for the
    /// toggle-driven re-tessellation to evict the geometry.
    pub fn clear_traffic(&mut self) {
        self.traffic_colors.clear();
    }

    /// Turn drawing of the traffic overlay on or off for subsequent frames.
    ///
    /// The geometry is gated at tessellation by the same toggle, so this is only the
    /// per-frame guard that stops resident meshes drawing in the brief window between a
    /// toggle-off and the re-tessellation that removes them.
    pub fn set_traffic_enabled(&mut self, enabled: bool) {
        self.traffic_enabled = enabled;
    }

    /// The region whose shape covers this point at the requested administrative level.
    ///
    /// The caller has a place — a tapped city label or a search result — and needs the relation
    /// id of the region it names, which the `places` feature does not carry. Containment is the
    /// link: a city label sits inside its own boundary.
    ///
    /// # Why the level is not optional
    ///
    /// Containment alone answers the wrong question. Every label sits inside a whole stack of
    /// regions — a city inside a county inside a state inside a country — so a point lookup has
    /// to be told which rung of that stack the caller means. Preferring the smallest was the
    /// first attempt and it picks the deepest rung every time: tapping a state's label selects
    /// whichever county the label's anchor happens to land in.
    ///
    /// `levels` is the inclusive band the selection maps to (see `kind_for` in the tiler's
    /// boundary schema, which is what put these numbers in the archive). Within the band the
    /// smallest containing shape still wins, so a city inside a larger city resolves inward.
    ///
    /// Ties are broken by the deeper level and then by the lower id, never by iteration order.
    /// A city and the county it is coterminous with have near-identical areas, and leaving that
    /// to a hash map's ordering makes the same tap pick differently from one frame to the next.
    ///
    /// Falls back to any level when the band matches nothing, because "no mask at all" reads as
    /// the feature being broken. A city mapped at a level this vocabulary calls a county is
    /// still better answered with its own shape than with nothing.
    ///
    /// `None` when no resident tile covers the point, which is the honest answer — the mask would
    /// otherwise punch out whichever larger region happened to be loaded.
    pub fn region_at(&self, lon: f64, lat: f64, levels: RangeInclusive<u16>) -> Option<u64> {
        self.smallest_containing(lon, lat, &levels).or_else(|| self.smallest_containing(lon, lat, &(0..=u16::MAX)))
    }

    fn smallest_containing(&self, lon: f64, lat: f64, levels: &RangeInclusive<u16>) -> Option<u64> {
        let mut best: Option<(f32, u16, u64)> = None;
        for tile in self.tiles.values() {
            let Some((u, v)) = tile_local(lon, lat, tile.z, tile.x, tile.y) else { continue };
            for region in &tile.regions {
                if !levels.contains(&region.level) {
                    continue;
                }
                if !region.rings.iter().any(|ring| contains(ring, u, v)) {
                    continue;
                }
                let candidate = (region.area, region.level, region.id);
                if best.is_none_or(|(area, level, id)| {
                    (candidate.0, Reverse(candidate.1), candidate.2) < (area, Reverse(level), id)
                }) {
                    best = Some(candidate);
                }
            }
        }
        best.map(|(_, _, id)| id)
    }

    pub fn resize(&mut self, width: u32, height: u32) {
        if width == self.width && height == self.height {
            return;
        }
        self.width = width;
        self.height = height;
        self.needs_rebuild = true;
    }

    /// Resident tiles, the geometry they carry, and what the last frame actually submitted.
    ///
    /// For the frame log in [`crate::bridge`]. Guessing at why nothing appears on screen is
    /// far slower than asking the renderer what it actually drew — so `meshes` and `draws` are
    /// reported separately. They differ exactly when the style gates a resident layer out, and
    /// a diagnostic that conflated them would say roads are being drawn while they are not.
    pub fn stats(&self) -> (usize, usize, usize, usize) {
        let tiles = self.tiles.len();
        let meshes: usize = self.tiles.values().map(|t| t.layers.len()).sum();
        let triangles: usize = self
            .tiles
            .values()
            .flat_map(|t| t.layers.iter())
            .map(|l| l.index_count as usize / 3)
            .sum();
        (tiles, meshes, self.submitted_draws.get(), triangles)
    }

    /// The swapchain's current extent, for the frame log.
    pub fn extent(&self) -> (u32, u32) {
        (self.swapchain.extent.width, self.swapchain.extent.height)
    }

    /// Samples per pixel actually in use, for the frame log.
    ///
    /// Worth reporting because it is negotiated with the device rather than chosen: a
    /// driver that offers no multisampled colour attachment silently drops to 1, and
    /// aliased edges on one device but not another is otherwise a hard thing to explain.
    pub fn samples(&self) -> u32 {
        self.swapchain.samples.as_raw()
    }

    /// Is this tile resident **and** tessellated at the current toggle generation?
    ///
    /// A stale tile answers `false`, so the caller re-requests it exactly the way it
    /// requests one it has never seen. That is the whole re-tessellation mechanism: the
    /// old mesh keeps drawing until the new one lands, so a toggle change never blanks
    /// the map, and nothing is evicted or refetched.
    pub fn has_tile(&self, key: u64, generation: u32) -> bool {
        self.tiles.get(&key).is_some_and(|tile| tile.generation == generation)
    }

    /// Upload a tile's geometry, replacing anything already resident for it.
    pub fn upload(&mut self, key: u64, mesh: &TileMesh) -> Result<(), String> {
        let mut layers = Vec::with_capacity(mesh.meshes.len());
        for layer_mesh in &mesh.meshes {
            if layer_mesh.indices.is_empty() {
                continue;
            }
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &layer_mesh.vertices,
                )?;
                let indices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &layer_mesh.indices,
                )?;
                layers.push(LayerBuffers {
                    layer_index: layer_mesh.layer_index,
                    kind: layer_mesh.kind,
                    vertices,
                    indices,
                    index_count: layer_mesh.indices.len() as u32,
                    color_override: layer_mesh.color_override,
                    lane: layer_mesh.lane,
                });
            }
        }
        let mut regions = Vec::with_capacity(mesh.regions.len());
        for region in &mesh.regions {
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &region.vertices,
                )?;
                let indices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &region.indices,
                )?;
                regions.push(RegionBuffers {
                    id: region.id,
                    vertices,
                    indices,
                    index_count: region.indices.len() as u32,
                    rings: region.rings.clone(),
                    area: region.area,
                    level: region.level,
                });
            }
        }
        let mut traffic = Vec::with_capacity(mesh.traffic.len());
        for segment in &mesh.traffic {
            if segment.indices.is_empty() {
                continue;
            }
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &segment.vertices,
                )?;
                let indices = match Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &segment.indices,
                ) {
                    Ok(buffer) => buffer,
                    Err(e) => {
                        vertices.destroy(&self.context.device);
                        return Err(e);
                    }
                };
                traffic.push(TrafficBuffers {
                    id: segment.id,
                    vertices,
                    indices,
                    index_count: segment.indices.len() as u32,
                });
            }
        }
        // The tile's road carriageways: one mesh per distinct road shape, uploaded like the
        // traffic segments above.
        let mut carriageways = Vec::with_capacity(mesh.carriageways.len());
        for road in &mesh.carriageways {
            if road.indices.is_empty() {
                continue;
            }
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &road.vertices,
                )?;
                let indices = match Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &road.indices,
                ) {
                    Ok(buffer) => buffer,
                    Err(e) => {
                        vertices.destroy(&self.context.device);
                        return Err(e);
                    }
                };
                carriageways.push(CarriagewayBuffers {
                    layer_index: road.layer_index,
                    lanes: road.lanes,
                    split: road.split,
                    oneway: road.oneway,
                    vertices,
                    indices,
                    index_count: road.indices.len() as u32,
                });
            }
        }
        // The tile's 3D buildings, if any: one combined mesh, uploaded like the rest.
        let buildings = if mesh.buildings.indices.is_empty() {
            None
        } else {
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &mesh.buildings.vertices,
                )?;
                let indices = match Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &mesh.buildings.indices,
                ) {
                    Ok(buffer) => buffer,
                    Err(e) => {
                        vertices.destroy(&self.context.device);
                        return Err(e);
                    }
                };
                Some(BuildingBuffers {
                    vertices,
                    indices,
                    index_count: mesh.buildings.indices.len() as u32,
                })
            }
        };
        // The tile's terrain grid, if any: one combined mesh, uploaded like the buildings above.
        let terrain = if mesh.terrain.indices.is_empty() {
            None
        } else {
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &mesh.terrain.vertices,
                )?;
                let indices = match Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &mesh.terrain.indices,
                ) {
                    Ok(buffer) => buffer,
                    Err(e) => {
                        vertices.destroy(&self.context.device);
                        return Err(e);
                    }
                };
                Some(TerrainBuffers {
                    vertices,
                    indices,
                    index_count: mesh.terrain.indices.len() as u32,
                })
            }
        };
        let tile = ResidentTile {
            layers,
            buildings,
            terrain,
            regions,
            traffic,
            carriageways,
            yellow_centre: mesh.yellow_centre,
            labels: mesh.labels.clone(),
            arrows: mesh.arrows.clone(),
            z: mesh.z,
            x: mesh.x,
            y: mesh.y,
            // Stamp the tile with the latest frame clock so the fade below measures from the
            // moment its GPU buffers landed. Before the first frame there is no clock yet;
            // 0.0 makes `now - uploaded_at` large, so a startup tile is fully opaque at once
            // (nothing is under it to fade over anyway).
            uploaded_at: self.last_camera.map(|c| c.time_seconds).unwrap_or(0.0),
            generation: mesh.generation,
        };
        if let Some(previous) = self.tiles.insert(key, tile) {
            self.retire(previous);
        }
        Ok(())
    }

    /// Drop resident tiles that are neither wanted nor useful as a stand-in, and enforce the
    /// residency cap.
    ///
    /// `keep` is the visible tiles and their ancestors, in the order [`select::resident_set`]
    /// produced them — coarsest first. `visible` is needed separately because descendants cannot
    /// be enumerated into a keep list without naming tiles that were never fetched; they are
    /// recognised here, against what is actually resident.
    ///
    /// This is also the **only** bound on GPU memory. Tiles live until they fall out of this, so
    /// the cap is not belt-and-braces: without it, retaining descendants would mean every deep
    /// tile visited during a session stays resident for as long as the camera sits above it.
    pub fn retain(&mut self, keep: &[u64], visible: &[select::TileId], cap: usize) {
        let visible_keys: HashSet<u64> = visible.iter().map(|t| t.key()).collect();
        let wanted: HashSet<u64> = keep.iter().copied().collect();

        // Rank by how much is lost if it goes, because the cap has to evict *something* and the
        // stand-ins are what it should reach for first.
        let mut ranked: Vec<(u8, u64)> = self
            .tiles
            .keys()
            .map(|&key| {
                let rank = if visible_keys.contains(&key) {
                    0 // on screen at its own zoom; evicting this is the blank frame itself
                } else if wanted.contains(&key) {
                    1 // an ancestor: one coarse tile covers many fine ones, so cheap to hold
                } else if select::stands_in_for_visible(key, visible, select::DESCENDANT_DEPTH) {
                    2 // a descendant: only covers a fraction of the screen, so the first to go
                } else {
                    3 // unrelated to anything on screen
                };
                (rank, key)
            })
            .collect();
        ranked.sort_unstable();

        let doomed: Vec<u64> = ranked
            .iter()
            .enumerate()
            .filter(|(at, (rank, _))| *rank == 3 || *at >= cap)
            .map(|(_, (_, key))| *key)
            .collect();
        for key in doomed {
            if let Some(tile) = self.tiles.remove(&key) {
                self.retire(tile);
            }
        }
    }

    /// Hold a tile's buffers until every in-flight frame that might reference them has
    /// finished.
    ///
    /// Freeing them immediately is the classic Vulkan use-after-free: a command buffer
    /// submitted last frame can still be executing, and destroying its vertex buffer is
    /// undefined behaviour that usually looks like corrupted geometry rather than a crash.
    fn retire(&mut self, tile: ResidentTile) {
        self.retiring.push((FRAMES_IN_FLIGHT, tile));
    }

    /// Free anything whose grace period has expired.
    fn collect_retired(&mut self) {
        let device = &self.context.device;
        self.retiring.retain_mut(|(remaining, tile)| {
            if *remaining > 0 {
                *remaining -= 1;
                return true;
            }
            unsafe {
                for layer in &tile.layers {
                    layer.vertices.destroy(device);
                    layer.indices.destroy(device);
                }
                for region in &tile.regions {
                    region.vertices.destroy(device);
                    region.indices.destroy(device);
                }
                for segment in &tile.traffic {
                    segment.vertices.destroy(device);
                    segment.indices.destroy(device);
                }
                for road in &tile.carriageways {
                    road.vertices.destroy(device);
                    road.indices.destroy(device);
                }
                if let Some(buildings) = &tile.buildings {
                    buildings.vertices.destroy(device);
                    buildings.indices.destroy(device);
                }
                if let Some(terrain) = &tile.terrain {
                    terrain.vertices.destroy(device);
                    terrain.indices.destroy(device);
                }
            }
            false
        });
        // Transient per-frame symbol buffers retire on the same grace count, in
        // their own queue — no device_wait_idle stall on the frame path.
        self.transients.retain_mut(|t| {
            if t.frames > 0 {
                t.frames -= 1;
                return true;
            }
            unsafe {
                t.vbuf.destroy(device);
                t.ibuf.destroy(device);
            }
            false
        });
    }

    /// Draw one frame.
    ///
    /// Returns `Ok(false)` when the frame was skipped because the swapchain needs
    /// rebuilding, which the caller answers by calling again.
    /// Draw one frame.
    ///
    /// `filter` is the active category filter. It reaches the gate as well as tessellation,
    /// because a chip both narrows which POIs are drawn and pulls its own kinds in earlier than
    /// the ambient map shows them — see [`Layer::draws_at_focused`].
    pub fn render(
        &mut self,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        clear: u32,
        filter: &crate::style::KindFilter,
    ) -> Result<bool, String> {
        self.last_camera = Some(*camera);
        if self.width == 0 || self.height == 0 {
            return Ok(true);
        }
        if self.needs_rebuild {
            self.rebuild()?;
            self.needs_rebuild = false;
        }

        let frame = &self.frames[self.frame_index];
        let device = &self.context.device;
        unsafe {
            device
                .wait_for_fences(std::slice::from_ref(&frame.in_flight), true, u64::MAX)
                .map_err(|e| format!("wait_for_fences {e:?}"))?;
        }
        // Only now is it safe to free what previous frames referenced.
        self.collect_retired();
        // Same fence, same reason: it says this frame slot's previous commands have retired, so
        // nothing is still reading the scratch they drew from.
        unsafe { self.scratch[self.frame_index].reset() };

        let frame = &self.frames[self.frame_index];
        let acquired = unsafe {
            self.swapchain.loader.acquire_next_image(
                self.swapchain.swapchain,
                u64::MAX,
                frame.image_available,
                vk::Fence::null(),
            )
        };
        let image_index = match acquired {
            Ok((index, _suboptimal)) => index,
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => {
                self.needs_rebuild = true;
                return Ok(false);
            }
            Err(e) => return Err(format!("acquire_next_image {e:?}")),
        };

        // Copy frame handles out by value so no borrow of `self.frames` lives
        // across the `&mut self` calls below (`record` uploads transients).
        let (command_buffer, in_flight, image_available, render_finished) = {
            let frame = &self.frames[self.frame_index];
            (
                frame.command_buffer,
                frame.in_flight,
                frame.image_available,
                frame.render_finished,
            )
        };
        unsafe {
            // Clone the device handle (ash::Device is Clone): `record` takes
            // `&mut self` for transient symbol uploads, so no `&self.context`
            // borrow may live across the call.
            let device = self.context.device.clone();
            device
                .reset_fences(std::slice::from_ref(&in_flight))
                .map_err(|e| format!("reset_fences {e:?}"))?;
            self.record(command_buffer, image_index as usize, camera, layers, palette, clear, filter)?;

            let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
            let submit = vk::SubmitInfo::default()
                .wait_semaphores(std::slice::from_ref(&image_available))
                .wait_dst_stage_mask(&wait_stages)
                .command_buffers(std::slice::from_ref(&command_buffer))
                .signal_semaphores(std::slice::from_ref(&render_finished));
            let queue = self.context.queue;
            let swapchain = self.swapchain.swapchain;
            let loader = self.swapchain.loader.clone();
            device
                .queue_submit(queue, std::slice::from_ref(&submit), in_flight)
                .map_err(|e| format!("queue_submit {e:?}"))?;

            let swapchains = [swapchain];
            let indices = [image_index];
            let present = vk::PresentInfoKHR::default()
                .wait_semaphores(std::slice::from_ref(&render_finished))
                .swapchains(&swapchains)
                .image_indices(&indices);
            match loader.queue_present(queue, &present) {
                Ok(false) => {}
                // `VK_SUBOPTIMAL_KHR` is a success code, not an error: the swapchain still
                // presents correctly, it just no longer matches the surface's ideal properties.
                // Rebuilding on it is disproportionate — `rebuild` is a `device_wait_idle`, a
                // swapchain teardown and a recompile of every pipeline, on the Choreographer
                // callback, inside a frame.
                //
                // It is also not self-limiting. Nothing guarantees the rebuild clears the
                // condition, and on Android it routinely does not: a swapchain whose
                // `preTransform` does not match the display's `currentTransform` reports
                // suboptimal on *every* present, so the old code recompiled all eleven pipelines
                // every frame for as long as that held. Note `acquire_next_image` above already
                // discards its own suboptimal flag, so this is now consistent rather than novel.
                //
                // The two cases that genuinely invalidate the swapchain still rebuild:
                // `ERROR_OUT_OF_DATE_KHR` here and at acquire, and `resize` from the host.
                Ok(true) => {}
                Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => self.needs_rebuild = true,
                Err(e) => return Err(format!("queue_present {e:?}")),
            }
        }

        self.frame_index = (self.frame_index + 1) % FRAMES_IN_FLIGHT;
        Ok(true)
    }

    /// Record the frame's single render pass.
    ///
    /// Draw order is **layer-major across tiles**: for each style layer, every resident
    /// tile's geometry for it. Tile-major would let one tile's road casing land on top of
    /// the next tile's road fill, which shows as a seam along every tile boundary.
    ///
    /// `record` takes `&mut self` (not `&self` like before) because symbol layers
    /// upload transient per-frame buffers. The frame path is still single-threaded
    /// per `crate::bridge`'s docs; only one command buffer records at a time.
    unsafe fn record(
        &mut self,
        command_buffer: vk::CommandBuffer,
        image_index: usize,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        clear: u32,
        filter: &crate::style::KindFilter,
    ) -> Result<(), String> {
        // `record_symbol` takes `&mut self` (transient uploads), so `record`
        // issues all fill/line draws through small helpers that re-borrow per
        // call — no `self.` reference lives across a `&mut self` call. The ash
        // `device` is `Copy`-free but its methods take `&self`; copy the few
        // Copy handles needed (pipeline ids, layout) per draw instead.
        let render_pass = self.swapchain.render_pass;
        let framebuffer = self.swapchain.framebuffers[image_index];
        let extent = self.swapchain.extent;
        unsafe {
            self.record_inner(command_buffer, render_pass, framebuffer, extent, camera, layers, palette, clear, filter)
        }
    }

    /// The body of [`record`](Self::record): split out so the borrow structure
    /// reads linearly. All Vulkan calls go through raw handles copied out of
    /// `self` at each step; `record_symbol` is the only `&mut self` callee.
    ///
    /// # Draw order (the contract A/C/D/E/G extend)
    ///
    /// Everything happens in one subpass, so order *is* correctness for the flat layers (they
    /// blend, depth-off) and the depth attachment resolves it for the 3D ones. The sequence is:
    ///
    /// 0. **`record_terrain`** (WS-G) — the DEM-displaced ground, depth-tested, drawn first so it is
    ///    the ground the flat layers sit over. A tile with no heightmap draws nothing here and keeps
    ///    its flat `earth` fill in step 1; at pitch 0 the grid collapses to the flat footprint.
    /// 1. **Basemap layers**, layer-major across tiles, coarsest tile first (fill/line, then
    ///    symbols per layer). WS-D scales each tile draw's alpha through `Push.morph.x`.
    /// 2. **`record_carriageways`** — road surfaces and their lane markings at z16+, over the road
    ///    fills they replace and still under the buildings and the deferred symbols.
    /// 3. **`record_traffic`** — basemap detail, so it dims with the region scrim (WS-B animates
    ///    it via `Push.misc.w`).
    /// 4. **`record_arrows`** — lane turn arrows over the roads.
    /// 5. **`record_region_mask`** — stencil + scrim; dims 1–4, not the route/puck.
    /// 6. **`record_route`** — over the scrim (a followed route must not dim), under the puck.
    /// 7. **`record_overlays`** — markers (app pins; WS-F vehicles) billboarded upright under tilt,
    ///    then the puck on top; last.
    ///
    /// Where a new workstream slots in: **WS-A buildings** and **WS-G terrain** draw with the
    /// depth-enabled pipeline; terrain goes *before* step 1 (it is the ground the flat layers
    /// drape over / sit above) and buildings *after* step 1 at z14+ so they occlude the basemap
    /// by depth. **WS-C markers + id pass** slot beside `record_overlays`. **WS-E curved labels**
    /// ride the symbol path inside step 1. Each adds its own pass/branch; keep this list current
    /// and serialise merges so the passes do not collide.
    ///
    /// # Flat layers over terrain: the drape-vs-offset choice (WS-G)
    ///
    /// The flat 2D layers (roads, water, landuse) **stay at z = 0** and draw depth-off, painting
    /// over the terrain in draw order rather than draping onto it. WS0's `Push` z semantics already
    /// say the vertex z is 0 for every flat 2D layer, and those layers pass `Depth::Off`, so terrain
    /// writes depth for the 3D layers (buildings occlude against it) while the flat layers paint on
    /// top with no z-fighting and need no polygon depth offset. Draping — sampling the same
    /// heightmap for each flat layer's z — would lift roads and water onto the relief but change
    /// every flat layer's vertex format and re-tessellation, so it is deliberately not done here;
    /// under the 60° pitch cap and ~30 m DEM the painted-over approximation reads correctly.
    #[allow(clippy::too_many_arguments)]
    unsafe fn record_inner(
        &mut self,
        command_buffer: vk::CommandBuffer,
        render_pass: vk::RenderPass,
        framebuffer: vk::Framebuffer,
        extent: vk::Extent2D,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        clear: u32,
        filter: &crate::style::KindFilter,
    ) -> Result<(), String> {
        let device = self.context.device.clone();
        device
            .reset_command_buffer(command_buffer, vk::CommandBufferResetFlags::empty())
            .map_err(|e| format!("reset_command_buffer {e:?}"))?;
        let begin = vk::CommandBufferBeginInfo::default()
            .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
        device
            .begin_command_buffer(command_buffer, &begin)
            .map_err(|e| format!("begin_command_buffer {e:?}"))?;

        // One per attachment, in render-pass order, and the layout differs: multisampled is
        // [colour, resolve, depth-stencil] while single-sampled is [colour, depth-stencil]. The
        // depth-stencil is therefore at index 2 or index 1 depending on the device, so both
        // trailing entries carry the same depth+stencil clear — the resolve target is `DONT_CARE`
        // and ignores its entry, and a trailing extra entry is allowed. Depth clears to the far
        // plane (1.0) for the 3D layers; stencil clears to zero, which the scrim reads as
        // "outside the region".
        let depth_stencil_clear =
            vk::ClearValue { depth_stencil: vk::ClearDepthStencilValue { depth: 1.0, stencil: 0 } };
        let clear_values = [
            vk::ClearValue { color: vk::ClearColorValue { float32: argb_to_rgba(clear) } },
            depth_stencil_clear,
            depth_stencil_clear,
        ];
        let pass = vk::RenderPassBeginInfo::default()
            .render_pass(render_pass)
            .framebuffer(framebuffer)
            .render_area(vk::Rect2D { offset: vk::Offset2D { x: 0, y: 0 }, extent })
            .clear_values(&clear_values);
        device.cmd_begin_render_pass(command_buffer, &pass, vk::SubpassContents::INLINE);

        let viewport = vk::Viewport::default()
            .width(extent.width as f32)
            .height(extent.height as f32)
            .min_depth(0.0)
            .max_depth(1.0);
        device.cmd_set_viewport(command_buffer, 0, std::slice::from_ref(&viewport));
        let scissor = vk::Rect2D { offset: vk::Offset2D { x: 0, y: 0 }, extent };
        device.cmd_set_scissor(command_buffer, 0, std::slice::from_ref(&scissor));

        let mut bound: Option<LayerKind> = None;
        let mut submitted = 0usize;
        // One tile-layer's draws, reused across the whole frame.
        //
        // A layer used to have at most one mesh per tile, so the draw could be a single
        // `find`. Transit breaks that: a tile holding two route colours emits two meshes
        // for one layer, and a `find` would silently draw only the first line. Copying the
        // handles out (rather than iterating `self.tiles` in place) is what keeps
        // `record_symbol`'s `&mut self` call legal in the sibling arm below; hoisting the
        // `Vec` out of the loop and clearing it keeps that free of allocation.
        #[allow(clippy::type_complexity)]
        let mut draws: Vec<(
            u8,
            u32,
            u32,
            LayerKind,
            vk::Buffer,
            vk::Buffer,
            u32,
            Option<u32>,
            (u8, u8, u8),
        )> = Vec::new();
        // Coarsest tiles first, so an ancestor standing in for a tile that has not arrived
        // is drawn *under* its descendants and gets covered as they load. A HashMap's
        // iteration order is arbitrary, so without this a stale parent can land on top of
        // the sharp child. Keys (not refs) so `record_symbol` can take `&mut self`.
        let mut ordered: Vec<u64> = self.tiles.keys().copied().collect();
        ordered.sort_by_key(|k| self.tiles.get(k).map(|t| t.z).unwrap_or(0));

        // WS-D LOD cross-fade: one opacity per resident tile for this frame, written into each
        // tile draw's `Push.morph.x` below. A finer tile ramps 0→1 over `LOD_FADE_SECONDS` from
        // its `uploaded_at` stamp *while* a coarse ancestor is resident to stand in under the gap
        // (see `tile_lod_alpha`); a tile with nothing beneath it stays fully opaque, so a freshly
        // fetched area never fades up from the background. Computed once here, not per layer.
        let now = camera.time_seconds;
        let resident: HashSet<u64> = self.tiles.keys().copied().collect();
        let tile_alpha: HashMap<u64, f32> = ordered
            .iter()
            .map(|&key| {
                let uploaded_at = self.tiles.get(&key).map(|t| t.uploaded_at).unwrap_or(0.0);
                (
                    key,
                    select::tile_lod_alpha(
                        key,
                        uploaded_at,
                        now,
                        select::LOD_FADE_SECONDS,
                        &resident,
                    ),
                )
            })
            .collect();

        // Symbol pre-pass: collision runs GLOBALLY across tiles and layers, but
        // draws stay per (tile, layer) below. Build one candidate per shaped
        // label with its screen box at this frame's text size, run the greedy
        // rank-ordered placer once, and hand the accept-set to `record_symbol`.
        // Without this every shaped label draws and z10 is an unreadable pile.
        let accepted = self.place_symbols(camera, layers, &ordered, extent, filter);
        // Task-17 pick snapshot: the accepted labels with their screen boxes,
        // names, kinds and anchor geo — refreshed every frame so pickLabels
        // answers the frame the user sees, not a stale one.
        self.refresh_placed(camera, layers, &accepted, extent);

        // 3D terrain (WS-G): the DEM-displaced ground, drawn first (step 0) with depth on so it is
        // the ground the flat layers below sit over. A tile with no heightmap draws nothing here and
        // keeps its flat `earth` fill in the loop; at pitch 0 the grid collapses to the flat
        // footprint, so the overhead map is unchanged.
        self.record_terrain(command_buffer, camera, layers, palette, &mut submitted);

        // Symbol layers are collected here and drawn after the buildings pass rather than inside
        // the loop. Buildings have no depth interaction with symbols (the symbol pipelines are
        // `Depth::Off`, so they can never *fail* a test) — whichever is issued last simply paints
        // over the other, and issuing buildings last hid every tile-baked POI icon and label
        // behind them. `(key, layer index)`, replayed in the same order the loop met them.
        let mut deferred_symbols: Vec<(u64, usize)> = Vec::new();

        for (index, layer) in layers.iter().enumerate() {
            // `min_zoom`/`max_zoom` are a data-and-cost gate, not paint: they say which zooms
            // the archive is worth asking for this layer at. Paint is the ramp below.
            if !layer.draws_at_focused(camera.zoom.floor().clamp(0.0, 22.0) as u8, layer.focused_by(filter)) {
                continue;
            }
            // Width and opacity come from the flat style, evaluated against the *camera's*
            // fractional zoom rather than the tile's, so a stroke grows and a fill fades
            // smoothly while zooming instead of jumping a step at every level. Both are push
            // constants, so this re-tessellates nothing, and neither varies per tile.
            // Symbols are the exception: label quads are sized per frame (see
            // tile::symbol), so the text size is evaluated here and the mesh lookup
            // below re-tessellates the tile's symbol layers every frame. Cheap —
            // dozens of quads — and placement stays frame-correct.
            let (stroke, opacity) = match layer.kind {
                // The ramp is what makes a fill visible, and it is the *only* thing: gating a
                // fill on an integer zoom drew `landcover` at full strength at z6 where the
                // ramp asks for half, and popped `landuse_park` on at full strength at z7
                // where it asks for a fifth.
                LayerKind::Fill => {
                    let opacity = layer.opacity_at(camera.zoom);
                    if opacity <= 0.0 {
                        continue;
                    }
                    (Stroke::NONE, opacity)
                }
                LayerKind::Line => {
                    let stroke = layer.stroke(camera.zoom);
                    // The ramps reach zero outside the zooms a layer is meant for, and that is
                    // the style's own gate: several road layers carry no `min_zoom` and rely on
                    // it.
                    if !stroke.visible() {
                        continue;
                    }
                    // A line's own opacity ramp (today only rail's 0.5): the authored style
                    // paints some lines translucent, and folding it into the colour column
                    // would bake a constant while the ramp stays per-frame like a fill's.
                    (stroke, layer.opacity_at(camera.zoom))
                }
                LayerKind::Symbol => {
                    if !layer.text_visible_at(camera.zoom) {
                        continue;
                    }
                    (Stroke::NONE, layer.opacity_at(camera.zoom))
                }
            };
            for key in &ordered {
                // Symbol layers emit per frame at the frame's text size from the
                // tile's shaped candidates (see above): deferred to after the buildings
                // pass so labels are not painted over, then drawn with `&mut self` for
                // their transient uploads.
                if layer.kind == LayerKind::Symbol {
                    deferred_symbols.push((*key, index));
                    continue;
                }
                // Copy the draw's inputs out, then issue them through the owned `device`
                // clone: `record_symbol` above takes `&mut self`, so this path must not
                // hold a `self.tiles` borrow either.
                draws.clear();
                if let Some(tile) = self.tiles.get(key) {
                    for mesh in tile.layers.iter().filter(|l| l.layer_index == index) {
                        draws.push((
                            tile.z,
                            tile.x,
                            tile.y,
                            mesh.kind,
                            mesh.vertices.buffer,
                            mesh.indices.buffer,
                            mesh.index_count,
                            mesh.color_override,
                            mesh.lane,
                        ));
                    }
                }
                // This tile's LOD cross-fade opacity for the frame (WS-D), applied through
                // `Push.morph.x`. The fill fragment multiplies output alpha by it; the line path
                // (owned by WS-B's `line.frag`) ignores `morph.x`, so road casings stay crisp
                // while the fill fades — the fade reads as the flat basemap ramping in.
                let tile_fade = tile_alpha.get(key).copied().unwrap_or(1.0);
                for &(tz, tx, ty, kind, vbuf, ibuf, count, color_override, lane) in &draws
                {
                    if bound != Some(kind) {
                        let pipeline = match kind {
                            LayerKind::Fill => self.pipelines.fill,
                            LayerKind::Line => self.pipelines.line,
                            // Symbols never take this path (drawn above); this arm is
                            // unreachable but the match must stay exhaustive.
                            LayerKind::Symbol => continue,
                        };
                        device.cmd_bind_pipeline(
                            command_buffer,
                            vk::PipelineBindPoint::GRAPHICS,
                            pipeline,
                        );
                        bound = Some(kind);
                    }

                    let (half_width_px, half_gap_px) = stroke.half_px(camera.density);
                    // `misc.y` tells the fragment shader whether it has to antialias the edge
                    // itself. With MSAA the rasteriser already resolves partial coverage, and
                    // applying a second coverage term on top fades a diagonal road twice — at
                    // z6 a 2.9px highway crossing the screen at an angle lost all but one
                    // pixel of its strength, while the near-vertical stretches of the same
                    // road kept three.
                    let edge_aa = f32::from(self.swapchain.samples == vk::SampleCountFlags::TYPE_1);
                    // A mesh that carries its own colour keeps it **unshifted**: transit
                    // colours are operator brand colours, and the reference draws them
                    // literally. Every other layer goes through `color(palette)`, which
                    // swaps in the dark column and blends toward the background when muted;
                    // doing that to a route colour would turn a network's own red into
                    // whatever the dark basemap thinks red should be. The opacity ramp still
                    // applies, because that is per-frame paint rather than palette.
                    let base = color_override.unwrap_or_else(|| layer.color(palette));
                    // How far this mesh's whole band shifts sideways, in device pixels. The
                    // feature carries its colour's ordinal and its corridor's colour count,
                    // not an offset: how many lanes the corridor actually draws is a step
                    // function of the camera zoom, so the lane is chosen here rather than
                    // baked into the mesh. Zero for every layer but transit, where the count
                    // is absent and reads one.
                    let (ordinal, count_of_colours, taper) = lane;
                    let lateral_px = layer.lane_offset_px(
                        camera.zoom,
                        camera.density,
                        ordinal,
                        count_of_colours,
                        taper,
                    );
                    let push = Push {
                        tile_to_clip: camera.tile_to_clip(tz, tx, ty),
                        color: argb_to_rgba(scale_alpha(base, opacity)),
                        line: [half_width_px, half_gap_px, layer.dash.0, layer.dash.1],
                        misc: [camera.tile_span_px(tz), edge_aa, lateral_px, camera.time_seconds],
                        morph: [tile_fade, 0.0, 0.0, 0.0],
                    };
                    let layout = self.pipelines.layout;
                    device.cmd_push_constants(
                        command_buffer,
                        layout,
                        vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                        0,
                        push.as_bytes(),
                    );
                    device.cmd_bind_vertex_buffers(command_buffer, 0, &[vbuf], &[0]);
                    // Uint32 rather than Uint16: a dense z14 tile can exceed 65535 vertices in
                    // one layer, and overflowing folds geometry back on itself rather than
                    // failing loudly.
                    device.cmd_bind_index_buffer(command_buffer, ibuf, 0, vk::IndexType::UINT32);
                    device.cmd_draw_indexed(command_buffer, count, 1, 0, 0, 0);
                    submitted += 1;
                }
            }
        }

        // Overlays last, over every tile and inside the same render pass, so they are
        // presented in the same frame and from the same camera value as the basemap under
        // them. Binding the overlay pipeline invalidates `bound`, which is why this comes
        // after the layer loop rather than anywhere inside it.
        //
        // The order between the three is the reading order the driver needs. The region
        // scrim is a property of the basemap, so it goes first and the route is *not*
        // dimmed by it — a route you are following must not fade because a details sheet
        // is open. The route then goes under the puck, because the puck is where you are
        // and it has to stay visible where it sits on top of the line it is following.
        // Traffic sits on the roads it colours, so it draws after the basemap layer loop but
        // before the region scrim — it is basemap detail and should dim with everything else
        // when a region is selected, unlike the route.
        // Road carriageways: over the flat layer loop, because the surface and its markings
        // replace the road fills at this zoom, and under the buildings and deferred symbols
        // below, because a carriageway is flat basemap like every other road layer.
        self.record_carriageways(command_buffer, camera, layers, palette, &ordered, &mut submitted);
        // 3D buildings: after the flat basemap so they paint over it, depth-tested so they occlude
        // one another. Gated to z14+; at pitch 0 the building matrix collapses height to the
        // footprint, so the flat overhead map is unchanged. Before the deferred symbols, so POI
        // icons and labels are not buried behind a tower.
        self.record_buildings(command_buffer, camera, layers, palette, &mut submitted);
        for (key, index) in deferred_symbols {
            self.record_symbol(
                command_buffer,
                key,
                index,
                &layers[index],
                camera,
                palette,
                &accepted,
                &mut submitted,
                &mut bound,
            );
        }
        self.record_traffic(command_buffer, camera, &mut submitted);
        self.record_arrows(command_buffer, camera, layers, &mut submitted);
        self.record_region_mask(command_buffer, camera, &mut submitted);
        self.record_route(command_buffer, camera, &mut submitted);
        self.record_overlays(command_buffer, camera, palette, &mut submitted);

        self.submitted_draws.set(submitted);
        device.cmd_end_render_pass(command_buffer);
        device.end_command_buffer(command_buffer).map_err(|e| format!("end_command_buffer {e:?}"))
    }

    /// Draw the road carriageways: each resident tile's road surfaces, with the lane markings
    /// painted on by `road_surface.frag` as a function of the across-road coordinate rather than
    /// drawn as geometry.
    ///
    /// The lane connectors through junctions draw here too, and are why this loop needs no
    /// connector-specific branch: a connector is the same asphalt on the same pipeline, differing
    /// only in the lane count and one-way flag it pushes, both of which already ride on the mesh.
    /// It belongs in this pass rather than beside it because it is carriageway — it has to sit
    /// under the buildings and the deferred symbols exactly as the roads it joins do, and a second
    /// pass could only get that right by accident.
    ///
    /// Its own pass rather than a branch of the layer loop because the ribbon is a different vertex
    /// format on a different pipeline, and because three of its push slots mean something else —
    /// the `Push` doc comment in [`crate::vulkan::pipeline`] is the contract this fills in.
    ///
    /// Coarsest tile first, in `ordered`, for the same reason the layer loop is: a stale ancestor
    /// standing in for a tile that has not arrived must be drawn *under* its descendants, or its
    /// asphalt lands on top of the sharp child as it loads.
    unsafe fn record_carriageways(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        ordered: &[u64],
        submitted: &mut usize,
    ) {
        let device = &self.context.device;
        let floor = camera.zoom.floor().clamp(0.0, 22.0) as u8;
        let edge_aa = f32::from(self.swapchain.samples == vk::SampleCountFlags::TYPE_1);
        let mut bound = false;
        for key in ordered {
            let Some(tile) = self.tiles.get(key) else { continue };
            if tile.carriageways.is_empty() {
                continue;
            }
            let tile_to_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
            let tile_span_px = camera.tile_span_px(tile.z);
            let yellow = f32::from(tile.yellow_centre);
            for road in &tile.carriageways {
                let Some(layer) = layers.get(road.layer_index) else { continue };
                if !layer.draws_at(floor) {
                    continue;
                }
                // The style ramp is one *lane's* width, so this road's own width is that times
                // the lanes it carries — one number cannot describe both a two-lane street and an
                // eight-lane motorway. Halved because the vertex shader offsets each kerb from
                // the centreline, exactly as `Stroke::half_px` does for a stroke.
                let half_width_px =
                    layer.width.at(camera.zoom) * camera.density * road.lanes as f32 / 2.0;
                if half_width_px <= 0.0 {
                    continue;
                }
                if !bound {
                    device.cmd_bind_pipeline(
                        command_buffer,
                        vk::PipelineBindPoint::GRAPHICS,
                        self.pipelines.ribbon,
                    );
                    bound = true;
                }
                // The asphalt only. The markings are the shader's own palette, because they have
                // to match the white it antialiases them against.
                let asphalt = scale_alpha(layer.color(palette), layer.opacity_at(camera.zoom));
                // A lane connector carries no paint. An intersection is not marked out into
                // lanes on the ground, and a connector is drawn in the carriageway's own colour,
                // so its markings would be the only part of it with any contrast against the road
                // beneath — twelve hairline pairs per junction rather than a widening of the
                // asphalt. `NO_MARKINGS` rides in the centre-line slot, which a one-way leaves
                // dead; see the `Push` doc comment in [`crate::vulkan::pipeline`].
                let centre_t = if layer.source_layer_id == LAYER_JUNCTION {
                    NO_MARKINGS
                } else {
                    road.split
                };
                let push = Push {
                    tile_to_clip,
                    color: argb_to_rgba(asphalt),
                    line: [
                        half_width_px,
                        road.lanes as f32,
                        centre_t,
                        f32::from(road.oneway),
                    ],
                    misc: [tile_span_px, edge_aa, yellow, camera.time_seconds],
                    // The markings are static, so unlike the traffic draw there is no phase to
                    // animate and nothing to fade: `MORPH_NONE` is what the ribbon contract asks
                    // for.
                    morph: MORPH_NONE,
                };
                device.cmd_push_constants(
                    command_buffer,
                    self.pipelines.layout,
                    vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                    0,
                    push.as_bytes(),
                );
                device.cmd_bind_vertex_buffers(command_buffer, 0, &[road.vertices.buffer], &[0]);
                device.cmd_bind_index_buffer(
                    command_buffer,
                    road.indices.buffer,
                    0,
                    vk::IndexType::UINT32,
                );
                device.cmd_draw_indexed(command_buffer, road.index_count, 1, 0, 0, 0);
                *submitted += 1;
            }
        }
    }

    /// Draw the per-lane turn arrows: one glyph per marked lane, bent by the manoeuvre the lane
    /// leads into and pushed sideways onto the lane the carriageway painted.
    ///
    /// Built per frame — rotation, screen size and lane offset all follow the camera, exactly as
    /// the carriageway's width does — and drawn through the **fill** pipeline as plain coloured
    /// triangles, so it needs no glyph atlas and no new pipeline. Gated to the carriageway layer's
    /// zoom floor (z16), so below it nothing is built or drawn.
    unsafe fn record_arrows(
        &mut self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        submitted: &mut usize,
    ) {
        // The carriageway layer, whose zoom window gates the arrows and whose width ramp puts them
        // over the lanes its own markings drew. Resolved through
        // [`crate::style::road_carriageway_layer`], which is careful to pick the *road* layer and
        // not the first layer that happens to carry a spread — see its doc comment.
        let Some(carriageway) = crate::style::road_carriageway_layer(layers) else { return };
        let floor = camera.zoom.floor().clamp(0.0, 22.0) as u8;
        if !carriageway.draws_at(floor) {
            return;
        }
        let density = camera.density;
        // One lane of the carriageway, in device px. The lane count is the arrow's own, so a
        // three-lane approach and a five-lane one are each spread across their whole road.
        let lane_px = carriageway.width.at(camera.zoom) * density;
        // Every glyph tessellates to the same vertex count whatever angle it bends through, so a
        // tile's buffer is sized exactly once up front and never grows.
        let verts_per_arrow = crate::tile::arrow::ARROW_VERTS * 2;
        // Build each tile's triangles first — this borrows `self.tiles` — then upload and draw,
        // which takes `&mut self`. One batch per tile carries its own tile-to-clip matrix.
        let mut batches: Vec<([f32; 16], Vec<f32>)> = Vec::new();
        for tile in self.tiles.values() {
            if tile.arrows.is_empty() {
                continue;
            }
            let span = camera.tile_span_px(tile.z);
            if span <= 0.0 {
                continue;
            }
            let scale = ARROW_DP * density / span; // tile-local 0..1 units per unit-arrow coord
            // What turns the arrows' ground setback into this tile's units. A property of the tile
            // and not of the camera, so the arrows stay the same distance behind the junction at
            // every zoom — see `arrow::tile_local_per_metre`.
            let per_metre = crate::tile::arrow::tile_local_per_metre(tile.z, tile.y);
            let mut verts: Vec<f32> = Vec::with_capacity(tile.arrows.len() * verts_per_arrow);
            for a in &tile.arrows {
                // Sideways onto the lane, perpendicular to the road heading (not the glyph's
                // turn). `lane_centre` measures from the middle of the road out, in lane widths,
                // and already accounts for the half of the carriageway this direction occupies
                // under the tile's driving convention — an arrow that does not sit between the
                // dividers the markings drew is worse than no arrow at all.
                let lateral = crate::tile::arrow::lane_centre(a) * lane_px;
                crate::tile::arrow::arrow_verts(a, scale, lateral / span, per_metre, &mut verts);
            }
            if !verts.is_empty() {
                batches.push((camera.tile_to_clip(tile.z, tile.x, tile.y), verts));
            }
        }
        if batches.is_empty() {
            return;
        }
        let color = argb_to_rgba(ARROW_COLOR);
        for (tile_to_clip, verts) in batches {
            let indices: Vec<u32> = (0..(verts.len() / 2) as u32).collect();
            let push = Push {
                tile_to_clip,
                color,
                line: [0.0; 4],
                misc: [0.0, 0.0, 0.0, camera.time_seconds],
                morph: MORPH_NONE,
            };
            self.draw_fill_batch(command_buffer, &verts, &indices, &push, submitted);
        }
    }

    /// Upload one transient vertex/index pair and draw it through the fill pipeline (colour from
    /// the push constant, no descriptor set). The arrow twin of [`draw_symbol_batch`]; buffers
    /// retire on the frames-in-flight grace count like every other transient.
    unsafe fn draw_fill_batch(
        &mut self,
        command_buffer: vk::CommandBuffer,
        vertices: &[f32],
        indices: &[u32],
        push: &Push,
        submitted: &mut usize,
    ) {
        let device = &self.context.device;
        let Ok(vbuf) = Buffer::upload(
            &self.context.instance,
            self.context.physical_device,
            device,
            vk::BufferUsageFlags::VERTEX_BUFFER,
            vertices,
        ) else {
            return;
        };
        let ibuf = match Buffer::upload(
            &self.context.instance,
            self.context.physical_device,
            device,
            vk::BufferUsageFlags::INDEX_BUFFER,
            indices,
        ) {
            Ok(b) => b,
            Err(_) => {
                vbuf.destroy(device);
                return;
            }
        };
        device.cmd_bind_pipeline(command_buffer, vk::PipelineBindPoint::GRAPHICS, self.pipelines.fill);
        device.cmd_push_constants(
            command_buffer,
            self.pipelines.layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push.as_bytes(),
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[vbuf.buffer], &[0]);
        device.cmd_bind_index_buffer(command_buffer, ibuf.buffer, 0, vk::IndexType::UINT32);
        device.cmd_draw_indexed(command_buffer, indices.len() as u32, 1, 0, 0, 0);
        *submitted += 1;
        self.transients.push(TransientBuffers { vbuf, ibuf, frames: FRAMES_IN_FLIGHT });
    }

    /// Draw the DEM-displaced ground grid: each resident tile's terrain mesh, depth-tested so hills
    /// occlude what is behind them and let buildings on the far side of a ridge be hidden by it.
    /// Drawn before the flat layer loop, so the flat 2D layers paint over it (see the drape-vs-offset
    /// note on [`record_inner`](Self::record_inner)). A tile with no heightmap has no terrain mesh
    /// and draws its flat `earth` fill in the loop instead; at pitch 0 the terrain vertex shader
    /// collapses the grid to the flat footprint, so the overhead map is unchanged.
    ///
    /// The ground colour is the `earth` style layer's, resolved for this palette and its fill
    /// opacity for this zoom — the same colour the flat earth fill would have used — pushed once and
    /// shared by every tile. `line.x` carries the tile's world-px span, the scale that turns the
    /// mesh's tile-normalised heights into the world-px height the WS0 matrix's `z` input expects.
    unsafe fn record_terrain(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        submitted: &mut usize,
    ) {
        let Some(earth) =
            layers.iter().find(|l| l.source_layer_id == tilecodec::mamaps::dict::LAYER_EARTH)
        else {
            return;
        };
        let opacity = earth.opacity_at(camera.zoom);
        if opacity <= 0.0 {
            return;
        }
        let colour = argb_to_rgba(scale_alpha(earth.color(palette), opacity));
        let device = &self.context.device;
        let mut bound = false;
        for tile in self.tiles.values() {
            let Some(terrain) = &tile.terrain else { continue };
            if !bound {
                device.cmd_bind_pipeline(
                    command_buffer,
                    vk::PipelineBindPoint::GRAPHICS,
                    self.pipelines.terrain,
                );
                bound = true;
            }
            let push = Push {
                tile_to_clip: camera.tile_to_clip(tile.z, tile.x, tile.y),
                color: colour,
                // `line.x`: the tile's world-px span, the tile-norm-height -> world-px scale.
                line: [camera.tile_span_dp(tile.z) as f32, 0.0, 0.0, 0.0],
                misc: [0.0, 0.0, 0.0, camera.time_seconds],
                morph: MORPH_NONE,
            };
            device.cmd_push_constants(
                command_buffer,
                self.pipelines.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                push.as_bytes(),
            );
            device.cmd_bind_vertex_buffers(command_buffer, 0, &[terrain.vertices.buffer], &[0]);
            device.cmd_bind_index_buffer(
                command_buffer,
                terrain.indices.buffer,
                0,
                vk::IndexType::UINT32,
            );
            device.cmd_draw_indexed(command_buffer, terrain.index_count, 1, 0, 0, 0);
            *submitted += 1;
        }
    }

    /// Draw the extruded 3D buildings: each resident tile's combined building mesh, depth-tested so
    /// buildings occlude one another and sit above the flat basemap drawn before them. Only at
    /// z14+, where the extruded detail is legible; below that nothing is drawn and the map is the
    /// flat basemap it always was. At pitch 0 the building vertex shader collapses height to the
    /// footprint, so from directly overhead the buildings read as their 2D outline.
    ///
    /// The per-tile push carries the WS0 clip matrix and, in `line.x`, the tile's world-px span for
    /// this frame — the scale that turns the mesh's tile-normalised heights into the world-px height
    /// the matrix's `z` input expects. Colour is per-vertex, so there is no colour push and no
    /// atlas: the pass binds one pipeline and issues one draw per resident tile that has buildings.
    unsafe fn record_buildings(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        submitted: &mut usize,
    ) {
        if camera.zoom.floor() < BUILDINGS_DRAW_MIN_ZOOM {
            return;
        }
        // The palette's building colour, for every wall and roof the archive did not colour
        // itself. Vertex colour is baked at tessellation time, where the palette is not reachable,
        // so the fallback rides in as a push instead and a light/dark switch recolours on the next
        // frame with no re-tessellation. `extrude_building` marks a vertex as wanting it by writing
        // alpha 0, which `building.frag` tests.
        let default_color = layers
            .iter()
            .find(|l| l.source_layer_id == tilecodec::mamaps::dict::LAYER_BUILDINGS)
            .map(|l| argb_to_rgba(l.color(palette)))
            .unwrap_or([0.8, 0.8, 0.8, 1.0]);
        let device = &self.context.device;
        let mut bound = false;
        for tile in self.tiles.values() {
            let Some(buildings) = &tile.buildings else { continue };
            if !bound {
                device.cmd_bind_pipeline(
                    command_buffer,
                    vk::PipelineBindPoint::GRAPHICS,
                    self.pipelines.building,
                );
                bound = true;
            }
            let push = Push {
                tile_to_clip: camera.tile_to_clip(tile.z, tile.x, tile.y),
                // The palette fallback for vertices that carry no archive colour (alpha 0).
                color: default_color,
                // `line.x`: the tile's world-px span, the tile-norm-height -> world-px scale.
                line: [camera.tile_span_dp(tile.z) as f32, 0.0, 0.0, 0.0],
                misc: [0.0, 0.0, 0.0, camera.time_seconds],
                morph: MORPH_NONE,
            };
            device.cmd_push_constants(
                command_buffer,
                self.pipelines.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                push.as_bytes(),
            );
            device.cmd_bind_vertex_buffers(command_buffer, 0, &[buildings.vertices.buffer], &[0]);
            device.cmd_bind_index_buffer(
                command_buffer,
                buildings.indices.buffer,
                0,
                vk::IndexType::UINT32,
            );
            device.cmd_draw_indexed(command_buffer, buildings.index_count, 1, 0, 0, 0);
            *submitted += 1;
        }
    }

    /// Draw the live-traffic overlay: each resident component segment, coloured from the
    /// pushed table.
    ///
    /// The region-mask precedent, applied to lines: the geometry comes from the archive and is
    /// resident, and the only per-frame input is a lightweight dynamic table — here
    /// `component_id → ARGB` rather than one selected region id. A segment whose id is not in
    /// the table draws **nothing**: pushing only the segments the server has a reading for keeps
    /// the overlay to the roads traffic actually covers, rather than laying a neutral tint over
    /// the whole network (which would merely repaint roads the basemap already drew). The
    /// no-data look is therefore "unchanged basemap road", which is the cleaner of the two.
    ///
    /// Allocation-free and re-tessellation-free: colour is a push constant read from the map,
    /// the width is one constant evaluated per frame, and the buffers were uploaded with the
    /// tile. A new speed reading only replaces [`traffic_colors`](Self::traffic_colors), so this
    /// path picks it up on the next frame with no geometry work at all.
    unsafe fn record_traffic(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        submitted: &mut usize,
    ) {
        // Gated on the toggle and empty until the host pushes a reading, so the common case —
        // traffic off, or no data yet — is one comparison and out.
        if !self.traffic_enabled || self.traffic_colors.is_empty() {
            return;
        }
        let device = &self.context.device;
        let half_width_px = TRAFFIC_WIDTH_DP * camera.density / 2.0;
        let edge_aa = f32::from(self.swapchain.samples == vk::SampleCountFlags::TYPE_1);

        let mut bound = false;
        for tile in self.tiles.values() {
            if tile.traffic.is_empty() {
                continue;
            }
            let tile_to_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
            let tile_span_px = camera.tile_span_px(tile.z);
            for segment in &tile.traffic {
                // The dynamic half of the pass: a segment the host has no reading for is not
                // drawn, so the table's size — not the archive's — bounds the draw calls.
                let Some(&argb) = self.traffic_colors.get(&segment.id) else { continue };
                if !bound {
                    device.cmd_bind_pipeline(
                        command_buffer,
                        vk::PipelineBindPoint::GRAPHICS,
                        self.pipelines.line,
                    );
                    bound = true;
                }
                let push = Push {
                    tile_to_clip,
                    color: argb_to_rgba(argb),
                    // A solid band. `line.frag` short-circuits on a zero dash gap before it
                    // touches the clock, so this reads no animation at all.
                    line: [half_width_px, 0.0, 0.0, 0.0],
                    misc: [tile_span_px, edge_aa, 0.0, camera.time_seconds],
                    morph: [1.0, 0.0, 0.0, 0.0],
                };
                device.cmd_push_constants(
                    command_buffer,
                    self.pipelines.layout,
                    vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                    0,
                    push.as_bytes(),
                );
                device.cmd_bind_vertex_buffers(command_buffer, 0, &[segment.vertices.buffer], &[0]);
                device.cmd_bind_index_buffer(
                    command_buffer,
                    segment.indices.buffer,
                    0,
                    vk::IndexType::UINT32,
                );
                device.cmd_draw_indexed(command_buffer, segment.index_count, 1, 0, 0, 0);
                *submitted += 1;
            }
        }
    }

    /// Rasterise the selected region into the stencil, then dim everything it did not cover.
    ///
    /// Two passes over one attachment rather than any geometric cut: the region arrives as one
    /// clipped polygon per tile, and rasterising every piece with `REPLACE` unions them in the
    /// stencil for free. Overlapping pieces and the self-touching rings the tile clipper produces
    /// are both harmless to a rasteriser, which is exactly what defeated the two attempts to do
    /// this with a polygon boolean.
    unsafe fn record_region_mask(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        submitted: &mut usize,
    ) {
        let Some(selected) = self.selected_region else { return };
        let device = &self.context.device;

        let mut any = false;
        for tile in self.tiles.values() {
            for region in tile.regions.iter().filter(|r| r.id == selected) {
                if !any {
                    device.cmd_bind_pipeline(
                        command_buffer,
                        vk::PipelineBindPoint::GRAPHICS,
                        self.pipelines.mask,
                    );
                    any = true;
                }
                let push = Push {
                    tile_to_clip: camera.tile_to_clip(tile.z, tile.x, tile.y),
                    color: [0.0; 4],
                    line: [0.0; 4],
                    misc: [0.0; 4],
                    morph: MORPH_NONE,
                };
                device.cmd_push_constants(
                    command_buffer,
                    self.pipelines.layout,
                    vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                    0,
                    push.as_bytes(),
                );
                device.cmd_bind_vertex_buffers(
                    command_buffer,
                    0,
                    &[region.vertices.buffer],
                    &[0],
                );
                device.cmd_bind_index_buffer(
                    command_buffer,
                    region.indices.buffer,
                    0,
                    vk::IndexType::UINT32,
                );
                device.cmd_draw_indexed(command_buffer, region.index_count, 1, 0, 0, 0);
                *submitted += 1;
            }
        }
        // No piece of the region is resident - the map has been panned away from it, or its
        // tiles have not landed yet. Dimming the whole screen would be worse than dimming none
        // of it, so the scrim is skipped rather than drawn over everything.
        if !any {
            return;
        }

        let push = Push {
            // The quad is already in clip space, so the vertex shader must not move it.
            tile_to_clip: IDENTITY,
            color: argb_to_rgba(SCRIM_COLOR),
            line: [0.0; 4],
            misc: [0.0; 4],
            morph: MORPH_NONE,
        };
        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipelines.scrim,
        );
        device.cmd_push_constants(
            command_buffer,
            self.pipelines.layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push.as_bytes(),
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[self.quad.vertices.buffer], &[0]);
        device.cmd_bind_index_buffer(
            command_buffer,
            self.quad.indices.buffer,
            0,
            vk::IndexType::UINT32,
        );
        device.cmd_draw_indexed(command_buffer, QUAD_INDICES.len() as u32, 1, 0, 0, 0);
        *submitted += 1;
    }

    /// Draw the navigation route, above the basemap and below the puck.
    ///
    /// The casing is one draw of the whole index buffer at the wider half-width in the
    /// casing colour; the fills are then one draw per coloured run, each of its own index
    /// slice in its own colour, over the casing. `stroke` bakes no width into a vertex, so
    /// the same geometry drawn wider underneath *is* the outline — no second mesh — and one
    /// casing over the lot keeps the outline continuous across the colour changes.
    ///
    /// Allocation-free, like every other per-frame path here: the buffers were uploaded
    /// when the route was set, and everything that varies per frame is a matrix and the
    /// push blocks built on the stack. The matrix is the overlay sibling of `tile_to_clip`,
    /// so the route picks up the camera's bearing exactly as the tiles under it do.
    unsafe fn record_route(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        submitted: &mut usize,
    ) {
        let Some(route) = &self.route else { return };
        let device = &self.context.device;
        let (origin, span) = route.placement.at_zoom(camera.zoom);
        let matrix = camera.world_quad_to_clip(origin, span);
        // What `line.vert` divides a pixel offset by to reach local units. The route's
        // square stands in for a tile here, which is the whole reason the two share a
        // vertex format.
        let span_px = (span * camera.density as f64) as f32;
        let edge_aa = f32::from(self.swapchain.samples == vk::SampleCountFlags::TYPE_1);

        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipelines.line,
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[route.vertices.buffer], &[0]);
        device.cmd_bind_index_buffer(
            command_buffer,
            route.indices.buffer,
            0,
            vk::IndexType::UINT32,
        );

        // Casing first (the whole route at the wider width, one colour), then each run's
        // fill over it (its own index slice, its own colour). A first entry with the whole
        // buffer stands in for the casing when there is one. Both are solid: the fills used to
        // carry a scrolling marching-ants, which was removed as unwanted motion. It carried no
        // meaning — `RouteOverlay` has no per-segment dashed flag, so walking, transit and
        // driving legs were all dashed alike and the mode is conveyed by colour.
        let casing = route
            .placement
            .casing_half(camera.density)
            .map(|half| (route.placement.style.casing_color, half, route.index_count, 0u32));
        let fill_half = route.placement.fill_half(camera.density);
        let fills =
            route.segments.iter().map(|s| (s.color, fill_half, s.index_count, s.index_offset));
        for (color, half_width_px, index_count, first_index) in casing.into_iter().chain(fills) {
            let push = Push {
                tile_to_clip: matrix,
                color: argb_to_rgba(color),
                line: [half_width_px, 0.0, 0.0, 0.0],
                misc: [span_px, edge_aa, 0.0, camera.time_seconds],
                morph: [1.0, 0.0, 0.0, 0.0],
            };
            device.cmd_push_constants(
                command_buffer,
                self.pipelines.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                push.as_bytes(),
            );
            device.cmd_draw_indexed(command_buffer, index_count, 1, first_index, 0, 0);
            *submitted += 1;
        }
    }

    /// Draw this frame's overlays on top of every tile.
    ///
    /// Called with the render pass still open: the viewport and scissor are already set
    /// and blending is the same straight src-alpha-over the tile layers use, so an
    /// overlay only has to bind its pipeline and push its own state.
    ///
    /// Draw order is fixed here rather than by vec position: markers (and WS-F's vehicles) first,
    /// the puck last, so the user's location stays on top of the pins around it. `&mut self`
    /// because the marker path uploads a transient buffer through
    /// [`draw_symbol_batch`](Self::draw_symbol_batch), same as the symbol layers.
    unsafe fn record_overlays(
        &mut self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        palette: Palette,
        submitted: &mut usize,
    ) {
        // Copy the overlay state out so no borrow of `self.overlays` lives across the `&mut self`
        // marker draw below (which uploads a transient buffer).
        let mut markers: Vec<Marker> = Vec::new();
        let mut vehicles: Vec<Marker> = Vec::new();
        let mut puck: Option<UserPuck> = None;
        for overlay in &self.overlays {
            match overlay {
                Overlay::Puck(p) => puck = Some(*p),
                Overlay::Markers(m) => markers.extend_from_slice(m),
                Overlay::Vehicles(v) => vehicles.extend_from_slice(v),
            }
        }

        // Vehicles first (lowest), then the app pins over them, then the puck on top: a pin the
        // user placed and can tap outranks a simulated vehicle sprite at the same spot, and the
        // user's own location outranks both. Both go through the shared billboarded sprite path.
        if !vehicles.is_empty() {
            self.draw_markers(command_buffer, camera, palette, &vehicles, submitted);
        }
        if !markers.is_empty() {
            self.draw_markers(command_buffer, camera, palette, &markers, submitted);
        }

        // The puck last, so it sits on top of any pin at the same spot. The shared unit quad, an
        // analytic shader, and one push constant — nothing allocated.
        let Some(puck) = puck else { return };
        let device = &self.context.device;
        let density = camera.density;
        let push = Push {
            tile_to_clip: camera.screen_quad_to_clip(puck.lon, puck.lat, PUCK_QUAD_DP as f64),
            color: argb_to_rgba(PUCK_COLOR),
            line: [
                PUCK_RIM_DP * density,
                PUCK_DOT_DP * density,
                PUCK_CONE_DP * density,
                PUCK_CONE_HALF_STROKE_DP * density,
            ],
            misc: [
                puck.bearing.unwrap_or(0.0).to_radians(),
                f32::from(puck.bearing.is_some()),
                PUCK_QUAD_DP * density,
                camera.time_seconds,
            ],
            morph: MORPH_NONE,
        };
        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipelines.puck,
        );
        device.cmd_push_constants(
            command_buffer,
            self.pipelines.layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push.as_bytes(),
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[self.quad.vertices.buffer], &[0]);
        device.cmd_bind_index_buffer(
            command_buffer,
            self.quad.indices.buffer,
            0,
            vk::IndexType::UINT32,
        );
        device.cmd_draw_indexed(command_buffer, QUAD_INDICES.len() as u32, 1, 0, 0, 0);
        *submitted += 1;
    }

    /// Draw the app's pins as billboarded atlas sprites, batched into one draw.
    ///
    /// The shared sprite/billboard path — WS-F's transit vehicles reuse it verbatim. Each marker
    /// is glued to its `lon`/`lat` and kept upright and screen-constant under tilt by
    /// [`Camera::screen_quad_to_clip`](crate::camera::Camera::screen_quad_to_clip), exactly as the
    /// puck is. Because a marker is *screen-anchored* (a fixed Dp size), its billboard quad is
    /// resolved to clip space on the CPU here — the per-marker perspective `w` is constant across
    /// the quad's four corners, so the divide can be done once — and every marker is emitted into
    /// one shared vertex/index buffer drawn with the identity matrix. That is what makes the bulk
    /// many-sprites case (dozens of vehicles) one upload and one draw rather than one per sprite.
    ///
    /// A marker whose icon the sheet does not carry, or which the tilt puts behind the eye, is
    /// skipped rather than drawn wrong. No `sprite_set` (a sheet that would not decode) draws no
    /// markers, exactly as it draws no POI icons.
    unsafe fn draw_markers(
        &mut self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        palette: Palette,
        markers: &[Marker],
        submitted: &mut usize,
    ) {
        let Some(sprite_set) = self.sprite_set else { return };
        let atlas = crate::tile::sprite::atlas();
        // The sheet is the light half over the dark one; dark mode adds this to every `v`, exactly
        // as `emit_icon` does, so a palette switch stays a per-frame emit rather than a re-upload.
        let dv = if palette.variant == crate::style::Variant::Dark { atlas.dark_v_offset() } else { 0.0 };

        let mut vertices: Vec<f32> = Vec::with_capacity(markers.len() * 4 * 4);
        let mut indices: Vec<u32> = Vec::with_capacity(markers.len() * 6);
        for marker in markers {
            let Some(sprite) = crate::marker::icon_sprite_name(marker.icon).and_then(|n| atlas.get(n))
            else {
                continue;
            };
            // The billboard matrix for this marker (upright + screen-constant under tilt). Its
            // translation column is the quad centre in clip space; columns 0 and 1 are the local
            // Dp axes. All four corners share the same `w` (the matrix's `w` columns for the two
            // in-plane axes are zero on both the ortho and the tilted path), so the perspective
            // divide is one number per marker.
            let m = camera.screen_quad_to_clip(marker.lon, marker.lat, 1.0);
            let w = m[15] as f64;
            if w <= 0.0 {
                continue; // behind the eye / above the horizon under tilt: nothing to draw.
            }
            let cx = m[12] as f64 / w;
            let cy = m[13] as f64 / w;
            // Draw the icon at `MARKER_SIZE_DP` on its larger side, keeping its aspect ratio. With
            // `radius_dp = 1.0` above, a local coordinate is one Dp, so these half-extents are Dp.
            let scale = MARKER_SIZE_DP / sprite.width_dp.max(sprite.height_dp).max(1e-3);
            let hw = (sprite.width_dp * scale * 0.5) as f64;
            let hh = (sprite.height_dp * scale * 0.5) as f64;
            let (xu, yu) = (m[0] as f64, m[1] as f64); // local +u axis, clip space
            let (xv, yv) = (m[4] as f64, m[5] as f64); // local +v axis, clip space
            let uv = sprite.uv;
            let (v0, v1) = (uv.v0 + dv, uv.v1 + dv);
            let base = (vertices.len() / 4) as u32;
            // Corners: (local_u, local_v, tex_u, tex_v). y-down in both clip and atlas, as
            // `emit_icon` documents, so v0 goes with the top edge.
            let corners = [
                (-hw, -hh, uv.u0, v0),
                (hw, -hh, uv.u1, v0),
                (hw, hh, uv.u1, v1),
                (-hw, hh, uv.u0, v1),
            ];
            for (lu, lv, tu, tv) in corners {
                let ox = (lu * xu + lv * xv) / w;
                let oy = (lu * yu + lv * yv) / w;
                vertices.push((cx + ox) as f32);
                vertices.push((cy + oy) as f32);
                vertices.push(tu);
                vertices.push(tv);
            }
            indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
        }
        if indices.is_empty() {
            return;
        }
        // Vertices are already in clip space, so the shader must not transform them: identity. Only
        // `color.a` is read by `sprite.frag` (an icon draws in its own colours), so full alpha.
        let push = Push {
            tile_to_clip: IDENTITY,
            color: [1.0, 1.0, 1.0, 1.0],
            line: [0.0; 4],
            misc: [0.0, 0.0, 0.0, camera.time_seconds],
            morph: MORPH_NONE,
        };
        self.draw_symbol_batch(
            command_buffer,
            self.pipelines.sprite,
            sprite_set,
            &vertices,
            &indices,
            &push,
            submitted,
        );
    }

    /// Draw one tile's one symbol layer: emit its shaped labels at the frame's
    /// text size, upload a transient buffer pair, and draw it with the symbol
    /// pipeline bound to the glyph atlas set.
    ///
    /// Only labels in `accepted` (see [`place_symbols`](Self::place_symbols))
    /// emit; the rest lost their collisions this frame.
    #[allow(clippy::too_many_arguments)]
    unsafe fn record_symbol(
        &mut self,
        command_buffer: vk::CommandBuffer,
        key: u64,
        layer_index: usize,
        layer: &Layer,
        camera: &Camera,
        palette: Palette,
        accepted: &HashMap<u64, (bool, u32)>,
        submitted: &mut usize,
        bound: &mut Option<LayerKind>,
    ) {
        use crate::tile::{placement, symbol};
        let Some(glyph_set) = self.glyph_set else { return };
        // THE density fix (task 1): the size ramp is authored in Dp but the
        // tile span and the shader are device px — without ×density every
        // label renders at 1/density size (≈2px tall cap-height at 17px Dp on
        // a density-3 screen, exactly the reported symptom). Lines already do
        // this (`half_px(density)`); symbols must too.
        //
        // Resolved per label rather than per layer, because the authored size depends on
        // the place's population rank as well as the zoom.
        if !layer.text_visible_at(camera.zoom) {
            return;
        }
        let Some(tile) = self.tiles.get(&key) else { return };
        // The tile's coordinates, copied out so the `self.tiles` borrow below is held only by
        // `tile.labels` and ends at the batch loop — the `&mut self` uploads come after it.
        let (tz, tx, ty) = (tile.z, tile.x, tile.y);
        let tile_span_px = camera.tile_span_px(tz);
        // Copy out what the draw needs before any `&mut self` call below: `tile`
        // borrows `self`, and buffer upload takes `&self.context` while retiring
        // takes `&mut self`.
        let tile_clip = camera.tile_to_clip(tz, tx, ty);
        // Billboarding under tilt (point labels and their icons): the shader projects each corner's
        // ground anchor through `tile_clip` (perspective) and hangs the corner off it at a constant
        // screen offset, reconstructed from the pitch-0 tile matrix's linear 2x2. At pitch 0 the
        // flag is clear and the shader draws straight through `tile_clip`, byte-identical to
        // before. Curved labels carry each vertex as its own anchor, so they stay on the ground
        // regardless — a road name lies along the road, which is the one case where flat is right.
        let flat_clip = Camera { pitch_deg: 0.0, ..*camera }.tile_to_clip(tz, tx, ty);
        let ortho2x2 = [flat_clip[0], flat_clip[1], flat_clip[4], flat_clip[5]];
        let billboard_flag = if camera.pitch_deg != 0.0 { 1.0 } else { 0.0 };
        let (primary, alternate) = anchors_for(layer);
        // Labels counter-rotate about their anchor so they stay upright under a
        // heading-up camera, which is what a driver needs and what keeps the placer's
        // axis-aligned collision boxes describing the box the label actually occupies.
        // `(1, 0)` north-up, where `upright` is a no-op.
        let (cos, sin) = camera.rotation();
        let rotation = (cos as f32, sin as f32);
        // Batched by resolved size, not one batch per tile-layer. A label's size now
        // depends on its population rank, so one draw can hold two of them — and
        // `Push::line.x` carries the text size the fragment shader turns a halo width in
        // px into SDF units with. One value cannot serve both arms, so each gets a draw.
        // The style declares at most two arms, so this is at most two.
        let mut batches: Vec<(f32, Vec<f32>, Vec<u32>)> = Vec::new();
        // Icons take one batch of their own however many sizes the text has: they are a
        // constant screen size, and they sample a different atlas through a different
        // fragment shader, so they could not share a draw with the text regardless.
        let mut icon_vertices: Vec<f32> = Vec::new();
        let mut icon_indices: Vec<u32> = Vec::new();
        // Labels are enumerated in tile-list order — the same order (and the
        // same ids) the pre-pass used — and only accepted ones emit. A tile
        // whose every label collides emits nothing and skips its draw.
        //
        // Borrowed, not cloned. This used to deep-copy the whole label vector — every name
        // `String`, every shaped line, every curved centreline — once per symbol layer per
        // resident tile per frame, purely to release the `self.tiles` borrow before the uploads
        // further down. Nothing in this loop needs `&mut self`, so the borrow simply ends here.
        for (label_idx, label) in
            tile.labels.iter().enumerate().filter(|(_, l)| l.layer_index == layer_index)
        {
            let id = placement::candidate_id(tz, tx, ty, layer_index, label_idx);
            let Some(&(flipped, _)) = accepted.get(&id) else { continue };
            // Draw at whichever anchor the placer actually accepted, or the label lands
            // on the side its box was rejected for.
            let anchor = if flipped { alternate.unwrap_or(primary) } else { primary };
            let text_px = layer.text_size_for(camera.zoom, label.pop) * camera.density;
            if text_px <= 0.0 {
                continue;
            }
            if let Some(sprite) = label.sprite {
                symbol::emit_icon(
                    label,
                    sprite,
                    palette.variant == crate::style::Variant::Dark,
                    camera.density,
                    tile_span_px,
                    rotation,
                    &mut icon_vertices,
                    &mut icon_indices,
                );
            }
            let batch = match batches.iter_mut().find(|(size, _, _)| *size == text_px) {
                Some(batch) => batch,
                None => {
                    batches.push((text_px, Vec::new(), Vec::new()));
                    batches.last_mut().expect("just pushed")
                }
            };
            symbol::emit_label(
                label,
                anchor,
                layer.text_offset,
                text_px,
                tile_span_px,
                rotation,
                &mut batch.1,
                &mut batch.2,
            );
        }
        batches.retain(|(_, _, indices)| !indices.is_empty());
        if batches.is_empty() && icon_indices.is_empty() {
            return;
        }
        let halo = argb_to_rgba(layer.halo_color(palette));
        let color = argb_to_rgba(scale_alpha(layer.color(palette), layer.opacity_at(camera.zoom)));
        let sdf_per_em = crate::tile::glyph::atlas().sdf_per_em;

        // Icons first, so the label's halo paints over the icon's edge rather than under
        // it — the order MapLibre draws them in.
        if let Some(sprite_set) = self.sprite_set.filter(|_| !icon_indices.is_empty()) {
            let push = Push {
                tile_to_clip: tile_clip,
                // Only the alpha is read by `sprite.frag`: an icon draws in its own
                // colours, since the reference sets no `icon-color`.
                color,
                // `sprite.frag` reads none of `line`; `w` is the billboard flag the shared vertex
                // shader reads, so an icon stands up under tilt on the same terms as its label.
                line: [0.0, 0.0, 0.0, billboard_flag],
                misc: [tile_span_px, 0.0, 0.0, 0.0],
                // The pitch-0 linear 2x2, as for the text below — the same matrix, so the icon and
                // the name beside it resolve their screen offsets identically.
                morph: [ortho2x2[0], ortho2x2[1], ortho2x2[2], ortho2x2[3]],
            };
            self.draw_symbol_batch(
                command_buffer,
                self.pipelines.icon,
                sprite_set,
                &icon_vertices,
                &icon_indices,
                &push,
                submitted,
            );
            // The icon pipeline shares `LayerKind::Symbol`, so this still forces the
            // fill/line path to rebind. The symbol pipeline is bound again immediately
            // below whenever there is any text — and a label with an icon always has
            // text, because `shape_label` returns nothing for an empty name.
            *bound = Some(LayerKind::Symbol);
        }

        for (text_px, vertices, indices) in &batches {
            // Halo width from the style (authored text-halo-width, 1px), in device px
            // like the text size beside it; text color + opacity per palette.
            let push = Push {
                tile_to_clip: tile_clip,
                color,
                line: [*text_px, layer.halo_width * camera.density, sdf_per_em, billboard_flag],
                misc: [tile_span_px, halo[0], halo[1], halo[2]],
                // Repurposed for the symbol billboard pipeline: the pitch-0 tile matrix's linear
                // 2x2, so the shader can add a screen-constant glyph offset under tilt. The symbol
                // fragment shader does not read `morph`, so this collides with nothing.
                morph: [ortho2x2[0], ortho2x2[1], ortho2x2[2], ortho2x2[3]],
            };
            self.draw_symbol_batch(
                command_buffer,
                self.pipelines.symbol,
                glyph_set,
                vertices,
                indices,
                &push,
                submitted,
            );
            *bound = Some(LayerKind::Symbol);
        }
    }

    /// Suballocate one vertex/index pair out of this frame's scratch ring, bind `pipeline` with
    /// `set`, and draw it.
    ///
    /// The icon and text paths differ only in which pipeline and atlas they bind, so they
    /// share this. Geometry goes into [`Renderer::scratch`] rather than into a buffer of its own:
    /// a screenful of labels is several hundred of these a frame, and a `vkAllocateMemory` per
    /// draw is both slow and bounded by `maxMemoryAllocationCount`. The ring is reset at the top
    /// of the frame under the in-flight fence, which is what makes the memory safe to reuse.
    #[allow(clippy::too_many_arguments)]
    unsafe fn draw_symbol_batch(
        &mut self,
        command_buffer: vk::CommandBuffer,
        pipeline: vk::Pipeline,
        set: vk::DescriptorSet,
        vertices: &[f32],
        indices: &[u32],
        push: &Push,
        submitted: &mut usize,
    ) {
        let frame_index = self.frame_index;
        let device = &self.context.device;
        // Disjoint field borrows, deliberately not `self.context.device.clone()` as the frame path
        // does once per frame: `ash::Device` clones its whole function-pointer table, and this
        // runs a few hundred times a frame.
        let Some((vbuf, voffset)) = self.scratch[frame_index].push(
            &self.context.instance,
            self.context.physical_device,
            device,
            vertices,
        ) else {
            return;
        };
        let Some((ibuf, ioffset)) = self.scratch[frame_index].push(
            &self.context.instance,
            self.context.physical_device,
            device,
            indices,
        ) else {
            return;
        };
        device.cmd_bind_pipeline(command_buffer, vk::PipelineBindPoint::GRAPHICS, pipeline);
        device.cmd_bind_descriptor_sets(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipelines.symbol_layout,
            0,
            &[set],
            &[],
        );
        device.cmd_push_constants(
            command_buffer,
            self.pipelines.symbol_layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push.as_bytes(),
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[vbuf], &[voffset]);
        device.cmd_bind_index_buffer(command_buffer, ibuf, ioffset, vk::IndexType::UINT32);
        device.cmd_draw_indexed(command_buffer, indices.len() as u32, 1, 0, 0, 0);
        *submitted += 1;
    }

    /// The per-frame symbol pre-pass: one collision candidate per shaped label
    /// of every drawing symbol layer, placed once, globally.
    ///
    /// Collision padding widens with zoom-out (see
    /// [`collision_padding_px`]): at low zoom boxes overlap eagerly so only
    /// the most important places survive, matching MapLibre's ~10 cities at
    /// z6; at street zoom padding relaxes toward the drawn box.
    fn place_symbols(
        &self,
        camera: &Camera,
        layers: &[Layer],
        ordered: &[u64],
        extent: vk::Extent2D,
        filter: &crate::style::KindFilter,
    ) -> AcceptSet {
        use crate::tile::placement;
        let key = PlacementKey {
            center_lon: camera.center_lon.to_bits(),
            center_lat: camera.center_lat.to_bits(),
            zoom: camera.zoom.to_bits(),
            bearing: camera.bearing_deg.to_bits(),
            pitch: camera.pitch_deg.to_bits(),
            width_dp: camera.width_dp.to_bits(),
            height_dp: camera.height_dp.to_bits(),
            density: camera.density.to_bits(),
            extent: (extent.width, extent.height),
            filter: filter.clone(),
            tiles: ordered
                .iter()
                .map(|key| {
                    (*key, self.tiles.get(key).map(|t| t.uploaded_at).unwrap_or(0.0).to_bits())
                })
                .collect(),
            layers: layers.len(),
        };
        if let Some((cached, accepted)) = self.placement_cache.borrow().as_ref() {
            if *cached == key {
                return accepted.clone();
            }
        }
        let mut candidates: Vec<placement::SegmentedCandidate> = Vec::new();
        for (index, layer) in layers.iter().enumerate() {
            if layer.kind != LayerKind::Symbol {
                continue;
            }
            if !layer.draws_at_focused(camera.zoom.floor().clamp(0.0, 22.0) as u8, layer.focused_by(filter)) {
                continue;
            }
            // Device px, matching `record_symbol`: `extent` below is device px, so a
            // Dp text size here would size every collision box at 1/density and let
            // labels that visibly overlap all survive the placer. Resolved per label,
            // because the size depends on the place's population rank.
            if !layer.text_visible_at(camera.zoom) {
                continue;
            }
            let (primary, alternate) = anchors_for(layer);
            for key in ordered {
                let Some(tile) = self.tiles.get(key) else { continue };
                let tile_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
                let tile_span_px = camera.tile_span_px(tile.z);
                let wh = (extent.width, extent.height);
                // Task-9 rank gating: at low UI zoom only high-pop localities
                // Rank gating BEFORE collision: a hamlet must not
                // become a candidate at all - collision alone can't thin
                // hundreds of towns to the major-city set. The camera zoom is
                // the UI zoom now that the tile grid is 512, so selection
                // matches MapLibre's per-zoom set directly.
                let min_pop = placement::locality_min_pop(camera.zoom);
                for (label_idx, label) in tile
                    .labels
                    .iter()
                    .enumerate()
                    .filter(|(_, l)| l.layer_index == index)
                {
                    if label.rank == 2 && label.pop < min_pop {
                        continue;
                    }
                    let inputs = box_inputs(layer, label, camera);
                    let id = placement::candidate_id(tile.z, tile.x, tile.y, index, label_idx);
                    // A curved label collides as the row of oriented per-glyph boxes it draws; a
                    // point label as one axis-aligned box (plus its variable-anchor alternate). Both
                    // go into one `place_segmented` pass so they collide with each other.
                    let (boxes, alternate_boxes) = if let Some(centreline) = &label.centreline {
                        let Some(line) = label.lines.first() else { continue };
                        let ppfu =
                            inputs.text_px / crate::tile::glyph::UP_EM as f32 / tile_span_px;
                        let placed = crate::tess::text::layout_along_line(line, centreline, ppfu);
                        let boxes =
                            placement::curved_boxes(&placed, tile_clip, wh, inputs.text_px, inputs.pad_px);
                        if boxes.is_empty() {
                            continue; // does not fit its line this frame: nothing to place.
                        }
                        (boxes, None)
                    } else {
                        let primary_box = placement::Obb::from_rect(placement::anchored_rect(
                            label.anchor,
                            tile_clip,
                            wh,
                            &inputs,
                            primary,
                        ));
                        let alt = alternate.map(|second| {
                            vec![placement::Obb::from_rect(placement::anchored_rect(
                                label.anchor,
                                tile_clip,
                                wh,
                                &inputs,
                                second,
                            ))]
                        });
                        (vec![primary_box], alt)
                    };
                    candidates.push(placement::SegmentedCandidate {
                        id,
                        rank: label.rank,
                        pop: label.pop,
                        boxes,
                        alternate: alternate_boxes,
                    });
                }
            }
        }
        // Keyed by candidate id, valued by the anchor the placer settled on and **where in
        // acceptance order it landed**. `place_segmented` returns its winners in priority order and
        // a `HashMap` would throw that away, which is what made `pick_labels`' "topmost first" a
        // claim rather than a fact.
        let accepted: AcceptSet = placement::place_segmented(&candidates)
            .into_iter()
            .enumerate()
            .map(|(order, (id, flipped))| (id, (flipped, order as u32)))
            .collect();
        *self.placement_cache.borrow_mut() = Some((key, accepted.clone()));
        accepted
    }

    /// Task-17 pick: the placed labels of the last frame whose screen boxes
    /// contain the query box (device px), in placement order (topmost first).
    /// A linear scan — hundreds of labels, no index needed. Called from the
    /// JNI pick path with Dp already converted to device px by the caller.
    pub fn pick_labels(&self, query: (f32, f32, f32, f32)) -> Vec<PlacedHit> {
        let (qx0, qy0, qx1, qy1) = query;
        self.placed
            .borrow()
            .iter()
            .filter(|h| h.rect.0 <= qx1 && h.rect.2 >= qx0 && h.rect.1 <= qy1 && h.rect.3 >= qy0)
            .cloned()
            .collect()
    }

    /// The marker id under the device-pixel `(x, y)`, or `0` when the tap hit no marker.
    ///
    /// The GPU-picking counterpart of [`pick_labels`](Self::pick_labels): where labels are picked
    /// against the CPU snapshot of the last placed frame, markers are picked by rendering their
    /// ids into an offscreen `R32_UINT` buffer with the last frame's camera and reading back the
    /// tapped pixel (see [`crate::vulkan::pick`]). That is what keeps a pin tappable under tilt,
    /// where its screen box is no longer a plain projection of its lon/lat.
    ///
    /// Returns the marker's own id (the value the host set on it), so the host maps the tap back to
    /// its feature without matching on position. `0` covers "no marker here", "no frame drawn yet",
    /// and a pick that could not run — all of which the host treats as "fall through to the next
    /// probe", exactly as an empty [`pick_labels`](Self::pick_labels) result is treated.
    pub fn pick_at(&mut self, x: u32, y: u32) -> u64 {
        let Some(camera) = self.last_camera else { return 0 };
        // The markers this frame would draw, in draw order, so the topmost pin wins the pixel.
        let markers: Vec<Marker> = self
            .overlays
            .iter()
            .filter_map(|overlay| match overlay {
                Overlay::Markers(m) => Some(m.iter().copied()),
                _ => None,
            })
            .flatten()
            .collect();
        if markers.is_empty() {
            return 0;
        }
        let extent = self.swapchain.extent;
        let quad_indices = QUAD_INDICES.len() as u32;
        match unsafe {
            self.pick.at(
                &self.context,
                self.command_pool,
                self.quad.vertices.buffer,
                self.quad.indices.buffer,
                quad_indices,
                extent,
                &camera,
                &markers,
                x,
                y,
            )
        } {
            Ok(id) => id,
            Err(e) => {
                eprintln!("pick_at failed: {e}");
                0
            }
        }
    }

    /// Task-17 pick snapshot: rebuild [`placed`](Self::placed) from the
    /// frame's accept-set. Boxes are recomputed with the same inputs the
    /// pre-pass used (same text size, same padding) so pick boxes match drawn
    /// boxes; anchor lon/lat comes from unprojecting the tile-local anchor
    /// through the tile's world position at the camera zoom.
    fn refresh_placed(
        &self,
        camera: &Camera,
        layers: &[Layer],
        accepted: &HashMap<u64, (bool, u32)>,
        extent: vk::Extent2D,
    ) {
        use crate::tile::placement;
        let mut placed: Vec<(u32, PlacedHit)> = Vec::new();
        for tile in self.tiles.values() {
            // Tile origin in world px at the camera zoom: tile (x,y) at ITS
            // OWN zoom would misplace overzoomed ancestors, but every
            // resident tile here is at the selected zoom (see select), so
            // tile_span at tile.z is the tile's own screen size and the
            // anchor offsets within it directly.
            let span_dp = camera.tile_span_dp(tile.z);
            let origin = camera.viewport_origin();
            let tile_wx = tile.x as f64 * span_dp;
            let tile_wy = tile.y as f64 * span_dp;
            for (label_idx, label) in tile.labels.iter().enumerate() {
                let id = placement::candidate_id(
                    tile.z,
                    tile.x,
                    tile.y,
                    label.layer_index,
                    label_idx,
                );
                let Some(&(flipped, order)) = accepted.get(&id) else { continue };
                let Some(layer) = layers.get(label.layer_index) else { continue };
                // Device px, as in `place_symbols` — this rebuilds the same boxes for
                // the pick path, so it has to agree with them, including which anchor
                // the placer settled on.
                let text_px = layer.text_size_for(camera.zoom, label.pop) * camera.density;
                if text_px <= 0.0 {
                    continue;
                }
                let (primary, alternate) = anchors_for(layer);
                let anchor = if flipped { alternate.unwrap_or(primary) } else { primary };
                let tile_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
                let rect = placement::anchored_rect(
                    label.anchor,
                    tile_clip,
                    (extent.width, extent.height),
                    &box_inputs(layer, label, camera),
                    anchor,
                );
                // Anchor tile-local → world px (Dp) → lon/lat.
                let wx = tile_wx + label.anchor.0 as f64 * span_dp;
                let wy = tile_wy + label.anchor.1 as f64 * span_dp;
                let (lon, lat) = crate::camera::unproject(wx, wy, camera.zoom);
                let _ = origin;
                placed.push((
                    order,
                    PlacedHit {
                        rect,
                        layer_index: label.layer_index,
                        name: label.name.clone(),
                        // The feature's own kind, not the layer's first whitelist entry — that
                        // reported `restaurant` for every `poi-food` hit, `stadium` for every
                        // civic one, and so on for all four multi-kind layers.
                        kind: kind_name(label.kind),
                        feature_id: label.feature_id,
                        lon,
                        lat,
                    },
                ));
            }
        }
        // `self.tiles` is a `HashMap`, so the walk above visits tiles in arbitrary order. Sorted
        // back into the placer's acceptance order here, which is what makes "topmost first" true
        // of what `pick_labels` returns — and therefore what makes a tap resolve to the label
        // actually drawn on top rather than to whichever tile the hasher happened to yield first.
        placed.sort_by_key(|(order, _)| *order);
        *self.placed.borrow_mut() = placed.into_iter().map(|(_, hit)| hit).collect();
    }

    /// Rebuild the swapchain after a resize, rotation or out-of-date present.
    fn rebuild(&mut self) -> Result<(), String> {
        unsafe {
            let _ = self.context.device.device_wait_idle();
            // The pipelines reference the old render pass, so they go with it. This is the
            // expensive part and the reason `pipeline_cache` exists: without it every one of the
            // twelve is recompiled from SPIR-V here, on the Choreographer callback, inside a
            // frame.
            self.pipelines.destroy(&self.context.device);
            self.swapchain.destroy(&self.context.device);
            self.swapchain = Swapchain::new(&self.context, self.width, self.height)?;
            self.pipelines = Pipelines::new(
                &self.context.device,
                self.swapchain.render_pass,
                self.swapchain.samples,
                Some(self.atlas_set.layout),
                self.pipeline_cache.handle(),
            )?;
            // Only writes if the driver actually added something — a rebuild to the same sample
            // count adds nothing, so the steady state costs no I/O on the frame path.
            self.pipeline_cache.persist(&self.context.device);
        }
        self.width = self.swapchain.extent.width;
        self.height = self.swapchain.extent.height;
        Ok(())
    }
}

/// Upload the process-global glyph atlas and allocate its descriptor set.
///
/// Separate from [`Renderer::new`] so the error paths read linearly. Called once
/// at startup; the bytes come from `tile::glyph::atlas()` (SDF R8 built from the
/// bundled Noto Sans at first use).
///
/// # Safety
///
/// Same rules as the surrounding constructors: live device, idle queue.
unsafe fn try_upload_glyph_atlas(
    context: &Context,
    atlas_set: &AtlasSet,
    command_pool: vk::CommandPool,
) -> Result<(SampledImage, vk::DescriptorSet), String> {
    use crate::tile::glyph;
    if !glyph::fonts_staged() {
        return Err("bundled fonts are not valid TTFs".into());
    }
    let atlas = glyph::atlas();
    let image = SampledImage::upload(
        &context.instance,
        context.physical_device,
        &context.device,
        context.queue,
        context.queue_family_index,
        command_pool,
        &atlas.pixels,
        glyph::ATLAS_PX,
        glyph::ATLAS_PX,
        vk::Format::R8_UNORM,
    )?;
    let set = atlas_set.allocate(&context.device, &image)?;
    Ok((image, set))
}

/// Upload the process-global POI sprite sheet and allocate its descriptor set.
///
/// [`try_upload_glyph_atlas`]'s counterpart, and the second of the two sets
/// [`AtlasSet`]'s pool is sized for. The sheet is already RGBA8, so
/// [`SampledImage::upload`] takes it unchanged — no expansion step like the glyph
/// atlas's R8 one.
///
/// # Safety
///
/// Same rules as the surrounding constructors: live device, idle queue.
unsafe fn try_upload_sprite_atlas(
    context: &Context,
    atlas_set: &AtlasSet,
    command_pool: vk::CommandPool,
) -> Result<(SampledImage, vk::DescriptorSet), String> {
    use crate::tile::sprite;
    let atlas = sprite::atlas();
    // An empty atlas is what `sprite::atlas()` leaves behind when the sheet will not
    // decode; uploading a zero-sized image would fail in the driver instead of here.
    if atlas.is_empty() {
        return Err("the sprite sheet carries no icons".into());
    }
    let image = SampledImage::upload(
        &context.instance,
        context.physical_device,
        &context.device,
        context.queue,
        context.queue_family_index,
        command_pool,
        &atlas.pixels,
        atlas.width,
        atlas.height,
        vk::Format::R8G8B8A8_UNORM,
    )?;
    let set = atlas_set.allocate(&context.device, &image)?;
    Ok((image, set))
}

impl Drop for Renderer {
    fn drop(&mut self) {
        unsafe {
            let _ = self.context.device.device_wait_idle();
            for tile in self.tiles.values() {
                for layer in &tile.layers {
                    layer.vertices.destroy(&self.context.device);
                    layer.indices.destroy(&self.context.device);
                }
                for region in &tile.regions {
                    region.vertices.destroy(&self.context.device);
                    region.indices.destroy(&self.context.device);
                }
                for road in &tile.carriageways {
                    road.vertices.destroy(&self.context.device);
                    road.indices.destroy(&self.context.device);
                }
                if let Some(buildings) = &tile.buildings {
                    buildings.vertices.destroy(&self.context.device);
                    buildings.indices.destroy(&self.context.device);
                }
                if let Some(terrain) = &tile.terrain {
                    terrain.vertices.destroy(&self.context.device);
                    terrain.indices.destroy(&self.context.device);
                }
            }
            self.tiles.clear();
            for (_, tile) in &self.retiring {
                for layer in &tile.layers {
                    layer.vertices.destroy(&self.context.device);
                    layer.indices.destroy(&self.context.device);
                }
                for region in &tile.regions {
                    region.vertices.destroy(&self.context.device);
                    region.indices.destroy(&self.context.device);
                }
                for road in &tile.carriageways {
                    road.vertices.destroy(&self.context.device);
                    road.indices.destroy(&self.context.device);
                }
                if let Some(buildings) = &tile.buildings {
                    buildings.vertices.destroy(&self.context.device);
                    buildings.indices.destroy(&self.context.device);
                }
                if let Some(terrain) = &tile.terrain {
                    terrain.vertices.destroy(&self.context.device);
                    terrain.indices.destroy(&self.context.device);
                }
            }
            self.retiring.clear();
            if let Some(route) = &self.route {
                route.vertices.destroy(&self.context.device);
                route.indices.destroy(&self.context.device);
            }
            for transient in &self.transients {
                transient.vbuf.destroy(&self.context.device);
                transient.ibuf.destroy(&self.context.device);
            }
            self.transients.clear();
            for ring in &self.scratch {
                ring.destroy(&self.context.device);
            }
            self.scratch.clear();
            for frame in &self.frames {
                self.context.device.destroy_fence(frame.in_flight, None);
                self.context.device.destroy_semaphore(frame.image_available, None);
                self.context.device.destroy_semaphore(frame.render_finished, None);
            }
            self.context.device.destroy_command_pool(self.command_pool, None);
            if let Some(image) = &self.glyph_atlas {
                image.destroy(&self.context.device);
            }
            if let Some(image) = &self.sprite_atlas {
                image.destroy(&self.context.device);
            }
            self.quad.vertices.destroy(&self.context.device);
            self.quad.indices.destroy(&self.context.device);
            self.pick.destroy(&self.context.device);
            self.pipeline_cache.destroy(&self.context.device);
            self.atlas_set.destroy(&self.context.device);
            self.pipelines.destroy(&self.context.device);
            self.swapchain.destroy(&self.context.device);
            // `context`'s own Drop destroys the device, surface and instance after this.
            if !self.window.is_null() {
                crate::vulkan::context::ANativeWindow_release(self.window);
                self.window = std::ptr::null_mut();
            }
        }
    }
}

/// The anchors a layer's labels may be drawn at: its first choice, and the one to fall
/// back to when that box collides.
///
/// A layer with no `text-variable-anchor` is centred and cannot move, which is every place
/// layer. Only the first two are used: the reference declares exactly two, and a
/// [`crate::tile::placement::Candidate`] carries exactly two boxes.
fn anchors_for(layer: &Layer) -> (Anchor, Option<Anchor>) {
    match layer.variable_anchor.as_slice() {
        [] => (Anchor::Center, None),
        [only] => (*only, None),
        [first, second, ..] => (*first, Some(*second)),
    }
}

/// Everything one label's collision box depends on besides its anchor.
///
/// Built once per label and reused for each candidate anchor, so the two boxes a POI is
/// tried at can only differ in where they sit — not in how big they are.
fn box_inputs(
    layer: &Layer,
    label: &geometry::ShapedLabel,
    camera: &Camera,
) -> crate::tile::placement::BoxInputs {
    crate::tile::placement::BoxInputs {
        text_px: layer.text_size_for(camera.zoom, label.pop) * camera.density,
        advance: label.total_advance,
        line_count: label.lines.len(),
        offset_em: layer.text_offset,
        // Dp from the sheet, device px here — the same conversion `emit_icon` makes.
        icon_px: label
            .sprite
            .map(|s| (s.width_dp * camera.density, s.height_dp * camera.density)),
        pad_px: collision_padding_px(camera.zoom),
    }
}

/// Collision padding in device px around every label box, by camera zoom.
///
/// MapLibre pads every label (icon + text padding, growing at low zoom via
/// the icon-padding ramp), which is what holds z6 to ~10 cities while z14
/// stays dense. Without it tight advance boxes let hundreds of villages
/// survive at z6. 24px at z6 and below culls hamlets against towns; 4px at
/// z14+ keeps street labels tight. Linear between.
fn collision_padding_px(zoom: f64) -> f32 {
    if zoom <= 6.0 {
        24.0
    } else if zoom >= 14.0 {
        4.0
    } else {
        (24.0 - (zoom - 6.0) * (20.0 / 8.0)) as f32
    }
}

/// Multiply a colour's alpha by `opacity`, for the style's fill-opacity ramps.
///
/// The ramp is applied here rather than folded into the layer table because it is a function of
/// the camera's zoom: a fill has to fade across a zoom, not switch at one.
fn scale_alpha(argb: u32, opacity: f32) -> u32 {
    if opacity >= 1.0 {
        return argb;
    }
    let alpha = (((argb >> 24) & 0xFF) as f32 * opacity.clamp(0.0, 1.0)).round() as u32;
    (alpha << 24) | (argb & 0x00FF_FFFF)
}

/// ARGB to the linear RGBA the shaders and clear values take.
fn argb_to_rgba(argb: u32) -> [f32; 4] {
    [
        ((argb >> 16) & 0xFF) as f32 / 255.0,
        ((argb >> 8) & 0xFF) as f32 / 255.0,
        (argb & 0xFF) as f32 / 255.0,
        ((argb >> 24) & 0xFF) as f32 / 255.0,
    ]
}
