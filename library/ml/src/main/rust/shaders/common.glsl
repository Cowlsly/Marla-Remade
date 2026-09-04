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

// Values that change per step without the command buffer being re-recorded.
//
// Push constants below are baked into the recording, so anything that varies between two submits
// of the *same* recording has to be read from memory. A decode step's key count is the case that
// matters: it grows by one every token, and re-recording for it cost a `device_wait_idle` and a
// full re-emit per token.
//
// Mirrors `StepParams` in `src/vulkan/run.rs` field for field. `std430` packs a struct of `uint`s
// with no padding, so neither side has to restate an alignment rule.
layout(std430, binding = 3) readonly buffer Params {
    // Cache positions already written; a cached-attention op attends over `prefix + 1` keys.
    uint prefix;
    // First position a sliding window may attend to. Zero attends from the start.
    uint window_start;
} step_params;

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
    uint pad_edge;
    uint group;
    uint act;
    uint act_weight;
    uint param0_bits;
    uint param1_bits;
    uint count;
    // Non-zero when the key range comes from `step_params` rather than from `in_w`/`out_w`,
    // which then carry only the stride. See `Push::dyn_keys` in `nets/mod.rs`.
    uint dyn_keys;
    // Key/value heads, when fewer than `group`. Zero means "as many as `group`", which is
    // ordinary multi-head attention. See `kv_head_of`.
    uint kv_heads;
} p;

// Keys a cached-attention op attends over, as an inclusive `[first, last]` range.
//
// A plain decoder attends `0 ..= prefix`. A sliding-window layer attends
// `window_start ..= prefix`, which is why this is a range and not a count: Gemma 4 alternates
// four sliding layers to one global one, and the sliding ones must not see the whole cache.
//
// When `p.dyn_keys` is zero the op was built at a fixed length and the range is the whole row,
// which is every net that does not decode.
uint attn_first() {
    return p.dyn_keys != 0u ? step_params.window_start : 0u;
}

uint attn_last(uint stride) {
    return p.dyn_keys != 0u ? min(step_params.prefix, stride - 1u) : stride - 1u;
}

// The KV head a query head reads from.
//
// `p.group` query heads share `p.kv_heads` key/value heads, so head `h` reads KV head
// `h / (group / kv_heads)`. Gemma 4's text decoder is the extreme case at one KV head for eight
// query heads; multi-head attention is the other, where the two counts are equal and this is the
// identity. Zero `kv_heads` means "same as `group`", so every net built before this existed is
// unaffected.
uint kv_head_of(uint head) {
    uint kv = p.kv_heads == 0u ? p.group : p.kv_heads;
    return head / max(p.group / max(kv, 1u), 1u);
}

// Channels one position of a KV cache occupies: `kv_heads * head_dim`, not `group * head_dim`.
uint kv_stride(uint head_dim) {
    uint kv = p.kv_heads == 0u ? p.group : p.kv_heads;
    return kv * head_dim;
}

// `nets::Act`.
#define ACT_NONE 0u
#define ACT_RELU 1u
#define ACT_HARDSWISH 2u
#define ACT_SIGMOID 3u
#define ACT_PRELU 4u
#define ACT_CLIP01 5u
#define ACT_SWISH 6u
#define ACT_GELU 8u

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
/// One signed 4-bit value of an int4 tensor, by element index.
///
/// Eight nibbles to a 32-bit word, low nibble first, so element `i` is at bit `4 * (i % 8)` of
/// word `i / 8`. `base` is the tensor's start as a 32-bit word index, as for `int8_at`.
///
/// `bitfieldExtract` on a **signed** int sign-extends, which is what makes the stored range
/// -8..7 rather than 0..15. Reading it unsigned and subtracting would be a different
/// quantisation and would silently bias every weight.
int int4_at(uint base, uint index) {
    uint word = weights32[base + (index >> 3u)];
    return bitfieldExtract(int(word), int((index & 7u) << 2u), 4);
}

/// Taps one int4 scale covers. Mirrors `weights::I4_BLOCK`.
#define I4_BLOCK 32u

/// The per-block scale for output `channel`, block `block`, of a rank-2 `(out, blocks)` table.
float int4_scale(uint channel, uint block, uint blocks) {
    return float(weights[p.act_weight + channel * blocks + block]);
}

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
    if (kind == ACT_GELU) {
        // The exact GELU, `0.5 x (1 + erf(x / sqrt(2)))`, which is what Supertonic's four nets
        // spell as an `Erf`.
        //
        // The tanh approximation would also have done: the two forms differ by at most 4.7e-4,
        // and fp16's step there is 2.0e-3. `erf` is used because it matches the export and
        // because `post::duration::erf` is the same Abramowitz and Stegun 7.1.26 series with the
        // same coefficients, so the host reference and this are one function rather than two
        // that happen to agree.
        float sign = x < 0.0 ? -1.0 : 1.0;
        float a = abs(x) * 0.70710678;
        float t = 1.0 / (1.0 + 0.3275911 * a);
        float poly = t * (0.254829592
            + t * (-0.284496736
            + t * (1.421413741
            + t * (-1.453152027
            + t * 1.061405429))));
        float erf = sign * (1.0 - poly * exp(-a * a));
        return 0.5 * x * (1.0 + erf);
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
