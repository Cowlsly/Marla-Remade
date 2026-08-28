//! Probes the live archive end to end, through the real code the app uses.
//!
//! ```text
//! cargo run --offline -p map_renderer --example probe_archive
//! ```
//!
//! This exists because the renderer cannot be verified on a host: Vulkan needs a device.
//! Everything *before* the GPU can be, though, and that is where the bugs were — a
//! hardcoded max zoom that did not match the archive, and tiles that silently resolved to
//! nothing. So this drives the actual [`StreamArchive`], the actual gzip and MVT decoders,
//! the actual style and the actual tessellators against
//! `https://data.vayunmathur.com/v4.pmtiles`, and prints what comes out.
//!
//! HTTP is shelled out to `curl`, which ships with Windows 10+, macOS and every Linux we
//! build on. On device this same trait is implemented over `library/jni-http`; using curl
//! here keeps the crate free of an HTTP dependency that only a diagnostic needs.

use map_renderer::camera::Camera;
use map_renderer::style;
use map_renderer::tess::{fill, stroke};
use map_renderer::tile::geometry;
use map_renderer::tile::select;
use std::process::Command;
use tilecodec::mvt::Tile;
use tilecodec::proto::{err, Result};
use tilecodec::stream::{RangeReader, StreamArchive};

/// Layers whose kind values the style filters on, so a mismatch is silent.
const KIND_REPORT: &[&str] = &["landuse", "water", "landcover", "earth", "buildings"];

const URL: &str = "https://data.vayunmathur.com/v4.pmtiles";

/// A [`RangeReader`] over `curl`, counting requests and bytes so the cost of a screenful is
/// visible rather than assumed.
///
/// The counters are thread-locals because the archive takes ownership of the reader, and a
/// diagnostic is not worth threading a handle back out for.
struct CurlReader;

thread_local! {
    static REQUESTS: std::cell::Cell<u32> = const { std::cell::Cell::new(0) };
    static BYTES: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

impl RangeReader for CurlReader {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        let range = format!("{}-{}", offset, offset + length as u64 - 1);
        REQUESTS.with(|c| c.set(c.get() + 1));
        let out = match Command::new("curl").args(["-s", "-f", "--range", &range, URL]).output() {
            Ok(out) => out,
            Err(e) => return err(format!("curl failed to start: {e}")),
        };
        if !out.status.success() {
            return err(format!("curl exited {:?} for range {range}", out.status.code()));
        }
        BYTES.with(|c| c.set(c.get() + out.stdout.len() as u64));
        Ok(out.stdout)
    }
}

/// Signed area of every ring in a polygon geometry, in command-stream order, without any
/// exterior/hole grouping applied.
///
/// This deliberately re-walks the raw command stream rather than using
/// `decode_polygons`, because the grouping is exactly what is under suspicion.
fn raw_rings(geometry: &[u32]) -> Vec<f64> {
    const MOVE_TO: u32 = 1;
    const LINE_TO: u32 = 2;
    const CLOSE_PATH: u32 = 7;

    let mut areas = Vec::new();
    let mut ring: Vec<(i64, i64)> = Vec::new();
    let (mut x, mut y) = (0i64, 0i64);
    let mut i = 0;
    while i < geometry.len() {
        let cmd = geometry[i] & 0x7;
        let count = geometry[i] >> 3;
        i += 1;
        match cmd {
            MOVE_TO | LINE_TO => {
                for _ in 0..count {
                    if i + 1 >= geometry.len() {
                        return areas;
                    }
                    let dx = ((geometry[i] >> 1) as i64) ^ -((geometry[i] & 1) as i64);
                    let dy = ((geometry[i + 1] >> 1) as i64) ^ -((geometry[i + 1] & 1) as i64);
                    i += 2;
                    x += dx;
                    y += dy;
                    ring.push((x, y));
                }
            }
            CLOSE_PATH => {
                let mut sum = 0i64;
                for k in 0..ring.len() {
                    let (x1, y1) = ring[k];
                    let (x2, y2) = ring[(k + 1) % ring.len()];
                    sum += x1 * y2 - x2 * y1;
                }
                areas.push(sum as f64 / 2.0);
                ring.clear();
            }
            _ => return areas,
        }
    }
    areas
}

/// The area a correct tessellation must cover, in tile-normalised units: the even-odd
/// region of every ring, by scanline.
///
/// Not `exterior - sum(holes)`. The archive's ocean polygons have holes that overlap each
/// other and lakes nested inside continent holes, so subtracting each ring independently
/// double-counts both and reads low — 0.7096 against a true 0.7135 on the z0 ocean, which
/// flatters the ratios below by about half a percent.
fn even_odd_area(rings: &[Vec<(i32, i32)>], extent: u32) -> f64 {
    let mut edges: Vec<((i32, i32), (i32, i32))> = Vec::new();
    for ring in rings {
        let mut n = ring.len();
        while n >= 2 && ring[0] == ring[n - 1] {
            n -= 1;
        }
        if n < 3 {
            continue;
        }
        for i in 0..n {
            let (a, b) = (ring[i], ring[(i + 1) % n]);
            if a.1 != b.1 {
                edges.push((a, b));
            }
        }
    }
    if edges.is_empty() {
        return 0.0;
    }
    let lo = edges.iter().map(|e| e.0 .1.min(e.1 .1)).min().unwrap();
    let hi = edges.iter().map(|e| e.0 .1.max(e.1 .1)).max().unwrap();

    let mut crossings: Vec<f64> = Vec::new();
    let mut total = 0.0f64;
    for row in lo..hi {
        let y = row as f64 + 0.5;
        crossings.clear();
        for &((x1, y1), (x2, y2)) in &edges {
            if (y1 as f64 > y) != (y2 as f64 > y) {
                let t = (y - y1 as f64) / (y2 as f64 - y1 as f64);
                crossings.push(x1 as f64 + t * (x2 as f64 - x1 as f64));
            }
        }
        crossings.sort_by(|a, b| a.partial_cmp(b).unwrap());
        let mut i = 0;
        while i + 1 < crossings.len() {
            total += crossings[i + 1] - crossings[i];
            i += 2;
        }
    }
    let scale = 1.0 / extent as f64;
    total * scale * scale
}

fn main() {
    let reader = CurlReader;
    let mut archive = match StreamArchive::open(reader) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("could not open the archive: {e}");
            std::process::exit(1);
        }
    };

    let header = &archive.header;
    println!("archive     zoom {}..{}, {} addressed tiles", header.min_zoom, header.max_zoom, header.addressed_tiles);
    println!("            tile_data at {}, leaves at {}", header.tile_data_offset, header.leaf_offset);
    let (min_zoom, max_zoom) = (header.min_zoom, header.max_zoom);

    // Match the device exactly: findfamily sits at null island, z1.87, on a 448x867dp
    // viewport, because it has no location fix to centre on.
    let camera = Camera {
        center_lon: 0.0,
        center_lat: 0.0,
        zoom: 1.87,
        width_dp: 448.0,
        height_dp: 867.0,
        density: 3.0,
    };
    let layers = style::layers();
    let wanted = select::resident_set(&camera, min_zoom, max_zoom);
    println!("viewport    z14 SF, {} tiles including ancestors\n", wanted.len());

    let mut drew = 0;
    let mut empty = 0;
    for tile in &wanted {
        let body = match archive.tile(tile.z, tile.x, tile.y) {
            Ok(Some(b)) => b,
            Ok(None) => {
                println!("  z{}/{}/{}  ABSENT from the archive", tile.z, tile.x, tile.y);
                empty += 1;
                continue;
            }
            Err(e) => {
                println!("  z{}/{}/{}  ERROR {e}", tile.z, tile.x, tile.y);
                empty += 1;
                continue;
            }
        };
        let decoded = match Tile::decode(&body) {
            Ok(t) => t,
            Err(e) => {
                println!("  z{}/{}/{}  MVT DECODE FAILED: {e}", tile.z, tile.x, tile.y);
                empty += 1;
                continue;
            }
        };
        // Ring structure of the water layer. The ocean is a polygon with continents cut out
        // as holes; if those holes are lost, the ocean paints over the land and the map is a
        // flat sheet of blue with land showing only where a later layer happens to cover it.
        if tile.z <= 1 {
            for layer in &decoded.layers {
                if layer.name != "water" {
                    continue;
                }
                let mut reported = 0;
                for feature in &layer.features {
                    if feature.geom_type != tilecodec::mvt::GeomType::Polygon {
                        continue;
                    }
                    let raw = raw_rings(&feature.geometry);
                    let grouped = tilecodec::mvt::decode_polygons(&feature.geometry);
                    let polygons = grouped.as_ref().map(|p| p.len()).unwrap_or(0);
                    let holes: usize =
                        grouped.as_ref().map(|p| p.iter().map(|r| r.len() - 1).sum()).unwrap_or(0);
                    let kind = feature
                        .get("kind")
                        .and_then(|v| match v {
                            tilecodec::mvt::Value::String(s) => Some(s.clone()),
                            _ => None,
                        })
                        .unwrap_or_default();
                    if raw.len() > 1 || kind == "ocean" {
                        // Per polygon group, not just the total. If holes are assigned to the
                        // wrong exterior, one group over-covers while another under-covers and
                        // the totals partly cancel — so an aggregate number hides it.
                        let mut detail = String::new();
                        let mut worst = 1.0f64;
                        if let Some(polygons) = &grouped {
                            for (g, rings) in polygons.iter().enumerate() {
                                let want = even_odd_area(rings, layer.extent);
                                let mut v = Vec::new();
                                let mut idx = Vec::new();
                                fill::tessellate(rings, layer.extent, &mut v, &mut idx);
                                let mut got = 0.0f64;
                                for tri in idx.chunks(3) {
                                    let p = |i: u32| {
                                        let b = i as usize * fill::FLOATS_PER_VERTEX;
                                        (v[b] as f64, v[b + 1] as f64)
                                    };
                                    let (ax, ay) = p(tri[0]);
                                    let (bx, by) = p(tri[1]);
                                    let (cx, cy) = p(tri[2]);
                                    got += ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)).abs()
                                        / 2.0;
                                }
                                let ratio = if want > 1e-9 { got / want } else { 1.0 };
                                // Distance from 1, so a group that under-covers is as
                                // visible as one that over-covers.
                                if (ratio - 1.0).abs() > (worst - 1.0).abs() {
                                    worst = ratio;
                                }
                                detail.push_str(&format!(
                                    "\n            group {g}: {} rings, want {want:.4}, got \
                                     {got:.4}, ratio {ratio:.3}",
                                    rings.len(),
                                ));
                            }
                        }
                        println!(
                            "        water/{kind}: {} rings -> {} polys, {} holes | worst group \
                             ratio {worst:.3}{}",
                            raw.len(),
                            polygons,
                            holes,
                            detail,
                        );
                        reported += 1;
                        if reported >= 2 {
                            break;
                        }
                    }
                }
            }
        }
        // Save the z0 tile so the hard real geometry becomes a test fixture. A synthetic grid
        // of square holes tessellates perfectly; real coastlines clipped at a tile edge, with
        // collinear runs and holes touching the exterior, do not. Guessing at a synthetic case
        // that reproduces that is slower than using the tile itself.
        if tile.z == 0 && std::env::var("DUMP_Z0").is_ok() {
            let path = "library/tilecodec/src/main/rust/tests/fixtures/v4_z0_tile.mvt";
            match std::fs::write(path, &body) {
                Ok(()) => println!("      wrote {} ({} bytes)", path, body.len()),
                Err(e) => println!("      could not write {path}: {e}"),
            }
        }
        let names: Vec<&str> = decoded.layers.iter().map(|l| l.name.as_str()).collect();
        // Converted rather than read: this probe reads the published `v4.pmtiles`, and the
        // renderer now takes a `.mamaps` body. The MVT analysis above stays MVT, because it is
        // about the upstream archive's own ring structure.
        let (converted, _) = tilecodec::mamaps::from_mvt::from_tile(&decoded).expect("converts");
        let mesh = geometry::build(&converted, &layers, tile.z, tile.x, tile.y);
        let triangles: usize = mesh.meshes.iter().map(|m| m.indices.len() / 3).sum();

        // Per-layer detail, plus how much of the tile each fill actually covers. A layer
        // whose fill covers far less than the polygons it was given is dropping geometry,
        // which on screen is ocean showing through the middle of a continent.
        for m in &mesh.meshes {
            let id = &layers[m.layer_index].id;
            let stride = match m.kind {
                style::LayerKind::Fill => fill::FLOATS_PER_VERTEX,
                style::LayerKind::Line => stroke::FLOATS_PER_VERTEX,
            };
            let mut covered = 0.0f64;
            if m.kind == style::LayerKind::Fill {
                for tri in m.indices.chunks(3) {
                    let p = |i: u32| {
                        let b = i as usize * stride;
                        (m.vertices[b] as f64, m.vertices[b + 1] as f64)
                    };
                    let (ax, ay) = p(tri[0]);
                    let (bx, by) = p(tri[1]);
                    let (cx, cy) = p(tri[2]);
                    covered += ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)).abs() / 2.0;
                }
            }
            println!(
                "        {:<22} {:>6} tris  covers {:>6.3} of the tile",
                id,
                m.indices.len() / 3,
                covered,
            );
        }

        // The bounds the vertex shaders assume. A violation here is exactly the class of
        // bug that renders a recognisable map that is badly wrong.
        let mut worst = 0.0f32;
        let mut nonfinite = 0;
        for m in &mesh.meshes {
            let stride = match m.kind {
                style::LayerKind::Fill => fill::FLOATS_PER_VERTEX,
                style::LayerKind::Line => stroke::FLOATS_PER_VERTEX,
            };
            for chunk in m.vertices.chunks(stride) {
                if !chunk[0].is_finite() || !chunk[1].is_finite() {
                    nonfinite += 1;
                    continue;
                }
                worst = worst.max(chunk[0].abs()).max(chunk[1].abs());
            }
        }

        println!(
            "  z{}/{}/{}  {:>6} B  layers[{}]  {} drawn, {} tris, |pos|<={:.3}{}",
            tile.z,
            tile.x,
            tile.y,
            body.len(),
            names.join(","),
            mesh.meshes.len(),
            triangles,
            worst,
            if nonfinite > 0 { format!("  !! {nonfinite} NON-FINITE") } else { String::new() },
        );
        if triangles > 0 {
            drew += 1;
        }
    }

    println!(
        "\n{drew} tiles produced geometry, {empty} produced none, \
         {} range requests, {:.1} KB fetched",
        REQUESTS.with(|c| c.get()),
        BYTES.with(|c| c.get()) as f64 / 1024.0,
    );
    if drew == 0 {
        eprintln!("\nnothing drew: the map would be blank");
        std::process::exit(1);
    }
}
