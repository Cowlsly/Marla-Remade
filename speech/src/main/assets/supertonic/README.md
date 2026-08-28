# Supertonic 3, bundled

`SupertonicSynthesizer.inAssets` reads these six files, and `SupertonicBundle.isPresent` refuses to
advertise the TTS engine unless they are all here. They are **not in version control** — the four
`.maml` come to ~105 MB — so a fresh checkout has to build them:

```
./scripts/ml/supertonic_fold.py duration_predictor.onnx --graph supertonic_dp  -o supertonic_dp.maml
./scripts/ml/supertonic_fold.py text_encoder.onnx       --graph supertonic_ttl -o supertonic_ttl.maml
./scripts/ml/supertonic_fold.py vector_estimator.onnx   --graph supertonic_ve  -o supertonic_ve.maml
./scripts/ml/supertonic_fold.py vocoder.onnx            --graph supertonic_voc -o supertonic_voc.maml
./scripts/ml/supertonic_bundle.py --indexer <ttl.json> --styles voice_styles/ -o .
```

| File | What it is |
| :--- | :--- |
| `supertonic_dp.maml` | duration predictor — one number, the utterance's length in seconds |
| `supertonic_ttl.maml` | text encoder — characters to a `[256, chars]` conditioning |
| `supertonic_ve.maml` | flow-matching sampler — the expensive one, 32 passes per sentence |
| `supertonic_voc.maml` | ConvNeXt vocoder — latent to 44,100 Hz samples |
| `unicode_indexer.bin` | 65,536 `int16`, one per BMP codepoint; the whole front end |
| `style_F1.bin` … `style_M5.bin` | ten voices, ~25 KB each; two style tensors, not a model |

## Uncompressed, and it has to stay that way

`noCompress += "maml"` in `speech/build.gradle.kts` is load-bearing rather than an optimisation:
`AssetManager.openFd` throws for a deflated entry, and the fd is how the weights reach the GPU
without being copied through the Java heap three times. Adding a `.bin` entry would be worth it too
if the codepoint table ever grew, but at 128 KB it is read whole anyway.

## Licence

Supertonic 3 is **OpenRAIL**, which is use-restricted — the first such licence in this tree, and the
reason `SUPPLY_CHAIN_RISKS.md` calls it out separately. It ships in the APK rather than being
downloaded, so the restriction travels with every release.
