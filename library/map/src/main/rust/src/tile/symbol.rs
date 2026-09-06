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

/// Floats per vertex: `x, y, u, v` in tile-local 0..1, scaled for this frame.
pub const FLOATS_PER_VERTEX: usize = text::FLOATS_PER_VERTEX;

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
    })
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
#[allow(clippy::too_many_arguments)]
pub fn emit_label(
    label: &ShapedLabel,
    anchor: Anchor,
    offset_em: (f32, f32),
    text_px: f32,
    tile_span_px: f32,
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    let atlas = crate::tile::glyph::atlas();
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
pub fn emit_icon(
    label: &ShapedLabel,
    sprite: Sprite,
    density: f32,
    tile_span_px: f32,
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
    let base = (vertices.len() / FLOATS_PER_VERTEX) as u32;
    vertices.extend_from_slice(&[x0, y0, uv.u0, uv.v0]);
    vertices.extend_from_slice(&[x1, y0, uv.u1, uv.v0]);
    vertices.extend_from_slice(&[x1, y1, uv.u1, uv.v1]);
    vertices.extend_from_slice(&[x0, y1, uv.u0, uv.v1]);
    indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
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
        };
        let label = shape_label(&layer, &body, &feature, "Test", 4096, 7);
        assert!(label.is_none(), "no point, no anchor, no label");
    }
}
