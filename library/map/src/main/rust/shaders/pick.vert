#version 450
// Id-buffer pick vertex: the shared unit quad, placed exactly like the puck/marker sprites
// through `screen_quad_to_clip` (billboarded, upright and screen-constant under tilt), so a
// marker's pick box lands where its icon is drawn. Position only — the pick pass writes an id,
// not a picture, so there is no UV.
layout(location = 0) in vec2 inLocal;
layout(push_constant) uniform Push {
    mat4 tileToClip;
} push;
void main() {
    gl_Position = push.tileToClip * vec4(inLocal, 0.0, 1.0);
}
