//! Host-side OSM ingest for the Maps offline packs.
//!
//! Two tools share this library:
//!
//! * `road_graph` — the routing graph (`metadata.bin`, `nodes.bin`, `edges.bin`,
//!   `lanes.bin`, `road_names.bin`), consumed by
//!   `maps/src/main/rust/src/graph.rs`.
//! * `poi_extract` — the `ma_pois` layer's geojsonseq plus `poi_names.bin` and
//!   `poi_index.bin`, consumed by `PoiIndex.kt`.
//!
//! Both used to be C++ built against libosmium, which meant they only ran under
//! WSL. Reading `.osm.pbf` directly — a hand-rolled protobuf decoder plus a
//! pure-Rust inflate — makes them native on every platform the app is built on.

pub mod graph_build;
pub mod names;
pub mod osm;
pub mod par;
pub mod pbf;
pub mod poi_build;
pub mod proto;
pub mod spatial;
pub mod tags;

#[cfg(test)]
mod testpbf;
