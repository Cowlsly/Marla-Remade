//! `poi_extract IN.osm.pbf --geojson FILE --names FILE --index FILE [--attrs FILE]`
//!
//! Replaces `scripts/maps/poi_extract.cpp`. See `osm_ingest::poi_build` for the
//! POI type map and the on-disk record layout.

use std::process::ExitCode;

const USAGE: &str = "Usage: poi_extract IN.osm.pbf --geojson FILE --names FILE \
                     --index FILE [--attrs FILE] [--threads N]";

fn main() -> ExitCode {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let args = match osm_ingest::poi_build::parse_args(&argv) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("error: {e}\n{USAGE}");
            return ExitCode::FAILURE;
        }
    };

    if let Some(n) = args.threads {
        osm_ingest::par::set_threads(n);
    }

    let started = std::time::Instant::now();
    match osm_ingest::poi_build::build(
        &args.input,
        &args.geojson,
        &args.names,
        &args.index,
        &args.attrs,
        &args.spatial,
        &args.name_index,
    ) {
        Ok(stats) => {
            println!(
                "{} POI(s) ({} node, {} closed-way, {} relation), {} unique name(s) \
                 ({} bytes) in {:.1}s",
                stats.records,
                stats.from_nodes,
                stats.from_ways,
                stats.from_relations,
                stats.unique_names,
                stats.name_bytes,
                started.elapsed().as_secs_f64()
            );
            println!(
                "{} POI(s) with attributes, {} unique record(s), {} sidecar byte(s)",
                stats.with_attrs, stats.unique_attrs, stats.attr_bytes
            );
            println!(
                "{} populated grid cell(s), {} name index entr(ies)",
                stats.spatial_cells, stats.name_entries
            );
            println!(
                "Wrote {}, {}, {}, {}, {}, {}",
                args.geojson.display(),
                args.names.display(),
                args.index.display(),
                args.attrs.display(),
                args.spatial.display(),
                args.name_index.display()
            );
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}
