//! PMTiles v3 container — read and write.
//!
//! Both halves are needed: reading, because the `transit_stops` layer has to be
//! composited into the existing basemap archive, and writing, because that
//! composite has to come back out as a PMTiles the app can stream.
//!
//! Layout, as verified against the published `v5-ca.pmtiles`:
//!
//! ```text
//! [ 127-byte header ][ root directory ][ metadata ][ leaf directories ][ tile data ]
//! ```
//!
//! The header is fixed-width little-endian; everything else is at an offset it
//! names. Directories and metadata are compressed with `internal_compression`,
//! tiles with `tile_compression` — both gzip in practice.
//!
//! A **directory** is a columnar run of varints:
//!
//! ```text
//! uvarint num_entries
//! num_entries x uvarint  tile_id delta   (first is absolute)
//! num_entries x uvarint  run_length
//! num_entries x uvarint  length
//! num_entries x uvarint  offset          (0 = contiguous with the previous entry)
//! ```
//!
//! `run_length == 0` marks a **leaf pointer**: its offset/length address the leaf
//! directories section instead of tile data. `run_length >= 1` is a tile run, where
//! `run_length` consecutive tile ids all resolve to the same bytes — that is how
//! PMTiles collapses the vast runs of identical ocean tiles.
//!
//! Tile ids are positions along a **Hilbert curve**, offset by the number of tiles
//! in all lower zooms, so a z/x/y maps to a single integer whose neighbours are
//! spatially close. That locality is what lets the app fetch a screenful of tiles
//! in few range requests.

use crate::gz;
use crate::proto::{err, Error, Reader, Result, Writer};
use std::collections::HashMap;
use std::fs::File;
use std::io::{BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

pub const MAGIC: &[u8; 7] = b"PMTiles";
pub const SPEC_VERSION: u8 = 3;
pub const HEADER_LEN: usize = 127;

/// Compression ids from the spec. We only ever emit `Gzip`.
pub const COMPRESSION_NONE: u8 = 1;
pub const COMPRESSION_GZIP: u8 = 2;

/// Tile type ids from the spec.
pub const TILE_TYPE_MVT: u8 = 1;

/// The spec advises keeping the root directory small enough to come back in the
/// same request as the header, so a cold open is one round trip.
const MAX_ROOT_BYTES: usize = 16_384 - HEADER_LEN;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Entry {
    pub tile_id: u64,
    /// Relative to `tile_data_offset`, or to `leaf_dirs_offset` when this is a
    /// leaf pointer.
    pub offset: u64,
    pub length: u32,
    /// `0` = leaf pointer; `n >= 1` = this entry covers `tile_id..tile_id + n`.
    pub run_length: u32,
}

#[derive(Debug, Clone)]
pub struct Header {
    pub root_offset: u64,
    pub root_length: u64,
    pub metadata_offset: u64,
    pub metadata_length: u64,
    pub leaf_offset: u64,
    pub leaf_length: u64,
    pub tile_data_offset: u64,
    pub tile_data_length: u64,
    pub addressed_tiles: u64,
    pub tile_entries: u64,
    pub tile_contents: u64,
    pub clustered: bool,
    pub internal_compression: u8,
    pub tile_compression: u8,
    pub tile_type: u8,
    pub min_zoom: u8,
    pub max_zoom: u8,
    pub min_lon_e7: i32,
    pub min_lat_e7: i32,
    pub max_lon_e7: i32,
    pub max_lat_e7: i32,
    pub center_zoom: u8,
    pub center_lon_e7: i32,
    pub center_lat_e7: i32,
}

impl Header {
    pub fn parse(buf: &[u8]) -> Result<Header> {
        if buf.len() < HEADER_LEN {
            return err("PMTiles header shorter than 127 bytes");
        }
        if &buf[0..7] != MAGIC {
            return err("not a PMTiles archive (bad magic)");
        }
        if buf[7] != SPEC_VERSION {
            return err(format!("unsupported PMTiles spec version {}", buf[7]));
        }
        let u64_at = |o: usize| {
            u64::from_le_bytes([
                buf[o],
                buf[o + 1],
                buf[o + 2],
                buf[o + 3],
                buf[o + 4],
                buf[o + 5],
                buf[o + 6],
                buf[o + 7],
            ])
        };
        let i32_at =
            |o: usize| i32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]);
        Ok(Header {
            root_offset: u64_at(8),
            root_length: u64_at(16),
            metadata_offset: u64_at(24),
            metadata_length: u64_at(32),
            leaf_offset: u64_at(40),
            leaf_length: u64_at(48),
            tile_data_offset: u64_at(56),
            tile_data_length: u64_at(64),
            addressed_tiles: u64_at(72),
            tile_entries: u64_at(80),
            tile_contents: u64_at(88),
            clustered: buf[96] != 0,
            internal_compression: buf[97],
            tile_compression: buf[98],
            tile_type: buf[99],
            min_zoom: buf[100],
            max_zoom: buf[101],
            min_lon_e7: i32_at(102),
            min_lat_e7: i32_at(106),
            max_lon_e7: i32_at(110),
            max_lat_e7: i32_at(114),
            center_zoom: buf[118],
            center_lon_e7: i32_at(119),
            center_lat_e7: i32_at(123),
        })
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER_LEN);
        out.extend_from_slice(MAGIC);
        out.push(SPEC_VERSION);
        for v in [
            self.root_offset,
            self.root_length,
            self.metadata_offset,
            self.metadata_length,
            self.leaf_offset,
            self.leaf_length,
            self.tile_data_offset,
            self.tile_data_length,
            self.addressed_tiles,
            self.tile_entries,
            self.tile_contents,
        ] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        out.push(self.clustered as u8);
        out.push(self.internal_compression);
        out.push(self.tile_compression);
        out.push(self.tile_type);
        out.push(self.min_zoom);
        out.push(self.max_zoom);
        for v in [self.min_lon_e7, self.min_lat_e7, self.max_lon_e7, self.max_lat_e7] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        out.push(self.center_zoom);
        out.extend_from_slice(&self.center_lon_e7.to_le_bytes());
        out.extend_from_slice(&self.center_lat_e7.to_le_bytes());
        debug_assert_eq!(out.len(), HEADER_LEN);
        out
    }
}

// --- Hilbert tile ids ---------------------------------------------------------

/// Number of tiles in every zoom below `z`, i.e. the first tile id at `z`.
/// `(4^z - 1) / 3`.
pub fn zoom_base(z: u8) -> u64 {
    // Closed form via the geometric series; exact in u64 up to z=31.
    ((1u64 << (2 * z as u32)) - 1) / 3
}

/// Rotate/flip a quadrant. The canonical Hilbert helper.
fn rot(n: u64, x: &mut u64, y: &mut u64, rx: u64, ry: u64) {
    if ry == 0 {
        if rx == 1 {
            *x = n.wrapping_sub(1).wrapping_sub(*x);
            *y = n.wrapping_sub(1).wrapping_sub(*y);
        }
        std::mem::swap(x, y);
    }
}

/// `(z, x, y)` -> PMTiles tile id.
pub fn tile_id(z: u8, x: u64, y: u64) -> u64 {
    let n = 1u64 << z;
    let (mut x, mut y) = (x, y);
    let mut d = 0u64;
    let mut s = n / 2;
    while s > 0 {
        let rx = u64::from(x & s > 0);
        let ry = u64::from(y & s > 0);
        d += s * s * ((3 * rx) ^ ry);
        rot(n, &mut x, &mut y, rx, ry);
        s /= 2;
    }
    zoom_base(z) + d
}

/// PMTiles tile id -> `(z, x, y)`.
pub fn tile_zxy(id: u64) -> (u8, u64, u64) {
    let mut z = 0u8;
    // Walk up until the next zoom's base passes the id. Bounded by z=31.
    while z < 31 && id >= zoom_base(z + 1) {
        z += 1;
    }
    let mut t = id - zoom_base(z);
    let n = 1u64 << z;
    let (mut x, mut y) = (0u64, 0u64);
    let mut s = 1u64;
    while s < n {
        let rx = 1 & (t / 2);
        let ry = 1 & (t ^ rx);
        rot(s, &mut x, &mut y, rx, ry);
        x += s * rx;
        y += s * ry;
        t /= 4;
        s *= 2;
    }
    (z, x, y)
}

// --- Directory serialization --------------------------------------------------

/// Serialize a directory. Entries must be sorted ascending by `tile_id`.
pub fn serialize_directory(entries: &[Entry]) -> Vec<u8> {
    let mut w = Writer::with_capacity(entries.len() * 5);
    w.uvarint(entries.len() as u64);
    let mut last = 0u64;
    for e in entries {
        w.uvarint(e.tile_id - last);
        last = e.tile_id;
    }
    for e in entries {
        w.uvarint(e.run_length as u64);
    }
    for e in entries {
        w.uvarint(e.length as u64);
    }
    for (i, e) in entries.iter().enumerate() {
        // 0 is the "picks up exactly where the last one ended" shorthand, which is
        // the common case in a clustered archive and saves several bytes per entry.
        let contiguous = i > 0
            && e.offset == entries[i - 1].offset + entries[i - 1].length as u64;
        if contiguous {
            w.uvarint(0);
        } else {
            w.uvarint(e.offset + 1);
        }
    }
    w.into_vec()
}

/// Parse a directory body (already decompressed).
pub fn parse_directory(buf: &[u8]) -> Result<Vec<Entry>> {
    let mut r = Reader::new(buf);
    let n = r.uvarint()? as usize;
    // A directory is 5 varints per entry at minimum one byte each, so a count
    // wildly larger than the buffer is corruption, not a huge directory.
    if n > buf.len() {
        return err(format!("PMTiles directory claims {n} entries in {} bytes", buf.len()));
    }
    let mut entries = vec![
        Entry { tile_id: 0, offset: 0, length: 0, run_length: 0 };
        n
    ];
    let mut last = 0u64;
    for e in entries.iter_mut() {
        last += r.uvarint()?;
        e.tile_id = last;
    }
    for e in entries.iter_mut() {
        e.run_length = r.uvarint()? as u32;
    }
    for e in entries.iter_mut() {
        e.length = r.uvarint()? as u32;
    }
    for i in 0..n {
        let v = r.uvarint()?;
        entries[i].offset = if v == 0 {
            if i == 0 {
                return err("PMTiles directory's first entry uses the contiguous shorthand");
            }
            entries[i - 1].offset + entries[i - 1].length as u64
        } else {
            v - 1
        };
    }
    if !r.at_end() {
        return err("trailing bytes after PMTiles directory");
    }
    Ok(entries)
}

// --- Reading ------------------------------------------------------------------

/// A whole archive held in memory.
///
/// Fine for the layers we build (tens of MB). The 1.5 GB published basemap is
/// composited by streaming instead — see `tile_join`.
pub struct Archive {
    pub header: Header,
    pub metadata: Vec<u8>,
    root: Vec<Entry>,
    leaves: Vec<u8>,
    tile_data: Vec<u8>,
}

impl Archive {
    pub fn parse(buf: &[u8]) -> Result<Archive> {
        let header = Header::parse(buf)?;
        let slice = |off: u64, len: u64, what: &str| -> Result<&[u8]> {
            let (o, l) = (off as usize, len as usize);
            match o.checked_add(l).filter(|e| *e <= buf.len()) {
                Some(end) => Ok(&buf[o..end]),
                None => err(format!("PMTiles {what} runs past end of file")),
            }
        };
        let root_raw = slice(header.root_offset, header.root_length, "root directory")?;
        let root = parse_directory(&decompress(header.internal_compression, root_raw)?)?;
        let metadata = decompress(
            header.internal_compression,
            slice(header.metadata_offset, header.metadata_length, "metadata")?,
        )?;
        let leaves = slice(header.leaf_offset, header.leaf_length, "leaf directories")?.to_vec();
        let tile_data =
            slice(header.tile_data_offset, header.tile_data_length, "tile data")?.to_vec();
        Ok(Archive { header, metadata, root, leaves, tile_data })
    }

    /// Raw (still `tile_compression`-compressed) bytes for a tile, or `None`.
    pub fn tile_raw(&self, z: u8, x: u64, y: u64) -> Result<Option<&[u8]>> {
        let want = tile_id(z, x, y);
        let Some(entry) = find_entry(&self.root, want).copied() else { return Ok(None) };
        if entry.run_length > 0 {
            return self.tile_bytes(&entry).map(Some);
        }
        // A leaf pointer. The spec allows arbitrary nesting; producers emit two
        // levels, so cap the walk rather than risk looping on a corrupt file.
        let mut entry = entry;
        for _ in 0..4 {
            let leaf = self.read_leaf(&entry)?;
            let Some(e) = find_entry(&leaf, want).copied() else { return Ok(None) };
            if e.run_length > 0 {
                return self.tile_bytes(&e).map(Some);
            }
            entry = e;
        }
        err("PMTiles directory nested deeper than 4 levels")
    }

    /// Tile-data bytes an entry addresses.
    fn tile_bytes(&self, e: &Entry) -> Result<&[u8]> {
        let (o, l) = (e.offset as usize, e.length as usize);
        match o.checked_add(l).filter(|v| *v <= self.tile_data.len()) {
            Some(end) => Ok(&self.tile_data[o..end]),
            None => err("PMTiles tile entry runs past tile data"),
        }
    }

    /// Parse the leaf directory a leaf-pointer entry addresses.
    fn read_leaf(&self, e: &Entry) -> Result<Vec<Entry>> {
        let (o, l) = (e.offset as usize, e.length as usize);
        let end = match o.checked_add(l).filter(|v| *v <= self.leaves.len()) {
            Some(v) => v,
            None => return err("PMTiles leaf entry runs past leaf section"),
        };
        parse_directory(&decompress(self.header.internal_compression, &self.leaves[o..end])?)
    }

    /// Decompressed tile bytes (an MVT body), or `None` when absent.
    pub fn tile(&self, z: u8, x: u64, y: u64) -> Result<Option<Vec<u8>>> {
        match self.tile_raw(z, x, y)? {
            None => Ok(None),
            Some(raw) => Ok(Some(decompress(self.header.tile_compression, raw)?)),
        }
    }

    /// Every `(tile_id, raw bytes)` in the archive, ascending. Runs are expanded,
    /// so a run of identical ocean tiles yields one item per tile id.
    /// Every `(tile_id, body offset, body length)` this archive holds, ascending.
    ///
    /// Unlike [`Archive::iter_tiles`] this hands back OFFSETS rather than slices, so a
    /// caller can hold the whole tile list of a 26-million-tile archive without also
    /// holding its 8 GB of bodies. Runs are expanded, because a run means several ids
    /// share one body and each id still needs its own row.
    ///
    /// The offset is `u64` and must stay that way. A single planet layer's data section
    /// is well past `u32::MAX` -- `maxspeed` alone is 8.3 GB -- so narrowing it silently
    /// wraps every offset beyond 4 GiB onto the wrong bytes, which surfaces as
    /// "not a gzip stream" halfway through a merge.
    pub fn tile_offsets(&self) -> Result<Vec<(u64, u64, u32)>> {
        let mut out = Vec::new();
        self.visit_entries(&mut |e| {
            for k in 0..e.run_length as u64 {
                out.push((e.tile_id + k, e.offset, e.length));
            }
            Ok(())
        })?;
        Ok(out)
    }

    /// Call `visit` once per tile entry, ascending by `tile_id`, runs unexpanded.
    ///
    /// The in-memory counterpart of [`ArchiveFile::visit_entries`], with the same
    /// contract, so a caller written against one works against the other.
    pub fn visit_entries(&self, visit: &mut dyn FnMut(&Entry) -> Result<()>) -> Result<()> {
        for e in &self.root {
            if e.run_length > 0 {
                check_body(&self.header, e)?;
                visit(e)?;
                continue;
            }
            let (o, l) = (e.offset as usize, e.length as usize);
            if o + l > self.leaves.len() {
                return err("PMTiles leaf entry runs past leaf section");
            }
            let leaf = parse_directory(&decompress(
                self.header.internal_compression,
                &self.leaves[o..o + l],
            )?)?;
            for le in &leaf {
                if le.run_length > 0 {
                    check_body(&self.header, le)?;
                    visit(le)?;
                }
            }
        }
        Ok(())
    }

    /// The still-compressed body at `offset..offset + length` in the data section.
    ///
    /// `offset` is `u64`: a planet layer's data section is far larger than `u32::MAX`.
    pub fn body_at(&self, offset: u64, length: u32) -> Result<&[u8]> {
        let end = offset
            .checked_add(length as u64)
            .filter(|e| *e <= self.tile_data.len() as u64);
        match end {
            Some(end) => Ok(&self.tile_data[offset as usize..end as usize]),
            None => err(format!(
                "PMTiles body at {offset}+{length} runs past the {}-byte tile data section",
                self.tile_data.len()
            )),
        }
    }

    pub fn iter_tiles(&self) -> Result<Vec<(u64, &[u8])>> {
        let mut out = Vec::new();
        for e in &self.root {
            if e.run_length > 0 {
                push_run(&mut out, e, &self.tile_data)?;
            } else {
                let (o, l) = (e.offset as usize, e.length as usize);
                if o + l > self.leaves.len() {
                    return err("PMTiles leaf entry runs past leaf section");
                }
                let leaf = parse_directory(&decompress(
                    self.header.internal_compression,
                    &self.leaves[o..o + l],
                )?)?;
                for le in &leaf {
                    if le.run_length > 0 {
                        push_run(&mut out, le, &self.tile_data)?;
                    }
                }
            }
        }
        Ok(out)
    }
}

fn push_run<'a>(out: &mut Vec<(u64, &'a [u8])>, e: &Entry, data: &'a [u8]) -> Result<()> {
    let (o, l) = (e.offset as usize, e.length as usize);
    if o + l > data.len() {
        return err("PMTiles tile entry runs past tile data");
    }
    let bytes = &data[o..o + l];
    for k in 0..e.run_length as u64 {
        out.push((e.tile_id + k, bytes));
    }
    Ok(())
}

// --- Reading from a file, without holding it -----------------------------------

/// An archive read through a file handle, holding only its root directory.
///
/// [`Archive`] needs the whole file resident, which puts a second ceiling on every
/// planet job: `tile_join` read each input with `std::fs::read` and then `parse`
/// copied the leaf and data sections again, so a 17 GB merge needed roughly 34 GB
/// before the writer had allocated anything. This keeps the root directory and the
/// metadata — kilobytes — and reads one leaf directory or one tile body at a time into
/// a buffer it reuses.
///
/// Its entry walk takes `&mut self`, because reading a leaf directory reuses one
/// buffer. [`ArchiveFile::body_into`] does **not**: it reads at an explicit offset
/// without touching the handle's cursor, so any number of threads can pull bodies
/// from one archive at once. That is what lets `tile_join` merge in parallel.
///
/// `mmap` would also allow that and would avoid the copy, but it needs a dependency
/// and `unsafe`, and it turns a truncated file or an I/O error into a fault rather
/// than an `Err`. A positional read gets the same concurrency for neither price.
pub struct ArchiveFile {
    /// Declared first so the handle closes before [`Scratch`]-style cleanup or any
    /// caller's `remove_file` runs. Nothing depends on it today, but the ordering is
    /// free and Windows is unforgiving about open handles.
    file: File,
    path: PathBuf,
    pub header: Header,
    pub metadata: Vec<u8>,
    root: Vec<Entry>,
    /// Reused across leaf reads: the published basemap has 324 of them.
    leaf_buf: Vec<u8>,
}

impl ArchiveFile {
    /// Just the 127-byte header, for a caller that only wants to report on an archive.
    ///
    /// What `tile_join` prints its summary from. Re-reading a finished planet archive
    /// to recover its zoom range cost 8 GB of reads and an 8 GB allocation.
    pub fn read_header(path: impl AsRef<Path>) -> Result<Header> {
        let path = path.as_ref();
        let mut file = File::open(path)
            .map_err(|e| Error(format!("cannot read {}: {e}", path.display())))?;
        let mut head = [0u8; HEADER_LEN];
        file.read_exact(&mut head)
            .map_err(|e| Error(format!("reading {}'s header: {e}", path.display())))?;
        Header::parse(&head)
    }

    pub fn open(path: impl Into<PathBuf>) -> Result<ArchiveFile> {
        let path = path.into();
        let mut file = File::open(&path)
            .map_err(|e| Error(format!("cannot read {}: {e}", path.display())))?;
        let file_len = file
            .metadata()
            .map_err(|e| Error(format!("cannot stat {}: {e}", path.display())))?
            .len();

        let mut head = [0u8; HEADER_LEN];
        file.read_exact(&mut head)
            .map_err(|e| Error(format!("reading {}'s header: {e}", path.display())))?;
        let header = Header::parse(&head)?;

        let root_raw = read_section(
            &mut file,
            file_len,
            header.root_offset,
            header.root_length,
            "root directory",
        )?;
        let root = parse_directory(&decompress(header.internal_compression, &root_raw)?)?;
        let metadata_raw = read_section(
            &mut file,
            file_len,
            header.metadata_offset,
            header.metadata_length,
            "metadata",
        )?;
        let metadata = decompress(header.internal_compression, &metadata_raw)?;
        // Checked once here so every later read can be bounds-checked against the
        // section length alone, exactly as `Archive` checks against its slice.
        for (off, len, what) in [
            (header.leaf_offset, header.leaf_length, "leaf directories"),
            (header.tile_data_offset, header.tile_data_length, "tile data"),
        ] {
            if off.checked_add(len).filter(|e| *e <= file_len).is_none() {
                return err(format!("PMTiles {what} runs past end of file"));
            }
        }

        Ok(ArchiveFile {
            file,
            path,
            header,
            metadata,
            root,
            leaf_buf: Vec::new(),
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Call `visit` once per tile entry in the archive, ascending by `tile_id`.
    ///
    /// Runs are **not** expanded: an entry covering `run_length` ids arrives once, and
    /// a caller that needs a row per id expands it itself. Leaf directories are read
    /// and inflated one at a time, so resident bytes are the root plus one leaf.
    pub fn visit_entries(
        &mut self,
        visit: &mut dyn FnMut(&Entry) -> Result<()>,
    ) -> Result<()> {
        // Indexed rather than iterated: reading a leaf needs `&mut self`, and `Entry`
        // is `Copy`, so a shared borrow of `root` would only be in the way.
        for i in 0..self.root.len() {
            let e = self.root[i];
            if e.run_length > 0 {
                check_body(&self.header, &e)?;
                visit(&e)?;
                continue;
            }
            let leaf = self.read_leaf(&e)?;
            for le in &leaf {
                if le.run_length > 0 {
                    check_body(&self.header, le)?;
                    visit(le)?;
                }
            }
        }
        Ok(())
    }

    fn read_leaf(&mut self, e: &Entry) -> Result<Vec<Entry>> {
        let len = e.length as u64;
        if e.offset
            .checked_add(len)
            .filter(|v| *v <= self.header.leaf_length)
            .is_none()
        {
            return err("PMTiles leaf entry runs past leaf section");
        }
        let at = self.header.leaf_offset + e.offset;
        self.leaf_buf.clear();
        self.leaf_buf.resize(len as usize, 0);
        self.file
            .seek(SeekFrom::Start(at))
            .map_err(|e| Error(format!("seeking {} to {at}: {e}", self.path.display())))?;
        self.file
            .read_exact(&mut self.leaf_buf)
            .map_err(|e| Error(format!("reading a leaf directory of {}: {e}", self.path.display())))?;
        parse_directory(&decompress(self.header.internal_compression, &self.leaf_buf)?)
    }

    /// Read the still-compressed body at `offset..offset + length` into `out`.
    ///
    /// `out` is a caller-owned buffer so a merge loop allocates once rather than once
    /// per tile. `offset` is `u64`: a planet layer's data section is far past
    /// `u32::MAX`.
    ///
    /// Takes `&self`: see [`read_exact_at`] for why, and [`ArchiveFile`] for what it
    /// buys.
    pub fn body_into(&self, offset: u64, length: u32, out: &mut Vec<u8>) -> Result<()> {
        if offset
            .checked_add(length as u64)
            .filter(|e| *e <= self.header.tile_data_length)
            .is_none()
        {
            return err(format!(
                "PMTiles body at {offset}+{length} runs past the {}-byte tile data section",
                self.header.tile_data_length
            ));
        }
        let at = self.header.tile_data_offset + offset;
        out.clear();
        out.resize(length as usize, 0);
        read_exact_at(&self.file, out, at)
            .map_err(|e| Error(format!("reading a tile body of {}: {e}", self.path.display())))
    }
}

/// Fill `buf` from `offset` without moving the file's cursor.
///
/// The cursor is the only reason a read would need `&mut File`, and it is shared
/// between clones of a handle — so seek-then-read cannot be done concurrently on one
/// archive, while this can. Both platforms expose a positional read; neither is
/// guaranteed to return everything at once, hence the loop.
///
/// `pub` because [`crate::stream::StreamArchive`] and `tile_build`'s
/// `spill::NormalizedChunks` both need the same thing for the same reason: many
/// threads pulling disjoint ranges out of one file. It was `pub(crate)` while the
/// only caller lived in this crate.
pub fn read_exact_at(
    file: &File,
    mut buf: &mut [u8],
    mut offset: u64,
) -> std::io::Result<()> {
    while !buf.is_empty() {
        #[cfg(windows)]
        let n = std::os::windows::fs::FileExt::seek_read(file, buf, offset)?;
        #[cfg(unix)]
        let n = std::os::unix::fs::FileExt::read_at(file, buf, offset)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "file ended mid-body",
            ));
        }
        buf = &mut buf[n..];
        offset += n as u64;
    }
    Ok(())
}

/// An entry's body must lie inside the data section. Shared by both readers so the
/// message a corrupt archive produces does not depend on which one opened it.
fn check_body(header: &Header, e: &Entry) -> Result<()> {
    if e.offset
        .checked_add(e.length as u64)
        .filter(|v| *v <= header.tile_data_length)
        .is_none()
    {
        return err("PMTiles entry runs past the tile data section");
    }
    Ok(())
}

fn read_section(
    file: &mut File,
    file_len: u64,
    offset: u64,
    length: u64,
    what: &str,
) -> Result<Vec<u8>> {
    if offset
        .checked_add(length)
        .filter(|e| *e <= file_len)
        .is_none()
    {
        return err(format!("PMTiles {what} runs past end of file"));
    }
    file.seek(SeekFrom::Start(offset))
        .map_err(|e| Error(format!("seeking to the {what}: {e}")))?;
    let mut buf = vec![0u8; length as usize];
    file.read_exact(&mut buf)
        .map_err(|e| Error(format!("reading the {what}: {e}")))?;
    Ok(buf)
}

/// Find the entry covering `want`: the last entry whose `tile_id <= want`, then a
/// bounds check against its run.
pub(crate) fn find_entry(entries: &[Entry], want: u64) -> Option<&Entry> {
    let idx = match entries.binary_search_by(|e| e.tile_id.cmp(&want)) {
        Ok(i) => i,
        Err(0) => return None,
        Err(i) => i - 1,
    };
    let e = &entries[idx];
    if e.run_length == 0 {
        // Leaf pointers cover everything from their id to the next entry's.
        return Some(e);
    }
    if want < e.tile_id + e.run_length as u64 {
        Some(e)
    } else {
        None
    }
}

pub(crate) fn decompress(kind: u8, data: &[u8]) -> Result<Vec<u8>> {
    match kind {
        COMPRESSION_NONE => Ok(data.to_vec()),
        COMPRESSION_GZIP => gz::decompress(data),
        other => err(format!("unsupported PMTiles compression {other}")),
    }
}

fn compress(kind: u8, data: &[u8]) -> Result<Vec<u8>> {
    match kind {
        COMPRESSION_NONE => Ok(data.to_vec()),
        COMPRESSION_GZIP => Ok(gz::compress(data)),
        other => err(format!("unsupported PMTiles compression {other}")),
    }
}

// --- Writing ------------------------------------------------------------------

/// Accumulates tiles and serializes a whole v3 archive.
pub struct Builder {
    /// `tile_id -> already tile-compressed bytes`.
    tiles: Vec<(u64, Vec<u8>)>,
    pub metadata: Vec<u8>,
    pub min_zoom: u8,
    pub max_zoom: u8,
    pub min_lon_e7: i32,
    pub min_lat_e7: i32,
    pub max_lon_e7: i32,
    pub max_lat_e7: i32,
    pub center_zoom: u8,
    pub center_lon_e7: i32,
    pub center_lat_e7: i32,
}

impl Default for Builder {
    fn default() -> Self {
        Builder::new()
    }
}

impl Builder {
    pub fn new() -> Builder {
        Builder {
            tiles: Vec::new(),
            metadata: b"{}".to_vec(),
            min_zoom: 0,
            max_zoom: 0,
            // Whole Mercator world, as the published archive uses.
            min_lon_e7: -1_800_000_000,
            min_lat_e7: -850_511_290,
            max_lon_e7: 1_800_000_000,
            max_lat_e7: 850_511_290,
            center_zoom: 0,
            center_lon_e7: 0,
            center_lat_e7: 0,
        }
    }

    /// Add a tile from its uncompressed MVT body.
    pub fn add_tile(&mut self, z: u8, x: u64, y: u64, mvt: &[u8]) {
        self.add_tile_raw(tile_id(z, x, y), gz::compress(mvt));
    }

    /// Add a tile that is already gzipped, e.g. copied straight out of another
    /// archive without a decode/re-encode cycle.
    pub fn add_tile_raw(&mut self, tile_id: u64, compressed: Vec<u8>) {
        self.tiles.push((tile_id, compressed));
    }

    pub fn is_empty(&self) -> bool {
        self.tiles.is_empty()
    }

    pub fn len(&self) -> usize {
        self.tiles.len()
    }

    /// Serialize the archive.
    pub fn build(mut self) -> Result<Vec<u8>> {
        self.tiles.sort_by_key(|(id, _)| *id);
        self.tiles.dedup_by_key(|(id, _)| *id);

        // Deduplicate identical tile bodies: a basemap is mostly repeated ocean and
        // empty land, and this is what makes an archive of 1.5 M tiles hold ~1 M
        // distinct bodies.
        let mut blob = Vec::new();
        let mut seen: HashMap<Vec<u8>, (u64, u32)> = HashMap::new();
        let mut entries: Vec<Entry> = Vec::with_capacity(self.tiles.len());
        let mut addressed = 0u64;
        for (id, data) in &self.tiles {
            addressed += 1;
            let (offset, length) = match seen.get(data) {
                Some(&v) => v,
                None => {
                    let v = (blob.len() as u64, data.len() as u32);
                    blob.extend_from_slice(data);
                    seen.insert(data.clone(), v);
                    v
                }
            };
            // Extend the previous entry's run when this tile is the next id and
            // resolves to the same bytes.
            if let Some(prev) = entries.last_mut() {
                if prev.offset == offset
                    && prev.length == length
                    && prev.tile_id + prev.run_length as u64 == *id
                {
                    prev.run_length += 1;
                    continue;
                }
            }
            entries.push(Entry { tile_id: *id, offset, length, run_length: 1 });
        }
        let tile_contents = seen.len() as u64;
        let tile_entries = entries.len() as u64;

        let (root_body, leaf_body) = self.split_directories(&entries)?;
        let root = compress(COMPRESSION_GZIP, &root_body)?;
        let metadata = compress(COMPRESSION_GZIP, &self.metadata)?;

        let root_offset = HEADER_LEN as u64;
        let metadata_offset = root_offset + root.len() as u64;
        let leaf_offset = metadata_offset + metadata.len() as u64;
        let tile_data_offset = leaf_offset + leaf_body.len() as u64;

        let header = Header {
            root_offset,
            root_length: root.len() as u64,
            metadata_offset,
            metadata_length: metadata.len() as u64,
            leaf_offset,
            leaf_length: leaf_body.len() as u64,
            tile_data_offset,
            tile_data_length: blob.len() as u64,
            addressed_tiles: addressed,
            tile_entries,
            tile_contents,
            // Entries are written in ascending tile_id and their offsets ascend with
            // them, which is exactly what `clustered` promises a reader.
            clustered: true,
            internal_compression: COMPRESSION_GZIP,
            tile_compression: COMPRESSION_GZIP,
            tile_type: TILE_TYPE_MVT,
            min_zoom: self.min_zoom,
            max_zoom: self.max_zoom,
            min_lon_e7: self.min_lon_e7,
            min_lat_e7: self.min_lat_e7,
            max_lon_e7: self.max_lon_e7,
            max_lat_e7: self.max_lat_e7,
            center_zoom: self.center_zoom,
            center_lon_e7: self.center_lon_e7,
            center_lat_e7: self.center_lat_e7,
        };

        let mut out = Vec::with_capacity(
            HEADER_LEN + root.len() + metadata.len() + leaf_body.len() + blob.len(),
        );
        out.extend_from_slice(&header.serialize());
        out.extend_from_slice(&root);
        out.extend_from_slice(&metadata);
        out.extend_from_slice(&leaf_body);
        out.extend_from_slice(&blob);
        Ok(out)
    }
}

/// The scratch file's lifetime, so a run that dies mid-planet cannot strand tens of
/// gigabytes. Follows `osm_ingest::chains::Spill`.
///
/// A field of its own rather than `impl Drop for StreamBuilder`, because a struct's
/// fields drop in declaration order and the removal must happen **after** the write
/// handle closes: Windows refuses to delete a file something still holds open. So
/// `data` is declared first and this second.
struct Scratch(PathBuf);

impl Drop for Scratch {
    fn drop(&mut self) {
        // Best effort: a leftover scratch is bad, but failing a finished build over an
        // undeletable temporary would be worse.
        let _ = std::fs::remove_file(&self.0);
    }
}

/// A [`Builder`] that never holds the archive in memory.
///
/// [`Builder`] is fine for a single layer but cannot assemble a planet-scale join: it
/// keeps every body in `tiles`, a deduplicated copy in `blob`, a third copy as the keys
/// of its `seen` map, and then serialises a fourth into one `Vec<u8>`. Measured on the
/// real overlay merge — 36.8 M tiles, 17 GB of input — that is about 76 GB and it is
/// killed long before it finishes.
///
/// This writes each body straight to a scratch file as it arrives and keeps only the
/// directory in memory (24 bytes per entry), so peak memory is set by the tile COUNT
/// rather than the tile BYTES. The scratch file is needed because PMTiles puts its
/// directories before its data and their size is not known until the last tile is in.
///
/// Bodies are still deduplicated, which matters — an overlay archive is mostly repeated
/// empty tiles. The map is keyed by a 64-bit hash and a length, and a hit is CONFIRMED
/// by re-reading the candidate body out of the scratch file and comparing it. Trusting
/// a hash alone would silently serve one tile's bytes for another about once every
/// 27,000 planet builds, which is far too often for a corruption that nothing
/// downstream can detect.
///
/// Tiles must arrive in ascending `tile_id`, which is what lets runs be coalesced and
/// what `clustered` promises a reader. [`StreamBuilder::add_tile_raw`] enforces it
/// rather than trusting the caller.
pub struct StreamBuilder {
    data: BufWriter<File>,
    scratch: Scratch,
    data_len: u64,
    /// How much of `data` has actually reached the file. Lets a dedup lookup skip the
    /// flush unless the body it wants is still sitting in the write buffer.
    flushed: u64,
    entries: Vec<Entry>,
    /// `(hash, length) -> the offsets already written with that hash`. Several offsets
    /// per key only when a hash collides, which is why it is a list.
    seen: HashMap<(u64, u32), Vec<u64>>,
    addressed: u64,
    distinct: u64,
    last_id: Option<u64>,
    pub metadata: Vec<u8>,
    pub min_zoom: u8,
    pub max_zoom: u8,
    pub min_lon_e7: i32,
    pub min_lat_e7: i32,
    pub max_lon_e7: i32,
    pub max_lat_e7: i32,
    pub center_zoom: u8,
    pub center_lon_e7: i32,
    pub center_lat_e7: i32,
}

impl StreamBuilder {
    /// `scratch` is created, written through, and removed by [`Self::finish`]. Put it
    /// on the same filesystem as the output: the last step copies it there.
    pub fn new(scratch: impl Into<PathBuf>) -> Result<StreamBuilder> {
        let scratch_path = scratch.into();
        // Read AND write: dedup confirms a hash hit by reading the candidate body back,
        // and `File::create` alone gives a write-only handle that then fails on Windows.
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(&scratch_path)
            .map_err(|e| Error(format!("cannot create {}: {e}", scratch_path.display())))?;
        Ok(StreamBuilder {
            data: BufWriter::with_capacity(1 << 20, file),
            scratch: Scratch(scratch_path),
            data_len: 0,
            flushed: 0,
            entries: Vec::new(),
            seen: HashMap::new(),
            addressed: 0,
            distinct: 0,
            last_id: None,
            metadata: Vec::new(),
            min_zoom: 0,
            max_zoom: 0,
            min_lon_e7: -1_800_000_000,
            min_lat_e7: -850_511_287,
            max_lon_e7: 1_800_000_000,
            max_lat_e7: 850_511_287,
            center_zoom: 0,
            center_lon_e7: 0,
            center_lat_e7: 0,
        })
    }

    /// Append one already-compressed tile. Ids must ascend.
    pub fn add_tile_raw(&mut self, tile_id: u64, compressed: &[u8]) -> Result<()> {
        if let Some(prev) = self.last_id {
            if tile_id <= prev {
                return err(format!(
                    "StreamBuilder needs ascending tile ids, got {tile_id} after {prev}"
                ));
            }
        }
        self.last_id = Some(tile_id);
        self.addressed += 1;

        let key = (hash64(compressed), compressed.len() as u32);
        // Field borrows taken separately: `seen` is read while `data` is written, which
        // is why the comparison is a free function rather than a method on self.
        let mut found: Option<u64> = None;
        if let Some(offsets) = self.seen.get(&key) {
            for &off in offsets {
                if body_matches(&mut self.data, &mut self.flushed, off, compressed)? {
                    found = Some(off);
                    break;
                }
            }
        }
        let offset = match found {
            Some(off) => off,
            None => {
                let off = self.data_len;
                self.data
                    .write_all(compressed)
                    .map_err(|e| Error(format!("writing tile data: {e}")))?;
                self.data_len += compressed.len() as u64;
                self.distinct += 1;
                self.seen.entry(key).or_default().push(off);
                off
            }
        };

        let length = compressed.len() as u32;
        if let Some(prev) = self.entries.last_mut() {
            if prev.offset == offset
                && prev.length == length
                && prev.tile_id + prev.run_length as u64 == tile_id
            {
                prev.run_length += 1;
                return Ok(());
            }
        }
        self.entries.push(Entry { tile_id, offset, length, run_length: 1 });
        Ok(())
    }

    /// Assemble the archive at `out`, then remove the scratch file.
    pub fn finish(mut self, out: impl AsRef<Path>) -> Result<()> {
        self.data
            .flush()
            .map_err(|e| Error(format!("flushing tile data: {e}")))?;
        self.flushed = self.data_len;

        let (root_body, leaf_body) = split_entries(&self.entries)?;
        let root = compress(COMPRESSION_GZIP, &root_body)?;
        let metadata = compress(COMPRESSION_GZIP, &self.metadata)?;
        let root_offset = HEADER_LEN as u64;
        let metadata_offset = root_offset + root.len() as u64;
        let leaf_offset = metadata_offset + metadata.len() as u64;
        let tile_data_offset = leaf_offset + leaf_body.len() as u64;
        let header = Header {
            root_offset,
            root_length: root.len() as u64,
            metadata_offset,
            metadata_length: metadata.len() as u64,
            leaf_offset,
            leaf_length: leaf_body.len() as u64,
            tile_data_offset,
            tile_data_length: self.data_len,
            addressed_tiles: self.addressed,
            tile_entries: self.entries.len() as u64,
            tile_contents: self.distinct,
            clustered: true,
            internal_compression: COMPRESSION_GZIP,
            tile_compression: COMPRESSION_GZIP,
            tile_type: TILE_TYPE_MVT,
            min_zoom: self.min_zoom,
            max_zoom: self.max_zoom,
            min_lon_e7: self.min_lon_e7,
            min_lat_e7: self.min_lat_e7,
            max_lon_e7: self.max_lon_e7,
            max_lat_e7: self.max_lat_e7,
            center_zoom: self.center_zoom,
            center_lon_e7: self.center_lon_e7,
            center_lat_e7: self.center_lat_e7,
        };

        let out = out.as_ref();
        let mut w = BufWriter::with_capacity(
            1 << 20,
            File::create(out).map_err(|e| Error(format!("cannot create {}: {e}", out.display())))?,
        );
        let mut put = |bytes: &[u8]| -> Result<()> {
            w.write_all(bytes)
                .map_err(|e| Error(format!("writing {}: {e}", out.display())))
        };
        put(&header.serialize())?;
        put(&root)?;
        put(&metadata)?;
        put(&leaf_body)?;

        // Stream the data section across rather than loading it: it is the whole point
        // of the scratch file. Read through `get_mut` rather than `into_inner`, so no
        // field is moved out and `Scratch` stays in charge of the cleanup.
        let src = self.data.get_mut();
        src.seek(SeekFrom::Start(0))
            .map_err(|e| Error(format!("rewinding scratch: {e}")))?;
        let mut buf = vec![0u8; 1 << 20];
        let mut copied = 0u64;
        loop {
            let n = src
                .read(&mut buf)
                .map_err(|e| Error(format!("reading scratch: {e}")))?;
            if n == 0 {
                break;
            }
            w.write_all(&buf[..n])
                .map_err(|e| Error(format!("writing {}: {e}", out.display())))?;
            copied += n as u64;
        }
        if copied != self.data_len {
            return err(format!(
                "scratch {} held {copied} tile byte(s), expected {}",
                self.scratch.0.display(),
                self.data_len
            ));
        }
        w.flush()
            .map_err(|e| Error(format!("flushing {}: {e}", out.display())))?;
        // `self` — and with it `Scratch` — drops here, which is what removes the
        // scratch file.
        Ok(())
    }
}

/// FNV-1a. Only ever a bucket key - every hit is confirmed by comparing bytes - so it
/// needs to be fast and well spread, not collision-proof.
fn hash64(data: &[u8]) -> u64 {
    let mut h = 0xcbf2_9ce4_8422_2325u64;
    for &b in data {
        h ^= b as u64;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}

/// Read a body back out of the scratch file and compare it, so a hash hit is a fact
/// rather than a probability.
///
/// Flushes only when the candidate is still in the write buffer. Duplicates point at
/// much earlier offsets in practice, so the 1 MiB buffer keeps working and the read is
/// served by the page cache.
fn body_matches(
    data: &mut BufWriter<File>,
    flushed: &mut u64,
    offset: u64,
    want: &[u8],
) -> Result<bool> {
    let end = offset + want.len() as u64;
    if end > *flushed {
        data.flush()
            .map_err(|e| Error(format!("flushing tile data: {e}")))?;
        let at = data
            .get_mut()
            .stream_position()
            .map_err(|e| Error(format!("reading scratch position: {e}")))?;
        *flushed = at;
    }
    let file = data.get_mut();
    file.seek(SeekFrom::Start(offset))
        .map_err(|e| Error(format!("seeking scratch to {offset}: {e}")))?;
    let mut buf = vec![0u8; want.len()];
    let read = file.read_exact(&mut buf);
    // Restore the append position before propagating anything, or the next write lands
    // in the middle of the data section.
    file.seek(SeekFrom::End(0))
        .map_err(|e| Error(format!("restoring scratch position: {e}")))?;
    read.map_err(|e| Error(format!("re-reading scratch at {offset}: {e}")))?;
    Ok(buf == want)
}

impl Builder {
    fn split_directories(&self, entries: &[Entry]) -> Result<(Vec<u8>, Vec<u8>)> {
        split_entries(entries)
    }
}

/// Fit the entries into a root directory small enough for a one-request open,
/// spilling into leaf directories when they do not fit.
///
/// Free-standing because both writers need it and neither owns the entries.
fn split_entries(entries: &[Entry]) -> Result<(Vec<u8>, Vec<u8>)> {
        let flat = serialize_directory(entries);
        if flat.len() <= MAX_ROOT_BYTES {
            return Ok((flat, Vec::new()));
        }
        // Grow the leaf size until one entry per leaf fits in the root. Starting at
        // 4096 matches what the published archive uses.
        let mut leaf_size = 4096usize;
        for _ in 0..12 {
            let mut root: Vec<Entry> = Vec::new();
            let mut leaves: Vec<u8> = Vec::new();
            for chunk in entries.chunks(leaf_size) {
                let body = compress(COMPRESSION_GZIP, &serialize_directory(chunk))?;
                root.push(Entry {
                    tile_id: chunk[0].tile_id,
                    offset: leaves.len() as u64,
                    length: body.len() as u32,
                    run_length: 0,
                });
                leaves.extend_from_slice(&body);
            }
            let root_body = serialize_directory(&root);
            if root_body.len() <= MAX_ROOT_BYTES {
                return Ok((root_body, leaves));
            }
            leaf_size *= 2;
        }
        err("cannot fit a PMTiles root directory even with very large leaves")
}
#[cfg(test)]
mod tests {
    use super::*;

    /// Real bytes 0..1479 of the published archive: the 127-byte header plus its
    /// whole gzipped root directory.
    const REAL_HEAD: &[u8] = include_bytes!("../tests/fixtures/v5ca_header_rootdir.bin");
    const REAL_TILE_MVT: &[u8] = include_bytes!("../tests/fixtures/v5ca_z11_tile.mvt");

    /// A body offset past `u32::MAX` must survive [`Archive::tile_offsets`] intact.
    ///
    /// This is the bug that killed the first planet join at 44%: the offset was
    /// narrowed to `u32`, so every body beyond 4 GiB pointed at the wrong bytes and
    /// surfaced as "not a gzip stream". No fixture is that large, so the type is
    /// exercised directly instead.
    #[test]
    fn a_body_offset_beyond_four_gigabytes_is_not_truncated() {
        let big = 5_000_000_000u64;
        assert!(big > u32::MAX as u64, "the case only matters past u32");

        // What `tile_offsets` does to an entry, in isolation.
        let e = Entry { tile_id: 7, offset: big, length: 128, run_length: 3 };
        let rows: Vec<(u64, u64, u32)> = (0..e.run_length as u64)
            .map(|k| (e.tile_id + k, e.offset, e.length))
            .collect();
        assert_eq!(
            rows,
            vec![(7, big, 128), (8, big, 128), (9, big, 128)],
            "the run expands and the offset is carried at full width"
        );

        // And the reader's bounds check must reject it against a small section rather
        // than wrapping into a valid-looking index.
        let mut b = Builder::new();
        b.min_zoom = 1;
        b.max_zoom = 1;
        b.add_tile(1, 0, 0, b"body");
        let bytes = b.build().unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert!(
            a.body_at(big, 128).is_err(),
            "an out-of-range offset must error, not index"
        );
    }

    #[test]
    fn zoom_base_matches_the_closed_form() {
        assert_eq!(zoom_base(0), 0);
        assert_eq!(zoom_base(1), 1);
        assert_eq!(zoom_base(2), 5);
        assert_eq!(zoom_base(3), 21);
        // Sum of 4^k for k < z.
        for z in 0..16u8 {
            let want: u64 = (0..z).map(|k| 1u64 << (2 * k as u32)).sum();
            assert_eq!(zoom_base(z), want, "zoom_base({z})");
        }
    }

    #[test]
    fn tile_ids_round_trip_exhaustively_at_low_zoom() {
        // Every tile up to z6 (5461 of them), both directions.
        for z in 0..=6u8 {
            let n = 1u64 << z;
            for x in 0..n {
                for y in 0..n {
                    let id = tile_id(z, x, y);
                    assert_eq!(tile_zxy(id), (z, x, y), "z{z}/{x}/{y} -> {id}");
                }
            }
        }
    }

    #[test]
    fn tile_ids_are_contiguous_within_a_zoom() {
        // A zoom's ids must exactly fill [base(z), base(z+1)) with no gaps or
        // repeats, which is what makes run-length encoding work.
        for z in 0..=6u8 {
            let n = 1u64 << z;
            let mut ids: Vec<u64> = (0..n).flat_map(|x| (0..n).map(move |y| (x, y)))
                .map(|(x, y)| tile_id(z, x, y))
                .collect();
            ids.sort_unstable();
            let want: Vec<u64> = (zoom_base(z)..zoom_base(z + 1)).collect();
            assert_eq!(ids, want, "zoom {z} ids");
        }
    }

    #[test]
    fn the_spec_anchor_points_hold() {
        // z0 is id 0, and z1 walks its quadrants in Hilbert order.
        assert_eq!(tile_id(0, 0, 0), 0);
        assert_eq!(tile_zxy(0), (0, 0, 0));
        assert_eq!(tile_id(1, 0, 0), 1);
        assert_eq!(tile_id(1, 0, 1), 2);
        assert_eq!(tile_id(1, 1, 1), 3);
        assert_eq!(tile_id(1, 1, 0), 4);
    }

    #[test]
    fn the_real_fixture_tile_id_round_trips() {
        // The id the published archive actually stored for our fixture tile. An
        // earlier hand-computed z/x/y for this was wrong, so the assertion is on
        // the round-trip and the zoom rather than on remembered coordinates.
        let id = 2_229_854u64;
        let (z, x, y) = tile_zxy(id);
        assert_eq!(z, 11, "the fixture came from a zoom-11 entry");
        assert_eq!(tile_id(z, x, y), id, "z{z}/{x}/{y} must map back to {id}");
        assert!(x < (1 << 11) && y < (1 << 11), "coords inside the z11 grid");
    }

    #[test]
    fn a_directory_round_trips() {
        let entries = vec![
            Entry { tile_id: 0, offset: 0, length: 100, run_length: 1 },
            // Contiguous: exercises the offset-0 shorthand.
            Entry { tile_id: 1, offset: 100, length: 50, run_length: 3 },
            // A jump, so a real offset must be written.
            Entry { tile_id: 99, offset: 9000, length: 7, run_length: 1 },
            // A leaf pointer.
            Entry { tile_id: 500, offset: 12, length: 34, run_length: 0 },
        ];
        let body = serialize_directory(&entries);
        assert_eq!(parse_directory(&body).unwrap(), entries);
    }

    #[test]
    fn an_empty_directory_round_trips() {
        assert_eq!(parse_directory(&serialize_directory(&[])).unwrap(), vec![]);
    }

    #[test]
    fn a_header_round_trips() {
        let h = Header {
            root_offset: 127,
            root_length: 1352,
            metadata_offset: 1479,
            metadata_length: 67106,
            leaf_offset: 68585,
            leaf_length: 2340693,
            tile_data_offset: 2409278,
            tile_data_length: 1614828579,
            addressed_tiles: 1571621,
            tile_entries: 1324064,
            tile_contents: 1026180,
            clustered: true,
            internal_compression: COMPRESSION_GZIP,
            tile_compression: COMPRESSION_GZIP,
            tile_type: TILE_TYPE_MVT,
            min_zoom: 0,
            max_zoom: 16,
            min_lon_e7: -1_800_000_000,
            min_lat_e7: -850_511_290,
            max_lon_e7: 1_800_000_000,
            max_lat_e7: 850_511_290,
            center_zoom: 16,
            center_lon_e7: -1_224_069_210,
            center_lat_e7: 377_945_930,
        };
        let bytes = h.serialize();
        assert_eq!(bytes.len(), HEADER_LEN);
        let back = Header::parse(&bytes).unwrap();
        assert_eq!(back.root_offset, h.root_offset);
        assert_eq!(back.tile_data_length, h.tile_data_length);
        assert_eq!(back.center_lat_e7, h.center_lat_e7);
        assert_eq!(back.max_zoom, h.max_zoom);
        assert!(back.clustered);
    }

    // --- Against the real published archive --------------------------------

    #[test]
    fn the_real_header_parses_to_its_known_values() {
        let h = Header::parse(REAL_HEAD).expect("the published header");
        assert_eq!(h.root_offset, 127);
        assert_eq!(h.root_length, 1352);
        assert_eq!(h.metadata_offset, 1479);
        assert_eq!(h.leaf_offset, 68585);
        assert_eq!(h.tile_data_offset, 2409278);
        assert_eq!(h.addressed_tiles, 1571621);
        assert_eq!(h.tile_entries, 1324064);
        assert_eq!(h.tile_contents, 1026180);
        assert!(h.clustered);
        assert_eq!(h.internal_compression, COMPRESSION_GZIP);
        assert_eq!(h.tile_compression, COMPRESSION_GZIP);
        assert_eq!(h.tile_type, TILE_TYPE_MVT);
        assert_eq!((h.min_zoom, h.max_zoom), (0, 16));
        // Whole Mercator world, centred on San Francisco.
        assert_eq!(h.min_lon_e7, -1_800_000_000);
        assert_eq!(h.max_lat_e7, 850_511_290);
        assert_eq!(h.center_lon_e7, -1_224_069_210);
        assert_eq!(h.center_lat_e7, 377_945_930);
    }

    #[test]
    fn the_real_root_directory_parses_and_re_serializes() {
        let h = Header::parse(REAL_HEAD).unwrap();
        let raw = &REAL_HEAD[h.root_offset as usize..(h.root_offset + h.root_length) as usize];
        let body = gz::decompress(raw).expect("gzipped root directory");
        assert_eq!(body.len(), 2004, "known inflated size of the real root directory");

        let entries = parse_directory(&body).expect("the real root directory");
        assert_eq!(entries.len(), 324, "known entry count");
        // Every root entry in this archive is a leaf pointer.
        assert!(entries.iter().all(|e| e.run_length == 0), "all leaf pointers");
        assert_eq!(entries[0].tile_id, 0);
        assert_eq!(entries[0].offset, 0);
        assert_eq!(entries[0].length, 9222);
        assert_eq!(entries[1].tile_id, 2_230_397);

        // Byte-exact: our serializer reproduces tippecanoe's directory encoding,
        // including its use of the contiguous-offset shorthand.
        assert_eq!(serialize_directory(&entries), body, "directory re-serializes byte-exactly");
    }

    // --- Whole-archive round trip -------------------------------------------

    #[test]
    fn an_archive_round_trips_through_build_and_parse() {
        let mut b = Builder::new();
        b.max_zoom = 2;
        b.metadata = br#"{"name":"test"}"#.to_vec();
        b.add_tile(0, 0, 0, b"tile-zero");
        b.add_tile(1, 0, 0, b"tile-one");
        b.add_tile(2, 3, 1, REAL_TILE_MVT);
        let bytes = b.build().unwrap();

        let a = Archive::parse(&bytes).expect("our own archive parses");
        assert_eq!(a.metadata, br#"{"name":"test"}"#);
        assert_eq!(a.header.max_zoom, 2);
        assert_eq!(a.tile(0, 0, 0).unwrap().unwrap(), b"tile-zero");
        assert_eq!(a.tile(1, 0, 0).unwrap().unwrap(), b"tile-one");
        assert_eq!(a.tile(2, 3, 1).unwrap().unwrap(), REAL_TILE_MVT);
        // Absent tiles are None, not an error.
        assert!(a.tile(1, 1, 1).unwrap().is_none());
        assert!(a.tile(9, 0, 0).unwrap().is_none());
    }

    #[test]
    fn identical_tiles_are_stored_once_and_run_length_encoded() {
        let mut b = Builder::new();
        b.max_zoom = 4;
        // Four consecutive ids with identical bodies, the ocean-tile case.
        let base = tile_id(4, 0, 0);
        for k in 0..4 {
            b.add_tile_raw(base + k, gz::compress(b"same"));
        }
        let bytes = b.build().unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert_eq!(a.header.addressed_tiles, 4, "four addressable tiles");
        assert_eq!(a.header.tile_entries, 1, "collapsed into one run");
        assert_eq!(a.header.tile_contents, 1, "one distinct body");
        // All four still resolve.
        for k in 0..4 {
            let (z, x, y) = tile_zxy(base + k);
            assert_eq!(a.tile(z, x, y).unwrap().unwrap(), b"same", "tile {k}");
        }
    }

    #[test]
    fn a_large_archive_spills_into_leaf_directories() {
        // Enough distinct tiles that the root cannot hold them all, forcing the
        // two-level layout the real archive uses.
        let mut b = Builder::new();
        b.max_zoom = 8;
        let n = 20_000u64;
        let base = tile_id(8, 0, 0);
        for k in 0..n {
            b.add_tile_raw(base + k, gz::compress(format!("tile {k}").as_bytes()));
        }
        let bytes = b.build().unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert!(a.header.leaf_length > 0, "must have spilled into leaves");
        assert!(
            a.header.root_length as usize <= MAX_ROOT_BYTES,
            "root must stay within one request"
        );
        // Spot-check across leaf boundaries.
        for k in [0u64, 1, 4095, 4096, 4097, 9999, n - 1] {
            let (z, x, y) = tile_zxy(base + k);
            assert_eq!(
                a.tile(z, x, y).unwrap().unwrap(),
                format!("tile {k}").as_bytes(),
                "tile {k}"
            );
        }
        assert_eq!(a.iter_tiles().unwrap().len(), n as usize, "every tile enumerated");
    }

    #[test]
    fn a_truncated_archive_errors_rather_than_panicking() {
        let mut b = Builder::new();
        b.add_tile(0, 0, 0, b"x");
        let bytes = b.build().unwrap();
        assert!(Archive::parse(&bytes[..HEADER_LEN - 1]).is_err(), "short header");
        assert!(Archive::parse(&bytes[..bytes.len() / 2]).is_err(), "clipped body");
        let mut bad = bytes.clone();
        bad[0] = b'X';
        assert!(Archive::parse(&bad).is_err(), "bad magic");
        let mut bad = bytes.clone();
        bad[7] = 2;
        assert!(Archive::parse(&bad).is_err(), "wrong spec version");
    }
}
