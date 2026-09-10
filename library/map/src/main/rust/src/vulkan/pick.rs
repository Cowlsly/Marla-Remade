//! Offscreen id-buffer picking: which feature is under a tap.
//!
//! # Why a GPU id buffer rather than a CPU hit-test
//!
//! The pins moved into the renderer as billboarded sprites (see [`crate::marker`]), so the CPU no
//! longer holds their screen boxes — and under tilt a screen box is not a simple projection of a
//! lon/lat anyway. Picking therefore mirrors what the renderer already draws: render every
//! pickable feature's **id** to an offscreen `R32_UINT` image with the same matrices the visible
//! frame uses, then read back the single tapped pixel. Overlapping features resolve by draw order
//! for free — the last one drawn to that pixel is the one on top — which is the same rule the
//! visible frame resolves them by.
//!
//! This is the offscreen sibling of the region mask ([`crate::vulkan::renderer`]'s
//! `record_region_mask`): a second attachment written in its own pass and consumed by the host,
//! not shown. It is invoked out of band from [`Renderer::pick_at`](crate::vulkan::renderer) on a
//! tap, not every frame, so a one-shot submit with a `queue_wait_idle` (as the atlas upload does)
//! is the whole of its synchronisation — no per-frame cost and nothing added to the hot loop.
//!
//! # Slots, not raw ids
//!
//! An `R32_UINT` pixel holds 32 bits, while a feature id is a `u64`, so the pass writes a **1-based
//! per-pick slot** (0 = nothing) and the caller maps the slot back to the feature. That also keeps
//! the mechanism uniform across feature kinds: today markers, and any of labels/POI/regions the
//! renderer later chooses to add carry their own slot the same way.

use crate::camera::Camera;
use crate::marker::{Marker, MARKER_SIZE_DP};
use crate::vulkan::buffers::Buffer;
use crate::vulkan::context::Context;
use crate::vulkan::pipeline::{Push, MORPH_NONE, PUSH_CONSTANT_BYTES};
use ash::vk;

const PICK_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/pick.vert.spv"));
const PICK_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/pick.frag.spv"));

/// The id-buffer format. `R32_UINT` is a mandatory colour-attachment format on every Vulkan
/// implementation, so this never has to negotiate one the way the swapchain colour format does.
const PICK_FORMAT: vk::Format = vk::Format::R32_UINT;

/// The slot value the target is cleared to: "nothing here". Real features get 1-based slots.
const PICK_MISS: u32 = 0;

/// The offscreen id pass: a render pass, a pipeline that writes a slot id, and the target it
/// writes into (rebuilt on resize).
pub struct Pick {
    render_pass: vk::RenderPass,
    layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    /// The colour target + framebuffer, sized to the swapchain. `None` until the first pick, then
    /// rebuilt whenever the extent changes.
    target: Option<PickTarget>,
    /// A 4-byte host-visible buffer the tapped pixel is copied into and read back from.
    readback: Buffer,
}

/// The `R32_UINT` image the ids are drawn into, its view and framebuffer.
struct PickTarget {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
    framebuffer: vk::Framebuffer,
    extent: vk::Extent2D,
}

impl Pick {
    /// Build the id pass. The render pass and pipeline are format-stable (the target is always
    /// `R32_UINT`, single-sampled, independent of the swapchain), so they are created once and
    /// survive [`Renderer::rebuild`](crate::vulkan::renderer) — only the target is resized.
    ///
    /// # Safety
    ///
    /// `context` must be live; call [`destroy`](Self::destroy) while the device is idle.
    pub unsafe fn new(context: &Context) -> Result<Pick, String> {
        let device = &context.device;

        // One colour attachment, cleared to "miss", stored, and left in TRANSFER_SRC so the pixel
        // copy that follows the pass reads it directly.
        let color = vk::AttachmentDescription::default()
            .format(PICK_FORMAT)
            .samples(vk::SampleCountFlags::TYPE_1)
            .load_op(vk::AttachmentLoadOp::CLEAR)
            .store_op(vk::AttachmentStoreOp::STORE)
            .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(vk::ImageLayout::TRANSFER_SRC_OPTIMAL);
        let color_ref = vk::AttachmentReference::default()
            .attachment(0)
            .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL);
        let subpass = vk::SubpassDescription::default()
            .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
            .color_attachments(std::slice::from_ref(&color_ref));
        // In: wait for prior use of the image before the clear. Out: order the id writes before
        // the copy-to-buffer that reads them back after the pass ends.
        let deps = [
            vk::SubpassDependency::default()
                .src_subpass(vk::SUBPASS_EXTERNAL)
                .dst_subpass(0)
                .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
                .dst_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
                .dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE),
            vk::SubpassDependency::default()
                .src_subpass(0)
                .dst_subpass(vk::SUBPASS_EXTERNAL)
                .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
                .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
                .dst_stage_mask(vk::PipelineStageFlags::TRANSFER)
                .dst_access_mask(vk::AccessFlags::TRANSFER_READ),
        ];
        let render_pass_info = vk::RenderPassCreateInfo::default()
            .attachments(std::slice::from_ref(&color))
            .subpasses(std::slice::from_ref(&subpass))
            .dependencies(&deps);
        let render_pass = device
            .create_render_pass(&render_pass_info, None)
            .map_err(|e| format!("pick create_render_pass {e:?}"))?;

        // Push-only layout, same range as the main pipelines so a full `Push` can be pushed.
        let push_range = vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
            .offset(0)
            .size(PUSH_CONSTANT_BYTES);
        let layout_info = vk::PipelineLayoutCreateInfo::default()
            .push_constant_ranges(std::slice::from_ref(&push_range));
        let layout = match device.create_pipeline_layout(&layout_info, None) {
            Ok(l) => l,
            Err(e) => {
                device.destroy_render_pass(render_pass, None);
                return Err(format!("pick create_pipeline_layout {e:?}"));
            }
        };

        let pipeline = match build_pipeline(device, render_pass, layout) {
            Ok(p) => p,
            Err(e) => {
                device.destroy_pipeline_layout(layout, None);
                device.destroy_render_pass(render_pass, None);
                return Err(e);
            }
        };

        // A 4-byte host-visible buffer the tapped pixel is copied into. Allocated once (its initial
        // contents are irrelevant; it is overwritten by every copy) and mapped for read after.
        let readback = match Buffer::upload(
            &context.instance,
            context.physical_device,
            device,
            vk::BufferUsageFlags::TRANSFER_DST,
            &[PICK_MISS],
        ) {
            Ok(b) => b,
            Err(e) => {
                device.destroy_pipeline(pipeline, None);
                device.destroy_pipeline_layout(layout, None);
                device.destroy_render_pass(render_pass, None);
                return Err(format!("pick readback buffer: {e}"));
            }
        };

        Ok(Pick { render_pass, layout, pipeline, target: None, readback })
    }

    /// The feature id under the device-pixel `(x, y)`, or `0` when the tap hit nothing.
    ///
    /// Renders every marker's slot into the id target with the frame's camera, reads back the one
    /// tapped pixel, and maps the slot to the marker's id. Markers are drawn in order, so a pin on
    /// top of another wins, matching what is on screen. A one-shot submit with `queue_wait_idle`:
    /// picking is a tap, not a frame, so a synchronous readback is fine and keeps this off the hot
    /// loop entirely.
    ///
    /// # Safety
    ///
    /// `context`/`command_pool`/`quad_*` must be live and belong to the same device this was built
    /// on; the caller (the renderer) guarantees that.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn at(
        &mut self,
        context: &Context,
        command_pool: vk::CommandPool,
        quad_vertices: vk::Buffer,
        quad_indices: vk::Buffer,
        quad_index_count: u32,
        extent: vk::Extent2D,
        camera: &Camera,
        markers: &[Marker],
        x: u32,
        y: u32,
    ) -> Result<u64, String> {
        if markers.is_empty() || extent.width == 0 || extent.height == 0 {
            return Ok(0);
        }
        // The tap can arrive a frame after a resize; clamp so the copy region is always in bounds.
        let px = x.min(extent.width - 1);
        let py = y.min(extent.height - 1);

        self.ensure_target(context, extent)?;
        let target = self.target.as_ref().expect("ensure_target set it");
        let device = &context.device;

        let alloc = vk::CommandBufferAllocateInfo::default()
            .command_pool(command_pool)
            .level(vk::CommandBufferLevel::PRIMARY)
            .command_buffer_count(1);
        let cmd = device
            .allocate_command_buffers(&alloc)
            .map_err(|e| format!("pick allocate cmd {e:?}"))?
            .into_iter()
            .next()
            .ok_or("pick got no command buffer")?;

        let free_cmd = |device: &ash::Device| device.free_command_buffers(command_pool, &[cmd]);

        let begin = vk::CommandBufferBeginInfo::default()
            .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
        if let Err(e) = device.begin_command_buffer(cmd, &begin) {
            free_cmd(device);
            return Err(format!("pick begin cmd {e:?}"));
        }

        let clear = [vk::ClearValue { color: vk::ClearColorValue { uint32: [PICK_MISS; 4] } }];
        let pass = vk::RenderPassBeginInfo::default()
            .render_pass(self.render_pass)
            .framebuffer(target.framebuffer)
            .render_area(vk::Rect2D { offset: vk::Offset2D { x: 0, y: 0 }, extent })
            .clear_values(&clear);
        device.cmd_begin_render_pass(cmd, &pass, vk::SubpassContents::INLINE);

        // Full viewport so clip→screen matches the visible frame, but the scissor is the single
        // tapped pixel: only a marker actually covering it writes, which is all the copy reads.
        let viewport = vk::Viewport::default()
            .width(extent.width as f32)
            .height(extent.height as f32)
            .min_depth(0.0)
            .max_depth(1.0);
        device.cmd_set_viewport(cmd, 0, std::slice::from_ref(&viewport));
        let scissor = vk::Rect2D {
            offset: vk::Offset2D { x: px as i32, y: py as i32 },
            extent: vk::Extent2D { width: 1, height: 1 },
        };
        device.cmd_set_scissor(cmd, 0, std::slice::from_ref(&scissor));

        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipeline);
        device.cmd_bind_vertex_buffers(cmd, 0, &[quad_vertices], &[0]);
        device.cmd_bind_index_buffer(cmd, quad_indices, 0, vk::IndexType::UINT32);

        // Slots are 1-based (0 is "miss"), so a slot is an index that fits a `u32` for any
        // plausible marker count; the id-buffer resolution caps at what a pixel can hold anyway.
        for (index, marker) in markers.iter().enumerate() {
            let slot = (index as u32).wrapping_add(1);
            // The same billboard matrix the sprite is drawn with, sized to the marker's tap box.
            let matrix = camera.screen_quad_to_clip(marker.lon, marker.lat, (MARKER_SIZE_DP * 0.5) as f64);
            let mut push = Push {
                tile_to_clip: matrix,
                color: [0.0; 4],
                line: [0.0; 4],
                misc: [0.0; 4],
                morph: MORPH_NONE,
            };
            // Carry the slot as the bit pattern of a float; the shader reads it with
            // `floatBitsToUint`, so the exact integer survives.
            push.color[0] = f32::from_bits(slot);
            device.cmd_push_constants(
                cmd,
                self.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                push.as_bytes(),
            );
            device.cmd_draw_indexed(cmd, quad_index_count, 1, 0, 0, 0);
        }

        device.cmd_end_render_pass(cmd);

        // Copy the tapped pixel out of the (now TRANSFER_SRC) image into the readback buffer.
        let region = vk::BufferImageCopy::default()
            .buffer_offset(0)
            .image_subresource(vk::ImageSubresourceLayers {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                mip_level: 0,
                base_array_layer: 0,
                layer_count: 1,
            })
            .image_offset(vk::Offset3D { x: px as i32, y: py as i32, z: 0 })
            .image_extent(vk::Extent3D { width: 1, height: 1, depth: 1 });
        device.cmd_copy_image_to_buffer(
            cmd,
            target.image,
            vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
            self.readback.buffer,
            std::slice::from_ref(&region),
        );

        if let Err(e) = device.end_command_buffer(cmd) {
            free_cmd(device);
            return Err(format!("pick end cmd {e:?}"));
        }

        let submit = vk::SubmitInfo::default().command_buffers(std::slice::from_ref(&cmd));
        if let Err(e) = device.queue_submit(context.queue, std::slice::from_ref(&submit), vk::Fence::null())
        {
            free_cmd(device);
            return Err(format!("pick queue_submit {e:?}"));
        }
        if let Err(e) = device.queue_wait_idle(context.queue) {
            free_cmd(device);
            return Err(format!("pick queue_wait_idle {e:?}"));
        }
        free_cmd(device);

        // Read the slot back. HOST_COHERENT (the Buffer helper's memory), so no invalidate.
        let slot = {
            let mapped = device
                .map_memory(self.readback.memory, 0, 4, vk::MemoryMapFlags::empty())
                .map_err(|e| format!("pick map_memory {e:?}"))?;
            let slot = (mapped as *const u32).read_unaligned();
            device.unmap_memory(self.readback.memory);
            slot
        };
        if slot == PICK_MISS {
            return Ok(0);
        }
        // A slot is 1-based into `markers`; anything out of range is a stale/garbage read and is a
        // miss rather than a panic.
        Ok(markers.get((slot - 1) as usize).map(|m| m.id).unwrap_or(0))
    }

    /// Ensure the target exists at `extent`, rebuilding it if the surface was resized.
    unsafe fn ensure_target(&mut self, context: &Context, extent: vk::Extent2D) -> Result<(), String> {
        if self.target.as_ref().is_some_and(|t| t.extent == extent) {
            return Ok(());
        }
        if let Some(old) = self.target.take() {
            old.destroy(&context.device);
        }
        self.target = Some(PickTarget::new(context, self.render_pass, extent)?);
        Ok(())
    }

    /// # Safety
    ///
    /// The device must be idle.
    pub unsafe fn destroy(&mut self, device: &ash::Device) {
        if let Some(target) = self.target.take() {
            target.destroy(device);
        }
        self.readback.destroy(device);
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.layout, None);
        device.destroy_render_pass(self.render_pass, None);
    }
}

impl PickTarget {
    unsafe fn new(
        context: &Context,
        render_pass: vk::RenderPass,
        extent: vk::Extent2D,
    ) -> Result<PickTarget, String> {
        let device = &context.device;
        // Colour attachment we also transfer from: not transient/lazy like the MSAA and depth
        // targets, because the whole point is to copy a pixel out to the host afterwards.
        let image_info = vk::ImageCreateInfo::default()
            .image_type(vk::ImageType::TYPE_2D)
            .format(PICK_FORMAT)
            .extent(vk::Extent3D { width: extent.width, height: extent.height, depth: 1 })
            .mip_levels(1)
            .array_layers(1)
            .samples(vk::SampleCountFlags::TYPE_1)
            .tiling(vk::ImageTiling::OPTIMAL)
            .usage(vk::ImageUsageFlags::COLOR_ATTACHMENT | vk::ImageUsageFlags::TRANSFER_SRC)
            .sharing_mode(vk::SharingMode::EXCLUSIVE)
            .initial_layout(vk::ImageLayout::UNDEFINED);
        let image = device
            .create_image(&image_info, None)
            .map_err(|e| format!("pick create_image {e:?}"))?;

        let requirements = device.get_image_memory_requirements(image);
        let properties =
            context.instance.get_physical_device_memory_properties(context.physical_device);
        let type_index = (0..properties.memory_type_count)
            .find(|&i| {
                requirements.memory_type_bits & (1 << i) != 0
                    && properties.memory_types[i as usize]
                        .property_flags
                        .contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)
            })
            .ok_or("no device-local memory type for the pick target")?;
        let allocate = vk::MemoryAllocateInfo::default()
            .allocation_size(requirements.size)
            .memory_type_index(type_index);
        let memory = match device.allocate_memory(&allocate, None) {
            Ok(m) => m,
            Err(e) => {
                device.destroy_image(image, None);
                return Err(format!("pick allocate_memory {e:?}"));
            }
        };
        if let Err(e) = device.bind_image_memory(image, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            return Err(format!("pick bind_image_memory {e:?}"));
        }

        let view_info = vk::ImageViewCreateInfo::default()
            .image(image)
            .view_type(vk::ImageViewType::TYPE_2D)
            .format(PICK_FORMAT)
            .subresource_range(vk::ImageSubresourceRange {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                base_mip_level: 0,
                level_count: 1,
                base_array_layer: 0,
                layer_count: 1,
            });
        let view = match device.create_image_view(&view_info, None) {
            Ok(v) => v,
            Err(e) => {
                device.free_memory(memory, None);
                device.destroy_image(image, None);
                return Err(format!("pick create_image_view {e:?}"));
            }
        };

        let framebuffer_info = vk::FramebufferCreateInfo::default()
            .render_pass(render_pass)
            .attachments(std::slice::from_ref(&view))
            .width(extent.width)
            .height(extent.height)
            .layers(1);
        let framebuffer = match device.create_framebuffer(&framebuffer_info, None) {
            Ok(f) => f,
            Err(e) => {
                device.destroy_image_view(view, None);
                device.free_memory(memory, None);
                device.destroy_image(image, None);
                return Err(format!("pick create_framebuffer {e:?}"));
            }
        };

        Ok(PickTarget { image, memory, view, framebuffer, extent })
    }

    unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_framebuffer(self.framebuffer, None);
        device.destroy_image_view(self.view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.memory, None);
    }
}

/// Build the pick pipeline: position-only unit quad, no blend (an integer attachment cannot
/// blend), no depth or stencil, writing the slot id.
unsafe fn build_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let vert = shader_module(device, PICK_VERT)?;
    let frag = match shader_module(device, PICK_FRAG) {
        Ok(m) => m,
        Err(e) => {
            device.destroy_shader_module(vert, None);
            return Err(e);
        }
    };

    let entry = c"main";
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(vert)
            .name(entry),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(frag)
            .name(entry),
    ];

    // The shared unit quad: two floats per vertex.
    let binding = vk::VertexInputBindingDescription::default()
        .binding(0)
        .stride(2 * std::mem::size_of::<f32>() as u32)
        .input_rate(vk::VertexInputRate::VERTEX);
    let attribute = vk::VertexInputAttributeDescription::default()
        .location(0)
        .binding(0)
        .format(vk::Format::R32G32_SFLOAT)
        .offset(0);
    let vertex_input = vk::PipelineVertexInputStateCreateInfo::default()
        .vertex_binding_descriptions(std::slice::from_ref(&binding))
        .vertex_attribute_descriptions(std::slice::from_ref(&attribute));
    let assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);

    let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let dynamic = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
    let viewport_state =
        vk::PipelineViewportStateCreateInfo::default().viewport_count(1).scissor_count(1);

    let rasterization = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    let multisample = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);

    // No blend: the attachment is R32_UINT and integer attachments cannot blend. Write all
    // components (only R exists in the format).
    let blend_attachment = vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(false)
        .color_write_mask(vk::ColorComponentFlags::RGBA);
    let blend = vk::PipelineColorBlendStateCreateInfo::default()
        .attachments(std::slice::from_ref(&blend_attachment));

    // The pass has no depth/stencil attachment, so this state is inert; provide a disabled one so
    // the pipeline is valid regardless.
    let depth_stencil = vk::PipelineDepthStencilStateCreateInfo::default()
        .depth_test_enable(false)
        .depth_write_enable(false)
        .stencil_test_enable(false);

    let info = vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&vertex_input)
        .input_assembly_state(&assembly)
        .viewport_state(&viewport_state)
        .rasterization_state(&rasterization)
        .multisample_state(&multisample)
        .depth_stencil_state(&depth_stencil)
        .color_blend_state(&blend)
        .dynamic_state(&dynamic)
        .layout(layout)
        .render_pass(render_pass)
        .subpass(0);

    let result = device.create_graphics_pipelines(
        vk::PipelineCache::null(),
        std::slice::from_ref(&info),
        None,
    );
    device.destroy_shader_module(vert, None);
    device.destroy_shader_module(frag, None);
    result.map(|pipelines| pipelines[0]).map_err(|(_, e)| format!("pick create_graphics_pipelines {e:?}"))
}

unsafe fn shader_module(device: &ash::Device, spirv: &[u8]) -> Result<vk::ShaderModule, String> {
    if spirv.len() % 4 != 0 || spirv.len() < 20 {
        return Err(format!("{} bytes is not a SPIR-V module", spirv.len()));
    }
    let mut words = Vec::with_capacity(spirv.len() / 4);
    for chunk in spirv.chunks_exact(4) {
        words.push(u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]));
    }
    let info = vk::ShaderModuleCreateInfo::default().code(&words);
    device.create_shader_module(&info, None).map_err(|e| format!("pick create_shader_module {e:?}"))
}
