//! Text to a waveform: the whole of Piper, in order.
//!
//! # The pipeline
//!
//! ```text
//! text -> ids            post::phonemes, a dictionary lookup
//! ids  -> stats, hidden  nets::vits_enc          on the GPU
//! hidden -> durations    post::duration          on the CPU
//! stats + durations -> prior   post::align       on the CPU
//! prior -> latent        nets::vits_flow         on the GPU
//! latent -> audio        nets::vits_dec          on the GPU, in chunks
//! ```
//!
//! # Why the networks arrive as closures
//!
//! The same argument as [`super::ocr`]: everything between them is arithmetic on a few
//! hundred numbers, and all of it is testable on the host if the Vulkan parts are injected.
//! A function owning three `Net`s could only be exercised on a device.
//!
//! # Chunking the vocoder
//!
//! The vocoder's arena grows linearly with the utterance — 7.37 MiB per second of audio,
//! measured — so a ten-second sentence in one pass would want about 74 MiB. It is run over
//! [`CHUNK_FRAMES`] at a time instead.
//!
//! The overlap is *exact* rather than approximate, and that is a property of the network
//! rather than a hope: the vocoder is convolutional with a bounded receptive field, so a
//! sample depends only on frames within [`RECEPTIVE_FRAMES`] of it. Give each chunk that much
//! context on both sides and discard the corresponding audio, and every kept sample is
//! bit-identical to the one the whole-utterance pass would have produced.

use crate::nets::{vits_dec, vits_enc};

/// Latent frames per vocoder pass. About two seconds of audio at 16 kHz.
pub const CHUNK_FRAMES: usize = 128;

/// Frames of context each chunk needs on either side for its output to be exact.
///
/// The vocoder's widest kernel is 7 taps at dilation 12, so 72 samples of reach per residual
/// block, over three upsampling stages of 8, 8 and 4. Working that back to input frames and
/// rounding generously: 16 frames is over four times the true reach, and the cost of being
/// generous is four extra frames of discarded audio per chunk.
pub const RECEPTIVE_FRAMES: usize = 16;

/// One synthesised utterance.
#[derive(Debug)]
pub struct Speech {
    /// Mono samples in `-1..1`.
    pub samples: Vec<f32>,
    /// Sample rate, from the voice.
    pub sample_rate: u32,
    /// Frames the vocoder produced, before trimming.
    pub frames: usize,
}

/// The scales a voice's `config.json` carries.
pub struct Scales {
    /// How much noise to add to the prior. `noise_scale`.
    pub noise: f32,
    /// Speaking rate. `length_scale`; above one is slower.
    pub length: f32,
    /// How much noise the duration predictor samples. `noise_w`.
    pub duration_noise: f32,
}

impl Default for Scales {
    /// Piper's own defaults, from `en_GB-alan-low.onnx.json`.
    fn default() -> Self {
        Scales { noise: 0.667, length: 1.0, duration_noise: 0.8 }
    }
}

/// Everything the pipeline needs that is not a network.
pub struct Request<'a> {
    /// Phoneme ids from [`super::phonemes::to_ids`].
    pub ids: &'a [u8],
    /// The voice's inference scales.
    pub scales: &'a Scales,
    /// Output sample rate, carried so a caller cannot play at the wrong pitch.
    pub sample_rate: u32,
}

/// The three Vulkan stages, as closures over their plans.
pub trait Networks {
    /// `ids` to `(stats, hidden)` — the prior's statistics and the state the duration
    /// predictor conditions on.
    fn encode(&mut self, ids: &[f32]) -> Result<(Vec<f32>, Vec<f32>), String>;
    /// The prior to the latent the vocoder decodes, at whatever frame count it is given.
    fn flow(&mut self, prior: &[f32], frames: usize) -> Result<Vec<f32>, String>;
    /// A latent chunk to audio. Always exactly [`CHUNK_FRAMES`] plus context.
    fn vocode(&mut self, latent: &[f32], frames: usize) -> Result<Vec<f32>, String>;
}

/// Randomness, injected so a test can pin every draw.
pub trait Noise {
    /// `count` standard normal values.
    fn normal(&mut self, count: usize) -> Vec<f32>;
}

/// Synthesise one utterance.
///
/// `durations` is handed in rather than computed here because the duration predictor needs the
/// voice's weights, which the caller already holds; see [`super::duration::log_durations`].
pub fn synthesise(
    request: &Request,
    durations: &[u32],
    stats: &[f32],
    networks: &mut dyn Networks,
    noise: &mut dyn Noise,
) -> Result<Speech, String> {
    let phonemes = request.ids.len();
    let alignment = super::align::plan(durations)?;
    let frames = alignment.frames();
    let channels = vits_enc::D_MODEL as usize;

    let prior_noise = noise.normal(channels * frames);
    let prior = super::align::sample(
        stats,
        phonemes,
        &alignment,
        &prior_noise,
        request.scales.noise,
    )?;

    let latent = networks.flow(&prior, frames)?;
    if latent.len() != channels * frames {
        return Err(format!(
            "the flow returned {} values for {channels} channels over {frames} frames",
            latent.len()
        ));
    }

    let samples = vocode_in_chunks(&latent, channels, frames, networks)?;
    Ok(Speech { samples, sample_rate: request.sample_rate, frames })
}

/// Run the vocoder over the latent a chunk at a time, with exact overlap.
///
/// Each pass sees `RECEPTIVE_FRAMES` of context on both sides, and the audio belonging to that
/// context is discarded. Frames before the start and past the end are clamped to the edge,
/// which is what a single whole-utterance pass would see at its own boundaries.
fn vocode_in_chunks(
    latent: &[f32],
    channels: usize,
    frames: usize,
    networks: &mut dyn Networks,
) -> Result<Vec<f32>, String> {
    let per_frame = vits_dec::SAMPLES_PER_FRAME as usize;
    let mut samples = Vec::with_capacity(frames * per_frame);
    let mut start = 0usize;
    while start < frames {
        let end = (start + CHUNK_FRAMES).min(frames);
        let lead = start.min(RECEPTIVE_FRAMES);
        let trail = (frames - end).min(RECEPTIVE_FRAMES);
        let span = (end - start) + lead + trail;

        // Gather the window channel by channel: the latent is `[channels, frames]`, so a
        // frame range is a stride, not a contiguous block.
        let mut window = vec![0.0f32; channels * span];
        for channel in 0..channels {
            let from = channel * frames + start - lead;
            let to = channel * span;
            let source = latent
                .get(from..from + span)
                .ok_or("the latent is shorter than the chunk it was asked for")?;
            window[to..to + span].copy_from_slice(source);
        }

        let audio = networks.vocode(&window, span)?;
        if audio.len() != span * per_frame {
            return Err(format!(
                "the vocoder returned {} samples for {span} frames",
                audio.len()
            ));
        }
        // Discard the context's audio, keeping only what this chunk owns.
        let keep_from = lead * per_frame;
        let keep_to = audio.len() - trail * per_frame;
        samples.extend_from_slice(&audio[keep_from..keep_to]);
        start = end;
    }
    Ok(samples)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A vocoder that returns each frame's index, so the reassembly is checkable by eye.
    ///
    /// The latent it is handed carries the frame index in channel 0, and it emits that index
    /// repeated for every sample of the frame. So the concatenated output should be
    /// `0, 0, ..., 1, 1, ..., n-1` with no gaps, repeats or shifts.
    struct Counting {
        calls: Vec<usize>,
    }

    impl Networks for Counting {
        fn encode(&mut self, _ids: &[f32]) -> Result<(Vec<f32>, Vec<f32>), String> {
            unreachable!("the fixture drives the flow and the vocoder only")
        }

        fn flow(&mut self, prior: &[f32], _frames: usize) -> Result<Vec<f32>, String> {
            Ok(prior.to_vec())
        }

        fn vocode(&mut self, latent: &[f32], frames: usize) -> Result<Vec<f32>, String> {
            self.calls.push(frames);
            let per_frame = vits_dec::SAMPLES_PER_FRAME as usize;
            let mut out = Vec::with_capacity(frames * per_frame);
            // Channel 0 of each frame is its index in the whole utterance.
            for &marker in latent.iter().take(frames) {
                out.extend(std::iter::repeat_n(marker, per_frame));
            }
            Ok(out)
        }
    }

    struct Zero;

    impl Noise for Zero {
        fn normal(&mut self, count: usize) -> Vec<f32> {
            vec![0.0; count]
        }
    }

    /// A latent whose channel 0 holds the frame index.
    fn marked(channels: usize, frames: usize) -> Vec<f32> {
        let mut out = vec![0.0f32; channels * frames];
        for (frame, slot) in out.iter_mut().take(frames).enumerate() {
            *slot = frame as f32;
        }
        out
    }

    fn chunked(frames: usize) -> (Vec<f32>, Vec<usize>) {
        let channels = 4;
        let latent = marked(channels, frames);
        let mut networks = Counting { calls: Vec::new() };
        let samples =
            vocode_in_chunks(&latent, channels, frames, &mut networks).expect("vocodes");
        (samples, networks.calls)
    }

    #[test]
    fn a_short_utterance_is_one_chunk_with_no_context() {
        let frames = 10;
        let (samples, calls) = chunked(frames);
        // Nothing either side to take context from, so the pass is exactly the utterance.
        assert_eq!(calls, vec![frames]);
        let per_frame = vits_dec::SAMPLES_PER_FRAME as usize;
        assert_eq!(samples.len(), frames * per_frame);
        for frame in 0..frames {
            assert_eq!(samples[frame * per_frame], frame as f32);
        }
    }

    #[test]
    fn a_long_utterance_reassembles_without_a_gap_or_a_repeat() {
        // Three chunks' worth, so the middle one has context on both sides — the case where
        // an off-by-one in the trim would duplicate or drop a frame's audio.
        let frames = CHUNK_FRAMES * 2 + 40;
        let (samples, calls) = chunked(frames);
        assert_eq!(calls.len(), 3, "{calls:?}");
        let per_frame = vits_dec::SAMPLES_PER_FRAME as usize;
        assert_eq!(samples.len(), frames * per_frame, "total length");
        // Every frame appears exactly once, in order, for its whole span.
        for frame in 0..frames {
            for sample in 0..per_frame {
                let got = samples[frame * per_frame + sample];
                assert_eq!(got, frame as f32, "frame {frame} sample {sample}");
            }
        }
    }

    #[test]
    fn the_middle_chunk_is_given_context_on_both_sides() {
        let frames = CHUNK_FRAMES * 3;
        let (_, calls) = chunked(frames);
        assert_eq!(calls.len(), 3);
        // First: no lead, full trail. Middle: both. Last: lead only.
        assert_eq!(calls[0], CHUNK_FRAMES + RECEPTIVE_FRAMES);
        assert_eq!(calls[1], CHUNK_FRAMES + 2 * RECEPTIVE_FRAMES);
        assert_eq!(calls[2], CHUNK_FRAMES + RECEPTIVE_FRAMES);
    }

    #[test]
    fn a_chunk_boundary_exactly_on_the_end_does_not_emit_an_empty_pass() {
        let frames = CHUNK_FRAMES * 2;
        let (samples, calls) = chunked(frames);
        assert_eq!(calls.len(), 2, "{calls:?}");
        assert_eq!(samples.len(), frames * vits_dec::SAMPLES_PER_FRAME as usize);
    }

    #[test]
    fn synthesis_runs_the_whole_pipeline_and_reports_its_length() {
        let channels = vits_enc::D_MODEL as usize;
        let phonemes = 3;
        let durations = vec![2u32, 3, 1];
        let frames: usize = durations.iter().map(|&d| d as usize).sum();
        // Stats are `[2 * channels, phonemes]`: means then log deviations.
        let stats = vec![0.0f32; 2 * channels * phonemes];
        let mut networks = Counting { calls: Vec::new() };
        let request = Request {
            ids: &[1, 5, 0, 6, 0, 2],
            scales: &Scales::default(),
            sample_rate: vits_dec::SAMPLE_RATE,
        };
        // `ids` is only used for its length here, and the fixture's phoneme count is 3.
        let request = Request { ids: &request.ids[0..phonemes], ..request };
        let got = synthesise(&request, &durations, &stats, &mut networks, &mut Zero)
            .expect("synthesises");
        assert_eq!(got.frames, frames);
        assert_eq!(got.sample_rate, vits_dec::SAMPLE_RATE);
        assert_eq!(got.samples.len(), frames * vits_dec::SAMPLES_PER_FRAME as usize);
    }

    #[test]
    fn a_flow_that_returns_the_wrong_length_is_refused() {
        struct Short;
        impl Networks for Short {
            fn encode(&mut self, _: &[f32]) -> Result<(Vec<f32>, Vec<f32>), String> {
                unreachable!()
            }
            fn flow(&mut self, _: &[f32], _: usize) -> Result<Vec<f32>, String> {
                Ok(vec![0.0; 3])
            }
            fn vocode(&mut self, _: &[f32], _: usize) -> Result<Vec<f32>, String> {
                unreachable!()
            }
        }
        let channels = vits_enc::D_MODEL as usize;
        let stats = vec![0.0f32; 2 * channels];
        let request = Request {
            ids: &[1],
            scales: &Scales::default(),
            sample_rate: vits_dec::SAMPLE_RATE,
        };
        let error = synthesise(&request, &[4], &stats, &mut Short, &mut Zero)
            .expect_err("a short flow");
        assert!(error.contains("the flow returned 3"), "{error}");
    }
}
