#version 450

// Fill: solid triangles from the earcut tessellator.
//
// Vertices carry position only, in tile-local 0..1. The tile's placement and the
// camera both live in the transform, and the layer's colour is a constant, so a
// fill vertex is 8 bytes.
//
// Everything per-draw arrives in a **push constant** block rather than a uniform
// buffer. 112 bytes is inside the 128 the spec guarantees, and it means the
// renderer needs no descriptor sets, no descriptor pool, and no per-tile uniform
// buffers to keep in sync with the camera.

layout(location = 0) in vec2 inPosition;

layout(push_constant) uniform Push {
    // Tile-local 0..1 to clip space.
    mat4 tileToClip;
    vec4 color;
    // x: half stroke width in px, y: half the casing gap in px,
    // z: dash length, w: gap length (both in line widths).
    vec4 line;
    // x: the screen size of one tile in px, which is what converts a pixel width
    // into tile-local units. y, z, w unused.
    vec4 misc;
} push;

void main() {
    gl_Position = push.tileToClip * vec4(inPosition, 0.0, 1.0);
}
