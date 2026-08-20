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
  `normalize_gtfs.py`.
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
  interned string pool, stops, a FEEDS table, RAPTOR routes (trips grouped by
  identical stop pattern) with **varint-packed per-route trip arrays**,
  **deduplicated timetable profiles** (per-stop run-time shape, varint-delta
  encoded), a stop→routes index, footpath transfers (≤400 m, cross-feed), a
  **sparse spatial grid** (CSR by cell for O(cell) nearest/bbox), and calendar
  service masks + `calendar_dates` exceptions.
- `<pack>.transit.json` — a small manifest with counts, bbox and a **per-section
  byte breakdown** so the compression win (profiles vs the old per-trip
  stop-times) is visible.

## Why TRX2 (v2)

v1 stored `(arr,dep)` u32 per stop *per trip* (8 B) and referenced them via a
`u32 first_stoptime`, so a worldwide pack was ~10–20 GB and near the 4.29 B
stop-time ceiling. v2 factors each trip into `{ start_time, profile_id }` and
shares one **profile** (varint hop+dwell offsets) across every trip with the
same run-time shape — killing both the per-trip stop-time table and the ceiling.
Section offsets in the directory are u64 so the file may exceed 4 GB. Expected
world size drops to roughly **~1.5–4 GB** while staying on u32 indices.

## Testing

`cargo test` runs a round-trip harness (`src/index.rs` `#[cfg(test)]`) that
builds a two-feed index and decodes the blob back, asserting profile encoding
reconstructs the original arr/dep times, feeds/ids are namespaced without
collision, and the grid nearest-stop lookup is correct.

## Known limitations

- Transfers use a straight-line ≤400 m footpath heuristic (not the road graph);
  merging feeds makes these cross-agency for free.
- Service masks cover `calendar.txt` weekdays + `calendar_dates.txt` exceptions;
  per-feed timezone handling is left to the on-device planner's query time.
