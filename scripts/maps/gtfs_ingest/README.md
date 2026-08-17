# gtfs_ingest (P11a)

Host-side Rust build tool that converts a region's **GTFS** feed into a compact
**on-device transit index** (`<feed>.transit`) for the Maps offline region
packs. It is the ingest half of offline public-transit routing; the on-device
planner that consumes the index is `maps/src/main/rust/src/transit.rs` (P11b).

## Why Rust + directory input

- **Rust-first (LANGUAGE RULE):** GTFS ingest is done in Rust, not Python. No
  `normalize_gtfs.py`.
- **Zero dependencies / offline builds:** the tool is `std`-only and detached
  from the repo-root Cargo workspace (see the empty `[workspace]` in
  `Cargo.toml`), so `cargo build` here resolves without the network and without
  the Android/aarch64 toolchain or the app crates' lints.
- **Unzipped input:** it reads an *unzipped* GTFS directory rather than a `.zip`,
  which avoids pulling in a DEFLATE crate. Unzipping is a one-line pre-step.

## Usage

```sh
# 1. Fetch a region feed (Transitous registry points at each agency's GTFS zip):
#    https://github.com/public-transport/transitous  ->  feeds/*.json
unzip sf_bay_511.zip -d /tmp/sf_bay

# 2. Build the compact index + manifest:
cargo run --release -- /tmp/sf_bay sf_bay /path/to/pack/out
#   -> /path/to/pack/out/sf_bay.transit        (mmap'd on device)
#   -> /path/to/pack/out/sf_bay.transit.json    (manifest: bbox, counts, size)
```

`<feed_name>` is the feed id used as the `present_feeds` key on device (it must
match the GTFS feed folder name the app recognises).

## Output

- `<feed>.transit` — the binary index. On-disk layout is documented at the top
  of `src/index.rs` and **must stay in sync** with `transit.rs`. It holds an
  interned string pool, stops, RAPTOR routes (trips grouped by identical stop
  pattern), delta-free `stop_times` in seconds since service midnight, a
  stop→routes index, footpath transfers (≤400 m), and calendar service masks +
  `calendar_dates` exceptions.
- `<feed>.transit.json` — a small manifest the packaging step (P11c) uses to
  list the transit part alongside the pmtiles.

Typical size is ~2–15 MB per metro (see `P11_OFFLINE_TRANSIT_SPIKE.md`).

## Known limitations (v1)

- Transfers use a straight-line ≤400 m footpath heuristic (not the road graph).
- Service masks cover `calendar.txt` weekdays + `calendar_dates.txt` exceptions;
  per-feed timezone handling is left to the on-device planner's query time.
