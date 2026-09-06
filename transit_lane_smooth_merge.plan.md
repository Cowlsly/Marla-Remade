# Merges that stay fanned, and a handover that waits until the tracks are close

## Context

The approach-ordered lane work landed and the ordinals are now right: at Civic Center BART's
four and Muni's six form contiguous blocks in the shared corridor of ten instead of
interleaving by colour value. What is still wrong is the *transition*. On the device, at every
merge point the lines collapse onto one another, snap sideways, and only then does the fan open
up again.

Three separate mechanisms produce that, and they are independent of each other and of the
ordering work.

**1. Corridors that abut each other still taper to nothing between them.** `bundle.rs:611-614`
decides whether to ease purely from whether a run has a neighbour at all:

```rust
let entering = fanned && at > 0;
let leaving  = fanned && at + 1 < runs.len();
```

At Civic Center the Muni-6 corridor runs *straight into* the shared-10 with no solo track
between them — `a_route_passing_from_one_corridor_into_the_next_changes_lane_where_they_meet`
pins exactly that shape. So every line eases out of its old lane down to 20% of it, crosses the
seam, and eases back out into its new lane. All ten converge on the centreline and re-open. This
is the dominant artefact and it is what "they all go to a single point" describes. A taper is
for easing on and off a line's *own* alignment; between two corridors the line is in a lane on
both sides and there is nothing to ease onto.

**2. The handover happens at 30 m.** `CORRIDOR_M = 30.0` decides membership *and*, because
`lo`/`hi` come from `distance_along` of the run's first and last sample (`bundle.rs:589-591`),
where the member stops drawing its own survey. Two lines closing at a shallow angle become one
corridor while still 30 m apart, and the whole 30 m is taken up in a single step at the mouth.

**3. The ease itself is cut from the reference, not from the member's own track.** `cut` at
`bundle.rs:617-620` slices `corridor.points` for the taper pieces as well as the body. So the
member is already on the shared polyline throughout the ease — every member on the same
polyline, at 20% of their offsets — which is the pinch, and `spans_of`'s weld
(`bundle.rs:660-673`) then pulls each member's approach onto the corridor's single entry point.

**Decisions taken** (do not re-open):

- Two abutting corridors: **no taper at all**. The line steps straight from its old lane to its
  new one. The lateral step is the difference between two lane offsets, which is small now that
  both corridors order by approach side.
- The handover waits until the member's own survey is within **~8 m** of the reference, with the
  ease drawn on its own track up to that point. Membership still forms at 30 m so corridors do
  not fragment.
- The ease goes to **8 steps**. Not a per-vertex ramp.

## Approach

**A taper means one thing: opening the fan off the member's own alignment.** Everywhere else it
is wrong. That single reframing covers points 1 and 3 — where the neighbour is another corridor
there is no own alignment to leave, so no taper; where the neighbour *is* own geometry, the ease
belongs on that own geometry rather than on the reference. Both fall out of asking "what is on
the other side of this run boundary" instead of "is there a run boundary".

**The snap point is where the survey gets close, not where membership forms.** Keeping
`CORRIDOR_M` for membership and introducing a separate, much tighter `SNAP_M` for the handover
is what separates "these two are the same corridor" from "these two are now close enough that
drawing one on the other's geometry is invisible". Those are genuinely different judgements and
have been sharing a constant. The ease then runs from the run's start to the snap point, which
makes it *adaptive*: a shallow, slow convergence gets a long ease, a sharp junction gets a short
one, and in both cases the geometry step at the end is bounded by `SNAP_M` rather than by
`CORRIDOR_M`.

**The staircase is structural and 8 steps is the affordable end of it.** `taper` is a per-draw
value: `tile/geometry.rs:202-223` keys sub-meshes on `(color, ordinal, lanes, taper)` and
`renderer.rs:683-766` turns each one into its own buffer pair and `vkCmdDrawIndexed`. A
continuous ramp would need a per-vertex attribute, and there is no room — the line vertex is 7
floats with every one consumed (`tess/stroke.rs:37`) on the single pipeline every road, water
and boundary layer shares, the `.mamaps` feature record is fixed at 24 bytes with one spare byte
(`tilecodec/.../body.rs:53-58`), and `tile_build`'s clip and simplify only move `(i32, i32)`
pairs. Worth noting: `taper_fraction` is *already* an equal-step ramp — `255 * step / (STEPS+1)`
gives 51, 102, 153, 204, 255, so every jump including the two at the ends is exactly 255/(N+1).
There is nothing to fix about the endpoints; the 20% jump is only because N is 4. N = 8 makes
every jump 11%, about 1 Dp at a 9 Dp offset.

**The ease pieces must still be stored in the reference's direction.** `stroke::band`
(`stroke.rs:101-126`) takes the normal from the polyline's own direction, which is why corridor
pieces are cut in the reference's order today and only the *listing* order follows the candidate
(`bundle.rs:640-645`). An ease piece cut from the member's own survey inherits that survey's
direction, so for any member travelling against the reference its points have to be reversed or
the fan mirrors across the ease. This is the one non-obvious correctness requirement in the
change.

## Implementation phases

### Phase 1 — decide placement for every run before emitting any of it

`scripts/maps/gtfs_ingest/src/bundle.rs`, `spans_of`:

- Split the single loop into two passes. The first resolves, per run, whether it is placed on a
  corridor and with what `(corridor, forward, lo, hi)` — the `placed` computation that is
  currently inline at `bundle.rs:588-593`. The second emits pieces.
- This is what lets a run see whether its *neighbours* are corridor runs. Testing
  `corridors.contains_key(&run.set)` is not enough on its own: a run whose two ends project to
  one place on the reference fails the `hi - lo >= SAMPLE_M` check and draws its own geometry
  despite being a corridor set, and treating that as "abuts a corridor" would leave a step.

### Phase 2 — no taper between two corridors

`bundle.rs`:

- `entering` becomes "there is a previous run and it is *not* placed on a corridor"; `leaving`
  likewise for the next run. A run at the start or end of the line keeps no taper, as today.
- The body then runs to the run boundary on the reference at that end, and the existing
  inter-run weld (`bundle.rs:660-673`) bridges the two references across the seam.

### Phase 3 — hand over at `SNAP_M`, and ease on the member's own track

`bundle.rs`:

- Add `SNAP_M` (~8 m) beside `CORRIDOR_M`, documented as the other half of that judgement: 30 m
  is "these are one corridor", 8 m is "close enough that drawing one on the other's geometry
  cannot be seen". Above the two-agencies-disagree-by-a-metre-or-two figure the module header
  already cites, well below `CORRIDOR_M`.
- Where a run enters from own geometry, walk its samples in from that end to the first whose
  distance to the reference is within `SNAP_M` — reuse `project` for the distance, the same
  nearest-segment walk `distance_along` and `signed_offset` already do. That sample's own
  distance is the snap point. Mirror it at the far end where the run leaves to own geometry.
- The ease spans the run's start to the snap point, but at least `TAPER_M` so the lane offset
  has somewhere to ramp even when the member converges immediately, and capped so the body keeps
  a usable share of the run. Cut its `TAPER_STEPS` pieces from `candidate.points` via
  `slice_between` over `own_cum`, not from `corridor.points`.
- Reverse each ease piece's points when the candidate runs against the reference, per the
  constraint above.
- Bridge ease→body by carrying the body's first point onto the end of the last ease piece — the
  same one-vertex trick the inter-run weld uses, and now a step of at most `SNAP_M` instead of
  `CORRIDOR_M`. The ease pieces and the own-geometry span before them are adjoining slices of
  one polyline and already share a vertex exactly, so nothing is needed there.
- A member that never comes within `SNAP_M` of the reference anywhere in the run keeps its own
  geometry across the whole run, fanned. It loses the exactly-parallel property, which it was
  never going to get honestly at that separation.

### Phase 4 — finer ease

`bundle.rs`: `TAPER_STEPS` 4 → 8. `taper_fraction` is unchanged — it is already equal-stepped.

Worst case per tile becomes `distinct colours × 9` transit draws, about 90 for a Market Street
tile against the ~600 the renderer budgets for (`vulkan/pipeline.rs:14-18`). Nothing in the
renderer or the record format changes.

## Risks

- **A long slow convergence makes a long ease and a short body.** Two lines closing over a
  kilometre now draw their own surveys for most of it. That is the intent, but it means the
  "every member draws the reference so they are exactly parallel" guarantee holds over less of
  the map. The cap on ease length is the lever; it needs a value picked with the Market Street
  and Oakland tiles in front of you rather than in the abstract.
- **`SNAP_M` interacts with the approach ordering.** `approach_offset` measures the side over
  the 150 m before the *run* starts. The run boundary does not move, so the ordering is
  unaffected — but the ease now covers ground the ordering treated as "inside the corridor", so
  the two windows overlap where convergence is slow. Worth confirming the Market Street ordinals
  are unchanged after this pass.
- **No taper at a corridor seam is a visible step**, just a much smaller one than the dip through
  zero. It is bounded by the difference between the two lane offsets, so it is only small while
  the approach ordering keeps abutting corridors in agreement — which it does not fully manage at
  Civic Center today (Muni's internal order is not preserved across that seam, and BART's Yellow
  sits on the far side). Those two are the plan's own documented risk #2 from the previous pass
  and are *not* fixed here; this change will make them more visible rather than less.
- **Draw count doubles for the transit layer.** Within budget on the tiles measured, unmeasured
  on a dense tile with many corridors. Worth a frame-time check at Market St z17 rather than
  trusting the estimate.
- **Line endings.** `bundle.rs` is large, hand-authored and pure LF; check `git diff --stat`
  against `--ignore-cr-at-eol` per the repo rule.

## Critical files

| Path | Change |
|---|---|
| `scripts/maps/gtfs_ingest/src/bundle.rs` | two-pass `spans_of`; no taper between corridors; `SNAP_M` handover; ease cut from the member's own survey and stored in the reference's direction; `TAPER_STEPS` 4 → 8 |

Nothing in `library/map` changes. The record format, the sub-mesh key, `LayerMesh::lane`,
`Layer::lane_offset_px` and `shaders/line.vert` are all untouched — this pass only changes which
polyline a taper piece is cut from, how long the ease is, and whether there is one at all.

## Verification

```
cd scripts/maps/gtfs_ingest && cargo test
cd library/map/src/main/rust && cargo test
cargo check --target aarch64-linux-android
./gradlew :library:map:testDebugUnitTest :library:map:lint :mapcompare:lint checkMetadata
```

New unit coverage in `bundle.rs`:

- **Two abutting corridors emit no taper.** Using `back_to_back_corridors`, every span of the
  through route reports `taper == 255`; the lane steps straight from one corridor's ordinal to
  the other's.
- **A shallow approach draws its own track until it is close.** Two lines converging at a slow
  angle over a kilometre and then running together: the joining line's spans stay on its own
  survey until the surveys are within `SNAP_M`, and the first point of its first
  reference-geometry span is within `SNAP_M` of the last point of the span before it.
- **The ease is on the member's own geometry.** In `trunk_then_branches`, a tapered span of a
  non-reference member is *not* a slice of the reference.
- **An ease against the reference's direction does not mirror.** Feeding a corridor with a
  member's polyline reversed yields identical spans, tapers included — the existing
  `the_direction_an_input_is_stored_in_does_not_reach_the_output` extended to a fixture that
  actually tapers.
- `the_spans_of_one_route_share_their_boundary_vertex` must still hold across the new
  ease→body bridge; extend its fixture list.
- `a_route_tapers_into_its_lane_at_the_end_of_a_corridor` needs its step count updated and
  should assert the ease reaches 255 with no jump into the body.

Then rebuild to a **new archive name** — the app caches by URL — and check with
`mamaps_dump --mode rings --layer transit`:

- Market Street (`14/2620/6332`): ten distinct ordinals of ten, unchanged from
  `usw_approach.mamaps`, confirming the ordering was not disturbed.
- The same tile's taper values: no piece below 255 on any span whose neighbours are both
  corridors.

Inputs for the rebuild are in `C:\Users\Vayun\Documents\code\maps-work` (a sibling of the repo):

```
transit_shapes .\corridor\usw_transit_routes.<name>.geojsonseq --manifest .\transit\usw_feeds.manifest
mamaps_build --input .\us-west-latest.osm.pbf --out .\corridor\usw_<name>.mamaps
    --min-zoom 0 --max-zoom 14
    --coastline .\land_polygons\land-polygons-split-4326\land_polygons.shp
    --transit-routes .\corridor\usw_transit_routes.<name>.geojsonseq
    --report .\corridor\usw_<name>.mamaps.report.json
```

Omitting `--coastline` silently drops the mainland and makes the comparison useless. The range
server on port 8000 already serves that directory.

On device, scoped to the emulator so the attached Pixel is untouched
(`$env:ANDROID_SERIAL = "emulator-5554"`):

```
./gradlew :mapcompare:installDev -PemulatorAbi=x86_64
adb -s emulator-5554 shell am start -n com.vayunmathur.mapcompare/.MainActivity \
    --es archive_path http://10.0.2.2:8000/<new_name>.mamaps
```

Enable the Transit chip, then use `Market St z17` and `Market St z13` and follow a single colour
through Civic Center: it should hold its side of the fan across the seam without the group
collapsing to the centreline, and the step where it changes lane should be a lane's width rather
than the full fan. `Oakland z16` and `San Jose z16` are the shallower merges where `SNAP_M`
should show.
