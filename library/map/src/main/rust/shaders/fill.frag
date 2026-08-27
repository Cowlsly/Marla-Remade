#version 450

// Fill fragment: the layer colour. Blending is the pipeline's job — src-alpha over
// one-minus-src-alpha — so the colour passes straight through.

layout(location = 0) out vec4 outColor;

layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
} push;

void main() {
    outColor = push.color;
}
