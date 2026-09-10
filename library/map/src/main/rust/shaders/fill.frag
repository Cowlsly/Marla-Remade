#version 450

// Fill fragment: the layer colour. Blending is the pipeline's job — src-alpha over
// one-minus-src-alpha — so the colour passes straight through, scaled by the per-tile
// LOD cross-fade opacity (WS-D) so a finer tile ramps in over its coarse ancestor.

layout(location = 0) out vec4 outColor;

layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
    // x: per-tile opacity/morph factor (1.0 = fully present) for WS-D's LOD cross-fade.
    vec4 morph;
} push;

void main() {
    outColor = vec4(push.color.rgb, push.color.a * push.morph.x);
}
