//! `osm_extract IN.osm.pbf --layer NAME --out FILE [--bbox BOX]`
//!
//! One 3-pass driver for the baked vector layers, replacing the
//! `osmium tags-filter | osmium export | normalize_*.py` chain for each of them.
//! See `osm_ingest::extract` for the pass ordering and each layer's own module
//! for its schema.

use std::process::ExitCode;

const USAGE: &str =
    "Usage: osm_extract IN.osm.pbf --layer NAME --out FILE [--bbox BOX] [--threads N]";

fn main() -> ExitCode {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let args = match osm_ingest::extract::parse_args(&argv) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("error: {e}\n{USAGE}");
            eprintln!(
                "Layers: {}",
                osm_ingest::extract::Layer::names().join(", ")
            );
            return ExitCode::FAILURE;
        }
    };

    if let Some(n) = args.threads {
        osm_ingest::par::set_threads(n);
    }

    let options = osm_ingest::extract::Options {
        layer: args.layer,
        bbox: args.bbox,
    };
    let started = std::time::Instant::now();
    match osm_ingest::extract::build(&args.input, &args.out, &options) {
        Ok(stats) => {
            println!(
                "{}: {} feature(s) ({} node, {} way, {} relation) in {:.1}s",
                args.layer.name(),
                stats.features,
                stats.from_nodes,
                stats.from_ways,
                stats.from_relations,
                started.elapsed().as_secs_f64()
            );
            println!("Wrote {}", args.out.display());
            if stats.features == 0 {
                // Not an error -- an extract really can hold no cameras -- but an
                // empty layer is nearly always a wrong --bbox or a wrong PBF, and
                // the tiler downstream only warns about it.
                eprintln!(
                    "WARNING: 0 features. Check --bbox and that the PBF covers the area."
                );
            }
            if let Some(why) = stats.missing_half(args.layer) {
                eprintln!("WARNING: {}: {why}", args.layer.name());
            }
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}
