//! The JNI surface: three entry points per network, and nothing per-pixel.
//!
//! Kotlin creates a segmenter, hands it a bitmap's pixels, gets a mask back, and closes
//! it. Preprocessing, the whole forward pass and the readback all happen on this side, so
//! the boundary is crossed three times per inference rather than once per element.
//!
//! # Why pixels and not the `Bitmap`
//!
//! `AndroidBitmap_lockPixels` from `libjnigraphics` would avoid copying the pixel array
//! across, but it also means handling every `Bitmap.Config` the platform might hand over
//! — including `HARDWARE`, which cannot be locked at all — and both call sites already
//! hold an ARGB_8888 copy. So Kotlin does `getPixels` into a reused `IntArray` and passes
//! that, which is one copy of at most 512x512 ints and no `libjnigraphics` dependency.
//!
//! # Threading
//!
//! Neither entry point is thread-safe, and neither needs to be: `BokehAnalyzer` holds its
//! segmenter behind a `synchronized(lock)` and `MlSegmentation` behind `segLock`. That
//! discipline is what the comments in `BokehAnalyzer.kt` are about — a use-after-free
//! tombstone with the ncnn net — and the hazard is identical with a Vulkan handle, which
//! is why the Kotlin side keeps it.
//!
//! # Handles
//!
//! A handle is a leaked `Box`, handed to Kotlin as an opaque `jlong`. `0` means failure,
//! and every entry point tolerates it, so a device without fp16 compute degrades to "no
//! bokeh" rather than to a crash.

use jni::objects::{JByteArray, JClass, JFloatArray, JIntArray, JString};
use jni::sys::{jfloatArray, jint, jlong, jstring};
use jni::JNIEnv;

use crate::nets::{mobilefacenet, ppocr_det, ppocr_rec, scrfd, selfie, u2netp};
use crate::post::ctc::Dictionary;
use crate::post::nms::{self, Face, Maps};
use crate::post::ocr::{self, Line};
use crate::preprocess::{
    Letterbox, FACE_EMBED, IMAGENET, PPOCR_DET, PPOCR_REC, RESCALE_ONLY, SCRFD,
};
use crate::vulkan::context;
use crate::vulkan::run::Net;
use crate::weights::{graph, Weights};

/// One segmenter. Handed to Kotlin as an opaque `jlong`.
struct Handle {
    net: Net,
    /// The pixel array, reused across calls.
    ///
    /// `:camera` runs this ~15 times a second on frames of up to 512x512, so a fresh
    /// `Vec<i32>` per call would be a megabyte of allocation and free per second on the
    /// preview path for nothing.
    pixels: Vec<i32>,
}

/// `SelfieSegmenter`'s constructor. Returns 0 on failure, having logged why.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env` and a `weights` array it owns.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createSelfie<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    weights: JByteArray<'l>,
) -> jlong {
    open(&mut env, weights, graph::SELFIE, "selfie")
}

/// `SubjectSegmenter`'s constructor. Returns 0 on failure, having logged why.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env` and a `weights` array it owns.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createU2netp<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    weights: JByteArray<'l>,
) -> jlong {
    open(&mut env, weights, graph::U2NETP, "u2netp")
}

/// `FaceDetector`'s constructor: SCRFD 500M at a fixed square. Returns 0 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env` and a `weights` array it owns.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createScrfd<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    weights: JByteArray<'l>,
) -> jlong {
    open(&mut env, weights, graph::SCRFD, "scrfd")
}

/// `FaceEmbedder`'s constructor: MobileFaceNet at 112x112. Returns 0 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env` and a `weights` array it owns.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createMobilefacenet<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    weights: JByteArray<'l>,
) -> jlong {
    open(&mut env, weights, graph::MOBILEFACENET, "mobilefacenet")
}

fn open<'l>(env: &mut JNIEnv<'l>, weights: JByteArray<'l>, graph_id: u32, name: &str) -> jlong {
    match build(env, weights, graph_id) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("{name} is unavailable: {e}"));
            0
        }
    }
}

fn build<'l>(
    env: &mut JNIEnv<'l>,
    weights: JByteArray<'l>,
    graph_id: u32,
) -> Result<Handle, String> {
    // The blob arrives as bytes rather than a path because an asset lives inside the APK
    // and has no filesystem path unless it is extracted first. Both `.vkml` files are
    // `noCompress`, so Kotlin's read is a straight copy out of the mapped APK.
    let bytes = env
        .convert_byte_array(&weights)
        .map_err(|e| format!("cannot read the weights array: {e}"))?;
    let parsed = Weights::parse(&bytes, graph_id)?;
    // The device comes up lazily and is shared, so `:camera`'s two segmenters do not
    // create two `VkDevice`s.
    let shared = context::shared()?;
    let (plan, normalise) = match graph_id {
        graph::SELFIE => (selfie::build(&parsed)?, RESCALE_ONLY),
        graph::U2NETP => (u2netp::build(&parsed)?, IMAGENET),
        // A fixed square rather than the tight multiple-of-32 letterbox `scrfd.cpp` uses;
        // see `preprocess::Letterbox::square` for why, and what it costs.
        graph::SCRFD => (
            scrfd::build(&parsed, scrfd::LONG_SIDE, scrfd::LONG_SIDE)?,
            SCRFD,
        ),
        graph::MOBILEFACENET => (mobilefacenet::build(&parsed)?, FACE_EMBED),
        other => return Err(format!("no forward pass for graph {other}")),
    };
    Ok(Handle {
        net: Net::new(shared, plan, &parsed, normalise)?,
        pixels: Vec::new(),
    })
}

/// Run the network over `pixels` and return the mask as a `float[]`.
///
/// Returns null on failure or on a `0` handle, which the Kotlin wrapper turns into "no
/// mask this frame" rather than an exception.
///
/// # Safety
///
/// `handle` must be `0` or a value returned by one of the create functions and not yet
/// destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_segment<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pixels: JIntArray<'l>,
    width: jint,
    height: jint,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 || width <= 0 || height <= 0 {
        return null;
    }
    // SAFETY: the caller guarantees `handle` came from a create function and is live, and
    // the Kotlin side serialises `segment` against `destroy` with a lock held across the
    // whole call.
    let state = unsafe { &mut *(handle as *mut Handle) };

    let count = match env.get_array_length(&pixels) {
        Ok(n) if n >= 0 => n as usize,
        Ok(n) => {
            log(&format!("a pixel array of length {n}"));
            return null;
        }
        Err(e) => {
            log(&format!("cannot size the pixel array: {e}"));
            return null;
        }
    };
    if count != (width as usize) * (height as usize) {
        log(&format!("{count} pixels for a {width}x{height} bitmap"));
        return null;
    }
    state.pixels.resize(count, 0);
    if let Err(e) = env.get_int_array_region(&pixels, 0, &mut state.pixels) {
        log(&format!("cannot read the pixel array: {e}"));
        return null;
    }
    let mask = match state.net.infer(&state.pixels, width as u32, height as u32) {
        Ok(m) => m,
        Err(e) => {
            log(&format!("inference failed: {e}"));
            return null;
        }
    };
    match new_float_array(&mut env, &mask) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("cannot return the mask: {e}"));
            null
        }
    }
}

/// Detect faces in `pixels` and return them flattened, nine floats each.
///
/// The layout per face is `left, top, right, bottom, leftEyeX, leftEyeY, rightEyeX,
/// rightEyeY, score` — deliberately the argument order of the old ncnn JNI's `Face`
/// constructor, so the Kotlin mapping is a straight read and a reviewer can diff the two.
/// Every coordinate is a fraction of the source bitmap, so it survives the caller
/// resizing.
///
/// A flat `float[]` rather than an array of objects: constructing `n` Java objects across
/// JNI is `n` class lookups and `n` constructor calls, and the Kotlin side has to allocate
/// its own data class anyway.
///
/// Returns null on failure or on a `0` handle. An empty array means no faces, which is
/// the common case and not an error.
///
/// # Safety
///
/// `handle` must be `0` or a value returned by
/// [`Java_com_vayunmathur_library_ml_MlNative_createScrfd`] and not yet destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_detectFaces<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pixels: JIntArray<'l>,
    width: jint,
    height: jint,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 || width <= 0 || height <= 0 {
        return null;
    }
    // SAFETY: as `segment` — the caller guarantees the handle is live and serialises this
    // against `destroy`.
    let state = unsafe { &mut *(handle as *mut Handle) };

    if let Err(e) = read_pixels(&mut env, &pixels, width, height, &mut state.pixels) {
        log(&e);
        return null;
    }
    match detect(state, width as u32, height as u32) {
        Ok(flat) => match new_float_array(&mut env, &flat) {
            Ok(array) => array,
            Err(e) => {
                log(&format!("cannot return the detections: {e}"));
                null
            }
        },
        Err(e) => {
            log(&format!("face detection failed: {e}"));
            null
        }
    }
}

/// Nine floats per detected face. See the entry point above for the order.
const FACE_FLOATS: usize = 9;

/// Letterbox, run, decode, suppress, and flatten.
fn detect(state: &mut Handle, width: u32, height: u32) -> Result<Vec<f32>, String> {
    let fit = Letterbox::square(width, height, scrfd::LONG_SIDE)?;
    let maps = state.net.infer_letterboxed(&state.pixels, width, height, &fit)?;
    let shapes: Vec<crate::nets::Shape> =
        state.net.output_shapes().iter().map(|b| b.shape).collect();
    if maps.len() != shapes.len() || maps.len() != scrfd::STRIDES.len() * 3 {
        return Err(format!("{} output maps, expected {}", maps.len(), scrfd::STRIDES.len() * 3));
    }

    let mut faces: Vec<Face> = Vec::new();
    for (level, stride) in scrfd::STRIDES.iter().enumerate() {
        let at = level * 3;
        let (score, bbox, keypoints) = match (maps.get(at), maps.get(at + 1), maps.get(at + 2)) {
            (Some(s), Some(b), Some(k)) => (s, b, k),
            _ => return Err(format!("stride {stride} is missing a map")),
        };
        let shape = shapes.get(at).copied().ok_or("a map with no shape")?;
        nms::decode(
            &Maps { score, bbox, keypoints, shape },
            *stride,
            nms::SCORE_THRESHOLD,
            &mut faces,
        )?;
    }
    nms::suppress(&mut faces, nms::IOU_THRESHOLD);
    nms::to_source(&mut faces, &fit, width, height);

    let mut flat = Vec::with_capacity(faces.len() * FACE_FLOATS);
    for face in &faces {
        flat.extend_from_slice(&face.bounds);
        // Landmarks 0 and 1 are the eyes, which is all the alignment in
        // `FaceRecognizer.alignFace` uses. The nose and mouth corners are decoded and
        // dropped here rather than carried across a boundary nothing reads them through.
        let (left_eye, right_eye) = match (face.keypoints.first(), face.keypoints.get(1)) {
            (Some(a), Some(b)) => (*a, *b),
            _ => ((0.0, 0.0), (0.0, 0.0)),
        };
        flat.push(left_eye.0);
        flat.push(left_eye.1);
        flat.push(right_eye.0);
        flat.push(right_eye.1);
        flat.push(face.score);
    }
    Ok(flat)
}

/// Both PP-OCRv5 networks and the dictionary between them. A separate handle type from
/// [`Handle`] because it owns two `Net`s, and separately destroyed for the same reason:
/// one `destroy` that guessed which kind of pointer it had been given would be a
/// type-confusion bug waiting for a caller to mix them up.
struct OcrHandle {
    det: Net,
    rec: Net,
    dictionary: Dictionary,
    pixels: Vec<i32>,
}

/// `TextRecognizer`'s constructor: detection at a fixed square, recognition at a fixed
/// width, and the character table. Returns 0 on failure, having logged why.
///
/// Three assets rather than one because they are three files in the APK, and the dictionary
/// arrives as a `String` rather than bytes because Kotlin already has to decode it as UTF-8
/// to know it is text.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env` and arrays it owns.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createPpocr<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    detection: JByteArray<'l>,
    recognition: JByteArray<'l>,
    keys: JString<'l>,
) -> jlong {
    match build_ocr(&mut env, detection, recognition, keys) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("ppocr is unavailable: {e}"));
            0
        }
    }
}

fn build_ocr<'l>(
    env: &mut JNIEnv<'l>,
    detection: JByteArray<'l>,
    recognition: JByteArray<'l>,
    keys: JString<'l>,
) -> Result<OcrHandle, String> {
    let det_bytes = env
        .convert_byte_array(&detection)
        .map_err(|e| format!("cannot read the detection weights: {e}"))?;
    let rec_bytes = env
        .convert_byte_array(&recognition)
        .map_err(|e| format!("cannot read the recognition weights: {e}"))?;
    let text: String = env
        .get_string(&keys)
        .map_err(|e| format!("cannot read the dictionary: {e}"))?
        .into();
    let dictionary = Dictionary::parse(&text)?;

    let det_weights = Weights::parse(&det_bytes, graph::PPOCR_DET)?;
    let rec_weights = Weights::parse(&rec_bytes, graph::PPOCR_REC)?;
    let shared = context::shared()?;
    // Both at fixed shapes so each records its command buffer once; see `post::ocr`.
    let det_plan = ppocr_det::build(&det_weights, ppocr_det::LONG_SIDE, ppocr_det::LONG_SIDE)?;
    let rec_plan = ppocr_rec::build(&rec_weights, ocr::REC_WIDTH)?;
    Ok(OcrHandle {
        det: Net::new(shared.clone(), det_plan, &det_weights, PPOCR_DET)?,
        rec: Net::new(shared, rec_plan, &rec_weights, PPOCR_REC)?,
        dictionary,
        pixels: Vec::new(),
    })
}

/// Recognise every line in `pixels` and return them as tab-separated text.
///
/// One line per region: `text`, then eight quad coordinates in source-bitmap pixels, then
/// the confidence, then `1` or `0` for vertical — ten fields after the text, tab-separated,
/// regions separated by newlines.
///
/// A string rather than a `float[]` plus a `String[]`, because the geometry and the text
/// belong to the same region and two arrays would have to be kept in step across the
/// boundary. It is safe to pack this way rather than lucky: the dictionary is 836 single
/// non-whitespace characters plus a space, so a decoded line can contain neither a tab nor
/// a newline, and `ctc::Dictionary::parse` rejects a file that broke that.
///
/// Returns null on failure or on a `0` handle. An empty string means no text, which is not
/// an error.
///
/// # Safety
///
/// `handle` must be `0` or a value returned by
/// [`Java_com_vayunmathur_library_ml_MlNative_createPpocr`] and not yet destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_recognizeText<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pixels: JIntArray<'l>,
    width: jint,
    height: jint,
) -> jstring {
    let null = std::ptr::null_mut();
    if handle == 0 || width <= 0 || height <= 0 {
        return null;
    }
    // SAFETY: as `segment` — the caller guarantees the handle is live and serialises this
    // against `destroyOcr`.
    let state = unsafe { &mut *(handle as *mut OcrHandle) };

    if let Err(e) = read_pixels(&mut env, &pixels, width, height, &mut state.pixels) {
        log(&e);
        return null;
    }
    let lines = match read_text(state, width as u32, height as u32) {
        Ok(lines) => lines,
        Err(e) => {
            log(&format!("OCR failed: {e}"));
            return null;
        }
    };
    match env.new_string(encode(&lines)) {
        Ok(text) => text.into_raw(),
        Err(e) => {
            log(&format!("cannot return the text: {e}"));
            null
        }
    }
}

/// Detect, crop, recognise and order. The Vulkan half of `post::ocr::lines`.
fn read_text(state: &mut OcrHandle, width: u32, height: u32) -> Result<Vec<Line>, String> {
    let fit = Letterbox::square(width, height, ppocr_det::LONG_SIDE)?;
    let maps = state.det.infer_letterboxed(&state.pixels, width, height, &fit)?;
    let probability = match maps.as_slice() {
        [only] => only,
        other => return Err(format!("detection returned {} maps, not one", other.len())),
    };
    let (map_w, map_h) = state.det.output_size()?;
    // Disjoint field borrows: the recogniser is taken mutably while the pixels and the
    // dictionary are read, which is why this is not `state.rec.infer(...)` inline.
    let OcrHandle { rec, dictionary, pixels, .. } = state;
    ocr::lines(
        &ocr::Detection { probability, width: map_w, height: map_h, fit: &fit },
        &ocr::Source { pixels, width, height },
        dictionary,
        |crop, crop_w, crop_h| rec.infer(crop, crop_w, crop_h),
    )
}

/// Pack the lines into the tab-separated form described on `recognizeText`.
fn encode(lines: &[Line]) -> String {
    let mut out = String::new();
    for line in lines {
        if !out.is_empty() {
            out.push('\n');
        }
        out.push_str(&line.text);
        for (x, y) in &line.corners {
            out.push('\t');
            out.push_str(&format!("{x}"));
            out.push('\t');
            out.push_str(&format!("{y}"));
        }
        out.push('\t');
        out.push_str(&format!("{}", line.confidence));
        out.push('\t');
        out.push(if line.vertical { '1' } else { '0' });
    }
    out
}

/// Free both networks and the dictionary.
///
/// Separate from [`Java_com_vayunmathur_library_ml_MlNative_destroy`] because the handle is
/// a different type; passing one to the other is undefined.
///
/// # Safety
///
/// `handle` must be `0` or a value returned by
/// [`Java_com_vayunmathur_library_ml_MlNative_createPpocr`], and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyOcr<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this came from `createPpocr` and has not been
    // destroyed. Each `Net`'s Drop waits for the device to go idle.
    drop(unsafe { Box::from_raw(handle as *mut OcrHandle) });
}

/// Copy a Java `int[]` of ARGB pixels into `into`, checking it against `width` x `height`.
fn read_pixels<'l>(
    env: &mut JNIEnv<'l>,
    pixels: &JIntArray<'l>,
    width: jint,
    height: jint,
    into: &mut Vec<i32>,
) -> Result<(), String> {
    let count = match env.get_array_length(pixels) {
        Ok(n) if n >= 0 => n as usize,
        Ok(n) => return Err(format!("a pixel array of length {n}")),
        Err(e) => return Err(format!("cannot size the pixel array: {e}")),
    };
    if count != (width as usize) * (height as usize) {
        return Err(format!("{count} pixels for a {width}x{height} bitmap"));
    }
    into.resize(count, 0);
    env.get_int_array_region(pixels, 0, into)
        .map_err(|e| format!("cannot read the pixel array: {e}"))
}

/// The mask's width, so Kotlin does not have to know either network's input size.
///
/// # Safety
///
/// As [`Java_com_vayunmathur_library_ml_MlNative_segment`].
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_maskWidth<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    // SAFETY: as `segment`.
    unsafe { &*(handle as *const Handle) }
        .net
        .output_size()
        .map(|(width, _)| width as jint)
        .unwrap_or(0)
}

/// The mask's height.
///
/// # Safety
///
/// As [`Java_com_vayunmathur_library_ml_MlNative_segment`].
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_maskHeight<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    // SAFETY: as `segment`.
    unsafe { &*(handle as *const Handle) }
        .net
        .output_size()
        .map(|(_, height)| height as jint)
        .unwrap_or(0)
}

/// Free everything the handle owns, waiting for the GPU to go idle first.
///
/// Must be called exactly once per non-zero handle. When it is the last segmenter, the
/// shared `VkDevice` goes away with it.
///
/// # Safety
///
/// As [`Java_com_vayunmathur_library_ml_MlNative_segment`], and the handle must not be
/// used again afterwards.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroy<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from a create function and has not
    // been destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut Handle) });
}

fn new_float_array(env: &mut JNIEnv, values: &[f32]) -> Result<jfloatArray, String> {
    let array: JFloatArray = env
        .new_float_array(values.len() as jint)
        .map_err(|e| format!("new_float_array: {e}"))?;
    env.set_float_array_region(&array, 0, values)
        .map_err(|e| format!("set_float_array_region: {e}"))?;
    Ok(array.into_raw())
}

fn log(message: &str) {
    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(priority: i32, tag: *const u8, text: *const u8) -> i32;
    }
    const ERROR: i32 = 6;
    let tag = b"ModelRunner\0";
    let mut text: Vec<u8> = message.as_bytes().to_vec();
    text.push(0);
    // SAFETY: both pointers are to NUL-terminated buffers that outlive the call.
    unsafe {
        let _ = __android_log_write(ERROR, tag.as_ptr(), text.as_ptr());
    }
}
