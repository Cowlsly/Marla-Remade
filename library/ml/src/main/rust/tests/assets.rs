//! Builds both forward passes against the `.maml` files actually committed to
//! `camera/` and `photos/`.
//!
//! Everything in `src/nets` is unit-tested against a stub weight source, which proves
//! the two passes are self-consistent but not that they agree with the converter. This
//! is the test that closes that: it parses the shipped assets and walks all 348 tensors
//! through `Weights::shaped`, so a converter that reordered a layer, a `.maml` rebuilt
//! from a moved upstream, or a miscounted RSU block fails here — naming the tensor.
//!
//! Given that nothing checks the *numbers* this runtime produces (correctness is
//! confirmed visually on device), agreement about the tensor table is the strongest
//! automatic guarantee available, and it is worth reaching across modules for.

use std::path::{Path, PathBuf};

use modelrunner::nets::{
    mobilefacenet, ppocr_det, ppocr_rec, scrfd, selfie, supertonic_duration, supertonic_sampler,
    supertonic_text, supertonic_vocoder, u2netp,
};
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
        .unwrap_or_else(|e| panic!("{} is not a usable .maml: {e}", path.display()))
}

#[test]
fn the_shipped_selfie_asset_builds_the_selfie_forward_pass() {
    let weights = load("camera/src/main/assets/selfie_segmentation.maml", graph::SELFIE);
    assert_eq!(weights.len(), selfie::TENSORS);
    let plan = selfie::build(&weights).expect("the shipped asset matches nets::selfie");
    assert!(!plan.ops.is_empty());
}

#[test]
fn the_shipped_u2netp_asset_builds_the_u2netp_forward_pass() {
    let weights = load("photos/src/main/assets/u2netp.maml", graph::U2NETP);
    assert_eq!(weights.len(), u2netp::TENSORS);
    let plan = u2netp::build(&weights).expect("the shipped asset matches nets::u2netp");
    assert!(!plan.ops.is_empty());
}

#[test]
fn the_shipped_mobilefacenet_asset_builds_the_mobilefacenet_forward_pass() {
    let weights = load("photos/src/main/assets/w600k_mbf.maml", graph::MOBILEFACENET);
    assert_eq!(weights.len(), mobilefacenet::TENSORS);
    let plan =
        mobilefacenet::build(&weights).expect("the shipped asset matches nets::mobilefacenet");
    assert!(!plan.ops.is_empty());
}

#[test]
fn the_shipped_scrfd_asset_builds_the_scrfd_forward_pass() {
    let weights = load("photos/src/main/assets/scrfd_500m.maml", graph::SCRFD);
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
    let weights = load("photos/src/main/assets/scrfd_500m.maml", graph::SCRFD);
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
    let weights = load("library/ocr/src/main/assets/ppocr_det.maml", graph::PPOCR_DET);
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
    let weights = load("library/ocr/src/main/assets/ppocr_rec.maml", graph::PPOCR_REC);
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
    let weights = load("library/ocr/src/main/assets/ppocr_rec.maml", graph::PPOCR_REC);
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
    let selfie = repo_root().join("camera/src/main/assets/selfie_segmentation.maml");
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
            load("camera/src/main/assets/selfie_segmentation.maml", graph::SELFIE),
            selfie::build(&load(
                "camera/src/main/assets/selfie_segmentation.maml",
                graph::SELFIE,
            ))
            .expect("selfie")
            .arena_elems,
        ),
        (
            "u2netp",
            load("photos/src/main/assets/u2netp.maml", graph::U2NETP),
            u2netp::build(&load("photos/src/main/assets/u2netp.maml", graph::U2NETP))
                .expect("u2netp")
                .arena_elems,
        ),
        (
            "mobilefacenet",
            load("photos/src/main/assets/w600k_mbf.maml", graph::MOBILEFACENET),
            mobilefacenet::build(&load(
                "photos/src/main/assets/w600k_mbf.maml",
                graph::MOBILEFACENET,
            ))
            .expect("mobilefacenet")
            .arena_elems,
        ),
        (
            "scrfd@640",
            load("photos/src/main/assets/scrfd_500m.maml", graph::SCRFD),
            scrfd::build(
                &load("photos/src/main/assets/scrfd_500m.maml", graph::SCRFD),
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

/// One of Supertonic's four `.maml`, or `None` when the bundle has not been built.
///
/// Unlike every other asset here, Supertonic's is **not committed**: the four nets come to 189 MiB
/// at fp16, which is more than belongs in git history for something a pinned script can rebuild
/// byte for byte. So a fresh checkout does not have it, and this returns `None` rather than
/// panicking — the test below then reports that it was skipped instead of failing for the one
/// reason that is not a bug.
///
/// A file that is *present but wrong* is still a hard failure. That is the case worth catching:
/// a bundle rebuilt from a moved upstream, or converted by a `supertonic_fold.py` whose layer
/// ordering has drifted from the Rust.
fn supertonic(name: &str, graph_id: u32) -> Option<Weights> {
    let path = repo_root().join("speech/src/main/assets/supertonic").join(name);
    let bytes = std::fs::read(&path).ok()?;
    Some(
        Weights::parse(&bytes, graph_id)
            .unwrap_or_else(|e| panic!("{} is not a usable .maml: {e}", path.display())),
    )
}

#[test]
fn the_bundled_supertonic_assets_build_all_four_forward_passes() {
    // The four hardcoded passes against the four converted files, which is the strongest automatic
    // check that the bundle `:speech` ships is the one the Rust was written for: `Builder::finish`
    // refuses a plan that does not read every tensor in its file, and each `build` walks its
    // tensors through `Weights::shaped`, so a reordered or re-shaped layer fails here naming the
    // tensor rather than producing silent nonsense on a phone.
    //
    // Widths are arbitrary but non-trivial: every Supertonic plan is utterance-shaped, so a build
    // at one length exercises the shape arithmetic that `Net::rebuild` repeats per sentence.
    let (frames, chars) = (32u32, 16u32);

    let Some(duration) = supertonic("supertonic_dp.maml", graph::SUPERTONIC_DP) else {
        println!(
            "skipped: no bundle in speech/src/main/assets/supertonic. \
             Run python scripts/ml/fetch_supertonic.py to build it."
        );
        return;
    };
    assert_eq!(duration.len(), supertonic_duration::TENSORS);
    assert!(!supertonic_duration::build(&duration, chars).expect("the duration pass").ops.is_empty());

    let text = supertonic("supertonic_ttl.maml", graph::SUPERTONIC_TTL).expect("the text encoder");
    assert_eq!(text.len(), supertonic_text::TENSORS);
    assert!(!supertonic_text::build(&text, chars).expect("the text pass").ops.is_empty());

    let sampler = supertonic("supertonic_ve.maml", graph::SUPERTONIC_VE).expect("the sampler");
    assert_eq!(sampler.len(), supertonic_sampler::TENSORS);
    assert!(
        !supertonic_sampler::build(&sampler, frames, chars).expect("the sampler pass").ops.is_empty()
    );

    let vocoder = supertonic("supertonic_voc.maml", graph::SUPERTONIC_VOC).expect("the vocoder");
    assert_eq!(vocoder.len(), supertonic_vocoder::TENSORS);
    assert!(!supertonic_vocoder::build(&vocoder, frames).expect("the vocoder pass").ops.is_empty());

    let total: usize = [&duration, &text, &sampler, &vocoder].iter().map(|w| w.data().len()).sum();
    println!("supertonic: {} MiB of weights across four nets", total / (1 << 20));
}

/// The six files `SupertonicBundle.isPresent` requires before `:speech` will advertise a voice.
///
/// Kotlin's check is a directory listing, so it cannot notice a file that is the right name and the
/// wrong size. This is the counterpart that does: the codepoint table is a fixed 65,536 `int16` and
/// each voice style a fixed 12,928 fp16, both fixed by the architecture rather than by the export,
/// so a length check here is as strong as a digest would be.
#[test]
fn the_bundled_supertonic_inputs_are_the_sizes_the_runtime_assumes() {
    let dir = repo_root().join("speech/src/main/assets/supertonic");
    let Ok(indexer) = std::fs::read(dir.join("unicode_indexer.bin")) else {
        println!("skipped: no bundle. Run python scripts/ml/fetch_supertonic.py to build it.");
        return;
    };
    // `post::supertonic::INDEXER_ENTRIES` int16, one per BMP codepoint.
    assert_eq!(indexer.len(), 65_536 * 2, "the codepoint table");

    // `style_ttl` transposed to [256, 50] then `style_dp` flattened to 128, as fp16.
    let style_elems = 256 * 50 + 128;
    for voice in ["F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"] {
        let name = format!("style_{voice}.bin");
        let bytes = std::fs::read(dir.join(&name)).unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(bytes.len(), style_elems * 2, "{name}");
    }
}
