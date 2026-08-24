//! `tile_join` — merge several PMTiles archives into one, unioning each tile's
//! layers. The `tile-join` replacement.
//!
//! Usage:
//!   tile_join --out OUT.pmtiles IN1.pmtiles IN2.pmtiles [...]
//!
//! Later inputs win a layer-name collision, so a freshly built layer replaces a
//! stale copy of itself.
//!
//! Inputs are read wholly into memory, which bounds what can be joined at the size
//! of the largest input. That is ample for the overlays (a few GB combined at planet
//! scale) and hopeless for a planet base (~127 GB) - which is why the base is not
//! joined at all. Build overlay-only archives with `build_v5_pmtiles.sh --no-base`
//! and let the app mount them alongside the published base.

use std::process::ExitCode;
use tile_build::pmtiles::Archive;
use tile_build::tiling::merge_archives_with;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut out = None;
    let mut inputs: Vec<String> = Vec::new();

    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--out" => {
                out = args.get(i + 1).cloned();
                i += 2;
            }
            "-h" | "--help" => {
                usage();
                return ExitCode::SUCCESS;
            }
            other => {
                inputs.push(other.to_string());
                i += 1;
            }
        }
    }

    let Some(out) = out else {
        eprintln!("tile_join: --out is required");
        usage();
        return ExitCode::from(2);
    };
    if inputs.is_empty() {
        eprintln!("tile_join: at least one input archive is required");
        usage();
        return ExitCode::from(2);
    }

    let mut blobs = Vec::with_capacity(inputs.len());
    for path in &inputs {
        match std::fs::read(path) {
            Ok(b) => {
                eprintln!("tile_join: read {path} ({:.1} MiB)", b.len() as f64 / 1_048_576.0);
                blobs.push(b);
            }
            Err(e) => {
                eprintln!("tile_join: cannot read {path}: {e}");
                return ExitCode::FAILURE;
            }
        }
    }

    let mut archives = Vec::with_capacity(blobs.len());
    for (path, blob) in inputs.iter().zip(blobs.iter()) {
        match Archive::parse(blob) {
            Ok(a) => {
                eprintln!(
                    "tile_join:   {path}: z{}-{}, {} addressed tile(s)",
                    a.header.min_zoom, a.header.max_zoom, a.header.addressed_tiles
                );
                archives.push(a);
            }
            Err(e) => {
                eprintln!("tile_join: {path}: {e}");
                return ExitCode::FAILURE;
            }
        }
    }

    let refs: Vec<&Archive> = archives.iter().collect();
    let merged = match merge_archives_with(&refs, true) {
        Ok(m) => m,
        Err(e) => {
            eprintln!("tile_join: {e}");
            return ExitCode::FAILURE;
        }
    };
    if let Err(e) = std::fs::write(&out, &merged) {
        eprintln!("tile_join: cannot write {out}: {e}");
        return ExitCode::FAILURE;
    }
    match Archive::parse(&merged) {
        Ok(a) => eprintln!(
            "tile_join: wrote {out} ({:.1} MiB): z{}-{}, {} addressed tile(s), {} distinct body(ies)",
            merged.len() as f64 / 1_048_576.0,
            a.header.min_zoom,
            a.header.max_zoom,
            a.header.addressed_tiles,
            a.header.tile_contents,
        ),
        Err(e) => {
            // Writing something we cannot read back is a bug, not a warning.
            eprintln!("tile_join: wrote {out} but it does not parse: {e}");
            return ExitCode::FAILURE;
        }
    }
    ExitCode::SUCCESS
}

fn usage() {
    eprintln!("usage: tile_join --out OUT.pmtiles IN1.pmtiles IN2.pmtiles [...]");
}
