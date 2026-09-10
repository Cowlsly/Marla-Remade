#version 450

// Billboard symbol vertex: point-label glyph quads that stay upright and pinned to their
// ground anchor under camera tilt. Text labels are tessellated per frame at the frame's text
// size (positions arrive tile-local and final), and each vertex also carries the label's
// ground anchor so this shader can, when tilted, project the anchor with perspective and hang
// the glyph off it at a constant screen offset.
//
// At pitch 0 the renderer clears the billboard flag (push.line.w) and this reduces to the plain
// `tileToClip * position` the flat map has always used — byte-identical output. A curved (line)
// label writes each glyph vertex as its own anchor (offset zero), so even with the flag set it
// projects straight onto the ground and stays map-aligned.

layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec2 inAnchor;
layout(location = 0) out vec2 outUv;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    // x: text size in px per em (halo SDF), y: halo width px, z: atlas sdf-per-em,
    // w: billboard flag (>0.5 = tilt billboard; 0 = flat, drawn straight through tileToClip).
    vec4 line;
    // x: tile span in px (unused here), yzw: halo color rgb.
    vec4 misc;
    // The pitch-0 tile matrix's linear 2x2 (column-major m0,m1,m4,m5), so the screen-constant
    // glyph offset can be reconstructed while the anchor goes through the perspective matrix.
    vec4 morph;
} push;
void main() {
    outUv = inUv;
    if (push.line.w > 0.5) {
        vec4 a = push.tileToClip * vec4(inAnchor, 0.0, 1.0);
        vec2 off = inPosition - inAnchor;
        // Column-major 2x2 * off: the same screen offset the ortho matrix would give this glyph,
        // scaled by the anchor's clip-w so the perspective divide leaves it screen-constant.
        vec2 offClip = vec2(push.morph.x * off.x + push.morph.z * off.y,
                            push.morph.y * off.x + push.morph.w * off.y);
        gl_Position = vec4(a.xy + offClip * a.w, a.z, a.w);
    } else {
        gl_Position = push.tileToClip * vec4(inPosition, 0.0, 1.0);
    }
}
