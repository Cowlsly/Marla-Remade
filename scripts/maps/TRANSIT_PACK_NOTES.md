# Offline transit packaging notes (P11c)

How the offline transit index (`<feed>.transit`, built by `gtfs_ingest`, P11a)
is delivered to the app, and the two pre-existing pipeline gaps P11 rides on.

## Delivery: transit index as a second pack "part"

The downloadable region packs already use a multi-part naming convention in the
`DownloadManager` titles: `"Map Zone $id ($part)"`, where the only part today is
`Map` (the `zone_$id.pmtiles` tiles). P11c adds a second, **optional** part:

- **URL:** `https://data.vayunmathur.com/zone_$id.transit`
- **On device:** `getExternalFilesDir(null)/zone_$id.transit`
- **Title:** `"Map Zone $id (Transit)"`

`ZoneDownloadManager.startDownload` now enqueues this alongside the pmtiles part;
`deleteZone` removes it too. It is best-effort: zones with no transit coverage
simply 404 and are ignored — `getZoneStatus` still keys `FINISHED` off the
pmtiles part alone, so map downloads never block on transit.

On device, `OfflineRouter` (P11d) discovers every `*.transit` file in the base
dir and asks the Rust planner (`transit.rs`) to load each and check whether its
bounding box covers the route endpoints, so the on-disk file name does not have
to encode the feed id — the feed id lives *inside* the index (used for
`present_feeds` and route colors).

## Publishing

`publish_r2.sh` already uploads arbitrary files, so no script change is needed:

```sh
# build the index for a metro, then upload it under the covering zone's key
gtfs_ingest /tmp/sf_bay sf_bay ./out
cp ./out/sf_bay.transit zone_7.transit        # see "feed -> zone" below
./publish_r2.sh zone_7.transit
```

## Gap (1): road-graph producer/consumer format mismatch — CLOSED by P16

*(Historical, kept for context.)* `scripts/maps/generator.cpp` used to emit
**per-zone** road-graph binaries (`nodes_zone_*.bin`, 28-byte `NodeMaster`,
8-byte voyages, etc.), while the shipping Rust router (`graph.rs`) mmaps a
**single merged** `nodes.bin` (16-byte `NodeMaster`, separate
`transit_attributes.bin`, 4-byte `TransitVoyageCompact`). No merge/compaction
stage existed in the repo (it was part of an un-checked-in `native-lib.cpp`
toolchain), so freshly generated road data could not be loaded.

**P16 fixes this.** `generator.cpp` now emits the **single global** graph in the
exact layout `graph.rs` loads (`nodes.bin`/`edges.bin`/`transit_voyages.bin`/
`transit_attributes.bin`/`lanes.bin`/`metadata.bin`) in one pass — no per-zone
artifacts, no separate merge stage. See
[`README.md` → Single global routing graph](README.md#single-global-routing-graph-p16)
for the on-disk contract and the app download path
(`ZoneDownloadManager.startGraphDownload`). This did **not** touch offline
transit: the transit index is still fully self-contained and independent of the
road graph (access/egress/transfer legs use a straight-line walk heuristic in
`transit.rs`, not the road graph).

## Gap (2): zone (Morton) vs feed (metro) boundaries

GTFS feeds are per-agency/metro; the 64-cell pack grid is geographic. A feed can
straddle several zones and a zone can hold several feeds. The delivery above
sidesteps this on device (bbox-based discovery), but the **packaging step** that
decides which `<feed>.transit` files ship under which `zone_$id.transit` key
needs a `feed -> covered zones` mapping. The `gtfs_ingest` manifest emits each
feed's `bbox_e7`; intersect that bbox with the Morton zone grid to pick the
covering zone key(s). For a metro that fits inside one zone this is a rename;
for a feed spanning zones, publish the same index under each covered zone key
(or, better, publish once under the feed id and add a small per-zone
`feeds.json` the app fetches — a future refinement).
