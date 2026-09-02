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
//! suggestion, and it is why this module needed no format of its own.
//!
//! The one change since made to that format was made *there*, for both its consumers: the geometry
//! is stored as `i32` e7 rather than `f64` degrees, halving the spill. Every coordinate `osm_ingest`
//! produces is already an e7 integer, so this archive is byte-identical across that change; see
//! [`spill::encode_geometry`].
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
    /// alone holds 16.9 M against ~5 M across z0..z12. Without this the reader parses the whole
    /// 2.0 GB spill once per zoom, which measured 55.2 s of a 180 s build back when the spill was
    /// 3.3 GB, all of it on one thread.
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

/// What a spill was built from, so reusing one cannot silently build the wrong archive.
///
/// A store is only valid for the input and layer selection that produced it. Reusing one built from
/// a different `.pbf`, or with `--layers water` when this run wants all seven, would produce an
/// archive that looks fine and is missing most of the world. Cheap to record, impossible to diagnose
/// from the symptom.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Provenance {
    /// Length and modification time of the source `.pbf`. Not a hash: hashing 18 GB to save 18
    /// minutes of stage A would give most of the saving back.
    pub source_len: u64,
    pub source_mtime: u64,
    /// The layer selection, one bit each, in [`crate::schema::Layers`] declaration order.
    pub layers: u8,
    /// Whether a coastline product was folded in, which adds features nothing else would.
    pub coastline: bool,
}

impl Provenance {
    /// Read the source file's identity, or an error naming it.
    pub fn of(source: &Path, layers: crate::schema::Layers, coastline: bool) -> Result<Provenance> {
        let meta = std::fs::metadata(source)
            .map_err(|e| osm_ingest::proto::Error(format!("cannot stat {}: {e}", source.display())))?;
        let mtime = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs())
            .unwrap_or(0);
        Ok(Provenance {
            source_len: meta.len(),
            source_mtime: mtime,
            layers: u8::from(layers.earth)
                | u8::from(layers.water) << 1
                | u8::from(layers.buildings) << 2
                | u8::from(layers.roads) << 3
                | u8::from(layers.boundaries) << 4
                | u8::from(layers.landcover) << 5
                | u8::from(layers.landuse) << 6,
            coastline,
        })
    }
}

/// Magic and version of the sidecar index. Bumped whenever the layout below changes, so an index
/// written by an older build is refused rather than misread.
const INDEX_MAGIC: &[u8; 8] = b"MAMASTOR";
const INDEX_VERSION: u32 = 1;

impl Store {
    /// Write the sidecar that lets [`Store::open`] skip stage A.
    ///
    /// Beside the spill, as `<spill>.index`. Everything a `Store` holds that is not the spill itself:
    /// the chunk offsets, the per-chunk shallowest zoom, the count and the bounding box. On a
    /// north-america build that is about 28 MB against a 29 GB spill, and it buys eighteen minutes
    /// per subsequent run.
    ///
    /// `features` is [`crate::extract::Stats::features`], which the build id is derived from. Stored
    /// rather than recomputed so a reused run derives the **same** id as the run that built the
    /// spill -- otherwise reuse would quietly republish under a different id.
    pub fn save_index(&self, provenance: Provenance, features: u64) -> Result<PathBuf> {
        let path = index_path(&self.path);
        let mut out = Vec::with_capacity(16 + self.chunks.len() * 8 + self.chunk_mins.len());
        out.extend_from_slice(INDEX_MAGIC);
        out.extend_from_slice(&INDEX_VERSION.to_le_bytes());
        out.extend_from_slice(&provenance.source_len.to_le_bytes());
        out.extend_from_slice(&provenance.source_mtime.to_le_bytes());
        out.push(provenance.layers);
        out.push(u8::from(provenance.coastline));
        out.extend_from_slice(&features.to_le_bytes());
        out.extend_from_slice(&self.count.to_le_bytes());
        for v in [self.bbox.0, self.bbox.1, self.bbox.2, self.bbox.3] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        out.extend_from_slice(&(self.chunks.len() as u64).to_le_bytes());
        for c in &self.chunks {
            out.extend_from_slice(&c.to_le_bytes());
        }
        out.extend_from_slice(&(self.chunk_mins.len() as u64).to_le_bytes());
        out.extend_from_slice(&self.chunk_mins);
        std::fs::write(&path, &out)
            .map_err(|e| osm_ingest::proto::Error(format!("cannot write {}: {e}", path.display())))?;
        Ok(path)
    }

    /// Reopen a spill written by an earlier run, refusing one that does not match `want`.
    ///
    /// Returns the store and the feature count the build id is derived from. The whole point is
    /// iterating on tiling without paying for stage A again, so the checks are what make that safe
    /// rather than merely fast.
    pub fn open(spill: &Path, want: Provenance) -> Result<(Store, u64)> {
        let path = index_path(spill);
        if !spill.exists() {
            return err(format!("no feature spill at {}", spill.display()));
        }
        let raw = std::fs::read(&path).map_err(|e| {
            osm_ingest::proto::Error(format!(
                "cannot read {} ({e}) -- a store can only be reused if the run that built it wrote \
                 its index",
                path.display(),
            ))
        })?;
        let mut at = 0usize;
        let mut take = |n: usize| -> Result<&[u8]> {
            let end = at + n;
            if end > raw.len() {
                return err(format!("{} is truncated", path.display()));
            }
            let slice = &raw[at..end];
            at = end;
            Ok(slice)
        };
        if take(8)? != INDEX_MAGIC {
            return err(format!("{} is not a store index", path.display()));
        }
        let version = u32::from_le_bytes(take(4)?.try_into().expect("four bytes"));
        if version != INDEX_VERSION {
            return err(format!(
                "{} is version {version}, this build writes {INDEX_VERSION}",
                path.display(),
            ));
        }
        let got = Provenance {
            source_len: u64::from_le_bytes(take(8)?.try_into().expect("eight bytes")),
            source_mtime: u64::from_le_bytes(take(8)?.try_into().expect("eight bytes")),
            layers: take(1)?[0],
            coastline: take(1)?[0] != 0,
        };
        if got != want {
            return err(format!(
                "{} was built from a different input or layer set (source {} bytes at mtime {}, \
                 layers {:#04x}, coastline {}) than this run wants (source {} bytes at mtime {}, \
                 layers {:#04x}, coastline {}) -- delete it or drop --reuse-store",
                path.display(),
                got.source_len,
                got.source_mtime,
                got.layers,
                got.coastline,
                want.source_len,
                want.source_mtime,
                want.layers,
                want.coastline,
            ));
        }
        let features = u64::from_le_bytes(take(8)?.try_into().expect("eight bytes"));
        let count = u64::from_le_bytes(take(8)?.try_into().expect("eight bytes"));
        let mut bbox = [0i32; 4];
        for slot in &mut bbox {
            *slot = i32::from_le_bytes(take(4)?.try_into().expect("four bytes"));
        }
        let chunk_count = u64::from_le_bytes(take(8)?.try_into().expect("eight bytes")) as usize;
        let mut chunks = Vec::with_capacity(chunk_count);
        for _ in 0..chunk_count {
            chunks.push(u64::from_le_bytes(take(8)?.try_into().expect("eight bytes")));
        }
        let min_count = u64::from_le_bytes(take(8)?.try_into().expect("eight bytes")) as usize;
        let chunk_mins = take(min_count)?.to_vec();
        // The same cross-check `WaySink::finish` makes, for the same reason: two indexes that
        // disagree silently drop real features from a handful of tiles at one zoom.
        if chunk_mins.len() != chunks.len().saturating_sub(1) {
            return err(format!(
                "{} describes {} chunk(s) but {} zoom entr(ies)",
                path.display(),
                chunks.len().saturating_sub(1),
                chunk_mins.len(),
            ));
        }
        Ok((
            Store {
                path: spill.to_path_buf(),
                count,
                bbox: (bbox[0], bbox[1], bbox[2], bbox[3]),
                chunks,
                chunk_mins,
            },
            features,
        ))
    }
}

fn index_path(spill: &Path) -> PathBuf {
    let mut p = spill.as_os_str().to_owned();
    p.push(".index");
    PathBuf::from(p)
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
        let wanted: Vec<usize> = self
            .chunk_mins
            .iter()
            .enumerate()
            .filter(|(_, min)| **min <= z)
            .map(|(i, _)| i)
            .collect();
        ZoomReader::spawn(
            NormalizedChunks::open(self.path.clone(), self.chunks.clone())
                .map_err(|e| osm_ingest::proto::Error(e.to_string()))?,
            wanted,
            z,
        )
    }

    pub fn wanted_chunks_for_zoom(&self, z: u8) -> Vec<usize> {
        self.chunk_mins
            .iter()
            .enumerate()
            .filter(|(_, min)| **min <= z)
            .map(|(i, _)| i)
            .collect()
    }

    pub fn wanted_len_for_zoom(&self, z: u8) -> usize {
        self.chunk_mins.iter().filter(|min| **min <= z).count()
    }

    pub fn path(&self) -> &PathBuf {
        &self.path
    }

    pub fn raw_chunks(&self) -> &[u64] {
        &self.chunks
    }

    pub fn chunk_mins_cloned(&self) -> Vec<u8> {
        self.chunk_mins.clone()
    }

    pub fn from_parts(path: PathBuf, chunks: Vec<u64>, chunk_mins: Vec<u8>) -> Self {
        Self { path, chunks, chunk_mins, count: 0, bbox: (0, 0, 0, 0) }
    }

    pub fn reader_for_wanted(&self, wanted: Vec<usize>, z: u8) -> Result<ZoomReader> {
        ZoomReader::spawn(
            NormalizedChunks::open(self.path.clone(), self.chunks.clone())
                .map_err(|e| osm_ingest::proto::Error(e.to_string()))?,
            wanted,
            z,
        )
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

/// Chunks of features as the lanes hand them over, or the error that stopped one.
///
/// [`Feature`] rather than the decoder's own record, because turning one into the other is where
/// the reader thread's remaining time went. It looks like a field lookup and a move, and the field
/// lookup and the move are free; what is not is dropping the `props` the decoder built — a `Vec` and
/// an owned key `String` per feature, freed one at a time on the one thread the whole map phase
/// waits on. Converting in the lane puts that on the lane.
type Decoded = Result<Vec<Feature>>;

/// Threads decoding spill chunks ahead of the tiler.
///
/// Not the pool: see [`ZoomReader::spawn`]. **Four is the safe default, not the right answer**, and
/// there is no right answer available as a constant. The two extracts want opposite things:
///
/// | lanes / tiling workers | us-west | north-america |
/// |---|---|---|
/// | 4 / 64 | **91.9 s** | 945.7 s |
/// | 16 / 64 | 151.9 s | **766.9 s** |
/// | 16 / 48 | 94.4 s | 900.4 s |
///
/// Sixteen lanes is a 65% regression on us-west and a 19% improvement on north-america, and the
/// mechanism is the same in both: a lane competes for a CPU only when it is running. On
/// north-america the reader cannot fill the channel fast enough, so the clipping workers are blocked
/// and the lanes are free; on us-west the workers saturate the machine and the lanes take CPU from
/// the single reader thread they exist to feed. Which regime a build lands in depends on the spill
/// against the page cache, not on anything this constant can see — the middle row of that table is
/// the attempt to correct for it by taking the lanes out of the worker budget, and it makes each
/// case worse than that case's own best.
///
/// So it is a knob, defaulting to the value that cannot hurt. `MAPS_PREFETCH_LANES=16` is what a
/// continent wants. Removing the need for the knob means removing the re-decode it is compensating
/// for: the spill is read once per zoom, which is ~1.06 billion feature decodes to deliver
/// 199.7 M distinct features on north-america, and no lane count makes redundant work cheap.
pub fn prefetch_lanes() -> usize {
    static LANES: std::sync::OnceLock<usize> = std::sync::OnceLock::new();
    *LANES.get_or_init(|| {
        std::env::var("MAPS_PREFETCH_LANES")
            .ok()
            .and_then(|raw| raw.trim().parse::<usize>().ok())
            .filter(|n| *n > 0)
            .unwrap_or(4)
    })
}

/// Consecutive chunks one lane claims at a time.
///
/// **One, because dealing longer runs was tried and measured as nothing.** The reasoning for runs is
/// good and the measurement does not support it: single-chunk round-robin means each of sixteen
/// lanes walks the file in ~9 KB hops sixteen chunks apart, which is the pattern readahead exists to
/// defeat, and the spill was moving at 643 MB/s on hardware that does several gigabytes. Dealing
/// runs of 64 consecutive chunks — ~600 KB, a streaming read — changed north-america tiling from
/// 807.0 s to 813.1 s and the reader from 292.6 s to 298.4 s. Noise, and slightly the wrong way.
///
/// So the reads were never the problem, and the throughput figure was measuring something else: the
/// reader was not waiting on disk, it was busy discarding features (see [`ZoomReader::spawn`]). Kept
/// as a named constant of 1 rather than deleted, because the shape of the partition is worth being
/// explicit about and because the next person to look at a 643 MB/s number will have the same idea.
const PREFETCH_RUN: usize = 1;

/// Chunks one lane may run ahead by.
///
/// Bounds the prefetch at `prefetch_lanes() * (PREFETCH_DEPTH + 1) * 64` features — about 34 K at
/// constants above, which is small against the 512 Ki *vertices* the tiler batches immediately
/// downstream. Depth is here to absorb a slow chunk, not to buffer.
const PREFETCH_DEPTH: usize = 32;

/// Reads the chunks one zoom needs and seeks past the rest.
pub struct ZoomReader {
    /// One receiver per lane. Wanted-chunk `k` is decoded by lane `(k / PREFETCH_RUN) % lanes.len()`,
    /// so walking the lanes a run at a time reproduces file order exactly. Empty when there is
    /// nothing to read.
    lanes: Vec<std::sync::mpsc::Receiver<Decoded>>,
    /// Joined on drop, after the receivers are dropped: a lane blocked on a full channel exits when
    /// its receiver goes, so the order of those two steps is what makes the join finite.
    lanes_running: Vec<std::thread::JoinHandle<()>>,
    /// Chunks taken so far, which is also what decides the lane to take the next one from.
    at: usize,
    total: usize,
    records: Vec<Feature>,
}

impl Drop for ZoomReader {
    fn drop(&mut self) {
        // Receivers first. A lane parked on `send` to a full channel wakes with an error the moment
        // its receiver is gone, and only then is the join below guaranteed to finish -- which
        // matters on the error path, where the tiler abandons a zoom with every lane backed up.
        self.lanes.clear();
        for lane in self.lanes_running.drain(..) {
            let _ = lane.join();
        }
    }
}

impl ZoomReader {
    /// Start a lane per [`prefetch_lanes`] over `wanted`, dealt round-robin.
    ///
    /// # Why dedicated threads and not the pool
    ///
    /// This decode is 62.6 s of a 175 s us-west build, all of it on one thread, and
    /// [`NormalizedChunks::read_into`] is an obvious candidate for the pool: it takes `&self`, reads
    /// its own byte range positionally, and is pure. Decoding 512 chunks at a time across the pool
    /// was tried and made the map phase **62% slower** — 102.4 s to 165.5 s — for byte-identical
    /// output.
    ///
    /// The reason is what this reader is: it feeds the clipping workers through a channel. Handing
    /// the refill to the same pool queues it *behind* the clipping tasks it exists to supply, so the
    /// reader blocks waiting on a pool that is busy with work only the reader can extend. The
    /// overlap between reading and clipping — the entire point of the arrangement — disappears, and
    /// both halves get slower.
    ///
    /// So: threads of their own, outside the pool, which is what [`crate::tiler`] does for the
    /// reader itself and for the same reason.
    ///
    /// # Why the lanes drop features rather than hand them over
    ///
    /// A feature below `z`'s own floor is dropped **here**, and that is not tidiness — it is most of
    /// what this reader costs. The chunk index skips a chunk only when *every* feature in it is too
    /// deep, and a chunk is 64 features in file order with no relationship between their
    /// `min_zoom`s, so at a shallow zoom nearly every chunk survives the index while holding almost
    /// nothing that zoom draws. z5 of north-america reads 590,002 chunks — 37.8 M features — to
    /// produce 156 tiles.
    ///
    /// Left to the consumer, as it was, that is roughly **1.06 billion features across the build**
    /// pulled one `next()` at a time and then discarded. No lane count fixes it, because it is not
    /// the lanes' work: raising lanes from 4 to 16 took the reader 496.8 s to 292.6 s and then
    /// stopped, and dealing sequential runs instead of strided chunks changed nothing at all. The
    /// filter is per-feature and order-preserving, so moving it up here removes exactly the features
    /// the tiler removed, in a place where sixteen threads share the cost.
    ///
    /// # Why the order cannot move
    ///
    /// Lane `j` takes wanted-chunks `j`, `j + n`, `j + 2n`, ... and a lane's own channel is FIFO, so
    /// the `k`th chunk out of lane `k % n` is wanted-chunk `k`. Reading the lanes round-robin is
    /// therefore the wanted list in order, which is the file in order, which is what the archive's
    /// feature ordering rests on. Nothing here depends on which lane finishes first; a lane that
    /// races ahead fills its channel and parks.
    fn spawn(inner: NormalizedChunks, wanted: Vec<usize>, z: u8) -> Result<ZoomReader> {
        let total = wanted.len();
        if total == 0 {
            return Ok(ZoomReader {
                lanes: Vec::new(),
                lanes_running: Vec::new(),
                at: 0,
                total: 0,
                records: Vec::new(),
            });
        }
        // One handle shared rather than one file open per lane: `read_into` is positional and
        // documented to serve every thread from one handle.
        let inner = std::sync::Arc::new(inner);
        // No more lanes than there are runs to deal, or the tail lanes are threads started to do
        // nothing.
        let count = prefetch_lanes().min(total.div_ceil(PREFETCH_RUN));
        let mut lanes = Vec::with_capacity(count);
        let mut lanes_running = Vec::with_capacity(count);
        for lane in 0..count {
            let (send, receive) = std::sync::mpsc::sync_channel::<Decoded>(PREFETCH_DEPTH);
            let inner = std::sync::Arc::clone(&inner);
            // This lane's runs, flattened back into the chunks it will read: sequential within a
            // run, which is the whole point of dealing runs rather than chunks.
            let mine: Vec<usize> = wanted
                .chunks(PREFETCH_RUN)
                .skip(lane)
                .step_by(count)
                .flatten()
                .copied()
                .collect();
            let running = std::thread::Builder::new()
                .name(format!("mamaps-spill-{lane}"))
                .spawn(move || {
                    // One byte buffer and one record buffer for the lane, reused across its
                    // chunks. What is sent is a third `Vec`, allocated per chunk -- one allocation
                    // per 64 features, against the per-feature geometry the decode allocates
                    // anyway.
                    let mut scratch = Vec::new();
                    let mut records = Vec::new();
                    for chunk in mine {
                        let decoded = inner
                            .read_into(chunk, &mut scratch, &mut records)
                            .map_err(|e| osm_ingest::proto::Error(e.to_string()))
                            // Reversed here rather than by the consumer, which takes from the
                            // back: same reason as everything else in this closure, it is work and
                            // it does not have to be the reader's.
                            .and_then(|()| {
                                let mut out = Vec::with_capacity(records.len());
                                for record in records.drain(..).rev() {
                                    let feature = feature_of(record)?;
                                    // The zoom floor, applied where the work is spread. See this
                                    // function's docs: at a shallow zoom this is nearly the whole
                                    // chunk.
                                    if z >= feature.class.min_zoom {
                                        out.push(feature);
                                    }
                                }
                                Ok(out)
                            });
                        // Stop on the first error: the consumer surfaces it and abandons the zoom,
                        // and carrying on would only queue work behind a build that is already
                        // failing.
                        let stop = decoded.is_err();
                        if send.send(decoded).is_err() || stop {
                            return;
                        }
                    }
                })
                .map_err(|e| {
                    osm_ingest::proto::Error(format!("cannot start spill prefetch lane {lane}: {e}"))
                })?;
            lanes.push(receive);
            lanes_running.push(running);
        }
        Ok(ZoomReader { lanes, lanes_running, at: 0, total, records: Vec::new() })
    }

    /// How many spill chunks this zoom will read, and how many it has.
    ///
    /// Exposed so the tiler can show progress: the map phase is the longest in the build and was
    /// silent for all of it, which on a continent is forty minutes of no output at all.
    pub fn chunks(&self) -> (usize, usize) {
        (self.at.min(self.total), self.total)
    }

    /// The next feature, or `None` once this zoom's chunks are exhausted.
    pub fn next(&mut self) -> Result<Option<Feature>> {
        loop {
            // From the back, which is why the lane reversed the chunk before sending it.
            if let Some(feature) = self.records.pop() {
                return Ok(Some(feature));
            }
            if self.at >= self.total {
                return Ok(None);
            }
            let lane = self.at % self.lanes.len();
            self.at += 1;
            // A lane that hung up before delivering all of its chunks panicked: it returns only
            // after sending every one of them or after sending an error, and an error arrives as a
            // value rather than a hangup.
            let decoded = self.lanes[lane].recv().map_err(|_| {
                osm_ingest::proto::Error(format!("spill prefetch lane {lane} stopped early"))
            })?;
            self.records = decoded?;
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
    max_ref: i64,
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
            max_ref: 0,
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
            self.max_ref = self.max_ref.max(node);
        }
        self.out
            .write_all(&self.record)
            .map_err(|e| Error(format!("cannot write the ways spill: {e}")))?;
        self.last_id = id;
        self.count += 1;
        self.refs += refs.len() as u64;
        Ok(())
    }

    /// Flush, and report how many ways were written, how many node refs they hold between them, and
    /// the largest of those refs.
    ///
    /// None of the three is a statistic. [`crate::extract`] reads this file back to collect the node
    /// ids pass 3 must resolve, and it picks between two ways of doing that on the ref count: below a
    /// budget it sizes one exact vector (grown by extension a 200 M-id vector doubles into 2.1 GB of
    /// capacity, and the realloc that gets it there holds the old and new allocations at once), and
    /// above it sizes a bitset from `max_ref`. Both numbers are free here, where every ref is already
    /// being walked to encode it, and neither is recoverable later without a second pass.
    pub fn finish(mut self) -> Result<WayCounts> {
        self.out.flush().map_err(|e| Error(format!("cannot flush the ways spill: {e}")))?;
        Ok(WayCounts { ways: self.count, refs: self.refs, max_ref: self.max_ref })
    }
}

/// What a finished [`WaySink`] holds.
pub struct WayCounts {
    pub ways: u64,
    /// Node refs summed over every way, duplicates included — a capacity, not a cardinality.
    pub refs: u64,
    /// The largest node ref written, or zero if none were. What a bitset over the id space is sized
    /// from.
    pub max_ref: i64,
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
                // Asserted, not filtered. The chunk index only skips a chunk when *every* feature
                // in it is too deep, so a kept chunk still carries features this zoom does not
                // draw -- and the reader's lanes are now what drops them. Anything arriving below
                // the floor means that filter has gone missing.
                assert!(feature.class.min_zoom <= z, "z{z} was handed a z{} feature", feature.class.min_zoom);
                got.push(feature.class.kind);
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

    /// **The lane partition, across every lane and past the wrap.**
    ///
    /// [`ZoomReader`] deals chunks over [`prefetch_lanes`] threads and reads them back by walking
    /// the lanes in the same order, and the archive's whole feature ordering rests on those two
    /// agreeing. Nothing else here reaches that code: the other store fixtures are a few hundred
    /// features, which is fewer chunks than there are lanes, so they run on a handful of lanes with
    /// one chunk each and would pass against a partition that shuffled the file.
    ///
    /// So this writes three full rounds over every lane, sized off the constants rather than a
    /// literal so it keeps its teeth if they change. Three rather than one because the wrap is the
    /// interesting part — a reader that dealt correctly but read back assuming one chunk per lane
    /// would agree for the first round and diverge after it. Every feature carries a coordinate
    /// unique to its position, so the assertion is the file's exact order rather than a count or a
    /// checksum.
    #[test]
    fn a_zoom_reader_returns_chunks_in_file_order_across_every_lane() {
        let count = 3 * prefetch_lanes() * PREFETCH_RUN * NORM_CHUNK_FEATURES as usize;
        // Unique per feature and inside real lon/lat, so the bbox fold has nothing to complain
        // about. 997 is prime, so it shares no factor with the lane or chunk counts and no aliasing
        // can hide a swapped chunk.
        //
        // Compared as e7 integers, never as `f64`. The spill quantises to the same 1e-7 grid the
        // archive header uses, so a round trip is exact on that grid and an ULP apart off it — and
        // an ULP is not what this test is about.
        let at = |i: usize| (-120.0 + (i % 997) as f64 * 0.0001, 35.0 + (i / 997) as f64 * 0.0001);
        let grid = |(x, y): (f64, f64)| ((x * 1e7).round() as i64, (y * 1e7).round() as i64);

        let path = temp("laneorder");
        let mut sink = Sink::create(&path).expect("create");
        let class = Class::line(dict::LAYER_ROADS, schema::kind("highway"), 0);
        for i in 0..count {
            let (x, y) = at(i);
            sink.push(&class, &Geometry::Lines(vec![vec![(x, y), (x, y + 0.0001)]]))
                .expect("push");
        }
        let store = sink.finish(&path).expect("finish");
        assert_eq!(store.len(), count as u64);

        // z0 keeps every chunk, because the class above is drawn from z0 -- so this is the whole
        // file, through the prefetch, with every lane loaded.
        let mut got = Vec::with_capacity(count);
        let mut reader = store.reader_for_zoom(0).expect("reader");
        while let Some(feature) = reader.next().expect("read") {
            let Geometry::Lines(lines) = &feature.geometry else { panic!("a line went in") };
            got.push(grid(lines[0][0]));
        }
        let expected: Vec<(i64, i64)> = (0..count).map(|i| grid(at(i))).collect();
        assert_eq!(got.len(), expected.len(), "the prefetch lost or invented features");
        // Located rather than just reported: `assert_eq` on two vectors this long prints something
        // nobody can read, and which chunk went astray is the whole diagnosis.
        if let Some(i) = (0..count).find(|&i| got[i] != expected[i]) {
            let chunk = i / NORM_CHUNK_FEATURES as usize;
            panic!(
                "feature {i} (chunk {chunk}, lane {}) is {:?}, expected {:?}",
                (chunk / PREFETCH_RUN) % prefetch_lanes(),
                got[i],
                expected[i],
            );
        }
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
