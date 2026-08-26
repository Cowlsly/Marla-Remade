//! Shared GTFS ingest internals for the Maps offline packs.
//!
//! Exists so the crate can carry more than one host binary over the same GTFS
//! `gtfs_ingest` builds the on-device `.transit` index, `transit_stops`
//! emits the basemap stop layer, `resolve_feeds` turns the Transitous and
//! transitland-atlas registries into a download plan whose feeds can carry MOTIS
//! stop ids, and `transit_dump` renders a built pack as diffable text. Mirrors
//! `osm_ingest`'s lib + `src/bin/*` shape.
//!
//! **Zero dependencies, deliberately.** That includes the JSON reader in [`json`]:
//! pulling in serde for two registry formats would trade away the property that
//! makes this crate build and run offline on any box.
pub mod gtfs;
pub mod index;
pub mod json;
pub mod manifest;
pub mod par;
pub mod reader;
pub mod registry;
pub mod shapes;
