# Vendored BetoCore crates

Apache-2.0, from https://github.com/google/beto-core @ `479289ef072b0880c0347d36937265e44f00f4ee`.

See `NOTICE` for the full vendored crate list + origin commit, and `LICENSE` (upstream Apache-2.0).
Wired into the Cargo workspace via `third_party/betocore/*` path members; `share_nearby` depends on
`np_adv`, `ukey2_connections`, and `crypto_provider_default` (pure-Rust `rustcrypto` backend, `std+alloc`).

Update: re-copy from the same clone, bump the commit in `NOTICE`, and re-run `cargo check -p share_nearby`.
