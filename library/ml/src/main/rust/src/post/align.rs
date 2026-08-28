//! Expanding a per-phoneme prior to per-frame, and sampling it.
//!
//! The step between VITS's duration predictor and its flow. The encoder produced a
//! distribution per *phoneme*; the vocoder needs one per *frame*, and the durations say how
//! many frames each phoneme lasts.
//!
//! # The attention matrix is a gather
//!
//! The export builds this as a `[frames, phonemes]` matrix and multiplies by it — `Range`,
//! `CumSum`, `Less`, a `Pad`, a `Sub` and two `MatMul`s. But the matrix is the output of
//! `generate_path`, which is one-hot by construction: durations are whole numbers and
//! monotonic, so each phoneme owns a contiguous run of frames and every frame belongs to
//! exactly one phoneme. A one-hot matrix multiply is a gather, so this is a walk over the
//! frames with a cursor over the phonemes.
//!
//! Worth stating because the matrix form is quadratic — at 400 phonemes and 2000 frames it is
//! 800,000 entries of which 2000 are non-zero, and the two multiplies against it are 192
//! channels deep.

/// Where each phoneme's frames start and end, and the total.
///
/// Returned rather than recomputed by the caller so the frame count and the expansion cannot
/// disagree about the length of the utterance.
pub struct Alignment {
    /// One entry per frame: the index of the phoneme it belongs to.
    pub phoneme_of: Vec<u32>,
}

impl Alignment {
    /// Frames in the whole utterance.
    pub fn frames(&self) -> usize {
        self.phoneme_of.len()
    }
}

/// Assign every frame to a phoneme, given each phoneme's whole-frame duration.
///
/// A duration of zero is skipped rather than refused: [`super::duration::frames`] floors at
/// one, so a zero can only arrive from a caller that built durations some other way, and
/// dropping the phoneme is what the export's `cumsum` does with it anyway.
pub fn plan(durations: &[u32]) -> Result<Alignment, String> {
    if durations.is_empty() {
        return Err("an alignment over no phonemes".into());
    }
    let total: u64 = durations.iter().map(|&d| d as u64).sum();
    if total == 0 {
        return Err("an alignment whose durations are all zero".into());
    }
    if total > u32::MAX as u64 {
        return Err(format!("{total} frames is longer than an index can hold"));
    }
    let mut phoneme_of = Vec::with_capacity(total as usize);
    for (index, &duration) in durations.iter().enumerate() {
        for _ in 0..duration {
            phoneme_of.push(index as u32);
        }
    }
    Ok(Alignment { phoneme_of })
}

/// Expand the encoder's prior to frame rate and sample it.
///
/// `stats` is the encoder's `[2 * channels, phonemes]` output: the mean in the first half and
/// the log standard deviation in the second. `noise` is `[channels, frames]` of standard
/// normal, and `noise_scale` is the voice's own setting.
///
/// Returns `[channels, frames]`, which is what the flow consumes.
pub fn sample(
    stats: &[f32],
    phonemes: usize,
    alignment: &Alignment,
    noise: &[f32],
    noise_scale: f32,
) -> Result<Vec<f32>, String> {
    if phonemes == 0 {
        return Err("a prior over no phonemes".into());
    }
    if !stats.len().is_multiple_of(2 * phonemes) {
        return Err(format!(
            "{} statistics is not two halves of {phonemes} phonemes",
            stats.len()
        ));
    }
    let channels = stats.len() / (2 * phonemes);
    let frames = alignment.frames();
    if noise.len() != channels * frames {
        return Err(format!(
            "{} noise values for {channels} channels over {frames} frames",
            noise.len()
        ));
    }

    let mut out = vec![0.0f32; channels * frames];
    for channel in 0..channels {
        let mean_row = channel * phonemes;
        // The log standard deviation sits `channels` rows further on, because the encoder
        // concatenates the two halves rather than interleaving them.
        let deviation_row = (channels + channel) * phonemes;
        for (frame, &phoneme) in alignment.phoneme_of.iter().enumerate() {
            let at = phoneme as usize;
            if at >= phonemes {
                return Err(format!("frame {frame} points at phoneme {at} of {phonemes}"));
            }
            let mean = stats[mean_row + at];
            let deviation = stats[deviation_row + at];
            out[channel * frames + frame] =
                mean + noise[channel * frames + frame] * deviation.exp() * noise_scale;
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn each_phoneme_owns_a_contiguous_run_of_frames() {
        let got = plan(&[3, 1, 2]).expect("plans");
        assert_eq!(got.phoneme_of, vec![0, 0, 0, 1, 2, 2]);
        assert_eq!(got.frames(), 6);
    }

    #[test]
    fn a_zero_duration_phoneme_takes_no_frames() {
        // Not an error: `duration::frames` floors at one, so a zero can only come from
        // elsewhere, and the export's cumulative sum drops it the same way.
        let got = plan(&[2, 0, 1]).expect("plans");
        assert_eq!(got.phoneme_of, vec![0, 0, 2]);
    }

    #[test]
    fn an_empty_or_silent_utterance_is_refused() {
        assert!(plan(&[]).is_err());
        assert!(plan(&[0, 0]).is_err());
    }

    #[test]
    fn sampling_repeats_a_phonemes_statistics_across_its_frames() {
        // Two channels, two phonemes, durations 2 and 1. Zero noise, so the answer is the
        // mean gathered to frame rate and nothing else — which is what pins the *gather*
        // rather than the sampling.
        let stats = vec![
            1.0, 2.0, // channel 0 mean, per phoneme
            10.0, 20.0, // channel 1 mean
            0.0, 0.0, // channel 0 log deviation
            0.0, 0.0, // channel 1 log deviation
        ];
        let alignment = plan(&[2, 1]).expect("plans");
        let got = sample(&stats, 2, &alignment, &[0.0; 6], 1.0).expect("samples");
        assert_eq!(got, vec![1.0, 1.0, 2.0, 10.0, 10.0, 20.0]);
    }

    #[test]
    fn sampling_scales_the_noise_by_the_exponentiated_deviation() {
        // One channel, one phoneme, three frames. `exp(log_deviation)` is the standard
        // deviation, and `noise_scale` multiplies it again — the export applies both.
        let stats = vec![5.0, 0.0];
        let alignment = plan(&[3]).expect("plans");
        let got = sample(&stats, 1, &alignment, &[1.0, -1.0, 2.0], 0.5).expect("samples");
        // deviation = exp(0) = 1, so each frame is 5 + noise * 1 * 0.5.
        assert_eq!(got, vec![5.5, 4.5, 6.0]);

        // A non-zero log deviation is exponentiated, not used directly.
        let stats = vec![0.0, (2.0f32).ln()];
        let got = sample(&stats, 1, &alignment, &[1.0, 1.0, 1.0], 1.0).expect("samples");
        for value in got {
            assert!((value - 2.0).abs() < 1e-6, "{value}");
        }
    }

    #[test]
    fn the_two_halves_of_the_statistics_are_not_interleaved() {
        // The encoder concatenates the mean and the log deviation, so channel `c`'s deviation
        // is row `channels + c` and not row `2c + 1`. Interleaving them would read one
        // channel's deviation as another's and still produce plausible audio.
        let stats = vec![
            0.0, // channel 0 mean
            0.0, // channel 1 mean
            (3.0f32).ln(),
            (7.0f32).ln(),
        ];
        let alignment = plan(&[1]).expect("plans");
        let got = sample(&stats, 1, &alignment, &[1.0, 1.0], 1.0).expect("samples");
        assert!((got[0] - 3.0).abs() < 1e-5, "{got:?}");
        assert!((got[1] - 7.0).abs() < 1e-5, "{got:?}");
    }

    #[test]
    fn a_noise_buffer_of_the_wrong_length_is_refused() {
        let stats = vec![0.0, 0.0];
        let alignment = plan(&[4]).expect("plans");
        let error = sample(&stats, 1, &alignment, &[0.0; 3], 1.0).expect_err("short noise");
        assert!(error.contains("3 noise values"), "{error}");
    }
}
