//! `tile_lines` — tile a geojsonseq line file into a single-layer PMTiles archive.
//! The `tippecanoe` replacement for line layers (`maxspeed`, `transit_lines`).
//!
//! Usage:
//!   tile_lines --geojson IN.geojsonseq --out OUT.pmtiles --layer NAME
//!              [--minzoom N] [--maxzoom N] [--simplification F]
//!              [--max-tile-bytes N] [--extent N]
//!
//! Input is one GeoJSON `Feature` per line with `LineString` or `MultiLineString`
//! geometry; anything else is counted and skipped. See `osm_ingest::extract` for
//! the producer and `tile_build::pyramid` for the drop policy, which is
//! deliberately *not* tippecanoe's `--drop-densest-as-needed`.

use std::process::ExitCode;
use tile_build::pyramid::{cli_main, Accept};

fn main() -> ExitCode {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    cli_main("tile_lines", Accept::Lines, &argv)
}
