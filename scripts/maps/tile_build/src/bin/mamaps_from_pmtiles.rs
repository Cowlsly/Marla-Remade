//! `mamaps_from_pmtiles` — convert a PMTiles/MVT archive to `.mamaps`.
//!
//! What "first pixels" is built on. Reproducing the upstream tag→kind schema from `.osm.pbf` is the
//! long pole of this project, and it would be a bad thing to be halfway through when the container
//! and the renderer are still unproven. So: take an archive the tiler already produced and the app
//! already drew, re-encode it, and point the renderer at that. Everything that then looks wrong is
//! a container or renderer bug, because the data did not change.
//!
//! It stays useful afterwards as the differential baseline: the same input through two encoders,
//! which is what makes a per-kind comparison against upstream meaningful.
//!
//! Usage:
//!   mamaps_from_pmtiles IN.pmtiles OUT.mamaps [--build-id N] [--rings-validated]
//!
//! Reads tiles in ascending tile-id order, which is what the writer requires and what a clustered
//! PMTiles archive already stores. Dedup is the writer's: a run of identical ocean tiles collapses
//! to one body whether or not the input had already collapsed it.

use std::process::ExitCode;

use tile_build::mamaps::from_mvt::{self, Stats};
use tile_build::mamaps::write::{Options, StreamWriter};
use tile_build::mvt::Tile;
use tile_build::pmtiles::{self, ArchiveFile};
use tile_build::gz;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut positional: Vec<String> = Vec::new();
    let mut build_id = 0u64;
    let mut rings_validated = false;

    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--build-id" => match args.get(i + 1).and_then(|v| v.parse::<u64>().ok()) {
                Some(v) => {
                    build_id = v;
                    i += 2;
                }
                None => {
                    eprintln!("mamaps_from_pmtiles: --build-id needs a number");
                    return ExitCode::from(2);
                }
            },
            "--rings-validated" => {
                rings_validated = true;
                i += 1;
            }
            "-h" | "--help" => {
                usage();
                return ExitCode::SUCCESS;
            }
            other if other.starts_with('-') => {
                eprintln!("mamaps_from_pmtiles: unexpected argument '{other}'");
                usage();
                return ExitCode::from(2);
            }
            other => {
                positional.push(other.to_string());
                i += 1;
            }
        }
    }
    let [input, output] = positional.as_slice() else {
        usage();
        return ExitCode::from(2);
    };

    let mut archive = match ArchiveFile::open(input) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("mamaps_from_pmtiles: {input} is not a readable PMTiles archive: {e}");
            return ExitCode::FAILURE;
        }
    };
    let header = archive.header.clone();

    // Runs expanded, because the writer re-derives them: an input run and an output run need not
    // agree, and expanding here means the two dedup policies cannot interact.
    let mut rows: Vec<(u64, u64, u32)> = Vec::new();
    if let Err(e) = archive.visit_entries(&mut |e| {
        for k in 0..e.run_length as u64 {
            rows.push((e.tile_id + k, e.offset, e.length));
        }
        Ok(())
    }) {
        eprintln!("mamaps_from_pmtiles: cannot walk the tile directory: {e}");
        return ExitCode::FAILURE;
    }
    // The writer needs ascending ids. A clustered archive already is, but an unclustered one is
    // legal PMTiles and would otherwise fail deep into the run.
    rows.sort_by_key(|(id, _, _)| *id);

    let options = Options {
        min_zoom: header.min_zoom,
        max_zoom: header.max_zoom,
        build_id,
        compress: true,
        rings_validated,
        min_lon_e7: header.min_lon_e7,
        min_lat_e7: header.min_lat_e7,
        max_lon_e7: header.max_lon_e7,
        max_lat_e7: header.max_lat_e7,
        ..Options::default()
    };
    let mut writer = match StreamWriter::new(options) {
        Ok(w) => w,
        Err(e) => {
            eprintln!("mamaps_from_pmtiles: {e}");
            return ExitCode::FAILURE;
        }
    };

    let mut totals = Stats::default();
    let mut skipped = 0usize;
    let mut raw = Vec::new();
    for (id, offset, length) in &rows {
        let (z, x, y) = pmtiles::tile_zxy(*id);
        if let Err(e) = archive.body_into(*offset, *length, &mut raw) {
            eprintln!("mamaps_from_pmtiles: z{z}/{x}/{y}: {e}");
            return ExitCode::FAILURE;
        }
        // PMTiles gzips its tile bodies, so `body_into` hands back the stored bytes rather than
        // the MVT. Only the declared compression is honoured; anything else is left alone, which
        // is what a `COMPRESSION_NONE` archive wants.
        let inflated;
        let decompressed: &[u8] = if header.tile_compression == pmtiles::COMPRESSION_GZIP {
            match gz::decompress(&raw) {
                Ok(b) => {
                    inflated = b;
                    &inflated
                }
                Err(e) => {
                    eprintln!("mamaps_from_pmtiles: z{z}/{x}/{y} did not inflate: {e}");
                    skipped += 1;
                    continue;
                }
            }
        } else {
            &raw
        };
        let tile = match Tile::decode(decompressed) {
            Ok(t) => t,
            Err(e) => {
                eprintln!("mamaps_from_pmtiles: z{z}/{x}/{y} did not decode: {e}");
                skipped += 1;
                continue;
            }
        };
        let (body, stats) = match from_mvt::from_tile(&tile) {
            Ok(pair) => pair,
            Err(e) => {
                eprintln!("mamaps_from_pmtiles: z{z}/{x}/{y} did not convert: {e}");
                skipped += 1;
                continue;
            }
        };
        totals.features += stats.features;
        totals.unknown_kinds += stats.unknown_kinds;
        totals.points_dropped += stats.points_dropped;
        totals.layers_skipped += stats.layers_skipped;
        totals.coords_clamped += stats.coords_clamped;
        if let Err(e) = writer.append(*id, &body) {
            eprintln!("mamaps_from_pmtiles: z{z}/{x}/{y}: {e}");
            return ExitCode::FAILURE;
        }
    }

    let bytes = match writer.finish() {
        Ok(b) => b,
        Err(e) => {
            eprintln!("mamaps_from_pmtiles: {e}");
            return ExitCode::FAILURE;
        }
    };
    if let Err(e) = std::fs::write(output, &bytes) {
        eprintln!("mamaps_from_pmtiles: cannot write {output}: {e}");
        return ExitCode::FAILURE;
    }

    println!("in  {input}\t{} tiles", rows.len());
    println!("out {output}\t{} bytes", bytes.len());
    println!(
        "features {}\tunknown_kinds {}\tpoints_dropped {}\tcoords_clamped {}",
        totals.features, totals.unknown_kinds, totals.points_dropped, totals.coords_clamped,
    );
    // A clamped coordinate means geometry moved, which is the one statistic here that is a fault
    // rather than a design choice.
    if totals.coords_clamped > 0 {
        eprintln!(
            "mamaps_from_pmtiles: {} coordinate(s) were clamped to fit an i16 -- geometry moved",
            totals.coords_clamped,
        );
    }
    if skipped > 0 {
        eprintln!("mamaps_from_pmtiles: {skipped} tile(s) were skipped");
    }
    ExitCode::SUCCESS
}

fn usage() {
    eprintln!(
        "usage: mamaps_from_pmtiles IN.pmtiles OUT.mamaps [--build-id N] [--rings-validated]"
    );
}
