//! The Vulkan compute ML runtime behind `:library:ml`.
//!
//! Runs the two pure-CNN vision models the repo ships — MediaPipe Selfie Segmentation
//! for `:camera`'s portrait bokeh, U^2-Net portable for `:photos`'s auto-select-subject
//! — replacing the CPU-only ncnn fork for both. ncnn stays for `:photos`'s two face
//! models and for `:library:ocr`, `:pdf`, `:translate` and `:speech`.
//!
//! # Layout
//!
//! * [`weights`] — the `.vkml` reader. Ordered tensors, no topology.
//! * [`nets`] — the two hardcoded forward passes, and the small compiler that packs
//!   their activations into one arena and resolves every offset.
//! * [`preprocess`] — bitmap to fp16 NCHW, and the fp16 conversions.
//! * `vulkan` — device, pipelines, the recorded command buffer. Android only.
//!
//! # GPU only, by decision
//!
//! There is no CPU fallback. Vulkan 1.1 is guaranteed at minSdk 31, but fp16 is not:
//! `VK_KHR_shader_float16_int8` was promoted only in 1.2, so the device extension and
//! its feature struct have to be asked for explicitly. Where they are missing the
//! runtime reports itself unavailable and the feature turns off — no bokeh, no
//! auto-subject-select — rather than falling back to a second implementation that would
//! have to be kept correct with no way to test it.
//!
//! # Nothing runs per frame
//!
//! A net is compiled to a flat [`nets::Plan`] once, and that plan is recorded into a
//! single command buffer once, at construction. An inference is then an upload, one
//! submit, one fence wait and one readback. That matters because `:camera` calls this
//! at ~15 fps while the UI is also using the GPU.
//!
//! # Host builds
//!
//! Everything except the Vulkan and JNI layers compiles and tests on the host, so
//! `cargo test` builds **both** networks in full, checks the `.vkml` round trip, the
//! arena arithmetic and the preprocessing, with no device and no asset.

pub mod nets;
pub mod post;
pub mod preprocess;
pub mod weights;

#[cfg(target_os = "android")]
pub mod vulkan;

#[cfg(target_os = "android")]
mod bridge;
