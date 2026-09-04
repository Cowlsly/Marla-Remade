//! Turns a decoded tile into per-layer triangles.
//!
//! Pure CPU and a pure function: no Vulkan types, no JNI, so the expensive half of a
//! frame is testable on the host and can run on any thread. This is the step the plan
//! expects to be the actual bottleneck — a vector map is slow on the CPU, not the GPU.
//!
//! Reads a `.mamaps` body rather than an MVT tile, and that is most of why the format exists: a
//! feature's `kind` is a `u16` tested against a sorted slice instead of a property-map lookup
//! yielding a `String`, and a part's points are a slice of an already-decoded arena instead of a
//! geometry-command walk. Nothing downstream of here changed — fills are still 2 floats a vertex,
//! strokes 7, and the shaders never saw any of it.

use crate::style::{Layer, LayerKind};
use crate::tess::{fill, stroke};
use crate::tile::symbol;
use crate::tile::select::ANCESTOR_DEPTH;
use tilecodec::mamaps::body::{Body, GEOM_LINE, GEOM_POINT, GEOM_POLYGON};

/// Tessellated geometry for one layer of one tile.
pub struct LayerMesh {
    /// Index into the style's layer list, which is also draw order.
    pub layer_index: usize,
    pub kind: LayerKind,
    pub vertices: Vec<f32>,
    pub indices: Vec<u32>,
}

/// One shaped label candidate for per-frame symbol emission.
#[derive(Clone)]
pub struct ShapedLabel {
    /// Index into the style's layer list (the symbol layer that owns it).
    pub layer_index: usize,
    /// Anchor in tile-local 0..1.
    pub anchor: (f32, f32),
    /// Display name as shaped (for the task-17 pick path).
    pub name: String,
    /// Shaped glyphs (font-unit advances, atlas UVs via `tile::glyph`).
    pub glyphs: Vec<crate::tess::text::ShapedGlyph>,
    /// Total advance in font units, for centring.
    pub total_advance: f32,
    /// Glyph weight (the layer's `medium` flag).
    pub weight: crate::tile::glyph::Weight,
    /// Uppercase transform (the layer's `uppercase` flag).
    pub uppercase: bool,
    /// Placement rank: 0 country … 3 subplace (from the layer id), 255 unknown.
    /// Decided at shape time so the per-frame path only sorts.
    pub rank: u8,
    /// Population weight within the rank (the feature's numeric `kind_detail`,
    /// 0–3 from the tiler, 0 unknown). Placement prefers higher weight on ties,
    /// so a big city beats a town at the same collision.
    pub pop: u16,
}

/// Every layer's geometry for one tile, ready to upload.
pub struct TileMesh {
    pub z: u8,
    pub x: u32,
    pub y: u32,
    pub meshes: Vec<LayerMesh>,
    /// Symbol candidates: shaped once at tessellation time, sized per frame.
    pub labels: Vec<ShapedLabel>,
}

/// Tessellate every layer of `tile` that could be drawn while this tile is on screen.
///
/// A tile is displayed at its own zoom *and* as a stand-in ancestor for up to
/// [`ANCESTOR_DEPTH`] levels below it, so the camera can be anywhere in `z ..= z +
/// ANCESTOR_DEPTH` while this mesh is resident. Gating on `z` alone — the tile's own zoom —
/// bakes a decision that only holds at the moment of tessellation: an ancestor then carries
/// no `landuse` or `buildings` at all, because those layers' `min_zoom` is above its own,
/// and whole families of geometry appear only once the exact-zoom tiles land rather than
/// the ancestor standing in for them.
///
/// So the gate here is the *widest* it could need to be, and the renderer decides what is
/// actually visible against the camera's own zoom every frame. Layers outside the window
/// are still skipped, which keeps this bounded: a z5 tile does not tessellate buildings.
///
/// Symbol layers shape here zoom-independently (string → advances); per-frame sizing
/// happens in the renderer from `labels`, which carries the shaped candidates while
/// `meshes` carries no symbol vertices (they would be stale the next frame).
pub fn build(
    tile: &Body,
    layers: &[Layer],
    z: u8,
    x: u32,
    y: u32,
    rings_validated: bool,
) -> TileMesh {
    build_at(tile, layers, z, x, y, rings_validated, z as f64, 256.0)
}

/// [`build`] at an explicit camera zoom and tile span.
///
/// Production calls [`build`] (camera == tile zoom, 256px span — the tessellation-time
/// default). Accepted for API symmetry with the per-frame path; symbols shape
/// zoom-independently, so both are currently unused at tessellation time.
pub fn build_at(
    tile: &Body,
    layers: &[Layer],
    z: u8,
    x: u32,
    y: u32,
    rings_validated: bool,
    camera_zoom: f64,
    tile_span_px: f32,
) -> TileMesh {
    let _ = (camera_zoom, tile_span_px);
    let mut meshes = Vec::with_capacity(layers.len());
    let mut labels = Vec::new();
    let deepest = z.saturating_add(ANCESTOR_DEPTH);
    let extent = tile.extent as u32;

    for (index, layer) in layers.iter().enumerate() {
        if layer.min_zoom > deepest || layer.max_zoom < z {
            continue;
        }
        let Some(source) = tile.layer(layer.source_layer_id) else { continue };

        let mut vertices: Vec<f32> = Vec::new();
        let mut indices: Vec<u32> = Vec::new();

        for feature in &source.features {
            // Kind, then the road flag/detail filters: one call, so a surface layer never
            // draws the ramps, bridges, tunnels and service streets its `kind` alone would
            // admit. This used to be a `String` allocation and a property-map lookup per
            // feature per tile; now it is integer compares against sorted slices.
            if !layer.matches_feature(feature) {
                continue;
            }
            let parts = source.parts_of(feature);
            match layer.kind {
                LayerKind::Fill => {
                    if feature.geom_type != GEOM_POLYGON {
                        continue;
                    }
                    // A feature's parts are exactly one exterior and its holes, which is what the
                    // tessellator takes: the encoder splits a multipolygon into one feature per
                    // ring group rather than making every consumer regroup them.
                    let rings: Vec<Vec<(i32, i32)>> =
                        parts.iter().map(|part| widen(source.points(part))).collect();
                    fill::tessellate(&rings, extent, rings_validated, &mut vertices, &mut indices);
                }
                LayerKind::Line => {
                    // Polygons contribute their outlines too: a lake's shoreline and an
                    // administrative boundary are both lines drawn over an area feature.
                    if !matches!(feature.geom_type, GEOM_LINE | GEOM_POLYGON) {
                        continue;
                    }
                    let gapped = layer.gapped();
                    for part in parts {
                        let flat = flatten(source.points(part));
                        stroke::stroke(&flat, extent, gapped, &mut vertices, &mut indices);
                    }
                }
                LayerKind::Symbol => {
                    // Points only: a place is one labelled point even when mapped as an
                    // area (extract centroids it). Shape here (zoom-independent);
                    // the renderer emits quads per frame at the frame's text size.
                    if feature.geom_type != GEOM_POINT {
                        continue;
                    }
                    let Some(name) = tile.name(feature.name_idx) else { continue };
                    if name.is_empty() {
                        continue;
                    }
                    if let Some(label) =
                        symbol::shape_label(layer, tile, feature, name, extent, index)
                    {
                        labels.push(label);
                    }
                }
            }
        }

        if indices.is_empty() {
            continue;
        }
        meshes.push(LayerMesh { layer_index: index, kind: layer.kind, vertices, indices });
    }

    TileMesh { z, x, y, meshes, labels }
}

/// `[(i16, i16)]` to the `[(i32, i32)]` the fill tessellator takes.
fn widen(points: &[(i16, i16)]) -> Vec<(i32, i32)> {
    points.iter().map(|&(x, y)| (x as i32, y as i32)).collect()
}

/// `[(i16, i16)]` to the flat `[x, y, ...]` the stroke tessellator takes.
fn flatten(points: &[(i16, i16)]) -> Vec<i32> {
    let mut out = Vec::with_capacity(points.len() * 2);
    for &(x, y) in points {
        out.push(x as i32);
        out.push(y as i32);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::style::paint::Ramp;
    use crate::style;

    const REAL_TILE: &[u8] = include_bytes!("../../tests/fixtures/v5ca_z11_tile.mvt");

    /// The published tile, converted to a `.mamaps` body.
    ///
    /// The fixture is still MVT because it was lifted out of the published archive with a ranged
    /// GET, and there is no published `.mamaps` archive yet. Going through `from_mvt` is what
    /// Phase 4 of the plan is: the container and this module are validated on data the tiler
    /// already produced and the app already drew, before any tag→kind schema work exists to be
    /// wrong.
    fn real() -> Body {
        let tile = tilecodec::mvt::Tile::decode(REAL_TILE).expect("the published tile decodes");
        tilecodec::mamaps::from_mvt::from_tile(&tile).expect("converts").0
    }

    fn mesh_for<'a>(mesh: &'a TileMesh, layers: &[Layer], id: &str) -> Option<&'a LayerMesh> {
        mesh.meshes.iter().find(|m| layers[m.layer_index].id == id)
    }

    #[test]
    fn the_real_tile_produces_geometry_for_the_layers_it_has_data_in() {
        // The published tile carries one `earth` polygon, one `roads` LineString of
        // kind = major_road, and two `water` polygons.
        let layers = style::layers();
        let mesh = build(&real(), &layers, 11, 339, 770, false);

        assert!(mesh_for(&mesh, &layers, "earth").is_some(), "the earth polygon tessellates");
        assert!(mesh_for(&mesh, &layers, "water").is_some(), "both water polygons tessellate");
        assert!(mesh_for(&mesh, &layers, "roads-major").is_some(), "the major_road strokes");
        assert!(mesh_for(&mesh, &layers, "roads-major-casing").is_some(), "and so does its casing");

        // Layers the tile has no data for produce no mesh at all, rather than an empty
        // one that would still cost a draw.
        assert!(mesh_for(&mesh, &layers, "buildings").is_none(), "no buildings layer here");
        assert!(mesh_for(&mesh, &layers, "roads-highway").is_none(), "the road is a major_road");
        assert!(
            mesh_for(&mesh, &layers, "landuse_park:national_park").is_none(),
            "no landuse layer",
        );
    }

    #[test]
    fn an_ancestor_carries_the_layers_it_will_stand_in_for() {
        // A tile is displayed as a stand-in ancestor up to ANCESTOR_DEPTH levels below its
        // own zoom, so tessellation has to cover that whole window. Gating on the tile's own
        // zoom instead means an ancestor holds no geometry for any layer whose `min_zoom` is
        // deeper than it, and those layers appear only once the exact-zoom tiles arrive.
        //
        // `roads-major` has min_zoom 9 and the published tile really does carry a
        // `major_road`, so this is decided by data rather than by the layer table alone.
        let layers = style::layers();
        let major = layers.iter().find(|l| l.id == "roads-major").expect("roads-major");
        assert_eq!(major.min_zoom, 9, "this test is calibrated to roads-major's min_zoom");

        // z6 is within reach of z9 (6 + 4 = 10), so the road is tessellated ready for the
        // camera to descend onto it. The old tile-zoom gate dropped it here.
        let reaching = build(&real(), &layers, 6, 339, 770, false);
        assert!(
            mesh_for(&reaching, &layers, "roads-major").is_some(),
            "a z6 ancestor must carry the roads it will stand in for at z9",
        );

        // z4 is not (4 + 4 = 8 < 9), so the window stays bounded and this is not simply
        // tessellating everything at every zoom.
        let out_of_reach = build(&real(), &layers, 4, 339, 770, false);
        assert!(
            mesh_for(&out_of_reach, &layers, "roads-major").is_none(),
            "the window must stay bounded, or every tile pays for every layer",
        );
    }

    #[test]
    fn every_mesh_is_well_formed() {
        let layers = style::layers();
        let mesh = build(&real(), &layers, 11, 339, 770, false);
        assert!(!mesh.meshes.is_empty());
        for m in &mesh.meshes {
            let id = &layers[m.layer_index].id;
            let stride = match m.kind {
                LayerKind::Fill => fill::FLOATS_PER_VERTEX,
                LayerKind::Line => stroke::FLOATS_PER_VERTEX,
                LayerKind::Symbol => crate::tile::symbol::FLOATS_PER_VERTEX,
            };
            assert_eq!(m.vertices.len() % stride, 0, "{id} vertices are whole");
            assert_eq!(m.indices.len() % 3, 0, "{id} indices come in threes");
            assert!(!m.indices.is_empty(), "{id} has triangles");

            let vertex_count = (m.vertices.len() / stride) as u32;
            for &i in &m.indices {
                assert!(i < vertex_count, "{id} index {i} is out of range");
            }
            for f in &m.vertices {
                assert!(f.is_finite(), "{id} has a non-finite vertex");
            }
        }
    }

    #[test]
    fn tessellated_output_is_within_the_bounds_the_shaders_assume() {
        // Tight, unlike the loose -3..4 this replaced. The vertex shaders assume
        // tile-local 0..1 positions, unit-length normals, and a distance-along-line that
        // is also tile-local — `line.vert` multiplies it by the tile's pixel size to get
        // pixels for the dash pattern. A violation of any of those renders a recognisable
        // map that is badly wrong, which is exactly the failure this pins down.
        let layers = style::layers();
        let mesh = build(&real(), &layers, 11, 339, 770, false);
        assert!(!mesh.meshes.is_empty());

        let mut worst_pos = 0.0f32;
        let mut worst_normal = 0.0f32;
        let mut worst_distance = 0.0f32;
        for m in &mesh.meshes {
            match m.kind {
                LayerKind::Fill => {
                    for chunk in m.vertices.chunks(fill::FLOATS_PER_VERTEX) {
                        worst_pos = worst_pos.max(chunk[0].abs()).max(chunk[1].abs());
                    }
                }
                LayerKind::Line => {
                    for chunk in m.vertices.chunks(stroke::FLOATS_PER_VERTEX) {
                        worst_pos = worst_pos.max(chunk[0].abs()).max(chunk[1].abs());
                        let length = (chunk[2] * chunk[2] + chunk[3] * chunk[3]).sqrt();
                        // A miter normal is deliberately *longer* than unit, by
                        // 1/cos(theta/2), so both segments' edges meet on it — the same
                        // trick MapLibre uses with its "special" normals of up to length
                        // 126/63 = 2. So the bound is the miter limit, not 1.
                        worst_normal = worst_normal.max(length);
                        worst_distance = worst_distance.max(chunk[6].abs());
                    }
                }
                LayerKind::Symbol => {
                    // Symbol quads carry (x, y, u, v): positions are tile-local like
                    // fills, UVs are atlas 0..1 — checked by tess::text's own tests.
                    for chunk in m.vertices.chunks(crate::tile::symbol::FLOATS_PER_VERTEX) {
                        worst_pos = worst_pos.max(chunk[0].abs()).max(chunk[1].abs());
                    }
                }
            }
        }

        // Protomaps buffers tiles by a few percent, so a little overspill is expected and
        // 2.0 would not be.
        assert!(worst_pos < 1.3, "positions reach {worst_pos}, not tile-local 0..1");
        assert!(
            worst_normal <= stroke::MITER_LIMIT + 1e-3,
            "a normal is {worst_normal} long, past the miter limit, so that join is too wide",
        );
        assert!(
            worst_distance < 1.3,
            "distance-along-line reaches {worst_distance}; line.vert scales it by the tile's \
             pixel size, so it must be tile-local, not extent units",
        );
        // And every vertex must be finite: a NaN normal from a zero-length segment would
        // silently drop or explode the triangles that share it.
        for m in &mesh.meshes {
            for f in &m.vertices {
                assert!(f.is_finite(), "{} emitted a non-finite vertex", layers[m.layer_index].id);
            }
        }
    }

    #[test]
    fn positions_are_tile_normalised() {
        // The clip transform assumes 0..1 within the tile. Clipped geometry overspills
        // the edges a little, which is why the bound is generous rather than exact.
        let layers = style::layers();
        let mesh = build(&real(), &layers, 11, 339, 770, false);
        for m in &mesh.meshes {
            let stride = match m.kind {
                LayerKind::Fill => fill::FLOATS_PER_VERTEX,
                LayerKind::Line => stroke::FLOATS_PER_VERTEX,
                LayerKind::Symbol => crate::tile::symbol::FLOATS_PER_VERTEX,
            };
            for chunk in m.vertices.chunks(stride) {
                assert!(chunk[0] > -3.0 && chunk[0] < 4.0, "x {} is not tile-normalised", chunk[0]);
                assert!(chunk[1] > -3.0 && chunk[1] < 4.0, "y {} is not tile-normalised", chunk[1]);
            }
        }
    }

    #[test]
    fn a_layer_outside_its_zoom_range_is_skipped() {
        let layers = style::layers();
        let low = build(&real(), &layers, 11, 339, 770, false);
        // buildings is min_zoom 14, and roads-minor is 13; the tile's road is a
        // major_road anyway.
        assert!(mesh_for(&low, &layers, "roads-minor").is_none());
        assert!(mesh_for(&low, &layers, "earth").is_some(), "earth draws at every zoom");
    }

    #[test]
    fn a_casing_produces_twice_the_vertices_of_a_plain_stroke() {
        let layers = style::layers();
        let mesh = build(&real(), &layers, 11, 339, 770, false);
        let plain = mesh_for(&mesh, &layers, "roads-major").expect("plain");
        let casing = mesh_for(&mesh, &layers, "roads-major-casing").expect("casing");
        assert_eq!(
            plain.vertices.len() * 2,
            casing.vertices.len(),
            "a casing is two bands of the same centreline",
        );
        assert_eq!(plain.indices.len() * 2, casing.indices.len());
    }

    #[test]
    fn an_empty_tile_produces_no_meshes() {
        let layers = style::layers();
        let mesh = build(&Body::new(4096), &layers, 11, 0, 0, false);
        assert!(mesh.meshes.is_empty());
    }

    #[test]
    fn a_line_layer_also_strokes_polygon_outlines() {
        // A lake shoreline and an administrative boundary are lines over area features.
        let outline = vec![Layer {
            id: "water-edge".to_string(),
            source_layer: "water".to_string(),
            source_layer_id: tilecodec::mamaps::dict::LAYER_WATER,
            kind: LayerKind::Line,
            kinds: Vec::new(),
            kind_ids: Vec::new(),
            require_flags: 0,
            forbid_flags: 0,
            detail_ids: Vec::new(),
            forbid_details: Vec::new(),
            light: 0xFF000000,
            dark: 0xFF000000,
            opacity: Ramp::constant(1.0),
            width: Ramp::constant(1.0),
            gap_width: Ramp::constant(0.0),
            dash: (0.0, 0.0),
            text_size: Ramp::constant(1.0),
            uppercase: false,
            medium: false,
            halo_light: 0x00000000,
            halo_dark: 0x00000000,
            halo_width: 1.0,
            min_zoom: 0,
            max_zoom: 22,
            authored: "water".to_string(),
        }];
        let mesh = build(&real(), &outline, 11, 339, 770, false);
        assert_eq!(mesh.meshes.len(), 1, "the water polygons' outlines stroke");
        assert!(!mesh.meshes[0].indices.is_empty());
    }
    /// **The Phase 4 milestone, end to end inside this crate.** A `.mamaps` archive is built,
    /// opened through a `RangeReader`, and tessellated -- so the container, the reader, the style's
    /// interned ids and this module are proven together, on data the tiler already produced.
    #[test]
    fn a_tile_read_out_of_a_mamaps_archive_tessellates() {
        use std::cell::RefCell;
        use tilecodec::mamaps::write::{Options, StreamWriter};
        use tilecodec::mamaps::MamapsArchive;
        use tilecodec::stream::RangeReader;

        struct Memory {
            bytes: Vec<u8>,
            requests: RefCell<usize>,
        }
        impl RangeReader for Memory {
            fn read(&self, offset: u64, length: u32) -> tilecodec::proto::Result<Vec<u8>> {
                *self.requests.borrow_mut() += 1;
                if offset >= self.bytes.len() as u64 {
                    return Ok(Vec::new());
                }
                let end = (offset + length as u64).min(self.bytes.len() as u64);
                Ok(self.bytes[offset as usize..end as usize].to_vec())
            }
        }

        let id = tilecodec::pmtiles::tile_id(11, 339, 770);
        let options = Options { min_zoom: 0, max_zoom: 14, ..Options::default() };
        let mut writer = StreamWriter::new(options).expect("options");
        writer.append(id, &real()).expect("append");
        let bytes = writer.finish().expect("finish");

        let mut archive =
            MamapsArchive::open(Memory { bytes, requests: RefCell::new(0) }).expect("open");
        assert_eq!(*archive.reader().requests.borrow(), 1, "a cold open is one request");

        let layers = style::layers();
        let body = archive.tile(11, 339, 770).expect("read").expect("present");
        let mesh = build(&body, layers, 11, 339, 770, archive.header.rings_validated());
        // The same layers the fixture produces when tessellated directly, so nothing was lost
        // between the encoder and the reader.
        for id in ["earth", "water", "roads-major", "roads-major-casing"] {
            assert!(mesh_for(&mesh, layers, id).is_some(), "{id} should draw");
        }
        assert!(mesh_for(&mesh, layers, "buildings").is_none(), "the tile has no buildings");
    }
}
