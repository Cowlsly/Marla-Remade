//! The tile pipeline: fetch, cache, decode, tessellate, choose.
//!
//! [`select`] decides what the viewport needs, [`source`] gets the bytes (through the
//! cache in [`cache`] and, on device, out through Kotlin's HTTP stack), and
//! [`geometry`] turns them into triangles.

pub mod cache;
pub mod geometry;
pub mod select;
pub mod source;
