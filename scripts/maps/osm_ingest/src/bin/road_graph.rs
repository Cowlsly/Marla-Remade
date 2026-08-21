//! `road_graph IN.osm.pbf [--out DIR]` — build the single global routing graph.
//!
//! Replaces `scripts/maps/generator.cpp`. See `osm_ingest::graph_build` for the
//! on-disk contract.

use std::process::ExitCode;

const USAGE: &str = "Usage: road_graph IN.osm.pbf [--out DIR]   (default --out map_data)";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (input, out_dir) = match osm_ingest::graph_build::parse_args(&args) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("error: {e}\n{USAGE}");
            return ExitCode::FAILURE;
        }
    };

    let started = std::time::Instant::now();
    match osm_ingest::graph_build::build(&input, &out_dir) {
        Ok(stats) => {
            println!(
                "{} node(s), {} edge(s), {} unique name(s) ({} bytes), LCC {} node(s), \
                 {} isolated stop(s) reconnected in {:.1}s",
                stats.node_count,
                stats.edge_count,
                stats.unique_names,
                stats.name_bytes,
                stats.lcc_size,
                stats.reconnected_stops,
                started.elapsed().as_secs_f64()
            );
            println!("Wrote {}", out_dir.display());
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}
