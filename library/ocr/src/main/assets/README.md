# `:library:ocr` bundled model assets

## `ppocr_det.vkml` — text detection

Apache-2.0, from https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx
@ `e6f4fa85f00e168c862bc462aebca69eef9b3d3d` (`inference.onnx`, 4,826,518 B fp32,
SHA-256 `a4319856…597b6e61d`) — the official PaddleOCR export of PP-OCRv5 mobile
detection. The source ONNX SHA-256 is in the `.vkml` header, so a shipped asset traces
back to this line.

Rebuild with `./scripts/ml/fetch_and_convert.sh`.

### It is folded, not copied

Unlike every other `.vkml`, this one is **not** the ONNX's tensors verbatim. The export
is PP-HGNetV2 as `paddle2onnx` spells it, and every convolution arrives as a chain:

    Conv (no bias) → Add(bias) → Mul(scalar) → Add(scalar)
                   → HardSigmoid(α=1/6) → Mul(self) → Mul(scalar) → Add(scalar)

threaded through 192 `Identity` nodes. That is 62 convolutions, 117 `Add`s, 86 `Mul`s and
34 `HardSigmoid`s, of which only about a dozen `Add`/`Mul` pairs are real ops — the
feature-pyramid residuals and the squeeze-excite gates.

`scripts/ml/ppocr_fold.py` folds the constant part ahead of time: the bias, the batch
norms, the affine blocks that precede an activation, and `HardSigmoid`'s α and β all go
into each convolution's weight and bias, and `HardSigmoid(1/6) → Mul(self)` is recognised
as the `HardSwish` the runtime already fuses. All of it is exact - an affine map composed
with a convolution is a convolution - and the result is 62 convolutions, 2 transposed
convolutions, 10 average pools, 10 channel multiplies, 11 adds, 6 nearest resizes, 1
concatenation and 14 scalar affines.
The 14 are the affine blocks that could **not** be folded forward into the convolution they
feed. `conv(a * x + t)` is `a * conv(x) + t * sum(W)` only at an interior pixel: a padded
convolution reads zero outside the input rather than `t`, so at the border the constant's
contribution is `t` times the in-bounds taps alone. Twelve of the 14 feed a padded
depthwise and two feed a squeeze-excite's two branches. See `nets::Kind::Affine`.

That op inventory matches ncnn's independent conversion of the same model layer for layer
(`Convolution` 48 + `ConvolutionDepthWise` 14 = 62, `Pooling` 10, `Interp` 6,
`Deconvolution` 2, `HardSwish` 24, `HardSigmoid` 10, `Concat` 1), which is the strongest
available check that the fold is right.

### Preprocessing and post-processing

From the export's own `inference.yml`, not guessed:

* **BGR**, not RGB (`DecodeImage: img_mode: BGR`).
* Long side resized to **960** (`DetResizeForTest: resize_long: 960`).
* ImageNet mean/std — the same constants `u2netp` uses.
* DBNet post-processing: `thresh: 0.3`, `box_thresh: 0.6`, `max_candidates: 1000`,
  `unclip_ratio: 1.5`.

## `ppocr_keys.txt` — the recogniser's character dictionary

836 characters, one per line, UTF-8, LF. Extracted from the same export's
`inference.yml` (`PostProcess.character_dict`, `CTCLabelDecode`), so it carries the same
Apache-2.0 provenance as the weights and needs no separate pin.

**Its order is the decode.** The recogniser's final layer emits 838 logits positionally:

| label | meaning |
|---|---|
| 0 | the CTC blank |
| 1..=836 | this file's lines, in order |
| 837 | the space, which `use_space_char` appends |

So a character is `line[label - 1]`, and a file that lost or reordered a line decodes to
fluent-looking nonsense rather than failing. `post::ctc` checks the length at load, and
`tests/assets.rs` checks that length against the model's logit count.

The space is deliberately **not** in the file — no entry in it is whitespace — so that a
text editor or a line-ending conversion cannot silently eat it. `post::ctc::Dictionary`
appends it.

## `ppocr_rec.vkml` - text recognition
Apache-2.0, from https://huggingface.co/PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx
@ `89d3a50e2c27e2e7cceeab0e944c25c807d5db4f` (`inference.onnx`, 8,042,023 B fp32,
SHA-256 `78881130.dd121498`) - the official export of the **latin** PP-OCRv5 mobile
recogniser, which is the variant whose 836-character dictionary is `ppocr_keys.txt`.
Folded by `scripts/ml/ppocr_fold.py` like detection, into 56 layers and 112 tensors.
From the export's own `inference.yml`: **BGR**, `image_shape [3, 48, 320]`,
`CTCLabelDecode`. Height is fixed at 48 and width must be a multiple of 8, so `T = W / 8`
and the standard 320-wide crop gives 40 timesteps.
### A CNN into a transformer, with no permutes
The backbone takes `3 x 48 x W` to `480 x 3 x W/4`; one average pool with kernel and
stride `(3, 2)` collapses that to `480 x 1 x W/8`, which *is* a sequence. Two pre-norm
transformer blocks at `d_model` 120 in 8 heads follow, then a concat back onto the pooled
features and an 838-way classifier.
`nets::ppocr_rec` keeps the sequence as `[d_model, 1, T]` - the layout the backbone already
produces - so all nine `Transpose`s and 52 `Reshape`s in the export are no-ops and are not
transcribed. Every projection is a 1x1 convolution and splitting `d_model` into heads is a
reinterpretation rather than a copy. The export's fused `[120, 360]` QKV projection is
split into three by the fold, and its `Mul(q, 1/sqrt(15))` is dropped because
`Kind::AttnScores` derives the same scale from the head geometry.
The classifier's final `Softmax` is **not** transcribed either. `post::ctc::decode` reads
the raw class-major logits: the argmax is unchanged by a softmax, and the winner's
probability is `1 / sum(exp(x - peak))` from the logits directly.
### Both folds are checked against onnxruntime
The layer table digest pins structure, not weight values, so a fold that computes the
wrong number passes it. `scripts/ml/onnx_parity.py` checks the numbers, and found two
bugs that everything else missed - an affine folded into a padded convolution, and a
silently zeroed QKV bias. At 64 wide the recogniser's logits agree with onnxruntime to
0.073 against a 0.075 bar from fp16 weight quantisation alone.
