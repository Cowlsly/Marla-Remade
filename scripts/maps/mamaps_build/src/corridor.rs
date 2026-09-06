//! Corridor generalisation: make a numbered road appear and disappear as one thing.
//!
//! [`schema::roads`](crate::schema::roads) assigns `min_zoom` from the OSM `highway` tag
//! alone, and that is right for a way considered on its own. A real corridor is not one
//! way, though, and it does not hold one class along its length: CA-99 is `motorway`
//! (min_zoom 3) for long stretches and `trunk` (5) or `primary` (7) through the towns it
//! passes. At z6 the tile carries the motorway parts and not the rest, so the road draws
//! as a handful of disconnected stubs with nothing joining them — the map's most visible
//! defect at continental zooms, and the reason this module exists.
//!
//! The fix is the standard generalisation one: decide `min_zoom` for the **corridor**
//! rather than for the way, by taking the shallowest `min_zoom` any of its ways asks for.
//! A corridor that is worth drawing at z3 anywhere is worth drawing at z3 everywhere, or
//! it should not be drawn at z3 at all.
//!
//! # What counts as one corridor
//!
//! Two conditions, both required:
//!
//! * **The same route identity** — the `ref` tag, so "CA 99" joins "CA 99". Without this
//!   every road that happens to touch a motorway junction would be promoted to the
//!   motorway's zoom, which is the whole network.
//! * **Connected end to end** — they share an endpoint node. Without this, "CA 1" in
//!   California and an unrelated "CA 1" elsewhere in the extract would merge, and a
//!   corridor's promotion would leak across a gap it does not actually cross.
//!
//! Endpoints only, not every node: two roads that merely cross at a junction are not one
//! corridor, and OSM splits a way at the junction anyway, so a genuine continuation
//! shares an endpoint.
//!
//! Slip roads are excluded by the caller. A `motorway_link` often carries its parent's
//! `ref` and touches the corridor at both ends, so it would be promoted with it — putting
//! a scatter of junction stubs on the map at world zoom, where the corridor itself is a
//! single line. A ramp is not part of the through route.
//!
//! # Cost
//!
//! One `Segment` per road way carrying a `ref`, which is a small minority of ways, and a
//! sort of twice that many endpoint records. No node geometry and no second pass over the
//! PBF: pass 1 already decodes the tags and the refs this needs.

/// One road way's corridor identity, as pass 1 sees it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Segment {
    pub way_id: i64,
    /// The route identity, interned by the caller. Ways only join a corridor with a peer
    /// carrying the same one.
    pub route: u64,
    /// First and last node of the way. A way with fewer than two nodes is not a corridor
    /// segment and should not be offered here.
    pub first_node: i64,
    pub last_node: i64,
    /// What [`crate::schema::roads::classify`] asked for, before promotion.
    pub min_zoom: u8,
}

/// Promote each segment's `min_zoom` to the shallowest in its corridor.
///
/// Returns `(way id, promoted min_zoom)` for the ways that actually move, ascending by
/// way id so the materialise pass can binary-search it. Ways whose corridor does not
/// change their zoom are left out — on a typical extract most of them.
pub fn promote(segments: &[Segment]) -> Vec<(i64, u8)> {
    if segments.is_empty() {
        return Vec::new();
    }
    let mut parent: Vec<u32> = (0..segments.len() as u32).collect();

    // Union ways that share a route AND an endpoint. Sorting the endpoint records puts
    // every such pair in one run, which avoids a hash map over twice the segment count -
    // the largest thing this pass would otherwise allocate.
    let mut endpoints: Vec<(u64, i64, u32)> = Vec::with_capacity(segments.len() * 2);
    for (index, segment) in segments.iter().enumerate() {
        endpoints.push((segment.route, segment.first_node, index as u32));
        if segment.last_node != segment.first_node {
            endpoints.push((segment.route, segment.last_node, index as u32));
        }
    }
    endpoints.sort_unstable();
    for run in endpoints.chunk_by(|a, b| a.0 == b.0 && a.1 == b.1) {
        for pair in run.windows(2) {
            union(&mut parent, pair[0].2, pair[1].2);
        }
    }

    // Shallowest min_zoom per corridor, then anything that moves.
    let mut shallowest: Vec<u8> = vec![u8::MAX; segments.len()];
    for (index, segment) in segments.iter().enumerate() {
        let root = find(&mut parent, index as u32) as usize;
        shallowest[root] = shallowest[root].min(segment.min_zoom);
    }
    let mut promoted: Vec<(i64, u8)> = Vec::new();
    for (index, segment) in segments.iter().enumerate() {
        let root = find(&mut parent, index as u32) as usize;
        let zoom = shallowest[root];
        if zoom < segment.min_zoom {
            promoted.push((segment.way_id, zoom));
        }
    }
    promoted.sort_unstable_by_key(|(id, _)| *id);
    promoted
}

fn find(parent: &mut [u32], mut node: u32) -> u32 {
    while parent[node as usize] != node {
        // Path halving: keeps the trees flat without a second pass.
        parent[node as usize] = parent[parent[node as usize] as usize];
        node = parent[node as usize];
    }
    node
}

fn union(parent: &mut [u32], a: u32, b: u32) {
    let (ra, rb) = (find(parent, a), find(parent, b));
    if ra != rb {
        parent[ra.max(rb) as usize] = ra.min(rb);
    }
}

/// Intern a route identity. `None` for a way that is not part of a numbered route.
///
/// The `ref` tag and nothing else. `name` was considered and rejected: "Main Street"
/// names thousands of unconnected roads, and the connectivity condition is not enough to
/// keep them apart where two of them meet at a junction.
pub fn route_key(tags: &(impl crate::schema::TagSource + ?Sized)) -> Option<u64> {
    let value = tags.get("ref")?.trim();
    if value.is_empty() {
        return None;
    }
    use std::hash::{Hash, Hasher};
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    value.hash(&mut hasher);
    Some(hasher.finish())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn segment(way_id: i64, route: u64, first: i64, last: i64, min_zoom: u8) -> Segment {
        Segment { way_id, route, first_node: first, last_node: last, min_zoom }
    }

    /// THE case this module exists for: a numbered route that is motorway, then trunk,
    /// then motorway again draws as three stubs at z6 unless the middle is promoted.
    #[test]
    fn a_trunk_between_two_motorway_stretches_is_promoted_to_the_motorways_zoom() {
        let corridor = [
            segment(1, 99, 10, 11, 3), // motorway
            segment(2, 99, 11, 12, 5), // trunk through a town
            segment(3, 99, 12, 13, 3), // motorway again
        ];
        assert_eq!(promote(&corridor), vec![(2, 3)], "only the trunk moves, and it moves to 3");
    }

    /// Promotion runs the length of the corridor, not just to the neighbour: a primary
    /// two hops from the motorway is still part of the same road.
    #[test]
    fn promotion_reaches_the_whole_connected_run() {
        let corridor = [
            segment(1, 99, 10, 11, 3),
            segment(2, 99, 11, 12, 5),
            segment(3, 99, 12, 13, 7),
            segment(4, 99, 13, 14, 7),
        ];
        assert_eq!(promote(&corridor), vec![(2, 3), (3, 3), (4, 3)]);
    }

    /// Without the route condition, every road touching a motorway junction would be
    /// promoted to the motorway's zoom, which is most of the network.
    #[test]
    fn a_different_route_sharing_a_junction_is_not_promoted() {
        let roads = [
            segment(1, 99, 10, 11, 3), // CA 99, motorway
            segment(2, 50, 11, 12, 9), // a different route leaving the same junction
        ];
        assert!(promote(&roads).is_empty(), "a junction is not a corridor");
    }

    /// Without the connectivity condition, two unconnected roads that happen to share a
    /// `ref` would merge and promotion would leak across the gap.
    #[test]
    fn the_same_ref_in_two_disconnected_places_stays_two_corridors() {
        let roads = [
            segment(1, 1, 10, 11, 3),
            segment(2, 1, 500, 501, 9), // same ref, nowhere near
        ];
        assert!(promote(&roads).is_empty());
    }

    /// A corridor already uniform costs nothing and produces no entries, which is what
    /// keeps the promotion table small on a real extract.
    #[test]
    fn a_uniform_corridor_produces_no_entries() {
        let corridor = [segment(1, 99, 10, 11, 3), segment(2, 99, 11, 12, 3)];
        assert!(promote(&corridor).is_empty());
    }

    /// Direction is not guaranteed: OSM splits a way wherever it likes and the halves may
    /// meet head-to-head or tail-to-tail.
    #[test]
    fn segments_join_whichever_way_round_they_are_drawn() {
        for (first, last) in [(12, 11), (11, 12)] {
            let corridor = [segment(1, 99, 10, 11, 3), segment(2, 99, first, last, 7)];
            assert_eq!(promote(&corridor), vec![(2, 3)], "{first}->{last} must join");
        }
    }

    /// A closed loop (a ring road tagged with a ref) has one endpoint, and must not
    /// confuse the endpoint index or be dropped.
    #[test]
    fn a_closed_way_is_still_a_segment() {
        let corridor = [segment(1, 99, 10, 10, 7), segment(2, 99, 10, 11, 3)];
        assert_eq!(promote(&corridor), vec![(1, 3)]);
    }

    #[test]
    fn the_table_comes_back_sorted_by_way_id() {
        let corridor = [
            segment(90, 7, 1, 2, 9),
            segment(10, 7, 2, 3, 3),
            segment(50, 7, 3, 4, 9),
        ];
        let promoted = promote(&corridor);
        let ids: Vec<i64> = promoted.iter().map(|(id, _)| *id).collect();
        assert_eq!(ids, vec![50, 90], "ascending, so the materialise pass can bisect");
    }

    #[test]
    fn nothing_in_nothing_out() {
        assert!(promote(&[]).is_empty());
    }
}
