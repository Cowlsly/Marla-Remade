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

use std::fs::File;
use std::os::fd::FromRawFd;

use jni::objects::{JByteArray, JClass, JFloatArray, JIntArray, JLongArray, JString};
use jni::sys::{jfloatArray, jint, jlong, jstring};
use jni::JNIEnv;

use crate::nets::{
    mobilefacenet, ppocr_det, ppocr_rec, scrfd, selfie, small100, supertonic_duration,
    supertonic_sampler, supertonic_text, supertonic_vocoder, u2netp, Plan,
};
use crate::post::ctc::Dictionary;
use crate::post::nms::{self, Face, Maps};
use crate::post::ocr::{self, Line};
use crate::post::sentencepiece::Table;
use crate::post::supertonic;
use crate::post::translate;
use crate::preprocess::{
    Letterbox, FACE_EMBED, IMAGENET, PPOCR_DET, PPOCR_REC, RESCALE_ONLY, SCRFD,
};
use crate::vulkan::context;
use crate::vulkan::reshape::Reshaped;
use crate::vulkan::run::Net;
use crate::weights::{graph, Offsets, Streamed, Weights};

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
    // and has no filesystem path unless it is extracted first. Both `.maml` files are
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

/// A small deterministic-per-seed generator, seeded from the clock per utterance.
///
/// Speech is *meant* to vary: Supertonic's flow matching starts from a sampled latent, so two
/// readings of the same sentence differ. That is the model's design, not a defect. SplitMix64 is
/// used rather than a cryptographic source because nothing here is a secret and the sequence only
/// has to be well-distributed.
struct SplitMix {
    state: u64,
}

impl SplitMix {
    fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    /// Box-Muller over two uniforms, which is exact rather than an approximation of normal.
    fn normal(&mut self, count: usize) -> Vec<f32> {
        let mut out = Vec::with_capacity(count);
        while out.len() < count {
            // `next_u64 >> 11` gives 53 significant bits, and the `+ 1` keeps the log finite.
            let first = ((self.next_u64() >> 11) as f64 + 1.0) / 9_007_199_254_740_993.0;
            let second = ((self.next_u64() >> 11) as f64) / 9_007_199_254_740_992.0;
            let radius = (-2.0 * first.ln()).sqrt();
            let angle = std::f64::consts::TAU * second;
            out.push((radius * angle.cos()) as f32);
            if out.len() < count {
                out.push((radius * angle.sin()) as f32);
            }
        }
        out
    }
}

/// The single output of a plan that has exactly one.
fn one_output(outputs: Vec<Vec<f32>>) -> Result<Vec<f32>, String> {
    match <[Vec<f32>; 1]>::try_from(outputs) {
        Ok([only]) => Ok(only),
        Err(other) => Err(format!("{} outputs, expected one", other.len())),
    }
}

/// A seed from the clock, so two readings of a sentence differ as the model intends.
fn seed() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0x1234_5678_9ABC_DEF0)
        | 1
}

/// Supertonic's four nets, the conditioning read once, and the voice bundle.
///
/// Its own handle type for the same reason [`OcrHandle`] is: it owns a different set of things, and
/// one `destroy` guessing between them would be type confusion waiting for a caller to mix them up.
///
/// Each net is a [`Reshaped`] rather than a [`Net`], because every Supertonic plan is
/// utterance-shaped and there is no width to compile once and pad to.
struct SupertonicHandle {
    duration: Reshaped<u32>,
    text: Reshaped<u32>,
    /// Frames and characters, which vary independently.
    sampler: Reshaped<(u32, u32)>,
    vocoder: Reshaped<u32>,
    /// What the sampler needs from its weights file that no shader sees, walked once per handle.
    conditioning: supertonic::Conditioning,
    /// The 65,536-entry codepoint table, read as bytes with no parsing.
    indexer: Vec<u8>,
    voice: supertonic::Voice,
    rng: SplitMix,
}

/// The smallest legal shape, for the plan recorded at construction and immediately replaced.
///
/// [`Net::new`] needs a plan, and the real one is not known until an utterance arrives. Recording
/// the smallest is cheapest, and [`Net::rebuild`] only ever grows the arena, so nothing is wasted
/// by starting here.
const SMALLEST: u32 = 1;

fn duration_plan(offsets: &Offsets, chars: u32) -> Result<Plan, String> {
    supertonic_duration::build(offsets, chars)
}

fn text_plan(offsets: &Offsets, chars: u32) -> Result<Plan, String> {
    supertonic_text::build(offsets, chars)
}

fn sampler_plan(offsets: &Offsets, shape: (u32, u32)) -> Result<Plan, String> {
    supertonic_sampler::build(offsets, shape.0, shape.1)
}

fn vocoder_plan(offsets: &Offsets, frames: u32) -> Result<Plan, String> {
    supertonic_vocoder::build(offsets, frames)
}

/// The four nets, as `post::supertonic` wants them.
struct SupertonicNets<'a> {
    duration: &'a mut Reshaped<u32>,
    text: &'a mut Reshaped<u32>,
    sampler: &'a mut Reshaped<(u32, u32)>,
    vocoder: &'a mut Reshaped<u32>,
}

/// Positions per id tensor: `crate::nets::embed_lanes` writes two lanes, `lo + 2048 * hi`.
const LANES: usize = 2;

/// `values / stride`, refusing a remainder.
///
/// Every shape below is recovered from an input's length rather than passed alongside it, so the
/// net is always recorded at what the data actually is and cannot drift from what `synthesise`
/// computed. A remainder means the caller and the forward pass disagree about a channel count,
/// which is worth an error rather than a truncating division.
fn positions(what: &str, values: usize, stride: usize) -> Result<u32, String> {
    if stride == 0 || values == 0 || !values.is_multiple_of(stride) {
        return Err(format!("{what}: {values} values is not a whole number of {stride}"));
    }
    Ok((values / stride) as u32)
}

impl supertonic::Stages for SupertonicNets<'_> {
    fn duration(&mut self, lanes: &[f32], style: &[f32]) -> Result<f32, String> {
        // This sequence leads with the sentence token, which `build` adds itself, so it is given
        // the character count without it.
        let sequence = positions("duration ids", lanes.len(), LANES)?;
        let chars = sequence.checked_sub(1).ok_or("a duration pass over only a sentence token")?;
        let net = self.duration.at(chars)?;
        let out = one_output(net.infer_raw_many(&[lanes, style])?)?;
        match out.as_slice() {
            [only] => Ok(*only),
            other => {
                Err(format!("the duration predictor returned {} values, not one", other.len()))
            }
        }
    }

    fn text(&mut self, lanes: &[f32], style: &[f32]) -> Result<Vec<f32>, String> {
        let chars = positions("text ids", lanes.len(), LANES)?;
        let net = self.text.at(chars)?;
        one_output(net.infer_raw_many(&[lanes, style])?)
    }

    fn sampler(
        &mut self,
        latent: &[f32],
        text: &[f32],
        keys: &[f32],
        style: &[f32],
        shifts: &[f32],
        query_angles: &[f32],
        key_angles: &[f32],
    ) -> Result<Vec<f32>, String> {
        let frames = positions("a latent", latent.len(), supertonic_sampler::LATENT as usize)?;
        let chars = positions("a conditioning", text.len(), supertonic_sampler::TEXT as usize)?;
        let net = self.sampler.at((frames, chars))?;
        // Declaration order, which `infer_raw_many` checks each of against its own binding — the
        // seven are all fp16 planes and a swapped pair would be the right size.
        one_output(net.infer_raw_many(&[
            latent,
            text,
            keys,
            style,
            shifts,
            query_angles,
            key_angles,
        ])?)
    }

    fn vocoder(&mut self, latent: &[f32], frames: u32) -> Result<Vec<f32>, String> {
        let net = self.vocoder.at(frames)?;
        one_output(net.infer_raw(latent)?)
    }
}

/// `SupertonicSynthesizer`'s constructor. Returns 0 on failure, having logged why.
///
/// Six assets, in two kinds. The four `.maml` plans arrive as **file descriptors** with a byte
/// range each; the codepoint table and one voice's style file arrive as byte arrays, because they
/// are 128 KB and 25 KB and nothing is saved by streaming them.
///
/// The voice is separate from the plans and swappable through
/// [`Java_com_vayunmathur_library_ml_MlNative_setSupertonicVoice`], because it is 25 KB against
/// the plans' ~105 MB and re-uploading those to change voice would be absurd.
///
/// # Why the plans are descriptors and not arrays
///
/// A `ByteArray` path allocates the model **three times**: the Java `byte[]`, the `Vec<u8>`
/// [`JNIEnv::convert_byte_array`] hands back, and [`Weights::parse`]'s own copy of the data
/// section. At the size a bundled Supertonic comes to that is ~300 MB of transient heap for a
/// ~105 MB model, which is an out-of-memory kill on a low-RAM device rather than a slow load.
///
/// [`Streamed`] reads the header and table only — a few kilobytes — and the upload then pulls the
/// data section through a fixed-size staging buffer, so the peak is one chunk.
///
/// `fds`, `offsets` and `lengths` are parallel, in the order the four graphs are listed below:
/// duration predictor, text encoder, sampler, vocoder. An `AssetFileDescriptor` carries all three
/// because an asset is a *range of the APK* rather than a file of its own.
///
/// # Ownership
///
/// Each descriptor must be **detached** by the caller: this takes ownership and closes it, on the
/// failure paths as much as the successful one. `AssetManager.openFd` also requires the asset to be
/// stored uncompressed, which is what `noCompress += "maml"` is for.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env`, arrays it owns, and descriptors nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createSupertonic<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fds: JIntArray<'l>,
    offsets: JLongArray<'l>,
    lengths: JLongArray<'l>,
    indexer: JByteArray<'l>,
    style: JByteArray<'l>,
) -> jlong {
    match build_supertonic(&mut env, &fds, &offsets, &lengths, &indexer, &style) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("supertonic is unavailable: {e}"));
            0
        }
    }
}

/// The four `.maml` graphs, in the order `createSupertonic`'s parallel arrays list them.
const SUPERTONIC_GRAPHS: [u32; 4] =
    [graph::SUPERTONIC_DP, graph::SUPERTONIC_TTL, graph::SUPERTONIC_VE, graph::SUPERTONIC_VOC];

fn build_supertonic<'l>(
    env: &mut JNIEnv<'l>,
    fds: &JIntArray<'l>,
    offsets: &JLongArray<'l>,
    lengths: &JLongArray<'l>,
    indexer: &JByteArray<'l>,
    style: &JByteArray<'l>,
) -> Result<SupertonicHandle, String> {
    let count = SUPERTONIC_GRAPHS.len();
    // The descriptors first, and adopted into owning `File`s before anything else may fail. The
    // caller detached them, so a path that returns without wrapping one leaks it for the life of
    // the process — and one of the four is a descriptor onto the APK itself. Every check below the
    // adoption loop is therefore free to fail; nothing above it is.
    let opened = env
        .get_array_length(fds)
        .map_err(|e| format!("cannot size the descriptors: {e}"))? as usize;
    let mut raw = vec![0i32; opened];
    env.get_int_array_region(fds, 0, &mut raw)
        .map_err(|e| format!("cannot read the descriptors: {e}"))?;
    let mut files = Vec::with_capacity(opened);
    let mut unopened = None;
    for &fd in &raw {
        if fd < 0 {
            // Recorded rather than returned on, so the descriptors after it are still adopted.
            unopened = unopened.or(Some(fd));
            continue;
        }
        // SAFETY: the caller detached each descriptor, so nothing else owns it, and `File` closes
        // it on drop.
        files.push(unsafe { File::from_raw_fd(fd) });
    }
    if let Some(fd) = unopened {
        return Err(format!("descriptor {fd} is not open"));
    }
    if opened != count {
        return Err(format!("{opened} descriptors for {count} graphs"));
    }

    let mut at = vec![0i64; count];
    let mut len = vec![0i64; count];
    for (what, length) in [
        ("asset offsets", env.get_array_length(offsets)),
        ("asset lengths", env.get_array_length(lengths)),
    ] {
        let length = length.map_err(|e| format!("cannot size the {what}: {e}"))?;
        if length as usize != count {
            return Err(format!("{length} {what} for {count} graphs"));
        }
    }
    env.get_long_array_region(offsets, 0, &mut at)
        .map_err(|e| format!("cannot read the asset offsets: {e}"))?;
    env.get_long_array_region(lengths, 0, &mut len)
        .map_err(|e| format!("cannot read the asset lengths: {e}"))?;

    let mut streams = Vec::with_capacity(count);
    for (i, (file, graph_id)) in files.into_iter().zip(SUPERTONIC_GRAPHS).enumerate() {
        let (at, len) = (at.get(i).copied().unwrap_or(0), len.get(i).copied().unwrap_or(0));
        let (at, len) = match (u64::try_from(at), u64::try_from(len)) {
            (Ok(at), Ok(len)) => (at, len),
            _ => return Err(format!("graph {graph_id} spans {at}+{len}")),
        };
        streams.push(Streamed::open(file, at, len, graph_id)?);
    }
    let [duration_weights, text_weights, sampler_weights, vocoder_weights] =
        <[Streamed; 4]>::try_from(streams).map_err(|_| "four graphs were opened".to_string())?;

    let indexer = env
        .convert_byte_array(indexer)
        .map_err(|e| format!("cannot read the codepoint table: {e}"))?;
    if indexer.len() != supertonic::INDEXER_ENTRIES * 2 {
        return Err(format!("a codepoint table of {} bytes", indexer.len()));
    }
    let style =
        env.convert_byte_array(style).map_err(|e| format!("cannot read the style: {e}"))?;
    let voice = supertonic::Voice::read(&style)?;
    // The sampler's timestep shifts, rotary frequencies and folded style keys are all tensors in
    // its file that no shader ever reads, so they are read here rather than uploaded.
    let conditioning = supertonic::Conditioning::read(sampler_weights.reader())?;

    let shared = context::shared()?;
    let handle = SupertonicHandle {
        duration: Reshaped::streamed(
            shared.clone(),
            duration_weights.offsets(),
            &duration_weights,
            SMALLEST,
            duration_plan,
        )?,
        text: Reshaped::streamed(
            shared.clone(),
            text_weights.offsets(),
            &text_weights,
            SMALLEST,
            text_plan,
        )?,
        sampler: Reshaped::streamed(
            shared.clone(),
            sampler_weights.offsets(),
            &sampler_weights,
            (SMALLEST, SMALLEST),
            sampler_plan,
        )?,
        vocoder: Reshaped::streamed(
            shared,
            vocoder_weights.offsets(),
            &vocoder_weights,
            SMALLEST,
            vocoder_plan,
        )?,
        conditioning,
        indexer,
        voice,
        rng: SplitMix { state: seed() },
    };
    // The four `Streamed` drop here, closing their descriptors: every byte they held is either in
    // device memory or in `conditioning`.
    Ok(handle)
}

/// Point an existing handle at another voice. Returns false on failure, having logged why.
///
/// # Safety
///
/// `handle` must be `0` or a live value from
/// [`Java_com_vayunmathur_library_ml_MlNative_createSupertonic`].
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_setSupertonicVoice<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    style: JByteArray<'l>,
) -> jni::sys::jboolean {
    if handle == 0 {
        return 0;
    }
    // SAFETY: as `synthesizeSupertonic` — the caller guarantees the handle is live and serialises
    // this against `destroySupertonic`.
    let state = unsafe { &mut *(handle as *mut SupertonicHandle) };
    let read = env
        .convert_byte_array(&style)
        .map_err(|e| format!("cannot read the voice: {e}"))
        .and_then(|bytes| supertonic::Voice::read(&bytes));
    match read {
        Ok(voice) => {
            state.voice = voice;
            1
        }
        Err(e) => {
            log(&format!("cannot change voice: {e}"));
            0
        }
    }
}

/// Free the four networks and everything beside them.
///
/// # Safety
///
/// `handle` must be `0` or a value from
/// [`Java_com_vayunmathur_library_ml_MlNative_createSupertonic`], and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroySupertonic<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this came from `createSupertonic` and has not been destroyed.
    drop(unsafe { Box::from_raw(handle as *mut SupertonicHandle) });
}

/// Synthesise `text` and return the waveform, or null on failure.
///
/// `text` must already be **NFD**-decomposed. That is the Kotlin side's job, through
/// `java.text.Normalizer`, because the model has no precomposed accents and doing the
/// decomposition here would mean carrying Unicode tables in the APK — see
/// [`supertonic::to_ids`].
///
/// The samples are mono `-1..1` at 44,100 Hz. Two calls with the same text differ: flow matching
/// starts from a sampled latent, which it is meant to.
///
/// # Safety
///
/// `handle` must be `0` or a live value from
/// [`Java_com_vayunmathur_library_ml_MlNative_createSupertonic`].
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_synthesizeSupertonic<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    text: JString<'l>,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: as `segment` — the caller guarantees the handle is live and serialises this against
    // `destroySupertonic`.
    let state = unsafe { &mut *(handle as *mut SupertonicHandle) };
    let words: String = match env.get_string(&text) {
        Ok(found) => found.into(),
        Err(e) => {
            log(&format!("cannot read the text: {e}"));
            return null;
        }
    };
    match speak_supertonic(state, &words) {
        Ok(samples) => match new_float_array(&mut env, &samples) {
            Ok(array) => array,
            Err(e) => {
                log(&e);
                null
            }
        },
        Err(e) => {
            log(&format!("synthesis failed: {e}"));
            null
        }
    }
}

/// The whole pipeline for one utterance.
fn speak_supertonic(state: &mut SupertonicHandle, text: &str) -> Result<Vec<f32>, String> {
    let SupertonicHandle {
        duration,
        text: encoder,
        sampler,
        vocoder,
        conditioning,
        indexer,
        voice,
        rng,
    } = state;
    // `synthesise` draws the starting latent once, after the duration predictor has settled the
    // frame count, so the generator has to be reachable from a `Fn` rather than pre-drawn.
    let noise = std::cell::RefCell::new(rng);
    let mut nets = SupertonicNets { duration, text: encoder, sampler, vocoder };
    supertonic::synthesise(&mut nets, conditioning, indexer, voice, text, &|count| {
        noise.borrow_mut().normal(count)
    })
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

/// SMaLL-100, as `:translate` holds it. Handed to Kotlin as an opaque `jlong`.
///
/// Its own handle type for the same reason [`SupertonicHandle`] is: it owns a different set of
/// things, and one `destroy` guessing between them would be type confusion waiting to happen.
///
/// # One net, three plans
///
/// [`Reshaped`] keyed by [`small100::Mode`] rather than by a length. The encoder, the decode step
/// and the logits projection are three passes over **one** 318 MiB file, so three `Net`s would
/// upload it three times; one net rebuilt per pass uploads it once. A rebuild is a
/// `device_wait_idle` and a re-record, and a decode step's cache grows by one position each time,
/// so a translation of `n` tokens costs `n + 1` of them. That is the known cost of holding the KV
/// cache on the host, and it is what a future prefix bound in [`crate::nets::Push`] would remove.
///
/// # The weights file stays open
///
/// Unlike Supertonic, whose host-side tensors are read once into [`supertonic::Conditioning`],
/// SMaLL-100's host-side tensor is the 125 MiB tied embedding, which cannot be pre-read. So the
/// [`Streamed`] is retained and [`small100::embed_positions`] gathers a 1 KB row per token from it.
struct Small100Handle {
    net: Reshaped<small100::Mode>,
    weights: Streamed,
    /// `scripts/ml/small100_tokenizer.py`'s table, 1.7 MB, parsed per translation.
    tokenizer: Vec<u8>,
}

fn small100_plan(offsets: &Offsets, mode: small100::Mode) -> Result<Plan, String> {
    small100::build(offsets, mode)
}

/// The two GPU passes, as [`translate::Nets`] wants them, plus the host-side KV cache.
///
/// Built per translation and dropped with it, so a cache cannot leak into the next sentence — the
/// failure `Net::reset` would otherwise exist to prevent.
struct Small100Nets<'a> {
    net: &'a mut Reshaped<small100::Mode>,
    weights: &'a Streamed,
    /// Per decoder layer, the K then V for every position decoded so far, position-major and
    /// concatenated. `[cache_len * D_MODEL]` each, appended one row at a time.
    cache: Vec<Vec<f32>>,
    /// Source positions, so a decode step records at the length the encoder ran at.
    src_len: u32,
}

/// K and V per decoder layer, which is how many cache buffers there are.
const SMALL100_CACHES: usize = small100::DECODER_LAYERS * 2;

impl Small100Nets<'_> {
    fn new<'a>(handle: &'a mut Small100Handle) -> Small100Nets<'a> {
        Small100Nets {
            net: &mut handle.net,
            weights: &handle.weights,
            cache: vec![Vec::new(); SMALL100_CACHES],
            src_len: 0,
        }
    }
}

impl translate::Nets for Small100Nets<'_> {
    fn encode(&mut self, source: &[u32]) -> Result<Vec<f32>, String> {
        let len = u32::try_from(source.len()).map_err(|_| "a source longer than u32")?;
        let reader = self.weights.reader();
        // The embedding, `sqrt(d_model)` and the sinusoidal positions, all on the host. See
        // `nets::small100` for why none of that is a shader.
        let embedded = small100::embed_positions(reader, source, 0)?;
        let net = self.net.at(small100::Mode::Encode { len })?;
        let out = one_output(net.infer_raw(&embedded)?)?;
        self.src_len = len;
        // The plan produces `[d_model, 1, len]`; the trait's contract is `[len, d_model]`. One
        // transpose here rather than a comment that disagrees with the trait.
        Ok(transpose(&out, small100::D_MODEL as usize, source.len()))
    }

    fn decode_step(
        &mut self,
        token: u32,
        step: usize,
        encoded: &[f32],
    ) -> Result<Vec<f32>, String> {
        let width = small100::D_MODEL as usize;
        let cache_len = u32::try_from(step).map_err(|_| "a step past u32")?;
        let reader = self.weights.reader();
        // `past = step`, which is what puts this token at position `step + 2`.
        let embedded = small100::embed_positions(reader, &[token], cache_len)?;
        if !encoded.len().is_multiple_of(width) {
            return Err(format!("{} encoder values is not a whole number of {width}", encoded.len()));
        }
        let src_len = (encoded.len() / width) as u32;
        if src_len != self.src_len {
            return Err(format!("a step over {src_len} source positions after {}", self.src_len));
        }
        // Back to `[d_model, 1, src_len]`, which is what the cross-attention projections read.
        let source = transpose(encoded, encoded.len() / width, width);

        let net = self.net.at(small100::Mode::DecodeStep { cache_len, src_len })?;
        // Declaration order: the token, the encoder output, then each layer's K and V. The cache
        // pair is absent at step 0, where there is nothing before this token.
        let mut inputs: Vec<&[f32]> = Vec::with_capacity(2 + SMALL100_CACHES);
        inputs.push(&embedded);
        inputs.push(&source);
        if cache_len > 0 {
            for held in &self.cache {
                inputs.push(held);
            }
        }
        let out = net.infer_raw_many(&inputs)?;

        // Two logits halves, then this step's K and V per layer. The halves are consecutive class
        // ranges, so concatenating them is the 128,112-wide vector `post::translate` argmaxes.
        let expected = small100::HEAD_SPLITS + SMALL100_CACHES;
        if out.len() != expected {
            return Err(format!("a decode step returned {} tensors, not {expected}", out.len()));
        }
        let mut logits = Vec::with_capacity(small100::VOCAB as usize);
        for half in out.iter().take(small100::HEAD_SPLITS) {
            logits.extend_from_slice(half);
        }
        if logits.len() != small100::VOCAB as usize {
            return Err(format!("{} logits, not {}", logits.len(), small100::VOCAB));
        }
        for (held, row) in self.cache.iter_mut().zip(out.iter().skip(small100::HEAD_SPLITS)) {
            if row.len() != width {
                return Err(format!("a cache row of {} values, not {width}", row.len()));
            }
            // Position-major, so appending is a plain extend and the plan's own concatenation of
            // it is one contiguous copy.
            held.extend_from_slice(row);
        }
        Ok(logits)
    }
}

/// `[rows, columns]` to `[columns, rows]`.
///
/// The encoder output crosses the `translate::Nets` seam as `[positions, d_model]` and this runtime
/// works in `[d_model, positions]`, so it is transposed once on the way out and once per step on
/// the way back in. Eight kilobytes for a sentence, against a trait whose documented shape would
/// otherwise be wrong.
fn transpose(values: &[f32], rows: usize, columns: usize) -> Vec<f32> {
    let mut out = vec![0.0f32; values.len()];
    for row in 0..rows {
        for column in 0..columns {
            if let (Some(&from), Some(slot)) =
                (values.get(row * columns + column), out.get_mut(column * rows + row))
            {
                *slot = from;
            }
        }
    }
    out
}

/// Bring up SMaLL-100 from its one `.maml` and its tokenizer table. Returns 0 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env`, arrays it owns, and a descriptor nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createSmall100<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
    tokenizer: JByteArray<'l>,
) -> jlong {
    // The descriptor first, and adopted into an owning `File` before anything else may fail: the
    // caller detached it, so a path that returns without wrapping it leaks it for the life of the
    // process. Every check below the adoption is therefore free to fail; nothing above it is.
    if fd < 0 {
        log(&format!("small100 is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes it on
    // drop — including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_small100(&mut env, file, offset, length, &tokenizer) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("small100 is unavailable: {e}"));
            0
        }
    }
}

fn build_small100<'l>(
    env: &mut JNIEnv<'l>,
    file: File,
    offset: jlong,
    length: jlong,
    tokenizer: &JByteArray<'l>,
) -> Result<Small100Handle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::SMALL100)?;

    let tokenizer = env
        .convert_byte_array(tokenizer)
        .map_err(|e| format!("cannot read the tokenizer table: {e}"))?;
    // Parsed once here purely to refuse a bad table at construction rather than at the first
    // translation, when the UI has already committed to having a working engine.
    let parsed = Table::parse(&tokenizer)?;
    if parsed.len() != small100::VOCAB as usize {
        return Err(format!("a tokenizer of {} pieces, not {}", parsed.len(), small100::VOCAB));
    }

    // The smallest legal encoder, immediately replaced: `Net::new` needs a plan and the real shapes
    // are not known until a sentence arrives. `Net::rebuild` only ever grows the arena.
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        small100::Mode::Encode { len: SMALLEST },
        small100_plan,
    )?;
    Ok(Small100Handle { net, weights, tokenizer })
}

/// Translate [`text`] into the language `target_token` names, or null on failure.
///
/// `text` must already be NFKC — `java.text.Normalizer.normalize(text, Form.NFKC)`. The model's
/// normaliser is `nmt_nfkc` with a 237 KB precompiled charsmap, and reproducing that natively would
/// mean carrying Unicode tables the platform already has. See `post::sentencepiece`.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createSmall100` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_translateSmall100<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    text: JString<'l>,
    target_token: jint,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    // SAFETY: the caller guarantees the handle came from `createSmall100` and is still live. It is
    // `&mut` because a decode step re-records the net, and Kotlin serialises calls on one handle.
    let handle = unsafe { &mut *(handle as *mut Small100Handle) };
    let translated = match env.get_string(&text) {
        Ok(text) => run_small100(handle, &String::from(text), target_token),
        Err(e) => Err(format!("cannot read the source text: {e}")),
    };
    match translated {
        Ok(out) => match env.new_string(&out) {
            Ok(string) => string.into_raw(),
            Err(e) => {
                log(&format!("small100 cannot return its translation: {e}"));
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            log(&format!("small100 failed: {e}"));
            std::ptr::null_mut()
        }
    }
}

fn run_small100(
    handle: &mut Small100Handle,
    text: &str,
    target_token: jint,
) -> Result<String, String> {
    let target = u32::try_from(target_token).map_err(|_| format!("{target_token} is not a token"))?;
    // Cloned so the table can borrow it while the nets borrow the handle mutably. 1.7 MB against a
    // translation that uploads nothing and reads a 318 MiB file.
    let tokenizer = handle.tokenizer.clone();
    let table = Table::parse(&tokenizer)?;
    let mut nets = Small100Nets::new(handle);
    translate::translate(&mut nets, &table, target, text)
}

/// Free SMaLL-100's net, its open weights file and its tokenizer table.
///
/// Exactly once per non-zero handle from `createSmall100`. When it is the last user of the shared
/// `VkDevice`, the device goes away with it.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createSmall100`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroySmall100<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createSmall100` and has not been
    // destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut Small100Handle) });
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
