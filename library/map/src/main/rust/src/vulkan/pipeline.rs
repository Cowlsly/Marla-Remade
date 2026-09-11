//! The fill and line pipelines, built from the SPIR-V `build.rs` compiled.
//!
//! # Push constants, and no descriptor sets at all
//!
//! Everything per-draw — the tile's clip matrix, the layer's colour, width, gap and dash,
//! the per-frame clock and the per-tile morph factor — is 128 bytes, exactly the minimum
//! the Vulkan spec guarantees for push constants. So there are no uniform buffers, no
//! descriptor set layouts, no descriptor pool and nothing to keep in sync with the camera.
//! `vkCmdPushConstants` before each draw is the whole per-draw state.
//!
//! This is a deliberate departure from the WebGPU design that preceded it, which used a
//! uniform buffer per tile plus a bind group per (tile, layer) so that pre-recorded
//! render bundles could be replayed while the camera moved. Bundles existed to avoid
//! WebGPU's per-draw validation cost; raw Vulkan does not have that cost, so the
//! simplest thing that works is to re-record the command buffer each frame. Roughly 600
//! draws at a few `vkCmd` calls each is a fraction of a millisecond. If profiling says
//! otherwise, secondary command buffers are the escape hatch — and they would need the
//! descriptor-set indirection back, because push constants are not inherited.

use crate::tess::{fill, stroke};
use crate::tile::symbol;
use ash::vk;
use std::ffi::CStr;

/// Bytes of push constant: `mat4` + four `vec4`. Exactly 128, the guaranteed minimum.
pub const PUSH_CONSTANT_BYTES: u32 = 64 + 16 + 16 + 16 + 16;

/// The default `Push::morph`: fully present, no LOD cross-fade. Every draw but WS-D's uses it.
pub const MORPH_NONE: [f32; 4] = [1.0, 0.0, 0.0, 0.0];

/// `Push::line[2]` for a ribbon that carries no markings at all — see the [`Push`] doc comment.
///
/// Below the `[-1, +1]` a real centre-line `t` is bounded to, so it cannot collide with one.
/// `road_surface.frag` tests against its own `NO_MARKINGS_BELOW` of -1.5, halfway between this and
/// the nearest legal split, so neither side is sensitive to the exact value.
pub const NO_MARKINGS: f32 = -2.0;

/// The push constant block, matching the `Push` block the shaders declare.
///
/// `repr(C)` so the field order is the declaration order, which is what the SPIR-V
/// offsets assume.
///
/// # Field map (the shared contract A/B/C/D/E/G build on)
///
/// | field          | bytes   | meaning                                                        |
/// |----------------|---------|---------------------------------------------------------------|
/// | `tile_to_clip` | 0..64   | column-major tile-local `(u, v, height, 1)` → clip            |
/// | `color`        | 64..80  | linear RGBA, 0..1                                              |
/// | `line`         | 80..96  | `half_width_px, gap_half_px, dash_on, dash_off`               |
/// | `misc`         | 96..112 | `tile_px, edge_aa, lateral_px, clock_seconds`                 |
/// | `morph`        | 112..128| `opacity(WS-D), reserved, reserved, reserved`                 |
///
/// `misc.w` is the per-frame clock (seconds); `morph.x` is the per-tile opacity/morph factor
/// (1.0 = fully present) reserved for WS-D. Both default to a value that leaves the flat 2D
/// output byte-identical (`clock` is ignored by the current shaders, `morph.x` is 1.0).
///
/// # The ribbon draw reads three of these slots differently
///
/// [`ribbon`](Pipelines::ribbon) is a road carriageway rather than a stroke, so `gap_half_px`,
/// `dash_on`, `dash_off` and `lateral_px` are all meaningless to it: it has no casing bands, its
/// dash pattern is derived from the lane width, and a carriageway is never fanned sideways. Those
/// dead slots carry the markings contract instead, which is why the block does not have to grow
/// past the guaranteed 128 bytes to gain a whole new layer.
///
/// | slot     | line pipeline | ribbon pipeline                                                |
/// |----------|---------------|-----------------------------------------------------------------|
/// | `line.x` | half width px | carriageway half-width px — unchanged meaning                    |
/// | `line.y` | gap half px   | lane count, both directions together                             |
/// | `line.z` | dash on       | `t` of the forward/backward split, the centre line's position     |
/// | `line.w` | dash off      | 1.0 on a one-way, which suppresses the centre line entirely      |
/// | `misc.z` | lateral px    | 1.0 for a yellow centre line, 0.0 for a white one                |
///
/// `line.z` is an across-road coordinate in `[-1, +1]`, not a lane index, so an odd split needs no
/// special case: the shader takes the lane boundary nearest to it. `t` runs -1 at the left kerb to
/// +1 at the right, so with `n` of the `line.y` lanes lying on the -1 side the split is
///
/// ```text
/// line.z = 2.0 * n / lane_count - 1.0
/// ```
///
/// It is ignored when `line.w` is set, so a one-way may push anything there.
///
/// # `line.z` also carries "no markings at all", as [`NO_MARKINGS`]
///
/// Because a real split is an across-road coordinate it cannot leave `[-1, +1]`, so a value below
/// that range is free to mean something else. [`NO_MARKINGS`] (-2.0) tells `road_surface.frag` to
/// paint the asphalt and nothing on it — no edge lines, no dividers, no centre line.
///
/// The junction layer's lane connectors use it. A connector is a notional path across an
/// intersection, and road paint does not mark those out on the ground; it is also drawn in the
/// carriageway's own colour, so where it overlies the road its asphalt is invisible and its
/// markings are the whole of what shows. Twelve connectors at a crossroads then read as a tangle
/// of hairlines rather than as a widening of the junction.
///
/// Chosen over a new slot or a wider block deliberately: a connector is always a one-way, so
/// `line.z` is *already* dead on exactly the draws this applies to — the shader has never read it
/// there. That makes the reuse something the code enforces rather than a convention a reader has
/// to hold, and it costs no vertex attribute, no `Push` growth past the guaranteed 128 bytes, and
/// no archive format change.
///
/// `misc.z` is the driving convention, resolved per tile at archive build time — the Americas paint
/// the line between opposing traffic yellow and most of the rest of the world paints it white. It
/// is a flag rather than a colour because the marking palette belongs to the shader alongside the
/// white it has to match, not to a per-draw push.
///
/// The remaining slots keep their usual meaning and must still be filled: `color` is the asphalt
/// (the markings are the shader's own), `misc.x` the tile's pixel span, `misc.y` the edge-AA flag,
/// and `morph` [`MORPH_NONE`]. `misc.w` is unread by the ribbon — its markings are static, so there
/// is deliberately no dash phase the way the traffic draw has one.
///
/// Both flags are compared against 0.5, so any nonzero-ish float reads as set; push exactly 0.0 or
/// 1.0 rather than relying on that.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct Push {
    /// Column-major tile-local `(u, v, height, 1)` to clip space. `height` (the vertex z) is 0
    /// for every flat 2D layer and a real world-px height for buildings/terrain.
    pub tile_to_clip: [f32; 16],
    /// Linear RGBA, 0..1.
    pub color: [f32; 4],
    /// `half_width_px, gap_half_px, dash_on, dash_off`. The ribbon pipeline reads
    /// `half_width_px, lane_count, centre_t, oneway` instead; see the type doc.
    pub line: [f32; 4],
    /// `tile_px, edge_aa, lateral_px, clock_seconds`. `misc.w` is the per-frame clock forwarded
    /// from the host's `frameTimeNanos` (see `camera::Camera::time_seconds`). The ribbon pipeline
    /// reads `misc.z` as the centre-line colour flag rather than a lateral shift.
    pub misc: [f32; 4],
    /// Per-draw animation slot. `morph.x` is the per-tile opacity/morph factor (1.0 = fully
    /// present) reserved for WS-D's LOD cross-fade; `y`/`z`/`w` are reserved. Appended past
    /// `misc`, so a shader that never declares it keeps its existing offsets and behaviour.
    pub morph: [f32; 4],
}

impl Push {
    /// The block as bytes, for `vkCmdPushConstants`.
    pub fn as_bytes(&self) -> &[u8] {
        // Safe: `repr(C)` POD with no padding, read as its own length.
        unsafe {
            std::slice::from_raw_parts(
                self as *const Push as *const u8,
                std::mem::size_of::<Push>(),
            )
        }
    }
}

const FILL_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/fill.vert.spv"));
const FILL_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/fill.frag.spv"));
const LINE_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/line.vert.spv"));
const LINE_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/line.frag.spv"));
const RIBBON_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/road_surface.vert.spv"));
const RIBBON_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/road_surface.frag.spv"));
const SYMBOL_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/symbol.vert.spv"));
const SYMBOL_BILLBOARD_VERT: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/symbol_billboard.vert.spv"));
const SYMBOL_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/symbol.frag.spv"));
const SPRITE_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/sprite.frag.spv"));
const PUCK_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/puck.vert.spv"));
const PUCK_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/puck.frag.spv"));
const BUILDING_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/building.vert.spv"));
const BUILDING_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/building.frag.spv"));
const TERRAIN_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/terrain.vert.spv"));
const TERRAIN_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/terrain.frag.spv"));

pub struct Pipelines {
    pub layout: vk::PipelineLayout,
    /// Layout with the atlas descriptor set (set 0) for the symbol pipeline.
    pub symbol_layout: vk::PipelineLayout,
    pub fill: vk::Pipeline,
    pub line: vk::Pipeline,
    /// Road carriageways: a filled surface with the lane markings painted onto it, from
    /// `road_surface.vert`/`.frag` over the 6-float `tess::ribbon` vertex.
    ///
    /// Deliberately a second pipeline rather than a wider [`line`](Self::line). `TrafficMesh` and
    /// the route overlay both ride the 7-float stroke format and the line pipeline, so widening it
    /// to carry an across-road coordinate would charge every one of them for a layer they do not
    /// draw — the tradeoff `tile/geometry.rs` already states about the lane fans this replaces.
    ///
    /// Same fixed-function state as [`line`](Self::line) — [`Depth::Off`], no culling, straight
    /// src-alpha — because a carriageway is flat basemap drawn in layer order like every other
    /// flat 2D layer. Only the vertex format and the shaders differ.
    pub ribbon: vk::Pipeline,
    /// Depth-tested (test + write, `LESS`) variant of [`fill`](Self::fill), for the 3D layers
    /// WS-A (buildings) and WS-G (terrain) add. It reuses the position-only fill shaders so the
    /// render-pass depth path is exercised today; the 3D workstreams build their own pipelines
    /// with the same [`Depth::TestWrite`] against the depth attachment WS0 added to the pass.
    /// The flat 2D layers stay on [`fill`](Self::fill)/[`line`](Self::line) with depth off, so
    /// their output is unchanged.
    pub depth: vk::Pipeline,
    /// The 3D building pipeline (WS-A): the extruded walls and roof caps of the `buildings` layer.
    /// Its own vertex format — position + height + normal + per-vertex ARGB colour — and its own
    /// `building.vert`/`building.frag`, depth-tested ([`Depth::TestWrite`]) so buildings occlude
    /// one another and the basemap at z14+. Takes the push-only [`layout`](Self::layout): it samples
    /// no atlas, its colour is per-vertex. At pitch 0 its vertex shader collapses to the footprint,
    /// so the flat map is unchanged.
    pub building: vk::Pipeline,
    /// The 3D terrain pipeline (WS-G): the DEM-displaced ground grid of a tile that carries a
    /// heightmap. Its own vertex format — position + height + surface normal (no per-vertex colour;
    /// the ground colour is the pushed `earth` colour) — and its own `terrain.vert`/`terrain.frag`,
    /// depth-tested ([`Depth::TestWrite`]) so hills occlude one another and let buildings on the far
    /// side of a ridge be hidden by it. Takes the push-only [`layout`](Self::layout): it samples no
    /// atlas. Drawn *before* the flat layer loop, so the flat layers paint over it; at pitch 0 its
    /// vertex shader collapses the grid to the flat footprint, so the overhead map is unchanged.
    pub terrain: vk::Pipeline,
    pub symbol: vk::Pipeline,
    /// POI icons. The billboard vertex shader from [`symbol`](Self::symbol) paired with the sprite
    /// fragment shader, on the same 6-float format and the same
    /// [`symbol_layout`](Self::symbol_layout): an icon faces the camera under tilt exactly as its
    /// label does, but samples a picture rather than a distance field. Deliberately the *same*
    /// vertex shader as the text so the two can never disagree about where the anchor projects.
    pub icon: vk::Pipeline,
    /// App markers. The plain on-ground vertex shader on the 4-float
    /// [`MARKER_FLOATS_PER_VERTEX`](crate::tile::symbol::MARKER_FLOATS_PER_VERTEX) format: markers
    /// resolve their quads to clip space on the CPU and draw through an identity matrix, so they
    /// need no per-vertex anchor and no billboard branch.
    pub sprite: vk::Pipeline,
    /// Screen-anchored overlay quads — today only the user puck. Takes the push-only
    /// [`layout`](Self::layout), not [`symbol_layout`](Self::symbol_layout), because it
    /// samples nothing: the puck is drawn analytically from a distance and an angle, so
    /// there is no atlas and no descriptor set.
    ///
    /// Built here rather than bolted onto the renderer so it survives `Renderer::rebuild`,
    /// which destroys and remakes every `Pipelines` on each resize and rotation.
    pub puck: vk::Pipeline,
    /// The region mask: draws the selected region's tessellated shape into the stencil and no
    /// colour at all, so [`scrim`](Self::scrim) can skip those pixels.
    ///
    /// A stencil rather than a clipped polygon because a region arrives as one clipped piece per
    /// tile: rasterising them all into the same stencil unions them for free, whereas cutting the
    /// region out of a viewport quad geometrically needs a polygon boolean, which is what two
    /// earlier attempts at exactly this foundered on.
    pub mask: vk::Pipeline,
    /// The dimming scrim, drawn over the whole viewport wherever the stencil is still zero.
    pub scrim: vk::Pipeline,
}

impl Pipelines {
    /// # Safety
    ///
    /// `render_pass` must outlive these pipelines. `atlas_layout` is the
    /// descriptor set layout [`images::AtlasSet`] built — `None` on a host
    /// build that never creates pipelines (tests link this module for the
    /// [`Push`] size asserts only).
    ///
    /// `cache` is [`crate::vulkan::cache::ShaderCache`]'s handle, or
    /// [`vk::PipelineCache::null`] for no cache. Every one of the twelve pipelines below is built
    /// through it, and they share shader modules heavily — `fill` is also `depth`, `mask` and
    /// `scrim`, and `symbol`'s billboard vertex shader is also `icon`'s — so even a cold cache
    /// pays for itself within this one call: the driver populates it as it goes, and the repeats
    /// hit rather than recompile.
    pub unsafe fn new(
        device: &ash::Device,
        render_pass: vk::RenderPass,
        samples: vk::SampleCountFlags,
        atlas_layout: Option<vk::DescriptorSetLayout>,
        cache: vk::PipelineCache,
    ) -> Result<Pipelines, String> {
        let push_range = vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
            .offset(0)
            .size(PUSH_CONSTANT_BYTES);
        let layout_info = vk::PipelineLayoutCreateInfo::default()
            .push_constant_ranges(std::slice::from_ref(&push_range));
        let layout = device
            .create_pipeline_layout(&layout_info, None)
            .map_err(|e| format!("create_pipeline_layout {e:?}"))?;

        let fill_vert = shader_module(device, FILL_VERT)?;
        let fill_frag = shader_module(device, FILL_FRAG)?;
        let line_vert = shader_module(device, LINE_VERT)?;
        let line_frag = shader_module(device, LINE_FRAG)?;
        let ribbon_vert = shader_module(device, RIBBON_VERT)?;
        let ribbon_frag = shader_module(device, RIBBON_FRAG)?;
        let symbol_vert = shader_module(device, SYMBOL_VERT)?;
        let symbol_billboard_vert = shader_module(device, SYMBOL_BILLBOARD_VERT)?;
        let symbol_frag = shader_module(device, SYMBOL_FRAG)?;
        let sprite_frag = shader_module(device, SPRITE_FRAG)?;
        let puck_vert = shader_module(device, PUCK_VERT)?;
        let puck_frag = shader_module(device, PUCK_FRAG)?;
        let building_vert = shader_module(device, BUILDING_VERT)?;
        let building_frag = shader_module(device, BUILDING_FRAG)?;
        let terrain_vert = shader_module(device, TERRAIN_VERT)?;
        let terrain_frag = shader_module(device, TERRAIN_FRAG)?;

        let fill_attributes = [vk::VertexInputAttributeDescription::default()
            .location(0)
            .binding(0)
            .format(vk::Format::R32G32_SFLOAT)
            .offset(0)];
        let line_attributes = [
            vk::VertexInputAttributeDescription::default()
                .location(0)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(0),
            vk::VertexInputAttributeDescription::default()
                .location(1)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(8),
            vk::VertexInputAttributeDescription::default()
                .location(2)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(16),
            vk::VertexInputAttributeDescription::default()
                .location(3)
                .binding(0)
                .format(vk::Format::R32_SFLOAT)
                .offset(24),
        ];
        // Ribbon (road carriageways): position (2 floats), join normal (2 floats), the normalised
        // across-road coordinate `t`, and tile-local distance-along. 24-byte stride. `t` is both the
        // vertex shader's extrusion multiplier and the fragment shader's marking coordinate, which
        // is what keeps this to six floats rather than seven.
        let ribbon_attributes = [
            vk::VertexInputAttributeDescription::default()
                .location(0)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(0),
            vk::VertexInputAttributeDescription::default()
                .location(1)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(8),
            vk::VertexInputAttributeDescription::default()
                .location(2)
                .binding(0)
                .format(vk::Format::R32_SFLOAT)
                .offset(16),
            vk::VertexInputAttributeDescription::default()
                .location(3)
                .binding(0)
                .format(vk::Format::R32_SFLOAT)
                .offset(20),
        ];
        // Sprite (app markers): position (already clip-space) + uv (atlas), 4 floats. Markers
        // resolve their corners on the CPU and draw through an identity matrix, so there is no
        // tile-local anchor to project and this format must not grow. POI icons no longer use it —
        // they moved to the billboard format below so they face the camera under tilt.
        let symbol_attributes = [
            vk::VertexInputAttributeDescription::default()
                .location(0)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(0),
            vk::VertexInputAttributeDescription::default()
                .location(1)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(8),
        ];
        // Symbol text and POI icons (billboarded): position (tile-local) + uv (atlas) + ground
        // anchor (tile-local), 6 floats. The anchor lets `symbol_billboard.vert` keep point labels
        // and their icons upright and pinned to the ground under tilt; at pitch 0 it is ignored and
        // output is unchanged. One format for both is what keeps an icon on top of its label.
        let symbol_billboard_attributes = [
            vk::VertexInputAttributeDescription::default()
                .location(0)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(0),
            vk::VertexInputAttributeDescription::default()
                .location(1)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(8),
            vk::VertexInputAttributeDescription::default()
                .location(2)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(16),
        ];
        // Building (WS-A): position+height (3 floats), face normal (3 floats), then the per-vertex
        // ARGB colour as one `R8G8B8A8_UNORM` word the shader reads as a 0..1 vec4. 28-byte stride.
        let building_attributes = [
            vk::VertexInputAttributeDescription::default()
                .location(0)
                .binding(0)
                .format(vk::Format::R32G32B32_SFLOAT)
                .offset(0),
            vk::VertexInputAttributeDescription::default()
                .location(1)
                .binding(0)
                .format(vk::Format::R32G32B32_SFLOAT)
                .offset(12),
            vk::VertexInputAttributeDescription::default()
                .location(2)
                .binding(0)
                .format(vk::Format::R8G8B8A8_UNORM)
                .offset(24),
        ];

        // Terrain (WS-G): position+height (3 floats) then the surface normal (3 floats). 24-byte
        // stride, no colour — the ground colour is the pushed `earth` colour, not per-vertex.
        let terrain_attributes = [
            vk::VertexInputAttributeDescription::default()
                .location(0)
                .binding(0)
                .format(vk::Format::R32G32B32_SFLOAT)
                .offset(0),
            vk::VertexInputAttributeDescription::default()
                .location(1)
                .binding(0)
                .format(vk::Format::R32G32B32_SFLOAT)
                .offset(12),
        ];

        let fill = build(
            device,
            layout,
            render_pass,
            samples,
            fill_vert,
            fill_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
            Stencil::Ignore,
            Depth::Off,
            cache,
        );
        let line = build(
            device,
            layout,
            render_pass,
            samples,
            line_vert,
            line_frag,
            (stroke::FLOATS_PER_VERTEX * 4) as u32,
            &line_attributes,
            Stencil::Ignore,
            Depth::Off,
            cache,
        );
        // Road carriageways. Same fixed-function state as `line` — a carriageway is flat basemap,
        // so depth stays off and layer order does the compositing exactly as it does for a stroke.
        // Only the vertex format and the shaders differ, which is the whole point of splitting it
        // off rather than widening the format every stroked layer shares.
        let ribbon = build(
            device,
            layout,
            render_pass,
            samples,
            ribbon_vert,
            ribbon_frag,
            (crate::tess::ribbon::FLOATS_PER_VERTEX * 4) as u32,
            &ribbon_attributes,
            Stencil::Ignore,
            Depth::Off,
            cache,
        );
        // The depth-tested variant of `fill`: same shaders and vertex format, depth test + write
        // on. WS-A/WS-G draw their extruded/relief geometry through pipelines built like this so
        // the 3D layers occlude correctly; the flat 2D layers never bind it.
        let depth = build(
            device,
            layout,
            render_pass,
            samples,
            fill_vert,
            fill_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
            Stencil::Ignore,
            Depth::TestWrite,
            cache,
        );
        // The 3D building pipeline: its own shaders and 7-float vertex, depth test + write on so
        // buildings occlude correctly. Push-only layout — colour is per-vertex, not a uniform.
        let building = build(
            device,
            layout,
            render_pass,
            samples,
            building_vert,
            building_frag,
            (crate::tess::roof::FLOATS_PER_VERTEX * 4) as u32,
            &building_attributes,
            Stencil::Ignore,
            Depth::TestWrite,
            cache,
        );
        // The 3D terrain pipeline: its own shaders and 6-float vertex, depth test + write on so the
        // relief occludes correctly. Push-only layout — the ground colour is the pushed `earth`
        // colour. At pitch 0 its vertex shader collapses to the flat footprint, so the map is
        // unchanged.
        let terrain = build(
            device,
            layout,
            render_pass,
            samples,
            terrain_vert,
            terrain_frag,
            (crate::tess::terrain::FLOATS_PER_VERTEX * 4) as u32,
            &terrain_attributes,
            Stencil::Ignore,
            Depth::TestWrite,
            cache,
        );

        // The symbol pipeline needs the atlas descriptor set, so it gets its own
        // layout: same push-constant range plus set 0. The sprite pipeline reuses this
        // layout with a second set from the same pool.
        let symbol_layout = match atlas_layout {
            Some(set_layout) => {
                let symbol_layout_info = vk::PipelineLayoutCreateInfo::default()
                    .push_constant_ranges(std::slice::from_ref(&push_range))
                    .set_layouts(std::slice::from_ref(&set_layout));
                device
                    .create_pipeline_layout(&symbol_layout_info, None)
                    .map_err(|e| format!("create_pipeline_layout(symbol) {e:?}"))?
            }
            None => {
                device.destroy_shader_module(fill_vert, None);
                device.destroy_shader_module(fill_frag, None);
                device.destroy_shader_module(line_vert, None);
                device.destroy_shader_module(line_frag, None);
                device.destroy_shader_module(ribbon_vert, None);
                device.destroy_shader_module(ribbon_frag, None);
                device.destroy_shader_module(symbol_vert, None);
                device.destroy_shader_module(symbol_billboard_vert, None);
                device.destroy_shader_module(symbol_frag, None);
                device.destroy_shader_module(sprite_frag, None);
                device.destroy_shader_module(puck_vert, None);
                device.destroy_shader_module(puck_frag, None);
                device.destroy_shader_module(building_vert, None);
                device.destroy_shader_module(building_frag, None);
                device.destroy_shader_module(terrain_vert, None);
                device.destroy_shader_module(terrain_frag, None);
                device.destroy_pipeline_layout(layout, None);
                return Err("symbol pipeline needs an atlas descriptor set layout".into());
            }
        };
        let symbol = build(
            device,
            symbol_layout,
            render_pass,
            samples,
            symbol_billboard_vert,
            symbol_frag,
            (symbol::FLOATS_PER_VERTEX * 4) as u32,
            &symbol_billboard_attributes,
            Stencil::Ignore,
            Depth::Off,
            cache,
        );
        // POI icons: the billboard vertex shader (so they face the camera under tilt, exactly as
        // the text beside them does) paired with the sprite fragment shader (because an icon is a
        // picture, not a distance field). No new shader — both halves already existed.
        let icon = build(
            device,
            symbol_layout,
            render_pass,
            samples,
            symbol_billboard_vert,
            sprite_frag,
            (symbol::ICON_FLOATS_PER_VERTEX * 4) as u32,
            &symbol_billboard_attributes,
            Stencil::Ignore,
            Depth::Off,
            cache,
        );
        let sprite = build(
            device,
            symbol_layout,
            render_pass,
            samples,
            symbol_vert,
            sprite_frag,
            (symbol::MARKER_FLOATS_PER_VERTEX * 4) as u32,
            &symbol_attributes,
            Stencil::Ignore,
            Depth::Off,
            cache,
        );

        // The overlay quad is position-only in -1..1, so it shares the fill vertex
        // format and its 8-byte stride.
        let puck = build(
            device,
            layout,
            render_pass,
            samples,
            puck_vert,
            puck_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
            Stencil::Ignore,
            Depth::Off,
            cache,
        );

        // The region mask and its scrim. Both are position-only quads/triangles in the same
        // vertex format as `fill`: the mask draws the region's tessellated shape, the scrim a
        // full-viewport quad that the stencil keeps off the region itself.
        let mask = build(
            device,
            layout,
            render_pass,
            samples,
            fill_vert,
            fill_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
            Stencil::Write,
            Depth::Off,
            cache,
        );
        let scrim = build(
            device,
            layout,
            render_pass,
            samples,
            fill_vert,
            fill_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
            Stencil::TestOutside,
            Depth::Off,
            cache,
        );

        // The modules are only needed while the pipelines are being created.
        device.destroy_shader_module(fill_vert, None);
        device.destroy_shader_module(fill_frag, None);
        device.destroy_shader_module(line_vert, None);
        device.destroy_shader_module(line_frag, None);
        device.destroy_shader_module(ribbon_vert, None);
        device.destroy_shader_module(ribbon_frag, None);
        device.destroy_shader_module(symbol_vert, None);
        device.destroy_shader_module(symbol_billboard_vert, None);
        device.destroy_shader_module(symbol_frag, None);
        device.destroy_shader_module(sprite_frag, None);
        device.destroy_shader_module(puck_vert, None);
        device.destroy_shader_module(puck_frag, None);
        device.destroy_shader_module(building_vert, None);
        device.destroy_shader_module(building_frag, None);
        device.destroy_shader_module(terrain_vert, None);
        device.destroy_shader_module(terrain_frag, None);

        match (fill, line, ribbon, depth, building, terrain, symbol, icon, sprite, puck, mask, scrim)
        {
            (
                Ok(fill),
                Ok(line),
                Ok(ribbon),
                Ok(depth),
                Ok(building),
                Ok(terrain),
                Ok(symbol),
                Ok(icon),
                Ok(sprite),
                Ok(puck),
                Ok(mask),
                Ok(scrim),
            ) => Ok(Pipelines {
                layout,
                symbol_layout,
                fill,
                line,
                ribbon,
                depth,
                building,
                terrain,
                symbol,
                icon,
                sprite,
                puck,
                mask,
                scrim,
            }),
            (
                fill,
                line,
                ribbon,
                depth,
                building,
                terrain,
                symbol,
                icon,
                sprite,
                puck,
                mask,
                scrim,
            ) => {
                for created in [
                    fill, line, ribbon, depth, building, terrain, symbol, icon, sprite, puck, mask,
                    scrim,
                ]
                .into_iter()
                .flatten()
                {
                    device.destroy_pipeline(created, None);
                }
                device.destroy_pipeline_layout(symbol_layout, None);
                device.destroy_pipeline_layout(layout, None);
                Err("pipeline creation failed".into())
            }
        }
    }

    /// # Safety
    ///
    /// The device must be idle.
    pub unsafe fn destroy(&self, device: &ash::Device) {
        device.destroy_pipeline(self.fill, None);
        device.destroy_pipeline(self.line, None);
        device.destroy_pipeline(self.ribbon, None);
        device.destroy_pipeline(self.depth, None);
        device.destroy_pipeline(self.building, None);
        device.destroy_pipeline(self.terrain, None);
        device.destroy_pipeline(self.symbol, None);
        device.destroy_pipeline(self.icon, None);
        device.destroy_pipeline(self.sprite, None);
        device.destroy_pipeline(self.puck, None);
        device.destroy_pipeline(self.mask, None);
        device.destroy_pipeline(self.scrim, None);
        device.destroy_pipeline_layout(self.symbol_layout, None);
        device.destroy_pipeline_layout(self.layout, None);
    }
}

/// How a pipeline uses the stencil attachment.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Stencil {
    /// Neither tests nor writes: every pipeline that draws the map itself.
    Ignore,
    /// Writes 1 wherever it draws, and writes no colour. The region mask.
    Write,
    /// Draws only where the stencil is still 0 — outside the region. The scrim.
    TestOutside,
}

/// How a pipeline uses the depth attachment WS0 added to the render pass.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Depth {
    /// Neither tests nor writes depth: every flat 2D layer. Its output is exactly what it was
    /// before the depth attachment existed, which is what keeps pitch-0 rendering byte-identical.
    Off,
    /// Tests and writes depth with `LESS`: the 3D layers (buildings, terrain) that must occlude
    /// one another. Only meaningful under a perspective camera, where the clip matrix produces a
    /// real per-vertex depth.
    TestWrite,
}

#[allow(clippy::too_many_arguments)]
unsafe fn build(
    device: &ash::Device,
    layout: vk::PipelineLayout,
    render_pass: vk::RenderPass,
    samples: vk::SampleCountFlags,
    vertex: vk::ShaderModule,
    fragment: vk::ShaderModule,
    stride: u32,
    attributes: &[vk::VertexInputAttributeDescription],
    stencil: Stencil,
    depth: Depth,
    cache: vk::PipelineCache,
) -> Result<vk::Pipeline, String> {
    let entry = c"main";
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(vertex)
            .name(entry),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(fragment)
            .name(entry),
    ];

    let binding = vk::VertexInputBindingDescription::default()
        .binding(0)
        .stride(stride)
        .input_rate(vk::VertexInputRate::VERTEX);
    let vertex_input = vk::PipelineVertexInputStateCreateInfo::default()
        .vertex_binding_descriptions(std::slice::from_ref(&binding))
        .vertex_attribute_descriptions(attributes);
    let assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);

    // Viewport and scissor are dynamic, so a resize does not rebuild the pipeline — only
    // the swapchain.
    let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let dynamic = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
    let viewport_state =
        vk::PipelineViewportStateCreateInfo::default().viewport_count(1).scissor_count(1);

    // No culling: tessellated tile geometry arrives in whatever winding the clipper left
    // it in, and a culled road is an invisible road.
    let rasterization = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    // Must match the render pass's colour attachment, which `swapchain` chooses from
    // what the device supports.
    let multisample =
        vk::PipelineMultisampleStateCreateInfo::default().rasterization_samples(samples);

    // Straight src-alpha over one-minus-src-alpha. Layer order does the rest.
    let blend_attachment = vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(vk::BlendFactor::SRC_ALPHA)
        .dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(vk::BlendFactor::ONE)
        .dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .alpha_blend_op(vk::BlendOp::ADD)
        // The mask writes stencil only. Letting it write colour would paint the region's
        // triangles over the map in whatever the fill shader produced.
        .color_write_mask(if stencil == Stencil::Write {
            vk::ColorComponentFlags::empty()
        } else {
            vk::ColorComponentFlags::RGBA
        });
    let blend = vk::PipelineColorBlendStateCreateInfo::default()
        .attachments(std::slice::from_ref(&blend_attachment));

    // A subpass with a depth-stencil attachment requires this state on every pipeline. The flat
    // 2D layers pass `Depth::Off` (test + write disabled), so the attachment WS0 added is present
    // but inert for them — their colour output is unchanged. The 3D layers pass `Depth::TestWrite`.
    let stencil_op = match stencil {
        Stencil::Ignore => vk::StencilOpState::default(),
        // `REPLACE` with reference 1 rather than increment: the region's tile pieces overlap at
        // shared edges, and any counting op would disagree with itself there.
        Stencil::Write => vk::StencilOpState::default()
            .compare_op(vk::CompareOp::ALWAYS)
            .pass_op(vk::StencilOp::REPLACE)
            .fail_op(vk::StencilOp::REPLACE)
            .depth_fail_op(vk::StencilOp::REPLACE)
            .compare_mask(0xff)
            .write_mask(0xff)
            .reference(1),
        Stencil::TestOutside => vk::StencilOpState::default()
            .compare_op(vk::CompareOp::NOT_EQUAL)
            .pass_op(vk::StencilOp::KEEP)
            .fail_op(vk::StencilOp::KEEP)
            .depth_fail_op(vk::StencilOp::KEEP)
            .compare_mask(0xff)
            .write_mask(0)
            .reference(1),
    };
    let (depth_test, depth_write) = match depth {
        Depth::Off => (false, false),
        Depth::TestWrite => (true, true),
    };
    let depth_stencil = vk::PipelineDepthStencilStateCreateInfo::default()
        .depth_test_enable(depth_test)
        .depth_write_enable(depth_write)
        .depth_compare_op(vk::CompareOp::LESS)
        .stencil_test_enable(stencil != Stencil::Ignore)
        .front(stencil_op)
        .back(stencil_op);

    let info = vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&vertex_input)
        .input_assembly_state(&assembly)
        .viewport_state(&viewport_state)
        .rasterization_state(&rasterization)
        .multisample_state(&multisample)
        .depth_stencil_state(&depth_stencil)
        .color_blend_state(&blend)
        .dynamic_state(&dynamic)
        .layout(layout)
        .render_pass(render_pass)
        .subpass(0);

    device
        .create_graphics_pipelines(cache, std::slice::from_ref(&info), None)
        .map(|pipelines| pipelines[0])
        .map_err(|(_, e)| format!("create_graphics_pipelines {e:?}"))
}

unsafe fn shader_module(device: &ash::Device, spirv: &[u8]) -> Result<vk::ShaderModule, String> {
    // SPIR-V is a stream of 32-bit words. `build.rs` guarantees a real module, so a
    // misaligned length here would be a build-system bug rather than bad input.
    if spirv.len() % 4 != 0 || spirv.len() < 20 {
        return Err(format!("{} bytes is not a SPIR-V module", spirv.len()));
    }
    let mut words = Vec::with_capacity(spirv.len() / 4);
    for chunk in spirv.chunks_exact(4) {
        words.push(u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]));
    }
    let info = vk::ShaderModuleCreateInfo::default().code(&words);
    device.create_shader_module(&info, None).map_err(|e| format!("create_shader_module {e:?}"))
}

/// Unused, but kept so the entry-point name is stated once.
#[allow(dead_code)]
const ENTRY_POINT: &CStr = c"main";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_push_block_is_inside_the_guaranteed_limit() {
        // 128 bytes is the minimum `maxPushConstantsSize` the spec requires, so staying
        // at or under it means no device can reject this.
        assert_eq!(std::mem::size_of::<Push>() as u32, PUSH_CONSTANT_BYTES);
        assert!(PUSH_CONSTANT_BYTES <= 128, "{PUSH_CONSTANT_BYTES} exceeds the guaranteed 128");
    }

    #[test]
    fn the_push_block_has_no_padding() {
        // The shader reads it at fixed offsets, so a gap Rust inserted would silently
        // shift the colour and the widths.
        assert_eq!(std::mem::size_of::<Push>(), 64 + 16 + 16 + 16 + 16);
        assert_eq!(std::mem::align_of::<Push>(), 4);
    }
}
