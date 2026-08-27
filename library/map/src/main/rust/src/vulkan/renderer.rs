//! The frame: tile residency, and one render pass per frame.

use crate::camera::Camera;
use crate::style::paint::Stroke;
use crate::style::{Layer, LayerKind, Palette};
use crate::tile::geometry::TileMesh;
use crate::vulkan::buffers::Buffer;
use crate::vulkan::context::{ANativeWindow, Context};
use crate::vulkan::pipeline::{Pipelines, Push};
use crate::vulkan::swapchain::Swapchain;
use ash::vk;
use std::cell::Cell;
use std::collections::HashMap;

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
    z: u8,
    x: u32,
    y: u32,
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
    command_pool: vk::CommandPool,
    frames: Vec<Frame>,
    frame_index: usize,
    tiles: HashMap<u64, ResidentTile>,
    /// Retired buffers waiting for the frames that might still reference them.
    retiring: Vec<(usize, ResidentTile)>,
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
        let pipelines = Pipelines::new(&context.device, swapchain.render_pass)?;

        let pool_info = vk::CommandPoolCreateInfo::default()
            .queue_family_index(context.queue_family_index)
            // Each frame's buffer is re-recorded every frame, so it must be individually
            // resettable rather than requiring a whole-pool reset.
            .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
        let command_pool = context
            .device
            .create_command_pool(&pool_info, None)
            .map_err(|e| format!("create_command_pool {e:?}"))?;

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
            command_pool,
            frames,
            frame_index: 0,
            tiles: HashMap::new(),
            retiring: Vec::new(),
            window,
            width,
            height,
            needs_rebuild: false,
            submitted_draws: Cell::new(0),
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
        let tile = ResidentTile { layers, z: mesh.z, x: mesh.x, y: mesh.y };
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

        unsafe {
            let device = &self.context.device;
            device
                .reset_fences(std::slice::from_ref(&frame.in_flight))
                .map_err(|e| format!("reset_fences {e:?}"))?;
            self.record(frame.command_buffer, image_index as usize, camera, layers, palette, clear)?;

            let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
            let submit = vk::SubmitInfo::default()
                .wait_semaphores(std::slice::from_ref(&frame.image_available))
                .wait_dst_stage_mask(&wait_stages)
                .command_buffers(std::slice::from_ref(&frame.command_buffer))
                .signal_semaphores(std::slice::from_ref(&frame.render_finished));
            device
                .queue_submit(self.context.queue, std::slice::from_ref(&submit), frame.in_flight)
                .map_err(|e| format!("queue_submit {e:?}"))?;

            let swapchains = [self.swapchain.swapchain];
            let indices = [image_index];
            let present = vk::PresentInfoKHR::default()
                .wait_semaphores(std::slice::from_ref(&frame.render_finished))
                .swapchains(&swapchains)
                .image_indices(&indices);
            match self.swapchain.loader.queue_present(self.context.queue, &present) {
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
    unsafe fn record(
        &self,
        command_buffer: vk::CommandBuffer,
        image_index: usize,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        clear: u32,
    ) -> Result<(), String> {
        let device = &self.context.device;
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
            .render_pass(self.swapchain.render_pass)
            .framebuffer(self.swapchain.framebuffers[image_index])
            .render_area(vk::Rect2D { offset: vk::Offset2D { x: 0, y: 0 }, extent: self.swapchain.extent })
            .clear_values(std::slice::from_ref(&clear_value));
        device.cmd_begin_render_pass(command_buffer, &pass, vk::SubpassContents::INLINE);

        let viewport = vk::Viewport::default()
            .width(self.swapchain.extent.width as f32)
            .height(self.swapchain.extent.height as f32)
            .min_depth(0.0)
            .max_depth(1.0);
        device.cmd_set_viewport(command_buffer, 0, std::slice::from_ref(&viewport));
        let scissor =
            vk::Rect2D { offset: vk::Offset2D { x: 0, y: 0 }, extent: self.swapchain.extent };
        device.cmd_set_scissor(command_buffer, 0, std::slice::from_ref(&scissor));

        let mut bound: Option<LayerKind> = None;
        let mut submitted = 0usize;
        // Coarsest tiles first, so an ancestor standing in for a tile that has not arrived
        // is drawn *under* its descendants and gets covered as they load. A HashMap's
        // iteration order is arbitrary, so without this a stale parent can land on top of
        // the sharp child.
        let mut ordered: Vec<&ResidentTile> = self.tiles.values().collect();
        ordered.sort_by_key(|tile| tile.z);

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
                    (stroke, 1.0)
                }
            };
            for tile in &ordered {
                let Some(buffers) = tile.layers.iter().find(|l| l.layer_index == index) else {
                    continue;
                };
                if bound != Some(buffers.kind) {
                    let pipeline = match buffers.kind {
                        LayerKind::Fill => self.pipelines.fill,
                        LayerKind::Line => self.pipelines.line,
                    };
                    device.cmd_bind_pipeline(
                        command_buffer,
                        vk::PipelineBindPoint::GRAPHICS,
                        pipeline,
                    );
                    bound = Some(buffers.kind);
                }

                let (half_width_px, half_gap_px) = stroke.half_px(camera.density);
                let push = Push {
                    tile_to_clip: camera.tile_to_clip(tile.z, tile.x, tile.y),
                    color: argb_to_rgba(scale_alpha(layer.color(palette), opacity)),
                    line: [half_width_px, half_gap_px, layer.dash.0, layer.dash.1],
                    misc: [camera.tile_span_px(tile.z), 0.0, 0.0, 0.0],
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
                    std::slice::from_ref(&buffers.vertices.buffer),
                    &[0],
                );
                // Uint32 rather than Uint16: a dense z14 tile can exceed 65535 vertices in
                // one layer, and overflowing folds geometry back on itself rather than
                // failing loudly.
                device.cmd_bind_index_buffer(
                    command_buffer,
                    buffers.indices.buffer,
                    0,
                    vk::IndexType::UINT32,
                );
                device.cmd_draw_indexed(command_buffer, buffers.index_count, 1, 0, 0, 0);
                submitted += 1;
            }
        }

        self.submitted_draws.set(submitted);
        device.cmd_end_render_pass(command_buffer);
        device.end_command_buffer(command_buffer).map_err(|e| format!("end_command_buffer {e:?}"))
    }

    /// Rebuild the swapchain after a resize, rotation or out-of-date present.
    fn rebuild(&mut self) -> Result<(), String> {
        unsafe {
            let _ = self.context.device.device_wait_idle();
            // The pipelines reference the old render pass, so they go with it.
            self.pipelines.destroy(&self.context.device);
            self.swapchain.destroy(&self.context.device);
            self.swapchain = Swapchain::new(&self.context, self.width, self.height)?;
            self.pipelines = Pipelines::new(&self.context.device, self.swapchain.render_pass)?;
        }
        self.width = self.swapchain.extent.width;
        self.height = self.swapchain.extent.height;
        Ok(())
    }
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
