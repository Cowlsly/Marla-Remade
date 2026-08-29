use crate::*;

/// A PDF transformation matrix `[a b c d e f]` representing
/// `[[a b 0] [c d 0] [e f 1]]`.
pub(crate) type Mat = [f64; 6];

pub(crate) const IDENTITY: Mat = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0];

/// `m1 * m2` in PDF convention (m1 is applied first).
pub(crate) fn mat_mul(m1: &Mat, m2: &Mat) -> Mat {
    [
        m1[0] * m2[0] + m1[1] * m2[2],
        m1[0] * m2[1] + m1[1] * m2[3],
        m1[2] * m2[0] + m1[3] * m2[2],
        m1[2] * m2[1] + m1[3] * m2[3],
        m1[4] * m2[0] + m1[5] * m2[2] + m2[4],
        m1[4] * m2[1] + m1[5] * m2[3] + m2[5],
    ]
}

/// Transform point `(x, y)` by `m`.
pub(crate) fn transform(m: &Mat, x: f64, y: f64) -> (f64, f64) {
    (m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5])
}

pub(crate) fn translate(tx: f64, ty: f64) -> Mat {
    [1.0, 0.0, 0.0, 1.0, tx, ty]
}

// ---------------------------------------------------------------------------
// Primitives
// ---------------------------------------------------------------------------

/// Look up `key` on the page dict, walking up `/Parent` for inherited
/// attributes (`MediaBox`, `Resources`).
pub(crate) fn inherited<'a>(doc: &'a Document, page_id: ObjectId, key: &[u8]) -> Option<&'a Object> {
    let mut current = page_id;
    for _ in 0..32 {
        let dict = doc.get_dictionary(current).ok()?;
        if let Ok(obj) = dict.get(key) {
            return Some(obj);
        }
        match dict.get(b"Parent").ok().and_then(|o| o.as_reference().ok()) {
            Some(parent) => current = parent,
            None => return None,
        }
    }
    None
}

/// Read a 4-element rectangle from an inherited page attribute, validating finiteness.
fn inherited_rect(doc: &Document, page_id: ObjectId, key: &[u8]) -> Option<[f64; 4]> {
    let obj = inherited(doc, page_id, key).and_then(|o| deref(doc, o))?;
    let arr = obj.as_array().ok()?;
    if arr.len() != 4 {
        return None;
    }
    let mut out = [0.0; 4];
    for (i, v) in arr.iter().enumerate() {
        let val = deref(doc, v).and_then(num)?;
        if !val.is_finite() {
            return None;
        }
        out[i] = val;
    }
    // Also validate rect finite (NaN/Inf guard for malformed PDF)
    if !out.iter().all(|x| x.is_finite()) {
        return None;
    }
    Some(out)
}

/// Page MediaBox as `[x0, y0, x1, y1]`, defaulting to US Letter.
pub(crate) fn media_box(doc: &Document, page_id: ObjectId) -> [f64; 4] {
    inherited_rect(doc, page_id, b"MediaBox").unwrap_or([0.0, 0.0, 612.0, 792.0])
}

/// `/UserUnit` inherited, default 1.0, clamped to valid range per spec.
///
/// Deliberately NOT used by the render path — see `page_visible_box`. Kept for a
/// future physical-sizing feature (true 100% zoom / print), which is the only
/// thing `/UserUnit` may legitimately affect.
#[allow(dead_code)]
pub(crate) fn user_unit(doc: &Document, page_id: ObjectId) -> f64 {
    let uu = inherited(doc, page_id, b"UserUnit")
        .and_then(|o| deref(doc, o))
        .and_then(num)
        .unwrap_or(1.0);
    uu.clamp(1.0, 75000.0)
}

/// Visible page rectangle: `CropBox` intersected with `MediaBox` when present,
/// otherwise `MediaBox` (§14.11.2). Always normalized so `x0 < x1` and `y0 < y1`,
/// and never degenerate — a zero-size result falls back to US Letter.
///
/// `/UserUnit` is deliberately NOT applied here. Rendering is invariant under it:
/// the rasterizer fits the page to the viewport (`scale = viewport_width /
/// page_width`), so a factor applied to both the page size and every coordinate
/// divides straight back out. Applying it here previously desynchronized
/// `page_display_size` from `page_base_matrix` (which never applied it), leaving
/// content mis-scaled against the canvas, hit-targets from `list_annotations` /
/// `list_links` / `list_form_fields` offset by a UserUnit-scaled crop origin, and
/// newly authored annotations written at the wrong coordinates. Keeping geometry
/// in raw PDF units means there is exactly one definition of page space with
/// nothing to keep in sync. If physical sizing is ever needed, ship `/UserUnit`
/// as its own wire field feeding a DPI calculation; it must not affect layout.
pub(crate) fn page_visible_box(doc: &Document, page_id: ObjectId) -> [f64; 4] {
    let mb = normalize_rect(media_box(doc, page_id));
    let mut vb = mb;
    if let Some(cb) = inherited_rect(doc, page_id, b"CropBox").map(normalize_rect) {
        let x0 = mb[0].max(cb[0]);
        let y0 = mb[1].max(cb[1]);
        let x1 = mb[2].min(cb[2]);
        let y1 = mb[3].min(cb[3]);
        // An empty or degenerate intersection falls back to MediaBox rather than
        // producing a zero-size page.
        if x1 > x0 && y1 > y0 {
            vb = [x0, y0, x1, y1];
        }
    }
    // A degenerate box (e.g. `MediaBox [0 0 0 0]`, which is finite and so passes
    // validation) would otherwise ship a zero page dimension and divide by zero
    // in the rasterizer's fit-to-width scale.
    if vb[2] - vb[0] >= 1.0 && vb[3] - vb[1] >= 1.0 {
        vb
    } else {
        [0.0, 0.0, 612.0, 792.0]
    }
}

/// Normalized page rotation in {0,90,180,270}, inherited via `/Parent`.
pub(crate) fn page_rotation(doc: &Document, page_id: ObjectId) -> i64 {
    let r = inherited(doc, page_id, b"Rotate")
        .and_then(|o| deref(doc, o))
        .and_then(num)
        .unwrap_or(0.0) as i64;
    (((r % 360) + 360) % 360 / 90) * 90
}

/// Matrix mapping raw page space (visible rect origin, before rotation) into
/// displayed space: origin bottom-left, with dimensions swapped for 90/270.
/// Visible rect is CropBox intersected with MediaBox, in raw PDF units.
///
/// `/Rotate` is clockwise (ISO 32000 §7.7.3.3). With page width `w`, height `h`
/// and device y-up, the spec-correct page→display maps are:
///   90°  (x,y) -> (y,     w - x)  => [0,-1, 1,0, 0, w]
///   180° (x,y) -> (w - x, h - y)  => [-1,0, 0,-1, w, h]
///   270° (x,y) -> (h - y, x)      => [0, 1,-1,0, h, 0]
/// (The 90° and 270° arms were previously swapped, rotating such pages 180°
/// off — content upside-down and mirrored; see issue #321 pagerotationexample.)
pub(crate) fn page_base_matrix(doc: &Document, page_id: ObjectId) -> Mat {
    let vb = page_visible_box(doc, page_id);
    // Normalize to min/max to handle inverted boxes.
    let minx = vb[0].min(vb[2]);
    let miny = vb[1].min(vb[3]);
    let w = (vb[2] - vb[0]).abs();
    let h = (vb[3] - vb[1]).abs();
    let t = translate(-minx, -miny);
    let r: Mat = match page_rotation(doc, page_id) {
        90 => [0.0, -1.0, 1.0, 0.0, 0.0, w],
        180 => [-1.0, 0.0, 0.0, -1.0, w, h],
        270 => [0.0, 1.0, -1.0, 0.0, h, 0.0],
        _ => IDENTITY,
    };
    mat_mul(&t, &r)
}

/// Page dimensions as displayed (after `/Rotate`), from the same visible box as
/// `page_base_matrix` so the two can never disagree. `page_visible_box`
/// guarantees both dimensions are finite and >= 1.
///
/// This is the ONLY source of page dimensions for the rasterizer, which
/// constrains its canvas to exactly this size and clips to its own bounds — that
/// is what makes the CropBox clip (§14.11.2) implicit. A change that let content
/// draw outside the canvas would need an explicit clip to the visible box here.
pub(crate) fn page_display_size(doc: &Document, page_id: ObjectId) -> (f32, f32) {
    let vb = page_visible_box(doc, page_id);
    let w = (vb[2] - vb[0]).abs() as f32;
    let h = (vb[3] - vb[1]).abs() as f32;
    match page_rotation(doc, page_id) {
        90 | 270 => (h, w),
        _ => (w, h),
    }
}

/// Inverse with Option: None if singular (determinant < eps)
pub(crate) fn mat_inverse_checked(m: &Mat) -> Option<Mat> {
    let det = m[0] * m[3] - m[1] * m[2];
    if det.abs() < 1e-12 { return None; }
    let inv = 1.0 / det;
    let a = m[3] * inv;
    let b = -m[1] * inv;
    let c = -m[2] * inv;
    let d = m[0] * inv;
    let e = -(m[4] * a + m[5] * c);
    let f = -(m[4] * b + m[5] * d);
    Some([a, b, c, d, e, f])
}

/// Inverse returning IDENTITY fallback (previous behavior) — now wraps checked
pub(crate) fn mat_inverse(m: &Mat) -> Mat {
    mat_inverse_checked(m).unwrap_or(IDENTITY)
}

/// Inverse base matrix for a page index, mapping displayed (editor) coordinates
/// back into raw page space so stored annotations remain valid PDF.
pub(crate) fn page_base_inverse(doc: &Document, page_index: i32) -> Mat {
    match nth_page_id(doc, page_index) {
        Some(pid) => mat_inverse(&page_base_matrix(doc, pid)),
        None => IDENTITY,
    }
}

/// Convert an editor-space rect into a normalized raw-page-space rect.
pub(crate) fn page_rect(doc: &Document, page_index: i32, rect: [f64; 4]) -> [f64; 4] {
    let binv = page_base_inverse(doc, page_index);
    let (x0, y0) = transform(&binv, rect[0], rect[1]);
    let (x1, y1) = transform(&binv, rect[2], rect[3]);
    normalize_rect([x0, y0, x1, y1])
}

/// Convert editor-space flat x,y points into raw-page-space.
pub(crate) fn page_points(doc: &Document, page_index: i32, points: &[f32]) -> Vec<f32> {
    let binv = page_base_inverse(doc, page_index);
    let mut out = Vec::with_capacity(points.len());
    let mut i = 0;
    while i + 1 < points.len() {
        let (x, y) = transform(&binv, points[i] as f64, points[i + 1] as f64);
        out.push(x as f32);
        out.push(y as f32);
        i += 2;
    }
    out
}

#[cfg(test)]
mod geometry_tests {
    use crate::*;

    fn rect4(r: [f64; 4]) -> Object {
        Object::Array(vec![r[0].into(), r[1].into(), r[2].into(), r[3].into()])
    }

    /// Single-page document with the given boxes, `/Rotate` and `/UserUnit`.
    fn page_doc(
        media: [f64; 4],
        crop: Option<[f64; 4]>,
        rotate: Option<i64>,
        user_unit: Option<f64>,
    ) -> Document {
        let mut doc = Document::with_version("1.7");
        let pages_id = doc.new_object_id();
        let mut page = dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => rect4(media),
        };
        if let Some(c) = crop {
            page.set("CropBox", rect4(c));
        }
        if let Some(r) = rotate {
            page.set("Rotate", Object::Integer(r));
        }
        if let Some(u) = user_unit {
            page.set("UserUnit", Object::Real(u as f32));
        }
        let page_id = doc.add_object(page);
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages",
                "Kids" => Object::Array(vec![page_id.into()]),
                "Count" => 1,
            }),
        );
        let catalog_id = doc.add_object(dictionary! {
            "Type" => "Catalog",
            "Pages" => pages_id,
        });
        doc.trailer.set("Root", catalog_id);
        doc
    }

    fn assert_mat_eq(a: &Mat, b: &Mat, what: &str) {
        for i in 0..6 {
            assert!(
                (a[i] - b[i]).abs() < 1e-9,
                "{what}: element {i} was {} expected {}",
                a[i],
                b[i]
            );
        }
    }

    /// `page_base_inverse` must be the exact inverse of `page_base_matrix` for
    /// every rotation — it converts editor coordinates back into raw page space
    /// when storing annotations, so any drift writes them to the wrong place.
    #[test]
    fn base_inverse_is_exact_inverse_for_all_rotations() {
        for uu in [None, Some(2.0)] {
            for rot in [0i64, 90, 180, 270] {
                let doc = page_doc([50.0, 100.0, 350.0, 500.0], None, Some(rot), uu);
                let pid = nth_page_id(&doc, 0).expect("page 0");
                let base = page_base_matrix(&doc, pid);
                let inv = page_base_inverse(&doc, 0);
                let tag = format!("rot={rot} uu={uu:?}");
                assert_mat_eq(&mat_mul(&base, &inv), &IDENTITY, &tag);
                assert_mat_eq(&mat_mul(&inv, &base), &IDENTITY, &format!("{tag} reversed"));
                let (dx, dy) = transform(&base, 350.0, 500.0);
                let (rx, ry) = transform(&inv, dx, dy);
                assert!(
                    (rx - 350.0).abs() < 1e-6 && (ry - 500.0).abs() < 1e-6,
                    "{tag}: round-trip gave ({rx},{ry})"
                );
            }
        }
    }

    /// Every corner of the crop box must land exactly on the canvas for all four
    /// rotations: nothing off-canvas, and the box must cover the full display
    /// size (which catches a rotation that rotates but forgets to translate).
    #[test]
    fn crop_box_corners_fill_the_canvas() {
        for rot in [0i64, 90, 180, 270] {
            let doc = page_doc(
                [0.0, 0.0, 612.0, 792.0],
                Some([50.0, 100.0, 350.0, 500.0]),
                Some(rot),
                None,
            );
            let pid = nth_page_id(&doc, 0).expect("page 0");
            let base = page_base_matrix(&doc, pid);
            let (w, h) = page_display_size(&doc, pid);
            let (w, h) = (w as f64, h as f64);
            let mut xs: Vec<f64> = Vec::new();
            let mut ys: Vec<f64> = Vec::new();
            for (x, y) in [(50.0, 100.0), (350.0, 100.0), (350.0, 500.0), (50.0, 500.0)] {
                let (dx, dy) = transform(&base, x, y);
                assert!(dx >= -1e-6 && dx <= w + 1e-6, "rot={rot}: x={dx} outside 0..{w}");
                assert!(dy >= -1e-6 && dy <= h + 1e-6, "rot={rot}: y={dy} outside 0..{h}");
                xs.push(dx);
                ys.push(dy);
            }
            let minx = xs.iter().cloned().fold(f64::INFINITY, f64::min);
            let maxx = xs.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
            let miny = ys.iter().cloned().fold(f64::INFINITY, f64::min);
            let maxy = ys.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
            assert!(
                minx.abs() < 1e-6 && miny.abs() < 1e-6,
                "rot={rot}: origin not translated to 0, got ({minx},{miny})"
            );
            assert!((maxx - w).abs() < 1e-6, "rot={rot}: extent {maxx} != width {w}");
            assert!((maxy - h).abs() < 1e-6, "rot={rot}: extent {maxy} != height {h}");
        }
    }

    /// `/Rotate` 90 and 270 swap the display dimensions; 0 and 180 do not.
    #[test]
    fn display_size_swaps_for_quarter_turns() {
        for (rot, want) in [
            (0i64, (300.0f32, 400.0f32)),
            (90, (400.0, 300.0)),
            (180, (300.0, 400.0)),
            (270, (400.0, 300.0)),
        ] {
            let doc = page_doc([50.0, 100.0, 350.0, 500.0], None, Some(rot), None);
            let pid = nth_page_id(&doc, 0).expect("page 0");
            assert_eq!(page_display_size(&doc, pid), want, "rot={rot}");
        }
    }

    /// Rendering is invariant under `/UserUnit`, so it must affect NEITHER the
    /// page matrix nor the display size. Applying it to only one of them (the
    /// original bug) mis-scaled content against the canvas, offset every
    /// hit-target by a UserUnit-scaled crop origin, and wrote newly authored
    /// annotations at the wrong coordinates.
    #[test]
    fn user_unit_does_not_affect_geometry() {
        for rot in [0i64, 90, 180, 270] {
            let plain = page_doc(
                [0.0, 0.0, 612.0, 792.0],
                Some([10.0, 20.0, 500.0, 700.0]),
                Some(rot),
                None,
            );
            let scaled = page_doc(
                [0.0, 0.0, 612.0, 792.0],
                Some([10.0, 20.0, 500.0, 700.0]),
                Some(rot),
                Some(2.0),
            );
            let pa = nth_page_id(&plain, 0).expect("page 0");
            let pb = nth_page_id(&scaled, 0).expect("page 0");
            assert_mat_eq(
                &page_base_matrix(&plain, pa),
                &page_base_matrix(&scaled, pb),
                &format!("rot={rot} matrix"),
            );
            assert_eq!(
                page_display_size(&plain, pa),
                page_display_size(&scaled, pb),
                "rot={rot} display size"
            );
        }
    }

    /// §7.7.3.3: `/Rotate` is normalized to a multiple of 90 in [0,360),
    /// including negatives and values above 360.
    #[test]
    fn rotation_is_normalized() {
        for (given, want) in [
            (-90i64, 270i64),
            (450, 90),
            (360, 0),
            (-450, 270),
            (720, 0),
            (45, 0),
            (100, 90),
        ] {
            let doc = page_doc([0.0, 0.0, 612.0, 792.0], None, Some(given), None);
            let pid = nth_page_id(&doc, 0).expect("page 0");
            assert_eq!(page_rotation(&doc, pid), want, "/Rotate {given}");
        }
    }

    /// §14.11.2: the visible box is CropBox intersected with MediaBox, falling
    /// back to MediaBox when the intersection is empty, always normalized, and
    /// never degenerate.
    #[test]
    fn visible_box_intersects_and_degrades() {
        let vb = |d: &Document| page_visible_box(d, nth_page_id(d, 0).expect("page 0"));

        // CropBox larger than MediaBox clamps to MediaBox.
        let d = page_doc([0.0, 0.0, 612.0, 792.0], Some([-100.0, -100.0, 1000.0, 1000.0]), None, None);
        assert_eq!(vb(&d), [0.0, 0.0, 612.0, 792.0]);

        // Partial overlap intersects.
        let d = page_doc([0.0, 0.0, 612.0, 792.0], Some([100.0, 100.0, 1000.0, 500.0]), None, None);
        assert_eq!(vb(&d), [100.0, 100.0, 612.0, 500.0]);

        // A disjoint CropBox falls back to MediaBox, not a zero-size page.
        let d = page_doc([0.0, 0.0, 612.0, 792.0], Some([700.0, 800.0, 900.0, 1000.0]), None, None);
        assert_eq!(vb(&d), [0.0, 0.0, 612.0, 792.0]);

        // §7.9.5: rect corners may be given in any order and must be normalized.
        let d = page_doc([612.0, 792.0, 0.0, 0.0], None, None, None);
        assert_eq!(vb(&d), [0.0, 0.0, 612.0, 792.0]);

        // A degenerate MediaBox must not ship a zero dimension, which would
        // divide by zero in the rasterizer's fit-to-width scale.
        let d = page_doc([0.0, 0.0, 0.0, 0.0], None, None, None);
        let (w, h) = page_display_size(&d, nth_page_id(&d, 0).expect("page 0"));
        assert!(w >= 1.0 && h >= 1.0, "degenerate MediaBox produced {w}x{h}");
    }

    /// §7.7.3.4: inheritable attributes walk /Parent, and a cyclic /Parent in a
    /// malformed file must terminate rather than hang.
    #[test]
    fn inherited_walks_parent_and_survives_a_cycle() {
        let mut doc = Document::with_version("1.7");
        let a = doc.new_object_id();
        let b = doc.new_object_id();
        // Two nodes that are each other's parent, neither carrying /MediaBox.
        doc.objects.insert(a, Object::Dictionary(dictionary! { "Type" => "Pages", "Parent" => b }));
        doc.objects.insert(b, Object::Dictionary(dictionary! { "Type" => "Pages", "Parent" => a }));
        assert!(inherited(&doc, a, b"MediaBox").is_none(), "cycle must terminate");
        assert_eq!(media_box(&doc, a), [0.0, 0.0, 612.0, 792.0], "falls back to Letter");

        // A page with no /MediaBox inherits its grandparent's.
        let root = doc.new_object_id();
        let mid = doc.new_object_id();
        let page = doc.add_object(dictionary! { "Type" => "Page", "Parent" => mid });
        doc.objects.insert(
            mid,
            Object::Dictionary(dictionary! { "Type" => "Pages", "Parent" => root, "Kids" => Object::Array(vec![page.into()]), "Count" => 1 }),
        );
        doc.objects.insert(
            root,
            Object::Dictionary(dictionary! { "Type" => "Pages", "Kids" => Object::Array(vec![mid.into()]), "Count" => 1, "MediaBox" => rect4([0.0, 0.0, 200.0, 400.0]) }),
        );
        assert_eq!(media_box(&doc, page), [0.0, 0.0, 200.0, 400.0]);
    }
}
