#version 450
// Id-buffer pick fragment: write this feature's 1-based pick slot to the R32_UINT target, so
// reading back the tapped pixel yields the slot (0 = nothing). The slot is carried as the bit
// pattern of a float in the push block's `color.x` — `floatBitsToUint` recovers the exact u32,
// which a plain float-to-uint cast would round. The whole quad writes the id: a marker's tap
// box is its rectangle, which is what a generous touch target wants.
layout(location = 0) out uint outId;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
} push;
void main() {
    outId = floatBitsToUint(push.color.x);
}
