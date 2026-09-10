#version 450

// Building: extruded 3D walls and roof caps (WS-A, OSM Simple 3D Buildings).
//
// Unlike the position-only fill vertex, a building vertex carries a height, a face normal
// and its own colour — the three things flat 2D layers never need. See `tess::roof` for the
// layout the CPU packs.
//
// The incoming height is **tile-normalised** (a fraction of the tile's ground width, the
// same unit x/y are) so the mesh is zoom-independent like every other tile mesh. `line.x`
// carries the tile's world-px span for this frame; multiplying the two recovers the real
// world-px height the WS0 perspective matrix expects in its `z` input. At pitch 0 that matrix
// ignores z for x/y, so a building drawn from directly overhead collapses to its footprint.

layout(location = 0) in vec3 inPosition; // tile-local (u, v, height); u,v in 0..1
layout(location = 1) in vec3 inNormal;   // face normal in the same tile-local space
layout(location = 2) in vec4 inColor;    // per-vertex ARGB, R8G8B8A8_UNORM -> 0..1

layout(push_constant) uniform Push {
    // Tile-local (u, v, height, 1) to clip space — the WS0 matrix. `height` is world-px.
    mat4 tileToClip;
    vec4 color;
    // x: the tile's world-px span this frame, the tile-norm-height -> world-px scale (WS-A).
    // y, z, w: unused by the building path.
    vec4 line;
    vec4 misc;
    vec4 morph;
} push;

layout(location = 0) out vec3 vNormal;
layout(location = 1) out vec4 vColor;

void main() {
    float worldHeight = inPosition.z * push.line.x;
    // The height column's w term (tileToClip[2][3]) is 0 on the ortho fast-path (pitch 0) and
    // -cos(pitch) once tilted. That distinguishes the two, and they need different z inputs.
    if (abs(push.tileToClip[2][3]) < 1e-6) {
        // Pitch 0: the ortho matrix copies z straight to clip depth and never lets height touch
        // x/y, so the building already reads as its flat footprint. Feed the small tile-normalised
        // height (clamped into range) instead of the world-px one, which would fall outside the
        // [0, w] depth range and clip the whole building away.
        gl_Position = push.tileToClip * vec4(inPosition.xy, clamp(inPosition.z, 0.0, 0.99), 1.0);
    } else {
        // Tilted: the full perspective maps a real world-px height to both the projected rise and a
        // depth inside the near/far range, so buildings extrude and occlude correctly.
        gl_Position = push.tileToClip * vec4(inPosition.xy, worldHeight, 1.0);
    }
    vNormal = inNormal;
    vColor = inColor;
}
