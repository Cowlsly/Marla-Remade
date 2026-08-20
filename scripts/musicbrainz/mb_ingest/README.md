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

# Prove the whole pipeline on a synthetic dump in ~a second.
./target/release/mb_ingest fixture /tmp/mb/mbdump
./target/release/mb_ingest build /tmp/mb /tmp/musicbrainz.pack
./target/release/mb_ingest inspect /tmp/musicbrainz.pack

cargo test          # 42 tests, including a full round trip through the reader
```

Flags: `--tier-a` adds track MBIDs (+918 MB at full scale), `--official-only`
drops the 9.4% of releases that are not Official, `--raw-strings` leaves the
string pool uncompressed (+454 MB, but strings become zero-copy),
`--work-dir <dir>` places the track spill buckets, `--no-isrcs`,
`--no-recording-search`, `--no-recording-mbids`.

`build` writes `<out>.pack.tmp` and renames, so a crashed build never leaves a
half-written pack where the server would mmap it.

## Input

Only **`mbdump.tar.bz2`** is needed. Verified against the live export
(`20260819-002541`), not assumed:

* core-dump members are `mbdump/<table>`, unprefixed, in ASCII order, after
  `TIMESTAMP`, `COPYING`, `README`, `REPLICATION_SEQUENCE`, `SCHEMA_SEQUENCE`.
  The non-`musicbrainz`-schema dumps use `mbdump/<schema>.<table>` instead.
* tables are PostgreSQL `COPY` TEXT: TAB-separated, `\N` for NULL, backslash
  escapes for embedded tabs and newlines. Real titles contain both, so the
  unescaping in `src/copy.rs` is load-bearing.

All 20 tables read are marked `-- replicate` in `admin/sql/CreateTables.sql`,
which is what puts a table in the core dump — including `release_group_meta`, the
source of `first_release_date_*`, which is a core table despite ratings being a
derived-dump concern.

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

## What it costs at full scale

Row counts are pack-designer's measurements from `musicbrainz.org/statistics`
(retrieved 2026-08-20, page dated 2026-08-19). `MB` means 10⁶ bytes. These are
**projections from the measured row counts, not from a completed full run.**

| section | MB |
|---|---|
| `RECORDING_MBID` | 638 |
| `RECORDINGS` | 359 |
| `TRACKS` (varint, overrides inline) | ~235 |
| `STRINGS` (zstd, sorted, from 635 raw) | ~181 |
| `SEARCH_POSTINGS` | ~173 |
| `REC_FIRST_RELEASE` | 160 |
| `RELEASES` + `RELEASE_MBID` | 217 |
| `RELEASE_GROUPS` + `RG_MBID` | 134 |
| `ARTISTS` + `ARTIST_MBID` | 101 |
| `SEARCH_TERMS` + `SEARCH_TERM_IDX` | ~88 |
| `ISRCS` | 69 |
| CSR indexes, `MEDIA`, `CREDITS`, buckets | ~158 |
| **total, tier B** | **~2 510** |

**~2.51 GB.** The design doc's 2,409.5 MB differs by ~100 MB of deliberate
layout corrections, itemised here so the gap is not a mystery:

* `ReleaseRec` is 24 B, not 20 (+23 MB). The doc packs "status+country" into one
  byte; a u8 cannot hold an ISO-3166-1 alpha-2 code.
* `ReleaseGroupRec` 16 B not 14 (+9), `ArtistRec` 20 B not 18 (+6): the small
  enumerations and the ~250 country codes share one table and need u16 indices.
* `MEDIA` 4 B not 1 (+19). The doc's medium record holds only a format byte, so
  per-medium track counts would have to be derived by decoding tracklists — on a
  release-group page with up to 100 editions, thousands of varint runs per
  request. Storing the count makes `TRACKTOTAL` O(1).
* `ISRCS` 69 MB not 25 (+44). Plain 11 B records sorted for binary search rather
  than delta-varint in compressed blocks. Recoverable later.
* `SEARCH_TERM_IDX` 44 MB not 22 (+22). A term needs two offsets, one into the
  dictionary and one into the postings; the doc budgeted one.
* `TRACKS` −24 MB, *and* it keeps a field the doc dropped. Title and credit
  overrides are inline in the varint stream rather than in `TRACK_TITLE_EXC` /
  `TRACK_CREDIT_EXC` side arrays: inline only widens the ~12% of tracks that
  differ, is 46 MB cheaper, and removes a binary search from the hot path. The
  room that freed up now carries per-track length overrides, which doc L8
  discarded — wrongly, because `track.length ?: recording.length`
  (`MusicBrainzViewModel.kt:503`) makes the track's length the primary source.
* the first track of each medium carries an **absolute** recording index rather
  than a delta from the previous medium's last track (+22 MB, inside the ~235).
  Without that a medium cannot be decoded on its own and `TRACK_IDX`'s byte
  offset buys nothing.

Tier A (add track MBIDs) is ~3.43 GB.

## Where the memory goes

Peak RSS is the binding constraint, not wall time: the ingest runs in-process
alongside the live server on an 11 GB box, so the budget is 4 GB. Four rules the
code follows:

* **`TRACKS` is never a `Vec<TrackRec>`.** It is built as a varint byte stream as
  the tracks are visited. This is the failure recorded at
  `scripts/maps/gtfs_ingest/src/index.rs:15-27`, where a fixed-size record per
  stop per trip made a world transit pack 10-20 GB and overflowed a u32.
* **id maps are dense `Vec<u32>` keyed by the dump's integer primary key.**
  mbdump foreign keys are integer row ids (`track.recording INTEGER`), not gids,
  so all six maps together are ~400 MB rather than the ~1.4 GB a
  `HashMap<[u8;16], u32>` over recording gids alone would cost.
* **track rows are bucket-sorted through spill files.** The dump is in track-id
  order and `TRACKS` needs medium order; buffering all 57.4 M rows to sort would
  be ~1.4 GB. Instead 24-byte records go out to 64 medium-keyed buckets and each
  bucket (~25 MB) is sorted in memory. This needs the table to be re-openable.
* **`RECORDING_MBID` is filled by a second pass over `recording`** that scatters
  gids into the output buffer, rather than holding 16 B per recording id (~800 MB)
  through the whole build.

Projected peak, largest phase governing — a projection, not a measurement:

| structure | MB |
|---|---|
| string pool staging bytes | ~635 |
| recording title/credit/duration arrays | ~500 |
| dense id -> index maps | ~400 |
| string pool interning index | ~130 |
| `TRACKS` output buffer | ~235 |
| one track bucket | ~25 |
| **peak** | **~2 100** |

The string pool's interning index is open-addressed over u32 symbol ids, not a
`HashMap<String, u32>`: the latter is ~1.84 GB steady with a ~2.3 GB transient
spike at its final resize, which does not fit.

Disk: ~1.4 GB of scratch for the track spill (`--work-dir`), plus the pack.

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
  are ANDed. Results will differ from musicbrainz.org.
* durations are **seconds, not milliseconds** — the UI renders m:ss, Tidal
  matching uses a ±3 s tolerance and LRCLIB takes seconds.
* strings are `Cow<'a, str>`: owned when the pool is compressed, borrowed when
  `--raw-strings` is used. Do not assume they are free.

## Possible follow-up, not in scope here

`mbdump-cover-art-archive.tar.bz2` (158 MB) records which releases actually have
cover art. The app currently builds coverartarchive.org URLs blindly and absorbs
the 404s. A presence bitmap over the 5.7 M releases is ~0.7 MB in the pack and
would remove those wasted round trips.
