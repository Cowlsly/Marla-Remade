//! Merging a layer's line features into one per class, and joining the fragments that continue each
//! other.
//!
//! # Why this exists
//!
//! An OSM way is an **editing** unit. It is split at every junction, bridge, surface change and tag
//! edit, none of which has anything to do with what gets drawn. Carrying one way as one rendering
//! feature therefore pays a feature header and a part entry for each fragment of a road, and on a
//! us-west z7 tile that came to:
//!
//! | | count | width | bytes |
//! |---|---|---|---|
//! | feature records | 57,218 | 16 | 916 KB |
//! | part entries | 57,218 | 12 | 687 KB |
//! | coordinate arena | 189,484 points | ~2-3 | ~0.5 MB |
//!
//! Three quarters of the layer was bookkeeping, 61% of its parts held exactly two points, and the
//! mean was 3.31 points per part. The same tile's `boundaries` layer -- whose relations arrive
//! already merged -- ran 139 features over 667 parts, which is what healthy looks like.
//!
//! It compounds three ways. The headers are the obvious cost. Less obvious is that
//! [`tilecodec::mamaps::body`] restarts its coordinate deltas at every part so a part decodes
//! independently, so at 3.3 points per part most points are the *first* of their part and pay a full
//! absolute coordinate where a continued line would pay one byte. And a `.mamaps` layer indexes its
//! features with a `u16`, so a North America build stopped outright at `layer 4 has 79407 features,
//! past the 65535 a .mamaps body can index`.
//!
//! This is not a container-format problem -- MVT has the same one, which is why `tippecanoe` ships
//! `--coalesce`. It is that nothing in this pipeline ever merged what OSM had split.
//!
//! # What it does
//!
//! Two steps, and the first is the one that matters for the `u16`:
//!
//! 1. **Group by class.** Line features drawn identically -- same `kind`, `kind_detail`, `flags`
//!    and `transit_color` -- become one multi-part feature. Nothing about the geometry changes; a
//!    `.mamaps` line feature has always been able to hold many parts, and `boundaries` already did.
//! 2. **Join what continues.** Within a group, a part whose last point is another's first point is
//!    the same line cut in half, so they are spliced into one part. That removes the part entry and
//!    lets the delta run through the joint instead of restarting.
//!
//! # Why it is safe
//!
//! A `.mamaps` feature carries no identity of its own: [`Feature`] is `kind`, `kind_detail`,
//! `geom_type`, `flags`, `transit_color`, the three transit lane fields and a part range, and
//! nothing else.
//! Two line features that
//! agree on all of those are indistinguishable to a renderer, so merging them cannot change a pixel.
//! There are no names or ids on a feature to lose, and `winding` is meaningless on an open path --
//! which [`crate::rings::normalise`] states outright.
//!
//! `transit_color` is part of the key rather than dropped from it because it is the one per-feature
//! field a renderer reads directly. Two subway lines of different operator colours crossing a tile
//! are the same `kind` and would otherwise collapse into one feature wearing whichever colour came
//! first. The lane fields join it for the same reason one step on: two routes of *one* colour on
//! different corridor ordinals are drawn as two parallel lines, and merging them would put both
//! on whichever ordinal came first.
//!
//! Direction is not preserved through a join and does not need to be: nothing in
//! [`tilecodec::mamaps::body`]'s flag set reads a line's direction, so `A -> B` and `B -> A` draw
//! the same. Joins are made head-to-tail only, so a part is never reversed anyway.
//!
//! # Ordering, and why the archive stays reproducible
//!
//! Called on a tile that the merge has already assembled, so it sees a layer's features in the order
//! the store yielded them, and it emits each class at the position of that class's **first** feature.
//! A group's parts stay in feature-then-part order and chains are built by walking them in that
//! order, so nothing here depends on a hash map's iteration order or on how the map phase chunked
//! its input.

use std::collections::HashMap;

use tilecodec::mamaps::body::{BuildingAttrs, LaneTurns, Layer, Part, GEOM_LINE, WINDING_OUTER};

/// What coalescing a build saved, for the report.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Stats {
    pub features_before: u64,
    pub features_after: u64,
    pub parts_before: u64,
    pub parts_after: u64,
}

impl Stats {
    pub fn add(&mut self, other: Stats) {
        self.features_before += other.features_before;
        self.features_after += other.features_after;
        self.parts_before += other.parts_before;
        self.parts_after += other.parts_after;
    }
}

/// What makes two line features interchangeable to a renderer. The trailing `name_idx` keeps two
/// differently-named roads or rivers of the same class apart, so a curved label is never attached
/// to a line that merged with a neighbour of another name; same-named fragments still merge.
type Class = (u16, u16, u8, u32, u8, u8, u8, u8, u16);

/// One line part, as `(coord_start, point_count)` into a layer's existing arena.
///
/// Indices rather than copied points: a group can hold tens of thousands of parts, and a `Vec` of
/// points each would be the allocation this module exists to remove.
type Span = (u32, u32);

/// The spans of one continuous polyline, in the order they join.
type Run = Vec<Span>;

/// Merge a layer's line features by class and splice the parts that continue each other.
///
/// Polygon features are left exactly as they were, in place; only the parts table and the arena are
/// rebuilt around them, which [`crate::rings::normalise`] does immediately afterwards anyway.
pub fn coalesce_lines(layer: &mut Layer) -> Stats {
    coalesce_lines_with_ids(layer, None, None, None)
}

/// As [`coalesce_lines`], but also rewrites an id side table and a turn-lane side table so they
/// stay parallel to the features.
///
/// The tables are indexed by feature position, so merging lines without rewriting them leaves more
/// entries than features and the encoder rejects the tile. A merged line takes the id of the first
/// feature of its class, which is arbitrary — that is why only layers whose id-carrying features
/// are *not* lines populate the id table at all (see `extract::tracks_ids`). Turn masks are the
/// opposite: they belong to a *road* line, so a line that carries any is held out of merging
/// entirely (each becomes its own survivor) rather than folded into a neighbour and losing them.
pub fn coalesce_lines_with_ids(
    layer: &mut Layer,
    ids: Option<&mut Vec<u64>>,
    turns: Option<&mut Vec<LaneTurns>>,
    buildings: Option<&mut Vec<BuildingAttrs>>,
) -> Stats {
    let mut stats = Stats {
        features_before: layer.features.len() as u64,
        parts_before: layer.parts.len() as u64,
        ..Stats::default()
    };
    let lines = layer.features.iter().filter(|f| f.geom_type == GEOM_LINE).count();
    // One line feature cannot be merged with anything, and a layer of pure polygons is the common
    // case: both would only pay for a rebuild that changes nothing.
    if lines < 2 {
        stats.features_after = stats.features_before;
        stats.parts_after = stats.parts_before;
        return stats;
    }

    // A line that carries turn masks is never merged — its arrows belong to that exact segment —
    // so it is copied through at its position like a non-line. `false` everywhere when the layer
    // has no turn table, which is every layer but a `roads` tile that had `turn:lanes` in it.
    let unmergeable: Vec<bool> = match turns.as_deref() {
        Some(t) => layer.features.iter().enumerate().map(|(i, _)| {
            t.get(i).is_some_and(|lt| !lt.is_empty())
        }).collect(),
        None => vec![false; layer.features.len()],
    };

    // Every line part, grouped by class, in feature-then-part order.
    let mut order: Vec<Class> = Vec::new();
    let mut group_of: HashMap<Class, usize> = HashMap::new();
    let mut groups: Vec<Vec<Span>> = Vec::new();
    for (index, feature) in layer.features.iter().enumerate() {
        if feature.geom_type != GEOM_LINE || unmergeable[index] {
            continue;
        }
        let class = class_of(feature);
        let at = *group_of.entry(class).or_insert_with(|| {
            order.push(class);
            groups.push(Vec::new());
            groups.len() - 1
        });
        let from = feature.parts_offset as usize;
        for part in &layer.parts[from..from + feature.part_count as usize] {
            groups[at].push((part.coord_start, part.point_count));
        }
    }

    // Chains first, so the arena can be laid down in one pass below.
    let chains: Vec<Vec<Run>> =
        groups.iter().map(|parts| chain(parts, &layer.coords)).collect();

    let mut features = Vec::with_capacity(layer.features.len() - lines + order.len());
    // The original position of each surviving feature, for remapping the side tables below.
    let mut kept: Vec<usize> = Vec::with_capacity(features.capacity());
    let mut parts: Vec<Part> = Vec::with_capacity(layer.parts.len());
    let mut coords: Vec<(i16, i16)> = Vec::with_capacity(layer.coords.len());
    let mut emitted: Vec<bool> = vec![false; order.len()];

    for (index, feature) in layer.features.iter().enumerate() {
        if feature.geom_type != GEOM_LINE || unmergeable[index] {
            // Copied through at its original position, so a mixed layer keeps its feature order and
            // a turn-masked road keeps its own geometry and its arrows.
            let from = feature.parts_offset as usize;
            let start = parts.len() as u32;
            for part in &layer.parts[from..from + feature.part_count as usize] {
                let points = points_of(&layer.coords, *part);
                parts.push(Part {
                    coord_start: coords.len() as u32,
                    point_count: points.len() as u32,
                    winding: part.winding,
                });
                coords.extend_from_slice(points);
            }
            let mut copy = *feature;
            copy.parts_offset = start;
            features.push(copy);
            kept.push(index);
            continue;
        }
        let class = class_of(feature);
        let at = group_of[&class];
        if emitted[at] {
            continue;
        }
        emitted[at] = true;
        let start = parts.len() as u32;
        for run in &chains[at] {
            let first = coords.len() as u32;
            for (i, &(coord_start, point_count)) in run.iter().enumerate() {
                let points = points_of(
                    &layer.coords,
                    Part { coord_start, point_count, winding: WINDING_OUTER },
                );
                // The joint is one point in two parts; the second copy would be a zero-length
                // segment and `geom::quantize` is no longer around to drop it.
                let points = if i == 0 { points } else { &points[1..] };
                coords.extend_from_slice(points);
            }
            parts.push(Part {
                coord_start: first,
                point_count: coords.len() as u32 - first,
                winding: WINDING_OUTER,
            });
        }
        let mut merged = *feature;
        merged.parts_offset = start;
        merged.part_count = parts.len() as u32 - start;
        features.push(merged);
        kept.push(index);
    }

    if let Some(ids) = ids {
        if !ids.is_empty() {
            *ids = kept.iter().map(|&at| ids[at]).collect();
        }
    }
    if let Some(turns) = turns {
        if !turns.is_empty() {
            *turns = kept.iter().map(|&at| turns[at].clone()).collect();
        }
    }
    if let Some(buildings) = buildings {
        if !buildings.is_empty() {
            *buildings = kept.iter().map(|&at| buildings[at]).collect();
        }
    }

    layer.features = features;
    layer.parts = parts;
    layer.coords = coords;
    stats.features_after = layer.features.len() as u64;
    stats.parts_after = layer.parts.len() as u64;
    stats
}

fn class_of(feature: &tilecodec::mamaps::body::Feature) -> Class {
    (
        feature.kind,
        feature.kind_detail,
        feature.flags,
        feature.transit_color,
        feature.transit_ordinal,
        feature.transit_lanes,
        feature.transit_taper,
        feature.lane_count,
        feature.name_idx,
    )
}

fn points_of(coords: &[(i16, i16)], part: Part) -> &[(i16, i16)] {
    let at = part.coord_start as usize;
    &coords[at..at + part.point_count as usize]
}

/// Group `parts` into runs that each form one continuous polyline.
///
/// Head-to-tail only: a part joins the chain when its first point is the chain's last. Parts are
/// never reversed, so the result is a subset of the joins a reversing walk would find -- and it is
/// the subset that needs no argument about direction.
///
/// Chains start at parts nothing runs into, so a road is grown from its end rather than from its
/// middle; only then are the leftovers taken, which is what closes a ring road onto itself. Both
/// loops walk `parts` in order, so the output does not depend on the hash map.
fn chain(parts: &[Span], coords: &[(i16, i16)]) -> Vec<Run> {
    let ends: Vec<((i16, i16), (i16, i16))> = parts
        .iter()
        .map(|&(coord_start, point_count)| {
            let points = points_of(coords, Part { coord_start, point_count, winding: WINDING_OUTER });
            // A part with nothing in it cannot join anything; a sentinel keeps the index aligned.
            match (points.first(), points.last()) {
                (Some(a), Some(b)) => (*a, *b),
                _ => ((i16::MIN, i16::MIN), (i16::MAX, i16::MAX)),
            }
        })
        .collect();

    let mut by_start: HashMap<(i16, i16), Vec<usize>> = HashMap::new();
    for (i, (first, _)) in ends.iter().enumerate() {
        by_start.entry(*first).or_default().push(i);
    }
    // Whether anything ends where this part starts. A part that nothing runs into is a chain head.
    let mut continued = vec![false; parts.len()];
    for (_, last) in &ends {
        if let Some(hits) = by_start.get(last) {
            for &i in hits {
                continued[i] = true;
            }
        }
    }

    let mut used = vec![false; parts.len()];
    let mut out: Vec<Run> = Vec::new();
    let grow = |from: usize, used: &mut Vec<bool>, out: &mut Vec<Run>| {
        let mut run: Run = vec![parts[from]];
        used[from] = true;
        let mut tail = ends[from].1;
        while let Some(next) =
            by_start.get(&tail).and_then(|hits| hits.iter().copied().find(|&j| !used[j]))
        {
            used[next] = true;
            run.push(parts[next]);
            tail = ends[next].1;
        }
        out.push(run);
    };
    for i in 0..parts.len() {
        if !used[i] && !continued[i] {
            grow(i, &mut used, &mut out);
        }
    }
    // Whatever is left is a cycle: every part in it is continued by another, so none is a head.
    for i in 0..parts.len() {
        if !used[i] {
            grow(i, &mut used, &mut out);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::body::{Feature, GEOM_POLYGON};

    /// One fixture feature: `(kind, geom_type, [part, ...])`, each part a list of points.
    type Fixture = (u16, u8, Vec<Vec<(i16, i16)>>);

    /// Build a layer from a list of fixture features.
    fn layer_of(features: &[Fixture]) -> Layer {
        let mut layer = Layer::new(4);
        for (kind, geom_type, parts) in features {
            let parts_offset = layer.parts.len() as u32;
            for points in parts {
                layer.parts.push(Part {
                    coord_start: layer.coords.len() as u32,
                    point_count: points.len() as u32,
                    winding: WINDING_OUTER,
                });
                layer.coords.extend_from_slice(points);
            }
            layer.features.push(Feature {
                kind: *kind,
                kind_detail: 0,
                geom_type: *geom_type,
                flags: 0,
                name_idx: tilecodec::mamaps::body::NAME_NONE,
                parts_offset,
                part_count: parts.len() as u32,
                transit_color: 0,
                transit_ordinal: 0,
                transit_lanes: 0,
                transit_taper: 0,
                lane_count: 0,
            });
        }
        layer
    }

    /// Every part's points, so a test can compare geometry without caring how it is grouped.
    fn all_parts(layer: &Layer) -> Vec<Vec<(i16, i16)>> {
        layer
            .parts
            .iter()
            .map(|p| points_of(&layer.coords, *p).to_vec())
            .collect()
    }

    /// The arena must be tiled exactly by the parts table, in order, or the encoder rejects the
    /// body. Asserted on every result below, because it is the invariant easiest to break here.
    fn assert_arena_is_tiled(layer: &Layer) {
        let mut at = 0u32;
        for part in &layer.parts {
            assert_eq!(part.coord_start, at, "parts must tile the arena with no gaps");
            at += part.point_count;
        }
        assert_eq!(at as usize, layer.coords.len(), "the arena must end where the parts do");
        for feature in &layer.features {
            let end = feature.parts_offset as usize + feature.part_count as usize;
            assert!(end <= layer.parts.len(), "a feature indexes past the parts table");
            assert!(feature.part_count > 0, "a feature with no parts draws nothing");
        }
    }

    #[test]
    fn features_of_one_class_become_one_feature() {
        // Four disjoint fragments of the same class: one feature, four parts, same geometry.
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 1)]]),
            (7, GEOM_LINE, vec![vec![(10, 10), (11, 11)]]),
            (7, GEOM_LINE, vec![vec![(20, 20), (21, 21)]]),
            (7, GEOM_LINE, vec![vec![(30, 30), (31, 31)]]),
        ]);
        let before = all_parts(&layer);
        let stats = coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 1);
        assert_eq!(layer.features[0].part_count, 4);
        assert_eq!(all_parts(&layer), before, "nothing disjoint may be joined");
        assert_eq!(stats, Stats { features_before: 4, features_after: 1, parts_before: 4, parts_after: 4 });
    }

    #[test]
    fn different_classes_stay_apart() {
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 1)]]),
            (9, GEOM_LINE, vec![vec![(1, 1), (2, 2)]]),
            (7, GEOM_LINE, vec![vec![(5, 5), (6, 6)]]),
        ]);
        coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 2, "two classes, two features");
        assert_eq!(layer.features[0].kind, 7, "the first class keeps the first position");
        assert_eq!(layer.features[1].kind, 9);
        // Class 9 touches class 7's endpoint but must not be spliced onto it.
        assert_eq!(layer.features[1].part_count, 1);
    }

    #[test]
    fn fragments_that_continue_each_other_are_spliced() {
        // Three pieces of one road, given out of order, plus a fourth that is elsewhere.
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(2, 0), (3, 0)]]),
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
            (7, GEOM_LINE, vec![vec![(50, 50), (51, 50)]]),
        ]);
        let stats = coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 1);
        assert_eq!(stats.parts_before, 4);
        assert_eq!(stats.parts_after, 2, "three joined into one, plus the far piece");
        let parts = all_parts(&layer);
        assert!(
            parts.contains(&vec![(0, 0), (1, 0), (2, 0), (3, 0)]),
            "the joint point appears once, not twice: {parts:?}"
        );
        assert!(parts.contains(&vec![(50, 50), (51, 50)]));
    }

    #[test]
    fn a_chain_starts_at_its_head_rather_than_its_middle() {
        // If a chain were grown from the middle piece the result would be two runs, not one.
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(2, 0), (3, 0)]]),
        ]);
        coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.parts.len(), 1, "one road, one part");
        assert_eq!(all_parts(&layer)[0], vec![(0, 0), (1, 0), (2, 0), (3, 0)]);
    }

    /// A ring road: every piece is continued by another, so none is a head. Without the second pass
    /// over the leftovers this would emit nothing and the road would vanish.
    #[test]
    fn a_closed_loop_survives_having_no_head() {
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (10, 0)]]),
            (7, GEOM_LINE, vec![vec![(10, 0), (10, 10)]]),
            (7, GEOM_LINE, vec![vec![(10, 10), (0, 0)]]),
        ]);
        coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.parts.len(), 1);
        assert_eq!(
            all_parts(&layer)[0],
            vec![(0, 0), (10, 0), (10, 10), (0, 0)],
            "the loop closes on the point it started at"
        );
    }

    #[test]
    fn polygons_are_untouched_and_keep_their_place() {
        let square = vec![(0, 0), (4, 0), (4, 4), (0, 4), (0, 0)];
        let hole = vec![(1, 1), (2, 1), (2, 2), (1, 2), (1, 1)];
        let mut layer = layer_of(&[
            (3, GEOM_POLYGON, vec![square.clone(), hole.clone()]),
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
        ]);
        coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 2);
        assert_eq!(layer.features[0].geom_type, GEOM_POLYGON, "the polygon keeps its position");
        assert_eq!(layer.features[0].part_count, 2, "exterior and hole both survive");
        let parts = all_parts(&layer);
        assert_eq!(parts[0], square);
        assert_eq!(parts[1], hole);
        assert_eq!(parts[2], vec![(0, 0), (1, 0), (2, 0)], "the lines still merged");
    }

    #[test]
    fn a_layer_with_nothing_to_merge_is_left_alone() {
        for features in [
            vec![],
            vec![(7u16, GEOM_LINE, vec![vec![(0i16, 0i16), (1, 1)]])],
            vec![(3, GEOM_POLYGON, vec![vec![(0, 0), (4, 0), (4, 4), (0, 0)]])],
        ] {
            let mut layer = layer_of(&features);
            let before = layer.clone();
            let stats = coalesce_lines(&mut layer);
            assert_eq!(layer, before, "a layer with fewer than two lines must not be rebuilt");
            assert_eq!(stats.features_before, stats.features_after);
            assert_eq!(stats.parts_before, stats.parts_after);
        }
    }

    /// Two transit lines of one mode crossing a tile: same `kind`, different operator colour. They
    /// must stay apart, or the merged feature wears whichever colour happened to come first.
    #[test]
    fn transit_colour_separates_otherwise_identical_features() {
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
        ]);
        layer.features[0].transit_color = 0x00_54_A5;
        layer.features[1].transit_color = 0xE3_1E_24;
        coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 2, "two colours, two features");
        assert_eq!(layer.features[0].transit_color, 0x00_54_A5);
        assert_eq!(layer.features[1].transit_color, 0xE3_1E_24);
        // They touch end to end, so without the colour in the key they would also be spliced.
        assert_eq!(all_parts(&layer), vec![vec![(0, 0), (1, 0)], vec![(1, 0), (2, 0)]]);
    }

    /// The counterpart: equal colours still merge, so the key is not simply always-distinct.
    #[test]
    fn equal_transit_colours_still_merge() {
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
        ]);
        layer.features[0].transit_color = 0x00_54_A5;
        layer.features[1].transit_color = 0x00_54_A5;
        coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 1);
        assert_eq!(all_parts(&layer), vec![vec![(0, 0), (1, 0), (2, 0)]]);
    }

    /// Two routes of one colour on different corridor ordinals draw as two parallel lines.
    /// Merging them would put both on whichever ordinal came first, which is the whole defect the
    /// ordinal exists to fix.
    #[test]
    fn a_transit_ordinal_separates_two_features_of_one_colour() {
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
        ]);
        for feature in &mut layer.features {
            feature.transit_color = 0x00_54_A5;
            feature.transit_lanes = 2;
            feature.transit_taper = 255;
        }
        layer.features[0].transit_ordinal = 0;
        layer.features[1].transit_ordinal = 1;
        coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 2, "two ordinals, two features");
        assert_eq!(layer.features[0].transit_ordinal, 0);
        assert_eq!(layer.features[1].transit_ordinal, 1);
        assert_eq!(all_parts(&layer), vec![vec![(0, 0), (1, 0)], vec![(1, 0), (2, 0)]]);
    }

    /// The point of the whole module, in the shape the real data has: thousands of two-point
    /// fragments of one class laid end to end.
    #[test]
    fn a_fragmented_road_collapses_to_one_part() {
        let features: Vec<Fixture> = (0..2000)
            .map(|i| (7u16, GEOM_LINE, vec![vec![(i as i16, 0), (i as i16 + 1, 0)]]))
            .collect();
        let mut layer = layer_of(&features);
        let stats = coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(stats.features_before, 2000);
        assert_eq!(stats.features_after, 1);
        assert_eq!(stats.parts_before, 2000);
        assert_eq!(stats.parts_after, 1);
        assert_eq!(layer.coords.len(), 2001, "2000 segments share 1999 joints");
    }
}
