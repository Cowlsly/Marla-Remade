//! `pmtiles_extract` — subset a PMTiles archive by bbox, zoom range and layer.
//! The `go-pmtiles extract` replacement, plus the layer lift the admin reuse path
//! needs.
//!
//! Usage:
//!   pmtiles_extract IN.pmtiles --out OUT.pmtiles
//!                   [--bbox minlon,minlat,maxlon,maxlat]
//!                   [--minzoom N] [--maxzoom N] [--layer NAME]...
//!
//! Two jobs, because they are the same walk over the same directory:
//!
//! * **Geographic and zoom subsetting** replaces `pmtiles extract --bbox`, which is
//!   what `build_base_layers.sh --mode reuse --bbox` shells out to today. That was
//!   the last non-cargo tool in the reuse path.
//! * **Layer selection** (`--layer`, repeatable) lifts named layers out of an
//!   archive so they can be carried forward into a `tile_join`. That is how
//!   `admin_country` and `admin_region` survive a rebuild: they come from Natural
//!   Earth shapefiles, not OSM, so the published archive is the source of record
//!   for them and re-deriving them is not an option.
//!
//! ## What is and is not lossless
//!
//! With no `--layer`, tile bodies are copied **byte for byte**: the archive is
//! re-indexed, never re-encoded. With `--layer`, each surviving tile is decoded and
//! re-encoded, which rebuilds its key/value dictionaries in first-use order -- so
//! the bytes differ while the decoded model does not. That is the same contract
//! `tile_join` has, and for the same reason.
//!
//! ## Memory
//!
//! The input is opened, not read: only its root directory, its metadata, one tile body
//! and a 12-byte row per tile are held, so subsetting the 1.5 GB published basemap no
//! longer needs the RAM to hold it. The OUTPUT still goes through [`Builder`], which
//! does hold every kept tile — so extracting most of a planet archive is still out of
//! reach, and that is now the only ceiling.

use std::collections::BTreeSet;
use std::process::ExitCode;
use tile_build::mvt::Tile;
use tile_build::pmtiles::{self, Archive, ArchiveFile, Builder};

fn main() -> ExitCode {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let mut input: Option<String> = None;
    let mut out: Option<String> = None;
    let mut bbox: Option<[f64; 4]> = None;
    let mut min_zoom: Option<u8> = None;
    let mut max_zoom: Option<u8> = None;
    let mut layers: BTreeSet<String> = BTreeSet::new();

    let mut i = 0;
    while i < argv.len() {
        let value = || argv.get(i + 1).cloned();
        match argv[i].as_str() {
            "--out" => {
                out = value();
                i += 2;
            }
            "--layer" => {
                match value() {
                    Some(l) => {
                        layers.insert(l);
                    }
                    None => {
                        eprintln!("pmtiles_extract: --layer needs a value");
                        return ExitCode::from(2);
                    }
                }
                i += 2;
            }
            "--bbox" => {
                let Some(raw) = value() else {
                    eprintln!("pmtiles_extract: --bbox needs a value");
                    return ExitCode::from(2);
                };
                match parse_bbox(&raw) {
                    Ok(b) => bbox = Some(b),
                    Err(e) => {
                        eprintln!("pmtiles_extract: {e}");
                        return ExitCode::from(2);
                    }
                }
                i += 2;
            }
            flag @ ("--minzoom" | "--maxzoom") => {
                let Some(raw) = value() else {
                    eprintln!("pmtiles_extract: {flag} needs a value");
                    return ExitCode::from(2);
                };
                let Ok(z) = raw.parse::<u8>() else {
                    eprintln!("pmtiles_extract: {flag} wants a zoom, got '{raw}'");
                    return ExitCode::from(2);
                };
                if flag == "--minzoom" {
                    min_zoom = Some(z);
                } else {
                    max_zoom = Some(z);
                }
                i += 2;
            }
            "-h" | "--help" => {
                usage();
                return ExitCode::SUCCESS;
            }
            other if other.starts_with('-') => {
                eprintln!("pmtiles_extract: unexpected argument '{other}'");
                usage();
                return ExitCode::from(2);
            }
            other => {
                if input.is_some() {
                    eprintln!("pmtiles_extract: more than one input archive given");
                    return ExitCode::from(2);
                }
                input = Some(other.to_string());
                i += 1;
            }
        }
    }

    let (Some(input), Some(out)) = (input, out) else {
        eprintln!("pmtiles_extract: an input archive and --out are both required");
        usage();
        return ExitCode::from(2);
    };
    if let (Some(lo), Some(hi)) = (min_zoom, max_zoom) {
        if lo > hi {
            eprintln!("pmtiles_extract: --minzoom {lo} is above --maxzoom {hi}");
            return ExitCode::from(2);
        }
    }

    let mut archive = match ArchiveFile::open(&input) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("pmtiles_extract: {input} is not a readable PMTiles archive: {e}");
            return ExitCode::FAILURE;
        }
    };
    let source_bytes = std::fs::metadata(&input).map(|m| m.len()).unwrap_or(0);
    let compression = archive.header.tile_compression;
    // Runs expanded: a run means several ids share one body and each id is its own tile
    // in the subset.
    let mut rows: Vec<(u64, u64, u32)> = Vec::new();
    if let Err(e) = archive.visit_entries(&mut |e| {
        for k in 0..e.run_length as u64 {
            rows.push((e.tile_id + k, e.offset, e.length));
        }
        Ok(())
    }) {
        eprintln!("pmtiles_extract: cannot walk the tile directory: {e}");
        return ExitCode::FAILURE;
    }

    let lo = min_zoom.unwrap_or(archive.header.min_zoom);
    let hi = max_zoom.unwrap_or(archive.header.max_zoom);
    let mut builder = Builder::new();
    builder.min_zoom = lo;
    builder.max_zoom = hi;
    builder.metadata = archive.metadata.clone();
    builder.center_zoom = archive.header.center_zoom.clamp(lo, hi);
    // The bbox, when given, is the truthful extent of what we kept.
    match bbox {
        Some([w, s, e, n]) => {
            builder.min_lon_e7 = e7(w);
            builder.min_lat_e7 = e7(s);
            builder.max_lon_e7 = e7(e);
            builder.max_lat_e7 = e7(n);
            builder.center_lon_e7 = e7((w + e) / 2.0);
            builder.center_lat_e7 = e7((s + n) / 2.0);
        }
        None => {
            builder.min_lon_e7 = archive.header.min_lon_e7;
            builder.min_lat_e7 = archive.header.min_lat_e7;
            builder.max_lon_e7 = archive.header.max_lon_e7;
            builder.max_lat_e7 = archive.header.max_lat_e7;
            builder.center_lon_e7 = archive.header.center_lon_e7;
            builder.center_lat_e7 = archive.header.center_lat_e7;
        }
    }

    let mut kept = 0usize;
    let mut dropped_zoom = 0usize;
    let mut dropped_bbox = 0usize;
    let mut dropped_layer = 0usize;
    let mut seen_layers: BTreeSet<String> = BTreeSet::new();
    let mut body = Vec::new();

    for (id, off, len) in &rows {
        let (z, x, y) = pmtiles::tile_zxy(*id);
        if z < lo || z > hi {
            dropped_zoom += 1;
            continue;
        }
        if let Some(b) = &bbox {
            if !tile_in_bbox(z, x, y, b) {
                dropped_bbox += 1;
                continue;
            }
        }
        // Read only after the cheap filters, so a bbox subset does not touch the bodies
        // it is about to discard.
        if let Err(e) = archive.body_into(*off, *len, &mut body) {
            eprintln!("pmtiles_extract: z{z}/{x}/{y}: {e}");
            return ExitCode::FAILURE;
        }
        if layers.is_empty() {
            // Nothing to filter: copy the compressed body straight across, so the
            // subset is byte-identical to the source for every tile it keeps.
            builder.add_tile_raw(*id, body.clone());
            kept += 1;
            continue;
        }

        let raw = if compression == pmtiles::COMPRESSION_GZIP {
            match tile_build::gz::decompress(&body) {
                Ok(v) => v,
                Err(e) => {
                    eprintln!("pmtiles_extract: z{z}/{x}/{y}: {e}");
                    return ExitCode::FAILURE;
                }
            }
        } else {
            body.clone()
        };
        let mut tile = match Tile::decode(&raw) {
            Ok(t) => t,
            Err(e) => {
                eprintln!("pmtiles_extract: z{z}/{x}/{y}: {e}");
                return ExitCode::FAILURE;
            }
        };
        for l in &tile.layers {
            seen_layers.insert(l.name.clone());
        }
        tile.layers.retain(|l| layers.contains(&l.name));
        if tile.layers.is_empty() {
            dropped_layer += 1;
            continue;
        }
        builder.add_tile(z, x, y, &tile.encode());
        kept += 1;
    }

    if !layers.is_empty() {
        let missing: Vec<&String> = layers.iter().filter(|l| !seen_layers.contains(*l)).collect();
        if !missing.is_empty() {
            // Loud, and fatal: a silent empty result from a typo'd layer name would
            // quietly drop the borders out of the next published archive.
            eprintln!(
                "pmtiles_extract: no such layer in {input}: {}. Present: {}",
                missing
                    .iter()
                    .map(|s| s.as_str())
                    .collect::<Vec<_>>()
                    .join(", "),
                seen_layers.iter().cloned().collect::<Vec<_>>().join(", ")
            );
            return ExitCode::FAILURE;
        }
    }
    if kept == 0 {
        eprintln!(
            "pmtiles_extract: nothing kept ({dropped_zoom} out of zoom range, \
             {dropped_bbox} outside the bbox, {dropped_layer} with no wanted layer)"
        );
        return ExitCode::FAILURE;
    }

    let built = match builder.build() {
        Ok(b) => b,
        Err(e) => {
            eprintln!("pmtiles_extract: {e}");
            return ExitCode::FAILURE;
        }
    };
    if let Err(e) = std::fs::write(&out, &built) {
        eprintln!("pmtiles_extract: cannot write {out}: {e}");
        return ExitCode::FAILURE;
    }
    // Writing something we cannot read back is a bug, not a warning.
    if let Err(e) = Archive::parse(&built) {
        eprintln!("pmtiles_extract: wrote {out} but cannot re-read it: {e}");
        return ExitCode::FAILURE;
    }

    eprintln!(
        "pmtiles_extract: wrote {out} ({:.1} MiB from {:.1} MiB): {kept} tile(s), z{lo}-{hi}",
        built.len() as f64 / (1024.0 * 1024.0),
        source_bytes as f64 / (1024.0 * 1024.0),
    );
    if !layers.is_empty() {
        eprintln!(
            "pmtiles_extract: kept layer(s) {}",
            layers.iter().cloned().collect::<Vec<_>>().join(", ")
        );
    }
    eprintln!(
        "pmtiles_extract: dropped {dropped_zoom} by zoom, {dropped_bbox} by bbox, \
         {dropped_layer} with no wanted layer"
    );
    ExitCode::SUCCESS
}

fn usage() {
    eprintln!("usage: pmtiles_extract IN.pmtiles --out OUT.pmtiles");
    eprintln!("                       [--bbox minlon,minlat,maxlon,maxlat]");
    eprintln!("                       [--minzoom N] [--maxzoom N] [--layer NAME]...");
}

fn parse_bbox(s: &str) -> Result<[f64; 4], String> {
    let parts: Vec<&str> = s.split(',').map(str::trim).collect();
    if parts.len() != 4 {
        return Err(format!(
            "--bbox wants minlon,minlat,maxlon,maxlat, got {} field(s)",
            parts.len()
        ));
    }
    let mut v = [0.0f64; 4];
    for (i, p) in parts.iter().enumerate() {
        v[i] = p
            .parse()
            .map_err(|_| format!("--bbox field {} is not a number: {p}", i + 1))?;
    }
    // Reversed rather than silently swapped: a transposed box usually means lat and
    // lon went in the wrong way round, and normalising it would produce a
    // plausible-looking extract of the wrong place.
    if v[0] >= v[2] || v[1] >= v[3] {
        return Err(format!("--bbox is empty or reversed: {s}"));
    }
    Ok(v)
}

/// Does tile `(z, x, y)` overlap the lon/lat box?
///
/// Compared in tile space rather than by un-projecting the tile: the tile grid is
/// what the archive is indexed by, so converting the box once per zoom is both
/// cheaper and free of the rounding a per-tile inverse projection would add.
fn tile_in_bbox(z: u8, x: u64, y: u64, [w, s, e, n]: &[f64; 4]) -> bool {
    let (x0, y0) = tile_build::geom::project(*w, *n, z); // north-west
    let (x1, y1) = tile_build::geom::project(*e, *s, z); // south-east
    let last = ((1u64 << z) - 1) as f64;
    let (tx0, tx1) = (x0.floor().max(0.0), x1.floor().min(last));
    let (ty0, ty1) = (y0.floor().max(0.0), y1.floor().min(last));
    (x as f64) >= tx0 && (x as f64) <= tx1 && (y as f64) >= ty0 && (y as f64) <= ty1
}

fn e7(deg: f64) -> i32 {
    (deg * 1e7).round().clamp(i32::MIN as f64, i32::MAX as f64) as i32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_bbox_parses_and_rejects_the_transposed_form() {
        assert_eq!(
            parse_bbox("-122.6,37.2,-121.7,37.9").unwrap(),
            [-122.6, 37.2, -121.7, 37.9]
        );
        assert_eq!(parse_bbox(" -1 , -2 , 3 , 4 ").unwrap(), [-1.0, -2.0, 3.0, 4.0]);
        for bad in ["", "1,2,3", "1,2,3,4,5", "a,2,3,4", "3,2,1,4", "1,4,3,2", "1,2,1,4"] {
            assert!(parse_bbox(bad).is_err(), "{bad:?} should not parse");
        }
    }

    #[test]
    fn tile_membership_matches_the_projection() {
        // San Francisco at z11 is tile 327/791.
        let sf = [-122.6, 37.2, -121.7, 37.9];
        assert!(tile_in_bbox(11, 327, 791, &sf));
        // New York is nowhere near it.
        let (nx, ny) = tile_build::geom::project(-74.0, 40.7, 11);
        assert!(!tile_in_bbox(11, nx.floor() as u64, ny.floor() as u64, &sf));
        // At z0 the whole world is one tile, so any box includes it.
        assert!(tile_in_bbox(0, 0, 0, &sf));
    }

    #[test]
    fn a_bbox_at_the_grid_edges_is_clamped_not_wrapped() {
        let world = [-180.0, -85.0, 180.0, 85.0];
        let n = 1u64 << 4;
        assert!(tile_in_bbox(4, 0, 0, &world));
        assert!(tile_in_bbox(4, n - 1, n - 1, &world));
    }
}
