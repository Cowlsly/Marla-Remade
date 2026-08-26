//! `pmtiles_dump` — print a canonical, line-oriented summary of a PMTiles
//! archive. The decoder half of the differential harness.
//!
//! Usage:
//!   pmtiles_dump IN.pmtiles [--mode summary|tiles|header] [--layer NAME]
//!
//! Output is deterministic and sorted, so two archives can be compared with a
//! plain textual diff. That is the point: comparing our tiler against tippecanoe
//! byte-for-byte is meaningless (`--drop-densest-as-needed` is a lossy per-tile
//! heuristic, and a re-encode reorders the key/value dictionaries anyway), so the
//! harness compares the decoded model instead — which layers exist at which
//! zooms, how many features they hold, and which property keys they carry.
//!
//! Modes:
//!   summary  one line per (zoom, layer): tile/feature counts, geometry-type
//!            counts, and the sorted union of property keys. The default, and
//!            what `diff_pmtiles.py` reads.
//!   tiles    one line per (z/x/y, layer). Verbose, for chasing a single tile.
//!   header   the archive header fields, one per line.
//!
//! The archive is opened rather than read: `--mode header` costs a 127-byte read, and
//! the other modes hold one tile body plus a 12-byte row per tile. Dumping a planet
//! layer used to mean an 8 GB allocation before the first line of output.

use std::collections::{BTreeMap, BTreeSet};
use std::process::ExitCode;
use tile_build::mvt::{GeomType, Tile};
use tile_build::pmtiles::ArchiveFile;
use tile_build::{gz, pmtiles};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut input = None;
    let mut mode = "summary".to_string();
    let mut only_layer: Option<String> = None;

    let mut i = 1;
    while i < args.len() {
        let take = |i: usize| args.get(i + 1).cloned();
        match args[i].as_str() {
            "--mode" => {
                match take(i) {
                    Some(m) => mode = m,
                    None => {
                        eprintln!("pmtiles_dump: --mode needs a value");
                        return ExitCode::from(2);
                    }
                }
                i += 2;
            }
            "--layer" => {
                only_layer = take(i);
                i += 2;
            }
            "-h" | "--help" => {
                usage();
                return ExitCode::SUCCESS;
            }
            other if other.starts_with('-') => {
                eprintln!("pmtiles_dump: unexpected argument '{other}'");
                usage();
                return ExitCode::from(2);
            }
            other => {
                if input.is_some() {
                    eprintln!("pmtiles_dump: more than one input archive given");
                    return ExitCode::from(2);
                }
                input = Some(other.to_string());
                i += 1;
            }
        }
    }

    let Some(input) = input else {
        eprintln!("pmtiles_dump: an input archive is required");
        usage();
        return ExitCode::from(2);
    };
    if !matches!(mode.as_str(), "summary" | "tiles" | "header") {
        eprintln!("pmtiles_dump: --mode must be summary|tiles|header (got '{mode}')");
        return ExitCode::from(2);
    }

    if mode == "header" {
        let h = match ArchiveFile::read_header(&input) {
            Ok(h) => h,
            Err(e) => {
                eprintln!("pmtiles_dump: {input} is not a readable PMTiles archive: {e}");
                return ExitCode::FAILURE;
            }
        };
        println!("min_zoom\t{}", h.min_zoom);
        println!("max_zoom\t{}", h.max_zoom);
        println!("addressed_tiles\t{}", h.addressed_tiles);
        println!("tile_entries\t{}", h.tile_entries);
        println!("tile_contents\t{}", h.tile_contents);
        println!("clustered\t{}", h.clustered);
        println!("tile_type\t{}", h.tile_type);
        println!("tile_compression\t{}", h.tile_compression);
        println!("min_lon_e7\t{}", h.min_lon_e7);
        println!("min_lat_e7\t{}", h.min_lat_e7);
        println!("max_lon_e7\t{}", h.max_lon_e7);
        println!("max_lat_e7\t{}", h.max_lat_e7);
        return ExitCode::SUCCESS;
    }

    let mut archive = match ArchiveFile::open(&input) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("pmtiles_dump: {input} is not a readable PMTiles archive: {e}");
            return ExitCode::FAILURE;
        }
    };
    let compression = archive.header.tile_compression;

    // Runs expanded, so a run of identical ocean tiles is still counted once per tile.
    let mut rows: Vec<(u64, u64, u32)> = Vec::new();
    if let Err(e) = archive.visit_entries(&mut |e| {
        for k in 0..e.run_length as u64 {
            rows.push((e.tile_id + k, e.offset, e.length));
        }
        Ok(())
    }) {
        eprintln!("pmtiles_dump: cannot walk the tile directory: {e}");
        return ExitCode::FAILURE;
    }

    // (zoom, layer) -> accumulator, ordered by BTreeMap so the output is sorted
    // without a separate sort step.
    let mut agg: BTreeMap<(u8, String), Agg> = BTreeMap::new();
    let mut undecodable = 0usize;
    let mut body = Vec::new();

    for (id, off, len) in &rows {
        let (z, x, y) = pmtiles::tile_zxy(*id);
        if let Err(e) = archive.body_into(*off, *len, &mut body) {
            eprintln!("pmtiles_dump: z{z}/{x}/{y}: {e}");
            return ExitCode::FAILURE;
        }
        let raw = if compression == pmtiles::COMPRESSION_GZIP {
            match gz::decompress(&body) {
                Ok(v) => v,
                Err(e) => {
                    eprintln!("pmtiles_dump: z{z}/{x}/{y}: {e}");
                    undecodable += 1;
                    continue;
                }
            }
        } else {
            body.clone()
        };
        let tile = match Tile::decode(&raw) {
            Ok(t) => t,
            Err(e) => {
                eprintln!("pmtiles_dump: z{z}/{x}/{y}: {e}");
                undecodable += 1;
                continue;
            }
        };
        for layer in &tile.layers {
            if let Some(want) = &only_layer {
                if &layer.name != want {
                    continue;
                }
            }
            if mode == "tiles" {
                let mut keys: BTreeSet<&str> = BTreeSet::new();
                let mut geoms: BTreeMap<&str, usize> = BTreeMap::new();
                for f in &layer.features {
                    *geoms.entry(geom_name(f.geom_type)).or_insert(0) += 1;
                    for (k, _) in &f.props {
                        keys.insert(k.as_str());
                    }
                }
                println!(
                    "{z}/{x}/{y}\t{}\tfeatures={}\textent={}\tgeom={}\tkeys={}",
                    layer.name,
                    layer.features.len(),
                    layer.extent,
                    join_counts(&geoms),
                    keys.into_iter().collect::<Vec<_>>().join(","),
                );
            }
            let e = agg.entry((z, layer.name.clone())).or_default();
            e.tiles += 1;
            e.features += layer.features.len();
            e.extents.insert(layer.extent);
            for f in &layer.features {
                *e.geoms.entry(geom_name(f.geom_type).to_string()).or_insert(0) += 1;
                for (k, _) in &f.props {
                    e.keys.insert(k.clone());
                }
            }
        }
    }

    if mode == "summary" {
        for ((z, layer), a) in &agg {
            println!(
                "z{z}\t{layer}\ttiles={}\tfeatures={}\textent={}\tgeom={}\tkeys={}",
                a.tiles,
                a.features,
                a.extents
                    .iter()
                    .map(|e| e.to_string())
                    .collect::<Vec<_>>()
                    .join("/"),
                join_counts_owned(&a.geoms),
                a.keys.iter().cloned().collect::<Vec<_>>().join(","),
            );
        }
    }

    if undecodable > 0 {
        // Loud on stderr but not fatal: a partial dump of a damaged archive is
        // still the most useful thing to hand the differ.
        eprintln!("pmtiles_dump: {undecodable} tile(s) could not be decoded");
    }
    ExitCode::SUCCESS
}

#[derive(Default)]
struct Agg {
    tiles: usize,
    features: usize,
    extents: BTreeSet<u32>,
    geoms: BTreeMap<String, usize>,
    keys: BTreeSet<String>,
}

fn geom_name(t: GeomType) -> &'static str {
    match t {
        GeomType::Unknown => "unknown",
        GeomType::Point => "point",
        GeomType::LineString => "line",
        GeomType::Polygon => "polygon",
    }
}

fn join_counts(m: &BTreeMap<&str, usize>) -> String {
    m.iter()
        .map(|(k, v)| format!("{k}:{v}"))
        .collect::<Vec<_>>()
        .join(",")
}

fn join_counts_owned(m: &BTreeMap<String, usize>) -> String {
    m.iter()
        .map(|(k, v)| format!("{k}:{v}"))
        .collect::<Vec<_>>()
        .join(",")
}

fn usage() {
    eprintln!("usage: pmtiles_dump IN.pmtiles [--mode summary|tiles|header] [--layer NAME]");
}
