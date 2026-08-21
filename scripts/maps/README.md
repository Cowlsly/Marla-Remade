# scripts/maps — PMTiles generator (`v5.pmtiles`)

Builds the custom **`v5.pmtiles`** consumed by the `maps` app. This single file
**replaces two things** the app ships/streams today:

| Old | New |
|-----|-----|
| `https://data.vayunmathur.com/v4.pmtiles` (base tiles) | `v5.pmtiles` base layers (identical schema) |
| `maps/src/main/assets/admin0.fgb` + `admin1.fgb` (border masks) | `v5.pmtiles` `admin_country` / `admin_region` / `admin_city` layers |

On top of the base it also **bakes** (a) safety / road-furniture data (speed
cameras, ALPR/surveillance, stop signs, traffic signals) so the app never needs
a runtime Overpass query, (b) posted **speed limits** (`maxspeed`) for the
P5b speed-limit feature, and (c) OSM **transit lines** (`transit_lines`:
rail/subway/tram/light-rail/monorail/train) for the P22 transit-line highlight —
all from the same file.

> This directory is the **generator**. Running the full planet build is an infra
> step (large + long-running); the scripts are designed to be correct, modular
> and runnable, and to support a small metro dry run for validation.

---

## What was there before (investigation summary)

* **Base tiles** = Protomaps' prebuilt basemap. `v4.pmtiles` (137 GB) is just
  downloaded from `demo-bucket.protomaps.com` and re-hosted on Cloudflare R2 by
  [`vendor_pmtiles.sh`](vendor_pmtiles.sh). There was **no** local tile-building
  pipeline. The style (`maps/src/main/assets/style.json`) expects the Protomaps
  schema (source-layers below).
* **Borders** = two vendored FlatGeobuf files read by
  `maps/src/main/java/com/vayunmathur/maps/data/CountryMap.kt`:
  * `admin0.fgb` — countries, matched by property **`ISO_A2`**
  * `admin1.fgb` — states/provinces, matched by property **`iso_3166_2`**
  Used to draw a dimming *inverted mask* around a selected country/state
  (`ui/MyMapLayers.kt` → `createInvertedMask`). No city-level file existed.
  Attribute schema (`ISO_A2`, `iso_3166_2`) is Natural-Earth-derived.
* **Offline packs / routing** (context for later phases, **not built here**):
  * `extract_pmtiles.sh` slices the world pmtiles into 64 Morton-grid
    `zone_$i.pmtiles` offline packs. Tiles stay **zoned** (a single global
    basemap is a ~137 GB download — see the size table below).
  * `osm_ingest` + `run_generator.sh` build the **routing graph** as a
    **single global graph** (`nodes.bin`, `edges.bin`, `lanes.bin`,
    `metadata.bin`, `road_names.bin`) — see
    [Single global routing graph](#single-global-routing-graph-p16)
    below. It carries **roads only**; public-transit timetables live in the
    separate `.transit` index built by `gtfs_ingest`.

---

## Output schema (source-layers)

The base layers keep the **exact** Protomaps names so `style.json` works
unchanged; the new layers are additive.

**Base (unchanged, from Protomaps schema):**

```
earth  landcover  landuse  water  roads  buildings  boundaries  pois  places
```

**New layers added by this generator (for P13 to reference in `style.json`):**

| source-layer | geometry | key attributes |
|---|---|---|
| `safety` | Point | `kind` ∈ {`speed_camera`,`alpr`,`surveillance`,`stop_sign`,`traffic_signals`}, `name?`, `direction?`, `operator?`, `ref?`, `osm_id` |
| `maxspeed` | LineString/MultiLineString | **`maxspeed`** (raw OSM value, e.g. `"35 mph"`, `"50"`, `"50 km/h"`), `highway?`, `name?`, `osm_id` |
| `transit_lines` | LineString/MultiLineString | **`kind`** ∈ {`rail`,`subway`,`light_rail`,`tram`,`monorail`,`train`}, `name?`, `ref?`, `colour?` (route colour, e.g. `"#DA291C"`), `osm_id` |
| `ma_pois` | Point | **`name`** (string), **`type`** (number, see POI type map), `osm_id` (number). OUR baked OSM POI layer — NOT the base `pois` layer. |
| `admin_country` | Polygon/MultiPolygon | `admin_level=2`, `name`, `name_en`, **`ISO_A2`**, `iso_a3` |
| `admin_region` | Polygon/MultiPolygon | `admin_level=4`, `name`, `name_en`, **`iso_3166_2`**, `country_iso` |
| `admin_city` | Polygon/MultiPolygon | `admin_level=8`, `name`, `name_en` |

The admin layers deliberately preserve `ISO_A2` (countries) and `iso_3166_2`
(regions) — the same keys `CountryMap.kt` matches on — so P13 can repoint the
mask lookup at the PMTiles source with **no attribute changes**.

The `maxspeed` layer feeds P5b's `MaxspeedSource`, which reads the posted speed
limit via `queryRenderedFeatures` on **source-layer `maxspeed`**, property
**`maxspeed`**. It is a dedicated layer (not an attribute on the base `roads`
layer) so it works whether the base is freshly built or the upstream Protomaps
base is reused verbatim. P13 points `MaxspeedSource.PMTILES_URL` at v5.pmtiles.

The `transit_lines` layer feeds the **P22** transit-line highlight: the app
queries **source-layer `transit_lines`** and styles each feature by **`kind`**
(rail/subway/light_rail/tram/monorail/train) and, where present, the route
**`colour`** (raw OSM `colour`/`color` value from the route relation, e.g.
`"#DA291C"` or a colour name). `name`/`ref` are carried for labels. Features come
from both railway ways (complete geometry, kind from `railway=*`) and route
relations (named/coloured lines, kind from `route=*`), so a given corridor may be
covered by both a way feature and a relation feature — filter on `colour`/`ref`
presence if the app wants only the named route features.

The **`ma_pois`** layer is OUR own baked OSM POI layer — the P27 feature that
lets POI **placement / name / type** come from OpenStreetMap instead of a
runtime Google viewport scrape (Google is then hit only for rich details on
tap). It deliberately does **not** reuse the base Protomaps `pois` layer (which
the app suppresses). It is built by [`build_pois_layer.sh`](build_pois_layer.sh)
(→ [`osm_ingest`](osm_ingest/)'s `poi_extract`) at zooms **z12–z16**. The same
pass also
emits two compact side files (`poi_names.bin` + `poi_index.bin`, formats below)
so the app can mmap + binary-scan POIs; the layer and the side files are
mutually consistent (same POI set, same coordinates). See
[POI layer & side files (P27)](#poi-layer--side-files-p27) below.

---

## Data sources & rationale

* **Base** — Protomaps basemaps. Either **regenerate** with Planetiler + the
  official Protomaps basemap profile (reproducible), or **reuse** the upstream
  prebuilt file (fast; identical schema). See `--base-mode` below.
* **Safety** — OpenStreetMap. Tag → `kind` mapping:
  * `highway=speed_camera` or `enforcement=maxspeed` → `speed_camera`
  * `man_made=surveillance` + ALPR signals → `alpr`
    (DeFlock / [deflock.me] tag ALPRs as `surveillance:type=ALPR`, or
    `camera:type=alpr`; Flock Safety is the dominant operator/manufacturer)
  * `man_made=surveillance` (any other) → `surveillance`
  * `highway=stop` → `stop_sign`
  * `highway=traffic_signals` → `traffic_signals`
* **Maxspeed** — OpenStreetMap. Ways carrying a `maxspeed` (or
  `maxspeed:forward`/`maxspeed:backward`) tag; the raw value is kept verbatim so
  the app parses `mph`/`km/h`/bare numbers itself. Non-numeric OSM values
  (`none`, `signals`, `walk`, etc.) are also passed through raw — the app-side
  parser (`MaxspeedSource`) decides how to render them.
* **Transit lines** — OpenStreetMap. Two inputs feed the `transit_lines` layer:
  * railway **ways** where `railway` ∈ {`rail`,`subway`,`light_rail`,`tram`,
    `monorail`,`narrow_gauge`} → `kind` (`narrow_gauge` folds into `rail`).
    Exported by `osmium export` (ways export cleanly as LineStrings).
  * route **relations** where `route` ∈ {`subway`,`tram`,`light_rail`,`train`,
    `monorail`} (bus is intentionally excluded) → `kind`, and the relation
    `colour`/`color` is carried onto the geometry. `osmium export` does not emit
    linear relations as geometry, so these are assembled by GDAL's OSM
    `multilinestrings` layer (`ogr2ogr`), whose `other_tags` HSTORE is parsed by
    the normalizer to recover `route`/`colour`/`ref`. If `ogr2ogr` is not
    installed the layer is still built from railway ways alone (colour omitted).
* **POIs (`ma_pois`)** — OpenStreetMap. Any node OR way/relation-area that has
  BOTH a `name` tag AND one of the recognised POI keys
  (`amenity`/`shop`/`tourism`/`leisure`/`office`/`healthcare`) becomes a POI. The
  tag value maps to a stable **type number** (see the [POI type map](#poi-type-map));
  a recognised key with an unmapped value falls into the `255` = "other" bucket.
  Way/relation geometry is reduced to a representative centroid so every POI
  renders as a point. Requiring a `name` naturally filters out unnamed street
  furniture (`amenity=bench`, etc.).
  Extraction is a single pass of [`osm_ingest`](osm_ingest/)'s `poi_extract`
  (Rust, reads `.osm.pbf` natively) — nodes are read directly, closed ways use
  their own node ring, and `type=multipolygon`/`boundary` relations use the node
  locations of their `outer`-role member ways. The same pass emits the tile
  layer's geojsonseq AND the two side files, so all three are mutually
  consistent.
* **Admin borders**:
  * country + region → **Natural Earth 10m** (`ne_10m_admin_0_countries`,
    `ne_10m_admin_1_states_provinces`). Chosen because the current `.fgb` are
    Natural-Earth-derived and keyed by `ISO_A2` / `iso_3166_2` → byte-for-byte
    attribute compatibility, plus clean generalized geometry that is ideal for
    the dimming mask.
  * city → **OSM `boundary=administrative` + `admin_level=8`** (Natural Earth
    has no city polygons).

---

## Files

| File | Purpose |
|---|---|
| `build_all.sh` | **The superscript**: one command from `.osm.pbf` + GTFS to all 9 runtime artifacts, with per-stage stamps and a `manifest.txt` of sizes and SHA-256s. Chains graph → pois → transit → tiles |
| `build_all.ps1` | **Windows twin** of the above. Stages graph/pois/transit are complete; the tiles stage composites only the cargo-only layers onto a `-BaseArchive` (see [Caveats](#caveats--known-limitations)) |
| `run_generator.sh` | **Deprecated** thin wrapper over `build_all.sh` (graph + pois + publish) |
| `build_v5_pmtiles.sh` | Tile orchestrator: base + safety + maxspeed + transit_lines + ma_pois + admin + transit_stops → merge → `v5.pmtiles` |
| `build_base_layers.sh` | Base tiles (Planetiler build **or** reuse upstream `v4.pmtiles`) |
| `build_safety_layer.sh` | **`--engine rust`** (default): `osm_ingest` `osm_extract` → geojsonseq → `tile_build` `tile_points` → `safety.pmtiles`, cargo-only. `--engine legacy`: osmium → GeoJSON → `normalize_safety.py` → tippecanoe |
| `build_maxspeed_layer.sh` | osmium → GeoJSON → `normalize_maxspeed.py` → tippecanoe → `maxspeed.pmtiles` |
| `build_transit_lines_layer.sh` | osmium + ogr2ogr → GeoJSON → `normalize_transit_lines.py` → tippecanoe → `transit_lines.pmtiles` |
| `build_pois_layer.sh` | `osm_ingest` `poi_extract` → geojsonseq + `poi_names.bin` + `poi_index.bin` → `tile_points` (or tippecanoe with `--engine legacy`) → `ma_pois.pmtiles` |
| `osm_ingest/` | Rust OSM ingest crate (detached, `.osm.pbf` read natively): `poi_extract` (POI layer + side files), `road_graph` (routing graph) and `osm_extract` (one geojsonseq per baked vector layer). Full TYPE MAP and on-disk layouts in `src/poi_build.rs` / `src/graph_build.rs`; see [`osm_ingest/README.md`](osm_ingest/README.md) |
| `build_graph.ps1` | Windows entry point for the routing-graph build (no WSL, no g++) |
| `build_admin_layers.sh` | Natural Earth / OSM → `normalize_admin.py` → tippecanoe → `admin_*.pmtiles` |
| `build_transit_stops_layer.sh` | GTFS dirs → `gtfs_ingest` `transit_stops` → geojsonseq → `tile_build` `tile_points` → `transit_stops.pmtiles`. **No tippecanoe, no osmium** |
| `build_transit_stops_layer.ps1` | **Windows** entry point for the same layer (cargo-only) |
| `gtfs_ingest/` | Rust GTFS crate (detached): `gtfs_ingest` (the on-device `.transit` pack) and `transit_stops` (the basemap stop layer). TRX2 on-disk format documented in `src/index.rs` |
| `tile_build/` | Rust MVT + PMTiles v3 crate (detached), **replacing tippecanoe and tile-join**: `tile_points` (tile a point geojsonseq), `tile_join` (merge archives) and `pmtiles_dump` (canonical text dump, for the differential harness). Container layout documented in `src/pmtiles.rs` |
| `publish_r2.sh` | Upload built artifacts to Cloudflare R2 (creds from env vars only) |
| `normalize_safety.py` | OSM tags → `safety` layer schema (pure stdlib, unit-tested). **Superseded** by `osm_ingest/src/safety.rs`; kept as the contract of record and as `--engine legacy` for the differential harness |
| `normalize_maxspeed.py` | OSM maxspeed ways → `maxspeed` layer schema (pure stdlib, unit-tested) |
| `normalize_transit_lines.py` | OSM railway ways + route relations → `transit_lines` schema (pure stdlib, unit-tested) |
| `normalize_admin.py` | NE/OSM attrs → admin layer schema (pure stdlib, unit-tested) |
| `test/test_normalize.py` | Dry-run unit test of the schema mapping (no external tools) |
| `test/diff_geojsonseq.py` | **Differential harness**, extraction half: diffs a legacy GeoJSONSeq against a Rust one. Properties exact, points exact, lines/polygons within an epsilon |
| `test/diff_pmtiles.py` | **Differential harness**, tiling half: diffs a tippecanoe archive against ours via `pmtiles_dump` — layer presence, property key sets, extent, geometry mix, and feature counts against a stated drop budget |
| `test/fixtures/*` | Tiny sample inputs for the test |

---

## Caveats & known limitations

These are structural, not bugs waiting to be fixed. Read them before assuming a
build is reproducible or cargo-only.

### 1. Planetiler cannot be ported

`build_base_layers.sh --mode build` runs Java 21 plus the Protomaps basemap jar.
Nothing here replaces it, so **`--base-mode build` is the one documented Java
exception** and it fails loudly when `java` is absent rather than silently
falling back. The default is `--base-mode reuse`, which fetches a published
archive with `curl` (plus `go-pmtiles` today for a `--bbox` extract).

### 2. Natural Earth is a third input

`admin_country` and `admin_region` come from `ne_10m_admin_0_countries` and
`ne_10m_admin_1_states_provinces`. They are **not derivable from OSM**, so the
default is to carry those two layers forward from the published archive rather
than rebuild them. "OSM + GTFS only" therefore holds for every layer that
actually changes between builds; it does not hold for national and state borders,
which change on a different clock and from a different source.

### 3. Output is not byte-identical to tippecanoe, by design

`--drop-densest-as-needed` is a lossy per-tile heuristic, and
`--extend-zooms-if-still-dropping` can push an archive *past* its own
`--maximum-zoom`. Our tilers implement a deterministic, documented drop policy
and a fixed max zoom instead. Consequences:

* Per-tile feature counts differ. `test/diff_pmtiles.py --max-feature-delta`
  is the explicit budget for that; the flag exists so the divergence has a number
  rather than being unbounded.
* Tests assert **our own invariants** (ring closure, winding order,
  Douglas-Peucker monotonicity, PMTiles round-trip), never equality with
  tippecanoe.
* An MVT re-encode rebuilds each layer's key/value dictionaries in first-use
  order, so even an untouched tile can come back a few bytes different. Compare
  the decoded model, which is what the spec defines.
* `--detect-shared-borders` is not reproduced. Adjacent admin polygons are
  simplified independently, so at low zoom a shared border can show a hairline
  gap or overlap. Noted rather than papered over.

### 4. World transit is a large step up in cost

Today's `world.transit` is built from `.url` fields alone, which silently drops
most US feeds — 38 of California's 49 sources — because they are referenced by
`transitland-atlas-id` with no URL. Resolving those references the way
`build_ca_transit.ps1` does turns 27 feeds and 18 MB into hundreds of feeds and
many GB of downloads. **A `--region` filter is mandatory** for staging: validate
on California, then a few regions, then the world.

The two producers also differ in what they can emit. Only the DMFR-resolving
path knows each feed's original source name, so only it can write the three-field
manifest that gives stops their MOTIS ids — and without those, live delays cannot
be matched to a stop.

### 5. Credential env vars are not uniform

`publish_r2.sh` reads `R2_ENDPOINT` / `R2_ACCESS_KEY_ID` /
`R2_SECRET_ACCESS_KEY`. `vendor_pmtiles.sh` also accepts the `AWS_*` pair. The
deprecated `run_generator.sh` used `R2_ACCESS_KEY` / `R2_SECRET_KEY` and now
bridges them onto the first set.

### 6. Intermediates are never deleted

`build_v5_pmtiles.sh` keeps everything under `--workdir`, and
`build_admin_layers.sh` leaves its `admin_*.geojsonseq` in `--outdir`. That is
what makes a re-run cheap. `--keep-work` only silences the reminder; it does not
change the behaviour.

---

## Prerequisites (tools)

Full build needs (all are existing off-the-shelf tools — the only code authored
here is Rust and Python):

* **cargo** (https://rustup.rs) — and for a growing share of the pipeline, the
  *only* thing needed. It builds:
  * [`osm_ingest`](osm_ingest/) — the `ma_pois` extractor, the routing graph, and
    `osm_extract` for the baked vector layers.
    No C/C++ toolchain and no libosmium: it reads `.osm.pbf` natively.
  * [`gtfs_ingest`](gtfs_ingest/) — the on-device `.transit` pack and the
    `transit_stops` basemap layer.
  * [`tile_build`](tile_build/) — MVT and PMTiles v3 read/write, which **replaces
    tippecanoe and tile-join**. The final merge and the `transit_stops` layer both
    go through it, so they run on **Windows**.
* **tippecanoe** ≥ 2.x — still required by the maxspeed, transit_lines and admin
  layer scripts, which pipe GeoJSON through it. The merge no longer uses its
  `tile-join`, and `safety` and `ma_pois` no longer use it at all.
* **osmium-tool** (`osmium`) — still required by those same OSM-derived layers, and
  by any `--bbox` run of `build_all.sh` (which clips once for every stage).
* **GDAL** (`ogr2ogr`) — transit_lines and admin only.
* **python3** (stdlib only) — the remaining `normalize_*.py` schema mappers, the
  schema unit test, and the differential harness.
* Base build mode only: **Java 21** + the Protomaps basemap jar
  (build once from `github.com/protomaps/basemaps`, `tiles/` → `mvn package`)
* Base reuse mode only: `curl`, and `go-pmtiles` (`pmtiles`) if using `--bbox`

### What builds on Windows

Everything that is cargo-only: the routing graph (`build_graph.ps1`), the POI
layer and its side files, the `safety` layer, the offline transit pack
(`build_ca_transit.ps1`), the `transit_stops` layer
(`build_transit_stops_layer.ps1`) and the final merge (`tile_join`).
`build_all.ps1` chains those. The remaining OSM-derived vector layers (maxspeed,
transit_lines, admin) still need tippecanoe + osmium + python3 + GDAL and
therefore a Linux box or WSL, which is why the Windows tiles stage composites onto
a `-BaseArchive` instead of building a complete `v5.pmtiles`.

That is why `tile_build` exists rather than shelling out to tippecanoe: there is
no Windows path for it (see the note at `build_graph.ps1:29`), which would
otherwise have left `transit_stops` unbuildable on the dev box.

That is why `tile_build` exists rather than shelling out to tippecanoe: there is
no Windows path for it (see the note at `build_graph.ps1:29`), which would
otherwise have left `transit_stops` unbuildable on the dev box.

> **`tile_join` memory.** It reads every input archive wholly into memory. That is
> fine for the overlay layers (tens of MB each) but **not** for compositing against
> the full 1.5 GB published basemap — do that on a machine with the RAM for it, or
> merge the overlays first and composite once.

Install

Install (Fedora/Amazon Linux, matching `run_generator.sh` conventions):

```bash
sudo dnf install -y osmium-tool gdal python3 curl unzip
# tippecanoe: build from github.com/felt/tippecanoe (make && make install)
```

The **dry-run test** needs only `python3`.

---

## Run commands

### 0. Everything, in one command

`build_all.sh` is the entry point. It builds all 9 artifacts in dependency order,
stamps each stage so a re-run is cheap, and writes a `manifest.txt` of sizes and
SHA-256s that should be identical across two runs of the same inputs.

```bash
# see the plan without touching anything
./build_all.sh --pbf norcal-latest.osm.pbf --bbox -122.6,37.2,-121.7,37.9 --dry-run

# a metro build, fetching the PBF and resolving one GTFS region
./build_all.sh --geofabrik north-america/us/california \
    --gtfs-region 'us-ca' --out-dir ./ca_build --out ./ca_build/v5-ca.pmtiles

# then publish all 9
./build_all.sh <same args> --publish
```

The `--bbox` clip happens **once**, up front, and is shared by every stage — so
unlike the per-layer scripts, the routing graph honours it too. That needs
`osmium`.

Per-layer `--engine rust|legacy` flags exist for every layer so a ported layer can
be rolled back with one flag. Asking for an engine that has not landed is an
error, never a silent fall-back. `safety` and `ma_pois` default to `rust`; the
rest still default to `legacy`.

> **Validate a flipped engine before trusting it.** Each layer that moves to Rust
> has unit tests mirroring the Python normaliser's assertions, but those pin the
> schema, not the extraction over real data. Run both engines over the same PBF
> and diff them before publishing:
>
> ```bash
> ./build_safety_layer.sh --pbf norcal.osm.pbf --bbox -122.6,37.2,-121.7,37.9 \
>     --engine legacy --out /tmp/safety-legacy.pmtiles \
>     --geojson-out /tmp/safety-legacy.geojsonseq
> ./build_safety_layer.sh --pbf norcal.osm.pbf --bbox -122.6,37.2,-121.7,37.9 \
>     --engine rust --out /tmp/safety-rust.pmtiles \
>     --geojson-out /tmp/safety-rust.geojsonseq
> python3 test/diff_geojsonseq.py /tmp/safety-legacy.geojsonseq /tmp/safety-rust.geojsonseq
> python3 test/diff_pmtiles.py    /tmp/safety-legacy.pmtiles    /tmp/safety-rust.pmtiles
> ```
>
> The extraction diff must be clean: properties exactly, point geometry exactly.
> The tile diff is allowed a feature-count delta (see
> [Caveats](#caveats--known-limitations) §3).

### 1. Dry run (single metro — validates safety + border layers)

Prove a valid `.pmtiles` with the new layers without a planet build. Example
San Francisco Bay Area, reusing the upstream base clipped to the bbox:

```bash
# small OSM extract, e.g. from geofabrik norcal, or osmium extract yourself
./build_v5_pmtiles.sh \
    --pbf norcal-latest.osm.pbf \
    --bbox -122.6,37.2,-121.7,37.9 \
    --base-mode reuse \
    --out v5-sf.pmtiles --workdir ./v5_work_sf

# inspect
pmtiles show v5-sf.pmtiles          # lists layers: ... safety admin_country ...
```

### 2. Full planet build

```bash
# Base built from OSM with Planetiler (reproducible):
./build_v5_pmtiles.sh \
    --pbf planet-latest.osm.pbf \
    --base-mode build \
    --base-jar protomaps-basemap-HEAD-with-deps.jar \
    --base-area planet \
    --out v5.pmtiles

# …or reuse the upstream prebuilt base and only refresh overlays (much faster):
./build_v5_pmtiles.sh --pbf planet-latest.osm.pbf --base-mode reuse --out v5.pmtiles
```

Individual layers can also be built standalone — see each script's `--help`.

---

## Expected output & size

| Artifact | Extent | Approx size |
|---|---|---|
| `safety.pmtiles` | planet | ~0.3–1 GB (point features only) |
| `maxspeed.pmtiles` | planet | ~1–3 GB (line features on tagged ways) |
| `transit_lines.pmtiles` | planet | ~0.2–1 GB (rail/transit line features) |
| `ma_pois.pmtiles` | planet | ~0.5–2 GB (named POI points) |
| `poi_names.bin` + `poi_index.bin` | planet | ~0.2–1 GB combined (side files) |
| `admin_country/region/city.pmtiles` | planet | ~50–300 MB combined |
| **`v5.pmtiles`** | planet | ≈ **137 GB** (dominated by the base; overlays add < 3%) |
| `v5-sf.pmtiles` | one metro | tens of MB |

The base dominates; the safety + admin overlays are a rounding error on top.

---

## Publishing to R2

Upload the built `v5.pmtiles` to Cloudflare R2 with
[`publish_r2.sh`](publish_r2.sh). It reads **all** credentials/config from
**environment variables** — nothing secret is stored in the repo.

**Required env** (placeholders — set your real values):

| Var | Meaning |
|---|---|
| `R2_ENDPOINT` | S3 API endpoint, e.g. `https://<ACCOUNT_ID>.r2.cloudflarestorage.com` |
| `R2_ACCESS_KEY_ID` | R2 access key id |
| `R2_SECRET_ACCESS_KEY` | R2 secret access key |
| `R2_BUCKET` | *(optional)* bucket name, default `maps` (served at `data.vayunmathur.com`) |

```bash
# export secrets (do NOT pass them as CLI args — they leak into shell history)
export R2_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com
export R2_ACCESS_KEY_ID=...        # your key id
export R2_SECRET_ACCESS_KEY=...    # your secret
# export R2_BUCKET=maps            # optional; this is the default

# upload (uses rclone if present, else aws s3 cp --endpoint-url "$R2_ENDPOINT")
./scripts/maps/publish_r2.sh v5.pmtiles --key v5.pmtiles

# ...or let the build publish automatically after a successful merge:
./build_v5_pmtiles.sh --pbf planet-latest.osm.pbf --out v5.pmtiles --publish
```

`publish_r2.sh` sets `Content-Type: application/octet-stream` and a long
immutable `Cache-Control`, and fails clearly if any required env var is unset.
It auto-detects the upload tool (`--tool auto|rclone|aws`):

* **rclone** — `rclone copyto … :s3:$R2_BUCKET/<key>` with `--s3-*` flags.
* **aws-cli** — `aws s3 cp … --endpoint-url "$R2_ENDPOINT"` (creds scoped to the
  invocation via `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` from the R2 vars).

Since v5 **bakes the maxspeed layer in**, you normally publish just the single
`v5.pmtiles`. The script also accepts multiple files
(`publish_r2.sh v5.pmtiles aux.pmtiles`) for any siblings built separately.

> **Secret hygiene:** never commit keys, never paste them on the command line
> (shell history), prefer a secret manager / `.env` that is git-ignored, and
> **rotate** R2 keys periodically and immediately if one is ever exposed.

> (The older [`vendor_pmtiles.sh`](vendor_pmtiles.sh) — which streams the 137 GB
> upstream base to R2 — remains for the base-vendoring workflow; `publish_r2.sh`
> is the general publisher for locally built `.pmtiles`.)

Then **P13** (separate task, app code) points the style at the new file:

```jsonc
// maps/src/main/assets/style.json  — protomaps + protomapsOnline sources
"url": "pmtiles://https://data.vayunmathur.com/v5.pmtiles"
```

…adds style layers for `safety`, points `MaxspeedSource.PMTILES_URL` at the same
file (source-layer `maxspeed`), adds the P22 transit-line highlight against
source-layer `transit_lines` (styling by `kind` + `colour`), and switches the
country/state mask in `CountryMap.kt` / `MyMapLayers.kt` from the `.fgb` assets
to the `admin_country` / `admin_region` PMTiles layers (matching on `ISO_A2` /
`iso_3166_2`, which are preserved).

> **Mask caveat for P13:** vector tiles clip polygons at tile boundaries. The
> admin layers are tiled with `--no-tile-size-limit --no-feature-limit
> --coalesce-densest-as-needed` and start at low zoom to keep country/region
> polygons as whole as possible for mask reassembly, but for a pixel-perfect
> full-country mask the app may still prefer querying the layer at a low zoom
> level. Evaluate during P13.

---

## POI layer & side files (P27)

`ma_pois` is the app's **own OSM POI layer**: POI placement, name and type come
from OpenStreetMap (baked here), so the app never scrapes a Google map viewport
to discover POIs — Google is queried only for rich details when the user taps a
POI. It is built by [`build_pois_layer.sh`](build_pois_layer.sh) →
[`osm_ingest`](osm_ingest/)'s `poi_extract` and merged into `v5.pmtiles` by
`build_v5_pmtiles.sh` (skip with `--skip-pois`). The **same** `poi_extract` pass
emits two compact side files **next to `--out`** so the app can `mmap` +
binary-scan POIs without opening the tileset:

```
poi_names.bin
poi_index.bin
```

Both are added to `run_generator.sh`'s R2 upload list so they ship from the same
`data.vayunmathur.com` host as the routing graph.

### `ma_pois` source-layer

| source-layer | geometry | attributes |
|---|---|---|
| `ma_pois` | Point (z12–z16) | **`name`** (string), **`type`** (number, see below), **`osm_id`** (number; negative for relation-sourced POIs) |

A POI = any node / closed-way area / multipolygon-relation area with a `name`
AND a recognised POI key (`amenity`/`shop`/`tourism`/`leisure`/`office`/
`healthcare`). Areas are reduced to a centroid point.

### POI type map

Stable POI type-number enum — **never renumber an existing value, only append**;
`255` is the catch-all "other". Kept in sync with `osm_ingest/src/tags.rs`
(source of truth) and the app. Precedence when several keys are present:
`amenity` → `shop` → `tourism` → `leisure` → `office` → `healthcare`.

| # | type | example OSM tags |
|---|---|---|
| 0 | restaurant | `amenity=restaurant`/`food_court` |
| 1 | cafe | `amenity=cafe`/`ice_cream` |
| 2 | fast_food | `amenity=fast_food` |
| 3 | bar | `amenity=bar`/`pub`/`biergarten`/`nightclub` |
| 4 | shop (generic) | `shop=yes` and other unmapped `shop=*` |
| 5 | grocery | `shop=supermarket`/`convenience`/`greengrocer`/`grocery` |
| 6 | gas_station | `amenity=fuel` |
| 7 | pharmacy | `amenity=pharmacy`, `shop=chemist`, `healthcare=pharmacy` |
| 8 | hotel | `tourism=hotel`/`motel`/`hostel`/`guest_house`/`apartment` |
| 9 | bank | `amenity=bank`/`atm`/`bureau_de_change` |
| 10 | hospital | `amenity=hospital`/`clinic`, `healthcare=hospital`/`clinic` |
| 11 | school | `amenity=school`/`college`/`university`/`kindergarten`/`language_school`/`driving_school` |
| 12 | park | `leisure=park`/`garden`/`nature_reserve` |
| 13 | gym | `leisure=fitness_centre`/`sports_centre` |
| 14 | place_of_worship | `amenity=place_of_worship` |
| 15 | attraction | `tourism=attraction`/`theme_park`/`zoo`/`viewpoint`/`artwork`/`gallery`/`aquarium` |
| 16 | parking | `amenity=parking`/`parking_entrance`/`bicycle_parking` |
| 17 | cinema | `amenity=cinema` |
| 18 | theatre | `amenity=theatre`/`arts_centre` |
| 19 | library | `amenity=library` |
| 20 | post_office | `amenity=post_office` |
| 21 | police | `amenity=police` |
| 22 | fire_station | `amenity=fire_station` |
| 23 | townhall | `amenity=townhall`/`courthouse`, `office=government` |
| 24 | clothing | `shop=clothes`/`shoes`/`boutique`/`fashion`/`tailor` |
| 25 | electronics | `shop=electronics`/`mobile_phone`/`computer`/`hifi` |
| 26 | hardware | `shop=hardware`/`doityourself`/`trade`/`paint` |
| 27 | beauty | `shop=hairdresser`/`beauty` |
| 28 | car | `shop=car`/`car_repair`/`car_parts`/`tyres`, `amenity=car_rental`/`car_wash`/`car_sharing` |
| 29 | bakery | `shop=bakery` |
| 30 | books | `shop=books`/`stationery` |
| 31 | furniture | `shop=furniture`/`interior_decoration`/`houseware` |
| 32 | sports_shop | `shop=sports`/`outdoor`/`bicycle` |
| 33 | department_store | `shop=department_store`/`mall` |
| 34 | dentist | `amenity=dentist`, `healthcare=dentist` |
| 35 | doctor | `amenity=doctors`, `healthcare=doctor`/`centre` |
| 36 | veterinary | `amenity=veterinary`, `healthcare=veterinary` |
| 37 | charging_station | `amenity=charging_station` |
| 38 | museum | `tourism=museum` |
| 39 | office (generic) | other unmapped `office=*` |
| 40 | tourism_info | `tourism=information` |
| 41 | florist | `shop=florist` |
| 42 | jewelry | `shop=jewelry`/`jewellery` |
| 43 | optician | `shop=optician`, `healthcare=optometrist` |
| 44 | laundry | `shop=laundry`/`dry_cleaning` |
| 45 | pet | `shop=pet` |
| 46 | liquor | `shop=alcohol`/`wine`/`beverages` |
| 47 | toys | `shop=toys` |
| 48 | gift | `shop=gift` |
| 49 | marketplace | `amenity=marketplace` |
| 50 | station | `railway=station`/`halt`/`tram_stop`, `public_transport=station`, `amenity=bus_station` (train/tram/metro/bus; has live departures) |
| 255 | other | any recognised POI key with an unmapped value |

### `poi_names.bin` — deduped name table

Each **unique** name is stored **once**, UTF-8, **NUL-terminated**, concatenated
in first-seen order:

```
"Blue Bottle Coffee\0Philz Coffee\0Dolores Park\0…"
```

A **"name start index"** (the `name_off` field in `poi_index.bin`) is the **byte
offset** of the name's first byte. To read a name: seek to `name_off` and read
bytes up to (not including) the next `0x00`. Multiple POIs with an identical name
share the same offset (that is the dedup win). Offsets are `uint32` byte offsets
(the table is well under 4 GB for a planet build).

### `poi_index.bin` — flat fixed-size records

A flat array of fixed **14-byte**, **little-endian**, packed records — one per
POI, in the SAME order as the `ma_pois` features:

| field | type | bytes | meaning |
|---|---|---|---|
| `lat_e7` | `int32` | 0–3 | latitude × 1e7 |
| `lon_e7` | `int32` | 4–7 | longitude × 1e7 |
| `name_off` | `uint32` | 8–11 | byte offset into `poi_names.bin` |
| `type` | `uint16` | 12–13 | POI type number (see the type map) |

`record_count = filesize(poi_index.bin) / 14`. Records are **sorted ascending by
the 64-bit Z-order (Morton) key of (lat, lon)** — computed exactly as
`osm_ingest`'s `latlng_to_spatial` (interleave the 32-bit normalized
`x=(lon+180)/360`, `y=(lat+90)/180`) — giving spatial locality for range scans.
The Morton key itself is **not** stored; the app recomputes it from `lat_e7`/
`lon_e7` when needed. Both files are byte-for-byte reproducible for a given input.

C/Rust view of a record:

```c
#pragma pack(push, 1)
struct PoiRecord {   // 14 bytes, little-endian
    int32_t  lat_e7;
    int32_t  lon_e7;
    uint32_t name_off;   // offset into poi_names.bin
    uint16_t type;
};
#pragma pack(pop)
```

---

## Single global routing graph (P16)

The offline **routing graph** is a **single global graph** — there is no
per-zone splitting and no separate merge/compaction stage. `osm_ingest`'s
`road_graph` emits, in one pass, exactly the on-disk layout the Rust router
(`maps/src/main/rust/src/graph.rs`) mmaps:

| File | Contents | Consumed by (graph.rs) |
|---|---|---|
| `metadata.bin` | `u32 magic` (`"MARG"` = `0x4752414D`), `u32 version` (1), `u64 node count` — 16 B | `load` |
| `nodes.bin` | `NodeMaster[node_count + 1]` (16 B: `i32 lat_e7`, `i32 lon_e7`, `u64 edge_ptr`), trailing sentinel | `node` / `get_node` |
| `edges.bin` | `Edge[edge_count]` (14 B: `u32 target`, `u32 dist_mm`, `u32 name_offset`, `u8 type`, `u8 speed_limit`) | `edge` |
| `lanes.bin` | `u64 offsets[edge_count + 1]` then a `u16` turn-mask blob | `edge_lane_masks` |
| `road_names.bin` | NUL-terminated string pool | `road_name` |

`intermediate.bin` (delta-encoded edge geometry) is **optional** and is not
produced by this generator; `graph.rs` treats its absence gracefully and the
router falls back to straight node-to-node segments. It can be added later
without changing any of the files above. When present it **is** length-validated
against `edge_count`, since its offset array is sized from it.

**Why single global (drop graph zoning):** the previous pipeline wrote per-zone
`*_zone_N.bin` artifacts in a layout `graph.rs` could not load and relied on a
merge/compaction stage that never existed in the repo (the old un-checked-in
`native-lib.cpp` toolchain — "Gap 1" in `TRANSIT_PACK_NOTES.md`). Folding the
final layout directly into the generator closes that gap: freshly generated
road data is now loadable by the shipping router with no extra step.

**Why `metadata.bin` has a magic + version.** The road graph is a directory of
independent files with no cross-file checksum, so a partial or mixed-vintage
update would previously have been read as a valid graph — `node_count` and
`edge_count` would disagree with the actual files and the router would read out of
bounds. The header makes that a clean rejection (`Graph::load` returns `None`).
It was added when the road graph stopped carrying transit at all (see below),
which changed both counts and so required every pack to be regenerated.

> **Update the app and the pack together.** The header protects a *new* reader
> from an old pack, but it cannot protect an *old* reader from a new pack: a build
> predating the header reads `metadata.bin` as a bare `u64 node_count`, so it takes
> the magic and version as the count — `0x00000001_4752414D` = 5,491,540,301
> instead of 35,857,253 — and then indexes `nodes.bin` far out of bounds. That is a
> native `SIGSEGV` in `findRouteNative`, not a clean failure. Pushing a regenerated
> pack to a device therefore also requires installing an app built from the same
> vintage or newer.

**No transit in the road graph.** The generator used to synthesize fake 15-minute
GTFS headways onto rail/bus ways and emit a `TRANSIT_FLAG` edge set, a
`transit_voyages.bin` schedule blob, a `transit_attributes.bin` per-node table,
and a **duplicated node per OSM node** so a road node and a transit node could
coexist. All of that is gone: public transit is planned exclusively by the RAPTOR
planner over the separate `.transit` index (`gtfs_ingest`, real GTFS timetables),
so `TravelMode.PUBLIC_TRANSIT` in the road graph now means *walking* — it is only
reached for a journey's access/egress/transfer legs. Dropping the duplicated
nodes is also the single biggest size win in this pipeline.

The two download entries outlived their producer, though: `MainActivity`'s
`InitialDownloadChecker` kept fetching `transit_voyages.bin` (290 MB) and
`transit_attributes.bin` (122 MB) long after the generator that wrote them was
deleted, and nothing in the app parsed either. They have now been removed, taking
412 MB off a cold start and the download list from 10 entries to 8.

### How the app obtains it — download-size implications

The routing graph and the map **tiles** have very different size profiles, so
they are delivered differently:

* **Routing graph — one global download.** `ZoneDownloadManager.startGraphDownload()`
  fetches the files above from `https://data.vayunmathur.com/<file>` into the
  app's external files dir (the same dir `Graph::load` reads). It is a single
  logical download surfaced once in `DownloadedMapsPage` (not per Morton zone).
  A planet routing graph is on the order of a few GB (nodes/edges/lanes; far
  smaller than the tile basemap), so shipping it whole is practical and removes
  the per-zone bookkeeping.
* **Map tiles — stay zoned.** The vector basemap (`v5.pmtiles`) is ≈137 GB
  (see the size table above), so it can never be one download. Offline tile
  packs remain the 64 Morton-grid `zone_$id.pmtiles` files, downloaded per zone.
* **Transit index — stays per-zone (documented exception).** The P11 offline
  transit index ships as per-zone `zone_$id.transit` files built by
  `gtfs_ingest`; it is self-contained and independent of the road graph. See
  `TRANSIT_PACK_NOTES.md` for why it is not folded into the global graph and
  what a single global transit index would require.

`run_generator.sh` publishes only the single global graph files listed above to
R2 (no `*_zone_*.bin`).

---

## Dry-run result (this checkout)

`tippecanoe`/`osmium`/`ogr2ogr` are not installed on the dev box, so the
OSM-derived tile builds were not executed here. The **schema-mapping core** (the
part that defines the new `safety` + admin layers) was dry-run via the unit test
and passes:

> **Since updated.** The `transit_stops` layer and the final merge no longer go
> through tippecanoe at all — they run entirely on `cargo` via
> [`tile_build`](tile_build/), and both *were* exercised on this dev box. That
> crate's PMTiles reader is additionally pinned against the real published
> `v5-ca.pmtiles`: its header parses to the known values and its gzipped root
> directory re-serializes byte-exactly. See `tile_build/tests/fixtures/README.md`.

```
$ python3 scripts/maps/test/test_normalize.py
...
52 passed, 0 failed
```

This validates: all five safety `kind` classifications (incl. DeFlock ALPR
detection), maxspeed value passthrough (mph/km/h/bare + `maxspeed:forward`
fallback + non-numeric `none`/`walk`/`signals` raw passthrough, lines only),
the `transit_lines` `kind` mapping (railway ways + route relations,
`narrow_gauge`→`rail`, bus excluded) with route `colour`/`ref` recovered from
GDAL's `other_tags` HSTORE (both `colour`/`color` spellings), non-safety/non-line
features dropped, `admin_level` mapping (2/4/8), `ISO_A2` / `iso_3166_2`
preservation, county-vs-city `admin_level` filtering, and that every emitted line
is valid GeoJSON (tippecanoe input). Run the metro dry run above on a box with
the toolchain installed for a full end-to-end check.

### P27 `ma_pois` dry run (toolchain box)

The POI layer + side files were validated end-to-end on a box with the full
toolchain (libosmium 2.20, osmium, tippecanoe, pmtiles) against the California
extract clipped to a small SF bbox (`-122.44,37.76,-122.40,37.80`):

```
$ ./build_pois_layer.sh --pbf california-latest.osm.pbf \
      --bbox -122.44,37.76,-122.40,37.80 \
      --out ma_pois.pmtiles --names-out poi_names.bin --index-out poi_index.bin
[poi] extracted 5918 POI(s)
[poi] wrote 5392 unique name(s), 5918 record(s)
[poi]   poi_names.bin (96408 bytes)
[poi]   poi_index.bin (82852 bytes)   # 5918 × 14
[ma_pois] tiling -> ma_pois.pmtiles (z12-16)
```

Verified: `ma_pois` appears in the tileset at
**z12–16** (and coexists with other layers after `tile-join` — a merge with
`safety.pmtiles` lists both `ma_pois` and `safety`); each tiled feature carries
`name`/`type`/`osm_id`; `poi_index.bin` records decode back to the exact
`ma_pois` features (Morton-sorted); and POIs sharing a name resolve to the same
`poi_names.bin` offset (212 shared offsets in the sample), confirming dedup.

### Rust ingest port (replacing the libosmium C++)

`generator.cpp` and `poi_extract.cpp` were replaced by
[`osm_ingest`](osm_ingest/), which reads `.osm.pbf` natively (hand-rolled
protobuf decoder + pure-Rust inflate) and therefore needs no g++, no libosmium
and no WSL. Validated on the full 1.24 GB `california-latest.osm.pbf`, natively
on Windows:

```
road_graph:   23007 blobs, 4,264,993 routable ways, 35,857,253 nodes,
              74,340,356 edges, 323,441 unique names, LCC 35,358,417,
              60,711 isolated stops reconnected                      ~19 s
poi_extract:  284,216 POIs (172,448 node, 107,612 closed-way, 4,156 relation),
              188,990 unique names                                    ~5 s
```

Verified: `metadata.bin` is the 16-byte `MARG`/v1/`node_count` header;
`nodes.bin` is `node_count + 1` records with a sentinel whose `edge_ptr` equals
`edge_count`; `edges.bin` is an exact multiple of 14; `lanes.bin` is
`(edge_count + 1) × 8` offset bytes plus the `u16` blob; nodes and POI records
are Morton-ordered. **Both tools are deterministic** — two runs on California
produce byte-identical output for all eight files, which the C++ never did (it
filled its node array from concurrent workers and interned names in
thread-scheduling order).

Also cross-checked against the C++ itself, built in WSL against libosmium 2.20,
over a San Luis Obispo extract: identical `node_count` (88,666), `edge_count`
(181,481), `lanes.bin` length, `road_names.bin` string set and out-degree
sequence; identical edge `(type, speed, name)` groups with every `dist_mm` within
12 mm; and every POI the C++ found present with the same `name`/`type`. The two
intentional differences — coordinates 1.1 cm more accurate (the C++ truncated
`lat_e7`) and relation centroids within ~3 m instead of assembled rings — are
detailed in [`osm_ingest/README.md`](osm_ingest/README.md).
