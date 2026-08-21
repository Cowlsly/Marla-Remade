//! MVT + PMTiles v3 tile-build tools for the Maps basemap.
//!
//! Replaces `tippecanoe` and `tile-join`. Neither has a Windows path, which would
//! otherwise leave the `transit_stops` layer unbuildable on the dev box, so the
//! whole tile chain is now cargo-only.
//!
//! Built in layers, each usable on its own:
//!
//!   * [`proto`] — protobuf wire codec. Read half mirrors `osm_ingest`'s
//!     decode-only reader; write half is new.
//!   * [`mvt`] — vector tile 2.1 decode/encode. A feature's geometry is stored as
//!     raw command integers, so anything we are merely compositing passes through
//!     untouched; the `encode_*`/`decode_*` functions beside that path are for the
//!     layers we build ourselves.
//!   * [`gz`] — gzip framing over `miniz_oxide`'s raw DEFLATE, which PMTiles needs
//!     for both its directories and its tiles.
//!   * [`pmtiles`] — the v3 container, read and write, including Hilbert tile ids
//!     and the root/leaf directory split.
//!   * [`geom`] — projection, bounds, tile ranges and quantisation: lon/lat in,
//!     integer tile coordinates out.
//!   * [`clip`] — Liang-Barsky for lines, Sutherland-Hodgman for polygons, against
//!     a tile's buffered rect.
//!   * [`simplify`] — Douglas-Peucker in integer tile coordinates.
//!   * [`tiling`] — bucket points into tiles, and merge tilesets.
//!
//! [`geom`], [`clip`] and [`simplify`] compose in one fixed order; [`geom`]'s module
//! docs give the pipeline.
//!
//! Three binaries sit on top: `tile_points` (the `tippecanoe` replacement for point
//! layers), `tile_join` (the `tile-join` replacement) and `pmtiles_dump` (a
//! canonical text dump, for the differential harness).
//!
//! Verified against the published `v5-ca.pmtiles` during development: its header,
//! gzipped root directory and gzipped leaf directories all decode with byte-exact
//! payload consumption, and a real tile's layers decode to the same names,
//! extents and feature counts tippecanoe wrote.
//!
//! **Output is not byte-identical to tippecanoe, by design.** Its
//! `--drop-densest-as-needed` is a lossy per-tile heuristic and
//! `--extend-zooms-if-still-dropping` can push an archive past its own maximum
//! zoom; we implement a deterministic policy and a fixed max zoom instead. Tests
//! assert our own invariants — ring closure, winding order, Douglas-Peucker
//! monotonicity, a PMTiles round trip — never equality with tippecanoe.

pub mod clip;
pub mod geom;
pub mod gz;
pub mod mvt;
pub mod pmtiles;
pub mod proto;
pub mod simplify;
pub mod tiling;
