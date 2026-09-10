//! Fill in the lane count of a junction stub from the road it interrupts.
//!
//! # The defect this exists for
//!
//! [`schema::roads::lane_count`](crate::schema::roads::lane_count) reads the OSM `lanes` tag and
//! nothing else, and a way that does not carry one gets zero — which the renderer turns into
//! `oneway ? 1 : 2`. That default is right for the untagged residential street it was written for.
//! It is wrong for the twenty metres of asphalt between the two carriageways of a divided
//! arterial, because a mapper who tagged `lanes=6` on the road either side very often left the
//! short piece in the middle untagged.
//!
//! The result reaches a screen as an intersection that is *narrower than the roads entering it*.
//! Measured at Broadway × Rollins Road in Burlingame, the case this was written for: Rollins is
//! 6.1 painted lanes west of the junction and 4.1 east of it, and **2.0 lanes through it** — the
//! narrowest pavement in the scene is the middle of the intersection. Way `417329335` is that
//! stub: nineteen metres, no `lanes`, no `lanes:forward`, no `lanes:backward`, sitting between
//! neighbours tagged 6 and 4.
//!
//! **This is not a defect in the extract.** [`crate::extract`] reads one OSM way to one feature
//! and never splits a way, so there is no `lanes` tag being dropped anywhere. The data genuinely
//! does not have one.
//!
//! # What this is, and what it is not
//!
//! **It is inference, and it must be read as inference.** Nothing here is tagged. What makes it
//! legitimate is not that the guess is good but that *the thing it replaces is also a guess* —
//! `oneway ? 1 : 2` is an invention with no more support in the data than this has, and it is a
//! worse one, because it ignores the two tagged measurements sitting at either end of the way it
//! is guessing about. Replacing a context-free invention with a context-sensitive one is the whole
//! of the claim. It is not a claim that the answer is right.
//!
//! Nothing here ever overrides a tag. A way that carries `lanes` keeps it.
//!
//! # What counts as the same road
//!
//! A way inherits only when **every** one of these holds. Each was measured against the whole San
//! Francisco peninsula extract — 27,287 drivable ways, of which 13,674 carry no `lanes` tag.
//!
//! * **It carries no `lanes` tag at all.** Never override data.
//! * **It has a name**, and the donor has the same one.
//! * **It is at most [`MAX_STUB_M`] long.**
//! * **A donor at *both* ends**, each collinear within [`MAX_TURN_DEGREES`] and agreeing on
//!   one-wayness.
//! * **The donor is itself tagged.** Not inherited — see "one hop" below.
//!
//! ## Why name *and* collinearity, rather than either
//!
//! | rule | candidates | of which the donor disagrees on one-wayness |
//! |---|---|---|
//! | same name only | 343 | 77 |
//! | collinear only | 393 | 77 |
//! | **both** | **290** | **40** |
//!
//! Neither alone is safe. Name alone joins a street to itself around a hard corner, which is a
//! different carriageway. Collinearity alone joins a street to whatever runs straight on through
//! the junction, and a road changing its name at a crossroads is ordinary. Requiring both is the
//! intersection of the two, and the last column is the evidence that it is the *right*
//! intersection rather than merely the smaller one: the disagreement rate on an independent
//! attribute nobody selected for — one-wayness — halves. Pairs that were never the same
//! carriageway are what that column counts, and requiring both conditions removes half of them.
//!
//! Matching on `ref` as well as `name` was measured and rejected: over the same extract it admits
//! exactly one further way, a 257 m motorway spur, which [`MAX_STUB_M`] excludes anyway.
//!
//! ## Why one-wayness has to agree
//!
//! `lanes` counts **both** directions. A two-way `lanes=6` inherited onto a one-way stub paints
//! six lanes running one way where the truth is about three, so the error is not a small
//! misjudgement but a doubling, in the direction that reads as a broken map. This is a
//! correctness condition and not a tuning knob; it costs 290 candidates down to 252.
//!
//! ## Why the minimum of the donors
//!
//! Over the same extract, at [`MAX_STUB_M`]: taking the minimum changes 113 ways by an average of
//! +1.33 lanes; taking the maximum changes 135 by +1.82. Both fix the reported defect. The
//! minimum is preferred because the two errors are not symmetric — too narrow reads as a cautious
//! map and too wide reads as a wrong one.
//!
//! Note the gap between 156 ways that qualify and 113 that change: where the minimum lands on the
//! value the default would have produced anyway, nothing is emitted. A rule that declines to speak
//! unless both of its neighbours jointly justify it is behaving correctly, not under-reaching.
//!
//! ## Why a length bound, and why in metres
//!
//! Candidate lengths run to a long tail — median 33.5 m but p90 202.7 m and a maximum of 804 m.
//! Unbounded, half a kilometre of El Camino Real quietly goes from the default 1 to 2, and 519 m
//! of San Bruno Avenue West from 1 to 3. That is no longer a junction stub; it is a road, and a
//! road acquiring a width from a neighbour it merely touches is the failure mode this bound
//! exists to prevent.
//!
//! [`MAX_STUB_M`] is 50 because of what is being modelled rather than where a percentile falls.
//! The subject is the pavement inside a junction, or the link between the two carriageways of a
//! divided road: [`schema::junction`](crate::schema::junction) already takes an intersection to be
//! about 14 m from its centre to its edge, so a box some 28 m across, and a divided-carriageway
//! link runs 15–30 m. Fifty clears that comfortably and sits well below the p75 of 99 m, so it
//! excludes the tail by construction. Relaxing 50 → 100 m buys 17 more changed ways; removing the
//! bound entirely buys 49 and admits the two above.
//!
//! **Metres, not node count.** A node count looks like a proxy for a short way and is not one: way
//! `23925587` in the same extract is a two-node way 497 m long.
//!
//! ## One hop, never a chain
//!
//! A donor must carry a real tag. An inherited count never becomes a donor, so a tagged way's
//! width can travel exactly one way and no further — a property of the rule's shape rather than a
//! limit someone has to remember to enforce. Chaining would also need iteration to a fixpoint
//! inside a pass whose output must be byte-identical at every thread count.
//!
//! # What is deliberately *not* touched
//!
//! Only the lane **count** — the width the carriageway is painted. Not
//! [`turn_masks`](crate::schema::roads::turn_masks) and not
//! [`carriageway`](crate::schema::roads::carriageway).
//!
//! A count is a physical property of the road that plainly continues across a junction. A turn
//! arrow and a directional split are surveys of a *particular* piece of asphalt, and they do not.
//! There is also a concrete trap: `osm_ingest::tags::build_dir_lanes` pads its mask list to the
//! lane count, so feeding an inferred count into the masks would grow the list and **draw turn
//! arrows that no one tagged**. Ways with `turn:lanes` and no `lanes` do exist — way `417976725`
//! on Rollins Road is one. Arrows stay strictly data-driven.
//!
//! # How it is computed
//!
//! A hash-partitioned external group-by, because the in-memory form does not survive contact with
//! a planet build. Every named road way contributes one record per endpoint; on the peninsula
//! extract that is tens of thousands, but scaled to a planet's ~70 M drivable ways it is of the
//! order of 4 GB against a build already peaking near 7 GB. Pre-filtering by node count was
//! measured and does not rescue it — it still holds ~1 GB while losing real candidates.
//!
//! So records go to [`PARTITIONS`] files, partitioned on the endpoint node id. Every way meeting
//! at a node lands in the same file, which is the only grouping the rule needs, and each file is
//! then sorted and grouped on its own. Peak memory is one partition rather than the whole planet.
//!
//! Recipients longer than [`MAX_STUB_M`] are dropped before they are ever written — an untagged
//! way is never a donor, so a long untagged way cannot affect anything and need not be stored.
//! That alone removes three quarters of the recipient records.
//!
//! Names are hashed to a `u64` rather than carried. A collision costs one wrong inheritance and
//! cannot cost more than that: the collinearity and one-wayness conditions still have to pass, and
//! the result is still bounded by [`MAX_STUB_M`] and by the minimum of the donors.

use std::fs::File;
use std::io::{BufWriter, Read, Write};
use std::path::{Path, PathBuf};

use osm_ingest::proto::{err, Error, Result};

/// The longest way that may inherit, in metres. See the module docs for why 50 and why metres.
pub const MAX_STUB_M: f64 = 50.0;

/// How far from straight a donor may leave the shared node and still be the same road, in degrees.
///
/// Zero is a perfect continuation. 45° was measured and rejected: it admits 17 more candidates and
/// brings 17 more one-wayness disagreements with them, which is the whole of what it buys.
pub const MAX_TURN_DEGREES: f64 = 30.0;

/// How many files the endpoint records are partitioned across.
///
/// Only peak memory depends on this — one partition is held at a time — so it wants to be large
/// enough that a planet's records divide into pieces of tens of megabytes, and small enough that
/// the open file handles and the write buffers behind them stay cheap.
pub const PARTITIONS: usize = 256;

/// Metres per degree of latitude. Spherical, matching [`crate::schema::junction`], and worth
/// centimetres over the tens of metres this module measures.
const METRES_PER_DEGREE: f64 = 111_320.0;

/// One endpoint record, fixed width so a partition is read back without a varint decoder.
const RECORD_LEN: usize = 30;

/// Bit 0 of a record's flag byte: the way is `oneway=yes`.
const FLAG_ONEWAY: u8 = 1 << 0;
/// Bit 1: this record is the way's *last* node rather than its first.
const FLAG_LAST_END: u8 = 1 << 1;

/// One named road way offered to the join.
///
/// `nodes` and `line` must be the same length: a way whose extract cut some of its nodes away has
/// coordinates that no longer line up with its refs, and its neighbours are as likely to be
/// missing as its geometry. Such a way is skipped rather than guessed at.
pub struct Segment<'a> {
    pub id: i64,
    /// The OSM `name`. A way without one cannot take part.
    pub name: &'a str,
    /// The way's tagged lane count. Zero — no `lanes` tag — is what makes it a recipient; anything
    /// else makes it a donor.
    pub lanes: u8,
    pub oneway: bool,
    pub nodes: &'a [i64],
    /// The way's coordinates in lon/lat, parallel to `nodes`.
    pub line: &'a [(f64, f64)],
}

#[derive(Clone, Copy)]
struct Record {
    node: i64,
    way: i64,
    name_hash: u64,
    /// Degrees clockwise from north, pointing *away* from `node` along the way.
    bearing: f32,
    lanes: u8,
    flags: u8,
}

impl Record {
    fn oneway(self) -> bool {
        self.flags & FLAG_ONEWAY != 0
    }

    fn end(self) -> u8 {
        u8::from(self.flags & FLAG_LAST_END != 0)
    }

    fn write(self, out: &mut impl Write) -> Result<()> {
        let mut buf = [0u8; RECORD_LEN];
        buf[0..8].copy_from_slice(&self.node.to_le_bytes());
        buf[8..16].copy_from_slice(&self.way.to_le_bytes());
        buf[16..24].copy_from_slice(&self.name_hash.to_le_bytes());
        buf[24..28].copy_from_slice(&self.bearing.to_le_bytes());
        buf[28] = self.lanes;
        buf[29] = self.flags;
        out.write_all(&buf).map_err(|e| Error(format!("cannot write a lanefill partition: {e}")))
    }

    fn read(buf: &[u8]) -> Record {
        let at = |a: usize, b: usize| -> [u8; 8] { buf[a..b].try_into().expect("8 bytes") };
        Record {
            node: i64::from_le_bytes(at(0, 8)),
            way: i64::from_le_bytes(at(8, 16)),
            name_hash: u64::from_le_bytes(at(16, 24)),
            bearing: f32::from_le_bytes(buf[24..28].try_into().expect("4 bytes")),
            lanes: buf[28],
            flags: buf[29],
        }
    }
}

/// Accumulates endpoint records, then resolves them into the ways whose lane count changes.
pub struct Collector {
    files: Vec<BufWriter<File>>,
    paths: Vec<PathBuf>,
}

impl Collector {
    /// Open the partition files. `stem` is a scratch path; each partition appends to it.
    pub fn create(stem: &Path) -> Result<Collector> {
        let mut files = Vec::with_capacity(PARTITIONS);
        let mut paths = Vec::with_capacity(PARTITIONS);
        for i in 0..PARTITIONS {
            let path = stem.with_extension(format!("lanefill{i:03}.tmp"));
            let file = File::create(&path)
                .map_err(|e| Error(format!("cannot create {}: {e}", path.display())))?;
            // Small buffers: there are `PARTITIONS` of them held open at once, and each takes a
            // steady trickle rather than a burst.
            files.push(BufWriter::with_capacity(1 << 14, file));
            paths.push(path);
        }
        Ok(Collector { files, paths })
    }

    /// Offer one way to the join. Ways that cannot take part are dropped here.
    pub fn push(&mut self, segment: &Segment) -> Result<()> {
        if segment.name.is_empty() || segment.nodes.len() < 2 {
            return Ok(());
        }
        if segment.nodes.len() != segment.line.len() {
            return Ok(());
        }
        let first = segment.nodes[0];
        let last = segment.nodes[segment.nodes.len() - 1];
        // A closed way has one endpoint, not two, so it can never have a donor at "both" ends and
        // is not a junction stub in any case.
        if first == last {
            return Ok(());
        }
        // An untagged way is never a donor, so one too long to be a recipient cannot affect
        // anything. Dropping it here rather than at resolve time is what keeps the spill small.
        if segment.lanes == 0 && polyline_length_m(segment.line) > MAX_STUB_M {
            return Ok(());
        }
        // The bearing leaving each end, skipping repeated coordinates: a duplicated vertex has no
        // direction, and the graph does contain them where a way was split at a coincident point.
        let Some(head) = leaving(segment.line, true) else { return Ok(()) };
        let Some(tail) = leaving(segment.line, false) else { return Ok(()) };

        let name_hash = hash_name(segment.name);
        let base = if segment.oneway { FLAG_ONEWAY } else { 0 };
        for (node, bearing, end) in [(first, head, 0u8), (last, tail, FLAG_LAST_END)] {
            let record = Record {
                node,
                way: segment.id,
                name_hash,
                bearing: bearing as f32,
                lanes: segment.lanes,
                flags: base | end,
            };
            record.write(&mut self.files[partition_of(node)])?;
        }
        Ok(())
    }

    /// Resolve every partition and return `(way id, inherited lane count)` for the ways that
    /// actually change, ascending by way id so the materialise pass can binary-search it.
    ///
    /// Ways whose inherited count equals the default they would have had anyway are left out, the
    /// same way [`crate::corridor::promote`] leaves out ways its corridor does not move.
    pub fn finish(mut self) -> Result<Vec<(i64, u8)>> {
        for file in &mut self.files {
            file.flush().map_err(|e| Error(format!("cannot flush a lanefill partition: {e}")))?;
        }
        drop(self.files);

        // `(way, end, donor lanes, one-way)`. The only state that outlives a partition, because a
        // way's two endpoints hash to two different files and the "donor at both ends" condition
        // cannot be decided until both have been seen.
        let mut found: Vec<(i64, u8, u8, bool)> = Vec::new();
        let mut records: Vec<Record> = Vec::new();
        for path in &self.paths {
            read_partition(path, &mut records)?;
            if records.is_empty() {
                continue;
            }
            // Sorted rather than hashed, for the reason `corridor::promote` gives: a hash map over
            // the record count is the largest thing this pass would otherwise allocate. The full
            // key makes the order total, so the output does not depend on the write order.
            records.sort_unstable_by_key(|r| (r.node, r.way, r.flags));
            for run in records.chunk_by(|a, b| a.node == b.node) {
                pair_up(run, &mut found);
            }
        }
        for path in &self.paths {
            let _ = std::fs::remove_file(path);
        }

        found.sort_unstable();
        let mut out: Vec<(i64, u8)> = Vec::new();
        for run in found.chunk_by(|a, b| a.0 == b.0) {
            if !run.iter().any(|e| e.1 == 0) || !run.iter().any(|e| e.1 == 1) {
                continue;
            }
            let inherited = run.iter().map(|e| e.2).min().unwrap_or(0);
            // What the renderer would have drawn without us: `tile/geometry.rs` turns a zero lane
            // count into one lane for a one-way and two for anything else.
            let default = if run[0].3 { 1 } else { 2 };
            if inherited != default {
                out.push((run[0].0, inherited));
            }
        }
        Ok(out)
    }
}

/// Every recipient in `run` against every donor, appending the pairs that survive the conditions.
fn pair_up(run: &[Record], found: &mut Vec<(i64, u8, u8, bool)>) {
    for recipient in run.iter().filter(|r| r.lanes == 0) {
        for donor in run.iter().filter(|d| d.lanes > 0) {
            if donor.way == recipient.way
                || donor.name_hash != recipient.name_hash
                || donor.oneway() != recipient.oneway()
            {
                continue;
            }
            if deviation(f64::from(recipient.bearing), f64::from(donor.bearing)) > MAX_TURN_DEGREES
            {
                continue;
            }
            found.push((recipient.way, recipient.end(), donor.lanes, recipient.oneway()));
        }
    }
}

fn read_partition(path: &Path, out: &mut Vec<Record>) -> Result<()> {
    out.clear();
    let mut file =
        File::open(path).map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?;
    let mut bytes = Vec::new();
    file.read_to_end(&mut bytes)
        .map_err(|e| Error(format!("cannot read {}: {e}", path.display())))?;
    if bytes.len() % RECORD_LEN != 0 {
        return err(format!(
            "{} holds {} bytes, which is not a whole number of {RECORD_LEN}-byte records",
            path.display(),
            bytes.len(),
        ));
    }
    out.reserve(bytes.len() / RECORD_LEN);
    for chunk in bytes.chunks_exact(RECORD_LEN) {
        out.push(Record::read(chunk));
    }
    Ok(())
}

/// Which partition an endpoint node belongs to.
///
/// Mixed rather than taken modulo directly: OSM node ids are allocated in runs, so the low bits of
/// neighbouring ids are correlated and a plain modulo would fill a few partitions and starve the
/// rest. This is the SplitMix64 finaliser, which is cheap and spreads the low bits.
fn partition_of(node: i64) -> usize {
    let mut z = (node as u64) ^ 0x9e37_79b9_7f4a_7c15;
    z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    ((z ^ (z >> 31)) % PARTITIONS as u64) as usize
}

/// FNV-1a over the name's bytes. See the module docs on why a collision is survivable.
fn hash_name(name: &str) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in name.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

/// The bearing leaving the line's first (or last) point, walking past repeated coordinates.
///
/// `None` when every point is the same place, which has no direction to report.
fn leaving(line: &[(f64, f64)], from_start: bool) -> Option<f64> {
    if from_start {
        let origin = *line.first()?;
        Some(bearing(origin, *line.iter().skip(1).find(|p| **p != origin)?))
    } else {
        let origin = *line.last()?;
        Some(bearing(origin, *line.iter().rev().skip(1).find(|p| **p != origin)?))
    }
}

/// Bearing from `a` to `b` in degrees clockwise from north. Both are lon/lat.
fn bearing(a: (f64, f64), b: (f64, f64)) -> f64 {
    let north = b.1 - a.1;
    let east = (b.0 - a.0) * a.1.to_radians().cos();
    east.atan2(north).to_degrees()
}

/// How far two bearings are from being a straight continuation of one another, in degrees.
///
/// Both point *away* from the node they share, so a road running straight through it leaves on two
/// bearings 180° apart and scores zero.
fn deviation(a: f64, b: f64) -> f64 {
    let mut delta = (a - b) % 360.0;
    if delta > 180.0 {
        delta -= 360.0;
    } else if delta < -180.0 {
        delta += 360.0;
    }
    (180.0 - delta.abs()).abs()
}

fn polyline_length_m(line: &[(f64, f64)]) -> f64 {
    line.windows(2)
        .map(|p| {
            let north = (p[1].1 - p[0].1) * METRES_PER_DEGREE;
            let east = (p[1].0 - p[0].0) * METRES_PER_DEGREE * p[0].1.to_radians().cos();
            (north * north + east * east).sqrt()
        })
        .sum()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Broadway × Rollins Road, Burlingame — the junction this module was written for.
    const JUNCTION: (f64, f64) = (-122.3622, 37.5889);

    fn offset(east_m: f64, north_m: f64) -> (f64, f64) {
        (
            JUNCTION.0 + east_m / (METRES_PER_DEGREE * JUNCTION.1.to_radians().cos()),
            JUNCTION.1 + north_m / METRES_PER_DEGREE,
        )
    }

    /// A straight way between two points on the local tangent plane, in metres east/north of the
    /// junction. `nodes` are given so tests can decide what touches what.
    struct Straight {
        nodes: [i64; 2],
        line: [(f64, f64); 2],
    }

    fn straight(from_node: i64, from: (f64, f64), to_node: i64, to: (f64, f64)) -> Straight {
        Straight {
            nodes: [from_node, to_node],
            line: [offset(from.0, from.1), offset(to.0, to.1)],
        }
    }

    fn segment<'a>(
        id: i64,
        name: &'a str,
        lanes: u8,
        oneway: bool,
        s: &'a Straight,
    ) -> Segment<'a> {
        Segment { id, name, lanes, oneway, nodes: &s.nodes, line: &s.line }
    }

    fn scratch(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!("mamaps_lanefill_{}_{name}", std::process::id()))
    }

    /// Push every segment and resolve. Takes the segments as a closure so the borrow of the
    /// `Straight`s outlives nothing awkward.
    fn run(name: &str, push: impl FnOnce(&mut Collector) -> Result<()>) -> Vec<(i64, u8)> {
        let mut collector = Collector::create(&scratch(name)).expect("partitions");
        push(&mut collector).expect("push");
        collector.finish().expect("resolve")
    }

    /// THE case this module exists for, to the real geometry: Rollins Road runs east-west across
    /// Broadway's two carriageways, is tagged 6 lanes on one side and 4 on the other, and the
    /// nineteen metres between the carriageways carries no `lanes` tag at all. Way 417329335.
    #[test]
    fn the_stub_between_two_carriageways_takes_the_narrower_neighbours_lane_count() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let stub = straight(2, (-10.0, 0.0), 3, (9.6, 0.0));
        let east = straight(3, (9.6, 0.0), 4, (120.0, 0.0));
        let out = run("reported", |c| {
            c.push(&segment(417_329_340, "Rollins Road", 6, false, &west))?;
            c.push(&segment(417_329_335, "Rollins Road", 0, false, &stub))?;
            c.push(&segment(504_486_258, "Rollins Road", 4, false, &east))
        });
        assert_eq!(
            out,
            vec![(417_329_335, 4)],
            "the stub inherits 4 - the minimum of its 6 and 4 neighbours - against a default of 2",
        );
    }

    /// The one condition that is not a heuristic. A way that states its own lane count keeps it,
    /// whatever its neighbours say, so this pass can never contradict a survey.
    #[test]
    fn a_way_that_carries_a_lanes_tag_is_never_touched() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let middle = straight(2, (-10.0, 0.0), 3, (10.0, 0.0));
        let east = straight(3, (10.0, 0.0), 4, (120.0, 0.0));
        let out = run("tagged", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            // Tagged 3, between neighbours tagged 6. Its own tag wins.
            c.push(&segment(11, "Rollins Road", 3, false, &middle))?;
            c.push(&segment(12, "Rollins Road", 6, false, &east))
        });
        assert!(out.is_empty(), "a tagged way is not a recipient: {out:?}");
    }

    /// A donor at one end is a road that happens to end here, not a road this way interrupts.
    /// Without both ends, every untagged street leaving a tagged one would widen.
    #[test]
    fn one_donor_is_not_enough() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let stub = straight(2, (-10.0, 0.0), 3, (10.0, 0.0));
        let out = run("oneend", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, false, &stub))
        });
        assert!(out.is_empty(), "no donor at the far end: {out:?}");
    }

    /// Collinearity alone is not the same road. A road changing its name at a crossroads is
    /// ordinary, and inheriting across the change would take a width from a different street.
    #[test]
    fn a_collinear_neighbour_with_another_name_is_a_different_road() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let stub = straight(2, (-10.0, 0.0), 3, (10.0, 0.0));
        let east = straight(3, (10.0, 0.0), 4, (120.0, 0.0));
        let out = run("names", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, false, &stub))?;
            c.push(&segment(12, "Carolan Avenue", 6, false, &east))
        });
        assert!(out.is_empty(), "the east arm is a different street: {out:?}");
    }

    /// The name alone is not the same road either: a street turning a right angle at a junction is
    /// a different carriageway, and one of the two is very often the wider.
    #[test]
    fn a_same_named_neighbour_round_a_hard_corner_is_a_different_carriageway() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let stub = straight(2, (-10.0, 0.0), 3, (10.0, 0.0));
        // Same name, touching the stub's east end, but leaving due north.
        let north = straight(3, (10.0, 0.0), 4, (10.0, 120.0));
        let out = run("corner", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, false, &stub))?;
            c.push(&segment(12, "Rollins Road", 6, false, &north))
        });
        assert!(out.is_empty(), "a 90-degree turn is not a continuation: {out:?}");
    }

    /// `lanes` counts both directions, so a two-way six inherited onto a one-way stub would paint
    /// six lanes running one way where the truth is about three. A doubling, not a rounding.
    #[test]
    fn a_one_way_stub_does_not_inherit_from_a_two_way_road() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let stub = straight(2, (-10.0, 0.0), 3, (10.0, 0.0));
        let east = straight(3, (10.0, 0.0), 4, (120.0, 0.0));
        let out = run("oneway", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, true, &stub))?;
            c.push(&segment(12, "Rollins Road", 6, false, &east))
        });
        assert!(out.is_empty(), "the donors are two-way and the stub is not: {out:?}");
    }

    /// The bound is what separates "the pavement inside a junction" from "a road". Without it,
    /// hundreds of metres of genuinely untagged road silently take a neighbour's width.
    #[test]
    fn a_road_too_long_to_be_a_junction_stub_does_not_inherit() {
        let west = straight(1, (-400.0, 0.0), 2, (-150.0, 0.0));
        let long = straight(2, (-150.0, 0.0), 3, (150.0, 0.0));
        let east = straight(3, (150.0, 0.0), 4, (400.0, 0.0));
        assert!(
            polyline_length_m(&long.line) > MAX_STUB_M,
            "the fixture has to be longer than the bound to test it",
        );
        let out = run("toolong", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, false, &long))?;
            c.push(&segment(12, "Rollins Road", 6, false, &east))
        });
        assert!(out.is_empty(), "300 m is a road, not a junction stub: {out:?}");
    }

    /// A donor must carry a real tag, so a width travels exactly one way and stops. Two untagged
    /// stubs in a row leaves each with a tagged donor at one end only, and neither inherits --
    /// which is what makes the blast radius a property of the rule rather than a limit to police.
    #[test]
    fn an_inherited_count_never_becomes_a_donor() {
        let west = straight(1, (-120.0, 0.0), 2, (-30.0, 0.0));
        let first = straight(2, (-30.0, 0.0), 3, (0.0, 0.0));
        let second = straight(3, (0.0, 0.0), 4, (30.0, 0.0));
        let east = straight(4, (30.0, 0.0), 5, (120.0, 0.0));
        let out = run("chain", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, false, &first))?;
            c.push(&segment(12, "Rollins Road", 0, false, &second))?;
            c.push(&segment(13, "Rollins Road", 6, false, &east))
        });
        assert!(out.is_empty(), "neither stub has a tagged donor at both ends: {out:?}");
    }

    /// Where the answer is the answer the default would have given, say nothing. Keeps the lookup
    /// table to the ways that actually move, the way `corridor::promote` does.
    #[test]
    fn a_stub_that_would_inherit_its_own_default_is_left_out() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let stub = straight(2, (-10.0, 0.0), 3, (10.0, 0.0));
        let east = straight(3, (10.0, 0.0), 4, (120.0, 0.0));
        let out = run("noop", |c| {
            c.push(&segment(10, "Rollins Road", 2, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, false, &stub))?;
            c.push(&segment(12, "Rollins Road", 2, false, &east))
        });
        assert!(out.is_empty(), "two lanes is what the default already draws: {out:?}");
    }

    /// The archive has to be byte-identical however the build is run, so the resolution cannot
    /// depend on the order ways arrived in -- which, with the records split across partitions and
    /// sorted inside them, is the property worth pinning rather than assuming.
    #[test]
    fn the_result_does_not_depend_on_the_order_ways_were_offered() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let stub = straight(2, (-10.0, 0.0), 3, (10.0, 0.0));
        let east = straight(3, (10.0, 0.0), 4, (120.0, 0.0));
        let forward = run("order_a", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, false, &stub))?;
            c.push(&segment(12, "Rollins Road", 5, false, &east))
        });
        let backward = run("order_b", |c| {
            c.push(&segment(12, "Rollins Road", 5, false, &east))?;
            c.push(&segment(11, "Rollins Road", 0, false, &stub))?;
            c.push(&segment(10, "Rollins Road", 6, false, &west))
        });
        assert_eq!(forward, backward);
        assert_eq!(forward, vec![(11, 5)], "the minimum of 6 and 5");
    }

    /// A closed way has one endpoint rather than two, so "a donor at both ends" is not a question
    /// that can be asked of it, and a loop is not a junction stub in any case.
    #[test]
    fn a_closed_way_is_not_a_stub() {
        let ring_nodes = [7i64, 7];
        let ring_line = [offset(0.0, 0.0), offset(0.0, 0.0)];
        let arm = straight(7, (0.0, 0.0), 8, (120.0, 0.0));
        let out = run("closed", |c| {
            c.push(&Segment {
                id: 11,
                name: "Rollins Road",
                lanes: 0,
                oneway: false,
                nodes: &ring_nodes,
                line: &ring_line,
            })?;
            c.push(&segment(10, "Rollins Road", 6, false, &arm))
        });
        assert!(out.is_empty(), "{out:?}");
    }

    /// A way whose extract cut some of its nodes away has coordinates that no longer line up with
    /// its refs, so its endpoints are not where they claim to be.
    #[test]
    fn a_way_whose_geometry_is_short_of_its_refs_is_skipped() {
        let mut collector = Collector::create(&scratch("cut")).expect("partitions");
        let nodes = [1i64, 2, 3];
        let line = [offset(-10.0, 0.0), offset(10.0, 0.0)];
        collector
            .push(&Segment {
                id: 11,
                name: "Rollins Road",
                lanes: 0,
                oneway: false,
                nodes: &nodes,
                line: &line,
            })
            .expect("push");
        assert!(collector.finish().expect("resolve").is_empty());
    }

    /// Both bearings point away from the node they share, so a straight-through road scores zero
    /// and the measure has to wrap correctly at north.
    #[test]
    fn deviation_is_zero_for_a_straight_continuation_and_wraps_at_north() {
        assert!(deviation(90.0, -90.0).abs() < 1e-9, "east meeting west runs straight through");
        assert!(deviation(10.0, -170.0).abs() < 1e-9, "and so does this, across the wrap");
        assert!((deviation(0.0, 0.0) - 180.0).abs() < 1e-9, "a doubling back is the far extreme");
        assert!((deviation(0.0, 90.0) - 90.0).abs() < 1e-9, "a right angle is 90 from straight");
        // Symmetric, because neither way of the pair is privileged.
        assert!((deviation(35.0, -120.0) - deviation(-120.0, 35.0)).abs() < 1e-9);
    }

    /// Peak memory is one partition, so an uneven spread is the thing that breaks. The hazard is
    /// not a run of consecutive ids -- those spread evenly under a plain modulo -- but a *strided*
    /// one, which a modulo folds onto a single partition. Node ids are handed out by editors and
    /// import scripts, so a stride sharing a factor with [`PARTITIONS`] is not exotic.
    #[test]
    fn a_strided_run_of_node_ids_still_spreads_across_the_partitions() {
        let mut hit = vec![0u32; PARTITIONS];
        for k in 0..100_000i64 {
            hit[partition_of(1_000_000_000 + k * PARTITIONS as i64)] += 1;
        }
        assert_eq!(
            hit.iter().filter(|n| **n > 0).count(),
            PARTITIONS,
            "a plain modulo would have put all 100,000 in one partition",
        );
        let (low, high) = (*hit.iter().min().unwrap(), *hit.iter().max().unwrap());
        // 100,000 over 256 averages 390. Within a factor of two of each other is far tighter than
        // a fold-onto-one failure and loose enough not to pin the hash's exact output.
        assert!(high < low * 2, "the spread is even: {low}..{high}");
    }
}
