//! Which routes share a corridor, and which parallel lane each one takes through it.
//!
//! Where several services run over one physical track — six Muni Metro lines through the
//! Market Street subway, BART and Caltrain sharing an alignment — every route's polyline
//! lies on top of every other's and only one colour is visible. The standard transit-map
//! treatment is to fan them out into parallel coloured lines, and this decides which line
//! goes where.
//!
//! # Why here and not in the tiler or the renderer
//!
//! "Which routes share this corridor" is a global property of the feed set. The tiler sees
//! features one tile at a time and the renderer sees one tile at a time in arbitrary order,
//! so either would have to decide it per tile — which is exactly what puts a jog in every
//! route at every tile seam. It is decided once, here, and travels with the feature.
//!
//! # What comes out
//!
//! An ordered list of [`Span`]s per candidate, each a polyline and the inputs to a lane
//! choice. A route that shares nothing is one span: its own geometry, ordinal zero of one.
//! A route that runs through a corridor is cut at the corridor's ends, and the part inside
//! draws the **corridor's own reference polyline** rather than its own survey. That is what
//! makes the members of a corridor exactly parallel and evenly spaced: two agencies' surveys
//! of one track differ by a metre or two, which is a large fraction of the gap between lanes.
//!
//! The lane itself is *not* decided here. A span carries the colour's ordinal among the
//! corridor's distinct colours and how many of those there are, and the renderer turns that
//! into an offset — because how many lanes a corridor draws depends on the camera zoom, which
//! nothing here has. A taper travels alongside as a 0–255 fraction of whatever that offset
//! turns out to be, so a route eases into its lane instead of stepping sideways onto it. The
//! ease is drawn on the route's *own* survey, and only where the route arrives from its own
//! alignment: between two corridors it is in a lane on both sides and steps from one to the
//! other.
//!
//! # How
//!
//! Every candidate is resampled at [`SAMPLE_M`] and its samples are dropped into a spatial
//! grid — the sorted flat `Vec<(cell, id)>` [`crate::index`] uses for footpath transfers,
//! not a `HashMap` of buckets. For each sample, the routes with a sample within
//! [`CORRIDOR_M`] *running parallel to it* are that sample's local membership.
//!
//! A candidate's samples are then cut into maximal **runs** of equal membership. Runs, not
//! one membership per route: a route takes its lane from the corridor it is in *here*, so a
//! service that shares Market Street with nine others and the Bay Bridge with four takes a
//! lane in each. Picking one membership for a whole route was the previous rule, and it is
//! what left BART's five services on `-5, -3, +3, +7, +9` across the bay — slots assigned
//! for Market Street and carried where they meant nothing.
//!
//! Membership flickers sample to sample wherever a route drifts in and out of another's
//! tolerance, so runs shorter than [`MIN_SHARED_M`] are merged into their neighbour until
//! none are left. That threshold already existed for exactly this judgement: two alignments
//! that touch for fifty metres leaving a station are not a corridor.
//!
//! Parallelism is what keeps two tracks crossing at a junction from reading as a corridor:
//! bearings are compared folded to `[0, 180)`, so opposite directions over one track count
//! as parallel and a crossing does not.
//!
//! # Which lane is which
//!
//! The order across a corridor is the order its members arrive in, so a line never crosses
//! its neighbours where corridor membership changes. Each member's side is read off the
//! stretch of its own untouched survey immediately outside the corridor mouth — its
//! **approach** — where two routes that then run over one identical alignment are still tens
//! of metres apart and the sign is unambiguous. Inside the corridor they are within a metre
//! or two of the reference and the sign is survey noise.
//!
//! Nothing propagates an order between corridors. It is read off the geometry at each one
//! independently, and abutting corridors agree because the ground does.
//!
//! Nothing here may depend on `HashMap` iteration order — the exporter's output has to be
//! byte-identical between runs — so every collection that feeds a decision is a sorted
//! vector or a `BTreeMap`.

use crate::shapes::{distance_m, project, resample};
use std::cmp::Ordering;
use std::collections::BTreeMap;
use std::ops::Range;
use std::sync::atomic::{AtomicUsize, Ordering as AtomicOrdering};
use std::sync::Mutex;

/// Two polylines closer than this everywhere are the same line, and two tracks closer than
/// this are one corridor.
///
/// Inside the band `shapes.rs` already establishes: well above `SIMPLIFY_TOLERANCE_M`
/// (2.5 m), which is below the disagreement between two agencies' surveys of one track, and
/// well below `MAX_STOP_OFFSET_M` (150 m), which is a shape belonging to another pattern.
pub const CORRIDOR_M: f64 = 30.0;

/// How close a member's own survey has to come to the reference before it stops drawing its
/// own track and hands over to the corridor's.
///
/// The other half of the judgement [`CORRIDOR_M`] makes, which the two shared until now: 30 m
/// is "these two tracks are one corridor", and this is "they are close enough that drawing
/// one on the other's geometry cannot be seen". Two lines closing at a shallow angle become
/// one corridor while still 30 m apart, and handing over there puts the whole 30 m into a
/// single sideways step at the mouth. Above the metre or two two agencies' surveys of one
/// track disagree by, and well below [`CORRIDOR_M`].
const SNAP_M: f64 = 8.0;

/// Spacing of the probe samples along a polyline.
///
/// Small enough that two parallel tracks are compared point-to-point rather than
/// point-to-segment without the along-track offset mattering: half a step is 5 m against a
/// 30 m radius.
const SAMPLE_M: f64 = 10.0;

/// Grid cell in metres, just above the search radius so a probe never has to scan more than
/// the nine cells around it. The sizing rule [`crate::index`]'s transfer grid uses.
const CELL_M: f64 = 40.0;

/// How much track two routes have to share before it counts as a corridor.
///
/// Also the smoothing window on the runs. Membership flips sample to sample wherever a
/// route grazes another's tolerance, and cutting on every flip would shatter a route into
/// dozens of features; a run shorter than this is absorbed into its neighbour instead.
/// Three hundred metres is well above a station throat and well below any shared trunk
/// worth drawing.
const MIN_SHARED_M: f64 = 300.0;

/// Cosine of the angle within which two bearings count as parallel (25°).
///
/// Compared on `|u · v|`, so the fold to `[0, 180)` is free: two routes over one track
/// stored in opposite directions are parallel, two tracks crossing at a junction are not.
const COS_FOLD: f64 = 0.906_307_787;

/// The shortest ease a member spends coming into its lane, and the whole of it where the
/// member is already on the reference when its run begins.
///
/// Without it a route steps sideways by up to nine Dp at the corridor mouth, which reads as
/// a break in the line rather than as a fan opening. Where the member converges slowly the
/// ease is longer than this: it runs from the mouth to wherever the survey first comes within
/// [`SNAP_M`].
const TAPER_M: f64 = 100.0;

/// Features in one taper. The taper travels as a 0–255 fraction of the full lane offset, so
/// the step count is only how many pieces the ease is cut into — and, because the fraction is
/// an equal-step ramp, how big the jump between two of them is. Eight puts every jump at
/// 255/9, about 1 Dp at a 9 Dp offset.
const TAPER_STEPS: usize = 8;

/// How much of a member's own survey, immediately outside the corridor mouth, decides which
/// side of the corridor it takes.
///
/// Across the shared stretch two routes on one track sit within a couple of metres of the
/// reference and the sign of that is survey noise. On the approach they are still diverging,
/// and the sign is what separates "came from the north" from "came from the south". Above
/// [`TAPER_M`] so it clears the ease-in, and below [`MIN_SHARED_M`] so it cannot reach back
/// into the body of the corridor before.
const APPROACH_M: f64 = 150.0;

/// Points sampled along the approach. The mean of a handful, because one point lands wherever
/// the survey's own vertices happen to fall.
const APPROACH_SAMPLES: usize = 8;

/// One route's polyline, offered for slotting.
pub struct Candidate<'a> {
    pub points: &'a [(i32, i32)],
    /// Which route this polyline belongs to. Two polylines of one route — a branch and its
    /// trunk — count as one member of a corridor, because the lane is per route and not per
    /// line.
    pub route: u32,
    /// `0xRRGGBB`. The lane is per colour, and the colour is the tie-break on the order
    /// within a corridor when two surveys genuinely coincide.
    pub color: u32,
    /// The route's display name. The second half of that tie-break, so a rebuild puts the
    /// same route in the same lane.
    pub name: &'a str,
}

/// One stretch of a candidate as it should be drawn.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Span {
    /// The polyline to emit. The candidate's own geometry outside a corridor and across the
    /// ease into one, and the corridor's reference geometry over the body.
    pub points: Vec<(i32, i32)>,
    /// This colour's index among the corridor's distinct colours, from zero. Zero outside a
    /// corridor.
    ///
    /// The order is **positional**: the colours are laid out across the corridor in the order
    /// their routes arrive at it, so two groups that merge do not interleave and nothing
    /// crosses. Zero is the left-hand side of the reference's direction of travel.
    ///
    /// The index is over the distinct **colours** of the corridor, not its routes. Two routes
    /// of one colour are indistinguishable once drawn, so giving them a lane each spends width
    /// on a difference nobody can see — and it is what doubles a network whose feed publishes
    /// each direction as its own route (BART's `Yellow-N` and `Yellow-S`) or splits one
    /// service into branch variants. Sharing a lane makes them coincide exactly, because they
    /// are also drawing the same reference geometry.
    pub ordinal: u8,
    /// How many distinct colours the corridor carries. One outside a corridor.
    ///
    /// Unclamped: how many lanes to actually draw is the renderer's decision, because it
    /// depends on the camera zoom and the exporter has no zoom.
    pub lanes: u8,
    /// How far into its lane this piece sits, over 255. 255 outside a corridor and on a
    /// corridor's body; less only across a taper at its mouth.
    pub taper: u8,
}

/// Cut every candidate into spans and give each one its lane. One list out per candidate in,
/// in the same order, never empty.
pub fn assign(candidates: &[Candidate]) -> Vec<Vec<Span>> {
    if candidates.is_empty() {
        return Vec::new();
    }
    let route_count = candidates.iter().map(|c| c.route as usize + 1).max().unwrap_or(0);
    let (samples, blocks) = sample_all(candidates);
    let grid = Grid::build(&samples);

    // Local membership per sample, interned: distinct sets are few even on a world feed set,
    // and holding one `Vec<u32>` per sample is what would not fit.
    let mut sets: Vec<Vec<u32>> = Vec::new();
    let mut set_ids: BTreeMap<Vec<u32>, u32> = BTreeMap::new();
    let mut per_sample: Vec<u32> = Vec::with_capacity(samples.len());

    // The probe is the whole cost of a large run — one per 10 m of every line, 287 million of
    // them on a world feed set — and it ran on one core for the better part of an hour.
    //
    // It splits in two. Finding which routes are near a sample is a pure read of the grid, so
    // that part goes wide: the samples are cut into one contiguous range per core and each
    // thread writes its answers into its own flat buffer. Interning those answers into set ids
    // cannot go wide, because an id is assigned on first sight and the ids have to come out in
    // sample order or every downstream lane moves. So the threads hand back their buffers in
    // range order and the interning walks them sequentially — cheap, since it is `BTreeMap`
    // lookups over an answer already computed.
    //
    // Flat `(values, ends)` buffers rather than `Vec<Vec<u32>>`: 287 million heap allocations
    // is its own kind of slow, and the whole point of interning is not to hold that many
    // vectors at once.
    let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1);
    let span = samples.len().div_ceil(threads.max(1)).max(1);
    let probed = AtomicUsize::new(0);
    let parts: Mutex<Vec<(usize, Vec<u32>, Vec<u32>)>> = Mutex::new(Vec::new());
    {
        let grid = &grid;
        let samples = &samples;
        let parts = &parts;
        let probed = &probed;
        let total = samples.len();
        std::thread::scope(|scope| {
            for t in 0..threads {
                let lo = t * span;
                if lo >= total {
                    break;
                }
                let hi = ((t + 1) * span).min(total);
                scope.spawn(move || {
                    // Per thread, so the stamp trick in `routes_near` needs no sharing: a
                    // stamp only has to be unique against this thread's own array.
                    let mut seen: Vec<u32> = vec![0; route_count];
                    let mut stamp: u32 = 0;
                    let mut near: Vec<u32> = Vec::new();
                    let mut values: Vec<u32> = Vec::new();
                    let mut ends: Vec<u32> = Vec::with_capacity(hi - lo);
                    for (i, sample) in samples[lo..hi].iter().enumerate() {
                        stamp += 1;
                        grid.routes_near(samples, sample, &mut near, &mut seen, stamp);
                        values.extend_from_slice(&near);
                        ends.push(values.len() as u32);
                        // Batched, because an atomic per probe would cost more than the probe.
                        if i % 65_536 == 0 {
                            let n = probed.fetch_add(65_536, AtomicOrdering::Relaxed) + 65_536;
                            eprint!(
                                "\r{:<28} [{:>3}%] {total} sample(s)",
                                "Corridor probe",
                                (n * 100 / total.max(1)).min(100)
                            );
                        }
                    }
                    parts.lock().expect("probe pool").push((t, values, ends));
                });
            }
        });
    }
    eprintln!("\r{:<28} [100%] {} sample(s)", "Corridor probe", samples.len());

    // Back in sample order, then interned sequentially: first-seen order is what fixes the ids.
    let mut parts = parts.into_inner().expect("probe pool");
    parts.sort_unstable_by_key(|(t, _, _)| *t);
    for (_, values, ends) in &parts {
        let mut start = 0usize;
        for &end in ends {
            let near = &values[start..end as usize];
            start = end as usize;
            let id = match set_ids.get(near) {
                Some(id) => *id,
                None => {
                    let id = sets.len() as u32;
                    sets.push(near.to_vec());
                    set_ids.insert(near.to_vec(), id);
                    id
                }
            };
            per_sample.push(id);
        }
    }
    drop(parts);

    // Distance along each candidate, for its own geometry and for its probes. `resample`
    // walks *along* the polyline, so probe `k` is exactly `k * SAMPLE_M` along it and the
    // last one is its end. Summing the chords between probes instead would fall short of
    // that wherever the alignment curves, and the drift accumulates over a route's length
    // until the last run is cut short of the line's own end.
    let own_cum: Vec<Vec<f64>> = candidates.iter().map(|c| cumulative(c.points)).collect();
    let probe_cum: Vec<Vec<f64>> = blocks
        .iter()
        .zip(&own_cum)
        .map(|(block, own)| {
            let total = own.last().copied().unwrap_or(0.0);
            let count = block.len();
            (0..count)
                .map(|k| if k + 1 == count { total } else { (k as f64 * SAMPLE_M).min(total) })
                .collect()
        })
        .collect();

    let runs: Vec<Vec<Run>> = blocks
        .iter()
        .zip(&probe_cum)
        .map(|(block, cum)| cut_runs(&per_sample[block.clone()], cum))
        .collect();

    let mut named: Vec<Option<(u32, &str)>> = vec![None; route_count];
    for candidate in candidates {
        named[candidate.route as usize].get_or_insert((candidate.color, candidate.name));
    }

    // Named too: on a large set the work after the probe loop is still minutes, and without a
    // line here the run goes silent again the moment the bar reaches 100%.
    eprintln!("{:<28} {} candidate(s)", "Corridor spans", candidates.len());
    let corridors = corridors_of(candidates, &sets, &runs, &named, &own_cum, &probe_cum);

    if std::env::var_os("TRANSIT_BUNDLE_DEBUG").is_some() {
        for (set, corridor) in &corridors {
            let members = &sets[*set as usize];
            eprintln!(
                "corridor of {} over {} colour(s): {}",
                members.len(),
                corridor.colours.len(),
                members
                    .iter()
                    .map(|&m| {
                        let (colour, name) = named[m as usize].unwrap_or((0, ""));
                        format!("{name}/{colour:06X}")
                    })
                    .collect::<Vec<String>>()
                    .join(" "),
            );
        }
    }

    // The last phase, and per candidate independent: each one cuts its own runs against
    // corridors it only reads. Left sequential it was the tail that made a world set look
    // hung again after the corridor bar hit 100%.
    //
    // Handed out one candidate at a time rather than in equal contiguous ranges, because the
    // cost per candidate is wildly uneven — a transcontinental line carries orders of magnitude
    // more runs than a tram loop. Splitting the range evenly gave whichever thread drew the
    // long-distance rail all the work and left the rest idle: a world set sat at 98% on one
    // core for ten minutes with sixty-three threads finished. Sample probes are uniform enough
    // for a range split; candidates are not.
    let total = candidates.len();
    let cut_next = AtomicUsize::new(0);
    let cut_done = AtomicUsize::new(0);
    let cut_parts: Mutex<Vec<(usize, Vec<Span>)>> = Mutex::new(Vec::with_capacity(total));
    {
        let corridors = &corridors;
        let cut_parts = &cut_parts;
        let cut_done = &cut_done;
        let cut_next = &cut_next;
        let samples = &samples;
        let runs = &runs;
        let blocks = &blocks;
        let probe_cum = &probe_cum;
        let own_cum = &own_cum;
        std::thread::scope(|scope| {
            for _ in 0..threads.min(total.max(1)) {
                scope.spawn(move || {
                    let mut local: Vec<(usize, Vec<Span>)> = Vec::new();
                    loop {
                        let at = cut_next.fetch_add(1, AtomicOrdering::Relaxed);
                        if at >= total {
                            break;
                        }
                        local.push((
                            at,
                            spans_of(
                                &candidates[at],
                                &runs[at],
                                &samples[blocks[at].clone()],
                                &probe_cum[at],
                                &own_cum[at],
                                corridors,
                            ),
                        ));
                        let n = cut_done.fetch_add(1, AtomicOrdering::Relaxed) + 1;
                        if n % 64 == 0 {
                            eprint!(
                                "\r{:<28} [{:>3}%] {total} line(s)",
                                "Corridor cut",
                                n * 100 / total.max(1)
                            );
                        }
                    }
                    cut_parts.lock().expect("cut pool").extend(local);
                });
            }
        });
    }
    eprintln!("\r{:<28} [100%] {total} line(s)", "Corridor cut");
    let mut cut_parts = cut_parts.into_inner().expect("cut pool");
    cut_parts.sort_unstable_by_key(|(at, _)| *at);
    cut_parts.into_iter().map(|(_, spans)| spans).collect()
}

/// A maximal stretch of one candidate's samples holding the same membership, as inclusive
/// indices into that candidate's own block of samples.
struct Run {
    from: usize,
    to: usize,
    set: u32,
}

/// A shared corridor: the geometry every member of it draws, and the lane order.
struct Corridor {
    /// One member's polyline, folded into the canonical direction, which the whole corridor
    /// draws slices of. Owned rather than borrowed because the fold may reverse it.
    points: Vec<(i32, i32)>,
    cum: Vec<f64>,
    /// The corridor's distinct colours, ordered across it: the first is the left-hand side of
    /// the reference's direction of travel, which is the side [`Span::ordinal`] zero draws on.
    colours: Vec<u32>,
}

/// Cut a candidate's samples into runs of constant membership, then smooth away the ones too
/// short to be a corridor.
fn cut_runs(per_sample: &[u32], cum: &[f64]) -> Vec<Run> {
    let Some(&first) = per_sample.first() else { return Vec::new() };
    let mut runs = vec![Run { from: 0, to: 0, set: first }];
    for (at, &set) in per_sample.iter().enumerate().skip(1) {
        let last = runs.last_mut().expect("seeded above");
        if last.set == set {
            last.to = at;
        } else {
            runs.push(Run { from: at, to: at, set });
        }
    }
    smooth(&mut runs, cum);
    runs
}

/// Absorb every run shorter than [`MIN_SHARED_M`] into a neighbour, longest neighbour first,
/// and coalesce the neighbours that end up equal.
///
/// Without this a route drifting in and out of another's tolerance flips membership sample
/// to sample and shatters into dozens of features, most of them a few metres long.
fn smooth(runs: &mut Vec<Run>, cum: &[f64]) {
    let length = |run: &Run| cum[run.to] - cum[run.from];
    while runs.len() > 1 {
        let Some(at) = (0..runs.len())
            .filter(|&i| length(&runs[i]) < MIN_SHARED_M)
            .min_by(|&a, &b| length(&runs[a]).total_cmp(&length(&runs[b])).then(a.cmp(&b)))
        else {
            break;
        };
        let into = match (at.checked_sub(1), runs.get(at + 1)) {
            (None, _) => at + 1,
            (Some(before), None) => before,
            (Some(before), Some(after)) => {
                if length(after) > length(&runs[before]) {
                    at + 1
                } else {
                    before
                }
            }
        };
        if into < at {
            runs[into].to = runs[at].to;
        } else {
            runs[into].from = runs[at].from;
        }
        runs.remove(at);
        let mut i = 1;
        while i < runs.len() {
            if runs[i].set == runs[i - 1].set {
                runs[i - 1].to = runs[i].to;
                runs.remove(i);
            } else {
                i += 1;
            }
        }
    }
}

/// Every membership that survived as a run of more than one route, with the geometry and the
/// lane order its members share.
///
/// No transitive closure. Unioning the members of neighbouring corridors was tried and is
/// badly wrong: BART shares Market Street with Muni Metro, Millbrae with Caltrain, Caltrain
/// shares San Jose with Capitol Corridor and ACE, and following that chain collapsed the
/// whole west-coast rail network into one corridor of twenty-three. A corridor is a local
/// thing, and two routes that share one see the same local membership, so they agree on the
/// reference and on the lane order without anything having to reconcile them.
///
/// Nor is there a corridor adjacency graph to propagate an order along. The order is read off
/// the geometry at each corridor independently, from the members' own untouched surveys, and
/// two abutting corridors agree because the ground does — the routes really are arranged that
/// way at the seam.
fn corridors_of(
    candidates: &[Candidate],
    sets: &[Vec<u32>],
    runs: &[Vec<Run>],
    named: &[Option<(u32, &str)>],
    own_cum: &[Vec<f64>],
    probe_cum: &[Vec<f64>],
) -> BTreeMap<u32, Corridor> {
    // Per corridor, each member run as the candidate it belongs to and where the run begins
    // and ends along that candidate's own survey — which is what the approach is measured
    // just outside of.
    let mut members: BTreeMap<u32, Vec<(usize, f64, f64)>> = BTreeMap::new();
    for (at, candidate_runs) in runs.iter().enumerate() {
        for run in candidate_runs {
            if sets[run.set as usize].len() > 1 {
                members
                    .entry(run.set)
                    .or_default()
                    .push((at, probe_cum[at][run.from], probe_cum[at][run.to]));
            }
        }
    }
    let length = |at: usize| own_cum[at].last().copied().unwrap_or(0.0);
    // Every corridor projects every member onto the reference geometry, so the work is members
    // times reference length per corridor, and on a world set that ran for the better part of
    // an hour on one core.
    //
    // Each corridor is an independent pure function of read-only shared state — it reads
    // `candidates`, `sets`, `named` and `own_cum` and writes only its own entry — so the loop
    // spreads across the machine with no coordination beyond handing out indices. Results go
    // into a `BTreeMap` keyed by set id, which is ordered by key rather than by insertion, so
    // the output is byte-identical however the threads interleave.
    let entries: Vec<(u32, Vec<(usize, f64, f64)>)> = members.into_iter().collect();
    let total = entries.len();
    let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1);
    let next = AtomicUsize::new(0);
    let finished = AtomicUsize::new(0);
    let collected: Mutex<Vec<(u32, Corridor)>> = Mutex::new(Vec::with_capacity(total));
    std::thread::scope(|scope| {
        for _ in 0..threads.min(total.max(1)) {
            scope.spawn(|| {
                // Accumulated per thread and merged once at the end: locking per corridor
                // would serialise the very loop this is spreading out.
                let mut local: Vec<(u32, Corridor)> = Vec::new();
                loop {
                    let i = next.fetch_add(1, AtomicOrdering::Relaxed);
                    if i >= total {
                        break;
                    }
                    let (set, in_corridor) = &entries[i];
                    local.push((
                        *set,
                        one_corridor(*set, in_corridor, candidates, sets, named, own_cum, &length),
                    ));
                    // Redrawn on a count, not a percentage: the threads finish out of order, so
                    // "the whole number changed" is not a thing any one of them can see.
                    let n = finished.fetch_add(1, AtomicOrdering::Relaxed) + 1;
                    if n % 128 == 0 {
                        eprint!(
                            "\r{:<28} [{:>3}%] {total} corridor(s)",
                            "Corridor order",
                            n * 100 / total.max(1)
                        );
                    }
                }
                collected.lock().expect("corridor pool").extend(local);
            });
        }
    });
    eprintln!("\r{:<28} [100%] {total} corridor(s)", "Corridor order");
    collected.into_inner().expect("corridor pool").into_iter().collect()
}

/// One corridor's reference geometry and the order its colours sit in across it.
///
/// Split out of [`corridors_of`] so the loop there can run on every core: this reads only
/// shared immutable state and returns an owned value, which is what makes that safe.
#[allow(clippy::too_many_arguments)]
fn one_corridor(
    set: u32,
    in_corridor: &[(usize, f64, f64)],
    candidates: &[Candidate],
    sets: &[Vec<u32>],
    named: &[Option<(u32, &str)>],
    own_cum: &[Vec<f64>],
    length: &dyn Fn(usize) -> f64,
) -> Corridor {
    {
        {
            // Whose geometry the corridor draws — and only that. It no longer decides the
            // direction, which is canonical, nor the order, which is the approaches. The
            // first member by colour and then name, so it does not move when a feed reorders
            // its routes. Between two polylines of that one member — a trunk and the
            // short-turn that runs half of it — the longer, because a shorter reference clips
            // every other member to itself.
            let pick = in_corridor
                .iter()
                .map(|&(at, _, _)| at)
                .reduce(|a, b| {
                    let better = match (candidates[a].color, candidates[a].name)
                        .cmp(&(candidates[b].color, candidates[b].name))
                    {
                        Ordering::Less => true,
                        Ordering::Greater => false,
                        Ordering::Equal => match length(b).total_cmp(&length(a)) {
                            Ordering::Less => true,
                            Ordering::Greater => false,
                            Ordering::Equal => candidates[a].points <= candidates[b].points,
                        },
                    };
                    if better {
                        a
                    } else {
                        b
                    }
                })
                .expect("a corridor has at least one member");
            let points = canonical(candidates[pick].points);
            let cum = cumulative(&points);
            let reference = Corridor { points, cum, colours: Vec::new() };

            // Where each member sits across the corridor, averaged over the colour: two
            // routes of one colour share a lane, so they share one place in the order.
            // Measured against the corridor's own stretch of the reference, which is every
            // member's run projected onto it.
            let projected = |at: usize, along: f64| {
                distance_along(&reference, point_at(candidates[at].points, &own_cum[at], along))
            };
            let extent = in_corridor.iter().fold(
                (f64::INFINITY, f64::NEG_INFINITY),
                |(lo, hi), &(at, from, to)| {
                    let (a, b) = (projected(at, from), projected(at, to));
                    (lo.min(a).min(b), hi.max(a).max(b))
                },
            );
            // Less a taper at each end. A run boundary is only located to a sample step, so
            // the extent bleeds a little past the mouth — and one segment past the mouth the
            // reference has left the corridor too and is heading wherever it goes next, which
            // is not an axis to measure anyone's side against.
            let margin = TAPER_M.min((extent.1 - extent.0) / 4.0);
            let extent = (extent.0 + margin, extent.1 - margin);
            let mut sides: BTreeMap<u32, (f64, u32)> = BTreeMap::new();
            for &(at, from, to) in in_corridor {
                let side = approach_offset(
                    &reference,
                    extent,
                    candidates[at].points,
                    &own_cum[at],
                    from,
                    to,
                );
                let seen = sides.entry(candidates[at].color).or_insert((0.0, 0));
                seen.0 += side;
                seen.1 += 1;
            }
            // A route can be in the membership without having a run of its own here, in which
            // case it draws nothing in this corridor and there is no approach to measure. It
            // still occupies a colour, so it still takes a place: the reference's own, zero.
            let mut ordered: Vec<(f64, u32, &str)> = sets[set as usize]
                .iter()
                .map(|&member| {
                    let (colour, name) = named[member as usize].unwrap_or((0, ""));
                    let side = sides
                        .get(&colour)
                        .map(|&(sum, n)| sum / f64::from(n))
                        .unwrap_or(0.0);
                    (side, colour, name)
                })
                .collect();
            // Ascending offset is ascending ordinal, because `lane_offset_px` gives ordinal
            // zero the most negative offset and a negative offset is the left of the
            // reference's travel. The `(colour, name)` tail is the tie-break for two surveys
            // that genuinely coincide, and is what keeps a rebuild byte-identical.
            ordered.sort_by(|a, b| a.0.total_cmp(&b.0).then(a.1.cmp(&b.1)).then(a.2.cmp(b.2)));
            let mut colours: Vec<u32> = ordered.into_iter().map(|(_, c, _)| c).collect();
            colours.dedup();
            Corridor { colours, ..reference }
        }
    }
}

/// Fold a polyline into a fixed half-plane: northward, or exactly east-west and eastward.
///
/// The lane side is measured along the reference, so the direction the reference is stored in
/// mirrors the whole fan. Inheriting it from whichever member won the name tie-break means a
/// lower-coloured route joining at a junction can flip the arrangement of everyone else.
/// Abutting corridors run roughly parallel where they meet, so folding both by their own
/// first-to-last chord makes them fold the same way and the fan does not mirror across the
/// seam. The same idea as [`COS_FOLD`]'s fold to `[0, 180)`, applied to a whole polyline.
fn canonical(points: &[(i32, i32)]) -> Vec<(i32, i32)> {
    let (Some(first), Some(last)) = (points.first(), points.last()) else {
        return points.to_vec();
    };
    // Signs only, so the longitude scaling that would make these metres cannot change the
    // answer and is not worth doing.
    let (north, east) = (last.0 - first.0, last.1 - first.1);
    if north < 0 || (north == 0 && east < 0) {
        points.iter().rev().copied().collect()
    } else {
        points.to_vec()
    }
}

/// Which side of the corridor one member arrives on, in metres, positive to the right of the
/// reference's direction of travel.
///
/// The **approach** — the stretch of the member's own untouched survey immediately before it
/// enters the corridor — rather than the shared stretch, where every member is within a
/// couple of metres of the reference and the sign is noise. A run that starts at the line's
/// own start has no approach and uses the departure instead; one that is the whole line has
/// neither, and falls back to the mean across the run, which is weaker but still correct.
fn approach_offset(
    corridor: &Corridor,
    extent: (f64, f64),
    points: &[(i32, i32)],
    own_cum: &[f64],
    from: f64,
    to: f64,
) -> f64 {
    let total = own_cum.last().copied().unwrap_or(0.0);
    let (lo, hi) = if from <= to { (from, to) } else { (to, from) };
    let (a, b) = if lo > 0.0 {
        ((lo - APPROACH_M).max(0.0), lo)
    } else if hi < total {
        (hi, (hi + APPROACH_M).min(total))
    } else {
        (lo, hi)
    };
    let mut sum = 0.0;
    for k in 0..APPROACH_SAMPLES {
        let t = k as f64 / (APPROACH_SAMPLES - 1) as f64;
        sum += signed_offset(corridor, extent, point_at(points, own_cum, a + (b - a) * t));
    }
    sum / APPROACH_SAMPLES as f64
}

/// The taper fraction of step `step` of [`TAPER_STEPS`], climbing toward but never reaching
/// 255 — the full lane belongs to the corridor span the taper leads into.
fn taper_fraction(step: usize) -> u8 {
    (255.0 * step as f64 / (TAPER_STEPS + 1) as f64).round() as u8
}

/// Where a run begins and ends on the ground, as `(entry, exit)` in the candidate's own
/// order of travel.
type Bounds = ((i32, i32), (i32, i32));

/// Where one run of a candidate is drawn, decided for every run before any of it is emitted.
///
/// A run has to know whether its *neighbours* are on a corridor to know whether to ease into
/// its lane, so the placement is a pass of its own. Testing `corridors.contains_key` at the
/// neighbour is not enough: a run whose two ends project to one place on the reference draws
/// its own geometry despite being a corridor set, and easing against that would leave a step.
struct Placement<'a> {
    corridor: &'a Corridor,
    /// Whether the candidate travels in the reference's direction over this run.
    forward: bool,
    /// The run's extent along the reference.
    lo: f64,
    hi: f64,
    /// The first and last of the run's samples within [`SNAP_M`] of the reference, as indices
    /// into the candidate's own block of samples. `None` where it never comes that close, in
    /// which case it keeps its own survey across the whole run — fanned, but not exactly
    /// parallel, which it was never going to be honestly at that separation.
    snap: Option<(usize, usize)>,
}

/// The points a run begins and ends at, which is what the runs either side have to reach.
fn bounds_of(pieces: &[Span]) -> Option<Bounds> {
    let entry = *pieces.first()?.points.first()?;
    let exit = *pieces.last()?.points.last()?;
    Some((entry, exit))
}

/// One candidate's spans, in its own order of travel.
fn spans_of(
    candidate: &Candidate,
    runs: &[Run],
    samples: &[Sample],
    probe_cum: &[f64],
    own_cum: &[f64],
    corridors: &BTreeMap<u32, Corridor>,
) -> Vec<Span> {
    let whole = || {
        vec![Span { points: candidate.points.to_vec(), ordinal: 0, lanes: 1, taper: 255 }]
    };
    if runs.is_empty() || (runs.len() == 1 && !corridors.contains_key(&runs[0].set)) {
        return whole();
    }

    let placements: Vec<Option<Placement>> = runs
        .iter()
        .map(|run| {
            let corridor = corridors.get(&run.set)?;
            let a = distance_along(corridor, samples[run.from].point);
            let b = distance_along(corridor, samples[run.to].point);
            let (forward, lo, hi) = if a <= b { (true, a, b) } else { (false, b, a) };
            // A run with no usable extent on the reference — the two ends project to one
            // place — has nothing to snap to, so the candidate keeps its own geometry there.
            if hi - lo < SAMPLE_M {
                return None;
            }
            let near = |at: usize| nearest(corridor, samples[at].point).0 <= SNAP_M;
            let snap = (run.from..=run.to).find(|&at| near(at)).map(|first| {
                (first, (first..=run.to).rev().find(|&at| near(at)).unwrap_or(first))
            });
            Some(Placement { corridor, forward, lo, hi, snap })
        })
        .collect();

    // Per run: its pieces and the points it begins and ends at, both in the candidate's order
    // of travel, and whether that order runs against the reference's.
    let mut pieces: Vec<Vec<Span>> = Vec::with_capacity(runs.len());
    let mut ends: Vec<Option<Bounds>> = Vec::with_capacity(runs.len());
    let mut against: Vec<bool> = Vec::with_capacity(runs.len());

    for (at, run) in runs.iter().enumerate() {
        let (own_from, own_to) = (probe_cum[run.from], probe_cum[run.to]);
        let own_piece = |from: f64, to: f64, ordinal: u8, lanes: u8, taper: u8| -> Option<Span> {
            let points = slice_between(candidate.points, own_cum, from, to);
            (points.len() >= 2).then_some(Span { points, ordinal, lanes, taper })
        };

        let Some(place) = &placements[at] else {
            let out: Vec<Span> = own_piece(own_from, own_to, 0, 1, 255).into_iter().collect();
            ends.push(bounds_of(&out));
            pieces.push(out);
            against.push(false);
            continue;
        };
        let corridor = place.corridor;
        let lanes = u8::try_from(corridor.colours.len()).unwrap_or(u8::MAX);
        let ordinal = corridor
            .colours
            .iter()
            .position(|c| *c == candidate.color)
            .and_then(|at| u8::try_from(at).ok())
            .unwrap_or(0);

        let Some((first, last)) = place.snap else {
            let out: Vec<Span> =
                own_piece(own_from, own_to, ordinal, lanes, 255).into_iter().collect();
            ends.push(bounds_of(&out));
            pieces.push(out);
            against.push(!place.forward);
            continue;
        };

        // A taper means one thing: opening the fan off the member's own alignment. A corridor
        // of one colour has no fan to open, and where the neighbouring run is on a corridor
        // too there is no own alignment to leave — the line is in a lane on both sides and
        // steps from one to the other, which is a fraction of the fan rather than the whole
        // of it dipping through zero.
        let fanned = lanes > 1;
        let eases = |other: Option<usize>| {
            fanned && matches!(other, Some(i) if placements[i].is_none())
        };
        let at_start = eases(at.checked_sub(1));
        let at_end = eases((at + 1 < runs.len()).then_some(at + 1));

        // The ease runs from the run's start to where the member's own survey first comes
        // within `SNAP_M`, which makes it adaptive: a shallow convergence gets a long ease
        // and a sharp junction a short one. At least `TAPER_M` so the offset has somewhere to
        // ramp when the member converges at once, and never more than a quarter of the run so
        // the body keeps a usable share of it.
        let extent = own_to - own_from;
        let ease = |to_snap: f64| to_snap.max(TAPER_M).min(extent / 4.0);
        let lead = if at_start { ease(probe_cum[first] - own_from) } else { 0.0 };
        let trail = if at_end { ease(own_to - probe_cum[last]) } else { 0.0 };

        // Where the body meets the reference: the handover point where there is an ease, and
        // the run boundary where there is not.
        let ref_at =
            |own: f64| distance_along(corridor, point_at(candidate.points, own_cum, own));
        let (from_end, to_end) =
            if place.forward { (place.lo, place.hi) } else { (place.hi, place.lo) };
        let body_from = if lead > 0.0 { ref_at(own_from + lead) } else { from_end };
        let body_to = if trail > 0.0 { ref_at(own_to - trail) } else { to_end };

        let mut out: Vec<Span> = Vec::new();
        if lead > 0.0 {
            let step = lead / TAPER_STEPS as f64;
            for k in 0..TAPER_STEPS {
                let base = own_from + k as f64 * step;
                out.extend(own_piece(base, base + step, ordinal, lanes, taper_fraction(k + 1)));
            }
        }
        let mut body = slice_between(&corridor.points, &corridor.cum, body_from, body_to);
        if !place.forward {
            body.reverse();
        }
        let mut body_exit = None;
        if body.len() >= 2 {
            let (head, tail) = (body[0], body[body.len() - 1]);
            // The ease is on the member's own survey and the body is on the reference, up to
            // `SNAP_M` apart. Carry one vertex across so the two meet, the same trick the
            // inter-run weld below uses. The ease pieces themselves are adjoining slices of
            // one polyline and already share their boundary vertex exactly.
            if let Some(lead_end) = out.last_mut() {
                if lead_end.points.last() != Some(&head) {
                    lead_end.points.push(head);
                }
            }
            out.push(Span { points: body, ordinal, lanes, taper: 255 });
            body_exit = Some(tail);
        }
        if trail > 0.0 {
            let step = trail / TAPER_STEPS as f64;
            let starts_at = out.len();
            for k in 0..TAPER_STEPS {
                let base = own_to - trail + k as f64 * step;
                out.extend(own_piece(
                    base,
                    base + step,
                    ordinal,
                    lanes,
                    taper_fraction(TAPER_STEPS - k),
                ));
            }
            if let (Some(tail), Some(trail_start)) = (body_exit, out.get_mut(starts_at)) {
                if trail_start.points.first() != Some(&tail) {
                    trail_start.points.insert(0, tail);
                }
            }
        }

        ends.push(bounds_of(&out));
        pieces.push(out);
        against.push(!place.forward);
    }

    // Two runs meet at a point each of them located only to a sample step, and on two
    // different polylines: a member's own survey and a reference, or two references where a
    // route steps straight from one corridor into the next. Carry one across to the other so
    // the pieces meet rather than leaving a gap at the seam — the corridor's own geometry
    // where there is one to preserve, and the run that follows otherwise.
    for at in 1..pieces.len() {
        let (Some(before), Some(after)) = (ends[at - 1], ends[at]) else { continue };
        if placements[at - 1].is_some() && placements[at].is_none() {
            let head = pieces[at].first_mut().expect("bounds imply a piece");
            if head.points.first() != Some(&before.1) {
                head.points.insert(0, before.1);
            }
        } else {
            let tail = pieces[at - 1].last_mut().expect("bounds imply a piece");
            if tail.points.last() != Some(&after.0) {
                tail.points.push(after.0);
            }
        }
    }

    // Every piece of a corridor run is stored in the reference's direction, so all its
    // members share one left-hand normal and ordinal `i` is the same physical side for all of
    // them. An ease piece is cut from the member's own survey and inherits that survey's
    // direction, so for a member travelling against the reference it has to be turned round
    // here or the fan mirrors across the ease. Only the order the pieces are listed in
    // follows the candidate.
    for (run_pieces, against) in pieces.iter_mut().zip(&against) {
        if *against {
            for span in run_pieces.iter_mut() {
                span.points.reverse();
            }
        }
    }

    let flat: Vec<Span> = pieces.into_iter().flatten().collect();
    if flat.is_empty() {
        whole()
    } else {
        flat
    }
}

/// Cumulative ground distance to each vertex of a polyline.
fn cumulative(points: &[(i32, i32)]) -> Vec<f64> {
    let mut out = Vec::with_capacity(points.len());
    let mut total = 0.0;
    for (at, point) in points.iter().enumerate() {
        if at > 0 {
            total += distance_m(points[at - 1], *point);
        }
        out.push(total);
    }
    out
}

/// The point `at` metres along a polyline, interpolated within its segment.
fn point_at(points: &[(i32, i32)], cum: &[f64], at: f64) -> (i32, i32) {
    if points.len() < 2 {
        return points.first().copied().unwrap_or((0, 0));
    }
    let last = points.len() - 1;
    if at <= 0.0 {
        return points[0];
    }
    if at >= cum[last] {
        return points[last];
    }
    let seg = cum.partition_point(|d| *d <= at).max(1) - 1;
    let span = cum[seg + 1] - cum[seg];
    let t = if span > 0.0 { (at - cum[seg]) / span } else { 0.0 };
    let (a, b) = (points[seg], points[seg + 1]);
    (
        (a.0 as f64 + (b.0 - a.0) as f64 * t).round() as i32,
        (a.1 as f64 + (b.1 - a.1) as f64 * t).round() as i32,
    )
}

/// The part of a polyline between two distances along it, with both ends interpolated.
///
/// Both ends land exactly on the polyline, so two adjacent slices share their boundary
/// vertex and the pieces of one route meet. Empty when the two distances leave nothing
/// between them.
fn slice_between(points: &[(i32, i32)], cum: &[f64], from: f64, to: f64) -> Vec<(i32, i32)> {
    if points.len() < 2 {
        return Vec::new();
    }
    let total = cum[cum.len() - 1];
    let (from, to) = (from.clamp(0.0, total), to.clamp(0.0, total));
    let (from, to) = if from <= to { (from, to) } else { (to, from) };
    let mut out = vec![point_at(points, cum, from)];
    for (at, along) in cum.iter().enumerate() {
        if *along > from && *along < to && out[out.len() - 1] != points[at] {
            out.push(points[at]);
        }
    }
    let end = point_at(points, cum, to);
    if out[out.len() - 1] != end {
        out.push(end);
    }
    if out.len() < 2 {
        Vec::new()
    } else {
        out
    }
}

/// How far along a corridor's reference the nearest point to `p` lies.
fn distance_along(corridor: &Corridor, p: (i32, i32)) -> f64 {
    nearest(corridor, p).1
}

/// The nearest point on a corridor's reference to `p`, as `(distance to it, distance along)`.
fn nearest(corridor: &Corridor, p: (i32, i32)) -> (f64, f64) {
    let (points, cum) = (&corridor.points, &corridor.cum);
    let mut best = (f64::INFINITY, 0.0f64);
    for at in 0..points.len().saturating_sub(1) {
        let (t, offset) = project(p, points[at], points[at + 1], 0.0);
        if offset < best.0 {
            best = (offset, cum[at] + t * (cum[at + 1] - cum[at]));
        }
    }
    best
}

/// How far to the side of a corridor's reference `p` lies, in metres, signed positive to the
/// right of the reference's direction of travel.
///
/// The same nearest-segment walk [`distance_along`] does, because [`project`] returns an
/// unsigned distance and no side. Against the nearest segment's unit tangent `(ux, uy)` in an
/// (east, north) frame — the frame [`directed`] already produces — the side of the offset `d`
/// is `d.east * uy - d.north * ux`. Positive is the right-hand side because `tess::stroke`
/// emits `(0, +1)` as the normal of an eastward segment and `line.vert` shifts along it, so
/// ascending offset is ascending ordinal: `lane_offset_px` gives ordinal zero the most
/// negative offset, which is the left of the reference's travel.
///
/// Only the reference's own stretch of the corridor, `extent`, is searched. Past the mouth the
/// reference has left the corridor too, and the segment nearest an approaching member is then
/// as likely to be the reference's departure as the corridor — which reads a member leaving
/// west as having no side at all. Clamped, the approach is measured against the reference's
/// tangent at the mouth, which is the axis the lanes are laid out across.
fn signed_offset(corridor: &Corridor, extent: (f64, f64), p: (i32, i32)) -> f64 {
    let (points, cum) = (&corridor.points, &corridor.cum);
    let mut best = (f64::INFINITY, 0.0f64);
    for at in 0..points.len().saturating_sub(1) {
        if cum[at + 1] < extent.0 || cum[at] > extent.1 {
            continue;
        }
        let (a, b) = (points[at], points[at + 1]);
        let (_, distance) = project(p, a, b, 0.0);
        if distance >= best.0 {
            continue;
        }
        let cos_lat = ((a.0 as f64 + b.0 as f64) * 0.5 * 1e-7).to_radians().cos();
        let (east, north) = ((b.1 - a.1) as f64 * cos_lat, (b.0 - a.0) as f64);
        let length = (east * east + north * north).sqrt();
        if length <= 0.0 {
            continue;
        }
        let (ux, uy) = (east / length, north / length);
        let d_east = (p.1 - a.1) as f64 * 1e-7 * 111_320.0 * cos_lat;
        let d_north = (p.0 - a.0) as f64 * 1e-7 * 111_320.0;
        best = (distance, d_east * uy - d_north * ux);
    }
    best.1
}

/// One probe point along a candidate, with the unit direction of travel there.
struct Sample {
    route: u32,
    point: (i32, i32),
    ux: f64,
    uy: f64,
}

/// Walk a polyline at [`SAMPLE_M`], returning each sample with its unit direction of travel.
fn walk(points: &[(i32, i32)]) -> Vec<((i32, i32), f64, f64)> {
    let walked = resample(points, SAMPLE_M);
    if walked.len() < 2 {
        return Vec::new();
    }
    directed(&walked)
}

/// Each point of `line` with the unit direction of travel there. One entry per input point:
/// a degenerate segment carries the previous direction forward rather than dropping the
/// point, so the result stays index-aligned with its input.
fn directed(line: &[(i32, i32)]) -> Vec<((i32, i32), f64, f64)> {
    let mut out = Vec::with_capacity(line.len());
    let (mut ux, mut uy) = (1.0f64, 0.0f64);
    for i in 0..line.len() {
        let (a, b) = if i + 1 < line.len() { (line[i], line[i + 1]) } else { (line[i.max(1) - 1], line[i]) };
        let cos_lat = ((a.0 as f64 + b.0 as f64) * 0.5 * 1e-7).to_radians().cos();
        let dy = (b.0 - a.0) as f64;
        let dx = (b.1 - a.1) as f64 * cos_lat;
        let length = (dx * dx + dy * dy).sqrt();
        if length > 0.0 {
            (ux, uy) = (dx / length, dy / length);
        }
        out.push((line[i], ux, uy));
    }
    out
}

/// The nine cells a corridor-radius match can live in, centre first.
///
/// Returning on the first match means visit order decides how much of the neighbourhood is
/// scanned. The point's own cell is by far the likeliest to hold it — a corridor of 30 m inside a
/// cell of 40 m — so it goes first, then the four edge neighbours, then the corners, which can
/// only match across a cell join. Visiting them in `-1..=1` order put a corner first and scanned
/// most of the neighbourhood before reaching the answer.
///
/// A pure reordering: the set of samples examined is unchanged, so every result is too.
const NEIGHBOURHOOD: [(i32, i32); 9] =
    [(0, 0), (1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)];

/// Track already drawn, for suppressing a line that adds nothing.
///
/// Two services of one colour must never draw as parallel lines: nothing distinguishes them,
/// so the second is pure over-draw. What stops that is the lane, which is assigned per
/// **colour** — two lines of one colour in one corridor take the same offset over the same
/// reference geometry and coincide exactly, reading as the one line they are.
///
/// This is the other half: a line wholly on top of track already drawn in its colour is not
/// worth carrying at all. A route publishes its two directions, its short-turns and its
/// branch variants as separate shapes, and every other feed covering the city republishes
/// the lot re-surveyed a few metres off.
///
/// **Whole lines only.** Subtracting the covered *parts* of a line was tried and is wrong: it
/// cut every route into fragments, and a stretch too short to be worth emitting left a hole
/// that nothing else drew. A line either adds something or it does not.
///
/// The lookup is a `HashMap` of cells rather than the sorted vector the rest of this module
/// uses. Nothing iterates it — the answer is a boolean about one point — so it cannot leak an
/// order into the output.
#[derive(Default)]
pub struct Covered {
    points: Vec<((i32, i32), f64, f64)>,
    cells: std::collections::HashMap<Cell, Vec<u32>>,
    /// Which service drew each sample, parallel to `points`. See [`Covered::crowd`].
    tags: Vec<u32>,
}

impl Covered {
    /// Record every metre of `line` as drawn.
    pub fn add(&mut self, line: &[(i32, i32)]) {
        self.add_tagged(line, 0);
    }

    /// Record every metre of `line` as drawn by service `tag`.
    pub fn add_tagged(&mut self, line: &[(i32, i32)], tag: u32) {
        for sample in walk(line) {
            let at = self.points.len() as u32;
            self.cells.entry(cell_of(sample.0)).or_default().push(at);
            self.points.push(sample);
            self.tags.push(tag);
        }
    }

    /// The most distinct services already drawn over any one metre of `line`.
    ///
    /// The colour-scoped gates above stop a service being drawn twice, but they are deliberately
    /// blind to *other* services: two lines sharing a track are two real services and both should
    /// draw, fanned into lanes. That holds for a city. It does not hold for a planet, where one
    /// physical alignment is republished by a city feed, the regional feed that contains it and a
    /// national feed on top, each under its own `route_color` and often its own `route_type` — all
    /// of which read as distinct services and each claim a lane. That is what turns one railway
    /// into fifteen jagged parallel lines when you zoom in.
    ///
    /// So the rule is a ceiling rather than a ban: a few services over one track is real, fifteen
    /// is a data artefact. Returns the worst point rather than an average, because a line that
    /// joins a crowded trunk for part of its length is exactly the case worth suppressing.
    ///
    /// `limit` stops the walk as soon as any point reaches it. The answer above the ceiling is
    /// never used, only compared against it, and this is called once per surviving line over a
    /// structure holding every sample of every line already drawn.
    pub fn crowd_reaches(&self, line: &[(i32, i32)], limit: usize) -> bool {
        if limit == 0 {
            return true;
        }
        let mut seen: Vec<u32> = Vec::new();
        for &(point, ux, uy) in &walk(line) {
            seen.clear();
            self.tags_over(point, ux, uy, &mut seen, limit);
            if seen.len() >= limit {
                return true;
            }
        }
        false
    }

    /// The most distinct services already drawn over any one metre of `line`.
    ///
    /// Unbounded, for tests and for reporting. Prefer [`crowd_reaches`](Self::crowd_reaches) on
    /// the build path, which stops as soon as the answer can no longer change the decision.
    pub fn crowd(&self, line: &[(i32, i32)]) -> usize {
        let mut worst = 0;
        let mut seen: Vec<u32> = Vec::new();
        for &(point, ux, uy) in &walk(line) {
            seen.clear();
            self.tags_over(point, ux, uy, &mut seen, usize::MAX);
            worst = worst.max(seen.len());
        }
        worst
    }

    /// Every distinct service drawn within [`CORRIDOR_M`] of this point, running parallel to it.
    ///
    /// Stops once `limit` distinct services have been found, since no caller needs more.
    fn tags_over(&self, point: (i32, i32), ux: f64, uy: f64, out: &mut Vec<u32>, limit: usize) {
        let (cx, cy) = cell_of(point);
        for (dx, dy) in NEIGHBOURHOOD {
            let Some(bucket) = self.cells.get(&(cx + dx, cy + dy)) else { continue };
            for &i in bucket {
                let (other, oux, ouy) = self.points[i as usize];
                if (ux * oux + uy * ouy).abs() < COS_FOLD {
                    continue;
                }
                if distance_m(point, other) <= CORRIDOR_M {
                    let tag = self.tags[i as usize];
                    if !out.contains(&tag) {
                        out.push(tag);
                        if out.len() >= limit {
                            return;
                        }
                    }
                }
            }
        }
    }

    /// Is every metre of `line` already drawn?
    ///
    /// Walked at [`SAMPLE_M`] rather than at the line's own vertices, whose spacing is a
    /// feed's business and is sometimes hundreds of metres.
    pub fn contains(&self, line: &[(i32, i32)]) -> bool {
        let walked = walk(line);
        !walked.is_empty()
            && walked.iter().all(|&(point, ux, uy)| self.covers(point, ux, uy))
    }

    /// Is this point already drawn, by track running parallel to it?
    ///
    /// Returns on the first match, so visit order matters — see [`NEIGHBOURHOOD`], which is
    /// ordered for exactly that reason. The buckets are unbounded: [`add`](Self::add) records
    /// every sample of every line it draws, and a trunk alignment republished by a dozen feeds is
    /// a dozen overlapping sample runs in the same cells.
    fn covers(&self, point: (i32, i32), ux: f64, uy: f64) -> bool {
        let (cx, cy) = cell_of(point);
        for (dx, dy) in NEIGHBOURHOOD {
            let Some(bucket) = self.cells.get(&(cx + dx, cy + dy)) else { continue };
            for &i in bucket {
                let (other, oux, ouy) = self.points[i as usize];
                if (ux * oux + uy * ouy).abs() < COS_FOLD {
                    continue;
                }
                if distance_m(point, other) <= CORRIDOR_M {
                    return true;
                }
            }
        }
        false
    }
}

/// Every candidate's samples, flat, with the range each candidate's own block occupies.
fn sample_all(candidates: &[Candidate]) -> (Vec<Sample>, Vec<Range<usize>>) {
    let mut out = Vec::new();
    let mut blocks = Vec::with_capacity(candidates.len());
    for candidate in candidates {
        let start = out.len();
        for (point, ux, uy) in walk(candidate.points) {
            out.push(Sample { route: candidate.route, point, ux, uy });
        }
        blocks.push(start..out.len());
    }
    (out, blocks)
}

type Cell = (i32, i32);

/// The cell a point falls in. Square in metres rather than in degrees, so a cell near the
/// pole is not a sliver: longitude is scaled by the cosine of its own latitude.
fn cell_of(point: (i32, i32)) -> Cell {
    let lat = point.0 as f64 * 1e-7;
    let cos_lat = lat.to_radians().cos().max(1e-6);
    let y = lat * 111_320.0 / CELL_M;
    let x = point.1 as f64 * 1e-7 * 111_320.0 * cos_lat / CELL_M;
    (x.floor() as i32, y.floor() as i32)
}

fn bucket<T: Copy>(grid: &[(Cell, T)], key: Cell) -> &[(Cell, T)] {
    let lo = grid.partition_point(|(k, _)| *k < key);
    let hi = grid.partition_point(|(k, _)| *k <= key);
    &grid[lo..hi]
}

/// A sorted flat `(cell, sample)` vector, as [`crate::index`]'s transfer grid is.
struct Grid {
    entries: Vec<(Cell, u32)>,
}

impl Grid {
    fn build(samples: &[Sample]) -> Grid {
        let mut entries: Vec<(Cell, u32)> =
            samples.iter().enumerate().map(|(i, s)| (cell_of(s.point), i as u32)).collect();
        entries.sort_unstable();
        Grid { entries }
    }

    /// The routes with a sample within [`CORRIDOR_M`] of `at` running parallel to it,
    /// ascending and deduplicated. Always contains `at`'s own route.
    ///
    /// `seen` is a caller-owned scratch array of one slot per route, holding the `stamp` of
    /// the sample a route was last accepted for. It replaces an `out.contains()` linear scan
    /// that ran once per neighbouring sample: a 40 m cell holds ~4 samples per line at
    /// [`SAMPLE_M`], so the nine-cell neighbourhood holds ~36 per route in the corridor, and
    /// scanning `out` for each made the probe quadratic in *local route density*. On a
    /// world-scale set that is the whole cost — dense metros carry the same trunk track
    /// republished by every feed covering the city, so density there is far higher than the
    /// feed count suggests, and the run time grows much faster than the input does.
    ///
    /// A route is stamped only when it is **accepted**, never when the angle or distance test
    /// rejects it. That is what the `contains` check did — it tested membership of `out`, not
    /// of everything examined — and a route rejected against one sample must stay eligible
    /// against a nearer one in the same neighbourhood.
    fn routes_near(
        &self,
        samples: &[Sample],
        at: &Sample,
        out: &mut Vec<u32>,
        seen: &mut [u32],
        stamp: u32,
    ) {
        out.clear();
        out.push(at.route);
        seen[at.route as usize] = stamp;
        let (cx, cy) = cell_of(at.point);
        for dx in -1..=1 {
            for dy in -1..=1 {
                for &(_, i) in bucket(&self.entries, (cx + dx, cy + dy)) {
                    let other = &samples[i as usize];
                    if seen[other.route as usize] == stamp {
                        continue;
                    }
                    if (at.ux * other.ux + at.uy * other.uy).abs() < COS_FOLD {
                        continue;
                    }
                    if distance_m(at.point, other.point) <= CORRIDOR_M {
                        seen[other.route as usize] = stamp;
                        out.push(other.route);
                    }
                }
            }
        }
        out.sort_unstable();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A straight line of `n` points, `metres` apart, running north from `(lat, lon)`.
    fn north(lat: f64, lon: f64, metres: f64, n: usize) -> Vec<(i32, i32)> {
        let step = metres / 111_320.0 * 1e7;
        (0..n).map(|i| (((lat * 1e7) + i as f64 * step) as i32, (lon * 1e7) as i32)).collect()
    }

    /// The same line shifted `metres` east: a second track in one corridor.
    fn shifted(line: &[(i32, i32)], metres: f64) -> Vec<(i32, i32)> {
        let cos_lat = (line[0].0 as f64 * 1e-7).to_radians().cos();
        let d = (metres / (111_320.0 * cos_lat) * 1e7) as i32;
        line.iter().map(|&(lat, lon)| (lat, lon + d)).collect()
    }

    /// One line offered to [`assign`]: route, colour, name and geometry.
    type Fixture = (u32, u32, &'static str, Vec<(i32, i32)>);

    fn candidates(lines: &[Fixture]) -> Vec<Candidate<'_>> {
        lines
            .iter()
            .map(|(route, color, name, points)| Candidate {
                points,
                route: *route,
                color: *color,
                name,
            })
            .collect()
    }

    /// The lane inputs each candidate ends up with: the `(ordinal, count)` of the span
    /// furthest into its lane, which is the one the tapers lead into.
    fn lanes(spans: &[Vec<Span>]) -> Vec<(u8, u8)> {
        spans
            .iter()
            .map(|s| {
                s.iter()
                    .max_by_key(|span| (span.lanes, span.taper))
                    .map(|span| (span.ordinal, span.lanes))
                    .unwrap_or((0, 1))
            })
            .collect()
    }

    #[test]
    fn a_route_running_alone_is_a_corridor_of_one() {
        let line = north(37.7, -122.4, 1000.0, 20);
        let lines = [(0u32, 0x00_54_A5u32, "N", line.clone())];
        let spans = assign(&candidates(&lines));
        assert_eq!(spans, vec![vec![Span { points: line, ordinal: 0, lanes: 1, taper: 255 }]]);
    }

    /// The corridor's colours get consecutive ordinals from zero and every member reports the
    /// same count, which is all the renderer needs to centre the fan on the track.
    #[test]
    fn two_routes_on_one_track_take_the_two_ordinals_of_a_corridor_of_two() {
        let a = north(37.7, -122.4, 1000.0, 20);
        let b = shifted(&a, 8.0);
        let lines = [(0u32, 0x00_00_FFu32, "A", a), (1, 0xFF_00_00, "B", b)];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 2), (1, 2)]);
    }

    /// A corridor of three is three ordinals of three, laid out across it: the west track
    /// takes lane zero, because lane zero is the left of the reference's direction of travel
    /// and this one runs north.
    #[test]
    fn three_routes_on_one_track_take_three_ordinals() {
        let a = north(37.7, -122.4, 1000.0, 20);
        let (b, c) = (shifted(&a, 8.0), shifted(&a, -8.0));
        let lines =
            [(0u32, 0x00_00_11u32, "A", a), (1, 0x00_00_22, "B", b), (2, 0x00_00_33, "C", c)];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(1, 3), (2, 3), (0, 3)], "west to east");
    }

    /// THE defect this pass exists to fix: the members of a corridor draw the *same*
    /// polyline at their own offsets, so they are exactly parallel and evenly spaced rather
    /// than each being its own survey pushed sideways.
    #[test]
    fn the_members_of_a_corridor_all_draw_the_reference_geometry() {
        let a = north(37.7, -122.4, 1000.0, 20);
        let b = shifted(&a, 8.0);
        let lines = [(0u32, 0x00_00_11u32, "A", a.clone()), (1, 0x00_00_22, "B", b)];
        let spans = assign(&candidates(&lines));
        assert_eq!(spans[0].len(), 1, "one corridor, one span");
        assert_eq!(spans[1].len(), 1);
        assert_eq!(spans[0][0].points, a, "the lower colour is the reference");
        assert_eq!(spans[1][0].points, a, "and its corridor-mate draws it too");
    }

    /// The tie-break under the geometric order: two routes published on one identical survey
    /// have the same offset everywhere, so there is no ground truth to recover and the order
    /// falls to the colour and then the name — never the input order, or a feed reordering
    /// its routes would move every line on the map.
    #[test]
    fn coincident_surveys_fall_back_to_colour_then_name() {
        let track = north(37.7, -122.4, 1000.0, 20);
        let lines = [(0u32, 0xFF_00_00u32, "Z", track.clone()), (1, 0x00_00_FF, "A", track)];
        let spans = assign(&candidates(&lines));
        // Route 1's colour is lower, so it takes the first ordinal despite being second in.
        assert_eq!(lanes(&spans), vec![(1, 2), (0, 2)]);
    }

    /// The direction bucket is the whole reason a junction is not a corridor.
    #[test]
    fn two_tracks_crossing_at_a_junction_do_not_share_a_corridor() {
        let north_south = north(37.7, -122.4, 1000.0, 20);
        // Due east through the middle of it.
        let mid = north_south[10];
        let cos_lat = (mid.0 as f64 * 1e-7).to_radians().cos();
        let step = (1000.0 / (111_320.0 * cos_lat) * 1e7) as i32;
        let east_west: Vec<(i32, i32)> =
            (0..20).map(|i| (mid.0, mid.1 + (i - 10) * step)).collect();
        let lines = [(0u32, 0x11u32, "NS", north_south), (1, 0x22, "EW", east_west)];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 1), (0, 1)]);
    }

    /// The renderer's perpendicular is the polyline's own left-hand normal, so the side a
    /// lane lands on depends on the direction the geometry is stored in. Every member of a
    /// corridor stores the reference in the reference's own **canonical** direction, so
    /// neither the direction a feed happened to publish a member in nor which member won the
    /// reference tie-break can reach the output.
    #[test]
    fn the_direction_an_input_is_stored_in_does_not_reach_the_output() {
        let a = north(37.7, -122.4, 1000.0, 20);
        let b = shifted(&a, 8.0);
        let flip = |line: &[(i32, i32)]| -> Vec<(i32, i32)> {
            line.iter().rev().copied().collect()
        };

        let forward = [(0u32, 0x11u32, "A", a.clone()), (1, 0x22, "B", b.clone())];
        let member = [(0u32, 0x11u32, "A", a.clone()), (1, 0x22, "B", flip(&b))];
        assert_eq!(assign(&candidates(&forward)), assign(&candidates(&member)));
        // And the reference itself, which is what the canonical fold is for: without it the
        // whole fan mirrors and B lands on the far side of A.
        let reference = [(0u32, 0x11u32, "A", flip(&a)), (1, 0x22, "B", b)];
        assert_eq!(assign(&candidates(&forward)), assign(&candidates(&reference)));
    }

    /// The same, for a fixture that actually eases. An ease piece is cut from the member's
    /// own survey and inherits whatever direction the feed stored that in, and `stroke::band`
    /// takes its normal from the polyline's own direction — so a piece left the wrong way
    /// round puts the member on the far side of the fan across the ease and nowhere else.
    #[test]
    fn an_ease_against_the_references_direction_does_not_mirror() {
        let forward = a_shallow_merge();
        let mut flipped = a_shallow_merge();
        flipped[1].3.reverse();
        let reference = corridor_of(&forward[0].3);

        assert_eq!(lanes(&assign(&candidates(&forward))), lanes(&assign(&candidates(&flipped))));
        for lines in [forward, flipped] {
            let spans = assign(&candidates(&lines));
            assert_eq!(
                spans[1].iter().filter(|s| s.taper < 255).count(),
                TAPER_STEPS,
                "the fixture has to actually ease, or this proves nothing",
            );
            for candidate in &spans {
                for span in candidate.iter().filter(|s| s.lanes > 1) {
                    let head = distance_along(&reference, span.points[0]);
                    let tail =
                        distance_along(&reference, span.points[span.points.len() - 1]);
                    assert!(head <= tail, "a piece stored against the reference: {span:?}");
                }
            }
        }
    }

    /// The junction the merging fixtures meet at.
    const JUNCTION: (f64, f64) = (37.70, -122.40);

    /// A line approaching [`JUNCTION`] from `(east, north)` metres away and then running 4 km
    /// north out of it, the whole thing shifted `side` metres east so several of them are one
    /// corridor over the northbound stretch and separate groups on the way in.
    fn joins_from(from: (f64, f64), side: f64) -> Vec<(i32, i32)> {
        let cos_lat = JUNCTION.0.to_radians().cos();
        let east = |m: f64| m / (111_320.0 * cos_lat) * 1e7;
        let up = |m: f64| m / 111_320.0 * 1e7;
        let (jy, jx) = (JUNCTION.0 * 1e7, JUNCTION.1 * 1e7 + east(side));
        let steps = 20;
        let mut line: Vec<(i32, i32)> = (0..steps)
            .map(|i| {
                let t = 1.0 - i as f64 / steps as f64;
                ((jy + up(from.1 * t)) as i32, (jx + east(from.0 * t)) as i32)
            })
            .collect();
        line.extend((0..=40).map(|i| ((jy + up(i as f64 * 100.0)) as i32, jx as i32)));
        line
    }

    /// A line arriving from the south-west.
    const FROM_WEST: (f64, f64) = (-1400.0, -1400.0);
    /// A line arriving from the south-east.
    const FROM_EAST: (f64, f64) = (1400.0, -1400.0);

    /// THE defect this pass exists to fix. Two routes that merge and then run north together
    /// keep the sides they arrived on: the one that came from the west stays west, whatever
    /// its colour, because lane zero is the left of the reference's direction of travel.
    #[test]
    fn two_routes_merging_keep_the_sides_they_arrived_on() {
        let lines = [
            (0u32, 0x00_00_11u32, "E", joins_from(FROM_EAST, 8.0)),
            (1, 0x00_00_22, "W", joins_from(FROM_WEST, 0.0)),
        ];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(1, 2), (0, 2)], "the western arrival takes lane 0");
    }

    /// Two pairs that each share a corridor and then all four share one: each pair stays
    /// contiguous in the merged order and keeps its internal order, rather than the two
    /// groups interleaving by colour value. The colours here run the other way to the
    /// geometry, so ordering by colour would interleave them.
    #[test]
    fn two_groups_joining_keep_their_blocks() {
        let lines = [
            (0u32, 0x00_00_33u32, "P1", joins_from(FROM_WEST, 0.0)),
            (1, 0x00_00_44, "P2", joins_from(FROM_WEST, 8.0)),
            (2, 0x00_00_11, "Q1", joins_from(FROM_EAST, 16.0)),
            (3, 0x00_00_22, "Q2", joins_from(FROM_EAST, 24.0)),
        ];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 4), (1, 4), (2, 4), (3, 4)]);
    }

    /// A lone line joining a group lands on the outside of it, on the side it joined from,
    /// rather than in the middle of a group it was never part of.
    #[test]
    fn a_lone_line_joining_a_group_lands_on_the_outside_of_it() {
        let lines = [
            (0u32, 0x00_00_22u32, "P1", joins_from(FROM_WEST, 0.0)),
            (1, 0x00_00_33, "P2", joins_from(FROM_WEST, 8.0)),
            (2, 0x00_00_11, "Lone", joins_from(FROM_EAST, 16.0)),
        ];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 3), (1, 3), (2, 3)], "and not between the pair");
    }

    /// Two lines of one route — a trunk and a branch — are one member of the corridor and
    /// take one lane, because the lane is per route and not per polyline.
    #[test]
    fn two_lines_of_one_route_share_its_lane() {
        let trunk = north(37.7, -122.4, 1000.0, 20);
        let branch = north(37.7, -122.4, 1000.0, 10);
        let other = shifted(&trunk, 8.0);
        let lines = [
            (0u32, 0x11u32, "A", trunk.clone()),
            (0, 0x11, "A", branch),
            (1, 0x22, "B", other),
        ];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 2), (0, 2), (1, 2)], "one route, one lane");
        // And the reference is the *longer* of the two, or the trunk would be clipped to
        // the branch that runs half of it.
        assert_eq!(spans[2][0].points, trunk);
    }

    /// Two services of one colour — a feed publishing each direction as its own route —
    /// take one lane, so they coincide exactly rather than drawing as two lines nothing
    /// distinguishes.
    #[test]
    fn two_routes_of_one_colour_take_one_lane_and_coincide() {
        let a = north(37.7, -122.4, 1000.0, 20);
        let b: Vec<(i32, i32)> = shifted(&a, 8.0).iter().rev().copied().collect();
        let lines = [(0u32, 0x11u32, "Yellow-N", a), (1, 0x11, "Yellow-S", b)];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 1), (0, 1)], "one colour is one lane");
        assert_eq!(spans[0], spans[1], "so the two directions are the same line");
    }

    /// The lane is per corridor, not per route: a service that shares one track here and a
    /// different one there takes its place in each. Carrying one lane for a whole route is
    /// what left BART's five services on a sparse uneven subset across the bay.
    #[test]
    fn a_route_crossing_two_corridors_takes_a_lane_in_each() {
        // The main line runs 6 km north. One route joins it for the first 2 km and another
        // for the last 2 km, with 2 km to itself in between.
        let main = north(37.70, -122.40, 100.0, 61);
        let first = shifted(&north(37.70, -122.40, 100.0, 21), 8.0);
        let last = shifted(&north(37.70 + 4000.0 / 111_320.0, -122.40, 100.0, 21), -8.0);
        let lines = [
            (0u32, 0x00_00_10u32, "Main", main),
            (1, 0x00_00_20, "First", first),
            (2, 0x00_00_05, "Last", last),
        ];
        let spans = assign(&candidates(&lines));
        let places: Vec<(u8, u8)> = spans[0].iter().map(|s| (s.ordinal, s.lanes)).collect();
        // West of the first corridor's partner and east of the second's, so it takes the
        // west lane of one and the east lane of the other.
        assert!(places.contains(&(0, 2)), "the first corridor's lower ordinal: {places:?}");
        assert!(places.contains(&(1, 2)), "the second corridor's higher ordinal: {places:?}");
        assert!(places.contains(&(0, 1)), "and its own track in between: {places:?}");
        assert_eq!(lanes(&spans[1..2]), vec![(1, 2)], "the first corridor's higher ordinal");
        assert_eq!(lanes(&spans[2..3]), vec![(0, 2)], "the second corridor's lower ordinal");
    }

    /// Nothing here caps the count: the style bounds how many lanes are drawn, so a corridor
    /// of five reports five and every member gets its own ordinal.
    #[test]
    fn every_colour_of_a_corridor_gets_its_own_ordinal() {
        let a = north(37.7, -122.4, 1000.0, 20);
        // Within `CORRIDOR_M` end to end, or the outer two would be in corridors of their
        // own rather than in one of five.
        let lines: Vec<Fixture> = (0..5)
            .map(|i| {
                let colour = 0x10u32 + i;
                let points = shifted(&a, (i as f64 - 2.0) * 6.0);
                (i, colour, "X", points)
            })
            .collect();
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 5), (1, 5), (2, 5), (3, 5), (4, 5)]);
    }

    /// THE case the corridor rule exists for: a radial network. Three services share a short
    /// central subway and then run a long way alone, and the lane has to be taken where it
    /// does some work rather than where the route spends its length.
    #[test]
    fn a_shared_trunk_gives_a_lane_and_the_run_alone_does_not() {
        let lines = trunk_then_branches(2000.0, 3);
        let spans = assign(&candidates(&lines));
        // C leaves the trunk westward and A eastward, so they take the outer lanes and B,
        // which carries straight on, takes the middle.
        assert_eq!(lanes(&spans), vec![(2, 3), (1, 3), (0, 3)], "the shared trunk decides");
        for candidate in &spans {
            assert_eq!(
                candidate.last().expect("a span").lanes,
                1,
                "and the long run alone stays on its own alignment",
            );
        }
    }

    /// The guard on that rule: two alignments that touch briefly leaving a station are not a
    /// corridor, and fanning a route out on that evidence would move it off its own casing
    /// for nothing.
    #[test]
    fn a_run_shorter_than_the_minimum_does_not_become_a_corridor() {
        let lines = trunk_then_branches(100.0, 2);
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 1), (0, 1)]);
        for candidate in &spans {
            assert_eq!(candidate.len(), 1, "and the route is not cut up over it");
        }
    }

    /// A route stepping sideways by nine Dp at a corridor mouth reads as a break in the
    /// line, so it eases in over a hundred metres or more instead.
    #[test]
    fn a_route_tapers_into_its_lane_at_the_end_of_a_corridor() {
        let lines = trunk_then_branches(2000.0, 4);
        let spans = assign(&candidates(&lines));
        let pieces: Vec<(u8, u8, u8)> =
            spans[0].iter().map(|s| (s.ordinal, s.lanes, s.taper)).collect();
        assert_eq!(pieces[0], (3, 4, 255), "the east lane of four, fully in lane");
        assert_eq!(
            pieces.last().expect("a span").1,
            1,
            "and its own track at the far end",
        );
        let tapers: Vec<u8> = pieces.iter().filter(|p| p.1 == 4).map(|p| p.2).collect();
        assert!(
            tapers.windows(2).all(|w| w[0] >= w[1]),
            "the taper only ever eases out of the lane: {tapers:?}",
        );
        assert!(pieces.len() >= 2 + TAPER_STEPS, "with a step per taper piece: {pieces:?}");
        // The ramp is equal-stepped the whole way, including the jump between the last ease
        // piece and the full lane of the body — there is no gap at either end of it.
        let step = 255.0 / (TAPER_STEPS + 1) as f64;
        for pair in tapers.windows(2) {
            let jump = f64::from(pair[0]) - f64::from(pair[1]);
            assert!((jump - step).abs() <= 1.0, "an uneven jump in the ease: {tapers:?}");
        }
        assert_eq!(
            *tapers.last().expect("a taper"),
            taper_fraction(1),
            "and the far end of the ease is one step off the alignment: {tapers:?}",
        );
    }

    /// A line in a lane on both sides of a run boundary steps from one lane to the other. It
    /// has no alignment of its own to ease onto there, and tapering to nothing and back is
    /// what collapsed every member of both corridors onto the centreline at the seam.
    #[test]
    fn two_abutting_corridors_do_not_taper_between_them() {
        let spans = assign(&candidates(&back_to_back_corridors()));
        for span in &spans[0] {
            assert_eq!(
                span.taper, 255,
                "the through route dips out of its lane at the seam: {span:?}",
            );
        }
    }

    /// A member hands over to the reference where the two surveys are close enough that the
    /// swap cannot be seen, not where they first count as one corridor. Two lines closing at
    /// a shallow angle are one corridor thirty metres apart, and handing over there puts the
    /// whole thirty metres into one sideways step at the mouth.
    #[test]
    fn a_shallow_approach_draws_its_own_track_until_it_is_close() {
        let lines = a_shallow_merge();
        let spans = assign(&candidates(&lines));
        let reference = corridor_of(&lines[0].3);
        let body = spans[1]
            .iter()
            .position(|s| s.lanes > 1 && s.taper == 255)
            .expect("a corridor body");
        let ease_from =
            spans[1].iter().position(|s| s.lanes > 1).expect("a piece in the corridor");
        assert!(ease_from < body, "the joining line eases in before it reaches the reference");
        assert!(
            nearest(&reference, spans[1][ease_from].points[0]).0 > SNAP_M,
            "the ease starts on the member's own track, well off the reference",
        );
        // Adaptive: the ease stretches to reach the handover rather than being the fixed
        // hundred metres a member that is already on the reference gets.
        let eased: f64 = spans[1][..body]
            .iter()
            .filter(|span| span.lanes > 1)
            .flat_map(|span| span.points.windows(2))
            .map(|pair| distance_m(pair[0], pair[1]))
            .sum();
        assert!(eased > TAPER_M, "an ease of only {eased} m for a kilometre of convergence");
        // The weld carries the body's first point onto the end of the ease, so the last step
        // of the ease *is* the handover.
        let ease = &spans[1][body - 1].points;
        let step = distance_m(ease[ease.len() - 2], ease[ease.len() - 1]);
        assert!(step <= SNAP_M, "a handover step of {step} m");
    }

    /// The pieces of one route meet: the span before a corridor is carried across to the
    /// point on the reference the corridor span begins at, and so is the ease across to the
    /// body it leads into.
    #[test]
    fn the_spans_of_one_route_share_their_boundary_vertex() {
        for lines in
            [trunk_then_branches(2000.0, 4), back_to_back_corridors(), a_shallow_merge()]
        {
            let spans = assign(&candidates(&lines));
            for candidate in &spans {
                for pair in candidate.windows(2) {
                    assert_eq!(
                        pair[0].points.last(),
                        pair[1].points.first(),
                        "a gap between two spans of one route",
                    );
                }
            }
        }
    }

    /// A route can step straight out of one corridor and into the next, with nothing of its
    /// own in between and two different references either side of the seam.
    #[test]
    fn a_route_passing_from_one_corridor_into_the_next_changes_lane_where_they_meet() {
        let spans = assign(&candidates(&back_to_back_corridors()));
        let places: Vec<(u8, u8)> = spans[0].iter().map(|s| (s.ordinal, s.lanes)).collect();
        assert!(places.contains(&(0, 2)), "the first corridor's lower ordinal: {places:?}");
        assert!(places.contains(&(1, 2)), "the second corridor's higher ordinal: {places:?}");
        assert!(
            !places.iter().any(|p| p.1 == 1),
            "and no stretch of its own between them: {places:?}",
        );
    }

    /// A 6 km line, shared with one route over its first half and another over its second,
    /// on opposite sides of it, so the lane it takes really does have to change at the seam.
    fn back_to_back_corridors() -> Vec<Fixture> {
        vec![
            (0u32, 0x00_00_10u32, "Main", north(37.70, -122.40, 100.0, 61)),
            (1, 0x00_00_20, "First", shifted(&north(37.70, -122.40, 100.0, 31), 8.0)),
            (
                2,
                0x00_00_05,
                "Last",
                shifted(&north(37.70 + 3000.0 / 111_320.0, -122.40, 100.0, 31), -8.0),
            ),
        ]
    }

    /// Two lines closing at a shallow angle: one runs 4 km north, the other comes in from a
    /// hundred metres west over the first kilometre of it and then runs alongside. They are
    /// one corridor from wherever they first come within [`CORRIDOR_M`], which is a long way
    /// before either could draw the other's geometry without it showing.
    fn a_shallow_merge() -> Vec<Fixture> {
        let main = north(37.70, -122.40, 100.0, 41);
        let cos_lat = 37.70_f64.to_radians().cos();
        let east = |m: f64| (m / (111_320.0 * cos_lat) * 1e7) as i32;
        let joining: Vec<(i32, i32)> = main
            .iter()
            .enumerate()
            .map(|(i, &(lat, lon))| {
                let closed = (i as f64 * 100.0 / 1000.0).min(1.0);
                (lat, lon + east(8.0 - 108.0 * (1.0 - closed)))
            })
            .collect();
        vec![(0u32, 0x00_00_10u32, "Main", main), (1, 0x00_00_20, "Join", joining)]
    }

    /// The corridor a polyline would be the reference of, for measuring the output against.
    fn corridor_of(points: &[(i32, i32)]) -> Corridor {
        let points = canonical(points);
        let cum = cumulative(&points);
        Corridor { points, cum, colours: Vec::new() }
    }

    /// `n` services sharing `metres` of trunk running north and then leaving it in
    /// different directions for 20 km.
    fn trunk_then_branches(metres: f64, n: usize) -> Vec<Fixture> {
        let steps = (metres / 100.0).round() as usize + 1;
        let trunk = north(37.70, -122.40, 100.0, steps);
        let top = *trunk.last().expect("a trunk");
        let cos_lat = (top.0 as f64 * 1e-7).to_radians().cos();
        let east = |m: f64| (m / (111_320.0 * cos_lat) * 1e7) as i32;
        let up = |m: f64| (m / 111_320.0 * 1e7) as i32;
        let branch = |dx: f64, dy: f64| -> Vec<(i32, i32)> {
            let mut line = trunk.clone();
            for i in 1..=200 {
                line.push((top.0 + up(dy * i as f64), top.1 + east(dx * i as f64)));
            }
            line
        };
        let away = [(100.0, 0.0), (0.0, 100.0), (-100.0, 0.0), (70.0, 70.0)];
        let names = ["A", "B", "C", "D"];
        (0..n)
            .map(|i| {
                let (dx, dy) = away[i];
                (i as u32, 0x11u32 * (i as u32 + 1), names[i], branch(dx, dy))
            })
            .collect()
    }

    /// A second copy of a line adds nothing to the map, whichever way round it is drawn.
    /// THE case: a feed publishes each direction of a service as its own shape, and a
    /// regional feed republishes the lot re-surveyed a few metres off.
    #[test]
    fn track_already_drawn_is_not_carried_again() {
        let line = north(37.7, -122.4, 100.0, 60);
        let mut covered = Covered::default();
        assert!(!covered.contains(&line), "the first copy is all new");
        covered.add(&line);
        assert!(covered.contains(&line), "the same line again");
        let reversed: Vec<(i32, i32)> = line.iter().rev().copied().collect();
        assert!(covered.contains(&reversed), "the other direction");
        assert!(covered.contains(&shifted(&line, 9.0)), "the adjacent track");
        // A short-turn lies on its parent for its whole length, so it adds nothing either.
        assert!(covered.contains(&north(37.7, -122.4, 100.0, 30)), "a short-turn");
    }

    /// How many distinct services already draw over a stretch of track.
    ///
    /// The colour-scoped gates let two services share a track on purpose, and that is right for a
    /// city. On a planet one alignment is republished by a city feed, the regional feed containing
    /// it and a national feed on top, each under its own colour, and every one of them claims a
    /// lane — which is what draws one railway as fifteen jagged parallel lines.
    #[test]
    fn crowd_counts_the_distinct_services_over_a_track() {
        let line = north(37.7, -122.4, 100.0, 60);
        let mut covered = Covered::default();
        assert_eq!(covered.crowd(&line), 0, "empty track carries nobody");

        covered.add_tagged(&line, 0xE31E24);
        assert_eq!(covered.crowd(&line), 1);
        // A second service on the same track is real and must be counted, not merged.
        covered.add_tagged(&line, 0x0054A5);
        assert_eq!(covered.crowd(&line), 2);
        // The same service again is not a third.
        covered.add_tagged(&line, 0xE31E24);
        assert_eq!(covered.crowd(&line), 2, "one service counted twice");
        // Slightly off, still the same corridor.
        covered.add_tagged(&shifted(&line, 9.0), 0x00A650);
        assert_eq!(covered.crowd(&line), 3, "a re-survey a few metres off is the same track");
        // Track nobody has drawn is uncrowded however busy its neighbour is.
        assert_eq!(covered.crowd(&north(37.9, -122.9, 100.0, 60)), 0, "elsewhere");
    }

    /// The worst point along the line, not the average: a branch joining a busy trunk for part of
    /// its length is exactly the line worth suppressing.
    #[test]
    fn crowd_reports_the_busiest_point_not_the_whole_line() {
        let trunk = north(37.7, -122.4, 100.0, 30);
        let longer = north(37.7, -122.4, 100.0, 60);
        let mut covered = Covered::default();
        for colour in [1u32, 2, 3] {
            covered.add_tagged(&trunk, colour);
        }
        assert_eq!(
            covered.crowd(&longer),
            3,
            "a line overlapping a crowded trunk for half its length is crowded"
        );
    }

    /// Survey noise is not new track. Two agencies' surveys of one track disagree by a few
    /// metres, and a wobble inside the tolerance must not resurrect a duplicate line.
    #[test]
    fn a_wobble_inside_the_tolerance_is_the_same_track() {
        let straight = north(37.7, -122.4, 100.0, 60);
        let mut covered = Covered::default();
        covered.add(&straight);
        let cos_lat = (straight[0].0 as f64 * 1e-7).to_radians().cos();
        let east = |m: f64| (m / (111_320.0 * cos_lat) * 1e7) as i32;
        let wobbled: Vec<(i32, i32)> = straight
            .iter()
            .enumerate()
            .map(|(i, &(lat, lon))| (lat, lon + east(if i % 2 == 0 { 9.0 } else { -9.0 })))
            .collect();
        assert!(covered.contains(&wobbled), "9 m of survey drift is the same track");
    }

    /// A branch adds track, so it is carried **whole** rather than trimmed to the part that
    /// diverges. Trimming was tried and is wrong: it leaves a route in fragments, and a
    /// stretch too short to be worth emitting leaves a hole that nothing else draws. The
    /// trunk it shares is over-drawn in its own colour at its own offset, which is invisible.
    #[test]
    fn a_branch_adds_track_and_is_carried_whole() {
        let trunk = north(37.7, -122.4, 100.0, 60);
        let mut covered = Covered::default();
        covered.add(&trunk);
        let mut branch = north(37.7, -122.4, 100.0, 30);
        let split_at = *branch.last().unwrap();
        let cos_lat = (split_at.0 as f64 * 1e-7).to_radians().cos();
        for i in 1..=20 {
            branch.push((
                split_at.0,
                split_at.1 + (i as f64 * 100.0 / (111_320.0 * cos_lat) * 1e7) as i32,
            ));
        }
        assert!(!covered.contains(&branch), "it leaves the trunk, so it is not redundant");
    }

    #[test]
    fn a_different_alignment_is_all_new_track() {
        let line = north(37.7, -122.4, 100.0, 60);
        let mut covered = Covered::default();
        covered.add(&line);
        assert!(!covered.contains(&shifted(&line, 1000.0)));
    }

    #[test]
    fn a_line_with_fewer_than_two_points_covers_nothing() {
        let line = north(37.7, -122.4, 100.0, 10);
        let covered = Covered::default();
        assert!(!covered.contains(&line[..1]));
        assert!(!covered.contains(&[]));
    }

    /// The two halves of the slicing primitive: measuring along a polyline and cutting it
    /// between two of those measurements, with both ends landing exactly on the line.
    #[test]
    fn a_slice_of_a_polyline_lands_exactly_on_it_at_both_ends() {
        let line = north(37.7, -122.4, 100.0, 11);
        let cum = cumulative(&line);
        assert!((cum[10] - 1000.0).abs() < 1.0, "{cum:?}");
        assert_eq!(slice_between(&line, &cum, 0.0, cum[10]), line, "the whole thing");
        let middle = slice_between(&line, &cum, 250.0, 750.0);
        assert_eq!(middle.len(), 7, "both cut ends plus the five vertices between");
        assert!(distance_m(middle[0], line[2]) < 60.0, "a quarter of the way along");
        // Two adjacent slices meet exactly, which is what keeps a route's pieces joined.
        let before = slice_between(&line, &cum, 0.0, 250.0);
        assert_eq!(before.last(), middle.first());
        // Nothing between the two distances is nothing at all.
        assert!(slice_between(&line, &cum, 400.0, 400.0).is_empty());
    }

    #[test]
    fn nothing_in_nothing_out() {
        assert!(assign(&[]).is_empty());
    }
}
