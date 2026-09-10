//! `junction`: one line per **lane connector** through an intersection.
//!
//! A lane connector is the path a single lane follows from an approach into an exit — the left-turn
//! lane's arc, the through lane's stripe, the slip road peeling off a motorway. The renderer draws
//! each as a one-lane ribbon and does no curve maths, so every feature here is an already-sampled
//! polyline rather than a control-point set.
//!
//! Geometry comes from the same v6 routing graph [`crate::schema::traffic`] reads —
//! `metadata.bin`, `nodes.bin`, `edges.bin`, `intermediate.bin`, plus `lanes.bin` — and that module
//! is where the on-disk format, the escape tables and the polyline decode are documented. This one
//! only adds what a connector needs on top: which nodes are junctions, which way each incident edge
//! points, and which lane goes where.
//!
//! # What is data and what is inference
//!
//! **Be clear about this, because most of it is inference.** OSM has essentially no lane
//! connectivity data. `connectivity` relations exist but are vanishingly rare and nothing in this
//! tree reads them. So:
//!
//! | Thing | Where it comes from |
//! |---|---|
//! | Which nodes are junctions | **Data.** The generator collapses degree-2 chains, so a surviving node of degree ≥ 3 is a real fork. |
//! | Where each approach and exit runs | **Data.** Node coordinates and edge polylines. |
//! | Approach and exit headings | **Data**, derived: `atan2` over the first geometry step, because the graph stores no headings. |
//! | Which movements are legal | **Inference.** Every exit but the U-turn back where you came from is assumed legal. The graph carries no turn restrictions. |
//! | A lane's turn indication, where `turn:lanes` is tagged | **Data.** `lanes.bin`, keyed by directed edge, ordered left→right. |
//! | A lane's turn indication everywhere else | **Inference**, and this is the overwhelming majority of roads. |
//! | How many lanes an arm has, absent `turn:lanes` | **Inference**, and the weakest link. An approach is assumed to have one lane per legal exit; an exit, one lane per approach that may legally feed it. The same rule read from either end — [`effective_lanes`]. |
//! | Which lane of the exit a movement lands in | **Inference.** The convention in [`exit_lane`], stated below. |
//! | Lane width | **Neither, and worth knowing which.** [`LANE_WIDTH_MERCATOR_M`] is not a road measurement — it is tuned to the width the renderer paints a lane at. |
//! | The setback the arc starts at | **Neither.** [`SETBACK_M`] is a nominal guess at how big an intersection is. |
//!
//! Motorway exits are the case this should look good on: they are comparatively well tagged, they
//! are geometrically simple, and a slip road's heading separates cleanly from the mainline's.
//! Ordinary urban intersections are a heuristic and will need tuning against screenshots. Nothing
//! here should be read as knowing where the paint is.
//!
//! # Both ends of a connector are placed by the same rule
//!
//! A connector runs from a lane to a lane, so both ends need a lane index and a lane count, and
//! the two must come from the same place: [`effective_lanes`] for the count and [`lane_offset_m`]
//! for the offset, called once per end. That is not a stylistic preference — it is the bug this
//! section exists to prevent. The exit end used to keep whatever `lanes.bin` left on its [`Arm`],
//! which for an untagged road is 1, so [`exit_lane`] returned lane 0 for every movement and every
//! connector entering an arm terminated on one identical point. A degree-`d` junction emitted the
//! correct `d * (d - 1)` connectors and only `d` distinct endpoints; a dozen ribbons converging on
//! four pencil points is the tangle of hairlines that reached a device.
//!
//! An exit's inferred count is the mirror of an approach's and is computed **once per junction,
//! not once per approach**. Per approach it would vary with whoever happened to be looking at the
//! arm, which at an asymmetric junction is the same drift one level down.
//!
//! ## Which lane of the exit a movement lands in
//!
//! With `N` approach lanes and `M` exit lanes, for the group of approach lanes making the *same*
//! movement into the *same* exit, ranked `0..r` left→right:
//!
//! - **Through and reverse** hold position: `floor(in_lane * M / N)`, clamped. Identity when
//!   `N == M`, which is what a straight-through wants and is what lets it draw as a straight line
//!   rather than a slight S.
//! - **Left turns** fill the exit from its left: `min(rank, M - 1)`.
//! - **Right turns** fill the exit from its right: `M - 1 - min(r - 1 - rank, M - 1)`.
//!
//! The rank is what makes a tagged dual left turn two ribbons in two lanes instead of two ribbons
//! on one point. A single-lane movement has `r == 1` and reduces to "the leftmost" or "the
//! rightmost".
//!
//! **This is convention, not data, and it is written down so it can be argued with.** Nothing in
//! the graph says which lane feeds which.
//!
//! ## The inferred count is not the painted width
//!
//! Worth knowing before reading a screenshot, because it looks like a bug and is not this one. The
//! carriageway renderer's own fallback for an untagged road is one lane each way — `lane_count 0`
//! becomes `oneway ? 1 : 2` across *both* directions, in `tile/geometry.rs`. The inference here is
//! wider than that at any junction of degree 4 or more, so at an untagged crossroads the
//! connectors fan across more lanes than there is asphalt painted.
//!
//! That is also the whole of why the furthest a connector reaches from the node grows with the
//! junction's degree — about 14.5 m at degree 3 against 20.9 m at degree 8. [`SETBACK_M`] is
//! constant and additionally clamped to a fraction of each arm's own length; what grows is the
//! lateral fan, whose outermost lane sits at `(count - 0.5)` widths and whose `count` is
//! `degree - 1`. It is the fan widening, not the setback lengthening, and it is a property of the
//! lane-count inference at *both* ends rather than a defect in either. Narrowing it means capping
//! the inference at what is painted, which collapses the approach fan too and is a separate
//! argument from this one.
//!
//! # Lateral offset is baked in projected units, not ground metres
//!
//! Each connector's polyline already carries its lane's offset across the carriageway. That offset
//! is expressed as a fixed number of **Web Mercator** units — spelled here as the ground metres a
//! lane occupies *at the equator*, which is the same thing — and **not** as ground metres at the
//! junction's own latitude.
//!
//! This is structural rather than cosmetic. The carriageway ribbon's lane width is a screen-space
//! quantity: a dp ramp turned into a half-width in pixels and pushed per draw. Web Mercator's scale
//! carries a `1 / cos φ` term, so a fixed ground distance is a *different* number of pixels at
//! every latitude. Measured against the style's painted lane, 3.5 m of ground comes out at 0.97 of
//! a lane at the equator, 0.76 at 38°N and 0.48 at 60°N — so a naively baked ground offset lands
//! 2.3× too far out in Scandinavia, which is more than two lane widths and puts the connector off
//! the road it is meant to join.
//!
//! No zoom gate can rescue that, because `cos φ` is not a function of zoom. Scaling the offset by
//! `cos φ` before it is converted to degrees cancels the projection's stretch exactly, costs one
//! multiply, and needs no wire field and no shader change. The along-road setback is deliberately
//! *not* scaled: [`SETBACK_M`] is a real distance on the ground, because the intersection it spans
//! and the road geometry it must meet are both real distances on the ground.
//!
//! ## The projection correction is exact; the lane width is not
//!
//! "Exactly" above is a claim about the projection and nothing else: `cos φ` cancels Mercator's
//! `1 / cos φ`, which is pure geometry and leaves nothing over at any latitude. It is *not* a
//! claim that a connector lands on the paint. Read as one it is actively dangerous, because a
//! reader who believes the whole thing is exact has no reason to expect a residual, and on meeting
//! one will look for it in the projection — where it is not — and close it by putting a `cos φ`
//! back somewhere. That is the bug this section exists to prevent.
//!
//! A second and smaller discrepancy does survive, and it is not projection error.
//! [`LANE_WIDTH_MERCATOR_M`] is a single number, while the width the renderer actually paints a
//! lane at comes from the style's dp ramp, which is not linear in ground metres — so the two agree
//! at one zoom and drift either side of it. The ramp is `roads-carriageway`'s `width` in
//! `basemap.flat.json` — exponential, base 2.0, over stops
//! `[[16, 3.0], [18, 11.0], [20, 40.0], [22, 160.0]]`, one lane's width in dp. Read at the
//! equator, where the `cos φ` term is already 1 and the ramp is therefore read cleanly on its
//! own, and against a 512 dp tile, a painted lane is worth about 3.40 m of ground at z17 falling
//! to 2.98 m by z20, against a fixed 3.0 m of connector pitch: roughly 12% narrow at z17,
//! crossing over to about 1% wide by z20. Those figures are reproducible from those stops rather
//! than asserted. The change of sign is the part that matters: no single percentage describes the
//! residual, and anyone who reads "the connector is N% wider" will reach for a correction that is
//! wrong at one end of the zoom range. That the fit crosses zero at all is the point — it is
//! centred across the zoom band rather than biased to one end, which is what fitting on absolute
//! dp instead of relative error buys. The residual is also latitude-independent, which together
//! with the drift in zoom is the signature of a ramp mismatch rather than of a projection.
//!
//! **It cannot be closed by a projection term, because it is not a projection error.** The only
//! two levers on it are [`LANE_WIDTH_MERCATOR_M`] and the style's width ramp; the constant is
//! already fitted against that ramp, and where the residual is deliberately put — in percent
//! rather than in pixels — is argued at the constant. A `cos φ` introduced to chase it would be 1
//! at the equator, which is exactly where the residual was measured, and wrong everywhere else.

use std::collections::BTreeSet;
use std::path::Path;

use osm_ingest::proto::{err, Result};
use tile_build::geom::Geometry;
use tilecodec::mamaps::body::{
    LANE_LEFT, LANE_NONE, LANE_REVERSE, LANE_RIGHT, LANE_SHARP_LEFT, LANE_SHARP_RIGHT,
    LANE_SLIGHT_LEFT, LANE_SLIGHT_RIGHT, LANE_THROUGH,
};
use tilecodec::mamaps::dict::{self, LAYER_JUNCTION};

use super::boundaries::Conventions;
use super::traffic::{Graph, ROAD_TYPE_MASK};
use super::Class;
use crate::store::Sink;

/// The shallowest zoom lane connectors are **tiled** into.
///
/// This is a tiling gate and nothing else, so it is bounded above by the deepest zoom an archive
/// is built to — `--max-zoom`, which defaults to [`crate::DEFAULT_MAX_ZOOM`]. Set past that there
/// is no tile deep enough to hold a connector, so every one of them is computed and then dropped,
/// and the layer is missing from the archive entirely while the build still reports success.
///
/// 14 is that bound, which puts connectors in the deepest tiles only and is the least size they
/// can cost. The zoom they are *drawn* from is a separate gate living in the style — the
/// `junction-connector` layer's `minzoom`, which is 16 — and the renderer overzooms the z14 tile
/// above it. That split is how `roads-lanes` already carries lane detail.
pub const MIN_ZOOM: u8 = 14;

/// Width of one lane, in **Web Mercator metres** — ground metres at the equator, which at any
/// other latitude is a smaller ground distance and the same projected one. That is what makes it a
/// fixed number of pixels everywhere; see the module docs, where expressing it as ground metres at
/// the junction's own latitude is what puts a connector two lanes off the road at 60°N.
/// [`lane_width_ground_m`] converts it for a given latitude.
///
/// **3.0, not the 3.5 a real lane is designed to, and that is deliberate — do not "correct" it.**
/// This is not a measurement of a road. It is fitted to the width the renderer actually paints a
/// lane at, which is a dp ramp and is not linear in ground metres. Measured against that ramp, a
/// lane is worth about 3.40 m of ground at z17 falling to 2.98 m by z20, so no single constant is
/// right at every zoom and the only question is where to put the residual.
///
/// It is fitted on **absolute misalignment in dp, not relative error**, and the two disagree: a dp
/// is 1.7 per ground metre at z17 and 13.4 at z20, so the same percentage costs eight times the
/// pixels at the deep end. 3.2 minimises worst-case percentage but leaves ~5 dp of visible gap at
/// z20; 3.0 is a worse percentage fit and keeps the gap near a pixel at every zoom, which is the
/// one that cannot be seen. Percentage error is not what a viewer looks at.
pub const LANE_WIDTH_MERCATOR_M: f64 = 3.0;

/// [`LANE_WIDTH_MERCATOR_M`] as ground metres at `lat_degrees`, which is a lane's width shrinking
/// toward the poles exactly as fast as Mercator stretches it. Exact as a statement about the
/// projection — `cos φ` against `1 / cos φ`, with no approximation in it — and only that: it says
/// nothing about whether the result matches the painted lane, which is the ramp residual the
/// module docs describe and is not fixable here.
fn lane_width_ground_m(lat_degrees: f64) -> f64 {
    LANE_WIDTH_MERCATOR_M * lat_degrees.to_radians().cos().abs().max(1e-6)
}

/// How far back from the junction node a connector starts, and how far past it the connector ends.
///
/// The node is a point; a real intersection is a box some tens of metres across. This is the
/// half-width that box is assumed to have, so a connector spans roughly `2 * SETBACK_M` of ground.
/// Clamped per edge to a fraction of that edge's own length, so a short block does not produce a
/// connector running past the next junction.
const SETBACK_M: f64 = 14.0;

/// The most of an incident edge's length a connector may consume at either end.
const SETBACK_EDGE_FRACTION: f64 = 0.4;

/// Below this the connector is shorter than a lane is wide and draws as a blob.
const MIN_SETBACK_M: f64 = 2.0;

/// Bezier handle length as a fraction of the setback.
///
/// 0.55 is the usual circular-arc approximation constant; it makes the curve leave the approach and
/// meet the exit tangentially without bulging past the intersection box.
const HANDLE: f64 = 0.55;

/// Metres per degree of latitude. Spherical, which is the same approximation
/// [`tile_build::geom::project`] makes and is worth centimetres over a connector's length.
const METRES_PER_DEGREE: f64 = 111_320.0;

/// The class every lane connector carries: layer `junction`, no kind and no detail.
///
/// No `kind`/`kind_detail` for the same reason [`crate::schema::traffic::traffic_class`] has none:
/// the basemap style does not draw this layer, the carriageway renderer does, and it selects the
/// whole layer rather than filtering within it. Keeping the name out of [`dict::KINDS`] avoids
/// disturbing the frozen kind table for a value nothing matches by name.
pub fn junction_class() -> Class {
    Class {
        layer: LAYER_JUNCTION,
        kind: dict::NONE,
        kind_detail: dict::NONE,
        flags: 0,
        area: false,
        min_zoom: MIN_ZOOM,
        min_area_px: 0.0,
    }
}

// --- lanes.bin ------------------------------------------------------------------------------

/// `lanes.bin`: `[u32 n][(u32 edge_idx, u32 blob_off) x (n + 1)][u16 mask blob]`.
///
/// **Sparse**: only edges that carry a `turn:lanes` tag appear, ascending by `edge_idx`, so absence
/// from the index is how "this edge has no lane data" is spelled. Mirrors
/// `maps/src/main/rust/src/graph.rs::edge_lane_masks`; the trailing sentinel entry exists only to
/// give the last real edge a length.
///
/// The whole file is read rather than mapped, matching how [`Graph`] reads the rest of the graph.
struct LaneTable {
    raw: Vec<u8>,
    count: u32,
    blob_off: usize,
}

impl LaneTable {
    /// Read `lanes.bin`, or `None` when the graph was built without lane data. A missing file is
    /// not an error — every connector then falls back to the inferred path, which is what happens
    /// for most edges even when the file is present.
    fn load(dir: &Path) -> Result<Option<LaneTable>> {
        let path = dir.join("lanes.bin");
        let raw = match std::fs::read(&path) {
            Ok(raw) => raw,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return err(format!("cannot read {}: {e}", path.display())),
        };
        if raw.len() < 4 {
            return err("lanes.bin is too short to hold its entry count".to_string());
        }
        let count = u32::from_le_bytes([raw[0], raw[1], raw[2], raw[3]]);
        // n + 1 entries: the trailing sentinel is what gives the last edge an end offset.
        let index_bytes = 4usize
            .checked_add((count as usize + 1).saturating_mul(8))
            .ok_or_else(|| osm_ingest::proto::Error("lanes.bin index size overflows".to_string()))?;
        if raw.len() < index_bytes {
            return err(format!(
                "lanes.bin is {} bytes, too short for its {count}-entry index",
                raw.len(),
            ));
        }
        Ok(Some(LaneTable { raw, count, blob_off: index_bytes }))
    }

    fn entry(&self, i: u32) -> (u32, u32) {
        let at = 4 + (i as usize) * 8;
        let edge = u32::from_le_bytes([self.raw[at], self.raw[at + 1], self.raw[at + 2], self.raw[at + 3]]);
        let off = u32::from_le_bytes([
            self.raw[at + 4],
            self.raw[at + 5],
            self.raw[at + 6],
            self.raw[at + 7],
        ]);
        (edge, off)
    }

    /// The per-lane `LANE_*` masks for directed edge `idx`, ordered left→right, or `None` when it
    /// carries none. Binary search, because the index is sparse.
    fn masks(&self, idx: u32) -> Option<Vec<u16>> {
        let (mut lo, mut hi) = (0u32, self.count);
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            if self.entry(mid).0 < idx {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if lo >= self.count || self.entry(lo).0 != idx {
            return None;
        }
        let start = self.blob_off + self.entry(lo).1 as usize;
        let end = self.blob_off + self.entry(lo + 1).1 as usize;
        if end <= start || end > self.raw.len() {
            return None;
        }
        Some(
            self.raw[start..end]
                .chunks_exact(2)
                .map(|c| u16::from_le_bytes([c[0], c[1]]))
                .collect(),
        )
    }
}

// --- turns ----------------------------------------------------------------------------------

/// A movement's turn class, the same vocabulary `turn:lanes` uses.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Turn {
    SharpLeft,
    Left,
    SlightLeft,
    Through,
    SlightRight,
    Right,
    SharpRight,
    /// A reversal back the way the driver came — the `LANE_REVERSE` indication.
    Reverse,
}

impl Turn {
    /// The `LANE_*` bit a lane tagged for this movement sets.
    fn bit(self) -> u16 {
        match self {
            Turn::SharpLeft => LANE_SHARP_LEFT,
            Turn::Left => LANE_LEFT,
            Turn::SlightLeft => LANE_SLIGHT_LEFT,
            Turn::Through => LANE_THROUGH,
            Turn::SlightRight => LANE_SLIGHT_RIGHT,
            Turn::Right => LANE_RIGHT,
            Turn::SharpRight => LANE_SHARP_RIGHT,
            Turn::Reverse => LANE_REVERSE,
        }
    }

    /// The angle, in degrees clockwise, this movement nominally turns through. Used to pick the
    /// closest real exit for a tagged indication that no exit's own bucket matches — a
    /// `turn:lanes=right` onto a road the graph classes as a sharp right, say.
    fn nominal_degrees(self) -> f64 {
        match self {
            Turn::SharpLeft => -155.0,
            Turn::Left => -90.0,
            Turn::SlightLeft => -32.0,
            Turn::Through => 0.0,
            Turn::SlightRight => 32.0,
            Turn::Right => 90.0,
            Turn::SharpRight => 155.0,
            Turn::Reverse => 180.0,
        }
    }

    /// Does this movement go left? Decides which lane of the exit a connector lands in.
    fn is_left(self) -> bool {
        matches!(self, Turn::SharpLeft | Turn::Left | Turn::SlightLeft)
    }

    fn is_right(self) -> bool {
        matches!(self, Turn::SharpRight | Turn::Right | Turn::SlightRight)
    }

    /// Every turn a `LANE_*` mask can name, so a lane's bits can be walked.
    const ALL: [Turn; 8] = [
        Turn::SharpLeft,
        Turn::Left,
        Turn::SlightLeft,
        Turn::Through,
        Turn::SlightRight,
        Turn::Right,
        Turn::SharpRight,
        Turn::Reverse,
    ];
}

/// Bucket a signed turn angle (degrees, positive clockwise/right) into a [`Turn`].
///
/// The thresholds are OSM's own reading of the `turn` vocabulary: `slight` up to 45°, the plain
/// direction out to 135°, `sharp` beyond that, and a reversal past 170°.
fn classify_turn(delta_degrees: f64) -> Turn {
    let magnitude = delta_degrees.abs();
    let right = delta_degrees > 0.0;
    if magnitude <= 20.0 {
        Turn::Through
    } else if magnitude > 170.0 {
        Turn::Reverse
    } else if magnitude <= 45.0 {
        if right {
            Turn::SlightRight
        } else {
            Turn::SlightLeft
        }
    } else if magnitude <= 135.0 {
        if right {
            Turn::Right
        } else {
            Turn::Left
        }
    } else if right {
        Turn::SharpRight
    } else {
        Turn::SharpLeft
    }
}

/// Wrap an angle difference into `(-180, 180]`, so a turn's sign is its direction.
fn normalise_degrees(mut d: f64) -> f64 {
    while d > 180.0 {
        d -= 360.0;
    }
    while d <= -180.0 {
        d += 360.0;
    }
    d
}

// --- local geometry -------------------------------------------------------------------------

/// Bearing from `a` to `b` in degrees clockwise from north, on the local tangent plane.
///
/// The `e7` units cancel in the ratio; only the `cos(lat)` correction on the east component
/// matters, and without it every heading north of the tropics comes out rotated.
fn bearing_degrees(a: (i32, i32), b: (i32, i32)) -> f64 {
    let mid_lat = (f64::from(a.0) + f64::from(b.0)) * 0.5 * 1e-7;
    let north = f64::from(b.0) - f64::from(a.0);
    let east = (f64::from(b.1) - f64::from(a.1)) * mid_lat.to_radians().cos();
    east.atan2(north).to_degrees()
}

/// Ground distance between two `e7` points, in metres.
fn distance_m(a: (i32, i32), b: (i32, i32)) -> f64 {
    let mid_lat = (f64::from(a.0) + f64::from(b.0)) * 0.5 * 1e-7;
    let north = (f64::from(b.0) - f64::from(a.0)) * 1e-7 * METRES_PER_DEGREE;
    let east =
        (f64::from(b.1) - f64::from(a.1)) * 1e-7 * METRES_PER_DEGREE * mid_lat.to_radians().cos();
    (north * north + east * east).sqrt()
}

/// The length of a polyline, in metres.
fn polyline_length_m(verts: &[(i32, i32)]) -> f64 {
    verts.windows(2).map(|w| distance_m(w[0], w[1])).sum()
}

/// A point offset from `origin` by `(east, north)` metres, as `(lon, lat)` degrees.
fn offset_degrees(origin: (i32, i32), east_m: f64, north_m: f64) -> (f64, f64) {
    let lat = f64::from(origin.0) * 1e-7;
    let lon = f64::from(origin.1) * 1e-7;
    let d_lat = north_m / METRES_PER_DEGREE;
    // At the poles this divisor collapses; the Mercator clamp means no tile is drawn there anyway,
    // and a floor keeps a degenerate coordinate from becoming an infinity in the output.
    let d_lon = east_m / (METRES_PER_DEGREE * lat.to_radians().cos().abs().max(1e-6));
    (lon + d_lon, lat + d_lat)
}

/// The unit vector pointing along `heading` (degrees clockwise from north), as `(east, north)`.
fn forward(heading_degrees: f64) -> (f64, f64) {
    let r = heading_degrees.to_radians();
    (r.sin(), r.cos())
}

/// The unit vector 90° clockwise of `heading` — "right of the direction of travel".
fn rightward(heading_degrees: f64) -> (f64, f64) {
    let r = heading_degrees.to_radians();
    (r.cos(), -r.sin())
}

// --- the graph, one junction at a time --------------------------------------------------------

/// Is this road class one a car drives on? The same `1..=9` (motorway..living_street) the traffic
/// layer uses, masked so the reverse-geometry flag cannot trip the range test.
fn is_drivable(type_: u8) -> bool {
    (1..=9).contains(&(type_ & ROAD_TYPE_MASK))
}

/// Incoming drivable edges per node, as a CSR built in one pass over the graph.
///
/// `nodes.bin` is out-edges only and there is no reverse index on disk, so this is the whole reason
/// approaches can be found at all. [`Graph::has_drivable_twin`] is the existing primitive and would
/// do for a two-way street, but it can only find an approach from a node the junction also has an
/// out-edge *to* — which misses every one-way approach, and a one-way approach is exactly the
/// motorway slip road this layer is meant to get right.
///
/// Costs `4 * (node_count + 1)` bytes plus 4 per drivable edge, and stores only the edge index: an
/// in-edge's source node is recovered by [`source_of`], which is a binary search over the same
/// `edge_ptr` array rather than another 4 bytes an edge.
struct InEdges {
    start: Vec<u32>,
    edges: Vec<u32>,
}

impl InEdges {
    fn build(graph: &Graph) -> Result<InEdges> {
        let node_count = graph.node_count;
        let mut start = vec![0u32; node_count as usize + 2];
        let mut total = 0usize;
        for n in 0..node_count {
            for idx in graph.edge_ptr(n)..graph.edge_ptr(n + 1) {
                if !is_drivable(graph.edge_type(idx)) {
                    continue;
                }
                let target = graph.edge_target(idx, n as u32)?;
                if u64::from(target) >= node_count {
                    return err(format!(
                        "edge {idx} targets node {target}, past the {node_count} in the graph"
                    ));
                }
                // Counted one slot high, so the prefix sum below leaves `start[t]` pointing at
                // node `t`'s first slot and `start[t + 1]` one past its last.
                start[target as usize + 1] += 1;
                total += 1;
            }
        }
        for i in 1..start.len() {
            start[i] += start[i - 1];
        }
        let mut fill = start.clone();
        let mut edges = vec![0u32; total];
        for n in 0..node_count {
            for idx in graph.edge_ptr(n)..graph.edge_ptr(n + 1) {
                if !is_drivable(graph.edge_type(idx)) {
                    continue;
                }
                let target = graph.edge_target(idx, n as u32)? as usize;
                edges[fill[target] as usize] = idx;
                fill[target] += 1;
            }
        }
        start.truncate(node_count as usize + 1);
        Ok(InEdges { start, edges })
    }

    fn of(&self, n: u64) -> &[u32] {
        let lo = self.start[n as usize] as usize;
        let hi = self.start[n as usize + 1] as usize;
        &self.edges[lo..hi]
    }
}

/// The node edge `idx` leaves from: the last node whose `edge_ptr` is at or below it.
///
/// `edge_ptr` ascends across `nodes.bin` and the sentinel record holds `edge_count`, so the answer
/// is always in `0..node_count` for a valid edge index.
fn source_of(graph: &Graph, idx: u32) -> u64 {
    let (mut lo, mut hi) = (1u64, graph.node_count + 1);
    while lo < hi {
        let mid = lo + (hi - lo) / 2;
        if graph.edge_ptr(mid) > idx {
            hi = mid;
        } else {
            lo = mid + 1;
        }
    }
    lo - 1
}

/// An edge's polyline in the order it is travelled, `from -> to`.
///
/// A reverse-geometry edge stores no shape of its own — its twin running the other way owns it — so
/// the twin's polyline is found and reversed. Without this every curved two-way approach would get
/// its heading from the straight chord instead of the road, which at a bend is a wrong turn angle
/// and therefore a wrong lane pairing.
fn travel_polyline(graph: &Graph, idx: u32, from: u32, to: u32) -> Vec<(i32, i32)> {
    if graph.geom_contains(idx) {
        return graph.polyline(idx, from, to);
    }
    for twin in graph.edge_ptr(u64::from(to))..graph.edge_ptr(u64::from(to) + 1) {
        if !graph.geom_contains(twin) || !is_drivable(graph.edge_type(twin)) {
            continue;
        }
        if graph.edge_target(twin, to).is_ok_and(|t| t == from) {
            let mut verts = graph.polyline(twin, to, from);
            verts.reverse();
            return verts;
        }
    }
    // A genuine straight chord: source and target, and nothing between them.
    graph.polyline(idx, from, to)
}

/// One movement into or out of a junction, resolved into everything a connector needs.
struct Arm {
    /// The node at the far end.
    neighbour: u32,
    /// Degrees clockwise from north, always in the direction of travel — so for an approach this is
    /// the heading traffic *arrives* on, and for an exit the heading it *departs* on.
    heading: f64,
    /// The edge's own length in metres, which caps how far the connector may reach along it.
    length_m: f64,
    /// Lanes running this way, and whether the road carries traffic the other way too. Together
    /// they place a lane across the carriageway.
    lanes: usize,
    two_way: bool,
    /// Per-lane `turn:lanes` masks, left→right, when `lanes.bin` has them. `None` is the common
    /// case and is what sends a movement down the inferred path.
    masks: Option<Vec<u16>>,
}

/// Resolve one incident edge, or `None` when it is not usable as an arm of this junction.
fn arm(
    graph: &Graph,
    lanes: Option<&LaneTable>,
    idx: u32,
    from: u32,
    to: u32,
    outgoing: bool,
) -> Option<Arm> {
    let verts = travel_polyline(graph, idx, from, to);
    // The heading is the first geometry step leaving the junction, or the last arriving at it. A
    // repeated vertex has no direction, so the scan walks past duplicates rather than dividing by
    // zero — the graph does contain them at edges that were split at a coincident point.
    let node = if outgoing { verts.first().copied()? } else { verts.last().copied()? };
    let (heading, ok) = if outgoing {
        match verts.iter().skip(1).find(|v| **v != node) {
            Some(next) => (bearing_degrees(node, *next), true),
            None => (0.0, false),
        }
    } else {
        match verts.iter().rev().skip(1).find(|v| **v != node) {
            Some(prev) => (bearing_degrees(*prev, node), true),
            None => (0.0, false),
        }
    };
    if !ok {
        return None;
    }
    let length_m = polyline_length_m(&verts);
    if length_m < MIN_SETBACK_M {
        return None;
    }
    let masks = lanes.and_then(|table| table.masks(idx)).filter(|m| !m.is_empty());
    // Absent `turn:lanes`, the lane count is filled in by the caller from the junction's own shape;
    // one lane is the floor.
    let lane_count = masks.as_ref().map_or(1, Vec::len);
    let two_way = graph.has_drivable_twin(to, from).unwrap_or(false);
    Some(Arm {
        neighbour: if outgoing { to } else { from },
        heading,
        length_m,
        lanes: lane_count,
        two_way,
        masks,
    })
}

/// A lane's centre, in metres right of the road's centreline as seen by traffic travelling this
/// arm's direction.
///
/// Two offsets stacked. A two-way road's centreline runs between the two directions, so the
/// carriageway a driver is on sits a half-carriageway to their own side of it — right in a
/// right-hand-traffic country, left in a left-hand one. Within that carriageway lane `k` of `count`
/// sits at the usual centred spacing, counted left→right in the direction of travel, which is the
/// order `lanes.bin` stores masks in.
///
/// `width` is the lane width in ground metres at this junction's latitude — see
/// [`lane_width_ground_m`], and the module docs for why it is not simply [`LANE_WIDTH_MERCATOR_M`].
///
/// The opposite direction is assumed to have the same lane count, because the graph gives no way to
/// ask. An asymmetric road — three lanes one way and one the other — puts its connectors half a
/// carriageway off.
fn lane_offset_m(k: usize, count: usize, two_way: bool, left_hand: bool, width: f64) -> f64 {
    let count = count.max(1);
    let side = if left_hand { -1.0 } else { 1.0 };
    let carriageway = if two_way { side * count as f64 * 0.5 * width } else { 0.0 };
    let within = (k as f64 - (count as f64 - 1.0) * 0.5) * width;
    carriageway + within
}

/// How many lanes to spread one arm's connector ends across.
///
/// `turn:lanes` where the arm carries it, `inferred` where it does not — and the point of it being
/// a function is that **both ends of a connector are placed by this same rule**. The module docs
/// have what went wrong when they were not.
///
/// `inferred` must be *this arm's own* inference: the number of legal exits for an approach, the
/// number of approaches that may legally feed it for an exit. Handing an exit the approach's count
/// makes the exit's layout depend on who is looking at it, which is the same drift one level down.
fn effective_lanes(arm: &Arm, inferred: usize) -> usize {
    if arm.masks.is_some() {
        arm.lanes
    } else {
        inferred.max(1)
    }
}

/// Sample the connector from lane `in_lane` of `approach` into lane `out_lane` of `exit`.
///
/// A cubic bezier in a local tangent plane centred on the junction node, with its handles along the
/// two headings so the curve leaves the approach and meets the exit tangentially. Sampled here, in
/// `f64` and once per build, rather than on device once per frame — which is the whole point of
/// carrying a polyline instead of control points.
fn connector(
    node: (i32, i32),
    approach: &Arm,
    in_lane: usize,
    approach_lanes: usize,
    exit: &Arm,
    out_lane: usize,
    exit_lanes: usize,
    turn: Turn,
    left_hand: bool,
) -> Option<Vec<(f64, f64)>> {
    let back = (SETBACK_M).min(approach.length_m * SETBACK_EDGE_FRACTION);
    let ahead = (SETBACK_M).min(exit.length_m * SETBACK_EDGE_FRACTION);
    if back < MIN_SETBACK_M || ahead < MIN_SETBACK_M {
        return None;
    }
    // The setback is real ground distance: the intersection is that big and the roads it joins are
    // where they are. The lateral offset is not — it shrinks with `cos φ` so that it stays a fixed
    // size in projected units, and therefore a fixed size in pixels, at every latitude.
    //
    // Both ends are placed by the same call with the same rule. Taking the counts as arguments
    // rather than off each `Arm` is what makes that structural: an arm's own `lanes` is whatever
    // `lanes.bin` happened to say, and reading it directly at one end while the caller overrode it
    // at the other is exactly how every connector into an exit came to share one endpoint.
    let width = lane_width_ground_m(f64::from(node.0) * 1e-7);
    let in_off = lane_offset_m(in_lane, approach_lanes, approach.two_way, left_hand, width);
    let out_off = lane_offset_m(out_lane, exit_lanes, exit.two_way, left_hand, width);

    let (fin_e, fin_n) = forward(approach.heading);
    let (rin_e, rin_n) = rightward(approach.heading);
    let (fout_e, fout_n) = forward(exit.heading);
    let (rout_e, rout_n) = rightward(exit.heading);

    // Start: back along the approach from the node, then across to the lane's centre.
    let p0 = (-fin_e * back + rin_e * in_off, -fin_n * back + rin_n * in_off);
    // End: forward along the exit, then across.
    let p3 = (fout_e * ahead + rout_e * out_off, fout_n * ahead + rout_n * out_off);
    let p1 = (p0.0 + fin_e * back * HANDLE, p0.1 + fin_n * back * HANDLE);
    let p2 = (p3.0 - fout_e * ahead * HANDLE, p3.1 - fout_n * ahead * HANDLE);

    // A straight-through movement needs two points; a U-turn needs every one of these. Anything
    // flatter than the sampling would resolve is dropped by the tiler's simplifier anyway.
    let sweep = normalise_degrees(exit.heading - approach.heading).abs();
    let samples = if turn == Turn::Through && sweep < 3.0 && (in_off - out_off).abs() < 0.05 {
        2
    } else {
        (4.0 + sweep / 18.0).round().clamp(4.0, 14.0) as usize
    };

    let mut out = Vec::with_capacity(samples);
    for i in 0..samples {
        let t = i as f64 / (samples - 1) as f64;
        let u = 1.0 - t;
        let (w0, w1, w2, w3) = (u * u * u, 3.0 * u * u * t, 3.0 * u * t * t, t * t * t);
        let east = w0 * p0.0 + w1 * p1.0 + w2 * p2.0 + w3 * p3.0;
        let north = w0 * p0.1 + w1 * p1.1 + w2 * p2.1 + w3 * p3.1;
        out.push(offset_degrees(node, east, north));
    }
    Some(out)
}

/// Which lane of the exit a connector lands in.
///
/// Inference, and the ordinary road convention; the rule is written out in full in the module docs.
/// `rank` is this lane's place, left→right, among the `siblings` approach lanes making the same
/// movement into the same exit — which is what keeps a dual left turn two ribbons wide instead of
/// two ribbons on one point. A movement only one lane makes passes `rank` 0 and `siblings` 1, and
/// reduces to "the exit's leftmost" or "its rightmost".
fn exit_lane(
    turn: Turn,
    in_lane: usize,
    in_lanes: usize,
    out_lanes: usize,
    rank: usize,
    siblings: usize,
) -> usize {
    let last = out_lanes.saturating_sub(1);
    if turn.is_left() {
        rank.min(last)
    } else if turn.is_right() {
        last - siblings.saturating_sub(1).saturating_sub(rank).min(last)
    } else {
        (in_lane * out_lanes / in_lanes.max(1)).min(last)
    }
}

/// The exits a lane tagged for `turn` should connect to.
///
/// Exact bucket first: every legal exit the junction's own geometry classes as this movement. When
/// none matches — a `turn:lanes=right` onto a road that leaves at 140°, say — the single closest
/// exit by angle is used instead, because a tagged indication with nowhere to go is far more likely
/// to be a threshold disagreement than a lane that leads nowhere.
fn exits_for(turn: Turn, legal: &[(usize, f64, Turn)]) -> Vec<usize> {
    let exact: Vec<usize> =
        legal.iter().filter(|(_, _, t)| *t == turn).map(|(i, _, _)| *i).collect();
    if !exact.is_empty() {
        return exact;
    }
    let nominal = turn.nominal_degrees();
    legal
        .iter()
        .min_by(|a, b| {
            normalise_degrees(a.1 - nominal)
                .abs()
                .total_cmp(&normalise_degrees(b.1 - nominal).abs())
        })
        .map(|(i, _, _)| vec![*i])
        .unwrap_or_default()
}

/// Spread `lanes` across `exits`, both already in left→right order, as `(lane, exit)` pairs.
///
/// Whichever side is larger drives the pairing, so every lane gets a connector and every exit gets
/// fed. Two left-turn lanes onto one road give two connectors into the same exit, which is right —
/// a dual left turn is two ribbons. One lane onto a two-lane exit gives two connectors out of the
/// same lane, which is also right: the lane fans.
fn distribute(lanes: &[usize], exits: &[usize]) -> Vec<(usize, usize)> {
    if lanes.is_empty() || exits.is_empty() {
        return Vec::new();
    }
    if lanes.len() >= exits.len() {
        lanes
            .iter()
            .enumerate()
            .map(|(i, &lane)| (lane, exits[i * exits.len() / lanes.len()]))
            .collect()
    } else {
        exits
            .iter()
            .enumerate()
            .map(|(j, &exit)| (lanes[j * lanes.len() / exits.len()], exit))
            .collect()
    }
}

/// Read the v6 graph at `dir` and push one line feature per lane connector into `sink`. Returns the
/// number of connectors emitted.
///
/// `conventions` decides which side of a road's centreline the direction of travel sits on, so a
/// left-hand-traffic country's connectors are not mirrored. It is resolved from each junction's own
/// coordinate at [`super::boundaries::COARSE_ZOOM`], which is the granularity the grid holds.
///
/// Not clipped to a bounding box, for the same reason [`crate::schema::traffic::stream_graph`] is
/// not: the graph is already the built region and the tiler clips each connector per tile.
pub fn stream_junctions(dir: &Path, conventions: &Conventions, sink: &mut Sink) -> Result<u64> {
    let graph = Graph::load(dir)?;
    let lanes = LaneTable::load(dir)?;
    let incoming = InEdges::build(&graph)?;
    let class = junction_class();
    let mut emitted = 0u64;

    for n in 0..graph.node_count {
        let node = graph.node(n);
        let in_arms: Vec<Arm> = incoming
            .of(n)
            .iter()
            .filter_map(|&idx| {
                arm(&graph, lanes.as_ref(), idx, source_of(&graph, idx) as u32, n as u32, false)
            })
            .collect();
        let out_arms: Vec<Arm> = (graph.edge_ptr(n)..graph.edge_ptr(n + 1))
            .filter(|&idx| is_drivable(graph.edge_type(idx)))
            .filter_map(|idx| {
                let target = graph.edge_target(idx, n as u32).ok()?;
                arm(&graph, lanes.as_ref(), idx, n as u32, target, true)
            })
            .collect();
        if in_arms.is_empty() || out_arms.len() < 2 {
            continue;
        }
        // A junction is a node three or more distinct roads meet at. Degree-2 chains are collapsed
        // by the generator, so the survivors below this bar are dead ends, attribute breaks and
        // transit stops — none of which anything turns at.
        let degree: BTreeSet<u32> =
            in_arms.iter().chain(&out_arms).map(|a| a.neighbour).collect();
        if degree.len() < 3 {
            continue;
        }

        let (tx, ty) = tile_build::geom::project(
            f64::from(node.1) * 1e-7,
            f64::from(node.0) * 1e-7,
            super::boundaries::COARSE_ZOOM,
        );
        let left_hand = conventions
            .at_tile(
                super::boundaries::COARSE_ZOOM,
                tx.max(0.0) as u64,
                ty.max(0.0) as u64,
            )
            .left_hand;

        // Each exit's lane count, mirroring the approach's inference from the other end: one lane
        // per approach that may legally feed the arm. Computed here, once for the junction, and
        // deliberately not inside the approach loop below — an exit that is three lanes wide to one
        // approach and two to another is the same end-placement drift this fix exists to remove.
        let exit_lanes: Vec<usize> = out_arms
            .iter()
            .map(|exit| {
                let feeders = in_arms.iter().filter(|a| a.neighbour != exit.neighbour).count();
                effective_lanes(exit, feeders)
            })
            .collect();

        for approach in &in_arms {
            // Every exit but the one back the way you came. The graph carries no turn restrictions,
            // so this is the whole legality model: inference, and generous.
            let mut legal: Vec<(usize, f64, Turn)> = out_arms
                .iter()
                .enumerate()
                .filter(|(_, exit)| exit.neighbour != approach.neighbour)
                .map(|(i, exit)| {
                    let delta = normalise_degrees(exit.heading - approach.heading);
                    (i, delta, classify_turn(delta))
                })
                .collect();
            if legal.is_empty() {
                continue;
            }
            // Left→right as the driver sees them, which is the order lane masks are stored in.
            legal.sort_by(|a, b| a.1.total_cmp(&b.1));

            // `turn:lanes` where it exists, junction shape where it does not. The inferred case is
            // the overwhelming majority of roads: one lane is assumed per legal exit, in the same
            // left→right order, which is the most a junction's own topology can say.
            let mut pairs: Vec<(usize, usize)> = match &approach.masks {
                Some(masks) => {
                    let mut pairs = Vec::new();
                    for turn in Turn::ALL {
                        let bit = turn.bit();
                        let claimants: Vec<usize> = masks
                            .iter()
                            .enumerate()
                            .filter(|(_, m)| {
                                // A lane whose only marking is "none" is treated as a through lane,
                                // which is what an unmarked lane at a junction means in practice.
                                **m & bit != 0
                                    || (turn == Turn::Through
                                        && (**m == 0 || **m == LANE_NONE))
                            })
                            .map(|(k, _)| k)
                            .collect();
                        if claimants.is_empty() {
                            continue;
                        }
                        pairs.extend(distribute(&claimants, &exits_for(turn, &legal)));
                    }
                    pairs
                }
                None => (0..legal.len()).map(|i| (i, legal[i].0)).collect(),
            };

            // Absent `turn:lanes` the junction's own topology is all there is to go on: one lane
            // per legal exit. `exit_lanes` is that same rule read from the other end.
            let approach_lanes = effective_lanes(approach, legal.len());

            // Ordered so a movement's lanes rank left→right, and deduplicated because two tagged
            // indications can fall back to the same exit and would otherwise draw one connector
            // twice on top of itself.
            pairs.sort_unstable();
            pairs.dedup();

            for &(in_lane, exit_index) in &pairs {
                let exit = &out_arms[exit_index];
                let Some((_, _, turn)) = legal.iter().find(|(i, _, _)| *i == exit_index) else {
                    continue;
                };
                let out_lanes = exit_lanes[exit_index];
                // This lane's place among the lanes making the same movement into the same exit.
                let siblings: Vec<usize> =
                    pairs.iter().filter(|(_, e)| *e == exit_index).map(|&(l, _)| l).collect();
                let rank = siblings.iter().position(|&l| l == in_lane).unwrap_or(0);
                let out_lane =
                    exit_lane(*turn, in_lane, approach_lanes, out_lanes, rank, siblings.len());
                let Some(points) = connector(
                    node,
                    approach,
                    in_lane,
                    approach_lanes,
                    exit,
                    out_lane,
                    out_lanes,
                    *turn,
                    left_hand,
                ) else {
                    continue;
                };
                sink.push(&class, &Geometry::Lines(vec![points]))?;
                emitted += 1;
            }
        }
    }
    Ok(emitted)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::traffic::tests::{write_graph, EdgeSpec, GraphFixture};

    /// A four-arm crossroads: node 0 in the middle, nodes 1..4 due north, east, south and west of
    /// it, every arm two-way and residential. Roughly 100 m out on each side, which is a real
    /// intersection's scale and comfortably longer than [`SETBACK_M`].
    ///
    /// Built with the traffic layer's own byte-for-byte v6 writer, so the reader here is exercised
    /// against the same layout the contract specifies rather than a mock.
    fn crossroads(lanes: &[(u32, Vec<u16>)]) -> GraphFixture {
        crossroads_at(350_000_000, 12_000, lanes)
    }

    /// The same crossroads at an arbitrary latitude. `lon_step_e7` is chosen by the caller so the
    /// east and west arms stay roughly 100 m out as `cos φ` shrinks a degree of longitude.
    fn crossroads_at(
        lat_e7: i32,
        lon_step_e7: i32,
        lanes: &[(u32, Vec<u16>)],
    ) -> GraphFixture {
        let coords: [(i32, i32); 5] = [
            (lat_e7, -1_200_000_000),             // 0: the junction
            (lat_e7 + 9_000, -1_200_000_000),     // 1: north, ~100 m
            (lat_e7, -1_200_000_000 + lon_step_e7), // 2: east
            (lat_e7 - 9_000, -1_200_000_000),     // 3: south
            (lat_e7, -1_200_000_000 - lon_step_e7), // 4: west
        ];
        let edge = |source, target| EdgeSpec { source, target, type_: 7, interior: Vec::new() };
        write_graph(
            "junction",
            &coords,
            &[
                // Edges 0..3 leave the junction; edges 4..7 return to it. Grouped by source, which
                // is what makes `nodes.bin`'s edge_ptr a CSR row pointer.
                edge(0, 1),
                edge(0, 2),
                edge(0, 3),
                edge(0, 4),
                edge(1, 0),
                edge(2, 0),
                edge(3, 0),
                edge(4, 0),
            ],
            lanes,
        )
    }

    fn stream(fixture: &GraphFixture) -> (u64, Vec<Vec<(f64, f64)>>) {
        let spill = fixture.dir.join("features.tmp");
        let mut sink = Sink::create(&spill).expect("sink");
        let emitted =
            stream_junctions(&fixture.dir, &Conventions::default(), &mut sink).expect("stream");
        let store = sink.finish(&spill).expect("finish");
        let mut reader = store.reader().expect("reader");
        let mut lines = Vec::new();
        while let Some(feature) = reader.next().expect("read") {
            assert_eq!(feature.class.layer, LAYER_JUNCTION);
            assert_eq!(feature.class.min_zoom, MIN_ZOOM);
            assert!(!feature.class.area, "a connector is a line, not a ring");
            match &feature.geometry {
                Geometry::Lines(parts) => {
                    assert_eq!(parts.len(), 1, "one connector is one part");
                    lines.push(parts[0].clone());
                }
                other => panic!("a connector came back as {other:?}"),
            }
        }
        let _ = std::fs::remove_file(&spill);
        (emitted, lines)
    }

    /// **Both ends of a connector are placed by the same rule, and the exit end is where it broke.**
    ///
    /// The failure this pins is not that connectors were missing but where they stopped. The
    /// approach end was spread across an inferred lane count while the exit end kept the raw
    /// `lanes.bin` value, which for an untagged road is 1 — so [`exit_lane`] returned lane 0 for
    /// every movement and every connector entering an arm terminated on one identical point. This
    /// degree-4 fixture emitted 12 connectors with 12 distinct starts and **4** distinct ends, and
    /// three ribbons converging on each of four pencil points is the tangle of hairlines that
    /// reached a device.
    ///
    /// `distinct_ends` is pinned to a number and not only to `starts == ends`, because that weaker
    /// form passes if both ends collapse together. Revert the exit's lane count to the arm's own
    /// and this reads 4.
    ///
    /// Points are compared at 1e-7 degrees, about a centimetre — enough to separate lane offsets
    /// that are metres apart, and no exact float equality anywhere.
    #[test]
    fn every_connector_into_an_exit_ends_in_a_lane_of_its_own() {
        let (emitted, lines) = stream(&crossroads(&[]));
        assert_eq!(emitted, 12, "four approaches x three exits, unchanged by the endpoint fix");

        let at = |p: (f64, f64)| ((p.0 * 1e7).round() as i64, (p.1 * 1e7).round() as i64);
        let starts: BTreeSet<(i64, i64)> = lines.iter().map(|l| at(l[0])).collect();
        let ends: BTreeSet<(i64, i64)> =
            lines.iter().map(|l| at(*l.last().expect("a connector has points"))).collect();

        assert_eq!(
            ends.len(),
            12,
            "{} distinct endpoints for 12 connectors: every movement into the same exit arm is \
             terminating on one shared point, which draws as a spray of hairlines converging on a \
             pencil point. Each connector must end in its own lane of the exit.",
            ends.len(),
        );
        assert_eq!(
            starts.len(),
            ends.len(),
            "the two ends of a connector are being spread by different rules: {} distinct starts \
             against {} distinct ends",
            starts.len(),
            ends.len(),
        );

        // And the mirror's other consequence: with the same count at both ends a straight-through
        // movement keeps its lane index and therefore its offset, so it is genuinely straight and
        // samples as two points. One per approach. With the exit collapsed to one lane the two
        // offsets differed by a lane width and all twelve came out bent.
        assert_eq!(
            lines.iter().filter(|l| l.len() == 2).count(),
            4,
            "a through movement should leave and arrive in the same lane, so it draws straight",
        );
    }

    /// The rule both ends share, at the unit level.
    #[test]
    fn an_arms_lane_count_is_its_tagged_one_or_the_junctions_inference() {
        let tagged = Arm {
            neighbour: 1,
            heading: 0.0,
            length_m: 100.0,
            lanes: 2,
            two_way: true,
            masks: Some(vec![LANE_LEFT, LANE_THROUGH]),
        };
        let untagged = Arm { masks: None, lanes: 1, ..tagged };
        assert_eq!(effective_lanes(&tagged, 5), 2, "`turn:lanes` wins over the inference");
        assert_eq!(effective_lanes(&untagged, 5), 5, "absent it, the junction's own shape");
        assert_eq!(effective_lanes(&untagged, 0), 1, "a lane count is never zero");
    }

    /// **A tiler-side `min_zoom` past the archive's deepest zoom deletes a layer in silence.**
    ///
    /// The tiler writes a feature into tiles from its `min_zoom` down. Nothing above `--max-zoom`
    /// is ever built, so a layer gated deeper than that is computed in full, costs its whole run
    /// time, and then lands in no tile at all — while the build exits zero and prints the count it
    /// generated. Junction shipped at 16 against a 14-deep archive and the only symptom was a
    /// layer missing from the archive's own summary.
    ///
    /// Pinned against [`crate::DEFAULT_MAX_ZOOM`] itself, so the bound is a compile-time
    /// dependency on the build's own default rather than a copy of it, and for both layers read
    /// out of the routing graph, because they share the failure.
    #[test]
    fn a_graph_derived_layer_is_tiled_no_deeper_than_the_archive_is_built() {
        let max_zoom = crate::DEFAULT_MAX_ZOOM;
        let layers = [("junction", MIN_ZOOM), ("traffic", crate::schema::traffic::MIN_ZOOM)];
        for (layer, min_zoom) in layers {
            assert!(
                min_zoom <= max_zoom,
                "`{layer}` is tiled from z{min_zoom}, but archives are only built to z{max_zoom}, \
                 so no tile that could hold it is ever written: every {layer} feature will be \
                 computed and then discarded, and the layer will be silently absent from every \
                 archive while the build still reports success. This constant is the TILING gate \
                 and must be at most z{max_zoom}; the zoom the layer is DRAWN at is the style's \
                 `minzoom` in basemap.flat.json and is set there, independently.",
            );
        }
    }

    /// The angle sign convention the whole layer rests on: positive is a right turn.
    ///
    /// Wrong here and every lane pairing is mirrored, which is the one failure that would look
    /// plausible on a screenshot — connectors that curve smoothly into the wrong road.
    #[test]
    fn a_turn_angle_is_positive_to_the_right() {
        // Heading north, leaving east.
        assert_eq!(classify_turn(normalise_degrees(90.0 - 0.0)), Turn::Right);
        // Heading south, leaving east: east is on the driver's left.
        assert_eq!(classify_turn(normalise_degrees(90.0 - 180.0)), Turn::Left);
        // The wrap is what makes that true rather than a 270-degree right.
        assert_eq!(normalise_degrees(90.0 - 180.0), -90.0);
        assert_eq!(normalise_degrees(350.0), -10.0);
        assert_eq!(normalise_degrees(-350.0), 10.0);
        assert_eq!(classify_turn(0.0), Turn::Through);
        assert_eq!(classify_turn(-15.0), Turn::Through);
        assert_eq!(classify_turn(30.0), Turn::SlightRight);
        assert_eq!(classify_turn(-30.0), Turn::SlightLeft);
        assert_eq!(classify_turn(150.0), Turn::SharpRight);
        assert_eq!(classify_turn(-150.0), Turn::SharpLeft);
        assert_eq!(classify_turn(179.0), Turn::Reverse);
    }

    /// Headings come from `atan2` over a geometry step, because the graph stores none.
    #[test]
    fn a_heading_is_computed_from_the_geometry_and_corrected_for_latitude() {
        let origin = (350_000_000, -1_200_000_000);
        assert!((bearing_degrees(origin, (350_009_000, -1_200_000_000)) - 0.0).abs() < 0.01, "north");
        assert!((bearing_degrees(origin, (349_991_000, -1_200_000_000)) - 180.0).abs() < 0.01, "south");
        assert!((bearing_degrees(origin, (350_000_000, -1_199_988_000)) - 90.0).abs() < 0.01, "east");
        assert!((bearing_degrees(origin, (350_000_000, -1_200_012_000)) + 90.0).abs() < 0.01, "west");
        // Without the cos(lat) correction a due-northeast step at latitude 35 comes out at 45
        // degrees only by accident of the coordinate deltas; with it, equal *ground* distances do.
        let north_m = 1000.0;
        let east_m = 1000.0;
        let (lon, lat) = offset_degrees(origin, east_m, north_m);
        let to = ((lat * 1e7) as i32, (lon * 1e7) as i32);
        assert!((bearing_degrees(origin, to) - 45.0).abs() < 0.05, "{}", bearing_degrees(origin, to));
    }

    /// A node nothing forks at emits nothing. The traffic layer's own square fixture is four nodes
    /// of degree two or less, so it is exactly this case.
    #[test]
    fn a_chain_of_degree_two_nodes_produces_no_connectors() {
        let coords: [(i32, i32); 3] = [
            (350_000_000, -1_200_000_000),
            (350_000_000, -1_199_990_000),
            (350_000_000, -1_199_980_000),
        ];
        let edge = |source, target| EdgeSpec { source, target, type_: 7, interior: Vec::new() };
        let fixture = write_graph(
            "junction_chain",
            &coords,
            &[edge(0, 1), edge(1, 0), edge(1, 2), edge(2, 1)],
            &[],
        );
        let (emitted, lines) = stream(&fixture);
        assert_eq!(emitted, 0, "node 1 has two neighbours, so nothing turns there");
        assert!(lines.is_empty());
    }

    /// The inferred path, which is what the overwhelming majority of roads take.
    ///
    /// Four approaches, three legal exits each (everything but the U-turn back the way you came),
    /// one inferred lane per exit: twelve connectors. The four outer nodes are degree one and
    /// contribute nothing, which is what pins the count to node 0 alone.
    #[test]
    fn a_crossroads_with_no_tagged_lanes_infers_one_connector_per_legal_movement() {
        let fixture = crossroads(&[]);
        let (emitted, lines) = stream(&fixture);
        assert_eq!(emitted, 12, "four approaches x three exits");
        assert_eq!(lines.len(), 12);
        for line in &lines {
            assert!(line.len() >= 2, "a connector needs at least two points");
            assert!(line.len() <= 14, "the sampler is bounded");
            for (lon, lat) in line {
                assert!(lon.is_finite() && lat.is_finite());
                // Every connector stays inside the intersection box, which is two setbacks across.
                assert!((lat - 35.0).abs() < 0.001, "{lat} ran outside the junction");
                assert!((lon + 120.0).abs() < 0.001, "{lon} ran outside the junction");
            }
        }
        // A turning connector is a sampled curve; a straight-through one need not be.
        assert!(
            lines.iter().any(|l| l.len() > 4),
            "no connector was sampled as a curve, so the bezier is not being walked",
        );
    }

    /// The data path: a tagged `turn:lanes` on one approach replaces the inference for that
    /// approach only, and a shared `through;right` lane emits both of its movements.
    #[test]
    fn a_tagged_turn_lanes_approach_pairs_each_lane_with_its_own_exit() {
        // Edge 4 is node1 -> node0, the approach from the north, arriving heading south. Its lanes,
        // left to right: a left-turn lane, a through lane, and a shared through/right lane.
        let masks = vec![
            LANE_LEFT,
            LANE_THROUGH,
            LANE_THROUGH | LANE_RIGHT,
        ];
        let fixture = crossroads(&[(4u32, masks)]);
        let (emitted, _) = stream(&fixture);
        // The tagged approach: one left, two throughs, one right = 4. The other three approaches
        // are untagged and infer three each = 9.
        assert_eq!(emitted, 13, "the tagged approach adds a fourth connector for its shared lane");
    }

    /// Spreading lanes across exits, both ways round. A dual left turn is two ribbons into one
    /// road; a single lane onto a two-lane exit fans into both.
    #[test]
    fn lanes_and_exits_are_distributed_so_neither_side_is_left_unfed() {
        assert_eq!(distribute(&[0, 1], &[7]), vec![(0, 7), (1, 7)], "a dual left turn");
        assert_eq!(distribute(&[3], &[5, 8]), vec![(3, 5), (3, 8)], "one lane fanning");
        assert_eq!(distribute(&[0, 1], &[4, 9]), vec![(0, 4), (1, 9)], "one to one");
        assert!(distribute(&[], &[1]).is_empty());
        assert!(distribute(&[1], &[]).is_empty());
    }

    /// A tagged indication no exit's own bucket matches still connects, to the closest exit by
    /// angle — a threshold disagreement is far likelier than a lane that leads nowhere.
    #[test]
    fn a_tagged_turn_with_no_matching_exit_falls_back_to_the_closest_one() {
        // Exits at a sharp right and straight on; nothing the classifier calls a plain right.
        let legal = vec![(0usize, 150.0, Turn::SharpRight), (1usize, 5.0, Turn::Through)];
        assert_eq!(exits_for(Turn::Right, &legal), vec![0], "90 is nearer 150 than 5");
        assert_eq!(exits_for(Turn::Through, &legal), vec![1], "an exact bucket wins");
        assert!(exits_for(Turn::Left, &[]).is_empty());
    }

    /// Lane offsets, which are what stop every connector from stacking on the road's centreline.
    #[test]
    fn a_lane_sits_on_its_own_side_of_the_centreline() {
        let w = LANE_WIDTH_MERCATOR_M;
        // A one-way, two lanes: centred on the road, half a lane either side.
        assert!((lane_offset_m(0, 2, false, false, w) + w / 2.0).abs() < 1e-9);
        assert!((lane_offset_m(1, 2, false, false, w) - w / 2.0).abs() < 1e-9);
        // Two-way, right-hand traffic: the whole carriageway sits right of the centreline, so both
        // lanes are strictly positive and the inner one is half a lane out.
        assert!((lane_offset_m(0, 2, true, false, w) - w * 0.5).abs() < 1e-9);
        assert!((lane_offset_m(1, 2, true, false, w) - w * 1.5).abs() < 1e-9);
        // Left-hand traffic mirrors it, and only it: lane order within the carriageway does not
        // flip, because `lanes.bin` stores masks left-to-right in the direction of travel.
        assert!((lane_offset_m(0, 2, true, true, w) + w * 1.5).abs() < 1e-9);
        assert!((lane_offset_m(1, 2, true, true, w) + w * 0.5).abs() < 1e-9);
        // A one-way is the same either side of the world: there is no opposing carriageway to sit
        // beside.
        assert_eq!(lane_offset_m(0, 3, false, true, w), lane_offset_m(0, 3, false, false, w));
    }

    /// **The latitude invariant, and the whole reason the offset is not baked in ground metres.**
    ///
    /// The renderer's lane width is a screen-space quantity, and Web Mercator carries a `1 / cos φ`
    /// stretch, so a connector offset by a fixed *ground* distance drifts off the carriageway as
    /// you go north — by 2.3x at 60°N, which is more than two lane widths. Offsetting by a fixed
    /// *projected* distance is what makes it land, at every latitude and with no wire change.
    ///
    /// Measured end to end through the real encoder rather than on the helper, and asserted from
    /// both sides: the projected spread must match, and the ground spread must not — otherwise the
    /// test would still pass if the correction were quietly dropped.
    #[test]
    fn a_connectors_lateral_offset_is_the_same_projected_size_at_every_latitude() {
        // The correction itself: a lane's ground width shrinks toward the poles exactly as fast as
        // Mercator stretches it.
        assert!((lane_width_ground_m(0.0) - LANE_WIDTH_MERCATOR_M).abs() < 1e-9);
        assert!(
            (lane_width_ground_m(60.0) - LANE_WIDTH_MERCATOR_M * 0.5).abs() < 1e-6,
            "cos 60 is a half",
        );

        // The first three connectors are the north approach's, in `incoming.of(0)` order. They
        // share a setback and a heading, so the spread between their start points is purely the
        // lane offset.
        let spread = |lines: &[Vec<(f64, f64)>], project: &dyn Fn((f64, f64)) -> (f64, f64)| {
            let starts: Vec<(f64, f64)> = lines[..3].iter().map(|l| project(l[0])).collect();
            let mut worst: f64 = 0.0;
            for a in &starts {
                for b in &starts {
                    worst = worst.max(((a.0 - b.0).powi(2) + (a.1 - b.1).powi(2)).sqrt());
                }
            }
            worst
        };

        // 0.0009 deg of latitude is ~100 m anywhere; a degree of longitude shrinks with cos, so the
        // east/west arms need a wider step at 60 to stay the same distance out.
        let (_, at_equator) = stream(&crossroads_at(0, 9_000, &[]));
        let (_, at_sixty) = stream(&crossroads_at(600_000_000, 18_000, &[]));
        assert_eq!(at_equator.len(), 12);
        assert_eq!(at_sixty.len(), 12);

        // In projected units — what the renderer actually draws — the two must agree. z20 is an
        // arbitrary scale; Mercator is self-similar, so any zoom gives the same ratio.
        let mercator = |(lon, lat): (f64, f64)| tile_build::geom::project(lon, lat, 20);
        let projected_equator = spread(&at_equator, &mercator);
        let projected_sixty = spread(&at_sixty, &mercator);
        assert!(projected_equator > 0.0, "the lanes did not separate at all");
        assert!(
            (projected_sixty / projected_equator - 1.0).abs() < 0.02,
            "a lane must be the same projected width everywhere: {projected_equator} at the \
             equator vs {projected_sixty} at 60N",
        );

        // And in ground units they must differ by cos 60, which is what proves the correction is
        // being applied rather than the assertion above being vacuous. A degree of longitude is
        // `cos φ` shorter on the ground, and the north approach's offset is purely east-west.
        let ground = |(lon, lat): (f64, f64)| {
            (lon * METRES_PER_DEGREE * lat.to_radians().cos(), lat * METRES_PER_DEGREE)
        };
        let ground_ratio = spread(&at_sixty, &ground) / spread(&at_equator, &ground);
        assert!(
            (ground_ratio - 0.5).abs() < 0.02,
            "a lane's ground width should halve by 60N, not stay put: ratio {ground_ratio}",
        );
    }

    /// Which lane of the exit a connector lands in.
    ///
    /// The single-lane cases pass `rank` 0 of `siblings` 1, which is what a movement only one
    /// approach lane makes looks like, and reduce to "the exit's leftmost" or "its rightmost".
    #[test]
    fn a_turn_lands_in_the_exit_lane_its_direction_implies() {
        assert_eq!(exit_lane(Turn::Left, 0, 3, 2, 0, 1), 0, "a left turn takes the exit's left lane");
        assert_eq!(exit_lane(Turn::SharpLeft, 2, 3, 4, 0, 1), 0);
        assert_eq!(exit_lane(Turn::Right, 2, 3, 3, 0, 1), 2, "a right turn takes the rightmost");
        assert_eq!(exit_lane(Turn::Through, 0, 2, 2, 0, 1), 0, "a through movement holds its place");
        assert_eq!(exit_lane(Turn::Through, 1, 2, 2, 0, 1), 1);
        // Narrowing: the outermost lane cannot land past the exit's last.
        assert_eq!(exit_lane(Turn::Through, 3, 4, 2, 0, 1), 1);
        // A zero-lane exit cannot underflow.
        assert_eq!(exit_lane(Turn::Right, 0, 1, 0, 0, 1), 0);
        // Several lanes making one movement rank across the exit rather than stacking on one lane;
        // `a_movement_shared_by_several_lanes_fills_the_exit_from_its_own_side` owns that rule.
        // What is pinned here is only that the rank cannot run off the far side of the road.
        assert_eq!(exit_lane(Turn::Left, 2, 3, 2, 2, 3), 1);
        assert_eq!(exit_lane(Turn::Right, 0, 3, 2, 0, 3), 0);
        assert_eq!(exit_lane(Turn::Left, 1, 3, 1, 1, 2), 0);
        assert_eq!(exit_lane(Turn::Right, 1, 3, 1, 0, 2), 0);
        assert_eq!(exit_lane(Turn::Right, 2, 3, 1, 1, 2), 0);
    }

    /// A movement several approach lanes share stays several ribbons wide, which is the whole
    /// reason [`exit_lane`] takes a rank at all: without it a dual left turn puts both its lanes on
    /// the exit's leftmost and draws one connector twice on top of itself.
    ///
    /// This pins the **direction each side fills from**, which the clamped cases above cannot: they
    /// all saturate, so they would still pass if left and right filled the same way. A dual left
    /// takes the exit's two leftmost lanes and a dual right its two rightmost, and the difference
    /// between those is the thing worth defending.
    #[test]
    fn a_movement_shared_by_several_lanes_fills_the_exit_from_its_own_side() {
        // Two left-turn lanes into a three-lane exit: the exit's left two, in order.
        assert_eq!(exit_lane(Turn::Left, 0, 3, 3, 0, 2), 0);
        assert_eq!(exit_lane(Turn::Left, 1, 3, 3, 1, 2), 1);
        // Two right-turn lanes into the same exit: the right two. Lanes 1 and 2, not 0 and 1 —
        // the outermost approach lane takes the outermost exit lane, and a right turn that fed
        // the exit's left lane would cross the traffic beside it.
        assert_eq!(exit_lane(Turn::Right, 1, 3, 3, 0, 2), 1);
        assert_eq!(exit_lane(Turn::Right, 2, 3, 3, 1, 2), 2);
        // The two sides genuinely disagree at the same rank, which is what makes the rule a rule
        // rather than a shared clamp.
        assert_ne!(
            exit_lane(Turn::Left, 0, 3, 3, 0, 2),
            exit_lane(Turn::Right, 1, 3, 3, 0, 2),
            "left and right must fill from opposite ends of the exit",
        );
    }

    /// `lanes.bin` is sparse, so absence is the answer for most edges and a missing file is not an
    /// error at all.
    #[test]
    fn a_sparse_lane_table_answers_only_for_the_edges_it_holds() {
        let fixture = crossroads(&[(1u32, vec![LANE_THROUGH]), (4u32, vec![LANE_LEFT, LANE_RIGHT])]);
        let table = LaneTable::load(&fixture.dir).expect("load").expect("a file was written");
        assert_eq!(table.masks(1), Some(vec![LANE_THROUGH]));
        assert_eq!(table.masks(4), Some(vec![LANE_LEFT, LANE_RIGHT]));
        assert_eq!(table.masks(0), None, "edge 0 is not in the index");
        assert_eq!(table.masks(3), None);
        assert_eq!(table.masks(99), None, "past every entry");

        let bare = crossroads(&[]);
        assert!(
            LaneTable::load(&bare.dir).expect("load").is_none(),
            "a graph built without lane data is not an error",
        );
    }

    /// The reverse index, which is the whole reason an approach can be found: `nodes.bin` is
    /// out-edges only.
    #[test]
    fn incoming_edges_are_recovered_from_a_graph_that_stores_only_outgoing_ones() {
        let fixture = crossroads(&[]);
        let graph = Graph::load(&fixture.dir).expect("graph");
        let incoming = InEdges::build(&graph).expect("reverse index");
        // Edges 4..7 all target the junction.
        assert_eq!(incoming.of(0), &[4, 5, 6, 7]);
        // Each outer node is fed by exactly the one edge leaving the junction toward it.
        assert_eq!(incoming.of(1), &[0]);
        assert_eq!(incoming.of(4), &[3]);
        // And every in-edge's source is recoverable from the same `edge_ptr` array.
        assert_eq!(source_of(&graph, 4), 1);
        assert_eq!(source_of(&graph, 7), 4);
        assert_eq!(source_of(&graph, 0), 0);
        assert_eq!(source_of(&graph, 3), 0);
    }

    /// A one-way approach is the case [`InEdges`] exists for: the junction has no out-edge back
    /// toward it, so [`Graph::has_drivable_twin`] could never find it.
    #[test]
    fn a_one_way_approach_is_found_even_though_nothing_leaves_the_junction_toward_it() {
        let coords: [(i32, i32); 4] = [
            (350_000_000, -1_200_000_000), // 0: the junction
            (350_009_000, -1_200_000_000), // 1: north, feeds in one-way
            (350_000_000, -1_199_988_000), // 2: east
            (349_991_000, -1_200_000_000), // 3: south
        ];
        let edge = |source, target| EdgeSpec { source, target, type_: 7, interior: Vec::new() };
        let fixture = write_graph(
            "junction_oneway",
            &coords,
            &[
                // The junction leaves only east and south. Nothing runs back north.
                edge(0, 2),
                edge(0, 3),
                // The one-way approach, plus two-way arms so the node has three neighbours.
                edge(1, 0),
                edge(2, 0),
                edge(3, 0),
            ],
            &[],
        );
        let graph = Graph::load(&fixture.dir).expect("graph");
        assert!(
            !graph.has_drivable_twin(0, 1).expect("twin"),
            "the fixture is only meaningful if nothing leaves the junction northward",
        );
        let (emitted, lines) = stream(&fixture);
        // Three approaches. From the north: east and south are both legal (2). From the east: south
        // only, since the U-turn back east is excluded and nothing runs north (1). From the south:
        // east only (1).
        assert_eq!(emitted, 4, "the one-way approach contributes its two movements");

        // **The asymmetric case, and what pins an exit's lane count to the junction rather than to
        // whichever approach happens to be looking at it.** Here the approaches disagree about how
        // many legal exits they have — the north approach has two, the south has one — so an exit
        // sized from the looking approach's own `legal.len()` is two lanes wide to one of them and
        // one lane wide to the other. The east arm's two connectors then both resolve to lane 0 and
        // share a point, and this reads 3. Sized from the arm's own feeders it is two lanes wide to
        // everyone, and the left turn into it and the right turn into it end a lane apart.
        //
        // The symmetric crossroads cannot catch this: there every approach has the same number of
        // legal exits, so the wrong count and the right one are the same number.
        let at = |p: (f64, f64)| ((p.0 * 1e7).round() as i64, (p.1 * 1e7).round() as i64);
        let ends: BTreeSet<(i64, i64)> =
            lines.iter().map(|l| at(*l.last().expect("a connector has points"))).collect();
        assert_eq!(
            ends.len(),
            4,
            "{} distinct endpoints for 4 connectors at an asymmetric junction: an exit's lane \
             count is varying with the approach that is looking at it",
            ends.len(),
        );
    }

    /// Left-hand traffic mirrors the connectors rather than leaving them on the wrong side.
    #[test]
    fn a_left_hand_traffic_country_gets_its_connectors_on_the_other_side() {
        let fixture = crossroads(&[]);
        let spill = fixture.dir.join("lht.tmp");

        let mut left = Conventions::default();
        // A ring around the fixture's coordinates, so its z6 cell is claimed for a left-hand
        // country. `add` takes lon/lat rings, outer first.
        left.add(
            "GB",
            &[vec![vec![
                (-121.0, 34.0),
                (-119.0, 34.0),
                (-119.0, 36.0),
                (-121.0, 36.0),
                (-121.0, 34.0),
            ]]],
        );

        let mut sink = Sink::create(&spill).expect("sink");
        stream_junctions(&fixture.dir, &left, &mut sink).expect("stream");
        let store = sink.finish(&spill).expect("finish");
        let mut reader = store.reader().expect("reader");
        let mut mirrored = Vec::new();
        while let Some(feature) = reader.next().expect("read") {
            if let Geometry::Lines(parts) = &feature.geometry {
                mirrored.push(parts[0].clone());
            }
        }
        let _ = std::fs::remove_file(&spill);

        let (_, right) = stream(&fixture);
        assert_eq!(mirrored.len(), right.len(), "the same movements exist either side of the world");
        assert_ne!(mirrored, right, "left-hand traffic must not draw the right-hand connectors");
    }
}
