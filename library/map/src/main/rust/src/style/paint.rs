//! The flat style: a file that says only what this renderer can draw, and a loader for it.
//!
//! `style/basemap.flat.json` is one entry per drawn layer, in draw order, with a fixed set of
//! properties: a source layer, a `kind` whitelist, a zoom range, a light and a dark colour, and
//! opacity, width, gap width and dash. Nothing is derived at runtime and there are no
//! expressions — a property that varies with zoom is a list of stops and an interpolation, and
//! that is the only shape a property can take.
//!
//! # Why a stops list rather than a number
//!
//! A stroke width is not a property of a layer, it is a function of the camera's zoom, and no
//! single number can stand in for one: a width that reads correctly at street level is
//! continent-wide at world level. The same is true of a fill's opacity, which is the whole of
//! how the low zooms are meant to look — `landcover` fades out between z5 and z7, and
//! `landuse_park` fades in between z6 and z11. Collapsing either to a constant plus an integer
//! zoom gate drew `landcover` at **full** strength at z6 where the ramp asks for half, laying a
//! flat mint blanket over a continent.
//!
//! Evaluating a [`Ramp`] per frame is affordable because both reach the GPU as push constants:
//! `shaders/line.vert` extrudes the centreline to the width it is given, so a width that
//! changes every frame re-tessellates nothing. It is per *frame* and not per tile because the
//! camera's zoom is fractional and continuous, and quantising it to the integer tile zoom is
//! exactly what makes a layer pop instead of fade.
//!
//! # Where the values come from
//!
//! `style/basemap.json`, the 71-layer MapLibre style this used to interpret at runtime, is kept
//! vendored beside the flat file for one reason: `the_flat_style_agrees_with_basemap_json`
//! cross-checks every value that exists in both, so a transcription slip fails a build instead
//! of being found on a screenshot. Two columns are deliberately **ours** and are not
//! cross-checked:
//!
//! * **The dark colours.** `basemap.json` is light-only. These are
//!   `maps/src/main/java/com/vayunmathur/maps/ui/theme/BasemapPalette.kt`'s contrast-checked
//!   values by role — see [`super`]'s module docs for why that palette rather than a third
//!   party's.
//! * **The line colours.** The authored file draws roads white on grey casings; these are the
//!   warmer set the app has always drawn, and `roads_stay_legible_against_the_land_behind_them`
//!   is what holds them honest.
//!
//! One flattening is worth naming. The authored style splits two road casings into
//! `*_casing_early`/`*_casing_late` pairs at z12, one gated by `maxzoom` and the other by
//! `minzoom`. A flat layer carries one ramp, so it carries the `_late` half, which is the one
//! that spans the whole range. Where the casing is wide enough to draw at all the halves differ
//! by at most 0.4 Dp — a little over half a pixel per edge on a density-3 screen — and the worst
//! of that is immediately below the z12 split, which is where two ramps meeting at a point are
//! furthest apart. `a_collapsed_casing_pair_matches_its_authored_late_half` pins the bound.

use super::{Layer, LayerKind};
use serde_json::Value as Json;
use std::sync::OnceLock;

/// The flat style, vendored beside the sources.
///
/// Compiled in rather than read from assets: it is one file for every host app, and it makes
/// the loader host-testable rather than reachable only from a device.
const FLAT: &str = include_str!("../../style/basemap.flat.json");

/// The deepest zoom the renderer draws at, and the default top of a layer's zoom range.
///
/// The archive stops at z14 and the renderer overzooms past it.
pub const MAX_ZOOM: u8 = 22;

/// The narrowest half-width worth drawing, in device pixels.
///
/// The ramps go continuously to zero and the line pipeline is single-sampled with no coverage
/// term in its shader, so a quad narrower than a pixel is filled only where it happens to
/// straddle a pixel centre. That rasterises as stipple which crawls along the road while
/// panning, rather than as the faint continuous line the ramp is asking for. Holding it at one
/// pixel wide is what a renderer that antialiased its line edges would end up drawing anyway.
pub const MIN_HALF_WIDTH_PX: f32 = 0.5;

/// A line's stroke at one zoom, in Dp.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Stroke {
    pub width_dp: f32,
    pub gap_width_dp: f32,
}

impl Stroke {
    pub const NONE: Stroke = Stroke { width_dp: 0.0, gap_width_dp: 0.0 };

    /// Would this stroke put anything on screen?
    ///
    /// A casing with a gap but no width is two bands of zero thickness, so width alone decides.
    /// A zoom where this is false is a zoom the style ramped the layer out at, which is the
    /// style's own gate and the reason road layers need no `min_zoom`.
    pub fn visible(&self) -> bool {
        self.width_dp > 0.0
    }

    /// Half-width and half-gap in device pixels, which is what the vertex shader extrudes by.
    ///
    /// Halved because the shader offsets each edge from the centreline. The width is floored at
    /// [`MIN_HALF_WIDTH_PX`]; the gap is not, since two bands standing a sub-pixel distance
    /// apart simply read as one band of their combined thickness.
    pub fn half_px(&self, density: f32) -> (f32, f32) {
        (
            (self.width_dp * density / 2.0).max(MIN_HALF_WIDTH_PX),
            self.gap_width_dp * density / 2.0,
        )
    }
}

/// A property as a function of zoom: a list of stops and how to interpolate between them.
///
/// The whole of what the flat format can say about a varying value, and enough for every ramp
/// the authored style uses. A constant is a single stop, so nothing downstream has to branch on
/// whether a property varies.
#[derive(Clone, Debug, PartialEq)]
pub struct Ramp {
    /// The exponential base, or `1.0` for linear.
    base: f64,
    /// Zoom and value, ascending by zoom, never empty.
    stops: Vec<(f64, f32)>,
}

impl Ramp {
    /// A property that does not vary.
    pub fn constant(value: f32) -> Ramp {
        Ramp { base: 1.0, stops: vec![(0.0, value)] }
    }

    /// The value at `zoom`, clamped to the first and last stop outside the ramp's range.
    ///
    /// The exponential curve is the style spec's: `t = (base^dz - 1) / (base^span - 1)`, which
    /// is what makes a road grow slowly at low zoom and quickly at high.
    pub fn at(&self, zoom: f64) -> f32 {
        let last = self.stops.len() - 1;
        if zoom <= self.stops[0].0 {
            return self.stops[0].1;
        }
        if zoom >= self.stops[last].0 {
            return self.stops[last].1;
        }
        let index = self.stops.windows(2).position(|pair| zoom <= pair[1].0).unwrap_or(0);
        let (lower_zoom, lower) = self.stops[index];
        let (upper_zoom, upper) = self.stops[index + 1];
        let span = upper_zoom - lower_zoom;
        let t = if span <= 0.0 {
            0.0
        } else if self.base == 1.0 {
            (zoom - lower_zoom) / span
        } else {
            (self.base.powf(zoom - lower_zoom) - 1.0) / (self.base.powf(span) - 1.0)
        };
        lower + (upper - lower) * t as f32
    }

    /// The largest value any stop takes.
    ///
    /// Interpolation never leaves the interval between two stops, so this bounds the whole ramp
    /// — which is what makes it the right answer to "does this layer have a gap at all", a
    /// question [`Layer::gapped`] has to answer once rather than per zoom.
    pub fn peak(&self) -> f32 {
        self.stops.iter().fold(f32::NEG_INFINITY, |peak, (_, value)| peak.max(*value))
    }

    fn parse(json: Option<&Json>, id: &str, property: &str, default: f32) -> Result<Ramp, String> {
        let Some(json) = json else {
            return Ok(Ramp::constant(default));
        };
        if let Some(value) = json.as_f64() {
            return Ok(Ramp::constant(value as f32));
        }
        let where_ = || format!("`{id}`'s {property}");
        let base = match json.get("interpolate").and_then(Json::as_str) {
            Some("linear") => 1.0,
            Some("exponential") => json
                .get("base")
                .and_then(Json::as_f64)
                .ok_or_else(|| format!("{}: an exponential ramp needs a `base`", where_()))?,
            other => {
                return Err(format!("{}: unknown interpolation {other:?}", where_()));
            }
        };
        let stops: Vec<(f64, f32)> = json
            .get("stops")
            .and_then(Json::as_array)
            .ok_or_else(|| format!("{}: a ramp needs `stops`", where_()))?
            .iter()
            .map(|stop| match stop.as_array().map(|pair| pair.as_slice()) {
                Some([zoom, value]) => match (zoom.as_f64(), value.as_f64()) {
                    (Some(zoom), Some(value)) => Ok((zoom, value as f32)),
                    _ => Err(format!("{}: a stop must be two numbers", where_())),
                },
                _ => Err(format!("{}: a stop must be `[zoom, value]`", where_())),
            })
            .collect::<Result<_, _>>()?;
        if stops.is_empty() {
            return Err(format!("{}: a ramp needs at least one stop", where_()));
        }
        // Ascending zooms are what `at`'s scan relies on, and what makes clamping to the
        // first and last stop mean what it says.
        if stops.windows(2).any(|pair| pair[1].0 <= pair[0].0) {
            return Err(format!("{}: stops must ascend by zoom", where_()));
        }
        Ok(Ramp { base, stops })
    }
}

/// The whole style: the backdrop, and every layer in draw order.
pub struct Style {
    /// ARGB behind everything, light and dark.
    pub background: (u32, u32),
    pub layers: Vec<Layer>,
}

/// Parse a flat style file.
pub fn parse(source: &str) -> Result<Style, String> {
    let root: Json =
        serde_json::from_str(source).map_err(|e| format!("the flat style is not JSON: {e}"))?;
    let background = root.get("background").ok_or("the flat style has no `background`")?;
    let layers = root
        .get("layers")
        .and_then(Json::as_array)
        .ok_or("the flat style has no `layers` array")?;
    Ok(Style {
        background: (
            color(background.get("light"), "background.light")?,
            color(background.get("dark"), "background.dark")?,
        ),
        layers: layers.iter().map(layer).collect::<Result<_, _>>()?,
    })
}

fn layer(json: &Json) -> Result<Layer, String> {
    let string = |key: &str| -> Result<String, String> {
        json.get(key)
            .and_then(Json::as_str)
            .map(str::to_string)
            .ok_or_else(|| format!("a layer has no `{key}`: {json}"))
    };
    let id = string("id")?;
    // Every property this format has. A hand-authored file is the source of truth now, so a
    // misspelling has to be a load failure: `gapwidth` or `minZoom` would otherwise take the
    // default and render something plausible that nobody asked for.
    const KNOWN: &[&str] = &[
        "id",
        "authored",
        "source",
        "type",
        "kinds",
        "light",
        "dark",
        "opacity",
        "width",
        "gap_width",
        "dash",
        "minzoom",
        "maxzoom",
    ];
    for key in json.as_object().ok_or_else(|| format!("`{id}` is not an object"))?.keys() {
        if !KNOWN.contains(&key.as_str()) {
            return Err(format!("`{id}` has an unknown property `{key}`"));
        }
    }
    let kind = match json.get("type").and_then(Json::as_str) {
        Some("fill") => LayerKind::Fill,
        Some("line") => LayerKind::Line,
        other => return Err(format!("`{id}` has an unknown type {other:?}")),
    };
    let zoom = |key: &str, default: u8| -> Result<u8, String> {
        match json.get(key) {
            None => Ok(default),
            Some(value) => value
                .as_u64()
                .filter(|z| *z <= MAX_ZOOM as u64)
                .map(|z| z as u8)
                .ok_or_else(|| {
                    format!("`{id}`'s {key} must be a whole zoom in 0..={MAX_ZOOM}")
                }),
        }
    };
    let dash = match json.get("dash").and_then(Json::as_array).map(|d| d.as_slice()) {
        None => (0.0, 0.0),
        Some([on, off]) => match (on.as_f64(), off.as_f64()) {
            (Some(on), Some(off)) => (on as f32, off as f32),
            _ => return Err(format!("`{id}`'s dash must be two numbers")),
        },
        Some(_) => return Err(format!("`{id}`'s dash must be `[on, off]`")),
    };
    Ok(Layer {
        source_layer: string("source")?,
        authored: string("authored")?,
        kind,
        kinds: match json.get("kinds") {
            None => Vec::new(),
            Some(Json::Array(kinds)) => kinds
                .iter()
                .map(|kind| {
                    kind.as_str()
                        .map(str::to_string)
                        .ok_or_else(|| format!("`{id}`'s kinds must be strings"))
                })
                .collect::<Result<_, _>>()?,
            Some(_) => return Err(format!("`{id}`'s kinds must be an array")),
        },
        light: color(json.get("light"), &id)?,
        dark: color(json.get("dark"), &id)?,
        opacity: Ramp::parse(json.get("opacity"), &id, "opacity", 1.0)?,
        width: Ramp::parse(json.get("width"), &id, "width", 0.0)?,
        gap_width: Ramp::parse(json.get("gap_width"), &id, "gap_width", 0.0)?,
        dash,
        min_zoom: zoom("minzoom", 0)?,
        max_zoom: zoom("maxzoom", MAX_ZOOM)?,
        id,
    })
}

/// Parse a `#rrggbb` or `#rrggbbaa` colour into ARGB.
///
/// The one spelling the flat file uses. `basemap.json`'s `rgba(...)` form is gone from the
/// production path along with the evaluator that needed it; the cross-check test reads it,
/// because the authored file still writes seven colours that way.
fn color(json: Option<&Json>, what: &str) -> Result<u32, String> {
    let source = json
        .and_then(Json::as_str)
        .ok_or_else(|| format!("`{what}` has no colour string"))?;
    parse_hex(source).ok_or_else(|| format!("`{what}`'s colour `{source}` will not parse"))
}

/// `#rrggbb` or `#rrggbbaa` to ARGB, or `None`.
///
/// `None` rather than a default: a colour that will not parse must fail the load, because any
/// substituted colour is a plausible-looking wrong map.
fn parse_hex(source: &str) -> Option<u32> {
    let hex = source.strip_prefix('#')?;
    // Explicitly, rather than leaving it to `from_str_radix`, which accepts a leading `+`.
    if !hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    let pair = |i: usize| u32::from_str_radix(&hex[i..i + 2], 16).ok();
    match hex.len() {
        6 => Some(0xFF00_0000 | (pair(0)? << 16) | (pair(2)? << 8) | pair(4)?),
        8 => Some((pair(6)? << 24) | (pair(0)? << 16) | (pair(2)? << 8) | pair(4)?),
        _ => None,
    }
}

/// The vendored flat style, parsed once.
///
/// Immutable and derived from a compiled-in string, so this is a constant table that happens to
/// need a parser. Keeping it here rather than threading it from Kotlin also keeps the render and
/// tessellation threads reading the same paint without having to agree on it across JNI.
///
/// A parse failure panics. The file ships inside the binary and
/// `the_vendored_flat_style_parses` fails the build if it will not load, so the alternative — a
/// blank map with no diagnostic — is strictly worse than a crash that names the line.
pub fn style() -> &'static Style {
    static STYLE: OnceLock<Style> = OnceLock::new();
    STYLE.get_or_init(|| parse(FLAT).unwrap_or_else(|e| panic!("style/basemap.flat.json: {e}")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::style::{layers, Layer, LayerKind};

    /// The archive's zoom range, plus the overzoom the renderer allows past it.
    const ZOOMS: std::ops::RangeInclusive<i32> = 0..=22;

    /// The authored MapLibre style, for the cross-check only.
    const BASEMAP: &str = include_str!("../../style/basemap.json");

    fn line_layers() -> Vec<&'static Layer> {
        layers().iter().filter(|layer| layer.kind == LayerKind::Line).collect()
    }

    fn find(id: &str) -> &'static Layer {
        layers().iter().find(|layer| layer.id == id).unwrap_or_else(|| panic!("{id}"))
    }

    // --- the loader --------------------------------------------------------

    #[test]
    fn the_vendored_flat_style_parses() {
        let style = parse(FLAT).expect("style/basemap.flat.json should parse");
        // 24 fills and 9 lines. Asserted exactly: the file is the layer set, so a layer
        // appearing or disappearing is a decision rather than an incidental restyle.
        let fills = style.layers.iter().filter(|l| l.kind == LayerKind::Fill).count();
        let lines = style.layers.iter().filter(|l| l.kind == LayerKind::Line).count();
        assert_eq!((fills, lines), (24, 9));
        assert_eq!(style.background, (0xFF80DEEA, 0xFF0D1B2A));
    }

    #[test]
    fn a_malformed_style_fails_to_load_rather_than_loading_partly() {
        let cases = [
            (r##"{}"##, "no background"),
            (r##"{"background":{"light":"#fff","dark":"#000"},"layers":[]}"##, "a bad colour"),
            (
                r##"{"background":{"light":"#ffffff","dark":"#000000"},
                    "layers":[{"id":"x","authored":"x","source":"s","type":"blob",
                               "light":"#ffffff","dark":"#000000"}]}"##,
                "an unknown type",
            ),
            (
                r##"{"background":{"light":"#ffffff","dark":"#000000"},
                    "layers":[{"id":"x","authored":"x","source":"s","type":"fill",
                               "light":"#ffffff","dark":"#000000",
                               "width":{"interpolate":"exponential","stops":[[1,2]]}}]}"##,
                "an exponential ramp with no base",
            ),
            (
                r##"{"background":{"light":"#ffffff","dark":"#000000"},
                    "layers":[{"id":"x","authored":"x","source":"s","type":"fill",
                               "light":"#ffffff","dark":"#000000",
                               "width":{"interpolate":"linear","stops":[[5,1],[3,0]]}}]}"##,
                "descending stops",
            ),
            (
                r##"{"background":{"light":"#ffffff","dark":"#000000"},
                    "layers":[{"id":"x","authored":"x","source":"s","type":"fill",
                               "light":"#ffffff","dark":"#000000","gapwidth":3}]}"##,
                "a misspelled property",
            ),
            (
                r##"{"background":{"light":"#ffffff","dark":"#000000"},
                    "layers":[{"id":"x","authored":"x","source":"s","type":"fill",
                               "light":"#ffffff","dark":"#000000","minzoom":14.0}]}"##,
                "a fractional minzoom",
            ),
            (
                r##"{"background":{"light":"#ffffff","dark":"#000000"},
                    "layers":[{"id":"x","authored":"x","source":"s","type":"fill",
                               "light":"#ffffff","dark":"#000000","maxzoom":40}]}"##,
                "a zoom past the renderer's own maximum",
            ),
        ];
        for (source, what) in cases {
            assert!(parse(source).is_err(), "{what} should not load");
        }
    }

    #[test]
    fn a_hex_colour_parses_and_anything_else_does_not() {
        assert_eq!(parse_hex("#80deea"), Some(0xFF80DEEA));
        assert_eq!(parse_hex("#E2DFDA"), Some(0xFFE2DFDA), "case does not matter");
        assert_eq!(parse_hex("#11223380"), Some(0x80112233));
        for source in ["", "#", "#abc", "#12345", "#gg0000", "cornflowerblue", "rgb(1,2,3)"] {
            assert_eq!(parse_hex(source), None, "{source:?} must not parse");
        }
        // `from_str_radix` would take these; a colour column must not.
        assert_eq!(parse_hex("#+12345"), None);
        assert_eq!(parse_hex("#12 345"), None);
    }

    // --- ramps -------------------------------------------------------------

    #[test]
    fn a_ramp_clamps_outside_its_stops_and_interpolates_inside() {
        let ramp = Ramp { base: 1.0, stops: vec![(5.0, 1.0), (7.0, 0.0)] };
        assert_eq!(ramp.at(0.0), 1.0, "clamped below the first stop");
        assert_eq!(ramp.at(5.0), 1.0);
        assert_eq!(ramp.at(6.0), 0.5, "halfway, linearly");
        assert_eq!(ramp.at(7.0), 0.0);
        assert_eq!(ramp.at(22.0), 0.0, "clamped above the last");
        assert_eq!(ramp.peak(), 1.0);
    }

    #[test]
    fn an_exponential_ramp_grows_slowly_at_first_as_the_spec_defines_it() {
        let ramp = Ramp { base: 1.6, stops: vec![(0.0, 0.0), (2.0, 1.0)] };
        // (1.6^1 - 1) / (1.6^2 - 1) = 0.6 / 1.56.
        assert!((ramp.at(1.0) - 0.3846).abs() < 1e-4, "{}", ramp.at(1.0));
        assert!(ramp.at(1.0) < 0.5, "an exponential curve lags a linear one");
        assert_eq!(Ramp { base: 1.0, stops: vec![(0.0, 0.0), (2.0, 1.0)] }.at(1.0), 0.5);
    }

    #[test]
    fn a_constant_is_a_ramp_with_one_stop() {
        let ramp = Ramp::constant(0.7);
        for tenth in 0..=220 {
            assert_eq!(ramp.at(tenth as f64 / 10.0), 0.7);
        }
        assert_eq!(ramp.peak(), 0.7);
    }

    #[test]
    fn a_ramp_picks_the_right_segment_of_several() {
        let ramp =
            Ramp { base: 1.0, stops: vec![(0.0, 0.0), (10.0, 10.0), (20.0, 0.0)] };
        assert_eq!(ramp.at(5.0), 5.0, "the rising segment");
        assert_eq!(ramp.at(15.0), 5.0, "the falling one");
        assert_eq!(ramp.at(10.0), 10.0, "the shared stop");
        assert_eq!(ramp.peak(), 10.0, "the peak is not the last stop");
    }

    // --- lines -------------------------------------------------------------

    #[test]
    fn every_line_layer_draws_something_somewhere_in_the_zoom_range() {
        for layer in line_layers() {
            let drawn = ZOOMS.into_iter().any(|zoom| layer.stroke(zoom as f64).visible());
            assert!(drawn, "`{}` is invisible at every zoom", layer.id);
        }
    }

    /// The defect a constant width had: a street-level width applied at world level.
    #[test]
    fn a_highway_at_world_zoom_is_a_small_fraction_of_its_width_at_street_zoom() {
        let highway = find("roads-highway");
        let (world, street) = (highway.stroke(4.0).width_dp, highway.stroke(14.0).width_dp);
        assert!(world > 0.0 && street > 0.0, "world {world}, street {street}");
        assert!(
            world < street * 0.15,
            "a highway is {world} Dp at z4 against {street} Dp at z14, which is not a \
             continent-scale road drawn thin",
        );
    }

    /// The invariant the constants violated: 6 Dp of highway plus its casing at z4 is the
    /// 15-25px ribbon that merged neighbouring roads into sheets.
    #[test]
    fn no_line_layer_is_more_than_a_couple_of_dp_wide_below_street_zoom() {
        for layer in line_layers() {
            for tenth in 0..80 {
                let zoom = tenth as f64 / 10.0;
                let stroke = layer.stroke(zoom);
                let total = stroke.width_dp * 2.0 + stroke.gap_width_dp * 2.0;
                assert!(total <= 10.0, "`{}` is {total} Dp across at z{zoom}", layer.id);
            }
        }
    }

    #[test]
    fn width_grows_continuously_with_zoom_rather_than_stepping_per_level() {
        // Across an integer boundary the ramp is inside one segment, so a width quantised to
        // the tile zoom would repeat itself here.
        let highway = find("roads-highway");
        let widths: Vec<f32> =
            (0..=10).map(|step| highway.stroke(13.5 + step as f64 / 10.0).width_dp).collect();
        for pair in widths.windows(2) {
            assert!(pair[1] > pair[0], "width stepped rather than grew: {widths:?}");
        }
        assert!(widths[4] < widths[5] && widths[5] < widths[6], "{widths:?}");
    }

    #[test]
    fn a_highway_grows_monotonically_across_the_whole_zoom_range() {
        let highway = find("roads-highway");
        let mut previous = -1.0;
        for tenth in 0..=220 {
            let width = highway.stroke(tenth as f64 / 10.0).width_dp;
            assert!(width >= previous, "width fell at z{}", tenth as f64 / 10.0);
            previous = width;
        }
    }

    /// Root cause of roads appearing five levels early: `roads-highway` has no `min_zoom`, so
    /// its gate has to be the ramp reaching zero.
    #[test]
    fn an_ungated_road_layer_is_still_not_drawn_at_world_zoom() {
        let highway = find("roads-highway");
        assert_eq!(highway.min_zoom, 0, "this asserts the ramp gates, not `min_zoom`");
        for tenth in 0..=30 {
            let zoom = tenth as f64 / 10.0;
            assert!(!highway.stroke(zoom).visible(), "drawn at z{zoom}");
        }
    }

    /// `gapped()` decides, at tessellation time, whether a layer emits one centred band or two
    /// offset ones, and the pushed gap width is discarded if it emits one. So the two have to
    /// agree: the flag is the ramp's peak, and nothing else may set it.
    #[test]
    fn a_layer_is_gapped_exactly_when_its_gap_ramp_is_ever_non_zero() {
        for layer in layers() {
            let ever = (0..=220).any(|tenth| layer.stroke(tenth as f64 / 10.0).gap_width_dp > 0.0);
            assert_eq!(layer.gapped(), ever, "`{}` disagrees with its gap ramp", layer.id);
        }
        assert!(find("roads-major-casing").gapped());
        assert!(!find("roads-major").gapped());
        assert!(!find("earth").gapped());
    }

    #[test]
    fn a_stroke_with_a_gap_but_no_width_is_not_visible() {
        assert!(!Stroke { width_dp: 0.0, gap_width_dp: 8.0 }.visible());
        assert!(Stroke { width_dp: 0.5, gap_width_dp: 0.0 }.visible());
        assert!(!Stroke::NONE.visible());
    }

    /// Whatever the ramps say, a road has to be wide enough to see once the map is a street
    /// map — otherwise this trades one visible defect for another.
    #[test]
    fn roads_are_still_drawn_at_street_zoom() {
        for id in ["roads-highway", "roads-major", "roads-minor"] {
            let width = find(id).stroke(16.0).width_dp;
            assert!(width >= 1.0, "`{id}` is {width} Dp at z16");
        }
    }

    /// A sub-pixel stroke has no antialiasing to fade it, so it must not be pushed as one.
    #[test]
    fn a_sub_pixel_stroke_is_held_at_one_pixel_wide() {
        let hair = Stroke { width_dp: 0.18, gap_width_dp: 0.0 };
        let (half_width, _) = hair.half_px(3.0);
        assert_eq!(half_width, MIN_HALF_WIDTH_PX, "0.18 Dp at density 3 is 0.27 px of half-width");
        // A stroke already wider than a pixel is left exactly alone.
        let solid = Stroke { width_dp: 4.0, gap_width_dp: 3.0 };
        assert_eq!(solid.half_px(3.0), (6.0, 4.5));
        // Density scales it, so the floor bites at a width that varies with the screen.
        assert_eq!(Stroke { width_dp: 0.4, gap_width_dp: 0.0 }.half_px(1.0).0, MIN_HALF_WIDTH_PX);
        assert_eq!(Stroke { width_dp: 0.4, gap_width_dp: 0.0 }.half_px(3.0).0, 0.6);
    }

    /// The gap is deliberately not floored: two bands a sub-pixel apart read as one band, which
    /// is correct, whereas forcing them apart would widen a road the style wanted narrow.
    #[test]
    fn a_sub_pixel_gap_is_left_alone() {
        assert_eq!(Stroke { width_dp: 2.0, gap_width_dp: 0.1 }.half_px(3.0).1, 0.15);
    }

    /// What should actually appear on a density-3 phone at the zooms the defect was measured at.
    /// This is the closest a host test gets to the device, so the numbers are spelled out.
    #[test]
    fn the_pixel_widths_at_the_measured_zooms_are_what_the_style_asks_for() {
        let of = |id: &str, zoom: f64| -> Option<f32> {
            let stroke = find(id).stroke(zoom);
            // `None` means the renderer skips the layer outright.
            stroke.visible().then(|| stroke.half_px(3.0).0 * 2.0)
        };

        // z3.87, where road colour covered 19.72% of the viewport. The casing is gated out by
        // its own ramp and the fill collapses to the one-pixel floor.
        assert_eq!(of("roads-highway-casing", 3.87), None);
        assert_eq!(of("roads-highway", 3.87), Some(1.0));
        // Was a 6.0 Dp fill plus a 1.25 Dp casing 6.0 Dp apart: 18 px of fill on a density-3
        // screen, which is the 15-25 px ribbon that merged adjacent roads into sheets.

        // z7.87, where coverage was 6.76% and the network was already recognisable.
        let highway = of("roads-highway", 7.87).expect("drawn");
        assert!((3.0..4.0).contains(&highway), "{highway} px");

        // z14.87, which looked correct and must still look correct.
        let highway = of("roads-highway", 14.87).expect("drawn");
        assert!((13.0..15.0).contains(&highway), "{highway} px");
        let minor = of("roads-minor", 14.87).expect("drawn");
        assert!((5.0..6.0).contains(&minor), "{minor} px");
        assert!(minor < highway, "a minor road must be narrower than a highway");
    }

    // --- fills -------------------------------------------------------------

    /// The opacity ramp is the *only* thing that gates a fill, and it is continuous. Pinned at
    /// the zooms that were visibly wrong on device.
    #[test]
    fn the_opacity_ramp_is_the_only_gate_and_it_is_continuous() {
        for layer in layers().iter().filter(|l| l.kind == LayerKind::Fill) {
            let mut previous = f32::NAN;
            for tenth in 0..=(MAX_ZOOM as u32 * 10) {
                let zoom = tenth as f64 / 10.0;
                let opacity = layer.opacity_at(zoom);
                assert!((0.0..=1.0).contains(&opacity), "{} at z{zoom} is {opacity}", layer.id);
                // No step larger than the ramp's own slope between adjacent tenths: a jump
                // means something quantised the zoom, which is how a layer pops instead of
                // fading.
                if previous.is_finite() {
                    assert!(
                        (opacity - previous).abs() < 0.06,
                        "{} jumped {previous} -> {opacity} at z{zoom}",
                        layer.id,
                    );
                }
                previous = opacity;
            }
        }
    }

    /// The values that were visibly wrong when opacity was a baked alpha plus a zoom gate.
    #[test]
    fn fill_opacity_is_a_ramp_evaluated_per_frame_not_a_baked_alpha() {
        // Alpha stays out of the colour column: the ramp owns it, in both variants.
        for id in ["buildings", "landuse_urban_green", "earth", "landcover:glacier"] {
            assert_eq!(find(id).light >> 24, 0xFF, "{id}'s alpha belongs to its ramp");
            assert_eq!(find(id).dark >> 24, 0xFF);
        }
        let at = |id: &str, zoom: f64| find(id).opacity_at(zoom);
        assert_eq!(at("buildings", 16.0), 0.5, "a literal opacity");
        assert_eq!(at("landuse_urban_green", 16.0), 0.7);
        assert_eq!(at("earth", 4.0), 1.0, "no opacity is fully opaque");
        // The two ramps, at the zooms that were visibly wrong on device.
        assert_eq!(at("landcover", 5.0), 1.0);
        assert_eq!(at("landcover", 6.0), 0.5, "half, not the full blanket");
        assert_eq!(at("landcover", 7.0), 0.0);
        assert_eq!(at("landcover:grassland", 6.0), 0.5, "an arm carries its family's ramp");
        assert_eq!(at("landuse_park:national_park", 6.0), 0.0);
        assert!((at("landuse_park:wood", 7.0) - 0.2).abs() < 1e-6, "a fifth, not full green");
        assert_eq!(at("landuse_park:military", 11.0), 1.0);
        // A line layer is opaque; only fills carry an opacity ramp.
        assert_eq!(at("roads-major", 10.0), 1.0);
    }

    #[test]
    fn a_line_layer_has_a_width_and_a_fill_does_not() {
        for layer in layers() {
            match layer.kind {
                LayerKind::Line => {
                    assert!(layer.width.peak() > 0.0, "{} needs a width", layer.id);
                    assert_eq!(layer.opacity, Ramp::constant(1.0), "{} is a line", layer.id);
                }
                LayerKind::Fill => {
                    assert_eq!(layer.width.peak(), 0.0, "{} is a fill", layer.id);
                    assert_eq!(layer.gap_width.peak(), 0.0, "{} is a fill", layer.id);
                    assert_eq!(layer.dash, (0.0, 0.0), "{} is a fill", layer.id);
                }
            }
        }
    }

    // --- the cross-check against basemap.json ------------------------------

    fn basemap() -> Json {
        serde_json::from_str(BASEMAP).expect("basemap.json should parse")
    }

    /// The authored layer a flat layer names, by id.
    fn authored_layer(root: &Json, id: &str) -> Json {
        root.get("layers")
            .and_then(Json::as_array)
            .expect("layers")
            .iter()
            .find(|layer| layer.get("id").and_then(Json::as_str) == Some(id))
            .unwrap_or_else(|| panic!("basemap.json has no layer `{id}`"))
            .clone()
    }

    /// `#rrggbb` or `rgb(...)`/`rgba(...)`, the two spellings `basemap.json` uses.
    ///
    /// Only the test needs the functional form: the flat file writes hex, and the seven
    /// `landcover` arms in the authored file are what this is here to read.
    fn parse_authored_color(source: &str) -> Option<u32> {
        if let Some(argb) = parse_hex(source) {
            return Some(argb);
        }
        let (name, rest) = source.trim().split_once('(')?;
        if !matches!(name.trim(), "rgb" | "rgba") {
            return None;
        }
        let parts: Vec<f64> = rest
            .strip_suffix(')')?
            .split(',')
            .map(|part| part.trim().parse::<f64>())
            .collect::<Result<_, _>>()
            .ok()?;
        let channel = |v: f64| v.round().clamp(0.0, 255.0) as u32;
        // Alpha is 0..1 in the functional notation, unlike the 0..255 of the channels.
        let alpha = match parts.len() {
            3 => 0xFF,
            4 => channel(parts[3] * 255.0),
            _ => return None,
        };
        Some(
            (alpha << 24)
                | (channel(parts[0]) << 16)
                | (channel(parts[1]) << 8)
                | channel(parts[2]),
        )
    }

    /// Every colour string anywhere in an expression subtree.
    fn colors_in(json: &Json, out: &mut Vec<u32>) {
        match json {
            Json::String(s) => {
                if let Some(argb) = parse_authored_color(s) {
                    out.push(argb);
                }
            }
            Json::Array(items) => items.iter().for_each(|item| colors_in(item, out)),
            _ => {}
        }
    }

    /// An authored `interpolate` expression as a flat [`Ramp`], or `None` if it is a constant.
    fn authored_ramp(json: &Json) -> Option<Ramp> {
        let items = json.as_array()?;
        if items.first().and_then(Json::as_str) != Some("interpolate") {
            return None;
        }
        let interpolation = items.get(1)?.as_array()?;
        let base = match interpolation.first().and_then(Json::as_str) {
            Some("linear") => 1.0,
            Some("exponential") => interpolation.get(1)?.as_f64()?,
            _ => return None,
        };
        let stops = items[3..]
            .chunks(2)
            .map(|pair| Some((pair[0].as_f64()?, pair.get(1)?.as_f64()? as f32)))
            .collect::<Option<Vec<_>>>()?;
        Some(Ramp { base, stops })
    }

    /// The authored value of one paint property, as a ramp — a literal number becoming a
    /// one-stop ramp, exactly as the loader treats one.
    fn authored_property(layer: &Json, property: &str) -> Option<Ramp> {
        let json = layer.get("paint")?.get(property)?;
        if let Some(value) = json.as_f64() {
            return Some(Ramp::constant(value as f32));
        }
        authored_ramp(json)
    }

    /// **The mitigation for the one risk this module has a history of.** Hand-transcribing
    /// `basemap.json` failed twice before, so every value that exists in both files is compared
    /// here and divergence fails a build rather than being noticed on a screenshot.
    ///
    /// What is *not* compared, and why: a fill's dark colour (the authored file is light-only)
    /// and a line's colour and dash (deliberately the app's own, warmer than the authored
    /// white-on-grey; see this module's docs).
    #[test]
    fn the_flat_style_agrees_with_basemap_json() {
        let root = basemap();
        for layer in layers() {
            let authored = authored_layer(&root, &layer.authored);
            assert_eq!(
                authored.get("source-layer").and_then(Json::as_str),
                Some(layer.source_layer.as_str()),
                "`{}` reads a different source layer than `{}` does",
                layer.id,
                layer.authored,
            );
            match layer.kind {
                LayerKind::Fill => {
                    // The light colour has to be one the authored `fill-color` can produce.
                    let mut colors = Vec::new();
                    colors_in(
                        authored.get("paint").and_then(|p| p.get("fill-color")).expect("a colour"),
                        &mut colors,
                    );
                    assert!(
                        colors.contains(&layer.light),
                        "`{}`'s {:#010X} is not a colour `{}`'s fill-color paints: {:?}",
                        layer.id,
                        layer.light,
                        layer.authored,
                        colors.iter().map(|c| format!("{c:#010X}")).collect::<Vec<_>>(),
                    );
                    let authored_opacity = authored_property(&authored, "fill-opacity")
                        .unwrap_or_else(|| Ramp::constant(1.0));
                    assert_ramps_agree(&layer.id, "opacity", &layer.opacity, &authored_opacity);
                }
                LayerKind::Line => {
                    let width = authored_property(&authored, "line-width")
                        .unwrap_or_else(|| Ramp::constant(1.0));
                    assert_ramps_agree(&layer.id, "width", &layer.width, &width);
                    let gap = authored_property(&authored, "line-gap-width")
                        .unwrap_or_else(|| Ramp::constant(0.0));
                    assert_ramps_agree(&layer.id, "gap_width", &layer.gap_width, &gap);
                }
            }
        }
    }

    /// Two ramps must agree at every tenth of a zoom, not merely stop for stop.
    ///
    /// Comparing evaluated values rather than the stop lists is what lets a constant and a
    /// one-stop ramp compare equal, and it is also the thing that actually matters: a stop
    /// written at a different zoom with a compensating value is still the same paint.
    fn assert_ramps_agree(id: &str, property: &str, flat: &Ramp, authored: &Ramp) {
        for tenth in 0..=(MAX_ZOOM as u32 * 10) {
            let zoom = tenth as f64 / 10.0;
            let (ours, theirs) = (flat.at(zoom), authored.at(zoom));
            assert!(
                (ours - theirs).abs() < 1e-5,
                "`{id}`'s {property} is {ours} at z{zoom} where basemap.json says {theirs}",
            );
        }
    }

    /// The one place the flat file cannot say what the authored file says: two casings are an
    /// `_early`/`_late` pair split at z12 there and one ramp here. The flat layer carries the
    /// `_late` half, so this bounds what carrying it costs at the shallow zooms the `_early`
    /// half used to own — 0.4 Dp, worst immediately below the split.
    #[test]
    fn a_collapsed_casing_pair_matches_its_authored_late_half() {
        let root = basemap();
        for (id, pair) in [
            ("roads-major-casing", "roads_major_casing"),
            ("roads-highway-casing", "roads_highway_casing"),
        ] {
            let layer = find(id);
            assert_eq!(layer.authored, format!("{pair}_late"));
            let early = authored_layer(&root, &format!("{pair}_early"));
            for property in ["line-width", "line-gap-width"] {
                let Some(ramp) = authored_property(&early, property) else { continue };
                for tenth in 0..120 {
                    let zoom = tenth as f64 / 10.0;
                    // Only where the casing is drawn at all. Below its own width ramp the two
                    // halves may say anything, because neither puts a pixel on screen.
                    if !layer.stroke(zoom).visible() {
                        continue;
                    }
                    let ours = match property {
                        "line-width" => layer.width.at(zoom),
                        _ => layer.gap_width.at(zoom),
                    };
                    assert!(
                        (ours - ramp.at(zoom)).abs() <= 0.4,
                        "`{id}`'s {property} is {ours} at z{zoom} where the `_early` half says {}",
                        ramp.at(zoom),
                    );
                }
            }
        }
    }

    /// The `kind` values an authored filter admits, or empty for "any of them".
    ///
    /// The four shapes `basemap.json` uses on its `fill` layers. A `$type` filter restricts
    /// geometry rather than `kind`, so it admits everything.
    fn authored_filter_kinds(filter: Option<&Json>) -> Vec<String> {
        let Some(Json::Array(items)) = filter else {
            return Vec::new();
        };
        let (op, args) = (items.first().and_then(Json::as_str).unwrap_or_default(), &items[1..]);
        let strings = |from: &[Json]| -> Vec<String> {
            from.iter().filter_map(Json::as_str).map(str::to_string).collect()
        };
        match op {
            "==" if args.first().and_then(Json::as_str) == Some("kind") => strings(&args[1..]),
            "in" if args.first().and_then(Json::as_str) == Some("kind") => strings(&args[1..]),
            // A union, and unrestricted if any branch is.
            "any" => {
                let mut out = Vec::new();
                for inner in args {
                    let kinds = authored_filter_kinds(Some(inner));
                    if kinds.is_empty() {
                        return Vec::new();
                    }
                    out.extend(kinds);
                }
                out
            }
            _ => Vec::new(),
        }
    }

    /// The other half of the cross-check, and the larger hand-transcribed surface: **which
    /// features each layer draws**.
    ///
    /// A data-driven authored layer becomes several flat layers, one per colour, so no single
    /// flat layer's `kinds` matches the authored filter. What must hold is closure: the union
    /// across the family is exactly the set the authored filter admits, so a kind cannot be
    /// dropped (it would stop being drawn) or invented (it would be drawn in the wrong colour).
    #[test]
    fn the_kinds_each_authored_layer_admits_are_all_drawn_and_no_others() {
        let root = basemap();
        let mut families: Vec<&str> = Vec::new();
        for layer in layers().iter().filter(|l| l.kind == LayerKind::Fill) {
            if !families.contains(&layer.authored.as_str()) {
                families.push(&layer.authored);
            }
        }
        for family in families {
            let authored = authored_layer(&root, family);
            let mut admitted = authored_filter_kinds(authored.get("filter"));
            let mut drawn: Vec<String> = layers()
                .iter()
                .filter(|l| l.authored == family)
                .flat_map(|l| l.kinds.iter().cloned())
                .collect();
            if admitted.is_empty() {
                // An unrestricted authored layer needs an unfiltered flat layer, or the kinds
                // its colour expression does not name would stop being drawn at all.
                assert!(
                    layers().iter().any(|l| l.authored == family && l.kinds.is_empty()),
                    "`{family}` admits every kind but no flat layer draws them",
                );
                continue;
            }
            admitted.sort_unstable();
            drawn.sort_unstable();
            // Every kind exactly once: two flat layers claiming the same kind would draw it
            // twice, in whichever colour came last.
            assert_eq!(
                drawn, admitted,
                "the flat layers for `{family}` draw a different kind set than its filter admits",
            );
        }
    }

    /// Every authored layer a flat layer names has to exist, or the cross-check silently stops
    /// checking that layer.
    #[test]
    fn every_authored_layer_a_flat_layer_names_exists() {
        let root = basemap();
        let ids: Vec<&str> = root
            .get("layers")
            .and_then(Json::as_array)
            .expect("layers")
            .iter()
            .filter_map(|layer| layer.get("id").and_then(Json::as_str))
            .collect();
        for layer in layers() {
            assert!(
                ids.contains(&layer.authored.as_str()),
                "`{}` names authored layer `{}`, which is not in basemap.json",
                layer.id,
                layer.authored,
            );
        }
    }
}
