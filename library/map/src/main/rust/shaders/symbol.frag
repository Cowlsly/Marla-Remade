#version 450

// Symbol fragment: SDF text with halo.
//
// The atlas texel is signed distance, 0.0 far outside to 1.0 deep inside, with
// the glyph edge at 0.5. `smoothstep` across a 1-texel band gives crisp edges at
// any size; the halo is the same edge test on a dilated threshold, drawn first
// in the halo color so the face overpaints it.

layout(location = 0) in vec2 inUv;

layout(location = 0) out vec4 outColor;

layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    // x: text size in device px per em, y: halo width in device px, z: SDF value
    // change across one em, w: unused.
    vec4 line;
    // x: tile span in px, yzw: halo color rgb.
    vec4 misc;
} push;
layout(set = 0, binding = 0) uniform sampler2D atlas;
void main() {
    // SDF is in `.r` by the upload contract (`glyph::expand_sdf_r8_to_rgba8`
    // writes [v, v, v, v]; pinned by
    // `the_rgba8_expansion_carries_sdf_in_every_channel`).
    float dist = texture(atlas, inUv).r;
    // Smooth across exactly one screen pixel of the distance field. `fwidth` is how
    // much `dist` changes between neighbouring fragments, so this tracks the label's
    // size for free. The previous constant band was one atlas texel of the 0..1 SDF
    // range - about 0.001 - which is far narrower than a pixel at any label size and
    // so drew hard, aliased edges.
    float aa = max(fwidth(dist), 1e-4);
    float face = smoothstep(0.5 - aa, 0.5 + aa, dist);
    // Halo: the edge test dilated outwards. A halo of `haloPx` screen px is
    // `haloPx / textPx` ems, and `line.z` is how much the SDF moves across an em.
    float haloSdf = push.line.y / max(push.line.x, 1.0) * push.line.z;
    float halo = smoothstep(0.5 - haloSdf - aa, 0.5 - haloSdf + aa, dist);
    vec3 haloRgb = push.misc.yzw;
    vec3 rgb = mix(haloRgb, push.color.rgb, face);
    float alpha = max(halo * push.color.a, face * push.color.a);
    if (alpha <= 0.001) discard;
    outColor = vec4(rgb, alpha);
}
