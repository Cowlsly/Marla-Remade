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
//!   * [`mvt`] — vector tile 2.1 decode/encode. Geometry stays as raw command
//!     integers, so lines and polygons pass through without a clipper.
//!   * [`gz`] — gzip framing over `miniz_oxide`'s raw DEFLATE, which PMTiles needs
//!     for both its directories and its tiles.
//!   * [`pmtiles`] — the v3 container, read and write, including Hilbert tile ids
//!     and the root/leaf directory split.
//!   * [`tiling`] — project and bucket points into tiles, and merge tilesets.
//!
//! Two binaries sit on top: `tile_points` (the `tippecanoe` replacement, for point
//! layers) and `tile_join` (the `tile-join` replacement).
//!
//! Verified against the published `v5-ca.pmtiles` during development: its header,
//! gzipped root directory and gzipped leaf directories all decode with byte-exact
//! payload consumption, and a real tile's layers decode to the same names,
//! extents and feature counts tippecanoe wrote.

pub mod gz;
pub mod mvt;
pub mod pmtiles;
pub mod proto;
pub mod tiling;
