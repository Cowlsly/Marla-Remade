//! Writing a `.mamaps` archive, streaming and byte-identically.
//!
//! Bodies are appended in ascending tile-id order — which the generator gets for free, because its
//! spill buckets are zoom-major Hilbert ranges — and the index is built as they go. Nothing is held
//! in memory but the index entries and the dedup buckets: bodies go to a scratch file as they
//! arrive and are copied onto the end of the archive at the finish.
//!
//! # Why the data section is not held in memory
//!
//! It was, and it cost twice the archive. A `data: Vec<u8>` grew to the whole 652 MB data section
//! of a California build, and then `finish` allocated a second 655 MB `Vec` and copied into it: a
//! 1.3 GB peak for a 655 MB file, and a projected 82 GB peak on a planet build, which is a hard
//! blocker on its own. Sending bodies straight to a scratch file makes the writer's peak the
//! *index* instead, at 16 bytes per stored body rather than the body itself, and
//! [`StreamWriter::finish_to_path`] never materialises the archive at all.
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
//! Both compares are against bytes that now live in a file, which is the one thing spilling the
//! data section actually complicates. Neither compare pays much for it:
//!
//! * A run-length compare is against the body the **last index entry** points at, and the writer
//!   keeps exactly those bytes to hand. That is one body, not a cache, and it never reads the file.
//! * A content compare is against a body at an arbitrary earlier offset, so it may read. Only the
//!   candidates in one FNV bucket are ever compared, and [`Spill`]'s write buffer answers a compare
//!   against a body still in it without a syscall.
//!
//! # Determinism
//!
//! Byte-identical output for identical input, at any thread count, because nothing in the emit
//! path iterates a hash map: the dictionary comes from a constant table, layers are sorted by id,
//! and the bucket map is only ever *probed*, never walked. `pyramid.rs`'s existing byte-identity
//! suite is the precedent and this holds to the same standard.
//!
//! Spilling changes none of that. The scratch file holds the same bytes in the same order the
//! in-memory `Vec` did, so which candidate a compare confirms — and therefore which offset an entry
//! is given — cannot depend on whether the bytes came from the buffer or from the file. That is
//! what lets [`StreamWriter::finish`] and [`StreamWriter::finish_to_path`] be two ways of emitting
//! one archive rather than two archives.

use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

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

/// DEFLATE level.
///
/// Nine, as `gz.rs` uses, because a basemap is written once and read forever.
///
/// Six was tried, on the theory that `encode`'s 43.9 s of a 135.8 s California build was mostly
/// compression. It is not: level six measured 44.8 s — inside the noise — for 0.12% more bytes. The
/// cost in that phase is stage C and body serialisation, not the deflate, so there is nothing here to
/// trade and the better ratio is free. Worth recording so nobody re-runs the experiment.
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
    /// Where the body scratch file goes, or the system temporary directory when unset.
    ///
    /// Worth naming rather than always taking `std::env::temp_dir()`, because the scratch file is
    /// the size of the data section: 652 MB for California and a projected 82 GB for a planet
    /// build, and a system temporary directory is routinely on a small system volume. The generator
    /// already puts its *feature* spill beside the output for the same reason.
    pub spill_dir: Option<PathBuf>,
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
            spill_dir: None,
            min_lon_e7: -1_800_000_000,
            min_lat_e7: -850_511_287,
            max_lon_e7: 1_800_000_000,
            max_lat_e7: 850_511_287,
        }
    }
}

/// Builds an archive, appending bodies in ascending tile-id order.
///
/// Bodies go to a scratch file as they arrive rather than into a `Vec`, so what this holds is the
/// index and the dedup buckets — 16 bytes per stored body and a hash entry per distinct one, not the
/// bodies. See the module header for why, and for what that costs the two dedup compares.
pub struct StreamWriter {
    options: Options,
    /// The data section, being written.
    ///
    /// Named `data` because that is what it is: the same bytes, in the same order, that used to be
    /// accumulated in memory here.
    data: Spill,
    /// One per stored body, ascending by tile id, partitioned into leaves at `finish`.
    ///
    /// The [`LeafEntry::offset_delta`] here is still **absolute** within the data section; it is
    /// rebased onto its leaf's `base_data_offset` by [`Self::partition`].
    entries: Vec<(u64, LeafEntry)>,
    /// `(hash, stored length) -> offsets already written`. Several per key only on a collision,
    /// which is why it is a list. Probed, never iterated.
    seen: HashMap<(u64, u32), Vec<u32>>,
    last_id: Option<u64>,
    /// The stored bytes of the body `entries.last()` points at, which is what a run-length compare
    /// is against.
    ///
    /// Kept so that compare stays in memory. It is *not* the last body appended: content dedup
    /// means the last entry may point at a body written for a much earlier tile, and this holds
    /// whatever those bytes are. A `debug_assert` in [`Self::append_encoded`] checks it against the
    /// spill on every append, which is how the shortcut is held to the file's answer rather than
    /// trusted to stay in step with it.
    last_body: Vec<u8>,
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
        // After the option checks, so a build with an impossible zoom range fails without having
        // created a file to clean up.
        let data = Spill::create(options.spill_dir.as_deref())?;
        Ok(StreamWriter {
            options,
            data,
            entries: Vec::new(),
            seen: HashMap::new(),
            last_id: None,
            last_body: Vec::new(),
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
        // Parsed rather than trusted: `raw_len` is what a reader allocates from, and a body whose
        // declared length disagrees with its bytes would be caught on device instead of here.
        let raw_len = Body::raw_len(encoded)?;
        if raw_len as usize != encoded.len() {
            return err(format!(
                "a .mamaps body declares {raw_len} bytes but is {}",
                encoded.len(),
            ));
        }
        let stored = if self.options.compress { compress_body(encoded) } else { encoded.to_vec() };
        self.push(tile_id, &stored)
    }

    /// Append a body already compressed by [`compress_body`].
    ///
    /// For a generator that compresses in parallel, which is the difference between using one core
    /// and using all of them. DEFLATE at level nine runs on the order of 15 MB/s, so a California
    /// build's 1.3 GB of bodies is about ninety seconds of a single core — and inside
    /// [`Self::append_encoded`] that sits *downstream* of the caller's parallel map and encode,
    /// serialising the whole pipeline behind the one step that cannot be stolen. Compressing in the
    /// worker and appending the result leaves the append what it should be: index bookkeeping.
    ///
    /// Dedup is unaffected. Entries are deduplicated on the **stored** bytes either way, and DEFLATE
    /// is deterministic, so two equal bodies still compress to two equal frames and still collapse.
    /// That is also why this is byte-identical to compressing here.
    pub fn append_stored(&mut self, tile_id: u64, stored: &[u8]) -> Result<()> {
        if !self.options.compress {
            return err(
                "append_stored was given a compressed body but this archive stores them raw"
                    .to_string(),
            );
        }
        // The 16-byte body header rides uncompressed ahead of the frame, so this still validates.
        Body::raw_len(stored)?;
        self.push(tile_id, stored)
    }

    /// The part both appends share: range and order checks, dedup, and the index entry.
    fn push(&mut self, tile_id: u64, stored: &[u8]) -> Result<()> {
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
        self.last_id = Some(tile_id);
        self.tiles_addressed += 1;

        let length = u32::try_from(stored.len())
            .map_err(|_| crate::proto::Error("a .mamaps body is larger than 4 GiB".to_string()))?;

        // Run-length first: a consecutive repeat needs no dedup lookup and no new entry at all.
        // The compare is against `last_body` rather than the spill, because the bytes the last
        // entry points at are the one body always worth keeping to hand.
        if let Some((previous_id, previous)) = self.entries.last_mut() {
            debug_assert!(
                self.data.matches_at(previous.offset_delta, &self.last_body)?,
                "last_body must be the bytes the last entry points at, or the shortcut below is \
                 answering for a body that is not there",
            );
            if previous.length == length
                && *previous_id + previous.run_length as u64 == tile_id
                && self.last_body == stored
            {
                previous.run_length += 1;
                self.runs_used = true;
                return Ok(());
            }
        }

        let key = (hash64(&stored), length);
        // `seen` and `data` are borrowed apart because confirming a candidate may read the spill,
        // which needs `&mut`, while the bucket being walked lives in the map.
        let StreamWriter { seen, data, distinct, .. } = &mut *self;
        let bucket = seen.entry(key).or_default();
        let mut hit = None;
        for &at in bucket.iter() {
            if data.matches_at(at, &stored)? {
                hit = Some(at);
                break;
            }
        }
        let offset = match hit {
            Some(at) => at,
            None => {
                let at = u32::try_from(data.len()).map_err(|_| {
                    crate::proto::Error(
                        "a .mamaps data section past 4 GiB needs a wider offset field".to_string(),
                    )
                })?;
                data.append(&stored)?;
                bucket.push(at);
                *distinct += 1;
                at
            }
        };
        self.entries.push((
            tile_id,
            LeafEntry { tile_id_lo: 0, run_length: 1, offset_delta: offset, length },
        ));
        self.last_body.clear();
        self.last_body.extend_from_slice(stored);
        Ok(())
    }

    /// Write the whole archive to `path`, never holding more than one section of it.
    ///
    /// This is the finish a real build wants: the prefix is assembled in memory — it is the index,
    /// which is small next to the bodies — and the data section is copied straight from the scratch
    /// file onto the end. Nothing ever holds the archive.
    ///
    /// The header is parsed before the destination is touched, so a build that would not open does
    /// not leave a file behind that looks like it might.
    pub fn finish_to_path(mut self, path: &Path) -> Result<()> {
        let (header, prefix) = self.prefix()?;
        let mut out = File::create(path).map_err(|e| {
            crate::proto::Error(format!("cannot write {}: {e}", path.display()))
        })?;
        out.write_all(&prefix)
            .map_err(|e| crate::proto::Error(format!("cannot write {}: {e}", path.display())))?;
        let copied = self.data.copy_to(&mut out, path)?;
        if copied != header.data_len {
            return err(format!(
                "a .mamaps build declared a {} byte data section and copied {copied}",
                header.data_len,
            ));
        }
        Ok(())
    }

    /// The whole file, in memory.
    ///
    /// Kept for callers small enough not to care — the tests, and the tools that read an archive
    /// back before writing it — and for them the peak is one copy of the archive rather than the two
    /// it used to be. Anything the size of a region should use [`Self::finish_to_path`].
    pub fn finish(mut self) -> Result<Vec<u8>> {
        let (header, prefix) = self.prefix()?;
        // TEMPORARY instrumentation.
        eprintln!(
            "spill: {} confirms, {} from file ({:.2}%), {} bytes read back",
            self.data.confirms,
            self.data.confirms_from_file,
            100.0 * self.data.confirms_from_file as f64 / self.data.confirms.max(1) as f64,
            self.data.bytes_from_file,
        );
        let mut out = Vec::with_capacity(header.file_len as usize);
        out.extend_from_slice(&prefix);
        let copied = self.data.copy_to_vec(&mut out)?;
        if copied != header.data_len {
            return err(format!(
                "a .mamaps build declared a {} byte data section and copied {copied}",
                header.data_len,
            ));
        }
        debug_assert_eq!(out.len() as u64, header.file_len);
        Ok(out)
    }

    /// Everything ahead of the data section: header, dictionary, root, leaves, in that order.
    ///
    /// Section order on disk is header, dictionary, root, leaves, data — but every one of them is
    /// located by a header field, so a later version may reorder them freely.
    ///
    /// Returned parsed as well as serialized, because every check `Header::parse` makes is a check a
    /// reader will make on open, and finding out then means finding out on a device. It is checked
    /// here, before either finish emits a byte.
    fn prefix(&self) -> Result<(Header, Vec<u8>)> {
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
        let data_len = self.data.len();

        let header = Header {
            flags,
            compression: if self.options.compress { COMPRESSION_DEFLATE } else { COMPRESSION_NONE },
            layer_count: crate::mamaps::dict::LAYERS.len() as u8,
            min_zoom: self.options.min_zoom,
            max_zoom: self.options.max_zoom,
            build_id: self.options.build_id,
            file_len: data_offset + data_len,
            dict_offset,
            dict_len: dictionary.len() as u32,
            leaf_entry_capacity: capacity,
            root_offset,
            root_len: root_bytes.len() as u32,
            leaf_count: root.len() as u32,
            leaf_offset,
            leaf_len: leaf_bytes.len() as u32,
            data_offset,
            data_len,
            tiles_addressed: self.tiles_addressed,
            bodies_written: self.distinct,
            min_lon_e7: self.options.min_lon_e7,
            min_lat_e7: self.options.min_lat_e7,
            max_lon_e7: self.options.max_lon_e7,
            max_lat_e7: self.options.max_lat_e7,
        };

        let mut prefix = Vec::with_capacity(data_offset as usize);
        prefix.extend_from_slice(&header.serialize());
        prefix.extend_from_slice(&dictionary);
        prefix.extend_from_slice(&root_bytes);
        prefix.extend_from_slice(&leaf_bytes);
        debug_assert_eq!(prefix.len() as u64, data_offset);
        Ok((Header::parse(&prefix)?, prefix))
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

/// How much of the data section is held in memory before it reaches the scratch file.
///
/// One mebibyte, which turns a California build's 1.9 M body appends into a few hundred writes
/// while staying noise next to the index it sits beside.
const BUFFER_BYTES: usize = 1 << 20;

/// The data section of an archive being written: a scratch file with its tail held in memory.
///
/// Two callers want different things from it. Appending wants a buffer, so that a body is not a
/// syscall. Confirming a content-dedup candidate wants random access to any *earlier* body — a seek
/// and a read for anything already on disk, but a slice compare for anything still in the buffer,
/// and the bodies most recently written are the ones a repeat is likeliest to match.
struct Spill {
    path: PathBuf,
    /// Opened for reading as well as writing, because a content-dedup compare reads back a body
    /// this same handle wrote.
    file: File,
    /// The tail of the section, not yet in `file`.
    buffer: Vec<u8>,
    /// Bytes that *are* in `file`, which is also the offset `buffer` begins at.
    flushed: u64,
    /// Reused by [`Self::matches_at`], so a compare against the file allocates nothing.
    scratch: Vec<u8>,
    // TEMPORARY instrumentation, to measure how often a content-dedup confirmation has to read.
    confirms: u64,
    confirms_from_file: u64,
    bytes_from_file: u64,
}

impl Spill {
    fn create(dir: Option<&Path>) -> Result<Spill> {
        /// Two writers in one process — which the test suite has, and a planet build may — must not
        /// share a scratch file.
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let dir = dir.map(Path::to_path_buf).unwrap_or_else(std::env::temp_dir);
        let path = dir.join(format!(
            "mamaps_bodies_{}_{}.tmp",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed),
        ));
        // Truncating rather than `create_new`: a run that died left its scratch behind, and once a
        // pid is recycled, refusing to build over an abandoned file would be a worse failure than
        // overwriting it.
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(&path)
            .map_err(|e| scratch_error(&path, "create", e))?;
        Ok(Spill {
            path,
            file,
            buffer: Vec::with_capacity(BUFFER_BYTES),
            flushed: 0,
            scratch: Vec::new(),
            confirms: 0,
            confirms_from_file: 0,
            bytes_from_file: 0,
        })
    }

    /// Bytes appended so far, buffered or not — which is the offset the next body will land at.
    fn len(&self) -> u64 {
        self.flushed + self.buffer.len() as u64
    }

    fn append(&mut self, bytes: &[u8]) -> Result<()> {
        if self.buffer.len() + bytes.len() > BUFFER_BYTES {
            self.flush()?;
        }
        if bytes.len() >= BUFFER_BYTES {
            // A body bigger than the buffer goes straight through, because buffering it would only
            // copy it twice on the way to the same place.
            let Spill { file, path, flushed, .. } = self;
            write_all_at(file, path, *flushed, bytes)?;
            *flushed += bytes.len() as u64;
        } else {
            self.buffer.extend_from_slice(bytes);
        }
        Ok(())
    }

    /// Whether the `candidate.len()` bytes at `offset` are exactly `candidate`.
    ///
    /// The compare that makes a content-dedup hit a fact rather than a probability. Answered out of
    /// the buffer when the body is still in it, which costs nothing at all.
    fn matches_at(&mut self, offset: u32, candidate: &[u8]) -> Result<bool> {
        let start = offset as u64;
        let end = start + candidate.len() as u64;
        debug_assert!(end <= self.len(), "a dedup candidate past the end of the data section");
        self.confirms += 1;
        if start >= self.flushed {
            let at = (start - self.flushed) as usize;
            return Ok(self.buffer[at..at + candidate.len()] == *candidate);
        }
        self.confirms_from_file += 1;
        self.bytes_from_file += candidate.len() as u64;
        // A body straddling the boundary is one the buffer holds only the tail of. Flushing is
        // simpler than stitching two compares together, and can only happen once per body.
        if end > self.flushed {
            self.flush()?;
        }
        let Spill { file, path, scratch, .. } = self;
        scratch.resize(candidate.len(), 0);
        read_exact_at(file, path, start, scratch)?;
        Ok(scratch[..] == *candidate)
    }

    fn flush(&mut self) -> Result<()> {
        if self.buffer.is_empty() {
            return Ok(());
        }
        let Spill { file, path, buffer, flushed, .. } = self;
        write_all_at(file, path, *flushed, buffer)?;
        *flushed += buffer.len() as u64;
        buffer.clear();
        Ok(())
    }

    /// Copy the whole section onto the end of the archive being written, and say how much.
    ///
    /// Through a buffer of its own rather than `std::io::copy`, whose eight kilobytes would make a
    /// California data section eighty thousand round trips in each direction.
    fn copy_to(&mut self, out: &mut File, out_path: &Path) -> Result<u64> {
        self.flush()?;
        let Spill { file, path, .. } = self;
        file.rewind().map_err(|e| scratch_error(path, "seek in", e))?;
        let mut chunk = vec![0u8; BUFFER_BYTES];
        let mut copied = 0u64;
        loop {
            let n = file.read(&mut chunk).map_err(|e| scratch_error(path, "read back from", e))?;
            if n == 0 {
                return Ok(copied);
            }
            out.write_all(&chunk[..n]).map_err(|e| {
                crate::proto::Error(format!(
                    "cannot copy the .mamaps data section into {}: {e}",
                    out_path.display(),
                ))
            })?;
            copied += n as u64;
        }
    }

    /// The same, onto the end of an archive being assembled in memory.
    ///
    /// Into a resized tail rather than through `read_to_end`, whose growth heuristics will happily
    /// double a 655 MB buffer and hold both halves while it copies — which is most of the peak this
    /// whole arrangement exists to remove. The length is known exactly, so nothing needs guessing.
    fn copy_to_vec(&mut self, out: &mut Vec<u8>) -> Result<u64> {
        self.flush()?;
        let at = out.len();
        let len = self.flushed;
        out.resize(at + len as usize, 0);
        let Spill { file, path, .. } = self;
        file.rewind().map_err(|e| scratch_error(path, "seek in", e))?;
        file.read_exact(&mut out[at..]).map_err(|e| scratch_error(path, "read back from", e))?;
        Ok(len)
    }
}

impl Drop for Spill {
    /// So a build that dies part way through cannot strand a copy of its data section — 652 MB for
    /// California and tens of gigabytes for a planet, which is why `tile_build::spill` does the
    /// same.
    ///
    /// The error is dropped on purpose. Scratch that outlives the process is untidy; panicking in a
    /// `Drop` while a real failure is unwinding would replace its diagnostic with this one.
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

/// Seeking before every access rather than tracking the cursor.
///
/// A dedup compare moves the file cursor, so a write that assumed it was still at the end would put
/// a body somewhere else in the data section — and the index would still say it was at the end.
/// These take the offset they want and are therefore immune to whatever ran last.
fn write_all_at(file: &mut File, path: &Path, offset: u64, bytes: &[u8]) -> Result<()> {
    file.seek(SeekFrom::Start(offset)).map_err(|e| scratch_error(path, "seek in", e))?;
    file.write_all(bytes).map_err(|e| scratch_error(path, "write to", e))
}

fn read_exact_at(file: &mut File, path: &Path, offset: u64, into: &mut [u8]) -> Result<()> {
    file.seek(SeekFrom::Start(offset)).map_err(|e| scratch_error(path, "seek in", e))?;
    file.read_exact(into).map_err(|e| scratch_error(path, "read back from", e))
}

fn scratch_error(path: &Path, doing: &str, e: std::io::Error) -> crate::proto::Error {
    crate::proto::Error(format!(
        "cannot {doing} the .mamaps body scratch file {}: {e}",
        path.display(),
    ))
}

/// Raw DEFLATE over everything but the body header.
///
/// Public so a generator can run it on a worker thread rather than leaving it on the appending
/// thread — see [`StreamWriter::append_stored`] for why that is the difference between using one
/// core and using all of them.
///
/// The 16-byte body header is left uncompressed ahead of the frame so a reader can read `raw_len`
/// out of it and allocate the output exactly once, before inflating a single byte.
pub fn compress_body(encoded: &[u8]) -> Vec<u8> {
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
