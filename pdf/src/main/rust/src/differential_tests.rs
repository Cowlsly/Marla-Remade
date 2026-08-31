//! Differential tests: invariant and metamorphic checks that do not need an
//! oracle.
//!
//! Why this module exists. Rounds 1 and 2 verified ~90 fixes by reading ISO
//! 32000-1 and asserting what we concluded. That process cannot catch a bug
//! where the code is SELF-CONSISTENT but our reading of the spec was WRONG,
//! because the tests assert the same belief the code implements — they agree
//! with us by construction. Two families of check escape that trap without
//! needing a second implementation:
//!
//! * INVARIANTS that must hold whatever the right answer is. Rendering the same
//!   page twice must produce identical primitives; rendering page N must not
//!   depend on whether page M was rendered first. These are true of every
//!   conforming renderer and of every non-conforming one, so they are immune to
//!   a mistaken spec reading. They are exactly the checks that catch leaking
//!   shared state — see the `FontCacheScope` thread-local in `fonts.rs`.
//! * METAMORPHIC relations, where a transformation of the input implies a
//!   predictable transformation of the output. We need not know where a mark
//!   belongs on a `/Rotate 90` page to know that turning the page four times
//!   must put it back where it started, or that prepending a `cm` translation
//!   must move it by exactly that vector.
//!
//! A reference renderer was investigated and none was usable here; see the
//! module-level note in the report. These checks are what remains valuable
//! regardless, and were wanted even if a reference had been found.
//!
//! House style follows `golden_tests.rs`: build with `lopdf`, run the real
//! pipeline. Unlike `golden_tests.rs` this module deliberately compares EXACT
//! fingerprints rather than meaning, because the property under test is
//! bit-level reproducibility — a "close enough" comparison would let precisely
//! the cache-staleness bugs being hunted slip through.

use crate::*;
use lopdf::content::{Content, Operation};
use lopdf::{dictionary, Object, Stream};

// ---------------------------------------------------------------------------
// Exact fingerprinting of a rendered page
//
// `Prim` deliberately derives nothing — not `Debug`, not `PartialEq` — so the
// determinism checks need their own total projection. Every field of every
// variant is matched by name with no `..` rest pattern, which means adding a
// field to `Prim` breaks THIS FILE at compile time. That is intentional: a new
// field silently excluded from the fingerprint would quietly weaken every test
// below into a vacuous pass.

fn fnv1a(bytes: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for &b in bytes {
        h ^= b as u64;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}

/// Floats go in as raw bits, never as formatted decimals. Two renders that
/// differ by one ULP are a determinism bug, and `{:?}` would round them into
/// agreement and hide it. Bit equality also distinguishes `0.0` from `-0.0`.
fn f32b(v: f32) -> String {
    format!("{:08x}", v.to_bits())
}

fn f64b(v: f64) -> String {
    format!("{:016x}", v.to_bits())
}

fn fp_mat(m: &Mat) -> String {
    m.iter().map(|v| f64b(*v)).collect::<Vec<_>>().join(",")
}

fn fp_pts(pts: &[(f32, f32)]) -> String {
    let mut s = String::with_capacity(pts.len() * 18);
    for (x, y) in pts {
        s.push_str(&f32b(*x));
        s.push(':');
        s.push_str(&f32b(*y));
        s.push(';');
    }
    s
}

fn fp_path_ops(ops: &Option<Vec<PathOp>>) -> String {
    match ops {
        None => "none".to_string(),
        Some(v) => v
            .iter()
            .map(|o| match o {
                PathOp::Move(a, b) => format!("M{}:{}", f32b(*a), f32b(*b)),
                PathOp::Line(a, b) => format!("L{}:{}", f32b(*a), f32b(*b)),
                PathOp::Cubic(a, b, c, d, e, f) => format!(
                    "C{}:{}:{}:{}:{}:{}",
                    f32b(*a),
                    f32b(*b),
                    f32b(*c),
                    f32b(*d),
                    f32b(*e),
                    f32b(*f)
                ),
                PathOp::Close => "Z".to_string(),
            })
            .collect::<Vec<_>>()
            .join(","),
    }
}

/// A total, exact projection of one primitive to a string.
///
/// Bulk pixel payloads are hashed rather than inlined — a page with a 4 MB
/// image would otherwise produce an 8 MB fingerprint per render — but the hash
/// covers every byte, so a single differing pixel still changes the result.
fn fp_prim(p: &Prim) -> String {
    match p {
        Prim::Text {
            x,
            y,
            size,
            argb,
            text,
            stroke_argb,
            stroke_width,
            advance,
            render_mode,
            blend,
            is_bold,
            is_italic,
            font_family,
            outline,
            h_scale,
        } => format!(
            "Text|{}|{}|{}|{:08x}|{}|{:?}|{:?}|{}|{}|{}|{}|{}|{}|{}|{}",
            f32b(*x),
            f32b(*y),
            f32b(*size),
            argb,
            text.escape_debug(),
            stroke_argb,
            stroke_width.map(f32b),
            f32b(*advance),
            render_mode,
            *blend as u8,
            is_bold,
            is_italic,
            font_family,
            outline,
            f32b(*h_scale)
        ),
        Prim::Fill { argb, even_odd, contours, blend } => format!(
            "Fill|{:08x}|{}|{}|{}",
            argb,
            even_odd,
            contours.iter().map(|c| fp_pts(c)).collect::<Vec<_>>().join("/"),
            *blend as u8
        ),
        Prim::Stroke { argb, width, dash, dash_phase, cap, join, miter, pts, blend } => format!(
            "Stroke|{:08x}|{}|{}|{}|{}|{}|{}|{}|{}",
            argb,
            f32b(*width),
            dash.iter().map(|d| f32b(*d)).collect::<Vec<_>>().join(","),
            f32b(*dash_phase),
            cap,
            join,
            f32b(*miter),
            fp_pts(pts),
            *blend as u8
        ),
        Prim::Image { ctm, w, h, format, data, alpha, blend } => format!(
            "Image|{}|{}|{}|{}|{}|{:016x}|{}|{}",
            fp_mat(ctm),
            w,
            h,
            format,
            data.len(),
            fnv1a(data),
            f32b(*alpha),
            *blend as u8
        ),
        Prim::ImageTiled {
            ctm,
            w,
            h,
            data,
            xstep,
            ystep,
            i0,
            j0,
            nx,
            ny,
            alpha,
            blend,
        } => format!(
            "ImageTiled|{}|{}|{}|{}|{:016x}|{}|{}|{}|{}|{}|{}|{}|{}",
            fp_mat(ctm),
            w,
            h,
            data.len(),
            fnv1a(data),
            f32b(*xstep),
            f32b(*ystep),
            i0,
            j0,
            nx,
            ny,
            f32b(*alpha),
            *blend as u8
        ),
        Prim::ClipPush { even_odd, pts, path_ops } => {
            format!("ClipPush|{}|{}|{}", even_odd, fp_pts(pts), fp_path_ops(path_ops))
        }
        Prim::ClipPop => "ClipPop".to_string(),
        Prim::TextClipApply => "TextClipApply".to_string(),
        Prim::GroupPush { isolated, knockout, alpha, blend } => format!(
            "GroupPush|{}|{}|{}|{}",
            isolated,
            knockout,
            f32b(*alpha),
            *blend as u8
        ),
        Prim::GroupPop => "GroupPop".to_string(),
        Prim::SoftMaskPush { mask_type } => format!("SoftMaskPush|{}", mask_type),
        Prim::SoftMaskTransfer(lut) => format!("SoftMaskTransfer|{:016x}", fnv1a(&lut[..])),
        Prim::SoftMaskContent => "SoftMaskContent".to_string(),
        Prim::SoftMaskPop => "SoftMaskPop".to_string(),
    }
}

/// Order-sensitive fingerprint of a whole rendered page, dimensions included.
/// Order matters because primitive order IS the paint order; a renderer that
/// emitted the same set in a different sequence would draw a different page.
fn fingerprint(page: &PageData) -> String {
    let mut s = format!("{}x{}\n", f32b(page.width), f32b(page.height));
    for (i, p) in page.prims.iter().enumerate() {
        s.push_str(&format!("{i}:{}\n", fp_prim(p)));
    }
    s
}

/// Where the fingerprints first diverge, for a failure message that names the
/// offending primitive instead of dumping two multi-kilobyte blobs.
fn first_difference(a: &str, b: &str) -> String {
    let (mut la, mut lb) = (a.lines(), b.lines());
    let mut n = 0;
    loop {
        match (la.next(), lb.next()) {
            (None, None) => return "identical".to_string(),
            (x, y) if x == y => n += 1,
            (x, y) => return format!("line {n}:\n  first:  {x:?}\n  second: {y:?}"),
        }
    }
}

// ---------------------------------------------------------------------------
// Fixture builders (mirrors of the `golden_tests.rs` helpers, which are private
// to that module)

fn assemble_with_contents(
    doc: &mut Document,
    contents: Object,
    resources: Dictionary,
    page: &mut Dictionary,
    catalog: Dictionary,
) -> ObjectId {
    let pages_id = doc.new_object_id();
    page.set("Type", Object::Name(b"Page".to_vec()));
    page.set("Parent", pages_id);
    page.set("Resources", resources);
    if !matches!(contents, Object::Null) {
        page.set("Contents", contents);
    }
    if page.get(b"MediaBox").is_err() {
        page.set("MediaBox", vec![0.into(), 0.into(), 612.into(), 792.into()]);
    }
    let page_id = doc.add_object(page.clone());
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }),
    );
    let mut cat = catalog;
    cat.set("Type", Object::Name(b"Catalog".to_vec()));
    cat.set("Pages", pages_id);
    let catalog_id = doc.add_object(cat);
    doc.trailer.set("Root", catalog_id);
    page_id
}

fn assemble(
    doc: &mut Document,
    content: Vec<u8>,
    resources: Dictionary,
    mut page: Dictionary,
) -> ObjectId {
    let content_id = doc.add_object(Stream::new(dictionary! {}, content));
    assemble_with_contents(
        doc,
        Object::Reference(content_id),
        resources,
        &mut page,
        dictionary! {},
    )
}

fn page_from_ops(doc: &mut Document, ops: Vec<Operation>, resources: Dictionary) -> ObjectId {
    let bytes = Content { operations: ops }.encode().unwrap();
    assemble(doc, bytes, resources, dictionary! {})
}

/// Build a many-page document. `per_page` supplies the operator list and the
/// resource dictionary for page `i`, so callers can give each page a distinct
/// font object — the arrangement that would expose a font cache keyed on the
/// resource NAME rather than the font object's id.
fn multi_page_doc<F>(n: usize, mut per_page: F) -> (Document, Vec<ObjectId>)
where
    F: FnMut(&mut Document, usize) -> (Vec<Operation>, Dictionary),
{
    let mut doc = Document::with_version("1.5");
    let pages_id = doc.new_object_id();
    let mut page_ids = Vec::new();
    for i in 0..n {
        let (ops, resources) = per_page(&mut doc, i);
        let content_id =
            doc.add_object(Stream::new(dictionary! {}, Content { operations: ops }.encode().unwrap()));
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 300.into(), 200.into()],
            "Contents" => content_id,
            "Resources" => resources,
        });
        page_ids.push(page_id);
    }
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages",
            "Kids" => page_ids.iter().map(|id| Object::Reference(*id)).collect::<Vec<_>>(),
            "Count" => n as i64,
        }),
    );
    let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", catalog_id);
    (doc, page_ids)
}

/// A page touching as many subsystems as one fixture reasonably can: a filled
/// path, a dashed stroke, text through a simple font, a Form XObject, an image
/// XObject, an axial shading, and an ExtGState luminosity soft mask.
///
/// Breadth is the point. A determinism check over a single filled rectangle
/// would pass even if the font cache were badly broken, so the fixture has to
/// reach the caches, the shading sampler and the mask bracketing code.
fn rich_page(doc: &mut Document) -> ObjectId {
    let font_id = doc.add_object(dictionary! {
        "Type" => "Font", "Subtype" => "Type1",
        "BaseFont" => "Helvetica", "Encoding" => "WinAnsiEncoding",
    });

    let form_ops = Content {
        operations: vec![
            Operation::new("0 1 0 rg", vec![]),
            Operation::new("re", vec![0.into(), 0.into(), 20.into(), 20.into()]),
            Operation::new("f", vec![]),
        ],
    }
    .encode()
    .unwrap();
    let form_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 20.into(), 20.into()],
        },
        form_ops,
    ));

    // 2x2 DeviceRGB image.
    let img_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 2, "Height" => 2,
            "ColorSpace" => "DeviceRGB", "BitsPerComponent" => 8,
        },
        vec![255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 0],
    ));

    // Luminosity soft-mask group.
    let mask_ops = Content {
        operations: vec![
            Operation::new("0.5 g", vec![]),
            Operation::new("re", vec![0.into(), 0.into(), 100.into(), 100.into()]),
            Operation::new("f", vec![]),
        ],
    }
    .encode()
    .unwrap();
    let mask_group_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            "Group" => dictionary! {
                "S" => "Transparency", "CS" => "DeviceGray", "I" => true,
            },
        },
        mask_ops,
    ));
    let gs_id = doc.add_object(dictionary! {
        "Type" => "ExtGState",
        "SMask" => dictionary! { "S" => "Luminosity", "G" => mask_group_id },
    });

    let shading = dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => "DeviceRGB",
        "Coords" => vec![0.into(), 0.into(), 120.into(), 0.into()],
        "Function" => dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![1.into(), 0.into(), 0.into()],
            "C1" => vec![0.into(), 0.into(), 1.into()],
            "N" => 1,
        },
    };

    let resources = dictionary! {
        "Font" => dictionary! { "F1" => font_id },
        "XObject" => dictionary! { "Fm0" => form_id, "Im0" => img_id },
        "ExtGState" => dictionary! { "GS0" => gs_id },
        "Shading" => dictionary! { "Sh0" => shading },
    };

    let ops = vec![
        // Filled path.
        Operation::new("rg", vec![0.9.into(), 0.1.into(), 0.2.into()]),
        Operation::new("re", vec![10.into(), 10.into(), 60.into(), 40.into()]),
        Operation::new("f", vec![]),
        // Dashed stroke with a bezier.
        Operation::new("RG", vec![0.into(), 0.into(), 1.into()]),
        Operation::new("w", vec![3.into()]),
        Operation::new("d", vec![vec![4.into(), 2.into()].into(), 1.into()]),
        Operation::new("m", vec![10.into(), 120.into()]),
        Operation::new("c", vec![40.into(), 160.into(), 80.into(), 80.into(), 120.into(), 120.into()]),
        Operation::new("S", vec![]),
        // Text.
        Operation::new("BT", vec![]),
        Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 14.into()]),
        Operation::new("Td", vec![20.into(), 70.into()]),
        Operation::new("Tj", vec![Object::string_literal("Diff Wg")]),
        Operation::new("ET", vec![]),
        // Form XObject under a translation.
        Operation::new("q", vec![]),
        Operation::new("cm", vec![1.into(), 0.into(), 0.into(), 1.into(), 200.into(), 20.into()]),
        Operation::new("Do", vec![Object::Name(b"Fm0".to_vec())]),
        Operation::new("Q", vec![]),
        // Image XObject.
        Operation::new("q", vec![]),
        Operation::new("cm", vec![40.into(), 0.into(), 0.into(), 40.into(), 220.into(), 90.into()]),
        Operation::new("Do", vec![Object::Name(b"Im0".to_vec())]),
        Operation::new("Q", vec![]),
        // Soft-masked shading inside a clip.
        Operation::new("q", vec![]),
        Operation::new("gs", vec![Object::Name(b"GS0".to_vec())]),
        Operation::new("re", vec![100.into(), 150.into(), 120.into(), 40.into()]),
        Operation::new("W", vec![]),
        Operation::new("n", vec![]),
        Operation::new("sh", vec![Object::Name(b"Sh0".to_vec())]),
        Operation::new("Q", vec![]),
    ];

    let bytes = Content { operations: ops }.encode().unwrap();
    assemble(
        doc,
        bytes,
        resources,
        dictionary! { "MediaBox" => vec![0.into(), 0.into(), 300.into(), 200.into()] },
    )
}

// ---------------------------------------------------------------------------
// Invariant: rendering is a pure function of (document, page)

/// Rendering the same page repeatedly must produce byte-identical primitives.
///
/// This is the primary guard on the `FontCacheScope` thread-local in
/// `fonts.rs`: the cache is installed and torn down inside `interpret_page`, so
/// the second render must repopulate it from scratch and reach exactly the same
/// answer. A cache that returned a partly-initialised or mutated `FontInfo` on
/// a hit, or a sampler that accumulated state in a static, shows up here as a
/// divergence. Ten repeats rather than two so an alternating (even/odd) fault
/// cannot hide.
#[test]
fn same_page_rendered_repeatedly_is_identical() {
    let mut doc = Document::with_version("1.5");
    let page_id = rich_page(&mut doc);

    let baseline = fingerprint(&interpret_page(&doc, page_id).expect("render"));
    assert!(
        baseline.lines().count() > 8,
        "fixture must emit a substantial number of primitives, else this test is vacuous; got:\n{baseline}"
    );

    for i in 1..10 {
        let again = fingerprint(&interpret_page(&doc, page_id).expect("render"));
        assert_eq!(
            baseline,
            again,
            "render {i} diverged from render 0: {}",
            first_difference(&baseline, &again)
        );
    }
}

/// The same invariant one level up, through the handle API and the wire
/// serializer, so a non-determinism introduced by `wire::serialize` (map
/// iteration order, uninitialised padding) is caught too.
#[test]
fn wire_bytes_are_identical_across_renders() {
    let mut doc = Document::with_version("1.5");
    rich_page(&mut doc);
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();

    let handle = open_document(&bytes);
    assert_ne!(handle, 0, "fixture must open");
    let first = render_page(handle, 0).expect("render 0");
    assert!(first.len() > 64, "wire buffer suspiciously small: {}", first.len());
    for i in 1..5 {
        let again = render_page(handle, 0).expect("render again");
        assert_eq!(first.len(), again.len(), "wire length changed on render {i}");
        assert!(first == again, "wire bytes changed on render {i}");
    }
    close_document(handle);
}

/// Two independent `Document`s parsed from the same bytes must render
/// identically. Distinguishes a genuinely pure pipeline from one that happens
/// to be stable only because it keeps hitting the same warm cache: here the
/// second document has different object addresses and a cold cache, so any
/// dependence on either would surface.
#[test]
fn two_documents_from_the_same_bytes_agree() {
    let mut doc = Document::with_version("1.5");
    rich_page(&mut doc);
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();

    let a = load_document_lenient(&bytes).expect("load a");
    let b = load_document_lenient(&bytes).expect("load b");
    let pa = nth_page_id(&a, 0).expect("page a");
    let pb = nth_page_id(&b, 0).expect("page b");

    let fa = fingerprint(&interpret_page(&a, pa).expect("render a"));
    let fb = fingerprint(&interpret_page(&b, pb).expect("render b"));
    assert_eq!(fa, fb, "reparsed document differs: {}", first_difference(&fa, &fb));
}

// ---------------------------------------------------------------------------
// Invariant: page independence

/// A four-page document where every page has its OWN font object bound to the
/// SAME resource name `/F1`, and draws different text.
///
/// This is the adversarial shape for a font cache: if the cache key were the
/// resource name, or the page's resource dictionary, page 1 would be served
/// page 0's font. It is keyed on `(document address, font ObjectId)`
/// (`fonts.rs:220`), which should be immune — this fixture is what proves it
/// rather than assuming it.
fn distinct_font_per_page_doc() -> (Document, Vec<ObjectId>) {
    const FACES: [&str; 4] = ["Helvetica", "Courier", "Times-Roman", "Helvetica-Bold"];
    multi_page_doc(4, |doc, i| {
        let font_id = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type1",
            "BaseFont" => FACES[i], "Encoding" => "WinAnsiEncoding",
        });
        let ops = vec![
            Operation::new("BT", vec![]),
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), (12 + i as i64).into()]),
            Operation::new("Td", vec![20.into(), (30 + 25 * i as i64).into()]),
            Operation::new("Tj", vec![Object::string_literal(format!("page{i} WAV"))]),
            Operation::new("ET", vec![]),
            Operation::new("rg", vec![(0.2 * i as f64).into(), 0.4.into(), 0.6.into()]),
            Operation::new("re", vec![(10 * i as i64).into(), 5.into(), 30.into(), 12.into()]),
            Operation::new("f", vec![]),
        ];
        (ops, dictionary! { "Font" => dictionary! { "F1" => font_id } })
    })
}

/// Rendering page N must not depend on which pages were rendered before it.
///
/// Checked against three orders: forward, reverse, and each page rendered in
/// isolation by a freshly reparsed document. The isolated pass is the real
/// control — it is the only one where no other page has ever been touched, so
/// agreement with it means "no page leaked into any other".
#[test]
fn page_rendering_is_independent_of_order() {
    let (mut doc, page_ids) = distinct_font_per_page_doc();
    let n = page_ids.len();

    let forward: Vec<String> = (0..n)
        .map(|i| fingerprint(&interpret_page(&doc, page_ids[i]).expect("fwd")))
        .collect();

    let mut reverse = vec![String::new(); n];
    for i in (0..n).rev() {
        reverse[i] = fingerprint(&interpret_page(&doc, page_ids[i]).expect("rev"));
    }

    // Isolated: a brand-new Document per page, so nothing else has been rendered.
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();
    let isolated: Vec<String> = (0..n)
        .map(|i| {
            let fresh = load_document_lenient(&bytes).expect("reload");
            let pid = nth_page_id(&fresh, i as i32).expect("page");
            fingerprint(&interpret_page(&fresh, pid).expect("iso"))
        })
        .collect();

    for i in 0..n {
        assert_eq!(
            forward[i], reverse[i],
            "page {i} depends on render order: {}",
            first_difference(&forward[i], &reverse[i])
        );
        assert_eq!(
            forward[i], isolated[i],
            "page {i} differs when rendered alone, i.e. another page leaked into it: {}",
            first_difference(&forward[i], &isolated[i])
        );
    }

    // Guard against a vacuous pass: the pages must actually differ from each
    // other, or "order does not matter" would be trivially true.
    for i in 1..n {
        assert_ne!(forward[0], forward[i], "fixture pages 0 and {i} are indistinguishable");
    }
}

/// The narrow form of the same property, stated the way a bug report would be:
/// render page 3 first, then render it again after rendering pages 0..3.
#[test]
fn rendering_a_page_first_or_last_agrees() {
    let (doc, page_ids) = distinct_font_per_page_doc();
    let last = *page_ids.last().unwrap();

    let alone = fingerprint(&interpret_page(&doc, last).expect("alone"));
    for pid in &page_ids[..page_ids.len() - 1] {
        let _ = interpret_page(&doc, *pid).expect("warm the caches");
    }
    let after = fingerprint(&interpret_page(&doc, last).expect("after"));
    assert_eq!(
        alone, after,
        "rendering earlier pages changed this page: {}",
        first_difference(&alone, &after)
    );
}

/// The text index is built for every page under ONE `FontCacheScope`
/// (`search.rs:49`), unlike rendering which scopes per page. Building it twice
/// must agree, and it must agree with a build from a reparsed document.
#[test]
fn text_index_is_reproducible() {
    let (mut doc, _) = distinct_font_per_page_doc();
    let first: Vec<String> = crate::search::build_index(&doc).iter().map(|p| p.text.clone()).collect();
    let second: Vec<String> = crate::search::build_index(&doc).iter().map(|p| p.text.clone()).collect();
    assert_eq!(first, second, "build_index is not deterministic");

    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();
    let fresh = load_document_lenient(&bytes).expect("reload");
    let third: Vec<String> = crate::search::build_index(&fresh).iter().map(|p| p.text.clone()).collect();
    assert_eq!(first, third, "build_index differs on a reparsed document");

    assert!(
        first.iter().any(|t| !t.trim().is_empty()),
        "fixture produced no indexed text, so this test would be vacuous: {first:?}"
    );
}

/// Directly probes the documented weak point of the font cache key.
///
/// The key is `(doc as *const Document as usize, font ObjectId)`
/// (`fonts.rs:220`), and `fonts.rs:169` argues that is safe because a
/// `FontCacheScope` is short-lived and never spans two documents. Address
/// reuse is normally hard to force, so this test removes the chance element:
/// it renders document A through a boxed pointer, then overwrites THAT SAME
/// BOX in place with document B. B is therefore guaranteed to live at the exact
/// address A did, with colliding object ids, while an outer scope is still
/// open.
///
/// If the cache leaks, B's `/F1` resolves to A's cached font and B renders with
/// the wrong face.
///
/// HISTORY. This test originally FAILED and was `#[ignore]`d as a documented
/// tripwire: the key was `(doc as *const Document as usize, font ObjectId)`, and
/// document B (Courier, monospaced) rendered with document A's Helvetica metrics
/// — advances 19.99/6.67/5.33 for `M`/space/`i`, which are 833/278/222 per mille
/// at 24pt, instead of Courier's uniform 14.4 — with `font_family` flipping from
/// 2 (mono) to 0 (sans). `residuals` then removed the raw pointer from the key
/// entirely: it is now the `ObjectId` alone, and a hit additionally requires the
/// stored font dictionary to still equal the one that id resolves to. The test
/// passes and is enabled.
///
/// It is kept because the reproduction is the cheap part and the guarantee is
/// worth pinning: an address is not a stable document identity, and nothing else
/// in the suite enforces that.
#[test]
fn font_cache_does_not_leak_between_documents_at_one_address() {
    // Two documents, same object numbering, deliberately different faces.
    let build = |face: &str, label: &str| {
        let mut doc = Document::with_version("1.5");
        let font_id = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type1",
            "BaseFont" => face, "Encoding" => "WinAnsiEncoding",
        });
        let ops = vec![
            Operation::new("BT", vec![]),
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 24.into()]),
            Operation::new("Td", vec![10.into(), 40.into()]),
            Operation::new("Tj", vec![Object::string_literal(label)]),
            Operation::new("ET", vec![]),
        ];
        let page_id = page_from_ops(&mut doc, ops, dictionary! {
            "Font" => dictionary! { "F1" => font_id }
        });
        (doc, page_id)
    };

    // Control renders, each with its own scope, no sharing possible.
    let (doc_a, pa) = build("Helvetica", "MMM iii");
    let (doc_b, pb) = build("Courier", "MMM iii");
    let want_a = fingerprint(&interpret_page(&doc_a, pa).expect("a"));
    let want_b = fingerprint(&interpret_page(&doc_b, pb).expect("b"));
    // Courier is monospaced and Helvetica is not, so "MMM iii" must advance
    // differently. Without this the test could pass by both being wrong.
    assert_ne!(
        want_a, want_b,
        "fixture fonts are indistinguishable; pick faces with different metrics"
    );
    drop(doc_a);
    drop(doc_b);

    // Now the adversarial arrangement: one outer scope spanning both documents,
    // with B forced to occupy A's address.
    let (got_a, got_b) = {
        let _outer = crate::FontCacheScope::new();

        let (doc_a2, pa2) = build("Helvetica", "MMM iii");
        let mut slot = Box::new(doc_a2);
        let addr_a = &*slot as *const Document as usize;
        let got_a = fingerprint(&interpret_page(&slot, pa2).expect("a2"));

        let (doc_b2, pb2) = build("Courier", "MMM iii");
        *slot = doc_b2; // same heap address, different document
        let addr_b = &*slot as *const Document as usize;
        assert_eq!(
            addr_a, addr_b,
            "in-place overwrite failed to reuse the address; test cannot conclude"
        );
        let got_b = fingerprint(&interpret_page(&slot, pb2).expect("b2"));
        (got_a, got_b)
    };

    assert_eq!(got_a, want_a, "document A rendered differently under an outer cache scope");
    assert_eq!(
        got_b, want_b,
        "STALE FONT CACHE: document B at document A's address was served A's cached font. {}",
        first_difference(&want_b, &got_b)
    );
}

// ---------------------------------------------------------------------------
// Metamorphic: page rotation

/// The font cache key requires the STORED font dictionary to still equal the one
/// the object id resolves to now (`fonts.rs`, `cached_font`). That kills the
/// address-reuse collision above, but `Dictionary` equality is SHALLOW: a font
/// dictionary carries indirect references (`/Widths`, `/FontDescriptor`,
/// `/ToUnicode`, `/DescendantFonts`), and comparing them compares object
/// NUMBERS, not the objects they resolve to.
///
/// So two documents can hold a byte-identical font dictionary at the same object
/// id whose referenced metrics differ completely. This fixture is that case,
/// reduced to its simplest form: both documents allocate objects in the same
/// order, so both have the font at the same id with a dictionary naming
/// `/Widths 1 0 R`; only the CONTENTS of object 1 differ (every glyph 500/1000
/// vs 900/1000). Nothing about the dictionaries distinguishes them.
///
/// Same precondition as the address-reuse case — it needs one scope spanning two
/// documents, which no current caller does — so this is latent too. It is here
/// because "a colliding object id from another document cannot be served" is
/// slightly stronger than what shallow dictionary equality actually buys, and
/// that gap should be written down rather than rediscovered.
///
/// IGNORED, and it FAILS when run, exactly as its predecessor did before the fix.
/// Verified 2026-08-29 against the `ObjectId` + stored-dictionary key: the
/// 900/1000 document was served the 500/1000 document's metrics, giving an
/// advance of 10.0 (= 500/1000 x 20pt) where 18.0 (= 900/1000 x 20pt) is correct,
/// and glyph origins at 10/20/30 instead of 10/28/46. Ignored because no caller
/// can reach it, NOT because the finding is doubtful. Routed to `residuals`.
///
/// A deep fix does not need a deep dictionary compare: including the resolved
/// generation of each indirect target would cost as much as the parse being
/// cached. Cheaper is to scope the cache to a document identity that cannot be
/// recycled — a monotonic id minted per `Document` load — which closes the class
/// rather than another instance of it.
#[ignore = "latent, not live: stale hit through shallow /Widths dictionary equality, unreachable \
            by any current caller; see the doc comment and the note routed to residuals"]
#[test]
fn font_cache_distinguishes_documents_whose_font_dicts_are_equal_but_indirect_targets_differ() {
    let build = |width: i64| {
        let mut doc = Document::with_version("1.5");
        // Object 1: the widths array. Same id in both documents, different values.
        let widths: Vec<Object> = (32..=122).map(|_| width.into()).collect();
        let widths_id = doc.add_object(Object::Array(widths));
        // Object 2: the font dictionary. Byte-identical in both documents.
        let font_id = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type1",
            "BaseFont" => "Helvetica", "Encoding" => "WinAnsiEncoding",
            "FirstChar" => 32, "LastChar" => 122,
            "Widths" => widths_id,
        });
        let ops = vec![
            Operation::new("BT", vec![]),
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 20.into()]),
            Operation::new("Td", vec![10.into(), 40.into()]),
            Operation::new("Tj", vec![Object::string_literal("iii")]),
            Operation::new("ET", vec![]),
        ];
        let page_id = page_from_ops(&mut doc, ops, dictionary! {
            "Font" => dictionary! { "F1" => font_id }
        });
        (doc, page_id, font_id)
    };

    let (narrow, np, nf) = build(500);
    let (wide, wp, wf) = build(900);
    assert_eq!(nf, wf, "fixture must place the font at the same object id in both documents");
    assert_eq!(
        narrow.get_object(nf).unwrap().as_dict().unwrap(),
        wide.get_object(wf).unwrap().as_dict().unwrap(),
        "fixture must give both documents an EQUAL font dictionary, else it proves nothing"
    );

    let want_narrow = fingerprint(&interpret_page(&narrow, np).expect("narrow"));
    let want_wide = fingerprint(&interpret_page(&wide, wp).expect("wide"));
    assert_ne!(
        want_narrow, want_wide,
        "/Widths is not reaching the advances, so this test cannot detect a stale hit"
    );

    // One scope spanning both documents: the arrangement a cache hit needs.
    let (got_narrow, got_wide) = {
        let _outer = crate::FontCacheScope::new();
        let a = fingerprint(&interpret_page(&narrow, np).expect("narrow scoped"));
        let b = fingerprint(&interpret_page(&wide, wp).expect("wide scoped"));
        (a, b)
    };

    assert_eq!(got_narrow, want_narrow, "first document changed under a shared cache scope");
    assert_eq!(
        got_wide, want_wide,
        "STALE FONT CACHE via shallow dictionary equality: the second document's font \
         resolves to different /Widths through an identical dictionary, but was served \
         the first document's parsed metrics. {}",
        first_difference(&want_wide, &got_wide)
    );
}

/// The live counterpart of the ignored test above: the SAME address-reuse
/// arrangement, but without an outer `FontCacheScope` — which is exactly how
/// production calls in. `interpret_page` opens and drops its own scope per call
/// (`interpret.rs:113`), so the cache must be empty on entry and document B must
/// render with its own font despite sitting at document A's address.
///
/// This is the property that currently protects us, and it was previously
/// implicit. Pinning it means the protection cannot be removed silently: if
/// someone hoists the scope out of `interpret_page` for a performance win, this
/// test goes red rather than the bug shipping.
#[test]
fn font_cache_is_scoped_per_render_so_address_reuse_is_safe() {
    let build = |face: &str| {
        let mut doc = Document::with_version("1.5");
        let font_id = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type1",
            "BaseFont" => face, "Encoding" => "WinAnsiEncoding",
        });
        let ops = vec![
            Operation::new("BT", vec![]),
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 24.into()]),
            Operation::new("Td", vec![10.into(), 40.into()]),
            Operation::new("Tj", vec![Object::string_literal("MMM iii")]),
            Operation::new("ET", vec![]),
        ];
        let page_id = page_from_ops(&mut doc, ops, dictionary! {
            "Font" => dictionary! { "F1" => font_id }
        });
        (doc, page_id)
    };

    let (ctl_a, ca) = build("Helvetica");
    let (ctl_b, cb) = build("Courier");
    let want_a = fingerprint(&interpret_page(&ctl_a, ca).expect("ctl a"));
    let want_b = fingerprint(&interpret_page(&ctl_b, cb).expect("ctl b"));
    assert_ne!(want_a, want_b, "fixture fonts must be distinguishable");
    drop(ctl_a);
    drop(ctl_b);

    // No outer scope here: this is the production arrangement.
    let (doc_a, pa) = build("Helvetica");
    let mut slot = Box::new(doc_a);
    let addr_a = &*slot as *const Document as usize;
    let got_a = fingerprint(&interpret_page(&slot, pa).expect("a"));

    let (doc_b, pb) = build("Courier");
    *slot = doc_b;
    assert_eq!(
        addr_a,
        &*slot as *const Document as usize,
        "in-place overwrite failed to reuse the address; test cannot conclude"
    );
    let got_b = fingerprint(&interpret_page(&slot, pb).expect("b"));

    assert_eq!(got_a, want_a, "document A rendered differently after boxing");
    assert_eq!(
        got_b, want_b,
        "per-render font cache scoping is broken: document B at A's address was \
         served A's font even with no outer scope. {}",
        first_difference(&want_b, &got_b)
    );
}

/// One quarter turn, as documented at `geometry.rs:144`: `(x,y) -> (y, w-x)`,
/// and the page's width/height swap. Returns the mapped point and the new
/// dimensions so the map can be composed.
fn quarter_turn(p: (f64, f64), w: f64, h: f64) -> ((f64, f64), (f64, f64)) {
    ((p.1, w - p.0), (h, w))
}

/// Four quarter turns are the identity on both the point and the dimensions.
///
/// Pure algebra on the documented map, kept as a test because it is the
/// premise every rotation assertion below rests on: if the composition were
/// not the identity, the documented maps could not all describe the same
/// rotation and one of the arms in `page_base_matrix` would have to be wrong.
#[test]
fn four_quarter_turns_compose_to_the_identity() {
    let (w0, h0) = (300.0_f64, 200.0_f64);
    let p0 = (37.0_f64, 91.0_f64);

    let (mut p, mut d) = (p0, (w0, h0));
    let mut seen = Vec::new();
    for _ in 0..4 {
        let (np, nd) = quarter_turn(p, d.0, d.1);
        p = np;
        d = nd;
        seen.push((p, d));
    }

    // Intermediate turns must match the other two documented maps.
    assert_eq!(seen[1].0, (w0 - p0.0, h0 - p0.1), "two turns must equal the 180 map");
    assert_eq!(seen[2].0, (h0 - p0.1, p0.0), "three turns must equal the 270 map");
    assert_eq!(seen[3].0, p0, "four turns must restore the point");
    assert_eq!(seen[3].1, (w0, h0), "four turns must restore the dimensions");
}

/// Marker rectangle and page used by the rotation tests. Asymmetric in both the
/// page (300x200) and the mark's placement, so a swapped 90/270 arm — the
/// regression behind issue #321 — cannot coincidentally still line up.
fn rotated_marker_doc(rotate: Option<i64>) -> (Document, ObjectId) {
    let mut doc = Document::with_version("1.5");
    let mut page = dictionary! { "MediaBox" => vec![0.into(), 0.into(), 300.into(), 200.into()] };
    if let Some(r) = rotate {
        page.set("Rotate", r);
    }
    let bytes = Content {
        operations: vec![
            Operation::new("rg", vec![1.into(), 0.into(), 0.into()]),
            Operation::new("re", vec![20.into(), 30.into(), 40.into(), 25.into()]),
            Operation::new("f", vec![]),
        ],
    }
    .encode()
    .unwrap();
    let page_id = assemble(&mut doc, bytes, dictionary! {}, page);
    (doc, page_id)
}

/// The bounding box of the single filled marker.
fn marker_box(page: &PageData) -> [f64; 4] {
    let mut b = [f64::MAX, f64::MAX, f64::MIN, f64::MIN];
    let mut found = false;
    for p in &page.prims {
        if let Prim::Fill { contours, .. } = p {
            for pt in contours.iter().flatten() {
                b[0] = b[0].min(pt.0 as f64);
                b[1] = b[1].min(pt.1 as f64);
                b[2] = b[2].max(pt.0 as f64);
                b[3] = b[3].max(pt.1 as f64);
                found = true;
            }
        }
    }
    assert!(found, "fixture emitted no Fill primitive to measure");
    b
}

fn boxes_close(a: [f64; 4], b: [f64; 4]) -> bool {
    // Generous relative to page size but far tighter than any real rotation
    // error, which displaces the mark by tens of points.
    a.iter().zip(b.iter()).all(|(x, y)| (x - y).abs() < 0.01)
}

/// Rotating the page through the four quarter turns moves the mark exactly as
/// the documented maps predict, and the fourth turn brings it home.
///
/// This is the check that would have caught issue #321 without knowing which
/// way round PDF rotation goes: it never asserts where the mark "should" be,
/// only that each successive turn is one application of the same map as the
/// previous, and that four of them are the identity.
#[test]
fn rotating_a_page_four_quarter_turns_returns_to_the_original() {
    let (d0, p0) = rotated_marker_doc(None);
    let base = interpret_page(&d0, p0).expect("rotate 0");
    let (w0, h0) = (base.width as f64, base.height as f64);
    let mut expect_box = marker_box(&base);
    let mut dims = (w0, h0);

    for (turns, rotate) in [(1, 90_i64), (2, 180), (3, 270), (4, 360)] {
        // Advance the prediction by one quarter turn.
        let corners = [
            (expect_box[0], expect_box[1]),
            (expect_box[2], expect_box[1]),
            (expect_box[2], expect_box[3]),
            (expect_box[0], expect_box[3]),
        ];
        let mut next = [f64::MAX, f64::MAX, f64::MIN, f64::MIN];
        let mut nd = dims;
        for c in corners {
            let (m, d) = quarter_turn(c, dims.0, dims.1);
            nd = d;
            next[0] = next[0].min(m.0);
            next[1] = next[1].min(m.1);
            next[2] = next[2].max(m.0);
            next[3] = next[3].max(m.1);
        }
        expect_box = next;
        dims = nd;

        let (doc, pid) = rotated_marker_doc(Some(rotate));
        let got = interpret_page(&doc, pid).expect("rotated render");
        assert_eq!(
            (got.width as f64, got.height as f64),
            dims,
            "/Rotate {rotate} ({turns} quarter turns) gave the wrong display size"
        );
        let got_box = marker_box(&got);
        assert!(
            boxes_close(got_box, expect_box),
            "/Rotate {rotate} ({turns} quarter turns) put the mark at {got_box:?}, \
             but composing the documented quarter-turn map {turns} times predicts {expect_box:?}"
        );
    }

    // The fourth turn is /Rotate 360, which must be indistinguishable from 0 —
    // not merely close, but the same primitives.
    let (d360, p360) = rotated_marker_doc(Some(360));
    let f0 = fingerprint(&base);
    let f360 = fingerprint(&interpret_page(&d360, p360).expect("rotate 360"));
    assert_eq!(
        f0, f360,
        "/Rotate 360 must render exactly as /Rotate 0: {}",
        first_difference(&f0, &f360)
    );
}

/// `/Rotate` is reduced modulo 360 (§7.7.3.3 requires a multiple of 90; the
/// implementation normalises rather than rejecting). Every value congruent to
/// the same quarter turn must render identically — including negatives, which
/// are the common real-world spelling of a counter-clockwise turn.
#[test]
fn congruent_rotations_render_identically() {
    let canonical: Vec<String> = [0_i64, 90, 180, 270]
        .iter()
        .map(|r| {
            let (d, p) = rotated_marker_doc(Some(*r));
            fingerprint(&interpret_page(&d, p).expect("canonical"))
        })
        .collect();

    for (value, quarter) in [
        (360_i64, 0_usize),
        (720, 0),
        (-360, 0),
        (450, 1),
        (-270, 1),
        (-180, 2),
        (540, 2),
        (-90, 3),
        (630, 3),
    ] {
        let (d, p) = rotated_marker_doc(Some(value));
        let got = fingerprint(&interpret_page(&d, p).expect("congruent"));
        assert_eq!(
            got,
            canonical[quarter],
            "/Rotate {value} must match /Rotate {}: {}",
            quarter * 90,
            first_difference(&canonical[quarter], &got)
        );
    }
}

// ---------------------------------------------------------------------------
// Metamorphic: translation and Form XObject equivalence

/// Prepending a `cm` translation must translate every emitted coordinate by
/// exactly that vector and change nothing else — same primitive kinds, same
/// order, same colours.
#[test]
fn translating_content_translates_the_primitives() {
    const DX: f64 = 37.0;
    const DY: f64 = -21.0;

    let draw = vec![
        Operation::new("rg", vec![0.2.into(), 0.7.into(), 0.3.into()]),
        Operation::new("re", vec![20.into(), 60.into(), 40.into(), 25.into()]),
        Operation::new("f", vec![]),
        Operation::new("w", vec![2.into()]),
        Operation::new("m", vec![15.into(), 100.into()]),
        Operation::new("l", vec![90.into(), 130.into()]),
        Operation::new("S", vec![]),
    ];

    let mut d0 = Document::with_version("1.5");
    let p0 = page_from_ops(&mut d0, draw.clone(), dictionary! {});
    let plain = interpret_page(&d0, p0).expect("plain");

    let mut shifted_ops = vec![Operation::new(
        "cm",
        vec![1.into(), 0.into(), 0.into(), 1.into(), DX.into(), DY.into()],
    )];
    shifted_ops.extend(draw);
    let mut d1 = Document::with_version("1.5");
    let p1 = page_from_ops(&mut d1, shifted_ops, dictionary! {});
    let shifted = interpret_page(&d1, p1).expect("shifted");

    assert_eq!(
        plain.prims.len(),
        shifted.prims.len(),
        "translation changed the primitive count"
    );
    assert!(!plain.prims.is_empty(), "fixture emitted nothing");

    for (i, (a, b)) in plain.prims.iter().zip(shifted.prims.iter()).enumerate() {
        match (a, b) {
            (
                Prim::Fill { argb: c0, contours: k0, .. },
                Prim::Fill { argb: c1, contours: k1, .. },
            ) => {
                assert_eq!(c0, c1, "prim {i}: translation changed the fill colour");
                assert_eq!(k0.len(), k1.len(), "prim {i}: contour count changed");
                for (ca, cb) in k0.iter().zip(k1.iter()) {
                    assert_eq!(ca.len(), cb.len(), "prim {i}: vertex count changed");
                    for (pa, pb) in ca.iter().zip(cb.iter()) {
                        assert!(
                            ((pb.0 - pa.0) as f64 - DX).abs() < 1e-3
                                && ((pb.1 - pa.1) as f64 - DY).abs() < 1e-3,
                            "prim {i}: vertex moved by ({}, {}), expected ({DX}, {DY})",
                            pb.0 - pa.0,
                            pb.1 - pa.1
                        );
                    }
                }
            }
            (Prim::Stroke { pts: s0, width: w0, .. }, Prim::Stroke { pts: s1, width: w1, .. }) => {
                assert_eq!(w0, w1, "prim {i}: a pure translation must not change stroke width");
                assert_eq!(s0.len(), s1.len(), "prim {i}: point count changed");
                for (pa, pb) in s0.iter().zip(s1.iter()) {
                    assert!(
                        ((pb.0 - pa.0) as f64 - DX).abs() < 1e-3
                            && ((pb.1 - pa.1) as f64 - DY).abs() < 1e-3,
                        "prim {i}: point moved by ({}, {}), expected ({DX}, {DY})",
                        pb.0 - pa.0,
                        pb.1 - pa.1
                    );
                }
            }
            _ => panic!("prim {i}: translation changed the primitive kind"),
        }
    }
}

/// Content invoked through a Form XObject with an identity `/Matrix` and a
/// `/BBox` large enough not to clip must paint the same ink as the same content
/// written inline (§8.10.2). The form path runs a different code route —
/// resource inheritance, `q`/`Q` bracketing, bbox clipping — so agreement here
/// is a real check on that route rather than a tautology.
#[test]
fn form_xobject_paints_the_same_ink_as_inline_content() {
    let draw = vec![
        Operation::new("rg", vec![0.1.into(), 0.4.into(), 0.9.into()]),
        Operation::new("re", vec![25.into(), 35.into(), 50.into(), 30.into()]),
        Operation::new("f", vec![]),
        Operation::new("RG", vec![0.into(), 0.5.into(), 0.into()]),
        Operation::new("w", vec![4.into()]),
        Operation::new("m", vec![30.into(), 120.into()]),
        Operation::new("l", vec![140.into(), 150.into()]),
        Operation::new("S", vec![]),
    ];

    let mut inline_doc = Document::with_version("1.5");
    let inline_page = page_from_ops(&mut inline_doc, draw.clone(), dictionary! {});
    let inline = interpret_page(&inline_doc, inline_page).expect("inline");

    let mut form_doc = Document::with_version("1.5");
    let form_bytes = Content { operations: draw }.encode().unwrap();
    let form_id = form_doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            // Covers the whole page, so /BBox clipping cannot be what differs.
            "BBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Matrix" => vec![1.into(), 0.into(), 0.into(), 1.into(), 0.into(), 0.into()],
        },
        form_bytes,
    ));
    let form_page = page_from_ops(
        &mut form_doc,
        vec![Operation::new("Do", vec![Object::Name(b"Fm0".to_vec())])],
        dictionary! { "XObject" => dictionary! { "Fm0" => form_id } },
    );
    let via_form = interpret_page(&form_doc, form_page).expect("form");

    assert_eq!(
        inline.width, via_form.width,
        "form and inline disagree on page width"
    );

    // Compare the ink only: the form route legitimately adds clip bracketing
    // primitives for /BBox, which are not ink and must not count as a
    // difference.
    let ink = |page: &PageData| -> Vec<String> {
        page.prims
            .iter()
            .filter(|p| matches!(p, Prim::Fill { .. } | Prim::Stroke { .. } | Prim::Text { .. }))
            .map(fp_prim)
            .collect()
    };
    let a = ink(&inline);
    let b = ink(&via_form);
    assert!(!a.is_empty(), "inline fixture emitted no ink");
    assert_eq!(
        a, b,
        "Form XObject ink differs from the same content inline:\n  inline: {a:#?}\n  form:   {b:#?}"
    );
}
