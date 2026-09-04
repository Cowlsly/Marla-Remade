# `maia3-5m.maml`

Maia3-5M, the chess AI. Converted from **[`UofTCSSLab/Maia3-5M`](https://huggingface.co/UofTCSSLab/Maia3-5M)**
(`maia3-5m.pt`, 20.0 MB fp32) to 6.8 MiB of int8.

Rebuild it byte for byte with:

    python scripts/ml/fetch_maia.py

which pins the upstream revision and the checkpoint's SHA-256, asserts the parameter inventory,
and writes this file. Committed rather than gitignored, for the same reason `:speech`'s voice
bundle is: a fresh checkout that silently ships a chess app with no opponent is worse than the
history.

## What it is

An encoder-only transformer over 64 square tokens — width 256, 8 blocks, 8 heads, 5,230,084
parameters. Upstream calls the architecture "Chessformer"; the code is
[CSSLab/maia3](https://github.com/CSSLab/maia3).

It **predicts human moves** rather than searching for good ones. One forward pass per move, no
tree. Strength is a model input (`SelfElo` / `OppoElo`, 0..5000), so this one file covers all
four difficulty levels — see `Difficulty` in
`games/chess/src/main/java/com/vayunmathur/games/chess/util/MaiaEngine.kt`.

It replaces Stockfish and its 86 MB `nn-71d6d32cb962.nnue`, which is where the APK's ~79 MB
reduction comes from. It also removed the last personal JitPack fork from the tree; see
`SUPPLY_CHAIN_RISKS.md`.

## Precision

Every `1x1` convolution is int8, quantised per output channel; the norms, the biases and the
two elo embeddings stay fp16 and are 1% of the parameters. Worst per-tensor correlation against
fp32 is 0.999941.

That was chosen on **move agreement**, not on the cosine gate, because a cosine says nothing
about whether the model still picks the same move:

| | size | move agreement vs fp32 | disagreements wider than fp16 rounding |
|---|---|---|---|
| fp16 | 13.3 MiB | — | — |
| **int8, per channel** | **6.8 MiB** | **99.0–99.5%** | **0** |
| int4, per 16 | 4.2 MiB | 91.2% | 14 |
| int4, per channel | 3.5 MiB | 88.0% | 29 |

int4 is rejected: its disagreements are the model changing its mind, not losing a coin flip,
and sub-row grouping gives back most of the saving without fixing it. 5.2M parameters have no
redundancy to spare. Re-measure any of this with `python scripts/ml/maia_quant_eval.py`.

## How it is checked

* `scripts/ml/test/test_maia.py` — the converter's tensor order, the int8 triple, and the two
  structural rewrites (the fused q/k/v split, the eight-fold attention-bias replication),
  against a toy model.
* `scripts/ml/maia_parity.py` — the real weights through the Rust interpreter against the
  PyTorch reference, over `maia_parity_fixture.json`. Agreement on the best **legal** move as
  well as on the numbers, because a wrong board encoding still plays legal chess.
* `scripts/ml/maia_quant_eval.py` — what the quantisation costs, in moves, over hundreds of
  positions at every shipping rating.
* `MaiaEncodingTest` in `src/test/` — the board planes, the move vocabulary and perft.

The forward pass is `library/ml/src/main/rust/src/nets/maia.rs`, which documents the parts that
fail silently: the post-norm block order, the RMS norms, and why the attention bias is a grouped
convolution.

## Licence

Maia3's weights are released by the University of Toronto's Computational Social Science Lab.
See the model card at the link above for terms.
