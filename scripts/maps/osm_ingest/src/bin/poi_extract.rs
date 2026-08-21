//! `poi_extract IN.osm.pbf --geojson FILE --names FILE --index FILE`
//!
//! Replaces `scripts/maps/poi_extract.cpp`. See `osm_ingest::poi_build` for the
//! POI type map and the on-disk record layout.

use std::process::ExitCode;

const USAGE: &str =
    "Usage: poi_extract IN.osm.pbf --geojson FILE --names FILE --index FILE";

fn main() -> ExitCode {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let args = match osm_ingest::poi_build::parse_args(&argv) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("error: {e}\n{USAGE}");
            return ExitCode::FAILURE;
        }
    };

    let started = std::time::Instant::now();
    match osm_ingest::poi_build::build(&args.input, &args.geojson, &args.names, &args.index) {
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
                "Wrote {}, {}, {}",
                args.geojson.display(),
                args.names.display(),
                args.index.display()
            );
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}
