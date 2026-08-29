# `:photos` bundled model assets

## `u2netp.maml` — salient-object detection

Apache-2.0, from https://huggingface.co/BritishWerewolf/U-2-Netp
@ `7112208dbac3a3642496c8d54e2f0f9bb3dc1dc8` (`onnx/model.onnx`, 4,574,861 B fp32,
SHA-256 `309c8469…4c76f4ddd8`) — U²-Net *portable*, the small variant of the
salient-object detector.

Not the ONNX itself: `scripts/ml/fetch_and_convert.sh` converts it to the fp16
`.maml` weights container that `:library:ml` reads. The source ONNX SHA-256 is in
the `.maml` header, so a shipped asset traces back to this line.

Run at 320×320 RGB, ImageNet mean/std, output a 320×320 saliency map through a
sigmoid. Consumed by `SubjectSegmenter` from `MlSegmentation.kt` for
auto-select-subject.

This replaces `u2netp.ncnn.{param,bin}`, which was added in `72dde80df` with no
upstream URL or license. Its op inventory (Convolution 119, Pooling 33, Interp 38)
matches this ONNX exactly (Conv 119, MaxPool 33, Resize 38), so it is the same
network re-sourced from a licensed export rather than a different model. The
upstream export is dynamic-shape; we keep the straight resize to 320×320 that the
ncnn path already used, **not** the HuggingFace processor's `keep_aspect_ratio`
letterbox, which would change existing behaviour.

## The face models

`scrfd_500m.maml` (face detection) and `w600k_mbf.maml` (face embedding) are
InsightFace's **buffalo_s** pack, from
https://huggingface.co/immich-app/buffalo_s @ `0ff1751885575e62e084dff70549ce24a11fa5dc`:

| asset | source ONNX | SHA-256 |
|---|---|---|
| `scrfd_500m.maml` | `detection/model.onnx`, 2,524,817 B | `5e4447f5…d8b4ea3a` |
| `w600k_mbf.maml` | `recognition/model.onnx`, 13,616,099 B | `9cc6e4a7…b319eb4f` |

**Licence: InsightFace's own, which permits non-commercial research use only** —
https://github.com/deepinsight/insightface/tree/master/python-package#license. This is
*not* Apache-2.0 like the two models above, and it is the one licence constraint among
the bundled weights. These are the same weights the repo already shipped as
`scrfd_500m_kps-opt2.{param,bin}` and `w600k_mbf.ncnn.{param,bin}`, which arrived with
no upstream URL and no licence recorded at all; re-sourcing them from a licensed ONNX
export makes the constraint visible rather than introducing it.

SCRFD runs at 640 on the long side with the short side padded to a multiple of 32, so
its `.maml` is the only one whose graph is dynamically shaped. MobileFaceNet runs at a
fixed 112×112 and returns a 512-d embedding.

Two nodes are rewritten during conversion rather than needing a shader — see
`scripts/ml/maml_convert.py`: MobileFaceNet's final `Gemm` becomes a `[512, 64, 7, 7]`
convolution kernel, and the `BatchNormalization` after it is folded into that layer's
weight and bias.

The ncnn `.param`/`.bin` files these replace (`scrfd_500m_kps-opt2.*`,
`w600k_mbf.ncnn.*`, 7.9 MB together) are gone. `libncnn_android.so` is still in this
APK, but only because `:library:ocr` pulls it in for PP-OCRv5 text recognition — nothing
in `:photos` itself uses ncnn any more.

## `clip/`

TinyCLIP-ViT-8M/16 Text-3M, read by `ClipEmbedder` through `:library:ml`. It has a README
of its own — `clip/README.md` — because it is the one asset here whose provenance had to be
written from scratch: until `scripts/ml/fetch_tinyclip.py` existed it was the only bundled
model in the tree with no pinned upstream SHA-256 and no fetch recipe. Rebuild it with that
script rather than with `fetch_and_convert.sh`.

`model_int8.onnx` and the `onnxruntime-reduced-android` AAR that ran it are both gone, which
is 10,466,856 bytes of arm64 native code out of this APK. `libmodelrunner.so` was already here
for the four models above, and there is now no third-party inference runtime in `:photos` at all.

## Keeping these current

Update: `./scripts/ml/fetch_and_convert.sh --update`, which reports the moved
SHA-256 to re-pin. If the ordered layer table changed too, the converter says so
and the hardcoded forward pass in
`library/ml/src/main/rust/src/nets/u2netp.rs` has to be updated with it.

This file is inside `assets/`, so it is packaged into the APK. That is deliberate
rather than overlooked: it is ~1 KB, and Apache-2.0 §4 asks that attribution
notices travel with the distributed work, which here is the weights next to it.
