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
    // x: text size in px per em, y: halo width in px, z/w: atlas texel (1/W,1/H).
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
    // One-texel smoothing in SDF units: the atlas spread is 8px per cell side,
    // i.e. 8/64 = 0.125 of the 0..1 range, so a texel is ~1/(spread) of that.
    // fwidth would be ideal; constant 1-texel smoothing is the M1 approximation.
    float texel = max(push.line.z, push.line.w);
    float edge0 = 0.5 - texel;
    float edge1 = 0.5 + texel;
    float face = smoothstep(edge0, edge1, dist);

    // Halo: edge test dilated by halo px converted to SDF units. Halo width is
    // in screen px; one em is push.line.x px and spans 8 SDF-px of spread per
    // side... M1 approximates: haloSdf = haloPx / textPx * 0.5 (half the em
    // range maps the full SDF range). Both line.x (text size) and line.y
    // (halo width) are device px now that the density fix scales the size —
    // the ratio is unit-clean either way.
    float haloSdf = push.line.y / max(push.line.x, 1.0) * 0.5;
    float halo = smoothstep(0.5 - haloSdf - texel, 0.5 - haloSdf + texel, dist);

    vec3 haloRgb = push.misc.yzw;
    vec3 rgb = mix(haloRgb, push.color.rgb, face);
    float alpha = max(halo * push.color.a, face * push.color.a);
    if (alpha <= 0.001) discard;
    outColor = vec4(rgb, alpha);
}
