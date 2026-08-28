//! Reading a `.mamaps` archive over range requests.
//!
//! The cost contract, which is the reason the container is shaped the way it is:
//!
//! | | requests |
//! |---|---|
//! | open | 1 |
//! | a tile whose leaf is cached | 1 |
//! | a tile whose leaf is not | 2 |
//! | ever | never 3 |
//!
//! Open is one request because the header, the dictionary and the root index all fit inside
//! [`OPEN_PREFIX_BYTES`] and none of the three is compressed, so all three are used straight out
//! of the prefix. Sixteen cached leaves at 4096 entries each address 65 536 tiles with no
//! directory traffic at all, which matches the locality the transport was tuned around.
//!
//! **Every read takes its length from a header or entry field.** No sentinel, no length
//! discovered mid-stream, no read-until-it-looks-done — because the disk cache in `tile::cache`
//! only stores a `206` whose body length equals what was asked for, so a read of the wrong length
//! does not merely fail, it poisons that range for every later read.

use crate::mamaps::body::Body;
use crate::mamaps::dict::Dictionary;
use crate::mamaps::header::{Header, COMPRESSION_DEFLATE, COMPRESSION_NONE, HEADER_LEN};
use crate::mamaps::index::{self, LeafEntry, RootEntry};
use crate::pmtiles::tile_id;
use crate::proto::{err, Error, Result};
use crate::stream::{RangeReader, OPEN_PREFIX_BYTES};

/// How many parsed leaves to keep. Sixteen, matching `stream::StreamArchive`, because the access
/// pattern is the same one: a viewport walks a contiguous stretch of the curve.
const MAX_CACHED_LEAVES: usize = 16;

/// An open archive.
pub struct MamapsArchive<R: RangeReader> {
    /// Reachable from the crate so the request-counting tests in [`super`] can assert on the
    /// number of round trips, which is the contract this whole layout exists to hold.
    pub(crate) reader: R,
    pub header: Header,
    pub dictionary: Dictionary,
    root: Vec<RootEntry>,
    /// `(leaf offset, entries)`, most-recently-used last.
    leaves: Vec<(u64, Vec<LeafEntry>)>,
}

impl<R: RangeReader> MamapsArchive<R> {
    /// The reader this archive was opened on.
    ///
    /// So a caller can act on something it only learns from the header — the `build_id`, which
    /// decides whether the reader's disk cache is still addressing this build.
    pub fn reader(&self) -> &R {
        &self.reader
    }
    /// One range request: the header, the dictionary and the root index all live in the prefix.
    ///
    /// A file whose prefix does not hold all three is refused rather than fetched again. The
    /// writer asserts the budget, so a file that misses it was not written by this codec, and
    /// silently paying an extra round trip per open is exactly the cost the layout exists to
    /// avoid.
    pub fn open(reader: R) -> Result<MamapsArchive<R>> {
        let prefix = reader.read(0, OPEN_PREFIX_BYTES)?;
        let header = Header::parse(&prefix)?;
        let section = |offset: u64, len: u64, what: &str| -> Result<&[u8]> {
            let end = offset + len;
            if end > prefix.len() as u64 {
                return err(format!(
                    "{what} ends at byte {end}, outside the {OPEN_PREFIX_BYTES} byte prefix a \
                     .mamaps open reads -- this archive was not written by this codec",
                ));
            }
            Ok(&prefix[offset as usize..end as usize])
        };
        let dictionary =
            Dictionary::parse(section(header.dict_offset, header.dict_len as u64, "the dictionary")?)?;
        dictionary.check_matches_schema()?;
        if dictionary.layers.len() != header.layer_count as usize {
            return err("a .mamaps header and dictionary disagree on the layer count");
        }
        let root =
            index::parse_root(section(header.root_offset, header.root_len as u64, "the root index")?)?;
        if root.len() != header.leaf_count as usize {
            return err(format!(
                "a .mamaps header declares {} leaves but its root has {} entries",
                header.leaf_count,
                root.len(),
            ));
        }
        Ok(MamapsArchive { reader, header, dictionary, root, leaves: Vec::new() })
    }

    /// The decoded body for a tile, or `None` when the archive does not hold it.
    ///
    /// `None` rather than an error: most of a coastal viewport is off the edge of coverage, and
    /// that is the ordinary answer rather than a fault.
    pub fn tile(&mut self, z: u8, x: u32, y: u32) -> Result<Option<Body>> {
        let Some(bytes) = self.tile_bytes(z, x, y)? else { return Ok(None) };
        Ok(Some(Body::parse(&bytes)?))
    }

    /// The decompressed body bytes for a tile, undecoded.
    pub fn tile_bytes(&mut self, z: u8, x: u32, y: u32) -> Result<Option<Vec<u8>>> {
        if z < self.header.min_zoom || z > self.header.max_zoom || z >= 32 {
            return Ok(None);
        }
        let n = 1u64 << z;
        if x as u64 >= n || y as u64 >= n {
            return Ok(None);
        }
        let want = tile_id(z, x as u64, y as u64);
        let Some(root) = index::find_leaf(&self.root, want).copied() else { return Ok(None) };
        // Ahead of the leaf's base is impossible (the root is ordered), so this only rules out a
        // tile past a `u32` of span, which the writer refuses to produce.
        let Ok(offset) = u32::try_from(want - root.base_tile_id) else { return Ok(None) };

        let leaf = self.leaf(&root)?;
        let Some(entry) = index::find_tile(&leaf, offset).copied() else { return Ok(None) };

        // Every bound from a field, checked before the read rather than after it.
        let at = root.base_data_offset + entry.offset_delta as u64;
        if at + entry.length as u64 > self.header.data_len {
            return err("a .mamaps leaf entry runs past the tile data section");
        }
        let stored = self.exact(self.header.data_offset + at, entry.length, "a tile body")?;
        Ok(Some(decompress(self.header.compression, &stored)?))
    }

    /// The parsed leaf a root entry addresses, from the cache or over the wire.
    fn leaf(&mut self, root: &RootEntry) -> Result<Vec<LeafEntry>> {
        if let Some(position) = self.leaves.iter().position(|(offset, _)| *offset == root.leaf_offset)
        {
            // Move to the back: most-recently-used last.
            let hit = self.leaves.remove(position);
            let entries = hit.1.clone();
            self.leaves.push(hit);
            return Ok(entries);
        }
        let len = root.leaf_entry_count as u64 * index::LEAF_ENTRY_LEN as u64;
        if root.leaf_offset + len > self.header.leaf_len as u64 {
            return err("a .mamaps root entry runs past the leaf section");
        }
        let length = u32::try_from(len)
            .map_err(|_| Error("a .mamaps leaf is larger than a single read".to_string()))?;
        let raw = self.exact(self.header.leaf_offset + root.leaf_offset, length, "a leaf")?;
        let entries = index::parse_leaf(&raw)?;
        if self.leaves.len() >= MAX_CACHED_LEAVES {
            self.leaves.remove(0);
        }
        self.leaves.push((root.leaf_offset, entries.clone()));
        Ok(entries)
    }

    /// A read that must come back at its full length.
    ///
    /// A short body means the range was not honoured. Decompressing it would fail somewhere far
    /// less informative, and caching it would poison that range for every later read.
    fn exact(&self, offset: u64, length: u32, what: &str) -> Result<Vec<u8>> {
        let body = self.reader.read(offset, length)?;
        if body.len() != length as usize {
            return err(format!(
                "a .mamaps read of {what} wanted {length} bytes at {offset}, got {}",
                body.len(),
            ));
        }
        Ok(body)
    }
}

/// Inflate one body frame, allocating exactly once.
///
/// The output size comes from the body header, which the writer keeps **outside** the compressed
/// frame precisely so this is possible: the length is known before the first byte is inflated, so
/// there is no grow-and-copy and no guess.
pub fn decompress(compression: u8, stored: &[u8]) -> Result<Vec<u8>> {
    match compression {
        COMPRESSION_NONE => Ok(stored.to_vec()),
        COMPRESSION_DEFLATE => {
            let raw_len = Body::raw_len(stored)? as usize;
            if raw_len < crate::mamaps::body::BODY_HEADER_LEN {
                return err("a .mamaps body frame declares a length shorter than a body header");
            }
            let mut out = vec![0u8; raw_len];
            // The body header is stored uncompressed ahead of the frame, so the frame itself is
            // everything after it and inflates to `raw_len` less that header.
            let header_len = crate::mamaps::body::BODY_HEADER_LEN;
            out[..header_len].copy_from_slice(&stored[..header_len]);
            let written = miniz_oxide::inflate::decompress_slice_iter_to_slice(
                &mut out[header_len..],
                std::iter::once(&stored[header_len..]),
                // Raw DEFLATE, matching what the writer emits: no zlib header, so no adler32
                // either.
                false,
                false,
            )
            .map_err(|e| Error(format!("a .mamaps body will not inflate: {e:?}")))?;
            if written != raw_len - header_len {
                return err(format!(
                    "a .mamaps body inflated to {} bytes, not the {} it declares",
                    written + header_len,
                    raw_len,
                ));
            }
            Ok(out)
        }
        other => err(format!("unknown .mamaps compression {other}")),
    }
}

/// The header, dictionary and root index of a file already in memory.
///
/// What `mamaps_dump` and the writer's own tests open with, so neither needs a `RangeReader`.
pub fn open_prefix(bytes: &[u8]) -> Result<(Header, Dictionary, Vec<RootEntry>)> {
    let header = Header::parse(bytes)?;
    if bytes.len() as u64 != header.file_len {
        return err(format!(
            "a .mamaps file declares {} bytes but is {}",
            header.file_len,
            bytes.len(),
        ));
    }
    let at = |offset: u64, len: u64| &bytes[offset as usize..(offset + len) as usize];
    let dictionary = Dictionary::parse(at(header.dict_offset, header.dict_len as u64))?;
    let root = index::parse_root(at(header.root_offset, header.root_len as u64))?;
    Ok((header, dictionary, root))
}

/// Every stored tile of a file already in memory, as `(tile_id, run_length, body bytes)`.
///
/// One entry per *stored body*, not per addressed tile, so a caller can see the dedup rather than
/// having it expanded away. Ascending by tile id.
pub fn read_all(bytes: &[u8]) -> Result<Vec<(u64, u32, Vec<u8>)>> {
    let (header, dictionary, root) = open_prefix(bytes)?;
    dictionary.check_matches_schema()?;
    let mut out = Vec::new();
    for entry in &root {
        let len = entry.leaf_entry_count as usize * index::LEAF_ENTRY_LEN;
        let at = (header.leaf_offset + entry.leaf_offset) as usize;
        if at + len > bytes.len() {
            return err("a .mamaps root entry runs past the file");
        }
        for tile in index::parse_leaf(&bytes[at..at + len])? {
            let start = (header.data_offset + entry.base_data_offset + tile.offset_delta as u64)
                as usize;
            let end = start + tile.length as usize;
            if end > bytes.len() {
                return err("a .mamaps leaf entry runs past the file");
            }
            out.push((
                entry.base_tile_id + tile.tile_id_lo as u64,
                tile.run_length,
                decompress(header.compression, &bytes[start..end])?,
            ));
        }
    }
    if out.windows(2).any(|pair| pair[1].0 <= pair[0].0) {
        return err("a .mamaps archive's tiles are not in ascending id order");
    }
    Ok(out)
}

/// A sanity bound so a reader can reject a prefix that is not a `.mamaps` file at all before
/// allocating anything, without depending on the reader's own constant.
pub const MIN_FILE_LEN: usize = HEADER_LEN;
