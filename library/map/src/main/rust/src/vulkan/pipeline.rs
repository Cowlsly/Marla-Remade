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
use crate::tile::symbol;
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
const SYMBOL_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/symbol.vert.spv"));
const SYMBOL_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/symbol.frag.spv"));
const SPRITE_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/sprite.frag.spv"));
const PUCK_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/puck.vert.spv"));
const PUCK_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/puck.frag.spv"));

pub struct Pipelines {
    pub layout: vk::PipelineLayout,
    /// Layout with the atlas descriptor set (set 0) for the symbol pipeline.
    pub symbol_layout: vk::PipelineLayout,
    pub fill: vk::Pipeline,
    pub line: vk::Pipeline,
    pub symbol: vk::Pipeline,
    /// POI icons. Same vertex format, same push block and the same
    /// [`symbol_layout`](Self::symbol_layout) as [`symbol`](Self::symbol) — only the
    /// fragment shader differs, because an icon is a picture and a glyph is a distance
    /// field. Sharing the layout is what lets the renderer swap atlases with a descriptor
    /// bind instead of a second pipeline layout.
    pub sprite: vk::Pipeline,
    /// Screen-anchored overlay quads — today only the user puck. Takes the push-only
    /// [`layout`](Self::layout), not [`symbol_layout`](Self::symbol_layout), because it
    /// samples nothing: the puck is drawn analytically from a distance and an angle, so
    /// there is no atlas and no descriptor set.
    ///
    /// Built here rather than bolted onto the renderer so it survives `Renderer::rebuild`,
    /// which destroys and remakes every `Pipelines` on each resize and rotation.
    pub puck: vk::Pipeline,
    /// The region mask: draws the selected region's tessellated shape into the stencil and no
    /// colour at all, so [`scrim`](Self::scrim) can skip those pixels.
    ///
    /// A stencil rather than a clipped polygon because a region arrives as one clipped piece per
    /// tile: rasterising them all into the same stencil unions them for free, whereas cutting the
    /// region out of a viewport quad geometrically needs a polygon boolean, which is what two
    /// earlier attempts at exactly this foundered on.
    pub mask: vk::Pipeline,
    /// The dimming scrim, drawn over the whole viewport wherever the stencil is still zero.
    pub scrim: vk::Pipeline,
}

impl Pipelines {
    /// # Safety
    ///
    /// `render_pass` must outlive these pipelines. `atlas_layout` is the
    /// descriptor set layout [`images::AtlasSet`] built — `None` on a host
    /// build that never creates pipelines (tests link this module for the
    /// [`Push`] size asserts only).
    pub unsafe fn new(
        device: &ash::Device,
        render_pass: vk::RenderPass,
        samples: vk::SampleCountFlags,
        atlas_layout: Option<vk::DescriptorSetLayout>,
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
        let symbol_vert = shader_module(device, SYMBOL_VERT)?;
        let symbol_frag = shader_module(device, SYMBOL_FRAG)?;
        let sprite_frag = shader_module(device, SPRITE_FRAG)?;
        let puck_vert = shader_module(device, PUCK_VERT)?;
        let puck_frag = shader_module(device, PUCK_FRAG)?;

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
        // Symbol: position (tile-local) + uv (atlas), 4 floats.
        let symbol_attributes = [
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
        ];

        let fill = build(
            device,
            layout,
            render_pass,
            samples,
            fill_vert,
            fill_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
            Stencil::Ignore,
        );
        let line = build(
            device,
            layout,
            render_pass,
            samples,
            line_vert,
            line_frag,
            (stroke::FLOATS_PER_VERTEX * 4) as u32,
            &line_attributes,
            Stencil::Ignore,
        );

        // The symbol pipeline needs the atlas descriptor set, so it gets its own
        // layout: same push-constant range plus set 0. The sprite pipeline reuses this
        // layout with a second set from the same pool.
        let symbol_layout = match atlas_layout {
            Some(set_layout) => {
                let symbol_layout_info = vk::PipelineLayoutCreateInfo::default()
                    .push_constant_ranges(std::slice::from_ref(&push_range))
                    .set_layouts(std::slice::from_ref(&set_layout));
                device
                    .create_pipeline_layout(&symbol_layout_info, None)
                    .map_err(|e| format!("create_pipeline_layout(symbol) {e:?}"))?
            }
            None => {
                device.destroy_shader_module(fill_vert, None);
                device.destroy_shader_module(fill_frag, None);
                device.destroy_shader_module(line_vert, None);
                device.destroy_shader_module(line_frag, None);
                device.destroy_shader_module(symbol_vert, None);
                device.destroy_shader_module(symbol_frag, None);
                device.destroy_shader_module(sprite_frag, None);
                device.destroy_shader_module(puck_vert, None);
                device.destroy_shader_module(puck_frag, None);
                device.destroy_pipeline_layout(layout, None);
                return Err("symbol pipeline needs an atlas descriptor set layout".into());
            }
        };
        let symbol = build(
            device,
            symbol_layout,
            render_pass,
            samples,
            symbol_vert,
            symbol_frag,
            (symbol::FLOATS_PER_VERTEX * 4) as u32,
            &symbol_attributes,
            Stencil::Ignore,
        );
        let sprite = build(
            device,
            symbol_layout,
            render_pass,
            samples,
            symbol_vert,
            sprite_frag,
            (symbol::FLOATS_PER_VERTEX * 4) as u32,
            &symbol_attributes,
            Stencil::Ignore,
        );

        // The overlay quad is position-only in -1..1, so it shares the fill vertex
        // format and its 8-byte stride.
        let puck = build(
            device,
            layout,
            render_pass,
            samples,
            puck_vert,
            puck_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
            Stencil::Ignore,
        );

        // The region mask and its scrim. Both are position-only quads/triangles in the same
        // vertex format as `fill`: the mask draws the region's tessellated shape, the scrim a
        // full-viewport quad that the stencil keeps off the region itself.
        let mask = build(
            device,
            layout,
            render_pass,
            samples,
            fill_vert,
            fill_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
            Stencil::Write,
        );
        let scrim = build(
            device,
            layout,
            render_pass,
            samples,
            fill_vert,
            fill_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
            Stencil::TestOutside,
        );

        // The modules are only needed while the pipelines are being created.
        device.destroy_shader_module(fill_vert, None);
        device.destroy_shader_module(fill_frag, None);
        device.destroy_shader_module(line_vert, None);
        device.destroy_shader_module(line_frag, None);
        device.destroy_shader_module(symbol_vert, None);
        device.destroy_shader_module(symbol_frag, None);
        device.destroy_shader_module(sprite_frag, None);
        device.destroy_shader_module(puck_vert, None);
        device.destroy_shader_module(puck_frag, None);

        match (fill, line, symbol, sprite, puck, mask, scrim) {
            (Ok(fill), Ok(line), Ok(symbol), Ok(sprite), Ok(puck), Ok(mask), Ok(scrim)) => Ok(
                Pipelines {
                    layout,
                    symbol_layout,
                    fill,
                    line,
                    symbol,
                    sprite,
                    puck,
                    mask,
                    scrim,
                },
            ),
            (fill, line, symbol, sprite, puck, mask, scrim) => {
                for created in
                    [fill, line, symbol, sprite, puck, mask, scrim].into_iter().flatten()
                {
                    device.destroy_pipeline(created, None);
                }
                device.destroy_pipeline_layout(symbol_layout, None);
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
        device.destroy_pipeline(self.symbol, None);
        device.destroy_pipeline(self.sprite, None);
        device.destroy_pipeline(self.puck, None);
        device.destroy_pipeline(self.mask, None);
        device.destroy_pipeline(self.scrim, None);
        device.destroy_pipeline_layout(self.symbol_layout, None);
        device.destroy_pipeline_layout(self.layout, None);
    }
}

/// How a pipeline uses the stencil attachment.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Stencil {
    /// Neither tests nor writes: every pipeline that draws the map itself.
    Ignore,
    /// Writes 1 wherever it draws, and writes no colour. The region mask.
    Write,
    /// Draws only where the stencil is still 0 — outside the region. The scrim.
    TestOutside,
}

unsafe fn build(
    device: &ash::Device,
    layout: vk::PipelineLayout,
    render_pass: vk::RenderPass,
    samples: vk::SampleCountFlags,
    vertex: vk::ShaderModule,
    fragment: vk::ShaderModule,
    stride: u32,
    attributes: &[vk::VertexInputAttributeDescription],
    stencil: Stencil,
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
    // Must match the render pass's colour attachment, which `swapchain` chooses from
    // what the device supports.
    let multisample =
        vk::PipelineMultisampleStateCreateInfo::default().rasterization_samples(samples);

    // Straight src-alpha over one-minus-src-alpha. Layer order does the rest.
    let blend_attachment = vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(vk::BlendFactor::SRC_ALPHA)
        .dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(vk::BlendFactor::ONE)
        .dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .alpha_blend_op(vk::BlendOp::ADD)
        // The mask writes stencil only. Letting it write colour would paint the region's
        // triangles over the map in whatever the fill shader produced.
        .color_write_mask(if stencil == Stencil::Write {
            vk::ColorComponentFlags::empty()
        } else {
            vk::ColorComponentFlags::RGBA
        });
    let blend = vk::PipelineColorBlendStateCreateInfo::default()
        .attachments(std::slice::from_ref(&blend_attachment));

    // A subpass with a depth-stencil attachment requires this state on every pipeline, even the
    // ones that ignore it. Depth is off throughout — see `swapchain`'s "# No depth buffer".
    let stencil_op = match stencil {
        Stencil::Ignore => vk::StencilOpState::default(),
        // `REPLACE` with reference 1 rather than increment: the region's tile pieces overlap at
        // shared edges, and any counting op would disagree with itself there.
        Stencil::Write => vk::StencilOpState::default()
            .compare_op(vk::CompareOp::ALWAYS)
            .pass_op(vk::StencilOp::REPLACE)
            .fail_op(vk::StencilOp::REPLACE)
            .depth_fail_op(vk::StencilOp::REPLACE)
            .compare_mask(0xff)
            .write_mask(0xff)
            .reference(1),
        Stencil::TestOutside => vk::StencilOpState::default()
            .compare_op(vk::CompareOp::NOT_EQUAL)
            .pass_op(vk::StencilOp::KEEP)
            .fail_op(vk::StencilOp::KEEP)
            .depth_fail_op(vk::StencilOp::KEEP)
            .compare_mask(0xff)
            .write_mask(0)
            .reference(1),
    };
    let depth_stencil = vk::PipelineDepthStencilStateCreateInfo::default()
        .depth_test_enable(false)
        .depth_write_enable(false)
        .stencil_test_enable(stencil != Stencil::Ignore)
        .front(stencil_op)
        .back(stencil_op);

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
