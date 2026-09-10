//! Per-tile symbol shaping: place labels as shaped candidates.
//!
//! M1 covers _place_ labels only. A symbol layer reads point features from the v2
//! `places` layer, looks up each feature's display name in the body's name table,
//! and shapes it via `tess::text` — string → advances, zoom-independent. Quad
//! emission happens per frame in the renderer (`record_symbol`), sized by the
//! frame's `text_size` ramp value.
//!
//! # Tessellate-time vs frame-time split
//!
//! Shaping here is tile-pure and runs once on the worker thread. Emission needs
//! the frame's text size (`text_size` ramp at the camera zoom) and tile span, so
//! the renderer re-emits quads every frame from the shaped candidates. That is
//! affordable: a tile carries dozens of labels, not thousands of road vertices.
//! Placement/collision across tiles is `placement.rs` (M1b); this module shapes
//! every candidate label unclipped.

use crate::style::{Anchor, Layer};
use crate::tess::text;
use crate::tile::geometry::ShapedLabel;
use crate::tile::glyph::{fonts_staged, Weight};
use crate::tile::sprite::Sprite;
use tilecodec::mamaps::body::{Body, Feature};

/// Floats per vertex for a **text** label quad: `x, y, u, v, ax, ay` — see
/// [`text::FLOATS_PER_VERTEX`]. The text draws through the billboard symbol pipeline, which
/// reads the per-vertex ground anchor to stay upright under tilt.
pub const FLOATS_PER_VERTEX: usize = text::FLOATS_PER_VERTEX;

/// Floats per vertex for a POI **icon** quad: `x, y, u, v`.
///
/// Icons keep the original 4-float layout and draw through the on-ground sprite pipeline (shared
/// with the app markers, which also push 4-float quads), so they are not billboarded under tilt —
/// only the text is. Keeping the icon format unchanged is what lets the marker path stay untouched.
pub const ICON_FLOATS_PER_VERTEX: usize = 4;

/// Shape one place label into a [`ShapedLabel`] candidate. `weight` follows the
/// layer's `medium` flag (country and big-city labels); the authored
/// `text-transform` is applied by [`text::shape`], so the shaped glyphs already
/// carry the codepoints that will be drawn and the metrics that go with them.
/// Returns `None` for empty shapes (no atlas, no point, unshapable string) - the
/// renderer skips those silently.
pub fn shape_label(
    layer: &Layer,
    tile: &Body,
    feature: &Feature,
    name: &str,
    extent: u32,
    layer_index: usize,
    feature_index: usize,
) -> Option<ShapedLabel> {
    if !fonts_staged() {
        return None;
    }
    let atlas = crate::tile::glyph::atlas();
    let weight = if layer.medium { Weight::Medium } else { Weight::Regular };
    // `text_max_width` is zero on every place layer, which is the single-line path and
    // therefore byte-identical to what `shape` alone used to produce.
    let lines = text::shape_wrapped(atlas, weight, name, layer.uppercase, layer.text_max_width);
    if lines.is_empty() {
        return None;
    }
    let total_advance = lines.iter().fold(0.0f32, |wide, line| wide.max(line.advance));
    let anchor = tile_point(tile, feature, extent)?;
    Some(ShapedLabel {
        layer_index,
        anchor,
        // Task-17 pick: the display name rides with the label so the JNI
        // pickLabels path can return it without re-reading the tile body.
        name: name.to_string(),
        lines,
        total_advance,
        weight,
        rank: rank_for_layer(&layer.id),
        // Only the POI layers ask for an icon, and a kind the sheet has no picture for
        // (`townhall`) simply draws label-only — which is what MapLibre does with a
        // missing `icon-image`.
        sprite: if layer.icon { sprite_for(feature.kind) } else { None },
        // Places carry the tiler's 0–3 population rank as a NUMERIC detail
        // (see schema/places.rs); anything else is unranked.
        pop: if feature.flags & tilecodec::mamaps::body::FLAG_DETAIL_NUMERIC != 0 {
            feature.kind_detail
        } else {
            0
        },
        // The feature's own kind, so a pick can say `cafe` where the layer only knows it
        // draws one of `poi-food`'s four.
        kind: feature.kind,
        // `feature_index` is the position in the *body* layer's feature vector, which is what
        // the id table is parallel to — not the position among the features this style layer
        // admitted. `ID_NONE` covers both "this layer has no id table" and "no OSM element".
        feature_id: tile
            .feature_id(layer.source_layer_id, feature_index)
            .unwrap_or(tilecodec::mamaps::body::ID_NONE),
        // A point label anchors one block; it does not follow a line.
        centreline: None,
    })
}

/// Shape one road/river line label into a curved [`ShapedLabel`] candidate.
///
/// The line half of [`shape_label`]: the name shapes into a single line (no wrapping — a curved
/// label is one run laid along the road), and the whole feature `centreline` in tile-local 0..1
/// rides on the label so the renderer can walk it per frame at the frame's text size (see
/// [`crate::tess::text::emit_curved`]). The [`anchor`](ShapedLabel::anchor) is the polyline's
/// midpoint, used only by the point-label collision/pick fallbacks until the segmented placer is
/// wired; the curved footprint proper comes from the per-glyph tangents.
///
/// Returns `None` when fonts are not staged, the name is unshapable, or the centreline has fewer
/// than two points — the renderer skips those silently, exactly as it does an empty point shape.
pub fn shape_line_label(
    layer: &Layer,
    tile: &Body,
    feature: &Feature,
    name: &str,
    layer_index: usize,
    feature_index: usize,
    centreline: Vec<(f32, f32)>,
) -> Option<ShapedLabel> {
    if !fonts_staged() {
        return None;
    }
    if centreline.len() < 2 {
        return None;
    }
    let atlas = crate::tile::glyph::atlas();
    let weight = if layer.medium { Weight::Medium } else { Weight::Regular };
    // A curved label is a single run — never wrapped — so `text_max_width` is ignored here.
    let lines = text::shape_wrapped(atlas, weight, name, layer.uppercase, 0.0);
    if lines.is_empty() {
        return None;
    }
    let total_advance = lines.iter().fold(0.0f32, |wide, line| wide.max(line.advance));
    let anchor = polyline_midpoint(&centreline);
    Some(ShapedLabel {
        layer_index,
        anchor,
        name: name.to_string(),
        lines,
        total_advance,
        weight,
        rank: rank_for_layer(&layer.id),
        // A line label carries no population rank and no icon.
        pop: 0,
        sprite: None,
        kind: feature.kind,
        feature_id: tile
            .feature_id(layer.source_layer_id, feature_index)
            .unwrap_or(tilecodec::mamaps::body::ID_NONE),
        centreline: Some(centreline),
    })
}

/// The point half-way along a tile-local polyline by arc length — the curved label's nominal
/// anchor. Falls back to the first point on a degenerate (zero-length) line.
fn polyline_midpoint(pts: &[(f32, f32)]) -> (f32, f32) {
    let mut total = 0.0f32;
    for w in pts.windows(2) {
        total += ((w[1].0 - w[0].0).powi(2) + (w[1].1 - w[0].1).powi(2)).sqrt();
    }
    if total <= 0.0 {
        return *pts.first().unwrap_or(&(0.0, 0.0));
    }
    let half = total * 0.5;
    let mut acc = 0.0f32;
    for w in pts.windows(2) {
        let seg = ((w[1].0 - w[0].0).powi(2) + (w[1].1 - w[0].1).powi(2)).sqrt();
        if seg <= 0.0 {
            continue;
        }
        if acc + seg >= half {
            let t = (half - acc) / seg;
            return (w[0].0 + (w[1].0 - w[0].0) * t, w[0].1 + (w[1].1 - w[0].1) * t);
        }
        acc += seg;
    }
    *pts.last().unwrap_or(&(0.0, 0.0))
}

/// Placement rank from the symbol layer id: country first, POI last.
/// Unknown ids sink (255) rather than winning collisions they were never
/// meant to enter.
fn rank_for_layer(id: &str) -> u8 {
    match id {
        "places-country" => 0,
        "places-region" => 1,
        "places-locality" => 2,
        "places-subplace" => 3,
        // One rank for all six POI colour groups: the split exists to give each group its
        // own `text-color` and nothing else, so a cafe must not beat a park at a collision
        // merely because its colour was authored later in the file. Without this they fall
        // through to `u8::MAX` and lose every collision to every place label — which at
        // z17, where places are sparse, would look almost right and be wrong.
        "poi-outdoor" | "poi-transport" | "poi-civic" | "poi-shop" | "poi-food"
        | "poi-culture" => 4,
        // Line labels below the point labels: a road or river name yields to a place or POI at a
        // collision, matching MapLibre's default `symbol-z-order`. Major roads above minor above
        // rivers, so a highway name wins over a side street and both over the waterway they cross.
        // The ids are the render-side symbol layers the build carries road/water names for (WS-E).
        "roads-label-major" => 5,
        "roads-label-minor" => 6,
        "waterway-label" => 7,
        _ => u8::MAX,
    }
}

/// The sprite a feature's `kind` names, if the sheet carries one.
///
/// The reference's `icon-image` is
/// `["match", ["get", "kind"], "station", "train_station", ["get", "kind"]]` — one rename,
/// and otherwise the kind itself. That whole expression is these three lines, which is why
/// the style carries a boolean rather than a per-layer icon name.
fn sprite_for(kind: u16) -> Option<Sprite> {
    use tilecodec::mamaps::dict;
    // `kind` is a 1-based id into the interned table; 0 is `dict::NONE`.
    let name = dict::KINDS.get(usize::from(kind).checked_sub(1)?)?;
    let name = if *name == "station" { "train_station" } else { *name };
    crate::tile::sprite::atlas().get(name)
}

/// Emit one shaped label's quads at the size and anchor the caller resolved for it.
///
/// `text_px` is DEVICE px (the layer's size arm at the camera zoom x camera density -
/// the ramp is authored in Dp, the shader and the tile span are device px). The wrapper
/// exists so the unit contract lives in one place instead of at every call.
///
/// `anchor` and `offset_em` are the resolved variable anchor and the layer's
/// `text-offset`: [`Anchor::Center`] with a zero offset is the place-label path and is
/// exactly what this drew before POI existed.
///
/// Rank emphasis used to live here as a fixed multiplier (1.25x for big cities, 0.85x
/// for hamlets) standing in for the authored data-driven size arms. The style carries
/// those arms properly now - see `Layer::text_size_for` - so the caller resolves the
/// size and this just draws it.
///
/// `rotation` is the camera's `(cos, sin)` (see [`crate::camera::Camera::rotation`]): the
/// emitted quads are counter-rotated about the anchor so the label stays **upright**
/// under a heading-up camera. `(1.0, 0.0)` is north-up and costs nothing.
///
/// A **curved** label — one carrying a [`centreline`](ShapedLabel::centreline) — takes a
/// different path: its single line is laid along the polyline by
/// [`text::emit_curved`](crate::tess::text::emit_curved), one glyph per vertex rotated to the
/// local tangent, and it is **not** counter-rotated. A curved label is map-aligned, so its
/// orientation is the road's, not the camera's; `anchor`, `offset_em` and `rotation` are unused
/// on that path.
#[allow(clippy::too_many_arguments)]
pub fn emit_label(
    label: &ShapedLabel,
    anchor: Anchor,
    offset_em: (f32, f32),
    text_px: f32,
    tile_span_px: f32,
    rotation: (f32, f32),
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    let atlas = crate::tile::glyph::atlas();
    // A curved (line) label lays its single shaped run along the centreline; the tangent gives
    // each glyph its rotation, so no `upright` counter-rotation and no anchor/offset apply.
    if let Some(centreline) = &label.centreline {
        let Some(line) = label.lines.first() else { return };
        text::emit_curved(
            atlas,
            label.weight,
            line,
            centreline,
            text_px,
            tile_span_px,
            vertices,
            indices,
        );
        return;
    }
    let start = vertices.len();
    text::emit(
        atlas,
        label.weight,
        &label.lines,
        label.anchor,
        anchor,
        offset_em,
        text_px,
        tile_span_px,
        vertices,
        indices,
    );
    text::upright(&mut vertices[start..], label.anchor, rotation);
}

/// Emit one POI icon's quad, centred on the label's anchor point.
///
/// The reference sets neither `icon-size` nor `icon-offset`, so an icon is its sheet size
/// in Dp, centred on the point, at a constant screen size whatever the zoom — unlike the
/// label beside it, which follows a `text-size` ramp. The label's `text-offset` is what
/// keeps the two from overlapping.
///
/// Goes into a **separate** buffer from the text: the two sample different atlases through
/// different fragment shaders (a picture against a distance field), so they cannot share a
/// draw even though they share a pipeline layout and a vertex format.
///
/// `rotation` counter-rotates the quad exactly as it does for the text beside it, so a POI
/// pictogram stays the right way up under a heading-up camera.
#[allow(clippy::too_many_arguments)]
pub fn emit_icon(
    label: &ShapedLabel,
    sprite: Sprite,
    dark: bool,
    density: f32,
    tile_span_px: f32,
    rotation: (f32, f32),
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    if tile_span_px <= 0.0 {
        return;
    }
    let half_w = sprite.width_dp * density * 0.5 / tile_span_px;
    let half_h = sprite.height_dp * density * 0.5 / tile_span_px;
    let (cx, cy) = label.anchor;
    let (x0, y0) = (cx - half_w, cy - half_h);
    let (x1, y1) = (cx + half_w, cy + half_h);
    let uv = sprite.uv;
    // The sheet is the light half over the dark one, so the theme is one addition here
    // rather than a second atlas or a second resolved `Sprite`. Applied at emit — which
    // runs every frame — so switching palette stays free of re-tessellation.
    let dv = if dark { crate::tile::sprite::atlas().dark_v_offset() } else { 0.0 };
    let (v0, v1) = (uv.v0 + dv, uv.v1 + dv);
    let base = (vertices.len() / ICON_FLOATS_PER_VERTEX) as u32;
    let start = vertices.len();
    vertices.extend_from_slice(&[x0, y0, uv.u0, v0]);
    vertices.extend_from_slice(&[x1, y0, uv.u1, v0]);
    vertices.extend_from_slice(&[x1, y1, uv.u1, v1]);
    vertices.extend_from_slice(&[x0, y1, uv.u0, v1]);
    indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
    text::upright_stride(&mut vertices[start..], label.anchor, rotation, ICON_FLOATS_PER_VERTEX);
}

/// The clip-space position the billboard vertex shader (`symbol_billboard.vert`) computes for one
/// text glyph vertex, mirrored on the CPU so the billboard math is testable without a GPU.
///
/// `tile_to_clip` is the per-tile matrix — perspective when the camera is pitched. `ortho2x2` is
/// the **pitch-0** tile matrix's linear part `[m0, m1, m4, m5]` (column-major), which the renderer
/// passes to the shader in the push `morph` slot. `billboard` is the per-draw flag (`Push::line.w`):
/// off when the camera is level, where the vertex is drawn straight through `tile_to_clip` — so the
/// flat map is byte-identical to the pre-billboard path.
///
/// When on, the label's ground `anchor` is projected through the perspective matrix and the glyph's
/// tile-local offset from it is added as a screen-constant clip offset (scaled by the anchor's `w`),
/// so the glyph stays pinned to the ground point but faces the screen upright at any pitch. A curved
/// label passes `position == anchor` (offset zero), which collapses this to the plain on-ground
/// projection — curved labels stay map-aligned.
pub fn billboard_clip(
    tile_to_clip: &[f32; 16],
    ortho2x2: [f32; 4],
    position: (f32, f32),
    anchor: (f32, f32),
    billboard: bool,
) -> [f32; 4] {
    let m = tile_to_clip;
    // `tile_to_clip * vec4(p, 0, 1)`: the same projection the flat symbol path uses (height 0).
    let project = |p: (f32, f32)| {
        [
            m[0] * p.0 + m[4] * p.1 + m[12],
            m[1] * p.0 + m[5] * p.1 + m[13],
            m[2] * p.0 + m[6] * p.1 + m[14],
            m[3] * p.0 + m[7] * p.1 + m[15],
        ]
    };
    if !billboard {
        return project(position);
    }
    let a = project(anchor);
    let off = (position.0 - anchor.0, position.1 - anchor.1);
    // Column-major 2x2 (pitch-0 linear part) times the tile-local offset → screen-constant clip.
    let off_clip = (
        ortho2x2[0] * off.0 + ortho2x2[2] * off.1,
        ortho2x2[1] * off.0 + ortho2x2[3] * off.1,
    );
    [a[0] + off_clip.0 * a[3], a[1] + off_clip.1 * a[3], a[2], a[3]]
}

/// The feature's point in tile-local 0..1. Places are single-point features; the
/// first point of the first part is the anchor.
fn tile_point(tile: &Body, feature: &Feature, extent: u32) -> Option<(f32, f32)> {
    let source = tile.layer(feature_source_layer(tile, feature))?;
    let parts = source.parts_of(feature);
    let first = parts.first()?;
    let (x, y) = *source.points(first).first()?;
    Some((x as f32 / extent as f32, y as f32 / extent as f32))
}

/// The layer id of the layer containing `feature`. The caller already resolved it
/// to call `matches_feature`; re-finding by scan keeps this module from threading
/// the id through. Places live in exactly one layer per tile.
fn feature_source_layer(tile: &Body, feature: &Feature) -> u8 {
    use tilecodec::mamaps::dict;
    for layer in &tile.layers {
        let start = layer.features.as_ptr() as usize;
        let end = start + layer.features.len() * std::mem::size_of::<Feature>();
        let addr = feature as *const Feature as usize;
        if addr >= start && addr < end {
            return layer.layer_id;
        }
        let _ = dict::LAYER_PLACES;
    }
    dict::LAYER_PLACES
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The size arms are the style's job now, so there is no multiplier here to pin.
    /// `paint::city_labels_track_the_big_city_arm_at_compared_zooms` covers the arms.
    #[test]
    fn rank_for_layer_orders_country_before_subplace() {
        assert!(rank_for_layer("places-country") < rank_for_layer("places-region"));
        assert!(rank_for_layer("places-region") < rank_for_layer("places-locality"));
        assert!(rank_for_layer("places-locality") < rank_for_layer("places-subplace"));
        assert_eq!(rank_for_layer("something-else"), u8::MAX, "unknown ids sink");
    }

    /// Line labels rank below every point label but above the unknown sink, and major roads
    /// outrank minor roads outrank rivers so the more important line wins a crossing collision.
    #[test]
    fn line_labels_rank_below_points_roads_above_rivers() {
        assert!(rank_for_layer("roads-label-major") > rank_for_layer("places-subplace"));
        assert!(rank_for_layer("roads-label-major") > rank_for_layer("poi-food"));
        assert!(rank_for_layer("roads-label-major") < rank_for_layer("roads-label-minor"));
        assert!(rank_for_layer("roads-label-minor") < rank_for_layer("waterway-label"));
        assert!(rank_for_layer("waterway-label") < u8::MAX, "line labels must not sink");
    }

    /// Every POI layer sits at one rank, below every place label.
    ///
    /// One rank for all six because the split exists to give each colour group its own
    /// `text-color` and nothing else — if they ranked in file order, a cafe would beat a
    /// park at a collision for no reason anyone authored. And they must be *known* ranks:
    /// falling through to `u8::MAX` would put them below an unrecognised layer.
    #[test]
    fn every_poi_layer_shares_one_rank_below_the_places() {
        let poi = [
            "poi-outdoor", "poi-transport", "poi-civic", "poi-shop", "poi-food", "poi-culture",
        ];
        for id in poi {
            assert_eq!(rank_for_layer(id), 4, "{id}");
            assert!(rank_for_layer(id) > rank_for_layer("places-subplace"), "{id}");
            assert!(rank_for_layer(id) < u8::MAX, "{id} fell through to the sink rank");
        }
        // And the style really does call them that — a renamed layer would silently sink.
        for layer in crate::style::layers() {
            if layer.toggle == Some(crate::style::Toggle::Poi) {
                assert!(poi.contains(&layer.id.as_str()), "`{}` is not ranked", layer.id);
            }
        }
    }

    /// The reference's whole `icon-image` expression: `station` renames, everything else
    /// is the kind itself, and a kind with no picture draws label-only.
    #[test]
    fn a_kind_resolves_to_its_own_sprite_except_the_one_the_reference_renames() {
        let id = crate::style::kind_id_for_test;
        let park = sprite_for(id("park")).expect("park has a sprite");
        assert_eq!(
            park.uv.u0.to_bits(),
            crate::tile::sprite::atlas().get("park").expect("park").uv.u0.to_bits(),
        );
        // `station` is the rename; it must NOT resolve to a sprite called `station`.
        let station = sprite_for(id("station")).expect("station resolves to train_station");
        assert_eq!(
            station.uv.u0.to_bits(),
            crate::tile::sprite::atlas().get("train_station").expect("train_station").uv.u0.to_bits(),
        );
        assert!(crate::tile::sprite::atlas().get("station").is_none(), "or this proves nothing");
        // The one POI kind with no picture: label-only, as MapLibre draws it.
        assert!(sprite_for(id("townhall")).is_none());
        // A kind that is not a POI at all, and the `dict::NONE` id.
        assert!(sprite_for(id("highway")).is_none());
        assert!(sprite_for(0).is_none(), "the no-kind id must not index the table");
    }

    #[test]
    fn an_unnamed_feature_shapes_nothing() {
        // A feature the name table cannot resolve shapes to None: the renderer
        // skips it silently — no panic, no partial geometry.
        let body = Body::new(4096);
        let layer = Layer {
            id: "places-country".to_string(),
            source_layer: "places".to_string(),
            source_layer_id: tilecodec::mamaps::dict::LAYER_PLACES,
            kind: crate::style::LayerKind::Symbol,
            kinds: vec!["country".to_string()],
            kind_ids: vec![crate::style::kind_id_for_test("country")],
            require_flags: 0,
            forbid_flags: 0,
            detail_ids: Vec::new(),
            forbid_details: Vec::new(),
            light: 0xFFA3A3A3,
            dark: 0xFFA3A3A3,
            opacity: crate::style::paint::Ramp::constant(1.0),
            width: crate::style::paint::Ramp::constant(0.0),
            gap_width: crate::style::paint::Ramp::constant(0.0),
            spread: crate::style::paint::Ramp::constant(0.0),
            lanes: crate::style::paint::Ramp::constant(1.0),
            dash: (0.0, 0.0),
            text_size: crate::style::paint::Ramp::constant(12.0),
            text_size_large: None,
            rank_threshold: None,
            uppercase: true,
            medium: true,
            toggle: None,
            icon: false,
            text_offset: (0.0, 0.0),
            text_max_width: 0.0,
            variable_anchor: Vec::new(),
            halo_light: 0xFFE2DFDA,
            halo_dark: 0xFFE2DFDA,
            halo_width: 1.0,
            min_zoom: 0,
            browse_min_zoom: 0,
            max_zoom: 22,
            authored: "places_country".to_string(),
        };
        let feature = Feature {
            kind: 1,
            kind_detail: 0,
            geom_type: tilecodec::mamaps::body::GEOM_POINT,
            flags: 0,
            name_idx: tilecodec::mamaps::body::NAME_NONE,
            parts_offset: 0,
            part_count: 0,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        };
        let label = shape_label(&layer, &body, &feature, "Test", 4096, 7, 0);
        assert!(label.is_none(), "no point, no anchor, no label");
    }

    /// **The bug this field exists to fix.** A shaped label reports the feature's own kind, not
    /// its layer's first whitelist entry — the old pick path read `layer.kinds.first()`, so every
    /// hit on `poi-food`'s four kinds came back as `restaurant`.
    ///
    /// Also pins the id: the index passed in is the position in the *body* layer, which is what
    /// the archive's id table is parallel to.
    #[test]
    fn a_label_carries_its_own_kind_and_id_not_its_layers() {
        let cafe = crate::style::kind_id_for_test("cafe");
        let mut poi = tilecodec::mamaps::body::Layer::new(tilecodec::mamaps::dict::LAYER_POI);
        poi.features.push(Feature {
            kind: cafe,
            kind_detail: 0,
            geom_type: tilecodec::mamaps::body::GEOM_POINT,
            flags: 0,
            name_idx: 1,
            parts_offset: 0,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
        poi.parts.push(tilecodec::mamaps::body::Part {
            coord_start: 0,
            point_count: 1,
            winding: tilecodec::mamaps::body::WINDING_OUTER,
        });
        poi.coords = vec![(2048, 2048)];
        let body = Body {
            extent: 4096,
            layers: vec![poi],
            names: vec!["Blue Bottle".to_string()],
            ids: vec![(tilecodec::mamaps::dict::LAYER_POI, vec![987_654_321])],
            turn_lanes: Vec::new(),
            buildings: Vec::new(),
            heightmap: None, carriageways: Vec::new(), convention: None,
        };
        // A layer whose whitelist lists `restaurant` first, exactly as `poi-food` does.
        let layer = food_layer();
        // A reference *into* the body, not a copy: `tile_point` recovers the feature's layer by
        // scanning for the pointer inside each layer's feature slice, so a copy anchors nowhere.
        let feature = &body.layers[0].features[0];
        // The staged TTFs are real, so shaping must succeed; a `None` here means the fixture
        // broke, not that the test does not apply. Skipping silently would make the two
        // assertions below vacuous.
        assert!(fonts_staged(), "this test needs the staged Noto Sans");
        let label =
            shape_label(&layer, &body, feature, "Blue Bottle", 4096, 3, 0).expect("a shaped poi");
        assert_eq!(label.kind, cafe, "the feature's kind, not the layer's first");
        assert_ne!(label.kind, layer.kind_ids[0], "or this proves nothing");
        assert_eq!(label.feature_id, 987_654_321);
    }

    fn food_layer() -> Layer {
        let kinds = ["restaurant", "fast_food", "cafe", "bar"];
        Layer {
            id: "poi-food".to_string(),
            source_layer: "poi".to_string(),
            source_layer_id: tilecodec::mamaps::dict::LAYER_POI,
            kind: crate::style::LayerKind::Symbol,
            kinds: kinds.iter().map(|k| (*k).to_string()).collect(),
            kind_ids: kinds.iter().map(|k| crate::style::kind_id_for_test(k)).collect(),
            require_flags: 0,
            forbid_flags: 0,
            detail_ids: Vec::new(),
            forbid_details: Vec::new(),
            light: 0xFFCB6704,
            dark: 0xFFCB6704,
            opacity: crate::style::paint::Ramp::constant(1.0),
            width: crate::style::paint::Ramp::constant(0.0),
            gap_width: crate::style::paint::Ramp::constant(0.0),
            spread: crate::style::paint::Ramp::constant(0.0),
            lanes: crate::style::paint::Ramp::constant(1.0),
            dash: (0.0, 0.0),
            text_size: crate::style::paint::Ramp::constant(12.0),
            text_size_large: None,
            rank_threshold: None,
            uppercase: false,
            medium: false,
            toggle: Some(crate::style::Toggle::Poi),
            icon: true,
            text_offset: (1.1, 0.0),
            text_max_width: 8.0,
            variable_anchor: Vec::new(),
            halo_light: 0xFFE2DFDA,
            halo_dark: 0xFF0D1B2A,
            halo_width: 1.0,
            min_zoom: 0,
            browse_min_zoom: 0,
            max_zoom: 22,
            authored: "pois".to_string(),
        }
    }

    // --- point-label billboarding under tilt --------------------------------

    use crate::camera::Camera;

    fn camera(pitch_deg: f64) -> Camera {
        Camera {
            center_lon: -122.4194,
            center_lat: 37.7749,
            zoom: 14.0,
            width_dp: 800.0,
            height_dp: 1000.0,
            density: 1.0,
            bearing_deg: 0.0,
            pitch_deg,
            time_seconds: 0.0,
        }
    }

    /// The pitch-0 tile matrix's linear 2x2 `[m0, m1, m4, m5]`, as the renderer passes it.
    fn ortho2x2(cam: &Camera, z: u8, x: u32, y: u32) -> [f32; 4] {
        let flat = Camera { pitch_deg: 0.0, ..*cam }.tile_to_clip(z, x, y);
        [flat[0], flat[1], flat[4], flat[5]]
    }

    #[test]
    fn billboard_off_is_the_plain_projection_byte_for_byte() {
        // At pitch 0 the renderer passes `billboard = false`, and the glyph must be drawn straight
        // through `tile_to_clip` — bit-identical to the pre-billboard path, whatever the anchor is.
        let cam = camera(0.0);
        let (z, x, y) = (14u8, 2617, 6335);
        let m = cam.tile_to_clip(z, x, y);
        let o = ortho2x2(&cam, z, x, y);
        let pos = (0.62f32, 0.48f32);
        let got = billboard_clip(&m, o, pos, (0.5, 0.5), false);
        let want =
            [m[0] * pos.0 + m[4] * pos.1 + m[12], m[1] * pos.0 + m[5] * pos.1 + m[13], m[14], m[15]];
        assert_eq!(got.map(f32::to_bits), want.map(f32::to_bits), "billboard-off moved a vertex");
    }

    #[test]
    fn a_pitched_anchor_projects_to_the_same_ground_clip_as_unbillboarded() {
        // The anchor vertex (offset zero) must land exactly where the plain projection would put
        // the ground point, so a billboarded label stays glued to its feature under tilt.
        let cam = camera(50.0);
        let (z, x, y) = (14u8, 2617, 6335);
        let m = cam.tile_to_clip(z, x, y);
        let o = ortho2x2(&cam, z, x, y);
        let anchor = (0.4f32, 0.55f32);
        let billed = billboard_clip(&m, o, anchor, anchor, true);
        let plain = billboard_clip(&m, o, anchor, anchor, false);
        for (a, b) in billed.iter().zip(plain.iter()) {
            assert!((a - b).abs() < 1e-6, "anchor drifted off the ground: {a} vs {b}");
        }
    }

    #[test]
    fn a_curved_glyph_vertex_is_never_billboarded() {
        // A curved label writes each vertex as its own anchor (offset zero), so even with the
        // billboard flag on it projects straight onto the ground — map-aligned, as it must be.
        let cam = camera(45.0);
        let (z, x, y) = (14u8, 2617, 6335);
        let m = cam.tile_to_clip(z, x, y);
        let o = ortho2x2(&cam, z, x, y);
        let v = (0.63f32, 0.47f32);
        let curved = billboard_clip(&m, o, v, v, true);
        let ground = billboard_clip(&m, o, v, v, false);
        assert_eq!(curved.map(f32::to_bits), ground.map(f32::to_bits));
    }

    #[test]
    fn a_billboarded_glyph_offset_is_screen_constant_regardless_of_depth() {
        // The whole point of scaling the offset by the anchor's `w`: the same tile-local glyph
        // offset must produce the same *screen* (NDC) offset whether the anchor is near the camera
        // or far up-map toward the horizon — otherwise text would shrink into the distance.
        let cam = camera(55.0);
        let (z, x, y) = (14u8, 2617, 6335);
        let m = cam.tile_to_clip(z, x, y);
        let o = ortho2x2(&cam, z, x, y);
        let off = (0.02f32, -0.015f32);
        let ndc_offset = |anchor: (f32, f32)| {
            let a = billboard_clip(&m, o, anchor, anchor, true);
            let g = billboard_clip(&m, o, (anchor.0 + off.0, anchor.1 + off.1), anchor, true);
            ((g[0] / g[3]) - (a[0] / a[3]), (g[1] / g[3]) - (a[1] / a[3]))
        };
        // Two anchors at very different ground depths under the tilt (near vs far up-map). The NDC
        // offset is mathematically `ortho2x2 * off` — independent of the anchor's depth — so the
        // only difference is floating-point noise from the `* w / w` round trip.
        let near = ndc_offset((0.5, 0.72));
        let far = ndc_offset((0.5, 0.30));
        assert!((near.0 - far.0).abs() < 1e-5 && (near.1 - far.1).abs() < 1e-5,
            "screen offset changed with depth: {near:?} vs {far:?}");
    }
}
