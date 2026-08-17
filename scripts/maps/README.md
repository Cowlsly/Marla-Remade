# scripts/maps — PMTiles generator (`v5.pmtiles`)

Builds the custom **`v5.pmtiles`** consumed by the `maps` app. This single file
**replaces two things** the app ships/streams today:

| Old | New |
|-----|-----|
| `https://data.vayunmathur.com/v4.pmtiles` (base tiles) | `v5.pmtiles` base layers (identical schema) |
| `maps/src/main/assets/admin0.fgb` + `admin1.fgb` (border masks) | `v5.pmtiles` `admin_country` / `admin_region` / `admin_city` layers |

On top of the base it also **bakes** (a) safety / road-furniture data (speed
cameras, ALPR/surveillance, stop signs, traffic signals) so the app never needs
a runtime Overpass query, and (b) posted **speed limits** (`maxspeed`) for the
P5b speed-limit feature — all from the same file.

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
    `zone_$i.pmtiles` offline packs.
  * `generator.cpp` + `run_generator.sh` build the **routing graph** binaries
    (`nodes_zone_*.bin`, `edges_zone_*.bin`, `transit_voyages_zone_*.bin`, …)
    with libosmium. **This is the offline-region-pack / routing-graph generator
    that P11 edits for GTFS** — it already has transit/GTFS scaffolding.

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
| `build_v5_pmtiles.sh` | **Top-level orchestrator**: base + safety + admin → merge → `v5.pmtiles` |
| `build_base_layers.sh` | Base tiles (Planetiler build **or** reuse upstream `v4.pmtiles`) |
| `build_safety_layer.sh` | osmium → GeoJSON → `normalize_safety.py` → tippecanoe → `safety.pmtiles` |
| `build_maxspeed_layer.sh` | osmium → GeoJSON → `normalize_maxspeed.py` → tippecanoe → `maxspeed.pmtiles` |
| `build_admin_layers.sh` | Natural Earth / OSM → `normalize_admin.py` → tippecanoe → `admin_*.pmtiles` |
| `publish_r2.sh` | Upload built `.pmtiles` to Cloudflare R2 (creds from env vars only) |
| `normalize_safety.py` | OSM tags → `safety` layer schema (pure stdlib, unit-tested) |
| `normalize_maxspeed.py` | OSM maxspeed ways → `maxspeed` layer schema (pure stdlib, unit-tested) |
| `normalize_admin.py` | NE/OSM attrs → admin layer schema (pure stdlib, unit-tested) |
| `test/test_normalize.py` | Dry-run unit test of the schema mapping (no external tools) |
| `test/fixtures/*` | Tiny sample inputs for the test |

---

## Prerequisites (tools)

Full build needs (all are existing off-the-shelf tools — no C++ authored here):

* **tippecanoe** ≥ 2.x (provides `tippecanoe` + `tile-join`, both write `.pmtiles`)
* **osmium-tool** (`osmium`)
* **GDAL** (`ogr2ogr`)
* **python3** (stdlib only)
* Base build mode only: **Java 21** + the Protomaps basemap jar
  (build once from `github.com/protomaps/basemaps`, `tiles/` → `mvn package`)
* Base reuse mode only: `curl`, and `go-pmtiles` (`pmtiles`) if using `--bbox`

Install (Fedora/Amazon Linux, matching `run_generator.sh` conventions):

```bash
sudo dnf install -y osmium-tool gdal python3 curl unzip
# tippecanoe: build from github.com/felt/tippecanoe (make && make install)
```

The **dry-run test** needs only `python3`.

---

## Run commands

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
file (source-layer `maxspeed`), and switches the country/state mask in
`CountryMap.kt` / `MyMapLayers.kt` from the `.fgb` assets to the
`admin_country` / `admin_region` PMTiles layers (matching on `ISO_A2` /
`iso_3166_2`, which are preserved).

> **Mask caveat for P13:** vector tiles clip polygons at tile boundaries. The
> admin layers are tiled with `--no-tile-size-limit --no-feature-limit
> --coalesce-densest-as-needed` and start at low zoom to keep country/region
> polygons as whole as possible for mask reassembly, but for a pixel-perfect
> full-country mask the app may still prefer querying the layer at a low zoom
> level. Evaluate during P13.

---

## Dry-run result (this checkout)

`tippecanoe`/`osmium`/`ogr2ogr` are not installed on the dev box, so the
tile-level build was not executed here. The **schema-mapping core** (the part
that defines the new `safety` + admin layers) was dry-run via the unit test and
passes:

```
$ python3 scripts/maps/test/test_normalize.py
...
37 passed, 0 failed
```

This validates: all five safety `kind` classifications (incl. DeFlock ALPR
detection), maxspeed value passthrough (mph/km/h/bare + `maxspeed:forward`
fallback + non-numeric `none`/`walk`/`signals` raw passthrough, lines only),
non-safety/non-point features dropped, `admin_level`
mapping (2/4/8), `ISO_A2` / `iso_3166_2` preservation, county-vs-city
`admin_level` filtering, and that every emitted line is valid GeoJSON
(tippecanoe input). Run the metro dry run above on a box with the toolchain
installed for a full end-to-end check.
