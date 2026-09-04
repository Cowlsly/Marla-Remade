#!/usr/bin/env python3
"""ONNX to `.maml`, the weights container `:library:ml` reads.

`:library:ml` is not a graph interpreter: each network's forward pass is hardcoded
Rust in `library/ml/src/main/rust/src/nets/`. So a `.maml` file carries **only
ordered tensors** — no operators, no topology, no names. The Rust net module
indexes the tensor table positionally.

That makes tensor *order* the entire contract between this script and the Rust,
and a reordering would be silently wrong rather than a load failure. So the order
is defined mechanically and pinned:

* Order is the weighted nodes in ONNX **topological order** — `Conv`/`ConvTranspose`
  weight then bias, `PRelu` slope, `Gemm` weight then bias. Nothing else is emitted:
  every other initializer in these graphs is shape-computation scaffolding that folds
  away at a fixed input size.

  Topological rather than initializer order matters for the nets with `PRelu`: the Rust
  reads a layer as `weight = i, bias = i + 1, slope = i + 2`, which only lines up if a
  slope is emitted immediately after the convolution it follows.
* `--check` hashes the whole ordered layer table (shapes *and* the attributes the
  Rust hardcodes: dilation, pads, strides, group) into one SHA-256 pinned below.
  Any upstream re-export that renames, reorders, retunes or re-pads a single layer
  changes that hash and fails, instead of producing a file that loads and infers
  nonsense.

Two nodes are rewritten rather than emitted verbatim, because doing it here means the
runtime needs no shader for either:

* A **`Gemm`** over a flattened NCHW map is a convolution whose kernel covers the whole
  spatial extent. MobileFaceNet's `[512, 3136]` becomes `[512, 64, 7, 7]` and the
  existing `conv.comp` computes it, so there is no `InnerProduct` pipeline.
* A trailing **`BatchNormalization`** is folded into the `Gemm` before it. At inference a
  batch norm is a per-channel affine, so `W' = W * gamma / sqrt(var + eps)` and
  `b' = (b - mean) * gamma / sqrt(var + eps) + beta` is exact, not an approximation.

Weights are stored fp16 with fp32 accumulation in-shader (see `SPEC` below for the
byte layout). ONNX tensor layout is preserved verbatim — `[M, C/group, kH, kW]` for
`Conv`, `[C, M/group, kH, kW]` for `ConvTranspose` — so nothing here has to agree
with the shaders about a transpose.

One graph does not come from an ONNX export at all. SMaLL-100 is read from its PyTorch
`.safetensors` and quantised to int8 here, because the only ONNX exports that exist for it are
already int8 and quantised per *tensor*; see [`CHECKPOINTS`] for why that is not usable.

One graph is an ONNX export whose tensor table is nevertheless **derived from architecture
constants and read by name**, rather than walked topologically. TinyCLIP is two towers in one
graph, interleaved, and its projections are anonymous `onnx::MatMul_NNNN` initializers, so a
topological walk would emit an order no one could read. See [`ARCHITECTURES`].

    ./scripts/ml/maml_convert.py --check model.onnx --graph u2netp
    ./scripts/ml/maml_convert.py model.onnx --graph u2netp -o out.maml
    ./scripts/ml/maml_convert.py model.onnx --graph u2netp --print-layers
    ./scripts/ml/maml_convert.py model.safetensors --graph small100 -o out.maml
"""

import argparse
import hashlib
import math
import os
import struct
import sys

import numpy as np
import onnx
from onnx import numpy_helper, shape_inference

# --- .maml ---------------------------------------------------------------------
#
# One contiguous blob, so the whole file becomes one VkBuffer and one staging
# upload. Little-endian throughout; every device we target is.
#
#   header, 64 bytes
#     0   4   magic b"MAML"
#     4   4   u32 format version
#     8   4   u32 graph id — the runtime rejects a file built for another net
#    12   4   u32 tensor count
#    16  32   source SHA-256 of the ONNX or checkpoint, so a shipped asset traces upstream
#    48   4   u32 offset of the data section
#    52   4   u32 length of the data section
#    56   8   reserved, zero
#
#   tensor table, 32 bytes per entry, immediately after the header
#     0   4   u32 rank, 1..4
#     4  16   u32[4] dims, unused trailing entries zero
#    20   4   u32 dtype, 0 = fp16
#    24   4   u32 byte offset within the data section
#    28   4   u32 element count
#
#   data, fp16, each tensor padded to ALIGNMENT
MAGIC = b"MAML"
FORMAT_VERSION = 1
HEADER_BYTES = 64
TENSOR_ENTRY_BYTES = 32
# Tensor payload types. The container carries the type per tensor, so fp16 and int8 can sit in
# one file and a reader knows the stride without being told.
DTYPE_F16 = 0

# Signed 8-bit, symmetric, with a per-output-channel scale in the fp16 tensor that follows it.
#
# Only for a network where the weights dominate the download: SMaLL-100 is 330 million
# parameters, 660 MB at fp16 against 330 MB here, and the ncnn build of the same model shipped
# 1.14 GB. Everything else stays fp16, where the saving would not pay for the loss.
DTYPE_I8 = 1
# 16 bytes is the largest scalar/vector alignment the shaders index with, and a
# multiple of 2, so an aligned tensor offset is also an aligned fp16 index.
ALIGNMENT = 16

SPEC = __doc__

# The floor for `cosine(fp32 weight, int8 * scale)` per tensor. Supertonic's text encoder was
# reverted to fp16 at 0.99212 while its sampler shipped at 0.99997, so this is set where a layer
# that quantises this badly is worth looking at rather than shipping.
MIN_INT8_COSINE = 0.999

# Graphs the generic ONNX collector reads as int8. See [`int8_eligible`] for what that
# excludes within a graph, and `scripts/ml/maml_survey.py` for how the list was chosen.
#
# Roughly halves each of these: 6.5 -> 3.3 MiB for mobilefacenet, 3.8 -> 1.9 for ppocr_rec,
# 2.2 -> 1.2 for ppocr_det and u2netp, 1.2 -> 0.7 for scrfd.
#
# `selfie` is deliberately absent. It is a 211 KB depthwise net: a `[C, 1, 3, 3]` kernel saves
# nine bytes per channel and spends two on the scale, so there is nothing to win and it would
# be one more graph to re-verify.
INT8_GRAPHS = set()

# Graph ids. Shared with `library/ml/src/main/rust/src/weights.rs`; changing one
# without the other is what the id exists to catch.
GRAPHS = {
    "selfie": 1,
    "u2netp": 2,
    "scrfd": 3,
    "mobilefacenet": 4,
    # Folded ahead of time by `scripts/ml/ppocr_fold.py`, not by this script.
    "ppocr_det": 5,
    "ppocr_rec": 6,
    # 7..10 were Piper's four graphs — vocoder, encoder, flow and duration predictor —
    # deleted when Supertonic replaced them. The numbers are **not** reused: an id identifies
    # a forward pass, and a `.maml` built for the old vocoder must be rejected rather than
    # loaded as whatever took its slot.
    "supertonic_voc": 11,
    "supertonic_dp": 12,
    "supertonic_ttl": 13,
    "supertonic_ve": 14,
    # Read from a PyTorch checkpoint rather than an ONNX graph; see [`CHECKPOINTS`].
    "small100": 15,
    # An ONNX graph read by name rather than walked; see [`ARCHITECTURES`].
    "tinyclip": 16,
    # Read from a PyTorch checkpoint rather than an ONNX graph; see [`CHECKPOINTS`].
    "whisper": 17,
    # NLLB-200-distilled-600M, replacing small100; see [`CHECKPOINTS`].
    # Agreed with rust-eng/app-eng in team chat (nllb-translate): next free id,
    # 7..10 stay retired.
    "nllb600": 18,
    # Maia3-5M human-move prediction, replacing Stockfish in `:games:chess`; see
    # [`CHECKPOINTS`]. Next free id after NLLB's 18, with 7..10 and 15 staying retired.
    "maia": 19,
}

# SHA-256 over the ordered layer table (see `layer_table_digest`). Regenerate with
# --print-digest after deliberately re-pointing at a new upstream export.
EXPECTED_DIGEST = {
    "selfie": "6db422ecfe0af8e37591eb14c195166b2c416649bf9063100bd5bb14eafaad3a",
    "u2netp": "f5199e05e4d4e93a6af601d04e3e0516bb68b9f292bb81d474704ea0b4e591d0",
    "scrfd": "d2ce2880a432af03b0ef594029d3ab03d86de54bd0a9f10ea2c8e412309b0f46",
    "mobilefacenet": "40987839aec03378958581deba29ce9fd9dd1ae9aa88d0c7ad04a885a2e2122c",
    "ppocr_det": "51b0c9ed871526fa270b4bfa53b393c056db21c58193849fb83d44768f69ef9b",
    "ppocr_rec": "8c16a8702aa7a1e7b4d94787d089616ca4bc3d8aaeebb0b0159eabc56af3ba08",
    "supertonic_voc": "5282083898554b1fae28de946bfc1a82a11e2acddf3911877f27b1b115c583aa",
    "supertonic_dp": "d9a548e1f1ed908a3b0ad27ab543dd7521858f9110b426edb1ed64ba1f1a13e4",
    "supertonic_ttl": "92d0fa8ae73d4a8a223c77fb4230e390df580744014a3fcf3211ab72a988c020",
    "supertonic_ve": "48ab5789e8839507ff93e099dc0f61940f25da9ba6201651784f6e68598199e2",
    "small100": "c9f05f2fb686aa53137ca06b521baf6d7a00f32cfb4ea42bd14a5c3e7cf6c086",
    "tinyclip": "7557b2725b52ea04bd18b12e089320b9dd419cd3f7344c0e3d32888d61c24fa7",
    "whisper": "9e2830c5ea5d443fc31eb5c4eb80fa9a3b9bbcccd3c07963256ade50963c72a2",
    # Filled in by running --print-digest after the first successful conversion;
    # see fetch_nllb600.py. Placeholder until then (check fails loudly, not silently).
    "nllb600": "8b5dd248896b11bf1bb0feae6818d4b00b8ad176549df48bdc07f7b970d3480a",
    # Pinned with --print-digest after the first successful conversion; see fetch_maia.py.
    "maia": "50da3ca5ebb9a1b54338c43b2a4b798df25279ac6f74fa19b64146b3964b61aa",
}

# Graphs that are one **module** of a larger export, keyed by the node-name prefix that
# selects it.
#
# Empty at present: every graph here is exported as its own file. The mechanism stays because
# an export that packs several forward passes into one graph is not convertible as one plan,
# and a Torch export keeps its module paths - so it partitions along the lines the model was
# written in, and each static module converts independently.
#
# A module-scoped graph has no GEOMETRY entry: its input is another module's output rather
# than a graph input, so there is nothing at the boundary to check a shape against. What is
# checked instead is the op inventory within the module, which is the same guard by another
# route.
MODULES = {}

# `outputs` are the graph outputs the Rust forward pass corresponds to; the export may
# declare more (U^2-Netp declares all seven of its side outputs and we read one).
GEOMETRY = {
    "selfie": {
        "input": "pixel_values",
        "outputs": ["alphas"],
        "shape": [3, 256, 256],
    },
    "u2netp": {
        "input": "input.1",
        "outputs": ["1959"],
        "shape": [3, 320, 320],
    },
    # Nine: score, box and keypoint maps at strides 8, 16 and 32. The Rust reads the
    # convolution outputs feeding these rather than the reshaped tensors themselves,
    # because a Transpose/Reshape is a relabelling that NCHW indexing does for free.
    "scrfd": {
        "input": "input.1",
        "outputs": [
            "443", "468", "493",
            "446", "471", "496",
            "449", "474", "499",
        ],
        "shape": [3, None, None],
    },
    "mobilefacenet": {
        "input": "input.1",
        "outputs": ["516"],
        "shape": [3, 112, 112],
    },
}

# Op-type counts, as a coarse first check that the graph is the one we lowered by
# hand. Ops absent from the list must be absent from the graph.
EXPECTED_OPS = {
    "selfie": {
        "Conv": 54, "ConvTranspose": 1, "Add": 14, "Mul": 10, "Concat": 3,
        "ReduceMean": 10, "Relu": 22, "HardSwish": 11, "Sigmoid": 11,
        "Resize": 3, "Shape": 3, "Slice": 3,
    },
    "u2netp": {
        "Conv": 119, "MaxPool": 33, "Resize": 38, "Concat": 127, "Add": 11,
        "Relu": 112, "Sigmoid": 7, "Shape": 114, "Gather": 76, "Unsqueeze": 76,
        "Slice": 38, "Cast": 38, "Constant": 266,
    },
    "scrfd": {
        "Conv": 60, "Relu": 41, "Sigmoid": 3, "Add": 4, "Resize": 2, "Concat": 2,
        "Reshape": 9, "Transpose": 9, "Shape": 6, "Gather": 4, "Unsqueeze": 4,
        "Slice": 2,
    },
    "mobilefacenet": {
        "Conv": 49, "PRelu": 34, "Add": 12, "BatchNormalization": 1, "Flatten": 1,
        "Gemm": 1,
    },
    # Two towers in one graph. `MatMul` 107 is the 80 weighted projections plus the 26 attention
    # products and the one `logit_scale` product; `Erf` 13 is the 13 layers' GELU; `Softmax` 13 is
    # their attention, and the causal mask the text tower's three need is a `-3.4e38` constant
    # added to the score map, not an op. `ArgMax` 1 is the end-of-text search this runtime does on
    # the host. Counted rather than transcribed: `python -c` over the graph produced these.
    "tinyclip": {
        "Add": 186, "ArgMax": 1, "Cast": 5, "Concat": 27, "ConstantOfShape": 1, "Conv": 1,
        "Div": 44, "Equal": 4, "Erf": 13, "Expand": 3, "Flatten": 1, "Gather": 20, "Less": 1,
        "MatMul": 107, "Mul": 73, "Pow": 33, "Range": 2, "ReduceMean": 58, "ReduceSum": 2,
        "Reshape": 113, "Shape": 12, "Slice": 3, "Softmax": 13, "Sqrt": 29, "Squeeze": 1,
        "Sub": 31, "Transpose": 68, "Unsqueeze": 20, "Where": 5,
    },
}

# ONNX graphs whose tensor table is **derived from these constants and read by name**, rather than
# produced by the topological walk in [`collect_layers`].
#
# One entry, TinyCLIP. Three things make the walk unusable for it:
#
# * It is **two towers in one graph**, and the export interleaves them - text layer 0, vision layer
#   0, text layer 1, and so on. A topological order is therefore an order no reader could check
#   against `nets::tinyclip`, and `Mode::Image` and `Mode::Text` would each read a stride through
#   the table.
# * Every projection is a bare `MatMul` against an **anonymous** `onnx::MatMul_NNNN` initializer.
#   There is nothing in the initializer's name to say which layer or which projection it is. The
#   *node* names are fully structured (`/vision_model/encoder/layers.3/mlp/fc1/MatMul`), so this
#   reads the weight through the node, which is also what makes the inventory check below possible.
# * `MatMul` is `x @ W` with a **`[in, out]`** weight, and this runtime indexes a kernel as
#   `[out, in, kh, kw]`. Every attention projection here is square 256 x 256, so reading one
#   convention as the other yields a tensor of exactly the right shape holding exactly the wrong
#   numbers. `fc1` is `[256, 1024]` and `fc2` is `[1024, 256]`, so the shape assertions in
#   [`check_architecture`] are what make that loud.
#
# The `Conv` is the one exception and needs **no** transpose: ONNX `Conv` is already
# `[M, C, kH, kW]`, which is this runtime's own layout.
#
# What is checked is the **exact named-parameter inventory** derived from these constants, as
# [`CHECKPOINTS`] gets, *plus* the op counts in [`EXPECTED_OPS`] that only an ONNX graph can give.
# There is no [`GEOMETRY`] entry: this export has three inputs and four outputs and is dynamically
# shaped throughout, so there is no single boundary shape to compare against.
ARCHITECTURES = {
    "tinyclip": {
        # `hidden_size`, shared by both towers, and `num_attention_heads` 4, so `head_dim` is 64
        # and `Builder::attn_scores`'s own `1 / sqrt(64)` is already the model's 0.125 scale.
        "width": 256,
        "heads": 4,
        "ffn": 1024,
        "vision_layers": 10,
        "text_layers": 3,
        # 16 x 16 patches over 224 x 224 is a 14 x 14 grid, so 196 patches plus the class token.
        "patch": 16,
        "grid": 14,
        "vocab": 49_408,
        "context": 77,
        "projection": 512,
    },
}

# Graphs read from a **PyTorch checkpoint** rather than an ONNX graph, keyed by graph id name.
#
# Every other net here converts from a frozen ONNX export, and for good reason: the graph records
# the shapes and the op inventory, so `check_graph` can refuse an export whose structure moved
# without anyone transcribing anything. A checkpoint has neither. It is used anyway for SMaLL-100,
# because the only ONNX exports that exist for it are already int8:
#
# * `casawolice/small100-onnx` quantises **per tensor**, not per output channel. Its embedding's
#   row absmax spans 0.258 to 1.740, so one scale for the whole table costs a factor of four in
#   resolution against the per-row scale `quantise_per_channel` produces, and up to 6.7x on the
#   quietest rows. That resolution cannot be recovered by requantising: it is already gone.
# * It carries the 131M-parameter embedding **three times** - `embed_tokens.weight` in the
#   encoder, `model.shared.weight` in the decoder and a separately quantised `[1024, 128112]`
#   `lm_head` - which is why that deployment set is 609 MB rather than 333 MB. In the checkpoint
#   the weight is tied and appears exactly once, so the tied head costs nothing.
# * Its two embedding copies are **uint8 with a zero point of 159**. `conv_point_int8.comp`
#   computes `scale[o] * sum(int8_w * in)` and has nowhere to put a zero point.
#
# What is checked instead is the **exact parameter inventory** - every name and every shape,
# derived from the constants below rather than listed - which is a stricter guard than the op
# counts an ONNX graph gets, because it fails on a renamed or resized parameter as well as on a
# missing one. The layer table digest still covers the order.
CHECKPOINTS = {
    "small100": {
        "d_model": 1024,
        "encoder_layers": 12,
        "decoder_layers": 3,
        "ffn": 4096,
        "vocab": 128_112,
        # The tied embedding is 125.1 MiB of int8, and `maxStorageBufferRange`'s guaranteed
        # minimum is 128 MiB. Emitting it as two tensors over disjoint class ranges keeps every
        # binding comfortably under that floor and gives `nets::small100` its two logits ops for
        # free; 128,112 is 2 x 64,056, so the halves are equal and no range is padded.
        "head_splits": 2,
    },
    # whisper-base, from `openai/whisper-base`. A checkpoint for the same reason SMaLL-100 is one:
    # the ONNX exports that exist are already int8 and quantised per **tensor**, which is the
    # coarseness that cost SMaLL-100's embedding a factor of four.
    #
    # Two things about this architecture are unlike anything else here, and both are asserted rather
    # than assumed by [`whisper_inventory`]:
    #
    # * **No `k_proj.bias`.** Only `q_proj`, `v_proj` and `out_proj` carry one, in all 18 attentions.
    #   `Builder::conv_int8` always reads a bias after the scale, so the converter synthesises a
    #   zero and records it in the digest key — a checkpoint that grows one must not be read as this
    #   table.
    # * **A convolution stem.** `conv1` is `[512, 80, 3]` stride 1 and `conv2` is `[512, 512, 3]`
    #   **stride 2**, which is what turns 3000 mel frames into 1500 encoder positions. Both are
    #   rank-3, so they are lifted to the `1 x k` rank-4 kernel this runtime indexes.
    #
    # The head is **tied** to `embed_tokens`, as SMaLL-100's is, but at 51,865 x 512 it is 26.6 MB
    # and fits one binding — so unlike SMaLL-100 it needs no class split.
    "whisper": {
        "d_model": 512,
        "encoder_layers": 6,
        "decoder_layers": 6,
        "ffn": 2048,
        "vocab": 51_865,
        "mels": 80,
        # `max_source_positions` and `max_target_positions`. The first is 3000 mel frames after
        # `conv2`'s stride 2; the second is the decoder's learned position table.
        "source_positions": 1500,
        "target_positions": 448,
        "conv_kernel": 3,
    },
    # NLLB-200-distilled-600M, from `facebook/nllb-200-distilled-600M` (gated, MIT-adjacent
    # CC-BY-NC but fetched fine unauthenticated). A checkpoint for the same reason SMaLL-100
    # is one: the repo ships only `pytorch_model.bin` (fp32) plus `sentencepiece.bpe.model`
    # and `tokenizer.json` — there is no ONNX export at all.
    #
    # The architecture is `M2M100ForConditionalGeneration` (config.json says so verbatim:
    # `model_type: m2m_100`, `activation_function: relu`, `scale_embedding: true`,
    # `d_model: 1024`, `vocab_size: 256206`). So the forward pass is SMaLL-100's with two
    # differences, both asserted by [`nllb_inventory`] and [`collect_nllb`]:
    #
    # * **12 decoder layers, not 3.** The distilled-600M keeps the full decoder.
    # * **202 language tokens, not 100**, at ids `256001..256202` with `<mask>` at 256203.
    #   Tokenizer detail only — the converter never reads them — but the vocab count pins it.
    #
    # Everything else carries over: fairseq sinusoid positions with the +2 offset (same
    # `M2M100SinusoidalPositionalEmbedding`, offset=2, verified in transformers'
    # `modeling_m2m_100.py`), pre-norm layers, ReLU FFN, `1/sqrt(64)` query scale already in
    # `attn_scores`, all four projections biased (unlike whisper's missing k bias), tied
    # embedding emitted once.
    #
    # The checkpoint holds the tied embedding **four times** (`model.shared`,
    # `model.encoder.embed_tokens`, `model.decoder.embed_tokens`, `lm_head` — all
    # byte-identical, verified exact-equal). Only `model.shared.weight` is emitted.
    #
    # The tied embedding is 262.4 MiB of int8 (256,206 x 1024), so it needs **four** class
    # splits to stay under `maxStorageBufferRange`'s 128 MiB floor: 256,206 = 2 x 128,103
    # is not divisible by 4, so the four ranges are 64,052 / 64,052 / 64,051 / 64,051
    # (first two get the extra row each). `nets::nllb600` reads four logits ops.
    "nllb600": {
        "d_model": 1024,
        "encoder_layers": 12,
        "decoder_layers": 12,
        "ffn": 4096,
        "vocab": 256_206,
        # Four head splits, uneven (see above): first two ranges hold 64,052 classes,
        # last two 64,051.
        "head_splits": 4,
        "head_rows": [64_052, 64_052, 64_051, 64_051],
    },
    # Maia3-5M, from `UofTCSSLab/Maia3-5M`. A checkpoint because the repo ships a single
    # `maia3-5m.pt` pickle and nothing else — no ONNX, no safetensors. `fetch_maia.py`
    # repacks it, as `fetch_nllb600.py` does for NLLB's `.bin`.
    #
    # The architecture is `MAIA3Model` from CSSLab/maia3, with `BASE_SIZE_CONFIG` plus the
    # 5M row of `MODEL_SPECS`: 8 encoder-only blocks over 64 square tokens, `dim_vit` 256,
    # 8 heads, `mlp_ratio` 2.0. Four things about it are unlike anything else here, and all
    # four are asserted rather than assumed by [`maia_inventory`] and [`collect_maia`]:
    #
    # * **`omit_qkv_biases=True`.** `nn.MultiheadAttention(bias=False)`, so there is no
    #   `in_proj_bias` and no `out_proj.bias`. `Builder::conv` always reads a bias after
    #   its kernel, so four zeros per block are synthesised and recorded in the digest key.
    # * **A fused `in_proj_weight` `[768, 256]`.** Torch packs q, k and v into one tensor;
    #   the collector splits it into three in that order, which is
    #   `_in_projection_packed`'s.
    # * **`use_rms_norm=True`, post-norm.** `norm1` and `norm2` are `torch.nn.RMSNorm`, so
    #   they carry a weight and **no bias**. A checkpoint that grew `norm1.bias` would be
    #   an architecture change, not a detail, and fails here.
    # * **A shared attention-bias weight.** `smolgen_shared_weight` `[4096, 64]` is one
    #   parameter registered under nine names — itself plus
    #   `transformer.layers.N.self_attn.smolgen_weight` for all eight blocks, all aliasing
    #   the same storage. `fetch_maia.py` asserts that and drops the eight extras, so this
    #   inventory names only the shared one. See [`collect_maia`] for why it is emitted
    #   replicated eight times anyway.
    #
    # The value and ponder heads (`last_ln`, `fc_value*`, `fc_ponder*`) are in the
    # checkpoint and so are in the inventory, but are deliberately **not emitted**: nothing
    # picks a move with them. Asserting them keeps a re-trained head from passing silently.
    #
    # Every convolution is int8, quantised per output channel. That was chosen on measured
    # move agreement rather than on the cosine gate — see [`collect_maia`] and
    # `scripts/ml/maia_quant_eval.py`, which also rules out int4.
    "maia": {
        "d_model": 256,
        "blocks": 8,
        "heads": 8,
        "ffn": 512,
        "head_hidden": 256,
        # `gab_intermediate_dim` and `gab_gen_size`.
        "bias_hidden": 64,
        "bias_gen": 64,
        # `dim_emb`, each of the two elo embeddings' width.
        "elo_dim": 128,
        # `12 * history + 2 * dim_emb`, the token projection's input width.
        "input": 12 * 8 + 2 * 128,
        "squares": 64,
        "promotions": 4,
    },
}


class Layer:
    """One weighted node, as the Rust forward pass needs to see it."""

    def __init__(self, index, op, name, key, first_tensor, tensor_count):
        self.index = index
        self.op = op
        self.name = name
        self._key = key
        # Where this layer's tensors start in the `.maml` table, and how many it has.
        # The Rust indexes by tensor, and with `PRelu` in the mix that is no longer
        # `2 * layer` — so --print-layers reports it rather than leaving a transcriber
        # to count.
        self.first_tensor = first_tensor
        self.tensor_count = tensor_count

    def key(self):
        """Everything the Rust hardcodes about this layer, as a stable string."""
        return self._key


def lift_to_2d(weight, node):
    """A 1-D convolution's rank-3 weight as the rank-4 one this runtime indexes.

    A tensor here is `[c, h, w]` with no rank-3 case, so a 1-D convolution is a 2-D one
    whose kernel is `1 x k` — which is what every convolution in `nets::supertonic_vocoder`
    reads. Inserting the height axis is a reshape of the same bytes in the same order, and
    doing it here means the Rust reads `[out, in, 1, k]` like every other kernel rather than
    carrying a special case for audio.

    Returns `(weight, spatial)`, where `spatial` is the kernel/dilation/pad/stride form the
    digest key should record: also lifted to two dimensions, so a reader comparing the key
    against the Rust sees the same `(1, k)` the Rust wrote.
    """
    attrs = {a.name: a for a in node.attribute}

    def ints(name, default):
        return list(attrs[name].ints) if name in attrs else list(default)

    if weight.ndim != 3:
        return weight, None
    lifted = weight.reshape(weight.shape[0], weight.shape[1], 1, weight.shape[2])
    # ONNX orders pads as all the begins then all the ends, so a 1-D `[before, after]`
    # becomes `[0, before, 0, after]` rather than being padded on the right.
    pads = ints("pads", (0, 0))
    return lifted, {
        "kernel_shape": [1] + ints("kernel_shape", [weight.shape[2]]),
        "dilations": [1] + ints("dilations", [1]),
        "strides": [1] + ints("strides", [1]),
        "pads": [0, pads[0], 0, pads[1]],
    }


def convolution_key(node, weight, bias, spatial=None):
    """The digest key for a `Conv`/`ConvTranspose`.

    Byte-for-byte the string the first version of this script produced, so the pinned
    selfie and u2netp digests survive every later addition here.

    `spatial` overrides the node's own attributes, for a 1-D convolution that
    [`lift_to_2d`] has turned into a `1 x k` one.
    """
    attrs = {a.name: a for a in node.attribute}

    def ints(name, default):
        if spatial is not None and name in spatial:
            return list(spatial[name])
        return list(attrs[name].ints) if name in attrs else list(default)

    return (
        f"{node.op_type} w={list(weight.shape)} "
        f"b={list(bias.shape) if bias is not None else None} "
        f"k={ints('kernel_shape', weight.shape[2:])} "
        f"d={ints('dilations', (1, 1))} p={ints('pads', (0, 0, 0, 0))} "
        f"s={ints('strides', (1, 1))} "
        f"g={attrs['group'].i if 'group' in attrs else 1}"
    )


def inferred_shapes(model):
    """Every tensor's shape, for the fixed-shape graphs. Empty where inference fails."""
    try:
        inferred = shape_inference.infer_shapes(model)
    except Exception:
        return {}
    everything = list(inferred.graph.value_info) + list(inferred.graph.input)
    return {
        vi.name: [d.dim_value for d in vi.type.tensor_type.shape.dim]
        for vi in everything
    }


def flatten_source_shape(node, graph, shapes):
    """`[c, h, w]` of the map a `Gemm`'s flattened input came from.

    This is what lets a `Gemm` be emitted as a convolution kernel. It is read from the
    graph rather than assumed, because getting it wrong reshapes the fully-connected
    weight into the wrong kernel and produces a plausible-looking embedding.
    """
    producer = next((n for n in graph.node if node.input[0] in n.output), None)
    if producer is None or producer.op_type != "Flatten":
        raise SystemExit(
            f"{node.output[0]}: a Gemm whose input is not a Flatten. Only a Gemm over a "
            "flattened feature map can be emitted as a convolution."
        )
    shape = shapes.get(producer.input[0])
    if not shape or len(shape) != 4 or 0 in shape[1:]:
        raise SystemExit(
            f"{node.output[0]}: cannot infer the shape feeding {producer.output[0]}, "
            f"got {shape}. A Gemm can only be reshaped where the map's size is static."
        )
    return list(shape[1:])


def fold_batch_norm(node, inits, weight, bias):
    """Fold a `BatchNormalization` into the weight and bias of the layer before it.

    At inference a batch norm is `gamma * (x - mean) / sqrt(var + eps) + beta`, a
    per-output-channel affine, so pushing it into the preceding layer is exact. Doing it
    here rather than in a shader is why the runtime has no batch-norm pipeline.
    """
    attrs = {a.name: a for a in node.attribute}
    epsilon = attrs["epsilon"].f if "epsilon" in attrs else 1e-5
    missing = [n for n in node.input[1:] if n not in inits]
    if missing:
        raise SystemExit(f"{node.output[0]}: batch norm parameter {missing} is computed")
    gamma = numpy_helper.to_array(inits[node.input[1]]).astype(np.float64)
    beta = numpy_helper.to_array(inits[node.input[2]]).astype(np.float64)
    mean = numpy_helper.to_array(inits[node.input[3]]).astype(np.float64)
    var = numpy_helper.to_array(inits[node.input[4]]).astype(np.float64)
    scale = gamma / np.sqrt(var + epsilon)
    # fp64 for the fold itself: the division by sqrt(var) can be large, and this is the
    # one place the file's values are computed rather than copied.
    folded_weight = weight.astype(np.float64) * scale.reshape([-1] + [1] * (weight.ndim - 1))
    folded_bias = (bias.astype(np.float64) - mean) * scale + beta
    return folded_weight, folded_bias


def int8_eligible(node, consumers):
    """Whether `node`, a convolution, can be read as int8 by this runtime.

    Three exclusions, all forced by the runtime rather than chosen:

    * **`ConvTranspose`.** There is no int8 transposed convolution — `Builder` has `conv_int8`
      and no `conv_transpose_int8`, and `conv_transpose.comp` has no int8 twin.
    * **A convolution feeding a `PRelu`.** The Rust fuses that into the producing convolution
      as `Act::PRelu`, whose per-channel slope wants the push-block offset the dequantisation
      scale occupies. `Builder::conv_int8` refuses it outright.
    * **Edge padding.** `conv_int8.comp` reads out-of-bounds taps as zero and has no border
      replication, so a net that called `Builder::edge_padding` cannot quantise a *padded*
      convolution. Only the four Supertonic graphs do, and they are not converted through
      here — so this is documented rather than tested for.

    Everything else is fair game. `conv_int8.comp` handles groups, arbitrary kernel sizes,
    strides, dilations and zero padding, which is worth stating because
    `nets::supertonic_vocoder::INT8_CONVS` reads as though grouping were the blocker; there,
    the depthwise convolutions are excluded because they are *edge-padded*, not because they
    are grouped.
    """
    if node.op_type != "Conv":
        return False
    return not any(c.op_type == "PRelu" for c in consumers.get(node.output[0], ()))


def collect_layers(model, prefix=None, quantise=False):
    """The weighted nodes in topological order, with the tensors each contributes.

    `prefix` restricts the walk to one Torch module; see [`MODULES`].

    `quantise` reads every eligible convolution as int8 rather than fp16 — three tensors
    (kernel, per-output-channel scale, bias) instead of two. See [`INT8_GRAPHS`] for which
    graphs ask for it and [`int8_eligible`] for what "eligible" excludes and why.
    """
    graph = model.graph
    inits = {i.name: i for i in graph.initializer}
    # A module-scoped export keeps its weights in `Constant` nodes rather than the
    # initializer list, the way PP-OCRv5's recognition export does.
    constants = {}
    for node in graph.node:
        if node.op_type == "Constant":
            for attribute in node.attribute:
                if attribute.name == "value":
                    constants[node.output[0]] = attribute.t
    shapes = inferred_shapes(model)

    def held(name):
        return name in inits or name in constants

    def array(name):
        return numpy_helper.to_array(inits[name] if name in inits else constants[name])

    # Which node's output each BatchNormalization consumes, so the layer producing it
    # can absorb it.
    batch_norms = {n.input[0]: n for n in graph.node if n.op_type == "BatchNormalization"}
    consumers = {}
    for node in graph.node:
        for name in node.input:
            consumers.setdefault(name, []).append(node)
    folded = set()

    layers = []
    tensors = []
    fidelity = Fidelity() if quantise else None
    for node in graph.node:
        if prefix is not None and not node.name.startswith(prefix):
            continue
        first_tensor = len(tensors)
        if node.op_type in ("Conv", "ConvTranspose"):
            missing = [n for n in node.input[1:] if not held(n)]
            if missing:
                raise SystemExit(
                    f"{node.output[0]}: weight/bias {missing} is not a constant. "
                    "A computed weight means this is not the frozen export we lower."
                )
            weight = array(node.input[1])
            weight, spatial = lift_to_2d(weight, node)
            bias = array(node.input[2]) if len(node.input) > 2 else None
            synthesised = bias is None
            if synthesised:
                # The shaders always add a bias, so a layer without one gets a zero. Exact,
                # and recorded in the key so the digest notices if a layer that used to have
                # a bias stops having one — which would otherwise look like the same table.
                channels = weight.shape[1] if node.op_type == "ConvTranspose" else weight.shape[0]
                bias = np.zeros(channels, dtype=np.float32)
            key = convolution_key(node, weight, bias, spatial)
            if synthesised:
                key += " b0=synthesised"
            if quantise and int8_eligible(node, consumers):
                kernel, scale = fidelity.quantise(node.name or node.output[0], weight)
                tensors.append(kernel)
                tensors.append(scale)
                tensors.append(bias)
                key = key.replace("Conv ", "ConvInt8 ", 1) + " zp=0 dtype=int8"
            else:
                tensors.append(weight)
                tensors.append(bias)
        elif node.op_type == "PRelu":
            if not held(node.input[1]):
                raise SystemExit(f"{node.output[0]}: a computed PRelu slope")
            slope = array(node.input[1])
            key = f"PRelu s={list(slope.shape)}"
            tensors.append(slope)
        elif node.op_type == "Gemm":
            attrs = {a.name: a for a in node.attribute}
            alpha = attrs["alpha"].f if "alpha" in attrs else 1.0
            beta = attrs["beta"].f if "beta" in attrs else 1.0
            trans_a = attrs["transA"].i if "transA" in attrs else 0
            trans_b = attrs["transB"].i if "transB" in attrs else 0
            if (alpha, beta, trans_a, trans_b) != (1.0, 1.0, 0, 1):
                raise SystemExit(
                    f"{node.output[0]}: Gemm alpha={alpha} beta={beta} transA={trans_a} "
                    f"transB={trans_b}; only the plain `x @ W.T + b` form is lowered."
                )
            missing = [n for n in node.input[1:] if not held(n)]
            if missing:
                raise SystemExit(f"{node.output[0]}: Gemm operand {missing} is computed")
            weight = array(node.input[1])
            bias = array(node.input[2])
            batch_norm = batch_norms.get(node.output[0])
            if batch_norm is not None:
                weight, bias = fold_batch_norm(batch_norm, inits, weight, bias)
                folded.add(batch_norm.output[0])
            spatial = flatten_source_shape(node, graph, shapes)
            expected = int(np.prod(spatial))
            if weight.shape[1] != expected:
                raise SystemExit(
                    f"{node.output[0]}: Gemm weight is {list(weight.shape)} but its input "
                    f"flattens {spatial} = {expected} values"
                )
            weight = weight.reshape([weight.shape[0]] + spatial)
            key = (
                f"Gemm w={list(weight.shape)} b={list(bias.shape)} "
                f"bn={batch_norm is not None}"
            )
            tensors.append(weight)
            tensors.append(bias)
        elif node.op_type == "Gather" and held(node.input[0]) and array(node.input[0]).ndim == 2:
            # An embedding table. An export that multiplies the looked-up row by
            # `sqrt(d_model)` has that scale folded in here rather than left to a shader: it
            # is one op fewer and one rounding fewer, since `round(table * sqrt(d))` is
            # closer than `round(table) * sqrt(d)`.
            table = array(node.input[0]).astype(np.float64)
            table = table * math.sqrt(table.shape[1])
            key = f"Embed t={list(table.shape)} scaled=sqrt({table.shape[1]})"
            tensors.append(table)
        elif node.op_type == "Pad" and held(node.input[0]):
            # A relative position table, which the export pads out to `2T-1` entries and
            # then skews. `nets::supertonic_text` and `nets::supertonic_duration` read the
            # nine unpadded entries directly, so the leading batch axis is dropped and the
            # padding never happens.
            table = np.squeeze(array(node.input[0]))
            if table.ndim != 2 or table.shape[0] % 2 == 0:
                raise SystemExit(
                    f"{node.output[0]}: a padded constant of {list(np.shape(table))}; a "
                    "relative table is [2 * window + 1, head_dim]"
                )
            key = f"Relative t={list(table.shape)}"
            tensors.append(table)
        elif node.op_type == "Mul" and layer_norm_scale(node, consumers, held, array):
            # A layer norm's gamma and the beta on the `Add` after it. The export decomposes
            # the rest of the norm into ReduceMean/Sub/Pow/Sqrt/Div, none of which carries a
            # weight, so this pair is all there is to collect.
            gamma = next(array(n) for n in node.input if held(n))
            follow = consumers[node.output[0]][0]
            beta = next(array(n) for n in follow.input if held(n))
            key = f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}"
            tensors.append(gamma)
            tensors.append(beta)
        elif node.op_type == "BatchNormalization":
            # Folded above, when the layer it follows was emitted. One that was not is a
            # batch norm somewhere in the middle of the graph, which nothing here can
            # absorb and no shader implements.
            if node.output[0] not in folded:
                raise SystemExit(
                    f"{node.output[0]}: a BatchNormalization that does not follow a Gemm. "
                    "Only a trailing batch norm can be folded away."
                )
            continue
        else:
            continue
        layers.append(
            Layer(
                len(layers),
                node.op_type,
                node.output[0],
                key,
                first_tensor,
                len(tensors) - first_tensor,
            )
        )
    if fidelity is not None:
        fidelity.report()
    return layers, tensors


def layer_norm_scale(node, consumers, held, array):
    """Whether `node` is the `Mul(x, gamma)` of a decomposed layer norm.

    Recognised by structure rather than by name: a `Mul` against a rank-1 constant whose one
    consumer is an `Add` against a rank-1 constant of the same length. In the exports this reads
    that matches the layer norms and nothing else - mask multiplies and embedding scales are
    against scalars or computed tensors.
    """
    scales = [n for n in node.input if held(n) and array(n).ndim == 1]
    if len(scales) != 1:
        return False
    follow = consumers.get(node.output[0], [])
    if len(follow) != 1 or follow[0].op_type != "Add":
        return False
    shifts = [
        n
        for n in follow[0].input
        if held(n) and array(n).ndim == 1 and array(n).size == array(scales[0]).size
    ]
    return len(shifts) == 1


def layer_table_digest(layers):
    h = hashlib.sha256()
    for layer in layers:
        h.update(layer.key().encode())
        h.update(b"\n")
    return h.hexdigest()


def check_graph(model, graph_id_name):
    graph = model.graph
    prefix = MODULES.get(graph_id_name)
    nodes = [n for n in graph.node if prefix is None or n.name.startswith(prefix)]

    if prefix is None and graph_id_name in GEOMETRY:
        want = GEOMETRY[graph_id_name]
        names = [i.name for i in graph.input]
        if names != [want["input"]]:
            raise SystemExit(f"inputs are {names}, expected [{want['input']!r}]")
        dims = [
            d.dim_value or d.dim_param for d in graph.input[0].type.tensor_type.shape.dim
        ]
        # Batch may be symbolic. A spatial dim may be too, but only where the expected
        # shape says so: for the fixed-size nets every folded Resize target and pad below
        # was resolved at exactly this size, so a dynamic one would silently mislower.
        got = list(dims[1:])
        expect = want["shape"]
        mismatched = len(got) != len(expect) or any(
            e is not None and g != e for g, e in zip(got, expect)
        )
        if mismatched:
            raise SystemExit(f"input shape {dims}, expected [batch] + {expect}")

        outputs = [o.name for o in graph.output]
        absent = [o for o in want["outputs"] if o not in outputs]
        if absent:
            raise SystemExit(f"outputs are {outputs}, expected {absent} among them")
    elif not nodes:
        raise SystemExit(
            f"no nodes are named {prefix!r}. A module-scoped graph relies on the export "
            "keeping its Torch module paths, which this one has not."
        )

    counts = {}
    for node in nodes:
        counts[node.op_type] = counts.get(node.op_type, 0) + 1
    expected = EXPECTED_OPS[graph_id_name]
    if counts != expected:
        only_got = {k: v for k, v in counts.items() if expected.get(k) != v}
        only_want = {k: v for k, v in expected.items() if counts.get(k) != v}
        raise SystemExit(f"op counts differ: got {only_got}, expected {only_want}")


def open_checkpoint(path):
    """`(name -> ndarray, {name: shape})` for a `.safetensors` file, read one tensor at a time.

    Lazily, because the checkpoint is 1.33 GB of fp32 and every tensor is quantised to int8 or
    rounded to fp16 the moment it is read - so holding the whole thing costs 1.3 GB of resident
    host memory for nothing.
    """
    try:
        from safetensors import safe_open
    except ImportError:
        raise SystemExit("needs safetensors: python -m pip install safetensors")
    handle = safe_open(path, framework="np")
    shapes = {name: list(handle.get_slice(name).get_shape()) for name in handle.keys()}
    return handle.get_tensor, shapes


def small100_inventory(spec):
    """`{name: shape}` the checkpoint must hold exactly, derived from [`CHECKPOINTS`].

    Derived rather than transcribed: a list of 275 names would be checked against itself.
    """
    d, ffn = spec["d_model"], spec["ffn"]
    want = {"model.shared.weight": [spec["vocab"], d]}
    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        want[f"model.{side}.layer_norm.weight"] = [d]
        want[f"model.{side}.layer_norm.bias"] = [d]
        for index in range(count):
            at = f"model.{side}.layers.{index}"
            attentions = ["self_attn"] + (["encoder_attn"] if side == "decoder" else [])
            for attention in attentions:
                want[f"{at}.{attention}_layer_norm.weight"] = [d]
                want[f"{at}.{attention}_layer_norm.bias"] = [d]
                for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                    want[f"{at}.{attention}.{projection}.weight"] = [d, d]
                    want[f"{at}.{attention}.{projection}.bias"] = [d]
            want[f"{at}.final_layer_norm.weight"] = [d]
            want[f"{at}.final_layer_norm.bias"] = [d]
            want[f"{at}.fc1.weight"] = [ffn, d]
            want[f"{at}.fc1.bias"] = [ffn]
            want[f"{at}.fc2.weight"] = [d, ffn]
            want[f"{at}.fc2.bias"] = [d]
    return want


def whisper_inventory(spec):
    """`{name: shape}` whisper-base's checkpoint must hold exactly, derived from [`CHECKPOINTS`].

    Derived rather than transcribed: a list of 245 names would be checked against itself.

    Two absences are as load-bearing as the presences, so both are stated by *not* appearing:

    * **`k_proj.bias` is not here**, in any of the 18 attentions. A checkpoint that grows one would
      have 18 unexpected names and fail, rather than being read as this table with the bias ignored.
    * **There is no `proj_out`.** The head is tied to `model.decoder.embed_tokens.weight`, which
      therefore appears once and is emitted once.

    The shapes are `torch.nn.Linear`'s **`[out, in]`**, which is a `[out, in, 1, 1]` kernel and so a
    reshape rather than a transpose. `fc1` at `[2048, 512]` and `fc2` at `[512, 2048]` are the
    asymmetric pair that makes a transposed checkpoint fail here rather than on the device.
    """
    d, ffn = spec["d_model"], spec["ffn"]
    kernel = spec["conv_kernel"]
    want = {
        "model.encoder.conv1.weight": [d, spec["mels"], kernel],
        "model.encoder.conv1.bias": [d],
        "model.encoder.conv2.weight": [d, d, kernel],
        "model.encoder.conv2.bias": [d],
        # A real tensor, not computed sinusoids: whisper freezes them into the checkpoint.
        "model.encoder.embed_positions.weight": [spec["source_positions"], d],
        "model.decoder.embed_tokens.weight": [spec["vocab"], d],
        # Learned, unlike the encoder's.
        "model.decoder.embed_positions.weight": [spec["target_positions"], d],
    }
    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        want[f"model.{side}.layer_norm.weight"] = [d]
        want[f"model.{side}.layer_norm.bias"] = [d]
        for index in range(count):
            at = f"model.{side}.layers.{index}"
            attentions = ["self_attn"] + (["encoder_attn"] if side == "decoder" else [])
            for attention in attentions:
                want[f"{at}.{attention}_layer_norm.weight"] = [d]
                want[f"{at}.{attention}_layer_norm.bias"] = [d]
                for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                    want[f"{at}.{attention}.{projection}.weight"] = [d, d]
                    # Every projection but the key has a bias. See the docstring.
                    if projection != "k_proj":
                        want[f"{at}.{attention}.{projection}.bias"] = [d]
            want[f"{at}.final_layer_norm.weight"] = [d]
            want[f"{at}.final_layer_norm.bias"] = [d]
            want[f"{at}.fc1.weight"] = [ffn, d]
            want[f"{at}.fc1.bias"] = [ffn]
            want[f"{at}.fc2.weight"] = [d, ffn]
            want[f"{at}.fc2.bias"] = [d]
    return want


# The inventory each checkpoint-sourced graph is checked against, and the collector that emits it.
#
# Two dicts rather than a key inside [`CHECKPOINTS`] because both hold functions defined below it.
INVENTORIES = {}
COLLECTORS = {}


def check_checkpoint(shapes, graph_id_name):
    """Refuse a checkpoint that is not the architecture the Rust forward pass hardcodes.

    # The transpose that would otherwise be silent

    A `torch.nn.Linear`'s weight is **`[out, in]`**, and this runtime indexes a kernel as
    `[out, in, kh, kw]`, so a projection needs a reshape and no transpose. ONNX `MatMul` is the
    other way round - `x @ W` with a `[in, out]` weight - and every attention projection here is
    square 1024 x 1024, so reading one convention as the other yields a tensor of exactly the
    right shape holding exactly the wrong numbers, and a parameter count that still adds up.

    `fc1` is `[4096, 1024]` and `fc2` is `[1024, 4096]`, so the shape check below is what makes
    that mistake loud: a transposed checkpoint fails on those four numbers before anything is
    written. That is the whole reason this asserts shapes rather than just names.
    """
    want = INVENTORIES[graph_id_name](CHECKPOINTS[graph_id_name])
    missing = sorted(set(want) - set(shapes))
    extra = sorted(set(shapes) - set(want))
    shared = sorted(set(want) & set(shapes))
    wrong = {n: (shapes[n], want[n]) for n in shared if shapes[n] != want[n]}
    if missing or extra or wrong:
        lines = []
        if missing:
            lines.append(f"  absent: {missing[:6]}{' ...' if len(missing) > 6 else ''}")
        if extra:
            lines.append(f"  unexpected: {extra[:6]}{' ...' if len(extra) > 6 else ''}")
        for name, (got, expect) in list(wrong.items())[:6]:
            lines.append(f"  {name}: {got} against {expect}")
        raise SystemExit(
            f"{graph_id_name}: the checkpoint is not the architecture nets/{graph_id_name}.rs\n"
            "hardcodes. A wrong shape here is a wrong forward pass, not a load failure:\n"
            + "\n".join(lines)
        )


class Fidelity:
    """The worst per-tensor cosine between an fp32 weight and the `int8 * scale` a device reads.

    The only check that looks at the *numbers* a quantisation produces. It is not a check that the
    forward pass is right — a transposed read agrees with itself perfectly, which is why
    [`check_checkpoint`] and [`check_architecture`] assert shapes — and it is not a check that the
    model is accurate, which `scripts/ml/onnx_parity.py` is for. It is the one thing that catches a
    layer whose distribution int8 cannot represent, before it ships.
    """

    def __init__(self):
        self.seen = []

    def quantise(self, name, weight):
        """`(int8, scale)` for `weight`, recording how well the pair reproduces it."""
        kernel, scale = quantise_per_channel(weight)
        back = kernel.astype(np.float32).reshape(weight.shape[0], -1) * scale[:, None]
        flat = np.ascontiguousarray(weight, dtype=np.float32).reshape(weight.shape[0], -1)
        cosine = float(
            np.dot(flat.ravel(), back.ravel())
            / max(np.linalg.norm(flat) * np.linalg.norm(back), 1e-30)
        )
        self.seen.append((cosine, name))
        return kernel, scale

    def report(self):
        """Print the worst three, and refuse the file if any is under [`MIN_INT8_COSINE`]."""
        self.seen.sort()
        for cosine, name in self.seen[:3]:
            if cosine < MIN_INT8_COSINE:
                raise SystemExit(
                    f"{name}: int8 * scale correlates {cosine:.6f} with the fp32 weight, under"
                    f" {MIN_INT8_COSINE}.\nQuantising this layer costs too much; exclude it and"
                    " store it fp16 instead."
                )
        worst = ", ".join(f"{name} {cosine:.6f}" for cosine, name in self.seen[:3])
        print(f"int8 fidelity over {len(self.seen)} tensors, worst three: {worst}")


def collect_small100(get, spec):
    """SMaLL-100's checkpoint as the ordered tensor table `nets::small100` indexes.

    Order is the contract, and it is:

        the tied embedding, as `head_splits` disjoint class ranges
        each encoder layer, in forward order
        the encoder's final layer norm
        each decoder layer, in forward order
        the decoder's final layer norm

    The **tied embedding comes first and appears once**, because it has two roles and neither
    wants a second copy. `nets::small100` gathers row `t` of it on the host to build the encoder
    and decoder inputs - the rows are contiguous, and each carries its own scale, which is exactly
    what a per-output-channel quantiser produces - and binds the same tensors as the kernel of the
    logits projection. Splitting it by class range is what keeps that binding under
    `maxStorageBufferRange`; see [`CHECKPOINTS`].

    # What is folded, and what is not

    * `sqrt(d_model)` is **not** folded into the embedding here, unlike the fp16 `Gather` arm in
      [`collect_layers`]. The host gather applies it while it is still fp32, which is one rounding
      fewer than scaling an int8 code by its scale and then by `sqrt(1024)`.
    * The attention query scale is **not** folded into `q_proj`. M2M-100 scales the query by
      `head_dim ** -0.5` and `head_dim` is 64, which is the `1 / sqrt(head_dim)` `attn_scores`
      already applies. Folding it would apply it twice.
    * Sinusoidal positions are not in the checkpoint and are not emitted. They are static, so
      `nets::small100` computes them and adds them to the gathered rows in fp32, which also
      removes the `pos_weights.f32.bin` the ncnn build had to download.
    """
    layers = []
    tensors = []
    # Worst per-tensor cosine between the fp32 weight and `int8 * scale`, which is the numbers
    # the device will read. This is the only check that the quantisation itself is usable; the
    # shape assertions in [`check_checkpoint`] are what catch a transpose, since a cosine is
    # computed against whatever was read and a transposed read agrees with itself perfectly.
    fidelity = Fidelity()

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def linear(name):
        """An int8 kernel as `[out, in, 1, 1]`, its per-output-channel scale, and its bias."""
        weight = get(f"{name}.weight")
        bias = get(f"{name}.bias")
        outputs, inputs = weight.shape
        kernel, scale = fidelity.quantise(name, weight.reshape(outputs, inputs, 1, 1))
        tensors.extend([kernel, scale, bias])
        emit(
            "Linear8",
            name,
            f"Linear8 w={list(kernel.shape)} scale={[outputs]} b={list(bias.shape)} "
            "zp=0 dtype=int8",
            3,
        )

    def layer_norm(name):
        gamma = get(f"{name}.weight")
        beta = get(f"{name}.bias")
        tensors.extend([gamma, beta])
        emit("LayerNorm", name, f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}", 2)

    embedding = get("model.shared.weight")
    splits = spec["head_splits"]
    classes, width = embedding.shape
    if classes % splits:
        raise SystemExit(f"{classes} classes do not divide into {splits} equal ranges")
    step = classes // splits
    for part in range(splits):
        lo = part * step
        rows = embedding[lo : lo + step]
        name = f"model.shared.weight[{lo}:{lo + step}]"
        kernel, scale = fidelity.quantise(name, rows.reshape(step, width, 1, 1))
        # `Builder::conv_int8` reads a bias after the scale, and a tied head has none. Zeros are
        # exact, and recording it in the key means a checkpoint that grows one is not mistaken for
        # this table.
        tensors.extend([kernel, scale, np.zeros(step, dtype=np.float32)])
        emit(
            "Head",
            name,
            f"Head w={list(kernel.shape)} scale={[step]} b={[step]} "
            f"classes={lo}..{lo + step} zp=0 dtype=int8 b0=synthesised",
            3,
        )

    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        for index in range(count):
            at = f"model.{side}.layers.{index}"
            # Pre-norm, as `M2M100EncoderLayer` and `M2M100DecoderLayer` are: the norm comes
            # before the sublayer it feeds and the residual skips both. Emitting in forward order
            # is what lets the Rust walk the table without an index table.
            layer_norm(f"{at}.self_attn_layer_norm")
            for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                linear(f"{at}.self_attn.{projection}")
            if side == "decoder":
                layer_norm(f"{at}.encoder_attn_layer_norm")
                for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                    linear(f"{at}.encoder_attn.{projection}")
            layer_norm(f"{at}.final_layer_norm")
            linear(f"{at}.fc1")
            linear(f"{at}.fc2")
        layer_norm(f"model.{side}.layer_norm")

    fidelity.report()
    return layers, tensors


def collect_whisper(get, spec):
    """whisper-base's checkpoint as the ordered tensor table `nets::whisper` indexes.

    Order is the contract, and it is forward order per stack, encoder first:

        `conv1`, `conv2`, the encoder position table, each encoder layer, the encoder's norm
        the tied head, the decoder position table, each decoder layer, the decoder's norm

    # The two convolutions

    Both are rank-3 in the checkpoint and are emitted as the `1 x k` rank-4 kernel this runtime
    indexes, which is a reshape of the same bytes. Their geometry is in the digest key rather than in
    the Rust alone, because `conv2`'s **stride 2** is what turns 3000 mel frames into 1500 encoder
    positions and a wrong stride gives the right rank and the wrong length.

    # The absent key bias

    No `k_proj` in any of the 18 attentions has one. `Builder::conv_int8` reads a bias after the
    scale, so each gets zeros — exact, and recorded in the digest key as `b0=synthesised` so a
    checkpoint that grows one is not silently read as this table.

    # The tied head

    `model.decoder.embed_tokens.weight` is emitted **once** and has two roles, as SMaLL-100's does:
    `nets::whisper` gathers a row of it on the host to build a decode step's input, and binds the
    same tensor as the kernel of the logits projection. At 51,865 x 512 it is 26.6 MB of int8, well
    inside `maxStorageBufferRange`'s guaranteed 128 MiB, so unlike SMaLL-100 it needs no class split.

    # What is not folded

    * The attention query scale. Whisper scales the query by `head_dim ** -0.5` and `head_dim` is 64,
      which is the `1 / sqrt(head_dim)` `attn_scores` already applies. Folding it would apply it
      twice.
    * `sqrt(d_model)` on the embedding. `config.json` has `scale_embedding: false`, so there is none.
    * Both **position tables** are real tensors, and they go opposite ways. The **encoder's** is
      added to the conv stem's output, which is a device tensor, so it is transposed to
      `[width, 1, positions]` and emitted as a `Kind::Constant`. The **decoder's** is added to a
      gathered embedding row on the *host*, in f32, exactly as `nets::small100::embed_positions`
      does, so it stays `[positions, width]` and never reaches a shader.
    """
    layers = []
    tensors = []
    fidelity = Fidelity()

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def convolution(name, stride):
        """A rank-3 `[out, in, k]` kernel as the `1 x k` int8 one, its scale and its bias."""
        weight = get(f"{name}.weight")
        bias = get(f"{name}.bias")
        outputs, inputs, width = weight.shape
        lifted = weight.reshape(outputs, inputs, 1, width)
        kernel, scale = fidelity.quantise(name, lifted)
        tensors.extend([kernel, scale, bias])
        # ONNX orders pads as all the begins then all the ends, so a 1-D `[1, 1]` is `[0, 1, 0, 1]`.
        # `same` padding on a kernel of 3, which is what holds the length at stride 1 and halves it
        # at stride 2.
        emit(
            "Conv8",
            name,
            f"Conv8 w={list(kernel.shape)} scale={[outputs]} b={list(bias.shape)} "
            f"k={[1, width]} s={[1, stride]} p={[0, 1, 0, 1]} zp=0 dtype=int8",
            3,
        )

    def linear(name, biased=True):
        """An int8 kernel as `[out, in, 1, 1]`, its per-output-channel scale, and its bias."""
        weight = get(f"{name}.weight")
        outputs, inputs = weight.shape
        kernel, scale = fidelity.quantise(name, weight.reshape(outputs, inputs, 1, 1))
        if biased:
            bias = get(f"{name}.bias")
            suffix = ""
        else:
            bias = np.zeros(outputs, dtype=np.float32)
            suffix = " b0=synthesised"
        tensors.extend([kernel, scale, bias])
        emit(
            "Linear8",
            name,
            f"Linear8 w={list(kernel.shape)} scale={[outputs]} b={list(bias.shape)} "
            f"zp=0 dtype=int8{suffix}",
            3,
        )

    def layer_norm(name):
        gamma = get(f"{name}.weight")
        beta = get(f"{name}.bias")
        tensors.extend([gamma, beta])
        emit("LayerNorm", name, f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}", 2)

    def table(name):
        values = get(f"{name}.weight")
        tensors.append(values)
        emit("Table", name, f"Table t={list(values.shape)}", 1)

    def constant(name):
        """A `[positions, width]` table as the `[width, 1, positions]` a `Kind::Constant` wants."""
        values = get(f"{name}.weight")
        positions, width = values.shape
        transposed = values.T.reshape(width, 1, positions).copy()
        tensors.append(transposed)
        emit("Constant", name, f"Constant t={list(transposed.shape)}", 1)

    def stack(side, count):
        for index in range(count):
            at = f"model.{side}.layers.{index}"
            # Pre-norm, as `WhisperEncoderLayer` and `WhisperDecoderLayer` are: the norm comes before
            # the sublayer it feeds and the residual skips both.
            attentions = ["self_attn"] + (["encoder_attn"] if side == "decoder" else [])
            for attention in attentions:
                layer_norm(f"{at}.{attention}_layer_norm")
                for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                    linear(f"{at}.{attention}.{projection}", biased=projection != "k_proj")
            layer_norm(f"{at}.final_layer_norm")
            linear(f"{at}.fc1")
            linear(f"{at}.fc2")
        layer_norm(f"model.{side}.layer_norm")

    # --- the encoder ---
    convolution("model.encoder.conv1", stride=1)
    # Stride 2: 3000 mel frames become the 1500 positions the decoder cross-attends over.
    convolution("model.encoder.conv2", stride=2)
    # Transposed and device-resident: it is added to the conv stem's output. The decoder's table
    # below is not, because the host adds that one.
    constant("model.encoder.embed_positions")
    stack("encoder", spec["encoder_layers"])

    # --- the decoder ---
    head = "model.decoder.embed_tokens"
    rows = get(f"{head}.weight")
    classes, width = rows.shape
    kernel, scale = fidelity.quantise(head, rows.reshape(classes, width, 1, 1))
    # `Builder::conv_int8` reads a bias after the scale, and a tied head has none.
    tensors.extend([kernel, scale, np.zeros(classes, dtype=np.float32)])
    emit(
        "Head",
        head,
        f"Head w={list(kernel.shape)} scale={[classes]} b={[classes]} tied=embed_tokens "
        "zp=0 dtype=int8 b0=synthesised",
        3,
    )
    table("model.decoder.embed_positions")
    stack("decoder", spec["decoder_layers"])

    fidelity.report()
    return layers, tensors


def nllb_inventory(spec):
    """`{name: shape}` NLLB-200-distilled-600M's checkpoint must hold exactly.

    Derived rather than transcribed: a list of 513 names would be checked against itself.

    The checkpoint holds the tied embedding **four times** (`model.shared`,
    `model.encoder.embed_tokens`, `model.decoder.embed_tokens`, `lm_head` — verified
    byte-identical). Only `model.shared.weight` is wanted; the other three are stated by
    *not* appearing, so a checkpoint that unties them fails loudly instead of being read
    as this table with three copies ignored.

    Unlike whisper, **every** projection (all 96 attentions' q/k/v/out) carries a bias —
    `k_proj.bias` is present. And unlike whisper there is no convolution stem and no
    learned position table: positions are fairseq sinusoids computed by the Rust, exactly
    as for small100.
    """
    d, ffn = spec["d_model"], spec["ffn"]
    want = {"model.shared.weight": [spec["vocab"], d]}
    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        want[f"model.{side}.layer_norm.weight"] = [d]
        want[f"model.{side}.layer_norm.bias"] = [d]
        for index in range(count):
            at = f"model.{side}.layers.{index}"
            attentions = ["self_attn"] + (["encoder_attn"] if side == "decoder" else [])
            for attention in attentions:
                want[f"{at}.{attention}_layer_norm.weight"] = [d]
                want[f"{at}.{attention}_layer_norm.bias"] = [d]
                for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                    want[f"{at}.{attention}.{projection}.weight"] = [d, d]
                    want[f"{at}.{attention}.{projection}.bias"] = [d]
            want[f"{at}.final_layer_norm.weight"] = [d]
            want[f"{at}.final_layer_norm.bias"] = [d]
            want[f"{at}.fc1.weight"] = [ffn, d]
            want[f"{at}.fc1.bias"] = [ffn]
            want[f"{at}.fc2.weight"] = [d, ffn]
            want[f"{at}.fc2.bias"] = [d]
    return want


def collect_nllb(get, spec):
    """NLLB-200-distilled-600M's checkpoint as the ordered tensor table `nets::nllb600` indexes.

    THE TENSOR ORDER (the contract with the Rust net — positional indexing, no names):

        1. the tied embedding (`model.shared.weight` ONLY), as `head_splits` disjoint
           class ranges: [0:64052], [64052:128104], [128104:192155], [192155:256206].
           Each range emits 3 tensors: int8 kernel [rows, 1024, 1, 1], fp16 scale [rows],
           synthesised zero bias [rows]. Op "Head". Tensors 0..11.
        2. each encoder layer 0..11 in forward order. Per layer, in this exact order:
           self_attn_layer_norm (LayerNorm, 2 tensors: gamma, beta),
           self_attn q/k/v/out_proj (Linear8, 3 tensors each: kernel, scale, bias),
           final_layer_norm (LayerNorm, 2),
           fc1 (Linear8, 3), fc2 (Linear8, 3).
           22 tensors per encoder layer.
        3. the encoder's final layer norm (LayerNorm, 2 tensors).
        4. each decoder layer 0..11 in forward order. Per layer: self_attn block as in
           the encoder (2 + 4*3 = 14 tensors), then encoder_attn_layer_norm (2) +
           encoder_attn q/k/v/out_proj (4*3 = 12), then final_layer_norm (2) + fc1/fc2
           (2*3 = 6). 36 tensors per decoder layer.
        5. the decoder's final layer norm (LayerNorm, 2 tensors). Last.

    Totals: 12 head tensors + 12*22 encoder + 2 + 12*36 decoder + 2 = 712 tensors in
    258 layers (4 Head + 96 enc + 1 + 156 dec + 1).
    196 int8 convolutions (4 head + 12*6 enc + 12*10 dec); everything else fp16 norms
    and biases.

    # What is folded, and what is not (same as small100 — same M2M-100 forward pass)

    * `sqrt(d_model)` is **not** folded into the embedding. The host gather applies it in
      fp32 (`embed_positions`), one rounding fewer than scaling int8 codes.
    * The attention query scale is **not** folded into `q_proj`. M2M-100 scales the query
      by `head_dim ** -0.5` (head_dim 64) = the `1 / sqrt(head_dim)` `attn_scores` applies.
    * Sinusoidal positions are not in the checkpoint and are not emitted. `nets::nllb600`
      computes fairseq's concatenated-halves sinusoid at position `past + at + 1 + 1`
      (padding_idx 1, so first token sits at 2) — byte-identical math to small100.
    * FFN activation is **ReLU** (`activation_function: relu` in config.json), so the Rust
      uses `Act::Relu` on fc1, not GELU.
    * The four checkpoint copies of the tied embedding are asserted byte-identical in
      `fetch_nllb600.py` before conversion; only `model.shared.weight` is read here.
    """
    layers = []
    tensors = []
    fidelity = Fidelity()

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def linear(name):
        """An int8 kernel as `[out, in, 1, 1]`, its per-output-channel scale, and its bias."""
        weight = get(f"{name}.weight")
        bias = get(f"{name}.bias")
        outputs, inputs = weight.shape
        kernel, scale = fidelity.quantise(name, weight.reshape(outputs, inputs, 1, 1))
        tensors.extend([kernel, scale, bias])
        emit(
            "Linear8",
            name,
            f"Linear8 w={list(kernel.shape)} scale={[outputs]} b={list(bias.shape)} "
            "zp=0 dtype=int8",
            3,
        )

    def layer_norm(name):
        gamma = get(f"{name}.weight")
        beta = get(f"{name}.bias")
        tensors.extend([gamma, beta])
        emit("LayerNorm", name, f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}", 2)

    embedding = get("model.shared.weight")
    rows_spec = spec["head_rows"]
    if sum(rows_spec) != embedding.shape[0]:
        raise SystemExit(
            f"{embedding.shape[0]} classes do not split as {rows_spec}"
        )
    lo = 0
    for part, rows in enumerate(rows_spec):
        chunk = embedding[lo : lo + rows]
        name = f"model.shared.weight[{lo}:{lo + rows}]"
        kernel, scale = fidelity.quantise(name, chunk.reshape(rows, embedding.shape[1], 1, 1))
        # `Builder::conv_int8` reads a bias after the scale, and a tied head has none. Zeros are
        # exact, and recording it in the key means a checkpoint that grows one is not mistaken for
        # this table.
        tensors.extend([kernel, scale, np.zeros(rows, dtype=np.float32)])
        emit(
            "Head",
            name,
            f"Head w={list(kernel.shape)} scale={[rows]} b={[rows]} "
            f"classes={lo}..{lo + rows} zp=0 dtype=int8 b0=synthesised",
            3,
        )
        lo += rows

    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        for index in range(count):
            at = f"model.{side}.layers.{index}"
            # Pre-norm, as `M2M100EncoderLayer` and `M2M100DecoderLayer` are: the norm comes
            # before the sublayer it feeds and the residual skips both. Emitting in forward order
            # is what lets the Rust walk the table without an index table.
            layer_norm(f"{at}.self_attn_layer_norm")
            for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                linear(f"{at}.self_attn.{projection}")
            if side == "decoder":
                layer_norm(f"{at}.encoder_attn_layer_norm")
                for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                    linear(f"{at}.encoder_attn.{projection}")
            layer_norm(f"{at}.final_layer_norm")
            linear(f"{at}.fc1")
            linear(f"{at}.fc2")
        layer_norm(f"model.{side}.layer_norm")

    fidelity.report()
    return layers, tensors


def maia_inventory(spec):
    """`{name: shape}` Maia3-5M's checkpoint must hold exactly, after `fetch_maia.py`'s repack.

    Derived rather than transcribed: a list of 148 names would be checked against itself.

    Three absences are as load-bearing as the presences, so all three are stated by *not*
    appearing:

    * **No attention biases.** `omit_qkv_biases=True`, so there is no `mha.in_proj_bias`
      and no `mha.out_proj.bias` in any of the eight blocks.
    * **No `norm1.bias` or `norm2.bias`.** They are `torch.nn.RMSNorm`, which has a gain
      and nothing else. A checkpoint that grew either would be a layer norm, and the Rust
      would silently ignore the beta.
    * **No `transformer.layers.N.self_attn.smolgen_weight`.** Those eight names alias
      `smolgen_shared_weight`; `fetch_maia.py` asserts that and drops them, so a checkpoint
      whose blocks stopped sharing one weight fails here with eight unexpected names rather
      than being converted as if they still did.

    `proj_sq_from`, `proj_sq_to` and `promo_bias_proj` are `bias=False` too. The shapes are
    `torch.nn.Linear`'s **`[out, in]`**, so a kernel is a reshape and not a transpose;
    `linear1 [512, 256]` against `linear2 [256, 512]` is the asymmetric pair that makes a
    transposed checkpoint fail here rather than on the device.
    """
    d, ffn = spec["d_model"], spec["ffn"]
    hidden, gen, heads = spec["bias_hidden"], spec["bias_gen"], spec["heads"]
    squares, hid = spec["squares"], spec["head_hidden"]
    want = {
        "smolgen_shared_weight": [squares * squares, gen],
        "elo_embedding_low.weight": [1, spec["elo_dim"]],
        "elo_embedding_high.weight": [1, spec["elo_dim"]],
        "token_projection.weight": [d, spec["input"]],
        "token_projection.bias": [d],
    }
    for index in range(spec["blocks"]):
        at = f"transformer.layers.{index}"
        want[f"{at}.self_attn.mha.in_proj_weight"] = [3 * d, d]
        want[f"{at}.self_attn.mha.out_proj.weight"] = [d, d]
        want[f"{at}.self_attn.sm2.weight"] = [hidden, d]
        want[f"{at}.self_attn.sm2.bias"] = [hidden]
        want[f"{at}.self_attn.ln1.weight"] = [hidden]
        want[f"{at}.self_attn.ln1.bias"] = [hidden]
        want[f"{at}.self_attn.sm3.weight"] = [heads * gen, hidden]
        want[f"{at}.self_attn.sm3.bias"] = [heads * gen]
        want[f"{at}.self_attn.ln2.weight"] = [heads * gen]
        want[f"{at}.self_attn.ln2.bias"] = [heads * gen]
        want[f"{at}.linear1.weight"] = [ffn, d]
        want[f"{at}.linear1.bias"] = [ffn]
        want[f"{at}.linear2.weight"] = [d, ffn]
        want[f"{at}.linear2.bias"] = [d]
        want[f"{at}.norm1.weight"] = [d]
        want[f"{at}.norm2.weight"] = [d]
    want["transformer.norm.weight"] = [d]
    want["transformer.norm.bias"] = [d]
    want["proj_sq_from.weight"] = [hid, d]
    want["proj_sq_to.weight"] = [hid, d]
    want["promo_bias_proj.weight"] = [spec["promotions"], hid]
    # The value and ponder heads. Present, asserted, and deliberately not emitted — see
    # [`CHECKPOINTS`] and [`collect_maia`].
    want["last_ln.weight"] = [d]
    want["last_ln.bias"] = [d]
    want["fc_value_hid.weight"] = [hid, d]
    want["fc_value_hid.bias"] = [hid]
    want["fc_value.weight"] = [3, hid]
    want["fc_value.bias"] = [3]
    want["fc_ponder_hid.weight"] = [hid, d]
    want["fc_ponder_hid.bias"] = [hid]
    want["fc_ponder.weight"] = [1, hid]
    want["fc_ponder.bias"] = [1]
    return want


def collect_maia(get, spec):
    """Maia3-5M's checkpoint as the ordered tensor table `nets::maia` indexes.

    THE TENSOR ORDER (the contract with the Rust net — positional indexing, no names):

        0.  elo_embedding_low.weight   [1, 128]           op "Host"
        1.  elo_embedding_high.weight  [1, 128]           op "Host"
        2.  token_projection           int8 [256, 352, 1, 1] + scale [256] + bias [256]
        5.  smolgen kernel replicated  int8 [32768, 64, 1, 1] + scale [32768] + zero bias
        8.  each block 0..7 in forward order, 30 tensors each, in this exact order:
              sm2 (Linear8, 3), ln1 (LayerNorm, 2), sm3 (Linear8, 3), ln2 (LayerNorm, 2),
              q / k / v / out_proj (Linear8, 3 each — bias synthesised),
              norm1 (RmsNorm, 1), linear1 (Linear8, 3), linear2 (Linear8, 3),
              norm2 (RmsNorm, 1).
        248. transformer.norm (LayerNorm, 2).
        250. proj_sq_from (Linear8, 3 — bias synthesised).
        253. proj_sq_to (Linear8, 3 — bias synthesised).
        256. promo_bias_proj (Linear8, 3 — bias synthesised). Last.

    Totals: 259 tensors in 104 layers, 69 of them int8 convolutions. Everything else — the
    norms, the biases and the two elo tables — stays fp16, and is 1% of the parameters.

    # The two structural rewrites

    * **The fused `in_proj_weight` is split.** Torch packs q, k and v into one `[768, 256]`
      tensor, in that order (`_in_projection_packed` chunks it three ways). Three separate
      `[256, 256, 1, 1]` kernels come out, each with a synthesised zero bias because
      `omit_qkv_biases=True` and `Builder::conv` always reads one.

    * **`smolgen_shared_weight` is replicated eight times.** Upstream expands the bias
      generator's `[8, 64]` output with `einsum("bhi,oi->bho")` against a `[4096, 64]`
      weight. This runtime has no transpose op, so `nets::maia` expresses that as a
      **grouped 1x1 convolution** with `group = 8` — and a grouped convolution's kernel is
      `[out, in/group, 1, 1]`, which here is `[32768, 64, 1, 1]`: group `g`'s 4096 output
      rows must each hold the same `[4096, 64]` weight. `np.tile` along axis 0 puts them in
      exactly the order group `g` reads.

      It costs +1.84M elements, and at int8 that is +1.8 MB and 30% of the file. It is
      replicated **once, not per block**: the parameter is shared, so all eight blocks pass
      tensor 5 as their `weight_index` and the file holds one copy of the replication.

      The quantisation is done on the `[4096, 64]` original and then tiled, not the other way
      round: the two give identical codes because the replicated rows are identical, but
      quantising 262,144 elements instead of 2,097,152 also makes the fidelity line report the
      tensor the checkpoint actually holds.

    # int8, and why not int4

    Every convolution here is int8, quantised per output channel; the norms, the biases and the
    two elo tables stay fp16. 13.3 MiB to 6.8 MiB, with the worst per-tensor cosine at
    0.999941 against a 0.999 gate.

    `scripts/ml/maia_quant_eval.py` is what justifies that, on move agreement rather than on
    cosine: over 400 positions at each shipping rating the int8 model picks a different move
    0.5% to 1.0% of the time, always where the top two legal moves are within 0.017 — inside
    what fp16 rounding does on its own. The same harness rejects int4 at 87% to 91% agreement
    with 14 to 29 genuine changes of mind, at any group size.

    # What is folded, and what is not

    * The attention query scale is **not** folded into the q kernel. Torch scales the query
      by `head_dim ** -0.5` and `head_dim` is 32, which is the `1 / sqrt(head_dim)`
      `attn_scores` already applies. Folding it would apply it twice.
    * The policy head's `1 / sqrt(head_hid_dim)` is not folded either, for the same reason:
      `head_hid_dim` is 256 and `attn_scores(from, to, 1)` derives `1 / sqrt(256)` itself.
    * `promo_bias_proj`'s `* sqrt(head_hid_dim)` is **not** folded into the kernel. It is
      applied by `post::maia` on the host, where the score it is added to has already been
      divided by the same factor — folding one of the pair and not the other is the easy
      mistake, so neither is folded.
    * The elo interpolation is not folded: the two embeddings are emitted raw and blended
      on the host by `nets::maia::elo_embedding`, because the blend weight is an input.
    """
    layers = []
    tensors = []
    fidelity = Fidelity()

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def linear(name, weight=None, bias=None):
        """An int8 kernel as `[out, in, 1, 1]`, its per-output-channel scale, and its bias."""
        weight = get(f"{name}.weight") if weight is None else weight
        outputs, inputs = weight.shape
        synthesised = bias is None
        if synthesised:
            bias = np.zeros(outputs, dtype=np.float32)
        kernel, scale = fidelity.quantise(name, weight.reshape(outputs, inputs, 1, 1))
        tensors.extend([kernel, scale, bias])
        note = " b0=synthesised" if synthesised else ""
        emit(
            "Linear8",
            name,
            f"Linear8 w={list(kernel.shape)} scale={[outputs]} b={[outputs]} "
            f"zp=0 dtype=int8{note}",
            3,
        )

    def layer_norm(name):
        gamma = get(f"{name}.weight")
        beta = get(f"{name}.bias")
        tensors.extend([gamma, beta])
        emit("LayerNorm", name, f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}", 2)

    def rms_norm(name):
        """An RMS norm's gain. One tensor: there is no beta to follow it."""
        gamma = get(f"{name}.weight")
        tensors.append(gamma)
        emit("RmsNorm", name, f"RmsNorm g={list(gamma.shape)}", 1)

    d, heads = spec["d_model"], spec["heads"]
    gen, squares = spec["bias_gen"], spec["squares"]

    for name in ("elo_embedding_low.weight", "elo_embedding_high.weight"):
        table = get(name)
        tensors.append(table)
        emit("Host", name, f"Host t={list(table.shape)}", 1)

    linear("token_projection", bias=get("token_projection.bias"))

    shared = get("smolgen_shared_weight")
    if list(shared.shape) != [squares * squares, gen]:
        raise SystemExit(f"smolgen_shared_weight is {list(shared.shape)}, not {[squares*squares, gen]}")
    # Quantise the original, then tile the codes and the scale. Tiling first would quantise
    # 2,097,152 identical-by-construction rows to reach the same answer, and would report the
    # fidelity of a tensor the checkpoint does not hold.
    codes, scale = fidelity.quantise("smolgen_shared_weight", shared.reshape(*shared.shape, 1, 1))
    replicated = np.tile(codes.reshape(squares * squares, gen), (heads, 1)).reshape(
        heads * squares * squares, gen, 1, 1
    )
    scales = np.tile(scale, heads)
    rows = replicated.shape[0]
    tensors.extend([replicated, scales, np.zeros(rows, dtype=np.float32)])
    emit(
        "Smolgen8",
        "smolgen_shared_weight",
        f"Smolgen8 w={list(replicated.shape)} scale={[rows]} b={[rows]} group={heads} "
        f"from={list(shared.shape)} zp=0 dtype=int8 b0=synthesised",
        3,
    )

    for index in range(spec["blocks"]):
        at = f"transformer.layers.{index}"
        # The bias generator, then attention, then the feed-forward — the order
        # `nets::maia::encoder_block` walks, so the Rust needs no index table.
        linear(f"{at}.self_attn.sm2", bias=get(f"{at}.self_attn.sm2.bias"))
        layer_norm(f"{at}.self_attn.ln1")
        linear(f"{at}.self_attn.sm3", bias=get(f"{at}.self_attn.sm3.bias"))
        layer_norm(f"{at}.self_attn.ln2")

        fused = get(f"{at}.self_attn.mha.in_proj_weight")
        if list(fused.shape) != [3 * d, d]:
            raise SystemExit(f"{at} in_proj_weight is {list(fused.shape)}, not {[3 * d, d]}")
        for part, projection in enumerate(("q_proj", "k_proj", "v_proj")):
            linear(f"{at}.self_attn.{projection}", weight=fused[part * d : (part + 1) * d])
        linear(f"{at}.self_attn.mha.out_proj")

        # Post-norm: `norm1` closes the attention residual and `norm2` the feed-forward's,
        # so both come after the sublayer they normalise rather than before it.
        rms_norm(f"{at}.norm1")
        linear(f"{at}.linear1", bias=get(f"{at}.linear1.bias"))
        linear(f"{at}.linear2", bias=get(f"{at}.linear2.bias"))
        rms_norm(f"{at}.norm2")

    layer_norm("transformer.norm")
    linear("proj_sq_from")
    linear("proj_sq_to")
    linear("promo_bias_proj")

    fidelity.report()
    return layers, tensors


INVENTORIES.update({"small100": small100_inventory, "whisper": whisper_inventory})
COLLECTORS.update({"small100": collect_small100, "whisper": collect_whisper})
INVENTORIES.update({"nllb600": nllb_inventory})
COLLECTORS.update({"nllb600": collect_nllb})
INVENTORIES.update({"maia": maia_inventory})
COLLECTORS.update({"maia": collect_maia})


# The vision position table, after TinyCLIP's export constant-folds it: `position_ids` is a
# constant, so the `Gather` that reads the table folds away and its `[1, 197, 256]` result is the
# initializer. There is no `vision_model.embeddings.position_embedding.weight` to read.
#
# Named here because it is the one parameter whose key is an internal tensor name rather than a
# module path, and both [`tinyclip_inventory`] and [`collect_tinyclip`] have to agree about it.
VISION_POSITIONS_FOLDED = "/vision_model/embeddings/position_embedding/Gather_output_0"


def tinyclip_inventory(spec):
    """What TinyCLIP's export must hold, as two dicts keyed the two ways the graph names things.

    Returned rather than transcribed: a list of 250 names would be checked against itself.

    * `parameters` is `{initializer name: dims}` for everything the export names — the norms, the
      biases, the patch kernel, both embedding tables and the class token.
    * `projections` is `{MatMul node name: dims}` for the 80 anonymous weights. Keyed by node
      because the initializer names carry no information; see [`ARCHITECTURES`].

    `dims` for a projection is ONNX's own **`[in, out]`**, so this dict is also the statement that
    catches a transposed read: `fc1` at `[256, 1024]` and `fc2` at `[1024, 256]` cannot both be
    satisfied by a checkpoint whose linears are stored the other way round.
    """
    width, ffn, projection = spec["width"], spec["ffn"], spec["projection"]
    parameters = {
        "vision_model.embeddings.patch_embedding.weight": [
            width, 3, spec["patch"], spec["patch"],
        ],
        "vision_model.embeddings.class_embedding": [width],
        VISION_POSITIONS_FOLDED: [1, spec["grid"] ** 2 + 1, width],
        "vision_model.pre_layrnorm.weight": [width],
        "vision_model.pre_layrnorm.bias": [width],
        "vision_model.post_layernorm.weight": [width],
        "vision_model.post_layernorm.bias": [width],
        "text_model.embeddings.token_embedding.weight": [spec["vocab"], width],
        "text_model.embeddings.position_embedding.weight": [spec["context"], width],
        "text_model.final_layer_norm.weight": [width],
        "text_model.final_layer_norm.bias": [width],
    }
    projections = {
        "/visual_projection/MatMul": [width, projection],
        "/text_projection/MatMul": [width, projection],
    }
    for tower, count in (("vision", spec["vision_layers"]), ("text", spec["text_layers"])):
        for index in range(count):
            at = f"{tower}_model.encoder.layers.{index}"
            node = f"/{tower}_model/encoder/layers.{index}"
            for norm in ("layer_norm1", "layer_norm2"):
                parameters[f"{at}.{norm}.weight"] = [width]
                parameters[f"{at}.{norm}.bias"] = [width]
            for name in ("q_proj", "k_proj", "v_proj", "out_proj"):
                parameters[f"{at}.self_attn.{name}.bias"] = [width]
                projections[f"{node}/self_attn/{name}/MatMul"] = [width, width]
            parameters[f"{at}.mlp.fc1.bias"] = [ffn]
            parameters[f"{at}.mlp.fc2.bias"] = [width]
            projections[f"{node}/mlp/fc1/MatMul"] = [width, ffn]
            projections[f"{node}/mlp/fc2/MatMul"] = [ffn, width]
    return parameters, projections


def check_architecture(model, graph_id_name, spec):
    """Refuse an ONNX export that is not the architecture `nets/<graph>.rs` hardcodes.

    The counterpart of [`check_checkpoint`], and the same argument: a wrong shape here is a wrong
    forward pass rather than a load failure, so it is asserted before anything is written. The op
    counts in [`EXPECTED_OPS`] are checked as well, by [`check_graph`] — this is the stricter of
    the two, because it fails on a renamed or resized parameter as well as on a missing one.

    `spec` is passed rather than looked up, as [`collect_tinyclip`]'s is, so the two cannot be
    given different ones.
    """
    parameters, projections = tinyclip_inventory(spec)
    inits = {i.name: list(i.dims) for i in model.graph.initializer}
    nodes = {n.name: n for n in model.graph.node}

    lines = []
    for name, want in sorted(parameters.items()):
        got = inits.get(name)
        if got != want:
            lines.append(f"  {name}: {got} against {want}")
    for node_name, want in sorted(projections.items()):
        node = nodes.get(node_name)
        if node is None:
            lines.append(f"  {node_name}: no such node")
            continue
        held = [x for x in node.input if x in inits]
        if len(held) != 1 or inits[held[0]] != want:
            lines.append(f"  {node_name}: {[inits.get(h) for h in held]} against {want}")
    if lines:
        raise SystemExit(
            f"{graph_id_name}: the export is not the architecture "
            f"nets/{graph_id_name}.rs\nhardcodes. A wrong shape here is a wrong forward pass, "
            "not a load failure:\n" + "\n".join(lines[:12])
        )
    print(
        f"{len(parameters)} named parameters and {len(projections)} projections match the "
        f"{graph_id_name} architecture"
    )


def collect_tinyclip(model, spec):
    """TinyCLIP's export as the ordered tensor table `nets::tinyclip` indexes.

    Order is the contract, and it is forward order **per tower**, vision first:

        the patch kernel, the class token, the vision positions, `pre_layrnorm`
        each vision layer, then `post_layernorm`, then `visual_projection`
        the token embedding, the text positions
        each text layer, then `final_layer_norm`, then `text_projection`

    The export interleaves the two towers; this does not. See [`ARCHITECTURES`].

    # What is transposed, and what is not

    * A **`MatMul`** weight is `[in, out]` and becomes `[out, in, 1, 1]`, so every projection is
      the `1 x 1` convolution `Builder::conv_int8` already computes.
    * The **`Conv`** patch kernel is `[M, C, kH, kW]` already, which is this runtime's own layout,
      so it is emitted **verbatim**. The `[256, 3, 16, 16]` assertion in [`check_architecture`] is
      what says so; a transpose here would be a plausible-looking embedding.
    * The two **position tables** are `[positions, width]` and the vision one becomes
      `[width, 1, positions]`, because it is added to a sequence the device holds channel-major.
      The text one is **not** transposed: the host reads it, gathers the token rows and adds the
      two in f32, exactly as `nets::small100::embed_positions` does, so it never reaches a shader.

    # What has no bias, and what gets a synthesised one

    The patch embedding and both projections have none — `bias=False` on all three in
    `CLIPModel`. The shaders always add one, so each gets zeros, which is exact and is recorded in
    the digest key so an export that grows a bias is not read as this table.
    """
    width = spec["width"]
    inits = {i.name: i for i in model.graph.initializer}
    nodes = {n.name: n for n in model.graph.node}
    layers = []
    tensors = []
    fidelity = Fidelity()

    def array(name):
        return numpy_helper.to_array(inits[name]).astype(np.float32)

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def matmul_weight(node_name):
        """The `[in, out]` initializer a projection's `MatMul` reads, by **node** name."""
        held = [x for x in nodes[node_name].input if x in inits]
        return numpy_helper.to_array(inits[held[0]]).astype(np.float32)

    def projection(node_name, name):
        """A `MatMul` as an int8 `[out, in, 1, 1]` kernel, its per-output scale and a zero bias."""
        weight = matmul_weight(node_name)
        inputs, outputs = weight.shape
        kernel, scale = fidelity.quantise(name, weight.T.reshape(outputs, inputs, 1, 1))
        tensors.extend([kernel, scale, np.zeros(outputs, dtype=np.float32)])
        emit(
            "Linear8",
            name,
            f"Linear8 w={list(kernel.shape)} scale={[outputs]} b={[outputs]} "
            "zp=0 dtype=int8 b0=synthesised",
            3,
        )

    def linear(node_name, bias_name, name):
        """[`projection`] with the export's own bias, which every layer projection has."""
        weight = matmul_weight(node_name)
        bias = array(bias_name)
        inputs, outputs = weight.shape
        kernel, scale = fidelity.quantise(name, weight.T.reshape(outputs, inputs, 1, 1))
        tensors.extend([kernel, scale, bias])
        emit(
            "Linear8",
            name,
            f"Linear8 w={list(kernel.shape)} scale={[outputs]} b={list(bias.shape)} "
            "zp=0 dtype=int8",
            3,
        )

    def layer_norm(name):
        gamma = array(f"{name}.weight")
        beta = array(f"{name}.bias")
        tensors.extend([gamma, beta])
        emit("LayerNorm", name, f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}", 2)

    def constant(name, values):
        tensors.append(values)
        emit("Constant", name, f"Constant t={list(values.shape)}", 1)

    def encoder_layer(tower, index):
        at = f"{tower}_model.encoder.layers.{index}"
        node = f"/{tower}_model/encoder/layers.{index}"
        # Pre-norm, as `CLIPEncoderLayer` is: the norm comes before the sublayer it feeds and the
        # residual skips both. Emitting in forward order is what lets the Rust walk the table.
        layer_norm(f"{at}.layer_norm1")
        for name in ("q_proj", "k_proj", "v_proj", "out_proj"):
            linear(
                f"{node}/self_attn/{name}/MatMul",
                f"{at}.self_attn.{name}.bias",
                f"{at}.self_attn.{name}",
            )
        layer_norm(f"{at}.layer_norm2")
        linear(f"{node}/mlp/fc1/MatMul", f"{at}.mlp.fc1.bias", f"{at}.mlp.fc1")
        linear(f"{node}/mlp/fc2/MatMul", f"{at}.mlp.fc2.bias", f"{at}.mlp.fc2")

    # --- the vision tower ---
    patch = "vision_model.embeddings.patch_embedding.weight"
    kernel, scale = fidelity.quantise(patch, array(patch))
    tensors.extend([kernel, scale, np.zeros(width, dtype=np.float32)])
    emit(
        "Conv8",
        patch,
        f"Conv8 w={list(kernel.shape)} scale={[width]} b={[width]} "
        f"k={[spec['patch'], spec['patch']]} s={[spec['patch'], spec['patch']]} "
        "p=[0, 0, 0, 0] zp=0 dtype=int8 b0=synthesised",
        3,
    )
    # `[width]` as the `[width, 1, 1]` a `Kind::Constant` wants: one position, every channel.
    token = array("vision_model.embeddings.class_embedding").reshape(width, 1, 1)
    constant("vision_model.embeddings.class_embedding", token)
    positions = np.squeeze(array(VISION_POSITIONS_FOLDED))
    constant(VISION_POSITIONS_FOLDED, positions.T.reshape(width, 1, positions.shape[0]).copy())
    layer_norm("vision_model.pre_layrnorm")
    for index in range(spec["vision_layers"]):
        encoder_layer("vision", index)
    layer_norm("vision_model.post_layernorm")
    projection("/visual_projection/MatMul", "visual_projection")

    # --- the text tower ---
    table = "text_model.embeddings.token_embedding.weight"
    rows = array(table)
    # Per **row**, which is per token: the host gathers one row and dequantises it by that row's
    # own scale, so this is the same shape of table `collect_small100`'s tied embedding is. The
    # export's own int8 file quantises it per *tensor* with a zero point of 226, which is neither
    # representable by `conv_int8.comp` nor recoverable by requantising.
    kernel, scale = fidelity.quantise(table, rows.reshape(rows.shape[0], rows.shape[1], 1, 1))
    tensors.extend([kernel, scale])
    emit(
        "Embed8",
        table,
        f"Embed8 t={list(kernel.shape)} scale={[rows.shape[0]]} zp=0 dtype=int8",
        2,
    )
    # Not transposed: the host reads this one. See the docstring.
    text_positions = array("text_model.embeddings.position_embedding.weight")
    constant("text_model.embeddings.position_embedding.weight", text_positions)
    for index in range(spec["text_layers"]):
        encoder_layer("text", index)
    layer_norm("text_model.final_layer_norm")
    projection("/text_projection/MatMul", "text_projection")

    fidelity.report()
    return layers, tensors


def quantise_per_channel(kernel):
    """An fp32 kernel as `(int8, scale)`, symmetric and absmax, one scale per output channel.

    Every int8 tensor in this tree goes through here. Nothing reads a pre-quantised export:
    Supertonic's are fp32, and SMaLL-100's only int8 export quantises per tensor, which throws
    away the resolution this recovers - see [`CHECKPOINTS`].

    # Symmetric, with no zero point

    `conv_int8.comp` and `conv_point_int8.comp` compute `scale[o] * sum(int8_w * in)`: there is
    nowhere for a zero point to go, and adding one would cost a second pass over the taps to
    subtract `zp * sum(in)`. So the scale is `absmax / 127` and 0 maps to 0, which is also what
    onnxruntime's dynamic quantiser was measured to emit for SMaLL-100 — every entry of every
    weight zero point in that export is 0.

    127 rather than 128: the range is made symmetric by giving up the one extra negative code, so
    that `-absmax` and `+absmax` both round to a representable value. Using 128 would let a
    weight at exactly `+absmax` quantise to 128, which does not exist in int8.

    # Per output channel, not per tensor

    A row of the kernel is one output channel, and the scale multiplies that row's finished
    accumulator, so a scale per row costs nothing over a scalar — see `Builder::conv_int8`. It
    buys a great deal: a layer whose channels differ in range by 100x loses most of its
    resolution on the quiet ones under a single tensor-wide scale.

    # An all-zero channel

    Its absmax is 0, and `0 / 127` is a scale of 0, which is *correct* — every weight in the row
    is 0, so any scale reproduces it — but it is also a division by zero when computing the
    codes. Those rows are given a scale of 1 instead, which is exact for the same reason and is
    finite.
    """
    kernel = np.ascontiguousarray(kernel, dtype=np.float32)
    if kernel.ndim < 1:
        raise SystemExit(f"rank {kernel.ndim} kernel; quantisation needs an output axis")
    if not np.isfinite(kernel).all():
        raise SystemExit("a kernel holding a non-finite value cannot be quantised")
    rows = kernel.reshape(kernel.shape[0], -1)
    absmax = np.abs(rows).max(axis=1)
    scale = np.where(absmax > 0, absmax / 127.0, 1.0).astype(np.float32)
    # Round half away from zero, as `np.round` does not: it rounds half to even, which biases a
    # symmetric weight distribution towards the even codes.
    codes = np.floor(np.abs(rows) / scale[:, None] + 0.5) * np.sign(rows)
    quantised = np.clip(codes, -127, 127).astype(np.int8).reshape(kernel.shape)
    # The scale is stored as fp16 beside the weights, so the reconstruction the shader performs
    # uses the *rounded* scale. Rounding it here means the error reported below is the error the
    # device will have, not an optimistic one.
    scale = scale.astype(np.float16).astype(np.float32)
    return quantised, scale


def build(layers, tensors, graph_id, onnx_sha256):
    """Serialise the tensor table and the data section.

    A tensor is fp16 unless it arrives as `int8`, in which case its bytes go through
    unchanged and the entry records [`DTYPE_I8`]. Its scale is a separate fp16 tensor that the
    caller has already placed after it.
    """
    table = bytearray()
    data = bytearray()
    for tensor in tensors:
        if tensor.ndim < 1 or tensor.ndim > 4:
            raise SystemExit(f"rank {tensor.ndim} tensor; the table holds 1..4")
        while len(data) % ALIGNMENT:
            data.append(0)
        offset = len(data)
        if tensor.dtype == np.int8:
            # Already quantised, so nothing to round: the bytes are the payload.
            dtype = DTYPE_I8
            data.extend(tensor.tobytes())
        else:
            dtype = DTYPE_F16
            half = np.ascontiguousarray(tensor, dtype=np.float32).astype(np.float16)
            if not np.isfinite(half).all():
                raise SystemExit(
                    f"a weight of shape {list(tensor.shape)} overflows fp16. "
                    "Storing this net at half precision needs per-tensor scaling."
                )
            data.extend(half.tobytes())
        dims = list(tensor.shape) + [0] * (4 - tensor.ndim)
        table.extend(struct.pack("<I", tensor.ndim))
        table.extend(struct.pack("<4I", *dims))
        table.extend(struct.pack("<III", dtype, offset, tensor.size))
    assert len(table) == len(tensors) * TENSOR_ENTRY_BYTES

    data_offset = HEADER_BYTES + len(table)
    header = bytearray()
    header.extend(MAGIC)
    header.extend(struct.pack("<III", FORMAT_VERSION, graph_id, len(tensors)))
    header.extend(onnx_sha256)
    header.extend(struct.pack("<II", data_offset, len(data)))
    header.extend(b"\0" * 8)
    assert len(header) == HEADER_BYTES

    return bytes(header) + bytes(table) + bytes(data), len(layers)


def main():
    parser = argparse.ArgumentParser(description=SPEC.splitlines()[0])
    parser.add_argument("model", help="an ONNX export, or a .safetensors for a CHECKPOINTS graph")
    parser.add_argument("--graph", required=True, choices=sorted(GRAPHS))
    parser.add_argument("-o", "--out", help="where to write the .maml")
    parser.add_argument("--check", action="store_true", help="validate only")
    parser.add_argument("--print-layers", action="store_true")
    parser.add_argument("--print-digest", action="store_true")
    args = parser.parse_args()

    source_sha256 = hashlib.sha256()
    with open(args.model, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            source_sha256.update(block)
    source_size = os.path.getsize(args.model)

    if args.graph in CHECKPOINTS:
        get, shapes = open_checkpoint(args.model)
        check_checkpoint(shapes, args.graph)
        layers, tensors = COLLECTORS[args.graph](get, CHECKPOINTS[args.graph])
    elif args.graph in ARCHITECTURES:
        model = onnx.load(args.model)
        check_graph(model, args.graph)
        check_architecture(model, args.graph, ARCHITECTURES[args.graph])
        layers, tensors = collect_tinyclip(model, ARCHITECTURES[args.graph])
    else:
        model = onnx.load(args.model)
        check_graph(model, args.graph)
        layers, tensors = collect_layers(
            model, MODULES.get(args.graph), quantise=args.graph in INT8_GRAPHS
        )

    digest = layer_table_digest(layers)
    if args.print_digest:
        print(f'    "{args.graph}": "{digest}",')
    pinned = EXPECTED_DIGEST[args.graph]
    if digest != pinned:
        message = (
            f"layer table digest {digest}\n"
            f"           pinned  {pinned}\n"
            "The ordered Conv/ConvTranspose table changed, so the hardcoded forward\n"
            f"pass in library/ml/src/main/rust/src/nets/{args.graph}.rs no longer\n"
            "matches this export. Re-read it with --print-layers, update the Rust,\n"
            "then re-pin with --print-digest."
        )
        if not args.print_digest:
            raise SystemExit(message)
        print(message, file=sys.stderr)

    if args.print_layers:
        for layer in layers:
            print(f"{layer.index:4d} t{layer.first_tensor:<4d} {layer.key()}  # {layer.name}")

    blob, count = build(layers, tensors, GRAPHS[args.graph], source_sha256.digest())
    if args.out:
        with open(args.out, "wb") as f:
            f.write(blob)
    print(
        f"{args.graph}: {count} layers, {len(tensors)} tensors, "
        f"{len(blob)} bytes ({source_size} fp32 in)"
    )


if __name__ == "__main__":
    main()
