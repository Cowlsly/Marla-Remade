#version 450
// Road surface: extrudes the carriageway centreline to its screen-space width and
// hands the fragment stage an across-the-road coordinate.
//
// # Why this is not `line.vert` with another attribute
//
// A stroke has no across-the-road coordinate, so a marking cannot be placed on it —
// which is the whole reason `tess::stroke` cannot draw a lane divider without
// re-stroking the road once per divider. What the ribbon adds is `t`, a genuine
// normalised across-coordinate that is exactly -1 at one kerb and +1 at the other,
// so `road_surface.frag` can paint every marking as a function of `t` alone with no
// extra geometry at all.
//
// It is a separate pipeline rather than a wider shared vertex format because
// `TrafficMesh` and the route overlay both ride the 7-float line format and the line
// pipeline; widening that to serve the roads layer would make every one of them pay
// for four bytes they never read. `tile/geometry.rs` already states this tradeoff.
//
// # Width still lives here, not in the vertices
//
// Same invariant as `line.vert`, and for the same reason: a carriageway's width is a
// screen measurement that ramps continuously with zoom, so baking it into vertices
// would re-tessellate every road in every visible tile on every zoom step, while
// panning, on the critical path. The tessellator emits the centreline point, the
// join normal and `t`; the half-width arrives as a push constant and the offset is
//
//   offset = normal * (t * halfWidth)
//
// `t` doubles as the extrusion multiplier and as the fragment stage's marking
// coordinate, which is what keeps the vertex to six floats.
//
// # Do not normalise `inNormal`
//
// It is the *miter* normal, not a unit vector: `tess::ribbon::join_normal` lengthens the
// bisector by `1/cos(theta/2)`, clamped at `stroke::MITER_LIMIT`, and the taper scales it
// again below 1 where a section abuts one of a different lane count. Unit length holds
// only on a straight run.
//
// That extra length is load-bearing. It is what keeps the kerb at `|t| = 1` through a
// bend; with a unit normal the carriageway pinches at every join and every marking slides
// sideways with it, because a marking's position is a fraction of a width that is no
// longer the width it should be. `normalize(inNormal)` compiles, warns about nothing, and
// silently breaks the joins and the taper together.
layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inNormal;
layout(location = 2) in float inT;
layout(location = 3) in float inDistance;
layout(location = 0) out float outT;
layout(location = 1) out float outDistancePx;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    // Repurposed for the ribbon; see the `Push` doc comment in `vulkan/pipeline.rs`,
    // which is the contract the renderer fills in.
    // x: carriageway half-width px, y: lane count, z: centre-line `t`, w: oneway flag.
    vec4 line;
    // x: tile span in px, y: 1 when this pass owns edge antialiasing,
    // z: 1 when the centre line is yellow, w: the per-frame clock in seconds.
    vec4 misc;
    vec4 morph;
} push;

// Mirrors `style::paint::MIN_HALF_WIDTH_PX`; must match `road_surface.frag`.
const float MIN_HALF_WIDTH_PX = 0.5;

void main() {
    float wantHalfPx = push.line.x;
    float tilePx = max(push.misc.x, 1.0);
    // A carriageway thinner than a pixel falls between pixel centres and rasterises as
    // stipple, so give it a pixel of geometry to land on and let the fragment stage take
    // the difference back off as alpha. Identical to `line.vert`, so a hairline road at
    // low zoom fades rather than snapping to full strength.
    float halfWidthPx = max(wantHalfPx, MIN_HALF_WIDTH_PX);
    // The offset is a pixel distance and the normal is in tile-local space, so dividing by
    // the tile's pixel size converts one to the other. The normal's own length is a
    // deliberate miter/taper factor and is meant to survive this — see the header.
    vec2 offsetTile = inNormal * (inT * halfWidthPx / tilePx);
    gl_Position = push.tileToClip * vec4(inPosition + offsetTile, 0.0, 1.0);
    outT = inT;
    // Distance along the carriageway in pixels, for the divider dash phase. Scaled here
    // rather than in the tessellator for the same reason the width is: the attribute is
    // tile-local so the geometry stays a function of the tile alone.
    outDistancePx = inDistance * tilePx;
}
