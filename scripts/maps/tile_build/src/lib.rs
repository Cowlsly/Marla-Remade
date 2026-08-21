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
//!
//! Verified against the published `v5-ca.pmtiles` during development: its header,
//! gzipped root directory and gzipped leaf directories all decode with byte-exact
//! payload consumption, and a real tile's layers decode to the same names,
//! extents and feature counts tippecanoe wrote.

pub mod mvt;
pub mod proto;
