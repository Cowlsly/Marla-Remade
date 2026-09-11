# geodb_build — offline geocoder database builder

Builds `geocoder-v3.geodb`, the offline reverse/forward geocoder database that
`:networklocation` serves the system `GeocodeProvider` from.

Replaces the `osmium tags-filter | osmium export | scripts/geocoder_gen.cpp` chain, which
needed osmium, g++, OpenMP, simdjson and POSIX `mmap` — so WSL only — and wrote a **101 GB**
GeoJSONSeq intermediate on the way to a 1.4 GB database. This reads the `.osm.pbf` natively
through `scripts/maps/osm_ingest`, holds no intermediate, and runs on Windows.

## What v3 indexes that v2 did not

v2 held **only** objects carrying both `addr:housenumber` and `addr:street`. Everything below
was simply absent, in both directions:

| Added | Why it mattered |
| --- | --- |
| **Named streets**, sampled along their length | Reverse geocoding could return a house number or nothing; it could never say "you are on Market Street". |
| **Points of interest** — `amenity`/`shop`/`tourism`/`leisure`/`office`/`healthcare` with a name | A named building or shop was invisible. |
| **Populated places** — `place=city\|town\|village\|hamlet\|suburb\|neighbourhood\|quarter\|borough\|island` | No way to say which neighbourhood or village a point is in. |
| **`addr:place` addresses** | Much of Germany and Austria, and most of Japan and Korea, address buildings against a place rather than a street. Requiring `addr:street` silently discarded all of them. |

Coordinates also moved from **e6 to e7**, which is the precision OSM itself stores, so the
conversion no longer throws away a decimal digit.

On California the result is 8.96 M records where the old rules would have given 3.66 M.

## Building and running

```sh
cd scripts/networklocation/geodb_build
cargo build --release

# rehearse on a metro, then a state, before the planet
./target/release/geodb_build ../../../analysis/mamaps/SanFrancisco.osm.pbf --out sf.geodb
./target/release/geodb_build california-latest.osm.pbf --out ca.geodb
./target/release/geodb_build planet-latest.osm.pbf --out geocoder-v3.geodb
```

Options: `--bbox W,S,E,N` to clip, `--threads N` (default: all cores, or `MAPS_THREADS`),
`--no-verify` to skip the read-back.

Detached from the Android Rust workspace like `scripts/maps/osm_ingest`, so the build never
pulls the aarch64 toolchain. `zstd` is the one C dependency: the device reader decompresses
with the pure-Rust `ruzstd`, which has no encoder, and nothing here cross-compiles.

## Publishing

```sh
sha256sum geocoder-v3.geodb
../../maps/publish_r2.sh geocoder-v3.geodb
```

Then put the hash into `OfflineDatabases.sha256For` in the app.

## The three orderings that decide whether it works

None of these fails loudly. A database with any of them wrong opens fine and returns
plausible nonsense, which is why `write::verify` re-reads the finished file through an
independent path and checks all three.

1. **Records are ordered by grid cell, then Z-order within the cell.** Reverse lookup walks
   outward from the query's cell, so this ordering *is* the spatial index.
2. **Searchable dictionaries are sorted by UTF-16 code unit**, because the reader binary
   -searches them with a UTF-16 comparator. UTF-8 and UTF-16 disagree above the BMP: `U+10000`
   sorts after `U+FFFF` in UTF-8 but before it in UTF-16, because surrogates start at `0xD800`.
   Sorting the wrong way makes forward lookups start missing entries near the disagreement.
3. **The forward and name indexes are permutations into grid order**, each sorted by its own
   key. Out of step with its dictionary, forward search returns the wrong rows.

The Z-order interleave is the format's other landmine. v2 interleaved 16 bits per axis, which
worked only because an e6 cell is 50 000 units wide. **An e7 cell is 500 000 units and needs
19**, so `format::morton_in_cell` interleaves 20. Getting that wrong does not fail; it silently
produces a worse ordering, and on overflow a wrong one.

## Determinism

Two runs over the same input produce byte-identical output, verified on full California. This
is not free: interned dictionary ids are assigned in first-seen order, which depends on how the
PBF's blobs were chunked, so `write` re-sorts each tied run once the final content-derived ids
are known (`sort_rows_deterministically`). A `HashMap` iteration or a non-total sort key
silently destroys the property, and only a byte comparison catches it.

## Memory

The two things that decide whether a planet build fits:

- **Rows are interned during extraction, not after.** Seven `String`s per row is about 250 GB
  at planet scale; seven `u32` ids is 40 bytes a row. Every pass uses `run_pass_sink` rather
  than `run_pass` so a chunk's owned strings are interned and dropped as it arrives, instead of
  every chunk's strings being alive at once.
- **`osm_ingest::nodeloc` backs its coordinate array with a memory-mapped file**, so the node
  table costs 8 bytes per needed node in RAM rather than 16.

## Compression

Column blocks are zstd level 19 and are compressed **in parallel** — they are independent by
construction, and at level 19 zstd manages only a couple of megabytes a second per core. On
California this took the write phase from 335 s to 62 s, and the output is byte-identical to
the sequential version.
