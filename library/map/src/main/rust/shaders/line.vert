#version 450
// Line: extrudes the stroked centreline to its screen-space width.
//
// The tessellator emits geometry that is a function of the tile alone - position,
// the unit normal of the join, and two multipliers - and the width arrives as a
// push constant. That is deliberate: a stroke's width is a screen measurement that
// changes continuously with zoom, so baking it into vertices would re-tessellate
// every road in every visible tile on every zoom step, while panning, on the
// critical path.
//
//   offset = normal * (offsetMul * gapHalf + widthMul * halfWidth + lateral)
//
// `offsetMul` is 0 for a plain stroke and ±1 for the two bands of a casing;
// `widthMul` is ±1 for a plain stroke's two edges and 0 / ±2 for a casing band's
// inner and outer edges - a band spans a full width, not a half width. See
// `tess::stroke`.
//
// `lateral` shifts the whole band sideways, which is how transit routes sharing
// one track fan out into parallel coloured lines. It is a push constant for the
// same reason the width is: it is a screen measurement that ramps with zoom, and
// baking it into vertices would re-tessellate the layer on every zoom step.
layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inNormal;
layout(location = 2) in vec2 inExtrude;
layout(location = 3) in float inDistance;
layout(location = 0) out float outDistancePx;
layout(location = 1) out float outEdgePx;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
} push;

// Mirrors `style::paint::MIN_HALF_WIDTH_PX`, which GLSL cannot include.
// `the_shader_floor_matches_the_style_constant` pins the two together.
const float MIN_HALF_WIDTH_PX = 0.5;

void main() {
    float wantHalfPx = push.line.x;
    float gapHalfPx = push.line.y;
    float tilePx = max(push.misc.x, 1.0);
    float lateralPx = push.misc.z;
    // A quad thinner than a pixel falls between pixel centres and rasterises as
    // stipple, so give it a pixel of geometry to land on. The fragment shader takes
    // the difference back off as alpha, which is how a thin road fades instead of
    // snapping to full strength.
    float halfWidthPx = max(wantHalfPx, MIN_HALF_WIDTH_PX);
    // The offset is a pixel distance and the normal is a unit vector in tile-local
    // space, so dividing by the tile's pixel size converts one to the other.
    float offsetPx = inExtrude.x * gapHalfPx + inExtrude.y * halfWidthPx + lateralPx;
    // Where this band's centreline sits: 0 for a plain stroke, and
    // gapHalf + halfWidth out on the offset side for a casing band. The lateral
    // shift has to be here too, or `outEdgePx` is measured from the old centre and
    // `line.frag`'s box filter fades the whole line instead of just its edges.
    float centrePx = inExtrude.x * gapHalfPx + sign(inExtrude.x) * halfWidthPx + lateralPx;
    // Signed distance from that centre, which spans -halfWidth..+halfWidth across
    // every band and so interpolates one unit per screen pixel.
    outEdgePx = offsetPx - centrePx;
    vec2 offsetTile = inNormal * (offsetPx / tilePx);
    gl_Position = push.tileToClip * vec4(inPosition + offsetTile, 0.0, 1.0);
    // Distance along the line in pixels, for the dash pattern.
    outDistancePx = inDistance * tilePx;
}
