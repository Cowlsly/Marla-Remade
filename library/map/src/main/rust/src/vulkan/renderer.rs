//! The frame: tile residency, and one render pass per frame.

use crate::camera::Camera;
use crate::overlay::{RouteMesh, RoutePlacement};
use crate::style::paint::Stroke;
use crate::style::{Anchor, Layer, LayerKind, Palette};
use crate::tile::geometry::{self, TileMesh};
use crate::tile::select;
use crate::vulkan::buffers::Buffer;
use crate::vulkan::context::{ANativeWindow, Context};
use crate::vulkan::images::{AtlasSet, SampledImage};
use crate::vulkan::pipeline::{Pipelines, Push};
use crate::vulkan::swapchain::Swapchain;
use ash::vk;
use std::cell::Cell;
use std::cmp::Reverse;
use std::collections::HashMap;
use std::collections::HashSet;
use std::ops::RangeInclusive;

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
    /// The region shapes in this tile, for the selection mask. Uploaded with the rest of the
    /// tile so selecting a region costs no tessellation and no allocation.
    regions: Vec<RegionBuffers>,
    /// Shaped symbol candidates (CPU-side): the renderer emits quads per frame
    /// at the frame's text size. Shaped once on the worker thread.
    labels: Vec<geometry::ShapedLabel>,
    z: u8,
    x: u32,
    y: u32,
    /// The toggle generation this was tessellated at — see
    /// [`crate::style::SharedToggles`].
    generation: u32,
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
/// One variant today. It is an enum rather than an `Option<UserPuck>` field because the
/// pins are the next thing to move in here, and the pass below is written as "draw the
/// overlays this frame has" so they can arrive one at a time.
///
/// The route line is deliberately *not* one of these: every variant here draws from the
/// shared unit quad and is pure `Copy` state, while a route owns vertex and index buffers
/// with a retirement rule. It lives in [`Renderer::route`] beside
/// [`Renderer::selected_region`] for the same reason that one does.
enum Overlay {
    Puck(UserPuck),
}

/// The navigation route, resident on the GPU.
///
/// Uploaded once by [`Renderer::set_route`] and never touched again until the route
/// changes: the mesh is zoom-independent by construction (see [`crate::overlay`]), so a
/// frame does nothing but build one matrix and push two colour/width pairs. That is the
/// difference between a route that costs nothing in a two-hour drive and one that
/// re-tessellates on every zoom step.
struct RouteBuffers {
    placement: RoutePlacement,
    vertices: Buffer,
    indices: Buffer,
    index_count: u32,
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
    /// Transient per-frame symbol buffers, same grace rule as `retiring`.
    transients: Vec<TransientBuffers>,
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
    ) -> Result<Renderer, String> {
        let context = Context::new(window)?;
        let swapchain = Swapchain::new(&context, width, height)?;
        let atlas_set = AtlasSet::new(&context.device)?;
        let pipelines = Pipelines::new(
            &context.device,
            swapchain.render_pass,
            swapchain.samples,
            Some(atlas_set.layout),
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
            transients: Vec::new(),
            window,
            width,
            height,
            needs_rebuild: false,
            submitted_draws: Cell::new(0),
            placed: std::cell::RefCell::new(Vec::new()),
            overlays: Vec::new(),
            quad,
            selected_region: None,
            route: None,
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
        let tile = ResidentTile {
            layers,
            regions,
            labels: mesh.labels.clone(),
            z: mesh.z,
            x: mesh.x,
            y: mesh.y,
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
                // Suboptimal or out of date: the window changed under us, so rebuild
                // before the next frame rather than drawing into a stale swapchain.
                Ok(true) | Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => self.needs_rebuild = true,
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
        // [colour, resolve, stencil] while single-sampled is [colour, stencil]. The stencil is
        // therefore at index 2 or index 1 depending on the device, so both carry the stencil
        // clear — the resolve target is `DONT_CARE` and ignores its entry, and a trailing extra
        // entry is allowed. Clearing to zero is what the scrim reads as "outside the region".
        let stencil_clear =
            vk::ClearValue { depth_stencil: vk::ClearDepthStencilValue { depth: 1.0, stencil: 0 } };
        let clear_values = [
            vk::ClearValue { color: vk::ClearColorValue { float32: argb_to_rgba(clear) } },
            stencil_clear,
            stencil_clear,
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
                // tile's shaped candidates (see above): fetch the tile by key so
                // `record_symbol` can take `&mut self` for transient uploads.
                if layer.kind == LayerKind::Symbol {
                    self.record_symbol(
                        command_buffer,
                        *key,
                        index,
                        layer,
                        camera,
                        palette,
                        &accepted,
                        &mut submitted,
                        &mut bound,
                    );
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
                        misc: [camera.tile_span_px(tz), edge_aa, lateral_px, 0.0],
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
        self.record_region_mask(command_buffer, camera, &mut submitted);
        self.record_route(command_buffer, camera, &mut submitted);
        self.record_overlays(command_buffer, camera, &mut submitted);

        self.submitted_draws.set(submitted);
        device.cmd_end_render_pass(command_buffer);
        device.end_command_buffer(command_buffer).map_err(|e| format!("end_command_buffer {e:?}"))
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
    /// Two draws over one buffer: the casing at the wider half-width, then the route on
    /// top of it. `stroke` bakes no width into a vertex, so the same geometry drawn wider
    /// underneath *is* the outline — no second mesh and no `gapped` band.
    ///
    /// Allocation-free, like every other per-frame path here: the buffers were uploaded
    /// when the route was set, and everything that varies per frame is a matrix and two
    /// push blocks built on the stack. The matrix is the overlay sibling of
    /// `tile_to_clip`, so the route picks up the camera's bearing exactly as the tiles
    /// under it do.
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
        for (color, half_width_px) in route.placement.passes(camera.density) {
            let push = Push {
                tile_to_clip: matrix,
                color: argb_to_rgba(color),
                // No gap and no dash: a route is one solid band, and `line.frag`
                // short-circuits a non-positive dash gap before it reaches the modulo.
                line: [half_width_px, 0.0, 0.0, 0.0],
                misc: [span_px, edge_aa, 0.0, 0.0],
            };
            device.cmd_push_constants(
                command_buffer,
                self.pipelines.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                push.as_bytes(),
            );
            device.cmd_draw_indexed(command_buffer, route.index_count, 1, 0, 0, 0);
            *submitted += 1;
        }
    }

    /// Draw this frame's overlays on top of every tile.
    ///
    /// Called with the render pass still open: the viewport and scissor are already set
    /// and blending is the same straight src-alpha-over the tile layers use, so an
    /// overlay only has to bind its pipeline and push its own state. The geometry is the
    /// shared unit quad, so nothing is allocated here.
    unsafe fn record_overlays(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        submitted: &mut usize,
    ) {
        let device = &self.context.device;
        for overlay in &self.overlays {
            match overlay {
                Overlay::Puck(puck) => {
                    let density = camera.density;
                    let push = Push {
                        tile_to_clip: camera.screen_quad_to_clip(
                            puck.lon,
                            puck.lat,
                            PUCK_QUAD_DP as f64,
                        ),
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
                            0.0,
                        ],
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
                    device.cmd_bind_vertex_buffers(
                        command_buffer,
                        0,
                        &[self.quad.vertices.buffer],
                        &[0],
                    );
                    device.cmd_bind_index_buffer(
                        command_buffer,
                        self.quad.indices.buffer,
                        0,
                        vk::IndexType::UINT32,
                    );
                    device.cmd_draw_indexed(
                        command_buffer,
                        QUAD_INDICES.len() as u32,
                        1,
                        0,
                        0,
                        0,
                    );
                    *submitted += 1;
                }
            }
        }
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
        let tile_span_px = camera.tile_span_px(tile.z);
        // Copy out what the draw needs before any `&mut self` call below: `tile`
        // borrows `self`, and buffer upload takes `&self.context` while retiring
        // takes `&mut self`.
        let tile_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
        let tile_labels = tile.labels.clone();
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
        for (label_idx, label) in
            tile_labels.iter().enumerate().filter(|(_, l)| l.layer_index == layer_index)
        {
            let id = placement::candidate_id(tile.z, tile.x, tile.y, layer_index, label_idx);
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
                line: [0.0, 0.0, 0.0, 0.0],
                misc: [tile_span_px, 0.0, 0.0, 0.0],
            };
            self.draw_symbol_batch(
                command_buffer,
                self.pipelines.sprite,
                sprite_set,
                &icon_vertices,
                &icon_indices,
                &push,
                submitted,
            );
            // The sprite pipeline shares `LayerKind::Symbol`, so this still forces the
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
                line: [*text_px, layer.halo_width * camera.density, sdf_per_em, 0.0],
                misc: [tile_span_px, halo[0], halo[1], halo[2]],
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

    /// Upload one transient vertex/index pair, bind `pipeline` with `set`, and draw it.
    ///
    /// The icon and text paths differ only in which pipeline and atlas they bind, so they
    /// share this. Buffers retire on the frames-in-flight grace count: last frame's
    /// command buffer may still reference them.
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
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[vbuf.buffer], &[0]);
        device.cmd_bind_index_buffer(command_buffer, ibuf.buffer, 0, vk::IndexType::UINT32);
        device.cmd_draw_indexed(command_buffer, indices.len() as u32, 1, 0, 0, 0);
        *submitted += 1;
        self.transients.push(TransientBuffers { vbuf, ibuf, frames: FRAMES_IN_FLIGHT });
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
    ) -> HashMap<u64, (bool, u32)> {
        use crate::tile::placement;
        let mut candidates = Vec::new();
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
                    let wh = (extent.width, extent.height);
                    candidates.push(placement::Candidate {
                        id: placement::candidate_id(
                            tile.z,
                            tile.x,
                            tile.y,
                            index,
                            label_idx,
                        ),
                        rank: label.rank,
                        pop: label.pop,
                        rect: placement::anchored_rect(
                            label.anchor,
                            tile_clip,
                            wh,
                            &inputs,
                            primary,
                        ),
                        alternate: alternate.map(|second| {
                            placement::anchored_rect(label.anchor, tile_clip, wh, &inputs, second)
                        }),
                    });
                }
            }
        }
        // Keyed by candidate id, valued by the anchor the placer settled on and **where in
        // acceptance order it landed**. `place` returns its winners in priority order and a
        // `HashMap` would throw that away, which is what made `pick_labels`' "topmost first"
        // a claim rather than a fact.
        placement::place(&candidates)
            .into_iter()
            .enumerate()
            .map(|(order, (id, flipped))| (id, (flipped, order as u32)))
            .collect()
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
            // The pipelines reference the old render pass, so they go with it.
            self.pipelines.destroy(&self.context.device);
            self.swapchain.destroy(&self.context.device);
            self.swapchain = Swapchain::new(&self.context, self.width, self.height)?;
            self.pipelines = Pipelines::new(
                &self.context.device,
                self.swapchain.render_pass,
                self.swapchain.samples,
                Some(self.atlas_set.layout),
            )?;
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
