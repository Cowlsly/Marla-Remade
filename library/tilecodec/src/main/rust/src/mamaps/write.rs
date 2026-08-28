//! Writing a `.mamaps` archive, streaming and byte-identically.
//!
//! Bodies are appended in ascending tile-id order — which the generator gets for free, because its
//! spill buckets are zoom-major Hilbert ranges — and the index is built as they go. Nothing is
//! held in memory but the entries.
//!
//! # Dedup
//!
//! Two kinds, both of which matter:
//!
//! * **Run-length**, for consecutive identical bodies. An ocean is thousands of consecutive
//!   identical tiles, and a run collapses them to one entry as well as one body.
//! * **Content**, for non-consecutive ones — an FNV-1a bucket confirmed by a full byte compare, the
//!   same pattern `pmtiles::StreamBuilder` uses. Every hit is a fact, not a probability.
//!
//! Together these are what makes empty and ocean tiles nearly free: on the planet PMTiles archive
//! the same two collapse 1.57 M addressed tiles to 1.03 M stored bodies.
//!
//! # Determinism
//!
//! Byte-identical output for identical input, at any thread count, because nothing in the emit
//! path iterates a hash map: the dictionary comes from a constant table, layers are sorted by id,
//! and the bucket map is only ever *probed*, never walked. `pyramid.rs`'s existing byte-identity
//! suite is the precedent and this holds to the same standard.

use std::collections::HashMap;

use crate::mamaps::body::{self, Body};
use crate::mamaps::dict::Dictionary;
use crate::mamaps::header::{
    Header, COMPRESSION_DEFLATE, COMPRESSION_NONE, FLAG_BODIES_COMPRESSED, FLAG_RINGS_VALIDATED,
    FLAG_RUN_LENGTH_PRESENT, HEADER_LEN, MAX_ZOOM,
};
use crate::mamaps::index::{self, LeafEntry, RootEntry};
use crate::proto::{err, Result};
use crate::stream::OPEN_PREFIX_BYTES;

/// Leaf entries per leaf to start with, matching what the published PMTiles archive uses.
pub const DEFAULT_LEAF_CAPACITY: u32 = 4096;

/// DEFLATE level. Nine, as `gz.rs` uses, because a basemap is written once and read forever.
const LEVEL: u8 = 9;

/// What a build declares about itself before the first tile.
pub struct Options {
    pub min_zoom: u8,
    pub max_zoom: u8,
    /// Hashed over the format version, the generator revision, the input digest, the zoom range,
    /// the layer set, the simplification parameters and the schema-table version.
    ///
    /// The writer does not compute it: only the generator knows those inputs. It does carry it, and
    /// a reader's cache marker is `CACHE_FORMAT | URL | build_id`.
    pub build_id: u64,
    pub compress: bool,
    /// Set when the generator normalised ring winding and hole containment, which is what lets the
    /// renderer skip its repair pass.
    pub rings_validated: bool,
    pub leaf_entry_capacity: u32,
    pub min_lon_e7: i32,
    pub min_lat_e7: i32,
    pub max_lon_e7: i32,
    pub max_lat_e7: i32,
}

impl Default for Options {
    fn default() -> Options {
        Options {
            min_zoom: 0,
            max_zoom: 14,
            build_id: 0,
            compress: true,
            rings_validated: false,
            leaf_entry_capacity: DEFAULT_LEAF_CAPACITY,
            min_lon_e7: -1_800_000_000,
            min_lat_e7: -850_511_287,
            max_lon_e7: 1_800_000_000,
            max_lat_e7: 850_511_287,
        }
    }
}

/// Builds an archive in memory, appending bodies in ascending tile-id order.
///
/// In memory rather than through a scratch file, unlike `pmtiles::StreamBuilder`: the data section
/// is the only thing that grows without bound, and the generator that will feed this already
/// spills its *input* to disk, so a second spill here would buy a smaller peak in exchange for a
/// second on-disk format to keep correct. A California archive is a few hundred megabytes. When a
/// planet build needs it, the scratch-file pattern is already written next door.
pub struct StreamWriter {
    options: Options,
    data: Vec<u8>,
    /// One per stored body, ascending by tile id, partitioned into leaves at `finish`.
    ///
    /// The [`LeafEntry::offset_delta`] here is still **absolute** within the data section; it is
    /// rebased onto its leaf's `base_data_offset` by [`Self::partition`].
    entries: Vec<(u64, LeafEntry)>,
    /// `(hash, stored length) -> offsets already written`. Several per key only on a collision,
    /// which is why it is a list. Probed, never iterated.
    seen: HashMap<(u64, u32), Vec<u32>>,
    last_id: Option<u64>,
    tiles_addressed: u64,
    /// Bodies actually appended to `data`, which is what dedup reduces. Distinct from
    /// `entries.len()`, which counts *index* entries: a body shared by two non-adjacent tiles is
    /// one body and two entries.
    distinct: u64,
    runs_used: bool,
}

impl StreamWriter {
    pub fn new(options: Options) -> Result<StreamWriter> {
        if options.min_zoom > options.max_zoom || options.max_zoom > MAX_ZOOM {
            return err(format!(
                "a .mamaps build needs a zoom range inside 0..={MAX_ZOOM}, got {}..={}",
                options.min_zoom, options.max_zoom,
            ));
        }
        if !options.leaf_entry_capacity.is_power_of_two() || options.leaf_entry_capacity == 0 {
            return err(format!(
                "a .mamaps leaf capacity must be a power of two, got {}",
                options.leaf_entry_capacity,
            ));
        }
        Ok(StreamWriter {
            options,
            data: Vec::new(),
            entries: Vec::new(),
            seen: HashMap::new(),
            last_id: None,
            tiles_addressed: 0,
            distinct: 0,
            runs_used: false,
        })
    }

    /// Encode and append one tile. Ids must ascend.
    pub fn append(&mut self, tile_id: u64, body: &Body) -> Result<()> {
        self.append_encoded(tile_id, &body::serialize(body)?)
    }

    /// Append an already-encoded body.
    ///
    /// Ascending ids are a requirement rather than something to sort into place: the index is built
    /// as bodies arrive, and the generator's zoom-major Hilbert buckets already deliver them in
    /// order. A caller that cannot is a caller whose bucketing broke, which is worth failing over.
    pub fn append_encoded(&mut self, tile_id: u64, encoded: &[u8]) -> Result<()> {
        let (z, _, _) = crate::pmtiles::tile_zxy(tile_id);
        if z < self.options.min_zoom || z > self.options.max_zoom {
            return err(format!(
                "tile {tile_id} is at z{z}, outside the declared range {}..={}",
                self.options.min_zoom, self.options.max_zoom,
            ));
        }
        if let Some(previous) = self.last_id {
            if tile_id <= previous {
                return err(format!(
                    "a .mamaps build needs ascending tile ids, got {tile_id} after {previous}"
                ));
            }
        }
        // Parsed rather than trusted: `raw_len` is what a reader allocates from, and a body whose
        // declared length disagrees with its bytes would be caught on device instead of here.
        let raw_len = Body::raw_len(encoded)?;
        if raw_len as usize != encoded.len() {
            return err(format!(
                "a .mamaps body declares {raw_len} bytes but is {}",
                encoded.len(),
            ));
        }
        self.last_id = Some(tile_id);
        self.tiles_addressed += 1;

        let stored = if self.options.compress { compress(encoded) } else { encoded.to_vec() };
        let length = u32::try_from(stored.len())
            .map_err(|_| crate::proto::Error("a .mamaps body is larger than 4 GiB".to_string()))?;

        // Run-length first: a consecutive repeat needs no dedup lookup and no new entry at all.
        if let Some((previous_id, previous)) = self.entries.last_mut() {
            if previous.length == length
                && *previous_id + previous.run_length as u64 == tile_id
                && self.data[previous.offset_delta as usize..][..length as usize] == stored[..]
            {
                previous.run_length += 1;
                self.runs_used = true;
                return Ok(());
            }
        }

        let key = (hash64(&stored), length);
        let offset = match self
            .seen
            .get(&key)
            .and_then(|offsets| {
                offsets
                    .iter()
                    .copied()
                    .find(|&at| self.data[at as usize..][..length as usize] == stored[..])
            }) {
            Some(at) => at,
            None => {
                let at = u32::try_from(self.data.len()).map_err(|_| {
                    crate::proto::Error(
                        "a .mamaps data section past 4 GiB needs a wider offset field".to_string(),
                    )
                })?;
                self.data.extend_from_slice(&stored);
                self.seen.entry(key).or_default().push(at);
                self.distinct += 1;
                at
            }
        };
        self.entries.push((
            tile_id,
            LeafEntry { tile_id_lo: 0, run_length: 1, offset_delta: offset, length },
        ));
        Ok(())
    }

    /// The whole file.
    ///
    /// Section order on disk is header, dictionary, root, leaves, data — but every one of them is
    /// located by a header field, so a later version may reorder them freely.
    pub fn finish(self) -> Result<Vec<u8>> {
        if self.entries.is_empty() {
            return err("a .mamaps archive needs at least one tile");
        }
        let dictionary = Dictionary::schema().serialize();
        let mut capacity = self.options.leaf_entry_capacity;

        // Grow the leaf size until the root fits the opening prefix, which is the same escape the
        // PMTiles writer takes and for the same reason. Twelve doublings takes 4096 to 16 M
        // entries per leaf; a root that still does not fit is a build failure, never a silent
        // third round trip charged to every reader forever.
        let (root, leaves) = loop {
            match self.partition(capacity) {
                Some(split) => {
                    let root_len = split.0.len() * index::ROOT_ENTRY_LEN;
                    if HEADER_LEN + dictionary.len() + root_len <= OPEN_PREFIX_BYTES as usize {
                        break split;
                    }
                }
                // A leaf whose tile-id span will not fit a `u32`. Splitting further makes each
                // leaf narrower, which is the fix.
                None => {}
            }
            if capacity >= 1 << 24 {
                return err(format!(
                    "a .mamaps root index will not fit the {OPEN_PREFIX_BYTES} byte opening \
                     prefix even at {capacity} entries per leaf",
                ));
            }
            capacity *= 2;
        };

        let mut flags = 0u16;
        if self.options.compress {
            flags |= FLAG_BODIES_COMPRESSED;
        }
        if self.runs_used {
            flags |= FLAG_RUN_LENGTH_PRESENT;
        }
        if self.options.rings_validated {
            flags |= FLAG_RINGS_VALIDATED;
        }

        let leaf_bytes: Vec<u8> =
            leaves.iter().flat_map(|leaf| index::serialize_leaf(leaf)).collect();
        let root_bytes = index::serialize_root(&root);
        let dict_offset = HEADER_LEN as u64;
        let root_offset = dict_offset + dictionary.len() as u64;
        let leaf_offset = root_offset + root_bytes.len() as u64;
        let data_offset = leaf_offset + leaf_bytes.len() as u64;

        let header = Header {
            flags,
            compression: if self.options.compress { COMPRESSION_DEFLATE } else { COMPRESSION_NONE },
            layer_count: crate::mamaps::dict::LAYERS.len() as u8,
            min_zoom: self.options.min_zoom,
            max_zoom: self.options.max_zoom,
            build_id: self.options.build_id,
            file_len: data_offset + self.data.len() as u64,
            dict_offset,
            dict_len: dictionary.len() as u32,
            leaf_entry_capacity: capacity,
            root_offset,
            root_len: root_bytes.len() as u32,
            leaf_count: root.len() as u32,
            leaf_offset,
            leaf_len: leaf_bytes.len() as u32,
            data_offset,
            data_len: self.data.len() as u64,
            tiles_addressed: self.tiles_addressed,
            bodies_written: self.distinct,
            min_lon_e7: self.options.min_lon_e7,
            min_lat_e7: self.options.min_lat_e7,
            max_lon_e7: self.options.max_lon_e7,
            max_lat_e7: self.options.max_lat_e7,
        };

        let mut out = Vec::with_capacity(header.file_len as usize);
        out.extend_from_slice(&header.serialize());
        out.extend_from_slice(&dictionary);
        out.extend_from_slice(&root_bytes);
        out.extend_from_slice(&leaf_bytes);
        out.extend_from_slice(&self.data);
        debug_assert_eq!(out.len() as u64, header.file_len);
        // Parsed back before it is handed over, because every check `Header::parse` makes is a
        // check a reader will make on open, and finding out then means finding out on a device.
        Header::parse(&out)?;
        Ok(out)
    }

    /// Chop the entries into leaves of at most `capacity`, rebasing each leaf's ids and offsets.
    ///
    /// `None` when some leaf's tile-id span will not fit the `u32` a [`LeafEntry::tile_id_lo`]
    /// carries. That is a real possibility at a deep zoom with a large capacity, and truncating it
    /// would put a body at the wrong tile with no diagnostic at all -- so the caller splits instead.
    fn partition(&self, capacity: u32) -> Option<(Vec<RootEntry>, Vec<Vec<LeafEntry>>)> {
        let mut root = Vec::new();
        let mut leaves = Vec::new();
        for chunk in self.entries.chunks(capacity as usize) {
            let (base_tile_id, _) = chunk[0];
            // The **minimum** offset in the chunk, not the first. Content dedup lets a tile in
            // this leaf point at a body written for an earlier one, so basing on the first entry
            // would make that delta negative.
            let base_data_offset =
                chunk.iter().map(|(_, e)| e.offset_delta).min().unwrap_or(0) as u64;
            let (last_id, last) = chunk[chunk.len() - 1];
            if !index::span_fits(last_id + last.run_length as u64 - 1 - base_tile_id) {
                return None;
            }
            let mut leaf = Vec::with_capacity(chunk.len());
            for (tile_id, entry) in chunk {
                leaf.push(LeafEntry {
                    tile_id_lo: (tile_id - base_tile_id) as u32,
                    run_length: entry.run_length,
                    offset_delta: entry.offset_delta - base_data_offset as u32,
                    length: entry.length,
                });
            }
            root.push(RootEntry {
                base_tile_id,
                leaf_offset: (leaves.len() * capacity as usize * index::LEAF_ENTRY_LEN) as u64,
                base_data_offset,
                leaf_entry_count: chunk.len() as u32,
            });
            leaves.push(leaf);
        }
        Some((root, leaves))
    }
}

/// Raw DEFLATE over everything but the body header.
///
/// The 16-byte body header is left uncompressed ahead of the frame so a reader can read `raw_len`
/// out of it and allocate the output exactly once, before inflating a single byte.
fn compress(encoded: &[u8]) -> Vec<u8> {
    let header_len = body::BODY_HEADER_LEN;
    let mut out = Vec::with_capacity(encoded.len());
    out.extend_from_slice(&encoded[..header_len]);
    out.extend_from_slice(&miniz_oxide::deflate::compress_to_vec(&encoded[header_len..], LEVEL));
    out
}

/// FNV-1a. Only ever a bucket key — every hit is confirmed by comparing bytes — so it needs to be
/// fast and well spread, not collision-proof.
fn hash64(data: &[u8]) -> u64 {
    let mut h = 0xcbf2_9ce4_8422_2325u64;
    for &b in data {
        h ^= b as u64;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}
