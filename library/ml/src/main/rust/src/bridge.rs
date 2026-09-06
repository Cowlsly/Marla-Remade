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

use jni::objects::{
    JByteArray, JClass, JFloatArray, JIntArray, JLongArray, JObjectArray, JShortArray, JString,
};
use jni::sys::{jfloatArray, jint, jintArray, jlong, jstring};
use jni::JNIEnv;

use crate::nets::{
    gemma4, gemma4_audio, gemma4_vision, maia, mobilefacenet, nllb, nnfp, ppocr_det, ppocr_rec,
    scrfd, selfie, supertonic_duration, supertonic_sampler, supertonic_text, supertonic_vocoder,
    tinyclip, u2netp, whisper, Plan,
};
use crate::post::ctc::Dictionary;
use crate::post::nms::{self, Face, Maps};
use crate::post::ocr::{self, Line};
use crate::post::sentencepiece::{Table, GEMMA};
use crate::post::supertonic;
use crate::post::translate;
use crate::post::whisper as whisper_post;
use crate::preprocess::{
    Letterbox, FACE_EMBED, IMAGENET, PPOCR_DET, PPOCR_REC, RESCALE_ONLY, SCRFD,
};
use crate::vulkan::context;
use crate::vulkan::reshape::Reshaped;
use crate::vulkan::run::{Net, StepParams};
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
        // Two outputs, in `nets::supertonic_duration`'s declaration order: the sentence encoder's
        // hidden states, then the one value `seconds` exponentiates. Only the second is wanted here.
        // The first exists because it is what `scripts/ml/onnx_parity.py` probes for this graph — the
        // net's own output is a single scalar, and a correlation over one value is not a number.
        //
        // Reading it with `one_output` was this engine's original bug: the duration predictor has
        // always returned two tensors, so every synthesis failed at the first stage with
        // "2 outputs, expected one" and no audio was ever produced.
        let out = net.infer_raw_many(&[lanes, style])?;
        let [_encoded, log_seconds] = <[Vec<f32>; 2]>::try_from(out).map_err(|other| {
            format!("the duration predictor returned {} tensors, not two", other.len())
        })?;
        match log_seconds.as_slice() {
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
        // Two marshalling steps, both the host's job — `nets::supertonic_vocoder::build`'s own doc
        // says the input is "reinterpreted" and the output "read transposed", and the parity path in
        // `nets::reference` does both. Production did neither, which is what made the engine whine:
        //
        //  - the latent arrives `[144, frames]` and the plan reads `[24, 6 * frames]`, and that is
        //    *not* a flat reinterpretation. Assuming one correlates with the reference at 0.009.
        //  - the plan emits `[512, 1, T]` channel-major while a waveform is time-major. Handing that
        //    to AudioTrack unchanged plays each channel plane as if it were consecutive samples, so
        //    every 512th value is a real neighbour and the rest is a periodic artefact — a loud tone
        //    at the frame rate rather than speech.
        let unpacked = supertonic_vocoder::unpack_latent(latent, frames as usize)?;
        let channelled = one_output(net.infer_raw(&unpacked)?)?;
        Ok(supertonic_vocoder::interleave(&channelled))
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

/// Synthesise `text` in `language` and return the waveform, or null on failure.
///
/// `text` must already be **NFKD**-decomposed. That is the Kotlin side's job, through
/// `java.text.Normalizer`, because the model has no precomposed accents and doing the
/// decomposition here would mean carrying Unicode tables in the APK — see
/// [`supertonic::to_ids`].
///
/// `language` is the ISO-639-1 code the model should read in, or `na` for one it does not list.
/// It is not a hint: Supertonic 3 is the multilingual model and was trained with the tag always
/// present, so a wrong or missing one produces fluent-sounding non-words rather than an error.
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
    language: JString<'l>,
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
    let code: String = match env.get_string(&language) {
        Ok(found) => found.into(),
        Err(e) => {
            log(&format!("cannot read the language: {e}"));
            return null;
        }
    };
    match speak_supertonic(state, &words, &code) {
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
fn speak_supertonic(
    state: &mut SupertonicHandle,
    text: &str,
    language: &str,
) -> Result<Vec<f32>, String> {
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
    supertonic::synthesise(&mut nets, conditioning, indexer, voice, text, language, &|count| {
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

/// NLLB-200-distilled-600M, as `:translate` holds it. Handed to Kotlin as an opaque `jlong`.
///
/// Its own handle type for the same reason the translation handle is: it owns a different set of
/// things, and one `destroy` guessing between them would be type confusion waiting to happen.
///
/// # One net, two passes
///
/// [`Reshaped`] keyed by [`nllb::Mode`]. The encoder and the decode step (which computes the tied
/// head itself) are two passes over **one** ~600 MiB file, so two `Net`s would upload it twice.
/// A rebuild is a `device_wait_idle` and a re-record, and a decode step's cache grows by one
/// position each time, so a translation of `n` tokens costs `n + 1` of them. That is the known
/// cost of holding the KV cache on the host, and it is what a future prefix bound in
/// [`crate::nets::Push`] would remove.
///
/// # The weights file stays open
///
/// Unlike Supertonic, whose host-side tensors are read once into [`supertonic::Conditioning`],
/// NLLB's host-side tensor is the ~250 MiB tied embedding, which cannot be pre-read. So the
/// [`Streamed`] is retained and [`nllb::embed_positions`] gathers a 1 KB row per token from it.
///
/// The tensor order is the contract in `maml_convert.collect_nllb` — see `nets::nllb`.
struct NllbHandle {
    net: Reshaped<nllb::Mode>,
    weights: Streamed,
    /// `scripts/ml/nllb_tokenizer.py`'s table, parsed per translation.
    tokenizer: Vec<u8>,
}

fn nllb_plan(offsets: &Offsets, mode: nllb::Mode) -> Result<Plan, String> {
    nllb::build(offsets, mode)
}

/// The two GPU passes, as [`translate::Nets`] wants them.
///
/// There is no host-side KV cache any more: the decode plan holds one in the arena and writes to
/// it in place. A sentence cannot leak into the next one because switching back to
/// [`nllb::Mode::Encode`] and out again re-records the plan, and the first step of a translation
/// writes row zero before reading anything.
struct NllbNets<'a> {
    net: &'a mut Reshaped<nllb::Mode>,
    weights: &'a Streamed,
    /// Source positions, so a decode step records at the length the encoder ran at.
    src_len: u32,
}

impl NllbNets<'_> {
    fn new<'a>(handle: &'a mut NllbHandle) -> NllbNets<'a> {
        NllbNets { net: &mut handle.net, weights: &handle.weights, src_len: 0 }
    }
}

impl translate::Nets for NllbNets<'_> {
    fn encode(&mut self, source: &[u32]) -> Result<Vec<f32>, String> {
        let len = u32::try_from(source.len()).map_err(|_| "a source longer than u32")?;
        let reader = self.weights.reader();
        // The embedding, `sqrt(d_model)` and the sinusoidal positions, all on the host. See
        // `nets::nllb` for why none of that is a shader.
        let embedded = nllb::embed_positions(reader, source, 0)?;
        let net = self.net.at(nllb::Mode::Encode { len })?;
        let out = one_output(net.infer_raw(&embedded)?)?;
        self.src_len = len;
        // The plan produces `[d_model, 1, len]`; the trait's contract is `[len, d_model]`. One
        // transpose here rather than a comment that disagrees with the trait.
        Ok(transpose(&out, nllb::D_MODEL as usize, source.len()))
    }

    fn decode_step(
        &mut self,
        token: u32,
        step: usize,
        encoded: &[f32],
    ) -> Result<Vec<f32>, String> {
        let width = nllb::D_MODEL as usize;
        let cache_len = u32::try_from(step).map_err(|_| "a step past u32")?;
        if cache_len >= nllb::MAX_DECODE_POSITIONS {
            return Err(format!(
                "step {step} is past the {} the KV cache holds",
                nllb::MAX_DECODE_POSITIONS
            ));
        }
        let reader = self.weights.reader();
        // `past = step`, which is what puts this token at position `step + 2`.
        let embedded = nllb::embed_positions(reader, &[token], cache_len)?;
        if !encoded.len().is_multiple_of(width) {
            return Err(format!("{} encoder values is not a whole number of {width}", encoded.len()));
        }
        let src_len = (encoded.len() / width) as u32;
        if src_len != self.src_len {
            return Err(format!("a step over {src_len} source positions after {}", self.src_len));
        }
        // Back to `[d_model, 1, src_len]`, which is what the cross-attention projections read.
        let source = transpose(encoded, encoded.len() / width, width);

        // The key no longer carries the step, so this matches after the first decode step and
        // `at` stops re-recording. The KV cache stays in the arena between submits.
        let net = self.net.at(nllb::Mode::DecodeStep { src_len })?;
        // The one thing that changes per token, and it is a memcpy rather than a re-record.
        net.set_params(StepParams { prefix: cache_len, window_start: 0 })?;
        let out = net.infer_raw_many(&[&embedded, &source])?;

        // Four logits splits and nothing else: the K and V rows are written straight into the
        // device-side cache by the plan, so there is no cache to bring back.
        if out.len() != nllb::HEAD_SPLITS {
            return Err(format!(
                "a decode step returned {} tensors, not {}",
                out.len(),
                nllb::HEAD_SPLITS
            ));
        }
        let mut logits = Vec::with_capacity(nllb::VOCAB as usize);
        for half in out.iter().take(nllb::HEAD_SPLITS) {
            logits.extend_from_slice(half);
        }
        if logits.len() != nllb::VOCAB as usize {
            return Err(format!("{} logits, not {}", logits.len(), nllb::VOCAB));
        }
        Ok(logits)
    }
}

/// Bring up NLLB from its one `.maml` and its tokenizer table. Returns 0 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env`, arrays it owns, and a descriptor nothing else holds.
///
/// `createNllb` mirrors the old `createSmall100`, and `translateNllb` takes **both** a source and a
/// target token — the protocol flip vs small100. Agreed with app-eng (team `nllb-translate`).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createNllb<'l>(
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
        log(&format!("nllb is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes it on
    // drop — including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_nllb(&mut env, file, offset, length, &tokenizer) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("nllb is unavailable: {e}"));
            0
        }
    }
}

fn build_nllb<'l>(
    env: &mut JNIEnv<'l>,
    file: File,
    offset: jlong,
    length: jlong,
    tokenizer: &JByteArray<'l>,
) -> Result<NllbHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::NLLB)?;

    let tokenizer = env
        .convert_byte_array(tokenizer)
        .map_err(|e| format!("cannot read the tokenizer table: {e}"))?;
    // Parsed once here purely to refuse a bad table at construction rather than at the first
    // translation, when the UI has already committed to having a working engine.
    let parsed = Table::parse(&tokenizer)?;
    if parsed.len() != nllb::VOCAB as usize {
        return Err(format!("a tokenizer of {} pieces, not {}", parsed.len(), nllb::VOCAB));
    }

    // The smallest legal encoder, immediately replaced: `Net::new` needs a plan and the real shapes
    // are not known until a sentence arrives. `Net::rebuild` only ever grows the arena.
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        nllb::Mode::Encode { len: SMALLEST },
        nllb_plan,
    )?;
    Ok(NllbHandle { net, weights, tokenizer })
}

/// Translate [`text`] from the language `source_token` names into the language `target_token`
/// names, or null on failure.
///
/// `text` must already be NFKC — `java.text.Normalizer.normalize(text, Form.NFKC)`. The model's
/// normaliser is `nmt_nfkc` with a precompiled charsmap, and reproducing that natively would
/// mean carrying Unicode tables the platform already has. See `post::sentencepiece`.
///
/// Unlike small100 before it, BOTH tokens are required: the source token goes on the encoder source
/// and the
/// target token forced-BOSes the decoder. Backwards it produces fluent output in the wrong
/// language rather than an error.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createNllb` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_translateNllb<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    text: JString<'l>,
    source_token: jint,
    target_token: jint,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    // SAFETY: the caller guarantees the handle came from `createNllb` and is still live. It is
    // `&mut` because a decode step re-records the net, and Kotlin serialises calls on one handle.
    let handle = unsafe { &mut *(handle as *mut NllbHandle) };
    let translated = match env.get_string(&text) {
        Ok(text) => run_nllb(handle, &String::from(text), source_token, target_token),
        Err(e) => Err(format!("cannot read the source text: {e}")),
    };
    match translated {
        Ok(out) => match env.new_string(&out) {
            Ok(string) => string.into_raw(),
            Err(e) => {
                log(&format!("nllb cannot return its translation: {e}"));
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            log(&format!("nllb failed: {e}"));
            std::ptr::null_mut()
        }
    }
}

fn run_nllb(
    handle: &mut NllbHandle,
    text: &str,
    source_token: jint,
    target_token: jint,
) -> Result<String, String> {
    let source =
        u32::try_from(source_token).map_err(|_| format!("{source_token} is not a token"))?;
    let target =
        u32::try_from(target_token).map_err(|_| format!("{target_token} is not a token"))?;
    // Cloned so the table can borrow it while the nets borrow the handle mutably.
    let tokenizer = handle.tokenizer.clone();
    let table = Table::parse(&tokenizer)?;
    let mut nets = NllbNets::new(handle);
    translate::translate(&mut nets, &table, source, target, text)
}

/// Free NLLB's net, its open weights file and its tokenizer table.
///
/// Exactly once per non-zero handle from `createNllb`. When it is the last user of the shared
/// `VkDevice`, the device goes away with it.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createNllb`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyNllb<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createNllb` and has not been
    // destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut NllbHandle) });
}

/// TinyCLIP, as `:photos` holds it. Handed to Kotlin as an opaque `jlong`.
///
/// # One net, two plans
///
/// [`Reshaped`] keyed by [`tinyclip::Mode`], as the translation handle is: the two towers share no
/// weights but they do share a 22.6 MiB file, so two `Net`s would upload it twice. Switching towers
/// is a `device_wait_idle` and a re-record — which is why an indexing run, which is `Mode::Image`
/// throughout, pays for exactly one.
///
/// # The weights file stays open
///
/// [`tinyclip::embed_positions`] gathers a 1 KB embedding row per token out of the 12.6 MiB token
/// table on the host, so the [`Streamed`] is retained for the same reason SMaLL-100's is.
struct TinyclipHandle {
    net: Reshaped<tinyclip::Mode>,
    weights: Streamed,
}

fn tinyclip_plan(offsets: &Offsets, mode: tinyclip::Mode) -> Result<Plan, String> {
    tinyclip::build(offsets, mode)
}

/// One column of a `[PROJECTION, 1, len]` output, which is the position both towers pool.
///
/// A position is a *column* in this runtime's layout, so it is strided rather than contiguous —
/// which is why the plan projects every position and this picks one. See `nets::tinyclip`.
fn tinyclip_column(out: &[f32], at: usize, len: usize) -> Result<Vec<f32>, String> {
    let width = tinyclip::PROJECTION as usize;
    if len == 0 || out.len() != width * len {
        return Err(format!("{} values is not {width} x {len}", out.len()));
    }
    (0..width)
        .map(|channel| {
            out.get(channel * len + at).copied().ok_or_else(|| format!("column {at} of {len}"))
        })
        .collect()
}

/// Bring up TinyCLIP from its one `.maml`. Returns 0 on failure.
///
/// The descriptor is an `AssetFileDescriptor`'s, so it carries an offset and a length: the file is
/// a *range of the APK* rather than a file of its own, which is also why the asset has to be stored
/// uncompressed.
///
/// # Safety
///
/// Called only by the JVM, with a descriptor nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createTinyclip<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
) -> jlong {
    // The descriptor first, and adopted into an owning `File` before anything else may fail, as
    // the old `createSmall100` did: the caller detached it, so a path that returns without wrapping
    // it
    // leaks it for the life of the process.
    if fd < 0 {
        log(&format!("tinyclip is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes it on
    // drop — including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_tinyclip(file, offset, length) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("tinyclip is unavailable: {e}"));
            0
        }
    }
}

fn build_tinyclip(file: File, offset: jlong, length: jlong) -> Result<TinyclipHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::TINYCLIP)?;
    if weights.len() != tinyclip::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), tinyclip::TENSORS));
    }
    // Recorded on the image tower, which is what an indexing run uses throughout. A text query
    // rebuilds once and rebuilds back on the next image.
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        tinyclip::Mode::Image,
        tinyclip_plan,
    )?;
    Ok(TinyclipHandle { net, weights })
}

/// The 512-d image embedding for `pixels`, or null on failure.
///
/// `pixels` is `[3, 224, 224]` already normalised by CLIP's mean and standard deviation — the
/// resize, the centre crop and the normalisation are `ClipEmbedder.preprocess`'s, because they are
/// bitmap work the platform does better and they are what the tokenizer's counterpart would be.
///
/// The vector is **not** L2-normalised here. `ClipEmbedder.l2Normalize` does it, on both towers'
/// output and on nothing else, so the stored BLOB format is decided in one place.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createTinyclip` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_tinyclipImage<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pixels: JFloatArray<'l>,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: the caller guarantees the handle came from `createTinyclip` and is still live. It is
    // `&mut` because switching towers re-records the net, and Kotlin serialises calls on one handle.
    let handle = unsafe { &mut *(handle as *mut TinyclipHandle) };
    let embedded = match read_float_array(&mut env, &pixels) {
        Ok(values) => run_tinyclip_image(handle, &values),
        Err(e) => Err(e),
    };
    match embedded.and_then(|values| new_float_array(&mut env, &values)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("tinyclip's image tower failed: {e}"));
            null
        }
    }
}

fn run_tinyclip_image(handle: &mut TinyclipHandle, pixels: &[f32]) -> Result<Vec<f32>, String> {
    let side = tinyclip::IMAGE_SIZE as usize;
    let expected = 3 * side * side;
    if pixels.len() != expected {
        return Err(format!("{} pixel values, not {expected}", pixels.len()));
    }
    let net = handle.net.at(tinyclip::Mode::Image)?;
    let out = one_output(net.infer_raw_many(&[pixels])?)?;
    // The class token, position 0. Pooling the mean, or the last position, would produce a
    // normalised 512-d vector that is simply wrong; see `nets::tinyclip`.
    tinyclip_column(&out, 0, tinyclip::VISION_POSITIONS as usize)
}

/// The 512-d text embedding for `ids`, or null on failure.
///
/// `ids` must be the query's tokens **up to and including `<|endoftext|>`**, with the tokenizer's
/// padding trimmed off. CLIP pools at the end-of-text position, so the caller's trim decides which
/// position is pooled — and because the tower is causal, running `ids.len()` positions instead of
/// the padded 77 gives the identical vector for a fraction of the work.
///
/// Not L2-normalised, as `tinyclipImage` is not.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createTinyclip` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_tinyclipText<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    ids: JIntArray<'l>,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: as `tinyclipImage`.
    let handle = unsafe { &mut *(handle as *mut TinyclipHandle) };
    let embedded = match read_int_array(&mut env, &ids) {
        Ok(values) => run_tinyclip_text(handle, &values),
        Err(e) => Err(e),
    };
    match embedded.and_then(|values| new_float_array(&mut env, &values)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("tinyclip's text tower failed: {e}"));
            null
        }
    }
}

fn run_tinyclip_text(handle: &mut TinyclipHandle, ids: &[i32]) -> Result<Vec<f32>, String> {
    let tokens: Vec<u32> = ids
        .iter()
        .map(|&id| u32::try_from(id).map_err(|_| format!("{id} is not a token")))
        .collect::<Result<_, _>>()?;
    // The embedding and the learned positions, both on the host and summed in f32. See
    // `nets::tinyclip` for why neither is a shader.
    let embedded = tinyclip::embed_positions(handle.weights.reader(), &tokens)?;
    let len = u32::try_from(tokens.len()).map_err(|_| "a query longer than u32")?;
    let net = handle.net.at(tinyclip::Mode::Text { len })?;
    let out = one_output(net.infer_raw_many(&[&embedded])?)?;
    // The end-of-text position, which the caller's trim made the last one.
    tinyclip_column(&out, tokens.len() - 1, tokens.len())
}

/// Free TinyCLIP's net and its open weights file.
///
/// Exactly once per non-zero handle from `createTinyclip`. When it is the last user of the shared
/// `VkDevice`, the device goes away with it.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createTinyclip`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyTinyclip<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createTinyclip` and has not been
    // destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut TinyclipHandle) });
}

/// Maia3-5M, as `:games:chess` holds it. Handed to Kotlin as an opaque `jlong`.
///
/// # One net, one plan
///
/// A plain [`Net`] rather than a [`Reshaped`]: the board is always 64 squares, so unlike
/// TinyCLIP's two towers or Whisper's growing decode there is nothing to re-record. One
/// forward pass per move, no search, no cache.
///
/// # The weights file stays open
///
/// [`maia::elo_embedding`] blends the two 128-vectors on the host — the blend weight is an
/// input, so it cannot be folded — so the [`Streamed`] is retained the way TinyCLIP's is
/// for its token table.
struct MaiaHandle {
    net: Net,
    weights: Streamed,
}

/// Bring up Maia3 from its one bundled `.maml`. Returns 0 on failure.
///
/// The descriptor is an `AssetFileDescriptor`'s, so it carries an offset and a length: the
/// file is a *range of the APK* rather than a file of its own, which is also why the asset
/// has to be stored uncompressed.
///
/// # Safety
///
/// Called only by the JVM, with a descriptor nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createMaia<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
) -> jlong {
    if fd < 0 {
        log(&format!("maia is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes
    // it on drop — including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_maia(file, offset, length) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("maia is unavailable: {e}"));
            0
        }
    }
}

fn build_maia(file: File, offset: jlong, length: jlong) -> Result<MaiaHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::MAIA)?;
    if weights.len() != maia::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), maia::TENSORS));
    }
    let plan = maia::build(&weights.offsets())?;
    let net = Net::new(context::shared()?, plan, &weights, RESCALE_ONLY)?;
    Ok(MaiaHandle { net, weights })
}

/// The 4352 move logits for a board, or null on failure.
///
/// `planes` is the 12 board planes as `12 * 64` floats, plane-major, square `rank * 8 + file`
/// — **already mirrored and colour-swapped if black is to move**, because the model only ever
/// sees the position from the mover's side and the move vocabulary has no black promotions
/// at all. `:games:chess` does that in `MaiaEngine`, next to the code that un-mirrors the
/// move it picks.
///
/// The logits are raw. Legal masking, temperature and sampling all need the caller's move
/// list, so they stay in Kotlin.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createMaia` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_maiaLogits<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    planes: JFloatArray<'l>,
    self_elo: jint,
    oppo_elo: jint,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: the caller guarantees the handle came from `createMaia` and is still live.
    // `&mut` because inference writes the net's staging buffer; Kotlin serialises calls.
    let handle = unsafe { &mut *(handle as *mut MaiaHandle) };
    let logits = match read_float_array(&mut env, &planes) {
        Ok(values) => run_maia(handle, &values, self_elo, oppo_elo),
        Err(e) => Err(e),
    };
    match logits.and_then(|values| new_float_array(&mut env, &values)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("maia failed: {e}"));
            null
        }
    }
}

fn run_maia(
    handle: &mut MaiaHandle,
    planes: &[f32],
    self_elo: jint,
    oppo_elo: jint,
) -> Result<Vec<f32>, String> {
    let self_vector = maia::elo_embedding(handle.weights.reader(), self_elo as f32)?;
    let oppo_vector = maia::elo_embedding(handle.weights.reader(), oppo_elo as f32)?;
    let tokens = maia::tokens(planes, &self_vector, &oppo_vector)?;
    let outputs = handle.net.infer_raw_many(&[&tokens])?;
    let [scores, promo] = outputs.as_slice() else {
        return Err(format!("{} outputs, expected two", outputs.len()));
    };
    crate::post::maia::logits(scores, promo)
}

/// Free Maia3's net and close its weights file.
///
/// Exactly once per non-zero handle from `createMaia`. When it is the last user of the shared
/// `VkDevice`, the device goes away with it.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createMaia`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyMaia<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createMaia` and has not been
    // destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut MaiaHandle) });
}

/// The Now Playing audio fingerprinter and its log-mel front end, as `:nowplaying` holds it.
///
/// # One net, one plan, no state
///
/// A plain [`Net`] like Maia's: the input is always [`nnfp::WINDOW_FRAMES`] frames, so there is
/// nothing to re-record. The export was a *streaming* graph with six circular buffers, but
/// `nets::nnfp` unrolls that into a fixed window, which is what lets it be one recorded command
/// buffer — see that module for the argument. Two calls with the same samples give the same
/// embedding; there is no warm-up and no carried state.
///
/// # The front end lives here, not in Kotlin
///
/// [`crate::microfrontend::Frontend`] holds precomputed window, mel and twiddle tables, so
/// keeping one alive per handle means an embedding allocates nothing. It is reset before each
/// window because a window is self-contained: [`nnfp_embed`] is handed all the samples the
/// answer depends on.
///
/// # The weights file does not stay open
///
/// Unlike [`MaiaHandle`], no [`Streamed`] is retained. Maia keeps its file because
/// `elo_embedding` reads rows on the host at inference time; nothing here does, so the whole
/// file is uploaded in `Net::new` and the descriptor is closed rather than held on the APK for
/// the life of the process.
struct NnfpHandle {
    net: Net,
    frontend: crate::microfrontend::Frontend,
}

/// Bring up the fingerprinter from its one bundled `.maml`. Returns 0 on failure.
///
/// The descriptor is an `AssetFileDescriptor`'s, so it carries an offset and a length: the file
/// is a *range of the APK* rather than a file of its own, which is also why the asset has to be
/// stored uncompressed — `noCompress += "maml"` in the app's Gradle configuration.
///
/// # Safety
///
/// Called only by the JVM, with a descriptor nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createNnfp<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
) -> jlong {
    if fd < 0 {
        log(&format!("nnfp is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes
    // it on drop — including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_nnfp(file, offset, length) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("nnfp is unavailable: {e}"));
            0
        }
    }
}

fn build_nnfp(file: File, offset: jlong, length: jlong) -> Result<NnfpHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::NNFP)?;
    if weights.len() != nnfp::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), nnfp::TENSORS));
    }
    let plan = nnfp::build(&weights.offsets())?;
    let net = Net::new(context::shared()?, plan, &weights, RESCALE_ONLY)?;
    let frontend = crate::microfrontend::Frontend::new(crate::microfrontend::NOW_PLAYING)?;
    // `weights` drops here, closing the descriptor: the whole file is already in the device
    // buffer and nothing reads it on the host.
    Ok(NnfpHandle { net, frontend })
}

/// The 64-value fingerprint for one window of audio, or null on failure.
///
/// `pcm` is exactly [`nnfp::WINDOW_SAMPLES`] mono 16-bit samples at 16 kHz — 415 ms, the
/// network's receptive field. i16 rather than f32 because that is what `AudioRecord` produces
/// and what the front end's fixed-point window expects; converting through float would lose
/// precision for nothing.
///
/// The embedding is **not** normalised. Whether the descriptor is compared by cosine, by L2 or
/// by a product quantiser could not be recovered from the model, so the caller's matcher
/// decides rather than this inventing a convention.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createNnfp` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_nnfpEmbed<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pcm: JShortArray<'l>,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: the caller guarantees the handle came from `createNnfp` and is still live.
    // `&mut` because inference writes the net's staging buffer; Kotlin serialises calls.
    let handle = unsafe { &mut *(handle as *mut NnfpHandle) };
    let embedding = match read_short_array(&mut env, &pcm) {
        Ok(samples) => run_nnfp(handle, &samples),
        Err(e) => Err(e),
    };
    match embedding.and_then(|values| new_float_array(&mut env, &values)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("nnfp failed: {e}"));
            null
        }
    }
}

fn run_nnfp(handle: &mut NnfpHandle, pcm: &[i16]) -> Result<Vec<f32>, String> {
    if pcm.len() != nnfp::WINDOW_SAMPLES {
        return Err(format!("{} samples, not {}", pcm.len(), nnfp::WINDOW_SAMPLES));
    }
    // A window is self-contained, so nothing from the last call may leak into this one.
    handle.frontend.reset();
    let mut frames = Vec::with_capacity(nnfp::WINDOW_FRAMES as usize * handle.frontend.channels());
    let produced = handle.frontend.process(pcm, &mut frames);
    if produced != nnfp::WINDOW_FRAMES as usize {
        return Err(format!("{produced} log-mel frames, not {}", nnfp::WINDOW_FRAMES));
    }
    let outputs = handle.net.infer_raw(&frames)?;
    let [embedding] = outputs.as_slice() else {
        return Err(format!("{} outputs, expected one", outputs.len()));
    };
    Ok(embedding.clone())
}

/// Free the fingerprinter's net.
///
/// Exactly once per non-zero handle from `createNnfp`. When it is the last user of the shared
/// `VkDevice`, the device goes away with it.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createNnfp`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyNnfp<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createNnfp` and has not been
    // destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut NnfpHandle) });
}

/// Now Playing's always-on music gate. Handed to Kotlin as an opaque `jlong`.
///
/// Unlike every other handle in this file there is no device, no plan and no asset: the
/// gate is 8,200 int8 parameters embedded in the binary and it runs on the CPU. See
/// [`crate::gate`] for why. Construction cannot fail for want of hardware, only if the
/// front end rejects its configuration, so a zero return here means a genuine bug.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createMusicGate<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jlong {
    match crate::gate::MusicGate::new() {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("the music gate is unavailable: {e}"));
            0
        }
    }
}

/// Feed PCM and get one music probability per completed 10 ms hop, oldest first.
///
/// Returns an empty array rather than null when a call completes no hop or the gate is
/// still warming up — both are ordinary, and null is reserved for a real failure. The gate
/// is stateful across calls; `resetMusicGate` starts a fresh session.
///
/// # Safety
///
/// `handle` must be a live value from `createMusicGate`, and Kotlin must serialise calls.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_musicGatePush<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pcm: JShortArray<'l>,
    count: jint,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: the caller guarantees the handle came from `createMusicGate` and is live.
    let handle = unsafe { &mut *(handle as *mut crate::gate::MusicGate) };
    let scores = read_short_array(&mut env, &pcm).and_then(|samples| {
        let count = usize::try_from(count).unwrap_or(usize::MAX);
        if count > samples.len() {
            return Err(format!("{count} samples of a {}-sample array", samples.len()));
        }
        let mut out = Vec::new();
        handle.push(&samples[..count], &mut out);
        Ok(out)
    });
    match scores.and_then(|values| new_float_array(&mut env, &values)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("the music gate failed: {e}"));
            null
        }
    }
}

/// Discard the front end's partial frame and the trunk's 230 ms of context.
///
/// # Safety
///
/// `handle` must be a live value from `createMusicGate`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_resetMusicGate<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees the handle came from `createMusicGate` and is live.
    unsafe { &mut *(handle as *mut crate::gate::MusicGate) }.reset();
}

/// Free the gate. Idempotent on the Kotlin side, which nulls its handle first.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createMusicGate`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyMusicGate<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createMusicGate` and has not
    // been destroyed.
    drop(unsafe { Box::from_raw(handle as *mut crate::gate::MusicGate) });
}

/// whisper-base, as `:speech` holds it. Handed to Kotlin as an opaque `jlong`.
///
/// # One net, two plans, and two kinds of cache
///
/// [`Reshaped`] keyed by [`whisper::Mode`]. The encoder runs once per utterance; a decode step is
/// re-recorded every token because its self-attention cache grows by one position, which is a
/// `device_wait_idle` and a re-record per token — the same known cost the translation handle
/// documents.
///
/// The **cross-attention** caches are the difference. Whisper's decoder cross-attends over 1500
/// encoder positions through each layer's own key and value projections, so recomputing them per
/// step would be 4.7 GMAC against the logits head's 26.5 MMAC. [`whisper::Mode::Encode`] computes
/// them once, they are held here, and each step passes them back in. See `nets::whisper`.
///
/// # The weights file stays open
///
/// [`whisper::embed_positions`] gathers a 1 KB embedding row per token out of the 26.6 MB tied table
/// on the host, so the [`Streamed`] is retained for the same reason SMaLL-100's is.
struct WhisperHandle {
    net: Reshaped<whisper::Mode>,
    weights: Streamed,
    /// Read from `generation_config.json` by Kotlin and checked once at construction.
    decoding: whisper_post::Decoding,
}

fn whisper_plan(offsets: &Offsets, mode: whisper::Mode) -> Result<Plan, String> {
    whisper::build(offsets, mode)
}

/// Cross-attention caches per decoder layer, K then V. See [`WhisperHandle`].
const WHISPER_CROSS: usize = whisper::DECODER_LAYERS * 2;

/// The two GPU passes, as [`whisper_post::Nets`] wants them, plus the cross-attention caches.
///
/// Built per transcription and dropped with it. The **self-attention** caches are not here at all
/// any more: the decode plan holds them in the arena and writes to them in place.
struct WhisperNets<'a> {
    net: &'a mut Reshaped<whisper::Mode>,
    weights: &'a Streamed,
    /// Each layer's cross-attention K then V, `[512 * 1500]` each, channel-major and constant for
    /// the whole transcript.
    cross: Vec<Vec<f32>>,
}

impl WhisperNets<'_> {
    fn new<'a>(handle: &'a mut WhisperHandle) -> WhisperNets<'a> {
        WhisperNets { net: &mut handle.net, weights: &handle.weights, cross: Vec::new() }
    }
}

impl whisper_post::Nets for WhisperNets<'_> {
    fn encode(&mut self, mel: &[f32]) -> Result<(), String> {
        let expected = whisper::MELS as usize * whisper::MEL_FRAMES as usize;
        if mel.len() != expected {
            return Err(format!("{} mel values, not {expected}", mel.len()));
        }
        let net = self.net.at(whisper::Mode::Encode)?;
        let mut out = net.infer_raw_many(&[mel])?;
        // Output 0 is the hidden states, which only the parity script reads; the twelve cross
        // caches follow, in layer order.
        if out.len() != 1 + WHISPER_CROSS {
            return Err(format!("the encoder returned {} tensors, not {}", out.len(), 1 + WHISPER_CROSS));
        }
        self.cross = out.split_off(1);
        Ok(())
    }

    fn decode_step(&mut self, token: u32, step: usize) -> Result<Vec<f32>, String> {
        if self.cross.len() != WHISPER_CROSS {
            return Err("a decode step before the encoder ran".into());
        }
        let cache_len = u32::try_from(step).map_err(|_| "a step past u32")?;
        if cache_len >= whisper::MAX_POSITIONS {
            return Err(format!(
                "step {step} is past the {} the KV cache holds",
                whisper::MAX_POSITIONS
            ));
        }
        // The embedding row and the learned position, both on the host and summed in f32.
        let embedded = whisper::embed_positions(self.weights.reader(), &[token], cache_len)?;

        // One key for every step, so this re-records once - leaving `Encode` - and never again.
        let net = self.net.at(whisper::Mode::DecodeStep)?;
        net.set_params(StepParams { prefix: cache_len, window_start: 0 })?;
        // Declaration order: the token, then the twelve cross caches.
        let mut inputs: Vec<&[f32]> = Vec::with_capacity(1 + WHISPER_CROSS);
        inputs.push(&embedded);
        for held in &self.cross {
            inputs.push(held);
        }
        let out = net.infer_raw_many(&inputs)?;

        // The logits, and nothing else: the self-attention rows went into the device-side cache.
        if out.len() != 1 {
            return Err(format!("a decode step returned {} tensors, not one", out.len()));
        }
        let logits = out.into_iter().next().ok_or("no logits")?;
        if logits.len() != whisper::VOCAB as usize {
            return Err(format!("{} logits, not {}", logits.len(), whisper::VOCAB));
        }
        Ok(logits)
    }
}

/// Bring up whisper-base from its one `.maml` and the ids read from `generation_config.json`.
///
/// Returns 0 on failure. The descriptor is an `AssetFileDescriptor`'s, so it carries an offset and a
/// length: the file is a range of the APK rather than a file of its own.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env`, arrays it owns, and a descriptor nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createWhisper<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
    special: JIntArray<'l>,
    languages: JIntArray<'l>,
    suppress: JIntArray<'l>,
    suppress_at_begin: JIntArray<'l>,
) -> jlong {
    // The descriptor first, and adopted into an owning `File` before anything else may fail: the
    // caller detached it, so a path that returns without wrapping it leaks it for the life of the
    // process.
    if fd < 0 {
        log(&format!("whisper is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes it on
    // drop — including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    let built = build_whisper(
        &mut env,
        file,
        offset,
        length,
        &special,
        &languages,
        &suppress,
        &suppress_at_begin,
    );
    match built {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("whisper is unavailable: {e}"));
            0
        }
    }
}

/// The five scalars `createWhisper` takes in its `special` array, in order.
///
/// An array rather than five `jint` parameters because the JNI signature is already eight arguments
/// wide, and because these five arrive together out of one JSON file.
const WHISPER_SPECIAL: usize = 5;

#[allow(clippy::too_many_arguments)]
fn build_whisper<'l>(
    env: &mut JNIEnv<'l>,
    file: File,
    offset: jlong,
    length: jlong,
    special: &JIntArray<'l>,
    languages: &JIntArray<'l>,
    suppress: &JIntArray<'l>,
    suppress_at_begin: &JIntArray<'l>,
) -> Result<WhisperHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::WHISPER)?;
    if weights.len() != whisper::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), whisper::TENSORS));
    }

    let mut ids = |array: &JIntArray<'l>| -> Result<Vec<u32>, String> {
        read_int_array(env, array)?
            .into_iter()
            .map(|id| u32::try_from(id).map_err(|_| format!("{id} is not a token")))
            .collect()
    };
    let special = ids(special)?;
    let [start_of_transcript, end_of_text, transcribe, no_timestamps, max_length] =
        <[u32; WHISPER_SPECIAL]>::try_from(special.as_slice())
            .map_err(|_| format!("{} special ids, not {WHISPER_SPECIAL}", special.len()))?;
    let decoding = whisper_post::Decoding {
        start_of_transcript,
        end_of_text,
        transcribe,
        no_timestamps,
        max_length: max_length as usize,
        languages: ids(languages)?,
        suppress: ids(suppress)?,
        suppress_at_begin: ids(suppress_at_begin)?,
    };
    // Checked here rather than per transcription, so a broken `generation_config.json` fails at
    // construction — when the UI can still report the recogniser as unavailable.
    decoding.check(whisper::VOCAB)?;

    // Recorded on the encoder, which is what every transcription runs first.
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        whisper::Mode::Encode,
        whisper_plan,
    )?;
    Ok(WhisperHandle { net, weights, decoding })
}

/// Transcribe one log-mel window into token ids, or null on failure.
///
/// `mel` is `[80 * 3000]` row-major, from `WhisperFeatures.logMel`. `language` is a `<|xx|>` token
/// the caller resolved from a code, or **negative** to detect.
///
/// The ids come back raw, including any special or timestamp token the model emitted:
/// `WhisperTokenizer` is what skips those, and it is unchanged by this port.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createWhisper` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_transcribeWhisper<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    mel: JFloatArray<'l>,
    language: jint,
) -> jintArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: the caller guarantees the handle came from `createWhisper` and is still live. It is
    // `&mut` because every step re-records the net, and Kotlin serialises calls on one handle.
    let handle = unsafe { &mut *(handle as *mut WhisperHandle) };
    let wanted = if language < 0 { None } else { u32::try_from(language).ok() };
    let ids = match read_float_array(&mut env, &mel) {
        Ok(values) => run_whisper(handle, &values, wanted),
        Err(e) => Err(e),
    };
    match ids.and_then(|ids| new_int_array(&mut env, &ids)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("whisper failed: {e}"));
            null
        }
    }
}

fn run_whisper(
    handle: &mut WhisperHandle,
    mel: &[f32],
    language: Option<u32>,
) -> Result<Vec<i32>, String> {
    // Cloned so the config can be read while the nets borrow the handle mutably. A hundred-odd ids
    // against a transcription that reads a 70 MiB file.
    let decoding = handle.decoding.clone();
    let mut nets = WhisperNets::new(handle);
    let ids = whisper_post::transcribe(&mut nets, &decoding, mel, language)?;
    ids.into_iter()
        .map(|id| i32::try_from(id).map_err(|_| format!("token {id} does not fit an int")))
        .collect()
}

/// Free whisper's net, its open weights file and both caches.
///
/// Exactly once per non-zero handle from `createWhisper`. When it is the last user of the shared
/// `VkDevice`, the device goes away with it.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createWhisper`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyWhisper<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createWhisper` and has not been
    // destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut WhisperHandle) });
}

fn new_int_array(env: &mut JNIEnv, values: &[i32]) -> Result<jintArray, String> {
    let array: JIntArray = env
        .new_int_array(values.len() as jint)
        .map_err(|e| format!("new_int_array: {e}"))?;
    env.set_int_array_region(&array, 0, values)
        .map_err(|e| format!("set_int_array_region: {e}"))?;
    Ok(array.into_raw())
}

fn read_float_array(env: &mut JNIEnv, array: &JFloatArray) -> Result<Vec<f32>, String> {
    let len = env.get_array_length(array).map_err(|e| format!("array length: {e}"))?;
    let mut out = vec![0.0f32; len.max(0) as usize];
    env.get_float_array_region(array, 0, &mut out)
        .map_err(|e| format!("get_float_array_region: {e}"))?;
    Ok(out)
}

fn read_int_array(env: &mut JNIEnv, array: &JIntArray) -> Result<Vec<i32>, String> {
    let len = env.get_array_length(array).map_err(|e| format!("array length: {e}"))?;
    let mut out = vec![0i32; len.max(0) as usize];
    env.get_int_array_region(array, 0, &mut out)
        .map_err(|e| format!("get_int_array_region: {e}"))?;
    Ok(out)
}

fn read_short_array(env: &mut JNIEnv, array: &JShortArray) -> Result<Vec<i16>, String> {
    let len = env.get_array_length(array).map_err(|e| format!("array length: {e}"))?;
    let mut out = vec![0i16; len.max(0) as usize];
    env.get_short_array_region(array, 0, &mut out)
        .map_err(|e| format!("get_short_array_region: {e}"))?;
    Ok(out)
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


/// Gemma 4 E2B, as `:openassistant` holds it. Handed to Kotlin as an opaque `jlong`.
///
/// # Why the loop is Kotlin's and not this module's
///
/// litertlm owned the whole turn: template, tool calls, sampling, streaming. Replacing it with a
/// single `generate(prompt) -> String` would reproduce that opacity and put the chat template,
/// the tool protocol and the stop conditions in Rust, where none of them belong. So the boundary
/// is one token: Kotlin pushes ids, asks for the next, and decides what to do with it. That is
/// also what lets the UI stream, since it already streams by writing each chunk to Room.
///
/// # Two files
///
/// The text model and the embedding are separate `.maml`s with separate graph ids. Nothing on the
/// device reads the embedding - a step needs one row of a 262144-row table - so it stays a
/// [`Streamed`] and is gathered on the host, exactly as NLLB's tied embedding is.
struct Gemma4Handle {
    net: Reshaped<gemma4::Pass>,
    /// The text model, retained for the rotary tables which are also host-read.
    weights: Streamed,
    /// The two embedding tables.
    embed: Streamed,
    /// `scripts/ml/gemma4_tokenizer.py`'s table.
    tokenizer: Vec<u8>,
    /// Rotary angles for the sliding layers, `[MAX_CONTEXT, HEAD_DIM]`, read once.
    ///
    /// A few megabytes held for the life of the handle rather than re-read per token. At 40 ms a
    /// token a re-read would be most of the step.
    local: Vec<f32>,
    /// The same for the full-attention layers, `[MAX_CONTEXT, GLOBAL_HEAD_DIM]`.
    global: Vec<f32>,
    /// The largest tier this device's memory affords. See `gemma4::tier_for`.
    ceiling: u32,

    /// Positions the KV caches are allocated for: one of [`gemma4::CONTEXT_TIERS`].
    ///
    /// Chosen per device rather than compiled in, because 18 KB a position means the tier *is*
    /// the memory: 19 MB at 1,024 and 302 MB at 16,384. A phone with 4 GB should hold a short
    /// conversation rather than fail to start.
    context: u32,

    /// Positions already in the KV cache, which is the next token's position.
    ///
    /// Resetting a conversation sets this to zero and nothing else: attention reads only
    /// `[window_start, prefix]`, so rows past the new prefix are never looked at again and
    /// overwriting them lazily is free.
    position: u32,
}

fn gemma4_plan(offsets: &Offsets, pass: gemma4::Pass) -> Result<Plan, String> {
    gemma4::build(offsets, pass)
}

impl Gemma4Handle {
    /// Feed one token and return its logits, or `None` while only filling the cache.
    fn step(&mut self, token: u32, want_logits: bool) -> Result<Option<Vec<f32>>, String> {
        let (hidden, per_layer) = gemma4::gather(&self.embed.reader(), token)?;
        self.step_hidden(&hidden, &per_layer, want_logits)
    }

    /// Feed one **soft token**: an encoder's output standing in for a token's embedding.
    ///
    /// The per-layer inputs come from the pad token, which is what the reference does - it
    /// rewrites the placeholder id to `pad_token_id` before gathering, then overwrites only the
    /// hidden state with the encoder's row. So the two halves of a soft token's input come from
    /// different places, and using the placeholder's own per-layer row instead would be a quiet
    /// and plausible-looking error.
    fn step_soft(&mut self, hidden: &[f32], want_logits: bool) -> Result<Option<Vec<f32>>, String> {
        if hidden.len() != gemma4::D_MODEL as usize {
            return Err(format!("a soft token of {} values, not {}", hidden.len(), gemma4::D_MODEL));
        }
        let (_, per_layer) = gemma4::gather(&self.embed.reader(), gemma4::embed::PLACEHOLDERS[0])?;
        self.step_hidden(hidden, &per_layer, want_logits)
    }

    /// The step itself, once both halves of the input are in hand.
    fn step_hidden(
        &mut self,
        hidden: &[f32],
        per_layer: &[f32],
        want_logits: bool,
    ) -> Result<Option<Vec<f32>>, String> {
        if self.position >= self.context {
            return Err(format!("the cache is full at {} positions", self.context));
        }
        let row = |table: &[f32], width: u32| -> Vec<f32> {
            let from = (self.position * width) as usize;
            table[from..from + width as usize].to_vec()
        };
        let local = row(&self.local, gemma4::HEAD_DIM);
        let global = row(&self.global, gemma4::GLOBAL_HEAD_DIM);
        // Prefill positions do not need logits, and until now they computed them anyway: the
        // plan was `DecodeStep` unconditionally and `want_logits` only decided whether the host
        // kept the result. The head is four int4 projections over the 262,144-entry vocabulary,
        // 227 MB of the 1.30 GB of weights - 17.5 % of the bytes a pass touches, discarded on
        // every one of the ~1,870 positions a prompt is long.
        //
        // `Prefill { tokens: 1 }` runs all thirty-five layers, fills all thirty caches and stops
        // before the final norm and the head. Its one output is the hidden state, which nothing
        // here wants.
        //
        // SAFETY OF SWITCHING PLANS MID-CONVERSATION, which is not obvious and is not guaranteed
        // by the type system: `Reshaped::at` re-records whenever the mode changes, and the caches
        // live in the arena, so the two plans must agree on where they are. They do - measured,
        // not assumed: every cache operand is identical across the two, at all twenty-nine
        // offsets, because at `tokens: 1` the inputs are the same size and `finish` assigns arena
        // offsets by walking the pinned list in declaration order. That equality is pinned by a
        // test in `nets::gemma4`; if it ever fails, prefill writes the caches where decode does
        // not read them and the model answers fluently from whatever was in the arena.
        //
        // The arena never reallocates either, which would drop the caches outright: decode's is
        // the larger of the two, the net is created at decode, and `rebuild` only grows.
        let mode = if want_logits {
            gemma4::Mode::DecodeStep
        } else {
            gemma4::Mode::Prefill { tokens: 1 }
        }
        .at(self.context);
        let at = self.net.at(mode)?;
        at.set_params(StepParams {
            prefix: self.position,
            window_start: self.position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let ran = std::time::Instant::now();
        let out = at.infer_raw_many(&[hidden, per_layer, &local, &global])?;
        // Every sixteenth, so the log is a sample rather than a flood.
        if self.position % 16 == 0 {
            log(&format!(
                "gemma4 decode at {}: {:.0} ms gpu, head {}",
                self.position,
                ran.elapsed().as_secs_f64() * 1000.0,
                if want_logits { "on" } else { "off" },
            ));
        }
        self.position += 1;
        if !want_logits {
            return Ok(None);
        }
        if out.len() != gemma4::HEAD_SPLITS {
            return Err(format!("a step returned {} tensors, not {}", out.len(), gemma4::HEAD_SPLITS));
        }
        let mut logits = Vec::with_capacity(gemma4::VOCAB as usize);
        for split in out.iter().take(gemma4::HEAD_SPLITS) {
            logits.extend_from_slice(split);
        }
        Ok(Some(logits))
    }

    /// Reallocate the caches so at least `needed` positions fit. Returns the new capacity.
    ///
    /// # This throws the cache away
    ///
    /// The caches live in the arena, and a bigger arena is a different allocation - nothing
    /// copies the old contents across. So a grow resets the position to zero and the caller has
    /// to feed the whole prompt again.
    ///
    /// That is why the tiers double rather than creep: over a long conversation the re-prefill
    /// happens four times, not once per message. The caller is told by the return value, which
    /// it must treat as "the cache is now empty".
    fn grow(&mut self, needed: u32) -> Result<u32, String> {
        if needed <= self.context {
            return Ok(self.context);
        }
        let Some(tier) = gemma4::CONTEXT_TIERS
            .iter()
            .copied()
            .find(|tier| *tier >= needed && *tier <= self.ceiling)
        else {
            return Err(format!(
                "{needed} positions, and this device's cache stops at {}",
                self.ceiling
            ));
        };
        let rebuilt = Reshaped::streamed(
            context::shared()?,
            self.weights.offsets(),
            &self.weights,
            gemma4::Mode::DecodeStep.at(tier),
            gemma4_plan,
        )?;
        log(&format!(
            "gemma4 cache grew {} -> {tier} positions ({} MB), so the prompt is re-fed",
            self.context,
            (u64::from(tier) * u64::from(gemma4::BYTES_PER_POSITION)) / 1_000_000,
        ));
        self.net = rebuilt;
        self.context = tier;
        self.position = 0;
        Ok(tier)
    }

    /// Feed many tokens in **one** submit, filling the caches and returning nothing.
    ///
    /// The prompt path, and the reason a conversation starts in under a second rather than in
    /// tens of them. At one position a pass is bandwidth-bound: it reads 1.30 GB of weights to
    /// produce a single 1536-wide vector, which measured 28.81 ms. At T positions it reads the
    /// same 1.30 GB once and every projection becomes a GEMM over T columns, so the weights are
    /// amortised T ways.
    ///
    /// Chunked rather than one submit for the whole prompt, because attention is quadratic in T
    /// and the score maps are a real allocation - eight heads of T x T at [`CHUNK`] is 16 MB,
    /// and the arena has to hold it alongside everything else.
    fn prefill(&mut self, tokens: &[u32]) -> Result<(), String> {
        if tokens.is_empty() {
            return Ok(());
        }
        if self.position as usize + tokens.len() > self.context as usize {
            return Err(format!(
                "a prompt of {} positions past this device's {} cache",
                self.position as usize + tokens.len(),
                self.context
            ));
        }
        for chunk in tokens.chunks(CHUNK as usize) {
            let width = chunk.len() as u32;
            let gathering = std::time::Instant::now();
            // Gathered per position and laid out `[C, 1, T]` - channel-major, which is what
            // every op in the pass expects and what `cache_write` transposes on the way in.
            let mut hidden = vec![0f32; (gemma4::D_MODEL * width) as usize];
            let mut per_layer =
                vec![0f32; (gemma4::PER_LAYER * gemma4::LAYERS as u32 * width) as usize];
            let mut local = vec![0f32; (gemma4::HEAD_DIM * width) as usize];
            let mut global = vec![0f32; (gemma4::GLOBAL_HEAD_DIM * width) as usize];
            for (offset, &token) in chunk.iter().enumerate() {
                let (h, p) = gemma4::gather(&self.embed.reader(), token)?;
                let column = offset as u32;
                let position = self.position + column;
                place(&mut hidden, &h, column, width);
                place(&mut per_layer, &p, column, width);
                // Written out rather than closed over: a closure returning a borrow of its own
                // argument needs a named lifetime, and two call sites do not justify one.
                let from = (position * gemma4::HEAD_DIM) as usize;
                let row = &self.local[from..from + gemma4::HEAD_DIM as usize];
                place(&mut local, row, column, width);
                let from = (position * gemma4::GLOBAL_HEAD_DIM) as usize;
                let row = &self.global[from..from + gemma4::GLOBAL_HEAD_DIM as usize];
                place(&mut global, row, column, width);
            }
            let gathered = gathering.elapsed();
            let submitted = std::time::Instant::now();
            let at = self.net.at(gemma4::Mode::Prefill { tokens: width }.at(self.context))?;
            let recorded = submitted.elapsed();
            at.set_params(StepParams {
                prefix: self.position,
                window_start: self.position.saturating_sub(gemma4::WINDOW - 1),
            })?;
            let ran = std::time::Instant::now();
            at.infer_raw_many(&[&hidden, &per_layer, &local, &global])?;
            // Timed on the device because nothing about a desktop GPU predicts a phone's. The
            // record time is called out separately: `Reshaped` re-records whenever the mode or
            // the width changes, and a prompt whose last chunk is short pays it twice.
            log(&format!(
                "gemma4 prefill {width} positions: {:.0} ms gather, {:.0} ms record, {:.0} ms gpu",
                gathered.as_secs_f64() * 1000.0,
                recorded.as_secs_f64() * 1000.0,
                ran.elapsed().as_secs_f64() * 1000.0,
            ));
            self.position += width;
        }
        Ok(())
    }
}

/// Positions one prefill submit covers.
///
/// # This is bounded by the fence, not by the arena
///
/// The obvious constraint is memory - attention is quadratic in this - and it is not the binding
/// one. `FENCE_TIMEOUT_NS` gives a submit five seconds, and a phone GPU is some thirty times
/// slower than the desktop one these numbers were taken on. Measured there, a submit costs about
/// 76 ms at 8 positions and 167 ms at 128: mostly a fixed cost, because the pass streams 1.30 GB
/// of weights whatever T is. Thirty times 167 ms is over five seconds, which is exactly the
/// `wait_for_fences TIMEOUT` a real device reported.
///
/// A timeout is not a soft failure. The submission is still in flight, nothing can cancel it, so
/// the net is **poisoned** and every later call on it fails too - which is why the retry in
/// `InferenceService` failed as well and the user saw two dead turns rather than one.
///
/// # Re-raised after the barrier fix
///
/// 16 was chosen when a 128-wide submit blew the fence, and that was *before* barriers were
/// narrowed from the whole arena to each op's own output. Measured on a Tensor G4 afterwards, a
/// submit is about **1,058 ms fixed plus 4.7 ms a position** - almost all fixed, because the
/// pass streams the same 1.30 GB of weights whatever T is.
///
/// So the chunk is nearly free to raise and enormously expensive to keep small: a 1,910-position
/// prompt is 136 seconds at 16 and 20 at 192. At 192 a submit is ~1.96 s against the 5 s fence,
/// which leaves room for a device rather slower than the one measured.

/// Measured, not extrapolated: 16 works at 1,133 ms and 192 times out past five seconds. The
/// slope between T=6 and T=16 is 4.7 ms a position, and projecting it to 192 predicted 1.96 s -
/// wrong, because attention is **quadratic** in T and both of those samples were inside a single
/// 16-wide tile. 64 is four times the throughput of 16 with a wide margin on the fence.
const CHUNK: u32 = 64;

/// Write one position's `values` into column `column` of a `[C, 1, width]` block.
///
/// The layout is channel-major - channel `c` at `c * width + column` - which is the transpose of
/// how the values arrive. Getting this backwards is not a shape error and produces a prompt whose
/// tokens are scrambled across channels.
fn place(into: &mut [f32], values: &[f32], column: u32, width: u32) {
    for (channel, &value) in values.iter().enumerate() {
        let at = channel * width as usize + column as usize;
        if let Some(slot) = into.get_mut(at) {
            *slot = value;
        }
    }
}

/// The most likely token, and nothing else.
///
/// Greedy. litertlm sampled with `top_k` 64 and `top_p` 0.95 and this does not, which is a real
/// behavioural change: replies become deterministic and slightly flatter. Sampling belongs on the
/// Kotlin side of the boundary where a seed can be held and a temperature exposed, and adding it
/// here would put a policy decision in the wrong module.
fn argmax(logits: &[f32]) -> u32 {
    let mut best = (f32::NEG_INFINITY, 0u32);
    for (index, &value) in logits.iter().enumerate() {
        if value > best.0 {
            best = (value, index as u32);
        }
    }
    best.1
}

/// Bring up Gemma 4 from its two `.maml`s and its tokenizer table. Returns 0 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env`, arrays it owns, and descriptors nothing else holds.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createGemma4<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    text_fd: jint,
    text_offset: jlong,
    text_length: jlong,
    embed_fd: jint,
    embed_offset: jlong,
    embed_length: jlong,
    tokenizer: JByteArray<'l>,
    // Bytes of KV cache this device will spend. See `gemma4::tier_for`.
    budget: jlong,
) -> jlong {
    // Both descriptors are adopted before anything may fail. The caller detached them, so a path
    // that returns without wrapping one leaks it for the life of the process - and there are two
    // here, so the usual single-`fd` shape is not enough.
    if text_fd < 0 || embed_fd < 0 {
        log(&format!("gemma4 is unavailable: descriptors {text_fd} and {embed_fd}"));
        return 0;
    }
    // SAFETY: the caller detached both, so nothing else owns them, and `File` closes them on drop
    // including on every failure path below.
    let text = unsafe { File::from_raw_fd(text_fd) };
    let embed = unsafe { File::from_raw_fd(embed_fd) };
    let spans = (
        u64::try_from(text_offset),
        u64::try_from(text_length),
        u64::try_from(embed_offset),
        u64::try_from(embed_length),
    );
    let built = match spans {
        (Ok(ta), Ok(tl), Ok(ea), Ok(el)) => {
            build_gemma4(&mut env, text, ta, tl, embed, ea, el, &tokenizer, budget.max(0) as u64)
        }
        _ => Err("a graph span that is not a positive offset and length".to_string()),
    };
    match built {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("gemma4 is unavailable: {e}"));
            0
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn build_gemma4<'l>(
    env: &mut JNIEnv<'l>,
    text: File,
    text_at: u64,
    text_len: u64,
    embed: File,
    embed_at: u64,
    embed_len: u64,
    tokenizer: &JByteArray<'l>,
    budget: u64,
) -> Result<Gemma4Handle, String> {
    let weights = Streamed::open(text, text_at, text_len, graph::GEMMA4_TEXT)?;
    let embed = Streamed::open(embed, embed_at, embed_len, graph::GEMMA4_EMBED)?;
    let tokenizer = env
        .convert_byte_array(tokenizer)
        .map_err(|e| format!("cannot read the tokenizer table: {e}"))?;
    // Parsed once here to refuse a bad table at construction rather than at the first turn, when
    // the UI has already committed to having a working assistant.
    let parsed = Table::parse_with(&tokenizer, GEMMA)?;
    if parsed.len() != gemma4::VOCAB as usize {
        return Err(format!("a tokenizer of {} pieces, not {}", parsed.len(), gemma4::VOCAB));
    }
    if !parsed.has_byte_fallback() {
        return Err("a tokenizer without byte fallback, which cannot spell every reply".into());
    }
    let reader = weights.reader();
    let local = reader.fp16(gemma4::ROTARY_LOCAL, &[gemma4::MAX_CONTEXT, gemma4::HEAD_DIM])?;
    let global =
        reader.fp16(gemma4::ROTARY_GLOBAL, &[gemma4::MAX_CONTEXT, gemma4::GLOBAL_HEAD_DIM])?;
    drop(reader);
    // The largest cache this device's memory budget affords. Kotlin measures the device;
    // native turns that into positions, because only native knows a position costs 18 KB.
    let cache = gemma4::tier_for(budget);
    log(&format!(
        "gemma4 cache {cache} positions, {} MB, from a {} MB budget",
        (u64::from(cache) * u64::from(gemma4::BYTES_PER_POSITION)) / 1_000_000,
        budget / 1_000_000,
    ));
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        gemma4::Mode::DecodeStep.at(gemma4::CONTEXT_TIERS[0]),
        gemma4_plan,
    )?;
    Ok(Gemma4Handle {
        net,
        weights,
        embed,
        tokenizer,
        local,
        global,
        ceiling: cache,
        // Start at the smallest tier whatever the device affords. A conversation that stays
        // short never pays for a cache it does not use, and 19 MB against 301 MB is the
        // difference between the assistant being a background cost and being the reason
        // something else was killed.
        context: gemma4::CONTEXT_TIERS[0],
        position: 0,
    })
}

/// Encode text to token ids, matching HuggingFace's `tokenizers` for this vocabulary.
///
/// `specials` are matched literally and never merged into - the chat markers a template inserts.
/// Passing them from Kotlin rather than hardcoding them here is deliberate: which markers a
/// prompt may contain is a policy question, and a model that let a user's text spell a turn
/// boundary would let them forge one.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_encodeGemma4<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    text: JString<'l>,
    specials: JObjectArray<'l>,
) -> jintArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &*(handle as *const Gemma4Handle) };
    let encoded = match env.get_string(&text) {
        Ok(text) => encode_gemma4(&mut env, handle, &String::from(text), &specials),
        Err(e) => Err(format!("cannot read the prompt: {e}")),
    };
    match encoded {
        Ok(ids) => match new_int_array(&mut env, &ids) {
            Ok(array) => array,
            Err(e) => {
                log(&format!("gemma4 cannot return its token ids: {e}"));
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            log(&format!("gemma4 cannot encode: {e}"));
            std::ptr::null_mut()
        }
    }
}

fn encode_gemma4<'l>(
    env: &mut JNIEnv<'l>,
    handle: &Gemma4Handle,
    text: &str,
    specials: &JObjectArray<'l>,
) -> Result<Vec<i32>, String> {
    let table = Table::parse_with(&handle.tokenizer, GEMMA)?;
    let count = env.get_array_length(specials).map_err(|e| format!("{e}"))?;
    let mut owned = Vec::with_capacity(count as usize);
    for index in 0..count {
        let item = env.get_object_array_element(specials, index).map_err(|e| format!("{e}"))?;
        let item: JString = item.into();
        let text = env.get_string(&item).map_err(|e| format!("{e}"))?;
        owned.push(String::from(text));
    }
    let borrowed: Vec<&str> = owned.iter().map(String::as_str).collect();
    // The JVM's arrays are signed, and the vocabulary fits in a positive `i32` twice over, so the
    // cast is total rather than merely usually right.
    Ok(table.encode_with_specials(text, &borrowed).into_iter().map(|id| id as i32).collect())
}

/// Text for a run of token ids, fusing byte pieces back into characters.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_decodeGemma4<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    tokens: JIntArray<'l>,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &*(handle as *const Gemma4Handle) };
    let decoded = read_int_array(&mut env, &tokens).and_then(|ids| {
        let table = Table::parse_with(&handle.tokenizer, GEMMA)?;
        // Negative ids cannot name a piece; dropping them keeps `decode`'s "one bad token loses a
        // word, not the reply" behaviour rather than failing the whole call.
        let ids: Vec<u32> = ids.into_iter().filter_map(|id| u32::try_from(id).ok()).collect();
        Ok(table.decode(&ids))
    });
    match decoded {
        Ok(text) => match env.new_string(&text) {
            Ok(string) => string.into_raw(),
            Err(e) => {
                log(&format!("gemma4 cannot return its text: {e}"));
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            log(&format!("gemma4 cannot decode: {e}"));
            std::ptr::null_mut()
        }
    }
}

/// Feed tokens into the cache without generating. Returns the new position, or -1.
///
/// The prompt path: every token but the last only fills the KV cache, so its logits are computed
/// and thrown away. Doing that here rather than one JNI call at a time keeps a 500-token prompt
/// to one crossing instead of 500.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_pushGemma4<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    tokens: JIntArray<'l>,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live. It is
    // `&mut` because a step advances the cache, and Kotlin serialises calls on one handle.
    let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
    let pushed = read_int_array(&mut env, &tokens).and_then(|ids| {
        let mut owned = Vec::with_capacity(ids.len());
        for token in ids {
            owned.push(
                u32::try_from(token)
                    .map_err(|_| format!("token {token} is not in the vocabulary"))?,
            );
        }
        handle.prefill(&owned)?;
        Ok(handle.position)
    });
    match pushed {
        Ok(position) => jint::try_from(position).unwrap_or(-1),
        Err(e) => {
            log(&format!("gemma4 cannot take the prompt: {e}"));
            -1
        }
    }
}

/// Feed one token and return the next, greedily. -1 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_stepGemma4<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    token: jint,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
    let token = match u32::try_from(token) {
        Ok(token) if token < gemma4::VOCAB => token,
        _ => {
            log(&format!("gemma4 was given token {token}, which is not in the vocabulary"));
            return -1;
        }
    };
    match handle.step(token, true) {
        Ok(Some(logits)) => jint::try_from(argmax(&logits)).unwrap_or(-1),
        Ok(None) => -1,
        Err(e) => {
            log(&format!("gemma4 step failed: {e}"));
            -1
        }
    }
}

/// Positions currently in the KV cache.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_positionGemma4<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &*(handle as *const Gemma4Handle) };
    jint::try_from(handle.position).unwrap_or(-1)
}

/// Time one decode pass and one head-free pass, and log both. Returns -1 always.
///
/// # What this settles
///
/// A decode step costs 676 ms on a Tensor G4 against 41 ms on a desktop, and the question is
/// whether that is the **weights** or the **dispatches**. The two answers need completely
/// different work - better kernels versus fusing ops - so guessing is expensive.
///
/// The head is the discriminator. It is four of the pass's 1,094 dispatches, and 402 MB of its
/// 1.30 GB of weights. So running with it and without it:
///
/// * bandwidth-bound -> the head-free pass is about **31% faster**
/// * dispatch-bound  -> it is about **0.4% faster**, which is nothing
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_benchmarkGemma4<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
    let Ok((hidden, per_layer)) = gemma4::gather(&handle.embed.reader(), 2) else {
        return -1;
    };
    let local = handle.local[..gemma4::HEAD_DIM as usize].to_vec();
    let global = handle.global[..gemma4::GLOBAL_HEAD_DIM as usize].to_vec();
    for (label, mode) in [
        ("with head   ", gemma4::Mode::DecodeStep),
        ("without head", gemma4::Mode::Prefill { tokens: 1 }),
    ] {
        let mut best = f64::MAX;
        for _ in 0..3 {
            handle.position = 0;
            let Ok(at) = handle.net.at(mode.at(handle.context)) else { continue };
            let _ = at.set_params(StepParams { prefix: 0, window_start: 0 });
            let started = std::time::Instant::now();
            if at.infer_raw_many(&[&hidden, &per_layer, &local, &global]).is_err() {
                continue;
            }
            best = best.min(started.elapsed().as_secs_f64() * 1000.0);
        }
        log(&format!("gemma4 benchmark {label}: {best:.0} ms"));
    }
    handle.position = 0;
    -1
}

/// Load a baked prefix cache, so the fixed prompt is never prefilled on device. Returns the
/// positions loaded, or -1.
///
/// # What this saves
///
/// The system block is ~334 positions and the tool declarations take it past a thousand. On a
/// Tensor G4 that is 14 seconds of prefill, paid on every cold start, to compute numbers that
/// are the same on every device - the tokens do not change, so neither do the keys and values.
/// `examples/bake_gemma4_prefix.rs` computes them once and this loads the result.
///
/// The caller must have the matching tokens and set its own reuse record to them, or the next
/// turn will re-feed the prefix and undo the point. It must also be the *same* prefix: native
/// checks only the size, because it has no way to know what tokens produced these numbers.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_loadPrefixGemma4<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    positions: jint,
    cache: JByteArray<'l>,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
    let Ok(positions) = u32::try_from(positions) else {
        return -1;
    };
    let loaded = env
        .convert_byte_array(&cache)
        .map_err(|e| format!("cannot read the cache: {e}"))
        .and_then(|bytes| {
            if positions > handle.context {
                // A device on a small tier cannot hold the whole prefix. Refusing leaves it to
                // prefill normally, which is slow and right, rather than loading a truncated
                // cache and attending over keys that stop mid-prompt.
                return Err(format!(
                    "a {positions}-position prefix into a {} cache",
                    handle.context
                ));
            }
            let at = handle.net.at(gemma4::Mode::DecodeStep.at(handle.context))?;
            at.import_pinned(gemma4::CACHE_TENSORS, positions, &bytes)?;
            handle.position = positions;
            Ok(positions)
        });
    match loaded {
        Ok(positions) => {
            log(&format!("gemma4 loaded a {positions}-position prefix cache"));
            jint::try_from(positions).unwrap_or(-1)
        }
        Err(e) => {
            log(&format!("gemma4 cannot load the prefix cache: {e}"));
            -1
        }
    }
}

/// Positions the cache currently holds, or -1.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_capacityGemma4<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &*(handle as *const Gemma4Handle) };
    jint::try_from(handle.context).unwrap_or(-1)
}

/// Grow the cache so `needed` positions fit. Returns the new capacity, or -1.
///
/// **The cache is emptied**: a bigger arena is a different allocation and nothing is copied
/// across, so the caller must feed its whole prompt again afterwards. Returning the capacity
/// rather than a boolean is deliberate - the caller needs the number to decide whether the
/// prompt fits at all.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_growGemma4<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    needed: jint,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
    let Ok(needed) = u32::try_from(needed) else {
        return -1;
    };
    match handle.grow(needed) {
        Ok(capacity) => jint::try_from(capacity).unwrap_or(-1),
        Err(e) => {
            log(&format!("gemma4 cannot grow: {e}"));
            -1
        }
    }
}

/// Rewind the cache to `position`, keeping everything before it. Returns the new position, or -1.
///
/// # The point of this
///
/// A turn's prompt is almost entirely the previous turn's prompt: the same system block, the same
/// tool declarations, the same history. Re-feeding all of it is how this started - `generate`
/// called `reset` and pushed the lot - and with 24 tools that is some 1,600 positions of prefill
/// before the model has seen a single new word, on every message.
///
/// The KV cache for that prefix is still sitting in the arena, still correct, because the tokens
/// that produced it have not changed. Seeking to the length of the unchanged prefix and pushing
/// only the new suffix turns a 1,600-position prefill into a 15-position one.
///
/// Rewinding is safe for exactly the reason [`Java_com_vayunmathur_library_ml_MlNative_resetGemma4`]
/// is: attention reads `[window_start, prefix]` and never past it, so the rows above `position`
/// are unreachable until something overwrites them. It is the caller's job to be sure the tokens
/// below `position` really are unchanged - native cannot check that, and a wrong seek is a model
/// answering a conversation that never happened.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_seekGemma4<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    position: jint,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
    match u32::try_from(position) {
        Ok(position) if position <= handle.position => {
            handle.position = position;
            jint::try_from(position).unwrap_or(-1)
        }
        // Forward is refused: those rows were never written, so attending over them would read
        // whatever the arena happened to hold.
        _ => {
            log(&format!("gemma4 cannot seek to {position} from {}", handle.position));
            -1
        }
    }
}

/// Start a new conversation on the same handle.
///
/// Only the position is reset. Attention reads `[window_start, prefix]`, so cache rows past the
/// new prefix are never read again and overwriting them lazily costs nothing - clearing them
/// would be a gigabyte of pointless writes between every turn.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_resetGemma4<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
    handle.position = 0;
}

/// Release the handle. Idempotent from Kotlin's side, which zeroes its field first.
///
/// # Safety
///
/// Called only by the JVM, once, with a handle from `createGemma4` that nothing else is using.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyGemma4<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this runs once, after every other call on the handle.
    drop(unsafe { Box::from_raw(handle as *mut Gemma4Handle) });
}

/// Feed soft tokens - an encoder's output - into the decoder's cache.
///
/// `embeddings` is `n * 1536` floats, one row per soft token, and it advances the position by `n`.
/// Returns the number fed, or -1. The logits are discarded: a soft token is never the last thing
/// in a prompt, because the template closes the image with `<eoi>` and opens a model turn.
///
/// This is the seam the vision tower attaches to. The decoder is text-only and gathers a row of
/// the embedding table per token; an image has no token to gather, so its rows arrive here
/// instead. See `Gemma4Handle::step_soft` for why the per-layer half still comes from the table.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_pushSoftGemma4<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    embeddings: JFloatArray<'l>,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live, and
    // that no other call on it overlaps this one.
    let handle = unsafe { &mut *(handle as *mut Gemma4Handle) };
    let fed = read_float_array(&mut env, &embeddings).and_then(|values| {
        let width = gemma4::D_MODEL as usize;
        if values.is_empty() || !values.len().is_multiple_of(width) {
            return Err(format!("{} values, which is not a whole number of {width}", values.len()));
        }
        for row in values.chunks_exact(width) {
            handle.step_soft(row, false)?;
        }
        Ok((values.len() / width) as jint)
    });
    match fed {
        Ok(count) => count,
        Err(e) => {
            log(&format!("gemma4 could not take soft tokens: {e}"));
            -1
        }
    }
}

/// Gemma 4's vision tower, which is its own `.maml` and its own graph id.
///
/// Separate from [`Gemma4Handle`] because it is optional: a device that never sends an image never
/// downloads it, and a handle that failed to build must not take the assistant down with it.
struct Gemma4VisionHandle {
    net: Reshaped<gemma4_vision::Mode>,
    /// Retained for the two position tables, which are gathered on the host.
    weights: Streamed,
}

fn gemma4_vision_plan(offsets: &Offsets, mode: gemma4_vision::Mode) -> Result<Plan, String> {
    gemma4_vision::build(offsets, mode)
}

/// Bring up the vision tower from its `.maml`. Returns 0 on failure, having logged why.
///
/// # Safety
///
/// Called only by the JVM, with a descriptor the caller detached and nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createGemma4Vision<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
) -> jlong {
    if fd < 0 {
        log(&format!("the gemma4 vision tower is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes it
    // on drop including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_gemma4_vision(file, offset, length) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("the gemma4 vision tower is unavailable: {e}"));
            0
        }
    }
}

fn build_gemma4_vision(file: File, offset: jlong, length: jlong) -> Result<Gemma4VisionHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::GEMMA4_VISION)?;
    if weights.len() != gemma4_vision::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), gemma4_vision::TENSORS));
    }
    // Recorded at the grid a square image resolves to. `at` re-records when a later image has a
    // different aspect ratio, which against sixteen layers over a couple of thousand patches
    // costs nothing worth avoiding.
    let start = gemma4_vision::Grid::for_image(1, 1, gemma4_vision::DEFAULT_SOFT_TOKENS)?;
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        gemma4_vision::Mode::Image(start),
        gemma4_vision_plan,
    )?;
    Ok(Gemma4VisionHandle { net, weights })
}

/// The pixel size an image of `width x height` must be resized to, as `[width, height]`.
///
/// Kotlin does the resize - it is bitmap work the platform does better, as it is for TinyCLIP -
/// but it cannot choose the size, because the target is the reference preprocessor's
/// aspect-ratio-preserving fit to a patch budget and getting it wrong by one block changes the
/// number of soft tokens. So the runtime decides and Kotlin obeys.
///
/// Returns null if the budget or the image is one no grid exists for.
///
/// # Safety
///
/// Called only by the JVM.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_gemma4VisionSize<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    width: jint,
    height: jint,
    soft_tokens: jint,
) -> jintArray {
    let null = std::ptr::null_mut();
    let sized = u32::try_from(width)
        .ok()
        .zip(u32::try_from(height).ok())
        .zip(u32::try_from(soft_tokens).ok());
    let Some(((width, height), soft_tokens)) = sized else {
        return null;
    };
    match gemma4_vision::Grid::for_image(width, height, soft_tokens) {
        Ok(grid) => {
            let (w, h) = grid.pixels();
            match new_int_array(&mut env, &[w as i32, h as i32]) {
                Ok(array) => array,
                Err(e) => {
                    log(&format!("cannot return a vision size: {e}"));
                    null
                }
            }
        }
        Err(e) => {
            log(&format!("no vision grid for {width}x{height}: {e}"));
            null
        }
    }
}

/// Encode one image into soft tokens: `[n, 1536]` flattened, or null.
///
/// `pixels` is ARGB_8888 at exactly the size [`Java_com_vayunmathur_library_ml_MlNative_gemma4VisionSize`]
/// asked for. The result goes straight to `pushSoftGemma4`, unscaled - the reference scatters the
/// tower's output into the decoder's embeddings as it is, while text embeddings carry a
/// `sqrt(hidden_size)` the converter folded into the table. Scaling these to match would be
/// wrong in a way nothing downstream would flag.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4Vision`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_encodeImageGemma4<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pixels: JIntArray<'l>,
    width: jint,
    height: jint,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4Vision` and is still live.
    // It is `&mut` because a new aspect ratio re-records the net, and Kotlin serialises calls.
    let handle = unsafe { &mut *(handle as *mut Gemma4VisionHandle) };
    let encoded = read_int_array(&mut env, &pixels)
        .and_then(|values| run_gemma4_vision(handle, &values, width, height));
    match encoded.and_then(|values| new_float_array(&mut env, &values)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("the gemma4 vision tower failed: {e}"));
            null
        }
    }
}

fn run_gemma4_vision(
    handle: &mut Gemma4VisionHandle,
    pixels: &[i32],
    width: jint,
    height: jint,
) -> Result<Vec<f32>, String> {
    let (Ok(width), Ok(height)) = (u32::try_from(width), u32::try_from(height)) else {
        return Err(format!("an image of {width}x{height}"));
    };
    if !width.is_multiple_of(gemma4_vision::PATCH) || !height.is_multiple_of(gemma4_vision::PATCH) {
        return Err(format!("{width}x{height} is not a whole number of patches"));
    }
    let grid = gemma4_vision::Grid::new(height / gemma4_vision::PATCH, width / gemma4_vision::PATCH)?;
    let inputs = gemma4_vision::prepare(&handle.weights.reader(), grid, pixels)?;
    let mode = gemma4_vision::Mode::Image(grid);
    let out = handle
        .net
        .at(mode)?
        .infer_raw_many(&[&inputs[0], &inputs[1], &inputs[2]])?;
    let features = one_output(out)?;
    let tokens = grid.soft_tokens() as usize;
    let width = gemma4_vision::OUT_DIM as usize;
    if features.len() != tokens * width {
        return Err(format!("{} values, not {}", features.len(), tokens * width));
    }
    // The plan writes `[1536, 1, tokens]`; the decoder reads one soft token at a time, so this
    // hands back `[tokens, 1536]`.
    Ok(transpose(&features, width, tokens))
}

/// Release the vision tower.
///
/// # Safety
///
/// Called only by the JVM, once, with a handle from `createGemma4Vision` that nothing else uses.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyGemma4Vision<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this runs once, after every other call on the handle.
    drop(unsafe { Box::from_raw(handle as *mut Gemma4VisionHandle) });
}

/// Gemma 4's audio tower, which is its own `.maml` and its own graph id.
///
/// Separate from [`Gemma4Handle`] for the reason the vision tower is: optional, separately
/// downloaded, and a failure here leaves the assistant answering without sound rather than not
/// answering.
///
/// # This one owns a front end, which the vision tower did not
///
/// The vision tower takes patches Kotlin already produced. This takes a **waveform**, and the
/// log-mel spectrogram between the two is [`crate::logmel`] - reference-verified, and until now
/// with no caller. It lives in the handle rather than being built per call because it holds the
/// Hann window, the 128-channel filter bank and the transform's twiddle tables, none of which
/// depend on the clip.
struct Gemma4AudioHandle {
    net: Reshaped<gemma4_audio::Mode>,
    /// The log-mel front end. Stateful only in its scratch buffers; one clip at a time.
    mel: crate::logmel::LogMel,
}

fn gemma4_audio_plan(offsets: &Offsets, mode: gemma4_audio::Mode) -> Result<Plan, String> {
    gemma4_audio::build(offsets, mode)
}

/// Bring up the audio tower from its `.maml`. Returns 0 on failure, having logged why.
///
/// # Safety
///
/// Called only by the JVM, with a descriptor the caller detached and nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createGemma4Audio<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
) -> jlong {
    if fd < 0 {
        log(&format!("the gemma4 audio tower is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes it
    // on drop including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_gemma4_audio(file, offset, length) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("the gemma4 audio tower is unavailable: {e}"));
            0
        }
    }
}

fn build_gemma4_audio(file: File, offset: jlong, length: jlong) -> Result<Gemma4AudioHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::GEMMA4_AUDIO)?;
    if weights.len() != gemma4_audio::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), gemma4_audio::TENSORS));
    }
    // Recorded at the cap. `at` re-records per clip length, and unlike the vision tower's grid
    // there is only one axis to vary, so most conversations settle on a handful of lengths.
    // Recording at the longest means the first short clip re-records downward rather than the
    // arena having to grow.
    let longest = crate::logmel::frame_count(gemma4_audio::MAX_SAMPLES) as u32;
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        gemma4_audio::Mode::Clip { frames: longest },
        gemma4_audio_plan,
    )?;
    Ok(Gemma4AudioHandle { net, mel: crate::logmel::LogMel::new() })
}

/// Encode one clip into soft tokens: `[n, 1536]` flattened, or null.
///
/// `samples` is **16 kHz mono** in roughly `-1.0..1.0`. The front end has no gain of its own, so
/// the scale it arrives in is the scale the tower sees. The result goes straight to
/// `pushSoftGemma4`, unscaled, for the reason the vision tower's does.
///
/// # What this does to the waveform, and what it deliberately does not
///
/// Truncates to [`gemma4_audio::MAX_SAMPLES`] - thirty seconds, the reference's own cap and what
/// makes the token count bounded. It does **not** pad to a multiple of 128 samples. The reference
/// does, so a batch stacks, and then spends a validity mask through the whole tower undoing it;
/// this runtime records a plan per frame count and passes the clip at its true length. Measured
/// bit-identical against the export over 68 configurations. See `nets::gemma4_audio::prepare`.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4Audio`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_encodeAudioGemma4<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    samples: JFloatArray<'l>,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4Audio` and is still live.
    // `&mut` because a new clip length re-records the net, and Kotlin serialises calls.
    let handle = unsafe { &mut *(handle as *mut Gemma4AudioHandle) };
    let encoded = read_float_array(&mut env, &samples)
        .and_then(|waveform| run_gemma4_audio(handle, &waveform));
    match encoded.and_then(|values| new_float_array(&mut env, &values)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("the gemma4 audio tower failed: {e}"));
            null
        }
    }
}

fn run_gemma4_audio(handle: &mut Gemma4AudioHandle, waveform: &[f32]) -> Result<Vec<f32>, String> {
    // The cap is the reference's, and it is what bounds the arena at 49.8 MiB. Truncating here
    // rather than refusing is deliberate: a caller who hands over a minute of audio wants the
    // first thirty seconds encoded, not an error.
    let capped = &waveform[..waveform.len().min(gemma4_audio::MAX_SAMPLES)];
    let frames = crate::logmel::frame_count(capped.len());
    let count = u32::try_from(frames).map_err(|_| format!("{frames} mel frames"))?;
    let tokens = gemma4_audio::tokens(count);
    if tokens < gemma4_audio::MIN_TOKENS {
        return Err(format!(
            "{} samples is {frames} mel frames and {tokens} soft tokens, under the {} the \
             attention band needs - about {} ms of audio",
            capped.len(),
            gemma4_audio::MIN_TOKENS,
            capped.len() * 1000 / crate::logmel::SAMPLE_RATE as usize
        ));
    }

    let mut mel = Vec::new();
    let produced = handle.mel.spectrogram(capped, &mut mel);
    if produced != frames {
        return Err(format!("the front end made {produced} frames, not {frames}"));
    }
    let input = gemma4_audio::prepare(&mel, count)?;
    let out = handle
        .net
        .at(gemma4_audio::Mode::Clip { frames: count })?
        .infer_raw_many(&[&input])?;
    let features = one_output(out)?;
    let width = gemma4_audio::OUT_DIM as usize;
    let rows = tokens as usize;
    if features.len() != rows * width {
        return Err(format!("{} values, not {}", features.len(), rows * width));
    }
    // The plan writes `[1536, 1, tokens]`; the decoder reads one soft token at a time, so this
    // hands back `[tokens, 1536]`.
    Ok(transpose(&features, width, rows))
}

/// Release the audio tower.
///
/// # Safety
///
/// Called only by the JVM, once, with a handle from `createGemma4Audio` that nothing else uses.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyGemma4Audio<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this runs once, after every other call on the handle.
    drop(unsafe { Box::from_raw(handle as *mut Gemma4AudioHandle) });
}
