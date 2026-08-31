//! On-disk spill for the streaming tiler: a record format, and `tile_id`-range buckets.
//!
//! [`crate::pyramid::build_archive`] holds every feature and, per zoom, every CLIPPED
//! copy of every geometry: one per `(feature, tile)` pair, plus the `tile -> candidates`
//! map that indexes them. So it is proportional to the input bytes and then again to the
//! zoom's OUTPUT geometry, which is why a `roads` layer — every OSM road, z11-16,
//! planet-wide — cannot be built by it: the `maxspeed` layer, which is only the ways
//! carrying a `maxspeed` tag and one zoom shallower, already produces an 8.3 GB archive.
//!
//! Holding the clipped copies is what [`crate::subdivide`] costs that path. The old loop
//! clipped lazily inside the per-tile encode batch and could throw each copy away, but it
//! paid a full vertex walk per tile to do it — `O(T · V)` for a feature reaching `T`
//! tiles. Keeping the descent's output instead trades memory for that, and it is the
//! right trade *here* because this path is the in-memory oracle the byte-identity tests
//! pin the streaming one against; anything at real scale goes through
//! [`crate::pyramid::build_archive_to`], which spills these very records to disk.
//!
//! This module is the disk that replaces that memory. It follows
//! `osm_ingest::chains`: manual little-endian encode and decode, a zero-filled reserved
//! tail so a field can be added without moving anything, counts cross-checked against
//! file length, and `Drop` cleanup so a run that dies mid-planet cannot strand tens of
//! gigabytes.
//!
//! Two files, for two different jobs:
//!
//! * **The normalized file** ([`NormalizedWriter`]) is the geojsonseq parsed exactly
//!   once into a compact binary. Every zoom then re-reads that instead of re-parsing
//!   JSON, which is the cheaper end of a trade that has to be made six times. The same
//!   pass records a fixed-stride offset index ([`NormalizedSummary::chunks`]), so those
//!   re-reads can be spread across the pool via [`NormalizedChunks`] rather than pulled
//!   through one cursor.
//! * **The buckets** ([`BucketSet`]) hold one record per `(feature, tile)` pair for ONE
//!   zoom, partitioned by `tile_id` range.
//!
//! # Why buckets and not an external merge sort
//!
//! [`crate::pmtiles::StreamBuilder`] needs tiles in ascending `tile_id`. A bucket index
//! of `(tile_id - lo) / span` is monotonic in `tile_id` by construction, so consuming
//! buckets in ascending index, each one internally sorted, yields globally ascending
//! `tile_id` — which is exactly that precondition, for one write and one read of the
//! spill. `osm_ingest::graph_build`'s rescan-per-round model would instead re-read the
//! whole spill once per round, and because Hilbert ranges are spatially local they are
//! badly skewed: an ocean range is empty and a metro range is enormous, so the round
//! count would have to be sized for the worst range and the rescan becomes thousands of
//! full passes. There is no `BinaryHeap` here for the same reason there is none anywhere
//! else in `scripts/maps`.
//!
//! Skew is handled by re-partitioning an oversized bucket over its own id sub-range
//! rather than by hoping. Sub-buckets ascend within a parent and parents ascend, so the
//! append-only order holds at every depth.
//!
//! # Props are inlined
//!
//! A feature touching forty tiles writes its properties forty times. That costs spill
//! bytes and keeps every read sequential, which is the trade worth making first. The
//! alternative — properties once in the normalized file plus a fixed-stride index keyed
//! by `seq`, exactly the `chains.rs` `hdr`+`pts` shape — is what [`SpillRecord::seq`] is
//! for, and should only be built if a measured run approaches the disk ceiling.

use crate::geom::{Geometry, IPt, IntGeometry, Pt, Rect};
use crate::mvt::Value;
use crate::proto::{err, Error, Result};
use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};

/// Bytes of fixed header before a spill record's two variable payloads.
///
/// The trailing padding is reserved and written as zero, so a field can be added
/// without moving the ones already there. A decoder that finds it nonzero is reading
/// something a newer writer produced, and says so rather than guessing.
pub const REC_HEADER_BYTES: usize = 48;

/// Bytes of fixed header before a normalized record's two variable payloads.
pub const NORM_HEADER_BYTES: usize = 16;

/// Features per entry in the normalized file's chunk index.
///
/// The index is what lets the bucket pass read the normalized file across the pool
/// instead of through one cursor: a worker takes a chunk, reads its byte range
/// positionally, and decodes the records in it. Records are self-delimiting, so an
/// offset every `NORM_CHUNK_FEATURES` features is all that is needed — a per-feature
/// table would cost 64x the memory to save nothing.
///
/// 64 is a compromise between two hard constraints. Larger chunks mean fewer tasks
/// per batch, and the batch is bounded by memory (`par::batch_len()` features), so a
/// stride of 1024 would leave a 64-core box with four tasks per batch. Smaller chunks
/// mean more index entries and smaller reads. At 64 the batch is one chunk per thread
/// at memory parity with the serial path, and the index is 8 bytes per 64 features:
/// a 200M-feature Europe extract indexes in ~25 MB.
pub const NORM_CHUNK_FEATURES: u64 = 64;

/// A record longer than this is corruption, not a large feature. The largest plausible
/// single OSM geometry is a coastline relation at a few million vertices, which is two
/// orders of magnitude below this.
const MAX_RECORD_BYTES: u64 = 1 << 30;

/// Which [`Geometry`] variant a record holds.
///
/// Stored as a byte so the decoder does not have to infer it, and reused as the
/// normalized file's summary of the input's geometry kind.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GeomKind {
    Points,
    Lines,
    Polygons,
}

impl GeomKind {
    fn tag(self) -> u8 {
        match self {
            GeomKind::Points => 0,
            GeomKind::Lines => 1,
            GeomKind::Polygons => 2,
        }
    }

    fn from_tag(tag: u8) -> Result<GeomKind> {
        match tag {
            0 => Ok(GeomKind::Points),
            1 => Ok(GeomKind::Lines),
            2 => Ok(GeomKind::Polygons),
            other => err(format!("spill record has geometry kind {other}")),
        }
    }

    pub fn of(g: &Geometry) -> GeomKind {
        match g {
            Geometry::Points(_) => GeomKind::Points,
            Geometry::Lines(_) => GeomKind::Lines,
            Geometry::Polygons(_) => GeomKind::Polygons,
        }
    }

    pub fn of_int(g: &IntGeometry) -> GeomKind {
        match g {
            IntGeometry::Points(_) => GeomKind::Points,
            IntGeometry::Lines(_) => GeomKind::Lines,
            IntGeometry::Polygons(_) => GeomKind::Polygons,
        }
    }
}

// --- little-endian primitives -------------------------------------------------

/// A bounds-checked cursor over a record's bytes.
///
/// Every read is fallible, because the bytes come off a disk file that a previous run
/// may have been killed halfway through writing.
struct Cur<'a> {
    b: &'a [u8],
    i: usize,
}

impl<'a> Cur<'a> {
    fn new(b: &'a [u8]) -> Cur<'a> {
        Cur { b, i: 0 }
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self.i.checked_add(n).filter(|e| *e <= self.b.len());
        match end {
            Some(end) => {
                let s = &self.b[self.i..end];
                self.i = end;
                Ok(s)
            }
            None => err(format!(
                "spill payload wants {n} byte(s) at offset {} of {}",
                self.i,
                self.b.len()
            )),
        }
    }

    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().expect("4 bytes")))
    }

    fn u64(&mut self) -> Result<u64> {
        Ok(u64::from_le_bytes(self.take(8)?.try_into().expect("8 bytes")))
    }

    fn i32(&mut self) -> Result<i32> {
        Ok(i32::from_le_bytes(self.take(4)?.try_into().expect("4 bytes")))
    }

    fn i64(&mut self) -> Result<i64> {
        Ok(i64::from_le_bytes(self.take(8)?.try_into().expect("8 bytes")))
    }

    /// How many bytes the decoder consumed, so the caller can check it against the
    /// length the header claimed.
    fn consumed(&self) -> usize {
        self.i
    }

    fn at_end(&self) -> bool {
        self.i == self.b.len()
    }
}

fn put_u32(out: &mut Vec<u8>, v: u32) {
    out.extend_from_slice(&v.to_le_bytes());
}

fn put_u64(out: &mut Vec<u8>, v: u64) {
    out.extend_from_slice(&v.to_le_bytes());
}

fn count(n: usize) -> Result<u32> {
    u32::try_from(n).map_err(|_| Error(format!("spill cannot encode a count of {n}")))
}

// --- integer geometry codec ---------------------------------------------------

/// Encode an [`IntGeometry`]. The kind is carried in the record header, so this is only
/// the shape and the vertices.
pub fn encode_int_geometry(g: &IntGeometry, out: &mut Vec<u8>) -> Result<()> {
    let put_ring = |ring: &[IPt], out: &mut Vec<u8>| -> Result<()> {
        put_u32(out, count(ring.len())?);
        for (x, y) in ring {
            out.extend_from_slice(&x.to_le_bytes());
            out.extend_from_slice(&y.to_le_bytes());
        }
        Ok(())
    };
    match g {
        IntGeometry::Points(p) => put_ring(p, out)?,
        IntGeometry::Lines(lines) => {
            put_u32(out, count(lines.len())?);
            for l in lines {
                put_ring(l, out)?;
            }
        }
        IntGeometry::Polygons(polys) => {
            put_u32(out, count(polys.len())?);
            for rings in polys {
                put_u32(out, count(rings.len())?);
                for r in rings {
                    put_ring(r, out)?;
                }
            }
        }
    }
    Ok(())
}

fn decode_int_ring(c: &mut Cur) -> Result<Vec<IPt>> {
    let n = c.u32()? as usize;
    // Eight bytes a vertex, so a count larger than what is left is corruption rather
    // than a request to allocate gigabytes.
    let mut out = Vec::with_capacity(n.min(1 << 16));
    for _ in 0..n {
        let x = c.i32()?;
        let y = c.i32()?;
        out.push((x, y));
    }
    Ok(out)
}

fn decode_int_geometry(kind: GeomKind, b: &[u8]) -> Result<IntGeometry> {
    let mut c = Cur::new(b);
    let g = match kind {
        GeomKind::Points => IntGeometry::Points(decode_int_ring(&mut c)?),
        GeomKind::Lines => {
            let n = c.u32()? as usize;
            let mut lines = Vec::with_capacity(n.min(1 << 16));
            for _ in 0..n {
                lines.push(decode_int_ring(&mut c)?);
            }
            IntGeometry::Lines(lines)
        }
        GeomKind::Polygons => {
            let n = c.u32()? as usize;
            let mut polys = Vec::with_capacity(n.min(1 << 16));
            for _ in 0..n {
                let rings_n = c.u32()? as usize;
                let mut rings = Vec::with_capacity(rings_n.min(1 << 16));
                for _ in 0..rings_n {
                    rings.push(decode_int_ring(&mut c)?);
                }
                polys.push(rings);
            }
            IntGeometry::Polygons(polys)
        }
    };
    if !c.at_end() {
        return err(format!(
            "spill geometry decoded {} of {} byte(s)",
            c.consumed(),
            b.len()
        ));
    }
    Ok(g)
}

// --- lon/lat geometry codec ---------------------------------------------------

/// Encode a lon/lat [`Geometry`] for the normalized file, quantised to e7.
///
/// **The wire format is [`encode_int_geometry`]'s**, vertex for vertex: the same nested
/// counts and the same `(i32, i32)` pairs, so [`decode_int_ring`]'s corruption bound
/// applies here unchanged. It is spelled out again rather than delegated because routing
/// a read through [`IntGeometry`] would allocate the whole nested geometry a second time
/// only to convert it, and the normalized file is decoded once per feature per zoom --
/// fifteen times over a z0-14 build.
///
/// # Why quantising is lossless for the data this carries
///
/// `osm_ingest`'s node table stores coordinates as `(i32 lat_e7, i32 lon_e7)`, and
/// `mamaps_build`'s `extract::locate` multiplies by `1e-7` purely to hand `f64` degrees
/// on. [`crate::pyramid::e7`] recovers the exact integer from that product, so an
/// OSM-only archive is byte-identical at half the spill bytes.
///
/// A shapefile-derived coastline is arbitrary `f64` and IS quantised, to ~1.1 cm. That is
/// ~1/70th of a z14 tile unit, so it cannot move a pixel, but it does change such an
/// archive's bytes and so needs its own baseline hash.
pub fn encode_geometry(g: &Geometry, out: &mut Vec<u8>) -> Result<()> {
    let put_ring = |ring: &[Pt], out: &mut Vec<u8>| -> Result<()> {
        put_u32(out, count(ring.len())?);
        for (x, y) in ring {
            out.extend_from_slice(&crate::pyramid::e7(*x).to_le_bytes());
            out.extend_from_slice(&crate::pyramid::e7(*y).to_le_bytes());
        }
        Ok(())
    };
    match g {
        Geometry::Points(p) => put_ring(p, out)?,
        Geometry::Lines(lines) => {
            put_u32(out, count(lines.len())?);
            for l in lines {
                put_ring(l, out)?;
            }
        }
        Geometry::Polygons(polys) => {
            put_u32(out, count(polys.len())?);
            for rings in polys {
                put_u32(out, count(rings.len())?);
                for r in rings {
                    put_ring(r, out)?;
                }
            }
        }
    }
    Ok(())
}

fn decode_ring(c: &mut Cur) -> Result<Vec<Pt>> {
    let n = c.u32()? as usize;
    let mut out = Vec::with_capacity(n.min(1 << 16));
    for _ in 0..n {
        let x = c.i32()?;
        let y = c.i32()?;
        out.push((x as f64 * 1e-7, y as f64 * 1e-7));
    }
    Ok(out)
}

fn decode_geometry(kind: GeomKind, b: &[u8]) -> Result<Geometry> {
    let mut c = Cur::new(b);
    let g = match kind {
        GeomKind::Points => Geometry::Points(decode_ring(&mut c)?),
        GeomKind::Lines => {
            let n = c.u32()? as usize;
            let mut lines = Vec::with_capacity(n.min(1 << 16));
            for _ in 0..n {
                lines.push(decode_ring(&mut c)?);
            }
            Geometry::Lines(lines)
        }
        GeomKind::Polygons => {
            let n = c.u32()? as usize;
            let mut polys = Vec::with_capacity(n.min(1 << 16));
            for _ in 0..n {
                let rings_n = c.u32()? as usize;
                let mut rings = Vec::with_capacity(rings_n.min(1 << 16));
                for _ in 0..rings_n {
                    rings.push(decode_ring(&mut c)?);
                }
                polys.push(rings);
            }
            Geometry::Polygons(polys)
        }
    };
    if !c.at_end() {
        return err(format!(
            "spill geometry decoded {} of {} byte(s)",
            c.consumed(),
            b.len()
        ));
    }
    Ok(g)
}

// --- property codec ----------------------------------------------------------

/// Encode a feature's properties.
///
/// Not [`crate::mvt`]'s layer encoder, which dictionary-encodes keys and values across a
/// whole layer. That is the right format for a tile and the wrong one for a record: a
/// bucket is read one record at a time and has no layer to share a dictionary with.
pub fn encode_props(props: &[(String, Value)], out: &mut Vec<u8>) -> Result<()> {
    put_u32(out, count(props.len())?);
    for (k, v) in props {
        put_u32(out, count(k.len())?);
        out.extend_from_slice(k.as_bytes());
        // Matched exhaustively with no `_` arm on purpose: adding an MVT value type
        // should break this build rather than silently spill the wrong bytes.
        match v {
            Value::String(s) => {
                out.push(0);
                put_u32(out, count(s.len())?);
                out.extend_from_slice(s.as_bytes());
            }
            Value::Float(f) => {
                out.push(1);
                out.extend_from_slice(&f.to_bits().to_le_bytes());
            }
            Value::Double(d) => {
                out.push(2);
                out.extend_from_slice(&d.to_bits().to_le_bytes());
            }
            Value::Int(i) => {
                out.push(3);
                out.extend_from_slice(&i.to_le_bytes());
            }
            Value::Uint(u) => {
                out.push(4);
                put_u64(out, *u);
            }
            Value::SInt(i) => {
                out.push(5);
                out.extend_from_slice(&i.to_le_bytes());
            }
            Value::Bool(b) => {
                out.push(6);
                out.push(u8::from(*b));
            }
        }
    }
    Ok(())
}

fn decode_props(b: &[u8]) -> Result<Vec<(String, Value)>> {
    let mut c = Cur::new(b);
    let n = c.u32()? as usize;
    let mut out = Vec::with_capacity(n.min(1 << 12));
    for _ in 0..n {
        let key_len = c.u32()? as usize;
        let key = std::str::from_utf8(c.take(key_len)?)
            .map_err(|e| Error(format!("spill property key is not UTF-8: {e}")))?
            .to_string();
        let tag = c.u8()?;
        let value = match tag {
            0 => {
                let len = c.u32()? as usize;
                Value::String(
                    std::str::from_utf8(c.take(len)?)
                        .map_err(|e| Error(format!("spill property value is not UTF-8: {e}")))?
                        .to_string(),
                )
            }
            1 => Value::Float(f32::from_bits(c.u32()?)),
            2 => Value::Double(f64::from_bits(c.u64()?)),
            3 => Value::Int(c.i64()?),
            4 => Value::Uint(c.u64()?),
            5 => Value::SInt(c.i64()?),
            6 => Value::Bool(c.u8()? != 0),
            other => return err(format!("spill property has value type {other}")),
        };
        out.push((key, value));
    }
    if !c.at_end() {
        return err(format!(
            "spill properties decoded {} of {} byte(s)",
            c.consumed(),
            b.len()
        ));
    }
    Ok(out)
}

// --- the spill record --------------------------------------------------------

/// One feature's contribution to one tile, as a bucket stores it.
///
/// Already clipped, moved into the tile and simplified: the bucket pass does that work
/// once and the encode pass does none of it. `extent` is the importance proxy, computed
/// there too, so the encode pass's sort is a comparison of two integers.
#[derive(Debug, Clone, PartialEq)]
pub struct SpillRecord {
    pub tile_id: u64,
    /// The feature's position in the input. What makes the drop policy's order total,
    /// and the key the props-normalizing variant of this format would use.
    pub seq: u64,
    pub extent: i64,
    pub geom: IntGeometry,
    pub props: Vec<(String, Value)>,
}

impl SpillRecord {
    /// Append the wire form of this record to `out`.
    pub fn encode(&self, out: &mut Vec<u8>) -> Result<()> {
        encode_record(
            self.tile_id,
            self.seq,
            self.extent,
            &self.geom,
            &self.props,
            out,
        )
    }

    /// The whole record, ready to hand to [`BucketSet::push`].
    pub fn to_bytes(&self) -> Result<Vec<u8>> {
        let mut out = Vec::new();
        self.encode(&mut out)?;
        Ok(out)
    }
}

/// Append one record's wire form to `out`, without owning it first.
///
/// The bucket pass has a borrowed geometry and a borrowed property list and writes the
/// same feature's properties once per touched tile; building a [`SpillRecord`] to encode
/// it would clone them forty times over for nothing.
///
/// ```text
/// 0..4    u32 rec_len       whole record, this field included
/// 4..12   u64 tile_id
/// 12..20  u64 seq
/// 20..28  i64 extent
/// 28..32  u32 geom_len
/// 32..36  u32 props_len
/// 36..37  u8  geom_kind
/// 37..48  reserved, zero
/// 48..    geometry, then properties
/// ```
pub fn encode_record(
    tile_id: u64,
    seq: u64,
    extent: i64,
    geom: &IntGeometry,
    props: &[(String, Value)],
    out: &mut Vec<u8>,
) -> Result<()> {
    let start = out.len();
    out.resize(start + REC_HEADER_BYTES, 0);
    encode_int_geometry(geom, out)?;
    let geom_len = count(out.len() - start - REC_HEADER_BYTES)?;
    encode_props(props, out)?;
    let props_len = count(out.len() - start - REC_HEADER_BYTES - geom_len as usize)?;
    let rec_len = count(out.len() - start)?;

    let h = &mut out[start..start + REC_HEADER_BYTES];
    h[0..4].copy_from_slice(&rec_len.to_le_bytes());
    h[4..12].copy_from_slice(&tile_id.to_le_bytes());
    h[12..20].copy_from_slice(&seq.to_le_bytes());
    h[20..28].copy_from_slice(&extent.to_le_bytes());
    h[28..32].copy_from_slice(&geom_len.to_le_bytes());
    h[32..36].copy_from_slice(&props_len.to_le_bytes());
    h[36] = GeomKind::of_int(geom).tag();
    Ok(())
}

/// A record header, as read off disk.
struct RecHeader {
    rec_len: u32,
    tile_id: u64,
    seq: u64,
    extent: i64,
    geom_len: u32,
    props_len: u32,
    kind: GeomKind,
}

impl RecHeader {
    fn parse(b: &[u8; REC_HEADER_BYTES]) -> Result<RecHeader> {
        let u32_at = |o: usize| u32::from_le_bytes(b[o..o + 4].try_into().expect("4 bytes"));
        let u64_at = |o: usize| u64::from_le_bytes(b[o..o + 8].try_into().expect("8 bytes"));
        // A newer writer would use these, so a nonzero tail means the reader is the
        // wrong version for the file. Guessing would decode a field that has moved.
        if b[37..REC_HEADER_BYTES].iter().any(|v| *v != 0) {
            return err("spill record has a nonzero reserved tail");
        }
        let rec_len = u32_at(0);
        let geom_len = u32_at(28);
        let props_len = u32_at(32);
        let want = REC_HEADER_BYTES as u64 + geom_len as u64 + props_len as u64;
        if rec_len as u64 != want {
            return err(format!(
                "spill record claims {rec_len} byte(s) but its header adds up to {want}"
            ));
        }
        if want > MAX_RECORD_BYTES {
            return err(format!("spill record is {want} byte(s), which is corruption"));
        }
        Ok(RecHeader {
            rec_len,
            tile_id: u64_at(4),
            seq: u64_at(12),
            extent: i64::from_le_bytes(b[20..28].try_into().expect("8 bytes")),
            geom_len,
            props_len,
            kind: GeomKind::from_tag(b[36])?,
        })
    }
}

// --- buckets -----------------------------------------------------------------

/// `tile_id`-range buckets for one zoom, or for one over-budget bucket's sub-range.
///
/// Bucket `i` holds every `tile_id` in `[lo + i*span, lo + (i+1)*span)`, so the index is
/// monotonic in `tile_id`: draining buckets in ascending index, each sorted, gives
/// globally ascending `tile_id`.
///
/// The bucket count is a power of four and the range is a power of four, so `span` is
/// too and a bucket is exactly one quadtree cell's descendants. That keeps a bucket
/// spatially coherent, which is what makes a re-partition of a dense metro bucket
/// productive rather than splitting the same crowd four ways.
///
/// **The count must stay under the process's file-descriptor limit.** Files are opened
/// lazily, so a shallow zoom with a handful of occupied ranges only creates a handful —
/// but a dense zoom will hold every one of them open at once. 256 is the default and
/// safe everywhere; a few thousand is not, and this deliberately does not paper over it
/// with a handle cache.
pub struct BucketSet {
    dir: PathBuf,
    /// First `tile_id` this set covers.
    lo: u64,
    /// One past the last `tile_id` this set covers.
    hi: u64,
    span: u64,
    paths: Vec<PathBuf>,
    files: Vec<Option<BufWriter<File>>>,
    /// Tallied at push time from each record's own `tile_id`. The reader re-derives both
    /// from the bytes on disk, and [`BucketSet::seal`] asserts they agree with each
    /// other and with the file length.
    records: Vec<u64>,
    bytes: Vec<u64>,
    pushed: u64,
    pushed_bytes: u64,
    sealed: bool,
}

impl BucketSet {
    /// `name` distinguishes one set's files from another's in the same directory, so a
    /// zoom and its re-partitions do not collide.
    ///
    /// `hi - lo` must be positive; it is a power of four in every use here.
    pub fn new(
        dir: impl Into<PathBuf>,
        name: &str,
        lo: u64,
        hi: u64,
        want_buckets: usize,
    ) -> Result<BucketSet> {
        if hi <= lo {
            return err(format!("bucket set range {lo}..{hi} is empty"));
        }
        if want_buckets == 0 {
            return err("bucket set needs at least one bucket");
        }
        if !want_buckets.is_power_of_two() || !want_buckets.trailing_zeros().is_multiple_of(2) {
            return err(format!(
                "bucket count must be a power of four so a bucket is one quadtree cell, got \
                 {want_buckets}"
            ));
        }
        let range = hi - lo;
        // A shallow zoom has fewer tiles than the requested bucket count. Dividing by
        // four keeps the quadtree property that clamping to `range` would break.
        let mut n = want_buckets as u64;
        while n > 1 && n > range {
            n /= 4;
        }
        let span = range.div_ceil(n);
        let n = range.div_ceil(span) as usize;

        let dir = dir.into();
        std::fs::create_dir_all(&dir)
            .map_err(|e| Error(format!("cannot create {}: {e}", dir.display())))?;
        let paths: Vec<PathBuf> = (0..n)
            .map(|i| dir.join(format!("{name}.{i:05}.bucket")))
            .collect();
        // A bucket that receives no records is never opened, so `File::create`'s truncate
        // would not clear a file a SIGKILLed run left at the same path -- and `seal`
        // would then report bytes on disk that nobody pushed. With an operator-supplied
        // --spill-dir that turns a crash into a baffling failure on the next attempt.
        for p in &paths {
            let _ = std::fs::remove_file(p);
        }
        Ok(BucketSet {
            dir,
            lo,
            hi,
            span,
            paths,
            files: (0..n).map(|_| None).collect(),
            records: vec![0; n],
            bytes: vec![0; n],
            pushed: 0,
            pushed_bytes: 0,
            sealed: false,
        })
    }

    pub fn len(&self) -> usize {
        self.paths.len()
    }

    pub fn is_empty(&self) -> bool {
        self.paths.is_empty()
    }

    pub fn span(&self) -> u64 {
        self.span
    }

    /// `[lo, hi)` of bucket `i`, clamped to the set's own range.
    pub fn range_of(&self, i: usize) -> (u64, u64) {
        let lo = self.lo + i as u64 * self.span;
        (lo, (lo + self.span).min(self.hi))
    }

    pub fn records_in(&self, i: usize) -> u64 {
        self.records[i]
    }

    pub fn bytes_in(&self, i: usize) -> u64 {
        self.bytes[i]
    }

    pub fn total_records(&self) -> u64 {
        self.pushed
    }

    fn index_of(&self, tile_id: u64) -> Result<usize> {
        if tile_id < self.lo || tile_id >= self.hi {
            return err(format!(
                "tile id {tile_id} is outside the bucket set's range {}..{}",
                self.lo, self.hi
            ));
        }
        Ok(((tile_id - self.lo) / self.span) as usize)
    }

    /// Append one already-encoded record to whichever bucket its `tile_id` names.
    pub fn push(&mut self, tile_id: u64, record: &[u8]) -> Result<()> {
        if self.sealed {
            return err("a sealed bucket set cannot be pushed to");
        }
        let i = self.index_of(tile_id)?;
        if self.files[i].is_none() {
            let f = File::create(&self.paths[i])
                .map_err(|e| Error(format!("cannot create {}: {e}", self.paths[i].display())))?;
            self.files[i] = Some(BufWriter::with_capacity(1 << 18, f));
        }
        let w = self.files[i].as_mut().expect("just opened");
        w.write_all(record)
            .map_err(|e| Error(format!("writing {}: {e}", self.paths[i].display())))?;
        self.records[i] += 1;
        self.bytes[i] += record.len() as u64;
        self.pushed += 1;
        self.pushed_bytes += record.len() as u64;
        Ok(())
    }

    /// Flush every bucket and check the books.
    ///
    /// The push-time tallies, the file lengths and the set's totals must all agree.
    /// This is `graph_build.rs`'s expected-count gate adapted to a partition: a bucket
    /// that quietly lost records would produce an archive with holes in it and nothing
    /// downstream could tell.
    pub fn seal(&mut self) -> Result<()> {
        for (i, slot) in self.files.iter_mut().enumerate() {
            if let Some(w) = slot {
                w.flush()
                    .map_err(|e| Error(format!("flushing {}: {e}", self.paths[i].display())))?;
            }
            *slot = None;
        }
        let mut records = 0u64;
        let mut bytes = 0u64;
        for i in 0..self.paths.len() {
            records += self.records[i];
            bytes += self.bytes[i];
            let on_disk = match std::fs::metadata(&self.paths[i]) {
                Ok(m) => m.len(),
                Err(_) if self.records[i] == 0 => 0,
                Err(e) => {
                    return err(format!("cannot stat {}: {e}", self.paths[i].display()));
                }
            };
            if on_disk != self.bytes[i] {
                return err(format!(
                    "{} is {on_disk} byte(s) on disk but {} were pushed to it",
                    self.paths[i].display(),
                    self.bytes[i]
                ));
            }
        }
        if records != self.pushed || bytes != self.pushed_bytes {
            return err(format!(
                "bucket totals are {records} record(s)/{bytes} byte(s) but {} record(s)/{} \
                 byte(s) were pushed",
                self.pushed, self.pushed_bytes
            ));
        }
        self.sealed = true;
        Ok(())
    }

    /// A reader over bucket `i`, or `None` when it holds nothing.
    ///
    /// Only valid after [`BucketSet::seal`]: reading a bucket whose writer has not been
    /// flushed would miss its tail.
    pub fn reader(&self, i: usize) -> Result<Option<BucketReader>> {
        if !self.sealed {
            return err("a bucket set must be sealed before it is read");
        }
        if self.records[i] == 0 {
            return Ok(None);
        }
        let (lo, hi) = self.range_of(i);
        let f = File::open(&self.paths[i])
            .map_err(|e| Error(format!("cannot read {}: {e}", self.paths[i].display())))?;
        Ok(Some(BucketReader {
            src: BufReader::with_capacity(1 << 18, f),
            path: self.paths[i].clone(),
            lo,
            hi,
            expect_records: self.records[i],
            expect_bytes: self.bytes[i],
            seen_records: 0,
            seen_bytes: 0,
            done: false,
            buf: Vec::new(),
        }))
    }

    /// Every record in bucket `i`, checked against the push-time tallies.
    pub fn load(&self, i: usize) -> Result<Vec<SpillRecord>> {
        let Some(mut r) = self.reader(i)? else { return Ok(Vec::new()) };
        let mut out = Vec::with_capacity(self.records[i].min(1 << 22) as usize);
        while let Some(rec) = r.next()? {
            out.push(rec);
        }
        Ok(out)
    }

    /// Best-effort cleanup, also run on drop.
    pub fn remove(&mut self) {
        for slot in self.files.iter_mut() {
            *slot = None;
        }
        for p in &self.paths {
            let _ = std::fs::remove_file(p);
        }
        // Only if empty, so a shared spill directory survives.
        let _ = std::fs::remove_dir(&self.dir);
    }
}

impl Drop for BucketSet {
    /// A zoom's buckets are a temporary, so their lifetime is the set's. Per-zoom spill
    /// is the reason peak disk is the largest single zoom rather than the sum of them,
    /// and that only holds if a dropped set really does take its files with it.
    fn drop(&mut self) {
        self.remove();
    }
}

/// Sequential reader over one bucket.
///
/// Errors if a record's `tile_id` falls outside the bucket's range, and at the end if
/// the record or byte count disagrees with what the writer tallied. The order the
/// encode pass depends on is asserted directly by its own test;
/// [`crate::pmtiles::StreamBuilder::add_tile_raw`] refusing a non-ascending id is the
/// second line of defence, not the first.
pub struct BucketReader {
    src: BufReader<File>,
    path: PathBuf,
    lo: u64,
    hi: u64,
    expect_records: u64,
    expect_bytes: u64,
    seen_records: u64,
    seen_bytes: u64,
    done: bool,
    buf: Vec<u8>,
}

impl BucketReader {
    #[allow(clippy::should_implement_trait)]
    pub fn next(&mut self) -> Result<Option<SpillRecord>> {
        if self.done {
            return Ok(None);
        }
        let mut head = [0u8; REC_HEADER_BYTES];
        let mut read = 0usize;
        while read < REC_HEADER_BYTES {
            let n = self
                .src
                .read(&mut head[read..])
                .map_err(|e| Error(format!("reading {}: {e}", self.path.display())))?;
            if n == 0 {
                break;
            }
            read += n;
        }
        if read == 0 {
            self.done = true;
            self.check_totals()?;
            return Ok(None);
        }
        if read < REC_HEADER_BYTES {
            // A partial header is a truncated file, never a legitimate end.
            return err(format!(
                "{} ends {read} byte(s) into a {REC_HEADER_BYTES}-byte record header",
                self.path.display()
            ));
        }
        let h = RecHeader::parse(&head)?;
        if h.tile_id < self.lo || h.tile_id >= self.hi {
            return err(format!(
                "{} holds tile id {} but covers {}..{}",
                self.path.display(),
                h.tile_id,
                self.lo,
                self.hi
            ));
        }
        let payload = h.geom_len as usize + h.props_len as usize;
        self.buf.clear();
        self.buf.resize(payload, 0);
        self.src
            .read_exact(&mut self.buf)
            .map_err(|e| Error(format!("reading {}'s record payload: {e}", self.path.display())))?;
        let geom = decode_int_geometry(h.kind, &self.buf[..h.geom_len as usize])?;
        let props = decode_props(&self.buf[h.geom_len as usize..])?;

        self.seen_records += 1;
        self.seen_bytes += h.rec_len as u64;
        Ok(Some(SpillRecord {
            tile_id: h.tile_id,
            seq: h.seq,
            extent: h.extent,
            geom,
            props,
        }))
    }

    fn check_totals(&self) -> Result<()> {
        if self.seen_records != self.expect_records || self.seen_bytes != self.expect_bytes {
            return err(format!(
                "{} read back {} record(s)/{} byte(s) but the writer tallied {}/{}",
                self.path.display(),
                self.seen_records,
                self.seen_bytes,
                self.expect_records,
                self.expect_bytes
            ));
        }
        Ok(())
    }
}

// --- the normalized file -----------------------------------------------------

/// What one pass over the geojsonseq learned, beyond the records themselves.
///
/// The streaming producer's header comes from here rather than from a second pass:
/// [`crate::pmtiles::StreamBuilder`]'s bounds fields are serialised last, so they can be
/// a streaming fold assigned any time before `finish`.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct NormalizedSummary {
    pub count: u64,
    pub bounds: Option<Rect>,
    /// The FIRST feature's kind, which is the rule `dominant_geom_type` already applies:
    /// a layer is styled as one thing, so a mixed layer is a mistake upstream and should
    /// show as wrong rendering rather than hide as a silently split layer.
    pub geom_kind: Option<GeomKind>,
    pub skipped: u64,
    /// Byte offset of every `NORM_CHUNK_FEATURES`-th record, plus a final sentinel
    /// holding the file's total length — so chunk `i` spans `chunks[i]..chunks[i + 1]`
    /// and there are `chunks.len() - 1` of them.
    ///
    /// Built by the same single pass that writes the records, because the writer is the
    /// only place that already knows each record's length. Empty for an empty file.
    pub chunks: Vec<u64>,
}

impl NormalizedSummary {
    /// How many chunks the file has, for [`NormalizedChunks::read_into`].
    pub fn chunk_count(&self) -> usize {
        self.chunks.len().saturating_sub(1)
    }
}

/// Writes the geojsonseq once into a compact binary every zoom can re-read.
///
/// ```text
/// 0..4    u32 rec_len       whole record, this field included
/// 4..8    u32 geom_len
/// 8..12   u32 props_len
/// 12..13  u8  geom_kind
/// 13..16  reserved, zero
/// 16..    geometry (lon/lat e7 `i32` pairs), then properties
/// ```
///
/// Eight bytes a vertex rather than sixteen: see [`encode_geometry`] for why that is
/// lossless for OSM coordinates and what it costs a coastline.
pub struct NormalizedWriter {
    out: BufWriter<File>,
    path: PathBuf,
    summary: NormalizedSummary,
    rec: Vec<u8>,
    /// Bytes written so far, which is the next record's offset.
    at: u64,
}

impl NormalizedWriter {
    pub fn create(path: impl Into<PathBuf>) -> Result<NormalizedWriter> {
        let path = path.into();
        if let Some(dir) = path.parent() {
            if !dir.as_os_str().is_empty() {
                std::fs::create_dir_all(dir)
                    .map_err(|e| Error(format!("cannot create {}: {e}", dir.display())))?;
            }
        }
        let f = File::create(&path)
            .map_err(|e| Error(format!("cannot create {}: {e}", path.display())))?;
        Ok(NormalizedWriter {
            out: BufWriter::with_capacity(1 << 20, f),
            path,
            summary: NormalizedSummary::default(),
            rec: Vec::new(),
            at: 0,
        })
    }

    /// A line the caller could not use. Counted here so the summary is the one place
    /// the whole pass is described.
    pub fn skip(&mut self) {
        self.summary.skipped += 1;
    }

    pub fn push(&mut self, geometry: &Geometry, props: &[(String, Value)]) -> Result<()> {
        self.rec.clear();
        self.rec.resize(NORM_HEADER_BYTES, 0);
        encode_geometry(geometry, &mut self.rec)?;
        let geom_len = count(self.rec.len() - NORM_HEADER_BYTES)?;
        encode_props(props, &mut self.rec)?;
        let props_len = count(self.rec.len() - NORM_HEADER_BYTES - geom_len as usize)?;
        let rec_len = count(self.rec.len())?;
        self.rec[0..4].copy_from_slice(&rec_len.to_le_bytes());
        self.rec[4..8].copy_from_slice(&geom_len.to_le_bytes());
        self.rec[8..12].copy_from_slice(&props_len.to_le_bytes());
        self.rec[12] = GeomKind::of(geometry).tag();
        // Index before writing, so the offset recorded is this record's own start.
        if self.summary.count.is_multiple_of(NORM_CHUNK_FEATURES) {
            self.summary.chunks.push(self.at);
        }
        self.out
            .write_all(&self.rec)
            .map_err(|e| Error(format!("writing {}: {e}", self.path.display())))?;
        self.at += self.rec.len() as u64;

        if self.summary.geom_kind.is_none() {
            self.summary.geom_kind = Some(GeomKind::of(geometry));
        }
        self.summary.bounds = crate::pyramid::fold_bounds(self.summary.bounds, geometry);
        self.summary.count += 1;
        Ok(())
    }

    /// Flush, and hand back what the pass learned.
    pub fn finish(mut self) -> Result<NormalizedSummary> {
        self.out
            .flush()
            .map_err(|e| Error(format!("flushing {}: {e}", self.path.display())))?;
        // Close the last chunk. Without this the final partial chunk has no end, and
        // `chunk_count` would also over-report by one.
        if !self.summary.chunks.is_empty() {
            self.summary.chunks.push(self.at);
        }
        Ok(self.summary)
    }
}

/// One normalized feature.
#[derive(Debug, Clone, PartialEq)]
pub struct NormalizedFeature {
    pub geometry: Geometry,
    pub props: Vec<(String, Value)>,
}

/// Validate a normalized record's fixed header, returning its payload shape.
///
/// Shared by the sequential reader and the chunked one, so the two cannot diverge on
/// what they accept: a record one of them rejects must not be one the other decodes.
fn norm_header(head: &[u8; NORM_HEADER_BYTES]) -> Result<(usize, usize, GeomKind)> {
    if head[13..NORM_HEADER_BYTES].iter().any(|v| *v != 0) {
        return err("normalized record has a nonzero reserved tail");
    }
    let u32_at = |o: usize| u32::from_le_bytes(head[o..o + 4].try_into().expect("4 bytes"));
    let rec_len = u32_at(0) as u64;
    let geom_len = u32_at(4) as usize;
    let props_len = u32_at(8) as usize;
    let want = NORM_HEADER_BYTES as u64 + geom_len as u64 + props_len as u64;
    if rec_len != want {
        return err(format!(
            "normalized record claims {rec_len} byte(s) but its header adds up to {want}"
        ));
    }
    if want > MAX_RECORD_BYTES {
        return err(format!(
            "normalized record is {want} byte(s), which is corruption"
        ));
    }
    Ok((geom_len, props_len, GeomKind::from_tag(head[12])?))
}

/// Decode a validated record's payload, `geom_len` bytes of geometry then properties.
fn norm_payload(kind: GeomKind, geom_len: usize, payload: &[u8]) -> Result<NormalizedFeature> {
    Ok(NormalizedFeature {
        geometry: decode_geometry(kind, &payload[..geom_len])?,
        props: decode_props(&payload[geom_len..])?,
    })
}

/// Sequential reader over the normalized file, rewindable because every zoom re-reads
/// it from the front.
pub struct NormalizedReader {
    src: BufReader<File>,
    path: PathBuf,
    buf: Vec<u8>,
}

impl NormalizedReader {
    pub fn open(path: impl Into<PathBuf>) -> Result<NormalizedReader> {
        let path = path.into();
        let f = File::open(&path)
            .map_err(|e| Error(format!("cannot read {}: {e}", path.display())))?;
        Ok(NormalizedReader {
            src: BufReader::with_capacity(1 << 20, f),
            path,
            buf: Vec::new(),
        })
    }

    pub fn rewind(&mut self) -> Result<()> {
        use std::io::Seek;
        self.src
            .rewind()
            .map_err(|e| Error(format!("rewinding {}: {e}", self.path.display())))?;
        Ok(())
    }

    #[allow(clippy::should_implement_trait)]
    pub fn next(&mut self) -> Result<Option<NormalizedFeature>> {
        let mut head = [0u8; NORM_HEADER_BYTES];
        let mut read = 0usize;
        while read < NORM_HEADER_BYTES {
            let n = self
                .src
                .read(&mut head[read..])
                .map_err(|e| Error(format!("reading {}: {e}", self.path.display())))?;
            if n == 0 {
                break;
            }
            read += n;
        }
        if read == 0 {
            return Ok(None);
        }
        if read < NORM_HEADER_BYTES {
            return err(format!(
                "{} ends {read} byte(s) into a {NORM_HEADER_BYTES}-byte record header",
                self.path.display()
            ));
        }
        let (geom_len, props_len, kind) = norm_header(&head)?;
        self.buf.clear();
        self.buf.resize(geom_len + props_len, 0);
        self.src.read_exact(&mut self.buf).map_err(|e| {
            Error(format!(
                "reading {}'s record payload: {e}",
                self.path.display()
            ))
        })?;
        Ok(Some(norm_payload(kind, geom_len, &self.buf)?))
    }
}

/// Chunked reader over the normalized file, for reading it across a thread pool.
///
/// The sequential [`NormalizedReader`] is one cursor, so the decode of every feature —
/// which allocates a `Geometry` and a `Vec` of properties, six times over a z11..z16
/// build — happens on whichever single thread owns it, while the pool waits. This reads
/// a whole chunk's byte range positionally instead, so the decode happens on the worker
/// that will bucket those features.
///
/// Takes `&self` throughout: see [`crate::pmtiles::read_exact_at`]. One handle serves
/// every thread.
pub struct NormalizedChunks {
    file: File,
    path: PathBuf,
    /// Chunk boundaries, `chunk_count() + 1` of them. See [`NormalizedSummary::chunks`].
    chunks: Vec<u64>,
}

impl NormalizedChunks {
    /// `chunks` is [`NormalizedSummary::chunks`] for this file.
    pub fn open(path: impl Into<PathBuf>, chunks: Vec<u64>) -> Result<NormalizedChunks> {
        let path = path.into();
        let file = File::open(&path)
            .map_err(|e| Error(format!("cannot read {}: {e}", path.display())))?;
        Ok(NormalizedChunks { file, path, chunks })
    }

    pub fn chunk_count(&self) -> usize {
        self.chunks.len().saturating_sub(1)
    }

    /// Decode chunk `i` into `out`, which is cleared first.
    ///
    /// `scratch` is the caller's per-worker byte buffer, reused across chunks so a pass
    /// allocates once per thread rather than once per chunk.
    ///
    /// The first feature of chunk `i` is input feature `i * NORM_CHUNK_FEATURES`, by
    /// construction of the index — which is what lets the caller reproduce the exact
    /// `seq` the sequential path assigns.
    pub fn read_into(
        &self,
        i: usize,
        scratch: &mut Vec<u8>,
        out: &mut Vec<NormalizedFeature>,
    ) -> Result<()> {
        out.clear();
        let (Some(&start), Some(&end)) = (self.chunks.get(i), self.chunks.get(i + 1)) else {
            return err(format!(
                "normalized chunk {i} is past the {} in {}",
                self.chunk_count(),
                self.path.display()
            ));
        };
        if end < start {
            return err(format!(
                "normalized chunk {i} of {} ends before it starts",
                self.path.display()
            ));
        }
        let len = (end - start) as usize;
        scratch.clear();
        scratch.resize(len, 0);
        crate::pmtiles::read_exact_at(&self.file, scratch, start).map_err(|e| {
            Error(format!(
                "reading normalized chunk {i} of {}: {e}",
                self.path.display()
            ))
        })?;
        let mut at = 0usize;
        while at < len {
            if len - at < NORM_HEADER_BYTES {
                return err(format!(
                    "normalized chunk {i} of {} ends {} byte(s) into a \
                     {NORM_HEADER_BYTES}-byte record header",
                    self.path.display(),
                    len - at
                ));
            }
            let head: &[u8; NORM_HEADER_BYTES] = scratch[at..at + NORM_HEADER_BYTES]
                .try_into()
                .expect("a header's worth of bytes");
            let (geom_len, props_len, kind) = norm_header(head)?;
            let body = at + NORM_HEADER_BYTES;
            let rec_end = body + geom_len + props_len;
            if rec_end > len {
                return err(format!(
                    "normalized chunk {i} of {} holds a record running {} byte(s) past its end",
                    self.path.display(),
                    rec_end - len
                ));
            }
            out.push(norm_payload(kind, geom_len, &scratch[body..rec_end])?);
            at = rec_end;
        }
        Ok(())
    }
}

/// The normalized file's lifetime, so a run that dies mid-planet cannot strand it.
///
/// A guard rather than `impl Drop` on the reader, because the reader is handed around
/// and the file has to outlive every one of them.
pub struct NormalizedFile(PathBuf);

impl NormalizedFile {
    pub fn new(path: impl Into<PathBuf>) -> NormalizedFile {
        NormalizedFile(path.into())
    }

    pub fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for NormalizedFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!(
            "tb_spill_{}_{name}_{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn every_value() -> Vec<(String, Value)> {
        vec![
            ("s".to_string(), Value::String("héllo, wörld".into())),
            ("f".to_string(), Value::Float(1.5)),
            ("d".to_string(), Value::Double(-2.25)),
            ("i".to_string(), Value::Int(-7)),
            ("u".to_string(), Value::Uint(9)),
            ("si".to_string(), Value::SInt(-11)),
            ("bt".to_string(), Value::Bool(true)),
            ("bf".to_string(), Value::Bool(false)),
            ("empty".to_string(), Value::String(String::new())),
        ]
    }

    fn every_int_geometry() -> Vec<IntGeometry> {
        vec![
            IntGeometry::Points(vec![]),
            IntGeometry::Points(vec![(0, 0), (-5, 4096), (i32::MIN, i32::MAX)]),
            IntGeometry::Lines(vec![]),
            IntGeometry::Lines(vec![vec![(1, 2), (3, 4)], vec![], vec![(9, -9)]]),
            IntGeometry::Polygons(vec![]),
            IntGeometry::Polygons(vec![
                // Exterior plus a hole, which is the shape a real admin polygon has.
                vec![
                    vec![(0, 0), (10, 0), (10, 10), (0, 10), (0, 0)],
                    vec![(2, 2), (8, 2), (8, 8), (2, 8), (2, 2)],
                ],
                vec![vec![(20, 20), (30, 20), (30, 30), (20, 20)]],
            ]),
        ]
    }

    /// Every geometry variant and every `mvt::Value` must survive the round trip. A
    /// value type that spilled wrongly would reach the encoder as a different property
    /// and nothing between here and the rendered tile would notice.
    #[test]
    fn a_record_round_trips_for_every_geometry_and_every_value() {
        let dir = tmp("roundtrip");
        for (n, geom) in every_int_geometry().into_iter().enumerate() {
            let rec = SpillRecord {
                tile_id: 5 + n as u64,
                seq: 42 + n as u64,
                extent: -1234,
                geom,
                props: every_value(),
            };
            let bytes = rec.to_bytes().unwrap();
            assert_eq!(
                bytes.len(),
                u32::from_le_bytes(bytes[0..4].try_into().unwrap()) as usize,
                "rec_len must be the whole record"
            );

            let mut set = BucketSet::new(&dir, &format!("rt{n}"), 0, 64, 1).unwrap();
            set.push(rec.tile_id, &bytes).unwrap();
            set.seal().unwrap();
            assert_eq!(set.load(0).unwrap(), vec![rec], "variant {n}");
        }
    }

    #[test]
    fn a_truncated_record_errors_rather_than_decoding() {
        let dir = tmp("truncated");
        let rec = SpillRecord {
            tile_id: 1,
            seq: 0,
            extent: 0,
            geom: IntGeometry::Lines(vec![vec![(1, 1), (2, 2), (3, 3)]]),
            props: every_value(),
        };
        let bytes = rec.to_bytes().unwrap();

        let mut set = BucketSet::new(&dir, "t", 0, 64, 1).unwrap();
        set.push(1, &bytes).unwrap();
        set.seal().unwrap();
        let path = dir.join("t.00000.bucket");
        assert!(set.load(0).is_ok(), "the intact fixture must read");

        // Cut mid-payload: the header promises more than the file holds.
        std::fs::write(&path, &bytes[..bytes.len() - 4]).unwrap();
        let mut r = set.reader(0).unwrap().unwrap();
        assert!(r.next().is_err(), "a short payload must error");

        // Cut mid-header, which is a truncated file rather than a legitimate end.
        std::fs::write(&path, &bytes[..REC_HEADER_BYTES - 1]).unwrap();
        let mut r = set.reader(0).unwrap().unwrap();
        assert!(r.next().is_err(), "a short header must error");

        // A whole record short: the reader reaches the end and the tallies disagree.
        std::fs::write(&path, []).unwrap();
        let mut r = set.reader(0).unwrap().unwrap();
        assert!(
            r.next().is_err(),
            "a bucket missing a record must fail the count check"
        );
    }

    #[test]
    fn a_wrong_length_or_a_dirty_reserved_tail_errors() {
        let rec = SpillRecord {
            tile_id: 1,
            seq: 0,
            extent: 0,
            geom: IntGeometry::Points(vec![(1, 1)]),
            props: vec![],
        };
        let good = rec.to_bytes().unwrap();
        let mut head: [u8; REC_HEADER_BYTES] = good[..REC_HEADER_BYTES].try_into().unwrap();
        assert!(RecHeader::parse(&head).is_ok(), "the fixture must be valid");

        let mut bad = head;
        bad[0] = bad[0].wrapping_add(1);
        assert!(
            RecHeader::parse(&bad).is_err(),
            "rec_len must agree with geom_len + props_len"
        );

        head[REC_HEADER_BYTES - 1] = 1;
        assert!(
            RecHeader::parse(&head).is_err(),
            "a nonzero reserved tail must be refused, not guessed at"
        );
    }

    #[test]
    fn an_unknown_geometry_kind_or_value_type_errors() {
        assert!(GeomKind::from_tag(3).is_err());
        // Value tag 7 does not exist.
        let mut b = Vec::new();
        put_u32(&mut b, 1);
        put_u32(&mut b, 1);
        b.push(b'k');
        b.push(7);
        assert!(decode_props(&b).is_err(), "an unknown value type must error");
    }

    /// Every record must land in the bucket its `tile_id` names, and the three
    /// independent tallies -- push time, read time, and the file length -- must agree.
    #[test]
    fn every_record_lands_in_its_own_bucket_and_the_books_balance() {
        let dir = tmp("buckets");
        // z4: ids 21..85, 64 of them, into 16 buckets of 4.
        let (lo, hi) = (21u64, 85u64);
        let mut set = BucketSet::new(&dir, "z4", lo, hi, 16).unwrap();
        assert_eq!(set.len(), 16);
        assert_eq!(set.span(), 4);

        let mut expected: Vec<Vec<u64>> = vec![Vec::new(); 16];
        for (n, id) in (lo..hi).enumerate() {
            // Skewed on purpose: some ids get several records, some none at all.
            for k in 0..(n % 3) {
                let rec = SpillRecord {
                    tile_id: id,
                    seq: (n * 10 + k) as u64,
                    extent: n as i64,
                    geom: IntGeometry::Points(vec![(n as i32, k as i32)]),
                    props: vec![("n".to_string(), Value::Uint(n as u64))],
                };
                set.push(id, &rec.to_bytes().unwrap()).unwrap();
                expected[((id - lo) / 4) as usize].push(id);
            }
        }
        set.seal().unwrap();

        let mut total = 0u64;
        for (i, want) in expected.iter().enumerate() {
            let recs = set.load(i).unwrap();
            let ids: Vec<u64> = recs.iter().map(|r| r.tile_id).collect();
            assert_eq!(&ids, want, "bucket {i}");
            let (blo, bhi) = set.range_of(i);
            for r in &recs {
                assert!(r.tile_id >= blo && r.tile_id < bhi, "bucket {i} range");
            }
            total += recs.len() as u64;
        }
        assert_eq!(total, set.total_records());
        assert!(total > 0, "the fixture must actually push something");
    }

    #[test]
    fn a_tile_id_outside_the_range_is_refused() {
        let dir = tmp("range");
        let mut set = BucketSet::new(&dir, "r", 21, 85, 16).unwrap();
        let rec = SpillRecord {
            tile_id: 20,
            seq: 0,
            extent: 0,
            geom: IntGeometry::Points(vec![(0, 0)]),
            props: vec![],
        };
        assert!(set.push(20, &rec.to_bytes().unwrap()).is_err(), "below the range");
        assert!(set.push(85, &rec.to_bytes().unwrap()).is_err(), "above the range");
    }

    /// A bucket claiming a record it does not cover is the failure a bad partition would
    /// produce, so the reader refuses it even though the writer could not have made it.
    #[test]
    fn a_reader_refuses_a_record_from_the_wrong_range() {
        let dir = tmp("wrongrange");
        let mut set = BucketSet::new(&dir, "w", 0, 64, 1).unwrap();
        let rec = SpillRecord {
            tile_id: 5,
            seq: 0,
            extent: 0,
            geom: IntGeometry::Points(vec![(0, 0)]),
            props: vec![],
        };
        set.push(5, &rec.to_bytes().unwrap()).unwrap();
        set.seal().unwrap();

        // Rewrite the same bucket with an out-of-range id, keeping the byte count so
        // only the range check can catch it.
        let mut moved = rec.clone();
        moved.tile_id = 999;
        let bytes = moved.to_bytes().unwrap();
        assert_eq!(bytes.len(), rec.to_bytes().unwrap().len());
        std::fs::write(dir.join("w.00000.bucket"), &bytes).unwrap();
        let mut r = set.reader(0).unwrap().unwrap();
        assert!(r.next().is_err(), "an out-of-range tile id must error");
    }

    /// Draining buckets in ascending index, each internally sorted, must give globally
    /// ascending `tile_id` -- across two levels of re-partition, which is the expected
    /// path for a dense metro bucket rather than an exotic one.
    #[test]
    fn buckets_drain_in_ascending_tile_id_across_two_recursion_levels() {
        let dir = tmp("recursion");
        let (lo, hi) = (21u64, 85u64);
        let mut parent = BucketSet::new(&dir, "p", lo, hi, 4).unwrap();
        assert_eq!(parent.span(), 16);

        // Pushed in a deliberately scrambled order: the spill has no ordering
        // requirement, which is what would make a parallel bucket pass safe later.
        let order: Vec<u64> = (lo..hi).rev().collect();
        for id in &order {
            let rec = SpillRecord {
                tile_id: *id,
                seq: *id,
                extent: 0,
                geom: IntGeometry::Points(vec![(0, 0)]),
                props: vec![],
            };
            parent.push(*id, &rec.to_bytes().unwrap()).unwrap();
        }
        parent.seal().unwrap();

        let mut drained: Vec<u64> = Vec::new();
        for i in 0..parent.len() {
            let (blo, bhi) = parent.range_of(i);
            // Re-partition every parent bucket, then re-partition each child again.
            let mut child = BucketSet::new(&dir, &format!("c{i}"), blo, bhi, 4).unwrap();
            let mut recs = parent.load(i).unwrap();
            recs.sort_by_key(|r| r.tile_id);
            for r in &recs {
                child.push(r.tile_id, &r.to_bytes().unwrap()).unwrap();
            }
            child.seal().unwrap();
            for j in 0..child.len() {
                let (clo, chi) = child.range_of(j);
                let mut grand =
                    BucketSet::new(&dir, &format!("g{i}_{j}"), clo, chi, 4).unwrap();
                let mut crecs = child.load(j).unwrap();
                crecs.sort_by_key(|r| r.tile_id);
                for r in &crecs {
                    grand.push(r.tile_id, &r.to_bytes().unwrap()).unwrap();
                }
                grand.seal().unwrap();
                for k in 0..grand.len() {
                    let mut g = grand.load(k).unwrap();
                    g.sort_by_key(|r| r.tile_id);
                    drained.extend(g.iter().map(|r| r.tile_id));
                }
            }
        }
        let want: Vec<u64> = (lo..hi).collect();
        assert_eq!(drained, want, "the drain must be globally ascending");
    }

    #[test]
    fn a_bucket_count_that_is_not_a_power_of_four_is_refused() {
        let dir = tmp("pow4");
        for bad in [0usize, 2, 8, 32, 100] {
            assert!(
                BucketSet::new(&dir, "b", 0, 64, bad).is_err(),
                "{bad} buckets must be refused"
            );
        }
        for good in [1usize, 4, 16, 64, 256] {
            assert!(BucketSet::new(&dir, "b", 0, 1024, good).is_ok(), "{good} buckets");
        }
    }

    /// A shallow zoom has fewer tiles than the requested bucket count. Dividing the
    /// count down by four keeps a bucket one quadtree cell; clamping it to the tile
    /// count would not.
    #[test]
    fn a_shallow_range_reduces_the_bucket_count_by_fours() {
        let dir = tmp("shallow");
        // z1 has 4 tiles; asking for 256 buckets gets 4.
        let set = BucketSet::new(&dir, "z1", 1, 5, 256).unwrap();
        assert_eq!(set.len(), 4);
        assert_eq!(set.span(), 1);
        // z0 has one.
        let set = BucketSet::new(&dir, "z0", 0, 1, 256).unwrap();
        assert_eq!(set.len(), 1);
        assert_eq!(set.span(), 1);
    }

    #[test]
    fn a_bucket_set_removes_its_files_when_it_is_dropped() {
        let dir = tmp("cleanup");
        let path;
        {
            let mut set = BucketSet::new(&dir, "gone", 0, 64, 1).unwrap();
            let rec = SpillRecord {
                tile_id: 0,
                seq: 0,
                extent: 0,
                geom: IntGeometry::Points(vec![(0, 0)]),
                props: vec![],
            };
            set.push(0, &rec.to_bytes().unwrap()).unwrap();
            set.seal().unwrap();
            path = dir.join("gone.00000.bucket");
            assert!(path.exists(), "the bucket file must exist while the set does");
        }
        assert!(!path.exists(), "a dropped bucket set must take its files with it");
    }

    /// A bucket that receives no records in a rerun must not inherit the file a killed
    /// previous run left at the same path: `seal` would report bytes nobody pushed, and
    /// with a persistent `--spill-dir` that turns a crash into a baffling hard failure.
    #[test]
    fn a_stale_bucket_file_does_not_survive_a_new_set() {
        let dir = tmp("stale");
        let stale = dir.join("s.00001.bucket");
        std::fs::write(&stale, b"left over by a killed run").unwrap();

        let mut set = BucketSet::new(&dir, "s", 0, 64, 4).unwrap();
        assert!(!stale.exists(), "the stale file must be cleared at construction");
        let rec = SpillRecord {
            tile_id: 0,
            seq: 0,
            extent: 0,
            geom: IntGeometry::Points(vec![(0, 0)]),
            props: vec![],
        };
        set.push(0, &rec.to_bytes().unwrap()).unwrap();
        set.seal().expect("the books must balance despite the stale file");
        assert!(set.load(1).unwrap().is_empty(), "bucket 1 is empty, not stale");
    }

    #[test]
    fn a_bucket_set_must_be_sealed_before_it_is_read() {
        let dir = tmp("sealed");
        let mut set = BucketSet::new(&dir, "s", 0, 64, 1).unwrap();
        assert!(set.reader(0).is_err(), "reading an unsealed set must error");
        set.seal().unwrap();
        assert!(set.reader(0).unwrap().is_none(), "an empty bucket has no reader");
        let rec = SpillRecord {
            tile_id: 0,
            seq: 0,
            extent: 0,
            geom: IntGeometry::Points(vec![(0, 0)]),
            props: vec![],
        };
        assert!(
            set.push(0, &rec.to_bytes().unwrap()).is_err(),
            "pushing to a sealed set must error"
        );
    }

    // --- the normalized file -------------------------------------------------

    /// Snap a geometry to the e7 grid, which is what [`encode_geometry`] stores.
    ///
    /// A plain decimal literal is only NEARLY on that grid: `-122.42` and
    /// `-1_224_200_000 as f64 * 1e-7` are different `f64`s, an ulp apart -- 1.4e-14
    /// degrees, seven orders of magnitude below the 1.1 cm the quantisation itself costs.
    /// So a decoded feature is compared against its snapped input rather than against the
    /// literal it was written from. The assertion stays exact and total -- every vertex,
    /// every count, every property -- and states the contract the codec actually has.
    fn snapped(g: &Geometry) -> Geometry {
        let pt = |&(x, y): &Pt| {
            (
                crate::pyramid::e7(x) as f64 * 1e-7,
                crate::pyramid::e7(y) as f64 * 1e-7,
            )
        };
        let ring = |r: &Vec<Pt>| r.iter().map(pt).collect::<Vec<Pt>>();
        match g {
            Geometry::Points(p) => Geometry::Points(ring(p)),
            Geometry::Lines(l) => Geometry::Lines(l.iter().map(ring).collect()),
            Geometry::Polygons(p) => {
                Geometry::Polygons(p.iter().map(|rs| rs.iter().map(ring).collect()).collect())
            }
        }
    }

    fn snapped_all(features: &[NormalizedFeature]) -> Vec<NormalizedFeature> {
        features
            .iter()
            .map(|f| NormalizedFeature {
                geometry: snapped(&f.geometry),
                props: f.props.clone(),
            })
            .collect()
    }

    /// **The guarantee a byte-identical archive rests on.** Every coordinate `osm_ingest`
    /// yields is an `i32` e7 multiplied by `1e-7`, and such a value must come back as the
    /// same `f64` it went in as -- not merely close. If it did not, halving the spill
    /// would have moved every OSM vertex in the build.
    ///
    /// Spanning the whole `i32` range, because the argument is about the two
    /// multiplications' relative error staying under half an e7 unit and that is weakest
    /// at the largest magnitude.
    #[test]
    fn an_e7_grid_coordinate_survives_the_round_trip_bit_exactly() {
        let dir = tmp("e7exact");
        let path = dir.join("f.bin");
        let grid: Vec<i32> = vec![
            0,
            1,
            -1,
            1_800_000_000,
            -1_800_000_000,
            900_000_000,
            -900_000_000,
            1_224_200_000,
            -1_224_200_000,
            377_700_000,
            i32::MAX,
            i32::MIN + 1,
        ];
        let points: Vec<Pt> = grid
            .iter()
            .map(|&n| (n as f64 * 1e-7, -n as f64 * 1e-7))
            .collect();
        let feature = NormalizedFeature {
            geometry: Geometry::Points(points.clone()),
            props: every_value(),
        };

        let mut w = NormalizedWriter::create(&path).unwrap();
        w.push(&feature.geometry, &feature.props).unwrap();
        w.finish().unwrap();

        let back = NormalizedReader::open(&path)
            .unwrap()
            .next()
            .unwrap()
            .expect("a feature");
        assert_eq!(back, feature, "an e7-grid coordinate must not move at all");

        // Eight bytes a vertex, not sixteen. The whole point of the encoding.
        let bytes = std::fs::metadata(&path).unwrap().len() as usize;
        let props = {
            let mut buf = Vec::new();
            encode_props(&feature.props, &mut buf).unwrap();
            buf.len()
        };
        assert_eq!(
            bytes,
            NORM_HEADER_BYTES + 4 + points.len() * 8 + props,
            "a vertex must cost eight bytes"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_normalized_file_round_trips_and_folds_its_summary() {
        let dir = tmp("normalized");
        let path = dir.join("features.bin");
        let features = vec![
            NormalizedFeature {
                geometry: Geometry::Lines(vec![vec![(-122.42, 37.77), (-122.40, 37.79)]]),
                props: every_value(),
            },
            NormalizedFeature {
                geometry: Geometry::Lines(vec![vec![(-74.01, 40.71), (-73.99, 40.73)]]),
                props: vec![],
            },
            NormalizedFeature {
                geometry: Geometry::Points(vec![(2.35, 48.85)]),
                props: vec![("x".to_string(), Value::Bool(true))],
            },
        ];

        let mut w = NormalizedWriter::create(&path).unwrap();
        for f in &features {
            w.push(&f.geometry, &f.props).unwrap();
        }
        w.skip();
        w.skip();
        let summary = w.finish().unwrap();

        assert_eq!(summary.count, 3);
        assert_eq!(summary.skipped, 2);
        // The FIRST feature's kind, even though a point follows.
        assert_eq!(summary.geom_kind, Some(GeomKind::Lines));
        let b = summary.bounds.expect("bounds");
        assert_eq!((b.min_x, b.max_x), (-122.42, 2.35));
        assert_eq!((b.min_y, b.max_y), (37.77, 48.85));

        let mut r = NormalizedReader::open(&path).unwrap();
        let mut back = Vec::new();
        while let Some(f) = r.next().unwrap() {
            back.push(f);
        }
        let want = snapped_all(&features);
        assert_eq!(back, want);

        // Every zoom re-reads it from the front.
        r.rewind().unwrap();
        assert_eq!(r.next().unwrap().as_ref(), Some(&want[0]));

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The chunked reader must yield exactly the sequential reader's features in exactly
    /// its order, and its chunk boundaries must land on multiples of
    /// `NORM_CHUNK_FEATURES` — that alignment is what the bucket pass relies on to
    /// reconstruct `seq` without reading the whole file.
    #[test]
    fn the_chunked_reader_yields_what_the_sequential_one_does() {
        let dir = tmp("normchunks");
        let path = dir.join("features.bin");

        // Deliberately not a multiple of the stride, so the last chunk is partial, and
        // varied enough that a record-length mistake cannot cancel out.
        let n = 2 * NORM_CHUNK_FEATURES as usize + 7;
        let features: Vec<NormalizedFeature> = (0..n)
            .map(|i| {
                let f = i as f64;
                if i % 3 == 0 {
                    NormalizedFeature {
                        geometry: Geometry::Points(vec![(f * 0.01, -f * 0.02)]),
                        props: vec![("i".to_string(), Value::Uint(i as u64))],
                    }
                } else if i % 3 == 1 {
                    NormalizedFeature {
                        geometry: Geometry::Lines(vec![vec![(f * 0.01, 1.0), (f * 0.01, 2.0)]]),
                        props: every_value(),
                    }
                } else {
                    NormalizedFeature {
                        geometry: Geometry::Lines(vec![vec![(0.0, f * 0.01), (1.0, f * 0.01)]]),
                        props: vec![],
                    }
                }
            })
            .collect();

        let mut w = NormalizedWriter::create(&path).unwrap();
        for f in &features {
            w.push(&f.geometry, &f.props).unwrap();
        }
        let summary = w.finish().unwrap();
        assert_eq!(summary.count, n as u64);
        assert_eq!(summary.chunk_count(), 3, "two full chunks and a partial one");
        assert_eq!(
            *summary.chunks.last().unwrap(),
            std::fs::metadata(&path).unwrap().len(),
            "the sentinel must be the file's length"
        );

        let chunks = NormalizedChunks::open(&path, summary.chunks.clone()).unwrap();
        let mut scratch = Vec::new();
        let mut out = Vec::new();
        let mut back = Vec::new();
        for i in 0..chunks.chunk_count() {
            chunks.read_into(i, &mut scratch, &mut out).unwrap();
            // The alignment the `seq` reconstruction depends on.
            assert_eq!(
                back.len() as u64,
                i as u64 * NORM_CHUNK_FEATURES,
                "chunk {i} must start at feature {}",
                i as u64 * NORM_CHUNK_FEATURES
            );
            back.extend(out.iter().cloned());
        }
        assert_eq!(back, snapped_all(&features), "the chunked read must match the input");

        // And the sequential reader, so neither can drift from the other.
        let mut r = NormalizedReader::open(&path).unwrap();
        let mut seq = Vec::new();
        while let Some(f) = r.next().unwrap() {
            seq.push(f);
        }
        assert_eq!(back, seq, "the two readers must agree");

        assert!(
            chunks
                .read_into(chunks.chunk_count(), &mut scratch, &mut out)
                .is_err(),
            "a chunk past the end must error rather than read nothing"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A corrupt chunk index must be reported, not silently mis-decoded: the offsets are
    /// the one part of the format the records themselves cannot validate.
    #[test]
    fn a_chunk_index_pointing_into_a_record_errors() {
        let dir = tmp("normchunkbad");
        let path = dir.join("f.bin");
        let mut w = NormalizedWriter::create(&path).unwrap();
        for i in 0..NORM_CHUNK_FEATURES + 3 {
            w.push(
                &Geometry::Lines(vec![vec![(i as f64, 1.0), (i as f64, 2.0)]]),
                &every_value(),
            )
            .unwrap();
        }
        let summary = w.finish().unwrap();

        let mut scratch = Vec::new();
        let mut out = Vec::new();

        // Nudge a boundary into the middle of a record.
        let mut bad = summary.chunks.clone();
        bad[1] += 3;
        let chunks = NormalizedChunks::open(&path, bad).unwrap();
        assert!(
            chunks.read_into(0, &mut scratch, &mut out).is_err()
                || chunks.read_into(1, &mut scratch, &mut out).is_err(),
            "a misaligned boundary must error"
        );

        // A boundary that runs backwards.
        let mut backwards = summary.chunks.clone();
        backwards[1] = backwards[0];
        backwards[0] = *summary.chunks.last().unwrap();
        let chunks = NormalizedChunks::open(&path, backwards).unwrap();
        assert!(
            chunks.read_into(0, &mut scratch, &mut out).is_err(),
            "a backwards range must error"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The summary's bounds must be exactly what the in-memory path computes, or the
    /// two producers write different header bytes for the same input.
    #[test]
    fn the_summary_bounds_match_the_in_memory_fold() {
        let dir = tmp("bounds");
        let path = dir.join("f.bin");
        let geoms = vec![
            Geometry::Lines(vec![vec![(-10.0, -5.0), (3.0, 8.0)]]),
            Geometry::Points(vec![(20.0, -30.0)]),
            // No vertices at all, so it must not move the box.
            Geometry::Lines(vec![]),
        ];
        let mut w = NormalizedWriter::create(&path).unwrap();
        for g in &geoms {
            w.push(g, &[]).unwrap();
        }
        let summary = w.finish().unwrap();

        let want = geoms.iter().fold(None, crate::pyramid::fold_bounds);
        assert_eq!(summary.bounds, want);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_empty_normalized_file_reads_as_no_features() {
        let dir = tmp("empty");
        let path = dir.join("f.bin");
        let summary = NormalizedWriter::create(&path).unwrap().finish().unwrap();
        assert_eq!(summary, NormalizedSummary::default());
        assert!(NormalizedReader::open(&path).unwrap().next().unwrap().is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_truncated_normalized_record_errors() {
        let dir = tmp("normtrunc");
        let path = dir.join("f.bin");
        let mut w = NormalizedWriter::create(&path).unwrap();
        w.push(
            &Geometry::Lines(vec![vec![(1.0, 2.0), (3.0, 4.0)]]),
            &every_value(),
        )
        .unwrap();
        w.finish().unwrap();

        let bytes = std::fs::read(&path).unwrap();
        std::fs::write(&path, &bytes[..bytes.len() - 8]).unwrap();
        assert!(NormalizedReader::open(&path).unwrap().next().is_err(), "short payload");
        std::fs::write(&path, &bytes[..NORM_HEADER_BYTES - 1]).unwrap();
        assert!(NormalizedReader::open(&path).unwrap().next().is_err(), "short header");
        let mut dirty = bytes.clone();
        dirty[NORM_HEADER_BYTES - 1] = 1;
        std::fs::write(&path, &dirty).unwrap();
        assert!(
            NormalizedReader::open(&path).unwrap().next().is_err(),
            "nonzero reserved tail"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_normalized_file_guard_removes_it_on_drop() {
        let dir = tmp("normguard");
        let path = dir.join("f.bin");
        {
            let guard = NormalizedFile::new(&path);
            NormalizedWriter::create(guard.path()).unwrap().finish().unwrap();
            assert!(path.exists());
        }
        assert!(!path.exists(), "the guard must remove it");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
