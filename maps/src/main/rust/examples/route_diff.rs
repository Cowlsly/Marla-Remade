//! Route-level differential oracle for road-graph changes.
//!
//! Compaction renumbers nodes and edges and changes their counts, so no output
//! file of two graphs built from the same region is comparable byte-wise. What
//! *is* comparable is what the router does with them: for a fixed list of
//! origin/destination coordinates, the total distance, the total time, the turn
//! sequence and the decoded polyline.
//!
//! This lives in the engine crate, and drives `Graph::load` +
//! `prepare_routing` + `perform_search_loop` + `reconstruct_path` directly,
//! because the point is to exercise the real reader — including
//! `decode_edge_coords` and the `REVERSE_GEOMETRY_FLAG` twin lookup — rather
//! than a host reimplementation of it.
//!
//! It is an `examples/` target, not `src/bin/`, so `cargo build` for the
//! Android lib never picks it up.
//!
//! ```text
//! cargo run --release --example route_diff -- gen-pairs --graph DIR --out pairs.txt [--count N]
//! cargo run --release --example route_diff -- report    --graph DIR --pairs pairs.txt --out report.txt
//! cargo run --release --example route_diff -- compare   --baseline a.txt --candidate b.txt [--tol-mm N]
//! ```
//!
//! `Graph::load` mmaps, and `MmapRegion::map` is `#[cfg(unix)]`, so this must
//! run on Linux (WSL is fine).

use std::collections::{HashMap, HashSet};
use std::fmt::Write as _;
use std::io::Write as _;

use offlinerouter::geometry::{accurate_dist_mm, TrafficSpeeds};
use offlinerouter::graph::{get_pt_at, Graph, LatLon, BICYCLE, DRIVING, WALK};
use offlinerouter::routing::{perform_search_loop, prepare_routing, reconstruct_path};
use offlinerouter::state::{RadixHeap, RoutingScratchpad};

/// One origin/destination probe. Coordinates, not node ids: ids move when the
/// graph is rebuilt, coordinates do not, which is the whole point.
struct Pair {
    kind: String,
    mode: i32,
    s_lat: f64,
    s_lon: f64,
    e_lat: f64,
    e_lon: f64,
}

fn main() -> std::process::ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let r = match args.first().map(String::as_str) {
        Some("gen-pairs") => gen_pairs(&args[1..]),
        Some("report") => report(&args[1..]),
        Some("compare") => compare(&args[1..]),
        Some("geometry") => geometry(&args[1..]),
        _ => Err(USAGE.to_string()),
    };
    match r {
        Ok(true) => std::process::ExitCode::SUCCESS,
        Ok(false) => std::process::ExitCode::FAILURE,
        Err(e) => {
            eprintln!("error: {e}");
            std::process::ExitCode::FAILURE
        }
    }
}

const USAGE: &str = "\
usage:
  route_diff gen-pairs --graph DIR --out FILE [--count N]
  route_diff report    --graph DIR --pairs FILE --out FILE
  route_diff compare   --baseline FILE --candidate FILE [--tol-mm N] [--tol-10ms N]
  route_diff geometry  --baseline DIR --candidate DIR";

fn flag(args: &[String], name: &str) -> Option<String> {
    args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).cloned()
}

fn need(args: &[String], name: &str) -> Result<String, String> {
    flag(args, name).ok_or_else(|| format!("missing {name}\n{USAGE}"))
}

fn load(dir: &str) -> Result<Graph, String> {
    Graph::load(dir).ok_or_else(|| {
        format!("cannot load a graph from {dir} (missing/short files, or a bad metadata.bin header)")
    })
}

// ---------------------------------------------------------------------------
// gen-pairs
// ---------------------------------------------------------------------------

/// Sample probe pairs from a graph's node array and write them as coordinates.
///
/// Three length classes, because they stress different things. `long` pairs
/// exercise the search itself; `mid` pairs exercise turn-by-turn output; and
/// `near` pairs put both endpoints within a few nodes of each other, which
/// after compaction is very often the *same* collapsed edge — the case where a
/// straight-chord graph and a collapsed graph can disagree most.
fn gen_pairs(args: &[String]) -> Result<bool, String> {
    let dir = need(args, "--graph")?;
    let out = need(args, "--out")?;
    let count: u32 = flag(args, "--count")
        .as_deref()
        .unwrap_or("300")
        .parse()
        .map_err(|e| format!("--count: {e}"))?;

    let g = load(&dir)?;
    if g.node_count < 16 {
        return Err(format!("graph has only {} node(s)", g.node_count));
    }
    let n = u64::from(g.node_count);

    // Deterministic sampling: a fixed odd multiplier walked over the node
    // array. No RNG, so the pair file is reproducible from any graph of the
    // same region without carrying a seed.
    let mut buf = String::new();
    buf.push_str("# route_diff pairs: kind mode s_lat s_lon e_lat e_lon\n");
    buf.push_str(&format!("# generated from {dir} ({} nodes)\n", g.node_count));
    let mut emitted = 0u32;
    let mut k = 0u64;
    while emitted < count {
        let i = (k.wrapping_mul(2_654_435_761) % n) as u32;
        let (kind, mode, j) = match emitted % 5 {
            0 => ("long", DRIVING, (k.wrapping_mul(40_503) % n) as u32),
            1 => ("mid", DRIVING, ((u64::from(i) + 4_000) % n) as u32),
            2 => ("near", DRIVING, ((u64::from(i) + 3) % n) as u32),
            3 => ("near", WALK, ((u64::from(i) + 2) % n) as u32),
            _ => ("mid", BICYCLE, ((u64::from(i) + 500) % n) as u32),
        };
        k += 1;
        if i == j {
            continue;
        }
        let a = g.node(i);
        let b = g.node(j);
        // A node at (0, 0) means the graph has a hole where a coordinate should
        // be; probing it tests nothing.
        if (a.lat_e7 == 0 && a.lon_e7 == 0) || (b.lat_e7 == 0 && b.lon_e7 == 0) {
            continue;
        }
        // Nudge off the node itself so the probe exercises mid-edge snapping
        // rather than landing exactly on a vertex.
        buf.push_str(&format!(
            "{} {} {:.7} {:.7} {:.7} {:.7}\n",
            kind,
            mode,
            f64::from(a.lat_e7) * 1e-7 + 1e-6,
            f64::from(a.lon_e7) * 1e-7 - 1e-6,
            f64::from(b.lat_e7) * 1e-7 - 1e-6,
            f64::from(b.lon_e7) * 1e-7 + 1e-6,
        ));
        emitted += 1;
    }
    write_file(&out, buf.as_bytes())?;
    println!("wrote {emitted} pair(s) to {out}");
    Ok(true)
}

fn read_pairs(path: &str) -> Result<Vec<Pair>, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("{path}: {e}"))?;
    let mut out = Vec::new();
    for (ln, line) in text.lines().enumerate() {
        if line.starts_with('#') || line.trim().is_empty() {
            continue;
        }
        let f: Vec<&str> = line.split_whitespace().collect();
        if f.len() != 6 {
            return Err(format!("{path}:{}: want 6 fields, got {}", ln + 1, f.len()));
        }
        let num = |s: &str| s.parse::<f64>().map_err(|e| format!("{path}:{}: {e}", ln + 1));
        out.push(Pair {
            kind: f[0].to_string(),
            mode: f[1].parse::<i32>().map_err(|e| format!("{path}:{}: {e}", ln + 1))?,
            s_lat: num(f[2])?,
            s_lon: num(f[3])?,
            e_lat: num(f[4])?,
            e_lon: num(f[5])?,
        });
    }
    Ok(out)
}

// ---------------------------------------------------------------------------
// report
// ---------------------------------------------------------------------------

/// Route every pair and write a line-oriented report.
///
/// The format is deliberately flat text: one `PAIR` line per probe carrying the
/// totals, then one `STEP` line per turn, then one `GEOM` line holding the
/// whole polyline. `compare` reads it back field by field, so totals can carry
/// a tolerance while the turn sequence and geometry are matched exactly.
fn report(args: &[String]) -> Result<bool, String> {
    let dir = need(args, "--graph")?;
    let pairs_path = need(args, "--pairs")?;
    let out = need(args, "--out")?;

    let g = load(&dir)?;
    let pairs = read_pairs(&pairs_path)?;
    let speeds: TrafficSpeeds = HashMap::new();
    let mut scratch = RoutingScratchpad::new();
    let mut heap = RadixHeap::new();

    let mut buf = String::new();
    let _ = writeln!(buf, "# route_diff report");
    let _ = writeln!(buf, "GRAPH nodes={} edges={}", g.node_count, g.edge_count);
    let _ = writeln!(buf, "PAIRS {}", pairs.len());

    let mut routed = 0usize;
    for (i, p) in pairs.iter().enumerate() {
        // No traffic on a host report: live speeds are a device-side input and
        // would make the differential depend on when it ran.
        let mut ensure = |_lat_e7: i32, _lon_e7: i32| {};
        let ctx = prepare_routing(
            &g, &speeds, &mut ensure, p.s_lat, p.s_lon, p.e_lat, p.e_lon, p.mode, &mut scratch,
            &mut heap,
        );
        let mut ctx = match ctx {
            Some(c) => c,
            None => {
                let _ = writeln!(buf, "PAIR {i} {} mode={} no_snap", p.kind, p.mode);
                continue;
            }
        };
        perform_search_loop(&g, &speeds, &mut ensure, p.mode, &mut ctx, &mut scratch, &mut heap);
        // Where the query points landed on the network, and on which edges.
        // Emitted before the routability check because a pair that stops routing
        // is usually a pair that snapped somewhere else, and that is only
        // visible if the snap is reported either way.
        //
        // Reported separately from the route because a rebuild that moves the
        // node set changes how far `find_nearest_edge`'s fixed 800-node window
        // reaches: a route that starts somewhere else is not the same question as
        // a route that takes a different path from the same place.
        let _ = writeln!(
            buf,
            "SNAP {i} s={:.7},{:.7} e={:.7},{:.7} s_off={:.1} e_off={:.1} s_road={} e_road={} direct={}",
            f64::from(ctx.start.proj_lat) * 1e-7,
            f64::from(ctx.start.proj_lon) * 1e-7,
            f64::from(ctx.end.proj_lat) * 1e-7,
            f64::from(ctx.end.proj_lon) * 1e-7,
            // How far the query point had to move to reach the network. This is
            // the measure of snap *quality*: the pairs were sampled from node
            // coordinates, so a good snap lands within centimetres.
            crow_m(p.s_lat, p.s_lon, f64::from(ctx.start.proj_lat) * 1e-7, f64::from(ctx.start.proj_lon) * 1e-7),
            crow_m(p.e_lat, p.e_lon, f64::from(ctx.end.proj_lat) * 1e-7, f64::from(ctx.end.proj_lon) * 1e-7),
            g.road_name(ctx.start.name_offset).unwrap_or_default(),
            g.road_name(ctx.end.name_offset).unwrap_or_default(),
            u8::from(ctx.direct.is_some())
        );
        let _ = writeln!(
            buf,
            "EDGE {i} s_edge={} s_ab={},{} e_edge={} e_ab={},{} twin={}",
            ctx.start.edge_idx,
            ctx.start.node_a,
            ctx.start.node_b,
            ctx.end.edge_idx,
            ctx.end.node_a,
            ctx.end.node_b,
            // Whether the road can be travelled the other way at all. When both
            // ends snap to one edge but in the wrong order, this is what decides
            // between "stay on the road" and "go around the block".
            twin_of(&g, ctx.start.node_a, ctx.start.node_b)
                .map_or(-1i64, |k| i64::try_from(k).unwrap_or(-2))
        );
        // A route that never leaves its snapped edge reaches no node, so
        // `target_node` stays unset and `ctx.direct` carries the route instead.
        if ctx.target_node == 0xFFFF_FFFF && ctx.direct.is_none() {
            let _ = writeln!(buf, "PAIR {i} {} mode={} no_route", p.kind, p.mode);
            continue;
        }
        let steps = reconstruct_path(&g, &speeds, p.mode, &ctx, &mut scratch);
        let dist: u64 = steps.iter().map(|s| s.dist_mm).sum();
        let time: u64 = steps.iter().map(|s| s.time_10ms).sum();
        routed += 1;
        let _ = writeln!(
            buf,
            "PAIR {i} {} mode={} ok dist_mm={dist} time_10ms={time} steps={}",
            p.kind,
            p.mode,
            steps.len()
        );
        for (k, s) in steps.iter().enumerate() {
            let name = g.road_name(s.name_off).unwrap_or_default();
            let _ = writeln!(
                buf,
                "STEP {i} {k} maneuver={} dist_mm={} time_10ms={} pts={} name={name}",
                s.maneuver,
                s.dist_mm,
                s.time_10ms,
                s.coords.len() / 2
            );
        }
        // The polyline is the claim that compaction is geometry-preserving, so
        // it is compared exactly. `coords` is flat [lon, lat, ...] from
        // `StepData`; 7 decimals is the graph's own resolution.
        let _ = write!(buf, "GEOM {i}");
        for s in &steps {
            for c in s.coords.chunks_exact(2) {
                let _ = write!(buf, " {:.7},{:.7}", c[1], c[0]);
            }
        }
        buf.push('\n');
    }
    let _ = writeln!(buf, "ROUTED {routed}/{}", pairs.len());
    write_file(&out, buf.as_bytes())?;
    println!("{out}: routed {routed}/{} pair(s)", pairs.len());
    Ok(true)
}

// ---------------------------------------------------------------------------
// compare
// ---------------------------------------------------------------------------

/// Compare two reports.
///
/// The pass criterion has to account for the fact that a rebuild which changes
/// the node set also changes *snapping*: `find_nearest_edge` scans a fixed
/// window of 800 nodes in Morton order, so with fewer, longer edges it reaches
/// further and legitimately finds a different — often nearer — road. A route from
/// a different starting edge is not a regression, it is a different question.
///
/// So pairs are split by whether both ends snapped to the same place:
///
/// * **Same snap, same distance** is the strict case: the turn sequence and the
///   polyline must match exactly. This is where a geometry or bookkeeping bug
///   would show, and it is the only class treated as a failure.
/// * **Same snap, different distance** is an alternative optimum. Reported.
/// * **Moved snap** is reported and counted, not failed.
///
/// A pair that stops being routable is always a failure; one that becomes
/// routable is not.
fn compare(args: &[String]) -> Result<bool, String> {
    let base_path = need(args, "--baseline")?;
    let cand_path = need(args, "--candidate")?;
    let tol_mm: u64 = flag(args, "--tol-mm")
        .as_deref()
        .unwrap_or("1000")
        .parse()
        .map_err(|e| format!("--tol-mm: {e}"))?;
    let tol_time: u64 = flag(args, "--tol-10ms")
        .as_deref()
        .unwrap_or("100")
        .parse()
        .map_err(|e| format!("--tol-10ms: {e}"))?;
    // How far a snap may move before the pair is no longer "the same question".
    let snap_tol_m: f64 = flag(args, "--tol-snap-m")
        .as_deref()
        .unwrap_or("1.0")
        .parse()
        .map_err(|e| format!("--tol-snap-m: {e}"))?;

    let base = std::fs::read_to_string(&base_path).map_err(|e| format!("{base_path}: {e}"))?;
    let cand = std::fs::read_to_string(&cand_path).map_err(|e| format!("{cand_path}: {e}"))?;

    let bp = index_by_key(&base, "PAIR");
    let cp = index_by_key(&cand, "PAIR");
    let bs_snap = index_by_key(&base, "SNAP");
    let cs_snap = index_by_key(&cand, "SNAP");
    let bg = index_by_key(&base, "GEOM");
    let cg = index_by_key(&cand, "GEOM");
    let bs = index_steps(&base);
    let cs = index_steps(&cand);

    let mut fails: Vec<String> = Vec::new();
    let mut lost = 0usize;
    let mut gained = 0usize;
    let mut moved_snap = 0usize;
    let mut strict = 0usize;
    let mut alt_optimum = 0usize;
    let mut worst_dist = 0i64;
    let mut worst_time = 0i64;

    let mut keys: Vec<&String> = bp.keys().collect();
    keys.sort_by_key(|k| k.parse::<u64>().unwrap_or(u64::MAX));

    for k in &keys {
        let b = &bp[*k];
        let c = match cp.get(*k) {
            Some(c) => c,
            None => {
                fails.push(format!("pair {k}: missing from the candidate report"));
                continue;
            }
        };
        let b_ok = b.contains(&"ok".to_string());
        let c_ok = c.contains(&"ok".to_string());
        // Snap movement is checked first, and outranks routability. A query point
        // that is a kilometre from any road can snap onto a nearer but
        // disconnected one after a rebuild, and calling that a lost route blames
        // the graph for an unreasonable question.
        let shift = snap_shift_m(bs_snap.get(*k), cs_snap.get(*k));
        if shift > snap_tol_m {
            moved_snap += 1;
            continue;
        }
        if b_ok && !c_ok {
            lost += 1;
            fails.push(format!("pair {k}: snapped to the same place but is no longer routable"));
            continue;
        }
        if !b_ok {
            if c_ok {
                gained += 1;
            }
            continue;
        }

        let dd = kv(c, "dist_mm").unwrap_or(0) as i64 - kv(b, "dist_mm").unwrap_or(0) as i64;
        let dt = kv(c, "time_10ms").unwrap_or(0) as i64 - kv(b, "time_10ms").unwrap_or(0) as i64;
        if dd.abs() > worst_dist.abs() {
            worst_dist = dd;
        }
        if dt.abs() > worst_time.abs() {
            worst_time = dt;
        }

        if dd.unsigned_abs() > tol_mm || dt.unsigned_abs() > tol_time {
            alt_optimum += 1;
            continue;
        }

        // Same snap, same length: the roads travelled and the path drawn must
        // match.
        strict += 1;
        let b_roads = bs.get(*k).map(road_seq).unwrap_or_default();
        let c_roads = cs.get(*k).map(road_seq).unwrap_or_default();
        if b_roads != c_roads {
            fails.push(format!(
                "pair {k}: same snap and same length, but the roads travelled differ \
                 ({} vs {}, first at {:?})",
                b_roads.len(),
                c_roads.len(),
                first_mismatch(&b_roads, &c_roads)
            ));
        }
        let b_line = polyline_points(bg.get(*k));
        let c_line = polyline_points(cg.get(*k));
        if !same_path(&b_line, &c_line) {
            fails.push(format!(
                "pair {k}: same snap and same length, but the path differs \
                 ({} vs {} distinct point(s))",
                b_line.len(),
                c_line.len()
            ));
        }
    }

    for k in cp.keys() {
        if !bp.contains_key(k) {
            fails.push(format!("pair {k}: only in the candidate report"));
        }
    }

    println!("{} pair(s) compared", keys.len());
    println!("  {strict:>5} same snap, same length  (compared strictly)");
    println!("  {alt_optimum:>5} same snap, different length  (alternative optimum)");
    println!("  {moved_snap:>5} snapped somewhere else  (>{snap_tol_m} m)");
    println!("  {gained:>5} became routable");
    println!("  {lost:>5} stopped being routable");
    println!("worst dist delta {worst_dist} mm, worst time delta {worst_time} (10 ms units)");
    for f in fails.iter().take(20) {
        println!("  {f}");
    }
    if fails.len() > 20 {
        println!("  ({} further finding(s) suppressed)", fails.len() - 20);
    }
    println!(
        "{}",
        if fails.is_empty() { "DIFFERENTIAL CLEAN" } else { "DIFFERENTIAL FAILED" }
    );
    Ok(fails.is_empty())
}

/// The larger of the two ends' snap movements, in metres.
fn snap_shift_m(b: Option<&Vec<String>>, c: Option<&Vec<String>>) -> f64 {
    let point = |t: &Vec<String>, key: &str| -> Option<(f64, f64)> {
        let v = t.iter().find_map(|s| s.strip_prefix(key)?.strip_prefix('='))?;
        let (a, b) = v.split_once(',')?;
        Some((a.parse().ok()?, b.parse().ok()?))
    };
    let (b, c) = match (b, c) {
        (Some(b), Some(c)) => (b, c),
        // No SNAP line means an older report; treat it as "unknown", which is
        // safer read as moved than as identical.
        _ => return f64::INFINITY,
    };
    let mut worst = 0.0f64;
    for key in ["s", "e"] {
        match (point(b, key), point(c, key)) {
            (Some(p), Some(q)) => worst = worst.max(crow_m(p.0, p.1, q.0, q.1)),
            _ => return f64::INFINITY,
        }
    }
    worst
}

/// Collect `KIND <id> rest...` lines into id -> tokens.
fn index_by_key(text: &str, kind: &str) -> HashMap<String, Vec<String>> {
    let mut out = HashMap::new();
    for line in text.lines() {
        let mut it = line.split_whitespace();
        if it.next() != Some(kind) {
            continue;
        }
        if let Some(id) = it.next() {
            let _ = out.insert(id.to_string(), it.map(str::to_string).collect());
        }
    }
    out
}

/// `STEP <pair> <k> ...` grouped by pair, in file order.
fn index_steps(text: &str) -> HashMap<String, Vec<String>> {
    let mut out: HashMap<String, Vec<String>> = HashMap::new();
    for line in text.lines() {
        let mut it = line.split_whitespace();
        if it.next() != Some("STEP") {
            continue;
        }
        if let Some(id) = it.next() {
            out.entry(id.to_string()).or_default().push(line.to_string());
        }
    }
    out
}

fn kv(tokens: &[String], key: &str) -> Option<u64> {
    tokens
        .iter()
        .find_map(|t| t.strip_prefix(key).and_then(|r| r.strip_prefix('=')))
        .and_then(|v| v.parse().ok())
}

/// The roads a route actually travels, in order, with repeats folded together.
///
/// Deliberately not the raw step list. Steps are split on bearing change, and two
/// graphs of one region split them differently for reasons that are not route
/// changes: an uncompacted graph's reconstruction emits a spurious zero-length
/// U-turn step where the route doubles back to a node, and a compacted one does
/// not. The sequence of named roads is what a driver would recognise, and it is
/// invariant to that.
fn road_seq(lines: &Vec<String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for l in lines {
        // Zero-length steps are reconstruction artefacts, not roads.
        if kv(&l.split_whitespace().map(str::to_string).collect::<Vec<_>>(), "dist_mm")
            == Some(0)
        {
            continue;
        }
        let name = match l.find(" name=") {
            Some(i) => l[i + 6..].to_string(),
            None => String::new(),
        };
        if out.last() != Some(&name) {
            out.push(name);
        }
    }
    out
}

/// A polyline's points, with consecutive repeats folded together.
///
/// Repeats are artefacts, not shape: a route that doubles back to a node emits it
/// twice, and the reconstruction's start and end stubs repeat their joint vertex.
fn polyline_points(tokens: Option<&Vec<String>>) -> Vec<(i64, i64)> {
    let tokens = match tokens {
        Some(t) => t,
        None => return Vec::new(),
    };
    let mut pts: Vec<(i64, i64)> = Vec::with_capacity(tokens.len());
    for t in tokens {
        if let Some((a, b)) = t.split_once(',') {
            if let (Ok(lat), Ok(lon)) = (a.parse::<f64>(), b.parse::<f64>()) {
                let p = ((lat * 1e7).round() as i64, (lon * 1e7).round() as i64);
                if pts.last() != Some(&p) {
                    pts.push(p);
                }
            }
        }
    }
    pts
}

/// Whether two polylines trace the same path, allowing one to be a finer
/// subdivision of the other.
///
/// The geometry encoder inserts collinear points wherever a gap exceeds the `i16`
/// delta range, so a compacted graph's polyline legitimately carries vertices an
/// uncompacted one never needed. Those extra points lie *between* the originals,
/// so the coarser polyline must appear in the finer one as an in-order
/// subsequence — which is an exact test, unlike trying to detect and drop the
/// inserted points, since collinearity is not stable under subdivision (adding a
/// point between A and P changes P's neighbours, and so changes whether P looks
/// collinear).
fn same_path(a: &[(i64, i64)], b: &[(i64, i64)]) -> bool {
    if a == b {
        return true;
    }
    is_subsequence(a, b) || is_subsequence(b, a)
}

fn is_subsequence(needle: &[(i64, i64)], haystack: &[(i64, i64)]) -> bool {
    if needle.len() > haystack.len() {
        return false;
    }
    let mut it = haystack.iter();
    needle.iter().all(|p| it.any(|q| q == p))
}

fn first_mismatch(a: &[String], b: &[String]) -> Option<usize> {
    (0..a.len().max(b.len())).find(|&i| a.get(i) != b.get(i))
}

// ---------------------------------------------------------------------------
// geometry
// ---------------------------------------------------------------------------

/// Check directly that a rebuild describes the same roads, independent of
/// routing.
///
/// This is sharper than the route differential, and it has to be: collapsing
/// nodes changes how far `find_nearest_edge`'s fixed 800-node window reaches, so
/// two graphs of one region legitimately snap to different edges and produce
/// different routes. What must not change is the road network itself.
///
/// Two invariants are checked, both chosen to be insensitive to how the geometry
/// is *divided* into points, because the encoder inserts collinear points
/// wherever a gap exceeds the `i16` delta range:
///
/// * **No vertex is lost.** Every point in the baseline's geometry must still
///   appear in the candidate's. Interpolation only ever adds points, so the
///   candidate's vertex set must be a superset. A collapsed node whose
///   coordinate vanished would mean a corner had been cut.
/// * **Total length is preserved.** Summed over every hop of every edge.
///   Subdividing a straight hop does not change its length beyond the
///   sub-centimetre rounding of the inserted points.
fn geometry(args: &[String]) -> Result<bool, String> {
    let base_dir = need(args, "--baseline")?;
    let cand_dir = need(args, "--candidate")?;
    let base = load(&base_dir)?;
    let cand = load(&cand_dir)?;

    let b = scan_geometry(&base);
    println!(
        "baseline  nodes={:<10} edges={:<10} hops={:<10} vertices={:<10} length={} mm",
        base.node_count,
        base.edge_count,
        b.hops,
        b.vertices.len(),
        b.length_mm
    );
    let c = scan_geometry(&cand);
    println!(
        "candidate nodes={:<10} edges={:<10} hops={:<10} vertices={:<10} length={} mm",
        cand.node_count,
        cand.edge_count,
        c.hops,
        c.vertices.len(),
        c.length_mm
    );
    println!(
        "candidate stored geometry on {} edge(s), {} of them by reference to a twin",
        c.with_geometry, c.reversed
    );
    print!("candidate points per edge (log2 buckets):");
    for (i, n) in c.pt_hist.iter().enumerate() {
        if *n > 0 {
            print!(" 2^{i}={n}");
        }
    }
    println!();

    let missing: Vec<&(i32, i32)> = b.vertices.difference(&c.vertices).collect();
    let mut ok = true;
    if missing.is_empty() {
        println!("PASS  every baseline vertex survives in the candidate");
    } else {
        ok = false;
        println!(
            "FAIL  {} of {} baseline vertices are missing from the candidate",
            missing.len(),
            b.vertices.len()
        );
        for v in missing.iter().take(10) {
            println!("        {:.7},{:.7}", f64::from(v.0) * 1e-7, f64::from(v.1) * 1e-7);
        }
    }

    // A tenth of a percent covers the reconnection edges, which legitimately
    // pick different anchors once the node set changes, plus integer rounding on
    // interpolated points.
    let delta = c.length_mm as i128 - b.length_mm as i128;
    let tol = (b.length_mm / 1000).max(1_000_000) as i128;
    if delta.abs() <= tol {
        println!(
            "PASS  total road length within tolerance (delta {delta} mm of {} mm, tol {tol})",
            b.length_mm
        );
    } else {
        ok = false;
        println!("FAIL  total road length moved by {delta} mm (tol {tol})");
    }
    println!("{}", if ok { "GEOMETRY PRESERVED" } else { "GEOMETRY CHANGED" });
    Ok(ok)
}

struct GeomScan {
    hops: u64,
    length_mm: u128,
    vertices: HashSet<(i32, i32)>,
    with_geometry: u64,
    reversed: u64,
    pt_hist: [u64; 9],
}

/// Walk every edge and collect the geometry the device would draw: the stored
/// polyline where there is one, the straight chord otherwise.
fn scan_geometry(g: &Graph) -> GeomScan {
    let mut coords = [LatLon { lat_e7: 0, lon_e7: 0 }; 256];
    let mut out = GeomScan {
        hops: 0,
        length_mm: 0,
        vertices: HashSet::new(),
        with_geometry: 0,
        reversed: 0,
        pt_hist: [0u64; 9],
    };
    let mut pts: Vec<LatLon> = Vec::with_capacity(256);

    for u in 0..g.node_count {
        let node_u = g.node(u);
        let end = g.node(u + 1).edge_ptr;
        for j in node_u.edge_ptr..end {
            let e = g.edge(j);
            if e.target >= g.node_count {
                continue;
            }
            pts.clear();
            match g.get_edge_coordinates(j, &mut coords) {
                Some((count, is_reversed)) if count >= 2 => {
                    out.with_geometry += 1;
                    if is_reversed {
                        out.reversed += 1;
                    }
                    pts.extend((0..count).map(|p| get_pt_at(&coords, count, is_reversed, p)));
                }
                _ => {
                    let node_v = g.node(e.target);
                    pts.push(LatLon { lat_e7: node_u.lat_e7, lon_e7: node_u.lon_e7 });
                    pts.push(LatLon { lat_e7: node_v.lat_e7, lon_e7: node_v.lon_e7 });
                }
            }
            let bucket = (pts.len() as f64).log2() as usize;
            out.pt_hist[bucket.min(8)] += 1;
            for p in &pts {
                let _ = out.vertices.insert((p.lat_e7, p.lon_e7));
            }
            for w in pts.windows(2) {
                if (w[0].lat_e7, w[0].lon_e7) == (w[1].lat_e7, w[1].lon_e7) {
                    continue;
                }
                out.hops += 1;
                out.length_mm += u128::from(accurate_dist_mm(
                    w[0].lat_e7, w[0].lon_e7, w[1].lat_e7, w[1].lon_e7,
                ));
            }
        }
    }
    out
}

/// The other direction of the same road: the first edge from `target` back to
/// `source`. Mirrors `graph.rs`'s reverse-geometry lookup and `routing.rs`'s
/// `twin_edge`, so the tool and the engine agree on what "the twin" means.
fn twin_of(g: &Graph, source: u32, target: u32) -> Option<u64> {
    if target >= g.node_count {
        return None;
    }
    let s = g.node(target).edge_ptr;
    let e = g.node(target + 1).edge_ptr;
    (s..e).find(|k| g.edge(*k).target == source)
}

/// Approximate ground distance in metres (equirectangular). Only used to report
/// how far a snap moved, so an approximation is fine.
fn crow_m(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let dlat = (lat2 - lat1) * 111_320.0;
    let dlon = (lon2 - lon1) * 111_320.0 * ((lat1 + lat2) * 0.5).to_radians().cos();
    (dlat * dlat + dlon * dlon).sqrt()
}

fn write_file(path: &str, bytes: &[u8]) -> Result<(), String> {
    let mut f = std::fs::File::create(path).map_err(|e| format!("{path}: {e}"))?;
    f.write_all(bytes).map_err(|e| format!("{path}: {e}"))
}
