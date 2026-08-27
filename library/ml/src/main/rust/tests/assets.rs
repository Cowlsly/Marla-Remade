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

use modelrunner::nets::{mobilefacenet, ppocr_det, ppocr_rec, scrfd, selfie, u2netp};
use modelrunner::post::ctc;
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
fn the_shipped_mobilefacenet_asset_builds_the_mobilefacenet_forward_pass() {
    let weights = load("photos/src/main/assets/w600k_mbf.vkml", graph::MOBILEFACENET);
    assert_eq!(weights.len(), mobilefacenet::TENSORS);
    let plan =
        mobilefacenet::build(&weights).expect("the shipped asset matches nets::mobilefacenet");
    assert!(!plan.ops.is_empty());
}

#[test]
fn the_shipped_scrfd_asset_builds_the_scrfd_forward_pass() {
    let weights = load("photos/src/main/assets/scrfd_500m.vkml", graph::SCRFD);
    assert_eq!(weights.len(), scrfd::TENSORS);
    let plan = scrfd::build(&weights, scrfd::LONG_SIDE, scrfd::LONG_SIDE)
        .expect("the shipped asset matches nets::scrfd");
    assert_eq!(plan.outputs.len(), 9);
}

#[test]
fn the_scrfd_pass_builds_at_every_letterbox_shape_a_photo_can_produce() {
    // The detector is compiled per shape, so the whole family has to lower — not just
    // the square case. These are the short sides a 640-long-side letterbox rounds to for
    // aspect ratios from 1:1 to about 3:1, which covers any real photo.
    let weights = load("photos/src/main/assets/scrfd_500m.vkml", graph::SCRFD);
    for short in (scrfd::EXTENT_MULTIPLE..=scrfd::LONG_SIDE).step_by(32) {
        for (height, width) in [(short, scrfd::LONG_SIDE), (scrfd::LONG_SIDE, short)] {
            let plan = scrfd::build(&weights, height, width)
                .unwrap_or_else(|e| panic!("{width}x{height}: {e}"));
            assert_eq!(plan.outputs.len(), 9, "{width}x{height}");
        }
    }
}

#[test]
fn the_shipped_ppocr_det_asset_builds_the_ppocr_det_forward_pass() {
    let weights = load("library/ocr/src/main/assets/ppocr_det.vkml", graph::PPOCR_DET);
    assert_eq!(weights.len(), ppocr_det::TENSORS);
    let plan = ppocr_det::build(&weights, ppocr_det::LONG_SIDE, ppocr_det::LONG_SIDE)
        .expect("the shipped asset matches nets::ppocr_det");
    // Full-resolution probability map, which is what DBNet thresholds.
    assert_eq!(
        plan.output().expect("one output").shape.h,
        ppocr_det::LONG_SIDE
    );
}

#[test]
fn the_shipped_ppocr_rec_asset_builds_the_ppocr_rec_forward_pass() {
    let weights = load("library/ocr/src/main/assets/ppocr_rec.vkml", graph::PPOCR_REC);
    assert_eq!(weights.len(), ppocr_rec::TENSORS);
    // Building against the real file checks every one of the 112 tensors' shapes, because
    // `Builder::weight` asks for each by index *and* dimensions — so a converter that
    // transposed a linear, split the fused QKV projection the wrong way, or emitted a
    // layer norm's gamma and beta in the wrong order fails here rather than on device.
    let plan = ppocr_rec::build(&weights, 320)
        .expect("the shipped asset matches nets::ppocr_rec");
    assert_eq!(
        plan.output().expect("one output").shape,
        modelrunner::nets::Shape::new(ppocr_rec::LOGITS, 1, 40)
    );
}

#[test]
fn the_shipped_ppocr_rec_asset_builds_at_every_padded_line_width() {
    // A line crop is resized to 48 tall and padded to a multiple of 8, so the plan has to
    // lower at whatever width the batcher lands on.
    let weights = load("library/ocr/src/main/assets/ppocr_rec.vkml", graph::PPOCR_REC);
    for width in [8u32, 64, 160, 320, 640, 1024] {
        let plan = ppocr_rec::build(&weights, width)
            .unwrap_or_else(|e| panic!("at width {width}: {e}"));
        assert_eq!(plan.output().expect("one output").shape.w, width / 8);
    }
}

#[test]
fn the_shipped_character_dictionary_matches_the_recogniser_label_space() {
    // The model's final layer emits 838 logits positionally, so the dictionary's *length*
    // is a hard constraint and its *order* is the whole decode. A file that lost a line
    // to an editor would shift every character above it.
    let path = repo_root().join("library/ocr/src/main/assets/ppocr_keys.txt");
    let text = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
    let dictionary = ctc::Dictionary::parse(&text).expect("the shipped dictionary parses");
    assert_eq!(dictionary.labels(), ctc::LOGITS);
    // Spot-check the ends against the export's `inference.yml`: digits first, and the
    // appended space last.
    assert_eq!(dictionary.label(0), None, "label 0 is the CTC blank");
    assert_eq!(dictionary.label(1), Some("0"));
    assert_eq!(dictionary.label(11), Some("A"));
    assert_eq!(dictionary.label(ctc::LOGITS - 1), Some(" "));
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
        (
            "mobilefacenet",
            load("photos/src/main/assets/w600k_mbf.vkml", graph::MOBILEFACENET),
            mobilefacenet::build(&load(
                "photos/src/main/assets/w600k_mbf.vkml",
                graph::MOBILEFACENET,
            ))
            .expect("mobilefacenet")
            .arena_elems,
        ),
        (
            "scrfd@640",
            load("photos/src/main/assets/scrfd_500m.vkml", graph::SCRFD),
            scrfd::build(
                &load("photos/src/main/assets/scrfd_500m.vkml", graph::SCRFD),
                scrfd::LONG_SIDE,
                scrfd::LONG_SIDE,
            )
            .expect("scrfd")
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
