# `mb_ingest` — MusicBrainz offline pack builder and reader

Turns a MusicBrainz full export into one mmap-able `.pack` file, and reads it
back. The point is to delete the `musicbrainz` app's dependence on the WS/2 API,
whose 1 req/s client-side rate limiter (`MusicBrainzApi.kt:21-40`) is what makes
the artist screen take 3-5 seconds — two serialised calls, not network latency.

Design: `c:\Users\Vayun\.llms\plans\mb_pack.plan.md` (pack-designer, task 2).
Format spec: the `//!` header of `src/pack.rs`, which is the source of truth.
Reader: `src/reader.rs`, which must stay in sync with it.

Detached from the repo-root Cargo workspace, as `scripts/maps/gtfs_ingest` is.
One dependency, `zstd`, for the compressed string pool; `location_share_server`
already pulls it in transitively through `zip 8.6.0`.

## Where the boundaries are

This crate does **not** download anything and does not touch bzip2 or tar.
`location_share_server` owns the download, does one bzip2 traversal of the
archive, demuxes the tar, spills the 20 tables named in `build::TABLES`, and
implements `copy::TableSource` over them. That matters because bz2 is not
seekable: one traversal of the 7.45 GB archive is ~8.5 minutes at the measured
14.7 MB/s, so a pass-per-table design would spend over an hour just inflating the
same bytes.

The trait is small, and **tables must be re-openable**:

```rust
pub trait TableSource {
    fn open_table(&self, table: &str) -> io::Result<Option<Box<dyn BufRead + Send>>>;
    fn dump_date(&self) -> u32 { 0 }
    fn describe(&self) -> String { .. }
}
```

`copy::Input` implements it over a directory or a `.tar.bz2` (through the system
`tar`). Those exist so the fixture and the CLI work without the server's
pipeline; they are not the production path.

## Use

```sh
cargo build --release

# The real 7.45 GB export: extract the 19 tables this crate reads (one bz2 pass,
# ~9 min, ~15 GB of scratch), then build from the directory. The server's
# single-traversal spill is the production equivalent.
tar -xf mbdump.tar.bz2 -C /scratch/mbdump TIMESTAMP mbdump/artist mbdump/track ...
./target/release/mb_ingest build /scratch/mbdump musicbrainz.pack --work-dir /scratch/spill

# Prove the whole pipeline on a synthetic dump in ~a second.
./target/release/mb_ingest fixture /tmp/mb/mbdump
./target/release/mb_ingest build /tmp/mb /tmp/musicbrainz.pack
./target/release/mb_ingest inspect /tmp/musicbrainz.pack
./target/release/mb_ingest query  /tmp/musicbrainz.pack "dark side"

cargo test          # 48 tests, including a full round trip through the reader
                    # and three that prove the reader cannot panic on corrupt input
```

Flags: `--tier-a` adds track MBIDs, `--official-only` drops the 9.4% of releases
that are not Official, `--raw-strings` leaves the string pool uncompressed
(+578 MB, but strings become zero-copy), `--work-dir <dir>` places the spill
buckets, `--no-isrcs`, `--no-recording-search`, `--no-recording-mbids`.

`build` writes `<out>.pack.tmp` and renames, so a crashed build never leaves a
half-written pack where the server would mmap it.

## Input

Only **`mbdump.tar.bz2`** is needed (7,451,072,390 bytes for 20260819-002541).
Verified against the live export by extracting it, not by reading the schema:

* core-dump members are `mbdump/<table>`, unprefixed, in ASCII order, after
  `TIMESTAMP`, `COPYING`, `README`, `REPLICATION_SEQUENCE`, `SCHEMA_SEQUENCE`.
  The non-`musicbrainz`-schema dumps use `mbdump/<schema>.<table>` instead.
* tables are PostgreSQL `COPY` TEXT: TAB-separated, `\N` for NULL, backslash
  escapes for embedded tabs and newlines. Real titles contain both, so the
  unescaping in `src/copy.rs` is load-bearing.

**`release_group_meta` is NOT in the core dump**, despite being marked
`-- replicate` in `admin/sql/CreateTables.sql` — that marker governs replication
packets, not the dump split. Its `first_release_date_*` is therefore derived as
the earliest date across a group's releases, which is how upstream materialises it.
This is what keeps the crate off `mbdump-derived.tar.bz2`.

The comparison uses `pack::date_rank`, not the raw packed date: a year-only date
packs with month and day zero, so a raw `<` makes `1973` beat `1973-03-24` and the
group loses the precise date. Spot-checked against musicbrainz.org after the fix —
*The Dark Side of the Moon* `1973-03-24`, *Homogenic* `1997-09-20`, *Nevermind*
`1991-09-24`, all three exact matches.

Tables read (19), and `build::TABLES` is the authoritative list — import it rather
than duplicating it: `area`, `iso_3166_1`, `artist_type`,
`release_group_primary_type`, `release_group_secondary_type`, `release_status`,
`medium_format`, `artist_credit`, `artist_credit_name`, `artist`,
`release_group`, `release_group_secondary_type_join`, `release`,
`release_country`, `release_unknown_country`, `medium`, `recording`, `track`,
`isrc`.

Measured uncompressed sizes of the 19 tables read (total ~14.8 GB):
`track` 7409 MB, `recording` 4319, `release` 739, `medium` 560,
`release_group` 459, `artist` 416, `artist_credit` 396, `isrc` 368,
`release_country` 273, `artist_credit_name` 227,
`release_group_secondary_type_join` 35, `area` 13,
`release_unknown_country` 8, and six enum tables under 1 MB each.

Deflate-compressed, measured rather than assumed, the same 19 tables are
**6.24 GB — an overall ratio of 2.37x, not 3x**. `isrc` (5.12x) and
`release_country` (3.48x) beat it because they are nearly pure digits; `track`
(2.52x) and `recording` (2.13x) are ordinary text and are 79% of the volume.

**`mbdump-derived.tar.bz2` is not needed** — it holds annotations, ratings, user
tags and search indexes, none of which the app requests, and depending on it
would drag in `mbdump-editor.tar.bz2` as well. `mbdump-edit.tar.bz2` (15 GB) is
irrelevant.

Column order is hard-coded from `CreateTables.sql` (schema sequence 31) and every
table's arity plus its distinctive columns are checked on the first row. If
MusicBrainz reorders a column the build stops with the table name rather than
producing a plausible-looking wrong pack.

### Integrity

The export directory publishes `SHA256SUMS` and `SHA256SUMS.asc`. SHA256 against
the published sums is the agreed bar: they arrive over the same authenticated TLS
channel as the archive, so PGP's marginal gain is protection against a
compromised origin, which is not the threat model for a personal server ingesting
a public dataset. The stronger option, if that ever changes, is verifying
`SHA256SUMS.asc` against MusicBrainz's key `C777580F` (fingerprint
`D5E6 3B4B DCCE 1956 4294 8684 B8FC 2375 C777 580F`).

## What it costs at full scale — MEASURED

Built from the `20260819-002541` full export (SHA256 verified). These are real
numbers from a completed run, not projections. `MB` means 10⁶ bytes.

| section | MB |
|---|---|
| `RECORDING_MBID` | 638.1 |
| `RECORDINGS` | 358.9 |
| `SEARCH_POSTINGS` | 299.0 |
| `STRINGS` (zstd, sorted, from 805.4 raw — **3.53x**) | 227.8 |
| `TRACKS` (varint, overrides inline) | 196.4 |
| `REC_FIRST_RELEASE` | 159.5 |
| `RELEASES` + `RELEASE_MBID` | 217.2 |
| `RELEASE_GROUPS` + `RG_MBID` | 134.1 |
| `ARTISTS` + `ARTIST_MBID` | 100.7 |
| `SEARCH_TERMS` + `SEARCH_TERM_IDX` | 86.2 |
| `ISRCS` | 69.1 |
| CSR indexes, `MEDIA`, `CREDITS`, buckets | 168.0 |
| **total, tier B** | **2 655.0** |

**2,801,472,608 bytes = 2.801 GB**, built in 6.9 min on Windows / 10m48s under WSL2
(the difference is reading 14.9 GB of tables over `/mnt/c` at 205 MB/s, not the
build). **Peak `RssAnon` on Linux: 2.85 GB** (see below).

SHA256 `5beccbf5ab267643b4adeefb12d373c4dc6ff4a49f847e3e7854f3e29920d170` for the
Linux build of `5f243c765`. Byte-for-byte reproducibility was proven on two
consecutive Windows runs of an earlier commit; it has **not** been re-proven for
this commit, and cross-platform byte-identity is unverified.

### Where the +145 MB over projection went

This compares the projection (2,510 MB) against the first measured pack
(2,655 MB), before artist-credit terms were folded into the search indexes.

| section | projected | measured | delta |
|---|---|---|---|
| `SEARCH_POSTINGS` | 173 | **299.0** | **+126** |
| `STRINGS` | 181 | **227.8** | **+47** |
| `CREDITS` | 12.4 | **20.3** | +8 |
| `SEARCH_TERMS` | 44 | **51.5** | +8 |
| `SEARCH_TERM_IDX` | 44 | **34.7** | −9 |
| `TRACKS` | 235 | **196.4** | **−39** |
| everything else | — | — | ~0 |

Two misses and one win. The postings miss dominates: the design doc estimated
78.8 M postings and there are **157,118,254**, exactly 2x. `STRINGS` missed
because the raw pool is 805 MB rather than the estimated 635 MB — the
distinct-recording-title fraction is 49.9%, not 40%. `TRACKS` came in 39 MB
*under* projection, so the inline-override design paid off more than claimed.
`CREDITS` is sized to max `artist_credit` id (5,083,674) rather than live rows
(3,830,114), a 1.33x id gap.

### Then artist-credit terms were folded in: +146 MB

| section | before | after |
|---|---|---|
| `SEARCH_POSTINGS` | 299.0 MB | **444.5 MB** |
| `SEARCH_TERMS` | 51.5 MB | **52.1 MB** |
| `SEARCH_TERM_IDX` | 34.7 MB | **35.0 MB** |
| postings | 157,118,254 | **266,307,161** |
| **pack total** | 2,655,030,016 B | **2,801,472,608 B** |

Almost all of it is the recording half: a credit attached to each of 39.88 M
recordings is where the 109 M extra postings come from. Release groups alone
would have cost ~20 MB. Peak memory is unaffected — the postings are spilled and
streamed, so this costs pack size only.

Row counts in the pack: 2,962,348 artists / 4,468,998 release groups /
5,714,674 releases / 6,274,550 media / 57,404,909 tracks / 39,881,298 recordings
/ 6,278,039 ISRCs / 4,332,477 search terms / 29,153,704 distinct strings.

### The design doc's estimates against reality

| ratio | doc estimate | measured |
|---|---|---|
| distinct recording titles | 40% | **49.9%** |
| tracks whose title == recording's | 88% | **92.8%** |
| tracks whose credit == recording's | 92% | **91.5%** |
| tracks whose length == recording's | not estimated | **89.9%** |
| standalone recordings | 5% | **0.4%** |
| search postings | 78.8 M | **157,118,254** |
| string pool, raw | 635 MB | **805.4 MB** |
| id-map gap (max id / live rows) | 1.6x | **1.03-1.19x** |

The standalone-recording figure being 12x over-estimated makes lever L5 worth
~3 MB rather than 36 MB. The postings count being exactly 2x the estimate is the
main reason the pack came in above projection.

Deliberate layout differences from the doc's §2.3, each one because the doc's
version does not fit or does not work:

* `ReleaseRec` is 24 B, not 20. The doc packs "status+country" into one byte; a u8
  cannot hold an ISO-3166-1 alpha-2 code.
* `ReleaseGroupRec` 16 B not 14, `ArtistRec` 20 B not 18: the small enumerations
  and the ~250 country codes share one table and need u16 indices.
* `MEDIA` 4 B not 1, so per-medium track counts need no tracklist decode. A
  release-group page renders up to 100 editions.
* `ISRCS` 69 MB not 25: plain 11 B records sorted for binary search rather than
  delta-varint in compressed blocks. Recoverable later.
* `SEARCH_TERM_IDX` needs two offsets per term, one into the dictionary and one
  into the postings; the doc budgeted one.
* `TRACKS` carries title, credit, position **and length** overrides inline rather
  than in `TRACK_TITLE_EXC` / `TRACK_CREDIT_EXC` side arrays. Inline only widens
  the ~7% of tracks that differ, is cheaper than the two side tables, and removes
  a binary search from the hot path. Doc L8 discarded per-track lengths, wrongly:
  `track.length ?: recording.length` (`MusicBrainzViewModel.kt:503`) makes the
  track's length the primary source.
* the first track of each medium carries an **absolute** recording index rather
  than a delta from the previous medium's last track. Without that a medium
  cannot be decoded on its own and `TRACK_IDX`'s byte offset buys nothing.

Tier A (add track MBIDs) would be ~3.57 GB.

## Where the memory goes

Peak memory is the binding constraint: the ingest runs in-process alongside the
live server on a box with 5-7 GB free, and the target is stated on **anonymous**
memory because that is what causes OOM kills — clean file-backed pages cost query
latency, not availability.

**Measured on real Linux (WSL2), polling `/proc/<pid>/status` every 200 ms:**

    max RssAnon    2.85 GB     <-- the figure the target is stated on
    max RssFile    0.00 GB
    max RssShmem   0.00 GB
    VmHWM          2.85 GB

The build is effectively **all anonymous** — buffered reads go through the page
cache without entering process RSS, and no old pack is mapped during a first
build. `max(RssAnon+RssFile+RssShmem) / VmHWM = 1.000`, so the sampling interval
did not miss the spike. Nothing swapped.

Note that Windows `WorkingSetSize` for the same build reads **2.64 GB** — *lower*
than Linux `RssAnon`. A Windows figure is not an upper bound on Linux anonymous
memory (different allocator: glibc retains freed memory in per-thread arenas), so
do not substitute one for the other.

Getting there took cutting 6.14 GB to 2.85 GB, and the itemisation is what made it
possible — the peak was **accumulation**, not one allocation, because nothing was
freed between pass 2 and the write phase:

| phase | peak RSS | delta |
|---|---|---|
| pass 2-6 artists, credits, RGs, releases, media | 1.23 GB | |
| pass 7 recordings | 2.56 GB | **+1.33** |
| pass 8-9 track spill, pool sort | 2.44 GB | −0.12 |
| pass 10 TRACKS | 3.05 GB | +0.61 |
| pass 13 search index | 3.33 GB | +0.21 |
| pass 14 writing | **3.49 GB** | +0.16 |

That was the 3.49 GB profile. The two changes that took it to 2.64 GB came
straight off it: emitting the artist/release-group/release sections **before** the
track pass and dropping those vectors (−571 MB, keeping only their 30 MB of
name/title symbols for the search index), and streaming `TRACKS` into the pack
rather than buffering the whole varint stream (−196 MB).

The peak is accumulation, not one allocation — nothing was freed between pass 2
and the write. Six rules the code follows, each worth a measured amount:

* **`TRACKS` is never a `Vec<TrackRec>`** and is now streamed. This is the failure
  recorded at `scripts/maps/gtfs_ingest/src/index.rs:15-27`, where a fixed-size
  record per stop per trip made a world transit pack 10-20 GB and overflowed a u32.
* **id maps are dense `Vec<u32>` keyed by the dump's integer primary key.**
  mbdump foreign keys are integer row ids (`track.recording INTEGER`), not gids.
  Measured: **83 MB** for all six, against ~1.4 GB for a `HashMap<[u8;16], u32>`
  over recording gids alone. Measured id gaps are 1.03-1.19x, not the 1.6x assumed.
* **track rows are bucket-sorted through spill files** — 64 medium-keyed buckets,
  1.38 GB on disk, ~25 MB resident.
* **the search dictionary is a symbol pool, not `HashMap<String,u32>`**, and
  postings are spilled to 64 rank-keyed buckets and streamed. Together ~3 GB: the
  map plus the `Vec<(String,u32)>` it was drained into held ~4.3 M duplicated
  `String` allocations at once, and 157 M postings as `Vec<(u32,u32)>` is 1.26 GB.
* **entity vectors go to disk before the track pass** and are dropped.
* **every remaining buffer is dropped as soon as its section is on disk**, and the
  638 MB recording-MBID pass runs immediately before its own write.

Disk: 1.38 GB of scratch for the track spill plus ~1.26 GB for the postings spill
(`--work-dir`), and the pack itself.

## Why the string pool is sorted

`intern` returns a dense **symbol id**, not a byte offset. Offsets are assigned
only by `finalize`, after sorting every distinct string alphabetically. zstd over
64 KB blocks of sorted short text gets ~3.5x where the same text in interning
order gets ~2.5-3x, and the offsets cannot be reordered afterwards because they
are baked into the inline overrides of the `TRACKS` varint stream, where changing
an offset changes the varint's width. Deferring costs nothing: every table is
read before any offset is needed.

Blocks break at exact multiples of 64 KiB so a pool offset names its block by
division rather than a search. A string may straddle a boundary; the reader
continues into the next block when it does not find the terminating NUL.

## What tier B gives up

Track MBIDs, and therefore the `MUSICBRAINZ_RELEASETRACKID` Vorbis tag. Nothing
else. `LibrarySnapshot.hasTrack` (`data/library/LibraryIndex.kt:30-42`) is a
four-tier cascade with every parameter nullable; dropping the tag removes tier 1
only, and recording MBIDs — tier 2, also exact — are in the pack. The precision
that tag adds is already absent for most of a real library, because
`MatchKeys.trackKey` is release-agnostic by construction.

Also given up, deliberately:

* **there is no `recording_by_mbid` and no `track_by_mbid`.** Recordings are
  stored in tracklist-clustered order, which is what makes the `TRACKS` deltas
  cheap; a sorted MBID index would cost more than it saves. The WS/2 endpoint
  that would have needed it is dead code with no callers.
* **search is word-prefix, not substring and not Lucene.** "dark side" finds
  "The Dark Side of the Moon"; "loitude" does not find "Solitude". Query words
  are ANDed. Measured latency on the full catalogue: 0.1-0.9 ms typical,
  19 ms worst seen.
* **artist-credit terms ARE indexed** for release groups and recordings, so
  "radiohead" in the albums tab finds Radiohead's albums — parity with WS/2's
  `release-group?query=`, which matches artist names. This costs **146 MB**
  (+109 M postings), almost all of it from attaching credits to 39.88 M
  recordings; release groups alone would be ~20 MB. Artist search remains
  name-only, which is what WS/2 does.
* durations are **seconds, not milliseconds** — the UI renders m:ss, Tidal
  matching uses a ±3 s tolerance and LRCLIB takes seconds.
* strings are `Cow<'a, str>`: owned when the pool is compressed, borrowed when
  `--raw-strings` is used. Do not assume they are free.

## Possible follow-up, not in scope here

`mbdump-cover-art-archive.tar.bz2` (158 MB) records which releases actually have
cover art. The app currently builds coverartarchive.org URLs blindly and absorbs
the 404s. A presence bitmap over the 5.7 M releases is ~0.7 MB in the pack and
would remove those wasted round trips.
