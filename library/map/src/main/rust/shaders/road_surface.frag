#version 450
// Road surface fragment: asphalt, then the markings painted onto it.
//
// Everything here is a function of `t` — the across-the-road coordinate, exactly -1 at
// one kerb and +1 at the other — and of the distance along the carriageway. There is no
// marking geometry at all. That is the point of the ribbon: the old `lane_fans` path
// re-stroked a road's entire geometry once per divider, so an eight-lane road paid seven
// extra full strokes to draw seven hairlines. Here they cost arithmetic.
//
// # Which coverage terms are gated by `misc.y`, and which are not
//
// `misc.y` says whether this pass owns edge antialiasing — it is 0 under MSAA, where the
// rasteriser already resolves partial coverage and a second coverage term on top fades a
// diagonal road twice (`line.frag` documents the z6 case that forced this).
//
// That reasoning applies to the **carriageway silhouette** and to nothing else. A marking
// is painted *inside* a fully covered triangle, so the rasteriser sees no edge there and
// resolves nothing: MSAA cannot antialias a lane divider. Its coverage term is therefore
// unconditional. Gating the markings on `misc.y` too would leave every marking hard-edged
// on the MSAA path, and a hard-edged sub-pixel line crawls and flickers as it drifts
// across pixel centres while panning — far more visible than the road edge ever was.
//
// For the same reason every marking edge is derived in **pixels**, never in `t`. A fixed
// width in `t` is a fixed fraction of the road, so it would grow and shrink with zoom and
// go sub-pixel at exactly the zoom where the aliasing is worst.
//
// # Why markings fade out rather than shrink to nothing
//
// Marking widths are proportional to the lane (a real marking is a real width on the
// ground), floored so they stay rasterisable. Left alone, that floor is what a low zoom
// converges to: at a few pixels per lane the floors of the edge lines, the dividers and
// the centre line together are wider than the road, and a motorway renders as a white
// smear. So markings ramp off once a lane is too narrow to read one on, and the asphalt
// is left plain — which is also what the road looked like before any of this existed.
layout(location = 0) in float inT;
layout(location = 1) in float inDistancePx;
layout(location = 0) out vec4 outColor;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    // The carriageway's asphalt colour.
    vec4 color;
    // Repurposed for the ribbon; see the `Push` doc comment in `vulkan/pipeline.rs`,
    // which is the contract the renderer fills in.
    // x: carriageway half-width px, y: lane count, z: centre-line `t`, w: oneway flag.
    vec4 line;
    // x: tile span in px, y: 1 when the pass is single-sampled and this shader owns the
    // carriageway's edge antialiasing, z: 1 when the centre line is yellow,
    // w: the per-frame clock in seconds.
    vec4 misc;
    vec4 morph;
} push;

// Mirrors `style::paint::MIN_HALF_WIDTH_PX`; must match `road_surface.vert`.
const float MIN_HALF_WIDTH_PX = 0.5;

// A painted marking is about 0.15 m in a lane about 3.5 m wide. Held as a fraction of the
// lane rather than an absolute width so it stays a ground measurement across zooms.
const float MARK_WIDTH_LANES = 0.045;
// Below half a pixel a marking falls between pixel centres and stipples, so it is widened
// to something the rasteriser can hit and the fade below takes it away instead.
const float MIN_MARK_HALF_PX = 0.5;
// The lane widths between which markings ramp in. Under the lower bound a lane is a few
// pixels across and its markings would be most of it.
const float MARK_FADE_START_PX = 4.0;
const float MARK_FADE_FULL_PX = 10.0;
// Divider dash and gap, in lane widths: roughly the 3 m line and 9 m gap that is the
// common standard. Tied to the lane so the dash is a ground length, like the widths.
const float DASH_ON_LANES = 0.9;
const float DASH_OFF_LANES = 2.6;
// Markings are painted, not lit, so they are a flat off-white rather than pure white,
// which reads as a blown highlight against the asphalt.
const vec3 MARK_WHITE = vec3(0.92, 0.92, 0.90);
const vec3 MARK_YELLOW = vec3(0.95, 0.76, 0.18);

// The 1px box filter `line.frag` uses, over a signed pixel distance from a band's centre.
// Shared rather than a smoothstep so a marking edge and a road edge fade identically.
float band(float distancePx, float halfPx) {
    return clamp(halfPx - distancePx + 0.5, 0.0, 1.0);
}

void main() {
    float wantHalfPx = push.line.x;
    float halfWidthPx = max(wantHalfPx, MIN_HALF_WIDTH_PX);
    // Hand back the width the vertex shader added to make the quad rasterisable, so a
    // hairline road at low zoom draws faint rather than solid.
    float coverage = clamp(wantHalfPx / halfWidthPx, 0.0, 1.0);
    // `t` interpolates linearly across the ribbon, so this is the same signed
    // one-unit-per-screen-pixel measure `line.frag` calls `inEdgePx`.
    float edgePx = inT * halfWidthPx;
    coverage *= mix(1.0, band(abs(edgePx), halfWidthPx), push.misc.y);
    if (coverage <= 0.0) discard;

    float lanes = max(push.line.y, 1.0);
    float lanePx = 2.0 * halfWidthPx / lanes;
    float legible =
        clamp((lanePx - MARK_FADE_START_PX) / (MARK_FADE_FULL_PX - MARK_FADE_START_PX), 0.0, 1.0);

    vec3 rgb = push.color.rgb;
    if (legible > 0.0) {
        float markHalfPx = max(lanePx * MARK_WIDTH_LANES * 0.5, MIN_MARK_HALF_PX);
        bool oneway = push.line.w >= 0.5;
        float centreT = push.line.z;

        // Lane-index space: 0 at one kerb, `lanes` at the other, so a lane boundary is an
        // integer and the nearest one is a rounding away.
        float lane = (inT * 0.5 + 0.5) * lanes;
        float boundary = round(lane);
        float boundaryT = boundary / lanes * 2.0 - 1.0;
        // Boundary 0 and boundary `lanes` are the kerbs, which carry the solid edge line
        // rather than a divider.
        bool interior = boundary > 0.5 && boundary < lanes - 0.5;
        // The boundary the opposing traffic meets at is the centre line, not a divider —
        // otherwise a white dash is painted straight over the yellow. Half a lane in `t`
        // is `1 / lanes`, so exactly one boundary can match. Identifying the boundary is
        // the one thing done in `t`; its edges are still placed in pixels below.
        bool isCentre = !oneway && abs(boundaryT - centreT) < 1.0 / lanes;

        float dashed = 0.0;
        if (interior && !isCentre) {
            float on = DASH_ON_LANES * lanePx;
            float period = on + DASH_OFF_LANES * lanePx;
            float along = mod(inDistancePx, period);
            // Soft at both ends of the dash for the same reason the sides are soft: a hard
            // dash end is a sub-pixel edge that crawls when the map pans.
            dashed = clamp(min(along, on - along) + 0.5, 0.0, 1.0);
            dashed *= band(abs(lane - boundary) * lanePx, markHalfPx);
        }
        rgb = mix(rgb, MARK_WHITE, dashed * legible);

        // The edge lines sit flush inside the kerb, so their outer side lands on the rim
        // of the carriageway and the road reads as having a painted border rather than a
        // white line floating near one.
        float edgeCentrePx = max(halfWidthPx - markHalfPx, 0.0);
        float edgeLine = band(abs(abs(edgePx) - edgeCentrePx), markHalfPx);
        rgb = mix(rgb, MARK_WHITE, edgeLine * legible);

        // The centre line last, so it wins wherever it overlaps. A one-way carriageway has
        // no opposing traffic and so has no centre line at all — not a white one.
        if (!oneway) {
            float centreLine = band(abs(edgePx - centreT * halfWidthPx), markHalfPx);
            vec3 centreRgb = push.misc.z >= 0.5 ? MARK_YELLOW : MARK_WHITE;
            rgb = mix(rgb, centreRgb, centreLine * legible);
        }
    }

    outColor = vec4(rgb, push.color.a * coverage);
}
