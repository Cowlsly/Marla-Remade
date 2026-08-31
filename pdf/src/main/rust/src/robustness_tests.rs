//! Malformed-input robustness harness.
//!
//! Rounds 1 and 2 closed a long list of panic paths, unbounded allocations and
//! potential hangs, but every one of those fixes was verified by *reading* the
//! code. This module verifies them by *execution*: it builds valid documents,
//! systematically corrupts their bytes, and drives the real public entry points
//! over every mutant.
//!
//! The invariant under test, per §7.5.1 ("a conforming reader shall be able to
//! process a damaged file"), is:
//!
//!   For ANY byte string, every entry point must either produce a sane result or
//!   a clean error, within a finite time budget. It must never panic, never hang
//!   and never allocate without bound.
//!
//! A panic is not a cosmetic failure here: the JNI layer wraps each entry point
//! in `catch_unwind` (jni_bindings.rs), so a panic surfaces to the user as a
//! blank page or a dead document — the reported "crashexample" /
//! "crasheshalfway" / "doesntopenexample" symptom classes.
//!
//! Everything is deterministic. The corpus is constructed in code, the mutation
//! schedule is fixed, and the one place randomness is used (bit-flip position
//! selection) is a seeded LCG whose seed is printed on failure, so any finding
//! reproduces exactly.

use crate::*;
use lopdf::content::{Content, Operation};
use lopdf::{dictionary, Object, Stream};
use std::time::{Duration, Instant};

// ---------------------------------------------------------------------------
// Budget — self-calibrating against a measured in-process reference
// ---------------------------------------------------------------------------

// Budgets here are expressed as a MULTIPLE of a reference page rendered in this
// same process, per `bench`'s methodology note, rather than as a hardcoded
// millisecond figure. A hardcoded figure is wrong in one build mode or the other:
// `bench` measured this crate's debug/release ratio at 12.5x (7.874 ms vs
// 0.628 ms for an identical reference page), and machine-to-machine spread is on
// top of that. A relative budget self-calibrates across debug/release, CI vs
// laptop, and CPU speed.
//
// On the MULTIPLIER: `bench`'s figure is 50x per PAGE with a flat per-document
// backstop — never 50x per document, which they confirmed explicitly. The budgets
// here are per-DOCUMENT ("open + count + render <=4 pages + index"), so a tight
// multiple would be the wrong instrument: `bench` measured *legitimate,
// well-formed* pages from 0.011 ms to 622 ms in release against a 0.628 ms
// reference — a ~56000x span among entirely valid documents. The multipliers below
// are therefore deliberately enormous; they exist to catch "never returns", not
// "slower than typical". `bench`'s own closing advice was to err loose, because a
// flaky timeout trains people to ignore the suite. For calibration: the slowest of
// my 768 mutants ran at 1.2x reference, and the two real hangs overshot by >20x
// and >2000x, so the loose multipliers cost nothing in detection.
//
// Each budget also has an absolute floor, so that on a very fast machine the
// calibrated value cannot collapse to something razor-thin, and the effective
// values on this box stay what they were when the findings below were measured.
// The reference itself has a CEILING (see `REFERENCE_CEILING`) so that a
// contaminated calibration cannot silently widen every budget.

/// Reference multiple for a whole mutant ("open + count + render <=4 pages +
/// index"). ~1000x a reference page.
const MUTANT_BUDGET_X: u32 = 1_200;
const MUTANT_BUDGET_FLOOR: Duration = Duration::from_secs(10);

/// Same order of work as a mutant; a single adversarial construct.
const CONSTRUCT_BUDGET_X: u32 = 1_200;
const CONSTRUCT_BUDGET_FLOOR: Duration = Duration::from_secs(10);

/// For cases that are LEGITIMATELY heavy by construction — operator floods and
/// decompression bombs. These do real, bounded work proportional to a cap
/// (`MAX_PRIMITIVES` = 300k, `MAX_DECODED_BYTES` = 256 MB), and `bench` measured
/// a valid 400k-rect page at 622 ms in release, which is ~7.8 s in debug. A
/// tight budget here would be a false positive on correct behaviour.
const FLOOD_BUDGET_X: u32 = 25_000;
const FLOOD_BUDGET_FLOOR: Duration = Duration::from_secs(180);

/// Absolute ceiling on the calibrated reference.
///
/// This closes a real hole `bench` identified in the self-calibrating design: if
/// calibration happens to run during a load spike (concurrent agents now, a busy
/// CI box later) the reference inflates, EVERY budget scales up with it, and that
/// run silently loses its ability to detect a slow case. A ceiling means a
/// contaminated calibration degrades to the fixed floors instead of to infinity —
/// the suite can lose precision but never lose sensitivity without saying so.
///
/// 60 ms is ~5x the honest debug figure measured on this box (11.9 ms) and ~8x
/// `bench`'s (7.874 ms), so it cannot clip a legitimately slower machine, but it
/// does clip a spike.
const REFERENCE_CEILING: Duration = Duration::from_millis(60);

/// Time one render of a reference page, once per process.
///
/// Deliberately shaped like `bench`'s reference workload (60 text runs + 200
/// filled rects) so that the figure printed by this module is directly
/// comparable to the numbers in their measurements.
///
/// Takes the MINIMUM of the samples, not the median. Contention can only ever
/// make a render look slower, never faster, so the minimum is the least
/// contaminated estimate of what this machine actually costs — and unlike a
/// median it is robust to a *sustained* spike across the whole calibration
/// window, which is precisely the case `bench` raised.
fn reference_render() -> Duration {
    static REF: OnceLock<Duration> = OnceLock::new();
    *REF.get_or_init(|| {
        let mut content = Vec::new();
        for i in 0..200u32 {
            content.extend_from_slice(
                format!("{} {} 0.5 0.2 0.9 rg 4 6 re f ", i % 590, (i * 3) % 780).as_bytes(),
            );
        }
        content.extend_from_slice(b"BT /F1 11 Tf ");
        for i in 0..60u32 {
            content.extend_from_slice(
                format!("1 0 0 1 40 {} Tm (reference workload line) Tj ", 760 - i * 12).as_bytes(),
            );
        }
        content.extend_from_slice(b"ET");
        let pdf = raw_one_page(
            "/Resources << /Font << /F1 5 0 R >> >>",
            &content,
            vec![(
                5,
                b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_vec(),
            )],
        );
        let doc = load_document_lenient(&pdf).expect("reference page must load");
        let page_id = *doc.get_pages().values().next().unwrap();
        // Warm the font cache the way a real session would, then sample.
        let _ = interpret_page(&doc, page_id);
        let mut samples: Vec<Duration> = (0..9)
            .map(|_| {
                let t0 = Instant::now();
                let page = interpret_page(&doc, page_id).expect("reference must interpret");
                let _ = crate::wire::serialize(&page);
                t0.elapsed()
            })
            .collect();
        samples.sort();
        let min = samples[0];
        let median = samples[samples.len() / 2];
        let max = samples[samples.len() - 1];
        let chosen = min.min(REFERENCE_CEILING);
        // Print all three so a contaminated calibration is visible in the log
        // rather than silently widening every budget.
        eprintln!(
            "[robustness] reference page (60 text runs + 200 rects), 9 samples: \
             min {min:?} / median {median:?} / max {max:?} -> using min, \
             clamped to {chosen:?} (ceiling {REFERENCE_CEILING:?})"
        );
        if max > min * 4 {
            eprintln!(
                "[robustness] WARNING: reference spread is {:.1}x across samples \
                 (min {min:?}, max {max:?}). The box is contended, so timing-based \
                 results from this run are less trustworthy than usual. Budgets are \
                 anchored on the minimum, so sensitivity is preserved.",
                max.as_secs_f64() / min.as_secs_f64().max(f64::MIN_POSITIVE)
            );
        }
        if min > REFERENCE_CEILING {
            eprintln!(
                "[robustness] WARNING: even the fastest reference sample ({min:?}) \
                 exceeds REFERENCE_CEILING ({REFERENCE_CEILING:?}). Budgets are \
                 clamped, so they are now effectively the absolute floors. Either \
                 this machine is much slower than the ones this was calibrated on, \
                 or the renderer regressed badly."
            );
        }
        chosen
    })
}

/// Effective budget: `multiple` x the measured reference, never below `floor`.
/// `FUZZ_BUDGET_SECS` overrides both, for diagnosing whether a failure is
/// "unbounded" or merely "slow".
fn scaled_budget(multiple: u32, floor: Duration) -> Duration {
    if let Some(s) = std::env::var("FUZZ_BUDGET_SECS").ok().and_then(|s| s.parse::<u64>().ok()) {
        return Duration::from_secs(s);
    }
    (reference_render() * multiple).max(floor)
}

fn mutant_budget() -> Duration {
    scaled_budget(MUTANT_BUDGET_X, MUTANT_BUDGET_FLOOR)
}

fn construct_budget() -> Duration {
    scaled_budget(CONSTRUCT_BUDGET_X, CONSTRUCT_BUDGET_FLOOR)
}

fn flood_budget() -> Duration {
    scaled_budget(FLOOD_BUDGET_X, FLOOD_BUDGET_FLOOR)
}

/// Worker-thread stack for guarded work.
///
/// Deliberately generous: an overflow at 8 MiB means genuinely unbounded
/// recursion rather than a tight-stack artifact, and a stack overflow aborts the
/// whole process (it is a guard-page fault, not an unwind) so it cannot be
/// reported per-test. `bisect_nesting_depth` with `FUZZ_NEST_STACK` is the
/// tighter, Android-realistic probe.
const WORKER_STACK: usize = 8 * 1024 * 1024;

/// Fixed seed for bit-flip position selection. Printed on every failure.
const FUZZ_SEED: u64 = 0x5EED_1234_ABCD_0001;

// ---------------------------------------------------------------------------
// Guarded execution: catches panics, enforces the time budget
// ---------------------------------------------------------------------------

#[derive(Debug)]
enum Verdict {
    Completed(Duration),
    /// Panic message, and how long it took to get there.
    Panicked(String, Duration),
    /// Exceeded the budget. The worker thread is deliberately leaked — there is
    /// no way to cancel Rust computation, and the test binary exits regardless.
    TimedOut,
}

impl Verdict {
    fn is_failure(&self) -> bool {
        !matches!(self, Verdict::Completed(_))
    }
}

fn panic_text(e: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = e.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = e.downcast_ref::<String>() {
        s.clone()
    } else {
        "<non-string panic payload>".to_string()
    }
}

/// Run `f` on a worker thread, capturing a panic and enforcing `budget`.
///
/// The panic is captured rather than allowed to propagate so that one bad mutant
/// does not stop the sweep: the caller collects every finding and reports them
/// together, which is far more useful than the first one.
fn guarded(label: &str, budget: Duration, f: impl FnOnce() + Send + 'static) -> Verdict {
    guarded_on_stack(label, budget, WORKER_STACK, f)
}

fn guarded_on_stack(
    label: &str,
    budget: Duration,
    stack: usize,
    f: impl FnOnce() + Send + 'static,
) -> Verdict {
    let (tx, rx) = std::sync::mpsc::channel();
    let handle = std::thread::Builder::new()
        .name(format!("fuzz:{label}"))
        .stack_size(stack)
        .spawn(move || {
            let t0 = Instant::now();
            let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(f));
            let dt = t0.elapsed();
            let _ = tx.send(match r {
                Ok(()) => Verdict::Completed(dt),
                Err(e) => Verdict::Panicked(panic_text(e), dt),
            });
        })
        .expect("failed to spawn guard thread");

    match rx.recv_timeout(budget) {
        Ok(v) => {
            let _ = handle.join();
            v
        }
        Err(_) => Verdict::TimedOut,
    }
}

/// Guarded run of a single adversarial construct. Fails the test loudly, naming
/// the hazard, if the construct panics or blows the budget.
fn assert_construct_survives(hazard: &str, bytes: Vec<u8>) -> Duration {
    let label = hazard.to_string();
    let b = construct_budget();
    match guarded(hazard, b, move || exercise(&bytes)) {
        Verdict::Completed(dt) => dt,
        Verdict::Panicked(msg, dt) => panic!(
            "ROBUSTNESS FAILURE (panic) — hazard: {label}\n  \
             panicked after {dt:?} with: {msg}\n  \
             A malformed PDF must degrade gracefully, never panic; a panic here \
             blanks or kills the whole document at the JNI boundary."
        ),
        Verdict::TimedOut => panic!(
            "ROBUSTNESS FAILURE (hang) — hazard: {label}\n  \
             exceeded the {b:?} budget. This is the \
             \"slowdownexample\" class: unbounded work on hostile input."
        ),
    }
}

// ---------------------------------------------------------------------------
// Deterministic RNG (same shape as content.rs's arbitrary_bytes_never_panic)
// ---------------------------------------------------------------------------

struct Lcg(u64);

impl Lcg {
    fn new(seed: u64) -> Self {
        Lcg(seed)
    }
    fn next_u64(&mut self) -> u64 {
        // Numerical Recipes LCG constants; deterministic across platforms.
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        self.0
    }
    fn below(&mut self, n: usize) -> usize {
        if n == 0 {
            0
        } else {
            (self.next_u64() % n as u64) as usize
        }
    }
}

// ---------------------------------------------------------------------------
// The entry points under test
// ---------------------------------------------------------------------------

/// How far each mutant actually got. Without this the sweep could look thorough
/// while every mutant was rejected at the front door, so the report would be
/// dishonest about coverage.
#[derive(Default)]
struct Reach {
    offered: std::sync::atomic::AtomicUsize,
    loaded: std::sync::atomic::AtomicUsize,
    had_pages: std::sync::atomic::AtomicUsize,
    interpreted: std::sync::atomic::AtomicUsize,
    prims_emitted: std::sync::atomic::AtomicUsize,
}

impl Reach {
    fn bump(c: &std::sync::atomic::AtomicUsize) {
        c.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }
    fn get(c: &std::sync::atomic::AtomicUsize) -> usize {
        c.load(std::sync::atomic::Ordering::Relaxed)
    }
}

/// Drive every byte-consuming entry point the app exposes, in the order the app
/// itself uses them. Any panic or hang inside this is a bug in the renderer.
///
/// Deliberately does NOT go through the global registry (`open_document` /
/// `render_page`): the registry is an 8-entry process-global LRU
/// (registry.rs:17), so bulk use of it would evict other tests' documents and
/// make results order-dependent. `registry_open_render_close_survives_mutants`
/// covers that path separately with a small, fixed number of documents.
fn exercise_counting(bytes: &[u8], reach: &Reach) {
    Reach::bump(&reach.offered);

    // 1. Password probe — reads the trailer and /Encrypt of untrusted bytes.
    let _ = pdf_password_state(bytes);

    // 2. Load, including the custom xref-rebuild recovery path that only runs
    //    for files lopdf rejects — i.e. exactly the fuzz-interesting branch.
    let doc = match load_document_lenient(bytes) {
        Some(d) => d,
        None => return, // a clean rejection is a correct outcome
    };
    Reach::bump(&reach.loaded);

    // 3. Page enumeration.
    let pages = doc.get_pages();
    if !pages.is_empty() {
        Reach::bump(&reach.had_pages);
    }

    // 4. Render each page: content tokenize -> interpret -> annotations ->
    //    wire serialize. Capped at 4 pages so a mutant that multiplies the page
    //    tree cannot dominate the sweep's runtime.
    for (_, page_id) in pages.iter().take(4) {
        if let Ok(page) = interpret_page(&doc, *page_id) {
            Reach::bump(&reach.interpreted);
            if !page.prims.is_empty() {
                Reach::bump(&reach.prims_emitted);
            }
            let buf = crate::wire::serialize(&page);
            // The wire buffer must always be structurally sound, even for a
            // page interpreted from garbage: Kotlin reads the header
            // unconditionally, so a short buffer is an out-of-bounds read there.
            assert!(
                buf.len() >= 20,
                "wire buffer shorter than its own 20-byte header ({} bytes)",
                buf.len()
            );
            let magic = u32::from_le_bytes(buf[0..4].try_into().unwrap());
            assert_eq!(magic, 0x5044_4657, "wire magic corrupted");
            let w = f32::from_le_bytes(buf[8..12].try_into().unwrap());
            let h = f32::from_le_bytes(buf[12..16].try_into().unwrap());
            assert!(
                w.is_finite() && h.is_finite(),
                "page size must be finite, got {w}x{h} — a NaN/Inf page size \
                 propagates into the Canvas transform on the Kotlin side"
            );
        }
    }

    // 5. The second, independent path through the interpreter (text_only).
    let _ = build_index(&doc);
}

fn exercise(bytes: &[u8]) {
    exercise_counting(bytes, &Reach::default());
}

// ---------------------------------------------------------------------------
// Valid seed corpus, built in code
// ---------------------------------------------------------------------------

fn flate(data: &[u8]) -> Vec<u8> {
    use flate2::write::ZlibEncoder;
    use std::io::Write;
    let mut e = ZlibEncoder::new(Vec::new(), flate2::Compression::default());
    e.write_all(data).unwrap();
    e.finish().unwrap()
}

/// Seed 1: text + vector paths. Exercises the tokenizer, the text machinery and
/// the path/fill/stroke pipeline.
fn seed_text_and_paths() -> Vec<u8> {
    let mut doc = Document::with_version("1.7");
    let content = Content {
        operations: vec![
            Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
            Operation::new("re", vec![100.into(), 100.into(), 50.into(), 40.into()]),
            Operation::new("f", vec![]),
            Operation::new("0.5 w".into(), vec![]),
            Operation::new("m", vec![10.into(), 10.into()]),
            Operation::new("c", vec![20.into(), 40.into(), 60.into(), 80.into(), 90.into(), 20.into()]),
            Operation::new("S", vec![]),
            Operation::new("q", vec![]),
            Operation::new("W", vec![]),
            Operation::new("re", vec![0.into(), 0.into(), 300.into(), 300.into()]),
            Operation::new("n", vec![]),
            Operation::new("BT", vec![]),
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
            Operation::new("Td", vec![72.into(), 700.into()]),
            Operation::new("Tj", vec![Object::string_literal("Robustness harness seed one")]),
            Operation::new("TL", vec![14.into()]),
            Operation::new("T*", vec![]),
            Operation::new("TJ", vec![Object::Array(vec![
                Object::string_literal("kerned"),
                (-120).into(),
                Object::string_literal("text"),
            ])]),
            Operation::new("ET", vec![]),
            Operation::new("Q", vec![]),
        ],
    };
    let font_id = doc.add_object(dictionary! {
        "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
    });
    let content_id = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
    let pages_id = doc.new_object_id();
    let page_id = doc.add_object(dictionary! {
        "Type" => "Page",
        "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        "Contents" => content_id,
        "Resources" => dictionary! { "Font" => dictionary! { "F1" => font_id } },
    });
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }),
    );
    let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", catalog_id);
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();
    bytes
}

/// Seed 2: a Flate-compressed DeviceRGB image XObject. Exercises the filter
/// chain, the predictor path and the image decoder.
fn seed_image() -> Vec<u8> {
    const W: usize = 24;
    const H: usize = 16;
    let mut raw = Vec::with_capacity(W * H * 3);
    for y in 0..H {
        for x in 0..W {
            raw.push((x * 10) as u8);
            raw.push((y * 15) as u8);
            raw.push(0x40);
        }
    }
    let mut doc = Document::with_version("1.7");
    let img_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject",
            "Subtype" => "Image",
            "Width" => W as i64,
            "Height" => H as i64,
            "ColorSpace" => "DeviceRGB",
            "BitsPerComponent" => 8,
            "Filter" => "FlateDecode",
        },
        flate(&raw),
    ));
    let content = Content {
        operations: vec![
            Operation::new("q", vec![]),
            Operation::new("cm", vec![200.into(), 0.into(), 0.into(), 150.into(), 50.into(), 400.into()]),
            Operation::new("Do", vec![Object::Name(b"Im0".to_vec())]),
            Operation::new("Q", vec![]),
        ],
    };
    let content_id = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
    let pages_id = doc.new_object_id();
    let page_id = doc.add_object(dictionary! {
        "Type" => "Page",
        "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        "Contents" => content_id,
        "Resources" => dictionary! { "XObject" => dictionary! { "Im0" => img_id } },
    });
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }),
    );
    let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", catalog_id);
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();
    bytes
}

/// Seed 3: a form XObject under a transparency group, an axial shading driven by
/// a Type 2 function, a tiling pattern, and an annotation with an appearance
/// stream. This is the seed whose mutants reach the most code.
fn seed_rich() -> Vec<u8> {
    let mut doc = Document::with_version("1.7");

    let form_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject",
            "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            "Group" => dictionary! { "Type" => "Group", "S" => "Transparency" },
        },
        Content {
            operations: vec![
                Operation::new("0 0 1 rg".into(), vec![]),
                Operation::new("re", vec![0.into(), 0.into(), 80.into(), 80.into()]),
                Operation::new("f", vec![]),
            ],
        }
        .encode()
        .unwrap(),
    ));
    let fn_id = doc.add_object(dictionary! {
        "FunctionType" => 2,
        "Domain" => vec![0.into(), 1.into()],
        "C0" => vec![1.0.into(), 0.0.into(), 0.0.into()],
        "C1" => vec![0.0.into(), 0.0.into(), 1.0.into()],
        "N" => 1,
    });
    let sh_id = doc.add_object(dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => "DeviceRGB",
        "Coords" => vec![0.into(), 0.into(), 400.into(), 400.into()],
        "Function" => fn_id,
        "Extend" => vec![true.into(), true.into()],
    });
    let pat_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "Pattern",
            "PatternType" => 1,
            "PaintType" => 1,
            "TilingType" => 1,
            "BBox" => vec![0.into(), 0.into(), 10.into(), 10.into()],
            "XStep" => 10,
            "YStep" => 10,
            "Resources" => dictionary! {},
        },
        Content {
            operations: vec![
                Operation::new("re", vec![0.into(), 0.into(), 5.into(), 5.into()]),
                Operation::new("f", vec![]),
            ],
        }
        .encode()
        .unwrap(),
    ));
    let egs_id = doc.add_object(dictionary! { "Type" => "ExtGState", "ca" => 0.4, "CA" => 0.6 });

    let content = Content {
        operations: vec![
            Operation::new("q", vec![]),
            Operation::new("gs", vec![Object::Name(b"GS0".to_vec())]),
            Operation::new("Do", vec![Object::Name(b"Fm0".to_vec())]),
            Operation::new("Q", vec![]),
            Operation::new("q", vec![]),
            Operation::new("re", vec![0.into(), 0.into(), 300.into(), 300.into()]),
            Operation::new("W", vec![]),
            Operation::new("n", vec![]),
            Operation::new("sh", vec![Object::Name(b"Sh0".to_vec())]),
            Operation::new("Q", vec![]),
            Operation::new("cs", vec![Object::Name(b"Pattern".to_vec())]),
            Operation::new("scn", vec![Object::Name(b"P0".to_vec())]),
            Operation::new("re", vec![300.into(), 300.into(), 100.into(), 100.into()]),
            Operation::new("f", vec![]),
        ],
    };
    let content_id = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));

    let ap_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 50.into(), 20.into()],
        },
        Content {
            operations: vec![
                Operation::new("1 1 0 rg".into(), vec![]),
                Operation::new("re", vec![0.into(), 0.into(), 50.into(), 20.into()]),
                Operation::new("f", vec![]),
            ],
        }
        .encode()
        .unwrap(),
    ));
    let annot_id = doc.add_object(dictionary! {
        "Type" => "Annot",
        "Subtype" => "Square",
        "Rect" => vec![500.into(), 500.into(), 550.into(), 520.into()],
        "F" => 4,
        "AP" => dictionary! { "N" => ap_id },
    });

    let pages_id = doc.new_object_id();
    let page_id = doc.add_object(dictionary! {
        "Type" => "Page",
        "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        "Contents" => content_id,
        "Annots" => vec![annot_id.into()],
        "Resources" => dictionary! {
            "XObject" => dictionary! { "Fm0" => form_id },
            "Shading" => dictionary! { "Sh0" => sh_id },
            "Pattern" => dictionary! { "P0" => pat_id },
            "ExtGState" => dictionary! { "GS0" => egs_id },
        },
    });
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }),
    );
    let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", catalog_id);
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();
    bytes
}

fn seeds() -> Vec<(&'static str, Vec<u8>)> {
    vec![
        ("text_and_paths", seed_text_and_paths()),
        ("image", seed_image()),
        ("rich", seed_rich()),
    ]
}

// ---------------------------------------------------------------------------
// Raw-PDF assembler for adversarial constructs
// ---------------------------------------------------------------------------

/// Assemble a byte-exact PDF from object bodies with a CORRECT classic xref, so
/// that a fixture is malformed only where it is meant to be. Object ids must
/// start at 1; gaps become free entries.
///
/// Being byte-exact matters: with a broken xref every fixture would go through
/// the rebuild path instead of the construct it is trying to test.
fn raw_pdf(objects: &[(u32, Vec<u8>)], trailer_extra: &str) -> Vec<u8> {
    let mut out = Vec::from(&b"%PDF-1.7\n%\xE2\xE3\xCF\xD3\n"[..]);
    let mut offsets: Vec<(u32, usize)> = Vec::new();
    for (id, body) in objects {
        offsets.push((*id, out.len()));
        out.extend_from_slice(format!("{id} 0 obj\n").as_bytes());
        out.extend_from_slice(body);
        out.extend_from_slice(b"\nendobj\n");
    }
    let xref_at = out.len();
    let max_id = objects.iter().map(|(i, _)| *i).max().unwrap_or(0);
    out.extend_from_slice(format!("xref\n0 {}\n", max_id + 1).as_bytes());
    out.extend_from_slice(b"0000000000 65535 f \n");
    for id in 1..=max_id {
        match offsets.iter().find(|(i, _)| *i == id) {
            Some((_, off)) => out.extend_from_slice(format!("{off:010} 00000 n \n").as_bytes()),
            None => out.extend_from_slice(b"0000000000 65535 f \n"),
        }
    }
    out.extend_from_slice(
        format!(
            "trailer\n<< /Size {} {} >>\nstartxref\n{}\n%%EOF\n",
            max_id + 1,
            trailer_extra,
            xref_at
        )
        .as_bytes(),
    );
    out
}

/// A raw stream object whose dictionary is written verbatim, so `/Length` can
/// lie, and whose body is arbitrary bytes.
fn raw_stream(dict: &str, body: &[u8]) -> Vec<u8> {
    let mut v = Vec::new();
    v.extend_from_slice(dict.as_bytes());
    v.extend_from_slice(b"\nstream\n");
    v.extend_from_slice(body);
    v.extend_from_slice(b"\nendstream");
    v
}

/// Standard catalog + single-page skeleton for `raw_pdf`, so each adversarial
/// fixture only has to describe the thing it is attacking.
/// Objects 1=Catalog, 2=Pages, 3=Page, 4=Contents; caller supplies 5.. .
fn raw_one_page(page_extra: &str, content: &[u8], mut rest: Vec<(u32, Vec<u8>)>) -> Vec<u8> {
    let mut objs: Vec<(u32, Vec<u8>)> = vec![
        (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
        (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
        (
            3,
            format!(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] \
                 /Contents 4 0 R {page_extra} >>"
            )
            .into_bytes(),
        ),
        (
            4,
            raw_stream(&format!("<< /Length {} >>", content.len()), content),
        ),
    ];
    objs.append(&mut rest);
    raw_pdf(&objs, "/Root 1 0 R")
}

// ---------------------------------------------------------------------------
// Mutators
// ---------------------------------------------------------------------------

fn find_all(hay: &[u8], needle: &[u8]) -> Vec<usize> {
    if needle.is_empty() || hay.len() < needle.len() {
        return Vec::new();
    }
    (0..=hay.len() - needle.len())
        .filter(|&i| &hay[i..i + needle.len()] == needle)
        .collect()
}

/// Overwrite `len` bytes at `at` with `fill`, clamped to the buffer.
fn splat(bytes: &[u8], at: usize, len: usize, fill: u8) -> Vec<u8> {
    let mut v = bytes.to_vec();
    let end = (at + len).min(v.len());
    if at < v.len() {
        v[at..end].fill(fill);
    }
    v
}

/// Replace the ASCII digit run starting at `at` with `replacement`, keeping the
/// rest of the file intact. Used to corrupt offsets and /Length values.
fn replace_digits_at(bytes: &[u8], at: usize, replacement: &str) -> Vec<u8> {
    let mut i = at;
    while i < bytes.len() && bytes[i].is_ascii_whitespace() {
        i += 1;
    }
    let start = i;
    while i < bytes.len() && bytes[i].is_ascii_digit() {
        i += 1;
    }
    if start == i {
        return bytes.to_vec();
    }
    let mut v = Vec::with_capacity(bytes.len() + replacement.len());
    v.extend_from_slice(&bytes[..start]);
    v.extend_from_slice(replacement.as_bytes());
    v.extend_from_slice(&bytes[i..]);
    v
}

/// The full deterministic mutation schedule for one seed document.
fn mutants(seed_name: &str, base: &[u8]) -> Vec<(String, Vec<u8>)> {
    let mut out: Vec<(String, Vec<u8>)> = Vec::new();
    let n = base.len();
    let tag = |what: &str| format!("{seed_name}/{what}");

    // (a) Truncate at every 1/16th of the length — the "crasheshalfway" /
    //     partial-download shape.
    for k in 0..16 {
        let cut = n * k / 16;
        out.push((tag(&format!("truncate@{k}/16={cut}")), base[..cut].to_vec()));
    }
    // Off-by-one truncations around the very end, where the trailer lives.
    for back in [1usize, 2, 5, 9, 20] {
        if n > back {
            out.push((tag(&format!("truncate-last-{back}")), base[..n - back].to_vec()));
        }
    }

    // (b) Zero out a 64-byte run at every 1/8th, plus 0xFF runs.
    for k in 0..8 {
        let at = n * k / 8;
        out.push((tag(&format!("zero64@{k}/8")), splat(base, at, 64, 0x00)));
        out.push((tag(&format!("ff64@{k}/8")), splat(base, at, 64, 0xFF)));
    }

    // (c) Single-bit flips at deterministic positions.
    let mut rng = Lcg::new(FUZZ_SEED ^ seed_name.len() as u64);
    for i in 0..180 {
        let pos = rng.below(n.max(1));
        let bit = rng.below(8);
        let mut v = base.to_vec();
        if pos < v.len() {
            v[pos] ^= 1u8 << bit;
        }
        out.push((tag(&format!("bitflip#{i}@{pos}:{bit}")), v));
    }

    // (d) Corrupt the startxref offset — the single most common real-world
    //     damage, and the trigger for the custom rebuild path.
    for sx in find_all(base, b"startxref") {
        for bogus in ["0", "1", "999999999999", "18446744073709551615", "4294967295"] {
            out.push((
                tag(&format!("startxref={bogus}")),
                replace_digits_at(base, sx + b"startxref".len(), bogus),
            ));
        }
        // Point startxref just off the `xref` keyword (the documented
        // real-world case that motivated the rebuild).
        out.push((
            tag("startxref+1"),
            replace_digits_at(base, sx + b"startxref".len(), &format!("{}", n.saturating_sub(1))),
        ));
    }

    // (e) Corrupt xref table entry offsets.
    if let Some(xr) = find_all(base, b"\nxref").first().copied() {
        out.push((tag("xref-entries-zeroed"), splat(base, xr + 1, 256, b'0')));
        out.push((tag("xref-keyword-broken"), splat(base, xr + 1, 4, b'X')));
    }

    // (f) Corrupt every /Length: far too large, zero, and negative.
    for lp in find_all(base, b"/Length") {
        for bogus in ["2147483647", "0", "1", "999999999999"] {
            out.push((
                tag(&format!("Length={bogus}@{lp}")),
                replace_digits_at(base, lp + b"/Length".len(), bogus),
            ));
        }
    }

    // (g) Replace a stream body with garbage, so a declared Flate/DCT filter
    //     gets bytes that cannot possibly decode.
    for (kw, skip) in [(&b"stream\n"[..], 7usize), (&b"stream\r\n"[..], 8)] {
        for sp in find_all(base, kw) {
            let body = sp + skip;
            out.push((tag(&format!("stream-body-garbage@{sp}")), splat(base, body, 128, 0xA5)));
            out.push((tag(&format!("stream-body-zeros@{sp}")), splat(base, body, 128, 0x00)));
        }
    }

    // (h) Remove the trailer entirely.
    if let Some(tp) = find_all(base, b"trailer").last().copied() {
        out.push((tag("trailer-removed"), base[..tp].to_vec()));
        let mut no_kw = base.to_vec();
        no_kw[tp..tp + 7].copy_from_slice(b"XXXXXXX");
        out.push((tag("trailer-keyword-clobbered"), no_kw));
    }
    // Remove /Root from the trailer.
    if let Some(rp) = find_all(base, b"/Root").last().copied() {
        let mut v = base.to_vec();
        v[rp..rp + 5].copy_from_slice(b"/Rxot");
        out.push((tag("Root-key-renamed"), v));
    }

    // (i) Duplicate an object header, so two definitions claim the same id.
    for id in [1u32, 2, 3] {
        let needle = format!("\n{id} 0 obj");
        if let Some(p) = find_all(base, needle.as_bytes()).first().copied() {
            let mut v = Vec::with_capacity(base.len() + needle.len());
            v.extend_from_slice(&base[..p]);
            v.extend_from_slice(needle.as_bytes());
            v.extend_from_slice(b"\n<< >>\nendobj");
            v.extend_from_slice(&base[p..]);
            out.push((tag(&format!("dup-header-{id}")), v));
        }
    }
    // A bogus enormous object number, which is what previously sized the
    // rebuilt xref table at ~20 GB.
    {
        let mut v = base.to_vec();
        v.extend_from_slice(b"\n999999999 0 obj\n<< >>\nendobj\n");
        out.push((tag("bogus-high-object-id"), v));
    }

    // (j) Swap the object-header digits inside the body, so offsets point at the
    //     wrong object entirely.
    for p in find_all(base, b" 0 obj") {
        if p >= 1 && base[p - 1].is_ascii_digit() {
            let mut v = base.to_vec();
            v[p - 1] = b'7';
            out.push((tag(&format!("objnum-repointed@{p}")), v));
        }
    }

    out
}

// ---------------------------------------------------------------------------
// LAYER 1 — mutation testing over valid documents
// ---------------------------------------------------------------------------

/// Run a labelled corpus through `exercise` under the guard, and fail once with
/// every finding rather than at the first one.
fn sweep(corpus: Vec<(String, Vec<u8>)>, budget: Duration) {
    let total = corpus.len();
    let mut failures: Vec<String> = Vec::new();
    let mut slowest: Vec<(Duration, String)> = Vec::new();
    let reach = std::sync::Arc::new(Reach::default());

    for (label, bytes) in corpus {
        let l = label.clone();
        let r = std::sync::Arc::clone(&reach);
        match guarded(&label, budget, move || exercise_counting(&bytes, &r)) {
            Verdict::Completed(dt) => slowest.push((dt, l)),
            Verdict::Panicked(msg, dt) => {
                failures.push(format!("  PANIC  [{l}] after {dt:?}: {msg}"))
            }
            Verdict::TimedOut => {
                failures.push(format!("  HANG   [{l}] exceeded {budget:?}"))
            }
        }
    }

    slowest.sort_by(|a, b| b.0.cmp(&a.0));
    let worst: Vec<String> = slowest
        .iter()
        .take(5)
        .map(|(d, l)| format!("{l} = {d:?}"))
        .collect();
    eprintln!(
        "[robustness] {total} mutants exercised, seed 0x{FUZZ_SEED:016X}.\n  \
         reached: loaded={} withPages={} interpreted={} emittedPrims={}\n  \
         slowest: {}",
        Reach::get(&reach.loaded),
        Reach::get(&reach.had_pages),
        Reach::get(&reach.interpreted),
        Reach::get(&reach.prims_emitted),
        worst.join(", ")
    );

    assert!(
        failures.is_empty(),
        "ROBUSTNESS FAILURES: {} of {total} mutants did not degrade gracefully \
         (FUZZ_SEED = 0x{FUZZ_SEED:016X}; each label is the exact deterministic \
         recipe, reproduce with `mutants(seed, base)`):\n{}",
        failures.len(),
        failures.join("\n")
    );
}

/// The core invariant: no byte-level corruption of a valid PDF may make any
/// entry point panic, hang, or emit a structurally invalid wire buffer.
#[test]
fn byte_level_corruption_of_valid_pdfs_never_panics_or_hangs() {
    let mut corpus = Vec::new();
    for (name, base) in seeds() {
        corpus.extend(mutants(name, &base));
    }
    assert!(
        corpus.len() > 700,
        "mutation schedule shrank unexpectedly ({} mutants) — a seed document \
         probably failed to build",
        corpus.len()
    );
    sweep(corpus, mutant_budget());
}

/// Every seed must render cleanly BEFORE mutation. Without this the sweep above
/// could pass vacuously by never reaching real code.
#[test]
fn seed_documents_render_successfully_before_mutation() {
    for (name, bytes) in seeds() {
        let doc = load_document_lenient(&bytes)
            .unwrap_or_else(|| panic!("seed {name} must load"));
        let pages = doc.get_pages();
        assert_eq!(pages.len(), 1, "seed {name} must have one page");
        let page_id = *pages.values().next().unwrap();
        let page = interpret_page(&doc, page_id)
            .unwrap_or_else(|e| panic!("seed {name} must interpret: {e}"));
        assert!(
            !page.prims.is_empty(),
            "seed {name} produced no primitives — it is not exercising the \
             renderer, so its mutants would not either"
        );
        assert_eq!(page.width, 612.0);
        let buf = crate::wire::serialize(&page);
        assert!(buf.len() > 20, "seed {name} wire buffer is header-only");
    }
}

/// Pure byte soup: no PDF structure at all. Cheap, and it covers the shapes a
/// structured mutator never produces (e.g. a file that is only a header, or
/// only binary).
#[test]
fn arbitrary_byte_strings_never_panic() {
    let mut rng = Lcg::new(FUZZ_SEED);
    let mut corpus = Vec::new();

    corpus.push(("empty".to_string(), Vec::new()));
    corpus.push(("header-only".to_string(), b"%PDF-1.7\n".to_vec()));
    corpus.push(("nul".to_string(), vec![0u8; 64]));
    corpus.push(("eof-only".to_string(), b"%%EOF".to_vec()));
    corpus.push((
        "keywords-only".to_string(),
        b"%PDF-1.7 obj endobj stream endstream trailer xref startxref R".to_vec(),
    ));
    for i in 0..120 {
        let len = rng.below(400) + 1;
        let bytes: Vec<u8> = (0..len).map(|_| (rng.next_u64() & 0xFF) as u8).collect();
        corpus.push((format!("random#{i}/{len}b"), bytes));
    }
    // Random bytes with a plausible PDF skeleton grafted on, so the parser gets
    // far enough in to matter.
    for i in 0..120 {
        let len = rng.below(400) + 1;
        let mut bytes = b"%PDF-1.7\n1 0 obj\n".to_vec();
        bytes.extend((0..len).map(|_| (rng.next_u64() & 0xFF) as u8));
        bytes.extend_from_slice(b"\nendobj\ntrailer\n<< /Root 1 0 R >>\nstartxref\n9\n%%EOF\n");
        corpus.push((format!("random-in-skeleton#{i}/{len}b"), bytes));
    }
    sweep(corpus, mutant_budget());
}

/// The registry path (`open_document` -> `page_count` -> `render_page` ->
/// `close_document`) is what JNI actually calls, so it gets its own pass.
///
/// Deliberately only a handful of mutants. The registry is a process-global
/// 8-entry LRU (MAX_REG_DOCS, registry.rs:17) that evicts on insert, so a bulk
/// sweep through it would evict documents belonging to tests running in parallel
/// and make the whole suite order-dependent. Each document is closed immediately
/// after use to keep at most one of ours resident.
///
/// Asserts only "no panic, no hang", never success: a concurrent test can
/// legitimately evict our handle, and `None` is a correct outcome anyway.
#[test]
fn registry_open_render_close_survives_mutants() {
    let base = seed_rich();
    let all = mutants("rich", &base);
    // A fixed, reproducible slice: every 150th mutant.
    let picked: Vec<(String, Vec<u8>)> =
        all.into_iter().enumerate().filter(|(i, _)| i % 150 == 0).map(|(_, m)| m).collect();

    let mut failures = Vec::new();
    for (label, bytes) in picked {
        let l = label.clone();
        let v = guarded(&label, mutant_budget(), move || {
            let handle = open_document(&bytes);
            if handle == 0 {
                return; // clean rejection
            }
            let n = page_count(handle);
            for i in 0..n.min(4) {
                let _ = render_page(handle, i);
            }
            // Out-of-range indices must be rejected, not indexed. Negative
            // indices are deliberately NOT probed here — they hit a separate,
            // already-identified defect (see
            // `negative_page_indices_do_not_overflow_the_page_lookup`) which
            // would otherwise mask any mutant-specific finding in this sweep.
            let _ = render_page(handle, i32::MAX);
            let _ = render_page(handle, n);
            let _ = document_text(handle);
            let _ = list_annotations(handle, 0);
            let _ = list_form_fields(handle, 0);
            let _ = list_links(handle, 0);
            let _ = list_outline(handle);
            let _ = search_document(handle, "a");
            close_document(handle);
        });
        if v.is_failure() {
            failures.push(format!("  [{l}] {v:?}"));
        }
    }
    assert!(
        failures.is_empty(),
        "registry round-trip failed on {} mutants (seed 0x{FUZZ_SEED:016X}):\n{}",
        failures.len(),
        failures.join("\n")
    );
}

/// HAZARD: a negative page index arriving from the JNI boundary must be
/// rejected, not converted to `u32` and incremented.
///
/// Two sites computed `(index as u32) + 1`, which for `index == -1` is
/// `0xFFFF_FFFFu32 + 1` — a panic in a debug build, a silent wrap to 0 in
/// release. `renderPage(long, int)` and the annotation / form / link listing
/// entry points all take an unvalidated `jint` from Kotlin, so -1 is reachable
/// input rather than a synthetic case.
///
///   annotations.rs `nth_page_id`  - FIXED; now
///                                   `u32::try_from(index).ok()?.checked_add(1)?`
///   docedit.rs `render_page`      - FIXED the same way
/// `jni_bindings.rs` also stopped coercing a negative index with `index.max(0) as
/// usize` in `removePage` / `movePage`, which edited page 0 instead of refusing.
///
/// The `render_page` half used to panic *while holding the process-global registry
/// mutex* (docedit.rs:326), which poisons it. Production code is poison-tolerant
/// (`.unwrap_or_else(|p| p.into_inner())` everywhere) so the app survived, but
/// `tests::edit_render_tests::radio_group_clears_siblings` locks the registry with a
/// bare `.unwrap()` (tests.rs:743/747/756) and failed as collateral. This test
/// therefore still clears the poison explicitly after catching a panic, so a
/// regression stays visible without breaking an unrelated test.
#[test]
fn negative_page_indices_do_not_overflow_the_page_lookup() {
    let bytes = seed_text_and_paths();
    let v = guarded("negative page index", construct_budget(), move || {
        let doc = load_document_lenient(&bytes).expect("the seed must load");
        let mut panics: Vec<String> = Vec::new();

        // Lock-free site: safe to probe directly.
        for idx in [-1i32, -2, -1000, i32::MIN, i32::MIN + 1] {
            match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| nth_page_id(&doc, idx))) {
                Ok(got) => assert!(
                    got.is_none(),
                    "nth_page_id({idx}) must not resolve to a page, got {got:?}"
                ),
                Err(e) => panics.push(format!("nth_page_id(index={idx}): {}", panic_text(e))),
            }
        }

        // Registry-backed sites. Each panic poisons the registry mutex, so clear
        // it before continuing; leaving it poisoned would fail unrelated tests
        // that lock it with a bare `.unwrap()`.
        let handle = open_document(&bytes);
        assert_ne!(handle, 0, "the seed must open");
        for idx in [-1i32, -2, i32::MIN] {
            for (name, call) in [
                ("render_page", 0u8),
                ("list_annotations", 1),
                ("list_form_fields", 2),
                ("list_links", 3),
            ] {
                let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| match call {
                    0 => render_page(handle, idx).map(|_| ()),
                    1 => list_annotations(handle, idx).map(|_| ()),
                    2 => list_form_fields(handle, idx).map(|_| ()),
                    _ => list_links(handle, idx).map(|_| ()),
                }));
                if let Err(e) = r {
                    panics.push(format!("{name}(index={idx}): {}", panic_text(e)));
                    registry().clear_poison();
                    index_cache().clear_poison();
                }
            }
        }
        close_document(handle);

        assert!(
            panics.is_empty(),
            "ROBUSTNESS FAILURE — a negative page index must be rejected before \
             the `as u32` cast; `(index as u32) + 1` overflows for index == -1 \
             (docedit.rs:331). {} panicking call(s):\n  {}",
            panics.len(),
            panics.join("\n  ")
        );
    });
    assert!(!v.is_failure(), "{v:?}");
}

/// A handle that was never opened must be inert on every entry point rather than
/// indexing a stale slot.
///
/// The probe values are constrained to handles the registry can never hand out:
/// `next_handle()` (registry.rs:26) counts UP from 1, so every live handle is a
/// small positive integer, and search.rs:277 reserves `i64::MAX - 777`. Probing
/// `1` or `i64::MAX - 777` here would `close_document` a document another test is
/// actively using — which is exactly what this test did in its first version, and
/// it broke `tests::edit_render_tests::radio_group_clears_siblings`.
#[test]
fn unknown_and_closed_handles_are_inert() {
    let v = guarded("stale-handles", construct_budget(), || {
        for h in [0i64, -1, -999_999, i64::MIN, i64::MIN + 1, i64::MAX] {
            assert_eq!(page_count(h), 0, "unknown handle {h} must report 0 pages");
            assert!(render_page(h, 0).is_none());
            assert!(render_page(h, i32::MAX).is_none());
            assert!(document_text(h).is_none());
            assert!(search_document(h, "x").is_none());
            close_document(h); // must be idempotent
            close_document(h);
        }
    });
    assert!(!v.is_failure(), "stale-handle probe failed: {v:?}");
}

// ---------------------------------------------------------------------------
// LAYER 2 — adversarial constructs, one named hazard each
// ---------------------------------------------------------------------------

/// Compressed data that expands to `total` zero bytes, built without ever
/// holding `total` bytes in memory.
fn zero_bomb(total: usize) -> Vec<u8> {
    use flate2::write::ZlibEncoder;
    use std::io::Write;
    let mut e = ZlibEncoder::new(Vec::new(), flate2::Compression::fast());
    let chunk = vec![0u8; 1 << 20];
    let mut written = 0usize;
    while written < total {
        let take = chunk.len().min(total - written);
        e.write_all(&chunk[..take]).unwrap();
        written += take;
    }
    e.finish().unwrap()
}

/// HAZARD: a Flate stream that decompresses to far more than the decoded-bytes
/// cap must be refused, not inflated into RAM. filters.rs caps decoded output at
/// 256 MB; this bomb declares 320 MB.
#[test]
fn flate_decompression_bomb_is_capped_not_inflated() {
    let bomb = zero_bomb(320 * 1024 * 1024);
    assert!(
        bomb.len() < 4 * 1024 * 1024,
        "the bomb fixture itself must stay small relative to what it expands to \
         ({} bytes compressed vs 320 MB decompressed)",
        bomb.len()
    );
    // As page content... The flate bomb gets FLOOD_BUDGET rather than
    // CONSTRUCT_BUDGET: even when correctly capped, walking up to the 256 MB
    // `MAX_DECODED_BYTES` limit (filters.rs:348) is real work in a debug build,
    // and it is slower still when the suite runs in parallel. Measured ~7s alone.
    // A cap failure would show up as an OOM abort or a multi-minute run, not as a
    // borderline time, so a generous budget does not weaken the test.
    let pdf = raw_pdf(
        &[
            (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
            (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
            (
                3,
                b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>".to_vec(),
            ),
            (
                4,
                raw_stream(
                    &format!("<< /Length {} /Filter /FlateDecode >>", bomb.len()),
                    &bomb,
                ),
            ),
        ],
        "/Root 1 0 R",
    );
    let v = guarded("flate bomb as content", flood_budget(), move || exercise(&pdf));
    assert!(
        !v.is_failure(),
        "ROBUSTNESS FAILURE — 320 MB flate bomb as page content stream: {v:?}"
    );

    // ...and as image data, which takes a different decode path.
    let pdf = raw_one_page(
        "/Resources << /XObject << /Im0 5 0 R >> >>",
        b"q 600 0 0 700 0 0 cm /Im0 Do Q",
        vec![(
            5,
            raw_stream(
                &format!(
                    "<< /Type /XObject /Subtype /Image /Width 8192 /Height 8192 \
                     /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode \
                     /Length {} >>",
                    bomb.len()
                ),
                &bomb,
            ),
        )],
    );
    let v = guarded("flate bomb as image", flood_budget(), move || exercise(&pdf));
    assert!(
        !v.is_failure(),
        "ROBUSTNESS FAILURE — 320 MB flate bomb as image XObject data: {v:?}"
    );
}

/// HAZARD: a bogus `999999999 0 obj` header in a file that needs xref rebuilding
/// used to size the rebuilt table by max object id, allocating ~20 GB.
#[test]
fn bogus_high_object_number_does_not_allocate_a_giant_xref() {
    // No xref at all, so recovery is forced.
    let mut pdf = Vec::from(&b"%PDF-1.7\n"[..]);
    pdf.extend_from_slice(b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
    pdf.extend_from_slice(b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
    pdf.extend_from_slice(
        b"3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n",
    );
    pdf.extend_from_slice(b"4294967295 0 obj\n<< >>\nendobj\n");
    pdf.extend_from_slice(b"999999999 0 obj\n<< >>\nendobj\n");
    pdf.extend_from_slice(b"%%EOF\n");
    assert_construct_survives("bogus high object id during xref rebuild", pdf);
}

/// HAZARD: /W in a CIDFont, and a ToUnicode bfrange, declaring billions of CIDs
/// must be clamped to MAX_CID rather than sized from the declared range.
#[test]
fn absurd_cid_ranges_in_w_and_tounicode_are_clamped() {
    let tounicode = b"/CIDInit /ProcSet findresource begin\n\
        12 dict begin begincmap\n\
        1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n\
        3 beginbfrange\n\
        <0000> <FFFFFFFF> <0041>\n\
        <0000> <7FFFFFFF> [<0042>]\n\
        <0001> <0000> <0043>\n\
        endbfrange\nendcmap end end\n"
        .to_vec();
    let pdf = raw_one_page(
        "/Resources << /Font << /F1 5 0 R >> >>",
        b"BT /F1 24 Tf 50 700 Td <00410042> Tj ET",
        vec![
            (
                5,
                b"<< /Type /Font /Subtype /Type0 /BaseFont /Test /Encoding /Identity-H \
                  /DescendantFonts [6 0 R] /ToUnicode 7 0 R >>"
                    .to_vec(),
            ),
            (
                6,
                b"<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Test \
                  /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> \
                  /DW 1000 \
                  /W [0 [500] 1 4294967295 600 0 2147483647 [700 700 700]] >>"
                    .to_vec(),
            ),
            (
                7,
                raw_stream(&format!("<< /Length {} >>", tounicode.len()), &tounicode),
            ),
        ],
    );
    assert_construct_survives("/W and bfrange declaring billions of CIDs", pdf);
}

/// HAZARD: a TrueType cmap format 12/13 subtable whose group count is absurd
/// must not drive an allocation or loop sized from the declared count.
#[test]
fn truetype_cmap_format_12_absurd_group_count_is_rejected() {
    // Minimal sfnt: one `cmap` table, format 12 with nGroups = 0xFFFFFFFF and no
    // group data at all.
    fn sfnt_with_cmap(format: u16, n_groups: u32) -> Vec<u8> {
        let mut cmap = Vec::new();
        cmap.extend_from_slice(&0u16.to_be_bytes()); // version
        cmap.extend_from_slice(&1u16.to_be_bytes()); // numTables
        cmap.extend_from_slice(&3u16.to_be_bytes()); // platformID
        cmap.extend_from_slice(&10u16.to_be_bytes()); // encodingID (UCS-4)
        cmap.extend_from_slice(&12u32.to_be_bytes()); // offset to subtable
        cmap.extend_from_slice(&format.to_be_bytes()); // format 12 or 13
        cmap.extend_from_slice(&0u16.to_be_bytes()); // reserved
        cmap.extend_from_slice(&0xFFFF_FFFFu32.to_be_bytes()); // length (lies)
        cmap.extend_from_slice(&0u32.to_be_bytes()); // language
        cmap.extend_from_slice(&n_groups.to_be_bytes()); // nGroups (absurd)
        // No group records follow — the table ends here.

        let mut font = Vec::new();
        font.extend_from_slice(&0x0001_0000u32.to_be_bytes()); // sfnt version
        font.extend_from_slice(&1u16.to_be_bytes()); // numTables
        font.extend_from_slice(&16u16.to_be_bytes()); // searchRange
        font.extend_from_slice(&0u16.to_be_bytes()); // entrySelector
        font.extend_from_slice(&0u16.to_be_bytes()); // rangeShift
        font.extend_from_slice(b"cmap");
        font.extend_from_slice(&0u32.to_be_bytes()); // checksum
        font.extend_from_slice(&(12u32 + 16).to_be_bytes()); // offset
        font.extend_from_slice(&(cmap.len() as u32).to_be_bytes()); // length
        font.extend_from_slice(&cmap);
        font
    }

    for format in [12u16, 13] {
        for n_groups in [0xFFFF_FFFFu32, 0x7FFF_FFFF, 0x0010_0000] {
            let font = sfnt_with_cmap(format, n_groups);
            let pdf = raw_one_page(
                "/Resources << /Font << /F1 5 0 R >> >>",
                b"BT /F1 24 Tf 50 700 Td (ABC) Tj ET",
                vec![
                    (
                        5,
                        b"<< /Type /Font /Subtype /TrueType /BaseFont /Bogus \
                          /FirstChar 65 /LastChar 67 /Widths [500 500 500] \
                          /FontDescriptor 6 0 R >>"
                            .to_vec(),
                    ),
                    (
                        6,
                        b"<< /Type /FontDescriptor /FontName /Bogus /Flags 4 \
                          /ItalicAngle 0 /Ascent 700 /Descent -200 /CapHeight 700 \
                          /StemV 80 /FontBBox [0 0 1000 1000] /FontFile2 7 0 R >>"
                            .to_vec(),
                    ),
                    (
                        7,
                        raw_stream(&format!("<< /Length1 {} /Length {} >>", font.len(), font.len()), &font),
                    ),
                ],
            );
            assert_construct_survives(
                &format!("TrueType cmap format {format} with nGroups={n_groups}"),
                pdf,
            );
        }
    }
}

/// HAZARD: a Type 0 (sampled) function's /Size and /BitsPerSample drive a
/// product and a 2^n interpolation loop; both must be bounded independently of
/// the declared values.
#[test]
fn sampled_function_absurd_size_and_bits_do_not_drive_a_2n_loop() {
    let cases: [(&str, &str, &str); 5] = [
        ("huge-2d", "[2000000000 2000000000]", "32"),
        ("eight-inputs", "[64 64 64 64 64 64 64 64]", "32"),
        ("bits-overflow", "[4294967295]", "32"),
        ("zero-size", "[0 0]", "8"),
        ("bad-bits", "[16 16]", "4294967295"),
    ];
    for (name, size, bits) in cases {
        let domain: String = if size.split_whitespace().count() > 2 {
            // One [min max] pair per input dimension.
            let dims = size.trim_start_matches('[').trim_end_matches(']').split_whitespace().count();
            (0..dims).map(|_| "0 1 ").collect()
        } else {
            "0 1 0 1".to_string()
        };
        let pdf = raw_one_page(
            "/Resources << /Shading << /Sh0 5 0 R >> >>",
            b"q 0 0 600 700 re W n /Sh0 sh Q",
            vec![
                (
                    5,
                    b"<< /ShadingType 2 /ColorSpace /DeviceRGB \
                      /Coords [0 0 600 700] /Function 6 0 R /Extend [true true] >>"
                        .to_vec(),
                ),
                (
                    6,
                    raw_stream(
                        &format!(
                            "<< /FunctionType 0 /Domain [{domain}] /Range [0 1 0 1 0 1] \
                             /Size {size} /BitsPerSample {bits} /Length 8 >>"
                        ),
                        &[0u8; 8],
                    ),
                ),
            ],
        );
        assert_construct_survives(&format!("sampled function {name}: /Size {size} /BitsPerSample {bits}"), pdf);
    }
}

/// HAZARD: a font program truncated mid-structure must abort parsing, not index
/// past the end of the buffer.
#[test]
fn truncated_font_programs_abort_instead_of_indexing_past_the_end() {
    // A CFF header + a partial INDEX, cut at every plausible boundary.
    let cff: Vec<u8> = vec![
        0x01, 0x00, 0x04, 0x01, // major, minor, hdrSize, offSize
        0x00, 0x01, 0x01, 0x01, 0x04, // Name INDEX: count 1, offSize 1, offsets
        b'T', b'e', b's', // partial name data
        0x00, 0x01, 0x01, 0x01, 0x1E, // TopDICT INDEX header, truncated body
        0x0F, 0x1C, // partial charset operator
    ];
    // A Type 1 program: plain-text prologue then eexec with a truncated body.
    let mut t1: Vec<u8> = b"%!PS-AdobeFont-1.0: Bogus\n/FontName /Bogus def\n\
        /Encoding 256 array\n0 1 255 {1 index exch /.notdef put} for\n\
        dup 65 /A put\nreadonly def\ncurrentdict end\ncurrentfile eexec\n"
        .to_vec();
    t1.extend_from_slice(&[0x11, 0x22, 0x33, 0x44, 0xAA, 0xBB, 0xCC]);

    for (name, prog, kind) in [
        ("CFF (FontFile3)", cff.clone(), "/FontFile3 7 0 R"),
        ("Type1 (FontFile)", t1.clone(), "/FontFile 7 0 R"),
        ("TrueType (FontFile2)", cff.clone(), "/FontFile2 7 0 R"),
    ] {
        // Every prefix length, so the cut lands mid-INDEX, mid-DICT, mid-charstring.
        for cut in 0..=prog.len() {
            let part = &prog[..cut];
            let pdf = raw_one_page(
                "/Resources << /Font << /F1 5 0 R >> >>",
                b"BT /F1 24 Tf 50 700 Td (ABC) Tj ET",
                vec![
                    (
                        5,
                        b"<< /Type /Font /Subtype /Type1 /BaseFont /Bogus \
                          /FirstChar 65 /LastChar 67 /Widths [500 500 500] \
                          /FontDescriptor 6 0 R >>"
                            .to_vec(),
                    ),
                    (
                        6,
                        format!(
                            "<< /Type /FontDescriptor /FontName /Bogus /Flags 4 \
                             /ItalicAngle 0 /Ascent 700 /Descent -200 /CapHeight 700 \
                             /StemV 80 /FontBBox [0 0 1000 1000] {kind} >>"
                        )
                        .into_bytes(),
                    ),
                    (
                        7,
                        raw_stream(
                            &format!(
                                "<< /Length {} /Length1 {} /Length2 0 /Length3 0 /Subtype /Type1C >>",
                                part.len(),
                                part.len()
                            ),
                            part,
                        ),
                    ),
                ],
            );
            assert_construct_survives(&format!("{name} truncated to {cut}/{} bytes", prog.len()), pdf);
        }
    }
}

/// HAZARD: a Form XObject that paints itself, and a pattern that paints itself,
/// are unbounded recursion unless the depth guards hold.
#[test]
fn self_referential_form_and_pattern_terminate() {
    // Form XObject whose resources name itself.
    let pdf = raw_one_page(
        "/Resources << /XObject << /Fm0 5 0 R >> >>",
        b"/Fm0 Do",
        vec![(
            5,
            raw_stream(
                "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] \
                 /Resources << /XObject << /Fm0 5 0 R >> >> /Length 8 >>",
                b"/Fm0 Do",
            ),
        )],
    );
    assert_construct_survives("Form XObject that paints itself", pdf);

    // Two forms that paint each other.
    let pdf = raw_one_page(
        "/Resources << /XObject << /A 5 0 R >> >>",
        b"/A Do",
        vec![
            (
                5,
                raw_stream(
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] \
                     /Resources << /XObject << /B 6 0 R >> >> /Length 6 >>",
                    b"/B Do",
                ),
            ),
            (
                6,
                raw_stream(
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] \
                     /Resources << /XObject << /A 5 0 R >> >> /Length 6 >>",
                    b"/A Do",
                ),
            ),
        ],
    );
    assert_construct_survives("mutually recursive Form XObjects", pdf);

    // Tiling pattern whose cell fills with itself.
    let pdf = raw_one_page(
        "/Resources << /Pattern << /P0 5 0 R >> >>",
        b"/Pattern cs /P0 scn 0 0 400 400 re f",
        vec![(
            5,
            raw_stream(
                "<< /Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 \
                 /BBox [0 0 8 8] /XStep 8 /YStep 8 \
                 /Resources << /Pattern << /P0 5 0 R >> >> /Length 40 >>",
                b"/Pattern cs /P0 scn 0 0 8 8 re f       ",
            ),
        )],
    );
    assert_construct_survives("tiling pattern that fills with itself", pdf);

    // Shading pattern whose function references itself.
    let pdf = raw_one_page(
        "/Resources << /Pattern << /P0 5 0 R >> >>",
        b"/Pattern cs /P0 scn 0 0 400 400 re f",
        vec![
            (
                5,
                b"<< /Type /Pattern /PatternType 2 /Shading 6 0 R >>".to_vec(),
            ),
            (
                6,
                b"<< /ShadingType 3 /ColorSpace /DeviceRGB \
                  /Coords [200 200 0 200 200 200] /Function 6 0 R >>"
                    .to_vec(),
            ),
        ],
    );
    assert_construct_survives("shading whose /Function references the shading", pdf);
}

/// HAZARD: a soft mask whose transparency group is the page itself (or a form
/// that reaches back to the page) is a cycle through a different code path than
/// plain `Do` recursion.
#[test]
fn soft_mask_group_referencing_the_page_terminates() {
    // /SMask /G points at a form whose resources contain the same ExtGState, so
    // applying the mask re-enters mask rendering.
    let pdf = raw_one_page(
        "/Resources << /ExtGState << /GS0 5 0 R >> /XObject << /Fm0 6 0 R >> >>",
        b"/GS0 gs /Fm0 Do 0 0 100 100 re f",
        vec![
            (
                5,
                b"<< /Type /ExtGState /SMask << /S /Luminosity /G 6 0 R >> >>".to_vec(),
            ),
            (
                6,
                raw_stream(
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] \
                     /Group << /Type /Group /S /Transparency /CS /DeviceGray >> \
                     /Resources << /ExtGState << /GS0 5 0 R >> /XObject << /Fm0 6 0 R >> >> \
                     /Length 30 >>",
                    b"/GS0 gs /Fm0 Do 0 0 9 9 re f  ",
                ),
            ),
        ],
    );
    assert_construct_survives("soft-mask group that re-enters itself", pdf);

    // /SMask /G pointing at the PAGE object rather than a form.
    let pdf = raw_one_page(
        "/Resources << /ExtGState << /GS0 5 0 R >> >>",
        b"/GS0 gs 0 0 400 400 re f",
        vec![(
            5,
            b"<< /Type /ExtGState /SMask << /S /Luminosity /G 3 0 R >> >>".to_vec(),
        )],
    );
    assert_construct_survives("soft-mask /G pointing at the page object", pdf);
}

/// HAZARD: an ASCII85 5-tuple whose base-85 value exceeds u32::MAX overflows the
/// accumulator. In debug that is a panic; in release it wraps silently.
#[test]
fn ascii85_tuple_overflowing_u32_is_rejected() {
    // 'u' = 84, the largest digit; "uuuuu" = 84*(85^4+85^3+85^2+85+1) >> u32::MAX.
    let bodies: [&[u8]; 6] = [
        b"uuuuu~>",
        b"uuuuuuuuuu~>",
        b"s8W-\"~>",  // one past the largest legal tuple "s8W-!"
        b"zzzz~>",    // z = all-zero group, must not be mixed into a tuple
        b"!!!!!uuuuu~>",
        b"uuuu~>",    // short final tuple at the maximum digit
    ];
    for (i, body) in bodies.iter().enumerate() {
        let pdf = raw_pdf(
            &[
                (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
                (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
                (
                    3,
                    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>"
                        .to_vec(),
                ),
                (
                    4,
                    raw_stream(
                        &format!(
                            "<< /Length {} /Filter /ASCII85Decode >>",
                            body.len()
                        ),
                        body,
                    ),
                ),
            ],
            "/Root 1 0 R",
        );
        assert_construct_survives(&format!("ASCII85 overflowing 5-tuple #{i}"), pdf);
    }
}

/// HAZARD: /Columns, /Colors and /BitsPerComponent on a PNG/TIFF predictor feed
/// a row-length multiplication that must be bounded by the actual data, not by
/// the declared values.
///
/// STATUS: CLOSED. `png-1GiB-row` below used to hang and allocate ~3 GiB from a
/// 512-byte stream. `apply_png_predictor` (and `apply_tiff_predictor2`) now bound
/// the row length by the decoded stream, which is the only honest bound available.
#[test]
fn predictor_columns_and_colors_cannot_overflow_a_row_size() {
    let payload = flate(&[0u8; 512]);
    let cases: [(&str, i64, i64, i64, i64); 8] = [
        ("png-huge-columns", 15, 0xFFFF_FFF, 3, 8),
        ("png-huge-colors", 15, 64, 0xFFFF_FFF, 8),
        ("png-huge-both", 15, 0x7FFF_FFFF, 0x7FFF_FFFF, 16),
        // The minimal reproduction: exactly the clamp ceilings in filters.rs.
        // filters.rs clamped the FACTORS (cols to 1<<24, colors to 32) but never
        // bounded the PRODUCT, so
        //   row_bytes = (1<<24 * 32 * 16).div_ceil(8) = 1 GiB
        // and apply_png_predictor then did:
        //   vec![0u8; 1 GiB]  x2      -> 2 GiB allocated
        //   row[got..].fill(0)        -> ~1 GiB memset per row
        //   out.extend_from_slice     -> a third 1 GiB, reallocated from cap 512
        // All from two dictionary numbers and 512 bytes of stream. Fixed by
        // clamping row_bytes to the decoded length (7.4.4.4).
        ("png-1GiB-row", 15, 1 << 24, 32, 16),
        ("png-negative", 15, -1, -1, 8),
        ("tiff-huge", 2, 0x7FFF_FFFF, 0x7FFF_FFFF, 16),
        ("png-zero", 15, 0, 0, 0),
        ("png-just-under", 15, 1 << 20, 32, 16),
    ];
    for (name, pred, columns, colors, bpc) in cases {
        let pdf = raw_pdf(
            &[
                (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
                (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
                (
                    3,
                    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>"
                        .to_vec(),
                ),
                (
                    4,
                    raw_stream(
                        &format!(
                            "<< /Length {} /Filter /FlateDecode /DecodeParms \
                             << /Predictor {pred} /Columns {columns} /Colors {colors} \
                             /BitsPerComponent {bpc} >> >>",
                            payload.len()
                        ),
                        &payload,
                    ),
                ),
            ],
            "/Root 1 0 R",
        );
        assert_construct_survives(&format!("PNG/TIFF predictor {name}"), pdf);
    }
}

/// HAZARD: an inline image whose declared /L exceeds the bytes actually present
/// must not slice past the end of the content stream.
#[test]
fn inline_image_declared_length_beyond_the_stream_is_clamped() {
    let cases: [&[u8]; 5] = [
        b"BI /W 4 /H 4 /BPC 8 /CS /RGB /L 999999999 ID \x00\x01\x02 EI",
        b"BI /W 4 /H 4 /BPC 8 /CS /RGB /L 2147483647 ID \x00 EI",
        // No EI at all.
        b"BI /W 4 /H 4 /BPC 8 /CS /RGB ID \x00\x01\x02\x03",
        // Dimensions that claim far more data than is present.
        b"BI /W 65535 /H 65535 /BPC 8 /CS /RGB ID \x00\x01 EI",
        // Negative length.
        b"BI /W 4 /H 4 /BPC 8 /CS /G /L -1 ID \x00\x01\x02 EI",
    ];
    for (i, content) in cases.iter().enumerate() {
        let pdf = raw_one_page("", content, vec![]);
        assert_construct_survives(&format!("inline image with lying /L #{i}"), pdf);
    }
}

/// HAZARD: a cyclic /Parent chain and a /Kids cycle in the page tree are
/// infinite walks unless every traversal is depth-bounded.
#[test]
fn cyclic_page_tree_parent_and_kids_chains_terminate() {
    // /Parent cycle: the page's parent is the Pages node, whose own /Parent
    // points back at the page.
    let pdf = raw_pdf(
        &[
            (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
            (
                2,
                b"<< /Type /Pages /Kids [3 0 R] /Count 1 /Parent 3 0 R >>".to_vec(),
            ),
            (
                3,
                b"<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>".to_vec(),
            ),
            (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
        ],
        "/Root 1 0 R",
    );
    assert_construct_survives("page tree with a cyclic /Parent", pdf);

    // /Kids cycle: the Pages node lists itself.
    let pdf = raw_pdf(
        &[
            (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
            (
                2,
                b"<< /Type /Pages /Kids [2 0 R 3 0 R] /Count 2 >>".to_vec(),
            ),
            (
                3,
                b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10] /Contents 4 0 R >>".to_vec(),
            ),
            (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
        ],
        "/Root 1 0 R",
    );
    assert_construct_survives("page tree whose /Kids lists its own node", pdf);

    // Two Pages nodes that list each other.
    let pdf = raw_pdf(
        &[
            (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
            (2, b"<< /Type /Pages /Kids [5 0 R] /Count 1 >>".to_vec()),
            (
                3,
                b"<< /Type /Page /Parent 5 0 R /MediaBox [0 0 10 10] /Contents 4 0 R >>".to_vec(),
            ),
            (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
            (
                5,
                b"<< /Type /Pages /Kids [2 0 R 3 0 R] /Count 2 /Parent 2 0 R >>".to_vec(),
            ),
        ],
        "/Root 1 0 R",
    );
    assert_construct_survives("mutually recursive /Pages nodes", pdf);

    // The catalog's /Pages points at the catalog.
    let pdf = raw_pdf(
        &[
            (1, b"<< /Type /Catalog /Pages 1 0 R >>".to_vec()),
            (2, b"<< /Type /Pages /Kids [] /Count 0 >>".to_vec()),
        ],
        "/Root 1 0 R",
    );
    assert_construct_survives("catalog whose /Pages is the catalog", pdf);
}

/// HAZARD: deeply nested arrays and dictionaries must hit a depth bound rather
/// than recursing to a stack overflow.
///
/// STATUS: CLOSED. Both routes are now bounded before lopdf recurses, and the two
/// `deep_nesting_*` reproductions below run live at depth 500 rather than being
/// `#[ignore]`d:
///   * content streams - `content::strict_operations` pre-checks nesting and skips
///     `Content::decode` above `MAX_DEPTH`, leaving the depth-bounded lenient
///     tokenizer to handle the stream;
///   * the object graph - `registry::load_document_lenient` pre-scans the raw bytes
///     and refuses the document above `MAX_RAW_NESTING`, with the parse itself run
///     on a 32 MiB worker stack as defence in depth.
/// The depths exercised here span 8..100_000, crossing both bounds. They were
/// pinned to 8..128 while the crash was live, because a guard-page fault aborts
/// the whole test binary; now that both routes are bounded, the full range is
/// safe to assert and is much stronger evidence. Verified with
/// `bisect_nesting_depth` at a 1 MiB stack (the Android-realistic condition, and
/// the one the original thresholds made fatal): `load` and `page` both Completed
/// at depths 48 / 500 / 5000 / 100_000.
///
/// Thresholds measured BEFORE the fix, debug build, for the record:
///   nested `[` via strict content parse:  8 MiB survives 320, dies at 360;
///                                         1 MiB survives  32, dies at  48
///   nested `<< >>` via Document::load_mem: 8 MiB survives 256, dies at 280;
///                                          1 MiB survives  16, dies at  32
#[test]
fn deeply_nested_arrays_and_dictionaries_hit_a_depth_bound() {
    for depth in [8usize, 32, 64, 128, 500, 5_000, 100_000] {
        // Nesting in a content stream operand.
        let mut content = Vec::new();
        content.extend_from_slice(b"BT /F1 12 Tf ");
        for _ in 0..depth {
            content.push(b'[');
        }
        content.extend_from_slice(b"(x)");
        for _ in 0..depth {
            content.push(b']');
        }
        content.extend_from_slice(b" TJ ET");
        let pdf = raw_one_page("", &content, vec![]);
        assert_construct_survives(&format!("content stream with {depth} nested arrays"), pdf);

        // Nesting in a dictionary in the object graph.
        let mut dict = Vec::new();
        for _ in 0..depth {
            dict.extend_from_slice(b"<< /K ");
        }
        dict.extend_from_slice(b"0");
        for _ in 0..depth {
            dict.extend_from_slice(b" >>");
        }
        let pdf = raw_one_page("/DeepNest 5 0 R", b"0 0 10 10 re f", vec![(5, dict)]);
        assert_construct_survives(&format!("object graph with {depth} nested dictionaries"), pdf);

        // Unbalanced: openers only, so the parser never sees a terminator.
        let mut content = vec![b'['; depth];
        content.extend_from_slice(b" 1 2 3 ");
        let pdf = raw_one_page("", &content, vec![]);
        assert_construct_survives(&format!("content stream with {depth} unclosed arrays"), pdf);

        let mut content = Vec::new();
        for _ in 0..depth {
            content.extend_from_slice(b"<< /A ");
        }
        let pdf = raw_one_page("", &content, vec![]);
        assert_construct_survives(&format!("content stream with {depth} unclosed dictionaries"), pdf);
    }
}

/// REGRESSION TEST (was a crash reproduction) — content-stream nesting.
///
/// Originally: `content::page_operations` handed the content stream to lopdf's
/// `Content::decode` BEFORE falling back to `parse_operations_lenient`. The
/// lenient tokenizer has `MAX_DEPTH = 32` and survives 500 levels; the strict
/// parser had no bound, so the guard never ran and the process died with
/// STATUS_STACK_OVERFLOW at ~48 levels on a 1 MiB stack.
///
/// FIXED: `content::strict_operations` now runs `nesting_is_too_deep` over the raw
/// content bytes first and skips `Content::decode` entirely when nesting exceeds
/// `MAX_DEPTH`, so the depth-bounded lenient tokenizer handles the stream instead.
/// The pre-check respects literal-string, hex-string and comment context, so a `[`
/// or `>` inside `(...)` cannot move the count.
#[test]
fn deep_content_stream_nesting_degrades_instead_of_overflowing_the_stack() {
    let depth = 500;
    let mut content = Vec::from(&b"BT /F1 12 Tf "[..]);
    for _ in 0..depth {
        content.push(b'[');
    }
    content.extend_from_slice(b"(x)");
    for _ in 0..depth {
        content.push(b']');
    }
    content.extend_from_slice(b" TJ ET");
    let pdf = raw_one_page("", &content, vec![]);
    assert_construct_survives(&format!("content stream with {depth} nested arrays"), pdf);
}

/// REGRESSION TEST (was a crash reproduction) — object-graph nesting, at OPEN.
///
/// Originally: `load_document_lenient` -> `lopdf::Document::load_mem` recursed per
/// nesting level with no bound. ~280 levels overflowed an 8 MiB stack and ~32 an
/// ordinary 1 MiB worker-thread stack, killing the process. This was both the
/// "doesntopenexample" and the "crashexample" class at once.
///
/// FIXED: `load_document_lenient` pre-scans the raw bytes with `nesting_exceeds`
/// and refuses the document above `MAX_RAW_NESTING` (200) levels before lopdf's
/// recursive object parser is entered, so the process kill becomes a clean "cannot
/// open" per 7.5.1. The parse itself additionally runs on a worker thread with a
/// 32 MiB stack, for nesting the pre-scan cannot see because it only exists after
/// an object stream is decompressed.
///
/// A clean refusal is the CORRECT outcome here, so this asserts "no panic, no
/// hang", not "loads successfully".
#[test]
fn deep_object_graph_nesting_is_refused_cleanly_at_open() {
    let depth = 500;
    let mut dict = Vec::new();
    for _ in 0..depth {
        dict.extend_from_slice(b"<< /K ");
    }
    dict.extend_from_slice(b"0");
    for _ in 0..depth {
        dict.extend_from_slice(b" >>");
    }
    let pdf = raw_one_page("/DeepNest 5 0 R", b"0 0 10 10 re f", vec![(5, dict)]);
    let v = guarded("deep object graph", construct_budget(), move || {
        let loaded = load_document_lenient(&pdf).is_some();
        eprintln!("loaded={loaded}");
    });
    assert!(!v.is_failure(), "{v:?}");
}

/// HAZARD: /Width * /Height * components overflows a usize/u32 product. Release
/// builds have no overflow checks, so this only panics in debug — which is
/// exactly the build these tests run in.
#[test]
fn image_dimension_products_that_overflow_are_refused() {
    let cases: [(&str, &str, &str, &str, &str); 8] = [
        ("u32-square", "65536", "65536", "8", "/DeviceRGB"),
        ("i64-max", "9223372036854775807", "9223372036854775807", "8", "/DeviceRGB"),
        ("u32-max", "4294967295", "4294967295", "16", "/DeviceCMYK"),
        ("negative", "-1", "-1", "8", "/DeviceGray"),
        ("zero", "0", "0", "8", "/DeviceRGB"),
        ("bpc-absurd", "16", "16", "4294967295", "/DeviceRGB"),
        ("bpc-zero", "16", "16", "0", "/DeviceRGB"),
        ("tall-thin", "1", "2147483647", "8", "/DeviceRGB"),
    ];
    for (name, w, h, bpc, cs) in cases {
        let pdf = raw_one_page(
            "/Resources << /XObject << /Im0 5 0 R >> >>",
            b"q 600 0 0 700 0 0 cm /Im0 Do Q",
            vec![(
                5,
                raw_stream(
                    &format!(
                        "<< /Type /XObject /Subtype /Image /Width {w} /Height {h} \
                         /BitsPerComponent {bpc} /ColorSpace {cs} /Length 16 >>"
                    ),
                    &[0x7Fu8; 16],
                ),
            )],
        );
        assert_construct_survives(&format!("image dimensions {name}: {w}x{h}x{bpc} {cs}"), pdf);
    }
}

/// HAZARD: an /Indexed colour space with an absurd /HiVal, and Separation /
/// DeviceN with an absurd component count, both size lookup tables from
/// attacker-controlled numbers.
#[test]
fn absurd_colorspace_declarations_do_not_size_a_lookup_table() {
    let cases: [(&str, String); 5] = [
        (
            "indexed-hival-u32max",
            "[/Indexed /DeviceRGB 4294967295 <00FF00>]".to_string(),
        ),
        (
            "indexed-hival-negative",
            "[/Indexed /DeviceRGB -1 <00FF00>]".to_string(),
        ),
        (
            "indexed-base-is-itself",
            "[/Indexed 5 0 R 255 <00FF00>]".to_string(),
        ),
        (
            "devicen-many-components",
            format!("[/DeviceN [{}] /DeviceRGB 6 0 R]", "/C ".repeat(4096)),
        ),
        (
            "lab-absurd-range",
            "[/Lab << /WhitePoint [1 1 1] /Range [-2147483648 2147483647 -2147483648 2147483647] >>]"
                .to_string(),
        ),
    ];
    for (name, cs) in cases {
        let pdf = raw_one_page(
            "/Resources << /ColorSpace << /CS0 5 0 R >> >>",
            b"/CS0 cs 0 0 0 sc 0 0 400 400 re f",
            vec![
                (5, cs.into_bytes()),
                (
                    6,
                    b"<< /FunctionType 2 /Domain [0 1] /C0 [0 0 0] /C1 [1 1 1] /N 1 >>".to_vec(),
                ),
            ],
        );
        assert_construct_survives(&format!("colour space {name}"), pdf);
    }
}

/// HAZARD: a mesh shading declaring an absurd patch/vertex count, or bit-packed
/// coordinate widths that do not divide, must be bounded by the actual stream
/// length rather than the declaration.
#[test]
fn mesh_shading_absurd_counts_are_bounded_by_the_stream() {
    for shading_type in [4i64, 5, 6, 7] {
        let dict = format!(
            "<< /ShadingType {shading_type} /ColorSpace /DeviceRGB \
             /BitsPerCoordinate 32 /BitsPerComponent 16 /BitsPerFlag 8 \
             /VerticesPerRow 2147483647 \
             /Decode [0 1 0 1 0 1 0 1 0 1] /Length 4 >>"
        );
        let pdf = raw_one_page(
            "/Resources << /Shading << /Sh0 5 0 R >> >>",
            b"q 0 0 600 700 re W n /Sh0 sh Q",
            vec![(5, raw_stream(&dict, &[0xFFu8; 4]))],
        );
        assert_construct_survives(
            &format!("mesh shading type {shading_type} with absurd /VerticesPerRow"),
            pdf,
        );
    }
}

/// HAZARD: a PostScript calculator function (Type 4) can loop or recurse without
/// bound unless token, step and depth budgets hold.
#[test]
fn postscript_calculator_functions_are_step_bounded() {
    let programs: [&[u8]; 4] = [
        // Deeply nested procedures.
        b"{ 0 { { { { { { { { { { pop 1 } if } if } if } if } if } if } if } if } if } if }",
        // Very long token stream.
        b"{ 0 dup dup dup dup dup dup dup dup pop pop pop pop pop pop pop pop }",
        // Unbalanced braces.
        b"{ { { { { { { 1 ",
        // Stack growth.
        b"{ 1 dup dup dup dup dup dup dup dup dup dup dup dup dup dup dup dup }",
    ];
    for (i, prog) in programs.iter().enumerate() {
        let pdf = raw_one_page(
            "/Resources << /Shading << /Sh0 5 0 R >> >>",
            b"q 0 0 600 700 re W n /Sh0 sh Q",
            vec![
                (
                    5,
                    b"<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 600 700] \
                      /Function 6 0 R /Extend [true true] >>"
                        .to_vec(),
                ),
                (
                    6,
                    raw_stream(
                        &format!(
                            "<< /FunctionType 4 /Domain [0 1] /Range [0 1 0 1 0 1] /Length {} >>",
                            prog.len()
                        ),
                        prog,
                    ),
                ),
            ],
        );
        assert_construct_survives(&format!("Type 4 PostScript function #{i}"), pdf);
    }

    // A very large generated program, to hit the token budget rather than the
    // depth budget.
    let mut big = Vec::from(&b"{ 0 "[..]);
    for _ in 0..200_000 {
        big.extend_from_slice(b"dup pop ");
    }
    big.extend_from_slice(b"0 0 }");
    let pdf = raw_one_page(
        "/Resources << /Shading << /Sh0 5 0 R >> >>",
        b"q 0 0 600 700 re W n /Sh0 sh Q",
        vec![
            (
                5,
                b"<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 600 700] \
                  /Function 6 0 R /Extend [true true] >>"
                    .to_vec(),
            ),
            (
                6,
                raw_stream(
                    &format!(
                        "<< /FunctionType 4 /Domain [0 1] /Range [0 1 0 1 0 1] /Length {} >>",
                        big.len()
                    ),
                    &big,
                ),
            ),
        ],
    );
    assert_construct_survives("Type 4 function with 400k tokens", pdf);
}

/// HAZARD: a Type 3 font whose glyph procedure paints text in the same font is
/// glyph-level recursion, distinct from `Do` recursion.
#[test]
fn self_referential_type3_glyph_terminates() {
    let pdf = raw_one_page(
        "/Resources << /Font << /T3 5 0 R >> >>",
        b"BT /T3 24 Tf 50 700 Td (AAA) Tj ET",
        vec![
            (
                5,
                b"<< /Type /Font /Subtype /Type3 /FontBBox [0 0 1000 1000] \
                  /FontMatrix [0.001 0 0 0.001 0 0] /CharProcs 6 0 R \
                  /Encoding << /Differences [65 /square] >> \
                  /FirstChar 65 /LastChar 65 /Widths [1000] \
                  /Resources << /Font << /T3 5 0 R >> >> >>"
                    .to_vec(),
            ),
            (6, b"<< /square 7 0 R >>".to_vec()),
            (
                7,
                raw_stream(
                    "<< /Length 46 >>",
                    b"1000 0 d0 BT /T3 24 Tf 0 0 Td (A) Tj ET   ",
                ),
            ),
        ],
    );
    assert_construct_survives("Type 3 glyph that shows text in its own font", pdf);
}

/// HAZARD: an /Annots array that is enormous, self-referential, or whose
/// appearance stream is the page, must be bounded by MAX_ANNOTATIONS.
#[test]
fn hostile_annotation_arrays_are_bounded() {
    // 50_000 references to one annotation, against a 10_000 cap.
    let refs: String = (0..50_000).map(|_| "5 0 R ").collect();
    let pdf = raw_one_page(
        &format!("/Annots [{refs}]"),
        b"0 0 10 10 re f",
        vec![(
            5,
            b"<< /Type /Annot /Subtype /Square /Rect [0 0 50 50] /F 4 /AP << /N 6 0 R >> >>"
                .to_vec(),
        ),
        (
            6,
            raw_stream(
                "<< /Type /XObject /Subtype /Form /BBox [0 0 50 50] /Length 16 >>",
                b"0 0 50 50 re f  ",
            ),
        )],
    );
    assert_construct_survives("50k-entry /Annots array against a 10k cap", pdf);

    // Annotation whose appearance stream is the page's own content stream.
    let pdf = raw_one_page(
        "/Annots [5 0 R]",
        b"0 0 10 10 re f",
        vec![(
            5,
            b"<< /Type /Annot /Subtype /Widget /Rect [0 0 600 700] /F 4 \
              /AP << /N 4 0 R >> >>"
                .to_vec(),
        )],
    );
    assert_construct_survives("annotation appearance stream is the page content", pdf);

    // Non-finite and inverted /Rect values.
    for rect in ["[nan nan nan nan]", "[1e400 1e400 -1e400 -1e400]", "[600 700 0 0]", "[]"] {
        let pdf = raw_one_page(
            "/Annots [5 0 R]",
            b"0 0 10 10 re f",
            vec![(
                5,
                format!(
                    "<< /Type /Annot /Subtype /Square /Rect {rect} /F 4 \
                     /AP << /N 6 0 R >> >>"
                )
                .into_bytes(),
            ),
            (
                6,
                raw_stream(
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 50 50] /Length 16 >>",
                    b"0 0 50 50 re f  ",
                ),
            )],
        );
        assert_construct_survives(&format!("annotation /Rect {rect}"), pdf);
    }
}

/// HAZARD: non-finite and extreme numbers in the graphics state (matrices, line
/// widths, colours, text params) propagate into the wire buffer, where the
/// Kotlin side feeds them straight to a Canvas transform.
#[test]
fn non_finite_and_extreme_numbers_do_not_reach_the_wire_buffer() {
    let contents: [&[u8]; 9] = [
        b"1e400 0 0 1e400 0 0 cm 0 0 10 10 re f",
        b"0 0 0 0 0 0 cm 0 0 10 10 re f",
        b"-1e400 -1e400 -1e400 -1e400 -1e400 -1e400 cm 0 0 10 10 re f",
        b"1e400 w 0 0 m 10 10 l S",
        b"1e400 1e400 1e400 rg 0 0 10 10 re f",
        b"BT 1e400 Tf 1e400 1e400 Td (x) Tj ET",
        b"BT /F1 1e400 Tf 0 Tc 1e400 Tz 1e400 TL (x) Tj ET",
        b"0 0 m 1e400 1e400 1e400 1e400 1e400 1e400 c S",
        b"[1e400 1e400] 1e400 d 0 0 m 10 10 l S",
    ];
    for (i, content) in contents.iter().enumerate() {
        let pdf = raw_one_page("", content, vec![]);
        assert_construct_survives(&format!("non-finite graphics-state numbers #{i}"), pdf);
    }

    // A MediaBox that is itself non-finite or inverted: page size flows straight
    // into the wire header.
    for mb in [
        "[0 0 1e400 1e400]",
        "[nan nan nan nan]",
        "[0 0 0 0]",
        "[1e400 1e400 -1e400 -1e400]",
        "[]",
        "[0 0 612]",
    ] {
        let pdf = raw_pdf(
            &[
                (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
                (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
                (
                    3,
                    format!(
                        "<< /Type /Page /Parent 2 0 R /MediaBox {mb} /Contents 4 0 R >>"
                    )
                    .into_bytes(),
                ),
                (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
            ],
            "/Root 1 0 R",
        );
        assert_construct_survives(&format!("MediaBox {mb}"), pdf);
    }
}

/// HAZARD: unbalanced `q`/`Q`, `BT`/`ET`, `W n` and marked-content operators.
/// The interpreter drains its clip/group stacks at end-of-stream, and an
/// unbalanced drain is how unmatched Push/Pop primitives reach the wire buffer.
#[test]
fn unbalanced_state_operators_leave_a_balanced_primitive_stream() {
    let mut deep_q = Vec::new();
    for _ in 0..5000 {
        deep_q.extend_from_slice(b"q 1 0 0 1 1 1 cm ");
    }
    deep_q.extend_from_slice(b"0 0 10 10 re f");

    let mut deep_qq = Vec::new();
    for _ in 0..5000 {
        deep_qq.extend_from_slice(b"Q ");
    }

    let mut deep_clip = Vec::new();
    for _ in 0..5000 {
        deep_clip.extend_from_slice(b"q 0 0 100 100 re W n ");
    }

    let mut deep_bdc = Vec::new();
    for _ in 0..5000 {
        deep_bdc.extend_from_slice(b"/OC /MC0 BDC ");
    }

    let cases: Vec<(&str, Vec<u8>)> = vec![
        ("5000 unmatched q", deep_q),
        ("5000 unmatched Q", deep_qq),
        ("5000 nested clips", deep_clip),
        ("5000 unmatched BDC", deep_bdc),
        ("unmatched BT", b"BT BT BT (x) Tj".to_vec()),
        ("unmatched ET", b"ET ET ET (x) Tj".to_vec()),
        ("W with no path", b"W n W n W n".to_vec()),
        ("EMC with no BDC", b"EMC EMC EMC 0 0 10 10 re f".to_vec()),
        ("Q before q", b"Q Q q 0 0 10 10 re f".to_vec()),
    ];
    for (name, content) in cases {
        let pdf = raw_one_page("/Resources << /Font << /F1 5 0 R >> >>", &content, vec![(
            5,
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_vec(),
        )]);
        assert_construct_survives(&format!("unbalanced operators: {name}"), pdf);
    }
}

/// HAZARD: an operator flood must be bounded by MAX_CONTENT_OPS /
/// MAX_PRIMITIVES rather than by memory.
///
/// This asserts the primitive cap DIRECTLY rather than via the wall clock,
/// because the wall clock here measures the debug build, not the renderer's
/// boundedness. Measured cost is ~110 microseconds per operator in debug
/// (1.5M operators complete in 110.9s), so a flood large enough to make
/// `MAX_PRIMITIVES` = 300_000 bind necessarily takes tens of seconds in debug.
/// An earlier version of this test failed a 10s budget for exactly that reason;
/// the work is bounded, so that was a mis-sized test and not a renderer defect.
/// Hence `flood_budget()`.
#[test]
fn operator_and_primitive_floods_are_capped() {
    // 350k path-painting operators, just above MAX_PRIMITIVES = 300_000, so the
    // primitive cap is what binds.
    let mut flood = Vec::with_capacity(16 * 350_000);
    for i in 0..350_000u32 {
        flood.extend_from_slice(format!("{} 0 1 1 re f ", i % 600).as_bytes());
    }
    let pdf = raw_one_page("", &flood, vec![]);
    let v = guarded("350k fill operators", flood_budget(), move || {
        let doc = load_document_lenient(&pdf).expect("flood fixture must load");
        let page_id = *doc.get_pages().values().next().unwrap();
        let page = interpret_page(&doc, page_id).expect("flood must interpret");
        eprintln!(
            "[robustness] 350k fill operators -> {} primitives (cap {})",
            page.prims.len(),
            MAX_PRIMITIVES
        );
        assert!(
            page.prims.len() <= MAX_PRIMITIVES,
            "the primitive cap did not bind: {} primitives emitted, cap is {}",
            page.prims.len(),
            MAX_PRIMITIVES
        );
        let _ = crate::wire::serialize(&page);
    });
    assert!(
        !v.is_failure(),
        "ROBUSTNESS FAILURE — 350k-operator flood: {v:?}"
    );

    // A single path with a huge number of subpaths.
    let mut subpaths = Vec::new();
    for i in 0..200_000u32 {
        subpaths.extend_from_slice(format!("{} 0 m {} 10 l ", i % 600, i % 600).as_bytes());
    }
    subpaths.extend_from_slice(b"S");
    let pdf = raw_one_page("", &subpaths, vec![]);
    let v = guarded("200k subpaths", flood_budget(), move || exercise(&pdf));
    assert!(!v.is_failure(), "ROBUSTNESS FAILURE — 200k subpaths in one path: {v:?}");

    // A large text run through a substitute font.
    let mut text = Vec::from(&b"BT /F1 1 Tf "[..]);
    for _ in 0..20_000 {
        text.extend_from_slice(b"(abcdefghijklmnopqrstuvwxyz) Tj 0 -1 Td ");
    }
    text.extend_from_slice(b"ET");
    let pdf = raw_one_page(
        "/Resources << /Font << /F1 5 0 R >> >>",
        &text,
        vec![(
            5,
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_vec(),
        )],
    );
    let v = guarded("520k glyphs", flood_budget(), move || exercise(&pdf));
    assert!(!v.is_failure(), "ROBUSTNESS FAILURE — 520k glyphs of text: {v:?}");
}

/// HAZARD: a tiling pattern with a near-zero /XStep over a large fill area is a
/// tile-count explosion — a pure slowdown with no panic, i.e. the
/// "slowdownexample" shape.
#[test]
fn degenerate_pattern_steps_do_not_explode_the_tile_count() {
    for (name, xstep, ystep, bbox) in [
        ("tiny-steps", "0.0001", "0.0001", "[0 0 0.0001 0.0001]"),
        ("zero-steps", "0", "0", "[0 0 1 1]"),
        ("negative-steps", "-1", "-1", "[0 0 1 1]"),
        ("nan-steps", "nan", "nan", "[0 0 1 1]"),
        ("huge-bbox", "1", "1", "[0 0 1e400 1e400]"),
    ] {
        let pdf = raw_one_page(
            "/Resources << /Pattern << /P0 5 0 R >> >>",
            b"/Pattern cs /P0 scn 0 0 612 792 re f",
            vec![(
                5,
                raw_stream(
                    &format!(
                        "<< /Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 \
                         /BBox {bbox} /XStep {xstep} /YStep {ystep} \
                         /Resources << >> /Length 16 >>"
                    ),
                    b"0 0 1 1 re f   ",
                ),
            )],
        );
        assert_construct_survives(&format!("tiling pattern {name}"), pdf);
    }
}

/// HAZARD: an /Encrypt dictionary with absurd or missing fields is parsed before
/// anything else, on fully untrusted bytes.
#[test]
fn hostile_encrypt_dictionaries_are_rejected_cleanly() {
    let cases: [&str; 7] = [
        "<< /Filter /Standard /V 2 /R 3 /Length 4294967295 /O <00> /U <00> /P -1 >>",
        "<< /Filter /Standard /V 2147483647 /R 2147483647 /Length 128 /O <00> /U <00> /P -1 >>",
        "<< /Filter /Standard /V 5 /R 6 /Length 256 >>",
        "<< /Filter /Standard >>",
        "<< /Filter /Standard /V 4 /R 4 /CF << /StdCF << /CFM /AESV2 /Length 4294967295 >> >> /StmF /StdCF /StrF /StdCF /O <00> /U <00> /P -1 >>",
        "<< /Filter /Standard /V 1 /R 2 /Length -8 /O () /U () /P 0 >>",
        "<< /Filter /Weird /V 99 >>",
    ];
    for (i, enc) in cases.iter().enumerate() {
        let pdf = raw_pdf(
            &[
                (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
                (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
                (
                    3,
                    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>"
                        .to_vec(),
                ),
                (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
                (5, enc.as_bytes().to_vec()),
            ],
            "/Root 1 0 R /Encrypt 5 0 R /ID [<00> <00>]",
        );
        assert_construct_survives(&format!("hostile /Encrypt dictionary #{i}"), pdf);
    }
}

/// HAZARD: filter chains that are absurdly long, unknown, or self-contradictory.
#[test]
fn hostile_filter_chains_are_rejected_cleanly() {
    let payload = flate(b"0 0 10 10 re f");
    let cases: [(&str, String); 6] = [
        (
            "64-deep-flate-chain",
            format!("[{}]", "/FlateDecode ".repeat(64)),
        ),
        ("unknown-filter", "/NoSuchDecode".to_string()),
        (
            "flate-then-ascii85",
            "[/FlateDecode /ASCII85Decode]".to_string(),
        ),
        ("filter-is-a-number", "42".to_string()),
        ("filter-is-a-dict", "<< /A 1 >>".to_string()),
        (
            "128-mixed-chain",
            format!("[{}]", "/ASCIIHexDecode /RunLengthDecode ".repeat(64)),
        ),
    ];
    for (name, filter) in cases {
        let pdf = raw_pdf(
            &[
                (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
                (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
                (
                    3,
                    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>"
                        .to_vec(),
                ),
                (
                    4,
                    raw_stream(
                        &format!("<< /Length {} /Filter {filter} >>", payload.len()),
                        &payload,
                    ),
                ),
            ],
            "/Root 1 0 R",
        );
        assert_construct_survives(&format!("filter chain {name}"), pdf);
    }
}

/// HAZARD: CCITT, JBIG2 and DCT streams are third-party-ish decoders fed
/// attacker bytes; garbage and lying parameters must not panic.
#[test]
fn image_codec_streams_with_garbage_bodies_are_rejected_cleanly() {
    let garbage: Vec<u8> = (0..512u32).map(|i| (i.wrapping_mul(2654435761) >> 13) as u8).collect();
    let cases: [(&str, String); 6] = [
        (
            "ccitt-k-absurd",
            "/CCITTFaxDecode /DecodeParms << /K -2147483648 /Columns 2147483647 /Rows 2147483647 /BlackIs1 true >>".to_string(),
        ),
        (
            "ccitt-zero-columns",
            "/CCITTFaxDecode /DecodeParms << /K 0 /Columns 0 /Rows 0 >>".to_string(),
        ),
        ("jbig2-garbage", "/JBIG2Decode".to_string()),
        ("dct-garbage", "/DCTDecode".to_string()),
        ("jpx-garbage", "/JPXDecode".to_string()),
        (
            "runlength-truncated",
            "/RunLengthDecode".to_string(),
        ),
    ];
    for (name, filter) in cases {
        let pdf = raw_one_page(
            "/Resources << /XObject << /Im0 5 0 R >> >>",
            b"q 600 0 0 700 0 0 cm /Im0 Do Q",
            vec![(
                5,
                raw_stream(
                    &format!(
                        "<< /Type /XObject /Subtype /Image /Width 256 /Height 256 \
                         /BitsPerComponent 8 /ColorSpace /DeviceGray /Filter {filter} \
                         /Length {} >>",
                        garbage.len()
                    ),
                    &garbage,
                ),
            )],
        );
        assert_construct_survives(&format!("image codec {name}"), pdf);
    }
}

/// HAZARD: an /SMask or /Mask image whose dimensions disagree with the base
/// image drives an index into the wrong buffer.
#[test]
fn mismatched_image_masks_do_not_index_out_of_bounds() {
    let base = flate(&[0x80u8; 16 * 16 * 3]);
    let cases: [(&str, &str); 4] = [
        ("smask-much-larger", "/SMask 6 0 R"),
        ("mask-as-array", "/Mask [0 0 0 0 0 0]"),
        ("mask-image", "/Mask 6 0 R"),
        ("smask-is-self", "/SMask 5 0 R"),
    ];
    for (name, extra) in cases {
        let mask = flate(&[0xFFu8; 4]);
        let pdf = raw_one_page(
            "/Resources << /XObject << /Im0 5 0 R >> >>",
            b"q 600 0 0 700 0 0 cm /Im0 Do Q",
            vec![
                (
                    5,
                    raw_stream(
                        &format!(
                            "<< /Type /XObject /Subtype /Image /Width 16 /Height 16 \
                             /BitsPerComponent 8 /ColorSpace /DeviceRGB /Filter /FlateDecode \
                             {extra} /Length {} >>",
                            base.len()
                        ),
                        &base,
                    ),
                ),
                (
                    6,
                    raw_stream(
                        &format!(
                            "<< /Type /XObject /Subtype /Image /Width 4096 /Height 4096 \
                             /BitsPerComponent 8 /ColorSpace /DeviceGray /Filter /FlateDecode \
                             /Length {} >>",
                            mask.len()
                        ),
                        &mask,
                    ),
                ),
            ],
        );
        assert_construct_survives(&format!("image mask {name}"), pdf);
    }
}

/// HAZARD: an AcroForm whose field tree is cyclic, or whose /DA is hostile, is
/// walked by the form-field enumeration path.
#[test]
fn cyclic_acroform_field_trees_terminate() {
    let pdf = raw_pdf(
        &[
            (
                1,
                b"<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [5 0 R] /NeedAppearances true >> >>"
                    .to_vec(),
            ),
            (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
            (
                3,
                b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R \
                  /Annots [5 0 R 6 0 R] >>"
                    .to_vec(),
            ),
            (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
            (
                5,
                b"<< /Type /Annot /Subtype /Widget /FT /Tx /T (a) /Rect [0 0 100 20] /F 4 \
                  /Parent 6 0 R /Kids [6 0 R] /DA (/NoSuchFont 1e400 Tf 1e400 1e400 1e400 rg) >>"
                    .to_vec(),
            ),
            (
                6,
                b"<< /Type /Annot /Subtype /Widget /FT /Tx /T (b) /Rect [0 0 100 20] /F 4 \
                  /Parent 5 0 R /Kids [5 0 R] /DA () >>"
                    .to_vec(),
            ),
        ],
        "/Root 1 0 R",
    );
    assert_construct_survives("cyclic AcroForm field tree with hostile /DA", pdf);
}

/// HAZARD: an outline (bookmark) tree with cyclic /Next, /First and /Parent
/// links is walked by `list_outline`, and a /Dest name tree can also cycle.
#[test]
fn cyclic_outline_and_name_trees_terminate() {
    let pdf = raw_pdf(
        &[
            (
                1,
                b"<< /Type /Catalog /Pages 2 0 R /Outlines 5 0 R \
                  /Names << /Dests 7 0 R >> >>"
                    .to_vec(),
            ),
            (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
            (
                3,
                b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>".to_vec(),
            ),
            (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
            (
                5,
                b"<< /Type /Outlines /First 6 0 R /Last 6 0 R /Count 2147483647 >>".to_vec(),
            ),
            (
                6,
                b"<< /Title (loop) /Parent 5 0 R /First 6 0 R /Last 6 0 R \
                  /Next 6 0 R /Prev 6 0 R /Dest (self) /Count -2147483648 >>"
                    .to_vec(),
            ),
            (
                7,
                b"<< /Kids [7 0 R] /Names [(self) 7 0 R] >>".to_vec(),
            ),
        ],
        "/Root 1 0 R",
    );
    // list_outline / named destinations are handle-based, so this one goes
    // through the registry deliberately.
    let v = guarded("cyclic outline tree", construct_budget(), move || {
        let handle = open_document(&pdf);
        if handle == 0 {
            return;
        }
        let _ = list_outline(handle);
        let _ = list_links(handle, 0);
        let _ = list_form_fields(handle, 0);
        let _ = render_page(handle, 0);
        close_document(handle);
    });
    assert!(
        !v.is_failure(),
        "ROBUSTNESS FAILURE — hazard: cyclic outline / name tree: {v:?}"
    );
}

/// HAZARD: an object stream (/ObjStm) with a lying /N and /First, and an xref
/// stream with a hostile /W, are both parsed before any page exists.
#[test]
fn hostile_object_and_xref_streams_are_rejected_cleanly() {
    let body = b"1 0 2 20 << /Type /Catalog >> << /Type /Pages >>";
    let cases: [(&str, String); 5] = [
        ("N-absurd", "/N 4294967295 /First 0".to_string()),
        ("First-past-end", "/N 2 /First 4294967295".to_string()),
        ("First-negative", "/N 2 /First -1".to_string()),
        ("N-zero-First-huge", "/N 0 /First 2147483647".to_string()),
        ("N-negative", "/N -2147483648 /First 0".to_string()),
    ];
    for (name, params) in cases {
        let pdf = raw_pdf(
            &[
                (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
                (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
                (
                    3,
                    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>"
                        .to_vec(),
                ),
                (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
                (
                    5,
                    raw_stream(
                        &format!("<< /Type /ObjStm {params} /Length {} >>", body.len()),
                        body,
                    ),
                ),
            ],
            "/Root 1 0 R",
        );
        assert_construct_survives(&format!("/ObjStm {name}"), pdf);
    }

    // xref stream with a hostile /W and /Index. `index` is varied too, because
    // a zero total entry width means the reader consumes no input per entry, so
    // whether the loop is bounded at all depends on /Index rather than the data.
    for (w, index) in [
        ("[1 2 1]", "[0 2147483647]"),
        ("[4294967295 4294967295 4294967295]", "[0 2147483647]"),
        ("[0 0 0]", "[0 16]"),
        ("[0 0 0]", "[0 2147483647]"),
        ("[-1 -1 -1]", "[0 2147483647]"),
        ("[]", "[0 2147483647]"),
        ("[1 2 1]", "[0 4294967295]"),
    ] {
        let mut pdf = Vec::from(&b"%PDF-1.7\n"[..]);
        pdf.extend_from_slice(b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        pdf.extend_from_slice(b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        pdf.extend_from_slice(
            b"3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n",
        );
        let xref_at = pdf.len();
        pdf.extend_from_slice(
            format!(
                "4 0 obj\n<< /Type /XRef /Size 5 /Root 1 0 R /W {w} \
                 /Index {index} /Length 8 >>\nstream\n"
            )
            .as_bytes(),
        );
        pdf.extend_from_slice(&[0xFFu8; 8]);
        pdf.extend_from_slice(b"\nendstream\nendobj\n");
        pdf.extend_from_slice(format!("startxref\n{xref_at}\n%%EOF\n").as_bytes());
        assert_construct_survives(&format!("xref stream with /W {w} /Index {index}"), pdf);
    }
}

/// HAZARD: /Rotate and /UserUnit feed the page base matrix, which every
/// primitive is transformed by.
#[test]
fn hostile_rotate_and_userunit_produce_a_finite_page() {
    for (rotate, uu) in [
        ("2147483647", "1"),
        ("-2147483648", "1"),
        ("45", "1"),
        ("90", "1e400"),
        ("0", "0"),
        ("0", "-1"),
        ("0", "nan"),
    ] {
        let pdf = raw_pdf(
            &[
                (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
                (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
                (
                    3,
                    format!(
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] \
                         /Rotate {rotate} /UserUnit {uu} /Contents 4 0 R >>"
                    )
                    .into_bytes(),
                ),
                (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
            ],
            "/Root 1 0 R",
        );
        assert_construct_survives(&format!("/Rotate {rotate} /UserUnit {uu}"), pdf);
    }
}

/// HAZARD: an inherited-attribute chain (/Resources, /MediaBox) hidden behind a
/// long /Parent chain must be bounded, and a /Resources that references the page
/// must not recurse.
#[test]
fn long_parent_chains_and_self_referential_resources_terminate() {
    // A 200-deep /Parent chain, against a 32-hop bound.
    let mut objs: Vec<(u32, Vec<u8>)> = vec![
        (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
        (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
        (3, b"<< /Type /Page /Parent 5 0 R /Contents 4 0 R >>".to_vec()),
        (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
    ];
    for id in 5u32..205 {
        objs.push((
            id,
            format!("<< /Type /Pages /Parent {} 0 R /Kids [3 0 R] /Count 1 >>", id + 1).into_bytes(),
        ));
    }
    objs.push((
        205,
        b"<< /Type /Pages /MediaBox [0 0 612 792] /Kids [3 0 R] /Count 1 >>".to_vec(),
    ));
    assert_construct_survives(
        "200-deep /Parent chain hiding /MediaBox",
        raw_pdf(&objs, "/Root 1 0 R"),
    );

    // /Resources that is the page dictionary itself.
    let pdf = raw_pdf(
        &[
            (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
            (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
            (
                3,
                b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R \
                  /Resources 3 0 R >>"
                    .to_vec(),
            ),
            (4, raw_stream("<< /Length 26 >>", b"/Fm0 Do 0 0 10 10 re f    ")),
        ],
        "/Root 1 0 R",
    );
    assert_construct_survives("/Resources pointing at the page dictionary", pdf);
}

/// HAZARD: operand-free operators amplify tiny content streams into large heap.
///
/// Reported by `bench` from live-heap measurement, and it is a hazard class none
/// of my other tests reach. Their numbers: a parsed `Operation` costs a ~544-byte
/// floor REGARDLESS of operand count — `re` with 4 operands, `l` with 2, and a
/// bare `q` or `Q` with ZERO operands all cost 544 B. So `"q\nQ\n"` (4 bytes of
/// content) retains ~1088 bytes: **~272x amplification from post-filter bytes to
/// heap**. They measured 400k `q`/`Q` operations from ~800 KB of content retaining
/// 207 MiB.
///
/// Why the existing caps do not bound this, which is the whole point:
///   * `MAX_DECODED_BYTES` (filters.rs:348) bounds FILTER output. The
///     amplification happens later, in `content.rs` building `Vec<Operation>`, so
///     a decompression-bomb cap cannot see it. My
///     `flate_decompression_bomb_is_capped_not_inflated` is a different stage and
///     remains valid.
///   * `MAX_PRIMITIVES` (300_000) cannot bind: bare `q`/`Q` emit ZERO primitives,
///     so the primitive cap never engages.
///   * `MAX_CONTENT_OPS` (1_000_000) is therefore the ONLY thing standing between
///     a ~2 MB content stream and 1_000_000 x 544 B = ~544 MB of live heap.
///
/// This test asserts the one bound that actually applies — that the operation
/// count is capped — because that is the invariant, and because I cannot measure
/// heap here: `perf_tests.rs` already installs the crate's single permitted
/// `#[global_allocator]`, and a binary may only have one. So this covers
/// boundedness and termination; the 544 B/op constant itself is `bench`'s
/// measurement, not something this test re-derives.
#[test]
fn operand_free_operator_floods_are_bounded_by_the_operation_cap() {
    // 600k bare q/Q pairs = 1.2M operators from ~2.4 MB of content, past the
    // 1M MAX_CONTENT_OPS cap. At bench's 544 B/op an uncapped parse would retain
    // over 600 MB.
    let pairs = 600_000usize;
    let mut content = Vec::with_capacity(pairs * 4);
    for _ in 0..pairs {
        content.extend_from_slice(b"q\nQ\n");
    }
    let declared = content.len();
    let v = guarded("4M bare q/Q operators", flood_budget(), move || {
        let ops = crate::content::parse_operations_lenient(&content);
        eprintln!(
            "[robustness] {declared} bytes of bare q/Q -> {} operations (cap {}), \
             ~{} MiB at bench's measured 544 B/op",
            ops.len(),
            MAX_CONTENT_OPS,
            ops.len() * 544 / (1024 * 1024)
        );
        assert!(
            ops.len() <= MAX_CONTENT_OPS,
            "the operation cap did not bind: {} operations parsed from {} bytes \
             of operand-free content, cap is {}. At the measured 544 B/op floor \
             that is ~{} MiB of live heap, and no other cap can bound it: \
             MAX_PRIMITIVES cannot engage because q/Q emit zero primitives, and \
             MAX_DECODED_BYTES only bounds filter output, not Vec<Operation>.",
            ops.len(),
            declared,
            MAX_CONTENT_OPS,
            ops.len() * 544 / (1024 * 1024)
        );
    });
    assert!(
        !v.is_failure(),
        "ROBUSTNESS FAILURE — operand-free operator flood: {v:?}"
    );

    // The same shape through the real page path, and with other zero/one-operand
    // operators, so the cap is not specific to q/Q.
    for (name, unit) in [
        ("q/Q", &b"q Q "[..]),
        ("BT/ET", &b"BT ET "[..]),
        ("W n", &b"W n "[..]),
        ("BMC/EMC", &b"BMC EMC "[..]),
        ("gs-no-such", &b"/NoSuch gs "[..]),
        ("h", &b"h "[..]),
    ] {
        let mut content = Vec::new();
        while content.len() < 1024 * 1024 {
            content.extend_from_slice(unit);
        }
        let pdf = raw_one_page("", &content, vec![]);
        let v = guarded(
            &format!("1 MiB of {name}"),
            flood_budget(),
            move || exercise(&pdf),
        );
        assert!(
            !v.is_failure(),
            "ROBUSTNESS FAILURE — 1 MiB of operand-free `{name}` operators: {v:?}"
        );
    }
}

// ---------------------------------------------------------------------------
// LAYER 3 — budget / timing
// ---------------------------------------------------------------------------

/// The budget assertion is only meaningful if a normal page is far inside it.
/// This measures real seed renders against the calibrated mutant budget and
/// prints both, so the headroom is visible in the log rather than asserted on
/// faith. It also reports each seed as a multiple of the reference page, which is
/// the unit the budgets are actually expressed in.
#[test]
fn a_normal_page_renders_far_inside_the_budget() {
    let reference = reference_render();
    let seeds = seeds();
    let mut worst = Duration::ZERO;
    for (name, bytes) in &seeds {
        let doc = load_document_lenient(bytes).expect("seed must load");
        let page_id = *doc.get_pages().values().next().unwrap();
        // Warm the font cache the way a real session would, then measure.
        let _ = interpret_page(&doc, page_id);
        let t0 = Instant::now();
        let page = interpret_page(&doc, page_id).expect("seed must interpret");
        let _ = crate::wire::serialize(&page);
        let dt = t0.elapsed();
        eprintln!(
            "[robustness] seed {name}: one page render = {dt:?} ({:.2}x reference)",
            dt.as_secs_f64() / reference.as_secs_f64().max(f64::MIN_POSITIVE)
        );
        worst = worst.max(dt);
    }
    let b = mutant_budget();
    let headroom = b / 10;
    eprintln!(
        "[robustness] reference = {reference:?}, mutant budget = {b:?} \
         ({MUTANT_BUDGET_X}x reference, floor {MUTANT_BUDGET_FLOOR:?})"
    );
    assert!(
        worst < headroom,
        "a normal page took {worst:?}, which is not comfortably inside \
         mutant_budget()/10 = {headroom:?}. Either the renderer regressed badly \
         or MUTANT_BUDGET_X needs raising — do NOT raise it without saying why."
    );
}

/// REGRESSION TEST — page RENDERING must not inherit the caller's stack.
///
/// The open path was pinned to an explicit worker stack for finding A; rendering
/// was not, even though `interpret_page` recurses through form XObjects, tiling
/// and shading patterns, soft-mask groups and Type 3 glyphs. Those are each
/// depth-capped (`MAX_GROUP_DEPTH` 10, `MAX_PATTERN_RECURSION` 4), so the depth is
/// a small constant — but the frames are large, and the headroom was still a
/// property of whichever thread called in. On Android that is a JNI thread with a
/// fraction of a desktop main thread's stack, which is the asymmetry that made the
/// same file open on a workstation and kill the app on a phone.
///
/// FIXED: `docedit::render_page` runs `interpret_page` on a worker with
/// `RENDER_STACK_BYTES`, so the caller's stack no longer bounds the render.
///
/// This drives the real entry point from a deliberately SMALL (256 KiB) thread,
/// nesting forms, a pattern and a soft mask together. Verified non-vacuous: with
/// the worker removed, this same case exits the test binary with
/// `STATUS_STACK_OVERFLOW` (0xc00000fd) rather than failing an assertion — the
/// process death is inherent to the hazard (see `WORKER_STACK`), which is why the
/// assertion here is "rendered non-empty", not "did not overflow".
#[test]
fn rendering_does_not_depend_on_the_calling_threads_stack() {
    let pdf = nested_forms_pattern_and_soft_mask_pdf();

    // 256 KiB: far below anything the interpreter could recurse through on its own,
    // and far below an Android JNI thread. If the render inherited it, this would
    // not return.
    let v = guarded_on_stack(
        "render from a small stack",
        construct_budget(),
        256 * 1024,
        move || {
            let handle = open_document(&pdf);
            assert_ne!(handle, 0, "the constructed document must open");
            let rendered = render_page(handle, 0);
            close_document(handle);
            assert!(
                rendered.is_some_and(|b| !b.is_empty()),
                "nested forms / pattern / soft mask must still render"
            );
        },
    );
    assert!(!v.is_failure(), "{v:?}");
}

/// A page that drives every recursive interpreter path at once: a form-XObject
/// chain deeper than the interpreter's own cap (so the cap, not the stack, is
/// what stops it), a tiling pattern, and a luminosity soft mask whose group
/// re-enters the chain. Shared by the three small-stack regression tests, which
/// differ only in which entry point they drive.
fn nested_forms_pattern_and_soft_mask_pdf() -> Vec<u8> {
    let mut objects: Vec<(u32, Vec<u8>)> = Vec::new();
    const CHAIN: u32 = 24;
    for k in 0..CHAIN {
        let id = 10 + k;
        let body = format!("q /GS0 gs /Pt0 scn 0 0 8 8 re f /Fm{} Do Q", k + 1);
        objects.push((
            id,
            raw_stream(
                &format!(
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] \
                     /Resources << /XObject << /Fm{} {} 0 R >> \
                     /Pattern << /Pt0 60 0 R >> /ExtGState << /GS0 61 0 R >> >> \
                     /Length {} >>",
                    k + 1,
                    id + 1,
                    body.len()
                ),
                body.as_bytes(),
            ),
        ));
    }
    // The tail of the chain terminates.
    objects.push((
        10 + CHAIN,
        raw_stream(
            "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] /Length 15 >>",
            b"0 0 4 4 re f  ",
        ),
    ));
    objects.push((
        60,
        raw_stream(
            "<< /Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 \
             /BBox [0 0 8 8] /XStep 8 /YStep 8 /Length 15 >>",
            b"0 0 8 8 re f  ",
        ),
    ));
    objects.push((
        61,
        b"<< /Type /ExtGState /SMask << /S /Luminosity /G 62 0 R >> >>".to_vec(),
    ));
    objects.push((
        62,
        raw_stream(
            "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] \
             /Group << /S /Transparency /CS /DeviceGray >> \
             /Resources << /XObject << /Fm0 10 0 R >> >> /Length 9 >>",
            b"/Fm0 Do ",
        ),
    ));
    raw_one_page(
        "/Resources << /XObject << /Fm0 10 0 R >> /Pattern << /Pt0 60 0 R >> \
         /ExtGState << /GS0 61 0 R >> >>",
        b"/Pattern cs /Pt0 scn /GS0 gs /Fm0 Do",
        objects,
    )
}

/// REGRESSION TEST — TEXT EXTRACTION must not inherit the caller's stack.
///
/// `forms::document_text` is the second JNI-reachable path into the interpreter
/// (`PdfNative.extractText`), and it recursed on the caller's stack exactly as
/// `render_page` did before it was pinned: the same form-XObject, tiling-pattern,
/// soft-mask and Type 3 recursion, with the same depth-capped-but-large frames.
///
/// FIXED: `document_text` runs the per-page loop on a worker with
/// `RENDER_STACK_BYTES`.
///
/// Verified non-vacuous the same way as `rendering_does_not_depend_on_the_calling_threads_stack`:
/// with the worker removed, this case exits the test binary with
/// `STATUS_STACK_OVERFLOW` (0xc00000fd) instead of failing an assertion, which is
/// why the assertion is "extraction returned", not "did not overflow".
#[test]
fn text_extraction_does_not_depend_on_the_calling_threads_stack() {
    let pdf = nested_forms_pattern_and_soft_mask_pdf();
    let v = guarded_on_stack(
        "extractText from a small stack",
        construct_budget(),
        256 * 1024,
        move || {
            let handle = open_document(&pdf);
            assert_ne!(handle, 0, "the constructed document must open");
            let text = document_text(handle);
            close_document(handle);
            // The page paints only rectangles, so the text is legitimately empty —
            // what matters is that the call returned at all rather than taking the
            // process down.
            assert!(
                text.is_some(),
                "text extraction over nested forms / pattern / soft mask must return"
            );
        },
    );
    assert!(!v.is_failure(), "{v:?}");
}

/// REGRESSION TEST — SEARCH INDEXING must not inherit the caller's stack.
///
/// `search::ensure_index` is the third JNI-reachable path into the interpreter
/// (`PdfNative.searchDocument` / `buildSearchIndex`): `build_index` calls
/// `content::page_operations` and `interpret_content` per page, so it carries the
/// same recursion — through the same form XObjects, patterns, soft masks and
/// Type 3 glyphs — on whatever stack called in.
///
/// FIXED: `ensure_index` builds on a worker with `RENDER_STACK_BYTES`.
///
/// Verified non-vacuous the same way: with the worker removed this case exits with
/// `STATUS_STACK_OVERFLOW` (0xc00000fd).
#[test]
fn search_indexing_does_not_depend_on_the_calling_threads_stack() {
    let pdf = nested_forms_pattern_and_soft_mask_pdf();
    let v = guarded_on_stack(
        "searchDocument from a small stack",
        construct_budget(),
        256 * 1024,
        move || {
            let handle = open_document(&pdf);
            assert_ne!(handle, 0, "the constructed document must open");
            let hits = search_document(handle, "anything");
            close_document(handle);
            assert!(
                hits.is_some(),
                "search over nested forms / pattern / soft mask must return"
            );
        },
    );
    assert!(!v.is_failure(), "{v:?}");
}

/// Bisection probe for the nesting stack-overflow finding. Ignored because a
/// stack overflow is a guard-page fault that kills the process, so it cannot be
/// a normal test. Driven by env vars and run one depth per process:
///
///   FUZZ_NEST_DEPTH=<n> FUZZ_NEST_TARGET=strict|lenient|page|objgraph \
///     cargo test -- --ignored bisect_nesting_depth
///
/// The process exit code is the result: 0 = survived, 0xc00000fd = overflow.
#[test]
#[ignore = "diagnostic probe; a stack overflow kills the process"]
fn bisect_nesting_depth() {
    let depth: usize = std::env::var("FUZZ_NEST_DEPTH")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(500);
    let target = std::env::var("FUZZ_NEST_TARGET").unwrap_or_else(|_| "page".to_string());
    eprintln!("[bisect] target={target} depth={depth}");

    let mut nested = Vec::new();
    for _ in 0..depth {
        nested.push(b'[');
    }
    nested.extend_from_slice(b"(x)");
    for _ in 0..depth {
        nested.push(b']');
    }

    let work: Box<dyn FnOnce() + Send> = match target.as_str() {
        // lopdf's strict content parser, called DIRECTLY.
        //
        // NOTE: this target deliberately bypasses the fix. The guard added for
        // finding B is a pre-check in `content::strict_operations`, so
        // `Content::decode` itself is still unbounded — measured here as
        // STACK_OVERFLOW at depth 48 on a 1 MiB stack even after the fix. That is
        // EXPECTED and is NOT a live app bug: nothing reachable from the app calls
        // `Content::decode` without the pre-check. Use the "page" target to test
        // what the app actually does. This target is kept only to demonstrate that
        // the underlying dependency behaviour is unchanged, i.e. that the fix is a
        // call-site guard rather than an upstream repair.
        "strict" => Box::new(move || {
            let mut ops = Vec::from(&b"BT "[..]);
            ops.extend_from_slice(&nested);
            ops.extend_from_slice(b" TJ ET");
            let r = lopdf::content::Content::decode(&ops);
            eprintln!("[bisect] strict decode ok={}", r.is_ok());
        }),
        // Our own lenient re-tokenizer.
        "lenient" => Box::new(move || {
            let mut ops = Vec::from(&b"BT "[..]);
            ops.extend_from_slice(&nested);
            ops.extend_from_slice(b" TJ ET");
            let v = crate::content::parse_operations_lenient(&ops);
            eprintln!("[bisect] lenient ops={}", v.len());
        }),
        // Nesting in the object graph rather than a content stream.
        "objgraph" | "load" => {
            let load_only = target == "load";
            Box::new(move || {
                let mut dict = Vec::new();
                for _ in 0..depth {
                    dict.extend_from_slice(b"<< /K ");
                }
                dict.extend_from_slice(b"0");
                for _ in 0..depth {
                    dict.extend_from_slice(b" >>");
                }
                let pdf = raw_one_page("/DeepNest 5 0 R", b"0 0 10 10 re f", vec![(5, dict)]);
                if load_only {
                    let loaded = load_document_lenient(&pdf).is_some();
                    eprintln!("[bisect] load-only survived, loaded={loaded}");
                } else {
                    exercise(&pdf);
                    eprintln!("[bisect] objgraph survived");
                }
            })
        }
        // The full page path, as the real app hits it.
        _ => Box::new(move || {
            let mut ops = Vec::from(&b"BT "[..]);
            ops.extend_from_slice(&nested);
            ops.extend_from_slice(b" TJ ET");
            let pdf = raw_one_page("", &ops, vec![]);
            exercise(&pdf);
            eprintln!("[bisect] page path survived");
        }),
    };
    // Same stack size as the harness unless overridden, so the bisect transfers.
    let stack: usize = std::env::var("FUZZ_NEST_STACK")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(WORKER_STACK);
    let v = guarded_on_stack("bisect", Duration::from_secs(60), stack, work);
    eprintln!("[bisect] stack={stack} verdict={v:?}");
}

/// The guard itself must work, or every test above passes vacuously. Verifies
/// that a hang is detected as a hang and a panic as a panic.
#[test]
fn the_guard_detects_hangs_and_panics() {
    let v = guarded("self-test-hang", Duration::from_millis(150), || {
        // Deliberate spin, deliberately leaked. Bounded so the leaked thread
        // cannot outlive the test binary in a way that burns CPU forever.
        let t0 = Instant::now();
        while t0.elapsed() < Duration::from_secs(20) {
            std::hint::spin_loop();
        }
    });
    assert!(
        matches!(v, Verdict::TimedOut),
        "the timeout guard failed to detect a hang: {v:?}"
    );

    let v = guarded("self-test-panic", construct_budget(), || {
        panic!("deliberate");
    });
    match v {
        Verdict::Panicked(msg, _) => assert!(msg.contains("deliberate")),
        other => panic!("the panic guard failed to capture a panic: {other:?}"),
    }

    let v = guarded("self-test-ok", construct_budget(), || {});
    assert!(matches!(v, Verdict::Completed(_)), "{v:?}");
}