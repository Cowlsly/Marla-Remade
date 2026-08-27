#version 450

// Line: extrudes the stroked centreline to its screen-space width.
//
// The tessellator emits geometry that is a function of the tile alone — position,
// the unit normal of the join, and two multipliers — and the width arrives as a
// push constant. That is deliberate: a stroke's width is a screen measurement that
// changes continuously with zoom, so baking it into vertices would re-tessellate
// every road in every visible tile on every zoom step, while panning, on the
// critical path.
//
//   offset = normal * (offsetMul * gapHalf + widthMul * halfWidth)
//
// `offsetMul` is 0 for a plain stroke and ±1 for the two bands of a casing;
// `widthMul` is ∓1 for a plain stroke's two edges and 0 / ±2 for a casing band's
// inner and outer edges — a band spans a full width, not a half width. See
// `tess::stroke`.

layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inNormal;
layout(location = 2) in vec2 inExtrude;
layout(location = 3) in float inDistance;

layout(location = 0) out float outDistancePx;

layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
} push;

void main() {
    float halfWidthPx = push.line.x;
    float gapHalfPx = push.line.y;
    float tilePx = max(push.misc.x, 1.0);

    // The offset is a pixel distance and the normal is a unit vector in tile-local
    // space, so dividing by the tile's pixel size converts one to the other.
    float offsetPx = inExtrude.x * gapHalfPx + inExtrude.y * halfWidthPx;
    vec2 offsetTile = inNormal * (offsetPx / tilePx);
    gl_Position = push.tileToClip * vec4(inPosition + offsetTile, 0.0, 1.0);

    // Distance along the line in pixels, for the dash pattern.
    outDistancePx = inDistance * tilePx;
}
