//! Vulkan: device, swapchain, pipelines and the frame. Android only.
//!
//! `libvulkan.so` is on every device we target (minSdk 31) and `ash` dlopen's it, so
//! nothing here is linked at build time and nothing is bundled in the APK. That is the
//! whole size argument against `androidx.webgpu`, which ships a 5.79 MB Dawn per ABI to
//! translate to this.
//!
//! Structured after `games/voxels/src/main/rust/src/vulkan`, the in-repo precedent, and
//! it inherits two black-screen fixes from it — see [`swapchain`].

pub mod buffers;
pub mod context;
pub mod images;
pub mod pipeline;
pub mod renderer;
pub mod swapchain;
