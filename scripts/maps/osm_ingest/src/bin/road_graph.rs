//! `road_graph IN.osm.pbf [--out DIR]` — build the single global routing graph.
//!
//! Replaces `scripts/maps/generator.cpp`. See `osm_ingest::graph_build` for the
//! on-disk contract.

use std::process::ExitCode;

const USAGE: &str = "Usage: road_graph IN.osm.pbf [--out DIR] [--within-way-chains] \
                     [--rounds N] [--spill-dir DIR] [--spill-pts-dir DIR]   \
                     (default --out map_data, --rounds 1)";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (input, out_dir, opts) = match osm_ingest::graph_build::parse_args(&args) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("error: {e}\n{USAGE}");
            return ExitCode::FAILURE;
        }
    };

    let started = std::time::Instant::now();
    match osm_ingest::graph_build::build_with(&input, &out_dir, opts) {
        Ok(stats) => {
            println!(
                "{} node(s), {} edge(s), {} unique name(s) ({} bytes), largest component \
                 {} node(s) in {:.1}s",
                stats.node_count,
                stats.edge_count,
                stats.unique_names,
                stats.name_bytes,
                stats.lcc_size,
                started.elapsed().as_secs_f64()
            );
            // The three counters that make MIN_ROUTABLE_COMPONENT auditable. A
            // large "found nothing" is the signal that the threshold is too high.
            println!(
                "Transit stops: {} already on a routable component, {} reconnected, \
                 {} found nothing",
                stats.stops_already_connected, stats.reconnected_stops, stats.stops_unreachable
            );
            println!(
                "Compaction: {} -> {} node(s) ({:.2}x), {} -> {} directed edge(s) ({:.2}x), \
                 {} chain(s) split for the 256-point limit",
                stats.raw_node_count,
                stats.node_count,
                stats.raw_node_count as f64 / stats.node_count.max(1) as f64,
                stats.raw_edge_count,
                stats.edge_count,
                stats.raw_edge_count as f64 / stats.edge_count.max(1) as f64,
                stats.chain_splits
            );
            println!(
                "intermediate.bin: {} byte(s), {} edge(s) store a polyline, \
                 {} defer to a twin",
                stats.intermediate_bytes, stats.geometry_edges, stats.reversed_edges
            );
            println!("Peak RSS: {}", osm_ingest::mem::peak_rss_report());
            println!("Wrote {}", out_dir.display());
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}
