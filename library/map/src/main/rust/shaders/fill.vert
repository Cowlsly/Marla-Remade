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
    // Tile-local (u, v, height, 1) to clip space. `height` (the vertex z) is 0 for
    // every flat 2D layer and a real world-px height for buildings/terrain.
    mat4 tileToClip;
    vec4 color;
    // x: half stroke width in px, y: half the casing gap in px,
    // z: dash length, w: gap length (both in line widths).
    vec4 line;
    // x: the screen size of one tile in px, which is what converts a pixel width
    // into tile-local units. y: edge-AA flag. z: lane lateral offset px. w: the
    // per-frame clock in seconds (WS0), read by the animated line paths.
    vec4 misc;
    // x: per-tile opacity/morph factor (1.0 = fully present), reserved for WS-D's
    // LOD cross-fade. y, z, w reserved.
    vec4 morph;
} push;

void main() {
    gl_Position = push.tileToClip * vec4(inPosition, 0.0, 1.0);
}
