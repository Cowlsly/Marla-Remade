//! `mamaps_dump` — print a canonical, line-oriented summary of a `.mamaps` archive.
//!
//! Usage:
//!   mamaps_dump IN.mamaps [--mode summary|tiles|header|dict] [--layer NAME]
//!
//! The `.mamaps` counterpart of `pmtiles_dump`, and for the same reason: a binary container is
//! only reviewable if there is a text rendering of it, and a golden file only diffs if that
//! rendering is deterministic. Everything here is `BTreeMap`/`BTreeSet` ordered, so there is no
//! sort step and no way for output order to depend on a hash seed.
//!
//! Modes:
//!   summary  one line per (zoom, layer): tile/feature counts, geometry-type counts and the
//!            sorted union of `kind`s. The default.
//!   tiles    one line per (z/x/y, layer). Verbose, for chasing a single tile.
//!   header   the header fields, one per line. Also what checks a published file is sane.
//!   dict     the interned tables, one id per line.
//!
//! Unlike `pmtiles_dump` this reads the whole file into memory. A `.mamaps` archive is opened by
//! range request on device, but a dump is a host tool run against a file that is already local, and
//! the format's whole point is that it is smaller than the MVT it replaces.

use std::collections::{BTreeMap, BTreeSet};
use std::process::ExitCode;

use tile_build::mamaps::body::{Body, GEOM_LINE, GEOM_POLYGON};
use tile_build::mamaps::{dict, read};
use tile_build::pmtiles::tile_zxy;

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
                        eprintln!("mamaps_dump: --mode needs a value");
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
                eprintln!("mamaps_dump: unexpected argument '{other}'");
                usage();
                return ExitCode::from(2);
            }
            other => {
                if input.is_some() {
                    eprintln!("mamaps_dump: more than one input archive given");
                    return ExitCode::from(2);
                }
                input = Some(other.to_string());
                i += 1;
            }
        }
    }

    let Some(input) = input else {
        usage();
        return ExitCode::from(2);
    };
    let bytes = match std::fs::read(&input) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("mamaps_dump: cannot read {input}: {e}");
            return ExitCode::from(1);
        }
    };
    match run(&bytes, &mode, only_layer.as_deref()) {
        Ok(code) => code,
        Err(e) => {
            eprintln!("mamaps_dump: {input}: {e}");
            ExitCode::from(1)
        }
    }
}

fn run(bytes: &[u8], mode: &str, only_layer: Option<&str>) -> tile_build::proto::Result<ExitCode> {
    let (header, dictionary, _root) = read::open_prefix(bytes)?;
    if mode == "header" {
        println!("format_version\t{}", tile_build::mamaps::header::FORMAT_VERSION);
        println!("build_id\t{:#018x}", header.build_id);
        println!("flags\t{:#06x}", header.flags);
        println!("bodies_compressed\t{}", header.compressed());
        println!("rings_validated\t{}", header.rings_validated());
        println!("compression\t{}", header.compression);
        println!("layer_count\t{}", header.layer_count);
        println!("min_zoom\t{}", header.min_zoom);
        println!("max_zoom\t{}", header.max_zoom);
        println!("file_len\t{}", header.file_len);
        println!("dict\t{}+{}", header.dict_offset, header.dict_len);
        println!("root\t{}+{}", header.root_offset, header.root_len);
        println!("leaves\t{}+{}\tcount={}", header.leaf_offset, header.leaf_len, header.leaf_count);
        println!("data\t{}+{}", header.data_offset, header.data_len);
        println!("leaf_entry_capacity\t{}", header.leaf_entry_capacity);
        println!("tiles_addressed\t{}", header.tiles_addressed);
        println!("bodies_written\t{}", header.bodies_written);
        // The dedup ratio, which is the number worth watching between builds: it is what makes
        // ocean and empty tiles nearly free.
        println!(
            "dedup\t{:.3}",
            header.bodies_written as f64 / header.tiles_addressed.max(1) as f64,
        );
        println!("min_lon_e7\t{}", header.min_lon_e7);
        println!("min_lat_e7\t{}", header.min_lat_e7);
        println!("max_lon_e7\t{}", header.max_lon_e7);
        println!("max_lat_e7\t{}", header.max_lat_e7);
        println!("prefix_bytes\t{}", 128 + header.dict_len + header.root_len);
        return Ok(ExitCode::SUCCESS);
    }
    if mode == "dict" {
        for (id, name) in dictionary.layers.iter().enumerate() {
            println!("layer\t{id}\t{name}");
        }
        for (index, name) in dictionary.kinds.iter().enumerate() {
            println!("kind\t{}\t{name}", index + 1);
        }
        for (index, name) in dictionary.details.iter().enumerate() {
            println!("kind_detail\t{}\t{name}", index + 1);
        }
        return Ok(ExitCode::SUCCESS);
    }
    if !matches!(mode, "summary" | "tiles") {
        eprintln!("mamaps_dump: unknown mode '{mode}'");
        return Ok(ExitCode::from(2));
    }

    let mut agg: BTreeMap<(u8, String), Agg> = BTreeMap::new();
    let mut undecodable = 0usize;
    for (tile_id, run_length, body) in read::read_all(bytes)? {
        let body = match Body::parse(&body) {
            Ok(b) => b,
            Err(_) => {
                undecodable += 1;
                continue;
            }
        };
        // A run is one body standing in for several tiles, and each of those tiles is really
        // present — so expanding it is what makes the counts comparable with an archive that
        // happened not to dedup.
        for offset in 0..run_length as u64 {
            let (z, x, y) = tile_zxy(tile_id + offset);
            for layer in &body.layers {
                let name = dictionary
                    .layer_name(layer.layer_id)
                    .map(str::to_string)
                    .unwrap_or_else(|| format!("layer{}", layer.layer_id));
                if only_layer.is_some_and(|want| want != name) {
                    continue;
                }
                let mut geoms: BTreeMap<&str, usize> = BTreeMap::new();
                let mut kinds: BTreeSet<String> = BTreeSet::new();
                let mut points = 0usize;
                for feature in &layer.features {
                    *geoms.entry(geom_name(feature.geom_type)).or_default() += 1;
                    kinds.insert(kind_label(&dictionary, feature));
                    points +=
                        layer.parts_of(feature).iter().map(|p| p.point_count as usize).sum::<usize>();
                }
                if mode == "tiles" {
                    println!(
                        "{z}/{x}/{y}\t{name}\tfeatures={}\tpoints={points}\textent={}\tgeom={}\tkinds={}",
                        layer.features.len(),
                        body.extent,
                        join_counts(&geoms),
                        kinds.iter().cloned().collect::<Vec<_>>().join(","),
                    );
                    continue;
                }
                let entry = agg.entry((z, name)).or_default();
                entry.tiles += 1;
                entry.features += layer.features.len();
                entry.points += points;
                entry.extents.insert(body.extent);
                for (geom, count) in geoms {
                    *entry.geoms.entry(geom.to_string()).or_default() += count;
                }
                entry.kinds.extend(kinds);
            }
        }
    }

    if mode == "summary" {
        for ((z, layer), a) in &agg {
            println!(
                "z{z}\t{layer}\ttiles={}\tfeatures={}\tpoints={}\textent={}\tgeom={}\tkinds={}",
                a.tiles,
                a.features,
                a.points,
                a.extents.iter().map(|e| e.to_string()).collect::<Vec<_>>().join("/"),
                join_counts_owned(&a.geoms),
                a.kinds.iter().cloned().collect::<Vec<_>>().join(","),
            );
        }
    }
    // Counted and reported, but not fatal: a dump of a mostly-good archive is more use than no
    // dump at all, and the count is what says whether to trust the rest.
    if undecodable > 0 {
        eprintln!("mamaps_dump: {undecodable} tile(s) could not be decoded");
    }
    Ok(ExitCode::SUCCESS)
}

#[derive(Default)]
struct Agg {
    tiles: usize,
    features: usize,
    points: usize,
    extents: BTreeSet<u16>,
    geoms: BTreeMap<String, usize>,
    kinds: BTreeSet<String>,
}

/// How a feature's `kind` prints: its interned name, its numeric detail, or `-` for neither.
///
/// `boundaries` carries an admin level rather than a `kind`, so printing the number is the only way
/// its rows say anything at all.
fn kind_label(dictionary: &dict::Dictionary, feature: &tile_build::mamaps::body::Feature) -> String {
    if let Some(level) = feature.detail_number() {
        return format!("level{level}");
    }
    match dictionary.kind_name(feature.kind) {
        Some(name) => match dictionary.detail_name(feature.kind_detail) {
            Some(detail) => format!("{name}:{detail}"),
            None => name.to_string(),
        },
        None => "-".to_string(),
    }
}

fn geom_name(geom_type: u8) -> &'static str {
    match geom_type {
        GEOM_LINE => "line",
        GEOM_POLYGON => "polygon",
        _ => "unknown",
    }
}

fn join_counts(m: &BTreeMap<&str, usize>) -> String {
    m.iter().map(|(k, v)| format!("{k}:{v}")).collect::<Vec<_>>().join(",")
}

fn join_counts_owned(m: &BTreeMap<String, usize>) -> String {
    m.iter().map(|(k, v)| format!("{k}:{v}")).collect::<Vec<_>>().join(",")
}

fn usage() {
    eprintln!("usage: mamaps_dump IN.mamaps [--mode summary|tiles|header|dict] [--layer NAME]");
}
