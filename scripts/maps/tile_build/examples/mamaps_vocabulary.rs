//! Collect the `(layer, kind)` vocabulary the published archive actually uses, and flag anything
//! `mamaps::dict` has no id for.
//!
//! The measurement that says how complete the schema table is. Sampled rather than exhaustive: the
//! published archive is 137 GB, and the point is to find values we do not carry, which show up in
//! the first few hundred tiles or not at all.

use std::collections::{BTreeMap, BTreeSet};
use std::process::Command;

use tile_build::mamaps::{dict, from_mvt};
use tile_build::mvt::{Tile, Value};
use tile_build::proto::{err, Result};
use tilecodec::stream::{RangeReader, StreamArchive};

const URL: &str = "https://data.vayunmathur.com/v4.pmtiles";

struct Curl;

impl RangeReader for Curl {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        let range = format!("{offset}-{}", offset + length as u64 - 1);
        match Command::new("curl").args(["-s", "-f", "--range", &range, URL]).output() {
            Ok(out) if out.status.success() => Ok(out.stdout),
            Ok(out) => err(format!("curl failed: {}", out.status)),
            Err(e) => err(format!("curl: {e}")),
        }
    }
}

fn main() {
    let mut archive = StreamArchive::open(Curl).expect("open");
    // A spread rather than a sweep: world, California, the Bay, SF, downtown, plus a few places
    // with different geography so the vocabulary is not just one metro's.
    let tiles: &[(u8, u32, u32)] = &[
        (0, 0, 0),
        (2, 0, 1),
        (4, 2, 6),
        (5, 5, 12),
        (6, 10, 24),
        (8, 40, 98),
        (8, 41, 98),
        (10, 163, 395),
        (11, 327, 791),
        (12, 655, 1583),
        (13, 1310, 3166),
        (14, 2620, 6333),
        (14, 2621, 6333),
        (14, 2620, 6334),
    ];

    let mut seen: BTreeMap<String, BTreeMap<String, usize>> = BTreeMap::new();
    let mut details: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    let mut other_props: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    for (z, x, y) in tiles {
        let Some(raw) = archive.tile(*z, *x, *y).expect("tile") else { continue };
        let tile = Tile::decode(&raw).expect("decode");
        for layer in &tile.layers {
            if !dict::LAYERS.contains(&layer.name.as_str()) {
                continue;
            }
            for feature in &layer.features {
                for (key, value) in &feature.props {
                    match (key.as_str(), value) {
                        ("kind", Value::String(kind)) => {
                            *seen
                                .entry(layer.name.clone())
                                .or_default()
                                .entry(kind.clone())
                                .or_default() += 1;
                        }
                        ("kind_detail", Value::String(detail)) => {
                            details.entry(layer.name.clone()).or_default().insert(detail.clone());
                        }
                        ("kind_detail", other) => {
                            details
                                .entry(layer.name.clone())
                                .or_default()
                                .insert(format!("<{other:?}>"));
                        }
                        (key, _) => {
                            other_props.entry(layer.name.clone()).or_default().insert(key.to_string());
                        }
                    }
                }
            }
        }
    }

    println!("== kinds, by layer. `MISSING` is one the schema table has no id for ==");
    for (layer, kinds) in &seen {
        for (kind, count) in kinds {
            let mark = if from_mvt::kind_id(kind).is_some() { "     " } else { "MISS " };
            println!("{mark}{layer:<12}{kind:<26}{count}");
        }
    }
    println!("\n== kind_detail values seen ==");
    for (layer, values) in &details {
        for value in values {
            let mark = if from_mvt::detail_id(value).is_some() { "     " } else { "MISS " };
            println!("{mark}{layer:<12}{value}");
        }
    }
    println!("\n== other property keys, which this format deliberately drops ==");
    for (layer, keys) in &other_props {
        println!("{layer:<12}{}", keys.iter().cloned().collect::<Vec<_>>().join(","));
    }
}
