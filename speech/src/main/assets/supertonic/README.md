# Supertonic 3, bundled

`SupertonicSynthesizer.inAssets` reads these six files, and `SupertonicBundle.isPresent` refuses to
advertise the TTS engine unless they are all here. They are **not in version control** — see the
entry in `/.gitignore` for why — so a fresh checkout has to build them:

```
python scripts/ml/fetch_supertonic.py
```

That fetches `Supertone/supertonic-3` pinned to one revision, checks all four ONNX SHA-256s, and
runs both converters. Each converter also checks its own layer-table digest against
`maml_convert.EXPECTED_DIGEST`, so a bundle built from a moved upstream fails loudly rather than
producing a net that reads the right shapes holding the wrong numbers.

| File | What it is |
| :--- | :--- |
| `supertonic_dp.maml` | duration predictor — one number, the utterance's length in seconds |
| `supertonic_ttl.maml` | text encoder — characters to a `[256, chars]` conditioning |
| `supertonic_ve.maml` | flow-matching sampler — the expensive one, 32 passes per sentence |
| `supertonic_voc.maml` | ConvNeXt vocoder — latent to 44,100 Hz samples |
| `unicode_indexer.bin` | 65,536 `int16`, one per BMP codepoint; the whole front end |
| `style_F1.bin` … `style_M5.bin` | ten voices, ~25 KB each; two style tensors, not a model |

At fp16 the four nets come to 189 MiB, which with Whisper's 74 MiB puts `:speech`'s assets at
263 MiB. Quantising the ungrouped `1 x 1` convolutions to int8 would take the four nets to ~100 MiB;
`Builder::conv_int8` and `conv_point_int8.comp` exist for it, but no net has been switched over yet.

Verified by `library/ml/src/main/rust/tests/assets.rs`, which builds all four forward passes against
these files and checks the codepoint table and every style is the size the runtime assumes. Those
tests **skip** when the bundle is absent, so they pass on a fresh checkout and fail on a wrong one.
The numbers are checked separately by `scripts/ml/onnx_parity.py`; at the pinned revision all four
nets correlate with onnxruntime above 0.999, the vocoder better than its own fp16 bar.

## Uncompressed, and it has to stay that way

`noCompress += "maml"` in `speech/build.gradle.kts` is load-bearing rather than an optimisation:
`AssetManager.openFd` throws for a deflated entry, and the fd is how the weights reach the GPU
without being copied through the Java heap three times. Adding a `.bin` entry would be worth it too
if the codepoint table ever grew, but at 128 KB it is read whole anyway.

## Licence

Supertonic 3 is **OpenRAIL**, which is use-restricted — the first such licence in this tree, and the
reason `SUPPLY_CHAIN_RISKS.md` calls it out separately. It ships in the APK rather than being
downloaded, so the restriction travels with every release.
