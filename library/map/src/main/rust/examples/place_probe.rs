//! TEMPORARY. Replicates the renderer symbol pre-pass on host to debug z6
//! over-culling: which candidates enter, which place, who blocks whom.
//!
//! ```text
//! MAMAPS_URL=http://127.0.0.1:8090/usw_v2.mamaps cargo run --offline -q -p map_renderer --example place_probe
//! ```
//!
//! Delete once placement parity lands.

use map_renderer::camera::Camera;
use map_renderer::style::{self, LayerKind};
use map_renderer::tile::{geometry, placement, select};
use std::collections::{HashMap, HashSet};
use std::process::Command;
use tilecodec::mamaps::dict;
use tilecodec::mamaps::MamapsArchive;
use tilecodec::proto::{err, Result};
use tilecodec::stream::RangeReader;

fn url() -> String {
    std::env::var("MAMAPS_URL")
        .unwrap_or_else(|_| "https://data.vayunmathur.com/planet.mamaps".to_string())
}

struct CurlReader;

impl RangeReader for CurlReader {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        let url = url();
        let range = format!("{}-{}", offset, offset + length as u64 - 1);
        let out = Command::new("curl")
            .args(["-s", "-f", "--range", &range, &url])
            .output()
            .map_err(|e| tilecodec::proto::Error(format!("curl failed to start: {e}")))?;
        if !out.status.success() {
            return err(format!("curl exited {:?} for range {range}", out.status.code()));
        }
        Ok(out.stdout)
    }
}

fn main() {
    // mapcompare-ish phone viewport. Assumption noted: collision outcome
    // scales with viewport, so counts are comparative, not absolute.
    // The comparator is side-by-side (two weight(1f) panels): each map gets
    // HALF the phone width. Override with HALF=0 for a full-width run.
    let half = std::env::var("HALF").map(|v| v != "0").unwrap_or(true);
    let (w, h) = if half { (205.0, 800.0) } else { (411.0, 891.0) };
    let camera = Camera {
        center_lon: -122.4194,
        center_lat: 37.7749,
        zoom: 6.0,
        width_dp: w,
        height_dp: h,
        density: 3.0,
    };
    let extent = ((camera.width_dp * camera.density) as u32, (camera.height_dp * camera.density) as u32);
    println!("camera    z6 SF viewport {extent:?} device px");

    let mut archive = match MamapsArchive::open(CurlReader) {
        Ok(a) => a,
        Err(e) => {
            println!("archive   OPEN FAILED: {e}");
            std::process::exit(1);
        }
    };
    let (min_zoom, max_zoom) = (archive.header.min_zoom, archive.header.max_zoom);
    let layers = style::layers();

    // Mirror record_inner pre-pass exactly: draws_at + text_size gates.
    let mut candidates: Vec<placement::Candidate> = Vec::new();
    // candidate id -> (tile z/x/y, layer_index, label idx in tile.labels, name)
    let mut meta: HashMap<u64, (u8, u32, u32, usize, usize, String, u8, u16)> = HashMap::new();
    let wanted = select::resident_set(&camera, min_zoom, max_zoom);
    println!("resident  {} tiles ({} exact visible)", wanted.len(), select::visible(&camera, min_zoom, max_zoom).len());
    let mut tiles_read = 0;
    for tile in &wanted {
        let body = match archive.tile(tile.z, tile.x, tile.y) {
            Ok(Some(b)) => b,
            _ => continue,
        };
        tiles_read += 1;
        let mesh = geometry::build(&body, layers, tile.z, tile.x, tile.y, false);
        let tile_extent = body.extent as f32;
        for (index, layer) in layers.iter().enumerate() {
            if layer.kind != LayerKind::Symbol {
                continue;
            }
            if !layer.draws_at(camera.zoom.floor().clamp(0.0, 22.0) as u8) {
                continue;
            }
            let text_px = layer.text_size.at(camera.zoom);
            if text_px <= 0.0 {
                continue;
            }
            let tile_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
            // Mirror the renderer's task-9 rank gating (UI zoom: probe camera
            // is already UI zoom, no offset here).
            let min_pop = placement::locality_min_pop(camera.zoom);
            for (label_idx, label) in mesh.labels.iter().enumerate().filter(|(_, l)| l.layer_index == index) {
                if label.rank == 2 && label.pop < min_pop {
                    continue;
                }
                let id = placement::candidate_id(tile.z, tile.x, tile.y, index, label_idx);
                let rect = placement::screen_rect(label.anchor, tile_clip, extent, text_px, label.total_advance, 24.0);
                candidates.push(placement::Candidate { id, rank: label.rank, pop: label.pop, rect });
                // Resolve the display name via anchor -> point feature coords.
                let cx = (label.anchor.0 * tile_extent).round() as i16;
                let cy = (label.anchor.1 * tile_extent).round() as i16;
                let mut name = "?".to_string();
                if let Some(places) = body.layer(dict::LAYER_PLACES) {
                    for f in &places.features {
                        if f.geom_type != tilecodec::mamaps::body::GEOM_POINT {
                            continue;
                        }
                        let parts = places.parts_of(f);
                        let Some(first) = parts.first() else { continue };
                        let pts = places.points(first);
                        if pts.first() == Some(&(cx, cy)) {
                            name = body.name(f.name_idx).unwrap_or("?").to_string();
                            break;
                        }
                    }
                }
                meta.insert(id, (tile.z, tile.x, tile.y, index, label_idx, name, label.rank, label.pop));
            }
        }
    }
    println!("tiles     {tiles_read} read, {} candidates", candidates.len());
    let mut entered: HashMap<u8, usize> = HashMap::new();
    for c in &candidates {
        *entered.entry(c.rank).or_default() += 1;
    }
    let mut ranks: Vec<u8> = entered.keys().copied().collect();
    ranks.sort();
    for r in &ranks {
        println!("entered   rank {r}: {} candidates", entered[r]);
    }

    let accepted: HashSet<u64> = placement::place(&candidates).into_iter().collect();
    println!("placed    {} / {} candidates", accepted.len(), candidates.len());
    let mut placed_rank: HashMap<u8, usize> = HashMap::new();
    for c in &candidates {
        if accepted.contains(&c.id) {
            *placed_rank.entry(c.rank).or_default() += 1;
        }
    }
    for r in &ranks {
        println!("placed    rank {r}: {} / {}", placed_rank.get(r).copied().unwrap_or(0), entered[r]);
    }

    // Acceptance order for blocker attribution: re-run place to get order.
    let order = placement::place(&candidates);
    let pos: HashMap<u64, usize> = order.iter().enumerate().map(|(i, id)| (*id, i)).collect();
    // For each culled candidate, the earliest-accepted overlapping box.
    let mut by_id: HashMap<u64, &placement::Candidate> = HashMap::new();
    for c in &candidates {
        by_id.insert(c.id, c);
    }
    let overlaps = |a: (f32, f32, f32, f32), b: (f32, f32, f32, f32)| {
        a.0 < b.2 && b.0 < a.2 && a.1 < b.3 && b.1 < a.3
    };
    println!("--- culled pop>=1 localities (the ones that should survive) ---");
    let mut shown = 0;
    for c in &candidates {
        if accepted.contains(&c.id) || c.rank != 2 || c.pop < 1 {
            continue;
        }
        let (_, _, _, _, _, name, _, pop) = &meta[&c.id];
        // Earliest accepted blocker.
        let mut blocker: Option<u64> = None;
        for id in &order {
            if pos[id] >= pos.get(&c.id).copied().unwrap_or(usize::MAX)
                && accepted.contains(id)
            {
                break;
            }
            if overlaps(by_id[id].rect, c.rect) {
                blocker = Some(*id);
                break;
            }
        }
        let block_str = match blocker {
            Some(b) => {
                let (_, _, _, _, _, bname, brank, bpop) = &meta[&b];
                let r = by_id[&b].rect;
                format!("BLOCKED BY rank={brank} pop={bpop} '{bname}' box=({:.0},{:.0},{:.0},{:.0})", r.0, r.1, r.2, r.3)
            }
            None => "no overlapping accepted box (ordering artifact?)".to_string(),
        };
        println!(
            "culled    pop={pop} '{name}' box=({:.0},{:.0},{:.0},{:.0}) {block_str}",
            c.rect.0, c.rect.1, c.rect.2, c.rect.3
        );
        shown += 1;
        if shown >= 40 {
            println!("... (truncated)");
            break;
        }
    }
    println!("--- placed pop>=2 (majors that survived) ---");
    for c in &candidates {
        if !accepted.contains(&c.id) || c.pop < 2 {
            continue;
        }
        let (_, _, _, _, _, name, rank, pop) = &meta[&c.id];
        println!("placed    rank={rank} pop={pop} '{name}'");
    }
}
