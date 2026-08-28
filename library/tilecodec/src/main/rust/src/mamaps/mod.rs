//! `.mamaps` — the container the Vulkan renderer reads, shaped like what it draws.
//!
//! # Why not MVT inside PMTiles
//!
//! Because almost none of it is used. An MVT tile is protobuf varints, a per-tile string table and
//! an arbitrary key/value property map per feature; the renderer reads exactly **one** property
//! (`kind`, as a `String`), handles two of the four geometry types, and decodes points only to
//! throw them away. Everything else is bytes every device downloads, inflates and discards.
//!
//! So: geometry only, pre-clipped, pre-simplified, attributes interned to integers, flat
//! little-endian structs. Not pre-tessellated — triangles would bake the style's layer set into the
//! data, and which layer a feature belongs to is a paint decision.
//!
//! # The sections
//!
//! ```text
//! [header 128][dictionary][root index][leaf indices][tile data]
//! ```
//!
//! Order on disk is **not** part of the format. Every section is located only by a header-declared
//! offset, because the real 137 GB PMTiles archive puts its leaves after its tile data and a reader
//! that assumed layout would address the wrong bytes.
//!
//! * [`header`] — 128 fixed bytes, including the `build_id` that makes republishing under an
//!   `immutable` URL safe.
//! * [`dict`] — layer names and the `kind`/`kind_detail` tables, **pre-seeded from a constant
//!   schema table** so an id never shifts between builds.
//! * [`index`] — a fixed-stride root and fixed-stride leaves, uncompressed so the root is usable
//!   straight out of the opening prefix.
//! * [`body`] — one per tile, with every layer inside it.
//! * [`read`] — open in one request, a warm tile in one, a cold tile in two, never three.
//! * `write` — the builder, behind the `write` feature.
//!
//! # What the `write` feature is for
//!
//! Android reads archives; it never writes one. Gating the builder keeps the interner and the
//! DEFLATE *encoder* out of the `aarch64-linux-android` cdylib, and — more usefully — makes it a
//! compile error rather than a review comment if the read path ever grows a dependency on the write
//! path.

pub mod body;
pub mod dict;
pub mod header;
pub mod index;
pub mod read;

#[cfg(feature = "write")]
pub mod write;

pub use body::Body;
pub use dict::Dictionary;
pub use header::Header;
pub use read::MamapsArchive;

#[cfg(all(test, feature = "write"))]
mod tests {
    use super::*;
    use crate::mamaps::body::{Feature, Layer, Part, DEFAULT_EXTENT, GEOM_LINE, WINDING_OUTER};
    use crate::mamaps::write::{Options, StreamWriter};
    use crate::pmtiles::{tile_id, zoom_base};
    use crate::proto::Result;
    use crate::stream::{RangeReader, OPEN_PREFIX_BYTES};
    use std::cell::RefCell;

    /// A [`RangeReader`] over bytes in memory that logs every range asked for, so a test can assert
    /// on the **number of round trips** as well as on the bytes. The same shape `stream.rs` uses.
    struct Counting {
        bytes: Vec<u8>,
        requests: RefCell<Vec<(u64, u32)>>,
    }

    impl RangeReader for Counting {
        fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
            self.requests.borrow_mut().push((offset, length));
            if offset >= self.bytes.len() as u64 {
                return Ok(Vec::new());
            }
            // Short rather than failing, as the trait specifies.
            let end = (offset + length as u64).min(self.bytes.len() as u64);
            Ok(self.bytes[offset as usize..end as usize].to_vec())
        }
    }

    /// A one-line body whose contents depend on `seed`, so two tiles differ unless asked not to.
    fn body_for(seed: i16) -> Body {
        let mut roads = Layer::new(dict::LAYER_ROADS);
        roads.features.push(Feature {
            kind: 45,
            kind_detail: dict::NONE,
            geom_type: GEOM_LINE,
            flags: 0,
            parts_offset: 0,
            part_count: 1,
        });
        roads.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
        roads.coords = vec![(0, 0), (seed, seed)];
        Body { extent: DEFAULT_EXTENT, layers: vec![roads] }
    }

    /// An archive of `(z, x, y, seed)` tiles, fed in ascending id order as the writer requires.
    fn archive(tiles: &[(u8, u64, u64, i16)], options: Options) -> Vec<u8> {
        let mut rows: Vec<(u64, i16)> =
            tiles.iter().map(|(z, x, y, seed)| (tile_id(*z, *x, *y), *seed)).collect();
        rows.sort_by_key(|(id, _)| *id);
        let mut writer = StreamWriter::new(options).expect("options");
        for (id, seed) in rows {
            writer.append(id, &body_for(seed)).expect("append");
        }
        writer.finish().expect("finish")
    }

    fn open(bytes: Vec<u8>) -> MamapsArchive<Counting> {
        MamapsArchive::open(Counting { bytes, requests: RefCell::new(Vec::new()) }).expect("open")
    }

    #[test]
    fn a_tile_written_reads_back_exactly() {
        let bytes = archive(&[(0, 0, 0, 100), (1, 0, 0, 200), (1, 1, 1, 300)], Options::default());
        let mut a = open(bytes);
        for (z, x, y, seed) in [(0u8, 0u32, 0u32, 100i16), (1, 0, 0, 200), (1, 1, 1, 300)] {
            let tile = a.tile(z, x, y).expect("read").expect("present");
            assert_eq!(tile, body_for(seed), "z{z}/{x}/{y}");
        }
        // A tile the archive does not hold is `None`, which is the ordinary answer off the edge of
        // coverage rather than a fault.
        assert!(a.tile(1, 0, 1).expect("read").is_none());
        assert!(a.tile(9, 0, 0).expect("read").is_none(), "past max_zoom");
        assert!(a.tile(1, 9, 9).expect("read").is_none(), "off the world");
    }

    #[test]
    fn a_tile_reads_back_uncompressed_too() {
        let options = Options { compress: false, ..Options::default() };
        let mut a = open(archive(&[(0, 0, 0, 7)], options));
        assert!(!a.header.compressed());
        assert_eq!(a.tile(0, 0, 0).expect("read").expect("present"), body_for(7));
    }

    /// **Invariant 2 of the plan's verification list.** The whole container shape exists to hold
    /// this: open is one request, a warm tile is one, a cold tile is two, and nothing is ever
    /// three.
    #[test]
    fn open_is_one_request_a_warm_tile_one_a_cold_tile_two_and_never_three() {
        // Enough tiles to need more than one leaf, so a cold tile really does have a leaf to
        // fetch. Sixteen entries per leaf: z0 plus all of z1..z3 is 85 tiles, so six leaves.
        let mut tiles: Vec<(u8, u64, u64, i16)> = Vec::new();
        for z in 0..=3u8 {
            for x in 0..(1u64 << z) {
                for y in 0..(1u64 << z) {
                    tiles.push((z, x, y, (tile_id(z, x, y) + 1) as i16));
                }
            }
        }
        let options = Options { leaf_entry_capacity: 16, ..Options::default() };
        let mut a = open(archive(&tiles, options));
        assert!(a.header.leaf_count > 1, "this test needs several leaves");
        assert_eq!(
            a.reader.requests.borrow().as_slice(),
            &[(0, OPEN_PREFIX_BYTES)],
            "a cold open is one request: header, dictionary and root all live in the prefix",
        );

        let count = |a: &MamapsArchive<Counting>| a.reader.requests.borrow().len();
        a.reader.requests.borrow_mut().clear();
        assert!(a.tile(3, 0, 0).expect("read").is_some());
        assert_eq!(count(&a), 2, "a cold tile is a leaf plus a body");

        a.reader.requests.borrow_mut().clear();
        assert!(a.tile(3, 1, 1).expect("read").is_some());
        assert_eq!(count(&a), 1, "its leaf is now cached, so only the body");

        // And nothing anywhere costs three, at any zoom, warm or cold.
        for (z, x, y, _) in &tiles {
            a.reader.requests.borrow_mut().clear();
            let _ = a.tile(*z, *x as u32, *y as u32).expect("read");
            let n = count(&a);
            assert!(n <= 2, "z{z}/{x}/{y} cost {n} requests");
        }
    }

    /// **Invariant 3.** Every read's length comes from a header or entry field, so the request log
    /// can be checked against the fields rather than against a sentinel or a guess. A read of the
    /// wrong length does not merely fail: `tile::cache` stores a `206` whose body length equals the
    /// request, so it poisons that range for every later read.
    #[test]
    fn every_read_takes_its_length_from_a_declared_field() {
        let mut a = open(archive(&[(0, 0, 0, 1), (1, 0, 0, 2)], Options::default()));
        let (leaf_len, leaf_offset) = (a.header.leaf_len, a.header.leaf_offset);
        a.reader.requests.borrow_mut().clear();
        assert!(a.tile(1, 0, 0).expect("read").is_some());
        let requests = a.reader.requests.borrow().clone();
        assert_eq!(requests.len(), 2);
        // The leaf: offset and length both from the root entry and the header.
        assert_eq!(requests[0].0, leaf_offset, "the leaf is where the header says");
        assert!(requests[0].1 as u64 <= leaf_len as u64, "and no longer than the section it is in");
        assert_eq!(
            requests[0].1 as usize % index::LEAF_ENTRY_LEN,
            0,
            "a leaf read is a whole number of entries",
        );
        // The body: inside the data section, at a length from its own leaf entry.
        assert!(requests[1].0 >= a.header.data_offset);
        assert!(requests[1].0 + requests[1].1 as u64 <= a.header.data_offset + a.header.data_len);
    }

    /// A short reply must fail loudly rather than be decoded or cached.
    #[test]
    fn a_range_that_comes_back_short_is_refused() {
        let full = archive(&[(0, 0, 0, 1), (1, 0, 0, 2)], Options::default());
        let truncated = full[..full.len() - 8].to_vec();
        let mut a =
            MamapsArchive::open(Counting { bytes: truncated, requests: RefCell::new(Vec::new()) })
                .expect("the prefix is intact, so open still works");
        let failure = a.tile(1, 0, 0).expect_err("the last body is cut off");
        assert!(failure.0.contains("got"), "{}", failure.0);
    }

    /// **What makes ocean and empty tiles nearly free.** Consecutive identical bodies collapse to
    /// one entry *and* one body; non-consecutive ones share the body through the content bucket.
    #[test]
    fn identical_tiles_share_one_body_whether_or_not_they_are_consecutive() {
        // Every z1 tile the same, and z0 different, so the run cannot swallow everything.
        let bytes = archive(&[(0, 0, 0, 9), (1, 0, 0, 5), (1, 1, 0, 5), (1, 1, 1, 5), (1, 0, 1, 5)], Options::default());
        let header = Header::parse(&bytes).expect("header");
        assert_eq!(header.tiles_addressed, 5);
        assert_eq!(header.bodies_written, 2, "one distinct z1 body plus z0's");
        assert!(header.flags & header::FLAG_RUN_LENGTH_PRESENT != 0, "a run was used");
        // And all four still read back.
        let mut a = open(bytes);
        for (x, y) in [(0u32, 0u32), (1, 0), (1, 1), (0, 1)] {
            assert_eq!(a.tile(1, x, y).expect("read").expect("present"), body_for(5));
        }
        assert_eq!(a.tile(0, 0, 0).expect("read").expect("present"), body_for(9));
    }

    /// Content dedup across a leaf boundary, which is the case that made a leaf's
    /// `base_data_offset` the *minimum* of its chunk rather than its first entry: a tile in a later
    /// leaf can point at a body written for an earlier one.
    #[test]
    fn a_tile_can_share_a_body_written_for_an_earlier_leaf() {
        // Ids ascend, seeds alternate, so no run ever forms and the repeats are all non-adjacent.
        let mut tiles: Vec<(u8, u64, u64, i16)> = Vec::new();
        for x in 0..8u64 {
            for y in 0..8u64 {
                tiles.push((3, x, y, if (x + y) % 2 == 0 { 11 } else { 22 }));
            }
        }
        let options = Options { leaf_entry_capacity: 4, ..Options::default() };
        let bytes = archive(&tiles, options);
        let header = Header::parse(&bytes).expect("header");
        assert_eq!(header.tiles_addressed, 64);
        assert!(header.bodies_written <= 8, "{} bodies for two shapes", header.bodies_written);
        assert!(header.leaf_count > 4, "this test needs the repeats to cross leaves");
        let mut a = open(bytes);
        for (z, x, y, seed) in &tiles {
            let tile = a.tile(*z, *x as u32, *y as u32).expect("read").expect("present");
            assert_eq!(tile, body_for(*seed), "z{z}/{x}/{y}");
        }
    }

    /// **Invariant 5.** Byte-identical output for identical input, twice over. Nothing in the emit
    /// path iterates a hash map: the dictionary is a constant table, layers are sorted by id and
    /// the content bucket is only ever probed.
    #[test]
    fn two_builds_of_the_same_input_are_byte_identical() {
        let tiles: Vec<(u8, u64, u64, i16)> =
            (0..4u64).flat_map(|x| (0..4u64).map(move |y| (2u8, x, y, (x * 4 + y) as i16))).collect();
        let first = archive(&tiles, Options::default());
        let second = archive(&tiles, Options::default());
        assert_eq!(first, second);
        // And feeding the same tiles in a different order gives the same file, because `archive`
        // sorts by id and the writer's output depends on nothing else. This is what stands in for
        // "identical at 1/2/3/32 threads": a threaded producer differs only in arrival order.
        let mut shuffled = tiles.clone();
        shuffled.reverse();
        assert_eq!(first, archive(&shuffled, Options::default()));
    }

    /// The dictionary is the same bytes whatever the archive covers, which is what makes a diff of
    /// two archives a diff of their tiles.
    #[test]
    fn a_small_archive_has_the_same_dictionary_as_a_large_one() {
        let small = archive(&[(0, 0, 0, 1)], Options::default());
        let large: Vec<(u8, u64, u64, i16)> =
            (0..8u64).flat_map(|x| (0..8u64).map(move |y| (3u8, x, y, (x * 8 + y) as i16))).collect();
        let large = archive(&large, Options::default());
        let dict_of = |bytes: &[u8]| {
            let h = Header::parse(bytes).expect("header");
            bytes[h.dict_offset as usize..(h.dict_offset + h.dict_len as u64) as usize].to_vec()
        };
        assert_eq!(dict_of(&small), dict_of(&large));
        assert_eq!(dict_of(&small), Dictionary::schema().serialize());
    }

    /// **Invariant 4.** Ids must ascend, and the writer refuses rather than sorting: the index is
    /// built as bodies arrive, and a caller feeding them out of order is a caller whose bucketing
    /// broke.
    #[test]
    fn appending_out_of_order_or_outside_the_zoom_range_is_refused() {
        let mut writer = StreamWriter::new(Options::default()).expect("options");
        writer.append(tile_id(1, 1, 1), &body_for(1)).expect("first");
        assert!(writer.append(tile_id(0, 0, 0), &body_for(2)).is_err(), "descending");
        assert!(writer.append(tile_id(1, 1, 1), &body_for(2)).is_err(), "repeated");

        let options = Options { min_zoom: 2, max_zoom: 3, ..Options::default() };
        let mut writer = StreamWriter::new(options).expect("options");
        assert!(writer.append(tile_id(0, 0, 0), &body_for(1)).is_err(), "shallower than min_zoom");
        assert!(writer.append(tile_id(4, 0, 0), &body_for(1)).is_err(), "deeper than max_zoom");
        assert!(writer.append(tile_id(2, 0, 0), &body_for(1)).is_ok());
    }

    /// And the ids the index is built on are the same zoom-major ones the generator's spill buckets
    /// use, so its `zoom_base(z)..zoom_base(z + 1)` ranges are provably untouched by this format.
    #[test]
    fn stored_ids_are_ascending_and_land_in_their_own_zooms_range() {
        let mut tiles: Vec<(u8, u64, u64, i16)> = Vec::new();
        for z in 0..=3u8 {
            for x in 0..(1u64 << z) {
                tiles.push((z, x, 0, (z as i16 + 1) * 10));
            }
        }
        let bytes = archive(&tiles, Options { leaf_entry_capacity: 4, ..Options::default() });
        let stored = read::read_all(&bytes).expect("read_all");
        assert!(stored.windows(2).all(|p| p[1].0 > p[0].0), "ascending across leaves");
        for (id, _, _) in &stored {
            let (z, _, _) = crate::pmtiles::tile_zxy(*id);
            assert!((zoom_base(z)..zoom_base(z + 1)).contains(id), "id {id} is outside z{z}");
        }
        let addressed: u32 = stored.iter().map(|(_, run, _)| run).sum();
        assert_eq!(addressed as usize, tiles.len(), "every tile is addressed exactly once");
    }

    /// **Invariant 1, end to end.** A root that will not fit the opening prefix is a build failure,
    /// and the escape is a bigger leaf rather than a bigger root — so a build that would have cost
    /// every reader a third round trip instead grows its leaves and still opens in one.
    #[test]
    fn a_root_too_large_for_the_prefix_grows_its_leaves_instead() {
        // 4096 z6 tiles at one entry per leaf would need 4096 root entries, which is 128 KiB. The
        // writer must double the capacity until the root fits.
        let tiles: Vec<(u8, u64, u64, i16)> =
            (0..64u64).flat_map(|x| (0..64u64).map(move |y| (6u8, x, y, (x * 64 + y) as i16))).collect();
        let options = Options { leaf_entry_capacity: 1, max_zoom: 6, ..Options::default() };
        let bytes = archive(&tiles, options);
        let header = Header::parse(&bytes).expect("header");
        assert!(header.leaf_entry_capacity > 1, "the capacity grew to {}", header.leaf_entry_capacity);
        let prefix = header::HEADER_LEN as u64 + header.dict_len as u64 + header.root_len as u64;
        assert!(prefix <= OPEN_PREFIX_BYTES as u64, "the prefix is {prefix} bytes");
        // And it still opens in one request and reads back.
        let mut a = open(bytes);
        assert_eq!(a.reader.requests.borrow().len(), 1);
        assert_eq!(a.tile(6, 63, 63).expect("read").expect("present"), body_for(63 * 64 + 63));
    }

    #[test]
    fn an_empty_archive_is_refused_rather_than_written() {
        let writer = StreamWriter::new(Options::default()).expect("options");
        assert!(writer.finish().is_err(), "an archive with no tiles addresses nothing");
    }

    #[test]
    fn a_build_id_survives_the_round_trip_and_is_what_invalidates_a_cache() {
        let options = Options { build_id: 0xDEAD_BEEF_0BAD_F00D, ..Options::default() };
        let a = open(archive(&[(0, 0, 0, 1)], options));
        assert_eq!(a.header.build_id, 0xDEAD_BEEF_0BAD_F00D);
        // Republishing the same tiles under a new id gives a different header and the same data,
        // which is exactly what lets a reader notice without a second request.
        let options = Options { build_id: 1, ..Options::default() };
        let other = archive(&[(0, 0, 0, 1)], options);
        assert_ne!(Header::parse(&other).expect("header").build_id, a.header.build_id);
    }

    #[test]
    fn the_rings_validated_flag_is_carried_so_the_renderer_can_gate_its_repair_pass() {
        let plain = open(archive(&[(0, 0, 0, 1)], Options::default()));
        assert!(!plain.header.rings_validated(), "not claimed unless the generator says so");
        let options = Options { rings_validated: true, ..Options::default() };
        let validated = open(archive(&[(0, 0, 0, 1)], options));
        assert!(validated.header.rings_validated());
    }

    /// An archive written against a different schema table must be refused on open, not rendered
    /// with every kind meaning something else.
    #[test]
    fn an_archive_whose_dictionary_is_not_this_schema_is_refused_on_open() {
        let mut bytes = archive(&[(0, 0, 0, 1)], Options::default());
        let header = Header::parse(&bytes).expect("header");
        // `island` is the first kind name; corrupt one byte of it.
        let at = header.dict_offset as usize;
        let island = bytes[at..].windows(6).position(|w| w == b"island").expect("island") + at;
        bytes[island] = b'X';
        let opened = MamapsArchive::open(Counting { bytes, requests: RefCell::new(Vec::new()) });
        let failure = match opened {
            Ok(_) => panic!("an archive written against another schema should be refused"),
            Err(e) => e,
        };
        assert!(failure.0.contains("disagrees with this build's schema"), "{}", failure.0);
    }
}
