#version 450

// Terrain fragment: the ground colour, slope-shaded (WS-G, 3D terrain relief).
//
// The base colour is the `earth` style layer's, pushed per draw (linear RGBA, palette- and
// opacity-resolved by the renderer). The vertex stage has already reduced the surface normal to a
// single scalar shade — full (1.0) at pitch 0, so the ground reads as the flat map's one colour,
// and a diffuse term once tilted so hills show relief. Blending is the pipeline's job, so the
// colour passes through with its own alpha.

layout(location = 0) in float vShade;

layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color; // the earth layer's colour, linear RGBA
    vec4 line;
    vec4 misc;
    vec4 morph;
} push;

layout(location = 0) out vec4 outColor;

void main() {
    outColor = vec4(push.color.rgb * vShade, push.color.a);
}
