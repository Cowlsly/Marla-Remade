//! `road_graph IN.osm.pbf [--out DIR]` — build the single global routing graph.
//!
//! Replaces `scripts/maps/generator.cpp`. See `osm_ingest::graph_build` for the
//! on-disk contract.

use std::process::ExitCode;

const USAGE: &str = "Usage: road_graph IN.osm.pbf [--out DIR] [--within-way-chains] \
                     [--rounds N] [--spill-dir DIR] [--spill-pts-dir DIR] [--threads N] \
                     [--stats] (default --out map_data, --rounds 1)";

/// Print what a narrower `edges.bin` record would cost on this extract.
///
/// The `c` column is the point of the table. A single escape rate measured on one
/// region says nothing about a planet 22x larger, but for nodes ordered along a
/// space-filling curve the tail decays as `P(|delta| > n) = c * n^(-1/2)`: a level-k
/// quadrant boundary produces a delta of order `N / 4^k` and is crossed with
/// probability proportional to `2^k`, and the constant is the ratio of edge length
/// to node spacing, which does not depend on `N`. So `c` is what transfers between
/// extracts, and a `c` column that is flat down the rows is the evidence that the
/// model holds at all.
fn print_census(stats: &osm_ingest::graph_build::Stats) {
    let c = &stats.census;
    let e = stats.edge_count.max(1) as f64;
    println!();
    println!("=== edges.bin narrowing census ===");
    println!("directed edges          {}", stats.edge_count);
    println!("nodes                   {}", stats.node_count);
    println!();
    println!("|target - source| tail (a level-k quadrant boundary costs ~N/4^k):");
    println!("        n        edges > n      fraction         c = P*sqrt(n)");
    for k in 0..32u32 {
        let n = 1u64 << k;
        let tail = c.delta_tail(k);
        // Once the tail empties the row says nothing, and the whole point is the
        // rows either side of the +-32767 boundary.
        if tail == 0 {
            println!("  {n:>15}  {tail:>15}  (tail empty)");
            break;
        }
        let p = tail as f64 / e;
        println!(
            "  {n:>15}  {tail:>15}  {:>12.6}%  {:>12.4}",
            p * 100.0,
            p * (n as f64).sqrt()
        );
    }
    println!();
    println!("max |target - source|   {}", c.max_delta);
    println!(
        "i16 delta escapes       {} ({:.4}% of edges)",
        c.delta_escapes,
        c.delta_escapes as f64 / e * 100.0
    );
    println!(
        "u24 dist_mm escapes     {} ({:.6}% of edges), max dist_mm {} mm",
        c.dist_escapes,
        c.dist_escapes as f64 / e * 100.0,
        c.max_dist_mm
    );
    println!(
        "merged escape rows      {} ({:.4}% of edges, {} bytes of table)",
        c.escapes,
        c.escapes as f64 / e * 100.0,
        c.escapes * 12
    );
    println!();
    println!("max out-degree          {}", c.max_degree);
    println!(
        "degree-0 nodes          {} ({:.4}% of nodes)",
        c.degree_zero_nodes,
        c.degree_zero_nodes as f64 / stats.node_count.max(1) as f64 * 100.0
    );
    println!(
        "named edges             {} ({:.4}% of edges)",
        c.named_edges,
        c.named_edges as f64 / e * 100.0
    );
    println!(
        "name pool               {} byte(s) in {} unique name(s)",
        stats.name_bytes, stats.unique_names
    );
}

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
    let want_stats = opts.stats;
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
            println!(
                "edges.bin: {} byte(s), {} edge(s) needed the escape table ({:.4}%), \
                 {} named ({:.2}%)",
                stats.edges_bytes,
                stats.escape_count,
                stats.escape_count as f64 / stats.edge_count.max(1) as f64 * 100.0,
                stats.named_edges,
                stats.named_edges as f64 / stats.edge_count.max(1) as f64 * 100.0
            );
            println!("Peak RSS: {}", osm_ingest::mem::peak_rss_report());
            println!("Wrote {}", out_dir.display());
            if want_stats {
                print_census(&stats);
            }
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}
