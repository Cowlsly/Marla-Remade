# gtfs_ingest (P11a / world pack)

Host-side Rust build tool that converts one or more **GTFS** feeds into a single
compact **on-device transit index** (`<pack>.transit`, format **TRX2**) for the
Maps offline packs. It is the ingest half of offline public-transit routing; the
on-device planner that consumes the index is
`maps/src/main/rust/src/transit.rs` (P11b).

Merging many feeds into one pack is what makes a single global **`world.transit`**
feasible — the app downloads it once on open (mirroring the single global road
graph). See `scripts/maps/build_world_transit.sh` for the world pipeline.

## Why Rust + directory input

- **Rust-first (LANGUAGE RULE):** GTFS ingest is done in Rust, not Python. No
  `normalize_gtfs.py`. Its sibling host tool, `scripts/maps/osm_ingest`, follows
  the same rule for the OSM side — it replaced the libosmium `generator.cpp` /
  `poi_extract.cpp`, so no hand-written C++ is left in the Maps pipeline and both
  tools run on Windows.
- **Zero dependencies / offline builds:** the tool is `std`-only and detached
  from the repo-root Cargo workspace (see the empty `[workspace]` in
  `Cargo.toml`), so `cargo build` here resolves without the network and without
  the Android/aarch64 toolchain or the app crates' lints.
- **Unzipped input:** it reads *unzipped* GTFS directories rather than `.zip`s,
  which avoids pulling in a DEFLATE crate. Unzipping is a one-line pre-step.

## Usage

```sh
# Fetch region feeds (Transitous registry points at each agency's GTFS zip):
#   https://github.com/public-transport/transitous  ->  feeds/*.json
unzip sf_bay_511.zip -d /tmp/sf_bay
unzip ac_transit.zip -d /tmp/ac

# Build ONE merged index + manifest from several feeds:
cargo run --release -- /path/to/out world sf_bay=/tmp/sf_bay actransit=/tmp/ac
#   -> /path/to/out/world.transit        (mmap'd on device)
#   -> /path/to/out/world.transit.json    (manifest: counts, bbox, section sizes)

# …or list feeds in a manifest file (one `feed_name=dir` per line):
cargo run --release -- /path/to/out world --manifest feeds.manifest
```

Arg order is `gtfs_ingest <out_dir> <pack_name> <feed>...`, where each `<feed>`
is `feed_name=gtfs_dir` (or just `gtfs_dir`, whose base name becomes the feed
name). Feed names are the per-agency provenance stored per route and surfaced on
device (used for route colours via `GTFSProvider`).

## Output

- `<pack>.transit` — the binary TRX2 index. On-disk layout is documented at the
  top of `src/index.rs` and **must stay in sync** with `transit.rs`. It holds an
  interned string pool, stops, a FEEDS table (with each feed's IANA timezone from
  `agency.txt`), RAPTOR routes (trips grouped by identical stop pattern) with
  **varint-packed per-route trip arrays**, **deduplicated timetable profiles**
  (per-stop run-time shape, varint-delta encoded), a stop→routes index (with each
  stop's position in the pattern), footpath transfers (≤400 m, cross-feed), a
  **sparse spatial grid** (CSR by cell for O(cell) nearest/bbox), and calendar
  service masks + a CSR-indexed, date-sorted `calendar_dates` exception table, and
  **`shapes.txt` ride geometry** fitted to each route's stop pattern.
- `<pack>.transit.json` — a small manifest with counts, bbox and a **per-section
  byte breakdown** so the compression win (profiles vs the old per-trip
  stop-times) is visible, plus how many routes got ride geometry.
  `format_version` is 5.

## Why TRX2 (v2)

v1 stored `(arr,dep)` u32 per stop *per trip* (8 B) and referenced them via a
`u32 first_stoptime`, so a worldwide pack was ~10–20 GB and near the 4.29 B
stop-time ceiling. v2 factors each trip into `{ start_time, profile_id }` and
shares one **profile** (varint hop+dwell offsets) across every trip with the
same run-time shape — killing both the per-trip stop-time table and the ceiling.
Section offsets in the directory are u64 so the file may exceed 4 GB. Expected
world size drops to roughly **~1.5–4 GB** while staying on u32 indices.

## What v3 added

v3 appends three sections after v2's section 16, so sections 0–16 are
byte-identical and only `VERSION` (3) and `SECTION_COUNT` (20) change. All three
exist so the on-device planner can be *correct* and *fast* on a world pack:

| Section | Payload | Why |
|---|---|---|
| 17 `FEED_TZ` | `u32[feed_count]` string offsets | Each feed's `agency_timezone`. The planner routes in the **feed's** local time; without this a world-merged pack is queried in the device's zone and picks trips hours off. |
| 18 `EXCEPTIONS_IDX` | `u32[service_count + 1]` CSR offsets | `EXCEPTIONS` is now sorted by `(service, date)` with one row per pair, so a `calendar_dates` lookup is a range + binary search instead of a full scan of the pack **per trip**. |
| 19 `STOP_ROUTE_POS` | `u32[]` parallel to `STOP_ROUTES` | A stop's position in each serving route's pattern, replacing a linear scan of the pattern in the RAPTOR hot loop. |

The device reader accepts **v3 and v4**, so an app update and a pack rebuild can
land in either order without offline transit silently degrading to the online
planner in between. A version it does not know is cleanly rejected
(`TransitIndex::load` returns `None`) and the app falls back to the online MOTIS
planner rather than misreading the file.

## What v4 added

v4 appends three more sections, so sections 0–19 are byte-identical to v3 and
only `VERSION` (4) and `SECTION_COUNT` (23) change. They carry GTFS
`shapes.txt` geometry, so a ride leg draws the path the vehicle actually takes
instead of a line through its stops — which also makes its reported distance
truthful, where stop-to-stop under-reports every ride.

| Section | Payload | Why |
|---|---|---|
| 20 `SHAPE_COORDS` | Concatenated blobs: `u32 point_count` then zigzag-varint delta lat/lon | The polylines. Zigzag varints, **not** the `i16` deltas `graph.rs` uses for road geometry: consecutive `shapes.txt` points are routinely far enough apart (highway running) to overflow an `i16` at 1e-7°. |
| 21 `ROUTE_SHAPE_IDX` | `u32[route_count + 1]` byte offsets | Route → blob. `NONE` means "no shape", and the last entry bounds the section. **Not** a prefix sum: routes producing identical geometry share one blob. |
| 22 `ROUTE_STOP_SHAPE` | `u32[]` parallel to `ROUTE_STOPS` | Each pattern stop's vertex index in its route's shape, non-decreasing, so the device slices `shape[vertex(board)..=vertex(alight)]`. |

Fitting a shape to a pattern (`src/shapes.rs`):

1. Take the **modal `shape_id`** across the pattern's trips. Direction
   differences already form distinct patterns, so what lands here is versioned
   shapes or rare express variants; the modal one keeps the common case exact.
2. **Project each stop onto the shape and insert that projection as a vertex**,
   so a boarded span begins and ends exactly on its stop.
3. **Simplify (Douglas–Peucker, ~2.5 m) only between those pinned vertices**, so
   it can never move or drop one, and trim to `[first stop, last stop]`.
4. `shape_dist_traveled` is used when present on **both** `shapes.txt` and
   `stop_times.txt`, strictly as a monotone *ordering* key — it disambiguates a
   route that passes one stop twice. Its units are feed-defined (km, mi, ft) and
   are never read as metres. Otherwise stops are projected by nearest point,
   forced monotonically non-decreasing.
5. **Validate, then fall back.** A stop further than ~150 m from the shape drops
   the whole fit, and the route stores `NONE` so the device draws stop-to-stop as
   before. The tool reports per-run counts of shaped routes, validation drops,
   and routes whose trips disagreed on `shape_id`.

The Douglas–Peucker tolerance is the lever on pack size: shapes dominate a v4
pack. `shapes.txt` and `stop_times.txt` are the two largest files in a feed, so
both are streamed (`gtfs::read_shapes`, `gtfs::stream_stop_times`) rather than
parsed into a `Csv` of `String`s — see *Build-time memory* below.

## Build-time memory

A world build is bound by RAM, not by pack size. The pack is ~1.5–4 GB, but the
*input* representation used to cost far more: parsing every feed up front held
each one's `Csv` for the whole run, and a `Csv` row is a `Vec<String>` — roughly
10x the bytes of the CSV text it came from. Measured at 6.19 GB of RSS per 1 GB of
GTFS, which projects to ~400 GB for 1272 feeds and OOMs at feed 302.

The builder is incremental instead. `index::IndexBuilder::add_feed_dir` both
ingests a feed and **finalizes** it — routes, trips, profiles, shapes and
stop→route lists all become section bytes before the next feed is read — so the
only things that cross a feed boundary are the sections themselves (which *are*
the pack), the string pool, and `stop_lat`/`stop_lon` (8 B a stop, needed because
the transfer grid, the spatial grid and the bbox are global and `grid_cols` is not
known until the last feed is in). Peak is therefore *pack size + largest single
feed*, not the sum of all feeds:

| | before | after |
|---|---|---|
| 100 feeds (346k stops, 1.24 M trips) | 1.85 GB, 147 s | **0.66 GB, 128 s** |
| `great_britain` alone (9.5 GB of GTFS, 5.8 GB of it `stop_times.txt`) | ~60 GB (projected) | **1.93 GB, 250 s** |

Four things got it there, all verified with `transit_dump` rather than by
byte-diffing:

1. **`stop_times.txt` is streamed**, not parsed into a `Csv` — it is 5.8 GB of the
   9.5 GB `great_britain` feed and every row reduces to a 24-byte `StopTime`. The
   streaming path has to agree with the `Csv` path *exactly*, which is subtler
   than it looks: ids are joined against `trips.txt`/`stops.txt` untrimmed, `\r`
   is swallowed anywhere, a repeated column name resolves to its last occurrence,
   and a quoted field may contain a newline. `gtfs.rs`'s tests pin all four —
   trimming ids alone silently dropped 1562 trips from one feed.
2. **Dedup maps hold no second copy.** `StringPool`, the profile table and the
   shape-blob table are keyed by hash and confirm each candidate against the
   append-only buffer that already holds the bytes. A collision can only fail to
   dedup, never merge two different values. `pattern_key_to_route` is the
   exception and compares `(route_id, stop_pattern)` in full — a false match there
   would fuse two patterns into one route, which is wrong departures rather than
   wasted bytes.
3. **Both grids are flat sorted vectors**, looked up by `partition_point`, instead
   of `HashMap`s holding millions of tiny `Vec`s. Sorting the spatial grid by
   `(cell_id, stop)` is also exactly the order the device's binary search needs.
4. **The pack streams out** through a `BufWriter`: every section length is known
   by `finish_to`, so the directory is computed up front and the pack is never
   concatenated into one giant `Vec` first.

`shape_dist_traveled` stays `f64` throughout. It is only an ordering key, so `f32`
looks free — but feeds give it 8+ significant digits, and at that width `f32`
collapses shape points millimetres apart and flips which segment a stop brackets
onto. Measured on 100 feeds it changed the fitted polyline of 0.3% of routes to
save 4 bytes a row, which is the wrong trade.

## Testing

`cargo test` runs a round-trip harness (`src/index.rs` `#[cfg(test)]`) that
builds a two-feed index and decodes the blob back, asserting profile encoding
reconstructs the original arr/dep times, feeds/ids are namespaced without
collision, the grid nearest-stop lookup is correct, per-feed timezones survive,
exceptions are date-sorted and collapsed to one row per `(service, date)`, and
`STOP_ROUTE_POS` agrees with `ROUTE_STOPS`. The v4 cases cover
`shape_dist_traveled` on both files, shapes without it, no `shapes.txt` at all, a
mismatched shape that must fail validation, a pattern whose trips carry two
`shape_id`s, deltas that would overflow an `i16`, and a shape shared by two
routes — asserting monotone, in-bounds vertex indices in every case.

The reader half is tested independently in
`maps/src/main/rust/src/transit.rs`, whose `#[cfg(test)]` module hand-builds TRX2
blobs and plans over them. The two crates cannot link to each other, so each
codec is exercised by an independent counterpart — which is what catches drift in
a format whose spec lives in a doc comment.

Because both halves are *reimplementations*, they could also drift together. So
`test_fixtures/mini_feed/` (a three-stop feed with a shape) is ingested into
`maps/src/main/rust/test_fixtures/mini.transit`, committed, and read back by
`transit.rs::reads_a_pack_written_by_the_real_ingester`. That pins the two crates
to each other rather than to a shared assumption. Regenerate it after any format
change, from the repo root:

```sh
cargo run --release --manifest-path scripts/maps/gtfs_ingest/Cargo.toml -- \
    maps/src/main/rust/test_fixtures mini \
    mini=scripts/maps/gtfs_ingest/test_fixtures/mini_feed
rm maps/src/main/rust/test_fixtures/mini.transit.json
```

The bytes are a captured artefact, not a reproducible one — trips are grouped out
of a `HashMap`, so route order, profile ids and service indices all vary between
runs. The device-side assertions are semantic for that reason, and so is
`transit_dump`:

```sh
cargo run --release --manifest-path scripts/maps/gtfs_ingest/Cargo.toml \
    --bin transit_dump -- world.transit dump.txt
```

It renders a pack as sort-stable text — header counts, bbox, grid, then per feed
per route (sorted) every trip with its profile expanded back to absolute
`(arr, dep)`, then stops with their routes and footpaths, services keyed by their
calendar rather than their index, and the grid cells. Two builds of the same
corpus produce byte-different packs but identical dumps, so a refactor is verified
by dumping before, dumping after, and diffing. **Do not byte-diff packs.**
`src/reader.rs` is the decoder it uses — deliberately a second implementation of
the device reader, not shared code.

## Building a regional pack

`build_ca_transit.ps1` (repo root: `scripts/maps/`) builds a state-wide pack
natively on Windows, the way `build_graph.ps1` does for the road graph:

```powershell
.\build_ca_transit.ps1                 # -> california.transit
.\build_ca_transit.ps1 -Resolve        # report feed resolution only
.\build_ca_transit.ps1 -Region us-ny -PackName newyork
```

It exists because `build_world_transit.sh` scrapes `url` fields out of the
Transitous registry, and most US sources have none: 38 of California's 49 are
`transitland-atlas` references carrying only a feed id. Scraping URLs yields a
third of the state. This script resolves those ids through the transitland-atlas
DMFR files, prefers a key-free `static_historic` zip when `static_current` sits
behind an API key, and rejects GTFS-realtime endpoints — an agency is often listed
twice, once static and once realtime, and only the atlas entry's `spec`
distinguishes them.

A California run in 2026 resolved 27 static feeds (58,566 stops, 4,441 routes,
267,929 trips) into an 18 MB pack, 95% of routes carrying `shapes.txt` geometry
and `SHAPE_COORDS` taking 2.6 MB of it. Everything it skips is either a realtime
duplicate of a feed already included, or documented upstream as broken.

**The app enumerates every `*.transit` file in its external files dir and
concatenates their departure boards without deduplicating**, so ship exactly one
pack. Replace `world.transit` rather than adding a second file beside it; a backup
must not end in `.transit` or it will be loaded too.

## Known limitations

- Transfers use a straight-line ≤400 m footpath heuristic (not the road graph);
  merging feeds makes these cross-agency for free. The *drawn* walk legs are
  re-routed along the road graph on device, but their timings stay as RAPTOR
  planned them, so a shown ETA is slightly optimistic.
- A route whose trips genuinely use different roads draws whichever the modal
  `shape_id` describes. Stops, timings and transfers stay correct.
- A feed with no `shapes.txt`, or a route whose shape fails validation, draws its
  ride legs stop-to-stop as before.
- GTFS permits several agencies per feed; `FEED_TZ` takes row 0 of `agency.txt`
  and assumes one timezone per feed. A feed with no `agency.txt` gets no
  timezone, and the device falls back to its own zone (the tool warns).
- A journey spanning two timezones is planned entirely in the origin feed's zone.
