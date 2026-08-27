//! Which layers are drawn, in what order, in what colour - in light and dark.
//!
//! # Where the fills come from
//!
//! Every `fill` layer in [`layers`] is **derived** from `style/basemap.json` rather than
//! transcribed: draw order, `source-layer`, the `kind` whitelist, the colour and the zoom gate
//! all come from the file, via [`paint::FillStyle`]. That file is what MapLibre renders
//! correctly, and the previous hand-written table could not converge on it — 34 `landuse`
//! kinds collapsed into 13 layers, opacity ramps approximated as hard zoom cutoffs, and no
//! fallback layer at all, so an unlisted `kind` simply was not drawn.
//!
//! Two things are still by hand, and both are deliberate:
//!
//! * **The dark column.** `basemap.json` is light-only.
//!   [`DARK_FILL`] is a per-authored-layer override, sourced from the contrast-checked values
//!   in `maps/src/main/java/com/vayunmathur/maps/ui/theme/BasemapPalette.kt` (see below). A
//!   missing entry is a test failure rather than a light colour rendered on a dark map.
//! * **The line layers.** Their widths already come from the authored ramps through
//!   [`paint::LineStyle`]; their colours, dashes and `kind` filters do not yet.
//!
//! Order in [`layers`] **is** draw order, and it is global rather than per tile: a road
//! casing from one tile must never cover the road fill of the tile next to it, so the
//! renderer draws layer-major across all tiles rather than tile-major across all layers.
//!
//! # Where the colours come from
//!
//! Not from a third-party style. `maps/src/main/java/com/vayunmathur/maps/ui/theme/BasemapPalette.kt`
//! already owns a **contrast-checked dark palette for this exact archive**, and its module
//! docs explain the reasoning: a road label does not sit on a surface, it sits on the
//! basemap, so deriving basemap colours from the Material scheme "would let an unlucky
//! accent produce grey-on-grey terrain". Light mode there is the bundled Protomaps style's
//! own paint and "the runtime recolour is what dark mode is".
//!
//! So the dark values here are `BasemapPalette.darkFill`'s, by role, and the light ones
//! are the Protomaps paint. Two consequences worth having: the schema matches by
//! construction — these are the colours written for `v4.pmtiles`' own layer names — and
//! the five consumer apps end up looking like the same product as `maps` rather than
//! merely adjacent to it.
//!
//! # Switching variants is free
//!
//! Colour reaches the GPU as a push constant and the layer *set* is identical between
//! variants, so flipping light to dark re-uploads nothing and re-tessellates nothing. That
//! is why this is a runtime switch rather than a startup choice: the app can follow the
//! system theme.

pub mod expr;
pub mod paint;

/// What pipeline a layer draws with.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum LayerKind {
    Fill,
    Line,
}

/// Light or dark basemap. The layer set is the same; only the paint differs.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Variant {
    Light,
    Dark,
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
/// palette to keep correct rather than four. Phase 3 may author it properly.
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

/// One drawn layer.
pub struct Layer {
    pub id: &'static str,
    /// The MVT layer to read, e.g. `roads`.
    pub source_layer: &'static str,
    pub kind: LayerKind,
    /// Feature `kind` values to draw, or empty for every feature in the layer.
    pub kinds: &'static [&'static str],
    /// ARGB in light mode: the Protomaps style's own paint.
    pub light: u32,
    /// ARGB in dark mode, from `BasemapPalette`'s contrast-checked palette.
    pub dark: u32,
    /// Stroke width in Dp. Ignored by fills, and superseded per frame by
    /// [`line_paint`](Self::line_paint)'s ramps wherever that is set.
    pub width_dp: f32,
    /// Gap between the two halves of a casing, in Dp. Non-zero turns the stroke into two
    /// bands standing off the centreline — how 17 layers of the real style draw road
    /// casings.
    ///
    /// Still load-bearing as a *boolean* even when [`line_paint`](Self::line_paint) supplies
    /// the distance: [`gapped`](Self::gapped) reads it, and that is what decides at
    /// tessellation time whether one centred band is emitted or two offset ones. A layer
    /// whose ramp has a gap but whose constant is zero would tessellate a plain stroke and
    /// silently discard the gap.
    pub gap_width_dp: f32,
    /// Authored `line` layer ids whose width ramps govern this layer, most specific first.
    ///
    /// Empty means the constants above stand. More than one is how the authored style's
    /// `*_casing_early`/`*_casing_late` pairs are followed: they are one layer split in two
    /// at z12, and [`paint::LineStyle::stroke`] picks whichever covers the camera's zoom.
    pub line_paint: &'static [&'static str],
    /// The authored `fill` layer id whose `fill-opacity` ramp governs this layer.
    ///
    /// Empty means fully opaque. Evaluated per frame by [`paint::FillStyle::opacity`] against
    /// the camera's fractional zoom, exactly as [`line_paint`](Self::line_paint)'s widths are —
    /// a constant alpha plus an integer zoom gate is what drew `landcover` at full strength at
    /// z6 where the authored ramp asks for half.
    pub fill_paint: &'static str,
    /// Dash and gap lengths in line widths, as `line-dasharray` defines them.
    pub dash: (f32, f32),
    pub min_zoom: u8,
    pub max_zoom: u8,
}

impl Layer {
    /// Does this layer draw a feature whose `kind` is `kind`?
    pub fn matches(&self, kind: Option<&str>) -> bool {
        self.kinds.is_empty() || kind.is_some_and(|k| self.kinds.contains(&k))
    }

    pub fn draws_at(&self, zoom: u8) -> bool {
        zoom >= self.min_zoom && zoom <= self.max_zoom
    }

    /// A casing: two bands offset either side of the centreline.
    pub fn gapped(&self) -> bool {
        self.gap_width_dp > 0.0
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
        matches!(self.source_layer, "earth" | "water")
    }
}

/// Behind everything, before any tile has loaded.
///
/// This is the **water** colour, not the land colour. The sea is the thing a world map is
/// mostly made of, `water.kind` includes `ocean`, and any area with no tile yet is far more
/// likely to be sea than land — so an unloaded map should read as ocean with land appearing
/// on top of it, rather than the reverse.
pub fn background(variant: Variant) -> u32 {
    match variant {
        // The authored style's own `water` paint.
        Variant::Light => 0xFF80DEEA,
        // BasemapPalette.Fill.Water.
        Variant::Dark => 0xFF0D1B2A,
    }
}

const NO_DASH: (f32, f32) = (0.0, 0.0);

/// A shorthand for the many fields a [`Layer`] has that most layers do not set.
const fn layer(
    id: &'static str,
    source_layer: &'static str,
    kind: LayerKind,
    kinds: &'static [&'static str],
    light: u32,
    dark: u32,
) -> Layer {
    Layer {
        id,
        source_layer,
        kind,
        kinds,
        light,
        dark,
        width_dp: 0.0,
        gap_width_dp: 0.0,
        line_paint: &[],
        fill_paint: "",
        dash: NO_DASH,
        min_zoom: 0,
        max_zoom: 22,
    }
}

/// The dark colour of each authored fill layer, RGB only.
///
/// `basemap.json` is light-only, so this is the one part of fill paint still maintained by
/// hand. Keyed by the id [`paint::FillDraw`] derives — the authored layer's own id, suffixed
/// with a `kind` for the two layers that paint more than one colour — so a restyle that
/// renames or re-splits a layer breaks the lookup loudly.
///
/// Alpha is **not** here: it comes from the authored `fill-opacity` in both variants, because
/// a translucent layer is translucent for a reason that has nothing to do with the theme.
///
/// Values are `BasemapPalette.darkFill`'s by role. Where the authored file has an arm the
/// palette has no counterpart for, the nearest role is used and noted.
const DARK_FILL: &[(&str, u32)] = &[
    // Not `BasemapPalette.Fill.Background`, which is what `earth` maps to there: that palette
    // was built for a raster style where the background *is* the land, so collapsing the two
    // is harmless. Here earth is a polygon drawn over water, and giving it the background
    // colour erased the coastline outright — the map became a single flat navy field with no
    // way to tell land from ocean. So dark earth is lifted to a neutral clearly above the
    // water, in the same family as the palette's Buildings (#22262C) and Other (#26282E).
    ("earth", 0x24262C),
    // Landcover's authored paint is near-white pastels that would wash out a dark map. Each is
    // a tint of the same hue held a few units off `earth`, which is what keeps a gap in
    // landcover coverage from reading as a different surface: earth is blue-dominant, so a
    // green-dominant tint of the same luminance shows up as a mauve patch where cover stops.
    ("landcover", 0x262B2A),
    ("landcover:grassland", 0x262C28),
    ("landcover:barren", 0x2C2A24),
    ("landcover:urban_area", 0x2A2B2E),
    ("landcover:farmland", 0x272D29),
    ("landcover:glacier", 0x34373D),
    ("landcover:scrub", 0x2A2C26),
    ("landuse_park:national_park", 0x1E2B20),
    ("landuse_park:wood", 0x1E2B20),
    ("landuse_park:scrub", 0x1F2A22),
    ("landuse_park:military", 0x26282E),
    ("landuse_urban_green", 0x23362A),
    ("landuse_hospital", 0x2B2528),
    ("landuse_industrial", 0x20262B),
    ("landuse_school", 0x282520),
    ("landuse_beach", 0x2C2A22),
    ("landuse_zoo", 0x213030),
    ("landuse_aerodrome", 0x212228),
    // No palette counterpart: the authored #e9e9ed is a shade off `landuse_aerodrome`'s
    // #dadbdf, so the dark value is held the same distance the other way.
    ("landuse_runway", 0x262931),
    // BasemapPalette.Fill.Water, and the same value `background` uses.
    ("water", 0x0D1B2A),
    ("landuse_pedestrian", 0x242229),
    ("landuse_pier", 0x202225),
    ("buildings", 0x22262C),
];

/// Zoom ranges the renderer imposes on a derived fill for its own reasons, not the style's.
///
/// A derived fill is otherwise gated by nothing but its authored `fill-opacity` ramp, and that
/// is deliberate. [`Layer::min_zoom`] and [`Layer::max_zoom`] are compared against the *tile's*
/// pyramid level in `tile::geometry::build`, so deriving them from the ramp — which is a
/// function of the *camera* — meant `landcover` geometry was never built for a tile deeper than
/// z6 even though the ramp wants it visible up to camera z7. Whether a shape existed then
/// depended on which pyramid level happened to be resident, so shapes appeared and vanished as
/// the camera zoomed. Cost is handled instead by the archive itself: `geometry::build` skips a
/// layer the tile does not contain, and Planetiler only puts `landcover` in shallow tiles and
/// `landuse`/`buildings` in deep ones.
///
/// `buildings` keeps a floor because that one *is* a cost decision rather than a paint one: it
/// is the densest layer in the schema, and a tessellation pass plus a draw call per tile is
/// worth avoiding even where the archive would return nothing.
const ZOOM_FLOOR: &[(&str, u8)] = &[("buildings", 14)];

/// The dark colour for a derived fill, or a safe neutral if the table has fallen behind.
///
/// A missing entry is pinned as a test failure by `every_derived_fill_has_a_dark_override`;
/// falling back to the dark earth here means the worst a stale table can do at runtime is
/// paint a layer the colour of the land, rather than a light colour on a dark map.
fn dark_fill(id: &str) -> u32 {
    const EARTH: u32 = 0x24262C;
    DARK_FILL.iter().find(|(key, _)| *key == id).map_or(EARTH, |(_, rgb)| *rgb)
}

/// Every layer the renderer draws, in draw order.
///
/// The fills are derived from `style/basemap.json`; the line layers below them are still a
/// hand-written table, though their widths come from the authored ramps through
/// [`paint::LineStyle`]. All fills precede all lines, which is both the authored order and what
/// `casings_are_drawn_under_their_fills` pins.
///
/// Layer and `kind` names follow the Protomaps v4 schema the archive is built to — as
/// `tilecodec`'s `v5ca_z11_tile.mvt` fixture shows.
pub fn layers() -> Vec<Layer> {
    let mut out: Vec<Layer> = paint::authored_fills().draws().map(fill_layer).collect();
    out.extend(line_layers());
    out
}

/// One derived fill draw as a [`Layer`].
///
/// Everything but the dark colour and the renderer's own zoom floor comes from the authored
/// file. The `&'static str`s borrow from the parsed style, which lives for the process — see
/// [`paint::authored_fills`].
///
/// The zoom range is the **whole** range unless [`ZOOM_FLOOR`] says otherwise: what a frame
/// shows is decided by the authored `fill-opacity` ramp against the camera, and the geometry has
/// to exist at every pyramid level the archive carries it at or shapes come and go with zoom.
fn fill_layer(draw: &'static paint::FillDraw) -> Layer {
    let min_zoom = ZOOM_FLOOR
        .iter()
        .find(|(id, _)| *id == draw.id)
        .map_or(0, |(_, floor)| *floor);
    Layer {
        id: &draw.id,
        source_layer: draw.source_layer,
        kind: LayerKind::Fill,
        kinds: &draw.kinds,
        light: draw.color,
        // Alpha comes from the authored colour itself in both variants; only the hue is themed.
        // The `fill-opacity` ramp is not baked in here — see `Layer::fill_paint`.
        dark: (draw.color & 0xFF00_0000) | dark_fill(&draw.id),
        width_dp: 0.0,
        gap_width_dp: 0.0,
        line_paint: &[],
        fill_paint: draw.authored,
        dash: NO_DASH,
        min_zoom,
        max_zoom: paint::MAX_ZOOM,
    }
}

/// The line layers, still hand-written.
///
/// Only the widths are authored, via [`Layer::line_paint`]. Colour, dashes and the `kind`
/// whitelists are transcribed, and the same derivation the fills now use would replace them —
/// but a line layer's colour is not the only thing to derive: `line-dasharray` is a `step`, and
/// the authored file splits a single road class across bridge, tunnel and casing layers.
fn line_layers() -> Vec<Layer> {
    vec![
        // Casings first, then fills, so each road reads as one line rather than a chain of
        // overlapping outlines. Both use line-gap-width. In dark mode a casing is *darker*
        // than the road it outlines, which is what separates two adjacent roads when the
        // land behind them is also dark.
        //
        // The widths below are the fallback only: `line_paint` names the authored ramps that
        // actually size these strokes, and the constants are what a road would be if the
        // authored file stopped naming it.
        Layer {
            width_dp: 1.0,
            gap_width_dp: 3.0,
            min_zoom: 13,
            line_paint: &["roads_minor_casing"],
            ..layer(
                "roads-minor-casing",
                "roads",
                LayerKind::Line,
                &["minor_road", "other"],
                0xFFD5D0C6,
                0xFF111318,
            )
        },
        Layer {
            width_dp: 1.0,
            gap_width_dp: 4.5,
            min_zoom: 9,
            line_paint: &["roads_major_casing_early", "roads_major_casing_late"],
            ..layer(
                "roads-major-casing",
                "roads",
                LayerKind::Line,
                &["major_road"],
                0xFFD8C9A6,
                0xFF111318,
            )
        },
        Layer {
            width_dp: 1.25,
            gap_width_dp: 6.0,
            line_paint: &["roads_highway_casing_early", "roads_highway_casing_late"],
            ..layer(
                "roads-highway-casing",
                "roads",
                LayerKind::Line,
                &["highway"],
                0xFFE0B96A,
                0xFF111318,
            )
        },
        Layer {
            width_dp: 1.0,
            dash: (2.0, 1.5),
            min_zoom: 15,
            // The authored layer that draws `path`, alongside `other`.
            line_paint: &["roads_other"],
            ..layer("roads-path", "roads", LayerKind::Line, &["path"], 0xFFC7BFAE, 0xFF26282E)
        },
        Layer {
            width_dp: 3.0,
            min_zoom: 13,
            line_paint: &["roads_minor"],
            ..layer(
                "roads-minor",
                "roads",
                LayerKind::Line,
                &["minor_road", "other"],
                0xFFFFFFFF,
                0xFF34383F,
            )
        },
        Layer {
            width_dp: 4.5,
            min_zoom: 9,
            line_paint: &["roads_major"],
            ..layer(
                "roads-major",
                "roads",
                LayerKind::Line,
                &["major_road"],
                0xFFFFF8E6,
                0xFF464B54,
            )
        },
        Layer {
            width_dp: 6.0,
            line_paint: &["roads_highway"],
            ..layer("roads-highway", "roads", LayerKind::Line, &["highway"], 0xFFFFE9B0, 0xFF464B54)
        },
        Layer {
            width_dp: 1.0,
            dash: (3.0, 3.0),
            min_zoom: 13,
            line_paint: &["roads_rail"],
            ..layer("roads-rail", "roads", LayerKind::Line, &["rail"], 0xFFC0BAB0, 0xFF3A3E45)
        },
        // The degenerate dash case: `boundaries_country` produces [2, 0] through a `step`,
        // and a zero gap has to render solid. See line.frag.
        Layer {
            width_dp: 1.0,
            dash: (2.0, 0.0),
            // One layer stands in for the authored `boundaries_country`/`boundaries` pair,
            // which differ by `kind_detail`; the country lines are what carries the map at
            // the zooms where boundaries dominate.
            line_paint: &["boundaries_country"],
            ..layer("boundaries", "boundaries", LayerKind::Line, &[], 0xFFA8A296, 0xFF4A4F57)
        },
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Perceived luminance, for asserting a palette is actually light or dark.
    fn luminance(argb: u32) -> f32 {
        let r = ((argb >> 16) & 0xFF) as f32 / 255.0;
        let g = ((argb >> 8) & 0xFF) as f32 / 255.0;
        let b = (argb & 0xFF) as f32 / 255.0;
        0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    #[test]
    fn landcover_is_a_low_zoom_tint_and_stops_before_street_level() {
        // The authored `fill-opacity` ramps landcover from 1 at z5 to 0 at z7, and that ramp is
        // the only thing that gates it. Drawing it at every zoom — which the transcribed table
        // did, because it had no ramp to read — lays a blanket over the map that follows
        // vegetation polygons rather than coastlines or borders, so it lines up with nothing.
        let layers = layers();
        let fills = paint::authored_fills();
        let landcovers: Vec<&Layer> =
            layers.iter().filter(|l| l.source_layer == "landcover").collect();
        assert!(!landcovers.is_empty(), "landcover must still be drawn at low zoom");
        for l in &landcovers {
            assert!(fills.opacity(l, 4.0) > 0.0, "{} should tint low zooms", l.id);
            assert_eq!(fills.opacity(l, 7.0), 0.0, "{} is at zero opacity by z7", l.id);
            assert_eq!(fills.opacity(l, 14.0), 0.0, "{} must not reach street level", l.id);
        }
    }

    /// The other half of the same derivation: `landuse_park`'s ramp is 0 at z6 rising to 1 at
    /// z11, so it must not be drawn at world zoom. Ignoring it put continent-sized
    /// `national_park`, `nature_reserve` and `military` polygons on the map, whose tile-clipped
    /// edges read as straight cuts slashed across the shape.
    #[test]
    fn the_landuse_park_family_is_gated_off_at_world_zoom() {
        let layers = layers();
        let fills = paint::authored_fills();
        let parks: Vec<&Layer> =
            layers.iter().filter(|l| l.id.starts_with("landuse_park")).collect();
        assert!(!parks.is_empty(), "the authored landuse_park layer must be derived");
        for l in &parks {
            assert_eq!(fills.opacity(l, 6.0), 0.0, "{} is at zero opacity at z6", l.id);
            assert_eq!(fills.opacity(l, 11.0), 1.0, "{} is fully on by z11", l.id);
        }
    }

    /// The gate that decides whether geometry is *built* must not come from the opacity ramp.
    ///
    /// `Layer::min_zoom`/`max_zoom` are compared against the **tile's** pyramid level in
    /// `tile::geometry::build`, while the ramp is a function of the **camera**. Deriving one
    /// from the other meant `landcover` was never tessellated for a tile deeper than z6 even
    /// though its ramp wants it visible up to camera z7 — so whether a shape existed depended on
    /// which pyramid level happened to be resident, and shapes appeared and vanished while
    /// zooming. Every ramp-driven fill therefore spans the whole zoom range.
    #[test]
    fn a_fills_zoom_range_is_not_derived_from_its_opacity_ramp() {
        for l in layers().iter().filter(|l| l.kind == LayerKind::Fill) {
            let floor = ZOOM_FLOOR.iter().find(|(id, _)| *id == l.id).map_or(0, |(_, z)| *z);
            assert_eq!(
                (l.min_zoom, l.max_zoom),
                (floor, paint::MAX_ZOOM),
                "`{}` carries a zoom gate that is not a declared cost floor",
                l.id,
            );
        }
    }

    /// The dark table is the only part of fill paint still written by hand, so a restyle that
    /// renames or re-splits a layer must not silently leave it painted light-on-dark.
    #[test]
    fn every_derived_fill_has_a_dark_override() {
        for l in layers().iter().filter(|l| l.kind == LayerKind::Fill) {
            assert!(
                DARK_FILL.iter().any(|(id, _)| *id == l.id),
                "`{}` is derived from basemap.json but has no entry in DARK_FILL",
                l.id,
            );
        }
        // And nothing stale in the other direction, which would be a colour nobody reads.
        let ids: Vec<&str> = layers().iter().map(|l| l.id).collect();
        for (id, _) in DARK_FILL {
            assert!(ids.contains(id), "DARK_FILL names `{id}`, which no layer is derived as");
        }
    }

    /// Fill paint comes from the file, not from this module. Spot-checked against the authored
    /// values rather than asserting a count, so a restyle changes one colour rather than
    /// breaking the test.
    #[test]
    fn fill_colour_is_the_authored_colour() {
        let layers = layers();
        let color = |id: &str| {
            layers.iter().find(|l| l.id == id).unwrap_or_else(|| panic!("{id}")).light
        };
        assert_eq!(color("earth"), 0xFFE2DFDA, "the authored `#e2dfda`");
        assert_eq!(color("water"), 0xFF80DEEA, "the authored `#80deea`");
        // A `match` arm written as `rgba(210, 239, 207, 1)`, spaces and all.
        assert_eq!(color("landcover:grassland"), 0xFFD2EFCF);
        // A `case` arm reached through `landuse_park`'s `in` conditions.
        assert_eq!(color("landuse_park:military"), 0xFFC6DCDC);
        // `fill-opacity` is a per-frame ramp now, not a baked alpha: see
        // `paint::fill_opacity_is_a_ramp_evaluated_per_frame_not_a_baked_alpha`.
        assert_eq!(color("buildings"), 0xFFCCCCCC);
        assert_eq!(color("landuse_urban_green"), 0xFF9CD3B4);
    }

    /// A kind the authored `case` gives its own colour must get its own layer, and a kind that
    /// shares a colour with an earlier arm must share its layer rather than adding a draw.
    #[test]
    fn a_data_driven_fill_becomes_one_layer_per_colour() {
        let layers = layers();
        let park: Vec<&Layer> =
            layers.iter().filter(|l| l.id.starts_with("landuse_park")).collect();
        // Four distinct colours across the authored `case`'s reachable arms.
        assert_eq!(park.len(), 4, "{:?}", park.iter().map(|l| l.id).collect::<Vec<_>>());
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
        let layers = layers();
        for palette in [Palette::new(false, false), Palette::new(true, false)] {
            let mut seen: Vec<(u32, &str)> = Vec::new();
            for l in layers.iter().filter(|l| l.source_layer == "landcover") {
                let colour = l.color(palette);
                if let Some((_, other)) = seen.iter().find(|(c, _)| *c == colour) {
                    panic!("{} and {} share {colour:#010X} in {palette:?}", l.id, other);
                }
                seen.push((colour, l.id));
            }
            assert_eq!(seen.len(), 7, "every authored `match` arm needs a layer here");
        }
    }

    #[test]
    fn landcover_kinds_are_lighter_than_the_earth_they_tint() {
        // A tint sits *on* the land, so in light mode it must not be darker than the land
        // itself or it reads as a separate landmass.
        let layers = layers();
        let earth = layers.iter().find(|l| l.id == "earth").expect("earth");
        for l in layers.iter().filter(|l| l.source_layer == "landcover") {
            assert!(
                luminance(l.light) > luminance(earth.light) - 0.06,
                "{} is darker than earth, so it reads as land rather than a tint",
                l.id,
            );
        }
    }

    #[test]
    fn the_unfiltered_landcover_layer_is_drawn_first_so_specific_kinds_win() {
        // The authored style's `match` has a fallback arm; here that is a separate
        // unfiltered layer, which only behaves like a fallback if it draws under nothing
        // else — i.e. it must come first, before the kind-specific ones paint over it.
        let layers = layers();
        let indices: Vec<(usize, &Layer)> = layers
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
    fn casings_are_drawn_under_their_fills() {
        // Order is draw order, and the renderer draws layer-major so it holds across
        // tiles. A casing drawn after its fill would outline the road on top of itself.
        let layers = layers();
        let index = |id: &str| layers.iter().position(|l| l.id == id).expect(id);
        for kind in ["minor", "major", "highway"] {
            assert!(
                index(&format!("roads-{kind}-casing")) < index(&format!("roads-{kind}")),
                "roads-{kind}-casing must precede roads-{kind}",
            );
        }
        assert!(index("earth") < index("water"), "water draws over earth");
        assert!(index("water") < index("roads-major"), "roads draw over water");
    }

    #[test]
    fn a_casing_is_gapped_and_a_plain_stroke_is_not() {
        let layers = layers();
        let find = |id: &str| layers.iter().find(|l| l.id == id).expect(id);
        assert!(find("roads-major-casing").gapped());
        assert!(!find("roads-major").gapped());
        assert!(!find("earth").gapped());
    }

    #[test]
    fn the_kind_filter_is_a_whitelist_and_empty_means_everything() {
        let layers = layers();
        let find = |id: &str| layers.iter().find(|l| l.id == id).expect(id);
        let highway = find("roads-highway");
        assert!(highway.matches(Some("highway")));
        assert!(!highway.matches(Some("major_road")));
        assert!(!highway.matches(None), "a feature with no kind is not a highway");

        let earth = find("earth");
        assert!(earth.matches(None), "an unfiltered layer draws everything");
        assert!(earth.matches(Some("anything")));
    }

    #[test]
    fn zoom_ranges_gate_the_expensive_layers() {
        let layers = layers();
        let find = |id: &str| layers.iter().find(|l| l.id == id).expect(id);
        // Buildings are the densest layer in the schema, so they stay off until they are
        // worth drawing.
        assert!(!find("buildings").draws_at(13));
        assert!(find("buildings").draws_at(14));
        assert!(find("earth").draws_at(0));
        assert!(find("earth").draws_at(22));
    }

    #[test]
    fn the_degenerate_dash_is_present_so_the_shader_path_is_exercised() {
        let layers = layers();
        let boundaries = layers.iter().find(|l| l.id == "boundaries").expect("boundaries");
        assert_eq!(boundaries.dash, (2.0, 0.0), "the [2, 0] pattern must reach the shader");
    }

    #[test]
    fn every_layer_id_is_unique() {
        let layers = layers();
        let mut ids: Vec<&str> = layers.iter().map(|l| l.id).collect();
        ids.sort_unstable();
        let count = ids.len();
        ids.dedup();
        assert_eq!(count, ids.len(), "layer ids are used as identities");
    }

    #[test]
    fn a_line_layer_has_a_width_and_a_fill_does_not() {
        for l in layers() {
            match l.kind {
                LayerKind::Line => assert!(l.width_dp > 0.0, "{} needs a width", l.id),
                LayerKind::Fill => assert_eq!(l.width_dp, 0.0, "{} is a fill", l.id),
            }
        }
    }

    // --- light and dark ----------------------------------------------------

    #[test]
    fn every_layer_defines_both_variants_and_they_differ() {
        // A layer that forgot its dark colour would render its light one on a dark
        // background, which is the single most visible way to get this wrong.
        for l in layers() {
            assert_ne!(l.light, l.dark, "{} has the same colour in both variants", l.id);
            // Translucency is legitimate — the authored style draws buildings at
            // `fill-opacity` 0.5 — but a fully transparent layer is a layer that silently
            // does nothing.
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
        let layers = layers();
        let earth = layers.iter().find(|l| l.id == "earth").expect("earth");
        let water = layers.iter().find(|l| l.id == "water").expect("water");
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
        let layers = layers();
        let earth = layers.iter().find(|l| l.id == "earth").expect("earth");
        let water = layers.iter().find(|l| l.id == "water").expect("water");
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
        // The reason BasemapPalette exists: a road has to read against the terrain, and in
        // dark mode both are dark. Assert real separation rather than merely different hex.
        let layers = layers();
        let find = |id: &str| layers.iter().find(|l| l.id == id).expect(id);
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
        let layers = layers();
        for dark in [false, true] {
            let plain = Palette::new(dark, false);
            let muted = Palette::new(dark, true);
            let land = background(plain.variant);
            for l in &layers {
                let before = distance(l.color(plain), land);
                let after = distance(l.color(muted), land);
                assert!(
                    after <= before + 1e-6,
                    "{} got further from the land when muted, dark={dark}",
                    l.id,
                );
            }
            // And it actually does something measurable to a high-contrast layer.
            let road = layers.iter().find(|l| l.id == "roads-major").expect("roads-major");
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
        //
        // The 0.5 lives in the authored ramp and is applied per frame, so it is asserted
        // through `FillStyle::opacity` rather than on the layer's own alpha.
        let layers = layers();
        let buildings = layers.iter().find(|l| l.id == "buildings").expect("buildings");
        assert_eq!(paint::authored_fills().opacity(buildings, 16.0), 0.5);
    }

    #[test]
    fn a_dark_casing_is_darker_than_the_road_it_outlines() {
        // In dark mode the casing is what separates two adjacent roads, since the land
        // behind them is dark too. A casing lighter than its road would read as a second
        // road.
        let layers = layers();
        let find = |id: &str| layers.iter().find(|l| l.id == id).expect(id);
        let dark = Palette::new(true, false);
        for kind in ["minor", "major", "highway"] {
            let casing = luminance(find(&format!("roads-{kind}-casing")).color(dark));
            let road = luminance(find(&format!("roads-{kind}")).color(dark));
            assert!(casing < road, "the dark roads-{kind} casing must be darker than its road");
        }
    }

    #[test]
    fn switching_variant_changes_no_geometry() {
        // Colour is a push constant and the layer set is identical, so a variant switch
        // re-tessellates and re-uploads nothing. This pins that: the two lists must agree
        // on everything except colour.
        let light = layers();
        let dark = layers();
        assert_eq!(light.len(), dark.len());
        for (a, b) in light.iter().zip(dark.iter()) {
            assert_eq!(a.id, b.id);
            assert_eq!(a.source_layer, b.source_layer);
            assert_eq!(a.kind, b.kind);
            assert_eq!(a.kinds, b.kinds);
            assert_eq!(a.width_dp, b.width_dp);
            assert_eq!(a.gap_width_dp, b.gap_width_dp);
            assert_eq!(a.dash, b.dash);
            assert_eq!((a.min_zoom, a.max_zoom), (b.min_zoom, b.max_zoom));
        }
    }
}
