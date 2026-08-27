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
as the `HardSwish` the runtime already fuses. All of it is exact — an affine map composed
with a convolution is a convolution — and the result is 62 convolutions, 2 transposed
convolutions, 10 average pools, 10 channel multiplies, 11 adds, 6 nearest resizes, 1
concatenation and 24 scalar affines.

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

## Recognition

`latin_PP_OCRv5_mobile_rec.ncnn.{param,bin}` still runs on ncnn, and is what keeps
`libncnn_android.so` in `:photos`, `:pdf` and `:translate`. Its replacement needs a
transformer stack the runtime does not have yet: the export decomposes each of its 5 layer
norms into `ReduceMean`/`Sub`/`Pow`/`Sqrt`/`Div`, and its attention into 13 `MatMul`s, 3
`Softmax`es and 9 `Transpose`s. The dictionary and the CTC decode for it are already here.

`PP_OCRv5_mobile_det.ncnn.{param,bin}` is superseded by `ppocr_det.vkml` but still
present, because `OcrEngine.kt` loads both models through the one ncnn wrapper and cannot
drop half of it.
