# osm_ingest

Host-side Rust tools that turn an OpenStreetMap `.osm.pbf` extract into the
Maps app's offline artifacts. Two binaries share one library:

| Binary | Replaces | Emits |
|---|---|---|
| `road_graph` | `scripts/maps/generator.cpp` | `metadata.bin`, `nodes.bin`, `edges.bin`, `lanes.bin`, `road_names.bin` |
| `poi_extract` | `scripts/maps/poi_extract.cpp` | `<name>.geojsonseq`, `poi_names.bin`, `poi_index.bin` |

## Why Rust (LANGUAGE RULE)

- **Rust-first:** the same rule that made `gtfs_ingest` Rust rather than Python,
  and the router Rust rather than C++ (`maps/src/main/cpp/` is gone). These two
  files were the last hand-written C++ in the Maps pipeline.
- **No libosmium, so no WSL.** The C++ needed `g++` + `libosmium2-dev` +
  `-lz -lexpat -lbz2`, which do not exist on Windows, so regenerating a pack
  meant dropping into WSL. `.osm.pbf` is read natively here: a hand-rolled
  protobuf decoder (`src/proto.rs`, mirroring the encoder in
  `maps/src/main/rust/src/mvt.rs`) over a pure-Rust inflate.
- **Offline builds:** the crate is detached from the repo-root Cargo workspace
  (the empty `[workspace]` in `Cargo.toml`), like `gtfs_ingest`, so `cargo build`
  never pulls the aarch64-android toolchain or the app crates' lints. The single
  dependency, `miniz_oxide`, is a **decode-only** inflate crate — we never
  compress — following the `ruzstd` precedent in
  `networklocation/src/main/rust/Cargo.toml`. It is already in the local registry
  cache because the app's maps crate pulls it via `flate2`.
- **Deterministic**, which the C++ was not. See below.

## Usage

```sh
# Routing graph -> map_data/ (the directory run_generator.sh uploads to R2)
cargo run --release --bin road_graph -- california-latest.osm.pbf --out map_data

# POI layer side files + the geojsonseq tippecanoe consumes
cargo run --release --bin poi_extract -- california-latest.osm.pbf \
    --geojson pois.geojsonseq --names poi_names.bin --index poi_index.bin
```

From Windows, `scripts/maps/build_graph.ps1` wraps the road-graph build in one
command. `scripts/maps/run_generator.sh` and `scripts/maps/build_pois_layer.sh`
call these binaries for the full pipelines.

## Output contracts

Every layout below is read by code elsewhere in the repo. Changing one without
changing its reader produces a graph that loads as garbage, which is why
`metadata.bin` carries a magic and version.

| File | Layout | Reader |
|---|---|---|
| `metadata.bin` | `u32 magic "MARG"`, `u32 version 1`, `u64 node_count` (16 B) | `graph.rs` `GRAPH_MAGIC`/`GRAPH_VERSION` |
| `nodes.bin` | `NodeMaster[node_count + 1]`: `i32 lat_e7, i32 lon_e7, u64 edge_ptr`; Morton-sorted, trailing sentinel whose `edge_ptr == edge_count` | `graph.rs` |
| `edges.bin` | `Edge[edge_count]`, **14 bytes**: `u32 target, u32 dist_mm, u32 name_offset, u8 type, u8 speed_limit` | `graph.rs` — `edge_count` comes from the *file size*, so this must be an exact multiple of 14 |
| `lanes.bin` | `u64 byte_offsets[edge_count + 1]` then the packed `u16` per-lane turn masks | `graph.rs` `LANE_*` |
| `road_names.bin` | deduped NUL-terminated UTF-8 pool; a name offset is a byte offset | `graph.rs` |
| `poi_index.bin` | 14-byte records: `i32 lat_e7, i32 lon_e7, u32 name_off, u16 type`, Morton-sorted | `PoiIndex.kt` |
| `poi_names.bin` | same pool convention as `road_names.bin` | `PoiIndex.kt` |

The POI Morton sort key is computed from the *stored* `lat_e7`/`lon_e7`, not from
the pre-rounding `f64` centroid the C++ used, so the order matches the key a
reader recomputes from the file. On California that removed 9 ordering
inversions the old tool produced.

The POI type numbers live in `src/tags.rs` and are also tabulated in
`scripts/maps/README.md`. **Never renumber an existing type — only append.**

## Determinism

Every output is a pure function of the input file. This is a real change: the C++
generator filled its node array with `idx.fetch_add(1)` from concurrent workers
and interned way names in thread-scheduling order, so two runs of the C++ on the
same extract produced different node ordering, different `name_offset` values and
therefore different `road_names.bin`, `edges.bin` and `nodes.bin`.

The mechanism is in `src/par.rs` and `pbf::run_pass`: work is split into fixed
contiguous chunks (of blobs, ways or nodes), chunks are claimed dynamically so a
heavy chunk cannot stall a core, but each chunk's results are stored at its own
index and merged **in index order**. Name interning then happens single-threaded
during that merge. The chunk size is a constant, not a function of the core
count, so the output does not vary between machines either.

Verified on the full California extract: two runs produce byte-identical
`metadata.bin`, `nodes.bin`, `edges.bin`, `lanes.bin`, `road_names.bin`,
`poi_index.bin`, `poi_names.bin` and geojsonseq.

## How the reader works

A `.osm.pbf` is a flat sequence of `[u32 BE header_len][BlobHeader][Blob]`.
`pbf::scan_blobs` walks that framing once reading only headers, recording each
`OSMData` blob's `(offset, len)`. Threads then each open their own `File`, seek,
read and inflate their own blobs — no channels, no shared queue, no throttling
condvar, which is how the C++ `ThreadPool`/`MoveTask`/`wait_finished` machinery
(and the data race its comments describe) disappears.

Every blob is self-checked: `miniz_oxide` validates the zlib header and the
Adler-32 trailer, and the inflated length is compared against the blob's declared
`raw_size`. Across California's 23,007 blobs that is strong evidence the reader
is correct. Compressions that would need a C library (lzma, bzip2, lz4, zstd)
are reported as a clear error rather than silently skipped, because a skipped
blob would yield a quietly incomplete graph.

Because a PBF stores all nodes, then all ways, then all relations, the first pass
records which entity kinds each blob held; later passes skip blobs outright.
`road_graph` needs two passes (ways must be known before we know which nodes
matter) and `poi_extract` needs three (relations -> ways -> nodes).

## Testing

`cargo test` runs 46 in-crate tests, following the `gtfs_ingest` pattern. The
fixture in `src/testpbf.rs` hand-encodes a complete `.osm.pbf` — including real
zlib framing built from stored DEFLATE blocks and a real Adler-32 — so the
decoder is tested against bytes produced independently of it.

- **protobuf**: uvarint at every 7-bit boundary, 10-byte negative `int64`,
  zigzag against the spec's vectors, packed deltas, skipping unknown fields of
  every wire type, and truncated input rejected.
- **`latlng_to_spatial`** compared against a transcription of
  `graph.rs::latlng_to_spatial` for a table of coordinates. If these drift the
  device's spatial binary search silently returns the wrong nodes.
- **`accurate_dist_mm`** against known city-pair great-circle distances, and
  across the antimeridian and pole to pole (where a naive `i32` coordinate
  subtraction overflows).
- **tags**: `get_hw_id`, `parse_maxspeed` (`mph` ×1.60934, `knots` ×1.852,
  non-numeric -> 0), `turn:lanes` (`|` lanes, `;` indications, unknown ->
  `LANE_NONE`, count pad/truncate), and POI `classify` precedence including the
  rule that bare `railway`/`public_transport` values are *not* POIs.
- **whole pipeline**: the fixture through `road_graph`, asserting the exact
  `metadata.bin` bytes, the `nodes.bin` sentinel, `edges.bin % 14 == 0`, Morton
  ordering, the full decoded edge set, the exact `road_names.bin`, and the
  `lanes.bin` offset/blob split — plus the same for `poi_extract`, and a
  two-runs-are-identical check for both.
- **ring centroid**: that the repeated ring vertex is the one with the smallest
  `(lon, lat)`, so the centroid does not move when the ring's start rotates.
  Getting this wrong put small buildings metres off the old tool's output.

## Cross-checked against the C++ it replaces

The C++ cannot be a byte-for-byte oracle (it was nondeterministic), so the port
was validated *structurally*. Both toolchains were run over the same San Luis
Obispo extract — the C++ built in WSL against libosmium 2.20 — and compared on
invariants that do not depend on its thread-scheduling-dependent ordering:

| Invariant | Result |
|---|---|
| `metadata.bin` magic / version / `node_count` | identical (88,666 nodes) |
| `edge_count` | identical (181,481) |
| `lanes.bin` length | identical (1,453,984 B) |
| `road_names.bin` string set | identical (1,125 names) |
| Node coordinates | every one matches; 7,164 differ by exactly one 1e-7 unit (1.1 cm) — see below |
| Edge `(type, speed_limit, name)` groups + `dist_mm` | identical groups, every distance within 12 mm |
| Out-degree sequence | identical |
| POIs the C++ found | all 1,413 present, identical `name` + `type` |
| Node/closed-way POI coordinates | all within one 1e-7 unit |
| Relation POI centroids | 4 of 15 over 1 m, worst 3.1 m |

Two deliberate differences:

- **Coordinates are 1.1 cm more accurate.** The C++ recovered `lat_e7` as
  `(int32_t)(location.lat() * 1e7)` — truncating a double that libosmium had
  already divided by 1e7 — so ~8% of coordinates came out one unit low. This
  keeps the PBF's exact integer, which also shifts `dist_mm` by a few mm.
- **Three extra POIs.** libosmium's assembler cannot close a ring whose member
  ways were cut off by the bbox, so it silently dropped three boundary relations
  (Los Padres National Forest, Santa Lucia Wilderness, Cuesta Ridge). The centroid
  approximation here still places them. Gaining a named POI is the better failure
  mode, and on an unclipped extract the question does not arise.

## Known limitations

- **Relation POI centroids are approximated.** libosmium assembled true polygon
  rings (`MultipolygonManager` + `Assembler`); porting a ring assembler is a
  large piece of work on its own. Here a relation's outer ring is approximated by
  the deduplicated node ids of its `outer`-role member ways. That is exact when
  the ring is a single closed way; when it is split across several, the measured
  error was under 3.1 m across the sample above. POIs render as points at z12–z16,
  so this is not visible, but `poi_index.bin` is not byte-identical to the C++ for
  relation-derived POIs. Node- and closed-way-derived POIs match exactly.
- **Closed-way areas** use `first ref == last ref` where libosmium compared end
  *locations*. Equivalent for real OSM data and it avoids needing a location
  index during the way pass.
- **Memory.** `road_graph` holds a 2.5 GB node-id bitset (sized from the C++'s
  `BITSET_SIZE = 20e9`, so node ids at or above it are skipped exactly as
  before) plus the node and edge arrays: roughly 10 GB peak on California. Same
  ballpark as the C++, and it will not run on a small machine.
- `build_pois_layer.sh` still needs `tippecanoe` and `osmium-tool` for the
  `.pmtiles` output, so the POI *tile* build stays non-Windows. Only the two side
  files became native.
