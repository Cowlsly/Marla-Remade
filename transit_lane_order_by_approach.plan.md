# Lane order that follows the tracks, so transit lines never cross

## Context

The zoom-varying lane work landed and is otherwise ready to ship. The remaining defect is that
lines **change relative order** wherever corridor membership changes: two groups that merge
interleave, and a line can jump across the corridor at a junction rather than settling beside
the neighbours it arrived with.

The intent is that relative position is preserved — within the group a line arrived with, and
between two groups that join. A route coming from the north and one coming from the south that
then run west together must stay north-of and south-of each other, not swap.

Three independent things cause the crossings. All three have to go.

**1. The ordinal is the colour's index in a colour-sorted list.** `bundle.rs:379-382` does
`colours.sort_unstable()`, so the lane order is the numeric order of `0xRRGGBB`. That has no
relation to where a line physically runs. Two groups merging interleave by colour value, which is
exactly the crossing being reported.

**2. The reference polyline's stored direction is arbitrary.** The lane side is measured along the
reference, and the reference is whichever member wins the `(colour, name)` tiebreak in
`corridors_of` (`bundle.rs:353-372`). When a lower-coloured route joins and becomes the new
reference, the new reference may be stored in the opposite direction — which **mirrors the whole
fan**. That is a crossing even when the order is otherwise right, and it is invisible to any
amount of ordering work.

**3. The renderer wraps.** `Layer::lane_offset_px` (`style/mod.rs:468`) does `ordinal % lanes`.
Wrap is not monotonic, so wherever a corridor carries more colours than the zoom allows lanes,
adjacent corridors permute. The previous plan accepted this as a known risk; it is not compatible
with "no crossings".

**Decisions taken** (these close the design questions; do not re-open them):

- The side signal is measured on each member's **approach** — the stretch of its own surveyed
  polyline immediately outside the corridor mouth — not across the shared stretch.
- The renderer **squashes** instead of wrapping: `lane = ordinal * lanes / count`.

## Approach

**The original survey is still available where the emitted geometry is not.** Inside a corridor
every member emits the corridor's reference polyline (`spans_of` slices `corridor.points` for all
of them), so the *exported* geometry carries no information about which member is which. But
`corridors_of` holds `candidates[at].points`, the untouched survey, and two agencies' surveys of
one track differ by a metre or two — which is exactly the signal needed. This is what makes
"preserve the order within the group" achievable without building a corridor adjacency graph and
propagating orders through it: the geometry already encodes the arrangement, at every corridor,
independently.

**The approach is the strong end of that signal.** Across the shared stretch two routes on one
track sit within a couple of metres of the reference and the sign is noise. A hundred metres
before the mouth they are still diverging, tens or hundreds of metres apart, and the sign is
unambiguous. Measuring there is what separates "came from the north" from "came from the south"
for routes that then run over one identical alignment.

**The sign convention is fixed and checkable.** Tile Y increases *southward*
(`tile_build/src/geom.rs:212`), `tess/stroke.rs` emits `(0, +1)` as the normal for an eastward
segment, and `line.vert:60` shifts by `normal * lateralPx`. So positive `lateralPx` is the
**right-hand side of travel** in an (east, north) frame. `Layer::lane_offset_px` gives lane 0 the
most negative offset, so **lane 0 is the left-hand side of the reference's direction of travel** —
the west side of a northbound corridor, the south side of a westbound one. Ordinals therefore sort
by **ascending** signed offset, where the offset of a point `d` (east, north) from the reference
with unit tangent `(ux, uy)` is `d.east * uy - d.north * ux`. `directed()` (`bundle.rs:627`)
already produces tangents in that frame, so there is nothing new to derive.

**Canonical orientation removes the mirror.** Rather than inheriting the direction of whichever
member won a name tiebreak, the reference is folded into a fixed half-plane: flip it if its
first-to-last chord points south, or points exactly along a parallel and westward. Abutting
corridors run roughly parallel where they meet, so they fold the same way and the fan does not
mirror across the seam. This is the same "fold to `[0, 180)`" idea `COS_FOLD` already uses for
parallelism.

**Squash cannot re-order.** `lane = ordinal * lanes / count` is monotonic non-decreasing in
`ordinal`, so two colours can share a lane but can never swap. That is what makes "no crossings"
hold at *every* zoom rather than only where the corridor fits the lane budget. The cost is the
intended one and is already the accepted trade at low zoom: colours merge onto a lane and the ones
underneath are hidden.

## Implementation phases

### Phase 1 — orient the reference

`scripts/maps/gtfs_ingest/src/bundle.rs`:

- `Corridor::points` becomes an owned `Vec<(i32, i32)>` rather than a `&'a [(i32, i32)]`, because
  a flipped reference is a new sequence. One copy of one polyline per corridor; corridors are few.
  This drops the `'a` lifetime from `Corridor` and from `corridors_of`'s return type.
- Add a helper that folds a polyline into the canonical half-plane: flip when the chord from first
  to last vertex runs south, or runs exactly east-west and westward. Apply it to the picked
  reference before `cumulative` is taken over it.
- `spans_of` needs no change: it already derives `forward` per run from `distance_along` and
  reverses its output when the candidate runs against the reference.

### Phase 2 — order by the approach

`scripts/maps/gtfs_ingest/src/bundle.rs`:

- Add a signed-offset helper beside `distance_along` (`bundle.rs:595`). It walks the reference's
  segments exactly as `distance_along` does, keeps the nearest one, and returns
  `d.east * uy - d.north * ux` against that segment's unit tangent. `project` (`shapes.rs:40`)
  returns an unsigned distance and no side, so the sign has to be computed here; reuse `project`
  for the nearest-segment search and take the sign separately.
- Add a function that returns one member's approach offset. For candidate `at`'s run in this
  corridor it takes the stretch of `candidates[at].points` immediately **before** the run starts,
  over a new `APPROACH_M` (~150 m, above `TAPER_M` so it clears the ease-in and below
  `MIN_SHARED_M` so it cannot reach the previous corridor's body). Sample a handful of points along
  it, take the signed offset of each against the reference, and average.
  - A run that starts at the line's own start has no approach; use the stretch immediately
    **after** the run's end instead.
  - A candidate wholly inside the corridor has neither; fall back to the mean signed offset across
    the run itself, which is the weaker but still correct signal.
  - The run's own-distance bounds come from `probe_cum[at][run.from]` and `[run.to]`, so
    `corridors_of` gains `probe_cum` as a parameter. It is already built in `assign`
    (`bundle.rs:184-194`).
- The lane order is per **colour**, not per route, so average the member offsets of each colour.
  Sort the corridor's distinct colours by `(offset, colour, name)`. The `(colour, name)` tail keeps
  the output byte-identical between runs when two routes are genuinely coincident and their offsets
  tie — the determinism rule at `bundle.rs:55-57`.
- `colours.sort_unstable()` and its "ascending" comment go; `Corridor::colours` is now ordered by
  position across the corridor, and its doc should say so.
- The reference pick in `corridors_of` stays on `(colour, name)` then length. It only chooses whose
  *geometry* is drawn; it no longer decides the direction (Phase 1) or the order (Phase 2).

### Phase 3 — squash instead of wrap

`library/map/src/main/rust/src/style/mod.rs`:

- `lane_offset_px`: `let lane = ordinal as i32 * lanes / count as i32;` replacing
  `ordinal as i32 % lanes`. `lanes` is already `min(style, count)` and `ordinal < count`, so the
  result stays inside `0..lanes`.
- Update the doc comment: past the zoom's lane count colours **share** a lane, in order, rather
  than wrapping onto it.
- `a_corridor_fans_out_centred_on_the_track_it_shares` asserts the wrap
  (`of(9.0, 2, 4) == of(9.0, 0, 4)`). Under squash ordinals 0,1 take lane 0 and 2,3 take lane 1, so
  that assertion inverts.

Nothing else in the renderer changes. The record format, the sub-mesh key, `LayerMesh::lane`,
`shaders/line.vert` and `basemap.flat.json` are all untouched — this pass only changes *which
number* the exporter writes into `transit_ordinal` and how the renderer maps it to a lane.

### Phase 4 — rebuild and verify on device

Re-run `transit_shapes` over `maps-work/transit/usw_feeds.manifest` and rebuild to a **new archive
name** (the app caches by URL). The previous run's inputs are
`maps-work/us-west-latest.osm.pbf` plus
`--coastline maps-work/land_polygons/land-polygons-split-4326/land_polygons.shp`; omitting the
coastline silently drops the mainland and makes the comparison useless.

## Risks

- **A corridor that curves through more than a right angle** can fold to a different canonical
  direction than the corridor it abuts, mirroring the fan at that seam. The chord is a whole-corridor
  average and the junction is local. Rarer and strictly better than today's tiebreak-derived
  direction, but not eliminated — worth checking anywhere a route turns hard between two shared
  stretches.
- **The approach window can miss.** A route that enters a corridor straight out of another corridor
  has an approach 150 m long that is still inside the previous shared stretch, where its own survey
  is close to that corridor's members rather than diverging. The signal is weaker there, though
  still correctly signed as long as the surveys are distinct.
- **Genuinely coincident surveys still order by colour.** Two routes published on one identical
  shape have a zero offset everywhere and fall to the `(colour, name)` tail. They are drawn on top
  of each other so nothing visibly crosses *in* the corridor, but the approach into it can still
  cross. Unfixable from geometry alone — there is no ground truth to recover.
- **Squash hides more at low zoom than wrap did.** Wrap spread ten colours over two lanes in an
  interleaved pattern; squash puts ordinals 0-4 on one lane and 5-9 on the other. The same number of
  colours are hidden, but *which* ones changes, and they are now hidden in contiguous blocks.
- **`Corridor` gaining an owned `Vec`** removes its lifetime parameter. Small signature churn
  through `corridors_of` and `spans_of`'s `&BTreeMap<u32, Corridor>`.
- **Line endings.** `bundle.rs` is large and hand-authored; check `git diff --stat` against
  `--ignore-cr-at-eol` per the repo rule.

## Critical files

| Path | Change |
|---|---|
| `scripts/maps/gtfs_ingest/src/bundle.rs` | canonical reference orientation; signed-offset and approach helpers; colours ordered by position |
| `library/map/src/main/rust/src/style/mod.rs` | squash replaces wrap in `lane_offset_px` |
| `scripts/maps/gtfs_ingest/src/bin/transit_shapes.rs` | no logic change; test expectations on emitted ordinals |

## Verification

```
cd scripts/maps/gtfs_ingest && cargo test
cd library/map/src/main/rust && cargo test
cargo check --target aarch64-linux-android
./gradlew :library:map:testDebugUnitTest :library:map:lint :mapcompare:lint checkMetadata
```

Unit coverage worth writing, in `bundle.rs`:

- **Two lines merging from opposite sides.** One approaching from the north and one from the south,
  running west together: the southern approach takes ordinal 0, because lane 0 is the left of the
  reference's travel and a westbound reference has south on its left.
- **Reversing an input does not move anyone.** Feeding the same corridor with the reference
  candidate's polyline reversed yields identical ordinals — the canonical fold, and the guard
  against the mirror.
- **Two groups joining keep their blocks.** Two pairs that share a corridor each, then all four
  sharing one: each pair stays contiguous in the merged order and keeps its internal order.
- **A lone line joining a group** lands on the outside of the group it joins from, not in the middle
  of it.
- The existing `the_order_within_a_corridor_is_colour_then_name` no longer describes the rule and
  becomes a *tie-break* test: two coincident surveys, ordered by colour then name.

In `style/mod.rs`:

- **Squash is monotonic.** For every `count` in 1..=12, every zoom at each lane step, and every
  ordinal, `lane_offset_px` is non-decreasing in `ordinal`. This is the property that means "no
  crossings" and is worth pinning directly rather than by example.

Then, against the rebuilt archive with `mamaps_dump --mode rings --layer transit`:

- Market Street (`14/2620/6332`) — still ten distinct ordinals, every feature reporting count 10.
- A tile spanning a junction where a corridor's count changes: the colours common to both sides keep
  their relative ordinal order across the seam.

On device against a **new archive name** over the local range server (port 8000):

```
./gradlew :mapcompare:installDev -PemulatorAbi=x86_64
adb -s emulator-5554 shell am start -n com.vayunmathur.mapcompare/.MainActivity \
    --es archive_path http://10.0.2.2:8000/<new_name>.mamaps
```

Using the `Market St z13`/`z17` and `Oakland z16` presets, follow a single colour through a junction
and confirm it stays on the same side of the neighbours it arrived with — and that where two groups
join, one group is wholly on one side of the other rather than interleaved.

Note: the emulator ANR'd during the last device pass and needs restarting before this one.
