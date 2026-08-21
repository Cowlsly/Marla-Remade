# Test fixtures

## `v5ca_z11_tile.mvt`

One real tile lifted out of the published basemap,
`https://data.vayunmathur.com/v5-ca.pmtiles` (PMTiles v3, tippecanoe-produced),
via a ranged GET. Stored **inflated** -- the archive gzips its tiles, but that is
the container's business, so the MVT tests work on the raw protobuf.

PMTiles ``tile_id`` 2229854, which is **z11/339/770** -- north-eastern California
(NW corner lon -120.4102, lat 40.7140). The zoom/x/y were derived with the crate's
own ``tile_zxy``, which is exhaustively round-trip tested; an earlier hand
calculation put this tile in the Arctic and was wrong.

Contents, as decoded from the published file at the time it was captured:

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

## ``v5ca_z11_tile.mvt.gz``

The same tile, exactly as the archive stores it: gzipped. Proves the gzip reader
handles a real producer's stream and inflates to ``v5ca_z11_tile.mvt`` byte for
byte.

## ``v5ca_header_rootdir.bin``

Bytes ``0..1479`` of the same archive -- the 127-byte v3 header plus its entire
gzipped root directory (which ends exactly where ``metadata_offset`` begins).

Enough to test header parsing and directory decoding against real data without
carrying the 1.5 GB file. Note the offsets inside it address that full file, so it
cannot be opened as a standalone archive; the tests parse the header and root
directory specifically.

Known values: 324 root entries, all leaf pointers, inflating to 2004 bytes. Our
serializer reproduces those 2004 bytes exactly, which is what proves the writer
agrees with tippecanoe on the directory encoding -- including its use of the
contiguous-offset shorthand.
