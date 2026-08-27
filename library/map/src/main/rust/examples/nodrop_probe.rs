//! TEMPORARY. Answers one question: what does "keep the nesting regrouping but STOP DROPPING
//! rings" actually cost? Delete once lead has decided.
//!
//! ```text
//! cargo run --offline -q -p map_renderer --example nodrop_probe
//! ```
//!
//! The proposal on the table is to follow MapLibre and stop discarding rings for validity,
//! on the principle that a discarded ring loses real map content. The principle is right -
//! MapLibre's fill path (`src/mln/gfx/fill_generator.cpp:113-125`) is `classifyRings` ->
//! `limitHoles(_, 500)` -> `earcut`, with no validity repair at all - but the principle does
//! not say what the change costs *here*, and the archive's geometry is invalid enough that
//! the cost is not guessable. So this measures three arms against the same exact target
//! rather than arguing from any of them:
//!
//! * **maplibre** - every ring bridged into one earcut call, no regrouping, no drops. This is
//!   both what MapLibre does and what this code did before the sanitiser landed.
//! * **current** - nesting regroup, then drop a hole that straddles its exterior or overlaps a
//!   hole already taken.
//! * **nodrop** - nesting regroup, keep every ring. The proposal.
//!
//! Target is the even-odd region of the rings by scanline, which is what a rasteriser paints
//! and is quantisation-free - not `exterior - sum(holes)`, which double-counts the archive's
//! overlapping holes and reads low.

use map_renderer::tess::earcut;
use map_renderer::tess::fill;

/// Total triangle area from the REAL production tessellator, in tile-normalised units.
///
/// This exists so the `current` arm cannot go stale. `group_rings` below is a copy of
/// `fill::polygons`, and a copy silently stops matching the moment someone edits the original
/// - which happened repeatedly while this was being written. Comparing against the real
/// `fill::tessellate` on every run makes that drift impossible to miss instead of something
/// you have to remember to re-check.
fn production_area(rings: &[Vec<(i32, i32)>], extent: u32) -> f64 {
    let mut v = Vec::new();
    let mut idx = Vec::new();
    fill::tessellate(rings, extent, &mut v, &mut idx);
    let mut total = 0.0f64;
    for tri in idx.chunks_exact(3) {
        let p = |i: u32| {
            let b = i as usize * fill::FLOATS_PER_VERTEX;
            (v[b] as f64, v[b + 1] as f64)
        };
        let (ax, ay) = p(tri[0]);
        let (bx, by) = p(tri[1]);
        let (cx, cy) = p(tri[2]);
        total += ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)).abs() / 2.0;
    }
    total
}

/// Twice the unsigned area of a ring.
fn ring_area2(ring: &[(i32, i32)]) -> i64 {
    let mut sum = 0i64;
    for i in 0..ring.len() {
        let (x1, y1) = ring[i];
        let (x2, y2) = ring[(i + 1) % ring.len()];
        sum += x1 as i64 * y2 as i64 - x2 as i64 * y1 as i64;
    }
    sum.abs()
}

fn bounds(ring: &[(i32, i32)]) -> (i32, i32, i32, i32) {
    let mut b = (ring[0].0, ring[0].1, ring[0].0, ring[0].1);
    for &(x, y) in &ring[1..] {
        b.0 = b.0.min(x);
        b.1 = b.1.min(y);
        b.2 = b.2.max(x);
        b.3 = b.3.max(y);
    }
    b
}

fn boxes_overlap(a: (i32, i32, i32, i32), b: (i32, i32, i32, i32)) -> bool {
    a.0 <= b.2 && a.2 >= b.0 && a.1 <= b.3 && a.3 >= b.1
}

fn box_area(b: (i32, i32, i32, i32)) -> i64 {
    (b.2 as i64 - b.0 as i64) * (b.3 as i64 - b.1 as i64)
}

/// Even-odd point-in-ring, integer cross product, as `fill.rs` does it.
fn point_in_ring(x: i32, y: i32, ring: &[(i32, i32)]) -> bool {
    let mut inside = false;
    for i in 0..ring.len() {
        let (x1, y1) = ring[i];
        let (x2, y2) = ring[(i + 1) % ring.len()];
        if (y1 > y) != (y2 > y) {
            let dy = y2 as i64 - y1 as i64;
            let along = (x as i64 - x1 as i64) * dy;
            let at = (y as i64 - y1 as i64) * (x2 as i64 - x1 as i64);
            if if dy > 0 { along < at } else { along > at } {
                inside = !inside;
            }
        }
    }
    inside
}

/// Is the point exactly on one of the ring's edges?
///
/// `point_in_ring` is an even-odd crossing test, so it answers inconsistently for a point that
/// lies *on* the boundary. Real coastline holes touch their exterior, so `fill.rs` resolves
/// that deliberately rather than leaving it to the crossing parity, and this copy has to do
/// the same or the `current` arm stops describing the renderer.
fn on_boundary(x: i32, y: i32, ring: &[(i32, i32)]) -> bool {
    for i in 0..ring.len() {
        let (x1, y1) = ring[i];
        let (x2, y2) = ring[(i + 1) % ring.len()];
        let cross = (x as i64 - x1 as i64) * (y2 as i64 - y1 as i64)
            - (y as i64 - y1 as i64) * (x2 as i64 - x1 as i64);
        if cross != 0 {
            continue;
        }
        if x >= x1.min(x2) && x <= x1.max(x2) && y >= y1.min(y2) && y <= y1.max(y2) {
            return true;
        }
    }
    false
}

/// The boundary counts as inside, as `fill::point_within_ring` does.
fn point_within_ring(x: i32, y: i32, ring: &[(i32, i32)]) -> bool {
    point_in_ring(x, y, ring) || on_boundary(x, y, ring)
}

fn rings_overlap(a: &[(i32, i32)], b: &[(i32, i32)]) -> bool {
    a.iter().any(|&(x, y)| point_in_ring(x, y, b))
        || b.iter().any(|&(x, y)| point_in_ring(x, y, a))
}

/// `fill.rs::polygons`, with the two drop tests behind a flag so both arms come from one
/// body and cannot drift apart.
fn group_rings(rings: &[&[(i32, i32)]], drop_conflicts: bool) -> Vec<Vec<usize>> {
    let count = rings.len();
    let boxes: Vec<_> = rings.iter().map(|r| bounds(r)).collect();

    let mut enclosing: Vec<Vec<(usize, usize)>> = vec![Vec::new(); count];
    for j in 0..count {
        for i in 0..count {
            if i == j
                || !boxes_overlap(boxes[i], boxes[j])
                || box_area(boxes[i]) < box_area(boxes[j])
            {
                continue;
            }
            let votes =
                rings[j].iter().filter(|&&(x, y)| point_within_ring(x, y, rings[i])).count();
            if votes * 2 > rings[j].len() {
                enclosing[j].push((i, votes));
            }
        }
    }
    let depth: Vec<usize> = enclosing.iter().map(|e| e.len()).collect();

    let mut groups = Vec::new();
    for exterior in 0..count {
        if depth[exterior] % 2 != 0 {
            continue;
        }
        let mut holes: Vec<(usize, usize)> = Vec::new();
        for j in 0..count {
            if depth[j] % 2 == 0 {
                continue;
            }
            let mut closest: Option<(usize, usize)> = None;
            for &(ring, votes) in &enclosing[j] {
                if closest.map_or(true, |(held, _)| depth[ring] > depth[held]) {
                    closest = Some((ring, votes));
                }
            }
            if closest.is_some_and(|(ring, _)| ring == exterior) {
                holes.push((j, closest.unwrap().1));
            }
        }
        holes.sort_by_cached_key(|&(j, _)| std::cmp::Reverse(ring_area2(rings[j])));

        let mut group = vec![exterior];
        for (j, votes) in holes {
            if drop_conflicts {
                let straddles = votes != rings[j].len();
                let overlaps = group[1..].iter().any(|&k| {
                    boxes_overlap(boxes[j], boxes[k]) && rings_overlap(rings[j], rings[k])
                });
                if straddles || overlaps {
                    continue;
                }
            }
            group.push(j);
        }
        groups.push(group);
    }
    groups
}

fn open_length(ring: &[(i32, i32)]) -> usize {
    let mut n = ring.len();
    while n >= 2 && ring[0] == ring[n - 1] {
        n -= 1;
    }
    n
}

/// Tessellate via `groups`, returning the total triangle area in tile-normalised units.
fn area_of(extent: u32, groups: &[Vec<usize>], open: &[&[(i32, i32)]]) -> f64 {
    let scale = 1.0 / extent as f64;
    let mut total = 0.0f64;
    let mut coords: Vec<i32> = Vec::new();
    let mut hole_starts: Vec<usize> = Vec::new();
    for group in groups {
        coords.clear();
        hole_starts.clear();
        for (position, &r) in group.iter().enumerate() {
            if position > 0 {
                hole_starts.push(coords.len() / 2);
            }
            for &(x, y) in open[r].iter() {
                coords.push(x);
                coords.push(y);
            }
        }
        for tri in earcut::triangulate(&coords, &hole_starts).chunks_exact(3) {
            let p = |i: u32| {
                let b = i as usize * 2;
                (coords[b] as f64 * scale, coords[b + 1] as f64 * scale)
            };
            let (ax, ay) = p(tri[0]);
            let (bx, by) = p(tri[1]);
            let (cx, cy) = p(tri[2]);
            total += ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)).abs() / 2.0;
        }
    }
    total
}

/// The even-odd region of every ring, by scanline. Quantisation-free.
fn even_odd_area(rings: &[Vec<(i32, i32)>], extent: u32) -> f64 {
    let mut edges: Vec<((i32, i32), (i32, i32))> = Vec::new();
    for ring in rings {
        let n = open_length(ring);
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
    let mut xs: Vec<f64> = Vec::new();
    let mut total = 0.0f64;
    for row in lo..hi {
        let y = row as f64 + 0.5;
        xs.clear();
        for &((x1, y1), (x2, y2)) in &edges {
            if (y1 as f64 > y) != (y2 as f64 > y) {
                let t = (y - y1 as f64) / (y2 as f64 - y1 as f64);
                xs.push(x1 as f64 + t * (x2 as f64 - x1 as f64));
            }
        }
        xs.sort_by(|a, b| a.partial_cmp(b).unwrap());
        let mut i = 0;
        while i + 1 < xs.len() {
            total += xs[i + 1] - xs[i];
            i += 2;
        }
    }
    let s = 1.0 / extent as f64;
    total * s * s
}

struct Arm {
    name: &'static str,
    worst: f64,
    lost: f64,
    surplus: f64,
}

fn scan(label: &str, bytes: &[u8], only_big: bool) {
    let tile = tilecodec::mvt::Tile::decode(bytes).expect("tile decodes");
    for layer in &tile.layers {
        let mut arms = [
            Arm { name: "maplibre", worst: 1.0, lost: 0.0, surplus: 0.0 },
            Arm { name: "current", worst: 1.0, lost: 0.0, surplus: 0.0 },
            Arm { name: "nodrop", worst: 1.0, lost: 0.0, surplus: 0.0 },
            Arm { name: "ml+area", worst: 1.0, lost: 0.0, surplus: 0.0 },
            Arm { name: "ml+revsd", worst: 1.0, lost: 0.0, surplus: 0.0 },
            Arm { name: "PROD", worst: 1.0, lost: 0.0, surplus: 0.0 },
        ];
        // Set if the `current` copy ever stops agreeing with the real tessellator.
        let mut drift = 0.0f64;
        let mut groups_seen = 0usize;

        for feature in &layer.features {
            if feature.geom_type != tilecodec::mvt::GeomType::Polygon {
                continue;
            }
            let Some(polys) = tilecodec::mvt::decode_polygons(&feature.geometry) else { continue };
            for rings in &polys {
                if only_big && rings.len() < 50 {
                    continue;
                }
                let want = even_odd_area(rings, layer.extent);
                if want <= 1e-9 {
                    continue;
                }
                groups_seen += 1;

                let mut open: Vec<&[(i32, i32)]> = Vec::new();
                let mut degenerate_exterior = false;
                for (r, ring) in rings.iter().enumerate() {
                    let n = open_length(ring);
                    // `fill::tessellate`'s MIN_RING_COORDS gate, reproduced exactly: a ring
                    // under three vertices encloses nothing, and if it is the EXTERIOR the
                    // whole polygon goes, because a hole with nothing around it would render
                    // as solid fill.
                    if n < 3 {
                        if r == 0 {
                            degenerate_exterior = true;
                            break;
                        }
                        continue;
                    }
                    open.push(&ring[..n]);
                }
                if degenerate_exterior || open.is_empty() {
                    continue;
                }

                // Arm 1: everything bridged into one earcut call, ring order as decoded.
                let flat: Vec<Vec<usize>> = vec![(0..open.len()).collect()];
                let got_ml = area_of(layer.extent, &flat, &open);
                let got_cur = area_of(layer.extent, &group_rings(&open, true), &open);
                let got_nd = area_of(layer.extent, &group_rings(&open, false), &open);

                // Arms 4 and 5: the same rings, holes presented in a different order.
                // `limitHoles` sorts holes by descending area, so if hole order reached
                // earcut's bridging it could explain MapLibre doing better on the same
                // geometry. Exterior stays at index 0; only the holes are permuted.
                let mut by_area: Vec<usize> = (1..open.len()).collect();
                by_area.sort_by_key(|&i| std::cmp::Reverse(ring_area2(open[i])));
                let area_first: Vec<Vec<usize>> =
                    vec![std::iter::once(0).chain(by_area).collect()];
                let got_area = area_of(layer.extent, &area_first, &open);

                let reversed: Vec<Vec<usize>> =
                    vec![std::iter::once(0).chain((1..open.len()).rev()).collect()];
                let got_rev = area_of(layer.extent, &reversed, &open);

                let got_prod = production_area(rings, layer.extent);
                drift = drift.max((got_prod - got_cur).abs());

                for (arm, got) in arms
                    .iter_mut()
                    .zip([got_ml, got_cur, got_nd, got_area, got_rev, got_prod])
                {
                    let ratio = got / want;
                    if (ratio - 1.0).abs() > (arm.worst - 1.0).abs() {
                        arm.worst = ratio;
                    }
                    if got < want {
                        arm.lost += want - got;
                    } else {
                        arm.surplus += got - want;
                    }
                }
            }
        }

        if groups_seen == 0 {
            continue;
        }
        println!("{label} {:<11} {groups_seen:>4} groups", layer.name);
        for arm in &arms {
            println!(
                "    {:<9} worst ratio {:>6.3}   surplus {:>8.5}   LOST {:>8.5}",
                arm.name, arm.worst, arm.surplus, arm.lost,
            );
        }
        // `current` is a copy of `fill::polygons`; PROD is the real thing. They must agree.
        if drift > 1e-9 {
            println!(
                "    !!! STALE: the `current` copy disagrees with fill::tessellate by {drift:.8} \
                 of a tile. `group_rings` no longer matches `fill::polygons` \u{2014} every `current` \
                 figure above is describing code that is not in the renderer.",
            );
        } else {
            println!("    (current == fill::tessellate to 1e-9, so the copy is faithful)");
        }
    }
}

fn main() {
    const Z0: &[u8] = include_bytes!("../tests/fixtures/v4_z0_tile.mvt");
    const Z11: &[u8] = include_bytes!("../tests/fixtures/v5ca_z11_tile.mvt");

    println!("=== the z0 ocean alone: the polygon the sanitiser was built for ===");
    scan("z0 ", Z0, true);
    println!("\n=== every polygon of both fixtures ===");
    scan("z0 ", Z0, false);
    scan("z11", Z11, false);
}
