//! Synthetic-fixture golden tests: hand-built minimal PDFs that each exercise
//! exactly one round-1 fix, asserted against the primitives `interpret_page`
//! produces.
//!
//! Why these exist: the round-1 fixes were verified by reading code against ISO
//! 32000-1, not by rendering. The real-world fixtures the `issue321_tests`
//! harness drives cannot be committed (copyright), so nothing here can depend on
//! them. A 20-line generated PDF that exercises one feature is a better
//! regression test anyway — when it fails you know exactly what broke.
//!
//! House style, inherited from `tests.rs`: build the document with `lopdf`, run
//! the real pipeline, and assert on MEANING ("no visible ink was emitted in this
//! region") rather than on incidental structure (`prims.len() == 7`). Brittle
//! count assertions are how round 1 ended up with a mode-3 test that pinned an
//! obsolete contract.

use crate::*;
use lopdf::content::{Content, Operation};
use lopdf::{dictionary, Object, Stream};

// ---------------------------------------------------------------------------
// Fixture builders

/// Assemble a one-page document around `content` (raw content-stream bytes, so
/// tests can hand-write things `Content::encode` cannot express, such as an
/// inline image with binary pixel data). `page` supplies the page-dictionary
/// entries under test (`MediaBox`, `Rotate`, `CropBox`, `Annots`, ...) and
/// `catalog` any catalog entries (`OCProperties`). Returns the page's id.
fn assemble(
    doc: &mut Document,
    content: Vec<u8>,
    resources: Dictionary,
    mut page: Dictionary,
    catalog: Dictionary,
) -> ObjectId {
    let content_id = doc.add_object(Stream::new(dictionary! {}, content));
    assemble_with_contents(doc, Object::Reference(content_id), resources, &mut page, catalog)
}

/// As [`assemble`], but the caller owns the `/Contents` value — used by the tests
/// that need a filtered stream, a corrupt stream, or no `/Contents` at all.
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

/// Shorthand for the common case: operator list, no page or catalog extras.
fn page_from_ops(doc: &mut Document, ops: Vec<Operation>, resources: Dictionary) -> ObjectId {
    let bytes = Content { operations: ops }.encode().unwrap();
    assemble(doc, bytes, resources, dictionary! {}, dictionary! {})
}

fn rect_ops(x: i64, y: i64, w: i64, h: i64) -> Vec<Operation> {
    vec![
        Operation::new("re", vec![x.into(), y.into(), w.into(), h.into()]),
        Operation::new("f", vec![]),
    ]
}

fn flate(data: &[u8]) -> Vec<u8> {
    use flate2::write::ZlibEncoder;
    use std::io::Write;
    let mut e = ZlibEncoder::new(Vec::new(), flate2::Compression::default());
    e.write_all(data).unwrap();
    e.finish().unwrap()
}

// ---------------------------------------------------------------------------
// Meaning-level assertions about the emitted primitives

/// Whether a primitive puts ink on the page that a user could see. Mode 3 and
/// mode 7 Text records are deliberately NOT ink (§9.3.6): they exist only so the
/// glyphs reach the text index. `outline: true` Text is likewise not ink — the
/// real outline was already emitted as Fill/Stroke prims alongside it.
fn is_ink(p: &Prim) -> bool {
    match p {
        Prim::Fill { argb, contours, .. } => (argb >> 24) != 0 && contours.iter().any(|c| c.len() >= 3),
        Prim::Stroke { argb, pts, .. } => (argb >> 24) != 0 && pts.len() >= 2,
        Prim::Text { argb, render_mode, outline, text, .. } => {
            !matches!(render_mode, 3 | 7) && (argb >> 24) != 0 && !*outline && !text.is_empty()
        }
        Prim::Image { alpha, data, w, h, .. } => *alpha > 0.0 && !data.is_empty() && *w > 0 && *h > 0,
        Prim::ImageTiled { alpha, data, w, h, nx, ny, .. } => {
            *alpha > 0.0 && !data.is_empty() && *w > 0 && *h > 0 && *nx > 0 && *ny > 0
        }
        _ => false,
    }
}

/// The device-space bounding box each inking primitive covers.
///
/// Deliberately area-based rather than vertex-based. Sampling only a shape's
/// vertices misses a shape that COVERS a region without having any vertex inside
/// it — a page-sized fill, or an `ImageTiled` lattice whose corners fall outside
/// the query — and for a "no ink here" assertion that is a false negative, i.e. a
/// vacuous pass. Over-approximating with a bbox can only ever cause a false
/// FAILURE, which is the safe direction for the assertions built on this.
fn ink_boxes(prims: &[Prim]) -> Vec<[f32; 4]> {
    let quad_box = |ctm: &Mat, corners: [(f64, f64); 4]| -> [f32; 4] {
        let pts: Vec<(f32, f32)> = corners
            .iter()
            .map(|&(u, v)| {
                let (dx, dy) = transform(ctm, u, v);
                (dx as f32, dy as f32)
            })
            .collect();
        bbox_of(&pts)
    };
    let mut out = Vec::new();
    for p in prims.iter().filter(|p| is_ink(p)) {
        match p {
            Prim::Fill { contours, .. } => {
                let pts: Vec<(f32, f32)> = contours.iter().flatten().copied().collect();
                if !pts.is_empty() {
                    out.push(bbox_of(&pts));
                }
            }
            Prim::Stroke { pts, .. } => {
                if !pts.is_empty() {
                    out.push(bbox_of(pts));
                }
            }
            // A glyph's origin, widened by its advance and nominal height, so a text
            // run is treated as the area it occupies rather than a single point.
            Prim::Text { x, y, size, advance, .. } => {
                out.push([*x, *y, *x + advance.max(*size * 0.5), *y + *size])
            }
            Prim::Image { ctm, .. } => {
                out.push(quad_box(ctm, [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)]))
            }
            // `ctm` maps the unit square onto ONE cell, so cell (i,j) is that square
            // offset by (i,j) and the painted area is the whole lattice extent.
            Prim::ImageTiled { ctm, i0, j0, nx, ny, .. } => {
                let (i1, j1) = ((*i0 + *nx as i32) as f64, (*j0 + *ny as i32) as f64);
                let (i0, j0) = (*i0 as f64, *j0 as f64);
                out.push(quad_box(ctm, [(i0, j0), (i1, j0), (i1, j1), (i0, j1)]));
            }
            _ => {}
        }
    }
    out
}

/// True when some primitive paints anywhere inside `[x0,x1) x [y0,y1)`.
fn ink_in_region(prims: &[Prim], x0: f32, y0: f32, x1: f32, y1: f32) -> bool {
    ink_boxes(prims)
        .iter()
        .any(|b| b[0] < x1 && b[2] > x0 && b[1] < y1 && b[3] > y0)
}

fn text_of(prims: &[Prim]) -> String {
    prims
        .iter()
        .filter_map(|p| match p {
            Prim::Text { text, .. } => Some(text.as_str()),
            _ => None,
        })
        .collect()
}

fn count<F: Fn(&Prim) -> bool>(prims: &[Prim], f: F) -> usize {
    prims.iter().filter(|p| f(p)).count()
}

/// Signed area of a polygon. A rectangle given in the spec's `/QuadPoints`
/// order collapses to ~0 if the vertices are consumed in file order, because
/// UL,UR,LL,LR traces a self-intersecting bow-tie.
fn polygon_area(pts: &[(f32, f32)]) -> f64 {
    let p: Vec<(f64, f64)> = pts.iter().map(|&(x, y)| (x as f64, y as f64)).collect();
    shoelace_area(&p).abs()
}

/// A soft mask carrying a `/TR` must still produce a well-formed bracket. Pinned

/// Build a page whose content is wrapped in `BDC /OC`, with the OCG switched OFF
/// via `/OCProperties /D /OFF`. `inner` is spliced inside the hidden region and
/// `after` follows the closing `EMC`.
fn hidden_ocg_page(
    doc: &mut Document,
    inner: Vec<Operation>,
    after: Vec<Operation>,
    off: bool,
) -> ObjectId {
    let ocg_id = doc.add_object(dictionary! { "Type" => "OCG", "Name" => Object::string_literal("Layer") });
    let mut ops = vec![Operation::new(
        "BDC",
        vec![Object::Name(b"OC".to_vec()), Object::Name(b"OC0".to_vec())],
    )];
    ops.extend(inner);
    ops.push(Operation::new("EMC", vec![]));
    ops.extend(after);
    let bytes = Content { operations: ops }.encode().unwrap();
    let d = if off {
        dictionary! { "OFF" => vec![ocg_id.into()] }
    } else {
        dictionary! { "ON" => vec![ocg_id.into()] }
    };
    let catalog = dictionary! {
        "OCProperties" => dictionary! { "OCGs" => vec![ocg_id.into()], "D" => d },
    };
    let resources = dictionary! {
        "Properties" => dictionary! { "OC0" => ocg_id },
    };
    assemble(doc, bytes, resources, dictionary! {}, catalog)
}

/// §8.11.4.5: content inside a hidden OCG is not drawn, and a plain BMC/EMC pair
/// nested inside it must not pop the hidden frame early. The round-1 bug popped
/// on the inner EMC, so everything from there to the end of the page un-hid.
#[test]
fn nested_bmc_inside_hidden_ocg_does_not_unhide_the_rest_of_the_region() {
    let mut doc = Document::with_version("1.6");
    let mut inner = rect_ops(10, 10, 40, 40); // hidden, before the nested BMC
    inner.push(Operation::new("BMC", vec![Object::Name(b"Tx".to_vec())]));
    inner.extend(rect_ops(60, 10, 40, 40)); // hidden, inside the nested BMC
    inner.push(Operation::new("EMC", vec![]));
    inner.extend(rect_ops(110, 10, 40, 40)); // hidden, AFTER the nested EMC
    let after = rect_ops(300, 300, 40, 40); // visible, after the OCG region
    let page_id = hidden_ocg_page(&mut doc, inner, after, true);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        !ink_in_region(&page.prims, 0.0, 0.0, 200.0, 100.0),
        "no ink may be painted anywhere in the hidden OCG region; the nested \
         BMC/EMC pair must not pop the hidden frame"
    );
    assert!(
        ink_in_region(&page.prims, 300.0, 300.0, 341.0, 341.0),
        "content after the OCG region is unaffected and must still paint"
    );
}

/// Control for the test above: the same page with the OCG switched ON must paint
/// everything, so a green run cannot come from suppressing the whole page.
#[test]
fn visible_ocg_region_paints_all_of_its_content() {
    let mut doc = Document::with_version("1.6");
    let mut inner = rect_ops(10, 10, 40, 40);
    inner.push(Operation::new("BMC", vec![Object::Name(b"Tx".to_vec())]));
    inner.extend(rect_ops(60, 10, 40, 40));
    inner.push(Operation::new("EMC", vec![]));
    inner.extend(rect_ops(110, 10, 40, 40));
    let page_id = hidden_ocg_page(&mut doc, inner, rect_ops(300, 300, 40, 40), false);

    let page = interpret_page(&doc, page_id).expect("interpret");
    for (x, label) in [(10.0, "before nested BMC"), (60.0, "inside nested BMC"), (110.0, "after nested EMC")] {
        assert!(
            ink_in_region(&page.prims, x, 10.0, x + 41.0, 51.0),
            "visible OCG: the rect {label} must paint"
        );
    }
}

/// §8.11.4.3 Table 101 lists `/BaseState`, then `/ON`, then `/OFF`, and applying
/// them in that order means `/OFF` wins for a group named by both arrays — which
/// is also what mainstream viewers do, so a file authored against them hides what
/// its author expected to be hidden.
#[test]
fn a_group_in_both_on_and_off_is_hidden() {
    let mut doc = Document::with_version("1.6");
    let ocg_id = doc.add_object(dictionary! { "Type" => "OCG", "Name" => Object::string_literal("L") });
    let mut ops = vec![Operation::new(
        "BDC",
        vec![Object::Name(b"OC".to_vec()), Object::Name(b"OC0".to_vec())],
    )];
    ops.extend(rect_ops(10, 10, 40, 40));
    ops.push(Operation::new("EMC", vec![]));
    ops.extend(rect_ops(300, 300, 40, 40));
    let bytes = Content { operations: ops }.encode().unwrap();
    let catalog = dictionary! {
        "OCProperties" => dictionary! {
            "OCGs" => vec![ocg_id.into()],
            "D" => dictionary! {
                "ON" => vec![ocg_id.into()],
                "OFF" => vec![ocg_id.into()],
            },
        },
    };
    let resources = dictionary! { "Properties" => dictionary! { "OC0" => ocg_id } };
    let page_id = assemble(&mut doc, bytes, resources, dictionary! {}, catalog);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        !ink_in_region(&page.prims, 0.0, 0.0, 60.0, 60.0),
        "/OFF is applied after /ON, so a group in both must be hidden"
    );
    assert!(
        ink_in_region(&page.prims, 300.0, 300.0, 341.0, 341.0),
        "content outside the OCG region must still paint"
    );
}

/// §14.6.2: an unbalanced `EMC` must be discarded, not allowed to pop a frame it
/// does not own — otherwise a stray EMC un-hides the hidden region that follows.
#[test]
fn unbalanced_emc_cannot_pop_a_frame_it_does_not_own() {
    let mut doc = Document::with_version("1.6");
    let ocg_id = doc.add_object(dictionary! { "Type" => "OCG", "Name" => Object::string_literal("L") });
    let mut ops = vec![
        // Stray EMC with nothing open.
        Operation::new("EMC", vec![]),
        Operation::new("BDC", vec![Object::Name(b"OC".to_vec()), Object::Name(b"OC0".to_vec())]),
    ];
    ops.extend(rect_ops(10, 10, 40, 40)); // hidden
    ops.push(Operation::new("EMC", vec![]));
    ops.push(Operation::new("EMC", vec![])); // second stray EMC
    ops.extend(rect_ops(300, 300, 40, 40)); // visible
    let bytes = Content { operations: ops }.encode().unwrap();
    let catalog = dictionary! {
        "OCProperties" => dictionary! {
            "OCGs" => vec![ocg_id.into()],
            "D" => dictionary! { "OFF" => vec![ocg_id.into()] },
        },
    };
    let resources = dictionary! { "Properties" => dictionary! { "OC0" => ocg_id } };
    let page_id = assemble(&mut doc, bytes, resources, dictionary! {}, catalog);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        !ink_in_region(&page.prims, 0.0, 0.0, 100.0, 100.0),
        "a stray EMC before the BDC must not leave the hidden region un-hidden"
    );
    assert!(
        ink_in_region(&page.prims, 300.0, 300.0, 341.0, 341.0),
        "a stray EMC after the region must not suppress later content either"
    );
}

/// §8.11.4.5 applies to *all* marked content, text included: a `Tj` inside a
/// hidden OCG must put no ink on the page. (The glyphs may still reach the text
/// index — that is what mode-3 text does — but they must not be painted.)
#[test]
fn hidden_ocg_suppresses_text_as_well_as_paths() {
    let mut doc = Document::with_version("1.6");
    let font_id = doc.add_object(dictionary! {
        "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
    });
    let ocg_id = doc.add_object(dictionary! { "Type" => "OCG", "Name" => Object::string_literal("L") });
    let ops = vec![
        Operation::new("BDC", vec![Object::Name(b"OC".to_vec()), Object::Name(b"OC0".to_vec())]),
        Operation::new("BT", vec![]),
        Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
        Operation::new("Td", vec![50.into(), 50.into()]),
        Operation::new("Tj", vec![Object::string_literal("HIDDEN")]),
        Operation::new("ET", vec![]),
        Operation::new("EMC", vec![]),
    ];
    let bytes = Content { operations: ops }.encode().unwrap();
    let catalog = dictionary! {
        "OCProperties" => dictionary! {
            "OCGs" => vec![ocg_id.into()],
            "D" => dictionary! { "OFF" => vec![ocg_id.into()] },
        },
    };
    let resources = dictionary! {
        "Font" => dictionary! { "F1" => font_id },
        "Properties" => dictionary! { "OC0" => ocg_id },
    };
    let page_id = assemble(&mut doc, bytes, resources, dictionary! {}, catalog);

    let page = interpret_page(&doc, page_id).expect("interpret");
    let painted: Vec<&str> = page
        .prims
        .iter()
        .filter(|p| is_ink(p))
        .filter_map(|p| match p {
            Prim::Text { text, .. } => Some(text.as_str()),
            _ => None,
        })
        .collect();
    assert!(
        painted.is_empty(),
        "text inside a hidden OCG must not be painted; got {painted:?}"
    );
}

/// §8.11.4.2: an XObject carrying its own `/OC` is skipped wholesale when that
/// optional content is off — the form's content never runs.
#[test]
fn form_xobject_with_hidden_oc_paints_nothing() {
    let mut doc = Document::with_version("1.6");
    let ocg_id = doc.add_object(dictionary! { "Type" => "OCG", "Name" => Object::string_literal("L") });
    let form_content = Content { operations: rect_ops(0, 0, 80, 80) };
    let form_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 80.into(), 80.into()],
            "OC" => ocg_id,
        },
        form_content.encode().unwrap(),
    ));
    let mut ops = vec![Operation::new("Do", vec![Object::Name(b"Fm0".to_vec())])];
    ops.extend(rect_ops(300, 300, 40, 40));
    let bytes = Content { operations: ops }.encode().unwrap();
    let catalog = dictionary! {
        "OCProperties" => dictionary! {
            "OCGs" => vec![ocg_id.into()],
            "D" => dictionary! { "OFF" => vec![ocg_id.into()] },
        },
    };
    let resources = dictionary! { "XObject" => dictionary! { "Fm0" => form_id } };
    let page_id = assemble(&mut doc, bytes, resources, dictionary! {}, catalog);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        !ink_in_region(&page.prims, 0.0, 0.0, 100.0, 100.0),
        "a form XObject with /OC off must not paint"
    );
    assert!(
        ink_in_region(&page.prims, 300.0, 300.0, 341.0, 341.0),
        "the rest of the page still paints"
    );
}

// ===========================================================================
// 1. Optional content — §14.6 marked content, §8.11 optional content groups

/// Self-check on the mechanism ~30 assertions in this file depend on. A shape can
/// COVER a region without having any vertex inside it, so vertex sampling reports
/// "no ink" for a page-sized fill queried at a small interior rectangle. For a
/// `!ink_in_region(...)` assertion that is a false negative — a vacuous pass, the
/// exact failure this whole module exists to eliminate. `ink_in_region` must
/// therefore be area-based; do not revert it to point containment.
#[test]
fn ink_in_region_detects_a_shape_that_covers_without_a_vertex_inside() {
    let mut doc = Document::with_version("1.5");
    // One fill spanning the whole page: no vertex anywhere near the middle.
    let ops = vec![
        Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
        Operation::new("re", vec![0.into(), 0.into(), 612.into(), 792.into()]),
        Operation::new("f", vec![]),
    ];
    let page_id = page_from_ops(&mut doc, ops, dictionary! {});
    let page = interpret_page(&doc, page_id).expect("interpret");

    assert!(
        ink_in_region(&page.prims, 300.0, 400.0, 310.0, 410.0),
        "a page-covering fill must register as ink in a small interior region, even \
         though none of its vertices lie inside it"
    );
    assert!(
        !ink_in_region(&page.prims, 700.0, 900.0, 750.0, 950.0),
        "and must NOT register outside its own extent, or every negative assertion \
         in this file becomes unfalsifiable"
    );
}


/// §9.3.6: `3 Tr` neither fills nor strokes. End-to-end guard on top of the
/// `show_string`-level ones in `tests.rs`: a whole page whose only content is a
/// mode-3 show-text must put NO ink down, while the glyphs still reach the text
/// index so a scanned page's OCR layer stays searchable. If mode 3 ever paints,
/// every scanned PDF overprints its own OCR layer.
#[test]
fn mode3_page_is_searchable_but_emits_no_ink() {
    let mut doc = Document::with_version("1.5");
    let font_id = doc.add_object(dictionary! {
        "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
    });
    let ops = vec![
        Operation::new("BT", vec![]),
        Operation::new("Tr", vec![3.into()]),
        Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
        Operation::new("Td", vec![72.into(), 700.into()]),
        Operation::new("Tj", vec![Object::string_literal("Scan")]),
        Operation::new("ET", vec![]),
    ];
    let page_id = page_from_ops(&mut doc, ops, dictionary! { "Font" => dictionary! { "F1" => font_id } });

    let page = interpret_page(&doc, page_id).expect("interpret");
    let inked = count(&page.prims, is_ink);
    assert_eq!(inked, 0, "a mode-3 page must emit no visible ink, got {inked} inking prims");
    assert_eq!(text_of(&page.prims), "Scan", "mode-3 glyphs must stay searchable");
    for p in &page.prims {
        if let Prim::Text { render_mode, argb, .. } = p {
            assert_eq!(*render_mode, 3, "the Text record must declare Tr 3 for the Kotlin paint guard");
            assert_eq!(*argb, 0, "and be fully transparent as a second, independent guard");
        }
    }
}

// ===========================================================================
// 3. Page robustness — §7.7.3.3

fn annot_square(doc: &mut Document) -> ObjectId {
    doc.add_object(dictionary! {
        "Type" => "Annot", "Subtype" => "Square",
        "Rect" => vec![100.into(), 100.into(), 200.into(), 160.into()],
        "IC" => vec![1.0.into(), 0.0.into(), 0.0.into()],
        "C" => vec![0.0.into(), 0.0.into(), 0.0.into()],
    })
}

/// §7.7.3.3: `/Contents` is optional. A page without it is still a page — right
/// size, and its annotations still render. It must not become a lost page.
#[test]
fn page_without_contents_keeps_its_size_and_renders_annotations() {
    let mut doc = Document::with_version("1.5");
    let square = annot_square(&mut doc);
    let mut page = dictionary! {
        "MediaBox" => vec![0.into(), 0.into(), 300.into(), 400.into()],
        "Annots" => vec![square.into()],
    };
    let page_id = assemble_with_contents(&mut doc, Object::Null, dictionary! {}, &mut page, dictionary! {});

    let page = interpret_page(&doc, page_id).expect("a page with no /Contents must still interpret");
    assert_eq!((page.width, page.height), (300.0, 400.0), "size comes from /MediaBox, not from content");
    assert!(
        ink_in_region(&page.prims, 100.0, 100.0, 201.0, 161.0),
        "the annotation must still render on a page with no /Contents"
    );
}

/// §7.7.3.3: a content stream the tokenizer cannot parse must not lose the page.
/// The size must still be right and the annotations must still render.
#[test]
fn page_with_corrupt_content_stream_keeps_its_size_and_renders_annotations() {
    let mut doc = Document::with_version("1.5");
    let square = annot_square(&mut doc);
    // Deliberately unparseable: an unterminated string and stray delimiters.
    let corrupt = b"q 1 0 0 1 0 0 cm >> ] ) BT /F1 (unterminated".to_vec();
    let mut page = dictionary! {
        "MediaBox" => vec![0.into(), 0.into(), 300.into(), 400.into()],
        "Annots" => vec![square.into()],
    };
    let cid = doc.add_object(Stream::new(dictionary! {}, corrupt));
    let page_id = assemble_with_contents(
        &mut doc,
        Object::Reference(cid),
        dictionary! {},
        &mut page,
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("a corrupt content stream must not lose the page");
    assert_eq!((page.width, page.height), (300.0, 400.0));
    assert!(
        ink_in_region(&page.prims, 100.0, 100.0, 201.0, 161.0),
        "annotations must survive a content stream that fails to tokenize"
    );
}

// ===========================================================================
// 4. Clipping — §8.5.4

fn clips(prims: &[Prim]) -> Vec<(bool, Vec<(f32, f32)>)> {
    prims
        .iter()
        .filter_map(|p| match p {
            Prim::ClipPush { even_odd, pts, .. } => Some((*even_odd, pts.clone())),
            _ => None,
        })
        .collect()
}

fn bbox_of(pts: &[(f32, f32)]) -> [f32; 4] {
    let mut b = [f32::MAX, f32::MAX, f32::MIN, f32::MIN];
    for &(x, y) in pts {
        b[0] = b[0].min(x);
        b[1] = b[1].min(y);
        b[2] = b[2].max(x);
        b[3] = b[3].max(y);
    }
    b
}

fn axial_shading(doc: &mut Document) -> ObjectId {
    let func_id = doc.add_object(dictionary! {
        "FunctionType" => 2,
        "Domain" => vec![0.into(), 1.into()],
        "C0" => vec![1.0.into(), 0.0.into(), 0.0.into()],
        "C1" => vec![0.0.into(), 0.0.into(), 1.0.into()],
        "N" => 1,
    });
    doc.add_object(dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => "DeviceRGB",
        "Coords" => vec![0.into(), 0.into(), 400.into(), 0.into()],
        "Extend" => vec![Object::Boolean(true), Object::Boolean(true)],
        "Function" => func_id,
    })
}

/// §8.5.4: `W n` intersects with the clip already in force — it does not replace
/// it. Asserted on meaning: an `sh` painted under two nested clips is confined
/// to their intersection, so its device extent is the inner 100x100 box and not
/// the outer 400x500 one.
#[test]
fn nested_w_n_clips_intersect_and_confine_a_shading() {
    let mut doc = Document::with_version("1.5");
    let sh_id = axial_shading(&mut doc);
    let ops = vec![
        Operation::new("re", vec![0.into(), 0.into(), 400.into(), 500.into()]),
        Operation::new("W", vec![]),
        Operation::new("n", vec![]),
        Operation::new("re", vec![100.into(), 100.into(), 100.into(), 100.into()]),
        Operation::new("W", vec![]),
        Operation::new("n", vec![]),
        Operation::new("sh", vec![Object::Name(b"Sh0".to_vec())]),
    ];
    let bytes = Content { operations: ops }.encode().unwrap();
    let page_id = assemble(
        &mut doc,
        bytes,
        dictionary! { "Shading" => dictionary! { "Sh0" => sh_id } },
        dictionary! { "MediaBox" => vec![0.into(), 0.into(), 400.into(), 500.into()] },
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert_eq!(clips(&page.prims).len(), 2, "each W n commits one clip");
    let ctm = page
        .prims
        .iter()
        .find_map(|p| match p {
            Prim::Image { ctm, .. } => Some(*ctm),
            _ => None,
        })
        .expect("the shading must be painted");
    let w = ctm[0].abs() + ctm[2].abs();
    let h = ctm[1].abs() + ctm[3].abs();
    assert!(
        w < 200.0 && h < 200.0,
        "the shading must be confined to the intersected clip, got {w:.0}x{h:.0} \
         (the outer clip alone would be 400x500)"
    );
}

/// §8.5.4: `W` establishes a clip but does not paint; the painting operator that
/// ends the path still runs. So `re W f` must BOTH fill the rectangle AND clip.
#[test]
fn w_followed_by_fill_both_fills_and_clips() {
    let mut doc = Document::with_version("1.5");
    let ops = vec![
        Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
        Operation::new("re", vec![10.into(), 10.into(), 50.into(), 50.into()]),
        Operation::new("W", vec![]),
        Operation::new("f", vec![]),
    ];
    let page_id = page_from_ops(&mut doc, ops, dictionary! {});

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        ink_in_region(&page.prims, 10.0, 10.0, 61.0, 61.0),
        "`W f` must still fill the path"
    );
    assert_eq!(clips(&page.prims).len(), 1, "`W f` must also commit the clip");
}

/// §8.5.4 / Table 60: `W` uses the nonzero winding rule and `W*` the even-odd
/// rule. The chosen rule has to reach the clip primitive, or a path with a hole
/// clips to the wrong region.
#[test]
fn w_and_w_star_record_different_winding_rules() {
    for (op, expect_even_odd) in [("W", false), ("W*", true)] {
        let mut doc = Document::with_version("1.5");
        let ops = vec![
            Operation::new("re", vec![0.into(), 0.into(), 100.into(), 100.into()]),
            Operation::new(op, vec![]),
            Operation::new("n", vec![]),
        ];
        let page_id = page_from_ops(&mut doc, ops, dictionary! {});
        let page = interpret_page(&doc, page_id).expect("interpret");
        let cl = clips(&page.prims);
        assert_eq!(cl.len(), 1, "{op} n commits exactly one clip");
        assert_eq!(cl[0].0, expect_even_odd, "{op} must record even_odd={expect_even_odd}");
    }
}

/// §8.4.4: `Q` restores the clip that was in force at the matching `q`, and a
/// clip that was still only *pending* at that point is discarded with it. The
/// round-1 bug let the pending `W` survive the `Q` and clip the whole rest of
/// the page — so the second rectangle here vanished.
#[test]
fn pending_clip_does_not_survive_the_matching_q_restore() {
    let mut doc = Document::with_version("1.5");
    let ops = vec![
        Operation::new("q", vec![]),
        Operation::new("re", vec![0.into(), 0.into(), 10.into(), 10.into()]),
        Operation::new("W", vec![]), // pending, never committed by a path-painting op
        Operation::new("Q", vec![]),
        Operation::new("rg", vec![0.0.into(), 1.0.into(), 0.0.into()]),
        Operation::new("re", vec![200.into(), 200.into(), 100.into(), 100.into()]),
        Operation::new("f", vec![]),
    ];
    let page_id = page_from_ops(&mut doc, ops, dictionary! {});

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert_eq!(
        clips(&page.prims).len(),
        0,
        "a `W` that was never committed must be dropped by `Q`, not applied afterwards"
    );
    assert!(
        ink_in_region(&page.prims, 200.0, 200.0, 301.0, 301.0),
        "the rectangle painted after `Q` must survive"
    );
}

// ===========================================================================
// 5. Annotation appearances — §12.5.5

/// §8.10.2: a form XObject's `/BBox` clip is mandatory. An `/AP /N` stream whose
/// content draws far outside its BBox must be clipped to it, so the clip has to
/// be emitted (mapped onto the annotation `/Rect`) around the appearance.
#[test]
fn ap_form_drawing_outside_its_bbox_is_clipped_to_it() {
    let mut doc = Document::with_version("1.7");
    // BBox is 10x10 but the content fills 1000x1000 centred well outside it.
    let ap_content = Content {
        operations: vec![
            Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
            Operation::new("re", vec![(-500).into(), (-500).into(), 1000.into(), 1000.into()]),
            Operation::new("f", vec![]),
        ],
    };
    let ap_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 10.into(), 10.into()],
        },
        ap_content.encode().unwrap(),
    ));
    let annot = doc.add_object(dictionary! {
        "Type" => "Annot", "Subtype" => "Stamp",
        "Rect" => vec![100.into(), 100.into(), 200.into(), 200.into()],
        "AP" => dictionary! { "N" => ap_id },
    });
    let mut page = dictionary! { "Annots" => vec![annot.into()] };
    let page_id = assemble_with_contents(
        &mut doc,
        Object::Null,
        dictionary! {},
        &mut page,
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let push = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::ClipPush { .. }))
        .expect("§8.10.2: the /BBox clip is mandatory and must be emitted");
    let fill = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::Fill { .. }))
        .expect("the appearance content must be drawn");
    let pop = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::ClipPop))
        .expect("the BBox clip must be popped again");
    assert!(push < fill && fill < pop, "the appearance must be drawn inside the BBox clip");

    let b = bbox_of(&clips(&page.prims)[0].1);
    for (got, want, name) in [(b[0], 100.0, "x0"), (b[1], 100.0, "y0"), (b[2], 200.0, "x1"), (b[3], 200.0, "y1")] {
        assert!(
            (got - want as f32).abs() < 0.5,
            "the BBox clip must map onto the annotation /Rect; {name} was {got}, expected {want}"
        );
    }
}

/// §12.5.6.10: `/QuadPoints` are stored UL, UR, LL, LR — a "Z", not a ring.
/// Consuming them in file order traces a self-intersecting bow-tie whose area
/// collapses to nearly nothing, which is why highlights rendered as two thin
/// triangles. Assert the filled quad really is the rectangle.
#[test]
fn highlight_quadpoints_in_spec_order_fill_a_rectangle_not_a_bowtie() {
    let mut doc = Document::with_version("1.7");
    // 100 wide x 30 tall, given in the spec's UL, UR, LL, LR order.
    let quad = vec![
        100.into(), 130.into(), // UL
        200.into(), 130.into(), // UR
        100.into(), 100.into(), // LL
        200.into(), 100.into(), // LR
    ];
    let annot = doc.add_object(dictionary! {
        "Type" => "Annot", "Subtype" => "Highlight",
        "Rect" => vec![100.into(), 100.into(), 200.into(), 130.into()],
        "QuadPoints" => quad,
        "C" => vec![1.0.into(), 1.0.into(), 0.0.into()],
    });
    let mut page = dictionary! { "Annots" => vec![annot.into()] };
    let page_id = assemble_with_contents(&mut doc, Object::Null, dictionary! {}, &mut page, dictionary! {});

    let page = interpret_page(&doc, page_id).expect("interpret");
    let contour = page
        .prims
        .iter()
        .find_map(|p| match p {
            Prim::Fill { contours, .. } => contours.first().cloned(),
            _ => None,
        })
        .expect("the highlight must be filled");
    let area = polygon_area(&contour);
    assert!(
        (area - 3000.0).abs() < 1.0,
        "the quad must enclose the full 100x30 = 3000 area; got {area:.1} \
         (a bow-tie from file-order vertices collapses to ~0)"
    );
}

/// §12.5.3 Table 165: only Hidden (bit 2, value 2) and NoView (bit 6, value 32)
/// suppress on-screen display. Print, NoZoom, NoRotate and friends must not.
#[test]
fn only_hidden_and_noview_flags_suppress_an_annotation() {
    // (flag value, must be visible on screen)
    let cases = [
        (0i64, true),
        (2, false),   // Hidden
        (4, true),    // Print
        (8, true),    // NoZoom
        (16, true),   // NoRotate
        (32, false),  // NoView
        (36, false),  // Print | NoView
        (64, true),   // ReadOnly
        (128, true),  // Locked
    ];
    for (flags, expect_visible) in cases {
        let mut doc = Document::with_version("1.7");
        let annot = doc.add_object(dictionary! {
            "Type" => "Annot", "Subtype" => "Square",
            "Rect" => vec![100.into(), 100.into(), 200.into(), 160.into()],
            "IC" => vec![1.0.into(), 0.0.into(), 0.0.into()],
            "F" => flags,
        });
        let mut page = dictionary! { "Annots" => vec![annot.into()] };
        let page_id = assemble_with_contents(&mut doc, Object::Null, dictionary! {}, &mut page, dictionary! {});
        let page = interpret_page(&doc, page_id).expect("interpret");
        let visible = ink_in_region(&page.prims, 100.0, 100.0, 201.0, 161.0);
        assert_eq!(
            visible, expect_visible,
            "/F {flags}: expected on-screen visible={expect_visible}, got {visible}"
        );
    }
}

// ===========================================================================
// 6. Page geometry — §7.7.3.3, §14.11.2

/// Every point a primitive touches, ink or not — used to prove content landed on
/// the canvas rather than off it.
fn all_points(prims: &[Prim]) -> Vec<(f32, f32)> {
    let mut out = Vec::new();
    for p in prims {
        match p {
            Prim::Fill { contours, .. } => out.extend(contours.iter().flatten().copied()),
            Prim::Stroke { pts, .. } => out.extend(pts.iter().copied()),
            Prim::Text { x, y, .. } => out.push((*x, *y)),
            _ => {}
        }
    }
    out
}

/// §7.7.3.3 + §14.11.2: `/Rotate` is baked into the emitted coordinates, and the
/// reported size is the DISPLAY size (swapped for quarter turns). For every
/// rotation the same content must land on the canvas.
#[test]
fn content_lands_on_canvas_for_every_rotation() {
    for rot in [0i64, 90, 180, 270] {
        let mut doc = Document::with_version("1.5");
        let bytes = Content { operations: rect_ops(50, 50, 100, 100) }.encode().unwrap();
        let page_id = assemble(
            &mut doc,
            bytes,
            dictionary! {},
            dictionary! {
                "MediaBox" => vec![0.into(), 0.into(), 400.into(), 500.into()],
                "Rotate" => rot,
            },
            dictionary! {},
        );
        let page = interpret_page(&doc, page_id).expect("interpret");
        let (ew, eh) = if rot == 90 || rot == 270 { (500.0, 400.0) } else { (400.0, 500.0) };
        assert_eq!(
            (page.width, page.height), (ew, eh),
            "/Rotate {rot}: reported size must be the display size"
        );
        let pts = all_points(&page.prims);
        assert!(!pts.is_empty(), "/Rotate {rot}: the rectangle must be emitted");
        for &(x, y) in &pts {
            assert!(
                x >= -0.5 && x <= page.width + 0.5 && y >= -0.5 && y <= page.height + 0.5,
                "/Rotate {rot}: point ({x},{y}) fell off the {}x{} canvas",
                page.width, page.height
            );
        }
        // A 100x100 square stays a 100x100 square under any quarter turn.
        let b = bbox_of(&pts);
        assert!(
            (b[2] - b[0] - 100.0).abs() < 0.5 && (b[3] - b[1] - 100.0).abs() < 0.5,
            "/Rotate {rot}: the square was distorted to {}x{}",
            b[2] - b[0], b[3] - b[1]
        );
    }
}

/// §14.11.2: `/CropBox` sets the visible page, and its origin is baked into the
/// emitted coordinates — Kotlin must not re-apply it. Content at the CropBox
/// origin therefore lands at (0,0).
#[test]
fn crop_box_smaller_than_media_box_moves_its_origin_to_zero() {
    let mut doc = Document::with_version("1.5");
    let bytes = Content { operations: rect_ops(100, 100, 200, 300) }.encode().unwrap();
    let page_id = assemble(
        &mut doc,
        bytes,
        dictionary! {},
        dictionary! {
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "CropBox" => vec![100.into(), 100.into(), 300.into(), 400.into()],
        },
        dictionary! {},
    );
    let page = interpret_page(&doc, page_id).expect("interpret");
    assert_eq!((page.width, page.height), (200.0, 300.0), "size is the CropBox, not the MediaBox");
    let b = bbox_of(&all_points(&page.prims));
    assert!(
        b[0].abs() < 0.5 && b[1].abs() < 0.5,
        "the CropBox origin must be baked in: rect started at ({}, {}), expected (0, 0)",
        b[0], b[1]
    );
    assert!(
        b[2] <= page.width + 0.5 && b[3] <= page.height + 0.5,
        "and the content must still fit the canvas"
    );
}

/// §7.9.5: a rectangle may be given with its corners in any order. A `/CropBox`
/// written as [x1 y1 x0 y0] must be normalized, not treated as empty or negative.
#[test]
fn crop_box_with_inverted_corner_order_is_normalized() {
    let mut doc = Document::with_version("1.5");
    let bytes = Content { operations: rect_ops(100, 100, 200, 300) }.encode().unwrap();
    let page_id = assemble(
        &mut doc,
        bytes,
        dictionary! {},
        dictionary! {
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            // Upper-right corner first — the same box as the test above.
            "CropBox" => vec![300.into(), 400.into(), 100.into(), 100.into()],
        },
        dictionary! {},
    );
    let page = interpret_page(&doc, page_id).expect("interpret");
    assert_eq!(
        (page.width, page.height), (200.0, 300.0),
        "an inverted /CropBox must normalize to the same 200x300 box"
    );
    let b = bbox_of(&all_points(&page.prims));
    assert!(b[0].abs() < 0.5 && b[1].abs() < 0.5, "and place content at the origin");
}

/// §14.11.2: `/UserUnit` scales the *physical* interpretation of a unit; it must
/// not scale the geometry we emit, or every page with it renders at the wrong
/// size and content walks off the canvas.
#[test]
fn user_unit_does_not_move_content_or_resize_the_page() {
    let mut sizes = Vec::new();
    let mut origins = Vec::new();
    for uu in [1i64, 5] {
        let mut doc = Document::with_version("1.6");
        let bytes = Content { operations: rect_ops(50, 50, 100, 100) }.encode().unwrap();
        let page_id = assemble(
            &mut doc,
            bytes,
            dictionary! {},
            dictionary! {
                "MediaBox" => vec![0.into(), 0.into(), 400.into(), 500.into()],
                "UserUnit" => uu,
            },
            dictionary! {},
        );
        let page = interpret_page(&doc, page_id).expect("interpret");
        sizes.push((page.width, page.height));
        origins.push(bbox_of(&all_points(&page.prims)));
    }
    assert_eq!(sizes[0], sizes[1], "/UserUnit must not change the reported page size");
    assert_eq!(sizes[0], (400.0, 500.0));
    for i in 0..4 {
        assert!(
            (origins[0][i] - origins[1][i]).abs() < 0.5,
            "/UserUnit must not move content: {:?} vs {:?}", origins[0], origins[1]
        );
    }
}

/// `page_base_inverse` must undo `page_base_matrix` exactly, for every rotation
/// AND with a CropBox in play — hit-testing a tap back into PDF space depends on
/// it, so an approximate inverse puts every tap on the wrong annotation.
#[test]
fn page_base_inverse_round_trips_with_a_crop_box_and_rotation() {
    for rot in [0i64, 90, 180, 270] {
        let mut doc = Document::with_version("1.5");
        let bytes = Content { operations: rect_ops(0, 0, 1, 1) }.encode().unwrap();
        let page_id = assemble(
            &mut doc,
            bytes,
            dictionary! {},
            dictionary! {
                "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
                "CropBox" => vec![37.into(), 61.into(), 337.into(), 461.into()],
                "Rotate" => rot,
            },
            dictionary! {},
        );
        let fwd = page_base_matrix(&doc, page_id);
        let inv = page_base_inverse(&doc, 0);
        for (x, y) in [(37.0, 61.0), (337.0, 461.0), (200.0, 123.5)] {
            let (dx, dy) = transform(&fwd, x, y);
            let (rx, ry) = transform(&inv, dx, dy);
            assert!(
                (rx - x).abs() < 1e-3 && (ry - y).abs() < 1e-3,
                "/Rotate {rot}: ({x},{y}) -> ({dx},{dy}) -> ({rx},{ry}) is not a round trip"
            );
        }
    }
}

// ===========================================================================
// 7. Images and masks — §8.9.6

fn no_cs() -> HashMap<Vec<u8>, ObjectId> {
    HashMap::new()
}

fn alphas(img: &ImageData) -> Vec<u8> {
    assert_eq!(img.format, 0, "expected decoded RGBA, not a passthrough format");
    img.data.chunks_exact(4).map(|px| px[3]).collect()
}

/// §8.9.6.2: in a stencil mask with the default `/Decode [0 1]`, sample value 0
/// marks the places that ARE painted with the current fill colour; 1 leaves the
/// page untouched.
#[test]
fn stencil_image_mask_paints_where_the_sample_bit_is_zero() {
    let doc = Document::with_version("1.5");
    // 8x1, bits 1010 1010: even x painted? no — bit 1 means "leave alone".
    let stream = Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 8, "Height" => 1, "ImageMask" => true, "BitsPerComponent" => 1,
        },
        vec![0b1010_1010u8],
    );
    let img = extract_image(&doc, &stream, 0xFF00_FF00, &no_cs()).expect("stencil must decode");
    assert_eq!((img.w, img.h), (8, 1));
    let a = alphas(&img);
    for x in 0..8 {
        let bit = (0b1010_1010u8 >> (7 - x)) & 1;
        let want = if bit == 0 { 255 } else { 0 };
        assert_eq!(a[x], want, "x={x}: sample bit {bit} must give alpha {want}");
    }
    // The painted pixels carry the fill colour, not black.
    let px = &img.data[4..8];
    assert_eq!((px[0], px[1], px[2]), (0, 255, 0), "painted stencil pixels take the fill colour");
}

/// §8.9.6.2 + §8.9.5.2: `/Decode [1 0]` inverts a stencil, so exactly the
/// complementary set of pixels is painted. Inverting it twice (or not at all) is
/// the classic "the mask came out negative" bug.
#[test]
fn stencil_image_mask_decode_one_zero_inverts_exactly_once() {
    let doc = Document::with_version("1.5");
    let mk = |inverted: bool| {
        let mut d = dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 8, "Height" => 1, "ImageMask" => true, "BitsPerComponent" => 1,
        };
        if inverted {
            d.set("Decode", vec![1.into(), 0.into()]);
        }
        Stream::new(d, vec![0b1010_1010u8])
    };
    let plain = alphas(&extract_image(&doc, &mk(false), 0xFF00_0000, &no_cs()).unwrap());
    let inv = alphas(&extract_image(&doc, &mk(true), 0xFF00_0000, &no_cs()).unwrap());
    for x in 0..8 {
        assert_ne!(
            plain[x], inv[x],
            "x={x}: /Decode [1 0] must flip coverage, got {} both ways", plain[x]
        );
    }
    assert!(plain.iter().any(|&v| v == 255) && inv.iter().any(|&v| v == 255), "both must paint something");
}

/// §8.9.5.4: an `/SMask` sample IS the alpha, so sample 0 is transparent.
#[test]
fn smask_sample_value_becomes_the_alpha_channel() {
    let mut doc = Document::with_version("1.5");
    let smask_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 2, "Height" => 1,
            "ColorSpace" => "DeviceGray", "BitsPerComponent" => 8,
        },
        vec![0x00, 0xFF],
    ));
    let stream = Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 2, "Height" => 1,
            "ColorSpace" => "DeviceGray", "BitsPerComponent" => 8,
            "SMask" => smask_id,
        },
        vec![0xFF, 0xFF],
    );
    let img = extract_image(&doc, &stream, 0xFF00_0000, &no_cs()).expect("image must decode");
    assert_eq!(alphas(&img), vec![0, 255], "/SMask sample 0 is transparent, 255 is opaque");
}

/// §8.9.6.4 vs §8.9.6.5: an explicit `/Mask` stencil has the OPPOSITE polarity
/// to an `/SMask`. For the same low/high sample pair, `/SMask` gives alpha
/// (0, 255) while `/Mask` gives (255, 0) — sample 1 means "masked OUT". Getting
/// this backwards makes masked images render as their own negative.
#[test]
fn explicit_mask_polarity_is_the_opposite_of_smask() {
    let mut doc = Document::with_version("1.5");
    // Stencil bits 0,1 for x=0,1 -> masked out where the bit is 1.
    let mask_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 2, "Height" => 1, "ImageMask" => true, "BitsPerComponent" => 1,
        },
        vec![0b0100_0000u8],
    ));
    let stream = Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 2, "Height" => 1,
            "ColorSpace" => "DeviceGray", "BitsPerComponent" => 8,
            "Mask" => mask_id,
        },
        vec![0xFF, 0xFF],
    );
    let img = extract_image(&doc, &stream, 0xFF00_0000, &no_cs()).expect("image must decode");
    assert_eq!(
        alphas(&img), vec![255, 0],
        "/Mask stencil bit 0 keeps the pixel and bit 1 removes it — the mirror of /SMask"
    );
}

/// §8.9.6.4: a colour-key `/Mask` array makes every pixel whose components all
/// fall in the given ranges transparent, and leaves the others alone.
#[test]
fn color_key_mask_makes_only_in_range_pixels_transparent() {
    let doc = Document::with_version("1.5");
    let stream = Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 3, "Height" => 1,
            "ColorSpace" => "DeviceGray", "BitsPerComponent" => 8,
            // Mask out samples in [0, 8] only.
            "Mask" => vec![0.into(), 8.into()],
        },
        vec![0x00, 0x80, 0xFF],
    );
    let img = extract_image(&doc, &stream, 0xFF00_0000, &no_cs()).expect("image must decode");
    assert_eq!(
        alphas(&img), vec![0, 255, 255],
        "only the sample inside the colour-key range becomes transparent"
    );
}

// ===========================================================================
// 8. Filters — §7.4

/// §7.4: a Flate stream truncated mid-way must yield the PARTIAL content it did
/// manage to inflate. Returning nothing turns a slightly-damaged page into a
/// blank one, which is the difference between "mostly readable" and "broken".
#[test]
fn truncated_flate_stream_yields_partial_content_not_nothing() {
    let plain: Vec<u8> = (0u8..250).cycle().take(4000).collect();
    let full = flate(&plain);
    assert!(full.len() > 40, "need a stream long enough to truncate meaningfully");
    let cut = &full[..full.len() * 2 / 3];
    let doc = Document::with_version("1.5");
    let got = crate::filters::decode_flate(cut);
    let got = got.expect("a truncated Flate stream must still return what inflated");
    assert!(
        !got.is_empty() && got.len() < plain.len(),
        "expected a partial inflate, got {} of {} bytes", got.len(), plain.len()
    );
    assert_eq!(&got[..64], &plain[..64], "the recovered prefix must be byte-correct");
    // And the same through the dictionary-driven chain the image/content layer uses.
    let dict = dictionary! { "Filter" => "FlateDecode" };
    let via_chain = crate::filters::decode_stream_chain(
        cut.to_vec(),
        &crate::filters::filter_specs_from_dict(&doc, &dict),
        &doc,
    )
    .expect("the filter chain must also surface partial output");
    assert_eq!(via_chain, got);
}

/// §7.4.4.4: the PNG predictor's row length is
/// `ceil(Columns * Colors * BitsPerComponent / 8)`. The round-1 bug computed it
/// as `bytes-per-pixel * Columns`, which is only correct at BPC >= 8 — every
/// sub-byte image decoded as garbage. Drive a real 1-bpc image end to end.
#[test]
fn png_predictor_row_length_is_correct_at_one_bit_per_component() {
    let doc = Document::with_version("1.5");
    // 16 columns at 1 bpc, 1 colour => 2 bytes per row.
    // Row 0: filter 0 (None)  data FF 00  -> 8 white then 8 black
    // Row 1: filter 2 (Up)    data 00 FF  -> FF, FF => all white
    let raw = vec![0u8, 0xFF, 0x00, 2u8, 0x00, 0xFF];
    let stream = Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 16, "Height" => 2,
            "ColorSpace" => "DeviceGray", "BitsPerComponent" => 1,
            "Filter" => "FlateDecode",
            "DecodeParms" => dictionary! {
                "Predictor" => 15, "Colors" => 1, "BitsPerComponent" => 1, "Columns" => 16,
            },
        },
        flate(&raw),
    );
    let img = extract_image(&doc, &stream, 0xFF00_0000, &no_cs()).expect("1-bpc predictor image must decode");
    assert_eq!((img.w, img.h), (16, 2));
    let lum = |x: u32, y: u32| img.data[((y * img.w + x) * 4) as usize];
    assert_eq!(lum(0, 0), 255, "row 0 left half is white");
    assert_eq!(lum(8, 0), 0, "row 0 right half is black — the row is 2 bytes, not 16");
    assert_eq!(lum(0, 1), 255, "row 1 came from the Up filter over a correct 2-byte row");
    assert_eq!(lum(8, 1), 255);
}

/// §7.4.4.2: an LZW stream that just stops, with no EOD (257) marker, must keep
/// everything decoded so far. Encoded here with literal codes only, so the
/// expected output is exact.
#[test]
fn lzw_stream_without_an_eod_marker_keeps_what_it_decoded() {
    /// 9-bit LZW: ClearTable then one literal code per byte. No EOD emitted.
    fn lzw_literals(bytes: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        let mut acc: u32 = 0;
        let mut nbits: u32 = 0;
        for code in std::iter::once(256u32).chain(bytes.iter().map(|&b| b as u32)) {
            acc = (acc << 9) | code;
            nbits += 9;
            while nbits >= 8 {
                nbits -= 8;
                out.push((acc >> nbits) as u8);
            }
        }
        if nbits > 0 {
            out.push((acc << (8 - nbits)) as u8);
        }
        out
    }
    let plain = b"1 0 0 rg 10 10 50 50 re f";
    let got = crate::filters::decode_lzw(&lzw_literals(plain), true)
        .expect("an LZW stream with no EOD must still decode");
    assert_eq!(&got[..], &plain[..], "every literal code before the missing EOD must survive");
}

/// §7.4 end to end: a page whose content stream is a truncated Flate must still
/// render the operators that did survive, rather than becoming a blank page.
#[test]
fn truncated_flate_content_stream_still_renders_its_leading_operators() {
    let mut doc = Document::with_version("1.5");
    let mut ops = vec![Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()])];
    for i in 0..300 {
        ops.extend(rect_ops(10 + (i % 50) * 2, 10, 5, 5));
    }
    let plain = Content { operations: ops }.encode().unwrap();
    let full = flate(&plain);
    let cut = full[..full.len() * 3 / 4].to_vec();
    let cid = doc.add_object(Stream::new(dictionary! { "Filter" => "FlateDecode" }, cut));
    let mut page = dictionary! {};
    let page_id = assemble_with_contents(
        &mut doc,
        Object::Reference(cid),
        dictionary! {},
        &mut page,
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let fills = count(&page.prims, |p| matches!(p, Prim::Fill { .. }));
    assert!(
        fills > 0,
        "a content stream truncated at 75% must still render its leading operators, \
         not collapse to a blank page (got 0 fills of ~300)"
    );
}

// ===========================================================================
// 9. Text metrics — §9.4.4, §9.6.2, §9.3.3

/// §9.6.2.1: `/FirstChar` may be an indirect reference. The round-1 bug treated
/// an unresolved `/FirstChar` as 0, shifting every width in the array by 32 and
/// making all text overlap or fly apart.
#[test]
fn indirect_first_char_still_aligns_the_widths_array() {
    let mut doc = Document::with_version("1.5");
    let fc = doc.add_object(Object::Integer(65));
    let font = dictionary! {
        "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
        "FirstChar" => fc,
        "LastChar" => 66,
        "Widths" => vec![600.into(), 700.into()],
    };
    let fi = font_info(&doc, &font);
    let a = fi.widths.get(&0x41).copied();
    let b = fi.widths.get(&0x42).copied();
    assert!(
        a.map(|w| (w - 0.6).abs() < 1e-6).unwrap_or(false),
        "/Widths[0] must land on 'A' (65) when /FirstChar is indirect; got {a:?}"
    );
    assert!(
        b.map(|w| (w - 0.7).abs() < 1e-6).unwrap_or(false),
        "/Widths[1] must land on 'B' (66); got {b:?}"
    );
    assert!(
        !fi.widths.contains_key(&0),
        "an unresolved /FirstChar defaulting to 0 is the bug — code 0 must not be mapped"
    );
}

/// §9.4.3: a POSITIVE number in a `TJ` array moves the next glyph LEFT (the
/// value is subtracted from the displacement). Getting the sign wrong makes
/// kerned and justified text spread out instead of tightening up.
#[test]
fn positive_tj_number_moves_the_next_glyph_left() {
    let mut doc = Document::with_version("1.5");
    let font_id = doc.add_object(dictionary! {
        "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
    });
    let ops = vec![
        Operation::new("BT", vec![]),
        Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
        Operation::new("Td", vec![100.into(), 700.into()]),
        Operation::new(
            "TJ",
            vec![Object::Array(vec![
                Object::string_literal("A"),
                1000.into(), // one full em to the LEFT
                Object::string_literal("B"),
            ])],
        ),
        Operation::new("ET", vec![]),
    ];
    let page_id = page_from_ops(&mut doc, ops, dictionary! { "Font" => dictionary! { "F1" => font_id } });

    let page = interpret_page(&doc, page_id).expect("interpret");
    let xs: Vec<f32> = page
        .prims
        .iter()
        .filter_map(|p| match p {
            Prim::Text { x, text, .. } if !text.trim().is_empty() => Some(*x),
            _ => None,
        })
        .collect();
    assert!(xs.len() >= 2, "both glyphs must be emitted, got {xs:?}");
    assert!(
        xs[1] < xs[0],
        "TJ 1000 at size 12 subtracts a full 12pt em, so 'B' must sit LEFT of 'A': {xs:?}"
    );
}

/// §9.3.3: word spacing (`Tw`) applies to the single-byte code 32 and to nothing
/// else — not to a 2-byte code 32 in a composite font, and not to NBSP (0xA0).
/// Applying it to a CID whose low byte is 32 tears CJK text apart.
#[test]
fn word_spacing_applies_only_to_the_single_byte_code_32() {
    let mut doc = Document::with_version("1.5");
    let simple = dictionary! {
        "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
        "FirstChar" => 32, "LastChar" => 160,
        // Uniform 500/1000 so any advance difference is purely Tw.
        "Widths" => Object::Array(vec![500.into(); 129]),
    };
    let desc = doc.add_object(dictionary! {
        "Type" => "Font", "Subtype" => "CIDFontType2", "BaseFont" => "Sub",
        "CIDSystemInfo" => dictionary! {
            "Registry" => Object::string_literal("Adobe"),
            "Ordering" => Object::string_literal("Identity"),
            "Supplement" => 0,
        },
        "DW" => 500,
    });
    let composite = dictionary! {
        "Type" => "Font", "Subtype" => "Type0", "BaseFont" => "Sub",
        "Encoding" => "Identity-H",
        "DescendantFonts" => vec![desc.into()],
    };

    let mut fonts = HashMap::new();
    fonts.insert(b"S".to_vec(), font_info(&doc, &simple));
    let cf = font_info(&doc, &composite);
    assert!(cf.two_byte, "Identity-H must be recognised as a 2-byte encoding");
    fonts.insert(b"C".to_vec(), cf);

    let advance = |key: &[u8], bytes: &[u8], tw: f64| -> f64 {
        let gs = GraphicsState {
            font_key: key.to_vec(),
            font_size: 10.0,
            word_spacing: tw,
            ..Default::default()
        };
        let mut prims = Vec::new();
        show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, bytes, 0)
    };

    let d_space = advance(b"S", b" ", 7.0) - advance(b"S", b" ", 0.0);
    assert!(
        (d_space - 7.0).abs() < 1e-4,
        "Tw must add 7 units to a single-byte space, added {d_space}"
    );
    let d_nbsp = advance(b"S", b"\xA0", 7.0) - advance(b"S", b"\xA0", 0.0);
    assert!(
        d_nbsp.abs() < 1e-4,
        "Tw must not apply to NBSP (0xA0), it changed the advance by {d_nbsp}"
    );
    let d_cid = advance(b"C", b"\x00\x20", 7.0) - advance(b"C", b"\x00\x20", 0.0);
    assert!(
        d_cid.abs() < 1e-4,
        "§9.3.3: Tw must not apply to a 2-byte code 32 in a composite font, \
         it changed the advance by {d_cid}"
    );
}

/// §11.6.5.2: the mask value must be passed through the soft mask's `/TR`
/// transfer function before use. An INVERTING `/TR` is the standard idiom for
/// "mask out where the group is bright", so ignoring it does not soften the
/// result — it hides exactly the wrong half of the content.
///
/// End-to-end plumbing check, because every individual piece of this chain
/// already exists and only the link between them is missing, which is precisely
/// the failure mode that reading code cannot catch: `functions::read_transfer_lut`
/// samples the LUT correctly (tested in functions.rs), `interpret.rs` stores it
/// into `GraphicsState.tr` and carries it into `SoftMask`/`MaskKey`, `model.rs`
/// declares `Prim::SoftMaskTransfer`, `wire.rs` can serialise it as tag 13, and
/// the Kotlin decoder can parse tag 13 — but nothing ever CONSTRUCTS the prim, so
/// the LUT is computed, threaded through three structs and then dropped.
#[test]
fn soft_mask_transfer_function_reaches_the_primitive_stream() {
    let mut doc = Document::with_version("1.7");
    let mask_content = Content {
        operations: vec![
            Operation::new("rg", vec![1.0.into(), 1.0.into(), 1.0.into()]),
            Operation::new("re", vec![0.into(), 0.into(), 200.into(), 200.into()]),
            Operation::new("f", vec![]),
        ],
    };
    let mask_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 200.into(), 200.into()],
            "Group" => dictionary! { "S" => "Transparency" },
        },
        mask_content.encode().unwrap(),
    ));
    // { 1 exch sub } — the canonical inverter, so this is unambiguously non-identity.
    let tr_id = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 4,
            "Domain" => vec![0.into(), 1.into()],
            "Range" => vec![0.into(), 1.into()],
        },
        b"{ 1 exch sub }".to_vec(),
    ));
    let gs_id = doc.add_object(dictionary! {
        "SMask" => dictionary! {
            "S" => "Luminosity",
            "G" => Object::Reference(mask_id),
            "TR" => Object::Reference(tr_id),
        },
    });
    let ops = vec![
        Operation::new("gs", vec![Object::Name(b"GS1".to_vec())]),
        Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
        Operation::new("re", vec![10.into(), 10.into(), 50.into(), 50.into()]),
        Operation::new("f", vec![]),
    ];
    let page_id = page_from_ops(
        &mut doc,
        ops,
        dictionary! { "ExtGState" => dictionary! { "GS1" => gs_id } },
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let push = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::SoftMaskPush { .. }))
        .expect("a soft-mask bracket must be emitted");
    let pop = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::SoftMaskPop))
        .expect("the soft-mask bracket must be closed");
    let lut = page.prims[push..pop].iter().find_map(|p| match p {
        Prim::SoftMaskTransfer(lut) => Some(lut.clone()),
        _ => None,
    });
    let lut = lut.expect(
        "§11.6.5.2: a soft mask with a non-identity /TR must emit a \
         Prim::SoftMaskTransfer inside its bracket, or the transfer function is \
         silently ignored and an inverting /TR hides the wrong half of the page",
    );
    assert_eq!(lut[0], 255, "the inverting /TR must survive to the renderer: 0 -> 255");
    assert_eq!(lut[255], 0, "and 255 -> 0");
}

/// A soft mask carrying a `/TR` must still produce a well-formed bracket. Pinned
/// separately from the test above so that if `/TR` is genuinely unsupported the
/// failure is "the transfer was dropped", not "the whole mask was dropped" —
/// those are very different bugs and should not share one assertion.
#[test]
fn soft_mask_with_a_transfer_function_still_brackets_its_content() {
    let mut doc = Document::with_version("1.7");
    let mask_content = Content {
        operations: vec![
            Operation::new("rg", vec![1.0.into(), 1.0.into(), 1.0.into()]),
            Operation::new("re", vec![0.into(), 0.into(), 200.into(), 200.into()]),
            Operation::new("f", vec![]),
        ],
    };
    let mask_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 200.into(), 200.into()],
            "Group" => dictionary! { "S" => "Transparency" },
        },
        mask_content.encode().unwrap(),
    ));
    let tr_id = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 4,
            "Domain" => vec![0.into(), 1.into()],
            "Range" => vec![0.into(), 1.into()],
        },
        b"{ 1 exch sub }".to_vec(),
    ));
    let gs_id = doc.add_object(dictionary! {
        "SMask" => dictionary! {
            "S" => "Luminosity",
            "G" => Object::Reference(mask_id),
            "TR" => Object::Reference(tr_id),
        },
    });
    let ops = vec![
        Operation::new("gs", vec![Object::Name(b"GS1".to_vec())]),
        Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
        Operation::new("re", vec![10.into(), 10.into(), 50.into(), 50.into()]),
        Operation::new("f", vec![]),
    ];
    let page_id = page_from_ops(
        &mut doc,
        ops,
        dictionary! { "ExtGState" => dictionary! { "GS1" => gs_id } },
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let kind = |want: fn(&Prim) -> bool| page.prims.iter().position(want);
    let push = kind(|p| matches!(p, Prim::SoftMaskPush { .. })).expect("SoftMaskPush");
    let sep = kind(|p| matches!(p, Prim::SoftMaskContent)).expect("SoftMaskContent");
    let pop = kind(|p| matches!(p, Prim::SoftMaskPop)).expect("SoftMaskPop");
    assert!(push < sep && sep < pop, "a /TR must not break the bracket ordering");
    let masked_fill = page.prims[push..sep]
        .iter()
        .any(|p| matches!(p, Prim::Fill { argb, .. } if (*argb & 0x00FF_FFFF) == 0x00FF_0000));
    assert!(masked_fill, "the red fill must still sit inside the mask bracket");
}

/// §9.6.5: a Type 3 glyph's CharProc is its own content stream, so an inline
/// image in one must not cost the glyph its other content. Covers the
/// `draw.rs` CharProc decode site.
#[test]
fn inline_image_inside_a_type3_charproc_keeps_the_rest_of_the_glyph() {
    let mut doc = Document::with_version("1.5");
    let mut proc_bytes: Vec<u8> = b"q 400 0 0 400 0 0 cm\nBI /W 2 /H 2 /CS /G ID ".to_vec();
    proc_bytes.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    proc_bytes.extend_from_slice(b" EI\nQ\n0 0 700 700 re f\n");
    let proc_id = doc.add_object(Stream::new(dictionary! {}, proc_bytes));
    let char_procs = doc.add_object(dictionary! { "a" => proc_id });
    let encoding = doc.add_object(dictionary! {
        "Type" => "Encoding",
        "Differences" => vec![65.into(), "a".into()],
    });
    let font = dictionary! {
        "Type" => "Font", "Subtype" => "Type3",
        "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
        "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
        "CharProcs" => char_procs, "Encoding" => encoding,
        "FirstChar" => 65, "LastChar" => 65, "Widths" => vec![700.into()],
        "Resources" => dictionary! {},
    };
    let mut fonts = HashMap::new();
    fonts.insert(b"F1".to_vec(), font_info(&doc, &font));
    let gs = GraphicsState {
        font_key: b"F1".to_vec(),
        font_size: 100.0,
        ..Default::default()
    };
    let mut prims = Vec::new();
    show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"A", 0);

    assert!(
        prims.iter().any(|p| matches!(p, Prim::Fill { .. })),
        "the CharProc's own rectangle, drawn AFTER the inline image, must still \
         paint — one BI must not blank the whole glyph"
    );
    assert!(
        prims.iter().any(|p| matches!(p, Prim::Image { w: 2, h: 2, .. })),
        "the inline image inside the CharProc must decode"
    );
}

/// §9.6.5 + §8.5.4: the per-glyph primitive bound must cut only where the glyph's
/// own brackets are CLOSED — a blind `truncate` could sever a ClipPush from its
/// ClipPop and unbalance the renderer's save/restore stack for the whole rest of
/// the page. But "cut at a balanced point" must not degenerate into "drop the
/// glyph": a CharProc that wraps ALL its drawing in one `q … W n … Q` (the natural
/// way to bound a glyph) has NO balanced point before the cap, so searching
/// backwards for one finds only the start and the glyph vanishes entirely.
///
/// The contract that satisfies both: keep the capped prims and CLOSE the brackets
/// left open, rather than discarding everything back to the last balanced point.
#[test]
fn type3_per_glyph_cap_keeps_the_glyph_and_stays_balanced() {
    let mut doc = Document::with_version("1.5");
    let mut proc_src = String::from("q\n0 0 700 700 re W n\n");
    // Well past MAX_TYPE3_PRIMS_PER_GLYPH, so the cap lands inside the clip — and
    // the clip closes only at the very end, so there is no earlier balanced point.
    let wanted = MAX_TYPE3_PRIMS_PER_GLYPH * 3;
    for i in 0..wanted {
        let y = (i % 600) as i64;
        proc_src.push_str(&format!("0 {y} 10 10 re f\n"));
    }
    proc_src.push_str("Q\n");
    let proc_id = doc.add_object(Stream::new(dictionary! {}, proc_src.into_bytes()));
    let char_procs = doc.add_object(dictionary! { "a" => proc_id });
    let encoding = doc.add_object(dictionary! {
        "Type" => "Encoding",
        "Differences" => vec![65.into(), "a".into()],
    });
    let font = dictionary! {
        "Type" => "Font", "Subtype" => "Type3",
        "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
        "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
        "CharProcs" => char_procs, "Encoding" => encoding,
        "FirstChar" => 65, "LastChar" => 65, "Widths" => vec![700.into()],
        "Resources" => dictionary! {},
    };
    let mut fonts = HashMap::new();
    fonts.insert(b"F1".to_vec(), font_info(&doc, &font));
    let gs = GraphicsState {
        font_key: b"F1".to_vec(),
        font_size: 100.0,
        ..Default::default()
    };
    let mut prims = Vec::new();
    // Three glyphs, so a mis-cut on the first corrupts the two that follow.
    show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"AAA", 0);

    let fills = count(&prims, |p| matches!(p, Prim::Fill { .. }));
    println!(
        "type3 per-glyph cap: {} prims, {fills} fills for 3 glyphs \
         (MAX_TYPE3_PRIMS_PER_GLYPH={MAX_TYPE3_PRIMS_PER_GLYPH})",
        prims.len()
    );
    assert!(
        fills > 0,
        "the glyph vanished entirely. The per-glyph cut searched backwards for a \
         balanced point and found none, because this CharProc's clip closes only at \
         the very end — so it discarded all {wanted} prims instead of the surplus. \
         Cut at the cap and append the closers for the brackets still open."
    );
    assert!(
        fills < wanted * 3,
        "the per-glyph bound must actually bind, or this proves nothing"
    );
    let mut clip = 0i32;
    let mut group = 0i32;
    for (i, p) in prims.iter().enumerate() {
        match p {
            Prim::ClipPush { .. } => clip += 1,
            Prim::ClipPop => {
                clip -= 1;
                assert!(
                    clip >= 0,
                    "prim {i}: a ClipPop with no matching ClipPush — the per-glyph cut \
                     severed a bracket and the canvas will over-restore"
                );
            }
            Prim::GroupPush { .. } => group += 1,
            Prim::GroupPop => {
                group -= 1;
                assert!(group >= 0, "prim {i}: unmatched GroupPop");
            }
            _ => {}
        }
    }
    assert_eq!(
        clip, 0,
        "{clip} clip level(s) left open by the per-glyph cut — everything after this \
         glyph on the page is clipped away"
    );
    assert_eq!(group, 0, "{group} group level(s) left open by the per-glyph cut");
}

/// The same per-glyph cap, but with a TRANSPARENCY GROUP open at the cut, not just
/// a clip. Closing the open brackets means synthesising a `GroupPop` and a
/// `ClipPop` in the right order; my sibling test above only exercises the clip
/// branch, so a fix that handled `ClipPush` and forgot `GroupPush` would pass it
/// and leave a `saveLayer` unbalanced for the rest of the page — which is worse
/// than an unbalanced clip, because it leaks a whole compositing layer.
#[test]
fn type3_per_glyph_cap_closes_group_brackets_too() {
    let mut doc = Document::with_version("1.7");
    let wanted = MAX_TYPE3_PRIMS_PER_GLYPH * 3;
    let mut form_src = String::from("1 0 0 rg\n");
    for i in 0..wanted {
        let y = (i % 600) as i64;
        form_src.push_str(&format!("0 {y} 10 10 re f\n"));
    }
    // A transparency group, so `Do` emits GroupPush inside the form's BBox clip.
    let form_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 700.into(), 700.into()],
            "Group" => dictionary! { "Type" => "Group", "S" => "Transparency", "I" => true },
        },
        form_src.into_bytes(),
    ));
    let proc_id = doc.add_object(Stream::new(
        dictionary! {},
        b"q /Fm0 Do Q\n".to_vec(),
    ));
    let char_procs = doc.add_object(dictionary! { "a" => proc_id });
    let encoding = doc.add_object(dictionary! {
        "Type" => "Encoding",
        "Differences" => vec![65.into(), "a".into()],
    });
    let font = dictionary! {
        "Type" => "Font", "Subtype" => "Type3",
        "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
        "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
        "CharProcs" => char_procs, "Encoding" => encoding,
        "FirstChar" => 65, "LastChar" => 65, "Widths" => vec![700.into()],
        "Resources" => dictionary! { "XObject" => dictionary! { "Fm0" => form_id } },
    };
    let mut fonts = HashMap::new();
    fonts.insert(b"F1".to_vec(), font_info(&doc, &font));
    let gs = GraphicsState {
        font_key: b"F1".to_vec(),
        font_size: 100.0,
        ..Default::default()
    };
    let mut prims = Vec::new();
    show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"AA", 0);

    let groups = count(&prims, |p| matches!(p, Prim::GroupPush { .. }));
    println!(
        "type3 group cap: {} prims, {} fills, {groups} GroupPush for 2 glyphs",
        prims.len(),
        count(&prims, |p| matches!(p, Prim::Fill { .. }))
    );
    assert!(
        groups > 0,
        "precondition: the CharProc's form must open a transparency group, else this \
         test exercises the same branch as its sibling"
    );
    assert!(
        prims.iter().any(|p| matches!(p, Prim::Fill { .. })),
        "the capped glyph must still draw something"
    );
    let mut clip = 0i32;
    let mut group = 0i32;
    for (i, p) in prims.iter().enumerate() {
        match p {
            Prim::ClipPush { .. } => clip += 1,
            Prim::ClipPop => {
                clip -= 1;
                assert!(clip >= 0, "prim {i}: unmatched ClipPop");
            }
            Prim::GroupPush { .. } => group += 1,
            Prim::GroupPop => {
                group -= 1;
                assert!(group >= 0, "prim {i}: unmatched GroupPop would over-restore a saveLayer");
            }
            _ => {}
        }
    }
    assert_eq!(
        group, 0,
        "{group} transparency group(s) left open by the per-glyph cut — a leaked \
         saveLayer composites the whole rest of the page through this glyph's group"
    );
    assert_eq!(clip, 0, "{clip} clip level(s) left open by the per-glyph cut");
}

/// The per-glyph cap with a SOFT MASK in play. `interp2` reports the
/// `SoftMaskPop` arm of the closer synthesis is deliberately not implemented,
/// because a soft-mask bracket cannot be closed after the fact — so the question
/// this test settles is not "does the arm work" but "can a cut orphan a soft-mask
/// bracket at all". Consecutive fills under one `gs` coalesce into a single
/// bracket, so a cut landing inside one is at least plausible.
///
/// Either outcome is informative: passing documents that soft-mask brackets are
/// emitted retroactively around a completed range and therefore cannot be open at
/// the cut (making the missing arm unreachable rather than a hole); failing means a
/// capped glyph leaks a soft mask over the rest of the page.
#[test]
fn type3_per_glyph_cap_cannot_orphan_a_soft_mask_bracket() {
    // Build a Type 3 font whose CharProc paints `fills` rectangles under an
    // ExtGState soft mask, and return the prims one glyph emits.
    let run = |fills: usize| -> Vec<Prim> {
        let mut doc = Document::with_version("1.7");
        let mask_content = Content {
            operations: vec![
                Operation::new("rg", vec![1.0.into(), 1.0.into(), 1.0.into()]),
                Operation::new("re", vec![0.into(), 0.into(), 700.into(), 700.into()]),
                Operation::new("f", vec![]),
            ],
        };
        let mask_id = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Form",
                "BBox" => vec![0.into(), 0.into(), 700.into(), 700.into()],
                "Group" => dictionary! { "S" => "Transparency" },
            },
            mask_content.encode().unwrap(),
        ));
        let gs_id = doc.add_object(dictionary! {
            "SMask" => dictionary! { "S" => "Luminosity", "G" => Object::Reference(mask_id) },
        });
        let mut proc_src = String::from("q\n/GS1 gs\n1 0 0 rg\n");
        for i in 0..fills {
            let y = (i % 600) as i64;
            proc_src.push_str(&format!("0 {y} 10 10 re f\n"));
        }
        proc_src.push_str("Q\n");
        let proc_id = doc.add_object(Stream::new(dictionary! {}, proc_src.into_bytes()));
        let char_procs = doc.add_object(dictionary! { "a" => proc_id });
        let encoding = doc.add_object(dictionary! {
            "Type" => "Encoding",
            "Differences" => vec![65.into(), "a".into()],
        });
        let font = dictionary! {
            "Type" => "Font", "Subtype" => "Type3",
            "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
            "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
            "CharProcs" => char_procs, "Encoding" => encoding,
            "FirstChar" => 65, "LastChar" => 65, "Widths" => vec![700.into()],
            "Resources" => dictionary! { "ExtGState" => dictionary! { "GS1" => gs_id } },
        };
        let mut fonts = HashMap::new();
        fonts.insert(b"F1".to_vec(), font_info(&doc, &font));
        let gs = GraphicsState {
            font_key: b"F1".to_vec(),
            font_size: 100.0,
            ..Default::default()
        };
        let mut prims = Vec::new();
        show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"A", 0);
        prims
    };

    // Precondition: WELL under the cap, the fixture must genuinely emit a soft-mask
    // bracket. Without this, a zero-prim result below could just mean the mask was
    // never set up and the test would be asserting nothing.
    let small = run(4);
    assert!(
        count(&small, |p| matches!(p, Prim::SoftMaskPush { .. })) > 0,
        "precondition: an uncapped glyph must emit a SoftMaskPush, else this test \
         cannot say anything about orphaning one"
    );
    assert!(
        small.iter().any(|p| matches!(p, Prim::Fill { .. })),
        "precondition: an uncapped glyph must draw"
    );

    let prims = run(MAX_TYPE3_PRIMS_PER_GLYPH * 3);
    let pushes = count(&prims, |p| matches!(p, Prim::SoftMaskPush { .. }));
    let pops = count(&prims, |p| matches!(p, Prim::SoftMaskPop));
    println!(
        "type3 soft-mask cap: {} prims, {} fills, {pushes} SoftMaskPush / {pops} SoftMaskPop",
        prims.len(),
        count(&prims, |p| matches!(p, Prim::Fill { .. }))
    );
    assert_eq!(
        pushes, pops,
        "a capped glyph left {pushes} SoftMaskPush against {pops} SoftMaskPop — an \
         orphaned soft mask composites the rest of the page through this glyph's mask"
    );
    assert!(
        prims.iter().any(|p| matches!(p, Prim::Fill { .. })),
        "the capped glyph vanished entirely. Avoiding an orphaned soft mask by cutting \
         BEFORE the bracket degenerates when the mask opens at the start of the glyph, \
         which is the normal shape for `q /GS1 gs <draw> Q` — there is nothing before \
         it to keep. A glyph missing from the page is a worse regression than a glyph \
         drawn without its mask."
    );
}

// ===========================================================================
// 10. Shading and patterns — §8.7

fn tiling_pattern(doc: &mut Document, xstep: f64, ystep: f64) -> ObjectId {
    let tile = Content {
        operations: vec![
            Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
            Operation::new("re", vec![0.into(), 0.into(), 8.into(), 8.into()]),
            Operation::new("f", vec![]),
        ],
    };
    doc.add_object(Stream::new(
        dictionary! {
            "Type" => "Pattern", "PatternType" => 1, "PaintType" => 1, "TilingType" => 1,
            "BBox" => vec![0.into(), 0.into(), 10.into(), 10.into()],
            "XStep" => xstep, "YStep" => ystep,
            "Resources" => dictionary! {},
        },
        tile.encode().unwrap(),
    ))
}

fn fill_region(x0: f64, y0: f64, x1: f64, y1: f64) -> Vec<Vec<(f64, f64)>> {
    vec![vec![(x0, y0), (x1, y0), (x1, y1), (x0, y1)]]
}

/// §8.7.3.1: a tiling pattern replicates across the WHOLE fill region on the
/// `/XStep` x `/YStep` lattice. A small step over a large area must tile it, not
/// paint a single cell in one corner.
///
/// Asserted on coverage rather than on prim counts, because the representation is
/// an implementation choice: a non-overlapping periodic pattern may collapse to a
/// single `ImageTiled` cell raster instead of one Fill per tile. What must hold
/// either way is that ink reaches every corner of the region.
#[test]
fn tiling_pattern_with_a_small_step_covers_the_whole_region() {
    let mut doc = Document::with_version("1.5");
    let pid = tiling_pattern(&mut doc, 10.0, 10.0);
    let region = fill_region(0.0, 0.0, 200.0, 200.0);
    let mut prims = Vec::new();
    paint_pattern_fill(
        &doc, pid, &region, false, &IDENTITY, 0xFF00_0000, 1.0, BlendMode::Normal,
        &mut prims, 0, 0,
    );
    assert!(
        prims.iter().any(is_ink),
        "the pattern must paint something at all"
    );
    assert!(
        ink_in_region(&prims, 0.0, 0.0, 20.0, 20.0),
        "the origin corner must be tiled"
    );
    assert!(
        ink_in_region(&prims, 180.0, 180.0, 201.0, 201.0),
        "the far corner must be tiled too — this is the 'pattern painted one corner' bug"
    );
}

/// §8.7.3.1: `/XStep` and `/YStep` are lattice *spacings*; a negative value is a
/// magnitude, not a direction. The round-1 bug let a negative step invert the
/// loop bounds so the pattern painted nothing at all.
#[test]
fn tiling_pattern_with_a_negative_step_still_paints() {
    let mut doc = Document::with_version("1.5");
    let pid = tiling_pattern(&mut doc, -10.0, -10.0);
    let region = fill_region(0.0, 0.0, 100.0, 100.0);
    let mut prims = Vec::new();
    paint_pattern_fill(
        &doc, pid, &region, false, &IDENTITY, 0xFF00_0000, 1.0, BlendMode::Normal,
        &mut prims, 0, 0,
    );
    assert!(
        prims.iter().any(is_ink),
        "a negative /XStep must tile by its magnitude, not paint nothing"
    );
    assert!(
        ink_in_region(&prims, 0.0, 0.0, 20.0, 20.0)
            && ink_in_region(&prims, 80.0, 80.0, 101.0, 101.0),
        "and it must cover the region, not just one corner"
    );
}

/// §8.9.5.2: a raster is placed by mapping the unit square through `ctm`, and
/// sample row 0 is the TOP of the image — it pairs with `v = 1`, not `v = 0`.
/// That convention is forced by real image XObjects, whose decoded JPEG/PNG
/// scanlines arrive top-first, so every producer of a `Prim::Image` must match it.
///
/// A SYNTHETIC raster (axial/radial shading, mesh shading, pattern cell) is
/// generated rather than decoded, so nothing forces its row order — if it writes
/// row 0 at the LOW y of its bbox while still handing back a positive-`d`
/// placement matrix, it renders vertically flipped while real images stay correct.
///
/// This is asserted on the shading's own geometry rather than against another
/// raster, which is what makes it decisive: an axial shading running black at
/// `y = 0` to white at `y = 100` has a known correct answer independent of any
/// convention I might have misread. It also catches the cancelling double-fix —
/// producer flipped AND consumer flipped looks right end-to-end but fails here.
#[test]
fn synthetic_shading_raster_pairs_row_zero_with_the_same_edge_as_a_real_image() {
    let mut doc = Document::with_version("1.5");
    let func_id = doc.add_object(dictionary! {
        "FunctionType" => 2,
        "Domain" => vec![0.into(), 1.into()],
        "C0" => vec![0.0.into(), 0.0.into(), 0.0.into()], // black at t=0
        "C1" => vec![1.0.into(), 1.0.into(), 1.0.into()], // white at t=1
        "N" => 1,
    });
    // Vertical axis: t=0 at y=0 (black), t=1 at y=100 (white).
    let sh_id = doc.add_object(dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => "DeviceRGB",
        "Coords" => vec![0.into(), 0.into(), 0.into(), 100.into()],
        "Extend" => vec![Object::Boolean(true), Object::Boolean(true)],
        "Function" => func_id,
    });
    let bytes = Content {
        operations: vec![Operation::new("sh", vec![Object::Name(b"Sh0".to_vec())])],
    }
    .encode()
    .unwrap();
    let page_id = assemble(
        &mut doc,
        bytes,
        dictionary! { "Shading" => dictionary! { "Sh0" => sh_id } },
        dictionary! { "MediaBox" => vec![0.into(), 0.into(), 100.into(), 100.into()] },
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let (ctm, w, h, data) = page
        .prims
        .iter()
        .find_map(|p| match p {
            Prim::Image { ctm, w, h, data, format: 0, .. } => Some((*ctm, *w, *h, data.clone())),
            _ => None,
        })
        .expect("the shading must emit a decoded raster");
    assert!(w > 0 && h > 1, "need at least two rows to talk about orientation");

    // Mean luminance of a raster row.
    let row_luma = |row: u32| -> f64 {
        let mut sum = 0f64;
        let mut n = 0f64;
        for x in 0..w {
            let i = ((row * w + x) * 4) as usize;
            if i + 2 < data.len() {
                sum += (data[i] as f64 + data[i + 1] as f64 + data[i + 2] as f64) / 3.0;
                n += 1.0;
            }
        }
        if n == 0.0 { 0.0 } else { sum / n }
    };
    let (top_row, bottom_row) = (row_luma(0), row_luma(h - 1));
    assert!(
        (top_row - bottom_row).abs() > 16.0,
        "the ramp must actually vary between the first and last row, else this test \
         cannot detect an inversion (row0={top_row:.0}, row{}={bottom_row:.0})",
        h - 1
    );

    // Where does each edge of the unit square land in page space?
    let y_at_v0 = transform(&ctm, 0.5, 0.0).1;
    let y_at_v1 = transform(&ctm, 0.5, 1.0).1;
    // Row 0 pairs with v = 1 (§8.9.5.2), row h-1 with v = 0.
    let (luma_at_v1, luma_at_v0) = (top_row, bottom_row);
    let (low_y_luma, high_y_luma) = if y_at_v0 < y_at_v1 {
        (luma_at_v0, luma_at_v1)
    } else {
        (luma_at_v1, luma_at_v0)
    };
    assert!(
        low_y_luma < high_y_luma,
        "the shading is vertically FLIPPED. Its function is black at t=0 (page y=0) \
         and white at t=1 (page y=100), so once placed, the low-y edge must be dark \
         and the high-y edge light. Got low-y luma {low_y_luma:.0} and high-y luma \
         {high_y_luma:.0} (raster row0={top_row:.0}, row{}={bottom_row:.0}; v=0 lands \
         at y={y_at_v0:.0}, v=1 at y={y_at_v1:.0}). Row 0 must pair with v=1, the same \
         way a decoded image's first scanline does — a synthetic raster that writes \
         row 0 at its bbox's low y while returning a positive-d matrix inverts. NOTE: \
         if this fails after BOTH the raster producer and the Kotlin decoder were \
         changed, the two flips cancelled and each looked correct in isolation.",
        h - 1
    );
}

/// The same orientation invariant for the TYPE 4 MESH producer
/// (`shading::rasterize_shading_mesh`), which is a different code path from the
/// axial one and was flipped independently. Mesh vertex colours are black along
/// `y = 0` and white along `y = 100`, so the row placed at the lower page y must
/// be the darker one — the same document-level fact, no convention assumed.
#[test]
fn type4_mesh_raster_is_not_vertically_flipped() {
    let mut doc = Document::with_version("1.5");
    // flag(1) x(1) y(1) r(1) g(1) b(1) per vertex; two triangles covering a square.
    // Decode maps x,y from 0..255 onto 0..100, so y=0 is black and y=100 is white.
    let v = |x: u8, y: u8, l: u8| -> [u8; 6] { [0, x, y, l, l, l] };
    let mut mesh: Vec<u8> = Vec::new();
    for b in [v(0, 0, 0), v(255, 0, 0), v(0, 255, 255)] {
        mesh.extend_from_slice(&b);
    }
    for b in [v(255, 0, 0), v(0, 255, 255), v(255, 255, 255)] {
        mesh.extend_from_slice(&b);
    }
    let sh_id = doc.add_object(Stream::new(
        dictionary! {
            "ShadingType" => 4,
            "ColorSpace" => "DeviceRGB",
            "BitsPerCoordinate" => 8,
            "BitsPerComponent" => 8,
            "BitsPerFlag" => 8,
            "Decode" => vec![
                0.into(), 100.into(), 0.into(), 100.into(),
                0.into(), 1.into(), 0.into(), 1.into(), 0.into(), 1.into(),
            ],
        },
        mesh,
    ));
    let bytes = Content {
        operations: vec![Operation::new("sh", vec![Object::Name(b"Sh0".to_vec())])],
    }
    .encode()
    .unwrap();
    let page_id = assemble(
        &mut doc,
        bytes,
        dictionary! { "Shading" => dictionary! { "Sh0" => sh_id } },
        dictionary! { "MediaBox" => vec![0.into(), 0.into(), 100.into(), 100.into()] },
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let (ctm, w, h, data) = page
        .prims
        .iter()
        .find_map(|p| match p {
            Prim::Image { ctm, w, h, data, format: 0, .. } => Some((*ctm, *w, *h, data.clone())),
            _ => None,
        })
        .expect("a type 4 mesh must emit a decoded raster");
    assert!(w > 0 && h > 1, "need at least two rows to talk about orientation");

    // Mean luminance over OPAQUE pixels only: a mesh does not cover its whole bbox,
    // and averaging in the transparent margin would wash the gradient out.
    let row_luma = |row: u32| -> Option<f64> {
        let (mut sum, mut n) = (0f64, 0f64);
        for x in 0..w {
            let i = ((row * w + x) * 4) as usize;
            if i + 3 < data.len() && data[i + 3] > 0 {
                sum += (data[i] as f64 + data[i + 1] as f64 + data[i + 2] as f64) / 3.0;
                n += 1.0;
            }
        }
        if n == 0.0 { None } else { Some(sum / n) }
    };
    // First and last rows that actually contain mesh.
    let first = (0..h).find(|&r| row_luma(r).is_some()).expect("some row must be covered");
    let last = (0..h).rev().find(|&r| row_luma(r).is_some()).expect("some row must be covered");
    assert!(last > first, "the mesh must cover more than one row");
    let (luma_first, luma_last) = (row_luma(first).unwrap(), row_luma(last).unwrap());
    assert!
    (
        (luma_first - luma_last).abs() > 16.0,
        "the mesh gradient must vary between its first and last covered row, else \
         this test cannot detect an inversion (row{first}={luma_first:.0}, \
         row{last}={luma_last:.0})"
    );

    // Lower row index is nearer v=1 (§8.9.5.2), so it lands wherever v=1 lands.
    let y_at_v0 = transform(&ctm, 0.5, 0.0).1;
    let y_at_v1 = transform(&ctm, 0.5, 1.0).1;
    let (low_y_luma, high_y_luma) = if y_at_v1 > y_at_v0 {
        (luma_last, luma_first)
    } else {
        (luma_first, luma_last)
    };
    assert!(
        low_y_luma < high_y_luma,
        "the type 4 mesh raster is vertically FLIPPED. Vertex colours are black along \
         page y=0 and white along y=100, so the row placed at the lower page y must be \
         darker. Got low-y luma {low_y_luma:.0}, high-y luma {high_y_luma:.0} \
         (row{first}={luma_first:.0}, row{last}={luma_last:.0}; v=0 at y={y_at_v0:.0}, \
         v=1 at y={y_at_v1:.0}). This producer is separate from the axial one and was \
         flipped independently, so fixing one does not fix the other."
    );
}

/// The same orientation invariant for the TYPE 1 FUNCTION-BASED producer
/// (`images::rasterize_shading_function_based`), the fourth and last of the
/// synthetic rasters that were mirrored. Its `/Function` returns the gray level
/// directly from the y coordinate, so black sits at page y=0 and white at y=100
/// by construction — the same document-level fact as the other three.
#[test]
fn type1_function_based_raster_is_not_vertically_flipped() {
    let mut doc = Document::with_version("1.5");
    // PostScript calculator over (x, y): drop x, scale y into 0..1 as the gray level.
    let func_id = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 4,
            "Domain" => vec![0.into(), 100.into(), 0.into(), 100.into()],
            "Range" => vec![0.into(), 1.into()],
        },
        b"{ exch pop 100 div }".to_vec(),
    ));
    let sh_id = doc.add_object(dictionary! {
        "ShadingType" => 1,
        "ColorSpace" => "DeviceGray",
        "Domain" => vec![0.into(), 100.into(), 0.into(), 100.into()],
        "Function" => func_id,
    });
    let bytes = Content {
        operations: vec![Operation::new("sh", vec![Object::Name(b"Sh0".to_vec())])],
    }
    .encode()
    .unwrap();
    let page_id = assemble(
        &mut doc,
        bytes,
        dictionary! { "Shading" => dictionary! { "Sh0" => sh_id } },
        dictionary! { "MediaBox" => vec![0.into(), 0.into(), 100.into(), 100.into()] },
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let (ctm, w, h, data) = page
        .prims
        .iter()
        .find_map(|p| match p {
            Prim::Image { ctm, w, h, data, format: 0, .. } => Some((*ctm, *w, *h, data.clone())),
            _ => None,
        })
        .expect("a type 1 function-based shading must emit a decoded raster");
    assert!(w > 0 && h > 1, "need at least two rows to talk about orientation");

    let row_luma = |row: u32| -> Option<f64> {
        let (mut sum, mut n) = (0f64, 0f64);
        for x in 0..w {
            let i = ((row * w + x) * 4) as usize;
            if i + 3 < data.len() && data[i + 3] > 0 {
                sum += (data[i] as f64 + data[i + 1] as f64 + data[i + 2] as f64) / 3.0;
                n += 1.0;
            }
        }
        if n == 0.0 { None } else { Some(sum / n) }
    };
    let first = (0..h).find(|&r| row_luma(r).is_some()).expect("some row must be covered");
    let last = (0..h).rev().find(|&r| row_luma(r).is_some()).expect("some row must be covered");
    assert!(last > first, "the shading must cover more than one row");
    let (luma_first, luma_last) = (row_luma(first).unwrap(), row_luma(last).unwrap());
    assert!(
        (luma_first - luma_last).abs() > 16.0,
        "the ramp must vary between the first and last covered row, else this test \
         cannot detect an inversion (row{first}={luma_first:.0}, row{last}={luma_last:.0})"
    );

    let y_at_v0 = transform(&ctm, 0.5, 0.0).1;
    let y_at_v1 = transform(&ctm, 0.5, 1.0).1;
    let (low_y_luma, high_y_luma) = if y_at_v1 > y_at_v0 {
        (luma_last, luma_first)
    } else {
        (luma_first, luma_last)
    };
    assert!(
        low_y_luma < high_y_luma,
        "the type 1 function-based raster is vertically FLIPPED. Its /Function returns \
         gray = y/100, so the row placed at the lower page y must be darker. Got low-y \
         luma {low_y_luma:.0}, high-y luma {high_y_luma:.0} (row{first}={luma_first:.0}, \
         row{last}={luma_last:.0}; v=0 at y={y_at_v0:.0}, v=1 at y={y_at_v1:.0})."
    );
}

/// The same orientation invariant for a RADIAL (type 3) shading. Radial shares the
/// axial pixel loop, so this is a cheap guard against a future divergence rather
/// than a separate producer — the concentric case has no vertical asymmetry, so the
/// circles are offset along y to give the raster a known top and bottom.
#[test]
fn radial_shading_raster_is_not_vertically_flipped() {
    let mut doc = Document::with_version("1.5");
    let func_id = doc.add_object(dictionary! {
        "FunctionType" => 2,
        "Domain" => vec![0.into(), 1.into()],
        "C0" => vec![0.0.into(), 0.0.into(), 0.0.into()], // black at the small circle
        "C1" => vec![1.0.into(), 1.0.into(), 1.0.into()], // white at the large one
        "N" => 1,
    });
    // Small circle low on the page, large circle high: dark at the bottom.
    let sh_id = doc.add_object(dictionary! {
        "ShadingType" => 3,
        "ColorSpace" => "DeviceRGB",
        "Coords" => vec![50.into(), 5.into(), 1.into(), 50.into(), 95.into(), 90.into()],
        "Extend" => vec![Object::Boolean(true), Object::Boolean(true)],
        "Function" => func_id,
    });
    let bytes = Content {
        operations: vec![Operation::new("sh", vec![Object::Name(b"Sh0".to_vec())])],
    }
    .encode()
    .unwrap();
    let page_id = assemble(
        &mut doc,
        bytes,
        dictionary! { "Shading" => dictionary! { "Sh0" => sh_id } },
        dictionary! { "MediaBox" => vec![0.into(), 0.into(), 100.into(), 100.into()] },
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let (ctm, w, h, data) = page
        .prims
        .iter()
        .find_map(|p| match p {
            Prim::Image { ctm, w, h, data, format: 0, .. } => Some((*ctm, *w, *h, data.clone())),
            _ => None,
        })
        .expect("a radial shading must emit a decoded raster");
    let row_luma = |row: u32| -> Option<f64> {
        let (mut sum, mut n) = (0f64, 0f64);
        for x in 0..w {
            let i = ((row * w + x) * 4) as usize;
            if i + 3 < data.len() && data[i + 3] > 0 {
                sum += (data[i] as f64 + data[i + 1] as f64 + data[i + 2] as f64) / 3.0;
                n += 1.0;
            }
        }
        if n == 0.0 { None } else { Some(sum / n) }
    };
    let first = (0..h).find(|&r| row_luma(r).is_some()).expect("some row must be covered");
    let last = (0..h).rev().find(|&r| row_luma(r).is_some()).expect("some row must be covered");
    let (luma_first, luma_last) = (row_luma(first).unwrap(), row_luma(last).unwrap());
    assert!(
        (luma_first - luma_last).abs() > 16.0,
        "the radial ramp must vary vertically for this test to mean anything \
         (row{first}={luma_first:.0}, row{last}={luma_last:.0})"
    );
    let y_at_v0 = transform(&ctm, 0.5, 0.0).1;
    let y_at_v1 = transform(&ctm, 0.5, 1.0).1;
    let (low_y_luma, high_y_luma) = if y_at_v1 > y_at_v0 {
        (luma_last, luma_first)
    } else {
        (luma_first, luma_last)
    };
    assert!(
        low_y_luma < high_y_luma,
        "the radial raster is vertically FLIPPED. Its small (black) circle sits low on \
         the page and its large (white) circle high, so the row at the lower page y \
         must be darker. Got low-y {low_y_luma:.0}, high-y {high_y_luma:.0}."
    );
}

/// only in its LOWER half must come back as a raster whose opaque rows are the
/// ones that land at the lower page y — not the upper.
#[test]
fn tiling_pattern_cell_raster_is_not_vertically_flipped() {
    let mut doc = Document::with_version("1.5");
    // Cell is 10x10; paint only y in [0,5), i.e. the bottom half.
    let cell = Content {
        operations: vec![
            Operation::new("rg", vec![0.0.into(), 0.0.into(), 0.0.into()]),
            Operation::new("re", vec![0.into(), 0.into(), 10.into(), 5.into()]),
            Operation::new("f", vec![]),
        ],
    };
    let pid = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "Pattern", "PatternType" => 1, "PaintType" => 1, "TilingType" => 1,
            "BBox" => vec![0.into(), 0.into(), 10.into(), 10.into()],
            "XStep" => 10, "YStep" => 10,
            "Resources" => dictionary! {},
        },
        cell.encode().unwrap(),
    ));
    let region = fill_region(0.0, 0.0, 100.0, 100.0);
    let mut prims = Vec::new();
    paint_pattern_fill(
        &doc, pid, &region, false, &IDENTITY, 0xFF00_0000, 1.0, BlendMode::Normal,
        &mut prims, 0, 0,
    );

    // The periodic path emits one ImageTiled cell raster; the per-tile path emits
    // Fills. Both are valid representations, so assert whichever appeared.
    let tiled = prims.iter().find_map(|p| match p {
        Prim::ImageTiled { ctm, w, h, data, .. } => Some((*ctm, *w, *h, data.clone())),
        _ => None,
    });
    if let Some((ctm, w, h, data)) = tiled {
        println!("pattern cell: RASTER path, {w}x{h} cell");
        assert!(h > 1, "need at least two rows to talk about orientation");
        let row_opaque = |row: u32| -> f64 {
            let (mut op, mut n) = (0f64, 0f64);
            for x in 0..w {
                let i = ((row * w + x) * 4) as usize;
                if i + 3 < data.len() {
                    if data[i + 3] > 0 { op += 1.0; }
                    n += 1.0;
                }
            }
            if n == 0.0 { 0.0 } else { op / n }
        };
        let (first, last) = (row_opaque(0), row_opaque(h - 1));
        assert!(
            (first - last).abs() > 0.5,
            "the cell must be opaque in one half and clear in the other, else this \
             test cannot detect an inversion (row0={first:.2}, row{}={last:.2})",
            h - 1
        );
        let y_at_v0 = transform(&ctm, 0.5, 0.0).1;
        let y_at_v1 = transform(&ctm, 0.5, 1.0).1;
        let (low_y_opaque, high_y_opaque) = if y_at_v1 > y_at_v0 { (last, first) } else { (first, last) };
        assert!(
            low_y_opaque > high_y_opaque,
            "the pattern cell raster is vertically FLIPPED. The cell paints only its \
             lower half, so the raster row landing at the lower page y must be the \
             opaque one. Got low-y opacity {low_y_opaque:.2}, high-y {high_y_opaque:.2} \
             (row0={first:.2}, row{}={last:.2}; v=0 at y={y_at_v0:.0}, v=1 at \
             y={y_at_v1:.0}). A flipped cell still tiles seamlessly, so this is \
             invisible to a spot check.",
            h - 1
        );
    } else {
        // Per-tile path: the painted rects must sit in the lower half of each cell.
        println!("pattern cell: PER-TILE path (no ImageTiled emitted)");
        let ys: Vec<f32> = prims
            .iter()
            .filter_map(|p| match p {
                Prim::Fill { contours, .. } => contours.first().map(|c| bbox_of(c)[1]),
                _ => None,
            })
            .collect();
        assert!(!ys.is_empty(), "the pattern must paint something");
        assert!(
            ys.iter().any(|&y| (y % 10.0).abs() < 1.0),
            "each tile's painted rect must start at the cell's low y, got {ys:?}"
        );
    }
}

/// `tests::unclipped_sh_covers_page` by checking the emitted raster genuinely
/// varies across the page rather than being one flat colour in a corner.
#[test]
fn unclipped_sh_raster_spans_the_page_and_varies_across_it() {
    let mut doc = Document::with_version("1.5");
    let sh_id = axial_shading(&mut doc);
    let bytes = Content {
        operations: vec![Operation::new("sh", vec![Object::Name(b"Sh0".to_vec())])],
    }
    .encode()
    .unwrap();
    let page_id = assemble(
        &mut doc,
        bytes,
        dictionary! { "Shading" => dictionary! { "Sh0" => sh_id } },
        dictionary! { "MediaBox" => vec![0.into(), 0.into(), 400.into(), 500.into()] },
        dictionary! {},
    );
    let page = interpret_page(&doc, page_id).expect("interpret");
    let (ctm, w, h, data) = page
        .prims
        .iter()
        .find_map(|p| match p {
            Prim::Image { ctm, w, h, data, format: 0, .. } => Some((*ctm, *w, *h, data.clone())),
            _ => None,
        })
        .expect("an unclipped sh must emit a raster");
    let dev_w = ctm[0].abs() + ctm[2].abs();
    let dev_h = ctm[1].abs() + ctm[3].abs();
    assert!(dev_w >= 399.0 && dev_h >= 499.0, "raster must span the page, got {dev_w:.0}x{dev_h:.0}");
    // The axial ramp runs left to right, so the left and right edges must differ.
    let px = |x: u32, y: u32| {
        let i = ((y * w + x) * 4) as usize;
        (data[i], data[i + 1], data[i + 2])
    };
    let (l, r) = (px(0, h / 2), px(w - 1, h / 2));
    assert_ne!(l, r, "the axial ramp must vary across the page, not paint one flat colour");
}

/// §14.6.2 + the MAX_OC_STACK cap: BMC/BDC pushes beyond the nesting cap are
/// dropped, so the matching EMCs must be dropped too. If an EMC past the cap
/// pops a frame it does not own, the hidden region un-hides from there on.
#[test]
fn marked_content_nested_past_the_stack_cap_still_stays_hidden() {
    let mut doc = Document::with_version("1.6");
    let ocg_id = doc.add_object(dictionary! { "Type" => "OCG", "Name" => Object::string_literal("L") });
    let mut ops = vec![Operation::new(
        "BDC",
        vec![Object::Name(b"OC".to_vec()), Object::Name(b"OC0".to_vec())],
    )];
    // Nest far past the cap, paint, then unwind by exactly as many EMCs.
    const DEEP: usize = 80;
    for _ in 0..DEEP {
        ops.push(Operation::new("BMC", vec![Object::Name(b"Tx".to_vec())]));
    }
    ops.extend(rect_ops(10, 10, 40, 40)); // deep inside, hidden
    for _ in 0..DEEP {
        ops.push(Operation::new("EMC", vec![]));
    }
    ops.extend(rect_ops(60, 10, 40, 40)); // back at OCG level, still hidden
    ops.push(Operation::new("EMC", vec![]));
    ops.extend(rect_ops(300, 300, 40, 40)); // outside the OCG, visible
    let bytes = Content { operations: ops }.encode().unwrap();
    let catalog = dictionary! {
        "OCProperties" => dictionary! {
            "OCGs" => vec![ocg_id.into()],
            "D" => dictionary! { "OFF" => vec![ocg_id.into()] },
        },
    };
    let resources = dictionary! { "Properties" => dictionary! { "OC0" => ocg_id } };
    let page_id = assemble(&mut doc, bytes, resources, dictionary! {}, catalog);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        !ink_in_region(&page.prims, 0.0, 0.0, 150.0, 100.0),
        "nesting past the marked-content cap must not un-hide the region on the way out"
    );
    assert!(
        ink_in_region(&page.prims, 300.0, 300.0, 341.0, 341.0),
        "and content genuinely outside the OCG must still paint"
    );
}

/// The prim stream must be a balanced bracket sequence — Kotlin turns every
/// ClipPush/GroupPush into a canvas `save()` and every Pop into a `restore()`, so
/// an unbalanced stream either leaks a clip onto the rest of the page or
/// over-restores past the caller's own save. This must hold even when the content
/// stream itself is unbalanced (a `q` with no `Q`, a `W n` never popped).
#[test]
fn prim_stream_brackets_are_balanced_even_for_unbalanced_content() {
    let mut doc = Document::with_version("1.5");
    let ops = vec![
        Operation::new("q", vec![]),
        Operation::new("re", vec![0.into(), 0.into(), 100.into(), 100.into()]),
        Operation::new("W", vec![]),
        Operation::new("n", vec![]),
        Operation::new("q", vec![]),
        Operation::new("re", vec![10.into(), 10.into(), 50.into(), 50.into()]),
        Operation::new("W*", vec![]),
        Operation::new("n", vec![]),
        Operation::new("re", vec![20.into(), 20.into(), 10.into(), 10.into()]),
        Operation::new("f", vec![]),
        // Deliberately no Q at all: the stream ends two levels deep.
    ];
    let page_id = page_from_ops(&mut doc, ops, dictionary! {});

    let page = interpret_page(&doc, page_id).expect("interpret");
    let mut clip = 0i32;
    let mut group = 0i32;
    for p in &page.prims {
        match p {
            Prim::ClipPush { .. } => clip += 1,
            Prim::ClipPop => {
                clip -= 1;
                assert!(clip >= 0, "ClipPop without a matching ClipPush would over-restore");
            }
            Prim::GroupPush { .. } => group += 1,
            Prim::GroupPop => {
                group -= 1;
                assert!(group >= 0, "GroupPop without a matching GroupPush would over-restore");
            }
            _ => {}
        }
    }
    assert_eq!(clip, 0, "{clip} clip level(s) left open at end of stream");
    assert_eq!(group, 0, "{group} group level(s) left open at end of stream");
}

/// §8.4.4: `Q` must pop the clip that the matching `q` scoped, so content after
/// the `Q` is no longer clipped. The pop has to be emitted, or a clip set inside
/// a `q`/`Q` pair silently applies to the whole rest of the page.
#[test]
fn q_pops_a_clip_that_was_committed_inside_its_scope() {
    let mut doc = Document::with_version("1.5");
    let ops = vec![
        Operation::new("q", vec![]),
        Operation::new("re", vec![0.into(), 0.into(), 10.into(), 10.into()]),
        Operation::new("W", vec![]),
        Operation::new("n", vec![]), // committed inside the q
        Operation::new("Q", vec![]),
        Operation::new("rg", vec![0.0.into(), 1.0.into(), 0.0.into()]),
        Operation::new("re", vec![200.into(), 200.into(), 100.into(), 100.into()]),
        Operation::new("f", vec![]),
    ];
    let page_id = page_from_ops(&mut doc, ops, dictionary! {});

    let page = interpret_page(&doc, page_id).expect("interpret");
    let push = page.prims.iter().position(|p| matches!(p, Prim::ClipPush { .. }));
    let pop = page.prims.iter().position(|p| matches!(p, Prim::ClipPop));
    let fill = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::Fill { .. }))
        .expect("the rect after Q must be filled");
    assert!(push.is_some(), "`W n` inside the q must commit a clip");
    let pop = pop.expect("`Q` must emit the matching ClipPop");
    assert!(
        pop < fill,
        "the clip must be popped BEFORE the later fill, or that fill is clipped to \
         the 10x10 box and disappears"
    );
}

/// §7.4 + §11.6.5.3: a filtered `/SMask` must still produce real alpha. The
/// dangerous failure mode is a filter the underlying library does not implement:
/// if the decode returns an EMPTY buffer instead of an error, every absent sample
/// reads as 0, and because an /SMask sample IS the alpha, the whole base image
/// goes to alpha 0 and the picture vanishes. RunLengthDecode stands in for the
/// commoner DCT case here because it can be generated by hand.
#[test]
fn smask_behind_an_unimplemented_filter_does_not_erase_the_image() {
    let mut doc = Document::with_version("1.5");
    // RunLength: 0x01 => copy the next 2 bytes literally; 0x80 => EOD.
    let smask_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 2, "Height" => 1,
            "ColorSpace" => "DeviceGray", "BitsPerComponent" => 8,
            "Filter" => "RunLengthDecode",
        },
        vec![0x01, 0x00, 0xFF, 0x80],
    ));
    let stream = Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 2, "Height" => 1,
            "ColorSpace" => "DeviceGray", "BitsPerComponent" => 8,
            "SMask" => smask_id,
        },
        vec![0xFF, 0xFF],
    );
    let img = extract_image(&doc, &stream, 0xFF00_0000, &no_cs()).expect("image must decode");
    let a = alphas(&img);
    assert!(
        a.iter().any(|&v| v > 0),
        "a filtered /SMask that fails to decode must not zero the alpha of the whole \
         image — that renders the picture completely invisible; got {a:?}"
    );
    assert_eq!(
        a, vec![0, 255],
        "the RunLength-encoded /SMask must decode to the same alphas as an \
         unfiltered one"
    );
}

// ===========================================================================
// Primitive and operator caps
//
// Three caps sit in series on the path from content stream to canvas:
// MAX_CONTENT_OPS (Rust, operators read), MAX_PRIMITIVES (Rust, prims emitted),
// and SafePdfParser.MAX_PRIMITIVES (Kotlin, prims decoded). The *smallest* one
// binds, so raising only the last cannot change what a dense page renders.

/// A page with far more content than the caps allow must render a truncated but
/// structurally valid page — never zero prims, never an unbalanced bracket
/// stream, never a panic.
///
/// The amount of content is DERIVED from the caps rather than hardcoded, so this
/// keeps testing the right thing when a cap moves and does not false-fail on a
/// legitimate change. What it does pin is the cap *relationship*, which is where
/// the real bug lives: three caps sit in series on the path from content stream to
/// canvas — `MAX_CONTENT_OPS` (operators read), `MAX_PRIMITIVES` (prims emitted),
/// and the consumer's own bound — and the smallest binds. When `MAX_CONTENT_OPS`
/// was 200_000 the operator cap bound first at ~100k prims, which made
/// `MAX_PRIMITIVES` dead and made raising the consumer-side bound a no-op.
#[test]
fn content_beyond_the_caps_truncates_to_a_valid_page() {
    // For the simplest content, one `re f` pair is 2 operators and 1 prim, so the
    // operator cap must leave room for MAX_PRIMITIVES prims or it binds first and
    // MAX_PRIMITIVES becomes unreachable.
    assert!(
        MAX_CONTENT_OPS >= 2 * MAX_PRIMITIVES,
        "cap ordering is inverted: MAX_CONTENT_OPS ({MAX_CONTENT_OPS}) must leave \
         room for MAX_PRIMITIVES ({MAX_PRIMITIVES}) prims at 2 operators each, or \
         the operator cap binds first and MAX_PRIMITIVES is dead code — which also \
         makes any larger consumer-side bound unreachable"
    );

    let mut doc = Document::with_version("1.5");
    let ceiling = (MAX_CONTENT_OPS / 2).min(MAX_PRIMITIVES);
    let pairs = ceiling + 500;
    let mut raw = String::with_capacity(pairs * 18);
    raw.push_str("1 0 0 rg\n");
    for i in 0..pairs {
        let y = (i % 700) as i32;
        raw.push_str(&format!("10 {y} 2 2 re f\n"));
    }
    let page_id = assemble(
        &mut doc,
        raw.into_bytes(),
        dictionary! {},
        dictionary! {},
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("an over-long content stream must not lose the page");
    let fills = count(&page.prims, |p| matches!(p, Prim::Fill { .. }));
    println!(
        "caps: requested {pairs} fills, emitted {fills} (ceiling {ceiling}); \
         MAX_CONTENT_OPS={MAX_CONTENT_OPS} MAX_PRIMITIVES={MAX_PRIMITIVES}"
    );
    assert!(fills > 0, "a truncated page must still render its leading content");
    assert!(
        fills < pairs,
        "the caps must actually bind or this test proves nothing (emitted all {fills})"
    );
    assert!(
        fills <= ceiling + 1,
        "emitted {fills} fills, past the {ceiling} the caps allow"
    );
    // Balanced, so a consumer's save/restore stack cannot be corrupted by truncation.
    let mut depth = 0i32;
    for p in &page.prims {
        match p {
            Prim::ClipPush { .. } => depth += 1,
            Prim::ClipPop => depth -= 1,
            _ => {}
        }
        assert!(depth >= 0, "truncation must not emit an unmatched ClipPop");
    }
    assert_eq!(depth, 0, "truncation must leave the clip stack balanced");
}

/// Reaching `MAX_PRIMITIVES` must not corrupt the bracket structure. This is the
/// one case where the cap value genuinely matters: bracket-closing prims are
/// pushed UNGUARDED (the `while clip_depth > 0` / `while group_depth > 0` drains),
/// while content emission stops at the cap. If a truncation ever landed between a
/// ClipPush and its ClipPop the clip would never be restored and everything after
/// it on the page would be clipped away — or, on the consumer side, the canvas
/// save/restore stack would desynchronise.
///
/// Clips are opened and left open across the point where the cap is hit, because a
/// cap reached with NO brackets open proves nothing about bracket integrity.
/// Deliberately built from plain path operators rather than a tiling pattern: how
/// many prims a pattern yields is an implementation choice (a periodic pattern may
/// collapse to a single `ImageTiled`), and this test must not depend on it.
#[test]
fn exceeding_the_primitive_cap_keeps_the_bracket_structure_intact() {
    let mut doc = Document::with_version("1.5");
    let mut raw = String::with_capacity(MAX_PRIMITIVES * 18);
    // Open several nested clips and never close them, so the cap is reached while
    // brackets are outstanding and the unguarded drains have real work to do.
    const NESTING: usize = 6;
    for k in 0..NESTING {
        let s = 10 * k;
        raw.push_str(&format!("q\n{s} {s} 380 380 re W n\n"));
    }
    raw.push_str("1 0 0 rg\n");
    // Comfortably past MAX_PRIMITIVES; emission stops at the cap so the surplus is
    // cheap, and each `re f` pair stays well inside MAX_CONTENT_OPS.
    let pairs = MAX_PRIMITIVES + 2_000;
    for i in 0..pairs {
        let y = (i % 380) as i32;
        raw.push_str(&format!("10 {y} 2 2 re f\n"));
    }
    let page_id = assemble(
        &mut doc,
        raw.into_bytes(),
        dictionary! {},
        dictionary! { "MediaBox" => vec![0.into(), 0.into(), 400.into(), 400.into()] },
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    println!(
        "primitive cap: emitted {} prims (MAX_PRIMITIVES={MAX_PRIMITIVES}), \
         {NESTING} clips left open by the content",
        page.prims.len()
    );
    assert!(
        page.prims.len() >= MAX_PRIMITIVES,
        "this test only means something if it actually reaches the cap; got {} of \
         {MAX_PRIMITIVES}. Raise `pairs` — do NOT lower this assertion, or the \
         checks below stop covering the truncation path.",
        page.prims.len()
    );
    assert!(
        count(&page.prims, |p| matches!(p, Prim::ClipPush { .. })) >= NESTING,
        "the nested clips must actually be committed, or nothing is at risk"
    );
    // The overshoot bound is not a tidy formula, so assert the property that
    // actually makes truncation safe: NO VISIBLE INK may appear past the cap.
    // Content emission is gated per-push, so drawing stops exactly at the cap; what
    // spills over is bracket bookkeeping. Note it is NOT only closers — a form's
    // mandatory /BBox ClipPush can also land past the cap, immediately followed by
    // its pop. That is why a consumer clamp must sit above the WHOLE overshoot, not
    // merely above the cap: cutting anywhere inside it unbalances the brackets even
    // though no drawing is lost.
    let overshoot: Vec<&Prim> = page.prims.iter().skip(MAX_PRIMITIVES).collect();
    println!("  overshoot: {} prim(s) past the cap", overshoot.len());
    for (i, p) in overshoot.iter().enumerate() {
        assert!(
            !is_ink(p),
            "prim {} past the cap puts ink on the page — a guard is gating \
             per-operator instead of per-emit, so a consumer truncating the tail \
             would silently drop visible drawing",
            MAX_PRIMITIVES + i
        );
    }
    let mut clip = 0i32;
    let mut group = 0i32;
    for (i, p) in page.prims.iter().enumerate() {
        match p {
            Prim::ClipPush { .. } => clip += 1,
            Prim::ClipPop => {
                clip -= 1;
                assert!(clip >= 0, "unmatched ClipPop at prim {i} would over-restore the canvas");
            }
            Prim::GroupPush { .. } => group += 1,
            Prim::GroupPop => {
                group -= 1;
                assert!(group >= 0, "unmatched GroupPop at prim {i} would over-restore the canvas");
            }
            _ => {}
        }
    }
    assert_eq!(clip, 0, "{clip} clip level(s) left open when the primitive cap was hit — a truncation landed between a ClipPush and its ClipPop, so the rest of the page is clipped away");
    assert_eq!(group, 0, "{group} group level(s) left open at the primitive cap");
}

/// The same invariant, but with the cap crossed PART-WAY THROUGH a single
/// operator. A `Do` is one operator that can emit unboundedly many prims, so this
/// is where the count would run past the cap if the guards were per-operator
/// rather than per-emit. It also crosses while the form's own mandatory `/BBox`
/// clip (§8.10.2) is open, on top of the page's clips, so the bracket at risk is
/// one the content stream never explicitly closes.
///
/// Kept separate from the plain-operator case above, and deliberately using a form
/// XObject rather than a tiling pattern: how many prims a pattern yields is an
/// implementation choice, and coupling this to it once blocked a legitimate tiling
/// coverage fix.
#[test]
fn crossing_the_primitive_cap_mid_operator_keeps_the_bracket_structure_intact() {
    let mut doc = Document::with_version("1.5");
    let per_form = MAX_PRIMITIVES / 2 + 1_000;
    let mut form = String::with_capacity(per_form * 18);
    form.push_str("q\n5 5 380 380 re W n\n1 0 0 rg\n");
    for i in 0..per_form {
        let y = (i % 370) as i32;
        form.push_str(&format!("10 {y} 2 2 re f\n"));
    }
    // No closing Q: the form's clip is drained by the interpreter, not the content.
    let form_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 400.into(), 400.into()],
        },
        form.into_bytes(),
    ));
    // Two page-level clips, then three `Do`s. The cap lands inside the second or
    // third one, i.e. inside a single operator.
    let mut ops = Vec::new();
    for k in 0..2 {
        let s = 10 * k;
        ops.push(Operation::new("q", vec![]));
        ops.push(Operation::new("re", vec![s.into(), s.into(), 380.into(), 380.into()]));
        ops.push(Operation::new("W", vec![]));
        ops.push(Operation::new("n", vec![]));
    }
    for _ in 0..3 {
        ops.push(Operation::new("Do", vec![Object::Name(b"Fm0".to_vec())]));
    }
    let bytes = Content { operations: ops }.encode().unwrap();
    let page_id = assemble(
        &mut doc,
        bytes,
        dictionary! { "XObject" => dictionary! { "Fm0" => form_id } },
        dictionary! { "MediaBox" => vec![0.into(), 0.into(), 400.into(), 400.into()] },
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    println!(
        "mid-operator cap: emitted {} prims (MAX_PRIMITIVES={MAX_PRIMITIVES})",
        page.prims.len()
    );
    assert!(
        page.prims.len() >= MAX_PRIMITIVES,
        "this test only means something if it actually reaches the cap; got {} of \
         {MAX_PRIMITIVES}. Raise `per_form` or the `Do` count — do NOT lower this \
         assertion.",
        page.prims.len()
    );
    // The point of this variant: an operator that WANTS to emit ~150k more prims
    // must still not put any ink past the cap, because content emission is gated
    // per-push and stops mid-operator. Bracket bookkeeping may spill over — here the
    // third `Do`'s mandatory /BBox ClipPush lands past the cap and is popped
    // immediately — but no drawing may.
    let overshoot: Vec<&Prim> = page.prims.iter().skip(MAX_PRIMITIVES).collect();
    let kind = |p: &Prim| match p {
        Prim::Fill { .. } => "Fill",
        Prim::Stroke { .. } => "Stroke",
        Prim::Text { .. } => "Text",
        Prim::Image { .. } => "Image",
        Prim::ImageTiled { .. } => "ImageTiled",
        Prim::ClipPush { .. } => "ClipPush",
        Prim::ClipPop => "ClipPop",
        Prim::GroupPush { .. } => "GroupPush",
        Prim::GroupPop => "GroupPop",
        Prim::SoftMaskPush { .. } => "SoftMaskPush",
        Prim::SoftMaskContent => "SoftMaskContent",
        Prim::SoftMaskPop => "SoftMaskPop",
        _ => "other",
    };
    println!(
        "  overshoot: {} prim(s) past the cap: {:?}",
        overshoot.len(),
        overshoot.iter().map(|p| kind(p)).collect::<Vec<_>>()
    );
    for (i, p) in overshoot.iter().enumerate() {
        assert!(
            !is_ink(p),
            "prim {} past the cap is {} and puts ink on the page — a guard is gating \
             per-operator instead of per-emit, so a consumer truncating the tail \
             would silently drop visible drawing",
            MAX_PRIMITIVES + i,
            kind(p)
        );
    }
    let mut clip = 0i32;
    let mut group = 0i32;
    for (i, p) in page.prims.iter().enumerate() {
        match p {
            Prim::ClipPush { .. } => clip += 1,
            Prim::ClipPop => {
                clip -= 1;
                assert!(clip >= 0, "unmatched ClipPop at prim {i} would over-restore the canvas");
            }
            Prim::GroupPush { .. } => group += 1,
            Prim::GroupPop => {
                group -= 1;
                assert!(group >= 0, "unmatched GroupPop at prim {i} would over-restore the canvas");
            }
            _ => {}
        }
    }
    assert_eq!(
        clip, 0,
        "{clip} clip level(s) left open — the form's mandatory /BBox clip was not \
         drained when the cap was hit mid-operator, so the rest of the page is \
         clipped away"
    );
    assert_eq!(group, 0, "{group} group level(s) left open at the primitive cap");
}

// ===========================================================================
// 11. Inline images — §8.9.7
// These are the acceptance criteria for the lenient content-stream tokenizer.
// lopdf's `Content::decode` is all-or-nothing, so a single inline image it
// cannot handle drops the ENTIRE operator list and the page degrades to
// annotations only. Each test therefore also asserts that the operators AFTER
// the inline image still ran — that is the part that actually loses pages.

/// Wrap raw inline-image bytes in a content stream with a marker fill after it,
/// so a test can tell "the image was skipped" from "the whole stream was lost".
fn inline_image_page(doc: &mut Document, bi: &[u8]) -> ObjectId {
    let mut c: Vec<u8> = Vec::new();
    c.extend_from_slice(b"q 100 0 0 100 10 600 cm\n");
    c.extend_from_slice(bi);
    c.extend_from_slice(b"\nQ\n0 1 0 rg\n300 300 40 40 re f\n");
    assemble(doc, c, dictionary! {}, dictionary! {}, dictionary! {})
}

fn marker_survived(page: &PageData) -> bool {
    ink_in_region(&page.prims, 300.0, 300.0, 341.0, 341.0)
}

fn inline_images(page: &PageData) -> Vec<(u32, u32)> {
    page.prims
        .iter()
        .filter_map(|p| match p {
            Prim::Image { w, h, .. } => Some((*w, *h)),
            _ => None,
        })
        .collect()
}

/// Control for the four tests below: the same wrapper with NO inline image in it
/// must render the marker. Without this, a failure in those tests could just mean
/// the fixture's own content stream is malformed.
#[test]
fn inline_image_wrapper_without_an_image_renders_its_marker() {
    let mut doc = Document::with_version("1.5");
    let page_id = inline_image_page(&mut doc, b"");
    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        marker_survived(&page),
        "the inline-image test wrapper must itself be valid content"
    );
}

/// §8.9.7: `EI` only ends an inline image once the expected
/// `W*H*BPC*ncomp/8` bytes have been consumed — a token boundary alone is not
/// enough. Binary pixel data frequently contains the bytes "EI"; treating that as
/// the terminator truncates the image and desynchronises every operator after it.
///
/// This payload is deliberately the worst case: the fake `EI` is whitespace
/// delimited on BOTH sides *and* followed by a real operator token (`Q`), so it
/// looks like a genuine end-of-image to every scan-based heuristic, including one
/// that validates what follows. Only computing the data length and skipping
/// exactly that many bytes gets this right. Do not relax it.
#[test]
fn inline_image_binary_data_containing_ei_is_not_truncated() {
    let mut doc = Document::with_version("1.5");
    // 4x4 8-bit gray = 16 bytes, holding the sequence " EI Q " mid-row.
    let mut px: Vec<u8> = (0u8..16).map(|i| i.wrapping_mul(17).wrapping_add(1)).collect();
    px[5..11].copy_from_slice(b" EI Q ");
    let mut bi = b"BI /W 4 /H 4 /CS /G /BPC 8 ID ".to_vec();
    bi.extend_from_slice(&px);
    bi.extend_from_slice(b" EI");
    let page_id = inline_image_page(&mut doc, &bi);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        marker_survived(&page),
        "the operators after the inline image must still run — an embedded \
         \" EI Q \" must not desynchronise (or drop) the rest of the content stream"
    );
    assert!(
        inline_images(&page).contains(&(4, 4)),
        "the full 4x4 inline image must be decoded, not truncated at the embedded \
         \"EI\"; got {:?}", inline_images(&page)
    );
}

/// §8.9.7 Table 93: `/IM true` is an inline stencil mask, for which `/BPC` is
/// implicitly 1 and `/D` may be omitted. This is the single most common inline
/// image in the wild (every scanned-fax overlay is one) and it is exactly the
/// form that trips a decoder requiring `/BPC` — so rejecting it costs the page.
#[test]
fn inline_stencil_mask_without_bpc_renders() {
    let mut doc = Document::with_version("1.5");
    // 8x2 stencil at an implicit 1 bpc = 1 byte per row.
    let mut bi = b"BI /W 8 /H 2 /IM true ID ".to_vec();
    bi.extend_from_slice(&[0b1010_1010, 0b0101_0101]);
    bi.extend_from_slice(b" EI");
    let page_id = inline_image_page(&mut doc, &bi);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        marker_survived(&page),
        "an /IM true stencil with no /BPC must not cost the rest of the content stream"
    );
    assert!(
        inline_images(&page).contains(&(8, 2)),
        "/BPC is implicitly 1 for /IM true; the stencil must decode. Got {:?}",
        inline_images(&page)
    );
}

/// §8.9.7: the same embedded-`EI` hazard, but NOT whitespace-delimited. A decoder
/// that scans for a token-boundary `EI` survives this one while still failing the
/// whitespace-delimited case above, so keeping both separates "handles the easy
/// case" from "computes the data length and does not scan at all".
#[test]
fn inline_image_data_containing_a_bare_ei_is_not_truncated() {
    let mut doc = Document::with_version("1.5");
    let mut px: Vec<u8> = (0u8..16).map(|i| i.wrapping_mul(9).wrapping_add(3)).collect();
    px[9] = b'E';
    px[10] = b'I';
    let mut bi = b"BI /W 4 /H 4 /CS /G /BPC 8 ID ".to_vec();
    bi.extend_from_slice(&px);
    bi.extend_from_slice(b" EI");
    let page_id = inline_image_page(&mut doc, &bi);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(marker_survived(&page), "a bare \"EI\" in the pixel data must not end the image");
    assert!(
        inline_images(&page).contains(&(4, 4)),
        "the full 4x4 image must decode; got {:?}", inline_images(&page)
    );
}
/// §8.9.7 + §8.10.1: an inline image inside a FORM XOBJECT must not cost the
/// form's other content. Nested content streams are decoded with a bare
/// `Content::decode` whose failure drops the whole stream, so one `BI` blanks the
/// entire form — the same all-or-nothing failure already fixed at page level.
#[test]
fn inline_image_inside_a_form_xobject_keeps_the_rest_of_the_form() {
    let mut doc = Document::with_version("1.5");
    // No /BPC, which is one of the forms a strict inline-image parser rejects.
    let mut form: Vec<u8> = b"q 20 0 0 20 0 0 cm\nBI /W 2 /H 2 /CS /G ID ".to_vec();
    form.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    form.extend_from_slice(b" EI\nQ\n0 1 0 rg\n5 5 30 30 re f\n");
    let form_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
        },
        form,
    ));
    let ops = vec![Operation::new("Do", vec![Object::Name(b"Fm0".to_vec())])];
    let page_id = page_from_ops(
        &mut doc,
        ops,
        dictionary! { "XObject" => dictionary! { "Fm0" => form_id } },
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        ink_in_region(&page.prims, 5.0, 5.0, 36.0, 36.0),
        "the form's own rectangle, drawn AFTER the inline image, must still paint — \
         one BI must not blank the whole form XObject"
    );
    assert!(
        inline_images(&page).contains(&(2, 2)),
        "the inline image inside the form must decode; got {:?}",
        inline_images(&page)
    );
}

/// §8.9.7 + §8.7.3.1: the same hazard inside a TILING PATTERN cell. A pattern
/// cell is its own content stream, so an inline image in one blanks every tile.
#[test]
fn inline_image_inside_a_tiling_pattern_cell_keeps_the_rest_of_the_cell() {
    let mut doc = Document::with_version("1.5");
    let mut cell: Vec<u8> = b"q 10 0 0 10 0 0 cm\nBI /W 2 /H 2 /CS /G ID ".to_vec();
    cell.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    cell.extend_from_slice(b" EI\nQ\n1 0 0 rg\n0 0 10 10 re f\n");
    let pid = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "Pattern", "PatternType" => 1, "PaintType" => 1, "TilingType" => 1,
            "BBox" => vec![0.into(), 0.into(), 20.into(), 20.into()],
            "XStep" => 20, "YStep" => 20,
            "Resources" => dictionary! {},
        },
        cell,
    ));
    let region = fill_region(0.0, 0.0, 100.0, 100.0);
    let mut prims = Vec::new();
    paint_pattern_fill(
        &doc, pid, &region, false, &IDENTITY, 0xFF00_0000, 1.0, BlendMode::Normal,
        &mut prims, 0, 0,
    );
    let inked = prims.iter().filter(|p| is_ink(p)).count();
    assert!(
        inked > 4,
        "each tile's own content must still paint after the inline image; got \
         {inked} inking prims across a 5x5 lattice"
    );
}

/// §8.9.7 + §11.6.5.2: the same hazard inside a SOFT-MASK GROUP. If the group's
/// stream is dropped the mask has no shape, which for a luminosity mask means
/// alpha 0 everywhere and the masked content vanishes entirely.
#[test]
fn inline_image_inside_a_soft_mask_group_keeps_the_rest_of_the_group() {
    let mut doc = Document::with_version("1.7");
    let mut mask: Vec<u8> = b"q 50 0 0 50 0 0 cm\nBI /W 2 /H 2 /CS /G ID ".to_vec();
    mask.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    mask.extend_from_slice(b" EI\nQ\n1 1 1 rg\n0 0 200 200 re f\n");
    let mask_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 200.into(), 200.into()],
            "Group" => dictionary! { "S" => "Transparency" },
        },
        mask,
    ));
    let gs_id = doc.add_object(dictionary! {
        "SMask" => dictionary! { "S" => "Luminosity", "G" => Object::Reference(mask_id) },
    });
    let ops = vec![
        Operation::new("gs", vec![Object::Name(b"GS1".to_vec())]),
        Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
        Operation::new("re", vec![10.into(), 10.into(), 50.into(), 50.into()]),
        Operation::new("f", vec![]),
    ];
    let page_id = page_from_ops(
        &mut doc,
        ops,
        dictionary! { "ExtGState" => dictionary! { "GS1" => gs_id } },
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let sep = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::SoftMaskContent))
        .expect("SoftMaskContent");
    let pop = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::SoftMaskPop))
        .expect("SoftMaskPop");
    // The mask group's white rectangle is what gives the mask any luminance at all.
    let mask_has_shape = page.prims[sep..pop]
        .iter()
        .any(|p| matches!(p, Prim::Fill { .. } | Prim::Image { .. }));
    assert!(
        mask_has_shape,
        "the mask group's own content, drawn after the inline image, must survive — \
         a luminosity mask with no shape means alpha 0 everywhere and the masked \
         content disappears"
    );
}

/// §8.9.7 + §12.5.5: the same hazard inside an ANNOTATION APPEARANCE stream. An
/// `/AP /N` form is its own content stream, so one `BI` blanks the whole
/// appearance — and an annotation with no appearance is invisible, which for a
/// stamp or a signature is a missing seal rather than a missing decoration.
#[test]
fn inline_image_inside_an_annotation_appearance_keeps_the_rest_of_it() {
    let mut doc = Document::with_version("1.7");
    let mut ap: Vec<u8> = b"q 20 0 0 20 0 0 cm\nBI /W 2 /H 2 /CS /G ID ".to_vec();
    ap.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    ap.extend_from_slice(b" EI\nQ\n0 1 0 rg\n10 10 60 60 re f\n");
    let ap_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
        },
        ap,
    ));
    let annot = doc.add_object(dictionary! {
        "Type" => "Annot", "Subtype" => "Stamp",
        "Rect" => vec![0.into(), 0.into(), 100.into(), 100.into()],
        "AP" => dictionary! { "N" => ap_id },
    });
    let mut page = dictionary! { "Annots" => vec![annot.into()] };
    let page_id = assemble_with_contents(
        &mut doc,
        Object::Null,
        dictionary! {},
        &mut page,
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        ink_in_region(&page.prims, 10.0, 10.0, 71.0, 71.0),
        "the appearance's own rectangle, drawn AFTER the inline image, must still \
         paint — one BI must not blank the whole /AP /N stream"
    );
    assert!(
        inline_images(&page).contains(&(2, 2)),
        "the inline image inside the appearance must decode; got {:?}",
        inline_images(&page)
    );
}

/// The SEARCH INDEX must survive the same failure as rendering. A page's text is
/// extracted by re-interpreting its content stream, so if one inline image loses
/// the operator list the page becomes unsearchable — silently, because nothing
/// visibly breaks. Guards the index against the all-or-nothing tokenizer failure
/// that was fixed for the render path.
#[test]
fn search_index_survives_an_inline_image_in_the_page_content() {
    let mut doc = Document::with_version("1.5");
    let font_id = doc.add_object(dictionary! {
        "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
    });
    // An inline image with no /BPC, then the text that must remain findable.
    let mut raw: Vec<u8> = b"q 20 0 0 20 0 0 cm\nBI /W 2 /H 2 /CS /G ID ".to_vec();
    raw.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    raw.extend_from_slice(
        b" EI\nQ\nBT /F1 12 Tf 72 700 Td (Findable) Tj ET\n",
    );
    let page_id = assemble(
        &mut doc,
        raw,
        dictionary! { "Font" => dictionary! { "F1" => font_id } },
        dictionary! {},
        dictionary! {},
    );
    // Sanity: the page must render its inline image, so the index and the render
    // path are being fed the same recovered operator list.
    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        inline_images(&page).contains(&(2, 2)),
        "precondition: the inline image should decode on the render path too"
    );

    let index = build_index(&doc);
    let joined: String = index.iter().map(|p| p.text_orig.as_str()).collect();
    assert!(
        joined.contains("Findable"),
        "text after an inline image must still reach the search index; got {joined:?}"
    );
    let hits = search_document_inner(&index, "Findable", false);
    assert!(
        !hits.is_empty(),
        "and it must be locatable, so a tap can highlight it"
    );
}
#[test]
fn inline_image_with_abbreviated_gray_colorspace_renders() {
    let mut doc = Document::with_version("1.5");
    let mut bi = b"BI /W 2 /H 2 /CS /G /BPC 8 ID ".to_vec();
    bi.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    bi.extend_from_slice(b" EI");
    let page_id = inline_image_page(&mut doc, &bi);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(marker_survived(&page), "/CS /G must not cost the rest of the content stream");
    assert!(
        inline_images(&page).contains(&(2, 2)),
        "the /CS /G inline image must decode; got {:?}", inline_images(&page)
    );
}

/// §8.9.7 Table 93: `/F` (`/Filter`) is legal on an inline image, with the same
/// abbreviations. `/AHx` here so the payload stays ASCII.
#[test]
fn inline_image_with_a_filter_renders() {
    let mut doc = Document::with_version("1.5");
    let bi = b"BI /W 2 /H 2 /CS /G /BPC 8 /F /AHx ID 004080FF> EI".to_vec();
    let page_id = inline_image_page(&mut doc, &bi);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(marker_survived(&page), "an inline image with /F must not cost the rest of the stream");
    assert!(
        inline_images(&page).contains(&(2, 2)),
        "the filtered inline image must decode; got {:?}", inline_images(&page)
    );
}

/// §8.9.7: `/BPC` is required on a non-stencil inline image, but real files omit
/// it. Defaulting to 8 keeps the page; rejecting it loses everything after `BI`.
#[test]
fn inline_image_without_bpc_defaults_to_eight_bits() {
    let mut doc = Document::with_version("1.5");
    let mut bi = b"BI /W 2 /H 2 /CS /G ID ".to_vec();
    bi.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    bi.extend_from_slice(b" EI");
    let page_id = inline_image_page(&mut doc, &bi);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(marker_survived(&page), "a missing /BPC must not cost the rest of the stream");
    assert!(
        inline_images(&page).contains(&(2, 2)),
        "a missing /BPC must default to 8 rather than dropping the image; got {:?}",
        inline_images(&page)
    );
}
