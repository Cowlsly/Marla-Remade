//! TEMPORARY diagnostic for the "landmass missing at low zoom" report. Delete after measuring.
//!
//! ```text
//! cargo run --offline -q -p map_renderer --example zoom_sweep
//! GRID=512 cargo run --offline -q -p map_renderer --example zoom_sweep
//! ```
//!
//! Sweeps the camera across the archive's whole zoom range at three places, and reports per
//! zoom what no existing probe measures.
//!
//! # Which columns to trust
//!
//! **`DROPD` and the absent-tile lines are sound.** `decode_polygons` returns `Option` and
//! `geometry::build` silently skips the feature on `None`, so a whole continent can vanish
//! there with every area-conservation check still passing - `loss_probe` cannot see it at all,
//! because it `continue`s on `None` too. Measured: zero, at every zoom, for `earth` and
//! `water`, at all three places, with no tile absent. That is a real result.
//!
//! **The `earth`/`water`/`VISIBLE`/`kept` columns are NOT trustworthy, and this is recorded
//! here so nobody builds on them.** They rasterise each layer to a grid and composite water
//! over earth, which sounds like it measures the thing the user reported. It does not: along
//! every coastline a cell can fall inside both layers, so the apparent overlap scales with
//! coastline length times cell size - and coastline length per tile is largest at exactly the
//! low zooms under suspicion. So the metric manufactures a clean monotonic "land is eaten at
//! low zoom" trend out of nothing but its own resolution. Quadrupling `GRID` from 128 to 512
//! moved z0 `kept` from 0.461 to 0.686, which is the tell. Use `GRID` to re-check any
//! conclusion drawn from these columns before believing it.
//!
//! The quantisation-free way to ask this question is the even-odd scanline integral already in
//! `fill.rs`'s tests and in `probe_archive`, which puts both layers within 0.5% of their true
//! area at z0 with zero area lost.

use map_renderer::camera::Camera;
use map_renderer::tess::fill;
use map_renderer::tile::select;
use std::process::Command;
use tilecodec::mvt::{GeomType, Tile};
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

/// Side of the coverage GRID() laid over one tile.
static GRID_CELLS: std::sync::OnceLock<usize> = std::sync::OnceLock::new();
#[allow(non_snake_case)]
fn GRID() -> usize {
    *GRID_CELLS.get_or_init(|| {
        std::env::var("GRID()").ok().and_then(|v| v.parse().ok()).unwrap_or(128)
    })
}

/// Rasterise the emitted triangles into a coverage mask over the tile's own 0..1 box.
///
/// Area sums cannot answer this question: a tile that is wholly land carries one ring that
/// spans the tile *plus its buffer*, so every corner of every triangle sits outside 0..1 and
/// any "is this triangle outside the tile" test misfiles it as overspill. Rasterising asks
/// the only question that matters instead - which parts of the tile end up painted - and it
/// composites layers in draw order the way the GPU does.
fn rasterise(vertices: &[f32], indices: &[u32], mask: &mut [bool]) {
    for tri in indices.chunks_exact(3) {
        let p = |i: u32| {
            let b = i as usize * fill::FLOATS_PER_VERTEX;
            (vertices[b] as f64, vertices[b + 1] as f64)
        };
        let (ax, ay) = p(tri[0]);
        let (bx, by) = p(tri[1]);
        let (cx, cy) = p(tri[2]);

        // Only the part of the triangle that lands on the tile can be painted.
        let lo_x = ax.min(bx).min(cx).max(0.0);
        let hi_x = ax.max(bx).max(cx).min(1.0);
        let lo_y = ay.min(by).min(cy).max(0.0);
        let hi_y = ay.max(by).max(cy).min(1.0);
        if lo_x > hi_x || lo_y > hi_y {
            continue;
        }
        let area2 = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
        if area2.abs() < 1e-18 {
            continue;
        }

        let col0 = (lo_x * GRID() as f64).floor().max(0.0) as usize;
        let col1 = ((hi_x * GRID() as f64).ceil() as usize).min(GRID());
        let row0 = (lo_y * GRID() as f64).floor().max(0.0) as usize;
        let row1 = ((hi_y * GRID() as f64).ceil() as usize).min(GRID());
        for row in row0..row1 {
            let y = (row as f64 + 0.5) / GRID() as f64;
            for col in col0..col1 {
                let x = (col as f64 + 0.5) / GRID() as f64;
                // Barycentric sign test, normalised so winding does not matter.
                let w0 = ((bx - ax) * (y - ay) - (x - ax) * (by - ay)) / area2;
                let w1 = ((cx - bx) * (y - by) - (x - bx) * (cy - by)) / area2;
                let w2 = ((ax - cx) * (y - cy) - (x - cx) * (ay - cy)) / area2;
                if w0 >= 0.0 && w1 >= 0.0 && w2 >= 0.0 {
                    mask[row * GRID() + col] = true;
                }
            }
        }
    }
}

fn main() {
    let mut archive = match StreamArchive::open(CurlReader) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("could not open the archive: {e}");
            std::process::exit(1);
        }
    };
    let (min_zoom, max_zoom) = (archive.header.min_zoom, archive.header.max_zoom);
    println!("archive zoom {min_zoom}..{max_zoom}\n");

    println!(
        "{:<5} {:<9} {:>5} {:>6} {:>8} {:>8} {:>8} {:>6}",
        "zoom", "place", "tiles", "DROPD", "earth", "water", "VISIBLE", "kept",
    );

    // A spread of places rather than one, so a single odd tile cannot carry the conclusion:
    // mid-Atlantic (mostly ocean with coasts), Europe, and the Americas.
    let places: [(f64, f64, &str); 3] =
        [(0.0, 20.0, "atlantic"), (10.0, 50.0, "europe"), (-80.0, 10.0, "americas")];

    for zoom in [0.0f64, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0] {
        for (lon, lat, place) in places {
            let camera = Camera {
                center_lon: lon,
                center_lat: lat,
                zoom,
                width_dp: 448.0,
                height_dp: 867.0,
                density: 3.0,
            };
            let tiles = select::visible(&camera, min_zoom, max_zoom);
            let mut absent = 0;
            let mut dropped = 0usize;
            // Summed over tiles, in units of one tile, so they read as fractions of a tile.
            let (mut earth_sum, mut water_sum, mut visible_sum) = (0.0f64, 0.0f64, 0.0f64);
            let mut counted = 0usize;

            for tile in &tiles {
                let body = match archive.tile(tile.z, tile.x, tile.y) {
                    Ok(Some(b)) => b,
                    _ => {
                        absent += 1;
                        continue;
                    }
                };
                let Ok(decoded) = Tile::decode(&body) else {
                    absent += 1;
                    continue;
                };

                let mut earth = vec![false; GRID() * GRID()];
                let mut water = vec![false; GRID() * GRID()];
                for source in &decoded.layers {
                    let mask = match source.name.as_str() {
                        "earth" => &mut earth,
                        "water" => &mut water,
                        _ => continue,
                    };
                    for feature in &source.features {
                        if feature.geom_type != GeomType::Polygon {
                            continue;
                        }
                        let Some(polygons) = tilecodec::mvt::decode_polygons(&feature.geometry)
                        else {
                            // Exactly the path `geometry::build` takes silently.
                            dropped += 1;
                            continue;
                        };
                        for rings in &polygons {
                            let mut v = Vec::new();
                            let mut idx = Vec::new();
                            fill::tessellate(rings, source.extent, false, &mut v, &mut idx);
                            rasterise(&v, &idx, mask);
                        }
                    }
                }

                // Water is drawn over earth, so land is only visible where earth is painted
                // and water is not.
                let cells = (GRID() * GRID()) as f64;
                earth_sum += earth.iter().filter(|c| **c).count() as f64 / cells;
                water_sum += water.iter().filter(|c| **c).count() as f64 / cells;
                visible_sum += earth
                    .iter()
                    .zip(&water)
                    .filter(|(e, w)| **e && !**w)
                    .count() as f64
                    / cells;
                counted += 1;
            }

            if counted == 0 {
                continue;
            }
            let n = counted as f64;
            let earth = earth_sum / n;
            let visible = visible_sum / n;
            // How much of the land the tile's own `earth` layer describes still reads as land
            // once `water` has been drawn over it. Water and earth should very nearly
            // complement each other, so this should sit close to 1; well below it means the
            // ocean is painted over ground the same tile calls land.
            let kept = if earth > 1e-6 { visible / earth } else { 1.0 };
            println!(
                "z{:<4} {:<9} {:>5} {:>6} {:>8.3} {:>8.3} {:>8.3} {:>6.3}{}",
                zoom as u32,
                place,
                counted,
                dropped,
                earth,
                water_sum / n,
                visible,
                kept,
                if dropped > 0 { "  <<< GEOMETRY DISCARDED" } else { "" },
            );
            if absent > 0 {
                println!("z{:<4} {place:<9} {absent} of {} tiles absent", zoom as u32, tiles.len());
            }
        }
    }
}
