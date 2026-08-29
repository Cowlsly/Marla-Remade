#!/usr/bin/env python3
"""Supertonic 3's ONNX nets to `.maml`, folding their per-channel affines away.

`nets::supertonic_vocoder` is a hardcoded forward pass of convolutions, layer norms, GELUs and
residual adds — and nothing else. The export has three per-channel affines that are not any of
those, and rather than adding an op for each, all three fold into a convolution:

    input   Mul by [24] then Add by [24]      -> folds into `embed`
    block   Mul by a [512] layer scale        -> folds into that block's `pwconv2`
    tail    BatchNormalization over [512]     -> folds into `head/layer1`

# Why the fold is legal here and not in ppocr_fold.py

`conv(a x + b)` is `conv_{aW}(x) + sum(W b)` at an interior position, and the constant term is a
per-output-channel bias. At a *border* it depends on what the padding supplies. With zero padding
the pad contributes `0` rather than `b`, the fold is wrong at the edge, and
`ppocr_fold.fold_affine_into_convs` refuses padded convolutions for exactly that reason — it was
a real bug in a shipped asset.

Every convolution in this vocoder is preceded by an ONNX `Pad` with **`mode=edge`**. A replicated
border supplies `a x_border + b`, which is what the fold assumes, so it holds everywhere. The
`pwconv2` case is unconditional anyway: a 1x1 has no border.

# Two smaller shape fixes

* `head/act` is a `PRelu` with a single shared slope. The runtime's `Act::PRelu` reads one slope
  per channel, so the scalar is widened to 2048 copies. 4 KB, and it keeps the shader branchless.
* `head/layer2` has no bias in the export. A zero one is synthesised, as `maml_convert`
  already does for a biasless convolution.

# The duration predictor

`--graph supertonic_dp` collects `duration_predictor.onnx` instead, which needs three fixes of
its own rather than the vocoder's three:

    sentence_token [1, 64, 1]  -> appended to the embedding table as its last row
    block gamma [1, 64, 1]     -> folds into that block's `pwconv2`
    proj_out                   -> a zero bias is synthesised

Prepending a learned token to the sequence is a `Concat` this runtime has no op for along the
sequence axis, and needs none: the token becomes row 8322 of the table and the caller prepends
that id. See `nets::supertonic_duration`.

Unlike the vocoder this graph is **not** entirely edge-padded — twelve of its eighteen `Pad`s are
the relative-attention skew and are `constant`. What matters is that every pad feeding a
*convolution* replicates, which is checked separately.

# The text encoder

`--graph supertonic_ttl` is the same shape again, four times wider, with two additions:

    block gamma [1, 256, 1]     -> folds into that block's `pwconv2`, as above
    tanh(W_key . style_key + b) -> folded to one `[256, 50]` tensor per style attention
    W_query                     -> scaled by sqrt(128)/16, since the export's own scale is
                                   sqrt(256) and `Builder::attn_scores` uses sqrt(head_dim)

Every projection in its style path is a `MatMul` whose weight is `[in, out]`, so all eight are
**transposed** here. They are all 256x256, where a missed transpose is invisible in the shape.

Usage:
    ./scripts/ml/supertonic_fold.py vocoder.onnx --graph supertonic_voc -o vocoder.maml
    ./scripts/ml/supertonic_fold.py duration_predictor.onnx --graph supertonic_dp -o dp.maml
    ./scripts/ml/supertonic_fold.py text_encoder.onnx --graph supertonic_ttl -o text.maml
    ./scripts/ml/supertonic_fold.py vector_estimator.onnx --graph supertonic_ve -o sampler.maml
"""

import argparse
import hashlib
import os
import sys

import numpy as np
import onnx
from onnx import numpy_helper

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import maml_convert  # noqa: E402  (needs the path above)

# The vocoder's stack width, and the widening inside each block.
CHANNELS = 512
INNER = CHANNELS * 4

# The duration predictor's, and the counts `nets::supertonic_duration` hardcodes.
DP_CHANNELS = 64
DP_INNER = 256
DP_BLOCKS = 6
DP_ATTN_LAYERS = 2
DP_HIDDEN = 128
DP_PREFIX = "tts.dp.sentence_encoder"

# The text encoder's, likewise.
TE_CHANNELS = 256
TE_INNER = 1024
TE_BLOCKS = 6
TE_ATTN_LAYERS = 4
TE_STYLE_TOKENS = 50
TE_STYLE_HEADS = 2
TE_PREFIX = "tts.ttl.text_encoder"
TE_STYLE_PREFIX = "tts.ttl.speech_prompted_text_encoder"
TE_STYLE_KEY = "tts.ttl.style_encoder.style_token_layer.style_key"

# The sampler's. `VE_PREFIX` is where every one of its parameters lives.
VE_LATENT = 144
VE_CHANNELS = 512
VE_INNER = 2048
VE_TEXT = 256
VE_STYLE = 256
VE_STYLE_TOKENS = 50
VE_TEXT_HEADS = 8
VE_STYLE_HEADS = 2
VE_TIME = 64
VE_TIME_INNER = 256
VE_MAIN_BLOCKS = 4
VE_LEADING = [1, 2, 4, 8]
VE_TRAILING = 4
VE_PREFIX = "vector_estimator.tts.ttl.vector_field"
VE_UNCOND = "vector_estimator.tts.ttl.uncond_masker"


def constants(graph):
    """Every initializer and `Constant` node output, by name."""
    held = {i.name: numpy_helper.to_array(i) for i in graph.initializer}
    for node in graph.node:
        if node.op_type == "Constant":
            for attribute in node.attribute:
                if attribute.name == "value":
                    held[node.output[0]] = numpy_helper.to_array(attribute.t)
    return held


def edge_padded(graph):
    """Whether every `Pad` in the graph replicates its border.

    Checked rather than assumed: the folds below are only valid for `edge`, and a future export
    that switched to `constant` would otherwise fold silently and wrongly.
    """
    modes = set()
    for node in graph.node:
        if node.op_type != "Pad":
            continue
        mode = "constant"
        for attribute in node.attribute:
            if attribute.name == "mode":
                mode = attribute.s.decode()
        modes.add(mode)
    return modes == {"edge"}


def convolutions_edge_padded(graph):
    """Whether every `Pad` that feeds a convolution replicates its border.

    Laxer than [`edge_padded`], and it has to be: the duration predictor's relative-attention
    skew spends twelve `constant` pads on `Slice`s and `Reshape`s, which are not convolutions and
    are not transcribed. The six that matter are the depthwise ones.
    """
    consumers = {}
    for node in graph.node:
        for name in node.input:
            consumers.setdefault(name, []).append(node)
    for node in graph.node:
        if node.op_type != "Pad":
            continue
        if not any(c.op_type == "Conv" for c in consumers.get(node.output[0], [])):
            continue
        mode = "constant"
        for attribute in node.attribute:
            if attribute.name == "mode":
                mode = attribute.s.decode()
        if mode != "edge":
            return False
    return True


def fold_into(weight, bias, scale, shift):
    """`conv(scale * x + shift)` as a convolution on `x`.

    `weight` is `[out, in, k]`, `scale` and `shift` are per **input** channel. The scale rides on
    the weight; the shift becomes a constant per output channel, which is where the `k` sum
    comes from.
    """
    scaled = weight * scale.reshape(1, -1, 1)
    added = (weight * shift.reshape(1, -1, 1)).sum(axis=(1, 2))
    return scaled, bias + added


def fold_output(weight, bias, scale, shift):
    """`scale * conv(x) + shift` as a convolution, for a per-**output**-channel affine."""
    return weight * scale.reshape(-1, 1, 1), bias * scale + shift


def batchnorm_affine(node, held, epsilon=1e-5):
    """A `BatchNormalization`'s inference-time per-channel scale and shift."""
    gamma, beta, mean, variance = (held[name] for name in node.input[1:5])
    for attribute in node.attribute:
        if attribute.name == "epsilon":
            epsilon = attribute.f
    scale = gamma / np.sqrt(variance + epsilon)
    return scale, beta - mean * scale


def collect(graph):
    """The vocoder's layers and tensors, folded, in the order the Rust reads them."""
    held = constants(graph)
    if not edge_padded(graph):
        raise SystemExit(
            "this export does not pad every convolution with mode=edge, so the affine folds "
            "below are not valid; see the module docstring"
        )

    # The input affine, which precedes `embed`.
    input_scale = input_shift = None
    for node in graph.node:
        if node.op_type in ("Mul", "Add"):
            sized = [i for i in node.input if i in held and held[i].size == 24]
            if not sized:
                continue
            value = np.asarray(held[sized[0]], dtype=np.float64).reshape(-1)
            if node.op_type == "Mul" and input_scale is None:
                input_scale = value
            elif node.op_type == "Add" and input_shift is None:
                input_shift = value
    if input_scale is None or input_shift is None:
        raise SystemExit("no [24] input affine found ahead of embed")

    # And a scalar divisor at the very head - `normalizer.scale` in tts.json. Folded into the
    # per-channel scale rather than left for the runtime: dividing by it is multiplying the
    # affine''s slope, and the shift is applied after the division so it is untouched.
    divisor = None
    for node in graph.node:
        if node.op_type == "Div":
            scalars = [i for i in node.input if i in held and np.size(held[i]) == 1]
            if scalars:
                divisor = float(np.asarray(held[scalars[0]]).reshape(-1)[0])
                break
    if divisor is None or divisor == 0.0:
        raise SystemExit("no scalar Div found at the head of the graph")
    input_scale = input_scale / divisor

    # Each block's layer scale, and the tail's BatchNormalization.
    layer_scales = []
    for node in graph.node:
        if node.op_type == "Mul" and "convnext" in node.name:
            sized = [i for i in node.input if i in held and held[i].size == CHANNELS]
            if sized:
                layer_scales.append(np.asarray(held[sized[0]], dtype=np.float64).reshape(-1))
    norm = next((n for n in graph.node if n.op_type == "BatchNormalization"), None)
    if norm is None:
        raise SystemExit("no BatchNormalization found ahead of head/layer1")
    tail_scale, tail_shift = batchnorm_affine(norm, held)

    layers, tensors = [], []
    scales = iter(layer_scales)

    def add(op, name, weight, bias, key, int8=False):
        first = len(tensors)
        if int8:
            # The three-tensor layout `Builder::conv_int8` reads: kernel, per-output-channel
            # scale, bias. `Table.conv` emits the same thing for the other graphs; this one
            # cannot use it because it walks ONNX nodes rather than named parameters.
            quantised, kernel_scale = maml_convert.quantise_per_channel(weight)
            tensors.append(quantised)
            tensors.append(np.ascontiguousarray(kernel_scale, dtype=np.float32))
            tensors.append(np.ascontiguousarray(bias, dtype=np.float32))
            layers.append(maml_convert.Layer(len(layers), op, name, key, first, 3))
            return
        tensors.append(np.ascontiguousarray(weight, dtype=np.float32))
        tensors.append(np.ascontiguousarray(bias, dtype=np.float32))
        layers.append(maml_convert.Layer(len(layers), op, name, key, first, 2))

    for node in graph.node:
        if node.op_type == "LayerNormalization":
            # Each block's norm sits between its depthwise and its `pwconv1`, so walking the
            # graph in order puts its pair in exactly the right place in the table.
            gamma = np.asarray(held[node.input[1]], dtype=np.float64)
            beta = np.asarray(held[node.input[2]], dtype=np.float64)
            add(
                "LayerNorm",
                node.name,
                gamma,
                beta,
                f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}",
            )
            continue
        if node.op_type != "Conv":
            continue
        weight = np.asarray(held[node.input[1]], dtype=np.float64)
        if len(node.input) > 2:
            bias = np.asarray(held[node.input[2]], dtype=np.float64)
        else:
            # `head/layer2` has none; the Rust reads a pair for every layer.
            bias = np.zeros(weight.shape[0], dtype=np.float64)
        note = ""

        if "embed" in node.name:
            weight, bias = fold_into(weight, bias, input_scale, input_shift)
            note = " folded=input_affine"
        elif "pwconv2" in node.name:
            weight, bias = fold_output(weight, bias, next(scales), 0.0)
            note = " folded=layer_scale"
        elif "head/layer1" in node.name:
            weight, bias = fold_into(weight, bias, tail_scale, tail_shift)
            note = " folded=final_norm"
        elif len(node.input) == 2:
            note = " b0=synthesised"

        lifted, _ = maml_convert.lift_to_2d(weight, node)
        group = 1
        for attribute in node.attribute:
            if attribute.name == "group":
                group = attribute.i
        # The two `1 x 1`s per block and `head/layer2` are read as int8; `embed`, the depthwise
        # convolutions and `head/layer1` cannot be. See `nets::supertonic_vocoder::INT8_CONVS`,
        # which spells out why for each, and must agree with this list.
        int8 = any(
            marker in node.name for marker in ("pwconv1", "pwconv2", "head/layer2")
        )
        if int8 and (group != 1 or tuple(lifted.shape[2:]) != (1, 1)):
            raise SystemExit(
                f"{node.name}: marked int8 but it is g={group} k={list(lifted.shape[2:])};"
                " only an ungrouped 1x1 can be quantised"
            )
        key = (
            f"{'ConvInt8' if int8 else 'Conv'} w={list(lifted.shape)} b={list(bias.shape)}"
            f" g={group} pad=edge{note}{' zp=0 dtype=int8' if int8 else ''}"
        )
        add("ConvInt8" if int8 else "Conv", node.name, lifted, bias, key, int8=int8)

        # The PReLU sits between the two head convolutions, and its slope is one tensor.
        if "head/layer1" in node.name:
            prelu = next(n for n in graph.node if n.op_type == "PRelu")
            slope = np.asarray(held[prelu.input[1]], dtype=np.float64).reshape(-1)
            if slope.size != 1:
                raise SystemExit(f"{prelu.name}: a {slope.size}-element slope, expected one")
            widened = np.full((INNER, 1, 1), float(slope[0]), dtype=np.float32)
            first = len(tensors)
            tensors.append(widened)
            layers.append(
                maml_convert.Layer(
                    len(layers),
                    "PRelu",
                    prelu.name,
                    f"PRelu s={list(widened.shape)} shared={float(slope[0]):.6f}",
                    first,
                    1,
                )
            )

    remaining = list(scales)
    if remaining:
        raise SystemExit(f"{len(remaining)} layer scales were never folded into a pwconv2")
    return layers, tensors


class Table:
    """The `.maml` layer table under construction, for a graph collected by parameter name.

    The vocoder is a plain stack where walking the ONNX nodes lands each tensor in the right
    slot. The duration predictor and the text encoder are not: each attention layer interleaves
    two relative-position tables, several mask multiplies and a twenty-node skew, so naming each
    tensor is both shorter and checkable against the Rust line by line.
    """

    def __init__(self, held):
        self.held = held
        self.layers = []
        self.tensors = []

    def at(self, name):
        if name not in self.held:
            raise SystemExit(f"{name} is not in this export")
        return np.asarray(self.held[name], dtype=np.float64)

    def add(self, op, name, key, *values):
        first = len(self.tensors)
        for value in values:
            # int8 goes through as int8: `maml_convert.build` switches on the dtype to decide the
            # payload, and casting a quantised kernel to fp32 here would make it fp16 in the file
            # and silently double the size this exists to halve.
            if getattr(value, "dtype", None) == np.int8:
                self.tensors.append(np.ascontiguousarray(value))
            else:
                self.tensors.append(np.ascontiguousarray(value, dtype=np.float32))
        self.layers.append(
            maml_convert.Layer(len(self.layers), op, name, key, first, len(values))
        )

    def quantised_conv(self, name, key, weight, bias):
        """Add a convolution whose kernel is quantised, from arrays already in hand.

        The int8 half of [`conv`], factored out because the sampler's attention projections come
        from `MatMul` weights rather than a named `.weight` - they are transposed, and the query has
        a scale folded in - so they cannot go through `conv` but must emit the identical
        three-tensor layout. Two copies of that layout would be two chances to order the scale and
        the bias differently, and they are the same length, so nothing downstream would notice.
        """
        quantised, kernel_scale = maml_convert.quantise_per_channel(weight)
        self.add("ConvInt8", name, f"{key} zp=0 dtype=int8", quantised, kernel_scale, bias)

    def conv(self, name, out_channels, fold_scale=None, synthesise_bias=False,
             pads=(0, 0, 0, 0), kernel=(1, 1), group=1, transpose=False, scale=None,
             int8=False):
        """One convolution's pair, lifted to the rank-4 `[out, in, 1, k]` the Rust indexes.

        `transpose` is for the style path's `MatMul`s, whose weight is `[in, out]` — all eight of
        them are 256x256, where a missed transpose is invisible in the shape.

        `int8` emits **three** tensors rather than two — the quantised kernel, its
        per-output-channel scale, then the bias — for `Builder::conv_int8` rather than
        `Builder::conv`. It shifts every later tensor index by one, which is why it is opt-in per
        call site: the Rust side has to move with it.

        The caller decides, because the runtime cannot quantise everything. An int8 convolution
        has no edge padding and refuses `Act::PRelu`, so only the ungrouped `1 x 1`s are eligible
        — which is where 92% of the parameters are anyway.
        """
        weight = self.at(f"{name}.weight")
        if synthesise_bias:
            bias = np.zeros(out_channels, dtype=np.float64)
        else:
            bias = self.at(f"{name}.bias")
        if transpose:
            weight = weight.T
        if fold_scale is not None:
            weight, bias = fold_output(weight, bias, fold_scale, 0.0)
        if scale is not None:
            weight, bias = weight * scale, bias * scale
        if weight.ndim == 2:
            # A `Gemm`'s `[out, in]`, at `transB=1`, is already the convolution's layout.
            weight = weight.reshape(weight.shape[0], weight.shape[1], 1, 1)
        elif weight.ndim == 3:
            weight = weight.reshape(weight.shape[0], weight.shape[1], 1, weight.shape[2])
        note = " b0=synthesised" if synthesise_bias else ""
        note += " folded=layer_scale" if fold_scale is not None else ""
        note += " transposed" if transpose else ""
        note += f" scaled={scale:.6f}" if scale is not None else ""
        if not int8:
            self.add(
                "Conv",
                name,
                f"Conv w={list(weight.shape)} b={list(bias.shape)} k={list(kernel)}"
                f" p={list(pads)} g={group} pad=edge{note}",
                weight,
                bias,
            )
            return
        # Every quantisation this script performs must be one the shaders can serve, and both
        # int8 shaders zero-pad and index the kernel ungrouped. Refusing here rather than in the
        # Rust means a wrong call site fails at conversion, when there is still a `.maml` to
        # compare against, instead of at `Builder::finish` with the tensor indices already shifted.
        if group != 1 or tuple(kernel) != (1, 1) or tuple(pads) != (0, 0, 0, 0):
            raise SystemExit(
                f"{name}: only an ungrouped, unpadded 1x1 can be int8 — this is "
                f"k={list(kernel)} p={list(pads)} g={group}"
            )
        quantised, kernel_scale = maml_convert.quantise_per_channel(weight)
        self.add(
            "ConvInt8",
            name,
            f"ConvInt8 w={list(weight.shape)} scale={list(kernel_scale.shape)}"
            f" b={list(bias.shape)} k={list(kernel)} p={list(pads)} g={group}"
            f" zp=0 dtype=int8{note}",
            quantised,
            kernel_scale,
            bias,
        )

    def layer_norm(self, name):
        gamma = self.at(f"{name}.weight")
        beta = self.at(f"{name}.bias")
        self.add(
            "LayerNorm",
            name,
            f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}",
            gamma,
            beta,
        )

    def convnext(self, at, channels, inner, dilation, int8=False):
        """One ConvNeXt block: depthwise, norm, widening 1x1, narrowing 1x1 with the gamma.

        Symmetric padding, `2 * dilation` each side. The vocoder's is causal instead, and that
        difference cost real debugging time once already.

        `int8` quantises the two `1 x 1`s and not the depthwise one, which is the only split the
        shaders allow: `conv_int8.comp` zero-pads where this block replicates, and a 5-tap depthwise
        has a border to get wrong where a `1 x 1` has none.
        """
        each = dilation * 4 // 2
        self.conv(
            f"{at}.dwconv",
            channels,
            pads=(0, each, 0, each),
            kernel=(1, 5),
            group=channels,
        )
        self.layer_norm(f"{at}.norm.norm")
        self.conv(f"{at}.pwconv1", inner, int8=int8)
        # The block's gamma rides on `pwconv2`, which is a 1x1 and so has no border.
        self.conv(
            f"{at}.pwconv2",
            channels,
            fold_scale=self.at(f"{at}.gamma").reshape(-1),
            int8=int8,
        )

    def relative_attention(self, at, layer, channels, inner, head_dim, int8=False):
        """One post-norm relative-attention layer, in the order the export declares it.

        `int8` quantises all six convolutions - every one is an ungrouped `1 x 1`. The two relative
        position tables stay fp16: they are read by `attn_scores_relative.comp` rather than by a
        convolution, and there is no int8 path through it.
        """
        attn = f"{at}.attn_layers.{layer}"
        for projection in ("conv_q", "conv_k", "conv_v"):
            self.conv(f"{attn}.{projection}", channels, int8=int8)
        # `[1, 9, head_dim]` in the export; the Rust reads `[offsets, head_dim]`.
        for table_name in ("emb_rel_k", "emb_rel_v"):
            relative = self.at(f"{attn}.{table_name}").reshape(9, -1)
            if relative.shape[1] != head_dim:
                raise SystemExit(f"{attn}.{table_name} is {list(relative.shape)}, not [9, {head_dim}]")
            self.add("RelPos", f"{attn}.{table_name}", f"RelPos t={list(relative.shape)}", relative)
        self.conv(f"{attn}.conv_o", channels, int8=int8)
        self.layer_norm(f"{at}.norm_layers_1.{layer}.norm")
        self.conv(f"{at}.ffn_layers.{layer}.conv_1", inner, int8=int8)
        self.conv(f"{at}.ffn_layers.{layer}.conv_2", channels, int8=int8)
        self.layer_norm(f"{at}.norm_layers_2.{layer}.norm")


def collect_duration(graph):
    """The duration predictor's layers and tensors, folded, in the order the Rust reads them."""
    held = constants(graph)
    if not convolutions_edge_padded(graph):
        raise SystemExit(
            "this export does not pad every convolution with mode=edge, so the layer-scale "
            "fold below is not valid; see the module docstring"
        )
    table = Table(held)

    # The embedding table, with `sentence_token` as its last row. The token is prepended to the
    # sequence in the export and read back out of position 0 at the end; making it a row of the
    # table is how a plan with no sequence-axis concat expresses that.
    rows = table.at(f"{DP_PREFIX}.text_embedder.char_embedder.weight")
    token = table.at(f"{DP_PREFIX}.sentence_token").reshape(1, -1)
    if token.shape[1] != rows.shape[1]:
        raise SystemExit(f"a {list(token.shape)} sentence token for a {list(rows.shape)} table")
    rows = np.concatenate([rows, token], axis=0)
    table.add(
        "Embed", "char_embedder+sentence_token", f"Embed t={list(rows.shape)} token=last", rows
    )

    for block in range(DP_BLOCKS):
        table.convnext(f"{DP_PREFIX}.convnext.convnext.{block}", DP_CHANNELS, DP_INNER, 1, int8=True)
    for layer in range(DP_ATTN_LAYERS):
        table.relative_attention(
            f"{DP_PREFIX}.attn_encoder", layer, DP_CHANNELS, DP_INNER, 32, int8=True
        )

    # `proj_out` has no bias in the export; the Rust reads a pair for every convolution.
    table.conv(f"{DP_PREFIX}.proj_out.net", DP_CHANNELS, synthesise_bias=True)
    table.conv("tts.dp.predictor.layers.0", DP_HIDDEN)

    prelu = next(n for n in graph.node if n.op_type == "PRelu")
    slope = np.asarray(held[prelu.input[1]], dtype=np.float64).reshape(-1)
    if slope.size != 1:
        raise SystemExit(f"{prelu.name}: a {slope.size}-element slope, expected one")
    widened = np.full((DP_HIDDEN, 1, 1), float(slope[0]), dtype=np.float32)
    table.add(
        "PRelu",
        prelu.name,
        f"PRelu s={list(widened.shape)} shared={float(slope[0]):.6f}",
        widened,
    )

    table.conv("tts.dp.predictor.layers.1", 1)
    return table.layers, table.tensors


def style_attention(table, graph, which):
    """One of the text encoder's two cross-attentions over the 50 style tokens.

    Four entries: `W_query` (scaled), the folded constant keys, `W_value` and `out_fc`.
    """
    at = f"{TE_STYLE_PREFIX}.attention{which}"
    linears = matmul_weights(
        graph, table.held, f"/speech_prompted_text_encoder/attention{which}/"
    )
    head_dim = TE_CHANNELS // TE_STYLE_HEADS
    # The export divides the scores by 16 = sqrt(256), not by sqrt(head_dim) = sqrt(128), so
    # `Builder::attn_scores`'s own scale is sqrt(2) too large. Folding the difference into the
    # query projection is exact and costs no op.
    scale = np.sqrt(head_dim) / 16.0
    square = [TE_CHANNELS, TE_CHANNELS, 1, 1]
    table.add(
        "Conv",
        f"{at}.W_query",
        f"Conv w={square} b={[TE_CHANNELS]} k={[1, 1]} p={[0, 0, 0, 0]} g=1 pad=edge"
        f" transposed scaled={scale:.6f}",
        (linears["W_query"].T * scale).reshape(*square),
        table.at(f"{at}.W_query.linear.bias") * scale,
    )

    # `tanh(W_key . style_key + b_key)` is entirely constant, so it folds to one tensor. Stored
    # channel-major as `[256, 1, 50]`, which is what `Kind::Constant` loads and
    # `Kind::AttnScores` contracts.
    style_key = table.at(TE_STYLE_KEY).reshape(-1, TE_CHANNELS)
    keys = np.tanh(style_key @ linears["W_key"] + table.at(f"{at}.W_key.linear.bias"))
    if keys.shape != (TE_STYLE_TOKENS, TE_CHANNELS):
        raise SystemExit(f"{at}: folded keys are {list(keys.shape)}")
    keys = np.ascontiguousarray(keys.T).reshape(TE_CHANNELS, 1, TE_STYLE_TOKENS)
    table.add(
        "Constant",
        f"{at}.keys",
        f"Constant t={list(keys.shape)} folded=tanh(W_key.style_key+b_key)",
        keys,
    )

    for name in ("W_value", "out_fc"):
        table.add(
            "Conv",
            f"{at}.{name}",
            f"Conv w={square} b={[TE_CHANNELS]} k={[1, 1]} p={[0, 0, 0, 0]} g=1 pad=edge"
            " transposed",
            linears[name].T.reshape(*square),
            table.at(f"{at}.{name}.linear.bias"),
        )


def matmul_weights(graph, held, prefix):
    """The four `MatMul` weights under the node-name `prefix`, by the linear they belong to.

    They are `onnx::MatMul_3680` and friends rather than named parameters, so each is found
    through the node that consumes it.
    """
    wanted = ("W_query", "W_key", "W_value", "out_fc")
    found = {}
    for node in graph.node:
        if node.op_type != "MatMul" or not node.name.startswith(prefix):
            continue
        for name in wanted:
            if f"/{name}/" not in node.name:
                continue
            weights = [i for i in node.input if i in held]
            if len(weights) != 1:
                raise SystemExit(f"{node.name} has {len(weights)} held inputs, expected one")
            found[name] = np.asarray(held[weights[0]], dtype=np.float64)
    missing = set(wanted) - set(found)
    if missing:
        raise SystemExit(f"{prefix}: no MatMul found for {sorted(missing)}")
    return found


def collect_text(graph):
    """The text encoder's layers and tensors, folded, in the order the Rust reads them."""
    held = constants(graph)
    if not convolutions_edge_padded(graph):
        raise SystemExit(
            "this export does not pad every convolution with mode=edge, so the layer-scale "
            "fold below is not valid; see the module docstring"
        )
    table = Table(held)

    rows = table.at(f"{TE_PREFIX}.text_embedder.char_embedder.weight")
    table.add("Embed", "char_embedder", f"Embed t={list(rows.shape)}", rows)

    # 1, 1, 2, 2, 4, 4 - pairs, and the pad follows the dilation.
    for block, dilation in enumerate([1, 1, 2, 2, 4, 4]):
        table.convnext(f"{TE_PREFIX}.convnext.convnext.{block}", TE_CHANNELS, TE_INNER, dilation)
    for layer in range(TE_ATTN_LAYERS):
        table.relative_attention(f"{TE_PREFIX}.attn_encoder", layer, TE_CHANNELS, TE_INNER, 64)

    for which in (1, 2):
        style_attention(table, graph, which)
    table.layer_norm(f"{TE_STYLE_PREFIX}.norm.norm")
    return table.layers, table.tensors


def collect_sampler(graph):
    """The sampler's layers and tensors, folded, in the order the Rust reads them.

    Four main blocks of `convnext x4, timestep shift, convnext, text attention, convnext, style
    attention`, then four trailing ConvNeXt blocks, between two unbiased projections. The
    export flattens the four into `main_blocks.0` .. `main_blocks.23`, six entries each.
    """
    held = constants(graph)
    if not convolutions_edge_padded(graph):
        raise SystemExit(
            "this export does not pad every convolution with mode=edge, so the layer-scale "
            "fold below is not valid; see the module docstring"
        )
    table = Table(held)

    def matmul(prefix, name):
        return matmul_weights(graph, held, prefix)[name]

    def linear(at, prefix, name, out_channels):
        """One `MatMul` plus its bias, as a quantised 1x1 convolution. The weight is `[in, out]`."""
        weight = matmul(prefix, name).T
        bias = table.at(f"{at}.{name}.linear.bias")
        if weight.shape[0] != out_channels:
            raise SystemExit(f"{at}.{name} is {list(weight.shape)}, not [{out_channels}, in]")
        table.quantised_conv(
            f"{at}.{name}",
            f"Conv w={[*weight.shape, 1, 1]} b={list(bias.shape)} k={[1, 1]}"
            f" p={[0, 0, 0, 0]} g=1 pad=edge transposed",
            weight.reshape(weight.shape[0], weight.shape[1], 1, 1),
            bias,
        )

    def scaled_query(at, prefix, out_channels, heads):
        """`W_query`, with the difference between the export's /16 and 1/sqrt(head_dim) folded in.

        Both attentions divide by 16. `Builder::attn_scores` divides by `sqrt(head_dim)`, so the
        query carries `sqrt(head_dim) / 16` - exact, and safe across the rotary because a
        rotation commutes with a scalar.
        """
        scale = np.sqrt(out_channels / heads) / 16.0
        weight = matmul(prefix, "W_query").T * scale
        bias = table.at(f"{at}.W_query.linear.bias") * scale
        table.quantised_conv(
            f"{at}.W_query",
            f"Conv w={[*weight.shape, 1, 1]} b={list(bias.shape)} k={[1, 1]}"
            f" p={[0, 0, 0, 0]} g=1 pad=edge transposed scaled={scale:.6f}",
            weight.reshape(weight.shape[0], weight.shape[1], 1, 1),
            bias,
        )

    table.conv(f"{VE_PREFIX}.proj_in.net", VE_CHANNELS, synthesise_bias=True, int8=True)
    for block in range(VE_MAIN_BLOCKS):
        base = block * 6
        for layer, dilation in enumerate(VE_LEADING):
            table.convnext(
                f"{VE_PREFIX}.main_blocks.{base}.convnext.{layer}",
                VE_CHANNELS,
                VE_INNER,
                dilation,
                int8=True,
            )
        # `main_blocks.{base + 1}` is the timestep `Linear`, evaluated on the host.
        table.convnext(
            f"{VE_PREFIX}.main_blocks.{base + 2}.convnext.0", VE_CHANNELS, VE_INNER, 1, int8=True
        )

        at = f"{VE_PREFIX}.main_blocks.{base + 3}.attn"
        prefix = f"/vector_estimator/vector_field/main_blocks.{base + 3}/attn/"
        scaled_query(at, prefix, VE_CHANNELS, VE_TEXT_HEADS)
        linear(at, prefix, "W_key", VE_CHANNELS)
        linear(at, prefix, "W_value", VE_CHANNELS)
        linear(at, prefix, "out_fc", VE_CHANNELS)
        table.layer_norm(f"{VE_PREFIX}.main_blocks.{base + 3}.norm.norm")

        table.convnext(
            f"{VE_PREFIX}.main_blocks.{base + 4}.convnext.0", VE_CHANNELS, VE_INNER, 1, int8=True
        )

        at = f"{VE_PREFIX}.main_blocks.{base + 5}.attention"
        prefix = f"/vector_estimator/vector_field/main_blocks.{base + 5}/attention/"
        scaled_query(at, prefix, VE_STYLE, VE_STYLE_HEADS)
        linear(at, prefix, "W_value", VE_STYLE)
        linear(at, prefix, "out_fc", VE_CHANNELS)
        table.layer_norm(f"{VE_PREFIX}.main_blocks.{base + 5}.norm.norm")

    for layer in range(VE_TRAILING):
        table.convnext(
            f"{VE_PREFIX}.last_convnext.convnext.{layer}", VE_CHANNELS, VE_INNER, 1, int8=True
        )
    table.conv(f"{VE_PREFIX}.proj_out.net", VE_LATENT, synthesise_bias=True, int8=True)

    # Everything from here is read on the host. `nets::Builder::host_tensor` names each, so an
    # accidentally unread weight is still an error.
    theta = table.at(f"{VE_PREFIX}.main_blocks.3.attn.theta").reshape(-1)
    table.add("Host", "rotary theta", f"Host t={list(theta.shape)} rotary=theta", theta)
    frequencies = table.at(
        "/vector_estimator/vector_field/time_encoder/sinusoidal/Constant_3_output_0"
    ).reshape(-1)
    table.add(
        "Host", "time frequencies", f"Host t={list(frequencies.shape)} time=frequencies", frequencies
    )

    for which, (rows, columns) in (("mlp.0", (VE_TIME_INNER, VE_TIME)), ("mlp.2", (VE_TIME, VE_TIME_INNER))):
        at = f"{VE_PREFIX}.time_encoder.mlp.{which.split('.')[1]}.linear"
        weight = table.at(f"{at}.weight")
        bias = table.at(f"{at}.bias")
        if list(weight.shape) != [rows, columns]:
            raise SystemExit(f"{at} is {list(weight.shape)}, not {[rows, columns]}")
        table.add(
            "Host",
            at,
            f"Host w={list(weight.shape)} b={list(bias.shape)} time={which}",
            weight,
            bias,
        )

    for block in range(VE_MAIN_BLOCKS):
        base = block * 6 + 1
        at = f"{VE_PREFIX}.main_blocks.{base}.linear.linear"
        prefix = f"/vector_estimator/vector_field/main_blocks.{base}/linear/linear/"
        node = next(n for n in graph.node if n.name == f"{prefix}MatMul")
        weight = np.asarray(held[next(i for i in node.input if i in held)], dtype=np.float64).T
        bias = table.at(f"{at}.bias")
        table.add(
            "Host",
            at,
            f"Host w={list(weight.shape)} b={list(bias.shape)} time=shift{block} transposed",
            weight,
            bias,
        )

    token = table.at(f"{VE_UNCOND}.text_special_token").reshape(-1)
    table.add("Host", "text_special_token", f"Host t={list(token.shape)} uncond=text", token)
    style = np.ascontiguousarray(
        table.at(f"{VE_UNCOND}.style_value_special_token").reshape(-1, VE_STYLE).T
    )
    table.add("Host", "style_value_special_token", f"Host t={list(style.shape)} uncond=style", style)

    # The two branches' style keys, `tanh(W_key . style_key + b_key)`, entirely constant. The
    # conditional key is a nameless initializer reached through the `Tile` that broadcasts it.
    for label, source in (("conditional", conditional_style_key(graph, held)), ("unconditional", f"{VE_UNCOND}.style_key_special_token")):
        # One `style_key` but four `W_key`s, so four folded tensors per branch, stacked along
        # channels for `Builder::slice_channels` to cut apart. Folding only the first left the
        # net exact through ten sub-blocks and then wrong, which is what the bisect found.
        stacked = []
        for block in range(VE_MAIN_BLOCKS):
            index = block * 6 + 5
            keys = matmul_weights(
                graph, held, f"/vector_estimator/vector_field/main_blocks.{index}/attention/"
            )["W_key"]
            bias = table.at(f"{VE_PREFIX}.main_blocks.{index}.attention.W_key.linear.bias")
            folded = np.tanh(table.at(source).reshape(-1, VE_STYLE) @ keys + bias)
            if folded.shape != (VE_STYLE_TOKENS, VE_STYLE):
                raise SystemExit(f"{label} keys for block {block} folded to {list(folded.shape)}")
            stacked.append(np.ascontiguousarray(folded.T))
        folded = np.concatenate(stacked, axis=0)
        table.add(
            "Host",
            f"style keys {label}",
            f"Host t={list(folded.shape)} folded=tanh(W_key.style_key+b) {label}",
            folded,
        )
    return table.layers, table.tensors


def conditional_style_key(graph, held):
    """The name of the conditional `style_key`, which the export leaves nameless.

    It reaches `W_key` through a `Concat` of a `Tile` of itself and an `Expand` of the
    unconditional token, so it is found rather than spelled.
    """
    producer = {o: n for n in graph.node for o in n.output}
    node = next(
        n
        for n in graph.node
        if n.name == "/vector_estimator/vector_field/main_blocks.5/attention/W_key/linear/MatMul"
    )
    concat = producer[node.input[0]]
    for branch in concat.input:
        broadcast = producer[branch]
        for name in broadcast.input:
            if name in held and list(np.asarray(held[name]).shape) == [1, VE_STYLE_TOKENS, VE_STYLE]:
                if "uncond" not in name:
                    return name
    raise SystemExit("no conditional style_key found ahead of the style W_key")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("onnx")
    parser.add_argument("--graph", required=True, choices=sorted(maml_convert.GRAPHS))
    parser.add_argument("-o", "--out")
    parser.add_argument("--print-layers", action="store_true")
    parser.add_argument("--print-digest", action="store_true")
    args = parser.parse_args()

    raw = open(args.onnx, "rb").read()
    onnx_sha256 = hashlib.sha256(raw).digest()
    model = onnx.load(args.onnx)
    if args.graph == "supertonic_dp":
        layers, tensors = collect_duration(model.graph)
    elif args.graph == "supertonic_ttl":
        layers, tensors = collect_text(model.graph)
    elif args.graph == "supertonic_ve":
        layers, tensors = collect_sampler(model.graph)
    else:
        layers, tensors = collect(model.graph)

    if args.print_layers:
        for layer in layers:
            print(f"{layer.index:4d} t{layer.first_tensor:<4d} {layer.key()}  # {layer.name}")
    digest = maml_convert.layer_table_digest(layers)
    if args.print_digest:
        print(f'    "{args.graph}": "{digest}",')
    pinned = maml_convert.EXPECTED_DIGEST.get(args.graph, "")
    if digest != pinned and not args.print_digest:
        raise SystemExit(
            f"layer table digest {digest}\n           pinned  {pinned}\n"
            "The folded layer table changed, so the hardcoded forward pass no longer\n"
            "matches this export. Re-read it, update the Rust, then re-pin with\n"
            "--print-digest."
        )

    blob, count = maml_convert.build(
        layers, tensors, maml_convert.GRAPHS[args.graph], onnx_sha256
    )
    if args.out:
        with open(args.out, "wb") as f:
            f.write(blob)
    print(
        f"{args.graph}: {count} folded layers, {len(tensors)} tensors, "
        f"{len(blob)} bytes ({len(raw)} fp32 in)"
    )


if __name__ == "__main__":
    main()
