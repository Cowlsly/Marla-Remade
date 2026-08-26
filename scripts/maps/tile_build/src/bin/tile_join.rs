//! `tile_join` — merge several PMTiles archives into one, unioning each tile's
//! layers. The `tile-join` replacement.
//!
//! Usage:
//!   tile_join --out OUT.pmtiles IN1.pmtiles IN2.pmtiles [...] [--threads N]
//!
//! Later inputs win a layer-name collision, so a freshly built layer replaces a
//! stale copy of itself.
//!
//! Inputs are opened rather than read: only each one's root directory and metadata are
//! held, so what can be joined is bounded by the tile COUNT and not by the input bytes.
//! An overlay-only join of the whole planet fits; a planet BASE (~127 GB of tile data
//! across 300 M tiles) still does not, which is why the base is not joined at all.
//! Build overlay-only archives with `build_v5_pmtiles.sh --no-base` and let the app
//! mount them alongside the published base.

use std::process::ExitCode;
use tile_build::par;
use tile_build::pmtiles::ArchiveFile;
use tile_build::tiling::merge_archives_to;

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
            "--threads" => {
                let Some(raw) = args.get(i + 1) else {
                    eprintln!("tile_join: --threads needs a value");
                    return ExitCode::from(2);
                };
                match par::parse_threads(raw) {
                    Ok(n) => par::set_threads(n),
                    Err(e) => {
                        eprintln!("tile_join: {e}");
                        return ExitCode::from(2);
                    }
                }
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

    let mut archives = Vec::with_capacity(inputs.len());
    for path in &inputs {
        match ArchiveFile::open(path) {
            Ok(a) => {
                eprintln!(
                    "tile_join: opened {path}: z{}-{}, {} addressed tile(s)",
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

    // Scratch beside the output, because the last step copies the data section across
    // and a cross-filesystem copy would be needlessly slow.
    let scratch = format!("{out}.tiledata");
    if let Err(e) = merge_archives_to(&mut archives, &out, &scratch, true) {
        eprintln!("tile_join: {e}");
        let _ = std::fs::remove_file(&scratch);
        return ExitCode::FAILURE;
    }
    // Header only. Re-reading the whole finished archive to print its zoom range was
    // its own ceiling: 8 GB of reads and an 8 GB allocation, after the merge had just
    // gone to the trouble of never holding it.
    let size = std::fs::metadata(&out).map(|m| m.len()).unwrap_or(0);
    match ArchiveFile::read_header(&out) {
        Ok(h) => eprintln!(
            "tile_join: wrote {out} ({:.1} MiB): z{}-{}, {} addressed tile(s), {} distinct body(ies)",
            size as f64 / 1_048_576.0,
            h.min_zoom,
            h.max_zoom,
            h.addressed_tiles,
            h.tile_contents,
        ),
        Err(e) => {
            // Writing something we cannot read back is a bug, not a warning.
            eprintln!("tile_join: wrote {out} but its header does not parse: {e}");
            return ExitCode::FAILURE;
        }
    }
    ExitCode::SUCCESS
}

fn usage() {
    eprintln!("usage: tile_join --out OUT.pmtiles IN1.pmtiles IN2.pmtiles [...] [--threads N]");
}
