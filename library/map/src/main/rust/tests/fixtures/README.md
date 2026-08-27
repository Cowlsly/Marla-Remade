# Test fixtures

A copy of `tilecodec`'s fixture, so the geometry builder is tested against a real
published tile rather than anything we generated.

See `library/tilecodec/src/main/rust/tests/fixtures/README.md` for its provenance: it is
one tile lifted out of `https://data.vayunmathur.com/v5-ca.pmtiles` via a ranged GET,
PMTiles `tile_id` 2229854 = z11/339/770, carrying one `earth` polygon, one `roads`
LineString of `kind = major_road`, and two `water` polygons.
