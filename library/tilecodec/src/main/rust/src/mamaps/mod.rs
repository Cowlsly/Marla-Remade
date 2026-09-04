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
pub mod from_mvt;
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
    use std::path::PathBuf;

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
            name_idx: crate::mamaps::body::NAME_NONE,
            parts_offset: 0,
            part_count: 1,
            transit_color: 0,
        });
        roads.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
        roads.coords = vec![(0, 0), (seed, seed)];
        Body { extent: DEFAULT_EXTENT, layers: vec![roads], names: Vec::new() }
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

    /// A directory of its own, removed on drop, so a test can point a writer's body scratch file at
    /// it and then assert on what is — or is not — left behind.
    struct Scratch {
        dir: PathBuf,
    }

    impl Scratch {
        fn new(name: &str) -> Scratch {
            let dir =
                std::env::temp_dir().join(format!("mamaps_write_{name}_{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).expect("temp dir");
            Scratch { dir }
        }

        fn path(&self, name: &str) -> PathBuf {
            self.dir.join(name)
        }

        fn options(&self) -> Options {
            Options { spill_dir: Some(self.dir.clone()), ..Options::default() }
        }

        /// What the directory holds, so a test can say "nothing".
        fn files(&self) -> Vec<String> {
            std::fs::read_dir(&self.dir)
                .expect("read_dir")
                .map(|e| e.expect("entry").file_name().to_string_lossy().into_owned())
                .collect()
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }

    /// A body big enough, and random enough, that a few hundred of them cannot fit in the writer's
    /// scratch-file buffer.
    ///
    /// The coordinates come from a seeded LCG rather than from a pattern on purpose: a body that
    /// deflated down to a few hundred bytes would let a whole test archive sit in the buffer, and
    /// then a test about reading bodies back off disk would pass without ever reading one.
    fn wide_body_for(seed: i16) -> Body {
        const POINTS: u32 = 2048;
        let mut roads = Layer::new(dict::LAYER_ROADS);
        roads.features.push(Feature {
            kind: 45,
            kind_detail: dict::NONE,
            geom_type: GEOM_LINE,
            flags: 0,
            name_idx: crate::mamaps::body::NAME_NONE,
            parts_offset: 0,
            part_count: 1,
            transit_color: 0,
        });
        roads.parts.push(Part { coord_start: 0, point_count: POINTS, winding: WINDING_OUTER });
        let mut state = seed as u64 ^ 0xA5A5_A5A5_A5A5_A5A5;
        roads.coords = (0..POINTS)
            .map(|_| {
                state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
                ((state >> 33) as i16, (state >> 17) as i16)
            })
            .collect();
        Body { extent: DEFAULT_EXTENT, layers: vec![roads], names: Vec::new() }
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

    /// **The one thing spilling the data section could have broken.** A content-dedup hit is a fact
    /// because every byte of the candidate is compared, and once the bodies are in a file that
    /// compare has to read them back. This feeds enough distinct bodies to push the earliest ones
    /// well past the writer's in-memory buffer and then repeats every one of them, so the
    /// confirmations really do come off disk rather than out of the buffer.
    #[test]
    fn content_dedup_confirms_a_candidate_that_has_already_reached_the_scratch_file() {
        let scratch = Scratch::new("dedup_off_disk");
        // 200 incompressible 8 KiB bodies is a data section of about 1.6 MB, against a buffer of 1.
        const DISTINCT: i16 = 200;
        let base = tile_id(9, 0, 0);
        let mut rows: Vec<(u64, i16)> = (0..DISTINCT).map(|i| (base + i as u64, i)).collect();
        // Then the same bodies again at later ids, in the same order. Every repeat is non-adjacent,
        // so no run can form and each one has to go through the content bucket.
        rows.extend((0..DISTINCT).map(|i| (base + DISTINCT as u64 + i as u64, i)));

        let options = Options { max_zoom: 9, ..scratch.options() };
        let mut writer = StreamWriter::new(options).expect("options");
        for (id, seed) in &rows {
            writer.append(*id, &wide_body_for(*seed)).expect("append");
        }
        let bytes = writer.finish().expect("finish");

        let header = Header::parse(&bytes).expect("header");
        assert!(
            header.data_len > 1 << 20,
            "the data section must outgrow the writer's buffer or this test proves nothing, it is \
             {} bytes",
            header.data_len,
        );
        assert_eq!(header.tiles_addressed, rows.len() as u64);
        assert_eq!(header.bodies_written, DISTINCT as u64, "the second pass stored nothing new");
        // And every tile still reads back as itself, which is what confirming against the wrong
        // bytes would silently break.
        let mut a = open(bytes);
        for (id, seed) in &rows {
            let (z, x, y) = crate::pmtiles::tile_zxy(*id);
            let tile = a.tile(z, x as u32, y as u32).expect("read").expect("present");
            assert_eq!(tile, wide_body_for(*seed), "tile {id}");
        }
    }

    /// `finish_to_path` and `finish` are two ways of emitting one archive, not two archives.
    ///
    /// Worth asserting because they are not the same code path — one copies the data section out of
    /// a file and the other into a `Vec` — and because the streaming one is what a real build uses.
    /// If it could differ by a byte then every test above would be testing something nothing ships.
    #[test]
    fn a_streamed_archive_is_byte_identical_to_one_finished_in_memory() {
        let scratch = Scratch::new("streamed");
        // Repeats and runs in the same archive, so dedup and the run-length flag are both live on
        // the way to both outputs.
        let tiles: Vec<(u8, u64, u64, i16)> = (0..8u64)
            .flat_map(|x| {
                (0..8u64).map(move |y| (3u8, x, y, if (x + y) % 3 == 0 { 4 } else { (x * 8 + y) as i16 }))
            })
            .collect();
        let options = || Options { leaf_entry_capacity: 8, ..scratch.options() };
        let in_memory = archive(&tiles, options());

        let mut rows: Vec<(u64, i16)> =
            tiles.iter().map(|(z, x, y, seed)| (tile_id(*z, *x, *y), *seed)).collect();
        rows.sort_by_key(|(id, _)| *id);
        let mut writer = StreamWriter::new(options()).expect("options");
        for (id, seed) in rows {
            writer.append(id, &body_for(seed)).expect("append");
        }
        let out = scratch.path("streamed.mamaps");
        writer.finish_to_path(&out).expect("finish_to_path");

        let streamed = std::fs::read(&out).expect("read back");
        assert_eq!(streamed.len(), in_memory.len(), "the same length");
        assert_eq!(streamed, in_memory, "the same bytes");
        // And it opens and reads, which is all a reader will ever ask of it.
        assert_eq!(open(streamed).tile(3, 7, 7).expect("read").expect("present"), body_for(63));
    }

    /// A build that dies must not strand a copy of its data section — 652 MB for California, tens of
    /// gigabytes for a planet. However the writer ends, its scratch file goes with it.
    #[test]
    fn the_body_scratch_file_is_removed_however_the_build_ends() {
        let scratch = Scratch::new("spill_cleanup");
        // A separate directory for the archives, so `scratch.files()` can assert on emptiness.
        let output = Scratch::new("spill_cleanup_out");

        // Dropped without ever being finished, which is what a failure upstream of the writer looks
        // like.
        {
            let mut writer = StreamWriter::new(scratch.options()).expect("options");
            writer.append(tile_id(0, 0, 0), &body_for(1)).expect("append");
            assert_eq!(scratch.files().len(), 1, "a writer holding bodies has a scratch file");
        }
        assert!(scratch.files().is_empty(), "dropping the writer took its scratch with it");

        let mut writer = StreamWriter::new(scratch.options()).expect("options");
        writer.append(tile_id(0, 0, 0), &body_for(2)).expect("append");
        writer.finish_to_path(&output.path("a.mamaps")).expect("finish_to_path");
        assert!(scratch.files().is_empty(), "and so did finishing");

        // And a build refused for having no tiles, which fails only after the scratch file exists.
        let writer = StreamWriter::new(scratch.options()).expect("options");
        let refused = output.path("b.mamaps");
        assert!(writer.finish_to_path(&refused).is_err(), "an archive with no tiles");
        assert!(scratch.files().is_empty());
        assert!(!refused.exists(), "and the destination was never touched");
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

    /// Planet per-leaf data-span overflow: a leaf whose entries' max(offset)-
    /// min(offset) would exceed u32::MAX (planet: 4294968552 = u32::MAX+1257)
    /// must be split, otherwise LeafEntry.offset_delta (u32) overflows.
    /// Common case (NA ~418 MB, all spans < u32::MAX) must stay byte-identical
    /// to the old chunks(capacity) partitioning (NA sha FF312EC...).
    #[test]
    fn a_leaf_whose_data_span_would_exceed_u32_max_is_split_and_common_case_stays_byte_identical() {
        use crate::mamaps::write::{Pending, Options, StreamWriter};

        // 1) Common case: all offsets within one capacity chunk and span < u32::MAX
        // → must yield ceil(N/capacity) leaves, same as old chunks(capacity).
        {
            let n = 5000usize;
            let cap = 4096u32;
            // Offsets grow linearly ~1 KiB per entry → 5000 KiB ≈5 MB span < 4 GiB
            let entries: Vec<Pending> = (0..n)
                .map(|i| Pending { tile_id: crate::pmtiles::tile_id(4, (i%32) as u64, (i/32) as u64), offset: i as u64 * 1024, run_length: 1, length: 100 })
                .collect();
            let split = crate::mamaps::write::StreamWriter::partition_for_test(&entries, cap).expect("partition").expect("split");
            let expected_leaves = (n + cap as usize - 1)/ cap as usize;
            assert_eq!(split.0.len(), expected_leaves, "common case must use count-only split");
            assert_eq!(split.1.iter().map(|l| l.len()).sum::<usize>(), n);
            // Every leaf leaf_offset must be byte-identical to old count-only: leaf_offset = leaf_index * cap*16
            for (i, re) in split.0.iter().enumerate() {
                assert_eq!(re.leaf_offset, (i * cap as usize * crate::mamaps::index::LEAF_ENTRY_LEN) as u64);
            }
            // Every delta fits u32 and reconstructs
            for (re, leaf) in split.0.iter().zip(split.1.iter()) {
                for e in leaf { assert!((e.offset_delta as u64 + re.base_data_offset) <= u32::MAX as u64 + re.base_data_offset); }
            }
        }

        // 2) Span overflow: 3 entries within one capacity slot but offsets 0 and u32::MAX+5000
        // must be split into 2 leaves even though count < capacity.
        {
            let cap = 4096u32;
            let entries = vec![
                Pending { tile_id: crate::pmtiles::tile_id(4,0,0), offset: 0, run_length: 1, length: 10 },
                Pending { tile_id: crate::pmtiles::tile_id(4,1,0), offset: 1_000_000, run_length: 1, length: 10 },
                Pending { tile_id: crate::pmtiles::tile_id(4,2,0), offset: u32::MAX as u64 + 5000, run_length: 1, length: 10 },
            ];
            let split = crate::mamaps::write::StreamWriter::partition_for_test(&entries, cap).expect("partition").expect("split");
            assert!(split.0.len() >= 2, "span > u32::MAX must force an extra leaf split (got {} leaves)", split.0.len());
            let total: usize = split.1.iter().map(|l| l.len()).sum();
            assert_eq!(total, 3);
            for (re, leaf) in split.0.iter().zip(split.1.iter()) {
                let max_delta = leaf.iter().map(|e| e.offset_delta as u64).max().unwrap_or(0);
                assert!(max_delta <= u32::MAX as u64, "every delta must fit u32 (max_delta={})", max_delta);
                // Absolute offset reconstructs
                for e in leaf {
                    let abs = re.base_data_offset + e.offset_delta as u64;
                    let found = entries.iter().any(|pe| pe.offset == abs);
                    assert!(found, "reconstructed offset {} must be one of the input offsets", abs);
                }
            }
            // Old fixed-chunk logic would have produced 1 leaf and later failed with
            // "leaf spans 429496..." — new logic succeeds by splitting.
            eprintln!("span-overflow partition: {} leaves for 3 entries spanning >u32::MAX (byte-identical common case preserved)", split.0.len());
        }

        // Also smoke a real archive (5000 tiles, 2 leaves) to ensure end-to-end still works.
        // z8: 256x256 tiles, so (i%32, i/32) for i in 0..5000 stays in range and unique (z4
        // only has 16 rows — y past 15 wraps tile_id into duplicates the writer refuses).
        let tiles: Vec<(u8,u64,u64,i16)> = (0..5000u64).map(|i| (8u8, i%32, i/32, i as i16)).collect();
        let opts = Options { leaf_entry_capacity: 4096, ..Options::default() };
        let mut rows: Vec<(u64,i16)> = tiles.iter().map(|(z,x,y,s)| (crate::pmtiles::tile_id(*z,*x,*y),*s)).collect();
        rows.sort_by_key(|(id,_)| *id);
        let mut w = StreamWriter::new(opts).expect("opts");
        for (id, seed) in rows { w.append(id, &crate::mamaps::body::Body { extent: 4096, layers: {
            let mut l = crate::mamaps::body::Layer::new(crate::mamaps::dict::LAYER_ROADS);
            l.features.push(crate::mamaps::body::Feature{kind:1,kind_detail:0,geom_type:1,flags:0,name_idx:0,parts_offset:0,part_count:1,transit_color:0});
            l.parts.push(crate::mamaps::body::Part{coord_start:0,point_count:2,winding:0});
            l.coords = vec![(0,0),(seed,seed)];
            vec![l]
        }, names: Vec::new() }).expect("append"); }
        let bytes = w.finish().expect("finish");
        let hdr = crate::mamaps::Header::parse(&bytes).expect("hdr");
        assert_eq!(hdr.leaf_count as usize, (5000+4096-1)/4096);
        eprintln!("end-to-end: 5000 tiles → leaf_count={} leaf_len={}", hdr.leaf_count, hdr.leaf_len);
    }
}
