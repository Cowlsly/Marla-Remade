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

use crate::style::Layer;
use crate::tess::text;
use crate::tile::geometry::ShapedLabel;
use crate::tile::glyph::{fonts_staged, Weight};
use tilecodec::mamaps::body::{Body, Feature};

/// Floats per vertex: `x, y, u, v` in tile-local 0..1, scaled for this frame.
pub const FLOATS_PER_VERTEX: usize = text::FLOATS_PER_VERTEX;

/// Shape one place label into a [`ShapedLabel`] candidate. `weight` follows the
/// layer's `medium` flag (country and big-city labels); `uppercase` follows the
/// authored `text-transform`. Returns `None` for empty shapes (no atlas, no
/// point, unshapable string) — the renderer skips those silently.
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
    let (glyphs, total_advance) = text::shape(atlas, weight, name);
    if glyphs.is_empty() {
        return None;
    }
    let anchor = tile_point(tile, feature, extent)?;
    Some(ShapedLabel {
        layer_index,
        anchor,
        // Task-17 pick: the display name rides with the label so the JNI
        // pickLabels path can return it without re-reading the tile body.
        name: name.to_string(),
        glyphs,
        total_advance,
        weight,
        uppercase: layer.uppercase,
        rank: rank_for_layer(&layer.id),
        // Places carry the tiler's 0–3 population rank as a NUMERIC detail
        // (see schema/places.rs); anything else is unranked.
        pop: if feature.flags & tilecodec::mamaps::body::FLAG_DETAIL_NUMERIC != 0 {
            feature.kind_detail
        } else {
            0
        },
    })
}

/// Placement rank from the symbol layer id: country first, subplace last.
/// Unknown ids sink (255) rather than winning collisions they were never
/// meant to enter.
fn rank_for_layer(id: &str) -> u8 {
    match id {
        "places-country" => 0,
        "places-region" => 1,
        "places-locality" => 2,
        "places-subplace" => 3,
        _ => u8::MAX,
    }
}

/// Emit one shaped label's quads at the frame's text size. Thin wrapper over
/// [`text::emit`] so the renderer calls one function per label.
///
/// `text_px` is DEVICE px (ramp Dp value × camera density — the ramp is
/// authored in Dp, the shader and the tile span are device px). The wrapper
/// exists so the unit contract lives in one place instead of at every call.
///
/// Task-9 rank emphasis: localities scale by population weight — pop>=2
/// (million-plus + capitals) draw 1.25x, pop 0 (unpopulated hamlets) 0.85x —
/// mirroring the authored data-driven size arms, which the flat single-value
/// transcription can't express. Country/region keep the ramp value.
pub fn emit_label(
    label: &ShapedLabel,
    text_px: f32,
    tile_span_px: f32,
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    let atlas = crate::tile::glyph::atlas();
    // Task-9 rank emphasis, pinned by `rank_emphasis_scales_...` below.
    let scale = match (label.rank, label.pop) {
        (2, p) if p >= 2 => 1.25,
        (2, 0) => 0.85,
        _ => 1.0,
    };
    text::emit(
        atlas,
        label.weight,
        &label.glyphs,
        label.total_advance,
        label.anchor,
        text_px * scale,
        tile_span_px,
        label.uppercase,
        vertices,
        indices,
    );
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

    /// Task-9 rank emphasis: the emit scale factor by (rank, pop) — big
    /// cities 1.25x, hamlets 0.85x, everything else 1.0. Mirrors the authored
    /// data-driven size arms the flat single value can't express.
    fn emit_scale(rank: u8, pop: u16) -> f32 {
        match (rank, pop) {
            (2, p) if p >= 2 => 1.25,
            (2, 0) => 0.85,
            _ => 1.0,
        }
    }

    #[test]
    fn rank_emphasis_scales_big_cities_up_and_hamlets_down() {
        assert_eq!(emit_scale(2, 3), 1.25, "million-plus city");
        assert_eq!(emit_scale(2, 2), 1.25, "capital/major");
        assert_eq!(emit_scale(2, 1), 1.0, "town keeps ramp");
        assert_eq!(emit_scale(2, 0), 0.85, "hamlet shrinks");
        assert_eq!(emit_scale(0, 3), 1.0, "country keeps ramp");
        assert_eq!(emit_scale(1, 2), 1.0, "region keeps ramp");
        assert_eq!(emit_scale(3, 1), 1.0, "subplace keeps ramp");
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
            dash: (0.0, 0.0),
            text_size: crate::style::paint::Ramp::constant(12.0),
            uppercase: true,
            medium: true,
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
        };
        let label = shape_label(&layer, &body, &feature, "Test", 4096, 7);
        assert!(label.is_none(), "no point, no anchor, no label");
    }
}
