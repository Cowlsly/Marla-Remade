# P11 — Offline public-transit routing: feasibility spike

**Status:** spike / report for lead review. **No app behavior changed.** No large
artifacts committed. This document is the deliverable.

> **POST-BUILD UPDATE (2026-08-17, added after the spike).** P11 has since been
> **built** by other teammates, adopting this spike's recommendations. For anyone
> reviewing: the body below reflects the *pre-build* state; the following commits
> supersede parts of it. This report is retained as the design rationale of record.
>
> - `d52a70e36` **P11b** — on-device **RAPTOR transit planner** `maps/src/main/rust/src/transit.rs` + JNI (matches §3a RAPTOR / §3b Rust). Its header cites "per the spike's recommendation" and decouples access/egress from the road graph "per spike §1d gap 1."
> - `bb4c8ab98` **P11d** — wired offline transit routing + **online Transitous fallback** (matches §5 Phase 5).
> - `a37b85be2` **P16** — generator now **emits a single global routing graph (drops zones)**, which **resolves the §1d gap 1** producer/consumer format mismatch. `c1c13302b` adds `OfflineRouter.reload()`; `734feb70e` fixes a graph-build race.
> - **One deviation from the recommendation:** the GTFS ingest host tool was built as **`scripts/maps/gtfs_ingest` (Rust, P11a)** rather than the Python I suggested in §3c. This still honors the "NO hand-written C++" rule and the "Rust preferred" steer, so the deviation is benign; the §3c *decision* (don't extend `generator.cpp` in C++) was followed.
>
> Net: the spike verdict (VIABLE) held, and the build tracked the plan. Consequently
> the §1d gaps and several "blockers" in §4 are now addressed in code.

**Scope of the spike (from task #144 / `VELA_PORT_PLAN.md` P11):** investigate
offline transit routing from the SAME Transitous/GTFS data as the P10 boards —
ingest per-region GTFS into the downloadable offline packs + an on-device
journey planner (Rust preferred; **user rule: NO hand-written C++** for P11).
Report feasibility/size and the C++-vs-Rust/Python generator decision *before*
any build.

**Verdict up front: VIABLE, and better-supported than expected.** The on-device
Rust engine already has a working public-transit mode and an explicit
"which GTFS feeds are present offline" mechanism. The real missing pieces are a
GTFS **ingest/compaction stage** (recommend **Python**, not C++), a proper
per-region **transit index + planner** (recommend **RAPTOR in Rust**), and
**pack download/packaging wiring**. Two pre-existing gaps in the offline-pack
pipeline (below) must be acknowledged because P11 rides on top of them.

---

## 1) How offline packs + routing work today (assessment)

### 1a. The offline-pack generator (`scripts/maps/generator.cpp`, `run_generator.sh`)

`generator.cpp` is a libosmium, multi-threaded C++ tool that turns an `.osm.pbf`
into a routing graph. **Its transit/GTFS scaffolding is already substantial** —
this is the single most important finding for effort estimation:

- **Stop detection** (`:431-445`, `:563-582`): flags `highway=bus_stop|bus_station|tram_stop`,
  `railway=station|halt|tram_stop|stop`, `public_transport=stop_position|platform|station`
  as transit nodes; reads `gtfs:stop_code:<feed>` OSM tags to capture a **feed name**
  and **stop code** per node (`:536-560`).
- **Transit ways** (`is_transit_way`, `:348-355`): `railway=rail|subway|tram|light_rail`,
  `route=bus|train`.
- **Voyage input two ways** (`:388-404`, `:641-688`):
  - if a second CLI arg `transit.csv` is given, it reads pre-expanded connections
    `u_osm, v_osm, line_name, dep(HH:MM:SS), arr(HH:MM:SS)` and groups them into
    per-edge voyage lists;
  - **otherwise it *synthesizes* a 15-minute-frequency timetable** on transit ways
    (`:641-668`) — i.e. today's "transit" is fake, evenly-spaced departures.
- **Transfer edges** (`:747-761`) between co-located road and transit nodes (15 m),
  and **isolated-stop reconnection** (`:774-853`).
- **Output** (`:855-913`): per-zone binaries `nodes_zone_{0..63}.bin`,
  `edges_zone_{0..63}.bin`, `transit_voyages_zone_{0..63}.bin`, plus global
  `road_names.bin`, `metadata.bin` (64×u32 zone node counts). `run_generator.sh`
  compiles it (`g++ -O3`), runs it on `california-latest.osm.pbf` by default, and
  `aws s3 sync`s those `.bin`s to Cloudflare R2.

**What's missing at the generator level:** a real GTFS→timetable path. The
`transit.csv` it consumes is *already-flattened consecutive-stop connections keyed
by OSM node id* — nothing in the repo produces that from GTFS `stop_times/trips/
calendar`, and doing so requires matching GTFS stops → OSM node ids (fragile; only
works where `gtfs:stop_code:*` tags exist). The synthesized 15-min timetable is a
placeholder, not schedule-accurate routing.

### 1b. The on-device engine (`maps/src/main/rust/src/`, `OfflineRouter.kt`)

The Rust router is **already transit-aware** — it is not a plain road router:

- `graph.rs` loads a whole-world graph and, crucially, **`present_feeds: HashSet<String>`**
  — the set of GTFS feeds available offline. It reads `transit_voyages.bin` and
  `transit_attributes.bin` (per-node `stop_code_off`/`feed_name_off`).
- `routing.rs` runs a **time-dependent A\*** with a `PUBLIC_TRANSIT` mode
  (`graph.rs:15`): a second node "state" bit for boarded-vs-not, boarding/transfer
  gating at recognized stops (`:366-401`), and `reconstruct_path` already emits
  transit steps with feed name, boarding stop, alighting stop, stop count, and a
  6000-unit boarding penalty (`:479-520`).
- `geometry.rs:get_transit_edge_time_10ms` (`:112-190`) does **next-departure
  scheduling** over a voyage list and — key for offline — **degrades gracefully
  when a feed is not present offline**: `if !present_feeds.contains(feed) { return
  unscheduled voyage-0 duration }` (`:132-139`).
- `lib.rs` exposes JNI `init(basePath, presentFeeds[])` + `findRouteNative(...,
  mode, startTime)` and marshals steps into `OfflineRouter.RawStep` → the Kotlin
  `RouteService.Step{..., transitDetails}` the whole P4/P5 UI already renders.
- `OfflineRouter.kt:214-218` computes `presentFeeds` by listing **APK assets** for
  folders containing `routes.txt`, and points the graph loader at
  `getExternalFilesDir(null)`. `GTFSProvider.kt` also reads `routes.txt` **from
  assets** (route colors).

**Implication:** the app already ships GTFS feeds *as bundled APK assets* (whole
feeds) and already has a transit routing mode and UI. The engine was clearly
designed with offline GTFS in mind — `present_feeds` is exactly the "is this
region's transit available offline?" switch P11 needs.

### 1c. The downloadable-pack format + `DownloadedMapsPage`

- `DownloadedMapsPage.kt` renders a 64-cell Morton grid; `ZoneDownloadManager.kt`
  downloads **only** `https://data.vayunmathur.com/zone_{id}.pmtiles` (map tiles)
  and checks/deletes only that file. Download titles use a `"Map Zone $id ($part)"`
  convention — a **multi-part pack scheme that currently has exactly one part
  (`Map`)**.

### 1d. Two pre-existing gaps P11 inherits (flag to lead)

1. **Producer/consumer format mismatch (road graph).** `generator.cpp` writes
   *per-zone* files with a 28-byte `NodeMaster` (inline `spatial_id` + stop/feed
   offsets) and 8-byte voyages. `graph.rs` reads a *single merged* `nodes.bin`
   (16-byte `NodeMaster`, u64 `edge_ptr`, **no** inline stop codes), a separate
   `transit_attributes.bin`, an `intermediate.bin` geometry blob, and 4-byte
   `TransitVoyageCompact`. **No merge/compaction stage exists in the repo** — the
   merged format is a port of an old `native-lib.cpp` toolchain that isn't checked
   in. So `generator.cpp` output cannot currently be loaded by the shipping Rust
   router as-is.
2. **No download path for routing data.** `ZoneDownloadManager` fetches only
   `.pmtiles`; nothing delivers `nodes.bin`/GTFS to `getExternalFilesDir`. Today
   the only transit data the app actually uses offline is the **bundled-asset GTFS
   feeds**.

These are not blockers for P11's *design*, but they mean "add GTFS to the existing
pack pipeline" is really "define a transit pack + its producer + its download
path," partly greenfield rather than a small edit to `generator.cpp`.

---

## 2) GTFS source & size (per-region, offline-bundle)

**Source.** Transitous (the P10 board backend, `TransitousDataSource.kt` →
`api.transitous.org`, MOTIS) is a community aggregator of open GTFS/GTFS-RT. Two
ways to get bundle-able GTFS:

- **Preferred: the Transitous feed registry** (`github.com/public-transport/
  transitous`, `feeds/*.json`) — a per-region index pointing at each agency's
  **official GTFS `.zip`** source URL. Pulling the original per-region zips gives
  clean per-metro granularity and licensing traceability. This is the right
  granularity for offline packs.
- Not recommended as the bundle source: MOTIS itself (online routing engine) or
  the giant nightly *merged* planet feed (wrong granularity, huge).

**Static GTFS is enough** for offline (schedules). GTFS-RT (live delays) stays
online-only via the P10 board.

**Granularity & size (a metro).** GTFS is CSV text dominated by `stop_times.txt`
(80–95% of bytes). Rough per-metro figures:

| Metro (static GTFS) | Raw `.zip` | Unzipped text | `stop_times` share |
|---|---:|---:|---:|
| Mid-size metro | 5–20 MB | 30–120 MB | ~85% |
| Large metro (e.g. SF Bay 511, NYC MTA-ish) | 20–60 MB | 150–500 MB | ~90% |

**Compacted on-device index** (intern strings; per-route trip arrays; delta-coded
`stop_times`; `u16` minutes-since-service-start; service-day bitsets from
`calendar`/`calendar_dates`):

- **~2–15 MB per metro**, dominated by stop_times. This is *small* next to a
  `zone_*.pmtiles` (tens–hundreds of MB) and the road graph. Bundling transit per
  region is cheap.

---

## 3) Algorithm + language decisions

### 3a. RAPTOR vs CSA — **recommend RAPTOR** (CSA acceptable as a simpler v1)

| | CSA (Connection Scan) | RAPTOR |
|---|---|---|
| Data | one array of connections sorted by dep time | routes→trips→stop_times + stop→routes index |
| Impl complexity | **lowest**; the connection array *is* the on-disk pack | moderate |
| Query | linear scan dep→horizon; great per-metro, cache-friendly | round-based (K = #transfers); prunes fast |
| Multi-criteria | awkward | **native** Pareto over (arrival time, #transfers) |
| UX fit | earliest-arrival | earliest-arrival **+ fewest-transfers** options, à la Google/MOTIS |

**Recommendation: RAPTOR**, because the transit UI (`RouteService.Step`,
`transitDetails`, multiple route options) expects transfer-aware alternatives, and
RAPTOR gives the (time, transfers) Pareto set for free. **CSA is a legitimate
lower-effort v1** if we want to validate the data pipeline first — its on-disk form
is trivially the sorted connection array, and it can be swapped for RAPTOR later
behind the same JNI. Either way this **replaces the synthesized-voyage graph-A\***
for transit with a real timetable engine; the road graph A* stays for walking
access/egress/transfer legs.

### 3b. Engine language — **Rust** (`maps/src/main/rust`)

Same crate as the router, behind the existing `OfflineRouter` JNI. Reuses the mmap
loader pattern, the `present_feeds` switch, and the `RawStep`→`RouteService.Step`
marshaling that the UI already consumes. JVM/Kotlin is possible but would duplicate
the JNI boundary and lose the mmap/zero-copy story; Rust is the clear fit and the
user's stated preference.

### 3c. KEY DECISION — extend the C++ generator, or port GTFS ingest to Rust/Python?

**Recommendation: do the GTFS ingest in Python; do NOT add hand-written C++.**

- **Honors the P11 rule** ("NO hand-written C++") and the user's app-code rule
  (this is host build-tooling, but the rule signals "stop growing the C++").
- **GTFS parsing is I/O/string-heavy, not perf-critical** — it runs once on a build
  host, not on device. Python's `csv`/`zipfile` stdlib is ideal.
- **Precedent + test harness already exist:** `scripts/maps/normalize_{safety,
  maxspeed,admin}.py` + `test/test_normalize.py` with fixtures. A
  `normalize_gtfs.py` slots straight in and is unit-testable with a tiny fixture
  feed, exactly like the others.
- **Decouple transit from OSM node ids.** Rather than feeding `generator.cpp`'s
  `transit.csv` (which forces GTFS onto OSM node ids via fragile `gtfs:stop_code`
  matching and still writes the incompatible per-zone format), have Python emit a
  **self-contained per-region transit index** (its own stop ids), and link stops to
  the road graph only for access/egress by nearest-walk-node. This sidesteps the
  §1d pipeline gaps entirely for transit.

**Rejected alternative — extend `generator.cpp`'s existing `transit.csv` path:**
minimal *lines* changed, but (i) requires new C++ (rule violation), (ii) inherits
the OSM-node-id coupling and the broken per-zone→merged format gap, and (iii)
entangles timetable data with the road graph. Higher real risk for less capability.

The C++ generator stays as-is for the **road** graph; GTFS becomes an **additive
Python stage** producing a new pack file consumed by a new Rust transit index.

---

## 4) Size / performance feasibility + blockers

**Size:** +2–15 MB transit index per region — negligible beside the pmtiles/road
packs. **Query:** RAPTOR/CSA on a single metro is interactive — typically
~10–50 ms on device for a handful of rounds; index is mmap'd (few MB resident).
Feasible on-device without trouble.

**Blockers / risks to plan for:**

1. **Pipeline gaps (§1d):** need a real pack-assembly + download path. Transit can
   be a clean new pack part (`zone_*.transit` or feed-keyed), but the road-graph
   producer/consumer mismatch should be settled in parallel or the offline router
   won't load at all on freshly generated data.
2. **Zone (Morton) vs feed (metro) boundaries don't align.** GTFS feeds are
   per-agency/metro; the 64-zone grid is geographic. Package transit **per feed/
   region**, and map "which feeds cover zone N" so downloading a zone can offer its
   transit. Feeds can also straddle zones.
3. **Stop↔walk-graph linking & footpath transfers.** Need nearest-walk-node
   snapping for access/egress and precomputed short walking transfers between nearby
   stops (RAPTOR needs a transfer table). The generator already has a 15 m transfer
   notion to borrow from.
4. **Calendar correctness:** `calendar.txt` + `calendar_dates.txt` exceptions,
   service-day bitsets, and per-feed **timezone**/midnight-rollover (`>24:00:00`
   times are legal in GTFS). The current engine's day-mod arithmetic is naive.
5. **Freshness/versioning:** offline GTFS goes stale; needs a feed version stamp +
   the P10 online board/MOTIS as the fresh fallback.
6. **Licensing/attribution** per feed (Transitous tracks this; carry it into packs).

None are showstoppers; all are standard transit-routing engineering.

---

## 5) Concrete phased build plan (if lead approves go)

**Phase 0 — Decisions & pack contract (design).** Lock: RAPTOR vs CSA-first; the
transit-pack file format (feed-keyed vs zone-keyed); resolve the §1d road-graph
format gap ownership. Define the on-disk transit index schema (stops, routes,
trips, stop_times delta-coded, service-day bitsets, transfer table, string pool)
and a `feeds.json` manifest (feed id, version, bbox, covered zones, size, URL).

**Phase 1 — GTFS ingest (Python, `scripts/maps/normalize_gtfs.py`).** Parse a
GTFS `.zip` → the compact per-region index above. Pure stdlib, deterministic,
`test/` fixture feed + `test_normalize.py`-style unit test. Emits index + manifest
entry; no C++.

**Phase 2 — Rust transit index + planner (`maps/src/main/rust/src/transit.rs`).**
mmap loader for the index (mirroring `graph.rs`); RAPTOR (or CSA) earliest-arrival
+ transfers over one region; produce the same `StepData` transit shape
`reconstruct_path` emits so `lib.rs` marshaling and the UI are unchanged. Reuse the
road A* for access/egress/transfer walking legs; keep the `present_feeds` gate.

**Phase 3 — Generator/packaging wiring.** Add the transit index to region packs
(new part). Publish via R2 like the pmtiles. Do **not** grow `generator.cpp`; the
Python stage writes the transit part; a thin packaging step lists it in the
manifest. Address the road-graph merge/format gap here (or as a tracked sibling).

**Phase 4 — App download + init wiring.** Extend `ZoneDownloadManager` to fetch the
transit part (the `($part)` scheme already anticipates this) and track it in
`DownloadedMapsPage`. Move `present_feeds` discovery from "APK assets" to
"downloaded region packs" (fall back to assets). Point the Rust transit loader at
`getExternalFilesDir`.

**Phase 5 — Routing integration + online fallback.** Route `TravelMode.TRANSIT`
through the new planner when the region's feed is present offline; otherwise fall
back to the **P10 online board / MOTIS** (`TransitousDataSource`) — the graceful
`present_feeds` miss path already models this. Wire journey results into the
existing directions UI.

**Phase 6 — Validation.** Fixture-feed unit tests (Phase 1); a metro dry-run
(e.g. SF Bay) end-to-end; compare a few journeys against the MOTIS online result
for sanity; measure on-device index size + query latency.

**Reuse leverage:** transit UI (`RouteService.Step`/`transitDetails`), the JNI
boundary + `RawStep` marshaling, `present_feeds`, the road A* for walk legs, the
Python normalizer pattern + test harness, and the P10 Transitous source for the
online fallback. The genuinely new work is `normalize_gtfs.py`, `transit.rs`
(RAPTOR/CSA + index loader), and the pack download/packaging wiring.

---

## Appendix — key file references

- `scripts/maps/generator.cpp` — OSM→graph; transit scaffolding `:348-355`,
  `:431-445`, `:536-582`, `:641-761`; output `:855-913`.
- `scripts/maps/run_generator.sh` — compile/run/R2-sync `:64-99`.
- `maps/src/main/rust/src/graph.rs` — loader + `present_feeds` `:167-301`; on-disk
  structs `:39-69`.
- `maps/src/main/rust/src/routing.rs` — transit A* `:322-428`; step build
  `:446-600`.
- `maps/src/main/rust/src/geometry.rs` — `get_transit_edge_time_10ms` + offline
  feed fallback `:112-190`.
- `maps/src/main/rust/src/lib.rs` — JNI `init(basePath, presentFeeds)` `:124-163`.
- `maps/src/main/java/.../util/OfflineRouter.kt` — present-feeds from assets
  `:214-218`; graph base = externalFilesDir `:211`.
- `maps/src/main/java/.../util/GTFSProvider.kt` — GTFS `routes.txt` from assets.
- `maps/src/main/java/.../ui/DownloadedMapsPage.kt` + `util/ZoneDownloadManager.kt`
  — pmtiles-only zone downloads; `"Map Zone $id ($part)"` multi-part hint.
- `maps/src/main/java/.../data/transit/TransitousDataSource.kt` — P10 online board
  (MOTIS) = the online fallback.
