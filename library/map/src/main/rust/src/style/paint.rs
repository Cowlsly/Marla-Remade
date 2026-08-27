//! Line width read from the authored style instead of transcribed into constants.
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
//! Scoped to `line-width` and `line-gap-width`. Colour, dashes, fills and per-feature filters
//! still come from [`super::layers`]' constants.

use super::expr::{self, Context};
use super::Layer;
use serde_json::Value as Json;
use std::collections::HashMap;
use std::sync::OnceLock;

/// The authored style, vendored beside the sources.
///
/// Compiled in rather than read from assets: it is 61 KB, it is the same file for every host
/// app, and it makes the loader host-testable rather than reachable only from a device.
const BASEMAP: &str = include_str!("../../style/basemap.json");

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
            evaluate(self.width.as_ref(), zoom, DEFAULT_WIDTH_DP),
            evaluate(self.gap_width.as_ref(), zoom, 0.0),
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
fn evaluate(expr: Option<&Json>, zoom: f64, default: f32) -> Option<f32> {
    let Some(expr) = expr else {
        return Some(default);
    };
    let properties = HashMap::new();
    let context = Context { zoom, properties: &properties, geometry_type: "LineString" };
    expr::eval(expr, &context).ok()?.as_number().map(|width| width as f32)
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
