# `photos/src/main/assets/clip/`

TinyCLIP-ViT-8M/16 Text-3M, the semantic-search embedder `ClipEmbedder` runs.

| file | what it is |
| :--- | :--- |
| `tinyclip.maml` | 22.6 MiB. Both towers in one file, read by `:library:ml`'s Vulkan runtime |
| `bpe_simple_vocab_16e6.txt` | 3.0 MB. CLIP's BPE merge table, read by `ClipTokenizer` |

## Provenance

**`onnx-community/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M-ONNX`**, revision
`9463a9c508a344c837ffefe9d724f3827bf2dc79`, MIT — an ONNX export of
`wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M`, also MIT.

Rebuild with:

    python scripts/ml/fetch_tinyclip.py

That downloads `onnx/model.onnx` (94 MB, **build host only**), checks its SHA-256 against the pin
in that script, checks `config.json` and `preprocessor_config.json` against the architecture and
the preprocessing the runtime hardcodes, and runs `scripts/ml/maml_convert.py --graph tinyclip`.
The rebuild is byte-identical.

Until this existed, TinyCLIP was the only bundled model in the tree with **no pinned upstream
SHA-256 and no fetch script**. `SUPPLY_CHAIN_RISKS.md` named the repo in prose and nothing checked
it.

## Why the fp32 export and not the int8 one

The asset this replaced was `onnx/model_int8.onnx` (24.3 MB) from the same repo, run on
onnxruntime. It is not what the `.maml` is built from. Its 80 `MatMulInteger` weights are
per-output-channel with zero zero-points, but three tensors are not:

* `patch_embedding` has a **scalar** scale, over a kernel whose per-channel absmax spans 0.0018 to
  0.117.
* `token_embedding` is **uint8 with a zero point of 226**, and `position_embedding` **uint8 with
  220**. `conv_int8.comp` computes `scale[o] * sum(int8_w * in)` and has nowhere to put a zero
  point.

Requantising those would add a second rounding to resolution that is already gone, so the converter
reads the fp32 graph and quantises per output channel itself. Worst measured per-tensor correlation
between the fp32 weight and `int8 * scale` is **0.99986**, over 82 quantised tensors.

The fp32 file costs nothing in fidelity over any other source, and that is measured rather than
assumed: **all 221 of its weight tensors are exactly fp16-representable**, so it was exported from
an fp16 checkpoint and carries no information a half-precision one would not. It is also why
`scripts/ml/onnx_parity.py` reports its "fp16 weights alone" bar as exactly zero for this graph —
every difference the runtime shows is int8 quantisation.

### Measured against the export, and against the file it replaces

Cosine of the L2-normalised 512-d embeddings, on one deterministic image and one 8-token query
(`scripts/ml/onnx_parity.py tinyclip` and `tinyclip_text`):

| | this `.maml` vs fp32 | shipped `model_int8.onnx` vs fp32 | this vs shipped |
| :--- | ---: | ---: | ---: |
| image | **0.999864** | 0.998478 | 0.998311 |
| text | **0.999394** | 0.982881 | 0.983471 |

The port is *closer* to the fp32 reference than the file it replaces, and the text tower is where
that gap is wide — 0.9994 against 0.9829 — because per-row int8 recovers most of what the shipped
export threw away on the 49,408-row token table.

The third column is why `ClipEmbedder.EMBEDDER_VERSION` is bumped: at 0.983 in the text tower, a
library indexed by the old path and queried through the new one would rank noticeably worse than one
indexed by either alone.

The result is *smaller* than the file it replaces — 23,734,912 bytes against 24,281,512 — because
the token embedding is int8 per row rather than uint8 per tensor and nothing is stored twice. The
APK shrinks by much more than that, because `onnxruntime-android`'s native `.so` leaves with it:
`onnxruntime-reduced-android-1.27.0-r1.aar` contributed **10,466,856 bytes** of arm64 `.so`
(3.43 MiB after the APK's own compression), against the 779 KB of `libmodelrunner.so` that was
already there for face detection, subject segmentation and OCR.

A `:photos` release APK built after the port contains **no `onnxruntime` entry at all**.

## What the `.maml` holds

306 tensors, 114 layers, 23,446,016 model parameters. Order is the contract between
`scripts/ml/maml_convert.py` and `library/ml/src/main/rust/src/nets/tinyclip.rs`, which indexes it
positionally — there are no names in the file. `maml_convert.py --graph tinyclip --print-layers`
prints the table.

Two transposes matter and are asserted rather than assumed. A `MatMul` weight is ONNX's
`[in, out]` and becomes `[out, in, 1, 1]`; the `Conv` patch kernel is `[M, C, kH, kW]` already and
is emitted **verbatim**. Every attention projection is square 256 x 256, so reading one convention
as the other gives a tensor of exactly the right shape holding exactly the wrong numbers.

## Licence

MIT, for both the export and the checkpoint it came from. This file is inside `assets/`, so it is
packaged into the APK — deliberately, so the attribution travels with the weights beside it.
