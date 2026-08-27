//! Paint read from the authored style instead of transcribed into constants.
//!
//! A stroke width is not a property of a layer, it is a function of the camera's zoom: every
//! road width in `style/basemap.json` is an `interpolate` on `["zoom"]` that starts at zero
//! and grows, which is how the authored style both scales roads and gates them on. A single
//! number cannot express that, and no choice of number can — one that reads correctly at
//! street level is continent-wide at world level.
//!
//! So the ramps are kept unevaluated here and evaluated per frame against the camera. That is
//! affordable because width reaches the GPU as a push constant and `shaders/line.vert`
//! extrudes the centreline to it, so a width that changes every frame re-tessellates
//! nothing. It is also why this is per *frame* and not per tile: the
//! camera's zoom is fractional and continuous, and quantising it to the integer tile zoom
//! would make roads jump in width at each level instead of growing smoothly.
//!
//! ## Fills
//!
//! [`FillStyle`] does the same job for `fill` layers, but the answer it computes is a *layer
//! list* rather than a per-frame number. Fill colour reaches the GPU as one push constant per
//! (layer, tile) draw, so a `fill-color` that varies per feature cannot be expressed in one
//! draw — it has to become one synthetic layer per colour. Only two of the authored fill
//! layers are data-driven (`landcover`'s `match` on `kind` and `landuse_park`'s `case`), and
//! expanding those by evaluating the authored expression per admitted `kind` is what
//! [`super::layers`] now does instead of transcribing 20 colours by eye.
//!
//! Fill opacity is evaluated per frame too, for the same reason and with the same machinery.
//! The authored ramps are the whole of how low zooms are meant to look: `landcover` fades from
//! 1 at z5 to 0 at z7 and `landuse_park` rises from 0 at z6 to 1 at z11. Baking either into a
//! constant alpha plus an integer zoom gate draws `landcover` at **full** strength at z6 where
//! the ramp asks for half, which lays a flat mint blanket over a whole continent, and pops
//! `landuse_park` on at full strength at z7 where the ramp asks for a fifth. Both were plainly
//! visible on device; neither shows up in a host test that only measures tessellated area.

use super::expr::{self, Context, Value};
use super::Layer;
use serde_json::Value as Json;
use std::collections::HashMap;
use std::sync::OnceLock;

/// The authored style, vendored beside the sources.
///
/// Compiled in rather than read from assets: it is 61 KB, it is the same file for every host
/// app, and it makes the loader host-testable rather than reachable only from a device.
const BASEMAP: &str = include_str!("../../style/basemap.json");

/// The deepest zoom the renderer draws at, and the top of every derived zoom gate.
///
/// The archive stops at z14 and the renderer overzooms past it; 22 is where
/// [`super::Layer::max_zoom`] has always been pinned.
pub const MAX_ZOOM: u8 = 22;

/// `line-width`'s default in the style spec. `line-gap-width`'s is zero.
const DEFAULT_WIDTH_DP: f32 = 1.0;

/// The narrowest half-width worth drawing, in device pixels.
///
/// The authored ramps go continuously to zero and the line pipeline is single-sampled with no
/// coverage term in its shader, so a quad narrower than a pixel is filled only where it happens
/// to straddle a pixel centre. That rasterises as stipple which crawls along the road while
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
    /// A zoom where this is false is a zoom the authored style ramped the layer out at, which is
    /// the style's own gate and the reason road layers need no `min_zoom`.
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

/// One authored `line` layer's width ramps, held unevaluated.
pub struct LinePaint {
    pub id: String,
    min_zoom: f64,
    max_zoom: f64,
    width: Option<Json>,
    gap_width: Option<Json>,
}

impl LinePaint {
    fn parse(json: &Json) -> Option<LinePaint> {
        let paint = json.get("paint");
        Some(LinePaint {
            id: json.get("id").and_then(Json::as_str)?.to_string(),
            min_zoom: json.get("minzoom").and_then(Json::as_f64).unwrap_or(0.0),
            max_zoom: json.get("maxzoom").and_then(Json::as_f64).unwrap_or(f64::INFINITY),
            width: paint.and_then(|p| p.get("line-width")).cloned(),
            gap_width: paint.and_then(|p| p.get("line-gap-width")).cloned(),
        })
    }

    /// Does this authored layer draw at `zoom`?
    ///
    /// `maxzoom` is exclusive, as the spec defines it, and that is what makes the style's
    /// `*_casing_early`/`*_casing_late` pairs work: they are split at z12 by one's `maxzoom`
    /// and the other's `minzoom`, so exactly one of the two applies at any zoom.
    fn covers(&self, zoom: f64) -> bool {
        zoom >= self.min_zoom && zoom < self.max_zoom
    }

    fn stroke(&self, zoom: f64) -> Stroke {
        // A ramp this evaluator cannot read is better left undrawn than drawn at a guessed
        // width, because a guess is constant across zoom and a constant width is the defect
        // this replaces.
        let (Some(width_dp), Some(gap_width_dp)) = (
            evaluate(self.width.as_ref(), zoom, DEFAULT_WIDTH_DP, "LineString"),
            evaluate(self.gap_width.as_ref(), zoom, 0.0, "LineString"),
        ) else {
            return Stroke::NONE;
        };
        Stroke { width_dp, gap_width_dp }
    }
}

/// Evaluate one width ramp at `zoom`, or `None` if it will not evaluate.
///
/// Width ramps key on nothing but zoom, so there is no feature to evaluate against. An absent
/// property is the spec default; an expression that *fails* is not, and the two are
/// deliberately different — see [`LinePaint::stroke`].
fn evaluate(expr: Option<&Json>, zoom: f64, default: f32, geometry_type: &str) -> Option<f32> {
    let Some(expr) = expr else {
        return Some(default);
    };
    let properties = HashMap::new();
    let context = Context { zoom, properties: &properties, geometry_type };
    expr::eval(expr, &context).ok()?.as_number().map(|width| width as f32)
}

/// Evaluate a colour-valued paint property, for one feature's properties at one zoom.
///
/// The seam [`super::expr`]'s `mix` points at: a colour is a string to the evaluator and an
/// ARGB word to everything downstream, and this is the only place that knows both. An exact
/// stop lookup, which is all the authored fill paint needs — no `fill-color` in the file
/// interpolates.
pub fn color_at(
    paint: Option<&Json>,
    zoom: f64,
    properties: &HashMap<String, Value>,
) -> Option<u32> {
    let context = Context { zoom, properties, geometry_type: "Polygon" };
    let value = expr::eval(paint?, &context).ok()?;
    expr::parse_color(value.as_str()?)
}

/// The authored style's `line` paint.
#[derive(Default)]
pub struct LineStyle {
    layers: Vec<LinePaint>,
}

impl LineStyle {
    pub fn parse(source: &str) -> Result<LineStyle, String> {
        let root: Json =
            serde_json::from_str(source).map_err(|e| format!("basemap.json is not JSON: {e}"))?;
        let layers = root
            .get("layers")
            .and_then(Json::as_array)
            .ok_or("basemap.json has no `layers` array")?;
        Ok(LineStyle {
            layers: layers
                .iter()
                .filter(|layer| layer.get("type").and_then(Json::as_str) == Some("line"))
                .filter_map(LinePaint::parse)
                .collect(),
        })
    }

    pub fn get(&self, id: &str) -> Option<&LinePaint> {
        self.layers.iter().find(|layer| layer.id == id)
    }

    /// The stroke `layer` draws at `zoom`, in Dp.
    ///
    /// A layer naming no authored counterpart keeps its constants, which is what leaves fills
    /// and the layers this change does not cover alone. A layer that names counterparts but
    /// whose every counterpart gates `zoom` out draws nothing.
    pub fn stroke(&self, layer: &Layer, zoom: f64) -> Stroke {
        if layer.line_paint.is_empty() {
            return Stroke { width_dp: layer.width_dp, gap_width_dp: layer.gap_width_dp };
        }
        layer
            .line_paint
            .iter()
            .filter_map(|id| self.get(id))
            .find(|paint| paint.covers(zoom))
            .map_or(Stroke::NONE, |paint| paint.stroke(zoom))
    }
}

/// The vendored style's line paint, parsed once.
///
/// Immutable and derived from a compiled-in string, so this is a constant table that happens
/// to need a parser rather than shared state. Keeping it here rather than threading it from
/// Kotlin also keeps the render and tessellation threads reading the same paint without
/// having to agree on it across JNI.
pub fn authored() -> &'static LineStyle {
    static AUTHORED: OnceLock<LineStyle> = OnceLock::new();
    AUTHORED.get_or_init(|| LineStyle::parse(BASEMAP).unwrap_or_default())
}

// --- fills ---------------------------------------------------------------------------------

/// The parsed style, held for the whole process so what it borrows out lives as long.
///
/// [`super::Layer`] carries `&'static str` for its id and its `kind` whitelist, so the derived
/// fill layers have to borrow their strings from somewhere permanent. Borrowing them from a
/// parse that lives in a `static` is what makes that work without leaking a string per kind.
fn root() -> &'static Json {
    static ROOT: OnceLock<Json> = OnceLock::new();
    ROOT.get_or_init(|| serde_json::from_str(BASEMAP).unwrap_or(Json::Null))
}

/// One colour an authored `fill` layer paints, and the feature `kind`s that take it.
///
/// An authored layer whose `fill-color` is a literal string has exactly one of these. The two
/// that are data-driven have one per distinct colour, because fill colour reaches the GPU as
/// one push constant per (layer, tile) draw: a `match` on `kind` cannot be expressed inside a
/// draw, so it has to become several draws.
pub struct FillDraw {
    /// The authored layer's id, suffixed with the first `kind` of the arm when the layer
    /// paints more than one colour.
    pub id: String,
    /// The authored layer this was derived from, which is what owns the `fill-opacity` ramp.
    pub authored: &'static str,
    pub source_layer: &'static str,
    /// The `kind`s that take this colour, or empty for every feature the layer admits.
    ///
    /// Empty only ever happens for the arm derived from the authored expression's *fallback*,
    /// and only when the authored layer has no `kind` filter to restrict it — which is why the
    /// fallback is emitted first and the specific arms paint over it.
    pub kinds: Vec<&'static str>,
    /// ARGB at **full** `fill-opacity`.
    ///
    /// The ramp is not folded in here: it is a function of the camera's zoom, so it is
    /// evaluated per frame by [`FillStyle::opacity`] exactly as a line's width is. Any alpha in
    /// this word came from the authored colour itself, e.g. an `rgba()` literal.
    pub color: u32,
}

/// One authored `fill` layer, and the draws derived from it.
pub struct FillPaint {
    pub id: &'static str,
    pub source_layer: &'static str,
    min_zoom: f64,
    max_zoom: f64,
    filter: Option<&'static Json>,
    color: Option<&'static Json>,
    opacity: Option<&'static Json>,
    draws: Vec<FillDraw>,
}

impl FillPaint {
    fn parse(json: &'static Json) -> Option<FillPaint> {
        let paint = json.get("paint");
        let mut layer = FillPaint {
            id: json.get("id").and_then(Json::as_str)?,
            source_layer: json.get("source-layer").and_then(Json::as_str)?,
            min_zoom: json.get("minzoom").and_then(Json::as_f64).unwrap_or(0.0),
            max_zoom: json.get("maxzoom").and_then(Json::as_f64).unwrap_or(f64::INFINITY),
            filter: json.get("filter"),
            color: paint.and_then(|p| p.get("fill-color")),
            opacity: paint.and_then(|p| p.get("fill-opacity")),
            draws: Vec::new(),
        };
        layer.draws = layer.derive_draws();
        Some(layer)
    }

    /// Does this authored layer draw at `zoom`?
    ///
    /// `maxzoom` is exclusive, as the spec defines it. No `fill` layer in the file sets either
    /// bound, so today this is the identity — but a restyle that adds one should be followed
    /// rather than ignored.
    fn covers(&self, zoom: f64) -> bool {
        zoom >= self.min_zoom && zoom < self.max_zoom
    }

    fn opacity_at(&self, zoom: f64) -> f32 {
        // An opacity ramp this evaluator cannot read leaves the layer undrawn, for the same
        // reason a width ramp does: a guessed constant is the defect being removed.
        evaluate(self.opacity, zoom, 1.0, "Polygon").unwrap_or(0.0)
    }

    /// The shallowest integer zoom the authored `fill-opacity` is non-zero at, if any.
    ///
    /// Used only to pick a zoom at which to *read the colour*. It is deliberately **not** a
    /// gate: [`super::Layer::min_zoom`] is about which tiles carry the layer, and deriving it
    /// from the ramp instead made the geometry that exists depend on which pyramid level
    /// happened to be resident — so shapes came and went as the camera zoomed, which is a far
    /// worse defect than the one it was meant to avoid.
    fn first_drawn_zoom(&self) -> Option<u8> {
        (0..=MAX_ZOOM).find(|&zoom| self.covers(zoom as f64) && self.opacity_at(zoom as f64) > 0.0)
    }

    /// The colour this layer paints a feature of `kind`, at full opacity.
    fn color_for(&self, kind: Option<&'static str>, zoom: f64) -> Option<u32> {
        let mut properties = HashMap::new();
        if let Some(kind) = kind {
            properties.insert("kind".to_string(), Value::String(kind.to_string()));
        }
        color_at(self.color, zoom, &properties)
    }

    fn derive_draws(&self) -> Vec<FillDraw> {
        // A layer whose ramp is zero everywhere draws nothing at any zoom.
        let Some(first_zoom) = self.first_drawn_zoom() else {
            return Vec::new();
        };
        let admitted = filter_kinds(self.filter);
        // Where the candidate `kind`s come from: a layer with a `kind` filter can only ever
        // draw those, so any arm of its colour expression outside the filter is unreachable. A
        // layer without one can draw anything, so the only kinds worth asking about are the
        // ones its own colour expression names.
        let candidates: Vec<&'static str> = if admitted.is_empty() {
            self.color.map(kind_labels).unwrap_or_default()
        } else {
            admitted.clone()
        };
        // Colour is read at the shallowest zoom the layer draws at. No `fill-color` in the file
        // keys on zoom, so this only has to be a zoom the layer is actually drawn at.
        let zoom = first_zoom as f64;

        let mut draws: Vec<FillDraw> = Vec::new();
        let make = |id: String, kinds: Vec<&'static str>, color: u32| FillDraw {
            id,
            authored: self.id,
            source_layer: self.source_layer,
            kinds,
            color,
        };

        // The expression's fallback arm, drawn first so the specific kinds paint over it. Only
        // for a layer with no `kind` filter: an unfiltered draw over a filtered layer would
        // paint the very kinds the authored filter excludes.
        if admitted.is_empty() {
            if let Some(color) = self.color_for(None, zoom) {
                draws.push(make(self.id.to_string(), Vec::new(), color));
            }
        }
        for kind in candidates {
            let Some(color) = self.color_for(Some(kind), zoom) else { continue };
            match draws.iter_mut().find(|draw| draw.color == color) {
                // Already drawn: either by the unfiltered fallback, which covers this kind
                // anyway, or by an earlier arm this kind shares a colour with.
                Some(draw) if draw.kinds.is_empty() => {}
                Some(draw) => draw.kinds.push(kind),
                None => draws.push(make(format!("{}:{kind}", self.id), vec![kind], color)),
            }
        }
        // A layer that paints one colour needs no arm suffix, which is 13 of the 15.
        if let [only] = draws.as_mut_slice() {
            only.id = self.id.to_string();
        }
        draws
    }
}

/// The `kind` values an authored filter admits, or an empty list for "any of them".
///
/// The four shapes `style/basemap.json` uses on its `fill` layers, and no more. Anything else
/// reads as unrestricted, which is what MapLibre would draw if the filter passed everything —
/// the permissive answer, so a restyle shows up as too much on screen rather than a layer
/// silently missing.
fn filter_kinds(filter: Option<&'static Json>) -> Vec<&'static str> {
    let Some(Json::Array(items)) = filter else {
        return Vec::new();
    };
    let op = items.first().and_then(Json::as_str).unwrap_or_default();
    let args = &items[1..];
    match op {
        // `["==", "kind", K]`. A `$type` filter restricts geometry, not `kind`.
        "==" if args.first().and_then(Json::as_str) == Some("kind") => {
            args.get(1).and_then(Json::as_str).into_iter().collect()
        }
        // `["in", "kind", K, ...]`
        "in" if args.first().and_then(Json::as_str) == Some("kind") => {
            args[1..].iter().filter_map(Json::as_str).collect()
        }
        // `["any", inner, ...]` — a union, and unrestricted if any branch is.
        "any" => {
            let mut out = Vec::new();
            for inner in args {
                let kinds = filter_kinds(Some(inner));
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

/// Every `kind` value a colour expression names, in the order it names them.
///
/// Used only to discover which kinds are worth asking [`FillPaint::color_for`] about; the
/// colour itself always comes from evaluating the authored expression, never from reading its
/// shape. Strings that are colours, operator names or the `kind` key itself are not kinds.
fn kind_labels(paint: &'static Json) -> Vec<&'static str> {
    let mut out = Vec::new();
    collect_labels(paint, &mut out);
    out
}

fn collect_labels(paint: &'static Json, out: &mut Vec<&'static str>) {
    match paint {
        Json::String(s) => {
            if s != "kind" && expr::parse_color(s).is_none() && !out.contains(&s.as_str()) {
                out.push(s);
            }
        }
        Json::Array(items) => {
            let op = items.first().and_then(Json::as_str);
            // `["get", k]` and friends name a property, not a value it can take.
            if matches!(op, Some("get") | Some("has") | Some("!has")) {
                return;
            }
            for item in &items[usize::from(op.is_some())..] {
                collect_labels(item, out);
            }
        }
        _ => {}
    }
}

/// The authored style's `fill` paint, expanded to one entry per colour.
#[derive(Default)]
pub struct FillStyle {
    layers: Vec<FillPaint>,
}

impl FillStyle {
    /// Read the `fill` layers out of an already-parsed style.
    ///
    /// Takes the parsed root rather than the source text, unlike [`LineStyle::parse`], because
    /// what it produces borrows from it — see [`root`].
    pub fn read(root: &'static Json) -> Result<FillStyle, String> {
        let layers = root
            .get("layers")
            .and_then(Json::as_array)
            .ok_or("basemap.json has no `layers` array")?;
        Ok(FillStyle {
            layers: layers
                .iter()
                .filter(|layer| layer.get("type").and_then(Json::as_str) == Some("fill"))
                .filter_map(FillPaint::parse)
                .collect(),
        })
    }

    pub fn get(&self, id: &str) -> Option<&FillPaint> {
        self.layers.iter().find(|layer| layer.id == id)
    }

    /// Every derived draw, in the authored file's own layer order.
    ///
    /// Order **is** draw order, which is why this is a flat list rather than a lookup: the
    /// authored style draws `landuse_park` through `landuse_runway` before `water` and only
    /// `landuse_pedestrian` and `landuse_pier` after it, and getting that wrong puts parks on
    /// top of rivers.
    pub fn draws(&self) -> impl Iterator<Item = &FillDraw> {
        self.layers.iter().flat_map(|layer| layer.draws.iter())
    }

    /// The `fill-opacity` `layer` draws at `zoom`, in 0..=1.
    ///
    /// The fill counterpart of [`LineStyle::stroke`], and per *frame* for the same reason: the
    /// camera's zoom is fractional and continuous, so quantising it to the integer tile zoom is
    /// what makes a layer appear at a step instead of fading in.
    ///
    /// A layer naming no authored counterpart is fully opaque, which is what leaves the
    /// hand-written line layers alone. A ramp this evaluator cannot read draws nothing, exactly
    /// as an unreadable width ramp does: a guessed constant is the defect being removed.
    pub fn opacity(&self, layer: &Layer, zoom: f64) -> f32 {
        if layer.fill_paint.is_empty() {
            return 1.0;
        }
        self.get(layer.fill_paint).map_or(0.0, |paint| paint.opacity_at(zoom))
    }
}

/// The vendored style's fill paint, parsed and expanded once.
pub fn authored_fills() -> &'static FillStyle {
    static AUTHORED: OnceLock<FillStyle> = OnceLock::new();
    AUTHORED.get_or_init(|| FillStyle::read(root()).unwrap_or_default())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::style::{layers, LayerKind};

    /// The archive's zoom range, plus the overzoom the renderer allows past it.
    const ZOOMS: std::ops::RangeInclusive<i32> = 0..=22;

    fn style() -> &'static LineStyle {
        authored()
    }

    fn line_layers() -> Vec<Layer> {
        layers().into_iter().filter(|layer| layer.kind == LayerKind::Line).collect()
    }

    fn width_of(id: &str, zoom: f64) -> f32 {
        style().get(id).map_or(f32::NAN, |paint| paint.stroke(zoom).width_dp)
    }

    #[test]
    fn the_vendored_style_parses_and_holds_its_line_layers() {
        let parsed = LineStyle::parse(BASEMAP).expect("basemap.json should parse");
        // 41 `line` layers in the authored file; asserting the exact count would fail on any
        // harmless restyle, but losing most of them means the filter broke.
        assert!(parsed.layers.len() > 30, "found {} line layers", parsed.layers.len());
        assert!(parsed.get("roads_highway").is_some());
        assert!(parsed.get("roads_highway_casing_early").is_some());
        // A `fill` layer must not be picked up by the `line` filter.
        assert!(parsed.get("buildings").is_none());
    }

    #[test]
    fn every_authored_layer_a_style_layer_names_actually_exists() {
        for layer in layers() {
            for id in layer.line_paint {
                assert!(
                    style().get(id).is_some(),
                    "`{}` names authored layer `{id}`, which is not in basemap.json",
                    layer.id,
                );
            }
        }
    }

    #[test]
    fn every_width_ramp_evaluates_across_the_zoom_range() {
        let properties = HashMap::new();
        for layer in style().layers.iter() {
            // Tenths, not integers: every authored stop is an integer or a half, so integer
            // zooms alone would only ever land on segment boundaries.
            for tenth in 0..=220 {
                let context = Context {
                    zoom: tenth as f64 / 10.0,
                    properties: &properties,
                    geometry_type: "LineString",
                };
                for (property, expr) in
                    [("line-width", &layer.width), ("line-gap-width", &layer.gap_width)]
                {
                    let Some(expr) = expr else { continue };
                    let value = expr::eval(expr, &context).unwrap_or_else(|e| {
                        panic!("{} {property} at z{}: {e}", layer.id, tenth as f64 / 10.0)
                    });
                    assert!(
                        value.as_number().is_some(),
                        "{} {property} at z{} is not a number: {value:?}",
                        layer.id,
                        tenth as f64 / 10.0,
                    );
                }
            }
        }
    }

    /// `gapped()` reads the *constant* gap width and it is what decides, at tessellation time,
    /// whether a layer emits one centred band or two offset ones. The ramp only sets the
    /// distance. So the constant has to agree with the ramp about whether there is a gap at
    /// all: a layer that tessellates a plain stroke discards the pushed gap silently.
    #[test]
    fn the_constant_that_picks_the_casing_geometry_agrees_with_the_authored_gap_ramp() {
        for layer in line_layers() {
            if layer.line_paint.is_empty() {
                continue;
            }
            let authored_gap = (0..=220).any(|tenth| {
                style().stroke(&layer, tenth as f64 / 10.0).gap_width_dp > 0.0
            });
            assert_eq!(
                layer.gapped(),
                authored_gap,
                "`{}` tessellates {} but the authored ramp says {}",
                layer.id,
                if layer.gapped() { "two bands" } else { "one band" },
                if authored_gap { "there is a gap" } else { "there is none" },
            );
        }
    }

    /// A ramp that will not evaluate must leave the layer undrawn rather than pick a width:
    /// any single guessed number is constant across zoom, which is the defect being removed.
    #[test]
    fn a_ramp_this_evaluator_cannot_read_draws_nothing() {
        let unreadable = LinePaint {
            id: "synthetic".to_string(),
            min_zoom: 0.0,
            max_zoom: f64::INFINITY,
            width: Some(serde_json::json!(["cubic-bezier", 0.4, 0.0, 0.6, 1.0])),
            gap_width: None,
        };
        assert!(expr::eval(
            unreadable.width.as_ref().expect("width"),
            &Context {
                zoom: 4.0,
                properties: &HashMap::new(),
                geometry_type: "LineString",
            },
        )
        .is_err());
        assert_eq!(unreadable.stroke(4.0), Stroke::NONE);
        assert!(!unreadable.stroke(4.0).visible());
    }

    /// The defect: a constant width is a street-level width applied at world level.
    #[test]
    fn a_highway_at_world_zoom_is_a_small_fraction_of_its_width_at_street_zoom() {
        let world = width_of("roads_highway", 4.0);
        let street = width_of("roads_highway", 14.0);
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
                let stroke = style().stroke(&layer, zoom);
                let total = stroke.width_dp * 2.0 + stroke.gap_width_dp * 2.0;
                assert!(
                    total <= 10.0,
                    "`{}` is {total} Dp across at z{zoom}",
                    layer.id,
                );
            }
        }
    }

    #[test]
    fn width_grows_continuously_with_zoom_rather_than_stepping_per_level() {
        // Across an integer boundary the authored ramp is inside one segment, so a width that
        // is quantised to the tile zoom would repeat itself here.
        let widths: Vec<f32> =
            (0..=10).map(|step| width_of("roads_highway", 13.5 + step as f64 / 10.0)).collect();
        for pair in widths.windows(2) {
            let (lower, upper) = (pair[0], pair[1]);
            assert!(upper > lower, "width stepped rather than grew: {widths:?}");
        }
        // And it really is the same segment either side of z14, not a coincidence of stops.
        assert!(widths[4] < widths[5] && widths[5] < widths[6], "{widths:?}");
    }

    #[test]
    fn a_highway_grows_monotonically_across_the_whole_zoom_range() {
        let mut previous = -1.0;
        for tenth in 0..=220 {
            let width = width_of("roads_highway", tenth as f64 / 10.0);
            assert!(width >= previous, "width fell at z{}", tenth as f64 / 10.0);
            previous = width;
        }
    }

    #[test]
    fn the_casing_pair_split_at_z12_applies_exactly_one_of_the_two() {
        for id in ["roads_highway_casing", "roads_major_casing"] {
            let early = style().get(&format!("{id}_early")).expect("early half");
            let late = style().get(&format!("{id}_late")).expect("late half");
            for tenth in 0..=220 {
                let zoom = tenth as f64 / 10.0;
                assert_ne!(
                    early.covers(zoom),
                    late.covers(zoom),
                    "{id} at z{zoom}: early {}, late {}",
                    early.covers(zoom),
                    late.covers(zoom),
                );
            }
        }
    }

    #[test]
    fn a_layer_naming_no_authored_paint_keeps_its_constants() {
        let plain = Layer { line_paint: &[], ..layers().into_iter().next().expect("earth") };
        let stroke = style().stroke(&plain, 4.0);
        assert_eq!(stroke.width_dp, plain.width_dp);
        assert_eq!(stroke.gap_width_dp, plain.gap_width_dp);
    }

    /// Root cause of roads appearing five levels early: `roads-highway` has no `min_zoom`, so
    /// its gate has to be the authored ramp reaching zero.
    #[test]
    fn an_ungated_road_layer_is_still_not_drawn_at_world_zoom() {
        let highway = line_layers()
            .into_iter()
            .find(|layer| layer.id == "roads-highway")
            .expect("roads-highway");
        assert_eq!(highway.min_zoom, 0, "this asserts the ramp gates, not `min_zoom`");
        for tenth in 0..=30 {
            let zoom = tenth as f64 / 10.0;
            assert!(!style().stroke(&highway, zoom).visible(), "drawn at z{zoom}");
        }
    }

    #[test]
    fn a_stroke_with_a_gap_but_no_width_is_not_visible() {
        assert!(!Stroke { width_dp: 0.0, gap_width_dp: 8.0 }.visible());
        assert!(Stroke { width_dp: 0.5, gap_width_dp: 0.0 }.visible());
        assert!(!Stroke::NONE.visible());
    }

    #[test]
    fn every_line_layer_draws_something_somewhere_in_the_zoom_range() {
        for layer in line_layers() {
            let drawn = ZOOMS.into_iter().any(|zoom| style().stroke(&layer, zoom as f64).visible());
            assert!(drawn, "`{}` is invisible at every zoom", layer.id);
        }
    }

    /// Whatever the authored ramps say, a road has to be wide enough to see once the map is a
    /// street map — otherwise this trades one visible defect for another.
    #[test]
    fn roads_are_still_drawn_at_street_zoom() {
        for id in ["roads-highway", "roads-major", "roads-minor"] {
            let layer = line_layers().into_iter().find(|layer| layer.id == id).expect(id);
            let stroke = style().stroke(&layer, 16.0);
            assert!(stroke.width_dp >= 1.0, "`{id}` is {} Dp at z16", stroke.width_dp);
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

    // --- fills -------------------------------------------------------------

    fn fills() -> &'static FillStyle {
        authored_fills()
    }

    #[test]
    fn the_vendored_style_parses_and_holds_its_fill_layers() {
        // 15 `fill` layers in the authored file.
        assert_eq!(fills().layers.len(), 15, "found {} fill layers", fills().layers.len());
        assert!(fills().get("earth").is_some());
        assert!(fills().get("landuse_park").is_some());
        // A `line` layer must not be picked up by the `fill` filter.
        assert!(fills().get("roads_highway").is_none());
    }

    /// The derived draws are in the authored file's own order, which is draw order. The authored
    /// style puts `landuse_park` through `landuse_runway` *before* `water` and only
    /// `landuse_pedestrian` and `landuse_pier` after it; flattening landuse to one side of water
    /// puts parks on top of rivers or rivers on top of parks.
    #[test]
    fn the_derived_draws_keep_the_authored_order() {
        let ids: Vec<&str> = fills().draws().map(|draw| draw.id.as_str()).collect();
        let at = |id: &str| ids.iter().position(|other| *other == id).unwrap_or_else(|| panic!("{id}"));
        assert!(at("earth") < at("landcover"));
        assert!(at("landuse_park:national_park") < at("water"));
        assert!(at("landuse_runway") < at("water"));
        assert!(at("water") < at("landuse_pedestrian"));
        assert!(at("landuse_pier") < at("buildings"));
    }

    /// A data-driven `fill-color` becomes one draw per colour, with the expression's **fallback
    /// first**: it is unfiltered, so it has to draw under the specific kinds rather than over
    /// them. Drawn last it collapses seven colours into one.
    #[test]
    fn an_unfiltered_fallback_arm_is_derived_first() {
        let landcover = fills().get("landcover").expect("landcover");
        let (first, rest) = landcover.draws.split_first().expect("at least the fallback");
        assert_eq!(first.id, "landcover");
        assert!(first.kinds.is_empty(), "the fallback draws every kind");
        assert_eq!(rest.len(), 6, "one per authored `match` arm");
        for draw in rest {
            assert!(!draw.kinds.is_empty(), "{} must be filtered", draw.id);
        }
    }

    /// A layer whose authored filter is a `kind` whitelist gets **no** unfiltered arm: one would
    /// draw the very kinds the filter excludes, in the fallback colour.
    #[test]
    fn a_filtered_layer_derives_no_unfiltered_arm() {
        for id in ["landuse_park", "buildings", "landuse_school"] {
            let layer = fills().get(id).expect(id);
            for draw in &layer.draws {
                assert!(!draw.kinds.is_empty(), "{} derived an unfiltered arm", draw.id);
            }
        }
        // And the kinds it does derive are all admitted by the authored filter.
        let admitted = filter_kinds(fills().get("landuse_park").expect("landuse_park").filter);
        for draw in &fills().get("landuse_park").expect("landuse_park").draws {
            for kind in &draw.kinds {
                assert!(admitted.contains(kind), "`{kind}` is outside the authored filter");
            }
        }
    }

    /// The authored `fill-opacity` ramp is the *only* thing that gates a fill, and it is
    /// continuous. Pinned at the zooms that were visibly wrong on device.
    #[test]
    fn the_opacity_ramp_is_the_only_gate_and_it_is_continuous() {
        for id in ["landcover", "landuse_park"] {
            let layer = fills().get(id).expect(id);
            let mut previous = f32::NAN;
            for tenth in 0..=(MAX_ZOOM as u32 * 10) {
                let zoom = tenth as f64 / 10.0;
                let opacity = layer.opacity_at(zoom);
                assert!((0.0..=1.0).contains(&opacity), "{id} at z{zoom} is {opacity}");
                // No step larger than the ramp's own slope between adjacent tenths: a jump
                // means something quantised the zoom, which is how a layer pops instead of
                // fading.
                if previous.is_finite() {
                    assert!(
                        (opacity - previous).abs() < 0.06,
                        "{id} jumped {previous} -> {opacity} at z{zoom}",
                    );
                }
                previous = opacity;
            }
        }
        // And the shallowest drawn zoom, which is only used to pick a zoom to read colour at.
        assert_eq!(fills().get("landcover").expect("landcover").first_drawn_zoom(), Some(0));
        assert_eq!(fills().get("landuse_park").expect("landuse_park").first_drawn_zoom(), Some(7));
    }

    /// `fill-opacity` is **not** folded into the layer table: it is a function of the camera's
    /// zoom, so it is evaluated per frame. Baking it in drew `landcover` at full strength at z6
    /// where the authored ramp asks for half.
    #[test]
    fn fill_opacity_is_a_ramp_evaluated_per_frame_not_a_baked_alpha() {
        let alpha = |id: &str| {
            fills().draws().find(|d| d.id == id).unwrap_or_else(|| panic!("{id}")).color >> 24
        };
        assert_eq!(alpha("buildings"), 0xFF, "the 0.5 is the ramp's, not the colour's");
        assert_eq!(alpha("landuse_urban_green"), 0xFF);
        assert_eq!(alpha("earth"), 0xFF);
        assert_eq!(alpha("landcover:glacier"), 0xFF, "rgba(255, 255, 255, 1)");

        // And the ramps themselves read the authored values.
        let at = |id: &str, zoom: f64| fills().get(id).unwrap_or_else(|| panic!("{id}")).opacity_at(zoom);
        assert_eq!(at("buildings", 16.0), 0.5, "a literal fill-opacity");
        assert_eq!(at("landuse_urban_green", 16.0), 0.7);
        assert_eq!(at("earth", 4.0), 1.0, "no fill-opacity is fully opaque");
        // The two ramps, at the zooms that were visibly wrong on device.
        assert_eq!(at("landcover", 5.0), 1.0);
        assert_eq!(at("landcover", 6.0), 0.5, "half, not the full blanket");
        assert_eq!(at("landcover", 7.0), 0.0);
        assert_eq!(at("landuse_park", 6.0), 0.0);
        assert!((at("landuse_park", 7.0) - 0.2).abs() < 1e-6, "a fifth, not full green");
        assert_eq!(at("landuse_park", 11.0), 1.0);
    }

    /// The fill counterpart of `LineStyle::stroke`, resolved through a real derived layer.
    #[test]
    fn the_opacity_of_a_derived_layer_follows_its_authored_ramp() {
        let layers = crate::style::layers();
        let of = |id: &str| layers.iter().find(|l| l.id == id).unwrap_or_else(|| panic!("{id}"));
        let grassland = of("landcover:grassland");
        assert_eq!(fills().opacity(grassland, 5.0), 1.0);
        assert_eq!(fills().opacity(grassland, 6.0), 0.5, "an arm inherits its layer's ramp");
        assert_eq!(fills().opacity(grassland, 7.0), 0.0, "which is also its gate");
        // Continuous, not stepped: the whole point of evaluating per frame.
        let half = fills().opacity(grassland, 6.5);
        assert!(half > 0.2 && half < 0.3, "got {half}");
        // A layer naming no authored fill paint is opaque, which leaves the line layers alone.
        let road = of("roads-major");
        assert_eq!(fills().opacity(road, 10.0), 1.0);
    }

    /// A filter shape this does not understand reads as unrestricted, which is the permissive
    /// answer: too much on screen is a visible bug, a silently missing layer is not.
    #[test]
    fn the_filter_reader_handles_the_shapes_the_style_uses() {
        let read = |source: &str| {
            let json: Json = serde_json::from_str(source).expect("valid JSON");
            // Leaked so the borrow is 'static, as it is in the real parse.
            filter_kinds(Some(Box::leak(Box::new(json))))
        };
        assert_eq!(read(r#"["==","kind","pier"]"#), vec!["pier"]);
        assert_eq!(read(r#"["in","kind","school","college"]"#), vec!["school", "college"]);
        assert_eq!(read(r#"["any",["in","kind","runway","taxiway"]]"#), vec!["runway", "taxiway"]);
        // `$type` restricts geometry, not `kind`, so it admits everything.
        assert!(read(r#"["==","$type","Polygon"]"#).is_empty());
        assert!(filter_kinds(None).is_empty());
        assert!(read(r#"["!in","kind","pier"]"#).is_empty(), "unrecognised reads as any");
    }

    /// The kind labels are only used to decide which kinds are worth asking about; the colour
    /// itself always comes from evaluating the authored expression.
    #[test]
    fn the_kind_labels_of_a_colour_expression_exclude_colours_and_operators() {
        let expr: &'static Json = Box::leak(Box::new(
            serde_json::from_str(
                r##"["match",["get","kind"],"grassland","rgba(1, 2, 3, 1)","barren","#fff3d7","#c4e7d2"]"##,
            )
            .expect("valid JSON"),
        ));
        assert_eq!(kind_labels(expr), vec!["grassland", "barren"]);
    }

    #[test]
    fn color_at_reads_a_data_driven_colour_for_one_feature() {
        let paint: &Json = &serde_json::json!([
            "case",
            ["in", ["get", "kind"], ["literal", ["military"]]],
            "#c6dcdc",
            "#e2dfda",
        ]);
        let mut properties = HashMap::new();
        properties.insert("kind".to_string(), Value::String("military".to_string()));
        assert_eq!(color_at(Some(paint), 8.0, &properties), Some(0xFFC6DCDC));
        assert_eq!(color_at(Some(paint), 8.0, &HashMap::new()), Some(0xFFE2DFDA), "the fallback");
        // A colour this cannot read is `None`, never a substituted default.
        assert_eq!(color_at(Some(&serde_json::json!("chartreuse")), 8.0, &HashMap::new()), None);
        assert_eq!(color_at(None, 8.0, &HashMap::new()), None);
    }

    /// What should actually appear on a density-3 phone at the zooms the defect was measured at.
    /// This is the closest a host test gets to the device, so the numbers are spelled out.
    #[test]
    fn the_pixel_widths_at_the_measured_zooms_are_what_the_authored_style_asks_for() {
        let layers = line_layers();
        let of = |id: &str, zoom: f64| -> Option<f32> {
            let layer = layers.iter().find(|layer| layer.id == id)?;
            let stroke = style().stroke(layer, zoom);
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
}
