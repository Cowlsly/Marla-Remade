//! Which layers are drawn, in what order, in what colour - in light and dark.
//!
//! # Being replaced
//!
//! The constants below are a hand-written approximation of `style/basemap.json`, and they
//! could not converge on it: 34 `landuse` kinds, opacity ramps and per-kind colour cases are
//! already written down in a file MapLibre renders correctly, so transcribing it by eye was
//! never going to work. [`expr`] evaluates that file instead, and these constants go once it
//! is wired through.
//!
//! Deliberately flat and constant: no expressions, no data-driven paint. Phase 3 replaces
//! the constants with the small expression evaluator the plan scopes out (`interpolate`,
//! `step`, `case`, `match`, `get`, `coalesce`), and this is the seam that lets the
//! renderer be finished and profiled first.
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
        dash: NO_DASH,
        min_zoom: 0,
        max_zoom: 22,
    }
}

/// A `landuse` fill.
///
/// `min_zoom` is 6 because the authored style ramps this family's `fill-opacity` from 0 at
/// z6 to 1 at z11. Drawing it below that puts continent-sized `national_park`,
/// `nature_reserve` and `military` polygons on the map, and their tile-clipped edges read as
/// straight cuts slashed across the shape.
const fn landuse(
    id: &'static str,
    kinds: &'static [&'static str],
    light: u32,
    dark: u32,
) -> Layer {
    Layer { min_zoom: LANDUSE_MIN_ZOOM, ..layer(id, "landuse", LayerKind::Fill, kinds, light, dark) }
}

/// Where the authored style's `landuse` opacity ramp leaves zero.
pub const LANDUSE_MIN_ZOOM: u8 = 6;

/// A `landcover` tint: light in the authored style's own paint, dark from
/// `BasemapPalette.Fill.Landcover`, and gone above z7 where the authored style's opacity
/// ramp reaches zero.
const fn landcover(id: &'static str, kinds: &'static [&'static str], light: u32) -> Layer {
    Layer {
        max_zoom: LANDCOVER_MAX_ZOOM,
        ..layer(id, "landcover", LayerKind::Fill, kinds, light, 0xFF1F2A22)
    }
}

/// Where the authored style's `landcover` opacity ramp reaches zero.
pub const LANDCOVER_MAX_ZOOM: u8 = 7;

/// The stand-in style: enough to see the basemap and judge the geometry renderer.
///
/// The authored style is Phase 3's job and is scoped to ~15–20 layers against `maps`' 71
/// authored (141 at runtime). What matters here is that every *code path* the real style
/// needs is exercised: fills, plain strokes, casings via `line-gap-width`, and dashes
/// including the degenerate `[2, 0]`.
///
/// Layer and `kind` names follow the Protomaps v4 schema the archive is built to — as
/// `tilecodec`'s `v5ca_z11_tile.mvt` fixture shows.
pub fn layers() -> Vec<Layer> {
    vec![
        // The land itself, and the only thing separating it from the sea.
        //
        // Light is the authored style's own `earth` paint. Dark is **not**
        // `BasemapPalette.Fill.Background`, which is what `earth` maps to there: that palette
        // was built for a raster style where the background *is* the land, so collapsing the
        // two is harmless. Here earth is a polygon drawn over water, and giving it the
        // background colour erased the coastline outright — the map became a single flat
        // navy field with no way to tell land from ocean. So dark earth is lifted to a
        // neutral clearly above the water, in the same family as the palette's Buildings
        // (#22262C) and Other (#26282E).
        layer("earth", "earth", LayerKind::Fill, &[], 0xFFE2DFDA, 0xFF24262C),
        // Landcover is a **low-zoom tint**, not a landmass.
        //
        // The authored style ramps `fill-opacity` from 1 at z5 to 0 at z7, so above z7 it
        // is not drawn at all, and its colours are light pastels keyed on `kind`. Painting
        // it one solid colour at every zoom instead — which is what this did — lays a
        // blanket over the whole map that follows coarse vegetation polygons rather than
        // coastlines or borders, so it lines up with nothing. That is the single most
        // visible way to get a basemap wrong.
        //
        // Without expression support there is no opacity ramp yet, so the ramp's endpoint
        // becomes a hard `max_zoom` of 7. Per-kind colours are worth having because they
        // are most of what makes low zooms read correctly. In dark mode `BasemapPalette`
        // deliberately collapses every landcover kind to one value, so the dark column does
        // the same.
        landcover("landcover-grassland", &["grassland"], 0xFFD2EFCF),
        landcover("landcover-farmland", &["farmland"], 0xFFD8EFD2),
        landcover("landcover-scrub", &["scrub"], 0xFFEAEFD2),
        landcover("landcover-barren", &["barren"], 0xFFFFF3D7),
        landcover("landcover-glacier", &["glacier"], 0xFFFFFFFF),
        landcover("landcover-urban", &["urban_area"], 0xFFE6E6E6),
        // The authored style's fallback for every other kind.
        landcover("landcover-other", &[], 0xFFC4E7D2),
        // Water, including the ocean — `water.kind` really does contain `ocean`, so the sea
        // is a polygon rather than the backdrop showing through. `#80deea` is the authored
        // paint: a bright cyan, and deliberately nothing like `earth`. A washed-out blue
        // here is what made land and sea indistinguishable. Drawn further down, in the
        // authored position between landuse_runway and landuse_pedestrian.
        // Landuse, by the authored style's own grouping, paint **and order**.
        //
        // Order matters: the authored style draws `landuse_park` through `landuse_runway`
        // *before* `water` (indices 3-13 against water's 14), and only `landuse_pedestrian`
        // and `landuse_pier` after it. Drawing all of landuse after water instead puts parks
        // and industrial areas on top of rivers and coastline.
        //
        // Two things here were badly wrong before. Its `fill-opacity` ramps from 0 at z6 to
        // 1 at z11, so it must not be drawn at world zoom at all — drawing it everywhere put
        // continent-sized `national_park`, `nature_reserve` and `military` polygons on the
        // map, whose tile-clipped edges are the straight "random cuts" across the shapes.
        // And they are not all green: `military` is a pale blue-grey, `sand` is stone, and
        // the fallback is simply the earth colour.
        landuse(
            "landuse-park",
            &[
                "national_park",
                "park",
                "cemetery",
                "protected_area",
                "nature_reserve",
                "forest",
                "golf_course",
            ],
            0xFF9CD3B4,
            0xFF1E2B20,
        ),
        landuse("landuse-wood", &["wood"], 0xFFA0D9A0, 0xFF1E2B20),
        landuse("landuse-grass", &["scrub", "grassland", "grass", "meadow"], 0xFF99D2BB, 0xFF1F2A22),
        landuse(
            "landuse-urban-green",
            &["allotments", "village_green", "playground", "garden", "dog_park", "pitch"],
            0xFF9CD3B4,
            0xFF23362A,
        ),
        landuse("landuse-military", &["military", "naval_base", "airfield"], 0xFFC6DCDC, 0xFF26282E),
        landuse("landuse-zoo", &["zoo"], 0xFFC6DCDC, 0xFF213030),
        landuse("landuse-hospital", &["hospital"], 0xFFE4DAD9, 0xFF2B2528),
        landuse("landuse-industrial", &["industrial", "railway"], 0xFFD1DDE1, 0xFF20262B),
        landuse("landuse-school", &["school", "university", "college"], 0xFFE4DED7, 0xFF282520),
        landuse("landuse-beach", &["beach", "sand", "bare_rock"], 0xFFE8E4D0, 0xFF2C2A22),
        landuse("landuse-aerodrome", &["aerodrome"], 0xFFDADBDF, 0xFF212228),
        // Water sits here: after most of landuse, before pedestrian and pier, exactly as the
        // authored style orders them.
        layer("water", "water", LayerKind::Fill, &[], 0xFF80DEEA, 0xFF0D1B2A),
        landuse("landuse-pedestrian", &["pedestrian", "dam"], 0xFFE3E0D4, 0xFF242229),
        landuse("landuse-pier", &["pier"], 0xFFE0E0E0, 0xFF202225),
        // The authored style draws buildings at `fill-opacity` 0.5 over whatever is beneath,
        // so the alpha is part of the colour rather than a solid slab of grey.
        Layer {
            min_zoom: 14,
            ..layer(
                "buildings",
                "buildings",
                LayerKind::Fill,
                &["building", "building_part"],
                0x80CCCCCC,
                0xFF22262C,
            )
        },
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
        // The authored style ramps landcover's opacity to zero by z7. Drawing it at every
        // zoom lays a blanket over the map that follows vegetation polygons rather than
        // coastlines or borders, which lines up with nothing — the exact bug this pins.
        let layers = layers();
        let landcovers: Vec<&Layer> =
            layers.iter().filter(|l| l.source_layer == "landcover").collect();
        assert!(!landcovers.is_empty(), "landcover must still be drawn at low zoom");
        for l in &landcovers {
            assert_eq!(l.max_zoom, LANDCOVER_MAX_ZOOM, "{} must stop at z7", l.id);
            assert!(l.draws_at(4), "{} should tint low zooms", l.id);
            assert!(!l.draws_at(8), "{} must be gone by z8", l.id);
            assert!(!l.draws_at(14), "{} must not reach street level", l.id);
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
    fn the_unfiltered_landcover_layer_is_drawn_last_so_specific_kinds_win() {
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
                *index < fallback.0,
                "{} must precede the unfiltered fallback so its colour is not overpainted",
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
        // `fill-opacity: 0.5` in the authored style. Pinned because it is the one layer
        // whose alpha is load-bearing, and because it means tile overspill drawn twice
        // would darken visibly — unlike an opaque layer, where double-drawing is harmless.
        let layers = layers();
        let buildings = layers.iter().find(|l| l.id == "buildings").expect("buildings");
        assert_eq!(buildings.light >> 24, 0x80, "the authored style draws these at half alpha");
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
