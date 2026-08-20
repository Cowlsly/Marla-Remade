//! The ingest passes: mbdump tables in, one `.pack` out.
//!
//! Column orders are hard-coded from the MusicBrainz schema's
//! `admin/sql/CreateTables.sql` and every table's arity and distinctive columns
//! are checked on the first row (see `copy::Shape`), because a silently shifted
//! column would produce a plausible-looking but wrong pack.
//!
//! # Memory
//!
//! Peak RSS is the binding constraint, not wall time. Two rules:
//!
//!   * per-row data is never accumulated for the output. `TRACKS` is built as a
//!     varint byte stream as the tracks are visited, not as a `Vec<TrackRec>` —
//!     that is the failure recorded in `scripts/maps/gtfs_ingest/src/index.rs:15-27`,
//!     where a fixed-size record per stop per trip made a world pack 10-20 GB.
//!   * id -> index maps are dense `Vec<u32>` keyed by the dump's integer primary
//!     key, not `HashMap<[u8;16], u32>` keyed by MBID. The dumps use integer
//!     foreign keys, so at full scale the recording map is ~180 MB instead of the
//!     ~2 GB a gid hash map would cost.
//!
//! One buffer is genuinely large and unavoidable without an external sort: the
//! `track` rows have to be grouped by medium, and the dump is in primary-key
//! order, so 57.4 M x 24 B = ~1.4 GB is held while sorting. `BuildStats` reports
//! it. An external merge sort would remove it if a full run ever needs to fit in
//! less.

use std::collections::HashMap;
use std::io::{self, Seek, Write};

use crate::copy::{other_err, Input, Row, Shape};
use crate::pack::{
    self, build_mbid_table, pack_date, pack_isrc, parse_mbid, search_terms, EnumPool, HeaderCounts,
    Mbid, PackWriter, StringPool,
};
use crate::reader::encode_recording_credit;

/// Guard against a hostile or corrupt dump asking us to allocate a dense map for
/// an absurd primary key.
const MAX_ID: u32 = 400_000_000;

/// Every table this crate reads, in the order the passes want them. All of these
/// live in the core `mbdump.tar.bz2`; none needs `mbdump-derived.tar.bz2`.
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
    "release_group_meta",
    "release_group_secondary_type_join",
    "release",
    "release_country",
    "release_unknown_country",
    "medium",
    "recording",
    "track",
    "isrc",
];

#[derive(Clone, Copy)]
pub struct BuildOptions {
    pub include_track_mbids: bool,
    pub include_recording_mbids: bool,
    pub include_isrcs: bool,
    pub include_recording_search: bool,
    pub official_only: bool,
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
    pub string_pool_bytes: usize,
    pub recording_titles: u64,
    pub distinct_recording_titles: u64,
    pub tracks_title_same: u64,
    pub tracks_credit_same: u64,
    pub tracks_length_same: u64,
    pub standalone_recordings: u64,
    pub skipped: Vec<(&'static str, u64)>,
    pub isrc_rejects: u64,
    pub track_sort_buffer_bytes: u64,
    pub dense_map_bytes: u64,
    pub search_postings: u64,
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
        let recs =
            self.rows.iter().find(|(n, _)| *n == "recording").map(|(_, n)| *n).unwrap_or(0);
        s.push_str(&format!(
            "  {:<38} {:>11.1}%  ({})\n",
            "recordings with no track (standalone)",
            pct(self.standalone_recordings, recs),
            self.standalone_recordings
        ));
        s.push_str("build cost\n");
        s.push_str(&format!(
            "  {:<38} {:>12.1} MB\n",
            "string pool (raw)",
            mb(self.string_pool_bytes as u64)
        ));
        s.push_str(&format!("  {:<38} {:>12}\n", "distinct strings", self.distinct_strings));
        s.push_str(&format!("  {:<38} {:>12}\n", "search postings", self.search_postings));
        s.push_str(&format!(
            "  {:<38} {:>12.1} MB\n",
            "track sort buffer (peak, transient)",
            mb(self.track_sort_buffer_bytes)
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
}

// --- intermediate rows ---

struct ArtistBuild {
    gid: Mbid,
    name_off: u32,
    disamb_off: u32,
    area_off: u32,
    begin_date: u32,
    kind: u16,
    country: u16,
    id: u32,
}

struct RgBuild {
    gid: Mbid,
    title_off: u32,
    first_date: u32,
    credit_id: u32,
    primary: u16,
    secondary: u16,
    secondary_src_id: u32,
    id: u32,
}

struct ReleaseBuild {
    gid: Mbid,
    title_off: u32,
    rg_id: u32,
    credit_id: u32,
    date: u32,
    disamb_off: u32,
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

#[derive(Clone, Copy)]
struct TrackBuild {
    medium_idx: u32,
    /// `i32`, not `i64`: `track.position` is a Postgres INTEGER, and the wider
    /// field would push this record from 28 to 32 bytes, which is 240 MB of peak
    /// RSS across 57.4 M rows.
    position: i32,
    recording_id: u32,
    title_off: u32,
    credit_id: u32,
    length_s: u16,
    /// Index into `track_gids`, which is only populated for tier A. Kept out of
    /// this record so a tier B build does not carry 16 B x 57 M of MBIDs it is
    /// never going to write.
    gid_slot: u32,
}

#[derive(Clone, Copy, Default)]
struct RecBuild {
    title_off: u32,
    credit_id: u32,
    dur_s: u16,
}

/// Build a pack from `input` and write it to `out`.
pub fn build<W: Write + Seek>(
    input: &Input,
    out: W,
    opts: &BuildOptions,
    log: &mut dyn Write,
) -> io::Result<BuildStats> {
    let mut st = BuildStats::default();
    let mut pool = StringPool::new();
    let mut enums = EnumPool::new();
    macro_rules! step {
        ($($arg:tt)*) => { if opts.verbose { writeln!(log, $($arg)*)?; } };
    }

    // --- areas and the small enumerations -------------------------------------
    step!("pass 1: areas, country codes, enumerations");
    let mut area_name: HashMap<u32, u32> = HashMap::new();
    let n = require(
        input,
        "area",
        Shape { table: "area", min_fields: 14, ints: &[0], uuids: &[1] },
        |row| {
            if let Some(id) = row.u32(0) {
                let off = pool.intern(&row.str(2));
                area_name.insert(id, off);
            }
            Ok(())
        },
    )?;
    st.rows.push(("area", n));

    let mut area_country: HashMap<u32, u16> = HashMap::new();
    let n = require(
        input,
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

    let artist_types = read_enum_table(input, "artist_type", &mut pool, &mut enums)?;
    let rg_primary = read_enum_table(input, "release_group_primary_type", &mut pool, &mut enums)?;
    let rg_secondary =
        read_enum_table(input, "release_group_secondary_type", &mut pool, &mut enums)?;
    let statuses = read_enum_table(input, "release_status", &mut pool, &mut enums)?;
    let formats = read_enum_table(input, "medium_format", &mut pool, &mut enums)?;

    // --- artist credits -------------------------------------------------------
    // A credit is stored as one pre-rendered display string ("A feat. B"), because
    // nothing in the app reads the per-artist breakdown -- only
    // MbArtistCredit.display(), which concatenates name + joinphrase. MusicBrainz
    // already stores exactly that string as `artist_credit.name`, so we take it
    // rather than re-deriving it from artist_credit_name and risking a different
    // join order. artist_credit_name is still needed, for the artist -> release
    // group links.
    step!("pass 2: artist credits");
    let mut credits_off: Vec<u32> = Vec::new();
    let n = require(
        input,
        "artist_credit",
        Shape { table: "artist_credit", min_fields: 7, ints: &[0, 2], uuids: &[6] },
        |row| {
            let Some(id) = row.u32(0) else { return Ok(()) };
            if id > MAX_ID {
                return Err(other_err(format!("artist_credit id {id} exceeds {MAX_ID}")));
            }
            let i = id as usize;
            if credits_off.len() <= i {
                credits_off.resize(i + 1, 0);
            }
            credits_off[i] = pool.intern(&row.str(1));
            Ok(())
        },
    )?;
    st.rows.push(("artist_credit", n));
    let credit_count = credits_off.len() as u32;

    let mut credit_artists: Vec<Vec<u32>> = vec![Vec::new(); credits_off.len()];
    let n = require(
        input,
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
        input,
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
                name_off: pool.intern(&row.str(2)),
                disamb_off: pool.intern(&row.str(13)),
                area_off: area.and_then(|a| area_name.get(&a).copied()).unwrap_or(0),
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
        input,
        "release_group",
        Shape { table: "release_group", min_fields: 8, ints: &[0, 3], uuids: &[1] },
        |row| {
            let Some(id) = row.u32(0) else { return Ok(()) };
            let Some(gid) = parse_mbid(&row.str(1)) else { return Ok(()) };
            rgs.push(RgBuild {
                gid,
                title_off: pool.intern(&row.str(2)),
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
        input,
        "release_group_meta",
        Shape { table: "release_group_meta", min_fields: 7, ints: &[0], uuids: &[] },
        |row| {
            if let Some(slot) = row.u32(0).and_then(|id| rg_slot.get(id)) {
                rgs[slot as usize].first_date = pack_date(row.i64(2), row.i64(3), row.i64(4));
            }
            Ok(())
        },
    )?;
    st.rows.push(("release_group_meta", n));
    let n = require(
        input,
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

    // ARTIST_RGS: artist -> release groups, via the release group's credit.
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

    // --- releases -------------------------------------------------------------
    step!("pass 5: releases");
    let mut releases: Vec<ReleaseBuild> = Vec::new();
    let mut filtered_status = 0u64;
    let n = require(
        input,
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
                title_off: pool.intern(&row.str(2)),
                rg_id: rg,
                credit_id: row.u32(3).unwrap_or(pack::NONE),
                date: 0,
                disamb_off: pool.intern(&row.str(10)),
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
        input,
        "release_country",
        Shape { table: "release_country", min_fields: 5, ints: &[0, 1], uuids: &[] },
        |row| {
            let Some(slot) = row.u32(0).and_then(|id| release_slot.get(id)) else { return Ok(()) };
            let d = pack_date(row.i64(2), row.i64(3), row.i64(4));
            let r = &mut releases[slot as usize];
            if r.date == 0 || (d != 0 && d < r.date) {
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
        input,
        "release_unknown_country",
        Shape { table: "release_unknown_country", min_fields: 4, ints: &[0], uuids: &[] },
        |row| {
            let Some(slot) = row.u32(0).and_then(|id| release_slot.get(id)) else { return Ok(()) };
            let d = pack_date(row.i64(1), row.i64(2), row.i64(3));
            let r = &mut releases[slot as usize];
            if r.date == 0 || (d != 0 && d < r.date) {
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
    rg_release_pairs.sort_unstable_by(|x, y| {
        let (a, b) = (&releases[x.1 as usize], &releases[y.1 as usize]);
        x.0.cmp(&y.0)
            .then(a.status_rank.cmp(&b.status_rank))
            .then(a.date.cmp(&b.date))
            .then(x.1.cmp(&y.1))
    });
    let (rg_releases, rg_releases_idx) = to_csr(&rg_release_pairs, rgs.len());
    drop(rg_release_pairs);

    // --- media ----------------------------------------------------------------
    step!("pass 6: media");
    let mut media: Vec<MediumBuild> = Vec::new();
    let mut dangling_media = 0u64;
    let n = require(
        input,
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
    step!("pass 7: recordings");
    let mut rec_rows: Vec<RecBuild> = Vec::new();
    let mut rec_gids: Vec<Mbid> = Vec::new();
    let mut rec_present: Vec<bool> = Vec::new();
    let mut distinct_titles: std::collections::HashSet<u32> = std::collections::HashSet::new();
    let n = require(
        input,
        "recording",
        Shape { table: "recording", min_fields: 9, ints: &[0, 3], uuids: &[1] },
        |row| {
            let Some(id) = row.u32(0) else { return Ok(()) };
            let Some(gid) = parse_mbid(&row.str(1)) else { return Ok(()) };
            if id > MAX_ID {
                return Err(other_err(format!("recording id {id} exceeds {MAX_ID}")));
            }
            let i = id as usize;
            if rec_rows.len() <= i {
                rec_rows.resize(i + 1, RecBuild::default());
                rec_gids.resize(i + 1, [0u8; 16]);
                rec_present.resize(i + 1, false);
            }
            let title_off = pool.intern(&row.str(2));
            distinct_titles.insert(title_off);
            rec_rows[i] = RecBuild {
                title_off,
                credit_id: row.u32(3).unwrap_or(pack::NONE),
                dur_s: ms_to_secs(row.i64(4)),
            };
            rec_gids[i] = gid;
            rec_present[i] = true;
            Ok(())
        },
    )?;
    st.rows.push(("recording", n));
    st.recording_titles = n;
    st.distinct_recording_titles = distinct_titles.len() as u64;
    drop(distinct_titles);

    // --- tracks ---------------------------------------------------------------
    // The dump is in track-id order but TRACKS must be grouped by medium, so the
    // rows are buffered and sorted once. This is the only large transient buffer.
    step!("pass 8: tracks");
    let mut tracks: Vec<TrackBuild> = Vec::new();
    let mut track_gids: Vec<Mbid> = Vec::new();
    let mut dangling_tracks = 0u64;
    let mut bad_track_gids = 0u64;
    let n = require(
        input,
        "track",
        Shape { table: "track", min_fields: 12, ints: &[0, 2, 3, 4, 7], uuids: &[1] },
        |row| {
            let (Some(rec), Some(med)) = (row.u32(2), row.u32(3)) else { return Ok(()) };
            let Some(mi) = medium_idx.get(med) else {
                dangling_tracks += 1;
                return Ok(());
            };
            let gid_slot = if opts.include_track_mbids {
                match parse_mbid(&row.str(1)) {
                    Some(g) => {
                        track_gids.push(g);
                        track_gids.len() as u32 - 1
                    }
                    None => {
                        bad_track_gids += 1;
                        return Ok(());
                    }
                }
            } else {
                0
            };
            tracks.push(TrackBuild {
                medium_idx: mi,
                position: clamp_position(row.i64(4)),
                recording_id: rec,
                title_off: pool.intern(&row.str(6)),
                credit_id: row.u32(7).unwrap_or(pack::NONE),
                length_s: ms_to_secs(row.i64(8)),
                gid_slot,
            });
            Ok(())
        },
    )?;
    st.rows.push(("track", n));
    if dangling_tracks > 0 {
        st.skipped.push(("tracks whose medium was filtered or missing", dangling_tracks));
    }
    if bad_track_gids > 0 {
        st.skipped.push(("tracks with an unparseable gid", bad_track_gids));
    }
    st.track_sort_buffer_bytes = (tracks.len() * std::mem::size_of::<TrackBuild>()) as u64;
    tracks.sort_unstable_by(|a, b| {
        a.medium_idx.cmp(&b.medium_idx).then(a.position.cmp(&b.position))
    });

    // Assign recording indices in first-appearance order (design doc §4.3): a
    // tracklist then references consecutive recordings, so d_recording is mostly
    // 1 and TRACKS stays around 2 B a track.
    let mut rec_new: Vec<u32> = vec![pack::NONE; rec_rows.len()];
    let mut rec_order: Vec<u32> = Vec::new();
    let mut rec_first_release: Vec<u32> = Vec::new();
    let mut tracks_stream: Vec<u8> = Vec::new();
    let mut track_mbids: Vec<u8> = Vec::new();
    let mut track_idx: Vec<u32> = vec![0u32; media.len() + 1];
    let mut track_count = 0u32;
    let mut cursor = 0usize;
    let mut missing_recordings = 0u64;
    for m in 0..media.len() {
        track_idx[m] = tracks_stream.len() as u32;
        let mut ordinal = 0u32;
        let mut prev_rec: i64 = 0;
        while cursor < tracks.len() && tracks[cursor].medium_idx as usize == m {
            let t = tracks[cursor];
            cursor += 1;
            let rid = t.recording_id as usize;
            if rid >= rec_rows.len() || !rec_present[rid] {
                missing_recordings += 1;
                continue;
            }
            let new_idx = if rec_new[rid] != pack::NONE {
                rec_new[rid]
            } else {
                let i = rec_order.len() as u32;
                rec_new[rid] = i;
                rec_order.push(t.recording_id);
                rec_first_release.push(media[m].release_idx);
                i
            };
            let rec = rec_rows[rid];
            let title_differs = t.title_off != rec.title_off;
            let credit_differs = t.credit_id != rec.credit_id;
            let pos_differs = t.position != (ordinal as i32 + 1);
            let len_differs = t.length_s != 0 && t.length_s != rec.dur_s;
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
            pack::write_uvarint(&mut tracks_stream, flags);
            if ordinal == 0 {
                pack::write_uvarint(&mut tracks_stream, new_idx as u64);
            } else {
                pack::write_zigzag(&mut tracks_stream, new_idx as i64 - prev_rec);
            }
            prev_rec = new_idx as i64;
            if title_differs {
                pack::write_uvarint(&mut tracks_stream, t.title_off as u64);
            }
            if credit_differs {
                pack::write_uvarint(&mut tracks_stream, t.credit_id as u64);
            }
            if pos_differs {
                pack::write_uvarint(&mut tracks_stream, t.position.max(0) as u64);
            }
            if len_differs {
                pack::write_uvarint(&mut tracks_stream, t.length_s as u64);
            }
            if opts.include_track_mbids {
                track_mbids.extend_from_slice(&track_gids[t.gid_slot as usize]);
            }
            ordinal += 1;
            track_count += 1;
        }
        // The stored track_count must match what a decode of the run yields, or
        // TRACKTOTAL and the tracklist disagree.
        media[m].track_count = ordinal;
    }
    track_idx[media.len()] = tracks_stream.len() as u32;
    if missing_recordings > 0 {
        st.skipped.push(("tracks whose recording is missing", missing_recordings));
    }
    drop(tracks);
    drop(track_gids);

    // Standalone recordings (no track anywhere) are appended so recording search
    // can still reach them.
    let tracked = rec_order.len() as u64;
    for id in 0..rec_rows.len() {
        if rec_present[id] && rec_new[id] == pack::NONE {
            rec_new[id] = rec_order.len() as u32;
            rec_order.push(id as u32);
            rec_first_release.push(pack::NONE);
        }
    }
    st.standalone_recordings = rec_order.len() as u64 - tracked;

    // --- ISRCs ----------------------------------------------------------------
    step!("pass 9: ISRCs");
    let mut isrcs: Vec<(u32, [u8; 7])> = Vec::new();
    if opts.include_isrcs {
        let mut rejects = 0u64;
        let n = require(
            input,
            "isrc",
            Shape { table: "isrc", min_fields: 5, ints: &[0, 1], uuids: &[] },
            |row| {
                let Some(rec) = row.u32(1) else { return Ok(()) };
                let Some(&new) = rec_new.get(rec as usize) else { return Ok(()) };
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
    step!("pass 10: search index");
    let mut term_dict: HashMap<String, u32> = HashMap::new();
    let mut postings: Vec<(u32, u32)> = Vec::new();
    let mut terms_buf: Vec<String> = Vec::new();
    let mut add = |text: &str, refv: u32, dict: &mut HashMap<String, u32>, post: &mut Vec<(u32, u32)>| {
        terms_buf.clear();
        search_terms(text, &mut terms_buf);
        for t in &terms_buf {
            let next = dict.len() as u32;
            let id = *dict.entry(t.clone()).or_insert(next);
            post.push((id, refv));
        }
    };
    for (i, a) in artists.iter().enumerate() {
        let name = str_from_pool(&pool, a.name_off);
        check_searchable(i as u32)?;
        add(&name, (pack::KIND_ARTIST << pack::KIND_SHIFT) | i as u32, &mut term_dict, &mut postings);
    }
    for (i, r) in rgs.iter().enumerate() {
        let title = str_from_pool(&pool, r.title_off);
        check_searchable(i as u32)?;
        add(
            &title,
            (pack::KIND_RELEASE_GROUP << pack::KIND_SHIFT) | i as u32,
            &mut term_dict,
            &mut postings,
        );
    }
    if opts.include_recording_search {
        for (i, id) in rec_order.iter().enumerate() {
            let title = str_from_pool(&pool, rec_rows[*id as usize].title_off);
            check_searchable(i as u32)?;
            add(
                &title,
                (pack::KIND_RECORDING << pack::KIND_SHIFT) | i as u32,
                &mut term_dict,
                &mut postings,
            );
        }
    }
    st.search_postings = postings.len() as u64;
    // Sort the dictionary so the reader can binary search it, then renumber.
    let mut sorted_terms: Vec<(String, u32)> = term_dict.into_iter().collect();
    sorted_terms.sort_unstable_by(|a, b| a.0.cmp(&b.0));
    let mut remap = vec![0u32; sorted_terms.len()];
    for (new, (_, old)) in sorted_terms.iter().enumerate() {
        remap[*old as usize] = new as u32;
    }
    for p in postings.iter_mut() {
        p.0 = remap[p.0 as usize];
    }
    drop(remap);
    postings.sort_unstable();
    postings.dedup();
    let term_count = sorted_terms.len();
    let mut search_terms_bytes: Vec<u8> = Vec::new();
    let mut postings_bytes: Vec<u8> = Vec::new();
    let mut term_index: Vec<u8> = Vec::with_capacity((term_count + 1) * 8);
    let mut p = 0usize;
    for (new_id, (t, _)) in sorted_terms.iter().enumerate() {
        term_index.extend_from_slice(&(search_terms_bytes.len() as u32).to_le_bytes());
        term_index.extend_from_slice(&(postings_bytes.len() as u32).to_le_bytes());
        search_terms_bytes.extend_from_slice(t.as_bytes());
        search_terms_bytes.push(0);
        let start = p;
        while p < postings.len() && postings[p].0 == new_id as u32 {
            p += 1;
        }
        pack::write_uvarint(&mut postings_bytes, (p - start) as u64);
        let mut prev = 0u32;
        for &(_, refv) in &postings[start..p] {
            pack::write_uvarint(&mut postings_bytes, (refv - prev) as u64);
            prev = refv;
        }
    }
    term_index.extend_from_slice(&(search_terms_bytes.len() as u32).to_le_bytes());
    term_index.extend_from_slice(&(postings_bytes.len() as u32).to_le_bytes());
    drop(postings);

    // --- assemble -------------------------------------------------------------
    step!("pass 11: writing");
    let mut w = PackWriter::new(out)?;
    w.section_u32(pack::S_ENUM_POOL, enums.offsets())?;
    w.section_u32(pack::S_CREDITS, &credits_off)?;

    let mut buf: Vec<u8> = Vec::new();
    buf.clear();
    for a in &artists {
        buf.extend_from_slice(&a.name_off.to_le_bytes());
        buf.extend_from_slice(&a.disamb_off.to_le_bytes());
        buf.extend_from_slice(&a.area_off.to_le_bytes());
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

    buf.clear();
    for r in &rgs {
        buf.extend_from_slice(&r.title_off.to_le_bytes());
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
    w.section_u32(pack::S_ARTIST_RGS, &artist_rgs)?;
    w.section_u32(pack::S_ARTIST_RGS_IDX, &artist_rgs_idx)?;

    buf.clear();
    for r in &releases {
        buf.extend_from_slice(&r.title_off.to_le_bytes());
        buf.extend_from_slice(&rg_idx.get(r.rg_id).unwrap_or(pack::NONE).to_le_bytes());
        buf.extend_from_slice(&credit_idx_of(r.credit_id, credit_count).to_le_bytes());
        buf.extend_from_slice(&r.date.to_le_bytes());
        buf.extend_from_slice(&r.disamb_off.to_le_bytes());
        buf.extend_from_slice(&r.status.to_le_bytes());
        buf.extend_from_slice(&r.country.to_le_bytes());
    }
    w.section(pack::S_RELEASES, &buf)?;
    let gids: Vec<Mbid> = releases.iter().map(|r| r.gid).collect();
    let (recs, hi) = build_mbid_table(&gids);
    w.section(pack::S_RELEASE_MBID, &recs)?;
    w.section_u32(pack::S_RELEASE_MBID_HI, &hi)?;
    drop(gids);
    w.section_u32(pack::S_RG_RELEASES, &rg_releases)?;
    w.section_u32(pack::S_RG_RELEASES_IDX, &rg_releases_idx)?;

    w.section_u32(pack::S_MEDIA_IDX, &media_idx_csr)?;
    buf.clear();
    for m in &media {
        buf.extend_from_slice(&m.format.to_le_bytes());
        buf.extend_from_slice(&(m.track_count.min(u16::MAX as u32) as u16).to_le_bytes());
    }
    w.section(pack::S_MEDIA, &buf)?;
    w.section_u32(pack::S_TRACK_IDX, &track_idx)?;
    assert!(
        tracks_stream.len() <= u32::MAX as usize,
        "TRACKS is {} bytes, past the u32 ceiling TRACK_IDX addresses it with",
        tracks_stream.len()
    );
    w.section(pack::S_TRACKS, &tracks_stream)?;
    drop(tracks_stream);
    if opts.include_track_mbids {
        w.section(pack::S_TRACK_MBID, &track_mbids)?;
    }
    drop(track_mbids);

    buf.clear();
    for id in &rec_order {
        let r = rec_rows[*id as usize];
        let (lo, hi) = encode_recording_credit(credit_idx_of(r.credit_id, credit_count));
        buf.extend_from_slice(&r.title_off.to_le_bytes());
        buf.extend_from_slice(&r.dur_s.to_le_bytes());
        buf.extend_from_slice(&lo.to_le_bytes());
        buf.push(hi);
    }
    w.section(pack::S_RECORDINGS, &buf)?;
    if opts.include_recording_mbids {
        buf.clear();
        for id in &rec_order {
            buf.extend_from_slice(&rec_gids[*id as usize]);
        }
        w.section(pack::S_RECORDING_MBID, &buf)?;
    }
    w.section_u32(pack::S_REC_FIRST_RELEASE, &rec_first_release)?;

    if opts.include_isrcs {
        buf.clear();
        for (rec, packed) in &isrcs {
            buf.extend_from_slice(&rec.to_le_bytes());
            buf.extend_from_slice(packed);
        }
        w.section(pack::S_ISRCS, &buf)?;
    }

    w.section(pack::S_SEARCH_TERMS, &search_terms_bytes)?;
    w.section(pack::S_SEARCH_POSTINGS, &postings_bytes)?;
    w.section(pack::S_SEARCH_TERM_IDX, &term_index)?;
    w.section(pack::S_STRINGS, pool.bytes())?;

    let counts = HeaderCounts {
        artists: artists.len() as u32,
        credits: credit_count,
        release_groups: rgs.len() as u32,
        releases: releases.len() as u32,
        media: media.len() as u32,
        tracks: track_count,
        recordings: rec_order.len() as u32,
        isrcs: isrcs.len() as u32,
        search_terms: term_count as u32,
        enums: enums.offsets().len() as u32,
    };
    w.set_header(&counts, opts.flags(), input.dump_date());
    st.sections = w
        .section_sizes()
        .into_iter()
        .map(|(i, len)| (pack::SECTION_NAMES[i], len))
        .collect();
    st.distinct_strings = pool.distinct();
    st.string_pool_bytes = pool.bytes().len();
    st.dense_map_bytes =
        artist_idx.bytes() + rg_idx.bytes() + release_idx.bytes() + medium_idx.bytes();
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

fn str_from_pool(pool: &StringPool, off: u32) -> String {
    let bytes = pool.bytes();
    let start = off as usize;
    if start >= bytes.len() {
        return String::new();
    }
    let end = bytes[start..].iter().position(|&b| b == 0).map_or(bytes.len(), |n| start + n);
    String::from_utf8_lossy(&bytes[start..end]).into_owned()
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
    input: &Input,
    table: &'static str,
    pool: &mut StringPool,
    enums: &mut EnumPool,
) -> io::Result<HashMap<u32, u16>> {
    let mut out = HashMap::new();
    require(input, table, Shape { table, min_fields: 2, ints: &[0], uuids: &[] }, |row| {
        if let Some(id) = row.u32(0) {
            out.insert(id, enums.intern(pool, &row.str(1)));
        }
        Ok(())
    })?;
    Ok(out)
}

/// Read a table that must be present, checking its shape on the first row.
fn require<F>(input: &Input, table: &'static str, shape: Shape, mut f: F) -> io::Result<u64>
where
    F: FnMut(&Row<'_>) -> io::Result<()>,
{
    let mut first = true;
    let rows = input.each_row(table, |row| {
        if first {
            shape.check(row)?;
            first = false;
        }
        f(row)
    })?;
    match rows {
        Some(n) => Ok(n),
        None => Err(other_err(format!(
            "table `{table}` is missing from {}. The core mbdump.tar.bz2 contains it as \
             `mbdump/{table}`; a derived or partial dump does not.",
            input.describe()
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn track_build_stays_small() {
        // 57.4 M of these are resident while the track pass sorts, so every byte
        // here is 57 MB of peak RSS. The README quotes this number.
        assert_eq!(std::mem::size_of::<TrackBuild>(), 28);
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
}
