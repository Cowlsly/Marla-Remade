# Vela → Modern-Apps Maps: Port Plan (Research + Plan only)

Scope: port Vela's UI/layout, driving/turn-by-turn nav UI, and missing features into the **existing** Modern-Apps (MA) maps app; rework amenities to be **Google-only** on a **custom overlay layer**.

**KEEP (do not replace):** MA's MapLibre/PMTiles **renderer**, MA's on-device Rust **routing** engine + live-nav stack, and the already-added Google POI enrichment (`data/google/` + `PoiEnrichment.kt`).
**Voice:** system TTS only (MA already has `NavigationTts.kt`); do **not** port Vela's Piper/neural synth.
**Lint:** `RawScaffoldInApp` → use shared `library/ui` scaffolds; trademark strings ("Google", etc.) stay non-translatable.

Vela paths below are relative to the clone root (removed after research; never committed). MA paths are under `maps/src/main/java/com/vayunmathur/maps/`.

---

## (a) Vela inventory — UI / driving nav / POI layer (file refs)

**Renderer (matches MA family):** MapLibre Native `org.maplibre.gl:android-sdk:11.8.0`, imperative API (not Compose bindings). Base style = OpenFreeMap Liberty (OMT schema) remote URL. `core/.../data/tiles/MapStyle.kt`. PMTiles used for overlays (buildings, maxspeed, offline regions).

**Map view / renderer wrapper:** `app/.../ui/map/VelaMapView.kt` (6013 lines) — the imperative MapLibre wrapper. Key mechanics:
- **Suppress native basemap POIs:** set `visibility = NONE` on Liberty POI layers `poi_r1, poi_r7, poi_r20, poi_transit` (`VelaMapView.kt:781-788`, re-asserted in `applyData` ~`:1150-1195`).
- **Custom Google-POI overlay ("ambient"):** `GeoJsonSource AMBIENT_SRC` + `SymbolLayer AMBIENT_LAYER` (icons) + `AMBIENT_DOT_LAYER` (dots) built at `:3234-3345`; category icons from `PoiIcons` (`vela-poi-<group>` runtime bitmaps). Consts `:139-143`, `:183`.
- **Search-result pins:** `GeoJsonSource MARKERS_SRC` + `SymbolLayer MARKERS_LAYER`/`MARKERS_DOTS_LAYER` (`:3140-3160`). Model `data class MapMarker(name, location, category, prominence, rating, fuelPrice)` (`:221`).
- **Tap handling:** `handleTap` (`:1857-2004`) → `map.queryRenderedFeatures(RectF, layers…)`; priority parking → own pins (`MARKER_INDEX_PROP`) → saved → GTFS stop → **ambient Google vs basemap POI by screen distance** (`:1905-1946`) → house-number → building; fires `onAmbientTap(index)` / `markerTap(index)` / `onPoiTap(name, latlng, kind)`. Registered `map.addOnMapClickListener { handleTap(it) }` (`:2004`).
- **Nav camera:** heading-up + tilt + close follow, eased; two-finger tilt/shove, re-center tick to drop overrides (`:283-292, :481-540, :585-640, :1215-1290`). Uses `CameraPosition.bearing/tilt`.
- **Posted speed limit:** poll `queryRenderedFeatures` on hosted **maxspeed PMTiles** overlay under the puck (`:967-1003`), parsed by `core/.../data/OsmMaxspeed.kt`.
- POI icon factory: `app/.../ui/map/PoiIcons.kt` — `applyToLiberty` (`:435`), `resultIconKey/resultBubbleLabel`, `ensureResultIcon`, `addResultDot`, `colorFor(group)`, `GROUPS` category→color/glyph map.

**Map screen layout:** `app/.../ui/map/MapScreen.kt` (259 KB) — `MapScreen(...)`, `SearchResults`, `CategoryChips`, `ChooseOnMapOverlay`, `SearchEntryContent`, shortcut/saved rows, `SpeedWidget` (speedometer), `ScaleBarReader`, cards (voice/region download, faster-route, notices), `ListsSheet`. Scale bar: `app/.../ui/map/ScaleBar.kt`. Layers button: `app/.../ui/LayersButton.kt`. Compass toggle wired via `onCompassTap` (north-up/heading-up during drive).

**Search UX:** `app/.../ui/search/SearchBar.kt` (`SearchBar(...)`), results/chips in `MapScreen.SearchResults`/`CategoryChips`. Geocode/search data via Google source (below) + `core/.../data/PhotonGeocoder.kt`, `RecentSearchStore`, `CategoryFilter.kt`.

**Place-details sheet:** `app/.../ui/place/PlaceSheet.kt` (235 KB) — `PlaceSheet`, `DirectionsPanel`, `RouteOption`, photos gallery, reviews, hours, popular times, transit boards. Support: `RouteTopCard.kt`, `StopsEditor.kt`, `PlaceContent.kt`, `RatingStars.kt`, `LiveReviews.kt`.

**Driving / turn-by-turn nav UI:** `app/.../ui/nav/`
- `NavOverlays.kt` (42 KB): `ManeuverBanner` (with `FitText`), `LaneGuide`/`LaneDiagram` (lane arrows), `SignChip`/`roadSigns` (road shields), `NavControls`, `ArrivalSummary`, `NavSearchChips`.
- `RouteShield.kt`: US route/state shields (`RouteShield`, `ShieldBadge`, `StateMarker`).
- `StepsSheet.kt`: full turn list (`StepsSheet`, `maneuverIcon`).
- Speedometer + posted limit: `MapScreen.SpeedWidget` + `app/.../ui/Format.kt formatSpeedLimit`.
- Nav logic (reference only — MA keeps its own): `core/.../nav/NavEngine.kt`, `NavSession.kt`, `NavModels.kt`, `Heading.kt`.
- Voice: `core/.../voice/VoiceGuide.kt` uses **Android `TextToSpeech`** as primary (`import android.speech.tts.TextToSpeech`); Piper/`NeuralSynth`/`PiperCatalog`/`VelaPiper` are the optional neural path we **skip**. Guidance text: `SpokenScript.kt`, `SpeechText.kt`.

**Google data source (reference for search + POIs):** `core/.../data/google/GoogleMapsDataSource.kt` (58 KB) implements `MapDataSource`: `search(query, near, span, rankFrom)`, `nearbyPlaces(center, span, onPartial)` (drives ambient layer), `placeDetails(id)`, `reverseGeocode`, `reviews`, `placePhotos`, `directions`, `streetView`. Ranking `ambientProminence(place)` / `rankAmbientPlaces`. Parsers in `core/.../data/google/parse/*` and `SearchParser.kt`, `DirectionsParser.kt`, `PolylineCodec.kt`, `PbBuilder.kt`. Ambient state fed as `ambientPois: List<Place>` from `MapViewModel` → `VelaMapView(ambientPois=…, onAmbientTap=…)`.

**Saved / layers / settings:** saved: `core/.../data/SavedPlaceStore.kt`, `PlaceListStore.kt`, `RecentPlaceStore.kt` + `SavedPlacesSettings.kt`; saved pins layer `SavedPin` in `VelaMapView.kt:240`. Layers toggle: `LayersButton.kt`, `SatelliteLayer.kt`, `TransitLayer.kt`, `Traffic.kt`, `Topography.kt`, `Buildings3d.kt`. Settings: `app/.../ui/settings/*` (`SettingsHub`, `MapSettings`, `NavigationSettings`, `VoiceSettings`, `SearchSettings`, `OfflineSettings`, `AboutSettings`, …).

---

## (b) Existing MA app inventory

**Renderer — CONFIRMED:** MapLibre Native via **`maplibre-compose`** (`org.maplibre.compose.*`, **declarative** Compose layers) over **PMTiles vector tiles** (Protomaps schema). NOT the raster-only `library/map` module.
- Map entry: `ui/MapPage.kt:326` `MaplibreMap(...)`; overlay layer tree `ui/MyMapLayers.kt:47` (`SymbolLayer`/`CircleLayer`/`LineLayer`/`FillLayer`, `GeoJsonSource`/`rememberVectorSource`).
- Base style: asset `maps/src/main/assets/style.json` (v8, 3544 lines) loaded `MapPage.kt:159-169` → `BaseStyle.Json`; source = `pmtiles://https://data.vayunmathur.com/v4.pmtiles`. Runtime online/offline duplication in `patchStyleForHybrid()` `MapPage.kt:545-597` (creates `*_base`/`*_hybrid` layer variants). Disk tile cache `util/MapTileCache.kt`.
- **(a) Hide native POIs:** NO runtime visibility API. Native POI symbol layer is `id:"pois"` (`style.json:2684-2748`, `source-layer:"pois"`); place labels `places_locality`/`places_country`/`places_region`. Suppress by dropping/filtering `pois` in `style.json` and/or in `patchStyleForHybrid`.
- **(b) Custom overlay + tap:** SUPPORTED. Add `GeoJsonSource`+`SymbolLayer` in `MyMapLayers.kt` (existing pattern). Tap hit-test: `onMapClick` `MapPage.kt:335-366` → `camera.projection.queryRenderedFeatures(offset, setOf(...))` → `parse(...)`.
- **Camera:** bearing/tilt/follow already used in nav: `MapPage.kt:281-306` (`bearing = courseOverGround, tilt = 60, zoom = 17`, `autoFollow`, user-pan detect via `camera.isCameraMoving`). Recenter FAB `NavigationOverlay.kt:115-126`.

**amenities.db — to REMOVE (usages):**
- Def/schema/builder: `data/Tag.kt` (`AmenityTag`, `TagDao`, `AmenityEntity`, `AmenityDao.getInBBox` FTS5, `AddressResult`/`AddressDao.search` FTS5 geocoder, `@Database AmenityDatabase`, `buildAmenityDatabase → createFromFile(amenities.db)`). Exported schema `maps/schemas/com.vayunmathur.maps.data.AmenityDatabase/1.json`.
- Repo: `data/AmenityRepository.kt`.
- Download (not bundled): `MainActivity.kt:60` Triple `https://data.vayunmathur.com/amenities.db`.
- Search flow: `util/MapsSearchViewModel.kt` (`getInBBox`, `searchAddresses`, `resolveAmenity`, `SearchResult.Amenity/.Address`); `ui/SearchPage.kt:39,57,63,90`.
- Details flow: `data/SpecificFeature.kt:40-96` (`parse(...)` → `db.tagDao().getTags(id)` for Restaurant/TransitStop/GenericPlace).
- Map tap enrichment: `ui/MapPage.kt:66,69,338-353` (`parse(raw, AmenityRepository.get(context).getDatabase())`).
- Build: `build.gradle.kts:6,42-43` (`ksp` + `implementRoom` — drop only if Room unused elsewhere). Strings: `res/values/strings.xml:110` `downloading_amenity_database`, `:33` `unnamed_amenity`.
- ⚠️ **Removing the DB kills ALL text search AND address geocoding** (`Addresses_fts`). No other geocoder exists → a Google-based search/geocode replacement is REQUIRED (see P3 / Decision D2).

**Routing — KEEP:** on-device **Rust A\*** router `libofflinerouter.so` (`util/OfflineRouter.kt`; rust in `maps/src/main/rust/src/{routing,graph,geometry,mvt,state,lib}.rs`). Outputs full turn-by-turn: `RouteService.Route{duration, distanceMeters, polyline, step[]}`, `Step{…, navInstruction, travelMode, transitDetails, speedRatio}`, maneuver enum `RouteService.kt:16`, icons `ui/ManeuverIcon.kt`. Live nav already exists: `util/NavigationSessionManager.kt` (snap/off-route/arrival/recalc), `util/NavigationService.kt` (foreground svc), `util/NavigationTts.kt` (**system TTS**), `util/PolylineProgress.kt` (ETA/remaining/next-maneuver), `ui/NavigationOverlay.kt` (maneuver card + ETA strip + recenter + arrival). Requests: `util/SelectedFeatureViewModel.kt:89-118`. Rendered: `MyMapLayers.buildRouteFeatures:335-431` → `LineLayer "route"`.

**Current UI:** scaffold `ui/MapPage.kt:308-511` (BottomSheetScaffold + AppScaffold + MaplibreMap); collapsed search bar `:433-440`; full search `ui/SearchPage.kt`; VM/contract `util/MapsSearchViewModel.kt`, `util/MapsUiContract.kt`; bottom sheet `ui/BottomSheetContent.kt` (`RouteSheet:130-242`); place detail `ui/RestaurantBottomSheet.kt`; nav overlay `ui/NavigationOverlay.kt`; downloads `ui/DownloadedMapsPage.kt`.

**Google POI code — KEEP + build on:**
- `data/google/GooglePoiDataSource.kt` — keyless scrape (ported from Vela). `fetch(name, lat, lon): GooglePoiInfo?` (cached LRU 64, 250 m match), `fetchReviews(featureId)`. Endpoints: `search?tbm=map` (`:45`, pb template `:359-378`), reviews RPC (`:47`), session warm (`:49`). `pickEntry`/`parsePlace` already parse `LAT/LNG/NAME/CATEGORY/RATING` per entry (`:167-215`) but **keep only the nearest single match**.
- `data/google/GooglePoiModels.kt` — `GooglePoiInfo{rating, reviewCount, priceText/Level, website, phone, openNow, statusText, category, editorialSummary, featuredReview, hours[], photoUrls[], reviews[], popularTimes, featureId}` (⚠️ **no id / no lat-lng / no name** — caller supplies position). `GoogleReview`, `PoiPopularTimes`.
- `data/google/GoogleResponse.kt` — XSSI strip + positional accessors.
- `ui/PoiEnrichment.kt` — `GooglePoiEnrichment(poi, hasOsmHours)` composable (subtitle/editorial/hours/photos/popular-times/reviews/attribution). Driven by `SelectedFeatureViewModel.currentPoiInfo` (`:68-85`), rendered in `RestaurantBottomSheet.kt`. **Only in the sheet — not on the map.**

---

## (c) Gap analysis (Vela vs MA)

| Area | Vela | MA today | Gap → action |
|---|---|---|---|
| Renderer | MapLibre **imperative** + PMTiles | MapLibre **compose** + PMTiles | Same engine; Vela overlay/nav code is a **pattern port** (translate imperative `addLayer/queryRenderedFeatures` → maplibre-compose declarative layers + `camera.projection`). |
| Native POI suppression | `visibility=NONE` on `poi_r*` at runtime | none (baked style) | Filter/drop `pois` layer in `style.json`/`patchStyleForHybrid`. |
| Amenity source | Google (`GoogleMapsDataSource`) + OSM/Overpass | **amenities.db (OSM)** | Remove DB; make Google-only. |
| POIs on map | ambient Google `SymbolLayer` + dots + tap | **none** (pins come from vector tiles only) | **Build** custom Google-POI overlay + tap→sheet. |
| Search/geocode | Google search + Photon | amenities.db FTS + address FTS | **Replace** with Google search list (generalize `pickEntry`) + Google reverse-geocode. |
| Search-result pins | `MARKERS_LAYER` from search | route/user layers only | Add result-pin layer. |
| Routing | Google directions + on-device fallback | **on-device Rust A\*** (keep) | Reuse MA routing; only borrow UI. |
| Turn-by-turn nav UI | Rich: maneuver banner + **lane guidance** + **road shields** + **speedometer + posted limit** + arrival panel + heading-up compass toggle | Basic maneuver card + ETA strip + recenter + arrival | **Port** the richer nav overlays onto MA's existing nav session. |
| Posted speed limit | maxspeed PMTiles + queryRenderedFeatures | none | Add maxspeed source OR derive from route (Decision D4). |
| Voice | system TTS (+Piper optional) | **system TTS** (`NavigationTts`) | Keep MA's; optionally port voice toggle UI. |
| Place details | `PlaceSheet` (photos/reviews/hours/popular times/directions) | `RestaurantBottomSheet` + `GooglePoiEnrichment` | Port richer layout, keep MA enrichment data. |
| Map layout | search bar + category chips + FAB stack + scale bar + compass + speed widget | search bar + chips + nav overlay | Port layout polish (scale bar, compass toggle, FAB stack, category chips). |
| Saved/layers/settings | full stores + layers button + settings hub | partial | Port incrementally (P6). |

---

## (d) Phased port plan (each phase independently shippable)

### P1 — Amenities → Google-only custom overlay layer
**Goal:** remove `amenities.db`, suppress native basemap POIs, render Google POIs as tappable pins → existing Google details.
**Vela refs:** `VelaMapView.kt` POI-hide `:781-788`; ambient source/layers `:3234-3345`; tap resolution `:1905-1946`; `MapMarker` `:221`; `PoiIcons.kt` (`resultIconKey`, `colorFor`, `vela-poi-<group>`); `GoogleMapsDataSource.nearbyPlaces` (viewport fetch) + `ambientProminence`.
**MA — ADD:**
- `data/google/GooglePoiMapModels.kt` — list-oriented `GooglePoiPin{id, name, lat, lng, category, rating, prominence}` (fills the id/latlng/name gap in `GooglePoiModels.kt`).
- `data/google/GooglePoiDiscovery.kt` — generalize existing `pickEntry`/`parsePlace` (`GooglePoiDataSource.kt:167-215`) to return **List** for a viewport/near query (nearby/search).
- `ui/GooglePoiLayer.kt` — maplibre-compose `GeoJsonSource` + `SymbolLayer` (+ dot layer) for pins, styled from category (port `PoiIcons` grouping to Compose image assets or generated bitmaps).
- POI-pin viewport fetch in a VM (new `util/GooglePoiMapViewModel.kt` or extend `SelectedFeatureViewModel`), keyed on camera idle bbox (reuse `queryVisibleBoundingBox()`).
**MA — CHANGE:**
- `style.json` (`pois` `:2684-2748`) and/or `patchStyleForHybrid` (`MapPage.kt:545-597`): drop/empty-filter the `pois` layer (and `_base`/`_hybrid` variants).
- `MapPage.kt` `onMapClick` `:335-366`: add Google-pin hit-test (query the new pin layer first) → `SelectedFeatureViewModel.set(...)` so `GooglePoiEnrichment` renders; remove the `pois` tile-feature branch + `AmenityRepository.getDatabase()` enrichment (`:66,69,338-353`).
- `MyMapLayers.kt`: mount the new POI-pin layer.
**MA — REMOVE:** `data/Tag.kt`, `data/AmenityRepository.kt`, `schemas/com.vayunmathur.maps.data.AmenityDatabase/`, `MainActivity.kt:60` download, amenity/tag branches in `data/SpecificFeature.kt` + `util/MapsSearchViewModel.kt` (amenity parts) + `ui/SearchPage.kt` (amenity parts), strings `:110,:33`, and `ksp`/`implementRoom` in `build.gradle.kts` **iff** Room unused elsewhere (verify first).
**Reuse:** MA renderer (declarative layers, `queryRenderedFeatures`), existing `GooglePoiDataSource`/`PoiEnrichment`.
**Decisions:** D1 (native POI hide method: edit `style.json` vs runtime filter in `patchStyleForHybrid`); D3 (pin fetch trigger: camera-idle bbox vs fixed radius; caching/quota for scrape); D5 (pin icon rendering: port `PoiIcons` bitmaps vs Compose `SymbolLayer` images). **Note:** address geocoding also dies here — either ship P1 with search still on the DB (remove DB only after P3) or sequence P3 before fully deleting `AddressDao`. Recommend: P1 removes amenity **map/details** paths + suppresses POIs + adds overlay, but keep DB search until P3, then delete.

### P2 — Map-screen layout to match Vela
**Goal:** match Vela's browse layout/polish.
**Vela refs:** `MapScreen.kt` (`MapScreen`, `CategoryChips`, `SearchEntryContent`, `ScaleBarReader`), `ScaleBar.kt`, `LayersButton.kt`, compass toggle (`onCompassTap`), FAB stack.
**MA — ADD:** `ui/MapScaleBar.kt`, `ui/CompassButton.kt` (heading-up/north-up toggle bound to `CameraState.bearing`), `ui/CategoryChips.kt`, `ui/LayersButton.kt`.
**MA — CHANGE:** `MapPage.kt:308-511` scaffold — arrange search bar + chips + FAB stack (locate/layers/compass) + scale bar per Vela; use `library/ui` scaffold (avoid `RawScaffoldInApp`).
**Reuse:** renderer camera (`MapPage.kt:281-306`).
**Decisions:** D6 (which controls surface on browse vs nav; FAB order).

### P3 — Search UX (Google-only)
**Goal:** replace DB search/geocode with Google; Vela search look.
**Vela refs:** `SearchBar.kt`, `MapScreen.SearchResults`/`CategoryChips`, `GoogleMapsDataSource.search`/`reverseGeocode`, `RecentSearchStore.kt`, `CategoryFilter.kt`.
**MA — ADD:** `data/google/GoogleSearchDataSource.kt` (search list + reverse-geocode via generalized discovery), `data/RecentSearchStore.kt`.
**MA — CHANGE:** `util/MapsSearchViewModel.kt` + `util/MapsUiContract.kt` (results from Google, drop `SearchResult.Amenity/.Address`), `ui/SearchPage.kt` (Vela-style results/chips, remove `AmenityRepository`).
**MA — REMOVE (final):** the remaining `amenities.db` search/geocode paths + `AddressDao`/`AmenityDao` (completes P1's deferred deletion).
**Reuse:** existing scrape/parse infra.
**Decisions:** D2 (geocode replacement: Google reverse-geocode scrape reliability/quota; offline-search story — DB removal drops offline search, confirm acceptable), D7 (debounce/session tokens for scrape).

### P4 — Place-details sheet layout
**Goal:** Vela `PlaceSheet` layout, MA data.
**Vela refs:** `PlaceSheet.kt` (`PlaceSheet`, `DirectionsPanel`, `RouteOption`, photo gallery, reviews, popular times), `RatingStars.kt`, `RouteTopCard.kt`.
**MA — ADD/CHANGE:** rework `ui/RestaurantBottomSheet.kt` → `ui/PlaceSheet.kt` layout; keep `GooglePoiEnrichment` (`PoiEnrichment.kt`) + `currentPoiInfo` as the data source; wire "Directions" to MA routing (`SelectedFeatureViewModel.routes`).
**Reuse:** `GooglePoiInfo`, MA routing, `BottomSheetContent.RouteSheet`.
**Decisions:** D8 (which Google fields are reliably scraped for the header/gallery).

### P5 — Driving / turn-by-turn nav UI (MA routing + system TTS)
**Goal:** upgrade MA's nav overlay to Vela's richness on top of MA's existing nav session.
**Vela refs:** `NavOverlays.kt` (`ManeuverBanner`, `LaneGuide`/`LaneDiagram`, `SignChip`/`roadSigns`, `NavControls`, `ArrivalSummary`), `RouteShield.kt`, `StepsSheet.kt`, `MapScreen.SpeedWidget`, `Format.formatSpeedLimit`, compass heading-up toggle.
**MA — ADD:** `ui/nav/ManeuverBanner.kt`, `ui/nav/LaneGuidance.kt`, `ui/nav/RouteShield.kt`, `ui/nav/StepsSheet.kt`, `ui/nav/SpeedWidget.kt` (speedometer + posted-limit badge), `ui/nav/ArrivalSummary.kt`.
**MA — CHANGE:** `ui/NavigationOverlay.kt` compose the new banners; `MapPage.kt` nav camera already heading-up/tilt (`:281-306`) — add compass north-up toggle + re-center override reset (port Vela's `navRecenterTick` idea). Feed from `PolylineProgress` (`distanceToNextManeuver`, `etaEpochMs`) + `RouteService.Step.navInstruction`/maneuver enum + `speedRatio`.
**MA — REUSE (do not port):** `NavigationSessionManager`, `NavigationService`, `NavigationTts` (system TTS), Rust routing, `PolylineProgress`, `ManeuverIcon`.
**Skip from Vela:** Piper/`NeuralSynth`/`PiperCatalog`/`VelaPiper`/`VoiceLibrary`.
**Decisions:** D4 (posted speed limit source: add a maxspeed PMTiles overlay + `queryRenderedFeatures` like Vela `:967-1003`, vs derive from route `speedRatio`/graph, vs omit); D9 (lane-guidance data: Rust router currently has no lane info → either extend graph output or hide `LaneDiagram` until available); D10 (road-shield data: derive from `roadName`/ref parsing).

### P6 — Saved places, layers toggle, settings + remaining features
**Goal:** feature parity extras.
**Vela refs:** `SavedPlaceStore.kt`, `PlaceListStore.kt`, `RecentPlaceStore.kt`, `SavedPlacesSettings.kt`, saved-pin layer (`SavedPin`); `LayersButton.kt` + `SatelliteLayer`/`TransitLayer`/`Traffic`/`Topography`/`Buildings3d`; `settings/*` hub.
**MA — ADD:** `data/SavedPlaceStore.kt` + saved-pin overlay layer; `ui/LayersSheet.kt` (satellite/traffic/transit toggles — MA already has `traffic-layer` in `MyMapLayers.kt:122-137`); `ui/settings/MapSettings.kt` etc. as needed.
**MA — CHANGE:** mount saved-pin layer in `MyMapLayers.kt`; add saved-pin tap → sheet in `onMapClick`.
**Reuse:** existing traffic layer + renderer.
**Decisions:** D11 (satellite/transit tile sources & keys); D12 (settings scope for v1).

### P7 — Google Street View (keyless, reuse photos app renderer)
**Goal:** add Street View panorama viewing. RENDERER DECISION (user): do NOT port Vela's bespoke GLES 2.0 sphere — instead REUSE the photos app's existing image viewer/renderer to display the equirectangular pano (pan/zoom), which is simpler and consistent with the suite. The P7 agent must locate the photos app's image-viewer component (photos/src/main/java/... the zoom/pan image display used in its editor/gallery) and reuse it for the pano image.
**Vela refs (data only, NOT the renderer):** `core/.../data/google/StreetViewParser.kt` (keyless pano metadata: panoId, lat/lng, links/neighbors, date, heading), `StreetViewTiles.kt` (equirect tile fetch/stitch by zoom), `GoogleMapsDataSource.streetView`. Skip Vela's `PanoramaView.kt` (GLES sphere) — replaced by the photos renderer.
**MA — ADD:** `data/google/StreetViewDataSource.kt` (port StreetViewParser + equirect tile fetch/stitch via :library:network — keyless pano metadata + tiles → one equirect bitmap), `ui/streetview/StreetViewScreen.kt` (full-screen viewer using the PHOTOS APP's image renderer/viewer to pan/zoom the equirect bitmap; shared library/ui scaffold; move between adjacent panos via links).
**MA — CHANGE:** Street View entry points — from the place-details sheet (P4 `PlaceSheet`) when a pano exists, and optionally a long-press-on-map "Street View here"; nearest-pano lookup by lat/lng.
**Reuse:** the photos app image viewer (renderer), existing Google scrape infra, library/ui, library/image.
**Decisions:** D13 (tile zoom/quality vs bandwidth; caching), D14 (entry points: place sheet only vs also map long-press). Threading: all tile/metadata fetch on Dispatchers.IO.

---

## Suggested sequencing

## Cross-cutting decisions (need answers before coding)
- **D1** Native-POI hide: static `style.json` edit vs runtime filter in `patchStyleForHybrid`. (Recommend runtime, so it stays OTA-swappable.)
- **D2** Geocoding after DB removal: Google reverse-geocode scrape only? Accept loss of **offline** search/geocode?
- **D3/D7** Scrape quota/caching/debounce for viewport POI fetch + search (keyless scrape rate limits).
- **D4** Posted speed limit source (maxspeed PMTiles overlay vs route-derived vs omit).
- **D5** POI pin icon rendering (port `PoiIcons` bitmap factory vs Compose `SymbolLayer` images).
- **D9/D10** Lane guidance + road shields need data the Rust router may not emit — extend router output or ship UI degraded.
- **D-arch** Port Vela's imperative MapLibre overlay code as **maplibre-compose declarative** layers (recommended, matches MA) vs reach through to raw `MapLibreMap`.

## Suggested sequencing
P1 (map/details Google-only + suppress POIs, keep DB search) → P3 (Google search, then delete DB) → P2 (layout) → P4 (details) → P7 (street view, entry from the place sheet) → P5 (nav UI) → P6 (extras). P1 and P3 are the critical path (they own DB removal); P2/P4/P7/P5/P6 are largely independent afterward.

---

## CONFIRMED DECISIONS (user sign-off)
- **D2 / offline search:** ACCEPT online-only (Google) search. Removing `amenities.db` intentionally drops offline search + geocoding; no offline fallback. All search + reverse-geocode = Google scrape.
- **D4 / posted speed limit:** ADD a real maxspeed data source (maxspeed PMTiles overlay + `queryRenderedFeatures` under the puck, like Vela `VelaMapView.kt:967-1003` + `OsmMaxspeed.kt`). The driving UI shows accurate posted limits (P5).
- **D9 / lane guidance:** EXTEND the Rust router (`maps/src/main/rust/`) to emit lane info so Vela's lane diagrams work (P5; can be prepped early since it's isolated from the UI phases).
- **Defaults applied (no further input needed):** D1 runtime POI-hide (filter in `patchStyleForHybrid`, OTA-swappable); D-arch port as maplibre-compose **declarative** layers; D3/D7 debounced + LRU-cached viewport scrape; D5 pin icons as Compose `SymbolLayer` images/generated bitmaps; D10 road shields derived from road name/ref.
