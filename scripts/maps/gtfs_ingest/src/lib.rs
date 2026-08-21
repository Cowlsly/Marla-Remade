//! Shared GTFS ingest internals for the Maps offline packs.
//!
//! Exists so the crate can carry more than one host binary over the same GTFS
//! parser: `gtfs_ingest` builds the on-device `.transit` index, and
//! `transit_stops` emits the basemap stop layer. Mirrors `osm_ingest`'s
//! lib + `src/bin/*` shape.

pub mod gtfs;
pub mod index;
pub mod manifest;
pub mod shapes;
