//! The compute pipelines, and the one descriptor set they all share.
//!
//! # One layout, one descriptor set, written once
//!
//! Every shader declares the same two bindings — the activation arena at 0, the weights
//! at 1 — and the same [`Push`] block. So there is one descriptor set layout, one
//! pipeline layout, one descriptor set, and after setup nothing is ever rebound: a layer
//! is `vkCmdBindPipeline` plus `vkCmdPushConstants` plus `vkCmdDispatch`.
//!
//! That is only possible because a tensor is an *element offset* into the arena rather
//! than its own buffer. Binding per-tensor would mean a descriptor set per layer, 350 of
//! them for U^2-Netp, a pool to allocate them from, and `minStorageBufferOffsetAlignment`
//! to respect — all to express something a `u32` already says.
//!
//! # SPIR-V is linked in, not shipped
//!
//! `build.rs` compiles `shaders/*.comp` into `$OUT_DIR` and panics if `glslc` is missing
//! or a shader fails, so these `include_bytes!` cannot silently become stubs the way
//! `games/voxels` does. An asset can be absent at run time; a `&'static [u8]` cannot.

use ash::vk;

use crate::nets::{Kind, Push};

use super::context::Context;

const CONV: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/conv.comp.spv"));
const CONV_TRANSPOSE: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/conv_transpose.comp.spv"));
const MAXPOOL: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/maxpool.comp.spv"));
const AVGPOOL: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/avgpool.comp.spv"));
const RESIZE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/resize.comp.spv"));
const RESIZE_NEAREST: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/resize_nearest.comp.spv"));
const GAP: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gap.comp.spv"));
const ADD: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/add.comp.spv"));
const MUL_BCAST: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/mul_bcast.comp.spv"));
const AFFINE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/affine.comp.spv"));
const LAYERNORM: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/layernorm.comp.spv"));
const ATTN_SCORES: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/attn_scores.comp.spv"));
const SOFTMAX: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/softmax.comp.spv"));
const ATTN_APPLY: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/attn_apply.comp.spv"));
const LEAKY_RELU: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/leaky_relu.comp.spv"));
const ATTN_SCORES_RELATIVE: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/attn_scores_relative.comp.spv"));
const ATTN_APPLY_RELATIVE: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/attn_apply_relative.comp.spv"));
const EMBED: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/embed.comp.spv"));
const GATED_TANH: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/gated_tanh.comp.spv"));
const MUL: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/mul.comp.spv"));
const CONV_POINT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/conv_point.comp.spv"));
const CONV_INT8: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/conv_int8.comp.spv"));
const FLIP_CHANNELS: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/flip_channels.comp.spv"));

/// Every shader, in the order [`Pipelines::create`] destructures them.
const SPIRV: [&[u8]; 23] = [
    CONV,
    CONV_TRANSPOSE,
    MAXPOOL,
    AVGPOOL,
    RESIZE,
    RESIZE_NEAREST,
    GAP,
    ADD,
    MUL_BCAST,
    AFFINE,
    LAYERNORM,
    ATTN_SCORES,
    SOFTMAX,
    ATTN_APPLY,
    LEAKY_RELU,
    ATTN_SCORES_RELATIVE,
    ATTN_APPLY_RELATIVE,
    EMBED,
    GATED_TANH,
    FLIP_CHANNELS,
    CONV_INT8,
    CONV_POINT,
    MUL,
];

/// `local_size_x` in `shaders/common.glsl`. A dispatch covers `ceil(invocations / this)`
/// workgroups, and each shader bails on the over-dispatched tail.
pub const WORKGROUP: u32 = 64;

/// The largest workgroup count per dimension the Vulkan spec *guarantees*
/// (`maxComputeWorkGroupCount`).
///
/// U^2-Netp's widest layer needs 102,400 workgroups, so a 1D dispatch would exceed this on
/// any device at the guaranteed minimum. `run::Net::record` splits the count across x and y
/// and `global_index()` in `shaders/common.glsl` flattens it back. Using the guaranteed
/// floor unconditionally rather than querying the device keeps one code path.
pub const MAX_WORKGROUPS_PER_DIM: u32 = 65_535;

/// Every compute pipeline, plus the layout and descriptor set they share.
pub struct Pipelines {
    /// Shared by all of them, so a bind never invalidates push constants.
    pub layout: vk::PipelineLayout,
    /// The one set: arena at binding 0, weights at binding 1.
    pub descriptor_set: vk::DescriptorSet,
    descriptor_layout: vk::DescriptorSetLayout,
    descriptor_pool: vk::DescriptorPool,
    conv: vk::Pipeline,
    conv_transpose: vk::Pipeline,
    maxpool: vk::Pipeline,
    avgpool: vk::Pipeline,
    resize: vk::Pipeline,
    resize_nearest: vk::Pipeline,
    gap: vk::Pipeline,
    add: vk::Pipeline,
    mul_bcast: vk::Pipeline,
    affine: vk::Pipeline,
    layernorm: vk::Pipeline,
    attn_scores: vk::Pipeline,
    softmax: vk::Pipeline,
    attn_apply: vk::Pipeline,
    leaky_relu: vk::Pipeline,
    attn_scores_relative: vk::Pipeline,
    attn_apply_relative: vk::Pipeline,
    embed: vk::Pipeline,
    gated_tanh: vk::Pipeline,
    flip_channels: vk::Pipeline,
    conv_int8: vk::Pipeline,
    conv_point: vk::Pipeline,
    mul: vk::Pipeline,
}

impl Pipelines {
    /// Build all of them and point the descriptor set at `arena` and `weights`.
    pub fn new(
        context: &Context,
        arena: vk::Buffer,
        arena_size: vk::DeviceSize,
        weights: vk::Buffer,
        weights_size: vk::DeviceSize,
    ) -> Result<Pipelines, String> {
        // SAFETY: every handle created below is destroyed by `Pipelines::destroy`, or on
        // the failure path here before returning.
        unsafe { Self::create(context, arena, arena_size, weights, weights_size) }
    }

    unsafe fn create(
        context: &Context,
        arena: vk::Buffer,
        arena_size: vk::DeviceSize,
        weights: vk::Buffer,
        weights_size: vk::DeviceSize,
    ) -> Result<Pipelines, String> {
        let device = &context.device;

        let bindings = [
            vk::DescriptorSetLayoutBinding::default()
                .binding(0)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
            vk::DescriptorSetLayoutBinding::default()
                .binding(1)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
            // The weights again, viewed as 32-bit words so int8 tensors can be unpacked
            // without `VK_KHR_8bit_storage`. Same buffer as binding 1; see `common.glsl`.
            vk::DescriptorSetLayoutBinding::default()
                .binding(2)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::COMPUTE),
        ];
        let descriptor_layout = device
            .create_descriptor_set_layout(
                &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                None,
            )
            .map_err(|e| format!("create_descriptor_set_layout {e:?}"))?;

        let mut cleanup = Cleanup::new(device);
        cleanup.descriptor_layout = Some(descriptor_layout);

        let pool_sizes = [vk::DescriptorPoolSize::default()
            .ty(vk::DescriptorType::STORAGE_BUFFER)
            .descriptor_count(3)];
        let descriptor_pool = device
            .create_descriptor_pool(
                &vk::DescriptorPoolCreateInfo::default().max_sets(1).pool_sizes(&pool_sizes),
                None,
            )
            .map_err(|e| format!("create_descriptor_pool {e:?}"))?;
        cleanup.descriptor_pool = Some(descriptor_pool);

        let layouts = [descriptor_layout];
        let sets = device
            .allocate_descriptor_sets(
                &vk::DescriptorSetAllocateInfo::default()
                    .descriptor_pool(descriptor_pool)
                    .set_layouts(&layouts),
            )
            .map_err(|e| format!("allocate_descriptor_sets {e:?}"))?;
        let descriptor_set = match sets.first() {
            Some(&s) => s,
            None => return Err("allocate_descriptor_sets returned nothing".into()),
        };

        let arena_info = vk::DescriptorBufferInfo::default()
            .buffer(arena)
            .offset(0)
            .range(arena_size);
        let weights_info = vk::DescriptorBufferInfo::default()
            .buffer(weights)
            .offset(0)
            .range(weights_size);
        let writes = [
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor_set)
                .dst_binding(0)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(std::slice::from_ref(&arena_info)),
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor_set)
                .dst_binding(1)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(std::slice::from_ref(&weights_info)),
            // Binding 2 is the same buffer as binding 1, viewed as 32-bit words so that int8
            // tensors can be unpacked. Both are read-only, so aliasing them is safe.
            vk::WriteDescriptorSet::default()
                .dst_set(descriptor_set)
                .dst_binding(2)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(std::slice::from_ref(&weights_info)),
        ];
        device.update_descriptor_sets(&writes, &[]);

        let push_range = vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::COMPUTE)
            .offset(0)
            .size(std::mem::size_of::<Push>() as u32);
        let layout = device
            .create_pipeline_layout(
                &vk::PipelineLayoutCreateInfo::default()
                    .set_layouts(&layouts)
                    .push_constant_ranges(std::slice::from_ref(&push_range)),
                None,
            )
            .map_err(|e| format!("create_pipeline_layout {e:?}"))?;
        cleanup.layout = Some(layout);

        let mut built = Vec::new();
        for spirv in SPIRV {
            match compute_pipeline(device, layout, spirv) {
                Ok(pipeline) => built.push(pipeline),
                Err(e) => {
                    for pipeline in built {
                        device.destroy_pipeline(pipeline, None);
                    }
                    return Err(e);
                }
            }
        }
        // A fixed-size array rather than a slice pattern of thirteen bindings, so
        // adding a shader is one entry in `SPIRV` and one name here.
        let [
            conv,
            conv_transpose,
            maxpool,
            avgpool,
            resize,
            resize_nearest,
            gap,
            add,
            mul_bcast,
            affine,
            layernorm,
            attn_scores,
            softmax,
            attn_apply,
            leaky_relu,
            attn_scores_relative,
            attn_apply_relative,
            embed,
            gated_tanh,
            flip_channels,
            conv_int8,
            conv_point,
            mul,
        ] = match <[vk::Pipeline; SPIRV.len()]>::try_from(built) {
            Ok(all) => all,
            Err(built) => {
                for pipeline in built {
                    device.destroy_pipeline(pipeline, None);
                }
                return Err("wrong pipeline count".into());
            }
        };

        cleanup.disarm();
        Ok(Pipelines {
            layout,
            descriptor_set,
            descriptor_layout,
            descriptor_pool,
            conv,
            conv_transpose,
            maxpool,
            avgpool,
            resize,
            resize_nearest,
            gap,
            add,
            mul_bcast,
            affine,
            layernorm,
            attn_scores,
            softmax,
            attn_apply,
            leaky_relu,
            attn_scores_relative,
            attn_apply_relative,
            embed,
            gated_tanh,
            flip_channels,
            conv_int8,
            conv_point,
            mul,
        })
    }

    /// The pipeline a plan's [`Kind`] wants.
    pub fn for_kind(&self, kind: Kind) -> vk::Pipeline {
        match kind {
            Kind::Conv => self.conv,
            Kind::ConvTranspose => self.conv_transpose,
            Kind::MaxPool => self.maxpool,
            Kind::AvgPool => self.avgpool,
            Kind::Resize => self.resize,
            Kind::ResizeNearest => self.resize_nearest,
            Kind::GlobalAvgPool => self.gap,
            Kind::Add => self.add,
            Kind::MulBroadcast => self.mul_bcast,
            Kind::Affine => self.affine,
            Kind::LayerNorm => self.layernorm,
            Kind::AttnScores => self.attn_scores,
            Kind::Softmax => self.softmax,
            Kind::AttnApply => self.attn_apply,
            Kind::LeakyRelu => self.leaky_relu,
            Kind::AttnScoresRelative => self.attn_scores_relative,
            Kind::AttnApplyRelative => self.attn_apply_relative,
            Kind::Embed => self.embed,
            Kind::GatedTanh => self.gated_tanh,
            Kind::FlipChannels => self.flip_channels,
            Kind::ConvInt8 => self.conv_int8,
            Kind::ConvPoint => self.conv_point,
            Kind::Mul => self.mul,
        }
    }

    /// # Safety
    ///
    /// The device must be idle.
    pub unsafe fn destroy(&self, device: &ash::Device) {
        for pipeline in [
            self.conv,
            self.conv_transpose,
            self.maxpool,
            self.avgpool,
            self.resize,
            self.resize_nearest,
            self.gap,
            self.add,
            self.mul_bcast,
            self.affine,
            self.layernorm,
            self.attn_scores,
            self.softmax,
            self.attn_apply,
            self.leaky_relu,
            self.attn_scores_relative,
            self.attn_apply_relative,
            self.embed,
            self.gated_tanh,
            self.flip_channels,
            self.conv_int8,
            self.conv_point,
            self.mul,
        ] {
            device.destroy_pipeline(pipeline, None);
        }
        device.destroy_pipeline_layout(self.layout, None);
        // The set is freed with its pool.
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_layout, None);
    }
}

/// Undoes a partially-built [`Pipelines`] on any early return.
///
/// There are five handles created in sequence and each failure point has to destroy a
/// different subset of them. A guard whose [`Drop`] does it means the fallible calls can
/// keep using `?` instead of nesting five `match`es, and no future insertion between two
/// of them can forget a leak.
struct Cleanup<'a> {
    device: &'a ash::Device,
    armed: bool,
    descriptor_layout: Option<vk::DescriptorSetLayout>,
    descriptor_pool: Option<vk::DescriptorPool>,
    layout: Option<vk::PipelineLayout>,
}

impl<'a> Cleanup<'a> {
    fn new(device: &'a ash::Device) -> Cleanup<'a> {
        Cleanup {
            device,
            armed: true,
            descriptor_layout: None,
            descriptor_pool: None,
            layout: None,
        }
    }

    fn disarm(&mut self) {
        self.armed = false;
    }
}

impl Drop for Cleanup<'_> {
    fn drop(&mut self) {
        if !self.armed {
            return;
        }
        // SAFETY: nothing has been submitted, so no command buffer references any of
        // these; and each is destroyed at most once because `disarm` is called on
        // success.
        unsafe {
            if let Some(layout) = self.layout {
                self.device.destroy_pipeline_layout(layout, None);
            }
            if let Some(pool) = self.descriptor_pool {
                self.device.destroy_descriptor_pool(pool, None);
            }
            if let Some(layout) = self.descriptor_layout {
                self.device.destroy_descriptor_set_layout(layout, None);
            }
        }
    }
}

unsafe fn compute_pipeline(
    device: &ash::Device,
    layout: vk::PipelineLayout,
    spirv: &[u8],
) -> Result<vk::Pipeline, String> {
    let module = shader_module(device, spirv)?;
    let stage = vk::PipelineShaderStageCreateInfo::default()
        .stage(vk::ShaderStageFlags::COMPUTE)
        .module(module)
        .name(c"main");
    let info = vk::ComputePipelineCreateInfo::default().stage(stage).layout(layout);
    let result = device
        .create_compute_pipelines(vk::PipelineCache::null(), std::slice::from_ref(&info), None)
        .map_err(|(_, e)| format!("create_compute_pipelines {e:?}"))
        .and_then(|pipelines| {
            pipelines
                .first()
                .copied()
                .ok_or_else(|| "create_compute_pipelines returned nothing".to_string())
        });
    // The module is only needed while the pipeline is being created.
    device.destroy_shader_module(module, None);
    result
}

unsafe fn shader_module(device: &ash::Device, spirv: &[u8]) -> Result<vk::ShaderModule, String> {
    // SPIR-V is a stream of 32-bit words, and `vkShaderModuleCreateInfo` wants it as
    // `*const u32`. Reassembling rather than casting the `&[u8]`: `include_bytes!` gives
    // no alignment guarantee, and a misaligned `u32` read is undefined behaviour even
    // where the hardware tolerates it. This is `library/map`'s approach, not
    // `games/voxels`' pointer cast.
    if !spirv.len().is_multiple_of(4) || spirv.len() < 20 {
        return Err(format!("{} bytes is not a SPIR-V module", spirv.len()));
    }
    let mut words = Vec::with_capacity(spirv.len() / 4);
    for chunk in spirv.chunks_exact(4) {
        words.push(u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]));
    }
    device
        .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words), None)
        .map_err(|e| format!("create_shader_module {e:?}"))
}
