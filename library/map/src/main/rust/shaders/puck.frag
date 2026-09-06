#version 450
// User puck: the bearing cone, the white rim and the blue dot, all from a distance and
// an angle rather than from geometry.
//
// Tessellating this would need a circle, an annulus sector with round caps, and
// per-vertex alpha the `fill` vertex format cannot carry. Solving it per fragment
// instead gives exact circles at any density, the real radial falloff across the cone's
// stroke, and antialiased edges that do not depend on MSAA.
layout(location = 0) in vec2 inLocal;
layout(location = 0) out vec4 outColor;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    // The dot and the cone. The rim's colour is a constant, below.
    vec4 color;
    // Radii in device px: x the rim, y the dot, z the cone's centreline, w half the
    // cone's stroke width.
    vec4 line;
    // x: bearing in radians clockwise from north. y: 1 when there is a bearing at all,
    // 0 when the fix carries no heading and the cone must not draw. z: the quad's radius
    // in device px, which turns a local -1..1 coordinate into pixels.
    vec4 misc;
} push;

// The rim is white in both palettes. It exists to separate the dot from whatever is
// under it, and a rim that followed the basemap would stop doing that on one of them.
const vec3 RIM = vec3(1.0);
// Half the cone's 60 degree sweep.
const float CONE_HALF_SWEEP = radians(30.0);
const float PI = radians(180.0);

// Source-over, on premultiplied colour.
vec4 over(vec4 src, vec4 dst) {
    return src + dst * (1.0 - src.a);
}

// 1 inside, 0 outside, with one pixel of falloff across the edge.
float disc(float distancePx, float radiusPx) {
    return clamp(radiusPx + 0.5 - distancePx, 0.0, 1.0);
}

void main() {
    float quadPx = push.misc.z;
    vec2 p = inLocal * quadPx;
    float r = length(p);

    vec4 acc = vec4(0.0);

    if (push.misc.y > 0.5) {
        float conePx = push.line.z;
        float halfStrokePx = push.line.w;
        // Clockwise from north, matching a compass bearing: clip y points down, so "up"
        // is -y.
        float offset = atan(p.x, -p.y) - push.misc.x;
        // The shortest way round, so a cone pointing just west of north is two degrees
        // from it rather than 358.
        offset = mod(offset + PI, 2.0 * PI) - PI;
        // Distance to the arc's centreline: straight across it inside the sweep, and to
        // the nearer end point outside it, which is what gives the round caps.
        float toCentreline;
        if (abs(offset) <= CONE_HALF_SWEEP) {
            toCentreline = abs(r - conePx);
        } else {
            float capAngle = push.misc.x + sign(offset) * CONE_HALF_SWEEP;
            toCentreline = distance(p, conePx * vec2(sin(capAngle), -cos(capAngle)));
        }
        // The gradient is radial across the whole puck rather than across the stroke, so
        // the arc is fully opaque at its inner edge and only ~71% at its outer one.
        // Fading to zero across the stroke instead looks visibly flat.
        float fade = 1.0 - clamp((r - 0.8 * quadPx) / (0.2 * quadPx), 0.0, 1.0);
        float cone = disc(toCentreline, halfStrokePx) * fade;
        acc = over(vec4(push.color.rgb * cone, cone), acc);
    }

    // Rim then dot, both over the cone, so the dot is never eaten by the cone's inner
    // edge.
    float rim = disc(r, push.line.x);
    acc = over(vec4(RIM * rim, rim), acc);
    float core = disc(r, push.line.y);
    acc = over(vec4(push.color.rgb * core, core), acc);

    if (acc.a <= 0.0) discard;
    // Back to straight alpha: the pipeline blends src-alpha over one-minus-src-alpha.
    outColor = vec4(acc.rgb / acc.a, acc.a * push.color.a);
}
