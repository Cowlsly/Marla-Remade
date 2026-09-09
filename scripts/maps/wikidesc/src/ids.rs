//! OSM element ids as `.mamaps` writes them.
//!
//! **This must agree with `mamaps_build`'s `extract::tagged_id` exactly.** The archive is looked up
//! by the id the renderer hands the app — `PlacedLabel.featureId`, which comes from the basemap's
//! v3 feature-id side table — so an encoding that disagrees produces an archive where every lookup
//! silently misses. Nothing would error; descriptions would simply never appear.
//!
//! The scheme: `(osm_id << 2) | element`, with the element tag in the low two bits. A bare OSM id
//! does not identify an element, because a node, a way and a relation may all be numbered 123.
//! Tags start at one so that an all-zero id stays reserved for "no id".
//!
//! Duplicated here rather than shared because `mamaps_build` does not expose a library, and a
//! four-line function behind a new crate dependency would be worse than a four-line function with
//! a test that states what it must match.

/// Reserved for a feature with no upstream OSM element.
pub const ID_NONE: u64 = 0;

pub const ELEMENT_NODE: u64 = 1;
pub const ELEMENT_WAY: u64 = 2;
pub const ELEMENT_RELATION: u64 = 3;

/// An OSM id tagged with its id space, or [`ID_NONE`] for an id OSM would never issue.
pub fn tagged_id(id: i64, element: u64) -> u64 {
    if id <= 0 {
        return ID_NONE;
    }
    ((id as u64) << 2) | element
}

pub fn node(id: i64) -> u64 {
    tagged_id(id, ELEMENT_NODE)
}

pub fn way(id: i64) -> u64 {
    tagged_id(id, ELEMENT_WAY)
}

pub fn relation(id: i64) -> u64 {
    tagged_id(id, ELEMENT_RELATION)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The whole reason the low bits exist: three different things may share a number.
    #[test]
    fn the_same_number_as_a_node_way_and_relation_are_three_ids() {
        let (n, w, r) = (node(123), way(123), relation(123));
        assert_ne!(n, w);
        assert_ne!(w, r);
        assert_ne!(n, r);
        assert_eq!(n, (123 << 2) | 1);
        assert_eq!(w, (123 << 2) | 2);
        assert_eq!(r, (123 << 2) | 3);
    }

    /// Pinned against `mamaps_build`'s `extract::tagged_id`. If that changes, this must change with
    /// it, or every lookup in the archive misses without erroring.
    #[test]
    fn the_encoding_matches_what_mamaps_build_writes() {
        // (id as u64) << 2 | element, tags 1/2/3, id <= 0 -> ID_NONE.
        assert_eq!(tagged_id(1, ELEMENT_NODE), 5);
        assert_eq!(tagged_id(1, ELEMENT_WAY), 6);
        assert_eq!(tagged_id(1, ELEMENT_RELATION), 7);
        assert_eq!(tagged_id(0, ELEMENT_NODE), ID_NONE);
        assert_eq!(tagged_id(-5, ELEMENT_WAY), ID_NONE);
    }

    /// A real OSM id is around 2^33 today; shifted left two it is nowhere near overflowing, and
    /// nowhere near the sign bit that `PlacedLabel.featureId` (a Kotlin Long) would care about.
    #[test]
    fn a_realistic_id_stays_well_inside_the_range() {
        let big = 12_000_000_000i64;
        let tagged = way(big);
        assert_eq!(tagged >> 2, big as u64, "the id survives the round trip");
        assert!(tagged < i64::MAX as u64, "and stays positive as a Kotlin Long");
    }
}
