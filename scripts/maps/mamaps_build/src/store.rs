//! The feature store: classified features on **disk** rather than in memory.
//!
//! # Why this exists
//!
//! Measured on `california-latest.osm.pbf`, the generator peaked at 10.03 GB, and a `--max-zoom 12`
//! run peaked at 10.01 GB — so essentially none of it was the tiler. It was all stage A, where three
//! large things were alive at once:
//!
//! | | California |
//! |---|---|
//! | materialised features, lon/lat `f64` | ~4.9 GB |
//! | classified ways and their node refs | ~2.7 GB |
//! | node id -> location table | ~2.5 GB |
//!
//! The features were the largest and the only one that did not have to be resident: nothing reads
//! them until the tiler does, and the tiler reads them **once per zoom, in order**. So they go to a
//! file, and the tiler streams it fifteen times instead of holding it fifteen times over.
//!
//! # The format is `tile_build`'s, not a new one
//!
//! [`spill::NormalizedWriter`] already writes exactly this: a length-prefixed record per feature
//! holding a lon/lat geometry and a property list. Reusing it means the encoding is already tested,
//! already round-tripped, and already handles every geometry kind — and it costs one small
//! indirection: the [`Class`] is packed into a single integer property. That is the plan's own
//! suggestion, and it is why `spill.rs` needed no change.
//!
//! # The second table: classified ways
//!
//! [`WaySink`] and [`WayReader`] do the same for the ~2.7 GB row of that table, for the same reason
//! and by a different route: a way's record is a handful of integers rather than a geometry, and it
//! is read back in the order it was written, so it gets a format of its own rather than
//! `NormalizedWriter`'s. See [`WaySink`] for why no sort is needed on the way back.

use std::fs::File;
use std::io::{BufRead, BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};

use osm_ingest::proto::{err, zigzag, Error, Result};
use tile_build::geom::Geometry;
use tile_build::mvt::Value;
use tile_build::spill::{
    NormalizedChunks, NormalizedReader, NormalizedWriter, NORM_CHUNK_FEATURES,
};

use crate::extract::Feature;
use crate::schema::Class;

/// The property key the packed [`Class`] travels under.
///
/// One byte, because it is written once per feature and 15.5 M allocations of a longer name is a
/// cost with nothing to show for it.
const CLASS_KEY: &str = "c";

/// A [`Class`] as one integer, so a feature's classification costs one property rather than seven.
///
/// | field | bits | width |
/// |---|---|---|
/// | `layer` | 0..3 | 7 layers |
/// | `kind` | 3..19 | `u16` |
/// | `kind_detail` | 19..35 | `u16` |
/// | `flags` | 35..43 | `u8` |
/// | `area` | 43..44 | one bit |
/// | `min_zoom` | 44..49 | 0..31 |
/// | `min_area_px` | 49..57 | 0..255, integral |
///
/// `min_area_px` is an `f64` in [`Class`] and a byte here. Every value the schema uses is a small
/// whole number of square pixels, and [`pack`] refuses anything else rather than rounding a
/// threshold silently.
fn pack(class: &Class) -> Result<u64> {
    if class.layer > 0b111 {
        return err(format!("layer {} does not fit the packed class", class.layer));
    }
    if class.min_zoom > 0b11111 {
        return err(format!("min_zoom {} does not fit the packed class", class.min_zoom));
    }
    // Refused rather than rounded: a threshold quietly changed is a layer quietly wrong.
    if class.min_area_px < 0.0
        || class.min_area_px > 255.0
        || class.min_area_px.fract() != 0.0
    {
        return err(format!(
            "min_area_px {} is not a whole number of square pixels in 0..=255",
            class.min_area_px,
        ));
    }
    Ok((class.layer as u64)
        | ((class.kind as u64) << 3)
        | ((class.kind_detail as u64) << 19)
        | ((class.flags as u64) << 35)
        | ((class.area as u64) << 43)
        | ((class.min_zoom as u64) << 44)
        | ((class.min_area_px as u64) << 49))
}

fn unpack(bits: u64) -> Class {
    Class {
        layer: (bits & 0b111) as u8,
        kind: ((bits >> 3) & 0xffff) as u16,
        kind_detail: ((bits >> 19) & 0xffff) as u16,
        flags: ((bits >> 35) & 0xff) as u8,
        area: (bits >> 43) & 1 == 1,
        min_zoom: ((bits >> 44) & 0b11111) as u8,
        min_area_px: ((bits >> 49) & 0xff) as f64,
    }
}

/// Accumulates features into a file, tracking the bounding box as it goes.
///
/// The bbox is computed here rather than in a pass of its own, because a pass of its own would be a
/// sixteenth read of a four-gigabyte file to learn four numbers.
pub struct Sink {
    writer: NormalizedWriter,
    /// The shallowest `min_zoom` in each spill chunk, and the running one for the chunk being filled.
    ///
    /// One byte per 64 features — 243 KB on California — and it is what lets the tiler **skip** a
    /// chunk rather than deserialise it. Every feature carries a `min_zoom` and most are deep: z14
    /// alone holds 16.9 M against ~5 M across z0..z12. Without this the reader parses the whole 3.3 GB
    /// spill once per zoom, which measured 55.2 s of a 180 s build, all of it on one thread.
    chunk_mins: Vec<u8>,
    filling: u8,
    props: Vec<(String, Value)>,
    count: u64,
    bbox: Option<(i32, i32, i32, i32)>,
}

impl Sink {
    pub fn create(path: impl AsRef<Path>) -> Result<Sink> {
        Ok(Sink {
            writer: NormalizedWriter::create(path.as_ref().to_path_buf())
                .map_err(|e| osm_ingest::proto::Error(e.to_string()))?,
            props: vec![(CLASS_KEY.to_string(), Value::Uint(0))],
            chunk_mins: Vec::new(),
            filling: u8::MAX,
            count: 0,
            bbox: None,
        })
    }

    pub fn push(&mut self, class: &Class, geometry: &Geometry) -> Result<()> {
        self.props[0].1 = Value::Uint(pack(class)?);
        self.writer
            .push(geometry, &self.props)
            .map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
        self.filling = self.filling.min(class.min_zoom);
        self.count += 1;
        // Closed on the same boundary `NormalizedWriter` closes its own chunks on, so entry `i` here
        // describes chunk `i` there. Off by one and the tiler skips the wrong features.
        if self.count % NORM_CHUNK_FEATURES == 0 {
            self.chunk_mins.push(self.filling);
            self.filling = u8::MAX;
        }
        self.grow(geometry);
        Ok(())
    }

    /// Extend the bounding box by a geometry, in degrees times 1e7.
    fn grow(&mut self, geometry: &Geometry) {
        let mut visit = |&(lon, lat): &(f64, f64)| {
            let (x, y) = ((lon * 1e7) as i32, (lat * 1e7) as i32);
            match &mut self.bbox {
                None => self.bbox = Some((x, y, x, y)),
                Some(b) => {
                    b.0 = b.0.min(x);
                    b.1 = b.1.min(y);
                    b.2 = b.2.max(x);
                    b.3 = b.3.max(y);
                }
            }
        };
        match geometry {
            Geometry::Points(points) => points.iter().for_each(&mut visit),
            Geometry::Lines(lines) => lines.iter().flatten().for_each(&mut visit),
            Geometry::Polygons(polygons) => {
                polygons.iter().flatten().flatten().for_each(&mut visit)
            }
        }
    }

    pub fn finish(mut self, path: impl Into<PathBuf>) -> Result<Store> {
        let (count, bbox) = (self.count, self.bbox);
        // The last chunk is usually partial and still needs an entry.
        if self.filling != u8::MAX {
            self.chunk_mins.push(self.filling);
        }
        let chunk_mins = std::mem::take(&mut self.chunk_mins);
        let summary = self.writer.finish().map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
        let chunks = summary.chunks;
        // The two indexes must describe the same chunks, or skipping silently drops real features.
        // Cheap to assert and near-impossible to diagnose from the symptom, which would be missing
        // geometry in a handful of tiles at one zoom.
        let expected = chunks.len().saturating_sub(1);
        if chunk_mins.len() != expected {
            return err(format!(
                "the spill has {expected} chunk(s) but {} zoom entr(ies)",
                chunk_mins.len(),
            ));
        }
        Ok(Store {
            path: path.into(),
            count,
            bbox: bbox.unwrap_or((0, 0, 0, 0)),
            chunks,
            chunk_mins,
        })
    }

    /// The bounding box of everything pushed so far, in degrees.
    ///
    /// `None` until something has been pushed. Used to clip a planet-wide coastline product down to
    /// the area a build actually covers, which is why it is readable mid-stream.
    pub fn bbox_degrees(&self) -> Option<(f64, f64, f64, f64)> {
        let (min_x, min_y, max_x, max_y) = self.bbox?;
        Some((
            min_x as f64 * 1e-7,
            min_y as f64 * 1e-7,
            max_x as f64 * 1e-7,
            max_y as f64 * 1e-7,
        ))
    }
}

/// Features on disk, re-readable in order as many times as the tiler needs.
pub struct Store {
    path: PathBuf,
    /// Byte offset of every 64th record, plus a sentinel holding the file length, so chunk `i` spans
    /// `chunks[i]..chunks[i + 1]` and reads with no knowledge of any other chunk.
    chunks: Vec<u64>,
    /// The shallowest `min_zoom` in each chunk.
    chunk_mins: Vec<u8>,
    count: u64,
    bbox: (i32, i32, i32, i32),
}

impl Store {
    /// How many features are in the file.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn len(&self) -> u64 {
        self.count
    }

    /// The bounding box of every feature, in degrees times 1e7, for the archive header.
    pub fn bbox(&self) -> (i32, i32, i32, i32) {
        self.bbox
    }

    /// Read every feature a zoom could want, in the order they were written.
    ///
    /// A chunk whose shallowest `min_zoom` is deeper than `z` holds nothing this zoom draws, so it is
    /// skipped outright — not read, not parsed. Order is unchanged, because the chunks that survive
    /// are still visited in file order, and that is what keeps the archive byte-identical.
    pub fn reader_for_zoom(&self, z: u8) -> Result<ZoomReader> {
        Ok(ZoomReader {
            inner: NormalizedChunks::open(self.path.clone(), self.chunks.clone())
                .map_err(|e| osm_ingest::proto::Error(e.to_string()))?,
            wanted: self
                .chunk_mins
                .iter()
                .enumerate()
                .filter(|(_, min)| **min <= z)
                .map(|(i, _)| i)
                .collect(),
            at: 0,
            scratch: Vec::new(),
            records: Vec::new(),
        })
    }

    /// Read every feature, in the order they were written.
    ///
    /// Kept as the reference [`Self::reader_for_zoom`] is checked against: a test that reads a store
    /// both ways and compares is the cheapest possible guard on the chunk index being right.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn reader(&self) -> Result<Reader> {
        Ok(Reader {
            inner: NormalizedReader::open(self.path.clone())
                .map_err(|e| osm_ingest::proto::Error(e.to_string()))?,
        })
    }
}

impl Store {
    /// Spill features from memory into a temporary store.
    ///
    /// For tests only. The generator never has a `Vec<Feature>` to hand -- that is the entire point
    /// of this module -- but a test that had to write a file to state its case would be a worse test.
    #[cfg(test)]
    pub fn of(features: &[Feature]) -> Result<Store> {
        use std::sync::atomic::{AtomicU64, Ordering};
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "mamaps_test_{}_{}.features",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed),
        ));
        let mut sink = Sink::create(&path)?;
        for feature in features {
            sink.push(&feature.class, &feature.geometry)?;
        }
        sink.finish(&path)
    }
}

/// A spilled record as a [`Feature`].
///
/// A record whose class property is missing or is not an integer is a corrupt file rather than a
/// feature to skip: everything in here was written by [`Sink::push`] one run ago, so anything else
/// means the file is not the one we wrote.
fn feature_of(record: tile_build::spill::NormalizedFeature) -> Result<Feature> {
    let bits = match record.props.iter().find(|(key, _)| key == CLASS_KEY) {
        Some((_, Value::Uint(bits))) => *bits,
        _ => return err("a spilled feature carries no packed class".to_string()),
    };
    Ok(Feature { class: unpack(bits), geometry: record.geometry })
}

/// Reads the chunks one zoom needs and seeks past the rest.
pub struct ZoomReader {
    inner: NormalizedChunks,
    /// Indices of the chunks this zoom wants, ascending.
    wanted: Vec<usize>,
    at: usize,
    scratch: Vec<u8>,
    records: Vec<tile_build::spill::NormalizedFeature>,
}

impl ZoomReader {
    /// The next feature, or `None` once this zoom's chunks are exhausted.
    pub fn next(&mut self) -> Result<Option<Feature>> {
        loop {
            if let Some(record) = self.records.pop() {
                return Ok(Some(feature_of(record)?));
            }
            let Some(&chunk) = self.wanted.get(self.at) else { return Ok(None) };
            self.at += 1;
            self.inner
                .read_into(chunk, &mut self.scratch, &mut self.records)
                .map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
            // Taken from the back, so reversing here is what preserves file order.
            self.records.reverse();
        }
    }
}

#[cfg_attr(not(test), allow(dead_code))]
pub struct Reader {
    inner: NormalizedReader,
}

impl Reader {
    /// The next feature, or `None` at the end of the file.
    ///
    /// A record whose class property is missing or is not an integer is a corrupt file rather than a
    /// feature to skip: everything in here was written by [`Sink::push`] one run ago, so anything
    /// else means the file is not the one we wrote.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn next(&mut self) -> Result<Option<Feature>> {
        let Some(record) =
            self.inner.next().map_err(|e| osm_ingest::proto::Error(e.to_string()))?
        else {
            return Ok(None);
        };
        Ok(Some(feature_of(record)?))
    }
}

/// Classified ways on disk: the second row of the table above, and the second thing stage A no
/// longer holds.
///
/// # Why a file rather than the `HashMap<i64, Way>` this replaces
///
/// The map cost ~2.7 GB on California, nearly all of it node refs, and every read of it was
/// sequential in ascending id order — [`crate::extract`] sorted the keys precisely so the archive
/// would not depend on hash order. It was a map only because a relation reaches its member ways by
/// id, in whatever order it lists them, and that one random access is served far more cheaply by
/// keeping *only* the relation members resident: California has 63 156 relations against several
/// million classified ways.
///
/// # Why nothing has to be sorted on the way back
///
/// A `.osm.pbf` stores ways sorted by id, and [`osm_ingest::pbf::run_pass_sink`] hands finished
/// chunks to its sink in file order. Appending as pass 1 goes therefore produces a file already in
/// the order materialisation wants, and the sort disappears along with the map.
///
/// [`push`] **enforces** that ordering rather than trusting it. An id that does not advance would
/// silently reorder the archive, and a byte-identical rebuild is the only evidence this pipeline has
/// that a change to stage A preserved its meaning — so a file that is not sorted is an error here,
/// where it is one line of output, rather than a diff in a 655 MB archive.
///
/// # The encoding
///
/// One record per way, every field a varint, nothing aligned:
///
/// | field | encoding |
/// |---|---|
/// | id | zigzag varint of the gap from the previous record's id |
/// | class | unsigned varint of [`pack`]'s integer |
/// | ref count | unsigned varint |
/// | refs | zigzag varint of each ref's gap from the one before it, the first from zero |
///
/// Delta coding earns its keep rather than being a flourish. The refs are ~150 M ids on California
/// and the file is read twice — once to collect the node ids pass 3 must resolve, once to build
/// geometry — so a flat 8 bytes each would be 1.2 GB read twice. Consecutive nodes of a way were
/// almost always created in one editing session and so have near-consecutive ids, which is exactly
/// what a delta collapses to one or two bytes.
///
/// [`push`]: WaySink::push
pub struct WaySink {
    out: BufWriter<File>,
    /// One record's bytes, reused. Several million records, so a `Vec` per record would be several
    /// million allocations for a buffer that is dead a line later.
    record: Vec<u8>,
    last_id: i64,
    count: u64,
    refs: u64,
}

impl WaySink {
    pub fn create(path: &Path) -> Result<WaySink> {
        let file = File::create(path)
            .map_err(|e| Error(format!("cannot create {}: {e}", path.display())))?;
        Ok(WaySink {
            // A megabyte, because the writes are a few dozen bytes each and there are millions.
            out: BufWriter::with_capacity(1 << 20, file),
            record: Vec::new(),
            last_id: 0,
            count: 0,
            refs: 0,
        })
    }

    /// Append one classified way. `id` must be greater than the previous call's.
    pub fn push(&mut self, id: i64, class: &Class, refs: &[i64]) -> Result<()> {
        if self.count > 0 && id <= self.last_id {
            return err(format!(
                "way {id} arrived after way {}, so this PBF is not sorted by way id and the \
                 sequential ways spill cannot be used for it",
                self.last_id,
            ));
        }
        self.record.clear();
        put_svarint(&mut self.record, id.wrapping_sub(self.last_id));
        put_uvarint(&mut self.record, pack(class)?);
        put_uvarint(&mut self.record, refs.len() as u64);
        let mut previous: i64 = 0;
        for &node in refs {
            put_svarint(&mut self.record, node.wrapping_sub(previous));
            previous = node;
        }
        self.out
            .write_all(&self.record)
            .map_err(|e| Error(format!("cannot write the ways spill: {e}")))?;
        self.last_id = id;
        self.count += 1;
        self.refs += refs.len() as u64;
        Ok(())
    }

    /// Flush, and report how many ways were written and how many node refs they hold between them.
    ///
    /// The ref count is not a statistic. [`crate::extract`] reads this file back to collect the node
    /// ids pass 3 must resolve, and that vector is ~200 M ids on California: grown by extension it
    /// doubles into 2.1 GB of capacity, and the realloc that gets it there holds the old and new
    /// allocations at once. Knowing the exact length first turns that into one allocation of exactly
    /// the right size.
    pub fn finish(mut self) -> Result<WayCounts> {
        self.out.flush().map_err(|e| Error(format!("cannot flush the ways spill: {e}")))?;
        Ok(WayCounts { ways: self.count, refs: self.refs })
    }
}

/// What a finished [`WaySink`] holds.
pub struct WayCounts {
    pub ways: u64,
    /// Node refs summed over every way, duplicates included — a capacity, not a cardinality.
    pub refs: u64,
}

/// Reads back what a [`WaySink`] wrote, in the order it was written.
pub struct WayReader {
    inner: BufReader<File>,
    last_id: i64,
    seen: u64,
}

impl WayReader {
    pub fn open(path: &Path) -> Result<WayReader> {
        let file = File::open(path)
            .map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?;
        Ok(WayReader {
            inner: BufReader::with_capacity(1 << 20, file),
            last_id: 0,
            seen: 0,
        })
    }

    /// The next way's id and class, with its node refs written into `refs`.
    ///
    /// `refs` belongs to the caller and is cleared here, so one allocation serves the whole file.
    /// Returning a fresh `Vec` instead would be one allocation per classified way, several million
    /// of them, for a buffer whose contents are dead by the next call — the same reasoning as
    /// [`WaySink::record`].
    ///
    /// A record that ends mid-varint or claims more refs than the file holds is a corrupt spill
    /// rather than a way to skip: this file was written by [`WaySink`] moments ago in the same
    /// process, so anything unreadable means it is not the file we wrote.
    pub fn next(&mut self, refs: &mut Vec<i64>) -> Result<Option<(i64, Class)>> {
        refs.clear();
        let Some(delta) = self.uvarint_or_end()? else {
            return Ok(None);
        };
        let id = self.last_id.wrapping_add(zigzag(delta));
        self.last_id = id;
        self.seen += 1;
        let class = unpack(self.uvarint()?);
        let count = self.uvarint()?;
        // No `reserve` on `count`: it comes off disk, and a corrupt one would be an allocation
        // request rather than the error this returns for every other kind of damage. The buffer is
        // the caller's and is reused across the whole file, so after the first few records it is
        // already as large as any way needs.
        let mut previous: i64 = 0;
        for _ in 0..count {
            previous = previous.wrapping_add(zigzag(self.uvarint()?));
            refs.push(previous);
        }
        Ok(Some((id, class)))
    }

    /// One varint, or `None` if the file ended cleanly on a record boundary.
    fn uvarint_or_end(&mut self) -> Result<Option<u64>> {
        let mut value: u64 = 0;
        let mut shift: u32 = 0;
        loop {
            let buf = self
                .inner
                .fill_buf()
                .map_err(|e| Error(format!("cannot read the ways spill: {e}")))?;
            if buf.is_empty() {
                if shift == 0 {
                    return Ok(None);
                }
                return err(format!("the ways spill ends mid-varint after {} way(s)", self.seen));
            }
            // Consumed in one go per buffer fill rather than a byte at a time: a varint almost
            // always lies wholly inside the buffer, and there are hundreds of millions of them.
            let mut used = 0usize;
            for &byte in buf {
                used += 1;
                if shift >= 64 {
                    return err("a ways spill varint is longer than 64 bits".to_string());
                }
                value |= ((byte & 0x7f) as u64) << shift;
                shift += 7;
                if byte & 0x80 == 0 {
                    self.inner.consume(used);
                    return Ok(Some(value));
                }
            }
            self.inner.consume(used);
        }
    }

    fn uvarint(&mut self) -> Result<u64> {
        match self.uvarint_or_end()? {
            Some(value) => Ok(value),
            None => err(format!("the ways spill ends mid-record after {} way(s)", self.seen)),
        }
    }
}

fn put_uvarint(out: &mut Vec<u8>, mut value: u64) {
    loop {
        let byte = (value & 0x7f) as u8;
        value >>= 7;
        if value == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

/// Zigzagged, so a gap that happens to run backwards costs one bit rather than ten bytes.
/// [`zigzag`] is the decoder, already in `osm_ingest` because the PBF itself is encoded this way.
fn put_svarint(out: &mut Vec<u8>, value: i64) {
    put_uvarint(out, ((value << 1) ^ (value >> 63)) as u64);
}

#[cfg(test)]
mod tests {
    /// **The guard on chunk skipping.** A zoom-filtered read must yield exactly what a full read
    /// yields after the same filter, in the same order. Otherwise skipping drops real geometry, and
    /// the symptom would be a few tiles quietly missing features at one zoom -- no error, no crash.
    #[test]
    fn skipping_chunks_yields_exactly_what_a_full_read_would() {
        let path = temp("zoomfilter");
        let mut sink = Sink::create(&path).expect("create");
        // Several chunks' worth, with min_zoom varying so chunks genuinely differ in what they hold.
        let mut expected_by_zoom: Vec<Vec<u16>> = vec![Vec::new(); 15];
        for i in 0..500u16 {
            let min_zoom = (i % 15) as u8;
            let kind = i.max(1);
            let class = Class::line(dict::LAYER_ROADS, kind, min_zoom);
            let line = vec![(-120.0 + i as f64 * 0.001, 35.0), (-119.9, 35.1)];
            sink.push(&class, &Geometry::Lines(vec![line])).expect("push");
            for z in min_zoom as usize..15 {
                expected_by_zoom[z].push(kind);
            }
        }
        let store = sink.finish(&path).expect("finish");

        for z in 0..15u8 {
            let mut got = Vec::new();
            let mut reader = store.reader_for_zoom(z).expect("reader");
            while let Some(feature) = reader.next().expect("read") {
                // The filter is per chunk, so a kept chunk still carries features too deep for this
                // zoom. The tiler drops those itself; what matters is that nothing wanted is lost.
                if feature.class.min_zoom <= z {
                    got.push(feature.class.kind);
                }
            }
            assert_eq!(got, expected_by_zoom[z as usize], "z{z}");
        }

        // The deepest zoom keeps every chunk, so it must match the sequential reader exactly.
        let mut full = Vec::new();
        let mut reader = store.reader().expect("reader");
        while let Some(feature) = reader.next().expect("read") {
            full.push(feature.class.kind);
        }
        let mut deep = Vec::new();
        let mut reader = store.reader_for_zoom(14).expect("reader");
        while let Some(feature) = reader.next().expect("read") {
            deep.push(feature.class.kind);
        }
        assert_eq!(deep, full, "at the deepest zoom nothing is skipped");
        let _ = std::fs::remove_file(&path);
    }

    use super::*;
    use crate::schema;
    use tilecodec::mamaps::dict;

    fn temp(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!("mamaps_store_{}_{name}", std::process::id()))
    }

    #[test]
    fn a_class_survives_being_packed_into_one_integer() {
        let class = Class {
            layer: dict::LAYER_ROADS,
            kind: 0xbeef,
            kind_detail: 0xcafe,
            flags: 0b1010_0101,
            area: true,
            min_zoom: 14,
            min_area_px: 8.0,
        };
        assert_eq!(unpack(pack(&class).expect("pack")), class);

        // And the zero case, which is most features.
        let plain = Class::line(dict::LAYER_WATER, 1, 0);
        assert_eq!(unpack(pack(&plain).expect("pack")), plain);
    }

    /// **Every class the schema can actually produce** has to round-trip, or a feature arrives at the
    /// tiler as something else. Cheaper to prove exhaustively than to trust the bit widths.
    #[test]
    fn every_class_the_schema_emits_round_trips() {
        let cases: &[&[(&str, &str)]] = &[
            &[("natural", "water")],
            &[("waterway", "river")],
            &[("building", "yes")],
            &[("building:part", "yes")],
            &[("highway", "motorway")],
            &[("highway", "motorway_link"), ("bridge", "yes"), ("tunnel", "yes")],
            &[("railway", "subway")],
            &[("boundary", "administrative"), ("admin_level", "2")],
            &[("boundary", "administrative"), ("admin_level", "8")],
            &[("natural", "wood")],
            &[("leisure", "park")],
            &[("leisure", "pitch")],
            &[("landuse", "residential")],
            &[("place", "island")],
            &[("natural", "cliff")],
        ];
        for tags in cases {
            let class =
                schema::classify(*tags, true, schema::Layers::all()).expect("classified");
            let bits = pack(&class).unwrap_or_else(|e| panic!("{tags:?} will not pack: {e:?}"));
            assert_eq!(unpack(bits), class, "{tags:?}");
        }
    }

    /// A threshold that cannot be represented is refused, not rounded. A minimum area quietly
    /// changed is a layer quietly wrong, and it would be invisible.
    #[test]
    fn an_unrepresentable_class_is_refused_rather_than_rounded() {
        let fractional = Class { min_area_px: 2.5, ..Class::area(0, 1, 0) };
        assert!(pack(&fractional).is_err(), "a fractional threshold");
        let huge = Class { min_area_px: 4096.0, ..Class::area(0, 1, 0) };
        assert!(pack(&huge).is_err(), "a threshold past a byte");
        let deep = Class { min_zoom: 32, ..Class::area(0, 1, 0) };
        assert!(pack(&deep).is_err(), "a zoom past five bits");
    }

    #[test]
    fn features_written_come_back_in_order_with_their_geometry() {
        let path = temp("roundtrip");
        let mut sink = Sink::create(&path).expect("create");
        let square = vec![vec![
            (-120.0, 35.0),
            (-119.0, 35.0),
            (-119.0, 36.0),
            (-120.0, 36.0),
            (-120.0, 35.0),
        ]];
        let lake = Class::area(dict::LAYER_WATER, schema::kind("lake"), 6);
        let road = Class::line(dict::LAYER_ROADS, schema::kind("highway"), 3);
        sink.push(&lake, &Geometry::Polygons(vec![square.clone()])).expect("push");
        sink.push(&road, &Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.5, 35.5)]]))
            .expect("push");
        let store = sink.finish(&path).expect("finish");

        assert_eq!(store.len(), 2);
        // The bbox came for free, rather than from a pass of its own over the whole file.
        assert_eq!(store.bbox(), (-1_200_000_000, 350_000_000, -1_190_000_000, 360_000_000));

        let mut reader = store.reader().expect("reader");
        let first = reader.next().expect("read").expect("a feature");
        assert_eq!(first.class, lake);
        assert!(matches!(first.geometry, Geometry::Polygons(ref p) if p[0] == square));
        let second = reader.next().expect("read").expect("a feature");
        assert_eq!(second.class, road);
        assert!(matches!(second.geometry, Geometry::Lines(_)));
        assert!(reader.next().expect("read").is_none(), "and then the end");

        // Re-readable, because the tiler reads it once per zoom.
        let mut again = store.reader().expect("reader");
        assert_eq!(again.next().expect("read").expect("a feature").class, lake);
        let _ = std::fs::remove_file(&path);
    }

    /// Ids, classes and refs all come back exactly, in the order they went in. The one thing the
    /// ways spill has to guarantee, because the archive's feature order is this file's record order.
    #[test]
    fn spilled_ways_come_back_in_the_order_and_with_the_refs_they_went_in_with() {
        let path = temp("ways_roundtrip");
        let lake = Class::area(dict::LAYER_WATER, schema::kind("lake"), 6);
        let road = Class::line(dict::LAYER_ROADS, schema::kind("highway"), 3);
        // Refs chosen to exercise the delta coding: a large first id, a run of neighbours, a jump
        // backwards, and a way with none at all.
        let cases: Vec<(i64, Class, Vec<i64>)> = vec![
            (1, lake, vec![10_000_000_001, 10_000_000_002, 10_000_000_003, 9_000_000_000]),
            (2, road, vec![]),
            (i64::MAX, lake, vec![-5, 0, 5, i64::MAX, i64::MIN]),
        ];
        let mut sink = WaySink::create(&path).expect("create");
        for (id, class, refs) in &cases {
            sink.push(*id, class, refs).expect("push");
        }
        let counts = sink.finish().expect("finish");
        assert_eq!(counts.ways, 3, "one record per push");
        assert_eq!(counts.refs, 9, "refs summed over every way, so `needed` can be sized once");

        let mut reader = WayReader::open(&path).expect("open");
        let mut refs: Vec<i64> = Vec::new();
        for (id, class, expected) in &cases {
            let (got_id, got_class) = reader.next(&mut refs).expect("read").expect("a way");
            assert_eq!(got_id, *id);
            assert_eq!(got_class, *class);
            assert_eq!(&refs, expected);
        }
        assert!(reader.next(&mut refs).expect("read").is_none(), "and then the end");
        assert!(refs.is_empty(), "the caller's buffer is cleared even at the end");
        let _ = std::fs::remove_file(&path);
    }

    /// The spill's whole premise is that a PBF's ways arrive sorted, so materialisation can stream
    /// the file instead of sorting a map's keys. An input that breaks the premise has to say so:
    /// accepting it would reorder the archive, and a reordered archive is only visible as a
    /// different hash of 655 MB.
    #[test]
    fn a_way_id_that_does_not_advance_is_refused_rather_than_reordering_the_archive() {
        let path = temp("ways_unsorted");
        let class = Class::line(dict::LAYER_ROADS, schema::kind("highway"), 3);
        let mut sink = WaySink::create(&path).expect("create");
        sink.push(100, &class, &[1, 2]).expect("push");
        assert!(sink.push(99, &class, &[3]).is_err(), "an id going backwards");
        assert!(sink.push(100, &class, &[3]).is_err(), "and the same id twice");
        sink.push(101, &class, &[3]).expect("but forwards is fine");
        let _ = std::fs::remove_file(&path);
    }

    /// A truncated spill is a corrupt file, not a short one. It was written by `WaySink` in this
    /// same process moments earlier, so a record that will not parse means the file is not ours.
    #[test]
    fn a_truncated_ways_spill_is_an_error_rather_than_a_silently_short_read() {
        let path = temp("ways_truncated");
        let class = Class::area(dict::LAYER_WATER, schema::kind("lake"), 6);
        let mut sink = WaySink::create(&path).expect("create");
        sink.push(1, &class, &[7, 8, 9]).expect("push");
        sink.push(2, &class, &[11, 12, 13]).expect("push");
        sink.finish().expect("finish");

        let whole = std::fs::read(&path).expect("read");
        // Cut the second record short, leaving it claiming refs the file does not hold.
        std::fs::write(&path, &whole[..whole.len() - 2]).expect("truncate");
        let mut reader = WayReader::open(&path).expect("open");
        let mut refs: Vec<i64> = Vec::new();
        assert!(reader.next(&mut refs).expect("read").is_some(), "the first record survives");
        assert!(reader.next(&mut refs).is_err(), "the cut one does not");
        let _ = std::fs::remove_file(&path);
    }

    /// Delta coding is the reason the file can be read twice without the I/O mattering. Worth
    /// pinning: a regression to fixed-width would be invisible except as a slower build.
    #[test]
    fn near_consecutive_refs_cost_about_a_byte_each() {
        let path = temp("ways_size");
        let class = Class::line(dict::LAYER_ROADS, schema::kind("highway"), 3);
        let refs: Vec<i64> = (0..1000).map(|i| 10_000_000_000 + i).collect();
        let mut sink = WaySink::create(&path).expect("create");
        sink.push(1, &class, &refs).expect("push");
        sink.finish().expect("finish");
        let bytes = std::fs::metadata(&path).expect("metadata").len();
        // The first ref is a full-width id; every one after it is a delta of 1, one byte.
        assert!(bytes < 1100, "{bytes} bytes for 1000 refs, against 8000 fixed-width");
        let _ = std::fs::remove_file(&path);
    }
}
