//! The fill and line pipelines, built from the SPIR-V `build.rs` compiled.
//!
//! # Push constants, and no descriptor sets at all
//!
//! Everything per-draw — the tile's clip matrix, the layer's colour, width, gap and dash
//! — is 112 bytes, inside the 128 the Vulkan spec guarantees for push constants. So there
//! are no uniform buffers, no descriptor set layouts, no descriptor pool and nothing to
//! keep in sync with the camera. `vkCmdPushConstants` before each draw is the whole
//! per-draw state.
//!
//! This is a deliberate departure from the WebGPU design that preceded it, which used a
//! uniform buffer per tile plus a bind group per (tile, layer) so that pre-recorded
//! render bundles could be replayed while the camera moved. Bundles existed to avoid
//! WebGPU's per-draw validation cost; raw Vulkan does not have that cost, so the
//! simplest thing that works is to re-record the command buffer each frame. Roughly 600
//! draws at a few `vkCmd` calls each is a fraction of a millisecond. If profiling says
//! otherwise, secondary command buffers are the escape hatch — and they would need the
//! descriptor-set indirection back, because push constants are not inherited.

use crate::tess::{fill, stroke};
use ash::vk;
use std::ffi::CStr;

/// Bytes of push constant: `mat4` + three `vec4`.
pub const PUSH_CONSTANT_BYTES: u32 = 64 + 16 + 16 + 16;

/// The push constant block, matching the `Push` block the shaders declare.
///
/// `repr(C)` so the field order is the declaration order, which is what the SPIR-V
/// offsets assume.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct Push {
    /// Column-major tile-local 0..1 to clip space.
    pub tile_to_clip: [f32; 16],
    /// Linear RGBA, 0..1.
    pub color: [f32; 4],
    /// `half_width_px, gap_half_px, dash_on, dash_off`.
    pub line: [f32; 4],
    /// `tile_px, 0, 0, 0`.
    pub misc: [f32; 4],
}

impl Push {
    /// The block as bytes, for `vkCmdPushConstants`.
    pub fn as_bytes(&self) -> &[u8] {
        // Safe: `repr(C)` POD with no padding, read as its own length.
        unsafe {
            std::slice::from_raw_parts(
                self as *const Push as *const u8,
                std::mem::size_of::<Push>(),
            )
        }
    }
}

const FILL_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/fill.vert.spv"));
const FILL_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/fill.frag.spv"));
const LINE_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/line.vert.spv"));
const LINE_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/line.frag.spv"));

pub struct Pipelines {
    pub layout: vk::PipelineLayout,
    pub fill: vk::Pipeline,
    pub line: vk::Pipeline,
}

impl Pipelines {
    /// # Safety
    ///
    /// `render_pass` must outlive these pipelines.
    pub unsafe fn new(
        device: &ash::Device,
        render_pass: vk::RenderPass,
    ) -> Result<Pipelines, String> {
        let push_range = vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
            .offset(0)
            .size(PUSH_CONSTANT_BYTES);
        let layout_info = vk::PipelineLayoutCreateInfo::default()
            .push_constant_ranges(std::slice::from_ref(&push_range));
        let layout = device
            .create_pipeline_layout(&layout_info, None)
            .map_err(|e| format!("create_pipeline_layout {e:?}"))?;

        let fill_vert = shader_module(device, FILL_VERT)?;
        let fill_frag = shader_module(device, FILL_FRAG)?;
        let line_vert = shader_module(device, LINE_VERT)?;
        let line_frag = shader_module(device, LINE_FRAG)?;

        let fill_attributes = [vk::VertexInputAttributeDescription::default()
            .location(0)
            .binding(0)
            .format(vk::Format::R32G32_SFLOAT)
            .offset(0)];
        let line_attributes = [
            vk::VertexInputAttributeDescription::default()
                .location(0)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(0),
            vk::VertexInputAttributeDescription::default()
                .location(1)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(8),
            vk::VertexInputAttributeDescription::default()
                .location(2)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(16),
            vk::VertexInputAttributeDescription::default()
                .location(3)
                .binding(0)
                .format(vk::Format::R32_SFLOAT)
                .offset(24),
        ];

        let fill = build(
            device,
            layout,
            render_pass,
            fill_vert,
            fill_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
        );
        let line = build(
            device,
            layout,
            render_pass,
            line_vert,
            line_frag,
            (stroke::FLOATS_PER_VERTEX * 4) as u32,
            &line_attributes,
        );

        // The modules are only needed while the pipelines are being created.
        device.destroy_shader_module(fill_vert, None);
        device.destroy_shader_module(fill_frag, None);
        device.destroy_shader_module(line_vert, None);
        device.destroy_shader_module(line_frag, None);

        match (fill, line) {
            (Ok(fill), Ok(line)) => Ok(Pipelines { layout, fill, line }),
            (fill, line) => {
                if let Ok(p) = fill {
                    device.destroy_pipeline(p, None);
                }
                if let Ok(p) = line {
                    device.destroy_pipeline(p, None);
                }
                device.destroy_pipeline_layout(layout, None);
                Err("pipeline creation failed".into())
            }
        }
    }

    /// # Safety
    ///
    /// The device must be idle.
    pub unsafe fn destroy(&self, device: &ash::Device) {
        device.destroy_pipeline(self.fill, None);
        device.destroy_pipeline(self.line, None);
        device.destroy_pipeline_layout(self.layout, None);
    }
}

unsafe fn build(
    device: &ash::Device,
    layout: vk::PipelineLayout,
    render_pass: vk::RenderPass,
    vertex: vk::ShaderModule,
    fragment: vk::ShaderModule,
    stride: u32,
    attributes: &[vk::VertexInputAttributeDescription],
) -> Result<vk::Pipeline, String> {
    let entry = c"main";
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(vertex)
            .name(entry),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(fragment)
            .name(entry),
    ];

    let binding = vk::VertexInputBindingDescription::default()
        .binding(0)
        .stride(stride)
        .input_rate(vk::VertexInputRate::VERTEX);
    let vertex_input = vk::PipelineVertexInputStateCreateInfo::default()
        .vertex_binding_descriptions(std::slice::from_ref(&binding))
        .vertex_attribute_descriptions(attributes);
    let assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);

    // Viewport and scissor are dynamic, so a resize does not rebuild the pipeline — only
    // the swapchain.
    let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let dynamic = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
    let viewport_state =
        vk::PipelineViewportStateCreateInfo::default().viewport_count(1).scissor_count(1);

    // No culling: tessellated tile geometry arrives in whatever winding the clipper left
    // it in, and a culled road is an invisible road.
    let rasterization = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    let multisample = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);

    // Straight src-alpha over one-minus-src-alpha. Layer order does the rest.
    let blend_attachment = vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(vk::BlendFactor::SRC_ALPHA)
        .dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(vk::BlendFactor::ONE)
        .dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .alpha_blend_op(vk::BlendOp::ADD)
        .color_write_mask(vk::ColorComponentFlags::RGBA);
    let blend = vk::PipelineColorBlendStateCreateInfo::default()
        .attachments(std::slice::from_ref(&blend_attachment));

    let info = vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&vertex_input)
        .input_assembly_state(&assembly)
        .viewport_state(&viewport_state)
        .rasterization_state(&rasterization)
        .multisample_state(&multisample)
        .color_blend_state(&blend)
        .dynamic_state(&dynamic)
        .layout(layout)
        .render_pass(render_pass)
        .subpass(0);

    device
        .create_graphics_pipelines(vk::PipelineCache::null(), std::slice::from_ref(&info), None)
        .map(|pipelines| pipelines[0])
        .map_err(|(_, e)| format!("create_graphics_pipelines {e:?}"))
}

unsafe fn shader_module(device: &ash::Device, spirv: &[u8]) -> Result<vk::ShaderModule, String> {
    // SPIR-V is a stream of 32-bit words. `build.rs` guarantees a real module, so a
    // misaligned length here would be a build-system bug rather than bad input.
    if spirv.len() % 4 != 0 || spirv.len() < 20 {
        return Err(format!("{} bytes is not a SPIR-V module", spirv.len()));
    }
    let mut words = Vec::with_capacity(spirv.len() / 4);
    for chunk in spirv.chunks_exact(4) {
        words.push(u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]));
    }
    let info = vk::ShaderModuleCreateInfo::default().code(&words);
    device.create_shader_module(&info, None).map_err(|e| format!("create_shader_module {e:?}"))
}

/// Unused, but kept so the entry-point name is stated once.
#[allow(dead_code)]
const ENTRY_POINT: &CStr = c"main";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_push_block_is_inside_the_guaranteed_limit() {
        // 128 bytes is the minimum `maxPushConstantsSize` the spec requires, so staying
        // under it means no device can reject this.
        assert_eq!(std::mem::size_of::<Push>() as u32, PUSH_CONSTANT_BYTES);
        assert!(PUSH_CONSTANT_BYTES <= 128, "{PUSH_CONSTANT_BYTES} exceeds the guaranteed 128");
    }

    #[test]
    fn the_push_block_has_no_padding() {
        // The shader reads it at fixed offsets, so a gap Rust inserted would silently
        // shift the colour and the widths.
        assert_eq!(std::mem::size_of::<Push>(), 64 + 16 + 16 + 16);
        assert_eq!(std::mem::align_of::<Push>(), 4);
    }
}
