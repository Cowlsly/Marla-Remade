//! Performance measurement harness.
//!
//! Every test here is `#[ignore]`d so it never runs in the normal suite. Run it
//! explicitly, and only in release:
//!
//! ```text
//! cargo test --release -- --ignored --nocapture
//! ```
//!
//! Debug-mode timings for this crate are misleading by more than an order of
//! magnitude (no inlining, no `lto`, overflow checks on every arithmetic op), and
//! release is what ships, so a debug number is not evidence about anything.
//!
//! There is no `criterion` dependency: `pdf_render` is `crate-type = ["cdylib"]`,
//! so a `benches/` target cannot link against it, and criterion only appears in
//! the workspace lockfile as a dev-dependency of the vendored `third_party`
//! crates (registry source, not vendored by path). This hand-rolls
//! `std::time::Instant` instead and reports a median with spread rather than a
//! single sample.
//!
//! Methodology, applied uniformly by [`bench`]:
//! - discard warmup iterations so the measurement excludes first-touch page
//!   faults and cold instruction cache,
//! - take the *median* of the remaining samples, not the mean, because a single
//!   scheduler preemption skews a mean and cannot skew a median,
//! - report the interquartile spread as a percentage of the median, so a reader
//!   can tell whether a difference between two rows exceeds the noise,
//! - report min, since for a deterministic single-threaded workload the fastest
//!   observed run is the one least perturbed by the rest of the machine.
//!
//! Two differences are only called a win when the gap exceeds the measured noise
//! floor (see [`noise_floor`]); otherwise this reports "within noise".

use crate::*;
use lopdf::{dictionary, Dictionary, Document, Object, ObjectId, Stream};
use std::alloc::{GlobalAlloc, Layout, System};
use std::hint::black_box;
use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering::Relaxed};
use std::time::Instant;

// ---------------------------------------------------------------------------
// Peak-heap instrumentation
// ---------------------------------------------------------------------------

/// Counting shim over the system allocator, used to report peak *heap* bytes.
///
/// Reading RSS portably from inside a test needs a platform crate this crate
/// does not depend on, so instead this measures net heap live-bytes directly,
/// which is the quantity the OOM question actually turns on.
///
/// Accounting is fully disabled unless [`TRACK`] is set, so the normal test
/// suite pays one relaxed bool load per allocation and nothing else. Peak is
/// measured as a high-water mark of net bytes allocated since [`mem_reset`], so
/// frees of blocks that predate the reset can bias it low; that makes the
/// reported figure a lower bound, which is the safe direction for a budget.
struct TrackingAlloc;

static TRACK: AtomicBool = AtomicBool::new(false);
static CUR: AtomicIsize = AtomicIsize::new(0);
static PEAK: AtomicIsize = AtomicIsize::new(0);

unsafe impl GlobalAlloc for TrackingAlloc {
    unsafe fn alloc(&self, l: Layout) -> *mut u8 {
        let p = System.alloc(l);
        if !p.is_null() && TRACK.load(Relaxed) {
            let cur = CUR.fetch_add(l.size() as isize, Relaxed) + l.size() as isize;
            PEAK.fetch_max(cur, Relaxed);
        }
        p
    }
    unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
        if TRACK.load(Relaxed) {
            CUR.fetch_sub(l.size() as isize, Relaxed);
        }
        System.dealloc(p, l)
    }
}

#[global_allocator]
static ALLOC: TrackingAlloc = TrackingAlloc;

fn mem_reset() {
    CUR.store(0, Relaxed);
    PEAK.store(0, Relaxed);
    TRACK.store(true, Relaxed);
}

/// Peak net heap bytes since [`mem_reset`], and stop accounting.
fn mem_peak() -> usize {
    TRACK.store(false, Relaxed);
    PEAK.load(Relaxed).max(0) as usize
}

/// Net heap bytes live *right now*, without stopping accounting. Comparing this
/// against [`mem_peak`] separates what a phase RETAINS from what it transiently
/// allocated while running.
fn mem_current() -> usize {
    CUR.load(Relaxed).max(0) as usize
}

fn mib(bytes: usize) -> f64 {
    bytes as f64 / (1024.0 * 1024.0)
}

// ---------------------------------------------------------------------------
// Timing harness
// ---------------------------------------------------------------------------

struct Stats {
    median_ms: f64,
    min_ms: f64,
    /// Interquartile spread as a percentage of the median.
    spread_pct: f64,
    iters: usize,
}

impl Stats {
    fn row(&self, label: &str, n: usize, extra: &str) {
        println!(
            "{:<38} n={:<9} median={:>10.3} ms  min={:>10.3} ms  spread={:>5.1}%  iters={:<3} {}",
            label, n, self.median_ms, self.min_ms, self.spread_pct, self.iters, extra
        );
    }
}

/// Time `f` `iters` times after `warmup` discarded runs; median + IQR spread.
fn bench<F: FnMut()>(iters: usize, mut f: F) -> Stats {
    let warmup = 3.min(iters);
    for _ in 0..warmup {
        f();
    }
    let mut samples = Vec::with_capacity(iters);
    for _ in 0..iters {
        let t = Instant::now();
        f();
        samples.push(t.elapsed().as_secs_f64() * 1000.0);
    }
    samples.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let median = samples[samples.len() / 2];
    let q1 = samples[samples.len() / 4];
    let q3 = samples[samples.len() * 3 / 4];
    Stats {
        median_ms: median,
        min_ms: samples[0],
        spread_pct: if median > 0.0 {
            (q3 - q1) / median * 100.0
        } else {
            0.0
        },
        iters,
    }
}

/// Ratio of two medians, plus whether the gap clears the noise floor.
fn verdict(base: &Stats, alt: &Stats, noise_pct: f64) -> String {
    if base.median_ms <= 0.0 {
        return "n/a".into();
    }
    let ratio = alt.median_ms / base.median_ms;
    let delta_pct = (ratio - 1.0).abs() * 100.0;
    // Require the gap to exceed both benchmarks' own spread and the machine
    // noise floor before calling it real.
    let threshold = noise_pct.max(base.spread_pct).max(alt.spread_pct);
    if delta_pct <= threshold {
        format!(
            "WITHIN NOISE (delta {:.1}% <= threshold {:.1}%)",
            delta_pct, threshold
        )
    } else if ratio < 1.0 {
        format!("{:.2}x FASTER (delta {:.1}%)", 1.0 / ratio, delta_pct)
    } else {
        format!("{:.2}x SLOWER (delta {:.1}%)", ratio, delta_pct)
    }
}

/// Per-step growth factor of a series of medians, printed next to the doubling
/// of `n`. Linear scaling shows the same factor as the `n` step; a larger factor
/// is super-linear and a factor near 1.0 means a constant dominates.
fn scaling(ns: &[usize], meds: &[f64]) {
    println!("  scaling:");
    for i in 1..ns.len() {
        let nfac = ns[i] as f64 / ns[i - 1] as f64;
        let tfac = if meds[i - 1] > 0.0 {
            meds[i] / meds[i - 1]
        } else {
            0.0
        };
        let exponent = if nfac > 1.0 {
            tfac.ln() / nfac.ln()
        } else {
            0.0
        };
        let kind = if exponent < 0.4 {
            "constant-dominated"
        } else if exponent < 1.3 {
            "~linear"
        } else if exponent < 1.7 {
            "super-linear"
        } else {
            "~QUADRATIC or worse"
        };
        println!(
            "    n {:>7} -> {:>7} ({:.1}x):  time {:.2}x  => O(n^{:.2})  {}",
            ns[i - 1], ns[i], nfac, tfac, exponent, kind
        );
    }
}

// ---------------------------------------------------------------------------
// Synthetic document builders
// ---------------------------------------------------------------------------

/// Build an n-page document whose pages all share one inline resources dict
/// (so any indirect font object inside it is shared across pages, which is what
/// the font cache keys on).
fn build_doc(contents: &[Vec<u8>], res: Dictionary) -> (Document, Vec<ObjectId>) {
    let mut doc = Document::with_version("1.5");
    let pages_id = doc.new_object_id();
    let mut kids = Vec::new();
    let mut page_ids = Vec::new();
    for c in contents {
        let cid = doc.add_object(Stream::new(Dictionary::new(), c.clone()));
        let pid = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cid,
            "Resources" => res.clone(),
        });
        kids.push(pid.into());
        page_ids.push(pid);
    }
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages",
            "Kids" => kids,
            "Count" => contents.len() as i64,
        }),
    );
    let cat = doc.add_object(dictionary! {
        "Type" => "Catalog",
        "Pages" => pages_id,
    });
    doc.trailer.set("Root", cat);
    (doc, page_ids)
}

fn u16b(v: u16) -> [u8; 2] {
    v.to_be_bytes()
}
fn u32b(v: u32) -> [u8; 4] {
    v.to_be_bytes()
}

/// A structurally valid TrueType font with `n_glyphs` glyphs of `pts` points
/// each, so the cost of parsing an embedded font program can be scaled.
///
/// The contours are monotone staircases rather than letter shapes: geometrically
/// meaningless, but the table structure, point counts and parsing work are real,
/// which is what is being measured.
fn synth_ttf(n_glyphs: u16, pts: u16) -> Vec<u8> {
    let ng = n_glyphs.max(1);
    let np = pts.max(2);

    // glyf: one simple glyph per id, single contour of `np` points.
    let mut glyf: Vec<u8> = Vec::new();
    let mut loca: Vec<u32> = vec![0];
    for _ in 0..ng {
        let start = glyf.len();
        glyf.extend_from_slice(&u16b(1)); // numberOfContours
        glyf.extend_from_slice(&u16b(0)); // xMin
        glyf.extend_from_slice(&u16b(0)); // yMin
        glyf.extend_from_slice(&u16b(500)); // xMax
        glyf.extend_from_slice(&u16b(700)); // yMax
        glyf.extend_from_slice(&u16b(np - 1)); // endPtsOfContours[0]
        glyf.extend_from_slice(&u16b(0)); // instructionLength
        // flags: on-curve | x-short | y-short | x-positive | y-positive
        for _ in 0..np {
            glyf.push(0x01 | 0x02 | 0x04 | 0x10 | 0x20);
        }
        for _ in 0..np {
            glyf.push(3); // dx
        }
        for _ in 0..np {
            glyf.push(5); // dy
        }
        while glyf.len() % 4 != 0 {
            glyf.push(0);
        }
        debug_assert!(glyf.len() > start);
        loca.push(glyf.len() as u32);
    }
    let mut loca_b = Vec::new();
    for o in &loca {
        loca_b.extend_from_slice(&u32b(*o));
    }

    // head
    let mut head = Vec::new();
    head.extend_from_slice(&u32b(0x0001_0000)); // version
    head.extend_from_slice(&u32b(0x0001_0000)); // fontRevision
    head.extend_from_slice(&u32b(0)); // checkSumAdjustment
    head.extend_from_slice(&u32b(0x5F0F_3CF5)); // magic
    head.extend_from_slice(&u16b(0)); // flags
    head.extend_from_slice(&u16b(1000)); // unitsPerEm
    head.extend_from_slice(&[0u8; 16]); // created + modified
    head.extend_from_slice(&u16b(0)); // xMin
    head.extend_from_slice(&u16b(0)); // yMin
    head.extend_from_slice(&u16b(1000)); // xMax
    head.extend_from_slice(&u16b(1000)); // yMax
    head.extend_from_slice(&u16b(0)); // macStyle
    head.extend_from_slice(&u16b(8)); // lowestRecPPEM
    head.extend_from_slice(&u16b(2)); // fontDirectionHint
    head.extend_from_slice(&u16b(1)); // indexToLocFormat = long
    head.extend_from_slice(&u16b(0)); // glyphDataFormat

    // maxp v1.0
    let mut maxp = Vec::new();
    maxp.extend_from_slice(&u32b(0x0001_0000));
    maxp.extend_from_slice(&u16b(ng));
    maxp.extend_from_slice(&[0u8; 26]);

    // hhea
    let mut hhea = Vec::new();
    hhea.extend_from_slice(&u32b(0x0001_0000));
    hhea.extend_from_slice(&u16b(800)); // ascender
    hhea.extend_from_slice(&u16b(0xFF38)); // descender (-200)
    hhea.extend_from_slice(&u16b(0)); // lineGap
    hhea.extend_from_slice(&u16b(1000)); // advanceWidthMax
    hhea.extend_from_slice(&[0u8; 22]);
    hhea.extend_from_slice(&u16b(ng)); // numberOfHMetrics

    // hmtx
    let mut hmtx = Vec::new();
    for _ in 0..ng {
        hmtx.extend_from_slice(&u16b(500)); // advanceWidth
        hmtx.extend_from_slice(&u16b(0)); // lsb
    }

    // cmap: one format-0 subtable (platform 1, Mac Roman), codes 0..255.
    let mut sub = Vec::new();
    sub.extend_from_slice(&u16b(0)); // format
    sub.extend_from_slice(&u16b(262)); // length
    sub.extend_from_slice(&u16b(0)); // language
    for c in 0..256u16 {
        sub.push((c % ng.min(256)) as u8);
    }
    let mut cmap = Vec::new();
    cmap.extend_from_slice(&u16b(0)); // version
    cmap.extend_from_slice(&u16b(1)); // numTables
    cmap.extend_from_slice(&u16b(1)); // platformID
    cmap.extend_from_slice(&u16b(0)); // encodingID
    cmap.extend_from_slice(&u32b(12)); // offset
    cmap.extend_from_slice(&sub);

    // Assemble the sfnt. Table records must be in ascending tag order.
    let tables: Vec<(&[u8; 4], Vec<u8>)> = vec![
        (b"cmap", cmap),
        (b"glyf", glyf),
        (b"head", head),
        (b"hhea", hhea),
        (b"hmtx", hmtx),
        (b"loca", loca_b),
        (b"maxp", maxp),
    ];
    let num = tables.len() as u16;
    let mut out = Vec::new();
    out.extend_from_slice(&u32b(0x0001_0000)); // sfntVersion
    out.extend_from_slice(&u16b(num));
    let mut sr = 1u16;
    while sr * 2 <= num {
        sr *= 2;
    }
    out.extend_from_slice(&u16b(sr * 16)); // searchRange
    out.extend_from_slice(&u16b(0)); // entrySelector
    out.extend_from_slice(&u16b(num * 16 - sr * 16)); // rangeShift
    let mut offset = 12 + 16 * tables.len();
    let mut records = Vec::new();
    let mut body = Vec::new();
    for (tag, data) in &tables {
        records.extend_from_slice(*tag);
        records.extend_from_slice(&u32b(0)); // checksum (unverified by parsers)
        records.extend_from_slice(&u32b(offset as u32));
        records.extend_from_slice(&u32b(data.len() as u32));
        body.extend_from_slice(data);
        let mut pad = data.len();
        while pad % 4 != 0 {
            body.push(0);
            pad += 1;
        }
        offset += pad;
    }
    out.extend_from_slice(&records);
    out.extend_from_slice(&body);
    out
}

/// Resources with an embedded-TrueType simple font under `/F1`.
fn embedded_font_res(doc: &mut Document, n_glyphs: u16, pts: u16) -> Dictionary {
    let prog = synth_ttf(n_glyphs, pts);
    let len = prog.len() as i64;
    let ff = doc.add_object(Stream::new(
        dictionary! { "Length1" => len },
        prog,
    ));
    let fd = doc.add_object(dictionary! {
        "Type" => "FontDescriptor",
        "FontName" => "BenchFont",
        "Flags" => 4,
        "ItalicAngle" => 0,
        "Ascent" => 800,
        "Descent" => -200,
        "CapHeight" => 700,
        "StemV" => 80,
        "FontBBox" => vec![0.into(), (-200).into(), 1000.into(), 1000.into()],
        "FontFile2" => ff,
    });
    let widths: Vec<Object> = (0..256).map(|_| Object::Integer(500)).collect();
    let font = doc.add_object(dictionary! {
        "Type" => "Font",
        "Subtype" => "TrueType",
        "BaseFont" => "BenchFont",
        "FirstChar" => 0,
        "LastChar" => 255,
        "Widths" => widths,
        "FontDescriptor" => fd,
    });
    dictionary! { "Font" => dictionary! { "F1" => font } }
}

/// Resources with a non-embedded standard font (AFM metrics path).
fn standard_font_res(doc: &mut Document) -> Dictionary {
    let font = doc.add_object(dictionary! {
        "Type" => "Font",
        "Subtype" => "Type1",
        "BaseFont" => "Helvetica",
    });
    dictionary! { "Font" => dictionary! { "F1" => font } }
}

fn text_content(n_runs: usize) -> Vec<u8> {
    let mut s = String::with_capacity(n_runs * 48);
    s.push_str("BT\n/F1 12 Tf\n");
    for i in 0..n_runs {
        let y = 780 - (i % 65) * 12;
        let x = 20 + (i / 65) % 20;
        s.push_str(&format!("1 0 0 1 {} {} Tm\n(Benchmark text run) Tj\n", x, y));
    }
    s.push_str("ET\n");
    s.into_bytes()
}

fn rect_content(n: usize) -> Vec<u8> {
    let mut s = String::with_capacity(n * 32);
    s.push_str("0.5 0.2 0.7 rg\n");
    for i in 0..n {
        let x = (i % 300) as f64 * 2.0;
        let y = (i / 300) as f64 * 2.0 % 780.0;
        s.push_str(&format!("{:.1} {:.1} 3 3 re f\n", x, y));
    }
    s.into_bytes()
}

// ---------------------------------------------------------------------------
// Noise floor
// ---------------------------------------------------------------------------

/// Run one fixed workload as two independent benchmarks and report how far
/// apart two measurements of *identical* work land. Any claimed difference
/// smaller than this is not a difference.
fn noise_floor() -> f64 {
    let mut doc0 = Document::with_version("1.5");
    let res = standard_font_res(&mut doc0);
    let (doc, pages) = build_doc(&[text_content(300)], res);
    let a = bench(15, || {
        black_box(interpret_page(&doc, pages[0]).unwrap());
    });
    let b = bench(15, || {
        black_box(interpret_page(&doc, pages[0]).unwrap());
    });
    let gap = ((b.median_ms - a.median_ms) / a.median_ms).abs() * 100.0;
    let floor = gap.max(a.spread_pct).max(b.spread_pct).max(3.0);
    println!("--- noise floor ---");
    a.row("identical workload, run A", 300, "");
    b.row("identical workload, run B", 300, "");
    println!(
        "  run-to-run gap on identical work: {:.1}%;  adopted noise floor: {:.1}%",
        gap, floor
    );
    println!("  (differences at or below the floor are reported as WITHIN NOISE)");
    floor
}

#[test]
#[ignore]
fn perf_noise_floor() {
    println!();
    noise_floor();
}

// ---------------------------------------------------------------------------
// 1. Text runs, and the reference page for the fuzz budget
// ---------------------------------------------------------------------------

#[test]
#[ignore]
fn perf_text_runs_scaling() {
    println!("\n=== text runs (standard font), single page ===");
    let ns = [250usize, 1000, 4000, 16000];
    let mut meds = Vec::new();
    for &n in &ns {
        let mut d0 = Document::with_version("1.5");
        let res = standard_font_res(&mut d0);
        let (doc, pages) = build_doc(&[text_content(n)], res);
        let mut prims = 0;
        let st = bench(9, || {
            let p = interpret_page(&doc, pages[0]).unwrap();
            prims = p.prims.len();
            black_box(p);
        });
        st.row("text runs", n, &format!("prims={}", prims));
        meds.push(st.median_ms);
    }
    scaling(&ns, &meds);
}

#[test]
#[ignore]
fn perf_reference_page_budget() {
    println!("\n=== reference 'normal page' (the fuzz budget baseline) ===");
    // A deliberately ordinary page: some text, some vector art. This is the
    // number `fuzz` should multiply to define "pathologically slow".
    let mut d0 = Document::with_version("1.5");
    let res = standard_font_res(&mut d0);
    let mut c = text_content(60);
    c.extend_from_slice(&rect_content(200));
    let (doc, pages) = build_doc(&[c], res);
    let st = bench(25, || {
        black_box(interpret_page(&doc, pages[0]).unwrap());
    });
    let p = interpret_page(&doc, pages[0]).unwrap();
    st.row("normal page (60 text + 200 rects)", 1, &format!("prims={}", p.prims.len()));
    println!(
        "  => normal page median {:.3} ms in RELEASE.  50x = {:.1} ms, 1000x = {:.1} ms",
        st.median_ms,
        st.median_ms * 50.0,
        st.median_ms * 1000.0
    );
}

// ---------------------------------------------------------------------------
// 2. CLAIM: the font cache. Author says it is a per-DOCUMENT win.
// ---------------------------------------------------------------------------

/// `interpret_page` opens its own [`FontCacheScope`] internally. Holding an
/// outer scope across several pages is therefore exactly the post-fix behaviour
/// (font parsed once per document); calling the pages with no outer scope is
/// exactly the pre-fix behaviour (parsed once per page). That gives a clean A/B
/// without touching production code.
#[test]
#[ignore]
fn perf_font_cache_claim() {
    println!("\n=== CLAIM: font cache (embedded TrueType, 400 glyphs x 64 pts) ===");
    let floor = noise_floor();
    println!();

    for &n_pages in &[1usize, 5, 20] {
        let mut d0 = Document::with_version("1.5");
        let res = embedded_font_res(&mut d0, 400, 64);
        let contents: Vec<Vec<u8>> = (0..n_pages).map(|_| text_content(200)).collect();
        // Rebuild in the real doc so the font objects belong to it.
        let mut doc = Document::with_version("1.5");
        let res = {
            let _ = res;
            embedded_font_res(&mut doc, 400, 64)
        };
        let pages_id = doc.new_object_id();
        let mut kids = Vec::new();
        let mut page_ids = Vec::new();
        for c in &contents {
            let cid = doc.add_object(Stream::new(Dictionary::new(), c.clone()));
            let pid = doc.add_object(dictionary! {
                "Type" => "Page",
                "Parent" => pages_id,
                "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
                "Contents" => cid,
                "Resources" => res.clone(),
            });
            kids.push(pid.into());
            page_ids.push(pid);
        }
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages", "Kids" => kids, "Count" => n_pages as i64,
            }),
        );
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);

        // Pre-fix: no document-wide scope, so each page parses the font again.
        let without = bench(9, || {
            for &pid in &page_ids {
                black_box(interpret_page(&doc, pid).unwrap());
            }
        });
        // Post-fix: one scope for the whole document.
        let with = bench(9, || {
            let _scope = FontCacheScope::new();
            for &pid in &page_ids {
                black_box(interpret_page(&doc, pid).unwrap());
            }
        });
        without.row("no document scope (pre-fix)", n_pages, "");
        with.row("document-wide scope (post-fix)", n_pages, "");
        println!(
            "  {} page(s): {}",
            n_pages,
            verdict(&without, &with, floor)
        );
        println!();
    }
    println!("  Read the 1-page row before believing the 20-page row.");
}

/// The single-page case the author flagged: does the cache help *within* one
/// page? It only can if `fonts_from_resources` runs more than once, i.e. if the
/// page has nested form XObjects that each re-resolve the same font.
#[test]
#[ignore]
fn perf_font_cache_single_page_nested_forms() {
    println!("\n=== font cache within ONE page, N nested form XObjects ===");
    let floor = 5.0;
    for &n_forms in &[1usize, 8, 32] {
        let mut doc = Document::with_version("1.5");
        let res_font = embedded_font_res(&mut doc, 400, 64);
        let font_obj = res_font
            .get(b"Font")
            .ok()
            .and_then(|o| o.as_dict().ok())
            .and_then(|d| d.get(b"F1").ok())
            .cloned()
            .unwrap();

        // Each form carries its own resources dict pointing at the SAME font
        // object, so every form triggers a fresh fonts_from_resources call.
        let mut xobjs = Dictionary::new();
        for i in 0..n_forms {
            let fres = dictionary! {
                "Font" => dictionary! { "F1" => font_obj.clone() },
            };
            let body = text_content(10);
            let fid = doc.add_object(Stream::new(
                dictionary! {
                    "Type" => "XObject",
                    "Subtype" => "Form",
                    "BBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
                    "Resources" => fres,
                },
                body,
            ));
            xobjs.set(format!("Fm{}", i), fid);
        }
        let mut s = String::new();
        for i in 0..n_forms {
            s.push_str(&format!("q /Fm{} Do Q\n", i));
        }
        let cid = doc.add_object(Stream::new(Dictionary::new(), s.into_bytes()));
        let res = dictionary! {
            "Font" => dictionary! { "F1" => font_obj },
            "XObject" => xobjs,
        };
        let pages_id = doc.new_object_id();
        let pid = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cid, "Resources" => res,
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
            }),
        );
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);

        let st = bench(9, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        st.row("1 page, nested forms sharing a font", n_forms, "");
        let _ = floor;
    }
    println!("  (interpret_page always holds a scope, so this is the POST-fix cost;");
    println!("   compare growth per form against the per-page parse cost above.)");
}

/// Cost of a cache *hit*. `FontInfo::clone` still deep-copies every non-`Arc`
/// field (`widths`, `encoding`, `cmap_uni`, `glyph_names`, `vertical_metrics`,
/// `cid_to_gid`, `cmap`); only `to_unicode` and `glyph_program` are shared. So a
/// hit is cheap only while those maps are small.
#[test]
#[ignore]
fn perf_font_cache_hit_cost_vs_width_table() {
    println!("\n=== cost of a font-cache HIT vs size of the /Widths table ===");
    let ns = [256usize, 4096, 32768];
    let mut meds = Vec::new();
    for &nw in &ns {
        let mut doc = Document::with_version("1.5");
        let prog = synth_ttf(400, 64);
        let len = prog.len() as i64;
        let ff = doc.add_object(Stream::new(dictionary! { "Length1" => len }, prog));
        let fd = doc.add_object(dictionary! {
            "Type" => "FontDescriptor", "FontName" => "BenchFont", "Flags" => 4,
            "ItalicAngle" => 0, "Ascent" => 800, "Descent" => -200,
            "CapHeight" => 700, "StemV" => 80,
            "FontBBox" => vec![0.into(), (-200).into(), 1000.into(), 1000.into()],
            "FontFile2" => ff,
        });
        // A Type0/CID font takes widths from /W, which can be huge for CJK.
        let mut w: Vec<Object> = Vec::new();
        for cid in 0..nw {
            w.push(Object::Integer(cid as i64));
            w.push(Object::Array(vec![Object::Integer(500)]));
        }
        let desc = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "CIDFontType2",
            "BaseFont" => "BenchFont",
            "CIDSystemInfo" => dictionary! {
                "Registry" => Object::string_literal("Adobe"),
                "Ordering" => Object::string_literal("Identity"),
                "Supplement" => 0,
            },
            "FontDescriptor" => fd,
            "DW" => 1000,
            "W" => w,
        });
        let font = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type0",
            "BaseFont" => "BenchFont", "Encoding" => "Identity-H",
            "DescendantFonts" => vec![desc.into()],
        });
        let res = dictionary! { "Font" => dictionary! { "F1" => font } };
        let cid_content = b"BT\n/F1 12 Tf\n1 0 0 1 20 700 Tm\n<00410042> Tj\nET\n".to_vec();

        let pages_id = doc.new_object_id();
        let cstream = doc.add_object(Stream::new(Dictionary::new(), cid_content));
        let pid = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cstream, "Resources" => res,
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
            }),
        );
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);

        // 40 pages sharing the font: 1 parse + 39 cache hits.
        let st = bench(7, || {
            let _scope = FontCacheScope::new();
            for _ in 0..40 {
                black_box(interpret_page(&doc, pid).unwrap());
            }
        });
        st.row("40 cached renders, /W entries", nw, "");
        meds.push(st.median_ms);
    }
    scaling(&ns, &meds);
    println!("  Every collection in FontInfo is now Arc-shared, so the CLONE is O(1).");
    println!("  What still grows with /W is the cache-key check: `font_identity`");
    println!("  hashes the font dict with references RESOLVED on every lookup, which");
    println!("  walks the /W array element by element (and, for a real font, the whole");
    println!("  embedded program's bytes). The per-hit cost moved rather than went away.");
}

// ---------------------------------------------------------------------------
// 3. CLAIM: soft-mask group caching
// ---------------------------------------------------------------------------

fn smask_doc(n_shapes: usize, interleave: bool) -> (Document, ObjectId) {
    let mut doc = Document::with_version("1.5");
    // Luminosity mask group: a form XObject with some content of its own.
    let mask_body = rect_content(40);
    let gid = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject",
            "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Group" => dictionary! {
                "S" => "Transparency",
                "CS" => "DeviceGray",
            },
        },
        mask_body,
    ));
    let gs_mask = doc.add_object(dictionary! {
        "Type" => "ExtGState",
        "SMask" => dictionary! {
            "S" => "Luminosity",
            "G" => gid,
        },
    });
    let gs_none = doc.add_object(dictionary! {
        "Type" => "ExtGState",
        "SMask" => "None",
    });

    let mut s = String::new();
    s.push_str("/GSM gs\n0.2 0.4 0.9 rg\n");
    for i in 0..n_shapes {
        if interleave {
            // Break the bracket every other shape, defeating adjacency-based
            // coalescing while keeping the same number of masked shapes.
            s.push_str("/GSN gs\n/GSM gs\n");
        }
        let x = (i % 300) as f64 * 2.0;
        let y = (i / 300) as f64 * 2.0 % 780.0;
        s.push_str(&format!("{:.1} {:.1} 4 4 re f\n", x, y));
    }
    let cid = doc.add_object(Stream::new(Dictionary::new(), s.into_bytes()));
    let res = dictionary! {
        "ExtGState" => dictionary! { "GSM" => gs_mask, "GSN" => gs_none },
    };
    let pages_id = doc.new_object_id();
    let pid = doc.add_object(dictionary! {
        "Type" => "Page", "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        "Contents" => cid, "Resources" => res,
    });
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
        }),
    );
    let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", cat);
    (doc, pid)
}

#[test]
#[ignore]
fn perf_softmask_claim() {
    println!("\n=== CLAIM: soft-mask group caching (one SMask over N shapes) ===");
    let ns = [50usize, 200, 800];
    let mut coalesced = Vec::new();
    let mut broken = Vec::new();
    for &n in &ns {
        let (doc, pid) = smask_doc(n, false);
        let st = bench(9, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        let p = interpret_page(&doc, pid).unwrap();
        st.row("contiguous masked shapes", n, &format!("prims={}", p.prims.len()));
        coalesced.push(st.median_ms);

        let (doc2, pid2) = smask_doc(n, true);
        let st2 = bench(9, || {
            black_box(interpret_page(&doc2, pid2).unwrap());
        });
        let p2 = interpret_page(&doc2, pid2).unwrap();
        st2.row("bracket broken between shapes", n, &format!("prims={}", p2.prims.len()));
        broken.push(st2.median_ms);
        println!();
    }
    println!(" contiguous (reuse possible):");
    scaling(&ns, &coalesced);
    println!(" bracket broken (reuse defeated):");
    scaling(&ns, &broken);
    println!("  The reuse is adjacency-based (MaskBracket requires b.end == start),");
    println!("  so the second series is the cost when anything intervenes.");
}

// ---------------------------------------------------------------------------
// 4. CLAIM: optional-content config hoisting
// ---------------------------------------------------------------------------

fn ocg_doc(n_sections: usize, distinct: bool) -> (Document, ObjectId) {
    let mut doc = Document::with_version("1.5");
    let n_ocgs = if distinct { n_sections } else { 1 };
    let mut ocg_ids = Vec::new();
    for i in 0..n_ocgs {
        let id = doc.add_object(dictionary! {
            "Type" => "OCG",
            "Name" => Object::string_literal(format!("Layer{}", i)),
        });
        ocg_ids.push(id);
    }
    // Every group listed in /ON, so the resolved config holds an n-element set —
    // which used to be an n-element array cloned and scanned on each call the
    // memo did not absorb.
    let on: Vec<Object> = ocg_ids.iter().map(|id| Object::Reference(*id)).collect();
    let all: Vec<Object> = on.clone();
    let d = dictionary! { "ON" => on, "OFF" => Vec::<Object>::new(), "BaseState" => "ON" };
    let cat_extra = dictionary! { "OCGs" => all, "D" => d };

    let mut props = Dictionary::new();
    let mut s = String::new();
    for i in 0..n_sections {
        let which = if distinct { i } else { 0 };
        props.set(format!("P{}", i), ocg_ids[which]);
        s.push_str(&format!(
            "/OC /P{} BDC\n0.1 0.2 0.3 rg\n{} 10 5 5 re f\nEMC\n",
            i,
            (i % 300) * 2
        ));
    }
    let cid = doc.add_object(Stream::new(Dictionary::new(), s.into_bytes()));
    let res = dictionary! { "Properties" => props };
    let pages_id = doc.new_object_id();
    let pid = doc.add_object(dictionary! {
        "Type" => "Page", "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        "Contents" => cid, "Resources" => res,
    });
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
        }),
    );
    let cat = doc.add_object(dictionary! {
        "Type" => "Catalog",
        "Pages" => pages_id,
        "OCProperties" => cat_extra,
    });
    doc.trailer.set("Root", cat);
    (doc, pid)
}

#[test]
#[ignore]
fn perf_optional_content_claim() {
    println!("\n=== CLAIM: optional-content config hoisting (N BDC/EMC sections) ===");
    // Extended past 1600 to answer a specific question: after the per-call
    // clones were removed, do the two remaining linear scans of /ON and /OFF
    // still produce a measurable O(N^2) at realistic layer counts?
    let ns = [100usize, 400, 1600, 3200, 6400];
    let mut same = Vec::new();
    let mut dist = Vec::new();
    for &n in &ns {
        // Big N is slow if the quadratic is still there; keep the sample count
        // low enough that the sweep terminates either way.
        let iters = if n > 1600 { 5 } else { 9 };
        let (doc, pid) = ocg_doc(n, false);
        let st = bench(iters, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        st.row("N sections, all ONE ocg (memo hits)", n, "");
        same.push(st.median_ms);

        let (doc2, pid2) = ocg_doc(n, true);
        let st2 = bench(iters, || {
            black_box(interpret_page(&doc2, pid2).unwrap());
        });
        st2.row("N sections, N DISTINCT ocgs", n, "");
        dist.push(st2.median_ms);
        println!(
            "    per-section cost: one-ocg {:.4} ms, distinct {:.4} ms",
            st.median_ms / n as f64,
            st2.median_ms / n as f64
        );
        println!();
    }
    println!(" all one OCG (memoized):");
    scaling(&ns, &same);
    println!(" N distinct OCGs (memo cannot help):");
    scaling(&ns, &dist);
    println!("  A flat 'per-section cost' for the distinct series means linear;");
    println!("  a per-section cost that grows with N means the scans still bite.");
}

// ---------------------------------------------------------------------------
// 5. CLAIM: shading raster sizing (square vs long thin)
// ---------------------------------------------------------------------------

fn axial_shading_dict() -> Dictionary {
    dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => "DeviceRGB",
        "Coords" => vec![0.into(), 0.into(), 600.into(), 0.into()],
        "Function" => dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![1.into(), 0.into(), 0.into()],
            "C1" => vec![0.into(), 0.into(), 1.into()],
            "N" => 1,
        },
        "Extend" => vec![true.into(), true.into()],
    }
}

fn shading_doc(n: usize, clip: (f64, f64)) -> (Document, ObjectId) {
    let mut doc = Document::with_version("1.5");
    let sh = doc.add_object(Object::Dictionary(axial_shading_dict()));
    let mut s = String::new();
    for _ in 0..n {
        s.push_str(&format!(
            "q 0 0 {:.1} {:.1} re W n /Sh0 sh Q\n",
            clip.0, clip.1
        ));
    }
    let cid = doc.add_object(Stream::new(Dictionary::new(), s.into_bytes()));
    let res = dictionary! { "Shading" => dictionary! { "Sh0" => sh } };
    let pages_id = doc.new_object_id();
    let pid = doc.add_object(dictionary! {
        "Type" => "Page", "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        "Contents" => cid, "Resources" => res,
    });
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
        }),
    );
    let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", cat);
    (doc, pid)
}

fn raster_bytes(p: &PageData) -> usize {
    p.prims
        .iter()
        .map(|pr| match pr {
            Prim::Image { data, .. } => data.len(),
            Prim::ImageTiled { data, .. } => data.len(),
            _ => 0,
        })
        .sum()
}

#[test]
#[ignore]
fn perf_shading_claim() {
    println!("\n=== CLAIM: shading raster sizing (square vs long thin) ===");
    let ns = [10usize, 40, 160];
    let mut meds = Vec::new();
    for &n in &ns {
        let (doc, pid) = shading_doc(n, (600.0, 600.0));
        let st = bench(7, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        let p = interpret_page(&doc, pid).unwrap();
        st.row(
            "N square shadings",
            n,
            &format!(
                "prims={} raster={:.2} MiB",
                p.prims.len(),
                mib(raster_bytes(&p))
            ),
        );
        meds.push(st.median_ms);
    }
    scaling(&ns, &meds);

    println!("\n  one shading, square vs long-thin clip (raster allocation):");
    for (label, clip) in [
        ("square 600x600", (600.0, 600.0)),
        ("thin   600x8", (600.0, 8.0)),
        ("thin   600x2", (600.0, 2.0)),
        ("thin   8x600", (8.0, 600.0)),
    ] {
        let (doc, pid) = shading_doc(1, clip);
        let st = bench(15, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        let p = interpret_page(&doc, pid).unwrap();
        let mut dims = String::new();
        for pr in &p.prims {
            if let Prim::Image { w, h, data, .. } = pr {
                dims = format!("{}x{} ({} B)", w, h, data.len());
            }
        }
        st.row(label, 1, &format!("raster={}", dims));
    }
    println!("  If the thin cases allocate ~the same bytes as the square one, the");
    println!("  raster is still effectively square-sized.");
}

// ---------------------------------------------------------------------------
// 6. CLAIM: tiling pattern rasterization
// ---------------------------------------------------------------------------

fn tiling_doc(pitch: f64) -> (Document, ObjectId) {
    let mut doc = Document::with_version("1.5");
    let cell = format!(
        "0 0 0 RG 0.4 w 0 0 m {:.2} {:.2} l S\n0 {:.2} m {:.2} 0 l S\n",
        pitch, pitch, pitch, pitch
    );
    let pat = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "Pattern",
            "PatternType" => 1,
            "PaintType" => 1,
            "TilingType" => 1,
            "BBox" => vec![0.into(), 0.into(), pitch.into(), pitch.into()],
            "XStep" => pitch,
            "YStep" => pitch,
            "Resources" => Dictionary::new(),
        },
        cell.into_bytes(),
    ));
    let content = b"/Pattern cs /P0 scn\n0 0 612 792 re f\n".to_vec();
    let cid = doc.add_object(Stream::new(Dictionary::new(), content));
    let res = dictionary! { "Pattern" => dictionary! { "P0" => pat } };
    let pages_id = doc.new_object_id();
    let pid = doc.add_object(dictionary! {
        "Type" => "Page", "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        "Contents" => cid, "Resources" => res,
    });
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
        }),
    );
    let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", cat);
    (doc, pid)
}

#[test]
#[ignore]
fn perf_tiling_pattern_claim() {
    println!("\n=== CLAIM: tiling pattern rasterization (full-page hatch) ===");
    // Smaller pitch = more tiles over the same page area, so tile count grows
    // as 1/pitch^2.
    let pitches = [32.0f64, 16.0, 8.0, 4.0, 2.0];
    let ns: Vec<usize> = pitches
        .iter()
        .map(|p| ((612.0 / p).ceil() * (792.0 / p).ceil()) as usize)
        .collect();
    let mut meds = Vec::new();
    for (i, &pitch) in pitches.iter().enumerate() {
        let (doc, pid) = tiling_doc(pitch);
        let st = bench(7, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        let p = interpret_page(&doc, pid).unwrap();
        st.row(
            &format!("hatch pitch {:.0} pt", pitch),
            ns[i],
            &format!(
                "prims={} raster={:.3} MiB",
                p.prims.len(),
                mib(raster_bytes(&p))
            ),
        );
        meds.push(st.median_ms);
    }
    scaling(&ns, &meds);
    println!("  n here is the TILE COUNT covering the page. Flat time against a");
    println!("  growing tile count means the cell is rasterized once, not per tile.");
}

// ---------------------------------------------------------------------------
// 7. Glyph outline extraction
// ---------------------------------------------------------------------------

#[test]
#[ignore]
fn perf_glyph_outlines_scaling() {
    println!("\n=== glyph outline extraction (embedded TrueType) ===");
    println!(" a) scaling in glyphs DRAWN (font fixed at 400 glyphs x 64 pts):");
    let ns = [200usize, 800, 3200];
    let mut meds = Vec::new();
    for &n in &ns {
        let mut doc = Document::with_version("1.5");
        let res = embedded_font_res(&mut doc, 400, 64);
        let pages_id = doc.new_object_id();
        let cid = doc.add_object(Stream::new(Dictionary::new(), text_content(n / 19 + 1)));
        let pid = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cid, "Resources" => res,
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
            }),
        );
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);
        let st = bench(9, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        let p = interpret_page(&doc, pid).unwrap();
        st.row("glyphs drawn (approx)", n, &format!("prims={}", p.prims.len()));
        meds.push(st.median_ms);
    }
    scaling(&ns, &meds);

    println!(" b) scaling in FONT PROGRAM size (same tiny page, one text run):");
    let gs = [100u16, 400, 1600];
    let mut meds2 = Vec::new();
    let nsz: Vec<usize> = gs.iter().map(|g| *g as usize).collect();
    for &g in &gs {
        let mut doc = Document::with_version("1.5");
        let res = embedded_font_res(&mut doc, g, 64);
        let pages_id = doc.new_object_id();
        let cid = doc.add_object(Stream::new(Dictionary::new(), text_content(1)));
        let pid = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cid, "Resources" => res,
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
            }),
        );
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);
        let st = bench(11, || {
            black_box(interpret_page(&doc, pid).unwrap());
        });
        st.row("font glyph count", g as usize, "one text run only");
        meds2.push(st.median_ms);
    }
    scaling(&nsz, &meds2);
    println!("  (b) is the fixed cost a page pays just for HAVING a big embedded font.");
}

// ---------------------------------------------------------------------------
// 8. Primitive count / memory: the "crasheshalfway" (OOM) hypothesis
// ---------------------------------------------------------------------------

#[test]
#[ignore]
fn perf_primitive_cap_and_memory() {
    println!("\n=== primitive count + PEAK HEAP (MAX_PRIMITIVES = {}) ===", MAX_PRIMITIVES);
    println!("  Peak heap measured with a counting global allocator (net live bytes");
    println!("  since reset), not RSS. Timing rows are measured with tracking OFF.");
    let ns = [10_000usize, 50_000, 200_000, 400_000];
    let mut meds = Vec::new();
    for &n in &ns {
        let mut d0 = Document::with_version("1.5");
        let res = standard_font_res(&mut d0);
        let (doc, pages) = build_doc(&[rect_content(n)], res);

        let st = bench(5, || {
            black_box(interpret_page(&doc, pages[0]).unwrap());
        });

        mem_reset();
        let p = interpret_page(&doc, pages[0]).unwrap();
        let prims = p.prims.len();
        let rb = raster_bytes(&p);
        drop(p);
        let peak = mem_peak();

        st.row(
            "rects requested",
            n,
            &format!(
                "prims={} ({}) peak_heap={:.2} MiB  prim_bytes~{:.2} MiB  raster={:.2} MiB",
                prims,
                if prims >= MAX_PRIMITIVES { "CAPPED" } else { "under cap" },
                mib(peak),
                mib(prims * std::mem::size_of::<Prim>()),
                mib(rb),
            ),
        );
        meds.push(st.median_ms);
    }
    scaling(&ns, &meds);
    println!("  size_of::<Prim>() = {} bytes", std::mem::size_of::<Prim>());
    println!(
        "  cap-implied ceiling on the primitive vector alone: {:.1} MiB",
        mib(MAX_PRIMITIVES * std::mem::size_of::<Prim>())
    );
}

#[test]
#[ignore]
fn perf_peak_memory_by_stressor() {
    println!("\n=== PEAK HEAP by stressor (counting allocator, net live bytes) ===");
    let mut rows: Vec<(String, usize, usize, usize)> = Vec::new();

    {
        let mut d0 = Document::with_version("1.5");
        let res = standard_font_res(&mut d0);
        let (doc, pages) = build_doc(&[rect_content(200_000)], res);
        mem_reset();
        let p = interpret_page(&doc, pages[0]).unwrap();
        let (a, b) = (p.prims.len(), raster_bytes(&p));
        drop(p);
        rows.push(("200k rects".into(), mem_peak(), a, b));
    }
    {
        let (doc, pid) = shading_doc(160, (600.0, 600.0));
        mem_reset();
        let p = interpret_page(&doc, pid).unwrap();
        let (a, b) = (p.prims.len(), raster_bytes(&p));
        drop(p);
        rows.push(("160 square shadings".into(), mem_peak(), a, b));
    }
    {
        let (doc, pid) = shading_doc(160, (600.0, 2.0));
        mem_reset();
        let p = interpret_page(&doc, pid).unwrap();
        let (a, b) = (p.prims.len(), raster_bytes(&p));
        drop(p);
        rows.push(("160 long-thin shadings".into(), mem_peak(), a, b));
    }
    {
        let (doc, pid) = tiling_doc(2.0);
        mem_reset();
        let p = interpret_page(&doc, pid).unwrap();
        let (a, b) = (p.prims.len(), raster_bytes(&p));
        drop(p);
        rows.push(("fine hatch tiling (pitch 2)".into(), mem_peak(), a, b));
    }
    {
        let (doc, pid) = smask_doc(800, false);
        mem_reset();
        let p = interpret_page(&doc, pid).unwrap();
        let (a, b) = (p.prims.len(), raster_bytes(&p));
        drop(p);
        rows.push(("800 shapes under one SMask".into(), mem_peak(), a, b));
    }
    {
        let mut doc = Document::with_version("1.5");
        let res = embedded_font_res(&mut doc, 1600, 64);
        let pages_id = doc.new_object_id();
        let cid = doc.add_object(Stream::new(Dictionary::new(), text_content(2000)));
        let pid = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cid, "Resources" => res,
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
            }),
        );
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);
        mem_reset();
        let p = interpret_page(&doc, pid).unwrap();
        let (a, b) = (p.prims.len(), raster_bytes(&p));
        drop(p);
        rows.push(("2000 glyph runs, 1600-glyph font".into(), mem_peak(), a, b));
    }

    println!(
        "{:<34} {:>14} {:>10} {:>14}",
        "stressor", "peak heap MiB", "prims", "raster MiB"
    );
    for (name, peak, prims, rb) in &rows {
        println!(
            "{:<34} {:>14.2} {:>10} {:>14.2}",
            name,
            mib(*peak),
            prims,
            mib(*rb)
        );
    }
    println!("  Highest peak here is the best available proxy for the OOM risk that");
    println!("  the 'crasheshalfway' symptom would come from.");
}

// ---------------------------------------------------------------------------
// 9. Font cache, sharpened
// ---------------------------------------------------------------------------

/// Build an n-page doc sharing one embedded font, with `runs` text runs per page.
fn font_doc(n_pages: usize, glyphs: u16, pts: u16, runs: usize) -> (Document, Vec<ObjectId>) {
    let mut doc = Document::with_version("1.5");
    let res = embedded_font_res(&mut doc, glyphs, pts);
    let pages_id = doc.new_object_id();
    let mut kids = Vec::new();
    let mut page_ids = Vec::new();
    for _ in 0..n_pages {
        let cid = doc.add_object(Stream::new(Dictionary::new(), text_content(runs)));
        let pid = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cid, "Resources" => res.clone(),
        });
        kids.push(pid.into());
        page_ids.push(pid);
    }
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => kids, "Count" => n_pages as i64,
        }),
    );
    let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", cat);
    (doc, page_ids)
}

/// The first font-cache benchmark buried the parse cost under 200 text runs of
/// rendering per page. This isolates it: a *large* embedded font and almost no
/// drawing, so per-page font parsing is the dominant term. If the cache is worth
/// anything, it has to show up here.
#[test]
#[ignore]
fn perf_font_cache_isolated() {
    println!("\n=== CLAIM: font cache, ISOLATED (big font, minimal drawing) ===");
    let floor = noise_floor();
    println!();
    for &(glyphs, runs) in &[(1600u16, 2usize), (1600, 40), (400, 2)] {
        for &n_pages in &[1usize, 20] {
            let (doc, pages) = font_doc(n_pages, glyphs, 64, runs);
            let without = bench(9, || {
                for &pid in &pages {
                    black_box(interpret_page(&doc, pid).unwrap());
                }
            });
            let with = bench(9, || {
                let _scope = FontCacheScope::new();
                for &pid in &pages {
                    black_box(interpret_page(&doc, pid).unwrap());
                }
            });
            let label = format!("{}-glyph font, {} runs/page", glyphs, runs);
            without.row(&format!("{} [pre-fix]", label), n_pages, "");
            with.row(&format!("{} [post-fix]", label), n_pages, "");
            println!(
                "  {} pages, {}-glyph font, {} runs/page: {}",
                n_pages,
                glyphs,
                runs,
                verdict(&without, &with, floor)
            );
            if n_pages > 1 {
                let saved = without.median_ms - with.median_ms;
                println!(
                    "     saved {:.3} ms over {} pages => {:.3} ms per avoided font parse",
                    saved,
                    n_pages,
                    saved / (n_pages as f64 - 1.0)
                );
            }
            println!();
        }
    }
}

/// Separate the one-time font *parse* from the per-cache-hit `FontInfo::clone`.
/// Rendering the same page k times inside one scope costs `parse + k*clone`, so
/// the slope in k is the clone cost and the intercept is the parse cost.
#[test]
#[ignore]
fn perf_font_cache_hit_vs_parse() {
    println!("\n=== separating font PARSE cost from per-HIT clone cost ===");
    println!("  (one scope, page rendered k times: cost = parse + k*(clone+render))");
    for &nw in &[256usize, 32768] {
        let mut doc = Document::with_version("1.5");
        let prog = synth_ttf(400, 64);
        let len = prog.len() as i64;
        let ff = doc.add_object(Stream::new(dictionary! { "Length1" => len }, prog));
        let fd = doc.add_object(dictionary! {
            "Type" => "FontDescriptor", "FontName" => "BenchFont", "Flags" => 4,
            "ItalicAngle" => 0, "Ascent" => 800, "Descent" => -200,
            "CapHeight" => 700, "StemV" => 80,
            "FontBBox" => vec![0.into(), (-200).into(), 1000.into(), 1000.into()],
            "FontFile2" => ff,
        });
        let mut w: Vec<Object> = Vec::new();
        for cid in 0..nw {
            w.push(Object::Integer(cid as i64));
            w.push(Object::Array(vec![Object::Integer(500)]));
        }
        let desc = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "CIDFontType2", "BaseFont" => "BenchFont",
            "CIDSystemInfo" => dictionary! {
                "Registry" => Object::string_literal("Adobe"),
                "Ordering" => Object::string_literal("Identity"),
                "Supplement" => 0,
            },
            "FontDescriptor" => fd, "DW" => 1000, "W" => w,
        });
        let font = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type0", "BaseFont" => "BenchFont",
            "Encoding" => "Identity-H", "DescendantFonts" => vec![desc.into()],
        });
        let res = dictionary! { "Font" => dictionary! { "F1" => font } };
        let pages_id = doc.new_object_id();
        let cstream = doc.add_object(Stream::new(
            Dictionary::new(),
            b"BT\n/F1 12 Tf\n1 0 0 1 20 700 Tm\n<00410042> Tj\nET\n".to_vec(),
        ));
        let pid = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cstream, "Resources" => res,
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages", "Kids" => vec![pid.into()], "Count" => 1,
            }),
        );
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);

        let mut meds = Vec::new();
        let ks = [1usize, 41];
        for &k in &ks {
            let st = bench(9, || {
                let _scope = FontCacheScope::new();
                for _ in 0..k {
                    black_box(interpret_page(&doc, pid).unwrap());
                }
            });
            st.row(&format!("/W={} entries, k renders", nw), k, "");
            meds.push(st.median_ms);
        }
        // Also the fully-cold cost: a fresh scope per render, so every render parses.
        let cold = bench(9, || {
            for _ in 0..ks[1] {
                black_box(interpret_page(&doc, pid).unwrap());
            }
        });
        cold.row(&format!("/W={} entries, k COLD renders", nw), ks[1], "");

        let slope = (meds[1] - meds[0]) / (ks[1] - ks[0]) as f64;
        let parse_plus = meds[0];
        let cold_each = cold.median_ms / ks[1] as f64;
        println!(
            "  /W={:>6}: per-HIT (clone+render) {:.4} ms | first render (parse+render) {:.4} ms | per-COLD-render {:.4} ms",
            nw, slope, parse_plus, cold_each
        );
        println!(
            "             => cache saves ~{:.4} ms per repeat; a HIT still costs ~{:.4} ms",
            cold_each - slope, slope
        );
        println!();
    }
    println!("  A hit is not free, but the cost is no longer the clone: every FontInfo");
    println!("  collection is Arc-shared. It is `font_identity`, the cache-key check,");
    println!("  which re-hashes the resolved font dict (/W and the embedded program)");
    println!("  on every lookup.");
}

// ---------------------------------------------------------------------------
// 10. Where does the memory actually go? (the OOM mechanism)
// ---------------------------------------------------------------------------

/// `perf_primitive_cap_and_memory` shows peak heap growing with the number of
/// rects *requested* even after `MAX_PRIMITIVES` clamps the number of primitives
/// retained. That means the memory is not in the output. This splits the two
/// phases to find out where it is: parsing the content stream into a
/// `Vec<Operation>`, versus interpreting that into `Vec<Prim>`.
#[test]
#[ignore]
fn perf_memory_ops_vs_prims() {
    println!("\n=== WHERE THE MEMORY GOES: content ops vs primitives ===");
    println!("  MAX_CONTENT_OPS = {}, MAX_PRIMITIVES = {}", MAX_CONTENT_OPS, MAX_PRIMITIVES);
    println!(
        "  size_of::<Prim>() = {} B, size_of::<lopdf Operation>() = {} B",
        std::mem::size_of::<Prim>(),
        std::mem::size_of::<lopdf::content::Operation>()
    );
    println!();
    println!(
        "{:<12} {:>10} {:>12} {:>12} {:>14} {:>10} {:>10}",
        "rects", "ops", "ops ret MiB", "ops pk MiB", "full peak MiB", "prims", "prim MiB"
    );
    for &n in &[50_000usize, 200_000, 400_000] {
        let mut d0 = Document::with_version("1.5");
        let res = standard_font_res(&mut d0);
        let (doc, pages) = build_doc(&[rect_content(n)], res);

        // Phase 1 only: parse the content stream.
        mem_reset();
        let (ops, _rec) = crate::content::page_operations(&doc, pages[0]);
        let n_ops = ops.len();
        let ops_retained = mem_current();
        let ops_peak = mem_peak();
        drop(ops);

        // Both phases.
        mem_reset();
        let p = interpret_page(&doc, pages[0]).unwrap();
        let prims = p.prims.len();
        drop(p);
        let full_peak = mem_peak();

        println!(
            "{:<12} {:>10} {:>12.2} {:>12.2} {:>14.2} {:>10} {:>10.2}",
            n,
            n_ops,
            mib(ops_retained),
            mib(ops_peak),
            mib(full_peak),
            prims,
            mib(prims * std::mem::size_of::<Prim>())
        );
    }
    println!();
    println!("  If 'ops peak' dominates, the OOM happens while PARSING, before the");
    println!("  primitive cap can bound anything - which is what a document that");
    println!("  'crashes halfway' would look like.");
    println!(
        "  Worst case admitted by MAX_CONTENT_OPS alone: {} ops.",
        MAX_CONTENT_OPS
    );
}

/// Live heap bytes per parsed `Operation`, for several content-stream shapes.
///
/// A byte-based bound on the operator vector needs this constant, and it is not
/// a single number: `size_of::<Operation>()` is only 48 bytes, but each one owns
/// an operator `String` and a `Vec<Object>` of operands, so the true cost
/// depends on how many operands the operators carry and whether any are strings.
#[test]
#[ignore]
fn perf_bytes_per_content_op() {
    println!("\n=== live heap bytes per parsed Operation, by stream shape ===");
    println!(
        "  size_of::<Operation>() = {} B (the heap behind it is the real cost)",
        std::mem::size_of::<lopdf::content::Operation>()
    );
    println!();

    // (label, content bytes, note)
    let mut cases: Vec<(String, Vec<u8>)> = Vec::new();
    cases.push(("rects: 're' (4 numeric operands) + 'f'".into(), rect_content(200_000)));
    cases.push((
        "text: 'Tm' (6 operands) + 'Tj' (1 string)".into(),
        text_content(100_000),
    ));
    {
        // Zero-operand operators only: the cheapest possible op.
        let mut s = String::new();
        for _ in 0..200_000 {
            s.push_str("q\nQ\n");
        }
        cases.push(("bare 'q'/'Q' (no operands)".into(), s.into_bytes()));
    }
    {
        // Many operands per operator: a long polyline of 'l' ops.
        let mut s = String::from("0 0 m\n");
        for i in 0..200_000 {
            s.push_str(&format!("{} {} l\n", i % 600, (i * 7) % 780));
        }
        s.push_str("S\n");
        cases.push(("'l' lineto (2 numeric operands)".into(), s.into_bytes()));
    }

    println!(
        "{:<44} {:>10} {:>12} {:>12} {:>10} {:>10}",
        "stream shape", "ops", "retain MiB", "peak MiB", "B/op ret", "B/op pk"
    );
    for (label, content) in &cases {
        let mut d0 = Document::with_version("1.5");
        let res = standard_font_res(&mut d0);
        let (doc, pages) = build_doc(&[content.clone()], res);
        mem_reset();
        let (ops, _rec) = crate::content::page_operations(&doc, pages[0]);
        let n_ops = ops.len();
        // Live bytes with `ops` still held = what the phase RETAINS.
        let retained = mem_current();
        let peak = mem_peak();
        drop(ops);
        println!(
            "{:<44} {:>10} {:>12.2} {:>12.2} {:>10.0} {:>10.0}",
            label,
            n_ops,
            mib(retained),
            mib(peak),
            if n_ops > 0 { retained as f64 / n_ops as f64 } else { 0.0 },
            if n_ops > 0 { peak as f64 / n_ops as f64 } else { 0.0 }
        );
    }
    println!();
    println!("  'retain' is the operator vector itself; 'peak' includes whatever the");
    println!("  parser transiently allocated. peak >> retain means a streaming parse");
    println!("  would help even more than shrinking the vector would.");
    println!("  To bound the operator vector by BYTES, use the worst shape here, not");
    println!("  an average: a hostile stream picks the most expensive operator.");
}

// ---------------------------------------------------------------------------
// 11. Prim width and wire-buffer cost, measured on the current tree
// ---------------------------------------------------------------------------

/// `size_of::<Prim>()` as of whatever is checked out right now, plus the cost of
/// serialising a page to the wire buffer measured as an allocator delta across
/// the `wire::serialize` call alone.
#[test]
#[ignore]
fn perf_prim_width_and_wire_cost() {
    println!("\n=== Prim width, measured on the CURRENT working tree ===");
    println!("  size_of::<Prim>()      = {} B", std::mem::size_of::<Prim>());
    println!("  align_of::<Prim>()     = {} B", std::mem::align_of::<Prim>());
    println!("  size_of::<PageData>()  = {} B", std::mem::size_of::<PageData>());
    println!("  for reference: Mat = {} B, Vec<u8> = {} B, Box<[u8;256]> = {} B",
        std::mem::size_of::<Mat>(),
        std::mem::size_of::<Vec<u8>>(),
        std::mem::size_of::<Box<[u8; 256]>>(),
    );
    println!(
        "  primitive vector at MAX_PRIMITIVES = {:.2} MiB",
        mib(MAX_PRIMITIVES * std::mem::size_of::<Prim>())
    );

    println!("\n=== wire::serialize cost (allocator delta across the call alone) ===");
    println!(
        "{:<30} {:>9} {:>12} {:>12} {:>11} {:>11}",
        "page", "prims", "buf MiB", "peak MiB", "B/prim buf", "B/prim pk"
    );

    let mut cases: Vec<(String, Document, ObjectId)> = Vec::new();
    {
        let mut d0 = Document::with_version("1.5");
        let res = standard_font_res(&mut d0);
        let (doc, pages) = build_doc(&[rect_content(100_000)], res);
        cases.push(("100k rects (Fill prims)".into(), doc, pages[0]));
    }
    {
        let mut d0 = Document::with_version("1.5");
        let res = standard_font_res(&mut d0);
        let (doc, pages) = build_doc(&[text_content(4000)], res);
        cases.push(("4000 text runs (Text prims)".into(), doc, pages[0]));
    }
    {
        let (doc, pid) = shading_doc(40, (600.0, 600.0));
        cases.push(("40 square shadings (Image)".into(), doc, pid));
    }
    {
        let (doc, pid) = smask_doc(800, false);
        cases.push(("800 shapes under SMask".into(), doc, pid));
    }

    for (label, doc, pid) in &cases {
        // Build the page OUTSIDE the measured window so only serialisation counts.
        let page = interpret_page(doc, *pid).unwrap();
        let n = page.prims.len();
        mem_reset();
        let buf = crate::wire::serialize(&page);
        let buf_len = buf.len();
        let peak = mem_peak();
        drop(buf);
        println!(
            "{:<30} {:>9} {:>12.2} {:>12.2} {:>11.0} {:>11.0}",
            label,
            n,
            mib(buf_len),
            mib(peak),
            if n > 0 { buf_len as f64 / n as f64 } else { 0.0 },
            if n > 0 { peak as f64 / n as f64 } else { 0.0 }
        );
    }
    println!();
    println!("  'buf' is the returned buffer; 'peak' is the high-water mark during");
    println!("  the call, so peak > buf means serialisation transiently doubles.");
    println!("  This buffer is live at the same time as the primitive vector, so the");
    println!("  two add on the way out to Kotlin.");

    // The full pipeline: how much is live at once for one heavy page.
    let mut d0 = Document::with_version("1.5");
    let res = standard_font_res(&mut d0);
    let (doc, pages) = build_doc(&[rect_content(400_000)], res);
    mem_reset();
    let page = interpret_page(&doc, pages[0]).unwrap();
    let after_interpret = mem_current();
    let buf = crate::wire::serialize(&page);
    let both_live = mem_current();
    let pipeline_peak = mem_peak();
    println!(
        "\n  400k-rect page: after interpret {:.2} MiB live, with wire buffer also live {:.2} MiB, pipeline peak {:.2} MiB",
        mib(after_interpret),
        mib(both_live),
        mib(pipeline_peak)
    );
    drop(buf);
    drop(page);
}
