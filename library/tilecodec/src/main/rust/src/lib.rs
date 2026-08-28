//! PMTiles v3 and Mapbox Vector Tile 2.1 — the formats the Maps basemap is made of.
//!
//! One codec, two consumers. `scripts/maps/tile_build` **writes** the archives
//! (it replaced `tippecanoe` and `tile-join`, neither of which has a Windows
//! path), and the Vulkan vector renderer in `:library:map` **reads** them on
//! device. Both have to agree with the published `v4.pmtiles` down to the byte, so
//! there is one implementation rather than one per consumer.
//!
//! That history is why the tests here assert against **real** data — the published
//! archive's header and root directory, and a tile lifted out of it with a ranged
//! GET — rather than only round-tripping through our own writer. A synthetic round
//! trip proves we agree with ourselves; `tests/fixtures/README.md` records what
//! each fixture is and where it came from.
//!
//! The layers, each usable on its own:
//!
//!   * [`proto`] — protobuf wire codec. The read half mirrors `osm_ingest`'s
//!     decode-only reader; the write half is used only by the tiler.
//!   * [`mvt`] — vector tile 2.1 decode/encode. A feature's geometry is stored as
//!     raw command integers, so anything being merely composited passes through
//!     untouched; the `encode_*`/`decode_*` functions beside that path are for the
//!     layers we build ourselves, and for the renderer that has to turn them into
//!     triangles.
//!   * [`gz`] — gzip framing over `miniz_oxide`'s raw DEFLATE, which PMTiles needs
//!     for both its directories and its tiles.
//!   * [`pmtiles`] — the v3 container, read and write, including Hilbert tile ids
//!     and the root/leaf directory split.
//!   * [`mamaps`] — the container the Vulkan renderer reads: geometry only,
//!     pre-clipped, attributes interned to integers, flat little-endian structs.
//!     PMTiles and MVT are what the `maps` app still needs, because it renders
//!     through MapLibre; this is what everything else is moving to, and the two
//!     live side by side for the transition. Its writer is behind the `write`
//!     feature, so Android links only the read half.
//!
//! Nothing here allocates a thread, opens a socket or touches a GPU. The reader the
//! app uses over HTTP range requests is [`stream`], which is built on top of
//! [`pmtiles`] rather than inside it; the transport it reads through is the caller's
//! to supply.

pub mod gz;
pub mod mamaps;
pub mod mvt;
pub mod pmtiles;
pub mod proto;
pub mod stream;
