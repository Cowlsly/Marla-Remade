//! `mamaps_size` — what a `.mamaps` body costs against the MVT it replaces, across zooms.
//!
//! The measurement that settled the coordinate arena, kept so it can be re-run. The first draft of
//! the format stored coordinates as flat `[i16 x, i16 y]` pairs on the reasoning that a zero-copy
//! slice is the cheapest decode; against the real published archive that cost **1.72× the
//! compressed bytes of the MVT it replaces**, because 87% of a body is coordinates and a fixed 4
//! bytes per point is worse than a zigzag varint delta of 1 to 2. Per-part varint deltas bring it
//! to about 1.14×.
//!
//! So this is a regression guard rather than a boast. `.mamaps` is **not** smaller than MVT; what it
//! wins is the decode — no per-tile string table, no property map, no `String` allocation per
//! feature, and one flat point list per layer instead of a geometry-command walk per feature. Run
//! it after any change to the body encoding.
//!
//! Reads the published archive over ranged GETs through `curl`, so it needs a network but no
//! local data.
//!
//! As measured: 1.02x MVT compressed over a San Francisco pyramid, and *better* than MVT at z8,
//! z11 and z4 where features carry attributes. Parity in bytes, and a much cheaper decode.

use std::process::Command;

use tile_build::mamaps::body;
use tile_build::mamaps::from_mvt;
use tile_build::mvt::Tile;
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
    let deflate = |b: &[u8]| miniz_oxide::deflate::compress_to_vec(b, 9).len();

    // San Francisco down the pyramid, which is what a user actually streams.
    let places: &[(u8, u32, u32, &str)] = &[
        (0, 0, 0, "world"),
        (4, 2, 6, "california"),
        (8, 40, 98, "bay area"),
        (11, 327, 791, "san francisco"),
        (14, 2620, 6333, "downtown SF"),
    ];
    println!(
        "{:<16}{:>10}{:>10}{:>10}{:>10}{:>9}{:>9}{:>8}",
        "tile", "mvt", "mvt-z", "mamaps", "mamaps-z", "ratio", "points", "feats",
    );
    let (mut mvt_total, mut ours_total) = (0usize, 0usize);
    for (z, x, y, name) in places {
        let Some(raw) = archive.tile(*z, *x, *y).expect("tile") else {
            println!("{name}: absent");
            continue;
        };
        let tile = Tile::decode(&raw).expect("decode");
        let (mamaps, stats) = from_mvt::from_tile(&tile).expect("convert");
        let encoded = body::serialize(&mamaps).expect("serialize");
        let points: usize = mamaps.layers.iter().map(|l| l.coords.len()).sum();
        let (mvt_z, ours_z) = (deflate(&raw), deflate(&encoded));
        println!(
            "{:<16}{:>10}{:>10}{:>10}{:>10}{:>9}{:>9}{:>8}",
            name,
            raw.len(),
            mvt_z,
            encoded.len(),
            ours_z,
            format!("{:.2}x", ours_z as f64 / mvt_z as f64),
            points,
            stats.features,
        );
        mvt_total += mvt_z;
        ours_total += ours_z;
    }
    println!(
        "\ncompressed total: mvt {mvt_total}, mamaps {ours_total} ({:.2}x)",
        ours_total as f64 / mvt_total as f64,
    );
    println!("a ratio near 1.7x means the coordinate arena has reverted to fixed-width pairs");
}
