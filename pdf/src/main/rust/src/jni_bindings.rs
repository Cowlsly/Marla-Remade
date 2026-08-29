use crate::*;
use jni::objects::{JByteArray, JClass, JFloatArray, JString};
use jni::sys::{jbyteArray, jboolean, jfloat, jint, jlong, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

// ---------------------------------------------------------------------------
// Safety / JNI constants
// ---------------------------------------------------------------------------

/// Maximum Java byte[] size we accept (200 MB) to prevent OOM DoS.
/// Audit #7: unbounded convert_byte_array copy. An Intent with huge PDF could OOM native heap,
/// and registry then duplicates via Document::load_mem.
const MAX_JAVA_BYTE_ARRAY_BYTES: usize = 200 * 1024 * 1024;
/// Drops the cached search index for `handle` on scope exit, so a document-mutating
/// entry point cannot leave `ensure_index` serving text from before the edit.
///
/// A guard rather than a call at each return: these functions have several exit paths
/// and a `catch_unwind` arm, and a panic can leave the document partially mutated — the
/// one case where a surviving stale index is worst. Declared before `catch_unwind` so it
/// drops after the unwind is caught.
///
/// Cheap: it only removes a map entry. The rebuild happens lazily on the next search.
struct InvalidateSearchIndex(jlong);
impl Drop for InvalidateSearchIndex {
    fn drop(&mut self) {
        crate::search::invalidate_index(self.0);
    }
}

/// Throw a Java IllegalArgumentException (if possible). Requires &mut JNIEnv.
fn throw_iae<'local>(env: &mut JNIEnv<'local>, msg: &str) {
    let _ = env.throw_new("java/lang/IllegalArgumentException", msg);
}

fn throw_oom<'local>(env: &mut JNIEnv<'local>, msg: &str) {
    let _ = env.throw_new("java/lang/OutOfMemoryError", msg);
}

/// Convert JString to Rust String with proper error propagation (no unwrap_or_default hiding errors).
/// Returns Ok(String) or Err with message. Handles null JString.
/// Audit #5 + #3: jstr unwrap_or_default hides malformed Modified UTF-8 and null.
fn jstr_safe<'local>(env: &mut JNIEnv<'local>, s: &JString<'local>) -> Result<String, String> {
    if s.is_null() {
        return Ok(String::new());
    }
    match env.get_string(s) {
        Ok(js) => Ok(js.into()),
        Err(e) => {
            let _ = env.exception_clear();
            Err(format!("Invalid JString (Modified UTF-8): {:?}", e))
        }
    }
}

/// Helper that creates a Java byte[] from Option<Vec<u8>> ensuring local capacity first.
/// Uses &mut JNIEnv since ensure_local_capacity requires &mut and we want to handle errors properly.
/// Returns raw jbyteArray (null on failure).
fn bytes_or_null_mut<'local>(env: &mut JNIEnv<'local>, data: Option<Vec<u8>>) -> jbyteArray {
    let null = std::ptr::null_mut();
    if env.ensure_local_capacity(4).is_err() {
        let _ = env.exception_clear();
        return null;
    }
    match data {
        Some(b) => match env.byte_array_from_slice(&b) {
            Ok(arr) => {
                // SAFETY: ownership transferred to JVM via into_raw. Local ref consumed.
                // Java GC will free the array. No further use of `env` for this object needed.
                arr.into_raw()
            }
            Err(_) => null,
        },
        None => null,
    }
}

// ---------------------------------------------------------------------------
// Inner logic functions (panic-safe, wrapped by catch_unwind in extern fns)
// ---------------------------------------------------------------------------

fn open_document_inner<'local>(env: &mut JNIEnv<'local>, data: JByteArray<'local>) -> jlong {
    if data.is_null() {
        throw_iae(env, "data is null");
        return 0;
    }
    let len = env.get_array_length(&data).unwrap_or(0);
    if len as usize > MAX_JAVA_BYTE_ARRAY_BYTES {
        throw_oom(env, "PDF too large (>200MB)");
        return 0;
    }
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => {
            let _ = env.exception_clear();
            return 0;
        }
    };
    open_document(&bytes) as jlong
}

fn open_document_pw_inner<'local>(
    env: &mut JNIEnv<'local>,
    data: JByteArray<'local>,
    password: JString<'local>,
) -> jlong {
    if data.is_null() {
        throw_iae(env, "data is null");
        return 0;
    }
    let len = env.get_array_length(&data).unwrap_or(0);
    if len as usize > MAX_JAVA_BYTE_ARRAY_BYTES {
        throw_oom(env, "PDF too large (>200MB)");
        return 0;
    }
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => {
            let _ = env.exception_clear();
            return 0;
        }
    };
    let pw = if password.is_null() {
        String::new()
    } else {
        match jstr_safe(env, &password) {
            Ok(s) => s,
            Err(e) => {
                throw_iae(env, &e);
                return 0;
            }
        }
    };
    open_document_pw(&bytes, pw.as_bytes()) as jlong
}

fn pdf_password_state_inner<'local>(env: &mut JNIEnv<'local>, data: JByteArray<'local>) -> jint {
    if data.is_null() {
        throw_iae(env, "data is null");
        return 0;
    }
    let len = env.get_array_length(&data).unwrap_or(0);
    if len as usize > MAX_JAVA_BYTE_ARRAY_BYTES {
        throw_oom(env, "PDF too large (>200MB)");
        return 0;
    }
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => {
            let _ = env.exception_clear();
            return 0;
        }
    };
    pdf_password_state(&bytes)
}

fn save_encrypted_inner<'local>(
    env: &mut JNIEnv<'local>,
    handle: jlong,
    user_pw: JString<'local>,
    owner_pw: JString<'local>,
) -> jbyteArray {
    let u = match jstr_safe(env, &user_pw) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return std::ptr::null_mut();
        }
    };
    let o = match jstr_safe(env, &owner_pw) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return std::ptr::null_mut();
        }
    };
    // Ensure capacity before allocating byte array (audit #4)
    if env.ensure_local_capacity(4).is_err() {
        let _ = env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, save_encrypted(handle, u.as_bytes(), o.as_bytes()))
}

fn render_page_inner<'local>(env: &mut JNIEnv<'local>, handle: jlong, index: jint) -> jbyteArray {
    if env.ensure_local_capacity(4).is_err() {
        let _ = env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, render_page(handle, index))
}

fn append_pdf_inner<'local>(
    env: &mut JNIEnv<'local>,
    handle: jlong,
    data: JByteArray<'local>,
) -> jint {
    if data.is_null() {
        throw_iae(env, "data is null");
        return 0;
    }
    let len = env.get_array_length(&data).unwrap_or(0);
    if len as usize > MAX_JAVA_BYTE_ARRAY_BYTES {
        throw_oom(env, "PDF to append too large (>200MB)");
        return 0;
    }
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => {
            let _ = env.exception_clear();
            return 0;
        }
    };
    append_pdf(handle, &bytes)
}

fn append_image_page_inner<'local>(
    env: &mut JNIEnv<'local>,
    handle: jlong,
    jpeg: JByteArray<'local>,
    w: jint,
    h: jint,
) -> jint {
    if jpeg.is_null() {
        throw_iae(env, "jpeg is null");
        return 0;
    }
    let len = env.get_array_length(&jpeg).unwrap_or(0);
    if len as usize > MAX_JAVA_BYTE_ARRAY_BYTES {
        throw_oom(env, "Image too large (>200MB)");
        return 0;
    }
    let bytes = match env.convert_byte_array(&jpeg) {
        Ok(b) => b,
        Err(_) => {
            let _ = env.exception_clear();
            return 0;
        }
    };
    append_image_page(handle, &bytes, w as u32, h as u32)
}

fn extract_page_inner<'local>(env: &mut JNIEnv<'local>, handle: jlong, index: jint) -> jbyteArray {
    if env.ensure_local_capacity(4).is_err() {
        let _ = env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, extract_page(handle, index))
}

fn list_data_inner<'local, F>(env: &mut JNIEnv<'local>, f: F) -> jbyteArray
where
    F: FnOnce() -> Option<Vec<u8>>,
{
    if env.ensure_local_capacity(4).is_err() {
        let _ = env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, f())
}

fn add_free_text_inner<'local>(
    env: &mut JNIEnv<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    argb: jint,
    size: jfloat,
    text: JString<'local>,
) -> jlong {
    let t = match jstr_safe(env, &text) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return 0;
        }
    };
    add_free_text(
        handle,
        page,
        [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
        argb as u32,
        size as f64,
        &t,
    )
    .unwrap_or(0)
}

fn add_note_inner<'local>(
    env: &mut JNIEnv<'local>,
    handle: jlong,
    page: jint,
    x: jfloat,
    y: jfloat,
    argb: jint,
    text: JString<'local>,
) -> jlong {
    let t = match jstr_safe(env, &text) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return 0;
        }
    };
    add_note(handle, page, x as f64, y as f64, argb as u32, &t).unwrap_or(0)
}

fn add_callout_inner<'local>(
    env: &mut JNIEnv<'local>,
    handle: jlong,
    page: jint,
    ax: jfloat,
    ay: jfloat,
    bx: jfloat,
    by: jfloat,
    argb: jint,
    size: jfloat,
    text: JString<'local>,
) -> jlong {
    let t = match jstr_safe(env, &text) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return 0;
        }
    };
    add_callout(
        handle,
        page,
        ax as f64,
        ay as f64,
        bx as f64,
        by as f64,
        argb as u32,
        size as f64,
        &t,
    )
    .unwrap_or(0)
}

fn add_poly_inner<'local>(
    env: &mut JNIEnv<'local>,
    handle: jlong,
    page: jint,
    argb: jint,
    line_width: jfloat,
    fill: jboolean,
    closed: jboolean,
    pts: JFloatArray<'local>,
) -> jlong {
    if pts.is_null() {
        throw_iae(env, "pts is null");
        return 0;
    }
    let len = match env.get_array_length(&pts) {
        Ok(l) => l as usize,
        Err(_) => {
            let _ = env.exception_clear();
            return 0;
        }
    };
    if len > 100_000 {
        throw_iae(env, "pts too large");
        return 0;
    }
    let mut buf = vec![0f32; len];
    if env.get_float_array_region(&pts, 0, &mut buf).is_err() {
        let _ = env.exception_clear();
        return 0;
    }
    add_poly(
        handle,
        page,
        &buf,
        argb as u32,
        line_width as f64,
        fill != 0,
        closed != 0,
    )
    .unwrap_or(0)
}

fn add_ink_inner<'local>(
    env: &mut JNIEnv<'local>,
    handle: jlong,
    page: jint,
    argb: jint,
    line_width: jfloat,
    pts: JFloatArray<'local>,
) -> jlong {
    if pts.is_null() {
        throw_iae(env, "pts is null");
        return 0;
    }
    let len = match env.get_array_length(&pts) {
        Ok(l) => l as usize,
        Err(_) => {
            let _ = env.exception_clear();
            return 0;
        }
    };
    if len > 100_000 {
        throw_iae(env, "pts too large");
        return 0;
    }
    let mut buf = vec![0f32; len];
    if env.get_float_array_region(&pts, 0, &mut buf).is_err() {
        let _ = env.exception_clear();
        return 0;
    }
    add_ink(handle, page, argb as u32, line_width as f64, &buf).unwrap_or(0)
}

fn add_stamp_inner<'local>(
    env: &mut JNIEnv<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    img_w: jint,
    img_h: jint,
    jpeg: JByteArray<'local>,
) -> jlong {
    if jpeg.is_null() {
        throw_iae(env, "jpeg is null");
        return 0;
    }
    let len = env.get_array_length(&jpeg).unwrap_or(0);
    if len as usize > MAX_JAVA_BYTE_ARRAY_BYTES {
        throw_oom(env, "Image stamp too large (>200MB)");
        return 0;
    }
    let bytes = match env.convert_byte_array(&jpeg) {
        Ok(b) => b,
        Err(_) => {
            let _ = env.exception_clear();
            return 0;
        }
    };
    add_stamp(
        handle,
        page,
        [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
        img_w as u32,
        img_h as u32,
        &bytes,
    )
    .unwrap_or(0)
}

fn update_text_annotation_inner<'local>(
    env: &mut JNIEnv<'local>,
    handle: jlong,
    annot_id: jlong,
    text: JString<'local>,
) -> jboolean {
    let t = match jstr_safe(env, &text) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return 0;
        }
    };
    update_free_text(handle, annot_id, &t) as jboolean
}

fn set_text_field_inner<'local>(
    env: &mut JNIEnv<'local>,
    handle: jlong,
    widget_id: jlong,
    value: JString<'local>,
) -> jboolean {
    let v = match jstr_safe(env, &value) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return 0;
        }
    };
    set_text_field(handle, widget_id, &v) as jboolean
}

fn set_choice_field_inner<'local>(
    env: &mut JNIEnv<'local>,
    handle: jlong,
    widget_id: jlong,
    value: JString<'local>,
) -> jboolean {
    let v = match jstr_safe(env, &value) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return 0;
        }
    };
    set_choice_field(handle, widget_id, &v) as jboolean
}

fn save_document_inner<'local>(env: &mut JNIEnv<'local>, handle: jlong) -> jbyteArray {
    if env.ensure_local_capacity(4).is_err() {
        let _ = env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, save_document(handle))
}

fn save_compressed_inner<'local>(env: &mut JNIEnv<'local>, handle: jlong) -> jbyteArray {
    if env.ensure_local_capacity(4).is_err() {
        let _ = env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, save_compressed(handle))
}

fn extract_text_inner<'local>(env: &mut JNIEnv<'local>, handle: jlong) -> jstring {
    if env.ensure_local_capacity(4).is_err() {
        let _ = env.exception_clear();
        return std::ptr::null_mut();
    }
    let text_opt = document_text(handle);
    let s_opt = match text_opt {
        Some(t) => match env.new_string(t) {
            Ok(js) => Some(js),
            Err(_) => {
                let _ = env.exception_clear();
                None
            }
        },
        None => None,
    };
    match s_opt {
        Some(s) => {
            // SAFETY: into_raw transfers ownership of local ref to JVM. JVM GC manages lifetime.
            s.into_raw()
        }
        None => std::ptr::null_mut(),
    }
}

fn search_document_inner_fn<'local>(
    env: &mut JNIEnv<'local>,
    handle: jlong,
    query: JString<'local>,
) -> jbyteArray {
    let q = match jstr_safe(env, &query) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return std::ptr::null_mut();
        }
    };
    if env.ensure_local_capacity(4).is_err() {
        let _ = env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, search_document(handle, &q))
}

fn search_document_cs_inner<'local>(
    env: &mut JNIEnv<'local>,
    handle: jlong,
    query: JString<'local>,
) -> jbyteArray {
    let q = match jstr_safe(env, &query) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return std::ptr::null_mut();
        }
    };
    if env.ensure_local_capacity(4).is_err() {
        let _ = env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, search_document_case_sensitive(handle, &q))
}

// ---------------------------------------------------------------------------
// JNI entry points — all wrapped in catch_unwind (critical #1)
// ---------------------------------------------------------------------------

/// `PdfNative.openDocument(byte[]) -> long`. Returns a non-zero handle, or
/// 0 on parse failure / encrypted document.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_openDocument<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JByteArray<'local>,
) -> jlong {
    match catch_unwind(AssertUnwindSafe(|| open_document_inner(&mut env, data))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in openDocument",
            );
            0
        }
    }
}

/// `PdfNative.openDocumentWithPassword(byte[], String) -> long`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_openDocumentWithPassword<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JByteArray<'local>,
    password: JString<'local>,
) -> jlong {
    match catch_unwind(AssertUnwindSafe(|| {
        open_document_pw_inner(&mut env, data, password)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in openDocumentWithPassword",
            );
            0
        }
    }
}

/// `PdfNative.pdfPasswordState(byte[]) -> int` (0 none, 1 needs pw, 2 unsupported).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_pdfPasswordState<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JByteArray<'local>,
) -> jint {
    match catch_unwind(AssertUnwindSafe(|| pdf_password_state_inner(&mut env, data))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in pdfPasswordState",
            );
            0
        }
    }
}

/// `PdfNative.saveEncrypted(long, String, String) -> byte[]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_saveEncrypted<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    user_pw: JString<'local>,
    owner_pw: JString<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| {
        save_encrypted_inner(&mut env, handle, user_pw, owner_pw)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in saveEncrypted",
            );
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.getPageCount(long) -> int`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_getPageCount<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    match catch_unwind(AssertUnwindSafe(|| page_count(handle))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in getPageCount",
            );
            0
        }
    }
}

/// `PdfNative.renderPage(long, int) -> byte[]`. Serialized primitives, or
/// `null` on error.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_renderPage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    index: jint,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| render_page_inner(&mut env, handle, index))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new("java/lang/RuntimeException", "Native panic in renderPage");
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.closeDocument(long)`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_closeDocument<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let res = catch_unwind(AssertUnwindSafe(|| {
        close_document(handle);
    }));
    if res.is_err() {
        let _ = env.exception_clear();
    }
}

/// `PdfNative.createEmptyDocument() -> long`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_createEmptyDocument<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    match catch_unwind(AssertUnwindSafe(create_empty_document)) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in createEmptyDocument",
            );
            0
        }
    }
}

/// `PdfNative.appendPdf(long, byte[]) -> int` (pages added).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_appendPdf<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    data: JByteArray<'local>,
) -> jint {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| append_pdf_inner(&mut env, handle, data))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new("java/lang/RuntimeException", "Native panic in appendPdf");
            0
        }
    }
}

/// `PdfNative.appendImagePage(long, byte[], int, int) -> int`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_appendImagePage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    jpeg: JByteArray<'local>,
    w: jint,
    h: jint,
) -> jint {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        append_image_page_inner(&mut env, handle, jpeg, w, h)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in appendImagePage",
            );
            0
        }
    }
}

/// `PdfNative.movePage(long, int, int) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_movePage<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    from: jint,
    to: jint,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        move_page(handle, from.max(0) as usize, to.max(0) as usize) as jboolean
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.removePage(long, int) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_removePage<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    index: jint,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        remove_page(handle, index.max(0) as usize) as jboolean
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.rotatePage(long, int, int) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_rotatePage<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    index: jint,
    delta: jint,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| rotate_page(handle, index, delta) as jboolean)) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.extractPage(long, int) -> byte[]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_extractPage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    index: jint,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| extract_page_inner(&mut env, handle, index))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new("java/lang/RuntimeException", "Native panic in extractPage");
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.listAnnotations(long, int) -> byte[]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listAnnotations<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| {
        list_data_inner(&mut env, || list_annotations(handle, page))
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.listFormFields(long, int) -> byte[]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listFormFields<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| {
        list_data_inner(&mut env, || list_form_fields(handle, page))
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listLinks<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| {
        list_data_inner(&mut env, || list_links(handle, page))
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addTextAnnotation<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    argb: jint,
    size: jfloat,
    text: JString<'local>,
) -> jlong {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_free_text_inner(&mut env, handle, page, x0, y0, x1, y1, argb, size, text)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in addTextAnnotation",
            );
            0
        }
    }
}

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addHighlight<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    argb: jint,
) -> jlong {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_highlight(
            handle,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
        )
        .unwrap_or(0)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addTextMarkup<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    argb: jint,
    kind: jint,
) -> jlong {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_text_markup(
            handle,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
            kind,
        )
        .unwrap_or(0)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addNote<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x: jfloat,
    y: jfloat,
    argb: jint,
    text: JString<'local>,
) -> jlong {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_note_inner(&mut env, handle, page, x, y, argb, text)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addCallout<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    ax: jfloat,
    ay: jfloat,
    bx: jfloat,
    by: jfloat,
    argb: jint,
    size: jfloat,
    text: JString<'local>,
) -> jlong {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_callout_inner(&mut env, handle, page, ax, ay, bx, by, argb, size, text)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addRectAnnotation<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    argb: jint,
    line_width: jfloat,
    fill: jboolean,
) -> jlong {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_square(
            handle,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
            line_width as f64,
            fill != 0,
        )
        .unwrap_or(0)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addCircleAnnotation<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    argb: jint,
    line_width: jfloat,
    fill: jboolean,
) -> jlong {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_circle(
            handle,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
            line_width as f64,
            fill != 0,
        )
        .unwrap_or(0)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.addPolyAnnotation(long, int, int argb, float width, bool fill, bool closed, float[] pts)`.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addPolyAnnotation<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    argb: jint,
    line_width: jfloat,
    fill: jboolean,
    closed: jboolean,
    pts: JFloatArray<'local>,
) -> jlong {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_poly_inner(&mut env, handle, page, argb, line_width, fill, closed, pts)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.addInkAnnotation(long, int, int argb, float width, float[] pts)`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addInkAnnotation<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    argb: jint,
    line_width: jfloat,
    pts: JFloatArray<'local>,
) -> jlong {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_ink_inner(&mut env, handle, page, argb, line_width, pts)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.addImageStamp(long, int, rect, imgW, imgH, byte[] jpeg)`.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addImageStamp<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    img_w: jint,
    img_h: jint,
    jpeg: JByteArray<'local>,
) -> jlong {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_stamp_inner(&mut env, handle, page, x0, y0, x1, y1, img_w, img_h, jpeg)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_updateAnnotationRect<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    annot_id: jlong,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        update_annotation_rect(
            handle,
            page,
            annot_id,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
        ) as jboolean
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_updateTextAnnotation<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    annot_id: jlong,
    text: JString<'local>,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        update_text_annotation_inner(&mut env, handle, annot_id, text)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_deleteAnnotation<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    annot_id: jlong,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        delete_annotation(handle, page, annot_id) as jboolean
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_detachAnnotation<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    annot_id: jlong,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        detach_annotation(handle, page, annot_id) as jboolean
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_reattachAnnotation<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    annot_id: jlong,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        reattach_annotation(handle, page, annot_id) as jboolean
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_duplicateAnnotation<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    annot_id: jlong,
    dx: jfloat,
    dy: jfloat,
) -> jlong {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        duplicate_annotation(handle, page, annot_id, dx as f64, dy as f64)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_setTextField<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    widget_id: jlong,
    value: JString<'local>,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        set_text_field_inner(&mut env, handle, widget_id, value)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_setCheckbox<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    widget_id: jlong,
    on: jboolean,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        set_checkbox(handle, widget_id, on != 0) as jboolean
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_setChoiceField<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    widget_id: jlong,
    value: JString<'local>,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        set_choice_field_inner(&mut env, handle, widget_id, value)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.saveDocument(long) -> byte[]`. Serialized modified PDF.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_saveDocument<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| save_document_inner(&mut env, handle))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in saveDocument",
            );
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.saveCompressed(long) -> byte[]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_saveCompressed<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| save_compressed_inner(&mut env, handle))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.flattenDocument(long) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_flattenDocument<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| flatten_document(handle) as jboolean)) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.applyRedactions(long) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_applyRedactions<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| apply_redactions(handle) as jboolean)) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.hasRedactions(long) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_hasRedactions<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    match catch_unwind(AssertUnwindSafe(|| has_redactions(handle) as jboolean)) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.addRedaction(long, int, f,f,f,f) -> long`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addRedaction<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
) -> jlong {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_redaction(
            handle,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
        )
        .unwrap_or(0)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.extractText(long) -> String` (null on failure).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_extractText<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    match catch_unwind(AssertUnwindSafe(|| extract_text_inner(&mut env, handle))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.listOutline(long) -> byte[]`. Serialized document outline.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listOutline<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| {
        list_data_inner(&mut env, || list_outline(handle))
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.searchDocument(long, String) -> byte[]`. Serialized matches.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_searchDocument<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    query: JString<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| {
        search_document_inner_fn(&mut env, handle, query)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.searchDocumentCaseSensitive(long, String) -> byte[]`. Phase 7 toggle.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_searchDocumentCaseSensitive<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    query: JString<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| {
        search_document_cs_inner(&mut env, handle, query)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.buildSearchIndex(long)`. Prebuilds the text index so the first
/// search is instant; safe to call on a background thread.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_buildSearchIndex<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let _ = ensure_index(handle);
    }));
    if res.is_err() {
        let _ = env.exception_clear();
    }
}
