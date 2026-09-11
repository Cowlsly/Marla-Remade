//! Which layers are drawn, in what order, in what colour - in light and dark.
//!
//! # Where the layers come from
//!
//! `style/basemap.flat.json`, one authored entry per drawn layer, read by [`paint`]. Order in
//! the file **is** draw order, and it is global rather than per tile: a road casing from one
//! tile must never cover the road fill of the tile next to it, so the renderer draws
//! layer-major across all tiles rather than tile-major across all layers.
//!
//! That file replaced a runtime interpretation of `style/basemap.json`, a 71-layer MapLibre
//! style: an expression evaluator, a filter reader, and a derivation that expanded one
//! data-driven layer into several because fill colour reaches the GPU as one push constant per
//! draw. All of it existed to reduce a general style to the seven properties this renderer
//! actually supports, and the last two rendering bugs lived in that reduction. The flat file
//! **is** one layer per colour, authored, so there is nothing left to derive — and
//! `paint::the_flat_style_agrees_with_basemap_json` cross-checks every value that exists in
//! both files, which is what keeps writing values down from becoming a third failed
//! transcription.
//!
//! # Where the colours come from
//!
//! Light is the bundled Protomaps style's own paint, except on lines — see [`paint`]'s docs for
//! that exception. Dark is not from a third-party style:
//! `maps/src/main/java/com/vayunmathur/maps/ui/theme/BasemapPalette.kt` already owns a
//! **contrast-checked dark palette for this exact archive**, and its module docs explain the
//! reasoning: a road label does not sit on a surface, it sits on the basemap, so deriving
//! basemap colours from the Material scheme "would let an unlucky accent produce grey-on-grey
//! terrain".
//!
//! So the dark values in the flat file are `BasemapPalette.darkFill`'s, by role. Two
//! consequences worth having: the schema matches by construction — these are the colours
//! written for `v4.pmtiles`' own layer names — and the five consumer apps end up looking like
//! the same product as `maps` rather than merely adjacent to it.
//!
//! # Switching variants is free
//!
//! Colour reaches the GPU as a push constant and the layer *set* is identical between
//! variants, so flipping light to dark re-uploads nothing and re-tessellates nothing. That
//! is why this is a runtime switch rather than a startup choice: the app can follow the
//! system theme.

pub mod paint;

use paint::{Ramp, Stroke};
use tilecodec::mamaps::body::Feature;
use tilecodec::mamaps::dict;

/// What pipeline a layer draws with.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum LayerKind {
    Fill,
    Line,
    /// Text labels (M1: places). Tessellated as textured quads from the SDF glyph
    /// atlas; drawn by the symbol pipeline with per-frame size/color/halo.
    Symbol,
}

/// Light or dark basemap. The layer set is the same; only the paint differs.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Variant {
    Light,
    Dark,
}

/// An optional layer group the host app opts into at runtime.
///
/// A layer with no toggle is basemap and always drawn. The others carry data
/// every archive already ships but which most consumers do not want: POI icons clutter a
/// map whose job is to show one pin, transit lines are noise outside a transit app, and
/// live traffic is a per-component overlay only a navigation view wants. Defaulting them
/// **off** is what makes the five existing consumers cost nothing.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Toggle {
    Poi,
    Transit,
    /// The live traffic layer (`LAYER_TRAFFIC`, id 10). Gated at tessellation like the
    /// others so an archive without it — or a consumer that never enables it — pays
    /// nothing; the per-segment colours arrive separately as a pushed id→ARGB table
    /// (see [`crate::vulkan::renderer::Renderer::set_traffic_speeds`]).
    Traffic,
}

/// Which optional layers are on. All off by default.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub struct LayerToggles {
    pub poi: bool,
    pub transit: bool,
    pub traffic: bool,
}

impl LayerToggles {
    /// Is a layer carrying `toggle` drawn?
    ///
    /// `None` is basemap, and always yes — which is why this takes an `Option` rather
    /// than making every caller special-case the common layer.
    pub fn enabled(&self, toggle: Option<Toggle>) -> bool {
        match toggle {
            None => true,
            Some(Toggle::Poi) => self.poi,
            Some(Toggle::Transit) => self.transit,
            Some(Toggle::Traffic) => self.traffic,
        }
    }
}

/// Which POI kinds the map is narrowed to, or empty for "every kind the style draws".
///
/// The app's category chips (Food, Gas, Hotels, …) select a handful of kinds out of the six
/// `poi-*` layers' thirty-nine. That is a *sub-layer* filter, so it cannot be expressed by
/// turning layers off, and it cannot ride in [`SharedToggles`]' packed word either — there are
/// more kinds than a `u32` has bits.
///
/// Interned ids rather than names, sorted, so the per-feature test is a binary search over a
/// `u16` slice — the same shape [`Layer::matches_id`] already uses for the layer's own whitelist.
/// An `Arc` because a worker holds it for the length of a tile build while the host may replace it.
#[derive(Clone, Debug, Default)]
pub struct KindFilter(std::sync::Arc<[u16]>);

impl PartialEq for KindFilter {
    fn eq(&self, other: &KindFilter) -> bool {
        // By value, not by pointer: the host rebuilds this list on every recomposition, so a
        // pointer comparison would report a change on every frame and re-tessellate forever.
        self.0[..] == other.0[..]
    }
}

impl Eq for KindFilter {}

impl KindFilter {
    /// Every kind. The default, and what an empty chip selection means.
    pub fn all() -> KindFilter {
        KindFilter(std::sync::Arc::from(Vec::new()))
    }

    /// A filter over interned kind ids. Sorted and deduplicated here so callers need not be.
    pub fn new(mut kinds: Vec<u16>) -> KindFilter {
        kinds.sort_unstable();
        kinds.dedup();
        KindFilter(std::sync::Arc::from(kinds))
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    /// Does this filter admit `kind` on `layer`?
    ///
    /// Only POI layers are filtered. The chips narrow which *points of interest* are shown; a
    /// road or a country label is not one, and hiding the basemap because the user tapped
    /// "Coffee" would be absurd.
    pub fn admits(&self, layer: &Layer, kind: u16) -> bool {
        if self.0.is_empty() || layer.toggle != Some(Toggle::Poi) {
            return true;
        }
        self.0.binary_search(&kind).is_ok()
    }
}

/// The toggles, the POI kind filter and a generation counter, read as one snapshot and shared
/// with the tile workers.
///
/// Gating happens at **tessellation** time rather than at draw time, because that is
/// where the cost is: with POI off, no label is shaped and no placement candidate is
/// built for any resident tile. The price of gating there is that a toggle change
/// invalidates meshes, so every mesh records the generation it was built at and the
/// renderer re-requests anything stale. Re-tessellation goes through the existing worker
/// pool and reads the archive it already has, so nothing is refetched and nothing is
/// evicted — the same "re-decorate without reloading" shape as a palette switch, one step
/// heavier.
///
/// One snapshot rather than a field each so a worker's read is consistent: a mesh tagged with
/// one generation but built from another's flags would either never be refreshed or be refreshed
/// forever. This was a single `AtomicU32` while the state was two bits and a counter; the kind
/// filter does not fit in a word, so it is a lock — taken once per tile build, not per feature.
#[derive(Debug)]
pub struct SharedToggles(std::sync::RwLock<Snapshot>);

#[derive(Clone, Debug)]
struct Snapshot {
    toggles: LayerToggles,
    kinds: KindFilter,
    generation: u32,
}

impl SharedToggles {
    /// Generations start at 1, so a mesh from a default-initialised 0 is always stale.
    pub fn new(toggles: LayerToggles) -> SharedToggles {
        SharedToggles(std::sync::RwLock::new(Snapshot {
            toggles,
            kinds: KindFilter::all(),
            generation: 1,
        }))
    }

    /// The current toggles, kind filter and the generation they belong to.
    pub fn get(&self) -> (LayerToggles, KindFilter, u32) {
        // A poisoned lock means another thread panicked mid-set. The state behind it is three
        // plain values with no invariant between them beyond the generation, so reading it is
        // sound; refusing to draw the map because a worker died is worse than drawing it.
        let snapshot = self.0.read().unwrap_or_else(|e| e.into_inner());
        (snapshot.toggles, snapshot.kinds.clone(), snapshot.generation)
    }

    /// Set the toggles and the kind filter, bumping the generation. Returns `true` when
    /// anything changed.
    ///
    /// Both at once rather than one setter each: the host sets them from a single Compose
    /// effect, and two bumps would re-tessellate the whole resident set twice for one change.
    ///
    /// A no-op set must not bump: the host may call this on every recomposition, and a
    /// bump there would re-tessellate the whole resident set for nothing.
    pub fn set(&self, toggles: LayerToggles, kinds: KindFilter) -> bool {
        let mut snapshot = self.0.write().unwrap_or_else(|e| e.into_inner());
        if snapshot.toggles == toggles && snapshot.kinds == kinds {
            return false;
        }
        snapshot.toggles = toggles;
        snapshot.kinds = kinds;
        snapshot.generation = snapshot.generation.wrapping_add(1).max(1);
        true
    }
}

/// Where a label sits relative to its anchor point.
///
/// Only the horizontal cases exist, because only they are used: place labels are centred
/// and the reference `pois` layer offers `["left", "right"]`. `Left` means the label's
/// left edge is at the anchor, so the text runs to the **right** of the point — which is
/// MapLibre's sense of the word and the opposite of the intuitive reading.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Anchor {
    Center,
    Left,
    Right,
}

impl Variant {
    pub fn from_dark(dark: bool) -> Variant {
        if dark {
            Variant::Dark
        } else {
            Variant::Light
        }
    }
}

/// Which basemap to paint: light or dark, and whether to mute it.
///
/// `muted` is what `weather` needs and what CARTO's Positron gave it: the basemap has to
/// recede so a colour-ramp overlay drawn on top of it stays readable. It is **derived**
/// rather than authored — every colour is blended toward the background by
/// [`MUTED_BLEND`] — which is what muting a basemap does, and it means there is one
/// palette to keep correct rather than four.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Palette {
    pub variant: Variant,
    pub muted: bool,
}

impl Palette {
    pub fn new(dark: bool, muted: bool) -> Palette {
        Palette { variant: Variant::from_dark(dark), muted }
    }
}

/// How far a muted colour moves toward the background. Enough for an overlay to dominate,
/// little enough that roads still read as roads.
pub const MUTED_BLEND: f32 = 0.45;

/// Blend `color` toward `toward` by `amount`.
fn blend(color: u32, toward: u32, amount: f32) -> u32 {
    let channel = |v: u32, shift: u32| ((v >> shift) & 0xFF) as f32;
    let mix = |shift: u32| {
        let from = channel(color, shift);
        let to = channel(toward, shift);
        (from + (to - from) * amount).round().clamp(0.0, 255.0) as u32
    };
    // Alpha is preserved: muting changes hue, not opacity.
    (color & 0xFF00_0000) | (mix(16) << 16) | (mix(8) << 8) | mix(0)
}

/// One drawn layer, as the flat style file states it.
pub struct Layer {
    pub id: String,
    /// The tile layer to read, e.g. `roads`.
    pub source_layer: String,
    /// The same layer, as the id a `.mamaps` body carries.
    ///
    /// Resolved once at load through [`tilecodec::mamaps::dict::LAYERS`], so the render path never
    /// compares a string. A `source` the schema has no id for fails the load.
    pub source_layer_id: u8,
    pub kind: LayerKind,
    /// Feature `kind` values to draw, or empty for every feature in the layer.
    pub kinds: Vec<String>,
    /// The same whitelist as interned ids, sorted.
    ///
    /// The hot path: `tile::geometry` tests every feature of every layer of every tile against
    /// this, and reading a `kind` used to mean a `String` allocation per feature per tile. A name
    /// the schema cannot emit fails the load rather than silently drawing nothing.
    pub kind_ids: Vec<u16>,
    /// Which of the tiler's road flags a feature must (not) carry to be drawn here.
    ///
    /// The authored style filters every road layer on `is_bridge`/`is_tunnel`/`is_link`
    /// (surface layers exclude them, link layers require `is_link`), but a flat entry only
    /// names `kind`s — so without this the surface layers would also draw every ramp, bridge
    /// and tunnel at full class width with casing, which is exactly the too-wide roads the
    /// comparator showed at super-zoom. Stored as require/forbid bitmasks over the feature
    /// flag bits so the render path stays two integer ops per feature.
    pub require_flags: u8,
    pub forbid_flags: u8,
    /// Which interned `kind_detail` values to draw, or empty for every detail.
    ///
    /// The authored style splits `minor_road` into `service` and non-`service` layers with
    /// different widths; `service` is an interned detail id, so this is a second sorted
    /// whitelist beside [`kind_ids`](Self::kind_ids).
    pub detail_ids: Vec<u16>,
    /// Interned `kind_detail` values explicitly excluded even when `detail_ids` is empty.
    ///
    /// `roads-minor` draws every minor road *except* `service`; an exclusion list states
    /// that without enumerating all thirty details.
    pub forbid_details: Vec<u16>,
    /// ARGB in light mode.
    pub light: u32,
    /// ARGB in dark mode.
    pub dark: u32,
    /// Fill opacity, in 0..=1. Always 1 for a line.
    ///
    /// Applied per frame against the camera's fractional zoom rather than folded into
    /// [`light`](Self::light)/[`dark`](Self::dark), because a fill has to fade *across* a zoom:
    /// a constant alpha plus an integer zoom gate is what drew `landcover` at full strength at
    /// z6 where the ramp asks for half.
    pub opacity: Ramp,
    /// Stroke width in Dp. Zero for a fill.
    ///
    /// On a [`carriageway`](Self::carriageway) layer it is one **lane's** width instead, and the
    /// road's own width is that times the lanes the feature carries. A single number cannot serve
    /// both a two-lane street and an eight-lane motorway, and the lane count is the feature's,
    /// not the style's.
    pub width: Ramp,
    /// Gap between the two halves of a casing, in Dp.
    ///
    /// Non-zero turns the stroke into two bands standing off the centreline — how the real
    /// style draws road casings. Whether there is a gap *at all* is decided once, at
    /// tessellation time, by [`gapped`](Self::gapped).
    pub gap_width: Ramp,
    /// Distance between two adjacent parallel lines of one corridor, in Dp.
    ///
    /// Only `transit-rail` sets it. Screen-space rather than a ground distance, and constant
    /// across zoom: the fan widens in discrete jumps as [`lanes`](Self::lanes) steps up, which
    /// is what makes a colour visibly re-assign at a boundary instead of its neighbours merely
    /// closing in on it.
    pub spread: Ramp,
    /// How many parallel lanes a corridor draws at a given zoom, floored at read.
    ///
    /// The one thing a feature cannot carry: it is a property of the camera, not of the route.
    /// A feature carries its colour's ordinal and its corridor's colour count
    /// ([`tilecodec::mamaps::body::Feature::transit_ordinal`]) and
    /// [`Layer::lane_offset_px`] turns the three into a lateral offset per frame.
    pub lanes: Ramp,
    /// Draw this line layer's features as carriageway surfaces rather than strokes.
    ///
    /// A stroke is a band of one colour, so nothing can be painted *within* it — which is why the
    /// divider fan this replaces had to re-stroke a road once per lane boundary. A carriageway
    /// ribbon ([`crate::tess::ribbon`]) carries an across-road coordinate, so every marking is
    /// arithmetic in `road_surface.frag` over one mesh.
    ///
    /// A flag on a line layer rather than a fourth [`LayerKind`], because the kind is what decides
    /// a [`crate::tile::geometry::LayerMesh`]'s stride and pipeline and a carriageway is not one:
    /// it has its own mesh list and its own render pass.
    pub carriageway: bool,
    /// Dash and gap lengths in line widths, as `line-dasharray` defines them.
    pub dash: (f32, f32),
    /// Halo color (ARGB), light and dark. The authored `text-halo-color` per layer;
    /// M1 transcribes it as a flat color like the fill color (no data-driven halos).
    pub halo_light: u32,
    pub halo_dark: u32,
    /// Halo width in screen px (authored `text-halo-width`, 1 everywhere in the
    /// authored style). Pushed per frame like the text size; making it style
    /// data rather than a constant keeps width and color agreeing in one place.
    pub halo_width: f32,
    /// Text size in px, as a zoom ramp. Zero/empty for non-symbol layers.
    ///
    /// The authored `text-size` is a *pixel* size at the camera zoom (unlike a road
    /// width in Dp it is not density-scaled - MapLibre sizes text in screen px, and
    /// the renderer applies density on the way to the shader because the tile span it
    /// is measured against is in device px).
    ///
    /// This is the arm for a place **below** [`Layer::rank_threshold`].
    pub text_size: Ramp,
    /// Text size for a place **at or above** [`Layer::rank_threshold`], where the
    /// authored style gives a second arm.
    ///
    /// `places_country` and `places_locality` size their labels with a two-arm `case`
    /// on `population_rank`, and the gap is wide: at z10 a locality is 12px below the
    /// threshold and 20px above it. Collapsing that to a single ramp drew every small
    /// town at close to city size - and because a label's collision box follows its
    /// size, those towns then beat the cities they should have lost to.
    pub text_size_large: Option<Ramp>,
    /// The population rank at which [`Layer::text_size_large`] takes over, per zoom.
    ///
    /// Falls as the camera descends (13 at z2 down to 8 at z15): the closer in, the
    /// smaller a place may be and still be worth drawing large.
    pub rank_threshold: Option<Ramp>,
    /// Uppercase the label (`text-transform: uppercase` in the authored style).
    pub uppercase: bool,
    /// Glyph weight: the authored `text-font` reduced to Regular/Medium.
    pub medium: bool,
    /// Which optional layer group this belongs to, or `None` for always-on basemap.
    pub toggle: Option<Toggle>,
    /// Draw a sprite icon beside the label, named by the feature's `kind`.
    ///
    /// Only the POI layers set this. The reference style's `icon-image` is
    /// `match(kind, "station", "train_station", kind)`, which is a rename of one kind and
    /// otherwise the kind itself — so a boolean plus that one rule says all of it, and no
    /// per-layer icon name has to be authored.
    pub icon: bool,
    /// `text-offset` in ems, applied along the resolved anchor.
    pub text_offset: (f32, f32),
    /// `text-max-width` in ems, the target width line breaking aims for. Zero means no
    /// wrapping, which is what every place layer wants.
    pub text_max_width: f32,
    /// `text-variable-anchor`: the anchors to try, in order, before giving up.
    ///
    /// Empty means the label is centred and does not move, which is the place layers'
    /// behaviour and what MapLibre does with no variable anchor declared.
    pub variable_anchor: Vec<Anchor>,
    pub min_zoom: u8,
    /// The floor that applies while *browsing*, i.e. with no category filter active.
    ///
    /// `min_zoom` says which zooms the archive is worth asking for. This says which zooms are
    /// worth *showing* when the user has not asked for anything in particular. They differ for
    /// exactly one reason: a category chip should reach further out than the ambient map does.
    /// Restaurants everywhere at z12 is clutter; restaurants at z12 *because you tapped
    /// Restaurants* is the feature.
    ///
    /// Defaults to `min_zoom`, so a layer that does not set it behaves exactly as before.
    pub browse_min_zoom: u8,
    pub max_zoom: u8,
    /// The `style/basemap.json` layer this was transcribed from.
    ///
    /// Provenance, and the key the cross-check test joins the two files on — see
    /// [`paint::tests::the_flat_style_agrees_with_basemap_json`]. Nothing in the render path
    /// reads it.
    pub authored: String,
}

impl Layer {
    /// Does this layer draw `feature`?
    ///
    /// The kind whitelist plus the road flag/detail filters, in one test so the render path
    /// calls a single function per feature per layer. Flag bits are require/forbid masks;
    /// details are whitelist-or-exclusion over the interned `kind_detail` id.
    pub fn matches_feature(&self, feature: &Feature) -> bool {
        if !self.matches_id(feature.kind) {
            return false;
        }
        if feature.flags & self.require_flags != self.require_flags {
            return false;
        }
        if feature.flags & self.forbid_flags != 0 {
            return false;
        }
        if !self.detail_ids.is_empty() && self.detail_ids.binary_search(&feature.kind_detail).is_err()
        {
            return false;
        }
        if !self.forbid_details.is_empty()
            && self.forbid_details.binary_search(&feature.kind_detail).is_ok()
        {
            return false;
        }
        true
    }

    /// Does this layer draw a feature whose interned `kind` is `kind`?
    ///
    /// The kind half of [`matches_feature`](Self::matches_feature): kept because tests and
    /// diagnostics name kinds without a feature to hand.
    /// The whole of the attribute filtering the renderer does, and all the style asks for.
    pub fn matches_id(&self, kind: u16) -> bool {
        self.kind_ids.is_empty() || self.kind_ids.binary_search(&kind).is_ok()
    }

    /// Does this layer draw a feature whose `kind` is named `kind`?
    ///
    /// Delegates to [`matches_id`](Self::matches_id) rather than comparing strings, so the two can
    /// never disagree. For tests and diagnostics; nothing on the render path takes a name.
    pub fn matches(&self, kind: Option<&str>) -> bool {
        match kind {
            None => self.matches_id(dict::NONE),
            Some(name) => match kind_id(name) {
                Some(id) => self.matches_id(id),
                // A name the schema cannot emit. Not drawn, because no feature can carry it.
                None => false,
            },
        }
    }

    /// Is this layer worth asking the archive for at `zoom`?
    ///
    /// A data-and-cost gate, not paint: it says which pyramid levels carry the layer. Paint is
    /// [`stroke`](Self::stroke) and [`opacity_at`](Self::opacity_at), which follow the *camera*.
    /// Deriving this from the opacity ramp instead meant `landcover` was never tessellated for
    /// a tile deeper than z6 even though its ramp wants it visible up to camera z7 — so whether
    /// a shape existed depended on which pyramid level happened to be resident, and shapes
    /// appeared and vanished while zooming.
    pub fn draws_at(&self, zoom: u8) -> bool {
        zoom >= self.min_zoom && zoom <= self.max_zoom
    }

    /// [`draws_at`](Self::draws_at), but honouring the browse floor unless this layer is what the
    /// user asked for.
    ///
    /// `focused` means at least one of this layer's kinds is in the active category filter. When
    /// it is, the layer falls back to its data floor and appears as early as the archive allows;
    /// when it is not, [`browse_min_zoom`](Self::browse_min_zoom) applies.
    pub fn draws_at_focused(&self, zoom: u8, focused: bool) -> bool {
        let floor = if focused { self.min_zoom } else { self.browse_min_zoom.max(self.min_zoom) };
        zoom >= floor && zoom <= self.max_zoom
    }

    /// Does the active category filter name any of this layer's kinds?
    ///
    /// An empty filter is "no category selected", not "select nothing", so it focuses nothing.
    pub fn focused_by(&self, filter: &KindFilter) -> bool {
        !filter.is_empty() && self.kind_ids.iter().any(|k| filter.admits(self, *k))
    }

    /// This label's size in px at `zoom`, for a place of population rank `pop`.
    ///
    /// Picks between the two arms the authored style declares. A layer without a second
    /// arm answers from [`Layer::text_size`] whatever the rank, which is what every
    /// non-place symbol layer wants.
    pub fn text_size_for(&self, zoom: f64, pop: u16) -> f32 {
        match (&self.text_size_large, &self.rank_threshold) {
            (Some(large), Some(threshold)) if f32::from(pop) >= threshold.at(zoom) => {
                large.at(zoom)
            }
            _ => self.text_size.at(zoom),
        }
    }

    /// Whether any place could produce a visible label at `zoom`.
    ///
    /// The cheap per-layer gate before the per-label sizing: a layer whose *widest* arm
    /// has ramped to zero cannot draw anything, whatever ranks the tile holds.
    pub fn text_visible_at(&self, zoom: f64) -> bool {
        let large = self.text_size_large.as_ref().map_or(0.0, |ramp| ramp.at(zoom));
        self.text_size.at(zoom).max(large) > 0.0
    }

    /// A casing: two bands offset either side of the centreline.
    ///
    /// Read at tessellation time, so it cannot vary with zoom — one geometry has to serve every
    /// frame. A layer whose ramp has a gap but which tessellates a plain stroke would discard
    /// the pushed gap silently, which is why this is the ramp's peak rather than its value
    /// anywhere in particular.
    pub fn gapped(&self) -> bool {
        self.gap_width.peak() > 0.0
    }

    /// Does this line layer fan its features into parallel lanes, rather than draw one stroke
    /// down each centreline?
    ///
    /// True when the style gives the layer a [`spread`](Self::spread) — the sideways step between
    /// adjacent lanes. Only `transit-rail` sets it, fanning its features by their colour ordinal.
    /// `lanes` defaults to 1 and so cannot be the discriminator; `spread` defaults to 0 and is only
    /// ever set on purpose.
    pub fn lane_fan(&self) -> bool {
        self.spread.peak() > 0.0
    }

    /// The stroke this layer draws at `zoom`, in Dp.
    pub fn stroke(&self, zoom: f64) -> Stroke {
        Stroke { width_dp: self.width.at(zoom), gap_width_dp: self.gap_width.at(zoom) }
    }

    /// How far sideways a transit feature's mesh shifts at `zoom`, in device pixels.
    ///
    /// `count` is the corridor's colour count and `ordinal` the colour's index within it, both
    /// straight off the feature; `taper` is how far into the lane this piece sits, over 255.
    ///
    /// Past the style's lane count the colours are squashed onto the lanes there are rather
    /// than the fan growing without bound. Squashing rather than wrapping is what keeps the map
    /// free of crossings at every zoom: the map is monotonic in `ordinal`, so two colours can
    /// come to share a lane but can never swap sides.
    ///
    /// It squashes from the **middle**. Ordinal zero takes lane zero and the last ordinal takes
    /// the last lane, so the outermost line on each side of a corridor keeps a lane to itself
    /// for as long as there is one to spare, and the doubling-up happens where it is least
    /// visible. Six colours over four lanes go `0,1,1,2,2,3` — one, two, two, one — where
    /// spacing them evenly would give `0,0,1,2,2,3` and crowd the two edges instead.
    pub fn lane_offset_px(
        &self,
        zoom: f64,
        density: f32,
        ordinal: u8,
        count: u8,
        taper: u8,
    ) -> f32 {
        let lanes = (self.lanes.at(zoom).floor() as i32).min(count as i32);
        if lanes < 2 {
            return 0.0;
        }
        // `lanes >= 2` implies `count >= 2`, so the divisor cannot be zero. The `+ steps` is
        // the round-to-nearest, which is what puts the wider buckets in the middle.
        let (span, steps) = (lanes - 1, count as i32 - 1);
        let lane = (2 * ordinal as i32 * span + steps) / (2 * steps);
        (2 * lane - (lanes - 1)) as f32 * self.spread.at(zoom) * density / 2.0
            * (taper as f32 / 255.0)
    }

    /// The fill opacity this layer draws at `zoom`, in 0..=1.
    pub fn opacity_at(&self, zoom: f64) -> f32 {
        self.opacity.at(zoom).clamp(0.0, 1.0)
    }

    pub fn color(&self, palette: Palette) -> u32 {
        let base = match palette.variant {
            Variant::Light => self.light,
            Variant::Dark => self.dark,
        };
        if palette.muted && !self.is_base() {
            blend(base, background(palette.variant), MUTED_BLEND)
        } else {
            base
        }
    }

    /// The halo color for a symbol layer, by palette. Non-symbol layers return
    /// transparent (their shaders never read it).
    pub fn halo_color(&self, palette: Palette) -> u32 {
        match palette.variant {
            Variant::Light => self.halo_light,
            Variant::Dark => self.halo_dark,
        }
    }

    /// Is this the land/sea base rather than detail drawn on it?
    ///
    /// The base is **never muted**. Muting blends toward the background, which is the water
    /// colour, so muting the base pulls land into the sea and erases the coastline — and no
    /// choice of colours avoids it, because blending toward a common point shrinks every
    /// separation by `1 - amount`. Positron, which is what muting imitates, keeps water
    /// plainly visible and mutes the detail on top. A host that mutes the basemap wants less
    /// competing detail, not less geography: `weather`'s overlay is meaningless without a
    /// recognisable coastline under it.
    fn is_base(&self) -> bool {
        matches!(self.source_layer.as_str(), "earth" | "water")
    }
}

/// A `kind` name's interned id, or `None` when the schema has no counterpart.
pub fn kind_id(name: &str) -> Option<u16> {
    dict::KINDS.iter().position(|k| *k == name).map(|i| i as u16 + 1)
}

/// Test-only access to [`kind_id`]: symbol tests build layers by hand.
#[cfg(test)]
pub fn kind_id_for_test(name: &str) -> u16 {
    kind_id(name).expect("a schema kind")
}

/// A `kind_detail` name's interned id, or `None` when the schema has no counterpart.
///
/// Details share the archive-wide [`dict::DETAILS`] table, so `service` here is the same id
/// the tiler wrote on the feature.
fn detail_id(name: &str) -> Option<u16> {
    dict::DETAILS.iter().position(|k| *k == name).map(|i| i as u16 + 1)
}

/// Behind everything, before any tile has loaded.
///
/// This is the **water** colour, not the land colour. The sea is the thing a world map is
/// mostly made of, `water.kind` includes `ocean`, and any area with no tile yet is far more
/// likely to be sea than land — so an unloaded map should read as ocean with land appearing
/// on top of it, rather than the reverse.
pub fn background(variant: Variant) -> u32 {
    let (light, dark) = paint::style().background;
    match variant {
        Variant::Light => light,
        Variant::Dark => dark,
    }
}

/// Whether the lane-level road rendering is built and drawn at all.
///
/// The one switch for the whole feature set: the `roads-carriageway` asphalt surface and its
/// painted markings, the `junction-connector` surface that continues it through an intersection,
/// the carriageway taper, and the road-surface turn arrows. Off for release; the code stays.
///
/// **This is the map surface, not the navigation lane guidance bar.** The two share the word
/// "lane" and nothing else: the guidance bar is a Compose overlay fed by the router's per-step
/// lane data, it has no connection to a style layer, and it draws whatever this says. Do not
/// read a blank map surface as the guidance bar being off, or vice versa.
/// It works by dropping the two [`Layer::carriageway`] layers from [`layers`], which is the one
/// point every part of the feature already funnels through. No carriageway layer means
/// `tile::geometry`'s carriageway branch is never taken (so no ribbon mesh, no taper), and
/// [`road_carriageway_layer`] answers `None`, which is what
/// [`crate::vulkan::renderer::Renderer::record_arrows`] gates the turn arrows on. Dropping the
/// layers rather than clearing the flag matters: a `carriageway` layer with the flag off would
/// fall through to the ordinary stroke path and draw a 40px grey band over every road at z20.
///
/// The plain road line layers (`roads-major` and friends) are untouched and already draw
/// *underneath* the carriageway pass, so removing that pass uncovers them rather than leaving a
/// hole — the map returns to its pre-carriageway appearance.
///
/// Not a [`Toggle`]: those are a host-app opt-in the user sees. This is a release kill switch,
/// so it is a `const` and flipping it is a code change.
///
/// # Before turning this back on
///
/// Check that the frame loop can still idle. Turning this on re-enables
/// [`crate::vulkan::renderer::Renderer::record_arrows`], which uploads a transient buffer per
/// arrow batch per frame; if the on-demand loop still treats a non-empty transient list as
/// "needs another frame", arrows re-arm it every frame and the map redraws forever with nothing
/// moving. The switch being off is the only reason that is unreachable today, so it will surface
/// as a battery regression the day the feature returns rather than as a failing test.
///
/// The tests that pin carriageway behaviour turn it on by reading
/// [`layers_with_lane_rendering`] instead, so they keep asserting against the real style rather
/// than being weakened to match the switch.
pub const LANE_RENDERING: bool = false;

/// Every layer the renderer draws, in draw order.
///
/// A borrow rather than a fresh list: the flat style is a constant table that happens to need a
/// parser, so it is parsed once for the process and handed out.
///
/// Carries no carriageway layer while [`LANE_RENDERING`] is off.
pub fn layers() -> &'static [Layer] {
    &paint::style().layers
}

/// Every layer the style declares, carriageways included, whatever [`LANE_RENDERING`] says.
///
/// For the tests that pin carriageway behaviour: they assert against the feature as authored, so
/// they have to name it explicitly rather than read a layer set the switch may have emptied.
#[cfg(test)]
pub fn layers_with_lane_rendering() -> &'static [Layer] {
    &paint::style_with_lane_rendering().layers
}

/// The road carriageway layer, whose zoom window gates the per-lane road detail and whose width
/// ramp says how wide one lane of it is.
///
/// Matched on [`Layer::carriageway`] **and** on the roads source, because the flag alone does not
/// name one layer: `junction-connector` draws a surface too, and would answer with a connector's
/// single lane. Its predecessor matched [`Layer::lane_fan`] — "does this layer have a spread" — and
/// `transit-rail` has one for its corridor colours and is declared first, so matching that way
/// silently answered with the rail layer, whose `minzoom` is 8 against the road detail's 16. That
/// is what drew turn arrows from z8, offset by the rail corridor's ramp instead of the road's.
///
/// Naming the source restores what that fix did before the `carriageway` flag replaced it, and is
/// what keeps the answer out of the hands of the declaration order in `basemap.flat.json`: exactly
/// one layer matches, so a reordered style or a third carriageway layer cannot re-point the gate.
pub fn road_carriageway_layer(layers: &[Layer]) -> Option<&Layer> {
    layers.iter().find(|l| l.carriageway && l.source_layer_id == dict::LAYER_ROADS)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The chips narrow POI and nothing else. A filter that hid roads and country labels
    /// because the user tapped "Coffee" would be a blank map, not a filtered one.
    #[test]
    fn a_kind_filter_narrows_poi_layers_only() {
        let poi = layers().iter().find(|l| l.id == "poi-food").expect("poi-food");
        let road = layers().iter().find(|l| l.toggle.is_none()).expect("a basemap layer");
        let cafe = kind_id("cafe").expect("cafe");
        let bar = kind_id("bar").expect("bar");

        let all = KindFilter::all();
        assert!(all.is_empty());
        assert!(all.admits(poi, cafe), "an empty filter admits everything");
        assert!(all.admits(poi, bar));

        let coffee = KindFilter::new(vec![cafe]);
        assert!(coffee.admits(poi, cafe));
        assert!(!coffee.admits(poi, bar), "a POI kind outside the filter is dropped");
        assert!(coffee.admits(road, bar), "a basemap layer is never narrowed");
    }

    /// Equality is by value. The host rebuilds the chip list on every recomposition, so a
    /// pointer comparison would report a change every frame and re-tessellate the resident set
    /// forever — which is exactly what the no-op rule exists to prevent.
    #[test]
    fn setting_the_same_filter_twice_does_not_bump_the_generation() {
        let cafe = kind_id("cafe").expect("cafe");
        let bar = kind_id("bar").expect("bar");
        let shared = SharedToggles::new(LayerToggles::default());
        let (_, _, first) = shared.get();

        let on = LayerToggles { poi: true, transit: false, traffic: false };
        assert!(shared.set(on, KindFilter::new(vec![cafe, bar])));
        let (toggles, kinds, second) = shared.get();
        assert!(toggles.poi);
        assert_ne!(second, first, "a real change bumps");

        // A freshly built, separately allocated filter holding the same kinds in the other
        // order — `new` sorts, so it must compare equal and bump nothing.
        assert!(!shared.set(on, KindFilter::new(vec![bar, cafe])));
        let (_, again, third) = shared.get();
        assert_eq!(third, second, "an identical set must not bump");
        assert_eq!(again, kinds);

        assert!(shared.set(on, KindFilter::all()), "clearing the chips is a change");
        assert_ne!(shared.get().2, third);
    }

    /// Perceived luminance, for asserting a palette is actually light or dark.
    fn luminance(argb: u32) -> f32 {
        let r = ((argb >> 16) & 0xFF) as f32 / 255.0;
        let g = ((argb >> 8) & 0xFF) as f32 / 255.0;
        let b = (argb & 0xFF) as f32 / 255.0;
        0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fn find(id: &str) -> &'static Layer {
        layers().iter().find(|l| l.id == id).unwrap_or_else(|| panic!("{id}"))
    }

    #[test]
    fn landcover_is_a_low_zoom_tint_and_stops_before_street_level() {
        // The opacity ramp is the only thing that gates landcover. Drawing it at every zoom —
        // which a transcribed table with no ramp to read did — lays a blanket over the map that
        // follows vegetation polygons rather than coastlines or borders, so it lines up with
        // nothing.
        let landcovers: Vec<&Layer> =
            layers().iter().filter(|l| l.source_layer == "landcover").collect();
        assert!(!landcovers.is_empty(), "landcover must still be drawn at low zoom");
        for l in &landcovers {
            assert!(l.opacity_at(4.0) > 0.0, "{} should tint low zooms", l.id);
            assert_eq!(l.opacity_at(7.0), 0.0, "{} is at zero opacity by z7", l.id);
            assert_eq!(l.opacity_at(14.0), 0.0, "{} must not reach street level", l.id);
        }
    }

    /// The other half of the same story: `landuse_park`'s ramp is 0 at z6 rising to 1 at z11, so
    /// it must not be drawn at world zoom. Ignoring it put continent-sized `national_park`,
    /// `nature_reserve` and `military` polygons on the map, whose tile-clipped edges read as
    /// straight cuts slashed across the shape.
    #[test]
    fn the_landuse_park_family_is_gated_off_at_world_zoom() {
        let parks: Vec<&Layer> =
            layers().iter().filter(|l| l.id.starts_with("landuse_park")).collect();
        assert_eq!(parks.len(), 4, "one layer per authored colour");
        for l in &parks {
            assert_eq!(l.opacity_at(6.0), 0.0, "{} is at zero opacity at z6", l.id);
            assert_eq!(l.opacity_at(11.0), 1.0, "{} is fully on by z11", l.id);
        }
    }

    /// The gate that decides whether geometry is *built* must not come from the opacity ramp:
    /// `min_zoom` is compared against the **tile's** pyramid level while the ramp follows the
    /// **camera**. See [`Layer::draws_at`] for what deriving one from the other did. Only
    /// `buildings` carries a floor, and that one is a cost decision — it is the densest layer in
    /// the schema, so a tessellation pass plus a draw call per tile is worth avoiding even where
    /// the archive would return nothing.
    #[test]
    fn only_a_declared_cost_floor_gates_a_fill_by_zoom() {
        for l in layers().iter().filter(|l| l.kind == LayerKind::Fill) {
            let expected = if l.id == "buildings" { 14 } else { 0 };
            assert_eq!(
                (l.min_zoom, l.max_zoom),
                (expected, paint::MAX_ZOOM),
                "`{}` carries a zoom gate that is not a declared cost floor",
                l.id,
            );
        }
    }

    /// Spot-checked against the authored values rather than asserting a count, so a restyle
    /// changes one colour rather than breaking the test. Every colour is cross-checked against
    /// `basemap.json` wholesale by `paint::the_flat_style_agrees_with_basemap_json`.
    #[test]
    fn fill_colour_is_the_authored_colour() {
        assert_eq!(find("earth").light, 0xFFE2DFDA, "the authored `#e2dfda`");
        assert_eq!(find("water").light, 0xFF80DEEA, "the authored `#80deea`");
        // A `match` arm the authored file writes as `rgba(210, 239, 207, 1)`.
        assert_eq!(find("landcover:grassland").light, 0xFFD2EFCF);
        // A `case` arm reached through `landuse_park`'s `in` conditions.
        assert_eq!(find("landuse_park:military").light, 0xFFC6DCDC);
        // Opacity is a per-frame ramp, not a baked alpha.
        assert_eq!(find("buildings").light, 0xFFCCCCCC);
        assert_eq!(find("landuse_urban_green").light, 0xFF9CD3B4);
    }

    /// Turn arrows are gated by the *road* lane layer, not by whichever layer happens to be the
    /// first with a spread.
    ///
    /// `transit-rail` carries a spread for its corridor colours and is declared before the road
    /// layer, so a `find(|l| l.lane_fan())` answers with the rail layer and its `minzoom: 8`. That
    /// shipped: turn arrows drew from z8, four zoom levels of dense per-frame CPU triangle building
    /// for geometry that belongs at z16. Pinned against the real style because the bug was entirely
    /// in the interaction between the predicate and the declaration order — a hand-built two-layer
    /// fixture would have passed.
    #[test]
    fn turn_arrows_are_gated_by_the_road_lane_layer() {
        // With lane rendering forced on: the gate is what the switch removes, so asserting it
        // against the shipped set would only re-state that the switch is off.
        let all = layers_with_lane_rendering();
        let gate = road_carriageway_layer(all).expect("the road carriageway layer");
        assert_eq!(gate.id, "roads-carriageway", "not `transit-rail`, which has a spread");

        let rail = all.iter().find(|l| l.id == "transit-rail").expect("transit-rail");
        assert!(rail.lane_fan(), "the rail corridor fan is what made the naive predicate wrong");
        assert!(!rail.carriageway, "and a corridor fan is not a carriageway");
        assert!(rail.min_zoom < gate.min_zoom, "and it is the earlier of the two");

        assert!(!gate.draws_at(12), "no turn arrows at z12");
        assert!(!gate.draws_at(gate.min_zoom - 1), "nor one level below the lane floor");
        assert!(gate.draws_at(16), "turn arrows from z16");
    }

    /// And the answer does not depend on the order the style lists its layers in.
    ///
    /// `junction-connector` carries `carriageway` too, so a `find` on the flag alone is once again
    /// a predicate several layers share, answering with whichever is declared first — the same
    /// shape as the `lane_fan` bug above, one layer along. `paint`'s
    /// `the_carriageway_is_gated_and_sized_by_the_lane` pins that order, which keeps today's style
    /// honest; this pins that the order is not load-bearing in the first place. Asserted as a
    /// filtered list rather than a `find`, because "one layer matches" is the property that makes
    /// order irrelevant, and a `find` cannot tell one match from the first of several.
    #[test]
    fn the_arrow_gate_does_not_depend_on_the_declaration_order() {
        let all = layers_with_lane_rendering();
        let matches: Vec<&str> = all
            .iter()
            .filter(|l| l.carriageway && l.source_layer_id == dict::LAYER_ROADS)
            .map(|l| l.id.as_str())
            .collect();
        assert_eq!(matches, vec!["roads-carriageway"], "the gate predicate must name one layer");

        // The layer that would answer instead, and the two halves of why it does not.
        let connector =
            all.iter().find(|l| l.id == "junction-connector").expect("junction-connector");
        assert!(connector.carriageway, "a connector draws as a road surface as well");
        assert_ne!(
            connector.source_layer_id,
            dict::LAYER_ROADS,
            "but it reads the junction source, which is what keeps it out of the gate",
        );
    }

    /// [`LANE_RENDERING`] decides whether the shipped layer set carries the carriageways at all,
    /// and the roads it uncovers are still there.
    ///
    /// The second half is the point. The carriageway pass draws *over* the flat layer loop, so
    /// switching it off has to leave the pre-carriageway road lines drawing rather than a hole —
    /// and `roads-carriageway` had to be dropped rather than have its flag cleared, because a
    /// `carriageway: false` layer of that width would fall through to the stroke path and paint a
    /// band over every road it names.
    #[test]
    fn the_lane_rendering_switch_removes_the_carriageways_and_nothing_else() {
        let shipped: Vec<&str> =
            layers().iter().filter(|l| l.carriageway).map(|l| l.id.as_str()).collect();
        if LANE_RENDERING {
            assert_eq!(shipped, vec!["roads-carriageway", "junction-connector"]);
            assert!(road_carriageway_layer(layers()).is_some(), "and the turn arrows are gated on");
        } else {
            assert!(shipped.is_empty(), "no surface layer, so no asphalt, markings or taper");
            assert!(road_carriageway_layer(layers()).is_none(), "which is the turn-arrow gate");
        }

        // Either way the plain road lines are untouched, and they draw to the top of the range.
        for id in ["roads-major", "roads-highway", "roads-minor", "roads-link"] {
            let road = find(id);
            assert!(!road.carriageway, "{id} is a stroke and stays one");
            assert!(road.draws_at(16) && road.draws_at(22), "{id} covers the carriageway's window");
        }

        // And the switch removes exactly the two, leaving every other layer in its place.
        let dropped: Vec<&str> = layers_with_lane_rendering()
            .iter()
            .filter(|l| !layers().iter().any(|kept| kept.id == l.id))
            .map(|l| l.id.as_str())
            .collect();
        let expected: Vec<&str> =
            if LANE_RENDERING { Vec::new() } else { vec!["roads-carriageway", "junction-connector"] };
        assert_eq!(dropped, expected);
    }

    /// A kind the authored `case` gives its own colour has its own layer, and a kind that shares
    /// a colour with another sits in the same layer rather than adding a draw.
    #[test]
    fn a_data_driven_fill_is_one_layer_per_colour() {
        let park: Vec<&Layer> =
            layers().iter().filter(|l| l.id.starts_with("landuse_park")).collect();
        let of = |kind: &str| park.iter().find(|l| l.matches(Some(kind))).map(|l| l.light);
        assert_eq!(of("national_park"), of("cemetery"), "one arm, one colour, one layer");
        assert_ne!(of("national_park"), of("military"));
        assert_eq!(of("pier"), None, "a kind the authored filter excludes is not drawn here");
    }

    #[test]
    fn every_landcover_kind_has_its_own_colour_in_both_palettes() {
        // The authored `fill-color` gives each kind a different colour, and that difference is
        // most of what makes a low zoom readable: it is why the Sahara does not look like the
        // Congo. Collapsing a palette's whole column to one literal paints every landmass a
        // single flat tint that follows vegetation polygons and lines up with nothing.
        for palette in [Palette::new(false, false), Palette::new(true, false)] {
            let mut seen: Vec<(u32, &str)> = Vec::new();
            for l in layers().iter().filter(|l| l.source_layer == "landcover") {
                let colour = l.color(palette);
                if let Some((_, other)) = seen.iter().find(|(c, _)| *c == colour) {
                    panic!("{} and {} share {colour:#010X} in {palette:?}", l.id, other);
                }
                seen.push((colour, &l.id));
            }
            assert_eq!(seen.len(), 7, "every authored `match` arm needs a layer here");
        }
    }

    #[test]
    fn landcover_kinds_are_lighter_than_the_earth_they_tint() {
        // A tint sits *on* the land, so in light mode it must not be darker than the land
        // itself or it reads as a separate landmass.
        let earth = find("earth");
        for l in layers().iter().filter(|l| l.source_layer == "landcover") {
            assert!(
                luminance(l.light) > luminance(earth.light) - 0.06,
                "{} is darker than earth, so it reads as land rather than a tint",
                l.id,
            );
        }
    }

    #[test]
    fn the_unfiltered_landcover_layer_is_drawn_first_so_specific_kinds_win() {
        // The authored style's `match` has a fallback arm; here that is a separate unfiltered
        // layer, which only behaves like a fallback if it draws under nothing else — i.e. it
        // must come first, before the kind-specific ones paint over it.
        let indices: Vec<(usize, &Layer)> = layers()
            .iter()
            .enumerate()
            .filter(|(_, l)| l.source_layer == "landcover")
            .collect();
        let fallback = indices.iter().find(|(_, l)| l.kinds.is_empty()).expect("a fallback arm");
        for (index, l) in &indices {
            if l.kinds.is_empty() {
                continue;
            }
            assert!(
                *index > fallback.0,
                "{} must follow the unfiltered fallback so its colour is not overpainted",
                l.id,
            );
        }
    }

    #[test]
    fn draw_order_is_the_order_in_the_file() {
        // Order is draw order, and the renderer draws layer-major so it holds across tiles. A
        // casing drawn after its fill would outline the road on top of itself.
        let index = |id: &str| layers().iter().position(|l| l.id == id).expect(id);
        for kind in ["minor", "major", "highway"] {
            assert!(
                index(&format!("roads-{kind}-casing")) < index(&format!("roads-{kind}")),
                "roads-{kind}-casing must precede roads-{kind}",
            );
        }
        assert!(index("earth") < index("water"), "water draws over earth");
        assert!(index("water") < index("roads-major"), "roads draw over water");
        // The authored style puts `landuse_park` through `landuse_runway` *before* `water` and
        // only `landuse_pedestrian` and `landuse_pier` after it. Flattening landuse to one side
        // of water puts parks on top of rivers or rivers on top of parks.
        assert!(index("landuse_park:national_park") < index("water"));
        assert!(index("landuse_runway") < index("water"));
        assert!(index("water") < index("landuse_pedestrian"));
        assert!(index("landuse_pier") < index("buildings"));
    }

    #[test]
    fn the_kind_filter_is_a_whitelist_and_empty_means_everything() {
        let highway = find("roads-highway");
        assert!(highway.matches(Some("highway")));
        assert!(!highway.matches(Some("major_road")));
        assert!(!highway.matches(None), "a feature with no kind is not a highway");

        let earth = find("earth");
        assert!(earth.matches(None), "an unfiltered layer draws a feature with no kind");
        assert!(earth.matches(Some("island")), "and every kind the schema can emit");
        assert!(earth.matches(Some("ocean")));
        // A name the schema has no id for cannot be on a feature at all, so nothing draws it.
        // Interning the whitelist is what turns that from a silent miss into an impossibility.
        assert!(!earth.matches(Some("not_a_kind")));
    }

    /// The interned whitelist and the authored names must agree, or the render path filters on
    /// something other than what the style says.
    #[test]
    fn the_interned_whitelist_is_the_authored_one() {
        for l in layers() {
            assert_eq!(
                l.kind_ids.len(),
                l.kinds.len(),
                "`{}` lost a kind when its whitelist was interned",
                l.id,
            );
            for name in &l.kinds {
                assert!(l.matches(Some(name)), "`{}` should draw `{name}`", l.id);
            }
            assert!(l.kind_ids.windows(2).all(|p| p[0] < p[1]), "`{}` is not sorted", l.id);
        }
        // And every layer reads a source the archive actually carries.
        let roads = find("roads-highway");
        assert_eq!(roads.source_layer_id, dict::LAYER_ROADS);
        assert_eq!(find("earth").source_layer_id, dict::LAYER_EARTH);
    }

    #[test]
    fn zoom_ranges_gate_the_expensive_layers() {
        // Buildings are the densest layer in the schema, so they stay off until they are
        // worth drawing.
        assert!(!find("buildings").draws_at(13));
        assert!(find("buildings").draws_at(14));
        assert!(find("earth").draws_at(0));
        assert!(find("earth").draws_at(22));
    }

    #[test]
    fn the_degenerate_dash_is_present_so_the_shader_path_is_exercised() {
        // `boundaries_country`'s authored `[2, 0]`: a zero gap has to render solid. See
        // line.frag.
        assert_eq!(find("boundaries").dash, (2.0, 0.0));
    }

    #[test]
    fn every_layer_id_is_unique() {
        let mut ids: Vec<&str> = layers().iter().map(|l| l.id.as_str()).collect();
        ids.sort_unstable();
        let count = ids.len();
        ids.dedup();
        assert_eq!(count, ids.len(), "layer ids are used as identities");
    }

    /// The lane arithmetic, at density 1 so a Dp is a pixel: `lanes` lanes of the constant
    /// 6 Dp spacing, centred on the track, so an even count straddles it and an odd one sits
    /// one line on it. The count comes from the style's zoom step, not from the feature.
    #[test]
    fn a_corridor_fans_out_centred_on_the_track_it_shares() {
        let rail = find("transit-rail");
        let of = |zoom: f64, ordinal: u8, count: u8| rail.lane_offset_px(zoom, 1.0, ordinal, count, 255);
        assert_eq!([of(9.0, 0, 2), of(9.0, 1, 2)], [-3.0, 3.0], "two lanes straddle it");
        assert_eq!([of(11.0, 0, 3), of(11.0, 1, 3), of(11.0, 2, 3)], [-6.0, 0.0, 6.0]);
        assert_eq!(
            [of(13.0, 0, 4), of(13.0, 1, 4), of(13.0, 2, 4), of(13.0, 3, 4)],
            [-9.0, -3.0, 3.0, 9.0],
        );
        // Past the zoom's lane count the ordinals squash, so a busy corridor shares lanes
        // rather than fanning off the street — from the middle, so the outermost line on each
        // side keeps a lane of its own.
        assert_eq!(of(9.0, 1, 4), of(9.0, 0, 4), "ordinals 0 and 1 of 4 share lane 0 of 2");
        assert_eq!(of(9.0, 3, 4), of(9.0, 2, 4), "and 2 and 3 share lane 1");
        assert!(of(9.0, 2, 4) > of(9.0, 1, 4), "without the two halves swapping");
        // A corridor of one has nothing to fan, and neither does a single-lane zoom.
        assert_eq!(of(13.0, 0, 1), 0.0);
        assert_eq!(of(8.0, 1, 4), 0.0);
    }

    /// Two groups of three merging, at a zoom that can draw four lanes: the outermost line on
    /// each side keeps a lane to itself and the four in the middle pair up, rather than the
    /// crowding landing on the two edges where it is most visible.
    #[test]
    fn a_corridor_past_its_lane_budget_doubles_up_in_the_middle() {
        let rail = find("transit-rail");
        let of = |ordinal: u8| rail.lane_offset_px(13.0, 1.0, ordinal, 6, 255);
        assert_eq!(
            [of(0), of(1), of(2), of(3), of(4), of(5)],
            [-9.0, -3.0, -3.0, 3.0, 3.0, 9.0],
            "one, two, two, one across the four lanes z13 draws",
        );
    }

    /// The property the whole lane-order pass rests on: within a corridor the offset never
    /// decreases as the ordinal rises, at any zoom and any colour count. Two lines can come
    /// to share a lane, but they can never cross.
    #[test]
    fn squashing_a_corridor_onto_fewer_lanes_never_reorders_it() {
        let rail = find("transit-rail");
        for count in 1u8..=12 {
            for step in 0..=40 {
                let zoom = 4.0 + f64::from(step) * 0.5;
                let offsets: Vec<f32> = (0..count)
                    .map(|ordinal| rail.lane_offset_px(zoom, 1.0, ordinal, count, 255))
                    .collect();
                assert!(
                    offsets.windows(2).all(|w| w[0] <= w[1]),
                    "count {count} at zoom {zoom}: {offsets:?}",
                );
                // And the fan always reaches its full width: the first and last ordinals take
                // the outermost lanes, which is what makes the squashing land in the middle.
                let (first, last) = (offsets[0], offsets[offsets.len() - 1]);
                assert_eq!(first, -last, "count {count} at zoom {zoom}: {offsets:?}");
            }
        }
    }

    /// The taper is a fraction of whatever the offset turns out to be, which is why it has to
    /// travel separately from the lane index.
    #[test]
    fn a_taper_scales_the_offset_it_eases_into() {
        let rail = find("transit-rail");
        assert_eq!(rail.lane_offset_px(9.0, 1.0, 1, 2, 255), 3.0);
        assert_eq!(rail.lane_offset_px(9.0, 1.0, 1, 2, 128), 3.0 * (128.0 / 255.0));
        assert_eq!(rail.lane_offset_px(9.0, 1.0, 1, 2, 0), 0.0);
    }

    // --- the road flag/detail filters (issue #3) -------------------------------

    fn feature(kind: &str, flags: u8, detail: &str) -> tilecodec::mamaps::body::Feature {
        use tilecodec::mamaps::body::{GEOM_LINE, NAME_NONE};
        tilecodec::mamaps::body::Feature {
            kind: kind_id(kind).expect(kind),
            kind_detail: detail_id(detail).expect(detail),
            geom_type: GEOM_LINE,
            flags,
            name_idx: NAME_NONE,
            parts_offset: 0,
            part_count: 0,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        }
    }

    /// Surface class layers draw plain surface roads only: no ramps, no bridges, no
    /// tunnels, and (for minor) no service streets. Before these filters a motorway_link
    /// drew as a full highway with casing at super-zoom — the thick cream band.
    #[test]
    fn surface_road_layers_draw_only_plain_surface_roads() {
        use tilecodec::mamaps::body::{FLAG_IS_BRIDGE, FLAG_IS_LINK, FLAG_IS_TUNNEL};
        let highway = find("roads-highway");
        assert!(highway.matches_feature(&feature("highway", 0, "motorway")));
        assert!(!highway.matches_feature(&feature("highway", FLAG_IS_LINK, "motorway_link")));
        assert!(!highway.matches_feature(&feature("highway", FLAG_IS_BRIDGE, "motorway")));
        assert!(!highway.matches_feature(&feature("highway", FLAG_IS_TUNNEL, "motorway")));
        assert!(!highway.matches_feature(&feature("major_road", 0, "primary")));

        let major = find("roads-major");
        assert!(major.matches_feature(&feature("major_road", 0, "primary")));
        assert!(!major.matches_feature(&feature("major_road", FLAG_IS_LINK, "primary_link")));
        assert!(!major.matches_feature(&feature("major_road", FLAG_IS_BRIDGE, "primary")));

        let minor = find("roads-minor");
        assert!(minor.matches_feature(&feature("minor_road", 0, "residential")));
        assert!(!minor.matches_feature(&feature("minor_road", 0, "service")));
        assert!(!minor.matches_feature(&feature("minor_road", FLAG_IS_TUNNEL, "residential")));

        // Casings agree with their fills: the same feature is outlined and filled, or
        // neither, so a fill can never sit uncased nor a casing unfilled.
        for (casing, fill) in
            [("roads-highway-casing", "roads-highway"), ("roads-major-casing", "roads-major")]
        {
            let (c, f) = (find(casing), find(fill));
            assert_eq!(c.forbid_flags, f.forbid_flags, "{casing} filters what {fill} fills");
            assert_eq!(c.require_flags, f.require_flags);
        }
        assert_eq!(find("roads-minor-casing").forbid_details, find("roads-minor").forbid_details);
    }

    /// Task-8 regression: bridge spans must draw. Every surface class layer
    /// forbids `bridge`, so without the roads-bridges-* pass a motorway
    /// bridge (Bay Bridge, Golden Gate) matched NO layer and vanished. Each
    /// bridge layer requires `bridge` and mirrors its surface class's paint.
    #[test]
    fn bridge_spans_draw_in_their_own_pass() {
        use tilecodec::mamaps::body::{FLAG_IS_BRIDGE, FLAG_IS_LINK, FLAG_IS_TUNNEL};
        let highway = find("roads-bridges-highway");
        assert!(highway.matches_feature(&feature("highway", FLAG_IS_BRIDGE, "motorway")));
        assert!(!highway.matches_feature(&feature("highway", 0, "motorway")));
        assert!(!highway.matches_feature(&feature(
            "highway",
            FLAG_IS_BRIDGE | FLAG_IS_LINK,
            "motorway_link"
        )));
        assert!(!highway.matches_feature(&feature("highway", FLAG_IS_TUNNEL, "motorway")));
        let major = find("roads-bridges-major");
        assert!(major.matches_feature(&feature("major_road", FLAG_IS_BRIDGE, "primary")));
        assert!(!major.matches_feature(&feature("major_road", 0, "primary")));
        let minor = find("roads-bridges-minor");
        assert!(minor.matches_feature(&feature("minor_road", FLAG_IS_BRIDGE, "residential")));
        assert!(!minor.matches_feature(&feature("minor_road", 0, "residential")));
        let link = find("roads-bridges-link");
        assert!(link.matches_feature(&feature(
            "highway",
            FLAG_IS_BRIDGE | FLAG_IS_LINK,
            "motorway_link"
        )));
        assert!(!link.matches_feature(&feature("highway", FLAG_IS_BRIDGE, "motorway")));
        let other = find("roads-bridges-other");
        assert!(other.matches_feature(&feature("other", FLAG_IS_BRIDGE, "unclassified")));
        assert!(!other.matches_feature(&feature("other", 0, "unclassified")));
        // Casings agree with their fills, like the surface pairs.
        for (casing, fill) in [
            ("roads-bridges-highway-casing", "roads-bridges-highway"),
            ("roads-bridges-major-casing", "roads-bridges-major"),
            ("roads-bridges-minor-casing", "roads-bridges-minor"),
            ("roads-bridges-link-casing", "roads-bridges-link"),
        ] {
            let (c, f) = (find(casing), find(fill));
            assert_eq!(c.require_flags, f.require_flags, "{casing} filters what {fill} fills");
            assert_eq!(c.forbid_flags, f.forbid_flags);
        }
    }

    /// The link layers catch exactly the `is_link` features the surface layers refuse.
    #[test]
    fn link_layers_draw_only_slip_roads() {
        use tilecodec::mamaps::body::{FLAG_IS_BRIDGE, FLAG_IS_LINK};
        let link = find("roads-link");
        assert!(link.matches_feature(&feature("highway", FLAG_IS_LINK, "motorway_link")));
        assert!(link.matches_feature(&feature("major_road", FLAG_IS_LINK, "primary_link")));
        assert!(!link.matches_feature(&feature("highway", 0, "motorway")));
        assert!(!link.matches_feature(&feature("minor_road", 0, "residential")));
        // A bridge that is also a link still draws as a link: the authored link layers
        // filter on `is_link` alone, and bridges keep their own roads-bridges-*
        // layers (task 8) alongside the surface + link passes.
        assert!(link.matches_feature(&feature(
            "highway",
            FLAG_IS_LINK | FLAG_IS_BRIDGE,
            "motorway_link"
        )));
        let casing = find("roads-link-casing");
        assert!(casing.matches_feature(&feature("highway", FLAG_IS_LINK, "motorway_link")));
        assert!(!casing.matches_feature(&feature("highway", 0, "motorway")));
    }

    /// Service streets have their own layer at their own width.
    #[test]
    fn service_streets_draw_only_in_the_service_layer() {
        let service = find("roads-minor-service");
        assert!(service.matches_feature(&feature("minor_road", 0, "service")));
        assert!(!service.matches_feature(&feature("minor_road", 0, "residential")));
        assert!(!find("roads-minor").matches_feature(&feature("minor_road", 0, "service")));
    }

    // --- light and dark ----------------------------------------------------

    #[test]
    fn every_layer_defines_both_variants_and_they_differ() {
        // A layer that forgot its dark colour would render its light one on a dark
        // background, which is the single most visible way to get this wrong.
        // Symbol layers are exempt (see the dark-palette test): one mid-grey column
        // until the dark label palette lands in M5.
        for l in layers() {
            if l.kind == LayerKind::Symbol {
                continue;
            }
            assert_ne!(l.light, l.dark, "{} has the same colour in both variants", l.id);
            // Translucency is legitimate — buildings draw at 0.5 — but that lives in the
            // opacity ramp, so a fully transparent colour is a layer that silently does
            // nothing.
            assert!(l.light >> 24 > 0, "{} light colour is fully transparent", l.id);
            assert!(l.dark >> 24 > 0, "{} dark colour is fully transparent", l.id);
        }
    }

    #[test]
    fn land_is_clearly_distinguishable_from_ocean() {
        // The most basic thing a map must do, and it was broken: dark `earth` was given
        // BasemapPalette's Background, which is a hair from its Water, so the whole map was a
        // flat navy field with no coastline. Asserted in every variant *and* muted, because
        // muting pulls colours toward the background and could collapse them again.
        let (earth, water) = (find("earth"), find("water"));
        for dark in [false, true] {
            for muted in [false, true] {
                let palette = Palette::new(dark, muted);
                let separation = distance(earth.color(palette), water.color(palette));
                assert!(
                    separation > 0.09,
                    "land and ocean are indistinguishable (dark={dark}, muted={muted}): \
                     distance {separation:.3}",
                );
            }
        }
    }

    #[test]
    fn the_backdrop_is_the_ocean_rather_than_the_land() {
        // An unloaded map should read as sea with land appearing on top, so a gap in coverage
        // never looks like a continent.
        let (earth, water) = (find("earth"), find("water"));
        for variant in [Variant::Light, Variant::Dark] {
            let backdrop = background(variant);
            let palette = Palette { variant, muted: false };
            assert!(
                distance(backdrop, water.color(palette)) < 0.02,
                "the backdrop should be the water colour in {variant:?}",
            );
            assert!(
                distance(backdrop, earth.color(palette)) > 0.09,
                "the backdrop must not look like land in {variant:?}",
            );
        }
    }

    #[test]
    fn the_dark_palette_is_actually_dark_and_the_light_one_light() {
        assert!(luminance(background(Variant::Dark)) < 0.2, "the dark backdrop must be dark");
        assert!(luminance(background(Variant::Light)) > 0.7, "the light backdrop must be light");
        for l in layers() {
            // Symbol layers are exempt: the authored style is light-only and label
            // colours are mid-grey by design (country #a3a3a3, region #b3b3b3), so
            // they read as labels on a dark basemap too. M1 keeps one column; the
            // dark label palette is M5 with the dark restyle.
            if l.kind == LayerKind::Symbol {
                continue;
            }
            assert!(
                luminance(l.dark) < 0.45,
                "{} is too bright for a dark basemap: {:.2}",
                l.id,
                luminance(l.dark),
            );
        }
    }

    /// Euclidean RGB distance, 0..~1.7.
    ///
    /// Luminance alone is the wrong measure for legibility: the light highway colour is a
    /// pale yellow whose luminance nearly matches the beige land behind it, and it is
    /// perfectly legible — which is why every real basemap draws highways that way. Hue
    /// separation has to count.
    fn distance(a: u32, b: u32) -> f32 {
        let channel = |v: u32, shift: u32| ((v >> shift) & 0xFF) as f32 / 255.0;
        let dr = channel(a, 16) - channel(b, 16);
        let dg = channel(a, 8) - channel(b, 8);
        let db = channel(a, 0) - channel(b, 0);
        (dr * dr + dg * dg + db * db).sqrt()
    }

    #[test]
    fn roads_stay_legible_against_the_land_behind_them() {
        // The reason BasemapPalette exists, and the reason the line colours are the app's own
        // rather than the authored white-on-grey: a road has to read against the terrain, and
        // in dark mode both are dark. Assert real separation rather than merely different hex.
        for dark in [false, true] {
            let palette = Palette::new(dark, false);
            let land = background(palette.variant);
            for id in ["roads-major", "roads-minor", "roads-highway"] {
                let road = find(id).color(palette);
                let separation = distance(road, land);
                assert!(
                    separation > 0.05,
                    "{id} is invisible against the land, dark={dark}: distance {separation:.3}",
                );
            }
        }
    }

    #[test]
    fn muting_moves_every_colour_toward_the_background() {
        // What weather needs, and what Positron gave it: the basemap recedes so a colour
        // ramp on top of it stays readable.
        for dark in [false, true] {
            let plain = Palette::new(dark, false);
            let muted = Palette::new(dark, true);
            let land = background(plain.variant);
            for l in layers() {
                let before = distance(l.color(plain), land);
                let after = distance(l.color(muted), land);
                assert!(
                    after <= before + 1e-6,
                    "{} got further from the land when muted, dark={dark}",
                    l.id,
                );
            }
            // And it actually does something measurable to a high-contrast layer.
            let road = find("roads-major");
            assert!(
                distance(road.color(muted), land) < distance(road.color(plain), land) * 0.75,
                "muting barely changed roads-major, dark={dark}",
            );
        }
    }

    #[test]
    fn muting_preserves_opacity() {
        // Muting shifts hue toward the background; it must not change alpha. Forcing a
        // translucent layer opaque would make `weather`'s muted basemap hide what it is
        // drawn over, and forcing an opaque one translucent would let the app's own surface
        // bleed through the map.
        for dark in [false, true] {
            for l in layers() {
                let plain = l.color(Palette::new(dark, false)) >> 24;
                let muted = l.color(Palette::new(dark, true)) >> 24;
                assert_eq!(plain, muted, "{} changed alpha when muted", l.id);
            }
        }
    }

    #[test]
    fn buildings_are_translucent_as_the_authored_style_draws_them() {
        // `fill-opacity: 0.5` in the authored style. Pinned because it is the one layer whose
        // translucency is load-bearing, and because it means tile overspill drawn twice would
        // darken visibly — unlike an opaque layer, where double-drawing is harmless.
        assert_eq!(find("buildings").opacity_at(16.0), 0.5);
    }

    #[test]
    fn a_dark_casing_is_darker_than_the_road_it_outlines() {
        // In dark mode the casing is what separates two adjacent roads, since the land
        // behind them is dark too. A casing lighter than its road would read as a second
        // road.
        let dark = Palette::new(true, false);
        for kind in ["minor", "major", "highway"] {
            let casing = luminance(find(&format!("roads-{kind}-casing")).color(dark));
            let road = luminance(find(&format!("roads-{kind}")).color(dark));
            assert!(casing < road, "the dark roads-{kind} casing must be darker than its road");
        }
    }

    #[test]
    fn a_variant_switch_changes_hue_and_nothing_else() {
        // Colour is a push constant and the layer set is one table read by both variants, so a
        // switch re-tessellates and re-uploads nothing. What could still go wrong is a variant
        // changing *alpha*, which changes blending rather than geometry — and would mean the
        // dark column had smuggled an opacity decision out of the ramp.
        for l in layers() {
            for muted in [false, true] {
                assert_eq!(
                    l.color(Palette::new(false, muted)) >> 24,
                    l.color(Palette::new(true, muted)) >> 24,
                    "{} changes alpha with the variant",
                    l.id,
                );
            }
        }
    }
}
