// Shared declarations for every compute shader in this runtime.
//
// Not a shader itself: `build.rs` compiles only `.vert`/`.frag`/`.comp`, so this is
// pulled in by `#include` and never compiled alone. glslc resolves the path relative
// to the including file.
//
// # Two bindings, and offsets in push constants
//
// There are exactly two storage buffers for the whole runtime:
//
//   binding 0  the activation arena, read and written
//   binding 1  the weights, read only
//
// Every tensor is a *region* of one of those, addressed by an element offset in the
// push-constant block. So the descriptor set is written once at setup and never
// rebound, there are no uniform buffers, no dynamic offsets and nothing to keep in
// sync per layer — `vkCmdPushConstants` before each dispatch is the entire per-layer
// state. See `nets::Push`, which this block must match field for field.
//
// # fp16 storage, fp32 arithmetic
//
// Weights and activations are fp16; every accumulator here is `float`. Half the
// bandwidth and half the memory, without half's 11-bit mantissa accumulating error
// down a 119-layer net.
//
// `shaderFloat16` and `storageBuffer16BitAccess` are both required at device creation.
// `VK_KHR_shader_float16_int8` is a Vulkan 1.2 promotion, so at the 1.1 floor minSdk 31
// guarantees it is an extension that may be absent — see `vulkan::context`, which
// refuses to create a device without it rather than silently producing fp32 code.

#extension GL_EXT_shader_16bit_storage : require
#extension GL_EXT_shader_explicit_arithmetic_types_float16 : require

// 64 invocations per workgroup: a multiple of every wave width in use (16 on some
// Mali, 32 on Adreno, 64 on older GCN-derived parts) so no wave is ever half idle.
layout(local_size_x = 64) in;

layout(std430, binding = 0) buffer Arena {
    float16_t arena[];
};

layout(std430, binding = 1) readonly buffer Weights {
    float16_t weights[];
};

layout(push_constant) uniform Push {
    uint in0;
    uint in1;
    uint out_at;
    uint weight;
    uint bias;
    uint in_c;
    uint in_h;
    uint in_w;
    uint out_c;
    uint out_h;
    uint out_w;
    uint kh;
    uint kw;
    uint stride_h;
    uint stride_w;
    uint dil_h;
    uint dil_w;
    uint pad_t;
    uint pad_l;
    uint group;
    uint act;
    uint act_weight;
    uint count;
} p;

// `nets::Act`.
#define ACT_NONE 0u
#define ACT_RELU 1u
#define ACT_HARDSWISH 2u
#define ACT_SIGMOID 3u
#define ACT_PRELU 4u

// This invocation's output element, across a 2D grid of workgroups.
//
// Not `gl_GlobalInvocationID.x`: U^2-Netp's largest layer writes 64x320x320 = 6,553,600
// elements, which at 64 per workgroup is 102,400 workgroups, and `maxComputeWorkGroupCount`
// is only guaranteed to be **65,535** per dimension. A 1D dispatch that big is rejected
// outright on any device at the guaranteed minimum, so `vulkan::run` splits the count
// across x and y and this flattens it back. `gl_NumWorkGroups` is the actual dispatch
// extent, so the two cannot disagree.
//
// The grid over-covers, so every shader here tests the result against `p.count`.
uint global_index() {
    uint group = gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x;
    return group * gl_WorkGroupSize.x + gl_LocalInvocationID.x;
}

// The activation fused into a layer's store. Every ReLU, HardSwish, Sigmoid and PReLU in
// every network here directly follows a convolution, so this is the only place
// activations happen and there is no standalone activation pass.
//
// `channel` is the output channel, needed only by PReLU, whose slope is one value per
// channel at `p.act_weight` in the weights buffer. Passing it unconditionally costs a
// register and keeps one signature.
float activate(float x, uint kind, uint channel) {
    if (kind == ACT_RELU) {
        return max(x, 0.0);
    }
    if (kind == ACT_HARDSWISH) {
        // ONNX HardSwish at its default alpha = 1/6, beta = 0.5.
        return x * clamp(x * (1.0 / 6.0) + 0.5, 0.0, 1.0);
    }
    if (kind == ACT_SIGMOID) {
        return 1.0 / (1.0 + exp(-x));
    }
    if (kind == ACT_PRELU) {
        // ONNX PRelu. The branch is on the sign of the accumulator, so it diverges
        // within a wave; a `mix` on the comparison would too, and this reads as the
        // definition does.
        return x < 0.0 ? x * float(weights[p.act_weight + channel]) : x;
    }
    return x;
}

// Unpack a flat output index into channel, row and column. Every shader here runs one
// invocation per output element, in NCHW order, so this is the first thing each does.
void unpack(uint index, out uint c, out uint y, out uint x) {
    x = index % p.out_w;
    uint rest = index / p.out_w;
    y = rest % p.out_h;
    c = rest / p.out_h;
}
