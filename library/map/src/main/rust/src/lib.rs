//! The Vulkan vector-tile map renderer behind `:library:map`.
//!
//! Replaces the CARTO raster basemap in findfamily, photos, weather, taxi and
//! fooddelivery (#615) with our own renderer over the self-hosted pmtiles archive at
//! `data.vayunmathur.com/v4.pmtiles` — the same archive `maps` streams through
//! MapLibre.
//!
//! # Layout
//!
//! * [`tess`] — MVT geometry to triangles: earcut fills, stroked lines. Pure, and the
//!   half of a frame that actually costs something.
//! * [`style`] — which layers are drawn, in what order, in what colour.
//! * [`camera`] — a camera snapshot from Kotlin to a per-tile clip matrix.
//! * [`tile`] — the streaming PMTiles reader, its range cache, and tile selection.
//! * `vulkan` — device, swapchain, pipelines, frame. Android only.
//!
//! # The JNI boundary
//!
//! Deliberately narrow, and never in the hot loop: Kotlin creates and destroys the
//! renderer for a `Surface`, resizes it, and hands it **one camera snapshot per
//! frame**. Everything else — tile selection, fetching, decode, tessellation,
//! drawing — happens on this side. A per-feature or per-vertex crossing would cost
//! more than the rendering does.
//!
//! `Projection` stays in Kotlin, in `Dp`, because it is public API that every
//! consumer app's overlays are positioned by.
//!
//! # Host builds
//!
//! Everything except the Vulkan and JNI layers compiles and tests on the host, so
//! `cargo test` covers tessellation, tile selection, the cache and the camera maths
//! with no device attached.

pub mod camera;
pub mod style;
pub mod tess;
pub mod tile;

#[cfg(target_os = "android")]
pub mod vulkan;

#[cfg(target_os = "android")]
mod bridge;
