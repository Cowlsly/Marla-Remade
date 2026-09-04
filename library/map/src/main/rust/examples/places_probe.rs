//! TEMPORARY. Answers: does live planet.mamaps carry named places points, and
//! what does the symbol pipeline do with them? Delete once the root cause lands.
//!
//! ```text
//! cargo run --offline -q -p map_renderer --example places_probe
//! ```
//!
//! Code-first evidence for the invisible-SDF-text investigation: atlas stats,
//! text-size ramp values at z6/z10, archive header/dict, and per-tile places
//! point counts with names through the real `geometry::build` path.

use map_renderer::camera;
use map_renderer::style;
use map_renderer::tile::geometry;
use map_renderer::tile::glyph;
use std::process::Command;
use tilecodec::mamaps::dict;
use tilecodec::mamaps::MamapsArchive;
use tilecodec::proto::{err, Result};
use tilecodec::stream::RangeReader;

const DEFAULT_URL: &str = "https://data.vayunmathur.com/planet.mamaps";

/// Override with `MAMAPS_URL=http://127.0.0.1:8089/na.mamaps` to probe a local
/// archive (e.g. the ones the emulator reads) instead of prod planet.mamaps.
fn url() -> String {
    std::env::var("MAMAPS_URL").unwrap_or_else(|_| DEFAULT_URL.to_string())
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

/// Tile covering `lon`/`lat` at integer zoom `z`: world-px divided by the
/// 256px tile span.
fn tile_for(lon: f64, lat: f64, z: u8) -> (u32, u32) {
    let p = camera::project(lon, lat, z as f64);
    ((p.x / 256.0).floor() as u32, (p.y / 256.0).floor() as u32)
}

fn main() {
    // (b) Atlas contents on host: dimensions + nonzero-coverage stats.
    let atlas = glyph::GlyphAtlas::build();
    let min = *atlas.pixels.iter().min().unwrap();
    let max = *atlas.pixels.iter().max().unwrap();
    let mid = atlas.pixels.iter().filter(|&&v| (64..=192).contains(&v)).count();
    let zero = atlas.pixels.iter().filter(|&&v| v == 0).count();
    println!(
        "atlas     {}x{} ({} bytes) min={} max={} mid-band={} zero={} fonts_staged={}",
        glyph::ATLAS_PX,
        glyph::ATLAS_PX,
        atlas.pixels.len(),
        min,
        max,
        mid,
        zero,
        glyph::fonts_staged(),
    );

    // (c) Size-ramp output in px at z6 and z10 for the four symbol layers.
    println!("ramp      layer                  size@z6  size@z10  opacity@z6  light      halo");
    for layer in style::layers().iter().filter(|l| l.kind == style::LayerKind::Symbol) {
        println!(
            "ramp      {:<22} {:>7.2} {:>8.2}  {:>9.2}  #{:08X} #{:08X}",
            layer.id,
            layer.text_size.at(6.0),
            layer.text_size.at(10.0),
            layer.opacity_at(6.0),
            layer.light,
            layer.halo_light,
        );
    }
    println!("bg        light=#{:08X} dark=#{:08X}", style::background(style::Variant::Light), style::background(style::Variant::Dark));

    // The live archive the device reads. Non-fatal: the hardcoded isolation
    // below must run even when the archive refuses to open (that refusal is
    // itself the leading data-side suspect — see the task-1 report).
    let archive_opt = match MamapsArchive::open(CurlReader) {
        Ok(a) => Some(a),
        Err(e) => {
            println!("archive   OPEN FAILED: {e}");
            None
        }
    };
    let layers = style::layers();
    if let Some(mut archive) = archive_opt {
    let h = &archive.header;
    let (min_zoom, max_zoom, layer_count, addressed, bodies, build_id, compressed, rings_v) =
        (h.min_zoom, h.max_zoom, h.layer_count, h.tiles_addressed, h.bodies_written, h.build_id, h.compressed(), h.rings_validated());
    println!(
        "archive   zoom {}..{} layers={} addressed={} bodies={} build_id={:#x} compressed={} rings_validated={}",
        min_zoom, max_zoom, layer_count, addressed, bodies, build_id, compressed, rings_v,
    );
    let layer_count = archive.header.layer_count;
    let names: Vec<&str> =
        (0..layer_count).filter_map(|id| archive.dictionary.layer_name(id)).collect();
    println!("archive   dict layers: {}", names.join(","));

    // World tile (countries at z0), SF at z6/z10 (localities), null-island z1.
    let (sfx6, sfy6) = tile_for(-122.4194, 37.7749, 6);
    let (sfx10, sfy10) = tile_for(-122.4194, 37.7749, 10);
    let targets: [(u8, u32, u32, &str); 5] = [
        (0, 0, 0, "world"),
        (1, 0, 1, "null-island-z1"),
        (6, sfx6, sfy6, "sf-z6"),
        (10, sfx10, sfy10, "sf-z10"),
        (3, 1, 3, "europe-z3"),
    ];
    for (z, x, y, what) in targets {
        let body = match archive.tile(z, x, y) {
            Ok(Some(b)) => b,
            Ok(None) => {
                println!("tile      z{z}/{x}/{y} ({what}): ABSENT");
                continue;
            }
            Err(e) => {
                println!("tile      z{z}/{x}/{y} ({what}): ERROR {e}");
                continue;
            }
        };
        let layer_names: Vec<&str> = body
            .layers
            .iter()
            .filter_map(|l| archive.dictionary.layer_name(l.layer_id))
            .collect();
        let places = body.layer(dict::LAYER_PLACES);
        let (points, named, mut samples) = match places {
            Some(layer) => {
                let mut points = 0usize;
                let mut named = 0usize;
                let mut samples = Vec::new();
                for f in &layer.features {
                    if f.geom_type != tilecodec::mamaps::body::GEOM_POINT {
                        continue;
                    }
                    points += 1;
                    if let Some(name) = body.name(f.name_idx) {
                        named += 1;
                        if samples.len() < 3 {
                            let kind = archive
                                .dictionary
                                .kind_name(f.kind)
                                .unwrap_or("?");
                            samples.push(format!("{kind}={name}"));
                        }
                    }
                }
                (points, named, samples)
            }
            None => (0, 0, Vec::new()),
        };
        // Through the real tessellation path: how many labels shape?
        // NOTE: `false` for rings_validated is deliberate and symbol-scoped: the
        // flag only gates fill repair (another team's assert fired on a real
        // tile under `true`); label shaping is independent of it.
        let mesh = geometry::build(&body, layers, z, x, y, false);
        let per_layer: Vec<String> = {
            let mut counts = std::collections::BTreeMap::new();
            for label in &mesh.labels {
                *counts.entry(layers[label.layer_index].id.clone()).or_insert(0usize) += 1;
            }
            counts.into_iter().map(|(id, n)| format!("{id}:{n}")).collect()
        };
        // What would emit at this zoom: text_size per symbol layer.
        let mut sizes = Vec::new();
        for layer in layers.iter().filter(|l| l.kind == style::LayerKind::Symbol) {
            sizes.push(format!("{}={:.1}px", layer.id, layer.text_size.at(z as f64)));
        }
        println!(
            "tile      z{z}/{x}/{y} ({what}): layers=[{}] places_points={} named={} samples=[{}]",
            layer_names.join(","),
            points,
            named,
            samples.join(" | "),
        );
        let _ = &mut samples;
        println!(
            "tile      z{z}/{x}/{y} ({what}): shaped_labels={} [{}]; text_size@[z{z}] {}",
            mesh.labels.len(),
            per_layer.join(" "),
            sizes.join(" "),
        );
        // Emit one label's quads at the frame size to prove emit produces geometry.
        if let Some(label) = mesh.labels.first() {
            let layer = &layers[label.layer_index];
            let text_px = layer.text_size.at(z as f64);
            if text_px > 0.0 {
                let (mut v, mut idx) = (Vec::new(), Vec::new());
                map_renderer::tile::symbol::emit_label(label, text_px, 256.0, &mut v, &mut idx);
                let xs: Vec<f32> =
                    v.chunks(4).map(|c| c[0]).collect();
                let (lo, hi) = (
                    xs.iter().cloned().fold(f32::INFINITY, f32::min),
                    xs.iter().cloned().fold(f32::NEG_INFINITY, f32::max),
                );
                println!(
                    "tile      z{z}/{x}/{y} ({what}): emit '{}' -> {} verts {} idx, x in [{lo:.4},{hi:.4}]",
                    body.name(
                        body.layer(dict::LAYER_PLACES)
                            .and_then(|l| l.features.iter().find_map(|f| {
                                if f.geom_type == tilecodec::mamaps::body::GEOM_POINT {
                                    body.name(f.name_idx)
                                } else {
                                    None
                                }
                            }))
                            .map(|_| 1u16)
                            .unwrap_or(0)
                    )
                    .unwrap_or("?"),
                    v.len() / 4,
                    idx.len(),
                );
            }
        }
    } // end `for` over target tiles.
    } // end if let Some(archive): per-tile live-archive section.

    // Shape a synthetic label to isolate emit from tile data (placement bypass).
    let (shaped, total) =
        map_renderer::tess::text::shape(&atlas, glyph::Weight::Regular, "San Francisco");
    let (mut v, mut idx) = (Vec::new(), Vec::new());
    map_renderer::tess::text::emit(&atlas, glyph::Weight::Regular, &shaped, total, (0.5, 0.5), 14.0, 256.0, false, &mut v, &mut idx);
    println!(
        "synthetic 'San Francisco' @14px/256px: {} glyphs -> {} verts, {} idx",
        shaped.len(),
        v.len() / 4,
        idx.len()
    );

    // (a) TEMPORARY hardcoded-label isolation: a synthetic v2 body with one named
    // GEOM_POINT through the REAL geometry::build + emit_label path, bypassing
    // archive/placement entirely. If this yields sane quads, atlas+shape+emit
    // are exonerated and the fault is upstream (no named points reach the tiler
    // output) or in the GPU submit (upload/bind/blend on device).
    {
        use tilecodec::mamaps::body::{Feature, Layer, Part, GEOM_POINT, WINDING_OUTER};
        let country_kind =
            dict::KINDS.iter().position(|k| *k == "country").map(|i| i as u16 + 1).unwrap();
        let body = tilecodec::mamaps::Body {
            extent: 4096,
            layers: vec![Layer {
                layer_id: dict::LAYER_PLACES,
                features: vec![Feature {
                    kind: country_kind,
                    kind_detail: 0,
                    geom_type: GEOM_POINT,
                    flags: 0,
                    name_idx: 1,
                    parts_offset: 0,
                    part_count: 1,
                    transit_color: 0,
                }],
                parts: vec![Part { coord_start: 0, point_count: 1, winding: WINDING_OUTER }],
                coords: vec![(2048, 2048)],
            }],
            names: vec!["Testland".to_string()],
        };
        let mesh = geometry::build(&body, layers, 6, 32, 40, false);
        println!(
            "hardcode  synthetic v2 body -> {} shaped label(s) {}",
            mesh.labels.len(),
            if mesh.labels.len() == 1 { "(shape_label OK)" } else { "(SHAPE FAILED)" },
        );
        if let Some(label) = mesh.labels.first() {
            let layer = &layers[label.layer_index];
            let text_px = layer.text_size.at(6.0);
            let (mut v, mut idx) = (Vec::new(), Vec::new());
            map_renderer::tile::symbol::emit_label(label, text_px, 256.0, &mut v, &mut idx);
            let (mut x0, mut x1, mut y0, mut y1) =
                (f32::INFINITY, f32::NEG_INFINITY, f32::INFINITY, f32::NEG_INFINITY);
            for c in v.chunks(4) {
                x0 = x0.min(c[0]);
                x1 = x1.max(c[0]);
                y0 = y0.min(c[1]);
                y1 = y1.max(c[1]);
            }
            println!(
                "hardcode  emit '{}' @ {:.1}px: {} verts {} idx, tile box x[{x0:.4},{x1:.4}] y[{y0:.4},{y1:.4}]",
                body.name(1).unwrap_or("?"),
                text_px,
                v.len() / 4,
                idx.len(),
            );
            // Frag-shader simulation on the real atlas bytes: does the SDF edge
            // test in symbol.frag yield alpha~1 inside a glyph?
            let cell = |ch: char| {
                atlas.uv(glyph::Weight::Medium, ch).expect("uv")
            };
            let uv = cell('T');
            let texel = 1.0 / glyph::ATLAS_PX as f32;
            let sample = |u: f32, vv: f32| -> f32 {
                let x = (u * glyph::ATLAS_PX as f32).floor().clamp(0.0, 1023.0) as usize;
                let y = (vv * glyph::ATLAS_PX as f32).floor().clamp(0.0, 1023.0) as usize;
                atlas.pixels[y * glyph::ATLAS_PX as usize + x] as f32 / 255.0
            };
            // Grid over the 'T' cell: count texels where frag face alpha > 0.5.
            let (mut inside, mut total_px) = (0usize, 0usize);
            let n = 16;
            for j in 0..n {
                for i in 0..n {
                    let u = uv.u0 + (uv.u1 - uv.u0) * (i as f32 + 0.5) / n as f32;
                    let vv = uv.v0 + (uv.v1 - uv.v0) * (j as f32 + 0.5) / n as f32;
                    let dist = sample(u, vv);
                    let edge0 = 0.5 - texel;
                    let edge1 = 0.5 + texel;
                    // smoothstep(edge0, edge1, dist) on CPU.
                    let t = ((dist - edge0) / (edge1 - edge0)).clamp(0.0, 1.0);
                    let face = t * t * (3.0 - 2.0 * t);
                    total_px += 1;
                    if face > 0.5 {
                        inside += 1;
                    }
                }
            }
            println!(
                "hardcode  frag-sim 'T' cell: {inside}/{total_px} texels face>0.5 (texel={texel:.6}) {}",
                if inside > 0 { "(shader edge test FIRES)" } else { "(SHADER WOULD EMIT NOTHING)" },
            );
        }
    }
} // main (closes the hardcoded-label isolation; the live-archive `if let` above is closed at its own end marker)
