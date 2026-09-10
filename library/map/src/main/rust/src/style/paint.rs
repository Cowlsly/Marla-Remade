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
//! of being found on a screenshot. One column is deliberately **ours** and is not
//! cross-checked:
//!
//! * **The dark colours.** `basemap.json` is light-only. These are
//!   `maps/src/main/java/com/vayunmathur/maps/ui/theme/BasemapPalette.kt`'s contrast-checked
//!   values by role - see [`super`]'s module docs for why that palette rather than a third
//!   party's.
//!
//! The light line colours used to be a second such column - a warmer set with cream fills over
//! tan casings. It was measured against the comparator and reverted to the authored
//! white-on-`#e0e0e0`: a casing *darker* than the land it sits on outlines every road, so a
//! street grid at z14 filled with tan linework and the map read as blurred rather than warm.
//! The bridge layers had never diverged, so the two halves of the same road disagreed as well.
//!
//! One flattening is worth naming. The authored style splits two road casings into
//! `*_casing_early`/`*_casing_late` pairs at z12, one gated by `maxzoom` and the other by
//! `minzoom`. A flat layer carries one ramp, so it carries the `_late` half, which is the one
//! that spans the whole range. Where the casing is wide enough to draw at all the halves differ
//! by at most 0.4 Dp — a little over half a pixel per edge on a density-3 screen — and the worst
//! of that is immediately below the z12 split, which is where two ramps meeting at a point are
//! furthest apart. `a_collapsed_casing_pair_matches_its_authored_late_half` pins the bound.

use super::{Anchor, Layer, LayerKind, Toggle};
use serde_json::Value as Json;
use std::sync::OnceLock;
use tilecodec::mamaps::body::{FLAG_IS_BRIDGE, FLAG_IS_LINK, FLAG_IS_TUNNEL};
use tilecodec::mamaps::dict;

/// The flat style, vendored beside the sources.
///
/// Compiled in rather than read from assets: it is one file for every host app, and it makes
/// the loader host-testable rather than reachable only from a device.
const FLAT: &str = include_str!("../../style/basemap.flat.json");

/// The deepest zoom the renderer draws at, and the default top of a layer's zoom range.
///
/// The archive stops at z14 and the renderer overzooms past it.
pub const MAX_ZOOM: u8 = 22;

/// The narrowest half-width the **geometry** is allowed to be, in device pixels.
///
/// A quad narrower than a pixel is filled only where it happens to straddle a pixel
/// centre, which rasterises as stipple that crawls along the road while panning. So
/// `shaders/line.vert` expands any thinner stroke to this, and `line.frag` takes the
/// difference straight back off as alpha — the road ends up a faint continuous line,
/// which is what the ramp was asking for.
///
/// This used to be applied here, in [`Stroke::half_px`], where it could only round a
/// sub-pixel road *up* to a solid pixel. That is why low zooms read as heavier than
/// MapLibre's: every road the style had ramped down to a hairline drew at full
/// strength. The floor belongs with the rasteriser, not with the style.
///
/// GLSL cannot include a Rust constant, so both shaders carry the literal;
/// [`the_shader_width_floor_matches_this_constant`] pins them together.
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
    /// Halved because the shader offsets each edge from the centreline. Neither is floored:
    /// the shader widens a sub-pixel stroke to [`MIN_HALF_WIDTH_PX`] of geometry and fades it
    /// by coverage instead, so the value handed over stays the width the style asked for.
    pub fn half_px(&self, density: f32) -> (f32, f32) {
        (self.width_dp * density / 2.0, self.gap_width_dp * density / 2.0)
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
        "require_flags",
        "forbid_flags",
        "details",
        "forbid_details",
        "light",
        "dark",
        "opacity",
        "width",
        "gap_width",
        "spread",
        "lanes",
        "dash",
        "text_size",
        "text_size_large",
        "rank_threshold",
        "uppercase",
        "medium",
        "toggle",
        "icon",
        "text_offset",
        "text_max_width",
        "variable_anchor",
        "halo_light",
        "halo_dark",
        "halo_width",
        "minzoom",
        // The floor that applies while browsing, i.e. with no category chip selected. Optional;
        // defaults to `minzoom`. See `Layer::browse_min_zoom`.
        "browse_minzoom",
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
        Some("symbol") => LayerKind::Symbol,
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
    let kinds: Vec<String> = match json.get("kinds") {
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
    };
    // Both halves of the schema closure the plan asks for: a `source` or a `kind` the archive
    // cannot carry is a build failure, not a layer that quietly draws nothing on device.
    let source = string("source")?;
    let source_layer_id = dict::LAYERS
        .iter()
        .position(|name| *name == source)
        .ok_or_else(|| {
            format!("`{id}` reads source layer `{source}`, which no .mamaps archive carries")
        })? as u8;
    let mut kind_ids = kinds
        .iter()
        .map(|name| {
            super::kind_id(name).ok_or_else(|| {
                format!("`{id}` filters on kind `{name}`, which the schema cannot emit")
            })
        })
        .collect::<Result<Vec<u16>, _>>()?;
    // Sorted so the render path's membership test is a binary search over a `u16` slice.
    kind_ids.sort_unstable();
    kind_ids.dedup();
    // The road flag/detail filters, as interned ids and bitmasks. `require_flags` names
    // features that must carry a bit (`["link"]`); `forbid_flags` names bits that must be
    // absent (`["bridge", "tunnel"]` on a surface layer). `details`/`forbid_details` are
    // `kind_detail` names (`service`), interned through the same DETAILS table the tiler
    // wrote. All four default to empty/zero — no filter — so fills never name them.
    let flag_bits = |key: &str| -> Result<u8, String> {
        let mut bits = 0u8;
        match json.get(key) {
            None => Ok(0),
            Some(Json::Array(names)) => {
                for name in names {
                    bits |= match name.as_str() {
                        Some("tunnel") => FLAG_IS_TUNNEL,
                        Some("bridge") => FLAG_IS_BRIDGE,
                        Some("link") => FLAG_IS_LINK,
                        other => {
                            return Err(format!(
                                "`{id}`'s {key} names an unknown flag {other:?}"
                            ))
                        }
                    };
                }
                Ok(bits)
            }
            Some(_) => Err(format!("`{id}`'s {key} must be an array of flag names")),
        }
    };
    let detail_ids_of = |key: &str| -> Result<Vec<u16>, String> {
        let mut ids: Vec<u16> = match json.get(key) {
            None => Vec::new(),
            Some(Json::Array(names)) => names
                .iter()
                .map(|name| {
                    name.as_str()
                        .and_then(super::detail_id)
                        .ok_or_else(|| {
                            format!("`{id}` filters on detail `{name}`, which the schema cannot emit")
                        })
                })
                .collect::<Result<_, _>>()?,
            Some(_) => return Err(format!("`{id}`'s {key} must be an array of detail names")),
        };
        ids.sort_unstable();
        ids.dedup();
        Ok(ids)
    };

    let toggle = match json.get("toggle").map(|v| v.as_str()) {
        None => None,
        Some(Some("poi")) => Some(Toggle::Poi),
        Some(Some("transit")) => Some(Toggle::Transit),
        Some(other) => return Err(format!("`{id}` has an unknown toggle {other:?}")),
    };
    let text_offset = match json.get("text_offset").map(|v| v.as_array()) {
        None => (0.0, 0.0),
        Some(Some(pair)) => match pair.as_slice() {
            [x, y] => match (x.as_f64(), y.as_f64()) {
                (Some(x), Some(y)) => (x as f32, y as f32),
                _ => return Err(format!("`{id}`'s text_offset must be two numbers")),
            },
            _ => return Err(format!("`{id}`'s text_offset must be `[x, y]`")),
        },
        Some(None) => return Err(format!("`{id}`'s text_offset must be an array")),
    };
    let variable_anchor: Vec<Anchor> = match json.get("variable_anchor") {
        None => Vec::new(),
        Some(Json::Array(names)) => names
            .iter()
            .map(|name| match name.as_str() {
                Some("center") => Ok(Anchor::Center),
                Some("left") => Ok(Anchor::Left),
                Some("right") => Ok(Anchor::Right),
                other => Err(format!("`{id}`'s variable_anchor names an unknown anchor {other:?}")),
            })
            .collect::<Result<_, _>>()?,
        Some(_) => return Err(format!("`{id}`'s variable_anchor must be an array")),
    };

    Ok(Layer {
        source_layer: source,
        source_layer_id,
        authored: string("authored")?,
        kind,
        kinds,
        kind_ids,
        require_flags: flag_bits("require_flags")?,
        forbid_flags: flag_bits("forbid_flags")?,
        detail_ids: detail_ids_of("details")?,
        forbid_details: detail_ids_of("forbid_details")?,
        light: color(json.get("light"), &id)?,
        dark: color(json.get("dark"), &id)?,
        opacity: Ramp::parse(json.get("opacity"), &id, "opacity", 1.0)?,
        width: Ramp::parse(json.get("width"), &id, "width", 0.0)?,
        gap_width: Ramp::parse(json.get("gap_width"), &id, "gap_width", 0.0)?,
        spread: Ramp::parse(json.get("spread"), &id, "spread", 0.0)?,
        lanes: Ramp::parse(json.get("lanes"), &id, "lanes", 1.0)?,
        dash,
        text_size: Ramp::parse(json.get("text_size"), &id, "text_size", 0.0)?,
        // Optional second arm: present only where the authored style's `text-size` is a
        // `case` on `population_rank`. Both halves have to be there or neither, since a
        // threshold with nothing to switch to says nothing.
        text_size_large: match json.get("text_size_large") {
            Some(value) => Some(Ramp::parse(Some(value), &id, "text_size_large", 0.0)?),
            None => None,
        },
        rank_threshold: match json.get("rank_threshold") {
            Some(value) => Some(Ramp::parse(Some(value), &id, "rank_threshold", 0.0)?),
            None => None,
        },
        uppercase: json.get("uppercase").and_then(Json::as_bool).unwrap_or(false),
        medium: json.get("medium").and_then(Json::as_bool).unwrap_or(false),
        toggle,
        icon: json.get("icon").and_then(Json::as_bool).unwrap_or(false),
        text_offset,
        text_max_width: json
            .get("text_max_width")
            .and_then(Json::as_f64)
            .map(|v| v as f32)
            .unwrap_or(0.0),
        variable_anchor,
        halo_light: color(json.get("halo_light"), &id).unwrap_or(0x00000000),
        halo_dark: color(json.get("halo_dark"), &id).unwrap_or(0x00000000),
        halo_width: json
            .get("halo_width")
            .and_then(Json::as_f64)
            .map(|v| v as f32)
            .unwrap_or(1.0),
        min_zoom: zoom("minzoom", 0)?,
        // Defaults to the data floor, so a layer that does not set it is gated exactly as before.
        browse_min_zoom: zoom("browse_minzoom", zoom("minzoom", 0)?)?,
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
        // Counted exactly, and split basemap from optional: the file *is* the layer set,
        // so a layer appearing or disappearing is a decision rather than an incidental
        // restyle. Splitting the count means adding an optional layer touches one number
        // that says what it is, instead of nudging a basemap total that then no longer
        // states what the basemap is.
        //
        // Basemap: 24 fills, 23 lines and 7 symbols. The 23 lines are the 12
        // surface/link layers, 10 bridge layers (5 casings + 5 fills), and the app-only
        // `roads-lanes` divider layer (task WS-D) that draws a multi-lane carriageway's
        // individual lanes at z16+: the authored
        // `is_bridge` pass the flat file used to drop entirely, which is what hid the Bay
        // Bridge and the Golden Gate (task 8). The 7 symbols are the 4-deep places hierarchy
        // (country/region/locality/subplace) plus the 3 curved line labels WS-E added
        // (roads-label-major, roads-label-minor, waterway-label).
        //
        // Optional: 1 transit line, and 6 POI symbols — one per colour group of the
        // reference `pois` layer's `text-color` `case` on `kind`. Six and not seven: the
        // `case` has a `#e2dfda` default arm, but the same layer's `filter` admits exactly
        // the 36 kinds the six groups list between them, so the default is unreachable.
        // A seventh unfiltered layer would not be a fallback, it would draw all 36 POIs a
        // second time in the wrong colour —
        // `the_poi_colour_groups_partition_the_kinds_the_reference_admits` pins the
        // arithmetic that makes the omission safe.
        let count = |kind: LayerKind, optional: bool| {
            style
                .layers
                .iter()
                .filter(|l| l.kind == kind && l.toggle.is_some() == optional)
                .count()
        };
        assert_eq!(
            (count(LayerKind::Fill, false), count(LayerKind::Line, false), count(LayerKind::Symbol, false)),
            (24, 23, 7),
            "the basemap layer set",
        );
        assert_eq!(
            (count(LayerKind::Fill, true), count(LayerKind::Line, true), count(LayerKind::Symbol, true)),
            (0, 1, 6),
            "the optional layer set",
        );
        assert_eq!(style.background, (0xFF80DEEA, 0xFF0D1B2A));
    }

    /// The six POI layers have to cover the reference filter exactly: every kind once, and
    /// no kind twice.
    ///
    /// A kind missing from all six is a POI the reference draws and we do not. A kind in
    /// two is a POI drawn twice, in whichever colour comes last — and since the layers are
    /// one per colour, that is a silent recolouring rather than a visible double image.
    /// This is also what licenses leaving the `case`'s default arm out entirely.
    #[test]
    fn the_poi_colour_groups_partition_the_kinds_the_reference_admits() {
        let root = basemap();
        let authored = authored_layer(&root, "pois");
        // `["all", ["in", ["get","kind"], ["literal", [...]]], [">=", ["zoom"], ...]]`
        let mut admitted: Vec<String> = Vec::new();
        let filter = authored.get("filter").and_then(Json::as_array).expect("a filter");
        for clause in filter {
            let Some(clause) = clause.as_array() else { continue };
            if clause.first().and_then(Json::as_str) != Some("in") {
                continue;
            }
            let Some(literal) = clause.get(2).and_then(Json::as_array) else { continue };
            let Some(names) = literal.get(1).and_then(Json::as_array) else { continue };
            admitted.extend(names.iter().filter_map(Json::as_str).map(str::to_string));
        }
        assert_eq!(admitted.len(), 36, "the reference admits 36 kinds");

        let mut drawn: Vec<String> = layers()
            .iter()
            .filter(|l| l.toggle == Some(Toggle::Poi))
            .flat_map(|l| l.kinds.iter().cloned())
            .collect();
        let before = drawn.len();
        drawn.sort_unstable();
        drawn.dedup();
        assert_eq!(before, drawn.len(), "a kind is claimed by two POI layers");
        // Four kinds are ours, not the reference's. The `maps` app has always offered Gas,
        // Hotels and ATM chips and the reference basemap draws none of those three, so the
        // chips could only ever have filtered a set that did not contain their subject. Stated
        // as an explicit extension rather than folded into the count, so this test still fails
        // if a kind is added to a POI layer by accident.
        const LOCAL: &[&str] = &["atm", "bank", "fuel", "hotel"];
        let (local, reference): (Vec<String>, Vec<String>) =
            drawn.into_iter().partition(|kind| LOCAL.contains(&kind.as_str()));
        assert_eq!(local, LOCAL, "the local POI extension is exactly these four kinds");
        admitted.sort_unstable();
        assert_eq!(reference, admitted, "the POI layers do not cover the reference filter");
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
        assert!(find("roads-link-casing").gapped(), "a link casing is still a casing");
        assert!(!find("roads-major").gapped());
        assert!(!find("roads-link").gapped(), "a link fill is a single centred stroke");
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
        for id in ["roads-highway", "roads-major", "roads-minor", "roads-link"] {
            let width = find(id).stroke(16.0).width_dp;
            assert!(width >= 1.0, "`{id}` is {width} Dp at z16");
        }
    }

    /// A bridge is drawn by the bridge layers and by nothing else: every surface road
    /// layer carries `forbid_flags: ["bridge"]`. So wherever a surface class draws, its
    /// bridge counterpart has to draw too, or the road is chopped at every crossing.
    ///
    /// This is what hid the Golden Gate and the Bay Bridge below z12, and with them every
    /// highway overpass in the network - `roads-bridges-highway` had a `minzoom` of 12
    /// that the authored style does not give it.
    #[test]
    fn a_highway_bridge_draws_wherever_a_surface_highway_does() {
        let (surface, bridge) = (find("roads-highway"), find("roads-bridges-highway"));
        assert!(
            surface.forbid_flags & FLAG_IS_BRIDGE != 0,
            "the surface layer must exclude bridges, or this test proves nothing",
        );
        for tenth in 0..=220 {
            let zoom = tenth as f64 / 10.0;
            if !surface.stroke(zoom).visible() || !surface.draws_at(zoom.floor() as u8) {
                continue;
            }
            assert!(
                bridge.draws_at(zoom.floor() as u8) && bridge.stroke(zoom).visible(),
                "a highway draws at z{zoom} but its bridges do not",
            );
        }
    }
    #[test]
    fn a_sub_pixel_stroke_keeps_its_true_width() {
        let hair = Stroke { width_dp: 0.18, gap_width_dp: 0.0 };
        let (half_width, _) = hair.half_px(3.0);
        assert!(
            (half_width - 0.27).abs() < 1e-6,
            "0.18 Dp at density 3 is 0.27 px of half-width, got {half_width}",
        );
        assert!(half_width < MIN_HALF_WIDTH_PX, "and it is under the shader's floor");
        // A stroke already wider than a pixel is unaffected either way.
        let solid = Stroke { width_dp: 4.0, gap_width_dp: 3.0 };
        assert_eq!(solid.half_px(3.0), (6.0, 4.5));
        // Density still scales it.
        assert_eq!(Stroke { width_dp: 0.4, gap_width_dp: 0.0 }.half_px(1.0).0, 0.2);
        assert_eq!(Stroke { width_dp: 0.4, gap_width_dp: 0.0 }.half_px(3.0).0, 0.6);
    }

    /// Both line shaders hardcode the floor because GLSL cannot include a Rust
    /// constant. A silent divergence would put the geometry and the coverage term on
    /// different widths, which shows up as roads that are too faint or too hard-edged
    /// — subtle enough to survive review.
    #[test]
    fn the_shader_width_floor_matches_this_constant() {
        let declared = format!("const float MIN_HALF_WIDTH_PX = {MIN_HALF_WIDTH_PX:.1};");
        for (name, source) in [
            ("line.vert", include_str!("../../shaders/line.vert")),
            ("line.frag", include_str!("../../shaders/line.frag")),
        ] {
            assert!(source.contains(&declared), "{name} does not declare `{declared}`");
        }
    }

    /// The gap is deliberately not floored: two bands a sub-pixel apart read as one band, which
    /// is correct, whereas forcing them apart would widen a road the style wanted narrow.
    #[test]
    fn a_sub_pixel_gap_is_left_alone() {
        assert_eq!(Stroke { width_dp: 2.0, gap_width_dp: 0.1 }.half_px(3.0).1, 0.15);
    }

    /// Mirror of `line.frag`'s dash test so WS-B's clock-driven phase can be checked without a
    /// GPU. `phase_px` is `misc.w * morph.y` — the per-frame clock times the per-draw phase
    /// speed — which is 0 for every static line (`morph.y` defaults to 0) and for a stopped
    /// clock. GLSL `mod` matches Rust's `rem_euclid` for a positive period, and the shader
    /// draws (does not `discard`) when the result is `<= on`.
    fn dash_drawn(distance_px: f32, on: f32, off: f32, phase_px: f32) -> bool {
        let period = on + off;
        if off <= 0.0 || period <= 0.0 {
            return true;
        }
        (distance_px - phase_px).rem_euclid(period) <= on
    }

    /// The no-regression guarantee: a static road (`morph.y = 0`) or a stopped clock makes the
    /// phase 0, and the dash then has to be exactly what the pre-WS-B shader drew.
    #[test]
    fn a_static_dash_is_byte_identical_with_a_zero_phase() {
        // The un-phased test the shader ran before WS-B.
        let unphased = |d: f32, on: f32, off: f32| d.rem_euclid(on + off) <= on;
        let (on, off) = (6.0, 6.0);
        for i in 0..480 {
            let d = i as f32 * 0.25;
            assert_eq!(
                dash_drawn(d, on, off, 0.0),
                unphased(d, on, off),
                "a zero phase must reproduce the old dash at distance {d}",
            );
        }
        // A solid line (non-positive gap) is untouched too.
        for i in 0..480 {
            let d = i as f32 * 0.25;
            assert!(dash_drawn(d, 2.0, 0.0, 12.0), "a [2, 0] line stays solid under any phase");
        }
    }

    /// And the animation actually moves: a whole-period phase is a no-op, a half-period phase
    /// inverts the pattern. If this ever stops differing the dash has frozen.
    #[test]
    fn a_travelling_dash_shifts_with_the_phase() {
        let (on, off) = (6.0, 6.0);
        let period = on + off;
        let mut differed = false;
        for i in 0..480 {
            let d = i as f32 * 0.25;
            assert_eq!(
                dash_drawn(d, on, off, period),
                dash_drawn(d, on, off, 0.0),
                "a whole-period phase lands back on the same pattern",
            );
            if dash_drawn(d, on, off, period / 2.0) != dash_drawn(d, on, off, 0.0) {
                differed = true;
            }
        }
        assert!(differed, "a half-period phase must move the dash");
    }

    /// The phase is gated on both the clock and the per-draw speed slot, so a future edit
    /// cannot animate static lines by accident. `line.frag` hardcodes the expression because
    /// GLSL cannot include the Rust push layout.
    #[test]
    fn the_dash_phase_is_gated_on_the_clock_and_the_speed_slot() {
        let frag = include_str!("../../shaders/line.frag");
        assert!(
            frag.contains("push.misc.w * push.morph.y"),
            "line.frag must derive the phase from the clock times the per-draw speed slot",
        );
        assert!(
            frag.contains("mod(inDistancePx - phase, period)"),
            "line.frag must subtract the phase inside the dash modulo",
        );
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
        // its own ramp, and the fill is a hairline the shader now fades rather than rounding
        // up to a solid pixel.
        assert_eq!(of("roads-highway-casing", 3.87), None);
        let hairline = of("roads-highway", 3.87).expect("drawn");
        assert!(hairline < 1.0, "{hairline} px should be sub-pixel at z3.87");
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
        // A link is narrower than the class road it leaves: at z14.87 the link ramp reads
        // ~2.24 Dp (~6.7 px) against the highway's ~4.73 Dp. Before the link layers
        // existed the ramp drew at full highway width, which is the too-wide super-zoom
        // roads this file's new layers fix.
        let link = of("roads-link", 14.87).expect("drawn");
        assert!((6.0..7.5).contains(&link), "{link} px");
        assert!(link < highway, "a slip road must be narrower than the highway it joins");
    }

    // --- the link/service split (issue #3) -----------------------------------

    /// The link casing carries the authored link-casing ramp, and the fill the link
    /// ramp — both narrower than the class layers (z18: 11 Dp vs 15 Dp with no link
    /// casing at all on the class side, since a link is not a highway).
    #[test]
    fn a_link_is_narrower_than_the_class_road_it_joins() {
        let (link, highway) = (find("roads-link"), find("roads-highway"));
        for tenth in [160, 170, 180] {
            let zoom = tenth as f64 / 10.0;
            let (l, h) = (link.stroke(zoom).width_dp, highway.stroke(zoom).width_dp);
            assert!(l > 0.0 && h > 0.0, "both drawn at z{zoom}");
            assert!(l < h, "link {l} Dp is not narrower than highway {h} Dp at z{zoom}");
        }
        assert_eq!(link.stroke(18.0).width_dp, 11.0, "the authored z18 link width");
        assert_eq!(highway.stroke(18.0).width_dp, 15.0);
        assert_eq!(link.gap_width.peak(), 0.0, "a link fill is a single centred stroke");
        assert_eq!(find("roads-link-casing").stroke(18.0).gap_width_dp, 11.0);
        // The highway casing's gap ramp ends at z18 with 15 Dp: a link must never be
        // outlined by it, which the `link` forbid-flag on the class layers guarantees
        // (see `surface_road_layers_draw_only_plain_surface_roads`).
        assert!(highway.forbid_flags & 0b100 != 0, "highway layers forbid links");
    }

    /// Service streets draw at the service width, not at full minor width.
    #[test]
    fn a_service_street_is_narrower_than_a_minor_road() {
        let (service, minor) = (find("roads-minor-service"), find("roads-minor"));
        assert_eq!(service.stroke(18.0).width_dp, 8.0, "the authored z18 service width");
        assert_eq!(minor.stroke(18.0).width_dp, 11.0);
        assert!(service.stroke(18.0).width_dp < minor.stroke(18.0).width_dp);
    }

    // --- rail: a dashed translucent line, not a solid road (issue #6) --------

    /// Rail renders as the authored dashed grey line: `[0.3, 0.75]` in line widths at
    /// 0.5 opacity in `#a7b1b3` — not the solid road-coloured band the comparator
    /// showed down the Market St corridor.
    #[test]
    fn rail_is_a_dashed_translucent_grey_line() {
        let rail = find("roads-rail");
        assert_eq!(rail.dash, (0.3, 0.75), "the authored line-dasharray");
        assert_eq!(rail.opacity_at(16.0), 0.5, "the authored line-opacity");
        assert_eq!(rail.light, 0xFFA7B1B3, "the authored line-color");
        // The width ramp is unchanged: the dash and translucency are the fix, not the
        // width.
        assert_eq!(rail.stroke(18.0).width_dp, 9.0);
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
                    // A casing needs a width OR a gap: gap-only casings
                    // (bridges-other: authored gap, no width — the casing IS
                    // the gap band) tessellate off the gap peak.
                    assert!(
                        layer.width.peak() > 0.0 || layer.gap_width.peak() > 0.0,
                        "{} needs a width",
                        layer.id
                    );
                    // A line's opacity is 1 except where the authored style paints it
                    // translucent — today only rail's 0.5, which is what makes a railway a
                    // faint dashed line rather than a solid road.
                    let expected_opacity = if layer.id == "roads-rail" { 0.5 } else { 1.0 };
                    assert_eq!(
                        layer.opacity,
                        Ramp::constant(expected_opacity),
                        "{} carries an opacity the authored style did not give it",
                        layer.id
                    );
                }
                LayerKind::Fill => {
                    assert_eq!(layer.width.peak(), 0.0, "{} is a fill", layer.id);
                    assert_eq!(layer.gap_width.peak(), 0.0, "{} is a fill", layer.id);
                    assert_eq!(layer.dash, (0.0, 0.0), "{} is a fill", layer.id);
                }
                LayerKind::Symbol => {
                    // Symbols carry text paint, not stroke paint.
                    assert!(layer.text_size.peak() > 0.0, "{} needs a text size", layer.id);
                    assert_eq!(layer.width.peak(), 0.0, "{} is a symbol", layer.id);
                    assert_eq!(layer.gap_width.peak(), 0.0, "{} is a symbol", layer.id);
                    // Halo width stays authored (1px everywhere in basemap.json);
                    // halo COLOR is deliberately high-contrast (white/dark) where
                    // the authored #e0e0e0 washes out on our land — pinned here,
                    // Halo width is 1px almost everywhere; locality takes 1.5
                    // for legibility at city sizes (pinned below).
                    assert!(
                        layer.halo_width == 1.0 || layer.id == "places-locality",
                        "{} halo width",
                        layer.id
                    );
                    assert!(layer.halo_light >> 24 > 0, "{} halo is fully transparent", layer.id);
                }
            }
        }
    }

    /// Symbol halos are high-contrast by decision, not transcription: white in
    /// light mode, the dark backdrop in dark mode. The authored #e0e0e0 halo
    /// is indistinguishable from our #e2dfda land, so labels smear instead of
    /// reading — the M1 legibility verdict. Width stays authored (cross-check).
    #[test]
    fn symbol_halos_contrast_against_both_land_and_text() {
        for id in ["places-country", "places-region", "places-locality", "places-subplace"] {
            let layer = find(id);
            assert_eq!(layer.halo_light, 0xFFFFFFFF, "{id} light halo");
            assert_eq!(layer.halo_dark, 0xFF0D1B2A, "{id} dark halo");
        }
    }

    /// Place labels carry the authored two-arm size, keyed on population rank.
    ///
    /// The authored `text-size` for country and locality is data-driven (`case` over
    /// `population_rank`), so the cross-check against `basemap.json` skips it and this
    /// pins the transcription instead. It used to be one collapsed ramp with a fixed
    /// 1.25x/0.85x nudge in `tile::symbol`, which drew a hamlet at close to city size —
    /// and since a collision box follows the label's size, those hamlets then beat the
    /// cities they overlapped.
    #[test]
    fn city_labels_track_the_big_city_arm_at_compared_zooms() {
        let size = |id: &str, zoom: f64, pop: u16| find(id).text_size_for(zoom, pop);

        // z10: the authored threshold is rank 9 (50k), 12px below and 20px above.
        assert_eq!(size("places-locality", 10.0, 13), 20.0, "a million-plus city");
        assert_eq!(size("places-locality", 10.0, 9), 20.0, "50k is on the threshold");
        assert_eq!(size("places-locality", 10.0, 8), 12.0, "a 20k town");
        assert_eq!(size("places-locality", 10.0, 0), 12.0, "uncounted");

        // z6: the threshold rises to rank 12 (500k), so a 200k town drops to the small arm.
        assert_eq!(size("places-locality", 6.0, 12), 17.0);
        assert_eq!(size("places-locality", 6.0, 11), 11.0);

        // Countries switch at rank 8 by z6.
        assert_eq!(size("places-country", 6.0, 8), 18.0);
        assert_eq!(size("places-country", 6.0, 7), 10.0);

        // Region and subplace are plain ramps in the authored style too, so they answer
        // the same size at every rank and the cross-check pins them stop-for-stop.
        for pop in [0, 8, 15] {
            assert_eq!(size("places-region", 7.0, pop), 16.0);
            assert_eq!(size("places-subplace", 14.0, pop), 14.0);
        }
    }

    /// The two halves of a data-driven size have to arrive together: a threshold with no
    /// large arm silently never fires, and a large arm with no threshold never applies.
    #[test]
    fn a_data_driven_size_declares_both_halves() {
        for layer in layers() {
            assert_eq!(
                layer.text_size_large.is_some(),
                layer.rank_threshold.is_some(),
                "`{}` declares only half of a data-driven text size",
                layer.id,
            );
            // And the big arm really is the bigger one, or the switch is inverted.
            if let Some(large) = &layer.text_size_large {
                for tenth in 0..=220 {
                    let zoom = tenth as f64 / 10.0;
                    assert!(
                        large.at(zoom) >= layer.text_size.at(zoom),
                        "`{}` draws big places smaller at z{zoom}",
                        layer.id,
                    );
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

    /// The authored value of one _layout_ property (`text-size` lives in `layout`,
    /// not `paint`). A data-driven `text-size` with `case`/`get` arms has no flat
    /// equivalent — the caller transcribes the constant the arms evaluate to at the
    /// reference zooms, and this reads the same arms-free ramps only. Returns `None`
    /// when the value is data-driven, so the cross-check skips rather than lies.
    fn authored_layout_ramp(layer: &Json, property: &str) -> Option<Ramp> {
        let json = layer.get("layout")?.get(property)?;
        if let Some(value) = json.as_f64() {
            return Some(Ramp::constant(value as f32));
        }
        // A plain `interpolate` over zoom transcribes directly; anything data-driven
        // (`case`, `get`, `step` over feature properties) is M1-out-of-scope.
        let text = serde_json::to_string(json).unwrap_or_default();
        if text.contains("\"case\"") || text.contains("\"get\"") {
            return None;
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
            // `roads-lanes` is an app-only layer: it draws a multi-lane carriageway's individual
            // lane dividers, which the authored `basemap.json` has no concept of. Its width, colour
            // and spread are all deliberately its own, so — like transit's width — it is pinned by
            // its own test (`road_lanes_fan_is_gated_and_spread`) rather than cross-checked here.
            if layer.id == "roads-lanes" {
                continue;
            }
            let authored = authored_layer(&root, &layer.authored);
            // The source layer has to match, with two structural exceptions that are not
            // transcription slips:
            //
            //  * `poi` vs `pois`. The archive's own layer table names it in the singular
            //    (`dict::LAYERS`); the reference tile set uses the plural. Same data.
            //  * `transit`. The reference has no transit layer at all — `v4.pmtiles` does
            //    not carry one — so `transit-rail` names `roads_rail` for the *colour* it
            //    copies while reading a source only our archives have. Its width is not
            //    cross-checked either; see the width arm below.
            if layer.toggle != Some(Toggle::Transit) {
                let expected =
                    if layer.source_layer == "poi" { "pois" } else { layer.source_layer.as_str() };
                assert_eq!(
                    authored.get("source-layer").and_then(Json::as_str),
                    Some(expected),
                    "`{}` reads a different source layer than `{}` does",
                    layer.id,
                    layer.authored,
                );
            }
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
                    // Gap-only casings (bridges-other: authored gap, no width —
                    // the casing IS the gap band, no centre stroke) carry no
                    // width ramp of their own; the stroke tessellator keys off
                    // the gap peak, so width agreement is vacuous there. The
                    // default-1.0 only fires when authored has NO line-width.
                    //
                    // Transit is exempt for a different reason: a route line is
                    // one object the eye follows across the network, so it is a
                    // constant width at every zoom rather than `roads_rail`'s
                    // ramp. `a_transit_line_is_one_constant_width` pins that
                    // deliberate divergence in place of this comparison.
                    let authored_has_width = authored
                        .get("paint")
                        .and_then(|p| p.get("line-width"))
                        .is_some();
                    if authored_has_width && layer.toggle != Some(Toggle::Transit) {
                        assert_ramps_agree(&layer.id, "width", &layer.width, &width);
                    }
                    let gap = authored_property(&authored, "line-gap-width")
                        .unwrap_or_else(|| Ramp::constant(0.0));
                    assert_ramps_agree(&layer.id, "gap_width", &layer.gap_width, &gap);
                }
                LayerKind::Symbol => {
                    // A POI layer's colour is one arm of the authored `text-color` `case`,
                    // exactly as a data-driven fill's colour is one arm of its
                    // `fill-color`. Checked the same way, because the six-way colour split
                    // is most of what "matching the reference" means for this layer — and
                    // a mistyped hex would otherwise only show as a slightly-wrong shade.
                    if let Some(text_color) =
                        authored.get("paint").and_then(|p| p.get("text-color"))
                    {
                        let mut colors = Vec::new();
                        colors_in(text_color, &mut colors);
                        if colors.len() > 1 {
                            assert!(
                                colors.contains(&layer.light),
                                "`{}`'s {:#010X} is not a colour `{}`'s text-color paints: {:?}",
                                layer.id,
                                layer.light,
                                layer.authored,
                                colors.iter().map(|c| format!("{c:#010X}")).collect::<Vec<_>>(),
                            );
                        }
                    }
                    // Text sizes are layout properties in the authored style, not
                    // paint — compared stop-for-stop against `text-size`. Data-driven
                    // sizes (`case` over population_rank) are transcribed as the
                    // constant the arms evaluate to at the reference zooms (see the
                    // flat file), so the cross-check only covers plain ramps.
                    if let Some(authored_size) = authored_layout_ramp(&authored, "text-size") {
                        assert_ramps_agree(&layer.id, "text_size", &layer.text_size, &authored_size);
                    }
                    // Halo WIDTH stays authored (1px everywhere): a transcription
                    // slip is a halo that is missing (0) or doubled, so it fails
                    // the build like any other value. Halo COLOR is deliberately
                    // Halo WIDTH stays authored (1px everywhere) except the
                    // deliberate locality legibility bump (pinned in
                    // `a_line_layer_has_a_width...`); halo COLOR is ours (see
                    // above), not the authored #e0e0e0.
                    if let Some(width) = authored.get("paint").and_then(|p| p.get("text-halo-width")).and_then(Json::as_f64) {
                        let expected = if layer.id == "places-locality" { 1.5 } else { width as f32 };
                        assert!(
                            (layer.halo_width - expected).abs() < 1e-6,
                            "`{}`'s halo_width is {} where basemap.json says {width}",
                            layer.id, layer.halo_width,
                        );
                    }
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

    /// **The deliberate divergence from `roads_rail`**, in place of the width comparison
    /// `the_flat_style_agrees_with_basemap_json` skips for this layer.
    ///
    /// A basemap's rail casing is scenery and thickens with the zoom like every other road. A
    /// transit line is not scenery — it is one object the eye follows from end to end, and a route
    /// that is a hairline at z10 and a band at z18 reads as two different things. So it is a
    /// constant width, and the floor is where the network first appears rather than where the
    /// stroke first has a pixel in it.
    ///
    /// The cap is not arbitrary: `no_line_layer_is_more_than_a_couple_of_dp_wide_below_street_zoom`
    /// walks z0..z8 over the ramp alone and does not consult `min_zoom`, so a constant here is
    /// spent against that budget at every zoom whether or not it is drawn.
    #[test]
    fn a_transit_line_is_one_constant_width_from_its_own_floor() {
        let layer = find("transit-rail");
        assert_eq!(layer.min_zoom, 8, "the network's floor, matching `schema::transit::MIN_ZOOM`");
        assert_eq!(layer.max_zoom, MAX_ZOOM);
        for tenth in 0..=(MAX_ZOOM as u32 * 10) {
            let zoom = tenth as f64 / 10.0;
            assert!(
                (layer.width.at(zoom) - 3.0).abs() < 1e-6,
                "transit-rail is {} Dp at z{zoom}",
                layer.width.at(zoom),
            );
        }
        // Whatever the constant becomes, it has to stay inside the shallow-zoom budget.
        assert!(layer.width.at(0.0) * 2.0 <= 10.0, "over the shallow-zoom width budget");
    }

    /// The corridor fan-out: routes sharing one track are one line at regional zoom and separate
    /// parallel lines once the camera is close enough to tell them apart.
    ///
    /// The *count* steps rather than the spacing ramping. Widening a spacing ramp makes four
    /// lanes narrower at low zoom, which is not the same as a corridor carrying fewer of them:
    /// they all thin together and converge into one unreadable stripe. Stepping the count
    /// instead keeps a constant, legible 6 Dp between adjacent lanes at every zoom and makes a
    /// colour visibly re-assign to a different lane at each boundary.
    #[test]
    fn transit_lanes_step_with_zoom_over_a_constant_spacing() {
        let layer = find("transit-rail");
        // Two adjacent lanes are always 6 Dp apart, whatever the camera is doing.
        for zoom in [0.0, 8.0, 9.5, 11.0, 13.0, 20.0] {
            assert_eq!(layer.spread.at(zoom), 6.0, "the spacing is constant at z{zoom}");
        }
        assert_eq!(layer.lanes.at(8.0).floor(), 1.0, "one corridor below z9");
        assert_eq!(layer.lanes.at(9.0).floor(), 2.0, "two lanes from z9");
        assert_eq!(layer.lanes.at(11.0).floor(), 3.0, "three from z11");
        assert_eq!(layer.lanes.at(13.0).floor(), 4.0, "four from z13");
        assert_eq!(layer.lanes.at(20.0).floor(), 4.0, "and it stays there");
        // Nothing else moves sideways, or every road in the style would — except `roads-lanes`,
        // which fans a multi-lane carriageway into its individual lane dividers by the same
        // mechanism. Its own configuration is pinned by `road_lanes_fan_is_gated_and_spread`.
        for other in layers().iter().filter(|l| l.id != "transit-rail" && l.id != "roads-lanes") {
            assert_eq!(other.spread.peak(), 0.0, "{} must not spread", other.id);
            assert_eq!(other.lanes.peak(), 1.0, "{} must not fan out", other.id);
        }
    }

    /// The road lane fan: `roads-lanes` is the one road layer that spreads, it only appears once
    /// the camera is close enough to make lanes legible, and its lane ramp is high enough to draw
    /// every interior divider of the widest roads. Every other layer is held to no-spread by
    /// `transit_lanes_step_with_zoom_over_a_constant_spacing`.
    #[test]
    fn road_lanes_fan_is_gated_and_spread() {
        let layer = find("roads-lanes");
        assert!(layer.lane_fan(), "roads-lanes must fan into lanes");
        assert_eq!(layer.min_zoom, 16, "the dense lane layer is gated to high zoom");
        assert!(!layer.draws_at(15), "no lanes at z15");
        assert!(layer.draws_at(16), "lanes from z16");
        // The spacing grows with zoom (a lane is a ground distance, unlike a transit corridor's
        // constant screen spacing), and is zero below the gate's reach.
        assert!(layer.spread.at(16.0) > 0.0, "a lane has width at z16");
        assert!(layer.spread.at(20.0) > layer.spread.at(16.0), "and it widens zooming in");
        // High enough that `min(style.lanes, dividers)` never caps a real road's divider count.
        assert!(layer.lanes.at(16.0).floor() >= 7.0, "up to an eight-lane road's dividers");
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
            // Kinds we deliberately do not draw, and why. Checked explicitly so the assertion
            // below still catches an *accidental* divergence from upstream, which is what it is
            // for — a silent one would mean a kind quietly stopped rendering.
            //
            // `protected_area` and `nature_reserve` are the tags the world's MARINE protected
            // areas carry. The sea has no geometry, so nothing is drawn over them, and on a planet
            // build they painted green across open water — the reported bug. Deriving sea geometry
            // to cover them was attempted twice and failed twice; see `mamaps_build`'s
            // `tiler::add_ocean` for both failure modes. Not drawing them is the fix that works.
            //
            // The cost, stated plainly: a protected area or nature reserve *on land* is no longer
            // green. Restore them here the day the sea can paint over them.
            const NOT_DRAWN: &[&str] = &["protected_area", "nature_reserve"];
            let (skipped, admitted): (Vec<String>, Vec<String>) =
                admitted.into_iter().partition(|k| NOT_DRAWN.contains(&k.as_str()));
            for kind in &skipped {
                assert!(
                    !drawn.contains(kind),
                    "`{kind}` is in NOT_DRAWN but a flat layer still draws it",
                );
            }
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
