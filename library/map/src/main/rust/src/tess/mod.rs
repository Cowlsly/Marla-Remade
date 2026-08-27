//! CPU tessellation: MVT geometry in, triangles out.
//!
//! The expensive half of a frame, and the half the plan expects to be the actual
//! bottleneck — a vector map is slow on the CPU, not the GPU. Pure functions over
//! flat slices, so it is testable on the host with no device and no JNI.

pub mod earcut;
pub mod fill;
pub mod stroke;
