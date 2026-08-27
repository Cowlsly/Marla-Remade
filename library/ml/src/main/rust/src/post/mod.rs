//! Turning a net's output maps into the things a caller actually wanted.
//!
//! Everything here is pure CPU work on small arrays, and deliberately so. A plan ends at
//! the last convolution; what follows is thresholding, sorting, pairwise comparison and
//! contour walking — data-dependent control flow over tens of candidates rather than
//! millions of elements, which is the one part of these pipelines a GPU makes slower.
//!
//! It is also the part that was invisible before. All of this lived inside the ncnn
//! fork's `.so`, so a wrong threshold or an off-by-one in an anchor centre could not be
//! read, let alone tested. Each module here is ported from a named C++ function and
//! host-tested against values computed by hand.

pub mod crop;
pub mod ctc;
pub mod dbnet;
pub mod nms;
