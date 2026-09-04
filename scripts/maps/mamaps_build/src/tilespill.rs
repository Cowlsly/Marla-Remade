//! On-disk scratch for one zoom's tile chunks: the missing half of an external sort.
//!
//! [`crate::tiler`]'s map phase produces one `BTreeMap<(tile, layer), BodyLayer>` per chunk and its
//! reduce k-way merges them. Those per-chunk maps already *are* the sorted runs of an external sort
//! -- produced free, as a side effect of clipping -- and `Merged` already *is* its merge phase. The
//! only piece missing was the disk in between, and holding it in memory instead is what put a
//! north-america z14 at 53 GB resident and would put a planet z14 near 244 GB.
//!
//! This module is that disk. A worker hands a finished chunk to [`ChunkSpill::write_chunk`] and gets
//! back a [`ChunkRef`]; the merge opens a [`ChunkReader`] per ref and walks them exactly as it
//! walked the maps. Peak becomes `O(threads) + O(READ_BUDGET)` rather than `O(extract)`.
//!
//! Modelled on `tile_build::spill`, and the conventions are that module's: manual little-endian
//! encode and decode, a zero-filled reserved tail so a field can be added without moving the ones
//! already there, a length that is a pure function of the header so a reader validates before it
//! allocates, counts cross-checked against the file's own length, and `Drop` cleanup so a run that
//! dies mid-planet cannot strand a hundred gigabytes.
//!
//! # The format
//!
//! One chunk is a plain sequential stream of entries in `BTreeMap::into_iter` order -- ascending
//! `(tile_id, layer_id)` -- with **no index**. The merge is strictly forward-only per stream, so an
//! index would be bytes nothing reads.
//!
//! ```text
//! 0..8    u64 tile_id
//! 8..12   u32 features
//! 12..16  u32 parts
//! 16..20  u32 coords
//! 20..21  u8  layer_id
//! 21..24  reserved, zero
//! 24..28  u32 names (label strings in this entry's table)
//! 28..32  reserved, zero
//! 32..    packed features (14 B), then parts (10 B), then coords (4 B),
//!          then names (u32 length + UTF-8 bytes each)
//! ```
//!
//! Packed field by field rather than cast wholesale from the in-memory struct. `body::Feature` is
//! `kind`, `kind_detail`, `geom_type`, `flags`, `parts_offset`, `part_count` -- fourteen bytes of
//! payload in a sixteen-byte struct with no `#[repr(C)]`, so a bulk cast would be both unsound and
//! dependent on the host's layout. Fourteen bytes is also less to write.
//!
//! Names ride per entry (not per chunk or per zoom) so the merge never holds more than one
//! entry's strings at a time: a tile's labels are dozens of strings, a zoom's are millions.
//!
//! # Reading with a budget, not a constant
//!
//! The merge holds one cursor per chunk: about 5,700 at a north-america z14 and about 27,000 at a
//! planet one. A fixed per-reader buffer is the wrong shape at both ends -- generous at 5,700, ruin
//! at 27,000. [`read_window_bytes`] divides a total [`READ_BUDGET`] by the stream count instead, so
//! the sum is a flat ceiling until the [`MIN_WINDOW`] floor takes over; the floor exists because a
//! read smaller than it costs more in syscalls than it saves in memory, and at the stream counts a
//! real build reaches the budget is still what binds.
//!
//! One pass, not a cascade. A cascading merge -- merge runs in groups, then merge the results -- is
//! available and order-preserving, and it is the escape hatch if the merge thread's decode turns out
//! to be the cost. It was not taken because it doubles I/O and doubles peak disk to buy memory that
//! [`read_window_bytes`] already bounds.
//!
//! # Random reads
//!
//! Thousands of interleaved forward cursors is a fine access pattern on NVMe and a poor one on
//! spinning media. [`READ_BUDGET`] is the dial: a larger window makes each stream's reads longer and
//! rarer at the cost of resident bytes.

use std::collections::BTreeMap;
use std::fs::File;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use tilecodec::mamaps::body::{Feature as BodyFeature, Layer as BodyLayer, Part};
use tilecodec::proto::{err, Error, Result};

use crate::tiler::ChunkEntry;

/// Bytes of fixed header before one entry's arenas.
pub const ENTRY_HEADER_BYTES: usize = 32;

/// Packed width of one [`BodyFeature`] in the spill: kind, kind_detail, geom_type, flags,
/// name_idx, parts_offset, part_count, transit_color. Twenty, wider than the body's 24-byte
/// record only in what it omits (padding); the fields are the codec's own, so the scratch
/// format never lags the codec by a version.
const FEATURE_BYTES: usize = 20;
/// Packed width of one [`Part`] in the spill: coord_start, point_count, winding. Ten — the
/// body's 12-byte entry carries a reserved half-word the scratch format does not need.
const PART_BYTES: usize = 10;
/// Packed width of one `(i16, i16)` coordinate.
const COORD_BYTES: usize = 4;

/// An entry longer than this is corruption, not a large tile layer. A tile-layer is capped at 65,535
/// features by the body format, so the largest plausible entry is orders of magnitude below this.
const MAX_ENTRY_BYTES: u64 = 1 << 30;

/// Encoded bytes a worker buffers before it writes. Large enough that a chunk of a few thousand
/// small entries is one or two writes, small enough that the buffer is noise next to the chunk map
/// it is draining.
const FLUSH_BYTES: usize = 1 << 20;

/// Bytes the whole merge may hold in read windows, divided across its streams.
pub const READ_BUDGET: usize = 256 << 20;
/// Smallest read window. Below this the syscall costs more than the memory saves.
pub const MIN_WINDOW: usize = 16 << 10;
/// Largest read window. A stream is forward-only, so beyond this a longer window is only read-ahead
/// the page cache would have done anyway.
pub const MAX_WINDOW: usize = 1 << 20;

/// How many bytes each of `streams` concurrent readers may buffer.
///
/// **Not observable in the output.** A reader yields the same entries in the same order whatever its
/// window is, which is what `the_archive_is_identical_however_the_read_window_is_sized` holds this
/// to; the window is a memory/syscall trade and nothing else.
pub fn read_window_bytes(streams: usize) -> usize {
    let forced = WINDOW_OVERRIDE.load(Ordering::Relaxed);
    if forced > 0 {
        return forced;
    }
    window_for(streams)
}

fn window_for(streams: usize) -> usize {
    (READ_BUDGET / streams.max(1)).clamp(MIN_WINDOW, MAX_WINDOW)
}

/// A window forced by a test, or zero for the budget. Zero rather than an `Option`, because this is
/// read on the merge's own path and a build never sets it.
static WINDOW_OVERRIDE: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

/// Pin the read window, or release it with zero. Tests only: the window has to be forcible for the
/// claim that it is not observable to be worth anything.
#[cfg(test)]
pub fn set_read_window(bytes: usize) {
    WINDOW_OVERRIDE.store(bytes, Ordering::Relaxed);
}

/// Where one chunk's bytes live in a [`ChunkSpill`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ChunkRef {
    pub at: u64,
    pub len: u64,
    pub entries: u64,
}

/// One zoom's chunks, in one file.
///
/// `&self` throughout, so every worker writes through it and every reader reads through it -- the
/// same discipline as `tile_build::spill::NormalizedChunks`, and for the same reason: one handle,
/// many threads, positional I/O and no shared cursor.
pub struct ChunkSpill {
    /// `None` only while dropping, where the handle has to close before the file can be unlinked or
    /// Windows refuses the delete.
    file: Option<File>,
    path: PathBuf,
    /// The next free byte, and so also the total reserved. Held for one integer add per chunk and
    /// never across I/O: a worker must not queue behind another worker's write with a finished chunk
    /// in hand.
    at: Mutex<u64>,
    written: AtomicU64,
    written_entries: AtomicU64,
    read: AtomicU64,
    read_entries: AtomicU64,
}

impl ChunkSpill {
    /// Create the scratch file, truncating whatever a killed run left at the path.
    ///
    /// Read *and* write on one handle, because that is the whole shape of this module: the workers
    /// write positionally through it and the merge reads positionally through it, and a second
    /// handle would be a second file description with nothing to gain.
    pub fn create(path: impl Into<PathBuf>) -> Result<ChunkSpill> {
        let path = path.into();
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(&path)
            .map_err(|e| Error(format!("cannot create {}: {e}", path.display())))?;
        Ok(ChunkSpill {
            file: Some(file),
            path,
            at: Mutex::new(0),
            written: AtomicU64::new(0),
            written_entries: AtomicU64::new(0),
            read: AtomicU64::new(0),
            read_entries: AtomicU64::new(0),
        })
    }

    fn file(&self) -> &File {
        self.file.as_ref().expect("the tile chunk spill outlives its readers and writers")
    }

    /// Write one finished chunk and say where it went.
    ///
    /// Takes the map **by value** and encodes out of `into_iter`, flushing every [`FLUSH_BYTES`], so
    /// the map shrinks as it is written instead of being copied whole into a buffer beside itself.
    ///
    /// The byte range is reserved under the cursor lock and written outside it. That ordering is the
    /// point: the lock covers an integer add, so a worker that has just finished a chunk is never
    /// waiting on another worker's disk.
    pub fn write_chunk(&self, map: BTreeMap<(u64, u8), ChunkEntry>) -> Result<ChunkRef> {
        let entries = map.len() as u64;
        let mut len = 0u64;
        for ((_, layer_id), entry) in &map {
            if *layer_id != entry.layer.layer_id {
                // The merge keys its heap on the map key and reads `layer_id` off the layer it
                // yields, so the two disagreeing would mean the round trip had to preserve both. It
                // is one field on disk because in this generator they are one field.
                return err(format!(
                    "a tile chunk entry is keyed on layer {layer_id} but carries layer {}",
                    entry.layer.layer_id
                ));
            }
            len += entry_bytes(entry)?;
        }

        let at = {
            let mut next = self.at.lock().expect("the tile chunk spill's cursor");
            let at = *next;
            *next += len;
            at
        };

        let mut buf: Vec<u8> = Vec::new();
        let mut cursor = at;
        for ((tile, layer_id), layer) in map {
            encode_entry(tile, layer_id, &layer, &mut buf);
            if buf.len() >= FLUSH_BYTES {
                cursor += self.flush(&mut buf, cursor)?;
            }
        }
        cursor += self.flush(&mut buf, cursor)?;

        if cursor != at + len {
            return err(format!(
                "a tile chunk reserved {len} byte(s) and wrote {}",
                cursor - at
            ));
        }
        self.written.fetch_add(len, Ordering::Relaxed);
        self.written_entries.fetch_add(entries, Ordering::Relaxed);
        Ok(ChunkRef { at, len, entries })
    }

    /// Write `buf` at `offset` and clear it. Returns what it wrote.
    fn flush(&self, buf: &mut Vec<u8>, offset: u64) -> Result<u64> {
        if buf.is_empty() {
            return Ok(0);
        }
        write_all_at(self.file(), buf, offset)
            .map_err(|e| Error(format!("writing {}: {e}", self.path.display())))?;
        let n = buf.len() as u64;
        buf.clear();
        Ok(n)
    }

    /// A forward cursor over one chunk, buffering `window` bytes at a time.
    pub fn reader(&self, chunk: &ChunkRef, window: usize) -> ChunkReader<'_> {
        ChunkReader {
            spill: self,
            at: chunk.at,
            end: chunk.at + chunk.len,
            left: chunk.entries,
            buf: Vec::new(),
            used: 0,
            window: window.max(ENTRY_HEADER_BYTES),
        }
    }

    /// Reserved, written, on disk and read back must all agree.
    ///
    /// `tile_build::spill::BucketSet::seal`'s triple entry, for the same reason: a chunk that
    /// quietly lost entries would produce an archive with holes in it and nothing downstream could
    /// tell. Called once the merge has drained every reader.
    pub fn check_books(&self) -> Result<()> {
        let reserved = *self.at.lock().expect("the tile chunk spill's cursor");
        let written = self.written.load(Ordering::Relaxed);
        if reserved != written {
            return err(format!(
                "{} reserved {reserved} byte(s) and wrote {written}",
                self.path.display()
            ));
        }
        let on_disk = std::fs::metadata(&self.path)
            .map_err(|e| Error(format!("cannot stat {}: {e}", self.path.display())))?
            .len();
        if on_disk != written {
            return err(format!(
                "{} is {on_disk} byte(s) on disk but {written} were written to it",
                self.path.display()
            ));
        }
        let read = self.read.load(Ordering::Relaxed);
        if read != written {
            return err(format!(
                "{} wrote {written} byte(s) and read back {read}",
                self.path.display()
            ));
        }
        let pushed = self.written_entries.load(Ordering::Relaxed);
        let pulled = self.read_entries.load(Ordering::Relaxed);
        if pushed != pulled {
            return err(format!(
                "{} wrote {pushed} entr(ies) and read back {pulled}",
                self.path.display()
            ));
        }
        Ok(())
    }
}

impl Drop for ChunkSpill {
    fn drop(&mut self) {
        // The handle first, or Windows refuses the delete.
        self.file = None;
        let _ = std::fs::remove_file(&self.path);
    }
}

/// A forward-only cursor over one chunk's byte range.
///
/// Reads through `&ChunkSpill`, so every cursor in a merge shares one file handle.
pub struct ChunkReader<'a> {
    spill: &'a ChunkSpill,
    /// Disk offset of the first byte not yet in `buf`.
    at: u64,
    /// One past the chunk's last byte.
    end: u64,
    /// Entries the header said were written and this cursor has not yielded.
    left: u64,
    buf: Vec<u8>,
    /// How much of `buf` has been yielded.
    used: usize,
    window: usize,
}

impl ChunkReader<'_> {
    /// The next entry, or `None` at the end of the chunk.
    ///
    /// Fallible, and that matters: a truncated scratch file must fail the build rather than silently
    /// shorten the archive.
    #[allow(clippy::should_implement_trait)]
    pub fn next(&mut self) -> Result<Option<((u64, u8), ChunkEntry)>> {
        if self.left == 0 {
            if self.used < self.buf.len() || self.at < self.end {
                return err(format!(
                    "a tile chunk in {} holds bytes past its last entry",
                    self.spill.path.display()
                ));
            }
            return Ok(None);
        }
        self.fill(ENTRY_HEADER_BYTES)?;
        let head: &[u8; ENTRY_HEADER_BYTES] = self.buf[self.used..self.used + ENTRY_HEADER_BYTES]
            .try_into()
            .expect("a header's worth of bytes");
        let header = entry_header(head)?;
        // The fixed arenas first: their length is a pure function of the header, so one fill
        // covers them. Names follow with inline lengths and are pulled one at a time — their
        // byte length is known only once the previous one is read.
        let fixed = payload_bytes(header.features, header.parts, header.coords) as usize;
        self.fill(ENTRY_HEADER_BYTES + fixed)?;
        let body = self.used + ENTRY_HEADER_BYTES;
        let mut entry = decode_fixed(&header, &self.buf[body..body + fixed])?;
        self.used = body + fixed;
        // Counted, not differenced: `fill` compacts the buffer when it tops up, so a span across
        // two buffer positions is not the difference of two offsets.
        let mut consumed = ENTRY_HEADER_BYTES + fixed;
        for _ in 0..header.names {
            self.fill(4)?;
            let len = u32::from_le_bytes(
                self.buf[self.used..self.used + 4].try_into().expect("4 bytes"),
            ) as usize;
            if len > 1 << 20 {
                return err("a tile chunk entry names a megabyte-plus string".to_string());
            }
            self.fill(4 + len)?;
            let bytes = &self.buf[self.used + 4..self.used + 4 + len];
            entry.names.push(
                std::str::from_utf8(bytes)
                    .map_err(|_| Error("a tile chunk entry's name is not UTF-8".to_string()))?
                    .to_string(),
            );
            self.used += 4 + len;
            consumed += 4 + len;
        }
        // Every name index must land in the table just read: a dangling index would decode to
        // the wrong label on device with no other symptom.
        for feature in &entry.layer.features {
            if feature.name_idx as usize > entry.names.len() {
                return err("a tile chunk feature names past its entry's table".to_string());
            }
        }
        self.left -= 1;
        self.spill.read.fetch_add(consumed as u64, Ordering::Relaxed);
        self.spill.read_entries.fetch_add(1, Ordering::Relaxed);
        Ok(Some(((header.tile, header.layer_id), entry)))
    }

    /// Make at least `need` unread bytes available in `buf`.
    fn fill(&mut self, need: usize) -> Result<()> {
        if self.buf.len() - self.used >= need {
            return Ok(());
        }
        if self.used > 0 {
            self.buf.copy_within(self.used.., 0);
            let keep = self.buf.len() - self.used;
            self.buf.truncate(keep);
            self.used = 0;
        }
        // An entry larger than the window is read whole rather than in pieces: the decode wants it
        // contiguous, and one such entry is bounded by `MAX_ENTRY_BYTES`.
        let want = need.max(self.window) - self.buf.len();
        let take = want.min((self.end - self.at) as usize);
        if self.buf.len() + take < need {
            return err(format!(
                "a tile chunk in {} ends {} byte(s) into a {need}-byte read",
                self.spill.path.display(),
                self.buf.len() + take
            ));
        }
        let base = self.buf.len();
        self.buf.resize(base + take, 0);
        tilecodec::pmtiles::read_exact_at(self.spill.file(), &mut self.buf[base..], self.at)
            .map_err(|e| Error(format!("reading {}: {e}", self.spill.path.display())))?;
        self.at += take as u64;
        Ok(())
    }
}

/// How many bytes one entry encodes to, or an error when an arena is past what a `u32` addresses.
fn entry_bytes(entry: &ChunkEntry) -> Result<u64> {
    let layer = &entry.layer;
    let count = |what: &str, n: usize| -> Result<usize> {
        if n > u32::MAX as usize {
            return err(format!("a tile chunk entry holds {n} {what}, which no header can address"));
        }
        Ok(n)
    };
    let features = count("feature(s)", layer.features.len())?;
    let parts = count("part(s)", layer.parts.len())?;
    let coords = count("coordinate(s)", layer.coords.len())?;
    let mut names_bytes = 0u64;
    for name in &entry.names {
        names_bytes += 4 + name.len() as u64;
    }
    Ok(ENTRY_HEADER_BYTES as u64 + payload_bytes(features, parts, coords) + names_bytes)
}

/// The payload width implied by an entry's three counts. A pure function of the header, which is
/// what lets a reader validate before it allocates. Names ride after the coords and are counted
/// in the header's own `names` field.
fn payload_bytes(features: usize, parts: usize, coords: usize) -> u64 {
    features as u64 * FEATURE_BYTES as u64
        + parts as u64 * PART_BYTES as u64
        + coords as u64 * COORD_BYTES as u64
}

fn encode_entry(tile: u64, layer_id: u8, entry: &ChunkEntry, out: &mut Vec<u8>) {
    let layer = &entry.layer;
    let base = out.len();
    out.resize(base + ENTRY_HEADER_BYTES, 0);
    out[base..base + 8].copy_from_slice(&tile.to_le_bytes());
    out[base + 8..base + 12].copy_from_slice(&(layer.features.len() as u32).to_le_bytes());
    out[base + 12..base + 16].copy_from_slice(&(layer.parts.len() as u32).to_le_bytes());
    out[base + 16..base + 20].copy_from_slice(&(layer.coords.len() as u32).to_le_bytes());
    out[base + 20] = layer_id;
    out[base + 24..base + 28].copy_from_slice(&(entry.names.len() as u32).to_le_bytes());
    for feature in &layer.features {
        // The spill carries the v2 index, not the v1 wire: name_idx + transit_color ride the
        // entry and are re-encoded by the body serializer, so the scratch format never lags the
        // codec by a version.
        out.extend_from_slice(&feature.kind.to_le_bytes());
        out.extend_from_slice(&feature.kind_detail.to_le_bytes());
        out.push(feature.geom_type);
        out.push(feature.flags);
        out.extend_from_slice(&feature.name_idx.to_le_bytes());
        out.extend_from_slice(&feature.parts_offset.to_le_bytes());
        out.extend_from_slice(&feature.part_count.to_le_bytes());
        out.extend_from_slice(&feature.transit_color.to_le_bytes());
    }
    for part in &layer.parts {
        out.extend_from_slice(&part.coord_start.to_le_bytes());
        out.extend_from_slice(&part.point_count.to_le_bytes());
        out.extend_from_slice(&part.winding.to_le_bytes());
    }
    for (x, y) in &layer.coords {
        out.extend_from_slice(&x.to_le_bytes());
        out.extend_from_slice(&y.to_le_bytes());
    }
    for name in &entry.names {
        out.extend_from_slice(&(name.len() as u32).to_le_bytes());
        out.extend_from_slice(name.as_bytes());
    }
}

struct EntryHeader {
    tile: u64,
    layer_id: u8,
    features: usize,
    parts: usize,
    coords: usize,
    names: usize,
}

fn entry_header(head: &[u8; ENTRY_HEADER_BYTES]) -> Result<EntryHeader> {
    // Both reserved tails must be zero: a newer writer would use them, so a nonzero tail means
    // the reader is the wrong version for the file. Guessing would decode a field that moved.
    if head[21..24].iter().any(|v| *v != 0) || head[28..ENTRY_HEADER_BYTES].iter().any(|v| *v != 0)
    {
        return err("a tile chunk entry has a nonzero reserved tail");
    }
    let u32_at =
        |o: usize| u32::from_le_bytes(head[o..o + 4].try_into().expect("4 bytes")) as usize;
    let header = EntryHeader {
        tile: u64::from_le_bytes(head[0..8].try_into().expect("8 bytes")),
        features: u32_at(8),
        parts: u32_at(12),
        coords: u32_at(16),
        layer_id: head[20],
        names: u32_at(24),
    };
    let len = ENTRY_HEADER_BYTES as u64
        + payload_bytes(header.features, header.parts, header.coords);
    if len > MAX_ENTRY_BYTES {
        return err(format!("a tile chunk entry is {len} byte(s), which is corruption"));
    }
    Ok(header)
}

/// Decode an entry's fixed arenas. `payload` is exactly the fixed bytes the header accounted
/// for; names are pulled separately by the reader, one inline length at a time.
fn decode_fixed(header: &EntryHeader, payload: &[u8]) -> Result<ChunkEntry> {
    let u16_at = |b: &[u8], o: usize| u16::from_le_bytes(b[o..o + 2].try_into().expect("2 bytes"));
    let u32_at = |b: &[u8], o: usize| u32::from_le_bytes(b[o..o + 4].try_into().expect("4 bytes"));
    let i16_at = |b: &[u8], o: usize| i16::from_le_bytes(b[o..o + 2].try_into().expect("2 bytes"));

    let mut at = 0usize;
    let mut features = Vec::with_capacity(header.features);
    for _ in 0..header.features {
        let b = payload
            .get(at..at + FEATURE_BYTES)
            .ok_or_else(|| Error("a tile chunk entry's features run past its payload".to_string()))?;
        features.push(BodyFeature {
            kind: u16_at(b, 0),
            kind_detail: u16_at(b, 2),
            geom_type: b[4],
            flags: b[5],
            name_idx: u16_at(b, 6),
            parts_offset: u32_at(b, 8),
            part_count: u32_at(b, 12),
            transit_color: u32_at(b, 16),
        });
        at += FEATURE_BYTES;
    }
    let mut parts = Vec::with_capacity(header.parts);
    for _ in 0..header.parts {
        let b = payload
            .get(at..at + PART_BYTES)
            .ok_or_else(|| Error("a tile chunk entry's parts run past its payload".to_string()))?;
        parts.push(Part {
            coord_start: u32_at(b, 0),
            point_count: u32_at(b, 4),
            winding: u16_at(b, 8),
        });
        at += PART_BYTES;
    }
    let mut coords = Vec::with_capacity(header.coords);
    for _ in 0..header.coords {
        let b = payload
            .get(at..at + COORD_BYTES)
            .ok_or_else(|| Error("a tile chunk entry's coords run past its payload".to_string()))?;
        coords.push((i16_at(b, 0), i16_at(b, 2)));
        at += COORD_BYTES;
    }
    debug_assert_eq!(at, payload.len(), "the fixed arenas must consume their payload exactly");
    let names = Vec::with_capacity(header.names);
    Ok(ChunkEntry {
        layer: BodyLayer { layer_id: header.layer_id, features, parts, coords },
        names,
    })
}

/// Write all of `buf` at `offset` without moving the file's cursor.
///
/// The positional twin of [`tilecodec::pmtiles::read_exact_at`], and the one genuinely new I/O
/// primitive this change needs: nothing else in the tree writes positionally. Same shape and the
/// same reason -- the cursor is the only thing that would need `&mut File`, and it is shared between
/// clones of a handle, so seek-then-write cannot be done concurrently on one file while this can.
/// Neither platform guarantees a full write, hence the loop.
fn write_all_at(file: &File, mut buf: &[u8], mut offset: u64) -> std::io::Result<()> {
    while !buf.is_empty() {
        #[cfg(windows)]
        let n = std::os::windows::fs::FileExt::seek_write(file, buf, offset)?;
        #[cfg(unix)]
        let n = std::os::unix::fs::FileExt::write_at(file, buf, offset)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::WriteZero,
                "the file took none of the bytes offered",
            ));
        }
        buf = &buf[n..];
        offset += n as u64;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::body::{GEOM_LINE, GEOM_POLYGON, WINDING_HOLE, WINDING_OUTER};

    fn tmp(name: &str) -> PathBuf {
        use std::sync::atomic::AtomicU64;
        static NEXT: AtomicU64 = AtomicU64::new(0);
        std::env::temp_dir().join(format!(
            "mamaps_tilespill_{}_{}_{name}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed),
        ))
    }

    fn feature(kind: u16, detail: u16, geom: u8, flags: u8, at: u32, n: u32) -> BodyFeature {
        BodyFeature {
            kind,
            kind_detail: detail,
            geom_type: geom,
            flags,
            name_idx: tilecodec::mamaps::body::NAME_NONE,
            parts_offset: at,
            part_count: n,
            transit_color: 0,
        }
    }

    fn named(kind: u16, name_idx: u16) -> BodyFeature {
        BodyFeature {
            kind,
            kind_detail: 0,
            geom_type: tilecodec::mamaps::body::GEOM_POINT,
            flags: 0,
            name_idx,
            parts_offset: 0,
            part_count: 1,
            transit_color: 0,
        }
    }

    fn entry(layer_id: u8, features: Vec<BodyFeature>, names: &[&str]) -> ChunkEntry {
        let mut layer = BodyLayer::new(layer_id);
        layer.features = features;
        for (i, _) in layer.features.iter().enumerate() {
            layer.parts.push(Part {
                coord_start: i as u32,
                point_count: 1,
                winding: WINDING_OUTER,
            });
            layer.coords.push((i as i16, 0));
        }
        // Fix up parts_offset to match the pushed parts (all features above use offset 0).
        for (i, feature) in layer.features.iter_mut().enumerate() {
            feature.parts_offset = i as u32;
        }
        ChunkEntry { layer, names: names.iter().map(|s| s.to_string()).collect() }
    }

    /// Every shape an entry can take, including the empty ones that a naive length check would let
    /// through and a naive decode would trip on — plus a named entry, because names are the one
    /// variable-length arena in the spill.
    fn every_layer() -> Vec<((u64, u8), ChunkEntry)> {
        let plain = |layer_id: u8, features: Vec<BodyFeature>| ChunkEntry {
            layer: BodyLayer { layer_id, features, parts: Vec::new(), coords: Vec::new() },
            names: Vec::new(),
        };
        vec![
            // Empty layer: no features, no parts, no coords.
            ((1, 0), ChunkEntry::new(0)),
            // Features but no parts and no coords, which `push` cannot make and a corrupt file can.
            ((2, 1), plain(1, vec![feature(1, 2, GEOM_LINE, 0, 0, 0)])),
            // A part with no coordinates.
            (
                (3, 2),
                ChunkEntry {
                    layer: BodyLayer {
                        layer_id: 2,
                        features: vec![feature(3, 4, GEOM_LINE, 1, 0, 1)],
                        parts: vec![Part {
                            coord_start: 0,
                            point_count: 0,
                            winding: WINDING_OUTER,
                        }],
                        coords: Vec::new(),
                    },
                    names: Vec::new(),
                },
            ),
            // The extremes of every field: `u16::MAX` kinds, `i16` at both ends, a hole.
            (
                (4, 3),
                ChunkEntry {
                    layer: BodyLayer {
                        layer_id: 3,
                        features: vec![
                            feature(u16::MAX, u16::MAX, GEOM_POLYGON, 0x0f, 0, 2),
                            feature(0, 0, GEOM_POLYGON, 0, 2, 1),
                        ],
                        parts: vec![
                            Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER },
                            Part { coord_start: 2, point_count: 2, winding: WINDING_HOLE },
                            Part { coord_start: 4, point_count: 1, winding: WINDING_OUTER },
                        ],
                        coords: vec![
                            (i16::MIN, i16::MAX),
                            (i16::MAX, i16::MIN),
                            (0, 0),
                            (-1, 1),
                            (32_767, -32_768),
                        ],
                    },
                    names: Vec::new(),
                },
            ),
            // Several layers on one tile, which is what the merge collapses.
            ((5, 1), ChunkEntry::new(1)),
            (
                (5, 7),
                ChunkEntry {
                    layer: BodyLayer {
                        layer_id: 7,
                        features: vec![feature(9, 9, GEOM_LINE, 0, 0, 1)],
                        parts: vec![Part {
                            coord_start: 0,
                            point_count: 3,
                            winding: WINDING_OUTER,
                        }],
                        coords: vec![(1, 2), (3, 4), (5, 6)],
                    },
                    names: Vec::new(),
                },
            ),
            ((5, 10), ChunkEntry::new(10)),
            // A named entry: two point labels sharing one name plus an unnamed one.
            ((6, 8), entry(8, vec![named(3, 1), named(5, 1), named(7, 0)], &["Café"])),
        ]
    }

    fn drain(spill: &ChunkSpill, at: &ChunkRef, window: usize) -> Vec<((u64, u8), ChunkEntry)> {
        let mut reader = spill.reader(at, window);
        let mut out = Vec::new();
        while let Some(entry) = reader.next().expect("read an entry back") {
            out.push(entry);
        }
        out
    }

    #[test]
    fn a_chunk_round_trips_through_the_scratch_file() {
        let spill = ChunkSpill::create(tmp("roundtrip")).expect("create");
        let want = every_layer();
        let map: BTreeMap<(u64, u8), ChunkEntry> = want.iter().cloned().collect();
        let at = spill.write_chunk(map).expect("write");
        assert_eq!(at.entries, want.len() as u64);
        assert_eq!(drain(&spill, &at, 1 << 16), want);
        spill.check_books().expect("the books balance");
    }

    /// The read accounting survives a buffer compaction mid-entry. Names are pulled one inline
    /// length at a time, so a narrow window tops the buffer up (compacting it) halfway through
    /// an entry's names — and the bytes-read counter used to difference two offsets across that
    /// compaction, undercounting by the compacted prefix. California z12 caught it: a megabyte
    /// short on `check_books`. Narrow window + many long names forces the compaction here.
    #[test]
    fn a_narrow_window_compacting_mid_entry_still_balances_the_books() {
        let spill = ChunkSpill::create(tmp("compactnames")).expect("create");
        let names: Vec<String> = (0..40).map(|i| format!("name-{i}-with-padding-to-lengthen")).collect();
        let features: Vec<BodyFeature> = names
            .iter()
            .enumerate()
            .map(|(i, _)| BodyFeature {
                kind: 1,
                kind_detail: 0,
                geom_type: tilecodec::mamaps::body::GEOM_POINT,
                flags: 0,
                name_idx: i as u16 + 1,
                parts_offset: i as u32,
                part_count: 1,
                transit_color: 0,
            })
            .collect();
        let mut layer = BodyLayer::new(8);
        for (i, feature) in features.into_iter().enumerate() {
            layer.features.push(feature);
            layer.parts.push(Part {
                coord_start: i as u32,
                point_count: 1,
                winding: WINDING_OUTER,
            });
            layer.coords.push((i as i16, 0));
        }
        let mut map: BTreeMap<(u64, u8), ChunkEntry> = BTreeMap::new();
        map.insert((7, 8), ChunkEntry { layer, names: names.clone() });
        let at = spill.write_chunk(map).expect("write");
        // Window of one header: every name tops up (and compacts) mid-entry.
        let read = drain(&spill, &at, ENTRY_HEADER_BYTES);
        assert_eq!(read.len(), 1);
        assert_eq!(read[0].1.names, names);
        spill.check_books().expect("the books balance after mid-entry compaction");
    }

    /// The claim the whole merge rests on: the file's order is `map.keys()`, so a reader hands
    /// `Merged` the identical key sequence a `BTreeMap` did.
    #[test]
    fn entry_order_is_the_maps_key_order() {
        let spill = ChunkSpill::create(tmp("order")).expect("create");
        // Inserted in a deliberately scrambled order; a `BTreeMap` sorts them and so must the file.
        let mut map: BTreeMap<(u64, u8), ChunkEntry> = BTreeMap::new();
        for (tile, layer) in [(30u64, 2u8), (10, 5), (10, 1), (20, 9), (10, 3), (30, 0)] {
            map.insert((tile, layer), ChunkEntry::new(layer));
        }
        let keys: Vec<(u64, u8)> = map.keys().copied().collect();
        let at = spill.write_chunk(map).expect("write");
        let read: Vec<(u64, u8)> = drain(&spill, &at, 1 << 16).into_iter().map(|(k, _)| k).collect();
        assert_eq!(read, keys);
        assert_eq!(read, vec![(10, 1), (10, 3), (10, 5), (20, 9), (30, 0), (30, 2)]);
    }

    /// Chunks written concurrently must not overlap, and every one must read back as itself.
    #[test]
    fn concurrent_chunks_get_disjoint_ranges_and_the_books_balance() {
        let spill = ChunkSpill::create(tmp("concurrent")).expect("create");
        let refs: Vec<ChunkRef> = std::thread::scope(|scope| {
            let handles: Vec<_> = (0..8u64)
                .map(|i| {
                    scope.spawn({
                        let spill = &spill;
                        move || {
                            let mut map: BTreeMap<(u64, u8), ChunkEntry> = BTreeMap::new();
                            for tile in 0..64u64 {
                                let mut entry = ChunkEntry::new(1);
                                entry.layer.coords =
                                    vec![(i as i16, tile as i16); 1 + tile as usize];
                                map.insert((tile, 1), entry);
                            }
                            (i, spill.write_chunk(map).expect("write"))
                        }
                    })
                })
                .collect();
            let mut refs: Vec<(u64, ChunkRef)> =
                handles.into_iter().map(|h| h.join().expect("join")).collect();
            refs.sort_by_key(|(i, _)| *i);
            refs.into_iter().map(|(_, at)| at).collect()
        });

        let mut ranges: Vec<(u64, u64)> = refs.iter().map(|r| (r.at, r.at + r.len)).collect();
        ranges.sort();
        for pair in ranges.windows(2) {
            assert!(pair[0].1 <= pair[1].0, "two chunks share bytes: {pair:?}");
        }
        for (i, at) in refs.iter().enumerate() {
            let entries = drain(&spill, at, 1 << 15);
            assert_eq!(entries.len(), 64);
            assert_eq!(entries[3].1.layer.coords[0], (i as i16, 3));
        }
        spill.check_books().expect("the books balance");
    }

    #[test]
    fn a_truncated_chunk_errors_rather_than_decoding() {
        let spill = ChunkSpill::create(tmp("truncated")).expect("create");
        let map: BTreeMap<(u64, u8), ChunkEntry> = every_layer().into_iter().collect();
        let at = spill.write_chunk(map).expect("write");

        // Half a chunk: the reader is told the entry count but the bytes run out first.
        let short = ChunkRef { at: at.at, len: at.len / 2, entries: at.entries };
        let mut reader = spill.reader(&short, 1 << 16);
        let mut failed = false;
        loop {
            match reader.next() {
                Ok(Some(_)) => {}
                Ok(None) => break,
                Err(_) => {
                    failed = true;
                    break;
                }
            }
        }
        assert!(failed, "a truncated chunk decoded instead of erroring");

        // And the other way round: the bytes are there but fewer entries were claimed, so the
        // cursor would leave a tail behind.
        let fewer = ChunkRef { at: at.at, len: at.len, entries: at.entries - 1 };
        let mut reader = spill.reader(&fewer, 1 << 16);
        for _ in 0..fewer.entries {
            reader.next().expect("read").expect("an entry");
        }
        assert!(reader.next().is_err(), "a chunk with bytes past its last entry read as complete");
    }

    #[test]
    fn a_dirty_reserved_tail_errors() {
        let mut head = [0u8; ENTRY_HEADER_BYTES];
        entry_header(&head).expect("a zero header is legal");
        head[23] = 1;
        assert!(entry_header(&head).is_err(), "a nonzero reserved tail decoded");
        let mut head = [0u8; ENTRY_HEADER_BYTES];
        head[31] = 1;
        assert!(entry_header(&head).is_err(), "a nonzero second reserved tail decoded");
    }

    #[test]
    fn an_entry_claiming_more_than_a_gigabyte_errors() {
        let mut head = [0u8; ENTRY_HEADER_BYTES];
        head[16..20].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(entry_header(&head).is_err(), "a gigabyte-plus entry decoded");
    }

    /// Byte-identity, at the level the format can state it: what a reader yields does not depend on
    /// how much it buffers. This is what pins "the window size is not observable".
    #[test]
    fn a_chunk_reads_the_same_however_the_window_is_sized() {
        let spill = ChunkSpill::create(tmp("windows")).expect("create");
        let mut map: BTreeMap<(u64, u8), ChunkEntry> = every_layer().into_iter().collect();
        // One entry far larger than the smallest window, so at least one read has to be the
        // oversize path rather than the buffered one.
        let mut big = ChunkEntry::new(4);
        big.layer.features = vec![feature(1, 1, GEOM_LINE, 0, 0, 1)];
        big.layer.parts =
            vec![Part { coord_start: 0, point_count: 40_000, winding: WINDING_OUTER }];
        big.layer.coords = (0..40_000).map(|i| (i as i16, -(i as i16))).collect();
        map.insert((6, 4), big);
        let at = spill.write_chunk(map).expect("write");

        let want = drain(&spill, &at, 1 << 20);
        for window in [1usize, 24, 25, 64, 1024, 1 << 14, 1 << 16, 1 << 22] {
            assert_eq!(drain(&spill, &at, window), want, "window {window} yielded something else");
        }
    }

    #[test]
    fn the_scratch_file_is_removed_on_drop() {
        let path = tmp("dropped");
        {
            let spill = ChunkSpill::create(&path).expect("create");
            spill.write_chunk(every_layer().into_iter().collect()).expect("write");
            assert!(path.exists());
        }
        assert!(!path.exists(), "the scratch file outlived its spill");
    }

    /// The error path too: a spill dropped while a write is failing must not strand the file.
    #[test]
    fn the_scratch_file_is_removed_when_a_chunk_is_refused() {
        let path = tmp("refused");
        {
            let spill = ChunkSpill::create(&path).expect("create");
            // Keyed on one layer, carrying another.
            let mut map: BTreeMap<(u64, u8), ChunkEntry> = BTreeMap::new();
            map.insert((1, 2), ChunkEntry::new(3));
            assert!(spill.write_chunk(map).is_err(), "a mislabelled entry was accepted");
        }
        assert!(!path.exists(), "the scratch file outlived a failed write");
    }

    #[test]
    fn an_empty_chunk_is_zero_bytes_and_reads_as_nothing() {
        let spill = ChunkSpill::create(tmp("empty")).expect("create");
        let at = spill.write_chunk(BTreeMap::new()).expect("write");
        assert_eq!(at, ChunkRef { at: 0, len: 0, entries: 0 });
        assert!(drain(&spill, &at, 1 << 16).is_empty());
        spill.check_books().expect("the books balance");
    }

    #[test]
    fn the_books_catch_a_chunk_that_was_never_read() {
        let spill = ChunkSpill::create(tmp("unread")).expect("create");
        spill.write_chunk(every_layer().into_iter().collect()).expect("write");
        assert!(spill.check_books().is_err(), "an undrained spill balanced");
    }

    #[test]
    fn the_read_window_is_a_budget_divided_by_the_stream_count() {
        // `window_for` rather than `read_window_bytes`, so a test elsewhere holding the override
        // cannot make this one assert something else.
        assert_eq!(window_for(0), MAX_WINDOW, "no streams clamps up, not to zero");
        assert_eq!(window_for(1), MAX_WINDOW);
        // A north-america z14: the budget still binds, well clear of both clamps.
        let na = window_for(5_700);
        assert_eq!(na, READ_BUDGET / 5_700);
        assert!((MIN_WINDOW..=MAX_WINDOW).contains(&na));
        // A planet z14: the floor takes over, so the total is `streams * MIN_WINDOW`.
        assert_eq!(window_for(27_000), MIN_WINDOW);
        assert_eq!(window_for(10_000_000), MIN_WINDOW);
        for streams in [1usize, 2, 100, 5_700, 27_000, 10_000_000] {
            let window = window_for(streams);
            assert!(
                (MIN_WINDOW..=MAX_WINDOW).contains(&window),
                "{streams} streams gave a {window}-byte window"
            );
        }
    }
}
