# Test fixtures

## `v5ca_z11_tile.mvt`

One real tile lifted out of the published basemap,
`https://data.vayunmathur.com/v5-ca.pmtiles` (PMTiles v3, tippecanoe-produced),
via a ranged GET. Stored **inflated** -- the archive gzips its tiles, but that is
the container's business, so the MVT tests work on the raw protobuf.

PMTiles `tile_id` 2229854, which is zoom 11. Contents, as decoded from the
published file at the time it was captured:

| layer  | features | keys | values | extent | version |
|--------|----------|------|--------|--------|---------|
| earth  | 1        | 1    | 1      | 4096   | 2       |
| roads  | 1        | 9    | 7      | 4096   | 2       |
| water  | 2        | 3    | 3      | 4096   | 2       |

It is deliberately a *small* tile that still carries both polygon (`earth`,
`water`) and line (`roads`) geometry, so it exercises the pass-through path
the tile-join step depends on: our own writer only ever emits points, but the
composite has to carry the base tileset's lines and polygons through untouched.

Its purpose is to pin the decoder against genuine tippecanoe output. A synthetic
round-trip only proves we agree with ourselves.
