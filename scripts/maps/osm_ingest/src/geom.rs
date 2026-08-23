//! Delta encoder for `intermediate.bin` — the per-edge geometry blob.
//!
//! The reader is `maps/src/main/rust/src/graph.rs`'s `decode_edge_coords`, and
//! it is the authority for every rule here:
//!
//! * **Only the interior points are stored.** A polyline's first point is its
//!   edge's source node and its last is its target, and the reader has both
//!   from `nodes.bin` already, so storing them costs 12 bytes per edge to
//!   repeat what is known. A blob is `m - 2` pairs of `i16` deltas, i.e.
//!   `4 * (m - 2)` bytes, and the reader recovers `m = byte_len / 4 + 2`.
//! * The first delta is relative to the source, and the delta that would land
//!   on the target is the one omitted. An **empty** blob is therefore a
//!   well-formed two-point polyline — the chord — and not a special case.
//! * The reader adds deltas with `wrapping_add`, so a delta that does not fit
//!   is silent corruption rather than an error — hence [`fits`] and the
//!   assertions in [`encode`].
//! * The decode loop stops at [`MAX_POINTS`] points, and every caller in
//!   `routing.rs` passes a `[LatLon; 256]`, so an edge may hold at most that
//!   many. Beyond it the reader would silently truncate the polyline.
//!
//! Whether an edge stores a blob at all is *not* spelled here: `intermediate.bin`
//! carries one presence bit per directed edge, so "this edge has no stored
//! geometry" is a bitmap test rather than a length.
//!
//! Where a real gap between two consecutive vertices exceeds the `i16` range we
//! insert extra points *on the straight line between them*. That leaves the
//! polyline geometrically identical — the inserted vertices are collinear with
//! the pair they split — and costs 4 bytes each. This is why the encoder can
//! promise exact geometry while still obeying a 3.2767°/step limit.

/// Most points one edge may carry, endpoints included. `graph.rs`'s decode loop
/// stops there and its callers allocate exactly `[LatLon; 256]`, so the largest
/// legal blob is `4 * (MAX_POINTS - 2)` = 1016 bytes.
pub const MAX_POINTS: u32 = 256;

/// Largest per-axis step the `i16` deltas can express. `i16::MIN` would also
/// fit, but keeping the bound symmetric means [`interp_steps`] needs only the
/// magnitude.
const MAX_DELTA: i64 = i16::MAX as i64;

/// A geometry vertex in the graph's fixed-point degrees.
pub type Pt = (i32, i32);

/// How many encoded points it takes to travel from `a` to `b`, not counting
/// `a`. Exactly 1 when the gap already fits in `i16` deltas, more when
/// collinear interpolation is needed.
#[inline]
pub fn segment_points(a: Pt, b: Pt) -> u64 {
    interp_steps(a, b)
}

/// Total encoded points for a whole polyline, interpolation included. Compare
/// against [`MAX_POINTS`] to decide whether an edge must be split.
pub fn encoded_points(points: &[Pt]) -> u64 {
    if points.is_empty() {
        return 0;
    }
    let mut n = 1u64;
    for w in points.windows(2) {
        n += interp_steps(w[0], w[1]);
    }
    n
}

/// Steps needed to get from `a` to `b` with no axis moving more than
/// [`MAX_DELTA`] per step. Widened to `i64` because a segment spanning the
/// antimeridian has a longitude difference of up to 3.6e9, which overflows
/// `i32`.
#[inline]
fn interp_steps(a: Pt, b: Pt) -> u64 {
    let dlat = (i64::from(b.0) - i64::from(a.0)).abs();
    let dlon = (i64::from(b.1) - i64::from(a.1)).abs();
    let worst = dlat.max(dlon);
    if worst <= MAX_DELTA {
        return 1;
    }
    // Ceiling division; `worst > 0` here so this cannot be zero.
    ((worst + MAX_DELTA - 1) / MAX_DELTA) as u64
}

/// True when `points` can be encoded without truncation.
pub fn fits(points: &[Pt]) -> bool {
    points.len() >= 2 && encoded_points(points) <= u64::from(MAX_POINTS)
}

/// Append the encoded form of `points` to `out`, returning the number of points
/// the reader will decode — which is two more than the number of deltas written.
///
/// Panics if the result would exceed [`MAX_POINTS`]: that is a builder bug, and
/// the failure mode on device (a silently shortened polyline) is much worse than
/// a failed build. Callers split or drop geometry themselves, using [`fits`].
pub fn encode(points: &[Pt], out: &mut Vec<u8>) -> u32 {
    if points.len() < 2 {
        return 0;
    }
    let total = encoded_points(points);
    assert!(
        total <= u64::from(MAX_POINTS),
        "edge geometry needs {total} encoded points, over the {MAX_POINTS} the reader can decode"
    );

    let mut count = 1u64;
    let mut cur = points[0];

    for w in points.windows(2) {
        let (a, b) = (w[0], w[1]);
        let steps = interp_steps(a, b);
        let dlat = i64::from(b.0) - i64::from(a.0);
        let dlon = i64::from(b.1) - i64::from(a.1);
        for s in 1..=steps {
            // Truncating division is fine and deliberate: at s == steps it
            // yields exactly `b`, so interpolation never moves an original
            // vertex, and every intermediate point lies on the a->b line.
            let s = s as i64;
            let steps_i = steps as i64;
            let next = (
                (i64::from(a.0) + dlat * s / steps_i) as i32,
                (i64::from(a.1) + dlon * s / steps_i) as i32,
            );
            let step_lat = i64::from(next.0) - i64::from(cur.0);
            let step_lon = i64::from(next.1) - i64::from(cur.1);
            debug_assert!(
                step_lat.abs() <= MAX_DELTA && step_lon.abs() <= MAX_DELTA,
                "delta ({step_lat}, {step_lon}) does not fit in i16"
            );
            count += 1;
            // The last point is the edge's target, which the reader reads out of
            // `nodes.bin`, so the delta that lands on it is not stored. Neither
            // is the absolute first point, for the same reason.
            if count < total {
                out.extend_from_slice(&(step_lat as i16).to_le_bytes());
                out.extend_from_slice(&(step_lon as i16).to_le_bytes());
            }
            cur = next;
        }
    }
    debug_assert_eq!(cur, points[points.len() - 1], "encoder lost the final vertex");
    debug_assert_eq!(count, total, "encoder disagreed with its own point count");
    count as u32
}

/// Reimplementation of `graph.rs`'s `decode_edge_coords`, for tests and for the
/// build's own verification pass. Kept deliberately literal — including the
/// `wrapping_add` and the [`MAX_POINTS`] stop — so that a divergence between the
/// two shows up here rather than on a phone.
///
/// `source` and `target` are the edge's own endpoints, which is what lets the
/// blob hold nothing but the interior: an empty `bytes` decodes to the chord.
pub fn decode(bytes: &[u8], source: Pt, target: Pt) -> Vec<Pt> {
    let mut out = vec![source];
    let (mut lat, mut lon) = source;
    let mut off = 0usize;
    // One slot is reserved for `target`, so the interior stops one short.
    while off + 4 <= bytes.len() && (out.len() as u32) + 1 < MAX_POINTS {
        let d_lat = i16::from_le_bytes([bytes[off], bytes[off + 1]]);
        let d_lon = i16::from_le_bytes([bytes[off + 2], bytes[off + 3]]);
        lat = lat.wrapping_add(i32::from(d_lat));
        lon = lon.wrapping_add(i32::from(d_lon));
        out.push((lat, lon));
        off += 4;
    }
    out.push(target);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every original vertex must survive a round trip, and the inserted points
    /// must lie on the segment they split.
    ///
    /// The endpoints are handed to [`decode`] rather than read out of the blob,
    /// exactly as the device takes them from `nodes.bin`.
    fn round_trip(points: &[Pt]) -> Vec<Pt> {
        let mut buf = Vec::new();
        let n = encode(points, &mut buf);
        let got = decode(&buf, points[0], points[points.len() - 1]);
        assert_eq!(n as usize, got.len(), "encode count vs decode count");
        assert_eq!(buf.len(), 4 * (got.len() - 2), "byte length");
        // The endpoints are never interpolated away, and every original vertex
        // appears in order.
        let mut it = got.iter();
        for p in points {
            assert!(it.any(|q| q == p), "original vertex {p:?} missing from {got:?}");
        }
        got
    }

    #[test]
    fn short_polyline_needs_no_interpolation() {
        let pts = vec![(370_000_000, -1_220_000_000), (370_001_000, -1_220_002_000)];
        let got = round_trip(&pts);
        assert_eq!(got, pts);
        assert_eq!(encoded_points(&pts), 2);
    }

    #[test]
    fn a_long_gap_is_split_into_collinear_points() {
        // 0.5 degrees of latitude is 5_000_000 e7 units, about 153 times the
        // i16 limit — long enough to interpolate heavily, short enough to stay
        // inside the 256-point ceiling.
        let a = (350_000_000, -1_200_000_000);
        let b = (355_000_000, -1_200_000_000);
        assert_eq!(interp_steps(a, b), 153);
        let got = round_trip(&[a, b]);
        assert_eq!(got.len(), 154);
        // Collinear: longitude never moves, latitude is monotonic.
        assert!(got.iter().all(|p| p.1 == -1_200_000_000));
        assert!(got.windows(2).all(|w| w[0].0 < w[1].0));
        assert_eq!(*got.last().unwrap(), b);
    }

    #[test]
    fn diagonal_interpolation_stays_on_the_line() {
        let a = (350_000_000, -1_200_000_000);
        let b = (350_500_000, -1_199_000_000);
        let got = round_trip(&[a, b]);
        // Cross product against the a->b direction must be tiny for every point
        // (exact zero is impossible with integer rounding).
        let (dx, dy) = (i64::from(b.0 - a.0), i64::from(b.1 - a.1));
        for p in &got {
            let (px, py) = (i64::from(p.0 - a.0), i64::from(p.1 - a.1));
            let cross = (dx * py - dy * px).abs();
            let scale = dx.abs().max(dy.abs());
            assert!(cross <= scale * 2, "point {p:?} is off the line: cross={cross}");
        }
    }

    #[test]
    fn negative_deltas_round_trip() {
        let pts = vec![
            (400_000_000, 1_100_000_000),
            (399_960_000, 1_099_950_000),
            (399_800_000, 1_099_900_000),
        ];
        let got = round_trip(&pts);
        assert_eq!(*got.first().unwrap(), pts[0]);
        assert_eq!(*got.last().unwrap(), pts[2]);
    }

    #[test]
    fn a_delta_exactly_at_the_limit_is_one_step() {
        let a = (0, 0);
        let b = (32_767, -32_767);
        assert_eq!(interp_steps(a, b), 1);
        assert_eq!(round_trip(&[a, b]), vec![a, b]);
        // One unit further needs two.
        assert_eq!(interp_steps(a, (32_768, 0)), 2);
    }

    #[test]
    fn fits_rejects_what_the_reader_would_truncate() {
        let a = (0, 0);
        // 256 points is the reader's ceiling, so 255 interpolation steps is the
        // most a single segment may need.
        let ok = (255 * 32_767, 0);
        assert_eq!(encoded_points(&[a, ok]), 256);
        assert!(fits(&[a, ok]));
        let too_far = (255 * 32_767 + 1, 0);
        assert_eq!(encoded_points(&[a, too_far]), 257);
        assert!(!fits(&[a, too_far]));
        // Degenerate inputs are not encodable geometry.
        assert!(!fits(&[]));
        assert!(!fits(&[a]));
    }

    #[test]
    fn an_empty_blob_decodes_to_the_chord() {
        // The interior is all that is stored, so an edge with no interior stores
        // nothing at all and the two endpoints alone are a valid polyline. This
        // is why `intermediate.bin` needs a presence bit rather than a length
        // test: a zero-length blob is meaningful.
        let mut buf = Vec::new();
        assert_eq!(encode(&[(1, 2), (3, 4)], &mut buf), 2);
        assert!(buf.is_empty(), "a two-point polyline is pure redundancy");
        assert_eq!(decode(&buf, (1, 2), (3, 4)), vec![(1, 2), (3, 4)]);
        assert_eq!(decode(&[], (1, 2), (3, 4)), vec![(1, 2), (3, 4)]);
    }

    #[test]
    fn a_trailing_partial_delta_is_ignored() {
        // Matching the reader's `off + 4 <= byte_len` guard. A real blob is
        // always a multiple of 4, so this only ever fires on a corrupt file.
        let mut buf = Vec::new();
        let _ = encode(&[(1, 2), (3, 4), (5, 7)], &mut buf);
        assert_eq!(buf.len(), 4);
        buf.push(0);
        assert_eq!(decode(&buf, (1, 2), (5, 7)), vec![(1, 2), (3, 4), (5, 7)]);
    }

    #[test]
    fn the_largest_legal_blob_is_1016_bytes() {
        // `MAX_POINTS` points means `MAX_POINTS - 2` stored deltas, which is the
        // bound `intermediate.bin`'s within-block u16 rests on.
        let pts: Vec<Pt> = (0..MAX_POINTS as i32).map(|i| (i * 1_000, 0)).collect();
        assert_eq!(encoded_points(&pts), u64::from(MAX_POINTS));
        let mut buf = Vec::new();
        assert_eq!(encode(&pts, &mut buf), MAX_POINTS);
        assert_eq!(buf.len(), 4 * (MAX_POINTS as usize - 2));
        assert_eq!(buf.len(), 1016);
        assert_eq!(round_trip(&pts), pts);
    }

    #[test]
    fn empty_output_for_fewer_than_two_points() {
        let mut buf = Vec::new();
        assert_eq!(encode(&[], &mut buf), 0);
        assert_eq!(encode(&[(1, 1)], &mut buf), 0);
        assert!(buf.is_empty());
    }
}
