//! Builds both forward passes against the `.vkml` files actually committed to
//! `camera/` and `photos/`.
//!
//! Everything in `src/nets` is unit-tested against a stub weight source, which proves
//! the two passes are self-consistent but not that they agree with the converter. This
//! is the test that closes that: it parses the shipped assets and walks all 348 tensors
//! through `Weights::shaped`, so a converter that reordered a layer, a `.vkml` rebuilt
//! from a moved upstream, or a miscounted RSU block fails here — naming the tensor.
//!
//! Given that nothing checks the *numbers* this runtime produces (correctness is
//! confirmed visually on device), agreement about the tensor table is the strongest
//! automatic guarantee available, and it is worth reaching across modules for.

use std::path::{Path, PathBuf};

use modelrunner::nets::{selfie, u2netp};
use modelrunner::weights::{graph, Weights};

/// The repo root, found by walking up for `settings.gradle.kts` rather than by counting
/// `..` — so moving this crate cannot silently turn the test into a no-op.
fn repo_root() -> PathBuf {
    let mut dir: &Path = &PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    loop {
        if dir.join("settings.gradle.kts").is_file() {
            return dir.to_path_buf();
        }
        dir = dir.parent().expect("this crate is inside the repo");
    }
}

fn load(relative: &str, graph_id: u32) -> Weights {
    let path = repo_root().join(relative);
    let bytes = std::fs::read(&path).unwrap_or_else(|e| {
        panic!(
            "cannot read {}: {e}. Run ./scripts/ml/fetch_and_convert.sh to rebuild it.",
            path.display()
        )
    });
    Weights::parse(&bytes, graph_id)
        .unwrap_or_else(|e| panic!("{} is not a usable .vkml: {e}", path.display()))
}

#[test]
fn the_shipped_selfie_asset_builds_the_selfie_forward_pass() {
    let weights = load("camera/src/main/assets/selfie_segmentation.vkml", graph::SELFIE);
    assert_eq!(weights.len(), selfie::TENSORS);
    let plan = selfie::build(&weights).expect("the shipped asset matches nets::selfie");
    assert!(!plan.ops.is_empty());
}

#[test]
fn the_shipped_u2netp_asset_builds_the_u2netp_forward_pass() {
    let weights = load("photos/src/main/assets/u2netp.vkml", graph::U2NETP);
    assert_eq!(weights.len(), u2netp::TENSORS);
    let plan = u2netp::build(&weights).expect("the shipped asset matches nets::u2netp");
    assert!(!plan.ops.is_empty());
}

#[test]
fn neither_asset_will_load_as_the_other_network() {
    // The `graph_id` in the header exists for exactly this: the two files are both
    // ordered fp16 tensor tables, so without it a swapped asset would parse.
    let selfie = repo_root().join("camera/src/main/assets/selfie_segmentation.vkml");
    let bytes = std::fs::read(selfie).expect("the selfie asset");
    assert!(Weights::parse(&bytes, graph::U2NETP).is_err());
}

#[test]
fn report_the_device_memory_each_net_needs() {
    // Not an assertion so much as the number a reviewer wants to see, printed by
    // `cargo test -- --nocapture`. Both are well inside what a phone will give a
    // background allocation, which is the point.
    for (name, weights, arena) in [
        (
            "selfie",
            load("camera/src/main/assets/selfie_segmentation.vkml", graph::SELFIE),
            selfie::build(&load(
                "camera/src/main/assets/selfie_segmentation.vkml",
                graph::SELFIE,
            ))
            .expect("selfie")
            .arena_elems,
        ),
        (
            "u2netp",
            load("photos/src/main/assets/u2netp.vkml", graph::U2NETP),
            u2netp::build(&load("photos/src/main/assets/u2netp.vkml", graph::U2NETP))
                .expect("u2netp")
                .arena_elems,
        ),
    ] {
        let weight_bytes = weights.data().len();
        let arena_bytes = arena as usize * 2;
        println!(
            "{name}: {} KiB weights + {} KiB arena = {} KiB",
            weight_bytes / 1024,
            arena_bytes / 1024,
            (weight_bytes + arena_bytes) / 1024,
        );
        assert!(weight_bytes + arena_bytes < 128 * 1024 * 1024);
    }
}
