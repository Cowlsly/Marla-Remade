#version 450

// Line fragment, with the dash pattern.
//
// `dashOn`/`dashOff` are dash and gap lengths in **line widths**, as the style spec
// defines `line-dasharray`, converted to pixels here.
//
// The degenerate `[2, 0]` pattern has to be special-cased. `boundaries_country`
// produces it through a `step` expression — a zero-length gap means "solid" — and a
// naive `mod(distance, on + off) < on` renders it solid only by accident of `off`
// being exactly zero. Any drift in the period, or a driver that evaluates
// `mod(x, x)` as `x` rather than `0`, turns the whole border into stipple. So a
// non-positive gap short-circuits before any arithmetic happens.

layout(location = 0) in float inDistancePx;

layout(location = 0) out vec4 outColor;

layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
} push;

void main() {
    float halfWidthPx = push.line.x;
    float dashOn = push.line.z;
    float dashOff = push.line.w;

    // A non-positive gap is a solid line, checked first so [2, 0] never reaches the
    // modulo at all.
    if (dashOff <= 0.0) {
        outColor = push.color;
        return;
    }

    float width = max(halfWidthPx * 2.0, 1.0);
    float on = dashOn * width;
    float off = dashOff * width;
    float period = on + off;
    if (period <= 0.0) {
        outColor = push.color;
        return;
    }

    if (mod(inDistancePx, period) > on) discard;
    outColor = push.color;
}
