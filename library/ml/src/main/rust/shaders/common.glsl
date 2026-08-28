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
// The same buffer as binding 1, viewed as 32-bit words.
//
// Int8 weights are unpacked from here four at a time. A byte view would be tidier but needs
// `VK_KHR_8bit_storage`, an extension on top of the fp16 one this runtime already requires,
// and every extra requirement narrows the devices that can run at all. 32-bit storage is
// universal.
//
// Aliasing one buffer through two descriptors is allowed, and both are read-only, so there is
// no write hazard between the views.
layout(std430, binding = 2) readonly buffer Weights32 {
    uint weights32[];
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
    uint param0_bits;
    uint param1_bits;
    uint count;
} p;

// `nets::Act`.
#define ACT_NONE 0u
#define ACT_RELU 1u
#define ACT_HARDSWISH 2u
#define ACT_SIGMOID 3u
#define ACT_PRELU 4u
#define ACT_CLIP01 5u
#define ACT_SWISH 6u
#define ACT_TANH 7u

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
/// One signed byte of an int8 tensor, by element index.
///
/// `base` is the tensor's start as a 32-bit word index; `index` is the element within it.
/// `bitfieldExtract` on a *signed* integer sign-extends, which is what turns the byte back into
/// -128..127 rather than 0..255 — reading it unsigned would make every negative weight large
/// and positive, which trains-looking output would not reveal.
int int8_at(uint base, uint index) {
    uint word = weights32[base + (index >> 2u)];
    return bitfieldExtract(int(word), int((index & 3u) << 3u), 8);
}

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
    if (kind == ACT_CLIP01) {
        // A normalised ONNX HardSigmoid: its alpha and beta were folded into this
        // layer's weight and bias by `scripts/ml/ppocr_fold.py`, so what is left is the
        // clamp. See `nets::Act::Clip01`.
        return clamp(x, 0.0, 1.0);
    }
    if (kind == ACT_SWISH) {
        // `x * sigmoid(x)`. Not HardSwish's piecewise approximation — the recogniser uses
        // both, and substituting one for the other is a plausible-looking accuracy loss.
        return x / (1.0 + exp(-x));
    }
    if (kind == ACT_TANH) {
        // The last thing Piper's vocoder does, which is what bounds the waveform to
        // -1..1. GLSL's `tanh` is a built-in; writing it as two exponentials would
        // overflow fp32 for the large inputs a badly-conditioned voice can produce.
        return tanh(x);
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
