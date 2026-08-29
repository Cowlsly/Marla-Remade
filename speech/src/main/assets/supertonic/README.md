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

At int8 the four nets come to 107.6 MiB, which with Whisper's 74.3 MiB puts `:speech`'s assets at
181.9 MiB — 190.7 MB, under the 200 MB target. Every ungrouped `1 x 1` convolution is quantised in
the sampler, the vocoder and the duration predictor; the text encoder stays fp16 on measurement, and
each net's `INT8_CONVS` doc says exactly what is excluded and why.

`onnx_parity.py` against this revision, fp16 and int8 side by side, correlation with onnxruntime:

| net | fp16 | int8 | size |
| :--- | ---: | ---: | ---: |
| `supertonic_dp` | 0.99999975 | 0.99999250 | 1.73 → 1.44 MB |
| `supertonic_ttl` | 0.99900006 | *not quantised* | 17.77 MB |
| `supertonic_voc` | 0.99999858 | 0.99909061 | 50.67 → 28.70 MB |
| `supertonic_ve` | 0.99999981 | 0.99996750 | 127.64 → 64.48 MB |

**The vocoder is the one to listen to first.** 0.99909 clears the 0.999 bar the conversion was gated
on, but it implies roughly −27 dB of error against fp16's −55 dB, and no correlation can say whether
that is audible. Holding it at fp16 costs 21 MB and still fits the target.

Verified by `library/ml/src/main/rust/tests/assets.rs`, which builds all four forward passes against
these files and checks the codepoint table and every style is the size the runtime assumes. Those
tests **skip** when the bundle is absent, so they pass on a fresh checkout and fail on a wrong one.

## Uncompressed, and it has to stay that way

`noCompress += "maml"` in `speech/build.gradle.kts` is load-bearing rather than an optimisation:
`AssetManager.openFd` throws for a deflated entry, and the fd is how the weights reach the GPU
without being copied through the Java heap three times. Adding a `.bin` entry would be worth it too
if the codepoint table ever grew, but at 128 KB it is read whole anyway.

## Licence

Supertonic 3 is **OpenRAIL**, which is use-restricted — the first such licence in this tree, and the
reason `SUPPLY_CHAIN_RISKS.md` calls it out separately. It ships in the APK rather than being
downloaded, so the restriction travels with every release.
