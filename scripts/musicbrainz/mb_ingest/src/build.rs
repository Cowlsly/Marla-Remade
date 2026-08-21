//! The ingest passes: mbdump tables in, one `.pack` out.
//!
//! Column orders are hard-coded from the MusicBrainz schema's
//! `admin/sql/CreateTables.sql` and every table's arity and distinctive columns
//! are checked on the first row (see `copy::Shape`), because a silently shifted
//! column would produce a plausible-looking but wrong pack.
//!
//! Tables arrive through [`crate::copy::TableSource`]. This crate never touches
//! the archive, bzip2 or tar — `location_share_server` owns the download and does
//! one traversal, spilling the 20 tables named in [`TABLES`]. It matters that the
//! spilled tables are **re-openable**: the track pass reads `track` once to spill
//! it into medium-keyed buckets and then reads the buckets back, which is what
//! keeps 57.4 M track rows out of memory.
//!
//! # Memory
//!
//! Peak RSS, not wall time, is the binding constraint: the ingest runs in-process
//! alongside the live server on an 11 GB box, so the budget is 4 GB. Four rules:
//!
//!   * per-row data is never accumulated for the output. `TRACKS` is built as a
//!     varint byte stream as the tracks are visited, not as a `Vec<TrackRec>` —
//!     that is the failure recorded in `scripts/maps/gtfs_ingest/src/index.rs:15-27`,
//!     where a fixed-size record per stop per trip made a world pack 10-20 GB.
//!   * id -> index maps are dense `Vec<u32>` keyed by the dump's integer primary
//!     key, not `HashMap<[u8;16], u32>` keyed by MBID. mbdump foreign keys are
//!     integer row ids (`track.recording INTEGER`), so the recording map is
//!     ~180 MB rather than the ~1.4 GB a UUID-keyed hash map would cost.
//!   * the track rows are **bucket-sorted through spill files**, not sorted in
//!     memory. The dump is in track-id order and `TRACKS` needs medium order;
//!     buffering all 57.4 M rows to sort would be ~1.4 GB.
//!   * `RECORDING_MBID` is filled by a second pass over `recording` that scatters
//!     gids into the output buffer, rather than holding 16 B per recording id
//!     (~800 MB) through the whole build.

use std::collections::HashMap;
use std::fs::File;
use std::io::{self, BufWriter, Read, Seek, Write};
use std::path::{Path, PathBuf};

use crate::copy::{each_row, other_err, Row, Shape, TableSource};
use crate::pack::{
    self, build_mbid_table, pack_date, pack_isrc, parse_mbid, search_terms, EnumPool, FinalPool,
    HeaderCounts, Mbid, PackWriter, StringBlockWriter, StringPool, Sym, SYM_EMPTY,
};
use crate::reader::encode_recording_credit;

/// Guard against a hostile or corrupt dump asking us to allocate a dense map for
/// an absurd primary key.
const MAX_ID: u32 = 400_000_000;

/// Every table this crate reads.
///
/// All of these are present in the core `mbdump.tar.bz2` — **verified by actually
/// extracting them**, which is not the same as what the schema suggests. Note the
/// absence of `release_group_meta`: it is marked `-- replicate` in
/// `admin/sql/CreateTables.sql`, but that marker controls replication packets, not
/// the dump split, and the table is NOT in the core dump. Its
/// `first_release_date_*` is derived here instead (see `derive_first_release_dates`),
/// which is what keeps this crate off `mbdump-derived.tar.bz2`.
pub const TABLES: &[&str] = &[
    "area",
    "iso_3166_1",
    "artist_type",
    "release_group_primary_type",
    "release_group_secondary_type",
    "release_status",
    "medium_format",
    "artist_credit",
    "artist_credit_name",
    "artist",
    "release_group",
    "release_group_secondary_type_join",
    "release",
    "release_country",
    "release_unknown_country",
    "medium",
    "recording",
    "track",
    "isrc",
];

#[derive(Clone)]
pub struct BuildOptions {
    pub include_track_mbids: bool,
    pub include_recording_mbids: bool,
    pub include_isrcs: bool,
    pub include_recording_search: bool,
    pub official_only: bool,
    /// Compress the string pool with zstd. Worth ~450 MB of the ~2.9 GB pack.
    pub compress_strings: bool,
    /// Where the track bucket files go. Defaults to the system temp directory.
    /// Needs room for ~1.4 GB at full scale.
    pub work_dir: Option<PathBuf>,
    pub verbose: bool,
}

impl Default for BuildOptions {
    /// Tier B of the design doc's §2.4: everything except track MBIDs, which is
    /// the single 918 MB section and the only thing tier B gives up.
    fn default() -> Self {
        BuildOptions {
            include_track_mbids: false,
            include_recording_mbids: true,
            include_isrcs: true,
            include_recording_search: true,
            official_only: false,
            compress_strings: true,
            work_dir: None,
            verbose: false,
        }
    }
}

impl BuildOptions {
    pub fn tier_a() -> Self {
        BuildOptions { include_track_mbids: true, ..Self::default() }
    }

    fn flags(&self) -> u32 {
        let mut f = 0;
        if self.include_track_mbids {
            f |= pack::FLAG_TRACK_MBIDS;
        }
        if self.include_recording_mbids {
            f |= pack::FLAG_RECORDING_MBIDS;
        }
        if self.include_isrcs {
            f |= pack::FLAG_ISRCS;
        }
        if self.include_recording_search {
            f |= pack::FLAG_RECORDING_SEARCH;
        }
        if self.official_only {
            f |= pack::FLAG_OFFICIAL_ONLY;
        }
        if self.compress_strings {
            f |= pack::FLAG_STRINGS_COMPRESSED;
        }
        f
    }
}

/// The measured numbers a build prints. The four ratios were estimates in the
/// design doc (its R2, ~285 MB of budget between them); a real run closes them.
#[derive(Default)]
pub struct BuildStats {
    pub rows: Vec<(&'static str, u64)>,
    pub sections: Vec<(&'static str, u64)>,
    pub total_bytes: u64,
    pub distinct_strings: usize,
    pub string_pool_raw: u64,
    pub string_pool_stored: u64,
    pub string_blocks: u32,
    pub recording_titles: u64,
    pub distinct_recording_titles: u64,
    pub tracks_title_same: u64,
    pub tracks_credit_same: u64,
    pub tracks_length_same: u64,
    pub standalone_recordings: u64,
    pub skipped: Vec<(&'static str, u64)>,
    pub isrc_rejects: u64,
    pub track_spill_bytes: u64,
    pub dense_map_bytes: u64,
    pub search_postings: u64,
    /// Per table: (name, max row id seen, live row count). The flat `Vec<u32>` id
    /// maps are sized to the MAX ID, not the live count, so the gap between them is
    /// a direct multiplier on resident memory and the last unmeasured input to the
    /// memory model.
    pub max_ids: Vec<(&'static str, u64, u64)>,
}

impl BuildStats {
    pub fn report(&self) -> String {
        let mb = |b: u64| b as f64 / 1_000_000.0;
        let pct = |a: u64, b: u64| if b == 0 { 0.0 } else { 100.0 * a as f64 / b as f64 };
        let mut s = String::new();
        s.push_str("input rows\n");
        for (name, n) in &self.rows {
            s.push_str(&format!("  {name:<38} {n:>12}\n"));
        }
        if !self.skipped.is_empty() {
            s.push_str("skipped (dangling or filtered)\n");
            for (name, n) in &self.skipped {
                s.push_str(&format!("  {name:<38} {n:>12}\n"));
            }
        }
        s.push_str("sections\n");
        for (name, len) in &self.sections {
            if *len > 0 {
                s.push_str(&format!("  {name:<38} {:>12.1} MB\n", mb(*len)));
            }
        }
        s.push_str(&format!("  {:<38} {:>12.1} MB\n", "TOTAL", mb(self.total_bytes)));
        s.push_str("measured ratios (design doc R2 -- these were estimates)\n");
        s.push_str(&format!(
            "  {:<38} {:>11.1}%  ({} of {})\n",
            "distinct recording titles",
            pct(self.distinct_recording_titles, self.recording_titles),
            self.distinct_recording_titles,
            self.recording_titles
        ));
        let tracks = self.rows.iter().find(|(n, _)| *n == "track").map(|(_, n)| *n).unwrap_or(0);
        s.push_str(&format!(
            "  {:<38} {:>11.1}%\n",
            "tracks whose title == recording's",
            pct(self.tracks_title_same, tracks)
        ));
        s.push_str(&format!(
            "  {:<38} {:>11.1}%\n",
            "tracks whose credit == recording's",
            pct(self.tracks_credit_same, tracks)
        ));
        s.push_str(&format!(
            "  {:<38} {:>11.1}%\n",
            "tracks whose length == recording's",
            pct(self.tracks_length_same, tracks)
        ));
        let recs = self.rows.iter().find(|(n, _)| *n == "recording").map(|(_, n)| *n).unwrap_or(0);
        s.push_str(&format!(
            "  {:<38} {:>11.1}%  ({})\n",
            "recordings with no track (standalone)",
            pct(self.standalone_recordings, recs),
            self.standalone_recordings
        ));
        s.push_str("string pool\n");
        s.push_str(&format!("  {:<38} {:>12}\n", "distinct strings", self.distinct_strings));
        s.push_str(&format!("  {:<38} {:>12.1} MB\n", "raw", mb(self.string_pool_raw)));
        s.push_str(&format!(
            "  {:<38} {:>12.1} MB  ({} blocks, {:.2}x)\n",
            "stored",
            mb(self.string_pool_stored),
            self.string_blocks,
            if self.string_pool_stored == 0 {
                0.0
            } else {
                self.string_pool_raw as f64 / self.string_pool_stored as f64
            }
        ));
        s.push_str("id density (flat maps are sized to max id, not live count)\n");
        for (name, max_id, live) in &self.max_ids {
            let gap = if *live == 0 { 0.0 } else { (*max_id + 1) as f64 / *live as f64 };
            s.push_str(&format!(
                "  {name:<24} max id {max_id:>11}  live {live:>11}  gap {gap:>5.2}x\n"
            ));
        }
        s.push_str("build cost\n");
        s.push_str(&format!("  {:<38} {:>12}\n", "search postings", self.search_postings));
        s.push_str(&format!(
            "  {:<38} {:>12.1} MB\n",
            "track spill (disk, not memory)",
            mb(self.track_spill_bytes)
        ));
        s.push_str(&format!(
            "  {:<38} {:>12.1} MB\n",
            "dense id -> index maps",
            mb(self.dense_map_bytes)
        ));
        if self.isrc_rejects > 0 {
            s.push_str(&format!("  {:<38} {:>12}\n", "malformed ISRCs dropped", self.isrc_rejects));
        }
        s
    }
}

/// A dense `primary key -> index` map.
struct DenseMap {
    v: Vec<u32>,
}

impl DenseMap {
    fn new() -> DenseMap {
        DenseMap { v: Vec::new() }
    }

    fn set(&mut self, id: u32, val: u32) -> io::Result<()> {
        if id > MAX_ID {
            return Err(other_err(format!("primary key {id} exceeds the sanity ceiling {MAX_ID}")));
        }
        if self.v.len() <= id as usize {
            self.v.resize(id as usize + 1, pack::NONE);
        }
        self.v[id as usize] = val;
        Ok(())
    }

    fn get(&self, id: u32) -> Option<u32> {
        match self.v.get(id as usize) {
            Some(&v) if v != pack::NONE => Some(v),
            _ => None,
        }
    }

    fn bytes(&self) -> u64 {
        (self.v.len() * 4) as u64
    }

    /// The largest primary key seen, which is what the allocation is sized to.
    fn max_id(&self) -> u64 {
        self.v.len().saturating_sub(1) as u64
    }

    fn live(&self) -> u64 {
        self.v.iter().filter(|&&v| v != pack::NONE).count() as u64
    }
}

// --- track bucket spill ---

/// Fixed-width track record written to the bucket files. 24 bytes, or 40 with a
/// track MBID appended for tier A.
const TRACK_SPILL_LEN: usize = 24;
const TRACK_SPILL_LEN_A: usize = 40;

/// Bucket-sorts track rows by medium through files on disk.
///
/// The alternative is buffering all 57.4 M rows to sort, ~1.4 GB, which does not
/// fit the memory budget. Trading wall time and ~1.4 GB of scratch disk for that
/// is the explicit instruction.
struct TrackSpill {
    dir: PathBuf,
    writers: Vec<BufWriter<File>>,
    buckets: usize,
    rec_len: usize,
    media: u32,
    written: u64,
}

/// Distinguishes concurrent builds inside one process. Without it two builds in
/// the same process share a spill directory and delete each other's buckets --
/// which is exactly what the parallel test runner does.
static SPILL_SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

impl TrackSpill {
    fn new(work_dir: Option<&Path>, media: u32, rec_len: usize) -> io::Result<TrackSpill> {
        // One bucket is plenty for a fixture; a full run wants each bucket to be a
        // few tens of MB so sorting one is cheap.
        let buckets = if media < 100_000 { 1 } else { 64 };
        let base = match work_dir {
            Some(p) => p.to_path_buf(),
            None => std::env::temp_dir(),
        };
        let seq = SPILL_SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let dir = base.join(format!("mb_ingest_tracks_{}_{seq}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir)?;
        let mut writers = Vec::with_capacity(buckets);
        for b in 0..buckets {
            writers.push(BufWriter::with_capacity(
                1 << 16,
                File::create(dir.join(format!("b{b:03}")))?,
            ));
        }
        Ok(TrackSpill { dir, writers, buckets, rec_len, media, written: 0 })
    }

    fn bucket_of(&self, medium_idx: u32) -> usize {
        if self.buckets == 1 || self.media == 0 {
            return 0;
        }
        ((medium_idx as u64 * self.buckets as u64) / self.media as u64)
            .min(self.buckets as u64 - 1) as usize
    }

    fn push(&mut self, medium_idx: u32, rec: &[u8]) -> io::Result<()> {
        debug_assert_eq!(rec.len(), self.rec_len);
        let b = self.bucket_of(medium_idx);
        self.writers[b].write_all(rec)?;
        self.written += rec.len() as u64;
        Ok(())
    }

    /// Flush and return the bucket paths, in medium order.
    fn finish(&mut self) -> io::Result<Vec<PathBuf>> {
        for w in self.writers.iter_mut() {
            w.flush()?;
        }
        self.writers.clear();
        Ok((0..self.buckets).map(|b| self.dir.join(format!("b{b:03}"))).collect())
    }
}

impl Drop for TrackSpill {
    fn drop(&mut self) {
        self.writers.clear();
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

/// Bucket-sorts search postings by term rank through files on disk.
///
/// 157 M postings held as `Vec<(u32, u32)>` is 1.26 GB, which was the single
/// largest contributor to a measured 6.14 GB peak. Bucketing by rank means only
/// one bucket (~20 MB) is resident while it is sorted and emitted, and the ranks
/// are known before this runs so the buckets come out in output order.
struct PostingSpill {
    dir: PathBuf,
    writers: Vec<BufWriter<File>>,
    buckets: usize,
    terms: u32,
    written: u64,
}

impl PostingSpill {
    fn new(work_dir: Option<&Path>, terms: u32) -> io::Result<PostingSpill> {
        let buckets = if terms < 100_000 { 1 } else { 64 };
        let base = match work_dir {
            Some(p) => p.to_path_buf(),
            None => std::env::temp_dir(),
        };
        let seq = SPILL_SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let dir = base.join(format!("mb_ingest_postings_{}_{seq}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir)?;
        let mut writers = Vec::with_capacity(buckets);
        for b in 0..buckets {
            writers.push(BufWriter::with_capacity(
                1 << 16,
                File::create(dir.join(format!("p{b:03}")))?,
            ));
        }
        Ok(PostingSpill { dir, writers, buckets, terms, written: 0 })
    }

    fn bucket_of(&self, rank: u32) -> usize {
        if self.buckets == 1 || self.terms == 0 {
            return 0;
        }
        ((rank as u64 * self.buckets as u64) / self.terms as u64).min(self.buckets as u64 - 1)
            as usize
    }

    /// First rank NOT in bucket `b`. Must be the exact inverse of `bucket_of`.
    fn bucket_end(&self, b: usize) -> u32 {
        if self.buckets == 1 {
            return self.terms;
        }
        if b + 1 >= self.buckets {
            return self.terms;
        }
        let t = self.terms as u64;
        let n = self.buckets as u64;
        (((b as u64 + 1) * t).div_ceil(n)).min(t) as u32
    }

    fn push(&mut self, rank: u32, refv: u32) -> io::Result<()> {
        let b = self.bucket_of(rank);
        let mut rec = [0u8; 8];
        rec[0..4].copy_from_slice(&rank.to_le_bytes());
        rec[4..8].copy_from_slice(&refv.to_le_bytes());
        self.writers[b].write_all(&rec)?;
        self.written += 8;
        Ok(())
    }

    fn finish(&mut self) -> io::Result<Vec<PathBuf>> {
        for w in self.writers.iter_mut() {
            w.flush()?;
        }
        self.writers.clear();
        Ok((0..self.buckets).map(|b| self.dir.join(format!("p{b:03}"))).collect())
    }
}

impl Drop for PostingSpill {
    fn drop(&mut self) {
        self.writers.clear();
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

/// The display-string symbol for a credit id, or `SYM_EMPTY` when there is none.
fn credit_sym_of(credit_syms: &[Sym], credit_id: u32) -> Sym {
    if credit_id == pack::NONE {
        return SYM_EMPTY;
    }
    credit_syms.get(credit_id as usize).copied().unwrap_or(SYM_EMPTY)
}

/// Tokenise an entity's indexable text and spill one posting per term.
///
/// The terms come from `pack::indexable_terms`, which the reader's verification
/// path also calls. That sharing is the point: two implementations of "what does
/// this entity contribute to the index" is precisely the bug that shipped.
fn emit_postings(
    primary: &str,
    credit: &str,
    refv: u32,
    terms: &StringPool,
    ranks: &[u32],
    buf: &mut Vec<String>,
    spill: &mut PostingSpill,
) -> io::Result<()> {
    pack::indexable_terms(primary, credit, buf);
    for t in buf.iter() {
        let Some(sym) = terms.lookup(t) else {
            return Err(other_err(format!(
                "search term {t:?} was not interned in the dictionary pass; the two search \
                 passes disagree, which would silently corrupt the index"
            )));
        };
        spill.push(ranks[sym as usize], refv)?;
    }
    Ok(())
}

#[derive(Clone, Copy)]
struct TrackRow {
    medium_idx: u32,
    position: i32,
    recording_id: u32,
    title: Sym,
    credit_id: u32,
    length_s: u16,
}

impl TrackRow {
    fn encode(&self, out: &mut [u8]) {
        out[0..4].copy_from_slice(&self.medium_idx.to_le_bytes());
        out[4..8].copy_from_slice(&self.position.to_le_bytes());
        out[8..12].copy_from_slice(&self.recording_id.to_le_bytes());
        out[12..16].copy_from_slice(&self.title.to_le_bytes());
        out[16..20].copy_from_slice(&self.credit_id.to_le_bytes());
        out[20..22].copy_from_slice(&self.length_s.to_le_bytes());
        out[22..24].copy_from_slice(&0u16.to_le_bytes());
    }

    fn decode(b: &[u8]) -> TrackRow {
        let u32_at = |i: usize| u32::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]]);
        TrackRow {
            medium_idx: u32_at(0),
            position: i32::from_le_bytes([b[4], b[5], b[6], b[7]]),
            recording_id: u32_at(8),
            title: u32_at(12),
            credit_id: u32_at(16),
            length_s: u16::from_le_bytes([b[20], b[21]]),
        }
    }
}

// --- intermediate rows ---

struct ArtistBuild {
    gid: Mbid,
    name: Sym,
    disamb: Sym,
    area: Sym,
    begin_date: u32,
    kind: u16,
    country: u16,
    id: u32,
}

struct RgBuild {
    gid: Mbid,
    title: Sym,
    first_date: u32,
    credit_id: u32,
    primary: u16,
    secondary: u16,
    secondary_src_id: u32,
    id: u32,
}

struct ReleaseBuild {
    gid: Mbid,
    title: Sym,
    rg_id: u32,
    credit_id: u32,
    date: u32,
    disamb: Sym,
    status: u16,
    status_rank: u8,
    country: u16,
    id: u32,
}

struct MediumBuild {
    id: u32,
    release_idx: u32,
    position: i32,
    format: u16,
    track_count: u32,
}

/// Build a pack from `source` and write it to `out`.
pub fn build<W: Write + Seek>(
    source: &dyn TableSource,
    out: W,
    opts: &BuildOptions,
    log: &mut dyn Write,
) -> io::Result<BuildStats> {
    let mut st = BuildStats::default();
    let mut pool = StringPool::new();
    let mut enums = EnumPool::new();
    // Phase lines carry elapsed seconds so an external RSS sampler can be joined
    // against them. Peak memory per phase is measured that way rather than
    // modelled: the one time it was modelled, the estimate was wrong by 3x.
    let started = std::time::Instant::now();
    macro_rules! step {
        ($($arg:tt)*) => {
            if opts.verbose {
                write!(log, "[{:8.2}s] ", started.elapsed().as_secs_f64())?;
                writeln!(log, $($arg)*)?;
                log.flush()?;
            }
        };
    }

    // --- areas and the small enumerations -------------------------------------
    step!("pass 1: areas, country codes, enumerations");
    let mut area_name: HashMap<u32, Sym> = HashMap::new();
    let n = require(
        source,
        "area",
        Shape { table: "area", min_fields: 14, ints: &[0], uuids: &[1] },
        |row| {
            if let Some(id) = row.u32(0) {
                let sym = pool.intern(&row.str(2));
                area_name.insert(id, sym);
            }
            Ok(())
        },
    )?;
    st.rows.push(("area", n));

    let mut area_country: HashMap<u32, u16> = HashMap::new();
    let n = require(
        source,
        "iso_3166_1",
        Shape { table: "iso_3166_1", min_fields: 2, ints: &[0], uuids: &[] },
        |row| {
            if let Some(area) = row.u32(0) {
                let code = row.str(1);
                let code = code.trim();
                if !code.is_empty() {
                    let e = enums.intern(&mut pool, code);
                    area_country.insert(area, e);
                }
            }
            Ok(())
        },
    )?;
    st.rows.push(("iso_3166_1", n));

    let artist_types = read_enum_table(source, "artist_type", &mut pool, &mut enums)?;
    let rg_primary = read_enum_table(source, "release_group_primary_type", &mut pool, &mut enums)?;
    let rg_secondary =
        read_enum_table(source, "release_group_secondary_type", &mut pool, &mut enums)?;
    let statuses = read_enum_table(source, "release_status", &mut pool, &mut enums)?;
    let formats = read_enum_table(source, "medium_format", &mut pool, &mut enums)?;

    // --- artist credits -------------------------------------------------------
    // A credit is stored as one pre-rendered display string ("A feat. B"), because
    // nothing in the app reads the per-artist breakdown -- only
    // MbArtistCredit.display(), which concatenates name + joinphrase. MusicBrainz
    // already stores exactly that string as `artist_credit.name`, so we take it
    // rather than re-deriving it from artist_credit_name and risking a different
    // join order. artist_credit_name is still needed, for the artist -> release
    // group links.
    step!("pass 2: artist credits");
    let mut credit_syms: Vec<Sym> = Vec::new();
    let n = require(
        source,
        "artist_credit",
        Shape { table: "artist_credit", min_fields: 7, ints: &[0, 2], uuids: &[6] },
        |row| {
            let Some(id) = row.u32(0) else { return Ok(()) };
            if id > MAX_ID {
                return Err(other_err(format!("artist_credit id {id} exceeds {MAX_ID}")));
            }
            let i = id as usize;
            if credit_syms.len() <= i {
                credit_syms.resize(i + 1, SYM_EMPTY);
            }
            credit_syms[i] = pool.intern(&row.str(1));
            Ok(())
        },
    )?;
    st.rows.push(("artist_credit", n));
    let credit_count = credit_syms.len() as u32;

    let mut credit_artists: Vec<Vec<u32>> = vec![Vec::new(); credit_syms.len()];
    let n = require(
        source,
        "artist_credit_name",
        Shape { table: "artist_credit_name", min_fields: 5, ints: &[0, 1, 2], uuids: &[] },
        |row| {
            let (Some(cid), Some(artist)) = (row.u32(0), row.u32(2)) else { return Ok(()) };
            if let Some(slot) = credit_artists.get_mut(cid as usize) {
                slot.push(artist);
            }
            Ok(())
        },
    )?;
    st.rows.push(("artist_credit_name", n));

    // --- artists --------------------------------------------------------------
    step!("pass 3: artists");
    let mut artists: Vec<ArtistBuild> = Vec::new();
    let mut bad_gids = 0u64;
    let n = require(
        source,
        "artist",
        Shape { table: "artist", min_fields: 19, ints: &[0], uuids: &[1] },
        |row| {
            let Some(id) = row.u32(0) else { return Ok(()) };
            let Some(gid) = parse_mbid(&row.str(1)) else {
                bad_gids += 1;
                return Ok(());
            };
            let area = row.u32(11);
            artists.push(ArtistBuild {
                gid,
                name: pool.intern(&row.str(2)),
                disamb: pool.intern(&row.str(13)),
                area: area.and_then(|a| area_name.get(&a).copied()).unwrap_or(SYM_EMPTY),
                begin_date: pack_date(row.i64(4), row.i64(5), row.i64(6)),
                kind: row
                    .u32(10)
                    .and_then(|t| artist_types.get(&t).copied())
                    .unwrap_or(EnumPool::NONE_ENUM),
                country: area
                    .and_then(|a| area_country.get(&a).copied())
                    .unwrap_or(EnumPool::NONE_ENUM),
                id,
            });
            Ok(())
        },
    )?;
    st.rows.push(("artist", n));
    if bad_gids > 0 {
        st.skipped.push(("artist rows with an unparseable gid", bad_gids));
    }
    drop(area_name);

    artists.sort_unstable_by_key(|a| a.gid);
    let mut artist_idx = DenseMap::new();
    for (i, a) in artists.iter().enumerate() {
        artist_idx.set(a.id, i as u32)?;
    }

    // --- release groups -------------------------------------------------------
    step!("pass 4: release groups");
    let mut rgs: Vec<RgBuild> = Vec::new();
    let n = require(
        source,
        "release_group",
        Shape { table: "release_group", min_fields: 8, ints: &[0, 3], uuids: &[1] },
        |row| {
            let Some(id) = row.u32(0) else { return Ok(()) };
            let Some(gid) = parse_mbid(&row.str(1)) else { return Ok(()) };
            rgs.push(RgBuild {
                gid,
                title: pool.intern(&row.str(2)),
                first_date: 0,
                credit_id: row.u32(3).unwrap_or(pack::NONE),
                primary: row
                    .u32(4)
                    .and_then(|t| rg_primary.get(&t).copied())
                    .unwrap_or(EnumPool::NONE_ENUM),
                secondary: EnumPool::NONE_ENUM,
                secondary_src_id: u32::MAX,
                id,
            });
            Ok(())
        },
    )?;
    st.rows.push(("release_group", n));
    let mut rg_slot = DenseMap::new();
    for (i, r) in rgs.iter().enumerate() {
        rg_slot.set(r.id, i as u32)?;
    }
    let n = require(
        source,
        "release_group_secondary_type_join",
        Shape {
            table: "release_group_secondary_type_join",
            min_fields: 2,
            ints: &[0, 1],
            uuids: &[],
        },
        |row| {
            let (Some(rg), Some(ty)) = (row.u32(0), row.u32(1)) else { return Ok(()) };
            if let Some(slot) = rg_slot.get(rg) {
                let r = &mut rgs[slot as usize];
                // The app only ever renders secondaryTypes[0]; pick the lowest type
                // id so the choice is deterministic across rebuilds.
                if ty < r.secondary_src_id {
                    if let Some(&e) = rg_secondary.get(&ty) {
                        r.secondary = e;
                        r.secondary_src_id = ty;
                    }
                }
            }
            Ok(())
        },
    )?;
    st.rows.push(("release_group_secondary_type_join", n));
    drop(rg_slot);

    rgs.sort_unstable_by_key(|r| r.gid);
    let mut rg_idx = DenseMap::new();
    for (i, r) in rgs.iter().enumerate() {
        rg_idx.set(r.id, i as u32)?;
    }

    // --- releases -------------------------------------------------------------
    step!("pass 5: releases");
    let mut releases: Vec<ReleaseBuild> = Vec::new();
    let mut filtered_status = 0u64;
    let n = require(
        source,
        "release",
        Shape { table: "release", min_fields: 14, ints: &[0, 3, 4], uuids: &[1] },
        |row| {
            let Some(id) = row.u32(0) else { return Ok(()) };
            let Some(gid) = parse_mbid(&row.str(1)) else { return Ok(()) };
            let Some(rg) = row.u32(4) else { return Ok(()) };
            let status_id = row.u32(5);
            if opts.official_only && status_id != Some(1) {
                filtered_status += 1;
                return Ok(());
            }
            releases.push(ReleaseBuild {
                gid,
                title: pool.intern(&row.str(2)),
                rg_id: rg,
                credit_id: row.u32(3).unwrap_or(pack::NONE),
                date: 0,
                disamb: pool.intern(&row.str(10)),
                status: status_id
                    .and_then(|s| statuses.get(&s).copied())
                    .unwrap_or(EnumPool::NONE_ENUM),
                // Official (id 1) first, then anything with a status, then none:
                // the edition-list sort key of MusicBrainzViewModel.kt:238.
                status_rank: match status_id {
                    Some(1) => 0,
                    Some(_) => 1,
                    None => 2,
                },
                country: EnumPool::NONE_ENUM,
                id,
            });
            Ok(())
        },
    )?;
    st.rows.push(("release", n));
    if filtered_status > 0 {
        st.skipped.push(("non-Official releases (--official-only)", filtered_status));
    }
    let mut release_slot = DenseMap::new();
    for (i, r) in releases.iter().enumerate() {
        release_slot.set(r.id, i as u32)?;
    }
    // release_country.country is an *area* id, not a country code, so it resolves
    // through the same iso_3166_1 mapping the artists used; a release can carry
    // several countries, and we keep the earliest-dated one.
    let n = require(
        source,
        "release_country",
        Shape { table: "release_country", min_fields: 5, ints: &[0, 1], uuids: &[] },
        |row| {
            let Some(slot) = row.u32(0).and_then(|id| release_slot.get(id)) else { return Ok(()) };
            let d = pack_date(row.i64(2), row.i64(3), row.i64(4));
            let r = &mut releases[slot as usize];
            if pack::date_rank(d) < pack::date_rank(r.date) {
                r.date = d;
                if let Some(&c) = row.u32(1).and_then(|a| area_country.get(&a)) {
                    r.country = c;
                }
            }
            Ok(())
        },
    )?;
    st.rows.push(("release_country", n));
    let n = require(
        source,
        "release_unknown_country",
        Shape { table: "release_unknown_country", min_fields: 4, ints: &[0], uuids: &[] },
        |row| {
            let Some(slot) = row.u32(0).and_then(|id| release_slot.get(id)) else { return Ok(()) };
            let d = pack_date(row.i64(1), row.i64(2), row.i64(3));
            let r = &mut releases[slot as usize];
            if pack::date_rank(d) < pack::date_rank(r.date) {
                r.date = d;
            }
            Ok(())
        },
    )?;
    st.rows.push(("release_unknown_country", n));
    drop(release_slot);
    drop(area_country);

    releases.sort_unstable_by_key(|r| r.gid);
    let mut release_idx = DenseMap::new();
    for (i, r) in releases.iter().enumerate() {
        release_idx.set(r.id, i as u32)?;
    }
    let mut dangling_rg = 0u64;
    let mut rg_release_pairs: Vec<(u32, u32)> = Vec::with_capacity(releases.len());
    for (i, r) in releases.iter().enumerate() {
        match rg_idx.get(r.rg_id) {
            Some(g) => rg_release_pairs.push((g, i as u32)),
            None => dangling_rg += 1,
        }
    }
    if dangling_rg > 0 {
        st.skipped.push(("releases whose release group is missing", dangling_rg));
    }

    // A release group's first release date. MusicBrainz materialises this in
    // `release_group_meta`, which is NOT in the core dump, so it is derived here as
    // the earliest known date across the group's releases -- which is how upstream
    // computes it in the first place.
    //
    // The comparison goes through `date_rank`, NOT the packed value: a year-only
    // date packs with month and day zero, so a raw `<` would make `1973` beat
    // `1973-03-24` and the group would lose the precise date. Spot-checked against
    // musicbrainz.org: release group f5093c06-23e3-404f-aeaa-40f72885ee3a (The Dark
    // Side of the Moon) is 1973-03-24 there, and a raw comparison derived `1973`.
    //
    // Load-bearing: the RG screen header, the discography sort
    // (MusicBrainzViewModel.kt:207) and the release-date fallback (:475-476).
    for &(g, ri) in &rg_release_pairs {
        let d = releases[ri as usize].date;
        if d == 0 {
            continue;
        }
        let cur = &mut rgs[g as usize].first_date;
        if pack::date_rank(d) < pack::date_rank(*cur) {
            *cur = d;
        }
    }

    // ARTIST_RGS: artist -> release groups, via the release group's credit. Built
    // after the dates are derived, because it is sorted on them.
    let mut artist_rg_pairs: Vec<(u32, u32)> = Vec::new();
    for (i, r) in rgs.iter().enumerate() {
        let Some(artists_of) = credit_artists.get(r.credit_id as usize) else { continue };
        for a in artists_of {
            if let Some(ai) = artist_idx.get(*a) {
                artist_rg_pairs.push((ai, i as u32));
            }
        }
    }
    // Newest first: the discography order MusicBrainzViewModel.kt:207 sorts into.
    artist_rg_pairs.sort_unstable_by(|x, y| {
        x.0.cmp(&y.0)
            .then(rgs[y.1 as usize].first_date.cmp(&rgs[x.1 as usize].first_date))
            .then(x.1.cmp(&y.1))
    });
    artist_rg_pairs.dedup();
    let (artist_rgs, artist_rgs_idx) = to_csr(&artist_rg_pairs, artists.len());
    drop(artist_rg_pairs);
    drop(credit_artists);

    rg_release_pairs.sort_unstable_by(|x, y| {
        let (a, b) = (&releases[x.1 as usize], &releases[y.1 as usize]);
        x.0.cmp(&y.0)
            .then(a.status_rank.cmp(&b.status_rank))
            .then(pack::date_rank(a.date).cmp(&pack::date_rank(b.date)))
            .then(x.1.cmp(&y.1))
    });
    let (rg_releases, rg_releases_idx) = to_csr(&rg_release_pairs, rgs.len());
    drop(rg_release_pairs);

    // --- media ----------------------------------------------------------------
    step!("pass 6: media");
    let mut media: Vec<MediumBuild> = Vec::new();
    let mut dangling_media = 0u64;
    let n = require(
        source,
        "medium",
        Shape { table: "medium", min_fields: 9, ints: &[0, 1, 2], uuids: &[8] },
        |row| {
            let (Some(id), Some(rel)) = (row.u32(0), row.u32(1)) else { return Ok(()) };
            let Some(ri) = release_idx.get(rel) else {
                dangling_media += 1;
                return Ok(());
            };
            media.push(MediumBuild {
                id,
                release_idx: ri,
                position: clamp_position(row.i64(2)),
                format: row
                    .u32(3)
                    .and_then(|f| formats.get(&f).copied())
                    .unwrap_or(EnumPool::NONE_ENUM),
                track_count: row.u32(7).unwrap_or(0),
            });
            Ok(())
        },
    )?;
    st.rows.push(("medium", n));
    if dangling_media > 0 {
        st.skipped.push(("media whose release was filtered or missing", dangling_media));
    }
    media.sort_unstable_by(|a, b| {
        a.release_idx.cmp(&b.release_idx).then(a.position.cmp(&b.position)).then(a.id.cmp(&b.id))
    });
    let mut medium_idx = DenseMap::new();
    for (i, m) in media.iter().enumerate() {
        medium_idx.set(m.id, i as u32)?;
    }
    let mut media_idx_csr = vec![0u32; releases.len() + 1];
    for m in &media {
        media_idx_csr[m.release_idx as usize + 1] += 1;
    }
    for i in 0..releases.len() {
        media_idx_csr[i + 1] += media_idx_csr[i];
    }

    // --- recordings -----------------------------------------------------------
    // Struct-of-arrays keyed by recording id: no padding, and the gids are NOT
    // held (that would be ~800 MB); RECORDING_MBID is filled by a second pass.
    step!("pass 7: recordings");
    let mut rec_title: Vec<Sym> = Vec::new();
    let mut rec_credit: Vec<u32> = Vec::new();
    let mut rec_dur: Vec<u16> = Vec::new();
    let mut rec_present: Vec<bool> = Vec::new();
    let mut distinct_titles: std::collections::HashSet<Sym> = std::collections::HashSet::new();
    let n = require(
        source,
        "recording",
        Shape { table: "recording", min_fields: 9, ints: &[0, 3], uuids: &[1] },
        |row| {
            let Some(id) = row.u32(0) else { return Ok(()) };
            if !row.looks_like_uuid(1) {
                return Ok(());
            }
            if id > MAX_ID {
                return Err(other_err(format!("recording id {id} exceeds {MAX_ID}")));
            }
            let i = id as usize;
            if rec_title.len() <= i {
                rec_title.resize(i + 1, SYM_EMPTY);
                rec_credit.resize(i + 1, pack::NONE);
                rec_dur.resize(i + 1, 0);
                rec_present.resize(i + 1, false);
            }
            let title = pool.intern(&row.str(2));
            distinct_titles.insert(title);
            rec_title[i] = title;
            rec_credit[i] = row.u32(3).unwrap_or(pack::NONE);
            rec_dur[i] = ms_to_secs(row.i64(4));
            rec_present[i] = true;
            Ok(())
        },
    )?;
    st.rows.push(("recording", n));
    st.recording_titles = n;
    st.distinct_recording_titles = distinct_titles.len() as u64;
    drop(distinct_titles);

    // --- tracks, phase 1: spill to medium-keyed buckets ------------------------
    step!("pass 8: tracks -> spill buckets");
    let spill_len =
        if opts.include_track_mbids { TRACK_SPILL_LEN_A } else { TRACK_SPILL_LEN };
    let mut spill = TrackSpill::new(opts.work_dir.as_deref(), media.len() as u32, spill_len)?;
    let mut dangling_tracks = 0u64;
    let mut bad_track_gids = 0u64;
    let mut rec = vec![0u8; spill_len];
    let n = require(
        source,
        "track",
        Shape { table: "track", min_fields: 12, ints: &[0, 2, 3, 4, 7], uuids: &[1] },
        |row| {
            let (Some(recording), Some(med)) = (row.u32(2), row.u32(3)) else { return Ok(()) };
            let Some(mi) = medium_idx.get(med) else {
                dangling_tracks += 1;
                return Ok(());
            };
            let gid = if opts.include_track_mbids {
                match parse_mbid(&row.str(1)) {
                    Some(g) => g,
                    None => {
                        bad_track_gids += 1;
                        return Ok(());
                    }
                }
            } else {
                [0u8; 16]
            };
            TrackRow {
                medium_idx: mi,
                position: clamp_position(row.i64(4)),
                recording_id: recording,
                title: pool.intern(&row.str(6)),
                credit_id: row.u32(7).unwrap_or(pack::NONE),
                length_s: ms_to_secs(row.i64(8)),
            }
            .encode(&mut rec);
            if opts.include_track_mbids {
                rec[TRACK_SPILL_LEN..TRACK_SPILL_LEN_A].copy_from_slice(&gid);
            }
            spill.push(mi, &rec)
        },
    )?;
    st.rows.push(("track", n));
    if dangling_tracks > 0 {
        st.skipped.push(("tracks whose medium was filtered or missing", dangling_tracks));
    }
    if bad_track_gids > 0 {
        st.skipped.push(("tracks with an unparseable gid", bad_track_gids));
    }
    st.track_spill_bytes = spill.written;
    let buckets = spill.finish()?;

    // --- finalise the string pool ---------------------------------------------
    // Every table has now been read, so the pool is complete and can be sorted.
    // Nothing before this point knows a byte offset; everything after uses one.
    step!("pass 9: sorting the string pool ({} distinct)", pool.distinct());
    st.distinct_strings = pool.distinct();
    let pool: FinalPool = pool.finalize();
    st.string_pool_raw = pool.raw_len() as u64;

    // --- emit everything that is already final --------------------------------
    // The pack writer opens HERE, before the track pass, so the artist,
    // release-group and release vectors can go to disk and be dropped rather than
    // sitting resident until the end. Measured: they are 571 MB of a 3.49 GB peak,
    // and nothing after this point needs more than their name/title symbols.
    // Section order in the file is irrelevant; the directory holds absolute offsets.
    step!("pass 9b: artists, release groups, releases");
    let artist_count = artists.len() as u32;
    let rg_count = rgs.len() as u32;
    let release_count = releases.len() as u32;
    let credit_live = credit_syms.iter().filter(|&&s| s != SYM_EMPTY).count() as u64;
    let mut w = PackWriter::new(out)?;

    let enum_offsets: Vec<u32> = enums.syms().iter().map(|&s| pool.offset(s)).collect();
    let enum_count = enum_offsets.len() as u32;
    w.section_u32(pack::S_ENUM_POOL, &enum_offsets)?;
    drop(enum_offsets);
    let credit_offsets: Vec<u32> = credit_syms.iter().map(|&s| pool.offset(s)).collect();
    w.section_u32(pack::S_CREDITS, &credit_offsets)?;
    drop(credit_offsets);
    // `credit_syms` survives to the search pass: artist-credit terms are folded into
    // the release-group and recording indexes so that typing an artist name into the
    // albums tab finds their albums. WS/2's `release-group?query=` matches artist
    // names, so a title-only index would be a REGRESSION against the live API the
    // pack replaces -- "radiohead" would return nothing rather than fewer results.
    // 20 MB to keep, and dropped as soon as the index is built.

    let mut buf: Vec<u8> = Vec::new();
    for a in &artists {
        buf.extend_from_slice(&pool.offset(a.name).to_le_bytes());
        buf.extend_from_slice(&pool.offset(a.disamb).to_le_bytes());
        buf.extend_from_slice(&pool.offset(a.area).to_le_bytes());
        buf.extend_from_slice(&a.begin_date.to_le_bytes());
        buf.extend_from_slice(&a.kind.to_le_bytes());
        buf.extend_from_slice(&a.country.to_le_bytes());
    }
    w.section(pack::S_ARTISTS, &buf)?;
    let gids: Vec<Mbid> = artists.iter().map(|a| a.gid).collect();
    let (recs, hi) = build_mbid_table(&gids);
    w.section(pack::S_ARTIST_MBID, &recs)?;
    w.section_u32(pack::S_ARTIST_MBID_HI, &hi)?;
    drop(gids);
    drop(recs);
    drop(hi);
    // Only the name symbols survive, for the search index: 12 MB instead of 118.
    let artist_name_syms: Vec<Sym> = artists.iter().map(|a| a.name).collect();
    drop(artists);

    buf.clear();
    for r in &rgs {
        buf.extend_from_slice(&pool.offset(r.title).to_le_bytes());
        buf.extend_from_slice(&r.first_date.to_le_bytes());
        buf.extend_from_slice(&credit_idx_of(r.credit_id, credit_count).to_le_bytes());
        buf.extend_from_slice(&r.primary.to_le_bytes());
        buf.extend_from_slice(&r.secondary.to_le_bytes());
    }
    w.section(pack::S_RELEASE_GROUPS, &buf)?;
    let gids: Vec<Mbid> = rgs.iter().map(|r| r.gid).collect();
    let (recs, hi) = build_mbid_table(&gids);
    w.section(pack::S_RG_MBID, &recs)?;
    w.section_u32(pack::S_RG_MBID_HI, &hi)?;
    drop(gids);
    drop(recs);
    drop(hi);
    let rg_title_syms: Vec<Sym> = rgs.iter().map(|r| r.title).collect();
    let rg_credit_syms: Vec<Sym> = rgs
        .iter()
        .map(|r| credit_sym_of(&credit_syms, r.credit_id))
        .collect();
    drop(rgs);

    w.section_u32(pack::S_ARTIST_RGS, &artist_rgs)?;
    drop(artist_rgs);
    w.section_u32(pack::S_ARTIST_RGS_IDX, &artist_rgs_idx)?;
    drop(artist_rgs_idx);

    buf.clear();
    for r in &releases {
        buf.extend_from_slice(&pool.offset(r.title).to_le_bytes());
        buf.extend_from_slice(&rg_idx.get(r.rg_id).unwrap_or(pack::NONE).to_le_bytes());
        buf.extend_from_slice(&credit_idx_of(r.credit_id, credit_count).to_le_bytes());
        buf.extend_from_slice(&r.date.to_le_bytes());
        buf.extend_from_slice(&pool.offset(r.disamb).to_le_bytes());
        buf.extend_from_slice(&r.status.to_le_bytes());
        buf.extend_from_slice(&r.country.to_le_bytes());
    }
    w.section(pack::S_RELEASES, &buf)?;
    let gids: Vec<Mbid> = releases.iter().map(|r| r.gid).collect();
    let (recs, hi) = build_mbid_table(&gids);
    w.section(pack::S_RELEASE_MBID, &recs)?;
    w.section_u32(pack::S_RELEASE_MBID_HI, &hi)?;
    drop(gids);
    drop(recs);
    drop(hi);
    drop(releases);

    w.section_u32(pack::S_RG_RELEASES, &rg_releases)?;
    drop(rg_releases);
    w.section_u32(pack::S_RG_RELEASES_IDX, &rg_releases_idx)?;
    drop(rg_releases_idx);
    w.section_u32(pack::S_MEDIA_IDX, &media_idx_csr)?;
    drop(media_idx_csr);
    buf.clear();
    buf.shrink_to_fit();

    // --- tracks, phase 2: cluster recordings and emit TRACKS -------------------
    step!("pass 10: tracks -> TRACKS varint stream");
    let mut rec_new: Vec<u32> = vec![pack::NONE; rec_title.len()];
    let mut rec_order: Vec<u32> = Vec::new();
    let mut rec_first_release: Vec<u32> = Vec::new();
    // TRACKS is streamed straight into the pack rather than buffered: the full
    // varint stream is 196 MB, and it would otherwise stay resident until the very
    // end. `chunk` is flushed every 64 KB, so only that much is ever held.
    let mut chunk: Vec<u8> = Vec::with_capacity(1 << 17);
    let mut tracks_len: u64 = 0;
    let mut track_mbids: Vec<u8> = Vec::new();
    let mut track_idx: Vec<u32> = vec![0u32; media.len() + 1];
    let mut track_count = 0u32;
    let mut missing_recordings = 0u64;
    let mut next_medium = 0usize;
    w.begin(pack::S_TRACKS)?;
    for bucket in &buckets {
        let mut rows: Vec<TrackRow> = Vec::new();
        let mut gids: Vec<Mbid> = Vec::new();
        buf.clear();
        File::open(bucket)?.read_to_end(&mut buf)?;
        for chunk in buf.chunks_exact(spill_len) {
            rows.push(TrackRow::decode(chunk));
            if opts.include_track_mbids {
                let mut g = [0u8; 16];
                g.copy_from_slice(&chunk[TRACK_SPILL_LEN..TRACK_SPILL_LEN_A]);
                gids.push(g);
            }
        }
        let mut order: Vec<u32> = (0..rows.len() as u32).collect();
        // The trailing `i` matters: MusicBrainz does contain more than one track at
        // the same position on the same medium, and without a total order
        // `sort_unstable_by_key` may order those two arbitrarily between runs. That
        // produced two same-sized but byte-different packs from identical input, and
        // the fixture never caught it because it has no tied positions.
        order.sort_unstable_by_key(|&i| {
            (rows[i as usize].medium_idx, rows[i as usize].position, i)
        });
        let mut cursor = 0usize;
        // Every medium up to the last one in this bucket gets its offset written,
        // including the ones with no tracks at all.
        let bucket_last = order
            .last()
            .map(|&i| rows[i as usize].medium_idx as usize)
            .unwrap_or(next_medium.saturating_sub(1));
        while next_medium <= bucket_last && next_medium < media.len() {
            let m = next_medium;
            track_idx[m] = (tracks_len + chunk.len() as u64) as u32;
            let mut ordinal = 0u32;
            let mut prev_rec: i64 = 0;
            while cursor < order.len() && rows[order[cursor] as usize].medium_idx as usize == m {
                let i = order[cursor] as usize;
                let t = rows[i];
                cursor += 1;
                let rid = t.recording_id as usize;
                if rid >= rec_present.len() || !rec_present[rid] {
                    missing_recordings += 1;
                    continue;
                }
                let new_idx = if rec_new[rid] != pack::NONE {
                    rec_new[rid]
                } else {
                    let idx = rec_order.len() as u32;
                    rec_new[rid] = idx;
                    rec_order.push(t.recording_id);
                    rec_first_release.push(media[m].release_idx);
                    idx
                };
                let title_differs = t.title != rec_title[rid];
                let credit_differs = t.credit_id != rec_credit[rid];
                let pos_differs = t.position != (ordinal as i32 + 1);
                let len_differs = t.length_s != 0 && t.length_s != rec_dur[rid];
                if !title_differs {
                    st.tracks_title_same += 1;
                }
                if !credit_differs {
                    st.tracks_credit_same += 1;
                }
                if !len_differs {
                    st.tracks_length_same += 1;
                }
                let flags = (title_differs as u64)
                    | ((credit_differs as u64) << 1)
                    | ((pos_differs as u64) << 2)
                    | ((len_differs as u64) << 3);
                pack::write_uvarint(&mut chunk, flags);
                // The first track of a medium carries an ABSOLUTE recording index so
                // the medium is decodable on its own; TRACK_IDX's byte offset would
                // be useless otherwise.
                if ordinal == 0 {
                    pack::write_uvarint(&mut chunk, new_idx as u64);
                } else {
                    pack::write_zigzag(&mut chunk, new_idx as i64 - prev_rec);
                }
                prev_rec = new_idx as i64;
                if title_differs {
                    pack::write_uvarint(&mut chunk, pool.offset(t.title) as u64);
                }
                if credit_differs {
                    pack::write_uvarint(&mut chunk, t.credit_id as u64);
                }
                if pos_differs {
                    pack::write_uvarint(&mut chunk, t.position.max(0) as u64);
                }
                if len_differs {
                    pack::write_uvarint(&mut chunk, t.length_s as u64);
                }
                if opts.include_track_mbids {
                    track_mbids.extend_from_slice(&gids[i]);
                }
                ordinal += 1;
                track_count += 1;
            }
            // The published count must match what a decode of the run yields, or
            // TRACKTOTAL and the tracklist disagree.
            media[m].track_count = ordinal;
            next_medium += 1;
            // Flushed at medium boundaries, so a medium's run is never split across
            // the boundary bookkeeping and only ~64 KB is ever resident.
            if chunk.len() >= (1 << 16) {
                w.write(&chunk)?;
                tracks_len += chunk.len() as u64;
                chunk.clear();
            }
        }
    }
    // Trailing media with no tracks.
    while next_medium < media.len() {
        track_idx[next_medium] = (tracks_len + chunk.len() as u64) as u32;
        media[next_medium].track_count = 0;
        next_medium += 1;
    }
    track_idx[media.len()] = (tracks_len + chunk.len() as u64) as u32;
    w.write(&chunk)?;
    tracks_len += chunk.len() as u64;
    chunk.clear();
    chunk.shrink_to_fit();
    let tracks_section_len = w.end()?;
    assert_eq!(tracks_section_len, tracks_len, "TRACKS length bookkeeping drifted");
    assert!(
        tracks_len <= u32::MAX as u64,
        "TRACKS is {tracks_len} bytes, past the u32 ceiling TRACK_IDX addresses it with"
    );
    if missing_recordings > 0 {
        st.skipped.push(("tracks whose recording is missing", missing_recordings));
    }
    drop(spill);

    // Standalone recordings (no track anywhere) are appended so recording search
    // can still reach them.
    let tracked = rec_order.len() as u64;
    for id in 0..rec_present.len() {
        if rec_present[id] && rec_new[id] == pack::NONE {
            rec_new[id] = rec_order.len() as u32;
            rec_order.push(id as u32);
            rec_first_release.push(pack::NONE);
        }
    }
    st.standalone_recordings = rec_order.len() as u64 - tracked;

    // --- ISRCs ----------------------------------------------------------------
    let mut isrcs: Vec<(u32, [u8; 7])> = Vec::new();
    if opts.include_isrcs {
        step!("pass 12: ISRCs");
        let mut rejects = 0u64;
        let n = require(
            source,
            "isrc",
            Shape { table: "isrc", min_fields: 5, ints: &[0, 1], uuids: &[] },
            |row| {
                let Some(recording) = row.u32(1) else { return Ok(()) };
                let Some(&new) = rec_new.get(recording as usize) else { return Ok(()) };
                if new == pack::NONE {
                    return Ok(());
                }
                match pack_isrc(row.str(2).trim().as_bytes()) {
                    Some(p) => isrcs.push((new, p)),
                    None => rejects += 1,
                }
                Ok(())
            },
        )?;
        st.rows.push(("isrc", n));
        st.isrc_rejects = rejects;
        isrcs.sort_unstable();
        isrcs.dedup();
    }

    // --- search index ---------------------------------------------------------
    // Two passes over the in-memory entity texts rather than one, because the
    // one-pass version was the peak of the whole build: a `HashMap<String, u32>`
    // dictionary plus the `Vec<(String, u32)>` it is drained into cost ~1.5 GB of
    // duplicated allocations, and 157 M postings held as `Vec<(u32, u32)>` cost
    // another 1.26 GB. Pass A interns terms into a symbol pool (the same
    // open-addressed structure the string pool uses); pass B emits postings with
    // their final sorted term ranks straight into rank-keyed spill buckets. The
    // texts are already in memory, so the second pass costs CPU and no I/O.
    step!("pass 13a: search dictionary");
    let mut terms = StringPool::new();
    let mut terms_buf: Vec<String> = Vec::new();
    for &sym in artist_name_syms.iter() {
        let text = String::from_utf8_lossy(pool.get(sym)).into_owned();
        terms_buf.clear();
        search_terms(&text, &mut terms_buf);
        for t in terms_buf.iter() {
            terms.intern(t);
        }
    }
    for &sym in rg_title_syms.iter() {
        let text = String::from_utf8_lossy(pool.get(sym)).into_owned();
        terms_buf.clear();
        search_terms(&text, &mut terms_buf);
        for t in terms_buf.iter() {
            terms.intern(t);
        }
    }
    // Every artist-credit display string, interned once. That covers the credits of
    // both release groups and recordings without walking 39.9 M recordings here --
    // 5.08 M strings instead. The postings pass below attaches them per entity.
    for &sym in credit_syms.iter() {
        if sym == SYM_EMPTY {
            continue;
        }
        let text = String::from_utf8_lossy(pool.get(sym)).into_owned();
        terms_buf.clear();
        search_terms(&text, &mut terms_buf);
        for t in terms_buf.iter() {
            terms.intern(t);
        }
    }
    if opts.include_recording_search {
        for id in rec_order.iter() {
            let text = String::from_utf8_lossy(pool.get(rec_title[*id as usize])).into_owned();
            terms_buf.clear();
            search_terms(&text, &mut terms_buf);
            for t in terms_buf.iter() {
                terms.intern(t);
            }
        }
    }
    // `finalize` would consume the pool, and pass B still needs to look terms up,
    // so the sorted order is taken without consuming it. Alphabetical order is
    // exactly what SEARCH_TERMS needs for the reader's binary search.
    let term_count = terms.distinct();
    let term_order = terms.sorted_order();
    let mut term_ranks = vec![0u32; term_count];
    for (rank, &sym) in term_order.iter().enumerate() {
        term_ranks[sym as usize] = rank as u32;
    }

    step!("pass 13b: search postings ({term_count} terms)");
    let mut post_spill = PostingSpill::new(opts.work_dir.as_deref(), term_count as u32)?;
    {
        for (i, &sym) in artist_name_syms.iter().enumerate() {
            check_searchable(i as u32)?;
            let text = String::from_utf8_lossy(pool.get(sym)).into_owned();
            emit_postings(
                &text,
                "",
                (pack::KIND_ARTIST << pack::KIND_SHIFT) | i as u32,
                &terms,
                &term_ranks,
                &mut terms_buf,
                &mut post_spill,
            )?;
        }
        for (i, &sym) in rg_title_syms.iter().enumerate() {
            check_searchable(i as u32)?;
            let refv = (pack::KIND_RELEASE_GROUP << pack::KIND_SHIFT) | i as u32;
            let title = String::from_utf8_lossy(pool.get(sym)).into_owned();
            let credit = match rg_credit_syms[i] {
                SYM_EMPTY => String::new(),
                csym => String::from_utf8_lossy(pool.get(csym)).into_owned(),
            };
            emit_postings(
                &title,
                &credit,
                refv,
                &terms,
                &term_ranks,
                &mut terms_buf,
                &mut post_spill,
            )?;
        }
        if opts.include_recording_search {
            for (i, id) in rec_order.iter().enumerate() {
                check_searchable(i as u32)?;
                let refv = (pack::KIND_RECORDING << pack::KIND_SHIFT) | i as u32;
                let title =
                    String::from_utf8_lossy(pool.get(rec_title[*id as usize])).into_owned();
                let credit = match credit_sym_of(&credit_syms, rec_credit[*id as usize]) {
                    SYM_EMPTY => String::new(),
                    csym => String::from_utf8_lossy(pool.get(csym)).into_owned(),
                };
                emit_postings(
                    &title,
                    &credit,
                    refv,
                    &terms,
                    &term_ranks,
                    &mut terms_buf,
                    &mut post_spill,
                )?;
            }
        }
    }
    drop(term_ranks);
    drop(artist_name_syms);
    drop(rg_title_syms);
    drop(rg_credit_syms);
    drop(credit_syms);
    st.search_postings = post_spill.written / 8;
    let post_buckets = post_spill.finish()?;

    // The pack writer opens here rather than after the search index, so
    // SEARCH_POSTINGS can be STREAMED bucket by bucket instead of accumulating a
    // ~300 MB buffer while the string pool is still resident. Section order in the
    // file is irrelevant -- the directory carries absolute offsets.
    step!("pass 14: writing");
    // Captured before the sources are dropped further down.
    let rec_max_id = rec_present.len().saturating_sub(1) as u64;
    let rec_live = rec_present.iter().filter(|&&p| p).count() as u64;

    // SEARCH_TERMS, streamed straight out of the term pool in rank order.
    w.begin(pack::S_SEARCH_TERMS)?;
    let mut chunk: Vec<u8> = Vec::with_capacity(1 << 16);
    for &sym in &term_order {
        chunk.extend_from_slice(terms.get(sym));
        chunk.push(0);
        if chunk.len() >= (1 << 16) {
            w.write(&chunk)?;
            chunk.clear();
        }
    }
    w.write(&chunk)?;
    w.end()?;

    // SEARCH_POSTINGS, streamed; SEARCH_TERM_IDX accumulates the two offsets per
    // term as we go, which is only 8 bytes a term.
    let mut term_index: Vec<u8> = Vec::with_capacity((term_count + 1) * 8);
    let mut term_off = 0u32;
    let mut post_off = 0u32;
    let mut rank = 0u32;
    let mut spill_buf: Vec<u8> = Vec::new();
    w.begin(pack::S_SEARCH_POSTINGS)?;
    for (b, bucket) in post_buckets.iter().enumerate() {
        spill_buf.clear();
        File::open(bucket)?.read_to_end(&mut spill_buf)?;
        let mut pairs: Vec<(u32, u32)> = spill_buf
            .chunks_exact(8)
            .map(|c| {
                (
                    u32::from_le_bytes([c[0], c[1], c[2], c[3]]),
                    u32::from_le_bytes([c[4], c[5], c[6], c[7]]),
                )
            })
            .collect();
        pairs.sort_unstable();
        pairs.dedup();
        let bucket_end = post_spill.bucket_end(b);
        let mut out_bytes: Vec<u8> = Vec::new();
        let mut p = 0usize;
        while rank < bucket_end {
            term_index.extend_from_slice(&term_off.to_le_bytes());
            term_index.extend_from_slice(&post_off.to_le_bytes());
            term_off += terms.get(term_order[rank as usize]).len() as u32 + 1;
            let start = p;
            while p < pairs.len() && pairs[p].0 == rank {
                p += 1;
            }
            let before = out_bytes.len();
            pack::write_uvarint(&mut out_bytes, (p - start) as u64);
            let mut prev = 0u32;
            for &(_, refv) in &pairs[start..p] {
                pack::write_uvarint(&mut out_bytes, (refv - prev) as u64);
                prev = refv;
            }
            post_off += (out_bytes.len() - before) as u32;
            rank += 1;
        }
        w.write(&out_bytes)?;
    }
    w.end()?;
    term_index.extend_from_slice(&term_off.to_le_bytes());
    term_index.extend_from_slice(&post_off.to_le_bytes());
    w.section(pack::S_SEARCH_TERM_IDX, &term_index)?;
    drop(term_index);
    drop(post_spill);
    drop(spill_buf);
    drop(term_order);
    drop(terms);

    // --- assemble -------------------------------------------------------------
    // Everything not already on disk. The entity vectors went out in pass 9b and
    // are gone; what remains is keyed off the recording clustering, which only
    // exists after the track pass.
    let media_count = media.len() as u32;
    let counts = HeaderCounts {
        artists: artist_count,
        credits: credit_count,
        release_groups: rg_count,
        releases: release_count,
        media: media_count,
        tracks: track_count,
        recordings: rec_order.len() as u32,
        isrcs: isrcs.len() as u32,
        search_terms: term_count as u32,
        enums: enum_count,
    };
    let mut buf: Vec<u8> = Vec::new();
    buf.clear();
    for m in &media {
        buf.extend_from_slice(&m.format.to_le_bytes());
        buf.extend_from_slice(&(m.track_count.min(u16::MAX as u32) as u16).to_le_bytes());
    }
    w.section(pack::S_MEDIA, &buf)?;
    drop(media);
    w.section_u32(pack::S_TRACK_IDX, &track_idx)?;
    drop(track_idx);
    if opts.include_track_mbids {
        w.section(pack::S_TRACK_MBID, &track_mbids)?;
    }
    drop(track_mbids);

    buf.clear();
    for id in &rec_order {
        let i = *id as usize;
        let (lo, hi) = encode_recording_credit(credit_idx_of(rec_credit[i], credit_count));
        buf.extend_from_slice(&pool.offset(rec_title[i]).to_le_bytes());
        buf.extend_from_slice(&rec_dur[i].to_le_bytes());
        buf.extend_from_slice(&lo.to_le_bytes());
        buf.push(hi);
    }
    w.section(pack::S_RECORDINGS, &buf)?;
    buf.clear();
    buf.shrink_to_fit();
    // Dead from here: RECORDINGS was their only remaining consumer, and the search
    // index is already written.
    drop(rec_title);
    drop(rec_credit);
    drop(rec_dur);
    drop(rec_present);
    let recording_count = rec_order.len();
    drop(rec_order);
    if opts.include_recording_mbids {
        // Deliberately built HERE rather than earlier: this is a 638 MB buffer, and
        // running the pass at its natural place in the order would have kept it
        // resident alongside the search index. A second scan of `recording` is far
        // cheaper than that overlap -- and cheaper still than holding 16 B per
        // recording id (~760 MB) through the whole build, which is what scattering
        // into this buffer avoids.
        step!("pass 14b: recording MBIDs");
        let mut recording_mbids = vec![0u8; recording_count * 16];
        require(
            source,
            "recording",
            Shape { table: "recording", min_fields: 9, ints: &[0, 3], uuids: &[1] },
            |row| {
                let Some(id) = row.u32(0) else { return Ok(()) };
                let Some(&new) = rec_new.get(id as usize) else { return Ok(()) };
                if new == pack::NONE {
                    return Ok(());
                }
                if let Some(gid) = parse_mbid(&row.str(1)) {
                    let at = new as usize * 16;
                    recording_mbids[at..at + 16].copy_from_slice(&gid);
                }
                Ok(())
            },
        )?;
        w.section(pack::S_RECORDING_MBID, &recording_mbids)?;
    }
    w.section_u32(pack::S_REC_FIRST_RELEASE, &rec_first_release)?;
    drop(rec_first_release);
    drop(rec_new);

    if opts.include_isrcs {
        buf.clear();
        for (recording, packed) in &isrcs {
            buf.extend_from_slice(&recording.to_le_bytes());
            buf.extend_from_slice(packed);
        }
        w.section(pack::S_ISRCS, &buf)?;
    }
    let isrc_count = isrcs.len();
    let _ = isrc_count;
    drop(isrcs);
    buf.clear();
    buf.shrink_to_fit();

    // STRINGS goes last: it is only complete now, and streaming it here means the
    // sorted pool is never materialised as a second buffer.
    step!("pass 15: STRINGS ({} MB raw)", pool.raw_len() / 1_000_000);
    let mut string_blocks = 0u32;
    if opts.compress_strings {
        let mut bw = StringBlockWriter::new();
        pool.write_strings(|s| bw.push(s))?;
        let (compressed, block_offsets) = bw.finish()?;
        st.string_pool_stored = compressed.len() as u64;
        string_blocks = (block_offsets.len() - 1) as u32;
        w.section(pack::S_STRINGS, &compressed)?;
        w.section_u32(pack::S_STRINGS_BLKIDX, &block_offsets)?;
    } else {
        w.begin(pack::S_STRINGS)?;
        let mut chunk: Vec<u8> = Vec::with_capacity(1 << 16);
        pool.write_strings(|s| {
            chunk.extend_from_slice(s);
            if chunk.len() >= (1 << 16) {
                let taken = std::mem::take(&mut chunk);
                w.write(&taken)?;
                chunk = taken;
                chunk.clear();
            }
            Ok(())
        })?;
        w.write(&chunk)?;
        st.string_pool_stored = w.end()?;
    }

    w.set_header(&counts, opts.flags(), source.dump_date(), string_blocks);
    st.string_blocks = string_blocks;
    st.sections = w
        .section_sizes()
        .into_iter()
        .map(|(i, len)| (pack::SECTION_NAMES[i], len))
        .collect();
    st.dense_map_bytes =
        artist_idx.bytes() + rg_idx.bytes() + release_idx.bytes() + medium_idx.bytes();
    st.max_ids = vec![
        ("artist", artist_idx.max_id(), artist_idx.live()),
        ("artist_credit", credit_count.saturating_sub(1) as u64, credit_live),
        ("release_group", rg_idx.max_id(), rg_idx.live()),
        ("release", release_idx.max_id(), release_idx.live()),
        ("medium", medium_idx.max_id(), medium_idx.live()),
        ("recording", rec_max_id, rec_live),
    ];
    st.total_bytes = w.finish()?;
    Ok(st)
}

fn check_searchable(idx: u32) -> io::Result<()> {
    if idx > pack::MAX_SEARCHABLE_IDX {
        return Err(other_err(format!(
            "entity index {idx} exceeds the {} a search posting can address; the two kind \
             bits would silently corrupt it",
            pack::MAX_SEARCHABLE_IDX
        )));
    }
    Ok(())
}

/// `medium.position` and `track.position` are Postgres INTEGERs. Anything outside
/// that range is corrupt input, not a wider position.
fn clamp_position(v: Option<i64>) -> i32 {
    match v {
        Some(v) => v.clamp(i32::MIN as i64, i32::MAX as i64) as i32,
        None => 0,
    }
}

fn credit_idx_of(credit_id: u32, credit_count: u32) -> u32 {
    if credit_id == pack::NONE || credit_id >= credit_count {
        pack::NONE
    } else {
        credit_id
    }
}

/// MusicBrainz stores lengths in milliseconds; the pack stores seconds because
/// the UI renders m:ss, Tidal matching uses a ±3 s tolerance and LRCLIB takes
/// seconds. Clamped rather than wrapped: an 18-hour recording is rare but real.
fn ms_to_secs(ms: Option<i64>) -> u16 {
    match ms {
        Some(ms) if ms > 0 => (ms.saturating_add(500) / 1000).min(u16::MAX as i64) as u16,
        _ => 0,
    }
}

fn to_csr(pairs: &[(u32, u32)], parents: usize) -> (Vec<u32>, Vec<u32>) {
    let mut idx = vec![0u32; parents + 1];
    for &(p, _) in pairs {
        idx[p as usize + 1] += 1;
    }
    for i in 0..parents {
        idx[i + 1] += idx[i];
    }
    (pairs.iter().map(|&(_, c)| c).collect(), idx)
}

fn read_enum_table(
    source: &dyn TableSource,
    table: &'static str,
    pool: &mut StringPool,
    enums: &mut EnumPool,
) -> io::Result<HashMap<u32, u16>> {
    let mut out = HashMap::new();
    require(source, table, Shape { table, min_fields: 2, ints: &[0], uuids: &[] }, |row| {
        if let Some(id) = row.u32(0) {
            out.insert(id, enums.intern(pool, &row.str(1)));
        }
        Ok(())
    })?;
    Ok(out)
}

/// Read a table that must be present, checking its shape on the first row.
fn require<F>(
    source: &dyn TableSource,
    table: &'static str,
    shape: Shape,
    mut f: F,
) -> io::Result<u64>
where
    F: FnMut(&Row<'_>) -> io::Result<()>,
{
    let Some(reader) = source.open_table(table)? else {
        return Err(other_err(format!(
            "table `{table}` is missing from {}. The core mbdump.tar.bz2 contains it as \
             `mbdump/{table}`; a derived or partial dump does not.",
            source.describe()
        )));
    };
    let mut first = true;
    each_row(reader, |row| {
        if first {
            shape.check(row)?;
            first = false;
        }
        f(row)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn track_spill_record_stays_small() {
        // 57.4 M of these go through the spill; the README quotes this width.
        assert_eq!(TRACK_SPILL_LEN, 24);
        assert_eq!(TRACK_SPILL_LEN_A, TRACK_SPILL_LEN + 16);
    }

    #[test]
    fn track_rows_round_trip_through_the_spill_encoding() {
        let t = TrackRow {
            medium_idx: 1_234_567,
            position: -3,
            recording_id: 39_881_257,
            title: 99,
            credit_id: pack::NONE,
            length_s: 425,
        };
        let mut b = [0u8; TRACK_SPILL_LEN];
        t.encode(&mut b);
        let d = TrackRow::decode(&b);
        assert_eq!(d.medium_idx, t.medium_idx);
        assert_eq!(d.position, t.position);
        assert_eq!(d.recording_id, t.recording_id);
        assert_eq!(d.title, t.title);
        assert_eq!(d.credit_id, t.credit_id);
        assert_eq!(d.length_s, t.length_s);
    }

    #[test]
    fn positions_clamp_instead_of_wrapping() {
        assert_eq!(clamp_position(None), 0);
        assert_eq!(clamp_position(Some(0)), 0);
        assert_eq!(clamp_position(Some(-1)), -1);
        assert_eq!(clamp_position(Some(i64::MAX)), i32::MAX);
        assert_eq!(clamp_position(Some(i64::MIN)), i32::MIN);
    }

    #[test]
    fn ms_round_to_nearest_second_and_clamp() {
        assert_eq!(ms_to_secs(None), 0);
        assert_eq!(ms_to_secs(Some(0)), 0);
        assert_eq!(ms_to_secs(Some(499)), 0);
        assert_eq!(ms_to_secs(Some(500)), 1);
        assert_eq!(ms_to_secs(Some(67_000)), 67);
        assert_eq!(ms_to_secs(Some(i64::MAX)), u16::MAX, "an 18-hour recording clamps");
    }

    #[test]
    fn csr_is_a_prefix_sum() {
        let (vals, idx) = to_csr(&[(0, 7), (0, 8), (2, 9)], 3);
        assert_eq!(vals, [7, 8, 9]);
        assert_eq!(idx, [0, 2, 2, 3]);
    }

    #[test]
    fn dense_map_rejects_absurd_keys() {
        let mut m = DenseMap::new();
        m.set(5, 1).unwrap();
        assert_eq!(m.get(5), Some(1));
        assert_eq!(m.get(4), None);
        assert_eq!(m.get(9_999), None);
        assert!(m.set(MAX_ID + 1, 0).is_err());
    }

    #[test]
    fn spill_buckets_are_monotonic_in_medium() {
        let s = TrackSpill::new(Some(&std::env::temp_dir()), 640_000, TRACK_SPILL_LEN).unwrap();
        assert!(s.buckets > 1, "a full-scale medium count must use many buckets");
        let mut prev = 0usize;
        for m in [0u32, 1, 10_000, 320_000, 639_999] {
            let b = s.bucket_of(m);
            assert!(b >= prev, "buckets must not go backwards: medium {m} -> {b}");
            assert!(b < s.buckets);
            prev = b;
        }
    }

    #[test]
    fn posting_buckets_and_ends_are_exact_inverses() {
        // If `bucket_of` and `bucket_end` disagree by even one rank, the emit loop
        // either skips a term's postings or attributes them to the wrong term.
        for terms in [1u32, 2, 63, 64, 65, 100_000, 4_332_477] {
            let s = PostingSpill::new(None, terms).unwrap();
            let mut expected = 0u32;
            for b in 0..s.buckets {
                let end = s.bucket_end(b);
                assert!(end >= expected, "bucket ends must not go backwards");
                for rank in expected..end {
                    assert_eq!(
                        s.bucket_of(rank),
                        b,
                        "terms={terms} rank={rank} belongs in bucket {b}"
                    );
                }
                expected = end;
            }
            assert_eq!(expected, terms, "buckets must cover every rank exactly once");
        }
    }

    #[test]
    fn concurrent_spills_do_not_share_a_directory() {
        let a = TrackSpill::new(None, 10, TRACK_SPILL_LEN).unwrap();
        let b = TrackSpill::new(None, 10, TRACK_SPILL_LEN).unwrap();
        assert_ne!(a.dir, b.dir, "two builds in one process must not collide");
    }
}
