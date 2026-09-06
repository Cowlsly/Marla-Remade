//! The `roads` counterpart of [`roads_probe`] for the vendored PMTiles basemap.
//!
//! ```text
//! cargo run --offline -p map_renderer --example pmtiles_roads_probe -- 6 10 24
//! ```
//!
//! Same question, other side of the comparator: which `kind`s does the reference
//! archive carry at a zoom where ours looks fragmented? HTTP is shelled to `curl`,
//! as `probe_archive` does, so the crate keeps no HTTP dependency.

use std::process::Command;
use tilecodec::mvt::Tile;
use tilecodec::proto::{err, Result};
use tilecodec::stream::{RangeReader, StreamArchive};

const URL: &str = "https://data.vayunmathur.com/v4.pmtiles";

struct CurlReader;

impl RangeReader for CurlReader {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        let range = format!("{}-{}", offset, offset + length as u64 - 1);
        let out = match Command::new("curl").args(["-s", "-f", "--range", &range, URL]).output() {
            Ok(out) => out,
            Err(e) => return err(format!("curl failed to start: {e}")),
        };
        if !out.status.success() {
            return err(format!("curl exited {:?} for range {range}", out.status.code()));
        }
        Ok(out.stdout)
    }
}

fn show(value: Option<&tilecodec::mvt::Value>) -> String {
    use tilecodec::mvt::Value;
    match value {
        Some(Value::String(s)) => s.clone(),
        Some(other) => format!("{other:?}"),
        None => "-".to_string(),
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 4 {
        eprintln!("usage: pmtiles_roads_probe Z X Y");
        std::process::exit(2);
    }
    let z: u8 = args[1].parse().expect("z");
    let x: u32 = args[2].parse().expect("x");
    let y: u32 = args[3].parse().expect("y");

    let mut archive = StreamArchive::open(CurlReader).expect("open archive");
    let Some(bytes) = archive.tile(z, x, y).expect("read tile") else {
        println!("{z}/{x}/{y} is absent");
        return;
    };
    let tile = Tile::decode(&bytes).expect("decode mvt");
    let Some(layer) = tile.layers.iter().find(|l| l.name == "roads") else {
        println!("{z}/{x}/{y} has no roads layer");
        return;
    };

    let mut totals: std::collections::BTreeMap<(String, String), (u32, f64)> = Default::default();
    for feature in &layer.features {
        let kind = show(feature.get("kind"));
        let detail = show(feature.get("kind_detail"));
        let mut length = 0.0f64;
        if let Some(lines) = tilecodec::mvt::decode_lines(&feature.geometry) {
            for line in lines {
                for pair in line.windows(2) {
                    let dx = (pair[1].0 - pair[0].0) as f64;
                    let dy = (pair[1].1 - pair[0].1) as f64;
                    length += (dx * dx + dy * dy).sqrt();
                }
            }
        }
        let entry = totals.entry((kind, detail)).or_insert((0, 0.0));
        entry.0 += 1;
        entry.1 += length;
    }

    println!("{z}/{x}/{y} extent {} — roads by (kind, kind_detail):", layer.extent);
    for ((kind, detail), (count, length)) in totals {
        println!("  {kind:<14} {detail:<16} {count:>6} features  {length:>12.0} units");
    }
}
