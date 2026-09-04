//! Dumps the `roads` layer of one tile of a local `.mamaps`, by kind and detail.
//!
//! ```text
//! cargo run --offline -p map_renderer --example roads_probe -- PATH.mamaps 7 20 48
//! ```
//!
//! Answers the only question a screenshot cannot: when a road looks like it stops
//! halfway across a tile, is the rest of it absent from the archive, or present under
//! a `kind` the style does not draw at that zoom?

use map_renderer::tile::source::FileRangeReader;
use tilecodec::mamaps::{dict, MamapsArchive};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 5 {
        eprintln!("usage: roads_probe PATH.mamaps Z X Y");
        std::process::exit(2);
    }
    let path = std::path::PathBuf::from(&args[1]);
    let z: u8 = args[2].parse().expect("z");
    let x: u32 = args[3].parse().expect("x");
    let y: u32 = args[4].parse().expect("y");

    let reader = FileRangeReader::open(&path).expect("open archive");
    let mut archive = MamapsArchive::open(reader).expect("parse archive");
    println!("archive z{}..{}", archive.header.min_zoom, archive.header.max_zoom);

    let Some(body) = archive.tile(z, x, y).expect("read tile") else {
        println!("{z}/{x}/{y} is absent");
        return;
    };
    let Some(layer) = body.layer(dict::LAYER_ROADS) else {
        println!("{z}/{x}/{y} has no roads layer");
        return;
    };
    let schema = dict::schema();

    // (kind, detail) -> (feature count, total tile-local length)
    let mut totals: std::collections::BTreeMap<(String, String), (u32, f64)> = Default::default();
    for feature in &layer.features {
        let kind = schema.kind_name(feature.kind).unwrap_or("?").to_string();
        let detail = if feature.flags & tilecodec::mamaps::body::FLAG_DETAIL_NUMERIC != 0 {
            format!("#{}", feature.kind_detail)
        } else {
            schema.detail_name(feature.kind_detail).unwrap_or("?").to_string()
        };
        let mut length = 0.0f64;
        for part in layer.parts_of(feature) {
            let points = layer.points(part);
            for pair in points.windows(2) {
                let dx = (pair[1].0 - pair[0].0) as f64;
                let dy = (pair[1].1 - pair[0].1) as f64;
                length += (dx * dx + dy * dy).sqrt();
            }
        }
        let entry = totals.entry((kind, detail)).or_insert((0, 0.0));
        entry.0 += 1;
        entry.1 += length;
    }

    println!("{z}/{x}/{y} extent {} — roads by (kind, detail):", body.extent);
    for ((kind, detail), (count, length)) in totals {
        println!("  {kind:<12} {detail:<16} {count:>6} features  {length:>12.0} units");
    }
}
