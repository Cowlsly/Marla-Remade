# `mb_ingest` — MusicBrainz offline pack builder and reader

Turns a MusicBrainz full export into one mmap-able `.pack` file, and reads it
back. The point is to delete the `musicbrainz` app's dependence on the WS/2 API,
whose 1 req/s client-side rate limiter (`MusicBrainzApi.kt:21-40`) is what makes
the artist screen take 3-5 seconds — two serialised calls, not network latency.

Design: `c:\Users\Vayun\.llms\plans\mb_pack.plan.md` (pack-designer, task 2).
Format spec: the `//!` header of `src/pack.rs`, which is the source of truth.
Reader: `src/reader.rs`, which must stay in sync with it.

Detached from the repo-root Cargo workspace and dependency-free, the same
convention as `scripts/maps/gtfs_ingest`.

## Use

```sh
cargo build --release

# Recommended for the real 7 GB export: extract only the 20 tables this crate
# reads (one bz2 pass, ~13 GB of disk), then build from the directory.
./target/release/mb_ingest extract mbdump.tar.bz2 /scratch/mbdump
./target/release/mb_ingest build /scratch/mbdump musicbrainz.pack

# Also works, and streams with nothing extracted -- but costs one bz2
# decompression pass PER TABLE, so it is for small archives and fixtures.
./target/release/mb_ingest build mbdump.tar.bz2 musicbrainz.pack

# Prove the whole pipeline on a synthetic dump in ~a second.
./target/release/mb_ingest fixture /tmp/mb/mbdump
./target/release/mb_ingest build /tmp/mb musicbrainz.pack
./target/release/mb_ingest inspect musicbrainz.pack

cargo test          # 36 tests, including a full round trip through the reader
```

Flags: `--tier-a` adds track MBIDs (+918 MB at full scale), `--official-only`
drops the 9.4% of releases that are not Official, `--no-isrcs`,
`--no-recording-search`, `--no-recording-mbids`.

## Input

Only **`mbdump.tar.bz2`** (7 GB) is needed. Verified against the live export
(`20260819-002541`), not assumed:

* core-dump members are `mbdump/<table>`, unprefixed, in ASCII order, after
  `TIMESTAMP`, `COPYING`, `README`, `REPLICATION_SEQUENCE`, `SCHEMA_SEQUENCE`.
  The non-`musicbrainz`-schema dumps use `mbdump/<schema>.<table>` instead.
* tables are PostgreSQL `COPY` TEXT: TAB-separated, `\N` for NULL, backslash
  escapes for embedded tabs and newlines. Real titles contain both, so the
  unescaping in `src/copy.rs` is load-bearing.

Tables read: `area`, `iso_3166_1`, `artist_type`,
`release_group_primary_type`, `release_group_secondary_type`, `release_status`,
`medium_format`, `artist_credit`, `artist_credit_name`, `artist`,
`release_group`, `release_group_meta`,
`release_group_secondary_type_join`, `release`, `release_country`,
`release_unknown_country`, `medium`, `recording`, `track`, `isrc`.

**`mbdump-derived.tar.bz2` is not needed** — it holds annotations, ratings, user
tags and search indexes, none of which the app requests, and depending on it
would drag in `mbdump-editor.tar.bz2` as well. `mbdump-edit.tar.bz2` (15 GB) is
irrelevant.

Column order is hard-coded from `admin/sql/CreateTables.sql` (schema sequence 31)
and every table's arity plus its distinctive columns are checked on the first row.
If MusicBrainz reorders a column the build stops with the table name rather than
producing a plausible-looking wrong pack.

### Integrity

The export directory publishes `SHA256SUMS` and `SHA256SUMS.asc`. Verify the
checksum as a baseline; verifying `SHA256SUMS.asc` against MusicBrainz's GPG key
`C777580F` (fingerprint `D5E6 3B4B DCCE 1956 4294 8684 B8FC 2375 C777 580F`) is
strictly stronger, because it authenticates the checksum list itself rather than
only detecting truncation.

## What it costs at full scale

Row counts are pack-designer's measurements from `musicbrainz.org/statistics`
(retrieved 2026-08-20, page dated 2026-08-19). `MB` means 10⁶ bytes. These are
**projections from the measured row counts, not from a completed full run** — the
7 GB dump has not been downloaded.

| section | MB |
|---|---|
| `STRINGS` (uncompressed) | 635 |
| `RECORDING_MBID` | 638 |
| `RECORDINGS` | 359 |
| `TRACKS` (varint, overrides inline) | ~235 |
| `SEARCH_POSTINGS` | ~173 |
| `REC_FIRST_RELEASE` | 160 |
| `RELEASES` + `RELEASE_MBID` | 217 |
| `RELEASE_GROUPS` + `RG_MBID` | 134 |
| `ARTISTS` + `ARTIST_MBID` | 101 |
| `ISRCS` | 69 |
| `SEARCH_TERMS` + `SEARCH_TERM_IDX` | ~88 |
| CSR indexes, `MEDIA`, `CREDITS`, buckets | ~158 |
| **total, tier B** | **~2 970** |

**~2.97 GB as built today.** The single biggest lever left is the string pool:
block-compressing it with zstd takes 635 MB to ~181 MB, i.e. **~2.51 GB**, at the
cost of this crate's zero-dependency property and of zero-copy string reads. The
header reserves a flag and a section for it (`FLAG_STRINGS_COMPRESSED`,
`STRINGS_BLKIDX`) and the reader refuses such a pack rather than misreading it, so
turning it on later is not a format break. Other levers, in order:
`REC_FIRST_RELEASE` is 160 MB for one field of one search result row;
delta-coding `ISRCS` in blocks saves ~44 MB; `SEARCH_TERM_IDX` can lose ~22 MB.

Tier A (add track MBIDs) is ~3.89 GB. Nothing else in the tier ladder is
implemented as a filter beyond `--official-only`.

## Where the memory goes

Peak RSS, not wall time, is the binding constraint. Two rules the code follows:

* **`TRACKS` is never a `Vec<TrackRec>`.** It is built as a varint byte stream as
  the tracks are visited. This is the failure recorded at
  `scripts/maps/gtfs_ingest/src/index.rs:15-27`, where a fixed-size record per
  stop per trip made a world transit pack 10-20 GB and overflowed a u32.
* **id maps are dense `Vec<u32>` keyed by the dump's integer primary key**, not
  hash maps keyed by MBID. The dumps use integer foreign keys, so the recording
  map is ~180 MB rather than the ~2 GB a `HashMap<[u8;16], u32>` would cost. The
  design doc assumed the latter.

Section offsets and lengths are u64, so the file may exceed 4 GB. The u32
ceilings that remain are checked and asserted: the string pool (635 MB raw, 6.8x
headroom — the first field that would break if scope grew), the `TRACKS` byte
length, and the 30-bit entity index inside a search posting.

Projected peak for a full run, again a projection and not a measurement:

| structure | MB |
|---|---|
| `track` sort buffer (57.4 M x 28 B) | ~1 600 |
| recording rows + gids, dense by id | ~1 100 |
| string pool + its open-addressed index | ~900 |
| search postings before grouping | ~700 |
| output section buffers | ~1 200 |
| dense id maps | ~300 |
| **peak** | **~5-6 GB** |

An external merge sort for the track pass, and spilling the output sections to
temporary files, are the two changes that would bring that down if a build has to
run somewhere smaller. Wall time is dominated by bz2 decompression, which is why
`extract` exists: one pass over the archive rather than one per table. The build
itself is a sort plus linear scans. Output is written to `<out>.pack.tmp` and
renamed, so a crashed build never leaves a half-written pack where the server
would mmap it.

The same pack is produced byte for byte whether the input is the archive or an
extracted directory; the round-trip test asserts reproducibility.

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
  "The Dark Side of the Moon"; "loitude" does not find "Solitude". Query words are
  ANDed. Results will differ from musicbrainz.org.
* a track's own `length` is kept (inline, only when it differs from the
  recording's), but as **seconds, not milliseconds** — the UI renders m:ss, Tidal
  matching uses a ±3 s tolerance and LRCLIB takes seconds.

## Possible follow-up, not in scope here

`mbdump-cover-art-archive.tar.bz2` (158 MB) records which releases actually have
cover art. The app currently builds coverartarchive.org URLs blindly and absorbs
the 404s. A presence bitmap over the 5.7 M releases is ~0.7 MB in the pack and
would remove those wasted round trips.
