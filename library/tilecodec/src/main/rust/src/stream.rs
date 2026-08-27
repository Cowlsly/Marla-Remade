//! A PMTiles archive read over byte ranges rather than out of a file.
//!
//! [`Archive`](crate::pmtiles::Archive) needs the whole file resident and
//! [`ArchiveFile`](crate::pmtiles::ArchiveFile) needs a `File`; the app has neither.
//! It streams the published archive — ~1.5 GB for California alone — over HTTP range
//! requests, where a screenful of tiles is a few hundred kilobytes of it.
//!
//! So this holds the header and root directory (kilobytes) and fetches one leaf
//! directory or one tile body at a time, sharing
//! [`parse_directory`](crate::pmtiles::parse_directory) and `find_entry` with the
//! file-backed readers so all three agree on addressing.
//!
//! Leaf directories are cached: a leaf covers 4096 spatially adjacent tiles, so
//! panning re-reads the same one constantly, and without the cache every tile costs a
//! range request and a gzip inflate *before* the request for the tile itself.

use crate::pmtiles::{
    decompress, find_entry, parse_directory, tile_id, Entry, Header, HEADER_LEN,
};
use crate::proto::{err, Result};

/// Random access to a remote byte-addressed resource.
///
/// Implementations return **at most** `length` bytes: a range past the end of the
/// resource comes back short rather than failing, which is how an HTTP server answers
/// it. A caller needing an exact count checks.
pub trait RangeReader {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>>;
}

/// The spec advises keeping the root directory inside the first 16 KB so a cold open
/// is one round trip, and the published archive does: its root ends at byte 1479.
pub const OPEN_PREFIX_BYTES: u32 = 16 * 1024;

/// A sanity bound on a root directory fetched separately. A corrupt header could
/// otherwise name a multi-gigabyte read.
const MAX_ROOT_BYTES: u64 = 8 * 1024 * 1024;

/// The spec allows arbitrary directory nesting; producers emit two levels, so the walk
/// is capped rather than risking a loop on a corrupt file.
const MAX_DIRECTORY_DEPTH: usize = 4;

/// How many parsed leaf directories to keep. The published archive has 324.
const MAX_CACHED_LEAVES: usize = 16;

pub struct StreamArchive<R: RangeReader> {
    reader: R,
    pub header: Header,
    root: Vec<Entry>,
    /// `(leaf offset, entries)`, most-recently-used last.
    leaves: Vec<(u64, Vec<Entry>)>,
}

impl<R: RangeReader> StreamArchive<R> {
    /// Read the header and root directory. One range request when the root is inside
    /// the first [`OPEN_PREFIX_BYTES`], which is what the spec asks producers for.
    pub fn open(reader: R) -> Result<StreamArchive<R>> {
        let prefix = reader.read(0, OPEN_PREFIX_BYTES)?;
        let header = Header::parse(&prefix)?;
        let root_end = header.root_offset + header.root_length;
        let root_raw = if root_end <= prefix.len() as u64 {
            prefix[header.root_offset as usize..root_end as usize].to_vec()
        } else {
            if header.root_length > MAX_ROOT_BYTES {
                return err(format!(
                    "PMTiles root directory of {} bytes is implausible",
                    header.root_length
                ));
            }
            let body = reader.read(header.root_offset, header.root_length as u32)?;
            if body.len() as u64 != header.root_length {
                return err("PMTiles root directory came back short");
            }
            body
        };
        let root = parse_directory(&decompress(header.internal_compression, &root_raw)?)?;
        Ok(StreamArchive { reader, header, root, leaves: Vec::new() })
    }

    /// The decompressed MVT body for a tile, or `None` when the archive does not hold
    /// it — which is the ordinary answer anywhere off the edge of its coverage.
    pub fn tile(&mut self, z: u8, x: u32, y: u32) -> Result<Option<Vec<u8>>> {
        if z < self.header.min_zoom || z > self.header.max_zoom {
            return Ok(None);
        }
        if z >= 32 {
            return Ok(None);
        }
        let n = 1u64 << z;
        if x as u64 >= n || y as u64 >= n {
            return Ok(None);
        }

        let want = tile_id(z, x as u64, y as u64);
        let mut entry = match find_entry(&self.root, want) {
            Some(e) => *e,
            None => return Ok(None),
        };
        let mut depth = 0;
        while entry.run_length == 0 {
            if depth >= MAX_DIRECTORY_DEPTH {
                return err(format!(
                    "PMTiles directory nested deeper than {MAX_DIRECTORY_DEPTH} levels"
                ));
            }
            depth += 1;
            let leaf = self.leaf(&entry)?;
            entry = match find_entry(&leaf, want) {
                Some(e) => *e,
                None => return Ok(None),
            };
        }

        if entry.offset + entry.length as u64 > self.header.tile_data_length {
            return err("PMTiles entry runs past the tile data section");
        }
        let body = self.exact(self.header.tile_data_offset + entry.offset, entry.length, "a tile body")?;
        Ok(Some(decompress(self.header.tile_compression, &body)?))
    }

    /// The parsed leaf directory a leaf-pointer entry addresses, from cache or over the
    /// wire.
    fn leaf(&mut self, pointer: &Entry) -> Result<Vec<Entry>> {
        if let Some(position) = self.leaves.iter().position(|(offset, _)| *offset == pointer.offset) {
            // Move to the back: most-recently-used last.
            let hit = self.leaves.remove(position);
            let entries = hit.1.clone();
            self.leaves.push(hit);
            return Ok(entries);
        }
        if pointer.offset + pointer.length as u64 > self.header.leaf_length {
            return err("PMTiles leaf entry runs past the leaf section");
        }
        let raw =
            self.exact(self.header.leaf_offset + pointer.offset, pointer.length, "a leaf directory")?;
        let entries = parse_directory(&decompress(self.header.internal_compression, &raw)?)?;
        if self.leaves.len() >= MAX_CACHED_LEAVES {
            self.leaves.remove(0);
        }
        self.leaves.push((pointer.offset, entries.clone()));
        Ok(entries)
    }

    /// A read that must come back at its full length.
    ///
    /// A short body means the range was not honoured. Inflating it would fail somewhere
    /// far less informative, and caching it would poison every later read of that range
    /// — which is exactly the "Prefix string too short" failure the Kotlin cache in
    /// `maps` records.
    fn exact(&self, offset: u64, length: u32, what: &str) -> Result<Vec<u8>> {
        let body = self.reader.read(offset, length)?;
        if body.len() != length as usize {
            return err(format!(
                "PMTiles read of {what} wanted {length} bytes at {offset}, got {}",
                body.len()
            ));
        }
        Ok(body)
    }
}

/// The header's declared zoom range, without opening the archive twice.
pub fn header_len() -> usize {
    HEADER_LEN
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pmtiles::{tile_zxy, Builder};
    use std::cell::RefCell;

    /// A [`RangeReader`] over bytes already in memory, logging every range asked for so
    /// a test can assert on the *number* of round trips as well as the bytes.
    struct Memory {
        bytes: Vec<u8>,
        requests: RefCell<Vec<(u64, u32)>>,
    }

    impl Memory {
        fn new(bytes: Vec<u8>) -> Memory {
            Memory { bytes, requests: RefCell::new(Vec::new()) }
        }
    }

    impl RangeReader for Memory {
        fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
            self.requests.borrow_mut().push((offset, length));
            if offset >= self.bytes.len() as u64 {
                return Ok(Vec::new());
            }
            // Short rather than failing, as the trait specifies.
            let end = (offset + length as u64).min(self.bytes.len() as u64);
            Ok(self.bytes[offset as usize..end as usize].to_vec())
        }
    }

    fn archive(tiles: &[(u8, u64, u64, &[u8])], max_zoom: u8) -> Vec<u8> {
        let mut builder = Builder::new();
        builder.max_zoom = max_zoom;
        for &(z, x, y, body) in tiles {
            builder.add_tile(z, x, y, body);
        }
        builder.build().expect("build")
    }

    #[test]
    fn a_flat_archive_round_trips() {
        let bytes = archive(
            &[(0, 0, 0, b"tile-zero"), (1, 0, 0, b"tile-one"), (2, 3, 1, b"tile-two")],
            2,
        );
        let mut stream = StreamArchive::open(Memory::new(bytes)).expect("open");
        assert_eq!(stream.header.max_zoom, 2);
        assert_eq!(stream.tile(0, 0, 0).unwrap().unwrap(), b"tile-zero");
        assert_eq!(stream.tile(1, 0, 0).unwrap().unwrap(), b"tile-one");
        assert_eq!(stream.tile(2, 3, 1).unwrap().unwrap(), b"tile-two");
        // Absent tiles are None, not an error.
        assert!(stream.tile(1, 1, 1).unwrap().is_none());
        assert!(stream.tile(2, 0, 0).unwrap().is_none());
    }

    #[test]
    fn a_cold_open_costs_one_range_request() {
        // The spec advises keeping the root inside the first 16 KB for exactly this
        // reason, and the published archive's root ends at byte 1479.
        let bytes = archive(&[(0, 0, 0, b"x")], 0);
        let reader = Memory::new(bytes);
        let stream = StreamArchive::open(reader).expect("open");
        assert_eq!(stream.reader.requests.borrow().as_slice(), &[(0, OPEN_PREFIX_BYTES)]);
    }

    #[test]
    fn a_run_of_identical_tiles_resolves_for_every_id_in_the_run() {
        // The ocean-tile case: one body, one entry, four addressable tiles.
        let base = tile_id(4, 0, 0);
        let mut builder = Builder::new();
        builder.max_zoom = 4;
        for k in 0..4 {
            builder.add_tile_raw(base + k, crate::gz::compress(b"same"));
        }
        let bytes = builder.build().expect("build");
        let mut stream = StreamArchive::open(Memory::new(bytes)).expect("open");
        assert_eq!(stream.header.addressed_tiles, 4);
        assert_eq!(stream.header.tile_entries, 1, "collapsed into one run");
        assert_eq!(stream.header.tile_contents, 1, "one distinct body");
        for k in 0..4 {
            let (z, x, y) = tile_zxy(base + k);
            assert_eq!(stream.tile(z, x as u32, y as u32).unwrap().unwrap(), b"same", "tile {k}");
        }
    }

    #[test]
    fn the_two_level_walk_crosses_leaf_boundaries() {
        // Enough distinct tiles that the root cannot hold them all, forcing the
        // two-level layout the published archive uses.
        let base = tile_id(8, 0, 0);
        let count = 20_000u64;
        let mut builder = Builder::new();
        builder.max_zoom = 8;
        for k in 0..count {
            builder.add_tile_raw(base + k, crate::gz::compress(format!("tile {k}").as_bytes()));
        }
        let bytes = builder.build().expect("build");
        let mut stream = StreamArchive::open(Memory::new(bytes)).expect("open");
        assert!(stream.header.leaf_length > 0, "must have spilled into leaves");
        // Spot-check across leaf boundaries.
        for k in [0u64, 1, 4095, 4096, 4097, 9999, count - 1] {
            let (z, x, y) = tile_zxy(base + k);
            assert_eq!(
                stream.tile(z, x as u32, y as u32).unwrap().unwrap(),
                format!("tile {k}").as_bytes(),
                "tile {k}",
            );
        }
    }

    #[test]
    fn a_leaf_directory_is_read_once_and_then_cached() {
        let base = tile_id(8, 0, 0);
        let mut builder = Builder::new();
        builder.max_zoom = 8;
        for k in 0..20_000u64 {
            builder.add_tile_raw(base + k, crate::gz::compress(format!("tile {k}").as_bytes()));
        }
        let bytes = builder.build().expect("build");
        let mut stream = StreamArchive::open(Memory::new(bytes)).expect("open");
        // Four tiles out of the same leaf: one leaf read, then four bodies. Without the
        // cache, panning re-inflates the same 4096-entry directory for every tile.
        stream.reader.requests.borrow_mut().clear();
        for k in 0..4u64 {
            let (z, x, y) = tile_zxy(base + k);
            stream.tile(z, x as u32, y as u32).unwrap();
        }
        let count = stream.reader.requests.borrow().len();
        assert_eq!(count, 5, "one leaf read plus four bodies, got {count}");
    }

    #[test]
    fn a_tile_outside_the_archives_zoom_range_is_not_requested_at_all() {
        let mut builder = Builder::new();
        builder.min_zoom = 2;
        builder.max_zoom = 2;
        builder.add_tile(2, 0, 0, b"x");
        let bytes = builder.build().expect("build");
        let mut stream = StreamArchive::open(Memory::new(bytes)).expect("open");
        stream.reader.requests.borrow_mut().clear();
        assert!(stream.tile(1, 0, 0).unwrap().is_none());
        assert!(stream.tile(3, 0, 0).unwrap().is_none());
        // Out-of-grid coordinates are rejected before they become a tile id.
        assert!(stream.tile(2, 4, 0).unwrap().is_none());
        assert!(stream.reader.requests.borrow().is_empty(), "no request for an impossible tile");
    }

    #[test]
    fn a_corrupt_archive_errors_rather_than_panicking() {
        let bytes = archive(&[(0, 0, 0, b"x")], 0);

        assert!(StreamArchive::open(Memory::new(bytes[..HEADER_LEN - 1].to_vec())).is_err());
        let mut bad = bytes.clone();
        bad[0] = b'X';
        assert!(StreamArchive::open(Memory::new(bad)).is_err(), "bad magic");
        let mut bad = bytes.clone();
        bad[7] = 2;
        assert!(StreamArchive::open(Memory::new(bad)).is_err(), "wrong spec version");
        // Root directory bytes replaced with something that is not gzip.
        let mut bad = bytes.clone();
        bad[HEADER_LEN] = 0;
        assert!(StreamArchive::open(Memory::new(bad)).is_err(), "root is not gzip");
    }

    #[test]
    fn a_body_that_comes_back_short_is_an_error_not_a_bad_inflate() {
        // What a range request answered with a truncated body looks like from here.
        let bytes = archive(&[(0, 0, 0, b"x")], 0);
        let truncated = bytes[..bytes.len() - 1].to_vec();
        let mut stream = StreamArchive::open(Memory::new(truncated)).expect("open");
        let error = stream.tile(0, 0, 0).expect_err("a short body must be an error");
        assert!(format!("{error}").contains("got"), "unhelpful message: {error}");
    }

    // --- against the published archive --------------------------------------

    const REAL_HEAD: &[u8] = include_bytes!("../tests/fixtures/v5ca_header_rootdir.bin");

    /// The 127-byte header of the archive the apps actually stream,
    /// `https://data.vayunmathur.com/v4.pmtiles`, fetched with a ranged GET.
    const PLANET_HEAD: &[u8] = include_bytes!("../tests/fixtures/v4_planet_header.bin");

    #[test]
    fn the_published_planet_archives_header_parses() {
        // Every one of these was an assumption in the renderer before it was a measurement,
        // and one of them was wrong: the code hardcoded a max zoom of 16.
        let header = Header::parse(PLANET_HEAD).expect("the published planet header");
        assert_eq!(header.min_zoom, 0);
        assert_eq!(header.max_zoom, 15, "the archive stops at z15, not z16");
        assert_eq!(header.internal_compression, 2, "directories are gzip");
        assert_eq!(header.tile_compression, 2, "tile bodies are gzip");
        assert_eq!(header.tile_type, 1, "tile bodies are MVT");
        assert!(header.clustered, "a clustered archive, so runs of identical tiles collapse");
    }

    #[test]
    fn the_planet_archives_root_directory_is_inside_the_open_prefix() {
        // A cold open is one range request only if the root fits in the prefix we ask for.
        // Root is 127..15682 here, so 16 KB covers it with room to spare.
        let header = Header::parse(PLANET_HEAD).expect("header");
        let root_end = header.root_offset + header.root_length;
        assert!(
            root_end <= OPEN_PREFIX_BYTES as u64,
            "root ends at {root_end}, past the {OPEN_PREFIX_BYTES}-byte open prefix, so every \
             cold open costs a second round trip",
        );
    }

    #[test]
    fn the_planet_archive_puts_its_leaf_directories_after_the_tile_data() {
        // The spec lists the sections header/root/metadata/leaves/tiles, and this archive
        // does *not* use that order: its leaves sit 136 GB in, past the tile data. A reader
        // that assumed the documented layout instead of following the header's offsets
        // would address the wrong bytes for every tile outside the root directory.
        let header = Header::parse(PLANET_HEAD).expect("header");
        assert_eq!(header.tile_data_offset, 16_384);
        assert!(
            header.leaf_offset > header.tile_data_offset,
            "leaves at {} are before the tile data at {}; this test is asserting the wrong \
             archive layout",
            header.leaf_offset,
            header.tile_data_offset,
        );
        assert!(header.leaf_length > 0, "a planet archive must have leaf directories");
        // 137 GB: far past a u32, so every offset arithmetic path has to be 64-bit.
        let file_end = header.leaf_offset + header.leaf_length;
        assert!(file_end > u32::MAX as u64, "the archive is {file_end} bytes, which needs u64");
    }

    #[test]
    fn the_published_archives_root_directory_addresses_its_first_leaf() {
        // tile_id 2229854 (z11/339/770, the fixture tile) is below the second root
        // entry's 2230397, so the walk for it descends through root entry 0. This is
        // the addressing the app depends on, stated against real bytes.
        let header = Header::parse(REAL_HEAD).expect("the published header");
        let raw = &REAL_HEAD
            [header.root_offset as usize..(header.root_offset + header.root_length) as usize];
        let root = parse_directory(&decompress(header.internal_compression, raw).unwrap()).unwrap();
        assert_eq!(root.len(), 324, "known entry count");
        let entry = find_entry(&root, tile_id(11, 339, 770)).expect("covered");
        assert_eq!(entry.tile_id, 0);
        assert_eq!(entry.run_length, 0, "a leaf pointer, so the walk is two-level");
    }
}
