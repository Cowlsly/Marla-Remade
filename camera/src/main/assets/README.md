# `:camera` bundled model assets

## `selfie_segmentation.vkml`

Apache-2.0, from https://huggingface.co/onnx-community/mediapipe_selfie_segmentation
@ `be49485c8e027524be38591817fc5cd31bd9d00e` (`onnx/model.onnx`, 462,352 B fp32,
SHA-256 `3241ac4a…4531aad`) — an ONNX export of Google's MediaPipe Selfie
Segmentation, the model purpose-built for exactly this job.

Not the ONNX itself: `scripts/ml/fetch_and_convert.sh` converts it to the fp16
`.vkml` weights container that `:library:ml` reads, which is half the size and the
only form anything here loads. The source ONNX SHA-256 is in the `.vkml` header, so
a shipped asset traces back to this line without needing the ONNX on disk.

Run at 256×256 RGB, rescale 1/255 and **no** mean/std (`do_normalize: false`
upstream), output a single 256×256 alpha channel through a sigmoid. Consumed by
`SelfieSegmenter` from `BokehAnalyzer` and `PortraitBokeh.StillBokehRenderer`.

This replaces `erdnet.{param,bin}`, which was added in `72dde80df` with no upstream
URL, license or conversion recipe. It was Caffe-derived from nihui's ncnn demo
family, had no obtainable ONNX, and so could not be attributed or rebuilt — see
`SUPPLY_CHAIN_RISKS.md`. The masks differ from erdnet's; this is a different model,
not a port.

Update: `./scripts/ml/fetch_and_convert.sh --update`, which reports the moved
SHA-256 to re-pin. If the ordered layer table changed too, the converter says so
and the hardcoded forward pass in
`library/ml/src/main/rust/src/nets/selfie.rs` has to be updated with it.

This file is inside `assets/`, so it is packaged into the APK. That is deliberate
rather than overlooked: it is ~1 KB, and Apache-2.0 §4 asks that attribution
notices travel with the distributed work, which here is the weights next to it.
