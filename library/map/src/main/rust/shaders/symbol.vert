#version 450

// Symbol vertex: label quads are tessellated per frame at the frame's text size,
// so positions arrive tile-local and final — no extrusion math. The only job is
// the tile-to-clip transform plus UV passthrough.

layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inUv;
layout(location = 0) out vec2 outUv;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    // x: text size in px per em (unused here - baked at tessellation), y: halo
    // width in px, z/w: atlas texel size (1/W,1/H) for edge smoothing.
    vec4 line;
    // x: tile span in px (unused here), yzw: halo color rgb.
    vec4 misc;
} push;
void main() {
    outUv = inUv;
    gl_Position = push.tileToClip * vec4(inPosition, 0.0, 1.0);
}
