//! The frame: tile residency, and one render pass per frame.

use crate::camera::Camera;
use crate::style::paint::Stroke;
use crate::style::{Layer, LayerKind, Palette};
use crate::tile::geometry::{self, TileMesh};
use crate::vulkan::buffers::Buffer;
use crate::vulkan::context::{ANativeWindow, Context};
use crate::vulkan::images::{AtlasSet, SampledImage};
use crate::vulkan::pipeline::{Pipelines, Push};
use crate::vulkan::swapchain::Swapchain;
use ash::vk;
use std::cell::Cell;
use std::collections::{HashMap, HashSet};

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
}

/// One tile's geometry, resident on the GPU.
struct ResidentTile {
    layers: Vec<LayerBuffers>,
    /// Shaped symbol candidates (CPU-side): the renderer emits quads per frame
    /// at the frame's text size. Shaped once on the worker thread.
    labels: Vec<geometry::ShapedLabel>,
    z: u8,
    x: u32,
    y: u32,
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

pub struct Renderer {
    context: Context,
    swapchain: Swapchain,
    pipelines: Pipelines,
    /// Sampled-image infra shared by the glyph atlas (M1) and the sprite atlas
    /// (M2): one pool/layout, one set per atlas. Uploaded once at startup from
    /// the CPU-built atlas bytes.
    atlas_set: AtlasSet,
    glyph_atlas: Option<SampledImage>,
    glyph_set: Option<vk::DescriptorSet>,
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
    /// Anchor lon/lat in degrees.
    pub lon: f64,
    pub lat: f64,
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
        let pipelines =
            Pipelines::new(&context.device, swapchain.render_pass, Some(atlas_set.layout))?;

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

        Ok(Renderer {
            context,
            swapchain,
            pipelines,
            atlas_set,
            glyph_atlas,
            glyph_set,
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
        })
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

    pub fn has_tile(&self, key: u64) -> bool {
        self.tiles.contains_key(&key)
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
                });
            }
        }
        let tile = ResidentTile {
            layers,
            labels: mesh.labels.clone(),
            z: mesh.z,
            x: mesh.x,
            y: mesh.y,
        };
        if let Some(previous) = self.tiles.insert(key, tile) {
            self.retire(previous);
        }
        Ok(())
    }

    /// Drop every resident tile whose key is not in `keep`.
    pub fn retain(&mut self, keep: &[u64]) {
        let doomed: Vec<u64> =
            self.tiles.keys().copied().filter(|k| !keep.contains(k)).collect();
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
    pub fn render(
        &mut self,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        clear: u32,
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
            self.record(command_buffer, image_index as usize, camera, layers, palette, clear)?;

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
            self.record_inner(command_buffer, render_pass, framebuffer, extent, camera, layers, palette, clear)
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

        let clear_value = vk::ClearValue {
            color: vk::ClearColorValue { float32: argb_to_rgba(clear) },
        };
        let pass = vk::RenderPassBeginInfo::default()
            .render_pass(render_pass)
            .framebuffer(framebuffer)
            .render_area(vk::Rect2D { offset: vk::Offset2D { x: 0, y: 0 }, extent })
            .clear_values(std::slice::from_ref(&clear_value));
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
        let accepted = self.place_symbols(camera, layers, &ordered, extent);
        // Task-17 pick snapshot: the accepted labels with their screen boxes,
        // names, kinds and anchor geo — refreshed every frame so pickLabels
        // answers the frame the user sees, not a stale one.
        self.refresh_placed(camera, layers, &accepted, extent);

        for (index, layer) in layers.iter().enumerate() {
            // `min_zoom`/`max_zoom` are a data-and-cost gate, not paint: they say which zooms
            // the archive is worth asking for this layer at. Paint is the ramp below.
            if !layer.draws_at(camera.zoom.floor().clamp(0.0, 22.0) as u8) {
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
                    if layer.text_size.at(camera.zoom) <= 0.0 {
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
                // Copy the draw's inputs out, then issue it through the owned
                // `device` clone: `record_symbol` above takes `&mut self`, so this
                // path must not hold a `self.tiles` borrow either. (Uniform shape
                // for both arms keeps the borrow rules obvious.)
                let draw = self.tiles.get(key).and_then(|tile| {
                    tile.layers
                        .iter()
                        .find(|l| l.layer_index == index)
                        .map(|l| (tile.z, tile.x, tile.y, l.kind, l.vertices.buffer, l.indices.buffer, l.index_count))
                });
                let Some((tz, tx, ty, kind, vbuf, ibuf, count)) = draw else { continue };
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
                let push = Push {
                    tile_to_clip: camera.tile_to_clip(tz, tx, ty),
                    color: argb_to_rgba(scale_alpha(layer.color(palette), opacity)),
                    line: [half_width_px, half_gap_px, layer.dash.0, layer.dash.1],
                    misc: [camera.tile_span_px(tz), 0.0, 0.0, 0.0],
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

        self.submitted_draws.set(submitted);
        device.cmd_end_render_pass(command_buffer);
        device.end_command_buffer(command_buffer).map_err(|e| format!("end_command_buffer {e:?}"))
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
        accepted: &HashSet<u64>,
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
        let text_px = layer.text_size.at(camera.zoom) * camera.density;
        if text_px <= 0.0 {
            return;
        }
        let Some(tile) = self.tiles.get(&key) else { return };
        let tile_span_px = camera.tile_span_px(tile.z);
        // Copy out what the draw needs before any `&mut self` call below: `tile`
        // borrows `self`, and buffer upload takes `&self.context` while retiring
        // takes `&mut self`.
        let tile_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
        let tile_labels = tile.labels.clone();
        let mut vertices: Vec<f32> = Vec::new();
        let mut indices: Vec<u32> = Vec::new();
        // Labels are enumerated in tile-list order — the same order (and the
        // same ids) the pre-pass used — and only accepted ones emit. A tile
        // whose every label collides emits nothing and skips its draw.
        for (label_idx, label) in
            tile_labels.iter().enumerate().filter(|(_, l)| l.layer_index == layer_index)
        {
            let id = placement::candidate_id(tile.z, tile.x, tile.y, layer_index, label_idx);
            if !accepted.contains(&id) {
                continue;
            }
            symbol::emit_label(label, text_px, tile_span_px, &mut vertices, &mut indices);
        }
        if indices.is_empty() {
            return;
        }
        let device = &self.context.device;
        let vbuf: Buffer = match Buffer::upload(
            &self.context.instance,
            self.context.physical_device,
            device,
            vk::BufferUsageFlags::VERTEX_BUFFER,
            &vertices,
        ) {
            Ok(b) => b,
            Err(_) => return,
        };
        let ibuf: Buffer = match Buffer::upload(
            &self.context.instance,
            self.context.physical_device,
            device,
            vk::BufferUsageFlags::INDEX_BUFFER,
            &indices,
        ) {
            Ok(b) => b,
            Err(_) => {
                vbuf.destroy(device);
                return;
            }
        };
        // Bind the symbol pipeline + atlas set once per draw (cheap; labels are few).
        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipelines.symbol,
        );
        *bound = Some(LayerKind::Symbol);
        device.cmd_bind_descriptor_sets(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipelines.symbol_layout,
            0,
            &[glyph_set],
            &[],
        );
        // Halo width from the style (authored text-halo-width, 1px); text color
        // + opacity per palette.
        let halo = argb_to_rgba(layer.halo_color(palette));
        let texel = 1.0 / crate::tile::glyph::ATLAS_PX as f32;
        let push = Push {
            tile_to_clip: tile_clip,
            color: argb_to_rgba(scale_alpha(layer.color(palette), layer.opacity_at(camera.zoom))),
            line: [text_px, layer.halo_width, texel, texel],
            misc: [tile_span_px, halo[0], halo[1], halo[2]],
        };
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
        // Retire with the frames-in-flight grace: last frame's command buffer may
        // still reference these buffers.
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
    ) -> HashSet<u64> {
        use crate::tile::placement;
        let mut candidates = Vec::new();
        for (index, layer) in layers.iter().enumerate() {
            if layer.kind != LayerKind::Symbol {
                continue;
            }
            if !layer.draws_at(camera.zoom.floor().clamp(0.0, 22.0) as u8) {
                continue;
            }
            let text_px = layer.text_size.at(camera.zoom);
            if text_px <= 0.0 {
                continue;
            }
            for key in ordered {
                let Some(tile) = self.tiles.get(key) else { continue };
                let tile_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
                // Task-9 rank gating: at low UI zoom only high-pop localities
                // become candidates at all — collision alone can't thin
                // hundreds of towns to the major-city set. UI zoom (offset
                // removed) so selection matches MapLibre's per-zoom set.
                let ui_zoom = camera.zoom - 1.0;
                let min_pop = placement::locality_min_pop(ui_zoom);
                for (label_idx, label) in tile
                    .labels
                    .iter()
                    .enumerate()
                    .filter(|(_, l)| l.layer_index == index)
                {
                    if label.rank == 2 && label.pop < min_pop {
                        continue;
                    }
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
                        rect: placement::screen_rect(
                            label.anchor,
                            tile_clip,
                            (extent.width, extent.height),
                            text_px,
                            label.total_advance,
                            collision_padding_px(camera.zoom),
                        ),
                    });
                }
            }
        }
        placement::place(&candidates).into_iter().collect()
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
        accepted: &std::collections::HashSet<u64>,
        extent: vk::Extent2D,
    ) {
        use crate::tile::placement;
        let mut placed = Vec::new();
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
                if !accepted.contains(&id) {
                    continue;
                }
                let Some(layer) = layers.get(label.layer_index) else { continue };
                let text_px = layer.text_size.at(camera.zoom);
                if text_px <= 0.0 {
                    continue;
                }
                let tile_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
                let rect = placement::screen_rect(
                    label.anchor,
                    tile_clip,
                    (extent.width, extent.height),
                    text_px,
                    label.total_advance,
                    collision_padding_px(camera.zoom),
                );
                // Anchor tile-local → world px (Dp) → lon/lat.
                let wx = tile_wx + label.anchor.0 as f64 * span_dp;
                let wy = tile_wy + label.anchor.1 as f64 * span_dp;
                let (lon, lat) = crate::camera::unproject(wx, wy, camera.zoom);
                let _ = origin;
                placed.push(PlacedHit {
                    rect,
                    layer_index: label.layer_index,
                    name: label.name.clone(),
                    kind: layer.kinds.first().cloned().unwrap_or_default(),
                    lon,
                    lat,
                });
            }
        }
        *self.placed.borrow_mut() = placed;
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

impl Drop for Renderer {
    fn drop(&mut self) {
        unsafe {
            let _ = self.context.device.device_wait_idle();
            for tile in self.tiles.values() {
                for layer in &tile.layers {
                    layer.vertices.destroy(&self.context.device);
                    layer.indices.destroy(&self.context.device);
                }
            }
            self.tiles.clear();
            for (_, tile) in &self.retiring {
                for layer in &tile.layers {
                    layer.vertices.destroy(&self.context.device);
                    layer.indices.destroy(&self.context.device);
                }
            }
            self.retiring.clear();
            for frame in &self.frames {
                self.context.device.destroy_fence(frame.in_flight, None);
                self.context.device.destroy_semaphore(frame.image_available, None);
                self.context.device.destroy_semaphore(frame.render_finished, None);
            }
            self.context.device.destroy_command_pool(self.command_pool, None);
            if let Some(image) = &self.glyph_atlas {
                image.destroy(&self.context.device);
            }
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
