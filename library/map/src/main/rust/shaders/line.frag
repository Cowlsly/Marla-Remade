#version 450
// Line fragment: edge coverage, then the dash pattern.
//
// `outEdgePx` is a signed pixel distance from the band's centreline, and `misc.y` says
// whether this pass has to antialias the edge itself. Under MSAA it does not: the
// rasteriser already resolves partial coverage, and a second coverage term on top fades
// a diagonal road twice - at z6 a 2.9px highway crossing at an angle kept one pixel of
// strength against three for the near-vertical stretches of the same road. Without MSAA
// the box filter is the only thing standing between a diagonal road and a staircase, so
// it stays for that case.
//
// The sub-pixel fade is separate and always applies: `line.vert` widens anything thinner
// than MIN_HALF_WIDTH_PX so the rasteriser can hit it, and the alpha here gives that width
// back. MSAA cannot do this - the geometry really is a pixel wide by then.
//
// `dashOn`/`dashOff` are dash and gap lengths in **line widths**, as the style spec
// defines `line-dasharray`, converted to pixels here.
//
// The degenerate `[2, 0]` pattern has to be special-cased. `boundaries_country`
// produces it through a `step` expression - a zero-length gap means "solid" - and a
// naive `mod(distance, on + off) < on` renders it solid only by accident of `off`
// being exactly zero. Any drift in the period, or a driver that evaluates
// `mod(x, x)` as `x` rather than `0`, turns the whole border into stipple. So a
// non-positive gap short-circuits before any arithmetic happens.
layout(location = 0) in float inDistancePx;
layout(location = 1) in float inEdgePx;
layout(location = 0) out vec4 outColor;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    // x: tile span in px, y: 1 when the pass is single-sampled and this shader owns
    // edge antialiasing, 0 when MSAA is resolving it.
    vec4 misc;
} push;

// Mirrors `style::paint::MIN_HALF_WIDTH_PX`; must match `line.vert`.
const float MIN_HALF_WIDTH_PX = 0.5;

void main() {
    float wantHalfPx = push.line.x;
    float halfWidthPx = max(wantHalfPx, MIN_HALF_WIDTH_PX);
    // Hand back the width the vertex shader added to make the quad rasterisable, so
    // a 0.3px road draws as a faint pixel rather than a solid one.
    float coverage = clamp(wantHalfPx / halfWidthPx, 0.0, 1.0);
    // Box filter over the pixel, only when nothing else is doing it.
    float box = clamp(halfWidthPx - abs(inEdgePx) + 0.5, 0.0, 1.0);
    coverage *= mix(1.0, box, push.misc.y);
    if (coverage <= 0.0) discard;

    float dashOn = push.line.z;
    float dashOff = push.line.w;
    // A non-positive gap is a solid line, checked first so [2, 0] never reaches the
    // modulo at all.
    if (dashOff > 0.0) {
        float width = max(halfWidthPx * 2.0, 1.0);
        float on = dashOn * width;
        float off = dashOff * width;
        float period = on + off;
        if (period > 0.0 && mod(inDistancePx, period) > on) discard;
    }
    outColor = vec4(push.color.rgb, push.color.a * coverage);
}
