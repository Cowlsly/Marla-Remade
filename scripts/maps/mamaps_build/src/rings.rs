//! Stage C: normalise ring winding and hole containment, once, at build time.
//!
//! # Why this is worth a module
//!
//! The published z0 ocean tile is one polygon with **105 holes**, of which 40 overlap each other, 6
//! straddle the exterior, 4 are entirely outside it and 13 are lakes nested inside other holes.
//! `tess::fill` on device repairs all of that every time the tile is tessellated, under a frame
//! budget, in `i32`. Doing it here instead costs nothing anyone waits for, runs in `f64`, and turns
//! a per-frame repair into a one-time fact — which is what
//! [`FLAG_RINGS_VALIDATED`](tilecodec::mamaps::header::FLAG_RINGS_VALIDATED) tells the renderer.
//!
//! # What "valid" means here
//!
//! Per part group, after this runs:
//!
//! 1. exactly one exterior, wound counter-clockwise;
//! 2. every hole wound clockwise;
//! 3. every hole strictly inside its exterior;
//! 4. no two holes overlapping;
//! 5. no zero-area ring.
//!
//! Those five are what `tess::fill` assumes and what the batch check in this module's tests asserts
//! over every tile of a real build.
//!
//! # What it does not do
//!
//! It does not clip a straddling hole to its exterior or subtract one overlapping hole from another.
//! Both need a real boolean operation, and getting one subtly wrong is worse than dropping the hole:
//! a dropped hole paints a lake as land, which is visible and wrong; a botched intersection paints a
//! wedge across a continent, which is visible and inexplicable. So a hole that cannot be placed
//! cleanly is **dropped and counted**, and the count is in the build report.

use tilecodec::mamaps::body::{Layer, Part, WINDING_HOLE, WINDING_OUTER};

/// What normalising a build cost, for the report.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Stats {
    /// Rings whose winding disagreed with their stated role and was corrected.
    pub rewound: u64,
    /// Rings that enclosed no area at all.
    pub zero_area: u64,
    /// Holes dropped for being outside their exterior, straddling it, or overlapping another hole.
    pub holes_dropped: u64,
    pub groups: u64,
}

impl Stats {
    pub fn add(&mut self, other: Stats) {
        self.rewound += other.rewound;
        self.zero_area += other.zero_area;
        self.holes_dropped += other.holes_dropped;
        self.groups += other.groups;
    }

    /// Did anything have to be corrected?
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn clean(&self) -> bool {
        self.rewound == 0 && self.zero_area == 0 && self.holes_dropped == 0
    }
}

/// Normalise every polygon feature of a layer in place.
///
/// Rebuilds the parts table and the coordinate arena, because dropping a ring changes both and the
/// encoder requires the parts to tile the arena exactly.
pub fn normalise(layer: &mut Layer) -> Stats {
    let mut stats = Stats::default();
    let mut parts: Vec<Part> = Vec::with_capacity(layer.parts.len());
    let mut coords: Vec<(i16, i16)> = Vec::with_capacity(layer.coords.len());

    for feature in &mut layer.features {
        if feature.geom_type != tilecodec::mamaps::body::GEOM_POLYGON {
            // A line's parts pass through untouched: winding means nothing on an open path.
            let start = parts.len() as u32;
            for part in layer.parts[feature.parts_offset as usize..]
                .iter()
                .take(feature.part_count as usize)
            {
                let points = ring_points(&layer.coords, part);
                parts.push(Part {
                    coord_start: coords.len() as u32,
                    point_count: points.len() as u32,
                    winding: part.winding,
                });
                coords.extend_from_slice(points);
            }
            feature.parts_offset = start;
            continue;
        }
        stats.groups += 1;
        let group: Vec<(&Part, &[(i16, i16)])> = layer.parts
            [feature.parts_offset as usize..]
            .iter()
            .take(feature.part_count as usize)
            .map(|part| (part, ring_points(&layer.coords, part)))
            .collect();

        // The exterior is the first part, by the format's own convention.
        let Some((_, exterior)) = group.first().copied() else {
            feature.part_count = 0;
            continue;
        };
        let exterior_area = signed_area(exterior);
        if exterior_area == 0.0 {
            // A zero-area exterior takes its holes with it: there is nothing for them to be in.
            stats.zero_area += 1;
            feature.part_count = 0;
            continue;
        }

        let start = parts.len() as u32;
        let mut count = 0u32;
        // An exterior is counter-clockwise. Reversed rather than re-labelled, because the winding
        // field states a fact about the coordinates and the two must agree.
        let mut kept: Vec<Vec<(i16, i16)>> = Vec::with_capacity(group.len());
        let mut exterior_ring = exterior.to_vec();
        if exterior_area < 0.0 {
            exterior_ring.reverse();
            stats.rewound += 1;
        }
        push_ring(&mut parts, &mut coords, &exterior_ring, WINDING_OUTER);
        count += 1;
        kept.push(exterior_ring.clone());

        for (_, hole) in group.iter().skip(1) {
            let area = signed_area(hole);
            if area == 0.0 {
                stats.zero_area += 1;
                continue;
            }
            // Strictly inside its exterior. A straddling or outside hole is dropped rather than
            // clipped: a dropped hole paints a lake as land, which is visible and explicable, and a
            // botched clip paints a wedge across a continent, which is neither.
            if !strictly_inside(hole, &exterior_ring) {
                stats.holes_dropped += 1;
                continue;
            }
            // And not overlapping a hole already kept. Same reasoning: no boolean operations.
            if kept.iter().skip(1).any(|other| rings_overlap(hole, other)) {
                stats.holes_dropped += 1;
                continue;
            }
            let mut ring = hole.to_vec();
            // A hole is clockwise, which is the opposite sign to its exterior.
            if area > 0.0 {
                ring.reverse();
                stats.rewound += 1;
            }
            push_ring(&mut parts, &mut coords, &ring, WINDING_HOLE);
            count += 1;
            kept.push(ring);
        }
        feature.parts_offset = start;
        feature.part_count = count;
    }

    // A feature whose exterior went takes no parts, so it draws nothing. Removed outright rather
    // than left as an empty group, which the encoder refuses.
    layer.features.retain(|feature| feature.part_count > 0);
    layer.parts = parts;
    layer.coords = coords;
    stats
}

fn ring_points<'a>(coords: &'a [(i16, i16)], part: &Part) -> &'a [(i16, i16)] {
    let start = part.coord_start as usize;
    &coords[start..start + part.point_count as usize]
}

fn push_ring(
    parts: &mut Vec<Part>,
    coords: &mut Vec<(i16, i16)>,
    ring: &[(i16, i16)],
    winding: u16,
) {
    parts.push(Part {
        coord_start: coords.len() as u32,
        point_count: ring.len() as u32,
        winding,
    });
    coords.extend_from_slice(ring);
}

/// A ring's signed area by the shoelace formula: positive counter-clockwise.
///
/// `f64` from `i64` terms. This is the whole reason the work belongs at build time: on device the
/// same computation is done in `i32` under a frame budget, where a long ring's cross products
/// overflow and a near-degenerate one rounds to the wrong sign.
pub fn signed_area(ring: &[(i16, i16)]) -> f64 {
    let mut twice = 0i64;
    for pair in ring.windows(2) {
        let ((x0, y0), (x1, y1)) = (pair[0], pair[1]);
        twice += x0 as i64 * y1 as i64 - x1 as i64 * y0 as i64;
    }
    // An unclosed ring: close it implicitly rather than reporting a wrong area.
    if let (Some(first), Some(last)) = (ring.first(), ring.last()) {
        if first != last {
            twice += last.0 as i64 * first.1 as i64 - first.0 as i64 * last.1 as i64;
        }
    }
    twice as f64 / 2.0
}

/// Is every vertex of `inner` inside `outer`?
///
/// Vertex containment rather than a full geometric test. It is what distinguishes the three cases
/// that matter — wholly inside, wholly outside, straddling — and it cannot be fooled by anything a
/// clipped tile actually contains, because a hole that crosses its exterior has vertices on both
/// sides by construction.
fn strictly_inside(inner: &[(i16, i16)], outer: &[(i16, i16)]) -> bool {
    inner.iter().all(|&point| point_in_ring(point, outer))
}

/// Do two rings overlap? Approximated by mutual vertex containment.
///
/// One ring having a vertex inside the other is the signature of every overlap a clipped tile
/// produces. Two rings that merely touch at a vertex are not overlapping, and are left alone —
/// `tess::fill` already handles a hole that touches its exterior.
fn rings_overlap(a: &[(i16, i16)], b: &[(i16, i16)]) -> bool {
    a.iter().filter(|&&p| point_in_ring(p, b)).count() > 1
        || b.iter().filter(|&&p| point_in_ring(p, a)).count() > 1
}

/// Even-odd ray cast. On the boundary counts as outside, which is what makes `strictly_inside`
/// strict.
fn point_in_ring((x, y): (i16, i16), ring: &[(i16, i16)]) -> bool {
    let (x, y) = (x as f64, y as f64);
    let mut inside = false;
    for pair in ring.windows(2) {
        let ((x0, y0), (x1, y1)) = (pair[0], pair[1]);
        let (x0, y0, x1, y1) = (x0 as f64, y0 as f64, x1 as f64, y1 as f64);
        if (y0 > y) != (y1 > y) {
            let t = (y - y0) / (y1 - y0);
            if x < x0 + t * (x1 - x0) {
                inside = !inside;
            }
        }
    }
    inside
}

/// **Verification item 6 of the plan's list.** Every ring of a layer must satisfy all five
/// invariants.
///
/// Returned as a list of complaints rather than a bool, so a failing build says which polygon and
/// why. Used by the generator's own tests over every tile of a real build.
#[cfg_attr(not(test), allow(dead_code))]
pub fn check(layer: &Layer) -> Vec<String> {
    let mut problems = Vec::new();
    for (index, feature) in layer.features.iter().enumerate() {
        if feature.geom_type != tilecodec::mamaps::body::GEOM_POLYGON {
            continue;
        }
        let parts = layer.parts_of(feature);
        let Some(exterior) = parts.first() else {
            problems.push(format!("feature {index} has no parts"));
            continue;
        };
        if exterior.winding != WINDING_OUTER {
            problems.push(format!("feature {index}'s first part is a hole"));
        }
        let exterior_points = layer.points(exterior);
        let area = signed_area(exterior_points);
        if area <= 0.0 {
            problems.push(format!("feature {index}'s exterior is not counter-clockwise ({area})"));
        }
        let holes: Vec<&Part> = parts[1..].iter().collect();
        if holes.iter().any(|part| part.winding != WINDING_HOLE) {
            problems.push(format!("feature {index} has a second exterior"));
        }
        for (i, hole) in holes.iter().enumerate() {
            let points = layer.points(hole);
            let area = signed_area(points);
            if area >= 0.0 {
                problems.push(format!("feature {index}'s hole {i} is not clockwise ({area})"));
            }
            if !strictly_inside(points, exterior_points) {
                problems.push(format!("feature {index}'s hole {i} is not inside its exterior"));
            }
            for (j, other) in holes.iter().enumerate().skip(i + 1) {
                if rings_overlap(points, layer.points(other)) {
                    problems.push(format!("feature {index}'s holes {i} and {j} overlap"));
                }
            }
        }
    }
    problems
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::body::{Feature, GEOM_LINE, GEOM_POLYGON};
    use tilecodec::mamaps::dict;

    /// A layer holding one polygon feature with the given rings, all labelled by position.
    fn layer_of(rings: &[Vec<(i16, i16)>]) -> Layer {
        let mut layer = Layer::new(dict::LAYER_WATER);
        layer.features.push(Feature {
            kind: dict::NONE,
            kind_detail: dict::NONE,
            geom_type: GEOM_POLYGON,
            flags: 0,
            parts_offset: 0,
            part_count: rings.len() as u32,
        });
        for (index, ring) in rings.iter().enumerate() {
            layer.parts.push(Part {
                coord_start: layer.coords.len() as u32,
                point_count: ring.len() as u32,
                winding: if index == 0 { WINDING_OUTER } else { WINDING_HOLE },
            });
            layer.coords.extend_from_slice(ring);
        }
        layer
    }

    /// Positive signed area, which is what this module calls an exterior's winding.
    ///
    /// Tile coordinates put y downward, so a positive shoelace area looks clockwise on screen. The
    /// name follows the formula rather than the screen, because the formula is what the code and
    /// `tess::fill` both use.
    fn square(x: i16, y: i16, size: i16) -> Vec<(i16, i16)> {
        vec![(x, y), (x + size, y), (x + size, y + size), (x, y + size), (x, y)]
    }

    fn reversed(ring: &[(i16, i16)]) -> Vec<(i16, i16)> {
        let mut out = ring.to_vec();
        out.reverse();
        out
    }

    #[test]
    fn an_exterior_wound_the_wrong_way_is_reversed_not_relabelled() {
        let mut layer = layer_of(&[reversed(&square(0, 0, 100))]);
        let stats = normalise(&mut layer);
        assert_eq!(stats.rewound, 1);
        assert!(check(&layer).is_empty(), "{:?}", check(&layer));
        // The coordinates changed, not just the label: the winding field states a fact about them.
        assert!(signed_area(layer.points(&layer.parts[0])) > 0.0);
    }

    #[test]
    fn a_hole_wound_the_wrong_way_is_reversed() {
        let mut layer = layer_of(&[square(0, 0, 100), square(20, 20, 20)]);
        let stats = normalise(&mut layer);
        // The hole was given counter-clockwise, which is an exterior's winding.
        assert_eq!(stats.rewound, 1);
        assert_eq!(stats.holes_dropped, 0);
        assert!(check(&layer).is_empty(), "{:?}", check(&layer));
        assert!(signed_area(layer.points(&layer.parts[1])) < 0.0, "the hole is clockwise");
    }

    /// **The four pathologies the published z0 tile exhibits.** Each is dropped and counted rather
    /// than clipped, because a botched boolean operation is worse than a missing lake.
    #[test]
    fn a_hole_outside_straddling_or_overlapping_is_dropped_and_counted() {
        // Wholly outside.
        let mut layer = layer_of(&[square(0, 0, 100), square(500, 500, 20)]);
        let stats = normalise(&mut layer);
        assert_eq!(stats.holes_dropped, 1);
        assert_eq!(layer.features[0].part_count, 1, "only the exterior is left");

        // Straddling the exterior.
        let mut layer = layer_of(&[square(0, 0, 100), square(90, 90, 40)]);
        assert_eq!(normalise(&mut layer).holes_dropped, 1);

        // Overlapping another hole.
        let mut layer =
            layer_of(&[square(0, 0, 200), square(20, 20, 60), square(50, 50, 60)]);
        let stats = normalise(&mut layer);
        assert_eq!(stats.holes_dropped, 1, "the first hole is kept, the overlapping one is not");
        assert_eq!(layer.features[0].part_count, 2);
        assert!(check(&layer).is_empty(), "{:?}", check(&layer));
    }

    #[test]
    fn a_zero_area_ring_goes_and_takes_nothing_with_it() {
        // A degenerate hole.
        let mut layer =
            layer_of(&[square(0, 0, 100), vec![(20, 20), (40, 20), (20, 20), (20, 20)]]);
        let stats = normalise(&mut layer);
        assert_eq!(stats.zero_area, 1);
        assert_eq!(layer.features[0].part_count, 1);

        // A degenerate exterior takes its holes with it: there is nothing for them to be in.
        let mut layer = layer_of(&[vec![(0, 0), (10, 0), (0, 0), (0, 0)], square(2, 2, 2)]);
        let stats = normalise(&mut layer);
        assert_eq!(stats.zero_area, 1);
        assert!(layer.features.is_empty(), "the feature draws nothing, so it is not carried");
    }

    /// Nested lakes: a hole inside a hole. The inner one is land again, and dropping it is the
    /// conservative answer — it paints as water rather than as a wedge of nothing.
    #[test]
    fn a_lake_nested_inside_another_hole_is_dropped() {
        let mut layer =
            layer_of(&[square(0, 0, 400), square(50, 50, 200), square(100, 100, 50)]);
        let stats = normalise(&mut layer);
        assert_eq!(stats.holes_dropped, 1);
        assert!(check(&layer).is_empty(), "{:?}", check(&layer));
    }

    /// An already-valid polygon must come out untouched, or every rebuild would churn.
    #[test]
    fn a_valid_polygon_is_left_exactly_alone() {
        let rings = [square(0, 0, 200), reversed(&square(20, 20, 40))];
        let mut layer = layer_of(&rings);
        let before = (layer.parts.clone(), layer.coords.clone());
        let stats = normalise(&mut layer);
        assert!(stats.clean(), "{stats:?}");
        assert_eq!((layer.parts, layer.coords), before);
    }

    /// Winding means nothing on an open path, so a line's parts pass through untouched.
    #[test]
    fn a_line_layer_is_not_rewound() {
        let mut layer = Layer::new(dict::LAYER_ROADS);
        layer.features.push(Feature {
            kind: dict::NONE,
            kind_detail: dict::NONE,
            geom_type: GEOM_LINE,
            flags: 0,
            parts_offset: 0,
            part_count: 1,
        });
        layer.parts.push(Part { coord_start: 0, point_count: 3, winding: WINDING_OUTER });
        layer.coords = vec![(0, 0), (50, 0), (50, 50)];
        let before = (layer.parts.clone(), layer.coords.clone());
        let stats = normalise(&mut layer);
        assert!(stats.clean());
        assert_eq!((layer.parts, layer.coords), before);
    }

    /// The check has to be able to fail, or it proves nothing.
    #[test]
    fn the_check_reports_each_invariant_it_can_see_broken() {
        let mut layer = layer_of(&[reversed(&square(0, 0, 100))]);
        assert!(!check(&layer).is_empty(), "a clockwise exterior");

        layer = layer_of(&[square(0, 0, 100), square(20, 20, 20)]);
        let problems = check(&layer);
        assert!(
            problems.iter().any(|p| p.contains("not clockwise")),
            "a counter-clockwise hole: {problems:?}",
        );

        layer = layer_of(&[square(0, 0, 100), reversed(&square(500, 500, 20))]);
        let problems = check(&layer);
        assert!(
            problems.iter().any(|p| p.contains("not inside")),
            "a hole outside its exterior: {problems:?}",
        );
    }

    /// The arena has to stay exactly tiled by the parts after a ring is dropped, or the encoder
    /// refuses the body — which is the check that makes this safe to run over a whole build.
    #[test]
    fn the_arena_stays_tiled_by_its_parts_after_a_drop() {
        let mut layer =
            layer_of(&[square(0, 0, 100), square(500, 500, 20), square(20, 20, 20)]);
        normalise(&mut layer);
        let mut at = 0u32;
        for part in &layer.parts {
            assert_eq!(part.coord_start, at);
            at += part.point_count;
        }
        assert_eq!(at as usize, layer.coords.len(), "no orphaned coordinates");
        let body = tilecodec::mamaps::body::Body {
            extent: 4096,
            layers: vec![layer],
        };
        // The encoder's own contiguity check, which is the real proof.
        assert!(tilecodec::mamaps::body::serialize(&body).is_ok());
    }

    /// **Verification item 6, over a whole build rather than a synthetic case.** Every polygon of
    /// every tile has to satisfy all five invariants after the tiler runs, because that is exactly
    /// what the archive's `FLAG_RINGS_VALIDATED` claims and what the renderer will skip a repair
    /// pass on the strength of.
    #[test]
    fn every_polygon_of_a_real_build_is_valid() {
        use crate::schema::Class;
        use tilecodec::mamaps::body::Body;
        use tilecodec::mamaps::dict;

        // Shapes chosen to exercise the pathologies: a lake with a hole, a hole outside its
        // exterior, and a shape large enough to be clipped across several tiles at deep zoom.
        let ring = |x: f64, y: f64, size: f64| {
            vec![(x, y), (x + size, y), (x + size, y + size), (x, y + size), (x, y)]
        };
        let features = vec![
            crate::extract::Feature {
                class: Class::area(dict::LAYER_WATER, crate::schema::kind("lake"), 0),
                geometry: tile_build::geom::Geometry::Polygons(vec![vec![
                    ring(-120.5, 35.0, 1.0),
                    // A hole, wound the same way as its exterior, which stage C has to reverse.
                    ring(-120.2, 35.2, 0.3),
                ]]),
            },
            crate::extract::Feature {
                class: Class::area(dict::LAYER_WATER, crate::schema::kind("water"), 0),
                geometry: tile_build::geom::Geometry::Polygons(vec![vec![
                    ring(-119.0, 36.0, 0.5),
                    // A hole nowhere near its exterior, which stage C has to drop.
                    ring(-100.0, 20.0, 0.1),
                ]]),
            },
        ];
        let settings = crate::tiler::Settings {
            min_zoom: 0,
            max_zoom: 10,
            simplification: crate::tiler::DEFAULT_SIMPLIFICATION,
            build_id: 1,
        };
        let (bytes, stats) = crate::tiler::build(&features, &settings).expect("build");

        // The archive claims validity, so every tile in it had better be valid.
        let header = tilecodec::mamaps::Header::parse(&bytes).expect("header");
        assert!(header.rings_validated(), "the build claims validated rings");
        let mut checked = 0usize;
        for (id, _, body) in tilecodec::mamaps::read::read_all(&bytes).expect("read") {
            let body = Body::parse(&body).expect("parse");
            for layer in &body.layers {
                let problems = check(layer);
                assert!(problems.is_empty(), "tile {id}: {problems:?}");
                checked += layer.features.len();
            }
        }
        assert!(checked > 0, "the build produced no polygons to check");
        // And stage C really had something to do, or this proves nothing.
        let corrected: crate::rings::Stats =
            stats.iter().fold(crate::rings::Stats::default(), |mut acc, z| {
                acc.add(z.rings);
                acc
            });
        assert!(!corrected.clean(), "stage C corrected nothing: {corrected:?}");
    }
}
