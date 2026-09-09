//! The swapchain, its render pass and framebuffers.
//!
//! # No depth buffer
//!
//! A 2D map has nothing to occlude: correctness comes from **draw order** — layer-major
//! across tiles — and from alpha blending, not from depth testing. Leaving the depth
//! attachment out saves a full-screen image, its allocation, and the bandwidth of
//! clearing and storing it every frame. On a tile-based mobile GPU that bandwidth is the
//! scarce resource.
//!
//! # Multisampling
//!
//! Rendering was single-sampled, and with no coverage term in any shader that made every
//! polygon edge a staircase — coastlines, park boundaries and building corners all
//! stepped a pixel at a time, which is most of what read as the renderer being lower
//! resolution than MapLibre. The colour attachment is now multisampled and resolved into
//! the swapchain image at the end of the subpass.
//!
//! The bandwidth argument above still holds, which is why the multisampled image is
//! `TRANSIENT_ATTACHMENT` + `LAZILY_ALLOCATED` where the driver offers it: on a
//! tile-based GPU the samples live and die inside tile memory and never reach main
//! memory, so the resolve is close to free and the image needs no real backing store.
//! `store_op` is `DONT_CARE` for the same reason — only the resolve target is kept.
//!
//! # Two hard-won details carried over from `games/voxels`
//!
//! * **`B8G8R8A8_UNORM` in preference to any `SRGB` format.** `voxels/swapchain.rs:39`
//!   records that Pixel's gralloc rejects the SRGB format and the result is a black
//!   screen.
//! * **A classic `VkRenderPass`, not `KHR_dynamic_rendering`.**
//!   `voxels/swapchain.rs:74` records the crash — "Unable to load cmd_begin_rendering" —
//!   from assuming the extension is present.

use crate::vulkan::context::Context;
use ash::vk;

/// How many samples per pixel to ask for.
///
/// Four is the usual sweet spot and the level mobile GPUs implement most efficiently;
/// eight costs proportionally more tile memory for a difference that does not show at
/// map line widths. Clamped to what the device reports, so this is a ceiling.
pub const WANTED_SAMPLES: vk::SampleCountFlags = vk::SampleCountFlags::TYPE_4;

pub struct Swapchain {
    pub loader: ash::khr::swapchain::Device,
    pub swapchain: vk::SwapchainKHR,
    pub images: Vec<vk::Image>,
    pub views: Vec<vk::ImageView>,
    pub framebuffers: Vec<vk::Framebuffer>,
    pub render_pass: vk::RenderPass,
    pub format: vk::Format,
    pub extent: vk::Extent2D,
    /// Samples per pixel the pipelines must be built for.
    pub samples: vk::SampleCountFlags,
    /// The multisampled colour target, absent when `samples` is 1.
    msaa: Option<MsaaTarget>,
    /// The stencil the region mask is rasterised into, and the attachment index it sits at.
    stencil: StencilTarget,
}

/// The stencil buffer, for punching the selected region out of the mask scrim.
///
/// Transient and lazily allocated for the same reason as [`MsaaTarget`]: it is written and read
/// within a single subpass and never afterwards, so on a tile-based GPU it lives in tile memory
/// and needs no backing store. That is what keeps the "# No depth buffer" bandwidth argument above
/// true even though there is now an attachment here.
struct StencilTarget {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
}

struct MsaaTarget {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
}

/// The most samples the device supports for a colour attachment, capped at
/// [`WANTED_SAMPLES`].
unsafe fn supported_samples(context: &Context) -> vk::SampleCountFlags {
    let limits = context.instance.get_physical_device_properties(context.physical_device).limits;
    let available = limits.framebuffer_color_sample_counts;
    for candidate in
        [vk::SampleCountFlags::TYPE_8, vk::SampleCountFlags::TYPE_4, vk::SampleCountFlags::TYPE_2]
    {
        if candidate.as_raw() <= WANTED_SAMPLES.as_raw() && available.contains(candidate) {
            return candidate;
        }
    }
    vk::SampleCountFlags::TYPE_1
}

impl Swapchain {
    /// # Safety
    ///
    /// `context` must be live, and any previous swapchain must have been destroyed.
    pub unsafe fn new(context: &Context, width: u32, height: u32) -> Result<Swapchain, String> {
        let (width, height) = (width.max(1), height.max(1));
        let capabilities = context
            .surface_loader
            .get_physical_device_surface_capabilities(context.physical_device, context.surface)
            .map_err(|e| format!("surface capabilities {e:?}"))?;
        let formats = context
            .surface_loader
            .get_physical_device_surface_formats(context.physical_device, context.surface)
            .map_err(|e| format!("surface formats {e:?}"))?;
        let present_modes = context
            .surface_loader
            .get_physical_device_surface_present_modes(context.physical_device, context.surface)
            .map_err(|e| format!("present modes {e:?}"))?;

        // Prefer a plain UNORM format: Pixel's gralloc rejects B8G8R8A8_SRGB and the
        // result is a black screen with no error. See the module docs.
        let mut chosen = *formats.first().ok_or("the surface reports no formats")?;
        for candidate in &formats {
            if candidate.format == vk::Format::B8G8R8A8_UNORM
                || candidate.format == vk::Format::R8G8B8A8_UNORM
            {
                chosen = *candidate;
                break;
            }
        }

        let extent = if capabilities.current_extent.width == u32::MAX {
            vk::Extent2D { width, height }
        } else {
            vk::Extent2D {
                width: capabilities.current_extent.width.max(1),
                height: capabilities.current_extent.height.max(1),
            }
        };
        // FIFO is vsync, and the map is driven by Choreographer: anything else would only
        // queue frames nobody sees, at the cost of power.
        let present_mode = if present_modes.contains(&vk::PresentModeKHR::FIFO) {
            vk::PresentModeKHR::FIFO
        } else {
            present_modes[0]
        };
        let max = if capabilities.max_image_count == 0 { u32::MAX } else { capabilities.max_image_count };
        let image_count = (capabilities.min_image_count + 1).min(max);
        // IDENTITY where offered, so the presentation engine does not rotate an image we
        // already produced in display orientation.
        let pre_transform =
            if capabilities.supported_transforms.contains(vk::SurfaceTransformFlagsKHR::IDENTITY) {
                vk::SurfaceTransformFlagsKHR::IDENTITY
            } else {
                capabilities.current_transform
            };

        let loader = ash::khr::swapchain::Device::new(&context.instance, &context.device);
        let create_info = vk::SwapchainCreateInfoKHR::default()
            .surface(context.surface)
            .min_image_count(image_count)
            .image_format(chosen.format)
            .image_color_space(chosen.color_space)
            .image_extent(extent)
            .image_array_layers(1)
            .image_usage(vk::ImageUsageFlags::COLOR_ATTACHMENT)
            .image_sharing_mode(vk::SharingMode::EXCLUSIVE)
            .pre_transform(pre_transform)
            .composite_alpha(vk::CompositeAlphaFlagsKHR::OPAQUE)
            .present_mode(present_mode)
            .clipped(true);
        let swapchain = loader
            .create_swapchain(&create_info, None)
            .map_err(|e| format!("create_swapchain {e:?}"))?;
        let images =
            loader.get_swapchain_images(swapchain).map_err(|e| format!("swapchain images {e:?}"))?;

        let samples = supported_samples(context);
        let multisampled = samples != vk::SampleCountFlags::TYPE_1;

        // One subpass. When multisampled the colour attachment is the transient MSAA
        // image (discarded after the resolve) and the swapchain image is the resolve
        // target; otherwise the swapchain image is the colour attachment directly.
        // Either way there are no mid-pass reads, so a tile-based GPU keeps the whole
        // frame in tile memory.
        let color = vk::AttachmentDescription::default()
            .format(chosen.format)
            .samples(samples)
            .load_op(vk::AttachmentLoadOp::CLEAR)
            .store_op(if multisampled {
                vk::AttachmentStoreOp::DONT_CARE
            } else {
                vk::AttachmentStoreOp::STORE
            })
            .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(if multisampled {
                vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL
            } else {
                vk::ImageLayout::PRESENT_SRC_KHR
            });
        let resolve = vk::AttachmentDescription::default()
            .format(chosen.format)
            .samples(vk::SampleCountFlags::TYPE_1)
            // The resolve writes every pixel, so there is nothing worth loading.
            .load_op(vk::AttachmentLoadOp::DONT_CARE)
            .store_op(vk::AttachmentStoreOp::STORE)
            .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(vk::ImageLayout::PRESENT_SRC_KHR);
        // Chosen before the render pass because the attachment has to name the format, and
        // `S8_UINT` is optional in Vulkan while a combined depth-stencil is universally
        // available. The depth half goes unused when the fallback is taken.
        let stencil_format = stencil_format(context)?;
        let stencil = vk::AttachmentDescription::default()
            .format(stencil_format)
            .samples(samples)
            // Cleared to zero every frame: zero means "outside the selected region", which is
            // what the scrim tests for, and is also the right answer when nothing is selected.
            .load_op(vk::AttachmentLoadOp::DONT_CARE)
            .store_op(vk::AttachmentStoreOp::DONT_CARE)
            .stencil_load_op(vk::AttachmentLoadOp::CLEAR)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        let attachments: Vec<vk::AttachmentDescription> =
            if multisampled { vec![color, resolve, stencil] } else { vec![color, stencil] };

        let color_ref = vk::AttachmentReference::default()
            .attachment(0)
            .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL);
        let resolve_ref = vk::AttachmentReference::default()
            .attachment(1)
            .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL);
        let stencil_ref = vk::AttachmentReference::default()
            .attachment(if multisampled { 2 } else { 1 })
            .layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        let mut subpass = vk::SubpassDescription::default()
            .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
            .color_attachments(std::slice::from_ref(&color_ref))
            .depth_stencil_attachment(&stencil_ref);
        if multisampled {
            subpass = subpass.resolve_attachments(std::slice::from_ref(&resolve_ref));
        }
        let dependency = vk::SubpassDependency::default()
            .src_subpass(vk::SUBPASS_EXTERNAL)
            .dst_subpass(0)
            .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
            .dst_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
            .dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE);
        let render_pass_info = vk::RenderPassCreateInfo::default()
            .attachments(&attachments)
            .subpasses(std::slice::from_ref(&subpass))
            .dependencies(std::slice::from_ref(&dependency));
        let render_pass = context
            .device
            .create_render_pass(&render_pass_info, None)
            .map_err(|e| format!("create_render_pass {e:?}"))?;

        let msaa = if multisampled {
            Some(MsaaTarget::new(context, chosen.format, extent, samples)?)
        } else {
            None
        };

        let stencil_target = StencilTarget::new(context, stencil_format, extent, samples)?;

        let mut views = Vec::with_capacity(images.len());
        let mut framebuffers = Vec::with_capacity(images.len());
        for &image in &images {
            let view_info = vk::ImageViewCreateInfo::default()
                .image(image)
                .view_type(vk::ImageViewType::TYPE_2D)
                .format(chosen.format)
                .subresource_range(vk::ImageSubresourceRange {
                    aspect_mask: vk::ImageAspectFlags::COLOR,
                    base_mip_level: 0,
                    level_count: 1,
                    base_array_layer: 0,
                    layer_count: 1,
                });
            let view = context
                .device
                .create_image_view(&view_info, None)
                .map_err(|e| format!("create_image_view {e:?}"))?;
            views.push(view);

            // Attachment order matches the render pass: colour, resolve, then stencil.
            let attached: Vec<vk::ImageView> = match &msaa {
                Some(target) => vec![target.view, view, stencil_target.view],
                None => vec![view, stencil_target.view],
            };
            let framebuffer_info = vk::FramebufferCreateInfo::default()
                .render_pass(render_pass)
                .attachments(&attached)
                .width(extent.width)
                .height(extent.height)
                .layers(1);
            framebuffers.push(
                context
                    .device
                    .create_framebuffer(&framebuffer_info, None)
                    .map_err(|e| format!("create_framebuffer {e:?}"))?,
            );
        }

        Ok(Swapchain {
            loader,
            swapchain,
            images,
            views,
            framebuffers,
            render_pass,
            format: chosen.format,
            extent,
            samples,
            msaa,
            stencil: stencil_target,
        })
    }

    /// # Safety
    ///
    /// The device must be idle.
    pub unsafe fn destroy(&mut self, device: &ash::Device) {
        for &framebuffer in &self.framebuffers {
            device.destroy_framebuffer(framebuffer, None);
        }
        self.framebuffers.clear();
        if let Some(target) = self.msaa.take() {
            device.destroy_image_view(target.view, None);
            device.destroy_image(target.image, None);
            device.free_memory(target.memory, None);
        }
        device.destroy_image_view(self.stencil.view, None);
        device.destroy_image(self.stencil.image, None);
        device.free_memory(self.stencil.memory, None);
        device.destroy_render_pass(self.render_pass, None);
        for &view in &self.views {
            device.destroy_image_view(view, None);
        }
        self.views.clear();
        self.loader.destroy_swapchain(self.swapchain, None);
    }
}

/// A stencil-capable attachment format the device supports, cheapest first.
///
/// `S8_UINT` is what this actually wants — eight bits, no depth — but it is optional in Vulkan
/// and plenty of drivers omit it, so the combined formats are the fallback. Every Vulkan
/// implementation must support at least one of `D24_UNORM_S8_UINT` or `D32_SFLOAT_S8_UINT`.
unsafe fn stencil_format(context: &Context) -> Result<vk::Format, String> {
    for candidate in [
        vk::Format::S8_UINT,
        vk::Format::D24_UNORM_S8_UINT,
        vk::Format::D32_SFLOAT_S8_UINT,
    ] {
        let properties = context
            .instance
            .get_physical_device_format_properties(context.physical_device, candidate);
        if properties
            .optimal_tiling_features
            .contains(vk::FormatFeatureFlags::DEPTH_STENCIL_ATTACHMENT)
        {
            return Ok(candidate);
        }
    }
    Err("no stencil-capable attachment format".into())
}

impl MsaaTarget {
    /// Allocate the multisampled colour target.
    ///
    /// `TRANSIENT_ATTACHMENT` with `LAZILY_ALLOCATED` memory is the whole point on a
    /// tile-based GPU: the samples never leave tile memory, so the driver backs the
    /// image with nothing. Desktop and emulator drivers do not offer that memory type,
    /// so it falls back to a normal device-local allocation.
    unsafe fn new(
        context: &Context,
        format: vk::Format,
        extent: vk::Extent2D,
        samples: vk::SampleCountFlags,
    ) -> Result<MsaaTarget, String> {
        let image_info = vk::ImageCreateInfo::default()
            .image_type(vk::ImageType::TYPE_2D)
            .format(format)
            .extent(vk::Extent3D { width: extent.width, height: extent.height, depth: 1 })
            .mip_levels(1)
            .array_layers(1)
            .samples(samples)
            .tiling(vk::ImageTiling::OPTIMAL)
            .usage(
                vk::ImageUsageFlags::COLOR_ATTACHMENT
                    | vk::ImageUsageFlags::TRANSIENT_ATTACHMENT,
            )
            .sharing_mode(vk::SharingMode::EXCLUSIVE)
            .initial_layout(vk::ImageLayout::UNDEFINED);
        let image = context
            .device
            .create_image(&image_info, None)
            .map_err(|e| format!("create MSAA image {e:?}"))?;

        let requirements = context.device.get_image_memory_requirements(image);
        let properties =
            context.instance.get_physical_device_memory_properties(context.physical_device);
        let find = |flags: vk::MemoryPropertyFlags| -> Option<u32> {
            (0..properties.memory_type_count).find(|&i| {
                requirements.memory_type_bits & (1 << i) != 0
                    && properties.memory_types[i as usize].property_flags.contains(flags)
            })
        };
        let type_index = find(
            vk::MemoryPropertyFlags::DEVICE_LOCAL | vk::MemoryPropertyFlags::LAZILY_ALLOCATED,
        )
        .or_else(|| find(vk::MemoryPropertyFlags::DEVICE_LOCAL))
        .ok_or("no device-local memory type for the MSAA target")?;

        let allocate = vk::MemoryAllocateInfo::default()
            .allocation_size(requirements.size)
            .memory_type_index(type_index);
        let memory = match context.device.allocate_memory(&allocate, None) {
            Ok(memory) => memory,
            Err(e) => {
                context.device.destroy_image(image, None);
                return Err(format!("allocate MSAA memory {e:?}"));
            }
        };
        if let Err(e) = context.device.bind_image_memory(image, memory, 0) {
            context.device.destroy_image(image, None);
            context.device.free_memory(memory, None);
            return Err(format!("bind MSAA memory {e:?}"));
        }

        let view_info = vk::ImageViewCreateInfo::default()
            .image(image)
            .view_type(vk::ImageViewType::TYPE_2D)
            .format(format)
            .subresource_range(vk::ImageSubresourceRange {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                base_mip_level: 0,
                level_count: 1,
                base_array_layer: 0,
                layer_count: 1,
            });
        let view = match context.device.create_image_view(&view_info, None) {
            Ok(view) => view,
            Err(e) => {
                context.device.destroy_image(image, None);
                context.device.free_memory(memory, None);
                return Err(format!("create MSAA image view {e:?}"));
            }
        };
        Ok(MsaaTarget { image, memory, view })
    }
}

impl StencilTarget {
    /// Allocate the stencil attachment, transient and lazily allocated like [`MsaaTarget::new`].
    unsafe fn new(
        context: &Context,
        format: vk::Format,
        extent: vk::Extent2D,
        samples: vk::SampleCountFlags,
    ) -> Result<StencilTarget, String> {
        let image_info = vk::ImageCreateInfo::default()
            .image_type(vk::ImageType::TYPE_2D)
            .format(format)
            .extent(vk::Extent3D { width: extent.width, height: extent.height, depth: 1 })
            .mip_levels(1)
            .array_layers(1)
            .samples(samples)
            .tiling(vk::ImageTiling::OPTIMAL)
            .usage(
                vk::ImageUsageFlags::DEPTH_STENCIL_ATTACHMENT
                    | vk::ImageUsageFlags::TRANSIENT_ATTACHMENT,
            )
            .sharing_mode(vk::SharingMode::EXCLUSIVE)
            .initial_layout(vk::ImageLayout::UNDEFINED);
        let image = context
            .device
            .create_image(&image_info, None)
            .map_err(|e| format!("create stencil image {e:?}"))?;

        let requirements = context.device.get_image_memory_requirements(image);
        let properties =
            context.instance.get_physical_device_memory_properties(context.physical_device);
        let find = |flags: vk::MemoryPropertyFlags| -> Option<u32> {
            (0..properties.memory_type_count).find(|&i| {
                requirements.memory_type_bits & (1 << i) != 0
                    && properties.memory_types[i as usize].property_flags.contains(flags)
            })
        };
        let type_index = find(
            vk::MemoryPropertyFlags::DEVICE_LOCAL | vk::MemoryPropertyFlags::LAZILY_ALLOCATED,
        )
        .or_else(|| find(vk::MemoryPropertyFlags::DEVICE_LOCAL))
        .ok_or("no device-local memory type for the stencil target")?;

        let allocate = vk::MemoryAllocateInfo::default()
            .allocation_size(requirements.size)
            .memory_type_index(type_index);
        let memory = match context.device.allocate_memory(&allocate, None) {
            Ok(memory) => memory,
            Err(e) => {
                context.device.destroy_image(image, None);
                return Err(format!("allocate stencil memory {e:?}"));
            }
        };
        if let Err(e) = context.device.bind_image_memory(image, memory, 0) {
            context.device.destroy_image(image, None);
            context.device.free_memory(memory, None);
            return Err(format!("bind stencil memory {e:?}"));
        }

        // The view names only the stencil aspect even when the format carries depth too, which
        // is what a depth-stencil attachment view must do when only one aspect is used.
        let aspect = if format == vk::Format::S8_UINT {
            vk::ImageAspectFlags::STENCIL
        } else {
            vk::ImageAspectFlags::DEPTH | vk::ImageAspectFlags::STENCIL
        };
        let view_info = vk::ImageViewCreateInfo::default()
            .image(image)
            .view_type(vk::ImageViewType::TYPE_2D)
            .format(format)
            .subresource_range(vk::ImageSubresourceRange {
                aspect_mask: aspect,
                base_mip_level: 0,
                level_count: 1,
                base_array_layer: 0,
                layer_count: 1,
            });
        let view = match context.device.create_image_view(&view_info, None) {
            Ok(view) => view,
            Err(e) => {
                context.device.destroy_image(image, None);
                context.device.free_memory(memory, None);
                return Err(format!("create stencil image view {e:?}"));
            }
        };
        Ok(StencilTarget { image, memory, view })
    }
}
