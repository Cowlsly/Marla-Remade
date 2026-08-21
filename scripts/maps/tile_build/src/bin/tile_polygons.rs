//! `tile_polygons` — tile a geojsonseq polygon file into a single-layer PMTiles
//! archive. The `tippecanoe` replacement for area layers (`admin_*`).
//!
//! Usage:
//!   tile_polygons --geojson IN.geojsonseq --out OUT.pmtiles --layer NAME
//!                 [--minzoom N] [--maxzoom N] [--simplification F]
//!                 [--max-tile-bytes N] [--extent N]
//!
//! Input is one GeoJSON `Feature` per line with `Polygon` or `MultiPolygon`
//! geometry; anything else is counted and skipped. Ring winding order is derived
//! from the signed area rather than trusted, so an input wound either way encodes
//! correctly.
//!
//! Two things tippecanoe does for admin layers that this does not:
//! `--detect-shared-borders` (so a shared border can show a hairline seam at low
//! zoom) and `--extend-zooms-if-still-dropping`. Both are documented in
//! `tile_build::pyramid`.

use std::process::ExitCode;
use tile_build::pyramid::{cli_main, Accept};

fn main() -> ExitCode {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    cli_main("tile_polygons", Accept::Polygons, &argv)
}
