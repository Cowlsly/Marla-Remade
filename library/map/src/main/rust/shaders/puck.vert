#version 450
// User puck: one screen-anchored quad, drawn analytically by `puck.frag`.
//
// The vertex data is a fixed unit square in -1..1, uploaded once at startup. Where the
// puck sits and how big it is both live in the matrix, which for an overlay maps -1..1
// to a quad of a fixed Dp radius centred on a lon/lat rather than to a tile
// (`Camera::screen_quad_to_clip`). So a pan, a zoom and a new fix are all a push
// constant, and no buffer is touched after startup.
layout(location = 0) in vec2 inLocal;
layout(location = 0) out vec2 outLocal;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
} push;
void main() {
    outLocal = inLocal;
    gl_Position = push.tileToClip * vec4(inLocal, 0.0, 1.0);
}
